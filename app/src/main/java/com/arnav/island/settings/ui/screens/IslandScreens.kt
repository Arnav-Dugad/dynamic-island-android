package com.arnav.island.settings.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.SwipeDown
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arnav.island.animation.MotionPreset
import com.arnav.island.island.IdleStyle
import com.arnav.island.settings.ui.Dest
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.ChipsRow
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.NavRow
import com.arnav.island.settings.ui.components.RadioRow
import com.arnav.island.settings.ui.components.SegmentedRow
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SliderRow
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.preview.IslandPreview
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.storage.AppThemeMode
import com.arnav.island.storage.IslandSettings
import com.arnav.island.storage.IslandTheme
import com.arnav.island.storage.PerformanceMode
import com.arnav.island.storage.TapAction
import java.util.Locale

private fun autoLabel(v: Float, unit: String = "dp") = if (v <= 0f) "Auto" else String.format(Locale.US, "%.0f %s", v, unit)

@Composable
fun IslandScreen(s: IslandSettings, ui: Ui) {
    SettingsPage("Shape & motion", onBack = ui::back) {
        item {
            IslandPreview(ui.graph, s, Modifier.fillMaxWidth().height(230.dp).padding(vertical = 4.dp), demo = true)
        }
        item {
            Group(title = "Idle") {
                SegmentedRow(IdleStyle.entries, s.idleStyle, { it.label }, { v -> ui.update { it.copy(idleStyle = v) } },
                    subtitle = "What the island looks like with nothing to show")
                NavRow("Camera calibration", "Position, idle size and camera padding", Icons.Rounded.CenterFocusStrong, BadgeColors.Cyan) { ui.go(Dest.CALIBRATION) }
            }
        }
        item {
            Group(title = "Size", footer = "Auto sizes are derived from your camera and screen. Drag to the far left to return to Auto.") {
                SliderRow("Compact width", s.compactWidthDp, 0f..300f, { v -> ui.update { it.copy(compactWidthDp = snapAuto(v, 150f)) } }, { autoLabel(it) })
                SliderRow("Compact height", s.compactHeightDp, 0f..48f, { v -> ui.update { it.copy(compactHeightDp = snapAuto(v, 26f)) } }, { autoLabel(it) })
                SliderRow("Expanded max width", s.expandedMaxWidthDp, 0f..420f, { v -> ui.update { it.copy(expandedMaxWidthDp = snapAuto(v, 260f)) } }, { autoLabel(it) })
                SliderRow("Corner roundness", s.cornerRoundness, 0f..1f, { v -> ui.update { it.copy(cornerRoundness = v) } }, { "${(it * 100).toInt()}%" },
                    subtitle = "100% makes cards concentric with your display corners")
                SliderRow("Touch area below island", s.touchExtensionDp, 0f..32f, { v -> ui.update { it.copy(touchExtensionDp = v) } }, { autoLabel(it).replace("Auto", "0 dp") },
                    subtitle = "The status bar owns touches beside the camera; this adds a touchable strip just below the pill")
            }
        }
        item {
            Group(title = "Motion") {
                ChipsRow(MotionPreset.entries, s.motionPreset, { it.label }, { v ->
                    ui.update {
                        if (v == MotionPreset.CUSTOM) it.copy(motionPreset = v)
                        else it.copy(motionPreset = v, customResponse = v.spec.response, customDamping = v.spec.dampingRatio)
                    }
                }, title = "Animation preset")
                SliderRow("Animation speed", s.animationSpeed, 0.5f..1.6f, { v -> ui.update { it.copy(animationSpeed = v) } }, { String.format(Locale.US, "%.2f×", it) })
                SliderRow("Animation intensity", s.animationIntensity, 0f..1.6f, { v -> ui.update { it.copy(animationIntensity = v) } }, { "${(it * 100).toInt()}%" },
                    subtitle = "How much the island overshoots and bounces")
                SliderRow("Spring stiffness", 1f / s.customResponse, 1.4f..4.5f, { v -> ui.update { it.copy(motionPreset = MotionPreset.CUSTOM, customResponse = 1f / v) } },
                    { String.format(Locale.US, "%.0f", (2 * Math.PI * it) * (2 * Math.PI * it)) },
                    subtitle = "Higher is snappier. Changing this switches to the Custom preset")
                SliderRow("Spring damping", s.customDamping, 0.4f..1f, { v -> ui.update { it.copy(motionPreset = MotionPreset.CUSTOM, customDamping = v) } }, { String.format(Locale.US, "%.2f", it) },
                    subtitle = "1.0 means no overshoot")
            }
        }
        item {
            Group(title = "Behaviour") {
                SwitchRow("Split island", s.splitEnabled, { v -> ui.update { it.copy(splitEnabled = v) } }, "Show two live activities side by side", Icons.Rounded.Layers, BadgeColors.Indigo)
                SliderRow("Auto-collapse", s.autoCollapseSeconds.toFloat(), 0f..15f, { v -> ui.update { it.copy(autoCollapseSeconds = v.toInt()) } },
                    { if (it < 1f) "Never" else "${it.toInt()} s" }, steps = 14, subtitle = "Collapse an expanded card after inactivity")
                SwitchRow("Show in landscape", s.showInLandscape, { v -> ui.update { it.copy(showInLandscape = v) } }, "Floats at the top centre when rotated", Icons.Rounded.ScreenRotation, BadgeColors.Graphite)
            }
        }
    }
}

/** Values dragged into the lowest part of a slider mean "automatic". */
private fun snapAuto(v: Float, threshold: Float) = if (v < threshold) 0f else v

@Composable
fun GesturesScreen(s: IslandSettings, ui: Ui) {
    SettingsPage("Gestures", onBack = ui::back) {
        item {
            Group(title = "Tap") {
                SegmentedRow(TapAction.entries, s.tapAction, { it.label }, { v -> ui.update { it.copy(tapAction = v) } },
                    title = "Tap the island", subtitle = "Long-press always expands")
            }
        }
        item {
            Group(title = "Swipes") {
                SwitchRow("Swipe down to expand", s.swipeDownExpands, { v -> ui.update { it.copy(swipeDownExpands = v) } }, null, Icons.Rounded.SwipeDown, BadgeColors.Blue)
                SwitchRow("Swipe to dismiss", s.swipeToDismiss, { v -> ui.update { it.copy(swipeToDismiss = v) } }, "Sideways or up dismisses temporary events; notifications are cleared where Android allows", Icons.Rounded.Swipe, BadgeColors.Indigo)
            }
        }
        item {
            Group(title = "Feel") {
                SwitchRow("Haptic feedback", s.haptics, { v -> ui.update { it.copy(haptics = v) } }, "Subtle ticks on press, expand and buttons", Icons.Rounded.Vibration, BadgeColors.Violet)
                SwitchRow("Physical deformation", s.touchDeformation, { v -> ui.update { it.copy(touchDeformation = v) } }, "The island compresses under your finger and stretches as you drag", Icons.Rounded.Gesture, BadgeColors.Pink)
                SliderRow("Touch area below island", s.touchExtensionDp, 0f..32f, { v -> ui.update { it.copy(touchExtensionDp = v) } }, { "${it.toInt()} dp" })
            }
        }
        item {
            Group(footer = "Android draws the system status bar above every app overlay, so taps directly beside the camera may reach the status bar instead. The touch strip below the pill always reaches the island.") {
                NavRow("Try it in the preview", "Home screen · tap, long-press, swipe", Icons.Rounded.OpenWith, BadgeColors.Graphite) { ui.go(Dest.HOME) }
            }
        }
    }
}

@Composable
fun AppearanceScreen(s: IslandSettings, ui: Ui) {
    SettingsPage("Appearance", onBack = ui::back) {
        item {
            IslandPreview(ui.graph, s, Modifier.fillMaxWidth().height(210.dp).padding(vertical = 4.dp), demo = true)
        }
        item {
            Group(title = "Island theme") {
                IslandTheme.entries.forEach { theme ->
                    ThemeOption(theme, selected = s.islandTheme == theme) { ui.update { it.copy(islandTheme = theme) } }
                }
            }
        }
        item {
            Group(title = "Content") {
                SwitchRow("Colours from artwork", s.tintFromArtwork, { v -> ui.update { it.copy(tintFromArtwork = v) } }, "Tint the equalizer and progress with the album art", Icons.Rounded.Animation, BadgeColors.Pink)
            }
        }
        item {
            Group(title = "App") {
                SegmentedRow(AppThemeMode.entries, s.appTheme, { it.label }, { v -> ui.update { it.copy(appTheme = v) } }, title = "Theme")
                SwitchRow("Pure black", s.amoledBlack, { v -> ui.update { it.copy(amoledBlack = v) } }, "True OLED black backgrounds in dark mode", Icons.Rounded.DarkMode, BadgeColors.Graphite)
                SwitchRow("Wallpaper colours", s.dynamicColor, { v -> ui.update { it.copy(dynamicColor = v) } }, "Use Material You accents", Icons.Rounded.Animation, BadgeColors.Violet)
            }
        }
    }
}

@Composable
private fun ThemeOption(theme: IslandTheme, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeSwatch(theme, selected)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(theme.label, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(theme.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThemeSwatch(theme: IslandTheme, selected: Boolean) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF2A2F55), border = BorderStroke(2.dp, ring), modifier = Modifier.size(width = 76.dp, height = 44.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(width = 54.dp, height = 18.dp)) {
                val r = CornerRadius(size.height / 2, size.height / 2)
                when (theme) {
                    IslandTheme.GLASS -> {
                        drawRoundRect(Color(0xE0101114), cornerRadius = r)
                        drawRoundRect(Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color.Transparent)), cornerRadius = r)
                        drawRoundRect(Color(0x40FFFFFF), cornerRadius = r, style = Stroke(1.dp.toPx()))
                    }
                    IslandTheme.RGB -> {
                        drawRoundRect(Color.Black, cornerRadius = r)
                        drawRoundRect(
                            Brush.sweepGradient(listOf(Color(0xFFFF4D6D), Color(0xFFFFB84D), Color(0xFF4DFF88), Color(0xFF4DD2FF), Color(0xFF7B4DFF), Color(0xFFFF4D6D))),
                            cornerRadius = r, style = Stroke(1.6.dp.toPx()),
                        )
                    }
                    else -> drawRoundRect(Color.Black, cornerRadius = r)
                }
                drawCircle(Color(0xFF1A1D26), radius = size.height * 0.22f, center = Offset(size.width * 0.68f, size.height / 2))
                if (theme == IslandTheme.SAMSUNG) drawCircle(Color(0xFF7FB2FF), radius = size.height * 0.2f, center = Offset(size.width * 0.2f, size.height / 2))
                if (theme != IslandTheme.MINIMAL && theme != IslandTheme.SAMSUNG) {
                    for (i in 0..2) {
                        val h = size.height * (0.28f + 0.14f * i)
                        drawRoundRect(Color.White, topLeft = Offset(size.width * 0.12f + i * 5.dp.toPx(), (size.height - h) / 2), size = Size(2.5.dp.toPx(), h), cornerRadius = CornerRadius(2f, 2f))
                    }
                }
            }
        }
    }
}

@Composable
fun PerformanceScreen(s: IslandSettings, ui: Ui) {
    SettingsPage("Performance", onBack = ui::back) {
        item {
            Group(title = "Mode", footer = "The island draws nothing while idle, so an idle island costs no CPU in any mode.") {
                PerformanceMode.entries.forEach { mode ->
                    RadioRow(mode.label, s.performanceMode == mode, { ui.update { it.copy(performanceMode = mode) } }, mode.description)
                }
            }
        }
        item {
            Group(title = "Display") {
                SwitchRow("Burn-in protection", s.burnInProtection, { v -> ui.update { it.copy(burnInProtection = v) } },
                    "Bright content drifts by one pixel every few minutes. The black shape never moves, so it stays locked to the camera.",
                    Icons.Rounded.DarkMode, BadgeColors.Graphite)
            }
        }
        item {
            Text(
                "While animating, Maximum smoothness asks Android for the display's highest refresh rate (Android 15+ uses the frame-rate category API). Animations are closed-form springs, so motion is identical at 60 or 120 Hz.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}
