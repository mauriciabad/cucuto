package app.mauri.cucuto.core.pack

import app.mauri.cucuto.packspec.ValidationReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

/** What [PackImporter.prepare] and [PackImporter.install] emit: progress, then exactly one [Finished]. */
sealed interface ImportEvent {
    data class Progress(
        val progress: ImportProgress,
    ) : ImportEvent

    data class Finished(
        val result: ImportResult,
    ) : ImportEvent
}

/** The current step, for a progress indicator. Counts are [done] of [total]; [total] is null when unknown. */
sealed interface ImportProgress {
    /** Copying the file into the app (SAF documents can be remote, slow or change while being read). */
    data class Copying(
        val bytesCopied: Long,
        val totalBytes: Long?,
    ) : ImportProgress

    /** Checking the archive, layout, manifests, references and media headers (pack-spec). */
    data object Validating : ImportProgress

    /** Unpacking the files that will be installed. */
    data class Extracting(
        val done: Int,
        val total: Int,
    ) : ImportProgress

    /** Decoding every clip, ringtone and image with the platform decoders. */
    data class Decoding(
        val done: Int,
        val total: Int,
    ) : ImportProgress

    /**
     * Preparing [packId] for installation with the [PackAnalyzer] (auto haptics: [done] of [total] clips), once the
     * user has confirmed. Part of installing from the user's point of view.
     */
    data class Analyzing(
        val packId: String,
        val done: Int,
        val total: Int,
    ) : ImportProgress

    /** Moving verified packs into place. */
    data class Installing(
        val done: Int,
        val total: Int,
    ) : ImportProgress
}

/** How an import ended. */
sealed interface ImportResult {
    /**
     * The file is valid and every pack is unpacked and verified. Show [PreparedImport.packs] (with warnings), let the
     * user pick and confirm, then call [PackImporter.install] or [PackImporter.discard].
     */
    data class Ready(
        val import: PreparedImport,
    ) : ImportResult

    /** The file breaks the pack format. [report] has every issue with its stable code (spec/error-codes.json). */
    data class Invalid(
        val report: ValidationReport,
    ) : ImportResult

    /** The selected packs are installed. [skipped] lists selected packs that still needed a confirmation. */
    data class Installed(
        val packs: List<InstalledPack>,
        val skipped: List<PackCandidate>,
    ) : ImportResult

    /** Something outside the file's content failed: reading the source, storage, or a discarded import. */
    data class Failed(
        val reason: FailureReason,
        val message: String,
        val cause: Throwable? = null,
    ) : ImportResult
}

enum class FailureReason {
    /** The document could not be opened or read (permission revoked, provider gone, network error). */
    SOURCE_UNREADABLE,

    /** The file is larger than any valid pack or bundle can be. */
    SOURCE_TOO_LARGE,

    /** Not enough free space to unpack the packs. */
    NOT_ENOUGH_STORAGE,

    /** Writing to app storage failed. */
    STORAGE_ERROR,

    /** [PackImporter.install] was called with an import that was already installed or discarded. */
    NOT_PREPARED,

    /** A bug or an unexpected platform error; the message and cause have the details. */
    UNEXPECTED,
}

/** One pack that [PackImporter.install] put in place. */
data class InstalledPack(
    val id: String,
    val version: String,
    val directory: java.io.File,
    /** The version that was active before, or null for a new pack. */
    val previousVersion: String?,
    /** True when this version is now the active one (older versions are deleted). */
    val active: Boolean,
)

/** The final result of a [PackImporter] flow, ignoring progress. */
suspend fun Flow<ImportEvent>.result(): ImportResult = filterIsInstance<ImportEvent.Finished>().first().result
