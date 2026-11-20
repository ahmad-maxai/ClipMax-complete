package com.clipmax.app

import kotlin.math.hypot

/**
 * Pure JVM specification for the draggable in-app circular quick translator button
 * rendered inside MainActivity's own view hierarchy (strictly app-only; never an
 * Android system overlay or AccessibilityService).
 */
internal object FloatingButtonSpec {

    const val RENDERED_IN_APP_ACTIVITY_ONLY = true
    const val USES_SYSTEM_OVERLAY = false
    const val USES_ACCESSIBILITY_SERVICE = false

    const val PREF_BUTTON_X_FRACTION = "translator_btn_x_fraction"
    const val PREF_BUTTON_Y_FRACTION = "translator_btn_y_fraction"

    const val BUTTON_DIAMETER_DP = 48f
    const val OUTER_HALO_DIAMETER_DP = 64f
    const val EDGE_MARGIN_DP = 8f
    const val TOUCH_SLOP_DP = 8f

    const val DEFAULT_X_FRACTION = 1.0f
    const val DEFAULT_Y_FRACTION = 0.60f

    const val SHINE_SWEEP_INTERVAL_MS = 4600L
    const val SHINE_SWEEP_DURATION_MS = 850L
    const val TAP_SCALE_PEAK = 1.13f

    data class PositionPx(
        val x: Float,
        val y: Float
    )

    data class Fractions(
        val xFraction: Float,
        val yFraction: Float
    )

    data class VisualStyle(
        val buttonBgColor: Int,
        val buttonBorderColor: Int,
        val textColor: Int,
        val glowInnerColor: Int,
        val glowMidColor: Int,
        val glassTopColor: Int = buttonBgColor,
        val glassMidColor: Int = buttonBgColor,
        val glassBottomColor: Int = buttonBgColor,
        val rimHighlightColor: Int = 0xCCFFFFFF.toInt(),
        val specularTopColor: Int = 0x6BFFFFFF.toInt(),
        val shineColor: Int = 0x6BFFFFFF.toInt()
    )

    fun buttonLabelForLocale(appUiLocale: String?): String =
        AppUiStrings.translatorButtonLabel(appUiLocale)

    fun toggleLocale(appUiLocale: String?): String =
        AppUiStrings.toggleLocale(appUiLocale)

    fun resolveStyle(palette: KeyboardLayoutSpec.ImeThemePalette): VisualStyle {
        return when {
            palette.isClassicTheme && !palette.isNightMode -> VisualStyle(
                buttonBgColor = 0xDDF4FAF8.toInt(),
                buttonBorderColor = 0xB80D9488.toInt(),
                textColor = 0xFF0F172A.toInt(),
                glowInnerColor = 0x520D9488.toInt(),
                glowMidColor = 0x1F14B8A6.toInt(),
                glassTopColor = 0xEDFFFFFF.toInt(),
                glassMidColor = 0xCEF0FAF7.toInt(),
                glassBottomColor = 0xD4D7ECE6.toInt(),
                rimHighlightColor = 0xD6FFFFFF.toInt(),
                specularTopColor = 0x73FFFFFF.toInt(),
                shineColor = 0x75FFFFFF.toInt()
            )
            palette.isClassicTheme -> VisualStyle(
                buttonBgColor = 0xC41B2623.toInt(),
                buttonBorderColor = 0xB82DD4BF.toInt(),
                textColor = 0xFFF1F8F5.toInt(),
                glowInnerColor = 0x592DD4BF.toInt(),
                glowMidColor = 0x222DD4BF.toInt(),
                glassTopColor = 0xCC354A45.toInt(),
                glassMidColor = 0xBF1A2724.toInt(),
                glassBottomColor = 0xD1101A17.toInt(),
                rimHighlightColor = 0x59CCFBF1.toInt(),
                specularTopColor = 0x3DE2FAF5.toInt(),
                shineColor = 0x52CCFBF1.toInt()
            )
            !palette.isNightMode -> VisualStyle(
                buttonBgColor = 0xDDF5FBFA.toInt(),
                buttonBorderColor = 0xB80D9488.toInt(),
                textColor = 0xFF111916.toInt(),
                glowInnerColor = 0x4E0D9488.toInt(),
                glowMidColor = 0x1C14B8A6.toInt(),
                glassTopColor = 0xEEFFFFFF.toInt(),
                glassMidColor = 0xD0F1FAF8.toInt(),
                glassBottomColor = 0xD6DAEFE9.toInt(),
                rimHighlightColor = 0xDBFFFFFF.toInt(),
                specularTopColor = 0x78FFFFFF.toInt(),
                shineColor = 0x78FFFFFF.toInt()
            )
            else -> VisualStyle(
                buttonBgColor = 0xC420292B.toInt(),
                buttonBorderColor = 0xB82DD4BF.toInt(),
                textColor = 0xFFF8FAFC.toInt(),
                glowInnerColor = 0x592DD4BF.toInt(),
                glowMidColor = 0x222DD4BF.toInt(),
                glassTopColor = 0xCC3B4C4D.toInt(),
                glassMidColor = 0xBF1F292B.toInt(),
                glassBottomColor = 0xD1121A1D.toInt(),
                rimHighlightColor = 0x59CCFBF1.toInt(),
                specularTopColor = 0x3EE2FAF5.toInt(),
                shineColor = 0x54CCFBF1.toInt()
            )
        }
    }

    fun clampPosition(
        x: Float,
        y: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        buttonSize: Float,
        margin: Float
    ): PositionPx {
        val maxX = (viewportWidth - buttonSize - margin).coerceAtLeast(0f)
        val maxY = (viewportHeight - buttonSize - margin).coerceAtLeast(0f)
        val minX = minOf(margin.coerceAtLeast(0f), maxX)
        val minY = minOf(margin.coerceAtLeast(0f), maxY)
        return PositionPx(
            x = x.coerceIn(minX, maxX),
            y = y.coerceIn(minY, maxY)
        )
    }

    fun toFractions(
        x: Float,
        y: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        buttonSize: Float,
        margin: Float
    ): Fractions {
        val clamped = clampPosition(x, y, viewportWidth, viewportHeight, buttonSize, margin)
        val maxX = (viewportWidth - buttonSize - margin).coerceAtLeast(0f)
        val maxY = (viewportHeight - buttonSize - margin).coerceAtLeast(0f)
        val minX = minOf(margin.coerceAtLeast(0f), maxX)
        val minY = minOf(margin.coerceAtLeast(0f), maxY)
        val spanX = (maxX - minX)
        val spanY = (maxY - minY)
        val fx = if (spanX > 0.5f) ((clamped.x - minX) / spanX).coerceIn(0f, 1f) else DEFAULT_X_FRACTION
        val fy = if (spanY > 0.5f) ((clamped.y - minY) / spanY).coerceIn(0f, 1f) else DEFAULT_Y_FRACTION
        return Fractions(fx, fy)
    }

    fun fromFractions(
        xFraction: Float,
        yFraction: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        buttonSize: Float,
        margin: Float
    ): PositionPx {
        val maxX = (viewportWidth - buttonSize - margin).coerceAtLeast(0f)
        val maxY = (viewportHeight - buttonSize - margin).coerceAtLeast(0f)
        val minX = minOf(margin.coerceAtLeast(0f), maxX)
        val minY = minOf(margin.coerceAtLeast(0f), maxY)
        val fx = if (xFraction.isNaN()) DEFAULT_X_FRACTION else xFraction.coerceIn(0f, 1f)
        val fy = if (yFraction.isNaN()) DEFAULT_Y_FRACTION else yFraction.coerceIn(0f, 1f)
        return PositionPx(
            x = minX + fx * (maxX - minX),
            y = minY + fy * (maxY - minY)
        )
    }

    /**
     * Pure JVM touch gesture controller distinguishing taps (locale toggle) from drags
     * using touch slop + viewport clamping.
     */
    class DragStateMachine(
        private val touchSlopPx: Float
    ) {
        var isTracking: Boolean = false
            private set
        var hasDragged: Boolean = false
            private set

        private var downRawX: Float = 0f
        private var downRawY: Float = 0f
        private var startViewX: Float = 0f
        private var startViewY: Float = 0f
        private var currentX: Float = 0f
        private var currentY: Float = 0f

        fun onDown(rawX: Float, rawY: Float, viewX: Float, viewY: Float) {
            isTracking = true
            hasDragged = false
            downRawX = rawX
            downRawY = rawY
            startViewX = viewX
            startViewY = viewY
            currentX = viewX
            currentY = viewY
        }

        fun onMove(
            rawX: Float,
            rawY: Float,
            viewportWidth: Float,
            viewportHeight: Float,
            buttonSize: Float,
            margin: Float
        ): PositionPx {
            if (!isTracking) {
                return clampPosition(currentX, currentY, viewportWidth, viewportHeight, buttonSize, margin)
            }
            val dx = rawX - downRawX
            val dy = rawY - downRawY
            if (!hasDragged && hypot(dx.toDouble(), dy.toDouble()) >= touchSlopPx.toDouble()) {
                hasDragged = true
            }
            val clamped = clampPosition(
                x = startViewX + dx,
                y = startViewY + dy,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                buttonSize = buttonSize,
                margin = margin
            )
            if (hasDragged) {
                currentX = clamped.x
                currentY = clamped.y
            }
            return clamped
        }

        data class UpOutcome(
            val triggerTap: Boolean,
            val persistPosition: Boolean,
            val finalPosition: PositionPx
        )

        fun onUp(
            viewportWidth: Float,
            viewportHeight: Float,
            buttonSize: Float,
            margin: Float
        ): UpOutcome {
            val wasTracking = isTracking
            val wasDrag = hasDragged
            isTracking = false
            hasDragged = false
            val clamped = clampPosition(currentX, currentY, viewportWidth, viewportHeight, buttonSize, margin)
            currentX = clamped.x
            currentY = clamped.y
            return UpOutcome(
                triggerTap = wasTracking && !wasDrag,
                persistPosition = wasTracking && wasDrag,
                finalPosition = clamped
            )
        }

        fun onCancel() {
            isTracking = false
            hasDragged = false
        }
    }
}
