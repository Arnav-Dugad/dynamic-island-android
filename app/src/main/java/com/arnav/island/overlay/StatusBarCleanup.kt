package com.arnav.island.overlay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.arnav.island.storage.IslandSettings

/**
 * Optional, experimental: hides status bar icons the island already shows (alarm, ringer, Do Not
 * Disturb, rotation) through the same `icon_blacklist` secure setting the System UI tuner uses.
 *
 * Only works after the user grants WRITE_SECURE_SETTINGS once over ADB; nothing is bypassed. The
 * previous value is backed up before the first change and restored exactly when turned off.
 */
object StatusBarCleanup {
    private const val TAG = "IslandStatusBar"
    const val KEY = "icon_blacklist"

    /** Stored in the backup when the setting did not exist before we touched it. */
    private const val WAS_UNSET = ""

    /** Icons that the island makes redundant, with the labels shown in settings. */
    val ICONS = linkedMapOf(
        "alarm_clock" to "Alarm",
        "volume" to "Sound mode (silent / vibrate)",
        "zen" to "Do Not Disturb",
        "rotate" to "Rotation lock",
        "headset" to "Headset",
        "bluetooth" to "Bluetooth",
        "hotspot" to "Hotspot",
    )

    const val GRANT_COMMAND = "adb shell pm grant com.arnav.island android.permission.WRITE_SECURE_SETTINGS"

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun current(context: Context): String? = Settings.Secure.getString(context.contentResolver, KEY)

    /**
     * Brings the system setting in line with [s]. Returns the settings to persist (a new backup,
     * or the backup cleared after a restore), or null when nothing needs saving.
     */
    fun sync(context: Context, s: IslandSettings): IslandSettings? {
        if (!canWrite(context)) return null
        val resolver = context.contentResolver
        return try {
            if (s.statusBarCleanup) {
                var next: IslandSettings? = null
                val original = if (s.statusBarBackup == IslandSettings.BACKUP_NONE) {
                    val existing = current(context).orEmpty()
                    next = s.copy(statusBarBackup = existing.ifEmpty { WAS_UNSET })
                    existing
                } else {
                    s.statusBarBackup
                }
                val wanted = (split(original) + split(s.statusBarIcons)).distinct().joinToString(",")
                if (current(context).orEmpty() != wanted) Settings.Secure.putString(resolver, KEY, wanted)
                next
            } else if (s.statusBarBackup != IslandSettings.BACKUP_NONE) {
                val restore = s.statusBarBackup.takeIf { it != WAS_UNSET }
                Settings.Secure.putString(resolver, KEY, restore)
                s.copy(statusBarBackup = IslandSettings.BACKUP_NONE)
            } else {
                null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS was revoked", e)
            null
        }
    }

    private fun split(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
