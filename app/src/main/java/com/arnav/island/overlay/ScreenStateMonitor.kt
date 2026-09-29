package com.arnav.island.overlay

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screen on/off and lock state. The island hides (and stops every animation) the instant the
 * screen turns off; it never tries to draw over AOD or the lock screen.
 */
class ScreenStateMonitor(private val context: Context) {

    data class ScreenState(val interactive: Boolean, val locked: Boolean) {
        val usable: Boolean get() = interactive && !locked
    }

    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ScreenState> = _state.asStateFlow()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            _state.value = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> ScreenState(interactive = false, locked = true)
                Intent.ACTION_USER_PRESENT -> ScreenState(interactive = true, locked = false)
                else -> read()
            }
        }
    }

    private fun read() = ScreenState(power.isInteractive, keyguard.isKeyguardLocked)

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        _state.value = read()
    }

    /** Re-reads the keyguard, e.g. after face unlock where USER_PRESENT can lag. */
    fun refresh() {
        _state.value = read()
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }
}
