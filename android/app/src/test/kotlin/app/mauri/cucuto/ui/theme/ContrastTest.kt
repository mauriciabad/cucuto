package app.mauri.cucuto.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.2 AA contrast for every color pair the screens use for text (4.5:1) and for meaningful icons and outlines
 * (3:1), in the brand schemes (light and dark, standard, medium and high contrast) and in character schemes built from
 * seeds at the edges of the color wheel (yellow and cyan are the hardest). Dynamic (wallpaper) colors come from the
 * same Material tonal system, whose role pairs are built for these ratios.
 */
class ContrastTest {
    private val schemes: List<Pair<String, ColorScheme>> =
        buildList {
            for (dark in listOf(false, true)) {
                for (contrast in listOf(0f, 0.5f, 1f)) {
                    add("brand dark=$dark contrast=$contrast" to brandColorScheme(dark, contrast))
                }
                for ((name, seed) in seeds) {
                    add("$name dark=$dark" to seedColorScheme(seed, dark))
                }
            }
        }

    @Test
    fun textMeetsAa() {
        val failures =
            schemes.flatMap { (name, scheme) ->
                textPairs(scheme).mapNotNull { (pair, colors) ->
                    val ratio = contrast(colors.first, colors.second)
                    if (ratio < TEXT) "$name: $pair = ${"%.2f".format(ratio)}" else null
                }
            }
        assertTrue("Text below 4.5:1\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun iconsAndOutlinesMeetAa() {
        val failures =
            schemes.flatMap { (name, scheme) ->
                listOf(
                    "primary icon on surface" to (scheme.primary to scheme.surface),
                    "error icon on surface" to (scheme.error to scheme.surface),
                    "outline on surface" to (scheme.outline to scheme.surface),
                    "outline on surfaceContainer" to (scheme.outline to scheme.surfaceContainer),
                ).mapNotNull { (pair, colors) ->
                    val ratio = contrast(colors.first, colors.second)
                    if (ratio < NON_TEXT) "$name: $pair = ${"%.2f".format(ratio)}" else null
                }
            }
        assertTrue("Non-text below 3:1\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** Foreground on background, as the screens draw them. */
    private fun textPairs(s: ColorScheme): List<Pair<String, Pair<Color, Color>>> {
        val surfaces =
            listOf(
                "surface" to s.surface,
                "surfaceContainerLow" to s.surfaceContainerLow,
                "surfaceContainer" to s.surfaceContainer,
                "surfaceContainerHigh" to s.surfaceContainerHigh,
                "surfaceContainerHighest" to s.surfaceContainerHighest,
            )
        // Body text, secondary text, section headers and labels (primary), errors and the import's warnings.
        val onSurfaces =
            listOf(
                "onSurface" to s.onSurface,
                "onSurfaceVariant" to s.onSurfaceVariant,
                "primary" to s.primary,
                "tertiary" to s.tertiary,
                "error" to s.error,
            )
        return onSurfaces.flatMap { (fg, fgColor) ->
            surfaces.map { (bg, bgColor) -> "$fg on $bg" to (fgColor to bgColor) }
        } +
            listOf(
                "onPrimary on primary" to (s.onPrimary to s.primary),
                "onPrimaryContainer on primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                "onSecondaryContainer on secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                "onTertiaryContainer on tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
                "onErrorContainer on errorContainer" to (s.onErrorContainer to s.errorContainer),
                "inverseOnSurface on inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
            )
    }

    private companion object {
        const val TEXT = 4.5
        const val NON_TEXT = 3.0

        val seeds =
            listOf(
                "leaf" to CharacterAccentSeeds.Leaf,
                "sky" to CharacterAccentSeeds.Sky,
                "berry" to CharacterAccentSeeds.Berry,
                "plum" to CharacterAccentSeeds.Plum,
                "sunny" to CharacterAccentSeeds.Sunny,
                "slate" to CharacterAccentSeeds.Slate,
                "yellow" to Color(0xFFFFFF00),
                "cyan" to Color(0xFF00FFFF),
                "red" to Color(0xFFFF0000),
                "gray" to Color(0xFF808080),
                "near black" to Color(0xFF101010),
                "near white" to Color(0xFFF4F4F4),
            )

        /** WCAG relative-luminance contrast ratio. */
        fun contrast(
            a: Color,
            b: Color,
        ): Double {
            val la = a.luminance().toDouble()
            val lb = b.luminance().toDouble()
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }
    }
}
