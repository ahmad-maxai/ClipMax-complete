package com.clipmax.app

/**
 * Pure JVM visual and structural specification for the Phase 2B Secondary Toolbar (State B),
 * matching approved reference Image B within the canonical 38dp toolbar height.
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
    const val BACK_ICON_SIZE_DP = 15.5f
    const val GLOBE_ICON_SIZE_DP = 18f
    const val CENTER_ICON_SIZE_DP = 17.5f
    const val LABEL_TEXT_SIZE_SP = 10f
    const val ICON_LABEL_GAP_DP = 2.5f
    const val LABEL_BOLD = false // Regular/medium weight, never heavy bold

    data class ToolbarPalette(
        val backCircleBg: Int,
        val backIconFg: Int,
        val centerIconFg: Int,
        val centerLabelFg: Int,
        val globeCircleBg: Int,
        val globeIconFg: Int
    )

    fun resolvePalette(isNightMode: Boolean): ToolbarPalette {
        return if (isNightMode) {
            ToolbarPalette(
                backCircleBg = 0xFFFFFFFF.toInt(),
                backIconFg = 0xFF111111.toInt(),
                centerIconFg = 0xFFE6E8EC.toInt(),
                centerLabelFg = 0xFF8E9096.toInt(),
                globeCircleBg = 0xFF2D2E32.toInt(),
                globeIconFg = 0xFFE6E8EC.toInt()
            )
        } else {
            ToolbarPalette(
                backCircleBg = 0xFF1F2024.toInt(),
                backIconFg = 0xFFFFFFFF.toInt(),
                centerIconFg = 0xFF1F2937.toInt(),
                centerLabelFg = 0xFF6B7280.toInt(),
                globeCircleBg = 0xFFDCDFE3.toInt(),
                globeIconFg = 0xFF1F2937.toInt()
            )
        }
    }
}
