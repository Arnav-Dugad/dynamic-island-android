package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import android.text.format.DateFormat
import com.arnav.island.events.CalendarPayload
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RollingText
import com.arnav.island.util.ColorExtractor
import java.util.Date
import kotlin.math.ceil

/**
 * Countdown to the next calendar event: "12m" in the pill in the calendar's colour, and a card
 * with the time, place and a ring that closes as the meeting approaches.
 */
class CalendarPresenter(rc: RenderContext) : Presenter(rc) {

    private var payload: CalendarPayload? = null
    private val countdown = RollingText()
    private var titleLine = ""
    private var placeLine = ""
    private var timeRange = ""
    private var pad = 0f
    private var top = 0f

    private val color: Int
        get() = payload?.calendarColor?.takeIf { it != 0 }?.let(ColorExtractor::legibleOnBlack) ?: IslandColors.BLUE

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> = when (mode) {
        PresentMode.EXPANDED, PresentMode.TOAST ->
            expandedWidth to (bandInset + dp(90f) + if (mode == PresentMode.EXPANDED && event.actions.isNotEmpty()) dp(48f) else 0f)
        else -> super.measure(event, mode)
    }

    override fun onBind(isUpdate: Boolean) {
        val p = event.payload as? CalendarPayload
        payload = p
        pad = dp(20f)
        top = bandInset
        if (p == null) return
        val fmt = DateFormat.getTimeFormat(rc.context)
        timeRange = "${fmt.format(Date(p.startsAt))} – ${fmt.format(Date(p.endsAt))}"
        if (mode == PresentMode.EXPANDED || mode == PresentMode.TOAST) {
            val textX = pad + dp(52f) + dp(14f)
            rc.titlePaint.textSize = rc.sp(16f)
            rc.bodyPaint.textSize = rc.sp(14f)
            titleLine = rc.ellipsize(p.title.ifBlank { "Event" }, rc.titlePaint, layoutW - pad - textX)
            placeLine = rc.ellipsize(listOf(timeRange, p.location).filter { it.isNotBlank() }.joinToString(" · "), rc.bodyPaint, layoutW - pad - textX)
            if (mode == PresentMode.EXPANDED) {
                val actions = event.actions.take(2)
                val gap = dp(8f)
                val bw = (layoutW - 2 * pad - gap * (actions.size - 1)) / actions.size.coerceAtLeast(1)
                val cy = layoutH - dp(12f) - dp(18f)
                actions.forEachIndexed { i, a ->
                    val l = pad + i * (bw + gap)
                    addTarget(a.id, a, l, cy - dp(18f), l + bw, cy + dp(18f))
                }
            }
        }
    }

    /** Whole minutes to the start, rounded up like a meeting reminder; 0 once it has started. */
    private fun minutesLeft(now: Long): Long {
        val ms = (payload?.startsAt ?: return 0) - now
        return if (ms <= 0) 0 else ceil(ms / 60_000.0).toLong()
    }

    /** "12m", "1h 5m" or "Now". */
    private fun label(now: Long): String {
        val m = minutesLeft(now)
        return when {
            m <= 0 -> "Now"
            m >= 60 -> "${m / 60}h ${m % 60}m"
            else -> "${m}m"
        }
    }

    private fun sentence(now: Long): String {
        val m = minutesLeft(now)
        return when {
            m <= 0 -> "Happening now"
            m >= 60 -> "Starts in ${m / 60} h ${m % 60} min"
            else -> "Starts in $m min"
        }
    }

    override fun step(dt: Float): Boolean {
        val a = super.step(dt)
        val b = countdown.step(dt)
        return a || b
    }

    override fun nextFrameDelay(now: Long): Long {
        val p = payload ?: return -1
        val ms = p.startsAt - now
        if (ms <= 0) return -1
        // Next minute boundary of the countdown.
        return (ms % 60_000).coerceAtLeast(1) + 8
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.EXPANDED, PresentMode.TOAST -> drawCard(canvas, alpha, now)
            PresentMode.BUBBLE -> drawBubbleGlyph(canvas, w, Glyph.CALENDAR, color, alpha)
            else -> drawCompact(canvas, w, h, alpha, now)
        }
    }

    private fun drawCompact(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val s = slot(h)
        val cy = h / 2f + rc.burnInY
        rc.glyphs.draw(canvas, Glyph.CALENDAR, h / 2f + rc.burnInX, cy, s * 0.86f, color, a)
        rc.numberPaint.textSize = rc.sp(15f)
        countdown.set(label(now), direction = -1, animate = true)
        countdown.draw(canvas, rc, w - (h - s) / 2f - dp(2f) + rc.burnInX, rc.baseline(rc.numberPaint, cy), rc.numberPaint, color, a, Paint.Align.RIGHT)
    }

    private fun drawCard(canvas: Canvas, alpha: Float, now: Long) {
        val p = payload ?: return
        val ringR = dp(24f)
        val rcx = pad + ringR + dp(2f)
        val rcy = top + dp(34f)
        // The ring closes over the last hour before the event.
        val left = (p.startsAt - now).coerceAtLeast(0)
        val fraction = 1f - (left / 3_600_000f).coerceIn(0f, 1f)
        rc.glyphs.drawRing(canvas, rcx, rcy, ringR, dp(3.2f), fraction, color, IslandColors.TRACK, alpha)
        rc.numberPaint.textSize = rc.sp(13f)
        countdown.set(label(now), direction = -1, animate = true)
        countdown.draw(canvas, rc, rcx, rc.baseline(rc.numberPaint, rcy), rc.numberPaint, IslandColors.TEXT, alpha, Paint.Align.CENTER)

        val textX = pad + dp(52f) + dp(14f)
        rc.captionPaint.textSize = rc.sp(12f)
        rc.text(canvas, sentence(now), textX, top + dp(16f), rc.captionPaint, color, alpha)
        rc.titlePaint.textSize = rc.sp(16f)
        rc.bodyPaint.textSize = rc.sp(14f)
        rc.text(canvas, titleLine, textX, top + dp(38f), rc.titlePaint, IslandColors.TEXT, alpha)
        rc.text(canvas, placeLine, textX, top + dp(58f), rc.bodyPaint, IslandColors.TEXT_SECONDARY, alpha)

        if (mode == PresentMode.EXPANDED) {
            hitTargets.forEach { t ->
                val action = t.action ?: return@forEach
                drawPillButton(canvas, t.id, action.label, t.rect.left, t.rect.top, t.rect.right, t.rect.bottom, action.style, alpha)
            }
        }
    }

    override val contentDescription: String
        get() = payload?.let { "${it.title}, $timeRange" } ?: event.title

    companion object {
        const val ACTION_JOIN = "calendar.join"
        const val ACTION_OPEN = "calendar.open"
    }
}
