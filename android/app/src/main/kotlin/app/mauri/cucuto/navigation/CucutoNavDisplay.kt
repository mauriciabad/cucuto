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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.mauri.cucuto.diagnostics.DiagnosticsScreen
import app.mauri.cucuto.ui.gallery.GalleryScreen
import app.mauri.cucuto.ui.onboarding.OnboardingScreen
import app.mauri.cucuto.ui.placeholder.PlaceholderScreen
import app.mauri.cucuto.ui.status.StatusScreen

/**
 * The app's navigation host (Navigation 3). The back stack is a plain list of [CucutoDestination] keys; system back
 * (with predictive back animations) pops it.
 */
@Composable
fun CucutoNavDisplay(
    modifier: Modifier = Modifier,
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
        entryProvider =
            entryProvider {
                entry<CucutoDestination.Status> { StatusScreen(onNavigate = navigate) }
                entry<CucutoDestination.Onboarding> {
                    OnboardingScreen(onFinish = finishOnboarding, resume = backStack.size == 1)
                }
                entry<CucutoDestination.Home> { PlaceholderScreen(it, onBack = goBack) }
                entry<CucutoDestination.Picker> { PlaceholderScreen(it, onBack = goBack) }
                entry<CucutoDestination.ContactDetail> { PlaceholderScreen(it, onBack = goBack) }
                entry<CucutoDestination.Packs> { PlaceholderScreen(it, onBack = goBack) }
                entry<CucutoDestination.Discover> { PlaceholderScreen(it, onBack = goBack) }
                entry<CucutoDestination.HapticEditor> { PlaceholderScreen(it, onBack = goBack) }
                entry<CucutoDestination.Settings> { PlaceholderScreen(it, onBack = goBack) }
                entry<CucutoDestination.Diagnostics> { DiagnosticsScreen(onBack = goBack) }
                entry<CucutoDestination.Gallery> { GalleryScreen(onBack = goBack) }
            },
    )
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
