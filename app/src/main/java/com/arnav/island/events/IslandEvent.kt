package com.arnav.island.events

/**
 * Everything the island can show is an [IslandEvent]. Event sources (media, notifications,
 * battery, timers, ...) translate platform callbacks into events and hand them to the
 * [EventEngine]; nothing downstream knows about Android framework types except through
 * [IslandAction.invoke] and the image keys in [ImageRef].
 *
 * Identity rules:
 *  - [id] is stable for one logical activity (e.g. "media", "timer:3", "notif:<key>").
 *    Posting an event with an existing id updates it in place: the island morphs its content
 *    instead of replaying an entrance.
 *  - [mergeKey] groups transient events that may be merged (e.g. several messages from one app).
 */
data class IslandEvent(
    val id: String,
    val source: EventSource,
    val type: EventType,
    val priority: EventPriority = type.defaultPriority,
    val timestamp: Long,
    /** Absolute wall-clock time after which the event is dropped, or null for no expiry. */
    val expiresAt: Long? = null,
    /** Persistent events are live activities; the rest are temporary toasts. */
    val persistent: Boolean,
    /** How long a temporary event stays on screen once shown. Ignored for persistent events. */
    val durationMs: Long = DEFAULT_TOAST_MS,
    val title: String = "",
    val subtitle: String = "",
    val icon: Glyph? = null,
    val iconImage: ImageRef? = null,
    val artwork: ImageRef? = null,
    /** 0..1, or null when the event has no meaningful progress. */
    val progress: Float? = null,
    val actions: List<IslandAction> = emptyList(),
    val colors: EventColors = EventColors.Default,
    val animation: EntranceAnimation = EntranceAnimation.MORPH,
    val mergeKey: String? = null,
    val mergeCount: Int = 1,
    /** Content identity. When it changes on a dismissed live event, the dismissal is lifted. */
    val contentKey: String? = null,
    val payload: EventPayload? = null,
    /** Present in the expanded card straight away (incoming calls, ringing timers). */
    val autoExpand: Boolean = false,
    /** While this event is primary, lower-priority toasts wait in the queue. */
    val blocksToasts: Boolean = false,
    val dismissible: Boolean = true,
    /** Primary tap action; falls back to expanding when null. */
    val tapAction: IslandAction? = null,
    /** Called when the user swipes the event away (e.g. to cancel a notification). */
    val onDismiss: IslandAction? = null,
) {
    val isTransient: Boolean get() = !persistent

    companion object {
        const val DEFAULT_TOAST_MS = 4_000L
    }
}

enum class EventSource { MEDIA, NOTIFICATION, BATTERY, BLUETOOTH, TIMER, STOPWATCH, CALL, NAVIGATION, PROGRESS, SYSTEM, MONITOR, API, TEST }

/**
 * Priority order from the brief. Higher [rank] wins; ties go to the most recent event.
 */
enum class EventPriority(val rank: Int) {
    BACKGROUND_ACTIVITY(10),
    NOTIFICATION(20),
    MEDIA(30),
    CHARGING(40),
    NAVIGATION(50),
    TIMER(60),
    CRITICAL_SYSTEM_EVENT(70),
    CALL(80),
}

enum class EventType(val defaultPriority: EventPriority) {
    MEDIA(EventPriority.MEDIA),
    NOTIFICATION(EventPriority.NOTIFICATION),
    CHARGING(EventPriority.CHARGING),
    BATTERY_LOW(EventPriority.CRITICAL_SYSTEM_EVENT),
    BATTERY_FULL(EventPriority.CHARGING),
    BLUETOOTH(EventPriority.CHARGING),
    TIMER(EventPriority.TIMER),
    TIMER_DONE(EventPriority.CRITICAL_SYSTEM_EVENT),
    STOPWATCH(EventPriority.TIMER),
    CALL_INCOMING(EventPriority.CALL),
    CALL_ONGOING(EventPriority.CALL),
    NAVIGATION(EventPriority.NAVIGATION),
    PROGRESS(EventPriority.BACKGROUND_ACTIVITY),
    PROGRESS_DONE(EventPriority.NOTIFICATION),
    SYSTEM(EventPriority.CHARGING),
    SCREEN_RECORD(EventPriority.CRITICAL_SYSTEM_EVENT),
    MONITOR(EventPriority.BACKGROUND_ACTIVITY),
    CUSTOM(EventPriority.NOTIFICATION),
    GLANCE(EventPriority.NOTIFICATION),

    /** Synthetic: the stack of every running activity (never posted by a source). */
    STACK(EventPriority.BACKGROUND_ACTIVITY),
}

enum class EntranceAnimation { MORPH, BLOOM, POP, SLIDE }

/** ARGB colours; 0 means "use the theme default". */
data class EventColors(val accent: Int = 0, val secondary: Int = 0) {
    companion object {
        val Default = EventColors()
    }
}

/** Opaque reference into the Android-side image cache. */
@JvmInline
value class ImageRef(val key: String)

/** Built-in vector glyphs drawn by the renderer at any scale. */
enum class Glyph {
    MUSIC, BELL, BELL_OFF, VIBRATE, MOON, BOLT, BATTERY, BLUETOOTH, HEADPHONES, EARBUDS, WATCH,
    SPEAKER, CAR, DEVICE, PHONE, PHONE_DOWN, VIDEO, TIMER, STOPWATCH, NAV_ARROW, DOWNLOAD, CHECK,
    ROTATE_LOCK, ROTATE, HOTSPOT, RECORD, CLIPBOARD, CHIP, PLAY, PAUSE, NEXT, PREVIOUS, PLUS, CLOSE,
    STOP, FLAG, INFO, WARNING, SPARK, MESSAGE, OPEN, REPLY, CALENDAR, ALARM,
}

enum class ActionStyle { DEFAULT, PRIMARY, POSITIVE, DESTRUCTIVE }

/**
 * A button or gesture target. Equality deliberately ignores [invoke] so that re-posting an event
 * with freshly created lambdas does not count as a content change.
 */
class IslandAction(
    val id: String,
    val label: String,
    val glyph: Glyph? = null,
    val style: ActionStyle = ActionStyle.DEFAULT,
    /** Whether the island should collapse after the action runs. */
    val collapses: Boolean = false,
    val invoke: () -> Unit,
) {
    override fun equals(other: Any?): Boolean =
        other is IslandAction && other.id == id && other.label == label && other.glyph == glyph &&
            other.style == style && other.collapses == collapses

    override fun hashCode(): Int = ((id.hashCode() * 31 + label.hashCode()) * 31 + (glyph?.hashCode() ?: 0)) * 31 + style.hashCode()

    override fun toString(): String = "IslandAction($id)"
}

/** Scrub target for seekable media. Equality is identity-free for the same reason as [IslandAction]. */
class SeekAction(val invoke: (fraction: Float) -> Unit) {
    override fun equals(other: Any?): Boolean = other is SeekAction
    override fun hashCode(): Int = 1
}
