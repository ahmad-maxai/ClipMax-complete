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
        var exposeContext: Boolean = true,
        var normalizeCrlf: Boolean = false
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
            val applied = if (normalizeCrlf) text.replace("\r\n", "\n").replace('\r', '\n') else text
            buffer.insert(cursor, applied)
            cursor += applied.length
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

    @Test
    fun testComposeRestartInputCompatibilityAcrossDynamicFlags() {
        val history = InsertionHistoryManager()
        // Jetpack Compose apps like ChatGPT often restart input with dynamic fieldId/inputType flags
        val initialTarget = InsertionHistoryManager.EditorTarget("com.openai.chatgpt", 0, 0x20001, sessionId = 7L)
        val restartedTarget = InsertionHistoryManager.EditorTarget("com.openai.chatgpt", 0, 0x24001, sessionId = 7L)
        val switchedFieldSameApp = InsertionHistoryManager.EditorTarget("com.openai.chatgpt", 0, 0x20001, sessionId = 8L)

        val editor = FakeHostEditorGateway(initialText = "Prompt: ", target = initialTarget)
        val clip = "Summarize this paragraph."
        val beforeAnchor = editor.textBeforeCursor(48).orEmpty()
        val afterAnchor = editor.textAfterCursor(48).orEmpty()
        assertTrue(editor.commitText(clip))
        assertTrue(history.recordClipInsertion(initialTarget, clip, beforeAnchor, afterAnchor))

        // Same session restart preserves undo/redo eligibility
        editor.target = restartedTarget
        assertTrue(history.canSafelyUndo(editor).allowed)
        assertTrue(history.undo(editor).success)
        assertEquals("Prompt: ", editor.buffer.toString())
        assertTrue(history.canSafelyRedo(editor).allowed)
        assertTrue(history.redo(editor).success)
        assertEquals("Prompt: Summarize this paragraph.", editor.buffer.toString())

        // Focusing a different field (new sessionId) blocks cross-field undo
        editor.target = switchedFieldSameApp
        assertFalse(history.canSafelyUndo(editor).allowed)
    }

    @Test
    fun testHostLineEndingNormalizationAndRollbackOnOptimisticRejection() {
        val history = InsertionHistoryManager()
        val target = InsertionHistoryManager.EditorTarget("com.openai.chatgpt", 1, 1, sessionId = 3L)
        val editor = FakeHostEditorGateway(initialText = "Intro\n", target = target, normalizeCrlf = true)

        // Saved clip contains CRLF, and host editor normalizes newlines to LF
        val clipWithCrlf = "Line 1\r\nLine 2"
        assertTrue(editor.commitText(clipWithCrlf))
        assertEquals("Intro\nLine 1\nLine 2", editor.buffer.toString())
        assertTrue(history.recordClipInsertion(target, clipWithCrlf, "Intro\r\n", ""))

        assertTrue(history.canSafelyUndo(editor).allowed)
        val undoRes = history.undo(editor)
        assertTrue(undoRes.success)
        // Must delete exact 13 normalized chars ("Line 1\nLine 2"), not 14, preserving "Intro\n"
        assertEquals("Intro\n", editor.buffer.toString())

        // Redo restores normalized clip cleanly
        assertTrue(history.redo(editor).success)
        assertEquals("Intro\nLine 1\nLine 2", editor.buffer.toString())

        // Rollback when an optimistic IPC InputConnection returns true but silently ignores delete
        val rejectingGateway = object : InsertionHistoryManager.HostEditorGateway {
            val fixedText = "Intro\nLine 1\nLine 2"
            override fun currentTarget() = target
            override fun isPasswordField() = false
            override fun hasActiveSelection() = false
            override fun textBeforeCursor(maxChars: Int) = fixedText.takeLast(maxChars)
            override fun textAfterCursor(maxChars: Int) = ""
            override fun deleteBeforeCursor(length: Int) = true
            override fun commitText(text: String) = true
            override fun runBatchEdit(block: () -> Boolean) = block()
        }
        val history2 = InsertionHistoryManager()
        assertTrue(history2.recordClipInsertion(target, clipWithCrlf, "Intro\r\n", ""))
        val optimisticUndo = history2.undo(rejectingGateway)
        assertTrue(optimisticUndo.success)
        assertTrue(history2.rollbackLastUndo(optimisticUndo.entry!!))
        assertFalse(history2.hasAnyRedoEntries())
        assertEquals(optimisticUndo.entry, history2.peekUndo())

        // Rollback after redo on silent rejection
        assertTrue(history.undo(editor).success)
        val redoRes = history.redo(editor)
        assertTrue(redoRes.success)
        assertTrue(history.rollbackLastRedo(redoRes.entry!!))
        assertFalse(history.hasAnyUndoEntries())
        assertTrue(history.hasAnyRedoEntries())
    }
}
