package app.mauri.cucuto.core.pack.media

import android.graphics.ImageDecoder
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.Limits
import java.io.File

/**
 * [ImageProbe] with [ImageDecoder] (PNG, JPEG, WebP, GIF, AVIF; the first frame of animations). JPEG XL goes to
 * [jxl], the jxl-rs decoder the app also displays it with (Android 17 has no platform decoder, ADR 0006). SVG has no
 * platform decoder: pack-spec parses it and applies the SVG safety rules, and the app renders it without scripting or
 * network.
 */
class PlatformImageProbe(
    private val jxl: ImageProbe = JxlImageProbe(),
) : ImageProbe {
    override fun probe(
        file: File,
        format: FileFormat,
    ): ImageProbeResult =
        when (format) {
            FileFormat.SVG -> ImageProbeResult.NotChecked("SVG is parsed and checked by the SVG safety rules")
            FileFormat.JXL -> jxl.probe(file, format)
            else -> decode(file)
        }

    private fun decode(file: File): ImageProbeResult {
        var width = 0
        var height = 0
        return try {
            val bitmap =
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    width = info.size.width
                    height = info.size.height
                    // Never allocate for an oversized picture (reported as IMAGE_TOO_LARGE from its size).
                    val max = Limits.MAX_BITMAP_SIDE_PX
                    if (width > max || height > max) throw OversizedException()
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            bitmap.recycle()
            ImageProbeResult.Decoded(width, height)
        } catch (e: OversizedException) {
            ImageProbeResult.Decoded(width, height)
        } catch (e: Exception) {
            ImageProbeResult.Undecodable(e.message ?: e.javaClass.simpleName)
        }
    }

    private class OversizedException : RuntimeException()
}
