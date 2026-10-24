package app.mauri.cucuto.ui.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.mauri.cucuto.ui.theme.LocalReducedMotion

/**
 * The motion scheme while animations are removed: every spec jumps to its end value, like the system's **Remove
 * animations** does through the animator duration scale (brand/motion.md, "Reduced motion"). Material components and
 * Cucuto's own motion read `MaterialTheme.motionScheme`, so state changes stay and the motion between them goes.
 */
object ReducedMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()

    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()

    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}

/**
 * Swaps the motion scheme for [ReducedMotionScheme] when [LocalReducedMotion] is on. The system setting already
 * scales every animation to zero; this makes Cucuto's own "Remove animations" setting (Settings › Sound and feel) do
 * the same inside the app without slowing other apps.
 */
@Composable
fun ProvideReducedMotionScheme(content: @Composable () -> Unit) {
    if (LocalReducedMotion.current) {
        MaterialTheme(motionScheme = ReducedMotionScheme, content = content)
    } else {
        content()
    }
}

/** `animateItem()` for list items, without the fade and placement motion when animations are removed. */
@Composable
fun LazyItemScope.animateItemUnlessReduced(modifier: Modifier = Modifier): Modifier =
    if (LocalReducedMotion.current) {
        modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
    } else {
        modifier.animateItem()
    }
