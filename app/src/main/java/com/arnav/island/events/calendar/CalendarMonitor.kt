package com.arnav.island.events.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.arnav.island.events.ActionStyle
import com.arnav.island.events.CalendarPayload
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventColors
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandAction
import com.arnav.island.events.IslandEvent
import com.arnav.island.island.render.presenters.CalendarPresenter
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Diagnostics
import com.arnav.island.util.Launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Meeting in 5 min": the next timed event from the phone's own calendars appears as a live
 * activity [IslandSettings.calendarLeadMinutes] before it starts and stays until a few minutes in.
 * Needs the calendar permission, which Island only asks for when this is turned on. Events are
 * read on demand and never stored.
 */
class CalendarMonitor(
    private val context: Context,
    private val engine: EventEngine,
    private val scope: CoroutineScope,
    private val settings: () -> IslandSettings,
) {
    private data class Next(val id: Long, val title: String, val begin: Long, val end: Long, val location: String, val description: String, val color: Int)

    private val handler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var observing = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    fun hasPermission(): Boolean = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!settings().calendarEnabled || !hasPermission()) {
            stop()
            return
        }
        if (!observing) {
            context.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observer)
            observing = true
        }
        refresh()
    }

    fun stop() {
        job?.cancel()
        job = null
        if (observing) {
            context.contentResolver.unregisterContentObserver(observer)
            observing = false
        }
        engine.remove(ID)
    }

    /** Re-reads the calendar and schedules the next check. */
    fun refresh() {
        if (!settings().calendarEnabled || !hasPermission()) {
            stop()
            return
        }
        job?.cancel()
        job = scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val next = withContext(Dispatchers.IO) { query(now) }
                val lead = settings().calendarLeadMinutes.coerceIn(1, 120) * 60_000L
                val wait: Long
                if (next != null && now >= next.begin - lead && now < next.begin + LINGER_MS) {
                    post(next)
                    wait = (next.begin + LINGER_MS - now).coerceAtMost(60_000L)
                } else {
                    engine.remove(ID)
                    wait = if (next != null) (next.begin - lead - now).coerceIn(15_000L, 15 * 60_000L) else 15 * 60_000L
                }
                delay(wait)
            }
        }
    }

    private fun query(now: Long): Next? {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, now - LINGER_MS)
            ContentUris.appendId(it, now + LOOKAHEAD_MS)
            it.build()
        }
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val begin = c.getLong(2)
                    if (c.getInt(7) != 0) continue
                    if (c.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    if (begin + LINGER_MS < now) continue
                    return@use Next(c.getLong(0), c.getString(1).orEmpty(), begin, c.getLong(3), c.getString(4).orEmpty(), c.getString(5).orEmpty(), c.getInt(6))
                }
                null
            }
        } catch (e: SecurityException) {
            Diagnostics.w(TAG, "Calendar permission missing", e)
            null
        }
    }

    private fun post(next: Next) {
        val join = joinLink(next)
        val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, next.id)
        val openIntent = Intent(Intent.ACTION_VIEW, eventUri)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, next.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, next.end)
        val open = IslandAction(CalendarPresenter.ACTION_OPEN, "Open", Glyph.CALENDAR, collapses = true) { Launch.startActivity(context, openIntent) }
        val actions = buildList {
            if (join != null) {
                add(IslandAction(CalendarPresenter.ACTION_JOIN, "Join", Glyph.VIDEO, ActionStyle.POSITIVE, collapses = true) {
                    Launch.startActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(join)))
                })
            }
            add(open)
        }
        engine.post(
            IslandEvent(
                id = ID,
                source = EventSource.SYSTEM,
                type = EventType.CALENDAR,
                timestamp = System.currentTimeMillis(),
                persistent = true,
                title = next.title.ifBlank { "Event" },
                subtitle = next.location,
                icon = Glyph.CALENDAR,
                actions = actions,
                colors = EventColors(accent = next.color),
                tapAction = open,
                contentKey = "${next.id}|${next.begin}",
                animation = EntranceAnimation.BLOOM,
                payload = CalendarPayload(next.title, next.begin, next.end, next.location, next.color, join != null),
            )
        )
    }

    /** A video meeting link in the location or description, if there is one. */
    private fun joinLink(next: Next): String? =
        MEETING.find(next.location)?.value ?: MEETING.find(next.description)?.value

    companion object {
        const val ID = "calendar"
        private const val TAG = "IslandCalendar"
        private const val LINGER_MS = 5 * 60_000L
        private const val LOOKAHEAD_MS = 24 * 60 * 60_000L
        private val MEETING = Regex("""https://(?:[\w-]+\.)*(?:meet\.google\.com|zoom\.us|teams\.microsoft\.com|teams\.live\.com|webex\.com)/\S+""")
    }
}
