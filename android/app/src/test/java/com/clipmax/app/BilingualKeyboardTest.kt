package com.clipmax.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class BilingualKeyboardTest {

    private lateinit var tempDir: File
    private lateinit var store: ClipStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("clipmax_bilingual_test").toFile()
        store = ClipStore(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testLanguagePreferenceNormalizationAndUiSpecs() {
        assertEquals("keyboard_language", SettingsPanelSpec.PREF_KEYBOARD_LANGUAGE)
        assertEquals("en", SettingsPanelSpec.LANG_ENGLISH)
        assertEquals("ar", SettingsPanelSpec.LANG_ARABIC)
        assertEquals(
            listOf("en" to "English (QWERTY)", "ar" to "العربية (Arabic)"),
            SettingsPanelSpec.LANGUAGE_OPTIONS.map { it.value to it.label }
        )
        assertEquals(
            listOf("en" to "English", "ar" to "العربية"),
            KeyboardLayoutSpec.LANGUAGE_POPUP_OPTIONS.map { it.code to it.primaryLabel }
        )

        // Default and fallback safety -> English
        assertEquals("en", SettingsPanelSpec.normalizeLanguageValue(null))
        assertEquals("en", SettingsPanelSpec.normalizeLanguageValue(""))
        assertEquals("en", SettingsPanelSpec.normalizeLanguageValue("en"))
        assertEquals("en", SettingsPanelSpec.normalizeLanguageValue("en-US"))
        assertEquals("en", SettingsPanelSpec.normalizeLanguageValue("unknown"))

        // Arabic locale and label normalization -> Arabic
        assertEquals("ar", SettingsPanelSpec.normalizeLanguageValue("ar"))
        assertEquals("ar", SettingsPanelSpec.normalizeLanguageValue("AR"))
        assertEquals("ar", SettingsPanelSpec.normalizeLanguageValue("ar-EG"))
        assertEquals("ar", SettingsPanelSpec.normalizeLanguageValue("ar_SA"))
        assertEquals("ar", SettingsPanelSpec.normalizeLanguageValue("arabic"))
        assertEquals("ar", SettingsPanelSpec.normalizeLanguageValue("العربية"))

        val enUi = KeyboardLayoutSpec.languageUiSpec("en")
        assertEquals("English", enUi.spacebarLabel)
        assertEquals("ABC", enUi.alphaToggleLabel)
        assertEquals("?123", enUi.symbolsToggleLabel)
        assertEquals(",", enUi.alphaCommaChar)
        assertFalse(enUi.textDirectionRtl)

        val arUi = KeyboardLayoutSpec.languageUiSpec("ar")
        assertEquals("العربية", arUi.spacebarLabel)
        assertEquals("أبج", arUi.alphaToggleLabel)
        assertEquals("؟٣٢١", arUi.symbolsToggleLabel)
        assertEquals("\u060C", arUi.alphaCommaChar)
        assertTrue(arUi.textDirectionRtl)
    }

    @Test
    fun testArabicStandardLetterLayoutAndDigitHints() {
        assertEquals(
            listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"),
            KeyboardLayoutSpec.ARABIC_ALPHA_ROW1.map { it.primary }
        )
        assertEquals(
            listOf("١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", null, null),
            KeyboardLayoutSpec.ARABIC_ALPHA_ROW1.map { it.hintDigit }
        )
        assertEquals(
            listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"),
            KeyboardLayoutSpec.ARABIC_ALPHA_ROW2
        )
        assertEquals(
            listOf("ذ", "ء", "ؤ", "ر", "ى", "ة", "و", "ز", "ظ", "د"),
            KeyboardLayoutSpec.ARABIC_ALPHA_ROW3
        )
        assertEquals(32, KeyboardLayoutSpec.ARABIC_ALPHA_ROW1.size + KeyboardLayoutSpec.ARABIC_ALPHA_ROW2.size + KeyboardLayoutSpec.ARABIC_ALPHA_ROW3.size)
    }

    @Test
    fun testArabicNumeralsAndSymbolsPreserveEasternAndWesternAccess() {
        assertEquals(listOf("١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", "٠"), KeyboardLayoutSpec.ARABIC_SYMBOLS_ROW1)
        assertTrue(KeyboardLayoutSpec.ARABIC_SYMBOLS_ROW3.contains("\u061B")) // Arabic semicolon ؛
        assertTrue(KeyboardLayoutSpec.ARABIC_SYMBOLS_ROW3.contains("\u061F")) // Arabic question mark ؟
        assertTrue(KeyboardLayoutSpec.ARABIC_SYMBOLS_EXT_ROW1.contains("\u066A")) // Arabic percent ٪
        assertTrue(KeyboardLayoutSpec.ARABIC_SYMBOLS_EXT_ROW1.contains("\uFDFC")) // Rial sign ﷼
        assertTrue(KeyboardLayoutSpec.ARABIC_SYMBOLS_EXT_ROW3.containsAll(listOf("«", "»", "\u066B", "\u066C")))

        // Cross-numeral alternatives preserve access to Western 0-9 from Arabic-Indic ١-٠ and vice versa
        assertEquals(listOf("1"), KeyboardLayoutSpec.arabicVariantsFor("١"))
        assertEquals(listOf("0"), KeyboardLayoutSpec.arabicVariantsFor("٠"))
        assertEquals(listOf("١"), KeyboardLayoutSpec.arabicVariantsFor("1"))
        assertEquals(listOf("٠"), KeyboardLayoutSpec.arabicVariantsFor("0"))
    }

    @Test
    fun testArabicLongPressVariantsDiacriticsAndCombiningBackspace() {
        // Required letter variants: ا, ه, ي, و, ة + companions
        assertTrue(KeyboardLayoutSpec.arabicVariantsFor("ا").containsAll(listOf("أ", "إ", "آ", "ٱ", "ء")))
        assertTrue(KeyboardLayoutSpec.arabicVariantsFor("ه").contains("ة"))
        assertTrue(KeyboardLayoutSpec.arabicVariantsFor("ي").containsAll(listOf("ى", "ئ")))
        assertTrue(KeyboardLayoutSpec.arabicVariantsFor("و").contains("ؤ"))
        assertTrue(KeyboardLayoutSpec.arabicVariantsFor("ة").containsAll(listOf("ه", "ت")))

        // Period long-press exposes Arabic vowel diacritics, shaddah, sukun, tanwin, tatweel, and Arabic punctuation
        val periodVariants = KeyboardLayoutSpec.arabicVariantsFor(".")
        assertTrue(periodVariants.containsAll(listOf("\u064E", "\u064F", "\u0650", "\u0651", "\u0652", "\u064B", "\u064C", "\u064D", "\u0640", "\u061F", "\u060C", "\u061B")))

        // Combining marks render with dotted circle placeholder for legibility
        assertEquals("\u25CC\u064E", KeyboardLayoutSpec.displayLabelForArabicVariant("\u064E"))
        assertEquals("\u25CC\u0651", KeyboardLayoutSpec.displayLabelForArabicVariant("\u0651"))
        assertEquals("\u061F", KeyboardLayoutSpec.displayLabelForArabicVariant("\u061F"))

        // Backspace deletes trailing combining diacritic cleanly without erasing its base letter
        assertEquals(1, KeyboardLayoutSpec.backspaceUtf16Count("مَ", "ar"))
        assertEquals(1, KeyboardLayoutSpec.backspaceUtf16Count("شّ", "ar"))
        assertEquals(1, KeyboardLayoutSpec.backspaceUtf16Count("سلام", "ar"))
        assertEquals(2, KeyboardLayoutSpec.backspaceUtf16Count("أهلاً 😀", "ar"))
    }

    @Test
    fun testEnglishTypingEngineDoesNotCorruptOrAutocorrectArabicTokens() {
        val engine = TypingIntelligenceEngine.createFallback()
        assertTrue(TypingIntelligenceEngine.containsArabicScript("مرحبا"))
        assertTrue(TypingIntelligenceEngine.containsArabicScript("أهلاً بكم"))
        assertFalse(TypingIntelligenceEngine.containsArabicScript("received"))

        val arabicAnalysis = engine.analyzeWord(
            word = "مرحبا",
            rawToken = "مرحبا",
            isSentenceStart = false,
            autocompleteEnabled = true,
            autoCorrectEnabled = true
        )
        assertNull(arabicAnalysis.autoCorrection)
        assertTrue(arabicAnalysis.suggestions.isEmpty())

        assertTrue(engine.nextWordSuggestions("شكرا", autocompleteEnabled = true).isEmpty())

        // English autocorrection still behaves identically
        val englishTypo = engine.analyzeWord(
            word = "recieved",
            rawToken = "recieved",
            isSentenceStart = false,
            autocompleteEnabled = true,
            autoCorrectEnabled = true
        )
        assertEquals("received", englishTypo.autoCorrection)
    }

    @Test
    fun testLanguageSwitchingKeepsSavedClipsCompletelyIsolated() {
        val t0 = 10_000_000L
        val arabicText = "مرحباً بكم في كليب ماكس — نص تجريبي مع التشكيل: أَهْلاً بِكُمْ!"
        val englishText = "Quick deployment checklist: verify release APK cleanly."

        val arPinned = store.save(
            id = null,
            title = "رسالة ترحيب",
            content = arabicText,
            pinned = true,
            savedAt = t0,
            isExpiryLocked = true
        )
        val enRegular = store.save(
            id = null,
            title = "Checklist",
            content = englishText,
            pinned = false,
            savedAt = t0 + 1000L,
            isExpiryLocked = false
        )

        val beforeIds = store.listItems().map { it.id }

        // Simulate switching between English and Arabic keyboard preferences and layout modes
        var activeLang = SettingsPanelSpec.normalizeLanguageValue("en")
        assertEquals("en", activeLang)
        activeLang = SettingsPanelSpec.normalizeLanguageValue("ar")
        assertEquals("ar", activeLang)
        val modeCtrl = KeyboardLayoutSpec.KeyboardModeController()
        modeCtrl.toSymbols()
        modeCtrl.toSymbolsExt()
        modeCtrl.toNumpad()
        modeCtrl.toAlpha()
        activeLang = SettingsPanelSpec.normalizeLanguageValue("en")
        assertEquals("en", activeLang)

        // Clips, IDs, Unicode contents, pinned flags, and expiry locks must be byte-for-byte identical
        val afterItems = store.listItems()
        assertEquals(beforeIds, afterItems.map { it.id })
        val fetchedAr = store.getItem(arPinned.id)!!
        val fetchedEn = store.getItem(enRegular.id)!!
        assertEquals("رسالة ترحيب", fetchedAr.title)
        assertEquals(arabicText, store.readContent(fetchedAr))
        assertTrue(fetchedAr.pinned)
        assertTrue(fetchedAr.isExpiryLocked)
        assertEquals("Checklist", fetchedEn.title)
        assertEquals(englishText, store.readContent(fetchedEn))
        assertFalse(fetchedEn.pinned)
        assertFalse(fetchedEn.isExpiryLocked)

        // Coexistence search works for both scripts regardless of active language
        assertEquals(1, store.search("كليب").size)
        assertEquals(1, store.search("checklist").size)

        // Backup and restore preserves both English and Arabic clips faithfully
        val backupBytes = ByteArrayOutputStream().also { store.backup(it) }.toByteArray()
        val restoreDir = Files.createTempDirectory("clipmax_restore_test").toFile()
        try {
            val restoredStore = ClipStore(restoreDir)
            val restoredCount = restoredStore.restore(ByteArrayInputStream(backupBytes))
            assertEquals(2, restoredCount)
            assertEquals(arabicText, restoredStore.readContent(restoredStore.getItem(arPinned.id)!!))
            assertEquals(englishText, restoredStore.readContent(restoredStore.getItem(enRegular.id)!!))
        } finally {
            restoreDir.deleteRecursively()
        }
    }
}
