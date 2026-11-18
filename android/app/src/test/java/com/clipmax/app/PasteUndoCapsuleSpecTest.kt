package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PasteUndoCapsuleSpecTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeEditorGateway(
        initialText: String = "",
        var target: InsertionHistoryManager.EditorTarget = InsertionHistoryManager.EditorTarget("com.example.app", 1, 1, 1L)
    ) : InsertionHistoryManager.HostEditorGateway {
        val buffer = StringBuilder(initialText)
        var cursor = initialText.length
        var hasSelection = false

        override fun currentTarget() = target
        override fun isPasswordField() = false
        override fun hasActiveSelection() = hasSelection
        override fun textBeforeCursor(maxChars: Int): String =
            buffer.substring((cursor - maxChars).coerceAtLeast(0), cursor)
        override fun textAfterCursor(maxChars: Int): String =
            buffer.substring(cursor, (cursor + maxChars).coerceAtMost(buffer.length))
        override fun deleteBeforeCursor(length: Int): Boolean {
            if (length < 0 || length > cursor) return false
            buffer.delete(cursor - length, cursor)
            cursor -= length
            return true
        }
        override fun commitText(text: String): Boolean {
            buffer.insert(cursor, text)
            cursor += text.length
            return true
        }
        override fun runBatchEdit(block: () -> Boolean): Boolean = block()
    }

    @Test
    fun testInitialStateHasNoCapsule() {
        val controller = PasteUndoCapsuleSpec.PasteUndoCapsuleController()
        assertFalse(controller.isActive)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.HIDDEN, controller.currentState)
        assertEquals("", controller.currentConfig.label)
        assertNull(controller.currentConfig.action)
    }

    @Test
    fun testSuccessfulPasteImmediatelyDisplaysUndoAndPersistsWithoutTimer() {
        val controller = PasteUndoCapsuleSpec.PasteUndoCapsuleController()
        val gateway = FakeEditorGateway("Hello ")
        val history = InsertionHistoryManager()

        val clip = "ClipMax"
        val beforeAnchor = gateway.textBeforeCursor(InsertionHistoryManager.ANCHOR_LENGTH)
        val afterAnchor = gateway.textAfterCursor(InsertionHistoryManager.ANCHOR_LENGTH)
        assertTrue(gateway.commitText(clip))
        val recorded = history.recordClipInsertion(gateway.currentTarget(), clip, beforeAnchor, afterAnchor)
        assertTrue(recorded)

        controller.onPasteSucceeded(nowMs = 1_000L)
        assertTrue(controller.isActive)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.UNDO_AVAILABLE, controller.currentState)
        assertEquals("Undo", controller.currentConfig.label)
        assertEquals(PasteUndoCapsuleSpec.CapsuleAction.UNDO, controller.currentConfig.action)

        // Remains visible indefinitely even after long elapsed time with no unrelated user input
        val dismissedDuringWait = controller.onEditorSelectionChanged(
            nowMs = 120_000L,
            hasSelection = false,
            cursorMoved = false,
            canUndoNow = history.canSafelyUndo(gateway).allowed,
            canRedoNow = history.canSafelyRedo(gateway).allowed
        )
        assertFalse(dismissedDuringWait)
        assertTrue(controller.isActive)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.UNDO_AVAILABLE, controller.currentState)
    }

    @Test
    fun testUndoAndRedoRoundTripWithInsertionHistoryManager() {
        val controller = PasteUndoCapsuleSpec.PasteUndoCapsuleController()
        val gateway = FakeEditorGateway("Start: ")
        val history = InsertionHistoryManager()

        val clip = "PastedSnippet"
        val beforeAnchor = gateway.textBeforeCursor(InsertionHistoryManager.ANCHOR_LENGTH)
        val afterAnchor = gateway.textAfterCursor(InsertionHistoryManager.ANCHOR_LENGTH)
        gateway.commitText(clip)
        assertTrue(history.recordClipInsertion(gateway.currentTarget(), clip, beforeAnchor, afterAnchor))
        controller.onPasteSucceeded(nowMs = 1_000L)

        // Tap Undo
        val undoRes = history.undo(gateway)
        assertTrue(undoRes.success)
        assertEquals("Start: ", gateway.buffer.toString())
        controller.onUndoSucceeded(nowMs = 2_000L)
        assertTrue(controller.isActive)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.REDO_AVAILABLE, controller.currentState)
        assertEquals("Redo", controller.currentConfig.label)
        assertEquals(PasteUndoCapsuleSpec.CapsuleAction.REDO, controller.currentConfig.action)

        // Tap Redo -> restores text and restores Undo (since entry is back on undoStack)
        val redoRes = history.redo(gateway)
        assertTrue(redoRes.success)
        assertEquals("Start: PastedSnippet", gateway.buffer.toString())
        controller.onRedoSucceeded(canUndoRemaining = history.hasAnyUndoEntries(), nowMs = 3_000L)
        assertTrue(controller.isActive)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.UNDO_AVAILABLE, controller.currentState)
        assertEquals("Undo", controller.currentConfig.label)

        // If Redo occurs with no remaining undos, capsule dismisses
        controller.onRedoSucceeded(canUndoRemaining = false, nowMs = 4_000L)
        assertFalse(controller.isActive)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.HIDDEN, controller.currentState)
    }

    @Test
    fun testUndoAndRedoHaveIdenticalFixedGeometry() {
        // Capsule uses single fixed host geometry regardless of Undo vs Redo state
        val undoCfg = PasteUndoCapsuleSpec.resolveConfig(isActive = true, isRedoMode = false)
        val redoCfg = PasteUndoCapsuleSpec.resolveConfig(isActive = true, isRedoMode = true)
        assertEquals("Undo", undoCfg.label)
        assertEquals("Redo", redoCfg.label)
        assertEquals(16f, PasteUndoCapsuleSpec.CORNER_RADIUS_DP, 0.001f)
        assertEquals(32f, PasteUndoCapsuleSpec.HEIGHT_DP, 0.001f)
        assertEquals(88f, PasteUndoCapsuleSpec.WIDTH_DP, 0.001f)
        assertEquals(42f, PasteUndoCapsuleSpec.RIGHT_MARGIN_DP, 0.001f)
    }

    @Test
    fun testTypingModeSwitchAndTappingElsewhereDismissCapsule() {
        val controller = PasteUndoCapsuleSpec.PasteUndoCapsuleController()
        controller.onPasteSucceeded(nowMs = 1_000L)
        assertTrue(controller.isActive)

        // Self-update selection callback within grace period (e.g. from commitText) must NOT dismiss
        assertFalse(
            controller.onEditorSelectionChanged(
                nowMs = 1_120L,
                hasSelection = false,
                cursorMoved = true,
                canUndoNow = true,
                canRedoNow = false
            )
        )
        assertTrue(controller.isActive)

        // Tapping elsewhere after grace period dismisses
        assertTrue(
            controller.onEditorSelectionChanged(
                nowMs = 2_000L,
                hasSelection = false,
                cursorMoved = true,
                canUndoNow = false,
                canRedoNow = false
            )
        )
        assertFalse(controller.isActive)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.HIDDEN, controller.currentState)

        // Re-paste -> typing / mode switch dismisses
        controller.onPasteSucceeded(nowMs = 3_000L)
        assertTrue(controller.isActive)
        controller.dismiss()
        assertFalse(controller.isActive)
    }

    @Test
    fun testNewPasteResetsRedoModeToUndoAndFailedPasteStaysHidden() {
        val controller = PasteUndoCapsuleSpec.PasteUndoCapsuleController()
        controller.onPasteSucceeded(nowMs = 1_000L)
        controller.onUndoSucceeded(nowMs = 1_500L)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.REDO_AVAILABLE, controller.currentState)

        // New paste while in Redo resets back to Undo
        controller.onPasteSucceeded(nowMs = 2_000L)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.UNDO_AVAILABLE, controller.currentState)
        assertEquals("Undo", controller.currentConfig.label)

        // Failed paste does not show capsule
        controller.onPasteFailed()
        assertFalse(controller.isActive)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.HIDDEN, controller.currentState)
    }

    @Test
    fun testSplitMergeHistoryRemainsIndependentFromPasteCapsule() {
        val store = ClipStore(tempFolder.newFolder("clipstore_capsule_indep"))
        val a = store.save(null, "First", "First sentence for split merge check", false)
        Thread.sleep(5)
        val b = store.save(null, "Second", "Second sentence for split merge check", false)

        val mergeRes = store.merge(a.id, b.id)
        assertEquals(1, store.listItems().size)
        assertEquals(a.id, mergeRes.sourceItem.id)
        assertEquals(b.id, mergeRes.targetItem.id)

        // Paste capsule controller + InsertionHistoryManager are completely independent of ClipStore Split/Merge state
        val history = InsertionHistoryManager()
        val gateway = FakeEditorGateway("")
        val capsule = PasteUndoCapsuleSpec.PasteUndoCapsuleController()
        assertFalse(capsule.isActive)
        assertFalse(history.hasAnyUndoEntries())

        gateway.commitText("PastedText")
        assertTrue(history.recordClipInsertion(gateway.currentTarget(), "PastedText", "", ""))
        capsule.onPasteSucceeded(nowMs = 500L)
        assertTrue(capsule.isActive)
        assertTrue(history.hasAnyUndoEntries())

        // Split/Merge store mutation does not dismiss or mutate paste undo stack/capsule
        val splitRes = store.split(mergeRes.mergedItem.id)!!
        assertEquals(2, store.listItems().size)
        assertEquals(splitRes.part1Item.id, store.listItems().firstOrNull { it.id == splitRes.part1Item.id }?.id)
        assertTrue(capsule.isActive)
        assertTrue(history.hasAnyUndoEntries())
    }

    @Test
    fun testLargeTextPasteDoesNotDismissFromDelayedPostPasteSelectionUpdate() {
        val controller = PasteUndoCapsuleSpec.PasteUndoCapsuleController()
        val history = InsertionHistoryManager()
        val gateway = FakeEditorGateway("Doc: ")

        val largeText = "Large clipboard item content ".repeat(200)
        gateway.commitText(largeText)
        assertTrue(history.recordClipInsertion(gateway.currentTarget(), largeText, "Doc: ", ""))

        controller.onPasteSucceeded(nowMs = 1_000L)
        assertTrue(controller.isActive)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.UNDO_AVAILABLE, controller.currentState)

        // Delayed selection update arrives at 800ms (nowMs = 1,800L, > 350ms SELF_UPDATE_GRACE_MS)
        // because host editor was formatting thousands of characters across IPC.
        // Since canUndoNow is true, capsule MUST NOT dismiss prematurely from an arbitrary timer.
        val dismissed = controller.onEditorSelectionChanged(
            nowMs = 1_800L,
            hasSelection = false,
            cursorMoved = true,
            canUndoNow = history.canSafelyUndo(gateway).allowed,
            canRedoNow = history.canSafelyRedo(gateway).allowed
        )
        assertFalse("Capsule must not dismiss when operation is still valid", dismissed)
        assertTrue(controller.isActive)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.UNDO_AVAILABLE, controller.currentState)

        // User taps Undo
        val undoRes = history.undo(gateway)
        assertTrue(undoRes.success)
        assertEquals("Doc: ", gateway.buffer.toString())
        controller.onUndoSucceeded(nowMs = 2_000L)
        assertTrue(controller.isActive)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.REDO_AVAILABLE, controller.currentState)

        // Another delayed selection update after Undo
        val dismissedAfterUndo = controller.onEditorSelectionChanged(
            nowMs = 3_000L,
            hasSelection = false,
            cursorMoved = true,
            canUndoNow = history.canSafelyUndo(gateway).allowed,
            canRedoNow = history.canSafelyRedo(gateway).allowed
        )
        assertFalse(dismissedAfterUndo)
        assertTrue(controller.isActive)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.REDO_AVAILABLE, controller.currentState)

        // Tapping elsewhere (moving cursor away) invalidates operation and dismisses
        val dismissedOnMove = controller.onEditorSelectionChanged(
            nowMs = 4_000L,
            hasSelection = false,
            cursorMoved = true,
            canUndoNow = false,
            canRedoNow = false
        )
        assertTrue(dismissedOnMove)
        assertFalse(controller.isActive)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.HIDDEN, controller.currentState)
    }

    @Test
    fun testActiveTextSelectionDismissesCapsuleImmediately() {
        val controller = PasteUndoCapsuleSpec.PasteUndoCapsuleController()
        controller.onPasteSucceeded(nowMs = 1_000L)
        assertTrue(controller.isActive)

        // Selecting text dismisses immediately
        val dismissed = controller.onEditorSelectionChanged(
            nowMs = 1_050L,
            hasSelection = true,
            cursorMoved = false,
            canUndoNow = true,
            canRedoNow = false
        )
        assertTrue(dismissed)
        assertFalse(controller.isActive)
        assertEquals(PasteUndoCapsuleSpec.CapsuleState.HIDDEN, controller.currentState)
    }
}
