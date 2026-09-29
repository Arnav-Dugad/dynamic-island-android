package com.arnav.island.island.render

import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.events.ActionStyle
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandAction
import com.arnav.island.events.IslandEvent

/** How an event is being presented. */
enum class PresentMode {
    /** Live activity around the camera. */
    COMPACT,

    /** Main pill of the split island (narrower compact). */
    SPLIT_MAIN,

    /** The detached circle of the split island. */
    BUBBLE,

    /** Temporary event. */
    TOAST,

    /** Full interaction card. */
    EXPANDED,
}

enum class HitKind { BUTTON, SEEK }

/** Interactive region in content-local layout coordinates. */
class HitTarget(val id: String, val kind: HitKind, val action: IslandAction?) {
    val rect = RectF()
}

/**
 * Base class for everything drawn inside the island.
 *
 * Coordinate contract:
 *  - COMPACT / SPLIT_MAIN / BUBBLE: (0,0) is the top-left of the *current* shape and [draw]
 *    receives its live size, so leading/trailing content rides the edges while the pill morphs.
 *  - TOAST / EXPANDED: content is laid out once for the *target* size ([layoutW] x [layoutH]).
 *    The scene centres that layout in the live shape, scales it during the reveal and clips it.
 */
abstract class Presenter(protected val rc: RenderContext) {

    lateinit var event: IslandEvent
        private set
    var mode: PresentMode = PresentMode.COMPACT
        private set
    var layoutW = 0f
        private set
    var layoutH = 0f
        private set

    val hitTargets = ArrayList<HitTarget>(4)

    /** Button currently under the finger, animated by [pressSpring]. */
    var pressedId: String? = null
        private set
    private val pressSpring = Spring(0f, SpringSpec(0.2f, 0.7f), restThreshold = 0.002f)

    /** Non-null while the user scrubs a seek bar (0..1). */
    var scrubFraction: Float? = null

    val isBound: Boolean get() = ::event.isInitialized

    /** Target size (px) for TOAST / EXPANDED. Called before [bind]. */
    open fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> =
        rc.geometry.compactWidth to rc.geometry.compactHeight

    fun bind(event: IslandEvent, mode: PresentMode, width: Float, height: Float) {
        this.event = event
        this.mode = mode
        layoutW = width
        layoutH = height
        hitTargets.clear()
        onBind(isUpdate = false)
    }

    /** Same event id, same mode: refresh content in place (no entrance, no relayout churn). */
    fun update(event: IslandEvent) {
        this.event = event
        hitTargets.clear()
        onBind(isUpdate = true)
    }

    protected abstract fun onBind(isUpdate: Boolean)

    /** Draw content. See class docs for the coordinate contract. */
    abstract fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long)

    /** Advance internal springs. Returns true while something is still moving. */
    open fun step(dt: Float): Boolean = pressSpring.step(dt)

    /** -1 = static, 0 = redraw every frame, n = redraw in n ms. */
    open fun nextFrameDelay(now: Long): Long = -1

    open fun onScrub(fraction: Float) {}
    open fun onScrubEnd(fraction: Float) {}

    /** Presses [id], or releases the current button (keeping its id so it can spring back). */
    fun setPressed(id: String?) {
        if (id != null) pressedId = id
        pressSpring.animateTo(if (id != null) 1f else 0f)
    }

    fun hitTest(x: Float, y: Float): HitTarget? = hitTargets.lastOrNull { it.rect.contains(x, y) }

    open val contentDescription: String
        get() = listOf(event.title, event.subtitle).filter { it.isNotBlank() }.joinToString(", ")

    // ---------------------------------------------------------------------------------------------
    // Layout helpers
    // ---------------------------------------------------------------------------------------------

    protected fun dp(v: Float) = rc.dp(v)
    protected val nowElapsed: Long get() = SystemClock.elapsedRealtime()

    /** Top inset below the status-bar band for TOAST/EXPANDED layouts. */
    protected val bandInset: Float
        get() = (rc.geometry.statusBandBottom - rc.geometry.baseTop).coerceAtLeast(rc.geometry.compactHeight) + dp(6f)

    protected val expandedWidth: Float get() = rc.geometry.expandedWidth
    protected val mediumWidth: Float get() = minOf(rc.geometry.expandedWidth, maxOf(rc.geometry.compactWidth + dp(88f), dp(284f)))

    /** Square slot size for leading/trailing compact content. */
    protected fun slot(h: Float): Float = (h - dp(14f)).coerceIn(dp(12f), dp(24f))

    /** Space available on each side of the camera for compact content. */
    protected fun sideSpace(w: Float): Float = (w / 2f - rc.geometry.cameraExclusionHalfWidth).coerceAtLeast(0f)

    /** Content fades as the pill gets too narrow to hold it (e.g. mid-morph from idle). */
    protected fun compactVisibility(w: Float, h: Float): Float {
        val needed = rc.geometry.cameraExclusionHalfWidth * 2f + slot(h) * 2f + dp(12f)
        return ((w - needed) / dp(28f)).coerceIn(0f, 1f)
    }

    protected fun addTarget(id: String, action: IslandAction?, l: Float, t: Float, r: Float, b: Float, kind: HitKind = HitKind.BUTTON): HitTarget {
        val target = HitTarget(id, kind, action)
        target.rect.set(l, t, r, b)
        hitTargets.add(target)
        return target
    }

    protected fun addCircleTarget(id: String, action: IslandAction?, cx: Float, cy: Float, radius: Float) =
        addTarget(id, action, cx - radius, cy - radius, cx + radius, cy + radius)

    protected fun pressScale(id: String): Float =
        if (pressedId == id) 1f - 0.12f * pressSpring.value else 1f

    /** Circular button with a glyph; background tinted by style. */
    protected fun drawCircleButton(
        canvas: Canvas, id: String, glyph: Glyph, cx: Float, cy: Float, radius: Float,
        style: ActionStyle, alpha: Float, glyphScale: Float = 0.5f, playPauseT: Float? = null,
    ) {
        val s = pressScale(id)
        val r = radius * s
        val (bg, fg) = when (style) {
            ActionStyle.POSITIVE -> IslandColors.GREEN to IslandColors.TEXT
            ActionStyle.DESTRUCTIVE -> IslandColors.RED to IslandColors.TEXT
            ActionStyle.PRIMARY -> IslandColors.TEXT to 0xFF000000.toInt()
            ActionStyle.DEFAULT -> (if (pressedId == id) IslandColors.CONTROL_PRESSED else IslandColors.CONTROL) to IslandColors.TEXT
        }
        rc.circle(canvas, cx, cy, r, bg, alpha)
        if (playPauseT != null) {
            rc.glyphs.drawPlayPause(canvas, cx, cy, r * 2f * glyphScale, playPauseT, fg, alpha)
        } else {
            rc.glyphs.draw(canvas, glyph, cx, cy, r * 2f * glyphScale, fg, alpha)
        }
    }

    /** Plain glyph button (no background), e.g. media transport controls. */
    protected fun drawGlyphButton(canvas: Canvas, id: String, glyph: Glyph, cx: Float, cy: Float, size: Float, color: Int, alpha: Float, playPauseT: Float? = null) {
        val s = pressScale(id)
        if (pressedId == id && s < 0.999f) rc.circle(canvas, cx, cy, size * 0.95f, IslandColors.CONTROL, alpha * (1f - s) / 0.12f)
        if (playPauseT != null) rc.glyphs.drawPlayPause(canvas, cx, cy, size * s, playPauseT, color, alpha)
        else rc.glyphs.draw(canvas, glyph, cx, cy, size * s, color, alpha)
    }

    /** Pill-shaped text button. */
    protected fun drawPillButton(canvas: Canvas, id: String, label: String, l: Float, t: Float, r: Float, b: Float, style: ActionStyle, alpha: Float) {
        val s = pressScale(id)
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val hw = (r - l) / 2f * s
        val hh = (b - t) / 2f * s
        val (bg, fg) = when (style) {
            ActionStyle.POSITIVE -> IslandColors.GREEN to 0xFF000000.toInt()
            ActionStyle.DESTRUCTIVE -> IslandColors.RED to IslandColors.TEXT
            ActionStyle.PRIMARY -> IslandColors.TEXT to 0xFF000000.toInt()
            ActionStyle.DEFAULT -> IslandColors.CONTROL to IslandColors.TEXT
        }
        rc.roundRect(canvas, cx - hw, cy - hh, cx + hw, cy + hh, hh, bg, alpha)
        rc.labelPaint.textSize = rc.sp(14f) * s
        rc.text(canvas, rc.ellipsize(label, rc.labelPaint, (r - l) - dp(16f)), cx, rc.baseline(rc.labelPaint, cy), rc.labelPaint, fg, alpha, android.graphics.Paint.Align.CENTER)
    }

    protected fun staticLayout(text: CharSequence, paint: TextPaint, width: Float, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.05f)
            .build()

    /** Draws a StaticLayout at (x, y) with [alpha] folded into its paint colour. */
    protected fun drawLayout(canvas: Canvas, layout: StaticLayout, x: Float, y: Float, color: Int, alpha: Float) {
        if (alpha <= 0.004f) return
        val paint = layout.paint
        paint.color = color
        paint.alpha = (((color ushr 24) * alpha)).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Draws the default bubble content: the event's glyph or image. */
    protected fun drawBubbleGlyph(canvas: Canvas, d: Float, glyph: Glyph, color: Int, alpha: Float) {
        rc.glyphs.draw(canvas, glyph, d / 2f + rc.burnInX, d / 2f + rc.burnInY, d * 0.46f, color, alpha)
    }
}
