package app.mauri.cucuto.screenshots

import app.mauri.cucuto.packs.PacksUiState
import app.mauri.cucuto.packs.ui.PacksContent
import org.junit.Test

/** Packs: both demo packs installed, with their characters and the contacts that use them. */
class PacksScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test
    fun packs() =
        screenshot("packs", web = "packs", store = 4) {
            PacksContent(
                state = PacksUiState(packs = DemoContent.packs.map { it.libraryPack() }),
                onBack = {},
                onDiscover = {},
                onImport = {},
                onRemove = {},
                onConsumeMessage = {},
            )
        }
}
