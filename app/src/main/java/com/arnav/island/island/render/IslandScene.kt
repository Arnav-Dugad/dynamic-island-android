package com.arnav.island.island.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.animation.lerp
import com.arnav.island.animation.rubberBand
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.IslandEvent
import com.arnav.island.island.IslandGeometry
import com.arnav.island.island.IslandState
import com.arnav.island.island.ShapeFrame
import com.arnav.island.island.TransitionKind
import com.arnav.island.storage.IslandTheme
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Owns every animated value of the island and draws it. The scene never decides *what* to show;
 * it receives states from the state machine and makes the journey between them physical:
 *
 *  - geometry is a set of springs (centre, top, width, height) that are simply retargeted, so any
 *    transition can be interrupted or reversed mid-flight with its velocity intact;
 *  - expansion leads with width and collapse leads with height, giving the characteristic
 *    "unfurl" and "tuck" choreography;
 *  - content is a stack of cross-fading presenter layers whose reveal is gated on shape progress,
 *    so text never appears in a shape too small to hold it;
 *  - the split bubble buds off the pill through a metaball bridge and is re-absorbed the same way.
 */
class IslandScene(private val rc: RenderContext) {

    enum class Slot { MAIN, BUBBLE }

    private inner class Layer(val presenter: Presenter, val slot: Slot, var reveal: Float) {
        val alpha = Spring(0f, SpringSpec(0.25f, 1f), restThreshold = 0.003f)
        var removing = false
        var revealed = false
        val eventId: String get() = presenter.event.id
    }

    // Shape.
    private val cx = Spring(0f, restThreshold = 0.25f)
    private val top = Spring(0f, restThreshold = 0.25f)
    private val width = Spring(0f, restThreshold = 0.25f)
    private val height = Spring(0f, restThreshold = 0.25f)
    private val visibility = Spring(0f, SpringSpec(0.3f, 1f), restThreshold = 0.002f)

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
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shaderMatrix = Matrix()
    private val glassHighlight = LinearGradient(0f, 0f, 0f, 1f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    private val rgbSweep = SweepGradient(0f, 0f, intArrayOf(
        0xFFFF4D6D.toInt(), 0xFFFFB84D.toInt(), 0xFFE8FF4D.toInt(), 0xFF4DFF88.toInt(),
        0xFF4DD2FF.toInt(), 0xFF7B4DFF.toInt(), 0xFFFF4DDB.toInt(), 0xFFFF4D6D.toInt(),
    ), null)

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
        }
        mainMode = mode

        // Target shape. Toasts and cards are sized by their presenter.
        var measuredWith: Presenter? = null
        val frame = when (newState) {
            // Retreat into the camera before fading out.
            IslandState.Hidden -> cameraDisc()
            IslandState.Idle -> geometry.idleFrame()
            is IslandState.Compact -> geometry.compactFrame()
            is IslandState.Split -> geometry.splitFrames().first
            is IslandState.Toast, is IslandState.Expanded -> {
                val presenter = reusablePresenter(mainEvent!!, mode) ?: PresenterFactory.create(mainEvent.type, rc)
                measuredWith = presenter
                val (w, h) = presenter.measure(mainEvent, mode)
                geometry.frameFor(w, h)
            }
        }
        target = frame

        // Content.
        updateMainContent(mainEvent, mode, frame, kind, immediate, measuredWith)
        updateBubble(newState, immediate)

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
            if (kind == TransitionKind.INTERRUPT && entrance == EntranceAnimation.POP && !motion.reduceMotion) {
                // A lively pop for moments like "charger connected".
                width.impulse(frame.width * 1.1f)
                height.impulse(frame.height * 0.9f)
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

    /** Finger drag in screen px; converted into rubber-banded deformation. */
    fun drag(dx: Float, dy: Float) {
        if (!rc.settings.touchDeformation) return
        rawDragX = dx
        rawDragY = dy
        dragX.snapTo(rubberBand(dx, rc.dp(46f)))
        dragY.snapTo(rubberBand(dy, if (dy > 0) rc.dp(40f) else rc.dp(14f)))
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
        if (rc.settings.motion.reduceMotion) return
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
        val p = mainLayer?.presenter
        if (p != null && (p.mode == PresentMode.TOAST || p.mode == PresentMode.EXPANDED)) {
            val lx = x - (shapeRect.left + (shapeRect.width() - p.layoutW) / 2f)
            val ly = y - shapeRect.top
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

    /** Converts a screen x into a 0..1 position along a hit target (seek bars). */
    fun fractionIn(target: HitTarget, xScreen: Float): Float {
        val p = mainLayer?.presenter ?: return 0f
        val lx = xScreen - (shapeRect.left + (shapeRect.width() - p.layoutW) / 2f)
        return ((lx - target.rect.left - rc.dp(6f)) / (target.rect.width() - rc.dp(12f))).coerceIn(0f, 1f)
    }

    class Hit(val slot: Slot, val target: HitTarget?)

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
        if (bubbleCx.step(dt)) moving = true
        if (bubbleTop.step(dt)) moving = true
        if (bubbleSize.step(dt)) moving = true
        if (press.step(dt)) moving = true
        if (dragX.step(dt)) moving = true
        if (dragY.step(dt)) moving = true

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
            if (layer.removing && layer.alpha.isAtRest && layer.alpha.value <= 0.003f) iterator.remove()
        }
        return moving
    }

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
        return best
    }

    private fun computeDisplayedShape() {
        var w = width.value
        var h = height.value
        var centerX = cx.value
        var t = top.value

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
        radius = min(h / 2f, geometry.expandedRadius)

        val bs = bubbleSize.value
        bubbleRect.set(bubbleCx.value - bs / 2f, bubbleTop.value, bubbleCx.value + bs / 2f, bubbleTop.value + bs)
    }

    fun draw(canvas: Canvas, now: Long) {
        computeDisplayedShape()
        val v = visibility.value
        if (v <= 0.001f) return
        val theme = rc.settings.theme

        // Shape.
        SmoothShapes.roundRect(shapePath, shapeRect.left, shapeRect.top, shapeRect.right, shapeRect.bottom, radius)
        val hasBubble = isBubbleVisible
        bridgePath.rewind()
        if (hasBubble) {
            val bs = bubbleRect.width()
            bubblePath.rewind()
            bubblePath.addCircle(bubbleRect.centerX(), bubbleRect.centerY(), bs / 2f, Path.Direction.CW)
            val r1 = radius
            val x1 = shapeRect.right - r1
            val y1 = shapeRect.top + r1
            val r2 = bs / 2f
            val maxD = r1 + r2 + r2 * 2.4f
            val d = hypot(bubbleRect.centerX() - x1, bubbleRect.centerY() - y1)
            val spread = if (d <= r1 + r2) 0.5f else 0.5f * (1f - ((d - r1 - r2) / (maxD - r1 - r2))).coerceIn(0f, 1f)
            SmoothShapes.metaballBridge(bridgePath, x1, y1, r1, bubbleRect.centerX(), bubbleRect.centerY(), r2, maxD, spread)
        }

        val translucent = theme == IslandTheme.GLASS
        if (translucent) canvas.saveLayerAlpha(null, (0xE0 * v).toInt())
        fillPaint.shader = null
        fillPaint.color = if (translucent) 0xFF0E0F12.toInt() else 0xFF000000.toInt()
        fillPaint.alpha = if (translucent) 255 else (255 * v).toInt().coerceIn(0, 255)
        canvas.drawPath(shapePath, fillPaint)
        if (hasBubble) {
            canvas.drawPath(bubblePath, fillPaint)
            canvas.drawPath(bridgePath, fillPaint)
        }
        if (translucent) canvas.restore()

        drawThemeDecor(canvas, theme, v)

        // Content, clipped to the live shape.
        rc.cameraXInShape = geometry.anchorX - shapeRect.left
        canvas.save()
        canvas.clipPath(shapePath)
        canvas.translate(shapeRect.left, shapeRect.top)
        val w = shapeRect.width()
        val h = shapeRect.height()
        for (layer in layers) {
            if (layer.slot != Slot.MAIN) continue
            val a = layer.alpha.value * v
            if (a <= 0.004f) continue
            val p = layer.presenter
            if (p.mode == PresentMode.TOAST || p.mode == PresentMode.EXPANDED) {
                canvas.save()
                canvas.translate((w - p.layoutW) / 2f, 0f)
                val s = if (rc.settings.motion.reduceMotion) 1f else lerp(0.9f, 1f, layer.alpha.value)
                canvas.scale(s, s, p.layoutW / 2f, 0f)
                p.draw(canvas, p.layoutW, p.layoutH, a, now)
                canvas.restore()
            } else {
                p.draw(canvas, w, h, a, now)
            }
        }
        canvas.restore()

        if (hasBubble) {
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

        if (rc.settings.calibrationGuides) drawGuides(canvas)
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
    }

    val dragOffsetX: Float get() = rawDragX
    val dragOffsetY: Float get() = rawDragY
}
