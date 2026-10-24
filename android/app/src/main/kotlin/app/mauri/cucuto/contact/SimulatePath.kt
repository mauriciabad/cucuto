package app.mauri.cucuto.contact

import app.mauri.cucuto.core.data.routing.FrameworkReport
import app.mauri.cucuto.core.data.routing.SinkConnection
import app.mauri.cucuto.core.data.routing.TargetState
import app.mauri.cucuto.provider.ReceivedStatus
import app.mauri.cucuto.ui.status.StatusChecks

/** How "Simulate message" plays a chat's next alert (ADR 0025). */
sealed interface SimulatePath {
    /**
     * A real notification through the module's hook in `system_server`: the routing table the module holds, its
     * shuffle bag, the notification sound player and the vibrator, exactly as for a WhatsApp message.
     */
    data object Module : SimulatePath

    /** The in-app preview: the same table and selection rules, played by the app. [reason] says why. */
    data class InApp(
        val reason: InAppReason,
    ) : SimulatePath
}

/** Why the module cannot play a simulation right now. */
enum class InAppReason {
    /** No framework service: the module is not enabled, or LSPosed is missing. */
    ModuleOff,

    /** `system_server` runs older module code (the app was updated since boot) or none yet: reboot. */
    RebootNeeded,

    /** The module runs, but could not hook this Android build: it cannot play anything. */
    HooksMissing,

    /** LSPosed could not say which code `system_server` runs, and nothing confirms it this boot. */
    NotConfirmed,
}

/** What [SimulatePaths.choose] reads, as plain values. */
data class SimulateInputs(
    val connection: SinkConnection,
    /** LSPosed's answer, or null when it was not asked or could not answer. */
    val framework: FrameworkReport?,
    val moduleStatus: ReceivedStatus?,
    /** Wall-clock time of this boot. */
    val bootTimeMillis: Long,
    /** When the installed app (and so the module code) was last updated, wall clock. */
    val appUpdatedAtMillis: Long,
)

/**
 * Picks the most faithful simulation that can run now. The module path needs `system_server` to run this build's
 * module code, because older modules ignore the simulate channel and the notification would play the default sound.
 */
object SimulatePaths {
    fun choose(inputs: SimulateInputs): SimulatePath {
        if (inputs.connection !is SinkConnection.Connected) return SimulatePath.InApp(InAppReason.ModuleOff)
        val reported =
            inputs.moduleStatus
                ?.status
                ?.takeIf { StatusChecks.isSameBoot(it.bootTimeMillis, inputs.bootTimeMillis) }
        if (reported != null && reported.missingHooks.isNotEmpty()) {
            return SimulatePath.InApp(InAppReason.HooksMissing)
        }
        val targets = inputs.framework?.targets
        if (targets != null) {
            // libxposed API 102: UP_TO_DATE means system_server runs the installed APK's module code, this build.
            val target = StatusChecks.systemServerTarget(targets)
            return if (target?.state == TargetState.UP_TO_DATE) {
                SimulatePath.Module
            } else {
                SimulatePath.InApp(InAppReason.RebootNeeded)
            }
        }
        // LSPosed did not answer: a report from this boot proves the module runs; it runs this build's code only if
        // the app was installed before the boot.
        return when {
            reported == null -> SimulatePath.InApp(InAppReason.NotConfirmed)
            inputs.appUpdatedAtMillis < inputs.bootTimeMillis -> SimulatePath.Module
            else -> SimulatePath.InApp(InAppReason.RebootNeeded)
        }
    }
}
