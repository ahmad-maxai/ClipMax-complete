package com.clipmax.app

/**
 * Pure JVM styling and ergonomics specification for Suggestion Pills and Long-Press Context Menus.
 */
internal object OverlayUiSpec {

    // Suggestion pills inside 38dp State A toolbar (1dp top/bottom outer padding -> 36dp pill height)
    const val TOOLBAR_VERTICAL_PADDING_DP = 1f
    const val SUGGESTION_PILL_HEIGHT_DP = 36f
    const val SUGGESTION_PILL_CORNER_DP = 18f
    const val SUGGESTION_PILL_PADDING_H_DP = 15f
    const val SUGGESTION_PILL_TEXT_SP = 13.5f
    const val SUGGESTION_PILL_MAX_WIDTH_DP = 210f
    const val CLIP_CHIP_HEIGHT_DP = 35f
    const val CLIP_CHIP_CORNER_DP = 17.5f
    const val CLIP_CHIP_PADDING_H_DP = 14f
    const val CLIP_CHIP_TEXT_SP = 13f

    data class SuggestionPillStyle(
        val bgColor: Int,
        val borderColor: Int,
        val textColor: Int,
        val isMediumOrBold: Boolean
    )

    fun resolveSuggestionPillStyle(
        palette: KeyboardLayoutSpec.ImeThemePalette,
        isPrimary: Boolean,
        isRevert: Boolean
    ): SuggestionPillStyle {
        val bg = if (isPrimary) palette.colorActiveTabBg else palette.colorChipBg
        val border = when {
            isPrimary && palette.isClassicTheme -> palette.colorAccent
            isPrimary -> 0x38FFFFFF.toInt() // crisp elevated border for primary suggestion
            else -> palette.colorChipBorder
        }
        val fg = when {
            isPrimary -> palette.colorActiveTabText
            isRevert -> palette.colorModText
            else -> palette.colorKeyText
        }
        return SuggestionPillStyle(
            bgColor = bg,
            borderColor = border,
            textColor = fg,
            isMediumOrBold = true
        )
    }

    // Context menu sizing & ergonomics (Pin, Edit, Delete, Merge, Split)
    const val CONTEXT_MENU_TARGET_ROW_HEIGHT_DP = 48f
    const val CONTEXT_MENU_MIN_WIDTH_DP = 152f
    const val CONTEXT_MENU_MAX_WIDTH_DP = 188f
    const val CONTEXT_MENU_PADDING_H_DP = 16f
    const val CONTEXT_MENU_PADDING_V_DP = 4f
    const val CONTEXT_MENU_ICON_SIZE_DP = 17f
    const val CONTEXT_MENU_ICON_GAP_DP = 12f
    const val CONTEXT_MENU_TEXT_SP = 14f
    const val CONTEXT_MENU_CORNER_DP = 12f
    const val CONTEXT_MENU_ACTION_COUNT = 5

    fun resolveContextMenuBg(palette: KeyboardLayoutSpec.ImeThemePalette): Int {
        return if (palette.isClassicTheme) palette.colorKeyBg else palette.colorChipBg
    }

    fun resolveContextMenuPanelWidthDp(surfaceWidthDp: Float): Float {
        return (surfaceWidthDp * 0.52f).coerceIn(CONTEXT_MENU_MIN_WIDTH_DP, CONTEXT_MENU_MAX_WIDTH_DP)
    }

    fun resolveContextMenuMaxHeightDp(surfaceHeightDp: Float): Float {
        return (surfaceHeightDp - 8f).coerceAtLeast(CONTEXT_MENU_TARGET_ROW_HEIGHT_DP * 3f)
    }
}
