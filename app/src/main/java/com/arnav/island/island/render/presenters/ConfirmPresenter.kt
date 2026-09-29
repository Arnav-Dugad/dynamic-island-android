package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.animation.lerp
import com.arnav.island.events.ConfirmPayload
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.IslandHaptics
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RoundedImage
import com.arnav.island.util.ColorExtractor
import kotlin.math.max
import kotlin.math.sin

/**
 * A small pill that confirms something the user did from the island: three soft dots while it
 * goes out, then a check that pops in ("Reply sent").
 */
class ConfirmPresenter(rc: RenderContext) : Presenter(rc) {

    private var payload: ConfirmPayload? = null
    private val icon = RoundedImage()
    private var boundAt = 0L
    private val done = Spring(0f, SpringSpec(0.34f, 0.5f), restThreshold = 0.002f)
    private var labelLine = ""
    private var pendingLine = ""

    private val accent: Int
        get() = event.colors.accent.takeIf { it != 0 }?.let(ColorExtractor::legibleOnBlack) ?: IslandColors.GREEN

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> =
        if (mode == PresentMode.TOAST || mode == PresentMode.EXPANDED) mediumWidth to rc.geometry.compactHeight else super.measure(event, mode)

    override fun onBind(isUpdate: Boolean) {
        payload = event.payload as? ConfirmPayload
        icon.set(rc.images.get(event.iconImage))
        if (!isUpdate) {
            boundAt = nowElapsed
            done.snapTo(0f)
        }
        rc.labelPaint.textSize = rc.sp(14f)
        val room = max(0f, layoutW / 2f - rc.geometry.cameraExclusionHalfWidth - dp(36f))
        labelLine = rc.ellipsize(payload?.label ?: event.title, rc.labelPaint, room)
        pendingLine = rc.ellipsize("Sending", rc.labelPaint, room)
    }

    private val pending: Boolean get() = nowElapsed - boundAt < (payload?.pendingMs ?: 0L)

    override fun step(dt: Float): Boolean {
        var moving = super.step(dt)
        if (!pending && done.target < 1f) {
            done.animateTo(1f)
            if (rc.settings.haptics) rc.haptics?.play(IslandHaptics.Cue.CONFIRM, touch = false)
        }
        if (done.step(dt)) moving = true
        return moving || pending
    }

    override fun nextFrameDelay(now: Long): Long = if (pending || !done.isAtRest) 0 else -1

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        val a = if (mode == PresentMode.TOAST || mode == PresentMode.EXPANDED) alpha else alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val s = slot(h)
        val inset = (h - s) / 2f
        val cy = h / 2f + rc.burnInY
        if (icon.hasImage) icon.draw(canvas, inset + rc.burnInX, inset + rc.burnInY, s, s * 0.26f, a)
        else rc.glyphs.draw(canvas, Glyph.REPLY, h / 2f + rc.burnInX, cy, s * 0.86f, accent, a)

        // Label cross-fades from "Sending" to the result.
        rc.labelPaint.textSize = rc.sp(14f)
        val textX = inset + s + dp(8f) + rc.burnInX
        val d = done.value.coerceIn(0f, 1f)
        rc.text(canvas, pendingLine, textX, rc.baseline(rc.labelPaint, cy), rc.labelPaint, IslandColors.TEXT_SECONDARY, a * (1f - d))
        rc.text(canvas, labelLine, textX, rc.baseline(rc.labelPaint, cy), rc.labelPaint, IslandColors.TEXT, a * d)

        val rcx = w - h / 2f + rc.burnInX
        if (d < 0.999f) {
            // Typing dots, fading out as the check arrives.
            val t = rc.animTime
            for (i in 0..2) {
                val lift = (sin(t * 9f - i * 0.9f) * 0.5f + 0.5f)
                val x = rcx - dp(9f) + i * dp(9f)
                rc.circle(canvas, x, cy - lift * dp(3f), dp(2.6f), IslandColors.TEXT_SECONDARY, a * (1f - d))
            }
        }
        if (d > 0.001f) {
            val r = lerp(0f, s / 2f, done.value)
            rc.circle(canvas, rcx, cy, r, accent, a)
            rc.glyphs.draw(canvas, Glyph.CHECK, rcx, cy, r * 1.4f, 0xFF000000.toInt(), a)
        }
    }

    override val contentDescription: String
        get() = payload?.let { "${it.label}. ${it.detail}" } ?: event.title
}
