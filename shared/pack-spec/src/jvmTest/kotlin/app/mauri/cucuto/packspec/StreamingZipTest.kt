package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.zip.RawInflater
import app.mauri.cucuto.packspec.zip.ZipArchive
import app.mauri.cucuto.packspec.zip.ZipTuning
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream
import kotlin.io.path.readText

/**
 * Streaming reads of zip entries (#84) must not change any result. Every golden package fixture is validated as a
 * zip, stored and deflated, read whole, streamed in tiny chunks and through the one-shot inflater fallback, and
 * must report the same codes and durations each time. Large synthetic entries prove that memory stays bounded: the
 * test JVM runs with a 64 MiB heap (build.gradle.kts), less than a single one of those entries.
 */
class StreamingZipTest {
    private val specDir = Path.of(System.getProperty("cucuto.specDir"))
    private val fixturesDir = specDir.resolve("fixtures")

    @TempDir
    lateinit var tmp: Path

    /** Forces every path through streaming, with chunks and windows far smaller than any entry. */
    private val tiny = ZipTuning(inMemoryBytes = 0, windowBytes = 5, chunkBytes = 7)

    /** An inflater without streaming, like a platform that can only inflate in one piece. */
    private val oneShot = RawInflater { compressed, expectedSize -> JvmPackIo.inflater.inflate(compressed, expectedSize) }

    private fun validateZip(zip: Path, inflater: RawInflater = JvmPackIo.inflater, tuning: ZipTuning = ZipTuning()): PackValidation =
        FileInput(zip).use { input ->
            val opened = ZipArchive.open(input, inflater, tuning)
            val archive = opened.source
            if (archive == null || opened.issues.any { it.severity == Severity.ERROR }) {
                PackValidation(ValidationReport(opened.issues), null, emptyMap())
            } else {
                PackValidator().validate(archive, opened.issues)
            }
        }

    @TestFactory
    fun `golden fixtures report the same codes however entries are read`(): List<DynamicTest> {
        val manifest = Json.parseToJsonElement(fixturesDir.resolve("fixtures.json").readText()).jsonObject
        return manifest["fixtures"]!!.jsonArray.map { it.jsonObject }
            .filter { it["kind"]!!.jsonPrimitive.content == "package" }
            .map { f ->
                val path = f["path"]!!.jsonPrimitive.content
                DynamicTest.dynamicTest(path) {
                    val errors = f["errors"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted()
                    val warnings = f["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted()
                    val source = fixturesDir.resolve(path)
                    val reference = JvmPackIo.validatePath(source)
                    val zips = if (source.isDirectory()) {
                        listOf(zipFolder(source, stored = true), zipFolder(source, stored = false))
                    } else {
                        listOf(source)
                    }
                    for (zip in zips) {
                        for ((name, run) in listOf<Pair<String, () -> PackValidation>>(
                            "default" to { validateZip(zip) },
                            "tiny chunks" to { validateZip(zip, tuning = tiny) },
                            "one-shot inflater" to { validateZip(zip, oneShot, tiny) },
                        )) {
                            val v = run()
                            val details = "$path, ${zip.fileName}, $name:\n${v.report.issues.joinToString("\n")}"
                            assertEquals(errors, v.report.errorCodes(), details)
                            assertEquals(warnings, v.report.warningCodes(), details)
                            assertEquals(reference.durationsMs, v.durationsMs, details)
                        }
                    }
                }
            }
    }

    @Test
    fun `large entries are validated without holding them in memory`() {
        val ring = wav(seconds = 220) // 220 s at 384,000 B/s: 84.5 MB, more than the test heap
        val song = ogg(seconds = 120, bodyBytes = 24L * Limits.MiB)
        for (stored in listOf(true, false)) {
            val zip = largePack("large-${if (stored) "stored" else "deflated"}.zip", stored, ring, song)
            val v = JvmPackIo.validatePath(zip)
            assertEquals(emptyList<String>(), v.report.errorCodes(), v.report.issues.joinToString("\n"))
            assertEquals(220_000L, v.durationsMs["characters/beep/ring.wav"])
            assertEquals(120_000L, v.durationsMs["characters/beep/song.ogg"])

            // One flipped byte in the middle of the big entry: the same error as reading it whole.
            val corrupt = tmp.resolve("corrupt-${zip.fileName}")
            Files.copy(zip, corrupt)
            val record = FileInput(corrupt).use { ZipArchive.open(it, JvmPackIo.inflater).source!!.records.single { r -> r.name.endsWith("ring.wav") } }
            RandomAccessFile(corrupt.toFile(), "rw").use { f ->
                val at = record.localHeaderOffset + 30 + record.name.length + record.compressedSize / 2
                f.seek(at)
                val b = f.read()
                f.seek(at)
                f.write(b xor 0x55)
            }
            val bad = JvmPackIo.validatePath(corrupt)
            assertEquals(listOf("ARCHIVE_UNREADABLE"), bad.report.errorCodes(), bad.report.issues.joinToString("\n"))
        }
    }

    @Test
    fun `random reads of a large entry match its bytes`() {
        val size = 6 * Limits.MiB.toInt() + 12_345
        val data = ByteArray(size).also { Noise(7).fill(it, 0, it.size) }
        val payload = Payload(header = data, bodyBytes = 0)
        for (stored in listOf(true, false)) {
            val zip = tmp.resolve("random-$stored.zip")
            writeZip(zip, listOf(Triple("a.bin", payload, stored)))
            FileInput(zip).use { input ->
                val archive = ZipArchive.open(input, JvmPackIo.inflater).source!!
                archive.useFile("a.bin") { file ->
                    assertEquals(size.toLong(), file.size)
                    val window = Limits.MiB.toInt()
                    // Head, forward through the middle, back again (starts over), across both window edges, tail.
                    val reads = listOf(
                        0 to 100, window - 10 to 20, 3 * window to 4096, 5 * window + 1 to 70_000, 2 * window to 10,
                        size - window - 50 to 100, size - 3 to 3, 0 to size,
                    )
                    for ((at, length) in reads) {
                        val out = ByteArray(length)
                        file.readFully(at.toLong(), out, 0, length)
                        assertArrayEquals(data.copyOfRange(at, at + length), out, "$length bytes at $at (stored=$stored)")
                    }
                    assertThrows<IndexOutOfBoundsException> { file.readFully(size - 1L, ByteArray(2), 0, 2) }
                }
                val copied = CRC32()
                var copiedBytes = 0L
                archive.copyTo("a.bin") { b, off, n -> copied.update(b, off, n); copiedBytes += n }
                assertEquals(size.toLong(), copiedBytes)
                assertEquals(CRC32().also { it.update(data) }.value, copied.value)
            }
        }
    }

    @Test
    fun `an entry read again after its check has the same bytes`() {
        // The second and later reads skip the CRC-32 and useFile's checking pass (#241); the bytes must not change.
        val data = ByteArray(6 * Limits.MiB.toInt() + 321).also { Noise(11).fill(it, 0, it.size) }
        val small = data.copyOf(10_000)
        for (stored in listOf(true, false)) {
            for (tuning in listOf(ZipTuning(), tiny)) {
                val zip = tmp.resolve("again-$stored-${tuning.chunkBytes}.zip")
                writeZip(zip, listOf(Triple("large.bin", Payload(data, 0), stored), Triple("small.bin", Payload(small, 0), stored)))
                FileInput(zip).use { input ->
                    val archive = ZipArchive.open(input, JvmPackIo.inflater, tuning).source!!
                    for ((name, bytes) in listOf("large.bin" to data, "small.bin" to small)) {
                        assertArrayEquals(bytes.copyOf(8), archive.readHead(name, 8), name)
                        repeat(2) {
                            assertArrayEquals(bytes, archive.useFile(name) { f -> ByteArray(f.size.toInt()).also { f.readFully(0, it) } }, name)
                            val copied = java.io.ByteArrayOutputStream()
                            archive.copyTo(name) { b, off, n -> copied.write(b, off, n) }
                            assertArrayEquals(bytes, copied.toByteArray(), name)
                            assertArrayEquals(bytes.copyOf(8), archive.readHead(name, 8), name)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a wrong CRC fails a streamed copy after the data`() {
        val payload = Payload(header = ByteArray(0), bodyBytes = 6L * Limits.MiB)
        for (stored in listOf(true, false)) {
            val zip = tmp.resolve("crc-$stored.zip")
            writeZip(zip, listOf(Triple("a.bin", payload, stored)))
            corruptCentralCrc(zip)
            FileInput(zip).use { input ->
                val archive = ZipArchive.open(input, JvmPackIo.inflater).source!!
                var received = 0L
                val e = assertThrows<PackReadException> { archive.copyTo("a.bin") { _, _, n -> received += n } }
                assertEquals(ErrorCode.ARCHIVE_UNREADABLE, e.code)
                assertEquals(payload.size, received)
                assertEquals(ErrorCode.ARCHIVE_UNREADABLE, assertThrows<PackReadException> { archive.useFile("a.bin") { } }.code)
                assertEquals(ErrorCode.ARCHIVE_UNREADABLE, assertThrows<PackReadException> { archive.readHead("a.bin", 4) }.code)
            }
        }
    }

    // ------------------------------------------------------------------ synthetic files

    /** Incompressible bytes (no 'O', so no accidental Ogg page header), the same for the same seed. */
    private class Noise(seed: Long) {
        private var x = seed xor 0x5DEECE66DL

        fun fill(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) {
                x = x xor (x shl 13)
                x = x xor (x ushr 7)
                x = x xor (x shl 17)
                val v = (x ushr 24).toByte()
                b[i] = if (v == 'O'.code.toByte()) 0 else v
            }
        }
    }

    /** [header], then [bodyBytes] of noise, then [trailer]. */
    private class Payload(val header: ByteArray, val bodyBytes: Long, val trailer: ByteArray = ByteArray(0), val seed: Long = 1) {
        val size: Long get() = header.size + bodyBytes + trailer.size

        fun writeTo(out: OutputStream) {
            out.write(header)
            val noise = Noise(seed)
            val buffer = ByteArray(1 shl 20)
            var left = bodyBytes
            while (left > 0) {
                val n = minOf(left, buffer.size.toLong()).toInt()
                noise.fill(buffer, 0, n)
                out.write(buffer, 0, n)
                left -= n
            }
            out.write(trailer)
        }

        fun crc(): Long {
            val crc = CRC32()
            writeTo(object : OutputStream() {
                override fun write(b: Int) = crc.update(b)
                override fun write(b: ByteArray, off: Int, len: Int) = crc.update(b, off, len)
            })
            return crc.value
        }
    }

    private fun le(value: Long, bytes: Int) = ByteArray(bytes) { (value ushr (8 * it)).toByte() }

    /** PCM WAV, 96 kHz stereo 16-bit (384,000 bytes per second). */
    private fun wav(seconds: Int): Payload {
        val dataBytes = 384_000L * seconds
        val header = "RIFF".toByteArray() + le(36 + dataBytes, 4) + "WAVEfmt ".toByteArray() + le(16, 4) + le(1, 2) + le(2, 2) +
            le(96_000, 4) + le(384_000, 4) + le(4, 2) + le(16, 2) + "data".toByteArray() + le(dataBytes, 4)
        return Payload(header, dataBytes, seed = 2)
    }

    /** An Ogg Opus stream: its first page, noise, and a last page whose granule position gives the duration. */
    private fun ogg(seconds: Int, bodyBytes: Long): Payload {
        val serial = le(0x1234_5678, 4)
        val preSkip = 312L
        fun page(type: Int, granule: Long, sequence: Long, payload: ByteArray) =
            "OggS".toByteArray() + byteArrayOf(0, type.toByte()) + le(granule, 8) + serial + le(sequence, 4) + le(0, 4) +
                byteArrayOf(1, payload.size.toByte()) + payload
        val opusHead = "OpusHead".toByteArray() + byteArrayOf(1, 1) + le(preSkip, 2) + le(48_000, 4) + le(0, 2) + byteArrayOf(0)
        return Payload(page(2, 0, 0, opusHead), bodyBytes, page(4, preSkip + 48_000L * seconds, 2, byteArrayOf(0)), seed = 3)
    }

    private fun largePack(name: String, stored: Boolean, ring: Payload, song: Payload): Path {
        val minimal = fixturesDir.resolve("valid/minimal-pack")
        val manifest = Files.readString(minimal.resolve("pack.json")).replace(
            "\"clips\": [{ \"file\": \"characters/beep/clips/beep.ogg\" }]",
            "\"clips\": [{ \"file\": \"characters/beep/clips/beep.ogg\" }],\n      " +
                "\"ringtones\": [{ \"file\": \"characters/beep/ring.wav\" }, { \"file\": \"characters/beep/song.ogg\" }]",
        )
        check("ringtones" in manifest) { "valid/minimal-pack/pack.json changed shape" }
        fun file(rel: String) = Payload(Files.readAllBytes(minimal.resolve(rel)), 0)
        val zip = tmp.resolve(name)
        writeZip(
            zip,
            listOf(
                Triple("pack.json", Payload(manifest.toByteArray(), 0), stored),
                Triple("characters/beep/sprite.png", file("characters/beep/sprite.png"), stored),
                Triple("characters/beep/clips/beep.ogg", file("characters/beep/clips/beep.ogg"), stored),
                Triple("characters/beep/ring.wav", ring, stored),
                Triple("characters/beep/song.ogg", song, stored),
            ),
        )
        return zip
    }

    /** Flips a bit of the CRC-32 in the (single) central directory record, which is the one readers check. */
    private fun corruptCentralCrc(zip: Path) {
        RandomAccessFile(zip.toFile(), "rw").use { f ->
            val tail = ByteArray(minOf(f.length(), 4096L).toInt())
            f.seek(f.length() - tail.size)
            f.readFully(tail)
            val at = (0..tail.size - 4).single { tail[it] == 0x50.toByte() && tail[it + 1] == 0x4B.toByte() && tail[it + 2] == 1.toByte() && tail[it + 3] == 2.toByte() }
            val position = f.length() - tail.size + at + 16
            f.seek(position)
            val b = f.read()
            f.seek(position)
            f.write(b xor 1)
        }
    }

    /** Writes a zip without ever holding an entry in memory. */
    private fun writeZip(target: Path, entries: List<Triple<String, Payload, Boolean>>) {
        ZipOutputStream(target.outputStream().buffered(1 shl 20)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            for ((name, payload, stored) in entries) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = payload.size
                    entry.compressedSize = payload.size
                    entry.crc = payload.crc()
                }
                zip.putNextEntry(entry)
                payload.writeTo(zip)
                zip.closeEntry()
            }
        }
    }

    /** Zips a fixture folder (every regular file, sorted), all entries stored or all deflated. */
    private fun zipFolder(folder: Path, stored: Boolean): Path {
        val target = tmp.resolve("${folder.fileName}-${if (stored) "stored" else "deflated"}.zip")
        val files = Files.walk(folder).use { s -> s.filter { it.isRegularFile() }.toList() }
            .map { folder.relativize(it).joinToString("/") to it }
            .sortedBy { it.first }
        writeZip(target, files.map { (name, file) -> Triple(name, Payload(Files.readAllBytes(file), 0), stored) })
        assertTrue(Files.size(target) > 0)
        return target
    }
}
