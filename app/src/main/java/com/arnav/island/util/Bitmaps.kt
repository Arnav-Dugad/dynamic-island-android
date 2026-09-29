package com.arnav.island.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlin.math.max
import kotlin.math.roundToInt

object Bitmaps {

    /** Renders any drawable (including adaptive icons) into a square bitmap. */
    fun fromDrawable(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return fit(drawable.bitmap, sizePx)
        }
        val bmp = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bmp)
        if (drawable is AdaptiveIconDrawable) {
            // Draw at the full adaptive size so the platform mask shape is applied.
            drawable.setBounds(0, 0, sizePx, sizePx)
        } else {
            drawable.setBounds(0, 0, sizePx, sizePx)
        }
        drawable.draw(canvas)
        return bmp
    }

    /** Downscales (never upscales) keeping aspect, then centre-crops to a square. */
    fun fit(source: Bitmap, sizePx: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return createBitmap(1, 1)
        val side = minOf(w, h)
        val cropped = if (w != h) {
            Bitmap.createBitmap(source, (w - side) / 2, (h - side) / 2, side, side)
        } else {
            source
        }
        if (side <= sizePx) return cropped.copyIfHardware()
        return cropped.scale(sizePx, sizePx, filter = true)
    }

    /** Samples a tiny thumbnail for colour extraction. */
    fun samplePixels(source: Bitmap, side: Int = 24): IntArray {
        val scaled = source.copyIfHardware().scale(side, max(1, (side * source.height.toFloat() / max(1, source.width)).roundToInt()), filter = true)
        val px = IntArray(scaled.width * scaled.height)
        scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        return px
    }

    private fun Bitmap.copyIfHardware(): Bitmap =
        if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
}
