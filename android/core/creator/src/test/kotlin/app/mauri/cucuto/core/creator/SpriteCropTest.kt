package app.mauri.cucuto.core.creator

import org.junit.Assert.assertEquals
import org.junit.Test

class SpriteCropTest {
    @Test
    fun `the default crop is the centered square of the short side`() {
        assertEquals(CropRegion(left = 200, top = 0, size = 600), SpriteCrop().region(1_000, 600))
        assertEquals(CropRegion(left = 0, top = 200, size = 600), SpriteCrop().region(600, 1_000))
        assertEquals(CropRegion(0, 0, 512), SpriteCrop().region(512, 512))
    }

    @Test
    fun `zooming in takes a smaller square around the center`() {
        assertEquals(CropRegion(left = 350, top = 150, size = 300), SpriteCrop(zoom = 2f).region(1_000, 600))
    }

    @Test
    fun `moving the picture right shows more of its left side, never past the edge`() {
        // At zoom 1 a 1000 x 600 picture spans 1.67 viewports across: it moves at most a third of a viewport.
        assertEquals(CropRegion(left = 0, top = 0, size = 600), SpriteCrop(panX = 5f).region(1_000, 600))
        assertEquals(CropRegion(left = 400, top = 0, size = 600), SpriteCrop(panX = -5f).region(1_000, 600))
        assertEquals(CropRegion(left = 140, top = 0, size = 600), SpriteCrop(panX = 0.1f).region(1_000, 600))
        // A square picture at zoom 1 cannot move at all.
        assertEquals(SpriteCrop(1f, 0f, 0f), SpriteCrop(panX = 0.3f, panY = -0.3f).clamped(aspect = 1f))
    }

    @Test
    fun `gestures compose and clamp`() {
        val zoomed = SpriteCrop().transformed(zoomChange = 4f, dx = 0f, dy = 0f, aspect = 1f)
        assertEquals(4f, zoomed.zoom)
        val moved = zoomed.transformed(1f, dx = 10f, dy = -10f, aspect = 1f)
        assertEquals(SpriteCrop(4f, 1.5f, -1.5f), moved)
        assertEquals(SpriteCrop.MAX_ZOOM, moved.transformed(100f, 0f, 0f, 1f).zoom)
        assertEquals(SpriteCrop.MIN_ZOOM, moved.transformed(0.01f, 0f, 0f, 1f).zoom)
        assertEquals(CropRegion(left = 0, top = 375, size = 125), moved.region(500, 500))
    }

    @Test
    fun `sprites are written at most 512 px and never enlarged`() {
        assertEquals(512, SpriteCrop.outputSize(CropRegion(0, 0, 1_800), 512))
        assertEquals(96, SpriteCrop.outputSize(CropRegion(0, 0, 96), 512))
    }
}
