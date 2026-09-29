package com.arnav.island.events.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.IntentCompat
import com.arnav.island.IslandApp
import com.arnav.island.events.EventColors
import com.arnav.island.events.CustomPayload
import com.arnav.island.events.EventPriority
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.util.Bitmaps

/**
 * Local-only API for the user's own apps (see client/IslandClient.kt and the README).
 *
 * Protection: the receiver requires [IslandApiContract.PERMISSION], a *dangerous* custom
 * permission, so an app can only post after the user explicitly grants it at runtime. The API is
 * also disabled by default in Island's settings. Nothing is ever sent over a network.
 */
object IslandApiContract {
    const val PERMISSION = "com.arnav.island.permission.POST_ISLAND_ACTIVITY"
    const val ACTION_SHOW = "com.arnav.island.action.SHOW_ACTIVITY"
    const val ACTION_DISMISS = "com.arnav.island.action.DISMISS_ACTIVITY"

    const val EXTRA_ID = "id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_SUBTITLE = "subtitle"
    /** Name of a built-in glyph, e.g. "TIMER", "DOWNLOAD", "SPARK". */
    const val EXTRA_ICON = "icon"
    /** Optional small Bitmap (<= 256 px) used instead of a glyph. */
    const val EXTRA_ICON_BITMAP = "icon_bitmap"
    /** 0..1, or omit / -1 for none. */
    const val EXTRA_PROGRESS = "progress"
    /** 0 or omitted = persistent live activity; > 0 = temporary for that many ms. */
    const val EXTRA_DURATION_MS = "duration_ms"
    const val EXTRA_ACCENT = "accent"
    /** "low", "normal" or "high". */
    const val EXTRA_PRIORITY = "priority"
    /** Label shown for the sending app (optional). */
    const val EXTRA_APP_LABEL = "app_label"
}

class IslandApiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as IslandApp).graph
        if (!graph.settings.state.value.localApiEnabled) return
        IslandApiHandler.handle(context, intent, sender = senderPackage(), graph = graph)
    }

    private fun senderPackage(): String? =
        if (Build.VERSION.SDK_INT >= 34) sentFromPackage else null
}

/** Shared by the receiver and the in-app "Test local API" button. */
object IslandApiHandler {
    private const val MAX_TEXT = 120

    fun handle(context: Context, intent: Intent, sender: String?, graph: com.arnav.island.core.AppGraph) {
        val rawId = intent.getStringExtra(IslandApiContract.EXTRA_ID)?.take(64)?.ifBlank { null } ?: "default"
        val namespace = sender ?: "local"
        val id = "api:$namespace:$rawId"
        when (intent.action) {
            IslandApiContract.ACTION_DISMISS -> graph.events.remove(id)
            IslandApiContract.ACTION_SHOW -> {
                val title = intent.getStringExtra(IslandApiContract.EXTRA_TITLE)?.take(MAX_TEXT).orEmpty()
                if (title.isBlank()) return
                val subtitle = intent.getStringExtra(IslandApiContract.EXTRA_SUBTITLE)?.take(MAX_TEXT).orEmpty()
                val glyph = intent.getStringExtra(IslandApiContract.EXTRA_ICON)?.let { name -> Glyph.entries.firstOrNull { it.name.equals(name, true) } }
                val progress = intent.getFloatExtra(IslandApiContract.EXTRA_PROGRESS, -1f).takeIf { it in 0f..1f }
                val duration = intent.getLongExtra(IslandApiContract.EXTRA_DURATION_MS, 0L).coerceIn(0L, 60_000L)
                val priority = when (intent.getStringExtra(IslandApiContract.EXTRA_PRIORITY)) {
                    "high" -> EventPriority.NAVIGATION
                    "low" -> EventPriority.BACKGROUND_ACTIVITY
                    else -> EventPriority.NOTIFICATION
                }
                val iconRef = IntentCompat.getParcelableExtra(intent, IslandApiContract.EXTRA_ICON_BITMAP, Bitmap::class.java)?.let {
                    graph.images.put("api-icon:$id", Bitmaps.fit(it, 128))
                }
                val appLabel = intent.getStringExtra(IslandApiContract.EXTRA_APP_LABEL)?.take(40)
                    ?: sender?.let { graph.apps.label(it) }
                    ?: "Local app"
                graph.events.post(
                    IslandEvent(
                        id = id,
                        source = EventSource.API,
                        type = EventType.CUSTOM,
                        priority = priority,
                        timestamp = System.currentTimeMillis(),
                        persistent = duration == 0L,
                        durationMs = if (duration > 0) duration else IslandEvent.DEFAULT_TOAST_MS,
                        title = title,
                        subtitle = subtitle,
                        icon = glyph ?: Glyph.SPARK,
                        iconImage = iconRef,
                        progress = progress,
                        colors = EventColors(accent = intent.getIntExtra(IslandApiContract.EXTRA_ACCENT, 0)),
                        contentKey = "$title|$subtitle",
                        payload = CustomPayload(appLabel, sender),
                    )
                )
            }
        }
    }
}
