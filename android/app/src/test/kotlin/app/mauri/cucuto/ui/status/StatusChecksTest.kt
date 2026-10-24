package app.mauri.cucuto.ui.status

import app.mauri.cucuto.core.data.routing.FrameworkReport
import app.mauri.cucuto.core.data.routing.RunningTarget
import app.mauri.cucuto.core.data.routing.SinkConnection
import app.mauri.cucuto.core.data.routing.TargetState
import app.mauri.cucuto.provider.ReceivedStatus
import app.mauri.cucuto.ui.onboarding.OnboardingStep
import app.mauri.cucuto.xposed.events.ModuleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusChecksTest {
    private val boot = 1_800_000_000_000L
    private val connected = SinkConnection.Connected("LSPosed 2.2.1", 102)
    private val systemServer = RunningTarget(uid = 1000, processName = "system_server", state = TargetState.UP_TO_DATE)

    private fun status(
        bootTime: Long = boot,
        hooks: List<String> = listOf("playSound", "playVibration"),
        missing: List<String> = emptyList(),
    ) = ReceivedStatus(
        receivedAtMillis = bootTime + 60_000,
        status = ModuleStatus(bootTime, hooks, missing, 1, 0, 1, 0, 120, 0),
    )

    private fun inputs(
        connection: SinkConnection = connected,
        framework: FrameworkReport? = FrameworkReport(listOf("system"), listOf(systemServer)),
        moduleStatus: ReceivedStatus? = status(),
        messengers: Map<String, Boolean> = mapOf("com.whatsapp" to true),
        packCount: Int = 1,
        appUpdatedSinceBoot: Boolean = false,
        providerDelivered: Boolean = false,
    ) = StatusInputs(
        connection,
        framework,
        moduleStatus,
        boot,
        messengers,
        packCount,
        appUpdatedSinceBoot,
        providerDelivered,
    )

    @Test
    fun everythingWorkingTurnsTheCardGreen() {
        val report = StatusChecks.evaluate(inputs())
        assertEquals(ModuleCheck.Active(listOf("playSound", "playVibration"), updatePending = false), report.module)
        assertEquals(ScopeCheck.SystemOnly, report.scope)
        assertEquals(WhatsAppCheck.Found(listOf("com.whatsapp")), report.whatsApp)
        assertEquals(PacksCheck.Installed(1), report.packs)
        assertTrue(report.allPassed)
        assertEquals(4, report.passedCount)
    }

    @Test
    fun oneFailingCheckKeepsTheCardFromTurningGreen() {
        val report = StatusChecks.evaluate(inputs(packCount = 0))
        assertEquals(PacksCheck.None, report.packs)
        assertFalse(report.allPassed)
        assertEquals(3, report.passedCount)
    }

    // Module

    @Test
    fun noFrameworkAndNoReportMeansNotEnabled() {
        val report =
            StatusChecks.evaluate(
                inputs(connection = SinkConnection.Disconnected, framework = null, moduleStatus = null),
            )
        assertEquals(ModuleCheck.NotEnabled, report.module)
        assertEquals(ScopeCheck.Unknown, report.scope)
        assertEquals(CheckOutcome.Unknown, report.scope.outcome)
    }

    @Test
    fun aReportFromAPreviousBootDoesNotCount() {
        val old = status(bootTime = boot - 3_600_000)
        assertEquals(
            ModuleCheck.NotEnabled,
            StatusChecks.evaluate(inputs(SinkConnection.Disconnected, null, old)).module,
        )
        assertEquals(ModuleCheck.AwaitingSelfTest, StatusChecks.evaluate(inputs(moduleStatus = old)).module)
    }

    @Test
    fun bootTimesWithinTheToleranceAreTheSameBoot() {
        assertTrue(StatusChecks.isSameBoot(boot, boot + StatusChecks.SAME_BOOT_TOLERANCE_MILLIS))
        assertTrue(StatusChecks.isSameBoot(boot, boot - 2_000))
        assertFalse(StatusChecks.isSameBoot(boot, boot + StatusChecks.SAME_BOOT_TOLERANCE_MILLIS + 1))
        val drifted = status(bootTime = boot + 1_500)
        assertTrue(StatusChecks.evaluate(inputs(moduleStatus = drifted)).module is ModuleCheck.Active)
    }

    @Test
    fun aReportThisBootWinsEvenBeforeTheFrameworkBinds() {
        val report = StatusChecks.evaluate(inputs(connection = SinkConnection.Disconnected, framework = null))
        assertTrue(report.module is ModuleCheck.Active)
        assertEquals(ScopeCheck.InferredFromModule, report.scope)
        assertTrue(report.allPassed)
    }

    @Test
    fun missingHooksFail() {
        val report =
            StatusChecks.evaluate(
                inputs(moduleStatus = status(hooks = listOf("playSound"), missing = listOf("playVibration"))),
            )
        assertEquals(ModuleCheck.HooksMissing(listOf("playVibration")), report.module)
        assertEquals(CheckOutcome.Fail, report.module.outcome)
    }

    @Test
    fun loadedWithoutAReportAsksForTheSelfTest() {
        val report = StatusChecks.evaluate(inputs(moduleStatus = null))
        assertEquals(ModuleCheck.AwaitingSelfTest, report.module)
        assertEquals(listOf(CheckAction.PostSelfTest), report.module.actions())
    }

    @Test
    fun withoutRunningTargetsTheSelfTestIsStillOffered() {
        val framework = FrameworkReport(scope = listOf("system"), targets = null)
        val report = StatusChecks.evaluate(inputs(framework = framework, moduleStatus = null))
        assertEquals(ModuleCheck.AwaitingSelfTest, report.module)
    }

    @Test
    fun enabledButNotInSystemServerNeedsAReboot() {
        val framework = FrameworkReport(scope = listOf("system"), targets = emptyList())
        val report = StatusChecks.evaluate(inputs(framework = framework, moduleStatus = null))
        assertEquals(ModuleCheck.NeedsReboot(afterUpdate = false), report.module)
        assertEquals(listOf(CheckAction.PostSelfTest), report.module.actions())
    }

    @Test
    fun staleModuleCodeNeedsARebootAfterAnUpdate() {
        val stale = FrameworkReport(listOf("system"), listOf(systemServer.copy(state = TargetState.STALE)))
        assertEquals(
            ModuleCheck.NeedsReboot(afterUpdate = true),
            StatusChecks.evaluate(inputs(framework = stale, moduleStatus = null)).module,
        )
        // Still reporting with the old code: active, with a reboot hint.
        assertEquals(
            ModuleCheck.Active(listOf("playSound", "playVibration"), updatePending = true),
            StatusChecks.evaluate(inputs(framework = stale)).module,
        )
    }

    @Test
    fun noFrameworkServiceAfterAnUpdateNeedsAReboot() {
        val lost = ModuleCheck.NeedsReboot(afterUpdate = true, frameworkLost = true)
        // LSPosed 2.2.1 hands the service over again only after a reboot (ADR 0043); the module may still report.
        val reported =
            StatusChecks.evaluate(
                inputs(connection = SinkConnection.Disconnected, framework = null, appUpdatedSinceBoot = true),
            )
        assertEquals(lost, reported.module)
        assertFalse(reported.allPassed)
        assertEquals(emptyList<CheckAction>(), reported.module.actions())
        assertEquals(
            lost,
            StatusChecks
                .evaluate(
                    inputs(
                        connection = SinkConnection.Disconnected,
                        framework = null,
                        moduleStatus = null,
                        appUpdatedSinceBoot = true,
                    ),
                ).module,
        )
        assertEquals(OnboardingStep.Reboot, OnboardingStep.resumeAt(lost))
    }

    @Test
    fun noRebootWhenTheModulePullsFromTheProvider() {
        // ADR 0048: the module fetched the app's latest table without the service, so voice changes still arrive.
        val pulled =
            inputs(
                connection = SinkConnection.Disconnected,
                framework = null,
                appUpdatedSinceBoot = true,
                providerDelivered = true,
            )
        assertEquals(
            ModuleCheck.Active(listOf("playSound", "playVibration"), updatePending = false),
            StatusChecks.evaluate(pulled).module,
        )
        // Pulled, report still on its way: the module runs.
        assertEquals(ModuleCheck.AwaitingSelfTest, StatusChecks.evaluate(pulled.copy(moduleStatus = null)).module)
    }

    @Test
    fun theProviderDeliveredTheLatestTable() {
        // status() reports generation 1.
        assertTrue(StatusChecks.providerDelivered(1, null, status(), boot))
        assertTrue(StatusChecks.providerDelivered(2, 2, status(), boot))
        assertFalse(StatusChecks.providerDelivered(2, 1, status(), boot))
        assertFalse(StatusChecks.providerDelivered(1, null, status(bootTime = boot - 3_600_000), boot))
        assertFalse(StatusChecks.providerDelivered(null, 1, status(), boot))
        assertFalse(StatusChecks.providerDelivered(2, null, null, boot))
    }

    @Test
    fun anUpdateDoesNotMatterWhileTheServiceIsThere() {
        assertEquals(
            ModuleCheck.Active(listOf("playSound", "playVibration"), updatePending = false),
            StatusChecks.evaluate(inputs(appUpdatedSinceBoot = true)).module,
        )
        // Missing hooks stay the more useful answer.
        val missing = status(hooks = listOf("playSound"), missing = listOf("playVibration"))
        assertEquals(
            ModuleCheck.HooksMissing(listOf("playVibration")),
            StatusChecks
                .evaluate(
                    inputs(
                        connection = SinkConnection.Disconnected,
                        framework = null,
                        moduleStatus = missing,
                        appUpdatedSinceBoot = true,
                    ),
                ).module,
        )
    }

    @Test
    fun onlyAnUpdateAfterBootCounts() {
        val day = 86_400_000L
        // Updated after boot.
        assertTrue(StatusChecks.updatedSinceBoot(boot - day, boot + 60_000, boot))
        // Updated before boot.
        assertFalse(StatusChecks.updatedSinceBoot(boot - day, boot - 60_000, boot))
        // Fresh install after boot: the module was never enabled, so "not enabled" stays the answer.
        assertFalse(StatusChecks.updatedSinceBoot(boot + 60_000, boot + 60_000, boot))
        // Installed and then updated, both after boot.
        assertTrue(StatusChecks.updatedSinceBoot(boot + 60_000, boot + 120_000, boot))
    }

    @Test
    fun unsupportedFrameworkFails() {
        val report =
            StatusChecks.evaluate(
                inputs(connection = SinkConnection.Unsupported("Embedded 1.0"), framework = null, moduleStatus = null),
            )
        assertEquals(ModuleCheck.FrameworkUnsupported("Embedded 1.0"), report.module)
        assertEquals(listOf(CheckAction.OpenInstallGuide), report.module.actions())
    }

    @Test
    fun systemServerIsFoundByUidInAnyUserOrByName() {
        assertEquals(systemServer, StatusChecks.systemServerTarget(listOf(systemServer)))
        val secondUser = RunningTarget(1_001_000, "whatever", TargetState.UP_TO_DATE)
        assertEquals(secondUser, StatusChecks.systemServerTarget(listOf(secondUser)))
        val byName = RunningTarget(-1, "system", TargetState.UP_TO_DATE)
        assertEquals(byName, StatusChecks.systemServerTarget(listOf(byName)))
        assertNull(
            StatusChecks.systemServerTarget(listOf(RunningTarget(10_123, "com.whatsapp", TargetState.UP_TO_DATE))),
        )
    }

    // Scope

    @Test
    fun scopeMustBeExactlySystemFramework() {
        assertEquals(ScopeCheck.SystemOnly, StatusChecks.scope(listOf("system"), moduleReported = false))
        assertEquals(ScopeCheck.SystemOnly, StatusChecks.scope(listOf("system", "system"), moduleReported = false))
        assertEquals(
            ScopeCheck.ExtraApps(listOf("com.whatsapp")),
            StatusChecks.scope(listOf("com.whatsapp", "system"), moduleReported = true),
        )
        // The legacy "android" entry is the android package (system:ui), not system_server (ADR 0002).
        assertEquals(ScopeCheck.MissingSystem(listOf("android")), StatusChecks.scope(listOf("android"), false))
        assertEquals(ScopeCheck.MissingSystem(emptyList()), StatusChecks.scope(emptyList(), false))
    }

    @Test
    fun unreadableScopeIsUnknownUnlessTheModuleReported() {
        assertEquals(ScopeCheck.Unknown, StatusChecks.scope(null, moduleReported = false))
        assertEquals(ScopeCheck.InferredFromModule, StatusChecks.scope(null, moduleReported = true))
        assertEquals(CheckOutcome.Pass, ScopeCheck.InferredFromModule.outcome)
    }

    @Test
    fun aReadableWrongScopeOverridesTheModuleReport() {
        val framework = FrameworkReport(listOf("system", "com.whatsapp"), listOf(systemServer))
        val report = StatusChecks.evaluate(inputs(framework = framework))
        assertEquals(ScopeCheck.ExtraApps(listOf("com.whatsapp")), report.scope)
        assertFalse(report.allPassed)
    }

    // WhatsApp

    @Test
    fun whatsAppOrBusinessCounts() {
        assertEquals(WhatsAppCheck.Missing, StatusChecks.whatsApp(emptyMap()))
        assertEquals(
            WhatsAppCheck.Found(listOf("com.whatsapp.w4b")),
            StatusChecks.whatsApp(mapOf("com.whatsapp.w4b" to true, "com.whatsapp" to false)),
        )
        assertEquals(
            WhatsAppCheck.Disabled(listOf("com.whatsapp")),
            StatusChecks.whatsApp(mapOf("com.whatsapp" to false)),
        )
        assertEquals(listOf(CheckAction.GetWhatsApp), WhatsAppCheck.Missing.actions())
        assertEquals(listOf(CheckAction.OpenWhatsAppSettings), WhatsAppCheck.Disabled(listOf("com.whatsapp")).actions())
    }

    // Packs

    @Test
    fun noPackOffersImportAndTheWebsite() {
        assertEquals(listOf(CheckAction.ImportPack, CheckAction.GetPacks), PacksCheck.None.actions())
        assertEquals(PacksCheck.Installed(3), StatusChecks.evaluate(inputs(packCount = 3)).packs)
    }

    // Onboarding

    @Test
    fun onboardingResumesWhereTheModuleIs() {
        assertNull(OnboardingStep.resumeAt(ModuleCheck.NotEnabled))
        assertEquals(OnboardingStep.Reboot, OnboardingStep.resumeAt(ModuleCheck.NeedsReboot(afterUpdate = false)))
        assertEquals(OnboardingStep.SelfTest, OnboardingStep.resumeAt(ModuleCheck.AwaitingSelfTest))
        assertEquals(OnboardingStep.SelfTest, OnboardingStep.resumeAt(ModuleCheck.Active(emptyList(), false)))
    }
}
