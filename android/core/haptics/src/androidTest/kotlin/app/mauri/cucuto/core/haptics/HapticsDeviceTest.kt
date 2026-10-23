package app.mauri.cucuto.core.haptics

import android.os.VibratorManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mauri.cucuto.core.haptics.auto.AutoHapticAnalyzer
import app.mauri.cucuto.core.haptics.auto.AutoHapticFile
import app.mauri.cucuto.core.haptics.auto.AutoHaptics
import app.mauri.cucuto.core.haptics.auto.ClipPcmDecoder
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Decodes a generated clip with the platform codecs, analyses it, and builds real [android.os.VibrationEffect]s on the
 * device. Nothing vibrates: effects are only built.
 */
@RunWith(AndroidJUnit4::class)
class HapticsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "haptics-test").apply { mkdirs() }
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val capabilities = vibrator.hapticCapabilities()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun decodesAndAnalysesAGeneratedClip() =
        runTest {
            Log.i(TAG, "Capabilities: $capabilities")
            val probe = runCatching { Class.forName("android.os.VibrationEffect\$Builder").getConstructor() }
            Log.i(TAG, "VibrationEffect.Builder in this process: $probe")
            // 250 ms of silence, a loud low hum, a quiet gap, a loud bright whistle, then silence: 1.5 s, stereo.
            val parts =
                listOf(
                    Part(250, 0.0, 0.0),
                    Part(400, 0.8, 180.0),
                    Part(150, 0.08, 180.0),
                    Part(400, 0.8, 3200.0),
                    Part(300, 0.0, 0.0),
                )
            val wav = writeWav(File(dir, "clip.wav"), parts, sampleRate = 44_100, channels = 2)

            val pcm = ClipPcmDecoder().decode(wav)
            assertEquals(44_100, pcm.sampleRate)
            assertTrue("decoded ${pcm.durationMs} ms", abs(pcm.durationMs - 1500) <= 30)

            val auto = AutoHaptics(capabilities)
            val haptic = auto.analyze(wav)
            assertNotNull(haptic)
            Log.i(TAG, "Auto haptic: $haptic")
            val envelope = haptic!!.events.single() as HapticEvent.Envelope
            assertTrue("starts at ${envelope.atMs}", envelope.atMs in 220..280)
            val end = envelope.atMs + envelope.points.sumOf { it.durationMs }
            assertTrue("ends at $end", end in 1150..1300 && end <= AutoHapticAnalyzer.MAX_DURATION_MS)
            assertEquals(0, envelope.points.last().milliIntensity)
            assertTrue(envelope.points.size <= auto.options.maxControlPoints)

            val effect = HapticEffects.create(haptic, capabilities)
            assertNotNull("No effect for $haptic on $capabilities", effect)

            // Stored next to the pack, and read back.
            val set = auto.analyzePack(dir, listOf("clip.wav", "missing.wav"))
            assertEquals(mapOf("clip.wav" to haptic), set.clips)
            assertEquals(set, AutoHapticFile.read(dir))
        }

    @Test
    fun buildsEffectsOnEveryPath() {
        val brief =
            Haptic.Custom(
                listOf(
                    HapticEvent.Preset(0, HapticPreset.CLICK, 800),
                    HapticEvent.Preset(120, HapticPreset.TICK, 500),
                    HapticEvent.Envelope(220, listOf(ControlPoint(1000, 800, 120), ControlPoint(0, 400, 180))),
                ),
            )
        assertNotNull(HapticEffects.create(brief, capabilities))
        assertNotNull(HapticEffects.create(brief, vibrator))

        // The fallbacks, forced on this device: one joined envelope, a composition, a waveform.
        val envelopes = Haptic.Custom(brief.events.filterIsInstance<HapticEvent.Envelope>())
        val presets = Haptic.Custom(brief.events.filterIsInstance<HapticEvent.Preset>())
        val noBuilder = capabilities.copy(builderSupported = false)
        if (capabilities.envelopesSupported) {
            val plan = HapticPlanner.plan(envelopes, noBuilder)
            assertTrue(plan.toString(), plan is HapticPlan.Envelope)
            assertNotNull(HapticEffects.build(plan))
        }
        if (presets.events.all { (it as HapticEvent.Preset).preset in capabilities.primitiveDurationsMs }) {
            val plan = HapticPlanner.plan(presets, noBuilder)
            assertTrue(plan.toString(), plan is HapticPlan.Primitives)
            assertNotNull(HapticEffects.build(plan))
        }
        val waveform = HapticPlanner.waveform(brief, capabilities)
        assertTrue(waveform.toString(), waveform is HapticPlan.Waveform)
        assertNotNull(HapticEffects.build(waveform))

        // Nonsense in, something playable (or nothing) out, never an exception.
        val messy =
            Haptic.Custom(
                listOf(
                    HapticEvent.Envelope(
                        0,
                        List(40) {
                            ControlPoint(
                                if (it ==
                                    39
                                ) {
                                    700
                                } else {
                                    (it * 97) % 1000
                                },
                                2000,
                                1,
                            )
                        },
                    ),
                    HapticEvent.Preset(0, HapticPreset.LOW_TICK, -4),
                    HapticEvent.Preset(0, HapticPreset.CLICK, 9000),
                ),
            )
        assertNotNull(HapticEffects.create(messy, capabilities))
    }

    private data class Part(
        val ms: Int,
        val amplitude: Double,
        val hz: Double,
    )

    /** Writes 16-bit PCM WAV, the same signal on every channel. */
    private fun writeWav(
        file: File,
        parts: List<Part>,
        sampleRate: Int,
        channels: Int,
    ): File {
        val frames = parts.sumOf { sampleRate * it.ms / 1000 }
        val data = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        var n = 0
        for (part in parts) {
            repeat(sampleRate * part.ms / 1000) {
                val value =
                    (
                        part.amplitude *
                            sin(
                                2 * PI * part.hz * n / sampleRate,
                            ) * Short.MAX_VALUE
                    ).toInt().toShort()
                repeat(channels) { data.putShort(value) }
                n++
            }
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        header
            .put("fmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(channels.toShort())
            .putInt(sampleRate)
        header.putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        header.put("data".toByteArray()).putInt(data.capacity())
        file.outputStream().use {
            it.write(header.array())
            it.write(data.array())
        }
        return file
    }

    private companion object {
        const val TAG = "HapticsDeviceTest"
    }
}
