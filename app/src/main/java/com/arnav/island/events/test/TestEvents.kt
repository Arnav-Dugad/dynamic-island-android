package com.arnav.island.events.test

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import com.arnav.island.events.ActionStyle
import com.arnav.island.events.BluetoothDeviceKind
import com.arnav.island.events.BluetoothPayload
import com.arnav.island.events.CallPayload
import com.arnav.island.events.CallState
import com.arnav.island.events.ChargingPayload
import com.arnav.island.events.ChargingTheme
import com.arnav.island.events.CustomPayload
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventColors
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandAction
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.MediaPayload
import com.arnav.island.events.NotificationPayload
import com.arnav.island.events.NotificationPrivacy
import com.arnav.island.events.PlugType
import com.arnav.island.events.ProgressPayload
import com.arnav.island.events.SeekAction
import com.arnav.island.events.StopwatchPayload
import com.arnav.island.events.SystemKind
import com.arnav.island.events.SystemPayload
import com.arnav.island.events.TimerPayload
import com.arnav.island.island.render.presenters.CallPresenter
import com.arnav.island.island.render.presenters.MediaPresenter
import com.arnav.island.island.render.presenters.TimerPresenter
import com.arnav.island.util.Bitmaps
import com.arnav.island.util.ColorExtractor
import com.arnav.island.util.ImageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Developer test events. Every one is clearly marked as a test in its text and uses source TEST,
 * so none of them can be mistaken for real system data. Used by the Developer panel, onboarding
 * and the in-app live preview.
 */
class TestEvents(private val engine: EventEngine, private val images: ImageStore, private val scope: CoroutineScope) {

    private var progressJob: Job? = null
    private val artwork: Bitmap by lazy { testArtwork() }
    private val artAccent: Int by lazy { ColorExtractor.accentFrom(Bitmaps.samplePixels(artwork)) }
    private var mediaPlaying = true
    private var mediaStartedAt = 0L

    fun music(playing: Boolean = true) {
        mediaPlaying = playing
        if (mediaStartedAt == 0L) mediaStartedAt = SystemClock.elapsedRealtime()
        val art = artwork
        val accent = artAccent
        val position = (SystemClock.elapsedRealtime() - mediaStartedAt) % DURATION
        engine.post(
            IslandEvent(
                id = "test:media",
                source = EventSource.TEST,
                type = EventType.MEDIA,
                timestamp = System.currentTimeMillis(),
                persistent = true,
                title = "Test Track",
                subtitle = "Island Preview",
                icon = Glyph.MUSIC,
                artwork = images.put(ART_KEY, art),
                actions = listOf(
                    IslandAction(MediaPresenter.ACTION_PREV, "Previous", Glyph.PREVIOUS) { mediaStartedAt = SystemClock.elapsedRealtime(); music(mediaPlaying) },
                    IslandAction(MediaPresenter.ACTION_TOGGLE, if (playing) "Pause" else "Play") { music(!mediaPlaying) },
                    IslandAction(MediaPresenter.ACTION_NEXT, "Next", Glyph.NEXT) { mediaStartedAt = SystemClock.elapsedRealtime(); music(mediaPlaying) },
                ),
                colors = EventColors(accent = accent),
                contentKey = "test-track",
                payload = MediaPayload(
                    packageName = "test",
                    appLabel = "Test",
                    title = "Test Track",
                    artist = "Island Preview",
                    album = "",
                    durationMs = DURATION,
                    positionMs = position,
                    positionUpdatedAt = SystemClock.elapsedRealtime(),
                    playbackSpeed = 1f,
                    isPlaying = playing,
                    canSkipNext = true,
                    canSkipPrevious = true,
                    canSeek = true,
                    accent = accent,
                    seek = SeekAction { f -> mediaStartedAt = SystemClock.elapsedRealtime() - (f * DURATION).toLong(); music(mediaPlaying) },
                ),
            )
        )
    }

    fun charging(theme: ChargingTheme = ChargingTheme.MINIMAL, level: Int = 64) = engine.post(
        IslandEvent(
            id = "test:charging",
            source = EventSource.TEST,
            type = EventType.CHARGING,
            timestamp = System.currentTimeMillis(),
            persistent = false,
            durationMs = 3_500,
            title = "Charging (test)",
            icon = Glyph.BOLT,
            animation = EntranceAnimation.POP,
            payload = ChargingPayload(level, PlugType.AC, isCharging = true, isFull = false, speed = null, temperatureC = null, voltageMv = null, timeToFullMs = null, theme = theme, countFrom = level - 16),
        )
    )

    fun notification(count: Int = 1) = engine.post(
        IslandEvent(
            id = "test:notification:$count",
            source = EventSource.TEST,
            type = EventType.NOTIFICATION,
            timestamp = System.currentTimeMillis(),
            persistent = false,
            durationMs = 4_500,
            title = "Test notification",
            subtitle = "This is how incoming notifications appear. Long-press to expand, swipe sideways to dismiss.",
            icon = Glyph.MESSAGE,
            mergeKey = "test:notification",
            actions = listOf(IslandAction("notif.action.0", "Mark done", collapses = true) {}),
            payload = NotificationPayload("test", "Island", "Test notification", "This is how incoming notifications appear. Long-press to expand, swipe sideways to dismiss.", emptyList(), NotificationPrivacy.FULL, true, System.currentTimeMillis(), false, false),
        )
    )

    fun bluetooth(connected: Boolean = true) = engine.post(
        IslandEvent(
            id = "test:bluetooth",
            source = EventSource.TEST,
            type = EventType.BLUETOOTH,
            timestamp = System.currentTimeMillis(),
            persistent = false,
            durationMs = 3_200,
            title = "Test Earbuds",
            icon = Glyph.EARBUDS,
            animation = EntranceAnimation.POP,
            contentKey = "$connected",
            payload = BluetoothPayload("Test Earbuds", BluetoothDeviceKind.EARBUDS, connected, if (connected) 82 else null),
        )
    )

    /** Preview-only timer (the Developer panel starts a real timer through TimerManager instead). */
    fun timer(minutes: Int = 5) {
        val now = System.currentTimeMillis()
        engine.post(
            IslandEvent(
                id = "test:timer",
                source = EventSource.TEST,
                type = EventType.TIMER,
                timestamp = now,
                persistent = true,
                title = "Timer",
                icon = Glyph.TIMER,
                actions = listOf(
                    IslandAction(TimerPresenter.ACTION_TOGGLE, "Pause", Glyph.PAUSE) {},
                    IslandAction(TimerPresenter.ACTION_SECONDARY, "Cancel", Glyph.CLOSE, collapses = true) { engine.remove("test:timer") },
                    IslandAction(TimerPresenter.ACTION_ADD, "+1 min", Glyph.PLUS) {},
                ),
                payload = TimerPayload(1, "", minutes * 60_000L, now + minutes * 60_000L, minutes * 60_000L, isPaused = false, isRinging = false, ringingSince = 0),
            )
        )
    }

    fun stopwatch() = engine.post(
        IslandEvent(
            id = "test:stopwatch",
            source = EventSource.TEST,
            type = EventType.STOPWATCH,
            timestamp = System.currentTimeMillis(),
            persistent = true,
            title = "Stopwatch",
            payload = StopwatchPayload(System.currentTimeMillis() - 83_000, 0, isRunning = true, laps = emptyList()),
        )
    )

    fun call() {
        val decline = IslandAction(CallPresenter.ACTION_DECLINE, "Decline", Glyph.PHONE_DOWN, ActionStyle.DESTRUCTIVE, collapses = true) { engine.remove("test:call") }
        val answer = IslandAction(CallPresenter.ACTION_ANSWER, "Answer", Glyph.PHONE, ActionStyle.POSITIVE, collapses = true) { ongoingCall() }
        engine.post(
            IslandEvent(
                id = "test:call",
                source = EventSource.TEST,
                type = EventType.CALL_INCOMING,
                timestamp = System.currentTimeMillis(),
                persistent = true,
                title = "Test Caller",
                icon = Glyph.PHONE,
                actions = listOf(decline, answer),
                autoExpand = true,
                blocksToasts = true,
                contentKey = "incoming",
                payload = CallPayload("Test Caller", "Call-style UI test", "Island", CallState.INCOMING, null, isVideo = false, hasAvatar = false),
            )
        )
    }

    private fun ongoingCall() = engine.post(
        IslandEvent(
            id = "test:call",
            source = EventSource.TEST,
            type = EventType.CALL_ONGOING,
            timestamp = System.currentTimeMillis(),
            persistent = true,
            title = "Test Caller",
            icon = Glyph.PHONE,
            actions = listOf(IslandAction(CallPresenter.ACTION_HANGUP, "Hang up", Glyph.PHONE_DOWN, ActionStyle.DESTRUCTIVE, collapses = true) { engine.remove("test:call") }),
            contentKey = "ongoing",
            payload = CallPayload("Test Caller", "", "Island", CallState.ONGOING, System.currentTimeMillis(), isVideo = false, hasAvatar = false),
        )
    )

    fun progress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            var p = 0f
            while (p < 1f) {
                engine.post(progressEvent(p))
                delay(180)
                p += 0.035f
            }
            engine.remove("test:progress")
            engine.post(
                IslandEvent(
                    id = "test:progress-done",
                    source = EventSource.TEST,
                    type = EventType.PROGRESS_DONE,
                    timestamp = System.currentTimeMillis(),
                    persistent = false,
                    durationMs = 2_400,
                    title = "test-file.zip",
                    animation = EntranceAnimation.POP,
                    payload = ProgressPayload("Test", "test", "test-file.zip", 1f, indeterminate = false, done = true),
                )
            )
        }
    }

    private fun progressEvent(p: Float) = IslandEvent(
        id = "test:progress",
        source = EventSource.TEST,
        type = EventType.PROGRESS,
        timestamp = System.currentTimeMillis(),
        persistent = true,
        title = "test-file.zip",
        icon = Glyph.DOWNLOAD,
        progress = p,
        contentKey = "progress",
        payload = ProgressPayload("Test download", "test", "test-file.zip", p.coerceAtMost(1f), indeterminate = false, done = false),
    )

    fun split() {
        music(true)
        timer(3)
    }

    fun ringer(kind: SystemKind = SystemKind.RINGER_SILENT) = engine.post(
        IslandEvent(
            id = "test:system",
            source = EventSource.TEST,
            type = EventType.SYSTEM,
            timestamp = System.currentTimeMillis(),
            persistent = false,
            durationMs = 2_000,
            title = "Silent",
            animation = EntranceAnimation.POP,
            contentKey = kind.name,
            payload = SystemPayload(kind, when (kind) {
                SystemKind.RINGER_VIBRATE -> "Vibrate"
                SystemKind.RINGER_NORMAL -> "Ring"
                SystemKind.DND_ON -> "Do not disturb"
                else -> "Silent"
            }),
        )
    )

    fun custom() = engine.post(
        IslandEvent(
            id = "test:custom",
            source = EventSource.TEST,
            type = EventType.CUSTOM,
            timestamp = System.currentTimeMillis(),
            persistent = false,
            durationMs = 4_000,
            title = "Custom activity",
            subtitle = "Posted through the local API format",
            icon = Glyph.SPARK,
            progress = 0.42f,
            payload = CustomPayload("Island", null),
        )
    )

    fun clear() {
        progressJob?.cancel()
        engine.removeWhere { it.source == EventSource.TEST }
    }

    /** Original generated artwork (no bundled images, nothing copyrighted). */
    private fun testArtwork(): Bitmap {
        val size = 256
        val bmp = createBitmap(size, size)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFF3A1C71.toInt(), 0xFFD76D77.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = RadialGradient(size * 0.72f, size * 0.3f, size * 0.55f, 0xCCFFAF7B.toInt(), 0x00FFAF7B, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
        p.color = 0x33FFFFFF
        c.drawCircle(size * 0.34f, size * 0.66f, size * 0.22f, p)
        return bmp
    }

    companion object {
        private const val DURATION = 214_000L
        private const val ART_KEY = "test:art"
    }
}
