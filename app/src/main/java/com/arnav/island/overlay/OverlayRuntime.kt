package com.arnav.island.overlay

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.WindowManager
import com.arnav.island.core.AppGraph
import com.arnav.island.core.IslandCommand
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventPriority
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.GlancePayload
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.MediaPayload
import com.arnav.island.events.battery.BatteryMonitor
import com.arnav.island.events.bluetooth.BluetoothMonitor
import com.arnav.island.events.calendar.CalendarMonitor
import com.arnav.island.events.system.ScreenRecordingMonitor
import com.arnav.island.events.system.ScreenshotMonitor
import com.arnav.island.events.system.SystemEventsMonitor
import com.arnav.island.events.system.SystemStatsMonitor
import com.arnav.island.events.system.TorchMonitor
import com.arnav.island.island.IslandController
import com.arnav.island.island.IslandState
import com.arnav.island.island.render.CustomTheme
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.IslandHaptics
import com.arnav.island.island.render.IslandSounds
import com.arnav.island.island.render.IslandView
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RenderSettings
import com.arnav.island.storage.AppVisibility
import com.arnav.island.storage.FullscreenMode
import com.arnav.island.storage.IslandSettings
import com.arnav.island.storage.PerformanceMode
import com.arnav.island.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Everything the overlay service runs: window, renderer, controller and event monitors.
 */
class OverlayRuntime(private val service: Service, private val graph: AppGraph) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val display: Display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    private val windowContext: Context = service.createDisplayContext(display)
        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)

    private val rc = RenderContext(windowContext, graph.images, graph.apps)
    private val view = IslandView(windowContext, rc, overlayMode = true)
    private val window = OverlayWindow(windowContext, view)
    private val statusBarVisible = MutableStateFlow(true)
    private val probe = FullscreenProbe(windowContext) { statusBarVisible.value = it }
    private val geometryEngine = GeometryEngine(windowContext)
    private val controller = IslandController(scope, graph.events, view)

    private val settingsFn: () -> IslandSettings = { settings }
    private val screen = ScreenStateMonitor(service)
    private val foreground = ForegroundAppMonitor(service, scope)
    private val battery = BatteryMonitor(service, graph.events, scope, settingsFn)
    private val bluetooth = BluetoothMonitor(service, graph.events, settingsFn)
    private val system = SystemEventsMonitor(service, graph.events, settingsFn)
    private val recording = ScreenRecordingMonitor(windowContext, graph.events, settingsFn)
    private val stats = SystemStatsMonitor(service, graph.events, scope, settingsFn, { battery.temperatureC }, { view.fps })
    private val haptics = IslandHaptics(windowContext) { settings.haptics }
    private val sounds = IslandSounds(windowContext) { settings.soundEffects }
    private val tilt = TiltSensor(windowContext) { dx, dy -> view.scene.setTilt(dx, dy); view.requestFrame() }
    private val torch = TorchMonitor(service, graph.events, settingsFn)
    private val calendar = CalendarMonitor(service, graph.events, scope, settingsFn)
    private val screenshots = ScreenshotMonitor(service, graph.events, graph.images, scope, settingsFn)

    /** Android's animator duration scale (Developer options); motion follows it like system UI. */
    private var systemAnimationScale = readAnimationScale()
    private val animationScaleObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            view.post {
                systemAnimationScale = readAnimationScale()
                applyRenderSettings()
            }
        }
    }

    private var settings: IslandSettings = graph.settings.state.value
    private var hud: DebugHud? = null
    private var hudJob: Job? = null
    private var hiddenReason: String? = "Starting"
    private val orientation = MutableStateFlow(windowContext.resources.configuration.orientation)
    private val windowRect = Rect()
    private val neededF = RectF()
    private val neededRect = Rect()
    private val maxRefresh: Float = display.supportedModes.maxOfOrNull { it.refreshRate } ?: 60f

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            rc.density = windowContext.resources.displayMetrics.density
            rc.fontScale = newConfig.fontScale
            rebuildGeometry(immediate = true)
            orientation.value = newConfig.orientation
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    fun start() {
        rc.haptics = haptics
        rc.sounds = sounds
        rebuildGeometry(immediate = true)
        applyRenderSettings()
        view.touchExtension = rc.geometry.touchExtension

        // Start around the compact pill; the bounds policy takes over from the first frame.
        val compact = rc.geometry.compactFrame()
        val margin = rc.dp(8f)
        window.attach(
            Rect(
                (compact.left - margin).toInt().coerceAtLeast(0),
                0,
                (compact.right + margin).toInt(),
                (compact.bottom + rc.geometry.touchExtension + margin).toInt(),
            )
        )
        windowRect.set(window.bounds)
        probe.attach()

        controller.onBounds = ::onIslandBounds
        controller.onAnimating = { animating ->
            if (Build.VERSION.SDK_INT < 35 && rc.settings.effectivePerformance == PerformanceMode.MAX_SMOOTHNESS) {
                window.setHighRefresh(animating, maxRefresh)
            }
        }
        controller.onStateChanged = { t ->
            graph.runtime.update { it.copy(stateLabel = t.to.label) }
            updateTilt()
            // Seamless status bar: clear it the moment a banner or card starts covering it.
            if (coversStatusBar()) StatusBarCleanup.setCardOpen(service, true)
        }
        controller.glanceProvider = ::buildGlance
        controller.start()
        controller.updateSettings(settings)

        screen.start()
        battery.start()
        system.start()
        recording.start()
        if (settings.bluetoothEnabled && bluetooth.hasPermission()) bluetooth.start()
        graph.media.start()
        if (settings.torchEnabled) torch.start()
        calendar.start()
        screenshots.start()
        service.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animationScaleObserver)
        windowContext.registerComponentCallbacks(configCallbacks)

        scope.launch { graph.settings.flow.collect(::onSettings) }
        scope.launch {
            graph.commands.collect { command ->
                when (command) {
                    is IslandCommand.Expand -> controller.expand(command.eventId)
                    IslandCommand.Collapse -> controller.collapse()
                    IslandCommand.Glance -> controller.showGlance()
                    IslandCommand.Stack -> controller.showStack()
                }
            }
        }
        scope.launch {
            combine(screen.state, statusBarVisible, foreground.current, graph.calibrating, orientation) { _, _, _, _, _ -> Unit }
                .collect { updateVisibility() }
        }
        scope.launch {
            foreground.current.collect {
                if (settings.appRules.values.any { r -> r.motionPreset != null }) applyRenderSettings()
            }
        }
        scope.launch {
            // Unlock bloom: the island greets you with a ring of the system accent.
            var wasLocked = screen.state.value.locked
            screen.state.collect { st ->
                if (wasLocked && st.usable && settings.unlockBloom) {
                    view.post {
                        view.scene.unlockBloom()
                        view.requestFrame()
                    }
                }
                wasLocked = st.locked || !st.interactive
            }
        }
        scope.launch {
            screen.state.collect { st ->
                if (!st.interactive) view.pauseRendering()
                if (st.usable) {
                    applyRenderSettings()
                    if (!Settings.canDrawOverlays(service)) service.stopSelf()
                }
                updateMonitors()
            }
        }
        graph.runtime.update { it.copy(serviceRunning = true, overlayAttached = window.attached) }
    }

    fun stop() {
        windowContext.unregisterComponentCallbacks(configCallbacks)
        service.contentResolver.unregisterContentObserver(animationScaleObserver)
        StatusBarCleanup.setCardOpen(service, false)
        torch.stop()
        calendar.stop()
        screenshots.stop()
        tilt.setActive(false)
        sounds.release()
        controller.stop()
        hudJob?.cancel()
        hud?.detach()
        stats.setActive(false)
        foreground.setActive(false)
        graph.media.stop()
        bluetooth.stop()
        recording.stop()
        system.stop()
        battery.stop()
        screen.stop()
        probe.detach()
        window.detach()
        scope.cancel()
        graph.runtime.update { it.copy(serviceRunning = false, overlayAttached = false, stateLabel = "Hidden") }
    }

    // ---------------------------------------------------------------------------------------------

    private fun onSettings(s: IslandSettings) {
        val old = settings
        settings = s
        applyRenderSettings()
        controller.updateSettings(s)
        if (old.geometryConfig() != s.geometryConfig() || old.autoDetectCutout != s.autoDetectCutout ||
            old.manualCameraXPx != s.manualCameraXPx || old.manualCameraYPx != s.manualCameraYPx || old.manualCameraRadiusPx != s.manualCameraRadiusPx
        ) {
            rebuildGeometry(immediate = false)
        }
        system.refresh()
        battery.refresh()
        graph.media.refresh()
        if (s.torchEnabled) torch.start() else torch.stop()
        torch.refresh()
        calendar.start()
        screenshots.start()
        graph.timers.resync()
        if (s.bluetoothEnabled && bluetooth.hasPermission()) bluetooth.start() else bluetooth.stop()
        if (s.debugHud) showHud() else hideHud()
        updateMonitors()
        updateVisibility()
        updateTilt()
    }

    /** True while a banner or card is heading below the visible status bar (pill toasts don't). */
    private fun coversStatusBar(): Boolean {
        val scene = view.scene
        val card = scene.state is IslandState.Toast || scene.state.isLarge
        return card && statusBarVisible.value && scene.target.bottom > rc.statusBarBottom + rc.dp(12f)
    }

    private fun readAnimationScale(): Float =
        Settings.Global.getFloat(service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f).takeIf { it > 0f } ?: 1f

    /** Tilt depth only listens while an open card is on screen and the screen is usable. */
    private fun updateTilt() {
        tilt.maxOffsetPx = rc.dp(3.5f) * rc.settings.motionIntensity
        val active = rc.settings.tiltDepth && !rc.settings.motion.reduceMotion && screen.state.value.usable && view.scene.state.isLarge
        tilt.setActive(active)
    }

    /** Long-press on the idle island: the day at a glance, from public system state only. */
    private fun buildGlance(): IslandEvent {
        val now = System.currentTimeMillis()
        val alarm = service.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime?.takeIf { it > now }
        val timers = graph.timers.state.value.timers.count { !it.ringing }
        val media = graph.events.current().live.firstOrNull { it.type == EventType.MEDIA }
        val playing = (media?.payload as? MediaPayload)?.takeIf { it.isPlaying }?.let { m ->
            if (m.artist.isBlank()) m.title else "${m.title} · ${m.artist}"
        }
        return IslandEvent(
            id = GLANCE_ID,
            source = EventSource.SYSTEM,
            type = EventType.GLANCE,
            timestamp = now,
            persistent = false,
            durationMs = GLANCE_MS,
            title = "Glance",
            icon = Glyph.CALENDAR,
            animation = EntranceAnimation.BLOOM,
            payload = GlancePayload(battery.level, battery.isCharging, alarm, timers, playing),
        )
    }

    private fun updateMonitors() {
        val usable = screen.state.value.usable
        val needsForeground = settings.gameMode ||
            settings.fullscreenMode == FullscreenMode.HIDE_IN_GAMES_VIDEOS ||
            settings.appRules.values.any { it.visibility != AppVisibility.NORMAL || it.motionPreset != null }
        foreground.setActive(usable && needsForeground)
        stats.setActive(usable && settings.monitorEnabled)
    }

    private fun rebuildGeometry(immediate: Boolean) {
        val measurement = geometryEngine.measure()
        rc.geometry = geometryEngine.build(settings, measurement)
        view.touchExtension = rc.geometry.touchExtension
        val g = rc.geometry
        graph.runtime.update {
            it.copy(
                cameraX = g.anchorX,
                cameraY = g.cameraY,
                cameraRadius = g.cameraRadius,
                cutoutSource = g.cutout.source,
                cutoutWidth = g.cutout.boundsRight - g.cutout.boundsLeft,
                cutoutHeight = g.cutout.boundsBottom - g.cutout.boundsTop,
                screenWidth = g.screen.widthPx,
                screenHeight = g.screen.heightPx,
                density = g.density,
                statusBarHeight = g.screen.statusBarHeightPx,
                deviceProfile = geometryEngine.profile.displayName,
                compactWidthDp = g.compactWidth / g.density,
                compactHeightDp = g.compactHeight / g.density,
                expandedWidthDp = g.expandedWidth / g.density,
            )
        }
        view.refreshGeometry(immediate)
    }

    private fun applyRenderSettings() {
        val s = settings
        val accent = if (Build.VERSION.SDK_INT >= 31) {
            windowContext.getColor(android.R.color.system_accent1_300)
        } else {
            IslandColors.BLUE
        }
        // Per-app personality: the foreground app's own motion preset, if it has one.
        val appPreset = settings.ruleFor(foreground.current.value).motionPreset
        rc.settings = RenderSettings(
            theme = s.islandTheme,
            motion = s.motionProfile(
                systemReduceMotion = !ValueAnimator.areAnimatorsEnabled(),
                systemSpeed = 1f / systemAnimationScale.coerceIn(0.25f, 4f),
                preset = appPreset,
            ),
            performance = s.performanceMode,
            systemPowerSave = service.getSystemService(PowerManager::class.java).isPowerSaveMode,
            waveform = s.mediaWaveform,
            compactMediaProgress = s.mediaCompactProgress,
            tintFromArtwork = s.tintFromArtwork,
            touchDeformation = s.touchDeformation,
            haptics = s.haptics,
            burnIn = s.burnInProtection,
            notificationStyle = s.notificationStyle,
            systemAccent = accent,
            calibrationGuides = graph.calibrating.value,
            swipeToDismiss = s.swipeToDismiss,
            swipeDownExpands = s.swipeDownExpands,
            motionIntensity = s.animationIntensity,
            squashStretch = s.squashStretch,
            iconFlight = s.iconFlight,
            arrivalPulse = s.arrivalPulse,
            lensGlint = s.lensGlint,
            blurReveal = s.blurReveal,
            tiltDepth = s.tiltDepth,
            custom = CustomTheme(s.customRimColor, s.customGlowColor, s.customHighlight, s.customRimWidthDp),
        )
        view.requestFrame()
    }

    private fun updateVisibility() {
        val st = screen.state.value
        val g = rc.geometry
        val calibrating = graph.calibrating.value
        val pkg = foreground.current.value
        val rule = settings.ruleFor(pkg)
        val fullscreen = !statusBarVisible.value
        rc.statusBarBottom = if (fullscreen) 0f else g.screen.statusBarHeightPx.toFloat()
        val isGame = pkg != null && graph.apps.isGame(pkg)
        val isVideo = pkg != null && graph.apps.isVideo(pkg)

        val reason = when {
            !st.interactive -> "Screen off"
            st.locked -> "Locked"
            !g.screen.isPortrait && !settings.showInLandscape -> "Landscape"
            calibrating -> null
            rule.visibility == AppVisibility.HIDDEN -> "Hidden in ${graph.apps.label(pkg!!)}"
            settings.gameMode && isGame -> "Game mode"
            fullscreen && settings.fullscreenMode == FullscreenMode.HIDE_IN_FULLSCREEN -> "Fullscreen"
            // Without Usage Access the app category is unknown: treat any fullscreen app as media.
            fullscreen && settings.fullscreenMode == FullscreenMode.HIDE_IN_GAMES_VIDEOS && (isGame || isVideo || pkg == null) -> "Fullscreen media"
            else -> null
        }
        val minPriority = if (!calibrating && rule.visibility == AppVisibility.IMPORTANT_ONLY) EventPriority.TIMER else null
        val visible = reason == null
        val immediate = !st.interactive
        if (visible) view.visibility = View.VISIBLE
        if (!visible || fullscreen) StatusBarCleanup.setCardOpen(service, false)
        rc.settings = rc.settings.copy(calibrationGuides = calibrating)
        controller.setVisibility(visible, minPriority, immediate)
        if (!visible && immediate) view.visibility = View.GONE
        hiddenReason = reason
        graph.runtime.update { it.copy(hiddenReason = reason, foregroundApp = pkg) }
    }

    /**
     * Window sizing policy: grow immediately to contain where the island is and where it is
     * heading; shrink back to a tight fit once everything has settled.
     */
    private fun onIslandBounds(bounds: RectF, settled: Boolean) {
        val g = rc.geometry
        val scene = view.scene
        // Seamless status bar: bring the icons back once the island has shrunk out of their way.
        if (settled && !coversStatusBar()) StatusBarCleanup.setCardOpen(service, false)
        if (settled && scene.state == IslandState.Hidden && scene.isFullyHidden) {
            view.visibility = View.GONE
            return
        }
        val marginX = rc.dp(10f) + bounds.width() * 0.05f
        val marginY = rc.dp(8f) + bounds.height() * 0.08f
        // Room for spring overshoot, plus the touch strip in compact states.
        val bottom = max(bounds.bottom + marginY, scene.touchBottom(g.touchExtension))
        neededF.set(bounds.left - marginX, 0f, bounds.right + marginX, bottom)
        OverlayWindow.toRect(neededF, g.screen.widthPx, g.screen.heightPx, neededRect)
        if (neededRect.isEmpty) return

        if (!windowRect.contains(neededRect)) {
            windowRect.union(neededRect)
            window.setBounds(windowRect)
        } else if (settled && differs(windowRect, neededRect)) {
            windowRect.set(neededRect)
            window.setBounds(windowRect)
        }
    }

    private fun differs(a: Rect, b: Rect) =
        abs(a.left - b.left) > 2 || abs(a.top - b.top) > 2 || abs(a.right - b.right) > 2 || abs(a.bottom - b.bottom) > 2

    // ---------------------------------------------------------------------------------------------
    // Debug HUD
    // ---------------------------------------------------------------------------------------------

    private fun showHud() {
        if (hud != null) return
        val h = DebugHud(windowContext)
        h.attach((rc.geometry.statusBandBottom + rc.dp(230f)).toInt())
        hud = h
        hudJob = scope.launch {
            val am = service.getSystemService(ActivityManager::class.java)
            while (isActive) {
                val schedule = graph.events.current()
                val g = rc.geometry
                val mem = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
                h.update(
                    listOf(
                        "fps" to String.format(Locale.US, "%.0f (max %.0f Hz)", view.fps, maxRefresh),
                        "frame" to String.format(Locale.US, "%.2f ms", view.frameMs),
                        "state" to view.scene.state.label.take(26),
                        "hidden" to (hiddenReason ?: "-"),
                        "queue" to "${schedule.queued.size} queued · ${schedule.live.size} live",
                        "toast" to (schedule.toast?.id?.take(24) ?: "-"),
                        "window" to "${windowRect.width()}×${windowRect.height()} @${windowRect.left},${windowRect.top}",
                        "camera" to String.format(Locale.US, "%.0f, %.0f r%.1f", g.anchorX, g.cameraY, g.cameraRadius),
                        "cutout" to "${g.cutout.source.label.take(14)} ${(g.cutout.boundsRight - g.cutout.boundsLeft).toInt()}×${(g.cutout.boundsBottom - g.cutout.boundsTop).toInt()}",
                        "media" to (graph.media.activePackage ?: "-"),
                        "memory" to "${Formatters.bytes(mem)} heap · ${Formatters.bytes(info.availMem)} free",
                        "images" to Formatters.bytes(graph.images.sizeBytes.toLong()),
                    )
                )
                delay(250)
            }
        }
    }

    private fun hideHud() {
        hudJob?.cancel()
        hudJob = null
        hud?.detach()
        hud = null
    }

    private companion object {
        const val GLANCE_ID = "glance"
        const val GLANCE_MS = 5_000L
    }
}
