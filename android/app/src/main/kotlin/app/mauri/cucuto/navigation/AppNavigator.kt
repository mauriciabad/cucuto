package app.mauri.cucuto.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * Navigation state of the shell: one back stack per [TopLevelTab] and the selected tab (ADR 0027, the Navigation 3
 * "multiple back stacks" pattern).
 *
 * - Navigating to a tab root switches tabs and keeps each tab's stack as the user left it; selecting the current tab
 *   again pops it to its root.
 * - Any other destination is pushed onto the current tab's stack, so back returns to where the user came from.
 * - Back pops the current stack; from another tab's root it returns to [TopLevelTab.Start]; from the start tab's root
 *   it leaves the app.
 *
 * The stacks are plain lists so the rules are unit-tested without Compose; [rememberAppNavigator] backs them with
 * saveable Navigation 3 back stacks.
 */
@Stable
class AppNavigator(
    private val stacks: Map<TopLevelTab, MutableList<NavKey>>,
    selected: MutableState<TopLevelTab>,
) {
    init {
        require(stacks.keys == TopLevelTab.entries.toSet()) { "one stack per tab" }
    }

    /** The selected tab. */
    var currentTab: TopLevelTab by selected
        private set

    /** The back stack of [tab]; never empty, starts with the tab's root. */
    fun stack(tab: TopLevelTab): List<NavKey> = stacks.getValue(tab)

    /** The destination on screen. */
    val current: NavKey get() = stacks.getValue(currentTab).last()

    /**
     * The tabs whose stacks [androidx.navigation3.ui.NavDisplay] shows, bottom first: the start tab, then the current
     * one. Back from another tab's root therefore animates (and predicts) the way to the start tab.
     */
    val visibleTabs: List<TopLevelTab>
        get() =
            if (currentTab ==
                TopLevelTab.Start
            ) {
                listOf(TopLevelTab.Start)
            } else {
                listOf(TopLevelTab.Start, currentTab)
            }

    /** Every destination in [visibleTabs], bottom first. */
    val visibleKeys: List<NavKey> get() = visibleTabs.flatMap { stack(it) }

    /** Switches to a tab root, or pushes any other [destination] onto the current tab (never twice in a row). */
    fun navigate(destination: NavKey) {
        val tab = TopLevelTab.ofRoot(destination)
        if (tab != null) {
            currentTab = tab
            return
        }
        val stack = stacks.getValue(currentTab)
        if (stack.last() != destination) stack.add(destination)
    }

    /** A tap on [tab] in the navigation bar: switch to it, or pop it to its root when it is already selected. */
    fun selectTab(tab: TopLevelTab) {
        if (tab == currentTab) popToRoot(tab) else currentTab = tab
    }

    /** Shows [tab]'s root with nothing above it, for example Packs when another app hands Cucuto a pack. */
    fun showRoot(tab: TopLevelTab) {
        popToRoot(tab)
        currentTab = tab
    }

    /**
     * Handles back. Returns false when there is nothing left to go back to inside the app (the start tab's root), so
     * the system can close it.
     */
    fun goBack(): Boolean {
        val stack = stacks.getValue(currentTab)
        return when {
            stack.size > 1 -> {
                stack.removeAt(stack.lastIndex)
                true
            }

            currentTab != TopLevelTab.Start -> {
                currentTab = TopLevelTab.Start
                true
            }

            else -> {
                false
            }
        }
    }

    /**
     * Removes [destination] (and anything above it) from whichever stack holds it: a screen that closes itself, like
     * the picker after assigning, when the user may already have left it.
     */
    fun remove(destination: NavKey) {
        for (stack in stacks.values) {
            val index = stack.indexOf(destination)
            if (index > 0) {
                while (stack.size > index) stack.removeAt(stack.lastIndex)
                return
            }
        }
    }

    private fun popToRoot(tab: TopLevelTab) {
        val stack = stacks.getValue(tab)
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

/** An [AppNavigator] whose stacks and selected tab survive configuration changes and process death. */
@Composable
fun rememberAppNavigator(): AppNavigator {
    val home = rememberNavBackStack(TopLevelTab.Home.root)
    val packs = rememberNavBackStack(TopLevelTab.Packs.root)
    val discover = rememberNavBackStack(TopLevelTab.Discover.root)
    val settings = rememberNavBackStack(TopLevelTab.Settings.root)
    val selected = rememberSaveable { mutableStateOf(TopLevelTab.Start) }
    return remember(home, packs, discover, settings, selected) {
        AppNavigator(
            stacks =
                mapOf(
                    TopLevelTab.Home to home,
                    TopLevelTab.Packs to packs,
                    TopLevelTab.Discover to discover,
                    TopLevelTab.Settings to settings,
                ),
            selected = selected,
        )
    }
}
