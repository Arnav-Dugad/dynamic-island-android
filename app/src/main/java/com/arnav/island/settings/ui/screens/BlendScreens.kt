package com.arnav.island.settings.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.BuildConfig
import com.arnav.island.core.AppGraph
import com.arnav.island.events.test.TestEvents
import com.arnav.island.overlay.StatusBarCleanup
import com.arnav.island.overlay.SystemPopups
import com.arnav.island.permissions.Permissions
import com.arnav.island.settings.ui.Dest
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.InfoCard
import com.arnav.island.settings.ui.components.NavRow
import com.arnav.island.settings.ui.components.SettingRow
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SliderRow
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.preview.IslandPreview
import com.arnav.island.settings.ui.preview.PreviewIsland
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.settings.ui.theme.LocalIslandColors
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Diagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// -------------------------------------------------------------------------------------------------
// Blend with One UI
// -------------------------------------------------------------------------------------------------

/** Samsung's own notification settings (status bar icons, pop-up style). */
private fun notificationSettings() = Intent("android.settings.NOTIFICATION_SETTINGS")

@Composable
fun BlendScreen(s: IslandSettings, ui: Ui) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(StatusBarCleanup.canWrite(context)) }
    var popupsOn by remember { mutableIntStateOf(SystemPopups.current(context)) }
    var scale by remember { mutableStateOf(animationScale(context)) }
    LifecycleResumeEffect(Unit) {
        granted = StatusBarCleanup.canWrite(context)
        popupsOn = SystemPopups.current(context)
        scale = animationScale(context)
        onPauseOrDispose { }
    }

    SettingsPage("Blend with One UI", onBack = ui::back) {
        item {
            InfoCard(
                title = "Make the island part of the phone",
                body = "Island can clear the status bar while a card is open and take over from One UI's pop-ups, so it behaves like it came with the phone. Both use Android's own settings, need a one-time permission from a computer, and put everything back exactly when you turn them off.",
                icon = Icons.Rounded.PhoneAndroid,
                color = BadgeColors.Blue,
            )
        }
        item {
            if (granted) {
                Group {
                    SettingRow("System settings access", "Granted. Island can use the options below.", Icons.Rounded.LockOpen, BadgeColors.Green)
                }
            } else {
                Group(title = "One-time setup", footer = "Connect the phone with USB debugging on, run this once on a computer, then come back. Nothing else is unlocked.") {
                    CommandRow(context, StatusBarCleanup.GRANT_COMMAND)
                }
            }
        }
        item {
            Group(title = "Status bar") {
                SwitchRow("Seamless status bar", s.seamlessStatusBar && granted, { v -> ui.update { it.copy(seamlessStatusBar = v) } },
                    "The clock and system icons step aside while a card is open, then come back. Camera, microphone and location indicators always stay.",
                    Icons.Rounded.Layers, BadgeColors.Indigo, enabled = granted)
                NavRow("Status bar cleanup", "Hide icons the island already shows", Icons.Rounded.VisibilityOff, BadgeColors.Graphite) { ui.go(Dest.STATUS_BAR) }
            }
        }
        item {
            val footer = if (BuildConfig.LITE) {
                "Island Lite can't read notifications, so One UI keeps its pop-ups."
            } else {
                "Right now One UI pop-ups are ${if (popupsOn == 0) "off (Island is showing them)" else "on"}. They come back by themselves whenever the island is hidden, the screen is locked or Island stops. Turn this off before uninstalling Island."
            }
            Group(title = "Notifications", footer = footer) {
                SwitchRow("Replace One UI pop-ups", s.replaceSystemPopups && granted, { v -> ui.update { it.copy(replaceSystemPopups = v) } },
                    "Each notification appears once, in the island, instead of twice",
                    Icons.Rounded.NotificationsPaused, BadgeColors.Pink, enabled = granted && !BuildConfig.LITE)
            }
        }
        item {
            Group(title = "Follow the system", footer = "Haptics already follow Settings → Sounds and vibration → Vibration intensity → Touch interactions, and island sounds follow Touch sounds.") {
                SwitchRow("Match system animation speed", s.followSystemAnimationSpeed, { v -> ui.update { it.copy(followSystemAnimationSpeed = v) } },
                    "Your animation scale is ${String.format(Locale.US, "%.1f", scale)}× (Developer options). The island speeds up or slows down with it.",
                    Icons.Rounded.Speed, BadgeColors.Cyan)
                SwitchRow("Unlock bloom", s.unlockBloom, { v -> ui.update { it.copy(unlockBloom = v) } },
                    "A ring in your wallpaper's accent colour around the camera when you unlock", Icons.Rounded.AutoAwesome, BadgeColors.Violet)
            }
        }
        item {
            Group(title = "One UI settings to change yourself", footer = "Android doesn't let other apps change these, so Island opens the right page.") {
                NavRow("Hide notification icons", "Notifications → Status bar → Show notification icons. Keeps the area beside the island clean.", Icons.Rounded.Notifications, BadgeColors.Red) {
                    ui.open(notificationSettings(), Intent(Settings.ACTION_SETTINGS))
                }
                if (!s.replaceSystemPopups) {
                    NavRow("Brief pop-ups", "Notifications → Notification pop-up style → Brief, so One UI's banner stays small", Icons.Rounded.Notifications, BadgeColors.Orange) {
                        ui.open(notificationSettings(), Intent(Settings.ACTION_SETTINGS))
                    }
                }
                NavRow("Wallpaper", "A darker top edge lets the black island melt into the screen", Icons.Rounded.Wallpaper, BadgeColors.Graphite) {
                    ui.open(Intent(Intent.ACTION_SET_WALLPAPER), Intent(Settings.ACTION_DISPLAY_SETTINGS))
                }
            }
        }
    }
}

private fun animationScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

// -------------------------------------------------------------------------------------------------
// Gallery
// -------------------------------------------------------------------------------------------------

private class Tile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color,
    /** Seconds between replays for moments that end by themselves; 0 plays once. */
    val loopSeconds: Int,
    val play: (TestEvents) -> Unit,
    val expand: String? = null,
)

/** Every kind of activity, each playing live in its own small preview, ready to pin on the island. */
@Composable
fun GalleryScreen(s: IslandSettings, ui: Ui) {
    val context = LocalContext.current
    val tiles = remember {
        listOf(
            Tile("Music", "Artwork, equalizer, controls", Icons.Rounded.MusicNote, BadgeColors.Pink, 0, { it.music(true, rich = false) }),
            Tile("Delivery", "Live route with the courier", Icons.Rounded.Download, BadgeColors.Orange, 0, { it.delivery() }, expand = "test:live"),
            Tile("Meeting", "Countdown to your next event", Icons.Rounded.Event, BadgeColors.Blue, 0, { it.meeting(5) }),
            Tile("Flashlight", "Brightness in the island", Icons.Rounded.FlashlightOn, BadgeColors.Yellow, 0, { it.torch() }),
            Tile("Timer", "Final countdown", Icons.Rounded.HourglassBottom, BadgeColors.Orange, 14, { it.finalCountdown() }),
            Tile("Stopwatch", "Lap dial and ripple", Icons.Rounded.Timelapse, BadgeColors.Orange, 0, { it.laps() }),
            Tile("Charging", "Your charging theme", Icons.Rounded.BatteryChargingFull, BadgeColors.Green, 6, { it.charging(s.chargingTheme) }),
            Tile("Group chat", "Stacked avatars and reply", Icons.Rounded.Forum, BadgeColors.Green, 7, { it.groupChat(testReplyAction(context)) }),
            Tile("Notification", "Banner with icon flight", Icons.Rounded.Notifications, BadgeColors.Red, 6, { it.notification() }),
            Tile("Call", "Incoming call", Icons.Rounded.Call, BadgeColors.Green, 0, { it.call() }),
            Tile("Reply sent", "Dots, then a check", Icons.Rounded.Forum, BadgeColors.Teal, 4, { it.sent() }),
            Tile("Split island", "Two activities at once", Icons.Rounded.Layers, BadgeColors.Indigo, 0, { it.split() }),
        )
    }
    SettingsPage("Gallery", onBack = ui::back, actions = {
        FilledTonalButton(onClick = { ui.graph.testEvents.clear() }, modifier = Modifier.padding(end = 8.dp)) { Text("Clear island") }
    }) {
        item {
            Text(
                "Every kind of activity, live. Pin one to try it on the real island; test activities are clearly marked and never pretend to be real.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        tiles.forEach { tile -> item(key = tile.title) { GalleryTile(tile, s, ui) } }
    }
}

@Composable
private fun GalleryTile(tile: Tile, s: IslandSettings, ui: Ui) {
    var preview by remember { mutableStateOf<PreviewIsland?>(null) }
    LaunchedEffect(preview) {
        val p = preview ?: return@LaunchedEffect
        delay(400)
        do {
            tile.play(p.tests)
            if (tile.loopSeconds <= 0) break
            delay(tile.loopSeconds * 1000L)
        } while (true)
    }
    Surface(shape = RoundedCornerShape(26.dp), color = LocalIslandColors.current.card, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column {
            IslandPreview(ui.graph, s, Modifier.fillMaxWidth().height(150.dp).padding(8.dp), onReady = { preview = it })
            Row(Modifier.padding(start = 16.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(tile.color), contentAlignment = Alignment.Center) {
                    Icon(tile.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tile.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = { tile.play(ui.graph.testEvents) }) {
                    Icon(Icons.Rounded.PushPin, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pin")
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Diagnostics
// -------------------------------------------------------------------------------------------------

@Composable
fun DiagnosticsScreen(s: IslandSettings, ui: Ui) {
    val context = LocalContext.current
    val runtime by ui.graph.runtime.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val report by produceState("", runtime, s, refresh) { value = buildReport(context, ui.graph, s) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }

    SettingsPage("Diagnostics", onBack = ui::back) {
        item {
            InfoCard(
                title = "Stays on this phone",
                body = "This report is built on the phone and never sent anywhere. Copy or share it yourself when reporting a problem. It contains your device model, Island's settings and recent Island warnings, but no notification content.",
                icon = Icons.Rounded.BugReport,
                color = BadgeColors.Graphite,
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Island diagnostics", report))
                    copied = true
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (copied) "Copied" else "Copy")
                }
                FilledTonalButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                    ui.open(Intent.createChooser(send, "Share diagnostics"))
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
                FilledTonalButton(onClick = {
                    Diagnostics.clearCrash(context)
                    refresh++
                }) {
                    Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp))
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF0D0F16), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    report,
                    color = Color(0xFFE6E8F0),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(16.dp),
                )
            }
        }
    }
}

private fun buildReport(context: Context, graph: AppGraph, s: IslandSettings): String {
    val r = graph.runtime.value
    val p = Permissions.snapshot(context)
    return buildString {
        appendLine("Island ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.LITE) " Lite" else ""}")
        appendLine("Device  ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.DISPLAY}")
        appendLine("Profile ${r.deviceProfile}")
        appendLine("Display ${r.screenWidth}×${r.screenHeight} px · density ${r.density} · status bar ${r.statusBarHeight} px")
        appendLine("Camera  ${String.format(Locale.US, "x %.1f y %.1f r %.1f", r.cameraX, r.cameraY, r.cameraRadius)} · ${r.cutoutSource?.label ?: "not measured"} · cutout ${r.cutoutWidth.toInt()}×${r.cutoutHeight.toInt()}")
        appendLine("Island  ${if (r.serviceRunning) "running" else "stopped"} · ${r.stateLabel} · hidden: ${r.hiddenReason ?: "no"} · compact ${r.compactWidthDp.toInt()}×${r.compactHeightDp.toInt()} dp")
        appendLine("Perms   overlay=${p.overlay} notifications=${p.notificationAccess} usage=${p.usageAccess} bluetooth=${p.bluetooth} alarms=${p.exactAlarms} battery=${p.batteryUnrestricted} secure=${StatusBarCleanup.canWrite(context)}")
        appendLine("System  pop-ups=${SystemPopups.current(context)} icon_blacklist=${StatusBarCleanup.current(context) ?: "-"} animation=${animationScale(context)}")
        appendLine("Setup   theme=${s.islandTheme} motion=${s.motionPreset} speed=${s.animationSpeed} intensity=${s.animationIntensity} performance=${s.performanceMode} seamless=${s.seamlessStatusBar} replacePopups=${s.replaceSystemPopups}")
        appendLine()
        appendLine("Recent warnings")
        val recent = Diagnostics.recent()
        if (recent.isEmpty()) appendLine("  none") else recent.takeLast(40).forEach { appendLine("  $it") }
        appendLine()
        appendLine("Last crash")
        append(Diagnostics.lastCrash(context)?.prependIndent("  ") ?: "  none")
    }
}

// -------------------------------------------------------------------------------------------------
// Custom island theme
// -------------------------------------------------------------------------------------------------

private val ThemePalette = listOf(
    0xFF7CF7FF, 0xFF4C9BFF, 0xFF7B4DFF, 0xFFB18CFF, 0xFFFF5C9A, 0xFFFF5147, 0xFFFFA23A, 0xFFFFD54A, 0xFF3DDC84, 0xFFFFFFFF,
).map { it.toInt() }

@Composable
fun CustomThemeEditor(s: IslandSettings, ui: Ui) {
    Group(title = "Your theme", footer = "The preview above shows it live. A rim width of 0 hides the rim.") {
        ColorRow("Rim", s.customRimColor) { c -> ui.update { it.copy(customRimColor = c) } }
        ColorRow("Glow", s.customGlowColor, allowNone = true) { c -> ui.update { it.copy(customGlowColor = c) } }
        SliderRow("Rim width", s.customRimWidthDp, 0f..3f, { v -> ui.update { it.copy(customRimWidthDp = v) } }, { String.format(Locale.US, "%.1f dp", it) }, live = true)
        SliderRow("Highlight", s.customHighlight, 0f..1f, { v -> ui.update { it.copy(customHighlight = v) } }, { "${(it * 100).toInt()}%" }, live = true)
    }
}

@Composable
private fun ColorRow(title: String, selected: Int, allowNone: Boolean = false, onPick: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (allowNone) {
                val none = selected ushr 24 == 0
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .border(if (none) 3.dp else 1.dp, if (none) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { onPick(0) },
                    contentAlignment = Alignment.Center,
                ) { Text("Off", style = MaterialTheme.typography.labelSmall) }
            }
            ThemePalette.forEach { c ->
                val isSelected = c == selected
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .border(if (isSelected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .padding(if (isSelected) 5.dp else 0.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .clickable { onPick(c) },
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Share your setup
// -------------------------------------------------------------------------------------------------

/** QR code and link for the user's own setup, and a field to apply a friend's. */
@Composable
fun SetupShareGroup(ui: Ui) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val code by produceState<String?>(null) { value = ui.graph.settings.exportSetup() }
    var paste by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }

    Group(title = "Share your setup", footer = "Shares sizes, motion, theme and behaviour. Calibration, permissions, app rules and anything personal stay on this phone. The code is a link: scanning it with the camera opens Island on a friend's phone.") {
        val link = code?.let { "island://setup/$it" }
        if (link != null) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {
                    QrCode(link, Modifier.padding(18.dp).size(200.dp).aspectRatio(1f))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    ui.open(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Share setup"))
                }, modifier = Modifier.weight(1f)) { Text("Send link") }
                FilledTonalButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Island setup", link))
                    result = "Link copied"
                }, modifier = Modifier.weight(1f)) { Text("Copy") }
            }
        }
        OutlinedTextField(
            value = paste,
            onValueChange = { paste = it },
            placeholder = { Text("Paste a friend's setup link") },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(enabled = paste.isNotBlank(), onClick = {
                scope.launch {
                    val ok = ui.graph.settings.importSetup(paste)
                    result = if (ok) "Applied. Your calibration was kept." else "That isn't an Island setup link."
                    if (ok) paste = ""
                }
            }) { Text("Apply") }
            Spacer(Modifier.width(12.dp))
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Dev-panel rows for 1.2 features, shared by Developer tools. */
@Composable
fun NewTestRows(ui: Ui) {
    val tests = ui.graph.testEvents
    Group(title = "New in 1.2", footer = "Test activities are labelled as tests. The real flashlight, calendar and deliveries appear by themselves.") {
        TestButtonRow("Delivery on the way", Icons.Rounded.Download, BadgeColors.Orange) { tests.delivery() }
        TestButtonRow("Meeting in 5 minutes", Icons.Rounded.Event, BadgeColors.Blue) { tests.meeting(5) }
        TestButtonRow("Flashlight", Icons.Rounded.FlashlightOn, BadgeColors.Yellow) { tests.torch() }
        TestButtonRow("Stopwatch laps", Icons.Rounded.Timelapse, BadgeColors.Orange) { tests.laps() }
        TestButtonRow("Reply sent", Icons.Rounded.Forum, BadgeColors.Teal) { tests.sent() }
    }
}

@Composable
private fun TestButtonRow(title: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    SettingRow(title, null, icon, color, onClick = onClick) {
        FilledTonalButton(onClick = onClick) { Text("Run") }
    }
}
