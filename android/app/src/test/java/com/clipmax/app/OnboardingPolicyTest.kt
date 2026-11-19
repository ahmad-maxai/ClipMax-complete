package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPolicyTest {

    private val pkg = "com.clipmax.app"
    private val cls = "com.clipmax.app.ClipMaxImeService"

    @Test
    fun testComponentMatchingHandlesShortFullAndMalformedIds() {
        assertEquals(pkg to cls, OnboardingPolicy.canonicalizeComponent("com.clipmax.app/.ClipMaxImeService"))
        assertEquals(pkg to cls, OnboardingPolicy.canonicalizeComponent("com.clipmax.app/com.clipmax.app.ClipMaxImeService"))
        assertEquals(pkg to cls, OnboardingPolicy.canonicalizeComponent("  com.clipmax.app/.ClipMaxImeService  "))
        assertNull(OnboardingPolicy.canonicalizeComponent(null))
        assertNull(OnboardingPolicy.canonicalizeComponent(""))
        assertNull(OnboardingPolicy.canonicalizeComponent("com.clipmax.app"))
        assertNull(OnboardingPolicy.canonicalizeComponent("/.ClipMaxImeService"))

        assertTrue(OnboardingPolicy.componentsMatch(pkg, cls, "com.clipmax.app/.ClipMaxImeService"))
        assertTrue(OnboardingPolicy.componentsMatch(pkg, cls, "com.clipmax.app/com.clipmax.app.ClipMaxImeService"))
        assertFalse(OnboardingPolicy.componentsMatch(pkg, cls, "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"))
        assertFalse(OnboardingPolicy.componentsMatch(pkg, cls, "com.clipmax.app/.OtherImeService"))
        assertFalse(OnboardingPolicy.componentsMatch(pkg, cls, null))
    }

    @Test
    fun testEnabledInputMethodsListDetection() {
        val otherImes = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME:com.samsung.android.honeyboard/.service.HoneyBoardService"
        assertFalse(OnboardingPolicy.isComponentInColonList(pkg, cls, otherImes))
        assertFalse(OnboardingPolicy.isComponentInColonList(pkg, cls, null))
        assertFalse(OnboardingPolicy.isComponentInColonList(pkg, cls, ""))

        assertTrue(
            OnboardingPolicy.isComponentInColonList(
                pkg,
                cls,
                "$otherImes:com.clipmax.app/.ClipMaxImeService"
            )
        )
        assertTrue(
            OnboardingPolicy.isComponentInColonList(
                pkg,
                cls,
                "com.clipmax.app/com.clipmax.app.ClipMaxImeService:$otherImes"
            )
        )
    }

    @Test
    fun testIntroAdvancesCorrectlyBasedOnRealSystemState() {
        val intro = OnboardingPolicy.reconcileState(
            completed = false,
            persistedStep = OnboardingPolicy.Step.INTRODUCTION,
            imeEnabled = true,
            imeSelected = true,
            attemptedEnable = false,
            attemptedSelect = false
        )
        assertEquals(OnboardingPolicy.Step.INTRODUCTION, intro.step)
        assertFalse(intro.completed)

        val needEnable = OnboardingPolicy.advanceFromIntroduction(imeEnabled = false, imeSelected = false)
        assertEquals(OnboardingPolicy.Step.ENABLE_KEYBOARD, needEnable.step)
        assertFalse(needEnable.completed)

        val alreadyEnabled = OnboardingPolicy.advanceFromIntroduction(imeEnabled = true, imeSelected = false)
        assertEquals(OnboardingPolicy.Step.SELECT_KEYBOARD, alreadyEnabled.step)
        assertFalse(alreadyEnabled.completed)

        val alreadySelected = OnboardingPolicy.advanceFromIntroduction(imeEnabled = true, imeSelected = true)
        assertEquals(OnboardingPolicy.Step.COMPLETION, alreadySelected.step)
        assertFalse(alreadySelected.completed)
    }

    @Test
    fun testEnableAndSelectVerificationAndRetryPreservation() {
        // User returned from Settings without switching ClipMax on
        val notEnabledYet = OnboardingPolicy.reconcileState(
            completed = false,
            persistedStep = OnboardingPolicy.Step.ENABLE_KEYBOARD,
            imeEnabled = false,
            imeSelected = false,
            attemptedEnable = true,
            attemptedSelect = false
        )
        assertEquals(OnboardingPolicy.Step.ENABLE_KEYBOARD, notEnabledYet.step)
        assertTrue(notEnabledYet.attemptedEnable)
        assertFalse(notEnabledYet.completed)

        // User enabled ClipMax in Settings -> advances to SELECT_KEYBOARD
        val nowEnabled = OnboardingPolicy.reconcileState(
            completed = false,
            persistedStep = OnboardingPolicy.Step.ENABLE_KEYBOARD,
            imeEnabled = true,
            imeSelected = false,
            attemptedEnable = true,
            attemptedSelect = false
        )
        assertEquals(OnboardingPolicy.Step.SELECT_KEYBOARD, nowEnabled.step)
        assertFalse(nowEnabled.attemptedEnable)

        // User dismissed IME picker without choosing ClipMax
        val notSelectedYet = OnboardingPolicy.reconcileState(
            completed = false,
            persistedStep = OnboardingPolicy.Step.SELECT_KEYBOARD,
            imeEnabled = true,
            imeSelected = false,
            attemptedEnable = false,
            attemptedSelect = true
        )
        assertEquals(OnboardingPolicy.Step.SELECT_KEYBOARD, notSelectedYet.step)
        assertTrue(notSelectedYet.attemptedSelect)

        // User selected ClipMax as active IME -> transitions to COMPLETION (still waiting for "Start using ClipMax")
        val selected = OnboardingPolicy.reconcileState(
            completed = false,
            persistedStep = OnboardingPolicy.Step.SELECT_KEYBOARD,
            imeEnabled = true,
            imeSelected = true,
            attemptedEnable = false,
            attemptedSelect = true
        )
        assertEquals(OnboardingPolicy.Step.COMPLETION, selected.step)
        assertFalse(selected.completed)
        assertFalse(selected.attemptedSelect)
    }

    @Test
    fun testDisabledRegressionAndCompletedIdempotency() {
        // If user disables ClipMax before completing setup, revert cleanly to ENABLE_KEYBOARD
        val reverted = OnboardingPolicy.reconcileState(
            completed = false,
            persistedStep = OnboardingPolicy.Step.SELECT_KEYBOARD,
            imeEnabled = false,
            imeSelected = false,
            attemptedEnable = false,
            attemptedSelect = true
        )
        assertEquals(OnboardingPolicy.Step.ENABLE_KEYBOARD, reverted.step)

        // Once completed (via Start or Skip), never re-enter onboarding even if IME is toggled off later
        val finished = OnboardingPolicy.reconcileState(
            completed = true,
            persistedStep = OnboardingPolicy.Step.COMPLETION,
            imeEnabled = false,
            imeSelected = false,
            attemptedEnable = false,
            attemptedSelect = false
        )
        assertTrue(finished.completed)
        assertEquals(OnboardingPolicy.Step.COMPLETION, finished.step)
    }

    @Test
    fun testStepWireParsingDefaultsSafelyToIntroduction() {
        assertEquals(OnboardingPolicy.Step.INTRODUCTION, OnboardingPolicy.Step.fromWire(null))
        assertEquals(OnboardingPolicy.Step.INTRODUCTION, OnboardingPolicy.Step.fromWire("unknown"))
        assertEquals(OnboardingPolicy.Step.ENABLE_KEYBOARD, OnboardingPolicy.Step.fromWire("enable_keyboard"))
        assertEquals(OnboardingPolicy.Step.SELECT_KEYBOARD, OnboardingPolicy.Step.fromWire("SELECT_KEYBOARD"))
        assertEquals(OnboardingPolicy.Step.COMPLETION, OnboardingPolicy.Step.fromWire("COMPLETION"))
    }
}
