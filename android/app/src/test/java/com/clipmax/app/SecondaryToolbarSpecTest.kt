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
    fun testToolbarHeightMatchesCanonicalViewportWithoutExpansion() {
        assertEquals(38f, SecondaryToolbarSpec.TOOLBAR_HEIGHT_DP, 0.001f)
        assertEquals(KeyboardLayoutSpec.TOOLBAR_HEIGHT_DP, SecondaryToolbarSpec.TOOLBAR_HEIGHT_DP, 0.001f)

        val portrait = KeyboardLayoutSpec.computeViewportSpecDp("normal", 850f, isLandscape = false)
        assertEquals(38f, portrait.toolbarHeightDp, 0.001f)
        assertEquals(portrait.toolbarHeightDp + portrait.mainPanelHeightDp, portrait.totalContentHeightDp, 0.001f)
    }

    @Test
    fun testIconScaleAndLabelTypographyMatchReferenceProportions() {
        assertFalse(SecondaryToolbarSpec.LABEL_BOLD)
        assertTrue(SecondaryToolbarSpec.CENTER_ICON_SIZE_DP >= 17f)
        assertTrue(SecondaryToolbarSpec.GLOBE_ICON_SIZE_DP >= 17.5f)
        assertEquals(
            SecondaryToolbarSpec.END_BUTTON_SIZE_DP / 2f,
            SecondaryToolbarSpec.END_BUTTON_CORNER_DP,
            0.001f
        )
    }

    @Test
    fun testDarkAndLightToolbarPalettesHaveCleanContrastAndMutedLabels() {
        val dark = SecondaryToolbarSpec.resolvePalette(isNightMode = true)
        assertEquals(0xFFFFFFFF.toInt(), dark.backCircleBg)
        assertEquals(0xFF111111.toInt(), dark.backIconFg)
        assertNotEquals(dark.centerIconFg, dark.centerLabelFg)

        val light = SecondaryToolbarSpec.resolvePalette(isNightMode = false)
        assertNotEquals(dark.backCircleBg, light.backCircleBg)
        assertNotEquals(dark.globeCircleBg, light.globeCircleBg)
        assertNotEquals(light.centerIconFg, light.centerLabelFg)
    }
}
