package fr.nicovers06.streamstudio.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizedRectTest {
    @Test
    fun `media aspect keeps frame height and horizontal center`() {
        val initial = NormalizedRect(x = 0.18f, y = 0.18f, width = 0.64f, height = 0.64f)

        val adjusted = initial.withPixelAspectKeepingHeight(
            pixelAspect = 9f / 16f,
            sceneWidth = 16,
            sceneHeight = 9,
        )

        assertEquals(initial.height, adjusted.height, 0.0001f)
        assertEquals(initial.y, adjusted.y, 0.0001f)
        assertEquals(initial.x + initial.width / 2f, adjusted.x + adjusted.width / 2f, 0.0001f)
        assertEquals(9f / 16f, adjusted.pixelAspect(16, 9), 0.0001f)
    }

    @Test
    fun `media aspect remains inside the scene when requested width is too large`() {
        val initial = NormalizedRect(x = 0.25f, y = 0.10f, width = 0.50f, height = 0.80f)

        val adjusted = initial.withPixelAspectKeepingHeight(
            pixelAspect = 4f,
            sceneWidth = 16,
            sceneHeight = 9,
        )

        assertEquals(initial.height, adjusted.height, 0.0001f)
        assertEquals(1f, adjusted.width, 0.0001f)
        assertEquals(0f, adjusted.x, 0.0001f)
    }
}
