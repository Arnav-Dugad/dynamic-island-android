package com.arnav.island.settings.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.events.timer.StopwatchState
import com.arnav.island.events.timer.TimerItem
import com.arnav.island.permissions.PermissionSnapshot
import com.arnav.island.permissions.Permissions
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.ChipsRow
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.PermissionRow
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SliderRow
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.settings.ui.theme.LocalIslandColors
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Formatters
import kotlinx.coroutines.delay

private val TimerOrange = Color(0xFFFFA23A)
private val tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun TimersScreen(s: IslandSettings, p: PermissionSnapshot, ui: Ui) {
    val context = LocalContext.current
    val state by ui.graph.timers.state.collectAsStateWithLifecycle()
    var minutes by remember { mutableIntStateOf(5) }
    var seconds by remember { mutableIntStateOf(0) }
    var label by remember { mutableStateOf("") }
    var notificationsGranted by remember(p.postNotifications) { mutableStateOf(p.postNotifications) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsGranted = it }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(if (state.stopwatch.running) 50 else 250)
            value = System.currentTimeMillis()
        }
    }

    SettingsPage("Timers", onBack = ui::back) {
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = LocalIslandColors.current.card, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(vertical = 18.dp)) {
                    Text(
                        Formatters.clock(minutes * 60L + seconds),
                        style = tabular.merge(TextStyle(fontSize = 56.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)),
                        color = TimerOrange,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    ChipsRow(listOf(1, 3, 5, 10, 15, 25, 45, 60), minutes.takeIf { seconds == 0 } ?: -1, { "$it min" }, { minutes = it; seconds = 0 })
                    SliderRow("Minutes", minutes.toFloat(), 0f..120f, { minutes = it.toInt() }, { "${it.toInt()}" }, live = true)
                    SliderRow("Seconds", seconds.toFloat(), 0f..55f, { seconds = it.toInt() }, { "${it.toInt()}" }, steps = 10, live = true)
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(24) },
                        placeholder = { Text("Label (optional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            ui.graph.timers.start((minutes * 60L + seconds) * 1000L, label)
                            label = ""
                        },
                        enabled = minutes * 60 + seconds > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = TimerOrange, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Start timer", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        if (state.timers.isNotEmpty()) {
            item {
                Group(title = "Running") {
                    state.timers.forEach { t -> TimerRow(t, now, ui) }
                }
            }
        }

        item { StopwatchCard(state.stopwatch, now, ui) }

        item {
            Group(title = "Island", footer = "Timers keep running with Island closed or after a restart; completion is delivered by Android's alarm service.") {
                SwitchRow("Show timers", s.timersEnabled, { v -> ui.update { it.copy(timersEnabled = v) } }, null, Icons.Rounded.Timer, BadgeColors.Orange)
                SwitchRow("Show stopwatch", s.stopwatchEnabled, { v -> ui.update { it.copy(stopwatchEnabled = v) } }, null, Icons.Rounded.Timelapse, BadgeColors.Orange)
                PermissionRow("Precise alarms", if (p.exactAlarms) "Timers finish to the second" else "Without it, Android may delay the alert slightly in deep sleep", p.exactAlarms, Icons.Rounded.AlarmOn, BadgeColors.Yellow,
                    onGrant = { ui.open(Permissions.exactAlarmSettings(context)) })
                PermissionRow("Timer alerts", if (notificationsGranted) "Sound when a timer finishes" else "Allow notifications for the finished-timer sound", notificationsGranted, Icons.Rounded.NotificationsActive, BadgeColors.Red,
                    onGrant = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) })
            }
        }
    }
}

@Composable
private fun TimerRow(t: TimerItem, now: Long, ui: Ui) {
    val remaining = t.remaining(now)
    val fraction = if (t.totalMs > 0) remaining.toFloat() / t.totalMs else 0f
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            val track = LocalIslandColors.current.cardPressed
            Canvas(Modifier.size(46.dp)) {
                val stroke = 4.dp.toPx()
                drawArc(track, 0f, 360f, false, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                drawArc(if (t.paused) Color.Gray else TimerOrange, -90f, 360f * fraction, false, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (t.ringing) "Done" else Formatters.countdown(remaining),
                style = tabular.merge(MaterialTheme.typography.headlineSmall),
                color = if (t.paused) MaterialTheme.colorScheme.onSurfaceVariant else TimerOrange,
            )
            Text(t.label.ifBlank { Formatters.humanDuration(t.totalMs) + " timer" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!t.ringing) {
                FilledTonalIconButton(onClick = { ui.graph.timers.togglePause(t.id) }) {
                    Icon(if (t.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (t.paused) "Resume" else "Pause")
                }
            }
            FilledTonalIconButton(onClick = { ui.graph.timers.addMinute(t.id) }) { Icon(Icons.Rounded.Add, "Add one minute") }
            FilledTonalIconButton(onClick = { ui.graph.timers.cancel(t.id) }) { Icon(Icons.Rounded.Close, if (t.ringing) "Stop" else "Cancel") }
        }
    }
}

@Composable
private fun StopwatchCard(sw: StopwatchState, now: Long, ui: Ui) {
    Surface(shape = RoundedCornerShape(28.dp), color = LocalIslandColors.current.card, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("Stopwatch", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                Formatters.stopwatch(sw.elapsed(now)),
                style = tabular.merge(TextStyle(fontSize = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { ui.graph.timers.stopwatchToggle() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sw.running) Color(0xFFFF5147) else Color(0xFF3DDC84),
                        contentColor = Color.Black,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(if (sw.running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (sw.running) "Stop" else if (sw.accumulatedMs > 0) "Resume" else "Start")
                }
                if (sw.running) {
                    FilledTonalIconButton(onClick = { ui.graph.timers.stopwatchLap() }) { Icon(Icons.Rounded.Flag, "Lap") }
                } else if (sw.active) {
                    FilledTonalIconButton(onClick = { ui.graph.timers.stopwatchReset() }) { Icon(Icons.Rounded.RestartAlt, "Reset") }
                }
            }
            if (sw.laps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                sw.laps.withIndex().reversed().take(6).forEach { (i, lap) ->
                    val previous = if (i > 0) sw.laps[i - 1] else 0L
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text("Lap ${i + 1}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(Formatters.stopwatch(lap - previous), style = tabular.merge(MaterialTheme.typography.bodyMedium))
                    }
                }
            }
        }
    }
}
