package app.mauri.cucuto.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/** The logo's fixed colors (brand/README.md#color). */
internal object BrandColors {
    val Tangerine = Color(0xFFFF6B2C)
    val Rust = Color(0xFFA83900)
    val Walnut = Color(0xFF321200)
    val Cream = Color(0xFFFFF1EB)
    val Sunny = Color(0xFFFFD25E)
    val Apricot = Color(0xFFFF9A6C)

    /** The stage behind the phone (docs/explainer-video.md: the brand's cream) and its soft sound rings. */
    val Stage = Color(0xFFFFF4F0)
    val Ring = Color(0xFFFFE9E2)
    val Success = Color(0xFF2E7D32)
    val SuccessContainer = Color(0xFFC8E6C9)
}

/** A lighter (container) or darker tone of a character's seed color. */
internal fun Color.mixWhite(amount: Float): Color = lerp(this, Color.White, amount)

internal fun Color.mixBlack(amount: Float): Color = lerp(this, Color.Black, amount)

// The cuckoo house ----------------------------------------------------------------------------------------------------

/** Colors of a cuckoo house: the brand's, or a character's (house from its container tone, door from its dark tone). */
@Immutable
internal data class HouseColors(
    val house: Color,
    val door: Color,
    val doorBack: Color,
    val opening: Color = BrandColors.Walnut,
    val head: Color = BrandColors.Cream,
    val beak: Color = BrandColors.Sunny,
    val waves: Color = house,
) {
    companion object {
        val Brand =
            HouseColors(BrandColors.Tangerine, BrandColors.Rust, Color(0xFF7A2900), waves = BrandColors.Tangerine)

        fun of(character: Character) =
            HouseColors(
                house = character.seed.mixWhite(0.45f),
                door = character.seed.mixBlack(0.3f),
                doorBack = character.seed.mixBlack(0.5f),
                waves = character.seed,
            )
    }
}

/** The pose of the door motion at one moment (brand/motion.md, "the door pops open"). */
@Immutable
internal data class DoorPose(
    /** The door's horizontal scale around its hinge: 1 closed, −0.32 open (negative shows its back). */
    val swing: Float,
    /** The door is drawn over the opening until it settles, then behind the frame, as in the logo. */
    val inFront: Boolean,
    /** The bird (or character) coming out: 0 hidden, 1 out (overshoots). */
    val out: Float,
    /** How far the beak is open for a call, 0 to about 1. */
    val call: Float,
    /** The sound arcs of a call: opacity 0 to 1. */
    val waves: Float,
) {
    companion object {
        val Closed = DoorPose(1f, true, 0f, 0f, 0f)
        val Open = DoorPose(OPEN_SWING, false, 1f, 0f, 0f)
    }
}

private const val OPEN_SWING = -0.32f

/** The door motion with the door swinging open at [opensAt] (the motion's 450 ms mark) and two calls after it. */
internal fun ShotScope.doorPose(opensAt: Double): DoorPose {
    val swing = lerp(1f, OPEN_SWING, spring(opensAt, Spring.FastSpatial))
    val calls = listOf(opensAt + 0.7, opensAt + 1.15)
    val call =
        calls.sumOf { at ->
            (Spring.FastSpatial.at(t - at) - Spring.DefaultSpatial.at(t - at - 0.2)).coerceAtLeast(0.0)
        }
    val waves = calls.maxOf { at -> window(at, at + 0.45).toDouble() }
    return DoorPose(
        swing = swing,
        inFront = t < opensAt + 0.4,
        out = spring(opensAt + 0.1, Spring.FastSpatial),
        call = call.toFloat(),
        waves = waves.toFloat(),
    )
}

/**
 * The brand's cuckoo house (brand/logo/cucuto-mark.svg, motion in brand/motion.md), drawn from its geometry: a
 * rounded pentagon, a round door on a hinge, the opening, and the bird leaning out to sing. With [character], the
 * character's sprite comes out of the door instead of the bird (as the app does). [height] is the house's height; the
 * composable is wider when [withWaves] leaves room for the sound arcs on the right.
 */
@Composable
internal fun CuckooHouse(
    pose: DoorPose,
    height: Dp,
    modifier: Modifier = Modifier,
    colors: HouseColors = HouseColors.Brand,
    character: Character? = null,
    withWaves: Boolean = false,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val spritePx = with(density) { (height * (SPRITE_UNITS / VIEW)).toPx() }.roundToInt()
    val sprite: ImageBitmap? =
        remember(character, spritePx) {
            character?.let { SvgArt.get(context, it.sprite, SvgArt.bucket(spritePx)) }
        }
    val width = if (withWaves) height * (WIDE / VIEW) else height
    Canvas(modifier.size(width, height)) {
        val unit = size.height / VIEW
        scale(unit, unit, pivot = Offset.Zero) { drawHouse(pose, colors, sprite) }
    }
}

private const val VIEW = 48f
private const val WIDE = 54f
private const val SPRITE_UNITS = 24f
private const val HINGE_X = 15.5f
private val OPENING = Offset(23.5f, 30f)
private const val OPENING_R = 10f

private object HouseGeometry {
    private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

    val house =
        path(
            "M21.078 5.494A4.5 4.5 0 0 1 26.922 5.494L43.272 19.452A3.5 3.5 0 0 1 44.5 22.114L44.5 38A6.5 6.5 0 0 1 " +
                "38 44.5L10 44.5A6.5 6.5 0 0 1 3.5 38L3.5 22.114A3.5 3.5 0 0 1 4.728 19.452Z",
        )
    val beakUpper =
        path(
            "M29.989 26.699A1.4 1.4 0 0 1 31.68 25.08L36.638 26.224A1.318 1.318 0 0 1 36.6 28.8L31.595 29.801" +
                "A1 1 0 0 1 30.415 29.003Z",
        )
    val beakLower =
        path(
            "M30.831 32.25A1 1 0 0 1 32.143 31.688L35.7 33.15A0.937 0.937 0 0 1 35.335 34.953L31.247 34.914" +
                "A1 1 0 0 1 30.324 33.552Z",
        )
    val waves =
        path(
            "M42.159 24.277A10.8 10.8 0 0 1 42.159 35.723A1.3 1.3 0 0 1 39.954 34.345A8.2 8.2 0 0 0 39.954 " +
                "25.655A1.3 1.3 0 0 1 42.159 24.277ZM45.975 21.892A15.3 15.3 0 0 1 45.975 38.108A1.3 1.3 0 0 1 " +
                "43.77 36.73A12.7 12.7 0 0 0 43.77 23.27A1.3 1.3 0 0 1 45.975 21.892ZM49.791 19.508A19.8 19.8 0 0 1 " +
                "49.791 40.492A1.3 1.3 0 0 1 47.586 39.115A17.2 17.2 0 0 0 47.586 20.885A1.3 1.3 0 0 1 49.791 " +
                "19.508Z",
        )
    val openingClip =
        Path().apply {
            addOval(
                androidx.compose.ui.geometry
                    .Rect(OPENING, OPENING_R),
            )
        }
}

private fun DrawScope.drawHouse(
    pose: DoorPose,
    colors: HouseColors,
    sprite: ImageBitmap?,
) {
    drawPath(HouseGeometry.house, colors.house)
    if (!pose.inFront) drawDoor(pose.swing, colors)
    drawCircle(colors.opening, OPENING_R, OPENING)
    if (pose.out > 0.01f) {
        val grow = 0.35f + 0.65f * pose.out
        val shift = -6f * (1 - pose.out.coerceAtMost(1f))
        if (sprite != null) {
            clipPath(HouseGeometry.openingClip) {
                withTransform({
                    translate(shift, 0f)
                    scale(grow, grow, OPENING)
                    // The character squashes on each call, pivoting at the bottom of the opening.
                    scale(1f + 0.05f * pose.call, 1f - 0.08f * pose.call, Offset(OPENING.x, OPENING.y + OPENING_R))
                }) {
                    val half = SPRITE_UNITS / 2
                    translate(OPENING.x - half, OPENING.y - half + 1.5f) {
                        drawImage(
                            sprite,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(sprite.width, sprite.height),
                            dstSize = IntSize(SPRITE_UNITS.toInt(), SPRITE_UNITS.toInt()),
                            filterQuality = FilterQuality.High,
                        )
                    }
                }
            }
        } else {
            withTransform({
                translate(shift, 0f)
                scale(grow, grow, OPENING)
                scale(1f, 1f - 0.09f * pose.call, Offset(25.2f, 37f))
            }) { drawBird(pose.call, colors) }
        }
    }
    if (pose.inFront) drawDoor(pose.swing, colors)
    if (pose.waves > 0f) {
        withTransform({ scale(0.9f + 0.1f * pose.waves, 0.9f + 0.1f * pose.waves, Offset(36f, 30f)) }) {
            drawPath(HouseGeometry.waves, colors.waves.copy(alpha = pose.waves))
        }
    }
}

private fun DrawScope.drawBird(
    call: Float,
    colors: HouseColors,
) {
    drawCircle(colors.head, 7f, Offset(25.2f, 30f))
    drawCircle(colors.eye(), 2f, Offset(26.2f, 27.2f))
    rotate(-10f * call, Offset(30.6f, 28.4f)) { drawPath(HouseGeometry.beakUpper, colors.beak) }
    rotate(22f * call, Offset(30.8f, 32.6f)) { drawPath(HouseGeometry.beakLower, colors.beak) }
}

private fun HouseColors.eye() = opening

private fun DrawScope.drawDoor(
    swing: Float,
    colors: HouseColors,
) {
    withTransform({ scale(swing, 1f, Offset(HINGE_X, 0f)) }) {
        drawCircle(if (swing < 0) colors.doorBack else colors.door, OPENING_R, OPENING)
        if (swing > 0) drawCircle(colors.opening, 1.6f, Offset(30.6f, 30f))
    }
}

// Avatars ------------------------------------------------------------------------------------------------------------

/** A shape morphing between two Material shapes at [progress] (0 = [Morph]'s start). */
private class MorphShape(
    private val morph: Morph,
    private val progress: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = morph.toPath(progress).asComposePath()
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

private val CookieToBurst by lazy { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.SoftBurst) }

/**
 * A contact or character avatar: the brand's cookie silhouette (never a plain circle, brand/README.md), morphing to
 * a soft burst at [burst] = 1 when it speaks or is assigned.
 */
@Composable
internal fun Cookie(
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    burst: Float = 0f,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shape = MorphShape(CookieToBurst, burst)
    Box(
        modifier.size(size).clip(shape).background(color),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

// Particles and sound ------------------------------------------------------------------------------------------------

/**
 * The assign burst (brand/motion.md, "particle burst"): 14 small Material shapes in the character's colors, fired
 * from [center] [elapsed] seconds ago, with drag, a little gravity and spin. [reach] scales the distances (1 = the
 * app's, in dp).
 */
internal fun DrawScope.burst(
    center: Offset,
    elapsed: Double,
    colors: List<Color>,
    reach: Float = 1f,
) {
    if (elapsed < 0 || elapsed > BURST_LIFE_MAX) return
    val shapes = listOf(MaterialShapes.Circle, MaterialShapes.Sunny, MaterialShapes.Clover4Leaf, MaterialShapes.Pill)
    val drag = BURST_FPS * ln(1 / BURST_DRAG)
    for (i in 0 until BURST_COUNT) {
        // Deterministic "random" values per particle, so every render is identical.
        val r1 = noise(i, 1)
        val r2 = noise(i, 2)
        val r3 = noise(i, 3)
        val life = 0.45 + 0.2 * r1
        if (elapsed > life) continue
        val angle = 2 * PI * i / BURST_COUNT + (r2 - 0.5) * PI / 9
        val speed = (900 + 500 * r3) * reach
        val travel = speed / drag * (1 - exp(-drag * elapsed))
        val fall = 0.5 * 600 * reach * elapsed * elapsed
        val position =
            Offset(
                center.x + (cos(angle) * travel).toFloat() * density,
                center.y + (sin(angle) * travel + fall).toFloat() * density,
            )
        val grow = 0.4f + 0.6f * Spring.FastSpatial.at(elapsed).toFloat()
        val fade = ((life - elapsed) / 0.2).toFloat().coerceIn(0f, 1f)
        val side = (6 + 8 * r2).toFloat() * density * grow
        val spin = ((r1 - 0.5) * 720 * elapsed).toFloat()
        val path = shapes[i % shapes.size].toPath().asComposePath()
        translate(position.x - side / 2, position.y - side / 2) {
            rotate(spin, Offset(side / 2, side / 2)) {
                scale(side, side, pivot = Offset.Zero) { drawPath(path, colors[i % colors.size].copy(alpha = fade)) }
            }
        }
    }
}

private const val BURST_COUNT = 14
private const val BURST_FPS = 60.0
private const val BURST_DRAG = 0.9
private const val BURST_LIFE_MAX = 0.7

private fun noise(
    i: Int,
    salt: Int,
): Double {
    val x = sin((i * 12.9898 + salt * 78.233)) * 43758.5453
    return x - kotlin.math.floor(x)
}

/** The burst colors for a character: its seed and lighter and darker tones. */
internal fun Character.burstColors(): List<Color> =
    listOf(seed, seed.mixWhite(0.4f), seed.mixBlack(0.25f), BrandColors.Sunny)

/**
 * Sound arcs spreading from [center] on both sides, like the logo's waves, while something speaks: [progress] goes
 * 0 to 1 over the sound; the arcs travel out and fade.
 */
internal fun DrawScope.soundArcs(
    center: Offset,
    radius: Float,
    progress: Float,
    color: Color,
    stroke: Float,
) {
    if (progress <= 0f || progress >= 1f) return
    for (i in 0 until 3) {
        val local = ((progress - i * 0.12f) / 0.64f).coerceIn(0f, 1f)
        if (local <= 0f || local >= 1f) continue
        val r = radius * (1f + 0.3f * i) * (0.85f + 0.3f * local)
        val alpha = (1f - local) * (if (local < 0.15f) local / 0.15f else 1f)
        for (start in listOf(-35f, 145f)) {
            drawArc(
                color.copy(alpha = alpha),
                startAngle = start,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(2 * r, 2 * r),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}
