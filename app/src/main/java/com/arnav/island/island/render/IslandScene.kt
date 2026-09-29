package com.arnav.island.island.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.animation.lerp
import com.arnav.island.animation.rubberBand
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.StackPayload
import com.arnav.island.island.IslandGeometry
import com.arnav.island.island.IslandState
import com.arnav.island.island.ShapeFrame
import com.arnav.island.island.TransitionKind
import com.arnav.island.storage.IslandTheme
import com.arnav.island.util.ColorExtractor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Owns every animated value of the island and draws it. The scene never decides *what* to show;
 * it receives states from the state machine and makes the journey between them physical:
 *
 *  - geometry is a set of springs (centre, top, width, height) that are simply retargeted, so any
 *    transition can be interrupted or reversed mid-flight with its velocity intact;
 *  - expansion leads with width and collapse leads with height ("unfurl" and "tuck"), and the
 *    shape squashes and stretches with its own velocity;
 *  - content is a stack of cross-fading presenter layers whose reveal is gated on shape progress
 *    and blurs in, while shared images (album art, app icons) fly between layers;
 *  - the split bubble buds off through a metaball bridge; a thrown-away island leaves as a
 *    detached blob on a stretching neck while the next state regrows from the camera.
 */
class IslandScene(private val rc: RenderContext) {

    enum class Slot { MAIN, BUBBLE }

    private inner class Layer(val presenter: Presenter, val slot: Slot, var reveal: Float) {
        val alpha = Spring(0f, SpringSpec(0.25f, 1f), restThreshold = 0.003f)
        var removing = false
        var revealed = false

        /** Retired into the thrown-away ghost: drawn inside it instead of the island. */
        var inGhost = false
        var node: RenderNode? = null
        val eventId: String get() = presenter.event.id
    }

    // Shape.
    private val cx = Spring(0f, restThreshold = 0.25f)
    private val top = Spring(0f, restThreshold = 0.25f)
    private val width = Spring(0f, restThreshold = 0.25f)
    private val height = Spring(0f, restThreshold = 0.25f)
    private val visibility = Spring(0f, SpringSpec(0.3f, 1f), restThreshold = 0.002f)

    /** Uniform squeeze used for toast-to-toast "gulps" and drag notch clicks. */
    private val pulse = Spring(0f, SpringSpec(0.28f, 0.42f), restThreshold = 0.002f)

    // Split bubble.
    private val bubbleCx = Spring(0f, restThreshold = 0.25f)
    private val bubbleTop = Spring(0f, restThreshold = 0.25f)
    private val bubbleSize = Spring(0f, restThreshold = 0.25f)
    private var bubbleWanted = false

    // Interaction.
    private val press = Spring(0f, restThreshold = 0.002f)
    private var pressBias = 0f
    private val dragX = Spring(0f, restThreshold = 0.3f)
    private val dragY = Spring(0f, restThreshold = 0.3f)
    private var rawDragX = 0f
    private var rawDragY = 0f

    // Grab and flick: long-press an open card and it follows the finger, then springs home.
    private val grabX = Spring(0f, restThreshold = 0.3f)
    private val grabY = Spring(0f, restThreshold = 0.3f)
    private val lift = Spring(0f, SpringSpec(0.3f, 0.7f), restThreshold = 0.002f)
    var isGrabbed = false
        private set

    /** Whether the split bubble is still joined to the pill by its liquid neck. */
    private var bridgeLinked = false

    // Tilt depth (expanded content only).
    private val tiltX = Spring(0f, SpringSpec(0.4f, 1f), restThreshold = 0.05f)
    private val tiltY = Spring(0f, SpringSpec(0.4f, 1f), restThreshold = 0.05f)

    // Effects.
    private val arrival = ArrivalPulse(rc)
    private val glint = LensGlint(rc)
    private val shimmer = Shimmer(rc)
    private val ghost = Ghost(rc)
    private val hero = Hero(rc)
    private var heroTarget: Layer? = null
    private var ghostW = 0f
    private var ghostH = 0f
    private var nextShimmerAt = SHIMMER_INTERVAL_S

    private val layers = ArrayList<Layer>(4)
    private var mainLayer: Layer? = null
    private var bubbleLayer: Layer? = null

    private var startW = 0f
    private var startH = 0f
    private var initialised = false

    var state: IslandState = IslandState.Hidden
        private set
    var mainMode: PresentMode = PresentMode.COMPACT
        private set
    var target: ShapeFrame = ShapeFrame.Zero
        private set
    private var bubbleTarget: ShapeFrame? = null

    private val shapePath = Path()
    private val bubblePath = Path()
    private val bridgePath = Path()
    private val ghostPath = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderMatrix = Matrix()
    private val glassHighlight = LinearGradient(0f, 0f, 0f, 1f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    private val rgbSweep = SweepGradient(0f, 0f, intArrayOf(
        0xFFFF4D6D.toInt(), 0xFFFFB84D.toInt(), 0xFFE8FF4D.toInt(), 0xFF4DFF88.toInt(),
        0xFF4DD2FF.toInt(), 0xFF7B4DFF.toInt(), 0xFFFF4DDB.toInt(), 0xFFFF4D6D.toInt(),
    ), null)
    private var glowGradient: RadialGradient? = null
    private var glowGradientColor = 0
    private var customGlow: RadialGradient? = null
    private var customGlowColor = 0
    private var customHighlight: LinearGradient? = null
    private var customHighlightAlpha = -1
    private val tmpRect = RectF()
    private val heroRect = RectF()

    /** Displayed geometry this frame (screen px). */
    val shapeRect = RectF()
    val bubbleRect = RectF()
    var radius = 0f
        private set

    val isBubbleVisible: Boolean get() = bubbleSize.value > 0.5f
    val mainPresenter: Presenter? get() = mainLayer?.presenter
    val bubblePresenter: Presenter? get() = bubbleLayer?.presenter
    val isVisible: Boolean get() = visibility.value > 0.001f || !visibility.isAtRest
    val isFullyHidden: Boolean get() = visibility.value <= 0.001f && visibility.isAtRest

    private val geometry: IslandGeometry get() = rc.geometry
    private val expressive: Boolean get() = !rc.settings.motion.reduceMotion

    // ---------------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------------

    /** Retargets everything for [newState]. [immediate] skips animation (screen off, rotation). */
    fun setState(newState: IslandState, kind: TransitionKind, immediate: Boolean = false) {
        val previous = state
        state = newState
        val motion = rc.settings.motion

        val (mainEvent, mode) = when (newState) {
            IslandState.Hidden, IslandState.Idle -> null to PresentMode.COMPACT
            is IslandState.Compact -> newState.event to PresentMode.COMPACT
            is IslandState.Split -> newState.primary to PresentMode.SPLIT_MAIN
            is IslandState.Toast -> newState.event to PresentMode.TOAST
            is IslandState.Expanded -> newState.event to PresentMode.EXPANDED
            is IslandState.Stack -> stackEvent(newState) to PresentMode.EXPANDED
        }
        mainMode = mode

        // Target shape. Toasts, cards and the stack are sized by their presenter.
        var measuredWith: Presenter? = null
        val frame = when (newState) {
            // Retreat into the camera before fading out.
            IslandState.Hidden -> cameraDisc()
            IslandState.Idle -> geometry.idleFrame()
            is IslandState.Compact -> geometry.compactFrame()
            is IslandState.Split -> geometry.splitFrames().first
            is IslandState.Toast, is IslandState.Expanded, is IslandState.Stack -> {
                val presenter = reusablePresenter(mainEvent!!, mode) ?: PresenterFactory.create(mainEvent.type, rc)
                measuredWith = presenter
                val (w, h) = presenter.measure(mainEvent, mode)
                geometry.frameFor(w, h)
            }
        }
        target = frame

        // Content.
        val previousLayer = mainLayer
        updateMainContent(mainEvent, mode, frame, kind, immediate, measuredWith)
        updateBubble(newState, immediate)
        if (!immediate) startSharedElements(previousLayer, kind)

        // Springs.
        val expanding = frame.height > height.value + 0.5f ||
            (frame.width > width.value + 0.5f && frame.height >= height.value - 0.5f)
        val wSpec = if (expanding) motion.widthExpand else motion.widthCollapse
        val hSpec = if (expanding) motion.heightExpand else motion.heightCollapse
        startW = width.value
        startH = height.value

        if (!initialised || immediate) {
            cx.snapTo(frame.centerX)
            top.snapTo(frame.top)
            width.snapTo(frame.width)
            height.snapTo(frame.height)
            initialised = true
        } else {
            cx.animateTo(frame.centerX, motion.position)
            top.animateTo(frame.top, motion.position)
            width.animateTo(frame.width, wSpec)
            height.animateTo(frame.height, hSpec)
            val entrance = mainEvent?.animation
            if (kind == TransitionKind.INTERRUPT && entrance == EntranceAnimation.POP && expressive) {
                // A lively pop for moments like "charger connected".
                width.impulse(frame.width * 1.1f)
                height.impulse(frame.height * 0.9f)
            }
            if (previous is IslandState.Toast && newState is IslandState.Toast && kind == TransitionKind.SWAP && expressive) {
                // Toast to toast: a quick squeeze instead of retracting in between.
                pulse.impulse(26f)
            }
        }

        val visibleTarget = if (newState == IslandState.Hidden) 0f else 1f
        if (immediate) visibility.snapTo(visibleTarget) else visibility.animateTo(visibleTarget)
        if (previous == IslandState.Hidden && newState != IslandState.Hidden && !immediate && initialised) {
            // Appear from the camera: start as the camera disc.
            if (visibility.value < 0.05f) {
                val disc = cameraDisc()
                cx.snapTo(disc.centerX)
                top.snapTo(disc.top)
                width.snapTo(disc.width)
                height.snapTo(disc.height)
                startW = width.value
                startH = height.value
                cx.animateTo(frame.centerX, motion.position)
                top.animateTo(frame.top, motion.position)
                width.animateTo(frame.width, motion.widthExpand)
                height.animateTo(frame.height, motion.heightExpand)
            }
        }
        if (!immediate) triggerEffects(previous, newState, kind, mainEvent)
        if (!newState.isLarge) setTilt(0f, 0f)
    }

    /** Geometry changed (calibration, rotation): retarget without replaying transitions. */
    fun onGeometryChanged(immediate: Boolean) {
        val current = state
        mainLayer?.let { layer ->
            val p = layer.presenter
            if (p.mode == PresentMode.TOAST || p.mode == PresentMode.EXPANDED) {
                val (w, h) = p.measure(p.event, p.mode)
                p.bind(p.event, p.mode, w, h)
            }
        }
        setState(current, TransitionKind.UPDATE, immediate)
    }

    private fun stackEvent(stack: IslandState.Stack) = IslandEvent(
        id = STACK_ID,
        source = EventSource.SYSTEM,
        type = EventType.STACK,
        timestamp = 0L,
        persistent = true,
        title = "Activities",
        payload = StackPayload(stack.events),
    )

    private fun cameraDisc(): ShapeFrame {
        val hole = geometry.holeRadius
        return ShapeFrame(geometry.anchorX, geometry.cameraY - hole, hole * 2f, hole * 2f, hole)
    }

    /** The current main presenter if it can show [event] in [mode] without a cross-fade. */
    private fun reusablePresenter(event: IslandEvent, mode: PresentMode): Presenter? {
        val current = mainLayer ?: return null
        val reusable = !current.removing && current.eventId == event.id && current.presenter.mode == mode &&
            PresenterFactory.family(current.presenter.event.type) == PresenterFactory.family(event.type)
        return if (reusable) current.presenter else null
    }

    private fun updateMainContent(
        event: IslandEvent?,
        mode: PresentMode,
        frame: ShapeFrame,
        kind: TransitionKind,
        immediate: Boolean,
        measured: Presenter?,
    ) {
        val current = mainLayer
        if (event == null) {
            current?.let(::retire)
            mainLayer = null
            return
        }
        val reusable = reusablePresenter(event, mode)
        if (reusable != null) {
            val p = reusable
            if (mode == PresentMode.TOAST || mode == PresentMode.EXPANDED) {
                if (abs(p.layoutW - frame.width) > 0.5f || abs(p.layoutH - frame.height) > 0.5f) {
                    p.bind(event, mode, frame.width, frame.height)
                } else {
                    p.update(event)
                }
            } else {
                p.update(event)
            }
            return
        }

        current?.let(::retire)
        val presenter = measured ?: PresenterFactory.create(event.type, rc)
        presenter.bind(event, mode, frame.width, frame.height)
        val gate = when {
            immediate -> 0f
            kind == TransitionKind.UPDATE || kind == TransitionKind.SWAP && current?.presenter?.mode == mode -> 0.15f
            mode == PresentMode.EXPANDED || mode == PresentMode.TOAST -> 0.55f
            else -> 0.4f
        }
        val layer = Layer(presenter, Slot.MAIN, gate)
        if (immediate) {
            layer.alpha.snapTo(1f)
            layer.revealed = true
        }
        layers.add(layer)
        mainLayer = layer
    }

    private fun updateBubble(newState: IslandState, immediate: Boolean) {
        val motion = rc.settings.motion
        if (newState is IslandState.Split) {
            val (main, bubble) = geometry.splitFrames()
            bubbleTarget = bubble
            val event = newState.secondary
            val existing = bubbleLayer
            if (existing != null && !existing.removing && existing.eventId == event.id) {
                existing.presenter.update(event)
            } else {
                existing?.let(::retire)
                val p = PresenterFactory.create(event.type, rc)
                p.bind(event, PresentMode.BUBBLE, bubble.width, bubble.height)
                val layer = Layer(p, Slot.BUBBLE, 0.7f)
                layers.add(layer)
                bubbleLayer = layer
            }
            if (!bubbleWanted || immediate) {
                // Bud from inside the pill's right cap.
                val origin = geometry.splitBudOrigin(main)
                if (bubbleSize.value < 0.5f || immediate) {
                    bubbleCx.snapTo(if (immediate) bubble.centerX else origin.centerX)
                    bubbleTop.snapTo(if (immediate) bubble.top else origin.top + (main.height - origin.height) / 2f)
                    bubbleSize.snapTo(if (immediate) bubble.width else origin.width)
                }
            }
            bubbleWanted = true
            bubbleCx.animateTo(bubble.centerX, motion.bubble)
            bubbleTop.animateTo(bubble.top, motion.bubble)
            bubbleSize.animateTo(bubble.width, motion.bubble)
        } else {
            if (bubbleWanted) {
                // Re-absorb into the pill.
                val mainTarget = target
                bubbleCx.animateTo(mainTarget.right - mainTarget.height / 2f, motion.bubble)
                bubbleTop.animateTo(mainTarget.top + mainTarget.height / 2f, motion.bubble)
                bubbleSize.animateTo(0f, motion.bubble)
            }
            bubbleWanted = false
            bubbleTarget = null
            bubbleLayer?.let(::retire)
            bubbleLayer = null
            if (immediate) {
                bubbleSize.snapTo(0f)
            }
        }
    }

    private fun retire(layer: Layer) {
        layer.removing = true
        layer.alpha.animateTo(0f, rc.settings.motion.contentOut)
        layer.presenter.setPressed(null)
    }

    // ---------------------------------------------------------------------------------------------
    // Effects and shared elements
    // ---------------------------------------------------------------------------------------------

    private fun triggerEffects(previous: IslandState, newState: IslandState, kind: TransitionKind, event: IslandEvent?) {
        val s = rc.settings
        val opening = kind == TransitionKind.APPEAR || kind == TransitionKind.BLOOM || kind == TransitionKind.EXPAND
        if (opening && s.lensGlint && expressive && s.theme != IslandTheme.RGB) glint.start()
        if (s.theme == IslandTheme.GLASS && (opening || kind == TransitionKind.INTERRUPT) && expressive) shimmer.start()

        val arrivedToast = newState is IslandState.Toast && previous !is IslandState.Toast ||
            newState is IslandState.Toast && kind == TransitionKind.SWAP
        if (arrivedToast && event != null && s.arrivalPulse && expressive &&
            (event.type == EventType.NOTIFICATION || event.type == EventType.CUSTOM)
        ) {
            val accent = event.colors.accent.takeIf { it != 0 }?.let(ColorExtractor::legibleOnBlack) ?: IslandColors.TEXT
            arrival.start(geometry.anchorX, geometry.cameraY, geometry.holeRadius, accent)
        }
    }

    /**
     * Starts an image flight when the new main presenter shares an image with the old one (album
     * art between compact and expanded) or when a notification arrives (its icon flies in from the
     * status bar).
     */
    private fun startSharedElements(previousLayer: Layer?, kind: TransitionKind) {
        val newLayer = mainLayer ?: return
        if (newLayer === previousLayer || !expressive) return
        val incoming = newLayer.presenter
        val bitmap = incoming.heroBitmap ?: return
        endHero()

        val old = previousLayer?.presenter
        if (old != null && old.isBound && old.event.id == incoming.event.id && old.heroBitmap != null) {
            computeDisplayedShape()
            if (old.heroSlot(slotW(previousLayer), slotH(previousLayer), tmpRect)) {
                toScreen(previousLayer, tmpRect, heroRect)
                hero.start(bitmap, "shared", heroRect, old.heroRadius(heroRect.width()), rc.settings.motion.heightExpand, curved = false)
                old.heroHidden = true
                incoming.heroHidden = true
                heroTarget = newLayer
                return
            }
        }

        val arriving = kind != TransitionKind.UPDATE && incoming.mode == PresentMode.TOAST &&
            incoming.event.type == EventType.NOTIFICATION && rc.settings.iconFlight
        if (arriving) {
            val size = rc.dp(18f)
            val y = if (rc.statusBarBottom > 0f) rc.statusBarBottom / 2f else geometry.cameraY
            val x = geometry.edgeMargin + rc.dp(40f)
            heroRect.set(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f)
            hero.start(bitmap, "flight", heroRect, size * 0.3f, SpringSpec(0.52f, 0.9f), curved = true)
            incoming.heroHidden = true
            heroTarget = newLayer
        }
    }

    private fun endHero() {
        heroTarget?.presenter?.heroHidden = false
        heroTarget = null
        hero.cancel()
    }

    /** Throw-to-dismiss: detach the current island as a flying blob; the next state regrows. */
    fun throwAway(velocityX: Float) {
        if (!expressive || state == IslandState.Hidden) return
        computeDisplayedShape()
        ghost.start(shapeRect, radius, velocityX)
        ghostW = shapeRect.width()
        ghostH = shapeRect.height()
        mainLayer?.let {
            it.inGhost = true
            retire(it)
        }
        mainLayer = null
        endHero()
        dragX.snapTo(0f)
        dragY.snapTo(0f)
        rawDragX = 0f
        rawDragY = 0f
        val disc = cameraDisc()
        cx.snapTo(disc.centerX)
        top.snapTo(disc.top)
        width.snapTo(disc.width)
        height.snapTo(disc.height)
    }

    /** A small click of the shape, paired with the haptic notch while dragging. */
    fun notch() {
        if (expressive) pulse.impulse(-16f)
    }

    /** A soft ring in the system accent around the camera, and a breath of the pill, on unlock. */
    fun unlockBloom() {
        if (!expressive) return
        arrival.start(geometry.anchorX, geometry.cameraY, geometry.holeRadius, rc.settings.systemAccent)
        pulse.impulse(-12f)
    }

    fun grab() {
        if (!expressive) return
        isGrabbed = true
        lift.animateTo(1f)
    }

    fun grabMove(dx: Float, dy: Float) {
        grabX.snapTo(rubberBand(dx, rc.dp(70f)))
        grabY.snapTo(rubberBand(dy, rc.dp(44f)))
    }

    /** Lets go: the island flies home with the finger's velocity and a little overshoot. */
    fun releaseGrab(vx: Float, vy: Float) {
        isGrabbed = false
        val spec = SpringSpec(0.42f, 0.5f)
        grabX.animateTo(0f, spec)
        grabY.animateTo(0f, spec)
        grabX.setVelocity(vx * 0.4f)
        grabY.setVelocity(vy * 0.4f)
        lift.animateTo(0f)
    }

    fun setTilt(dx: Float, dy: Float) {
        tiltX.animateTo(dx)
        tiltY.animateTo(dy)
    }

    // ---------------------------------------------------------------------------------------------
    // Interaction
    // ---------------------------------------------------------------------------------------------

    fun pressDown(xScreen: Float) {
        val half = max(1f, width.value / 2f)
        pressBias = ((xScreen - cx.value) / half).coerceIn(-1f, 1f)
        press.animateTo(1f, rc.settings.motion.press)
    }

    fun pressUp() {
        press.animateTo(0f, rc.settings.motion.release)
    }

    /**
     * Finger drag in screen px; converted into rubber-banded deformation. Pulling down is
     * magnetic: the stretch clings slightly around the expand and stack detents.
     */
    fun drag(dx: Float, dy: Float, detents: FloatArray? = null) {
        if (!rc.settings.touchDeformation) return
        rawDragX = dx
        rawDragY = dy
        dragX.snapTo(rubberBand(dx, rc.dp(46f)))
        var stretch = rubberBand(dy, if (dy > 0) rc.dp(46f) else rc.dp(14f))
        if (dy > 0 && detents != null) {
            val snap = rc.dp(10f)
            for (d in detents) {
                val distance = dy - d
                if (abs(distance) < snap) stretch -= distance * 0.35f * (1f - abs(distance) / snap)
            }
        }
        dragY.snapTo(stretch)
    }

    /** Releases the drag, handing the finger's velocity to the springs. */
    fun releaseDrag(vx: Float, vy: Float) {
        val spec = rc.settings.motion.gesture
        dragX.animateTo(0f, spec)
        dragY.animateTo(0f, spec)
        dragX.setVelocity(vx * 0.35f)
        dragY.setVelocity(vy * 0.35f)
        rawDragX = 0f
        rawDragY = 0f
    }

    /** Extra momentum when a fling triggered a size change (velocity preservation). */
    fun kick(vy: Float) {
        if (!expressive) return
        height.impulse(vy * 0.5f)
    }

    /** Hit test in screen coordinates. */
    fun hitTest(x: Float, y: Float, touchExtension: Float): Hit? {
        if (!isVisible || state == IslandState.Hidden) return null
        if (isBubbleVisible) {
            val r = bubbleRect.width() / 2f + rc.dp(6f)
            if (hypot(x - bubbleRect.centerX(), y - bubbleRect.centerY()) <= r) return Hit(Slot.BUBBLE, null)
        }
        if (x < shapeRect.left - rc.dp(6f) || x > shapeRect.right + rc.dp(6f) || y < shapeRect.top - rc.dp(4f) || y > touchBottom(touchExtension)) {
            return null
        }
        val layer = mainLayer
        val p = layer?.presenter
        if (layer != null && p != null && (p.mode == PresentMode.TOAST || p.mode == PresentMode.EXPANDED)) {
            val (lx, ly) = toLayout(layer, x, y)
            return Hit(Slot.MAIN, p.hitTest(lx, ly))
        }
        return Hit(Slot.MAIN, null)
    }

    /** True for states that sit inside the status bar and need a touch strip below it. */
    val usesTouchStrip: Boolean
        get() = state is IslandState.Compact || state is IslandState.Split ||
            (state is IslandState.Toast && mainMode == PresentMode.TOAST && target.bottom <= rc.statusBarBottom + rc.dp(4f))

    /**
     * Lowest touchable y for the island. Compact states get a strip of [touchExtension] below the
     * status bar (or below the pill in fullscreen apps); other states only their own bounds.
     */
    fun touchBottom(touchExtension: Float): Float = when {
        usesTouchStrip -> max(shapeRect.bottom, rc.statusBarBottom) + touchExtension
        state == IslandState.Idle -> shapeRect.bottom + rc.dp(2f)
        else -> shapeRect.bottom + rc.dp(6f)
    }

    /** Converts a screen x into a 0..1 position along a hit target (seek bars, sliders). */
    fun fractionIn(target: HitTarget, xScreen: Float): Float {
        val layer = mainLayer ?: return 0f
        val (lx, _) = toLayout(layer, xScreen, 0f)
        return ((lx - target.rect.left - rc.dp(6f)) / (target.rect.width() - rc.dp(12f))).coerceIn(0f, 1f)
    }

    class Hit(val slot: Slot, val target: HitTarget?)

    // ---------------------------------------------------------------------------------------------
    // Coordinate helpers
    // ---------------------------------------------------------------------------------------------

    private fun isCard(p: Presenter) = p.mode == PresentMode.TOAST || p.mode == PresentMode.EXPANDED

    private fun contentScale(layer: Layer): Float =
        if (!expressive) 1f else lerp(0.9f, 1f, layer.alpha.value)

    private fun slotW(layer: Layer) = if (isCard(layer.presenter)) layer.presenter.layoutW else shapeRect.width()
    private fun slotH(layer: Layer) = if (isCard(layer.presenter)) layer.presenter.layoutH else shapeRect.height()

    /** Presenter-local rect to screen rect, matching exactly how the layer is drawn. */
    private fun toScreen(layer: Layer, local: RectF, out: RectF) {
        val p = layer.presenter
        if (!isCard(p)) {
            out.set(local)
            out.offset(shapeRect.left, shapeRect.top)
            return
        }
        val s = contentScale(layer)
        val originX = shapeRect.left + (shapeRect.width() - p.layoutW) / 2f + tiltX.value
        val originY = shapeRect.top + tiltY.value
        val pivotX = p.layoutW / 2f
        out.set(
            originX + pivotX + (local.left - pivotX) * s,
            originY + local.top * s,
            originX + pivotX + (local.right - pivotX) * s,
            originY + local.bottom * s,
        )
    }

    private fun toLayout(layer: Layer, x: Float, y: Float): Pair<Float, Float> {
        val p = layer.presenter
        val originX = shapeRect.left + (shapeRect.width() - p.layoutW) / 2f + tiltX.value
        val originY = shapeRect.top + tiltY.value
        return (x - originX) to (y - originY)
    }

    // ---------------------------------------------------------------------------------------------
    // Frame
    // ---------------------------------------------------------------------------------------------

    /** Advances all springs. Returns true while anything is still moving. */
    fun step(dt: Float): Boolean {
        rc.animTime += dt
        var moving = false
        if (cx.step(dt)) moving = true
        if (top.step(dt)) moving = true
        if (width.step(dt)) moving = true
        if (height.step(dt)) moving = true
        if (visibility.step(dt)) moving = true
        if (pulse.step(dt)) moving = true
        if (bubbleCx.step(dt)) moving = true
        if (bubbleTop.step(dt)) moving = true
        if (bubbleSize.step(dt)) moving = true
        if (press.step(dt)) moving = true
        if (dragX.step(dt)) moving = true
        if (dragY.step(dt)) moving = true
        if (tiltX.step(dt)) moving = true
        if (tiltY.step(dt)) moving = true
        if (grabX.step(dt)) moving = true
        if (grabY.step(dt)) moving = true
        if (lift.step(dt)) moving = true
        if (stepSurfaceTension()) moving = true
        if (arrival.step(dt)) moving = true
        if (glint.step(dt)) moving = true
        if (shimmer.step(dt)) moving = true
        if (ghost.step(dt)) moving = true
        if (hero.active) {
            if (hero.step(dt)) moving = true
            if (!hero.active) endHero()
        }
        if (rc.settings.theme == IslandTheme.GLASS && !state.isQuiet && rc.animTime >= nextShimmerAt) {
            nextShimmerAt = rc.animTime + SHIMMER_INTERVAL_S
            shimmer.start()
            moving = true
        }

        // Content reveal gating on shape progress.
        val dist0 = abs(startW - target.width) + abs(startH - target.height)
        val dist = abs(width.value - target.width) + abs(height.value - target.height)
        val shapeProgress = if (dist0 < 1f) 1f else (1f - dist / dist0).coerceIn(0f, 1f)
        val iterator = layers.iterator()
        while (iterator.hasNext()) {
            val layer = iterator.next()
            if (!layer.removing && !layer.revealed) {
                val gateOk = layer.slot == Slot.BUBBLE && bubbleSize.value >= (bubbleTarget?.width ?: 0f) * layer.reveal ||
                    layer.slot == Slot.MAIN && shapeProgress >= layer.reveal
                if (gateOk) {
                    layer.revealed = true
                    layer.alpha.animateTo(1f, rc.settings.motion.contentIn)
                }
                moving = true
            }
            if (layer.alpha.step(dt)) moving = true
            if (layer.presenter.step(dt)) moving = true
            val gone = layer.removing && layer.alpha.isAtRest && layer.alpha.value <= 0.003f
            if (gone && !(layer.inGhost && ghost.active)) {
                layer.node?.discardDisplayList()
                iterator.remove()
            }
        }
        return moving
    }

    /**
     * Liquid split: when the neck between pill and bubble stretches past breaking point both drops
     * recoil, and when the bubble falls back in the pill gulps it. Uses last frame's geometry.
     */
    private fun stepSurfaceTension(): Boolean {
        val bs = bubbleSize.value
        if (bs <= 0.5f) {
            bridgeLinked = false
            return false
        }
        val r1 = radius
        val r2 = bs / 2f
        val dx = bubbleCx.value - (shapeRect.right - r1)
        val dy = bubbleTop.value + r2 - (shapeRect.top + r1)
        val d = hypot(dx, dy)
        val linked = d < r1 + r2 + r2 * 2.4f
        var kicked = false
        if (expressive) {
            if (bridgeLinked && !linked && bubbleWanted) {
                bubbleSize.impulse(r2 * 9f)
                pulse.impulse(9f)
                kicked = true
            } else if (!bridgeLinked && linked && !bubbleWanted) {
                pulse.impulse(14f)
                kicked = true
            }
        }
        bridgeLinked = linked
        return kicked
    }

    private val IslandState.isQuiet: Boolean get() = this == IslandState.Hidden || this == IslandState.Idle

    /** -1 static, 0 continuous, else ms until content changes. */
    fun nextFrameDelay(now: Long): Long {
        if (isFullyHidden) return -1
        var best = -1L
        fun offer(d: Long) {
            if (d < 0) return
            best = if (best < 0) d else min(best, d)
        }
        for (layer in layers) if (!layer.removing) offer(layer.presenter.nextFrameDelay(now))
        if (rc.settings.theme == IslandTheme.RGB && state != IslandState.Idle && rc.settings.particles) offer(rc.settings.decorativeFrameMs)
        if (rc.settings.theme == IslandTheme.GLASS && !state.isQuiet) {
            offer(((nextShimmerAt - rc.animTime) * 1000f).toLong().coerceAtLeast(16))
        }
        return best
    }

    private fun computeDisplayedShape() {
        var w = width.value
        var h = height.value
        var centerX = cx.value
        var t = top.value
        val intensity = rc.settings.motionIntensity.coerceIn(0f, 2f)

        // Squash and stretch: the shape elongates along its motion and thins across it.
        if (rc.settings.squashStretch && expressive && intensity > 0f) {
            val unit = rc.dp(1f)
            val sy = (height.velocity / (1600f * unit) * 0.05f * intensity).coerceIn(-0.06f, 0.06f)
            val sx = (width.velocity / (2400f * unit) * 0.04f * intensity).coerceIn(-0.05f, 0.05f)
            if (h > geometry.compactHeight * 1.2f || w > geometry.compactWidth * 0.9f) {
                h *= 1f + sy - sx * 0.5f
                w *= 1f + sx - sy * 0.5f
            }
        }

        // Press: gentle compression, stronger on the pressed edge.
        val p = press.value
        if (p > 0.0005f) {
            val big = mainMode == PresentMode.EXPANDED || mainMode == PresentMode.TOAST
            val s = 1f - p * (if (big) 0.018f else 0.055f)
            val newW = w * s
            val newH = h * s
            t += (h - newH) / 2f
            val edge = abs(pressBias) * p * w * (if (big) 0.008f else 0.022f)
            w = newW - edge
            centerX -= pressBias * edge / 2f
            h = newH
        }

        // Toast-to-toast squeeze and notch clicks.
        val squeeze = pulse.value
        if (abs(squeeze) > 0.0005f) {
            val s = 1f - 0.08f * squeeze
            val nh = h * s
            t += (h - nh) / 2f
            w *= s
            h = nh
        }

        // Drag: rubber-band stretch in the drag direction.
        val dx = dragX.value
        val dy = dragY.value
        if (dy > 0f) {
            h += dy
            w += dy * 0.22f
        } else if (dy < 0f) {
            h = max(h * 0.6f, h + dy * 0.6f)
            w -= dy * 0.25f
        }
        if (dx != 0f) {
            centerX += dx
            h = max(h * 0.8f, h - abs(dx) * 0.06f)
        }

        // Grab: the whole island follows the finger and lifts slightly.
        centerX += grabX.value
        t += grabY.value
        val l = lift.value
        if (l > 0.001f) {
            val s = 1f + 0.03f * l
            t -= h * (s - 1f) / 2f
            w *= s
            h *= s
        }

        // Fade-scale when hiding.
        val v = visibility.value
        if (v < 0.999f) {
            val s = lerp(0.6f, 1f, v)
            val nw = w * s
            val nh = h * s
            t += (h - nh) / 2f
            w = nw
            h = nh
        }
        w = max(0f, w)
        h = max(0f, h)
        shapeRect.set(centerX - w / 2f, t, centerX + w / 2f, t + h)

        // Faster motion reads as more liquid: corners round up with speed.
        val speed = if (expressive) ((abs(width.velocity) + abs(height.velocity)) / (3000f * rc.dp(1f))).coerceIn(0f, 1f) else 0f
        radius = min(h / 2f, geometry.expandedRadius * (1f + 0.3f * speed * intensity))

        val bs = bubbleSize.value
        bubbleRect.set(bubbleCx.value - bs / 2f, bubbleTop.value, bubbleCx.value + bs / 2f, bubbleTop.value + bs)
    }

    fun draw(canvas: Canvas, now: Long) {
        computeDisplayedShape()
        val v = visibility.value
        if (v <= 0.001f && !ghost.active) return
        val theme = rc.settings.theme

        // Behind the island: the arrival ripple and the album glow.
        arrival.draw(canvas)
        drawGlow(canvas, v)
        if (theme == IslandTheme.CUSTOM) drawCustomGlow(canvas, v)

        // Shape.
        SmoothShapes.roundRect(shapePath, shapeRect.left, shapeRect.top, shapeRect.right, shapeRect.bottom, radius)
        val hasBubble = isBubbleVisible
        bridgePath.rewind()
        if (hasBubble) {
            val bs = bubbleRect.width()
            bubblePath.rewind()
            // The drop stretches along its motion like liquid.
            val k = if (expressive) (bubbleCx.velocity / (1500f * rc.dp(1f)) * 0.18f * rc.settings.motionIntensity).coerceIn(-0.2f, 0.2f) else 0f
            val rx = bs / 2f * (1f + abs(k))
            val ry = bs / 2f * (1f - abs(k) * 0.6f)
            tmpRect.set(bubbleRect.centerX() - rx, bubbleRect.centerY() - ry, bubbleRect.centerX() + rx, bubbleRect.centerY() + ry)
            bubblePath.addOval(tmpRect, Path.Direction.CW)
            val r1 = radius
            val x1 = shapeRect.right - r1
            val y1 = shapeRect.top + r1
            val r2 = bs / 2f
            val bubbleSpeed = (abs(bubbleCx.velocity) / (1200f * rc.dp(1f))).coerceIn(0f, 1f)
            val maxD = r1 + r2 + r2 * (2.4f + 1.6f * bubbleSpeed)
            val d = hypot(bubbleRect.centerX() - x1, bubbleRect.centerY() - y1)
            val spread = if (d <= r1 + r2) 0.5f else 0.5f * (1f - ((d - r1 - r2) / (maxD - r1 - r2))).coerceIn(0f, 1f)
            SmoothShapes.metaballBridge(bridgePath, x1, y1, r1, bubbleRect.centerX(), bubbleRect.centerY(), r2, maxD, spread)
        }
        if (ghost.active) buildGhostPaths()

        val translucent = theme == IslandTheme.GLASS
        if (translucent) canvas.saveLayerAlpha(null, (0xE0 * v).toInt())
        fillPaint.shader = null
        fillPaint.color = if (translucent) 0xFF0E0F12.toInt() else 0xFF000000.toInt()
        fillPaint.alpha = if (translucent) 255 else (255 * v).toInt().coerceIn(0, 255)
        if (v > 0.001f) {
            canvas.drawPath(shapePath, fillPaint)
            if (hasBubble) {
                canvas.drawPath(bubblePath, fillPaint)
                canvas.drawPath(bridgePath, fillPaint)
            }
        }
        if (translucent) canvas.restore()
        if (ghost.active) {
            fillPaint.color = 0xFF000000.toInt()
            fillPaint.alpha = (255 * ghost.alpha).toInt().coerceIn(0, 255)
            canvas.drawPath(ghostPath, fillPaint)
        }

        if (v > 0.001f) drawThemeDecor(canvas, theme, v)

        // Content, clipped to the live shape.
        rc.cameraXInShape = geometry.anchorX - shapeRect.left
        if (v > 0.001f) {
            canvas.save()
            canvas.clipPath(shapePath)
            canvas.translate(shapeRect.left, shapeRect.top)
            val w = shapeRect.width()
            val h = shapeRect.height()
            val canBlur = Build.VERSION.SDK_INT >= 31 && rc.settings.blurReveal && expressive && canvas.isHardwareAccelerated
            for (layer in layers) {
                if (layer.slot != Slot.MAIN || layer.inGhost) continue
                val a = layer.alpha.value * v
                if (a <= 0.004f) continue
                val blur = if (canBlur) (1f - layer.alpha.value) * rc.dp(if (layer.removing) 5f else 8f) else 0f
                if (blur > 0.4f && Build.VERSION.SDK_INT >= 31) {
                    drawBlurred(canvas, layer, w, h, a, now, blur)
                } else {
                    drawLayer(canvas, layer, w, h, a, now)
                }
            }
            canvas.restore()
        }

        drawGhostContent(canvas, now)

        if (hasBubble && v > 0.001f) {
            canvas.save()
            canvas.clipPath(bubblePath)
            canvas.translate(bubbleRect.left, bubbleRect.top)
            val d = bubbleRect.width()
            val grow = ((d - (bubbleTarget?.width ?: d) * 0.6f) / max(1f, (bubbleTarget?.width ?: d) * 0.4f)).coerceIn(0f, 1f)
            for (layer in layers) {
                if (layer.slot != Slot.BUBBLE) continue
                val a = layer.alpha.value * v * grow
                if (a > 0.004f) layer.presenter.draw(canvas, d, d, a, now)
            }
            canvas.restore()
        }

        // Shared-element flight, above everything and not clipped: it may start outside the island.
        drawHero(canvas, v)

        if (v > 0.001f && rc.settings.lensGlint) {
            glint.draw(canvas, geometry.anchorX, geometry.cameraY, geometry.cameraRadius + rc.dp(1.6f), v)
        }
        if (rc.settings.calibrationGuides) drawGuides(canvas)
    }

    /** Draws one main layer relative to the shape's top-left (the canvas is already there). */
    private fun drawLayer(canvas: Canvas, layer: Layer, w: Float, h: Float, a: Float, now: Long) {
        val p = layer.presenter
        if (isCard(p)) {
            canvas.save()
            canvas.translate((w - p.layoutW) / 2f + tiltX.value, tiltY.value)
            val s = contentScale(layer)
            canvas.scale(s, s, p.layoutW / 2f, 0f)
            p.draw(canvas, p.layoutW, p.layoutH, a, now)
            canvas.restore()
        } else {
            p.draw(canvas, w, h, a, now)
        }
    }

    /** Blur-in / blur-out: the layer is recorded into a RenderNode and drawn through a blur. */
    @androidx.annotation.RequiresApi(31)
    private fun drawBlurred(canvas: Canvas, layer: Layer, w: Float, h: Float, a: Float, now: Long, blur: Float) {
        val node = layer.node ?: RenderNode("island-layer").also { layer.node = it }
        node.setPosition(0, 0, ceil(w).toInt() + 1, ceil(h).toInt() + 1)
        val recording = node.beginRecording()
        try {
            drawLayer(recording, layer, w, h, a, now)
        } finally {
            node.endRecording()
        }
        node.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL))
        canvas.drawRenderNode(node)
    }

    private fun drawGlow(canvas: Canvas, v: Float) {
        val layer = mainLayer ?: return
        val color = layer.presenter.glowColor
        if (color == 0 || !state.isLarge) return
        val a = layer.alpha.value * v
        if (a <= 0.01f) return
        if (glowGradient == null || glowGradientColor != color) {
            glowGradient = RadialGradient(0f, 0f, 1f, ColorExtractor.withAlpha(color, 0.55f), 0x00000000, Shader.TileMode.CLAMP)
            glowGradientColor = color
        }
        val gx = shapeRect.centerX()
        val gy = shapeRect.bottom - rc.dp(6f)
        val rx = shapeRect.width() * 0.52f
        val ry = rc.dp(36f)
        shaderMatrix.setScale(rx, ry)
        shaderMatrix.postTranslate(gx, gy)
        glowGradient?.setLocalMatrix(shaderMatrix)
        glowPaint.shader = glowGradient
        glowPaint.alpha = (255 * a).toInt().coerceIn(0, 255)
        tmpRect.set(gx - rx, gy - ry, gx + rx, gy + ry)
        canvas.drawOval(tmpRect, glowPaint)
    }

    /** Custom theme: a soft light in the user's colour that the island sits on. */
    private fun drawCustomGlow(canvas: Canvas, v: Float) {
        val color = rc.settings.custom.glow
        if ((color ushr 24) == 0 || state == IslandState.Hidden) return
        if (customGlow == null || customGlowColor != color) {
            customGlow = RadialGradient(0f, 0f, 1f, ColorExtractor.withAlpha(color, 0.5f), 0x00000000, Shader.TileMode.CLAMP)
            customGlowColor = color
        }
        val gx = shapeRect.centerX()
        val gy = shapeRect.centerY()
        val rx = shapeRect.width() / 2f + rc.dp(18f)
        val ry = shapeRect.height() / 2f + rc.dp(18f)
        shaderMatrix.setScale(rx, ry)
        shaderMatrix.postTranslate(gx, gy)
        customGlow?.setLocalMatrix(shaderMatrix)
        glowPaint.shader = customGlow
        glowPaint.alpha = (255 * v * if (state == IslandState.Idle) 0.5f else 1f).toInt().coerceIn(0, 255)
        tmpRect.set(gx - rx, gy - ry, gx + rx, gy + ry)
        canvas.drawOval(tmpRect, glowPaint)
    }

    private fun buildGhostPaths() {
        val g = ghost.rect
        SmoothShapes.roundRect(ghostPath, g.left, g.top, g.right, g.bottom, ghost.radius)
        // Rubbery neck back to the regrowing island until the distance snaps it.
        val r1 = radius
        val x1 = shapeRect.centerX()
        val y1 = shapeRect.centerY()
        val r2 = ghost.radius
        val maxD = r1 + r2 + max(r1, r2) * 4f
        val d = hypot(g.centerX() - x1, g.centerY() - y1)
        val spread = if (d <= r1 + r2) 0.5f else 0.5f * (1f - ((d - r1 - r2) / (maxD - r1 - r2))).coerceIn(0f, 1f)
        SmoothShapes.metaballBridge(ghostPath, x1, y1, r1, g.centerX(), g.centerY(), r2, maxD, spread)
    }

    private fun drawGhostContent(canvas: Canvas, now: Long) {
        if (!ghost.active) return
        val g = ghost.rect
        SmoothShapes.roundRect(tmpPathForGhost, g.left, g.top, g.right, g.bottom, ghost.radius)
        canvas.save()
        canvas.clipPath(tmpPathForGhost)
        canvas.translate(g.left, g.top)
        canvas.scale(ghost.scale, ghost.scale)
        for (layer in layers) {
            if (!layer.inGhost) continue
            val a = layer.alpha.value * ghost.alpha
            if (a > 0.004f) drawLayer(canvas, layer, ghostW, ghostH, a, now)
        }
        canvas.restore()
    }

    private val tmpPathForGhost = Path()

    private fun drawHero(canvas: Canvas, v: Float) {
        if (!hero.active) return
        val targetLayer = heroTarget ?: return
        val p = targetLayer.presenter
        if (!p.heroSlot(slotW(targetLayer), slotH(targetLayer), tmpRect)) {
            endHero()
            return
        }
        toScreen(targetLayer, tmpRect, heroRect)
        hero.draw(canvas, heroRect, p.heroRadius(heroRect.width()), v)
    }

    private fun drawThemeDecor(canvas: Canvas, theme: IslandTheme, v: Float) {
        when (theme) {
            IslandTheme.GLASS -> {
                // Keep the camera swallowed by true black, then a highlight and hairline rim.
                fillPaint.shader = null
                fillPaint.color = 0xFF000000.toInt()
                fillPaint.alpha = (255 * v).toInt()
                canvas.drawCircle(geometry.anchorX, geometry.cameraY, geometry.holeRadius + rc.dp(3f), fillPaint)
                shaderMatrix.setScale(1f, shapeRect.height() * 0.5f)
                shaderMatrix.postTranslate(0f, shapeRect.top)
                glassHighlight.setLocalMatrix(shaderMatrix)
                fillPaint.shader = glassHighlight
                fillPaint.alpha = (255 * v).toInt()
                canvas.drawPath(shapePath, fillPaint)
                fillPaint.shader = null
                rimPaint.shader = null
                rimPaint.strokeWidth = rc.dp(0.8f)
                rimPaint.color = 0x2EFFFFFF
                rimPaint.alpha = (0x2E * v).toInt()
                canvas.drawPath(shapePath, rimPaint)
                shimmer.draw(canvas, shapePath, shapeRect, v)
            }
            IslandTheme.CUSTOM -> {
                val c = rc.settings.custom
                val highlightAlpha = (c.highlight.coerceIn(0f, 1f) * 0x44).toInt()
                if (highlightAlpha > 0) {
                    if (customHighlight == null || customHighlightAlpha != highlightAlpha) {
                        customHighlight = LinearGradient(0f, 0f, 0f, 1f, (highlightAlpha shl 24) or 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                        customHighlightAlpha = highlightAlpha
                    }
                    shaderMatrix.setScale(1f, shapeRect.height() * 0.55f)
                    shaderMatrix.postTranslate(0f, shapeRect.top)
                    customHighlight?.setLocalMatrix(shaderMatrix)
                    fillPaint.shader = customHighlight
                    fillPaint.alpha = (255 * v).toInt()
                    canvas.drawPath(shapePath, fillPaint)
                    fillPaint.shader = null
                }
                if (c.rimWidthDp > 0.05f) {
                    rimPaint.shader = null
                    rimPaint.strokeWidth = rc.dp(c.rimWidthDp)
                    rimPaint.color = c.rim
                    rimPaint.alpha = ((c.rim ushr 24) * v * if (state == IslandState.Idle) 0.55f else 1f).toInt().coerceIn(0, 255)
                    canvas.drawPath(shapePath, rimPaint)
                }
            }
            IslandTheme.RGB -> {
                shaderMatrix.setRotate((rc.animTime * 90f) % 360f)
                shaderMatrix.postTranslate(shapeRect.centerX(), shapeRect.centerY())
                rgbSweep.setLocalMatrix(shaderMatrix)
                rimPaint.shader = rgbSweep
                rimPaint.strokeWidth = rc.dp(1.6f)
                rimPaint.alpha = (255 * v * if (state == IslandState.Idle) 0.55f else 1f).toInt()
                canvas.drawPath(shapePath, rimPaint)
                rimPaint.shader = null
            }
            else -> Unit
        }
    }

    private fun drawGuides(canvas: Canvas) {
        val g = geometry
        guidePaint.color = 0xFF4DD2FF.toInt()
        guidePaint.strokeWidth = rc.dp(1f)
        // Detected camera (before offsets) and the adjusted anchor.
        canvas.drawCircle(g.cutout.centerX, g.cutout.centerY, g.cutout.radius, guidePaint)
        guidePaint.color = 0xFFFFD54A.toInt()
        canvas.drawLine(g.anchorX, shapeRect.top - rc.dp(6f), g.anchorX, shapeRect.bottom + rc.dp(6f), guidePaint)
        canvas.drawLine(g.anchorX - rc.dp(12f), g.cameraY, g.anchorX + rc.dp(12f), g.cameraY, guidePaint)
    }

    /** Union of what is drawn now and where the island is heading (screen px). */
    fun bounds(out: RectF) {
        computeDisplayedShape()
        out.set(shapeRect)
        out.union(target.left, target.top, target.right, target.bottom)
        if (isBubbleVisible || bubbleWanted) {
            out.union(bubbleRect)
            bubbleTarget?.let { out.union(it.left, it.top, it.right, it.bottom) }
        }
        arrival.union(out)
        ghost.union(out)
        val layer = heroTarget
        if (hero.active && layer != null && layer.presenter.heroSlot(slotW(layer), slotH(layer), tmpRect)) {
            toScreen(layer, tmpRect, heroRect)
            hero.union(out, heroRect)
        }
        if (mainLayer?.presenter?.glowColor?.let { it != 0 } == true && state.isLarge) {
            out.union(target.left, target.top, target.right, target.bottom + rc.dp(34f))
        }
        if (rc.settings.theme == IslandTheme.CUSTOM) out.inset(-rc.dp(20f), -rc.dp(20f))
        if (isGrabbed || !grabX.isAtRest || !grabY.isAtRest) out.inset(-rc.dp(72f), -rc.dp(48f))
    }

    val dragOffsetX: Float get() = rawDragX
    val dragOffsetY: Float get() = rawDragY

    companion object {
        const val STACK_ID = "stack"
        private const val SHIMMER_INTERVAL_S = 60f
    }
}
