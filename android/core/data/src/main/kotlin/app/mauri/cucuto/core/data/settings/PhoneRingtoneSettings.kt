package app.mauri.cucuto.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

        /**
         * Phone ringtones a backup restore brought back whose Android contacts are still to be found on this phone
         * (ADR 0057): kept until the contacts permission is granted. Opaque JSON owned by `:core:ringtone`.
         */
        suspend fun pendingRestore(): String? = dataStore.data.first()[PENDING_RESTORE]

        suspend fun setPendingRestore(json: String?) {
            dataStore.edit { if (json == null) it.remove(PENDING_RESTORE) else it[PENDING_RESTORE] = json }
        }

        private companion object {
            val ENABLED = booleanPreferencesKey("phone_ringtones_enabled")
            val PENDING_RESTORE = stringPreferencesKey("phone_ringtones_pending_restore")
        }
    }
