package com.arnav.island.settings.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.app.AlarmManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.BuildConfig
import com.arnav.island.core.IslandCommand
import com.arnav.island.events.GlancePayload
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandAction
import com.arnav.island.island.render.presenters.NotificationPresenter
import com.arnav.island.overlay.IslandOverlayService
import com.arnav.island.overlay.StatusBarCleanup
import com.arnav.island.settings.reply.ReplyRequest
import com.arnav.island.settings.ui.Dest
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.ChipsRow
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** Public links. Opening them hands off to the browser; Island itself has no network access. */
object Links {
    const val REPO = "https://github.com/Arnav-Dugad/dynamic-island-android"
    const val RELEASES = "$REPO/releases/latest"
    const val OBTAINIUM = "obtainium://add/$REPO"
    const val OBTAINIUM_SITE = "https://obtainium.imranr.dev"
}

private fun Ui.browse(url: String, fallback: String? = null) =
    open(Intent(Intent.ACTION_VIEW, Uri.parse(url)), fallback?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) })

private fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** The reply action used by demos: opens the real reply sheet with a test conversation. */
fun testReplyAction(context: Context, onOpen: () -> Unit = {}) = IslandAction(NotificationPresenter.ACTION_REPLY, "Reply", Glyph.REPLY, collapses = true) {
    ReplyRequest.launch(context, ReplyRequest.test(context))
    onOpen()
}

// -------------------------------------------------------------------------------------------------
// Island Studio
// -------------------------------------------------------------------------------------------------

private enum class StudioMode(val label: String) { COMPACT("Compact"), EXPANDED("Expanded") }

/**
 * Island Studio: drag the edges of a large blueprint and the real island (showing a test
 * activity at the top of the screen) resizes live.
 */
@Composable
fun IslandStudioScreen(s: IslandSettings, ui: Ui) {
    val graph = ui.graph
    val runtime by graph.runtime.collectAsStateWithLifecycle()
    val running by IslandOverlayService.running.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(StudioMode.COMPACT) }

    DisposableEffect(Unit) {
        onDispose { graph.testEvents.clear() }
    }
    LaunchedEffect(mode, running) {
        graph.testEvents.clear()
        graph.testEvents.music(true)
        if (mode == StudioMode.EXPANDED) {
            delay(250)
            graph.commands.tryEmit(IslandCommand.Expand("test:media"))
        }
    }

    val compactW = s.compactWidthDp.takeIf { it > 0f } ?: runtime.compactWidthDp.takeIf { it > 0f } ?: 190f
    val compactH = s.compactHeightDp.takeIf { it > 0f } ?: runtime.compactHeightDp.takeIf { it > 0f } ?: 37f
    val expandedW = s.expandedMaxWidthDp.takeIf { it > 0f } ?: runtime.expandedWidthDp.takeIf { it > 0f } ?: 370f

    SettingsPage("Island Studio", onBack = ui::back) {
        if (!running) {
            item {
                InfoCard(
                    title = "Turn on the island",
                    body = "Studio resizes the real island at the top of your screen as you drag.",
                    icon = Icons.Rounded.Layers,
                    color = BadgeColors.Indigo,
                    actionLabel = "Turn on",
                    onAction = { ui.setEnabled(true) },
                )
            }
        }
        item {
            ChipsRow(StudioMode.entries, mode, { it.label }, { mode = it })
        }
        item {
            Blueprint(
                mode = mode,
                widthDp = if (mode == StudioMode.COMPACT) compactW else expandedW,
                heightDp = if (mode == StudioMode.COMPACT) compactH else 150f,
                roundness = s.cornerRoundness,
                onWidth = { w ->
                    ui.update {
                        if (mode == StudioMode.COMPACT) it.copy(compactWidthDp = w.coerceIn(150f, 300f))
                        else it.copy(expandedMaxWidthDp = w.coerceIn(260f, 420f))
                    }
                },
                onHeight = { h -> if (mode == StudioMode.COMPACT) ui.update { it.copy(compactHeightDp = h.coerceIn(26f, 48f)) } },
            )
        }
        item {
            Group(footer = "Drag the handles on the blueprint. The island at the top of the screen follows live. Reset returns every size to automatic.") {
                SliderRow("Corner roundness", s.cornerRoundness, 0f..1f, { v -> ui.update { it.copy(cornerRoundness = v) } }, { "${(it * 100).toInt()}%" }, live = true)
                SettingRow(
                    "Current size",
                    String.format(Locale.US, "Compact %.0f × %.0f dp · Expanded %.0f dp wide", compactW, compactH, expandedW),
                    Icons.Rounded.Straighten, BadgeColors.Cyan,
                ) {
                    FilledTonalButton(onClick = { ui.update { it.copy(compactWidthDp = 0f, compactHeightDp = 0f, expandedMaxWidthDp = 0f, cornerRoundness = 1f) } }) { Text("Reset") }
                }
            }
        }
    }
}

@Composable
private fun Blueprint(
    mode: StudioMode,
    widthDp: Float,
    heightDp: Float,
    roundness: Float,
    onWidth: (Float) -> Unit,
    onHeight: (Float) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    // Local copies so the drag feels instant; settings catch up asynchronously.
    var w by remember(mode) { mutableFloatStateOf(widthDp) }
    var h by remember(mode) { mutableFloatStateOf(heightDp) }
    var dragging by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(widthDp, heightDp) {
        if (dragging == null) {
            w = widthDp
            h = heightDp
        }
    }
    val latestW by rememberUpdatedState(onWidth)
    val latestH by rememberUpdatedState(onHeight)
    val grow by animateFloatAsState(if (dragging != null) 1f else 0f, spring(dampingRatio = 0.6f), label = "handle")

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF191C2E), Color(0xFF0B0C14))))
            .height(250.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(250.dp)
                .pointerInput(mode) {
                    // The blueprint is drawn at 1.25x (compact) or 0.8x (expanded) of real size.
                    val scale = if (mode == StudioMode.COMPACT) 1.25f else 0.8f
                    detectDragGestures(
                        onDragStart = { p ->
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val halfW = w.dp.toPx() * scale / 2f
                            val halfH = h.dp.toPx() * scale / 2f
                            dragging = when {
                                mode == StudioMode.COMPACT && kotlin.math.abs(p.y - (cy + halfH)) < 28.dp.toPx() && kotlin.math.abs(p.x - cx) < halfW -> "bottom"
                                p.x > cx -> "right"
                                else -> "left"
                            }
                        },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null },
                    ) { change, drag ->
                        change.consume()
                        when (dragging) {
                            "right" -> w += drag.x * 2f / scale / density
                            "left" -> w -= drag.x * 2f / scale / density
                            "bottom" -> h += drag.y * 2f / scale / density
                        }
                        if (mode == StudioMode.COMPACT) {
                            w = w.coerceIn(150f, 300f)
                            h = h.coerceIn(26f, 48f)
                            if (dragging == "bottom") latestH(h) else latestW(w)
                        } else {
                            w = w.coerceIn(260f, 420f)
                            latestW(w)
                        }
                    }
                },
        ) {
            val scale = if (mode == StudioMode.COMPACT) 1.25f else 0.8f
            val pw = w.dp.toPx() * scale
            val ph = h.dp.toPx() * scale
            val left = (size.width - pw) / 2f
            val top = (size.height - ph) / 2f
            // Grid.
            val step = 16.dp.toPx()
            var gx = size.width / 2f % step
            while (gx < size.width) {
                drawLine(Color(0x10FFFFFF), Offset(gx, 0f), Offset(gx, size.height), 1f)
                gx += step
            }
            var gy = size.height / 2f % step
            while (gy < size.height) {
                drawLine(Color(0x10FFFFFF), Offset(0f, gy), Offset(size.width, gy), 1f)
                gy += step
            }
            val radius = if (mode == StudioMode.COMPACT) ph / 2f else (22.dp.toPx() + 18.dp.toPx() * roundness) * scale
            drawRoundRect(Color.Black, Offset(left, top), Size(pw, ph), CornerRadius(radius))
            drawRoundRect(accent.copy(alpha = 0.55f + 0.45f * grow), Offset(left, top), Size(pw, ph), CornerRadius(radius), style = Stroke(1.5.dp.toPx()))
            // Camera.
            val camY = if (mode == StudioMode.COMPACT) top + ph / 2f else top + 18.dp.toPx() * scale
            drawCircle(Color(0xFF1A1F33), 7.dp.toPx() * scale, Offset(size.width / 2f, camY))
            drawCircle(Color(0xFF2E3A66), 3.dp.toPx() * scale, Offset(size.width / 2f, camY))
            // Handles.
            fun handle(c: Offset, active: Boolean) {
                val r = 7.dp.toPx() + if (active) 3.dp.toPx() * grow else 0f
                drawCircle(Color.White, r, c)
                drawCircle(accent, r - 2.5.dp.toPx(), c)
            }
            handle(Offset(left, top + ph / 2f), dragging == "left")
            handle(Offset(left + pw, top + ph / 2f), dragging == "right")
            if (mode == StudioMode.COMPACT) handle(Offset(size.width / 2f, top + ph), dragging == "bottom")
        }
        Text(
            if (mode == StudioMode.COMPACT) String.format(Locale.US, "%.0f × %.0f dp", w, h) else String.format(Locale.US, "%.0f dp wide", w),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Motion details
// -------------------------------------------------------------------------------------------------

@Composable
fun MotionDetailsGroup(s: IslandSettings, ui: Ui) {
    Group(title = "Motion details", footer = "Every effect follows the animation intensity above and switches off with Android's Remove animations.") {
        SwitchRow("Squash & stretch", s.squashStretch, { v -> ui.update { it.copy(squashStretch = v) } }, "The island stretches with speed and settles like something physical", Icons.Rounded.Animation, BadgeColors.Pink)
        SwitchRow("Icon flight", s.iconFlight, { v -> ui.update { it.copy(iconFlight = v) } }, "A notification's icon flies in from the status bar; artwork glides between sizes", Icons.Rounded.Flight, BadgeColors.Blue)
        SwitchRow("Arrival pulse", s.arrivalPulse, { v -> ui.update { it.copy(arrivalPulse = v) } }, "A soft ring in the event's colour when something arrives", Icons.Rounded.Waves, BadgeColors.Cyan)
        SwitchRow("Lens glint", s.lensGlint, { v -> ui.update { it.copy(lensGlint = v) } }, "A faint highlight sweeps past the camera as the island opens", Icons.Rounded.Flare, BadgeColors.Yellow)
        SwitchRow("Blur-in text", s.blurReveal, { v -> ui.update { it.copy(blurReveal = v) } }, "Content sharpens into place (Android 12+)", Icons.Rounded.BlurOn, BadgeColors.Violet)
        SwitchRow("Tilt depth", s.tiltDepth, { v -> ui.update { it.copy(tiltDepth = v) } }, "Open cards shift slightly as you tilt the phone", Icons.Rounded.ScreenRotation, BadgeColors.Teal)
        SwitchRow("Sound design", s.soundEffects, { v -> ui.update { it.copy(soundEffects = v) } }, "Quiet synthesized clicks; silent when the phone is on vibrate or silent", Icons.AutoMirrored.Rounded.VolumeUp, BadgeColors.Orange)
    }
}

// -------------------------------------------------------------------------------------------------
// What's new
// -------------------------------------------------------------------------------------------------

private class Feature(val title: String, val body: String, val icon: ImageVector, val color: Color, val play: suspend (PreviewIsland) -> Unit)

@Composable
fun WhatsNewScreen(s: IslandSettings, ui: Ui) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<PreviewIsland?>(null) }
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf<String?>(null) }

    val features = remember {
        listOf(
            Feature("Icon flight", "New notifications fly in from the status bar along a spring arc, then open into the banner.", Icons.Rounded.Flight, BadgeColors.Blue) { p ->
                p.tests.notification()
            },
            Feature("Album art in motion", "Artwork glides between the pill and the card, with a soft glow in its colours. Up next, output device and a volume slider live in the card.", Icons.Rounded.Album, BadgeColors.Pink) { p ->
                p.tests.music(true)
                delay(1_200)
                p.controller.expand("test:media")
                delay(3_800)
                p.controller.collapse()
            },
            Feature("Group chats & quick reply", "Several people in one chat show as stacked avatars. Reply right from the island using the app's own reply action.", Icons.Rounded.Forum, BadgeColors.Green) { p ->
                p.tests.groupChat(testReplyAction(context) { p.engine.remove("test:group") })
                delay(900)
                p.controller.expand("test:group")
            },
            Feature("Stack peek", "Pull an open card further down to see every running activity at once.", Icons.Rounded.ViewAgenda, BadgeColors.Indigo) { p ->
                p.tests.music(true, rich = false)
                p.tests.timer(4)
                delay(1_000)
                p.controller.showStack()
                delay(3_600)
                p.controller.collapse()
            },
            Feature("Glance", "Long-press the empty island for the date, battery, next alarm and what's playing.", Icons.Rounded.Today, BadgeColors.Cyan) { p ->
                p.tests.glance(realGlance(context))
            },
            Feature("Final countdown", "Timers pulse and warm to red in the last ten seconds, with a haptic tick each second. Digits roll like an odometer.", Icons.Rounded.HourglassBottom, BadgeColors.Orange) { p ->
                p.tests.finalCountdown()
            },
            Feature("Charging graph", "The charging card draws live charging power, and says exactly where charging paused.", Icons.AutoMirrored.Rounded.ShowChart, BadgeColors.Green) { p ->
                p.tests.chargingGraph(s.chargingTheme)
                delay(900)
                p.controller.expand("test:charging")
                delay(3_600)
                p.controller.collapse()
                p.engine.remove("test:charging")
            },
            Feature("Throw to dismiss", "Fling a banner sideways and it flies off with the speed of your swipe.", Icons.Rounded.SwipeLeft, BadgeColors.Red) { p ->
                p.tests.notification()
                delay(1_600)
                p.engine.current().toast?.let { p.controller.dismiss(it, throwVelocity = 4_200f) }
            },
            Feature("Arrival pulse & lens glint", "Events arrive with a ring of colour around the camera; cards catch a glint as they open.", Icons.Rounded.Flare, BadgeColors.Yellow) { p ->
                p.tests.ringer()
                delay(2_400)
                p.tests.bluetooth()
            },
        )
    }

    SettingsPage("What's new in ${BuildConfig.VERSION_NAME.substringBefore('-')}", onBack = ui::back) {
        item {
            IslandPreview(ui.graph, s, Modifier.fillMaxWidth().height(350.dp).padding(vertical = 4.dp), onReady = { preview = it })
        }
        item {
            Text(
                "Tap play to see each feature on the live preview. It's the same renderer as the real island.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        features.forEach { f ->
            item {
                FeatureCard(f, playing == f.title) {
                    val p = preview ?: return@FeatureCard
                    scope.launch {
                        playing = f.title
                        p.tests.clear()
                        p.controller.collapse()
                        delay(300)
                        f.play(p)
                        delay(2_000)
                        if (playing == f.title) playing = null
                    }
                }
            }
        }
        item {
            Group(title = "Also new") {
                NavRow("Island Studio", "Drag the real island's edges", Icons.Rounded.Straighten, BadgeColors.Cyan) { ui.go(Dest.STUDIO) }
                NavRow("Motion lab & details", "See the springs, pick each effect", Icons.Rounded.Animation, BadgeColors.Pink) { ui.go(Dest.ISLAND) }
                NavRow("Share with friends", "QR code, install guide, Lite edition", Icons.Rounded.QrCode2, BadgeColors.Violet) { ui.go(Dest.SHARE) }
                NavRow("Status bar cleanup", "Hide icons the island already shows", Icons.Rounded.VisibilityOff, BadgeColors.Graphite) { ui.go(Dest.STATUS_BAR) }
            }
        }
    }
}

@Composable
private fun FeatureCard(f: Feature, playing: Boolean, onPlay: () -> Unit) {
    val scale by animateFloatAsState(if (playing) 1.02f else 1f, spring(dampingRatio = 0.6f), label = "feature")
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = LocalIslandColors.current.card,
        border = if (playing) androidx.compose.foundation.BorderStroke(1.5.dp, f.color.copy(alpha = 0.7f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .scale(scale),
    ) {
        Row(Modifier.clickable(onClick = onPlay).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(f.color), contentAlignment = Alignment.Center) {
                Icon(f.icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(f.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(f.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            FilledTonalIconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, "Play ${f.title}") }
        }
    }
}

/** Glance for the preview, built from the same real state as the island's own Glance. */
private fun realGlance(context: Context): GlancePayload {
    val battery = context.getSystemService(BatteryManager::class.java)
    val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    val alarm = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime?.takeIf { it > System.currentTimeMillis() }
    return GlancePayload(level, battery.isCharging, alarm, runningTimers = 0, nowPlaying = null)
}

// -------------------------------------------------------------------------------------------------
// Share with friends
// -------------------------------------------------------------------------------------------------

@Composable
fun ShareScreen(ui: Ui) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }

    SettingsPage("Share with friends", onBack = ui::back) {
        if (BuildConfig.LITE) {
            item { LiteCard(ui, showUpgrade = false) }
        }
        item {
            Surface(shape = RoundedCornerShape(30.dp), color = Color.White, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCode(Links.RELEASES, Modifier.fillMaxWidth(0.72f).aspectRatio(1f))
                    Spacer(Modifier.height(18.dp))
                    Text("Scan to download Island", style = MaterialTheme.typography.titleLarge, color = Color.Black, fontWeight = FontWeight.SemiBold)
                    Text(Links.RELEASES.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = Color(0xFF5B5E6B), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "Island: a free Dynamic Island for Android, fully local, no ads. Download: ${Links.RELEASES}")
                            ui.open(Intent.createChooser(send, "Share Island"))
                        }) {
                            Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Share link")
                        }
                        FilledTonalButton(onClick = { copy(context, "Island", Links.RELEASES); copied = true }) {
                            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (copied) "Copied" else "Copy")
                        }
                    }
                }
            }
        }
        item {
            Group(title = "Which file to download", footer = "Both editions are the same app with the same signature, so either one updates the other and keeps its settings.") {
                SettingRow("Island Lite (easiest)", "Island-Lite-v….apk · installs straight from the browser. Charging, battery, Bluetooth, timers, system events, Glance.", Icons.Rounded.Download, BadgeColors.Green)
                SettingRow("Island (full)", "Island-v….apk · adds music, calls, navigation, downloads and notifications. Android may block it from a browser (Play Protect's enhanced fraud protection), so install it over USB.", Icons.Rounded.Layers, BadgeColors.Indigo)
            }
        }
        item {
            Group(title = "Install on a friend's phone") {
                Step(1, "Open the link or scan the code on their phone.")
                Step(2, "Under Assets, download the Lite APK (or the full one, see below).")
                Step(3, "Open the file and allow the browser to install unknown apps when asked.")
                Step(4, "Open Island, follow the short setup and turn it on.")
            }
        }
        item {
            Group(title = "Full edition over USB", footer = "USB installs keep Play Protect on; it is how Android expects developers to install apps that need notification access.") {
                SettingRow("1. Enable USB debugging", "Settings → About phone → Software information → tap Build number 7 times, then Developer options → USB debugging.", Icons.Rounded.Usb, BadgeColors.Graphite)
                CommandRow(context, "adb install -r Island-v${BuildConfig.VERSION_NAME.substringBefore('-')}.apk")
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CommandRow(context: Context, command: String) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0D0F16))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Terminal, null, tint = Color(0xFF7CF7FF), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            command, color = Color(0xFFE6E8F0), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        )
        FilledTonalIconButton(onClick = { copy(context, "Command", command); copied = true }) {
            Icon(if (copied) Icons.Rounded.NewReleases else Icons.Rounded.ContentCopy, if (copied) "Copied" else "Copy command")
        }
    }
}

/**
 * QR code drawn natively: rounded dots, rounded finder patterns and a tiny island in the centre
 * (error correction H leaves plenty of room for it).
 */
@Composable
fun QrCode(text: String, modifier: Modifier, color: Color = Color.Black) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H),
        )
    }
    Canvas(modifier) {
        val n = matrix.width
        val cell = size.minDimension / n
        val logoCells = (n * 0.24f).roundToInt().let { if (it % 2 == n % 2) it else it + 1 }
        val logoStart = (n - logoCells) / 2
        fun finder(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
        fun logo(x: Int, y: Int) = x in logoStart until logoStart + logoCells && y in logoStart until logoStart + logoCells
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (!matrix[x, y] || finder(x, y) || logo(x, y)) continue
                drawCircle(color, cell * 0.46f, Offset((x + 0.5f) * cell, (y + 0.5f) * cell))
            }
        }
        listOf(0 to 0, n - 7 to 0, 0 to n - 7).forEach { (fx, fy) ->
            val o = Offset(fx * cell, fy * cell)
            drawRoundRect(color, o + Offset(cell * 0.5f, cell * 0.5f), Size(cell * 6f, cell * 6f), CornerRadius(cell * 2f), style = Stroke(cell))
            drawRoundRect(color, o + Offset(cell * 2f, cell * 2f), Size(cell * 3f, cell * 3f), CornerRadius(cell * 1.1f))
        }
        // The island itself as the logo.
        val lw = logoCells * cell * 0.86f
        val lh = lw * 0.36f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawRoundRect(color, Offset(c.x - lw / 2f, c.y - lh / 2f), Size(lw, lh), CornerRadius(lh / 2f))
        drawCircle(Color(0xFF2E3A66), lh * 0.2f, Offset(c.x + lw * 0.22f, c.y))
        drawCircle(Color(0xFF7CF7FF), lh * 0.14f, Offset(c.x - lw * 0.26f, c.y))
    }
}

// -------------------------------------------------------------------------------------------------
// Lite edition
// -------------------------------------------------------------------------------------------------

/** Explains what Lite leaves out and how to move to the full edition without losing settings. */
@Composable
fun LiteCard(ui: Ui, showUpgrade: Boolean = true) {
    InfoCard(
        title = "You're using Island Lite",
        body = "Lite leaves out notification access so it installs from a browser without being blocked. Charging, battery, Bluetooth, timers, system events and Glance all work. Music, calls, navigation and notifications need the full edition, which installs over Lite and keeps your settings.",
        icon = Icons.Rounded.SystemUpdate,
        color = BadgeColors.Violet,
        actionLabel = if (showUpgrade) "Get the full edition" else "Download page",
        onAction = { if (showUpgrade) ui.go(Dest.SHARE) else ui.browse(Links.RELEASES) },
    )
}

// -------------------------------------------------------------------------------------------------
// Updates (About)
// -------------------------------------------------------------------------------------------------

@Composable
fun UpdatesGroup(ui: Ui) {
    Group(title = "Updates", footer = "Island never checks for updates by itself: it has no internet access. These buttons open your browser or Obtainium, a free open-source app that watches GitHub releases for you.") {
        NavRow("Check for updates", "Opens the latest release on GitHub · you have ${BuildConfig.VERSION_NAME}", Icons.Rounded.SystemUpdate, BadgeColors.Green) { ui.browse(Links.RELEASES) }
        NavRow("Automatic updates with Obtainium", "Adds Island to Obtainium in one tap", Icons.Rounded.Download, BadgeColors.Indigo) { ui.browse(Links.OBTAINIUM, Links.OBTAINIUM_SITE) }
        NavRow("What's new", "Play this version's features on a live preview", Icons.Rounded.NewReleases, BadgeColors.Pink) { ui.go(Dest.WHATS_NEW) }
        NavRow("Share with friends", "QR code and install guide", Icons.Rounded.QrCode2, BadgeColors.Violet) { ui.go(Dest.SHARE) }
    }
}

// -------------------------------------------------------------------------------------------------
// Status bar cleanup
// -------------------------------------------------------------------------------------------------

@Composable
fun StatusBarScreen(s: IslandSettings, ui: Ui) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(StatusBarCleanup.canWrite(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        granted = StatusBarCleanup.canWrite(context)
        onPauseOrDispose { }
    }
    val selected = remember(s.statusBarIcons) { s.statusBarIcons.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet() }

    SettingsPage("Status bar cleanup", onBack = ui::back) {
        item {
            InfoCard(
                title = "Experimental",
                body = "Hides status bar icons the island already shows, using Android's own icon_blacklist setting (the one System UI Tuner uses). It needs a one-time permission granted from a computer; Island cannot grant it to itself. Your previous value is saved and restored when you turn this off.",
                icon = Icons.Rounded.VisibilityOff,
                color = BadgeColors.Graphite,
            )
        }
        if (!granted) {
            item {
                Group(title = "One-time setup", footer = "Connect the phone with USB debugging on, then run this once on the computer.") {
                    CommandRow(context, StatusBarCleanup.GRANT_COMMAND)
                }
            }
        }
        item {
            Group {
                SwitchRow("Hide redundant icons", s.statusBarCleanup && granted, { v -> ui.update { it.copy(statusBarCleanup = v) } },
                    if (granted) "Applies immediately" else "Waiting for the permission above", Icons.Rounded.VisibilityOff, BadgeColors.Graphite, enabled = granted)
            }
        }
        item {
            Group(title = "Icons", footer = "Before uninstalling Island, turn this off. If you forget, `adb shell settings delete secure icon_blacklist` brings every icon back.") {
                StatusBarCleanup.ICONS.forEach { (slot, label) ->
                    SwitchRow(label, slot in selected, { on ->
                        val next = if (on) selected + slot else selected - slot
                        ui.update { it.copy(statusBarIcons = StatusBarCleanup.ICONS.keys.filter { k -> k in next }.joinToString(",")) }
                    }, enabled = granted)
                }
            }
        }
    }
}
