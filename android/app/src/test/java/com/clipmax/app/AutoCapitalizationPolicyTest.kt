package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCapitalizationPolicyTest {

    @Test
    fun testSpecializedAndPasswordFieldsDisableAutoCapitalization() {
        val disabledTypes = listOf(
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_PASSWORD,
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputFieldPolicy.TYPE_CLASS_NUMBER or InputFieldPolicy.TYPE_NUMBER_VARIATION_PASSWORD,
            InputFieldPolicy.TYPE_CLASS_NUMBER,
            InputFieldPolicy.TYPE_CLASS_PHONE,
            InputFieldPolicy.TYPE_CLASS_DATETIME,
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_URI,
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_FILTER,
            InputFieldPolicy.TYPE_CLASS_TEXT or AutoCapitalizationPolicy.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )

        for (inputType in disabledTypes) {
            val mode = AutoCapitalizationPolicy.resolveCapMode(inputType, isFilterMode = false)
            assertEquals("Expected NONE for inputType=$inputType", AutoCapitalizationPolicy.CapMode.NONE, mode)
            assertFalse(AutoCapitalizationPolicy.shouldAutoShift(mode, "", ""))
            assertFalse(AutoCapitalizationPolicy.shouldAutoShift(mode, "Hello. ", ""))
        }

        // Internal Search Clips filter bar also disables auto-cap
        assertEquals(
            AutoCapitalizationPolicy.CapMode.NONE,
            AutoCapitalizationPolicy.resolveCapMode(InputFieldPolicy.TYPE_CLASS_TEXT, isFilterMode = true)
        )
    }

    @Test
    fun testFreshFieldAndSentenceBoundariesAutoShiftProperly() {
        val mode = AutoCapitalizationPolicy.resolveCapMode(
            InputFieldPolicy.TYPE_CLASS_TEXT or AutoCapitalizationPolicy.TYPE_TEXT_FLAG_CAP_SENTENCES
        )
        assertEquals(AutoCapitalizationPolicy.CapMode.SENTENCES, mode)

        // Fresh empty field -> Shift active
        assertTrue(AutoCapitalizationPolicy.shouldAutoShift(mode, "", ""))

        // After first letter or mid-word -> Shift inactive
        assertFalse(AutoCapitalizationPolicy.shouldAutoShift(mode, "H", ""))
        assertFalse(AutoCapitalizationPolicy.shouldAutoShift(mode, "Hello ", ""))

        // Period without trailing space yet -> Shift still inactive
        assertFalse(AutoCapitalizationPolicy.shouldAutoShift(mode, "Hello.", ""))
        assertFalse(AutoCapitalizationPolicy.shouldAutoShift(mode, "example.", ""))

        // Period + space -> Shift activates
        assertTrue(AutoCapitalizationPolicy.shouldAutoShift(mode, "Hello. ", ""))
        assertTrue(AutoCapitalizationPolicy.shouldAutoShift(mode, "Hello.   ", ""))

        // Question mark / exclamation + space -> Shift activates
        assertTrue(AutoCapitalizationPolicy.shouldAutoShift(mode, "How are you? ", ""))
        assertTrue(AutoCapitalizationPolicy.shouldAutoShift(mode, "Done! ", ""))

        // Newline paragraph break -> Shift activates
        assertTrue(AutoCapitalizationPolicy.shouldAutoShift(mode, "Hello.\n", ""))
        assertTrue(AutoCapitalizationPolicy.shouldAutoShift(mode, "Line one\n  ", ""))

        // Mid-sentence word or decimal -> does NOT capitalize every word
        assertFalse(AutoCapitalizationPolicy.shouldAutoShift(mode, "Version 3.14 ", ""))
        assertFalse(AutoCapitalizationPolicy.shouldAutoShift(mode, "How are ", ""))
    }

    @Test
    fun testCursorMovementMidWordAndTargetCapFlags() {
        val sentencesMode = AutoCapitalizationPolicy.resolveCapMode(InputFieldPolicy.TYPE_CLASS_TEXT)
        assertEquals(AutoCapitalizationPolicy.CapMode.SENTENCES, sentencesMode)

        // Editing immediately before an existing word at start of field should NOT randomly force shift
        assertFalse(AutoCapitalizationPolicy.shouldAutoShift(sentencesMode, "", "hello"))
        // Cursor moved into middle of word after a period
        assertFalse(AutoCapitalizationPolicy.shouldAutoShift(sentencesMode, "Hello. ", "how"))
        // Cursor moved back to end right after ". " with trailing space/empty after
        assertTrue(AutoCapitalizationPolicy.shouldAutoShift(sentencesMode, "Hello. ", " World"))

        // Active text selection suppresses auto-shift
        assertFalse(AutoCapitalizationPolicy.shouldAutoShift(sentencesMode, "Hello. ", "", hasSelection = true))

        // CAP_WORDS & PERSON_NAME capitalize each word boundary
        val wordsMode = AutoCapitalizationPolicy.resolveCapMode(
            InputFieldPolicy.TYPE_CLASS_TEXT or AutoCapitalizationPolicy.TYPE_TEXT_FLAG_CAP_WORDS
        )
        assertEquals(AutoCapitalizationPolicy.CapMode.WORDS, wordsMode)
        assertTrue(AutoCapitalizationPolicy.shouldAutoShift(wordsMode, "John ", ""))

        val personNameMode = AutoCapitalizationPolicy.resolveCapMode(
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_PERSON_NAME
        )
        assertEquals(AutoCapitalizationPolicy.CapMode.WORDS, personNameMode)
        assertTrue(AutoCapitalizationPolicy.shouldAutoShift(personNameMode, "Ahmad ", ""))

        // CAP_CHARACTERS keeps shift active throughout
        val charsMode = AutoCapitalizationPolicy.resolveCapMode(
            InputFieldPolicy.TYPE_CLASS_TEXT or AutoCapitalizationPolicy.TYPE_TEXT_FLAG_CAP_CHARACTERS
        )
        assertEquals(AutoCapitalizationPolicy.CapMode.CHARACTERS, charsMode)
        assertTrue(AutoCapitalizationPolicy.shouldAutoShift(charsMode, "AB", ""))
    }
}
