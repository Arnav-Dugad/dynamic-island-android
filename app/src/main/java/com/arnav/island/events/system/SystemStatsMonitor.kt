package com.arnav.island.events.system

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.PowerManager
import android.os.SystemClock
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.MonitorPayload
import com.arnav.island.storage.IslandSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Experimental, opt-in device monitor. Runs only while enabled and the screen is on. Every value
 * comes from a public source; anything unavailable (e.g. CPU clocks blocked by SELinux) is null
 * and simply not shown. System-wide CPU *usage* is not readable by apps since Android 8, so
 * current CPU clock is shown instead of a fabricated percentage.
 */
class SystemStatsMonitor(
    private val context: Context,
    private val engine: EventEngine,
    private val scope: CoroutineScope,
    private val settings: () -> IslandSettings,
    private val batteryTemp: () -> Float?,
    private val islandFps: () -> Float?,
) {
    private val activity = context.getSystemService(ActivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var job: Job? = null
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L
    private val cpuMax: Int? by lazy { readMaxFreqMhz() }

    fun setActive(active: Boolean) {
        if (active) {
            if (job?.isActive == true) return
            job = scope.launch {
                while (isActive) {
                    val payload = withContext(Dispatchers.Default) { sample() }
                    engine.post(
                        IslandEvent(
                            id = ID,
                            source = EventSource.MONITOR,
                            type = EventType.MONITOR,
                            timestamp = System.currentTimeMillis(),
                            persistent = true,
                            title = "System",
                            icon = Glyph.CHIP,
                            payload = payload,
                            contentKey = "monitor",
                        )
                    )
                    delay(INTERVAL_MS)
                }
            }
        } else {
            job?.cancel()
            job = null
            lastRx = -1L
            lastTx = -1L
            engine.remove(ID)
        }
    }

    private fun sample(): MonitorPayload {
        val s = settings()
        var ramFraction: Float? = null
        var used: Long? = null
        var total: Long? = null
        if (s.monitorRam) {
            val info = ActivityManager.MemoryInfo()
            activity.getMemoryInfo(info)
            if (info.totalMem > 0) {
                total = info.totalMem
                used = info.totalMem - info.availMem
                ramFraction = used.toFloat() / info.totalMem
            }
        }

        var down: Long? = null
        var up: Long? = null
        if (s.monitorNetwork) {
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val now = SystemClock.elapsedRealtime()
            if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong()) {
                if (lastRx >= 0 && now > lastAt) {
                    val secs = (now - lastAt) / 1000f
                    down = ((rx - lastRx) / secs).toLong().coerceAtLeast(0)
                    up = ((tx - lastTx) / secs).toLong().coerceAtLeast(0)
                }
                lastRx = rx
                lastTx = tx
                lastAt = now
            }
        }

        val thermal = if (s.monitorTemperature) {
            when (power.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> null
            }
        } else {
            null
        }

        return MonitorPayload(
            ramUsedFraction = ramFraction,
            ramUsedBytes = used,
            ramTotalBytes = total,
            netDownBps = down,
            netUpBps = up,
            cpuFreqMhz = if (s.monitorCpu) readCurrentFreqMhz() else null,
            cpuMaxFreqMhz = if (s.monitorCpu) cpuMax else null,
            batteryTempC = if (s.monitorTemperature) batteryTemp() else null,
            thermalStatus = thermal,
            islandFps = if (s.monitorFps) islandFps()?.takeIf { it > 0f } else null,
        )
    }

    /** Highest current core clock, or null when cpufreq is not readable. */
    private fun readCurrentFreqMhz(): Int? {
        var best = -1
        for (i in 0 until 12) {
            val khz = readInt("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq") ?: continue
            if (khz > best) best = khz
        }
        return if (best > 0) best / 1000 else null
    }

    private fun readMaxFreqMhz(): Int? {
        var best = -1
        for (i in 0 until 12) {
            val khz = readInt("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq") ?: continue
            if (khz > best) best = khz
        }
        return if (best > 0) best / 1000 else null
    }

    private fun readInt(path: String): Int? = try {
        File(path).takeIf { it.canRead() }?.readText()?.trim()?.toIntOrNull()
    } catch (_: Exception) {
        null
    }

    companion object {
        const val ID = "monitor"
        private const val INTERVAL_MS = 1_000L
    }
}
