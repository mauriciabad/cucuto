package app.mauri.cucuto.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The automatic backup setup and what its last run did. */
data class AutoBackupState(
    /** The folder the user picked (an SAF tree URI with a persisted permission), or null when automatic backups are off. */
    val folderUri: String? = null,
    /** The folder's name as the document provider shows it, for the settings row. */
    val folderName: String? = null,
    /** Whether automatic backups include the installed packs' files ("full backup"). Default on. */
    val includePacks: Boolean = true,
    /** When the last automatic backup was written (epoch milliseconds). */
    val lastSuccessAt: Long? = null,
    val lastFileName: String? = null,
    /** Fingerprint of the last backup's content: a backup with the same content is not written again. */
    val lastFingerprint: String? = null,
    /** Why the last attempt failed, cleared by the next success. */
    val lastError: String? = null,
    val lastErrorAt: Long? = null,
) {
    val enabled: Boolean get() = folderUri != null
}

/**
 * Automatic backups (docs/project-brief.md, "Backup and restore"; ADR 0028): the chosen folder and the last result.
 * Device-specific, so never part of a backup (`AppSettingsBackup` lists the keys that travel).
 */
@Singleton
class AutoBackupSettings
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val state: Flow<AutoBackupState> =
            dataStore.data
                .map { prefs ->
                    AutoBackupState(
                        folderUri = prefs[FOLDER_URI],
                        folderName = prefs[FOLDER_NAME],
                        includePacks = prefs[INCLUDE_PACKS] ?: true,
                        lastSuccessAt = prefs[LAST_SUCCESS_AT],
                        lastFileName = prefs[LAST_FILE_NAME],
                        lastFingerprint = prefs[LAST_FINGERPRINT],
                        lastError = prefs[LAST_ERROR],
                        lastErrorAt = prefs[LAST_ERROR_AT],
                    )
                }.distinctUntilChanged()

        /** Turns automatic backups on with [uri], or off with null. A new folder forgets the last fingerprint. */
        suspend fun setFolder(
            uri: String?,
            name: String?,
        ) {
            dataStore.edit {
                if (uri == null) it.remove(FOLDER_URI) else it[FOLDER_URI] = uri
                if (name == null) it.remove(FOLDER_NAME) else it[FOLDER_NAME] = name
                it.remove(LAST_FINGERPRINT)
                it.remove(LAST_ERROR)
                it.remove(LAST_ERROR_AT)
            }
        }

        suspend fun setIncludePacks(include: Boolean) {
            dataStore.edit { it[INCLUDE_PACKS] = include }
        }

        suspend fun recordSuccess(
            at: Long,
            fileName: String,
            fingerprint: String,
        ) {
            dataStore.edit {
                it[LAST_SUCCESS_AT] = at
                it[LAST_FILE_NAME] = fileName
                it[LAST_FINGERPRINT] = fingerprint
                it.remove(LAST_ERROR)
                it.remove(LAST_ERROR_AT)
            }
        }

        suspend fun recordFailure(
            at: Long,
            message: String,
        ) {
            dataStore.edit {
                it[LAST_ERROR] = message
                it[LAST_ERROR_AT] = at
            }
        }

        private companion object {
            val FOLDER_URI = stringPreferencesKey("auto_backup_folder_uri")
            val FOLDER_NAME = stringPreferencesKey("auto_backup_folder_name")
            val INCLUDE_PACKS = booleanPreferencesKey("auto_backup_include_packs")
            val LAST_SUCCESS_AT = longPreferencesKey("auto_backup_last_success_at")
            val LAST_FILE_NAME = stringPreferencesKey("auto_backup_last_file_name")
            val LAST_FINGERPRINT = stringPreferencesKey("auto_backup_last_fingerprint")
            val LAST_ERROR = stringPreferencesKey("auto_backup_last_error")
            val LAST_ERROR_AT = longPreferencesKey("auto_backup_last_error_at")
        }
    }
