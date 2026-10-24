package app.mauri.cucuto.core.creator

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import app.mauri.cucuto.core.jxl.JxlImage
import app.mauri.cucuto.packspec.authoring.Authoring
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * Pictures for sprites and covers: decode what the photo picker returns (PNG, JPEG, WebP, GIF, HEIF, AVIF and, through
 * `:core:jxl`, JPEG XL; the first frame of animations), crop the square the user framed, and write it as WebP: lossless
 * when the picture has transparency (sprites and pixel art stay crisp), lossy at quality [LOSSY_QUALITY] for photos.
 * The written side is at most [Authoring.Image.SPRITE_SIZE_PX] (the format's bitmap limit) and never enlarged.
 * Blocking: call off the main thread.
 */
object SpriteImages {
    /** Pictures are decoded at most this big for cropping (a 50 MP photo would not fit in memory). */
    const val MAX_DECODE_SIDE = 2_048
    const val LOSSY_QUALITY = 90
    private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024

    /** Decodes [uri] as a software bitmap no larger than [MAX_DECODE_SIDE]. Throws [IOException] when it cannot. */
    fun decode(
        resolver: ContentResolver,
        uri: Uri,
    ): Bitmap {
        val head =
            resolver.openInputStream(uri)?.use { it.readNBytes(JXL_HEADER_BYTES) } ?: throw IOException("Cannot open")
        if (JxlImage.isJxl(head)) {
            val bytes =
                resolver.openInputStream(uri)?.use { it.readNBytes(MAX_SOURCE_BYTES.toInt()) }
                    ?: throw IOException("Cannot open")
            return JxlImage.decodeBitmap(bytes, MAX_DECODE_SIDE)
        }
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_DECODE_SIDE) {
                    val scale = MAX_DECODE_SIDE.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Cannot decode the picture: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** The square [crop] of [bitmap], scaled down to at most [maxSide]. */
    fun render(
        bitmap: Bitmap,
        crop: SpriteCrop,
        maxSide: Int = Authoring.Image.SPRITE_SIZE_PX,
    ): Bitmap {
        val region = crop.region(bitmap.width, bitmap.height)
        val side = SpriteCrop.outputSize(region, maxSide)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            bitmap,
            Rect(region.left, region.top, region.left + region.size, region.top + region.size),
            Rect(0, 0, side, side),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return out
    }

    /** Writes [bitmap] as WebP to [file]; lossless when any pixel is transparent. */
    fun writeWebp(
        bitmap: Bitmap,
        file: File,
    ) {
        val format =
            if (hasTransparency(
                    bitmap,
                )
            ) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP_LOSSY
            }
        val quality = if (format == Bitmap.CompressFormat.WEBP_LOSSLESS) LOSSLESS_EFFORT else LOSSY_QUALITY
        file.outputStream().buffered().use { out ->
            if (!bitmap.compress(format, quality, out)) throw IOException("Cannot write the picture")
        }
    }

    private fun hasTransparency(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.any { (it ushr ALPHA_SHIFT) != OPAQUE_ALPHA }
    }

    private const val JXL_HEADER_BYTES = 16

    /** For lossless WebP the "quality" is the compression effort; 100 is the smallest file. */
    private const val LOSSLESS_EFFORT = 100
    private const val ALPHA_SHIFT = 24
    private const val OPAQUE_ALPHA = 0xFF
}
