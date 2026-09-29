package com.arnav.island.island

import com.arnav.island.FakeClock
import com.arnav.island.events.EventPriority
import com.arnav.island.events.EventScheduler
import com.arnav.island.events.EventType
import com.arnav.island.live
import com.arnav.island.toast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IslandStateMachineTest {

    private lateinit var clock: FakeClock
    private lateinit var scheduler: EventScheduler
    private lateinit var machine: IslandStateMachine

    @Before
    fun setUp() {
        clock = FakeClock()
        scheduler = EventScheduler(clock.fn)
        machine = IslandStateMachine()
    }

    private fun sync() = machine.onSchedule(scheduler.snapshot())

    @Test
    fun `hidden until visible, then idle`() {
        assertEquals(IslandState.Hidden, machine.state)
        val t = machine.onVisibility(true)
        assertEquals(IslandState.Idle, machine.state)
        assertEquals(TransitionKind.APPEAR, t?.kind)
    }

    @Test
    fun `live activity blooms from idle into compact`() {
        machine.onVisibility(true)
        scheduler.post(live("media"))
        val t = sync()
        assertTrue(machine.state is IslandState.Compact)
        assertEquals(TransitionKind.BLOOM, t?.kind)
    }

    @Test
    fun `music then charging toast then back to music`() {
        machine.onVisibility(true)
        scheduler.post(live("media"))
        sync()
        scheduler.post(toast("charging", EventType.CHARGING, at = clock.now, durationMs = 3_000))
        assertEquals(TransitionKind.INTERRUPT, sync()?.kind)
        assertTrue(machine.state is IslandState.Toast)

        clock.advance(3_000)
        scheduler.tick()
        assertEquals(TransitionKind.RESUME, sync()?.kind)
        assertEquals("media", (machine.state as IslandState.Compact).event.id)
    }

    @Test
    fun `two live activities split and merge back`() {
        machine.onVisibility(true)
        scheduler.post(live("media"))
        sync()
        scheduler.post(live("timer", EventType.TIMER))
        assertEquals(TransitionKind.SPLIT, sync()?.kind)
        val split = machine.state as IslandState.Split
        assertEquals("timer", split.primary.id)
        assertEquals("media", split.secondary.id)

        scheduler.remove("timer")
        assertEquals(TransitionKind.MERGE, sync()?.kind)
    }

    @Test
    fun `split can be disabled`() {
        machine.onVisibility(true)
        machine.setOptions(splitEnabled = false, minPriority = null)
        scheduler.post(live("media"))
        scheduler.post(live("timer", EventType.TIMER))
        sync()
        assertTrue(machine.state is IslandState.Compact)
    }

    @Test
    fun `user expand and collapse`() {
        machine.onVisibility(true)
        scheduler.post(live("media"))
        sync()
        assertEquals(TransitionKind.EXPAND, machine.expand()?.kind)
        assertTrue(machine.state is IslandState.Expanded)
        // Content updates keep the card expanded.
        scheduler.post(live("media", contentKey = "next-track"))
        assertEquals(TransitionKind.UPDATE, sync()?.kind)
        assertTrue(machine.state is IslandState.Expanded)
        assertEquals(TransitionKind.COLLAPSE, machine.collapse()?.kind)
        assertTrue(machine.state is IslandState.Compact)
    }

    @Test
    fun `incoming call auto expands and can be collapsed to compact`() {
        machine.onVisibility(true)
        scheduler.post(live("call", EventType.CALL_INCOMING, autoExpand = true, blocksToasts = true))
        sync()
        assertTrue(machine.state is IslandState.Expanded)
        machine.collapse()
        assertTrue(machine.state is IslandState.Compact)
        // Stays collapsed across updates of the same call.
        scheduler.post(live("call", EventType.CALL_INCOMING, autoExpand = true, blocksToasts = true, contentKey = "x"))
        sync()
        assertTrue(machine.state is IslandState.Compact)
    }

    @Test
    fun `expanded event disappearing falls back deterministically`() {
        machine.onVisibility(true)
        scheduler.post(live("media"))
        sync()
        machine.expand()
        scheduler.remove("media")
        sync()
        assertEquals(IslandState.Idle, machine.state)
        assertTrue(!machine.expandedByUser)
    }

    @Test
    fun `important only filter hides low priority events`() {
        machine.onVisibility(true)
        machine.setOptions(splitEnabled = true, minPriority = EventPriority.TIMER)
        scheduler.post(live("media"))
        sync()
        assertEquals(IslandState.Idle, machine.state)
        scheduler.post(live("timer", EventType.TIMER))
        sync()
        assertTrue(machine.state is IslandState.Compact)
    }

    @Test
    fun `hiding clears user expansion`() {
        machine.onVisibility(true)
        scheduler.post(live("media"))
        sync()
        machine.expand()
        machine.onVisibility(false)
        assertEquals(IslandState.Hidden, machine.state)
        machine.onVisibility(true)
        assertTrue(machine.state is IslandState.Compact)
    }

    @Test
    fun `same state produces no transition`() {
        machine.onVisibility(true)
        assertNull(machine.onVisibility(true))
    }
}
