package app.mauri.cucuto.ui.ringtone

import app.mauri.cucuto.packspec.LoopRange
import app.mauri.cucuto.packspec.RingFilePlan
import app.mauri.cucuto.packspec.RingFiles
import java.io.File

/**
 * A ringtone to preview: its pack file and the loop and fade-in that shape its ring file (pack format, "Ring files").
 *
 * @property loop The loop section; `LoopRange(null, null)` loops the whole file (the default), null is `loop: false`.
 */
data class RingSound(
    val file: File,
    val loop: LoopRange? = LoopRange(null, null),
    val fadeInMs: Long = 0,
)

/**
 * How a call rings a ringtone, as time goes by (owner update 2026-10-24, #242; ADR 0063): Telecom loops the ring file,
 * and the ring file is the intro once, the loop section repeated, and silence after a `loop: false` ringtone
 * ([RingFilePlan], the same plan every renderer uses). A preview follows this timeline from 0 ms until the user stops
 * it, so it sounds exactly like the call.
 */
class RingTimeline(
    val plan: RingFilePlan,
) {
    /** One ring file: what plays, in order. It repeats for as long as the phone rings. */
    sealed interface Segment {
        val lengthMs: Long

        /** The source from [fromMs] to [toMs]. */
        data class Play(
            val fromMs: Long,
            val toMs: Long,
        ) : Segment {
            override val lengthMs: Long get() = toMs - fromMs
        }

        data class Silence(
            override val lengthMs: Long,
        ) : Segment
    }

    /** The length of one ring file: the preview's progress goes round once per cycle. */
    val cycleMs: Long get() = plan.totalMs

    val segments: List<Segment> =
        buildList {
            if (plan.loopStartMs > 0) add(Segment.Play(0, plan.loopStartMs))
            repeat(plan.loopCount) { add(Segment.Play(plan.loopStartMs, plan.loopEndMs)) }
            if (plan.silenceMs > 0) add(Segment.Silence(plan.silenceMs))
        }

    /** Whether the player must seek to start segment [index] (it does not simply continue from the one before). */
    fun seeksAt(index: Int): Boolean {
        val segment = segments[index] as? Segment.Play ?: return false
        val previous = segments[(index - 1 + segments.size) % segments.size]
        return when {
            index == 0 -> true
            previous is Segment.Play -> previous.toMs != segment.fromMs
            else -> true
        }
    }

    /** The source position that sounds [elapsedMs] into the call, or null during silence. Starts at 0, always. */
    fun sourceAt(elapsedMs: Long): Long? {
        var t = elapsedMs.coerceAtLeast(0) % cycleMs
        for (segment in segments) {
            if (t < segment.lengthMs) {
                return when (segment) {
                    is Segment.Play -> segment.fromMs + t
                    is Segment.Silence -> null
                }
            }
            t -= segment.lengthMs
        }
        return null
    }

    companion object {
        /**
         * The timeline of [sound] whose file lasts [sourceMs]. Loop points the validator would reject (too short, past
         * the end) fall back to looping the whole file, as a broken pack should still preview.
         */
        fun of(
            sound: RingSound,
            sourceMs: Long,
        ): RingTimeline {
            val plan =
                runCatching { RingFiles.plan(sound.loop, sound.fadeInMs, sourceMs) }
                    .getOrElse { RingFiles.plan(LoopRange(null, null), sound.fadeInMs, sourceMs) }
            return RingTimeline(plan)
        }
    }
}
