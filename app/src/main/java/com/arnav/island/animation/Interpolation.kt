package com.arnav.island.animation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Inverse lerp clamped to [0, 1]. */
fun progress(value: Float, from: Float, to: Float): Float =
    if (to == from) 1f else ((value - from) / (to - from)).coerceIn(0f, 1f)

fun easeOutCubic(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

fun easeInOutSine(t: Float): Float = (-(cos(PI * t.coerceIn(0f, 1f)) - 1) / 2).toFloat()

fun easeOutBack(t: Float, overshoot: Float = 1.4f): Float {
    val x = t.coerceIn(0f, 1f) - 1f
    return 1f + (overshoot + 1f) * x * x * x + overshoot * x * x
}

/** Rubber-band resistance used for drag deformation: linear near 0, asymptotic to [limit]. */
fun rubberBand(offset: Float, limit: Float, coefficient: Float = 0.55f): Float {
    if (limit <= 0f) return 0f
    val sign = if (offset < 0) -1f else 1f
    val x = kotlin.math.abs(offset)
    return sign * (1f - 1f / (x * coefficient / limit + 1f)) * limit
}

/**
 * Smooth pseudo-random signal in [0, 1] built from a few incommensurate sines. Used for the
 * decorative equalizer so bars move organically without allocating or sampling audio.
 */
fun organic(t: Float, seed: Float): Float {
    val a = sin(t * 5.3f + seed * 1.7f)
    val b = sin(t * 8.9f + seed * 4.1f)
    val c = sin(t * 2.1f + seed * 7.3f)
    return ((a * 0.5f + b * 0.3f + c * 0.2f) * 0.5f + 0.5f).coerceIn(0f, 1f)
}
