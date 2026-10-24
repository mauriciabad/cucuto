package app.mauri.cucuto.video

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.mauri.cucuto.ui.theme.CucutoTypography
import app.mauri.cucuto.ui.theme.LightColorScheme
import coil3.ImageLoader
import coil3.executeBlocking
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.svg.SvgDecoder
import coil3.toBitmap
import java.io.File
import kotlin.math.roundToInt

/**
 * The repository the video reads its art from: the demo packs' sprites and covers (`packs/`, CC0) and the brand's
 * logo (`brand/`). Tests get it from the `cucuto.repository` system property; otherwise it is found from the working
 * directory.
 */
internal object Repository {
    val root: File by lazy {
        System.getProperty("cucuto.repository")?.let(::File)
            ?: generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "docs/explainer-video.md").isFile }
            ?: File("")
    }

    fun file(path: String): File = File(root, path)
}

/**
 * SVG art drawn as a bitmap, decoded once and synchronously (so the first frame that shows it is complete). Sizes are
 * rounded up to a few steps so a growing sprite reuses the same bitmap.
 */
internal object SvgArt {
    private val cache = HashMap<Pair<String, Int>, ImageBitmap?>()
    private var loader: ImageLoader? = null

    fun get(
        context: Context,
        path: String,
        widthPx: Int,
    ): ImageBitmap? =
        cache.getOrPut(path to widthPx) {
            val imageLoader =
                loader ?: ImageLoader
                    .Builder(context)
                    .components { add(SvgDecoder.Factory()) }
                    .build()
                    .also { loader = it }
            val request =
                ImageRequest
                    .Builder(context)
                    .data(Repository.file(path))
                    .size(widthPx, widthPx * MAX_ASPECT)
                    .precision(Precision.EXACT)
                    .build()
            (imageLoader.executeBlocking(request) as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
        }

    /** Sprites render at one of these widths (px), the smallest that is at least as wide as asked. */
    fun bucket(px: Int): Int = BUCKETS.firstOrNull { it >= px } ?: BUCKETS.last()

    private val BUCKETS = listOf(128, 256, 512, 1024)
    private const val MAX_ASPECT = 4
}

/** SVG art from the repository ([path]), [width] wide, keeping its aspect ratio. Draws nothing if the file is missing. */
@Composable
internal fun Svg(
    path: String,
    width: Dp,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val px = with(density) { width.toPx() }.roundToInt()
    val bitmap = remember(path, px) { SvgArt.get(context, path, SvgArt.bucket(px)) }
    val height = if (bitmap == null) width else width * (bitmap.height.toFloat() / bitmap.width)
    Canvas(modifier.size(width, height)) {
        if (bitmap == null) return@Canvas
        drawImage(
            bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            alpha = alpha,
            filterQuality = FilterQuality.High,
        )
    }
}

/** A demo-pack character's sprite, [size] square. */
@Composable
internal fun Sprite(
    character: Character,
    size: Dp,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) = Svg(character.sprite, size, modifier, alpha)

/** The brand's light color scheme and type scale (brand/tokens), as the app uses them when dynamic color is off. */
@Composable
internal fun VideoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColorScheme, typography = CucutoTypography, content = content)
}
