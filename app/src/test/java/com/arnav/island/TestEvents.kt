package com.arnav.island

import com.arnav.island.events.EventPriority
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.IslandEvent

/** Mutable fake clock for deterministic scheduler tests. */
class FakeClock(var now: Long = 1_000_000L) {
    fun advance(ms: Long) {
        now += ms
    }

    val fn: () -> Long = { now }
}

fun live(
    id: String,
    type: EventType = EventType.MEDIA,
    priority: EventPriority = type.defaultPriority,
    at: Long = 0,
    contentKey: String? = null,
    expiresAt: Long? = null,
    blocksToasts: Boolean = false,
    autoExpand: Boolean = false,
) = IslandEvent(
    id = id,
    source = EventSource.TEST,
    type = type,
    priority = priority,
    timestamp = at,
    persistent = true,
    contentKey = contentKey,
    expiresAt = expiresAt,
    blocksToasts = blocksToasts,
    autoExpand = autoExpand,
)

fun toast(
    id: String,
    type: EventType = EventType.NOTIFICATION,
    priority: EventPriority = type.defaultPriority,
    at: Long = 0,
    durationMs: Long = 4_000,
    mergeKey: String? = null,
    contentKey: String? = null,
    title: String = id,
) = IslandEvent(
    id = id,
    source = EventSource.TEST,
    type = type,
    priority = priority,
    timestamp = at,
    persistent = false,
    durationMs = durationMs,
    mergeKey = mergeKey,
    contentKey = contentKey,
    title = title,
)
