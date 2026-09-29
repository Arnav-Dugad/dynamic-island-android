package com.arnav.island.events.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.util.Size
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.ImageRef
import com.arnav.island.events.IslandAction
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.EventColors
import com.arnav.island.events.MediaPayload
import com.arnav.island.events.SeekAction
import com.arnav.island.events.notification.IslandNotificationListener
import com.arnav.island.island.render.presenters.MediaPresenter
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.AppInfoCache
import com.arnav.island.util.Bitmaps
import com.arnav.island.util.ColorExtractor
import com.arnav.island.util.ImageStore
import com.arnav.island.util.Launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Follows Android media sessions (any MediaSession-compatible player). Requires notification
 * access, which is how Android grants third-party apps visibility of active sessions. No media
 * history is stored; artwork lives only in the in-memory cache.
 */
class MediaSessionMonitor(
    private val context: Context,
    private val engine: EventEngine,
    private val images: ImageStore,
    private val apps: AppInfoCache,
    private val scope: CoroutineScope,
    private val settings: () -> IslandSettings,
) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, IslandNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val tracked = LinkedHashMap<MediaSession.Token, Tracked>()
    private val accents = LruCache<String, Int>(32)
    private val loadingArt = HashSet<String>()
    private var started = false

    /** Package of the session currently shown, for the debug HUD. */
    var activePackage: String? = null
        private set

    private inner class Tracked(val controller: MediaController) : MediaController.Callback() {
        var lastPlayingAt = 0L

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state.isPlayingish()) lastPlayingAt = System.currentTimeMillis()
            recompute()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) = recompute()

        override fun onSessionDestroyed() {
            untrack(controller.sessionToken)
            recompute()
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        sync(list.orEmpty())
        recompute()
    }

    fun start() {
        if (started) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            sync(manager.getActiveSessions(component))
            started = true
            recompute()
        } catch (e: SecurityException) {
            // Notification access not granted yet; the listener service calls refresh() later.
            Log.i(TAG, "Media sessions unavailable: notification access not granted")
        }
    }

    fun stop() {
        if (started) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (_: RuntimeException) {
            }
        }
        tracked.keys.toList().forEach(::untrack)
        started = false
        activePackage = null
        engine.remove(ID)
    }

    /** Called when notification access becomes available or settings change. */
    fun refresh() {
        if (!started) {
            start()
            return
        }
        try {
            sync(manager.getActiveSessions(component))
        } catch (_: SecurityException) {
            stop()
            return
        }
        recompute()
    }

    private fun sync(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        tracked.keys.filter { it !in tokens }.forEach(::untrack)
        for (controller in list) {
            if (tracked.containsKey(controller.sessionToken)) continue
            val t = Tracked(controller)
            if (controller.playbackState.isPlayingish()) t.lastPlayingAt = System.currentTimeMillis()
            controller.registerCallback(t, handler)
            tracked[controller.sessionToken] = t
        }
    }

    private fun untrack(token: MediaSession.Token) {
        tracked.remove(token)?.let { it.controller.unregisterCallback(it) }
    }

    private fun recompute() {
        if (!settings().mediaEnabled) {
            engine.remove(ID)
            activePackage = null
            return
        }
        // Prefer whatever is playing (in the system's own priority order), else the most
        // recently played paused session.
        val playing = tracked.values.firstOrNull { it.controller.playbackState.isPlayingish() && it.controller.metadata != null }
        val chosen = playing ?: tracked.values
            .filter { it.controller.metadata != null && it.lastPlayingAt > 0 }
            .maxByOrNull { it.lastPlayingAt }
        if (chosen == null) {
            engine.remove(ID)
            activePackage = null
            return
        }
        post(chosen)
    }

    private fun post(t: Tracked) {
        val controller = t.controller
        val metadata = controller.metadata ?: return
        val state = controller.playbackState
        val pkg = controller.packageName
        val title = metadata.text(MediaMetadata.METADATA_KEY_TITLE) ?: metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        if (title.isBlank()) {
            engine.remove(ID)
            return
        }
        val artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        val album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        val playing = state.isPlayingish()
        val actions = state?.actions ?: 0L
        activePackage = pkg

        val artKey = "art:" + "$pkg|$title|$artist|$album".hashCode()
        val artRef = if (images.contains(artKey)) ImageRef(artKey) else null
        if (artRef == null) loadArtwork(metadata, artKey)
        val iconKey = "app:$pkg@96"
        if (!images.contains(iconKey)) loadAppIcon(pkg, iconKey)
        val accent = accents.get(artKey) ?: 0

        val transport = controller.transportControls
        val eventActions = listOf(
            IslandAction(MediaPresenter.ACTION_PREV, "Previous", Glyph.PREVIOUS) { transport.skipToPrevious() },
            IslandAction(MediaPresenter.ACTION_TOGGLE, if (playing) "Pause" else "Play", if (playing) Glyph.PAUSE else Glyph.PLAY) {
                if (controller.playbackState.isPlayingish()) transport.pause() else transport.play()
            },
            IslandAction(MediaPresenter.ACTION_NEXT, "Next", Glyph.NEXT) { transport.skipToNext() },
        )
        val open = IslandAction("media.open", "Open ${apps.label(pkg)}", Glyph.OPEN) {
            if (!Launch.send(context, controller.sessionActivity)) Launch.openApp(context, pkg)
        }

        engine.post(
            IslandEvent(
                id = ID,
                source = EventSource.MEDIA,
                type = EventType.MEDIA,
                timestamp = System.currentTimeMillis(),
                expiresAt = if (playing) null else System.currentTimeMillis() + settings().mediaPausedTimeoutMin * 60_000L,
                persistent = true,
                title = title,
                subtitle = artist,
                icon = Glyph.MUSIC,
                iconImage = if (images.contains(iconKey)) ImageRef(iconKey) else null,
                artwork = artRef,
                actions = eventActions,
                colors = EventColors(accent = accent),
                contentKey = "$pkg|$title|$artist",
                tapAction = open,
                payload = MediaPayload(
                    packageName = pkg,
                    appLabel = apps.label(pkg),
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = duration,
                    positionMs = state?.position ?: 0L,
                    positionUpdatedAt = state?.lastPositionUpdateTime ?: 0L,
                    playbackSpeed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                    isPlaying = playing,
                    canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                    canSkipPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
                    canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
                    accent = accent,
                    seek = SeekAction { fraction -> if (duration > 0) transport.seekTo((fraction * duration).toLong()) },
                ),
            )
        )
    }

    private fun loadArtwork(metadata: MediaMetadata, key: String) {
        if (!loadingArt.add(key)) return
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeArtwork(metadata) }
            loadingArt.remove(key)
            if (bitmap != null) {
                val scaled = withContext(Dispatchers.Default) { Bitmaps.fit(bitmap, ART_SIZE_PX) }
                val accent = withContext(Dispatchers.Default) { ColorExtractor.accentFrom(Bitmaps.samplePixels(scaled)) }
                images.put(key, scaled)
                accents.put(key, accent)
                recompute()
            }
        }
    }

    private fun decodeArtwork(metadata: MediaMetadata): Bitmap? {
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return it }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return it }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return it }
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: return null
        return try {
            context.contentResolver.loadThumbnail(Uri.parse(uri), Size(ART_SIZE_PX, ART_SIZE_PX), null)
        } catch (e: Exception) {
            // Remote http(s) artwork is intentionally not fetched: Island has no network access.
            null
        }
    }

    private fun loadAppIcon(pkg: String, key: String) {
        if (!loadingArt.add(key)) return
        scope.launch {
            val icon = apps.icon(pkg, 96)
            loadingArt.remove(key)
            if (icon != null) {
                images.put(key, icon)
                recompute()
            }
        }
    }

    private fun MediaMetadata.text(key: String): String? = getString(key)?.takeIf { it.isNotBlank() }

    companion object {
        const val ID = "media"
        private const val TAG = "IslandMedia"
        private const val ART_SIZE_PX = 256

        fun PlaybackState?.isPlayingish(): Boolean = when (this?.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
            PlaybackState.STATE_CONNECTING -> true
            else -> false
        }
    }
}
