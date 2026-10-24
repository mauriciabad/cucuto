package app.mauri.cucuto.core.drive

import java.io.File

/**
 * Automatic backups in the user's Google Drive (#163, ADR 0040): a visible "Cucuto backups" folder that the app
 * created (so the `drive.file` scope covers it, also after a reinstall), found again by its app property rather
 * than its name, so renaming or moving it in Drive keeps working.
 *
 * Naming and pruning stay with `:core:backup` (`BackupFileNames`): the caller passes [isBackup] and [toPrune], so
 * files the user put in the folder are never touched.
 */
class DriveBackupStore(
    private val client: DriveClient,
    private val folderName: String = DEFAULT_FOLDER_NAME,
) {
    private var folderId: String? = null

    /** The backup folder's ID, creating the folder the first time. */
    suspend fun folder(): String {
        folderId?.let { return it }
        val query =
            "mimeType = ${DriveClient.quote(DriveFile.FOLDER_MIME_TYPE)} and trashed = false and " +
                "appProperties has { key = ${DriveClient.quote(APP_KEY)} and value = ${DriveClient.quote(APP_VALUE)} }"
        val found =
            client.search(query).minByOrNull { it.modifiedTime.orEmpty() }
                ?: client.createFolder(folderName, mapOf(APP_KEY to APP_VALUE))
        folderId = found.id
        return found.id
    }

    /** The backups in the folder, newest first (by Drive's modified time, then name). */
    suspend fun backups(isBackup: (String) -> Boolean): List<DriveFile> =
        client
            .list(folder())
            .filter { !it.isFolder && isBackup(it.name) }
            .sortedWith(compareByDescending<DriveFile> { it.modifiedTime.orEmpty() }.thenByDescending { it.name })

    /**
     * Uploads [file] as [name]. A file with that name already in the folder gets the new content (Drive keeps the
     * previous one as a revision), so "one file per day" holds like in a local folder.
     */
    suspend fun upload(
        file: File,
        name: String,
        mimeType: String,
    ): DriveFile {
        val parent = folder()
        val existing = client.list(parent, "name = ${DriveClient.quote(name)}").firstOrNull { !it.isFolder }
        return try {
            client.upload(file, name, mimeType, parentId = parent, replaceId = existing?.id)
        } catch (e: DriveException) {
            if (e.error != DriveError.NotFound) throw e
            // The folder (or the file) was deleted since we last looked: start over once.
            folderId = null
            client.upload(file, name, mimeType, parentId = folder())
        }
    }

    /** Deletes the files [toPrune] picks among the folder's names; returns how many went. */
    suspend fun prune(toPrune: (Collection<String>) -> Collection<String>): Int {
        val files = client.list(folder()).filter { !it.isFolder }
        val doomed = toPrune(files.map { it.name }).toSet()
        val victims = files.filter { it.name in doomed }
        victims.forEach { client.delete(it.id) }
        return victims.size
    }

    /** Downloads a backup listed by [backups] for the restore flow. */
    suspend fun download(
        backup: DriveFile,
        destination: File,
        maxBytes: Long,
    ) = client.download(backup.id, destination, maxBytes)

    companion object {
        /** Shown in the user's Drive; the app property, not the name, identifies it. */
        const val DEFAULT_FOLDER_NAME = "Cucuto backups"
        const val APP_KEY = "cucuto"
        const val APP_VALUE = "backups"
    }
}
