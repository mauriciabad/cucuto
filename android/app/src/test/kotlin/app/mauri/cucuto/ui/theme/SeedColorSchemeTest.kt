package app.mauri.cucuto.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The runtime seed-to-scheme path (character screens) must match the brand tool that generated
 * `brand/tokens/Color.kt` (MCU 0.4.0 TypeScript, SchemeVibrant, spec 2025): same seed, same colors.
 */
class SeedColorSchemeTest {
    @Test
    fun brandSeedReproducesTheBrandSchemes() {
        assertSchemesEqual(LightColorScheme, seedColorScheme(CucutoSeed, darkTheme = false))
        assertSchemesEqual(DarkColorScheme, seedColorScheme(CucutoSeed, darkTheme = true))
        assertSchemesEqual(LightMediumContrastColorScheme, seedColorScheme(CucutoSeed, darkTheme = false, contrast = 0.5f))
        assertSchemesEqual(DarkHighContrastColorScheme, seedColorScheme(CucutoSeed, darkTheme = true, contrast = 1f))
    }

    @Test
    fun characterSeedsGiveTheirOwnPrimary() {
        val leaf = seedColorScheme(Color(0xFF3FAE5A), darkTheme = false)
        assertNotEquals(LightColorScheme.primary, leaf.primary)
    }

    @Test
    fun lerpEndsAreTheSchemes() {
        val leaf = seedColorScheme(Color(0xFF3FAE5A), darkTheme = false)
        assertEquals(LightColorScheme, lerp(LightColorScheme, leaf, 0f))
        assertEquals(leaf, lerp(LightColorScheme, leaf, 1f))
    }

    private fun assertSchemesEqual(
        expected: ColorScheme,
        actual: ColorScheme,
    ) {
        val roles: List<Pair<String, (ColorScheme) -> Color>> =
            listOf(
                "primary" to { it.primary },
                "onPrimary" to { it.onPrimary },
                "primaryContainer" to { it.primaryContainer },
                "onPrimaryContainer" to { it.onPrimaryContainer },
                "secondary" to { it.secondary },
                "secondaryContainer" to { it.secondaryContainer },
                "tertiary" to { it.tertiary },
                "tertiaryContainer" to { it.tertiaryContainer },
                "surface" to { it.surface },
                "onSurface" to { it.onSurface },
                "onSurfaceVariant" to { it.onSurfaceVariant },
                "surfaceContainer" to { it.surfaceContainer },
                "surfaceContainerHighest" to { it.surfaceContainerHighest },
                "outline" to { it.outline },
                "outlineVariant" to { it.outlineVariant },
                "error" to { it.error },
                "inverseSurface" to { it.inverseSurface },
                "primaryFixed" to { it.primaryFixed },
            )
        roles.forEach { (name, role) -> assertEquals(name, role(expected), role(actual)) }
    }
}
