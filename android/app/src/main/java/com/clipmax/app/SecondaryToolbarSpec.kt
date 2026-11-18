package com.clipmax.app

/**
 * Pure JVM visual and structural specification for the Icon-Only Secondary Toolbar (State B),
 * preserving the canonical 38dp toolbar height while removing text labels and persistent active
 * rectangular cards in favor of larger, evenly spaced icons with subtle press feedback.
 */
internal object SecondaryToolbarSpec {
    enum class ToolbarControl {
        BACK,
        PINNED,
        SEARCH_CLIPS,
        SETTINGS,
        GLOBE
    }

    val ORDERED_CONTROLS: List<ToolbarControl> = listOf(
        ToolbarControl.BACK,
        ToolbarControl.PINNED,
        ToolbarControl.SEARCH_CLIPS,
        ToolbarControl.SETTINGS,
        ToolbarControl.GLOBE
    )

    const val TOOLBAR_HEIGHT_DP = KeyboardLayoutSpec.TOOLBAR_HEIGHT_DP // 38f unchanged
    const val END_BUTTON_SIZE_DP = 33f
    const val END_BUTTON_CORNER_DP = 16.5f
    const val BACK_ICON_SIZE_DP = 16f
    const val GLOBE_ICON_SIZE_DP = 18.5f
    const val CENTER_ICON_SIZE_DP = 20.5f
    const val TOUCH_RIPPLE_SIZE_DP = 34f
    const val ICON_ONLY = true
    const val SHOW_LABELS = false
    const val PERSISTENT_ACTIVE_CARD = false
    const val PRESS_SCALE = 0.88f
    const val PRESS_ALPHA = 0.68f
    const val PRESS_DOWN_MS = 60L
    const val PRESS_UP_MS = 110L

    data class ToolbarPalette(
        val toolbarBg: Int,
        val toolbarBottomHairline: Int,
        val backCircleBg: Int,
        val backIconFg: Int,
        val centerIconFg: Int,
        val centerLabelFg: Int,
        val globeCircleBg: Int,
        val globeCircleBorder: Int,
        val globeIconFg: Int,
        val rippleColor: Int
    )

    fun resolvePalette(isNightMode: Boolean, isClassicTheme: Boolean = false): ToolbarPalette {
        return when {
            isClassicTheme && !isNightMode -> ToolbarPalette(
                toolbarBg = 0xFFECEFF1.toInt(),
                toolbarBottomHairline = 0xFFD1D9D4.toInt(),
                backCircleBg = 0xFF1F2024.toInt(),
                backIconFg = 0xFFFFFFFF.toInt(),
                centerIconFg = 0xFF1F2937.toInt(),
                centerLabelFg = 0xFF6B7280.toInt(),
                globeCircleBg = 0xFFDCDFE3.toInt(),
                globeCircleBorder = 0xFFD1D9D4.toInt(),
                globeIconFg = 0xFF1F2937.toInt(),
                rippleColor = 0x260D9488.toInt()
            )
            isClassicTheme -> ToolbarPalette(
                toolbarBg = 0xFF151C18.toInt(),
                toolbarBottomHairline = 0xFF2C3D35.toInt(),
                backCircleBg = 0xFFFFFFFF.toInt(),
                backIconFg = 0xFF111111.toInt(),
                centerIconFg = 0xFFEAF1ED.toInt(),
                centerLabelFg = 0xFF9CB3A9.toInt(),
                globeCircleBg = 0xFF223029.toInt(),
                globeCircleBorder = 0xFF2C3D35.toInt(),
                globeIconFg = 0xFFEAF1ED.toInt(),
                rippleColor = 0x2A2DD4BF.toInt()
            )
            else -> ToolbarPalette(
                toolbarBg = 0xFF21252B.toInt(),
                toolbarBottomHairline = 0x14FFFFFF.toInt(),
                backCircleBg = 0xFFFFFFFF.toInt(),
                backIconFg = 0xFF111111.toInt(),
                centerIconFg = 0xFFE1E3E6.toInt(),
                centerLabelFg = 0xFF8A909A.toInt(),
                globeCircleBg = 0xFF282C33.toInt(),
                globeCircleBorder = 0x1AFFFFFF.toInt(),
                globeIconFg = 0xFFE1E3E6.toInt(),
                rippleColor = 0x24FFFFFF.toInt()
            )
        }
    }
}
