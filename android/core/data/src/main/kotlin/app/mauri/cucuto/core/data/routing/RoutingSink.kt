package app.mauri.cucuto.core.data.routing

import android.util.Log
import app.mauri.cucuto.core.routing.RoutingContract
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Where the publisher writes the encoded table. Production: [XposedRoutingSink]; tests use a fake. */
interface RoutingSink {
    /** Whether a write can succeed now (the framework's service is connected and supports Remote Preferences). */
    val connection: StateFlow<SinkConnection>

    /** Starts listening for the framework. Idempotent. */
    fun start()

    /** The encoded table currently published, or null. */
    suspend fun read(): String?

    /** Publishes [encoded]; throws on failure. */
    suspend fun write(
        encoded: String,
        generation: Long,
    )
}

/** State of the link to the Xposed framework. */
sealed interface SinkConnection {
    /** No framework service yet: the module is not enabled, or LSPosed has not bound to the app. */
    data object Disconnected : SinkConnection

    /** Connected to [framework]; Remote Preferences can be written. */
    data class Connected(
        val framework: String,
        val apiVersion: Int,
    ) : SinkConnection

    /** Connected, but the framework does not offer Remote Preferences (for example an embedded framework). */
    data class Unsupported(
        val framework: String,
    ) : SinkConnection
}

/**
 * Writes the routing table to libxposed Remote Preferences through the framework service the app receives with
 * `XposedServiceHelper` (libxposed `service` 102). The framework stores the values and serves them to the module in
 * `system_server`, notifying its change listeners. Nothing is written while the module is disabled: the database
 * stays the source of truth and the table is republished as soon as the service connects.
 */
@Singleton
class XposedRoutingSink
    @Inject
    constructor() :
    RoutingSink,
        XposedServiceHelper.OnServiceListener {
        private val started = AtomicBoolean(false)
        private val _connection = MutableStateFlow<SinkConnection>(SinkConnection.Disconnected)

        @Volatile private var service: XposedService? = null

        override val connection: StateFlow<SinkConnection> = _connection.asStateFlow()

        override fun start() {
            if (started.compareAndSet(false, true)) XposedServiceHelper.registerListener(this)
        }

        override fun onServiceBind(service: XposedService) {
            val state =
                try {
                    val name = "${service.frameworkName} ${service.frameworkVersion}"
                    if (service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) {
                        SinkConnection.Unsupported(name)
                    } else {
                        SinkConnection.Connected(name, service.apiVersion)
                    }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Xposed service bound but unusable", e)
                    SinkConnection.Disconnected
                }
            this.service = if (state is SinkConnection.Connected) service else null
            _connection.value = state
        }

        override fun onServiceDied(service: XposedService) {
            if (this.service === service || this.service == null) {
                this.service = null
                _connection.value = SinkConnection.Disconnected
            }
        }

        override suspend fun read(): String? =
            withContext(Dispatchers.IO) {
                preferences()?.getString(RoutingContract.KEY_TABLE, null)
            }

        override suspend fun write(
            encoded: String,
            generation: Long,
        ) = withContext(Dispatchers.IO) {
            val preferences = checkNotNull(preferences()) { "Xposed service not connected" }
            val committed =
                preferences
                    .edit()
                    .putString(RoutingContract.KEY_TABLE, encoded)
                    .putLong(RoutingContract.KEY_GENERATION, generation)
                    .commit()
            check(committed) { "Remote Preferences commit failed" }
        }

        private fun preferences() = service?.getRemotePreferences(RoutingContract.PREFERENCES_GROUP)

        private companion object {
            const val TAG = "cucuto"
        }
    }
