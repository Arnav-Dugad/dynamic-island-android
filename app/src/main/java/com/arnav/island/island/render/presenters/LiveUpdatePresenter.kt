package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.animation.lerp
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.LiveUpdatePayload
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RollingText
import com.arnav.island.island.render.RoundedImage
import com.arnav.island.util.ColorExtractor
import kotlin.math.sin

/**
 * Deliveries, rides and Android 16 Live Updates. The pill shows the app and its short status
 * ("8 min"); the card shows a route with the app's segments and stops, and the courier (the app's
 * own tracker icon when it provides one) gliding along it.
 */
class LiveUpdatePresenter(rc: RenderContext) : Presenter(rc) {

    private var payload: LiveUpdatePayload? = null
    private val icon = RoundedImage()
    private val tracker = RoundedImage()
    private val position = Spring(0f, SpringSpec(0.9f, 0.9f), restThreshold = 0.001f)
    private val shortText = RollingText()
    private var titleLine = ""
    private var textLine = ""
    private var pad = 0f
    private var top = 0f

    private val accent: Int
        get() = event.colors.accent.takeIf { it != 0 }?.let(ColorExtractor::legibleOnBlack) ?: IslandColors.GREEN

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> = when (mode) {
        PresentMode.EXPANDED, PresentMode.TOAST -> expandedWidth to (bandInset + dp(104f))
        else -> super.measure(event, mode)
    }

    override fun onBind(isUpdate: Boolean) {
        val p = event.payload as? LiveUpdatePayload
        payload = p
        icon.set(rc.images.get(event.iconImage))
        tracker.set(rc.images.get(event.artwork))
        val target = p?.progress ?: 0f
        if (isUpdate) position.animateTo(target) else position.snapTo(target)
        pad = dp(20f)
        top = bandInset
        if (p != null && (mode == PresentMode.EXPANDED || mode == PresentMode.TOAST)) {
            rc.titlePaint.textSize = rc.sp(16f)
            rc.bodyPaint.textSize = rc.sp(14f)
            val textX = pad + dp(40f) + dp(12f)
            rc.numberPaint.textSize = rc.sp(20f)
            val etaW = p.shortText?.let { rc.numberPaint.measureText(it) + dp(12f) } ?: 0f
            titleLine = rc.ellipsize(p.title.ifBlank { p.appLabel }, rc.titlePaint, layoutW - pad - textX - etaW)
            textLine = rc.ellipsize(p.text, rc.bodyPaint, layoutW - pad - textX)
        }
    }

    override fun step(dt: Float): Boolean {
        var moving = super.step(dt)
        if (position.step(dt)) moving = true
        if (shortText.step(dt)) moving = true
        return moving
    }

    override fun nextFrameDelay(now: Long): Long =
        // The courier bobs gently while the card is open.
        if (mode == PresentMode.EXPANDED && payload?.progress != null) maxOf(33L, rc.settings.decorativeFrameMs) else -1

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.EXPANDED, PresentMode.TOAST -> drawCard(canvas, alpha)
            PresentMode.BUBBLE -> drawBubble(canvas, w, alpha)
            else -> drawCompact(canvas, w, h, alpha)
        }
    }

    private fun drawAppIcon(canvas: Canvas, l: Float, t: Float, s: Float, alpha: Float) {
        if (icon.hasImage) {
            icon.draw(canvas, l, t, s, s * 0.26f, alpha)
        } else {
            rc.glyphs.draw(canvas, payload?.vehicle ?: Glyph.SCOOTER, l + s / 2f, t + s / 2f, s * 0.86f, accent, alpha)
        }
    }

    private fun drawBubble(canvas: Canvas, d: Float, alpha: Float) {
        val p = payload
        if (p?.progress != null) {
            rc.glyphs.drawRing(canvas, d / 2f, d / 2f, d * 0.3f, dp(2.6f), position.value, accent, IslandColors.TRACK, alpha)
        } else {
            val s = d * 0.56f
            drawAppIcon(canvas, (d - s) / 2f, (d - s) / 2f, s, alpha)
        }
    }

    private fun drawCompact(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val s = slot(h)
        val inset = (h - s) / 2f
        drawAppIcon(canvas, inset + rc.burnInX, inset + rc.burnInY, s, a)
        val cy = h / 2f + rc.burnInY
        val right = w - inset - dp(2f) + rc.burnInX
        val p = payload ?: return
        val status = p.shortText
        if (status != null) {
            rc.numberPaint.textSize = rc.sp(15f)
            shortText.set(rc.ellipsize(status, rc.numberPaint, sideSpace(w) - inset), direction = -1, animate = true)
            shortText.draw(canvas, rc, right, rc.baseline(rc.numberPaint, cy), rc.numberPaint, accent, a, Paint.Align.RIGHT)
        } else if (p.progress != null) {
            rc.glyphs.drawRing(canvas, w - h / 2f + rc.burnInX, cy, s / 2f - dp(1.5f), dp(2.6f), position.value, accent, IslandColors.TRACK, a)
        }
    }

    private fun drawCard(canvas: Canvas, alpha: Float) {
        val p = payload ?: return
        val iconSize = dp(40f)
        drawAppIcon(canvas, pad, top + dp(2f), iconSize, alpha)
        val textX = pad + iconSize + dp(12f)
        rc.titlePaint.textSize = rc.sp(16f)
        rc.bodyPaint.textSize = rc.sp(14f)
        rc.text(canvas, titleLine, textX, top + dp(17f), rc.titlePaint, IslandColors.TEXT, alpha)
        rc.text(canvas, textLine, textX, top + dp(37f), rc.bodyPaint, IslandColors.TEXT_SECONDARY, alpha)
        p.shortText?.let {
            rc.numberPaint.textSize = rc.sp(20f)
            rc.text(canvas, it, layoutW - pad, top + dp(20f), rc.numberPaint, accent, alpha, Paint.Align.RIGHT)
        }
        val progress = p.progress ?: return
        drawRoute(canvas, p, progress, alpha)
    }

    /** The route: app segments (or a plain track), stops, and the courier at the current position. */
    private fun drawRoute(canvas: Canvas, p: LiveUpdatePayload, progress: Float, alpha: Float) {
        val y = top + dp(74f)
        val l = pad + dp(8f)
        val r = layoutW - pad - dp(8f)
        val th = dp(6f)
        if (p.segments.isNotEmpty()) {
            var x = l
            val total = p.segments.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.0001f)
            p.segments.forEach { (length, color) ->
                val xr = x + (r - l) * (length / total)
                val c = if (color != 0) ColorExtractor.legibleOnBlack(color) else IslandColors.TRACK
                rc.roundRect(canvas, x + dp(1f), y - th / 2f, xr - dp(1f), y + th / 2f, th / 2f, ColorExtractor.withAlpha(c, 0.45f), alpha)
                x = xr
            }
        } else {
            rc.roundRect(canvas, l, y - th / 2f, r, y + th / 2f, th / 2f, IslandColors.TRACK, alpha)
        }
        val px = lerp(l, r, position.value.coerceIn(0f, 1f))
        rc.roundRect(canvas, l, y - th / 2f, px, y + th / 2f, th / 2f, accent, alpha)
        for (point in p.points) {
            val x = lerp(l, r, point.coerceIn(0f, 1f))
            val reached = point <= progress
            rc.circle(canvas, x, y, dp(5f), 0xFF000000.toInt(), alpha)
            rc.circle(canvas, x, y, dp(3.4f), if (reached) accent else IslandColors.TEXT_TERTIARY, alpha)
        }
        // Courier: bobs slightly while on the way.
        val bob = if (progress < 0.999f) sin(rc.animTime * 6f) * dp(1.2f) else 0f
        val size = dp(24f)
        val cy = y - size / 2f - dp(6f) + bob
        rc.circle(canvas, px, cy, size / 2f + dp(2f), 0xFF000000.toInt(), alpha)
        if (tracker.hasImage) {
            tracker.draw(canvas, px - size / 2f, cy - size / 2f, size, size / 2f, alpha)
        } else {
            rc.circle(canvas, px, cy, size / 2f, ColorExtractor.withAlpha(accent, 0.25f), alpha)
            rc.glyphs.draw(canvas, p.vehicle, px, cy, size * 0.66f, accent, alpha)
        }
    }

    override val contentDescription: String
        get() = payload?.let { listOfNotNull(it.appLabel, it.title, it.shortText).joinToString(", ") } ?: event.title
}
