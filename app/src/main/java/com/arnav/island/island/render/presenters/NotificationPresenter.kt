package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import android.text.StaticLayout
import android.text.TextPaint
import com.arnav.island.events.ActionStyle
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.NotificationPayload
import com.arnav.island.events.NotificationPrivacy
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RoundedImage
import com.arnav.island.storage.NotificationStyle

class NotificationPresenter(rc: RenderContext) : Presenter(rc) {

    private val avatar = RoundedImage()
    private val appIcon = RoundedImage()
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
        avatar.set(rc.images.get(event.artwork))
        appIcon.set(rc.images.get(event.iconImage))

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
            if (event.mergeCount > 1 && p?.privacy == NotificationPrivacy.FULL) {
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
                drawPillButton(canvas, t.id, action.label, t.rect.left, t.rect.top, t.rect.right, t.rect.bottom, ActionStyle.DEFAULT, alpha)
            }
        }
    }

    private fun drawAvatar(canvas: Canvas, l: Float, t: Float, size: Float, alpha: Float) {
        val showLarge = avatar.hasImage && payload?.privacy == NotificationPrivacy.FULL
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
    }
}
