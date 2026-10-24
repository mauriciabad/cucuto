package app.mauri.cucuto.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.ui.theme.CucutoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A child screen ([childOf]) works on its parent's view models and learns when it is popped (ADR 0055), while every
 * other screen keeps a store of its own.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class EntryViewModelsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    class Counter : ViewModel() {
        var count by mutableIntStateOf(0)
    }

    private lateinit var navigator: AppNavigator
    private var rootCounter: Counter? = null
    private var childCounter: Counter? = null
    private var ownCounter: Counter? = null
    private var pops = 0

    private fun show() {
        compose.setContent {
            CucutoTheme(dynamicColor = false) {
                navigator = rememberAppNavigator()
                val entries =
                    entryProvider<NavKey> {
                        entry<CucutoDestination.Home>(metadata = TabRootMetadata) { Text("Home screen") }
                        entry<CucutoDestination.Packs>(metadata = TabRootMetadata) {
                            val counter = viewModel { Counter() }
                            rootCounter = counter
                            Column {
                                Text("Packs ${counter.count}")
                                Button(onClick = { counter.count++ }) { Text("Count") }
                            }
                        }
                        entry<CucutoDestination.Discover>(metadata = TabRootMetadata) { Text("Discover screen") }
                        entry<CucutoDestination.Settings>(metadata = TabRootMetadata) { Text("Settings screen") }
                        entry<CucutoDestination.PackDetail>(metadata = childOf(CucutoDestination.Packs)) {
                            val counter =
                                viewModel(viewModelStoreOwner = LocalParentViewModelStoreOwner.current!!) { Counter() }
                            childCounter = counter
                            ownCounter = viewModel(LocalViewModelStoreOwner.current!!) { Counter() }
                            OnEntryPopped { pops++ }
                            Text("Detail ${counter.count}")
                        }
                    }
                CucutoShellLayout(navigator = navigator, entryProvider = entries)
            }
        }
    }

    @Test
    fun aChildSharesItsParentsViewModelsAndHearsItsPop() {
        show()
        compose.runOnIdle { navigator.navigate(CucutoDestination.Packs) }
        compose.onNodeWithText("Count").performClick()
        compose.onNodeWithText("Packs 1").assertIsDisplayed()

        compose.runOnIdle { navigator.navigate(CucutoDestination.PackDetail("critters")) }
        compose.onNodeWithText("Detail 1").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(rootCounter, childCounter)
            assertNotSame(rootCounter, ownCounter)
            assertEquals(0, pops)
        }

        // A tab switch is not a pop.
        compose.runOnIdle { navigator.navigate(CucutoDestination.Home) }
        compose.onNodeWithText("Home screen").assertIsDisplayed()
        compose.runOnIdle { navigator.navigate(CucutoDestination.Packs) }
        compose.onNodeWithText("Detail 1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, pops) }

        compose.runOnIdle { navigator.goBack() }
        compose.onNodeWithText("Packs 1").assertIsDisplayed()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, pops) }
    }
}
