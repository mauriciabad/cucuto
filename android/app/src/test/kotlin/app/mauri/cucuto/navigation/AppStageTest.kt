package app.mauri.cucuto.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AppStageTest {
    @Test
    fun aPlainSurfaceWhileTheSettingsLoad() {
        assertEquals(AppStage.Loading, appStage(onboarded = null))
    }

    @Test
    fun firstRunOpensOnboarding() {
        assertEquals(AppStage.Onboarding, appStage(onboarded = false))
    }

    @Test
    fun afterOnboardingTheAppOpensOnTheShell() {
        assertEquals(AppStage.Shell, appStage(onboarded = true))
        assertEquals(TopLevelTab.Home, TopLevelTab.Start)
    }
}
