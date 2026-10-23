package app.mauri.cucuto.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.toPath
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.feedback.rememberBeatSeconds
import app.mauri.cucuto.ui.motion.rememberSprings
import app.mauri.cucuto.ui.theme.CucutoShapes
import app.mauri.cucuto.ui.theme.CucutoTheme
import app.mauri.cucuto.ui.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/**
 * The assign burst (brand/motion.md, "Particle burst"): 12–16 small `MaterialShapes` in the character scheme's
 * colors fly out from the center of this overlay when [beat] fires, slow down with drag, fall a little, spin, and
 * fade. Put it over the avatar (same center), larger than it: particles travel about 140–220 dp.
 *
 * Driven by the beat's timestamp in the draw phase: no recomposition and no allocation per frame, one burst at a
 * time (a new beat replaces the running one). Nothing is drawn when animations are removed: the burst is purely
 * decorative, the avatar change and the sound carry the meaning.
 */
@Composable
fun ParticleBurst(
    beat: FeedbackBeat?,
    modifier: Modifier = Modifier,
    colors: List<Color> = burstColors(),
    particleCount: Int = DEFAULT_COUNT,
) {
    if (beat == null || LocalReducedMotion.current) return
    val seconds = rememberBeatSeconds(beat, MAX_LIFE_SECONDS)
    BurstScene(beat.startNanos, { seconds.floatValue }, modifier, colors, particleCount)
}

/** The burst frozen [seconds] after its beat: previews, filmstrips and screenshot tests. */
@Composable
fun ParticleBurstStill(
    seconds: Float,
    modifier: Modifier = Modifier,
    seed: Long = 0L,
    colors: List<Color> = burstColors(),
    particleCount: Int = DEFAULT_COUNT,
) = BurstScene(seed, { seconds }, modifier, colors, particleCount)

@Composable
private fun BurstScene(
    seed: Long,
    seconds: () -> Float,
    modifier: Modifier,
    colors: List<Color>,
    particleCount: Int,
) {
    val density = LocalDensity.current
    val springs = rememberSprings()
    val paths = remember { CucutoShapes.Particles.map { it.toPath().asComposePath() } }
    val particles = remember(seed, particleCount, density) { Particle.burst(seed, particleCount, density.density) }

    Canvas(modifier) {
        val t = seconds()
        if (t < 0f || t >= MAX_LIFE_SECONDS) return@Canvas
        val decay = (1f - exp(-DRAG * t)) / DRAG
        val gravityPx = GRAVITY_DP * density.density
        val scale = MIN_SCALE + (1f - MIN_SCALE) * springs.fastSpatial.value(t)
        particles.forEach { p ->
            if (t >= p.life) return@forEach
            val fadeStart = p.life - FADE_SECONDS
            val alpha = if (t <= fadeStart) 1f else 1f - springs.defaultEffects.value(t - fadeStart)
            val x = center.x + p.vx * decay
            val y = center.y + p.vy * decay + gravityPx / DRAG * (t - decay)
            withTransform({
                translate(x, y)
                rotate(p.spin * t, pivot = Offset.Zero)
                scale(p.sizePx * scale, p.sizePx * scale, pivot = Offset.Zero)
                translate(-0.5f, -0.5f)
            }) {
                drawPath(paths[p.shape % paths.size], colors[p.color % colors.size], alpha = alpha.coerceIn(0f, 1f))
            }
        }
    }
}

/** The character scheme's `primary`, `primaryContainer`, `tertiary` and `tertiaryContainer`. */
@Composable
fun burstColors(): List<Color> =
    with(MaterialTheme.colorScheme) { listOf(primary, primaryContainer, tertiary, tertiaryContainer) }

/** One particle's launch parameters, chosen once per burst. */
private class Particle(
    val vx: Float,
    val vy: Float,
    val sizePx: Float,
    val spin: Float,
    val life: Float,
    val shape: Int,
    val color: Int,
) {
    companion object {
        fun burst(
            seed: Long,
            count: Int,
            density: Float,
        ): List<Particle> {
            val random = Random(seed)
            fun between(
                min: Float,
                max: Float,
            ) = min + random.nextFloat() * (max - min)
            return List(count) { i ->
                val angle = 2 * PI.toFloat() * i / count + between(-JITTER, JITTER) - PI.toFloat() / 2
                val speed = between(MIN_SPEED_DP, MAX_SPEED_DP) * density
                Particle(
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    sizePx = between(MIN_SIZE_DP, MAX_SIZE_DP) * density,
                    spin = between(-MAX_SPIN, MAX_SPIN),
                    life = between(MIN_LIFE_SECONDS, MAX_LIFE_SECONDS),
                    shape = i,
                    color = random.nextInt(0, Int.MAX_VALUE),
                )
            }
        }
    }
}

private const val DEFAULT_COUNT = 14
private const val MIN_SPEED_DP = 900f
private const val MAX_SPEED_DP = 1400f
private const val MIN_SIZE_DP = 6f
private const val MAX_SIZE_DP = 14f
private const val MAX_SPIN = 360f
private const val MIN_LIFE_SECONDS = 0.45f
private const val MAX_LIFE_SECONDS = 0.65f
private const val FADE_SECONDS = 0.2f
private const val MIN_SCALE = 0.4f
private const val GRAVITY_DP = 600f
private const val JITTER = (10 * PI / 180).toFloat()

/** Drag of "velocity × 0.9 per frame at 60 Hz" as a continuous rate: v(t) = v0 · e^(−DRAG·t). */
private val DRAG = (-60 * ln(0.9)).toFloat()

/** Filmstrip of the burst at 60, 150, 300 and 500 ms. */
@Preview(widthDp = 960, showBackground = true)
@Composable
private fun ParticleBurstFilmstripPreview() {
    CucutoTheme(dynamicColor = false) {
        Row {
            listOf(0.06f, 0.15f, 0.3f, 0.5f).forEach { t ->
                Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CucutoShapes.Avatar.toShape(),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {}
                    ParticleBurstStill(seconds = t, modifier = Modifier.size(240.dp))
                }
            }
        }
    }
}
