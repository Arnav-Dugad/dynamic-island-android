package com.arnav.island.island.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.arnav.island.animation.MotionProfile
import com.arnav.island.island.IslandGeometry
import com.arnav.island.island.ScreenSpec
import com.arnav.island.storage.IslandTheme
import com.arnav.island.storage.NotificationStyle
import com.arnav.island.storage.PerformanceMode
import com.arnav.island.util.AppInfoCache
import com.arnav.island.util.ImageStore

/** Island palette: tuned for OLED black, original values. */
object IslandColors {
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_SECONDARY = 0x9EFFFFFF.toInt()
    const val TEXT_TERTIARY = 0x66FFFFFF.toInt()
    const val CONTROL = 0x24FFFFFF
    const val CONTROL_PRESSED = 0x3DFFFFFF
    const val TRACK = 0x2EFFFFFF
    const val GREEN = 0xFF3DDC84.toInt()
    const val ORANGE = 0xFFFFA23A.toInt()
    const val RED = 0xFFFF5147.toInt()
    const val BLUE = 0xFF4C9BFF.toInt()
    const val YELLOW = 0xFFFFD54A.toInt()
    const val PURPLE = 0xFFB18CFF.toInt()
    const val TEAL = 0xFF5EE6D0.toInt()
}

/** The user's own island theme: rim, glow under the island and a top highlight. */
data class CustomTheme(
    val rim: Int = 0xFF7CF7FF.toInt(),
    val glow: Int = 0xFF7B4DFF.toInt(),
    val highlight: Float = 0.5f,
    val rimWidthDp: Float = 1.2f,
)

/** Render-relevant subset of the user's settings. */
data class RenderSettings(
    val theme: IslandTheme = IslandTheme.CLASSIC_BLACK,
    val motion: MotionProfile = MotionProfile.Default,
    val performance: PerformanceMode = PerformanceMode.ADAPTIVE,
    val systemPowerSave: Boolean = false,
    val waveform: Boolean = true,
    val compactMediaProgress: Boolean = false,
    val tintFromArtwork: Boolean = true,
    val touchDeformation: Boolean = true,
    val haptics: Boolean = true,
    val burnIn: Boolean = true,
    val notificationStyle: NotificationStyle = NotificationStyle.BANNER,
    val systemAccent: Int = IslandColors.BLUE,
    val calibrationGuides: Boolean = false,
    val swipeToDismiss: Boolean = true,
    val swipeDownExpands: Boolean = true,
    /** 0..2 multiplier for expressive motion (squash and stretch, speed-reactive corners). */
    val motionIntensity: Float = 1f,
    val squashStretch: Boolean = true,
    val iconFlight: Boolean = true,
    val arrivalPulse: Boolean = true,
    val lensGlint: Boolean = true,
    val blurReveal: Boolean = true,
    val tiltDepth: Boolean = true,
    val custom: CustomTheme = CustomTheme(),
) {
    val effectivePerformance: PerformanceMode
        get() = if (performance == PerformanceMode.ADAPTIVE && systemPowerSave) PerformanceMode.BATTERY_SAVER else performance

    /** Minimum interval between frames for purely decorative motion (waveforms, particles). */
    val decorativeFrameMs: Long
        get() = when (effectivePerformance) {
            PerformanceMode.MAX_SMOOTHNESS, PerformanceMode.ADAPTIVE -> 0L
            PerformanceMode.BALANCED -> 16L
            PerformanceMode.BATTERY_SAVER -> 33L
        }

    val particles: Boolean get() = effectivePerformance != PerformanceMode.BATTERY_SAVER
    val minimalContent: Boolean get() = theme == IslandTheme.MINIMAL
}

/**
 * Shared drawing state for every presenter. Holds all paints so nothing is allocated per frame.
 */
class RenderContext(
    val context: Context,
    val images: ImageStore,
    val apps: AppInfoCache,
) {
    var density: Float = context.resources.displayMetrics.density
    var fontScale: Float = context.resources.configuration.fontScale
    lateinit var geometry: IslandGeometry
    var settings = RenderSettings()

    /** Wall clock used by presenters (injectable for previews). */
    var clock: () -> Long = System::currentTimeMillis

    /**
     * Bottom of the visible status bar in screen px (0 when an app hides it). Touches above this
     * line belong to SystemUI, so the island's touch strip is placed below it.
     */
    var statusBarBottom = 0f

    /** Monotonic animation time in seconds, advanced by the scene every frame. */
    var animTime = 0f

    /** Camera centre x relative to the current shape's left edge (set every frame). */
    var cameraXInShape = 0f

    /** Sub-pixel content shift for OLED burn-in protection (content only, never the shape). */
    var burnInX = 0f
    var burnInY = 0f

    val glyphs = GlyphPainter()

    /** Haptic and sound feedback (null in contexts that should stay silent). */
    var haptics: IslandHaptics? = null
    var sounds: IslandSounds? = null

    /** Called when a row of the activity stack is tapped. */
    var onStackPick: ((String) -> Unit)? = null

    fun dp(v: Float): Float = v * density

    /** Text size that honours font scale within limits so the compact pill never overflows. */
    fun sp(v: Float): Float = v * density * fontScale.coerceIn(0.9f, 1.18f)

    private val regular: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    private val medium: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    private val semibold: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    private val bold: Typeface = Typeface.create(Typeface.DEFAULT, 700, false)

    val titlePaint = textPaint(semibold)
    val bodyPaint = textPaint(regular)
    val labelPaint = textPaint(medium)
    val captionPaint = textPaint(medium)
    val numberPaint = textPaint(semibold).apply { fontFeatureSettings = "tnum" }
    val bigNumberPaint = textPaint(bold).apply { fontFeatureSettings = "tnum" }

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val rect = RectF()
    val rect2 = RectF()

    private fun textPaint(tf: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = tf
        color = IslandColors.TEXT
    }

    fun ellipsize(text: CharSequence, paint: TextPaint, width: Float): String =
        if (width <= 0f) "" else TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    /** Draws text with [alpha] multiplied into the paint colour. y is the baseline. */
    fun text(canvas: Canvas, text: String, x: Float, y: Float, paint: TextPaint, color: Int, alpha: Float, align: Paint.Align = Paint.Align.LEFT) {
        if (alpha <= 0.004f || text.isEmpty()) return
        paint.color = color
        paint.alpha = (((color ushr 24) * alpha)).toInt().coerceIn(0, 255)
        paint.textAlign = align
        canvas.drawText(text, x, y, paint)
    }

    /** Baseline that vertically centres a single line of [paint] on [centerY]. */
    fun baseline(paint: Paint, centerY: Float): Float {
        val fm = paint.fontMetrics
        return centerY - (fm.ascent + fm.descent) / 2f
    }

    fun circle(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Float) {
        if (alpha <= 0.004f) return
        fill.shader = null
        fill.color = color
        fill.alpha = (((color ushr 24) * alpha)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r, fill)
    }

    fun roundRect(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int, alpha: Float) {
        if (alpha <= 0.004f) return
        fill.shader = null
        fill.color = color
        fill.alpha = (((color ushr 24) * alpha)).toInt().coerceIn(0, 255)
        rect.set(l, t, r, b)
        canvas.drawRoundRect(rect, radius, radius, fill)
    }

    fun screen(): ScreenSpec = geometry.screen
}

/**
 * Draws a bitmap clipped to a rounded rect or circle using a cached [BitmapShader]; only the
 * matrix changes per frame.
 */
class RoundedImage {
    private var bitmap: Bitmap? = null
    private var shader: BitmapShader? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()

    val hasImage: Boolean get() = bitmap != null

    fun set(bmp: Bitmap?) {
        if (bmp === bitmap) return
        bitmap = bmp
        shader = bmp?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }

    fun draw(canvas: Canvas, l: Float, t: Float, size: Float, radius: Float, alpha: Float) {
        val bmp = bitmap ?: return
        val sh = shader ?: return
        if (alpha <= 0.004f || size <= 0f) return
        val scale = size / minOf(bmp.width, bmp.height).toFloat()
        matrix.setScale(scale, scale)
        matrix.postTranslate(l - (bmp.width * scale - size) / 2f, t - (bmp.height * scale - size) / 2f)
        sh.setLocalMatrix(matrix)
        paint.shader = sh
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        bounds.set(l, t, l + size, t + size)
        canvas.drawRoundRect(bounds, radius, radius, paint)
    }
}
