package com.arnav.island.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Central, thread-safe entry point for every event source. Wraps the pure [EventScheduler] and
 * publishes immutable [Schedule] snapshots. Time-based changes (toast end, expiry) are driven by a
 * single delayed job aimed at the scheduler's next deadline, so an idle island costs no wakeups.
 */
class EventEngine(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val scheduler = EventScheduler(clock)
    private val _schedule = MutableStateFlow(Schedule.Empty)
    val schedule: StateFlow<Schedule> = _schedule.asStateFlow()

    private var tickJob: Job? = null

    /** Optional gate applied before an event is accepted (per-app rules, feature toggles). */
    @Volatile
    var filter: (IslandEvent) -> Boolean = { true }

    fun post(event: IslandEvent) {
        if (!filter(event)) return
        mutate { scheduler.post(event) }
    }

    fun remove(id: String) = mutate { scheduler.remove(id) }

    fun removeWhere(predicate: (IslandEvent) -> Boolean) = mutate { scheduler.removeWhere(predicate) }

    fun removeSource(source: EventSource) = removeWhere { it.source == source }

    fun dismiss(id: String) = mutate { scheduler.dismiss(id) }

    fun hold(held: Boolean) = mutate { scheduler.setHeld(held) }

    fun clear() = mutate {
        scheduler.clear()
        true
    }

    fun current(): Schedule = _schedule.value

    private inline fun mutate(block: () -> Boolean) {
        synchronized(lock) {
            if (block()) publishLocked()
        }
    }

    private fun publishLocked() {
        _schedule.value = scheduler.snapshot()
        scheduleTickLocked(minWait = 0)
    }

    private fun scheduleTickLocked(minWait: Long) {
        tickJob?.cancel()
        tickJob = null
        val deadline = scheduler.nextDeadline() ?: return
        val wait = maxOf((deadline - clock()).coerceAtLeast(0) + TICK_SLACK_MS, minWait)
        tickJob = scope.launch {
            delay(wait)
            synchronized(lock) {
                tickJob = null
                if (scheduler.tick()) publishLocked() else scheduleTickLocked(minWait = IDLE_RETRY_MS)
            }
        }
    }

    private companion object {
        const val TICK_SLACK_MS = 12L

        /** Guard against a deadline that produced no change (never spin). */
        const val IDLE_RETRY_MS = 250L
    }
}
