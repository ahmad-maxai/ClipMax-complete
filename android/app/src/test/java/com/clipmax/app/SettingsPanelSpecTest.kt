package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPanelSpecTest {

    @Test
    fun testOptionGroupsForBinaryAndThreeChoiceSettings() {
        assertEquals(listOf("system" to "System", "light" to "Light", "dark" to "Dark", "classic" to "Classic"),
            SettingsPanelSpec.THEME_OPTIONS.map { it.value to it.label })

        assertEquals(listOf("compact" to "Compact", "normal" to "Normal", "tall" to "Tall"),
            SettingsPanelSpec.HEIGHT_OPTIONS.map { it.value to it.label })

        assertEquals(listOf("left" to "Left", "off" to "Off", "right" to "Right"),
            SettingsPanelSpec.ONE_HANDED_OPTIONS.map { it.value to it.label })

        assertEquals(listOf(true to "On", false to "Off"),
            SettingsPanelSpec.BINARY_ON_OFF_OPTIONS.map { it.value to it.label })

        assertEquals(
            listOf("typing_autocomplete", "typing_autocorrect", "typing_suggestions"),
            SettingsPanelSpec.TYPING_SETTINGS.map { it.prefKey }
        )
    }

    @Test
    fun testGraphitePillCardPaletteUsesNeutralCharcoalActiveAndInactiveStates() {
        val imePalette = KeyboardLayoutSpec.resolveThemePalette("graphite", systemNightMode = true)
        val pillPalette = SettingsPanelSpec.resolvePillCardPalette(imePalette)

        assertFalse(pillPalette.isClassicTheme)
        assertEquals(0xFF202124.toInt(), pillPalette.panelBg)
        assertEquals(0xFF303134.toInt(), pillPalette.headerBg)
        assertEquals(0xFF303134.toInt(), pillPalette.cardBg)
        assertEquals(0xFF3C4043.toInt(), pillPalette.inactivePillBg)
        assertEquals(0xFF3C4043.toInt(), pillPalette.actionPillBg)
        assertEquals(0xFFE8EAED.toInt(), pillPalette.activePillFg)

        val active = SettingsPanelSpec.resolvePillVisualState(selected = true, palette = pillPalette)
        val inactive = SettingsPanelSpec.resolvePillVisualState(selected = false, palette = pillPalette)

        assertNotEquals(active.bgColor, inactive.bgColor)
        assertNotEquals(active.borderColor, inactive.borderColor)
        assertNotEquals(active.textColor, inactive.textColor)
        assertTrue(active.isBold)
        assertFalse(inactive.isBold)
        // Never harsh white background in Graphite active pills
        assertNotEquals(0xFFFFFFFF.toInt(), active.bgColor)
        // Micro-border emphasis on active pill must exceed inactive border alpha
        val activeBorderAlpha = (active.borderColor ushr 24) and 0xFF
        val inactiveBorderAlpha = (inactive.borderColor ushr 24) and 0xFF
        assertTrue(activeBorderAlpha > inactiveBorderAlpha)
    }

    @Test
    fun testClassicThemeSettingsPaletteRemainsPreserved() {
        val classicDark = SettingsPanelSpec.resolvePillCardPalette(
            KeyboardLayoutSpec.resolveThemePalette("classic", systemNightMode = true)
        )
        assertTrue(classicDark.isClassicTheme)
        assertTrue(classicDark.isNightMode)
        assertEquals(0xFF2DD4BF.toInt(), classicDark.activePillBg)
        assertEquals(0xFF151C18.toInt(), classicDark.panelBg)

        val classicLight = SettingsPanelSpec.resolvePillCardPalette(
            KeyboardLayoutSpec.resolveThemePalette("classic", systemNightMode = false)
        )
        assertTrue(classicLight.isClassicTheme)
        assertFalse(classicLight.isNightMode)
        assertEquals(0xFF0D9488.toInt(), classicLight.activePillBg)
        assertEquals(0xFFECEFF1.toInt(), classicLight.panelBg)

        val neutralLight = SettingsPanelSpec.resolvePillCardPalette(
            KeyboardLayoutSpec.resolveThemePalette("light", systemNightMode = true)
        )
        assertFalse(neutralLight.isClassicTheme)
        assertFalse(neutralLight.isNightMode)
        assertEquals(0xFFF1F3F4.toInt(), neutralLight.panelBg)
        assertEquals(0xFFFFFFFF.toInt(), neutralLight.cardBg)
        assertEquals(0xFF202124.toInt(), neutralLight.activePillBg)
        assertEquals(0xFFFFFFFF.toInt(), neutralLight.activePillFg)

        assertEquals("system", SettingsPanelSpec.normalizeThemeValue(null))
        assertEquals("system", SettingsPanelSpec.normalizeThemeValue(""))
        assertEquals("dark", SettingsPanelSpec.normalizeThemeValue("graphite"))
        assertEquals("light", SettingsPanelSpec.normalizeThemeValue("light"))
        assertEquals("classic", SettingsPanelSpec.normalizeThemeValue("classic"))
    }
}
