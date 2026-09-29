package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RoundedImage
import com.arnav.island.util.ColorExtractor
import com.arnav.island.util.Formatters

/** Local API activities and developer test events: icon, title, subtitle, optional progress. */
class CustomPresenter(rc: RenderContext) : Presenter(rc) {

    private val image = RoundedImage()
    private val shown = Spring(0f, SpringSpec(0.5f, 0.95f), restThreshold = 0.001f)
    private var titleLine = ""
    private var subtitleLine = ""
    private var pad = 0f
    private var top = 0f

    private val accent: Int
        get() = event.colors.accent.takeIf { it != 0 }?.let(ColorExtractor::legibleOnBlack) ?: IslandColors.BLUE

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> {
        val hasProgress = event.progress != null
        return when (mode) {
            PresentMode.TOAST -> expandedWidth to (bandInset + dp(if (hasProgress) 66f else 54f))
            PresentMode.EXPANDED -> expandedWidth to (bandInset + dp(if (hasProgress) 66f else 54f) + if (event.actions.isNotEmpty()) dp(48f) else 0f)
            else -> super.measure(event, mode)
        }
    }

    override fun onBind(isUpdate: Boolean) {
        image.set(rc.images.get(event.iconImage) ?: rc.images.get(event.artwork))
        val p = event.progress ?: 0f
        if (isUpdate) shown.animateTo(p) else shown.snapTo(p)
        pad = dp(18f)
        top = bandInset
        if (mode == PresentMode.TOAST || mode == PresentMode.EXPANDED) {
            val textW = layoutW - 2 * pad - dp(42f) - dp(12f)
            rc.titlePaint.textSize = rc.sp(15f)
            rc.bodyPaint.textSize = rc.sp(14f)
            titleLine = rc.ellipsize(event.title, rc.titlePaint, textW - if (event.progress != null) dp(44f) else 0f)
            subtitleLine = rc.ellipsize(event.subtitle, rc.bodyPaint, textW)
            if (mode == PresentMode.EXPANDED && event.actions.isNotEmpty()) {
                val actions = event.actions.take(3)
                val gap = dp(8f)
                val bw = (layoutW - 2 * pad - gap * (actions.size - 1)) / actions.size
                val cy = layoutH - dp(12f) - dp(18f)
                actions.forEachIndexed { i, a ->
                    val l = pad + i * (bw + gap)
                    addTarget(a.id, a, l, cy - dp(18f), l + bw, cy + dp(18f))
                }
            }
        }
    }

    override fun step(dt: Float): Boolean {
        val a = super.step(dt)
        val b = shown.step(dt)
        return a || b
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.TOAST, PresentMode.EXPANDED -> drawCard(canvas, alpha)
            PresentMode.BUBBLE -> if (event.progress != null) {
                rc.glyphs.drawRing(canvas, w / 2f, w / 2f, w * 0.3f, dp(2.6f), shown.value, accent, IslandColors.TRACK, alpha)
            } else {
                drawBubbleGlyph(canvas, w, event.icon ?: Glyph.SPARK, accent, alpha)
            }
            else -> drawCompact(canvas, w, h, alpha)
        }
    }

    private fun drawIcon(canvas: Canvas, l: Float, t: Float, s: Float, alpha: Float) {
        if (image.hasImage) image.draw(canvas, l, t, s, s * 0.26f, alpha)
        else rc.glyphs.draw(canvas, event.icon ?: Glyph.SPARK, l + s / 2f, t + s / 2f, s * 0.86f, accent, alpha)
    }

    private fun drawCompact(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val s = slot(h)
        val inset = (h - s) / 2f
        drawIcon(canvas, inset + rc.burnInX, inset + rc.burnInY, s, a)
        val cy = h / 2f + rc.burnInY
        if (event.progress != null) {
            rc.glyphs.drawRing(canvas, w - h / 2f + rc.burnInX, cy, s / 2f - dp(1.5f), dp(2.6f), shown.value, accent, IslandColors.TRACK, a)
        } else {
            rc.labelPaint.textSize = rc.sp(14f)
            val text = rc.ellipsize(event.subtitle.ifBlank { event.title }, rc.labelPaint, sideSpace(w) - inset)
            rc.text(canvas, text, w - inset - dp(2f) + rc.burnInX, rc.baseline(rc.labelPaint, cy), rc.labelPaint, accent, a, Paint.Align.RIGHT)
        }
    }

    private fun drawCard(canvas: Canvas, alpha: Float) {
        val size = dp(42f)
        drawIcon(canvas, pad, top + dp(2f), size, alpha)
        val textX = pad + size + dp(12f)
        rc.titlePaint.textSize = rc.sp(15f)
        rc.bodyPaint.textSize = rc.sp(14f)
        rc.text(canvas, titleLine, textX, top + dp(18f), rc.titlePaint, IslandColors.TEXT, alpha)
        rc.text(canvas, subtitleLine, textX, top + dp(38f), rc.bodyPaint, IslandColors.TEXT_SECONDARY, alpha)
        if (event.progress != null) {
            rc.numberPaint.textSize = rc.sp(13f)
            rc.text(canvas, Formatters.percent(shown.value), layoutW - pad, top + dp(18f), rc.numberPaint, accent, alpha, Paint.Align.RIGHT)
            val y = top + dp(54f)
            val bh = dp(4f)
            rc.roundRect(canvas, textX, y - bh / 2f, layoutW - pad, y + bh / 2f, bh / 2f, IslandColors.TRACK, alpha)
            rc.roundRect(canvas, textX, y - bh / 2f, textX + (layoutW - pad - textX) * shown.value.coerceIn(0f, 1f), y + bh / 2f, bh / 2f, accent, alpha)
        }
        if (mode == PresentMode.EXPANDED) {
            hitTargets.forEach { t ->
                val action = t.action ?: return@forEach
                drawPillButton(canvas, t.id, action.label, t.rect.left, t.rect.top, t.rect.right, t.rect.bottom, action.style, alpha)
            }
        }
    }
}
