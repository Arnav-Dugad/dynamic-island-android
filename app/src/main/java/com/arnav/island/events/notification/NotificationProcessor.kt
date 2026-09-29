package com.arnav.island.events.notification

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat
import com.arnav.island.events.ActionStyle
import com.arnav.island.events.CallPayload
import com.arnav.island.events.CallState
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventPriority
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.ImageRef
import com.arnav.island.events.IslandAction
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.EventColors
import com.arnav.island.events.NavigationPayload
import com.arnav.island.events.NotificationPayload
import com.arnav.island.events.NotificationPrivacy
import com.arnav.island.events.ProgressPayload
import com.arnav.island.events.navigation.NavigationRegistry
import com.arnav.island.island.render.presenters.CallPresenter
import com.arnav.island.island.render.presenters.NotificationPresenter
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.AppInfoCache
import com.arnav.island.util.Bitmaps
import com.arnav.island.util.ImageStore
import com.arnav.island.util.Launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turns posted notifications into island events: calls, navigation, progress and regular
 * alerts. Notification content is kept in memory only for as long as the event is on screen.
 */
class NotificationProcessor(
    private val context: Context,
    private val engine: EventEngine,
    private val images: ImageStore,
    private val apps: AppInfoCache,
    private val scope: CoroutineScope,
    private val settings: () -> IslandSettings,
) {
    private var listener: NotificationListenerService? = null
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    /** Content hash per notification key, to ignore silent re-posts (LRU, bounded). */
    private val seen = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 96
    }
    private val lastProgress = HashMap<String, Float>()

    val isConnected: Boolean get() = listener != null

    fun attach(service: NotificationListenerService) {
        listener = service
    }

    fun detach() {
        listener = null
        engine.removeWhere { it.source == EventSource.CALL || it.source == EventSource.NAVIGATION || it.source == EventSource.PROGRESS }
    }

    fun onPosted(sbn: StatusBarNotification, rankingMap: RankingMap?, initialScan: Boolean) {
        val s = settings()
        if (!s.enabled) return
        if (sbn.packageName == context.packageName) return
        val n = sbn.notification ?: return
        if (isMedia(n)) return
        val rule = s.ruleFor(sbn.packageName)

        when {
            isCall(n) -> if (s.callsEnabled) handleCall(sbn)
            NavigationRegistry.providerFor(sbn) != null -> if (s.navigationEnabled && rule.notifications) handleNavigation(sbn)
            hasProgress(n) -> if (s.progressEnabled && rule.notifications) handleProgress(sbn)
            !initialScan -> if (s.notificationsEnabled) handleAlert(sbn, ranking(rankingMap, sbn.key), s)
        }
    }

    fun onRemoved(sbn: StatusBarNotification, reason: Int) {
        val key = sbn.key
        engine.remove(callId(key))
        engine.remove(navId(key))
        lastProgress.remove(key)?.let { last ->
            engine.remove(progressId(key))
            val finished = last >= 0.97f && reason != NotificationListenerService.REASON_CANCEL && reason != NotificationListenerService.REASON_USER_STOPPED
            if (finished) postProgressDone(sbn)
        }
        // Read, cleared or withdrawn elsewhere: stop showing it (and drop any queued copy).
        engine.remove(alertId(key))
        seen.remove(key)
    }

    // ---------------------------------------------------------------------------------------------
    // Classification
    // ---------------------------------------------------------------------------------------------

    private fun template(n: Notification): String = n.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()

    private fun isMedia(n: Notification) =
        n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || template(n).contains("MediaStyle")

    private fun isCall(n: Notification) =
        n.category == Notification.CATEGORY_CALL || template(n).endsWith("\$CallStyle")

    private fun hasProgress(n: Notification): Boolean {
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (!ongoing) return false
        return n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 || n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
    }

    private fun ranking(map: RankingMap?, key: String): Ranking? {
        map ?: return null
        val r = Ranking()
        return if (map.getRanking(key, r)) r else null
    }

    // ---------------------------------------------------------------------------------------------
    // Regular alerts
    // ---------------------------------------------------------------------------------------------

    private fun handleAlert(sbn: StatusBarNotification, ranking: Ranking?, s: IslandSettings) {
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (ranking != null) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
            if (!ranking.matchesInterruptionFilter()) return
            if (ranking.isSuspended) return
        }
        val rule = s.ruleFor(sbn.packageName)
        if (!rule.notifications) return

        val content = extractContent(n)
        if (content.title.isBlank() && content.text.isBlank()) return
        val hash = (content.title + '\u0000' + content.text).hashCode()
        val previous = seen[sbn.key]
        if (previous == hash) return
        seen[sbn.key] = hash
        val id = alertId(sbn.key)
        val isUpdate = previous != null
        if (isUpdate && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && engine.current().toast?.id != id) return

        // The user's per-channel lock screen choice overrides what the app declared.
        val visibility = ranking?.lockscreenVisibilityOverride
            ?.takeIf { it != Ranking.VISIBILITY_NO_OVERRIDE }
            ?: n.visibility
        val isPrivate = visibility != Notification.VISIBILITY_PUBLIC
        var privacy = rule.privacy ?: s.notificationPrivacy
        val reduce = isPrivate && (keyguard.isKeyguardLocked || s.hideSensitiveContent)
        if (reduce && privacy == NotificationPrivacy.FULL) privacy = NotificationPrivacy.APP_NAME

        val priority = when (rule.priority) {
            1 -> EventPriority.CHARGING
            -1 -> EventPriority.BACKGROUND_ACTIVITY
            else -> EventPriority.NOTIFICATION
        }
        val duration = rule.durationMs.takeIf { it > 0 } ?: s.notificationDurationMs
        val pkg = sbn.packageName
        val appLabel = apps.label(pkg)
        val key = sbn.key
        val clearable = sbn.isClearable
        val autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0

        val actions = n.actions.orEmpty()
            .filter { it.remoteInputs.isNullOrEmpty() && it.actionIntent != null && !it.title.isNullOrBlank() }
            .take(3)
            .mapIndexed { i, action ->
                IslandAction(NotificationPresenter.ACTION_PREFIX + i, action.title.toString(), collapses = true) {
                    Launch.send(context, action.actionIntent)
                }
            }
        val open = IslandAction("notif.open", "Open") {
            Launch.send(context, n.contentIntent) || Launch.openApp(context, pkg)
            if (autoCancel && clearable) cancel(key)
        }
        val onDismiss = IslandAction("notif.dismiss", "Dismiss") { if (clearable) cancel(key) }

        scope.launch {
            val (largeRef, appRef) = withContext(Dispatchers.Default) {
                loadLargeIcon(n, content.personIcon, "notif:$key") to loadAppIcon(pkg)
            }
            engine.post(
                IslandEvent(
                    id = id,
                    source = EventSource.NOTIFICATION,
                    type = EventType.NOTIFICATION,
                    priority = priority,
                    timestamp = System.currentTimeMillis(),
                    persistent = false,
                    durationMs = duration,
                    title = content.title,
                    subtitle = content.text,
                    icon = if (content.isConversation) Glyph.MESSAGE else Glyph.BELL,
                    iconImage = appRef,
                    artwork = largeRef,
                    actions = actions,
                    colors = EventColors(accent = n.color),
                    mergeKey = "app:$pkg:${content.conversationKey}",
                    contentKey = hash.toString(),
                    tapAction = open,
                    onDismiss = onDismiss,
                    animation = EntranceAnimation.MORPH,
                    payload = NotificationPayload(
                        packageName = pkg,
                        appLabel = appLabel,
                        title = content.title,
                        text = content.text,
                        extraLines = content.extraLines,
                        privacy = privacy,
                        showText = rule.showText,
                        postedAt = sbn.postTime,
                        hasLargeIcon = largeRef != null,
                        isConversation = content.isConversation,
                    ),
                )
            )
        }
    }

    private data class Content(
        val title: String,
        val text: String,
        val extraLines: List<String>,
        val isConversation: Boolean,
        val conversationKey: String,
        val personIcon: Icon?,
    )

    private fun extractContent(n: Notification): Content {
        val extras = n.extras
        var title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        var text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val extra = ArrayList<String>()
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.mapTo(extra) { it.toString() }

        var isConversation = false
        var conversationKey = n.shortcutId.orEmpty()
        var personIcon: Icon? = null
        val messaging = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val messages = messaging?.messages.orEmpty()
        if (messaging != null && messages.isNotEmpty()) {
            isConversation = true
            val last = messages.last()
            val sender = last.person?.name?.toString()
            val group = messaging.isGroupConversation
            text = if (group && !sender.isNullOrBlank()) "$sender: ${last.text}" else last.text?.toString().orEmpty()
            title = messaging.conversationTitle?.toString() ?: sender ?: title
            conversationKey = conversationKey.ifEmpty { title }
            extra.clear()
            messages.dropLast(1).takeLast(4).forEach { m ->
                val who = m.person?.name?.toString()
                extra += if (group && !who.isNullOrBlank()) "$who: ${m.text}" else m.text?.toString().orEmpty()
            }
            personIcon = last.person?.icon?.toIcon(context)
        }
        return Content(title.trim(), text.trim(), extra.filter { it.isNotBlank() }, isConversation, conversationKey, personIcon)
    }

    // ---------------------------------------------------------------------------------------------
    // Calls
    // ---------------------------------------------------------------------------------------------

    private fun handleCall(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        val key = sbn.key

        var answer: PendingIntent? = null
        var decline: PendingIntent? = null
        var hangUp: PendingIntent? = null
        var person: Person? = null
        var callType = 0
        var isVideo = false
        if (Build.VERSION.SDK_INT >= 31) {
            answer = BundleCompat.getParcelable(extras, Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
            decline = BundleCompat.getParcelable(extras, Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java)
            hangUp = BundleCompat.getParcelable(extras, Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java)
            person = BundleCompat.getParcelable(extras, Notification.EXTRA_CALL_PERSON, Person::class.java)
            callType = extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
            isVideo = extras.getBoolean(Notification.EXTRA_CALL_IS_VIDEO)
        }
        // Dialers that don't use CallStyle: match their own action labels.
        val actions = n.actions.orEmpty()
        fun find(vararg words: String) = actions.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            // Whole words only: "end" must not match "Send message".
            words.any { w -> Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(t) }
        }?.actionIntent
        answer = answer ?: find("answer", "accept", "pick up")
        decline = decline ?: find("decline", "reject")
        hangUp = hangUp ?: find("hang up", "end call", "hangup", "end")

        val usesChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val incoming = when (callType) {
            CALL_TYPE_INCOMING, CALL_TYPE_SCREENING -> true
            CALL_TYPE_ONGOING -> false
            else -> !usesChronometer && (answer != null || n.fullScreenIntent != null)
        }
        val name = person?.name?.toString() ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val detail = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val pkg = sbn.packageName
        val open = n.contentIntent ?: n.fullScreenIntent

        val eventActions = buildList {
            if (incoming) {
                decline?.let { pi -> add(IslandAction(CallPresenter.ACTION_DECLINE, "Decline", Glyph.PHONE_DOWN, ActionStyle.DESTRUCTIVE, collapses = true) { Launch.send(context, pi) }) }
                answer?.let { pi -> add(IslandAction(CallPresenter.ACTION_ANSWER, "Answer", Glyph.PHONE, ActionStyle.POSITIVE, collapses = true) { Launch.send(context, pi) }) }
            } else {
                open?.let { pi -> add(IslandAction(CallPresenter.ACTION_OPEN, "Return to call", Glyph.OPEN, collapses = true) { Launch.send(context, pi) }) }
                hangUp?.let { pi -> add(IslandAction(CallPresenter.ACTION_HANGUP, "Hang up", Glyph.PHONE_DOWN, ActionStyle.DESTRUCTIVE, collapses = true) { Launch.send(context, pi) }) }
            }
        }
        val tap = IslandAction("call.tap", "Open call") { Launch.send(context, open) || Launch.openApp(context, pkg) }

        scope.launch {
            val avatar = withContext(Dispatchers.Default) {
                val icon = if (Build.VERSION.SDK_INT >= 31) person?.icon else null
                loadLargeIcon(n, icon, "call:$key")
            }
            engine.post(
                IslandEvent(
                    id = callId(key),
                    source = EventSource.CALL,
                    type = if (incoming) EventType.CALL_INCOMING else EventType.CALL_ONGOING,
                    timestamp = System.currentTimeMillis(),
                    persistent = true,
                    title = name.ifBlank { apps.label(pkg) },
                    subtitle = detail,
                    icon = Glyph.PHONE,
                    artwork = avatar,
                    actions = eventActions,
                    autoExpand = incoming,
                    blocksToasts = incoming,
                    tapAction = tap,
                    contentKey = "$incoming",
                    animation = EntranceAnimation.BLOOM,
                    payload = CallPayload(
                        callerName = name,
                        detail = detail,
                        appLabel = apps.label(pkg),
                        state = if (incoming) CallState.INCOMING else CallState.ONGOING,
                        chronometerBase = if (usesChronometer && n.`when` > 0) n.`when` else null,
                        isVideo = isVideo,
                        hasAvatar = avatar != null,
                    ),
                )
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Navigation & progress
    // ---------------------------------------------------------------------------------------------

    private fun handleNavigation(sbn: StatusBarNotification) {
        val provider = NavigationRegistry.providerFor(sbn) ?: return
        val info = provider.parse(sbn) ?: return
        val n = sbn.notification
        val pkg = sbn.packageName
        val key = sbn.key
        scope.launch {
            val maneuver = withContext(Dispatchers.Default) { loadLargeIcon(n, null, "nav:$key") }
            engine.post(
                IslandEvent(
                    id = navId(key),
                    source = EventSource.NAVIGATION,
                    type = EventType.NAVIGATION,
                    timestamp = System.currentTimeMillis(),
                    persistent = true,
                    title = info.distance,
                    subtitle = info.instruction,
                    icon = Glyph.NAV_ARROW,
                    artwork = maneuver,
                    colors = EventColors(accent = n.color),
                    tapAction = IslandAction("nav.open", "Open navigation") { Launch.send(context, n.contentIntent) || Launch.openApp(context, pkg) },
                    contentKey = "${info.distance}|${info.instruction}",
                    payload = NavigationPayload(apps.label(pkg), info.distance, info.instruction, info.eta, maneuver != null),
                )
            )
        }
    }

    private fun handleProgress(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val current = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val fraction = if (max > 0) (current.toFloat() / max).coerceIn(0f, 1f) else 0f
        val key = sbn.key
        val pkg = sbn.packageName
        lastProgress[key] = if (indeterminate) 0f else fraction
        if (!indeterminate && fraction >= 1f) {
            engine.remove(progressId(key))
            postProgressDone(sbn)
            lastProgress.remove(key)
            return
        }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { apps.label(pkg) }
        scope.launch {
            val icon = withContext(Dispatchers.Default) { loadAppIcon(pkg) }
            engine.post(
                IslandEvent(
                    id = progressId(key),
                    source = EventSource.PROGRESS,
                    type = EventType.PROGRESS,
                    timestamp = System.currentTimeMillis(),
                    persistent = true,
                    title = title,
                    icon = Glyph.DOWNLOAD,
                    iconImage = icon,
                    progress = fraction,
                    tapAction = IslandAction("progress.open", "Open") { Launch.send(context, n.contentIntent) || Launch.openApp(context, pkg) },
                    contentKey = "progress",
                    payload = ProgressPayload(apps.label(pkg), pkg, title, fraction, indeterminate, done = false),
                )
            )
        }
    }

    private fun postProgressDone(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { apps.label(pkg) }
        engine.post(
            IslandEvent(
                id = "progress-done:${sbn.key}",
                source = EventSource.PROGRESS,
                type = EventType.PROGRESS_DONE,
                timestamp = System.currentTimeMillis(),
                persistent = false,
                durationMs = 2_400,
                title = title,
                subtitle = "Done",
                icon = Glyph.CHECK,
                iconImage = "app:$pkg@96".takeIf(images::contains)?.let(::ImageRef),
                progress = 1f,
                animation = EntranceAnimation.POP,
                payload = ProgressPayload(apps.label(pkg), pkg, title, 1f, indeterminate = false, done = true),
            )
        )
    }

    // ---------------------------------------------------------------------------------------------

    private fun cancel(key: String) {
        try {
            listener?.cancelNotification(key)
        } catch (_: SecurityException) {
        }
    }

    /** Loads the large icon (or a person icon) off the main thread. */
    private fun loadLargeIcon(n: Notification, preferred: Icon?, key: String): ImageRef? {
        val icon = preferred ?: n.getLargeIcon() ?: return null
        return try {
            val drawable = icon.loadDrawable(context) ?: return null
            images.put("large:$key", Bitmaps.fromDrawable(drawable, ICON_PX))
        } catch (_: Exception) {
            null
        }
    }

    private fun loadAppIcon(pkg: String): ImageRef? {
        val key = "app:$pkg@96"
        if (images.contains(key)) return ImageRef(key)
        val bmp = apps.iconBlocking(pkg, 96) ?: return null
        return images.put(key, bmp)
    }

    private fun alertId(key: String) = "notif:$key"
    private fun callId(key: String) = "call:$key"
    private fun navId(key: String) = "nav:$key"
    private fun progressId(key: String) = "progress:$key"

    companion object {
        private const val ICON_PX = 144

        // Notification.CALL_TYPE_* (API 31).
        private const val CALL_TYPE_INCOMING = 1
        private const val CALL_TYPE_ONGOING = 2
        private const val CALL_TYPE_SCREENING = 3
    }
}
