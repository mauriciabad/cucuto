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

    /**
     * The encoded table in Remote Preferences, or null. When the latest table was too large for them, this is a copy
     * without clip envelopes or an older table: its generation is then below [publishedGeneration] (ADR 0054).
     */
    suspend fun read(): String?

    /** The generation of the latest publication through the service (whatever Remote Preferences hold), or null. */
    suspend fun publishedGeneration(): Long?

    /**
     * Publishes [encoded], of [generation], through the framework service: the remote file gets it and Remote
     * Preferences get [forPreferences] (the same text; a smaller copy with an older generation when [encoded] is over
     * [RoutingContract.MAX_PREFERENCES_CHARS]; or null to leave their table as it is) with [generation] as the
     * published generation. Throws when the preferences could not be written. Does not nudge: the publisher calls
     * [nudge] afterwards.
     */
    suspend fun write(
        encoded: String,
        generation: Long,
        forPreferences: String? = encoded,
    )

    /**
     * Tells the module that [generation] is published, with or without the framework service: it re-reads the
     * remote file, or pulls the table from the app's provider (ADR 0048). Best effort, never throws.
     */
    fun nudge(generation: Long)
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
 * (ADR 0024), so every write also mirrors the table to a remote file, and the publisher then sends `system_server` a
 * nudge broadcast ([nudge]); the module re-reads the file when nudged. The nudge also goes out without a service,
 * and the module then pulls the table from the app's event provider (ADR 0048). Mirror and nudge are best effort:
 * the preferences stay the source of truth, except for a table too large for them, which only the remote file and
 * the app's provider carry in full (ADR 0054); a module that misses the file pulls it from the provider.
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

        override suspend fun publishedGeneration(): Long? =
            withContext(Dispatchers.IO) {
                preferences()?.getLong(RoutingContract.KEY_GENERATION, 0L)?.takeIf { it > 0 }
            }

        override suspend fun write(
            encoded: String,
            generation: Long,
            forPreferences: String?,
        ) = withContext(Dispatchers.IO) {
            val service = checkNotNull(this@XposedRoutingSink.service) { "Xposed service not connected" }
            val editor =
                service
                    .getRemotePreferences(RoutingContract.PREFERENCES_GROUP)
                    .edit()
                    .putLong(RoutingContract.KEY_GENERATION, generation)
            if (forPreferences != null) editor.putString(RoutingContract.KEY_TABLE, forPreferences)
            check(editor.commit()) { "Remote Preferences commit failed" }
            mirror(service, encoded)
        }

        /** Writes [encoded] to the remote file the module re-reads when nudged. Best effort. */
        private fun mirror(
            service: XposedService,
            encoded: String,
        ) {
            try {
                val bytes = RoutingFile.frame(encoded)
                // The framework opens the file read-write without truncating it: overwrite, then cut the old tail.
                ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(RoutingContract.REMOTE_FILE)).use {
                    it.write(bytes)
                    it.channel.truncate(bytes.size.toLong())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mirroring the routing table to a remote file failed", e)
            }
        }

        /**
         * Tells `system_server` that a new table is published. Only receivers registered by the system (package
         * `android`) get it, and it carries only the generation: the module checks the sender's package, which this
         * broadcast shares, and reads the table from the framework's remote file or, when that does not have it,
         * from the app's provider (ADR 0048). Needs no framework service.
         */
        override fun nudge(generation: Long) {
            try {
                val intent =
                    Intent(RoutingContract.ACTION_TABLE_PUBLISHED)
                        .setPackage(RoutingContract.SYSTEM_PACKAGE)
                        .putExtra(RoutingContract.EXTRA_GENERATION, generation)
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
