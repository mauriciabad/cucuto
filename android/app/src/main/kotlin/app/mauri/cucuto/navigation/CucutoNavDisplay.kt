package app.mauri.cucuto.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.mauri.cucuto.contact.ContactDetailScreen
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.daynight.DayNightScreen
import app.mauri.cucuto.diagnostics.DiagnosticsScreen
import app.mauri.cucuto.home.HomeScreen
import app.mauri.cucuto.packs.ui.PacksScreen
import app.mauri.cucuto.picker.PickerScreen
import app.mauri.cucuto.ui.discover.DiscoverScreen
import app.mauri.cucuto.ui.gallery.GalleryScreen
import app.mauri.cucuto.ui.haptics.HapticEditorScreen
import app.mauri.cucuto.ui.motion.SharedElementEntry
import app.mauri.cucuto.ui.motion.SharedElementsHost
import app.mauri.cucuto.ui.onboarding.OnboardingScreen
import app.mauri.cucuto.ui.placeholder.PlaceholderScreen
import app.mauri.cucuto.ui.settings.SettingsScreen
import app.mauri.cucuto.ui.status.StatusScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * The app's navigation host (Navigation 3). The back stack is a plain list of [CucutoDestination] keys; system back
 * (with predictive back animations) pops it.
 */
@Composable
fun CucutoNavDisplay(
    modifier: Modifier = Modifier,
    pendingImport: StateFlow<ImportSource?>? = null,
    pendingRepoLink: StateFlow<String?>? = null,
    startViewModel: StartViewModel = hiltViewModel(),
) {
    val onboarded by startViewModel.onboarded.collectAsStateWithLifecycle()
    // The first frame or two of a cold start, while the settings load: a plain surface instead of a wrong screen.
    val start = onboarded?.let(::startDestination)
    if (start == null) {
        Surface(modifier.fillMaxSize()) {}
        return
    }
    val backStack = rememberNavBackStack(start)
    val motion = MaterialTheme.motionScheme
    val navigate: (CucutoDestination) -> Unit = { backStack.add(it) }
    val goBack: () -> Unit = { backStack.removeLastOrNull() }
    val finishOnboarding: () -> Unit = {
        startViewModel.finishOnboarding()
        // First run: onboarding is the only entry, so Status replaces it. Reopened from Status: just go back.
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        } else {
            backStack.add(CucutoDestination.Status)
            backStack.removeAt(0)
        }
    }

    // A zip opened with Cucuto or shared to it goes to the Packs screen, which imports it (#27).
    LaunchedEffect(pendingImport) {
        pendingImport?.filterNotNull()?.collect {
            if (backStack.lastOrNull() != CucutoDestination.Packs) backStack.add(CucutoDestination.Packs)
        }
    }

    // An "Add to app" link opens Discover, which shows the add sheet (#36).
    LaunchedEffect(pendingRepoLink) {
        pendingRepoLink?.filterNotNull()?.collect {
            if (backStack.lastOrNull() != CucutoDestination.Discover) backStack.add(CucutoDestination.Discover)
        }
    }

    SharedElementsHost {
        NavDisplay(
            backStack = backStack,
            onBack = goBack,
            modifier = modifier,
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            transitionSpec = { motion.slideTransition(forward = true) },
            popTransitionSpec = { motion.slideTransition(forward = false) },
            predictivePopTransitionSpec = { motion.slideTransition(forward = false) },
            entryProvider = entries(backStack, navigate, goBack, finishOnboarding),
        )
    }
}

private fun entries(
    backStack: MutableList<NavKey>,
    navigate: (CucutoDestination) -> Unit,
    goBack: () -> Unit,
    finishOnboarding: () -> Unit,
) = entryProvider<NavKey> {
    entry<CucutoDestination.Status> { StatusScreen(onNavigate = navigate) }
    entry<CucutoDestination.Onboarding> {
        OnboardingScreen(onFinish = finishOnboarding, resume = backStack.size == 1)
    }
    entry<CucutoDestination.Home> {
        SharedElementEntry {
            HomeScreen(
                onOpenChat = { chat -> navigate(CucutoDestination.Picker(chat)) },
                onOpenPacks = { navigate(CucutoDestination.Packs) },
                onBack = goBack,
                onOpenDetail = { chat -> navigate(CucutoDestination.ContactDetail(chat)) },
                onOpenDiscover = { navigate(CucutoDestination.Discover) },
            )
        }
    }
    entry<CucutoDestination.Picker> { key ->
        SharedElementEntry {
            PickerScreen(
                chat = key.chat,
                onBack = goBack,
                // Only this picker, and only if the user has not left it already.
                onDone = { backStack.remove(key) },
                onOpenPacks = { navigate(CucutoDestination.Packs) },
            )
        }
    }
    entry<CucutoDestination.ContactDetail> { key ->
        SharedElementEntry {
            ContactDetailScreen(
                chat = key.chat,
                onBack = goBack,
                onChangeCharacter = { navigate(CucutoDestination.Picker(key.chat)) },
                onEditPattern = { navigate(CucutoDestination.HapticEditor(key.chat)) },
            )
        }
    }
    entry<CucutoDestination.Packs> {
        PacksScreen(onBack = goBack, onDiscover = { navigate(CucutoDestination.Discover) })
    }
    entry<CucutoDestination.Discover> { DiscoverScreen(onBack = goBack) }
    entry<CucutoDestination.HapticEditor> { key ->
        HapticEditorScreen(chat = key.chat, onBack = goBack, onDone = { backStack.remove(key) })
    }
    entry<CucutoDestination.Settings> { SettingsScreen(onNavigate = navigate) }
    entry<CucutoDestination.Diagnostics> { DiagnosticsScreen(onBack = goBack) }
    entry<CucutoDestination.DayNight> { DayNightScreen(onBack = goBack) }
    entry<CucutoDestination.Gallery> { GalleryScreen(onBack = goBack) }
}

/** Shared-axis style slide driven by the expressive motion scheme's springs. */
private fun MotionScheme.slideTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return (
        slideInHorizontally(defaultSpatialSpec()) { width -> direction * width / 4 } +
            fadeIn(defaultEffectsSpec())
    ) togetherWith (
        slideOutHorizontally(defaultSpatialSpec()) { width -> -direction * width / 4 } +
            fadeOut(fastEffectsSpec())
    )
}
