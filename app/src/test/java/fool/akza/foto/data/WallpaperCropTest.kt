package fool.akza.foto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the wallpaper crop geometry: the frame must always keep
 * the chosen orientation's aspect, stay inside the image, and map onto the
 * source pixels exactly.
 */
class WallpaperCropTest {

    private val epsilon = 1e-4f

    @Test
    fun `fit fills the image and keeps the frame aspect`() {
        // A 4:3 photo (aspect 1.333) in a 9:16 portrait frame.
        val crop = fitWallpaperCrop(frameAspect = 9f / 16f, imageAspect = 4f / 3f)

        assertEquals(1f, crop.height, epsilon)
        assertEquals((9f / 16f) / (4f / 3f), crop.width, epsilon)
        // Centered horizontally, full height.
        assertEquals(0f, crop.top, epsilon)
        assertEquals(1f, crop.bottom, epsilon)
        assertEquals(crop.left, 1f - crop.right, epsilon)
    }

    @Test
    fun `fit fills the short side for a landscape frame on a portrait photo`() {
        // A 3:4 photo (aspect 0.75) in a 16:9 landscape frame.
        val crop = fitWallpaperCrop(frameAspect = 16f / 9f, imageAspect = 3f / 4f)

        assertEquals(1f, crop.width, epsilon)
        assertEquals((3f / 4f) / (16f / 9f), crop.height, epsilon)
        assertEquals(0f, crop.left, epsilon)
        assertEquals(1f, crop.right, epsilon)
        assertEquals(crop.top, 1f - crop.bottom, epsilon)
    }

    @Test
    fun `pinching in shrinks the crop around the focus point`() {
        val frame = 9f / 16f
        val image = 4f / 3f
        val start = fitWallpaperCrop(frame, image)
        val focusX = 0.5f
        val focusY = 0.5f

        val zoomed = transformWallpaperCrop(
            crop = start,
            frameAspect = frame,
            imageAspect = image,
            focusX = focusX,
            focusY = focusY,
            panX = 0f,
            panY = 0f,
            zoom = 2f,
        )

        assertEquals(start.width / 2f, zoomed.width, epsilon)
        // The point under the fingers stays put: the center.
        assertEquals(focusX, (zoomed.left + zoomed.right) / 2f, epsilon)
        assertEquals(focusY, (zoomed.top + zoomed.bottom) / 2f, epsilon)
    }

    @Test
    fun `panning far past the edge clamps the crop inside the image`() {
        val frame = 9f / 16f
        val image = 4f / 3f
        val start = fitWallpaperCrop(frame, image)
        val zoomed = transformWallpaperCrop(
            crop = start,
            frameAspect = frame,
            imageAspect = image,
            focusX = 0.5f,
            focusY = 0.5f,
            panX = 0f,
            panY = 0f,
            zoom = 2f,
        )

        val panned = transformWallpaperCrop(
            crop = zoomed,
            frameAspect = frame,
            imageAspect = image,
            focusX = 0.5f,
            focusY = 0.5f,
            panX = 10f,
            panY = 10f,
            zoom = 1f,
        )

        assertEquals(1f, panned.right, epsilon)
        assertEquals(1f, panned.bottom, epsilon)
        assertTrue(panned.left >= 0f)
        assertTrue(panned.top >= 0f)
    }

    @Test
    fun `pinching out stops at the maximum frame`() {
        val frame = 9f / 16f
        val image = 4f / 3f
        val start = fitWallpaperCrop(frame, image)

        val out = transformWallpaperCrop(
            crop = start,
            frameAspect = frame,
            imageAspect = image,
            focusX = 0.5f,
            focusY = 0.5f,
            panX = 0f,
            panY = 0f,
            zoom = 0.25f,
        )

        assertEquals(start.width, out.width, epsilon)
        assertEquals(start.height, out.height, epsilon)
    }

    @Test
    fun `crop maps onto the source pixels`() {
        val region = NormalizedCrop(0.25f, 0.5f, 0.75f, 1f).toCropRegion(1000, 800)

        assertEquals(250, region.left)
        assertEquals(400, region.top)
        assertEquals(500, region.width)
        assertEquals(400, region.height)
    }
}
