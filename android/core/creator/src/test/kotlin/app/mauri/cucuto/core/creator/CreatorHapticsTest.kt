package app.mauri.cucuto.core.creator

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.packspec.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import app.mauri.cucuto.packspec.ControlPoint as PackControlPoint
import app.mauri.cucuto.packspec.HapticEvent as PackHapticEvent

/** A character's pattern from the haptic editor, into the pack and through pack-spec's validator (#185). */
class CreatorHapticsTest {
    @get:Rule val temp = TemporaryFolder()

    private val fixtures = File(System.getProperty("cucuto.specDir")!!, "fixtures/valid")
    private val store by lazy { DraftStore(temp.newFolder("creator")) }

    /** What the editor makes: presets with and without a scale, a swell with and without an initial sharpness. */
    private val edited =
        Haptic.Custom(
            listOf(
                HapticEvent.Preset(0, HapticPreset.CLICK, 800),
                HapticEvent.Preset(120, HapticPreset.TICK),
                HapticEvent.Envelope(
                    220,
                    listOf(ControlPoint(1000, 800, 120), ControlPoint(0, 400, 180)),
                    milliInitialSharpness = 300,
                ),
                HapticEvent.Envelope(600, listOf(ControlPoint(625, 500, 40), ControlPoint(0, 300, 100))),
                HapticEvent.Preset(800, HapticPreset.LOW_TICK, 333),
            ),
        )

    @Test
    fun `patterns convert to the pack format and back`() {
        val pack = CreatorHaptics.toPack(edited)
        assertEquals(PackHapticEvent(at = 0, preset = "CLICK", scale = 0.8), pack[0])
        assertEquals(PackHapticEvent(at = 120, preset = "TICK"), pack[1])
        assertEquals(
            PackHapticEvent(
                at = 220,
                initialSharpness = 0.3,
                envelope = listOf(PackControlPoint(1.0, 0.8, 120), PackControlPoint(0.0, 0.4, 180)),
            ),
            pack[2],
        )
        assertNull("the default initial sharpness is left out", pack[3].initialSharpness)
        assertEquals(edited, CreatorHaptics.fromPack(pack))
    }

    @Test
    fun `unreadable pack events degrade instead of throwing`() {
        assertNull(CreatorHaptics.fromPack(emptyList()))
        assertNull(CreatorHaptics.fromPack(listOf(PackHapticEvent(at = 0, preset = "BUZZ"))))
        assertNull(CreatorHaptics.fromPack(listOf(PackHapticEvent(at = 0, envelope = emptyList()))))
        assertEquals(
            Haptic.Custom(listOf(HapticEvent.Preset(10, HapticPreset.TICK, 1000))),
            CreatorHaptics.fromPack(listOf(PackHapticEvent(at = 10, preset = "TICK", scale = 7.0))),
        )
    }

    @Test
    fun `an edited pattern is written into the pack and passes validation`() {
        val out = File(temp.root, "haptics.soundpack.zip")
        val result = PackWriter().write(draft(CreatorHaptics.toPack(edited)).toPackDraft(store.mediaDir), out)
        result as WriteResult.Written
        assertTrue(result.report.issues.joinToString(), result.isValid)
        assertEquals(emptyList<String>(), result.report.warningCodes())
        val haptic =
            result.manifest.characters
                .single()
                .haptic!!
        assertEquals("custom", haptic.mode)
        // What the importer reads back is what was edited.
        assertEquals(edited, CreatorHaptics.fromPack(haptic.events))
    }

    @Test
    fun `the validator rejects a pattern that breaks a timeline rule`() {
        // A swell that does not end at zero intensity: the editor never makes one, but a pattern is checked anyway.
        val broken =
            Haptic.Custom(listOf(HapticEvent.Envelope(0, listOf(ControlPoint(800, 500, 100)))))
        val out = File(temp.root, "broken.soundpack.zip")
        val result = PackWriter().write(draft(CreatorHaptics.toPack(broken)).toPackDraft(store.mediaDir), out)
        result as WriteResult.Written
        assertEquals(listOf(ErrorCode.HAPTIC_INVALID.name), result.report.errorCodes())
    }

    private fun draft(events: List<PackHapticEvent>): CreatorDraft {
        val sprite = store.newMediaFile("sprite", "png")
        File(fixtures, "minimal-pack/characters/beep/sprite.png").copyTo(sprite)
        val clip = store.newMediaFile("clip", "ogg")
        File(fixtures, "minimal-pack/characters/beep/clips/beep.ogg").copyTo(clip)
        return CreatorDraft(
            idSuffix = "abc123",
            name = "Haptics",
            author = "Ana",
            license = "CC0-1.0",
            characters =
                listOf(
                    DraftCharacter(
                        name = "Kettle",
                        sprite = sprite.name,
                        clips = listOf(DraftClip(file = clip.name, label = "Whistle", durationMs = 250)),
                        haptic = HapticMode.CUSTOM,
                        customHaptic = events,
                    ),
                ),
        )
    }
}
