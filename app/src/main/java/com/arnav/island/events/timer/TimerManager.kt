package com.arnav.island.events.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.arnav.island.events.ActionStyle
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandAction
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.StopwatchPayload
import com.arnav.island.events.TimerPayload
import com.arnav.island.island.render.presenters.TimerPresenter
import com.arnav.island.storage.IslandSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class TimerItem(
    val id: Long,
    val label: String,
    val totalMs: Long,
    val endsAt: Long,
    val remainingWhenPausedMs: Long,
    val paused: Boolean,
    val ringing: Boolean,
    val ringingSince: Long,
) {
    fun remaining(now: Long) = when {
        ringing -> 0L
        paused -> remainingWhenPausedMs
        else -> (endsAt - now).coerceAtLeast(0)
    }
}

data class StopwatchState(
    val running: Boolean = false,
    val runningSince: Long = 0L,
    val accumulatedMs: Long = 0L,
    val laps: List<Long> = emptyList(),
) {
    val active: Boolean get() = running || accumulatedMs > 0
    fun elapsed(now: Long) = accumulatedMs + if (running) (now - runningSince).coerceAtLeast(0) else 0
}

data class TimerState(val timers: List<TimerItem> = emptyList(), val stopwatch: StopwatchState = StopwatchState())

private val Context.timerStore: DataStore<Preferences> by preferencesDataStore(name = "island_timers")

/**
 * Local timers and stopwatch. State is persisted with absolute (wall-clock) end times, so timers
 * keep running across process death and reboots; completion is delivered by AlarmManager even
 * when no Island UI exists.
 */
class TimerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val engine: EventEngine,
    private val settings: () -> IslandSettings,
) {
    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state.asStateFlow()
    private val loaded = CompletableDeferred<Unit>()
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val completionJobs = HashMap<Long, Job>()
    private val silenceJobs = HashMap<Long, Job>()
    private val key = stringPreferencesKey("state")

    init {
        scope.launch {
            val json = try {
                context.timerStore.data.first()[key]
            } catch (e: Exception) {
                Log.w(TAG, "Timer state unreadable", e)
                null
            }
            _state.value = json?.let(::decode) ?: TimerState()
            loaded.complete(Unit)
            // Timers that finished while the process was dead ring now.
            val now = System.currentTimeMillis()
            _state.value.timers.filter { !it.paused && !it.ringing && it.endsAt <= now }.forEach { markRinging(it.id) }
            sync()
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    val canScheduleExact: Boolean get() = alarms.canScheduleExactAlarms()

    // ---------------------------------------------------------------------------------------------
    // Timer operations
    // ---------------------------------------------------------------------------------------------

    fun start(durationMs: Long, label: String = "") {
        if (durationMs <= 0) return
        val now = System.currentTimeMillis()
        val item = TimerItem(now, label.trim(), durationMs, now + durationMs, durationMs, paused = false, ringing = false, ringingSince = 0)
        commit(_state.value.copy(timers = _state.value.timers + item))
    }

    fun togglePause(id: Long) {
        val t = find(id) ?: return
        if (t.ringing) return
        if (t.paused) resume(id) else pause(id)
    }

    fun pause(id: Long) = updateTimer(id) { t ->
        val now = System.currentTimeMillis()
        if (t.paused || t.ringing) t else t.copy(paused = true, remainingWhenPausedMs = (t.endsAt - now).coerceAtLeast(0))
    }

    fun resume(id: Long) = updateTimer(id) { t ->
        if (!t.paused) t else t.copy(paused = false, endsAt = System.currentTimeMillis() + t.remainingWhenPausedMs)
    }

    fun addMinute(id: Long) = updateTimer(id) { t ->
        val now = System.currentTimeMillis()
        when {
            t.ringing -> t.copy(ringing = false, ringingSince = 0, paused = false, totalMs = MINUTE, endsAt = now + MINUTE, remainingWhenPausedMs = MINUTE)
            t.paused -> t.copy(totalMs = t.totalMs + MINUTE, remainingWhenPausedMs = t.remainingWhenPausedMs + MINUTE)
            else -> t.copy(totalMs = t.totalMs + MINUTE, endsAt = t.endsAt + MINUTE)
        }
    }.also { TimerNotifications.cancelDone(context, id) }

    fun cancel(id: Long) {
        TimerNotifications.cancelDone(context, id)
        commit(_state.value.copy(timers = _state.value.timers.filterNot { it.id == id }))
    }

    /** Stops a ringing timer (it is finished, so it is removed). */
    fun stopRinging(id: Long) = cancel(id)

    fun onAlarm(id: Long) {
        val t = find(id) ?: return
        if (!t.ringing && !t.paused) markRinging(id)
    }

    // ---------------------------------------------------------------------------------------------
    // Stopwatch
    // ---------------------------------------------------------------------------------------------

    fun stopwatchToggle() {
        val sw = _state.value.stopwatch
        val now = System.currentTimeMillis()
        val next = if (sw.running) {
            sw.copy(running = false, accumulatedMs = sw.elapsed(now))
        } else {
            sw.copy(running = true, runningSince = now)
        }
        commit(_state.value.copy(stopwatch = next))
    }

    fun stopwatchLap() {
        val sw = _state.value.stopwatch
        if (!sw.running) return
        commit(_state.value.copy(stopwatch = sw.copy(laps = sw.laps + sw.elapsed(System.currentTimeMillis()))))
    }

    fun stopwatchReset() = commit(_state.value.copy(stopwatch = StopwatchState()))

    // ---------------------------------------------------------------------------------------------

    private fun find(id: Long) = _state.value.timers.firstOrNull { it.id == id }

    private fun updateTimer(id: Long, transform: (TimerItem) -> TimerItem) {
        commit(_state.value.copy(timers = _state.value.timers.map { if (it.id == id) transform(it) else it }))
    }

    private fun markRinging(id: Long) {
        val now = System.currentTimeMillis()
        updateTimer(id) { it.copy(ringing = true, ringingSince = now, paused = false) }
        find(id)?.let { TimerNotifications.showDone(context, it) }
        silenceJobs[id]?.cancel()
        silenceJobs[id] = scope.launch {
            delay(RING_TIMEOUT_MS)
            // Stop occupying the island after a minute; the notification stays in the shade.
            if (find(id)?.ringing == true) commit(_state.value.copy(timers = _state.value.timers.filterNot { it.id == id }))
        }
    }

    private fun commit(newState: TimerState) {
        _state.value = newState
        scope.launch {
            try {
                context.timerStore.edit { it[key] = encode(newState) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist timers", e)
            }
        }
        sync()
    }

    /** Publishes island events and (re)schedules alarms to match the current state. */
    private fun sync() {
        val s = _state.value
        val now = System.currentTimeMillis()
        val enabled = settings().timersEnabled
        val liveIds = HashSet<String>()

        for (t in s.timers) {
            val eventId = "timer:${t.id}"
            liveIds += eventId
            if (!t.paused && !t.ringing) scheduleAlarm(t) else cancelAlarm(t.id)
            scheduleCompletion(t, now)
            if (enabled) engine.post(timerEvent(t, now))
        }
        completionJobs.keys.filter { id -> s.timers.none { it.id == id } }.forEach { id ->
            completionJobs.remove(id)?.cancel()
            cancelAlarm(id)
        }

        if (s.stopwatch.active && settings().stopwatchEnabled) {
            liveIds += STOPWATCH_ID
            engine.post(stopwatchEvent(s.stopwatch, now))
        }
        engine.removeWhere { (it.source == EventSource.TIMER || it.source == EventSource.STOPWATCH) && it.id !in liveIds }
        if (!enabled) engine.removeWhere { it.source == EventSource.TIMER }
    }

    fun resync() = sync()

    private fun scheduleCompletion(t: TimerItem, now: Long) {
        completionJobs.remove(t.id)?.cancel()
        if (t.paused || t.ringing) return
        completionJobs[t.id] = scope.launch {
            delay((t.endsAt - now).coerceAtLeast(0))
            onAlarm(t.id)
        }
    }

    private fun alarmIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        (id % Int.MAX_VALUE).toInt(),
        Intent(context, TimerAlarmReceiver::class.java).putExtra(TimerAlarmReceiver.EXTRA_TIMER_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scheduleAlarm(t: TimerItem) {
        val pi = alarmIntent(t.id)
        try {
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endsAt, pi)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endsAt, pi)
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endsAt, pi)
        }
    }

    private fun cancelAlarm(id: Long) = alarms.cancel(alarmIntent(id))

    private fun timerEvent(t: TimerItem, now: Long): IslandEvent {
        val actions = if (t.ringing) {
            listOf(
                IslandAction(TimerPresenter.ACTION_ADD, "+1 min", Glyph.PLUS) { addMinute(t.id) },
                IslandAction(TimerPresenter.ACTION_STOP, "Stop", Glyph.STOP, ActionStyle.PRIMARY, collapses = true) { stopRinging(t.id) },
            )
        } else {
            listOf(
                IslandAction(TimerPresenter.ACTION_TOGGLE, if (t.paused) "Resume" else "Pause", if (t.paused) Glyph.PLAY else Glyph.PAUSE) { togglePause(t.id) },
                IslandAction(TimerPresenter.ACTION_SECONDARY, "Cancel", Glyph.CLOSE, collapses = true) { cancel(t.id) },
                IslandAction(TimerPresenter.ACTION_ADD, "+1 min", Glyph.PLUS) { addMinute(t.id) },
            )
        }
        return IslandEvent(
            id = "timer:${t.id}",
            source = EventSource.TIMER,
            type = if (t.ringing) EventType.TIMER_DONE else EventType.TIMER,
            timestamp = now,
            persistent = true,
            title = t.label.ifBlank { "Timer" },
            icon = Glyph.TIMER,
            actions = actions,
            autoExpand = t.ringing,
            blocksToasts = t.ringing,
            dismissible = !t.ringing,
            contentKey = "${t.id}:${t.paused}:${t.ringing}",
            tapAction = if (t.ringing) actions.last() else null,
            payload = TimerPayload(t.id, t.label, t.totalMs, t.endsAt, t.remainingWhenPausedMs, t.paused, t.ringing, t.ringingSince),
        )
    }

    private fun stopwatchEvent(sw: StopwatchState, now: Long): IslandEvent = IslandEvent(
        id = STOPWATCH_ID,
        source = EventSource.STOPWATCH,
        type = EventType.STOPWATCH,
        timestamp = now,
        persistent = true,
        title = "Stopwatch",
        icon = Glyph.STOPWATCH,
        actions = listOf(
            IslandAction(TimerPresenter.ACTION_TOGGLE, if (sw.running) "Stop" else "Start", if (sw.running) Glyph.STOP else Glyph.PLAY) { stopwatchToggle() },
            if (sw.running) {
                IslandAction(TimerPresenter.ACTION_SECONDARY, "Lap", Glyph.FLAG) { stopwatchLap() }
            } else {
                IslandAction(TimerPresenter.ACTION_SECONDARY, "Reset", Glyph.CLOSE, collapses = true) { stopwatchReset() }
            },
        ),
        contentKey = "stopwatch:${sw.running}",
        payload = StopwatchPayload(sw.runningSince, sw.accumulatedMs, sw.running, sw.laps),
    )

    // ---------------------------------------------------------------------------------------------

    private fun encode(s: TimerState): String = JSONObject().apply {
        put("timers", JSONArray().apply {
            s.timers.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id)
                    put("label", t.label)
                    put("total", t.totalMs)
                    put("endsAt", t.endsAt)
                    put("remaining", t.remainingWhenPausedMs)
                    put("paused", t.paused)
                    put("ringing", t.ringing)
                    put("ringingSince", t.ringingSince)
                })
            }
        })
        put("stopwatch", JSONObject().apply {
            put("running", s.stopwatch.running)
            put("since", s.stopwatch.runningSince)
            put("acc", s.stopwatch.accumulatedMs)
            put("laps", JSONArray(s.stopwatch.laps))
        })
    }.toString()

    private fun decode(json: String): TimerState? = try {
        val root = JSONObject(json)
        val arr = root.optJSONArray("timers") ?: JSONArray()
        val timers = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TimerItem(
                id = o.getLong("id"),
                label = o.optString("label"),
                totalMs = o.optLong("total"),
                endsAt = o.optLong("endsAt"),
                remainingWhenPausedMs = o.optLong("remaining"),
                paused = o.optBoolean("paused"),
                ringing = o.optBoolean("ringing"),
                ringingSince = o.optLong("ringingSince"),
            )
        }
        val sw = root.optJSONObject("stopwatch")
        val laps = sw?.optJSONArray("laps")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
        TimerState(
            timers = timers.filterNot { it.ringing && System.currentTimeMillis() - it.ringingSince > RING_TIMEOUT_MS },
            stopwatch = StopwatchState(
                running = sw?.optBoolean("running") ?: false,
                runningSince = sw?.optLong("since") ?: 0L,
                accumulatedMs = sw?.optLong("acc") ?: 0L,
                laps = laps,
            ),
        )
    } catch (e: org.json.JSONException) {
        Log.w(TAG, "Discarding corrupt timer state", e)
        null
    }

    companion object {
        private const val TAG = "IslandTimers"
        const val STOPWATCH_ID = "stopwatch"
        private const val MINUTE = 60_000L
        private const val RING_TIMEOUT_MS = 60_000L
    }
}
