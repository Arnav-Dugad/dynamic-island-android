package com.arnav.island.island

import com.arnav.island.events.EventPriority
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.Schedule

/**
 * The single owner of island state. Every input funnels through here and the resulting state is a
 * pure function of (visibility, schedule, user intent, options), so transitions are deterministic
 * and unit-testable. Renderers never decide *what* to show, only *how* to animate to it.
 */
class IslandStateMachine {

    var state: IslandState = IslandState.Hidden
        private set

    private var visible = false
    private var schedule = Schedule.Empty
    private var splitEnabled = true
    private var minPriority: EventPriority? = null

    /** Event the user explicitly expanded. Cleared when it disappears or the user collapses. */
    private var userExpandedId: String? = null

    /** Auto-expanding event (e.g. incoming call) the user chose to collapse to its compact form. */
    private var userCollapsedId: String? = null

    /** The user asked to see every running activity at once. */
    private var userStack = false

    val expandedByUser: Boolean get() = userExpandedId != null || userStack

    fun onSchedule(newSchedule: Schedule): IslandTransition? {
        schedule = newSchedule
        return commit()
    }

    fun onVisibility(isVisible: Boolean): IslandTransition? {
        visible = isVisible
        if (!isVisible) {
            userExpandedId = null
            userStack = false
        }
        return commit()
    }

    fun setOptions(splitEnabled: Boolean, minPriority: EventPriority?): IslandTransition? {
        this.splitEnabled = splitEnabled
        this.minPriority = minPriority
        return commit()
    }

    /** Expands [eventId], or the currently focused event when null. */
    fun expand(eventId: String? = null): IslandTransition? {
        val target = eventId ?: state.focusEvent?.id ?: return null
        if (schedule.find(target)?.let(::allowed) != true) return null
        userExpandedId = target
        userStack = false
        if (userCollapsedId == target) userCollapsedId = null
        return commit()
    }

    /** Shows every running activity. Needs at least two; otherwise nothing changes. */
    fun showStack(): IslandTransition? {
        if (stackCandidates().size < 2) return null
        userStack = true
        userExpandedId = null
        return commit()
    }

    fun collapse(): IslandTransition? {
        val current = state
        if (current is IslandState.Stack) {
            userStack = false
            return commit()
        }
        if (current !is IslandState.Expanded) return null
        if (current.event.autoExpand) userCollapsedId = current.event.id
        userExpandedId = null
        return commit()
    }

    /** Forget interaction state tied to an event the user swiped away. */
    fun onDismissed(eventId: String): IslandTransition? {
        if (userExpandedId == eventId) userExpandedId = null
        return commit()
    }

    private fun commit(): IslandTransition? {
        val next = derive()
        val previous = state
        if (next == previous) return null
        state = next
        return IslandTransition(previous, next, classifyTransition(previous, next))
    }

    private fun stackCandidates(): List<IslandEvent> =
        listOfNotNull(schedule.toast?.takeIf(::allowed)) + schedule.live.filter(::allowed)

    private fun derive(): IslandState {
        if (!visible) return IslandState.Hidden

        val toast = schedule.toast?.takeIf(::allowed)
        val live = schedule.live.filter(::allowed)
        val primary = live.getOrNull(0)
        val secondary = live.getOrNull(1)

        if (userStack) {
            val items = stackCandidates()
            if (items.size >= 2) return IslandState.Stack(items)
            userStack = false
        }

        userExpandedId?.let { id ->
            val event = (if (toast?.id == id) toast else live.firstOrNull { it.id == id })
            if (event != null) return IslandState.Expanded(event)
            userExpandedId = null
        }
        if (userCollapsedId != null && live.none { it.id == userCollapsedId } && toast?.id != userCollapsedId) {
            userCollapsedId = null
        }

        if (toast != null) {
            return if (toast.autoExpand && userCollapsedId != toast.id) IslandState.Expanded(toast) else IslandState.Toast(toast)
        }
        if (primary != null) {
            if (primary.autoExpand && userCollapsedId != primary.id) return IslandState.Expanded(primary)
            if (secondary != null && splitEnabled) return IslandState.Split(primary, secondary)
            return IslandState.Compact(primary)
        }
        return IslandState.Idle
    }

    private fun allowed(event: IslandEvent): Boolean {
        val min = minPriority ?: return true
        return event.priority.rank >= min.rank
    }
}
