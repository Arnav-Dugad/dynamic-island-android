package com.arnav.island.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isGame: Boolean,
    val isVideo: Boolean,
    val isSystem: Boolean,
)

/**
 * App labels, icons and categories. Visibility of other apps is limited to launchable apps and
 * media browsers via <queries> in the manifest (no QUERY_ALL_PACKAGES).
 */
class AppInfoCache(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager
    private val labels = LruCache<String, String>(256)
    private val icons = LruCache<String, Bitmap>(64)
    private val categories = LruCache<String, Int>(256)
    private val accents = LruCache<String, Int>(128)

    fun label(packageName: String): String {
        labels.get(packageName)?.let { return it }
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
        labels.put(packageName, label)
        return label
    }

    /** Must be called off the main thread the first time for a package. */
    fun iconBlocking(packageName: String, sizePx: Int): Bitmap? {
        val key = "$packageName@$sizePx"
        icons.get(key)?.let { return it }
        return try {
            val bmp = Bitmaps.fromDrawable(pm.getApplicationIcon(packageName), sizePx)
            icons.put(key, bmp)
            bmp
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    suspend fun icon(packageName: String, sizePx: Int): Bitmap? = withContext(Dispatchers.Default) { iconBlocking(packageName, sizePx) }

    fun cachedIcon(packageName: String, sizePx: Int): Bitmap? = icons.get("$packageName@$sizePx")

    /**
     * The app's brand colour taken from its launcher icon, lifted to read on black; 0 when the
     * icon is monochrome. Off the main thread the first time for a package.
     */
    fun accentBlocking(packageName: String): Int {
        accents.get(packageName)?.let { return it }
        val icon = iconBlocking(packageName, 96) ?: return 0
        val color = ColorExtractor.accentFrom(Bitmaps.samplePixels(icon))
        val accent = if (color == ColorExtractor.FALLBACK) 0 else color
        accents.put(packageName, accent)
        return accent
    }

    private fun category(packageName: String): Int {
        categories.get(packageName)?.let { return it }
        val cat = try {
            pm.getApplicationInfo(packageName, 0).category
        } catch (_: PackageManager.NameNotFoundException) {
            ApplicationInfo.CATEGORY_UNDEFINED
        }
        categories.put(packageName, cat)
        return cat
    }

    fun isGame(packageName: String): Boolean = category(packageName) == ApplicationInfo.CATEGORY_GAME

    fun isVideo(packageName: String): Boolean = category(packageName) == ApplicationInfo.CATEGORY_VIDEO

    suspend fun launchableApps(): List<InstalledApp> = withContext(Dispatchers.Default) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString().also { labels.put(info.packageName, it) },
                    isGame = info.category == ApplicationInfo.CATEGORY_GAME,
                    isVideo = info.category == ApplicationInfo.CATEGORY_VIDEO,
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
