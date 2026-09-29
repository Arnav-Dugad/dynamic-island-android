package com.arnav.island.island.render.presenters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.arnav.island.events.ActionStyle
import com.arnav.island.events.CallPayload
import com.arnav.island.events.CallState
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RoundedImage
import com.arnav.island.util.Formatters
import kotlin.math.sin

/**
 * Incoming and ongoing calls. Buttons only appear when the dialer's own notification exposes the
 * matching PendingIntent (answer / decline / hang up); nothing is simulated.
 */
class CallPresenter(rc: RenderContext) : Presenter(rc) {

    private val avatar = RoundedImage()
    private var avatarBitmap: Bitmap? = null
    private var call: CallPayload? = null
    private var nameLine = ""
    private var initials = ""
    private var pad = 0f
    private var cy = 0f

    private val incoming get() = call?.state == CallState.INCOMING

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> = when (mode) {
        PresentMode.EXPANDED, PresentMode.TOAST -> expandedWidth to (bandInset + dp(68f))
        else -> super.measure(event, mode)
    }

    override fun onBind(isUpdate: Boolean) {
        call = event.payload as? CallPayload
        avatarBitmap = rc.images.get(event.artwork)
        avatar.set(avatarBitmap)
        val name = call?.callerName?.ifBlank { null } ?: event.title.ifBlank { "Call" }
        initials = name.split(' ', '-').filter { it.isNotBlank() && it.first().isLetter() }.take(2).joinToString("") { it.first().uppercase() }
        pad = dp(18f)
        cy = bandInset + dp(28f)
        if (mode == PresentMode.EXPANDED || mode == PresentMode.TOAST) {
            val buttons = buttonIds()
            var x = layoutW - pad - dp(25f)
            for (id in buttons.reversed()) {
                addCircleTarget(id, event.actions.firstOrNull { it.id == id }, x, cy, dp(27f))
                x -= dp(62f)
            }
            rc.titlePaint.textSize = rc.sp(17f)
            val textX = pad + dp(50f) + dp(12f)
            val textRight = x + dp(62f) - dp(25f) - dp(10f)
            nameLine = rc.ellipsize(name, rc.titlePaint, textRight - textX)
        } else {
            nameLine = name
        }
    }

    /** Ordered left to right. */
    private fun buttonIds(): List<String> {
        val ids = event.actions.map { it.id }
        return if (incoming) {
            listOf(ACTION_DECLINE, ACTION_ANSWER).filter { it in ids }
        } else {
            listOf(ACTION_OPEN, ACTION_HANGUP).filter { it in ids }
        }
    }

    override fun nextFrameDelay(now: Long): Long {
        if (incoming) return rc.settings.decorativeFrameMs
        val base = call?.chronometerBase ?: return -1
        return 1000 - ((now - base) % 1000).coerceAtLeast(0) + 4
    }

    private fun durationText(now: Long): String? = call?.chronometerBase?.let { Formatters.elapsed(now - it) }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.EXPANDED, PresentMode.TOAST -> drawExpanded(canvas, alpha, now)
            PresentMode.BUBBLE -> drawBubbleGlyph(canvas, w, Glyph.PHONE, IslandColors.GREEN, alpha)
            else -> drawCompact(canvas, w, h, alpha, now)
        }
    }

    private fun drawCompact(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val s = slot(h)
        val inset = (h - s) / 2f
        val cy = h / 2f + rc.burnInY
        if (avatar.hasImage) {
            if (!heroHidden) avatar.draw(canvas, inset + rc.burnInX, inset + rc.burnInY, s, s / 2f, a)
        } else {
            rc.glyphs.draw(canvas, Glyph.PHONE, h / 2f + rc.burnInX, cy, s * 0.9f, IslandColors.GREEN, a)
        }
        val right = w - inset + rc.burnInX
        if (incoming) {
            // Ringing: the handset rocks like a bell.
            val angle = sin(rc.animTime * 22f) * 14f * (0.5f + 0.5f * sin(rc.animTime * 3f).coerceAtLeast(0f))
            canvas.save()
            canvas.rotate(angle, right - s / 2f, cy)
            rc.circle(canvas, right - s / 2f, cy, s / 2f, IslandColors.GREEN, a)
            rc.glyphs.draw(canvas, Glyph.PHONE, right - s / 2f, cy, s * 0.62f, IslandColors.TEXT, a)
            canvas.restore()
        } else {
            rc.numberPaint.textSize = rc.sp(15f)
            val label = durationText(now) ?: "Call"
            rc.text(canvas, label, right - dp(2f), rc.baseline(rc.numberPaint, cy), rc.numberPaint, IslandColors.GREEN, a, Paint.Align.RIGHT)
        }
    }

    private fun drawExpanded(canvas: Canvas, alpha: Float, now: Long) {
        val size = dp(50f)
        val l = pad
        val t = cy - size / 2f
        if (avatar.hasImage) {
            if (!heroHidden) avatar.draw(canvas, l, t, size, size / 2f, alpha)
        } else {
            rc.circle(canvas, l + size / 2f, cy, size / 2f, IslandColors.CONTROL, alpha)
            if (initials.isNotEmpty()) {
                rc.titlePaint.textSize = rc.sp(18f)
                rc.text(canvas, initials, l + size / 2f, rc.baseline(rc.titlePaint, cy), rc.titlePaint, IslandColors.TEXT, alpha, Paint.Align.CENTER)
            } else {
                rc.glyphs.draw(canvas, Glyph.PHONE, l + size / 2f, cy, size * 0.46f, IslandColors.TEXT, alpha)
            }
        }
        val textX = l + size + dp(12f)
        rc.titlePaint.textSize = rc.sp(17f)
        rc.text(canvas, nameLine, textX, cy - dp(3f), rc.titlePaint, IslandColors.TEXT, alpha)

        val c = call
        val detail = when {
            c == null -> event.subtitle
            incoming -> c.detail.ifBlank { if (c.isVideo) "Incoming video call" else "Incoming call" }
            else -> durationText(now) ?: c.detail.ifBlank { c.appLabel }
        }
        rc.captionPaint.textSize = rc.sp(13f)
        val detailColor = if (!incoming && c?.chronometerBase != null) IslandColors.GREEN else IslandColors.TEXT_SECONDARY
        val maxDetail = (hitTargets.minOfOrNull { it.rect.left } ?: (layoutW - pad)) - textX - dp(8f)
        rc.text(canvas, rc.ellipsize(detail, rc.captionPaint, maxDetail), textX, cy + dp(16f), rc.captionPaint, detailColor, alpha)

        for (target in hitTargets) {
            val bx = target.rect.centerX()
            when (target.id) {
                ACTION_ANSWER -> drawCircleButton(canvas, target.id, if (c?.isVideo == true) Glyph.VIDEO else Glyph.PHONE, bx, cy, dp(25f), ActionStyle.POSITIVE, alpha, glyphScale = 0.48f)
                ACTION_DECLINE, ACTION_HANGUP -> drawCircleButton(canvas, target.id, Glyph.PHONE_DOWN, bx, cy, dp(25f), ActionStyle.DESTRUCTIVE, alpha, glyphScale = 0.5f)
                ACTION_OPEN -> drawCircleButton(canvas, target.id, Glyph.OPEN, bx, cy, dp(25f), ActionStyle.DEFAULT, alpha, glyphScale = 0.42f)
            }
        }
    }

    /** The caller's photo glides between the compact pill and the call card. */
    override fun heroSlot(w: Float, h: Float, out: RectF): Boolean {
        if (avatarBitmap == null) return false
        when (mode) {
            PresentMode.EXPANDED, PresentMode.TOAST -> {
                val size = dp(50f)
                out.set(pad, cy - size / 2f, pad + size, cy + size / 2f)
            }
            PresentMode.COMPACT, PresentMode.SPLIT_MAIN -> {
                if (compactVisibility(w, h) < 0.5f) return false
                val s = slot(h)
                val inset = (h - s) / 2f
                out.set(inset + rc.burnInX, inset + rc.burnInY, inset + rc.burnInX + s, inset + rc.burnInY + s)
            }
            else -> return false
        }
        return true
    }

    override val heroBitmap: Bitmap? get() = avatarBitmap

    override fun heroRadius(size: Float): Float = size / 2f

    override val contentDescription: String
        get() = if (incoming) "Incoming call from $nameLine" else "Call with $nameLine"

    companion object {
        const val ACTION_ANSWER = "call.answer"
        const val ACTION_DECLINE = "call.decline"
        const val ACTION_HANGUP = "call.hangup"
        const val ACTION_OPEN = "call.open"
    }
}
