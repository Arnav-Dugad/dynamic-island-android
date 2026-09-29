package com.arnav.island.island.render.presenters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import com.arnav.island.events.ActionStyle
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.NotificationPayload
import com.arnav.island.events.NotificationPrivacy
import com.arnav.island.island.render.HitTarget
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RoundedImage
import com.arnav.island.storage.NotificationStyle
import com.arnav.island.util.ColorExtractor

class NotificationPresenter(rc: RenderContext) : Presenter(rc) {

    private val avatar = RoundedImage()
    private val appIcon = RoundedImage()
    private var avatarBitmap: Bitmap? = null
    private var iconBitmap: Bitmap? = null
    private val group = List(MAX_GROUP_AVATARS) { RoundedImage() }
    private var groupSize = 0
    private var accent = 0
    private var payload: NotificationPayload? = null
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var body: StaticLayout? = null
    private var titleLine = ""
    private var caption = ""
    private var compactLabel = ""

    private var pad = 0f
    private var top = 0f
    private var avatarSize = 0f
    private var textX = 0f
    private var actionsY = 0f

    private val usesPill: Boolean
        get() = payload?.privacy == NotificationPrivacy.ICON_ONLY || rc.settings.notificationStyle == NotificationStyle.COMPACT

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> {
        val p = event.payload as? NotificationPayload
        val pill = p?.privacy == NotificationPrivacy.ICON_ONLY || rc.settings.notificationStyle == NotificationStyle.COMPACT
        if (mode == PresentMode.TOAST && pill) return (rc.geometry.compactWidth + dp(24f)) to rc.geometry.compactHeight
        if (mode != PresentMode.TOAST && mode != PresentMode.EXPANDED) return super.measure(event, mode)
        val w = expandedWidth
        val expanded = mode == PresentMode.EXPANDED
        val layout = buildBody(event, p, w, if (expanded) 6 else 2)
        val bodyH = layout?.height?.toFloat() ?: 0f
        var h = bandInset + maxOf(dp(46f), dp(24f) + bodyH + dp(4f)) + dp(12f)
        if (expanded && visibleActions(event).isNotEmpty()) h += dp(48f)
        return w to h
    }

    private fun visibleActions(e: IslandEvent) = e.actions.filter { it.id.startsWith(ACTION_PREFIX) }.take(3)

    private fun buildBody(e: IslandEvent, p: NotificationPayload?, w: Float, maxLines: Int): StaticLayout? {
        val text = bodyText(e, p, includeExtraLines = maxLines > 2)
        if (text.isBlank()) return null
        bodyPaint.set(rc.bodyPaint)
        bodyPaint.textSize = rc.sp(14f)
        val textWidth = w - dp(16f) * 2 - dp(42f) - dp(12f)
        return staticLayout(text, bodyPaint, textWidth, maxLines)
    }

    private fun bodyText(e: IslandEvent, p: NotificationPayload?, includeExtraLines: Boolean): String {
        if (p == null) return e.subtitle
        return when (p.privacy) {
            NotificationPrivacy.FULL -> if (!p.showText) "" else buildString {
                append(p.text)
                if (includeExtraLines && p.extraLines.isNotEmpty()) {
                    p.extraLines.forEach { append('\n').append(it) }
                }
            }
            NotificationPrivacy.APP_NAME -> if (e.mergeCount > 1) "${e.mergeCount} new notifications" else "New notification"
            NotificationPrivacy.ICON_ONLY -> ""
        }
    }

    override fun onBind(isUpdate: Boolean) {
        val p = event.payload as? NotificationPayload
        payload = p
        avatarBitmap = rc.images.get(event.artwork)
        iconBitmap = rc.images.get(event.iconImage)
        avatar.set(avatarBitmap)
        appIcon.set(iconBitmap)
        accent = event.colors.accent.takeIf { it != 0 }?.let(ColorExtractor::legibleOnBlack) ?: 0

        // Group conversations: the most recent distinct senders, stacked.
        groupSize = 0
        if (p != null && p.privacy == NotificationPrivacy.FULL && p.senderAvatars.size >= 2) {
            p.senderAvatars.take(MAX_GROUP_AVATARS).forEach { ref ->
                val bmp = rc.images.get(ref) ?: return@forEach
                group[groupSize++].set(bmp)
            }
            if (groupSize < 2) groupSize = 0
        }

        pad = dp(16f)
        top = bandInset
        avatarSize = dp(42f)
        textX = pad + avatarSize + dp(12f)
        val textRight = layoutW - pad

        val appLabel = p?.appLabel.orEmpty()
        val displayTitle = when (p?.privacy) {
            NotificationPrivacy.APP_NAME, NotificationPrivacy.ICON_ONLY -> appLabel
            else -> event.title.ifBlank { appLabel }
        }
        caption = buildString {
            if (p?.privacy == NotificationPrivacy.FULL && displayTitle != appLabel) append(appLabel)
            if (p?.privacy == NotificationPrivacy.FULL && p.senderCount > 2) {
                if (isNotEmpty()) append(" · ")
                append("${p.senderCount} people")
            } else if (event.mergeCount > 1 && p?.privacy == NotificationPrivacy.FULL) {
                if (isNotEmpty()) append(" · ")
                append("${event.mergeCount} new")
            }
        }
        rc.captionPaint.textSize = rc.sp(12f)
        rc.titlePaint.textSize = rc.sp(15f)
        val captionW = if (caption.isEmpty()) 0f else rc.captionPaint.measureText(caption) + dp(10f)
        titleLine = rc.ellipsize(displayTitle, rc.titlePaint, textRight - textX - captionW)

        compactLabel = when {
            p?.privacy == NotificationPrivacy.ICON_ONLY -> if (event.mergeCount > 1) "${event.mergeCount}" else ""
            else -> displayTitle
        }

        if (mode == PresentMode.TOAST || mode == PresentMode.EXPANDED) {
            body = if (mode == PresentMode.TOAST && usesPill) null else buildBody(event, p, layoutW, if (mode == PresentMode.EXPANDED) 6 else 2)
            if (mode == PresentMode.EXPANDED) {
                val actions = visibleActions(event)
                actionsY = layoutH - dp(12f) - dp(18f)
                if (actions.isNotEmpty()) {
                    val gap = dp(8f)
                    val bw = (layoutW - 2 * pad - gap * (actions.size - 1)) / actions.size
                    actions.forEachIndexed { i, a ->
                        val l = pad + i * (bw + gap)
                        addTarget(a.id, a, l, actionsY - dp(18f), l + bw, actionsY + dp(18f))
                    }
                }
            }
        }
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when {
            mode == PresentMode.BUBBLE -> drawAvatar(canvas, dp(5f), dp(5f), w - dp(10f), alpha)
            mode == PresentMode.TOAST && usesPill -> drawPill(canvas, w, h, alpha)
            mode == PresentMode.TOAST || mode == PresentMode.EXPANDED -> drawBanner(canvas, alpha)
            else -> drawPill(canvas, w, h, alpha)
        }
    }

    private fun drawPill(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val s = slot(h)
        val inset = (h - s) / 2f
        drawAvatar(canvas, inset + rc.burnInX, inset + rc.burnInY, s, a)
        if (compactLabel.isEmpty()) return
        rc.labelPaint.textSize = rc.sp(14f)
        val right = w - inset - dp(2f) + rc.burnInX
        val label = rc.ellipsize(compactLabel, rc.labelPaint, sideSpace(w) - inset - dp(4f))
        rc.text(canvas, label, right, rc.baseline(rc.labelPaint, h / 2f + rc.burnInY), rc.labelPaint, IslandColors.TEXT, a, Paint.Align.RIGHT)
    }

    private fun drawBanner(canvas: Canvas, alpha: Float) {
        drawAvatar(canvas, pad, top + dp(2f), avatarSize, alpha)
        rc.titlePaint.textSize = rc.sp(15f)
        rc.captionPaint.textSize = rc.sp(12f)
        val titleY = top + dp(17f)
        rc.text(canvas, titleLine, textX, titleY, rc.titlePaint, IslandColors.TEXT, alpha)
        if (caption.isNotEmpty()) {
            rc.text(canvas, caption, layoutW - pad, titleY, rc.captionPaint, IslandColors.TEXT_TERTIARY, alpha, Paint.Align.RIGHT)
        }
        body?.let { drawLayout(canvas, it, textX, top + dp(24f), IslandColors.TEXT_SECONDARY, alpha) }

        if (mode == PresentMode.EXPANDED) {
            hitTargets.forEach { t ->
                val action = t.action ?: return@forEach
                if (t.id == ACTION_REPLY) drawReplyPill(canvas, t, action.label, alpha)
                else drawPillButton(canvas, t.id, action.label, t.rect.left, t.rect.top, t.rect.right, t.rect.bottom, ActionStyle.DEFAULT, alpha)
            }
        }
    }

    /** The app's own reply action, tinted with its accent and marked with a reply glyph. */
    private fun drawReplyPill(canvas: Canvas, t: HitTarget, label: String, alpha: Float) {
        val s = if (pressedId == t.id) 0.94f else 1f
        val r = t.rect
        val cx = r.centerX()
        val cy = r.centerY()
        val hw = r.width() / 2f * s
        val hh = r.height() / 2f * s
        val tint = if (accent != 0) accent else IslandColors.BLUE
        rc.roundRect(canvas, cx - hw, cy - hh, cx + hw, cy + hh, hh, ColorExtractor.withAlpha(tint, 0.28f), alpha)
        rc.labelPaint.textSize = rc.sp(14f) * s
        val text = rc.ellipsize(label, rc.labelPaint, r.width() - dp(40f))
        val textW = rc.labelPaint.measureText(text)
        val glyph = dp(15f) * s
        val start = cx - (glyph + dp(6f) + textW) / 2f
        rc.glyphs.draw(canvas, Glyph.REPLY, start + glyph / 2f, cy, glyph, IslandColors.TEXT, alpha)
        rc.text(canvas, text, start + glyph + dp(6f), rc.baseline(rc.labelPaint, cy), rc.labelPaint, IslandColors.TEXT, alpha)
    }

    private val showsLargeAvatar: Boolean
        get() = avatar.hasImage && payload?.privacy == NotificationPrivacy.FULL

    /** Two or three overlapping sender avatars, newest in front. */
    private fun drawGroup(canvas: Canvas, l: Float, t: Float, size: Float, alpha: Float) {
        val n = groupSize
        val d = if (n == 2) size * 0.68f else size * 0.58f
        val ring = maxOf(dp(1.5f), size * 0.045f)
        for (i in n - 1 downTo 0) {
            // Oldest at the top-right, newest at the bottom-left.
            val f = i / (n - 1).toFloat()
            val x = l + (size - d) * f
            val y = t + (size - d) * (1f - f)
            rc.circle(canvas, x + d / 2f, y + d / 2f, d / 2f + ring, 0xFF000000.toInt(), alpha)
            group[i].draw(canvas, x, y, d, d / 2f, alpha)
        }
    }

    private fun drawAvatar(canvas: Canvas, l: Float, t: Float, size: Float, alpha: Float) {
        if (heroHidden) return
        if (groupSize >= 2 && size >= dp(30f)) {
            drawGroup(canvas, l, t, size, alpha)
            return
        }
        val showLarge = showsLargeAvatar
        if (accent != 0 && size >= dp(30f)) {
            // Per-app accent: a hairline ring in the app's colour.
            rc.stroke.shader = null
            rc.stroke.strokeWidth = dp(1.5f)
            rc.stroke.color = accent
            rc.stroke.alpha = (170 * alpha).toInt().coerceIn(0, 255)
            val gap = dp(2.5f)
            if (showLarge) {
                canvas.drawCircle(l + size / 2f, t + size / 2f, size / 2f + gap, rc.stroke)
            } else {
                val rr = size * 0.26f + gap
                canvas.drawRoundRect(l - gap, t - gap, l + size + gap, t + size + gap, rr, rr, rc.stroke)
            }
        }
        when {
            showLarge -> {
                avatar.draw(canvas, l, t, size, size / 2f, alpha)
                if (appIcon.hasImage && size >= dp(30f)) {
                    val b = size * 0.42f
                    val bl = l + size - b + dp(2f)
                    val bt = t + size - b + dp(2f)
                    rc.circle(canvas, bl + b / 2f, bt + b / 2f, b / 2f + dp(2f), 0xFF000000.toInt(), alpha)
                    appIcon.draw(canvas, bl, bt, b, b / 2f, alpha)
                }
            }
            appIcon.hasImage -> appIcon.draw(canvas, l, t, size, size * 0.26f, alpha)
            else -> {
                rc.roundRect(canvas, l, t, l + size, t + size, size * 0.26f, IslandColors.CONTROL, alpha)
                rc.glyphs.draw(canvas, event.icon ?: Glyph.BELL, l + size / 2f, t + size / 2f, size * 0.55f, IslandColors.TEXT, alpha)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shared element: the icon flies in from the status bar, then between banner and card.
    // ---------------------------------------------------------------------------------------------

    override fun heroSlot(w: Float, h: Float, out: RectF): Boolean {
        if (heroBitmap == null) return false
        when {
            mode == PresentMode.BUBBLE -> out.set(dp(5f), dp(5f), w - dp(5f), w - dp(5f))
            (mode == PresentMode.TOAST && !usesPill) || mode == PresentMode.EXPANDED ->
                out.set(pad, top + dp(2f), pad + avatarSize, top + dp(2f) + avatarSize)
            else -> {
                if (compactVisibility(w, h) < 0.5f) return false
                val s = slot(h)
                val inset = (h - s) / 2f
                out.set(inset + rc.burnInX, inset + rc.burnInY, inset + rc.burnInX + s, inset + rc.burnInY + s)
            }
        }
        return true
    }

    override val heroBitmap: Bitmap?
        get() = when {
            groupSize >= 2 -> null
            showsLargeAvatar -> avatarBitmap
            else -> iconBitmap
        }

    override fun heroRadius(size: Float): Float = if (showsLargeAvatar) size / 2f else size * 0.26f

    override val contentDescription: String
        get() {
            val p = payload ?: return event.title
            return when (p.privacy) {
                NotificationPrivacy.FULL -> "Notification from ${p.appLabel}: ${event.title}. ${if (p.showText) p.text else ""}"
                else -> "Notification from ${p.appLabel}"
            }
        }

    companion object {
        const val ACTION_PREFIX = "notif.action."
        const val ACTION_REPLY = "notif.action.reply"
        private const val MAX_GROUP_AVATARS = 3
    }
}
