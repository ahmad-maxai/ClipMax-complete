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
        val inputType: Int
    )

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
        if ((topUndo != null && topUndo.target != newTarget) ||
            (topRedo != null && topRedo.target != newTarget)
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

    fun canSafelyUndo(gateway: HostEditorGateway): Availability {
        val entry = undoStack.peekFirst() ?: return Availability(false, "Nothing to undo.")
        if (gateway.isPasswordField()) {
            return Availability(false, "Insertion undo disabled in password fields.")
        }
        if (entry.target != gateway.currentTarget()) {
            return Availability(false, "Input field changed; cannot safely undo.")
        }
        if (gateway.hasActiveSelection()) {
            return Availability(false, "Text is selected; cannot safely undo.")
        }

        val requestLen = entry.insertedText.length + entry.beforeAnchor.length
        val before = gateway.textBeforeCursor(requestLen)
            ?: return Availability(false, "App did not expose surrounding text.")

        if (before.length < entry.insertedText.length || !before.endsWith(entry.insertedText)) {
            return Availability(false, "Inserted text or cursor position changed.")
        }

        if (entry.beforeAnchor.isNotEmpty()) {
            val prefixEnd = before.length - entry.insertedText.length
            val prefix = before.substring(0, prefixEnd)
            val expectedAnchor = if (prefix.length >= entry.beforeAnchor.length) {
                entry.beforeAnchor
            } else {
                entry.beforeAnchor.takeLast(prefix.length)
            }
            if (expectedAnchor.isNotEmpty() && !prefix.endsWith(expectedAnchor)) {
                return Availability(false, "Text before insertion changed.")
            }
        }

        if (entry.afterAnchor.isNotEmpty()) {
            val after = gateway.textAfterCursor(entry.afterAnchor.length)
                ?: return Availability(false, "App did not expose trailing text.")
            if (!after.startsWith(entry.afterAnchor)) {
                return Availability(false, "Trailing text changed; cannot safely undo.")
            }
        }

        return Availability(true, null)
    }

    fun canSafelyRedo(gateway: HostEditorGateway): Availability {
        val entry = redoStack.peekFirst() ?: return Availability(false, "Nothing to redo.")
        if (gateway.isPasswordField()) {
            return Availability(false, "Insertion redo disabled in password fields.")
        }
        if (entry.target != gateway.currentTarget()) {
            return Availability(false, "Input field changed; cannot safely redo.")
        }
        if (gateway.hasActiveSelection()) {
            return Availability(false, "Text is selected; cannot safely redo.")
        }

        val expectedTail = entry.beforeAnchor + entry.originalText
        if (expectedTail.isNotEmpty()) {
            val before = gateway.textBeforeCursor(expectedTail.length)
                ?: return Availability(false, "App did not expose surrounding text.")
            if (entry.originalText.isNotEmpty() &&
                (before.length < entry.originalText.length || !before.endsWith(entry.originalText))
            ) {
                return Availability(false, "Word changed; cannot safely redo.")
            }
            val beforeWithoutOriginal = before.dropLast(entry.originalText.length)
            if (entry.beforeAnchor.isNotEmpty()) {
                val expectedAnchor = if (beforeWithoutOriginal.length >= entry.beforeAnchor.length) {
                    entry.beforeAnchor
                } else {
                    entry.beforeAnchor.takeLast(beforeWithoutOriginal.length)
                }
                if (expectedAnchor.isNotEmpty() && !beforeWithoutOriginal.endsWith(expectedAnchor)) {
                    return Availability(false, "Cursor moved or text changed; cannot safely redo.")
                }
            }
        } else {
            // Confirm host still supports reading surrounding text cleanly
            gateway.textBeforeCursor(16)
                ?: return Availability(false, "App did not expose surrounding text.")
        }

        if (entry.afterAnchor.isNotEmpty()) {
            val after = gateway.textAfterCursor(entry.afterAnchor.length)
                ?: return Availability(false, "App did not expose trailing text.")
            if (!after.startsWith(entry.afterAnchor)) {
                return Availability(false, "Trailing text changed; cannot safely redo.")
            }
        }

        return Availability(true, null)
    }

    fun undo(gateway: HostEditorGateway): OperationResult {
        val check = canSafelyUndo(gateway)
        if (!check.allowed) {
            return OperationResult(false, check.reason ?: "Undo unavailable.")
        }
        val entry = undoStack.peekFirst() ?: return OperationResult(false, "Nothing to undo.")
        val ok = gateway.runBatchEdit {
            val deleted = gateway.deleteBeforeCursor(entry.insertedText.length)
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
            "Undid clip insertion (${entry.insertedText.length} chars)"
        }
        return OperationResult(true, label, entry)
    }

    fun redo(gateway: HostEditorGateway): OperationResult {
        val check = canSafelyRedo(gateway)
        if (!check.allowed) {
            return OperationResult(false, check.reason ?: "Redo unavailable.")
        }
        val entry = redoStack.peekFirst() ?: return OperationResult(false, "Nothing to redo.")
        val ok = gateway.runBatchEdit {
            if (entry.originalText.isNotEmpty()) {
                if (!gateway.deleteBeforeCursor(entry.originalText.length)) return@runBatchEdit false
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
        val ok = gateway.runBatchEdit {
            if (!gateway.deleteBeforeCursor(entry.insertedText.length)) return@runBatchEdit false
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
