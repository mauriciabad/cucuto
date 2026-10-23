package app.mauri.cucuto.ui.theme

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Re-themes [content] in a character's colors: a `SchemeVibrant` scheme (spec 2025, like the brand) built from the
 * [seed] extracted from the character's sprite, following the surrounding light/dark and contrast. Typography,
 * shapes and the motion scheme stay Cucuto's. With a null seed (not extracted yet, or a colorless sprite) the
 * surrounding scheme stays.
 *
 * Switching characters cross-fades every color role with the default effects spring (brand/motion.md: "color scheme
 * change to a character's colors").
 */
@Composable
fun CharacterTheme(
    seed: Color?,
    content: @Composable () -> Unit,
) {
    val conditions = LocalThemeConditions.current
    val surrounding = MaterialTheme.colorScheme
    val target =
        remember(seed, conditions, surrounding) {
            seed?.let { seedColorScheme(it, conditions.darkTheme, conditions.contrast) } ?: surrounding
        }
    MaterialTheme(
        colorScheme = animateColorScheme(target, MaterialTheme.motionScheme.defaultEffectsSpec()),
        content = content,
    )
}

/** [CharacterTheme] for a sprite bitmap: extracts its seed off the main thread, then re-themes. */
@Composable
fun CharacterTheme(
    sprite: Bitmap?,
    content: @Composable () -> Unit,
) {
    val seed by rememberSeedColor(sprite)
    CharacterTheme(seed = seed, content = content)
}

/** Animates between color schemes, starting from whatever is on screen when the target changes mid-flight. */
@Composable
fun animateColorScheme(
    target: ColorScheme,
    animationSpec: AnimationSpec<Float>,
): ColorScheme {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target != to) {
            from = lerp(from, to, progress.value)
            to = target
            progress.snapTo(0f)
            progress.animateTo(1f, animationSpec)
        }
    }
    return lerp(from, to, progress.value)
}
