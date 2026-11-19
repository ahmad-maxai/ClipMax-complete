package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondaryToolbarSpecTest {

    @Test
    fun testExactFiveControlsOrderWithoutUndoOrRedo() {
        assertEquals(
            listOf(
                SecondaryToolbarSpec.ToolbarControl.BACK,
                SecondaryToolbarSpec.ToolbarControl.PINNED,
                SecondaryToolbarSpec.ToolbarControl.SEARCH_CLIPS,
                SecondaryToolbarSpec.ToolbarControl.SETTINGS,
                SecondaryToolbarSpec.ToolbarControl.GLOBE
            ),
            SecondaryToolbarSpec.ORDERED_CONTROLS
        )
        assertEquals(5, SecondaryToolbarSpec.ORDERED_CONTROLS.size)
    }

    @Test
    fun testToolbarIsIconOnlyWithoutLabelsOrPersistentActiveCard() {
        assertTrue(SecondaryToolbarSpec.ICON_ONLY)
        assertFalse(SecondaryToolbarSpec.SHOW_LABELS)
        assertFalse(SecondaryToolbarSpec.PERSISTENT_ACTIVE_CARD)
        assertTrue(SecondaryToolbarSpec.CENTER_ICON_SIZE_DP >= 19.5f)
        assertTrue(SecondaryToolbarSpec.TOUCH_RIPPLE_SIZE_DP >= 32f)
        assertTrue(SecondaryToolbarSpec.PRESS_SCALE in 0.80f..0.95f)
        assertTrue(SecondaryToolbarSpec.PRESS_ALPHA in 0.50f..0.85f)
    }

    @Test
    fun testToolbarHeightMatchesCanonicalViewportWithoutExpansion() {
        assertEquals(38f, SecondaryToolbarSpec.TOOLBAR_HEIGHT_DP, 0.001f)
        assertEquals(KeyboardLayoutSpec.TOOLBAR_HEIGHT_DP, SecondaryToolbarSpec.TOOLBAR_HEIGHT_DP, 0.001f)

        val portrait = KeyboardLayoutSpec.computeViewportSpecDp("normal", 850f, isLandscape = false)
        assertEquals(38f, portrait.toolbarHeightDp, 0.001f)
        assertEquals(portrait.toolbarHeightDp + portrait.mainPanelHeightDp, portrait.totalContentHeightDp, 0.001f)
    }

    @Test
    fun testEndButtonGeometryAndPalettesAcrossGraphiteAndClassic() {
        assertEquals(
            SecondaryToolbarSpec.END_BUTTON_SIZE_DP / 2f,
            SecondaryToolbarSpec.END_BUTTON_CORNER_DP,
            0.001f
        )

        val graphite = SecondaryToolbarSpec.resolvePalette(isNightMode = true, isClassicTheme = false)
        assertEquals(0xFF303134.toInt(), graphite.toolbarBg)
        assertEquals(0xFFFFFFFF.toInt(), graphite.backCircleBg)
        assertEquals(0xFF111111.toInt(), graphite.backIconFg)
        assertEquals(0xFFE8EAED.toInt(), graphite.centerIconFg)
        assertEquals(0xFF3C4043.toInt(), graphite.globeCircleBg)
        assertEquals(0x1FFFFFFF.toInt(), graphite.globeCircleBorder)
        assertEquals(0xFFE8EAED.toInt(), graphite.globeIconFg)

        val classicDark = SecondaryToolbarSpec.resolvePalette(isNightMode = true, isClassicTheme = true)
        assertEquals(0xFF151C18.toInt(), classicDark.toolbarBg)
        assertNotEquals(graphite.toolbarBg, classicDark.toolbarBg)

        val classicLight = SecondaryToolbarSpec.resolvePalette(isNightMode = false, isClassicTheme = true)
        assertNotEquals(graphite.backCircleBg, classicLight.backCircleBg)
        assertNotEquals(graphite.globeCircleBg, classicLight.globeCircleBg)
        assertNotEquals(classicLight.centerIconFg, classicLight.toolbarBg)

        val neutralLight = SecondaryToolbarSpec.resolvePalette(isNightMode = false, isClassicTheme = false)
        assertEquals(0xFFE8EAED.toInt(), neutralLight.toolbarBg)
        assertEquals(0xFF202124.toInt(), neutralLight.backCircleBg)
        assertEquals(0xFFFFFFFF.toInt(), neutralLight.backIconFg)
        assertEquals(0xFF202124.toInt(), neutralLight.centerIconFg)
        assertNotEquals(neutralLight.toolbarBg, neutralLight.centerIconFg)
    }
}
