package app.mauri.cucuto.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First-run state (#24): whether the onboarding flow was finished or skipped, and whether the status card has
 * already celebrated turning green once. Stored in the app's settings DataStore.
 */
@Singleton
class OnboardingSettings
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        /** False until the user finishes or skips onboarding; the app then starts on the Status screen. */
        val completed: Flow<Boolean> = dataStore.data.map { it[COMPLETED] ?: false }.distinctUntilChanged()

        /** Whether the status card has ever turned green (the first time is always celebrated). */
        val celebrated: Flow<Boolean> = dataStore.data.map { it[CELEBRATED] ?: false }.distinctUntilChanged()

        suspend fun setCompleted() {
            dataStore.edit { it[COMPLETED] = true }
        }

        suspend fun setCelebrated() {
            dataStore.edit { it[CELEBRATED] = true }
        }

        private companion object {
            val COMPLETED = booleanPreferencesKey("onboarding_completed")
            val CELEBRATED = booleanPreferencesKey("status_celebrated")
        }
    }
