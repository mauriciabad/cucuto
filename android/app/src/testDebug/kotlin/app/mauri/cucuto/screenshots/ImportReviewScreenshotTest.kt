package app.mauri.cucuto.screenshots

import app.mauri.cucuto.core.pack.InstallAction
import app.mauri.cucuto.core.pack.PackCandidate
import app.mauri.cucuto.core.pack.PackSelection
import app.mauri.cucuto.packs.ImportReview
import app.mauri.cucuto.packs.ImportUi
import app.mauri.cucuto.packs.PacksUiState
import app.mauri.cucuto.packs.ReviewPack
import app.mauri.cucuto.packs.ui.ImportSheet
import app.mauri.cucuto.packs.ui.PacksContent
import app.mauri.cucuto.packspec.Character
import app.mauri.cucuto.packspec.Clip
import app.mauri.cucuto.packspec.PackManifest
import org.junit.Test

/** The import review: Kitchen orchestra opened from a file, over Packs with Chiptune critters installed. */
class ImportReviewScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test
    fun importReview() =
        screenshot("import-review") {
            val installed = DemoContent.critters
            val imported = DemoContent.kitchen
            PacksContent(
                state = PacksUiState(packs = listOf(installed.libraryPack())),
                onBack = {},
                onDiscover = {},
                onImport = {},
                onRemove = {},
                onConsumeMessage = {},
            )
            ImportSheet(
                import =
                    ImportUi.Review(
                        review =
                            ImportReview(
                                fileName = "${imported.folder}.soundpack.zip",
                                bundle = null,
                                packs = listOf(reviewPack(imported)),
                                warnings = emptyList(),
                                handle = null,
                            ),
                        selection = PackSelection(selected = setOf(imported.id)),
                    ),
                onSelect = { _, _ -> },
                onInstall = {},
                onPickAnother = {},
                onClose = {},
            )
        }

    private fun reviewPack(pack: DemoPack): ReviewPack {
        val manifest =
            PackManifest(
                formatVersion = 1,
                id = pack.id,
                name = pack.name,
                version = "1.0.0",
                author = "Cucuto contributors",
                license = "CC0-1.0",
                description = pack.description,
                cover = "cover.svg",
                characters =
                    pack.characters.map { c ->
                        Character(
                            id = c.id,
                            name = c.name,
                            sprite = "${c.id}/sprite.svg",
                            clips = pack.clips(c.id).map { Clip("${c.id}/clips/${it.name}") },
                        )
                    },
            )
        val candidate =
            PackCandidate(
                root = pack.folder,
                manifest = manifest,
                action = InstallAction.INSTALL,
                installedVersion = null,
                sizeBytes = pack.sizeBytes,
                fileCount = 13,
            )
        return ReviewPack(candidate, pack.libraryCharacters())
    }
}
