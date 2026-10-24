package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.authoring.Authoring
import app.mauri.cucuto.packspec.authoring.CharacterDraft
import app.mauri.cucuto.packspec.authoring.ClipDraft
import app.mauri.cucuto.packspec.authoring.DraftFile
import app.mauri.cucuto.packspec.authoring.DraftProblem
import app.mauri.cucuto.packspec.authoring.PackAssembler
import app.mauri.cucuto.packspec.authoring.PackDraft
import app.mauri.cucuto.packspec.authoring.RingtoneDraft
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.random.Random

class AuthoringTest {
    private val fixtures = Path.of(System.getProperty("cucuto.specDir")).resolve("fixtures")
    private val sprite = fixtures.resolve("valid/minimal-pack/characters/beep/sprite.png").readBytes()
    private val clip = fixtures.resolve("valid/minimal-pack/characters/beep/clips/beep.ogg").readBytes()
    private val packId = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
    private val slug = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    @Test
    fun `slugs fold accents and join words with single hyphens`() {
        assertEquals("pokemon-ash-s-pikachu", Authoring.slug("  Pokémon: Ash's  Pikachu! "))
        assertEquals("strasse-cafe-noel", Authoring.slug("Straße Café Noël"))
        assertEquals("", Authoring.slug("ピカチュウ"))
        assertEquals("r2-d2", Authoring.slug("R2--D2"))
    }

    @Test
    fun `long slugs are cut at a word boundary`() {
        val cut = Authoring.slug("word ".repeat(30), maxLength = 22)
        assertEquals("word-word-word-word", cut)
        assertTrue(Authoring.slug("x".repeat(100)).length == Authoring.MAX_SLUG_LENGTH)
    }

    @Test
    fun `unique slugs count up and fall back for empty names`() {
        assertEquals("frog", Authoring.uniqueSlug("frog", setOf("toad"), "character"))
        assertEquals("frog-3", Authoring.uniqueSlug("frog", setOf("frog", "frog-2"), "character"))
        assertEquals("character-2", Authoring.uniqueSlug("", setOf("character"), "character"))
        val long = "a".repeat(Authoring.MAX_SLUG_LENGTH)
        val next = Authoring.uniqueSlug(long, setOf(long), "character")
        assertEquals(Authoring.MAX_SLUG_LENGTH, next.length)
        assertTrue(next.endsWith("-2"))
    }

    @Test
    fun `suggested pack IDs always match the schema`() {
        val suffix = Authoring.randomIdSuffix(Random(1))
        assertEquals(Authoring.PACK_ID_SUFFIX_LENGTH, suffix.length)
        assertEquals("local.ana.garden-birds-$suffix", Authoring.suggestPackId("Ana", "Garden birds", suffix))
        for ((author, name) in listOf("" to "", "ピカ" to "チュウ", "A".repeat(200) to "B ".repeat(200), "-" to "-")) {
            val id = Authoring.suggestPackId(author, name, suffix)
            assertTrue(packId.matches(id), id)
            assertTrue(id.length in 3..128, id)
        }
    }

    @Test
    fun `pack file names come from the name, or the ID when the name has no usable letters`() {
        assertEquals("garden-birds.soundpack.zip", Authoring.packFileName("Garden birds!", "local.ana.garden-birds-k3x9q2"))
        assertEquals("pika-k3x9q2.soundpack.zip", Authoring.packFileName("ピカチュウ", "local.me.pika-k3x9q2"))
    }

    @Test
    fun `a pack that would not update the installed one gets the next patch version`() {
        assertEquals("1.0.0", Authoring.versionAfter("1.0.0", null))
        assertEquals("1.0.1", Authoring.versionAfter("1.0.0", "1.0.0"))
        assertEquals("2.3.5", Authoring.versionAfter("1.0.0", "2.3.4"))
        assertEquals("2.3.5", Authoring.versionAfter("1.0.0", "2.3.5-beta.1"))
        assertEquals("1.2.0", Authoring.versionAfter("1.2.0", "1.1.9"))
    }

    @Test
    fun `an assembled draft passes every validator rule`() {
        val assembled = PackAssembler.assemble(draft())
        val files =
            buildMap {
                put("pack.json", assembled.manifestJson.encodeToByteArray())
                for (f in assembled.files) put(f.path, if (f.key.endsWith(".png")) sprite else clip)
            }
        val report = PackValidator().validate(InMemoryPackSource(files)).report
        assertTrue(report.isValid, report.issues.joinToString())
        assertEquals(emptyList<String>(), report.warningCodes())
    }

    @Test
    fun `assembly writes the canonical layout with unique IDs and file names`() {
        val pack = PackAssembler.assemble(draft())
        val m = pack.manifest
        assertEquals(1, m.formatVersion)
        assertTrue(packId.matches(m.id))
        assertEquals(listOf("frog", "frog-2", "toad"), m.characters.map { it.id })
        assertEquals("cover.png", m.cover)
        val frog = m.characters[0]
        assertEquals("characters/frog/sprite.png", frog.sprite)
        assertEquals(
            listOf("characters/frog/clips/ribbit.ogg", "characters/frog/clips/ribbit-2.ogg", "characters/frog/clips/clip.ogg"),
            frog.clips.map { it.file },
        )
        assertEquals(listOf("Ribbit", "Ribbit", null), frog.clips.map { it.label })
        assertEquals(listOf(null, 3.0, null), frog.clips.map { it.weight })
        assertNull(frog.haptic, "auto is the default and is left out")
        assertEquals("none", m.characters[1].haptic?.mode)
        assertEquals("#4CAF50", frog.seedColor)
        assertEquals(listOf("pack.json") + pack.files.map { it.path }, pack.paths)
        assertTrue(pack.manifestJson.endsWith("}\n"))
        assertFalse(pack.manifestJson.startsWith("﻿"))
        m.characters.forEach { assertTrue(slug.matches(it.id)) }
        pack.files.forEach { assertFalse(PackPaths.isUnsafe(it.path) || PackPaths.isInvalidName(it.path), it.path) }
    }

    @Test
    fun `pass-through data from a duplicated pack is written unchanged`() {
        val day = Ringtone(file = "ignored", label = "Pond song (day)", tags = listOf("day"), `when` = When(time = JsonPrimitive("day")), fadeInMs = 500)
        val night = Ringtone(file = "ignored", label = "Night", loop = JsonPrimitive(false), weight = 3.0)
        val base = draft()
        val song =
            CharacterDraft(
                id = "song",
                name = "Song",
                sprite = DraftFile("song.png", "png"),
                seedColor = null,
                clips = emptyList(),
                description = " A pond song. ",
                tags = listOf("pond"),
                spriteAnimated = DraftFile("song.gif", "gif"),
                ringtones = listOf(RingtoneDraft(DraftFile("r1.ogg", "ogg"), day), RingtoneDraft(DraftFile("r1.ogg", "ogg"), night)),
            )
        val frog = base.characters[0].let { c -> c.copy(clips = c.clips.mapIndexed { i, clip -> if (i == 0) clip.copy(`when` = When(time = JsonPrimitive("night"))) else clip }) }
        val draft = base.copy(homepage = "https://example.com", tags = listOf("birds"), characters = listOf(frog, song))
        assertEquals(emptyList<DraftProblem>(), PackAssembler.problems(draft), "a song-only character needs no clip")

        val pack = PackAssembler.assemble(draft)
        val m = pack.manifest
        assertEquals("https://example.com", m.homepage)
        assertEquals(listOf("birds"), m.tags)
        assertEquals("night", (m.characters[0].clips[0].`when`?.time as JsonPrimitive).content)
        val s = m.characters[1]
        assertEquals("A pond song.", s.description)
        assertEquals(listOf("pond"), s.tags)
        assertEquals("characters/song/sprite-anim.gif", s.spriteAnimated)
        // One file for both variants, named after the first; everything else as it was.
        val path = "characters/song/ringtones/pond-song-day.ogg"
        assertEquals(listOf(day.copy(file = path), night.copy(file = path)), s.ringtones)
        assertEquals(1, pack.files.count { it.key == "r1.ogg" })

        val wrong = draft.copy(characters = listOf(song.copy(spriteAnimated = DraftFile("still.png", "png"))))
        assertEquals(listOf(DraftProblem(DraftProblem.Kind.UNSUPPORTED_FILE, 0)), PackAssembler.problems(wrong))
    }

    @Test
    fun `fixed IDs are kept and never reused for derived ones`() {
        val base = draft()
        val fixed = base.copy(id = "io.github.ana.birds", characters = base.characters.mapIndexed { i, c -> if (i == 2) c.copy(id = "frog") else c })
        val m = PackAssembler.assemble(fixed).manifest
        assertEquals("io.github.ana.birds", m.id)
        assertEquals(listOf("frog-2", "frog-3", "frog"), m.characters.map { it.id })
    }

    @Test
    fun `problems name what is missing`() {
        val empty = PackDraft(id = null, idSuffix = "abc123", name = " ", author = "", license = "", characters = emptyList())
        assertEquals(
            listOf(DraftProblem.Kind.PACK_NAME_MISSING, DraftProblem.Kind.AUTHOR_MISSING, DraftProblem.Kind.LICENSE_MISSING, DraftProblem.Kind.NO_CHARACTERS),
            PackAssembler.problems(empty).map { it.kind },
        )
        val bare = draft().copy(characters = listOf(CharacterDraft(null, "", null, null, emptyList())))
        assertEquals(
            listOf(
                DraftProblem(DraftProblem.Kind.CHARACTER_NAME_MISSING, 0),
                DraftProblem(DraftProblem.Kind.SPRITE_MISSING, 0),
                DraftProblem(DraftProblem.Kind.NO_CLIPS, 0),
            ),
            PackAssembler.problems(bare),
        )
        val wrongFile = draft().copy(characters = listOf(CharacterDraft(null, "X", DraftFile("s", "mp3"), null, listOf(ClipDraft(DraftFile("c", "png"), null)))))
        assertEquals(listOf(DraftProblem(DraftProblem.Kind.UNSUPPORTED_FILE, 0)), PackAssembler.problems(wrongFile))
        assertThrows<IllegalArgumentException> { PackAssembler.assemble(empty) }
    }

    private fun draft() =
        PackDraft(
            id = null,
            idSuffix = "k3x9q2",
            name = "Garden birds",
            author = "Ana",
            license = "CC0-1.0",
            description = "  ",
            cover = DraftFile("cover.png", "PNG"),
            characters =
                listOf(
                    CharacterDraft(
                        id = null,
                        name = "Frog",
                        sprite = DraftFile("frog.png", "png"),
                        seedColor = "#4CAF50",
                        clips =
                            listOf(
                                ClipDraft(DraftFile("a.ogg", "ogg"), "Ribbit"),
                                ClipDraft(DraftFile("b.ogg", "ogg"), " Ribbit ", weight = 3.0),
                                ClipDraft(DraftFile("c.ogg", "ogg"), "", weight = 1.0),
                            ),
                        haptic = Haptic("auto"),
                    ),
                    CharacterDraft(null, "Frog", DraftFile("frog2.png", "png"), null, listOf(ClipDraft(DraftFile("d.ogg", "ogg"), null)), Haptic("none")),
                    CharacterDraft(null, "Toad", DraftFile("toad.png", "png"), null, listOf(ClipDraft(DraftFile("e.ogg", "ogg"), "Croak"))),
                ),
        )
}
