package app.mauri.cucuto.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeVibrant

/**
 * Builds a Material 3 color scheme from one [seed] color, exactly the way the brand schemes in `brand/tokens/Color.kt`
 * were generated: Material Color Utilities, variant `SchemeVibrant`, color spec 2025, platform phone. Feeding it
 * [CucutoSeed] reproduces the brand schemes (see `SeedColorSchemeTest`).
 *
 * @param contrast the system contrast level: 0 standard, 0.5 medium, 1 high (`UiModeManager.getContrast()`).
 */
fun seedColorScheme(
    seed: Color,
    darkTheme: Boolean,
    contrast: Float = 0f,
): ColorScheme {
    val scheme =
        SchemeVibrant(
            sourceColorHct = Hct.fromInt(seed.toArgb()),
            isDark = darkTheme,
            contrastLevel = contrast.toDouble().coerceIn(-1.0, 1.0),
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            platform = DynamicScheme.Platform.PHONE,
        )
    return scheme.toColorScheme()
}

private fun DynamicScheme.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(primary),
        onPrimary = Color(onPrimary),
        primaryContainer = Color(primaryContainer),
        onPrimaryContainer = Color(onPrimaryContainer),
        inversePrimary = Color(inversePrimary),
        secondary = Color(secondary),
        onSecondary = Color(onSecondary),
        secondaryContainer = Color(secondaryContainer),
        onSecondaryContainer = Color(onSecondaryContainer),
        tertiary = Color(tertiary),
        onTertiary = Color(onTertiary),
        tertiaryContainer = Color(tertiaryContainer),
        onTertiaryContainer = Color(onTertiaryContainer),
        background = Color(background),
        onBackground = Color(onBackground),
        surface = Color(surface),
        onSurface = Color(onSurface),
        surfaceVariant = Color(surfaceVariant),
        onSurfaceVariant = Color(onSurfaceVariant),
        surfaceTint = Color(surfaceTint),
        inverseSurface = Color(inverseSurface),
        inverseOnSurface = Color(inverseOnSurface),
        error = Color(error),
        onError = Color(onError),
        errorContainer = Color(errorContainer),
        onErrorContainer = Color(onErrorContainer),
        outline = Color(outline),
        outlineVariant = Color(outlineVariant),
        scrim = Color(scrim),
        surfaceBright = Color(surfaceBright),
        surfaceDim = Color(surfaceDim),
        surfaceContainer = Color(surfaceContainer),
        surfaceContainerHigh = Color(surfaceContainerHigh),
        surfaceContainerHighest = Color(surfaceContainerHighest),
        surfaceContainerLow = Color(surfaceContainerLow),
        surfaceContainerLowest = Color(surfaceContainerLowest),
        primaryFixed = Color(primaryFixed),
        primaryFixedDim = Color(primaryFixedDim),
        onPrimaryFixed = Color(onPrimaryFixed),
        onPrimaryFixedVariant = Color(onPrimaryFixedVariant),
        secondaryFixed = Color(secondaryFixed),
        secondaryFixedDim = Color(secondaryFixedDim),
        onSecondaryFixed = Color(onSecondaryFixed),
        onSecondaryFixedVariant = Color(onSecondaryFixedVariant),
        tertiaryFixed = Color(tertiaryFixed),
        tertiaryFixedDim = Color(tertiaryFixedDim),
        onTertiaryFixed = Color(onTertiaryFixed),
        onTertiaryFixedVariant = Color(onTertiaryFixedVariant),
    )
}

/** Blends every role of two schemes, for animating from one character's colors to another's. */
fun lerp(
    start: ColorScheme,
    stop: ColorScheme,
    fraction: Float,
): ColorScheme {
    if (fraction <= 0f) return start
    if (fraction >= 1f) return stop
    fun mix(
        a: Color,
        b: Color,
    ) = lerp(a, b, fraction)
    return stop.copy(
        primary = mix(start.primary, stop.primary),
        onPrimary = mix(start.onPrimary, stop.onPrimary),
        primaryContainer = mix(start.primaryContainer, stop.primaryContainer),
        onPrimaryContainer = mix(start.onPrimaryContainer, stop.onPrimaryContainer),
        inversePrimary = mix(start.inversePrimary, stop.inversePrimary),
        secondary = mix(start.secondary, stop.secondary),
        onSecondary = mix(start.onSecondary, stop.onSecondary),
        secondaryContainer = mix(start.secondaryContainer, stop.secondaryContainer),
        onSecondaryContainer = mix(start.onSecondaryContainer, stop.onSecondaryContainer),
        tertiary = mix(start.tertiary, stop.tertiary),
        onTertiary = mix(start.onTertiary, stop.onTertiary),
        tertiaryContainer = mix(start.tertiaryContainer, stop.tertiaryContainer),
        onTertiaryContainer = mix(start.onTertiaryContainer, stop.onTertiaryContainer),
        background = mix(start.background, stop.background),
        onBackground = mix(start.onBackground, stop.onBackground),
        surface = mix(start.surface, stop.surface),
        onSurface = mix(start.onSurface, stop.onSurface),
        surfaceVariant = mix(start.surfaceVariant, stop.surfaceVariant),
        onSurfaceVariant = mix(start.onSurfaceVariant, stop.onSurfaceVariant),
        surfaceTint = mix(start.surfaceTint, stop.surfaceTint),
        inverseSurface = mix(start.inverseSurface, stop.inverseSurface),
        inverseOnSurface = mix(start.inverseOnSurface, stop.inverseOnSurface),
        error = mix(start.error, stop.error),
        onError = mix(start.onError, stop.onError),
        errorContainer = mix(start.errorContainer, stop.errorContainer),
        onErrorContainer = mix(start.onErrorContainer, stop.onErrorContainer),
        outline = mix(start.outline, stop.outline),
        outlineVariant = mix(start.outlineVariant, stop.outlineVariant),
        scrim = mix(start.scrim, stop.scrim),
        surfaceBright = mix(start.surfaceBright, stop.surfaceBright),
        surfaceDim = mix(start.surfaceDim, stop.surfaceDim),
        surfaceContainer = mix(start.surfaceContainer, stop.surfaceContainer),
        surfaceContainerHigh = mix(start.surfaceContainerHigh, stop.surfaceContainerHigh),
        surfaceContainerHighest = mix(start.surfaceContainerHighest, stop.surfaceContainerHighest),
        surfaceContainerLow = mix(start.surfaceContainerLow, stop.surfaceContainerLow),
        surfaceContainerLowest = mix(start.surfaceContainerLowest, stop.surfaceContainerLowest),
        primaryFixed = mix(start.primaryFixed, stop.primaryFixed),
        primaryFixedDim = mix(start.primaryFixedDim, stop.primaryFixedDim),
        onPrimaryFixed = mix(start.onPrimaryFixed, stop.onPrimaryFixed),
        onPrimaryFixedVariant = mix(start.onPrimaryFixedVariant, stop.onPrimaryFixedVariant),
        secondaryFixed = mix(start.secondaryFixed, stop.secondaryFixed),
        secondaryFixedDim = mix(start.secondaryFixedDim, stop.secondaryFixedDim),
        onSecondaryFixed = mix(start.onSecondaryFixed, stop.onSecondaryFixed),
        onSecondaryFixedVariant = mix(start.onSecondaryFixedVariant, stop.onSecondaryFixedVariant),
        tertiaryFixed = mix(start.tertiaryFixed, stop.tertiaryFixed),
        tertiaryFixedDim = mix(start.tertiaryFixedDim, stop.tertiaryFixedDim),
        onTertiaryFixed = mix(start.onTertiaryFixed, stop.onTertiaryFixed),
        onTertiaryFixedVariant = mix(start.onTertiaryFixedVariant, stop.onTertiaryFixedVariant),
    )
}
