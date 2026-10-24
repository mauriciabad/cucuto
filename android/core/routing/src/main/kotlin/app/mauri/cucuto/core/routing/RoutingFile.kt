package app.mauri.cucuto.core.routing

import java.util.zip.CRC32

/**
 * Framing of [RoutingContract.REMOTE_FILE]: one header line `cucuto-routing-file 1 <bytes> <crc32>` followed by the
 * encoded table in UTF-8. libxposed remote files have no atomic replace, so the app rewrites the file in place and
 * the module may read it while it is being written; the length and CRC-32 make such a torn read fail instead of
 * yielding a partial table.
 */
public object RoutingFile {
    private const val MAGIC = "cucuto-routing-file"
    private const val VERSION = 1

    /**
     * Largest file the module accepts: a table of [RoutingContract.MAX_TABLE_BYTES], plus the header. Every released
     * module enforces this same value (1,048,640 bytes), so it must not shrink.
     */
    public const val MAX_BYTES: Int = RoutingContract.MAX_TABLE_BYTES + 64

    /** Whether [encoded] (the output of [RoutingCodec.encode]) is at most [RoutingContract.MAX_TABLE_BYTES] in UTF-8. */
    public fun fits(encoded: String): Boolean = utf8Length(encoded) <= RoutingContract.MAX_TABLE_BYTES

    /** Length of [text] in UTF-8 as [frame] writes it (a lone surrogate becomes `?`), without encoding it. */
    public fun utf8Length(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes +=
                when {
                    c.code < 0x80 -> {
                        1
                    }

                    c.code < 0x800 -> {
                        2
                    }

                    Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                        i++
                        4
                    }

                    Character.isSurrogate(c) -> {
                        1
                    }

                    else -> {
                        3
                    }
                }
            i++
        }
        return bytes
    }

    /** The file content for [encoded] (the output of [RoutingCodec.encode]). */
    public fun frame(encoded: String): ByteArray {
        val body = encoded.toByteArray(Charsets.UTF_8)
        val header = "$MAGIC $VERSION ${body.size} ${crc(body, 0, body.size)}\n".toByteArray(Charsets.US_ASCII)
        return header + body
    }

    /** The encoded table in [bytes], or null when the file is torn, corrupt, too large or of a newer version. */
    public fun unframe(bytes: ByteArray): String? {
        if (bytes.size > MAX_BYTES) return null
        val newline = bytes.indexOf('\n'.code.toByte())
        if (newline <= 0 || newline > 80) return null
        val parts = String(bytes, 0, newline, Charsets.US_ASCII).split(' ')
        if (parts.size != 4 || parts[0] != MAGIC || parts[1] != VERSION.toString()) return null
        val length = parts[2].toIntOrNull() ?: return null
        val checksum = parts[3].toLongOrNull() ?: return null
        val start = newline + 1
        if (length != bytes.size - start) return null
        if (crc(bytes, start, length) != checksum) return null
        return String(bytes, start, length, Charsets.UTF_8)
    }

    private fun crc(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Long = CRC32().apply { update(bytes, offset, length) }.value
}
