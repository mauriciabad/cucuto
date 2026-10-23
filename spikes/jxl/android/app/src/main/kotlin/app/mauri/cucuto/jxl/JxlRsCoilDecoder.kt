package app.mauri.cucuto.jxl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import java.util.concurrent.Executors
import okio.ByteString.Companion.toByteString

/**
 * Coil 3 decoder for JPEG XL backed by jxl-rs (sketch for the spike, issue #7).
 *
 * Static files become a plain bitmap. Animated files become a [JxlAnimatedDrawable] that keeps
 * one native decoder and two bitmaps and decodes each frame just in time on a worker thread.
 * Sprites are at most 512 px, so no subsampling is done here; Coil/Compose scale at draw time.
 */
class JxlRsCoilDecoder(
    private val source: ImageSource,
    @Suppress("unused") private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val image = JxlImage(bytes)
        return if (!image.isAnimated) {
            image.use { DecodeResult(image = it.newBitmap().also { b -> it.decodeNextFrame(b) }.asImage(), isSampled = false) }
        } else {
            DecodeResult(image = JxlAnimatedDrawable(image).asImage(), isSampled = false)
        }
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val src = result.source.source()
            val isJxl = result.mimeType == "image/jxl" ||
                JxlImage.signatures.any { src.rangeEquals(0, it.toByteString()) }
            return if (isJxl) JxlRsCoilDecoder(result.source, options) else null
        }
    }
}

/** Just-in-time animated JPEG XL drawable: one decoder, double-buffered bitmaps. */
class JxlAnimatedDrawable(private val image: JxlImage) : Drawable(), Animatable {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "jxl-anim").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    @Volatile private var front: Bitmap = image.newBitmap()
    private var back: Bitmap = image.newBitmap()
    private var frontDuration: Int = image.decodeNextFrame(front) ?: 0
    private var loopsDone = 0
    private var running = false

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(front, null, bounds, paint)
    }

    override fun start() {
        if (running) return
        running = true
        scheduleNext(SystemClock.uptimeMillis() + frontDuration)
    }

    override fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
    }

    override fun isRunning() = running

    private fun scheduleNext(showAt: Long) {
        worker.execute {
            val target = back
            var duration = image.decodeNextFrame(target)
            if (duration == null) { // end of animation: loop or stop
                loopsDone++
                if (image.loopCount != 0 && loopsDone >= image.loopCount) {
                    main.post { stop() }
                    return@execute
                }
                image.rewind()
                duration = image.decodeNextFrame(target) ?: return@execute
            }
            main.postAtTime({
                if (!running) return@postAtTime
                back = front
                front = target
                invalidateSelf()
                scheduleNext(SystemClock.uptimeMillis() + duration)
            }, showAt)
        }
    }

    /** Releases the native decoder; call when the drawable is no longer displayed. */
    fun release() {
        stop()
        worker.execute { image.close() }
        worker.shutdown()
    }

    override fun getIntrinsicWidth() = image.width
    override fun getIntrinsicHeight() = image.height
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = if (image.hasAlpha) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
}
