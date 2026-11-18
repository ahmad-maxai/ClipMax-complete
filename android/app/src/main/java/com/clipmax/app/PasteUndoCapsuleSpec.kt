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
    const val SELF_UPDATE_GRACE_MS = 350L

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
     * Pure JVM state controller governing the contextual Paste Undo/Redo capsule.
     * Ensures deterministic transitions for paste, undo, redo, failure, cursor tap,
     * mode switch, panel switch, and typing dismissal.
     */
    class PasteUndoCapsuleController {
        var isActive: Boolean = false
            private set
        var isRedoMode: Boolean = false
            private set
        var lastActionElapsedMs: Long = 0L
            private set

        val currentState: CapsuleState
            get() = resolveState(isActive, isRedoMode)

        val currentConfig: CapsuleConfig
            get() = resolveConfig(isActive, isRedoMode)

        fun onPasteSucceeded(nowMs: Long = 0L) {
            val (act, redo) = onPasteSuccess()
            isActive = act
            isRedoMode = redo
            lastActionElapsedMs = nowMs
        }

        fun onPasteFailed() {
            dismiss()
        }

        fun onUndoSucceeded(nowMs: Long = 0L) {
            val (act, redo) = onUndoSuccess()
            isActive = act
            isRedoMode = redo
            lastActionElapsedMs = nowMs
        }

        fun onRedoSucceeded(canUndoRemaining: Boolean, nowMs: Long = 0L) {
            val (act, redo) = onRedoSuccess(canUndoRemaining)
            isActive = act
            isRedoMode = redo
            lastActionElapsedMs = nowMs
        }

        fun onEditorSelectionChanged(
            nowMs: Long,
            hasSelection: Boolean,
            cursorMoved: Boolean,
            canUndoNow: Boolean,
            canRedoNow: Boolean
        ): Boolean {
            if (!isActive) return false
            if (nowMs - lastActionElapsedMs <= SELF_UPDATE_GRACE_MS) {
                return false
            }
            if (hasSelection || cursorMoved) {
                dismiss()
                return true
            }
            val expectedStillValid = if (isRedoMode) canRedoNow else canUndoNow
            if (!expectedStillValid) {
                dismiss()
                return true
            }
            return false
        }

        fun dismiss() {
            val (act, redo) = onUnrelatedAction()
            isActive = act
            isRedoMode = redo
        }
    }
}
