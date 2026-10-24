package app.mauri.cucuto.diagnostics

import app.mauri.cucuto.core.data.routing.PublishState
import app.mauri.cucuto.xposed.events.ModuleStatus
import kotlin.math.abs

/** How the routing table in the module's last report compares with the table the app published. */
enum class ModuleTableSync {
    /** The module holds the app's latest generation. */
    SAME,

    /** The module reported another generation: its report is pending, or it did not load the latest table. */
    BEHIND,

    /** The report is from before the last reboot, so it says nothing about the module running now. */
    PREVIOUS_BOOT,

    /** The app has not published in this process yet: nothing to compare with. */
    UNKNOWN,
}

/**
 * Compares [status] with [publish]. Both sides compute the boot time as wall clock minus `elapsedRealtime`, so they
 * agree to within [toleranceMillis] unless the clock was changed by more than that.
 */
fun moduleTableSync(
    status: ModuleStatus,
    publish: PublishState,
    bootTimeMillis: Long,
    toleranceMillis: Long = 60_000,
): ModuleTableSync =
    when {
        abs(status.bootTimeMillis - bootTimeMillis) > toleranceMillis -> ModuleTableSync.PREVIOUS_BOOT
        publish !is PublishState.Published -> ModuleTableSync.UNKNOWN
        publish.generation == status.tableGeneration -> ModuleTableSync.SAME
        else -> ModuleTableSync.BEHIND
    }
