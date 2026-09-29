package com.arnav.island.settings.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.BuildConfig
import com.arnav.island.core.AppGraph
import com.arnav.island.overlay.IslandOverlayService
import com.arnav.island.permissions.PermissionSnapshot
import com.arnav.island.permissions.Permissions
import com.arnav.island.settings.onboarding.OnboardingScreen
import com.arnav.island.settings.ui.screens.AboutScreen
import com.arnav.island.settings.ui.screens.AdvancedScreen
import com.arnav.island.settings.ui.screens.AppearanceScreen
import com.arnav.island.settings.ui.screens.AppsScreen
import com.arnav.island.settings.ui.screens.BatteryScreen
import com.arnav.island.settings.ui.screens.BlendScreen
import com.arnav.island.settings.ui.screens.BluetoothScreen
import com.arnav.island.settings.ui.screens.CalibrationScreen
import com.arnav.island.settings.ui.screens.ChargingScreen
import com.arnav.island.settings.ui.screens.DeveloperScreen
import com.arnav.island.settings.ui.screens.DiagnosticsScreen
import com.arnav.island.settings.ui.screens.EventsScreen
import com.arnav.island.settings.ui.screens.GalleryScreen
import com.arnav.island.settings.ui.screens.GesturesScreen
import com.arnav.island.settings.ui.screens.HomeScreen
import com.arnav.island.settings.ui.screens.IslandScreen
import com.arnav.island.settings.ui.screens.IslandStudioScreen
import com.arnav.island.settings.ui.screens.MediaScreen
import com.arnav.island.settings.ui.screens.NotificationsScreen
import com.arnav.island.settings.ui.screens.PerformanceScreen
import com.arnav.island.settings.ui.screens.PrivacyScreen
import com.arnav.island.settings.ui.screens.ShareScreen
import com.arnav.island.settings.ui.screens.StatusBarScreen
import com.arnav.island.settings.ui.screens.TimersScreen
import com.arnav.island.settings.ui.screens.WhatsNewScreen
import com.arnav.island.storage.IslandSettings
import kotlinx.coroutines.launch

enum class Dest {
    HOME, ISLAND, CALIBRATION, EVENTS, APPS, MEDIA, NOTIFICATIONS, BATTERY, CHARGING, BLUETOOTH, TIMERS,
    GESTURES, APPEARANCE, PERFORMANCE, PRIVACY, ADVANCED, DEVELOPER, ABOUT,
    STUDIO, WHATS_NEW, SHARE, STATUS_BAR, BLEND, GALLERY, DIAGNOSTICS,
}

/** Everything a screen needs besides the settings snapshot. */
@Stable
class Ui(
    val graph: AppGraph,
    private val context: Context,
    private val navigate: (Dest) -> Unit,
    private val goBack: () -> Unit,
) {
    fun go(dest: Dest) = navigate(dest)
    fun back() = goBack()

    fun update(transform: (IslandSettings) -> IslandSettings) = graph.settings.set(transform)

    /** Opens a system settings screen, falling back when an OEM does not support the deep link. */
    fun open(intent: Intent, fallback: Intent? = null) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            fallback?.let {
                try {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                }
            }
        } catch (_: SecurityException) {
        }
    }

    /** Turns the island on/off. Returns false when overlay permission is missing. */
    fun setEnabled(enabled: Boolean): Boolean {
        if (enabled && !Permissions.snapshot(context).overlay) {
            open(Permissions.overlaySettings(context))
            return false
        }
        update { it.copy(enabled = enabled) }
        if (enabled) IslandOverlayService.start(context) else IslandOverlayService.stop(context)
        return true
    }
}

/** Permission snapshot, refreshed whenever the app resumes (e.g. back from system settings). */
@Composable
fun rememberPermissions(): State<PermissionSnapshot> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(Permissions.snapshot(context)) }
    LifecycleResumeEffect(Unit) {
        state.value = Permissions.snapshot(context)
        onPauseOrDispose { }
    }
    return state
}

@Composable
fun IslandRoot(graph: AppGraph, initial: Dest?, sharedSetup: String? = null, onSetupHandled: () -> Unit = {}) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val loaded by graph.settings.loaded.collectAsStateWithLifecycle()
    val context = LocalContext.current

    if (!loaded) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    if (!settings.onboardingDone) {
        OnboardingScreen(graph, settings) { graph.settings.set { it.copy(onboardingDone = true, lastSeenVersion = BuildConfig.VERSION_CODE) } }
        return
    }

    var stack by rememberSaveable { mutableStateOf(listOf(Dest.HOME)) }
    var forward by remember { mutableStateOf(true) }
    LaunchedEffect(initial) {
        if (initial != null && initial != Dest.HOME) stack = listOf(Dest.HOME, initial)
    }
    // After an update, show what's new once.
    LaunchedEffect(settings.lastSeenVersion) {
        if (settings.lastSeenVersion < BuildConfig.VERSION_CODE) {
            graph.settings.set { it.copy(lastSeenVersion = BuildConfig.VERSION_CODE) }
            forward = true
            stack = listOf(Dest.HOME, Dest.WHATS_NEW)
        }
    }
    val ui = remember {
        Ui(
            graph,
            context.applicationContext,
            navigate = { d ->
                forward = true
                stack = stack + d
            },
            goBack = {
                if (stack.size > 1) {
                    forward = false
                    stack = stack.dropLast(1)
                }
            },
        )
    }

    // Resume the overlay if it should be running (e.g. permission granted in system settings).
    LifecycleResumeEffect(settings.enabled) {
        if (settings.enabled && !IslandOverlayService.running.value) IslandOverlayService.start(context)
        onPauseOrDispose { }
    }

    BackHandler(enabled = stack.size > 1) { ui.back() }

    // A friend's setup opened from a link or QR code: confirm before applying anything.
    if (sharedSetup != null) {
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = onSetupHandled,
            title = { Text("Use a friend's setup?") },
            text = { Text("Their island size, motion, theme and behaviour replace yours. Your camera calibration, permissions and app rules stay as they are.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { graph.settings.importSetup(sharedSetup) }
                    onSetupHandled()
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = onSetupHandled) { Text("Cancel") } },
        )
    }
    val permissions by rememberPermissions()

    AnimatedContent(
        targetState = stack.last(),
        transitionSpec = {
            val duration = 340
            if (forward) {
                (slideInHorizontally(tween(duration)) { it / 5 } + fadeIn(tween(duration))) togetherWith
                    (slideOutHorizontally(tween(duration)) { -it / 10 } + fadeOut(tween(duration / 2)))
            } else {
                (slideInHorizontally(tween(duration)) { -it / 10 } + fadeIn(tween(duration))) togetherWith
                    (slideOutHorizontally(tween(duration)) { it / 5 } + fadeOut(tween(duration / 2)))
            }
        },
        label = "nav",
    ) { dest ->
        when (dest) {
            Dest.HOME -> HomeScreen(settings, permissions, ui)
            Dest.ISLAND -> IslandScreen(settings, ui)
            Dest.CALIBRATION -> CalibrationScreen(settings, ui)
            Dest.EVENTS -> EventsScreen(settings, permissions, ui)
            Dest.APPS -> AppsScreen(settings, permissions, ui)
            Dest.MEDIA -> MediaScreen(settings, permissions, ui)
            Dest.NOTIFICATIONS -> NotificationsScreen(settings, permissions, ui)
            Dest.BATTERY -> BatteryScreen(settings, ui)
            Dest.CHARGING -> ChargingScreen(settings, ui)
            Dest.BLUETOOTH -> BluetoothScreen(settings, permissions, ui)
            Dest.TIMERS -> TimersScreen(settings, permissions, ui)
            Dest.GESTURES -> GesturesScreen(settings, ui)
            Dest.APPEARANCE -> AppearanceScreen(settings, ui)
            Dest.PERFORMANCE -> PerformanceScreen(settings, ui)
            Dest.PRIVACY -> PrivacyScreen(settings, ui)
            Dest.ADVANCED -> AdvancedScreen(settings, ui)
            Dest.DEVELOPER -> DeveloperScreen(settings, ui)
            Dest.ABOUT -> AboutScreen(ui)
            Dest.STUDIO -> IslandStudioScreen(settings, ui)
            Dest.WHATS_NEW -> WhatsNewScreen(settings, ui)
            Dest.SHARE -> ShareScreen(ui)
            Dest.STATUS_BAR -> StatusBarScreen(settings, ui)
            Dest.BLEND -> BlendScreen(settings, ui)
            Dest.GALLERY -> GalleryScreen(settings, ui)
            Dest.DIAGNOSTICS -> DiagnosticsScreen(settings, ui)
        }
    }
}
