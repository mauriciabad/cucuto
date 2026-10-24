package app.mauri.cucuto.xposed

import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.xposed.events.DecisionEvent
import app.mauri.cucuto.xposed.events.EventReporter
import app.mauri.cucuto.xposed.events.ModuleStatus
import app.mauri.cucuto.xposed.events.ProviderEventSender
import app.mauri.cucuto.xposed.hook.AlertHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * libxposed API 102 entry point, listed in `META-INF/xposed/java_init.list` and scoped to the system framework
 * (`system_server`) only.
 *
 * This runs inside `system_server`, so every callback is defensive: nothing may ever escape, because an exception
 * here could break notifications for the whole phone. On any failure WhatsApp simply keeps its default tone.
 *
 * Pieces (ADR 0010):
 * - [AlertHooks] swaps WhatsApp's sound and vibration on the notification path, reading [routing] from memory.
 * - [routing] follows the table the app publishes through Remote Preferences. It is attached from the background
 *   thread right after boot, never from the notification path, and retried until the framework serves it.
 * - [EventReporter] carries every decision to the app's decision log and "recent senders" feed, batched, from a
 *   background thread.
 */
class CucutoModule : XposedModule() {
    private val moduleLog = ModuleLog(sink = { priority, message, error -> writeLog(priority, message, error) })

    /** The routing table the app publishes through Remote Preferences. */
    internal val routing =
        RoutingTableHolder(
            preferences = { getRemotePreferences(RoutingContract.PREFERENCES_GROUP) },
            log = { message, error -> if (error == null) moduleLog.info(message) else moduleLog.warn(message, error) },
        )

    private var hooks: AlertHooks? = null
    private var reporter: EventReporter? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        guarded("onModuleLoaded") {
            moduleLog.info("Loaded in ${param.processName} by $frameworkName ($frameworkVersionCode), API $apiVersion")
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        guarded("onSystemServerStarting") {
            val sender = ProviderEventSender(moduleInfo = { moduleInfo() })
            val reporter =
                EventReporter(
                    sender = sender,
                    status = { status() },
                    onError = { message, error -> moduleLog.warnRateLimited("events", message, error) },
                    onQueuedListener = { event -> moduleLog.info(describe(event)) },
                    onDelivered = { count -> moduleLog.info("Delivered $count decision events to the app") },
                )
            reporter.start()
            this.reporter = reporter

            val hooks =
                AlertHooks(
                    module = this,
                    table = routing::peek,
                    report = reporter::report,
                    log = moduleLog,
                    background = { block -> reporter.post(block = block) },
                )
            hooks.install(param.classLoader)
            this.hooks = hooks
            moduleLog.info("Hooks installed: ${hooks.installed}; missing: ${hooks.missing}")
            reporter.post { hooks.warmUp() }
            // Reads the module APK's signing certificate while the file still exists (an app update replaces it).
            reporter.post(delayMillis = ATTACH_FIRST_DELAY_MILLIS) { sender.prepare() }

            attachRouting(attempt = 0)
        }
    }

    /** Attaches the routing table off the notification path, retrying while the framework is still starting. */
    private fun attachRouting(attempt: Int) {
        val reporter = reporter ?: return
        reporter.post(delayMillis = if (attempt == 0) ATTACH_FIRST_DELAY_MILLIS else ATTACH_RETRY_MILLIS) {
            if (routing.ensureAttached()) {
                moduleLog.info("Routing table attached (generation ${routing.peek().generation})")
            } else if (attempt < ATTACH_MAX_ATTEMPTS) {
                attachRouting(attempt + 1)
            } else {
                moduleLog.warn("Remote Preferences never became available; every contact keeps WhatsApp's tone")
            }
        }
    }

    private fun status(): ModuleStatus {
        val hooks = hooks
        val table = routing.peek()
        return ModuleStatus(
            bootTimeMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
            hooks = hooks?.installed.orEmpty(),
            missingHooks = hooks?.missing.orEmpty(),
            tableGeneration = table.generation,
            tableRoutes = table.routes.size,
            decisions = hooks?.decisions?.get() ?: 0,
            slowDecisions = hooks?.slowDecisions?.get() ?: 0,
            maxHookMicros = hooks?.maxMicros?.get() ?: 0,
            dropped = reporter?.dropped ?: 0,
        )
    }

    /**
     * The module's `ApplicationInfo` as LSPosed built it by parsing the APK: package name and `sourceDir` are
     * reliable, but its `uid` is not the installed app's uid (never compare uids with it).
     */
    private fun moduleInfo(): ApplicationInfo? =
        try {
            moduleApplicationInfo
        } catch (_: Throwable) {
            null
        }

    private fun writeLog(
        priority: Int,
        message: String,
        error: Throwable?,
    ) {
        if (error == null) log(priority, TAG, message) else log(priority, TAG, message, error)
    }

    private inline fun guarded(
        stage: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            // Last line of defense: report and carry on. Never rethrow inside system_server.
            try {
                Log.e(TAG, "$stage failed; WhatsApp keeps its default tone", t)
            } catch (_: Throwable) {
                // Logging itself failed; there is nothing safe left to do.
            }
        }
    }

    internal companion object {
        const val TAG = ModuleLog.TAG

        private const val ATTACH_FIRST_DELAY_MILLIS = 5_000L
        private const val ATTACH_RETRY_MILLIS = 15_000L
        private const val ATTACH_MAX_ATTEMPTS = 40

        /** One redacted line per alert for LSPosed's modules log: no keys, names or numbers. */
        fun describe(event: DecisionEvent): String =
            buildString {
                append("alert ").append(event.packageName).append(' ').append(event.outcome)
                append(" key=").append(ModuleLog.keyShape(event.conversationKey))
                append(" group=").append(event.isGroup)
                if (event.isGroup) {
                    append(" sender=").append(ModuleLog.senderShape(event.senderKey, event.senderName != null))
                }
                if (event.contactId != null) append(" contact=#").append(event.contactId)
                event.clipUri?.let { append(" clip=").append(it) }
                event.detail?.let { append(" (").append(it).append(')') }
                append(' ').append(event.hookMicros).append("us")
            }
    }
}
