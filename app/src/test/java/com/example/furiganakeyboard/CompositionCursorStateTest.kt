package com.example.furiganakeyboard

import com.example.furiganakeyboard.ime.CompositionCursorMutation
import com.example.furiganakeyboard.ime.CompositionCursorRejection
import com.example.furiganakeyboard.ime.CompositionCursorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionCursorStateTest {
    @Test
    fun cursorMovesToStartMiddleAndEndInScalarUnits() {
        val state = CompositionCursorState.create("かな")

        assertApplied(state.moveCursorToScalar(0))
        assertEquals(0, state.cursorScalar)
        assertApplied(state.moveCursorToScalar(1))
        assertEquals(1, state.cursorScalar)
        assertApplied(state.moveCursorToScalar(2))
        assertEquals(2, state.cursorScalar)
        assertEquals(2, state.cursorUtf16)
    }

    @Test
    fun replaceMoveInsertAndDeleteUseOneConsistentRevisionSequence() {
        val state = CompositionCursorState.create("かな")
        val initial = state.revision

        val replaced = assertApplied(
            state.replaceComposition("かなた", cursorScalar = 3, pendingRange = null)
        )
        assertTrue(replaced.compositionChanged)
        assertTrue(replaced.cursorChanged)
        assertEquals(initial.composition + 1, state.compositionRevision)
        assertEquals(initial.cursor + 1, state.cursorRevision)

        val moved = assertApplied(state.moveCursorByGrapheme(-1))
        assertFalse(moved.compositionChanged)
        assertTrue(moved.cursorChanged)
        assertEquals(initial.composition + 1, state.compositionRevision)
        assertEquals(initial.cursor + 2, state.cursorRevision)

        val inserted = assertApplied(state.insertAtCursor("の"))
        assertTrue(inserted.compositionChanged)
        assertTrue(inserted.cursorChanged)
        assertEquals(initial.composition + 2, state.compositionRevision)
        assertEquals(initial.cursor + 3, state.cursorRevision)

        val deleted = assertApplied(state.deleteGraphemeBeforeCursor())
        assertTrue(deleted.compositionChanged)
        assertTrue(deleted.cursorChanged)
        assertEquals(initial.composition + 3, state.compositionRevision)
        assertEquals(initial.cursor + 4, state.cursorRevision)
        assertEquals("かなた", state.text)
    }

    @Test
    fun prefixReplacementPreservesSumomoSuffixExactly() {
        val state = CompositionCursorState.create("すもももも", cursorScalar = 3)
        val before = state.slice()

        assertApplied(state.replacePrefixAtCursor("李"))

        assertEquals("すもも", before.prefix)
        assertEquals("もも", before.suffix)
        assertEquals("李もも", state.text)
        assertEquals("李", state.slice().prefix)
        assertEquals("もも", state.slice().suffix)
    }

    @Test
    fun scalarRangeReplacementPreservesTextOutsideTheRange() {
        val state = CompositionCursorState.create("A\uD842\uDFB7BC", cursorScalar = 1)

        assertApplied(state.replaceScalarRange(1, 3, "X"))

        assertEquals("AXC", state.text)
        assertEquals(2, state.cursorScalar)
        assertEquals("AX", state.slice().prefix)
        assertEquals("C", state.slice().suffix)
    }

    @Test
    fun scalarAndUtf16OffsetsRoundTripWithoutSplittingSupplementaryCharacter() {
        val state = CompositionCursorState.create("A\uD842\uDFB7B")

        assertEquals(listOf(0, 1, 3, 4), (0..3).map(state::scalarToUtf16))
        assertEquals(0, state.utf16ToScalar(0))
        assertEquals(1, state.utf16ToScalar(1))
        assertEquals(null, state.utf16ToScalar(2))
        assertEquals(2, state.utf16ToScalar(3))
        assertEquals(3, state.utf16ToScalar(4))

        assertApplied(state.moveCursorByGrapheme(-1))
        assertEquals(2, state.cursorScalar)
        assertEquals(3, state.cursorUtf16)
        assertApplied(state.moveCursorByGrapheme(-1))
        assertEquals(1, state.cursorScalar)
        assertEquals(1, state.cursorUtf16)
    }

    @Test
    fun invalidUtf16BoundaryCannotMoveOrReplaceState() {
        val state = CompositionCursorState.create("A\uD842\uDFB7B")
        val initial = state.snapshot()

        assertRejected(
            state.moveCursorToUtf16(2),
            CompositionCursorRejection.INVALID_UTF16_BOUNDARY,
        )
        assertRejected(
            state.replaceUtf16Range(1, 2, "X"),
            CompositionCursorRejection.INVALID_UTF16_BOUNDARY,
        )
        assertEquals(initial, state.snapshot())
    }

    @Test
    fun spaceMovesCombiningDakutenAsOneGrapheme() {
        val state = CompositionCursorState.create("か\u3099な")

        assertApplied(state.moveCursorByGrapheme(-1))
        assertEquals(2, state.cursorScalar)
        assertApplied(state.moveCursorByGrapheme(-1))
        assertEquals(0, state.cursorScalar)
        assertApplied(state.moveCursorByGrapheme(1))
        assertEquals(2, state.cursorScalar)
    }

    @Test
    fun oneUserDeletionRemovesBaseAndCombiningDakuten() {
        val state = CompositionCursorState.create("か\u3099")

        assertApplied(state.deleteGraphemeBeforeCursor())

        assertEquals("", state.text)
        assertEquals(0, state.cursorScalar)
    }

    @Test
    fun zwjSequenceIsNeverAUserFacingStop() {
        val state = CompositionCursorState.create("A\uD83D\uDC69\u200D\uD83D\uDCBBB")

        assertApplied(state.moveCursorByGrapheme(-1))
        assertEquals(4, state.cursorScalar)
        assertApplied(state.moveCursorByGrapheme(-1))
        assertEquals(1, state.cursorScalar)
        assertApplied(state.deleteGraphemeBeforeCursor())
        assertEquals("\uD83D\uDC69\u200D\uD83D\uDCBBB", state.text)
    }

    @Test
    fun pendingMiddleRangePreservesBothSidesAndBlocksMovementAndDictionary() {
        val state = CompositionCursorState.create("かな", cursorScalar = 1)

        assertApplied(state.replacePendingDisplay("k", logicalInputChanged = true))
        assertEquals("かkな", state.text)
        assertEquals("k", state.pendingDisplay)
        assertEquals("な", state.text.substring(state.cursorUtf16))
        assertFalse(state.canRequestDictionary)
        assertRejected(
            state.moveCursorByGrapheme(-1),
            CompositionCursorRejection.PENDING_INPUT_ACTIVE,
        )
        assertRejected(
            state.deleteGraphemeBeforeCursor(),
            CompositionCursorRejection.PENDING_INPUT_ACTIVE,
        )

        assertApplied(state.replacePendingDisplay("き", logicalInputChanged = true))
        assertEquals("かきな", state.text)
        assertEquals("な", state.text.substring(state.cursorUtf16))
        assertApplied(state.resolvePendingDisplay("き"))
        assertEquals("かきな", state.text)
        assertEquals(2, state.cursorScalar)
        assertTrue(state.canRequestDictionary)
    }

    @Test
    fun resolvedEditBeforePendingRangeShiftsRangeWithoutLosingPendingText() {
        val state = CompositionCursorState.create("にh", resolvedPrefix = "に")

        assertApplied(state.insertAtCursor("ほ"))

        assertEquals("にほh", state.text)
        assertEquals("h", state.pendingDisplay)
        assertEquals(2, state.pendingRange!!.start)
        assertEquals(3, state.pendingRange!!.end)
    }

    @Test
    fun staleCompositionAndCursorRevisionRejectAllMutation() {
        val state = CompositionCursorState.create("かな")
        val staleCursor = state.revision
        assertApplied(state.moveCursorByGrapheme(-1, expected = staleCursor))
        val afterMove = state.snapshot()

        assertRejected(
            state.insertAtCursor("ん", expected = staleCursor),
            CompositionCursorRejection.STALE_REVISION,
        )
        assertEquals(afterMove, state.snapshot())

        val beforeEdit = state.revision
        assertApplied(state.insertAtCursor("ん", expected = beforeEdit))
        val afterEdit = state.snapshot()
        assertRejected(
            state.moveCursorToScalar(0, expected = beforeEdit),
            CompositionCursorRejection.STALE_REVISION,
        )
        assertEquals(afterEdit, state.snapshot())
    }

    @Test
    fun malformedReplacementIsRejectedWithoutRevisionChange() {
        val state = CompositionCursorState.create("かな")
        val initial = state.snapshot()

        assertRejected(
            state.insertAtCursor("\uD842"),
            CompositionCursorRejection.MALFORMED_UTF16,
        )
        assertEquals(initial, state.snapshot())
    }

    private fun assertApplied(
        result: CompositionCursorMutation,
    ): CompositionCursorMutation.Applied {
        assertTrue("expected Applied, got $result", result is CompositionCursorMutation.Applied)
        return result as CompositionCursorMutation.Applied
    }

    private fun assertRejected(
        result: CompositionCursorMutation,
        reason: CompositionCursorRejection,
    ): CompositionCursorMutation.Rejected {
        assertTrue("expected Rejected, got $result", result is CompositionCursorMutation.Rejected)
        return (result as CompositionCursorMutation.Rejected).also {
            assertEquals(reason, it.reason)
        }
    }
}
