package com.arnav.island.island.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.animation.easeInOutSine
import com.arnav.island.animation.easeOutCubic
import com.arnav.island.animation.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** Linear 0→1 timeline for one-shot decorative effects. */
class Tween(private val durationS: Float) {
    var t = 1f
        private set
    val active: Boolean get() = t < 1f

    fun start() {
        t = 0f
    }

    fun stop() {
        t = 1f
    }

    fun step(dt: Float): Boolean {
        if (t >= 1f) return false
        t = (t + dt / durationS).coerceAtMost(1f)
        return true
    }
}

/** A single soft ring that spreads out from the camera just before a banner drops. */
class ArrivalPulse(private val rc: RenderContext) {
    private val tween = Tween(0.8f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var cx = 0f
    private var cy = 0f
    private var r0 = 0f
    private var color = IslandColors.TEXT

    val active: Boolean get() = tween.active
    private val maxRadius: Float get() = r0 + rc.dp(40f)

    fun start(cx: Float, cy: Float, startRadius: Float, color: Int) {
        this.cx = cx
        this.cy = cy
        r0 = startRadius
        this.color = color
        tween.start()
    }

    fun step(dt: Float) = tween.step(dt)

    fun draw(canvas: Canvas) {
        if (!active) return
        val p = easeOutCubic(tween.t)
        paint.strokeWidth = rc.dp(lerp(2.6f, 0.6f, p))
        paint.color = color
        paint.alpha = (170 * (1f - p) * (1f - p)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, lerp(r0, maxRadius, p), paint)
    }

    fun union(out: RectF) {
        if (active) out.union(cx - maxRadius, cy - maxRadius, cx + maxRadius, cy + maxRadius)
    }
}

/** A faint highlight that travels around the camera ring as the island opens. */
class LensGlint(private val rc: RenderContext) {
    private val tween = Tween(0.95f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    val active: Boolean get() = tween.active

    fun start() = tween.start()
    fun step(dt: Float) = tween.step(dt)

    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float, visibility: Float) {
        if (!active) return
        val t = tween.t
        val intensity = sin(PI.toFloat() * t) * visibility
        if (intensity <= 0.01f) return
        val start = -130f + 320f * easeInOutSine(t)
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.strokeWidth = rc.dp(1.3f)
        paint.color = 0xFFFFFFFF.toInt()
        paint.alpha = (120 * intensity).toInt().coerceIn(0, 255)
        canvas.drawArc(oval, start, 64f, false, paint)
        paint.alpha = (60 * intensity).toInt().coerceIn(0, 255)
        canvas.drawArc(oval, start - 26f, 26f, false, paint)
    }
}

/** A quick highlight that sweeps along the Glass rim. */
class Shimmer(private val rc: RenderContext) {
    private val tween = Tween(0.75f)
    private val gradient = LinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(0x00FFFFFF, 0xB3FFFFFF.toInt(), 0x00FFFFFF),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        shader = gradient
    }

    val active: Boolean get() = tween.active

    fun start() = tween.start()
    fun step(dt: Float) = tween.step(dt)

    fun draw(canvas: Canvas, path: Path, rect: RectF, visibility: Float) {
        if (!active) return
        val band = max(rc.dp(40f), rect.width() * 0.22f)
        val x = lerp(rect.left - band, rect.right + band, easeInOutSine(tween.t))
        matrix.setScale(band * 2f, 1f)
        matrix.postTranslate(x - band, 0f)
        gradient.setLocalMatrix(matrix)
        paint.strokeWidth = rc.dp(1.4f)
        paint.alpha = (255 * visibility).toInt().coerceIn(0, 255)
        canvas.drawPath(path, paint)
    }
}

/**
 * Throw-to-dismiss: the swiped-away island keeps its momentum as a detached blob that
 * shrinks and fades, connected to the regrowing island by a stretching neck until it snaps.
 */
class Ghost(private val rc: RenderContext) {
    val rect = RectF()
    var active = false
        private set
    var alpha = 0f
        private set

    /** Content scale relative to the moment it was thrown (for the content riding inside). */
    var scale = 1f
        private set
    private var vx = 0f
    private var life = 0f
    private var startW = 1f
    private var startH = 1f
    private var startRadius = 0f

    val radius: Float get() = min(rect.height() / 2f, startRadius * scale)

    fun start(from: RectF, radius: Float, velocityX: Float) {
        rect.set(from)
        startW = max(1f, from.width())
        startH = max(1f, from.height())
        startRadius = radius
        val minSpeed = rc.dp(1100f)
        vx = if (abs(velocityX) < minSpeed) sign(velocityX).takeIf { it != 0f }?.times(minSpeed) ?: minSpeed else velocityX
        life = 0f
        scale = 1f
        alpha = 1f
        active = true
    }

    fun step(dt: Float): Boolean {
        if (!active) return false
        life += dt
        rect.offset(vx * dt, 0f)
        vx *= exp(-2.8f * dt)
        scale *= exp(-2.4f * dt)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val w = startW * scale
        val h = startH * scale
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        alpha = (1f - life / LIFETIME).coerceIn(0f, 1f)
        if (alpha <= 0f || w < 2f) active = false
        return active
    }

    fun union(out: RectF) {
        if (active) out.union(rect)
    }

    private companion object {
        const val LIFETIME = 0.5f
    }
}

/**
 * Shared-element flight of an image between two rects: album art growing from the compact
 * thumbnail into the expanded card, or an app icon flying from the status bar into a banner.
 */
class Hero(private val rc: RenderContext) {
    private val image = RoundedImage()
    private val from = RectF()
    private var fromRadius = 0f
    private val progress = Spring(0f, SpringSpec(0.45f, 0.86f), restThreshold = 0.002f)
    private var curved = false
    private val current = RectF()

    var active = false
        private set
    var key: String? = null
        private set
    val value: Float get() = progress.value

    fun start(bitmap: Bitmap, key: String, from: RectF, fromRadius: Float, spec: SpringSpec, curved: Boolean) {
        image.set(bitmap)
        this.key = key
        this.from.set(from)
        this.fromRadius = fromRadius
        this.curved = curved
        progress.setSpec(spec)
        progress.snapTo(0f)
        progress.animateTo(1f)
        active = true
    }

    fun cancel() {
        active = false
        key = null
    }

    fun step(dt: Float): Boolean {
        if (!active) return false
        val moving = progress.step(dt)
        if (!moving && progress.value >= 0.999f) {
            active = false
            key = null
        }
        return moving || active
    }

    /** Draws the image heading for [to]; returns the rect it occupies this frame. */
    fun draw(canvas: Canvas, to: RectF, toRadius: Float, alpha: Float): RectF {
        val p = progress.value
        val size = lerp(from.width(), to.width(), p)
        var cx = lerp(from.centerX(), to.centerX(), p)
        var cy = lerp(from.centerY(), to.centerY(), p)
        if (curved) {
            // Quadratic arc: rises above the straight line, like something tossed into the island.
            val arc = rc.dp(26f) * 4f * p * (1f - p)
            cy -= arc
            cx += (to.centerX() - from.centerX()) * 0.08f * sin(PI.toFloat() * p)
        }
        val r = lerp(fromRadius, toRadius, p.coerceIn(0f, 1f))
        current.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        image.draw(canvas, current.left, current.top, size, r, alpha)
        return current
    }

    fun union(out: RectF, to: RectF) {
        if (!active) return
        out.union(from)
        out.union(to)
        if (curved) out.union(min(from.left, to.left), min(from.top, to.top) - rc.dp(30f), max(from.right, to.right), max(from.bottom, to.bottom))
    }
}
