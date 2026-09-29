package com.arnav.island.events.system

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.SystemKind
import com.arnav.island.events.SystemPayload
import com.arnav.island.storage.IslandSettings
import java.util.function.Consumer

/**
 * Android 15+ screen recording detection through the official
 * [WindowManager.addScreenRecordingCallback] API (DETECT_SCREEN_RECORDING, install-time). It
 * reports whether Island's own windows are being recorded, which, since the island is always on
 * screen, means "the screen is being recorded". Older Android versions: feature unavailable.
 */
class ScreenRecordingMonitor(
    private val windowContext: Context,
    private val engine: EventEngine,
    private val settings: () -> IslandSettings,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var callback: Consumer<Int>? = null
    private var since = 0L
    private val clear = Runnable {
        since = 0L
        engine.remove(ID)
    }

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 35

    fun start() {
        if (Build.VERSION.SDK_INT >= 35) startApi35()
    }

    @RequiresApi(35)
    private fun startApi35() {
        if (callback != null) return
        val wm = windowContext.getSystemService(WindowManager::class.java)
        val cb = Consumer<Int> { state -> onState(state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) }
        callback = cb
        try {
            onState(wm.addScreenRecordingCallback(windowContext.mainExecutor, cb) == WindowManager.SCREEN_RECORDING_STATE_VISIBLE)
        } catch (_: SecurityException) {
            callback = null
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 35) {
            callback?.let { windowContext.getSystemService(WindowManager::class.java).removeScreenRecordingCallback(it) }
        }
        callback = null
        handler.removeCallbacks(clear)
        engine.remove(ID)
    }

    private fun onState(recording: Boolean) {
        if (!settings().screenRecordEnabled) {
            engine.remove(ID)
            return
        }
        if (recording) {
            handler.removeCallbacks(clear)
            if (since == 0L) since = System.currentTimeMillis()
            engine.post(
                IslandEvent(
                    id = ID,
                    source = EventSource.SYSTEM,
                    type = EventType.SCREEN_RECORD,
                    timestamp = since,
                    persistent = true,
                    title = "Recording",
                    payload = SystemPayload(SystemKind.SCREEN_RECORDING, "Recording"),
                    contentKey = "recording",
                )
            )
        } else {
            // Brief grace period: the island hiding for a moment must not reset the timer.
            handler.removeCallbacks(clear)
            handler.postDelayed(clear, 2_500)
        }
    }

    companion object {
        const val ID = "system:recording"
    }
}
