package com.arnav.island.island.render.presenters

import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.SweepGradient
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.animation.easeInOutSine
import com.arnav.island.animation.lerp
import com.arnav.island.events.BatteryAlert
import com.arnav.island.events.BatteryPayload
import com.arnav.island.events.ChargingPayload
import com.arnav.island.events.ChargingSpeed
import com.arnav.island.events.ChargingTheme
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.PlugType
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RollingText
import com.arnav.island.storage.IslandTheme
import com.arnav.island.util.ColorExtractor
import com.arnav.island.util.Formatters
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Charging, low battery and battery full. */
class ChargingPresenter(rc: RenderContext) : Presenter(rc) {

    private var charging: ChargingPayload? = null
    private var battery: BatteryPayload? = null

    private val count = Spring(0f, SpringSpec(0.9f, 1f), restThreshold = 0.05f)
    private val fillLevel = Spring(0f, SpringSpec(0.8f, 0.9f), restThreshold = 0.001f)
    private val boltPop = Spring(0f, SpringSpec(0.42f, 0.5f), restThreshold = 0.002f)

    private val wavePath = Path()
    private var gradient: SweepGradient? = null
    private var gradientKey = 0f
    private val gradientMatrix = android.graphics.Matrix()

    private var title = ""
    private var subtitle = ""
    private var color = IslandColors.GREEN

    private val level: Int get() = charging?.level ?: battery?.level ?: 0
    private val theme: ChargingTheme get() = charging?.theme ?: ChargingTheme.MINIMAL
    private val isChargingState: Boolean get() = charging?.isCharging ?: battery?.isCharging ?: false

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> = when (mode) {
        PresentMode.TOAST -> mediumWidth to (bandInset + dp(56f))
        PresentMode.EXPANDED -> {
            val graph = ((event.payload as? ChargingPayload)?.powerHistory?.size ?: 0) >= MIN_GRAPH_POINTS
            expandedWidth to (bandInset + dp(if (graph) 204f else 132f))
        }
        else -> super.measure(event, mode)
    }

    override fun onBind(isUpdate: Boolean) {
        charging = event.payload as? ChargingPayload
        battery = event.payload as? BatteryPayload
        val target = level.toFloat()
        if (!isUpdate) {
            val from = (charging?.countFrom ?: level).toFloat()
            count.snapTo(from)
            fillLevel.snapTo(from / 100f)
            boltPop.snapTo(0f)
            boltPop.animateTo(1f)
        }
        count.animateTo(target)
        fillLevel.animateTo(target / 100f)

        val c = charging
        val b = battery
        color = when {
            b?.alert == BatteryAlert.LOW -> IslandColors.RED
            rc.settings.theme == IslandTheme.SAMSUNG -> rc.settings.systemAccent
            c != null && !c.isCharging && !c.isFull -> IslandColors.TEXT
            else -> IslandColors.GREEN
        }
        title = when {
            b != null -> when (b.alert) {
                BatteryAlert.LOW -> "Low battery"
                BatteryAlert.FULL -> "Fully charged"
                BatteryAlert.SAVER_ON -> "Power saving on"
                BatteryAlert.SAVER_OFF -> "Power saving off"
            }
            c == null -> event.title
            c.isFull -> "Fully charged"
            // e.g. One UI "Protect battery" holding at a limit while plugged in.
            !c.isCharging -> if (c.plugType != PlugType.NONE) "Charging paused" else "Not charging"
            else -> buildString {
                append(
                    when (c.speed) {
                        ChargingSpeed.SUPER_FAST -> "Super fast "
                        ChargingSpeed.FAST -> "Fast "
                        else -> ""
                    }
                )
                append(if (c.plugType == PlugType.WIRELESS) "wireless charging" else "charging")
            }.replaceFirstChar { it.uppercase() }
        }
        subtitle = when {
            c == null -> event.subtitle
            // Plugged in but held (e.g. One UI "Protect battery"): say exactly where it stopped.
            !c.isCharging && !c.isFull && c.plugType != PlugType.NONE -> "Paused at ${c.level}%"
            c.isCharging && c.timeToFullMs != null && c.timeToFullMs > 0 -> "Full in ${Formatters.humanDuration(c.timeToFullMs)}"
            else -> plugLabel(c.plugType).orEmpty()
        }
    }

    private fun plugLabel(p: PlugType): String? = when (p) {
        PlugType.AC -> "Wall charger"
        PlugType.USB -> "USB"
        PlugType.WIRELESS -> "Wireless"
        PlugType.DOCK -> "Dock"
        PlugType.NONE, PlugType.UNKNOWN -> null
    }

    private val percentText = RollingText()
    private val graphLine = Path()
    private val graphFill = Path()
    private val graphGradient = LinearGradient(0f, 0f, 0f, 1f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    private val graphMatrix = Matrix()

    /** Odometer percentage: digits roll as the counter climbs to the real level. */
    private fun drawPercent(canvas: Canvas, x: Float, y: Float, paint: android.text.TextPaint, alpha: Float, align: Paint.Align) {
        percentText.set("${count.value.roundToInt()}%", direction = 1, animate = true)
        percentText.draw(canvas, rc, x, y, paint, color, alpha, align)
    }

    override fun step(dt: Float): Boolean {
        var moving = super.step(dt)
        if (percentText.step(dt)) moving = true
        if (count.step(dt)) moving = true
        if (fillLevel.step(dt)) moving = true
        if (boltPop.step(dt)) moving = true
        return moving
    }

    override fun nextFrameDelay(now: Long): Long {
        if (!isChargingState) return -1
        val animatedTheme = theme != ChargingTheme.MINIMAL && (rc.settings.particles || theme != ChargingTheme.ENERGY)
        return if (animatedTheme && (mode == PresentMode.TOAST || mode == PresentMode.EXPANDED)) rc.settings.decorativeFrameMs else -1
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.TOAST -> drawToast(canvas, alpha)
            PresentMode.EXPANDED -> drawExpanded(canvas, alpha)
            PresentMode.BUBBLE -> drawBubbleGlyph(canvas, w, Glyph.BOLT, color, alpha)
            else -> drawCompact(canvas, w, h, alpha)
        }
    }

    private fun drawCompact(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val s = slot(h)
        val cy = h / 2f + rc.burnInY
        rc.glyphs.draw(canvas, if (isChargingState) Glyph.BOLT else Glyph.BATTERY, h / 2f + rc.burnInX, cy, s, color, a)
        val bw = dp(24f)
        val right = w - (h - s) / 2f + rc.burnInX
        drawBattery(canvas, right - bw, cy - dp(6f), bw, dp(12f), a, small = true)
        rc.numberPaint.textSize = rc.sp(14f)
        drawPercent(canvas, right - bw - dp(6f), rc.baseline(rc.numberPaint, cy), rc.numberPaint, a, Paint.Align.RIGHT)
    }

    private fun drawToast(canvas: Canvas, alpha: Float) {
        val w = layoutW
        val pad = dp(18f)
        val cy = bandInset + dp(22f)

        // Right cluster: percentage + battery (or the One UI gauge).
        val gaugeTheme = theme == ChargingTheme.SAMSUNG
        val bw = dp(30f)
        val bh = dp(15f)
        val batteryRight = w - pad
        val clusterLeft: Float
        if (gaugeTheme) {
            val r = dp(14f)
            drawGauge(canvas, batteryRight - r, cy, r, alpha)
            clusterLeft = batteryRight - 2 * r - dp(8f)
        } else {
            drawThemeBackdrop(canvas, batteryRight - bw / 2f, cy, alpha)
            drawBattery(canvas, batteryRight - bw, cy - bh / 2f, bw, bh, alpha, small = false)
            clusterLeft = batteryRight - bw - dp(8f)
        }
        rc.numberPaint.textSize = rc.sp(17f)
        drawPercent(canvas, clusterLeft, rc.baseline(rc.numberPaint, cy), rc.numberPaint, alpha, Paint.Align.RIGHT)

        // Left: glyph + status.
        val iconX = pad + dp(11f)
        val pop = lerp(0.6f, 1f, boltPop.value)
        val glyph = when {
            battery?.alert == BatteryAlert.LOW -> Glyph.BATTERY
            battery?.alert == BatteryAlert.FULL || charging?.isFull == true -> Glyph.CHECK
            isChargingState -> Glyph.BOLT
            else -> Glyph.BATTERY
        }
        rc.glyphs.draw(canvas, glyph, iconX, cy, dp(22f) * pop, color, alpha)
        if (theme == ChargingTheme.ENERGY && isChargingState && rc.settings.particles) {
            drawParticles(canvas, iconX + dp(14f), clusterLeft - dp(40f), cy, alpha)
        }

        val textX = iconX + dp(20f)
        val maxText = clusterLeft - dp(48f) - textX
        rc.titlePaint.textSize = rc.sp(15f)
        rc.captionPaint.textSize = rc.sp(12f)
        if (subtitle.isNotEmpty()) {
            rc.text(canvas, rc.ellipsize(title, rc.titlePaint, maxText), textX, cy - dp(2f), rc.titlePaint, IslandColors.TEXT, alpha)
            rc.text(canvas, rc.ellipsize(subtitle, rc.captionPaint, maxText), textX, cy + dp(14f), rc.captionPaint, IslandColors.TEXT_SECONDARY, alpha)
        } else {
            rc.text(canvas, rc.ellipsize(title, rc.titlePaint, maxText), textX, rc.baseline(rc.titlePaint, cy), rc.titlePaint, IslandColors.TEXT, alpha)
        }
    }

    private fun drawExpanded(canvas: Canvas, alpha: Float) {
        val w = layoutW
        val pad = dp(20f)
        val top = bandInset

        rc.bigNumberPaint.textSize = rc.sp(46f)
        drawPercent(canvas, pad, top + dp(44f), rc.bigNumberPaint, alpha, Paint.Align.LEFT)
        rc.titlePaint.textSize = rc.sp(15f)
        val status = if (subtitle.startsWith("Paused")) "$title · $subtitle" else title
        rc.text(canvas, rc.ellipsize(status, rc.titlePaint, w * 0.62f), pad, top + dp(68f), rc.titlePaint, IslandColors.TEXT, alpha)

        val bw = dp(92f)
        val bh = dp(42f)
        val bcy = top + dp(34f)
        drawThemeBackdrop(canvas, w - pad - bw / 2f, bcy, alpha * 0.8f)
        drawBattery(canvas, w - pad - bw - dp(4f), bcy - bh / 2f, bw, bh, alpha, small = false)

        // Details: only values the platform actually reported.
        val c = charging
        val details = buildList {
            if (c != null) plugLabel(c.plugType)?.let { add("Source" to it) }
            if (c?.timeToFullMs != null && c.timeToFullMs > 0 && c.isCharging) add("Full in" to Formatters.humanDuration(c.timeToFullMs))
            if (c?.temperatureC != null && rc.settings.theme != IslandTheme.MINIMAL) add("Temperature" to Formatters.temperature(c.temperatureC))
        }
        val colW = (w - 2 * pad) / 3f
        rc.captionPaint.textSize = rc.sp(12f)
        rc.labelPaint.textSize = rc.sp(15f)
        details.forEachIndexed { i, (label, value) ->
            val x = pad + colW * i
            rc.text(canvas, label, x, top + dp(98f), rc.captionPaint, IslandColors.TEXT_TERTIARY, alpha)
            rc.text(canvas, rc.ellipsize(value, rc.labelPaint, colW - dp(8f)), x, top + dp(118f), rc.labelPaint, IslandColors.TEXT, alpha)
        }
        drawPowerGraph(canvas, pad, top + dp(140f), w - pad, top + dp(184f), alpha)
    }

    /**
     * Live charging power, measured from public current x voltage readings (see
     * BatteryMonitor). Shown only once there are enough real samples.
     */
    private fun drawPowerGraph(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, alpha: Float) {
        val history = charging?.powerHistory ?: return
        if (history.size < MIN_GRAPH_POINTS) return
        rc.captionPaint.textSize = rc.sp(12f)
        rc.numberPaint.textSize = rc.sp(13f)
        rc.text(canvas, "Charging power", l, t, rc.captionPaint, IslandColors.TEXT_TERTIARY, alpha)
        val latest = history.last()
        rc.text(canvas, String.format(java.util.Locale.US, "%.1f W", latest), r, t, rc.numberPaint, color, alpha, Paint.Align.RIGHT)

        val top = t + dp(8f)
        val max = maxOf(history.max(), 5f) * 1.15f
        val n = history.size
        graphLine.rewind()
        graphFill.rewind()
        var px = l
        var py = b
        for (i in 0 until n) {
            val x = l + (r - l) * i / (n - 1).toFloat()
            val y = b - (history[i] / max) * (b - top)
            if (i == 0) {
                graphLine.moveTo(x, y)
                graphFill.moveTo(x, b)
                graphFill.lineTo(x, y)
            } else {
                val mx = (px + x) / 2f
                graphLine.quadTo(px, py, mx, (py + y) / 2f)
                graphFill.quadTo(px, py, mx, (py + y) / 2f)
            }
            px = x
            py = y
        }
        graphLine.lineTo(px, py)
        graphFill.lineTo(px, py)
        graphFill.lineTo(px, b)
        graphFill.close()

        graphMatrix.setScale(1f, b - top)
        graphMatrix.postTranslate(0f, top)
        graphGradient.setLocalMatrix(graphMatrix)
        // The white gradient supplies the fade; the colour filter tints it with the theme colour.
        rc.fill.shader = graphGradient
        rc.fill.colorFilter = BlendModeColorFilter(color, BlendMode.SRC_IN)
        rc.fill.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(graphFill, rc.fill)
        rc.fill.shader = null
        rc.fill.colorFilter = null
        rc.stroke.shader = null
        rc.stroke.strokeWidth = dp(1.8f)
        rc.stroke.color = color
        rc.stroke.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(graphLine, rc.stroke)
        rc.circle(canvas, px, py, dp(3f), color, alpha)
    }

    /** Battery outline + animated fill (+ liquid surface for the Liquid theme). */
    private fun drawBattery(canvas: Canvas, l: Float, t: Float, w: Float, h: Float, alpha: Float, small: Boolean) {
        val r = h * 0.3f
        val stroke = if (small) dp(1.3f) else maxOf(dp(1.6f), h * 0.07f)
        val inset = stroke * 1.6f
        rc.stroke.strokeWidth = stroke
        rc.stroke.color = IslandColors.TEXT_SECONDARY
        rc.stroke.alpha = (0x9E * alpha).toInt().coerceIn(0, 255)
        rc.rect.set(l, t, l + w, t + h)
        canvas.drawRoundRect(rc.rect, r, r, rc.stroke)
        // Nub.
        rc.roundRect(canvas, l + w + stroke * 0.8f, t + h * 0.32f, l + w + stroke * 0.8f + h * 0.12f, t + h * 0.68f, h * 0.06f, IslandColors.TEXT_SECONDARY, alpha)

        val frac = fillLevel.value.coerceIn(0f, 1f)
        val fl = l + inset
        val ft = t + inset
        val fb = t + h - inset
        val fullW = w - 2 * inset
        val fr = fl + fullW * frac
        if (frac <= 0.001f) return
        val fillColor = if (level <= 20 && !isChargingState) IslandColors.RED else color
        if (theme == ChargingTheme.LIQUID && isChargingState && !small) {
            val amp = (fb - ft) * 0.08f + dp(0.8f)
            wavePath.rewind()
            wavePath.moveTo(fl, ft)
            val steps = 10
            for (i in 0..steps) {
                val y = ft + (fb - ft) * i / steps
                val x = fr + amp * sin(rc.animTime * 5f + i * 0.9f)
                wavePath.lineTo(x.coerceIn(fl, fl + fullW), y)
            }
            wavePath.lineTo(fl, fb)
            wavePath.close()
            rc.fill.shader = null
            rc.fill.color = fillColor
            rc.fill.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            canvas.save()
            rc.rect.set(fl, ft, fl + fullW, fb)
            canvas.clipRect(rc.rect)
            canvas.drawPath(wavePath, rc.fill)
            canvas.restore()
        } else {
            rc.roundRect(canvas, fl, ft, fr, fb, (r - inset * 0.6f).coerceAtLeast(dp(1f)), fillColor, alpha)
        }
        if (isChargingState && !small) {
            rc.glyphs.draw(canvas, Glyph.BOLT, l + w / 2f, t + h / 2f, h * 0.78f, if (frac > 0.55f) 0xFF000000.toInt() else IslandColors.TEXT, alpha)
        }
    }

    private fun drawGauge(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
        val key = cx * 31 + cy + r
        if (gradient == null || gradientKey != key) {
            gradient = SweepGradient(cx, cy, intArrayOf(IslandColors.TEAL, color, IslandColors.GREEN, IslandColors.TEAL), null)
            gradientKey = key
        }
        rc.glyphs.drawRing(canvas, cx, cy, r, dp(3f), 0f, color, IslandColors.TRACK, alpha)
        gradientMatrix.setRotate(rc.animTime * 90f, cx, cy)
        gradient?.setLocalMatrix(gradientMatrix)
        rc.stroke.shader = gradient
        rc.stroke.strokeWidth = dp(3f)
        rc.stroke.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        rc.rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rc.rect, -90f, 360f * fillLevel.value.coerceIn(0f, 1f), false, rc.stroke)
        rc.stroke.shader = null
        rc.glyphs.draw(canvas, Glyph.BOLT, cx, cy, r * 1.05f, color, alpha)
    }

    /** Pulse rings / energy glow behind the battery. */
    private fun drawThemeBackdrop(canvas: Canvas, cx: Float, cy: Float, alpha: Float) {
        if (!isChargingState) return
        when (theme) {
            ChargingTheme.PULSE -> for (i in 0..1) {
                val p = ((rc.animTime * 0.8f + i * 0.5f) % 1f)
                val radius = lerp(dp(8f), dp(38f), easeInOutSine(p))
                rc.stroke.shader = null
                rc.stroke.strokeWidth = dp(1.5f)
                rc.stroke.color = color
                rc.stroke.alpha = (110 * (1f - p) * alpha).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, radius, rc.stroke)
            }
            ChargingTheme.ENERGY -> if (rc.settings.particles) {
                val glow = 0.14f + 0.08f * sin(rc.animTime * 3f)
                rc.circle(canvas, cx, cy, dp(24f), ColorExtractor.withAlpha(color, glow), alpha)
            }
            else -> Unit
        }
    }

    private fun drawParticles(canvas: Canvas, fromX: Float, toX: Float, cy: Float, alpha: Float) {
        if (toX <= fromX) return
        val count = 12
        for (i in 0 until count) {
            val p = ((rc.animTime * 0.55f + i / count.toFloat()) % 1f)
            val x = lerp(fromX, toX + dp(40f), p * p)
            val y = cy + sin(p * PI.toFloat() * 2f + i) * dp(5f) * (1f - p)
            val a = sin(p * PI.toFloat()) * 0.85f
            rc.circle(canvas, x, y, dp(1.7f) * (1f - p * 0.4f), color, alpha * a)
        }
    }

    override val contentDescription: String
        get() = "$title, $level percent${if (subtitle.isNotEmpty()) ", $subtitle" else ""}"

    private companion object {
        const val MIN_GRAPH_POINTS = 4
    }
}
