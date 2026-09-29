package com.arnav.island.overlay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Diagnostics
import java.util.concurrent.Executors

/**
 * Status bar control through the same `icon_blacklist` secure setting the System UI tuner uses.
 * Two features share it:
 *
 *  - cleanup: permanently hides icons the island already shows (alarm, ringer, Do Not Disturb…);
 *  - seamless status bar: while a card covers the status bar, the clock and system icons step
 *    aside (the way the status bar clears around an expanded Dynamic Island) and return as it closes.
 *
 * Both only work after the user grants WRITE_SECURE_SETTINGS once over ADB; nothing is bypassed.
 * The previous value is backed up before the first change and restored exactly when both are off.
 * Writes run on a background thread, latest request wins, so animations never wait on SettingsProvider.
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

    /**
     * What steps aside while a card is open. Privacy indicators (camera, microphone, location)
     * are deliberately never hidden.
     */
    private val SEAMLESS_SLOTS = listOf(
        "clock", "battery", "wifi", "mobile", "stacked_mobile", "ims_volte", "vpn", "data_saver",
        "airplane", "ethernet", "nfc", "bluetooth", "bluetooth_connected", "bt_tethering", "headset",
        "hotspot", "rotate", "zen", "volume", "mute", "alarm_clock", "managed_profile", "sensors_off",
        "screen_record", "cast", "connected_display", "satellite", "speakerphone", "tty",
    )

    const val GRANT_COMMAND = "adb shell pm grant com.arnav.island android.permission.WRITE_SECURE_SETTINGS"

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "island-statusbar").apply { isDaemon = true } }

    @Volatile
    private var cardOpen = false

    @Volatile
    private var lastSettings: IslandSettings? = null

    @Volatile
    private var pending: String? = null

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun current(context: Context): String? = Settings.Secure.getString(context.contentResolver, KEY)

    /** Whether Island is managing icon_blacklist at all for [s]. */
    private fun managed(context: Context, s: IslandSettings) =
        canWrite(context) && (s.statusBarCleanup || s.seamlessStatusBar)

    /**
     * Brings the system setting in line with [s]. Returns the settings to persist (a new backup,
     * or the backup cleared after a restore), or null when nothing needs saving.
     */
    fun sync(context: Context, s: IslandSettings): IslandSettings? {
        lastSettings = s
        if (!canWrite(context)) return null
        return try {
            if (managed(context, s)) {
                var next: IslandSettings? = null
                val original = if (s.statusBarBackup == IslandSettings.BACKUP_NONE) {
                    val existing = current(context).orEmpty()
                    next = s.copy(statusBarBackup = existing.ifEmpty { WAS_UNSET })
                    existing
                } else {
                    s.statusBarBackup
                }
                write(context, compose(original, s))
                next
            } else if (s.statusBarBackup != IslandSettings.BACKUP_NONE) {
                write(context, s.statusBarBackup.takeIf { it != WAS_UNSET })
                s.copy(statusBarBackup = IslandSettings.BACKUP_NONE)
            } else {
                null
            }
        } catch (e: SecurityException) {
            Diagnostics.w(TAG, "WRITE_SECURE_SETTINGS was revoked", e)
            null
        }
    }

    /**
     * Seamless status bar: called by the overlay as cards open and (once they have shrunk back
     * below the status bar) close. Needs a backup to exist, which [sync] creates.
     */
    fun setCardOpen(context: Context, open: Boolean) {
        if (cardOpen == open) return
        cardOpen = open
        val s = lastSettings ?: return
        if (!s.seamlessStatusBar || !managed(context, s) || s.statusBarBackup == IslandSettings.BACKUP_NONE) return
        write(context, compose(s.statusBarBackup, s))
    }

    private fun compose(original: String, s: IslandSettings): String {
        val slots = LinkedHashSet<String>()
        slots += split(original.takeIf { it != WAS_UNSET }.orEmpty())
        if (s.statusBarCleanup) slots += split(s.statusBarIcons)
        if (s.seamlessStatusBar && cardOpen) slots += SEAMLESS_SLOTS
        return slots.joinToString(",")
    }

    /** Queues a write; if several are queued only the newest runs. */
    private fun write(context: Context, value: String?) {
        val app = context.applicationContext
        pending = value ?: WAS_UNSET
        writer.execute {
            val wanted = pending ?: return@execute
            pending = null
            try {
                val now = current(app).orEmpty()
                if (now != wanted) Settings.Secure.putString(app.contentResolver, KEY, wanted.ifEmpty { null })
            } catch (e: SecurityException) {
                Diagnostics.w(TAG, "WRITE_SECURE_SETTINGS was revoked", e)
            }
        }
    }

    private fun split(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
