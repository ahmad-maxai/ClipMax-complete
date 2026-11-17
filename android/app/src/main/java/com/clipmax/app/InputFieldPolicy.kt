package com.clipmax.app

/**
 * Evaluates Android EditorInfo inputType and imeOptions flags to determine whether
 * clipboard insertion, word suggestions, autocomplete, and autocorrection should be active.
 * Pure JVM helper with exact Android InputType/EditorInfo bitmask constants.
 */
internal object InputFieldPolicy {

    const val TYPE_MASK_CLASS = 0x0000000f
    const val TYPE_MASK_VARIATION = 0x00000ff0

    const val TYPE_CLASS_TEXT = 0x00000001
    const val TYPE_CLASS_NUMBER = 0x00000002
    const val TYPE_CLASS_PHONE = 0x00000003
    const val TYPE_CLASS_DATETIME = 0x00000004

    const val TYPE_TEXT_VARIATION_URI = 0x00000010
    const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x00000020
    const val TYPE_TEXT_VARIATION_EMAIL_SUBJECT = 0x00000030
    const val TYPE_TEXT_VARIATION_SHORT_MESSAGE = 0x00000040
    const val TYPE_TEXT_VARIATION_LONG_MESSAGE = 0x00000050
    const val TYPE_TEXT_VARIATION_PERSON_NAME = 0x00000060
    const val TYPE_TEXT_VARIATION_POSTAL_ADDRESS = 0x00000070
    const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    const val TYPE_TEXT_VARIATION_WEB_EDIT_TEXT = 0x000000a0
    const val TYPE_TEXT_VARIATION_FILTER = 0x000000b0
    const val TYPE_TEXT_VARIATION_PHONETIC = 0x000000c0
    const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0x000000d0
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0

    const val TYPE_TEXT_FLAG_NO_SUGGESTIONS = 0x00080000
    const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010

    const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x01000000
    const val IME_MASK_ACTION = 0x000000ff
    const val IME_ACTION_UNSPECIFIED = 0x00000000
    const val IME_ACTION_NONE = 0x00000001
    const val IME_ACTION_GO = 0x00000002
    const val IME_ACTION_SEARCH = 0x00000003
    const val IME_ACTION_SEND = 0x00000004
    const val IME_ACTION_NEXT = 0x00000005
    const val IME_ACTION_DONE = 0x00000006
    const val IME_ACTION_PREVIOUS = 0x00000007
    const val IME_FLAG_NO_ENTER_ACTION = 0x40000000

    enum class ActionKeyKind {
        ENTER,
        SEARCH,
        SEND,
        GO,
        NEXT,
        DONE
    }

    data class ActionKeyResolution(
        val kind: ActionKeyKind,
        val editorActionId: Int?,
        val isFilterDismiss: Boolean = false
    )

    fun resolveActionKey(imeOptions: Int, isFilterMode: Boolean = false): ActionKeyResolution {
        if (isFilterMode) {
            return ActionKeyResolution(
                kind = ActionKeyKind.DONE,
                editorActionId = null,
                isFilterDismiss = true
            )
        }
        if ((imeOptions and IME_FLAG_NO_ENTER_ACTION) != 0) {
            return ActionKeyResolution(kind = ActionKeyKind.ENTER, editorActionId = null)
        }
        return when (imeOptions and IME_MASK_ACTION) {
            IME_ACTION_SEARCH -> ActionKeyResolution(ActionKeyKind.SEARCH, IME_ACTION_SEARCH)
            IME_ACTION_SEND -> ActionKeyResolution(ActionKeyKind.SEND, IME_ACTION_SEND)
            IME_ACTION_GO -> ActionKeyResolution(ActionKeyKind.GO, IME_ACTION_GO)
            IME_ACTION_NEXT -> ActionKeyResolution(ActionKeyKind.NEXT, IME_ACTION_NEXT)
            IME_ACTION_DONE -> ActionKeyResolution(ActionKeyKind.DONE, IME_ACTION_DONE)
            IME_ACTION_PREVIOUS -> ActionKeyResolution(ActionKeyKind.ENTER, IME_ACTION_PREVIOUS)
            else -> ActionKeyResolution(ActionKeyKind.ENTER, null)
        }
    }

    enum class NumericFieldKind {
        NONE,
        NUMBER,
        PHONE,
        DATETIME,
        PIN
    }

    data class FieldCapabilities(
        val isPasswordOrPin: Boolean,
        val allowClipboard: Boolean,
        val allowSuggestions: Boolean,
        val allowAutocomplete: Boolean,
        val allowAutocorrect: Boolean,
        val allowSessionLearning: Boolean,
        val numericFieldKind: NumericFieldKind = NumericFieldKind.NONE
    )

    fun isNumericField(capabilities: FieldCapabilities): Boolean =
        capabilities.numericFieldKind != NumericFieldKind.NONE

    /**
     * Phase 2A corrective navigation policy: NUMPAD is never auto-activated when a numeric
     * EditorInfo field receives focus; users enter it via ALPHA -> SYMBOLS (?123) -> NUMPAD (12 34).
     */
    fun shouldAutoActivateNumpad(
        @Suppress("UNUSED_PARAMETER") capabilities: FieldCapabilities,
        @Suppress("UNUSED_PARAMETER") isFilterMode: Boolean = false
    ): Boolean = false

    fun shouldUseDedicatedNumpad(capabilities: FieldCapabilities, isFilterMode: Boolean = false): Boolean =
        shouldAutoActivateNumpad(capabilities, isFilterMode)

    fun evaluate(inputType: Int, imeOptions: Int = 0): FieldCapabilities {
        val fieldClass = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION

        val isNumericPin = fieldClass == TYPE_CLASS_NUMBER && variation == TYPE_NUMBER_VARIATION_PASSWORD
        val isPassword = (fieldClass == TYPE_CLASS_TEXT && (
            variation == TYPE_TEXT_VARIATION_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_WEB_PASSWORD
            )) || isNumericPin

        val noPersonalized = (imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        if (isPassword) {
            return FieldCapabilities(
                isPasswordOrPin = true,
                allowClipboard = false,
                allowSuggestions = false,
                allowAutocomplete = false,
                allowAutocorrect = false,
                allowSessionLearning = false,
                numericFieldKind = if (isNumericPin) NumericFieldKind.PIN else NumericFieldKind.NONE
            )
        }

        val numericKind = when (fieldClass) {
            TYPE_CLASS_NUMBER -> NumericFieldKind.NUMBER
            TYPE_CLASS_PHONE -> NumericFieldKind.PHONE
            TYPE_CLASS_DATETIME -> NumericFieldKind.DATETIME
            else -> NumericFieldKind.NONE
        }

        val isNonProseClass = numericKind != NumericFieldKind.NONE

        val isSpecializedText = fieldClass == TYPE_CLASS_TEXT && (
            variation == TYPE_TEXT_VARIATION_URI ||
                variation == TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == TYPE_TEXT_VARIATION_FILTER ||
                variation == TYPE_TEXT_VARIATION_PHONETIC
            )

        val noSuggestionsFlag = (inputType and TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0

        // Also avoid replacing proper names if Android explicitly flags PERSON_NAME
        val isPersonNameField = fieldClass == TYPE_CLASS_TEXT && variation == TYPE_TEXT_VARIATION_PERSON_NAME

        val disableIntelligence = isNonProseClass || isSpecializedText || noSuggestionsFlag
        return FieldCapabilities(
            isPasswordOrPin = false,
            allowClipboard = true,
            allowSuggestions = !disableIntelligence,
            allowAutocomplete = !disableIntelligence,
            allowAutocorrect = !disableIntelligence && !isPersonNameField,
            allowSessionLearning = !disableIntelligence && !noPersonalized,
            numericFieldKind = numericKind
        )
    }
}
