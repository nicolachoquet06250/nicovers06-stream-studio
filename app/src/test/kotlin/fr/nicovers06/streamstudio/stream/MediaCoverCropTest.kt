package fr.nicovers06.streamstudio.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCoverCropTest {
    @Test
    fun `wide video is cropped horizontally inside a square free frame`() {
        val crop = MediaCoverCrop.centered(
            sourceAspect = 16f / 9f,
            targetAspect = 1f,
        )

        assertEquals(9f / 16f, crop.scaleX, 0.0001f)
        assertEquals(1f, crop.scaleY, 0.0001f)
        assertEquals((1f - 9f / 16f) / 2f, crop.offsetX, 0.0001f)
        assertEquals(0f, crop.offsetY, 0.0001f)
    }

    @Test
    fun `wide free frame crops a portrait video vertically`() {
        val crop = MediaCoverCrop.centered(
            sourceAspect = 9f / 16f,
            targetAspect = 16f / 9f,
        )

        assertEquals(1f, crop.scaleX, 0.0001f)
        assertEquals(81f / 256f, crop.scaleY, 0.0001f)
        assertEquals(0f, crop.offsetX, 0.0001f)
        assertEquals((1f - 81f / 256f) / 2f, crop.offsetY, 0.0001f)
    }

    @Test
    fun `matching ratios keep the complete video texture`() {
        val crop = MediaCoverCrop.centered(
            sourceAspect = 16f / 9f,
            targetAspect = 16f / 9f,
        )

        assertEquals(MediaCoverCrop.FULL, crop)
    }
}
