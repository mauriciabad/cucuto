package app.mauri.cucuto.ui.status

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupCardModeTest {
    private val green =
        StatusReport(
            module = ModuleCheck.Active(listOf("playSound", "playVibration"), updatePending = false),
            scope = ScopeCheck.SystemOnly,
            whatsApp = WhatsAppCheck.Found(listOf("com.whatsapp")),
            packs = PacksCheck.Installed(2),
        )
    private val firstRun = green.copy(module = ModuleCheck.NotEnabled, packs = PacksCheck.None)

    @Test
    fun hiddenWhileLoading() {
        assertEquals(SetupCardMode.Hidden, setupCardMode(null, everGreenAtStart = false, collapsed = false))
        assertEquals(SetupCardMode.Hidden, setupCardMode(firstRun, everGreenAtStart = null, collapsed = false))
    }

    @Test
    fun theFullCardStaysOnHomeUntilSetupIsGreenOnce() {
        assertEquals(SetupCardMode.Card, setupCardMode(firstRun, everGreenAtStart = false, collapsed = false))
        // Turning green keeps the card for its celebration...
        assertEquals(SetupCardMode.Card, setupCardMode(green, everGreenAtStart = false, collapsed = false))
        // ...then it collapses into Settings → Setup.
        assertEquals(SetupCardMode.Hidden, setupCardMode(green, everGreenAtStart = false, collapsed = true))
    }

    @Test
    fun onceGreenHomeStaysQuietAfterAReboot() {
        val afterReboot = green.copy(module = ModuleCheck.AwaitingSelfTest)
        assertEquals(SetupCardMode.Hidden, setupCardMode(afterReboot, everGreenAtStart = true, collapsed = false))
        assertEquals(SetupCardMode.Hidden, setupCardMode(green, everGreenAtStart = true, collapsed = false))
    }

    @Test
    fun onceGreenARealProblemShowsTheBanner() {
        val broken =
            listOf(
                green.copy(module = ModuleCheck.NeedsReboot(afterUpdate = true)),
                green.copy(module = ModuleCheck.HooksMissing(listOf("playSound"))),
                green.copy(scope = ScopeCheck.ExtraApps(listOf("android", "com.whatsapp"))),
                green.copy(whatsApp = WhatsAppCheck.Missing),
            )
        broken.forEach { assertEquals(it.toString(), SetupCardMode.Attention, setupCardMode(it, true, false)) }
    }

    @Test
    fun removingEveryPackIsLeftToHomesOwnPrompt() {
        assertEquals(SetupCardMode.Hidden, setupCardMode(green.copy(packs = PacksCheck.None), true, false))
    }
}
