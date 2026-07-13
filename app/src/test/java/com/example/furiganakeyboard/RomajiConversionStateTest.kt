package com.example.furiganakeyboard

import com.example.furiganakeyboard.ime.RomajiConversionState
import org.junit.Assert.assertEquals
import org.junit.Test

class RomajiConversionStateTest {
    @Test
    fun spaceMovesUnconvertedCompositionIntoFirstCandidateSelection() {
        val state = RomajiConversionState().apply { onCompositionEdited(hasComposition = true) }

        assertEquals(
            RomajiConversionState.KeyAction.SelectCandidate(0),
            state.onSpace(candidateCount = 3),
        )
        assertEquals(RomajiConversionState.Phase.SELECTING_CANDIDATE, state.phase)
        assertEquals(0, state.selectedCandidateIndex(3))
    }

    @Test
    fun spaceCyclesOnlyAfterCandidateSelectionHasStarted() {
        val state = RomajiConversionState().apply { onCompositionEdited(hasComposition = true) }

        state.onSpace(candidateCount = 3)
        assertEquals(RomajiConversionState.KeyAction.SelectCandidate(1), state.onSpace(3))
        assertEquals(RomajiConversionState.KeyAction.SelectCandidate(2), state.onSpace(3))
        assertEquals(RomajiConversionState.KeyAction.SelectCandidate(0), state.onSpace(3))
    }

    @Test
    fun enterCommitsFirstOrSelectedCandidateAndEmptyCompositionSendsEditorAction() {
        val state = RomajiConversionState().apply { onCompositionEdited(hasComposition = true) }

        assertEquals(RomajiConversionState.KeyAction.CommitCandidate(0), state.onEnter(2))
        state.onSpace(2)
        state.onSpace(2)
        assertEquals(RomajiConversionState.KeyAction.CommitCandidate(1), state.onEnter(2))
        state.clear()
        assertEquals(RomajiConversionState.KeyAction.SendEditorAction, state.onEnter(0))
    }

    @Test
    fun missingCandidatesCommitCompositionWithoutInsertingSpace() {
        val state = RomajiConversionState().apply { onCompositionEdited(hasComposition = true) }

        assertEquals(RomajiConversionState.KeyAction.CommitComposition, state.onSpace(0))
        assertEquals(RomajiConversionState.KeyAction.CommitComposition, state.onEnter(0))
    }

    @Test
    fun candidatePresentationDoesNotChangeSelectionPhase() {
        val state = RomajiConversionState().apply { onCompositionEdited(hasComposition = true) }

        assertEquals(RomajiConversionState.Phase.COMPOSING, state.phase)
        assertEquals(null, state.selectedCandidateIndex(8))
    }
}
