package app.mauri.cucuto.ui.haptics

import androidx.compose.runtime.Immutable
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a tap on an empty part of the timeline drops. */
enum class HapticTool { Click, Tick, LowTick, Envelope }

/**
 * The pattern being edited in the haptic editor, with its selection and undo history. Immutable: every edit returns a
 * new draft. Every edit keeps the timeline valid ([HapticTimeline]): events never overlap, envelopes end at zero
 * intensity and nothing ends after 5 s. An edit that does not fit returns the same draft, so the screen can tell the
 * user with a "reject" haptic.
 *
 * Edits that come in a stream (dragging an event, a point or a slider) pass a gesture key: consecutive edits with the
 * same key are one undo step.
 *
 * @property events The timeline, sorted and valid.
 * @property selected The index of the selected event in [events], or null.
 */
@Immutable
data class HapticDraft(
    val events: List<HapticEvent> = emptyList(),
    val selected: Int? = null,
    private val history: List<List<HapticEvent>> = emptyList(),
    private val gesture: String? = null,
) {
    /** The pattern to save or preview, or null when the timeline is empty. */
    val haptic: Haptic.Custom? get() = events.takeIf { it.isNotEmpty() }?.let(Haptic::Custom)

    /** Where the timeline ends, in milliseconds. */
    val durationMs: Int get() = HapticTimeline.durationOf(events)

    val canUndo: Boolean get() = history.isNotEmpty()

    val selectedEvent: HapticEvent? get() = selected?.let(events::getOrNull)

    /** Whether another event fits (at most [HapticTimeline.MAX_EVENTS]). */
    val canAdd: Boolean get() = events.size < HapticTimeline.MAX_EVENTS

    fun select(index: Int?): HapticDraft = copy(selected = index?.takeIf { it in events.indices }, gesture = null)

    /** Drops the event of [tool] at [atMs], or at the nearest free spot. Selects it. */
    fun add(
        tool: HapticTool,
        atMs: Int,
    ): HapticDraft {
        if (!canAdd) return this
        val event =
            when (tool) {
                HapticTool.Click -> HapticEvent.Preset(0, HapticPreset.CLICK, DEFAULT_CLICK_SCALE)
                HapticTool.Tick -> HapticEvent.Preset(0, HapticPreset.TICK, DEFAULT_TICK_SCALE)
                HapticTool.LowTick -> HapticEvent.Preset(0, HapticPreset.LOW_TICK, DEFAULT_LOW_TICK_SCALE)
                HapticTool.Envelope -> DEFAULT_ENVELOPE
            }
        val start = freeSlot(atMs, HapticTimeline.lengthOf(event), except = null) ?: return this
        return place(HapticTimeline.withStart(event, start), replacing = null, key = null)
    }

    /** Moves event [index] to start at [atMs], or the nearest free spot (it may hop over a neighbour). */
    fun move(
        index: Int,
        atMs: Int,
    ): HapticDraft {
        val event = events.getOrNull(index) ?: return this
        val start = freeSlot(atMs, HapticTimeline.lengthOf(event), except = index) ?: return this
        if (start == event.atMs) return select(index).copy(gesture = gesture)
        return place(HapticTimeline.withStart(event, start), replacing = index, key = "move")
    }

    /** Sets the preset type of event [index] (a preset). */
    fun setPreset(
        index: Int,
        preset: HapticPreset,
    ): HapticDraft {
        val event = events.getOrNull(index) as? HapticEvent.Preset ?: return this
        return replace(index, event.copy(preset = preset), key = null)
    }

    /** Sets the strength of event [index]: a preset's scale, or an envelope's peak (its points scaled to fit). */
    fun setStrength(
        index: Int,
        milli: Int,
    ): HapticDraft {
        val value = milli.coerceIn(0, HapticTimeline.MILLI_ONE)
        val updated =
            when (val event = events.getOrNull(index) ?: return this) {
                is HapticEvent.Preset -> {
                    event.copy(milliScale = value)
                }

                is HapticEvent.Envelope -> {
                    val peak = event.points.maxOf { it.milliIntensity }
                    val points =
                        if (peak == 0) {
                            event.points.dropLast(1).map { it.copy(milliIntensity = value) } + event.points.last()
                        } else {
                            event.points.map {
                                it.copy(milliIntensity = (it.milliIntensity.toLong() * value / peak).toInt())
                            }
                        }
                    event.copy(points = points)
                }
            }
        return replace(index, updated, key = "strength$index")
    }

    /** Sets the sharpness of every point of envelope [index]. */
    fun setSharpness(
        index: Int,
        milli: Int,
    ): HapticDraft {
        val event = events.getOrNull(index) as? HapticEvent.Envelope ?: return this
        val value = milli.coerceIn(0, HapticTimeline.MILLI_ONE)
        val updated =
            event.copy(
                points = event.points.map { it.copy(milliSharpness = value) },
                milliInitialSharpness = HapticEvent.Envelope.DEFAULT_SHARPNESS,
            )
        return replace(index, updated, key = "sharpness$index")
    }

    /**
     * Drags point [point] of envelope [index] to [milliIntensity], reached [durationMs] after the previous point. The
     * last point stays at zero intensity; the duration is kept between [HapticTimeline.MIN_SEGMENT_MS] and what fits
     * before the next event.
     */
    fun movePoint(
        index: Int,
        point: Int,
        milliIntensity: Int,
        durationMs: Int,
    ): HapticDraft {
        val event = events.getOrNull(index) as? HapticEvent.Envelope ?: return this
        val current = event.points.getOrNull(point) ?: return this
        val others = event.points.sumOf { it.durationMs } - current.durationMs
        val limit = events.getOrNull(index + 1)?.atMs ?: HapticTimeline.MAX_MS
        val maxDuration = limit - event.atMs - others
        if (maxDuration < HapticTimeline.MIN_SEGMENT_MS) return this
        val moved =
            current.copy(
                milliIntensity = if (point == event.points.lastIndex) 0 else milliIntensity.coerceIn(0, 1000),
                durationMs = durationMs.coerceIn(HapticTimeline.MIN_SEGMENT_MS, maxDuration),
            )
        val points = event.points.toMutableList().also { it[point] = moved }
        return replace(index, event.copy(points = points), key = "point$index.$point")
    }

    /** Splits the longest segment of envelope [index] in two, halfway, up to [HapticTimeline.MAX_POINTS] points. */
    fun addPoint(index: Int): HapticDraft {
        val event = events.getOrNull(index) as? HapticEvent.Envelope ?: return this
        if (event.points.size >= HapticTimeline.MAX_POINTS) return this
        val longest = event.points.indices.maxBy { event.points[it].durationMs }
        val target = event.points[longest]
        if (target.durationMs < 2 * HapticTimeline.MIN_SEGMENT_MS) return this
        val fromIntensity = event.points.getOrNull(longest - 1)?.milliIntensity ?: 0
        val firstHalf = target.durationMs / 2
        val middle =
            ControlPoint(
                milliIntensity = (fromIntensity + target.milliIntensity) / 2,
                milliSharpness = target.milliSharpness,
                durationMs = firstHalf,
            )
        val points = event.points.toMutableList()
        points[longest] = target.copy(durationMs = target.durationMs - firstHalf)
        points.add(longest, middle)
        return replace(index, event.copy(points = points), key = null)
    }

    /** Removes point [point] of envelope [index] (its time goes to the next point). Keeps at least two points. */
    fun removePoint(
        index: Int,
        point: Int,
    ): HapticDraft {
        val event = events.getOrNull(index) as? HapticEvent.Envelope ?: return this
        if (event.points.size <= 2 || point !in 0 until event.points.lastIndex) return this
        val points = event.points.toMutableList()
        val removed = points.removeAt(point)
        points[point] = points[point].let { it.copy(durationMs = it.durationMs + removed.durationMs) }
        return replace(index, event.copy(points = points), key = null)
    }

    /** Removes event [index]. */
    fun delete(index: Int): HapticDraft {
        if (index !in events.indices) return this
        return record(events.filterIndexed { i, _ -> i != index }, selected = null, key = null)
    }

    /** Replaces the whole timeline, such as with a tap-the-rhythm recording. */
    fun replaceAll(newEvents: List<HapticEvent>): HapticDraft =
        record(HapticTimeline.normalize(newEvents), selected = null, key = null)

    fun clear(): HapticDraft = if (events.isEmpty()) this else replaceAll(emptyList())

    /** Goes back one step. */
    fun undo(): HapticDraft {
        val previous = history.lastOrNull() ?: return this
        return HapticDraft(events = previous, selected = null, history = history.dropLast(1))
    }

    /**
     * The event at [atMs] within [toleranceMs] (the nearest when several are close), for taps and drags on the
     * timeline. Null on an empty part.
     */
    fun eventAt(
        atMs: Int,
        toleranceMs: Int,
    ): Int? =
        events.indices
            .filter { atMs in (events[it].atMs - toleranceMs)..(HapticTimeline.endOf(events[it]) + toleranceMs) }
            .minByOrNull { i ->
                val event = events[i]
                if (atMs in event.atMs..HapticTimeline.endOf(event)) 0 else abs(atMs - event.atMs)
            }

    /**
     * The start nearest to [atMs] where an event of [length] fits without overlapping the others (except [except])
     * and ends by [HapticTimeline.MAX_MS]; null when there is no room.
     */
    internal fun freeSlot(
        atMs: Int,
        length: Int,
        except: Int?,
    ): Int? {
        val busy = events.filterIndexed { i, _ -> i != except }.map { it.atMs to HapticTimeline.endOf(it) }
        val latest = HapticTimeline.MAX_MS - length
        if (latest < 0) return null
        val candidates =
            buildList {
                add(atMs.coerceIn(0, latest))
                for ((start, end) in busy) {
                    add(end)
                    add(start - length)
                }
            }
        return candidates
            .filter { it in 0..latest }
            .filter { start -> busy.none { (s, e) -> start < e && s < start + length } }
            .minByOrNull { abs(it - atMs) }
    }

    private fun replace(
        index: Int,
        event: HapticEvent,
        key: String?,
    ): HapticDraft {
        if (events[index] == event) return this
        return record(events.toMutableList().also { it[index] = event }, selected = index, key = key)
    }

    /** Puts [event] on the timeline in start order (instead of event [replacing]) and selects it. */
    private fun place(
        event: HapticEvent,
        replacing: Int?,
        key: String?,
    ): HapticDraft {
        val rest = events.filterIndexed { i, _ -> i != replacing }
        val position = rest.indexOfFirst { it.atMs > event.atMs }.let { if (it < 0) rest.size else it }
        val updated = rest.toMutableList().also { it.add(position, event) }
        return record(updated, selected = position, key = key)
    }

    private fun record(
        newEvents: List<HapticEvent>,
        selected: Int?,
        key: String?,
    ): HapticDraft {
        val coalesce = key != null && key == gesture
        return HapticDraft(
            events = newEvents,
            selected = selected,
            history = if (coalesce) history else (history + listOf(events)).takeLast(MAX_UNDO),
            gesture = key,
        )
    }

    companion object {
        const val MAX_UNDO: Int = 50
        const val DEFAULT_CLICK_SCALE: Int = 800
        const val DEFAULT_TICK_SCALE: Int = 600
        const val DEFAULT_LOW_TICK_SCALE: Int = 800

        /** A soft swell: up in 40 ms, held for 120 ms, down in 100 ms. */
        val DEFAULT_ENVELOPE: HapticEvent.Envelope =
            HapticEvent.Envelope(
                0,
                listOf(ControlPoint(800, 500, 40), ControlPoint(800, 500, 120), ControlPoint(0, 300, 100)),
            )

        /** A draft that starts from [haptic] (made valid), or an empty one. */
        fun of(haptic: Haptic.Custom?): HapticDraft = HapticDraft(HapticTimeline.normalize(haptic?.events.orEmpty()))

        /** A fraction in 0..1 as thousandths. */
        fun milli(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * HapticTimeline.MILLI_ONE).roundToInt()
    }
}
