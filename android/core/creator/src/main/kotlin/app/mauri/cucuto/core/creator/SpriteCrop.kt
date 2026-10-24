package app.mauri.cucuto.core.creator

import kotlin.math.min
import kotlin.math.roundToInt

/** A square region of a picture, in its pixels. */
data class CropRegion(
    val left: Int,
    val top: Int,
    val size: Int,
)

/**
 * The sprite cropper's state: the picture fills a square viewport ("cover": its short side spans the viewport) and
 * is then zoomed by [zoom] and moved by [panX], [panY] (fractions of the viewport side, positive to the right and
 * down). Sprites are square (docs/pack-format.md: "square pictures look best"), and the app shows them inside its
 * cookie shapes. Pure math, independent of screen density. `aspect` is the picture's width / height.
 */
data class SpriteCrop(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    /** Keeps the viewport inside the picture: zoom within [MIN_ZOOM]..[MAX_ZOOM], pan within reach. */
    fun clamped(aspect: Float): SpriteCrop {
        val z = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        // At zoom z the picture spans z * max(1, aspect) viewports across and z * max(1, 1 / aspect) down.
        val reachX = (z * maxOf(1f, aspect) - 1f) / 2f
        val reachY = (z * maxOf(1f, 1f / aspect) - 1f) / 2f
        // "+ 0f" turns −0 into 0, so equal crops compare equal.
        return SpriteCrop(z, panX.coerceIn(-reachX, reachX) + 0f, panY.coerceIn(-reachY, reachY) + 0f)
    }

    /** This crop after a gesture: zoomed by [zoomChange], moved by [dx], [dy] (viewport fractions), clamped. */
    fun transformed(
        zoomChange: Float,
        dx: Float,
        dy: Float,
        aspect: Float,
    ): SpriteCrop = SpriteCrop(zoom * zoomChange, panX + dx, panY + dy).clamped(aspect)

    /** The visible square in a [width] × [height] picture. */
    fun region(
        width: Int,
        height: Int,
    ): CropRegion {
        val c = clamped(width.toFloat() / height.coerceAtLeast(1))
        val short = min(width, height)
        val size = (short / c.zoom).roundToInt().coerceIn(1, short)
        // One viewport is `size` picture pixels: moving the picture right moves the visible square left.
        val centerX = width / 2f - c.panX * size
        val centerY = height / 2f - c.panY * size
        val left = (centerX - size / 2f).roundToInt().coerceIn(0, width - size)
        val top = (centerY - size / 2f).roundToInt().coerceIn(0, height - size)
        return CropRegion(left, top, size)
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f

        /** Side of the written sprite: the crop's own size up to [maxSide]; small pictures are never enlarged. */
        fun outputSize(
            region: CropRegion,
            maxSide: Int,
        ): Int = min(region.size, maxSide)
    }
}
