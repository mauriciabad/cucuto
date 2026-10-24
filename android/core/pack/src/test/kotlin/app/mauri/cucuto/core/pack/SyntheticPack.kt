package app.mauri.cucuto.core.pack

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * A large pack shaped like the one in #241 (98 characters with a clip, two ringtones and a JPEG sprite each, about
 * 290 MB), built without external tools so the benchmark runs anywhere. The Ogg Opus files have real Ogg pages,
 * Opus headers and granule positions, which is all the header probes read; their packets are random bytes, so only
 * the JVM stand-in decoders accept them. Media is stored and manifests deflated, as `packer build` writes them.
 */
object SyntheticPack {
    const val CHARACTERS = 98
    const val RINGTONES_PER_CHARACTER = 2
    const val RINGTONE_MS = 90_000L
    const val RINGTONE_BYTES = 1_450_000
    const val CLIP_MS = 2_500L
    const val CLIP_BYTES = 30_000

    /** Number of media files the importer test-decodes. */
    const val MEDIA_FILES = CHARACTERS * (2 + RINGTONES_PER_CHARACTER)

    /** Writes the pack to [target]. With [deflateMedia], media is deflated too, as most zip tools do. */
    fun write(
        target: File,
        deflateMedia: Boolean = false,
    ): File {
        val random = Random(241)
        val rings = List(4) { ogg(random, RINGTONE_MS, RINGTONE_BYTES) }
        val clips = List(4) { ogg(random, CLIP_MS, CLIP_BYTES) }
        val covers = List(4) { jpeg(random) }
        val characters =
            (0 until CHARACTERS).joinToString(",\n") { i ->
                val ringtones =
                    (0 until RINGTONES_PER_CHARACTER).joinToString(", ") { r ->
                        """{ "file": "characters/c$i/ring$r.ogg", "label": "Ring $r" }"""
                    }
                """
                {
                  "id": "c$i", "name": "Character $i", "sprite": "characters/c$i/sprite.jpg",
                  "clips": [{ "file": "characters/c$i/clip.ogg" }],
                  "ringtones": [$ringtones],
                  "haptic": { "mode": "auto" }
                }
                """.trimIndent()
            }
        val manifest =
            """
            {
              "formatVersion": 1, "type": "pack", "id": "app.mauri.benchmark.large", "name": "Large pack",
              "version": "1.0.0", "author": "Cucuto benchmark", "license": "CC0-1.0",
              "characters": [
            $characters
              ]
            }
            """.trimIndent()
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered(1 shl 20)).use { zip ->
            zip.put("pack.json", manifest.encodeToByteArray(), deflate = true)
            for (i in 0 until CHARACTERS) {
                zip.put("characters/c$i/sprite.jpg", covers[i % covers.size], deflateMedia)
                zip.put("characters/c$i/clip.ogg", clips[i % clips.size], deflateMedia)
                repeat(RINGTONES_PER_CHARACTER) { r ->
                    zip.put(
                        "characters/c$i/ring$r.ogg",
                        rings[(i * RINGTONES_PER_CHARACTER + r) % rings.size],
                        deflateMedia,
                    )
                }
            }
        }
        return target
    }

    private fun ZipOutputStream.put(
        path: String,
        bytes: ByteArray,
        deflate: Boolean,
    ) {
        val entry = ZipEntry(path)
        if (!deflate) {
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = CRC32().apply { update(bytes) }.value
        }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    /** An Ogg Opus stream of [durationMs] and about [bytes] bytes: real pages and headers, random packets. */
    fun ogg(
        random: Random,
        durationMs: Long,
        bytes: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(bytes + 4096)
        val serial = random.nextInt()
        val preSkip = 312
        var sequence = 0
        val head =
            ByteArrayOutputStream()
                .apply {
                    write("OpusHead".encodeToByteArray())
                    write(1)
                    write(2)
                    le(preSkip.toLong(), 2)
                    le(48_000, 4)
                    le(0, 2)
                    write(0)
                }.toByteArray()
        val tags =
            ByteArrayOutputStream()
                .apply {
                    write("OpusTags".encodeToByteArray())
                    le(6, 4)
                    write("cucuto".encodeToByteArray())
                    le(0, 4)
                }.toByteArray()
        out.write(page(serial, sequence++, 0, FLAG_BOS, head))
        out.write(page(serial, sequence++, 0, 0, tags))
        val pageBytes = 255 * 255
        val pages = maxOf(1, (bytes - out.size()) / (pageBytes + 27 + 255))
        val samples = durationMs * 48 + preSkip
        for (p in 1..pages) {
            val granule = samples * p / pages
            out.write(page(serial, sequence++, granule, if (p == pages) FLAG_EOS else 0, random.nextBytes(pageBytes)))
        }
        return out.toByteArray()
    }

    private fun page(
        serial: Int,
        sequence: Int,
        granule: Long,
        flags: Int,
        payload: ByteArray,
    ): ByteArray {
        val segments = ArrayList<Int>()
        var left = payload.size
        while (left >= 255) {
            segments += 255
            left -= 255
        }
        if (segments.size < 255) segments += left
        val page =
            ByteArrayOutputStream()
                .apply {
                    write("OggS".encodeToByteArray())
                    write(0)
                    write(flags)
                    le(granule, 8)
                    le(serial.toLong(), 4)
                    le(sequence.toLong(), 4)
                    le(0, 4)
                    write(segments.size)
                    segments.forEach { write(it) }
                    write(payload)
                }.toByteArray()
        val crc = oggCrc(page)
        for (i in 0 until 4) page[22 + i] = (crc ushr (8 * i)).toByte()
        return page
    }

    private val crcTable =
        IntArray(256) { n ->
            var r = n shl 24
            repeat(8) { r = if (r and (1 shl 31) != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            r
        }

    private fun oggCrc(data: ByteArray): Int {
        var crc = 0
        for (b in data) crc = (crc shl 8) xor crcTable[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return crc
    }

    private fun jpeg(random: Random): ByteArray {
        val image = BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 512) for (x in 0 until 512) image.setRGB(x, y, random.nextInt())
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun ByteArrayOutputStream.le(
        value: Long,
        bytes: Int,
    ) = repeat(bytes) { write(((value ushr (8 * it)) and 0xFF).toInt()) }

    private const val FLAG_BOS = 2
    private const val FLAG_EOS = 4
}
