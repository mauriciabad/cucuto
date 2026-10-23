package app.mauri.cucuto.core.jxl

import android.graphics.Bitmap
import android.graphics.ColorSpace
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.lang.ref.Cleaner

/**
 * A JPEG XL file opened with jxl-rs (ADR 0006). Frames are decoded one at a time straight into a caller-supplied
 * ARGB_8888 bitmap, so an animation needs one or two bitmaps, not one per frame.
 *
 * Every failure (corrupt or truncated data, an oversized image, even a panic inside the decoder) arrives as a
 * [JxlException], never as a crash. Calls are serialized on an internal lock, so [close] from another thread waits
 * for a frame in progress; still, decode from one thread at a time. A forgotten [close] is caught by a [Cleaner].
 */
class JxlImage internal constructor(
    /** The header: size, animation, frame count, color space. */
    val info: JxlInfo,
    private val native: JxlNative,
    handle: Long,
    private val allocator: BitmapAllocator,
) : AutoCloseable {
    private val state = NativeHandle(native, handle)
    private val cleanable = cleaner.register(this, state)

    val width: Int get() = info.width
    val height: Int get() = info.height

    val isClosed: Boolean get() = state.isClosed

    /** A bitmap of the image's size, alpha and color space, to decode frames into. */
    fun newBitmap(): Bitmap = allocator.allocate(info)

    /**
     * Decodes the next frame into [into] (mutable, ARGB_8888, the image's size) and returns its duration in ms, or
     * null after the last frame.
     */
    fun decodeNextFrame(into: Bitmap): Int? = state.use { native.nextFrame(it, into) }.takeIf { it >= 0 }

    /** Back to the first frame, for looping animations. */
    fun rewind() = state.use { native.rewind(it) }

    /**
     * Decodes every remaining frame into [into], calling [onFrame] after each one; [JxlFrame.bitmap] is [into], so it
     * is overwritten by the next frame. Cancellable between frames. Returns how many frames were decoded.
     */
    suspend fun forEachFrame(
        into: Bitmap = newBitmap(),
        onFrame: suspend (JxlFrame) -> Unit = {},
    ): Int {
        var index = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val duration = decodeNextFrame(into) ?: return index
            onFrame(JxlFrame(index++, duration, into))
        }
    }

    /** Frees the native decoder. Idempotent. */
    override fun close() = cleanable.clean()

    companion object {
        /** Packs allow at most 512 px per side (pack-spec `Limits.MAX_BITMAP_SIDE_PX`). */
        const val DEFAULT_MAX_SIDE = 512

        /** The native decoder never accepts more than this, whatever the caller asks for. */
        const val HARD_MAX_SIDE = 2048

        /** The MIME type of JPEG XL files. */
        const val MIME_TYPE = "image/jxl"

        private val cleaner: Cleaner by lazy { Cleaner.create() }

        /** Whether [header] (at least the first 12 bytes of a file) starts like a JPEG XL codestream or container. */
        fun isJxl(header: ByteArray): Boolean = JxlSignature.matches(header)

        /**
         * Reads the header and counts the frames, without decoding pixels. An image over [maxSide] is not an error
         * here: its size is returned with a null [JxlInfo.frameCount].
         */
        fun readInfo(
            data: ByteArray,
            maxSide: Int = DEFAULT_MAX_SIDE,
        ): JxlInfo = readInfo(JxlJni, data, maxSide)

        /** Opens [data] for decoding; throws [JxlTooLargeException] when a side is over [maxSide]. */
        fun open(
            data: ByteArray,
            maxSide: Int = DEFAULT_MAX_SIDE,
        ): JxlImage = open(JxlJni, data, maxSide, BitmapAllocator.Default)

        /** The first (or only) frame as a new bitmap. */
        fun decodeBitmap(
            data: ByteArray,
            maxSide: Int = DEFAULT_MAX_SIDE,
        ): Bitmap = open(data, maxSide).use { it.decodeFirstFrame() }

        /**
         * Decodes every frame of [data] into one scratch bitmap and returns the header: the import check, so a file
         * that breaks halfway through an animation is caught before it is installed.
         */
        fun verify(
            data: ByteArray,
            maxSide: Int = DEFAULT_MAX_SIDE,
        ): JxlInfo = verify(JxlJni, data, maxSide, BitmapAllocator.Default)

        internal fun readInfo(
            native: JxlNative,
            data: ByteArray,
            maxSide: Int,
        ): JxlInfo = JxlInfo.fromNative(native.readInfo(data, checkMaxSide(maxSide)))

        internal fun open(
            native: JxlNative,
            data: ByteArray,
            maxSide: Int,
            allocator: BitmapAllocator,
        ): JxlImage {
            val info = readInfo(native, data, maxSide)
            info.requireWithin(maxSide)
            return JxlImage(info, native, native.open(data, maxSide), allocator)
        }

        internal fun verify(
            native: JxlNative,
            data: ByteArray,
            maxSide: Int,
            allocator: BitmapAllocator,
        ): JxlInfo =
            open(native, data, maxSide, allocator).use { image ->
                val scratch = image.newBitmap()
                try {
                    var frames = 0
                    while (image.decodeNextFrame(scratch) != null) frames++
                    if (frames == 0) throw JxlException("No frames")
                    image.info
                } finally {
                    scratch.recycle()
                }
            }

        private fun checkMaxSide(maxSide: Int): Int {
            require(maxSide in 1..HARD_MAX_SIDE) { "maxSide must be in 1..$HARD_MAX_SIDE, was $maxSide" }
            return maxSide
        }
    }
}

/** One decoded frame of [JxlImage.forEachFrame]. */
data class JxlFrame(
    val index: Int,
    val durationMs: Int,
    /** Overwritten by the next frame. */
    val bitmap: Bitmap,
)

internal fun JxlImage.decodeFirstFrame(): Bitmap {
    val bitmap = newBitmap()
    if (decodeNextFrame(bitmap) == null) {
        bitmap.recycle()
        throw JxlException("No frames")
    }
    return bitmap
}

/** Allocates the bitmaps frames are decoded into (a fake on the JVM, where `Bitmap` is a stub). */
internal fun interface BitmapAllocator {
    fun allocate(info: JxlInfo): Bitmap

    companion object {
        val Default =
            BitmapAllocator { info ->
                val colorSpace =
                    try {
                        info.colorSpace.toColorSpace()
                    } catch (e: IllegalArgumentException) {
                        ColorSpace.get(ColorSpace.Named.SRGB)
                    }
                Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888, info.hasAlpha, colorSpace)
            }
    }
}

/** Owns the native handle; the lock keeps [close] (or the [Cleaner]) from freeing it during a call. */
private class NativeHandle(
    private val native: JxlNative,
    private var handle: Long,
) : Runnable {
    val isClosed: Boolean
        @Synchronized get() = handle == 0L

    @Synchronized
    fun <T> use(block: (Long) -> T): T {
        check(handle != 0L) { "JxlImage is closed" }
        return block(handle)
    }

    @Synchronized
    override fun run() {
        val h = handle
        handle = 0L
        if (h != 0L) native.close(h)
    }
}
