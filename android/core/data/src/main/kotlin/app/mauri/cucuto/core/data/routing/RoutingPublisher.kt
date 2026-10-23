package app.mauri.cucuto.core.data.routing

import android.util.Log
import app.mauri.cucuto.core.data.di.ApplicationScope
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.core.routing.RoutingTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** What the last publication did, for the status and diagnostics screens. */
sealed interface PublishState {
    /** Nothing published in this process yet (no framework connection, or no table built yet). */
    data object Idle : PublishState

    /** The framework does not support Remote Preferences; the module cannot be reached. */
    data class Unsupported(
        val framework: String,
    ) : PublishState

    /** The module has [generation], covering [routes] keys and [voices] voices. */
    data class Published(
        val generation: Long,
        val routes: Int,
        val voices: Int,
        val encodedChars: Int,
    ) : PublishState

    /** The table is over [RoutingContract.MAX_ENCODED_CHARS]; the module keeps the previous one. */
    data class TooLarge(
        val encodedChars: Int,
    ) : PublishState

    /** Writing failed; retried on the next change or reconnection. */
    data class Failed(
        val message: String,
    ) : PublishState
}

/**
 * Keeps the module's copy of the routing table in sync with the database: every time [RoutingTableSource] emits a
 * different table, or the framework (re)connects, the table is stamped with a new generation, encoded and written
 * to Remote Preferences. A table equal to what is already published is not written again, so app restarts do not
 * make the module reparse.
 *
 * After every publication, [ClipMirror.retain] deletes mirrored clips the module can no longer play.
 *
 * Call [start] once from `Application.onCreate` (libxposed delivers the service binder at process start).
 */
@Singleton
class RoutingPublisher
    @Inject
    constructor(
        private val source: RoutingTableSource,
        private val sink: RoutingSink,
        private val mirror: ClipMirror,
        private val clock: WallClock,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        private val started = AtomicBoolean(false)
        private val _state = MutableStateFlow<PublishState>(PublishState.Idle)

        val state: StateFlow<PublishState> = _state.asStateFlow()

        fun start() {
            if (!started.compareAndSet(false, true)) return
            sink.start()
            scope.launch {
                combine(source.tables, sink.connection) { table, connection -> table to connection }
                    .collectLatest { (table, connection) ->
                        when (connection) {
                            is SinkConnection.Connected -> {
                                publish(table)
                            }

                            is SinkConnection.Unsupported -> {
                                _state.value =
                                    PublishState.Unsupported(connection.framework)
                            }

                            SinkConnection.Disconnected -> {
                                // Wait: the table is published as soon as the framework connects.
                            }
                        }
                    }
            }
        }

        private suspend fun publish(table: RoutingTable) {
            try {
                val published = RoutingCodec.decodeOrNull(sink.read())
                if (published != null && published.copy(generation = 0) == table.copy(generation = 0)) {
                    _state.value = published.toState(RoutingCodec.encode(published).length)
                    mirror.retain(published)
                    return
                }
                val generation = maxOf(clock.now(), (published?.generation ?: 0) + 1)
                val stamped = table.copy(generation = generation)
                val encoded = RoutingCodec.encode(stamped)
                if (encoded.length > RoutingContract.MAX_ENCODED_CHARS) {
                    Log.w(TAG, "Routing table too large to publish: ${encoded.length} chars")
                    _state.value = PublishState.TooLarge(encoded.length)
                    return
                }
                sink.write(encoded, generation)
                _state.value = stamped.toState(encoded.length)
                mirror.retain(stamped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Publishing the routing table failed", e)
                _state.value = PublishState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }

        private fun RoutingTable.toState(encodedChars: Int) =
            PublishState.Published(generation, routes.size, voices.size, encodedChars)

        private companion object {
            const val TAG = "cucuto"
        }
    }
