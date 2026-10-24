package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.core.routing.RoutingTable
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Decides which URI the routing table gives each clip (ADR 0018).
 *
 * SystemUI opens the clip when WhatsApp alerts. If that URI is the app's `content://` clip provider and Cucuto's
 * process is not running, Android has to cold-start the process first, and the sound starts about 0.4 s late (#121).
 * A mirror copies the clips to a place SystemUI can read directly, so the URI becomes a `file://` path and no
 * process starts. The provider stays the fallback for every clip that is not mirrored.
 */
interface ClipMirror {
    /** The URI to publish for [file] (a path from `pack.json`) of [characterId] in the installed [pack]. */
    suspend fun clipUri(
        pack: InstalledPackEntity,
        characterId: String,
        file: String,
    ): String

    /** The mirrored Diagnostics chirp ([RoutingTable.selfTestClip]), or null to keep the provider's. */
    suspend fun selfTestUri(): String?

    /** Deletes mirrored files that the published [table] no longer references. */
    suspend fun retain(table: RoutingTable)
}

/** No mirror: every clip is served by the provider. For tests, and where SystemUI could not read a mirror. */
object ProviderClips : ClipMirror {
    override suspend fun clipUri(
        pack: InstalledPackEntity,
        characterId: String,
        file: String,
    ): String = RoutingContract.clipUri(pack.packId, characterId, file)

    override suspend fun selfTestUri(): String? = null

    override suspend fun retain(table: RoutingTable) = Unit
}

/**
 * The file side of the clip mirror: copies under [root], laid out as `<packId>/<version>/<path in the pack>` (each
 * installed version is its own immutable folder, ADR 0008) plus [SELF_TEST]. Plain `java.io`, unit-tested on the
 * JVM. Blocking: call it on an IO thread. Never throws; a clip it cannot mirror gets null, so the caller keeps the
 * provider URI for it.
 *
 * Copies are written to a temporary file and renamed, so SystemUI never reads half a clip. A copy is current when it
 * has the source's size and is not older than it; each current copy is checked once per process, so rebuilding the
 * routing table does not stat every clip again.
 *
 * [mirror] and [retain] are serialised, and [retain] also keeps everything [mirror] handed out since the previous
 * [retain]: a table that is still being built (or not yet published) never loses a copy it already points at.
 */
class ClipMirrorStore(
    private val root: File,
) {
    private val lock = Any()
    private val checked = HashSet<String>()
    private val issued = HashSet<String>()

    /** Creates [root] and its `.nomedia` marker (keeps the copies out of the media library). False on failure. */
    fun prepare(): Boolean =
        try {
            root.mkdirs()
            val marker = File(root, NO_MEDIA)
            (marker.isFile || marker.createNewFile()) && root.isDirectory
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    /** Mirrors clip [path] of an installed pack whose files are in [packDir]; returns its `file://` URI or null. */
    fun mirrorClip(
        packDir: File,
        packId: String,
        version: String,
        path: String,
    ): String? = mirror(File(packDir, path), listOf(packId, version) + path.split('/'))

    /** Mirrors [source] at `<root>/<relative…>` and returns its `file://` URI, or null when it cannot. */
    fun mirror(
        source: File,
        relative: List<String>,
    ): String? =
        synchronized(lock) {
            try {
                if (relative.isEmpty() || relative.any { !SEGMENT.matches(it) }) return null
                val target = File(root, relative.joinToString(File.separator))
                val uri = uriOf(target) ?: return null
                if (!source.isFile) return null
                val size = source.length()
                val stamp = "${target.path}\u0000$size\u0000${source.lastModified()}"
                if (stamp !in checked) {
                    if (!target.isFile || target.length() != size || target.lastModified() < source.lastModified()) {
                        copy(source, target)
                    }
                    checked += stamp
                }
                issued += uri
                uri
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

    /**
     * Deletes every mirrored file whose URI is neither in [keep] nor handed out by [mirror] since the previous call,
     * then any folder left empty. Never touches [root] or its `.nomedia`, and never follows a symbolic link.
     */
    fun retain(keep: Set<String>) {
        synchronized(lock) {
            val kept = keep + issued
            issued.clear()
            try {
                root
                    .walkBottomUp()
                    .onEnter { it == root || !Files.isSymbolicLink(it.toPath()) }
                    .forEach { file ->
                        when {
                            file == root -> Unit
                            Files.isSymbolicLink(file.toPath()) -> file.delete()
                            file.isDirectory -> if (file.list()?.isEmpty() == true) file.delete()
                            file.parentFile == root && file.name == NO_MEDIA -> Unit
                            uriOf(file) !in kept -> file.delete()
                        }
                    }
                checked.removeAll { stamp -> !File(stamp.substringBefore('\u0000')).isFile }
            } catch (_: IOException) {
                // A leftover copy costs only space; the next publication tries again.
            } catch (_: SecurityException) {
                // Same.
            }
        }
    }

    private fun copy(
        source: File,
        target: File,
    ) {
        val parent = target.parentFile ?: throw IOException("No parent for $target")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create $parent")
        val temporary = File(parent, ".${target.name}.tmp")
        try {
            source.copyTo(temporary, overwrite = true)
            if (!temporary.renameTo(target)) throw IOException("Cannot rename $temporary")
        } finally {
            temporary.delete()
        }
    }

    companion object {
        /** Where the Diagnostics chirp is mirrored. `selftest` has no dot, so it is never a pack ID. */
        val SELF_TEST: List<String> = listOf("selftest", "chirp.wav")

        private const val NO_MEDIA = ".nomedia"

        /** Pack IDs, versions and pack paths (pack format): no separators, no `.`/`..`, nothing to escape. */
        private val SEGMENT = Regex("^[A-Za-z0-9_][A-Za-z0-9._+-]*$")

        /** Characters a `file://` path can hold unescaped; anything else is not mirrored. */
        private val SAFE_PATH = Regex("^/[A-Za-z0-9/._+-]+$")

        /** `file://` plus the absolute path, or null when the path needs escaping (never the case on Android). */
        fun uriOf(file: File): String? = file.absolutePath.takeIf { SAFE_PATH.matches(it) }?.let { "file://$it" }

        /** Every `file://` clip URI in [table], self-test chirp included. */
        fun fileUris(table: RoutingTable): Set<String> =
            buildSet {
                for (voice in table.voices) for (clip in voice.clips) add(clip.uri)
                table.selfTestClip?.let(::add)
            }.filterTo(HashSet()) { it.startsWith("file://") }
    }
}
