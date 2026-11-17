package com.clipmax.app

/**
 * Ergonomic keyboard layout geometry, embedded top-row number mappings (Q-P -> 1-0),
 * Gboard-style three-mode navigation controller (ALPHA -> SYMBOLS -> NUMPAD),
 * 4x5 calculator numeric keypad specification, IME theme palette resolver,
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

    enum class KeyboardMode(val modeIndex: Int) {
        ALPHA(0),
        SYMBOLS(1),
        SYMBOLS_EXT(2),
        NUMPAD(3)
    }

    /**
     * Pure JVM state machine governing ClipMax three-mode keyboard navigation:
     * - ALPHA (?123) -> SYMBOLS
     * - SYMBOLS (12 34) -> NUMPAD
     * - SYMBOLS (ABC) -> ALPHA
     * - SYMBOLS (=\<) <-> SYMBOLS_EXT (?123 / ABC)
     * - NUMPAD (ABC) -> ALPHA
     *
     * Never auto-activates NUMPAD on numeric EditorInfo focus.
     * Supports temporary QWERTY entry during Search Clips and restores prior mode on exit.
     */
    class KeyboardModeController {
        var currentMode: KeyboardMode = KeyboardMode.ALPHA
            private set

        private var preSearchMode: KeyboardMode? = null

        fun toAlpha() {
            currentMode = KeyboardMode.ALPHA
        }

        fun toSymbols() {
            currentMode = KeyboardMode.SYMBOLS
        }

        fun toSymbolsExt() {
            currentMode = KeyboardMode.SYMBOLS_EXT
        }

        fun toNumpad() {
            currentMode = KeyboardMode.NUMPAD
        }

        fun enterSearchClips() {
            if (preSearchMode == null) {
                preSearchMode = currentMode
            }
            currentMode = KeyboardMode.ALPHA
        }

        fun exitSearchClips() {
            val restore = preSearchMode
            preSearchMode = null
            if (restore != null) {
                currentMode = restore
            }
        }

        fun onFieldFocused(
            @Suppress("UNUSED_PARAMETER") capabilities: InputFieldPolicy.FieldCapabilities,
            restarting: Boolean = false
        ) {
            if (!restarting) {
                preSearchMode = null
                currentMode = KeyboardMode.ALPHA
            }
        }

        fun onWindowHiddenReset() {
            preSearchMode = null
            currentMode = KeyboardMode.ALPHA
        }
    }

    data class ToolbarStripState(
        val showClipboardStrip: Boolean,
        val showSuggestionsStrip: Boolean
    )

    /**
     * Resolves center toolbar strip visibility.
     * In NUMPAD mode, both the horizontal clipboard snippet strip and suggestions strip
     * are completely hidden so nothing crowds or expands the numpad viewport.
     */
    fun resolveToolbarStripState(
        mode: KeyboardMode,
        centerMode: Int,
        isPasswordTarget: Boolean,
        isFilterMode: Boolean,
        isSuggestionsEnabled: Boolean,
        allowSuggestions: Boolean
    ): ToolbarStripState {
        if (mode == KeyboardMode.NUMPAD) {
            return ToolbarStripState(
                showClipboardStrip = false,
                showSuggestionsStrip = false
            )
        }
        val showSuggest = centerMode == 1 &&
            !isPasswordTarget &&
            !isFilterMode &&
            isSuggestionsEnabled &&
            allowSuggestions
        return ToolbarStripState(
            showClipboardStrip = !showSuggest,
            showSuggestionsStrip = showSuggest
        )
    }

    enum class NumpadKeyRole {
        OPERATOR,
        DIGIT,
        BACKSPACE,
        RETURN_ALPHA,
        ACTION_ENTER
    }

    data class NumpadKeySpec(
        val label: String,
        val commitChar: String?,
        val role: NumpadKeyRole,
        val weight: Float,
        val contentDescription: String
    )

    /**
     * Canonical 4x5 calculator-style numeric keypad layout:
     * Row 1: +   | 1 | 2 | 3 | %
     * Row 2: −   | 4 | 5 | 6 | )
     * Row 3: ×   | 7 | 8 | 9 | ⌫
     * Row 4: ABC | , | 0 | = | ↵
     *
     * Row weights sum cleanly to 10.0f to align left/right columns flush with QWERTY/SYMBOLS
     * while giving row 4 a visually wider centered '0' key and uncrowded 'ABC', ',', '=', '↵'.
     */
    object NumpadLayoutSpec {
        const val MINUS_CHAR = "\u2212"
        const val MULTIPLY_CHAR = "\u00D7"
        const val TOGGLE_KEY_LABEL = "12\n34"

        val ROWS: List<List<NumpadKeySpec>> = listOf(
            listOf(
                NumpadKeySpec("+", "+", NumpadKeyRole.OPERATOR, 1.45f, "Plus"),
                NumpadKeySpec("1", "1", NumpadKeyRole.DIGIT, 2.366f, "1"),
                NumpadKeySpec("2", "2", NumpadKeyRole.DIGIT, 2.368f, "2"),
                NumpadKeySpec("3", "3", NumpadKeyRole.DIGIT, 2.366f, "3"),
                NumpadKeySpec("%", "%", NumpadKeyRole.OPERATOR, 1.45f, "Percent")
            ),
            listOf(
                NumpadKeySpec(MINUS_CHAR, MINUS_CHAR, NumpadKeyRole.OPERATOR, 1.45f, "Minus"),
                NumpadKeySpec("4", "4", NumpadKeyRole.DIGIT, 2.366f, "4"),
                NumpadKeySpec("5", "5", NumpadKeyRole.DIGIT, 2.368f, "5"),
                NumpadKeySpec("6", "6", NumpadKeyRole.DIGIT, 2.366f, "6"),
                NumpadKeySpec(")", ")", NumpadKeyRole.OPERATOR, 1.45f, "Right parenthesis")
            ),
            listOf(
                NumpadKeySpec(MULTIPLY_CHAR, MULTIPLY_CHAR, NumpadKeyRole.OPERATOR, 1.45f, "Multiply"),
                NumpadKeySpec("7", "7", NumpadKeyRole.DIGIT, 2.366f, "7"),
                NumpadKeySpec("8", "8", NumpadKeyRole.DIGIT, 2.368f, "8"),
                NumpadKeySpec("9", "9", NumpadKeyRole.DIGIT, 2.366f, "9"),
                NumpadKeySpec("\u232B", null, NumpadKeyRole.BACKSPACE, 1.45f, "Backspace")
            ),
            listOf(
                NumpadKeySpec("ABC", null, NumpadKeyRole.RETURN_ALPHA, 1.45f, "Return to letters"),
                NumpadKeySpec(",", ",", NumpadKeyRole.OPERATOR, 1.40f, "Comma"),
                NumpadKeySpec("0", "0", NumpadKeyRole.DIGIT, 4.30f, "0"),
                NumpadKeySpec("=", "=", NumpadKeyRole.OPERATOR, 1.40f, "Equals"),
                NumpadKeySpec("\u21B5", null, NumpadKeyRole.ACTION_ENTER, 1.45f, "Enter")
            )
        )
    }

    data class ImeThemePalette(
        val isClassicTheme: Boolean,
        val isNightMode: Boolean,
        val colorBg: Int,
        val colorKeyBg: Int,
        val colorKeyBorder: Int,
        val colorKeyText: Int,
        val colorModBg: Int,
        val colorModText: Int,
        val colorAccent: Int,
        val colorAccentText: Int,
        val colorHintText: Int,
        val colorChipBg: Int,
        val colorChipBorder: Int,
        val colorSnippetBg: Int,
        val colorSnippetBorder: Int,
        val colorActiveTabBg: Int,
        val colorActiveTabText: Int,
        val colorDangerText: Int
    ) {
        val enterKeyBg: Int get() = if (isClassicTheme) colorAccent else colorKeyBg
        val enterKeyFg: Int get() = if (isClassicTheme) colorAccentText else colorKeyText
    }

    fun resolveThemePalette(savedTheme: String?, systemNightMode: Boolean): ImeThemePalette {
        val isClassic = savedTheme == "classic"
        val isNight = when (savedTheme) {
            "dark", "graphite" -> true
            "light", "classic_light" -> false
            "classic" -> systemNightMode
            else -> true
        }
        return ImeThemePalette(
            isClassicTheme = isClassic,
            isNightMode = isNight,
            colorBg = if (isClassic && !isNight) 0xFFECEFF1.toInt()
                      else if (isClassic) 0xFF151C18.toInt()
                      else 0xFF0E0E10.toInt(),
            colorKeyBg = if (isClassic && !isNight) 0xFFFFFFFF.toInt()
                         else if (isClassic) 0xFF223029.toInt()
                         else 0xFF252528.toInt(),
            colorKeyBorder = if (isClassic && !isNight) 0xFFD1D9D4.toInt()
                             else if (isClassic) 0xFF2C3D35.toInt()
                             else 0xFF333338.toInt(),
            colorKeyText = if (isClassic && !isNight) 0xFF111827.toInt()
                           else if (isClassic) 0xFFEAF1ED.toInt()
                           else 0xFFFFFFFF.toInt(),
            colorModBg = if (isClassic && !isNight) 0xFFDFE5E1.toInt()
                         else if (isClassic) 0xFF1A2520.toInt()
                         else 0xFF1C1C1F.toInt(),
            colorModText = if (isClassic && !isNight) 0xFF374151.toInt()
                           else if (isClassic) 0xFF9CB3A9.toInt()
                           else 0xFFA0A0A8.toInt(),
            colorAccent = if (isClassic) (if (isNight) 0xFF2DD4BF.toInt() else 0xFF0D9488.toInt())
                          else 0xFFFFFFFF.toInt(),
            colorAccentText = if (isClassic) (if (isNight) 0xFF0A1F18.toInt() else 0xFFFFFFFF.toInt())
                              else 0xFF111111.toInt(),
            colorHintText = if (isClassic) (if (isNight) 0xFF7A8E85.toInt() else 0xFF8A9992.toInt())
                            else 0xFF8E8E93.toInt(),
            colorChipBg = if (isClassic) (if (isNight) 0xFF1E2C26.toInt() else 0xFFFFFFFF.toInt())
                          else 0xFF242428.toInt(),
            colorChipBorder = if (isClassic) (if (isNight) 0xFF2C3D35.toInt() else 0xFFD1D9D4.toInt())
                              else 0xFF36363C.toInt(),
            colorSnippetBg = if (isClassic) (if (isNight) 0xFF141C18.toInt() else 0xFFF3F6F4.toInt())
                             else 0xFF1A1A1E.toInt(),
            colorSnippetBorder = if (isClassic) (if (isNight) 0xFF23322B.toInt() else 0xFFE2E8E4.toInt())
                                 else 0xFF2C2C32.toInt(),
            colorActiveTabBg = if (isClassic) (if (isNight) 0xFF1A3A32.toInt() else 0xFFCCFBF1.toInt())
                               else 0xFF32333A.toInt(),
            colorActiveTabText = if (isClassic) (if (isNight) 0xFF2DD4BF.toInt() else 0xFF0F766E.toInt())
                                 else 0xFFFFFFFF.toInt(),
            colorDangerText = if (isClassic) (if (isNight) 0xFFF87171.toInt() else 0xFFDC2626.toInt())
                              else 0xFFF87171.toInt()
        )
    }

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
