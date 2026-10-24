package app.mauri.cucuto.packs

import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.core.haptics.auto.AutoHapticFile
import app.mauri.cucuto.core.haptics.auto.AutoHapticSet
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutoHapticsRefreshTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun pack(
        id: String,
        clips: List<String>,
        analysed: Int? = null,
    ): InstalledPackEntity {
        val dir = folder.newFolder(id, "1.0.0")
        val clipJson = clips.joinToString(",") { """{ "file": "$it" }""" }
        File(dir, "pack.json").writeText(
            """
            {
              "formatVersion": 1, "id": "$id", "version": "1.0.0", "name": "$id", "author": "Someone",
              "license": "CC0-1.0",
              "characters": [
                { "id": "a", "name": "A", "sprite": "a.svg", "clips": [$clipJson] },
                { "id": "b", "name": "B", "sprite": "b.svg", "clips": [{ "file": "${clips.first()}" }] }
              ]
            }
            """.trimIndent(),
        )
        if (analysed != null) AutoHapticFile.write(dir, AutoHapticSet(analysed, 16, 20, emptyMap()))
        return InstalledPackEntity(
            packId = id,
            version = "1.0.0",
            name = id,
            path = dir.path,
            sha256 = "0".repeat(64),
            sizeBytes = 1,
            source = PackSource.LOCAL,
            installedAt = 1,
        )
    }

    @Test
    fun analysesOnlyMissingOrStaleAnalysesAndSurvivesBrokenPacks() =
        runTest {
            val fresh = pack("app.mauri.test.fresh", listOf("x.ogg"), analysed = 2)
            val old = pack("app.mauri.test.old", listOf("c/1.ogg", "c/2.ogg"), analysed = 1)
            val never = pack("app.mauri.test.never", listOf("n.ogg"))
            val broken =
                pack(
                    "app.mauri.test.broken",
                    listOf("b.ogg"),
                ).also { File(it.path, "pack.json").writeText("{") }
            val gone = never.copy(packId = "app.mauri.test.gone", path = File(folder.root, "gone/1.0.0").path)

            val analysed = mutableMapOf<String, List<String>>()
            val changed = mutableListOf<String>()
            val refreshed =
                refreshStaleAutoHaptics(
                    packs = listOf(fresh, broken, old, gone, never),
                    isStale = { set -> set == null || set.analyzerVersion < 2 },
                    analyze = { dir, clips -> analysed[dir.path] = clips },
                    changed = { changed += it },
                )

            // Every notification clip of every character, whatever its haptic mode (overrides can ask for auto).
            assertEquals(
                mapOf(old.path to listOf("c/1.ogg", "c/2.ogg"), never.path to listOf("n.ogg")),
                analysed,
            )
            assertEquals(listOf(old.path, never.path), changed)
            assertEquals(changed, refreshed)
        }
}
