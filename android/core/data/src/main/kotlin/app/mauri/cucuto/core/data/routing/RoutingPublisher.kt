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
import java.io.IOException
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

    /**
     * The module has [generation], covering [routes] keys and [voices] voices. With [clipHapticsLeftOut], the full
     * table was over [RoutingContract.MAX_ENCODED_CHARS], so it went without clip envelopes: sounds still change,
     * but `auto` haptics keep WhatsApp's vibration. With [throughProvider], there was no framework service: the table
     * is only in the app, and the module pulls it from the app's provider when nudged (ADR 0048).
     */
    data class Published(
        val generation: Long,
        val routes: Int,
        val voices: Int,
        val encodedChars: Int,
        val clipHapticsLeftOut: Boolean = false,
        val throughProvider: Boolean = false,
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
 * Every new table also goes to [store], the app's own copy, and every publication ends with a nudge to the module,
 * with or without the framework service. Without the service (LSPosed sometimes drops it after an app update, ADR
 * 0043), the store is the only copy: the module pulls it from the app's event provider when nudged (ADR 0048).
 *
 * After every publication through the service, [ClipMirror.retain] deletes mirrored clips the module can no longer
 * play. A publication without the service retains nothing: the module may still run an older table.
 *
 * Call [start] once from `Application.onCreate` (libxposed delivers the service binder at process start).
 */
@Singleton
class RoutingPublisher
    @Inject
    constructor(
        private val source: RoutingTableSource,
        private val sink: RoutingSink,
        private val store: PublishedTableStore,
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
                                _state.value = publish(table, throughService = true)
                            }

                            is SinkConnection.Unsupported -> {
                                // The module may still pull the table; the screens say what the framework lacks.
                                publish(table, throughService = false)
                                _state.value = PublishState.Unsupported(connection.framework)
                            }

                            SinkConnection.Disconnected -> {
                                // No service yet, or LSPosed dropped it after an update: the module pulls the table
                                // from the app's provider. Republished through the service as soon as it connects.
                                _state.value = publish(table, throughService = false)
                            }
                        }
                    }
            }
        }

        private suspend fun publish(
            table: RoutingTable,
            throughService: Boolean,
        ): PublishState =
            try {
                val local = RoutingCodec.decodeOrNull(store.read())
                val remote = if (throughService) RoutingCodec.decodeOrNull(sink.read()) else null
                // Generations only grow, whichever copy saw the last publication.
                val generation = maxOf(clock.now(), (remote?.generation ?: 0) + 1, (local?.generation ?: 0) + 1)
                var stamped = table.copy(generation = generation)
                var encoded = RoutingCodec.encode(stamped)
                var leftOut = false
                if (encoded.length > RoutingContract.MAX_ENCODED_CHARS) {
                    // Sounds matter more than vibrations: try again without the clip envelopes (issue #98).
                    stamped = stamped.withoutClipHaptics()
                    encoded = RoutingCodec.encode(stamped)
                    leftOut = true
                    Log.w(TAG, "Routing table too large with clip haptics; publishing without them")
                }
                if (encoded.length > RoutingContract.MAX_ENCODED_CHARS) {
                    Log.w(TAG, "Routing table too large to publish: ${encoded.length} chars")
                    PublishState.TooLarge(encoded.length)
                } else {
                    val published = if (throughService) remote else local
                    if (published != null && published.copy(generation = 0) == stamped.copy(generation = 0)) {
                        unchanged(published, local, throughService, leftOut)
                    } else {
                        val stored = keep(encoded, generation)
                        try {
                            if (throughService) {
                                sink.write(encoded, generation)
                            } else {
                                check(stored) { "Saving the routing table failed" }
                            }
                        } finally {
                            // Also after a failed write: the module may still find the table in the other copy.
                            sink.nudge(generation)
                        }
                        if (throughService) mirror.retain(stamped)
                        stamped.toState(encoded.length, leftOut, throughProvider = !throughService)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Publishing the routing table failed", e)
                PublishState.Failed(e.message ?: e.javaClass.simpleName)
            }

        /** Writes the app's own copy. Returns false (logged) when it could not, which only matters without a service. */
        private suspend fun keep(
            encoded: String,
            generation: Long,
        ): Boolean =
            try {
                store.write(encoded, generation)
                true
            } catch (e: IOException) {
                Log.w(TAG, "Saving the routing table for the module's provider pull failed", e)
                false
            }

        /** [published] already holds this table: nothing to write or nudge, but keep the app's copy in step. */
        private suspend fun unchanged(
            published: RoutingTable,
            local: RoutingTable?,
            throughService: Boolean,
            leftOut: Boolean,
        ): PublishState {
            val encoded = RoutingCodec.encode(published)
            if (throughService) {
                // The provider must serve what the module may hold: the generation in Remote Preferences.
                if (local?.generation != published.generation) keep(encoded, published.generation)
                mirror.retain(published)
            }
            return published.toState(encoded.length, leftOut, throughProvider = !throughService)
        }

        private fun RoutingTable.toState(
            encodedChars: Int,
            clipHapticsLeftOut: Boolean,
            throughProvider: Boolean,
        ) = PublishState.Published(
            generation,
            routes.size,
            voices.size,
            encodedChars,
            clipHapticsLeftOut,
            throughProvider,
        )

        private fun RoutingTable.withoutClipHaptics() =
            copy(voices = voices.map { voice -> voice.copy(clips = voice.clips.map { it.copy(haptic = null) }) })

        private companion object {
            const val TAG = "cucuto"
        }
    }
