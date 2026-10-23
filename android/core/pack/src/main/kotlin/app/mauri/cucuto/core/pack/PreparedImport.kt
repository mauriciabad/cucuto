package app.mauri.cucuto.core.pack

import app.mauri.cucuto.packspec.BundleManifest
import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.PackManifest
import app.mauri.cucuto.packspec.ValidationReport
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What installing a pack would do, compared with what is installed. */
enum class InstallAction {
    /** The pack is not installed. */
    INSTALL,

    /** A lower version is installed: update in place, keeping every assignment. */
    UPDATE,

    /** The same version is installed: ask before replacing it ([ErrorCode.PACK_SAME_VERSION]). */
    REINSTALL,

    /** A higher version is installed: ask before downgrading ([ErrorCode.PACK_DOWNGRADE]). */
    DOWNGRADE,
    ;

    /** True when the user must confirm before this pack is installed. */
    val needsConfirmation: Boolean get() = this == REINSTALL || this == DOWNGRADE
}

/**
 * One pack of a prepared import (the only one of a single pack, or one of a bundle), unpacked and verified.
 * [root] is its folder in the archive ("" for a single pack).
 */
data class PackCandidate(
    val root: String,
    val manifest: PackManifest,
    val action: InstallAction,
    /** The active installed version, or null when the pack is new. */
    val installedVersion: String?,
    /** Unpacked size of the files that will be installed. */
    val sizeBytes: Long,
    val fileCount: Int,
) {
    val id: String get() = manifest.id
    val version: String get() = manifest.version
}

/**
 * A validated import waiting for the user's choice: the packs are unpacked and decoded in a staging folder, so
 * [PackImporter.install] only moves folders. Use it once: install it or [PackImporter.discard] it.
 */
class PreparedImport internal constructor(
    /** Name of the imported file, when known. */
    val displayName: String?,
    /** The bundle manifest, or null for a single pack. */
    val bundle: BundleManifest?,
    /** Packs in manifest order. All are selected by default ([PackSelection.default]). */
    val packs: List<PackCandidate>,
    /** Warnings (never errors), including PACK_SAME_VERSION and PACK_DOWNGRADE for installed packs. */
    val report: ValidationReport,
    internal val stagingDir: File,
    internal val stagedPacks: Map<String, File>,
) {
    private val used = AtomicBoolean(false)

    val isBundle: Boolean get() = bundle != null

    /**
     * The unpacked, verified files of [pack] while the import waits for the user (for previews: sprites and clips
     * resolve against this folder like against an installed one). Null once the import is installed or discarded.
     */
    fun stagedFolder(pack: PackCandidate): File? = stagedPacks[pack.root]?.takeIf { !used.get() && it.isDirectory }

    /** Marks the import as used; false if it already was. */
    internal fun claim(): Boolean = used.compareAndSet(false, true)
}

/**
 * Which packs of a [PreparedImport] to install. Every pack is selected by default (docs/pack-format.md#bundle);
 * packs whose [InstallAction.needsConfirmation] must also be in [confirmed], or install skips them.
 */
data class PackSelection(
    val selected: Set<String>,
    val confirmed: Set<String> = emptySet(),
) {
    fun isSelected(id: String): Boolean = id in selected

    /** Selects or deselects one pack. */
    fun with(
        id: String,
        selected: Boolean,
    ): PackSelection = copy(selected = if (selected) this.selected + id else this.selected - id)

    /** Records that the user confirmed replacing or downgrading [id]. */
    fun confirm(id: String): PackSelection = copy(confirmed = confirmed + id)

    /** Selected packs that still need a confirmation in [import]. */
    fun unconfirmed(import: PreparedImport): List<PackCandidate> =
        import.packs.filter { it.id in selected && it.action.needsConfirmation && it.id !in confirmed }

    companion object {
        /** Every pack selected, nothing confirmed yet. */
        fun default(import: PreparedImport): PackSelection = PackSelection(import.packs.map { it.id }.toSet())

        /** Every pack selected and confirmed. */
        fun all(import: PreparedImport): PackSelection =
            import.packs
                .map { it.id }
                .toSet()
                .let { PackSelection(it, it) }
    }
}
