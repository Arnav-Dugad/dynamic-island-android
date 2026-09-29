package com.arnav.island.island.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser
import com.arnav.island.animation.lerp
import com.arnav.island.events.Glyph
import java.util.EnumMap

/**
 * Vector glyphs defined in a 24x24 box and drawn with canvas transforms, so they stay razor sharp
 * at any animated scale. Several shapes use Material Icons path data (Apache License 2.0); the
 * rest are drawn geometrically here. No Apple assets are used.
 */
class GlyphPainter {

    private class Part(val path: Path, val stroke: Boolean, val strokeWidth: Float = 2f)

    private val cache = EnumMap<Glyph, List<Part>>(Glyph::class.java)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val morphPath = Path()
    private val tmpRect = RectF()

    /** Draws [glyph] centred on (cx, cy) with the given box [size] in pixels. */
    fun draw(canvas: Canvas, glyph: Glyph, cx: Float, cy: Float, size: Float, color: Int, alpha: Float = 1f) {
        if (alpha <= 0.004f || size <= 0f) return
        if (glyph == Glyph.PLAY || glyph == Glyph.PAUSE) {
            drawPlayPause(canvas, cx, cy, size, if (glyph == Glyph.PLAY) 0f else 1f, color, alpha)
            return
        }
        val parts = cache.getOrPut(glyph) { build(glyph) }
        val scale = size / 24f
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(scale, scale)
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        for (part in parts) {
            val paint = if (part.stroke) stroke.apply { strokeWidth = part.strokeWidth } else fill
            paint.color = color
            paint.alpha = ((color ushr 24) * a) / 255
            canvas.drawPath(part.path, paint)
        }
        canvas.restore()
    }

    /**
     * Continuous play (t = 0) to pause (t = 1) morph: the triangle splits into two quads that
     * become the pause bars. Used when the media state flips so the button never "swaps".
     */
    fun drawPlayPause(canvas: Canvas, cx: Float, cy: Float, size: Float, t: Float, color: Int, alpha: Float) {
        if (alpha <= 0.004f) return
        val s = size / 24f
        val ox = cx - size / 2f
        val oy = cy - size / 2f
        fun x(v: Float) = ox + v * s
        fun y(v: Float) = oy + v * s
        val p = t.coerceIn(0f, 1f)

        fill.color = color
        fill.alpha = (((color ushr 24) * alpha)).toInt().coerceIn(0, 255)
        stroke.color = fill.color
        stroke.alpha = fill.alpha
        stroke.strokeWidth = 2.2f * s

        // Left quad: triangle left half -> left bar.
        morphPath.rewind()
        morphPath.moveTo(x(lerp(7.5f, 6.4f, p)), y(lerp(5.0f, 5.2f, p)))
        morphPath.lineTo(x(lerp(13.2f, 10.2f, p)), y(lerp(8.55f, 5.2f, p)))
        morphPath.lineTo(x(lerp(13.2f, 10.2f, p)), y(lerp(15.45f, 18.8f, p)))
        morphPath.lineTo(x(lerp(7.5f, 6.4f, p)), y(lerp(19.0f, 18.8f, p)))
        morphPath.close()
        // Right quad: triangle right half -> right bar.
        morphPath.moveTo(x(lerp(13.2f, 13.8f, p)), y(lerp(8.55f, 5.2f, p)))
        morphPath.lineTo(x(lerp(18.8f, 17.6f, p)), y(lerp(12.0f, 5.2f, p)))
        morphPath.lineTo(x(lerp(18.8f, 17.6f, p)), y(lerp(12.0f, 18.8f, p)))
        morphPath.lineTo(x(lerp(13.2f, 13.8f, p)), y(lerp(15.45f, 18.8f, p)))
        morphPath.close()
        canvas.drawPath(morphPath, fill)
        // A thin round-joined stroke softens every corner like a rounded symbol.
        canvas.drawPath(morphPath, stroke)
    }

    /** Circular progress ring (track + arc). */
    fun drawRing(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, thickness: Float,
        fraction: Float, color: Int, trackColor: Int, alpha: Float, startAngle: Float = -90f,
    ) {
        if (alpha <= 0.004f) return
        tmpRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        stroke.strokeWidth = thickness
        stroke.color = trackColor
        stroke.alpha = (((trackColor ushr 24) * alpha)).toInt().coerceIn(0, 255)
        canvas.drawOval(tmpRect, stroke)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0.001f) {
            stroke.color = color
            stroke.alpha = (((color ushr 24) * alpha)).toInt().coerceIn(0, 255)
            canvas.drawArc(tmpRect, startAngle, 360f * f, false, stroke)
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun svg(data: String, stroke: Boolean = false, width: Float = 2f) = Part(PathParser.createPathFromPathData(data), stroke, width)

    private fun build(glyph: Glyph): List<Part> = when (glyph) {
        Glyph.MUSIC -> listOf(svg("M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"))
        Glyph.BELL -> listOf(svg("M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z"))
        Glyph.BELL_OFF -> listOf(
            svg("M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z"),
            Part(Path().apply { moveTo(3.5f, 3.5f); lineTo(20.5f, 20.5f) }, stroke = true, strokeWidth = 2.4f),
        )
        Glyph.VIBRATE -> listOf(svg("M0 15h2V9H0v6zm3 2h2V7H3v10zm19-8v6h2V9h-2zm-3 8h2V7h-2v10zM16.5 3h-9C6.67 3 6 3.67 6 4.5v15c0 .83.67 1.5 1.5 1.5h9c.83 0 1.5-.67 1.5-1.5v-15c0-.83-.67-1.5-1.5-1.5zM16 19H8V5h8v14z"))
        Glyph.MOON -> listOf(svg("M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9 9-4.03 9-9c0-.46-.04-.92-.1-1.36-.98 1.37-2.58 2.26-4.4 2.26-2.98 0-5.4-2.42-5.4-5.4 0-1.81.89-3.42 2.26-4.4-.44-.06-.9-.1-1.36-.1z"))
        Glyph.BOLT -> listOf(
            Part(Path().apply {
                moveTo(13.4f, 2.6f); lineTo(5.2f, 13.6f); lineTo(11.2f, 13.6f)
                lineTo(10.4f, 21.4f); lineTo(18.8f, 10.2f); lineTo(12.6f, 10.2f); close()
            }, stroke = false),
            Part(Path().apply {
                moveTo(13.4f, 2.6f); lineTo(5.2f, 13.6f); lineTo(11.2f, 13.6f)
                lineTo(10.4f, 21.4f); lineTo(18.8f, 10.2f); lineTo(12.6f, 10.2f); close()
            }, stroke = true, strokeWidth = 1.2f),
        )
        Glyph.BATTERY -> listOf(
            Part(Path().apply { addRoundRect(RectF(2.5f, 7f, 19.5f, 17f), 3f, 3f, Path.Direction.CW) }, stroke = true, strokeWidth = 1.6f),
            Part(Path().apply { addRoundRect(RectF(20.6f, 10.2f, 22.2f, 13.8f), 0.8f, 0.8f, Path.Direction.CW) }, stroke = false),
        )
        Glyph.BLUETOOTH -> listOf(svg("M17.71 7.71L12 2h-1v7.59L6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 11 14.41V22h1l5.71-5.71-4.3-4.29 4.3-4.29zM13 5.83l1.88 1.88L13 9.59V5.83zm1.88 10.46L13 18.17v-3.76l1.88 1.88z"))
        Glyph.HEADPHONES -> listOf(svg("M12 1c-4.97 0-9 4.03-9 9v7c0 1.66 1.34 3 3 3h3v-8H5v-2c0-3.87 3.13-7 7-7s7 3.13 7 7v2h-4v8h3c1.66 0 3-1.34 3-3v-7c0-4.97-4.03-9-9-9z"))
        Glyph.EARBUDS -> listOf(
            Part(Path().apply {
                addCircle(7.5f, 8f, 3.6f, Path.Direction.CW)
                addCircle(16.5f, 8f, 3.6f, Path.Direction.CW)
            }, stroke = false),
            Part(Path().apply {
                moveTo(8.6f, 10.5f); lineTo(8.6f, 19.5f)
                moveTo(15.4f, 10.5f); lineTo(15.4f, 19.5f)
            }, stroke = true, strokeWidth = 2.6f),
        )
        Glyph.WATCH -> listOf(svg("M20 12c0-2.54-1.19-4.81-3.04-6.27L16 0H8l-.95 5.73C5.19 7.19 4 9.45 4 12s1.19 4.81 3.05 6.27L8 24h8l.96-5.73C18.81 16.81 20 14.54 20 12zM6 12c0-3.31 2.69-6 6-6s6 2.69 6 6-2.69 6-6 6-6-2.69-6-6z"))
        Glyph.SPEAKER -> listOf(svg("M17 2H7c-1.1 0-2 .9-2 2v16c0 1.1.9 1.99 2 1.99L17 22c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-5 2c1.1 0 2 .9 2 2s-.9 2-2 2c-1.11 0-2-.9-2-2s.89-2 2-2zm0 16c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"))
        Glyph.CAR -> listOf(svg("M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.5 16c-.83 0-1.5-.67-1.5-1.5S5.67 13 6.5 13s1.5.67 1.5 1.5S7.33 16 6.5 16zm11 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM5 11l1.5-4.5h11L19 11H5z"))
        Glyph.DEVICE -> listOf(svg("M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z"))
        Glyph.PHONE -> listOf(svg("M20.01 15.38c-1.23 0-2.42-.2-3.53-.56-.35-.12-.74-.03-1.01.24l-1.57 1.97c-2.83-1.35-5.48-3.9-6.89-6.83l1.95-1.66c.27-.28.35-.67.24-1.02-.37-1.11-.56-2.3-.56-3.53 0-.54-.45-.99-.99-.99H4.19C3.65 3 3 3.24 3 3.99 3 13.28 10.73 21 20.01 21c.71 0 .99-.63.99-1.18v-3.45c0-.54-.45-.99-.99-.99z"))
        Glyph.PHONE_DOWN -> listOf(svg("M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .39-.23.74-.56.9-.98.49-1.87 1.12-2.66 1.85-.18.18-.43.28-.7.28-.28 0-.53-.11-.71-.29L.29 13.08c-.18-.17-.29-.42-.29-.7 0-.28.11-.53.29-.71C3.34 8.78 7.46 7 12 7s8.66 1.78 11.71 4.67c.18.18.29.43.29.71 0 .28-.11.53-.29.71l-2.48 2.48c-.18.18-.43.29-.71.29-.27 0-.52-.11-.7-.28-.79-.74-1.69-1.36-2.67-1.85-.33-.16-.56-.5-.56-.9v-3.1C15.15 9.25 13.6 9 12 9z"))
        Glyph.VIDEO -> listOf(svg("M17 10.5V7c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l4 4v-11l-4 4z"))
        Glyph.TIMER -> listOf(svg("M15 1H9v2h6V1zm-4 13h2V8h-2v6zm8.03-6.61l1.42-1.42c-.43-.51-.9-.99-1.41-1.41l-1.42 1.42C16.07 4.74 14.12 4 12 4c-4.97 0-9 4.03-9 9s4.02 9 9 9 9-4.03 9-9c0-2.12-.74-4.07-1.97-5.61zM12 20c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z"))
        Glyph.STOPWATCH -> listOf(
            Part(Path().apply {
                addCircle(12f, 13.5f, 7.6f, Path.Direction.CW)
                moveTo(12f, 13.5f); lineTo(14.8f, 10.2f)
                moveTo(10f, 2.8f); lineTo(14f, 2.8f)
                moveTo(12f, 2.8f); lineTo(12f, 5.6f)
                moveTo(18.2f, 6.6f); lineTo(19.6f, 5.2f)
            }, stroke = true, strokeWidth = 2.1f),
        )
        Glyph.NAV_ARROW -> listOf(svg("M12 2L4.5 20.29l.71.71L12 18l6.79 3 .71-.71z"))
        Glyph.DOWNLOAD -> listOf(svg("M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z"))
        Glyph.CHECK -> listOf(Part(Path().apply { moveTo(5f, 12.6f); lineTo(9.6f, 17.2f); lineTo(19.2f, 7.4f) }, stroke = true, strokeWidth = 2.6f))
        Glyph.ROTATE -> listOf(
            Part(Path().apply {
                addArc(RectF(4f, 4f, 20f, 20f), 200f, 250f)
            }, stroke = true, strokeWidth = 2.2f),
            Part(Path().apply { moveTo(4.2f, 5.2f); lineTo(4.4f, 10.2f); lineTo(9.2f, 9.2f); close() }, stroke = false),
        )
        Glyph.ROTATE_LOCK -> listOf(
            Part(Path().apply { addRoundRect(RectF(7.5f, 11f, 16.5f, 18.5f), 1.8f, 1.8f, Path.Direction.CW) }, stroke = false),
            Part(Path().apply { addArc(RectF(9.3f, 6.2f, 14.7f, 11.6f), 180f, 180f); moveTo(9.3f, 8.9f); lineTo(9.3f, 11.2f); moveTo(14.7f, 8.9f); lineTo(14.7f, 11.2f) }, stroke = true, strokeWidth = 1.8f),
            Part(Path().apply { addArc(RectF(2.5f, 2.5f, 21.5f, 21.5f), 120f, 300f) }, stroke = true, strokeWidth = 1.6f),
        )
        Glyph.HOTSPOT -> listOf(svg("M12 11c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm6 2c0-3.31-2.69-6-6-6s-6 2.69-6 6c0 2.22 1.21 4.15 3 5.19l1-1.74c-1.19-.7-2-1.97-2-3.45 0-2.21 1.79-4 4-4s4 1.79 4 4c0 1.48-.81 2.75-2 3.45l1 1.74c1.79-1.04 3-2.97 3-5.19zM12 3C6.48 3 2 7.48 2 13c0 3.7 2.01 6.92 4.99 8.65l1-1.73C5.61 18.53 4 15.96 4 13c0-4.42 3.58-8 8-8s8 3.58 8 8c0 2.96-1.61 5.53-4 6.92l1 1.73c2.99-1.73 5-4.95 5-8.65 0-5.52-4.48-10-10-10z"))
        Glyph.RECORD -> listOf(Part(Path().apply { addCircle(12f, 12f, 6.5f, Path.Direction.CW) }, stroke = false))
        Glyph.CLIPBOARD -> listOf(svg("M19 2h-4.18C14.4.84 13.3 0 12 0c-1.3 0-2.4.84-2.82 2H5c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm7 18H5V4h2v3h10V4h2v16z"))
        Glyph.CHIP -> listOf(svg("M15 9H9v6h6V9zm-2 4h-2v-2h2v2zm8-2V9h-2V7c0-1.1-.9-2-2-2h-2V3h-2v2h-2V3H9v2H7c-1.1 0-2 .9-2 2v2H3v2h2v2H3v2h2v2c0 1.1.9 2 2 2h2v2h2v-2h2v2h2v-2h2c1.1 0 2-.9 2-2v-2h2v-2h-2v-2h2zm-4 6H7V7h10v10z"))
        Glyph.NEXT -> listOf(
            Part(Path().apply { moveTo(4.5f, 6.2f); lineTo(12.2f, 12f); lineTo(4.5f, 17.8f); close(); moveTo(12.2f, 6.2f); lineTo(19.9f, 12f); lineTo(12.2f, 17.8f); close() }, stroke = false),
            Part(Path().apply { moveTo(4.5f, 6.2f); lineTo(12.2f, 12f); lineTo(4.5f, 17.8f); close(); moveTo(12.2f, 6.2f); lineTo(19.9f, 12f); lineTo(12.2f, 17.8f); close() }, stroke = true, strokeWidth = 1.4f),
        )
        Glyph.PREVIOUS -> listOf(
            Part(Path().apply { moveTo(19.5f, 6.2f); lineTo(11.8f, 12f); lineTo(19.5f, 17.8f); close(); moveTo(11.8f, 6.2f); lineTo(4.1f, 12f); lineTo(11.8f, 17.8f); close() }, stroke = false),
            Part(Path().apply { moveTo(19.5f, 6.2f); lineTo(11.8f, 12f); lineTo(19.5f, 17.8f); close(); moveTo(11.8f, 6.2f); lineTo(4.1f, 12f); lineTo(11.8f, 17.8f); close() }, stroke = true, strokeWidth = 1.4f),
        )
        Glyph.PLUS -> listOf(Part(Path().apply { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }, stroke = true, strokeWidth = 2.4f))
        Glyph.CLOSE -> listOf(Part(Path().apply { moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f); moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f) }, stroke = true, strokeWidth = 2.4f))
        Glyph.STOP -> listOf(Part(Path().apply { addRoundRect(RectF(6.5f, 6.5f, 17.5f, 17.5f), 2.4f, 2.4f, Path.Direction.CW) }, stroke = false))
        Glyph.FLAG -> listOf(svg("M14.4 6L14 4H5v17h2v-7h5.6l.4 2h7V6z"))
        Glyph.INFO -> listOf(svg("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"))
        Glyph.WARNING -> listOf(svg("M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"))
        Glyph.SPARK -> listOf(svg("M19 9l1.25-2.75L23 5l-2.75-1.25L19 1l-1.25 2.75L15 5l2.75 1.25L19 9zm-7.5.5L9 4 6.5 9.5 1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5zM19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25L19 15z"))
        Glyph.MESSAGE -> listOf(svg("M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z"))
        Glyph.OPEN -> listOf(svg("M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z"))
        Glyph.REPLY -> listOf(svg("M10 9V5l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z"))
        Glyph.CALENDAR -> listOf(svg("M19 4h-1V2h-2v2H8V2H6v2H5c-1.11 0-1.99.9-1.99 2L3 20c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 16H5V10h14v10zm0-12H5V6h14v2z"))
        Glyph.ALARM -> listOf(svg("M22 5.72l-4.6-3.86-1.29 1.53 4.6 3.86L22 5.72zM7.88 3.39L6.6 1.86 2 5.71l1.29 1.53 4.59-3.85zM12.5 8H11v6l4.75 2.85.75-1.23-4-2.37V8zM12 4c-4.97 0-9 4.03-9 9s4.02 9 9 9c4.97 0 9-4.03 9-9s-4.03-9-9-9zm0 16c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z"))
        Glyph.PLAY, Glyph.PAUSE -> emptyList()
    }
}
