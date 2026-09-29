package com.arnav.island.overlay

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Tilt depth: small parallax offsets from how the phone moves away from its resting pose. The
 * resting pose is a slow average of gravity, so holding the phone still always drifts back to
 * zero. Only listens while an open card is on screen.
 */
class TiltSensor(context: Context, private val onTilt: (dx: Float, dy: Float) -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gravity: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var listening = false
    private var restX = 0f
    private var restY = 0f
    private var fresh = true

    /** Maximum offset in pixels at full tilt. */
    var maxOffsetPx = 0f

    fun setActive(active: Boolean) {
        if (active == listening || gravity == null) return
        listening = active
        if (active) {
            fresh = true
            sensors?.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sensors?.unregisterListener(this)
            onTilt(0f, 0f)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        if (fresh) {
            restX = gx
            restY = gy
            fresh = false
        }
        // Resting pose follows slowly (about a second), so only movement produces depth.
        restX += (gx - restX) * REST_FOLLOW
        restY += (gy - restY) * REST_FOLLOW
        val nx = ((gx - restX) / FULL_TILT).coerceIn(-1f, 1f)
        val ny = ((gy - restY) / FULL_TILT).coerceIn(-1f, 1f)
        // Content moves against the tilt, like something sitting slightly below the glass.
        onTilt(-nx * maxOffsetPx, ny * maxOffsetPx)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val REST_FOLLOW = 0.02f
        const val FULL_TILT = 2.4f
    }
}
