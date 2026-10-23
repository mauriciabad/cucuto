package app.mauri.cucuto.packspec.media

import app.mauri.cucuto.packspec.zip.RandomAccessInput

/**
 * The bytes of one file, as the header probes read them: an array, or pages read on demand from a
 * [RandomAccessInput], so a 100 MiB file never has to be in memory whole. A read outside the file throws
 * [IndexOutOfBoundsException], like an array does, so the probes behave the same with both.
 */
internal abstract class Bytes {
    abstract val size: Int

    abstract operator fun get(i: Int): Byte

    fun startsWith(ascii: String, at: Int = 0): Boolean =
        at >= 0 && at + ascii.length <= size && ascii.indices.all { this[at + it] == ascii[it].code.toByte() }

    fun ascii(at: Int, len: Int): String = buildString { for (i in at until at + len) append((this@Bytes[i].toInt() and 0xFF).toChar()) }
    fun le16(i: Int): Int = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
    fun le32(i: Int): Long = le16(i).toLong() or (le16(i + 2).toLong() shl 16)
    fun le64(i: Int): Long = le32(i) or (le32(i + 4) shl 32)
    fun be16(i: Int): Int = ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)
    fun be32(i: Int): Long = (be16(i).toLong() shl 16) or be16(i + 2).toLong()
    fun be64(i: Int): Long = (be32(i) shl 32) or be32(i + 4)

    companion object {
        fun of(array: ByteArray): Bytes = ArrayBytes(array)

        /** Pages of [input], read when first touched; null when the input is larger than an array index allows. */
        fun of(input: RandomAccessInput): Bytes? = if (input.size > Int.MAX_VALUE) null else PagedBytes(input)
    }
}

private class ArrayBytes(private val array: ByteArray) : Bytes() {
    override val size: Int get() = array.size

    override fun get(i: Int): Byte = array[i]
}

/** Keeps the last few [PAGE_BYTES] pages: the probes read headers, walk forward, or scan back from the end. */
private class PagedBytes(private val input: RandomAccessInput) : Bytes() {
    override val size: Int = input.size.toInt()
    private val pages = ArrayDeque<Pair<Int, ByteArray>>()
    private var lastIndex = -1
    private var last = ByteArray(0)

    override fun get(i: Int): Byte {
        if (i < 0 || i >= size) throw IndexOutOfBoundsException("Index $i outside 0 until $size")
        val index = i / PAGE_BYTES
        if (index != lastIndex) {
            last = page(index)
            lastIndex = index
        }
        return last[i - index * PAGE_BYTES]
    }

    private fun page(index: Int): ByteArray {
        pages.firstOrNull { it.first == index }?.let { return it.second }
        val start = index.toLong() * PAGE_BYTES
        val bytes = ByteArray(minOf(PAGE_BYTES.toLong(), size - start).toInt())
        input.readFully(start, bytes, 0, bytes.size)
        if (pages.size >= MAX_PAGES) pages.removeLast()
        pages.addFirst(index to bytes)
        return bytes
    }

    companion object {
        const val PAGE_BYTES = 64 * 1024
        const val MAX_PAGES = 4
    }
}
