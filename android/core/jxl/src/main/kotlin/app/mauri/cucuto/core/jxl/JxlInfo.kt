package app.mauri.cucuto.core.jxl

import android.graphics.ColorSpace
import java.io.IOException

/** What a JPEG XL header says, read without decoding pixels ([JxlImage.readInfo]). */
data class JxlInfo(
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
    val isAnimated: Boolean,
    /** How many times an animation plays; 0 means forever (JPEG XL semantics). */
    val loopCount: Int,
    val colorSpace: JxlColorSpace,
    /** Visible frames (1 for a still image), or null when the image is too large and its frames were not scanned. */
    val frameCount: Int?,
) {
    /** Throws [JxlTooLargeException] when a side is over [maxSide]. */
    fun requireWithin(maxSide: Int) {
        if (width > maxSide || height > maxSide) throw JxlTooLargeException(width, height, maxSide)
    }

    internal companion object {
        /** Parses `nativeReadInfo` (rust/src/android.rs): width, height, alpha, animated, loops, color, frames. */
        fun fromNative(values: IntArray): JxlInfo {
            require(values.size == 7) { "Expected 7 header values, got ${values.size}" }
            return JxlInfo(
                width = values[0],
                height = values[1],
                hasAlpha = values[2] != 0,
                isAnimated = values[3] != 0,
                loopCount = values[4],
                colorSpace = JxlColorSpace.fromCode(values[5]),
                frameCount = values[6].takeIf { it >= 0 },
            )
        }
    }
}

/**
 * The color space of the decoded pixels. jxl-rs outputs lossy (XYB) files in sRGB, or in their embedded color space
 * when it is a simple one, and lossless files in their embedded color space; bitmaps are tagged accordingly, so
 * a Display P3 sprite shows in Display P3.
 */
enum class JxlColorSpace(
    internal val code: Int,
) {
    /** An ICC profile or an encoding Android has no named space for; shown as sRGB. */
    UNKNOWN(0),
    SRGB(1),
    LINEAR_SRGB(2),
    DISPLAY_P3(3),

    /** BT.2020 primaries with the BT.709 transfer function. */
    BT2020(4),
    ;

    /** The Android color space to tag bitmaps with ([UNKNOWN] is shown as sRGB). */
    fun toColorSpace(): ColorSpace =
        ColorSpace.get(
            when (this) {
                UNKNOWN, SRGB -> ColorSpace.Named.SRGB
                LINEAR_SRGB -> ColorSpace.Named.LINEAR_SRGB
                DISPLAY_P3 -> ColorSpace.Named.DISPLAY_P3
                BT2020 -> ColorSpace.Named.BT2020
            },
        )

    internal companion object {
        fun fromCode(code: Int): JxlColorSpace = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** A JPEG XL file could not be decoded: corrupt, truncated, unsupported or too large. Never a crash. */
open class JxlException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** A side of the image is over the limit; thrown from the header, before any pixel memory is allocated. */
class JxlTooLargeException(
    val width: Int,
    val height: Int,
    val maxSide: Int,
) : JxlException("Image is ${width}x$height px; at most $maxSide px per side")

/** The native decoder could not be loaded (for example on an ABI other than arm64-v8a). */
class JxlUnavailableException(
    cause: Throwable,
) : JxlException("The JPEG XL decoder is not available on this device", cause)
