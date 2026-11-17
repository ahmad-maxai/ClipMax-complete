package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class TypingIntelligenceEngineTest {

    private lateinit var engine: TypingIntelligenceEngine

    @Before
    fun setUp() {
        val candidates = listOf(
            File("src/main/res/raw/en_words.txt"),
            File("app/src/main/res/raw/en_words.txt")
        )
        val dictFile = candidates.firstOrNull { it.isFile }
        engine = if (dictFile != null) {
            dictFile.inputStream().use { TypingIntelligenceEngine.fromStream(it) }
        } else {
            TypingIntelligenceEngine.createFallback()
        }
    }

    @Test
    fun testCommonMisspellingsAutocorrectAndPreserveCasing() {
        val recieved = engine.analyzeWord("recieved", "recieved", isSentenceStart = false, autocompleteEnabled = true, autoCorrectEnabled = true)
        assertEquals("received", recieved.autoCorrection)
        assertTrue(recieved.suggestions.contains("received"))

        val mesage = engine.analyzeWord("mesage", "mesage", isSentenceStart = false, autocompleteEnabled = true, autoCorrectEnabled = true)
        assertEquals("message", mesage.autoCorrection)
        assertTrue(mesage.suggestions.contains("message"))

        val teh = engine.analyzeWord("teh", "teh", isSentenceStart = false, autocompleteEnabled = true, autoCorrectEnabled = true)
        assertEquals("the", teh.autoCorrection)

        val seperate = engine.analyzeWord("seperate", "seperate", isSentenceStart = false, autocompleteEnabled = true, autoCorrectEnabled = true)
        assertEquals("separate", seperate.autoCorrection)

        val sentenceCap = engine.analyzeWord("Recieved", "Recieved", isSentenceStart = true, autocompleteEnabled = true, autoCorrectEnabled = true)
        assertEquals("Received", sentenceCap.autoCorrection)

        val sentenceThe = engine.analyzeWord("Teh", "Teh", isSentenceStart = true, autocompleteEnabled = true, autoCorrectEnabled = true)
        assertEquals("The", sentenceThe.autoCorrection)

        // All-caps preservation helper check (while live autocorrect safely protects ALL-CAPS tokens/acronyms)
        assertEquals("THE", TypingIntelligenceEngine.applyWordCasing("TEH", "the"))
        assertNull(engine.analyzeWord("TEH", "TEH", isSentenceStart = false, autocompleteEnabled = true, autoCorrectEnabled = true).autoCorrection)
    }

    @Test
    fun testValidWordsNamesAcronymsUrlsAndCodeNeverAutocorrect() {
        val valid = engine.analyzeWord("received", "received", isSentenceStart = false, autocompleteEnabled = true, autoCorrectEnabled = true)
        assertTrue(valid.isKnownWord)
        assertNull(valid.autoCorrection)

        val midSentenceName = engine.analyzeWord("Ahmad", "Ahmad", isSentenceStart = false, autocompleteEnabled = true, autoCorrectEnabled = true)
        assertNull(midSentenceName.autoCorrection)

        val camelCase = engine.analyzeWord("ClipMax", "ClipMax", isSentenceStart = false, autocompleteEnabled = true, autoCorrectEnabled = true)
        assertNull(camelCase.autoCorrection)

        val acronym = engine.analyzeWord("API", "API", isSentenceStart = false, autocompleteEnabled = true, autoCorrectEnabled = true)
        assertNull(acronym.autoCorrection)

        val urlToken = engine.analyzeWord("cm", "https://example.cm", isSentenceStart = false, autocompleteEnabled = true, autoCorrectEnabled = true)
        assertNull(urlToken.autoCorrection)

        val emailToken = engine.analyzeWord("gmial", "user@gmial", isSentenceStart = false, autocompleteEnabled = true, autoCorrectEnabled = true)
        assertNull(emailToken.autoCorrection)

        val snakeCase = engine.analyzeWord("mesage", "my_mesage", isSentenceStart = false, autocompleteEnabled = true, autoCorrectEnabled = true)
        assertNull(snakeCase.autoCorrection)
    }

    @Test
    fun testPrefixCompletionsAndContextualNextWords() {
        val prefix = engine.analyzeWord("tomor", "tomor", isSentenceStart = false, autocompleteEnabled = true, autoCorrectEnabled = true)
        assertTrue(prefix.suggestions.contains("tomorrow"))
        assertNull(prefix.autoCorrection)

        val nextAfterThank = engine.nextWordSuggestions("thank", autocompleteEnabled = true)
        assertTrue(nextAfterThank.contains("you"))

        val nextDisabled = engine.nextWordSuggestions("thank", autocompleteEnabled = false)
        assertTrue(nextDisabled.isEmpty())
    }

    @Test
    fun testIndependentSettingsAndSuppressedUndoLoopPrevention() {
        // Autocorrect OFF still provides manual suggestion while suppressing automatic replacement
        val manualOnly = engine.analyzeWord("recieved", "recieved", isSentenceStart = false, autocompleteEnabled = true, autoCorrectEnabled = false)
        assertNull(manualOnly.autoCorrection)
        assertTrue(manualOnly.suggestions.contains("received"))

        // Suppressed word after undo does not auto-correct again in a loop
        val suppressed = engine.analyzeWord(
            "recieved",
            "recieved",
            isSentenceStart = false,
            autocompleteEnabled = true,
            autoCorrectEnabled = true,
            suppressedWordsLower = setOf("recieved")
        )
        assertNull(suppressed.autoCorrection)

        // Autocomplete OFF still allows autocorrect without prefix spam
        val correctOnly = engine.analyzeWord("tomor", "tomor", isSentenceStart = false, autocompleteEnabled = false, autoCorrectEnabled = true)
        assertFalse(correctOnly.suggestions.contains("tomorrow"))
    }
}
