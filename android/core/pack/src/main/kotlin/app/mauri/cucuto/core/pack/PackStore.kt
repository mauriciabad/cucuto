package app.mauri.cucuto.core.pack

import app.mauri.cucuto.packspec.PackManifest
import app.mauri.cucuto.packspec.SemVer
import app.mauri.cucuto.packspec.SpecJson
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A pack on disk: every installed version and the one in use. */
data class InstalledPackInfo(
    val id: String,
    val activeVersion: String?,
    val versions: List<String>,
)

/**
 * Installed packs on disk, under `files/packs/` (docs/decisions/0008-pack-importer.md):
 *
 * ```
 * files/packs/
 * ├── <pack id>/
 * │   ├── <version>/      pack.json and the pack's files, exactly as in the archive (relative to the pack root)
 * │   └── active          the version in use (one line), replaced atomically
 * ├── .staging/           imports being verified; same file system, so committing is a rename
 * └── .trash/             replaced or uninstalled folders, deleted right after the move
 * ```
 *
 * Every change is a rename on the same file system, so readers (the clip provider, the UI) see either the old or the
 * new folder, never a half-written one. A newly committed version does not replace the active one until [activate]
 * switches to it; only then are the other versions deleted. All methods do blocking IO: call them off the main thread.
 */
class PackStore(
    val root: File,
) {
    private val lock = Any()

    internal val stagingRoot: File get() = File(root, STAGING)
    private val trashRoot: File get() = File(root, TRASH)

    /** Every installed pack, sorted by ID. */
    fun installedPacks(): List<InstalledPackInfo> =
        synchronized(lock) {
            root
                .listFiles { f -> f.isDirectory && isValidId(f.name) }
                .orEmpty()
                .map { info(it.name) }
                .filter { it.versions.isNotEmpty() }
                .sortedBy { it.id }
        }

    /** The installed versions of [id], lowest first, and the active one. */
    fun info(id: String): InstalledPackInfo =
        synchronized(lock) {
            requireValidId(id)
            val dir = File(root, id)
            val versions =
                dir
                    .listFiles { f -> f.isDirectory && SemVer.parse(f.name) != null }
                    .orEmpty()
                    .map { it.name }
                    .sortedBy { SemVer.parse(it) }
            val pointer = File(dir, ACTIVE).takeIf { it.isFile }?.readText()?.trim()
            // Without a valid pointer (an interrupted first install), the highest version is the active one.
            val active = pointer?.takeIf { it in versions } ?: versions.lastOrNull()
            InstalledPackInfo(id, active, versions)
        }

    /** The version of [id] in use, or null when it is not installed. */
    fun activeVersion(id: String): String? = info(id).activeVersion

    /** The folder of one installed version (it may not exist). */
    fun packDir(
        id: String,
        version: String,
    ): File {
        requireValidId(id)
        requireValidVersion(version)
        return File(File(root, id), version)
    }

    /** The folder of the active version of [id], or null when it is not installed. */
    fun activePackDir(id: String): File? = activeVersion(id)?.let { packDir(id, it) }

    /** The manifest of the active version of [id], or null when it is not installed or unreadable. */
    fun activeManifest(id: String): PackManifest? {
        val file = activePackDir(id)?.resolve(MANIFEST)?.takeIf { it.isFile } ?: return null
        return try {
            SpecJson.decodeFromString<PackManifest>(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** A new, empty folder under `.staging/` for an import session. */
    internal fun newStagingDir(): File {
        val dir = File(stagingRoot, UUID.randomUUID().toString())
        if (!dir.mkdirs()) throw IOException("Cannot create $dir")
        return dir
    }

    /**
     * Moves the verified pack in [staged] to `<id>/<version>/` with a rename. A folder already installed for the same
     * version (a confirmed reinstall) is moved to the trash first. Does not change the active version, except for a
     * pack that had no version installed: its first version becomes active.
     */
    fun commit(
        staged: File,
        id: String,
        version: String,
    ): File =
        synchronized(lock) {
            val target = packDir(id, version)
            val stagedCanonical = staged.canonicalFile
            require(
                stagedCanonical.toPath().startsWith(stagingRoot.canonicalFile.toPath()),
            ) { "$staged is not a staging folder" }
            require(File(staged, MANIFEST).isFile) { "$staged has no $MANIFEST" }
            val packFolder = target.parentFile!!
            val firstVersion = info(id).versions.isEmpty()
            if (!packFolder.isDirectory && !packFolder.mkdirs()) throw IOException("Cannot create $packFolder")
            if (target.exists()) moveToTrashAndDelete(target)
            move(staged, target)
            if (firstVersion) writeActive(id, version)
            target
        }

    /**
     * Makes [version] the active version of [id] and then deletes every other version: the previous version stays
     * on disk until this switch, so nothing that is playing loses its files mid-update.
     */
    fun activate(
        id: String,
        version: String,
    ): Unit =
        synchronized(lock) {
            val dir = packDir(id, version)
            require(dir.isDirectory) { "$id $version is not installed" }
            writeActive(id, version)
            for (other in info(id).versions) {
                if (other != version) moveToTrashAndDelete(packDir(id, other))
            }
        }

    /** Removes every version of [id]. Returns false when it was not installed. */
    fun uninstall(id: String): Boolean =
        synchronized(lock) {
            requireValidId(id)
            val dir = File(root, id)
            if (!dir.exists()) return false
            moveToTrashAndDelete(dir)
            true
        }

    /** Deletes leftovers of interrupted imports and removals (call once at startup). */
    fun deleteLeftovers(): Unit =
        synchronized(lock) {
            stagingRoot.deleteRecursively()
            trashRoot.deleteRecursively()
        }

    /** Deletes one staging folder (an import that was discarded or failed). */
    internal fun deleteStaging(dir: File) {
        if (dir.canonicalFile.toPath().startsWith(stagingRoot.canonicalFile.toPath())) dir.deleteRecursively()
    }

    private fun writeActive(
        id: String,
        version: String,
    ) {
        val dir = File(root, id)
        val tmp = File(dir, "$ACTIVE.tmp")
        tmp.writeText(version + "\n")
        move(tmp, File(dir, ACTIVE))
    }

    private fun moveToTrashAndDelete(dir: File) {
        if (!trashRoot.isDirectory && !trashRoot.mkdirs()) throw IOException("Cannot create $trashRoot")
        val trash = File(trashRoot, UUID.randomUUID().toString())
        move(dir, trash)
        trash.deleteRecursively()
    }

    private fun move(
        from: File,
        to: File,
    ) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException("$from and $to are not on the same file system", e)
        }
    }

    companion object {
        const val MANIFEST = "pack.json"
        internal const val ACTIVE = "active"
        internal const val STAGING = ".staging"
        internal const val TRASH = ".trash"

        private val ID = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")

        /** The store in the app's private files: `files/packs/`. */
        fun inFilesDir(filesDir: File): PackStore = PackStore(File(filesDir, "packs"))

        fun isValidId(id: String): Boolean = id.length in 3..128 && ID.matches(id)

        private fun requireValidId(id: String) = require(isValidId(id)) { "Invalid pack ID: $id" }

        private fun requireValidVersion(version: String) =
            require(SemVer.parse(version) != null) {
                "Invalid version: $version"
            }
    }
}
