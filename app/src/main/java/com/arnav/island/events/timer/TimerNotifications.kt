package com.arnav.island.events.timer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arnav.island.IslandApp
import com.arnav.island.R
import com.arnav.island.settings.MainActivity
import com.arnav.island.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object TimerNotifications {
    const val CHANNEL_ID = "island.timers"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        // DEFAULT importance: plays the alarm sound without a heads-up banner covering the island.
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_timer_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.channel_timer_desc)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 250, 400, 250, 400)
        }
        nm.createNotificationChannel(channel)
    }

    private fun notificationId(id: Long) = 5_000 + (id % 10_000).toInt()

    fun showDone(context: Context, timer: TimerItem) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_DESTINATION, "timers"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(timer.label.ifBlank { context.getString(R.string.timer_done_title) })
            .setContentText("${Formatters.humanDuration(timer.totalMs)} timer finished")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .addAction(0, context.getString(R.string.timer_action_add_minute), action(context, TimerActionReceiver.ACTION_ADD_MINUTE, timer.id))
            .addAction(0, context.getString(R.string.timer_action_stop), action(context, TimerActionReceiver.ACTION_STOP, timer.id))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(timer.id), notification)
    }

    fun cancelDone(context: Context, id: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(id))
    }

    private fun action(context: Context, action: String, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        (action.hashCode() + id).toInt(),
        Intent(context, TimerActionReceiver::class.java).setAction(action).putExtra(TimerAlarmReceiver.EXTRA_TIMER_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Fires when a timer's alarm goes off, even if the process had been killed. */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_TIMER_ID, -1)
        if (id < 0) return
        val pending = goAsync()
        val timers = (context.applicationContext as IslandApp).graph.timers
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                timers.awaitLoaded()
                timers.onAlarm(id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TIMER_ID = "timer_id"
    }
}

/** Stop / +1 min buttons on the "timer finished" notification. */
class TimerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(TimerAlarmReceiver.EXTRA_TIMER_ID, -1)
        if (id < 0) return
        val pending = goAsync()
        val timers = (context.applicationContext as IslandApp).graph.timers
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                timers.awaitLoaded()
                when (intent.action) {
                    ACTION_STOP -> timers.stopRinging(id)
                    ACTION_ADD_MINUTE -> timers.addMinute(id)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.arnav.island.timer.STOP"
        const val ACTION_ADD_MINUTE = "com.arnav.island.timer.ADD_MINUTE"
    }
}
