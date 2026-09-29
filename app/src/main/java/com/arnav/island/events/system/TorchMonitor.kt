package com.arnav.island.events.system

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandAction
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.SeekAction
import com.arnav.island.events.TorchPayload
import com.arnav.island.island.render.presenters.TorchPresenter
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Diagnostics
import kotlin.math.roundToInt

/**
 * Flashlight as a live activity. Torch state comes from CameraManager's torch callback, and the
 * brightness steps from the flash unit's own strength range (Android 13+). Neither needs the
 * camera permission; the camera itself is never opened.
 */
class TorchMonitor(
    private val context: Context,
    private val engine: EventEngine,
    private val settings: () -> IslandSettings,
) {
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private val on = HashMap<String, Boolean>()

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            on[cameraId] = enabled
            update(cameraId)
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            on[cameraId] = false
            update(cameraId)
        }

        override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) = update(cameraId)
    }

    fun start() {
        if (registered || cameras == null) return
        cameras.registerTorchCallback(callback, handler)
        registered = true
    }

    fun stop() {
        if (!registered) return
        cameras.unregisterTorchCallback(callback)
        registered = false
        on.clear()
        engine.remove(ID)
    }

    fun refresh() {
        if (!settings().torchEnabled) engine.remove(ID) else on.keys.forEach(::update)
    }

    private fun update(cameraId: String) {
        if (!settings().enabled || !settings().torchEnabled || on[cameraId] != true) {
            if (on.values.none { it }) engine.remove(ID)
            return
        }
        val max = maxLevel(cameraId)
        val level = if (Build.VERSION.SDK_INT >= 33 && max > 1) {
            try {
                cameras.getTorchStrengthLevel(cameraId)
            } catch (e: CameraAccessException) {
                max
            }
        } else {
            1
        }
        val setLevel = if (Build.VERSION.SDK_INT >= 33 && max > 1) {
            SeekAction { f -> setStrength(cameraId, (f * max).roundToInt().coerceIn(1, max)) }
        } else {
            null
        }
        val off = IslandAction(TorchPresenter.ACTION_OFF, "Turn off", Glyph.CLOSE, collapses = true) { turnOff(cameraId) }
        engine.post(
            IslandEvent(
                id = ID,
                source = EventSource.SYSTEM,
                type = EventType.TORCH,
                timestamp = System.currentTimeMillis(),
                persistent = true,
                title = "Flashlight",
                icon = Glyph.FLASHLIGHT,
                actions = listOf(off),
                animation = EntranceAnimation.POP,
                contentKey = "torch",
                payload = TorchPayload(level.coerceIn(1, max.coerceAtLeast(1)), max.coerceAtLeast(1), setLevel),
            )
        )
    }

    private fun maxLevel(cameraId: String): Int {
        if (Build.VERSION.SDK_INT < 33) return 1
        return try {
            cameras.getCameraCharacteristics(cameraId).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        } catch (e: CameraAccessException) {
            1
        } catch (e: IllegalArgumentException) {
            1
        }
    }

    private fun setStrength(cameraId: String, level: Int) {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            cameras.turnOnTorchWithStrengthLevel(cameraId, level)
        } catch (e: CameraAccessException) {
            Diagnostics.w(TAG, "Torch strength change refused", e)
        } catch (e: IllegalArgumentException) {
            Diagnostics.w(TAG, "Torch strength out of range", e)
        }
    }

    private fun turnOff(cameraId: String) {
        try {
            cameras.setTorchMode(cameraId, false)
        } catch (e: CameraAccessException) {
            Diagnostics.w(TAG, "Torch could not be turned off", e)
        }
    }

    companion object {
        const val ID = "torch"
        private const val TAG = "IslandTorch"
    }
}
