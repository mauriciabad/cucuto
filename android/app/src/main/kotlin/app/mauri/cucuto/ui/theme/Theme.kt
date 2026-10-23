package app.mauri.cucuto.ui.theme

import android.app.UiModeManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Cucuto's theme: Material 3 Expressive with the expressive (spring-based) motion scheme and the brand type scale.
 * Colors come from the wallpaper (dynamic color) by default; with dynamic color off, the brand schemes from
 * `brand/tokens/Color.kt` apply, following the system contrast setting.
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
    val contrast = remember(context) { context.getSystemService(UiModeManager::class.java)?.contrast ?: 0f }
    val colorScheme =
        when {
            dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
            dynamicColor -> dynamicLightColorScheme(context)
            else -> brandColorScheme(darkTheme, contrast)
        }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = CucutoTypography,
        content = content,
    )
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
