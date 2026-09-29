package com.arnav.island.overlay

import android.content.Context
import android.graphics.RectF
import android.os.Build
import android.view.DisplayCutout
import android.view.RoundedCorner
import android.view.WindowInsets
import android.view.WindowManager
import com.arnav.island.core.DeviceProfile
import com.arnav.island.core.DeviceProfiles
import com.arnav.island.island.CutoutSource
import com.arnav.island.island.CutoutSpec
import com.arnav.island.island.IslandGeometry
import com.arnav.island.island.ScreenSpec
import com.arnav.island.storage.IslandSettings
import kotlin.math.min

/**
 * Measures the real display: size, density, status bar, rounded corners and the camera cutout.
 * Must be given a visual context (a window context from the overlay service or an activity).
 */
class GeometryEngine(private val context: Context) {

    val profile: DeviceProfile = DeviceProfiles.detect()

    data class Measurement(val screen: ScreenSpec, val detected: CutoutSpec?)

    fun measure(): Measurement {
        val wm = context.getSystemService(WindowManager::class.java)
        val metrics = wm.maximumWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets
        val density = context.resources.displayMetrics.density
        val statusBar = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val display = context.display
        val corner = if (Build.VERSION.SDK_INT >= 31) {
            display?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
        } else {
            0
        }
        val screen = ScreenSpec(
            widthPx = bounds.width(),
            heightPx = bounds.height(),
            density = density,
            statusBarHeightPx = statusBar,
            displayCornerRadiusPx = corner.toFloat(),
            rotation = display?.rotation ?: 0,
        )
        return Measurement(screen, fromCutout(insets.displayCutout, screen))
    }

    /** Builds the island geometry for the current display and the user's calibration. */
    fun build(settings: IslandSettings, measurement: Measurement = measure()): IslandGeometry {
        val screen = measurement.screen
        val cutout = when {
            !screen.isPortrait -> IslandGeometry.virtualCutout(screen.widthPx, screen.density, centerYDp = 14f)
            !settings.autoDetectCutout && settings.manualCameraXPx >= 0f && settings.manualCameraRadiusPx > 0f ->
                CutoutSpec(settings.manualCameraXPx, settings.manualCameraYPx, settings.manualCameraRadiusPx, CutoutSource.MANUAL)
            measurement.detected != null -> measurement.detected
            else -> fromProfile(screen)
        }
        return IslandGeometry(screen, cutout, settings.geometryConfig())
    }

    private fun fromProfile(screen: ScreenSpec) = CutoutSpec(
        centerX = screen.widthPx / 2f,
        centerY = profile.fallbackCameraYDp * screen.density,
        radius = profile.fallbackCameraRadiusDp * screen.density,
        source = CutoutSource.DEVICE_PROFILE,
    )

    private fun fromCutout(cutout: DisplayCutout?, screen: ScreenSpec): CutoutSpec? {
        cutout ?: return null
        if (Build.VERSION.SDK_INT >= 31) {
            val path = cutout.cutoutPath
            if (path != null && !path.isEmpty) {
                val r = RectF()
                @Suppress("DEPRECATION")
                path.computeBounds(r, true)
                if (r.width() > 1f && r.top < screen.heightPx / 4f) {
                    return spec(r.left, r.top, r.right, r.bottom, CutoutSource.CUTOUT_PATH)
                }
            }
        }
        val rect = cutout.boundingRectTop.takeIf { !it.isEmpty }
            ?: cutout.boundingRects.filter { !it.isEmpty }.minByOrNull { it.top }
            ?: return null
        return spec(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), CutoutSource.CUTOUT_RECT)
    }

    /**
     * Some OEMs describe a punch-hole as a region running from the top edge down to the bottom of
     * the hole. The lens then sits at the bottom of that region, not its centre.
     */
    private fun spec(l: Float, t: Float, r: Float, b: Float, source: CutoutSource): CutoutSpec {
        val w = r - l
        val h = b - t
        val radius = min(w, h) / 2f
        val cy = if (h > w * 1.25f) b - w / 2f else (t + b) / 2f
        return CutoutSpec((l + r) / 2f, cy, radius, source, l, t, r, b)
    }
}
