package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import android.text.StaticLayout
import android.text.TextPaint
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.NavigationPayload
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RoundedImage
import com.arnav.island.util.ColorExtractor

/** Turn-by-turn from navigation notifications (whatever the app publishes, nothing more). */
class NavigationPresenter(rc: RenderContext) : Presenter(rc) {

    private val maneuver = RoundedImage()
    private var nav: NavigationPayload? = null
    private val instructionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var instruction: StaticLayout? = null
    private var distanceLine = ""
    private var footer = ""

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> = when (mode) {
        PresentMode.EXPANDED, PresentMode.TOAST -> expandedWidth to (bandInset + dp(96f))
        else -> super.measure(event, mode)
    }

    override fun onBind(isUpdate: Boolean) {
        nav = event.payload as? NavigationPayload
        maneuver.set(rc.images.get(event.artwork))
        if (mode == PresentMode.EXPANDED || mode == PresentMode.TOAST) {
            val pad = dp(20f)
            val textX = pad + dp(52f) + dp(14f)
            val width = layoutW - pad - textX
            instructionPaint.set(rc.bodyPaint)
            instructionPaint.textSize = rc.sp(15f)
            val text = nav?.instruction?.ifBlank { null } ?: event.subtitle
            instruction = if (text.isBlank()) null else staticLayout(text, instructionPaint, width, 2)
            rc.bigNumberPaint.textSize = rc.sp(24f)
            distanceLine = rc.ellipsize(nav?.distance?.ifBlank { null } ?: event.title, rc.bigNumberPaint, width)
            footer = listOfNotNull(nav?.appLabel?.ifBlank { null }, nav?.eta?.ifBlank { null }).joinToString(" · ")
        }
    }

    private val tint: Int get() = event.colors.accent.takeIf { it != 0 }?.let(ColorExtractor::legibleOnBlack) ?: IslandColors.BLUE

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.EXPANDED, PresentMode.TOAST -> drawExpanded(canvas, alpha)
            PresentMode.BUBBLE -> if (maneuver.hasImage) {
                val s = w * 0.62f
                maneuver.draw(canvas, (w - s) / 2f, (w - s) / 2f, s, s * 0.2f, alpha)
            } else {
                drawBubbleGlyph(canvas, w, Glyph.NAV_ARROW, tint, alpha)
            }
            else -> drawCompact(canvas, w, h, alpha)
        }
    }

    private fun drawCompact(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val s = slot(h)
        val inset = (h - s) / 2f
        val cy = h / 2f + rc.burnInY
        if (maneuver.hasImage) maneuver.draw(canvas, inset + rc.burnInX, inset + rc.burnInY, s, s * 0.2f, a)
        else rc.glyphs.draw(canvas, Glyph.NAV_ARROW, h / 2f + rc.burnInX, cy, s * 0.9f, tint, a)
        rc.numberPaint.textSize = rc.sp(15f)
        val text = rc.ellipsize(nav?.distance?.ifBlank { null } ?: event.title, rc.numberPaint, sideSpace(w) - inset)
        rc.text(canvas, text, w - inset - dp(2f) + rc.burnInX, rc.baseline(rc.numberPaint, cy), rc.numberPaint, IslandColors.TEXT, a, Paint.Align.RIGHT)
    }

    private fun drawExpanded(canvas: Canvas, alpha: Float) {
        val pad = dp(20f)
        val top = bandInset
        val size = dp(52f)
        if (maneuver.hasImage) {
            maneuver.draw(canvas, pad, top, size, dp(12f), alpha)
        } else {
            rc.roundRect(canvas, pad, top, pad + size, top + size, dp(14f), ColorExtractor.withAlpha(tint, 0.2f), alpha)
            rc.glyphs.draw(canvas, Glyph.NAV_ARROW, pad + size / 2f, top + size / 2f, size * 0.6f, tint, alpha)
        }
        val textX = pad + size + dp(14f)
        rc.bigNumberPaint.textSize = rc.sp(24f)
        rc.text(canvas, distanceLine, textX, top + dp(22f), rc.bigNumberPaint, IslandColors.TEXT, alpha)
        instruction?.let { drawLayout(canvas, it, textX, top + dp(30f), IslandColors.TEXT_SECONDARY, alpha) }
        if (footer.isNotEmpty()) {
            rc.captionPaint.textSize = rc.sp(12f)
            rc.text(canvas, footer, pad, layoutH - dp(14f), rc.captionPaint, IslandColors.TEXT_TERTIARY, alpha)
        }
    }

    override val contentDescription: String
        get() = listOfNotNull(nav?.distance, nav?.instruction).joinToString(", ").ifBlank { event.title }
}
