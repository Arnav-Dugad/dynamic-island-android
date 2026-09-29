package com.arnav.island.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The island's TYPE_APPLICATION_OVERLAY window.
 *
 * The window is only ever as large as the island (plus a small margin). It grows *before* a
 * transition needs the space and shrinks once the island settles, so it never swallows touches
 * meant for the app underneath. Because [com.arnav.island.island.render.IslandView] draws in
 * screen coordinates, moving/resizing the window never moves the island on screen.
 */
class OverlayWindow(private val context: Context, private val view: View) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val params = baseParams(touchable = true)
    private val current = Rect()
    var attached = false
        private set

    val bounds: Rect get() = current

    fun attach(initial: Rect) {
        if (attached) return
        apply(initial)
        try {
            wm.addView(view, params)
            attached = true
        } catch (e: RuntimeException) {
            // Overlay permission revoked or window token rejected: fail quietly, the service stops.
            Log.w(TAG, "Could not add overlay window", e)
        }
    }

    fun detach() {
        if (!attached) return
        try {
            wm.removeViewImmediate(view)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not remove overlay window", e)
        }
        attached = false
    }

    fun setBounds(rect: Rect) {
        if (!attached || rect == current) return
        apply(rect)
        try {
            wm.updateViewLayout(view, params)
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    /** Asks for the display's top refresh rate while animating (pre-API 35 path). */
    fun setHighRefresh(enabled: Boolean, rate: Float) {
        val desired = if (enabled) rate else 0f
        if (!attached || params.preferredRefreshRate == desired) return
        params.preferredRefreshRate = desired
        try {
            wm.updateViewLayout(view, params)
        } catch (e: RuntimeException) {
            Log.w(TAG, "refresh rate update failed", e)
        }
    }

    private fun apply(rect: Rect) {
        current.set(rect)
        params.x = rect.left
        params.y = rect.top
        params.width = rect.width().coerceAtLeast(1)
        params.height = rect.height().coerceAtLeast(1)
    }

    companion object {
        private const val TAG = "IslandOverlay"

        fun baseParams(touchable: Boolean): WindowManager.LayoutParams {
            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            if (touchable) {
                flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            } else {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            return WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
                fitInsetsSides = 0
                isFitInsetsIgnoringVisibility = true
                windowAnimations = 0
                if (Build.VERSION.SDK_INT >= 31) setCanPlayMoveAnimation(false)
                title = "Island"
            }
        }

        /** Rounds a float rect outward and clamps it to the screen. */
        fun toRect(src: RectF, screenW: Int, screenH: Int, out: Rect) {
            out.set(
                floor(src.left).toInt().coerceIn(0, screenW),
                floor(src.top).toInt().coerceIn(0, screenH),
                ceil(src.right).toInt().coerceIn(0, screenW),
                ceil(src.bottom).toInt().coerceIn(0, screenH),
            )
        }
    }
}

/**
 * A 1 x 1 px, non-touchable, invisible window in the (physically rounded-off) top-left corner.
 * It exists only to keep receiving WindowInsets so fullscreen apps can be detected even while the
 * island itself is hidden.
 */
class FullscreenProbe(context: Context, private val onStatusBarVisible: (Boolean) -> Unit) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val view = View(context).apply {
        setOnApplyWindowInsetsListener { _, insets ->
            onStatusBarVisible(insets.isVisible(android.view.WindowInsets.Type.statusBars()))
            insets
        }
    }
    private var attached = false

    fun attach() {
        if (attached) return
        val params = OverlayWindow.baseParams(touchable = false).apply {
            width = 1
            height = 1
            x = 0
            y = 0
            title = "IslandProbe"
        }
        try {
            wm.addView(view, params)
            attached = true
        } catch (e: RuntimeException) {
            Log.w("IslandProbe", "Probe window rejected", e)
        }
    }

    fun detach() {
        if (!attached) return
        try {
            wm.removeViewImmediate(view)
        } catch (_: RuntimeException) {
        }
        attached = false
    }
}
