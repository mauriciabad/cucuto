package app.mauri.cucuto.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.settings.OnboardingSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Picks the start destination: onboarding until it is finished or skipped, then Status. */
@HiltViewModel
class StartViewModel
    @Inject
    constructor(
        private val onboarding: OnboardingSettings,
    ) : ViewModel() {
        /** Null while the settings load (a few milliseconds at cold start). */
        val onboarded: StateFlow<Boolean?> = onboarding.completed.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        fun finishOnboarding() {
            viewModelScope.launch { onboarding.setCompleted() }
        }
    }

/** The first screen for a user who has ([onboarded]) or has not finished onboarding. */
fun startDestination(onboarded: Boolean): CucutoDestination =
    if (onboarded) CucutoDestination.Status else CucutoDestination.Onboarding
