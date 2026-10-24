package app.mauri.cucuto.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #180: the Settings tab's badge leads to a banner on top of Settings, which opens Settings → Setup. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsAttentionTest {
    @get:Rule
    val compose = createComposeRule()

    private val navigated = mutableListOf<CucutoDestination>()

    private fun show(needsAttention: Boolean) {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                SettingsContent(
                    state = SettingsState(),
                    systemRemovesAnimations = false,
                    actions = SettingsActions(onNavigate = { navigated += it }),
                    setupNeedsAttention = needsAttention,
                )
            }
        }
    }

    @Test
    fun theBannerComesFirstAndOpensSetup() {
        show(needsAttention = true)
        compose.onNodeWithText("Cucuto needs a hand").assertIsDisplayed().performClick()
        assertEquals(listOf<CucutoDestination>(CucutoDestination.Setup), navigated)
    }

    @Test
    fun noBannerWhenSetupIsFine() {
        show(needsAttention = false)
        compose.onNodeWithText("Cucuto needs a hand").assertDoesNotExist()
    }
}
