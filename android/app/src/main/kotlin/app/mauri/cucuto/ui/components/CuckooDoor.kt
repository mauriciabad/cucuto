package app.mauri.cucuto.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.feedback.rememberBeatSeconds
import app.mauri.cucuto.ui.motion.Segment
import app.mauri.cucuto.ui.motion.Springs
import app.mauri.cucuto.ui.motion.Track
import app.mauri.cucuto.ui.motion.rememberSprings
import app.mauri.cucuto.ui.theme.CucutoTheme
import app.mauri.cucuto.ui.theme.LocalReducedMotion

/**
 * Cucuto's signature, "the door pops open" (brand/motion.md): the cuckoo-clock house from the logo, its round door
 * swinging open on a hinge and a character popping out, squashing and stretching on fast spatial springs, then
 * calling with sound arcs. Plays once per [beat] (fire `Feedback.DoorOpen` or `Feedback.Assign` and pass the beat);
 * with a null beat the door is closed.
 *
 * Timeline after the beat (the sound's first note "cu-" is the door unlatching, the second "-cu!" at 130 ms lands
 * as the character arrives, which is also when the character's own clip starts):
 * door opens at 0 ms (fast spatial), the character pops out at 80 ms (fast spatial), calls at [callsAtMillis];
 * with [closeAfterMillis] it goes back in (default spatial) and the door closes 250 ms later.
 *
 * Colors follow the scheme (wrap it in a `CharacterTheme` for a character's colors): house `primaryContainer`,
 * door `primary`. [character] is clipped to the door opening, like a sprite; null draws the Cucuto bird.
 * With "Remove animations" on, the door cross-fades to the open state and nothing bounces.
 */
@Composable
fun CuckooDoor(
    beat: FeedbackBeat?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    closeAfterMillis: Long? = null,
    callsAtMillis: List<Long> = listOf(Feedback.Assign.CHARACTER_CLIP_DELAY_MS),
    character: (@Composable () -> Unit)? = null,
) {
    val timeline = rememberDoorTimeline(closeAfterMillis, callsAtMillis)
    val seconds = rememberBeatSeconds(beat, timeline.endSeconds)
    DoorScene({ seconds.floatValue }, timeline, modifier, contentDescription, character)
}

/** The door frozen [seconds] after its beat: previews, filmstrips and screenshot tests. */
@Composable
fun CuckooDoorStill(
    seconds: Float,
    modifier: Modifier = Modifier,
    closeAfterMillis: Long? = null,
    callsAtMillis: List<Long> = listOf(Feedback.Assign.CHARACTER_CLIP_DELAY_MS),
    character: (@Composable () -> Unit)? = null,
) {
    val timeline = rememberDoorTimeline(closeAfterMillis, callsAtMillis)
    DoorScene({ seconds }, timeline, modifier, contentDescription = null, character = character)
}

@Composable
private fun rememberDoorTimeline(
    closeAfterMillis: Long?,
    callsAtMillis: List<Long>,
): DoorTimeline {
    val springs = rememberSprings()
    val reduced = LocalReducedMotion.current
    return remember(springs, closeAfterMillis, callsAtMillis, reduced) {
        DoorTimeline(springs, closeAfterMillis?.div(MILLIS), callsAtMillis.map { it / MILLIS }, reduced)
    }
}

@Composable
private fun DoorScene(
    seconds: () -> Float,
    timeline: DoorTimeline,
    modifier: Modifier,
    contentDescription: String?,
    character: (@Composable () -> Unit)?,
) {
    val colors = doorColors()
    val semantics = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier

    BoxWithConstraints(modifier.aspectRatio(VIEW_W / VIEW_H).then(semantics)) {
        val unit = maxWidth / VIEW_W
        // House, the door's back once it is open, the dark opening and the default bird.
        Canvas(Modifier.fillMaxSize()) {
            scale(size.width / VIEW_W) {
                val t = seconds()
                drawPath(DoorPaths.house, colors.house)
                drawBackDoor(timeline, t, colors)
                drawCircle(colors.opening, OPENING_R, Offset(OPENING_X, OPENING_Y))
                if (character == null) drawBird(timeline, t)
            }
        }
        if (character != null) {
            Box(
                Modifier
                    .offset(unit * (OPENING_X - OPENING_R), unit * (OPENING_Y - OPENING_R))
                    .requiredSize(unit * OPENING_R * 2)
                    .clip(CircleShape),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val t = seconds()
                            val pose = timeline.bird(t)
                            transformOrigin =
                                TransformOrigin((HEAD_X - OPENING_X + OPENING_R) / (2 * OPENING_R), 0.5f)
                            scaleX = pose.scaleX
                            scaleY = pose.scaleY
                            translationX = pose.offsetX * size.width / (2 * OPENING_R)
                            alpha = pose.alpha
                        },
                ) { character() }
            }
        }
        // The door's front and the sound arcs go over everything.
        Canvas(Modifier.fillMaxSize()) {
            scale(size.width / VIEW_W) {
                val t = seconds()
                drawFrontDoor(timeline, t, colors)
                drawWaves(timeline, t, colors)
            }
        }
    }
}

private fun DrawScope.scale(
    factor: Float,
    block: DrawScope.() -> Unit,
) = withTransform({ scale(factor, factor, pivot = Offset.Zero) }, block)

@Immutable
private data class DoorColors(
    val house: Color,
    val door: Color,
    val opening: Color,
    val knob: Color,
    val waves: Color,
)

@Composable
private fun doorColors(): DoorColors {
    val scheme = MaterialTheme.colorScheme
    val opening = lerp(scheme.primaryContainer, Color.Black, OPENING_DARKEN)
    return DoorColors(
        house = scheme.primaryContainer,
        door = scheme.primary,
        opening = opening,
        knob = opening,
        waves = scheme.primary,
    )
}

private fun DrawScope.drawBackDoor(
    timeline: DoorTimeline,
    t: Float,
    colors: DoorColors,
) {
    val alpha = timeline.backDoorAlpha(t)
    if (alpha <= 0f) return
    drawOval(
        colors.door,
        topLeft = Offset(BACK_DOOR_X - BACK_DOOR_RX, OPENING_Y - BACK_DOOR_RY),
        size = Size(BACK_DOOR_RX * 2, BACK_DOOR_RY * 2),
        alpha = alpha,
    )
}

private fun DrawScope.drawFrontDoor(
    timeline: DoorTimeline,
    t: Float,
    colors: DoorColors,
) {
    val alpha = timeline.frontDoorAlpha(t)
    if (alpha <= 0f) return
    val scaleX = timeline.doorScale(t)
    withTransform({ scale(scaleX, 1f, pivot = Offset(HINGE_X, OPENING_Y)) }) {
        drawCircle(colors.door, OPENING_R, Offset(OPENING_X, OPENING_Y), alpha = alpha)
        // A darker rim so the door reads as a door on any scheme (dynamic and dark schemes put door and house close).
        drawCircle(
            colors.opening,
            OPENING_R - RIM_WIDTH / 2,
            Offset(OPENING_X, OPENING_Y),
            alpha = alpha * RIM_ALPHA,
            style = Stroke(width = RIM_WIDTH),
        )
        drawCircle(colors.knob, KNOB_R, Offset(OPENING_X + KNOB_DX, OPENING_Y), alpha = alpha)
    }
}

private fun DrawScope.drawBird(
    timeline: DoorTimeline,
    t: Float,
) {
    val pose = timeline.bird(t)
    if (pose.alpha <= 0f) return
    withTransform({
        translate(pose.offsetX, 0f)
        scale(pose.scaleX, pose.scaleY, pivot = Offset(HEAD_X, OPENING_Y))
    }) {
        drawCircle(BirdColors.head, HEAD_R, Offset(HEAD_X, OPENING_Y), alpha = pose.alpha)
        drawCircle(BirdColors.eye, EYE_R, Offset(EYE_X, EYE_Y), alpha = pose.alpha)
        withTransform({ rotate(-UPPER_BEAK_DEG * pose.beak, Offset(UPPER_BEAK_PIVOT_X, UPPER_BEAK_PIVOT_Y)) }) {
            drawPath(DoorPaths.upperBeak, BirdColors.beak, alpha = pose.alpha)
        }
        withTransform({ rotate(LOWER_BEAK_DEG * pose.beak, Offset(LOWER_BEAK_PIVOT_X, LOWER_BEAK_PIVOT_Y)) }) {
            drawPath(DoorPaths.lowerBeak, BirdColors.beak, alpha = pose.alpha)
        }
    }
}

private fun DrawScope.drawWaves(
    timeline: DoorTimeline,
    t: Float,
    colors: DoorColors,
) {
    val (alpha, travel) = timeline.waves(t)
    if (alpha <= MIN_ALPHA) return
    val waveScale = WAVE_MIN_SCALE + WAVE_GROWTH * travel
    withTransform({
        translate(WAVE_SHIFT * travel, 0f)
        scale(waveScale, waveScale, pivot = Offset(WAVE_X, OPENING_Y))
    }) {
        WAVE_RADII.forEach { r ->
            drawArc(
                color = colors.waves,
                startAngle = -WAVE_HALF_ANGLE,
                sweepAngle = WAVE_HALF_ANGLE * 2,
                useCenter = false,
                topLeft = Offset(WAVE_X - r, OPENING_Y - r),
                size = Size(r * 2, r * 2),
                alpha = alpha,
                style = Stroke(width = WAVE_STROKE, cap = StrokeCap.Round),
            )
        }
    }
}

/** Where the bird is: scale with squash and stretch, horizontal offset (view units), beak opening, alpha. */
private class BirdPose(
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float,
    val beak: Float,
    val alpha: Float,
)

/**
 * The motion of `brand/tools/motion.mjs`, retimed for the app (plays once from the beat instead of looping) and
 * sampled from the motion scheme's springs.
 */
private class DoorTimeline(
    springs: Springs,
    closeAt: Float?,
    calls: List<Float>,
    private val reduced: Boolean,
) {
    private val doorCloseAt = closeAt?.plus(DOOR_CLOSE_DELAY)
    private val door =
        Track(
            1f,
            listOfNotNull(
                Segment(0f, DOOR_OPEN, springs.fastSpatial),
                doorCloseAt?.let { Segment(it, 1f, springs.defaultSpatial) },
            ),
        )
    private val bird =
        Track(
            0f,
            listOfNotNull(
                Segment(BIRD_OUT_AT, 1f, springs.fastSpatial),
                closeAt?.let { Segment(it, 0f, springs.defaultSpatial) },
            ),
        )
    private val beak = Track(0f, calls.flatMap { listOf(Segment(it, 1f, springs.fastSpatial), Segment(it + BEAK_HOLD, 0f, springs.defaultSpatial)) })
    private val waveAlpha = calls.map { Track(0f, listOf(Segment(it, 1f, springs.defaultEffects), Segment(it + WAVE_HOLD, 0f, springs.slowEffects))) }
    private val waveTravel = calls.map { Track(0f, listOf(Segment(it, 1f, springs.slowSpatial), Segment(it + WAVE_TRAVEL_HOLD, 0f, springs.fastEffects))) }

    // Reduced motion: one cross-fade to the open state (and back), no swing, no pop.
    private val fade =
        Track(
            0f,
            listOfNotNull(
                Segment(0f, 1f, springs.defaultEffects),
                closeAt?.let { Segment(it, 0f, springs.defaultEffects) },
            ),
        )

    val endSeconds: Float = (doorCloseAt ?: calls.maxOrNull() ?: 0f) + SETTLE

    fun doorScale(t: Float): Float = if (reduced) 1f else door.value(t)

    private fun settledOpen(t: Float) = t > SETTLED_AFTER && (doorCloseAt == null || t < doorCloseAt)

    fun frontDoorAlpha(t: Float): Float =
        when {
            reduced -> 1f - fade.value(t).coerceIn(0f, 1f)
            settledOpen(t) -> 0f
            else -> 1f
        }

    fun backDoorAlpha(t: Float): Float =
        when {
            reduced -> fade.value(t).coerceIn(0f, 1f)
            settledOpen(t) -> 1f
            else -> 0f
        }

    fun bird(t: Float): BirdPose {
        if (t < 0f) return BirdPose(MIN_BIRD_SCALE, MIN_BIRD_SCALE, -BIRD_TRAVEL, 0f, 0f)
        if (reduced) return BirdPose(1f, 1f, 0f, 0f, fade.value(t).coerceIn(0f, 1f))
        val b = bird.value(t)
        val stretch = (bird.velocity(t) * STRETCH_PER_VELOCITY).coerceIn(-MAX_STRETCH, MAX_STRETCH)
        val beakOpen = beak.value(t).coerceAtLeast(0f)
        val callSquash = -CALL_SQUASH * beakOpen
        val scale = MIN_BIRD_SCALE + (1f - MIN_BIRD_SCALE) * b.coerceAtLeast(0f)
        return BirdPose(
            scaleX = scale * (1 + stretch) / (1 + callSquash),
            scaleY = scale * (1 + callSquash) / (1 + stretch),
            offsetX = -BIRD_TRAVEL * (1 - b),
            beak = beakOpen,
            alpha = if (b <= 0.001f && t > BIRD_OUT_AT) 0f else 1f,
        )
    }

    fun waves(t: Float): Pair<Float, Float> {
        if (reduced || t < 0f) return 0f to 0f
        var alpha = 0f
        var travel = 0f
        waveAlpha.forEachIndexed { i, track ->
            val a = track.value(t).coerceIn(0f, 1f)
            alpha = maxOf(alpha, a)
            if (a > MIN_ALPHA) travel = maxOf(travel, waveTravel[i].value(t))
        }
        return alpha to travel
    }
}

/** Geometry from the logo (`brand/tools/geometry.mjs`, viewBox 48 × 48, drawn here in a 64 × 48 box). */
private object DoorPaths {
    val house =
        PathParser()
            .parsePathString(
                "M21.078 5.494A4.5 4.5 0 0 1 26.922 5.494L43.272 19.452A3.5 3.5 0 0 1 44.5 22.114L44.5 38" +
                    "A6.5 6.5 0 0 1 38 44.5L10 44.5A6.5 6.5 0 0 1 3.5 38L3.5 22.114A3.5 3.5 0 0 1 4.728 19.452Z",
            ).toPath()
    val upperBeak =
        PathParser()
            .parsePathString(
                "M29.989 26.699A1.4 1.4 0 0 1 31.68 25.08L36.638 26.224A1.318 1.318 0 0 1 36.6 28.8" +
                    "L31.595 29.801A1 1 0 0 1 30.415 29.003Z",
            ).toPath()
    val lowerBeak =
        PathParser()
            .parsePathString(
                "M30.831 32.25A1 1 0 0 1 32.143 31.688L35.7 33.15A0.937 0.937 0 0 1 35.335 34.953" +
                    "L31.247 34.914A1 1 0 0 1 30.324 33.552Z",
            ).toPath()
}

/** The bird keeps the logo's fixed colors (cream, walnut, sunny): it is the mascot, not themed UI. */
private object BirdColors {
    val head = Color(0xFFFFF1EB)
    val eye = Color(0xFF321200)
    val beak = Color(0xFFFFD25E)
}

private const val MILLIS = 1000f
private const val VIEW_W = 64f
private const val VIEW_H = 48f
private const val OPENING_X = 23.5f
private const val OPENING_Y = 30f
private const val OPENING_R = 10f
private const val OPENING_DARKEN = 0.72f
private const val HINGE_X = 15.5f
private const val KNOB_DX = 7.1f
private const val KNOB_R = 1.6f
private const val RIM_WIDTH = 1.4f
private const val RIM_ALPHA = 0.45f
private const val BACK_DOOR_X = 12.4f
private const val BACK_DOOR_RX = 3.2f
private const val BACK_DOOR_RY = 9.4f
private const val HEAD_X = 25.2f
private const val HEAD_R = 7f
private const val EYE_X = 26.2f
private const val EYE_Y = 27.2f
private const val EYE_R = 2f
private const val UPPER_BEAK_DEG = 10f
private const val UPPER_BEAK_PIVOT_X = 30.2f
private const val UPPER_BEAK_PIVOT_Y = 27.6f
private const val LOWER_BEAK_DEG = 22f
private const val LOWER_BEAK_PIVOT_X = 30.6f
private const val LOWER_BEAK_PIVOT_Y = 32.4f
private const val WAVE_X = 33f
private const val WAVE_STROKE = 2.6f
private const val WAVE_HALF_ANGLE = 32f
private val WAVE_RADII = floatArrayOf(9.5f, 14f, 18.5f)
private const val WAVE_MIN_SCALE = 0.85f
private const val WAVE_GROWTH = 0.25f
private const val WAVE_SHIFT = 2f
private const val MIN_ALPHA = 0.01f

private const val DOOR_OPEN = -0.32f
private const val BIRD_OUT_AT = 0.08f
private const val DOOR_CLOSE_DELAY = 0.25f
private const val SETTLED_AFTER = 0.4f
private const val SETTLE = 1.2f
private const val BEAK_HOLD = 0.16f
private const val WAVE_HOLD = 0.22f
private const val WAVE_TRAVEL_HOLD = 0.6f
private const val MIN_BIRD_SCALE = 0.35f
private const val BIRD_TRAVEL = 6f
private const val STRETCH_PER_VELOCITY = 0.012f
private const val MAX_STRETCH = 0.2f
private const val CALL_SQUASH = 0.09f

/** Filmstrip of key poses, like `brand/motion/door-open-frames.png`. */
@Preview(widthDp = 640, showBackground = true)
@Composable
private fun CuckooDoorFilmstripPreview() {
    CucutoTheme(dynamicColor = false) {
        Row {
            listOf(-1f, 0.04f, 0.1f, 0.16f, 0.24f, 1f).forEach { t ->
                CuckooDoorStill(seconds = t, modifier = Modifier.width(106.dp))
            }
        }
    }
}
