package app.mauri.cucuto.packspec.zip

import app.mauri.cucuto.packspec.EntryKind
import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.Issue
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.PackReadException
import app.mauri.cucuto.packspec.PackSource
import app.mauri.cucuto.packspec.SourceEntry

/** Random access to the bytes of an archive (or of one file, see [PackSource.useFile]). */
interface RandomAccessInput {
    val size: Long

    /** Reads exactly [length] bytes at [position] into [buffer]; throws if the input is shorter. */
    fun readFully(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
}

class ByteArrayInput(private val bytes: ByteArray) : RandomAccessInput {
    override val size: Long get() = bytes.size.toLong()

    override fun readFully(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        if (position < 0 || position + length > bytes.size) throw IndexOutOfBoundsException("read past end")
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + length)
    }
}

/** Raw DEFLATE decompression, provided by the platform (java.util.zip.Inflater, DecompressionStream, ...). */
fun interface RawInflater {
    /** Inflates [compressed]. Returns null if the data is corrupt or does not expand to exactly [expectedSize] bytes. */
    fun inflate(compressed: ByteArray, expectedSize: Int): ByteArray?

    /**
     * Starts a streaming inflation, so large entries never sit in memory whole. Platforms without one return null and
     * entries are inflated in one piece with [inflate].
     */
    fun stream(): InflateStream? = null
}

/** One raw DEFLATE stream, fed with compressed input in chunks (the shape of java.util.zip.Inflater). */
interface InflateStream {
    /** All input given so far is consumed and more is needed to continue. */
    val needsInput: Boolean

    /** The end of the DEFLATE stream was reached. */
    val finished: Boolean

    /** Supplies compressed bytes. The array must not change until [needsInput] is true again. */
    fun setInput(buffer: ByteArray, offset: Int, length: Int)

    /** Inflates into [out]. Returns the bytes written (0 when input is needed or the stream is finished), or -1 for corrupt data. */
    fun inflate(out: ByteArray, offset: Int, length: Int): Int

    /** Releases the stream (native memory on the JVM). */
    fun close()
}

/** A central directory record. Sizes come from the central directory (Zip64-aware). */
class ZipEntryRecord(
    val name: String,
    val kind: EntryKind,
    val method: Int,
    val flags: Int,
    val crc32: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
)

/** Result of [ZipArchive.open]: the archive-stage issues, and a source when the archive is usable. */
class ZipOpenResult(val source: ZipArchive?, val issues: List<Issue>)

/**
 * Memory use of [ZipArchive] reads: entries up to [inMemoryBytes] are read whole, larger ones are streamed in
 * [chunkBytes] chunks, and random access to a large deflated entry keeps its first and last [windowBytes] in memory.
 */
internal class ZipTuning(
    val inMemoryBytes: Long = 4 * Limits.MiB,
    val windowBytes: Int = Limits.MiB.toInt(),
    val chunkBytes: Int = 64 * 1024,
)

/**
 * Minimal zip reader for pack archives: parses the central directory itself so it can see encryption flags, Unix
 * file types and declared sizes before inflating anything. Only stored (0) and deflate (8) entries are readable.
 *
 * Every read checks the whole entry: the local header, the data bounds, that a DEFLATE stream expands to exactly the
 * declared size, and the CRC-32. Large entries are streamed, so memory stays bounded whatever their size, and the
 * errors are the same whichever way an entry is read.
 */
class ZipArchive private constructor(
    private val input: RandomAccessInput,
    private val inflater: RawInflater,
    val records: List<ZipEntryRecord>,
    private val tuning: ZipTuning,
) : PackSource {

    private val byName = records.associateBy { it.name }

    override val entries: List<SourceEntry> = records.map { SourceEntry(it.name, it.uncompressedSize, it.kind) }

    /** Platforms without streaming inflation read deflated entries in one piece. */
    private val streaming: Boolean = inflater.stream()?.also { it.close() } != null

    private fun record(path: String, maxBytes: Long): ZipEntryRecord {
        val rec = byName[path] ?: throw PackReadException(ErrorCode.FILE_MISSING, "No such entry: $path")
        if (rec.uncompressedSize > maxBytes) {
            throw PackReadException(ErrorCode.FILE_TOO_LARGE, "$path is larger than $maxBytes bytes")
        }
        if (rec.uncompressedSize > Int.MAX_VALUE - 16 || rec.compressedSize > Int.MAX_VALUE - 16) {
            throw PackReadException(ErrorCode.FILE_TOO_LARGE, "$path is too large to read")
        }
        return rec
    }

    /** Small entries, and deflated ones when the platform cannot stream, are read whole. */
    private fun inMemory(rec: ZipEntryRecord) =
        rec.uncompressedSize <= tuning.inMemoryBytes || (rec.method == METHOD_DEFLATE && !streaming)

    override fun read(path: String, maxBytes: Long): ByteArray = readWhole(record(path, maxBytes))

    override fun readHead(path: String, count: Int): ByteArray {
        val rec = record(path, Long.MAX_VALUE)
        if (inMemory(rec)) {
            val all = readWhole(rec)
            return if (all.size <= count) all else all.copyOf(count)
        }
        // The whole entry is still checked, so a corrupt file fails here exactly as a full read would.
        val head = ByteArray(minOf(count.toLong(), rec.uncompressedSize).toInt())
        var filled = 0
        stream(rec) { b, off, n ->
            val take = minOf(n, head.size - filled)
            if (take > 0) {
                b.copyInto(head, filled, off, off + take)
                filled += take
            }
        }
        return head
    }

    override fun copyTo(path: String, maxBytes: Long, sink: (ByteArray, Int, Int) -> Unit) {
        val rec = record(path, maxBytes)
        if (inMemory(rec)) {
            val all = readWhole(rec)
            sink(all, 0, all.size)
        } else {
            stream(rec, sink)
        }
    }

    override fun <T> useFile(path: String, maxBytes: Long, block: (RandomAccessInput) -> T): T {
        val rec = record(path, maxBytes)
        if (inMemory(rec)) return block(ByteArrayInput(readWhole(rec)))
        if (rec.method == METHOD_STORED) {
            // Check it once, then read straight from the archive.
            val start = EntryReader(rec).use { reader ->
                val scratch = ByteArray(tuning.chunkBytes)
                while (reader.read(scratch, 0, scratch.size) >= 0) Unit
                reader.dataStart
            }
            return block(SliceInput(input, start, rec.uncompressedSize, rec.name))
        }
        // Deflated: one checking pass keeps the head and the tail, where the header probes look. The middle is
        // inflated again on demand.
        val window = minOf(tuning.windowBytes.toLong(), rec.uncompressedSize / 2).coerceAtLeast(1L).toInt()
        val head = ByteArray(window)
        val ring = ByteArray(window)
        var position = 0L
        stream(rec) { b, off, n ->
            if (position < window) {
                val take = minOf(n.toLong(), window - position).toInt()
                b.copyInto(head, position.toInt(), off, off + take)
            }
            // Only the last `window` bytes of this chunk can end up in the tail.
            val skip = maxOf(0, n - window)
            var p = position + skip
            for (i in off + skip until off + n) ring[(p++ % window).toInt()] = b[i]
            position += n
        }
        // ring holds the last `window` bytes; rotate it so tail[0] is the byte at size - window.
        val shift = (rec.uncompressedSize % window).toInt()
        val tail = if (shift == 0) ring else ring.copyOfRange(shift, window) + ring.copyOfRange(0, shift)
        return InflatedInput(rec, head, tail).use { block(it) }
    }

    /** Reads a whole entry into memory. */
    private fun readWhole(rec: ZipEntryRecord): ByteArray {
        if (rec.method == METHOD_DEFLATE && !streaming) return readWholeOneShot(rec)
        val out = ByteArray(rec.uncompressedSize.toInt())
        EntryReader(rec).use { reader ->
            var n = 0
            while (true) {
                val r = reader.read(out, n, out.size - n)
                if (r < 0) break
                n += r
            }
        }
        return out
    }

    /** A deflated entry through [RawInflater.inflate], for platforms without streaming inflation. */
    private fun readWholeOneShot(rec: ZipEntryRecord): ByteArray {
        val dataStart = dataStart(rec)
        val compressed = ByteArray(rec.compressedSize.toInt())
        try {
            input.readFully(dataStart, compressed)
        } catch (e: Exception) {
            throw unreadable("Data of ${rec.name} is truncated")
        }
        val data = inflater.inflate(compressed, rec.uncompressedSize.toInt()) ?: throw inflateError(rec)
        if (Crc32.of(data) != rec.crc32) throw unreadable("CRC mismatch in ${rec.name}")
        return data
    }

    private fun stream(rec: ZipEntryRecord, sink: (ByteArray, Int, Int) -> Unit) {
        if (rec.method == METHOD_DEFLATE && !streaming) {
            val all = readWholeOneShot(rec)
            sink(all, 0, all.size)
            return
        }
        EntryReader(rec).use { reader ->
            val buffer = ByteArray(tuning.chunkBytes)
            while (true) {
                val n = reader.read(buffer, 0, buffer.size)
                if (n < 0) break
                if (n > 0) sink(buffer, 0, n)
            }
        }
    }

    private fun dataStart(rec: ZipEntryRecord): Long {
        val local = ByteArray(30)
        try {
            input.readFully(rec.localHeaderOffset, local)
        } catch (e: Exception) {
            throw unreadable("Local header of ${rec.name} is out of bounds")
        }
        if (local.u32(0) != LOCAL_SIG) throw unreadable("Bad local header for ${rec.name}")
        return rec.localHeaderOffset + 30 + local.u16(26) + local.u16(28)
    }

    /**
     * The uncompressed bytes of one entry, in order. [read] returns -1 at the end, after checking that a DEFLATE stream
     * ends exactly at the declared size and that the CRC-32 matches. Every failure is a [PackReadException].
     */
    private inner class EntryReader(private val rec: ZipEntryRecord) : AutoCloseable {
        val dataStart: Long = dataStart(rec)
        private var stream: InflateStream? = null
        private var inBuffer = ByteArray(0)
        private var inPosition = dataStart
        private var inLeft = rec.compressedSize
        private var crc = 0L
        private var ended = false

        /** Uncompressed bytes returned so far. */
        var position = 0L
            private set

        init {
            if (dataStart + rec.compressedSize > input.size) throw unreadable("Data of ${rec.name} is truncated")
            when (rec.method) {
                METHOD_STORED -> if (rec.compressedSize != rec.uncompressedSize) {
                    throw unreadable("Stored entry ${rec.name} has inconsistent sizes")
                }
                METHOD_DEFLATE -> {
                    stream = inflater.stream() ?: error("Streaming inflation is not available")
                    inBuffer = ByteArray(minOf(tuning.chunkBytes.toLong(), maxOf(rec.compressedSize, 1L)).toInt())
                }
                else -> throw PackReadException(ErrorCode.ARCHIVE_UNSUPPORTED, "Unsupported compression method ${rec.method}")
            }
        }

        fun read(out: ByteArray, offset: Int, length: Int): Int {
            if (ended) return -1
            val left = rec.uncompressedSize - position
            if (left == 0L) {
                finish()
                return -1
            }
            val want = minOf(length.toLong(), left).toInt()
            if (want == 0) return 0
            val s = stream
            val n = if (s == null) {
                try {
                    input.readFully(dataStart + position, out, offset, want)
                } catch (e: Exception) {
                    throw unreadable("Data of ${rec.name} is truncated")
                }
                want
            } else {
                inflateSome(s, out, offset, want)
            }
            crc = Crc32.update(crc, out, offset, n)
            position += n
            return n
        }

        private fun inflateSome(s: InflateStream, out: ByteArray, offset: Int, want: Int): Int {
            while (true) {
                // Finished before the declared size: the entry is shorter than its header says.
                if (s.finished) throw inflateError(rec)
                if (s.needsInput) {
                    if (!feed(s)) throw inflateError(rec)
                    continue
                }
                val r = s.inflate(out, offset, want)
                if (r < 0) throw inflateError(rec)
                if (r > 0) return r
                if (!s.needsInput && !s.finished) throw inflateError(rec)
            }
        }

        private fun feed(s: InflateStream): Boolean {
            if (inLeft <= 0L) return false
            val n = minOf(inBuffer.size.toLong(), inLeft).toInt()
            try {
                input.readFully(inPosition, inBuffer, 0, n)
            } catch (e: Exception) {
                throw unreadable("Data of ${rec.name} is truncated")
            }
            inPosition += n
            inLeft -= n
            s.setInput(inBuffer, 0, n)
            return true
        }

        private fun finish() {
            ended = true
            stream?.let { s ->
                // The stream must end at the declared size: nothing more may come out of the remaining input.
                val probe = ByteArray(1)
                while (!s.finished) {
                    if (s.inflate(probe, 0, 1) != 0) throw inflateError(rec)
                    if (s.finished || !s.needsInput || !feed(s)) break
                }
            }
            if (crc != rec.crc32) throw unreadable("CRC mismatch in ${rec.name}")
        }

        override fun close() {
            stream?.close()
            stream = null
        }
    }

    /** A stored entry, already checked: its bytes straight from the archive. */
    private class SliceInput(
        private val input: RandomAccessInput,
        private val start: Long,
        override val size: Long,
        private val name: String,
    ) : RandomAccessInput {
        override fun readFully(position: Long, buffer: ByteArray, offset: Int, length: Int) {
            if (position < 0 || length < 0 || position + length > size) throw IndexOutOfBoundsException("read past end of $name")
            try {
                input.readFully(start + position, buffer, offset, length)
            } catch (e: Exception) {
                throw unreadable("Data of $name is truncated")
            }
        }
    }

    /**
     * A large deflated entry, already checked: its first and last bytes from memory, the middle inflated again on
     * demand. Reads that move forward continue the same inflation; a read behind it starts over.
     */
    private inner class InflatedInput(
        private val rec: ZipEntryRecord,
        private val head: ByteArray,
        private val tail: ByteArray,
    ) : RandomAccessInput, AutoCloseable {
        override val size: Long = rec.uncompressedSize
        private val tailStart = size - tail.size
        private var cursor: EntryReader? = null
        private var scratch: ByteArray? = null

        override fun readFully(position: Long, buffer: ByteArray, offset: Int, length: Int) {
            if (position < 0 || length < 0 || position + length > size) throw IndexOutOfBoundsException("read past end of ${rec.name}")
            var p = position
            var o = offset
            var left = length
            while (left > 0) {
                val n = when {
                    p < head.size -> {
                        val n = minOf(left.toLong(), head.size - p).toInt()
                        head.copyInto(buffer, o, p.toInt(), p.toInt() + n)
                        n
                    }
                    p >= tailStart -> {
                        val t = (p - tailStart).toInt()
                        tail.copyInto(buffer, o, t, t + left)
                        left
                    }
                    else -> middle(p, buffer, o, minOf(left.toLong(), tailStart - p).toInt())
                }
                p += n
                o += n
                left -= n
            }
        }

        private fun middle(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            var reader = cursor
            if (reader == null || reader.position > position) {
                reader?.close()
                reader = EntryReader(rec).also { cursor = it }
            }
            val skip = scratch ?: ByteArray(tuning.chunkBytes).also { scratch = it }
            while (reader.position < position) {
                val n = reader.read(skip, 0, minOf(skip.size.toLong(), position - reader.position).toInt())
                if (n < 0) throw unreadable("Data of ${rec.name} ended early")
            }
            val n = reader.read(buffer, offset, length)
            if (n <= 0) throw unreadable("Data of ${rec.name} ended early")
            return n
        }

        override fun close() {
            cursor?.close()
            cursor = null
        }
    }

    companion object {
        const val METHOD_STORED = 0
        const val METHOD_DEFLATE = 8
        private const val LOCAL_SIG = 0x04034b50L
        private const val CENTRAL_SIG = 0x02014b50L
        private const val EOCD_SIG = 0x06054b50L
        private const val ZIP64_LOCATOR_SIG = 0x07064b50L
        private const val ZIP64_EOCD_SIG = 0x06064b50L

        private fun unreadable(message: String) = PackReadException(ErrorCode.ARCHIVE_UNREADABLE, message)

        private fun inflateError(rec: ZipEntryRecord) = unreadable("Cannot inflate ${rec.name} or its size differs from the header")

        /** Opens [input] and runs the archive-stage checks. */
        fun open(input: RandomAccessInput, inflater: RawInflater): ZipOpenResult = open(input, inflater, ZipTuning())

        internal fun open(input: RandomAccessInput, inflater: RawInflater, tuning: ZipTuning): ZipOpenResult {
            val issues = mutableListOf<Issue>()
            fun fail(code: ErrorCode, msg: String) = ZipOpenResult(null, issues + Issue(code, msg))
            try {
                val size = input.size
                if (size < 22) return fail(ErrorCode.ARCHIVE_UNREADABLE, "Too small to be a zip archive")
                val tailLen = minOf(size, 22L + 65_535L).toInt()
                val tail = ByteArray(tailLen)
                input.readFully(size - tailLen, tail)
                var eocd = -1
                for (i in tailLen - 22 downTo 0) {
                    if (tail.u32(i) == EOCD_SIG && i + 22 + tail.u16(i + 20) <= tailLen) { eocd = i; break }
                }
                if (eocd < 0) return fail(ErrorCode.ARCHIVE_UNREADABLE, "No end of central directory record: not a zip archive")
                val diskNumber = tail.u16(eocd + 4)
                val cdDisk = tail.u16(eocd + 6)
                var entriesOnDisk = tail.u16(eocd + 8).toLong()
                var totalEntries = tail.u16(eocd + 10).toLong()
                var cdSize = tail.u32(eocd + 12)
                var cdOffset = tail.u32(eocd + 16)
                val eocdPosition = size - tailLen + eocd
                if (totalEntries == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                    val locatorPos = eocdPosition - 20
                    if (locatorPos < 0) return fail(ErrorCode.ARCHIVE_UNREADABLE, "Missing Zip64 locator")
                    val locator = ByteArray(20)
                    input.readFully(locatorPos, locator)
                    if (locator.u32(0) != ZIP64_LOCATOR_SIG) return fail(ErrorCode.ARCHIVE_UNREADABLE, "Missing Zip64 locator")
                    val z64Pos = locator.u64(8)
                    val z64 = ByteArray(56)
                    input.readFully(z64Pos, z64)
                    if (z64.u32(0) != ZIP64_EOCD_SIG) return fail(ErrorCode.ARCHIVE_UNREADABLE, "Bad Zip64 end record")
                    if (z64.u32(16) != 0L || z64.u32(20) != 0L) return fail(ErrorCode.ARCHIVE_UNSUPPORTED, "Multi-disk archives are not supported")
                    entriesOnDisk = z64.u64(24)
                    totalEntries = z64.u64(32)
                    cdSize = z64.u64(40)
                    cdOffset = z64.u64(48)
                } else if (diskNumber != 0 || cdDisk != 0) {
                    return fail(ErrorCode.ARCHIVE_UNSUPPORTED, "Multi-disk archives are not supported")
                }
                if (entriesOnDisk != totalEntries) return fail(ErrorCode.ARCHIVE_UNSUPPORTED, "Multi-disk archives are not supported")
                if (totalEntries > Limits.ENTRIES_PER_ARCHIVE) {
                    return fail(ErrorCode.TOO_MANY_FILES, "$totalEntries entries; at most ${Limits.ENTRIES_PER_ARCHIVE} are allowed")
                }
                if (cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > size || cdSize > 64L * 1024 * 1024) {
                    return fail(ErrorCode.ARCHIVE_UNREADABLE, "Central directory is out of bounds")
                }
                val cd = ByteArray(cdSize.toInt())
                input.readFully(cdOffset, cd)
                val records = ArrayList<ZipEntryRecord>(totalEntries.toInt())
                var p = 0
                repeat(totalEntries.toInt()) {
                    if (p + 46 > cd.size || cd.u32(p) != CENTRAL_SIG) {
                        return fail(ErrorCode.ARCHIVE_UNREADABLE, "Corrupt central directory")
                    }
                    val madeBy = cd.u16(p + 4)
                    val flags = cd.u16(p + 8)
                    val method = cd.u16(p + 10)
                    val crc = cd.u32(p + 16)
                    var csize = cd.u32(p + 20)
                    var usize = cd.u32(p + 24)
                    val nameLen = cd.u16(p + 28)
                    val extraLen = cd.u16(p + 30)
                    val commentLen = cd.u16(p + 32)
                    val externalAttrs = cd.u32(p + 38)
                    var offset = cd.u32(p + 42)
                    val end = p + 46 + nameLen + extraLen + commentLen
                    if (end > cd.size) return fail(ErrorCode.ARCHIVE_UNREADABLE, "Corrupt central directory")
                    val nameBytes = cd.copyOfRange(p + 46, p + 46 + nameLen)
                    val name = if (flags and 0x0800 != 0) nameBytes.decodeToString() else latin1(nameBytes)
                    // Zip64 extended information extra field.
                    var e = p + 46 + nameLen
                    val extraEnd = e + extraLen
                    while (e + 4 <= extraEnd) {
                        val id = cd.u16(e)
                        val len = cd.u16(e + 2)
                        if (id == 0x0001) {
                            var q = e + 4
                            if (usize == 0xFFFFFFFFL && q + 8 <= e + 4 + len) { usize = cd.u64(q); q += 8 }
                            if (csize == 0xFFFFFFFFL && q + 8 <= e + 4 + len) { csize = cd.u64(q); q += 8 }
                            if (offset == 0xFFFFFFFFL && q + 8 <= e + 4 + len) { offset = cd.u64(q) }
                        }
                        e += 4 + len
                    }
                    val host = madeBy ushr 8
                    val unixType = (externalAttrs ushr 16) and 0xF000L
                    val kind = when {
                        host == 3 && unixType == 0xA000L -> EntryKind.SYMLINK
                        name.endsWith("/") -> EntryKind.DIRECTORY
                        host == 3 && unixType == 0x4000L -> EntryKind.DIRECTORY
                        host == 3 && unixType != 0L && unixType != 0x8000L -> EntryKind.OTHER
                        else -> EntryKind.FILE
                    }
                    if (flags and 0x0001 != 0 || flags and 0x0040 != 0) {
                        issues += Issue(ErrorCode.ARCHIVE_UNSUPPORTED, "Encrypted entries are not supported", file = name)
                    } else if (kind == EntryKind.FILE && method != METHOD_STORED && method != METHOD_DEFLATE) {
                        issues += Issue(ErrorCode.ARCHIVE_UNSUPPORTED, "Compression method $method is not supported (use stored or deflate)", file = name)
                    }
                    if (offset < 0 || offset + 30 > cdOffset || csize < 0 || usize < 0) {
                        return fail(ErrorCode.ARCHIVE_UNREADABLE, "Entry $name points outside the archive")
                    }
                    if (usize > Limits.RATIO_CHECK_THRESHOLD_BYTES && usize > maxOf(csize, 1L) * Limits.MAX_COMPRESSION_RATIO) {
                        issues += Issue(
                            ErrorCode.COMPRESSION_RATIO_EXCEEDED,
                            "Expands from $csize to $usize bytes (more than ${Limits.MAX_COMPRESSION_RATIO}:1)",
                            file = name,
                        )
                    }
                    records += ZipEntryRecord(name, kind, method, flags, crc, csize, usize, offset)
                    p = end
                }
                val total = records.sumOf { it.uncompressedSize }
                if (total > Limits.RATIO_CHECK_THRESHOLD_BYTES && total > size * Limits.MAX_COMPRESSION_RATIO &&
                    issues.none { it.code == ErrorCode.COMPRESSION_RATIO_EXCEEDED }
                ) {
                    issues += Issue(ErrorCode.COMPRESSION_RATIO_EXCEEDED, "Archive expands from $size to $total bytes")
                }
                val archive = if (issues.isEmpty()) ZipArchive(input, inflater, records, tuning) else null
                return ZipOpenResult(archive, issues)
            } catch (e: PackReadException) {
                return fail(e.code, e.message ?: "Unreadable archive")
            } catch (e: Exception) {
                return fail(ErrorCode.ARCHIVE_UNREADABLE, "Unreadable archive: ${e.message}")
            }
        }

        private fun latin1(bytes: ByteArray): String = buildString(bytes.size) { bytes.forEach { append((it.toInt() and 0xFF).toChar()) } }
    }
}

internal fun ByteArray.u16(i: Int): Int = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
internal fun ByteArray.u32(i: Int): Long = (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)
internal fun ByteArray.u64(i: Int): Long = u32(i) or (u32(i + 4) shl 32)

/** CRC-32 (IEEE 802.3), as used by zip. */
object Crc32 {
    private val table = LongArray(256) { n ->
        var c = n.toLong()
        repeat(8) { c = if (c and 1L != 0L) 0xEDB88320L xor (c ushr 1) else c ushr 1 }
        c
    }

    fun of(data: ByteArray, offset: Int = 0, length: Int = data.size): Long = update(0L, data, offset, length)

    /** Continues the CRC-32 [crc] (0 for no data yet) with [length] more bytes. */
    fun update(crc: Long, data: ByteArray, offset: Int = 0, length: Int = data.size): Long {
        var c = crc xor 0xFFFFFFFFL
        for (i in offset until offset + length) c = table[((c xor data[i].toLong()) and 0xFF).toInt()] xor (c ushr 8)
        return c xor 0xFFFFFFFFL
    }
}
