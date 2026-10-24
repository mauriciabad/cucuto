package app.mauri.cucuto.core.audio.dsp

import app.mauri.cucuto.core.audio.PcmAudio
import app.mauri.cucuto.packspec.authoring.Authoring
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/** What the user chose in the clip editor: the cut, and whether to trim silence and even out loudness. */
data class ClipEdit(
    val startMs: Long,
    val endMs: Long,
    val trimSilence: Boolean = true,
    val normalize: Boolean = true,
)

/** A finished clip: mono floats at [sampleRate] (always 48 kHz), ready to encode. */
class ProcessedClip(
    val samples: FloatArray,
    val sampleRate: Int,
    /** Gain applied by normalization, in dB (0 without it). */
    val gainDb: Double,
    /** Loudness after processing, in LUFS; null for silence. */
    val loudnessLufs: Double?,
) {
    val durationMs: Long get() = samples.size * PcmAudio.MILLIS / sampleRate

    /** True when nothing in the clip rises above the silence threshold. */
    val isSilent: Boolean get() = loudnessLufs == null
}

/**
 * The creator's clip pipeline, with packer's targets ([Authoring.Audio]): cut → trim leading and trailing silence →
 * resample to 48 kHz → normalize to −16 LUFS under a −1.5 dBTP ceiling → 5 ms fades at both ends. Pure Kotlin.
 */
object ClipProcessor {
    fun process(
        source: PcmAudio,
        edit: ClipEdit,
    ): ProcessedClip {
        var samples = source.slice(edit.startMs, edit.endMs)
        if (edit.trimSilence) {
            val bounds = SilenceTrim.bounds(samples, source.sampleRate)
            if (bounds != null) samples = samples.copyOfRange(bounds.first, bounds.last + 1)
        }
        samples = Resampler.resample(samples, source.sampleRate, Authoring.Audio.SAMPLE_RATE_HZ)
        val gain =
            if (edit.normalize) {
                Loudness.normalizationGainDb(
                    samples,
                    Authoring.Audio.TARGET_LUFS,
                    Authoring.Audio.TRUE_PEAK_CEILING_DBTP,
                    Authoring.Audio.MAX_GAIN_DB,
                )
            } else {
                0.0
            }
        Loudness.applyGain(samples, gain)
        fadeEdges(samples, Authoring.Audio.SAMPLE_RATE_HZ, Authoring.Audio.EDGE_FADE_MS)
        for (i in samples.indices) samples[i] = samples[i].coerceIn(-1f, 1f)
        val loudness =
            if (SilenceTrim.bounds(samples, Authoring.Audio.SAMPLE_RATE_HZ) ==
                null
            ) {
                null
            } else {
                Loudness.integratedLufs(samples)
            }
        return ProcessedClip(samples, Authoring.Audio.SAMPLE_RATE_HZ, gain, loudness)
    }

    /** Raised-cosine fade-in and fade-out of [fadeMs] (shorter for very short clips), in place. */
    fun fadeEdges(
        samples: FloatArray,
        sampleRate: Int,
        fadeMs: Long,
    ) {
        val length = minOf((sampleRate * fadeMs / PcmAudio.MILLIS).toInt(), samples.size / 2)
        for (i in 0 until length) {
            val gain = (0.5 - 0.5 * cos(PI * i / length)).toFloat()
            samples[i] *= gain
            samples[samples.size - 1 - i] *= gain
        }
    }
}

/** Leading and trailing silence, as packer's `silenceremove` trims it (threshold and the silence kept around sound). */
object SilenceTrim {
    /**
     * The first and last sample to keep in [samples]: from [Authoring.Audio.SILENCE_KEEP_MS] before the first sample
     * above [thresholdDbfs] to the same after the last one. Null when everything is below the threshold.
     */
    fun bounds(
        samples: FloatArray,
        sampleRate: Int,
        thresholdDbfs: Double = Authoring.Audio.SILENCE_THRESHOLD_DBFS,
        keepMs: Long = Authoring.Audio.SILENCE_KEEP_MS,
    ): IntRange? {
        val threshold = 10.0.pow(thresholdDbfs / 20).toFloat()
        val first = samples.indexOfFirst { abs(it) > threshold }
        if (first < 0) return null
        val last = samples.indexOfLast { abs(it) > threshold }
        val keep = (sampleRate * keepMs / PcmAudio.MILLIS).toInt()
        return (first - keep).coerceAtLeast(0)..(last + keep).coerceAtMost(samples.size - 1)
    }
}

/** Peaks for drawing a waveform. */
object Waveform {
    /** The highest absolute sample (0..1) in each of [buckets] equal slices of [audio], or of its frames [from]..[to]. */
    fun peaks(
        audio: PcmAudio,
        buckets: Int,
        from: Int = 0,
        to: Int = audio.frames,
    ): FloatArray {
        val out = FloatArray(buckets)
        val start = from.coerceIn(0, audio.frames)
        val frames = to.coerceIn(start, audio.frames) - start
        if (frames == 0 || buckets <= 0) return out
        for (b in 0 until buckets) {
            val lo = start + (frames.toLong() * b / buckets).toInt()
            val hi =
                (start + (frames.toLong() * (b + 1) / buckets).toInt())
                    .coerceAtLeast(
                        lo + 1,
                    ).coerceAtMost(start + frames)
            var peak = 0
            for (i in lo until hi) {
                val v = abs(audio.samples[i].toInt())
                if (v > peak) peak = v
            }
            out[b] = peak / PcmAudio.SHORT_SCALE
        }
        return out
    }
}
