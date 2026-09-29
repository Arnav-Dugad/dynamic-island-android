package com.arnav.island.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandGeometryTest {

    /** Galaxy S23+ class screen: 1080 x 2340 at density 2.8125 with a centred punch-hole. */
    private val screen = ScreenSpec(
        widthPx = 1080,
        heightPx = 2340,
        density = 2.8125f,
        statusBarHeightPx = 110,
        displayCornerRadiusPx = 100f,
    )
    private val cutout = CutoutSpec(centerX = 540f, centerY = 62f, radius = 26f, source = CutoutSource.CUTOUT_RECT)

    private fun geometry(config: GeometryConfig = GeometryConfig()) = IslandGeometry(screen, cutout, config)

    @Test
    fun `idle camera disc is centred on the camera and covers it`() {
        val g = geometry()
        val idle = g.idleFrame()
        assertEquals(540f, idle.centerX, 0.001f)
        assertEquals(62f, idle.centerY, 0.001f)
        assertTrue(idle.width / 2f >= cutout.radius)
        assertEquals(idle.width / 2f, idle.radius, 0.001f)
    }

    @Test
    fun `compact pill contains the camera and is a stadium`() {
        val g = geometry()
        val c = g.compactFrame()
        assertTrue(c.top <= g.cameraY - g.holeRadius)
        assertTrue(c.bottom >= g.cameraY + g.holeRadius)
        assertTrue(c.left < g.anchorX - g.cameraExclusionHalfWidth)
        assertTrue(c.right > g.anchorX + g.cameraExclusionHalfWidth)
        assertEquals(c.height / 2f, c.radius, 0.001f)
    }

    @Test
    fun `manual offsets move the anchor by whole pixels`() {
        val g = geometry(GeometryConfig(offsetXPx = 3f, offsetYPx = -2f))
        assertEquals(543f, g.anchorX, 0.001f)
        assertEquals(60f, g.cameraY, 0.001f)
        assertEquals(543f, g.idleFrame().centerX, 0.001f)
    }

    @Test
    fun `expanded frames grow downward from the shared top edge`() {
        val g = geometry()
        val compact = g.compactFrame()
        val expanded = g.frameFor(g.expandedWidth, g.dp(200f))
        assertEquals(compact.top, expanded.top, 0.001f)
        assertTrue(expanded.width <= screen.widthPx - 2 * g.edgeMargin + 0.01f)
        assertTrue(expanded.radius < expanded.height / 2f)
    }

    @Test
    fun `explicit sizes are respected`() {
        val g = geometry(GeometryConfig(compactWidthDp = 200f, compactHeightDp = 36f))
        assertEquals(200f * screen.density, g.compactWidth, 0.01f)
        assertEquals(36f * screen.density, g.compactHeight, 0.01f)
    }

    @Test
    fun `split bubble sits to the right of the main pill`() {
        val g = geometry()
        val (main, bubble) = g.splitFrames()
        assertTrue(bubble.left > main.right)
        assertEquals(main.top, bubble.top, 0.001f)
        assertEquals(bubble.width, bubble.height, 0.001f)
        assertTrue(bubble.right <= screen.widthPx - g.edgeMargin + 0.01f)
    }

    @Test
    fun `touch rect extends below the island`() {
        val g = geometry(GeometryConfig(touchExtensionDp = 20f))
        val c = g.compactFrame()
        val touch = g.touchRect(c)
        assertEquals(c.bottom + 20f * screen.density, touch.bottom, 0.01f)
        assertTrue(touch.contains(c.centerX, c.bottom + 10f))
    }

    @Test
    fun `status band ends at or below the status bar`() {
        val g = geometry()
        assertTrue(g.statusBandBottom >= screen.statusBarHeightPx)
    }

    @Test
    fun `wide shapes are clamped inside the screen`() {
        val offCentre = CutoutSpec(centerX = 150f, centerY = 62f, radius = 26f, source = CutoutSource.MANUAL)
        val g = IslandGeometry(screen, offCentre, GeometryConfig())
        val f = g.frameFor(g.dp(300f), g.dp(40f))
        assertTrue(f.left >= g.edgeMargin - 0.01f)
    }
}
