package app.mauri.cucuto.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.home.HomeContent
import app.mauri.cucuto.home.HomeState
import app.mauri.cucuto.home.JourneyStep
import app.mauri.cucuto.home.buildHomeState
import app.mauri.cucuto.navigation.TopLevelTab
import app.mauri.cucuto.ui.status.ModuleCheck
import app.mauri.cucuto.ui.status.PacksCheck
import app.mauri.cucuto.ui.status.ScopeCheck
import app.mauri.cucuto.ui.status.SelfTestMessage
import app.mauri.cucuto.ui.status.SetupContent
import app.mauri.cucuto.ui.status.StatusCard
import app.mauri.cucuto.ui.status.StatusReport
import app.mauri.cucuto.ui.status.WhatsAppCheck
import org.junit.Test

/**
 * The app shell's first-run journey on Home (#153, ADR 0027): right after onboarding, with the compact setup card and
 * the offer to import the demo packs or restore a backup; then, with the packs in, the invitation to give the newest
 * sender a voice. Settings → Setup with the tab badge (#180) and with the open door on the green card (#178).
 */
class ShellScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test
    fun firstRun() =
        screenshot("home-first-run") {
            InShell(TopLevelTab.Home) {
                HomeContent(
                    state = HomeState(loading = false),
                    onQueryChange = {},
                    onTogglePack = {},
                    onDismissRecent = {},
                    onOpenChat = {},
                    onOpenPacks = {},
                    journey = JourneyStep.ImportPacks,
                    setupCard = {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            StatusCard(FirstRunReport, beat = null, SelfTestMessage.None, onAction = {}, compact = true)
                        }
                    },
                )
            }
        }

    @Test
    fun firstVoice() =
        screenshot("home-give-first-voice") {
            val state =
                buildHomeState(
                    chats = DemoContent.chats().map { it.copy(voice = null) },
                    packs = DemoContent.packs.map { it.voicePack() },
                    query = "",
                    packFilter = null,
                    now = System.currentTimeMillis(),
                )
            InShell(TopLevelTab.Home) {
                HomeContent(
                    state = state,
                    onQueryChange = {},
                    onTogglePack = {},
                    onDismissRecent = {},
                    onOpenChat = {},
                    onOpenPacks = {},
                    journey = state.recent.firstOrNull()?.let(JourneyStep::GiveFirstVoice) ?: JourneyStep.None,
                )
            }
        }

    /** Settings → Setup when the app was updated and LSPosed lost it (#209): the reboot card, the Settings badge. */
    @Test
    fun setupNeedsAttention() =
        screenshot("setup") {
            InShell(TopLevelTab.Settings, settingsBadge = true) {
                SetupContent(
                    report = UpdatedReport,
                    beat = null,
                    selfTest = SelfTestMessage.None,
                    onAction = {},
                    onNavigate = {},
                    onBack = {},
                )
            }
        }

    /** Everything green: the open cuckoo door on the leaf-green card (#178: the house must show on the card). */
    @Test
    fun setupReady() =
        screenshot("setup-ready") {
            InShell(TopLevelTab.Settings) {
                SetupContent(
                    report = ReadyReport,
                    beat = null,
                    selfTest = SelfTestMessage.None,
                    onAction = {},
                    onNavigate = {},
                    onBack = {},
                )
            }
        }

    private companion object {
        /** Right after the onboarding: WhatsApp found, the module waiting for its self-test, no packs yet. */
        val FirstRunReport =
            StatusReport(
                module = ModuleCheck.AwaitingSelfTest,
                scope = ScopeCheck.SystemOnly,
                whatsApp = WhatsAppCheck.Found(listOf("com.whatsapp")),
                packs = PacksCheck.None,
            )

        val ReadyReport =
            StatusReport(
                module = ModuleCheck.Active(listOf("playSound", "playVibration"), updatePending = false),
                scope = ScopeCheck.SystemOnly,
                whatsApp = WhatsAppCheck.Found(listOf("com.whatsapp")),
                packs = PacksCheck.Installed(4),
            )

        /** Updated since boot, and LSPosed did not hand the service back: only a reboot helps (ADR 0043). */
        val UpdatedReport = ReadyReport.copy(module = ModuleCheck.NeedsReboot(afterUpdate = true, frameworkLost = true))
    }
}
