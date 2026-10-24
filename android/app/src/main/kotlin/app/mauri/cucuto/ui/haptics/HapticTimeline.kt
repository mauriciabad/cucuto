package app.mauri.cucuto.ui.haptics

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.HapticEvent

/**
 * The pack format's rules for a custom haptic timeline (`docs/pack-format.md#haptics`), as the haptic editor applies
 * them. Whatever the editor saves passes the importer's `HAPTIC_INVALID` checks, so overrides behave exactly like pack
 * haptics in the module and survive a backup and restore.
 */
object HapticTimeline {
    /** The whole timeline lasts at most 5,000 ms. */
    const val MAX_MS: Int = 5000

    /** 1 to 64 events. */
    const val MAX_EVENTS: Int = 64

    /** 1 to 16 control points per envelope (also the budget the reference recommends across devices). */
    const val MAX_POINTS: Int = 16

    /**
     * The shortest segment the editor makes: the minimum every device with envelope support guarantees
     * (`VibratorEnvelopeEffectInfo`), and the auto analyser's frame.
     */
    const val MIN_SEGMENT_MS: Int = 20

    /**
     * The room a preset takes on the timeline. Preset lengths depend on the device; Android recommends leaving 50 ms
     * or more between events, so the editor keeps that gap after every preset.
     */
    const val PRESET_SLOT_MS: Int = 50

    /** Thousandths for "1.0". */
    const val MILLI_ONE: Int = 1000

    /** How long [event] occupies the timeline: its envelope, or [PRESET_SLOT_MS] for a preset. */
    fun lengthOf(event: HapticEvent): Int =
        when (event) {
            is HapticEvent.Preset -> PRESET_SLOT_MS
            is HapticEvent.Envelope -> event.points.sumOf { it.durationMs }
        }

    /** Where [event] ends, in milliseconds from the start. */
    fun endOf(event: HapticEvent): Int = event.atMs + lengthOf(event)

    /** Where the timeline ends: the latest event end, or 0 when empty. */
    fun durationOf(events: List<HapticEvent>): Int = events.maxOfOrNull(::endOf) ?: 0

    /**
     * [events] as a valid timeline: values clamped to their ranges, at most [MAX_POINTS] points per envelope, every
     * envelope closed at zero intensity, events sorted, an event that would start inside the previous one moved to
     * its end, events that would end after [MAX_MS] dropped, and at most [MAX_EVENTS] events.
     */
    fun normalize(events: List<HapticEvent>): List<HapticEvent> {
        val result = ArrayList<HapticEvent>(events.size)
        var free = 0
        for (event in events.map(::clamp).sortedBy { it.atMs }) {
            if (result.size == MAX_EVENTS) break
            val moved = withStart(event, maxOf(event.atMs, free))
            if (endOf(moved) > MAX_MS) continue
            result += moved
            free = endOf(moved)
        }
        return result
    }

    /** Whether [events] already is a valid, non-empty timeline (what [normalize] would leave unchanged). */
    fun isValid(events: List<HapticEvent>): Boolean = events.isNotEmpty() && normalize(events) == events

    /** [event] starting at [atMs]. */
    fun withStart(
        event: HapticEvent,
        atMs: Int,
    ): HapticEvent =
        when (event) {
            is HapticEvent.Preset -> event.copy(atMs = atMs)
            is HapticEvent.Envelope -> event.copy(atMs = atMs)
        }

    private fun clamp(event: HapticEvent): HapticEvent =
        when (event) {
            is HapticEvent.Preset -> {
                event.copy(atMs = event.atMs.coerceIn(0, MAX_MS), milliScale = event.milliScale.coerceIn(0, MILLI_ONE))
            }

            is HapticEvent.Envelope -> {
                val points =
                    event.points.take(MAX_POINTS).map {
                        ControlPoint(
                            milliIntensity = it.milliIntensity.coerceIn(0, MILLI_ONE),
                            milliSharpness = it.milliSharpness.coerceIn(0, MILLI_ONE),
                            durationMs = it.durationMs.coerceIn(1, MAX_MS),
                        )
                    }
                event.copy(
                    atMs = event.atMs.coerceIn(0, MAX_MS),
                    points = closed(points),
                    milliInitialSharpness =
                        if (event.milliInitialSharpness == HapticEvent.Envelope.DEFAULT_SHARPNESS) {
                            HapticEvent.Envelope.DEFAULT_SHARPNESS
                        } else {
                            event.milliInitialSharpness.coerceIn(0, MILLI_ONE)
                        },
                )
            }
        }

    /** [points] ending at zero intensity, as `BasicEnvelopeBuilder` requires. */
    private fun closed(points: List<ControlPoint>): List<ControlPoint> {
        val last = points.last()
        return when {
            last.milliIntensity == 0 -> points
            points.size < MAX_POINTS -> points + ControlPoint(0, last.milliSharpness, MIN_SEGMENT_MS)
            else -> points.dropLast(1) + last.copy(milliIntensity = 0)
        }
    }
}
