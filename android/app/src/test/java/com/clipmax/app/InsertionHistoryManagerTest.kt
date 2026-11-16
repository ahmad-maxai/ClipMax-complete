package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class InsertionHistoryManagerTest {

    private class FakeHostEditorGateway(
        initialText: String = "",
        initialCursor: Int = initialText.length,
        var target: InsertionHistoryManager.EditorTarget = InsertionHistoryManager.EditorTarget("com.example.notes", 1, 1),
        var passwordField: Boolean = false,
        var selectionActive: Boolean = false,
        var exposeContext: Boolean = true
    ) : InsertionHistoryManager.HostEditorGateway {
        val buffer = StringBuilder(initialText)
        var cursor = initialCursor

        override fun currentTarget() = target
        override fun isPasswordField() = passwordField
        override fun hasActiveSelection() = selectionActive

        override fun textBeforeCursor(maxChars: Int): String? {
            if (!exposeContext) return null
            val start = (cursor - maxChars).coerceAtLeast(0)
            return buffer.substring(start, cursor)
        }

        override fun textAfterCursor(maxChars: Int): String? {
            if (!exposeContext) return null
            val end = (cursor + maxChars).coerceAtMost(buffer.length)
            return buffer.substring(cursor, end)
        }

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
    fun testClipInsertionUndoRedoPreservesUnrelatedSurroundingText() {
        val history = InsertionHistoryManager()
        val target = InsertionHistoryManager.EditorTarget("com.example.notes", 10, 1)
        val editor = FakeHostEditorGateway(initialText = "Header: [TAIL]", initialCursor = 8, target = target)

        val clip = "Meeting at 3pm"
        val beforeAnchor = editor.textBeforeCursor(48).orEmpty()
        val afterAnchor = editor.textAfterCursor(48).orEmpty()
        assertTrue(editor.commitText(clip))
        assertEquals("Header: Meeting at 3pm[TAIL]", editor.buffer.toString())

        assertTrue(history.recordClipInsertion(target, clip, beforeAnchor, afterAnchor))
        assertTrue(history.canSafelyUndo(editor).allowed)

        val undoRes = history.undo(editor)
        assertTrue(undoRes.success)
        assertEquals("Header: [TAIL]", editor.buffer.toString())
        assertEquals(8, editor.cursor)
        assertFalse(history.canSafelyUndo(editor).allowed)
        assertTrue(history.canSafelyRedo(editor).allowed)

        val redoRes = history.redo(editor)
        assertTrue(redoRes.success)
        assertEquals("Header: Meeting at 3pm[TAIL]", editor.buffer.toString())
        assertTrue(history.canSafelyUndo(editor).allowed)
    }

    @Test
    fun testUndoRefusesWhenCursorMovesOrTextIsExternallyModified() {
        val history = InsertionHistoryManager()
        val target = InsertionHistoryManager.EditorTarget("com.example.notes", 10, 1)
        val editor = FakeHostEditorGateway(initialText = "Start ", initialCursor = 6, target = target)

        val clip = "Alpha Beta"
        val beforeAnchor = editor.textBeforeCursor(48).orEmpty()
        val afterAnchor = editor.textAfterCursor(48).orEmpty()
        editor.commitText(clip)
        history.recordClipInsertion(target, clip, beforeAnchor, afterAnchor)

        // 1. User moves cursor mid-clip
        editor.cursor = 11
        assertFalse(history.canSafelyUndo(editor).allowed)
        val failMove = history.undo(editor)
        assertFalse(failMove.success)
        assertEquals("Start Alpha Beta", editor.buffer.toString())

        // 2. User returns cursor to end and modifies last character externally
        editor.cursor = editor.buffer.length
        editor.buffer.setCharAt(editor.buffer.length - 1, 'X')
        assertFalse(history.canSafelyUndo(editor).allowed)
        val failEdit = history.undo(editor)
        assertFalse(failEdit.success)
        assertEquals("Start Alpha BetX", editor.buffer.toString())
    }

    @Test
    fun testUndoRefusesAcrossSwitchedAppOrUncooperativeHost() {
        val history = InsertionHistoryManager()
        val appA = InsertionHistoryManager.EditorTarget("com.app.a", 1, 1)
        val appB = InsertionHistoryManager.EditorTarget("com.app.b", 1, 1)
        val editor = FakeHostEditorGateway(initialText = "", target = appA)

        val clip = "Secret Note"
        editor.commitText(clip)
        history.recordClipInsertion(appA, clip, "", "")

        // Uncooperative host returns null for textBeforeCursor
        editor.exposeContext = false
        assertFalse(history.canSafelyUndo(editor).allowed)
        assertFalse(history.undo(editor).success)
        assertEquals("Secret Note", editor.buffer.toString())

        // Restore context, switch to App B
        editor.exposeContext = true
        editor.target = appB
        assertFalse(history.canSafelyUndo(editor).allowed)
        assertFalse(history.undo(editor).success)
        assertEquals("Secret Note", editor.buffer.toString())
    }

    @Test
    fun testAutocorrectToolbarUndoRedoAndImmediateBackspaceRevert() {
        val history = InsertionHistoryManager()
        val target = InsertionHistoryManager.EditorTarget("com.example.chat", 5, 1)
        val editor = FakeHostEditorGateway(initialText = "I ", target = target)

        // User typed "recieved", autocorrected to "received "
        editor.commitText("received ")
        history.recordAutocorrect(
            target = target,
            rawOriginalWord = "recieved",
            correctedWord = "received",
            delimiter = " ",
            beforeAnchor = "I ",
            afterAnchor = ""
        )

        // Immediate backspace reverts "received " -> "recieved"
        val reverted = history.tryImmediateBackspaceRevertAutocorrect(editor)
        assertEquals("recieved", reverted)
        assertEquals("I recieved", editor.buffer.toString())
        assertNull(history.tryImmediateBackspaceRevertAutocorrect(editor))

        // Separate toolbar Undo/Redo flow for autocorrect
        editor.deleteBeforeCursor("recieved".length)
        editor.commitText("received ")
        history.recordAutocorrect(target, "recieved", "received", " ", "I ", "")

        val undo = history.undo(editor)
        assertTrue(undo.success)
        assertEquals("I recieved ", editor.buffer.toString())

        val redo = history.redo(editor)
        assertTrue(redo.success)
        assertEquals("I received ", editor.buffer.toString())
    }

    @Test
    fun testClipStoreMergeSplitHistoryRemainsCompletelyIndependent() {
        val tempDir = Files.createTempDirectory("clipmax_hist_sep").toFile()
        try {
            val store = ClipStore(tempDir)
            val a = store.save(null, "A", "First card")
            val b = store.save(null, "B", "Second card")
            val merged = store.merge(a.id, b.id)

            val history = InsertionHistoryManager()
            val target = InsertionHistoryManager.EditorTarget("com.example.notes", 2, 1)
            val editor = FakeHostEditorGateway(initialText = "", target = target)

            val pasted = store.readContent(merged.mergedItem)
            editor.commitText(pasted)
            history.recordClipInsertion(target, pasted, "", "")

            // Undoing host insertion does NOT alter ClipStore merged card
            assertTrue(history.undo(editor).success)
            assertEquals("", editor.buffer.toString())
            assertEquals("First card\nSecond card", store.readContent(store.getItem(merged.mergedItem.id)!!))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
