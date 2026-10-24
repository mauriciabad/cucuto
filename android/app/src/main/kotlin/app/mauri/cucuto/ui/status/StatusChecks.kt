package app.mauri.cucuto.ui.status

import app.mauri.cucuto.core.data.routing.FrameworkReport
import app.mauri.cucuto.core.data.routing.RunningTarget
import app.mauri.cucuto.core.data.routing.SinkConnection
import app.mauri.cucuto.core.data.routing.TargetState
import app.mauri.cucuto.provider.ReceivedStatus
import kotlin.math.abs

/** How one check reads on the status card. Never conveyed by color alone: each has its own icon and text. */
enum class CheckOutcome {
    /** Works. */
    Pass,

    /** Needs the user: the card shows a fix or an instruction. */
    Fail,

    /** Cucuto cannot tell from here; the card says how to verify it by hand. */
    Unknown,
}

/** "Module active in system_server", from the module's own reports and what LSPosed says. */
sealed interface ModuleCheck {
    val outcome: CheckOutcome

    /** The module reported from `system_server` since this boot with every hook installed. */
    data class Active(
        val hooks: List<String>,
        /** The app was updated after boot; `system_server` still runs the previous module code until a reboot. */
        val updatePending: Boolean,
    ) : ModuleCheck {
        override val outcome = CheckOutcome.Pass
    }

    /** The module runs, but some hooks could not be installed on this Android build: WhatsApp's tone plays. */
    data class HooksMissing(
        val missing: List<String>,
    ) : ModuleCheck {
        override val outcome = CheckOutcome.Fail
    }

    /** Enabled and (as far as LSPosed says) loaded, but nothing reported since boot: post the self-test. */
    data object AwaitingSelfTest : ModuleCheck {
        override val outcome = CheckOutcome.Fail
    }

    /** Enabled in LSPosed but not loaded into `system_server` yet, or loaded with older code: reboot. */
    data class NeedsReboot(
        val afterUpdate: Boolean,
        /**
         * The app was updated since boot and LSPosed has not given it the framework service. LSPosed 2.2.1 hands it
         * over again only after a reboot (ADR 0043), so voice changes cannot reach the module until then.
         */
        val frameworkLost: Boolean = false,
    ) : ModuleCheck {
        override val outcome = CheckOutcome.Fail
    }

    /** The framework that bound to the app cannot serve Cucuto (no Remote Preferences). */
    data class FrameworkUnsupported(
        val framework: String,
    ) : ModuleCheck {
        override val outcome = CheckOutcome.Fail
    }

    /** No framework service: the module is not enabled in LSPosed, or LSPosed is missing. */
    data object NotEnabled : ModuleCheck {
        override val outcome = CheckOutcome.Fail
    }
}

/** "LSPosed scope is System Framework only". */
sealed interface ScopeCheck {
    val outcome: CheckOutcome

    /** LSPosed says the scope is exactly System Framework. */
    data object SystemOnly : ScopeCheck {
        override val outcome = CheckOutcome.Pass
    }

    /**
     * LSPosed's answer is unavailable, but the module reported from `system_server` since boot, so System Framework
     * is in the scope; `staticScope=true` keeps any other app out of it.
     */
    data object InferredFromModule : ScopeCheck {
        override val outcome = CheckOutcome.Pass
    }

    /** System Framework is not ticked: the module can never load where it works. [others] is what is ticked. */
    data class MissingSystem(
        val others: List<String>,
    ) : ScopeCheck {
        override val outcome = CheckOutcome.Fail
    }

    /** System Framework plus other apps (Cucuto must never hook WhatsApp or anything else). */
    data class ExtraApps(
        val packages: List<String>,
    ) : ScopeCheck {
        override val outcome = CheckOutcome.Fail
    }

    /** Not readable (module disabled or framework error): verify in the LSPosed manager. */
    data object Unknown : ScopeCheck {
        override val outcome = CheckOutcome.Unknown
    }
}

/** "WhatsApp detected": WhatsApp or WhatsApp Business installed and enabled. */
sealed interface WhatsAppCheck {
    val outcome: CheckOutcome

    data class Found(
        val packages: List<String>,
    ) : WhatsAppCheck {
        override val outcome = CheckOutcome.Pass
    }

    /** Installed but disabled in the system settings. */
    data class Disabled(
        val packages: List<String>,
    ) : WhatsAppCheck {
        override val outcome = CheckOutcome.Fail
    }

    data object Missing : WhatsAppCheck {
        override val outcome = CheckOutcome.Fail
    }
}

/** "At least one pack installed". */
sealed interface PacksCheck {
    val outcome: CheckOutcome

    data class Installed(
        val count: Int,
    ) : PacksCheck {
        override val outcome = CheckOutcome.Pass
    }

    data object None : PacksCheck {
        override val outcome = CheckOutcome.Fail
    }
}

/** What [StatusChecks.evaluate] reads. Everything is a plain value so the logic is unit-tested without Android. */
data class StatusInputs(
    val connection: SinkConnection,
    /** The framework's answer, or null when it was not asked (not connected) or could not answer at all. */
    val framework: FrameworkReport?,
    val moduleStatus: ReceivedStatus?,
    /** Wall-clock time of this boot: `System.currentTimeMillis() - SystemClock.elapsedRealtime()`. */
    val bootTimeMillis: Long,
    /** Package name to "enabled" for each installed messenger (WhatsApp, WhatsApp Business). */
    val messengers: Map<String, Boolean>,
    /** Installed packs that count (the debug test voice does not). */
    val packCount: Int,
    /** The installed app replaced an earlier install after this boot ([StatusChecks.updatedSinceBoot]). */
    val appUpdatedSinceBoot: Boolean = false,
)

/** The four checks of the status card. */
data class StatusReport(
    val module: ModuleCheck,
    val scope: ScopeCheck,
    val whatsApp: WhatsAppCheck,
    val packs: PacksCheck,
) {
    /** The card turns green only when every check passes. */
    val allPassed: Boolean
        get() = outcomes.all { it == CheckOutcome.Pass }

    val passedCount: Int
        get() = outcomes.count { it == CheckOutcome.Pass }

    private val outcomes: List<CheckOutcome>
        get() = listOf(module.outcome, scope.outcome, whatsApp.outcome, packs.outcome)
}

/** The check logic of the status card (#24), pure Kotlin. */
object StatusChecks {
    /** LSPosed's name for System Framework (`system_server`) in a modern module's scope (ADR 0002). */
    const val SYSTEM_SCOPE = "system"

    /**
     * Two wall-clock boot times this close are the same boot. The module computes its boot time the same way as the
     * app, so they differ only by clock adjustments (NTP) between the two readings; a reboot moves it by at least the
     * previous uptime plus the reboot itself.
     */
    const val SAME_BOOT_TOLERANCE_MILLIS: Long = 60_000

    private const val SYSTEM_APP_ID = 1000
    private const val PER_USER_RANGE = 100_000

    fun evaluate(inputs: StatusInputs): StatusReport {
        val reportedThisBoot =
            inputs.moduleStatus
                ?.status
                ?.takeIf { isSameBoot(it.bootTimeMillis, inputs.bootTimeMillis) }
        val module = module(inputs, reportedThisBoot != null)
        return StatusReport(
            module = module,
            scope = scope(inputs.framework?.scope, moduleReported = reportedThisBoot != null),
            whatsApp = whatsApp(inputs.messengers),
            packs = if (inputs.packCount > 0) PacksCheck.Installed(inputs.packCount) else PacksCheck.None,
        )
    }

    fun isSameBoot(
        a: Long,
        b: Long,
    ): Boolean = abs(a - b) <= SAME_BOOT_TOLERANCE_MILLIS

    /**
     * Whether an install that replaced an earlier one happened after this boot (`PackageInfo` wall-clock times). A
     * fresh install after boot does not count: its module was never enabled, so "not enabled" is still the answer.
     */
    fun updatedSinceBoot(
        firstInstallMillis: Long,
        lastUpdateMillis: Long,
        bootTimeMillis: Long,
    ): Boolean = lastUpdateMillis > firstInstallMillis && lastUpdateMillis > bootTimeMillis

    private fun module(
        inputs: StatusInputs,
        reportedThisBoot: Boolean,
    ): ModuleCheck {
        val systemTarget = inputs.framework?.targets?.let(::systemServerTarget)
        val stale = systemTarget?.state == TargetState.STALE || systemTarget?.state == TargetState.FAILED
        // No framework service after an update: LSPosed only hands it over again after a reboot (ADR 0043). The
        // module may still run and report, but no voice change can reach it, so this is not "active".
        val frameworkLost = inputs.connection == SinkConnection.Disconnected && inputs.appUpdatedSinceBoot
        if (reportedThisBoot) {
            val status = checkNotNull(inputs.moduleStatus).status
            return when {
                status.missingHooks.isNotEmpty() -> ModuleCheck.HooksMissing(status.missingHooks)
                frameworkLost -> ModuleCheck.NeedsReboot(afterUpdate = true, frameworkLost = true)
                else -> ModuleCheck.Active(status.hooks, updatePending = stale)
            }
        }
        return when (val connection = inputs.connection) {
            SinkConnection.Disconnected -> {
                if (frameworkLost) {
                    ModuleCheck.NeedsReboot(afterUpdate = true, frameworkLost = true)
                } else {
                    ModuleCheck.NotEnabled
                }
            }

            is SinkConnection.Unsupported -> {
                ModuleCheck.FrameworkUnsupported(connection.framework)
            }

            is SinkConnection.Connected -> {
                val targets = inputs.framework?.targets
                when {
                    stale -> ModuleCheck.NeedsReboot(afterUpdate = true)
                    targets != null && systemTarget == null -> ModuleCheck.NeedsReboot(afterUpdate = false)
                    else -> ModuleCheck.AwaitingSelfTest
                }
            }
        }
    }

    /**
     * The module's process in `system_server`: uid 1000 in any user. The scope allows nothing else, but the name is
     * accepted too, since the framework calls both values diagnostic.
     */
    fun systemServerTarget(targets: List<RunningTarget>): RunningTarget? =
        targets.firstOrNull { it.uid >= 0 && it.uid % PER_USER_RANGE == SYSTEM_APP_ID }
            ?: targets.firstOrNull { it.processName == "system_server" || it.processName == SYSTEM_SCOPE }

    fun scope(
        scope: List<String>?,
        moduleReported: Boolean,
    ): ScopeCheck {
        if (scope == null) return if (moduleReported) ScopeCheck.InferredFromModule else ScopeCheck.Unknown
        val others = scope.filter { it != SYSTEM_SCOPE }.distinct().sorted()
        return when {
            SYSTEM_SCOPE !in scope -> ScopeCheck.MissingSystem(others)
            others.isNotEmpty() -> ScopeCheck.ExtraApps(others)
            else -> ScopeCheck.SystemOnly
        }
    }

    fun whatsApp(messengers: Map<String, Boolean>): WhatsAppCheck {
        val enabled = messengers.filterValues { it }.keys.sorted()
        val disabled = messengers.filterValues { !it }.keys.sorted()
        return when {
            enabled.isNotEmpty() -> WhatsAppCheck.Found(enabled)
            disabled.isNotEmpty() -> WhatsAppCheck.Disabled(disabled)
            else -> WhatsAppCheck.Missing
        }
    }
}
