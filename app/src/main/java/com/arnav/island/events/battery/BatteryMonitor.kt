package com.arnav.island.events.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.arnav.island.events.BatteryAlert
import com.arnav.island.events.BatteryPayload
import com.arnav.island.events.ChargingPayload
import com.arnav.island.events.ChargingSpeed
import com.arnav.island.events.CustomPayload
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventColors
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.PlugType
import com.arnav.island.storage.IslandSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Charging, low battery, battery full and Power saving, from public battery broadcasts.
 * Nothing is estimated beyond what BatteryManager reports (see [measureWatts]).
 */
class BatteryMonitor(
    private val context: Context,
    private val engine: EventEngine,
    private val scope: CoroutineScope,
    private val settings: () -> IslandSettings,
) {
    private data class Snapshot(
        val level: Int,
        val plugged: Int,
        val status: Int,
        val temperatureC: Float?,
        val voltageMv: Int?,
    ) {
        val isPlugged get() = plugged != 0
        val isCharging get() = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val isFull get() = status == BatteryManager.BATTERY_STATUS_FULL
    }

    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var last: Snapshot? = null
    private var connectJob: Job? = null
    private var speedJob: Job? = null
    private var historyJob: Job? = null

    /** Measured charging power since the charger was connected (W), oldest first. */
    private val powerHistory = ArrayDeque<Float>(HISTORY_POINTS)
    private var speed: ChargingSpeed? = null
    private var lowAnnounced = false
    private var fullAnnounced = false
    private var coachAnnounced = false
    private var lastConnectAt = 0L
    private var registered = false

    /** Latest battery level, for the monitor and HUD. */
    val level: Int? get() = last?.level
    val temperatureC: Float? get() = last?.temperatureC
    val isCharging: Boolean get() = last?.isCharging == true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> onBatteryChanged(intent)
                Intent.ACTION_POWER_CONNECTED -> onConnected()
                Intent.ACTION_POWER_DISCONNECTED -> onDisconnected()
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> onPowerSaveChanged()
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val sticky = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        last = sticky?.let(::parse)
        if (last?.isPlugged == true) {
            fullAnnounced = last?.isFull == true
            // Already past the limit when Island starts: don't nag straight away.
            coachAnnounced = (last?.level ?: 0) >= settings().chargeLimit
            recordPower()
        }
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
        connectJob?.cancel()
        speedJob?.cancel()
        historyJob?.cancel()
        powerHistory.clear()
        engine.remove(ID_TOAST)
        engine.remove(ID_LIVE)
    }

    /** Re-applies settings (e.g. the live charging activity was toggled). */
    fun refresh() {
        val snap = last ?: return
        if (snap.isPlugged && settings().chargingLiveActivity && settings().chargingEnabled) postLive() else engine.remove(ID_LIVE)
    }

    private fun parse(intent: Intent): Snapshot {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level >= 0) (level * 100f / scale).toInt().coerceIn(0, 100) else 0
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val volt = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        return Snapshot(
            level = pct,
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
            temperatureC = if (temp != Int.MIN_VALUE && temp > -400) temp / 10f else null,
            voltageMv = volt.takeIf { it in 2_500..5_500 },
        )
    }

    private fun onBatteryChanged(intent: Intent) {
        val snap = parse(intent)
        val prev = last
        last = snap
        if (prev == null) return
        val s = settings()

        if (!prev.isPlugged && snap.isPlugged && connectJob == null && System.currentTimeMillis() - lastConnectAt > 3_000) {
            // Some devices skip ACTION_POWER_CONNECTED; the plug transition is enough.
            onConnected()
        }
        if (snap.isPlugged) {
            if (engine.current().toast?.id == ID_TOAST) postCharging(entrance = false)
            if (s.chargingLiveActivity && s.chargingEnabled) postLive()
        }

        if (s.batteryLowEnabled && !snap.isPlugged && snap.level <= s.batteryLowThreshold && !lowAnnounced && prev.level > snap.level) {
            lowAnnounced = true
            postAlert(BatteryAlert.LOW, snap)
        }
        if (snap.isPlugged || snap.level > s.batteryLowThreshold + 2) lowAnnounced = false

        if (s.batteryFullEnabled && snap.isPlugged && snap.isFull && !fullAnnounced) {
            fullAnnounced = true
            postAlert(BatteryAlert.FULL, snap)
        }

        // Charge limit coach: a gentle nudge once, only while the phone is still actually charging
        // past the limit (One UI's own battery protection pauses charging, so it stays quiet then).
        if (s.chargeCoach && snap.isPlugged && snap.status == BatteryManager.BATTERY_STATUS_CHARGING && snap.level >= s.chargeLimit && !coachAnnounced) {
            coachAnnounced = true
            postCoach(snap)
        }
    }

    private fun onConnected() {
        lastConnectAt = System.currentTimeMillis()
        if (!settings().chargingEnabled) return
        connectJob?.cancel()
        connectJob = scope.launch {
            // Give ACTION_BATTERY_CHANGED a moment to report the plug type.
            delay(CONNECT_SETTLE_MS)
            connectJob = null
            speed = null
            powerHistory.clear()
            postCharging(entrance = true)
            sampleSpeed()
            recordPower()
        }
    }

    private fun onDisconnected() {
        connectJob?.cancel()
        connectJob = null
        speedJob?.cancel()
        historyJob?.cancel()
        powerHistory.clear()
        speed = null
        fullAnnounced = false
        coachAnnounced = false
        engine.remove(ID_TOAST)
        engine.remove(ID_LIVE)
    }

    private fun onPowerSaveChanged() {
        if (!settings().batteryLowEnabled) return
        val snap = last ?: return
        postAlert(if (power.isPowerSaveMode) BatteryAlert.SAVER_ON else BatteryAlert.SAVER_OFF, snap)
    }

    private fun chargingPayload(snap: Snapshot, entrance: Boolean): ChargingPayload {
        val s = settings()
        val remaining = batteryManager.computeChargeTimeRemaining().takeIf { it > 0 }
        return ChargingPayload(
            level = snap.level,
            plugType = plugType(snap.plugged),
            isCharging = snap.isCharging,
            isFull = snap.isFull,
            speed = speed,
            temperatureC = if (s.batteryShowTemperature) snap.temperatureC else null,
            voltageMv = snap.voltageMv,
            timeToFullMs = remaining,
            theme = s.chargingTheme,
            countFrom = if (entrance) max(0, snap.level - COUNT_UP_SPAN) else snap.level,
            powerHistory = powerHistory.toList(),
        )
    }

    private fun postCharging(entrance: Boolean) {
        val snap = last ?: return
        if (!snap.isPlugged) return
        val s = settings()
        engine.post(
            IslandEvent(
                id = ID_TOAST,
                source = EventSource.BATTERY,
                type = EventType.CHARGING,
                timestamp = System.currentTimeMillis(),
                persistent = false,
                durationMs = s.chargingDurationMs,
                title = "Charging",
                icon = Glyph.BOLT,
                payload = chargingPayload(snap, entrance),
                animation = EntranceAnimation.POP,
                contentKey = "charging",
            )
        )
    }

    private fun postLive() {
        val snap = last ?: return
        engine.post(
            IslandEvent(
                id = ID_LIVE,
                source = EventSource.BATTERY,
                type = EventType.CHARGING,
                priority = com.arnav.island.events.EventPriority.BACKGROUND_ACTIVITY,
                timestamp = System.currentTimeMillis(),
                persistent = true,
                title = "Charging",
                icon = Glyph.BOLT,
                payload = chargingPayload(snap, entrance = false),
                contentKey = "charging-live",
            )
        )
    }

    private fun postAlert(alert: BatteryAlert, snap: Snapshot) {
        engine.post(
            IslandEvent(
                id = "battery:${alert.name.lowercase()}",
                source = EventSource.BATTERY,
                type = if (alert == BatteryAlert.LOW) EventType.BATTERY_LOW else EventType.BATTERY_FULL,
                timestamp = System.currentTimeMillis(),
                persistent = false,
                durationMs = if (alert == BatteryAlert.LOW) 4_000 else 3_000,
                title = alert.name,
                icon = Glyph.BATTERY,
                payload = BatteryPayload(snap.level, alert, snap.isCharging),
                animation = EntranceAnimation.POP,
            )
        )
    }

    /**
     * Samples charging power a few times after connection. Only labels "Fast" when the public
     * CURRENT_NOW and voltage readings are plausible and consistently high.
     */
    private fun sampleSpeed() {
        speedJob?.cancel()
        speedJob = scope.launch {
            val samples = ArrayList<Float>(4)
            repeat(4) {
                delay(SAMPLE_INTERVAL_MS)
                val snap = last ?: return@launch
                if (!snap.isPlugged || snap.status != BatteryManager.BATTERY_STATUS_CHARGING) return@launch
                val watts = measureWatts(snap) ?: return@launch
                samples += watts
                if (samples.size >= 2) {
                    val avg = (samples[samples.size - 1] + samples[samples.size - 2]) / 2f
                    val detected = when {
                        avg >= SUPER_FAST_WATTS -> ChargingSpeed.SUPER_FAST
                        avg >= FAST_WATTS -> ChargingSpeed.FAST
                        else -> null
                    }
                    if (detected != null && detected != speed) {
                        speed = detected
                        if (engine.current().toast?.id == ID_TOAST) postCharging(entrance = false)
                    }
                }
            }
        }
    }

    private fun postCoach(snap: Snapshot) {
        engine.post(
            IslandEvent(
                id = "battery:coach",
                source = EventSource.BATTERY,
                type = EventType.CUSTOM,
                timestamp = System.currentTimeMillis(),
                persistent = false,
                durationMs = 5_000,
                title = "${snap.level}% charged",
                subtitle = "Unplug now to go easy on the battery",
                icon = Glyph.LEAF,
                colors = EventColors(accent = 0xFF3DDC84.toInt()),
                animation = EntranceAnimation.POP,
                payload = CustomPayload("Battery", null),
            )
        )
    }

    /**
     * Keeps a short rolling record of real charging power for the graph in the expanded card.
     * Stops on its own when charging pauses or the charger is removed.
     */
    private fun recordPower() {
        historyJob?.cancel()
        historyJob = scope.launch {
            while (true) {
                delay(HISTORY_INTERVAL_MS)
                val snap = last ?: return@launch
                if (!snap.isPlugged) return@launch
                // Charging paused (battery protection, full): record zero, it is what happens.
                val watts = if (snap.status == BatteryManager.BATTERY_STATUS_CHARGING) measureWatts(snap) ?: continue else 0f
                if (powerHistory.size == HISTORY_POINTS) powerHistory.removeFirst()
                powerHistory.addLast(watts)
                val s = settings()
                if (s.chargingLiveActivity && s.chargingEnabled) postLive()
            }
        }
    }

    private fun measureWatts(snap: Snapshot): Float? {
        val raw = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Int.MIN_VALUE || raw == 0) return null
        val magnitude = abs(raw)
        // Documented unit is microamps; some devices report milliamps. Values under 20 000 can
        // only plausibly be mA while charging.
        val amps = if (magnitude < 20_000) magnitude / 1_000f else magnitude / 1_000_000f
        val volts = (snap.voltageMv ?: return null) / 1_000f
        if (volts !in 3f..5f || amps !in 0.05f..8f) return null
        return amps * volts
    }

    private fun plugType(plugged: Int): PlugType = when {
        plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> PlugType.WIRELESS
        plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> PlugType.AC
        plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> PlugType.USB
        plugged and BATTERY_PLUGGED_DOCK != 0 -> PlugType.DOCK
        plugged == 0 -> PlugType.NONE
        else -> PlugType.UNKNOWN
    }

    companion object {
        const val ID_TOAST = "charging"
        const val ID_LIVE = "charging.live"
        private const val BATTERY_PLUGGED_DOCK = 8
        private const val CONNECT_SETTLE_MS = 320L
        private const val SAMPLE_INTERVAL_MS = 650L
        private const val COUNT_UP_SPAN = 16
        private const val FAST_WATTS = 12f
        private const val SUPER_FAST_WATTS = 22f
        private const val HISTORY_POINTS = 48
        private const val HISTORY_INTERVAL_MS = 7_500L
    }
}
