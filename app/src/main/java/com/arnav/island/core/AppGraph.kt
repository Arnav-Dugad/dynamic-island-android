package com.arnav.island.core

import android.app.Application
import com.arnav.island.events.EventEngine
import com.arnav.island.events.media.MediaSessionMonitor
import com.arnav.island.events.notification.NotificationProcessor
import com.arnav.island.events.test.TestEvents
import com.arnav.island.events.timer.TimerManager
import com.arnav.island.island.CutoutSource
import com.arnav.island.storage.IslandSettings
import com.arnav.island.storage.SettingsRepository
import com.arnav.island.util.AppInfoCache
import com.arnav.island.util.ImageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Live facts about the running overlay, for the settings UI and the debug HUD. */
data class RuntimeStatus(
    val serviceRunning: Boolean = false,
    val overlayAttached: Boolean = false,
    val stateLabel: String = "Hidden",
    val hiddenReason: String? = null,
    val cameraX: Float = 0f,
    val cameraY: Float = 0f,
    val cameraRadius: Float = 0f,
    val cutoutSource: CutoutSource? = null,
    val cutoutWidth: Float = 0f,
    val cutoutHeight: Float = 0f,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val density: Float = 0f,
    val statusBarHeight: Int = 0,
    val foregroundApp: String? = null,
    val deviceProfile: String = "",
)

/**
 * Manual dependency graph. One instance per process, created by [com.arnav.island.IslandApp].
 * Everything here is cheap to construct; heavier monitors are started by the overlay service.
 */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsRepository(app, scope)
    private val currentSettings: () -> IslandSettings = { settings.state.value }

    val events = EventEngine(scope)
    val images = ImageStore()
    val apps = AppInfoCache(app)
    val timers = TimerManager(app, scope, events, currentSettings)
    val notifications = NotificationProcessor(app, events, images, apps, scope, currentSettings)
    val media = MediaSessionMonitor(app, events, images, apps, scope, currentSettings)
    val testEvents = TestEvents(events, images, scope)

    val runtime = MutableStateFlow(RuntimeStatus(deviceProfile = DeviceProfiles.detect().displayName))

    /** True while the calibration screen is open: keeps the island visible with guides. */
    val calibrating = MutableStateFlow(false)

    /** UI → overlay requests, handled by the running overlay (no-op when it is off). */
    val commands = MutableSharedFlow<IslandCommand>(extraBufferCapacity = 8)
}

sealed interface IslandCommand {
    data class Expand(val eventId: String? = null) : IslandCommand
    data object Collapse : IslandCommand
}
