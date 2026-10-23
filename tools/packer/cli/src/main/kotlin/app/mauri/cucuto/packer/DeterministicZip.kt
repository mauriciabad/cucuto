package app.mauri.cucuto.packer

import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.FileKind
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Writes reproducible zip archives: the manifest first, then all other entries sorted by path; fixed timestamps
 * (1980-01-01 00:00, the DOS epoch); fixed Unix permissions (0644); UTF-8 names; no extra fields, comments or data
 * descriptors. Text, manifests, SVG and WAV are deflated (level 9) when that saves space; already compressed media is
 * stored. The same inputs and packer version always produce the same bytes.
 */
object DeterministicZip {
    private const val DOS_DATE_1980_01_01 = (0 shl 9) or (1 shl 5) or 1
    private const val UNIX_FILE_0644 = 0x81A4L
    private const val VERSION_MADE_BY_UNIX = (3 shl 8) or 20
    private const val VERSION_NEEDED = 20
    private const val FLAG_UTF8 = 0x0800

    private val MANIFESTS = listOf("pack.json", "bundle.json")

    fun orderedNames(names: Collection<String>): List<String> {
        val manifests = MANIFESTS.filter { it in names }
        // Valid names are ASCII, so String order is byte order.
        return manifests + names.filter { it !in manifests }.sorted()
    }

    private fun shouldDeflate(name: String): Boolean {
        val f = FileFormat.of(name) ?: return true
        return f.kind == FileKind.TEXT || f.kind == FileKind.MANIFEST || f == FileFormat.SVG || f == FileFormat.WAV
    }

    /** Writes [pack] to [target] atomically (through a temporary file in the same folder). */
    fun write(pack: AssembledPack, target: Path) {
        val parent = target.toAbsolutePath().parent
        Files.createDirectories(parent)
        val tmp = Files.createTempFile(parent, ".packer-", ".tmp")
        try {
            Files.newOutputStream(tmp).buffered().use { write(pack, it) }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun write(pack: AssembledPack, out: OutputStream) {
        val central = ByteArrayOutputStream()
        var offset = 0L
        val names = orderedNames(pack.files.keys)
        for (name in names) {
            val data = pack.files.getValue(name).bytes()
            val crc = CRC32().apply { update(data) }.value
            var method = 0
            var body = data
            if (shouldDeflate(name) && data.isNotEmpty()) {
                val deflated = deflate(data)
                if (deflated.size < data.size) { method = 8; body = deflated }
            }
            val nameBytes = name.encodeToByteArray()
            require(offset <= 0xFFFFFFFFL && data.size.toLong() <= 0xFFFFFFFFL) { "Archives over 4 GiB are not supported" }
            val local = ByteArrayOutputStream()
            local.le(0x04034b50, 4); local.le(VERSION_NEEDED.toLong(), 2); local.le(FLAG_UTF8.toLong(), 2); local.le(method.toLong(), 2)
            local.le(0, 2); local.le(DOS_DATE_1980_01_01.toLong(), 2); local.le(crc, 4)
            local.le(body.size.toLong(), 4); local.le(data.size.toLong(), 4); local.le(nameBytes.size.toLong(), 2); local.le(0, 2)
            local.write(nameBytes)
            local.writeTo(out)
            out.write(body)

            central.le(0x02014b50, 4); central.le(VERSION_MADE_BY_UNIX.toLong(), 2); central.le(VERSION_NEEDED.toLong(), 2)
            central.le(FLAG_UTF8.toLong(), 2); central.le(method.toLong(), 2); central.le(0, 2); central.le(DOS_DATE_1980_01_01.toLong(), 2)
            central.le(crc, 4); central.le(body.size.toLong(), 4); central.le(data.size.toLong(), 4)
            central.le(nameBytes.size.toLong(), 2); central.le(0, 2); central.le(0, 2); central.le(0, 2); central.le(0, 2)
            central.le(UNIX_FILE_0644 shl 16, 4); central.le(offset, 4); central.write(nameBytes)

            offset += local.size() + body.size
        }
        val cd = central.toByteArray()
        require(names.size <= 0xFFFF) { "Too many entries" }
        out.write(cd)
        val end = ByteArrayOutputStream()
        end.le(0x06054b50, 4); end.le(0, 2); end.le(0, 2); end.le(names.size.toLong(), 2); end.le(names.size.toLong(), 2)
        end.le(cd.size.toLong(), 4); end.le(offset, 4); end.le(0, 2)
        end.writeTo(out)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(9, true)
        try {
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buf = ByteArray(64 * 1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    private fun ByteArrayOutputStream.le(value: Long, bytes: Int) = repeat(bytes) { write(((value ushr (8 * it)) and 0xFF).toInt()) }
}
