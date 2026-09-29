package com.arnav.island.util

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Picks an accent colour from artwork pixels that reads well on OLED black: the most populated
 * vivid hue bucket, lifted to a legible lightness. Pure function over ARGB ints so it is unit
 * testable and cheap enough to run on a 24x24 thumbnail.
 */
object ColorExtractor {

    const val FALLBACK = 0xFFFFFFFF.toInt()

    fun accentFrom(pixels: IntArray): Int {
        if (pixels.isEmpty()) return FALLBACK
        val bucketCount = 24
        val weights = FloatArray(bucketCount)
        val sumS = FloatArray(bucketCount)
        val sumL = FloatArray(bucketCount)
        val sumH = FloatArray(bucketCount)
        val hsl = FloatArray(3)
        var greyWeight = 0f
        var greyL = 0f

        for (p in pixels) {
            if ((p ushr 24) < 0x80) continue
            rgbToHsl(p, hsl)
            val s = hsl[1]
            val l = hsl[2]
            if (s < 0.18f || l < 0.12f || l > 0.94f) {
                greyWeight += 1f
                greyL += l
                continue
            }
            val bucket = ((hsl[0] / 360f) * bucketCount).toInt().coerceIn(0, bucketCount - 1)
            // Favour saturated, mid-lightness pixels: they carry the artwork's character.
            val w = s * (1f - abs(l - 0.55f))
            weights[bucket] += w
            sumS[bucket] += s * w
            sumL[bucket] += l * w
            sumH[bucket] += hsl[0] * w
        }

        var best = -1
        for (i in 0 until bucketCount) if (best < 0 || weights[i] > weights[best]) best = i
        if (best < 0 || weights[best] <= 0.02f * pixels.size) {
            // Mostly monochrome artwork: use a soft neutral rather than inventing a hue.
            return FALLBACK
        }
        val w = weights[best]
        val h = sumH[best] / w
        val s = (sumS[best] / w).coerceIn(0.45f, 0.9f)
        val l = (sumL[best] / w).coerceIn(0.6f, 0.74f)
        return hslToRgb(h, s, l)
    }

    /** Lifts a colour until it has enough contrast on black (used for app accent colours). */
    fun legibleOnBlack(color: Int): Int {
        if (color == 0) return FALLBACK
        val hsl = FloatArray(3)
        rgbToHsl(color, hsl)
        if (hsl[1] < 0.12f) return hslToRgb(hsl[0], hsl[1], max(hsl[2], 0.78f))
        return hslToRgb(hsl[0], min(hsl[1], 0.9f), hsl[2].coerceIn(0.58f, 0.78f))
    }

    fun rgbToHsl(color: Int, out: FloatArray) {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        var h = 0f
        var s = 0f
        if (d > 1e-6f) {
            s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
            h = when (mx) {
                r -> ((g - b) / d + (if (g < b) 6f else 0f))
                g -> ((b - r) / d + 2f)
                else -> ((r - g) / d + 4f)
            } * 60f
        }
        out[0] = h
        out[1] = s
        out[2] = l
    }

    fun hslToRgb(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = (h / 60f) % 6f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, x, 0f)
            hp < 2 -> Triple(x, c, 0f)
            hp < 3 -> Triple(0f, c, x)
            hp < 4 -> Triple(0f, x, c)
            hp < 5 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        val r = ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        (((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt() shl 24) or (color and 0x00FFFFFF)

    fun blend(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = ((((a shr shift) and 0xFF) * (1 - tt)) + (((b shr shift) and 0xFF) * tt)).toInt() and 0xFF
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
