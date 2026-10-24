package app.mauri.cucuto.core.audio.dsp

import app.mauri.cucuto.core.audio.PcmAudio
import app.mauri.cucuto.packspec.authoring.Authoring
import app.mauri.cucuto.packspec.authoring.ClipDsp
import kotlin.math.abs

/** What the user chose in the clip editor: the cut, and whether to trim silence and even out loudness. */
data class ClipEdit(
    val startMs: Long,
    val endMs: Long,
    val trimSilence: Boolean = true,
    val normalize: Boolean = true,
)

/**
 * The app side of the creators' clip pipeline: cut the selection from the source (at its own rate), resample it to
 * 48 kHz ([Resampler]), then hand it to pack-spec's shared [ClipDsp] (silence trimming, EBU R128 loudness, edge fades),
 * the same code the website's creator runs (ADR 0034, ADR 0038). One implementation, identical clips.
 */
object ClipProcessor {
    fun process(
        source: PcmAudio,
        edit: ClipEdit,
    ): ClipDsp.Processed {
        val cut = source.slice(edit.startMs, edit.endMs)
        val samples = Resampler.resample(cut, source.sampleRate, Authoring.Audio.SAMPLE_RATE_HZ)
        val durationMs = samples.size * PcmAudio.MILLIS / Authoring.Audio.SAMPLE_RATE_HZ
        // The end is past the last sample, so the whole resampled cut goes in.
        return ClipDsp.process(samples, 0, durationMs + 1, edit.trimSilence, edit.normalize)
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
            val hi = (start + (frames.toLong() * (b + 1) / buckets).toInt()).coerceIn(lo + 1, start + frames)
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
