package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun testDynamicActionKeyResolutionAndNoEnterActionPriority() {
        val cases = listOf(
            InputFieldPolicy.IME_ACTION_SEARCH to InputFieldPolicy.ActionKeyKind.SEARCH,
            InputFieldPolicy.IME_ACTION_SEND to InputFieldPolicy.ActionKeyKind.SEND,
            InputFieldPolicy.IME_ACTION_GO to InputFieldPolicy.ActionKeyKind.GO,
            InputFieldPolicy.IME_ACTION_NEXT to InputFieldPolicy.ActionKeyKind.NEXT,
            InputFieldPolicy.IME_ACTION_DONE to InputFieldPolicy.ActionKeyKind.DONE
        )
        for ((flag, expectedKind) in cases) {
            val resolved = InputFieldPolicy.resolveActionKey(flag, isFilterMode = false)
            assertEquals(expectedKind, resolved.kind)
            assertEquals(flag, resolved.editorActionId)
            assertFalse(resolved.isFilterDismiss)

            // IME_FLAG_NO_ENTER_ACTION must take priority over any action bit (e.g. multiline chat)
            val multiline = InputFieldPolicy.resolveActionKey(
                flag or InputFieldPolicy.IME_FLAG_NO_ENTER_ACTION,
                isFilterMode = false
            )
            assertEquals(InputFieldPolicy.ActionKeyKind.ENTER, multiline.kind)
            assertNull(multiline.editorActionId)
            assertFalse(multiline.isFilterDismiss)
        }

        val noneAction = InputFieldPolicy.resolveActionKey(InputFieldPolicy.IME_ACTION_NONE)
        assertEquals(InputFieldPolicy.ActionKeyKind.ENTER, noneAction.kind)
        assertNull(noneAction.editorActionId)

        // Search Clips overrides host action with internal filter dismissal
        val filterAction = InputFieldPolicy.resolveActionKey(
            InputFieldPolicy.IME_ACTION_SEND,
            isFilterMode = true
        )
        assertEquals(InputFieldPolicy.ActionKeyKind.DONE, filterAction.kind)
        assertTrue(filterAction.isFilterDismiss)
        assertNull(filterAction.editorActionId)
    }

    @Test
    fun testNumericFieldClassificationPreservedWithoutAutoActivatingNumpad() {
        val num = InputFieldPolicy.evaluate(InputFieldPolicy.TYPE_CLASS_NUMBER)
        assertEquals(InputFieldPolicy.NumericFieldKind.NUMBER, num.numericFieldKind)
        assertTrue(InputFieldPolicy.isNumericField(num))
        assertFalse(num.isPasswordOrPin)
        assertTrue(num.allowClipboard)
        assertFalse(InputFieldPolicy.shouldAutoActivateNumpad(num, isFilterMode = false))
        assertFalse(InputFieldPolicy.shouldAutoActivateNumpad(num, isFilterMode = true))
        assertFalse(InputFieldPolicy.shouldUseDedicatedNumpad(num, isFilterMode = false))

        val phone = InputFieldPolicy.evaluate(InputFieldPolicy.TYPE_CLASS_PHONE)
        assertEquals(InputFieldPolicy.NumericFieldKind.PHONE, phone.numericFieldKind)
        assertTrue(InputFieldPolicy.isNumericField(phone))
        assertFalse(InputFieldPolicy.shouldAutoActivateNumpad(phone, isFilterMode = false))

        val datetime = InputFieldPolicy.evaluate(InputFieldPolicy.TYPE_CLASS_DATETIME)
        assertEquals(InputFieldPolicy.NumericFieldKind.DATETIME, datetime.numericFieldKind)
        assertTrue(InputFieldPolicy.isNumericField(datetime))
        assertFalse(InputFieldPolicy.shouldAutoActivateNumpad(datetime, isFilterMode = false))

        val pin = InputFieldPolicy.evaluate(
            InputFieldPolicy.TYPE_CLASS_NUMBER or InputFieldPolicy.TYPE_NUMBER_VARIATION_PASSWORD
        )
        assertEquals(InputFieldPolicy.NumericFieldKind.PIN, pin.numericFieldKind)
        assertTrue(InputFieldPolicy.isNumericField(pin))
        assertTrue(pin.isPasswordOrPin)
        assertFalse(pin.allowClipboard)
        assertFalse(pin.allowSuggestions)
        assertFalse(pin.allowAutocomplete)
        assertFalse(pin.allowAutocorrect)
        assertFalse(pin.allowSessionLearning)
        assertFalse(InputFieldPolicy.shouldAutoActivateNumpad(pin, isFilterMode = false))

        val textField = InputFieldPolicy.evaluate(InputFieldPolicy.TYPE_CLASS_TEXT)
        assertEquals(InputFieldPolicy.NumericFieldKind.NONE, textField.numericFieldKind)
        assertFalse(InputFieldPolicy.isNumericField(textField))
        assertFalse(InputFieldPolicy.shouldAutoActivateNumpad(textField, isFilterMode = false))
    }
}
