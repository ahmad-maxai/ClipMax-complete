package com.clipmax.app

import kotlin.math.abs

/**
 * Pure-JVM components of ClipMax's non-blocking native IME input pipeline.
 *
 * Keeps a bounded main-thread shadow buffer of the focused host editor alongside
 * snapshot-backed history gateways and View-signature keys so key presses,
 * suggestion refreshes, auto-capitalization, and lifecycle transitions never
 * block MainLooper on synchronous InputConnection queries.
 */
object ImeInputPipeline {

    data class ComposingContextSnapshot(
        val composingWord: String,
        val fullToken: String,
        val beforeAnchor: String,
        val afterAnchor: String,
        val isSentenceStart: Boolean,
        val previousWord: String?,
        val endedWithSpace: Boolean,
        val midWord: Boolean
    )

    enum class SelectionClassification {
        EXPECTED_STEP,
        RANGE_SELECTED,
        CURSOR_JUMP
    }

    fun extractComposingContext(
        before: String,
        after: String,
        hasSelection: Boolean
    ): ComposingContextSnapshot? {
        if (hasSelection) return null

        val midWord = after.isNotEmpty() && (after[0].isLetterOrDigit() || after[0] == '\'')
        val endedWithSpace = before.isNotEmpty() && before.last().isWhitespace()

        var wsIdx = before.length - 1
        while (wsIdx >= 0 && !before[wsIdx].isWhitespace()) wsIdx--
        val fullToken = if (midWord || endedWithSpace) "" else before.substring(wsIdx + 1)

        var wordStart = before.length - 1
        while (wordStart >= 0 && (before[wordStart].isLetter() || before[wordStart] == '\'')) {
            wordStart--
        }
        val composingWord = if (midWord || endedWithSpace) "" else before.substring(wordStart + 1)
        val anchorBeforeWord = if (composingWord.isNotEmpty()) {
            before.substring(0, wordStart + 1).takeLast(InsertionHistoryManager.ANCHOR_LENGTH)
        } else {
            before.takeLast(InsertionHistoryManager.ANCHOR_LENGTH)
        }

        var scanIdx = if (fullToken.isNotEmpty()) wsIdx else before.length - 1
        while (scanIdx >= 0 && before[scanIdx] == ' ') scanIdx--
        val isSentenceStart = scanIdx < 0 || before[scanIdx] in charArrayOf('\n', '.', '!', '?')

        var prevEnd = scanIdx
        while (prevEnd >= 0 && !before[prevEnd].isLetter() && before[prevEnd] != '\'') {
            if (!before[prevEnd].isWhitespace()) {
                prevEnd = -1
                break
            }
            prevEnd--
        }
        val prevWord = if (prevEnd >= 0) {
            var prevStart = prevEnd
            while (prevStart >= 0 && (before[prevStart].isLetter() || before[prevStart] == '\'')) prevStart--
            before.substring(prevStart + 1, prevEnd + 1).takeIf { it.isNotEmpty() }
        } else {
            null
        }

        return ComposingContextSnapshot(
            composingWord = composingWord,
            fullToken = fullToken,
            beforeAnchor = anchorBeforeWord,
            afterAnchor = after,
            isSentenceStart = isSentenceStart,
            previousWord = prevWord,
            endedWithSpace = endedWithSpace,
            midWord = midWord
        )
    }

    /**
     * Bounded cursor mirror owned exclusively by the IME main thread.
     *
     * Every local edit increments [revision]. Out-of-order asynchronous host snapshots
     * with stale revisions are ignored via [applyHostSnapshot] so fast typing bursts
     * are never overwritten by older InputConnection reads.
     */
    class EditorShadowBuffer {
        companion object {
            const val MAX_SHADOW_BEFORE_CHARS = 512
            const val MAX_SHADOW_AFTER_CHARS = 128
        }

        var beforeCursor: String = ""
            private set
        var afterCursor: String = ""
            private set
        var hasSelection: Boolean = false
            private set
        var lastSelStart: Int = -1
            private set
        var lastSelEnd: Int = -1
            private set
        var revision: Long = 0L
            private set
        var synchronizedWithHost: Boolean = false
            private set
        var awaitingHostSync: Boolean = true
            private set

        fun resetForInputSession(
            initialBefore: String?,
            initialAfter: String?,
            initialHasSelection: Boolean,
            selStart: Int,
            selEnd: Int
        ) {
            revision++
            beforeCursor = initialBefore?.takeLast(MAX_SHADOW_BEFORE_CHARS).orEmpty()
            afterCursor = initialAfter?.take(MAX_SHADOW_AFTER_CHARS).orEmpty()
            hasSelection = initialHasSelection || (selStart >= 0 && selEnd >= 0 && selStart != selEnd)
            lastSelStart = selStart
            lastSelEnd = selEnd
            synchronizedWithHost = initialBefore != null
            awaitingHostSync = initialBefore == null
        }

        fun invalidateHostSync() {
            revision++
            synchronizedWithHost = false
            awaitingHostSync = true
        }

        fun markHostSyncNeeded() {
            awaitingHostSync = true
        }

        fun applyHostSnapshot(
            expectedRevision: Long,
            before: String?,
            after: String?,
            hasSelection: Boolean
        ): Boolean {
            if (expectedRevision != revision) {
                return false
            }
            beforeCursor = before?.takeLast(MAX_SHADOW_BEFORE_CHARS).orEmpty()
            afterCursor = after?.take(MAX_SHADOW_AFTER_CHARS).orEmpty()
            this.hasSelection = hasSelection
            synchronizedWithHost = before != null
            awaitingHostSync = false
            return true
        }

        fun onCommitText(text: String) {
            revision++
            if (hasSelection && lastSelStart >= 0 && lastSelEnd >= lastSelStart) {
                lastSelEnd = lastSelStart
            }
            hasSelection = false
            if (text.isNotEmpty()) {
                beforeCursor = (beforeCursor + text).takeLast(MAX_SHADOW_BEFORE_CHARS)
                if (lastSelStart >= 0) {
                    lastSelStart += text.length
                    lastSelEnd = lastSelStart
                }
            }
        }

        fun onReplaceBeforeCursor(deleteChars: Int, commitText: String) {
            revision++
            val safeDelete = deleteChars.coerceAtLeast(0).coerceAtMost(beforeCursor.length)
            val base = if (safeDelete > 0) beforeCursor.dropLast(safeDelete) else beforeCursor
            beforeCursor = (base + commitText).takeLast(MAX_SHADOW_BEFORE_CHARS)
            hasSelection = false
            if (lastSelStart >= 0) {
                lastSelStart = (lastSelStart - safeDelete).coerceAtLeast(0) + commitText.length
                lastSelEnd = lastSelStart
            }
        }

        fun onDeleteBeforeCursor(deleteChars: Int) {
            revision++
            val safeDelete = deleteChars.coerceAtLeast(0).coerceAtMost(beforeCursor.length)
            if (safeDelete > 0) {
                beforeCursor = beforeCursor.dropLast(safeDelete)
            }
            hasSelection = false
            if (lastSelStart >= 0) {
                lastSelStart = (lastSelStart - safeDelete).coerceAtLeast(0)
                lastSelEnd = lastSelStart
            }
        }

        fun onDeleteSelection() {
            revision++
            hasSelection = false
            if (lastSelStart >= 0 && lastSelEnd >= lastSelStart) {
                lastSelEnd = lastSelStart
            }
        }

        fun onSelectionReported(
            oldSelStart: Int,
            oldSelEnd: Int,
            newSelStart: Int,
            newSelEnd: Int
        ): SelectionClassification {
            val prevPredictedStart = lastSelStart
            lastSelStart = newSelStart
            lastSelEnd = newSelEnd
            if (newSelStart >= 0 && newSelEnd >= 0 && newSelStart != newSelEnd) {
                hasSelection = true
                return SelectionClassification.RANGE_SELECTED
            }
            hasSelection = false

            // If cursor position matches what our local commits/deletes already predicted, no IPC needed.
            if (newSelStart >= 0 && prevPredictedStart >= 0 && newSelStart == prevPredictedStart) {
                return SelectionClassification.EXPECTED_STEP
            }

            // WebView frequently reports initial cursor -1 -> N or small batch steps during fast typing.
            if (oldSelStart >= 0 && oldSelStart == oldSelEnd && newSelStart >= 0 && abs(newSelStart - oldSelStart) <= 1) {
                return SelectionClassification.EXPECTED_STEP
            }

            return SelectionClassification.CURSOR_JUMP
        }

        fun beforeText(maxChars: Int = MAX_SHADOW_BEFORE_CHARS): String? {
            if (!synchronizedWithHost && beforeCursor.isEmpty()) return null
            return beforeCursor.takeLast(maxChars.coerceAtLeast(0))
        }

        fun afterText(maxChars: Int = MAX_SHADOW_AFTER_CHARS): String {
            return afterCursor.take(maxChars.coerceAtLeast(0))
        }

        fun currentComposingContext(): ComposingContextSnapshot? {
            if (!synchronizedWithHost && beforeCursor.isEmpty() && afterCursor.isEmpty() && !hasSelection) {
                return null
            }
            return extractComposingContext(beforeCursor, afterCursor, hasSelection)
        }
    }

    /**
     * Pre-fetched host editor snapshot + mutator gateway so InsertionHistoryManager runs cleanly
     * without blocking the main thread on InputConnection read calls.
     */
    interface MutationSink {
        fun deleteBeforeCursor(length: Int): Boolean
        fun commitText(text: String): Boolean
        fun runBatchEdit(block: () -> Boolean): Boolean
    }

    class SnapshotHostEditorGateway(
        private val target: InsertionHistoryManager.EditorTarget,
        private val passwordField: Boolean,
        private val preReadBefore: String?,
        private val preReadAfter: String?,
        private val preReadSelected: String?,
        private val sink: MutationSink
    ) : InsertionHistoryManager.HostEditorGateway {

        override fun currentTarget(): InsertionHistoryManager.EditorTarget = target

        override fun isPasswordField(): Boolean = passwordField

        override fun hasActiveSelection(): Boolean = !preReadSelected.isNullOrEmpty()

        override fun textBeforeCursor(maxChars: Int): String? {
            if (maxChars <= 0) return ""
            return preReadBefore?.takeLast(maxChars)
        }

        override fun textAfterCursor(maxChars: Int): String? {
            if (maxChars <= 0) return ""
            return preReadAfter?.take(maxChars)
        }

        override fun deleteBeforeCursor(length: Int): Boolean = sink.deleteBeforeCursor(length)

        override fun commitText(text: String): Boolean = sink.commitText(text)

        override fun runBatchEdit(block: () -> Boolean): Boolean = sink.runBatchEdit(block)
    }

    /**
     * Signature keys avoiding needless View teardown/reconstruction when state has not changed.
     */
    object ViewSignatureSpec {
        fun keyboardRowsSignature(
            language: String,
            mode: KeyboardLayoutSpec.KeyboardMode,
            themeKey: String,
            heightPref: String,
            screenHeightDp: Int,
            isLandscape: Boolean,
            isFilterMode: Boolean,
            isInserting: Boolean,
            isPasswordTarget: Boolean
        ): String =
            "$language|${mode.name}|$themeKey|$heightPref|$screenHeightDp|$isLandscape|$isFilterMode|$isInserting|$isPasswordTarget"

        fun clipChipsSignature(
            storeRevision: Long,
            itemCount: Int,
            firstId: String?,
            isPasswordTarget: Boolean,
            pinnedOnly: Boolean,
            filterQuery: String,
            themeKey: String,
            oneHandedMode: String
        ): String =
            "$storeRevision|$itemCount|${firstId.orEmpty()}|$isPasswordTarget|$pinnedOnly|$filterQuery|$themeKey|$oneHandedMode"

        fun pasteCapsuleUiSignature(
            config: PasteUndoCapsuleSpec.CapsuleConfig,
            toolbarState: Int,
            label: String,
            fgColor: Int
        ): String =
            "${config.state.name}|${config.action.name}|$toolbarState|$label|$fgColor"
    }
}
