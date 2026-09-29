package com.arnav.island.events.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.arnav.island.events.BluetoothDeviceKind
import com.arnav.island.events.BluetoothPayload
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.storage.IslandSettings

/**
 * Bluetooth connect/disconnect toasts. Requires the "Nearby devices" (BLUETOOTH_CONNECT)
 * permission on Android 12+, which the user grants only if they enable this feature.
 */
class BluetoothMonitor(
    private val context: Context,
    private val engine: EventEngine,
    private val settings: () -> IslandSettings,
) {
    private val lastShown = HashMap<String, Long>()
    private val batteryLevels = HashMap<String, Int>()
    private var registered = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (!hasPermission()) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> device(intent)?.let { onConnection(it, connected = true) }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> device(intent)?.let { onConnection(it, connected = false) }
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    val device = device(intent) ?: return
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    if (level in 0..100) {
                        batteryLevels[device.address] = level
                        // Refresh the toast in place if it is on screen.
                        if (engine.current().toast?.id == eventId(device)) onConnection(device, connected = true, refreshOnly = true)
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) batteryLevels.clear()
                }
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // Sent by the Bluetooth stack (not the system uid) as protected broadcasts.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }

    private fun device(intent: Intent): BluetoothDevice? =
        IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

    private fun eventId(device: BluetoothDevice) = "bt:${device.address}"

    @SuppressLint("MissingPermission")
    private fun onConnection(device: BluetoothDevice, connected: Boolean, refreshOnly: Boolean = false) {
        val s = settings()
        if (!s.bluetoothEnabled) return
        val name = try {
            device.alias ?: device.name
        } catch (_: SecurityException) {
            null
        } ?: return
        val kind = classify(device, name)
        val interesting = kind in setOf(
            BluetoothDeviceKind.HEADPHONES, BluetoothDeviceKind.EARBUDS, BluetoothDeviceKind.WATCH,
            BluetoothDeviceKind.SPEAKER, BluetoothDeviceKind.CAR,
        )
        if (!interesting && !s.bluetoothAllDevices) return

        val now = System.currentTimeMillis()
        val key = "${device.address}:$connected"
        if (!refreshOnly) {
            val last = lastShown[key] ?: 0L
            if (now - last < DEBOUNCE_MS) return
            lastShown[key] = now
        }
        if (!connected) batteryLevels.remove(device.address)

        engine.post(
            IslandEvent(
                id = eventId(device),
                source = EventSource.BLUETOOTH,
                type = EventType.BLUETOOTH,
                timestamp = now,
                persistent = false,
                durationMs = if (connected) 3_200 else 2_200,
                title = name,
                subtitle = if (connected) "Connected" else "Disconnected",
                icon = Glyph.BLUETOOTH,
                payload = BluetoothPayload(name, kind, connected, if (connected) batteryLevels[device.address] else null),
                animation = EntranceAnimation.POP,
                contentKey = "$connected",
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun classify(device: BluetoothDevice, name: String): BluetoothDeviceKind {
        val lower = name.lowercase()
        val cls: BluetoothClass? = try {
            device.bluetoothClass
        } catch (_: SecurityException) {
            null
        }
        val major = cls?.majorDeviceClass ?: BluetoothClass.Device.Major.UNCATEGORIZED
        val dev = cls?.deviceClass ?: 0
        val looksLikeEarbuds = listOf("buds", "pods", "earbud", "tws", "nothing ear", "ear (").any { it in lower }
        return when {
            dev == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET || dev == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ->
                if (looksLikeEarbuds) BluetoothDeviceKind.EARBUDS else BluetoothDeviceKind.HEADPHONES
            dev == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> if (looksLikeEarbuds) BluetoothDeviceKind.EARBUDS else BluetoothDeviceKind.HEADPHONES
            dev == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> BluetoothDeviceKind.CAR
            dev == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER ||
                dev == BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO ||
                dev == BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> BluetoothDeviceKind.SPEAKER
            major == BluetoothClass.Device.Major.WEARABLE || "watch" in lower -> BluetoothDeviceKind.WATCH
            major == BluetoothClass.Device.Major.AUDIO_VIDEO -> if (looksLikeEarbuds) BluetoothDeviceKind.EARBUDS else BluetoothDeviceKind.HEADPHONES
            looksLikeEarbuds -> BluetoothDeviceKind.EARBUDS
            major == BluetoothClass.Device.Major.PHONE -> BluetoothDeviceKind.PHONE
            major == BluetoothClass.Device.Major.COMPUTER -> BluetoothDeviceKind.COMPUTER
            major == BluetoothClass.Device.Major.PERIPHERAL -> BluetoothDeviceKind.INPUT
            else -> BluetoothDeviceKind.GENERIC
        }
    }

    companion object {
        /** Broadcast by the Bluetooth stack when a headset reports its battery (best effort). */
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        private const val DEBOUNCE_MS = 6_000L
    }
}
