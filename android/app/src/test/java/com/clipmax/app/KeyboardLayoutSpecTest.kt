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
    fun testNumpadLayoutExactFourByFiveGridCharactersAndWeights() {
        val rows = KeyboardLayoutSpec.NumpadLayoutSpec.ROWS
        assertEquals(4, rows.size)
        assertEquals("12\n34", KeyboardLayoutSpec.NumpadLayoutSpec.TOGGLE_KEY_LABEL)

        val expectedLabels = listOf(
            listOf("+", "1", "2", "3", "%"),
            listOf("\u2212", "4", "5", "6", ")"),
            listOf("\u00D7", "7", "8", "9", "\u232B"),
            listOf("ABC", ",", "0", "=", "\u21B5")
        )

        rows.forEachIndexed { rIdx, row ->
            assertEquals("Row ${rIdx + 1} must have 5 positions", 5, row.size)
            assertEquals(expectedLabels[rIdx], row.map { it.label })
            assertEquals(10.0f, row.sumOf { it.weight.toDouble() }.toFloat(), 0.01f)
            // Outer columns stay aligned across all 4 rows
            assertEquals(1.45f, row.first().weight, 0.001f)
            assertEquals(1.45f, row.last().weight, 0.001f)
        }

        // Exact character commits (pure text insertion, no arithmetic evaluation)
        val expectedCommits = mapOf(
            (0 to 0) to "+",
            (0 to 1) to "1",
            (0 to 2) to "2",
            (0 to 3) to "3",
            (0 to 4) to "%",
            (1 to 0) to "\u2212",
            (1 to 1) to "4",
            (1 to 2) to "5",
            (1 to 3) to "6",
            (1 to 4) to ")",
            (2 to 0) to "\u00D7",
            (2 to 1) to "7",
            (2 to 2) to "8",
            (2 to 3) to "9",
            (3 to 1) to ",",
            (3 to 2) to "0",
            (3 to 3) to "="
        )
        for ((pos, expectedChar) in expectedCommits) {
            val key = rows[pos.first][pos.second]
            assertEquals(expectedChar, key.commitChar)
        }

        // Non-character control keys
        assertEquals(KeyboardLayoutSpec.NumpadKeyRole.BACKSPACE, rows[2][4].role)
        assertNull(rows[2][4].commitChar)
        assertEquals(KeyboardLayoutSpec.NumpadKeyRole.RETURN_ALPHA, rows[3][0].role)
        assertNull(rows[3][0].commitChar)
        assertEquals(KeyboardLayoutSpec.NumpadKeyRole.ACTION_ENTER, rows[3][4].role)
        assertNull(rows[3][4].commitChar)

        // Bottom-row zero key is visually wider than regular digits and flanking symbols
        val commaWeight = rows[3][1].weight
        val zeroWeight = rows[3][2].weight
        val equalsWeight = rows[3][3].weight
        val oneWeight = rows[0][1].weight
        assertTrue(zeroWeight > oneWeight * 1.7f)
        assertTrue(zeroWeight > commaWeight * 2.5f)
        assertTrue(zeroWeight > equalsWeight * 2.5f)
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
