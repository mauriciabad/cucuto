package app.mauri.cucuto.packspec

/** Path rules for entries inside a pack or bundle. */
object PackPaths {
    private val SEGMENT = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]*$")
    private val DRIVE = Regex("^[A-Za-z]:")
    private val IGNORED_NAMES = setOf("thumbs.db", "desktop.ini")

    /**
     * UNSAFE_PATH check: absolute paths, drive letters, backslashes, control characters, empty, '.' or '..' segments.
     * A trailing slash (directory entry) is allowed.
     */
    fun isUnsafe(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || DRIVE.containsMatchIn(path)) return true
        if (path.any { it == '\\' || it.code < 0x20 || it.code == 0x7F }) return true
        val segments = path.removeSuffix("/").split('/')
        return segments.any { it.isEmpty() || it == "." || it == ".." }
    }

    /** Operating system artifacts that importers skip with an IGNORED_ENTRY warning. Call after [isUnsafe]. */
    fun isIgnored(path: String): Boolean {
        val segments = path.removeSuffix("/").split('/')
        if (segments.first() == "__MACOSX") return true
        return segments.any { it.startsWith(".") } || segments.last().lowercase() in IGNORED_NAMES
    }

    /** INVALID_FILE_NAME check: portable characters, length and depth. Call after [isUnsafe] and [isIgnored]. */
    fun isInvalidName(path: String): Boolean {
        val trimmed = path.removeSuffix("/")
        if (trimmed.length > Limits.MAX_PATH_LENGTH) return true
        val segments = trimmed.split('/')
        return segments.size > Limits.MAX_PATH_DEPTH || segments.any { !SEGMENT.matches(it) }
    }

    /** Joins a pack root (empty for the archive root) and a manifest-relative path. */
    fun join(root: String, relative: String): String = if (root.isEmpty()) relative else "$root/$relative"

    /** The parent folder of [path], or "" at the root. */
    fun parent(path: String): String = path.substringBeforeLast('/', "")
}
