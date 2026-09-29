package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.MonitorPayload
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.util.Formatters
import java.util.Locale

/** Opt-in device monitor. Metrics the platform does not expose are simply absent. */
class MonitorPresenter(rc: RenderContext) : Presenter(rc) {

    private var metrics: List<Pair<String, String>> = emptyList()

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> {
        if (mode != PresentMode.EXPANDED && mode != PresentMode.TOAST) return super.measure(event, mode)
        val rows = (collect(event.payload as? MonitorPayload).size + 1) / 2
        return expandedWidth to (bandInset + dp(8f) + rows.coerceAtLeast(1) * dp(46f) + dp(6f))
    }

    override fun onBind(isUpdate: Boolean) {
        metrics = collect(event.payload as? MonitorPayload)
    }

    private fun collect(p: MonitorPayload?): List<Pair<String, String>> {
        if (p == null) return emptyList()
        return buildList {
            if (p.ramUsedFraction != null) {
                val detail = if (p.ramUsedBytes != null && p.ramTotalBytes != null) " · ${Formatters.bytes(p.ramUsedBytes)} / ${Formatters.bytes(p.ramTotalBytes)}" else ""
                add("Memory" to Formatters.percent(p.ramUsedFraction) + detail)
            }
            if (p.netDownBps != null) add("Download" to Formatters.bytesPerSecond(p.netDownBps))
            if (p.netUpBps != null) add("Upload" to Formatters.bytesPerSecond(p.netUpBps))
            if (p.cpuFreqMhz != null) {
                val max = p.cpuMaxFreqMhz?.let { " / ${String.format(Locale.US, "%.1f", it / 1000f)} GHz" }.orEmpty()
                add("CPU clock" to String.format(Locale.US, "%.2f GHz", p.cpuFreqMhz / 1000f) + max)
            }
            if (p.batteryTempC != null) add("Battery temp" to Formatters.temperature(p.batteryTempC))
            if (p.thermalStatus != null) add("Thermal" to p.thermalStatus)
            if (p.islandFps != null) add("Island FPS" to String.format(Locale.US, "%.0f", p.islandFps))
        }
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.EXPANDED, PresentMode.TOAST -> drawExpanded(canvas, alpha)
            PresentMode.BUBBLE -> drawBubbleGlyph(canvas, w, Glyph.CHIP, IslandColors.TEAL, alpha)
            else -> drawCompact(canvas, w, h, alpha)
        }
    }

    private fun drawCompact(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val p = event.payload as? MonitorPayload ?: return
        val inset = (h - slot(h)) / 2f
        val cy = h / 2f + rc.burnInY
        rc.numberPaint.textSize = rc.sp(12.5f)
        val side = sideSpace(w) - inset
        val left = when {
            p.ramUsedFraction != null -> "RAM ${Formatters.percent(p.ramUsedFraction)}"
            p.cpuFreqMhz != null -> String.format(Locale.US, "%.1f GHz", p.cpuFreqMhz / 1000f)
            p.batteryTempC != null -> Formatters.temperature(p.batteryTempC)
            else -> ""
        }
        val right = when {
            p.netDownBps != null -> "↓ " + Formatters.bytesPerSecond(p.netDownBps)
            p.batteryTempC != null && p.ramUsedFraction != null -> Formatters.temperature(p.batteryTempC)
            p.islandFps != null -> String.format(Locale.US, "%.0f fps", p.islandFps)
            else -> ""
        }
        rc.text(canvas, rc.ellipsize(left, rc.numberPaint, side), inset + dp(4f) + rc.burnInX, rc.baseline(rc.numberPaint, cy), rc.numberPaint, IslandColors.TEAL, a)
        rc.text(canvas, rc.ellipsize(right, rc.numberPaint, side), w - inset - dp(4f) + rc.burnInX, rc.baseline(rc.numberPaint, cy), rc.numberPaint, IslandColors.TEXT, a, Paint.Align.RIGHT)
    }

    private fun drawExpanded(canvas: Canvas, alpha: Float) {
        val pad = dp(20f)
        val colW = (layoutW - 2 * pad) / 2f
        rc.captionPaint.textSize = rc.sp(12f)
        rc.labelPaint.textSize = rc.sp(15f)
        metrics.forEachIndexed { i, (label, value) ->
            val x = pad + (i % 2) * colW
            val y = bandInset + dp(8f) + (i / 2) * dp(46f)
            rc.text(canvas, label, x, y + dp(12f), rc.captionPaint, IslandColors.TEXT_TERTIARY, alpha)
            rc.text(canvas, rc.ellipsize(value, rc.labelPaint, colW - dp(8f)), x, y + dp(32f), rc.labelPaint, IslandColors.TEXT, alpha)
        }
        if (metrics.isEmpty()) {
            rc.text(canvas, "No metrics enabled", pad, bandInset + dp(28f), rc.labelPaint, IslandColors.TEXT_SECONDARY, alpha)
        }
    }

    override val contentDescription: String
        get() = metrics.joinToString(", ") { "${it.first} ${it.second}" }
}
