package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import android.text.format.DateFormat
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.events.GlancePayload
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.util.Formatters
import java.util.Date
import java.util.Locale

/** Long-press on the idle island: date, battery, next alarm, timers and what's playing. */
class GlancePresenter(rc: RenderContext) : Presenter(rc) {

    private var payload: GlancePayload? = null
    private val ring = Spring(0f, SpringSpec(0.7f, 0.9f), restThreshold = 0.002f)
    private var weekday = ""
    private var date = ""
    private val chips = ArrayList<Pair<Glyph, String>>(3)

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> =
        expandedWidth to (bandInset + dp(112f))

    override fun onBind(isUpdate: Boolean) {
        val p = event.payload as? GlancePayload
        payload = p
        val now = Date(rc.clock())
        val locale = Locale.getDefault()
        weekday = android.text.format.DateFormat.format("EEEE", now).toString()
        date = android.text.format.DateFormat.format(DateFormat.getBestDateTimePattern(locale, "dMMMM"), now).toString()
        if (!isUpdate) ring.snapTo(0f)
        ring.animateTo((p?.batteryLevel ?: 0) / 100f)

        chips.clear()
        val alarm = p?.nextAlarmAt
        if (alarm != null) {
            val time = DateFormat.getTimeFormat(rc.context).format(Date(alarm))
            chips += Glyph.ALARM to "$time · in ${Formatters.humanDuration(alarm - rc.clock())}"
        } else {
            chips += Glyph.BELL_OFF to "No alarm set"
        }
        if (p != null && p.runningTimers > 0) chips += Glyph.TIMER to if (p.runningTimers == 1) "1 timer" else "${p.runningTimers} timers"
        p?.nowPlaying?.let { chips += Glyph.MUSIC to it }
    }

    override fun step(dt: Float): Boolean {
        val a = super.step(dt)
        val b = ring.step(dt)
        return a || b
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        val pad = dp(20f)
        val top = bandInset
        rc.bigNumberPaint.textSize = rc.sp(26f)
        rc.bodyPaint.textSize = rc.sp(15f)
        rc.text(canvas, weekday, pad, top + dp(26f), rc.bigNumberPaint, IslandColors.TEXT, alpha)
        rc.text(canvas, date, pad, top + dp(48f), rc.bodyPaint, IslandColors.TEXT_SECONDARY, alpha)

        // Battery ring on the right.
        val p = payload
        val level = p?.batteryLevel
        if (level != null) {
            val r = dp(24f)
            val cx = layoutW - pad - r
            val cy = top + dp(28f)
            val color = when {
                p.isCharging -> IslandColors.GREEN
                level <= 20 -> IslandColors.RED
                else -> IslandColors.TEXT
            }
            rc.glyphs.drawRing(canvas, cx, cy, r, dp(3.4f), ring.value, color, IslandColors.TRACK, alpha)
            rc.numberPaint.textSize = rc.sp(14f)
            rc.text(canvas, "$level", cx, rc.baseline(rc.numberPaint, cy - if (p.isCharging) dp(3f) else 0f), rc.numberPaint, IslandColors.TEXT, alpha, Paint.Align.CENTER)
            if (p.isCharging) rc.glyphs.draw(canvas, Glyph.BOLT, cx, cy + dp(10f), dp(10f), IslandColors.GREEN, alpha)
        }

        // Chips row.
        var x = pad
        val cy = top + dp(84f)
        rc.captionPaint.textSize = rc.sp(13f)
        for ((glyph, label) in chips) {
            val text = rc.ellipsize(label, rc.captionPaint, layoutW - pad - x - dp(40f))
            if (text.isEmpty()) break
            val cw = dp(34f) + rc.captionPaint.measureText(text)
            rc.roundRect(canvas, x, cy - dp(15f), x + cw, cy + dp(15f), dp(15f), IslandColors.CONTROL, alpha)
            rc.glyphs.draw(canvas, glyph, x + dp(16f), cy, dp(14f), IslandColors.TEXT_SECONDARY, alpha)
            rc.text(canvas, text, x + dp(28f), rc.baseline(rc.captionPaint, cy), rc.captionPaint, IslandColors.TEXT, alpha)
            x += cw + dp(8f)
            if (x > layoutW - pad - dp(60f)) break
        }
    }

    override val contentDescription: String
        get() = "$weekday $date" + (payload?.batteryLevel?.let { ", battery $it percent" }.orEmpty())
}
