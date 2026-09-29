package com.arnav.island.events

import com.arnav.island.FakeClock
import com.arnav.island.live
import com.arnav.island.toast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventSchedulerTest {

    private lateinit var clock: FakeClock
    private lateinit var scheduler: EventScheduler

    @Before
    fun setUp() {
        clock = FakeClock()
        scheduler = EventScheduler(clock.fn)
    }

    @Test
    fun `live activities are ordered by priority`() {
        scheduler.post(live("media", EventType.MEDIA, at = clock.now))
        scheduler.post(live("timer", EventType.TIMER, at = clock.now))
        val s = scheduler.snapshot()
        assertEquals("timer", s.primary?.id)
        assertEquals("media", s.secondary?.id)
    }

    @Test
    fun `equal priority prefers the most recently started activity`() {
        scheduler.post(live("timer:1", EventType.TIMER))
        clock.advance(10)
        scheduler.post(live("timer:2", EventType.TIMER))
        assertEquals("timer:2", scheduler.snapshot().primary?.id)
        // Updating the older one must not reorder it (ordering is by first appearance).
        clock.advance(10)
        scheduler.post(live("timer:1", EventType.TIMER, contentKey = "tick"))
        assertEquals("timer:2", scheduler.snapshot().primary?.id)
    }

    @Test
    fun `posting the same id replaces in place`() {
        scheduler.post(live("media", contentKey = "a"))
        scheduler.post(live("media", contentKey = "b"))
        val s = scheduler.snapshot()
        assertEquals(1, s.live.size)
        assertEquals("b", s.primary?.contentKey)
    }

    @Test
    fun `toast shows immediately then expires and the live activity resumes`() {
        scheduler.post(live("media"))
        scheduler.post(toast("charging", EventType.CHARGING, at = clock.now, durationMs = 3_000))
        assertEquals("charging", scheduler.snapshot().toast?.id)
        assertEquals("media", scheduler.snapshot().primary?.id)

        clock.advance(2_999)
        scheduler.tick()
        assertEquals("charging", scheduler.snapshot().toast?.id)

        clock.advance(2)
        scheduler.tick()
        assertNull(scheduler.snapshot().toast)
        assertEquals("media", scheduler.snapshot().primary?.id)
    }

    @Test
    fun `higher priority toast interrupts and the interrupted toast resumes with remaining time`() {
        scheduler.post(toast("notif", EventType.NOTIFICATION, at = clock.now, durationMs = 4_000))
        clock.advance(1_000)
        scheduler.post(toast("lowbatt", EventType.BATTERY_LOW, at = clock.now, durationMs = 2_000))
        assertEquals("lowbatt", scheduler.snapshot().toast?.id)
        assertEquals(listOf("notif"), scheduler.snapshot().queued.map { it.id })

        clock.advance(2_000)
        scheduler.tick()
        val s = scheduler.snapshot()
        assertEquals("notif", s.toast?.id)
        // 3 s were left when it was interrupted.
        assertEquals(clock.now + 3_000, s.toastEndsAt)
    }

    @Test
    fun `lower priority toast waits in the queue`() {
        scheduler.post(toast("bt", EventType.BLUETOOTH, at = clock.now, durationMs = 2_000))
        scheduler.post(toast("msg", EventType.NOTIFICATION, at = clock.now))
        assertEquals("bt", scheduler.snapshot().toast?.id)
        assertEquals(listOf("msg"), scheduler.snapshot().queued.map { it.id })
        clock.advance(2_000)
        scheduler.tick()
        assertEquals("msg", scheduler.snapshot().toast?.id)
    }

    @Test
    fun `toasts with the same merge key are merged with a count`() {
        scheduler.post(toast("n1", mergeKey = "chat", at = clock.now, title = "first"))
        scheduler.post(toast("n2", mergeKey = "chat", at = clock.now, title = "second"))
        val s = scheduler.snapshot()
        assertEquals("second", s.toast?.title)
        assertEquals(2, s.toast?.mergeCount)
        assertTrue(s.queued.isEmpty())
    }

    @Test
    fun `stale low priority toasts are dropped from the queue`() {
        scheduler.post(toast("call", EventType.BATTERY_LOW, at = clock.now, durationMs = 60_000))
        scheduler.post(toast("msg", EventType.NOTIFICATION, at = clock.now))
        clock.advance(25_000)
        scheduler.tick()
        assertTrue(scheduler.snapshot().queued.isEmpty())
    }

    @Test
    fun `expired live events are removed on tick`() {
        scheduler.post(live("nav", EventType.NAVIGATION, expiresAt = clock.now + 500))
        assertEquals("nav", scheduler.snapshot().primary?.id)
        assertEquals(clock.now + 500, scheduler.nextDeadline())
        clock.advance(500)
        scheduler.tick()
        assertNull(scheduler.snapshot().primary)
    }

    @Test
    fun `dismissed live activity stays hidden until its content changes`() {
        scheduler.post(live("media", contentKey = "song-a"))
        scheduler.dismiss("media")
        assertNull(scheduler.snapshot().primary)
        scheduler.post(live("media", contentKey = "song-a"))
        assertNull(scheduler.snapshot().primary)
        scheduler.post(live("media", contentKey = "song-b"))
        assertEquals("media", scheduler.snapshot().primary?.id)
    }

    @Test
    fun `holding freezes the countdown`() {
        scheduler.post(toast("msg", at = clock.now, durationMs = 4_000))
        clock.advance(1_000)
        scheduler.setHeld(true)
        clock.advance(30_000)
        scheduler.tick()
        assertEquals("msg", scheduler.snapshot().toast?.id)
        scheduler.setHeld(false)
        clock.advance(2_999)
        scheduler.tick()
        assertEquals("msg", scheduler.snapshot().toast?.id)
        clock.advance(2)
        scheduler.tick()
        assertNull(scheduler.snapshot().toast)
    }

    @Test
    fun `blocking live activity holds back lesser toasts until it ends`() {
        scheduler.post(toast("msg", at = clock.now, durationMs = 5_000))
        scheduler.post(live("call", com.arnav.island.events.EventType.CALL_INCOMING, blocksToasts = true))
        assertNull(scheduler.snapshot().toast)
        assertEquals(listOf("msg"), scheduler.snapshot().queued.map { it.id })
        scheduler.remove("call")
        assertEquals("msg", scheduler.snapshot().toast?.id)
    }

    @Test
    fun `next deadline tracks the visible toast`() {
        assertNull(scheduler.nextDeadline())
        scheduler.post(toast("msg", at = clock.now, durationMs = 1_500))
        assertEquals(clock.now + 1_500, scheduler.nextDeadline())
    }
}
