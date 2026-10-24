package app.mauri.cucuto.packspec

/**
 * How a pack ringtone becomes a **ring file**: the audio Cucuto exports for Android's per-contact ringtone
 * (docs/pack-format.md#ring-files, ADR 0033).
 *
 * Telecom loops the whole ringtone file and has no loop points or fade-in (M9 report §6), so the loop is rendered
 * into the file: the intro `[0, loopStartMs)` once, then the loop section `[loopStartMs, loopEndMs)` [loopCount]
 * times, then [silenceMs] of silence (only for `loop: false`), with a linear fade-in over the first [fadeInMs].
 * Audio after `loopEndMs` (an outro) is never part of a looping ring file, as it never plays in a loop.
 *
 * Every renderer (packer, the app) uses this plan, so the same ringtone gives the same ring file layout everywhere.
 * Positions are milliseconds of the source; a renderer converts them to frames with [frame].
 */
data class RingFilePlan(
    /** Measured duration of the source file. */
    val sourceMs: Long,
    val loopStartMs: Long,
    val loopEndMs: Long,
    /** How many times the loop section plays, at least 1. For `loop: false` the "loop" is the whole file, once. */
    val loopCount: Int,
    /** Silence appended after a ringtone that plays once (`loop: false`), so it does not restart right away. */
    val silenceMs: Long,
    val fadeInMs: Long,
    /** True for `loop: false`. */
    val playOnce: Boolean,
) {
    val loopMs: Long get() = loopEndMs - loopStartMs

    /** Length of the rendered ring file. */
    val totalMs: Long get() = loopStartMs + loopCount * loopMs + silenceMs

    /**
     * True when the ring file is the source file itself (a whole-file loop of at least [Limits.RING_FILE_MIN_MS] with
     * no fade-in): renderers may copy the source as is instead of re-encoding it.
     */
    val isPassThrough: Boolean
        get() = loopStartMs == 0L && loopEndMs == sourceMs && loopCount == 1 && silenceMs == 0L && fadeInMs == 0L

    /** The pieces of the ring file in order, in frames at [sampleRate]: copies of source ranges, then silence. */
    fun pieces(sampleRate: Int): List<RingPiece> = buildList {
        val start = frame(loopStartMs, sampleRate)
        val end = frame(loopEndMs, sampleRate)
        if (start > 0) add(RingPiece.Copy(0, start))
        repeat(loopCount) { add(RingPiece.Copy(start, end - start)) }
        if (silenceMs > 0) add(RingPiece.Silence(frame(silenceMs, sampleRate)))
    }

    companion object {
        /** Frame index of [ms] at [sampleRate], rounded down. */
        fun frame(ms: Long, sampleRate: Int): Long = ms * sampleRate / 1000
    }
}

/** One piece of a rendered ring file, in frames (one sample per channel). */
sealed interface RingPiece {
    val frames: Long

    /** [frames] frames of the decoded source starting at frame [from]. */
    data class Copy(val from: Long, override val frames: Long) : RingPiece

    data class Silence(override val frames: Long) : RingPiece
}

object RingFiles {
    /**
     * The ring file plan for [ringtone] whose source measures [sourceMs]. The ringtone must have passed validation
     * (loop points within the audio and at least [Limits.MIN_LOOP_MS] long); a loop end within the 50 ms tolerance past
     * the end of the audio is clamped to the end.
     */
    fun plan(ringtone: Ringtone, sourceMs: Long): RingFilePlan = plan(ringtone.loopRange, ringtone.fadeInMs?.toLong() ?: 0L, sourceMs)

    /** [plan] from the parts of a ringtone: [loop] is null for `loop: false`. */
    fun plan(loop: LoopRange?, fadeInMs: Long, sourceMs: Long): RingFilePlan {
        require(sourceMs > 0) { "Empty source" }
        val fade = fadeInMs.coerceAtLeast(0)
        if (loop == null) {
            return RingFilePlan(
                sourceMs = sourceMs,
                loopStartMs = 0,
                loopEndMs = sourceMs,
                loopCount = 1,
                silenceMs = (Limits.RING_FILE_MIN_MS - sourceMs).coerceAtLeast(0),
                fadeInMs = fade,
                playOnce = true,
            )
        }
        val start = loop.startMs ?: 0L
        val end = (loop.endMs ?: sourceMs).coerceAtMost(sourceMs)
        require(end - start >= Limits.MIN_LOOP_MS) { "Loop $start..$end ms is shorter than ${Limits.MIN_LOOP_MS} ms" }
        val length = end - start
        val needed = Limits.RING_FILE_MIN_MS - start
        val count = if (needed <= length) 1 else ((needed + length - 1) / length).toInt()
        return RingFilePlan(sourceMs, start, end, count, 0, fade, playOnce = false)
    }
}
