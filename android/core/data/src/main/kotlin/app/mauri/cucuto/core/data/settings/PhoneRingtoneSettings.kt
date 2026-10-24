package app.mauri.cucuto.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone ringtones switch (#52, ADR 0039). Off by default: Cucuto touches Android contacts only after the user
 * turns it on in Settings or a contact's detail screen and grants the contacts permission.
 */
@Singleton
class PhoneRingtoneSettings
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val enabled: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: false }.distinctUntilChanged()

        suspend fun isEnabled(): Boolean = enabled.first()

        suspend fun setEnabled(enabled: Boolean) {
            dataStore.edit { it[ENABLED] = enabled }
        }

        private companion object {
            val ENABLED = booleanPreferencesKey("phone_ringtones_enabled")
        }
    }
