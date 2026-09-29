package com.arnav.island.overlay

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.arnav.island.IslandApp
import com.arnav.island.R
import com.arnav.island.settings.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hosts the island overlay. A foreground service is what Android requires for a long-lived,
 * user-enabled overlay to survive app switching and memory pressure; its notification is silent,
 * minimal-importance and can be hidden from system settings.
 */
class IslandOverlayService : Service() {

    private var runtime: OverlayRuntime? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        runtime = OverlayRuntime(this, (application as IslandApp).graph).also { it.start() }
        _running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            val graph = (application as IslandApp).graph
            graph.scope.launch { graph.settings.update { it.copy(enabled = false) } }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runtime?.stop()
        runtime = null
        _running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, IslandOverlayService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.service_action_pause), pause)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "IslandService"
        const val CHANNEL_ID = "island.service"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_PAUSE = "com.arnav.island.action.PAUSE"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_service_name), NotificationManager.IMPORTANCE_MIN).apply {
                    description = context.getString(R.string.channel_service_desc)
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        /** Starts the overlay if permitted. Safe to call from anywhere; never throws. */
        fun start(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, IslandOverlayService::class.java))
                true
            } catch (e: Exception) {
                // Android 12+ may refuse background starts (e.g. without the battery exemption).
                if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "Background start refused; will start when Island is next opened")
                } else {
                    Log.w(TAG, "Could not start overlay service", e)
                }
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IslandOverlayService::class.java))
        }
    }
}

/** Restores the island after reboot and after app updates, if the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val pending = goAsync()
        val graph = (context.applicationContext as IslandApp).graph
        graph.scope.launch {
            try {
                val s = graph.settings.current()
                // Touching the timer manager reschedules alarms lost across the reboot.
                graph.timers.awaitLoaded()
                graph.timers.resync()
                if (s.enabled && s.startOnBoot) IslandOverlayService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
