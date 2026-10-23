package app.mauri.cucuto.core.backup

import app.mauri.cucuto.packspec.EntryKind
import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.FileInput
import app.mauri.cucuto.packspec.Issue
import app.mauri.cucuto.packspec.JvmPackIo
import app.mauri.cucuto.packspec.PackReadException
import app.mauri.cucuto.packspec.Severity
import app.mauri.cucuto.packspec.ValidationReport
import app.mauri.cucuto.packspec.zip.ZipArchive
import app.mauri.cucuto.packspec.zip.ZipEntryRecord
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** What a zip holds, decided by the manifest at its root (docs/decisions/0007-zip-file-names.md). */
enum class ZipContentType {
    /** `pack.json`: a sound pack, for the pack importer. */
    PACK,

    /** `bundle.json`: a bundle of packs, for the pack importer. */
    BUNDLE,

    /** `backup.json`: a plain backup. */
    BACKUP,

    /** `envelope.json` and `payload.bin`: a passphrase-encrypted backup. */
    ENCRYPTED_BACKUP,
}

/** Result of [ZipContents.sniff]: the type, or the archive and layout issues that make the file unusable. */
data class SniffResult(
    val type: ZipContentType?,
    val report: ValidationReport,
)

/** Tells packs, bundles and backups apart by their contents, for "Open with" and the share sheet. */
object ZipContentSniffer {
    /** Decides what [file] holds from the manifest at its root. The file name does not matter. */
    fun sniff(file: File): SniffResult {
        val (zip, issues) = ZipContents.open(file)
        if (zip == null) return SniffResult(null, ValidationReport(issues))
        return zip.use {
            val (type, all) = it.type(issues)
            SniffResult(type, ValidationReport(all))
        }
    }
}

/**
 * A zip opened for reading backups. It reuses pack-spec's central directory parser (encrypted entries, unsupported
 * methods, Zip64, compression-ratio and entry-count caps), reads small entries fully with their CRC checked, and
 * streams large ones ([open]) with the CRC and size checked at the end. Entries are only ever looked up by name;
 * nothing is extracted by its own path, so hostile names cannot escape anywhere.
 */
internal class ZipContents private constructor(
    private val file: File,
    private val input: FileInput,
    private val archive: ZipArchive,
) : AutoCloseable {
    val names: Set<String> = archive.records.map { it.name }.toSet()

    fun record(name: String): ZipEntryRecord? =
        archive.records.firstOrNull {
            it.name == name &&
                it.kind == EntryKind.FILE
        }

    /** Reads a whole entry (manifests). Throws [PackReadException] when it is missing, too large or corrupt. */
    fun read(
        name: String,
        maxBytes: Long,
    ): ByteArray = archive.read(name, maxBytes)

    /** Streams an entry. The stream throws [IOException] at its end if the size or CRC-32 does not match. */
    fun open(name: String): InputStream {
        val record = record(name) ?: throw PackReadException(ErrorCode.FILE_MISSING, "No such entry: $name")
        val raf = RandomAccessFile(file, "r")
        try {
            val header = ByteArray(LOCAL_HEADER_BYTES)
            raf.seek(record.localHeaderOffset)
            raf.readFully(header)
            if (u32(header, 0) != LOCAL_SIGNATURE) throw IOException("Bad local header for $name")
            val start = record.localHeaderOffset + LOCAL_HEADER_BYTES + u16(header, 26) + u16(header, 28)
            if (start + record.compressedSize > raf.length()) throw IOException("Data of $name is truncated")
            raf.seek(start)
            val raw = BoundedInputStream(Channels.newInputStream(raf.channel), record.compressedSize)
            val data =
                when (record.method) {
                    ZipArchive.METHOD_STORED -> raw
                    ZipArchive.METHOD_DEFLATE -> InflaterInputStream(raw, Inflater(true), BUFFER_BYTES)
                    else -> throw IOException("Unsupported compression method ${record.method}")
                }
            return CheckedEntryStream(data, record, raf)
        } catch (e: Exception) {
            raf.close()
            throw e
        }
    }

    override fun close() = input.close()

    companion object {
        /** Opens [file] and checks the archive stage. Returns the issues instead when the archive is unusable. */
        fun open(file: File): Pair<ZipContents?, List<Issue>> {
            val input =
                try {
                    FileInput(file.toPath())
                } catch (e: IOException) {
                    return null to listOf(Issue(ErrorCode.ARCHIVE_UNREADABLE, "Cannot open the file: ${e.message}"))
                }
            val opened = ZipArchive.open(input, JvmPackIo.inflater)
            val archive = opened.source
            if (archive == null) {
                input.close()
                return null to opened.issues
            }
            return ZipContents(file, input, archive) to opened.issues
        }

        private const val LOCAL_SIGNATURE = 0x04034b50L
        private const val LOCAL_HEADER_BYTES = 30
        private const val BUFFER_BYTES = 64 * 1024

        private fun u16(
            b: ByteArray,
            i: Int,
        ): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

        private fun u32(
            b: ByteArray,
            i: Int,
        ): Long = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)
    }

    /** The content type and the layout issues found while deciding it. */
    fun type(archiveIssues: List<Issue> = emptyList()): Pair<ZipContentType?, List<Issue>> {
        val issues = archiveIssues.toMutableList()
        val counts = archive.records.groupingBy { it.name }.eachCount()
        for (name in MANIFESTS + BackupEnvelope.PAYLOAD) {
            if ((counts[name] ?: 0) >
                1
            ) {
                issues += Issue(ErrorCode.DUPLICATE_ENTRY, "$name appears more than once", file = name)
            }
        }
        val manifests = MANIFESTS.filter { record(it) != null }
        val type =
            when {
                manifests.size > 1 -> {
                    issues +=
                        Issue(
                            ErrorCode.MANIFEST_AMBIGUOUS,
                            "More than one manifest at the root: ${manifests.joinToString()}",
                        )
                    null
                }

                manifests.isEmpty() -> {
                    val message = "No pack.json, bundle.json, backup.json or envelope.json at the root"
                    issues += Issue(ErrorCode.MANIFEST_MISSING, message)
                    null
                }

                else -> {
                    when (manifests.single()) {
                        PACK -> ZipContentType.PACK
                        BUNDLE -> ZipContentType.BUNDLE
                        BACKUP -> ZipContentType.BACKUP
                        else -> ZipContentType.ENCRYPTED_BACKUP
                    }
                }
            }
        if (type == ZipContentType.ENCRYPTED_BACKUP && record(BackupEnvelope.PAYLOAD) == null) {
            issues += Issue(ErrorCode.FILE_MISSING, "envelope.json without payload.bin", file = BackupEnvelope.PAYLOAD)
        }
        val failed = issues.any { it.code.severity == Severity.ERROR }
        return (if (failed) null else type) to issues
    }

    /** Reads at most [remaining] bytes from the underlying stream. */
    private class BoundedInputStream(
        input: InputStream,
        private var remaining: Long,
    ) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            return super.read().also { if (it >= 0) remaining-- }
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (remaining <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun close() {
            // The underlying file is closed by CheckedEntryStream.
        }
    }

    /** Counts and checksums the uncompressed bytes; at the end, both must match the central directory. */
    private class CheckedEntryStream(
        input: InputStream,
        private val record: ZipEntryRecord,
        private val file: RandomAccessFile,
    ) : FilterInputStream(input) {
        private val crc = CRC32()
        private var count = 0L
        private var checked = false

        override fun read(): Int {
            val b = super.read()
            if (b < 0) verify() else update(byteArrayOf(b.toByte()), 0, 1)
            return b
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val n = super.read(b, off, len)
            if (n < 0) verify() else update(b, off, n)
            return n
        }

        private fun update(
            b: ByteArray,
            off: Int,
            n: Int,
        ) {
            crc.update(b, off, n)
            count += n
            if (count > record.uncompressedSize) throw IOException("${record.name} is larger than its header says")
        }

        private fun verify() {
            if (checked) return
            checked = true
            if (count != record.uncompressedSize || crc.value != record.crc32) {
                throw IOException("${record.name} is corrupt (size or CRC mismatch)")
            }
        }

        override fun close() {
            try {
                super.close()
            } finally {
                file.close()
            }
        }
    }
}

internal const val PACK = "pack.json"
internal const val BUNDLE = "bundle.json"
internal const val BACKUP = "backup.json"
internal const val ENVELOPE = "envelope.json"
private val MANIFESTS = listOf(PACK, BUNDLE, BACKUP, ENVELOPE)
