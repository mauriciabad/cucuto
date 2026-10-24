package app.mauri.cucuto.ui.haptics

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import kotlin.math.roundToInt

/**
 * "Tap the rhythm" (brief, Haptic editor): records presses on a pad and turns them into a haptic timeline. The first
 * press starts the clock (it lands at 0 ms); recording ends after [HapticTimeline.MAX_MS] or
 * [HapticTimeline.MAX_EVENTS] presses. Times come from the caller (`uptimeMillis` of pointer events), so it is pure.
 *
 * How long a press lasts sets what it becomes ([RhythmMapping]): a quick tap is a light `TICK`, a firmer tap a
 * `CLICK`, and a hold an envelope that lasts as long as the finger stays down. Longer presses are stronger.
 */
class RhythmRecorder {
    private data class Press(
        val atMs: Int,
        val holdMs: Int,
    )

    private val presses = ArrayList<Press>()
    private var originMs: Long? = null
    private var downAtMs: Long? = null

    /** Presses recorded so far (a press still held counts). */
    val count: Int get() = presses.size + if (downAtMs != null) 1 else 0

    /** Whether a finger is down. */
    val isPressed: Boolean get() = downAtMs != null

    /** Whether nothing was pressed yet. */
    val isEmpty: Boolean get() = count == 0

    /** Milliseconds since the first press, or 0 before it. */
    fun elapsedMs(nowMs: Long): Int = originMs?.let { (nowMs - it).toIntClamped() } ?: 0

    /** Whether no more presses fit: the timeline is full or [HapticTimeline.MAX_EVENTS] were recorded. */
    fun isFull(nowMs: Long): Boolean =
        presses.size >= HapticTimeline.MAX_EVENTS ||
            (originMs != null && elapsedMs(nowMs) >= HapticTimeline.MAX_MS - HapticTimeline.PRESET_SLOT_MS)

    /** A finger went down at [nowMs]. Returns false (ignored) when [isFull] or a press is already down. */
    fun press(nowMs: Long): Boolean {
        if (downAtMs != null || isFull(nowMs)) return false
        if (originMs == null) originMs = nowMs
        downAtMs = nowMs
        return true
    }

    /** The finger went up at [nowMs]. Ignored without a press. */
    fun release(nowMs: Long) {
        val down = downAtMs ?: return
        val origin = originMs ?: return
        downAtMs = null
        presses += Press(atMs = (down - origin).toIntClamped(), holdMs = (nowMs - down).toIntClamped())
    }

    /** Forgets every press. */
    fun reset() {
        presses.clear()
        originMs = null
        downAtMs = null
    }

    /**
     * The recorded timeline. A press still held at [nowMs] counts as released then. Each envelope's release ramp is
     * shortened so it ends before the next press, and the result is a valid timeline ([HapticTimeline.normalize]).
     */
    fun events(nowMs: Long): List<HapticEvent> {
        val all = presses.toMutableList()
        val down = downAtMs
        val origin = originMs
        if (down != null && origin != null) {
            all += Press(atMs = (down - origin).toIntClamped(), holdMs = (nowMs - down).toIntClamped())
        }
        val events =
            all.mapIndexed { i, press ->
                // A hold that runs past the end of the timeline is cut so that it still fits, fade included.
                val room = (HapticTimeline.MAX_MS - press.atMs - RhythmMapping.RELEASE_MS).coerceAtLeast(0)
                RhythmMapping.event(press.atMs, minOf(press.holdMs, room), nextAtMs = all.getOrNull(i + 1)?.atMs)
            }
        return HapticTimeline.normalize(events)
    }

    private fun Long.toIntClamped(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

/** How one press of "tap the rhythm" becomes an event: tap length maps to the kind, the intensity and the duration. */
object RhythmMapping {
    /** Presses up to this long are a `TICK`. */
    const val TICK_MAX_MS: Int = 70

    /** Presses up to this long (and longer than [TICK_MAX_MS]) are a `CLICK`; longer ones are held envelopes. */
    const val CLICK_MAX_MS: Int = 180

    /** A hold reaches full intensity at this length. */
    const val FULL_HOLD_MS: Int = 1000

    /** The rise at the start of a held envelope. */
    const val ATTACK_MS: Int = HapticTimeline.MIN_SEGMENT_MS

    /** The fade at the end of a held envelope, when the next press leaves room for it. */
    const val RELEASE_MS: Int = 80

    /**
     * The event for a press at [atMs] held for [holdMs]. [nextAtMs] (the next press) limits the release ramp, so a
     * quick rhythm keeps its timing.
     */
    fun event(
        atMs: Int,
        holdMs: Int,
        nextAtMs: Int? = null,
    ): HapticEvent {
        val hold = holdMs.coerceAtLeast(0)
        return when {
            hold <= TICK_MAX_MS -> {
                HapticEvent.Preset(atMs, HapticPreset.TICK, lerp(TICK_SCALE_MIN, TICK_SCALE_MAX, hold, 0, TICK_MAX_MS))
            }

            hold <= CLICK_MAX_MS -> {
                HapticEvent.Preset(
                    atMs,
                    HapticPreset.CLICK,
                    lerp(CLICK_SCALE_MIN, HapticTimeline.MILLI_ONE, hold, TICK_MAX_MS, CLICK_MAX_MS),
                )
            }

            else -> {
                val intensity = lerp(HOLD_INTENSITY_MIN, HapticTimeline.MILLI_ONE, hold, CLICK_MAX_MS, FULL_HOLD_MS)
                // Longer holds feel rounder: sharpness falls from crisp to soft.
                val sharpness = lerp(HOLD_SHARPNESS_MAX, HOLD_SHARPNESS_MIN, hold, CLICK_MAX_MS, FULL_HOLD_MS)
                val room = nextAtMs?.let { it - atMs - hold } ?: RELEASE_MS
                val release = room.coerceIn(HapticTimeline.MIN_SEGMENT_MS, RELEASE_MS)
                val sustain = (hold - ATTACK_MS).coerceAtLeast(HapticTimeline.MIN_SEGMENT_MS)
                HapticEvent.Envelope(
                    atMs,
                    listOf(
                        ControlPoint(intensity, sharpness, ATTACK_MS),
                        ControlPoint(intensity, sharpness, sustain),
                        ControlPoint(0, sharpness, release),
                    ),
                )
            }
        }
    }

    /** [from] at [x] = [x0] to [to] at [x] = [x1], clamped. */
    private fun lerp(
        from: Int,
        to: Int,
        x: Int,
        x0: Int,
        x1: Int,
    ): Int {
        val t = ((x - x0).toFloat() / (x1 - x0)).coerceIn(0f, 1f)
        return (from + (to - from) * t).roundToInt()
    }

    private const val TICK_SCALE_MIN = 500
    private const val TICK_SCALE_MAX = 700
    private const val CLICK_SCALE_MIN = 700
    private const val HOLD_INTENSITY_MIN = 600
    private const val HOLD_SHARPNESS_MAX = 700
    private const val HOLD_SHARPNESS_MIN = 300
}
