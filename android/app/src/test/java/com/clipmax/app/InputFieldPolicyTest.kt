package com.clipmax.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputFieldPolicyTest {

    @Test
    fun testPasswordAndPinFieldsDisableClipboardAndTypingAssistance() {
        val textPwd = InputFieldPolicy.evaluate(
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_PASSWORD
        )
        assertTrue(textPwd.isPasswordOrPin)
        assertFalse(textPwd.allowClipboard)
        assertFalse(textPwd.allowSuggestions)
        assertFalse(textPwd.allowAutocomplete)
        assertFalse(textPwd.allowAutocorrect)

        val pinField = InputFieldPolicy.evaluate(
            InputFieldPolicy.TYPE_CLASS_NUMBER or InputFieldPolicy.TYPE_NUMBER_VARIATION_PASSWORD
        )
        assertTrue(pinField.isPasswordOrPin)
        assertFalse(pinField.allowClipboard)
        assertFalse(pinField.allowAutocorrect)
    }

    @Test
    fun testEmailUrlNumericPhoneFilterAndNoSuggestionsDisableIntelligence() {
        val specializedTypes = listOf(
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_URI,
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_FILTER,
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            InputFieldPolicy.TYPE_CLASS_NUMBER,
            InputFieldPolicy.TYPE_CLASS_PHONE,
            InputFieldPolicy.TYPE_CLASS_DATETIME
        )

        for (type in specializedTypes) {
            val caps = InputFieldPolicy.evaluate(type)
            assertFalse(caps.isPasswordOrPin)
            assertTrue(caps.allowClipboard)
            assertFalse("Expected suggestions disabled for inputType=$type", caps.allowSuggestions)
            assertFalse("Expected autocomplete disabled for inputType=$type", caps.allowAutocomplete)
            assertFalse("Expected autocorrect disabled for inputType=$type", caps.allowAutocorrect)
        }
    }

    @Test
    fun testStandardTextAndPersonNameFields() {
        val normalText = InputFieldPolicy.evaluate(
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_SHORT_MESSAGE
        )
        assertTrue(normalText.allowClipboard)
        assertTrue(normalText.allowSuggestions)
        assertTrue(normalText.allowAutocomplete)
        assertTrue(normalText.allowAutocorrect)
        assertTrue(normalText.allowSessionLearning)

        val personName = InputFieldPolicy.evaluate(
            InputFieldPolicy.TYPE_CLASS_TEXT or InputFieldPolicy.TYPE_TEXT_VARIATION_PERSON_NAME
        )
        assertTrue(personName.allowSuggestions)
        assertFalse(personName.allowAutocorrect)

        val incognitoText = InputFieldPolicy.evaluate(
            InputFieldPolicy.TYPE_CLASS_TEXT,
            InputFieldPolicy.IME_FLAG_NO_PERSONALIZED_LEARNING
        )
        assertTrue(incognitoText.allowAutocomplete)
        assertFalse(incognitoText.allowSessionLearning)
    }
}
