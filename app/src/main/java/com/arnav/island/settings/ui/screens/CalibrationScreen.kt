package com.arnav.island.settings.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.core.IslandCommand
import com.arnav.island.island.IdleStyle
import com.arnav.island.overlay.IslandOverlayService
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.ChipsRow
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.InfoCard
import com.arnav.island.settings.ui.components.SegmentedRow
import com.arnav.island.settings.ui.components.SettingRow
import com.arnav.island.settings.ui.components.SliderRow
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.storage.IslandSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class CalibrationPreview(val label: String) { IDLE("Idle"), COMPACT("Compact"), SPLIT("Split"), TOAST("Toast"), EXPANDED("Expanded") }

@Composable
fun CalibrationScreen(s: IslandSettings, ui: Ui) {
    val graph = ui.graph
    val runtime by graph.runtime.collectAsStateWithLifecycle()
    val running by IslandOverlayService.running.collectAsStateWithLifecycle()
    var contrast by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(CalibrationPreview.COMPACT) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        graph.calibrating.value = true
        onDispose {
            graph.calibrating.value = false
            graph.testEvents.clear()
        }
    }
    LaunchedEffect(mode, running) {
        val tests = graph.testEvents
        tests.clear()
        when (mode) {
            CalibrationPreview.IDLE -> Unit
            CalibrationPreview.COMPACT -> tests.music(true)
            CalibrationPreview.SPLIT -> tests.split()
            CalibrationPreview.TOAST -> tests.charging(s.chargingTheme)
            CalibrationPreview.EXPANDED -> {
                tests.music(true)
                delay(250)
                graph.commands.tryEmit(IslandCommand.Expand("test:media"))
            }
        }
    }
    LightStatusBar(contrast)

    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // High-contrast stage behind the camera so the island's edge is visible.
        Box(
            Modifier
                .fillMaxWidth()
                .height(statusBar + 104.dp)
                .background(if (contrast) Color.White else Color.Black),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Text(
                "Align the black shape with your camera",
                color = if (contrast) Color(0xFF55565F) else Color(0xFFB4B6C0),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ui::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Calibration", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { graph.settings.resetCalibration() } }) { Text("Reset") }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            if (!running) {
                item {
                    InfoCard(
                        title = "Turn on the island to calibrate",
                        body = "Calibration moves the real island around your camera, so it needs to be running.",
                        icon = Icons.Rounded.Layers,
                        color = BadgeColors.Indigo,
                        actionLabel = "Turn on",
                        onAction = { ui.setEnabled(true) },
                    )
                }
            }
            item {
                Group(title = "Show") {
                    ChipsRow(CalibrationPreview.entries, mode, { it.label }, { mode = it })
                    SwitchRow("High-contrast stage", contrast, { contrast = it }, "White behind the camera makes the edge obvious", Icons.Rounded.Contrast, BadgeColors.Graphite)
                }
            }
            item {
                Group(title = "Position", footer = "Each step is exactly one physical pixel. Hold to repeat.") {
                    NudgeRow("Horizontal", s.offsetXPx) { d -> ui.update { it.copy(offsetXPx = it.offsetXPx + d) } }
                    NudgeRow("Vertical", s.offsetYPx) { d -> ui.update { it.copy(offsetYPx = it.offsetYPx + d) } }
                }
            }
            item {
                Group(title = "Camera") {
                    SwitchRow("Auto detect", s.autoDetectCutout, { auto ->
                        ui.update {
                            if (auto) {
                                it.copy(autoDetectCutout = true)
                            } else {
                                // Start manual mode from what Android reported.
                                it.copy(
                                    autoDetectCutout = false,
                                    manualCameraXPx = if (it.manualCameraXPx >= 0f) it.manualCameraXPx else runtime.cameraX - it.offsetXPx,
                                    manualCameraYPx = if (it.manualCameraYPx >= 0f) it.manualCameraYPx else runtime.cameraY - it.offsetYPx,
                                    manualCameraRadiusPx = if (it.manualCameraRadiusPx > 0f) it.manualCameraRadiusPx else runtime.cameraRadius,
                                )
                            }
                        }
                    }, "Read the camera position from Android's display cutout", Icons.Rounded.CenterFocusStrong, BadgeColors.Cyan)
                    if (!s.autoDetectCutout) {
                        SliderRow("Camera X", s.manualCameraXPx, 0f..runtime.screenWidth.coerceAtLeast(1).toFloat(), { v -> ui.update { it.copy(manualCameraXPx = v) } }, { "${it.toInt()} px" }, live = true)
                        SliderRow("Camera Y", s.manualCameraYPx, 0f..200f, { v -> ui.update { it.copy(manualCameraYPx = v) } }, { "${it.toInt()} px" }, live = true)
                        SliderRow("Camera radius", s.manualCameraRadiusPx, 8f..60f, { v -> ui.update { it.copy(manualCameraRadiusPx = v) } }, { String.format(Locale.US, "%.1f px", it) }, live = true)
                    }
                    SettingRow("Detected by", runtime.cutoutSource?.label ?: "Not measured yet")
                    SettingRow("Camera centre", String.format(Locale.US, "x %.1f · y %.1f px · radius %.1f px", runtime.cameraX, runtime.cameraY, runtime.cameraRadius))
                    SettingRow("Cutout region", String.format(Locale.US, "%.0f × %.0f px", runtime.cutoutWidth, runtime.cutoutHeight))
                    SettingRow("Display", "${runtime.screenWidth} × ${runtime.screenHeight} px · density ${runtime.density} · status bar ${runtime.statusBarHeight} px")
                    SettingRow("Device profile", runtime.deviceProfile)
                }
            }
            item {
                Group(title = "Size") {
                    SegmentedRow(IdleStyle.entries, s.idleStyle, { it.label }, { v -> ui.update { it.copy(idleStyle = v) } }, title = "Idle")
                    SliderRow("Camera padding", s.cameraPaddingDp, 0f..6f, { v -> ui.update { it.copy(cameraPaddingDp = v) } }, { String.format(Locale.US, "%.1f dp", it) }, live = true,
                        subtitle = "Black margin around the lens in the idle state")
                    if (s.idleStyle == IdleStyle.PILL) {
                        SliderRow("Idle width", s.idleWidthDp, 0f..120f, { v -> ui.update { it.copy(idleWidthDp = if (v < 20f) 0f else v) } }, { if (it < 20f) "Auto" else "${it.toInt()} dp" }, live = true)
                        SliderRow("Idle height", s.idleHeightDp, 0f..40f, { v -> ui.update { it.copy(idleHeightDp = if (v < 10f) 0f else v) } }, { if (it < 10f) "Auto" else "${it.toInt()} dp" }, live = true)
                    }
                    SliderRow("Compact width", s.compactWidthDp, 0f..300f, { v -> ui.update { it.copy(compactWidthDp = if (v < 150f) 0f else v) } }, { if (it < 150f) "Auto" else "${it.toInt()} dp" }, live = true)
                    SliderRow("Compact height", s.compactHeightDp, 0f..48f, { v -> ui.update { it.copy(compactHeightDp = if (v < 26f) 0f else v) } }, { if (it < 26f) "Auto" else "${it.toInt()} dp" }, live = true)
                    SliderRow("Expanded max width", s.expandedMaxWidthDp, 0f..420f, { v -> ui.update { it.copy(expandedMaxWidthDp = if (v < 260f) 0f else v) } }, { if (it < 260f) "Auto" else "${it.toInt()} dp" }, live = true)
                    SliderRow("Corner roundness", s.cornerRoundness, 0f..1f, { v -> ui.update { it.copy(cornerRoundness = v) } }, { "${(it * 100).toInt()}%" }, live = true)
                }
            }
        }
    }
}

@Composable
private fun NudgeRow(title: String, value: Float, onStep: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                String.format(Locale.US, "%+.0f px", value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RepeatButton(Icons.Rounded.Remove, "$title minus one pixel") { onStep(-1f) }
            RepeatButton(Icons.Rounded.Add, "$title plus one pixel") { onStep(1f) }
        }
    }
}

/** Steps once on tap; while held, keeps stepping with a short initial delay. */
@Composable
private fun RepeatButton(icon: ImageVector, description: String, onStep: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val step by rememberUpdatedState(onStep)
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        repeated = false
        delay(420)
        repeated = true
        while (true) {
            step()
            delay(55)
        }
    }
    FilledTonalIconButton(
        onClick = { if (!repeated) step() },
        interactionSource = interaction,
    ) { Icon(icon, description) }
}

/** Dark status bar icons over the white calibration stage; restored on exit. */
@Composable
private fun LightStatusBar(light: Boolean) {
    val view = LocalView.current
    DisposableEffect(light) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = light
        onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
    }
}
