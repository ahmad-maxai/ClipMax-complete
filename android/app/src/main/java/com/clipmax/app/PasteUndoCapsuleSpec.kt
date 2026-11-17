package com.clipmax.app

/**
 * Pure JVM layout and state specifications for the contextual floating
 * Paste Undo/Redo capsule control (Phase 2B).
 */
internal object PasteUndoCapsuleSpec {
    const val CORNER_RADIUS_DP = 16f
    const val HEIGHT_DP = 32f // Slightly taller than snippet pills (~29dp)
    const val WIDTH_DP = 86f  // Fixed width to ensure zero layout shift between Undo and Redo
    const val RIGHT_MARGIN_DP = 42f // Offset to sit immediately left of the round navigation button
    const val ICON_SIZE_DP = 13.5f
    const val TEXT_SIZE_SP = 12.5f

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

    /**
     * State transition when user successfully executes an undo on a paste item.
     * Transitions from Undo to Redo mode in place.
     */
    fun onUndoSuccess(): Pair<Boolean, Boolean> {
        // (isActive, isRedoMode)
        return Pair(true, true)
    }

    /**
     * State transition when user successfully executes a redo.
     * If canUndoRemaining is true, restores Undo in place.
     * Otherwise dismisses the capsule.
     */
    fun onRedoSuccess(canUndoRemaining: Boolean): Pair<Boolean, Boolean> {
        return if (canUndoRemaining) {
            Pair(true, false)
        } else {
            Pair(false, false)
        }
    }

    /**
     * State transition when an unrelated user action occurs (typing, backspace, mode switch, panel open, focus change).
     */
    fun onUnrelatedAction(): Pair<Boolean, Boolean> {
        return Pair(false, false)
    }

    /**
     * State transition when a new paste operation completes.
     */
    fun onPasteSuccess(): Pair<Boolean, Boolean> {
        return Pair(true, false)
    }
}
