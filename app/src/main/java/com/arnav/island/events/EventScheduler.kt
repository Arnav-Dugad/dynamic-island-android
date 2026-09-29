package com.arnav.island.events

/**
 * Snapshot of what the island should be showing, produced by [EventScheduler].
 */
data class Schedule(
    /** Highest-priority live activity. */
    val primary: IslandEvent?,
    /** Second live activity, eligible for the split island. */
    val secondary: IslandEvent?,
    /** Temporary event currently on screen, which temporarily covers [primary]. */
    val toast: IslandEvent?,
    val toastEndsAt: Long?,
    val toastHeld: Boolean,
    /** Temporary events waiting their turn, in the order they will be shown. */
    val queued: List<IslandEvent>,
    /** Every visible (non-suppressed) live activity, primary first. */
    val live: List<IslandEvent>,
    val revision: Long,
) {
    fun find(id: String): IslandEvent? =
        if (toast?.id == id) toast else live.firstOrNull { it.id == id }

    companion object {
        val Empty = Schedule(null, null, null, null, false, emptyList(), emptyList(), 0)
    }
}

/**
 * Pure, deterministic event scheduling. Not thread-safe; [EventEngine] serialises access.
 *
 * Semantics:
 *  - **replace**: posting an existing id updates it in place.
 *  - **temporarily interrupt**: a higher-priority toast pre-empts the current one, which goes back
 *    to the front of the queue with its remaining time and **resumes** afterwards.
 *  - **coexist**: live activities stack; the top two become primary/secondary (split island).
 *  - **merge**: toasts sharing a mergeKey collapse into one with a count.
 *  - **queue**: lower-priority toasts wait; stale low-priority toasts are dropped.
 *  - **expire**: events past [IslandEvent.expiresAt] disappear.
 */
class EventScheduler(
    private val clock: () -> Long,
    private val maxQueue: Int = 12,
    private val staleAfterMs: Long = 20_000,
    private val minResumeMs: Long = 1_200,
    private val minRemainingAfterHoldMs: Long = 1_600,
) {
    private val live = LinkedHashMap<String, IslandEvent>()
    private val firstSeen = HashMap<String, Long>()
    private val suppressed = HashMap<String, String?>()
    private val queue = ArrayList<QueuedToast>()

    private var current: IslandEvent? = null
    private var currentEndsAt = 0L
    private var heldRemaining: Long? = null

    var revision = 0L
        private set

    private class QueuedToast(val event: IslandEvent, val remainingMs: Long?, val resumed: Boolean)

    // ---------------------------------------------------------------------------------------------
    // Mutations. Each returns true when the snapshot changed.
    // ---------------------------------------------------------------------------------------------

    fun post(event: IslandEvent): Boolean {
        val now = clock()
        if (event.expiresAt != null && event.expiresAt <= now) return remove(event.id)
        if (event.persistent) postLive(event, now) else postToast(event, now)
        rebalance(now)
        return bump()
    }

    fun remove(id: String): Boolean {
        val now = clock()
        var changed = live.remove(id) != null
        firstSeen.remove(id)
        suppressed.remove(id)
        if (queue.removeAll { it.event.id == id }) changed = true
        if (current?.id == id) {
            current = null
            heldRemaining = null
            changed = true
        }
        if (!changed) return false
        rebalance(now)
        return bump()
    }

    /** Removes every event whose id starts with [prefix] (e.g. all "notif:" events). */
    fun removeWhere(predicate: (IslandEvent) -> Boolean): Boolean {
        val ids = buildList {
            live.values.filter(predicate).forEach { add(it.id) }
            queue.filter { predicate(it.event) }.forEach { add(it.event.id) }
            current?.takeIf(predicate)?.let { add(it.id) }
        }
        var changed = false
        ids.forEach { if (remove(it)) changed = true }
        return changed
    }

    /**
     * User dismissal. Toasts are dropped; live activities are hidden until their content changes
     * (e.g. the next track) or their source removes and re-posts them.
     */
    fun dismiss(id: String): Boolean {
        val now = clock()
        if (current?.id == id) {
            current = null
            heldRemaining = null
            rebalance(now)
            return bump()
        }
        if (queue.removeAll { it.event.id == id }) return bump()
        val event = live[id] ?: return false
        suppressed[id] = event.contentKey
        rebalance(now)
        return bump()
    }

    /** Freezes the toast countdown while the user is touching or reading the island. */
    fun setHeld(held: Boolean): Boolean {
        val now = clock()
        if (current == null) {
            heldRemaining = null
            return false
        }
        if (held && heldRemaining == null) {
            heldRemaining = (currentEndsAt - now).coerceAtLeast(0)
            return bump()
        }
        if (!held && heldRemaining != null) {
            currentEndsAt = now + maxOf(heldRemaining ?: 0, minRemainingAfterHoldMs)
            heldRemaining = null
            return bump()
        }
        return false
    }

    /** Advances time-based state (toast end, expiry). */
    fun tick(): Boolean {
        val before = signature()
        rebalance(clock())
        return if (signature() != before) bump() else false
    }

    fun clear() {
        live.clear()
        firstSeen.clear()
        suppressed.clear()
        queue.clear()
        current = null
        heldRemaining = null
        bump()
    }

    /** The next wall-clock time at which [tick] would change something, or null. */
    fun nextDeadline(): Long? {
        var next = Long.MAX_VALUE
        current?.let { c ->
            if (heldRemaining == null) next = minOf(next, currentEndsAt)
            c.expiresAt?.let { next = minOf(next, it) }
        }
        live.values.forEach { e -> e.expiresAt?.let { next = minOf(next, it) } }
        queue.forEach { q ->
            q.event.expiresAt?.let { next = minOf(next, it) }
            if (!q.resumed && isStaleable(q.event)) next = minOf(next, q.event.timestamp + staleAfterMs)
        }
        return next.takeIf { it != Long.MAX_VALUE }
    }

    fun snapshot(): Schedule {
        val visibleLive = sortedLive()
        return Schedule(
            primary = visibleLive.getOrNull(0),
            secondary = visibleLive.getOrNull(1),
            toast = current,
            toastEndsAt = current?.let { heldRemaining?.let { r -> clock() + r } ?: currentEndsAt },
            toastHeld = heldRemaining != null,
            queued = queue.map { it.event },
            live = visibleLive,
            revision = revision,
        )
    }

    // ---------------------------------------------------------------------------------------------

    private fun postLive(event: IslandEvent, now: Long) {
        // An id can only be one thing at a time.
        if (current?.id == event.id) {
            current = null
            heldRemaining = null
        }
        queue.removeAll { it.event.id == event.id }
        if (suppressed.containsKey(event.id) && suppressed[event.id] != event.contentKey) {
            suppressed.remove(event.id)
        }
        if (!live.containsKey(event.id)) firstSeen[event.id] = now
        live[event.id] = event
    }

    private fun postToast(event: IslandEvent, now: Long) {
        if (live.remove(event.id) != null) firstSeen.remove(event.id)

        val showing = current
        // Replace in place.
        if (showing != null && showing.id == event.id) {
            current = event.copy(mergeCount = maxOf(event.mergeCount, showing.mergeCount))
            if (event.contentKey != showing.contentKey) restartCountdown(event, now)
            return
        }
        // Merge.
        if (event.mergeKey != null) {
            if (showing != null && showing.mergeKey == event.mergeKey) {
                current = event.copy(mergeCount = showing.mergeCount + event.mergeCount)
                restartCountdown(event, now)
                return
            }
            val idx = queue.indexOfFirst { it.event.mergeKey == event.mergeKey }
            if (idx >= 0) {
                val merged = event.copy(mergeCount = queue[idx].event.mergeCount + event.mergeCount)
                queue.removeAt(idx)
                enqueue(QueuedToast(merged, null, resumed = false))
                return
            }
        }
        // Replace a queued copy.
        val queuedIdx = queue.indexOfFirst { it.event.id == event.id }
        if (queuedIdx >= 0) {
            val old = queue.removeAt(queuedIdx)
            enqueue(QueuedToast(event, old.remainingMs, old.resumed))
            return
        }

        if (showing == null) {
            if (canShowToast(event)) show(event, null, now) else enqueue(QueuedToast(event, null, false))
            return
        }
        if (event.priority.rank > showing.priority.rank) {
            // Temporarily interrupt the current toast; it resumes with its remaining time.
            val remaining = heldRemaining ?: (currentEndsAt - now)
            if (remaining >= minResumeMs) enqueue(QueuedToast(showing, remaining, resumed = true))
            show(event, null, now)
        } else {
            enqueue(QueuedToast(event, null, resumed = false))
        }
    }

    private fun rebalance(now: Long) {
        // Expiry.
        val expiredLive = live.values.filter { it.expiresAt != null && it.expiresAt <= now }.map { it.id }
        expiredLive.forEach {
            live.remove(it)
            firstSeen.remove(it)
            suppressed.remove(it)
        }
        queue.removeAll { q ->
            (q.event.expiresAt != null && q.event.expiresAt <= now) ||
                (!q.resumed && isStaleable(q.event) && now - q.event.timestamp >= staleAfterMs)
        }
        current?.let { c ->
            val expired = c.expiresAt != null && c.expiresAt <= now
            val finished = heldRemaining == null && now >= currentEndsAt
            if (expired || finished) {
                current = null
                heldRemaining = null
            }
        }

        // A blocking live activity (incoming call, ringing timer) pushes lesser toasts back.
        val blocker = sortedLive().firstOrNull()?.takeIf { it.blocksToasts }
        current?.let { c ->
            if (blocker != null && c.priority.rank < blocker.priority.rank) {
                val remaining = heldRemaining ?: (currentEndsAt - now)
                if (remaining >= minResumeMs) enqueue(QueuedToast(c, remaining, resumed = true))
                current = null
                heldRemaining = null
            }
        }

        if (current == null) {
            val next = queue.firstOrNull { canShowToast(it.event) }
            if (next != null) {
                queue.remove(next)
                show(next.event, next.remainingMs, now)
            }
        }
    }

    private fun canShowToast(event: IslandEvent): Boolean {
        val blocker = sortedLive().firstOrNull()?.takeIf { it.blocksToasts } ?: return true
        return event.priority.rank >= blocker.priority.rank
    }

    private fun show(event: IslandEvent, remainingMs: Long?, now: Long) {
        current = event
        heldRemaining = null
        currentEndsAt = now + (remainingMs ?: event.durationMs).coerceAtLeast(0)
    }

    private fun restartCountdown(event: IslandEvent, now: Long) {
        if (heldRemaining != null) heldRemaining = event.durationMs else currentEndsAt = now + event.durationMs
    }

    private fun enqueue(item: QueuedToast) {
        // Order: priority desc, resumed items first within a priority, then FIFO.
        var index = queue.size
        for (i in queue.indices) {
            val other = queue[i]
            val higher = item.event.priority.rank > other.event.priority.rank
            val samePriorityResumed = item.event.priority.rank == other.event.priority.rank && item.resumed && !other.resumed
            if (higher || samePriorityResumed) {
                index = i
                break
            }
        }
        queue.add(index, item)
        while (queue.size > maxQueue) queue.removeAt(queue.lastIndex)
    }

    private fun sortedLive(): List<IslandEvent> =
        live.values
            .filter { !suppressed.containsKey(it.id) }
            .sortedWith(
                compareByDescending<IslandEvent> { it.priority.rank }
                    .thenByDescending { firstSeen[it.id] ?: 0L }
            )

    private fun isStaleable(event: IslandEvent) = event.priority.rank <= EventPriority.NOTIFICATION.rank

    private fun signature(): Int {
        var h = current?.id.hashCode()
        h = h * 31 + queue.size
        h = h * 31 + live.size
        h = h * 31 + sortedLive().joinToString { it.id }.hashCode()
        return h
    }

    private fun bump(): Boolean {
        revision++
        return true
    }
}
