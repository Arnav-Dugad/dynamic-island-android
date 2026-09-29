package com.arnav.island.island

import android.graphics.RectF
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventPriority
import com.arnav.island.events.EventType
import com.arnav.island.events.IslandAction
import com.arnav.island.events.IslandEvent
import com.arnav.island.island.render.IslandHaptics
import com.arnav.island.island.render.IslandScene
import com.arnav.island.island.render.IslandSounds
import com.arnav.island.island.render.IslandView
import com.arnav.island.storage.IslandSettings
import com.arnav.island.storage.TapAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Glue between the [EventEngine], the [IslandStateMachine] and an [IslandView]. Translates user
 * gestures into state-machine intents and event actions. Used by the overlay and the in-app
 * preview alike.
 */
class IslandController(
    private val scope: CoroutineScope,
    private val engine: EventEngine,
    private val view: IslandView,
) : IslandView.Host {

    val machine = IslandStateMachine()
    private var settings = IslandSettings()
    private var minPriority: EventPriority? = null
    private var scheduleJob: Job? = null
    private var autoCollapseJob: Job? = null
    private var burnInJob: Job? = null
    private var pressed = false

    var onStateChanged: ((IslandTransition) -> Unit)? = null
    var onBounds: ((RectF, Boolean) -> Unit)? = null
    var onAnimating: ((Boolean) -> Unit)? = null

    /** Builds the Glance card for a long-press on the idle island (null: feature unavailable). */
    var glanceProvider: (() -> IslandEvent?)? = null

    val state: IslandState get() = machine.state

    fun start() {
        view.host = this
        view.rc.onStackPick = { id -> expand(id) }
        scheduleJob = scope.launch {
            engine.schedule.collect { apply(machine.onSchedule(it)) }
        }
    }

    fun stop() {
        scheduleJob?.cancel()
        autoCollapseJob?.cancel()
        burnInJob?.cancel()
        view.host = null
    }

    fun updateSettings(s: IslandSettings) {
        settings = s
        apply(machine.setOptions(s.splitEnabled, minPriority))
        updateBurnIn()
    }

    fun setVisibility(visible: Boolean, minPriority: EventPriority?, immediate: Boolean) {
        this.minPriority = minPriority
        apply(machine.setOptions(settings.splitEnabled, minPriority), immediate)
        apply(machine.onVisibility(visible), immediate)
        updateBurnIn()
    }

    private fun apply(transition: IslandTransition?, immediate: Boolean = false) {
        transition ?: return
        view.render(transition.to, transition.kind, immediate)
        if (!immediate) playTransitionFeedback(transition)
        updateHold()
        scheduleAutoCollapse()
        onStateChanged?.invoke(transition)
    }

    /**
     * Haptics and sounds that belong to a state change rather than a touch: an arrival tick for
     * island-only moments (notifications already buzz on their own), and the optional sounds.
     */
    private fun playTransitionFeedback(t: IslandTransition) {
        val sounds = view.rc.sounds
        when (t.kind) {
            TransitionKind.EXPAND -> sounds?.play(IslandSounds.Sound.EXPAND)
            TransitionKind.COLLAPSE -> sounds?.play(IslandSounds.Sound.COLLAPSE)
            TransitionKind.INTERRUPT, TransitionKind.BLOOM -> {
                val event = (t.to as? IslandState.Toast)?.event ?: return
                if (event.type != EventType.NOTIFICATION && settings.haptics) {
                    view.rc.haptics?.play(IslandHaptics.Cue.ARRIVAL, touch = false)
                }
                sounds?.play(IslandSounds.Sound.ARRIVAL)
            }
            else -> Unit
        }
    }

    /** Toast countdowns pause while touched or while the toast is expanded. */
    private fun updateHold() {
        val st = machine.state
        engine.hold(pressed || (st is IslandState.Expanded && st.event.isTransient))
    }

    private fun scheduleAutoCollapse() {
        autoCollapseJob?.cancel()
        val st = machine.state
        if (!st.isLarge || !machine.expandedByUser || settings.autoCollapseSeconds <= 0) return
        autoCollapseJob = scope.launch {
            delay(settings.autoCollapseSeconds * 1_000L)
            if (!pressed) apply(machine.collapse())
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Intents
    // ---------------------------------------------------------------------------------------------

    fun expand(eventId: String? = null) = apply(machine.expand(eventId))

    fun collapse() = apply(machine.collapse())

    /** Glance: date, battery, next alarm, timers and what's playing. */
    fun showGlance(): Boolean {
        val glance = glanceProvider?.invoke() ?: return false
        engine.post(glance)
        return true
    }

    fun showStack(): Boolean {
        val t = machine.showStack() ?: return false
        apply(t)
        return true
    }

    /** Dismisses [event]; with a [throwVelocity] the island is thrown away sideways first. */
    fun dismiss(event: IslandEvent, throwVelocity: Float? = null) {
        if (!event.dismissible) {
            collapse()
            return
        }
        if (throwVelocity != null) view.throwAway(throwVelocity)
        event.onDismiss?.invoke?.invoke()
        engine.dismiss(event.id)
        apply(machine.onDismissed(event.id))
    }

    private fun slotEvent(slot: IslandScene.Slot): IslandEvent? {
        val st = machine.state
        return if (slot == IslandScene.Slot.BUBBLE && st is IslandState.Split) st.secondary else st.focusEvent
    }

    // ---------------------------------------------------------------------------------------------
    // IslandView.Host
    // ---------------------------------------------------------------------------------------------

    override fun onTap(slot: IslandScene.Slot) {
        val st = machine.state
        val event = slotEvent(slot) ?: return
        if (slot == IslandScene.Slot.BUBBLE) {
            expand(event.id)
            return
        }
        when (st) {
            is IslandState.Expanded -> {
                val tap = event.tapAction
                collapse()
                if (tap != null) {
                    tap.invoke()
                    if (event.isTransient) engine.dismiss(event.id)
                }
            }
            is IslandState.Compact, is IslandState.Split, is IslandState.Toast -> {
                val tap = event.tapAction
                if (settings.tapAction == TapAction.EXPAND || tap == null) {
                    expand(event.id)
                } else {
                    tap.invoke()
                    if (event.isTransient) engine.dismiss(event.id)
                }
            }
            else -> Unit
        }
    }

    override fun onLongPress(slot: IslandScene.Slot) {
        val st = machine.state
        if (st == IslandState.Idle) {
            if (settings.glanceEnabled) showGlance()
            return
        }
        val event = slotEvent(slot) ?: return
        if (st.isLarge) return
        expand(event.id)
    }

    override fun onAction(action: IslandAction) {
        action.invoke()
        if (action.collapses) collapse() else scheduleAutoCollapse()
    }

    override fun onSwipe(direction: IslandView.Swipe, velocity: Float) {
        val st = machine.state
        val event = st.focusEvent ?: return
        when (direction) {
            IslandView.Swipe.DOWN -> when {
                // Pulling down on an open card reveals every running activity.
                st is IslandState.Expanded -> showStack()
                settings.swipeDownExpands && !st.isLarge -> expand(event.id)
            }
            IslandView.Swipe.DOWN_FAR -> if (!showStack() && settings.swipeDownExpands && !st.isLarge) expand(event.id)
            IslandView.Swipe.UP -> when (st) {
                is IslandState.Expanded, is IslandState.Stack -> collapse()
                is IslandState.Toast -> dismiss(event)
                else -> if (settings.swipeToDismiss) dismiss(event)
            }
            IslandView.Swipe.LEFT, IslandView.Swipe.RIGHT -> if (settings.swipeToDismiss) {
                when {
                    st is IslandState.Stack -> collapse()
                    st is IslandState.Expanded && !event.isTransient -> collapse()
                    else -> dismiss(event, throwVelocity = velocity)
                }
            }
        }
    }

    override fun onPressChanged(pressed: Boolean) {
        this.pressed = pressed
        updateHold()
        if (!pressed) scheduleAutoCollapse() else autoCollapseJob?.cancel()
    }

    override fun onOutsideTouch() {
        if (machine.state.isLarge) collapse()
    }

    override fun onBoundsChanged(bounds: RectF, settled: Boolean) {
        onBounds?.invoke(bounds, settled)
    }

    override fun onAnimatingChanged(animating: Boolean) {
        onAnimating?.invoke(animating)
    }

    override fun onAccessibilityCommand(command: String) {
        val event = machine.state.focusEvent
        when {
            command == IslandView.CMD_EXPAND -> expand()
            command == IslandView.CMD_COLLAPSE -> collapse()
            command == IslandView.CMD_DISMISS -> event?.let(::dismiss)
            command.startsWith(IslandView.CMD_ACTION_PREFIX) -> {
                val id = command.removePrefix(IslandView.CMD_ACTION_PREFIX)
                event?.actions?.firstOrNull { it.id == id }?.let(::onAction)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // OLED burn-in protection
    // ---------------------------------------------------------------------------------------------

    /**
     * Pure black pixels are off on OLED and cannot burn in, so the black shape never moves (it
     * must stay locked to the camera). Only bright content such as a long-running timer's digits
     * drifts by at most one physical pixel every few minutes.
     */
    private fun updateBurnIn() {
        val active = settings.burnInProtection && machine.state != IslandState.Hidden
        if (!active) {
            burnInJob?.cancel()
            burnInJob = null
            view.rc.burnInX = 0f
            view.rc.burnInY = 0f
            return
        }
        if (burnInJob?.isActive == true) return
        burnInJob = scope.launch {
            var index = 0
            while (isActive) {
                delay(BURN_IN_STEP_MS)
                index = (index + 1) % BURN_IN_PATTERN.size
                val (x, y) = BURN_IN_PATTERN[index]
                view.rc.burnInX = x.toFloat()
                view.rc.burnInY = y.toFloat()
                view.invalidate()
            }
        }
    }

    companion object {
        private const val BURN_IN_STEP_MS = 180_000L
        private val BURN_IN_PATTERN = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
    }
}
