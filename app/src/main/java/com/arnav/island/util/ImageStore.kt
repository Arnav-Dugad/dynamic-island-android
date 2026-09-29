package com.arnav.island.util

import android.graphics.Bitmap
import android.util.LruCache
import com.arnav.island.events.ImageRef

/**
 * Process-wide bitmap cache keyed by [ImageRef]. Everything placed here is already downscaled to
 * render size on a background thread, so the renderer never decodes on the main thread.
 */
class ImageStore(maxBytes: Int = 16 * 1024 * 1024) {

    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun put(key: String, bitmap: Bitmap): ImageRef {
        cache.put(key, bitmap)
        return ImageRef(key)
    }

    fun get(ref: ImageRef?): Bitmap? = ref?.let { cache.get(it.key) }

    fun contains(key: String): Boolean = cache.get(key) != null

    fun remove(key: String) {
        cache.remove(key)
    }

    val sizeBytes: Int get() = cache.size()
}
