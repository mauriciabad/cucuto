package app.mauri.cucuto.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Android stopped showing its prompt for the contacts permission ("don't ask again"), as seen the last time
 * Cucuto asked (ADR 0065, #337). Android can't tell an app this before it asks, so "Add a chat from contacts" remembers
 * it and points to Android's settings instead of a prompt that would not appear.
 */
interface ContactsPromptStore {
    suspend fun blocked(): Boolean

    suspend fun setBlocked(blocked: Boolean)
}

/**
 * [ContactsPromptStore] in the app's settings DataStore. A permission belongs to this phone, so the key is never backed
 * up (backups list their settings one by one, `core/backup/SettingsBackup.kt`).
 */
@Singleton
class ContactsPromptSettings
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : ContactsPromptStore {
        override suspend fun blocked(): Boolean = dataStore.data.first()[BLOCKED] ?: false

        override suspend fun setBlocked(blocked: Boolean) {
            dataStore.edit { if (blocked) it[BLOCKED] = true else it.remove(BLOCKED) }
        }

        private companion object {
            val BLOCKED = booleanPreferencesKey("contacts_prompt_blocked")
        }
    }
