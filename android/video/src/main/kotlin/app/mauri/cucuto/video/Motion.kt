package app.mauri.cucuto.video

import androidx.compose.runtime.Immutable
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every picture of the video is a pure function of time: a scene reads [ShotScope.t] and computes where things are.
 * No animation state, no clock to wait for, so any frame renders on its own and the same frame always looks the same.
 *
 * The springs are the Material 3 Expressive motion tokens from `brand/motion.md`, solved in closed form.
 */
@Immutable
internal data class Spring(
    val dampingRatio: Double,
    val stiffness: Double,
) {
    /** Progress from 0 to 1 (overshooting when underdamped), [elapsed] seconds after the spring started. */
    fun at(elapsed: Double): Double {
        if (elapsed <= 0) return 0.0
        val omega = sqrt(stiffness)
        val zeta = dampingRatio
        val decay = exp(-zeta * omega * elapsed)
        return if (zeta < 1) {
            val damped = omega * sqrt(1 - zeta * zeta)
            1 - decay * (cos(damped * elapsed) + zeta * omega / damped * sin(damped * elapsed))
        } else {
            1 - decay * (1 + omega * elapsed)
        }
    }

    companion object {
        val FastSpatial = Spring(0.6, 800.0)
        val DefaultSpatial = Spring(0.8, 380.0)
        val SlowSpatial = Spring(0.8, 200.0)
        val FastEffects = Spring(1.0, 3800.0)
        val DefaultEffects = Spring(1.0, 1600.0)
        val SlowEffects = Spring(1.0, 800.0)
    }
}

/** Where a scene is in time. [t] is seconds since the shot started; [duration] is the shot's length. */
@Immutable
internal class ShotScope(
    val t: Double,
    val duration: Double,
) {
    /** A spatial spring from 0 to 1 that starts at [at] (may overshoot). */
    fun spring(
        at: Double,
        spec: Spring = Spring.DefaultSpatial,
    ): Float = spec.at(t - at).toFloat()

    /** An effects spring (never overshoots): 0 before [at], 1 once settled. For opacity and color. */
    fun fade(
        at: Double,
        spec: Spring = Spring.DefaultEffects,
    ): Float = spec.at(t - at).toFloat().coerceIn(0f, 1f)

    /** 1 from [from] to [until], with effects springs in and out. */
    fun window(
        from: Double,
        until: Double,
        spec: Spring = Spring.DefaultEffects,
    ): Float = (fade(from, spec) - fade(until, spec)).coerceIn(0f, 1f)

    /** Linear progress from 0 at [from] to 1 at [until]. */
    fun progress(
        from: Double,
        until: Double,
    ): Float = ((t - from) / (until - from)).toFloat().coerceIn(0f, 1f)

    /** Whether the shot has reached [at]. */
    fun after(at: Double): Boolean = t >= at

    /**
     * A finger press at [at] (the moment the sound plays): 0 at rest, 1 fully pressed. The press goes down a little
     * before the click and lifts right after, on fast springs (brand/motion.md: press scale 0.92).
     */
    fun press(at: Double): Float {
        val down = at - TAP_LEAD
        val up = at + TAP_HOLD
        return (Spring.FastSpatial.at(t - down) - Spring.FastSpatial.at(t - up)).toFloat()
    }

    /** Opacity of the touch indicator for a tap at [at]. */
    fun touch(at: Double): Float = window(at - TAP_LEAD - TOUCH_FADE_IN, at + TAP_HOLD, Spring.FastEffects)

    /**
     * Squash and stretch on a sound beat at [at] (brand/motion.md): `scaleY` dips by [amount] and springs back, so
     * the sprite looks like it is the one talking. Multiply `scaleY` by the result and `scaleX` by its inverse.
     */
    fun squash(
        at: Double,
        amount: Float = 0.1f,
    ): Float {
        if (t < at) return 1f
        val e = t - at
        // A quick dip, then the fast spatial spring's wobble back to rest.
        val dip = exp(-e * SQUASH_DECAY) * sin(e * SQUASH_FREQUENCY)
        return 1f - amount * dip.toFloat()
    }

    companion object {
        /** The finger goes down this long before the click. */
        const val TAP_LEAD = 0.1
        const val TAP_HOLD = 0.06
        private const val TOUCH_FADE_IN = 0.05
        private const val SQUASH_DECAY = 9.0
        private const val SQUASH_FREQUENCY = 18.0
    }
}

/** Linear interpolation. */
internal fun lerp(
    from: Float,
    to: Float,
    fraction: Float,
): Float = from + (to - from) * fraction
