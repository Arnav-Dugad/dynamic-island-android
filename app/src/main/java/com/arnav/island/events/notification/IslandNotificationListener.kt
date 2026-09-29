package com.arnav.island.events.notification

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.arnav.island.IslandApp
import com.arnav.island.util.Diagnostics

/**
 * Notification access entry point. All logic lives in [NotificationProcessor]; this service only
 * forwards callbacks (on the main thread) and keeps the binding healthy.
 */
class IslandNotificationListener : NotificationListenerService() {

    private val graph get() = (application as IslandApp).graph

    override fun onListenerConnected() {
        super.onListenerConnected()
        graph.notifications.attach(this)
        graph.media.refresh()
        try {
            activeNotifications?.forEach { graph.notifications.onPosted(it, currentRanking, initialScan = true) }
        } catch (e: SecurityException) {
            Diagnostics.w(TAG, "Could not read active notifications", e)
        }
    }

    override fun onListenerDisconnected() {
        graph.notifications.detach()
        super.onListenerDisconnected()
        // Ask the system to rebind us (e.g. after an app update).
        requestRebind(ComponentName(this, IslandNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        graph.notifications.onPosted(sbn, rankingMap, initialScan = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        graph.notifications.onRemoved(sbn, reason)
    }

    companion object {
        private const val TAG = "IslandListener"
    }
}
