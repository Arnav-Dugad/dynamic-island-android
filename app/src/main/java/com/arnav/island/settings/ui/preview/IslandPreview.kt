package com.arnav.island.settings.ui.preview

import android.animation.ValueAnimator
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arnav.island.core.AppGraph
import com.arnav.island.events.EventEngine
import com.arnav.island.events.test.TestEvents
import com.arnav.island.island.IslandController
import com.arnav.island.island.IslandGeometry
import com.arnav.island.island.ScreenSpec
import com.arnav.island.island.render.IslandView
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RenderSettings
import com.arnav.island.storage.IslandSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * A self-contained island (own engine, controller and view) rendered with the exact same code as
 * the real overlay, against a virtual camera. Used on the home screen, onboarding and anywhere a
 * live preview helps.
 */
class PreviewIsland(context: Context, graph: AppGraph) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val engine = EventEngine(scope)
    val rc = RenderContext(context, graph.images, graph.apps)
    val view = IslandView(context, rc, overlayMode = false)
    val controller = IslandController(scope, engine, view)
    val tests = TestEvents(engine, graph.images, scope)
    private var size = IntSize.Zero
    private var settings = IslandSettings()
    private var laidOut = false

    fun start() {
        controller.start()
    }

    fun stop() {
        controller.stop()
        scope.cancel()
    }

    fun resize(newSize: IntSize) {
        if (newSize == size || newSize.width <= 0) return
        size = newSize
        layout()
    }

    fun apply(s: IslandSettings) {
        val geometryChanged = s.geometryConfig() != settings.geometryConfig()
        settings = s
        rc.settings = RenderSettings(
            theme = s.islandTheme,
            motion = s.motionProfile(systemReduceMotion = !ValueAnimator.areAnimatorsEnabled()),
            performance = s.performanceMode,
            waveform = s.mediaWaveform,
            compactMediaProgress = s.mediaCompactProgress,
            tintFromArtwork = s.tintFromArtwork,
            touchDeformation = s.touchDeformation,
            haptics = s.haptics,
            burnIn = false,
            notificationStyle = s.notificationStyle,
            swipeToDismiss = s.swipeToDismiss,
            swipeDownExpands = s.swipeDownExpands,
        )
        controller.updateSettings(s.copy(burnInProtection = false))
        if (geometryChanged && laidOut) layout()
        view.requestFrame()
    }

    private fun layout() {
        if (size.width <= 0) return
        val density = rc.density
        val screen = ScreenSpec(
            widthPx = size.width,
            heightPx = maxOf(size.height, size.width * 2),
            density = density,
            statusBarHeightPx = (30 * density).toInt(),
            displayCornerRadiusPx = 40 * density,
        )
        val cutout = IslandGeometry.virtualCutout(size.width, density, centerYDp = PREVIEW_CAMERA_Y_DP, radiusDp = PREVIEW_CAMERA_RADIUS_DP)
        // Pixel offsets are specific to the real display, so the preview ignores them.
        rc.geometry = IslandGeometry(screen, cutout, settings.geometryConfig().copy(offsetXPx = 0f, offsetYPx = 0f))
        rc.statusBarBottom = screen.statusBarHeightPx.toFloat()
        view.touchExtension = rc.geometry.touchExtension
        if (!laidOut) {
            laidOut = true
            controller.setVisibility(true, null, immediate = true)
        } else {
            view.refreshGeometry(immediate = false)
        }
    }

    /** A looping, choreographed tour of the island's states. */
    suspend fun runDemo() {
        delay(700)
        while (true) {
            tests.music(true)
            delay(3_400)
            tests.charging()
            delay(4_300)
            tests.timer(4)
            delay(3_200)
            controller.expand("test:media")
            delay(3_600)
            controller.collapse()
            delay(1_400)
            tests.notification()
            delay(5_200)
            engine.remove("test:timer")
            delay(1_600)
            tests.bluetooth()
            delay(3_800)
            tests.ringer()
            delay(2_600)
            tests.clear()
            delay(2_000)
        }
    }
}

@Composable
fun IslandPreview(
    graph: AppGraph,
    settings: IslandSettings,
    modifier: Modifier = Modifier,
    demo: Boolean = false,
    onReady: (PreviewIsland) -> Unit = {},
) {
    val context = LocalContext.current
    val preview = remember { PreviewIsland(context, graph) }
    val latestSettings by rememberUpdatedState(settings)
    DisposableEffect(preview) {
        preview.start()
        preview.apply(latestSettings)
        onReady(preview)
        onDispose { preview.stop() }
    }
    LaunchedEffect(settings) { preview.apply(settings) }
    LaunchedEffect(demo) { if (demo) preview.runDemo() }

    Box(
        modifier
            .clip(RoundedCornerShape(36.dp))
            .background(
                Brush.verticalGradient(listOf(Color(0xFF2A2F55), Color(0xFF151731), Color(0xFF0A0B14)))
            ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0x40FF8CC6), Color.Transparent), radius = 700f)),
        )
        StatusBarMock(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 9.dp))
        AndroidView(
            factory = { preview.view },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged(preview::resize),
        )
        CameraLens(Modifier.fillMaxSize())
    }
}

/** The physical lens sits in front of the display, so it is drawn above the island. */
@Composable
private fun CameraLens(modifier: Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, PREVIEW_CAMERA_Y_DP.dp.toPx())
        val r = PREVIEW_CAMERA_RADIUS_DP.dp.toPx()
        drawCircle(Color(0xFF050608), r, center)
        drawCircle(Color(0xFF14192A), r * 0.62f, center)
        drawCircle(Color(0xFF232C4A), r * 0.36f, center)
        drawCircle(Color(0x66FFFFFF), r * 0.16f, center + Offset(-r * 0.28f, -r * 0.28f))
    }
}

private const val PREVIEW_CAMERA_Y_DP = 15f
private const val PREVIEW_CAMERA_RADIUS_DP = 5.2f

@Composable
private fun StatusBarMock(modifier: Modifier) {
    val time by produceState(initialValue = now()) {
        while (true) {
            delay(15_000)
            value = now()
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(time, color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        val tint = Color.White.copy(alpha = 0.9f)
        Icon(Icons.Rounded.SignalCellularAlt, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.Wifi, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.BatteryFull, null, tint = tint, modifier = Modifier.size(15.dp))
    }
}

private fun now(): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()).replace(" ", " ")
