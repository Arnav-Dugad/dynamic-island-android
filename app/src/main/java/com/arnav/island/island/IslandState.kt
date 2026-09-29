package com.arnav.island.island

import com.arnav.island.events.IslandEvent

/**
 * Logical island states. "Transitioning" is deliberately not a state: the renderer is always
 * morphing towards the current state, so any state can be interrupted by any other at any time.
 */
sealed interface IslandState {
    /** Not drawn at all (disabled, screen off, fullscreen rules, landscape, ...). */
    data object Hidden : IslandState

    /** Only the camera (or a tiny pill around it) is shown. */
    data object Idle : IslandState

    /** One live activity in its compact form: leading content, camera, trailing content. */
    data class Compact(val event: IslandEvent) : IslandState

    /** Two live activities: a compact pill around the camera plus a detached bubble. */
    data class Split(val primary: IslandEvent, val secondary: IslandEvent) : IslandState

    /** A temporary event (notification, charging, Bluetooth, ringer, ...). */
    data class Toast(val event: IslandEvent) : IslandState

    /** The full interaction card for one event. */
    data class Expanded(val event: IslandEvent) : IslandState

    /** Every running activity as a stack of cards (drag further down from expanded). */
    data class Stack(val events: List<IslandEvent>) : IslandState

    val focusEvent: IslandEvent?
        get() = when (this) {
            is Compact -> event
            is Split -> primary
            is Toast -> event
            is Expanded -> event
            is Stack -> events.firstOrNull()
            else -> null
        }

    /** Large states that fill the width below the status bar. */
    val isLarge: Boolean get() = this is Expanded || this is Stack

    val label: String
        get() = when (this) {
            Hidden -> "Hidden"
            Idle -> "Idle"
            is Compact -> "Compact(${event.id})"
            is Split -> "Split(${primary.id} | ${secondary.id})"
            is Toast -> "Toast(${event.id})"
            is Expanded -> "Expanded(${event.id})"
            is Stack -> "Stack(${events.size})"
        }
}

/** How the renderer should choreograph a change between two states. */
enum class TransitionKind {
    NONE,
    /** Same state, event content updated in place (e.g. a new track, a new percentage). */
    UPDATE,
    /** A different event replaces the current one in the same form. */
    SWAP,
    APPEAR,
    DISAPPEAR,
    /** Idle grows into an activity. */
    BLOOM,
    /** An activity shrinks back to idle. */
    RETRACT,
    EXPAND,
    COLLAPSE,
    /** A toast covers a live activity. */
    INTERRUPT,
    /** A toast ends and the live activity it covered comes back. */
    RESUME,
    /** A second activity buds off into a bubble. */
    SPLIT,
    /** The bubble is absorbed back into the pill. */
    MERGE,
}

data class IslandTransition(val from: IslandState, val to: IslandState, val kind: TransitionKind)

fun classifyTransition(from: IslandState, to: IslandState): TransitionKind {
    if (from == to) return TransitionKind.NONE
    return when {
        from is IslandState.Hidden -> TransitionKind.APPEAR
        to is IslandState.Hidden -> TransitionKind.DISAPPEAR
        from is IslandState.Idle -> if (to.isLarge) TransitionKind.EXPAND else TransitionKind.BLOOM
        to is IslandState.Idle -> if (from.isLarge) TransitionKind.COLLAPSE else TransitionKind.RETRACT
        from.isLarge && to.isLarge && from::class != to::class -> TransitionKind.SWAP
        to.isLarge && !from.isLarge -> TransitionKind.EXPAND
        from.isLarge && !to.isLarge -> TransitionKind.COLLAPSE
        from is IslandState.Compact && to is IslandState.Split -> TransitionKind.SPLIT
        from is IslandState.Split && to is IslandState.Compact -> TransitionKind.MERGE
        to is IslandState.Toast && from !is IslandState.Toast -> TransitionKind.INTERRUPT
        from is IslandState.Toast && to !is IslandState.Toast -> TransitionKind.RESUME
        else -> {
            val a = from.focusEvent
            val b = to.focusEvent
            if (a != null && b != null && a.id == b.id && from::class == to::class) {
                if (from is IslandState.Split && to is IslandState.Split && from.secondary.id != to.secondary.id) {
                    TransitionKind.SWAP
                } else {
                    TransitionKind.UPDATE
                }
            } else {
                TransitionKind.SWAP
            }
        }
    }
}
