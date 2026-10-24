package app.mauri.cucuto.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The shell's main flow on Robolectric: the adaptive navigation bar, one back stack per tab, state kept per tab and
 * system back. Screens are stand-ins so the test exercises navigation only.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class ShellNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navigator: AppNavigator

    private fun show() {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                navigator = rememberAppNavigator()
                val entries =
                    entryProvider<NavKey> {
                        entry<CucutoDestination.Home>(metadata = TabRootMetadata) { CounterScreen("Home screen") }
                        entry<CucutoDestination.Packs>(metadata = TabRootMetadata) { Text("Packs screen") }
                        entry<CucutoDestination.Discover>(metadata = TabRootMetadata) { Text("Discover screen") }
                        entry<CucutoDestination.Settings>(metadata = TabRootMetadata) {
                            Column {
                                Text("Settings screen")
                                Button(onClick = { navigator.navigate(CucutoDestination.Developer) }) {
                                    Text("Open developer")
                                }
                            }
                        }
                        entry<CucutoDestination.Developer> { Text("Developer screen") }
                    }
                CucutoShellLayout(navigator = navigator, entryProvider = entries)
            }
        }
    }

    @Test
    fun opensOnHomeWithFourTabs() {
        show()
        compose.onNodeWithText("Home screen").assertIsDisplayed()
        listOf("Home", "Packs", "Discover", "Settings").forEach { tab(it).assertIsDisplayed() }
        tab("Home").assertIsSelected()
    }

    @Test
    fun tabsSwitchAndBackReturnsThroughTheStackThenHome() {
        show()
        tab("Settings").performClick()
        compose.onNodeWithText("Settings screen").assertIsDisplayed()
        tab("Settings").assertIsSelected()

        compose.onNodeWithText("Open developer").performClick()
        compose.onNodeWithText("Developer screen").assertIsDisplayed()

        pressBack()
        compose.onNodeWithText("Settings screen").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("Home screen").assertIsDisplayed()
        tab("Home").assertIsSelected()
        assertEquals(TopLevelTab.Home, navigator.currentTab)
    }

    @Test
    fun eachTabKeepsItsStateAndStack() {
        show()
        compose.onNodeWithText("Count 0").performClick()
        compose.onNodeWithText("Count 1").performClick()
        tab("Settings").performClick()
        compose.onNodeWithText("Open developer").performClick()

        tab("Packs").performClick()
        compose.onNodeWithText("Packs screen").assertIsDisplayed()
        tab("Home").performClick()
        compose.onNodeWithText("Count 2").assertIsDisplayed()
        tab("Settings").performClick()
        compose.onNodeWithText("Developer screen").assertIsDisplayed()

        // Selecting the current tab again pops it to its root.
        tab("Settings").performClick()
        compose.onNodeWithText("Settings screen").assertIsDisplayed()
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}

@Composable
private fun CounterScreen(title: String) {
    var count by rememberSaveable { mutableIntStateOf(0) }
    Column {
        Text(title)
        Button(onClick = { count++ }) { Text("Count $count") }
    }
}
