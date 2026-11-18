package com.clipmax.app

/**
 * Pure JVM layout, dynamic bottom-up serial numbering, content preview,
 * and capture toggle transition rules for the ClipMax clipboard panel.
 */
internal object ClipboardPanelSpec {

    const val BASE_CARD_SPACING_DP = 6f
    const val CARD_SPACING_DP = BASE_CARD_SPACING_DP * 1.5f // 9dp (1.5x previous 6dp spacing)
    const val CARD_CORNER_RADIUS_DP = 10f
    const val CARD_PADDING_HORIZONTAL_DP = 12f
    const val CARD_PADDING_VERTICAL_DP = 10f
    const val CARD_MIN_HEIGHT_PORTRAIT_DP = 76f // ~13% taller than previous ~67dp nested cards
    const val CARD_MIN_HEIGHT_LANDSCAPE_DP = 68f
    const val HEADER_HEIGHT_DP = 38f

    /**
     * Computes 1-based positional serial numbering counting upwards from the bottom-most
     * visible card (#1 at index == totalCount - 1, #totalCount at index == 0).
     * Never used as a persistent database ID.
     */
    fun computeSerialNumber(index: Int, totalCount: Int): Int {
        if (totalCount <= 0) return 0
        val clampedIndex = index.coerceIn(0, totalCount - 1)
        return totalCount - clampedIndex
    }

    fun formatSerialLabel(index: Int, totalCount: Int): String =
        "#${computeSerialNumber(index, totalCount)}"

    /**
     * Resolves the unified card's second-row preview strictly from the beginning of the
     * original clipboard content (never generic titles, copy labels, chunk labels, or byte sizes).
     */
    fun resolveCardContentPreview(rawContent: String, maxChars: Int = 220): String {
        val normalized = ClipPreviewFormatter.normalizePreviewText(rawContent, maxChars)
        return when {
            normalized.isNotEmpty() -> normalized
            rawContent.isNotEmpty() -> "(blank spaces)"
            else -> "(empty clip)"
        }
    }

    data class CaptureToggleTransition(
        val nextMode: String,
        val rememberedEnabledMode: String?
    )

    /**
     * Resolves ON/OFF transitions for clipboard capture while preserving the user's
     * previously selected retention duration ("forever", "1h", "6h", "24h", "72h").
     */
    fun resolveCaptureToggleTransition(
        currentMode: String,
        rememberedEnabledMode: String?,
        enable: Boolean,
        defaultEnabledMode: String = "24h"
    ): CaptureToggleTransition {
        return if (!enable) {
            val modeToRemember = currentMode.takeIf { it != "off" }
                ?: rememberedEnabledMode?.takeIf { it != "off" }
            CaptureToggleTransition(
                nextMode = "off",
                rememberedEnabledMode = modeToRemember
            )
        } else {
            val restoredMode = currentMode.takeIf { it != "off" }
                ?: rememberedEnabledMode?.takeIf { it != "off" }
                ?: defaultEnabledMode
            CaptureToggleTransition(
                nextMode = restoredMode,
                rememberedEnabledMode = restoredMode
            )
        }
    }

    enum class RowHeightMode {
        WRAP_CONTENT,
        MATCH_PARENT
    }

    data class GridRowLayoutSpec(
        val primaryCardHeightMode: RowHeightMode,
        val hasSecondCard: Boolean,
        val secondaryCardHeightMode: RowHeightMode?
    )

    /**
     * Resolves two-column grid row height behavior.
     * Paired rows use MATCH_PARENT so adjacent cards equalize height cleanly;
     * single-card trailing odd rows MUST use WRAP_CONTENT because Android LinearLayout
     * collapses a MATCH_PARENT child paired with a 0-height spacer to 0px height.
     */
    fun resolveGridRowLayout(hasSecondCard: Boolean): GridRowLayoutSpec {
        return if (hasSecondCard) {
            GridRowLayoutSpec(
                primaryCardHeightMode = RowHeightMode.MATCH_PARENT,
                hasSecondCard = true,
                secondaryCardHeightMode = RowHeightMode.MATCH_PARENT
            )
        } else {
            GridRowLayoutSpec(
                primaryCardHeightMode = RowHeightMode.WRAP_CONTENT,
                hasSecondCard = false,
                secondaryCardHeightMode = null
            )
        }
    }
}
