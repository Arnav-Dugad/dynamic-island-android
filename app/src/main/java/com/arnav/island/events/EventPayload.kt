package com.arnav.island.events

/**
 * Strongly typed data for each renderer. Only real values reported by the platform (or by the
 * user's own timers) ever go in here: optional fields stay null when Android does not expose them.
 */
sealed interface EventPayload

data class MediaPayload(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** Position reported by the player at [positionUpdatedAt] (elapsedRealtime). */
    val positionMs: Long,
    val positionUpdatedAt: Long,
    val playbackSpeed: Float,
    val isPlaying: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
    val canSeek: Boolean,
    val accent: Int,
    val seek: SeekAction? = null,
    /** Next item in the player's queue, when the player publishes one. */
    val upNextTitle: String? = null,
    val upNextSubtitle: String? = null,
    /** 0..1 media volume (stream volume, or the remote volume for cast sessions). */
    val volume: Float? = null,
    val setVolume: SeekAction? = null,
    /** Where audio is playing, e.g. "Galaxy Buds2 Pro" or "Phone speaker". */
    val outputName: String? = null,
    val outputKind: OutputKind = OutputKind.SPEAKER,
) : EventPayload {
    /** Extrapolates the playback position to [nowElapsed] like the system media controls do. */
    fun positionAt(nowElapsed: Long): Long {
        if (!isPlaying || positionUpdatedAt <= 0) return positionMs.coerceAtLeast(0)
        val advanced = positionMs + ((nowElapsed - positionUpdatedAt) * playbackSpeed).toLong()
        return if (durationMs > 0) advanced.coerceIn(0, durationMs) else advanced.coerceAtLeast(0)
    }
}

enum class OutputKind { SPEAKER, HEADPHONES, BLUETOOTH, CAST, OTHER }

enum class PlugType { NONE, AC, USB, WIRELESS, DOCK, UNKNOWN }

enum class ChargingSpeed { NORMAL, FAST, SUPER_FAST }

enum class ChargingTheme(val label: String) {
    MINIMAL("Minimal"), ENERGY("Energy"), LIQUID("Liquid"), PULSE("Pulse"), SAMSUNG("One UI"),
}

data class ChargingPayload(
    val level: Int,
    val plugType: PlugType,
    val isCharging: Boolean,
    val isFull: Boolean,
    /** Only set when the device explicitly reports it (see ChargingSpeedDetector). */
    val speed: ChargingSpeed?,
    val temperatureC: Float?,
    val voltageMv: Int?,
    /** From BatteryManager.computeChargeTimeRemaining(); null when unknown. */
    val timeToFullMs: Long?,
    val theme: ChargingTheme,
    /** Level shown at the start of the entrance so the counter animates to the real value. */
    val countFrom: Int,
    /** Charging power measured from public current x voltage readings, oldest first (W). */
    val powerHistory: List<Float> = emptyList(),
) : EventPayload {
    val watts: Float? get() = powerHistory.lastOrNull()
}

enum class BatteryAlert { LOW, FULL, SAVER_ON, SAVER_OFF }

data class BatteryPayload(val level: Int, val alert: BatteryAlert, val isCharging: Boolean) : EventPayload

enum class BluetoothDeviceKind { HEADPHONES, EARBUDS, WATCH, SPEAKER, CAR, PHONE, COMPUTER, INPUT, GENERIC }

data class BluetoothPayload(
    val deviceName: String,
    val kind: BluetoothDeviceKind,
    val connected: Boolean,
    /** 0..100 when the device reports it, else null. */
    val batteryLevel: Int?,
) : EventPayload

data class TimerPayload(
    val timerId: Long,
    val label: String,
    val totalMs: Long,
    /** Wall-clock end time while running. */
    val endsAt: Long,
    /** Remaining time captured when paused. */
    val remainingWhenPausedMs: Long,
    val isPaused: Boolean,
    val isRinging: Boolean,
    val ringingSince: Long,
) : EventPayload {
    fun remainingAt(now: Long): Long = when {
        isRinging -> 0
        isPaused -> remainingWhenPausedMs
        else -> (endsAt - now).coerceAtLeast(0)
    }

    fun fractionRemaining(now: Long): Float =
        if (totalMs <= 0) 0f else (remainingAt(now).toFloat() / totalMs).coerceIn(0f, 1f)
}

data class StopwatchPayload(
    /** elapsedRealtime-independent: wall clock when the current run segment started. */
    val runningSince: Long,
    val accumulatedMs: Long,
    val isRunning: Boolean,
    val laps: List<Long>,
) : EventPayload {
    fun elapsedAt(now: Long): Long = accumulatedMs + if (isRunning) (now - runningSince).coerceAtLeast(0) else 0
}

enum class CallState { INCOMING, ONGOING, OUTGOING }

data class CallPayload(
    val callerName: String,
    val detail: String,
    val appLabel: String,
    val state: CallState,
    /** Wall-clock start of the call timer when the dialer publishes one; null otherwise. */
    val chronometerBase: Long?,
    val isVideo: Boolean,
    val hasAvatar: Boolean,
) : EventPayload

data class NavigationPayload(
    val appLabel: String,
    val distance: String,
    val instruction: String,
    val eta: String,
    val hasManeuverImage: Boolean,
) : EventPayload

data class ProgressPayload(
    val appLabel: String,
    val packageName: String,
    val title: String,
    val progress: Float,
    val indeterminate: Boolean,
    val done: Boolean,
) : EventPayload

enum class NotificationPrivacy(val label: String) { FULL("Full"), APP_NAME("App name only"), ICON_ONLY("Icon only") }

data class NotificationPayload(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val extraLines: List<String>,
    val privacy: NotificationPrivacy,
    val showText: Boolean,
    val postedAt: Long,
    val hasLargeIcon: Boolean,
    val isConversation: Boolean,
    /** Distinct recent senders in a group conversation (for stacked avatars). */
    val senderAvatars: List<ImageRef> = emptyList(),
    val senderCount: Int = 0,
    /** Label of the app's own reply action when it offers free-form replies. */
    val replyLabel: String? = null,
) : EventPayload

enum class SystemKind {
    RINGER_SILENT, RINGER_VIBRATE, RINGER_NORMAL, DND_ON, DND_OFF, HEADSET_IN, HEADSET_OUT,
    ROTATION_LOCKED, ROTATION_AUTO, HOTSPOT_ON, HOTSPOT_OFF, CLIPBOARD, SCREEN_RECORDING,
}

data class SystemPayload(val kind: SystemKind, val label: String, val detail: String = "") : EventPayload

data class MonitorPayload(
    val ramUsedFraction: Float?,
    val ramUsedBytes: Long?,
    val ramTotalBytes: Long?,
    val netDownBps: Long?,
    val netUpBps: Long?,
    val cpuFreqMhz: Int?,
    val cpuMaxFreqMhz: Int?,
    val batteryTempC: Float?,
    val thermalStatus: String?,
    val islandFps: Float?,
) : EventPayload

data class CustomPayload(val appLabel: String, val packageName: String?) : EventPayload

/** "Glance" card for a long-press on the idle island. Every value is read from Android. */
data class GlancePayload(
    val batteryLevel: Int?,
    val isCharging: Boolean,
    /** Wall-clock time of the next alarm (AlarmManager.getNextAlarmClock), if any. */
    val nextAlarmAt: Long?,
    val runningTimers: Int,
    val nowPlaying: String?,
) : EventPayload

/** Every running activity, shown as a stack of cards. */
data class StackPayload(val items: List<IslandEvent>) : EventPayload
