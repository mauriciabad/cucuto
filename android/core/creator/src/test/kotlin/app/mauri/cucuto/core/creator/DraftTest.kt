package app.mauri.cucuto.core.creator

import app.mauri.cucuto.core.pack.PackStore
import app.mauri.cucuto.packspec.HapticEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DraftTest {
    @get:Rule val temp = TemporaryFolder()

    private val fixtures = File(System.getProperty("cucuto.specDir")!!, "fixtures/valid")

    @Test
    fun `drafts survive a save and a reload, and unused media is pruned`() {
        val store = DraftStore(temp.newFolder("creator"))
        assertNull(store.load())
        val kept = store.newMediaFile("sprite", "webp").apply { writeText("x") }
        val dropped = store.newMediaFile("clip", "ogg").apply { writeText("y") }
        val draft =
            CreatorDraft(
                name = "Birds",
                author = "Ana",
                characters =
                    listOf(
                        DraftCharacter(
                            name = "Robin",
                            sprite = kept.name,
                            haptic = HapticMode.CUSTOM,
                            customHaptic = listOf(HapticEvent(at = 0, preset = "TICK")),
                        ),
                    ),
            )
        store.save(draft)
        assertEquals(draft, store.load())
        assertEquals("Ana", store.lastAuthor())

        store.prune(draft)
        assertTrue(kept.exists())
        assertFalse(dropped.exists())

        store.clear()
        assertNull(store.load())
        assertFalse(kept.exists())
        assertEquals("Ana", store.lastAuthor())
    }

    @Test
    fun `an unreadable draft counts as none`() {
        val root = temp.newFolder("creator")
        File(root, "draft").mkdirs()
        File(root, "draft/draft.json").writeText("{ not json")
        assertNull(DraftStore(root).load())
    }

    @Test
    fun `each draft gets its own ID suffix and keys`() {
        assertNotEquals(CreatorDraft().idSuffix, CreatorDraft().idSuffix)
        assertNotEquals(DraftCharacter().key, DraftCharacter().key)
        assertTrue(CreatorDraft().isEmpty)
        // Never public domain on the person's behalf: their clips may not be theirs to give away.
        assertEquals("Personal use only", CreatorDraft().license)
        assertFalse(CreatorDraft(characters = listOf(DraftCharacter())).isEmpty)
    }

    @Test
    fun `haptic modes map to the format`() {
        assertNull(DraftCharacter(haptic = HapticMode.AUTO).haptic())
        assertEquals("none", DraftCharacter(haptic = HapticMode.NONE).haptic()?.mode)
        // Custom without a pattern yet (the editor was closed early) stays auto.
        assertNull(DraftCharacter(haptic = HapticMode.CUSTOM).haptic())
        val events = listOf(HapticEvent(at = 0, preset = "CLICK"))
        assertEquals(events, DraftCharacter(haptic = HapticMode.CUSTOM, customHaptic = events).haptic()?.events)
    }

    @Test
    fun `duplicating an installed pack copies it into a new draft`() {
        val files = temp.newFolder("files")
        val packs = PackStore.inFilesDir(files)
        val installed = File(packs.root, "app.mauri.fixtures.full/2.1.0-beta.1")
        File(fixtures, "full-pack").copyRecursively(installed)
        File(packs.root, "app.mauri.fixtures.full/active").writeText("2.1.0-beta.1\n")
        val store = DraftStore.inFilesDir(files)
        store.save(CreatorDraft(name = "Old draft"))

        val copy = PackDuplicator(packs, store).duplicate("app.mauri.fixtures.full") { "$it (copy)" }
        val draft = copy.draft
        assertEquals(draft, store.load())
        assertNull(draft.packId)
        assertEquals("Full pack (copy)", draft.name)
        assertEquals("Full pack", draft.copiedFrom)
        assertEquals("1.0.0", draft.version)
        assertEquals("CC0-1.0 OR MIT", draft.license)
        assertEquals(listOf("robin", "song", "ringer"), draft.characters.map { it.id })
        val robin = draft.characters[0]
        assertEquals("#E65100", robin.seedColor)
        assertEquals(
            listOf("Chirp", "Tweet", "Good morning", "Impatient", "Golden chirp", "Spooky"),
            robin.clips.map {
                it.label
            },
        )
        assertEquals(listOf(2.0, 0.5, 1.0, 1.0, 1.0, 1.0), robin.clips.map { it.weight })
        assertTrue(robin.clips.all { it.durationMs in 200..30_000 })
        assertEquals(HapticMode.CUSTOM, robin.haptic)
        assertEquals(4, robin.customHaptic.size)
        assertTrue(robin.sprite!!.endsWith(".svg"))
        assertTrue(draft.mediaFiles().all { store.media(it).isFile })
        assertTrue(copy.droppedRingtones > 0)
        assertEquals(1, copy.droppedAnimations)
        // The original is untouched.
        assertTrue(File(installed, "pack.json").readText().contains("app.mauri.fixtures.full"))
    }
}
