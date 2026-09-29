package com.arnav.island.settings.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop169
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.BuildConfig
import com.arnav.island.overlay.IslandOverlayService
import com.arnav.island.permissions.PermissionSnapshot
import com.arnav.island.permissions.Permissions
import com.arnav.island.settings.ui.Dest
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.InfoCard
import com.arnav.island.settings.ui.components.NavRow
import com.arnav.island.settings.ui.components.OneUiSwitch
import com.arnav.island.settings.ui.preview.IslandPreview
import com.arnav.island.settings.ui.preview.PreviewIsland
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.settings.ui.theme.LocalIslandColors
import com.arnav.island.settings.ui.theme.TitleHero
import com.arnav.island.storage.IslandSettings

@Composable
fun HomeScreen(settings: IslandSettings, permissions: PermissionSnapshot, ui: Ui) {
    val context = LocalContext.current
    val running by IslandOverlayService.running.collectAsStateWithLifecycle()
    val runtime by ui.graph.runtime.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<PreviewIsland?>(null) }
    var demo by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 40.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 6.dp)) {
                Text("Island", style = TitleHero)
                Text(
                    when {
                        !permissions.overlay -> "Needs permission to draw over apps"
                        !settings.enabled -> "Paused"
                        running && runtime.hiddenReason != null -> "Running · hidden (${runtime.hiddenReason})"
                        running -> "Running · ${runtime.deviceProfile}"
                        else -> "Starting…"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(18.dp))
        }

        item {
            IslandPreview(
                graph = ui.graph,
                settings = settings,
                demo = demo,
                onReady = { preview = it },
                modifier = Modifier.fillMaxWidth().height(250.dp),
            )
            Spacer(Modifier.height(12.dp))
            DemoChips(onPick = { pick ->
                demo = false
                val p = preview ?: return@DemoChips
                when (pick) {
                    "Music" -> p.tests.music(true, rich = false)
                    "Charging" -> p.tests.charging(settings.chargingTheme)
                    "Timer" -> p.tests.timer(5)
                    "Notification" -> p.tests.notification()
                    "Call" -> p.tests.call()
                    "Split" -> p.tests.split()
                    "Bluetooth" -> p.tests.bluetooth()
                    "Download" -> p.tests.progress()
                    "Silent" -> p.tests.ringer()
                    "Clear" -> p.tests.clear()
                }
            })
            Text(
                "Tap, long-press or swipe the preview island. It is the same renderer as the real one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        item { MasterSwitch(settings.enabled, permissions.overlay) { ui.setEnabled(it) } }

        if (!permissions.overlay) {
            item {
                InfoCard(
                    title = "Allow Island to appear on top",
                    body = "Android requires the \"Display over other apps\" permission for any overlay. Island uses it only to draw the island around your camera.",
                    icon = Icons.Rounded.Layers,
                    color = BadgeColors.Indigo,
                    actionLabel = "Open settings",
                    onAction = { ui.open(Permissions.overlaySettings(context)) },
                )
            }
        }
        if (BuildConfig.LITE) {
            item { LiteCard(ui) }
        } else if (!permissions.notificationAccess) {
            item {
                InfoCard(
                    title = "Unlock media, calls and notifications",
                    body = "Notification access lets Island follow music players, calls, navigation, downloads and alerts. On Android 13+ sideloaded apps may first need App info → ⋮ → Allow restricted settings.",
                    icon = Icons.Rounded.NotificationsOff,
                    color = BadgeColors.Pink,
                    actionLabel = "Grant access",
                    onAction = { ui.open(Permissions.notificationAccessSettings(context), Permissions.notificationAccessFallback()) },
                    secondaryLabel = "App info",
                    onSecondary = { ui.open(Permissions.appDetails(context)) },
                )
            }
        }

        item {
            Group(title = "Island") {
                NavRow("Blend with One UI", "Seamless status bar, no double pop-ups", Icons.Rounded.PhoneAndroid, BadgeColors.Blue) { ui.go(Dest.BLEND) }
                NavRow("Shape & motion", "Size, corners, springs, split island", Icons.Rounded.Crop169, BadgeColors.Graphite) { ui.go(Dest.ISLAND) }
                NavRow("Camera calibration", "Auto-detect or fine-tune to the pixel", Icons.Rounded.CenterFocusStrong, BadgeColors.Cyan) { ui.go(Dest.CALIBRATION) }
                NavRow("Gestures", "Tap, long-press, swipe, haptics", Icons.Rounded.TouchApp, BadgeColors.Blue) { ui.go(Dest.GESTURES) }
                NavRow("Appearance", settings.islandTheme.label, Icons.Rounded.Palette, BadgeColors.Violet) { ui.go(Dest.APPEARANCE) }
            }
        }
        item {
            Group(title = "Activities") {
                NavRow("Events", "What the island reacts to", Icons.Rounded.AutoAwesome, BadgeColors.Indigo) { ui.go(Dest.EVENTS) }
                NavRow("Media", "Now playing, controls, artwork", Icons.Rounded.MusicNote, BadgeColors.Pink) { ui.go(Dest.MEDIA) }
                NavRow("Notifications", "Banners, privacy, duration", Icons.Rounded.Notifications, BadgeColors.Red) { ui.go(Dest.NOTIFICATIONS) }
                NavRow("Charging", settings.chargingTheme.label + " animation", Icons.Rounded.BatteryChargingFull, BadgeColors.Green) { ui.go(Dest.CHARGING) }
                NavRow("Battery", "Low, full and power saving alerts", Icons.Rounded.BatteryFull, BadgeColors.Teal) { ui.go(Dest.BATTERY) }
                NavRow("Bluetooth", "Earbuds, headphones, watch, car", Icons.Rounded.Bluetooth, BadgeColors.Blue) { ui.go(Dest.BLUETOOTH) }
                NavRow("Timers & stopwatch", "Local timers that live in the island", Icons.Rounded.Timer, BadgeColors.Orange) { ui.go(Dest.TIMERS) }
                NavRow("Apps", "Per-app rules, motion, fullscreen, Game Mode", Icons.Rounded.Apps, BadgeColors.Yellow) { ui.go(Dest.APPS) }
                NavRow("Gallery", "Every activity, live, ready to pin", Icons.Rounded.GridView, BadgeColors.Violet) { ui.go(Dest.GALLERY) }
            }
        }
        item {
            Group(title = "System") {
                NavRow("Performance", settings.performanceMode.label, Icons.Rounded.Speed, BadgeColors.Cyan) { ui.go(Dest.PERFORMANCE) }
                NavRow("Privacy", "Everything stays on this device", Icons.Rounded.Shield, BadgeColors.Green) { ui.go(Dest.PRIVACY) }
                NavRow("Advanced", "Debug HUD, local API, monitor", Icons.Rounded.Tune, BadgeColors.Graphite) { ui.go(Dest.ADVANCED) }
                NavRow("Developer tools", "Fire test events on the real island", Icons.Rounded.Science, BadgeColors.Violet) { ui.go(Dest.DEVELOPER) }
                NavRow("What's new", "Version ${BuildConfig.VERSION_NAME.substringBefore('-')} · play every new feature", Icons.Rounded.NewReleases, BadgeColors.Pink) { ui.go(Dest.WHATS_NEW) }
                NavRow("Share with friends", "QR code and install guide", Icons.Rounded.QrCode2, BadgeColors.Violet) { ui.go(Dest.SHARE) }
                NavRow("About", "Version, updates, device, limitations", Icons.Rounded.Info, BadgeColors.Graphite) { ui.go(Dest.ABOUT) }
            }
        }
        item { Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
    }
}

@Composable
private fun MasterSwitch(enabled: Boolean, canEnable: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalIslandColors.current
    val container by animateColorAsState(
        if (enabled) MaterialTheme.colorScheme.primaryContainer else colors.card,
        label = "master",
    )
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use Island", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !canEnable -> "Grant the overlay permission to start"
                        enabled -> "The island is live around your camera"
                        else -> "Turn on to show the island"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            OneUiSwitch(checked = enabled, onCheckedChange = null)
        }
    }
}

private data class DemoChip(val label: String, val icon: ImageVector)

@Composable
private fun DemoChips(onPick: (String) -> Unit) {
    val chips = listOf(
        DemoChip("Music", Icons.Rounded.MusicNote),
        DemoChip("Charging", Icons.Rounded.BatteryChargingFull),
        DemoChip("Timer", Icons.Rounded.Timer),
        DemoChip("Notification", Icons.Rounded.Notifications),
        DemoChip("Call", Icons.Rounded.Call),
        DemoChip("Split", Icons.Rounded.Layers),
        DemoChip("Bluetooth", Icons.Rounded.Bluetooth),
        DemoChip("Download", Icons.Rounded.Download),
        DemoChip("Silent", Icons.Rounded.NotificationsOff),
        DemoChip("Clear", Icons.Rounded.Close),
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            AssistChip(
                onClick = { onPick(chip.label) },
                label = { Text(chip.label) },
                leadingIcon = { Icon(chip.icon, null, modifier = Modifier.size(18.dp)) },
                shape = CircleShape,
                colors = AssistChipDefaults.assistChipColors(containerColor = LocalIslandColors.current.card),
                border = null,
            )
        }
    }
}
