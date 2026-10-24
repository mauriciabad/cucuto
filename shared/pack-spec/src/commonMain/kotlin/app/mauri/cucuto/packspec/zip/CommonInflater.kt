package app.mauri.cucuto.packspec.zip

/**
 * Raw DEFLATE (RFC 1951) in common code, for platforms without a synchronous inflater: the browser build used by the
 * website (ADR 0022). The JVM and Android keep `java.util.zip.Inflater` (`JvmPackIo.inflater`); a JVM test checks
 * that both agree. Follows the structure of zlib's reference decoder `puff.c`: canonical Huffman codes decoded bit by
 * bit, which is small and easy to audit rather than fast.
 *
 * Same contract as the JVM adapter: never writes more than `expectedSize` bytes, returns null for corrupt data or a
 * stream that expands to more or fewer bytes.
 */
object CommonInflater : RawInflater {
    override fun inflate(compressed: ByteArray, expectedSize: Int): ByteArray? {
        if (expectedSize < 0) return null
        val run = Inflate(compressed, expectedSize)
        return try {
            run.blocks()
            if (run.outPos == expectedSize) run.out else null
        } catch (e: InputEnded) {
            // Like java.util.zip: a stream cut short is accepted only if it already produced every expected byte.
            if (run.outPos == expectedSize) run.out else null
        } catch (e: CorruptData) {
            null
        }
    }
}

private class InputEnded : Exception()

private class CorruptData : Exception()

/** A canonical Huffman code: how many codes have each length (0..15), and the symbols ordered by code. */
private class Huffman(symbols: Int) {
    val count = IntArray(MAX_BITS + 1)
    val symbol = IntArray(symbols)

    /**
     * Builds the code from code lengths. Returns 0 for a complete code, a positive number for an incomplete one, and a
     * negative number for an over-subscribed one.
     */
    fun build(lengths: IntArray, offset: Int, n: Int): Int {
        count.fill(0)
        for (i in 0 until n) count[lengths[offset + i]]++
        if (count[0] == n) return 0
        var left = 1
        for (len in 1..MAX_BITS) {
            left = (left shl 1) - count[len]
            if (left < 0) return left
        }
        val offs = IntArray(MAX_BITS + 1)
        for (len in 1 until MAX_BITS) offs[len + 1] = offs[len] + count[len]
        for (i in 0 until n) {
            val len = lengths[offset + i]
            if (len != 0) symbol[offs[len]++] = i
        }
        return left
    }

    companion object {
        const val MAX_BITS = 15
    }
}

private class Inflate(private val input: ByteArray, expectedSize: Int) {
    val out = ByteArray(expectedSize)
    var outPos = 0
    private var inPos = 0
    private var bitBuf = 0
    private var bitCount = 0

    private fun bits(need: Int): Int {
        var value = bitBuf
        while (bitCount < need) {
            if (inPos >= input.size) throw InputEnded()
            value = value or ((input[inPos++].toInt() and 0xFF) shl bitCount)
            bitCount += 8
        }
        bitBuf = value ushr need
        bitCount -= need
        return value and ((1 shl need) - 1)
    }

    private fun decode(h: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..Huffman.MAX_BITS) {
            code = code or bits(1)
            val count = h.count[len]
            if (code - count < first) return h.symbol[index + (code - first)]
            index += count
            first = (first + count) shl 1
            code = code shl 1
        }
        throw CorruptData()
    }

    fun blocks() {
        do {
            val last = bits(1)
            when (bits(2)) {
                0 -> stored()
                1 -> codes(FIXED.lengths, FIXED.distances)
                2 -> dynamic()
                else -> throw CorruptData()
            }
        } while (last == 0)
    }

    private fun stored() {
        // Skip to the next byte boundary: bits() never buffers more than the rest of the current byte.
        bitBuf = 0
        bitCount = 0
        if (inPos + 4 > input.size) throw InputEnded()
        val len = (input[inPos].toInt() and 0xFF) or ((input[inPos + 1].toInt() and 0xFF) shl 8)
        val nlen = (input[inPos + 2].toInt() and 0xFF) or ((input[inPos + 3].toInt() and 0xFF) shl 8)
        inPos += 4
        if (len != (nlen.inv() and 0xFFFF)) throw CorruptData()
        if (outPos + len > out.size) throw CorruptData()
        if (inPos + len > input.size) throw InputEnded()
        input.copyInto(out, outPos, inPos, inPos + len)
        inPos += len
        outPos += len
    }

    private fun codes(lengths: Huffman, distances: Huffman) {
        while (true) {
            val sym = decode(lengths)
            when {
                sym < 256 -> {
                    if (outPos >= out.size) throw CorruptData()
                    out[outPos++] = sym.toByte()
                }
                sym == 256 -> return
                else -> {
                    val ls = sym - 257
                    if (ls >= LENGTH_BASE.size) throw CorruptData()
                    val len = LENGTH_BASE[ls] + bits(LENGTH_EXTRA[ls])
                    val ds = decode(distances)
                    if (ds >= DIST_BASE.size) throw CorruptData()
                    val dist = DIST_BASE[ds] + bits(DIST_EXTRA[ds])
                    if (dist > outPos || outPos + len > out.size) throw CorruptData()
                    for (i in 0 until len) {
                        out[outPos] = out[outPos - dist]
                        outPos++
                    }
                }
            }
        }
    }

    private fun dynamic() {
        val nlen = bits(5) + 257
        val ndist = bits(5) + 1
        val ncode = bits(4) + 4
        if (nlen > 286 || ndist > 30) throw CorruptData()
        val lengths = IntArray(nlen + ndist)
        val codeLengths = IntArray(19)
        for (i in 0 until ncode) codeLengths[CODE_LENGTH_ORDER[i]] = bits(3)
        val lencode = Huffman(286)
        // The code-length code must be complete.
        if (lencode.build(codeLengths, 0, 19) != 0) throw CorruptData()
        var index = 0
        while (index < nlen + ndist) {
            var sym = decode(lencode)
            if (sym < 16) {
                lengths[index++] = sym
                continue
            }
            var len = 0
            when (sym) {
                16 -> {
                    if (index == 0) throw CorruptData()
                    len = lengths[index - 1]
                    sym = 3 + bits(2)
                }
                17 -> sym = 3 + bits(3)
                else -> sym = 11 + bits(7)
            }
            if (index + sym > nlen + ndist) throw CorruptData()
            repeat(sym) { lengths[index++] = len }
        }
        if (lengths[256] == 0) throw CorruptData()
        // Incomplete codes are allowed only when every code has length 1 (a single code), as in zlib.
        var err = lencode.build(lengths, 0, nlen)
        if (err < 0 || (err > 0 && nlen != lencode.count[0] + lencode.count[1])) throw CorruptData()
        val distcode = Huffman(30)
        err = distcode.build(lengths, nlen, ndist)
        if (err < 0 || (err > 0 && ndist != distcode.count[0] + distcode.count[1])) throw CorruptData()
        codes(lencode, distcode)
    }

    private object FIXED {
        val lengths = Huffman(288)
        val distances = Huffman(30)

        init {
            val l = IntArray(288) { i ->
                when {
                    i < 144 -> 8
                    i < 256 -> 9
                    i < 280 -> 7
                    else -> 8
                }
            }
            lengths.build(l, 0, 288)
            distances.build(IntArray(30) { 5 }, 0, 30)
        }
    }

    private companion object {
        val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
        val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
        val DIST_BASE = intArrayOf(
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
            8193, 12289, 16385, 24577,
        )
        val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
        val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
    }
}
