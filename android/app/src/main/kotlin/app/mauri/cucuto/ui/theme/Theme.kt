package app.mauri.cucuto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * Cucuto's theme: Material 3 Expressive with the expressive (spring-based) motion scheme, the brand type scale
 * (Google Sans Flex with its ROND axis) and the standard expressive shape scale ([CucutoShapes] names the
 * `MaterialShapes` each element uses).
 *
 * Colors come from the wallpaper (dynamic color) by default; with dynamic color off, or in previews, the brand
 * schemes from `brand/tokens/Color.kt` apply. Both follow light/dark and the system contrast level (standard,
 * medium, high): the platform's dynamic colors already include contrast, the brand schemes have one variant each.
 * Character screens re-theme their subtree with [CharacterTheme].
 *
 * Also provides [LocalReducedMotion] ("Remove animations") and [LocalThemeConditions].
 *
 * The brand tokens (`LightColorScheme`, `DarkColorScheme`, their contrast variants and `CucutoTypography`) are
 * compiled straight from `brand/tokens/`, see `app/build.gradle.kts`.
 */
@Composable
fun CucutoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val contrast = rememberSystemContrast()
    val useDynamic = dynamicColor && !LocalInspectionMode.current
    val colorScheme =
        when {
            useDynamic && darkTheme -> dynamicDarkColorScheme(context)
            useDynamic -> dynamicLightColorScheme(context)
            else -> brandColorScheme(darkTheme, contrast)
        }

    CompositionLocalProvider(
        LocalReducedMotion provides rememberReducedMotion(),
        LocalThemeConditions provides ThemeConditions(darkTheme, contrast),
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = CucutoTypography,
            content = content,
        )
    }
}

/** Picks the brand scheme for the system contrast level (0 standard, 0.5 medium, 1 high). */
internal fun brandColorScheme(
    darkTheme: Boolean,
    contrast: Float,
): ColorScheme =
    when {
        contrast >= HIGH_CONTRAST -> if (darkTheme) DarkHighContrastColorScheme else LightHighContrastColorScheme
        contrast >= MEDIUM_CONTRAST -> if (darkTheme) DarkMediumContrastColorScheme else LightMediumContrastColorScheme
        else -> if (darkTheme) DarkColorScheme else LightColorScheme
    }

private const val MEDIUM_CONTRAST = 0.5f
private const val HIGH_CONTRAST = 1f
