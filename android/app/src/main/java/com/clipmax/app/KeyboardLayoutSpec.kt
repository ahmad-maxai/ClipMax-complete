package com.clipmax.app

/**
 * Ergonomic keyboard layout geometry, embedded top-row number mappings (Q-P -> 1-0),
 * and deterministic long-press touch state machine. Pure JVM helper.
 */
internal object KeyboardLayoutSpec {

    val TOP_ROW_EMBEDDED_NUMBERS: List<Pair<String, String>> = listOf(
        "Q" to "1",
        "W" to "2",
        "E" to "3",
        "R" to "4",
        "T" to "5",
        "Y" to "6",
        "U" to "7",
        "I" to "8",
        "O" to "9",
        "P" to "0"
    )

    val ALPHA_TO_NUMBER_MAP: Map<String, String> = TOP_ROW_EMBEDDED_NUMBERS.toMap()

    fun embeddedNumberFor(letter: String): String? =
        ALPHA_TO_NUMBER_MAP[letter.uppercase()]

    const val TOOLBAR_HEIGHT_DP = 38f
    const val STATUS_BANNER_HEIGHT_DP = 24f

    data class ViewportSpecDp(
        val toolbarHeightDp: Float,
        val mainPanelHeightDp: Float,
        val totalContentHeightDp: Float,
        val statusBannerHeightDp: Float,
        val searchTopPaddingDp: Float,
        val searchBottomPaddingDp: Float,
        val searchBarHeightDp: Float,
        val searchResultsTopMarginDp: Float,
        val searchResultsHeightDp: Float
    ) {
        val searchInterfaceHeightDp: Float
            get() = searchTopPaddingDp + searchBarHeightDp + searchResultsTopMarginDp +
                searchResultsHeightDp + searchBottomPaddingDp
    }

    data class GeometryDp(
        val keyHeightDp: Float,
        val interRowGapDp: Float,
        val horizontalGapDp: Float,
        val cornerRadiusDp: Float,
        val topPaddingDp: Float,
        val bottomPaddingDp: Float,
        val sidePaddingDp: Float,
        val primaryTextSizeSp: Float,
        val numberLegendSizeSp: Float
    )

    private fun baseGeometryFor(heightPref: String): GeometryDp = when (heightPref) {
        "compact" -> GeometryDp(
            keyHeightDp = 40f,
            interRowGapDp = 6f,
            horizontalGapDp = 4f,
            cornerRadiusDp = 8f,
            topPaddingDp = 6f,
            bottomPaddingDp = 6f,
            sidePaddingDp = 4f,
            primaryTextSizeSp = 18.5f,
            numberLegendSizeSp = 9.5f
        )
        "tall" -> GeometryDp(
            keyHeightDp = 48f,
            interRowGapDp = 8f,
            horizontalGapDp = 4.5f,
            cornerRadiusDp = 9.5f,
            topPaddingDp = 9f,
            bottomPaddingDp = 9f,
            sidePaddingDp = 4f,
            primaryTextSizeSp = 21f,
            numberLegendSizeSp = 10.5f
        )
        else -> GeometryDp(
            keyHeightDp = 45f,
            interRowGapDp = 7f,
            horizontalGapDp = 4.5f,
            cornerRadiusDp = 8.5f,
            topPaddingDp = 8f,
            bottomPaddingDp = 8f,
            sidePaddingDp = 4f,
            primaryTextSizeSp = 20f,
            numberLegendSizeSp = 10f
        )
    }

    /**
     * Computes one canonical IME content viewport height for each preset (compact/normal/tall)
     * and orientation so QWERTY, numeric keypad, emoji picker, clipboard, settings, Search Clips,
     * and status banner states maintain an identical window height.
     */
    fun computeViewportSpecDp(
        heightPref: String,
        screenHeightDp: Float,
        isLandscape: Boolean
    ): ViewportSpecDp {
        val base = baseGeometryFor(heightPref)
        val naturalPanelHeightDp =
            (base.keyHeightDp * 4f) + (base.interRowGapDp * 3f) + base.topPaddingDp + base.bottomPaddingDp
        val maxKeyboardFraction = if (isLandscape) 0.56f else 0.44f
        val maxPanelBudgetDp = (screenHeightDp * maxKeyboardFraction - TOOLBAR_HEIGHT_DP).coerceAtLeast(145f)
        val mainPanelHeightDp = minOf(naturalPanelHeightDp, maxPanelBudgetDp)

        return if (isLandscape) {
            ViewportSpecDp(
                toolbarHeightDp = TOOLBAR_HEIGHT_DP,
                mainPanelHeightDp = mainPanelHeightDp,
                totalContentHeightDp = TOOLBAR_HEIGHT_DP + mainPanelHeightDp,
                statusBannerHeightDp = STATUS_BANNER_HEIGHT_DP,
                searchTopPaddingDp = 2f,
                searchBottomPaddingDp = 2f,
                searchBarHeightDp = 24f,
                searchResultsTopMarginDp = 2f,
                searchResultsHeightDp = 32f
            )
        } else {
            ViewportSpecDp(
                toolbarHeightDp = TOOLBAR_HEIGHT_DP,
                mainPanelHeightDp = mainPanelHeightDp,
                totalContentHeightDp = TOOLBAR_HEIGHT_DP + mainPanelHeightDp,
                statusBannerHeightDp = STATUS_BANNER_HEIGHT_DP,
                searchTopPaddingDp = 2f,
                searchBottomPaddingDp = 2f,
                searchBarHeightDp = 28f,
                searchResultsTopMarginDp = 3f,
                searchResultsHeightDp = 38f
            )
        }
    }

    /**
     * Computes scaled ergonomic dimensions in dp for the 4-row keyboard inside the canonical
     * fixed viewport, subtracting exact measured dimensions of any active Search Clips or
     * status banner so total IME height never shifts.
     */
    fun computeGeometryDp(
        heightPref: String,
        screenHeightDp: Float,
        isLandscape: Boolean,
        hasFilterBar: Boolean = false,
        hasStatusBanner: Boolean = false
    ): GeometryDp {
        val base = baseGeometryFor(heightPref)
        val viewport = computeViewportSpecDp(heightPref, screenHeightDp, isLandscape)
        val reservedInsidePanelDp =
            (if (hasFilterBar) viewport.searchInterfaceHeightDp else 0f) +
            (if (hasStatusBanner) viewport.statusBannerHeightDp else 0f)
        val rowsBudgetDp = (viewport.mainPanelHeightDp - reservedInsidePanelDp).coerceAtLeast(60f)

        val naturalRowsTotalDp = (base.keyHeightDp * 4f) + (base.interRowGapDp * 3f) + base.topPaddingDp + base.bottomPaddingDp
        if (naturalRowsTotalDp <= rowsBudgetDp) {
            return base
        }

        val scale = (rowsBudgetDp / naturalRowsTotalDp).coerceIn(0.42f, 1f)
        val topPad = (base.topPaddingDp * scale).coerceAtLeast(2f)
        val bottomPad = (base.bottomPaddingDp * scale).coerceAtLeast(2f)
        val gap = (base.interRowGapDp * scale).coerceAtLeast(2.5f)
        val keyH = ((rowsBudgetDp - topPad - bottomPad - gap * 3f) / 4f).coerceAtLeast(14f)
        return base.copy(
            keyHeightDp = keyH,
            interRowGapDp = gap,
            cornerRadiusDp = (base.cornerRadiusDp * scale).coerceAtLeast(5.5f),
            topPaddingDp = topPad,
            bottomPaddingDp = bottomPad,
            primaryTextSizeSp = (base.primaryTextSizeSp * scale.coerceAtLeast(0.78f))
        )
    }

    /**
     * Pure JVM touch state machine for dual-function keys (short tap -> primary letter,
     * hold >= threshold -> single long-press number insertion without duplicate letter or key repeat).
     */
    class LongPressKeyController {
        private var isDown = false
        private var movedOutside = false
        private var longPressFired = false

        fun isLongPressTriggered(): Boolean = longPressFired

        fun onDown() {
            isDown = true
            movedOutside = false
            longPressFired = false
        }

        /**
         * Invoked when the long-press timer expires. Returns true ONLY once per touch if the
         * finger is still pressed inside bounds so repeated number insertion never occurs.
         */
        fun onLongPressTimeout(): Boolean {
            if (!isDown || movedOutside || longPressFired) return false
            longPressFired = true
            return true
        }

        fun onMove(insideBounds: Boolean): Boolean {
            if (!insideBounds && isDown) {
                movedOutside = true
                return true // caller should cancel timer & clear press visual
            }
            return false
        }

        /**
         * Invoked on ACTION_UP. Returns true if primary tap should commit, false if long-press
         * already consumed this press or pointer slid outside bounds.
         */
        fun onUp(): Boolean {
            val shouldCommitPrimary = isDown && !movedOutside && !longPressFired
            isDown = false
            movedOutside = false
            longPressFired = false
            return shouldCommitPrimary
        }

        fun onCancel() {
            isDown = false
            movedOutside = false
            longPressFired = false
        }
    }
}
