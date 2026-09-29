package com.arnav.island.events.system

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.ImageRef
import com.arnav.island.events.IslandAction
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.ScreenshotPayload
import com.arnav.island.island.render.presenters.ScreenshotPresenter
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Diagnostics
import com.arnav.island.util.ImageStore
import com.arnav.island.util.Launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screenshot preview: when a new image appears in a Screenshots folder, its thumbnail drops into
 * the island with Share and Edit. Opt-in and off by default; needs photo access, which Island asks
 * for only when this is turned on. Only the newest screenshot is looked at, and nothing is kept.
 */
class ScreenshotMonitor(
    private val context: Context,
    private val engine: EventEngine,
    private val images: ImageStore,
    private val scope: CoroutineScope,
    private val settings: () -> IslandSettings,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var observing = false
    private var lastId = -1L

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = check()
    }

    fun hasPermission(): Boolean = context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!settings().screenshotPreview || !hasPermission()) {
            stop()
            return
        }
        if (observing) return
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        observing = true
    }

    fun stop() {
        if (!observing) return
        context.contentResolver.unregisterContentObserver(observer)
        observing = false
    }

    private fun check() {
        if (!settings().enabled || !settings().screenshotPreview) return
        scope.launch {
            val found = withContext(Dispatchers.IO) { newest() } ?: return@launch
            val (id, uri) = found
            if (id == lastId) return@launch
            lastId = id
            val thumb = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null)
                } catch (e: Exception) {
                    Diagnostics.w(TAG, "Screenshot thumbnail unavailable", e)
                    null
                }
            }
            post(uri, thumb?.let { images.put("shot:$id", it) })
        }
    }

    /** The newest image in a Screenshots folder, if it was added in the last few seconds. */
    private fun newest(): Pair<Long, Uri>? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val since = System.currentTimeMillis() / 1000 - RECENT_S
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.IS_PENDING} = 0")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%Screenshot%", since.toString()))
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        return try {
            context.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), args, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val id = c.getLong(0)
                id to ContentUris.withAppendedId(collection, id)
            }
        } catch (e: SecurityException) {
            Diagnostics.w(TAG, "Photo access missing", e)
            null
        }
    }

    private fun post(uri: Uri, thumb: ImageRef?) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val share = Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share screenshot",
        )
        val edit = Intent(Intent.ACTION_EDIT).setDataAndType(uri, "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        engine.post(
            IslandEvent(
                id = ID,
                source = EventSource.SYSTEM,
                type = EventType.SCREENSHOT,
                timestamp = System.currentTimeMillis(),
                persistent = false,
                durationMs = 5_000,
                title = "Screenshot saved",
                icon = Glyph.SCREENSHOT,
                artwork = thumb,
                actions = listOf(
                    IslandAction(ScreenshotPresenter.ACTION_SHARE, "Share", Glyph.SHARE, collapses = true) { Launch.startActivity(context, share) },
                    IslandAction(ScreenshotPresenter.ACTION_EDIT, "Edit", Glyph.EDIT, collapses = true) { Launch.startActivity(context, edit) },
                ),
                tapAction = IslandAction("shot.open", "Open") { Launch.startActivity(context, view) },
                animation = EntranceAnimation.POP,
                payload = ScreenshotPayload(uri.toString()),
            )
        )
    }

    companion object {
        const val ID = "screenshot"
        private const val TAG = "IslandScreenshots"
        private const val RECENT_S = 10L
        private const val THUMB_PX = 320

        fun permission(): String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
