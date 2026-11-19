package com.clipmax.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ArabicUiLocalizationAndTranslatorTest {

    private lateinit var tempDir: File
    private lateinit var store: ClipStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("clipmax_locale_isolation_test").toFile()
        store = ClipStore(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testAppUiLocaleNormalizationDefaultsToEnglishAndSupportsArabicVariants() {
        assertEquals(SettingsPanelSpec.UI_LOCALE_ENGLISH, SettingsPanelSpec.normalizeAppUiLocale(null))
        assertEquals(SettingsPanelSpec.UI_LOCALE_ENGLISH, SettingsPanelSpec.normalizeAppUiLocale(""))
        assertEquals(SettingsPanelSpec.UI_LOCALE_ENGLISH, SettingsPanelSpec.normalizeAppUiLocale("fr"))
        assertEquals(SettingsPanelSpec.UI_LOCALE_ENGLISH, SettingsPanelSpec.normalizeAppUiLocale("en"))
        assertEquals(SettingsPanelSpec.UI_LOCALE_ARABIC, SettingsPanelSpec.normalizeAppUiLocale("ar"))
        assertEquals(SettingsPanelSpec.UI_LOCALE_ARABIC, SettingsPanelSpec.normalizeAppUiLocale("ar-EG"))
        assertEquals(SettingsPanelSpec.UI_LOCALE_ARABIC, SettingsPanelSpec.normalizeAppUiLocale("ar_SA"))
        assertEquals(SettingsPanelSpec.UI_LOCALE_ARABIC, SettingsPanelSpec.normalizeAppUiLocale("Arabic"))
        assertEquals(SettingsPanelSpec.UI_LOCALE_ARABIC, SettingsPanelSpec.normalizeAppUiLocale("العربية"))
    }

    @Test
    fun testAppUiLocaleAndKeyboardLanguageUseSeparatePreferencesAndToggleIndependently() {
        assertNotEquals(SettingsPanelSpec.PREF_APP_UI_LOCALE, SettingsPanelSpec.PREF_KEYBOARD_LANGUAGE)
        assertEquals("en", SettingsPanelSpec.UI_LOCALE_ENGLISH)
        assertEquals("ar", SettingsPanelSpec.UI_LOCALE_ARABIC)

        // Switching UI locale toggles only appUiLocale while keyboardLanguage can independently stay English or Arabic
        var appUiLocale = SettingsPanelSpec.UI_LOCALE_ENGLISH
        var keyboardLang = SettingsPanelSpec.LANG_ENGLISH

        appUiLocale = AppUiStrings.toggleLocale(appUiLocale)
        assertEquals(SettingsPanelSpec.UI_LOCALE_ARABIC, appUiLocale)
        assertEquals(SettingsPanelSpec.LANG_ENGLISH, keyboardLang)

        keyboardLang = SettingsPanelSpec.LANG_ARABIC
        appUiLocale = AppUiStrings.toggleLocale(appUiLocale)
        assertEquals(SettingsPanelSpec.UI_LOCALE_ENGLISH, appUiLocale)
        assertEquals(SettingsPanelSpec.LANG_ARABIC, keyboardLang)
    }

    @Test
    fun testCompleteArabicStringsRtlFlagsAndSixContextMenuActions() {
        assertFalse(AppUiStrings.isRtl("en"))
        assertTrue(AppUiStrings.isRtl("ar"))

        assertEquals("Clipboard", AppUiStrings.clipboardTitle("en"))
        assertEquals("الحافظة", AppUiStrings.clipboardTitle("ar"))
        assertEquals("Pinned Clips", AppUiStrings.pinnedClipsTitle("en"))
        assertEquals("العناصر المثبتة", AppUiStrings.pinnedClipsTitle("ar"))
        assertEquals("Settings", AppUiStrings.settingsA11y("en"))
        assertEquals("الإعدادات", AppUiStrings.settingsA11y("ar"))
        assertEquals("ClipMax Settings", AppUiStrings.settingsHeaderTitle("en"))
        assertEquals("إعدادات ClipMax", AppUiStrings.settingsHeaderTitle("ar"))

        // Six ClipCard context menu labels present in both languages
        val enActions = listOf(
            AppUiStrings.actionPin("en", false),
            AppUiStrings.actionLockExpiry("en", false),
            AppUiStrings.actionEdit("en"),
            AppUiStrings.actionDelete("en"),
            AppUiStrings.actionMerge("en"),
            AppUiStrings.actionSplit("en")
        )
        val arActions = listOf(
            AppUiStrings.actionPin("ar", false),
            AppUiStrings.actionLockExpiry("ar", false),
            AppUiStrings.actionEdit("ar"),
            AppUiStrings.actionDelete("ar"),
            AppUiStrings.actionMerge("ar"),
            AppUiStrings.actionSplit("ar")
        )
        assertEquals(listOf("Pin", "Lock expiry", "Edit", "Delete", "Merge", "Split"), enActions)
        assertTrue(arActions.all { it.isNotBlank() })
        assertEquals(6, arActions.distinct().size)

        // All EmojiCatalog categories have non-empty Arabic labels
        for (cat in EmojiCatalog.CATEGORIES) {
            val arLabel = AppUiStrings.emojiCategoryLabel("ar", cat)
            assertTrue(arLabel.isNotBlank())
            assertNotEquals(cat.label, arLabel)
        }

        // Retention mode labels
        assertEquals("٢٤ ساعة", AppUiStrings.localizedRetentionModeLabel("ar", "24h"))
        assertEquals("إيقاف", AppUiStrings.localizedRetentionModeLabel("ar", "off"))
        assertEquals("للأبد", AppUiStrings.localizedRetentionModeLabel("ar", "forever"))
    }

    @Test
    fun testClipStoreRemainsCompletelyIsolatedAcrossUiLocaleSwitches() {
        val mixedUrlPayload = "https://example.com/path?token=ABC_123&lang=ar#section"
        val arabicPayload = "مرحباً بكم في كليب ماكس — نص عربي مع رابط https://clipmax.app"
        val englishPayload = "Quarterly roadmap review — action items & notes"

        val c1 = store.save(id = null, title = "URL Clip", content = mixedUrlPayload, pinned = true, isExpiryLocked = true)
        val c2 = store.save(id = null, title = "ملاحظة عربية", content = arabicPayload, pinned = false, isExpiryLocked = false)
        val c3 = store.save(id = null, title = "English Note", content = englishPayload, pinned = false, isExpiryLocked = true)

        val beforeItems = store.listItems().associateBy { it.id }

        // Simulate toggling appUiLocale repeatedly between en <-> ar
        var locale = SettingsPanelSpec.UI_LOCALE_ENGLISH
        repeat(6) {
            locale = AppUiStrings.toggleLocale(locale)
            // Only static UI labels are derived from locale
            assertTrue(AppUiStrings.clipboardTitle(locale).isNotBlank())
        }

        val afterItems = store.listItems().associateBy { it.id }
        assertEquals(beforeItems.keys, afterItems.keys)

        for (id in beforeItems.keys) {
            val orig = beforeItems[id]!!
            val curr = afterItems[id]!!
            assertEquals(orig.id, curr.id)
            assertEquals(orig.title, curr.title)
            assertEquals(orig.pinned, curr.pinned)
            assertEquals(orig.isExpiryLocked, curr.isExpiryLocked)
            assertEquals(orig.createdAt, curr.createdAt)
            assertEquals(orig.characterCount, curr.characterCount)
            assertEquals(orig.sizeBytes, curr.sizeBytes)
            assertEquals(store.readContent(orig), store.readContent(curr))
        }
        assertEquals(mixedUrlPayload, store.readContent(afterItems[c1.id]!!))
        assertEquals(arabicPayload, store.readContent(afterItems[c2.id]!!))
        assertEquals(englishPayload, store.readContent(afterItems[c3.id]!!))
    }

    @Test
    fun testFloatingTranslatorButtonBoundariesDraggingClampingAndTapToggle() {
        // Strict app-only containment flags (no system overlay, no AccessibilityService)
        assertTrue(FloatingButtonSpec.RENDERED_IN_APP_ACTIVITY_ONLY)
        assertFalse(FloatingButtonSpec.USES_SYSTEM_OVERLAY)
        assertFalse(FloatingButtonSpec.USES_ACCESSIBILITY_SERVICE)

        // Button label mirrors target switch language ("عرب" in EN UI, "ENG" in AR UI)
        assertEquals("عرب", FloatingButtonSpec.buttonLabelForLocale("en"))
        assertEquals("ENG", FloatingButtonSpec.buttonLabelForLocale("ar"))
        assertEquals("ar", FloatingButtonSpec.toggleLocale("en"))
        assertEquals("en", FloatingButtonSpec.toggleLocale("ar"))

        // Viewport clamping & normalized fraction persistence across screen sizes
        val clampedNeg = FloatingButtonSpec.clampPosition(-50f, -80f, 1080f, 2200f, 174f, 24f)
        assertEquals(24f, clampedNeg.x, 0.01f)
        assertEquals(24f, clampedNeg.y, 0.01f)

        val clampedMax = FloatingButtonSpec.clampPosition(2500f, 3500f, 1080f, 2200f, 174f, 24f)
        assertEquals(1080f - 174f - 24f, clampedMax.x, 0.01f)
        assertEquals(2200f - 174f - 24f, clampedMax.y, 0.01f)

        val fractions = FloatingButtonSpec.toFractions(420f, 960f, 1080f, 2200f, 174f, 24f)
        val restored = FloatingButtonSpec.fromFractions(fractions.xFraction, fractions.yFraction, 1080f, 2200f, 174f, 24f)
        assertEquals(420f, restored.x, 0.5f)
        assertEquals(960f, restored.y, 0.5f)

        // Gesture state machine separates tap vs drag accurately
        val machine = FloatingButtonSpec.DragStateMachine(touchSlopPx = 24f)
        machine.onDown(rawX = 500f, rawY = 500f, viewX = 300f, viewY = 400f)
        machine.onMove(rawX = 506f, rawY = 508f, viewportWidth = 1080f, viewportHeight = 2200f, buttonSize = 174f, margin = 24f)
        val tapUp = machine.onUp(viewportWidth = 1080f, viewportHeight = 2200f, buttonSize = 174f, margin = 24f)
        assertTrue(tapUp.triggerTap)
        assertFalse(tapUp.persistPosition)

        machine.onDown(rawX = 500f, rawY = 500f, viewX = 300f, viewY = 400f)
        val draggedPos = machine.onMove(rawX = 680f, rawY = 720f, viewportWidth = 1080f, viewportHeight = 2200f, buttonSize = 174f, margin = 24f)
        assertEquals(480f, draggedPos.x, 0.5f)
        assertEquals(620f, draggedPos.y, 0.5f)
        val dragUp = machine.onUp(viewportWidth = 1080f, viewportHeight = 2200f, buttonSize = 174f, margin = 24f)
        assertFalse(dragUp.triggerTap)
        assertTrue(dragUp.persistPosition)
        assertEquals(480f, dragUp.finalPosition.x, 0.5f)
        assertEquals(620f, dragUp.finalPosition.y, 0.5f)

        // All theme palettes return distinct, non-zero visual styles
        for ((theme, night) in listOf("system" to false, "light" to false, "dark" to true, "classic" to false, "classic" to true)) {
            val style = FloatingButtonSpec.resolveStyle(KeyboardLayoutSpec.resolveThemePalette(theme, night))
            assertNotEquals(style.buttonBgColor, style.textColor)
            assertNotEquals(0, style.glowInnerColor)
        }
    }
}
