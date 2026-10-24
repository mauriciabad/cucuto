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

/** Where automatic backups go (ADR 0040). */
enum class AutoBackupTargetKind {
    /** A folder picked with `ACTION_OPEN_DOCUMENT_TREE`: one file per day, the newest 10 kept. */
    FOLDER,

    /**
     * One document picked with `ACTION_CREATE_DOCUMENT`, rewritten by every backup. For cloud apps whose provider
     * offers no folders to other apps (Google Drive): the cloud app uploads it and keeps the old versions.
     */
    FILE,
}

/** The automatic backup setup and what its last run did. */
data class AutoBackupState(
    /**
     * Where backups go, an SAF URI with a persisted permission: the folder (tree URI) or, with [targetKind]
     * [AutoBackupTargetKind.FILE], the one document. Null when automatic backups are off.
     */
    val folderUri: String? = null,
    /** The folder's or the file's name as the document provider shows it, for the settings row. */
    val folderName: String? = null,
    val targetKind: AutoBackupTargetKind = AutoBackupTargetKind.FOLDER,
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
                        targetKind =
                            AutoBackupTargetKind.entries.firstOrNull { it.name == prefs[TARGET_KIND] }
                                ?: AutoBackupTargetKind.FOLDER,
                        includePacks = prefs[INCLUDE_PACKS] ?: true,
                        lastSuccessAt = prefs[LAST_SUCCESS_AT],
                        lastFileName = prefs[LAST_FILE_NAME],
                        lastFingerprint = prefs[LAST_FINGERPRINT],
                        lastError = prefs[LAST_ERROR],
                        lastErrorAt = prefs[LAST_ERROR_AT],
                    )
                }.distinctUntilChanged()

        /**
         * Turns automatic backups on with [uri] (a folder or one file, see [kind]), or off with null. A new target
         * forgets the last fingerprint.
         */
        suspend fun setFolder(
            uri: String?,
            name: String?,
            kind: AutoBackupTargetKind = AutoBackupTargetKind.FOLDER,
        ) {
            dataStore.edit {
                if (uri == null) it.remove(FOLDER_URI) else it[FOLDER_URI] = uri
                if (name == null) it.remove(FOLDER_NAME) else it[FOLDER_NAME] = name
                it[TARGET_KIND] = kind.name
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
            val TARGET_KIND = stringPreferencesKey("auto_backup_target_kind")
            val INCLUDE_PACKS = booleanPreferencesKey("auto_backup_include_packs")
            val LAST_SUCCESS_AT = longPreferencesKey("auto_backup_last_success_at")
            val LAST_FILE_NAME = stringPreferencesKey("auto_backup_last_file_name")
            val LAST_FINGERPRINT = stringPreferencesKey("auto_backup_last_fingerprint")
            val LAST_ERROR = stringPreferencesKey("auto_backup_last_error")
            val LAST_ERROR_AT = longPreferencesKey("auto_backup_last_error_at")
        }
    }
