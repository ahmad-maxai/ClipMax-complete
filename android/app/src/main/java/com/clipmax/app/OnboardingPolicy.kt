package com.clipmax.app

import java.util.Locale

/**
 * Pure JVM state machine and Android IME component-matching helpers for
 * ClipMax's three-stage verified onboarding flow:
 * 1. ENABLE_KEYBOARD -> verified against ENABLED_INPUT_METHODS
 * 2. SELECT_KEYBOARD -> verified against DEFAULT_INPUT_METHOD
 * 3. CONFIGURE_EXPIRY -> contextual onboarding guide inside Settings retention section
 * -> COMPLETION
 */
internal object OnboardingPolicy {

    const val PREF_FILE = "clipmax_onboarding_prefs"
    const val KEY_IS_COMPLETED = "is_onboarding_completed"
    const val KEY_STEP = "onboarding_step"
    const val KEY_ATTEMPTED_ENABLE = "onboarding_attempted_enable"
    const val KEY_ATTEMPTED_SELECT = "onboarding_attempted_select"

    enum class Step(val wireName: String) {
        INTRODUCTION("INTRODUCTION"),
        ENABLE_KEYBOARD("ENABLE_KEYBOARD"),
        SELECT_KEYBOARD("SELECT_KEYBOARD"),
        CONFIGURE_EXPIRY("CONFIGURE_EXPIRY"),
        COMPLETION("COMPLETION");

        companion object {
            fun fromWire(raw: String?): Step {
                val normalized = raw?.trim()?.uppercase(Locale.ROOT)
                return entries.firstOrNull { it.wireName == normalized } ?: INTRODUCTION
            }
        }
    }

    data class State(
        val completed: Boolean,
        val step: Step,
        val imeEnabled: Boolean,
        val imeSelected: Boolean,
        val attemptedEnable: Boolean,
        val attemptedSelect: Boolean
    )

    /**
     * Parses an Android flattened ComponentName string (e.g. "com.clipmax.app/.ClipMaxImeService"
     * or "com.clipmax.app/com.clipmax.app.ClipMaxImeService") into canonical (packageName, fullClassName).
     */
    fun canonicalizeComponent(rawComponent: String?): Pair<String, String>? {
        val trimmed = rawComponent?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val slashIndex = trimmed.indexOf('/')
        if (slashIndex <= 0 || slashIndex >= trimmed.length - 1) return null

        val pkg = trimmed.substring(0, slashIndex).trim()
        val clsRaw = trimmed.substring(slashIndex + 1).trim()
        if (pkg.isEmpty() || clsRaw.isEmpty()) return null

        val fullCls = when {
            clsRaw.startsWith(".") -> pkg + clsRaw
            clsRaw.contains('.') -> clsRaw
            else -> "$pkg.$clsRaw"
        }
        return pkg to fullCls
    }

    fun componentsMatch(
        expectedPackage: String,
        expectedClassName: String,
        candidateComponent: String?
    ): Boolean {
        val pkg = expectedPackage.trim()
        val cls = expectedClassName.trim().let {
            if (it.startsWith(".")) pkg + it else it
        }
        if (pkg.isEmpty() || cls.isEmpty()) return false
        val parsed = canonicalizeComponent(candidateComponent) ?: return false
        return parsed.first == pkg && parsed.second == cls
    }

    fun isComponentInColonList(
        expectedPackage: String,
        expectedClassName: String,
        rawEnabledInputMethods: String?
    ): Boolean {
        val raw = rawEnabledInputMethods?.trim().orEmpty()
        if (raw.isEmpty()) return false
        return raw.split(':').any { token ->
            componentsMatch(expectedPackage, expectedClassName, token)
        }
    }

    /**
     * Reconciles persisted onboarding progress against verified Android IME state.
     *
     * Rules:
     * - Once [completed] is true, onboarding stays completed regardless of later IME changes.
     * - While on [Step.INTRODUCTION], stays on Introduction until user taps Next.
     * - On [Step.ENABLE_KEYBOARD], advances to [Step.SELECT_KEYBOARD] once enabled is verified,
     *   or to [Step.CONFIGURE_EXPIRY] only when BOTH enabled and selected are verified.
     *   (Enabling alone never skips Stage 2.)
     * - On [Step.SELECT_KEYBOARD], regresses to [Step.ENABLE_KEYBOARD] if disabled,
     *   or unlocks [Step.CONFIGURE_EXPIRY] once selected as default IME is strictly verified.
     * - On [Step.CONFIGURE_EXPIRY], remains sticky across activity recreation, keyboard switching,
     *   and process death until the user taps Finish Setup or Skip.
     * - On [Step.COMPLETION], remains on [Step.COMPLETION].
     */
    fun reconcileState(
        completed: Boolean,
        persistedStep: Step,
        imeEnabled: Boolean,
        imeSelected: Boolean,
        attemptedEnable: Boolean,
        attemptedSelect: Boolean
    ): State {
        if (completed) {
            return State(
                completed = true,
                step = Step.COMPLETION,
                imeEnabled = imeEnabled,
                imeSelected = imeSelected,
                attemptedEnable = false,
                attemptedSelect = false
            )
        }

        val resolvedStep = when (persistedStep) {
            Step.INTRODUCTION -> Step.INTRODUCTION
            Step.ENABLE_KEYBOARD -> when {
                imeEnabled && imeSelected -> Step.CONFIGURE_EXPIRY
                imeEnabled -> Step.SELECT_KEYBOARD
                else -> Step.ENABLE_KEYBOARD
            }
            Step.SELECT_KEYBOARD -> when {
                !imeEnabled -> Step.ENABLE_KEYBOARD
                imeSelected -> Step.CONFIGURE_EXPIRY
                else -> Step.SELECT_KEYBOARD
            }
            Step.CONFIGURE_EXPIRY -> Step.CONFIGURE_EXPIRY
            Step.COMPLETION -> Step.COMPLETION
        }

        return State(
            completed = false,
            step = resolvedStep,
            imeEnabled = imeEnabled,
            imeSelected = imeSelected,
            attemptedEnable = if (resolvedStep == Step.ENABLE_KEYBOARD) attemptedEnable else false,
            attemptedSelect = if (resolvedStep == Step.SELECT_KEYBOARD) attemptedSelect else false
        )
    }

    /**
     * Computes next state when user taps "Next" on the Welcome introduction screen.
     */
    fun advanceFromIntroduction(imeEnabled: Boolean, imeSelected: Boolean): State {
        return reconcileState(
            completed = false,
            persistedStep = Step.ENABLE_KEYBOARD,
            imeEnabled = imeEnabled,
            imeSelected = imeSelected,
            attemptedEnable = false,
            attemptedSelect = false
        )
    }
}
