package app.mauri.cucuto.core.creator

import app.mauri.cucuto.packspec.SpecJson
import app.mauri.cucuto.packspec.When
import app.mauri.cucuto.packspec.authoring.ConditionForm
import app.mauri.cucuto.packspec.authoring.DateForm
import app.mauri.cucuto.packspec.authoring.LoopMode
import app.mauri.cucuto.packspec.authoring.Moment
import app.mauri.cucuto.packspec.authoring.RingtoneForm
import app.mauri.cucuto.packspec.authoring.TimeOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The creator's ringtones and clip conditions (#322), from the draft to a validated pack, with real fixture media. */
class RingtonesAndConditionsTest {
    @get:Rule val temp = TemporaryFolder()

    private val fixtures = File(System.getProperty("cucuto.specDir")!!, "fixtures/valid")
    private val store by lazy { DraftStore(temp.newFolder("creator")) }
    private val song get() = File(fixtures, "full-pack/characters/song/ringtones/day.ogg")

    private fun media(
        fixture: String,
        prefix: String,
    ): String {
        val target = store.newMediaFile(prefix, fixture.substringAfterLast('.'))
        File(fixtures, fixture).copyTo(target)
        return target.name
    }

    private fun json(w: When?) = w?.let { SpecJson.encodeToJsonElement(When.serializer(), it).toString() }

    @Test
    fun `a song goes in as it is, with a label from its name`() {
        val result = RingtoneImport.import(store, song, "pond-song_day.ogg") as RingtoneImport.Result.Added
        val ringtone = result.ringtone
        assertTrue(ringtone.file.startsWith("ringtone-") && ringtone.file.endsWith(".ogg"))
        assertEquals("Pond song day", ringtone.entry.label)
        assertTrue(ringtone.durationMs >= 5_000)
        assertTrue(song.readBytes().contentEquals(store.media(ringtone.file).readBytes()))
    }

    @Test
    fun `files a pack cannot hold as a ringtone are refused and not kept`() {
        val clip = File(fixtures, "minimal-pack/characters/beep/clips/beep.ogg")
        assertTrue(RingtoneImport.import(store, clip, "beep.ogg") is RingtoneImport.Result.OutOfRange)
        assertEquals(RingtoneImport.Result.Unsupported, RingtoneImport.import(store, song, "song.webm"))
        val text = temp.newFile("notes.ogg").apply { writeText("not audio") }
        assertEquals(RingtoneImport.Result.Undecodable, RingtoneImport.import(store, text, "notes.ogg"))
        assertTrue(
            store.mediaDir
                .listFiles()
                .orEmpty()
                .isEmpty(),
        )
    }

    @Test
    fun `a ringtone-only character with versions and clip conditions builds a valid pack`() {
        val added = (RingtoneImport.import(store, song, "pond.ogg") as RingtoneImport.Result.Added).ringtone
        val day =
            added.copy(
                entry =
                    RingtoneForm(
                        label = "Day",
                        condition = ConditionForm(time = TimeOfDay.DAY),
                        loop = LoopMode.SECTION,
                        loopStartMs = 1_000,
                        loopEndMs = 4_000,
                    ).toEntry(added.file),
            )
        // "Add a version": another entry for the same file.
        val night =
            added.copy(
                key = CreatorDraft.newKey(),
                entry =
                    RingtoneForm(
                        label = "Night",
                        condition = ConditionForm(time = TimeOfDay.HOURS, from = "22:00", to = "06:00"),
                        loop = LoopMode.ONCE,
                        fadeInMs = 500,
                        weight = 2.0,
                    ).toEntry(added.file),
            )
        assertTrue(RingtoneForm.of(day.entry).problems(added.durationMs).isEmpty())
        val sprite = media("minimal-pack/characters/beep/sprite.png", "sprite")
        val beep = media("minimal-pack/characters/beep/clips/beep.ogg", "clip")
        val morning =
            ConditionForm(moment = Moment.FIRST_OF_DAY, dates = listOf(DateForm("12-24", "01-06")), oneIn = 13)
        val draft =
            CreatorDraft(
                idSuffix = "abc123",
                name = "Songs",
                author = "Ana",
                license = "CC0-1.0",
                homepage = "https://example.com/songs",
                tags = listOf("songs", "8-bit"),
                characters =
                    listOf(
                        DraftCharacter(name = "Pond song", sprite = sprite, ringtones = listOf(day, night)),
                        DraftCharacter(
                            name = "Beeper",
                            description = "Beeps at you.",
                            sprite = sprite,
                            clips =
                                listOf(
                                    DraftClip(
                                        file = beep,
                                        label = "Morning",
                                        durationMs = 250,
                                        `when` = morning.toWhen(clip = true),
                                        oneIn = morning.oneIn,
                                    ),
                                ),
                        ),
                    ),
            )
        val out = File(temp.root, "songs.soundpack.zip")
        val result = PackWriter().write(draft.toPackDraft(store.mediaDir), out) as WriteResult.Written
        assertTrue(result.report.issues.joinToString(), result.isValid)

        val m = result.manifest
        assertEquals("https://example.com/songs", m.homepage)
        assertEquals(listOf("songs", "8-bit"), m.tags)
        val songCharacter = m.characters[0]
        assertTrue(songCharacter.clips.isEmpty())
        // Both versions share one file, written once.
        assertEquals(
            listOf("characters/pond-song/ringtones/day.ogg"),
            songCharacter.ringtones.map { it.file }.distinct(),
        )
        assertEquals("""{"time":"day"}""", json(songCharacter.ringtones[0].`when`))
        assertEquals("""{"time":{"from":"22:00","to":"06:00"}}""", json(songCharacter.ringtones[1].`when`))
        assertEquals("false", songCharacter.ringtones[1].loop.toString())
        assertEquals(500, songCharacter.ringtones[1].fadeInMs)
        val clip = m.characters[1].clips.single()
        assertEquals("""{"date":{"from":"12-24","to":"01-06"},"firstOfDay":true}""", json(clip.`when`))
        assertEquals(13, clip.oneIn)
        assertEquals("Beeps at you.", m.characters[1].description)
        // Back into the editors, unchanged.
        assertEquals(morning, ConditionForm.of(clip.`when`, clip.oneIn))
    }

    @Test
    fun `a homepage that is not an https URL is left out`() {
        val draft = CreatorDraft(homepage = "example.com")
        assertNull(draft.toPackDraft(store.mediaDir).homepage)
        assertEquals("https://a.b", CreatorDraft(homepage = " https://a.b ").toPackDraft(store.mediaDir).homepage)
    }
}
