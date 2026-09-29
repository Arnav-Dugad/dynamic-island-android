package com.arnav.island.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpringTest {

    private fun settle(spring: Spring, hz: Int, maxSeconds: Float = 5f): Float {
        val dt = 1f / hz
        var t = 0f
        var peak = spring.value
        while (spring.step(dt) && t < maxSeconds) {
            t += dt
            peak = maxOf(peak, spring.value)
        }
        return peak
    }

    @Test
    fun `settles on target`() {
        val s = Spring(0f, SpringSpec.Natural)
        s.animateTo(100f)
        settle(s, 120)
        assertEquals(100f, s.value, 0.001f)
        assertTrue(s.isAtRest)
    }

    @Test
    fun `motion is frame rate independent`() {
        val a = Spring(0f, SpringSpec.Elastic)
        val b = Spring(0f, SpringSpec.Elastic)
        a.animateTo(100f)
        b.animateTo(100f)
        repeat(12) { a.step(1f / 120f) }
        repeat(6) { b.step(1f / 60f) }
        assertEquals(a.value, b.value, 0.01f)
        assertEquals(a.velocity, b.velocity, 0.1f)
    }

    @Test
    fun `underdamped spring overshoots, critically damped does not`() {
        val bouncy = Spring(0f, SpringSpec(0.5f, 0.5f))
        bouncy.animateTo(100f)
        assertTrue(settle(bouncy, 120) > 100.5f)

        val critical = Spring(0f, SpringSpec(0.5f, 1f))
        critical.animateTo(100f)
        assertTrue(settle(critical, 120) <= 100.001f)
    }

    @Test
    fun `retarget preserves velocity`() {
        val s = Spring(0f, SpringSpec.Natural)
        s.animateTo(100f)
        repeat(10) { s.step(1f / 120f) }
        val v = s.velocity
        s.animateTo(-50f)
        assertEquals(v, s.velocity, 0.0001f)
        // Still moving up for a moment before turning around.
        s.step(1f / 120f)
        assertTrue(s.value > 0f)
    }

    @Test
    fun `overdamped spring converges`() {
        val s = Spring(0f, SpringSpec(0.4f, 1.6f))
        s.animateTo(10f)
        settle(s, 60)
        assertTrue(abs(s.value - 10f) < 0.01f)
    }

    @Test
    fun `presets scale with speed and intensity`() {
        val faster = SpringSpec.Natural.scaled(speed = 2f)
        assertTrue(faster.stiffness > SpringSpec.Natural.stiffness)
        val calmer = SpringSpec.Elastic.scaled(intensity = 0f)
        assertEquals(1f, calmer.dampingRatio, 0.0001f)
    }
}
