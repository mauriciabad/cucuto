package app.mauri.cucuto.ui.theme

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.scale
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Picks the seed color of a sprite the same way Android picks one from the wallpaper: Material Color Utilities'
 * Celebi quantizer (Wu + WSMeans) followed by `Score`, which prefers colorful, common hues and rejects near-greys.
 * Runs on [Dispatchers.Default]; never call the pixel work on the main thread (brief: smooth at 120 Hz).
 *
 * Transparent pixels (most sprites have a transparent background) are ignored. Returns null when the image has no
 * usable color (an all-grey or fully transparent sprite): the caller then keeps the surrounding scheme.
 * See docs/decisions/0014-theme-and-feedback-engine.md for why this is MCU and not androidx.palette.
 */
suspend fun extractSeedColor(bitmap: Bitmap): Color? =
    withContext(Dispatchers.Default) {
        val pixels = bitmap.samplePixels()
        val opaque = pixels.filter { (it ushr ALPHA_SHIFT) >= MIN_ALPHA }.map { it or OPAQUE }.toIntArray()
        if (opaque.isEmpty()) return@withContext null
        val quantized = QuantizerCelebi.quantize(opaque, MAX_COLORS)
        Score.score(quantized, desired = 1, fallbackColorArgb = null).firstOrNull()?.let { Color(it) }
    }

/** Seed color of [bitmap] as Compose state: null until extraction finishes (or when there is no usable color). */
@Composable
fun rememberSeedColor(bitmap: Bitmap?): State<Color?> =
    produceState<Color?>(initialValue = null, bitmap) {
        value = bitmap?.let { extractSeedColor(it) }
    }

/**
 * Seed color of the sprite in [sprite] as Compose state, from a small software decode of its own ([SAMPLE_EDGE] px)
 * through the app's ImageLoader. For screens that show many sprites but theme around one (the pickers): their cells
 * keep Coil's hardware bitmaps, and only the character in focus is decoded again for its colors (#330).
 */
@Composable
fun rememberSpriteSeedColor(sprite: File?): State<Color?> {
    val context = LocalPlatformContext.current
    return produceState<Color?>(initialValue = null, sprite) {
        value =
            sprite?.let { file ->
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(file)
                        .size(SAMPLE_EDGE)
                        .allowHardware(false)
                        .build()
                val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                result?.image?.toBitmap()?.let { extractSeedColor(it) }
            }
    }
}

/** Downscales to at most [SAMPLE_EDGE] px on the long edge (quantizing every pixel of a big sprite is wasted work). */
private fun Bitmap.samplePixels(): IntArray {
    val source = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    val longEdge = max(source.width, source.height)
    val sampled =
        if (longEdge <= SAMPLE_EDGE) {
            source
        } else {
            val ratio = SAMPLE_EDGE.toFloat() / longEdge
            source.scale(
                (source.width * ratio).roundToInt().coerceAtLeast(1),
                (source.height * ratio).roundToInt().coerceAtLeast(1),
            )
        }
    val pixels = IntArray(sampled.width * sampled.height)
    sampled.getPixels(pixels, 0, sampled.width, 0, 0, sampled.width, sampled.height)
    return pixels
}

private const val SAMPLE_EDGE = 112
private const val MAX_COLORS = 128
private const val ALPHA_SHIFT = 24
private const val MIN_ALPHA = 128
private const val OPAQUE = 0xFF000000.toInt()
