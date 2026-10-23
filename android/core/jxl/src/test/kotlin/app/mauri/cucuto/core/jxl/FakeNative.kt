package app.mauri.cucuto.core.jxl

import android.graphics.Bitmap

/**
 * A scripted [JxlNative]: a header and a list of frame durations; [failAtFrame] makes that frame throw like a corrupt
 * file. Records every call so tests can check what reached the "native" side.
 */
internal class FakeNative(
    private val width: Int = 64,
    private val height: Int = 32,
    private val frames: List<Int> = listOf(0),
    private val loops: Int = 0,
    private val failAtFrame: Int? = null,
    private val badHeader: Boolean = false,
) : JxlNative {
    val calls = mutableListOf<String>()
    private var nextHandle = 1L
    private val open = mutableMapOf<Long, Int>() // handle -> next frame index

    val openHandles: Int get() = open.size

    override fun readInfo(
        data: ByteArray,
        maxSide: Int,
    ): IntArray {
        calls += "readInfo"
        if (badHeader) throw JxlException("JPEG XL decode error: bad signature")
        val fits = width <= maxSide && height <= maxSide
        return intArrayOf(width, height, 1, if (frames.size > 1) 1 else 0, loops, 3, if (fits) frames.size else -1)
    }

    override fun open(
        data: ByteArray,
        maxSide: Int,
    ): Long {
        calls += "open"
        check(width <= maxSide && height <= maxSide) { "the Kotlin side must reject oversized images first" }
        return nextHandle++.also { open[it] = 0 }
    }

    override fun nextFrame(
        handle: Long,
        bitmap: Bitmap,
    ): Int {
        val index = open.getValue(handle)
        calls += "nextFrame"
        if (index == failAtFrame) throw JxlException("JPEG XL decode error: corrupt frame")
        if (index >= frames.size) return -1
        open[handle] = index + 1
        return frames[index]
    }

    override fun rewind(handle: Long) {
        calls += "rewind"
        open[handle] = 0
    }

    override fun close(handle: Long) {
        calls += "close"
        checkNotNull(open.remove(handle)) { "double close" }
    }
}

/** `Bitmap` is a stub on the JVM (methods return defaults); an instance without running any constructor. */
internal val fakeBitmaps =
    BitmapAllocator {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, Bitmap::class.java) as Bitmap
    }
