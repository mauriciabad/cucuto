package app.mauri.cucuto.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.ui.status.ModuleCheck
import app.mauri.cucuto.ui.status.PacksCheck
import app.mauri.cucuto.ui.status.ScopeCheck
import app.mauri.cucuto.ui.status.SelfTestMessage
import app.mauri.cucuto.ui.status.StatusCard
import app.mauri.cucuto.ui.status.StatusReport
import app.mauri.cucuto.ui.status.WhatsAppCheck
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Right after onboarding on a typical phone (360 × 800 dp, with the 80 dp navigation bar below), the first-run
 * journey's main button must be on screen without scrolling, even with the setup card still on Home (ADR 0050).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
class HomeFirstRunFoldTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theJourneysMainButtonIsAboveTheFoldAndTheSetupCardFollows() {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                Column {
                    Box(Modifier.weight(1f)) {
                        HomeContent(
                            state = HomeState(loading = false),
                            onQueryChange = {},
                            onTogglePack = {},
                            onDismissRecent = {},
                            onOpenChat = {},
                            onOpenPacks = {},
                            journey = JourneyStep.ImportPacks,
                            setupCard = {
                                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    StatusCard(
                                        FirstRun,
                                        beat = null,
                                        SelfTestMessage.None,
                                        onAction = {},
                                        compact = true,
                                    )
                                }
                            },
                        )
                    }
                    Spacer(Modifier.fillMaxWidth().height(NAVIGATION_BAR))
                }
            }
        }
        compose.onNodeWithText("Get the demo packs").assertIsDisplayed()
        compose.onNodeWithText("Restore a backup").assertIsDisplayed()
        val button = compose.onNodeWithText("Get the demo packs").getUnclippedBoundsInRoot()
        val card = compose.onNodeWithText("Getting Cucuto ready").getUnclippedBoundsInRoot()
        assertTrue("the journey comes before the setup card", button.bottom < card.top)
        assertTrue("the button is above the navigation bar", button.bottom < 800.dp - NAVIGATION_BAR)
    }

    private companion object {
        val NAVIGATION_BAR = 80.dp

        /** The status card right after onboarding: the module waits for its self-test, no packs yet. */
        val FirstRun =
            StatusReport(
                module = ModuleCheck.AwaitingSelfTest,
                scope = ScopeCheck.SystemOnly,
                whatsApp = WhatsAppCheck.Found(listOf("com.whatsapp")),
                packs = PacksCheck.None,
            )
    }
}
