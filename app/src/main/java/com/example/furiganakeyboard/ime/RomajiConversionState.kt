package com.example.furiganakeyboard.ime

/**
 * State transitions for Japanese IME conversion keys on the romaji panel.
 *
 * Candidate presentation is deliberately not a state transition: scrolling the
 * candidate bar must not turn a pre-conversion composition into a selection.
 */
class RomajiConversionState {
    enum class Phase { EMPTY, COMPOSING, SELECTING_CANDIDATE }

    sealed interface KeyAction {
        data object InsertSpace : KeyAction
        data object SendEditorAction : KeyAction
        data object CommitComposition : KeyAction
        data class SelectCandidate(val index: Int) : KeyAction
        data class CommitCandidate(val index: Int) : KeyAction
    }

    var phase: Phase = Phase.EMPTY
        private set

    private var selectedIndex = 0

    /** Call only when romaji input has been edited, not when candidates redraw. */
    fun onCompositionEdited(hasComposition: Boolean) {
        phase = if (hasComposition) Phase.COMPOSING else Phase.EMPTY
        selectedIndex = 0
    }

    fun clear() = onCompositionEdited(hasComposition = false)

    fun selectedCandidateIndex(candidateCount: Int): Int? =
        if (phase == Phase.SELECTING_CANDIDATE && candidateCount > 0) {
            selectedIndex.mod(candidateCount)
        } else {
            null
        }

    fun onSpace(candidateCount: Int): KeyAction = when (phase) {
        Phase.EMPTY -> KeyAction.InsertSpace
        Phase.COMPOSING -> selectFirstOrCommit(candidateCount)
        Phase.SELECTING_CANDIDATE -> selectNextOrCommit(candidateCount)
    }

    fun onEnter(candidateCount: Int): KeyAction = when (phase) {
        Phase.EMPTY -> KeyAction.SendEditorAction
        Phase.COMPOSING -> commitFirstOrComposition(candidateCount)
        Phase.SELECTING_CANDIDATE -> commitSelectedOrComposition(candidateCount)
    }

    private fun selectFirstOrCommit(candidateCount: Int): KeyAction {
        if (candidateCount <= 0) return KeyAction.CommitComposition
        phase = Phase.SELECTING_CANDIDATE
        selectedIndex = 0
        return KeyAction.SelectCandidate(selectedIndex)
    }

    private fun selectNextOrCommit(candidateCount: Int): KeyAction {
        if (candidateCount <= 0) return KeyAction.CommitComposition
        selectedIndex = (selectedIndex + 1).mod(candidateCount)
        return KeyAction.SelectCandidate(selectedIndex)
    }

    private fun commitFirstOrComposition(candidateCount: Int): KeyAction =
        if (candidateCount > 0) KeyAction.CommitCandidate(0) else KeyAction.CommitComposition

    private fun commitSelectedOrComposition(candidateCount: Int): KeyAction =
        selectedCandidateIndex(candidateCount)?.let(KeyAction::CommitCandidate)
            ?: KeyAction.CommitComposition
}
