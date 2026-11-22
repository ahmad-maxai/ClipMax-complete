package com.clipmax.app

/**
 * Ergonomic keyboard layout geometry, embedded top-row number mappings (Q-P -> 1-0),
 * Gboard-style three-mode navigation controller (ALPHA -> SYMBOLS -> NUMPAD),
 * exact Gboard numeric keypad specification, IME theme palette resolver,
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

    data class ArabicKeySpec(
        val primary: String,
        val hintDigit: String? = null
    )

    data class LanguageUiSpec(
        val language: String,
        val spacebarLabel: String,
        val alphaToggleLabel: String,
        val symbolsToggleLabel: String,
        val alphaCommaChar: String,
        val textDirectionRtl: Boolean
    )

    fun languageUiSpec(language: String?): LanguageUiSpec {
        return if (SettingsPanelSpec.normalizeLanguageValue(language) == SettingsPanelSpec.LANG_ARABIC) {
            LanguageUiSpec(
                language = SettingsPanelSpec.LANG_ARABIC,
                spacebarLabel = "العربية",
                alphaToggleLabel = "أبج",
                symbolsToggleLabel = "؟٣٢١",
                alphaCommaChar = "\u060C",
                textDirectionRtl = true
            )
        } else {
            LanguageUiSpec(
                language = SettingsPanelSpec.LANG_ENGLISH,
                spacebarLabel = "English",
                alphaToggleLabel = "ABC",
                symbolsToggleLabel = "?123",
                alphaCommaChar = ",",
                textDirectionRtl = false
            )
        }
    }

    data class LanguagePopupOption(
        val code: String,
        val primaryLabel: String,
        val secondaryLabel: String,
        val accessibilityLabel: String
    )

    val LANGUAGE_POPUP_OPTIONS: List<LanguagePopupOption> = listOf(
        LanguagePopupOption(
            code = SettingsPanelSpec.LANG_ENGLISH,
            primaryLabel = "English",
            secondaryLabel = "QWERTY",
            accessibilityLabel = "English (QWERTY)"
        ),
        LanguagePopupOption(
            code = SettingsPanelSpec.LANG_ARABIC,
            primaryLabel = "العربية",
            secondaryLabel = "Arabic",
            accessibilityLabel = "العربية (Arabic)"
        )
    )

    // Standard Arabic layout matching reference screenshots:
    // Row 1 (11 keys, top-right digit hints ١–٩ on first 9 keys): ض ص ث ق ف غ ع ه خ ح ج
    val ARABIC_ALPHA_ROW1: List<ArabicKeySpec> = listOf(
        ArabicKeySpec("ض", "١"),
        ArabicKeySpec("ص", "٢"),
        ArabicKeySpec("ث", "٣"),
        ArabicKeySpec("ق", "٤"),
        ArabicKeySpec("ف", "٥"),
        ArabicKeySpec("غ", "٦"),
        ArabicKeySpec("ع", "٧"),
        ArabicKeySpec("ه", "٨"),
        ArabicKeySpec("خ", "٩"),
        ArabicKeySpec("ح", null),
        ArabicKeySpec("ج", null)
    )

    // Row 2 (11 keys): ش س ي ب ل ا ت ن م ك ط
    val ARABIC_ALPHA_ROW2: List<String> = listOf(
        "ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"
    )

    // Row 3 (10 letters + Backspace): ذ ء ؤ ر ى ة و ز ظ د
    val ARABIC_ALPHA_ROW3: List<String> = listOf(
        "ذ", "ء", "ؤ", "ر", "ى", "ة", "و", "ز", "ظ", "د"
    )

    // Arabic Symbols & Numerals (?٣٢١)
    val ARABIC_SYMBOLS_ROW1: List<String> = listOf("١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", "٠")
    val ARABIC_SYMBOLS_ROW2: List<String> = listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/")
    val ARABIC_SYMBOLS_ROW3: List<String> = listOf("*", "\"", "'", ":", "\u061B", "!", "\u061F")

    // Arabic Secondary Symbols (=\<) including Arabic percent, Rial, guillemets, decimal/thousands separators
    val ARABIC_SYMBOLS_EXT_ROW1: List<String> = listOf("~", "`", "|", "•", "√", "\u066A", "÷", "×", "\uFDFC", "°")
    val ARABIC_SYMBOLS_EXT_ROW2: List<String> = listOf("£", "€", "¥", "¢", "^", "%", "=", "{", "}", "\\")
    val ARABIC_SYMBOLS_EXT_ROW3: List<String> = listOf("<", ">", "«", "»", "\u066B", "\u066C", "[", "]")

    // Arabic Diacritics (Tanwin, Harakat, Shaddah, Sukun, Tatweel) & Arabic Punctuation on long-press of "."
    val ARABIC_PERIOD_VARIANTS: List<String> = listOf(
        "\u064B", // Fathatan (ً)
        "\u064C", // Dammatan (ٌ)
        "\u064D", // Kasratan (ٍ)
        "\u064E", // Fathah (َ)
        "\u064F", // Dammah (ُ)
        "\u0650", // Kasrah (ِ)
        "\u0651", // Shaddah (ّ)
        "\u0652", // Sukun (ْ)
        "\u0640", // Tatweel / Kashida (ـ)
        "\u061F", // Arabic Question Mark (؟)
        "!",
        "\u060C", // Arabic Comma (،)
        "\u061B"  // Arabic Semicolon (؛)
    )

    val ARABIC_LONG_PRESS_VARIANTS: Map<String, List<String>> = mapOf(
        // Top row letter keys (with embedded digit first where shown)
        "ض" to listOf("١", "1"),
        "ص" to listOf("٢", "2"),
        "ث" to listOf("٣", "3"),
        "ق" to listOf("٤", "4"),
        "ف" to listOf("٥", "5", "ڤ"),
        "غ" to listOf("٦", "6"),
        "ع" to listOf("٧", "7"),
        "ه" to listOf("٨", "8", "ة"),
        "خ" to listOf("٩", "9"),
        "ح" to listOf("٠", "0"),
        "ج" to listOf("چ"),
        // Row 2 letter variants
        "ب" to listOf("پ"),
        "ي" to listOf("ى", "ئ"),
        "ا" to listOf("أ", "إ", "آ", "ٱ", "ء"),
        "ت" to listOf("ة", "ط"),
        "ك" to listOf("گ"),
        "ط" to listOf("ظ"),
        // Row 3 letter variants
        "ذ" to listOf("د"),
        "ء" to listOf("أ", "إ", "آ", "ؤ", "ئ"),
        "ؤ" to listOf("و", "ء"),
        "ى" to listOf("ي", "ا", "آ"),
        "ة" to listOf("ه", "ت"),
        "و" to listOf("ؤ"),
        "ز" to listOf("ژ"),
        "ظ" to listOf("ط"),
        "د" to listOf("ذ"),
        // Cross-numeral alternatives between Arabic-Indic and Western digits
        "١" to listOf("1"),
        "٢" to listOf("2"),
        "٣" to listOf("3"),
        "٤" to listOf("4"),
        "٥" to listOf("5"),
        "٦" to listOf("6"),
        "٧" to listOf("7"),
        "٨" to listOf("8"),
        "٩" to listOf("9"),
        "٠" to listOf("0"),
        "1" to listOf("١"),
        "2" to listOf("٢"),
        "3" to listOf("٣"),
        "4" to listOf("٤"),
        "5" to listOf("٥"),
        "6" to listOf("٦"),
        "7" to listOf("٧"),
        "8" to listOf("٨"),
        "9" to listOf("٩"),
        "0" to listOf("٠"),
        // Punctuation & diacritics
        "\u060C" to listOf(","),
        "," to listOf("\u060C"),
        "\u061B" to listOf(";"),
        "\u061F" to listOf("?"),
        "%" to listOf("\u066A", "‰"),
        "\u066A" to listOf("%", "‰"),
        "." to ARABIC_PERIOD_VARIANTS
    )

    fun arabicVariantsFor(key: String): List<String> =
        ARABIC_LONG_PRESS_VARIANTS[key].orEmpty()

    fun isArabicCombiningMark(cp: Int): Boolean =
        cp in 0x064B..0x065F || cp == 0x0670 || cp in 0x06D6..0x06ED

    fun displayLabelForArabicVariant(token: String): String {
        if (token.length == 1) {
            val cp = token[0].code
            if (cp in 0x064B..0x065F || cp == 0x0670) {
                return "\u25CC$token"
            }
        }
        return token
    }

    /**
     * Computes UTF-16 length to delete on Backspace.
     * For standalone Arabic diacritics (fathah, dammah, kasrah, shaddah, sukun, tanwin, superscript alef),
     * removes only the trailing combining mark first so mis-tapped harakat do not erase the base letter;
     * otherwise deletes the complete trailing grapheme/emoji cluster safely.
     */
    fun backspaceUtf16Count(beforeCursor: CharSequence?, language: String): Int {
        if (beforeCursor == null || beforeCursor.isEmpty()) return 0
        val lastCp = Character.codePointBefore(beforeCursor, beforeCursor.length)
        if (lastCp in 0x064B..0x065F || lastCp == 0x0670) {
            return Character.charCount(lastCp)
        }
        return EmojiInterfaceSpec.lastGraphemeUtf16Length(beforeCursor)
    }

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
     * - NUMPAD (!?#) -> SYMBOLS
     *
     * Auto-activates NUMPAD when focusing numeric EditorInfo fields and restores ALPHA when
     * returning to text/email/URL/password fields. Supports temporary QWERTY entry during
     * Search Clips and restores prior mode on exit.
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
            capabilities: InputFieldPolicy.FieldCapabilities,
            restarting: Boolean = false,
            isFilterMode: Boolean = false
        ) {
            if (!restarting) {
                val targetMode = if (InputFieldPolicy.shouldAutoActivateNumpad(capabilities, isFilterMode = false)) {
                    KeyboardMode.NUMPAD
                } else {
                    KeyboardMode.ALPHA
                }
                if (isFilterMode) {
                    preSearchMode = targetMode
                    currentMode = KeyboardMode.ALPHA
                } else {
                    preSearchMode = null
                    currentMode = targetMode
                }
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
        SPACE,
        BACKSPACE,
        RETURN_ALPHA,
        TO_SYMBOLS,
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
     * Exact Gboard-style numeric keypad hierarchy matching reference "185765.png":
     *
     * Upper 3-row block (1.40f left rail | 7.20f center 3x3 digits | 1.40f right column):
     * - Left elongated operator rail (single rounded vertical pill spanning 3 number rows):
     *     + , − , * , / (individually tappable)
     * - Central 3x3 number grid:
     *     1 | 2 | 3
     *     4 | 5 | 6
     *     7 | 8 | 9
     * - Right modifier/action column (3 separate keys aligned with the 3 number rows):
     *     %
     *     Space (⊔)
     *     Backspace (⌫)
     *
     * Bottom action row (exact 7 controls in order, flush 1.40f | 7.20f | 1.40f alignment):
     *     ABC | , | !?# | 0 (wide centered, 2.80f) | = | . | Dynamic Action (↵)
     */
    object NumpadLayoutSpec {
        const val MINUS_LABEL = "\u2212"
        const val TOGGLE_KEY_LABEL = "12\n34"

        const val LEFT_RAIL_WEIGHT = 1.40f
        const val CENTER_BLOCK_WEIGHT = 7.20f
        const val RIGHT_COLUMN_WEIGHT = 1.40f

        val OPERATOR_RAIL: List<NumpadKeySpec> = listOf(
            NumpadKeySpec("+", "+", NumpadKeyRole.OPERATOR, 1f, "Plus"),
            NumpadKeySpec(MINUS_LABEL, "-", NumpadKeyRole.OPERATOR, 1f, "Minus"),
            NumpadKeySpec("*", "*", NumpadKeyRole.OPERATOR, 1f, "Multiply"),
            NumpadKeySpec("/", "/", NumpadKeyRole.OPERATOR, 1f, "Divide")
        )

        val NUMBER_ROWS: List<List<NumpadKeySpec>> = listOf(
            listOf(
                NumpadKeySpec("1", "1", NumpadKeyRole.DIGIT, 1f, "1"),
                NumpadKeySpec("2", "2", NumpadKeyRole.DIGIT, 1f, "2"),
                NumpadKeySpec("3", "3", NumpadKeyRole.DIGIT, 1f, "3")
            ),
            listOf(
                NumpadKeySpec("4", "4", NumpadKeyRole.DIGIT, 1f, "4"),
                NumpadKeySpec("5", "5", NumpadKeyRole.DIGIT, 1f, "5"),
                NumpadKeySpec("6", "6", NumpadKeyRole.DIGIT, 1f, "6")
            ),
            listOf(
                NumpadKeySpec("7", "7", NumpadKeyRole.DIGIT, 1f, "7"),
                NumpadKeySpec("8", "8", NumpadKeyRole.DIGIT, 1f, "8"),
                NumpadKeySpec("9", "9", NumpadKeyRole.DIGIT, 1f, "9")
            )
        )

        val RIGHT_COLUMN: List<NumpadKeySpec> = listOf(
            NumpadKeySpec("%", "%", NumpadKeyRole.OPERATOR, 1f, "Percent"),
            NumpadKeySpec(" ", " ", NumpadKeyRole.SPACE, 1f, "Space"),
            NumpadKeySpec("\u232B", null, NumpadKeyRole.BACKSPACE, 1f, "Backspace")
        )

        val BOTTOM_LEFT: NumpadKeySpec =
            NumpadKeySpec("ABC", null, NumpadKeyRole.RETURN_ALPHA, LEFT_RAIL_WEIGHT, "Return to letters")

        val BOTTOM_CENTER_KEYS: List<NumpadKeySpec> = listOf(
            NumpadKeySpec(",", ",", NumpadKeyRole.OPERATOR, 1.10f, "Comma"),
            NumpadKeySpec("!?#", null, NumpadKeyRole.TO_SYMBOLS, 1.10f, "Symbols"),
            NumpadKeySpec("0", "0", NumpadKeyRole.DIGIT, 2.80f, "0"),
            NumpadKeySpec("=", "=", NumpadKeyRole.OPERATOR, 1.10f, "Equals"),
            NumpadKeySpec(".", ".", NumpadKeyRole.OPERATOR, 1.10f, "Period")
        )

        val BOTTOM_RIGHT: NumpadKeySpec =
            NumpadKeySpec("\u21B5", null, NumpadKeyRole.ACTION_ENTER, RIGHT_COLUMN_WEIGHT, "Enter")

        val BOTTOM_ROW: List<NumpadKeySpec> = listOf(BOTTOM_LEFT) + BOTTOM_CENTER_KEYS + listOf(BOTTOM_RIGHT)
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
        val colorDangerText: Int,
        val colorAltDeepBg: Int = colorBg,
        val colorAltPrimaryBg: Int = colorKeyBg,
        val colorAltPrimaryText: Int = colorKeyText,
        val colorToolbarBg: Int = colorBg
    ) {
        val enterKeyBg: Int get() = if (isClassicTheme) colorAccent else colorKeyBg
        val enterKeyFg: Int get() = if (isClassicTheme) colorAccentText else colorKeyText
    }

    fun resolveThemePalette(savedTheme: String?, systemNightMode: Boolean): ImeThemePalette {
        val normalized = savedTheme?.trim()?.lowercase(java.util.Locale.ROOT)
        val isClassic = normalized == "classic" || normalized == "classic_light"
        val isNight = when (normalized) {
            "dark", "graphite" -> true
            "light", "classic_light" -> false
            "classic" -> systemNightMode
            "system", null, "" -> systemNightMode
            else -> systemNightMode
        }
        return ImeThemePalette(
            isClassicTheme = isClassic,
            isNightMode = isNight,
            colorBg = when {
                isClassic && !isNight -> 0xFFECEFF1.toInt()
                isClassic -> 0xFF151C18.toInt()
                !isNight -> 0xFFF1F3F4.toInt()
                else -> 0xFF202124.toInt()
            },
            colorKeyBg = when {
                isClassic && !isNight -> 0xFFFFFFFF.toInt()
                isClassic -> 0xFF223029.toInt()
                !isNight -> 0xFFFFFFFF.toInt()
                else -> 0xFF3C4043.toInt()
            },
            colorKeyBorder = when {
                isClassic && !isNight -> 0xFFD1D9D4.toInt()
                isClassic -> 0xFF2C3D35.toInt()
                !isNight -> 0xFFDADCE0.toInt()
                else -> 0x1FFFFFFF.toInt() // ~12% white micro-border
            },
            colorKeyText = when {
                isClassic && !isNight -> 0xFF111827.toInt()
                isClassic -> 0xFFEAF1ED.toInt()
                !isNight -> 0xFF202124.toInt()
                else -> 0xFFE8EAED.toInt()
            },
            colorModBg = when {
                isClassic && !isNight -> 0xFFDFE5E1.toInt()
                isClassic -> 0xFF1A2520.toInt()
                !isNight -> 0xFFDCE1E7.toInt()
                else -> 0xFF5F6368.toInt()
            },
            colorModText = when {
                isClassic && !isNight -> 0xFF374151.toInt()
                isClassic -> 0xFF9CB3A9.toInt()
                !isNight -> 0xFF3C4043.toInt()
                else -> 0xFFE8EAED.toInt()
            },
            colorAccent = when {
                isClassic && isNight -> 0xFF2DD4BF.toInt()
                isClassic -> 0xFF0D9488.toInt()
                !isNight -> 0xFF202124.toInt()
                else -> 0xFFE8EAED.toInt()
            },
            colorAccentText = when {
                isClassic && isNight -> 0xFF0A1F18.toInt()
                isClassic -> 0xFFFFFFFF.toInt()
                !isNight -> 0xFFFFFFFF.toInt()
                else -> 0xFF202124.toInt()
            },
            colorHintText = when {
                isClassic && isNight -> 0xFF7A8E85.toInt()
                isClassic -> 0xFF8A9992.toInt()
                !isNight -> 0xFF5F6368.toInt()
                else -> 0xFF9AA0A6.toInt()
            },
            colorChipBg = when {
                isClassic && isNight -> 0xFF1E2C26.toInt()
                isClassic -> 0xFFFFFFFF.toInt()
                !isNight -> 0xFFFFFFFF.toInt()
                else -> 0xFF303134.toInt()
            },
            colorChipBorder = when {
                isClassic && isNight -> 0xFF2C3D35.toInt()
                isClassic -> 0xFFD1D9D4.toInt()
                !isNight -> 0xFFDADCE0.toInt()
                else -> 0x1FFFFFFF.toInt() // ~12% white micro-border
            },
            colorSnippetBg = when {
                isClassic && isNight -> 0xFF141C18.toInt()
                isClassic -> 0xFFF3F6F4.toInt()
                !isNight -> 0xFFF8F9FA.toInt()
                else -> 0xFF303134.toInt()
            },
            colorSnippetBorder = when {
                isClassic && isNight -> 0xFF23322B.toInt()
                isClassic -> 0xFFE2E8E4.toInt()
                !isNight -> 0xFFE1E3E6.toInt()
                else -> 0x1FFFFFFF.toInt() // ~12% white micro-border
            },
            colorActiveTabBg = when {
                isClassic && isNight -> 0xFF1A3A32.toInt()
                isClassic -> 0xFFCCFBF1.toInt()
                !isNight -> 0xFFE0E5EB.toInt()
                else -> 0xFF484C52.toInt()
            },
            colorActiveTabText = when {
                isClassic && isNight -> 0xFF2DD4BF.toInt()
                isClassic -> 0xFF0F766E.toInt()
                !isNight -> 0xFF202124.toInt()
                else -> 0xFFFFFFFF.toInt()
            },
            colorDangerText = when {
                isClassic && isNight -> 0xFFF87171.toInt()
                isClassic -> 0xFFDC2626.toInt()
                !isNight -> 0xFFD93025.toInt()
                else -> 0xFFF87171.toInt()
            },
            colorAltDeepBg = when {
                isClassic && !isNight -> 0xFFE9EFEA.toInt()
                isClassic -> 0xFF141C18.toInt()
                !isNight -> 0xFFE8EAED.toInt()
                else -> 0xFF303134.toInt()
            },
            colorAltPrimaryBg = when {
                isClassic && !isNight -> 0xFFDFE5E1.toInt()
                isClassic -> 0xFF1A2520.toInt()
                !isNight -> 0xFFFFFFFF.toInt()
                else -> 0xFF3C4043.toInt()
            },
            colorAltPrimaryText = when {
                isClassic && !isNight -> 0xFF111827.toInt()
                isClassic -> 0xFFEAF1ED.toInt()
                !isNight -> 0xFF202124.toInt()
                else -> 0xFFE8EAED.toInt()
            },
            colorToolbarBg = when {
                isClassic && !isNight -> 0xFFECEFF1.toInt()
                isClassic -> 0xFF151C18.toInt()
                !isNight -> 0xFFE8EAED.toInt()
                else -> 0xFF303134.toInt()
            }
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
     * Computes the canonical shared expanded viewport height for media panels
     * (Emoji, Kaomoji, Stickers, and GIFs), ensuring a generous, comfortable browsing
     * grid that never covers the active target text field or exceeds screen bounds.
     */
    fun computeMediaViewportSpecDp(
        heightPref: String,
        screenHeightDp: Float,
        isLandscape: Boolean
    ): ViewportSpecDp {
        val standard = computeViewportSpecDp(heightPref, screenHeightDp, isLandscape)
        val maxFraction = if (isLandscape) 0.60f else 0.48f
        val maxMediaBudgetDp = (screenHeightDp * maxFraction - TOOLBAR_HEIGHT_DP).coerceAtLeast(standard.mainPanelHeightDp)
        val desiredMediaPanelDp = (standard.mainPanelHeightDp * 1.28f).coerceIn(standard.mainPanelHeightDp, maxMediaBudgetDp)
        return standard.copy(
            mainPanelHeightDp = desiredMediaPanelDp,
            totalContentHeightDp = TOOLBAR_HEIGHT_DP + desiredMediaPanelDp
        )
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
