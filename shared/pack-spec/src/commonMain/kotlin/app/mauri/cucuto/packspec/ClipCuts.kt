package app.mauri.cucuto.packspec

import kotlin.math.sqrt

/** A notification clip cut from a ringtone: `[startMs, endMs)` of the source. */
data class ClipCut(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/**
 * Finds the "hook" of a song for an auto-cut notification clip (docs/pack-format.md#auto-cut-clips, ADR 0033). The
 * packer, the pack creators and the app use this one heuristic, so the same song gives the same clip everywhere:
 *
 * 1. Split the audio into 50 ms frames and take each frame's RMS level.
 * 2. Pick the window of `lengthMs` (whole frames, frame-aligned) with the highest mean level within the search range;
 *    the earliest one wins a tie. The loudest stretch of a song is usually its chorus or hook.
 * 3. Move the start back to the strongest onset (largest rise in level) among the 5 frames (250 ms) before it, so the
 *    clip starts on a beat rather than in the middle of a note.
 *
 * Renderers then fade the clip in over [Limits.CUT_FADE_IN_MS] and out over [Limits.CUT_FADE_OUT_MS].
 */
object ClipCuts {
    const val FRAME_MS = 50L
    private const val ONSET_LOOKBACK_FRAMES = 5

    /**
     * The hook of [samples] (mono, any scale, at [sampleRate]) for a clip of [lengthMs], searched within
     * `[fromMs, toMs)` (the whole file by default, or the whole file when the range is shorter than the clip).
     */
    fun findHook(
        samples: FloatArray,
        sampleRate: Int,
        lengthMs: Long = Limits.CUT_CLIP_DEFAULT_MS,
        fromMs: Long = 0,
        toMs: Long? = null,
    ): ClipCut {
        require(sampleRate > 0 && lengthMs > 0) { "Bad sample rate or length" }
        val durationMs = samples.size.toLong() * 1000 / sampleRate
        if (lengthMs >= durationMs) return ClipCut(0, durationMs)
        val frameLength = (sampleRate * FRAME_MS / 1000).toInt().coerceAtLeast(1)
        val frameCount = samples.size / frameLength
        val levels = DoubleArray(frameCount) { f ->
            var sum = 0.0
            for (i in f * frameLength until (f + 1) * frameLength) sum += samples[i].toDouble() * samples[i]
            sqrt(sum / frameLength)
        }
        val window = ((lengthMs + FRAME_MS - 1) / FRAME_MS).toInt().coerceAtMost(frameCount)
        var first = (fromMs / FRAME_MS).toInt().coerceIn(0, frameCount)
        var last = ((toMs ?: durationMs) / FRAME_MS).toInt().coerceIn(0, frameCount) // exclusive
        if (last - first < window) {
            first = 0
            last = frameCount
        }
        val prefix = DoubleArray(frameCount + 1)
        for (f in 0 until frameCount) prefix[f + 1] = prefix[f] + levels[f]
        var best = first
        var bestSum = -1.0
        for (start in first..last - window) {
            val sum = prefix[start + window] - prefix[start]
            if (sum > bestSum) {
                bestSum = sum
                best = start
            }
        }
        var onset = best
        var onsetRise = 0.0
        for (f in maxOf(first, best - ONSET_LOOKBACK_FRAMES)..best) {
            val rise = levels[f] - (if (f > 0) levels[f - 1] else 0.0)
            if (rise > onsetRise) {
                onsetRise = rise
                onset = f
            }
        }
        val startMs = onset * FRAME_MS
        return ClipCut(startMs, minOf(startMs + lengthMs, durationMs))
    }
}
