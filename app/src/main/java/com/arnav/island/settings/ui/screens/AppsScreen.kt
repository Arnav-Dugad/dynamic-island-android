package com.arnav.island.settings.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arnav.island.events.NotificationPrivacy
import com.arnav.island.permissions.PermissionSnapshot
import com.arnav.island.permissions.Permissions
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.ChipsRow
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.InfoCard
import com.arnav.island.settings.ui.components.PermissionRow
import com.arnav.island.settings.ui.components.RadioRow
import com.arnav.island.settings.ui.components.SegmentedRow
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SliderRow
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.settings.ui.theme.LocalIslandColors
import com.arnav.island.storage.AppRule
import com.arnav.island.storage.AppVisibility
import com.arnav.island.storage.FullscreenMode
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.InstalledApp

@Composable
fun AppsScreen(s: IslandSettings, p: PermissionSnapshot, ui: Ui) {
    val context = LocalContext.current
    val apps by produceState(initialValue = emptyList<InstalledApp>()) { value = ui.graph.apps.launchableApps() }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<InstalledApp?>(null) }
    val filtered = remember(apps, query, s.appRules) {
        val q = query.trim().lowercase()
        apps.filter { q.isEmpty() || q in it.label.lowercase() || q in it.packageName }
            .sortedByDescending { s.appRules.containsKey(it.packageName) }
    }

    SettingsPage("Apps", onBack = ui::back) {
        item {
            Group(title = "Fullscreen apps", footer = "Fullscreen is detected from whether the status bar is showing. Game & video categories need Usage Access; without it, any fullscreen app counts.") {
                FullscreenMode.entries.forEach { mode ->
                    RadioRow(mode.label, s.fullscreenMode == mode, { ui.update { it.copy(fullscreenMode = mode) } })
                }
                SwitchRow("Game Mode", s.gameMode, { v -> ui.update { it.copy(gameMode = v) } }, "Hide the island in games; events keep running and it returns when you leave", Icons.Rounded.SportsEsports, BadgeColors.Violet)
            }
        }
        item {
            if (p.usageAccess) {
                Group {
                    PermissionRow("Usage access", "Lets per-app rules and Game Mode know which app is open", true, Icons.Rounded.QueryStats, BadgeColors.Yellow, onGrant = {})
                }
            } else {
                InfoCard(
                    title = "Usage access for per-app rules",
                    body = "To hide the island in specific apps or games, Android must tell Island which app is in front. Only the current app's package name is read, it is never stored.",
                    icon = Icons.Rounded.QueryStats,
                    color = BadgeColors.Yellow,
                    actionLabel = "Grant access",
                    onAction = { ui.open(Permissions.usageAccessSettings(context), Permissions.usageAccessFallback()) },
                )
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = LocalIslandColors.current.card,
                    focusedContainerColor = LocalIslandColors.current.card,
                    unfocusedBorderColor = LocalIslandColors.current.card,
                ),
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
        }
        items(filtered, key = { it.packageName }) { app ->
            AppRow(app, s.ruleFor(app.packageName), ui) { editing = app }
        }
        if (apps.isEmpty()) {
            item { Text("Loading apps…", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    editing?.let { app ->
        AppRuleSheet(app, s.ruleFor(app.packageName), ui, onDismiss = { editing = null })
    }
}

@Composable
private fun AppIcon(packageName: String, ui: Ui, size: Int = 40) {
    val px = with(LocalDensity.current) { size.dp.roundToPx() }
    val bitmap by produceState(initialValue = ui.graph.apps.cachedIcon(packageName, px), packageName) {
        if (value == null) value = ui.graph.apps.icon(packageName, px)
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.28f).dp)))
    } else {
        Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.28f).dp)).background(LocalIslandColors.current.cardPressed))
    }
}

@Composable
private fun AppRow(app: InstalledApp, rule: AppRule, ui: Ui, onClick: () -> Unit) {
    val summary = buildList {
        if (rule.visibility != AppVisibility.NORMAL) add(rule.visibility.label)
        if (!rule.notifications) add("Notifications off")
        if (rule.priority == 1) add("High priority")
        if (rule.priority == -1) add("Low priority")
        if (rule.accent != 0) add("Custom colour")
        rule.privacy?.let { add(it.label) }
        if (app.isGame) add("Game")
        if (app.isVideo) add("Video")
    }.joinToString(" · ").ifEmpty { "Default" }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(LocalIslandColors.current.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, ui)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = if (rule.isDefault) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRuleSheet(app: InstalledApp, rule: AppRule, ui: Ui, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun set(r: AppRule) = ui.graph.settings.setRule(app.packageName, r)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.packageName, ui, 48)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { set(AppRule()) }) { Text("Reset") }
            }
            Spacer(Modifier.height(8.dp))
            SegmentedRow(AppVisibility.entries, rule.visibility, { it.label.substringBefore(" ") }, { set(rule.copy(visibility = it)) },
                title = "While this app is open", subtitle = rule.visibility.label)
            SwitchRow("Notifications in the island", rule.notifications, { set(rule.copy(notifications = it)) })
            SegmentedRow(listOf(-1, 0, 1), rule.priority, { when (it) { -1 -> "Low"; 1 -> "High"; else -> "Normal" } }, { set(rule.copy(priority = it)) }, title = "Priority")
            SliderRow("Duration", rule.durationMs / 1000f, 0f..10f, { set(rule.copy(durationMs = if (it < 1.5f) 0 else (it * 1000).toLong())) },
                { if (it < 1.5f) "Default" else String.format(java.util.Locale.US, "%.1f s", it) })
            SwitchRow("Show message text", rule.showText, { set(rule.copy(showText = it)) })
            ChipsRow(listOf<NotificationPrivacy?>(null) + NotificationPrivacy.entries, rule.privacy, { it?.label ?: "Default" }, { set(rule.copy(privacy = it)) }, title = "Privacy")
            AccentRow(rule.accent) { set(rule.copy(accent = it)) }
        }
    }
}

private val AccentChoices = listOf(
    0xFF4C9BFF, 0xFF2FBF71, 0xFFFF5C9A, 0xFFFF9330, 0xFFF5B82E, 0xFF9B5CFF, 0xFF1FB5A0, 0xFFFF5147,
).map { it.toInt() }

/** The colour this app's notifications use in the island: Auto takes it from the app icon. */
@Composable
private fun AccentRow(accent: Int, onPick: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text("Accent colour", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Text("Rings the avatar and tints the reply button", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Swatch(selected = accent == 0, onClick = { onPick(0) }) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.sweepGradient(listOf(Color(0xFFFF5C9A), Color(0xFFF5B82E), Color(0xFF2FBF71), Color(0xFF4C9BFF), Color(0xFF9B5CFF), Color(0xFFFF5C9A)))
                    ),
                    contentAlignment = Alignment.Center,
                ) { Text("A", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
            }
            AccentChoices.forEach { c ->
                Swatch(selected = accent == c, onClick = { onPick(c) }) { Box(Modifier.fillMaxSize().background(Color(c))) }
            }
        }
    }
}

@Composable
private fun Swatch(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val ring by animateDpAsState(if (selected) 3.dp else 0.dp, spring(dampingRatio = 0.55f), label = "swatch")
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(ring, MaterialTheme.colorScheme.onSurface, CircleShape)
            .padding(ring + if (selected) 2.dp else 0.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) { content() }
}
