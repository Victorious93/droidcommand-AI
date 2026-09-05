package ai.droidcommand.tools.android

import kotlin.test.Test
import kotlin.test.assertEquals

class RectTest {
    @Test
    fun `computes width and height`() {
        val rect = Rect(left = 10, top = 20, right = 110, bottom = 70)
        assertEquals(100, rect.width)
        assertEquals(50, rect.height)
    }

    @Test
    fun `computes center point`() {
        val rect = Rect(left = 0, top = 0, right = 100, bottom = 50)
        assertEquals(50, rect.centerX)
        assertEquals(25, rect.centerY)
    }

    @Test
    fun `computes center point for a non-origin rect`() {
        val rect = Rect(left = 10, top = 10, right = 30, bottom = 30)
        assertEquals(20, rect.centerX)
        assertEquals(20, rect.centerY)
    }
}
