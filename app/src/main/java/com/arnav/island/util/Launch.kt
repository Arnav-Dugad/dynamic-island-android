package com.arnav.island.util

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Launch helpers that respect Android 14+ background-activity-launch rules. The island only
 * launches in direct response to a user tap on its visible overlay window, which is exactly the
 * case the platform allows; we opt in explicitly as Android 14+ requires.
 */
object Launch {
    private const val TAG = "IslandLaunch"

    private fun options(): Bundle? {
        val opts = ActivityOptions.makeBasic()
        return when {
            Build.VERSION.SDK_INT >= 36 -> {
                // Our visible overlay window + the user's tap is what grants the privilege.
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
                opts.toBundle()
            }
            Build.VERSION.SDK_INT >= 34 -> {
                @Suppress("DEPRECATION")
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                opts.toBundle()
            }
            else -> null
        }
    }

    /** Sends a PendingIntent created by another app (notification content intent, action...). */
    fun send(context: Context, pendingIntent: PendingIntent?): Boolean {
        if (pendingIntent == null) return false
        return try {
            pendingIntent.send(context, 0, null, null, null, null, options())
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "PendingIntent was cancelled", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "PendingIntent send failed", e)
            false
        }
    }

    fun openApp(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return startActivity(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    fun startActivity(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not start ${intent.component ?: intent.action}", e)
        false
    }
}
