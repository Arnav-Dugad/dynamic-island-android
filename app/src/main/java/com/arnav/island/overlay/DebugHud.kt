package com.arnav.island.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Optional developer HUD in its own non-touchable window. The window alpha stays at 0.8 so that
 * Android's untrusted-touch rules let touches pass through to the app below.
 */
class DebugHud(context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val hudView = HudView(context)
    private var attached = false

    fun attach(topPx: Int) {
        if (attached) return
        val params = OverlayWindow.baseParams(touchable = false).apply {
            width = (230 * density).toInt()
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = (10 * density).toInt()
            y = topPx
            alpha = 0.8f
            title = "IslandHud"
        }
        try {
            wm.addView(hudView, params)
            attached = true
        } catch (e: RuntimeException) {
            Log.w("IslandHud", "HUD window rejected", e)
        }
    }

    fun detach() {
        if (!attached) return
        try {
            wm.removeViewImmediate(hudView)
        } catch (_: RuntimeException) {
        }
        attached = false
    }

    fun update(lines: List<Pair<String, String>>) {
        if (!attached) return
        hudView.lines = lines
        hudView.requestLayout()
        hudView.invalidate()
    }

    @SuppressLint("ViewConstructor")
    private class HudView(context: Context) : View(context) {
        var lines: List<Pair<String, String>> = emptyList()
        private val d = context.resources.displayMetrics.density
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.5f * d
            color = 0xAAFFFFFF.toInt()
            typeface = Typeface.MONOSPACE
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.5f * d
            color = 0xFF7CF7FF.toInt()
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6000000.toInt() }
        private val rect = RectF()
        private val lineH get() = 14f * d

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (lines.size * lineH + 12 * d).toInt().coerceAtLeast(1))
        }

        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, 10 * d, 10 * d, bg)
            var y = 6 * d + lineH * 0.8f
            for ((label, value) in lines) {
                canvas.drawText(label, 8 * d, y, labelPaint)
                canvas.drawText(value, 84 * d, y, valuePaint)
                y += lineH
            }
        }
    }
}
