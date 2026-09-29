package com.arnav.island.client

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.app.BroadcastOptions
import androidx.core.content.ContextCompat

/**
 * Drop-in client for Island's local API. Copy this file into your own app.
 *
 * Setup in YOUR app's AndroidManifest.xml:
 *
 *     <uses-permission android:name="com.arnav.island.permission.POST_ISLAND_ACTIVITY" />
 *     <queries>
 *         <package android:name="com.arnav.island" />
 *     </queries>
 *
 * The permission is a runtime ("dangerous") permission: request it once with
 * ActivityResultContracts.RequestPermission() using [IslandClient.PERMISSION]. Also enable
 * "Local API" in Island → Advanced. Everything stays on the device.
 *
 * Example:
 *
 *     IslandClient.show(context, id = "build", title = "Building…", subtitle = "app:assembleRelease",
 *         icon = "DOWNLOAD", progress = 0.4f)                     // persistent live activity
 *     IslandClient.show(context, id = "build", title = "Build finished", icon = "CHECK",
 *         durationMs = 4_000)                                    // temporary toast
 *     IslandClient.dismiss(context, "build")
 */
object IslandClient {
    const val PACKAGE = "com.arnav.island"
    const val PERMISSION = "com.arnav.island.permission.POST_ISLAND_ACTIVITY"

    private const val ACTION_SHOW = "com.arnav.island.action.SHOW_ACTIVITY"
    private const val ACTION_DISMISS = "com.arnav.island.action.DISMISS_ACTIVITY"

    /** Built-in glyph names you can pass as [show]'s icon. */
    val GLYPHS = listOf(
        "MUSIC", "BELL", "BOLT", "BATTERY", "BLUETOOTH", "HEADPHONES", "TIMER", "STOPWATCH", "NAV_ARROW",
        "DOWNLOAD", "CHECK", "RECORD", "CHIP", "PLAY", "PAUSE", "FLAG", "INFO", "WARNING", "SPARK", "MESSAGE", "PHONE",
    )

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Shows or updates an activity. Re-using the same [id] updates it in place (the island morphs
     * instead of replaying an entrance).
     *
     * @param durationMs 0 for a persistent live activity, otherwise a temporary event (max 60 s).
     * @param progress 0..1 to show a progress ring/bar, or null.
     * @param priority "low", "normal" or "high".
     * @return false if the permission has not been granted.
     */
    fun show(
        context: Context,
        id: String,
        title: String,
        subtitle: String = "",
        icon: String = "SPARK",
        iconBitmap: Bitmap? = null,
        progress: Float? = null,
        durationMs: Long = 0,
        accentColor: Int = 0,
        priority: String = "normal",
        appLabel: String? = null,
    ): Boolean {
        if (!hasPermission(context)) return false
        val intent = Intent(ACTION_SHOW).setPackage(PACKAGE)
            .putExtra("id", id)
            .putExtra("title", title)
            .putExtra("subtitle", subtitle)
            .putExtra("icon", icon)
            .putExtra("progress", progress ?: -1f)
            .putExtra("duration_ms", durationMs)
            .putExtra("accent", accentColor)
            .putExtra("priority", priority)
        if (iconBitmap != null) intent.putExtra("icon_bitmap", iconBitmap)
        if (appLabel != null) intent.putExtra("app_label", appLabel)
        send(context, intent)
        return true
    }

    fun dismiss(context: Context, id: String): Boolean {
        if (!hasPermission(context)) return false
        send(context, Intent(ACTION_DISMISS).setPackage(PACKAGE).putExtra("id", id))
        return true
    }

    private fun send(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            // Lets Island attribute the activity to your app (per-app ids and labels).
            val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
            context.sendBroadcast(intent, null, options.toBundle())
        } else {
            context.sendBroadcast(intent)
        }
    }
}
