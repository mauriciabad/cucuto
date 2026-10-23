package app.mauri.cucuto.packs

import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.core.pack.FailureReason
import app.mauri.cucuto.core.pack.ImportProgress
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.pack.InstalledPack
import app.mauri.cucuto.core.pack.PackCandidate
import app.mauri.cucuto.core.pack.PackSelection
import app.mauri.cucuto.packspec.BundleManifest
import app.mauri.cucuto.packspec.Issue
import app.mauri.cucuto.packspec.ValidationReport
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * What the Packs screen needs from the pack library: the installed packs, the import pipeline and removal.
 * [PackLibrary] is the real one; view model tests use a fake.
 */
interface PackCatalog {
    /** Installed packs with their characters and the contacts that use them, sorted by name. */
    val installed: Flow<List<LibraryPack>>

    /** Copies, validates, unpacks and test-decodes [source]. Emits [ImportStep.Working], then one final step. */
    fun prepare(source: ImportSource): Flow<ImportStep>

    /** Installs the packs of [review] chosen in [selection]. Ends with [ImportStep.Installed] or a failure. */
    fun install(
        review: ImportReview,
        selection: PackSelection,
    ): Flow<ImportStep>

    /** Drops a prepared import and its unpacked files. Safe to call at any time, even after the screen is gone. */
    fun discard(review: ImportReview)

    /**
     * Uninstalls [packId]. With [clearAssignments] the contacts that use its characters lose their assignment;
     * otherwise they keep it and hear WhatsApp's own tone until the pack is imported again.
     */
    suspend fun remove(
        packId: String,
        clearAssignments: Boolean,
    )
}

/** An installed pack as the Packs screen shows it. */
data class LibraryPack(
    val id: String,
    val name: String,
    val version: String,
    val author: String?,
    val license: String?,
    val description: String?,
    val cover: File?,
    val characters: List<LibraryCharacter>,
    val sizeBytes: Long,
    val source: PackSource,
    val bundleId: String?,
    /** Contacts whose character comes from this pack. */
    val contacts: List<AssignedContact>,
    /** True when the installed files cannot be read (deleted or corrupted): importing the pack again fixes it. */
    val filesMissing: Boolean = false,
)

/** One character of an installed or reviewed pack, with absolute paths to its files. */
data class LibraryCharacter(
    val id: String,
    val name: String,
    val sprite: File?,
    /** The pack's `seedColor` as ARGB, or null. */
    val seedColor: Int?,
    val clips: List<File>,
    val ringtoneCount: Int,
)

/** A contact that uses a character of a pack. */
data class AssignedContact(
    val contactId: Long,
    /** Best known name, or null when the contact has none yet (a bare phone number or group ID). */
    val name: String?,
    val characterId: String,
)

/** One step of an import, as the screen sees it. */
sealed interface ImportStep {
    data class Working(
        val progress: ImportProgress,
    ) : ImportStep

    /** The file is valid: show [review] and let the user choose. */
    data class Ready(
        val review: ImportReview,
    ) : ImportStep

    /**
     * The file breaks the pack format. [detected] says what the zip is when it is not a pack at all (ADR 0007: the
     * type comes from the contents).
     */
    data class Invalid(
        val report: ValidationReport,
        val detected: DetectedFile = DetectedFile.UNKNOWN,
    ) : ImportStep

    data class Failed(
        val reason: FailureReason,
        val message: String,
    ) : ImportStep

    data class Installed(
        val packs: List<InstalledPack>,
        val skipped: List<PackCandidate>,
    ) : ImportStep
}

/** What a zip that is not a pack turned out to be. */
enum class DetectedFile {
    UNKNOWN,

    /** `backup.json`, or `envelope.json` with `payload.bin`, at the root: a Cucuto backup. */
    BACKUP,
}

/**
 * A validated import waiting for the user's choice. Wraps the importer's prepared import ([handle], opaque here) so
 * the screen logic can be tested without unpacking real files.
 */
class ImportReview(
    /** Name of the imported file, when the source knows it. */
    val fileName: String?,
    /** The bundle manifest, or null for a single pack. */
    val bundle: BundleManifest?,
    val packs: List<ReviewPack>,
    /** Warnings to show, without the per-pack version notes (those are in [ReviewPack.candidate]). */
    val warnings: List<Issue>,
    internal val handle: Any?,
) {
    val isBundle: Boolean get() = bundle != null
}

/** One pack of an [ImportReview]. */
data class ReviewPack(
    val candidate: PackCandidate,
    /** Its characters, with files in the staging folder for previews. */
    val characters: List<LibraryCharacter>,
    /** Contacts that use characters this version no longer has: they will hear WhatsApp's own tone. */
    val orphanedContacts: Int = 0,
) {
    val id: String get() = candidate.id
    val name: String get() = candidate.manifest.name
}
