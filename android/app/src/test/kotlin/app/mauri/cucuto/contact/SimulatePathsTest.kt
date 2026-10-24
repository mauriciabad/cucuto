package app.mauri.cucuto.contact

import app.mauri.cucuto.core.data.routing.FrameworkReport
import app.mauri.cucuto.core.data.routing.RunningTarget
import app.mauri.cucuto.core.data.routing.SinkConnection
import app.mauri.cucuto.core.data.routing.TargetState
import app.mauri.cucuto.provider.ReceivedStatus
import app.mauri.cucuto.xposed.events.ModuleStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SimulatePathsTest {
    private val boot = 1_800_000_000_000L
    private val connected = SinkConnection.Connected("LSPosed 2.2.1", 102)

    private fun target(state: TargetState) = RunningTarget(uid = 1000, processName = "system_server", state = state)

    private fun status(
        bootTime: Long = boot,
        missing: List<String> = emptyList(),
    ) = ReceivedStatus(bootTime + 60_000, ModuleStatus(bootTime, listOf("playSound"), missing, 1, 0, 1, 0, 120, 0))

    private fun choose(
        connection: SinkConnection = connected,
        targets: List<RunningTarget>? = listOf(target(TargetState.UP_TO_DATE)),
        status: ReceivedStatus? = null,
        updatedAt: Long = boot - 86_400_000,
    ) = SimulatePaths.choose(
        SimulateInputs(connection, targets?.let { FrameworkReport(listOf("system"), it) }, status, boot, updatedAt),
    )

    @Test
    fun systemServerRunningThisBuildUsesTheModule() {
        assertEquals(SimulatePath.Module, choose())
    }

    @Test
    fun anUpdateSinceBootFallsBackToTheInAppPreviewUntilTheReboot() {
        // Tonight's case: the APK with simulate support is installed, system_server still runs the old module.
        assertEquals(SimulatePath.InApp(InAppReason.RebootNeeded), choose(targets = listOf(target(TargetState.STALE))))
        assertEquals(SimulatePath.InApp(InAppReason.RebootNeeded), choose(targets = listOf(target(TargetState.FAILED))))
    }

    @Test
    fun enabledButNotLoadedNeedsAReboot() {
        assertEquals(SimulatePath.InApp(InAppReason.RebootNeeded), choose(targets = emptyList()))
    }

    @Test
    fun noFrameworkMeansTheModuleIsOff() {
        assertEquals(SimulatePath.InApp(InAppReason.ModuleOff), choose(connection = SinkConnection.Disconnected))
        assertEquals(
            SimulatePath.InApp(InAppReason.ModuleOff),
            choose(connection = SinkConnection.Unsupported("Embedded")),
        )
    }

    @Test
    fun missingHooksThisBootCannotPlayAnything() {
        assertEquals(
            SimulatePath.InApp(InAppReason.HooksMissing),
            choose(status = status(missing = listOf("playSound"))),
        )
        // A report from an earlier boot says nothing about this one.
        assertEquals(SimulatePath.Module, choose(status = status(bootTime = boot - 86_400_000, missing = listOf("x"))))
    }

    @Test
    fun withoutLsposedsAnswerAReportFromThisBootDecides() {
        assertEquals(SimulatePath.InApp(InAppReason.NotConfirmed), choose(targets = null))
        assertEquals(SimulatePath.Module, choose(targets = null, status = status()))
        assertEquals(
            SimulatePath.InApp(InAppReason.RebootNeeded),
            choose(targets = null, status = status(), updatedAt = boot + 1_000),
        )
    }
}
