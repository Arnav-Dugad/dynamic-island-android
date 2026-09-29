package com.arnav.island.permissions

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arnav.island.events.notification.IslandNotificationListener
import com.arnav.island.overlay.ForegroundAppMonitor

/** Everything Island may be allowed to do, as of the last check. */
data class PermissionSnapshot(
    val overlay: Boolean = false,
    val notificationAccess: Boolean = false,
    val postNotifications: Boolean = false,
    val bluetooth: Boolean = false,
    val usageAccess: Boolean = false,
    val exactAlarms: Boolean = false,
    val batteryUnrestricted: Boolean = false,
) {
    val coreReady: Boolean get() = overlay
}

object Permissions {

    fun snapshot(context: Context) = PermissionSnapshot(
        overlay = Settings.canDrawOverlays(context),
        notificationAccess = hasNotificationAccess(context),
        postNotifications = granted(context, Manifest.permission.POST_NOTIFICATIONS),
        bluetooth = granted(context, Manifest.permission.BLUETOOTH_CONNECT),
        usageAccess = ForegroundAppMonitor.hasUsageAccess(context),
        exactAlarms = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
    )

    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationAccess(context: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private fun pkg(context: Context) = Uri.parse("package:${context.packageName}")

    fun overlaySettings(context: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg(context))

    fun notificationAccessSettings(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, IslandNotificationListener::class.java).flattenToString(),
        )

    fun notificationAccessFallback() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun usageAccessSettings(context: Context): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg(context))

    fun usageAccessFallback() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun exactAlarmSettings(context: Context) = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg(context))

    /** App info: battery "Unrestricted" and, for sideloaded builds, "Allow restricted settings". */
    fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(context))

    fun batteryOptimizationList() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appNotificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** Android 13+ blocks notification access for sideloaded apps until the user allows it. */
    val restrictedSettingsApply: Boolean get() = Build.VERSION.SDK_INT >= 33
}
