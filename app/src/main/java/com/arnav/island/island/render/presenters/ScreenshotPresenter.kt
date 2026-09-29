package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.animation.lerp
import com.arnav.island.events.ActionStyle
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RoundedImage

/**
 * A new screenshot drops into the island like a print landing on a table, with Share and Edit.
 */
class ScreenshotPresenter(rc: RenderContext) : Presenter(rc) {

    private val thumb = RoundedImage()
    private var aspect = 0.5f
    private val drop = Spring(0f, SpringSpec(0.5f, 0.62f), restThreshold = 0.002f)
    private var pad = 0f
    private var top = 0f

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> = when (mode) {
        PresentMode.EXPANDED, PresentMode.TOAST -> expandedWidth to (bandInset + dp(96f))
        else -> super.measure(event, mode)
    }

    override fun onBind(isUpdate: Boolean) {
        val bmp = rc.images.get(event.artwork)
        thumb.set(bmp)
        aspect = if (bmp != null && bmp.height > 0) (bmp.width / bmp.height.toFloat()).coerceIn(0.3f, 1.2f) else 0.5f
        if (!isUpdate) {
            drop.snapTo(1f)
            drop.animateTo(0f)
        }
        pad = dp(18f)
        top = bandInset
        if (mode == PresentMode.EXPANDED || mode == PresentMode.TOAST) {
            val cy = top + dp(40f)
            var x = layoutW - pad - dp(22f)
            // Right to left: Edit, then Share.
            for (id in listOf(ACTION_EDIT, ACTION_SHARE)) {
                event.actions.firstOrNull { it.id == id }?.let { addCircleTarget(id, it, x, cy, dp(22f)) }
                x -= dp(54f)
            }
        }
    }

    override fun step(dt: Float): Boolean {
        val a = super.step(dt)
        val b = drop.step(dt)
        return a || b
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        if (mode != PresentMode.EXPANDED && mode != PresentMode.TOAST) {
            val a = alpha * compactVisibility(w, h)
            val s = slot(h)
            rc.glyphs.draw(canvas, Glyph.SCREENSHOT, h / 2f + rc.burnInX, h / 2f + rc.burnInY, s * 0.86f, IslandColors.TEXT, a)
            return
        }
        // Thumbnail: tilted and slightly large as it lands, then settles flat.
        val th = dp(72f)
        val tw = th * aspect
        val l = pad
        val t = top + dp(4f)
        val d = drop.value
        canvas.save()
        canvas.rotate(-7f * d, l + tw / 2f, t + th / 2f)
        val s = lerp(1f, 1.18f, d)
        canvas.scale(s, s, l + tw / 2f, t + th / 2f)
        rc.roundRect(canvas, l - dp(1.5f), t - dp(1.5f), l + tw + dp(1.5f), t + th + dp(1.5f), dp(9f), 0x33FFFFFF, alpha)
        if (thumb.hasImage) {
            // RoundedImage is square; draw a centre crop of the screenshot into the portrait frame.
            canvas.save()
            rc.rect.set(l, t, l + tw, t + th)
            canvas.clipRect(rc.rect)
            thumb.draw(canvas, l + tw / 2f - th / 2f, t, th, dp(8f), alpha)
            canvas.restore()
        } else {
            rc.roundRect(canvas, l, t, l + tw, t + th, dp(8f), IslandColors.CONTROL, alpha)
        }
        canvas.restore()

        val textX = l + tw + dp(16f)
        rc.titlePaint.textSize = rc.sp(16f)
        rc.captionPaint.textSize = rc.sp(13f)
        rc.text(canvas, "Screenshot saved", textX, top + dp(34f), rc.titlePaint, IslandColors.TEXT, alpha)
        rc.text(canvas, "Tap to open", textX, top + dp(54f), rc.captionPaint, IslandColors.TEXT_SECONDARY, alpha)
        hitTargets.forEach { tg ->
            val glyph = if (tg.id == ACTION_SHARE) Glyph.SHARE else Glyph.EDIT
            drawCircleButton(canvas, tg.id, glyph, tg.rect.centerX(), tg.rect.centerY(), tg.rect.width() / 2f, ActionStyle.DEFAULT, alpha, glyphScale = 0.44f)
        }
    }

    override val contentDescription: String get() = "Screenshot saved"

    companion object {
        const val ACTION_SHARE = "shot.share"
        const val ACTION_EDIT = "shot.edit"
    }
}
