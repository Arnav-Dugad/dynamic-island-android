package com.arnav.island.events.navigation

import android.app.Notification
import android.service.notification.StatusBarNotification

/** What a navigation app chose to publish in its ongoing notification. */
data class NavigationInfo(
    val distance: String,
    val instruction: String,
    val eta: String,
)

/**
 * Pluggable navigation parsing. Providers read only public notification fields; add a provider
 * to support a new app's layout.
 */
interface NavigationProvider {
    fun matches(sbn: StatusBarNotification): Boolean
    fun parse(sbn: StatusBarNotification): NavigationInfo?
}

private val DISTANCE = Regex("""^\s*[\d.,]+\s*(m|km|ft|mi|yd|meters?|miles?|feet)\b.*""", RegexOption.IGNORE_CASE)

private fun Notification.field(key: String): String = extras.getCharSequence(key)?.toString()?.trim().orEmpty()

/** Splits title/text into (distance, instruction) whichever way round the app published them. */
private fun splitDistance(title: String, text: String): Pair<String, String> = when {
    DISTANCE.matches(title) -> title to text
    DISTANCE.matches(text) -> text to title
    else -> title to text
}

object GoogleMapsProvider : NavigationProvider {
    private val packages = setOf("com.google.android.apps.maps", "com.google.android.apps.mapslite")
    // Maps also posts ongoing non-navigation notifications (e.g. location sharing).
    override fun matches(sbn: StatusBarNotification) = sbn.packageName in packages && looksLikeGuidance(sbn.notification)
    override fun parse(sbn: StatusBarNotification): NavigationInfo? {
        val n = sbn.notification
        val (distance, instruction) = splitDistance(n.field(Notification.EXTRA_TITLE), n.field(Notification.EXTRA_TEXT))
        if (distance.isBlank() && instruction.isBlank()) return null
        return NavigationInfo(distance, instruction, n.field(Notification.EXTRA_SUB_TEXT))
    }
}

object WazeProvider : NavigationProvider {
    override fun matches(sbn: StatusBarNotification) = sbn.packageName == "com.waze" && looksLikeGuidance(sbn.notification)
    override fun parse(sbn: StatusBarNotification): NavigationInfo? {
        val n = sbn.notification
        val (distance, instruction) = splitDistance(n.field(Notification.EXTRA_TITLE), n.field(Notification.EXTRA_TEXT))
        if (distance.isBlank() && instruction.isBlank()) return null
        return NavigationInfo(distance, instruction, n.field(Notification.EXTRA_SUB_TEXT))
    }
}

/** Any app posting CATEGORY_NAVIGATION. */
object GenericNavigationProvider : NavigationProvider {
    override fun matches(sbn: StatusBarNotification) = sbn.notification.category == CATEGORY_NAVIGATION
    override fun parse(sbn: StatusBarNotification): NavigationInfo? {
        val n = sbn.notification
        val (distance, instruction) = splitDistance(n.field(Notification.EXTRA_TITLE), n.field(Notification.EXTRA_TEXT))
        if (distance.isBlank() && instruction.isBlank()) return null
        return NavigationInfo(distance, instruction, n.field(Notification.EXTRA_SUB_TEXT))
    }
}

object NavigationRegistry {
    private val providers = listOf(GoogleMapsProvider, WazeProvider, GenericNavigationProvider)

    fun providerFor(sbn: StatusBarNotification): NavigationProvider? {
        val ongoing = sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (!ongoing) return null
        return providers.firstOrNull { it.matches(sbn) }
    }
}

/** Notification.CATEGORY_NAVIGATION (API 31), compared by value so older releases work too. */
private const val CATEGORY_NAVIGATION = "navigation"

/** Turn-by-turn notifications carry the navigation category or a maneuver image. */
private fun looksLikeGuidance(n: Notification) = n.category == CATEGORY_NAVIGATION || n.getLargeIcon() != null
