package app.mauri.cucuto.provider

import java.io.File
import java.io.IOException

/**
 * Who may read clips, and which file a clip URI names. Pure (no Android types) so the security rules are unit-tested
 * on the JVM; [ClipProvider] feeds it `Binder.getCallingUid()` and `PackageManager.getPackagesForUid()`.
 */
object ClipAccess {
    /** `android.os.Process.SYSTEM_UID`: `system_server` and other `android.uid.system` processes. */
    const val SYSTEM_APP_ID: Int = 1000

    /**
     * The process that actually opens notification sounds: SystemUI hosts `IRingtonePlayer` (confirmed in the
     * supervised M0 run). Matched by package, never by its install-time uid, which differs per device.
     */
    val ALLOWED_PACKAGES: Set<String> = setOf("com.android.systemui")

    private const val PER_USER_RANGE = 100_000

    /** Allows the system (in any user) and the packages in [ALLOWED_PACKAGES]; refuses everyone else. */
    fun isAllowedCaller(
        uid: Int,
        packagesForUid: Array<String>?,
    ): Boolean {
        if (uid < 0) return false
        if (uid % PER_USER_RANGE == SYSTEM_APP_ID) return true
        return packagesForUid?.any { it in ALLOWED_PACKAGES } == true
    }
}

/** What a clip URI points at. */
sealed interface ClipTarget {
    /** `content://app.mauri.cucuto.clips/<packId>/<characterId>/<path inside the pack>`. */
    data class PackClip(
        val packId: String,
        val characterId: String,
        val path: List<String>,
    ) : ClipTarget

    /** `content://app.mauri.cucuto.clips/selftest/chirp.wav`: the Diagnostics screen's test sound. */
    data object SelfTest : ClipTarget
}

/**
 * Parses and resolves clip URIs against the installed packs (`files/packs/`, layout from ADR 0008:
 * `<packId>/<version>/…` with an `active` pointer file). Path traversal is impossible twice over: every segment must
 * match the pack format's ID and path grammar (no `.`/`..`, separators or leading dots), and the resolved file's
 * canonical path must still be inside the pack's version folder.
 */
object ClipPaths {
    const val SELF_TEST_SEGMENT: String = "selftest"
    const val SELF_TEST_FILE: String = "chirp.wav"

    // Pack format, "IDs" and common.schema.json.
    private val PACK_ID = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
    private val SLUG = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private val PATH_SEGMENT = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]*$")
    private val VERSION = Regex("^[0-9A-Za-z][0-9A-Za-z.+-]*$")
    private const val MAX_PATH = 255

    /** Audio extensions from the pack format ("Media"). Only clips are served, never manifests or images. */
    val AUDIO_EXTENSIONS: Set<String> = setOf("ogg", "opus", "oga", "mp3", "m4a", "aac", "flac", "wav")

    private const val ACTIVE = "active"

    /** Parses decoded URI path segments; null for anything that is not a well-formed clip URI. */
    fun parse(segments: List<String>): ClipTarget? {
        if (segments == listOf(SELF_TEST_SEGMENT, SELF_TEST_FILE)) return ClipTarget.SelfTest
        if (segments.size < 3) return null
        val packId = segments[0]
        val characterId = segments[1]
        val path = segments.subList(2, segments.size)
        if (packId.length !in 3..128 || !PACK_ID.matches(packId)) return null
        if (characterId.length > 64 || !SLUG.matches(characterId)) return null
        if (path.any { !PATH_SEGMENT.matches(it) }) return null
        if (path.sumOf { it.length + 1 } - 1 > MAX_PATH) return null
        if (extension(path.last()) !in AUDIO_EXTENSIONS) return null
        return ClipTarget.PackClip(packId, characterId, path.toList())
    }

    /** The file for [target] under [packsRoot], or null when it does not exist or escapes its pack. */
    fun resolve(
        packsRoot: File,
        target: ClipTarget.PackClip,
    ): File? =
        try {
            val packDir = File(packsRoot, target.packId)
            val version = activeVersion(packDir) ?: return null
            val versionDir = File(packDir, version).canonicalFile
            val file = File(versionDir, target.path.joinToString(File.separator)).canonicalFile
            if (!file.path.startsWith(versionDir.path + File.separator)) return null
            if (!file.isFile) return null
            file
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    /** The `active` pointer, or the only installed version when the pointer is missing. */
    private fun activeVersion(packDir: File): String? {
        val pointer = File(packDir, ACTIVE)
        if (pointer.isFile) {
            val version = pointer.readText().trim()
            return version.takeIf { VERSION.matches(it) && File(packDir, it).isDirectory }
        }
        val versions = packDir.listFiles { f -> f.isDirectory && VERSION.matches(f.name) }.orEmpty()
        return versions.singleOrNull()?.name
    }

    fun mimeType(fileName: String): String =
        when (extension(fileName)) {
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()
}
