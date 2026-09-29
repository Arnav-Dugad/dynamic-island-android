package com.arnav.island.island

import kotlin.math.max
import kotlin.math.min

/** Where the camera hole is, in portrait screen pixels. */
data class CutoutSpec(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val source: CutoutSource,
    val boundsLeft: Float = centerX - radius,
    val boundsTop: Float = centerY - radius,
    val boundsRight: Float = centerX + radius,
    val boundsBottom: Float = centerY + radius,
)

enum class CutoutSource(val label: String) {
    CUTOUT_PATH("Display cutout path"),
    CUTOUT_RECT("Display cutout bounds"),
    DEVICE_PROFILE("Device profile"),
    MANUAL("Manual"),
    NONE("No cutout (virtual camera)"),
}

data class ScreenSpec(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val statusBarHeightPx: Int,
    /** Physical display corner radius from the RoundedCorner API, or 0 when unknown. */
    val displayCornerRadiusPx: Float,
    val rotation: Int = 0,
) {
    val isPortrait: Boolean get() = heightPx >= widthPx
}

enum class IdleStyle(val label: String) {
    /** A black disc hugging the camera: visually indistinguishable from the punch-hole. */
    CAMERA("Camera only"),

    /** A small pill around the camera. */
    PILL("Tiny pill"),

    /** Nothing is drawn while idle; activities grow out of the camera. */
    HIDDEN("Hidden"),
}

/** User calibration. Offsets are in physical pixels for true 1 px adjustments; sizes in dp. */
data class GeometryConfig(
    val offsetXPx: Float = 0f,
    val offsetYPx: Float = 0f,
    val cameraPaddingDp: Float = 1.5f,
    val idleStyle: IdleStyle = IdleStyle.CAMERA,
    /** 0 = automatic. */
    val idleWidthDp: Float = 0f,
    val idleHeightDp: Float = 0f,
    val compactWidthDp: Float = 0f,
    val compactHeightDp: Float = 0f,
    val expandedMaxWidthDp: Float = 0f,
    /** 0..1 scales the expanded corner radius between "rounded" and "concentric with the display". */
    val cornerRoundness: Float = 1f,
    val touchExtensionDp: Float = 14f,
    val edgeMarginDp: Float = 10f,
)

/** A rounded shape on screen, in pixels. */
data class ShapeFrame(
    val centerX: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val radius: Float,
) {
    val left: Float get() = centerX - width / 2f
    val right: Float get() = centerX + width / 2f
    val bottom: Float get() = top + height
    val centerY: Float get() = top + height / 2f

    companion object {
        val Zero = ShapeFrame(0f, 0f, 0f, 0f, 0f)
    }
}

data class RectPx(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
}

/**
 * All island measurements for one (screen, cutout, calibration) combination. Immutable; rebuilt
 * whenever any input changes (rotation, density, calibration).
 */
class IslandGeometry(
    val screen: ScreenSpec,
    val cutout: CutoutSpec,
    val config: GeometryConfig,
) {
    val density: Float = screen.density
    fun dp(value: Float): Float = value * density

    /** Horizontal anchor: the camera centre plus the user's fine-tune offset. */
    val anchorX: Float = cutout.centerX + config.offsetXPx
    val cameraY: Float = cutout.centerY + config.offsetYPx
    val cameraRadius: Float = cutout.radius

    /** Radius of the black disc that swallows the camera. */
    val holeRadius: Float = cameraRadius + dp(config.cameraPaddingDp)

    val edgeMargin: Float = dp(config.edgeMarginDp)
    val maxWidth: Float = (screen.widthPx - 2 * edgeMargin).coerceAtLeast(dp(120f))

    val compactHeight: Float = if (config.compactHeightDp > 0f) {
        dp(config.compactHeightDp)
    } else {
        // Centre the camera vertically inside the pill whenever the hole position allows it.
        (2f * (cameraY - dp(TOP_MIN_DP))).coerceIn(dp(AUTO_HEIGHT_MIN_DP), dp(AUTO_HEIGHT_MAX_DP))
    }.coerceAtLeast(holeRadius * 2f + dp(2f))

    /** Top edge shared by every non-idle shape so that growth happens downward from the camera. */
    val baseTop: Float = (cameraY - compactHeight / 2f).coerceAtLeast(dp(TOP_MIN_DP))

    val compactWidth: Float = if (config.compactWidthDp > 0f) {
        dp(config.compactWidthDp)
    } else {
        (screen.widthPx * 0.5f).coerceIn(dp(176f), dp(232f))
    }.coerceIn(holeRadius * 2f + dp(48f), maxWidth)

    val expandedWidth: Float = if (config.expandedMaxWidthDp > 0f) {
        min(dp(config.expandedMaxWidthDp), maxWidth)
    } else {
        maxWidth
    }

    /**
     * Expanded corner radius. When the display reports its corner radius we make the card
     * concentric with the glass (display radius minus the card's inset), the detail that makes
     * the island look like hardware rather than a floating dialog.
     */
    val expandedRadius: Float = run {
        val concentric = if (screen.displayCornerRadiusPx > 0f) {
            screen.displayCornerRadiusPx - min(edgeMargin, baseTop + edgeMargin) * 0.5f
        } else {
            dp(DEFAULT_EXPANDED_RADIUS_DP)
        }
        val maxR = concentric.coerceIn(dp(22f), dp(52f))
        val minR = dp(18f)
        minR + (maxR - minR) * config.cornerRoundness.coerceIn(0f, 1f)
    }

    /** Bottom of the status-bar band: expanded content starts below this line. */
    val statusBandBottom: Float =
        max(screen.statusBarHeightPx.toFloat(), max(baseTop + compactHeight, cameraY + holeRadius + dp(4f)))

    /** Horizontal zone around the camera that compact content must keep clear of. */
    val cameraExclusionHalfWidth: Float = holeRadius + dp(7f)

    val touchExtension: Float = dp(config.touchExtensionDp)

    fun radiusFor(height: Float): Float = min(height / 2f, expandedRadius)

    fun idleFrame(): ShapeFrame = when (config.idleStyle) {
        IdleStyle.CAMERA -> ShapeFrame(anchorX, cameraY - holeRadius, holeRadius * 2f, holeRadius * 2f, holeRadius)
        IdleStyle.PILL -> {
            val h = if (config.idleHeightDp > 0f) dp(config.idleHeightDp) else holeRadius * 2f + dp(3f)
            val w = if (config.idleWidthDp > 0f) dp(config.idleWidthDp) else h + dp(30f)
            placed(w.coerceAtLeast(h), h, centerOnCamera = true)
        }
        IdleStyle.HIDDEN -> ShapeFrame(anchorX, cameraY, 0f, 0f, 0f)
    }

    fun compactFrame(widthPx: Float = compactWidth): ShapeFrame = placed(widthPx, compactHeight)

    fun frameFor(widthPx: Float, heightPx: Float): ShapeFrame = placed(widthPx, heightPx)

    /** Main pill and bubble for the split island. */
    fun splitFrames(): Pair<ShapeFrame, ShapeFrame> {
        val bubble = compactHeight
        val gap = dp(SPLIT_GAP_DP)
        val mainWidth = max(compactWidth * 0.8f, holeRadius * 2f + dp(64f))
        val main = placed(mainWidth, compactHeight)
        var bubbleCenter = main.right + gap + bubble / 2f
        val overflow = bubbleCenter + bubble / 2f - (screen.widthPx - edgeMargin)
        if (overflow > 0f) bubbleCenter -= overflow
        return main to ShapeFrame(bubbleCenter, main.top, bubble, bubble, bubble / 2f)
    }

    /** Where the main pill's right cap starts in split mode, used by the liquid "bud" effect. */
    fun splitBudOrigin(main: ShapeFrame): ShapeFrame =
        ShapeFrame(main.right - main.height / 2f, main.top, main.height * 0.6f, main.height * 0.6f, main.height * 0.3f)

    /** Touchable area for a shape: its bounds plus the configurable extension below it. */
    fun touchRect(frame: ShapeFrame): RectPx = RectPx(
        left = frame.left - dp(6f),
        top = frame.top - dp(2f),
        right = frame.right + dp(6f),
        bottom = frame.bottom + touchExtension,
    )

    private fun placed(widthPx: Float, heightPx: Float, centerOnCamera: Boolean = false): ShapeFrame {
        val w = widthPx.coerceIn(0f, maxWidth)
        val h = heightPx.coerceAtLeast(0f)
        val top = if (centerOnCamera || h < compactHeight) {
            (cameraY - h / 2f).coerceAtLeast(min(baseTop, dp(TOP_MIN_DP)))
        } else {
            baseTop
        }
        val half = w / 2f
        val cx = if (w <= 0f) anchorX else anchorX.coerceIn(edgeMargin + half, screen.widthPx - edgeMargin - half)
        return ShapeFrame(cx, top, w, h, radiusFor(h))
    }

    companion object {
        const val TOP_MIN_DP = 2f
        const val AUTO_HEIGHT_MIN_DP = 30f
        const val AUTO_HEIGHT_MAX_DP = 38f
        const val DEFAULT_EXPANDED_RADIUS_DP = 36f
        const val SPLIT_GAP_DP = 6f

        /** A synthetic camera for previews and devices without a cutout. */
        fun virtualCutout(screenWidthPx: Int, density: Float, centerYDp: Float = 18f, radiusDp: Float = 5.5f) = CutoutSpec(
            centerX = screenWidthPx / 2f,
            centerY = centerYDp * density,
            radius = radiusDp * density,
            source = CutoutSource.NONE,
        )
    }
}
