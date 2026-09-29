package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.animation.lerp
import com.arnav.island.events.ActionStyle
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.TorchPayload
import com.arnav.island.island.render.HitKind
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import kotlin.math.roundToInt

/**
 * Flashlight: a warm glyph that glows with the torch level in the pill, and a card with a
 * stepped brightness slider (the camera's own strength range) and a turn-off button.
 */
class TorchPresenter(rc: RenderContext) : Presenter(rc) {

    private var payload: TorchPayload? = null
    private val level = Spring(0f, SpringSpec(0.3f, 0.86f), restThreshold = 0.002f)
    private val beam = Spring(0f, SpringSpec(0.55f, 0.72f), restThreshold = 0.002f)
    private var dragFraction: Float? = null

    private val glowMatrix = Matrix()
    private val glow = RadialGradient(0f, 0f, 1f, intArrayOf(0x88FFD27A.toInt(), 0x33FFD27A, 0x00FFD27A), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)

    private var pad = 0f
    private var top = 0f
    private var sliderY = 0f
    private var sliderL = 0f
    private var sliderR = 0f

    private val steps: Int get() = (payload?.maxLevel ?: 1).coerceAtLeast(1)
    private val fraction: Float
        get() = payload?.let { if (it.maxLevel <= 1) 1f else it.level / it.maxLevel.toFloat() } ?: 1f

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> = when (mode) {
        PresentMode.EXPANDED, PresentMode.TOAST -> expandedWidth to (bandInset + dp(118f))
        else -> super.measure(event, mode)
    }

    override fun onBind(isUpdate: Boolean) {
        payload = event.payload as? TorchPayload
        if (isUpdate) level.animateTo(fraction) else level.snapTo(fraction)
        if (!isUpdate) {
            beam.snapTo(0f)
            beam.animateTo(1f)
        }
        pad = dp(20f)
        top = bandInset
        if (mode == PresentMode.EXPANDED || mode == PresentMode.TOAST) {
            sliderY = top + dp(76f)
            sliderL = pad
            sliderR = layoutW - pad - dp(112f)
            if (payload?.setLevel != null && steps > 1) {
                addTarget(TARGET_LEVEL, null, sliderL - dp(6f), sliderY - dp(18f), sliderR + dp(6f), sliderY + dp(18f), HitKind.SEEK)
            }
            event.actions.firstOrNull { it.id == ACTION_OFF }?.let { off ->
                addTarget(ACTION_OFF, off, layoutW - pad - dp(96f), sliderY - dp(19f), layoutW - pad, sliderY + dp(19f))
            }
        }
    }

    override fun step(dt: Float): Boolean {
        var moving = super.step(dt)
        if (level.step(dt)) moving = true
        if (beam.step(dt)) moving = true
        return moving
    }

    override fun onScrub(fraction: Float) {
        val snapped = quantize(fraction)
        if (snapped != dragFraction) {
            dragFraction = snapped
            level.animateTo(snapped)
            // Each step is a real torch level: apply as the finger crosses it.
            payload?.setLevel?.invoke?.invoke(snapped)
        }
    }

    override fun onScrubEnd(fraction: Float) {
        val snapped = quantize(fraction)
        payload?.setLevel?.invoke?.invoke(snapped)
        dragFraction = null
    }

    private fun quantize(f: Float): Float {
        val n = steps
        val step = (f.coerceIn(0f, 1f) * n).roundToInt().coerceIn(1, n)
        return step / n.toFloat()
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.EXPANDED, PresentMode.TOAST -> drawCard(canvas, alpha)
            PresentMode.BUBBLE -> drawGlowingIcon(canvas, w / 2f + rc.burnInX, w / 2f + rc.burnInY, w * 0.46f, alpha)
            else -> drawCompact(canvas, w, h, alpha)
        }
    }

    private fun drawGlowingIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, alpha: Float) {
        val intensity = level.value.coerceIn(0.15f, 1f) * beam.value
        val r = size * lerp(0.7f, 1.25f, intensity)
        glowMatrix.setScale(r, r)
        glowMatrix.postTranslate(cx, cy)
        glow.setLocalMatrix(glowMatrix)
        rc.fill.shader = glow
        rc.fill.alpha = (255 * alpha * intensity).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r, rc.fill)
        rc.fill.shader = null
        rc.glyphs.draw(canvas, Glyph.FLASHLIGHT, cx, cy, size, WARM, alpha)
    }

    private fun drawCompact(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val s = slot(h)
        val cy = h / 2f + rc.burnInY
        drawGlowingIcon(canvas, h / 2f + rc.burnInX, cy, s * 0.9f, a)
        // Level as a row of small bars, one per torch step (max 5 shown).
        val shown = steps.coerceAtMost(5)
        val filled = (level.value * shown).roundToInt().coerceIn(1, shown)
        val barW = dp(3f)
        val gap = dp(2.5f)
        var x = w - (h - s) / 2f - dp(2f) + rc.burnInX - shown * barW - (shown - 1) * gap
        for (i in 0 until shown) {
            val bh = lerp(s * 0.35f, s * 0.8f, i / (shown - 1).coerceAtLeast(1).toFloat())
            rc.roundRect(canvas, x, cy - bh / 2f, x + barW, cy + bh / 2f, barW / 2f, if (i < filled) WARM else IslandColors.TRACK, a)
            x += barW + gap
        }
    }

    private fun drawCard(canvas: Canvas, alpha: Float) {
        val iconSize = dp(34f)
        drawGlowingIcon(canvas, pad + iconSize / 2f, top + dp(22f), iconSize, alpha)
        val textX = pad + iconSize + dp(14f)
        rc.titlePaint.textSize = rc.sp(16f)
        rc.captionPaint.textSize = rc.sp(13f)
        rc.text(canvas, "Flashlight", textX, top + dp(19f), rc.titlePaint, IslandColors.TEXT, alpha)
        val p = payload
        val caption = if (p != null && p.maxLevel > 1) {
            "Brightness ${(level.value * p.maxLevel).roundToInt().coerceIn(1, p.maxLevel)} of ${p.maxLevel}"
        } else {
            "On"
        }
        rc.text(canvas, caption, textX, top + dp(38f), rc.captionPaint, IslandColors.TEXT_SECONDARY, alpha)

        if (p?.setLevel != null && steps > 1) {
            val bh = dp(10f)
            rc.roundRect(canvas, sliderL, sliderY - bh / 2f, sliderR, sliderY + bh / 2f, bh / 2f, IslandColors.TRACK, alpha)
            val fillR = lerp(sliderL + bh, sliderR, level.value.coerceIn(0f, 1f))
            rc.roundRect(canvas, sliderL, sliderY - bh / 2f, fillR, sliderY + bh / 2f, bh / 2f, WARM, alpha)
            // Step ticks.
            for (i in 1 until steps) {
                val tx = lerp(sliderL + bh, sliderR, i / steps.toFloat())
                rc.circle(canvas, tx, sliderY, dp(1.4f), if (tx < fillR) 0x66000000 else IslandColors.TEXT_TERTIARY, alpha)
            }
        }
        hitTargets.firstOrNull { it.id == ACTION_OFF }?.let { t ->
            drawPillButton(canvas, t.id, t.action?.label ?: "Turn off", t.rect.left, t.rect.top, t.rect.right, t.rect.bottom, ActionStyle.DEFAULT, alpha)
        }
    }

    override val contentDescription: String
        get() = payload?.let { "Flashlight on, brightness ${it.level} of ${it.maxLevel}" } ?: "Flashlight on"

    companion object {
        const val ACTION_OFF = "torch.off"
        const val TARGET_LEVEL = "torch.level"
        private val WARM = 0xFFFFD27A.toInt()
    }
}
