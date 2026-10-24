package app.mauri.cucuto.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Volume of Cucuto's own UI sounds (brand/sound-and-haptics.md, "Accessibility"). Character sounds are separate. */
enum class UiSoundLevel(
    /** SoundPool volume, before the system's assistance (sonification) volume. */
    val volume: Float,
) {
    Off(0f),
    Quiet(0.4f),
    Normal(1f),
}

/**
 * In-app feedback settings: "UI sounds" (off, quiet, normal; default quiet), "UI haptics" (default on; the system
 * "Vibration and haptics" / touch feedback settings still apply on top) and "Remove animations" (default off; the
 * system's Remove animations turns it on too). Stored in the app's settings DataStore.
 */
@Singleton
class UiFeedbackSettings
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val soundLevel: Flow<UiSoundLevel> =
            dataStore.data
                .map { prefs ->
                    UiSoundLevel.entries.firstOrNull { it.name == prefs[SOUND_LEVEL] } ?: UiSoundLevel.Quiet
                }.distinctUntilChanged()

        val hapticsEnabled: Flow<Boolean> = dataStore.data.map { it[HAPTICS] ?: true }.distinctUntilChanged()

        /**
         * Cucuto's own "Remove animations": drops decorative motion (particles, bounces, the door swing) like the
         * system setting does, without slowing every other app. Sound and haptics still play.
         */
        val removeAnimations: Flow<Boolean> =
            dataStore.data.map { it[REMOVE_ANIMATIONS] ?: false }.distinctUntilChanged()

        suspend fun setSoundLevel(level: UiSoundLevel) {
            dataStore.edit { it[SOUND_LEVEL] = level.name }
        }

        suspend fun setHapticsEnabled(enabled: Boolean) {
            dataStore.edit { it[HAPTICS] = enabled }
        }

        suspend fun setRemoveAnimations(remove: Boolean) {
            dataStore.edit { it[REMOVE_ANIMATIONS] = remove }
        }

        private companion object {
            val REMOVE_ANIMATIONS = booleanPreferencesKey("ui_remove_animations")
            val SOUND_LEVEL = stringPreferencesKey("ui_sound_level")
            val HAPTICS = booleanPreferencesKey("ui_haptics")
        }
    }
