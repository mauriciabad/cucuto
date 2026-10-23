package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.zip.ByteArrayInput
import app.mauri.cucuto.packspec.zip.RandomAccessInput

enum class EntryKind { FILE, DIRECTORY, SYMLINK, OTHER }

/** One entry of an unpacked folder or a zip archive. [path] uses '/' separators, relative to the root. */
data class SourceEntry(val path: String, val size: Long, val kind: EntryKind)

/** Thrown by [PackSource.read] when an entry cannot be read; the validator reports [code]. */
class PackReadException(val code: ErrorCode, message: String) : Exception(message)

/**
 * The files of a pack or bundle, independent of where they live (zip archive, folder, memory). Platform code provides
 * implementations; the validation rules only see this interface.
 */
interface PackSource {
    val entries: List<SourceEntry>

    /** Reads a whole file. Implementations must never return more than [maxBytes] bytes and throw if the file is larger. */
    fun read(path: String, maxBytes: Long = Long.MAX_VALUE): ByteArray

    /** Reads up to [count] leading bytes. Reports the same errors as [read]. */
    fun readHead(path: String, count: Int): ByteArray {
        val all = read(path)
        return if (all.size <= count) all else all.copyOf(count)
    }

    /**
     * Streams a whole file to [sink] in order, in chunks, without holding it in memory. Reports the same errors as
     * [read], but integrity errors (a zip entry's size or CRC-32) can come after some chunks were delivered: discard
     * what [sink] received when this throws.
     */
    fun copyTo(path: String, maxBytes: Long = Long.MAX_VALUE, sink: (ByteArray, Int, Int) -> Unit) {
        val bytes = read(path, maxBytes)
        sink(bytes, 0, bytes.size)
    }

    /**
     * Runs [block] with random access to a whole file, without necessarily loading it into memory. The file is checked
     * first, so this reports the same errors as [read]. The input is only valid inside [block].
     */
    fun <T> useFile(path: String, maxBytes: Long = Long.MAX_VALUE, block: (RandomAccessInput) -> T): T =
        block(ByteArrayInput(read(path, maxBytes)))
}

/** A [PackSource] over in-memory files. Used by tests, by `packer build` before writing, and by the web pack creator. */
class InMemoryPackSource(files: Map<String, ByteArray>) : PackSource {
    private val files = files.toMap()
    override val entries: List<SourceEntry> = this.files.map { (p, b) -> SourceEntry(p, b.size.toLong(), EntryKind.FILE) }

    override fun read(path: String, maxBytes: Long): ByteArray {
        val bytes = files[path] ?: throw PackReadException(ErrorCode.FILE_MISSING, "No such file: $path")
        if (bytes.size > maxBytes) throw PackReadException(ErrorCode.FILE_TOO_LARGE, "$path is larger than $maxBytes bytes")
        return bytes
    }
}
