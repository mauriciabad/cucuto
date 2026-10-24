package app.mauri.cucuto.core.creator

import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.HapticEvent
import app.mauri.cucuto.packspec.authoring.Authoring
import app.mauri.cucuto.packspec.authoring.DraftProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/** The creator end to end, minus the device: draft → assembly → zip → pack-spec validation, with real fixture media. */
class PackWriterTest {
    @get:Rule val temp = TemporaryFolder()

    private val fixtures = File(System.getProperty("cucuto.specDir")!!, "fixtures/valid")
    private val store by lazy { DraftStore(temp.newFolder("creator")) }

    /** Copies a fixture file into the draft's media folder, as the creator does with its own files. */
    private fun media(
        fixture: String,
        prefix: String,
    ): String {
        val ext = fixture.substringAfterLast('.')
        val target = store.newMediaFile(prefix, ext)
        File(fixtures, fixture).copyTo(target)
        return target.name
    }

    private fun draft(): CreatorDraft {
        val sprite = media("minimal-pack/characters/beep/sprite.png", "sprite")
        val beep = media("minimal-pack/characters/beep/clips/beep.ogg", "clip")
        val beep2 = media("minimal-pack/characters/beep/clips/beep.ogg", "clip")
        return CreatorDraft(
            idSuffix = "abc123",
            name = "Kitchen sounds",
            author = "Ana",
            license = "CC0-1.0",
            description = "Made on my phone.",
            characters =
                listOf(
                    DraftCharacter(
                        name = "Kettle",
                        sprite = sprite,
                        seedColor = "#3366FF",
                        clips =
                            listOf(
                                DraftClip(file = beep, label = "Whistle", durationMs = 250),
                                DraftClip(file = beep2, label = "", weight = 2.5, durationMs = 250),
                            ),
                    ),
                    DraftCharacter(
                        name = "Toaster",
                        sprite = sprite,
                        clips = listOf(DraftClip(file = beep, label = "Pop", durationMs = 250)),
                        haptic = HapticMode.CUSTOM,
                        customHaptic = listOf(HapticEvent(at = 0, preset = "CLICK", scale = 0.8)),
                    ),
                ),
        )
    }

    @Test
    fun `a finished draft becomes a valid soundpack zip`() {
        val draft = draft()
        val out = File(temp.root, "out/kitchen.soundpack.zip")
        val result = PackWriter().write(draft.toPackDraft(store.mediaDir), out) as WriteResult.Written
        assertTrue(result.report.issues.joinToString(), result.isValid)
        assertEquals(emptyList<String>(), result.report.warningCodes())

        val m = result.manifest
        assertEquals("local.ana.kitchen-sounds-abc123", m.id)
        assertEquals("1.0.0", m.version)
        assertEquals("CC0-1.0", m.license)
        assertEquals(listOf("kettle", "toaster"), m.characters.map { it.id })
        assertEquals(
            listOf(
                "characters/kettle/clips/whistle.ogg",
                "characters/kettle/clips/clip.ogg",
            ),
            m.characters[0].clips.map {
                it.file
            },
        )
        assertEquals(2.5, m.characters[0].clips[1].weight!!, 0.0)
        assertNull(m.characters[0].haptic)
        assertEquals("custom", m.characters[1].haptic?.mode)
        assertEquals("kitchen-sounds.soundpack.zip", Authoring.packFileName(m.name, m.id))

        ZipFile(out).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals("pack.json", names.first())
            assertEquals(
                setOf(
                    "pack.json",
                    "characters/kettle/sprite.png",
                    "characters/kettle/clips/whistle.ogg",
                    "characters/kettle/clips/clip.ogg",
                    "characters/toaster/sprite.png",
                    "characters/toaster/clips/pop.ogg",
                ),
                names.toSet(),
            )
        }
    }

    @Test
    fun `an incomplete draft lists what is missing and writes nothing`() {
        val out = File(temp.root, "nothing.soundpack.zip")
        val draft =
            CreatorDraft(
                idSuffix = "abc123",
                name = "Empty",
                author = "Ana",
                characters = listOf(DraftCharacter(name = "Nobody")),
            )
        val result = PackWriter().write(draft.toPackDraft(store.mediaDir), out) as WriteResult.Incomplete
        assertEquals(
            listOf(DraftProblem(DraftProblem.Kind.SPRITE_MISSING, 0), DraftProblem(DraftProblem.Kind.NO_CLIPS, 0)),
            result.problems,
        )
        assertTrue(!out.exists())
    }

    @Test
    fun `the validator still catches what assembly cannot know`() {
        // A clip over 30 s: the creator's editor never makes one, but a duplicated pack's file is checked too.
        val long = media("longest-clip/characters/beep/clips/beep.ogg", "clip")
        val base = draft()
        val draft =
            base.updateCharacter(base.characters[0].key) {
                it.copy(
                    clips =
                        it.clips + DraftClip(file = long, durationMs = 30_000),
                )
            }
        val out = File(temp.root, "long.soundpack.zip")
        val result = PackWriter().write(draft.toPackDraft(store.mediaDir), out) as WriteResult.Written
        // The fixture is the longest valid clip, so the pack is still valid; a broken file is not.
        assertTrue(result.isValid)
        File(store.mediaDir, long).writeText("not audio")
        val broken = PackWriter().write(draft.toPackDraft(store.mediaDir), out) as WriteResult.Written
        assertEquals(listOf(ErrorCode.MEDIA_UNDECODABLE.name), broken.report.errorCodes())
    }

    @Test
    fun `fixed IDs survive renames`() {
        val draft = draft()
        val out = File(temp.root, "first.soundpack.zip")
        val first = PackWriter().write(draft.toPackDraft(store.mediaDir), out) as WriteResult.Written
        val fixed = draft.withFixedIds(first.manifest)
        val renamed =
            fixed
                .copy(
                    name = "Kitchen orchestra",
                    author = "Ana B.",
                ).updateCharacter(fixed.characters[0].key) {
                    it.copy(name = "Big kettle")
                }
        val second = PackWriter().write(renamed.toPackDraft(store.mediaDir), out) as WriteResult.Written
        assertEquals(first.manifest.id, second.manifest.id)
        assertEquals(listOf("kettle", "toaster"), second.manifest.characters.map { it.id })
        assertEquals("Big kettle", second.manifest.characters[0].name)
    }
}
