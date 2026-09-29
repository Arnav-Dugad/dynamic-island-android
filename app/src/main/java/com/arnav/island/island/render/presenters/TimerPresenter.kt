package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import com.arnav.island.events.ActionStyle
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.StopwatchPayload
import com.arnav.island.events.TimerPayload
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.util.ColorExtractor
import com.arnav.island.util.Formatters
import kotlin.math.sin

/** Timers, ringing timers and the stopwatch. */
class TimerPresenter(rc: RenderContext) : Presenter(rc) {

    private var timer: TimerPayload? = null
    private var stopwatch: StopwatchPayload? = null
    private val isStopwatch get() = event.type == EventType.STOPWATCH
    private val isRinging get() = event.type == EventType.TIMER_DONE || timer?.isRinging == true

    private var pad = 0f
    private var top = 0f
    private var buttonsY = 0f

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> = when (mode) {
        PresentMode.EXPANDED, PresentMode.TOAST -> expandedWidth to (bandInset + dp(if (event.type == EventType.TIMER_DONE) 76f else 86f))
        else -> super.measure(event, mode)
    }

    override fun onBind(isUpdate: Boolean) {
        timer = event.payload as? TimerPayload
        stopwatch = event.payload as? StopwatchPayload
        if (mode == PresentMode.EXPANDED || mode == PresentMode.TOAST) layoutExpanded()
    }

    private fun layoutExpanded() {
        val w = layoutW
        pad = dp(20f)
        top = bandInset
        buttonsY = top + dp(48f)
        if (isRinging) {
            val cy = top + dp(34f)
            val stopR = w - pad
            val stopL = stopR - dp(76f)
            addTarget(ACTION_STOP, action(ACTION_STOP), stopL, cy - dp(19f), stopR, cy + dp(19f))
            val addR = stopL - dp(10f)
            addTarget(ACTION_ADD, action(ACTION_ADD), addR - dp(76f), cy - dp(19f), addR, cy + dp(19f))
            return
        }
        val first = pad + dp(22f)
        addCircleTarget(ACTION_TOGGLE, action(ACTION_TOGGLE), first, buttonsY, dp(24f))
        addCircleTarget(ACTION_SECONDARY, action(ACTION_SECONDARY), first + dp(54f), buttonsY, dp(24f))
        if (!isStopwatch) {
            val l = first + dp(54f) + dp(32f)
            addTarget(ACTION_ADD, action(ACTION_ADD), l, buttonsY - dp(18f), l + dp(66f), buttonsY + dp(18f))
        }
    }

    private fun action(id: String) = event.actions.firstOrNull { it.id == id }

    override fun nextFrameDelay(now: Long): Long {
        if (isRinging) return rc.settings.decorativeFrameMs
        if (isStopwatch) {
            val sw = stopwatch ?: return -1
            if (!sw.isRunning) return -1
            return if (mode == PresentMode.EXPANDED) maxOf(16L, rc.settings.decorativeFrameMs) else 1000 - (sw.elapsedAt(now) % 1000)
        }
        val t = timer ?: return -1
        if (t.isPaused) return -1
        val remaining = t.remainingAt(now)
        if (remaining <= 0) return -1
        return (remaining % 1000).coerceAtLeast(1) + 4
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.EXPANDED, PresentMode.TOAST -> if (isRinging) drawRinging(canvas, alpha) else drawExpanded(canvas, alpha, now)
            PresentMode.BUBBLE -> drawBubble(canvas, w, alpha, now)
            else -> drawCompact(canvas, w, h, alpha, now)
        }
    }

    private val accent: Int
        get() = event.colors.accent.takeIf { it != 0 }?.let(ColorExtractor::legibleOnBlack) ?: IslandColors.ORANGE

    private fun drawCompact(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val s = slot(h)
        val bx = rc.burnInX
        val by = rc.burnInY
        val lx = h / 2f + bx
        val cy = h / 2f + by
        val right = w - (h - s) / 2f - dp(2f) + bx

        rc.numberPaint.textSize = rc.sp(15f)
        when {
            isRinging -> {
                val pulse = 1f + 0.08f * sin(rc.animTime * 9f)
                rc.glyphs.draw(canvas, Glyph.TIMER, lx, cy, s * 0.92f * pulse, accent, a)
                rc.labelPaint.textSize = rc.sp(15f)
                rc.text(canvas, "Done", right, rc.baseline(rc.labelPaint, cy), rc.labelPaint, accent, a, Paint.Align.RIGHT)
            }
            isStopwatch -> {
                val sw = stopwatch ?: return
                rc.glyphs.draw(canvas, Glyph.STOPWATCH, lx, cy, s * 0.92f, accent, a)
                val color = if (sw.isRunning) IslandColors.TEXT else IslandColors.TEXT_SECONDARY
                rc.text(canvas, Formatters.elapsed(sw.elapsedAt(now)), right, rc.baseline(rc.numberPaint, cy), rc.numberPaint, color, a, Paint.Align.RIGHT)
            }
            else -> {
                val t = timer ?: return
                val color = if (t.isPaused) IslandColors.TEXT_SECONDARY else accent
                rc.glyphs.drawRing(canvas, lx, cy, s / 2f - dp(1.5f), dp(2.6f), t.fractionRemaining(now), color, IslandColors.TRACK, a)
                rc.text(canvas, Formatters.countdown(t.remainingAt(now)), right, rc.baseline(rc.numberPaint, cy), rc.numberPaint, color, a, Paint.Align.RIGHT)
            }
        }
    }

    private fun drawBubble(canvas: Canvas, d: Float, alpha: Float, now: Long) {
        val c = d / 2f
        val t = timer
        if (!isStopwatch && t != null && !isRinging) {
            val color = if (t.isPaused) IslandColors.TEXT_SECONDARY else accent
            rc.glyphs.drawRing(canvas, c + rc.burnInX, c + rc.burnInY, d * 0.3f, dp(2.6f), t.fractionRemaining(now), color, IslandColors.TRACK, alpha)
        } else {
            drawBubbleGlyph(canvas, d, if (isStopwatch) Glyph.STOPWATCH else Glyph.TIMER, accent, alpha)
        }
    }

    private fun drawExpanded(canvas: Canvas, alpha: Float, now: Long) {
        val w = layoutW
        rc.captionPaint.textSize = rc.sp(13f)
        val first = pad + dp(22f)

        if (isStopwatch) {
            val sw = stopwatch ?: return
            val label = if (sw.laps.isNotEmpty()) "Stopwatch · Lap ${sw.laps.size + 1}" else "Stopwatch"
            rc.text(canvas, label, pad, top + dp(12f), rc.captionPaint, IslandColors.TEXT_SECONDARY, alpha)
            drawCircleButton(canvas, ACTION_TOGGLE, if (sw.isRunning) Glyph.STOP else Glyph.PLAY, first, buttonsY, dp(22f),
                if (sw.isRunning) ActionStyle.DESTRUCTIVE else ActionStyle.POSITIVE, alpha, glyphScale = 0.44f)
            drawCircleButton(canvas, ACTION_SECONDARY, if (sw.isRunning) Glyph.FLAG else Glyph.CLOSE, first + dp(54f), buttonsY, dp(22f),
                ActionStyle.DEFAULT, alpha, glyphScale = 0.42f)
            drawBigTime(canvas, Formatters.stopwatch(sw.elapsedAt(now)), w - pad, first + dp(54f) + dp(34f), IslandColors.TEXT, alpha)
            return
        }

        val t = timer ?: return
        val color = if (t.isPaused) IslandColors.TEXT_SECONDARY else accent
        val label = buildString {
            append(t.label.ifBlank { "Timer" })
            append(" · ")
            append(Formatters.humanDuration(t.totalMs))
            if (t.isPaused) append(" · Paused")
        }
        rc.text(canvas, rc.ellipsize(label, rc.captionPaint, w - 2 * pad), pad, top + dp(12f), rc.captionPaint, IslandColors.TEXT_SECONDARY, alpha)

        // Pause/resume uses the timer colour; cancel stays neutral.
        val tint = ColorExtractor.withAlpha(accent, 0.24f)
        val s = pressScale(ACTION_TOGGLE)
        rc.circle(canvas, first, buttonsY, dp(22f) * s, tint, alpha)
        rc.glyphs.drawPlayPause(canvas, first, buttonsY, dp(22f) * s, if (t.isPaused) 0f else 1f, accent, alpha)
        drawCircleButton(canvas, ACTION_SECONDARY, Glyph.CLOSE, first + dp(54f), buttonsY, dp(22f), ActionStyle.DEFAULT, alpha, glyphScale = 0.42f)
        val l = first + dp(54f) + dp(32f)
        drawPillButton(canvas, ACTION_ADD, "+1:00", l, buttonsY - dp(18f), l + dp(66f), buttonsY + dp(18f), ActionStyle.DEFAULT, alpha)

        drawBigTime(canvas, Formatters.countdown(t.remainingAt(now)), w - pad, l + dp(66f), color, alpha)
    }

    /** Right-aligned big numerals, shrunk to fit whatever space the buttons leave. */
    private fun drawBigTime(canvas: Canvas, text: String, right: Float, minLeft: Float, color: Int, alpha: Float) {
        val paint = rc.bigNumberPaint
        paint.textSize = rc.sp(40f)
        val available = right - minLeft - dp(12f)
        val width = paint.measureText(text)
        if (width > available && available > 0f) paint.textSize *= available / width
        rc.text(canvas, text, right, rc.baseline(paint, buttonsY), paint, color, alpha, Paint.Align.RIGHT)
    }

    private fun drawRinging(canvas: Canvas, alpha: Float) {
        val w = layoutW
        val cy = top + dp(34f)
        val iconX = pad + dp(24f)
        val pulse = (sin(rc.animTime * 6f) + 1f) / 2f
        rc.circle(canvas, iconX, cy, dp(24f) + dp(5f) * pulse, ColorExtractor.withAlpha(accent, 0.18f * (1f - pulse)), alpha)
        rc.circle(canvas, iconX, cy, dp(24f), ColorExtractor.withAlpha(accent, 0.22f), alpha)
        rc.glyphs.draw(canvas, Glyph.TIMER, iconX, cy, dp(26f), accent, alpha)

        val textX = iconX + dp(24f) + dp(14f)
        val stopL = w - pad - dp(76f)
        val addL = stopL - dp(10f) - dp(76f)
        rc.titlePaint.textSize = rc.sp(16f)
        rc.captionPaint.textSize = rc.sp(13f)
        val t = timer
        rc.text(canvas, rc.ellipsize(t?.label?.ifBlank { "Timer" } ?: "Timer", rc.titlePaint, addL - textX - dp(8f)), textX, cy - dp(3f), rc.titlePaint, IslandColors.TEXT, alpha)
        val sub = if (t != null) "Finished · ${Formatters.humanDuration(t.totalMs)}" else "Finished"
        rc.text(canvas, rc.ellipsize(sub, rc.captionPaint, addL - textX - dp(8f)), textX, cy + dp(15f), rc.captionPaint, accent, alpha)

        drawPillButton(canvas, ACTION_ADD, "+1 min", addL, cy - dp(19f), addL + dp(76f), cy + dp(19f), ActionStyle.DEFAULT, alpha)
        drawPillButton(canvas, ACTION_STOP, "Stop", stopL, cy - dp(19f), stopL + dp(76f), cy + dp(19f), ActionStyle.PRIMARY, alpha)
    }

    override val contentDescription: String
        get() {
            val now = rc.clock()
            return when {
                isRinging -> "Timer finished"
                isStopwatch -> "Stopwatch ${Formatters.elapsed(stopwatch?.elapsedAt(now) ?: 0)}"
                else -> "Timer, ${Formatters.countdown(timer?.remainingAt(now) ?: 0)} remaining"
            }
        }

    companion object {
        const val ACTION_TOGGLE = "timer.toggle"
        const val ACTION_SECONDARY = "timer.secondary"
        const val ACTION_ADD = "timer.add"
        const val ACTION_STOP = "timer.stop"
    }
}
