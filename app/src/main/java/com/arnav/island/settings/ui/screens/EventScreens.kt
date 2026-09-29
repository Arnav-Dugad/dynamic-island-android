package com.arnav.island.settings.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arnav.island.BuildConfig
import com.arnav.island.events.ChargingTheme
import com.arnav.island.events.EventPriority
import com.arnav.island.events.NotificationPrivacy
import com.arnav.island.permissions.PermissionSnapshot
import com.arnav.island.permissions.Permissions
import com.arnav.island.settings.ui.Dest
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.ChipsRow
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.InfoCard
import com.arnav.island.settings.ui.components.NavRow
import com.arnav.island.settings.ui.components.PermissionRow
import com.arnav.island.settings.ui.components.Reveal
import com.arnav.island.settings.ui.components.SegmentedRow
import com.arnav.island.settings.ui.components.SettingRow
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SliderRow
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.preview.IslandPreview
import com.arnav.island.settings.ui.preview.PreviewIsland
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.settings.ui.theme.LocalIslandColors
import com.arnav.island.storage.IslandSettings
import com.arnav.island.storage.NotificationStyle

@Composable
fun EventsScreen(s: IslandSettings, p: PermissionSnapshot, ui: Ui) {
    SettingsPage("Events", onBack = ui::back) {
        item {
            Group(title = "Live activities") {
                SwitchRow("Media", s.mediaEnabled, { v -> ui.update { it.copy(mediaEnabled = v) } }, "Now playing from any media app", Icons.Rounded.MusicNote, BadgeColors.Pink, enabled = p.notificationAccess)
                SwitchRow("Calls", s.callsEnabled, { v -> ui.update { it.copy(callsEnabled = v) } }, "Incoming and ongoing calls", Icons.Rounded.Call, BadgeColors.Green, enabled = p.notificationAccess)
                SwitchRow("Timers", s.timersEnabled, { v -> ui.update { it.copy(timersEnabled = v) } }, null, Icons.Rounded.Timer, BadgeColors.Orange)
                SwitchRow("Stopwatch", s.stopwatchEnabled, { v -> ui.update { it.copy(stopwatchEnabled = v) } }, null, Icons.Rounded.Timelapse, BadgeColors.Orange)
                SwitchRow("Navigation", s.navigationEnabled, { v -> ui.update { it.copy(navigationEnabled = v) } }, "Turn-by-turn from navigation apps", Icons.Rounded.Navigation, BadgeColors.Blue, enabled = p.notificationAccess)
                SwitchRow("Downloads & progress", s.progressEnabled, { v -> ui.update { it.copy(progressEnabled = v) } }, "Any notification that reports progress", Icons.Rounded.Download, BadgeColors.Cyan, enabled = p.notificationAccess)
                SwitchRow("Screen recording", s.screenRecordEnabled, { v -> ui.update { it.copy(screenRecordEnabled = v) } },
                    if (Build.VERSION.SDK_INT >= 35) "Red recording indicator" else "Requires Android 15", Icons.Rounded.FiberManualRecord, BadgeColors.Red, enabled = Build.VERSION.SDK_INT >= 35)
            }
        }
        item {
            Group(title = "Moments") {
                SwitchRow("Notifications", s.notificationsEnabled, { v -> ui.update { it.copy(notificationsEnabled = v) } }, null, Icons.Rounded.Notifications, BadgeColors.Red, enabled = p.notificationAccess)
                SwitchRow("Charging", s.chargingEnabled, { v -> ui.update { it.copy(chargingEnabled = v) } }, null, Icons.Rounded.BatteryChargingFull, BadgeColors.Green)
                SwitchRow("Battery alerts", s.batteryLowEnabled, { v -> ui.update { it.copy(batteryLowEnabled = v) } }, "Low battery and power saving", Icons.Rounded.BatteryAlert, BadgeColors.Yellow)
                SwitchRow("Bluetooth", s.bluetoothEnabled, { v -> ui.update { it.copy(bluetoothEnabled = v) } }, null, Icons.Rounded.Bluetooth, BadgeColors.Blue)
                SwitchRow("Ringer mode", s.ringerEnabled, { v -> ui.update { it.copy(ringerEnabled = v) } }, "Silent, vibrate and ring", Icons.AutoMirrored.Rounded.VolumeOff, BadgeColors.Red)
                SwitchRow("Do Not Disturb", s.dndEnabled, { v -> ui.update { it.copy(dndEnabled = v) } }, null, Icons.Rounded.DoNotDisturbOn, BadgeColors.Violet)
                SwitchRow("Wired headset", s.headsetEnabled, { v -> ui.update { it.copy(headsetEnabled = v) } }, null, Icons.Rounded.Headphones, BadgeColors.Graphite)
                SwitchRow("Rotation lock", s.rotationEnabled, { v -> ui.update { it.copy(rotationEnabled = v) } }, null, Icons.Rounded.ScreenRotation, BadgeColors.Graphite)
                SwitchRow("Hotspot", s.hotspotEnabled, { v -> ui.update { it.copy(hotspotEnabled = v) } }, "Best effort: only if your Android version broadcasts it", Icons.Rounded.WifiTethering, BadgeColors.Teal)
                SwitchRow("Clipboard", s.clipboardEnabled, { v -> ui.update { it.copy(clipboardEnabled = v) } }, "Off by default. Android 10+ only reports copies while Island is open, and content is never read", Icons.Rounded.ContentPaste, BadgeColors.Graphite)
            }
        }
        item {
            Group(title = "Priority", footer = "Higher-priority events interrupt lower ones and then hand the island back; equal priority favours the newest. Two live activities share the island as a split.") {
                PriorityLadder()
            }
        }
    }
}

@Composable
private fun PriorityLadder() {
    val order = listOf(
        EventPriority.CALL to "Calls",
        EventPriority.CRITICAL_SYSTEM_EVENT to "Critical: low battery, finished timers, recording",
        EventPriority.TIMER to "Timers & stopwatch",
        EventPriority.NAVIGATION to "Navigation",
        EventPriority.CHARGING to "Charging, Bluetooth, system",
        EventPriority.MEDIA to "Media",
        EventPriority.NOTIFICATION to "Notifications",
        EventPriority.BACKGROUND_ACTIVITY to "Background: downloads, monitor",
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        order.forEachIndexed { i, (_, label) ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 1f - i * 0.1f), modifier = Modifier.size(24.dp)) {
                    Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun NotificationAccessCard(p: PermissionSnapshot, ui: Ui) {
    val context = LocalContext.current
    if (BuildConfig.LITE) {
        LiteCard(ui)
        return
    }
    if (p.notificationAccess) return
    InfoCard(
        title = "Notification access needed",
        body = "Android only shares media sessions and notifications with apps the user trusts with notification access. Island reads them on-device and never stores them. Sideloaded on Android 13+? Open App info → ⋮ → Allow restricted settings first.",
        icon = Icons.Rounded.NotificationsActive,
        color = BadgeColors.Pink,
        actionLabel = "Grant access",
        onAction = { ui.open(Permissions.notificationAccessSettings(context), Permissions.notificationAccessFallback()) },
        secondaryLabel = "App info",
        onSecondary = { ui.open(Permissions.appDetails(context)) },
    )
}

@Composable
fun MediaScreen(s: IslandSettings, p: PermissionSnapshot, ui: Ui) {
    SettingsPage("Media", onBack = ui::back) {
        item { NotificationAccessCard(p, ui) }
        item {
            Group(title = "Now playing", footer = "Works with Spotify, YouTube Music, Samsung Music, Apple Music, Poweramp, VLC and any player that publishes an Android media session.") {
                SwitchRow("Show media", s.mediaEnabled, { v -> ui.update { it.copy(mediaEnabled = v) } }, null, Icons.Rounded.PlayCircle, BadgeColors.Pink)
                SwitchRow("Equalizer", s.mediaWaveform, { v -> ui.update { it.copy(mediaWaveform = v) } }, "Decorative motion (Island never listens to audio)", Icons.Rounded.GraphicEq, BadgeColors.Violet)
                SwitchRow("Progress ring in compact view", s.mediaCompactProgress, { v -> ui.update { it.copy(mediaCompactProgress = v) } }, null, Icons.Rounded.Timelapse, BadgeColors.Cyan)
                SwitchRow("Colours from artwork", s.tintFromArtwork, { v -> ui.update { it.copy(tintFromArtwork = v) } }, null, Icons.Rounded.Palette, BadgeColors.Pink)
                SliderRow("Hide when paused after", s.mediaPausedTimeoutMin.toFloat(), 1f..30f, { v -> ui.update { it.copy(mediaPausedTimeoutMin = v.toInt()) } }, { "${it.toInt()} min" }, steps = 28)
            }
        }
        item {
            Group(title = "Gestures") {
                Column(Modifier.padding(16.dp)) {
                    Text("Tap: open the player  ·  Long-press: controls  ·  Swipe: hide until the next track", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Drag the progress bar in the expanded card to seek.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun NotificationsScreen(s: IslandSettings, p: PermissionSnapshot, ui: Ui) {
    SettingsPage("Notifications", onBack = ui::back) {
        item { NotificationAccessCard(p, ui) }
        item {
            Group(title = "Banners") {
                SwitchRow("Show notifications", s.notificationsEnabled, { v -> ui.update { it.copy(notificationsEnabled = v) } }, "Only notifications Android would alert for (respects Do Not Disturb)", Icons.Rounded.Notifications, BadgeColors.Red)
                SegmentedRow(NotificationStyle.entries, s.notificationStyle, { it.label }, { v -> ui.update { it.copy(notificationStyle = v) } }, title = "Style")
                SliderRow("Duration", s.notificationDurationMs / 1000f, 2f..10f, { v -> ui.update { it.copy(notificationDurationMs = (v * 1000).toLong()) } }, { String.format(java.util.Locale.US, "%.1f s", it) })
            }
        }
        item {
            Group(title = "Privacy", footer = "Notifications marked private are always reduced to the app name while locked. On Android 15+, the system itself removes one-time codes before any notification listener sees them.") {
                SegmentedRow(NotificationPrivacy.entries, s.notificationPrivacy, { it.label.substringBefore(" only") }, { v -> ui.update { it.copy(notificationPrivacy = v) } }, title = "Show", subtitle = s.notificationPrivacy.label)
                SwitchRow("Hide private content", s.hideSensitiveContent, { v -> ui.update { it.copy(hideSensitiveContent = v) } },
                    "Notifications that apps or your lock screen settings mark private show the app name only, even when unlocked",
                    Icons.Rounded.VisibilityOff, BadgeColors.Graphite)
            }
        }
        item {
            Group {
                NavRow("Per-app settings", "Enable, priority, duration, text, privacy", Icons.Rounded.Apps, BadgeColors.Yellow) { ui.go(Dest.APPS) }
            }
        }
    }
}

@Composable
fun ChargingScreen(s: IslandSettings, ui: Ui) {
    var preview by remember { mutableStateOf<PreviewIsland?>(null) }
    SettingsPage("Charging", onBack = ui::back) {
        item {
            IslandPreview(ui.graph, s, Modifier.fillMaxWidth().height(170.dp).padding(vertical = 4.dp), onReady = {
                preview = it
                it.tests.charging(s.chargingTheme)
            })
        }
        item {
            Group(title = "Animation") {
                ChipsRow(ChargingTheme.entries, s.chargingTheme, { it.label }, { v ->
                    ui.update { it.copy(chargingTheme = v) }
                    preview?.tests?.charging(v)
                })
                SliderRow("Show for", s.chargingDurationMs / 1000f, 1.5f..8f, { v -> ui.update { it.copy(chargingDurationMs = (v * 1000).toLong()) } }, { String.format(java.util.Locale.US, "%.1f s", it) })
            }
        }
        item {
            Group(title = "Behaviour", footer = "\"Fast charging\" appears only when Android's own current and voltage readings sustain a high charging power. Time to full appears only when Android estimates it.") {
                SwitchRow("Charging animation", s.chargingEnabled, { v -> ui.update { it.copy(chargingEnabled = v) } }, null, Icons.Rounded.BatteryChargingFull, BadgeColors.Green)
                SwitchRow("Keep as live activity", s.chargingLiveActivity, { v -> ui.update { it.copy(chargingLiveActivity = v) } }, "Show the percentage in the island while plugged in", Icons.Rounded.BatteryFull, BadgeColors.Teal)
                SwitchRow("Temperature in details", s.batteryShowTemperature, { v -> ui.update { it.copy(batteryShowTemperature = v) } }, null, Icons.Rounded.Thermostat, BadgeColors.Orange)
            }
        }
    }
}

@Composable
fun BatteryScreen(s: IslandSettings, ui: Ui) {
    SettingsPage("Battery", onBack = ui::back) {
        item {
            Group(title = "Alerts") {
                SwitchRow("Low battery", s.batteryLowEnabled, { v -> ui.update { it.copy(batteryLowEnabled = v) } }, "Also announces Power saving on/off", Icons.Rounded.BatteryAlert, BadgeColors.Red)
                Reveal(s.batteryLowEnabled) {
                    SliderRow("Threshold", s.batteryLowThreshold.toFloat(), 5f..30f, { v -> ui.update { it.copy(batteryLowThreshold = v.toInt()) } }, { "${it.toInt()}%" }, steps = 24)
                }
                SwitchRow("Fully charged", s.batteryFullEnabled, { v -> ui.update { it.copy(batteryFullEnabled = v) } }, null, Icons.Rounded.BatteryFull, BadgeColors.Green)
            }
        }
        item {
            Group {
                NavRow("Charging animation", s.chargingTheme.label, Icons.Rounded.BatteryChargingFull, BadgeColors.Green) { ui.go(Dest.CHARGING) }
            }
        }
    }
}

@Composable
fun BluetoothScreen(s: IslandSettings, p: PermissionSnapshot, ui: Ui) {
    var granted by remember(p.bluetooth) { mutableStateOf(p.bluetooth) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) ui.update { it.copy(bluetoothEnabled = true) }
    }
    SettingsPage("Bluetooth", onBack = ui::back) {
        item {
            Group(title = "Permission", footer = "Android calls this permission \"Nearby devices\". Island uses it only to read the name, type and reported battery of devices as they connect.") {
                PermissionRow("Nearby devices", if (granted) "Granted" else "Needed for Bluetooth events", granted, Icons.AutoMirrored.Rounded.BluetoothSearching, BadgeColors.Blue,
                    onGrant = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) })
            }
        }
        item {
            Group(title = "Events") {
                SwitchRow("Connection animations", s.bluetoothEnabled, { v -> ui.update { it.copy(bluetoothEnabled = v) } }, "Earbuds, headphones, speakers, watches and cars", Icons.Rounded.Bluetooth, BadgeColors.Blue, enabled = granted)
                SwitchRow("All device types", s.bluetoothAllDevices, { v -> ui.update { it.copy(bluetoothAllDevices = v) } }, "Also keyboards, phones and computers", Icons.Rounded.Apps, BadgeColors.Graphite, enabled = granted && s.bluetoothEnabled)
            }
        }
        item {
            Text(
                "Battery levels are shown only when your device reports them to Android.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
    }
}

@Composable
fun PrivacyScreen(s: IslandSettings, ui: Ui) {
    SettingsPage("Privacy", onBack = ui::back) {
        item {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp), color = LocalIslandColors.current.card, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Everything stays on this device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        "No internet permission: Island cannot send anything anywhere.",
                        "No accounts, analytics, ads or telemetry.",
                        "Notification content is held in memory only while it is on screen.",
                        "No music, notification or usage history is stored.",
                        "Settings live in a local DataStore file, excluded from backups.",
                    ).forEach { line ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text("•  ", color = MaterialTheme.colorScheme.primary)
                            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            Group(title = "Notification content") {
                SegmentedRow(NotificationPrivacy.entries, s.notificationPrivacy, { it.label.substringBefore(" only") }, { v -> ui.update { it.copy(notificationPrivacy = v) } }, title = "Default detail level")
                SwitchRow("Hide private content", s.hideSensitiveContent, { v -> ui.update { it.copy(hideSensitiveContent = v) } }, "Private notifications show the app name only", Icons.Rounded.VisibilityOff, BadgeColors.Graphite)
                SwitchRow("Clipboard events", s.clipboardEnabled, { v -> ui.update { it.copy(clipboardEnabled = v) } }, "Never reads clipboard content", Icons.Rounded.ContentPaste, BadgeColors.Graphite)
            }
        }
        item {
            Group(title = "Lock screen") {
                SettingRow("Island hides while locked", "It never draws over AOD or the lock screen", Icons.Rounded.PhonelinkLock, BadgeColors.Graphite)
                NavRow("Per-app privacy", "Full, app name only or icon only", Icons.Rounded.Lock, BadgeColors.Yellow) { ui.go(Dest.APPS) }
                NavRow("Permissions", "What Island is allowed to do", Icons.Rounded.Shield, BadgeColors.Green) { ui.go(Dest.ABOUT) }
            }
        }
    }
}
