package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.SystemKind
import com.arnav.island.events.SystemPayload
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.util.Formatters
import kotlin.math.sin

/** Ringer, Do Not Disturb, headset, rotation lock, hotspot, clipboard and screen recording. */
class SystemPresenter(rc: RenderContext) : Presenter(rc) {

    private var payload: SystemPayload? = null

    /** Decaying swing used for the bell / vibrate micro-animations. */
    private val swing = Spring(0f, SpringSpec(0.34f, 0.22f), restThreshold = 0.004f)
    private var label = ""

    private val kind: SystemKind? get() = payload?.kind
    private val isRecording get() = event.type == EventType.SCREEN_RECORD || kind == SystemKind.SCREEN_RECORDING

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> {
        return when (mode) {
            PresentMode.EXPANDED -> expandedWidth to (bandInset + dp(58f))
            PresentMode.TOAST -> {
                val text = (event.payload as? SystemPayload)?.label ?: event.title
                rc.labelPaint.textSize = rc.sp(15f)
                val g = rc.geometry
                val h = g.compactHeight
                val s = slot(h)
                val inset = (h - s) / 2f
                val right = g.cameraExclusionHalfWidth + rc.labelPaint.measureText(text) + inset + dp(6f)
                val left = g.cameraExclusionHalfWidth + s + inset
                (maxOf(right, left) * 2f).coerceIn(g.compactWidth, g.expandedWidth) to h
            }
            else -> super.measure(event, mode)
        }
    }

    override fun onBind(isUpdate: Boolean) {
        payload = event.payload as? SystemPayload
        label = payload?.label ?: event.title
        if (!isUpdate) {
            swing.snapTo(1f)
            swing.animateTo(0f)
        }
    }

    override fun step(dt: Float): Boolean {
        val a = super.step(dt)
        val b = swing.step(dt)
        return a || b
    }

    override fun nextFrameDelay(now: Long): Long =
        if (isRecording) (1000 - ((now - event.timestamp) % 1000)).coerceAtLeast(16) else -1

    private fun glyph(): Glyph = when (kind) {
        SystemKind.RINGER_SILENT -> Glyph.BELL_OFF
        SystemKind.RINGER_VIBRATE -> Glyph.VIBRATE
        SystemKind.RINGER_NORMAL -> Glyph.BELL
        SystemKind.DND_ON, SystemKind.DND_OFF -> Glyph.MOON
        SystemKind.HEADSET_IN, SystemKind.HEADSET_OUT -> Glyph.HEADPHONES
        SystemKind.ROTATION_LOCKED -> Glyph.ROTATE_LOCK
        SystemKind.ROTATION_AUTO -> Glyph.ROTATE
        SystemKind.HOTSPOT_ON, SystemKind.HOTSPOT_OFF -> Glyph.HOTSPOT
        SystemKind.CLIPBOARD -> Glyph.CLIPBOARD
        SystemKind.SCREEN_RECORDING -> Glyph.RECORD
        null -> event.icon ?: Glyph.INFO
    }

    private fun color(): Int = when (kind) {
        SystemKind.RINGER_SILENT, SystemKind.SCREEN_RECORDING -> IslandColors.RED
        SystemKind.DND_ON -> IslandColors.PURPLE
        SystemKind.HOTSPOT_ON -> IslandColors.GREEN
        SystemKind.DND_OFF, SystemKind.HEADSET_OUT, SystemKind.HOTSPOT_OFF -> IslandColors.TEXT_SECONDARY
        else -> IslandColors.TEXT
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.EXPANDED -> drawExpanded(canvas, alpha)
            PresentMode.BUBBLE -> drawBubbleGlyph(canvas, w, glyph(), color(), alpha)
            else -> drawPill(canvas, w, h, alpha, now)
        }
    }

    private fun drawAnimatedGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, alpha: Float) {
        val g = glyph()
        canvas.save()
        when (kind) {
            SystemKind.RINGER_SILENT, SystemKind.RINGER_NORMAL -> canvas.rotate(swing.value * 24f, cx, cy - size * 0.4f)
            SystemKind.RINGER_VIBRATE -> canvas.translate(sin(rc.animTime * 70f) * swing.value * size * 0.12f, 0f)
            else -> {
                val s = 1f + swing.value * 0.25f
                canvas.scale(s, s, cx, cy)
            }
        }
        if (isRecording) {
            val pulse = 0.75f + 0.25f * sin(rc.animTime * 4f)
            rc.circle(canvas, cx, cy, size * 0.3f, IslandColors.RED, alpha * pulse)
        } else {
            rc.glyphs.draw(canvas, g, cx, cy, size, color(), alpha)
        }
        canvas.restore()
    }

    private fun drawPill(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val s = slot(h)
        val inset = (h - s) / 2f
        val cy = h / 2f + rc.burnInY
        drawAnimatedGlyph(canvas, h / 2f + rc.burnInX, cy, s * 0.92f, a)
        val right = w - inset - dp(2f) + rc.burnInX
        if (isRecording) {
            rc.numberPaint.textSize = rc.sp(15f)
            rc.text(canvas, Formatters.elapsed(now - event.timestamp), right, rc.baseline(rc.numberPaint, cy), rc.numberPaint, IslandColors.RED, a, Paint.Align.RIGHT)
        } else {
            rc.labelPaint.textSize = rc.sp(15f)
            val text = rc.ellipsize(label, rc.labelPaint, sideSpace(w) - inset)
            rc.text(canvas, text, right, rc.baseline(rc.labelPaint, cy), rc.labelPaint, color(), a, Paint.Align.RIGHT)
        }
    }

    private fun drawExpanded(canvas: Canvas, alpha: Float) {
        val pad = dp(20f)
        val cy = bandInset + dp(22f)
        drawAnimatedGlyph(canvas, pad + dp(16f), cy, dp(30f), alpha)
        val textX = pad + dp(44f)
        rc.titlePaint.textSize = rc.sp(16f)
        rc.captionPaint.textSize = rc.sp(13f)
        val detail = payload?.detail.orEmpty()
        if (detail.isEmpty()) {
            rc.text(canvas, label, textX, rc.baseline(rc.titlePaint, cy), rc.titlePaint, IslandColors.TEXT, alpha)
        } else {
            rc.text(canvas, label, textX, cy - dp(3f), rc.titlePaint, IslandColors.TEXT, alpha)
            rc.text(canvas, rc.ellipsize(detail, rc.captionPaint, layoutW - textX - pad), textX, cy + dp(15f), rc.captionPaint, IslandColors.TEXT_SECONDARY, alpha)
        }
    }

    override val contentDescription: String get() = label
}
