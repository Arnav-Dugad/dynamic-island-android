package com.arnav.island.island.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import androidx.core.view.ViewCompat
import com.arnav.island.events.IslandAction
import com.arnav.island.island.IslandState
import com.arnav.island.island.TransitionKind
import com.arnav.island.storage.PerformanceMode
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The island's drawing surface, used both by the overlay window and by the in-app live preview.
 *
 * Rendering is driven by [Choreographer] only while something moves (springs, a waveform, a
 * ticking timer); an idle island schedules no frames at all. In overlay mode the view draws in
 * screen coordinates (it reads its window position every frame), so the overlay window can be
 * resized around the island without the island ever moving on screen.
 */
@SuppressLint("ViewConstructor")
class IslandView(
    context: Context,
    val rc: RenderContext,
    private val overlayMode: Boolean,
) : View(context), Choreographer.FrameCallback {

    /** DOWN_FAR: pulled past the second detent (show every activity). */
    enum class Swipe { UP, DOWN, DOWN_FAR, LEFT, RIGHT }

    interface Host {
        fun onTap(slot: IslandScene.Slot)
        fun onLongPress(slot: IslandScene.Slot)
        fun onAction(action: IslandAction)
        fun onSwipe(direction: Swipe, velocity: Float)
        fun onPressChanged(pressed: Boolean)
        fun onOutsideTouch() {}
        fun onBoundsChanged(bounds: RectF, settled: Boolean) {}
        fun onAnimatingChanged(animating: Boolean) {}
        fun onStatusBarVisibility(visible: Boolean) {}
        fun onAccessibilityCommand(command: String) {}
    }

    val scene = IslandScene(rc)
    var host: Host? = null

    /** Extra touchable depth below compact shapes (see GeometryConfig.touchExtensionDp). */
    var touchExtension = 0f

    private val choreographer = Choreographer.getInstance()
    private var frameScheduled = false
    private var lastFrameNanos = 0L
    private var animating = false
    private val location = IntArray(2)
    private val bounds = RectF()

    // Gestures.
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var tracking = false
    private var dragging = false
    private var longPressed = false

    /** Holding an open card: the island follows the finger (grab and flick). */
    private var grabbing = false
    private var scrubbing = false
    private var downX = 0f
    private var downY = 0f
    private var downSlot = IslandScene.Slot.MAIN
    private var downTarget: HitTarget? = null
    private var detentLevel = 0
    private val detents = FloatArray(2)
    private var velocityTracker: VelocityTracker? = null
    private val longPressRunnable = Runnable { onLongPressTimeout() }

    // Frame statistics (for the debug HUD / monitor).
    private val intervals = FloatArray(90)
    private var intervalCount = 0
    private var intervalIndex = 0
    var fps = 0f
        private set
    var frameMs = 0f
        private set

    private val a11yActionIds = ArrayList<Int>()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isHapticFeedbackEnabled = true
        if (overlayMode) {
            setOnApplyWindowInsetsListener { _, insets ->
                host?.onStatusBarVisibility(insets.isVisible(WindowInsets.Type.statusBars()))
                insets
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    fun render(state: IslandState, kind: TransitionKind, immediate: Boolean = false) {
        scene.setState(state, kind, immediate)
        updateAccessibility()
        requestFrame()
        reportBounds(settled = false)
    }

    fun refreshGeometry(immediate: Boolean) {
        scene.onGeometryChanged(immediate)
        requestFrame()
        reportBounds(settled = false)
    }

    fun requestFrame() {
        if (frameScheduled || !isAttachedToWindow) return
        frameScheduled = true
        choreographer.postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        val dt = if (lastFrameNanos == 0L) 1f / 120f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 1f)
        lastFrameNanos = frameTimeNanos
        val moving = scene.step(dt)
        invalidate()
        if (moving) recordInterval(dt)
        setAnimating(moving)
        reportBounds(settled = !moving)
        if (moving) {
            requestFrame()
            return
        }
        val delay = scene.nextFrameDelay(rc.clock())
        when {
            delay < 0L -> lastFrameNanos = 0L
            delay == 0L -> requestFrame()
            else -> {
                frameScheduled = true
                choreographer.postFrameCallbackDelayed(this, delay)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (overlayMode) {
            getLocationOnScreen(location)
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        }
        scene.draw(canvas, rc.clock())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        requestFrame()
    }

    override fun onDetachedFromWindow() {
        choreographer.removeFrameCallback(this)
        frameScheduled = false
        removeCallbacks(longPressRunnable)
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    /** Stops every frame immediately (screen off). */
    fun pauseRendering() {
        choreographer.removeFrameCallback(this)
        frameScheduled = false
        lastFrameNanos = 0L
        setAnimating(false)
    }

    private fun setAnimating(value: Boolean) {
        if (animating == value) return
        animating = value
        host?.onAnimatingChanged(value)
        if (Build.VERSION.SDK_INT >= 35) {
            val high = value && rc.settings.effectivePerformance == PerformanceMode.MAX_SMOOTHNESS
            setRequestedFrameRate(if (high) REQUESTED_FRAME_RATE_CATEGORY_HIGH else REQUESTED_FRAME_RATE_CATEGORY_DEFAULT)
        }
    }

    private fun reportBounds(settled: Boolean) {
        if (!overlayMode) return
        scene.bounds(bounds)
        host?.onBoundsChanged(bounds, settled)
    }

    private fun recordInterval(dt: Float) {
        if (dt <= 0f || dt > 0.25f) return
        intervals[intervalIndex] = dt
        intervalIndex = (intervalIndex + 1) % intervals.size
        if (intervalCount < intervals.size) intervalCount++
        var sum = 0f
        for (i in 0 until intervalCount) sum += intervals[i]
        val avg = sum / intervalCount
        frameMs = avg * 1000f
        fps = if (avg > 0f) 1f / avg else 0f
    }

    // ---------------------------------------------------------------------------------------------
    // Touch
    // ---------------------------------------------------------------------------------------------

    private fun sx(e: MotionEvent) = if (overlayMode) e.rawX else e.x
    private fun sy(e: MotionEvent) = if (overlayMode) e.rawY else e.y

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                host?.onOutsideTouch()
                return false
            }
            MotionEvent.ACTION_DOWN -> {
                val x = sx(e)
                val y = sy(e)
                val hit = scene.hitTest(x, y, touchExtension) ?: return false
                // Inside a scrolling settings page, the island owns its gesture.
                if (!overlayMode) parent?.requestDisallowInterceptTouchEvent(true)
                tracking = true
                dragging = false
                longPressed = false
                grabbing = false
                scrubbing = false
                detentLevel = 0
                detents[0] = rc.dp(DETENT_EXPAND_DP)
                detents[1] = rc.dp(DETENT_STACK_DP)
                downX = x
                downY = y
                downSlot = hit.slot
                downTarget = hit.target
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(e) }

                val target = hit.target
                val presenter = scene.mainPresenter
                if (target != null && presenter != null) {
                    if (target.kind == HitKind.SEEK) {
                        scrubbing = true
                        presenter.scrubTargetId = target.id
                        presenter.onScrub(scene.fractionIn(target, x))
                        feedback(IslandHaptics.Cue.TICK)
                    } else {
                        presenter.setPressed(target.id)
                    }
                } else {
                    scene.pressDown(x)
                }
                host?.onPressChanged(true)
                if (!scrubbing) postDelayed(longPressRunnable, LONG_PRESS_MS)
                requestFrame()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                velocityTracker?.addMovement(e)
                val x = sx(e)
                val y = sy(e)
                val dx = x - downX
                val dy = y - downY
                if (scrubbing) {
                    downTarget?.let { scene.mainPresenter?.onScrub(scene.fractionIn(it, x)) }
                    requestFrame()
                    return true
                }
                if (grabbing) {
                    scene.grabMove(dx, dy)
                    requestFrame()
                    return true
                }
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                    if (downTarget != null) {
                        scene.mainPresenter?.setPressed(null)
                        downTarget = null
                    }
                }
                if (dragging && (!longPressed || dy > 0f)) {
                    updateDetent(dy)
                    scene.drag(if (longPressed) 0f else dx, dy, detents)
                    requestFrame()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                velocityTracker?.addMovement(e)
                finishGesture(e, cancelled = false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                finishGesture(e, cancelled = true)
                return true
            }
        }
        return false
    }

    private fun finishGesture(e: MotionEvent, cancelled: Boolean) {
        removeCallbacks(longPressRunnable)
        val vt = velocityTracker
        vt?.computeCurrentVelocity(1000)
        val vx = vt?.xVelocity ?: 0f
        val vy = vt?.yVelocity ?: 0f
        val x = sx(e)
        val y = sy(e)
        val dx = x - downX
        val dy = y - downY
        val presenter = scene.mainPresenter

        when {
            cancelled -> {
                if (scrubbing) presenter?.onScrubEnd(presenter.scrubFraction ?: 0f)
                if (grabbing) scene.releaseGrab(0f, 0f)
                scene.releaseDrag(0f, 0f)
            }
            scrubbing -> {
                val target = downTarget
                val fraction = if (target != null) scene.fractionIn(target, x) else 0f
                presenter?.onScrubEnd(fraction)
                feedback(IslandHaptics.Cue.TICK)
            }
            grabbing -> scene.releaseGrab(vx, vy)
            detentLevel >= 2 -> {
                scene.releaseDrag(0f, vy)
                host?.onSwipe(Swipe.DOWN_FAR, vy)
            }
            longPressed -> scene.releaseDrag(vx, vy)
            dragging -> {
                val swipe = if (detentLevel == 1) Swipe.DOWN else classify(dx, dy, vx, vy)
                scene.releaseDrag(vx, vy)
                if (swipe != null) {
                    feedback(if (swipe == Swipe.DOWN) IslandHaptics.Cue.EXPAND else IslandHaptics.Cue.COLLAPSE)
                    host?.onSwipe(swipe, if (swipe == Swipe.UP || swipe == Swipe.DOWN) vy else vx)
                    if (swipe == Swipe.DOWN) scene.kick(vy)
                }
            }
            downTarget != null -> {
                val target = downTarget!!
                val hit = scene.hitTest(x, y, touchExtension)
                if (hit?.target?.id == target.id) {
                    feedback(IslandHaptics.Cue.CONFIRM)
                    target.action?.let { host?.onAction(it) }
                }
            }
            else -> {
                feedback(IslandHaptics.Cue.PRESS)
                host?.onTap(downSlot)
            }
        }
        presenter?.setPressed(null)
        scene.pressUp()
        host?.onPressChanged(false)
        tracking = false
        dragging = false
        grabbing = false
        scrubbing = false
        downTarget = null
        velocityTracker?.recycle()
        velocityTracker = null
        requestFrame()
    }

    private fun classify(dx: Float, dy: Float, vx: Float, vy: Float): Swipe? {
        val distance = rc.dp(SWIPE_DISTANCE_DP)
        val fling = rc.dp(FLING_VELOCITY_DP)
        return if (abs(dy) >= abs(dx)) {
            when {
                dy > distance || vy > fling -> Swipe.DOWN
                dy < -distance || vy < -fling -> Swipe.UP
                else -> null
            }
        } else {
            when {
                dx > distance * 1.3f || vx > fling * 1.2f -> Swipe.RIGHT
                dx < -distance * 1.3f || vx < -fling * 1.2f -> Swipe.LEFT
                else -> null
            }
        }
    }

    private fun onLongPressTimeout() {
        if (!tracking || dragging || downTarget != null) return
        if (scene.state.isLarge && rc.settings.touchDeformation) {
            // An open card lifts off and can be moved around a little.
            grabbing = true
            scene.pressUp()
            scene.grab()
            feedback(IslandHaptics.Cue.NOTCH)
            requestFrame()
            return
        }
        longPressed = true
        feedback(IslandHaptics.Cue.EXPAND)
        scene.pressUp()
        host?.onLongPress(downSlot)
        requestFrame()
    }

    /** Magnetic detents while pulling down: a haptic notch and a click of the shape at each. */
    private fun updateDetent(dy: Float) {
        val level = when {
            dy >= detents[1] -> 2
            dy >= detents[0] -> 1
            else -> 0
        }
        if (level != detentLevel) {
            detentLevel = level
            feedback(IslandHaptics.Cue.NOTCH)
            scene.notch()
        }
    }

    /** Detaches the island for a throw-to-dismiss; the controller then applies the next state. */
    fun throwAway(velocityX: Float) {
        scene.throwAway(velocityX)
        requestFrame()
    }

    /** Plays a haptic cue through the primitive-based engine, or the view fallback. */
    fun feedback(cue: IslandHaptics.Cue) {
        if (!rc.settings.haptics) return
        val engine = rc.haptics
        if (engine != null) {
            engine.play(cue)
            return
        }
        val constant = when (cue) {
            IslandHaptics.Cue.EXPAND -> HapticFeedbackConstants.LONG_PRESS
            IslandHaptics.Cue.CONFIRM -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            IslandHaptics.Cue.COLLAPSE -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.VIRTUAL_KEY
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        performHapticFeedback(constant)
    }

    // ---------------------------------------------------------------------------------------------
    // Accessibility
    // ---------------------------------------------------------------------------------------------

    private fun updateAccessibility() {
        val state = scene.state
        contentDescription = when (state) {
            IslandState.Hidden -> null
            IslandState.Idle -> "Island"
            else -> scene.mainPresenter?.takeIf { it.isBound }?.contentDescription ?: state.label
        }
        a11yActionIds.forEach { ViewCompat.removeAccessibilityAction(this, it) }
        a11yActionIds.clear()
        fun add(label: String, command: String) {
            a11yActionIds += ViewCompat.addAccessibilityAction(this, label) { _, _ ->
                host?.onAccessibilityCommand(command)
                true
            }
        }
        when (state) {
            is IslandState.Expanded -> add("Collapse", CMD_COLLAPSE)
            is IslandState.Compact, is IslandState.Toast, is IslandState.Split -> add("Expand", CMD_EXPAND)
            else -> Unit
        }
        if (state is IslandState.Toast || state is IslandState.Compact) add("Dismiss", CMD_DISMISS)
        state.focusEvent?.actions?.take(4)?.forEach { action -> add(action.label, CMD_ACTION_PREFIX + action.id) }
        ViewCompat.setStateDescription(this, (state as? IslandState.Expanded)?.let { "Expanded" })
    }

    companion object {
        const val LONG_PRESS_MS = 380L
        const val SWIPE_DISTANCE_DP = 26f
        const val FLING_VELOCITY_DP = 650f
        const val DETENT_EXPAND_DP = 30f
        const val DETENT_STACK_DP = 86f

        const val CMD_EXPAND = "expand"
        const val CMD_COLLAPSE = "collapse"
        const val CMD_DISMISS = "dismiss"
        const val CMD_ACTION_PREFIX = "action:"
    }
}
