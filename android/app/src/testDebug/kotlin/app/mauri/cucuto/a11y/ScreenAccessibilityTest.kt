package app.mauri.cucuto.a11y

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every screen (#29) at the default font size, on every build: each control has a label, no TalkBack stop says the
 * same words twice, and touch targets are at least 48 dp. Compose skips Google's Accessibility Test Framework on
 * Robolectric (`enableAccessibilityChecks` logs "not supported by Robolectric" and returns), so these are Cucuto's
 * own versions of its main checks (TouchTargetSizeCheck, SpeakableTextPresentCheck, DuplicateSpeakableTextCheck).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenAccessibilityTest(
    private val screen: String,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyControlIsLabeledOnceAndBigEnough() {
        val content = A11yScreens.content(screen)
        compose.showScreen { content() }
        compose.assertInteractiveNodesLabeled(screen)
        compose.assertNoDuplicateAnnouncements(screen)
        compose.assertTouchTargets(screen)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun screens(): List<Array<Any>> = A11yScreens.all.map { arrayOf(it.first) }
    }
}
