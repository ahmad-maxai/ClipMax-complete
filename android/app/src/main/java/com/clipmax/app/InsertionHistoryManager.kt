package com.clipmax.app

import java.util.ArrayDeque

/**
 * Tracks host-application text insertions (clipboard pastes and automatic spelling corrections)
 * completely separately from ClipStore's card Merge/Split history.
 *
 * Enforces strict surrounding-text and editor-identity verification before modifying any host
 * application text so moved cursors, manual edits, uncooperative InputConnections, or switched apps
 * never delete unrelated user text.
 */
internal class InsertionHistoryManager(
    private val maxHistorySize: Int = MAX_HISTORY_ENTRIES,
    private val maxTrackedChars: Int = MAX_TRACKED_INSERTION_CHARS
) {
    companion object {
        const val MAX_HISTORY_ENTRIES = 25
        const val MAX_TRACKED_INSERTION_CHARS = 100_000
        const val ANCHOR_LENGTH = 48
    }

    enum class ActionKind {
        CLIP_INSERT,
        AUTOCORRECT
    }

    data class EditorTarget(
        val packageName: String?,
        val fieldId: Int,
        val inputType: Int,
        val sessionId: Long = 0L
    ) {
        fun isCompatibleWith(other: EditorTarget): Boolean {
            if (packageName != other.packageName) return false
            if (sessionId != other.sessionId) return false
            if ((inputType and InputFieldPolicy.TYPE_MASK_CLASS) != (other.inputType and InputFieldPolicy.TYPE_MASK_CLASS)) {
                return false
            }
            if (fieldId != 0 && other.fieldId != 0 && fieldId != other.fieldId) {
                return false
            }
            return true
        }
    }

    data class HistoryEntry(
        val kind: ActionKind,
        val target: EditorTarget,
        val originalText: String,
        val insertedText: String,
        val rawOriginalWord: String,
        val beforeAnchor: String,
        val afterAnchor: String
    )

    data class Availability(
        val allowed: Boolean,
        val reason: String? = null
    )

    data class OperationResult(
        val success: Boolean,
        val message: String,
        val entry: HistoryEntry? = null
    )

    interface HostEditorGateway {
        fun currentTarget(): EditorTarget
        fun isPasswordField(): Boolean
        fun hasActiveSelection(): Boolean
        fun textBeforeCursor(maxChars: Int): String?
        fun textAfterCursor(maxChars: Int): String?
        fun deleteBeforeCursor(length: Int): Boolean
        fun commitText(text: String): Boolean
        fun runBatchEdit(block: () -> Boolean): Boolean
    }

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private var immediateBackspaceAutocorrectActive = false

    fun hasAnyUndoEntries(): Boolean = undoStack.isNotEmpty()
    fun hasAnyRedoEntries(): Boolean = redoStack.isNotEmpty()

    fun peekUndo(): HistoryEntry? = undoStack.peekFirst()
    fun peekRedo(): HistoryEntry? = redoStack.peekFirst()

    fun invalidateImmediateBackspaceRevert() {
        immediateBackspaceAutocorrectActive = false
    }

    fun clearForTargetChangeIfNeeded(newTarget: EditorTarget) {
        val topUndo = undoStack.peekFirst()
        val topRedo = redoStack.peekFirst()
        if ((topUndo != null && !topUndo.target.isCompatibleWith(newTarget)) ||
            (topRedo != null && !topRedo.target.isCompatibleWith(newTarget))
        ) {
            immediateBackspaceAutocorrectActive = false
        }
    }

    fun recordClipInsertion(
        target: EditorTarget,
        insertedText: String,
        beforeAnchor: String,
        afterAnchor: String
    ): Boolean {
        immediateBackspaceAutocorrectActive = false
        if (insertedText.isEmpty() || insertedText.length > maxTrackedChars) {
            return false
        }
        pushUndo(
            HistoryEntry(
                kind = ActionKind.CLIP_INSERT,
                target = target,
                originalText = "",
                insertedText = insertedText,
                rawOriginalWord = "",
                beforeAnchor = beforeAnchor.takeLast(ANCHOR_LENGTH),
                afterAnchor = afterAnchor.take(ANCHOR_LENGTH)
            )
        )
        redoStack.clear()
        return true
    }

    fun recordAutocorrect(
        target: EditorTarget,
        rawOriginalWord: String,
        correctedWord: String,
        delimiter: String,
        beforeAnchor: String,
        afterAnchor: String
    ): Boolean {
        val originalWithDelim = rawOriginalWord + delimiter
        val correctedWithDelim = correctedWord + delimiter
        if (rawOriginalWord.isEmpty() || correctedWithDelim.isEmpty() || correctedWithDelim.length > maxTrackedChars) {
            return false
        }
        pushUndo(
            HistoryEntry(
                kind = ActionKind.AUTOCORRECT,
                target = target,
                originalText = originalWithDelim,
                insertedText = correctedWithDelim,
                rawOriginalWord = rawOriginalWord,
                beforeAnchor = beforeAnchor.takeLast(ANCHOR_LENGTH),
                afterAnchor = afterAnchor.take(ANCHOR_LENGTH)
            )
        )
        redoStack.clear()
        immediateBackspaceAutocorrectActive = true
        return true
    }

    private fun normalizeLineEndings(text: String): String {
        if (!text.contains('\r')) return text
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun suffixMatchesAnchor(prefix: String, anchor: String): Boolean {
        if (anchor.isEmpty()) return true
        val rawExpected = if (prefix.length >= anchor.length) anchor else anchor.takeLast(prefix.length)
        if (rawExpected.isEmpty() || prefix.endsWith(rawExpected)) return true
        val normAnchor = normalizeLineEndings(anchor)
        if (normAnchor != anchor) {
            val normExpected = if (prefix.length >= normAnchor.length) normAnchor else normAnchor.takeLast(prefix.length)
            if (normExpected.isEmpty() || prefix.endsWith(normExpected)) return true
        }
        return false
    }

    private fun resolveUndoMatchedText(before: String, entry: HistoryEntry): String? {
        if (before.length >= entry.insertedText.length && before.endsWith(entry.insertedText)) {
            val prefix = before.substring(0, before.length - entry.insertedText.length)
            if (suffixMatchesAnchor(prefix, entry.beforeAnchor)) {
                return entry.insertedText
            }
        }
        val normInserted = normalizeLineEndings(entry.insertedText)
        if (normInserted != entry.insertedText && before.length >= normInserted.length && before.endsWith(normInserted)) {
            val prefix = before.substring(0, before.length - normInserted.length)
            if (suffixMatchesAnchor(prefix, entry.beforeAnchor)) {
                return normInserted
            }
        }
        return null
    }

    private fun resolveRedoMatchedOriginal(before: String, entry: HistoryEntry): String? {
        if (entry.originalText.isEmpty()) {
            return if (suffixMatchesAnchor(before, entry.beforeAnchor)) "" else null
        }
        if (before.length >= entry.originalText.length && before.endsWith(entry.originalText)) {
            val prefix = before.substring(0, before.length - entry.originalText.length)
            if (suffixMatchesAnchor(prefix, entry.beforeAnchor)) {
                return entry.originalText
            }
        }
        val normOrig = normalizeLineEndings(entry.originalText)
        if (normOrig != entry.originalText && before.length >= normOrig.length && before.endsWith(normOrig)) {
            val prefix = before.substring(0, before.length - normOrig.length)
            if (suffixMatchesAnchor(prefix, entry.beforeAnchor)) {
                return normOrig
            }
        }
        return null
    }

    private fun checkAfterAnchor(gateway: HostEditorGateway, afterAnchor: String): Availability? {
        if (afterAnchor.isEmpty()) return null
        val after = gateway.textAfterCursor(afterAnchor.length)
            ?: return Availability(false, "App did not expose trailing text.")
        if (after.startsWith(afterAnchor)) return null
        val normAnchor = normalizeLineEndings(afterAnchor)
        if (normAnchor != afterAnchor && after.startsWith(normAnchor)) return null
        return Availability(false, "Trailing text changed; cannot safely modify.")
    }

    fun canSafelyUndo(gateway: HostEditorGateway): Availability {
        val entry = undoStack.peekFirst() ?: return Availability(false, "Nothing to undo.")
        if (gateway.isPasswordField()) {
            return Availability(false, "Insertion undo disabled in password fields.")
        }
        if (!entry.target.isCompatibleWith(gateway.currentTarget())) {
            return Availability(false, "Input field changed; cannot safely undo.")
        }
        if (gateway.hasActiveSelection()) {
            return Availability(false, "Text is selected; cannot safely undo.")
        }

        val requestLen = entry.insertedText.length + entry.beforeAnchor.length
        val before = gateway.textBeforeCursor(requestLen)
            ?: return Availability(false, "App did not expose surrounding text.")

        val matched = resolveUndoMatchedText(before, entry)
        if (matched == null) {
            return Availability(false, "Inserted text or cursor position changed.")
        }

        checkAfterAnchor(gateway, entry.afterAnchor)?.let { return it }

        return Availability(true, null)
    }

    fun canSafelyRedo(gateway: HostEditorGateway): Availability {
        val entry = redoStack.peekFirst() ?: return Availability(false, "Nothing to redo.")
        if (gateway.isPasswordField()) {
            return Availability(false, "Insertion redo disabled in password fields.")
        }
        if (!entry.target.isCompatibleWith(gateway.currentTarget())) {
            return Availability(false, "Input field changed; cannot safely redo.")
        }
        if (gateway.hasActiveSelection()) {
            return Availability(false, "Text is selected; cannot safely redo.")
        }

        val expectedTailLen = entry.beforeAnchor.length + entry.originalText.length
        if (expectedTailLen > 0) {
            val before = gateway.textBeforeCursor(expectedTailLen)
                ?: return Availability(false, "App did not expose surrounding text.")
            if (resolveRedoMatchedOriginal(before, entry) == null) {
                return Availability(false, "Cursor moved or text changed; cannot safely redo.")
            }
        } else {
            // Confirm host still supports reading surrounding text cleanly
            gateway.textBeforeCursor(16)
                ?: return Availability(false, "App did not expose surrounding text.")
        }

        checkAfterAnchor(gateway, entry.afterAnchor)?.let { return it }

        return Availability(true, null)
    }

    fun undo(gateway: HostEditorGateway): OperationResult {
        val check = canSafelyUndo(gateway)
        if (!check.allowed) {
            return OperationResult(false, check.reason ?: "Undo unavailable.")
        }
        val entry = undoStack.peekFirst() ?: return OperationResult(false, "Nothing to undo.")
        val requestLen = entry.insertedText.length + entry.beforeAnchor.length
        val before = gateway.textBeforeCursor(requestLen)
            ?: return OperationResult(false, "App did not expose surrounding text.")
        val matchedInserted = resolveUndoMatchedText(before, entry)
            ?: return OperationResult(false, "Inserted text or cursor position changed.")

        val ok = gateway.runBatchEdit {
            val deleted = gateway.deleteBeforeCursor(matchedInserted.length)
            if (!deleted) return@runBatchEdit false
            if (entry.originalText.isNotEmpty()) {
                gateway.commitText(entry.originalText)
            } else {
                true
            }
        }
        if (!ok) {
            return OperationResult(false, "Destination app rejected undo.")
        }
        undoStack.removeFirst()
        pushRedo(entry)
        immediateBackspaceAutocorrectActive = false
        val label = if (entry.kind == ActionKind.AUTOCORRECT) {
            "Reverted correction to \"${entry.rawOriginalWord}\""
        } else {
            "Undid clip insertion (${matchedInserted.length} chars)"
        }
        return OperationResult(true, label, entry)
    }

    fun redo(gateway: HostEditorGateway): OperationResult {
        val check = canSafelyRedo(gateway)
        if (!check.allowed) {
            return OperationResult(false, check.reason ?: "Redo unavailable.")
        }
        val entry = redoStack.peekFirst() ?: return OperationResult(false, "Nothing to redo.")
        val expectedTailLen = entry.beforeAnchor.length + entry.originalText.length
        val matchedOriginal = if (expectedTailLen > 0) {
            val before = gateway.textBeforeCursor(expectedTailLen)
                ?: return OperationResult(false, "App did not expose surrounding text.")
            resolveRedoMatchedOriginal(before, entry)
                ?: return OperationResult(false, "Cursor moved or text changed; cannot safely redo.")
        } else ""

        val ok = gateway.runBatchEdit {
            if (matchedOriginal.isNotEmpty()) {
                if (!gateway.deleteBeforeCursor(matchedOriginal.length)) return@runBatchEdit false
            }
            gateway.commitText(entry.insertedText)
        }
        if (!ok) {
            return OperationResult(false, "Destination app rejected redo.")
        }
        redoStack.removeFirst()
        pushUndo(entry)
        immediateBackspaceAutocorrectActive = false
        val label = if (entry.kind == ActionKind.AUTOCORRECT) {
            "Restored correction"
        } else {
            "Redid clip insertion (${entry.insertedText.length} chars)"
        }
        return OperationResult(true, label, entry)
    }

    /**
     * Rolls back the most recent undo transition if an optimistic asynchronous InputConnection
     * silently ignored or rejected the deletion in the host editor.
     */
    fun rollbackLastUndo(entry: HistoryEntry): Boolean {
        if (redoStack.peekFirst() !== entry) return false
        redoStack.removeFirst()
        pushUndo(entry)
        return true
    }

    /**
     * Rolls back the most recent redo transition if an optimistic asynchronous InputConnection
     * silently ignored or rejected the re-insertion in the host editor.
     */
    fun rollbackLastRedo(entry: HistoryEntry): Boolean {
        if (undoStack.peekFirst() !== entry) return false
        undoStack.removeFirst()
        pushRedo(entry)
        return true
    }

    /**
     * Supports standard immediate-backspace undo right after an automatic spelling correction:
     * deletes "correctedWord + delimiter" and commits "rawOriginalWord" without the delimiter.
     * Returns the restored original word if reverted, or null if normal backspace should proceed.
     */
    fun tryImmediateBackspaceRevertAutocorrect(gateway: HostEditorGateway): String? {
        if (!immediateBackspaceAutocorrectActive) return null
        val entry = undoStack.peekFirst() ?: run {
            immediateBackspaceAutocorrectActive = false
            return null
        }
        if (entry.kind != ActionKind.AUTOCORRECT) {
            immediateBackspaceAutocorrectActive = false
            return null
        }
        val check = canSafelyUndo(gateway)
        if (!check.allowed) {
            immediateBackspaceAutocorrectActive = false
            return null
        }
        val before = gateway.textBeforeCursor(entry.insertedText.length + entry.beforeAnchor.length) ?: run {
            immediateBackspaceAutocorrectActive = false
            return null
        }
        val matched = resolveUndoMatchedText(before, entry) ?: run {
            immediateBackspaceAutocorrectActive = false
            return null
        }
        val ok = gateway.runBatchEdit {
            if (!gateway.deleteBeforeCursor(matched.length)) return@runBatchEdit false
            gateway.commitText(entry.rawOriginalWord)
        }
        immediateBackspaceAutocorrectActive = false
        if (!ok) return null
        undoStack.removeFirst()
        return entry.rawOriginalWord
    }

    private fun pushUndo(entry: HistoryEntry) {
        undoStack.addFirst(entry)
        while (undoStack.size > maxHistorySize) {
            undoStack.removeLast()
        }
    }

    private fun pushRedo(entry: HistoryEntry) {
        redoStack.addFirst(entry)
        while (redoStack.size > maxHistorySize) {
            redoStack.removeLast()
        }
    }
}
