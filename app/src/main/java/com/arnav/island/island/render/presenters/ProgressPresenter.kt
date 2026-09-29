package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.ProgressPayload
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RoundedImage
import com.arnav.island.util.Formatters

/** Downloads, transfers and anything else a notification reports progress for. */
class ProgressPresenter(rc: RenderContext) : Presenter(rc) {

    private val icon = RoundedImage()
    private var progress: ProgressPayload? = null
    private val shown = Spring(0f, SpringSpec(0.5f, 0.95f), restThreshold = 0.001f)
    private val check = Spring(0f, SpringSpec(0.45f, 1f), restThreshold = 0.002f)
    private val checkPath = Path()
    private val checkSegment = Path()
    private val measure = PathMeasure()
    private var titleLine = ""

    private val isDone get() = event.type == EventType.PROGRESS_DONE || progress?.done == true
    private val indeterminate get() = progress?.indeterminate == true && !isDone

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> = when (mode) {
        PresentMode.EXPANDED -> expandedWidth to (bandInset + dp(74f))
        PresentMode.TOAST -> (rc.geometry.compactWidth + dp(16f)) to rc.geometry.compactHeight
        else -> super.measure(event, mode)
    }

    override fun onBind(isUpdate: Boolean) {
        progress = event.payload as? ProgressPayload
        icon.set(rc.images.get(event.iconImage))
        val target = if (isDone) 1f else progress?.progress ?: event.progress ?: 0f
        if (isUpdate) shown.animateTo(target) else shown.snapTo(target)
        if (isDone) {
            if (!isUpdate) check.snapTo(0f)
            check.animateTo(1f)
        } else {
            check.snapTo(0f)
        }
        if (mode == PresentMode.EXPANDED) {
            rc.titlePaint.textSize = rc.sp(15f)
            titleLine = rc.ellipsize(progress?.title?.ifBlank { null } ?: event.title, rc.titlePaint, layoutW - dp(20f) * 2 - dp(54f) - dp(56f))
        }
    }

    override fun step(dt: Float): Boolean {
        var moving = super.step(dt)
        if (shown.step(dt)) moving = true
        if (check.step(dt)) moving = true
        return moving
    }

    override fun nextFrameDelay(now: Long): Long = if (indeterminate) rc.settings.decorativeFrameMs else -1

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.EXPANDED -> drawExpanded(canvas, alpha)
            PresentMode.BUBBLE -> drawRingOrCheck(canvas, w / 2f + rc.burnInX, w / 2f + rc.burnInY, w * 0.3f, alpha)
            else -> drawCompact(canvas, w, h, alpha)
        }
    }

    private fun drawCompact(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val s = slot(h)
        val inset = (h - s) / 2f
        drawIcon(canvas, inset + rc.burnInX, inset + rc.burnInY, s, a)
        drawRingOrCheck(canvas, w - h / 2f + rc.burnInX, h / 2f + rc.burnInY, s / 2f - dp(1.5f), a)
    }

    private fun drawIcon(canvas: Canvas, l: Float, t: Float, s: Float, a: Float) {
        if (icon.hasImage) icon.draw(canvas, l, t, s, s * 0.26f, a)
        else rc.glyphs.draw(canvas, event.icon ?: Glyph.DOWNLOAD, l + s / 2f, t + s / 2f, s * 0.85f, IslandColors.BLUE, a)
    }

    private fun drawRingOrCheck(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
        val thickness = dp(2.6f)
        if (isDone) {
            rc.circle(canvas, cx, cy, r + thickness / 2f, IslandColors.GREEN, alpha)
            drawCheck(canvas, cx, cy, r * 1.2f, alpha)
            return
        }
        if (indeterminate) {
            rc.glyphs.drawRing(canvas, cx, cy, r, thickness, 0.28f, IslandColors.BLUE, IslandColors.TRACK, alpha, startAngle = (rc.animTime * 300f) % 360f)
        } else {
            rc.glyphs.drawRing(canvas, cx, cy, r, thickness, shown.value, IslandColors.BLUE, IslandColors.TRACK, alpha)
        }
    }

    /** Check mark drawn on with a trim-path animation. */
    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, size: Float, alpha: Float) {
        val s = size / 24f
        checkPath.rewind()
        checkPath.moveTo(cx + (5.5f - 12f) * s, cy + (12.5f - 12f) * s)
        checkPath.lineTo(cx + (10f - 12f) * s, cy + (17f - 12f) * s)
        checkPath.lineTo(cx + (18.5f - 12f) * s, cy + (7.5f - 12f) * s)
        measure.setPath(checkPath, false)
        checkSegment.rewind()
        measure.getSegment(0f, measure.length * check.value.coerceIn(0f, 1f), checkSegment, true)
        rc.stroke.shader = null
        rc.stroke.strokeWidth = 2.6f * s
        rc.stroke.color = 0xFF000000.toInt()
        rc.stroke.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(checkSegment, rc.stroke)
    }

    private fun drawExpanded(canvas: Canvas, alpha: Float) {
        val pad = dp(20f)
        val top = bandInset
        val size = dp(42f)
        drawIcon(canvas, pad, top, size, alpha)
        val textX = pad + size + dp(12f)
        rc.titlePaint.textSize = rc.sp(15f)
        rc.captionPaint.textSize = rc.sp(12f)
        rc.text(canvas, titleLine, textX, top + dp(17f), rc.titlePaint, IslandColors.TEXT, alpha)
        rc.text(canvas, progress?.appLabel.orEmpty(), textX, top + dp(35f), rc.captionPaint, IslandColors.TEXT_SECONDARY, alpha)

        val barY = top + size + dp(16f)
        val l = pad
        val r = layoutW - pad - dp(52f)
        val bh = dp(5f)
        rc.roundRect(canvas, l, barY - bh / 2f, r, barY + bh / 2f, bh / 2f, IslandColors.TRACK, alpha)
        if (indeterminate) {
            val seg = (r - l) * 0.28f
            val x = l + ((rc.animTime * 0.8f) % 1f) * (r - l + seg) - seg
            canvas.save()
            rc.rect2.set(l, barY - bh, r, barY + bh)
            canvas.clipRect(rc.rect2)
            rc.roundRect(canvas, x, barY - bh / 2f, x + seg, barY + bh / 2f, bh / 2f, IslandColors.BLUE, alpha)
            canvas.restore()
        } else {
            val color = if (isDone) IslandColors.GREEN else IslandColors.BLUE
            rc.roundRect(canvas, l, barY - bh / 2f, l + (r - l) * shown.value.coerceIn(0f, 1f), barY + bh / 2f, bh / 2f, color, alpha)
            rc.numberPaint.textSize = rc.sp(14f)
            rc.text(canvas, if (isDone) "Done" else Formatters.percent(shown.value), layoutW - pad, rc.baseline(rc.numberPaint, barY), rc.numberPaint, IslandColors.TEXT, alpha, Paint.Align.RIGHT)
        }
    }

    override val contentDescription: String
        get() = if (isDone) "${event.title} complete" else "${event.title}, ${Formatters.percent(shown.value)}"
}
