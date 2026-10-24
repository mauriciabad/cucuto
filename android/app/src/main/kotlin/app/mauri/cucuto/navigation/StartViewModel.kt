package app.mauri.cucuto.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.settings.OnboardingSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the activity shows at the top level. */
enum class AppStage {
    /** The settings are still loading (the first frame or two of a cold start): a plain surface, never a wrong screen. */
    Loading,

    /** First run: the onboarding flow, full screen, without the navigation bar. */
    Onboarding,

    /** The app: four tabs, opening on Home. */
    Shell,
}

/** The stage for a user who has ([onboarded] true) or has not finished or skipped onboarding; null while loading. */
fun appStage(onboarded: Boolean?): AppStage =
    when (onboarded) {
        null -> AppStage.Loading
        false -> AppStage.Onboarding
        true -> AppStage.Shell
    }

/** Picks the start of the app: onboarding until it is finished or skipped, then the shell on Home (ADR 0027). */
@HiltViewModel
class StartViewModel
    @Inject
    constructor(
        private val onboarding: OnboardingSettings,
    ) : ViewModel() {
        val stage: StateFlow<AppStage> =
            onboarding.completed
                .map(::appStage)
                .stateIn(viewModelScope, SharingStarted.Eagerly, AppStage.Loading)

        fun finishOnboarding() {
            viewModelScope.launch { onboarding.setCompleted() }
        }
    }
