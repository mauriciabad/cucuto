package app.mauri.cucuto.packspec.media

import app.mauri.cucuto.packspec.FileFormat

data class ImageSize(val width: Int, val height: Int)

/**
 * Reads bitmap dimensions from image headers. Returns null when the bytes do not match the format's signature or the
 * header cannot be parsed (MEDIA_UNDECODABLE).
 */
object ImageProbe {
    fun size(format: FileFormat, b: ByteArray): ImageSize? = try {
        when (format) {
            FileFormat.PNG -> png(b)
            FileFormat.GIF -> gif(b)
            FileFormat.JPEG -> jpeg(b)
            FileFormat.WEBP -> webp(b)
            FileFormat.AVIF -> avif(b)
            FileFormat.JXL -> jxl(b)
            else -> null
        }?.takeIf { it.width > 0 && it.height > 0 }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun png(b: ByteArray): ImageSize? {
        if (b.size < 24 || !PNG_SIG.indices.all { b[it] == PNG_SIG[it] } || !b.startsWith("IHDR", 12)) return null
        return ImageSize(b.be32(16).toInt(), b.be32(20).toInt())
    }

    private fun gif(b: ByteArray): ImageSize? {
        if (!b.startsWith("GIF87a") && !b.startsWith("GIF89a")) return null
        return ImageSize(b.le16(6), b.le16(8))
    }

    private fun jpeg(b: ByteArray): ImageSize? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var p = 2
        while (p + 4 <= b.size) {
            if (b[p] != 0xFF.toByte()) return null
            var marker = b[p + 1].toInt() and 0xFF
            while (marker == 0xFF) { p++; marker = b[p + 1].toInt() and 0xFF }
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { p += 2; continue }
            if (marker == 0xD9) return null
            val len = b.be16(p + 2)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return ImageSize(b.be16(p + 7), b.be16(p + 5))
            }
            p += 2 + len
        }
        return null
    }

    private fun webp(b: ByteArray): ImageSize? {
        if (!b.startsWith("RIFF") || !b.startsWith("WEBP", 8) || b.size < 30) return null
        return when {
            b.startsWith("VP8 ", 12) -> {
                if (b[23] != 0x9D.toByte() || b[24] != 0x01.toByte() || b[25] != 0x2A.toByte()) null
                else ImageSize(b.le16(26) and 0x3FFF, b.le16(28) and 0x3FFF)
            }
            b.startsWith("VP8L", 12) -> {
                if (b[20] != 0x2F.toByte()) null
                else {
                    val bits = b.le32(21)
                    ImageSize((bits and 0x3FFF).toInt() + 1, ((bits ushr 14) and 0x3FFF).toInt() + 1)
                }
            }
            b.startsWith("VP8X", 12) -> {
                val w = (b[24].toInt() and 0xFF) or ((b[25].toInt() and 0xFF) shl 8) or ((b[26].toInt() and 0xFF) shl 16)
                val h = (b[27].toInt() and 0xFF) or ((b[28].toInt() and 0xFF) shl 8) or ((b[29].toInt() and 0xFF) shl 16)
                ImageSize(w + 1, h + 1)
            }
            else -> null
        }
    }

    private fun avif(b: ByteArray): ImageSize? {
        val ftyp = Boxes.find(b, 0, b.size, "ftyp") ?: return null
        val brands = (ftyp.first until ftyp.last + 1 step 4).filter { it != ftyp.first + 4 }.map { b.ascii(it, 4) }
        if (brands.none { it == "avif" || it == "avis" }) return null
        val meta = Boxes.find(b, 0, b.size, "meta") ?: return null
        val iprp = Boxes.find(b, meta.first + 4, meta.last + 1, "iprp") ?: return null
        val ipco = Boxes.find(b, iprp.first, iprp.last + 1, "ipco") ?: return null
        val sizes = Boxes.findAll(b, ipco.first, ipco.last + 1, "ispe").map { ImageSize(b.be32(it.first + 4).toInt(), b.be32(it.first + 8).toInt()) }
        return sizes.maxByOrNull { it.width.toLong() * it.height }
    }

    private val JXL_CONTAINER = byteArrayOf(0, 0, 0, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A)

    private fun jxl(b: ByteArray): ImageSize? {
        val codestream: ByteArray = when {
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0x0A.toByte() -> b
            b.size >= 12 && JXL_CONTAINER.indices.all { b[it] == JXL_CONTAINER[it] } -> {
                val jxlc = Boxes.find(b, 0, b.size, "jxlc")
                if (jxlc != null) b.copyOfRange(jxlc.first, jxlc.last + 1)
                else {
                    val parts = Boxes.findAll(b, 0, b.size, "jxlp")
                    if (parts.isEmpty()) return null
                    parts.fold(ByteArray(0)) { acc, r -> acc + b.copyOfRange(r.first + 4, r.last + 1) }
                }
            }
            else -> return null
        }
        if (codestream.size < 3 || codestream[0] != 0xFF.toByte() || codestream[1] != 0x0A.toByte()) return null
        val r = BitReader(codestream, 2)
        fun sizeU32(): Long = when (r.bits(2)) {
            0 -> r.bits(9) + 1L
            1 -> r.bits(13) + 1L
            2 -> r.bits(18) + 1L
            else -> r.bits(30) + 1L
        }
        val small = r.bits(1) == 1
        val height = if (small) (r.bits(5) + 1L) * 8 else sizeU32()
        val ratio = r.bits(3)
        val width = when (ratio) {
            0 -> if (small) (r.bits(5) + 1L) * 8 else sizeU32()
            1 -> height
            2 -> height * 12 / 10
            3 -> height * 4 / 3
            4 -> height * 3 / 2
            5 -> height * 16 / 9
            6 -> height * 5 / 4
            else -> height * 2
        }
        if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) return null
        return ImageSize(width.toInt(), height.toInt())
    }

    /** Least-significant-bit-first reader, as JPEG XL uses. */
    private class BitReader(private val b: ByteArray, private var byte: Int) {
        private var bit = 0
        fun bits(n: Int): Int {
            var v = 0
            for (i in 0 until n) {
                val x = (b[byte].toInt() ushr bit) and 1
                v = v or (x shl i)
                bit++
                if (bit == 8) { bit = 0; byte++ }
            }
            return v
        }
    }
}
