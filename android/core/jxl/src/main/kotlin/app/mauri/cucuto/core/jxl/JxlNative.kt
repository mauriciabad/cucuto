package app.mauri.cucuto.core.jxl

import android.graphics.Bitmap

/** The native decoder, behind an interface so the Kotlin logic is unit tested on the JVM with a fake. */
internal interface JxlNative {
    /** `[width, height, hasAlpha, animated, loops, colorSpace, frameCount]`; see [JxlInfo.fromNative]. */
    fun readInfo(
        data: ByteArray,
        maxSide: Int,
    ): IntArray

    /** A decoder handle; fails when a side is over [maxSide]. */
    fun open(
        data: ByteArray,
        maxSide: Int,
    ): Long

    /** Decodes the next frame into [bitmap]; its duration in ms, or -1 after the last frame. */
    fun nextFrame(
        handle: Long,
        bitmap: Bitmap,
    ): Int

    fun rewind(handle: Long)

    fun close(handle: Long)
}

/**
 * JNI binding to `libcucuto_jxl.so` (rust/src/android.rs). Native failures, including panics, arrive as
 * [RuntimeException] and are rethrown as [JxlException]; a missing library becomes [JxlUnavailableException].
 */
internal object JxlJni : JxlNative {
    private val loadError: Throwable? by lazy {
        runCatching { System.loadLibrary("cucuto_jxl") }.exceptionOrNull()
    }

    private inline fun <T> call(block: () -> T): T {
        loadError?.let { throw JxlUnavailableException(it) }
        return try {
            block()
        } catch (e: RuntimeException) {
            throw JxlException(e.message ?: "JPEG XL decoding failed", e)
        }
    }

    override fun readInfo(
        data: ByteArray,
        maxSide: Int,
    ): IntArray = call { nativeReadInfo(data, maxSide) }

    override fun open(
        data: ByteArray,
        maxSide: Int,
    ): Long = call { nativeOpen(data, maxSide) }

    override fun nextFrame(
        handle: Long,
        bitmap: Bitmap,
    ): Int = call { nativeNextFrame(handle, bitmap) }

    override fun rewind(handle: Long) = call { nativeRewind(handle) }

    override fun close(handle: Long) = call { nativeClose(handle) }

    @JvmStatic private external fun nativeReadInfo(
        data: ByteArray,
        maxSide: Int,
    ): IntArray

    @JvmStatic private external fun nativeOpen(
        data: ByteArray,
        maxSide: Int,
    ): Long

    @JvmStatic private external fun nativeNextFrame(
        handle: Long,
        bitmap: Bitmap,
    ): Int

    @JvmStatic private external fun nativeRewind(handle: Long)

    @JvmStatic private external fun nativeClose(handle: Long)
}
