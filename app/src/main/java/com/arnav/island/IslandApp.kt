package com.arnav.island

import android.app.Application
import android.provider.Settings
import com.arnav.island.core.AppGraph
import com.arnav.island.events.timer.TimerNotifications
import com.arnav.island.overlay.IslandOverlayService
import com.arnav.island.util.Diagnostics
import kotlinx.coroutines.launch

class IslandApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        Diagnostics.installCrashHandler(this)
        graph = AppGraph(this)
        IslandOverlayService.ensureChannel(this)
        TimerNotifications.ensureChannel(this)

        // Process recreated (crash, update, low-memory kill): bring the island back if enabled.
        graph.scope.launch {
            val s = graph.settings.current()
            if (s.enabled && Settings.canDrawOverlays(this@IslandApp)) {
                IslandOverlayService.start(this@IslandApp)
            }
        }
    }
}
