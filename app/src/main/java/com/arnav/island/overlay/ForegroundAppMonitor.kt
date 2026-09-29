package com.arnav.island.overlay

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Knows which app is in front, using Usage Access (a special permission the user grants in
 * Settings). Only needed for per-app rules and Game Mode; it polls only while those features are
 * configured and the screen is on, and it is entirely inert without the permission.
 */
class ForegroundAppMonitor(private val context: Context, private val scope: CoroutineScope) {

    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()
    private var job: Job? = null
    private var lastQuery = 0L

    fun hasPermission(): Boolean = hasUsageAccess(context)

    fun setActive(active: Boolean) {
        if (active && hasPermission()) {
            if (job?.isActive == true) return
            job = scope.launch {
                while (isActive) {
                    val pkg = withContext(Dispatchers.Default) { query() }
                    if (pkg != null) _current.value = pkg
                    delay(POLL_MS)
                }
            }
        } else {
            job?.cancel()
            job = null
            if (!active) _current.value = null
        }
    }

    private fun query(): String? {
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L) now - 60_000 else lastQuery - 2_000
        lastQuery = now
        val events = try {
            usage.queryEvents(from, now)
        } catch (_: SecurityException) {
            return null
        } ?: return null
        val event = UsageEvents.Event()
        var latest: String? = null
        var latestTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= latestTime) {
                latestTime = event.timeStamp
                latest = event.packageName
            }
        }
        return latest
    }

    companion object {
        const val POLL_MS = 2_500L

        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val op = AppOpsManager.OPSTR_GET_USAGE_STATS
            val mode = if (Build.VERSION.SDK_INT >= 36) {
                ops.checkOpNoThrow(op, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
