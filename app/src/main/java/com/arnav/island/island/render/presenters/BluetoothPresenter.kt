package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.animation.lerp
import com.arnav.island.events.BluetoothDeviceKind
import com.arnav.island.events.BluetoothPayload
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.util.ColorExtractor

/** Bluetooth arrival/departure with an original "device lands in the island" animation. */
class BluetoothPresenter(rc: RenderContext) : Presenter(rc) {

    private var payload: BluetoothPayload? = null
    private val arrival = Spring(0f, SpringSpec(0.5f, 0.55f), restThreshold = 0.002f)
    private val ripple = Spring(0f, SpringSpec(0.9f, 1f), restThreshold = 0.002f)
    private var nameLine = ""

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> = when (mode) {
        PresentMode.TOAST -> mediumWidth to (bandInset + dp(56f))
        PresentMode.EXPANDED -> expandedWidth to (bandInset + dp(60f))
        else -> super.measure(event, mode)
    }

    override fun onBind(isUpdate: Boolean) {
        payload = event.payload as? BluetoothPayload
        if (!isUpdate) {
            arrival.snapTo(0f)
            arrival.animateTo(1f)
            ripple.snapTo(0f)
            if (payload?.connected == true) ripple.animateTo(1f)
        }
        rc.titlePaint.textSize = rc.sp(15f)
        val maxW = layoutW - dp(18f) * 2 - dp(52f) - (if (payload?.batteryLevel != null) dp(70f) else dp(8f))
        nameLine = rc.ellipsize(payload?.deviceName ?: event.title, rc.titlePaint, maxW)
    }

    override fun step(dt: Float): Boolean {
        var moving = super.step(dt)
        if (arrival.step(dt)) moving = true
        if (ripple.step(dt)) moving = true
        return moving
    }

    private fun glyph(): Glyph = when (payload?.kind) {
        BluetoothDeviceKind.HEADPHONES -> Glyph.HEADPHONES
        BluetoothDeviceKind.EARBUDS -> Glyph.EARBUDS
        BluetoothDeviceKind.WATCH -> Glyph.WATCH
        BluetoothDeviceKind.SPEAKER -> Glyph.SPEAKER
        BluetoothDeviceKind.CAR -> Glyph.CAR
        BluetoothDeviceKind.PHONE -> Glyph.DEVICE
        BluetoothDeviceKind.COMPUTER -> Glyph.CHIP
        else -> Glyph.BLUETOOTH
    }

    private val tint: Int
        get() = if (payload?.connected == false) IslandColors.TEXT_SECONDARY else event.colors.accent.takeIf { it != 0 } ?: IslandColors.BLUE

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.TOAST, PresentMode.EXPANDED -> drawCard(canvas, alpha)
            PresentMode.BUBBLE -> drawBubbleGlyph(canvas, w, glyph(), tint, alpha)
            else -> drawCompact(canvas, w, h, alpha)
        }
    }

    private fun drawCompact(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val s = slot(h)
        val cy = h / 2f + rc.burnInY
        rc.glyphs.draw(canvas, glyph(), h / 2f + rc.burnInX, cy, s, tint, a)
        val level = payload?.batteryLevel
        val right = w - (h - s) / 2f + rc.burnInX
        if (level != null) {
            rc.glyphs.drawRing(canvas, right - s / 2f, cy, s / 2f - dp(1.5f), dp(2.4f), level / 100f, batteryColor(level), IslandColors.TRACK, a)
        } else {
            rc.glyphs.draw(canvas, if (payload?.connected == false) Glyph.CLOSE else Glyph.CHECK, right - s / 2f, cy, s * 0.8f, tint, a)
        }
    }

    private fun batteryColor(level: Int) = if (level <= 20) IslandColors.RED else IslandColors.GREEN

    private fun drawCard(canvas: Canvas, alpha: Float) {
        val pad = dp(18f)
        val cy = bandInset + dp(24f)
        val r = dp(21f)
        val cx = pad + r
        val p = arrival.value

        // Ripple ring: the device "arrives" and sends a wave outward.
        val rp = ripple.value
        if (rp > 0.001f && rp < 0.999f) {
            rc.stroke.shader = null
            rc.stroke.strokeWidth = dp(1.6f)
            rc.stroke.color = tint
            rc.stroke.alpha = (140 * (1f - rp) * alpha).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, r + dp(16f) * rp, rc.stroke)
        }
        rc.circle(canvas, cx, cy, r * lerp(0.7f, 1f, p), ColorExtractor.withAlpha(tint, 0.2f), alpha)
        val drop = (1f - p) * -dp(10f)
        rc.glyphs.draw(canvas, glyph(), cx, cy + drop, dp(24f) * lerp(0.5f, 1f, p), tint, alpha * p.coerceIn(0f, 1f))

        val textX = cx + r + dp(12f)
        rc.titlePaint.textSize = rc.sp(15f)
        rc.captionPaint.textSize = rc.sp(12f)
        rc.text(canvas, nameLine, textX, cy - dp(2f), rc.titlePaint, IslandColors.TEXT, alpha)
        val connected = payload?.connected != false
        val status = if (connected) "Connected" else "Disconnected"
        if (connected) rc.circle(canvas, textX + dp(3f), cy + dp(10.5f), dp(3f), IslandColors.GREEN, alpha)
        rc.text(canvas, status, textX + if (connected) dp(10f) else 0f, cy + dp(15f), rc.captionPaint, IslandColors.TEXT_SECONDARY, alpha)

        val level = payload?.batteryLevel ?: return
        val ringR = dp(13f)
        val ringX = layoutW - pad - ringR
        rc.glyphs.drawRing(canvas, ringX, cy, ringR, dp(3f), level / 100f * p, batteryColor(level), IslandColors.TRACK, alpha)
        rc.numberPaint.textSize = rc.sp(14f)
        rc.text(canvas, "$level%", ringX - ringR - dp(8f), rc.baseline(rc.numberPaint, cy), rc.numberPaint, IslandColors.TEXT, alpha, Paint.Align.RIGHT)
    }

    override val contentDescription: String
        get() {
            val p = payload ?: return event.title
            val battery = p.batteryLevel?.let { ", battery $it percent" }.orEmpty()
            return "${p.deviceName} ${if (p.connected) "connected" else "disconnected"}$battery"
        }
}
