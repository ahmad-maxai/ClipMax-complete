package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutSpecTest {

    @Test
    fun testAllTenTopRowEmbeddedNumberMappings() {
        val expected = mapOf(
            "Q" to "1",
            "W" to "2",
            "E" to "3",
            "R" to "4",
            "T" to "5",
            "Y" to "6",
            "U" to "7",
            "I" to "8",
            "O" to "9",
            "P" to "0"
        )
        assertEquals(10, KeyboardLayoutSpec.TOP_ROW_EMBEDDED_NUMBERS.size)
        for ((letter, digit) in expected) {
            assertEquals(digit, KeyboardLayoutSpec.embeddedNumberFor(letter))
            assertEquals(digit, KeyboardLayoutSpec.embeddedNumberFor(letter.lowercase()))
        }
        assertNull(KeyboardLayoutSpec.embeddedNumberFor("A"))
        assertNull(KeyboardLayoutSpec.embeddedNumberFor("Z"))
    }

    @Test
    fun testErgonomicGeometryReferenceProportionsAndResizeScaling() {
        // Normal portrait phone (~840dp tall): Gboard reference proportions preserved
        val normal = KeyboardLayoutSpec.computeGeometryDp("normal", screenHeightDp = 840f, isLandscape = false)
        assertTrue(normal.keyHeightDp in 42f..46f)
        assertTrue(normal.interRowGapDp in 6f..8f)
        assertTrue(normal.horizontalGapDp in 4f..5f)
        assertTrue(normal.cornerRadiusDp in 8f..10f)
        assertEquals(8f, normal.topPaddingDp, 0.01f)
        assertEquals(8f, normal.bottomPaddingDp, 0.01f)

        val compact = KeyboardLayoutSpec.computeGeometryDp("compact", screenHeightDp = 840f, isLandscape = false)
        val tall = KeyboardLayoutSpec.computeGeometryDp("tall", screenHeightDp = 840f, isLandscape = false)
        assertTrue(compact.keyHeightDp < normal.keyHeightDp)
        assertTrue(tall.keyHeightDp > normal.keyHeightDp)

        // Landscape viewport clamps keyboard cleanly so it doesn't exceed viewport height
        val landscape = KeyboardLayoutSpec.computeGeometryDp("tall", screenHeightDp = 390f, isLandscape = true)
        val totalLandscapeRows = landscape.keyHeightDp * 4f + landscape.interRowGapDp * 3f +
            landscape.topPaddingDp + landscape.bottomPaddingDp
        assertTrue(totalLandscapeRows <= 390f * 0.65f)
    }

    @Test
    fun testLongPressStateMachinePreventsLetterDuplicateAndHoldRepeat() {
        val ctrl = KeyboardLayoutSpec.LongPressKeyController()

        // 1. Short normal tap commits primary letter only
        ctrl.onDown()
        assertFalse(ctrl.isLongPressTriggered())
        assertTrue(ctrl.onUp())

        // 2. Long-press fires digit ONCE, never repeats, and ACTION_UP does NOT commit letter
        ctrl.onDown()
        assertTrue(ctrl.onLongPressTimeout())
        assertTrue(ctrl.isLongPressTriggered())
        assertFalse(ctrl.onLongPressTimeout()) // Holding further must not repeat
        assertFalse(ctrl.onUp()) // Releasing must NOT also insert letter

        // 3. Sliding finger off key cancels both tap and timeout
        ctrl.onDown()
        assertTrue(ctrl.onMove(insideBounds = false))
        assertFalse(ctrl.onLongPressTimeout())
        assertFalse(ctrl.onUp())

        // 4. Rapid subsequent tap still works cleanly
        ctrl.onDown()
        assertTrue(ctrl.onUp())
    }
}
