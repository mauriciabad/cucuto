package app.mauri.cucuto.ui.haptics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Colors of the timeline, from the theme by default. */
data class HapticTimelineColors(
    val grid: Color,
    val label: Color,
    val click: Color,
    val tick: Color,
    val lowTick: Color,
    val envelope: Color,
    val envelopeFill: Color,
    val selection: Color,
    val playhead: Color,
)

@Composable
fun hapticTimelineColors(): HapticTimelineColors {
    val scheme = MaterialTheme.colorScheme
    return HapticTimelineColors(
        grid = scheme.outlineVariant,
        label = scheme.onSurfaceVariant,
        click = scheme.primary,
        tick = scheme.secondary,
        lowTick = scheme.tertiary,
        envelope = scheme.primary,
        envelopeFill = scheme.primary.copy(alpha = 0.24f),
        selection = scheme.inversePrimary,
        playhead = scheme.error,
    )
}

/** The time window that fits [durationMs] with some room after it: 1.5 s to 5 s, in half-second steps. */
fun timelineWindowMs(durationMs: Int): Int {
    val padded = maxOf(durationMs, MIN_WINDOW_MS - WINDOW_ROOM_MS) + WINDOW_ROOM_MS
    val rounded = (padded + WINDOW_STEP_MS - 1) / WINDOW_STEP_MS * WINDOW_STEP_MS
    return rounded.coerceIn(MIN_WINDOW_MS, HapticTimeline.MAX_MS)
}

private const val MIN_WINDOW_MS = 1500
private const val WINDOW_ROOM_MS = 500
private const val WINDOW_STEP_MS = 500

/**
 * The haptic timeline, drawn live: presets as bars as tall as their strength (a round head for `CLICK`, a thin bar for
 * `TICK`, a square head for `LOW_TICK`, so the kind never depends on color alone), envelopes as their intensity
 * curve, and the playhead while a preview plays.
 *
 * When [interactive], a tap reports its time ([onTap], with a touch tolerance in milliseconds), dragging an event
 * moves it ([onMoveEvent]) and dragging a handle of the selected envelope reshapes it ([onMovePoint]: the point's
 * intensity and the duration of its segment). Screen readers get the same edits from the event panel.
 *
 * @param windowMs The time the width shows, from 0.
 * @param playhead The playhead position in milliseconds, or null.
 */
@Composable
fun HapticTimelineView(
    events: List<HapticEvent>,
    selected: Int?,
    windowMs: Int,
    modifier: Modifier = Modifier,
    playhead: Float? = null,
    interactive: Boolean = true,
    colors: HapticTimelineColors = hapticTimelineColors(),
    onTap: (atMs: Int, toleranceMs: Int) -> Unit = { _, _ -> },
    onDragStart: (index: Int) -> Unit = {},
    onMoveEvent: (index: Int, atMs: Int) -> Unit = { _, _ -> },
    onMovePoint: (index: Int, point: Int, milliIntensity: Int, durationMs: Int) -> Unit = { _, _, _, _ -> },
    onDragEnd: () -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.label)
    val density = LocalDensity.current
    val pad = with(density) { EDGE.toPx() }
    val touch = with(density) { TOUCH.toPx() }
    val currentEvents by rememberUpdatedState(events)
    val currentSelected by rememberUpdatedState(selected)
    val currentWindow by rememberUpdatedState(windowMs)
    val tap by rememberUpdatedState(onTap)
    val dragStart by rememberUpdatedState(onDragStart)
    val moveEvent by rememberUpdatedState(onMoveEvent)
    val movePoint by rememberUpdatedState(onMovePoint)
    val dragEnd by rememberUpdatedState(onDragEnd)
    var drag by remember { mutableStateOf<TimelineDrag?>(null) }

    val gestures =
        if (!interactive) {
            Modifier
        } else {
            Modifier
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val geometry = TimelineGeometry(size.width.toFloat(), size.height.toFloat(), pad, currentWindow)
                        tap(geometry.msAt(offset.x), geometry.msPerPx(touch))
                    }
                }.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val geometry =
                                TimelineGeometry(size.width.toFloat(), size.height.toFloat(), pad, currentWindow)
                            drag = startDrag(offset, geometry, touch, currentEvents, currentSelected)
                            drag?.let { dragStart(it.index) }
                        },
                        onDragEnd = {
                            drag = null
                            dragEnd()
                        },
                        onDragCancel = {
                            drag = null
                            dragEnd()
                        },
                        onDrag = { change, _ ->
                            val current = drag ?: return@detectDragGestures
                            val geometry =
                                TimelineGeometry(size.width.toFloat(), size.height.toFloat(), pad, currentWindow)
                            val atMs = geometry.msAt(change.position.x)
                            when (current) {
                                is TimelineDrag.Event -> {
                                    moveEvent(current.index, atMs - current.grabMs)
                                }

                                is TimelineDrag.Point -> {
                                    val event = currentEvents.getOrNull(current.index) as? HapticEvent.Envelope
                                    if (event != null) {
                                        val before = event.points.take(current.point).sumOf { it.durationMs }
                                        movePoint(
                                            current.index,
                                            current.point,
                                            geometry.milliAt(change.position.y),
                                            atMs - event.atMs - before,
                                        )
                                    }
                                }
                            }
                            change.consume()
                        },
                    )
                }
        }

    Canvas(modifier.then(gestures)) {
        val geometry = TimelineGeometry(size.width, size.height, pad, windowMs)
        drawGrid(geometry, colors) { ms ->
            val text = measurer.measure("${ms / 1000} s", labelStyle)
            drawText(text, topLeft = Offset(geometry.x(ms) + 4f, size.height - text.size.height))
        }
        events.forEachIndexed { index, event ->
            val isSelected = index == selected
            when (event) {
                is HapticEvent.Preset -> drawPreset(event, geometry, colors, isSelected, PresetMarks.Editor)
                is HapticEvent.Envelope -> drawEnvelope(event, geometry, colors, isSelected, interactive)
            }
        }
        playhead?.let { ms ->
            val x = geometry.x(ms.roundToInt())
            drawLine(colors.playhead, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

/**
 * A character's vibration as a small picture (#301, ADR 0069): the same marks as [HapticTimelineView] (presets as bars
 * as tall as their strength with the kind's head, envelopes as their filled intensity curve) on a baseline, without
 * grid, labels or touch. The pattern fills the width: the window is its own length (at least [minWindowMs]), so short
 * taps stay readable. Decorative: the caller says what it is to screen readers.
 */
@Composable
fun HapticPatternThumbnail(
    events: List<HapticEvent>,
    modifier: Modifier = Modifier,
    minWindowMs: Int = THUMBNAIL_MIN_WINDOW_MS,
    colors: HapticTimelineColors = hapticTimelineColors(),
) {
    val windowMs = maxOf(HapticTimeline.durationOf(events), minWindowMs)
    Canvas(modifier) {
        val pad = THUMBNAIL_EDGE.toPx()
        val geometry = TimelineGeometry(size.width, size.height, pad, windowMs, bottomInset = pad)
        drawLine(
            colors.grid,
            Offset(0f, geometry.bottom),
            Offset(size.width, geometry.bottom),
            strokeWidth = 1.dp.toPx(),
        )
        events.forEach { event ->
            when (event) {
                is HapticEvent.Preset -> drawPreset(event, geometry, colors, false, PresetMarks.Thumbnail)
                is HapticEvent.Envelope -> drawEnvelope(event, geometry, colors, selected = false, showHandles = false)
            }
        }
    }
}

/** A thumbnail shows at least this much time, so a single tap doesn't stretch across it. */
private const val THUMBNAIL_MIN_WINDOW_MS = 400
private val THUMBNAIL_EDGE: Dp = 4.dp

private val EDGE: Dp = 12.dp
private val TOUCH: Dp = 24.dp
private val LABEL_SPACE: Dp = 16.dp

/** Maps milliseconds and thousandths to pixels and back. */
private class TimelineGeometry(
    val width: Float,
    val height: Float,
    val pad: Float,
    val windowMs: Int,
    /** Room under the baseline: the editor keeps space for the second labels. */
    bottomInset: Float = pad * 2,
) {
    private val usableWidth = (width - 2 * pad).coerceAtLeast(1f)
    val top = pad
    val bottom = (height - bottomInset).coerceAtLeast(top + 1f)

    fun x(ms: Int): Float = pad + ms.toFloat() / windowMs * usableWidth

    fun y(milli: Int): Float = bottom - milli / 1000f * (bottom - top)

    fun msAt(x: Float): Int = ((x - pad) / usableWidth * windowMs).roundToInt().coerceIn(0, windowMs)

    fun milliAt(y: Float): Int = ((bottom - y) / (bottom - top) * 1000).roundToInt().coerceIn(0, 1000)

    fun msPerPx(px: Float): Int = (px / usableWidth * windowMs).roundToInt()
}

private sealed interface TimelineDrag {
    val index: Int

    data class Event(
        override val index: Int,
        val grabMs: Int,
    ) : TimelineDrag

    data class Point(
        override val index: Int,
        val point: Int,
    ) : TimelineDrag
}

/** What a drag starting at [offset] grabs: a handle of the selected envelope first, else an event. */
private fun startDrag(
    offset: Offset,
    geometry: TimelineGeometry,
    touch: Float,
    events: List<HapticEvent>,
    selected: Int?,
): TimelineDrag? {
    val envelope = selected?.let { events.getOrNull(it) } as? HapticEvent.Envelope
    if (envelope != null) {
        val handle =
            handles(envelope, geometry)
                .withIndex()
                .map { (i, p) -> i to hypot(p.x - offset.x, p.y - offset.y) }
                .filter { it.second <= touch }
                .minByOrNull { it.second }
        if (handle != null) return TimelineDrag.Point(selected, handle.first)
    }
    val atMs = geometry.msAt(offset.x)
    val tolerance = geometry.msPerPx(touch)
    val hit = HapticDraft(events).eventAt(atMs, tolerance) ?: return null
    return TimelineDrag.Event(hit, grabMs = atMs - events[hit].atMs)
}

/** Where each control point of [event] is drawn. */
private fun handles(
    event: HapticEvent.Envelope,
    geometry: TimelineGeometry,
): List<Offset> {
    var t = event.atMs
    return event.points.map { point ->
        t += point.durationMs
        Offset(geometry.x(t), geometry.y(point.milliIntensity))
    }
}

private fun DrawScope.drawGrid(
    geometry: TimelineGeometry,
    colors: HapticTimelineColors,
    label: DrawScope.(ms: Int) -> Unit,
) {
    val labelSpace = LABEL_SPACE.toPx()
    drawLine(
        colors.grid,
        Offset(geometry.x(0), geometry.bottom),
        Offset(geometry.x(geometry.windowMs), geometry.bottom),
    )
    var ms = 0
    while (ms <= geometry.windowMs) {
        val major = ms % 1000 == 0
        val x = geometry.x(ms)
        drawLine(
            colors.grid.copy(alpha = if (major) 1f else 0.4f),
            Offset(x, geometry.top),
            Offset(x, if (major) size.height - labelSpace else geometry.bottom),
            strokeWidth = if (major) 1.dp.toPx() else 0.5.dp.toPx(),
        )
        if (major) label(ms)
        ms += 100
    }
}

/** How big a preset's bar and head are drawn: full size in the editor, smaller in a thumbnail. */
private enum class PresetMarks(
    val bar: Dp,
    val thinBar: Dp,
    val head: Dp,
) {
    Editor(bar = 5.dp, thinBar = 3.dp, head = 5.dp),
    Thumbnail(bar = 3.dp, thinBar = 2.dp, head = 3.dp),
}

private fun DrawScope.drawPreset(
    event: HapticEvent.Preset,
    geometry: TimelineGeometry,
    colors: HapticTimelineColors,
    selected: Boolean,
    marks: PresetMarks,
) {
    val x = geometry.x(event.atMs)
    val top = geometry.y(event.milliScale)
    val color =
        when (event.preset) {
            HapticPreset.CLICK -> colors.click
            HapticPreset.TICK -> colors.tick
            HapticPreset.LOW_TICK -> colors.lowTick
        }
    val barWidth = if (event.preset == HapticPreset.TICK) marks.thinBar.toPx() else marks.bar.toPx()
    val head = marks.head.toPx()
    if (selected) {
        val halo = 10.dp.toPx()
        drawRoundRect(
            colors.selection,
            topLeft = Offset(x - halo, top - halo),
            size = Size(halo * 2, geometry.bottom - top + halo),
            cornerRadius = CornerRadius(halo),
        )
    }
    drawRoundRect(
        color,
        topLeft = Offset(x - barWidth / 2, top),
        size = Size(barWidth, geometry.bottom - top),
        cornerRadius = CornerRadius(barWidth / 2),
    )
    when (event.preset) {
        HapticPreset.CLICK -> {
            drawCircle(color, radius = head, center = Offset(x, top))
        }

        HapticPreset.LOW_TICK -> {
            drawRect(
                color,
                topLeft = Offset(x - head, top - head),
                size = Size(head * 2, head * 2),
            )
        }

        HapticPreset.TICK -> {
            Unit
        }
    }
}

private fun DrawScope.drawEnvelope(
    event: HapticEvent.Envelope,
    geometry: TimelineGeometry,
    colors: HapticTimelineColors,
    selected: Boolean,
    showHandles: Boolean,
) {
    val points = handles(event, geometry)
    val start = Offset(geometry.x(event.atMs), geometry.bottom)
    val outline =
        Path().apply {
            moveTo(start.x, start.y)
            points.forEach { lineTo(it.x, it.y) }
        }
    val fill =
        Path().apply {
            addPath(outline)
            lineTo(points.last().x, geometry.bottom)
            close()
        }
    if (selected) drawPath(fill, colors.selection)
    drawPath(fill, colors.envelopeFill)
    drawPath(outline, colors.envelope, style = Stroke(width = 2.dp.toPx()))
    if (selected && showHandles) {
        val radius = 6.dp.toPx()
        points.forEach {
            drawCircle(colors.envelope, radius = radius, center = it)
            drawCircle(colors.selection, radius = radius / 2, center = it)
        }
    }
}
