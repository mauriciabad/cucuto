package app.mauri.cucuto.backup

import app.mauri.cucuto.core.backup.BackupDocument
import app.mauri.cucuto.core.backup.BackupPackReference
import app.mauri.cucuto.core.backup.PackRestoreItem
import app.mauri.cucuto.core.backup.RestoreReport
import app.mauri.cucuto.core.backup.ZipContentType
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * What the Settings and restore screens need from `:core:backup` (ADR 0013, ADR 0028). [AppBackupGateway] is the
 * real one; view model tests use a fake. Documents are addressed by their URI string so the view models stay free of
 * Android types.
 */
interface BackupGateway {
    /** The name to suggest when creating a backup document: `cucuto-backup-YYYY-MM-DD.zip` for today. */
    fun suggestedFileName(): String

    /** Writes a backup into the document at [destination] (created with `ACTION_CREATE_DOCUMENT`). */
    suspend fun export(
        destination: String,
        request: ExportRequest,
    ): ExportOutcome

    /** Opens the backup document at [source], decrypting it with [passphrase] when it is encrypted. */
    suspend fun open(
        source: String,
        passphrase: CharArray? = null,
    ): OpenOutcome

    /** IDs of the installed packs, each with the bundle it came with (for [app.mauri.cucuto.core.backup.PackRestorePlanner]). */
    suspend fun installedPacks(): Map<String, String?>

    /** Restores the backup's contacts, voices, repositories and settings; replaces only the voices in [overwrite]. */
    suspend fun restore(
        session: RestoreSession,
        overwrite: Set<String> = emptySet(),
    ): RestoreReport

    /** Brings back one pack without asking for a file: from the backup itself, its repository or its download link. */
    fun restorePack(
        session: RestoreSession,
        item: PackRestoreItem,
    ): Flow<PackProgress>

    /** Imports the pack file the user picked for [item] (a local-only pack). */
    fun importPackFile(
        source: String,
        item: PackRestoreItem,
    ): Flow<PackProgress>
}

/** What to put in a manual backup. */
data class ExportRequest(
    /** A "full backup" with every installed pack's files. */
    val includePacks: Boolean,
    /** Encrypts the backup (Argon2id + AES-256-GCM); null for a plain backup. */
    val passphrase: CharArray? = null,
    /** Includes repository credentials; only with a [passphrase]. */
    val includeCredentials: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is ExportRequest &&
            includePacks == other.includePacks &&
            includeCredentials == other.includeCredentials &&
            passphrase.contentEquals(other.passphrase)

    override fun hashCode(): Int =
        31 * (31 * includePacks.hashCode() + includeCredentials.hashCode()) + passphrase.contentHashCode()

    override fun toString(): String = "ExportRequest(includePacks=$includePacks, encrypted=${passphrase != null})"
}

sealed interface ExportOutcome {
    data class Written(
        val encrypted: Boolean,
        val contacts: Int,
        /** Packs whose files are inside the backup. */
        val includedPacks: Int,
        /** Installed packs left out: no known file hash and their files were not included (debug tools). */
        val skippedPacks: List<String>,
    ) : ExportOutcome

    data class Failed(
        val message: String,
    ) : ExportOutcome
}

/** An opened, validated backup. Close it when the restore ends: it deletes the scratch copies. */
interface RestoreSession : Closeable {
    val document: BackupDocument

    /** True for passphrase-encrypted backups: only those restore repository credentials. */
    val encrypted: Boolean

    /** References whose pack file is inside the backup (a full backup). */
    val includedPacks: List<BackupPackReference>
}

sealed interface OpenOutcome {
    data class Opened(
        val session: RestoreSession,
    ) : OpenOutcome

    /** The backup is encrypted: ask for the passphrase. */
    data object PassphraseRequired : OpenOutcome

    /** Wrong passphrase, or the encrypted file was changed. */
    data object WrongPassphrase : OpenOutcome

    /** The file is a pack or a bundle, not a backup. */
    data class NotABackup(
        val type: ZipContentType,
    ) : OpenOutcome

    /** The file breaks the backup format; [details] are the validator's issues (stable codes first). */
    data class Invalid(
        val details: List<String>,
    ) : OpenOutcome

    data class Failed(
        val message: String,
    ) : OpenOutcome
}

/** Progress of bringing back one pack. */
sealed interface PackProgress {
    /** [fraction] is null while the size is unknown. */
    data class Working(
        val fraction: Float?,
    ) : PackProgress

    data object Done : PackProgress

    data class Failed(
        val reason: PackFailure,
        val message: String? = null,
    ) : PackProgress
}

/** Why a pack could not be restored, for a plain message. */
enum class PackFailure {
    /** The repository or link could not be reached, or answered with an error. */
    DOWNLOAD,

    /** The repository no longer lists the pack. */
    NOT_IN_REPOSITORY,

    /** The downloaded file is not the one in the backup (SHA-256 differs). */
    HASH_MISMATCH,

    /** The picked file does not contain the missing pack. */
    WRONG_FILE,

    /** The file is not a valid pack (the importer rejected it). */
    INVALID,

    /** Storage, permissions or anything else. */
    OTHER,
}
