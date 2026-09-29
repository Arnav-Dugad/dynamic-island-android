package com.arnav.island.island.render

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Allocation-free shape builders for the island.
 */
object SmoothShapes {

    private val arcRect = RectF()

    /**
     * Continuous-corner rounded rectangle (corner smoothing in the style popularised by design
     * tools). With [smoothing] = 0 this is a plain rounded rect; as the corner approaches a full
     * semicircle (a pill) the smoothing automatically fades out, so a pill morphs into a
     * smooth-cornered card without any discontinuity.
     */
    fun roundRect(path: Path, left: Float, top: Float, right: Float, bottom: Float, radius: Float, smoothing: Float = 0.6f) {
        path.rewind()
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return
        val maxR = min(w, h) / 2f
        val r = radius.coerceIn(0f, maxR)
        if (r <= 0.01f) {
            path.addRect(left, top, right, bottom, Path.Direction.CW)
            return
        }
        // Available length along each edge limits how far the smoothing may extend.
        val s = smoothing.coerceIn(0f, ((maxR / r) - 1f).coerceAtLeast(0f)).coerceAtMost(1f)
        if (s <= 0.001f) {
            path.addRoundRect(left, top, right, bottom, r, r, Path.Direction.CW)
            return
        }

        val p = (1f + s) * r
        val arcMeasure = 90f * (1f - s)
        val arcSectionLength = sinDeg(arcMeasure / 2f) * r * SQRT2
        val angleAlpha = (90f - arcMeasure) / 2f
        val p3ToP4 = r * tanDeg(angleAlpha / 2f)
        val angleBeta = 45f * s
        val c = p3ToP4 * cosDeg(angleBeta)
        val d = c * tanDeg(angleBeta)
        val b = (p - arcSectionLength - c - d) / 3f
        val a = 2f * b

        // Top edge, starting after the top-left corner.
        path.moveTo(left + p, top)
        path.lineTo(right - p, top)
        // Top-right corner.
        path.cubicTo(right - p + a, top, right - p + a + b, top, right - p + a + b + c, top + d)
        arcRect.set(right - 2 * r, top, right, top + 2 * r)
        path.arcTo(arcRect, -90f + angleAlpha, arcMeasure, false)
        path.cubicTo(right, top + p - a - b, right, top + p - a, right, top + p)
        // Right edge.
        path.lineTo(right, bottom - p)
        // Bottom-right corner.
        path.cubicTo(right, bottom - p + a, right, bottom - p + a + b, right - d, bottom - p + a + b + c)
        arcRect.set(right - 2 * r, bottom - 2 * r, right, bottom)
        path.arcTo(arcRect, angleAlpha, arcMeasure, false)
        path.cubicTo(right - p + a + b, bottom, right - p + a, bottom, right - p, bottom)
        // Bottom edge.
        path.lineTo(left + p, bottom)
        // Bottom-left corner.
        path.cubicTo(left + p - a, bottom, left + p - a - b, bottom, left + p - a - b - c, bottom - d)
        arcRect.set(left, bottom - 2 * r, left + 2 * r, bottom)
        path.arcTo(arcRect, 90f + angleAlpha, arcMeasure, false)
        path.cubicTo(left, bottom - p + a + b, left, bottom - p + a, left, bottom - p)
        // Left edge.
        path.lineTo(left, top + p)
        // Top-left corner.
        path.cubicTo(left, top + p - a, left, top + p - a - b, left + d, top + p - a - b - c)
        arcRect.set(left, top, left + 2 * r, top + 2 * r)
        path.arcTo(arcRect, 180f + angleAlpha, arcMeasure, false)
        path.cubicTo(left + p - a - b, top, left + p - a, top, left + p, top)
        path.close()
    }

    /**
     * Liquid bridge between two circles (metaball connector). Appends to [path] and returns true
     * when a bridge exists. [spread] in 0..1 controls how thick the neck is; it is driven towards
     * 0 as the circles separate so the neck thins to nothing instead of popping.
     */
    fun metaballBridge(
        path: Path,
        x1: Float, y1: Float, r1: Float,
        x2: Float, y2: Float, r2: Float,
        maxDistance: Float,
        spread: Float = 0.5f,
        handleSize: Float = 2.4f,
    ): Boolean {
        val d = hypot(x2 - x1, y2 - y1)
        if (r1 <= 0f || r2 <= 0f || d > maxDistance || d <= kotlin.math.abs(r1 - r2)) return false
        val v = spread.coerceIn(0f, 1f)

        val u1: Float
        val u2: Float
        if (d < r1 + r2) {
            u1 = acos(((r1 * r1 + d * d - r2 * r2) / (2 * r1 * d)).coerceIn(-1f, 1f))
            u2 = acos(((r2 * r2 + d * d - r1 * r1) / (2 * r2 * d)).coerceIn(-1f, 1f))
        } else {
            u1 = 0f
            u2 = 0f
        }
        val angle = atan2(y2 - y1, x2 - x1)
        val maxSpread = acos(((r1 - r2) / d).coerceIn(-1f, 1f))
        val pi = PI.toFloat()

        val a1 = angle + u1 + (maxSpread - u1) * v
        val a2 = angle - u1 - (maxSpread - u1) * v
        val a3 = angle + pi - u2 - (pi - u2 - maxSpread) * v
        val a4 = angle - pi + u2 + (pi - u2 - maxSpread) * v

        val p1x = x1 + r1 * cos(a1); val p1y = y1 + r1 * sin(a1)
        val p2x = x1 + r1 * cos(a2); val p2y = y1 + r1 * sin(a2)
        val p3x = x2 + r2 * cos(a3); val p3y = y2 + r2 * sin(a3)
        val p4x = x2 + r2 * cos(a4); val p4y = y2 + r2 * sin(a4)

        val total = r1 + r2
        val d2Base = min(v * handleSize, hypot(p3x - p1x, p3y - p1y) / total)
        val d2 = d2Base * min(1f, d * 2f / total)
        val r1h = r1 * d2
        val r2h = r2 * d2
        val half = pi / 2f

        val h1x = p1x + r1h * cos(a1 - half); val h1y = p1y + r1h * sin(a1 - half)
        val h2x = p2x + r1h * cos(a2 + half); val h2y = p2y + r1h * sin(a2 + half)
        val h3x = p3x + r2h * cos(a3 + half); val h3y = p3y + r2h * sin(a3 + half)
        val h4x = p4x + r2h * cos(a4 - half); val h4y = p4y + r2h * sin(a4 - half)

        path.moveTo(p1x, p1y)
        path.cubicTo(h1x, h1y, h3x, h3y, p3x, p3y)
        path.lineTo(p4x, p4y)
        path.cubicTo(h4x, h4y, h2x, h2y, p2x, p2y)
        // Close through the first circle's interior so the fill has no gap.
        path.lineTo(x1, y1)
        path.close()
        return true
    }

    private val SQRT2 = sqrt(2f)
    private fun rad(deg: Float) = deg * (PI.toFloat() / 180f)
    private fun sinDeg(deg: Float) = sin(rad(deg))
    private fun cosDeg(deg: Float) = cos(rad(deg))
    private fun tanDeg(deg: Float) = tan(rad(deg))
}
