package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.animation.lerp
import com.arnav.island.animation.organic
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.MediaPayload
import com.arnav.island.island.render.HitKind
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RoundedImage
import com.arnav.island.util.ColorExtractor
import com.arnav.island.util.Formatters

class MediaPresenter(rc: RenderContext) : Presenter(rc) {

    private val art = RoundedImage()
    private val appIcon = RoundedImage()
    private var payload: MediaPayload? = null
    private var accent = IslandColors.TEXT
    private var seed = 0f

    /** 1 = playing (pause glyph shown), 0 = paused (play glyph). Morphs continuously. */
    private val playT = Spring(1f, SpringSpec(0.32f, 0.82f), restThreshold = 0.002f)

    /** Equalizer amplitude: bars melt into dots when playback pauses. */
    private val amp = Spring(1f, SpringSpec(0.55f, 0.92f), restThreshold = 0.002f)
    private val scrubGrow = Spring(0f, SpringSpec(0.25f, 0.8f), restThreshold = 0.002f)

    // Expanded layout (px), computed in onBind.
    private var pad = 0f
    private var top = 0f
    private var artSize = 0f
    private var textX = 0f
    private var textRight = 0f
    private var waveCx = 0f
    private var barY = 0f
    private var controlsY = 0f
    private var titleLine = ""
    private var artistLine = ""

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> = when (mode) {
        PresentMode.EXPANDED -> expandedWidth to (bandInset + dp(164f))
        else -> super.measure(event, mode)
    }

    override fun onBind(isUpdate: Boolean) {
        val p = event.payload as? MediaPayload
        payload = p
        art.set(rc.images.get(event.artwork))
        appIcon.set(rc.images.get(event.iconImage))
        accent = if (rc.settings.tintFromArtwork && p != null && p.accent != 0) ColorExtractor.legibleOnBlack(p.accent) else IslandColors.TEXT
        seed = ((p?.title?.hashCode() ?: 0) and 0xFF) / 37f

        val playing = p?.isPlaying == true
        if (isUpdate) {
            playT.animateTo(if (playing) 1f else 0f)
            amp.animateTo(if (playing) 1f else 0f)
        } else {
            playT.snapTo(if (playing) 1f else 0f)
            amp.snapTo(if (playing) 1f else 0f)
        }
        if (mode == PresentMode.EXPANDED) layoutExpanded()
    }

    private fun layoutExpanded() {
        val w = layoutW
        pad = dp(20f)
        top = bandInset
        artSize = dp(58f)
        textX = pad + artSize + dp(14f)
        waveCx = w - pad - dp(13f)
        textRight = waveCx - dp(13f) - dp(12f)
        barY = top + artSize + dp(22f)
        controlsY = barY + dp(50f)

        rc.titlePaint.textSize = rc.sp(16f)
        rc.bodyPaint.textSize = rc.sp(14f)
        titleLine = rc.ellipsize(event.title.ifBlank { payload?.appLabel.orEmpty() }, rc.titlePaint, textRight - textX)
        artistLine = rc.ellipsize(event.subtitle, rc.bodyPaint, textRight - textX)

        val prev = event.actions.firstOrNull { it.id == ACTION_PREV }
        val toggle = event.actions.firstOrNull { it.id == ACTION_TOGGLE }
        val next = event.actions.firstOrNull { it.id == ACTION_NEXT }
        addCircleTarget(ACTION_PREV, prev, w / 2f - dp(74f), controlsY, dp(26f))
        addCircleTarget(ACTION_TOGGLE, toggle, w / 2f, controlsY, dp(30f))
        addCircleTarget(ACTION_NEXT, next, w / 2f + dp(74f), controlsY, dp(26f))
        if (payload?.canSeek == true && (payload?.durationMs ?: 0) > 0) {
            addTarget(TARGET_SEEK, null, pad - dp(6f), barY - dp(16f), w - pad + dp(6f), barY + dp(16f), HitKind.SEEK)
        }
    }

    override fun step(dt: Float): Boolean {
        var moving = super.step(dt)
        if (playT.step(dt)) moving = true
        if (amp.step(dt)) moving = true
        if (scrubGrow.step(dt)) moving = true
        return moving
    }

    override fun nextFrameDelay(now: Long): Long {
        val p = payload ?: return -1
        if (!p.isPlaying) return -1
        val waveVisible = rc.settings.waveform && !rc.settings.minimalContent
        return when (mode) {
            PresentMode.EXPANDED -> rc.settings.decorativeFrameMs
            else -> if (waveVisible) rc.settings.decorativeFrameMs else -1
        }
    }

    override fun onScrub(fraction: Float) {
        scrubFraction = fraction
        scrubGrow.animateTo(1f)
    }

    override fun onScrubEnd(fraction: Float) {
        payload?.seek?.invoke?.invoke(fraction.coerceIn(0f, 1f))
        scrubFraction = null
        scrubGrow.animateTo(0f)
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.EXPANDED -> drawExpanded(canvas, alpha)
            PresentMode.BUBBLE -> drawBubble(canvas, w, alpha)
            else -> drawCompact(canvas, w, h, alpha)
        }
    }

    private fun drawCompact(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val s = slot(h)
        val inset = (h - s) / 2f
        val bx = rc.burnInX
        val by = rc.burnInY
        drawArtwork(canvas, inset + bx, inset + by, s, s * 0.26f, a)

        val cx = w - h / 2f + bx
        val cy = h / 2f + by
        if (rc.settings.compactMediaProgress) {
            val p = payload
            if (p != null && p.durationMs > 0) {
                val frac = p.positionAt(nowElapsed).toFloat() / p.durationMs
                rc.glyphs.drawRing(canvas, cx, cy, s / 2f, dp(2.2f), frac, accent, IslandColors.TRACK, a)
                drawWave(canvas, cx, cy, s * 0.52f, s * 0.42f, 3, a)
                return
            }
        }
        drawWave(canvas, cx, cy, s * 0.92f, s * 0.7f, 4, a)
    }

    private fun drawBubble(canvas: Canvas, d: Float, alpha: Float) {
        if (art.hasImage) {
            val s = d - dp(10f)
            art.draw(canvas, (d - s) / 2f + rc.burnInX, (d - s) / 2f + rc.burnInY, s, s / 2f, alpha)
        } else {
            drawWave(canvas, d / 2f + rc.burnInX, d / 2f + rc.burnInY, d * 0.5f, d * 0.4f, 4, alpha)
        }
    }

    private fun drawExpanded(canvas: Canvas, alpha: Float) {
        val w = layoutW
        drawArtwork(canvas, pad, top, artSize, dp(13f), alpha)

        rc.titlePaint.textSize = rc.sp(16f)
        rc.bodyPaint.textSize = rc.sp(14f)
        rc.text(canvas, titleLine, textX, top + dp(25f), rc.titlePaint, IslandColors.TEXT, alpha)
        rc.text(canvas, artistLine, textX, top + dp(46f), rc.bodyPaint, IslandColors.TEXT_SECONDARY, alpha)
        drawWave(canvas, waveCx, top + artSize / 2f, dp(26f), dp(22f), 5, alpha)

        // Progress.
        val p = payload
        if (p != null && p.durationMs > 0) {
            val pos = scrubFraction?.let { (it * p.durationMs).toLong() } ?: p.positionAt(nowElapsed)
            val frac = (pos.toFloat() / p.durationMs).coerceIn(0f, 1f)
            val barH = lerp(dp(5f), dp(8f), scrubGrow.value)
            val l = pad
            val r = w - pad
            rc.roundRect(canvas, l, barY - barH / 2f, r, barY + barH / 2f, barH / 2f, IslandColors.TRACK, alpha)
            rc.roundRect(canvas, l, barY - barH / 2f, l + (r - l) * frac, barY + barH / 2f, barH / 2f, accent, alpha)
            rc.numberPaint.textSize = rc.sp(12f)
            val timesY = barY + dp(20f)
            rc.text(canvas, Formatters.elapsed(pos), l, timesY, rc.numberPaint, IslandColors.TEXT_TERTIARY, alpha)
            rc.text(canvas, "-" + Formatters.elapsed(p.durationMs - pos), r, timesY, rc.numberPaint, IslandColors.TEXT_TERTIARY, alpha, Paint.Align.RIGHT)
        }

        // Transport.
        val prevEnabled = p?.canSkipPrevious != false
        val nextEnabled = p?.canSkipNext != false
        drawGlyphButton(canvas, ACTION_PREV, Glyph.PREVIOUS, w / 2f - dp(74f), controlsY, dp(28f), IslandColors.TEXT, alpha * if (prevEnabled) 1f else 0.35f)
        drawGlyphButton(canvas, ACTION_TOGGLE, Glyph.PAUSE, w / 2f, controlsY, dp(36f), IslandColors.TEXT, alpha, playPauseT = playT.value)
        drawGlyphButton(canvas, ACTION_NEXT, Glyph.NEXT, w / 2f + dp(74f), controlsY, dp(28f), IslandColors.TEXT, alpha * if (nextEnabled) 1f else 0.35f)
    }

    private fun drawArtwork(canvas: Canvas, l: Float, t: Float, size: Float, radius: Float, alpha: Float) {
        when {
            art.hasImage -> {
                // Artwork dims very slightly while paused, like a record that stopped spinning.
                art.draw(canvas, l, t, size, radius, alpha * lerp(0.72f, 1f, amp.value))
            }
            appIcon.hasImage -> appIcon.draw(canvas, l, t, size, radius, alpha)
            else -> {
                rc.roundRect(canvas, l, t, l + size, t + size, radius, IslandColors.CONTROL, alpha)
                rc.glyphs.draw(canvas, Glyph.MUSIC, l + size / 2f, t + size / 2f, size * 0.6f, accent, alpha)
            }
        }
    }

    private fun drawWave(canvas: Canvas, cx: Float, cy: Float, width: Float, maxH: Float, bars: Int, alpha: Float) {
        if (!rc.settings.waveform || alpha <= 0.004f) return
        val animated = !rc.settings.minimalContent
        val unit = width / (bars * 2 - 1)
        val barW = unit
        val t = rc.animTime
        val level = if (animated) amp.value else amp.value * 0.35f
        for (i in 0 until bars) {
            val v = if (animated) organic(t, seed + i * 1.37f) else 0.6f
            val hh = lerp(barW, maxH, (0.16f + 0.84f * v) * level).coerceAtLeast(barW)
            val x = cx - width / 2f + i * unit * 2f
            rc.roundRect(canvas, x, cy - hh / 2f, x + barW, cy + hh / 2f, barW / 2f, accent, alpha)
        }
    }

    override val contentDescription: String
        get() {
            val p = payload ?: return event.title
            val state = if (p.isPlaying) "Playing" else "Paused"
            return "$state: ${p.title}${if (p.artist.isNotBlank()) " by ${p.artist}" else ""}"
        }

    companion object {
        const val ACTION_PREV = "media.prev"
        const val ACTION_TOGGLE = "media.toggle"
        const val ACTION_NEXT = "media.next"
        const val TARGET_SEEK = "media.seek"
    }
}
