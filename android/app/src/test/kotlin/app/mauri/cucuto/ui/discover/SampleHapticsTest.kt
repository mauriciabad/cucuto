package app.mauri.cucuto.ui.discover

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.packspec.RepositoryIndex
import app.mauri.cucuto.packspec.SpecJson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.IOException

/** Discover's haptic previews (#272, ADR 0069): which vibration a sample plays with. */
class SampleHapticsTest {
    private val click = Haptic.Custom(listOf(HapticEvent.Preset(0, HapticPreset.CLICK, 900)))
    private val envelope =
        Haptic.Custom(
            listOf(HapticEvent.Envelope(0, listOf(ControlPoint(800, 500, 40), ControlPoint(0, 500, 60)))),
        )
    private val sample = File("cache/repo-preview/abc.ogg")

    private class Fixture(
        var enabled: Boolean = true,
        val result: () -> Haptic.Custom?,
    ) {
        val analysed = mutableListOf<File>()
        val vibrations =
            SampleVibrations(
                enabled = { enabled },
                analyze = {
                    analysed += it
                    result()
                },
            )
    }

    @Test
    fun `a custom pattern plays as the index has it, without analysing the sample`() =
        runTest {
            val f = Fixture { envelope }
            assertEquals(click, f.vibrations.of(click, sample))
            assertEquals(emptyList<File>(), f.analysed)
        }

    @Test
    fun `auto is the sample's envelope, analysed once per file`() =
        runTest {
            val f = Fixture { envelope }
            assertEquals(envelope, f.vibrations.of(Haptic.Auto, sample))
            assertEquals(envelope, f.vibrations.of(Haptic.Auto, sample))
            assertEquals(listOf(sample), f.analysed)
            val other = File("cache/repo-preview/def.ogg")
            f.vibrations.of(Haptic.Auto, other)
            assertEquals(listOf(sample, other), f.analysed)
        }

    @Test
    fun `a silent or unreadable sample plays without vibration, and is not analysed again`() =
        runTest {
            val silent = Fixture { null }
            assertNull(silent.vibrations.of(Haptic.Auto, sample))
            assertNull(silent.vibrations.of(Haptic.Auto, sample))
            assertEquals(1, silent.analysed.size)

            val broken = Fixture { throw IOException("not audio") }
            assertNull(broken.vibrations.of(Haptic.Auto, sample))
            assertNull(broken.vibrations.of(Haptic.Auto, sample))
            assertEquals(1, broken.analysed.size)
        }

    @Test
    fun `none, and an index without x-haptic, play the sample alone`() =
        runTest {
            val f = Fixture { envelope }
            assertNull(f.vibrations.of(Haptic.Keep, sample))
            assertNull(f.vibrations.of(null, sample))
            assertEquals(emptyList<File>(), f.analysed)
        }

    @Test
    fun `the UI haptics switch turns haptic previews off`() =
        runTest {
            val f = Fixture(enabled = false) { envelope }
            assertNull(f.vibrations.of(click, sample))
            assertNull(f.vibrations.of(Haptic.Auto, sample))
            assertEquals(emptyList<File>(), f.analysed)
            f.enabled = true
            assertEquals(click, f.vibrations.of(click, sample))
        }

    @Test
    fun `x-haptic in an index reads like an installed pack's haptic`() {
        val index =
            SpecJson.decodeFromString(
                RepositoryIndex.serializer(),
                """
                {"formatVersion": 1, "name": "R", "packs": [{"id": "a.b", "type": "pack", "name": "P", "version": "1.0.0",
                  "characters": [
                    {"id": "frog", "name": "Frog", "sample": "f.ogg", "x-haptic": {"mode": "custom", "events": [
                      {"at": 0, "preset": "CLICK", "scale": 0.9},
                      {"at": 200, "initialSharpness": 0.3, "envelope": [{"intensity": 1, "sharpness": 0.8, "ms": 120}, {"intensity": 0, "sharpness": 0.4, "ms": 180}]}
                    ]}},
                    {"id": "pot", "name": "Pot", "sample": "p.ogg", "x-haptic": {"mode": "none"}},
                    {"id": "whisk", "name": "Whisk", "sample": "w.ogg", "x-haptic": {"mode": "auto"}},
                    {"id": "old", "name": "Old", "sample": "o.ogg"}
                  ],
                  "download": {"url": "p.zip", "size": 100, "sha256": "${"a".repeat(64)}"}}]}
                """.trimIndent(),
            )
        val (frog, pot, whisk, old) = index.packs.single().characters!!
        assertEquals(
            Haptic.Custom(
                listOf(
                    HapticEvent.Preset(0, HapticPreset.CLICK, 900),
                    HapticEvent.Envelope(200, listOf(ControlPoint(1000, 800, 120), ControlPoint(0, 400, 180)), 300),
                ),
            ),
            previewHaptic(frog.haptic!!),
        )
        assertEquals(Haptic.Keep, previewHaptic(pot.haptic!!))
        assertEquals(Haptic.Auto, previewHaptic(whisk.haptic!!))
        assertNull(old.haptic)
    }
}
