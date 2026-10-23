package app.mauri.cucuto.packer

import app.mauri.cucuto.packspec.EntryKind
import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.PackReadException
import app.mauri.cucuto.packspec.PackSource
import app.mauri.cucuto.packspec.SourceEntry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes

/** The bytes of one file in a pack being assembled: either still on disk or already in memory. */
sealed interface Content {
    val size: Long
    fun bytes(): ByteArray

    data class OnDisk(val path: Path) : Content {
        override val size: Long get() = path.fileSize()
        override fun bytes(): ByteArray = path.readBytes()
    }

    class InMemory(private val data: ByteArray) : Content {
        override val size: Long get() = data.size.toLong()
        override fun bytes(): ByteArray = data
    }
}

/**
 * A pack or bundle ready to be validated and zipped: archive path to content. Archive paths use '/' and are relative to
 * the archive root.
 */
class AssembledPack(files: Map<String, Content>) : PackSource {
    val files: Map<String, Content> = files.toSortedMap()

    override val entries: List<SourceEntry> = this.files.map { (p, c) -> SourceEntry(p, c.size, EntryKind.FILE) }

    override fun read(path: String, maxBytes: Long): ByteArray {
        val c = files[path] ?: throw PackReadException(ErrorCode.FILE_MISSING, "No such file: $path")
        if (c.size > maxBytes) throw PackReadException(ErrorCode.FILE_TOO_LARGE, "$path is larger than $maxBytes bytes")
        return c.bytes()
    }

    override fun readHead(path: String, count: Int): ByteArray {
        val c = files[path] ?: throw PackReadException(ErrorCode.FILE_MISSING, "No such file: $path")
        return when (c) {
            is Content.OnDisk -> Files.newInputStream(c.path).use { it.readNBytes(count) }
            is Content.InMemory -> c.bytes().let { if (it.size <= count) it else it.copyOf(count) }
        }
    }

    /** This pack's files moved under [prefix] (for bundles). */
    fun prefixed(prefix: String) = AssembledPack(files.mapKeys { "$prefix/${it.key}" })
}
