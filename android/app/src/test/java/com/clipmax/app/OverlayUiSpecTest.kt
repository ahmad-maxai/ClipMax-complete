package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayUiSpecTest {

    @Test
    fun testSuggestionPillsFitCanonicalToolbarWithComfortableProportions() {
        assertEquals(36f, OverlayUiSpec.SUGGESTION_PILL_HEIGHT_DP, 0.001f)
        assertEquals(
            KeyboardLayoutSpec.TOOLBAR_HEIGHT_DP,
            OverlayUiSpec.SUGGESTION_PILL_HEIGHT_DP + OverlayUiSpec.TOOLBAR_VERTICAL_PADDING_DP * 2f,
            0.001f
        )
        assertTrue(OverlayUiSpec.SUGGESTION_PILL_PADDING_H_DP >= 14f)
        assertTrue(OverlayUiSpec.SUGGESTION_PILL_TEXT_SP in 13f..14f)
        assertTrue(OverlayUiSpec.SUGGESTION_PILL_MAX_WIDTH_DP >= 200f)
    }

    @Test
    fun testSuggestionPillPrimaryAndSecondaryDistinctionAcrossThemes() {
        for ((theme, night) in listOf("graphite" to true, "classic" to true, "classic" to false)) {
            val palette = KeyboardLayoutSpec.resolveThemePalette(theme, night)
            val primary = OverlayUiSpec.resolveSuggestionPillStyle(palette, isPrimary = true, isRevert = false)
            val secondary = OverlayUiSpec.resolveSuggestionPillStyle(palette, isPrimary = false, isRevert = false)
            val revert = OverlayUiSpec.resolveSuggestionPillStyle(palette, isPrimary = false, isRevert = true)

            assertNotEquals(primary.bgColor, secondary.bgColor)
            assertNotEquals(primary.textColor, primary.bgColor)
            assertNotEquals(secondary.textColor, secondary.bgColor)
            assertNotEquals(revert.textColor, secondary.bgColor)
            assertTrue(primary.isMediumOrBold)
            assertTrue(secondary.isMediumOrBold)
        }
    }

    @Test
    fun testContextMenuErgonomicDimensionsAndAdaptiveClamping() {
        assertEquals(48f, OverlayUiSpec.CONTEXT_MENU_TARGET_ROW_HEIGHT_DP, 0.001f)
        assertTrue(OverlayUiSpec.CONTEXT_MENU_PADDING_H_DP >= 14f)
        assertTrue(OverlayUiSpec.CONTEXT_MENU_ICON_GAP_DP >= 10f)
        assertTrue(OverlayUiSpec.CONTEXT_MENU_TEXT_SP >= 13.5f)
        assertEquals(5, OverlayUiSpec.CONTEXT_MENU_ACTION_COUNT)

        // Width scales proportionally and stays within comfortable bounds
        val portraitWidth = OverlayUiSpec.resolveContextMenuPanelWidthDp(360f)
        assertEquals(187.2f, portraitWidth, 0.5f)
        val smallWidth = OverlayUiSpec.resolveContextMenuPanelWidthDp(260f)
        assertEquals(OverlayUiSpec.CONTEXT_MENU_MIN_WIDTH_DP, smallWidth, 0.001f)
        val wideWidth = OverlayUiSpec.resolveContextMenuPanelWidthDp(720f)
        assertEquals(OverlayUiSpec.CONTEXT_MENU_MAX_WIDTH_DP, wideWidth, 0.001f)

        // Height fits all 5 x 48dp rows in tall portrait without clipping & safely caps on short landscape
        val tallPortraitMaxH = OverlayUiSpec.resolveContextMenuMaxHeightDp(260f)
        assertTrue(tallPortraitMaxH >= 48f * 5f + 8f)
        val shortLandscapeMaxH = OverlayUiSpec.resolveContextMenuMaxHeightDp(170f)
        assertEquals(162f, shortLandscapeMaxH, 0.001f)
    }

    @Test
    fun testContextMenuUsesHarmonizedSurfaceInGraphiteAndPreservesClassic() {
        val graphite = KeyboardLayoutSpec.resolveThemePalette("graphite", systemNightMode = true)
        assertEquals(0xFF303134.toInt(), OverlayUiSpec.resolveContextMenuBg(graphite))

        val classicDark = KeyboardLayoutSpec.resolveThemePalette("classic", systemNightMode = true)
        assertEquals(classicDark.colorKeyBg, OverlayUiSpec.resolveContextMenuBg(classicDark))

        val classicLight = KeyboardLayoutSpec.resolveThemePalette("classic", systemNightMode = false)
        assertEquals(classicLight.colorKeyBg, OverlayUiSpec.resolveContextMenuBg(classicLight))
    }
}
