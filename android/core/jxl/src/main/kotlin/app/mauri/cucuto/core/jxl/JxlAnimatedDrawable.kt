package app.mauri.cucuto.core.jxl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An animated JPEG XL image, decoded just in time: one native decoder and two bitmaps (the frame on screen and the
 * next one, decoded in the background while the current one shows), whatever the frame count. Plays
 * [JxlInfo.loopCount] times (0: forever) and then keeps the last frame.
 *
 * The decoder only exists while the animation runs: [stop] (Coil and Compose call it when the image leaves the
 * screen) and the end of the last loop release it and the second bitmap; [start] reopens the file from its bytes and
 * plays from the first frame. A decoding error mid-animation stops on the last good frame.
 */
class JxlAnimatedDrawable internal constructor(
    private val data: ByteArray,
    private val maxSide: Int,
    image: JxlImage,
    firstFrame: Bitmap,
    firstFrameMs: Int,
) : Drawable(),
    Animatable {
    val info: JxlInfo = image.info

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** One decode at a time per drawable; touched only on this dispatcher: [decoder], [spare], [loops]. */
    private val decodeDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var decoder: JxlImage? = image
    private var spare: Bitmap? = null
    private var loops = LoopCounter(info.loopCount)

    // Main thread only.
    private var front: Bitmap = firstFrame
    private var frontMs: Int = firstFrameMs
    private var job: Job? = null

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch { animate() }
    }

    override fun stop() {
        job?.cancel()
        job = null
        release()
    }

    override fun isRunning(): Boolean = job?.isActive == true

    private suspend fun animate() {
        var showAt = SystemClock.uptimeMillis() + frameDelayMs(frontMs)
        while (true) {
            val next =
                try {
                    withContext(decodeDispatcher) { decodeNext() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // A broken frame: keep the last good one.
                } ?: break
            delay(showAt - SystemClock.uptimeMillis())
            val (bitmap, ms) = next
            val previous = front
            front = bitmap
            frontMs = ms
            withContext(decodeDispatcher) { spare = previous }
            invalidateSelf()
            showAt = SystemClock.uptimeMillis() + frameDelayMs(ms)
        }
        release()
        job = null
    }

    /** On [decodeDispatcher]: the next frame into the spare bitmap, or null when the last loop is over. */
    private fun decodeNext(): Pair<Bitmap, Int>? {
        val image =
            decoder ?: JxlImage.open(data, maxSide).also {
                // Reopened after stop(): play from the first frame, all loops again.
                decoder = it
                loops = LoopCounter(info.loopCount)
            }
        val target = spare ?: image.newBitmap()
        spare = null
        var ms = image.decodeNextFrame(target)
        if (ms == null && loops.repeatAfterLoop()) {
            image.rewind()
            ms = image.decodeNextFrame(target)
        }
        if (ms == null) {
            spare = target
            return null
        }
        return target to ms
    }

    /** Frees the decoder and the spare bitmap once any frame in progress is done. */
    private fun release() {
        scope.launch(decodeDispatcher + NonCancellable) {
            decoder?.close()
            decoder = null
            spare = null
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(front, null, bounds, paint)
    }

    override fun getIntrinsicWidth(): Int = info.width

    override fun getIntrinsicHeight(): Int = info.height

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = if (info.hasAlpha) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
}

/** Counts finished loops of an animation that plays [loopCount] times (0: forever). */
internal class LoopCounter(
    private val loopCount: Int,
) {
    private var finished = 0

    /** Call at the end of each loop: whether to play another one. */
    fun repeatAfterLoop(): Boolean {
        finished++
        return loopCount <= 0 || finished < loopCount
    }
}

/** Frames shorter than 10 ms (including 0) would spin; browsers clamp them too. */
internal fun frameDelayMs(durationMs: Int): Long = durationMs.coerceAtLeast(MIN_FRAME_MS).toLong()

internal const val MIN_FRAME_MS = 10
