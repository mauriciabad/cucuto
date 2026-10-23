package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.zip.InflateStream
import app.mauri.cucuto.packspec.zip.RandomAccessInput
import app.mauri.cucuto.packspec.zip.RawInflater
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes

/** JVM adapters for the platform interfaces of pack-spec. */
object JvmPackIo {
    /** Raw DEFLATE via java.util.zip.Inflater; never produces more than the expected size. */
    val inflater: RawInflater = object : RawInflater {
        override fun inflate(compressed: ByteArray, expectedSize: Int): ByteArray? {
            val inf = Inflater(true)
            try {
                inf.setInput(compressed)
                val out = ByteArray(expectedSize)
                var n = 0
                while (n < expectedSize && !inf.finished()) {
                    val r = inf.inflate(out, n, expectedSize - n)
                    if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    n += r
                }
                // The stream must end exactly at the declared size.
                val probe = ByteArray(1)
                return if (n != expectedSize || (!inf.finished() && inf.inflate(probe) > 0)) null else out
            } catch (e: DataFormatException) {
                return null
            } finally {
                inf.end()
            }
        }

        override fun stream(): InflateStream = JvmInflateStream()
    }

    /** Validates a file or folder: folders as unpacked packs, anything else as a zip archive. */
    fun validatePath(path: Path, validator: PackValidator = PackValidator()): PackValidation =
        if (path.isDirectory()) validator.validate(FolderPackSource(path))
        else FileInput(path).use { validator.validateArchive(it, inflater) }
}

/** A raw DEFLATE stream on java.util.zip.Inflater. A preset dictionary (never used in zip) counts as corrupt data. */
private class JvmInflateStream : InflateStream {
    private val inflater = Inflater(true)
    override val needsInput: Boolean get() = inflater.needsInput()
    override val finished: Boolean get() = inflater.finished()

    override fun setInput(buffer: ByteArray, offset: Int, length: Int) = inflater.setInput(buffer, offset, length)

    override fun inflate(out: ByteArray, offset: Int, length: Int): Int = try {
        val n = inflater.inflate(out, offset, length)
        if (n == 0 && inflater.needsDictionary()) -1 else n
    } catch (e: DataFormatException) {
        -1
    }

    override fun close() = inflater.end()
}

/** Random access over a file. */
class FileInput(path: Path) : RandomAccessInput, AutoCloseable {
    private val file = RandomAccessFile(path.toFile(), "r")
    override val size: Long = file.length()

    override fun readFully(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        file.seek(position)
        file.readFully(buffer, offset, length)
    }

    override fun close() = file.close()
}

/** An unpacked pack or bundle in a folder. Symbolic links are reported as such and never followed. */
class FolderPackSource(private val root: Path) : PackSource {
    override val entries: List<SourceEntry> = Files.walk(root).use { stream ->
        stream.filter { it != root }.map { p ->
            val rel = root.relativize(p).joinToString("/")
            val kind = when {
                Files.isSymbolicLink(p) -> EntryKind.SYMLINK
                Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) -> EntryKind.DIRECTORY
                Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) -> EntryKind.FILE
                else -> EntryKind.OTHER
            }
            SourceEntry(rel, if (kind == EntryKind.FILE) Files.size(p) else 0L, kind)
        }.toList()
    }.sortedBy { it.path }

    private val files = entries.filter { it.kind == EntryKind.FILE }.associateBy { it.path }

    private fun resolve(path: String): Path {
        val entry = files[path] ?: throw PackReadException(ErrorCode.FILE_MISSING, "No such file: $path")
        return root.resolve(entry.path)
    }

    override fun read(path: String, maxBytes: Long): ByteArray {
        val p = resolve(path)
        if (Files.size(p) > maxBytes) throw PackReadException(ErrorCode.FILE_TOO_LARGE, "$path is larger than $maxBytes bytes")
        return p.readBytes()
    }

    override fun readHead(path: String, count: Int): ByteArray = Files.newInputStream(resolve(path)).use { it.readNBytes(count) }

    override fun copyTo(path: String, maxBytes: Long, sink: (ByteArray, Int, Int) -> Unit) {
        val p = resolve(path)
        if (Files.size(p) > maxBytes) throw PackReadException(ErrorCode.FILE_TOO_LARGE, "$path is larger than $maxBytes bytes")
        Files.newInputStream(p).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) sink(buffer, 0, n)
            }
        }
    }

    override fun <T> useFile(path: String, maxBytes: Long, block: (RandomAccessInput) -> T): T {
        val p = resolve(path)
        if (Files.size(p) > maxBytes) throw PackReadException(ErrorCode.FILE_TOO_LARGE, "$path is larger than $maxBytes bytes")
        return FileInput(p).use(block)
    }
}
