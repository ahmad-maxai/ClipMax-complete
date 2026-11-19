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
        for ((theme, night) in listOf("dark" to true, "light" to false, "system" to false, "classic" to true, "classic" to false)) {
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
        assertEquals(46f, OverlayUiSpec.CONTEXT_MENU_TARGET_ROW_HEIGHT_DP, 0.001f)
        assertTrue(OverlayUiSpec.CONTEXT_MENU_PADDING_H_DP >= 14f)
        assertTrue(OverlayUiSpec.CONTEXT_MENU_ICON_GAP_DP >= 10f)
        assertTrue(OverlayUiSpec.CONTEXT_MENU_TEXT_SP >= 13.5f)
        assertEquals(6, OverlayUiSpec.CONTEXT_MENU_ACTION_COUNT)

        // Width scales proportionally and stays within comfortable bounds for English & Arabic
        val portraitWidth = OverlayUiSpec.resolveContextMenuPanelWidthDp(360f, isArabic = false)
        assertEquals(187.2f, portraitWidth, 0.5f)
        val portraitArabicWidth = OverlayUiSpec.resolveContextMenuPanelWidthDp(360f, isArabic = true)
        assertEquals(201.6f, portraitArabicWidth, 0.5f)
        val smallWidth = OverlayUiSpec.resolveContextMenuPanelWidthDp(260f, isArabic = false)
        assertEquals(OverlayUiSpec.CONTEXT_MENU_MIN_WIDTH_DP, smallWidth, 0.001f)
        val wideWidth = OverlayUiSpec.resolveContextMenuPanelWidthDp(720f, isArabic = false)
        assertEquals(OverlayUiSpec.CONTEXT_MENU_MAX_WIDTH_DP, wideWidth, 0.001f)
        val wideArabicWidth = OverlayUiSpec.resolveContextMenuPanelWidthDp(720f, isArabic = true)
        assertEquals(OverlayUiSpec.CONTEXT_MENU_ARABIC_MAX_WIDTH_DP, wideArabicWidth, 0.001f)

        // All 6 options fit inside compact, normal, tall, and landscape IME viewports without scrolling
        for (surfaceHeightDp in listOf(228f, 255f, 272f, 180f)) {
            for (isArabic in listOf(false, true)) {
                val layout = OverlayUiSpec.resolveContextMenuLayout(
                    surfaceWidthDp = 360f,
                    surfaceHeightDp = surfaceHeightDp,
                    isArabic = isArabic
                )
                assertTrue("Expected non-scrolling fit at height $surfaceHeightDp (ar=$isArabic)", layout.fitsWithoutScrolling)
                assertTrue(layout.totalHeightDp <= layout.maxMenuHeightDp + 0.5f)
                assertTrue(layout.rowHeightDp >= 22f)
            }
        }
        val tallLayout = OverlayUiSpec.resolveContextMenuLayout(360f, 300f, isArabic = false)
        assertTrue(tallLayout.rowHeightDp in 44f..48f)
    }

    @Test
    fun testContextMenuUsesHarmonizedSurfaceInGraphiteAndPreservesClassic() {
        val graphite = KeyboardLayoutSpec.resolveThemePalette("graphite", systemNightMode = true)
        assertEquals(0xFF303134.toInt(), OverlayUiSpec.resolveContextMenuBg(graphite))

        val classicDark = KeyboardLayoutSpec.resolveThemePalette("classic", systemNightMode = true)
        assertEquals(classicDark.colorKeyBg, OverlayUiSpec.resolveContextMenuBg(classicDark))

        val classicLight = KeyboardLayoutSpec.resolveThemePalette("classic", systemNightMode = false)
        assertEquals(classicLight.colorKeyBg, OverlayUiSpec.resolveContextMenuBg(classicLight))

        val neutralLight = KeyboardLayoutSpec.resolveThemePalette("light", systemNightMode = false)
        assertEquals(0xFFFFFFFF.toInt(), OverlayUiSpec.resolveContextMenuBg(neutralLight))
    }
}
