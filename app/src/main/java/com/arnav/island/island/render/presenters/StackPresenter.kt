package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import com.arnav.island.events.CallPayload
import com.arnav.island.events.ChargingPayload
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandAction
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.MediaPayload
import com.arnav.island.events.NavigationPayload
import com.arnav.island.events.ProgressPayload
import com.arnav.island.events.StackPayload
import com.arnav.island.events.StopwatchPayload
import com.arnav.island.events.TimerPayload
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RoundedImage
import com.arnav.island.util.ColorExtractor
import com.arnav.island.util.Formatters

/** Every running activity as a stack of rows; tapping a row expands that activity. */
class StackPresenter(rc: RenderContext) : Presenter(rc) {

    private class Row(val event: IslandEvent, val image: RoundedImage, val title: String)

    private val rows = ArrayList<Row>(MAX_ROWS)

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> {
        val count = ((event.payload as? StackPayload)?.items?.size ?: 1).coerceIn(1, MAX_ROWS)
        return expandedWidth to (bandInset + count * ROW_DP * rc.density + dp(8f))
    }

    override fun onBind(isUpdate: Boolean) {
        val items = (event.payload as? StackPayload)?.items.orEmpty().take(MAX_ROWS)
        rows.clear()
        rc.titlePaint.textSize = rc.sp(15f)
        val textW = layoutW - dp(20f) * 2 - dp(36f) - dp(12f) - dp(64f)
        items.forEachIndexed { i, e ->
            val image = RoundedImage().apply { set(rc.images.get(e.artwork) ?: rc.images.get(e.iconImage)) }
            rows += Row(e, image, rc.ellipsize(e.title.ifBlank { e.type.name.lowercase().replaceFirstChar { it.uppercase() } }, rc.titlePaint, textW))
            val top = bandInset + i * dp(ROW_DP)
            addTarget("stack.$i", IslandAction("stack.$i", e.title) { rc.onStackPick?.invoke(e.id) }, 0f, top, layoutW, top + dp(ROW_DP))
        }
    }

    override fun nextFrameDelay(now: Long): Long =
        if (rows.any { it.event.type == EventType.TIMER || it.event.type == EventType.STOPWATCH || it.event.type == EventType.CALL_ONGOING }) {
            1000 - now % 1000
        } else {
            -1
        }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        val pad = dp(20f)
        rc.titlePaint.textSize = rc.sp(15f)
        rc.captionPaint.textSize = rc.sp(13f)
        rc.numberPaint.textSize = rc.sp(14f)
        rows.forEachIndexed { i, row ->
            val top = bandInset + i * dp(ROW_DP)
            val cy = top + dp(ROW_DP) / 2f
            val target = hitTargets.getOrNull(i)
            if (target != null && pressedId == target.id) {
                rc.roundRect(canvas, dp(8f), top + dp(3f), layoutW - dp(8f), top + dp(ROW_DP) - dp(3f), dp(16f), IslandColors.CONTROL, alpha)
            }
            val e = row.event
            val accent = e.colors.accent.takeIf { it != 0 }?.let(ColorExtractor::legibleOnBlack) ?: typeColor(e.type)
            val size = dp(36f)
            if (row.image.hasImage) {
                row.image.draw(canvas, pad, cy - size / 2f, size, size * 0.28f, alpha)
            } else {
                rc.circle(canvas, pad + size / 2f, cy, size / 2f, ColorExtractor.withAlpha(accent, 0.2f), alpha)
                rc.glyphs.draw(canvas, e.icon ?: Glyph.SPARK, pad + size / 2f, cy, size * 0.52f, accent, alpha)
            }
            val textX = pad + size + dp(12f)
            val summary = summary(e)
            if (summary.isEmpty()) {
                rc.text(canvas, row.title, textX, rc.baseline(rc.titlePaint, cy), rc.titlePaint, IslandColors.TEXT, alpha)
            } else {
                rc.text(canvas, row.title, textX, cy - dp(2f), rc.titlePaint, IslandColors.TEXT, alpha)
                rc.text(canvas, rc.ellipsize(summary, rc.captionPaint, layoutW - textX - pad - dp(64f)), textX, cy + dp(15f), rc.captionPaint, IslandColors.TEXT_SECONDARY, alpha)
            }
            val trailing = trailing(e, now)
            if (trailing != null) {
                rc.text(canvas, trailing, layoutW - pad, rc.baseline(rc.numberPaint, cy), rc.numberPaint, accent, alpha, Paint.Align.RIGHT)
            }
        }
    }

    private fun typeColor(type: EventType) = when (type) {
        EventType.TIMER, EventType.TIMER_DONE, EventType.STOPWATCH -> IslandColors.ORANGE
        EventType.CALL_INCOMING, EventType.CALL_ONGOING, EventType.CHARGING, EventType.BATTERY_FULL -> IslandColors.GREEN
        EventType.NAVIGATION, EventType.PROGRESS, EventType.BLUETOOTH -> IslandColors.BLUE
        EventType.BATTERY_LOW, EventType.SCREEN_RECORD -> IslandColors.RED
        else -> IslandColors.TEXT
    }

    private fun summary(e: IslandEvent): String = when (val p = e.payload) {
        is MediaPayload -> if (p.isPlaying) p.artist else "Paused · ${p.artist}".trimEnd(' ', '·')
        is TimerPayload -> if (p.isPaused) "Paused" else if (p.isRinging) "Finished" else Formatters.humanDuration(p.totalMs) + " timer"
        is StopwatchPayload -> if (p.isRunning) "Running" else "Paused"
        is CallPayload -> p.detail.ifBlank { p.appLabel }
        is NavigationPayload -> p.instruction
        is ProgressPayload -> p.appLabel
        is ChargingPayload -> if (p.isCharging) "Charging" else "Plugged in"
        else -> e.subtitle
    }

    private fun trailing(e: IslandEvent, now: Long): String? = when (val p = e.payload) {
        is TimerPayload -> if (p.isRinging) null else Formatters.countdown(p.remainingAt(now))
        is StopwatchPayload -> Formatters.elapsed(p.elapsedAt(now))
        is CallPayload -> p.chronometerBase?.let { Formatters.elapsed(now - it) }
        is ProgressPayload -> Formatters.percent(p.progress)
        is ChargingPayload -> "${p.level}%"
        is NavigationPayload -> p.distance.takeIf { it.length <= 8 }
        else -> null
    }

    override val contentDescription: String
        get() = "${rows.size} activities: " + rows.joinToString(", ") { it.title }

    private companion object {
        const val MAX_ROWS = 5
        const val ROW_DP = 56f
    }
}
