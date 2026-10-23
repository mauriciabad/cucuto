package app.mauri.cucuto.core.pack.media

import app.mauri.cucuto.core.jxl.JxlImage
import app.mauri.cucuto.core.jxl.JxlInfo
import app.mauri.cucuto.core.jxl.JxlTooLargeException
import app.mauri.cucuto.core.jxl.JxlUnavailableException
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.Limits
import java.io.File

/**
 * [ImageProbe] for JPEG XL with the jxl-rs decoder of `:core:jxl` (Android 17 has no platform decoder, ADR 0006).
 * Every frame is decoded, so an animation that breaks halfway is caught at import; an oversized image is reported
 * from its header (IMAGE_TOO_LARGE) without allocating its pixels. Any decoder error, even a panic in the native
 * code, is a broken image.
 */
class JxlImageProbe internal constructor(
    private val verify: (data: ByteArray, maxSide: Int) -> JxlInfo,
) : ImageProbe {
    constructor() : this({ data, maxSide -> JxlImage.verify(data, maxSide) })

    override fun probe(
        file: File,
        format: FileFormat,
    ): ImageProbeResult =
        try {
            val info = verify(file.readBytes(), Limits.MAX_BITMAP_SIDE_PX)
            ImageProbeResult.Decoded(info.width, info.height)
        } catch (e: JxlTooLargeException) {
            ImageProbeResult.Decoded(e.width, e.height)
        } catch (e: JxlUnavailableException) {
            ImageProbeResult.NotChecked(e.message ?: "No JPEG XL decoder")
        } catch (e: Exception) {
            ImageProbeResult.Undecodable(e.message ?: e.javaClass.simpleName)
        }
}
