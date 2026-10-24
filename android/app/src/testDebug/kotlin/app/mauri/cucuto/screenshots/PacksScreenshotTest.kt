package app.mauri.cucuto.screenshots

import app.mauri.cucuto.navigation.TopLevelTab
import app.mauri.cucuto.packs.PacksUiState
import app.mauri.cucuto.packs.ui.PackDetailContent
import app.mauri.cucuto.packs.ui.PacksContent
import org.junit.Test

/** Packs: both demo packs installed, with their characters and the contacts that use them. */
class PacksScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test
    fun packs() =
        screenshot("packs", web = "packs", store = 4) {
            InShell(TopLevelTab.Packs) {
                PacksContent(
                    state = PacksUiState(packs = DemoContent.packs.map { it.libraryPack() }),
                    onDiscover = {},
                    onAssignVoices = {},
                    onImport = {},
                    onRemove = {},
                    onConsumeMessage = {},
                )
            }
        }

    /** A pack's characters with what each offers (messages, ringtone, or both) and their previews (ADR 0063). */
    @Test
    fun packDetail() =
        screenshot("pack-detail") {
            PackDetailContent(pack = DemoContent.critters.libraryPack(), onBack = {}, onRemove = {})
        }
}
