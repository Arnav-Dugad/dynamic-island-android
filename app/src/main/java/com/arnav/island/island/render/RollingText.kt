package com.arnav.island.island.render

import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec

/**
 * Odometer-style text: characters that change roll vertically while the rest stay put. Works
 * best with tabular figures (the island's number paints use "tnum"), so digits keep their slots.
 */
class RollingText {
    private var current = ""
    private var previous = ""
    private val progress = Spring(1f, SpringSpec(0.34f, 0.82f), restThreshold = 0.004f)

    /** +1: new characters arrive from below (counting up); -1: from above (counting down). */
    private var direction = 1
    private var widths = FloatArray(16)

    val text: String get() = current

    fun set(text: String, direction: Int, animate: Boolean) {
        if (text == current) return
        previous = current
        current = text
        this.direction = if (direction >= 0) 1 else -1
        if (animate && previous.isNotEmpty()) {
            progress.snapTo(0f)
            progress.animateTo(1f)
        } else {
            progress.snapTo(1f)
        }
    }

    fun step(dt: Float): Boolean = progress.step(dt)

    val isRolling: Boolean get() = progress.value < 0.999f

    /**
     * Draws the text with the given alignment at [x] and baseline [y]. Changed characters roll;
     * when the length changed, the whole string rolls instead.
     */
    fun draw(canvas: Canvas, rc: RenderContext, x: Float, y: Float, paint: TextPaint, color: Int, alpha: Float, align: Paint.Align) {
        if (alpha <= 0.004f || current.isEmpty()) return
        val p = progress.value.coerceIn(0f, 1f)
        if (p >= 0.999f) {
            rc.text(canvas, current, x, y, paint, color, alpha, align)
            return
        }
        val fm = paint.fontMetrics
        val lineH = (fm.descent - fm.ascent) * 0.9f
        val shift = lineH * (1f - p) * direction

        if (previous.length != current.length) {
            canvas.save()
            canvas.clipRect(x - LARGE, y + fm.ascent, x + LARGE, y + fm.descent)
            rc.text(canvas, previous, x, y - lineH * p * direction, paint, color, alpha * (1f - p), align)
            rc.text(canvas, current, x, y + shift, paint, color, alpha * p, align)
            canvas.restore()
            return
        }

        paint.textAlign = Paint.Align.LEFT
        if (widths.size < current.length) widths = FloatArray(current.length * 2)
        paint.getTextWidths(current, widths)
        var total = 0f
        for (i in current.indices) total += widths[i]
        var cx = when (align) {
            Paint.Align.RIGHT -> x - total
            Paint.Align.CENTER -> x - total / 2f
            Paint.Align.LEFT -> x
        }
        canvas.save()
        canvas.clipRect(cx - 2f, y + fm.ascent, cx + total + 2f, y + fm.descent)
        for (i in current.indices) {
            val now = current[i]
            val before = previous[i]
            val w = widths[i]
            if (now == before) {
                drawChar(canvas, now, cx, y, paint, color, alpha)
            } else {
                drawChar(canvas, before, cx, y - lineH * p * direction, paint, color, alpha * (1f - p))
                drawChar(canvas, now, cx, y + shift, paint, color, alpha * p)
            }
            cx += w
        }
        canvas.restore()
    }

    private val one = CharArray(1)

    private fun drawChar(canvas: Canvas, c: Char, x: Float, y: Float, paint: TextPaint, color: Int, alpha: Float) {
        if (alpha <= 0.004f) return
        one[0] = c
        paint.color = color
        paint.alpha = (((color ushr 24) * alpha)).toInt().coerceIn(0, 255)
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(one, 0, 1, x, y, paint)
    }

    private companion object {
        const val LARGE = 10_000f
    }
}
