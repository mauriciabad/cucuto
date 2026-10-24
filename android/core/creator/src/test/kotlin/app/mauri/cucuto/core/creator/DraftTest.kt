package app.mauri.cucuto.core.creator

import app.mauri.cucuto.core.pack.PackStore
import app.mauri.cucuto.packspec.HapticEvent
import app.mauri.cucuto.packspec.Ringtone
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

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
        assertEquals(6, copy.keptRingtones)
        assertEquals(1, copy.keptAnimations)
        assertTrue(robin.spriteAnimated!!.endsWith(".gif"))
        assertEquals(
            listOf("day", "night", null, null, null, "night"),
            robin.clips.map { (it.`when`?.time as? JsonPrimitive)?.content },
        )
        // A rare clip keeps its chance.
        assertEquals(listOf(null, null, null, null, 50, null), robin.clips.map { it.oneIn })
        assertEquals("A small bird with two calls.", robin.description)
        assertEquals(listOf("bird"), robin.tags)
        assertEquals(listOf("fixtures", "test-pack"), draft.tags)
        assertEquals("https://cucuto.mauri.app", draft.homepage)
        // Variants that share a file in the pack share one copy.
        val song = draft.characters[1]
        assertEquals(5, song.ringtones.size)
        assertEquals(
            2,
            song.ringtones
                .map { it.file }
                .distinct()
                .size,
        )
        assertEquals(song.ringtones[0].file, song.ringtones[2].file)
        // The original is untouched.
        assertTrue(File(installed, "pack.json").readText().contains("app.mauri.fixtures.full"))
    }

    @Test
    fun `a duplicated pack with ringtones and an animated sprite writes back to a valid pack with all of them`() {
        val files = temp.newFolder("files")
        val packs = PackStore.inFilesDir(files)
        val installed = File(packs.root, "app.mauri.fixtures.full/2.1.0-beta.1")
        File(fixtures, "full-pack").copyRecursively(installed)
        File(packs.root, "app.mauri.fixtures.full/active").writeText("2.1.0-beta.1\n")
        val original = packs.activeManifest("app.mauri.fixtures.full")!!
        val store = DraftStore.inFilesDir(files)

        val draft = PackDuplicator(packs, store).duplicate("app.mauri.fixtures.full") { "$it (copy)" }.draft
        // The draft survives a save and a reload with everything it passes through.
        assertEquals(draft, store.load())
        val out = File(temp.root, "copy.soundpack.zip")
        val written = PackWriter().write(draft.toPackDraft(store.mediaDir), out) as WriteResult.Written
        assertTrue(written.report.issues.joinToString(), written.isValid)

        val copy = written.manifest
        assertEquals(original.homepage, copy.homepage)
        assertEquals(original.tags, copy.tags)
        for ((before, after) in original.characters.zip(copy.characters)) {
            assertEquals(before.id, after.id)
            assertEquals(before.description, after.description)
            assertEquals(before.tags, after.tags)
            // `auto` is the default and is left out.
            assertEquals(before.haptic?.takeIf { it.mode != "auto" }, after.haptic)
            assertEquals(before.spriteAnimated != null, after.spriteAnimated != null)
            assertEquals(before.clips.map { it.copy(file = "") }, after.clips.map { it.copy(file = "") })
            // Every ringtone setting comes back; only the file paths follow the canonical layout.
            assertEquals(before.ringtones.map { it.copy(file = "") }, after.ringtones.map { it.copy(file = "") })
            assertTrue(after.ringtones.all { it.file.startsWith("characters/${after.id}/ringtones/") })
            // Entries that shared a file still do.
            assertEquals(
                before.ringtones.map { r -> before.ringtones.indexOfFirst { it.file == r.file } },
                after.ringtones.map { r -> after.ringtones.indexOfFirst { it.file == r.file } },
            )
        }
        assertEquals("characters/robin/sprite-anim.gif", copy.characters[0].spriteAnimated)
        ZipFile(out).use { zip ->
            assertTrue(zip.getEntry("characters/robin/sprite-anim.gif") != null)
            assertEquals(2, zip.entries().toList().count { it.name.startsWith("characters/song/ringtones/") })
            assertEquals(1, zip.entries().toList().count { it.name.startsWith("characters/ringer/ringtones/") })
        }
    }

    @Test
    fun `replacing pass-through media keeps the draft's media list complete`() {
        val character =
            DraftCharacter(
                sprite = "sprite-1.png",
                spriteAnimated = "sprite-anim-1.gif",
                ringtones = listOf(DraftRingtone("ringtone-1.ogg", Ringtone(file = "characters/x/ring.ogg"))),
            )
        assertEquals(
            setOf("sprite-1.png", "sprite-anim-1.gif", "ringtone-1.ogg"),
            CreatorDraft(characters = listOf(character)).mediaFiles(),
        )
    }
}
