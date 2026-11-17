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

    /**
     * Computes scaled ergonomic dimensions in dp for the 4-row keyboard (matching Gboard
     * reference proportions ~42-46dp keys, ~6-8dp vertical gaps, ~4-5dp horizontal gaps,
     * ~8-10dp corner radii, ~8dp top/bottom breathing room) while respecting available height.
     */
    fun computeGeometryDp(
        heightPref: String,
        screenHeightDp: Float,
        isLandscape: Boolean,
        hasFilterBar: Boolean = false
    ): GeometryDp {
        val base = when (heightPref) {
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

        // Guard against overflowing compact viewports, split-screen, or landscape screens
        val reservedAboveDp = 38f + (if (hasFilterBar) 66f else 0f)
        val maxKeyboardFraction = if (isLandscape) 0.56f else 0.44f
        val maxRowsBudgetDp = (screenHeightDp * maxKeyboardFraction - reservedAboveDp).coerceAtLeast(145f)

        val naturalRowsTotalDp = (base.keyHeightDp * 4f) + (base.interRowGapDp * 3f) + base.topPaddingDp + base.bottomPaddingDp
        if (naturalRowsTotalDp <= maxRowsBudgetDp) {
            return base
        }

        val scale = (maxRowsBudgetDp / naturalRowsTotalDp).coerceIn(0.62f, 1f)
        return base.copy(
            keyHeightDp = (base.keyHeightDp * scale).coerceAtLeast(30f),
            interRowGapDp = (base.interRowGapDp * scale).coerceAtLeast(4f),
            cornerRadiusDp = (base.cornerRadiusDp * scale).coerceAtLeast(6.5f),
            topPaddingDp = (base.topPaddingDp * scale).coerceAtLeast(4f),
            bottomPaddingDp = (base.bottomPaddingDp * scale).coerceAtLeast(4f),
            primaryTextSizeSp = (base.primaryTextSizeSp * scale.coerceAtLeast(0.84f))
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
