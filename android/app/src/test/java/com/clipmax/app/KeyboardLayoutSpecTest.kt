package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutSpecTest {

    @Test
    fun testAllTenTopRowEmbeddedNumberMappings() {
        val expected = mapOf(
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
        assertEquals(10, KeyboardLayoutSpec.TOP_ROW_EMBEDDED_NUMBERS.size)
        for ((letter, digit) in expected) {
            assertEquals(digit, KeyboardLayoutSpec.embeddedNumberFor(letter))
            assertEquals(digit, KeyboardLayoutSpec.embeddedNumberFor(letter.lowercase()))
        }
        assertNull(KeyboardLayoutSpec.embeddedNumberFor("A"))
        assertNull(KeyboardLayoutSpec.embeddedNumberFor("Z"))
    }

    @Test
    fun testThreeModeNavigationTransitions() {
        val controller = KeyboardLayoutSpec.KeyboardModeController()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.ALPHA, controller.currentMode)

        // ALPHA (?123) -> SYMBOLS
        controller.toSymbols()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.SYMBOLS, controller.currentMode)

        // SYMBOLS (12 34) -> NUMPAD
        controller.toNumpad()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.NUMPAD, controller.currentMode)

        // NUMPAD (ABC) -> ALPHA
        controller.toAlpha()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.ALPHA, controller.currentMode)

        // Secondary symbols round-trip: ALPHA -> SYMBOLS -> SYMBOLS_EXT -> SYMBOLS -> NUMPAD -> ALPHA
        controller.toSymbols()
        controller.toSymbolsExt()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.SYMBOLS_EXT, controller.currentMode)
        controller.toSymbols()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.SYMBOLS, controller.currentMode)
        controller.toNumpad()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.NUMPAD, controller.currentMode)
        controller.toAlpha()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.ALPHA, controller.currentMode)

        // Gboard numpad !?# returns directly to SYMBOLS and 12 34 returns back to NUMPAD
        controller.toSymbols()
        controller.toNumpad()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.NUMPAD, controller.currentMode)
        controller.toSymbols()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.SYMBOLS, controller.currentMode)
        controller.toNumpad()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.NUMPAD, controller.currentMode)
        controller.toAlpha()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.ALPHA, controller.currentMode)
    }

    @Test
    fun testNumericEditorInfoNeverAutoActivatesNumpadAndFocusResetsToAlpha() {
        val controller = KeyboardLayoutSpec.KeyboardModeController()
        val numericTypes = listOf(
            InputFieldPolicy.TYPE_CLASS_NUMBER,
            InputFieldPolicy.TYPE_CLASS_PHONE,
            InputFieldPolicy.TYPE_CLASS_DATETIME,
            InputFieldPolicy.TYPE_CLASS_NUMBER or InputFieldPolicy.TYPE_NUMBER_VARIATION_PASSWORD,
            InputFieldPolicy.TYPE_CLASS_TEXT
        )

        for (type in numericTypes) {
            val caps = InputFieldPolicy.evaluate(type)
            controller.onFieldFocused(caps, restarting = false)
            assertEquals(
                "Numeric or text EditorInfo=$type must not auto-activate NUMPAD",
                KeyboardLayoutSpec.KeyboardMode.ALPHA,
                controller.currentMode
            )
        }

        // User explicitly opens NUMPAD via SYMBOLS -> 12 34
        controller.toSymbols()
        controller.toNumpad()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.NUMPAD, controller.currentMode)

        // Same field restarting keeps current mode; moving focus to a new field resets to ALPHA
        val numCaps = InputFieldPolicy.evaluate(InputFieldPolicy.TYPE_CLASS_NUMBER)
        controller.onFieldFocused(numCaps, restarting = true)
        assertEquals(KeyboardLayoutSpec.KeyboardMode.NUMPAD, controller.currentMode)

        controller.onFieldFocused(numCaps, restarting = false)
        assertEquals(KeyboardLayoutSpec.KeyboardMode.ALPHA, controller.currentMode)

        // Window hidden resets back to ALPHA
        controller.toSymbols()
        controller.toNumpad()
        controller.onWindowHiddenReset()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.ALPHA, controller.currentMode)
    }

    @Test
    fun testGboardNumpadExactHierarchyCharactersAndProportions() {
        val spec = KeyboardLayoutSpec.NumpadLayoutSpec
        assertEquals("12\n34", spec.TOGGLE_KEY_LABEL)

        // 1. Left elongated operator rail: 4 individually tappable vertical operators (+, −, *, /)
        assertEquals(4, spec.OPERATOR_RAIL.size)
        assertEquals(listOf("+", "\u2212", "*", "/"), spec.OPERATOR_RAIL.map { it.label })
        assertEquals(listOf("+", "-", "*", "/"), spec.OPERATOR_RAIL.map { it.commitChar })
        spec.OPERATOR_RAIL.forEach { op ->
            assertEquals(KeyboardLayoutSpec.NumpadKeyRole.OPERATOR, op.role)
            assertEquals(1f, op.weight, 0.001f)
        }

        // 2. Central 3x3 number grid: equal-width columns (1-3, 4-6, 7-9)
        assertEquals(3, spec.NUMBER_ROWS.size)
        assertEquals(
            listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9")
            ),
            spec.NUMBER_ROWS.map { row -> row.map { it.label } }
        )
        spec.NUMBER_ROWS.forEach { row ->
            assertEquals(3, row.size)
            row.forEach { cell ->
                assertEquals(KeyboardLayoutSpec.NumpadKeyRole.DIGIT, cell.role)
                assertEquals(cell.label, cell.commitChar)
                assertEquals(1f, cell.weight, 0.001f)
            }
        }

        // 3. Right modifier column: 3 separate keys aligned with the 3 number rows (%, Space, Backspace)
        assertEquals(3, spec.RIGHT_COLUMN.size)
        assertEquals(KeyboardLayoutSpec.NumpadKeyRole.OPERATOR, spec.RIGHT_COLUMN[0].role)
        assertEquals("%", spec.RIGHT_COLUMN[0].label)
        assertEquals("%", spec.RIGHT_COLUMN[0].commitChar)
        assertEquals(KeyboardLayoutSpec.NumpadKeyRole.SPACE, spec.RIGHT_COLUMN[1].role)
        assertEquals(" ", spec.RIGHT_COLUMN[1].commitChar)
        assertEquals(KeyboardLayoutSpec.NumpadKeyRole.BACKSPACE, spec.RIGHT_COLUMN[2].role)
        assertNull(spec.RIGHT_COLUMN[2].commitChar)

        // 4. Bottom action row: exact 7 controls in order (ABC | , | !?# | 0 | = | . | ↵)
        assertEquals(7, spec.BOTTOM_ROW.size)
        assertEquals(
            listOf("ABC", ",", "!?#", "0", "=", ".", "\u21B5"),
            spec.BOTTOM_ROW.map { it.label }
        )
        assertEquals(KeyboardLayoutSpec.NumpadKeyRole.RETURN_ALPHA, spec.BOTTOM_ROW[0].role)
        assertEquals(KeyboardLayoutSpec.NumpadKeyRole.OPERATOR, spec.BOTTOM_ROW[1].role)
        assertEquals(",", spec.BOTTOM_ROW[1].commitChar)
        assertEquals(KeyboardLayoutSpec.NumpadKeyRole.TO_SYMBOLS, spec.BOTTOM_ROW[2].role)
        assertNull(spec.BOTTOM_ROW[2].commitChar)
        assertEquals(KeyboardLayoutSpec.NumpadKeyRole.DIGIT, spec.BOTTOM_ROW[3].role)
        assertEquals("0", spec.BOTTOM_ROW[3].commitChar)
        assertEquals(KeyboardLayoutSpec.NumpadKeyRole.OPERATOR, spec.BOTTOM_ROW[4].role)
        assertEquals("=", spec.BOTTOM_ROW[4].commitChar)
        assertEquals(KeyboardLayoutSpec.NumpadKeyRole.OPERATOR, spec.BOTTOM_ROW[5].role)
        assertEquals(".", spec.BOTTOM_ROW[5].commitChar)
        assertEquals(KeyboardLayoutSpec.NumpadKeyRole.ACTION_ENTER, spec.BOTTOM_ROW[6].role)
        assertNull(spec.BOTTOM_ROW[6].commitChar)

        // 5. Outer column weights and symmetric centered '0' key match upper block cleanly
        assertEquals(spec.LEFT_RAIL_WEIGHT, spec.BOTTOM_LEFT.weight, 0.001f)
        assertEquals(spec.RIGHT_COLUMN_WEIGHT, spec.BOTTOM_RIGHT.weight, 0.001f)
        assertEquals(
            spec.CENTER_BLOCK_WEIGHT,
            spec.BOTTOM_CENTER_KEYS.sumOf { it.weight.toDouble() }.toFloat(),
            0.001f
        )
        assertEquals(
            10.0f,
            spec.LEFT_RAIL_WEIGHT + spec.CENTER_BLOCK_WEIGHT + spec.RIGHT_COLUMN_WEIGHT,
            0.001f
        )
        val commaW = spec.BOTTOM_CENTER_KEYS[0].weight
        val symW = spec.BOTTOM_CENTER_KEYS[1].weight
        val zeroW = spec.BOTTOM_CENTER_KEYS[2].weight
        val eqW = spec.BOTTOM_CENTER_KEYS[3].weight
        val dotW = spec.BOTTOM_CENTER_KEYS[4].weight
        assertEquals(commaW + symW, eqW + dotW, 0.001f)
        assertTrue(zeroW > spec.CENTER_BLOCK_WEIGHT / 3f)
        assertTrue(zeroW > commaW * 2.0f)
    }

    @Test
    fun testClipboardAndSuggestionStripsHiddenInNumpadModeOnly() {
        // In NUMPAD, both horizontal clipboard snippet strip and suggestions strip must be hidden
        for (centerMode in listOf(0, 1)) {
            val state = KeyboardLayoutSpec.resolveToolbarStripState(
                mode = KeyboardLayoutSpec.KeyboardMode.NUMPAD,
                centerMode = centerMode,
                isPasswordTarget = false,
                isFilterMode = false,
                isSuggestionsEnabled = true,
                allowSuggestions = true
            )
            assertFalse(state.showClipboardStrip)
            assertFalse(state.showSuggestionsStrip)
        }

        // In ALPHA and SYMBOLS, clipboard snippet strip is visible by default (centerMode = 0)
        for (mode in listOf(
            KeyboardLayoutSpec.KeyboardMode.ALPHA,
            KeyboardLayoutSpec.KeyboardMode.SYMBOLS,
            KeyboardLayoutSpec.KeyboardMode.SYMBOLS_EXT
        )) {
            val clipState = KeyboardLayoutSpec.resolveToolbarStripState(
                mode = mode,
                centerMode = 0,
                isPasswordTarget = false,
                isFilterMode = false,
                isSuggestionsEnabled = true,
                allowSuggestions = true
            )
            assertTrue(clipState.showClipboardStrip)
            assertFalse(clipState.showSuggestionsStrip)

            val suggestState = KeyboardLayoutSpec.resolveToolbarStripState(
                mode = mode,
                centerMode = 1,
                isPasswordTarget = false,
                isFilterMode = false,
                isSuggestionsEnabled = true,
                allowSuggestions = true
            )
            assertFalse(suggestState.showClipboardStrip)
            assertTrue(suggestState.showSuggestionsStrip)
        }
    }

    @Test
    fun testSearchClipsTemporaryQwertyAndReturnToNumpad() {
        val controller = KeyboardLayoutSpec.KeyboardModeController()

        // Enter Search Clips from NUMPAD -> temporarily switches to ALPHA
        controller.toSymbols()
        controller.toNumpad()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.NUMPAD, controller.currentMode)

        controller.enterSearchClips()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.ALPHA, controller.currentMode)

        // Exiting Search Clips restores NUMPAD
        controller.exitSearchClips()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.NUMPAD, controller.currentMode)

        // Enter/Exit Search Clips from ALPHA returns cleanly to ALPHA
        controller.toAlpha()
        controller.enterSearchClips()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.ALPHA, controller.currentMode)
        controller.exitSearchClips()
        assertEquals(KeyboardLayoutSpec.KeyboardMode.ALPHA, controller.currentMode)
    }

    @Test
    fun testThemePalettesForGraphiteClassicDarkAndClassicLight() {
        val graphite = KeyboardLayoutSpec.resolveThemePalette("graphite", systemNightMode = false)
        assertFalse(graphite.isClassicTheme)
        assertTrue(graphite.isNightMode)
        assertEquals(graphite.colorKeyBg, graphite.enterKeyBg)
        assertEquals(graphite.colorKeyText, graphite.enterKeyFg)
        assertNotEquals(graphite.colorKeyBg, graphite.colorModBg)

        val classicDark = KeyboardLayoutSpec.resolveThemePalette("classic", systemNightMode = true)
        assertTrue(classicDark.isClassicTheme)
        assertTrue(classicDark.isNightMode)
        assertEquals(classicDark.colorAccent, classicDark.enterKeyBg)
        assertEquals(classicDark.colorAccentText, classicDark.enterKeyFg)
        assertNotEquals(classicDark.colorBg, graphite.colorBg)

        val classicLight = KeyboardLayoutSpec.resolveThemePalette("classic", systemNightMode = false)
        assertTrue(classicLight.isClassicTheme)
        assertFalse(classicLight.isNightMode)
        assertEquals(classicLight.colorAccent, classicLight.enterKeyBg)
        assertEquals(classicLight.colorAccentText, classicLight.enterKeyFg)
        assertNotEquals(classicLight.colorBg, classicDark.colorBg)
        assertEquals(0xFFECEFF1.toInt(), classicLight.colorBg)
        assertEquals(0xFF151C18.toInt(), classicDark.colorBg)
    }

    @Test
    fun testHarmonizedNeutralDarkPaletteTiersAndMicroBorders() {
        val graphite = KeyboardLayoutSpec.resolveThemePalette("graphite", systemNightMode = true)
        assertEquals(0xFF121316.toInt(), graphite.colorBg)
        assertEquals(0xFF181A1F.toInt(), graphite.colorAltDeepBg)
        assertEquals(0xFF282C33.toInt(), graphite.colorKeyBg)
        assertEquals(0xFF202328.toInt(), graphite.colorAltPrimaryBg)
        assertEquals(0xFF202328.toInt(), graphite.colorModBg)
        assertEquals(0xFF21252B.toInt(), graphite.colorToolbarBg)
        assertEquals(0xFF21252B.toInt(), graphite.colorChipBg)
        assertEquals(0xFFE1E3E6.toInt(), graphite.colorKeyText)
        assertEquals(0xFFF0F2F5.toInt(), graphite.colorAltPrimaryText)

        // Micro-borders must be subtle 8-12% white opacity in dark mode
        val borderAlpha = (graphite.colorKeyBorder ushr 24) and 0xFF
        assertTrue("Expected ~8-12% white micro-border, got alpha=$borderAlpha", borderAlpha in 20..32)
        assertEquals(0x00FFFFFF, graphite.colorKeyBorder and 0x00FFFFFF)
    }

    @Test
    fun testErgonomicGeometryReferenceProportionsAndResizeScaling() {
        // Normal portrait phone (~840dp tall): target ~240-260dp total viewport and 4-row proportions
        val normalViewport = KeyboardLayoutSpec.computeViewportSpecDp("normal", screenHeightDp = 840f, isLandscape = false)
        assertTrue(normalViewport.totalContentHeightDp in 240f..260f)

        val normal = KeyboardLayoutSpec.computeGeometryDp("normal", screenHeightDp = 840f, isLandscape = false)
        assertTrue(normal.keyHeightDp in 42f..46f)
        assertTrue(normal.interRowGapDp in 6f..8f)
        assertTrue(normal.horizontalGapDp in 4f..5f)
        assertTrue(normal.cornerRadiusDp in 8f..10f)
        assertEquals(8f, normal.topPaddingDp, 0.01f)
        assertEquals(8f, normal.bottomPaddingDp, 0.01f)

        val compact = KeyboardLayoutSpec.computeGeometryDp("compact", screenHeightDp = 840f, isLandscape = false)
        val tall = KeyboardLayoutSpec.computeGeometryDp("tall", screenHeightDp = 840f, isLandscape = false)
        assertTrue(compact.keyHeightDp < normal.keyHeightDp)
        assertTrue(tall.keyHeightDp > normal.keyHeightDp)

        // Landscape viewport clamps keyboard cleanly so it doesn't exceed viewport height
        val landscape = KeyboardLayoutSpec.computeGeometryDp("tall", screenHeightDp = 390f, isLandscape = true)
        val totalLandscapeRows = landscape.keyHeightDp * 4f + landscape.interRowGapDp * 3f +
            landscape.topPaddingDp + landscape.bottomPaddingDp
        assertTrue(totalLandscapeRows <= 390f * 0.65f)
    }

    @Test
    fun testLongPressStateMachinePreventsLetterDuplicateAndHoldRepeat() {
        val ctrl = KeyboardLayoutSpec.LongPressKeyController()

        // 1. Short normal tap commits primary letter only
        ctrl.onDown()
        assertFalse(ctrl.isLongPressTriggered())
        assertTrue(ctrl.onUp())

        // 2. Long-press fires digit ONCE, never repeats, and ACTION_UP does NOT commit letter
        ctrl.onDown()
        assertTrue(ctrl.onLongPressTimeout())
        assertTrue(ctrl.isLongPressTriggered())
        assertFalse(ctrl.onLongPressTimeout()) // Holding further must not repeat
        assertFalse(ctrl.onUp()) // Releasing must NOT also insert letter

        // 3. Sliding finger off key cancels both tap and timeout
        ctrl.onDown()
        assertTrue(ctrl.onMove(insideBounds = false))
        assertFalse(ctrl.onLongPressTimeout())
        assertFalse(ctrl.onUp())

        // 4. Rapid subsequent tap still works cleanly
        ctrl.onDown()
        assertTrue(ctrl.onUp())
    }

    @Test
    fun testCanonicalFixedViewportHeightConsistencyAcrossOverlaysAndPresets() {
        for (preset in listOf("compact", "normal", "tall")) {
            for ((screenH, isLand) in listOf(840f to false, 390f to true)) {
                val viewport = KeyboardLayoutSpec.computeViewportSpecDp(preset, screenH, isLand)
                val base = KeyboardLayoutSpec.computeGeometryDp(preset, screenH, isLand, hasFilterBar = false, hasStatusBanner = false)
                val withSearch = KeyboardLayoutSpec.computeGeometryDp(preset, screenH, isLand, hasFilterBar = true, hasStatusBanner = false)
                val withBanner = KeyboardLayoutSpec.computeGeometryDp(preset, screenH, isLand, hasFilterBar = false, hasStatusBanner = true)
                val withBoth = KeyboardLayoutSpec.computeGeometryDp(preset, screenH, isLand, hasFilterBar = true, hasStatusBanner = true)

                fun rowsHeight(g: KeyboardLayoutSpec.GeometryDp): Float =
                    g.keyHeightDp * 4f + g.interRowGapDp * 3f + g.topPaddingDp + g.bottomPaddingDp

                assertEquals(viewport.mainPanelHeightDp, rowsHeight(base), 0.1f)
                assertTrue(rowsHeight(withSearch) + viewport.searchInterfaceHeightDp <= viewport.mainPanelHeightDp + 0.1f)
                assertTrue(rowsHeight(withBanner) + viewport.statusBannerHeightDp <= viewport.mainPanelHeightDp + 0.1f)
                assertTrue(
                    rowsHeight(withBoth) + viewport.searchInterfaceHeightDp + viewport.statusBannerHeightDp <=
                        viewport.mainPanelHeightDp + 0.1f
                )
            }
        }
    }
}
