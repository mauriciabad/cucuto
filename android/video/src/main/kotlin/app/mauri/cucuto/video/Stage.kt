package app.mauri.cucuto.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The stage: 960 × 540 dp, rendered at 2 px per dp (1920 × 1080). The phone stands in the middle, on the brand's
 * cream with the soft sound rings of the social card (docs/explainer-video.md, shot list).
 */
internal object StageGeometry {
    val Width = 960.dp
    val Height = 540.dp
    val ScreenWidth = 236.dp
    val ScreenHeight = 500.dp
    val ScreenLeft = (Width - ScreenWidth) / 2
    val ScreenTop = (Height - ScreenHeight) / 2
    val ScreenRadius = 30.dp
    val Bezel = 7.dp
    val StatusBar = 26.dp

    /** Where the characters pop out beside the phone: the center of each side's free space. */
    val PopLeft = ScreenLeft / 2 + 20.dp
    val PopRight = Width - PopLeft
}

/** How long a shot takes to replace the previous one (a slide inside the phone, or a cross-fade). */
private const val TRANSITION = 0.45

/** Draws the whole frame of [timeline] at [time] seconds. */
@Composable
internal fun ExplainerFrame(
    timeline: Timeline,
    time: Double,
    modifier: Modifier = Modifier,
) {
    VideoTheme {
        Box(modifier.fillMaxSize().background(BrandColors.Stage)) {
            Canvas(Modifier.fillMaxSize()) { soundRings() }
            val current = timeline.at(time)
            val local = time - current.start
            val previous = timeline.placed.getOrNull(current.number - 2)
            val scope = ShotScope(local, current.shot.duration)
            if (previous != null && local < TRANSITION) {
                val before = ShotScope(previous.shot.duration, previous.shot.duration)
                if (previous.shot.frame == ShotFrame.Phone && current.shot.frame == ShotFrame.Phone) {
                    // Phone to phone: the new screen slides in from the right, the old one out to the left.
                    val slide = Spring.DefaultSpatial.at(local).toFloat()
                    Phone(scope, current.shot) {
                        Box(Modifier.fillMaxSize().graphicsLayer { translationX = -size.width * slide }) {
                            previous.shot.content(before)
                        }
                        Box(Modifier.fillMaxSize().graphicsLayer { translationX = size.width * (1 - slide) }) {
                            current.shot.content(scope)
                        }
                    }
                } else {
                    val fade = scope.fade(0.0)
                    Layer(previous.shot, before, alpha = 1 - fade, grow = 0f)
                    Layer(current.shot, scope, alpha = fade, grow = 1 - scope.spring(0.0))
                }
            } else {
                Layer(current.shot, scope, alpha = 1f, grow = 0f)
            }
            current.shot.stage(scope)
            Pops(current.shot, scope)
        }
    }
}

/** One shot drawn on the stage: the phone with its screen, or the full frame. [grow] (0 = settled) scales it in. */
@Composable
private fun Layer(
    shot: Shot,
    scope: ShotScope,
    alpha: Float,
    grow: Float,
) {
    if (alpha <= 0f) return
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            this.alpha = alpha
            val scale = 1f - 0.06f * grow
            scaleX = scale
            scaleY = scale
        },
    ) {
        when (shot.frame) {
            ShotFrame.Phone -> Phone(scope, shot) { shot.content(scope) }
            ShotFrame.Full -> shot.content(scope)
        }
    }
}

/**
 * The illustrated phone: a dark body with rounded corners, the screen with a drawn status bar (no text, no real
 * device), the gesture handle, the vibration ripples and a small wiggle when it vibrates.
 */
@Composable
private fun Phone(
    scope: ShotScope,
    shot: Shot,
    screen: @Composable BoxScope.() -> Unit,
) {
    val wiggle =
        shot.vibrations.sumOf { at ->
            val e = scope.t - at
            if (e < 0 || e > 0.8) 0.0 else 0.9 * sin(2 * PI * 11 * e) * exp(-e * 5)
        }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            for (at in shot.vibrations) {
                ripple(scope.t - at)
                ripple(scope.t - at - RIPPLE_GAP)
            }
        }
        Box(
            Modifier
                .offset(StageGeometry.ScreenLeft - StageGeometry.Bezel, StageGeometry.ScreenTop - StageGeometry.Bezel)
                .size(
                    StageGeometry.ScreenWidth + StageGeometry.Bezel * 2,
                    StageGeometry.ScreenHeight + StageGeometry.Bezel * 2,
                ).graphicsLayer {
                    rotationZ = wiggle.toFloat()
                    transformOrigin = TransformOrigin.Center
                }.clip(RoundedCornerShape(StageGeometry.ScreenRadius + StageGeometry.Bezel))
                .background(PhoneColors.Body),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(StageGeometry.ScreenWidth, StageGeometry.ScreenHeight)
                    .clip(RoundedCornerShape(StageGeometry.ScreenRadius))
                    .background(PhoneColors.Screen),
            ) {
                screen()
                Canvas(Modifier.fillMaxSize()) { phoneChrome() }
            }
        }
    }
}

internal object PhoneColors {
    val Body = Color(0xFF2A211E)
    val Screen = Color(0xFFFFF8F6)
    val Chrome = Color(0xFF4B240A)
    val Ripple = Color(0xFFF46222)
    val Touch = Color(0xFF5F5F5F)
}

/** The status bar's shapes (a camera dot, signal and battery, no clock text) and the gesture handle. */
private fun DrawScope.phoneChrome() {
    val bar = StageGeometry.StatusBar.toPx()
    val chrome = PhoneColors.Chrome.copy(alpha = 0.75f)
    drawCircle(PhoneColors.Body, 5.dp.toPx(), Offset(size.width / 2, bar / 2))
    // Signal: three rising bars; battery: a rounded body with a nub.
    val right = size.width - 18.dp.toPx()
    val base = bar / 2 + 5.dp.toPx()
    for (i in 0 until 3) {
        val h = (4 + 3 * i).dp.toPx()
        drawRoundRect(
            chrome,
            Offset(right - 44.dp.toPx() + i * 5.dp.toPx(), base - h),
            Size(3.dp.toPx(), h),
            CornerRadius(1.dp.toPx()),
        )
    }
    drawRoundRect(
        chrome,
        Offset(right - 18.dp.toPx(), base - 9.dp.toPx()),
        Size(16.dp.toPx(), 9.dp.toPx()),
        CornerRadius(2.dp.toPx()),
    )
    drawRoundRect(
        chrome,
        Offset(right - 2.dp.toPx(), base - 6.dp.toPx()),
        Size(2.dp.toPx(), 3.dp.toPx()),
        CornerRadius(1.dp.toPx()),
    )
    drawRoundRect(
        chrome,
        Offset(size.width / 2 - 36.dp.toPx(), size.height - 10.dp.toPx()),
        Size(72.dp.toPx(), 4.dp.toPx()),
        CornerRadius(2.dp.toPx()),
    )
}

/** The cream stage's sound rings around the phone. */
private fun DrawScope.soundRings() {
    val center = Offset(size.width / 2, size.height / 2)
    for (radius in listOf(165.dp, 235.dp, 305.dp, 375.dp)) {
        drawCircle(BrandColors.Ring, radius.toPx(), center, style = Stroke(32.dp.toPx()))
    }
}

/** A vibration ripple [elapsed] seconds old, spreading out from the phone's outline. */
private fun DrawScope.ripple(elapsed: Double) {
    if (elapsed < 0 || elapsed > RIPPLE_LIFE) return
    val progress = (elapsed / RIPPLE_LIFE).toFloat()
    val grow = (3 + 60 * progress).dp.toPx()
    val body =
        Rect(
            Offset(
                (StageGeometry.ScreenLeft - StageGeometry.Bezel).toPx(),
                (
                    StageGeometry.ScreenTop -
                        StageGeometry.Bezel
                ).toPx(),
            ),
            Size(
                (StageGeometry.ScreenWidth + StageGeometry.Bezel * 2).toPx(),
                (StageGeometry.ScreenHeight + StageGeometry.Bezel * 2).toPx(),
            ),
        ).inflate(grow)
    drawRoundRect(
        PhoneColors.Ripple.copy(alpha = 0.6f * (1 - progress)),
        body.topLeft,
        body.size,
        CornerRadius((StageGeometry.ScreenRadius + StageGeometry.Bezel).toPx() + grow),
        style = Stroke((5 * (1 - progress * 0.5f)).dp.toPx()),
    )
}

private const val RIPPLE_LIFE = 0.65
private const val RIPPLE_GAP = 0.12

/**
 * The characters that speak in a shot jump out beside the phone, big, while their clip plays: they pop in on a fast
 * spring, squash on the sound, send out sound arcs, and leave. The sound is the star, so the picture shows who made it.
 */
@Composable
private fun Pops(
    shot: Shot,
    scope: ShotScope,
) {
    Box(Modifier.fillMaxSize()) {
        for (cue in shot.cues) {
            val character = cue.pop ?: continue
            val start = cue.at - POP_LEAD
            if (scope.t < start || scope.t > cue.at + POP_STAY + 0.4) continue
            val enter = scope.spring(start, Spring.FastSpatial)
            val leave = scope.spring(cue.at + POP_STAY, Spring.DefaultSpatial)
            val scale = (enter - leave).coerceAtLeast(0f)
            val squash = scope.squash(cue.at, 0.18f)
            val x = if (cue.side == Side.Left) StageGeometry.PopLeft else StageGeometry.PopRight
            val size = POP_SIZE
            Box(
                Modifier
                    .offset(x - size / 2, StageGeometry.Height / 2 - size / 2)
                    .size(size)
                    .drawWithContent {
                        soundArcs(
                            Offset(this.size.width / 2, this.size.height / 2),
                            this.size.width * 0.5f,
                            scope.progress(cue.at, cue.at + 1.1),
                            character.seed,
                            5.dp.toPx(),
                        )
                        drawContent()
                    },
            ) {
                Sprite(
                    character,
                    size,
                    Modifier.graphicsLayer {
                        scaleX = scale / squash
                        scaleY = scale * squash
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    },
                )
            }
        }
    }
}

private const val POP_LEAD = 0.12
private const val POP_STAY = 1.3
private val POP_SIZE: Dp = 150.dp

/**
 * A tap target in a phone screen: it presses (scale 0.92, brand/motion.md) at each time in [at] and shows the touch
 * indicator ("Show taps": a soft circle under the finger) on its center. [touchAlign] moves the finger off-center.
 */
internal fun Modifier.tapped(
    scope: ShotScope,
    vararg at: Double,
    touchAlign: Offset = Offset(0.5f, 0.5f),
): Modifier {
    val press = at.maxOfOrNull { scope.press(it) } ?: 0f
    return this
        .graphicsLayer {
            val scale = 1f - 0.08f * press
            scaleX = scale
            scaleY = scale
        }.drawWithContent {
            drawContent()
            for (tap in at) {
                val alpha = scope.touch(tap)
                if (alpha <= 0f) continue
                val center = Offset(size.width * touchAlign.x, size.height * touchAlign.y)
                drawCircle(Color.White.copy(alpha = 0.6f * alpha), 20.dp.toPx(), center)
                drawCircle(
                    PhoneColors.Touch.copy(alpha = 0.7f * alpha),
                    20.dp.toPx(),
                    center,
                    style = Stroke(2.dp.toPx()),
                )
            }
        }
}
