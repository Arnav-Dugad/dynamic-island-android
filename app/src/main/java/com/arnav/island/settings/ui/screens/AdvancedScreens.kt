package com.arnav.island.settings.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.BuildConfig
import com.arnav.island.core.IslandCommand
import com.arnav.island.events.api.IslandApiContract
import com.arnav.island.events.api.IslandApiHandler
import com.arnav.island.permissions.Permissions
import com.arnav.island.settings.reply.ReplyRequest
import com.arnav.island.settings.ui.Dest
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.NavRow
import com.arnav.island.settings.ui.components.PermissionRow
import com.arnav.island.settings.ui.components.Reveal
import com.arnav.island.settings.ui.components.SettingRow
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.rememberPermissions
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.settings.ui.theme.LocalIslandColors
import com.arnav.island.storage.IslandSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun AdvancedScreen(s: IslandSettings, ui: Ui) {
    val context = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SettingsPage("Advanced", onBack = ui::back) {
        item {
            Group(title = "Developer") {
                SwitchRow("Debug HUD", s.debugHud, { v -> ui.update { it.copy(debugHud = v) } }, "FPS, frame time, state, queue, window, camera and memory", Icons.Rounded.BugReport, BadgeColors.Graphite)
                NavRow("Developer tools", "Fire test events on the real island", Icons.Rounded.Science, BadgeColors.Violet) { ui.go(Dest.DEVELOPER) }
            }
        }
        item {
            Group(title = "System UI") {
                NavRow("Status bar cleanup", "Experimental · hide icons the island already shows", Icons.Rounded.VisibilityOff, BadgeColors.Graphite) { ui.go(Dest.STATUS_BAR) }
            }
        }
        item {
            Group(title = "Local API", footer = "Lets your own apps show activities with a broadcast (see README). Apps must hold the \"show live activities in Island\" permission, which you grant per app. Off by default.") {
                SwitchRow("Enable local API", s.localApiEnabled, { v -> ui.update { it.copy(localApiEnabled = v) } }, IslandApiContract.ACTION_SHOW.substringAfterLast('.'), Icons.Rounded.Api, BadgeColors.Blue)
                Reveal(s.localApiEnabled) {
                    NavRow("Send a test activity", "Goes through the same parser as external apps", Icons.Rounded.AutoAwesome, BadgeColors.Cyan) {
                        val intent = Intent(IslandApiContract.ACTION_SHOW)
                            .putExtra(IslandApiContract.EXTRA_ID, "demo")
                            .putExtra(IslandApiContract.EXTRA_TITLE, "Build finished")
                            .putExtra(IslandApiContract.EXTRA_SUBTITLE, "Local API test")
                            .putExtra(IslandApiContract.EXTRA_ICON, "CHECK")
                            .putExtra(IslandApiContract.EXTRA_PROGRESS, 1f)
                            .putExtra(IslandApiContract.EXTRA_DURATION_MS, 4000L)
                        IslandApiHandler.handle(context, intent, sender = null, graph = ui.graph)
                    }
                }
            }
        }
        item {
            Group(title = "System monitor", footer = "Experimental and off by default. Runs once per second only while enabled and the screen is on. Android does not let apps read system-wide CPU usage, so the current CPU clock is shown instead.") {
                SwitchRow("Show monitor", s.monitorEnabled, { v -> ui.update { it.copy(monitorEnabled = v) } }, "Adds a background live activity", Icons.Rounded.Speed, BadgeColors.Teal)
                Reveal(s.monitorEnabled) {
                    SwitchRow("Memory", s.monitorRam, { v -> ui.update { it.copy(monitorRam = v) } }, null, Icons.Rounded.Memory, BadgeColors.Teal)
                    SwitchRow("Network speed", s.monitorNetwork, { v -> ui.update { it.copy(monitorNetwork = v) } }, null, Icons.Rounded.NetworkCheck, BadgeColors.Teal)
                    SwitchRow("CPU clock", s.monitorCpu, { v -> ui.update { it.copy(monitorCpu = v) } }, "When the kernel exposes it", Icons.Rounded.Memory, BadgeColors.Teal)
                    SwitchRow("Temperature", s.monitorTemperature, { v -> ui.update { it.copy(monitorTemperature = v) } }, "Battery temperature and thermal status", Icons.Rounded.Thermostat, BadgeColors.Teal)
                    SwitchRow("Island FPS", s.monitorFps, { v -> ui.update { it.copy(monitorFps = v) } }, "Island's own frame rate while animating", Icons.Rounded.Speed, BadgeColors.Teal)
                }
            }
        }
        item {
            Group(title = "Startup") {
                SwitchRow("Start on boot", s.startOnBoot, { v -> ui.update { it.copy(startOnBoot = v) } }, "Bring the island back after a restart", Icons.Rounded.RestartAlt, BadgeColors.Green)
                NavRow("Battery optimisation", "Set Island to Unrestricted so One UI never sleeps it", Icons.Rounded.BatteryChargingFull, BadgeColors.Yellow) { ui.open(Permissions.appDetails(context)) }
            }
        }
        item {
            Group(title = "Reset") {
                NavRow("Reset calibration", "Back to automatic camera detection", Icons.Rounded.RestartAlt, BadgeColors.Cyan) { scope.launch { ui.graph.settings.resetCalibration() } }
                NavRow("Show the introduction again", null, Icons.Rounded.Layers, BadgeColors.Indigo) { ui.update { it.copy(onboardingDone = false) } }
                NavRow("Reset all settings", "Keeps nothing but your timers", Icons.Rounded.DeleteSweep, BadgeColors.Red) { confirmReset = true }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all settings?") },
            text = { Text("Calibration, rules and preferences return to their defaults. The island stays on.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    ui.update { IslandSettings(enabled = it.enabled, onboardingDone = true, lastSeenVersion = it.lastSeenVersion) }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun DeveloperScreen(s: IslandSettings, ui: Ui) {
    val runtime by ui.graph.runtime.collectAsStateWithLifecycle()
    val schedule by ui.graph.events.schedule.collectAsStateWithLifecycle()
    var notificationCount by remember { mutableIntStateOf(1) }
    val tests = ui.graph.testEvents
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    SettingsPage("Developer tools", onBack = ui::back) {
        item {
            Surface(shape = RoundedCornerShape(26.dp), color = LocalIslandColors.current.card, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Live island", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(runtime.stateLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            if (runtime.serviceRunning) "Service running" else "Service stopped",
                            runtime.hiddenReason?.let { "hidden: $it" },
                            "${schedule.live.size} live",
                            "${schedule.queued.size} queued",
                            schedule.toast?.let { "toast: ${it.id}" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Group(title = "Test events", footer = "Test events are labelled as tests and never imitate real system data. \"Test timer\" starts a real one-minute timer.") {
                TestRow("Test music", Icons.Rounded.MusicNote, BadgeColors.Pink) { tests.music(true) }
                TestRow("Test charging (${s.chargingTheme.label})", Icons.Rounded.BatteryChargingFull, BadgeColors.Green) { tests.charging(s.chargingTheme) }
                TestRow("Test notification", Icons.Rounded.Notifications, BadgeColors.Red) { tests.notification(notificationCount++) }
                TestRow("Test Bluetooth", Icons.Rounded.Bluetooth, BadgeColors.Blue) { tests.bluetooth() }
                TestRow("Test timer (real, 1 min)", Icons.Rounded.Timer, BadgeColors.Orange) { ui.graph.timers.start(60_000L, "Test timer") }
                TestRow("Test stopwatch", Icons.Rounded.Timelapse, BadgeColors.Orange) { tests.stopwatch() }
                TestRow("Test call-style UI", Icons.Rounded.Call, BadgeColors.Green) { tests.call() }
                TestRow("Test progress", Icons.Rounded.Download, BadgeColors.Cyan) { tests.progress() }
                TestRow("Test split island", Icons.Rounded.Layers, BadgeColors.Indigo) { tests.split() }
                TestRow("Test ringer", Icons.Rounded.NotificationsOff, BadgeColors.Red) { tests.ringer() }
                TestRow("Test custom activity", Icons.Rounded.AutoAwesome, BadgeColors.Violet) { tests.custom() }
            }
        }
        item {
            Group(title = "New in 1.1", footer = "Glance uses real device state. The reply test opens the real reply sheet; what you type goes nowhere.") {
                TestRow("Group chat + quick reply", Icons.Rounded.Forum, BadgeColors.Green) { tests.groupChat(testReplyAction(context) { ui.graph.events.remove("test:group") }) }
                TestRow("Timer final countdown", Icons.Rounded.HourglassBottom, BadgeColors.Orange) { tests.finalCountdown() }
                TestRow("Charging graph", Icons.AutoMirrored.Rounded.ShowChart, BadgeColors.Green) { tests.chargingGraph(s.chargingTheme) }
                TestRow("Glance", Icons.Rounded.Today, BadgeColors.Cyan) { ui.graph.commands.tryEmit(IslandCommand.Glance) }
                TestRow("Stack peek", Icons.Rounded.ViewAgenda, BadgeColors.Indigo) {
                    tests.music(true)
                    tests.timer(4)
                    scope.launch {
                        delay(600)
                        ui.graph.commands.tryEmit(IslandCommand.Stack)
                    }
                }
                TestRow("Reply sheet", Icons.AutoMirrored.Rounded.Reply, BadgeColors.Blue) { ReplyRequest.launch(context, ReplyRequest.test(context)) }
            }
        }
        item {
            Group(title = "Control") {
                TestRow("Expand", Icons.Rounded.OpenInFull, BadgeColors.Graphite) { ui.graph.commands.tryEmit(IslandCommand.Expand()) }
                TestRow("Collapse", Icons.Rounded.CloseFullscreen, BadgeColors.Graphite) { ui.graph.commands.tryEmit(IslandCommand.Collapse) }
                TestRow("Clear test events", Icons.Rounded.DeleteSweep, BadgeColors.Red) { tests.clear() }
            }
        }
    }
}

@Composable
private fun TestRow(title: String, icon: ImageVector, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    SettingRow(title, null, icon, color, onClick = onClick) {
        FilledTonalButton(onClick = onClick) { Text("Run") }
    }
}

@Composable
fun AboutScreen(ui: Ui) {
    val context = LocalContext.current
    val runtime by ui.graph.runtime.collectAsStateWithLifecycle()
    val p by rememberPermissions()

    SettingsPage("About", onBack = ui::back) {
        item {
            Surface(shape = RoundedCornerShape(26.dp), color = LocalIslandColors.current.card, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(18.dp), color = androidx.compose.ui.graphics.Color.Black, modifier = Modifier.size(56.dp)) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Layers, null, tint = androidx.compose.ui.graphics.Color(0xFF7CF7FF))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Island", style = MaterialTheme.typography.headlineSmall)
                        Text("Version ${BuildConfig.VERSION_NAME}" + if (BuildConfig.LITE) " · Lite edition" else "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { UpdatesGroup(ui) }
        item {
            Group(title = "This device") {
                SettingRow("Model", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                SettingRow("Profile", runtime.deviceProfile)
                SettingRow("Camera detection", runtime.cutoutSource?.label ?: "Starts with the island")
            }
        }
        item {
            Group(title = "Permissions") {
                PermissionRow("Display over other apps", "Required", p.overlay, Icons.Rounded.Layers, BadgeColors.Indigo, onGrant = { ui.open(Permissions.overlaySettings(context)) })
                if (BuildConfig.LITE) {
                    PermissionRow("Notification access", "Island full edition only", false, Icons.Rounded.Notifications, BadgeColors.Pink, onGrant = { ui.go(Dest.SHARE) }, grantLabel = "Get")
                } else {
                    PermissionRow("Notification access", "Media, calls, navigation, notifications", p.notificationAccess, Icons.Rounded.Notifications, BadgeColors.Pink,
                        onGrant = { ui.open(Permissions.notificationAccessSettings(context), Permissions.notificationAccessFallback()) })
                }
                PermissionRow("Notifications", "Timer alerts", p.postNotifications, Icons.Rounded.Notifications, BadgeColors.Red, onGrant = { ui.open(Permissions.appNotificationSettings(context)) })
                PermissionRow("Nearby devices", "Bluetooth events", p.bluetooth, Icons.Rounded.Bluetooth, BadgeColors.Blue, onGrant = { ui.go(Dest.BLUETOOTH) })
                PermissionRow("Usage access", "Per-app rules and Game Mode", p.usageAccess, Icons.Rounded.Speed, BadgeColors.Yellow,
                    onGrant = { ui.open(Permissions.usageAccessSettings(context), Permissions.usageAccessFallback()) })
                PermissionRow("Precise alarms", "Exact timer completion", p.exactAlarms, Icons.Rounded.Timer, BadgeColors.Orange, onGrant = { ui.open(Permissions.exactAlarmSettings(context)) })
                PermissionRow("Unrestricted battery", "Recommended on One UI", p.batteryUnrestricted, Icons.Rounded.BatteryChargingFull, BadgeColors.Green, onGrant = { ui.open(Permissions.appDetails(context)) })
            }
        }
        item {
            Group(title = "Android limitations", footer = "Island never works around these; it uses the best legitimate fallback and says so.") {
                listOf(
                    "The status bar sits above app overlays: status icons can draw over a wide island, and taps right beside the camera can reach the status bar. Use the touch strip below the pill.",
                    "The island cannot appear on the lock screen or Always On Display.",
                    "Apps that block overlays (banking, some video apps) hide it automatically.",
                    "The equalizer is decorative; reading audio would need the microphone.",
                    "Charging speed is only labelled when measured power clearly supports it.",
                    "Call buttons appear only when the dialer's notification provides them.",
                    "Clipboard events fire only while Island is open (Android 10+).",
                ).forEach { SettingRow(it) }
            }
        }
        item {
            Group(title = "Open source") {
                SettingRow("AndroidX, Jetpack Compose, Kotlin", "Apache License 2.0")
                SettingRow("ZXing (QR codes)", "Apache License 2.0")
                SettingRow("Material Icons (some island glyph paths)", "Apache License 2.0")
                SettingRow("Everything else", "Original work in this repository")
            }
        }
    }
}
