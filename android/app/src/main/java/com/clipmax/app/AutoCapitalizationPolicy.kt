package com.clipmax.app

/**
 * Context-aware automatic sentence capitalization policy matching Android EditorInfo flags
 * and surrounding cursor text. Pure JVM helper with zero Android framework dependencies.
 */
internal object AutoCapitalizationPolicy {

    const val TYPE_MASK_CLASS = InputFieldPolicy.TYPE_MASK_CLASS
    const val TYPE_MASK_VARIATION = InputFieldPolicy.TYPE_MASK_VARIATION

    const val TYPE_CLASS_TEXT = InputFieldPolicy.TYPE_CLASS_TEXT
    const val TYPE_TEXT_FLAG_CAP_CHARACTERS = 0x00001000
    const val TYPE_TEXT_FLAG_CAP_WORDS = 0x00002000
    const val TYPE_TEXT_FLAG_CAP_SENTENCES = 0x00004000
    const val TYPE_TEXT_FLAG_NO_SUGGESTIONS = InputFieldPolicy.TYPE_TEXT_FLAG_NO_SUGGESTIONS

    enum class CapMode {
        NONE,
        SENTENCES,
        WORDS,
        CHARACTERS
    }

    /**
     * Resolves the active automatic capitalization mode for an EditorInfo inputType.
     * Specialized fields (passwords, numbers, phone, datetime, email, URI, filter, no-suggestions,
     * and Search Clips filter mode) strictly disable automatic capitalization.
     */
    fun resolveCapMode(inputType: Int, isFilterMode: Boolean = false): CapMode {
        if (isFilterMode) return CapMode.NONE

        val capabilities = InputFieldPolicy.evaluate(inputType, 0)
        if (capabilities.isPasswordOrPin) return CapMode.NONE

        val fieldClass = inputType and TYPE_MASK_CLASS
        if (fieldClass != TYPE_CLASS_TEXT) return CapMode.NONE

        val variation = inputType and TYPE_MASK_VARIATION
        val isSpecializedNoCap = variation == InputFieldPolicy.TYPE_TEXT_VARIATION_URI ||
            variation == InputFieldPolicy.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputFieldPolicy.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputFieldPolicy.TYPE_TEXT_VARIATION_FILTER ||
            variation == InputFieldPolicy.TYPE_TEXT_VARIATION_PHONETIC ||
            variation == InputFieldPolicy.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputFieldPolicy.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputFieldPolicy.TYPE_TEXT_VARIATION_WEB_PASSWORD

        if (isSpecializedNoCap) return CapMode.NONE
        if ((inputType and TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return CapMode.NONE

        // Respect explicit application capitalization preferences first
        if ((inputType and TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0) return CapMode.CHARACTERS
        if ((inputType and TYPE_TEXT_FLAG_CAP_WORDS) != 0) return CapMode.WORDS
        if ((inputType and TYPE_TEXT_FLAG_CAP_SENTENCES) != 0) return CapMode.SENTENCES

        // Person name fields naturally capitalize each word unless overridden
        if (variation == InputFieldPolicy.TYPE_TEXT_VARIATION_PERSON_NAME) return CapMode.WORDS

        // Default allowed prose fields (including WebViews/chats omitting CAP_SENTENCES) to sentence cap
        return CapMode.SENTENCES
    }

    /**
     * Determines whether Shift should automatically activate at the current cursor position.
     */
    fun shouldAutoShift(
        capMode: CapMode,
        textBeforeCursor: String?,
        textAfterCursor: String?,
        hasSelection: Boolean = false,
        androidCursorCapsMode: Int = 0
    ): Boolean {
        if (capMode == CapMode.NONE || hasSelection) return false
        if (capMode == CapMode.CHARACTERS) return true

        val after = textAfterCursor.orEmpty()
        val isBeforeExistingWordChars = after.isNotEmpty() && (after[0].isLetterOrDigit() || after[0] == '\'')
        if (isBeforeExistingWordChars) {
            return false
        }

        if (textBeforeCursor == null) {
            return androidCursorCapsMode != 0
        }

        if (textBeforeCursor.isEmpty()) {
            return true
        }

        if (capMode == CapMode.WORDS) {
            return textBeforeCursor.last().isWhitespace()
        }

        // SENTENCES mode:
        // Activate at very start of buffer/paragraph, or after sentence terminator (. ! ?) + >=1 spaces/tabs/newlines
        var idx = textBeforeCursor.length - 1
        var sawTrailingSpace = false
        while (idx >= 0 && (textBeforeCursor[idx] == ' ' || textBeforeCursor[idx] == '\t')) {
            sawTrailingSpace = true
            idx--
        }

        // Entire prefix before cursor was spaces/tabs
        if (idx < 0) return true

        val ch = textBeforeCursor[idx]
        if (ch == '\n' || ch == '\r') {
            return true
        }

        if (!sawTrailingSpace) {
            return false
        }

        return ch == '.' || ch == '!' || ch == '?'
    }

    /**
     * Pure JVM state machine governing Shift (0 = off, 1 = shifted, 2 = Caps Lock)
     * and platform double-tap timeout transitions, preserving manual Caps Lock even when
     * automatic capitalization is disabled and resetting double-tap windows on non-shift events.
     */
    class ShiftKeyStateMachine {
        var shiftState: Int = 0
            private set

        var manualShiftOverride: Boolean = false
            private set

        private var lastShiftTapTimeMs: Long = 0L

        fun onShiftPress(nowMs: Long, doubleTapTimeoutMs: Long): Int {
            val timeout = doubleTapTimeoutMs.coerceAtLeast(1L)
            if (shiftState == 2) {
                // Single tap exits Caps Lock cleanly and clears timer so next tap enters 1-shot Shift
                shiftState = 0
                manualShiftOverride = true
                lastShiftTapTimeMs = 0L
                return shiftState
            }

            val isDoubleTap = lastShiftTapTimeMs > 0L && (nowMs - lastShiftTapTimeMs) in 0L..timeout
            if (isDoubleTap) {
                shiftState = 2
                manualShiftOverride = true
                lastShiftTapTimeMs = 0L
            } else {
                shiftState = if (shiftState == 0) 1 else 0
                manualShiftOverride = true
                lastShiftTapTimeMs = nowMs
            }
            return shiftState
        }

        fun onLetterCommitted(capMode: CapMode) {
            lastShiftTapTimeMs = 0L
            manualShiftOverride = false
            if (shiftState == 1 && capMode != CapMode.CHARACTERS) {
                shiftState = 0
            }
        }

        fun onNonLetterInput() {
            lastShiftTapTimeMs = 0L
            manualShiftOverride = false
        }

        fun resetDoubleTapTimer() {
            lastShiftTapTimeMs = 0L
        }

        fun clearManualOverride() {
            manualShiftOverride = false
            lastShiftTapTimeMs = 0L
        }

        fun onInputSessionReset() {
            shiftState = 0
            manualShiftOverride = false
            lastShiftTapTimeMs = 0L
        }

        fun syncWithAutoCap(
            capMode: CapMode,
            preserveManualShift: Boolean,
            computeAutoShift: () -> Boolean
        ): Boolean {
            val prev = shiftState
            if (shiftState == 2) {
                // Manual Caps Lock persists even in CapMode.NONE fields (URLs/filter/no-suggestions)
                return false
            }
            if (preserveManualShift && manualShiftOverride) {
                return false
            }
            shiftState = if (capMode == CapMode.NONE) {
                0
            } else if (computeAutoShift()) {
                1
            } else {
                0
            }
            return shiftState != prev
        }
    }
}
