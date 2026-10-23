package app.mauri.cucuto.core.jxl

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okio.BufferedSource

/**
 * Coil 3 [Decoder] for JPEG XL with jxl-rs. A still image becomes a bitmap tagged with its color space; an animated
 * one becomes a [JxlAnimatedDrawable]. Images over [maxSide] px per side fail from their header, before any pixel
 * memory is allocated; sprites and covers are at most 512 px, so there is no subsampling (Compose scales at draw
 * time). Register [Factory] in the app's `ImageLoader`.
 */
class JxlCoilDecoder(
    private val source: ImageSource,
    private val maxSide: Int = JxlImage.DEFAULT_MAX_SIDE,
) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = runInterruptible { source.source().use { it.readCapped() } }
        currentCoroutineContext().ensureActive()
        val image = JxlImage.open(bytes, maxSide)
        try {
            currentCoroutineContext().ensureActive()
            val first = image.newBitmap()
            val firstMs = image.decodeNextFrame(first) ?: throw JxlException("No frames")
            if (!image.info.isAnimated || image.info.frameCount == 1) {
                image.close()
                return DecodeResult(image = first.asImage(), isSampled = false)
            }
            val drawable = JxlAnimatedDrawable(bytes, maxSide, image, first, firstMs)
            return DecodeResult(image = drawable.asImage(), isSampled = false)
        } catch (e: Throwable) {
            image.close()
            throw e
        }
    }

    /** Claims sources served as `image/jxl` or starting with a JPEG XL signature. */
    class Factory(
        private val maxSide: Int = JxlImage.DEFAULT_MAX_SIDE,
    ) : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? =
            if (isJxl(result.mimeType, result.source.source())) JxlCoilDecoder(result.source, maxSide) else null

        internal companion object {
            fun isJxl(
                mimeType: String?,
                source: BufferedSource,
            ): Boolean = mimeType == JxlImage.MIME_TYPE || JxlSignature.all.any { source.rangeEquals(0, it) }
        }
    }

    internal companion object {
        /** No sprite or cover comes close; this only stops a hostile source from exhausting memory. */
        const val MAX_FILE_BYTES = 32L * 1024 * 1024

        fun BufferedSource.readCapped(): ByteArray {
            if (request(MAX_FILE_BYTES + 1)) throw JxlException("File larger than $MAX_FILE_BYTES bytes")
            return readByteArray()
        }
    }
}
