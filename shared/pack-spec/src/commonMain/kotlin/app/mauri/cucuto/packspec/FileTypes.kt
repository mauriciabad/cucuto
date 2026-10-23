package app.mauri.cucuto.packspec

enum class FileKind { AUDIO, IMAGE, MANIFEST, TEXT }

/** A supported file format, identified by extension (case-insensitive). */
enum class FileFormat(val kind: FileKind, val extensions: List<String>, val mimeType: String) {
    OGG(FileKind.AUDIO, listOf("ogg", "oga", "opus"), "audio/ogg"),
    MP3(FileKind.AUDIO, listOf("mp3"), "audio/mpeg"),
    M4A(FileKind.AUDIO, listOf("m4a"), "audio/mp4"),
    AAC(FileKind.AUDIO, listOf("aac"), "audio/aac"),
    FLAC(FileKind.AUDIO, listOf("flac"), "audio/flac"),
    WAV(FileKind.AUDIO, listOf("wav"), "audio/wav"),
    PNG(FileKind.IMAGE, listOf("png"), "image/png"),
    JPEG(FileKind.IMAGE, listOf("jpg", "jpeg"), "image/jpeg"),
    WEBP(FileKind.IMAGE, listOf("webp"), "image/webp"),
    GIF(FileKind.IMAGE, listOf("gif"), "image/gif"),
    AVIF(FileKind.IMAGE, listOf("avif"), "image/avif"),
    JXL(FileKind.IMAGE, listOf("jxl"), "image/jxl"),
    SVG(FileKind.IMAGE, listOf("svg"), "image/svg+xml"),
    JSON(FileKind.MANIFEST, listOf("json"), "application/json"),
    MARKDOWN(FileKind.TEXT, listOf("md"), "text/markdown"),
    PLAIN_TEXT(FileKind.TEXT, listOf("txt"), "text/plain"),
    ;

    val isBitmap: Boolean get() = kind == FileKind.IMAGE && this != SVG

    /** Formats allowed for `spriteAnimated`. */
    val canAnimate: Boolean get() = this == GIF || this == WEBP || this == JXL

    val maxBytes: Long
        get() = when (kind) {
            FileKind.AUDIO -> Limits.AUDIO_FILE_BYTES
            FileKind.IMAGE -> if (this == SVG) Limits.SVG_FILE_BYTES else Limits.BITMAP_FILE_BYTES
            FileKind.MANIFEST -> Limits.MANIFEST_BYTES
            FileKind.TEXT -> Limits.TEXT_FILE_BYTES
        }

    companion object {
        private val byExtension = entries.flatMap { f -> f.extensions.map { it to f } }.toMap()

        /** Extension-less text files allowed anywhere (case-insensitive). */
        val TEXT_FILE_NAMES = setOf("license", "licence", "copying", "notice", "authors", "readme", "credits")

        /** Extensions that are always rejected as executable or active content. */
        val EXECUTABLE_EXTENSIONS = setOf(
            "apk", "apks", "xapk", "aab", "dex", "odex", "oat", "so", "dylib", "dll", "exe", "com", "msi", "bat", "cmd",
            "sh", "bash", "zsh", "ps1", "vbs", "js", "mjs", "cjs", "jar", "class", "py", "pyc", "rb", "pl", "php", "lua",
            "html", "htm", "xhtml", "hta", "wasm", "elf", "bin", "app", "deb", "rpm", "kts", "kt", "scr", "lnk",
        )

        fun extensionOf(path: String): String? {
            val name = path.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            return if (dot <= 0 || dot == name.length - 1) null else name.substring(dot + 1).lowercase()
        }

        /** The format of [path], or null when the extension is not on the allowlist. */
        fun of(path: String): FileFormat? {
            val ext = extensionOf(path)
            if (ext == null) {
                val name = path.substringAfterLast('/').lowercase()
                return if (name in TEXT_FILE_NAMES) PLAIN_TEXT else null
            }
            return byExtension[ext]
        }

        fun isExecutableExtension(path: String): Boolean = extensionOf(path) in EXECUTABLE_EXTENSIONS
    }
}

/** Leading bytes of executables. Media files that start with one of these are rejected as EXECUTABLE_FILE. */
internal object ExecutableSignatures {
    private val signatures: List<ByteArray> = listOf(
        byteArrayOf(0x7F, 0x45, 0x4C, 0x46), // ELF
        byteArrayOf(0x4D, 0x5A), // PE / MZ
        byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCE.toByte()), // Mach-O 32
        byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCF.toByte()), // Mach-O 64
        byteArrayOf(0xCE.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()), // Mach-O 32, little-endian
        byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()), // Mach-O 64, little-endian
        byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()), // Mach-O fat / Java class
        byteArrayOf(0x64, 0x65, 0x78, 0x0A), // DEX
        byteArrayOf(0x23, 0x21), // #! script
    )

    fun matches(head: ByteArray): Boolean = signatures.any { sig ->
        head.size >= sig.size && sig.indices.all { head[it] == sig[it] }
    }
}
