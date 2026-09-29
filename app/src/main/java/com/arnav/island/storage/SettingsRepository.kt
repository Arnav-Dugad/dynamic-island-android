package com.arnav.island.storage

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.arnav.island.events.NotificationPrivacy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "island_settings")

/**
 * Local-only preferences (DataStore). Nothing here ever leaves the device.
 */
class SettingsRepository(private val context: Context, private val scope: CoroutineScope) {

    val flow: Flow<IslandSettings> = context.settingsStore.data
        .catch { e ->
            if (e is IOException) {
                Log.w(TAG, "Settings read failed, using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map(::read)
        .distinctUntilChanged()

    private val _loaded = MutableStateFlow(false)

    /** True once the first read from disk completed (UI waits for it to avoid flashing defaults). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Hot copy for UI. Starts from defaults until the first read completes. */
    val state: StateFlow<IslandSettings> = flow
        .onEach { _loaded.value = true }
        .stateIn(scope, SharingStarted.Eagerly, IslandSettings())

    suspend fun current(): IslandSettings = flow.first()

    suspend fun update(transform: (IslandSettings) -> IslandSettings) {
        context.settingsStore.edit { prefs ->
            val before = read(prefs)
            val after = transform(before)
            if (after != before) write(prefs, after)
        }
    }

    /** Fire-and-forget variant for UI callbacks. */
    fun set(transform: (IslandSettings) -> IslandSettings) {
        scope.launch { update(transform) }
    }

    fun setRule(packageName: String, rule: AppRule) = set { s ->
        val rules = s.appRules.toMutableMap()
        if (rule.isDefault) rules.remove(packageName) else rules[packageName] = rule
        s.copy(appRules = rules)
    }

    suspend fun resetCalibration() = update {
        val d = IslandSettings()
        it.copy(
            autoDetectCutout = true,
            manualCameraXPx = d.manualCameraXPx,
            manualCameraYPx = d.manualCameraYPx,
            manualCameraRadiusPx = d.manualCameraRadiusPx,
            offsetXPx = 0f,
            offsetYPx = 0f,
            cameraPaddingDp = d.cameraPaddingDp,
            idleWidthDp = 0f,
            idleHeightDp = 0f,
            compactWidthDp = 0f,
            compactHeightDp = 0f,
            expandedMaxWidthDp = 0f,
            cornerRoundness = d.cornerRoundness,
        )
    }

    // ---------------------------------------------------------------------------------------------

    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val startOnBoot = booleanPreferencesKey("start_on_boot")

        val autoDetect = booleanPreferencesKey("auto_detect_cutout")
        val manualX = floatPreferencesKey("manual_camera_x")
        val manualY = floatPreferencesKey("manual_camera_y")
        val manualR = floatPreferencesKey("manual_camera_r")
        val offsetX = floatPreferencesKey("offset_x")
        val offsetY = floatPreferencesKey("offset_y")
        val cameraPadding = floatPreferencesKey("camera_padding_dp")
        val idleStyle = stringPreferencesKey("idle_style")
        val idleW = floatPreferencesKey("idle_w_dp")
        val idleH = floatPreferencesKey("idle_h_dp")
        val compactW = floatPreferencesKey("compact_w_dp")
        val compactH = floatPreferencesKey("compact_h_dp")
        val expandedMaxW = floatPreferencesKey("expanded_max_w_dp")
        val cornerRoundness = floatPreferencesKey("corner_roundness")
        val touchExtension = floatPreferencesKey("touch_extension_dp")
        val showInLandscape = booleanPreferencesKey("show_in_landscape")

        val motionPreset = stringPreferencesKey("motion_preset")
        val animationSpeed = floatPreferencesKey("animation_speed")
        val animationIntensity = floatPreferencesKey("animation_intensity")
        val customResponse = floatPreferencesKey("custom_response")
        val customDamping = floatPreferencesKey("custom_damping")
        val squashStretch = booleanPreferencesKey("squash_stretch")
        val iconFlight = booleanPreferencesKey("icon_flight")
        val arrivalPulse = booleanPreferencesKey("arrival_pulse")
        val lensGlint = booleanPreferencesKey("lens_glint")
        val blurReveal = booleanPreferencesKey("blur_reveal")
        val tiltDepth = booleanPreferencesKey("tilt_depth")
        val soundEffects = booleanPreferencesKey("sound_effects")
        val glance = booleanPreferencesKey("glance_enabled")
        val statusBarCleanup = booleanPreferencesKey("status_bar_cleanup")
        val statusBarIcons = stringPreferencesKey("status_bar_icons")
        val statusBarBackup = stringPreferencesKey("status_bar_backup")
        val lastSeenVersion = intPreferencesKey("last_seen_version")

        val haptics = booleanPreferencesKey("haptics")
        val tapAction = stringPreferencesKey("tap_action")
        val swipeToDismiss = booleanPreferencesKey("swipe_to_dismiss")
        val swipeDownExpands = booleanPreferencesKey("swipe_down_expands")
        val touchDeformation = booleanPreferencesKey("touch_deformation")
        val autoCollapse = intPreferencesKey("auto_collapse_s")

        val islandTheme = stringPreferencesKey("island_theme")
        val appTheme = stringPreferencesKey("app_theme")
        val amoled = booleanPreferencesKey("amoled_black")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val tintFromArtwork = booleanPreferencesKey("tint_from_artwork")

        val mediaEnabled = booleanPreferencesKey("media_enabled")
        val mediaWaveform = booleanPreferencesKey("media_waveform")
        val mediaCompactProgress = booleanPreferencesKey("media_compact_progress")
        val mediaPausedTimeout = intPreferencesKey("media_paused_timeout_min")

        val notificationsEnabled = booleanPreferencesKey("notifications_enabled")
        val notificationDuration = longPreferencesKey("notification_duration_ms")
        val notificationPrivacy = stringPreferencesKey("notification_privacy")
        val notificationStyle = stringPreferencesKey("notification_style")
        val hideSensitive = booleanPreferencesKey("hide_sensitive")

        val callsEnabled = booleanPreferencesKey("calls_enabled")
        val navigationEnabled = booleanPreferencesKey("navigation_enabled")
        val progressEnabled = booleanPreferencesKey("progress_enabled")

        val chargingEnabled = booleanPreferencesKey("charging_enabled")
        val chargingTheme = stringPreferencesKey("charging_theme")
        val chargingDuration = longPreferencesKey("charging_duration_ms")
        val chargingLive = booleanPreferencesKey("charging_live")
        val batteryLow = booleanPreferencesKey("battery_low_enabled")
        val batteryLowThreshold = intPreferencesKey("battery_low_threshold")
        val batteryFull = booleanPreferencesKey("battery_full_enabled")
        val batteryTemp = booleanPreferencesKey("battery_show_temp")

        val bluetoothEnabled = booleanPreferencesKey("bluetooth_enabled")
        val bluetoothAll = booleanPreferencesKey("bluetooth_all_devices")

        val timersEnabled = booleanPreferencesKey("timers_enabled")
        val stopwatchEnabled = booleanPreferencesKey("stopwatch_enabled")

        val ringer = booleanPreferencesKey("ringer_enabled")
        val dnd = booleanPreferencesKey("dnd_enabled")
        val headset = booleanPreferencesKey("headset_enabled")
        val rotation = booleanPreferencesKey("rotation_enabled")
        val hotspot = booleanPreferencesKey("hotspot_enabled")
        val screenRecord = booleanPreferencesKey("screen_record_enabled")
        val clipboard = booleanPreferencesKey("clipboard_enabled")

        val split = booleanPreferencesKey("split_enabled")

        val fullscreenMode = stringPreferencesKey("fullscreen_mode")
        val gameMode = booleanPreferencesKey("game_mode")
        val appRules = stringPreferencesKey("app_rules_json")

        val performance = stringPreferencesKey("performance_mode")
        val burnIn = booleanPreferencesKey("burn_in_protection")

        val debugHud = booleanPreferencesKey("debug_hud")
        val localApi = booleanPreferencesKey("local_api")
        val monitor = booleanPreferencesKey("monitor_enabled")
        val monitorRam = booleanPreferencesKey("monitor_ram")
        val monitorNet = booleanPreferencesKey("monitor_net")
        val monitorCpu = booleanPreferencesKey("monitor_cpu")
        val monitorTemp = booleanPreferencesKey("monitor_temp")
        val monitorFps = booleanPreferencesKey("monitor_fps")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private fun read(p: Preferences): IslandSettings {
        val d = IslandSettings()
        return IslandSettings(
            enabled = p[K.enabled] ?: d.enabled,
            onboardingDone = p[K.onboardingDone] ?: d.onboardingDone,
            startOnBoot = p[K.startOnBoot] ?: d.startOnBoot,
            autoDetectCutout = p[K.autoDetect] ?: d.autoDetectCutout,
            manualCameraXPx = p[K.manualX] ?: d.manualCameraXPx,
            manualCameraYPx = p[K.manualY] ?: d.manualCameraYPx,
            manualCameraRadiusPx = p[K.manualR] ?: d.manualCameraRadiusPx,
            offsetXPx = p[K.offsetX] ?: d.offsetXPx,
            offsetYPx = p[K.offsetY] ?: d.offsetYPx,
            cameraPaddingDp = p[K.cameraPadding] ?: d.cameraPaddingDp,
            idleStyle = p.enum(K.idleStyle, d.idleStyle),
            idleWidthDp = p[K.idleW] ?: d.idleWidthDp,
            idleHeightDp = p[K.idleH] ?: d.idleHeightDp,
            compactWidthDp = p[K.compactW] ?: d.compactWidthDp,
            compactHeightDp = p[K.compactH] ?: d.compactHeightDp,
            expandedMaxWidthDp = p[K.expandedMaxW] ?: d.expandedMaxWidthDp,
            cornerRoundness = p[K.cornerRoundness] ?: d.cornerRoundness,
            touchExtensionDp = p[K.touchExtension] ?: d.touchExtensionDp,
            showInLandscape = p[K.showInLandscape] ?: d.showInLandscape,
            motionPreset = p.enum(K.motionPreset, d.motionPreset),
            animationSpeed = p[K.animationSpeed] ?: d.animationSpeed,
            animationIntensity = p[K.animationIntensity] ?: d.animationIntensity,
            customResponse = p[K.customResponse] ?: d.customResponse,
            customDamping = p[K.customDamping] ?: d.customDamping,
            squashStretch = p[K.squashStretch] ?: d.squashStretch,
            iconFlight = p[K.iconFlight] ?: d.iconFlight,
            arrivalPulse = p[K.arrivalPulse] ?: d.arrivalPulse,
            lensGlint = p[K.lensGlint] ?: d.lensGlint,
            blurReveal = p[K.blurReveal] ?: d.blurReveal,
            tiltDepth = p[K.tiltDepth] ?: d.tiltDepth,
            soundEffects = p[K.soundEffects] ?: d.soundEffects,
            glanceEnabled = p[K.glance] ?: d.glanceEnabled,
            statusBarCleanup = p[K.statusBarCleanup] ?: d.statusBarCleanup,
            statusBarIcons = p[K.statusBarIcons] ?: d.statusBarIcons,
            statusBarBackup = p[K.statusBarBackup] ?: d.statusBarBackup,
            lastSeenVersion = p[K.lastSeenVersion] ?: d.lastSeenVersion,
            haptics = p[K.haptics] ?: d.haptics,
            tapAction = p.enum(K.tapAction, d.tapAction),
            swipeToDismiss = p[K.swipeToDismiss] ?: d.swipeToDismiss,
            swipeDownExpands = p[K.swipeDownExpands] ?: d.swipeDownExpands,
            touchDeformation = p[K.touchDeformation] ?: d.touchDeformation,
            autoCollapseSeconds = p[K.autoCollapse] ?: d.autoCollapseSeconds,
            islandTheme = p.enum(K.islandTheme, d.islandTheme),
            appTheme = p.enum(K.appTheme, d.appTheme),
            amoledBlack = p[K.amoled] ?: d.amoledBlack,
            dynamicColor = p[K.dynamicColor] ?: d.dynamicColor,
            tintFromArtwork = p[K.tintFromArtwork] ?: d.tintFromArtwork,
            mediaEnabled = p[K.mediaEnabled] ?: d.mediaEnabled,
            mediaWaveform = p[K.mediaWaveform] ?: d.mediaWaveform,
            mediaCompactProgress = p[K.mediaCompactProgress] ?: d.mediaCompactProgress,
            mediaPausedTimeoutMin = p[K.mediaPausedTimeout] ?: d.mediaPausedTimeoutMin,
            notificationsEnabled = p[K.notificationsEnabled] ?: d.notificationsEnabled,
            notificationDurationMs = p[K.notificationDuration] ?: d.notificationDurationMs,
            notificationPrivacy = p.enum(K.notificationPrivacy, d.notificationPrivacy),
            notificationStyle = p.enum(K.notificationStyle, d.notificationStyle),
            hideSensitiveContent = p[K.hideSensitive] ?: d.hideSensitiveContent,
            callsEnabled = p[K.callsEnabled] ?: d.callsEnabled,
            navigationEnabled = p[K.navigationEnabled] ?: d.navigationEnabled,
            progressEnabled = p[K.progressEnabled] ?: d.progressEnabled,
            chargingEnabled = p[K.chargingEnabled] ?: d.chargingEnabled,
            chargingTheme = p.enum(K.chargingTheme, d.chargingTheme),
            chargingDurationMs = p[K.chargingDuration] ?: d.chargingDurationMs,
            chargingLiveActivity = p[K.chargingLive] ?: d.chargingLiveActivity,
            batteryLowEnabled = p[K.batteryLow] ?: d.batteryLowEnabled,
            batteryLowThreshold = p[K.batteryLowThreshold] ?: d.batteryLowThreshold,
            batteryFullEnabled = p[K.batteryFull] ?: d.batteryFullEnabled,
            batteryShowTemperature = p[K.batteryTemp] ?: d.batteryShowTemperature,
            bluetoothEnabled = p[K.bluetoothEnabled] ?: d.bluetoothEnabled,
            bluetoothAllDevices = p[K.bluetoothAll] ?: d.bluetoothAllDevices,
            timersEnabled = p[K.timersEnabled] ?: d.timersEnabled,
            stopwatchEnabled = p[K.stopwatchEnabled] ?: d.stopwatchEnabled,
            ringerEnabled = p[K.ringer] ?: d.ringerEnabled,
            dndEnabled = p[K.dnd] ?: d.dndEnabled,
            headsetEnabled = p[K.headset] ?: d.headsetEnabled,
            rotationEnabled = p[K.rotation] ?: d.rotationEnabled,
            hotspotEnabled = p[K.hotspot] ?: d.hotspotEnabled,
            screenRecordEnabled = p[K.screenRecord] ?: d.screenRecordEnabled,
            clipboardEnabled = p[K.clipboard] ?: d.clipboardEnabled,
            splitEnabled = p[K.split] ?: d.splitEnabled,
            fullscreenMode = p.enum(K.fullscreenMode, d.fullscreenMode),
            gameMode = p[K.gameMode] ?: d.gameMode,
            appRules = p[K.appRules]?.let(::decodeRules) ?: d.appRules,
            performanceMode = p.enum(K.performance, d.performanceMode),
            burnInProtection = p[K.burnIn] ?: d.burnInProtection,
            debugHud = p[K.debugHud] ?: d.debugHud,
            localApiEnabled = p[K.localApi] ?: d.localApiEnabled,
            monitorEnabled = p[K.monitor] ?: d.monitorEnabled,
            monitorRam = p[K.monitorRam] ?: d.monitorRam,
            monitorNetwork = p[K.monitorNet] ?: d.monitorNetwork,
            monitorCpu = p[K.monitorCpu] ?: d.monitorCpu,
            monitorTemperature = p[K.monitorTemp] ?: d.monitorTemperature,
            monitorFps = p[K.monitorFps] ?: d.monitorFps,
        )
    }

    private fun write(p: MutablePreferences, s: IslandSettings) {
        p[K.enabled] = s.enabled
        p[K.onboardingDone] = s.onboardingDone
        p[K.startOnBoot] = s.startOnBoot
        p[K.autoDetect] = s.autoDetectCutout
        p[K.manualX] = s.manualCameraXPx
        p[K.manualY] = s.manualCameraYPx
        p[K.manualR] = s.manualCameraRadiusPx
        p[K.offsetX] = s.offsetXPx
        p[K.offsetY] = s.offsetYPx
        p[K.cameraPadding] = s.cameraPaddingDp
        p[K.idleStyle] = s.idleStyle.name
        p[K.idleW] = s.idleWidthDp
        p[K.idleH] = s.idleHeightDp
        p[K.compactW] = s.compactWidthDp
        p[K.compactH] = s.compactHeightDp
        p[K.expandedMaxW] = s.expandedMaxWidthDp
        p[K.cornerRoundness] = s.cornerRoundness
        p[K.touchExtension] = s.touchExtensionDp
        p[K.showInLandscape] = s.showInLandscape
        p[K.motionPreset] = s.motionPreset.name
        p[K.animationSpeed] = s.animationSpeed
        p[K.animationIntensity] = s.animationIntensity
        p[K.customResponse] = s.customResponse
        p[K.customDamping] = s.customDamping
        p[K.squashStretch] = s.squashStretch
        p[K.iconFlight] = s.iconFlight
        p[K.arrivalPulse] = s.arrivalPulse
        p[K.lensGlint] = s.lensGlint
        p[K.blurReveal] = s.blurReveal
        p[K.tiltDepth] = s.tiltDepth
        p[K.soundEffects] = s.soundEffects
        p[K.glance] = s.glanceEnabled
        p[K.statusBarCleanup] = s.statusBarCleanup
        p[K.statusBarIcons] = s.statusBarIcons
        p[K.statusBarBackup] = s.statusBarBackup
        p[K.lastSeenVersion] = s.lastSeenVersion
        p[K.haptics] = s.haptics
        p[K.tapAction] = s.tapAction.name
        p[K.swipeToDismiss] = s.swipeToDismiss
        p[K.swipeDownExpands] = s.swipeDownExpands
        p[K.touchDeformation] = s.touchDeformation
        p[K.autoCollapse] = s.autoCollapseSeconds
        p[K.islandTheme] = s.islandTheme.name
        p[K.appTheme] = s.appTheme.name
        p[K.amoled] = s.amoledBlack
        p[K.dynamicColor] = s.dynamicColor
        p[K.tintFromArtwork] = s.tintFromArtwork
        p[K.mediaEnabled] = s.mediaEnabled
        p[K.mediaWaveform] = s.mediaWaveform
        p[K.mediaCompactProgress] = s.mediaCompactProgress
        p[K.mediaPausedTimeout] = s.mediaPausedTimeoutMin
        p[K.notificationsEnabled] = s.notificationsEnabled
        p[K.notificationDuration] = s.notificationDurationMs
        p[K.notificationPrivacy] = s.notificationPrivacy.name
        p[K.notificationStyle] = s.notificationStyle.name
        p[K.hideSensitive] = s.hideSensitiveContent
        p[K.callsEnabled] = s.callsEnabled
        p[K.navigationEnabled] = s.navigationEnabled
        p[K.progressEnabled] = s.progressEnabled
        p[K.chargingEnabled] = s.chargingEnabled
        p[K.chargingTheme] = s.chargingTheme.name
        p[K.chargingDuration] = s.chargingDurationMs
        p[K.chargingLive] = s.chargingLiveActivity
        p[K.batteryLow] = s.batteryLowEnabled
        p[K.batteryLowThreshold] = s.batteryLowThreshold
        p[K.batteryFull] = s.batteryFullEnabled
        p[K.batteryTemp] = s.batteryShowTemperature
        p[K.bluetoothEnabled] = s.bluetoothEnabled
        p[K.bluetoothAll] = s.bluetoothAllDevices
        p[K.timersEnabled] = s.timersEnabled
        p[K.stopwatchEnabled] = s.stopwatchEnabled
        p[K.ringer] = s.ringerEnabled
        p[K.dnd] = s.dndEnabled
        p[K.headset] = s.headsetEnabled
        p[K.rotation] = s.rotationEnabled
        p[K.hotspot] = s.hotspotEnabled
        p[K.screenRecord] = s.screenRecordEnabled
        p[K.clipboard] = s.clipboardEnabled
        p[K.split] = s.splitEnabled
        p[K.fullscreenMode] = s.fullscreenMode.name
        p[K.gameMode] = s.gameMode
        p[K.appRules] = encodeRules(s.appRules)
        p[K.performance] = s.performanceMode.name
        p[K.burnIn] = s.burnInProtection
        p[K.debugHud] = s.debugHud
        p[K.localApi] = s.localApiEnabled
        p[K.monitor] = s.monitorEnabled
        p[K.monitorRam] = s.monitorRam
        p[K.monitorNet] = s.monitorNetwork
        p[K.monitorCpu] = s.monitorCpu
        p[K.monitorTemp] = s.monitorTemperature
        p[K.monitorFps] = s.monitorFps
    }

    companion object {
        private const val TAG = "IslandSettings"

        fun encodeRules(rules: Map<String, AppRule>): String {
            val root = JSONObject()
            rules.forEach { (pkg, r) ->
                root.put(pkg, JSONObject().apply {
                    put("v", r.visibility.name)
                    put("n", r.notifications)
                    put("p", r.priority)
                    put("d", r.durationMs)
                    put("t", r.showText)
                    r.privacy?.let { put("x", it.name) }
                    if (r.accent != 0) put("a", r.accent)
                })
            }
            return root.toString()
        }

        fun decodeRules(json: String): Map<String, AppRule> = try {
            val root = JSONObject(json)
            buildMap {
                root.keys().forEach { pkg ->
                    val o = root.getJSONObject(pkg)
                    put(pkg, AppRule(
                        visibility = AppVisibility.entries.firstOrNull { it.name == o.optString("v") } ?: AppVisibility.NORMAL,
                        notifications = o.optBoolean("n", true),
                        priority = o.optInt("p", 0),
                        durationMs = o.optLong("d", 0),
                        showText = o.optBoolean("t", true),
                        privacy = NotificationPrivacy.entries.firstOrNull { it.name == o.optString("x") },
                        accent = o.optInt("a", 0),
                    ))
                }
            }
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "Ignoring corrupt app rules", e)
            emptyMap()
        }
    }
}
