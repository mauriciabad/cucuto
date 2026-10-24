package app.mauri.cucuto.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import app.mauri.cucuto.ui.theme.CharacterAccentSeeds
import app.mauri.cucuto.ui.theme.CucutoSeed
import app.mauri.cucuto.ui.theme.seedColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #178: the cuckoo house on the status card (a filled `primaryContainer` card) must stand out from the card in every
 * scheme the card can get: the brand's (light, dark, every contrast level), the green "ready" character scheme, and
 * dynamic color, which Android builds as `SchemeTonalSpot` from the wallpaper (a few typical wallpaper seeds here).
 */
class CuckooDoorColorsTest {
    private data class Case(
        val name: String,
        val container: Color,
        val content: Color,
    )

    private val cases: List<Case> =
        buildList {
            val seeds = listOf(CucutoSeed, CharacterAccentSeeds.Leaf)
            for (seed in seeds) {
                for (dark in listOf(false, true)) {
                    for (contrast in listOf(0f, 0.5f, 1f)) {
                        val scheme = seedColorScheme(seed, darkTheme = dark, contrast = contrast)
                        add(
                            Case(
                                "vibrant $seed dark=$dark contrast=$contrast",
                                scheme.primaryContainer,
                                scheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }
            val wallpapers = listOf(0xFF1B6EF3, 0xFF3FAE5A, 0xFF8E5CFF, 0xFFD9468F, 0xFF5F7A8A, 0xFFFFC233, 0xFF6B4F3A)
            for (wallpaper in wallpapers) {
                for (dark in listOf(false, true)) {
                    val scheme =
                        SchemeTonalSpot(
                            sourceColorHct = Hct.fromInt(Color(wallpaper).toArgb()),
                            isDark = dark,
                            contrastLevel = 0.0,
                            specVersion = ColorSpec.SpecVersion.SPEC_2025,
                            platform = DynamicScheme.Platform.PHONE,
                        )
                    add(
                        Case(
                            "dynamic ${wallpaper.toString(16)} dark=$dark",
                            Color(scheme.primaryContainer),
                            Color(scheme.onPrimaryContainer),
                        ),
                    )
                }
            }
        }

    @Test
    fun houseStandsOutFromTheCard() {
        for (case in cases) {
            val colors = CuckooDoorDefaults.onContainerColors(case.container, case.content)
            // WCAG 1.4.11: graphics needed to understand the content get at least 3:1 against what is next to them.
            assertContrast(case.name + " house/card", colors.house, case.container, NON_TEXT)
            assertContrast(case.name + " opening/house", colors.opening, colors.house, NON_TEXT)
            assertContrast(case.name + " waves/card", colors.waves, case.container, NON_TEXT)
        }
    }

    @Test
    fun doorReadsOnTheHouseAndAgainstTheOpening() {
        for (case in cases) {
            val colors = CuckooDoorDefaults.onContainerColors(case.container, case.content)
            assertContrast(case.name + " door/house", colors.door, colors.house, DOOR)
            assertContrast(case.name + " door/opening", colors.door, colors.opening, DOOR_RIM)
        }
    }

    @Test
    fun theOldColorsHidTheHouse() {
        // What #178 saw: a primaryContainer house on a primaryContainer card has no contrast at all.
        val scheme = seedColorScheme(CucutoSeed, darkTheme = false)
        assertTrue(contrast(scheme.primaryContainer, scheme.primaryContainer) < NON_TEXT)
    }

    private fun assertContrast(
        what: String,
        a: Color,
        b: Color,
        minimum: Double,
    ) {
        val ratio = contrast(a, b)
        assertTrue("$what: ${"%.2f".format(ratio)} < $minimum", ratio >= minimum)
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }

    private companion object {
        const val NON_TEXT = 3.0

        /** The door is a shape inside the house; it also has a rim in the opening's color. */
        const val DOOR = 2.0

        /** The closed door's rim and knob are drawn in the opening's color. */
        const val DOOR_RIM = 1.8
    }
}
