package app.mauri.cucuto.core.pack.media

import android.graphics.ImageDecoder
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.Limits
import java.io.File

/**
 * [ImageProbe] with [ImageDecoder] (PNG, JPEG, WebP, GIF, AVIF; the first frame of animations). SVG has no platform
 * decoder: pack-spec parses it and applies the SVG safety rules, and the app renders it without scripting or network.
 */
class PlatformImageProbe : ImageProbe {
    override fun probe(
        file: File,
        format: FileFormat,
    ): ImageProbeResult =
        when {
            format == FileFormat.SVG -> {
                ImageProbeResult.NotChecked("SVG is parsed and checked by the SVG safety rules")
            }

            // TODO(#77): Android 17 has no JPEG XL decoder (ADR 0006, spike #7). Test-decode JXL with the jxl-rs wrapper of
            //  :core:jxl behind this probe once it exists; until then JXL gets the header checks of pack-spec only.
            format == FileFormat.JXL && !ImageDecoder.isMimeTypeSupported(format.mimeType) -> {
                ImageProbeResult.NotChecked("No JPEG XL decoder yet (#77)")
            }

            else -> {
                decode(file)
            }
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
