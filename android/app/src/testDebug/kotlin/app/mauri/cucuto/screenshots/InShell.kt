package app.mauri.cucuto.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import app.mauri.cucuto.navigation.AppNavigator
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.navigation.CucutoShellLayout
import app.mauri.cucuto.navigation.TabRootMetadata
import app.mauri.cucuto.navigation.TopLevelTab

/**
 * Shows [content] as the root of [tab] inside the app shell (ADR 0027): the adaptive navigation bar with Home, Packs,
 * Discover and Settings, [tab] selected, as the screen looks in the app.
 */
@Composable
internal fun InShell(
    tab: TopLevelTab,
    content: @Composable () -> Unit,
) {
    val current by rememberUpdatedState(content)
    val navigator =
        remember(tab) {
            AppNavigator(
                stacks = TopLevelTab.entries.associateWith { mutableStateListOf<NavKey>(it.root) },
                selected = mutableStateOf(tab),
            )
        }
    val entries =
        remember {
            entryProvider<NavKey> {
                entry<CucutoDestination.Home>(metadata = TabRootMetadata) { current() }
                entry<CucutoDestination.Packs>(metadata = TabRootMetadata) { current() }
                entry<CucutoDestination.Discover>(metadata = TabRootMetadata) { current() }
                entry<CucutoDestination.Settings>(metadata = TabRootMetadata) { current() }
            }
        }
    CucutoShellLayout(navigator = navigator, entryProvider = entries)
}
