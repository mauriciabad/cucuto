package app.mauri.cucuto.a11y

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen at the largest font size (200%), in English and in Spanish (the longer strings): no text is cut off and
 * controls keep their labels. `LargeFontScreenshotTest` records how they look.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LargeFontScreensTest(
    screen: String,
) : LargeFontScreens(screen, "en") {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun screens(): List<Array<Any>> = A11yScreens.all.map { arrayOf(it.first) }
    }
}

/** [LargeFontScreensTest] in Spanish. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "es-w411dp-h891dp-xxhdpi")
class LargeFontScreensSpanishTest(
    screen: String,
) : LargeFontScreens(screen, "es") {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun screens(): List<Array<Any>> = A11yScreens.all.map { arrayOf(it.first) }
    }
}

abstract class LargeFontScreens(
    private val screen: String,
    private val locale: String,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reflowsAtTwiceTheFontSize() {
        val content = A11yScreens.content(screen)
        compose.showScreen(fontScale = LARGEST_FONT_SCALE) { content() }
        val clipped = compose.clippedTexts()
        assertTrue("$screen ($locale, 200%): text cut off:\n" + clipped.joinToString("\n"), clipped.isEmpty())
        compose.assertInteractiveNodesLabeled("$screen ($locale, 200%)")
    }

    private companion object {
        const val LARGEST_FONT_SCALE = 2f
    }
}
