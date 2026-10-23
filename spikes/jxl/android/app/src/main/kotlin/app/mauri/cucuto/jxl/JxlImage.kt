package app.mauri.cucuto.jxl

import android.graphics.Bitmap

/** Raw JNI surface of `libcucuto_jxl.so` (spikes/jxl/rust/src/android.rs). */
internal object JxlNative {
    init {
        System.loadLibrary("cucuto_jxl")
    }

    @JvmStatic external fun nativeOpen(data: ByteArray, threads: Int): Long
    @JvmStatic external fun nativeInfo(handle: Long): IntArray
    @JvmStatic external fun nativeNextFrame(handle: Long, bitmap: Bitmap): Int
    @JvmStatic external fun nativeRewind(handle: Long)
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativeFrameCount(data: ByteArray): Int
}

/**
 * A JPEG XL file opened with jxl-rs. Frames are decoded one at a time straight into a caller
 * supplied ARGB_8888 bitmap, so an animation needs one or two bitmaps, not one per frame.
 *
 * Not thread-safe: use one instance per decoding thread. Every native failure (corrupt data,
 * oversized image, even a Rust panic) arrives as a [RuntimeException], never as a crash.
 */
class JxlImage(data: ByteArray, threads: Int = 1) : AutoCloseable {
    private var handle: Long = JxlNative.nativeOpen(data, threads)

    val width: Int
    val height: Int
    val hasAlpha: Boolean
    val isAnimated: Boolean
    /** 0 = loop forever. */
    val loopCount: Int

    init {
        val info = JxlNative.nativeInfo(handle)
        width = info[0]
        height = info[1]
        hasAlpha = info[2] != 0
        isAnimated = info[3] != 0
        loopCount = info[4]
    }

    fun newBitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setHasAlpha(hasAlpha)
    }

    /** Decodes the next frame into [into]; returns its duration in ms, or null after the last frame. */
    fun decodeNextFrame(into: Bitmap): Int? {
        check(handle != 0L) { "closed" }
        return JxlNative.nativeNextFrame(handle, into).takeIf { it >= 0 }
    }

    fun rewind() {
        check(handle != 0L) { "closed" }
        JxlNative.nativeRewind(handle)
    }

    override fun close() {
        if (handle != 0L) JxlNative.nativeClose(handle)
        handle = 0L
    }

    companion object {
        private val CODESTREAM = byteArrayOf(0xFF.toByte(), 0x0A)
        private val CONTAINER = byteArrayOf(0, 0, 0, 0x0C, 'J'.code.toByte(), 'X'.code.toByte(), 'L'.code.toByte(), ' '.code.toByte(), 0x0D, 0x0A, 0x87.toByte(), 0x0A)

        fun isJxl(header: ByteArray): Boolean =
            header.size >= 2 && header.copyOf(2).contentEquals(CODESTREAM) ||
                header.size >= 12 && header.copyOf(12).contentEquals(CONTAINER)

        /** First (or only) frame as a new bitmap. */
        fun decodeBitmap(data: ByteArray, threads: Int = 1): Bitmap = JxlImage(data, threads).use { img ->
            img.newBitmap().also { checkNotNull(img.decodeNextFrame(it)) { "no frames" } }
        }

        /** Frame count from a header/TOC scan, without decoding pixels. */
        fun frameCount(data: ByteArray): Int = JxlNative.nativeFrameCount(data)

        internal val signatures = listOf(CODESTREAM, CONTAINER)
    }
}
