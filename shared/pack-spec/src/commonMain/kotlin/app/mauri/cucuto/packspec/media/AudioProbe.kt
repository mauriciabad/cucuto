package app.mauri.cucuto.packspec.media

import app.mauri.cucuto.packspec.FileFormat

/**
 * Reads the duration of an audio file from its container headers, without decoding samples. Returns null when the
 * bytes do not match the format's signature or the headers cannot be parsed (MEDIA_UNDECODABLE). Apps additionally
 * test-decode with the platform decoder at import.
 */
object AudioProbe {
    fun durationMs(format: FileFormat, b: ByteArray): Long? = try {
        when (format) {
            FileFormat.OGG -> ogg(b)
            FileFormat.WAV -> wav(b)
            FileFormat.FLAC -> flac(b)
            FileFormat.MP3 -> mp3(b)
            FileFormat.AAC -> adts(b)
            FileFormat.M4A -> mp4(b)
            else -> null
        }?.takeIf { it > 0 }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private fun ogg(b: ByteArray): Long? {
        if (!b.startsWith("OggS") || b.size < 28) return null
        val segments = b[26].toInt() and 0xFF
        val payload = 27 + segments
        val serial = b.le32(14)
        val (rate, preSkip) = when {
            b.startsWith("OpusHead", payload) -> 48_000L to b.le16(payload + 10).toLong()
            b.size > payload + 16 && b[payload].toInt() == 1 && b.startsWith("vorbis", payload + 1) -> b.le32(payload + 12) to 0L
            else -> return null
        }
        if (rate <= 0) return null
        var i = b.size - 27
        while (i >= 0) {
            if (b[i] == 'O'.code.toByte() && b.startsWith("OggS", i) && b[i + 4].toInt() == 0 && b.le32(i + 14) == serial) {
                val granule = b.le64(i + 6)
                if (granule > 0) return (granule - preSkip).coerceAtLeast(0) * 1000 / rate
            }
            i--
        }
        return null
    }

    private fun wav(b: ByteArray): Long? {
        if (!b.startsWith("RIFF") || !b.startsWith("WAVE", 8)) return null
        var p = 12
        var byteRate = 0L
        while (p + 8 <= b.size) {
            val id = b.ascii(p, 4)
            val size = b.le32(p + 4)
            when (id) {
                "fmt " -> byteRate = b.le32(p + 16)
                "data" -> {
                    if (byteRate <= 0) return null
                    val available = (b.size - p - 8).toLong()
                    val dataSize = if (size == 0xFFFFFFFFL || size > available) available else size
                    return dataSize * 1000 / byteRate
                }
            }
            if (size > b.size) return null
            p += 8 + size.toInt() + (size.toInt() and 1)
        }
        return null
    }

    private fun flac(b: ByteArray): Long? {
        if (!b.startsWith("fLaC") || b.size < 42) return null
        if (b[4].toInt() and 0x7F != 0) return null // first block must be STREAMINFO
        val s = 8
        val rate = ((b[s + 10].toLong() and 0xFF) shl 12) or ((b[s + 11].toLong() and 0xFF) shl 4) or ((b[s + 12].toLong() and 0xFF) ushr 4)
        val total = ((b[s + 13].toLong() and 0x0F) shl 32) or b.be32(s + 14)
        if (rate <= 0 || total <= 0) return null
        return total * 1000 / rate
    }

    private val MP3_BITRATES = arrayOf(
        intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448), // V1 L1
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384), // V1 L2
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320), // V1 L3
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256), // V2 L1
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160), // V2 L2/L3
    )
    private val MP3_RATES = arrayOf(intArrayOf(44100, 48000, 32000), intArrayOf(22050, 24000, 16000), intArrayOf(11025, 12000, 8000))

    private class Mp3Frame(val length: Int, val samples: Int, val rate: Int, val mpeg1: Boolean, val mono: Boolean)

    private fun mp3Frame(b: ByteArray, p: Int): Mp3Frame? {
        if (p + 4 > b.size) return null
        val h = b.be32(p)
        if (h ushr 21 != 0x7FFL) return null
        val version = ((h ushr 19) and 3).toInt() // 0: 2.5, 2: 2, 3: 1
        val layer = ((h ushr 17) and 3).toInt() // 1: L3, 2: L2, 3: L1
        val bitrateIdx = ((h ushr 12) and 0xF).toInt()
        val rateIdx = ((h ushr 10) and 3).toInt()
        val padding = ((h ushr 9) and 1).toInt()
        val mono = ((h ushr 6) and 3).toInt() == 3
        if (version == 1 || layer == 0 || bitrateIdx == 0 || bitrateIdx == 15 || rateIdx == 3) return null
        val mpeg1 = version == 3
        val table = when {
            mpeg1 -> 3 - layer // L1 -> 0, L2 -> 1, L3 -> 2
            layer == 3 -> 3
            else -> 4
        }
        val bitrate = MP3_BITRATES[table][bitrateIdx] * 1000
        val rate = MP3_RATES[if (mpeg1) 0 else if (version == 2) 1 else 2][rateIdx]
        val (length, samples) = when (layer) {
            3 -> (12 * bitrate / rate + padding) * 4 to 384
            2 -> 144 * bitrate / rate + padding to 1152
            else -> (if (mpeg1) 144 else 72) * bitrate / rate + padding to (if (mpeg1) 1152 else 576)
        }
        return if (length < 4) null else Mp3Frame(length, samples, rate, mpeg1, mono)
    }

    private fun skipId3v2(b: ByteArray): Int {
        if (!b.startsWith("ID3") || b.size < 10) return 0
        val size = ((b[6].toInt() and 0x7F) shl 21) or ((b[7].toInt() and 0x7F) shl 14) or ((b[8].toInt() and 0x7F) shl 7) or (b[9].toInt() and 0x7F)
        val footer = if (b[5].toInt() and 0x10 != 0) 10 else 0
        return 10 + size + footer
    }

    private fun mp3(b: ByteArray): Long? {
        var p = skipId3v2(b)
        // Find the first frame that is followed by another valid frame.
        val limit = minOf(b.size, p + 64 * 1024)
        var first: Mp3Frame? = null
        while (p < limit) {
            val f = mp3Frame(b, p)
            if (f != null && (p + f.length == b.size || mp3Frame(b, p + f.length) != null)) { first = f; break }
            p++
        }
        if (first == null) return null
        // Xing / Info header gives the frame count directly.
        val sideInfo = if (first.mpeg1) (if (first.mono) 17 else 32) else (if (first.mono) 9 else 17)
        val x = p + 4 + sideInfo
        if (x + 12 <= b.size && (b.startsWith("Xing", x) || b.startsWith("Info", x))) {
            val flags = b.be32(x + 4)
            if (flags and 1L != 0L) {
                val frames = b.be32(x + 8)
                if (frames > 0) return frames * first.samples * 1000 / first.rate
            }
        }
        var samples = 0L
        var rate = first.rate
        while (p < b.size) {
            val f = mp3Frame(b, p) ?: break
            samples += f.samples
            rate = f.rate
            p += f.length
        }
        return if (samples == 0L) null else samples * 1000 / rate
    }

    private val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    private fun adts(b: ByteArray): Long? {
        var p = skipId3v2(b)
        var samples = 0L
        var rate = 0
        while (p + 7 <= b.size) {
            if (b[p].toInt() and 0xFF != 0xFF || b[p + 1].toInt() and 0xF6 != 0xF0) break
            val rateIdx = (b[p + 2].toInt() ushr 2) and 0xF
            if (rateIdx >= AAC_RATES.size) return null
            rate = AAC_RATES[rateIdx]
            val length = ((b[p + 3].toInt() and 3) shl 11) or ((b[p + 4].toInt() and 0xFF) shl 3) or ((b[p + 5].toInt() and 0xFF) ushr 5)
            if (length < 7) return null
            samples += 1024L * ((b[p + 6].toInt() and 3) + 1)
            p += length
        }
        return if (samples == 0L || rate == 0) null else samples * 1000 / rate
    }

    private fun mp4(b: ByteArray): Long? {
        if (b.size < 12 || !b.startsWith("ftyp", 4)) return null
        val moov = Boxes.find(b, 0, b.size, "moov") ?: return null
        val mvhd = Boxes.find(b, moov.first, moov.last + 1, "mvhd") ?: return null
        val s = mvhd.first
        val version = b[s].toInt()
        val (timescale, duration) = if (version == 1) b.be32(s + 20) to b.be64(s + 24) else b.be32(s + 12) to b.be32(s + 16)
        if (timescale <= 0 || duration <= 0 || duration == 0xFFFFFFFFL) return null
        return duration * 1000 / timescale
    }
}

/** ISO-BMFF box walking shared by MP4 and AVIF. */
internal object Boxes {
    /** Content range (excluding the header) of the first box of [type] directly inside [start, end). */
    fun find(b: ByteArray, start: Int, end: Int, type: String): IntRange? {
        var p = start
        while (p + 8 <= end) {
            var size = b.be32(p)
            var header = 8
            if (size == 1L) { size = b.be64(p + 8); header = 16 } else if (size == 0L) size = (end - p).toLong()
            if (size < header || p + size > end) return null
            if (b.startsWith(type, p + 4)) return (p + header) until (p + size).toInt()
            p += size.toInt()
        }
        return null
    }

    fun findAll(b: ByteArray, start: Int, end: Int, type: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var p = start
        while (p + 8 <= end) {
            var size = b.be32(p)
            var header = 8
            if (size == 1L) { size = b.be64(p + 8); header = 16 } else if (size == 0L) size = (end - p).toLong()
            if (size < header || p + size > end) return out
            if (b.startsWith(type, p + 4)) out += (p + header) until (p + size).toInt()
            p += size.toInt()
        }
        return out
    }
}

internal fun ByteArray.startsWith(ascii: String, at: Int = 0): Boolean =
    at >= 0 && at + ascii.length <= size && ascii.indices.all { this[at + it] == ascii[it].code.toByte() }

internal fun ByteArray.ascii(at: Int, len: Int): String = buildString { for (i in at until at + len) append((this@ascii[i].toInt() and 0xFF).toChar()) }
internal fun ByteArray.le16(i: Int): Int = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
internal fun ByteArray.le32(i: Int): Long = le16(i).toLong() or (le16(i + 2).toLong() shl 16)
internal fun ByteArray.le64(i: Int): Long = le32(i) or (le32(i + 4) shl 32)
internal fun ByteArray.be16(i: Int): Int = ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)
internal fun ByteArray.be32(i: Int): Long = (be16(i).toLong() shl 16) or be16(i + 2).toLong()
internal fun ByteArray.be64(i: Int): Long = (be32(i) shl 32) or be32(i + 4)
