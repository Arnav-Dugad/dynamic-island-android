package com.arnav.island.storage

import com.arnav.island.animation.MotionPreset
import com.arnav.island.animation.MotionProfile
import com.arnav.island.events.ChargingTheme
import com.arnav.island.events.NotificationPrivacy
import com.arnav.island.island.GeometryConfig
import com.arnav.island.island.IdleStyle

enum class TapAction(val label: String) { OPEN_APP("Open app"), EXPAND("Expand") }

enum class IslandTheme(val label: String, val description: String) {
    CLASSIC_BLACK("Classic Black", "Pure OLED black. Disappears into the camera."),
    GLASS("Glass", "Smoked translucent glass with a hairline rim."),
    MINIMAL("Minimal", "Quieter compact content, no decorative motion."),
    SAMSUNG("Samsung", "One UI accent colours from your wallpaper."),
    RGB("RGB Gaming", "Animated spectrum rim."),
}

enum class AppThemeMode(val label: String) { SYSTEM("System"), DARK("Dark"), LIGHT("Light") }

enum class FullscreenMode(val label: String) {
    SHOW_ALWAYS("Show always"),
    HIDE_IN_FULLSCREEN("Hide in fullscreen"),
    HIDE_IN_GAMES_VIDEOS("Hide in games & videos"),
    PER_APP("Per-app rules only"),
}

enum class PerformanceMode(val label: String, val description: String) {
    ADAPTIVE("Adaptive", "Follows Battery Saver and screen refresh automatically."),
    MAX_SMOOTHNESS("Maximum smoothness", "Requests the highest refresh rate while animating."),
    BALANCED("Balanced", "Decorative motion capped at 60 fps."),
    BATTERY_SAVER("Battery saver", "30 fps decorations, no particles, fewer continuous effects."),
}

enum class AppVisibility(val label: String) {
    NORMAL("Show normally"),
    IMPORTANT_ONLY("Important events only"),
    HIDDEN("Hide completely"),
}

enum class NotificationStyle(val label: String) { BANNER("Banner"), COMPACT("Compact pill") }

/** Per-app behaviour. Defaults mean "no rule". */
data class AppRule(
    val visibility: AppVisibility = AppVisibility.NORMAL,
    val notifications: Boolean = true,
    /** -1 low, 0 normal, 1 high. */
    val priority: Int = 0,
    /** 0 = use the global duration. */
    val durationMs: Long = 0,
    val showText: Boolean = true,
    /** Null = use the global privacy level. */
    val privacy: NotificationPrivacy? = null,
    /** 0 = automatic (from the app icon), otherwise a fixed ARGB accent. */
    val accent: Int = 0,
) {
    val isDefault: Boolean get() = this == AppRule()
}

/**
 * Every user preference. Immutable; changed through [SettingsRepository.update].
 */
data class IslandSettings(
    // Master
    val enabled: Boolean = false,
    val onboardingDone: Boolean = false,
    val startOnBoot: Boolean = true,

    // Geometry & calibration
    val autoDetectCutout: Boolean = true,
    val manualCameraXPx: Float = -1f,
    val manualCameraYPx: Float = -1f,
    val manualCameraRadiusPx: Float = -1f,
    val offsetXPx: Float = 0f,
    val offsetYPx: Float = 0f,
    val cameraPaddingDp: Float = 1.5f,
    val idleStyle: IdleStyle = IdleStyle.CAMERA,
    val idleWidthDp: Float = 0f,
    val idleHeightDp: Float = 0f,
    val compactWidthDp: Float = 0f,
    val compactHeightDp: Float = 0f,
    val expandedMaxWidthDp: Float = 0f,
    val cornerRoundness: Float = 1f,
    val touchExtensionDp: Float = 14f,
    val showInLandscape: Boolean = false,

    // Motion
    val motionPreset: MotionPreset = MotionPreset.NATURAL,
    val animationSpeed: Float = 1f,
    val animationIntensity: Float = 1f,
    val customResponse: Float = 0.44f,
    val customDamping: Float = 0.74f,

    // Motion details
    val squashStretch: Boolean = true,
    val iconFlight: Boolean = true,
    val arrivalPulse: Boolean = true,
    val lensGlint: Boolean = true,
    val blurReveal: Boolean = true,
    val tiltDepth: Boolean = true,
    val soundEffects: Boolean = false,

    // Gestures
    val haptics: Boolean = true,
    val tapAction: TapAction = TapAction.OPEN_APP,
    val swipeToDismiss: Boolean = true,
    val swipeDownExpands: Boolean = true,
    val touchDeformation: Boolean = true,
    val autoCollapseSeconds: Int = 6,

    // Appearance
    val islandTheme: IslandTheme = IslandTheme.CLASSIC_BLACK,
    val appTheme: AppThemeMode = AppThemeMode.SYSTEM,
    val amoledBlack: Boolean = true,
    val dynamicColor: Boolean = true,
    val tintFromArtwork: Boolean = true,

    // Media
    val mediaEnabled: Boolean = true,
    val mediaWaveform: Boolean = true,
    val mediaCompactProgress: Boolean = false,
    val mediaPausedTimeoutMin: Int = 5,

    // Notifications
    val notificationsEnabled: Boolean = true,
    val notificationDurationMs: Long = 4_000,
    val notificationPrivacy: NotificationPrivacy = NotificationPrivacy.FULL,
    val notificationStyle: NotificationStyle = NotificationStyle.BANNER,
    /** Also reduce notifications apps mark private/secret while unlocked. */
    val hideSensitiveContent: Boolean = false,

    // Calls / navigation / progress
    val callsEnabled: Boolean = true,
    val navigationEnabled: Boolean = true,
    val progressEnabled: Boolean = true,

    // Battery & charging
    val chargingEnabled: Boolean = true,
    val chargingTheme: ChargingTheme = ChargingTheme.MINIMAL,
    val chargingDurationMs: Long = 3_000,
    val chargingLiveActivity: Boolean = false,
    val batteryLowEnabled: Boolean = true,
    val batteryLowThreshold: Int = 15,
    val batteryFullEnabled: Boolean = true,
    val batteryShowTemperature: Boolean = true,

    // Bluetooth
    val bluetoothEnabled: Boolean = true,
    val bluetoothAllDevices: Boolean = false,

    // Timers
    val timersEnabled: Boolean = true,
    val stopwatchEnabled: Boolean = true,

    // System events
    val ringerEnabled: Boolean = true,
    val dndEnabled: Boolean = true,
    val headsetEnabled: Boolean = true,
    val rotationEnabled: Boolean = false,
    val hotspotEnabled: Boolean = false,
    val screenRecordEnabled: Boolean = true,
    val clipboardEnabled: Boolean = false,

    // Multi-activity
    val splitEnabled: Boolean = true,
    val glanceEnabled: Boolean = true,

    // Fullscreen & apps
    val fullscreenMode: FullscreenMode = FullscreenMode.HIDE_IN_GAMES_VIDEOS,
    val gameMode: Boolean = true,
    val appRules: Map<String, AppRule> = emptyMap(),

    // Performance
    val performanceMode: PerformanceMode = PerformanceMode.ADAPTIVE,
    val burnInProtection: Boolean = true,

    // Advanced
    val debugHud: Boolean = false,
    val localApiEnabled: Boolean = false,
    val monitorEnabled: Boolean = false,
    val monitorRam: Boolean = true,
    val monitorNetwork: Boolean = true,
    val monitorCpu: Boolean = true,
    val monitorTemperature: Boolean = true,
    val monitorFps: Boolean = false,

    // Experimental status bar cleanup (needs WRITE_SECURE_SETTINGS granted over ADB)
    val statusBarCleanup: Boolean = false,
    val statusBarIcons: String = "alarm_clock,volume,zen,rotate",
    val statusBarBackup: String = BACKUP_NONE,

    /** versionCode whose "What's new" was last shown. */
    val lastSeenVersion: Int = 0,
) {
    companion object {
        /** Marks "no original icon_blacklist value was saved yet". */
        const val BACKUP_NONE = "<none>"
    }

    fun geometryConfig() = GeometryConfig(
        offsetXPx = offsetXPx,
        offsetYPx = offsetYPx,
        cameraPaddingDp = cameraPaddingDp,
        idleStyle = idleStyle,
        idleWidthDp = idleWidthDp,
        idleHeightDp = idleHeightDp,
        compactWidthDp = compactWidthDp,
        compactHeightDp = compactHeightDp,
        expandedMaxWidthDp = expandedMaxWidthDp,
        cornerRoundness = cornerRoundness,
        touchExtensionDp = touchExtensionDp,
    )

    fun motionProfile(systemReduceMotion: Boolean) = MotionProfile.from(
        preset = motionPreset,
        speed = animationSpeed,
        intensity = animationIntensity,
        customResponse = customResponse,
        customDamping = customDamping,
        reduceMotion = systemReduceMotion,
    )

    fun ruleFor(packageName: String?): AppRule = packageName?.let { appRules[it] } ?: AppRule()
}
