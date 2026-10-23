package app.mauri.cucuto.ui.motion

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember

/**
 * The six expressive springs of [MaterialTheme.motionScheme] as unit steps (0 to 1, released at rest) that can be
 * sampled at any time. Timelines built from them are pure functions of "seconds since the beat", which is how the
 * Feedback engine keeps motion locked to sound and haptics: no animation start latency, no drift.
 */
@Immutable
class Springs(
    motion: MotionScheme,
) {
    val fastSpatial = unitStep(motion.fastSpatialSpec())
    val defaultSpatial = unitStep(motion.defaultSpatialSpec())
    val slowSpatial = unitStep(motion.slowSpatialSpec())
    val fastEffects = unitStep(motion.fastEffectsSpec())
    val defaultEffects = unitStep(motion.defaultEffectsSpec())
    val slowEffects = unitStep(motion.slowEffectsSpec())
}

/** [Springs] for the current motion scheme. */
@Composable
fun rememberSprings(): Springs {
    val motion = MaterialTheme.motionScheme
    return remember(motion) { Springs(motion) }
}

/** A unit step of one spring. */
@Immutable
class UnitStep internal constructor(
    private val animation: TargetBasedAnimation<Float, AnimationVector1D>,
) {
    /** Progress (0 to 1, spatial springs overshoot) [seconds] after release. */
    fun value(seconds: Float): Float = if (seconds <= 0f) 0f else animation.getValueFromNanos(seconds.toNanos())

    /** Velocity in progress units per second. */
    fun velocity(seconds: Float): Float =
        if (seconds <=
            0f
        ) {
            0f
        } else {
            animation.getVelocityVectorFromNanos(seconds.toNanos()).value
        }
}

private fun unitStep(spec: FiniteAnimationSpec<Float>) =
    UnitStep(TargetBasedAnimation(spec, Float.VectorConverter, initialValue = 0f, targetValue = 1f))

private fun Float.toNanos(): Long = (this * NANOS_PER_SECOND).toLong()

private const val NANOS_PER_SECOND = 1_000_000_000f

/** One leg of a [Track]: at [atSeconds], spring towards [to]. */
@Immutable
data class Segment(
    val atSeconds: Float,
    val to: Float,
    val spring: UnitStep,
)

/**
 * A value animated by a chain of springs starting from [from], the same model as the brand motion demo
 * (`brand/tools/motion.mjs`): each segment starts from the previous target. Segments must be sorted by time.
 */
@Immutable
class Track(
    private val from: Float,
    private val segments: List<Segment>,
) {
    fun value(seconds: Float): Float {
        var value = from
        var previous = from
        for (segment in segments) {
            if (seconds < segment.atSeconds) break
            value = previous + (segment.to - previous) * segment.spring.value(seconds - segment.atSeconds)
            previous = segment.to
        }
        return value
    }

    fun velocity(seconds: Float): Float {
        var velocity = 0f
        var previous = from
        for (segment in segments) {
            if (seconds < segment.atSeconds) break
            velocity = (segment.to - previous) * segment.spring.velocity(seconds - segment.atSeconds)
            previous = segment.to
        }
        return velocity
    }
}
