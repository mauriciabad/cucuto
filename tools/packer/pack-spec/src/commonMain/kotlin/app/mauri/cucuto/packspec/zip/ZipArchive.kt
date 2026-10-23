package app.mauri.cucuto.packspec.zip

import app.mauri.cucuto.packspec.EntryKind
import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.Issue
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.PackReadException
import app.mauri.cucuto.packspec.PackSource
import app.mauri.cucuto.packspec.SourceEntry

/** Random access to the bytes of an archive. */
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
 * Minimal zip reader for pack archives: parses the central directory itself so it can see encryption flags, Unix
 * file types and declared sizes before inflating anything. Only stored (0) and deflate (8) entries are readable.
 */
class ZipArchive private constructor(
    private val input: RandomAccessInput,
    private val inflater: RawInflater,
    val records: List<ZipEntryRecord>,
) : PackSource {

    private val byName = records.associateBy { it.name }

    override val entries: List<SourceEntry> = records.map { SourceEntry(it.name, it.uncompressedSize, it.kind) }

    override fun read(path: String, maxBytes: Long): ByteArray {
        val rec = byName[path] ?: throw PackReadException(ErrorCode.FILE_MISSING, "No such entry: $path")
        if (rec.uncompressedSize > maxBytes) {
            throw PackReadException(ErrorCode.FILE_TOO_LARGE, "$path is larger than $maxBytes bytes")
        }
        if (rec.uncompressedSize > Int.MAX_VALUE - 16 || rec.compressedSize > Int.MAX_VALUE - 16) {
            throw PackReadException(ErrorCode.FILE_TOO_LARGE, "$path is too large to read")
        }
        val local = ByteArray(30)
        try {
            input.readFully(rec.localHeaderOffset, local)
        } catch (e: Exception) {
            throw PackReadException(ErrorCode.ARCHIVE_UNREADABLE, "Local header of $path is out of bounds")
        }
        if (local.u32(0) != LOCAL_SIG) throw PackReadException(ErrorCode.ARCHIVE_UNREADABLE, "Bad local header for $path")
        val dataStart = rec.localHeaderOffset + 30 + local.u16(26) + local.u16(28)
        val compressed = ByteArray(rec.compressedSize.toInt())
        try {
            input.readFully(dataStart, compressed)
        } catch (e: Exception) {
            throw PackReadException(ErrorCode.ARCHIVE_UNREADABLE, "Data of $path is truncated")
        }
        val data = when (rec.method) {
            METHOD_STORED -> {
                if (rec.compressedSize != rec.uncompressedSize) {
                    throw PackReadException(ErrorCode.ARCHIVE_UNREADABLE, "Stored entry $path has inconsistent sizes")
                }
                compressed
            }
            METHOD_DEFLATE -> inflater.inflate(compressed, rec.uncompressedSize.toInt())
                ?: throw PackReadException(ErrorCode.ARCHIVE_UNREADABLE, "Cannot inflate $path or its size differs from the header")
            else -> throw PackReadException(ErrorCode.ARCHIVE_UNSUPPORTED, "Unsupported compression method ${rec.method}")
        }
        if (Crc32.of(data) != rec.crc32) throw PackReadException(ErrorCode.ARCHIVE_UNREADABLE, "CRC mismatch in $path")
        return data
    }

    companion object {
        const val METHOD_STORED = 0
        const val METHOD_DEFLATE = 8
        private const val LOCAL_SIG = 0x04034b50L
        private const val CENTRAL_SIG = 0x02014b50L
        private const val EOCD_SIG = 0x06054b50L
        private const val ZIP64_LOCATOR_SIG = 0x07064b50L
        private const val ZIP64_EOCD_SIG = 0x06064b50L

        /** Opens [input] and runs the archive-stage checks. */
        fun open(input: RandomAccessInput, inflater: RawInflater): ZipOpenResult {
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
                val archive = if (issues.isEmpty()) ZipArchive(input, inflater, records) else null
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

    fun of(data: ByteArray, offset: Int = 0, length: Int = data.size): Long {
        var c = 0xFFFFFFFFL
        for (i in offset until offset + length) c = table[((c xor data[i].toLong()) and 0xFF).toInt()] xor (c ushr 8)
        return c xor 0xFFFFFFFFL
    }
}
