package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteUndoCapsuleSpecTest {

    @Test
    fun testInitialStateIsHidden() {
        val config = PasteUndoCapsuleSpec.resolveConfig(isActive = false, isRedoMode = false)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.HIDDEN, config.state)
        assertEquals("", config.label)
        assertNull(config.action)
    }

    @Test
    fun testSuccessfulPasteShowsUndo() {
        val (isActive, isRedo) = PasteUndoCapsuleSpec.onPasteSuccess()
        assertTrue(isActive)
        assertFalse(isRedo)

        val config = PasteUndoCapsuleSpec.resolveConfig(isActive, isRedo)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.UNDO_AVAILABLE, config.state)
        assertEquals("Undo", config.label)
        assertEquals(PasteUndoCapsuleSpec.CapsuleAction.UNDO, config.action)
    }

    @Test
    fun testTapUndoTransitionsToRedoInPlace() {
        val (isActive, isRedo) = PasteUndoCapsuleSpec.onUndoSuccess()
        assertTrue(isActive)
        assertTrue(isRedo)

        val config = PasteUndoCapsuleSpec.resolveConfig(isActive, isRedo)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.REDO_AVAILABLE, config.state)
        assertEquals("Redo", config.label)
        assertEquals(PasteUndoCapsuleSpec.CapsuleAction.REDO, config.action)
    }

    @Test
    fun testRedoWithMoreUndosRestoresUndo() {
        val (isActive, isRedo) = PasteUndoCapsuleSpec.onRedoSuccess(canUndoRemaining = true)
        assertTrue(isActive)
        assertFalse(isRedo)

        val config = PasteUndoCapsuleSpec.resolveConfig(isActive, isRedo)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.UNDO_AVAILABLE, config.state)
        assertEquals("Undo", config.label)
        assertEquals(PasteUndoCapsuleSpec.CapsuleAction.UNDO, config.action)
    }

    @Test
    fun testRedoWithNoMoreUndosDismisses() {
        val (isActive, isRedo) = PasteUndoCapsuleSpec.onRedoSuccess(canUndoRemaining = false)
        assertFalse(isActive)
        assertFalse(isRedo)

        val config = PasteUndoCapsuleSpec.resolveConfig(isActive, isRedo)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.HIDDEN, config.state)
    }

    @Test
    fun testUnrelatedActionDismissesCapsuleFromUndoMode() {
        val (isActive, isRedo) = PasteUndoCapsuleSpec.onUnrelatedAction()
        assertFalse(isActive)
        assertFalse(isRedo)

        val config = PasteUndoCapsuleSpec.resolveConfig(isActive, isRedo)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.HIDDEN, config.state)
    }

    @Test
    fun testUnrelatedActionDismissesCapsuleFromRedoMode() {
        // Even if in redo mode, unrelated action resets both
        val (isActive, isRedo) = PasteUndoCapsuleSpec.onUnrelatedAction()
        assertFalse(isActive)
        assertFalse(isRedo)

        val config = PasteUndoCapsuleSpec.resolveConfig(isActive, isRedo)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.HIDDEN, config.state)
    }

    @Test
    fun testNewPasteWhileInRedoModeResetsToUndoMode() {
        // If in Redo mode and user pastes again
        val (isActive, isRedo) = PasteUndoCapsuleSpec.onPasteSuccess()
        assertTrue(isActive)
        assertFalse(isRedo)

        val config = PasteUndoCapsuleSpec.resolveConfig(isActive, isRedo)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.UNDO_AVAILABLE, config.state)
        assertEquals("Undo", config.label)
    }

    @Test
    fun testGeometryAndDimensionsConstants() {
        assertEquals(16f, PasteUndoCapsuleSpec.CORNER_RADIUS_DP, 0.001f)
        assertEquals(32f, PasteUndoCapsuleSpec.HEIGHT_DP, 0.001f)
        assertEquals(86f, PasteUndoCapsuleSpec.WIDTH_DP, 0.001f)
        assertEquals(42f, PasteUndoCapsuleSpec.RIGHT_MARGIN_DP, 0.001f)
        assertEquals(13.5f, PasteUndoCapsuleSpec.ICON_SIZE_DP, 0.001f)
        assertEquals(12.5f, PasteUndoCapsuleSpec.TEXT_SIZE_SP, 0.001f)
    }
}
