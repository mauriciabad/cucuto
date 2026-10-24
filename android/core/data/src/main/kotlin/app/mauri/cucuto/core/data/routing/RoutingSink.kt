package app.mauri.cucuto.core.data.routing

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.core.routing.RoutingFile
import dagger.hilt.android.qualifiers.ApplicationContext
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

    /** Publishes [encoded] and nudges the module to load it; throws when the preferences could not be written. */
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
 *
 * LSPosed stops delivering those change notifications to a module that has been running since before an app update
 * (ADR 0024), so every write also mirrors the table to a remote file and sends `system_server` a nudge broadcast;
 * the module re-reads the file when nudged. Both steps are best effort: the preferences stay the source of truth.
 */
@Singleton
class XposedRoutingSink
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : RoutingSink,
        XposedFramework,
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
            val service = checkNotNull(this@XposedRoutingSink.service) { "Xposed service not connected" }
            val committed =
                service
                    .getRemotePreferences(RoutingContract.PREFERENCES_GROUP)
                    .edit()
                    .putString(RoutingContract.KEY_TABLE, encoded)
                    .putLong(RoutingContract.KEY_GENERATION, generation)
                    .commit()
            check(committed) { "Remote Preferences commit failed" }
            if (mirror(service, encoded)) nudge()
        }

        /** Writes [encoded] to the remote file the module re-reads when nudged. Returns false on failure. */
        private fun mirror(
            service: XposedService,
            encoded: String,
        ): Boolean =
            try {
                val bytes = RoutingFile.frame(encoded)
                // The framework opens the file read-write without truncating it: overwrite, then cut the old tail.
                ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(RoutingContract.REMOTE_FILE)).use {
                    it.write(bytes)
                    it.channel.truncate(bytes.size.toLong())
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Mirroring the routing table to a remote file failed", e)
                false
            }

        /**
         * Tells `system_server` that a new table is published. Only receivers registered by the system (package
         * `android`) get it, and it carries no data: the module checks the sender's package, which this broadcast
         * shares, and reads the table from the framework.
         */
        private fun nudge() {
            try {
                val intent =
                    Intent(RoutingContract.ACTION_TABLE_PUBLISHED)
                        .setPackage(RoutingContract.SYSTEM_PACKAGE)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY or Intent.FLAG_RECEIVER_FOREGROUND)
                val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
                context.sendBroadcast(intent, null, options)
            } catch (e: Exception) {
                Log.w(TAG, "Nudging the module failed", e)
            }
        }

        override suspend fun report(): FrameworkReport? =
            withContext(Dispatchers.IO) {
                val service = service ?: return@withContext null
                FrameworkReport(
                    scope = ask("scope") { service.scope.toList() },
                    targets =
                        ask("running targets") {
                            service.runningTargets.map {
                                RunningTarget(it.uid, it.processName, TargetState.valueOf(it.state.name))
                            }
                        },
                )
            }

        /** One framework query; a dead service, an older API or any framework error reads as "unknown". */
        private inline fun <T> ask(
            what: String,
            block: () -> T,
        ): T? =
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Xposed service: reading the $what failed (${e.javaClass.simpleName})")
                null
            }

        private fun preferences() = service?.getRemotePreferences(RoutingContract.PREFERENCES_GROUP)

        private companion object {
            const val TAG = "cucuto"
        }
    }
