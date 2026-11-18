package com.clipmax.app

/**
 * Pure JVM layout constants and deterministic state machine for the contextual
 * floating Paste Undo/Redo capsule (Phase 2B).
 */
internal object PasteUndoCapsuleSpec {
    const val CORNER_RADIUS_DP = 16f
    const val HEIGHT_DP = 32f // Slightly taller than clipboard snippet pills (29dp)
    const val WIDTH_DP = 88f  // Fixed width so Undo and Redo have identical geometry
    const val RIGHT_MARGIN_DP = 42f // Positioned immediately left of the circular > nav button
    const val ICON_SIZE_DP = 14f
    const val TEXT_SIZE_SP = 12.5f
    const val SELF_UPDATE_GRACE_MS = 700L
    const val LARGE_PASTE_CHAR_THRESHOLD = 1_500
    const val LARGE_PASTE_GRACE_MS = 2_500L
    const val RESTART_INPUT_SETTLE_GRACE_MS = 2_500L

    enum class CapsuleState {
        HIDDEN,
        UNDO_AVAILABLE,
        REDO_AVAILABLE
    }

    enum class CapsuleAction {
        UNDO,
        REDO
    }

    data class CapsuleConfig(
        val state: CapsuleState,
        val label: String,
        val action: CapsuleAction?
    )

    fun resolveState(isActive: Boolean, isRedoMode: Boolean): CapsuleState {
        if (!isActive) return CapsuleState.HIDDEN
        return if (isRedoMode) CapsuleState.REDO_AVAILABLE else CapsuleState.UNDO_AVAILABLE
    }

    fun resolveConfig(isActive: Boolean, isRedoMode: Boolean): CapsuleConfig {
        val state = resolveState(isActive, isRedoMode)
        return when (state) {
            CapsuleState.UNDO_AVAILABLE -> CapsuleConfig(state, "Undo", CapsuleAction.UNDO)
            CapsuleState.REDO_AVAILABLE -> CapsuleConfig(state, "Redo", CapsuleAction.REDO)
            CapsuleState.HIDDEN -> CapsuleConfig(state, "", null)
        }
    }

    fun onPasteSuccess(): Pair<Boolean, Boolean> = Pair(true, false)

    fun onUndoSuccess(): Pair<Boolean, Boolean> = Pair(true, true)

    fun onRedoSuccess(canUndoRemaining: Boolean): Pair<Boolean, Boolean> =
        if (canUndoRemaining) Pair(true, false) else Pair(false, false)

    fun onUnrelatedAction(): Pair<Boolean, Boolean> = Pair(false, false)

    /**
     * Determines whether an onStartInput() callback on the host editor represents a benign
     * restart/layout expansion on the same field (which must NOT abort an active paste or
     * dismiss the Paste Undo/Redo capsule) versus a genuine input field switch.
     */
    fun shouldPreserveSessionOnStartInput(
        restarting: Boolean,
        hardFieldChanged: Boolean,
        isInserting: Boolean,
        isCapsuleActive: Boolean,
        lastActionElapsedMs: Long,
        nowMs: Long,
        canPreserveViaGateway: Boolean
    ): Boolean {
        if (hardFieldChanged) return false
        if (isInserting) return true
        if (!isCapsuleActive) return false
        if (restarting || canPreserveViaGateway) return true
        return (nowMs - lastActionElapsedMs) in 0L..RESTART_INPUT_SETTLE_GRACE_MS
    }

    /**
     * Pure JVM state controller governing the contextual Paste Undo/Redo capsule.
     * Ensures deterministic transitions for paste, undo, redo, failure, cursor tap,
     * mode switch, panel switch, and typing dismissal.
     */
    class PasteUndoCapsuleController {
        var isActive: Boolean = false
            private set
        var isRedoMode: Boolean = false
            private set
        var isPendingConfirmation: Boolean = false
            private set
        var lastActionElapsedMs: Long = 0L
            private set
        var activeGraceMs: Long = SELF_UPDATE_GRACE_MS
            private set

        val currentState: CapsuleState
            get() = resolveState(isActive, isRedoMode)

        val currentConfig: CapsuleConfig
            get() = resolveConfig(isActive, isRedoMode)

        fun onPasteSucceeded(nowMs: Long = 0L, isLargeOperation: Boolean = false) {
            val (act, redo) = onPasteSuccess()
            isActive = act
            isRedoMode = redo
            isPendingConfirmation = false
            lastActionElapsedMs = nowMs
            activeGraceMs = if (isLargeOperation) LARGE_PASTE_GRACE_MS else SELF_UPDATE_GRACE_MS
        }

        fun onPasteFailed() {
            dismiss()
        }

        fun onUndoPendingConfirmation(nowMs: Long = 0L, isLargeOperation: Boolean = false) {
            isActive = true
            isRedoMode = false
            isPendingConfirmation = true
            lastActionElapsedMs = nowMs
            activeGraceMs = if (isLargeOperation) LARGE_PASTE_GRACE_MS else SELF_UPDATE_GRACE_MS
        }

        fun onUndoConfirmed(nowMs: Long = 0L, isLargeOperation: Boolean = false) {
            isPendingConfirmation = false
            onUndoSucceeded(nowMs = nowMs, isLargeOperation = isLargeOperation)
        }

        fun onUndoConfirmationRejected(preserveUndoForRetry: Boolean = true, nowMs: Long = 0L) {
            isPendingConfirmation = false
            if (preserveUndoForRetry) {
                isActive = true
                isRedoMode = false
                lastActionElapsedMs = nowMs
            } else {
                dismiss()
            }
        }

        fun onUndoSucceeded(nowMs: Long = 0L, isLargeOperation: Boolean = false) {
            val (act, redo) = onUndoSuccess()
            isActive = act
            isRedoMode = redo
            isPendingConfirmation = false
            lastActionElapsedMs = nowMs
            activeGraceMs = if (isLargeOperation) LARGE_PASTE_GRACE_MS else SELF_UPDATE_GRACE_MS
        }

        fun onRedoPendingConfirmation(nowMs: Long = 0L, isLargeOperation: Boolean = false) {
            isActive = true
            isRedoMode = true
            isPendingConfirmation = true
            lastActionElapsedMs = nowMs
            activeGraceMs = if (isLargeOperation) LARGE_PASTE_GRACE_MS else SELF_UPDATE_GRACE_MS
        }

        fun onRedoConfirmed(canUndoRemaining: Boolean, nowMs: Long = 0L, isLargeOperation: Boolean = false) {
            isPendingConfirmation = false
            onRedoSucceeded(canUndoRemaining = canUndoRemaining, nowMs = nowMs, isLargeOperation = isLargeOperation)
        }

        fun onRedoConfirmationRejected(preserveRedoForRetry: Boolean = true, nowMs: Long = 0L) {
            isPendingConfirmation = false
            if (preserveRedoForRetry) {
                isActive = true
                isRedoMode = true
                lastActionElapsedMs = nowMs
            } else {
                dismiss()
            }
        }

        fun onRedoSucceeded(canUndoRemaining: Boolean, nowMs: Long = 0L, isLargeOperation: Boolean = false) {
            val (act, redo) = onRedoSuccess(canUndoRemaining)
            isActive = act
            isRedoMode = redo
            isPendingConfirmation = false
            lastActionElapsedMs = nowMs
            activeGraceMs = if (isLargeOperation) LARGE_PASTE_GRACE_MS else SELF_UPDATE_GRACE_MS
        }

        fun onEditorSelectionChanged(
            nowMs: Long,
            hasSelection: Boolean,
            cursorMoved: Boolean,
            canUndoNow: Boolean,
            canRedoNow: Boolean
        ): Boolean {
            if (!isActive) return false
            if (isPendingConfirmation && (nowMs - lastActionElapsedMs <= activeGraceMs)) {
                return false
            }
            if (hasSelection) {
                dismiss()
                return true
            }
            val expectedStillValid = if (isRedoMode) canRedoNow else canUndoNow
            if (expectedStillValid) {
                // The paste/undo/redo operation remains completely valid and ready to be undone/redone;
                // do not dismiss on arbitrary timers or post-paste layout reflow cursor adjustments.
                return false
            }
            // If the expected operation is not yet valid immediately after action (within grace window, e.g.
            // host editor asynchronous text layout and IPC reflow in progress), give the host time to settle.
            if (nowMs - lastActionElapsedMs <= activeGraceMs) {
                return false
            }
            // Operation is no longer valid (e.g. user moved cursor elsewhere, modified text, or switched field)
            dismiss()
            return true
        }

        fun dismiss() {
            val (act, redo) = onUnrelatedAction()
            isActive = act
            isRedoMode = redo
            isPendingConfirmation = false
            activeGraceMs = SELF_UPDATE_GRACE_MS
        }
    }
}
