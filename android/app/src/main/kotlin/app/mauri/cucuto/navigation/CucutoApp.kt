package app.mauri.cucuto.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.ui.motion.SharedElementEntry
import app.mauri.cucuto.ui.motion.SharedElementsHost
import app.mauri.cucuto.ui.onboarding.OnboardingScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * The whole app (ADR 0027): a blank surface while the settings load, the onboarding flow on first run, then the
 * shell with its four tabs.
 */
@Composable
fun CucutoApp(
    modifier: Modifier = Modifier,
    pendingImport: StateFlow<ImportSource?>? = null,
    startViewModel: StartViewModel = hiltViewModel(),
) {
    val stage by startViewModel.stage.collectAsStateWithLifecycle()
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = stage,
        transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
        label = "stage",
        modifier = modifier,
    ) { current ->
        when (current) {
            AppStage.Loading -> Surface(Modifier.fillMaxSize()) {}
            AppStage.Onboarding -> OnboardingScreen(onFinish = startViewModel::finishOnboarding)
            AppStage.Shell -> CucutoShell(pendingImport = pendingImport)
        }
    }
}

/** The shell with the real screens; a pack handed over by another app opens the Packs tab, which imports it. */
@Composable
fun CucutoShell(
    modifier: Modifier = Modifier,
    pendingImport: StateFlow<ImportSource?>? = null,
    navigator: AppNavigator = rememberAppNavigator(),
) {
    LaunchedEffect(navigator, pendingImport) {
        pendingImport?.filterNotNull()?.collect { navigator.showRoot(TopLevelTab.Packs) }
    }
    val entries = remember(navigator) { cucutoEntries(navigator) }
    CucutoShellLayout(navigator = navigator, entryProvider = entries, modifier = modifier)
}

/**
 * Material 3 adaptive navigation (a bar on phones, a rail on large screens) around one [NavDisplay] showing the
 * navigator's visible stacks. [entryProvider] is a parameter so tests can drive the shell with plain screens.
 */
@Composable
fun CucutoShellLayout(
    navigator: AppNavigator,
    entryProvider: (NavKey) -> NavEntry<NavKey>,
    modifier: Modifier = Modifier,
) {
    val suiteType = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())
    val suiteState = rememberNavigationSuiteScaffoldState()
    // Full-screen flows (the setup guide) hide the bar; it slides back when they close.
    val immersive = navigator.current in ImmersiveDestinations
    LaunchedEffect(immersive) { if (immersive) suiteState.hide() else suiteState.show() }
    val motion = MaterialTheme.motionScheme

    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteType = suiteType,
        state = suiteState,
        navigationItems = {
            TopLevelTab.entries.forEach { tab ->
                NavigationSuiteItem(
                    selected = tab == navigator.currentTab,
                    onClick = { navigator.selectTab(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                    navigationSuiteType = suiteType,
                )
            }
        },
    ) {
        SharedElementsHost {
            NavDisplay(
                entries = rememberShellEntries(navigator, entryProvider),
                onBack = { navigator.goBack() },
                transitionSpec = { transitionFor(motion, forward = true) },
                popTransitionSpec = { transitionFor(motion, forward = false) },
                predictivePopTransitionSpec = { _ -> transitionFor(motion, forward = false) },
            )
        }
    }
}

/** Destinations shown without the navigation bar. */
private val ImmersiveDestinations: Set<NavKey> = setOf(CucutoDestination.Onboarding)

/**
 * Decorates each tab's stack on its own, so a tab's saved state and view models live as long as the entry is in its
 * stack, even while another tab is shown. Content keys are prefixed with the tab: the same screen may sit in two
 * stacks at once (Diagnostics from Home's setup card and from Settings).
 */
@Composable
private fun rememberShellEntries(
    navigator: AppNavigator,
    entryProvider: (NavKey) -> NavEntry<NavKey>,
): List<NavEntry<NavKey>> {
    val perTab =
        TopLevelTab.entries.associateWith { tab ->
            rememberDecoratedNavEntries(
                backStack = navigator.stack(tab),
                entryDecorators =
                    listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                entryProvider = { key -> entryProvider(key).inTab(tab, key) },
            )
        }
    return navigator.visibleTabs.flatMap { perTab.getValue(it) }
}

private fun NavEntry<NavKey>.inTab(
    tab: TopLevelTab,
    key: NavKey,
): NavEntry<NavKey> {
    val inner = this
    return NavEntry(key = key, contentKey = "${tab.name}/${inner.contentKey}", metadata = inner.metadata) {
        SharedElementEntry { inner.Content() }
    }
}

/** Marks the root entry of a tab, so switching tabs fades through instead of sliding. */
const val TAB_ROOT_METADATA = "app.mauri.cucuto.tabRoot"

/** Metadata for a tab root's entry. */
val TabRootMetadata: Map<String, Any> = mapOf(TAB_ROOT_METADATA to true)

private fun AnimatedContentTransitionScope<Scene<NavKey>>.isTabSwitch(): Boolean =
    initialState.entries
        .lastOrNull()
        ?.metadata
        ?.containsKey(TAB_ROOT_METADATA) == true &&
        targetState.entries
            .lastOrNull()
            ?.metadata
            ?.containsKey(TAB_ROOT_METADATA) == true

/**
 * Tab roots fade through (Material's top-level pattern); everything else is a shared-axis slide. Both run on the
 * expressive motion scheme's springs, and predictive back scrubs the same spec.
 */
private fun AnimatedContentTransitionScope<Scene<NavKey>>.transitionFor(
    motion: MotionScheme,
    forward: Boolean,
): ContentTransform = if (isTabSwitch()) motion.fadeThrough() else motion.slide(forward)

private fun MotionScheme.fadeThrough(): ContentTransform =
    (fadeIn(defaultEffectsSpec()) + scaleIn(defaultSpatialSpec(), initialScale = FADE_THROUGH_SCALE)) togetherWith
        fadeOut(fastEffectsSpec())

private fun MotionScheme.slide(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return (
        slideInHorizontally(defaultSpatialSpec()) { width -> direction * width / 4 } +
            fadeIn(defaultEffectsSpec())
    ) togetherWith (
        slideOutHorizontally(defaultSpatialSpec()) { width -> -direction * width / 4 } +
            fadeOut(fastEffectsSpec())
    )
}

private const val FADE_THROUGH_SCALE = 0.94f
