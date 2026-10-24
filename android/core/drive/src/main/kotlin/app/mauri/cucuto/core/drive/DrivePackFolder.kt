package app.mauri.cucuto.core.drive

/** What a Drive folder offers as a pack source (#37): an index, or else its pack files. */
data class DrivePackFolderContents(
    /** `repository.json` (docs/pack-format.md, "Pack repositories"), when the folder has one. */
    val index: DriveFile?,
    /** `*.soundpack.zip` files (any `.zip`; the importer checks the contents), by name. */
    val packs: List<DriveFile>,
)

/**
 * A Drive folder as a pack source (#37, ADR 0040). With the `drive.file` scope Cucuto only sees what the user picked
 * in the Google Picker (selecting a folder does not grant its contents), so the listing holds the picked files; the
 * folder's owner adds new packs, the user picks them once more. Downloads go through [DriveClient.download] and then
 * the pack importer, like repository downloads.
 */
class DrivePackFolder(
    private val client: DriveClient,
) {
    suspend fun contents(folderId: String): DrivePackFolderContents = classify(client.list(folderId))

    companion object {
        const val INDEX_FILE_NAME = "repository.json"

        fun classify(files: List<DriveFile>): DrivePackFolderContents {
            val plain = files.filter { !it.isFolder }
            return DrivePackFolderContents(
                index = plain.firstOrNull { it.name == INDEX_FILE_NAME },
                packs = plain.filter { it.name.lowercase().endsWith(".zip") }.sortedBy { it.name.lowercase() },
            )
        }
    }
}
