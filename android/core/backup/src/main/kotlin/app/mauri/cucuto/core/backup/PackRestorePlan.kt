package app.mauri.cucuto.core.backup

/** How one pack reference of a backup gets back onto the phone. */
enum class PackRestoreAction {
    /** Every pack it needs is installed already (any version): nothing to do. */
    INSTALLED,

    /** Import the file included in the backup ([OpenedBackup.includedPack]). */
    FROM_BACKUP,

    /** Download from its repository (look the ID up in the index; SHA-256 verified), then import. */
    DOWNLOAD_FROM_REPOSITORY,

    /** Download its direct link, check the backup's SHA-256, then import. */
    DOWNLOAD_FROM_URL,

    /** Imported from a local file that is not in the backup: ask the user to pick it again. */
    PICK_FILE,
}

/**
 * One step of a restore's pack list (docs/project-brief.md, "Restore on a new phone", step 2).
 *
 * @property missingPackIds The packs of [reference] that are not installed (all of them for a missing pack).
 */
data class PackRestoreItem(
    val reference: BackupPackReference,
    val action: PackRestoreAction,
    val missingPackIds: List<String>,
)

/** Decides how to bring back each pack of a backup. Pure, so the restore screen can recompute it after each import. */
object PackRestorePlanner {
    /**
     * @param installed IDs of the installed packs, and for packs from a bundle, the bundle ID they came with.
     * @param included References whose file is in the backup ([OpenedBackup.includedPacks]).
     */
    fun plan(
        references: List<BackupPackReference>,
        installed: Map<String, String?>,
        included: Collection<BackupPackReference> = emptyList(),
    ): List<PackRestoreItem> {
        val includedIds = included.map { it.id }.toSet()
        return references.map { ref ->
            val missing =
                when {
                    ref.type == BackupPackType.PACK -> listOf(ref.id).filterNot { it in installed }

                    !ref.packs.isNullOrEmpty() -> ref.packs.filterNot { it in installed }

                    // A bundle without its pack list: satisfied by any pack installed from it.
                    installed.values.any { it == ref.id } -> emptyList()

                    else -> listOf(ref.id)
                }
            val action =
                when {
                    missing.isEmpty() -> PackRestoreAction.INSTALLED
                    ref.id in includedIds -> PackRestoreAction.FROM_BACKUP
                    ref.source.type == BackupPackSourceType.REPOSITORY -> PackRestoreAction.DOWNLOAD_FROM_REPOSITORY
                    ref.source.type == BackupPackSourceType.URL -> PackRestoreAction.DOWNLOAD_FROM_URL
                    else -> PackRestoreAction.PICK_FILE
                }
            PackRestoreItem(ref, action, missing)
        }
    }
}
