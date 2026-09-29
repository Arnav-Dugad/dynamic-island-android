package com.arnav.island.overlay

import android.content.Context
import android.provider.Settings
import com.arnav.island.BuildConfig
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Diagnostics

/**
 * "Replace One UI pop-ups": while the island is on screen and showing notifications, Android's
 * own heads-up banners are switched off through the global `heads_up_notifications_enabled`
 * setting, so each notification appears once, in the island.
 *
 * Pop-ups come back by themselves whenever the island cannot show them: hidden in a game or
 * fullscreen video, screen locked, overlay stopped, or the feature turned off. Needs
 * WRITE_SECURE_SETTINGS granted over ADB and the full edition (Lite cannot read notifications).
 */
object SystemPopups {
    private const val TAG = "IslandPopups"
    const val KEY = "heads_up_notifications_enabled"

    fun available(context: Context): Boolean = !BuildConfig.LITE && StatusBarCleanup.canWrite(context)

    fun current(context: Context): Int = Settings.Global.getInt(context.contentResolver, KEY, 1)

    /**
     * Applies the right value for [s] given whether the island is currently able to show
     * notifications. Returns settings to persist when the backup changes, else null.
     */
    fun sync(context: Context, s: IslandSettings, islandShowing: Boolean): IslandSettings? {
        if (!StatusBarCleanup.canWrite(context)) return null
        return try {
            val managed = s.replaceSystemPopups && available(context)
            when {
                managed -> {
                    val backup = if (s.headsUpBackup < 0) current(context).takeIf { it != 0 } ?: 1 else s.headsUpBackup
                    val suppress = islandShowing && s.enabled && s.notificationsEnabled
                    put(context, if (suppress) 0 else backup)
                    if (s.headsUpBackup < 0) s.copy(headsUpBackup = backup) else null
                }
                s.headsUpBackup >= 0 -> {
                    put(context, s.headsUpBackup)
                    s.copy(headsUpBackup = -1)
                }
                else -> null
            }
        } catch (e: SecurityException) {
            Diagnostics.w(TAG, "WRITE_SECURE_SETTINGS was revoked", e)
            null
        }
    }

    private fun put(context: Context, value: Int) {
        if (current(context) != value) Settings.Global.putInt(context.contentResolver, KEY, value)
    }
}
