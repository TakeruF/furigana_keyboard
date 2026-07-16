package com.example.furiganakeyboard

import com.example.furiganakeyboard.ime.CompositionCursorMutation
import com.example.furiganakeyboard.ime.CompositionCursorRejection
import com.example.furiganakeyboard.ime.CompositionCursorState
import com.example.furiganakeyboard.ime.RomajiCompositionCursor
import com.example.furiganakeyboard.ime.RomajiCursorDeltaReceiver
import com.example.furiganakeyboard.ime.RomajiSelectionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RomajiCompositionCursorTest {
    @Test
    fun spaceCallbackContractNamesItsUnitAsGraphemes() {
        var received = 0
        val receiver = RomajiCursorDeltaReceiver { received = it }

        receiver.onSpaceCursorDelta(deltaInGraphemes = -2)

        assertEquals(-2, received)
    }

    @Test
    fun compositionStateIsTheOnlyTextCursorAndRevisionOwnerAcrossReplaces() {
        val authoritative = CompositionCursorState.create()
        val adapter = RomajiCompositionCursor(authoritative)

        adapter.replace("かな", "かな")
        val firstRevision = authoritative.revision
        assertSame(authoritative, adapter.compositionState)
        assertEquals(authoritative.text, adapter.displayText)
        assertEquals(authoritative.cursorUtf16, adapter.cursorUtf16)
        assertEquals(authoritative.compositionRevision, adapter.compositionRevision)
        assertEquals(authoritative.cursorRevision, adapter.cursorRevision)

        adapter.replace("かな漢字", "かな漢字")
        assertSame(authoritative, adapter.compositionState)
        assertTrue(authoritative.compositionRevision > firstRevision.composition)
        assertTrue(authoritative.cursorRevision > firstRevision.cursor)

        val beforeClear = authoritative.revision
        adapter.clear()
        adapter.replace("さかな", "さかな")
        assertSame(authoritative, adapter.compositionState)
        assertTrue(authoritative.compositionRevision > beforeClear.composition)
        assertTrue(authoritative.cursorRevision > beforeClear.cursor)
    }

    @Test
    fun androidUtf16SelectionIsConvertedIntoAuthoritativeScalarCursor() {
        val adapter = RomajiCompositionCursor()
        adapter.replace("あ\uD842\uDFB7い", "あ\uD842\uDFB7い")

        assertEquals(
            RomajiSelectionAction.CursorChanged(cursorUtf16 = 3),
            adapter.onUpdateSelection(13, 13, 10, 14),
        )
        assertEquals(2, adapter.compositionState.cursorScalar)
        assertEquals(3, adapter.cursorUtf16)
        assertEquals("あ\uD842\uDFB7", adapter.conversionSlice()!!.request.reading)
    }

    @Test
    fun selectionCannotSplitSupplementaryOrCombiningGrapheme() {
        val supplementary = RomajiCompositionCursor()
        supplementary.replace("A\uD842\uDFB7B", "A\uD842\uDFB7B")
        assertEquals(
            RomajiSelectionAction.RestoreCursor(14),
            supplementary.onUpdateSelection(12, 12, 10, 14),
        )

        val combining = RomajiCompositionCursor()
        combining.replace("か\u3099な", "か\u3099な")
        assertEquals(
            RomajiSelectionAction.RestoreCursor(23),
            combining.onUpdateSelection(21, 21, 20, 23),
        )
    }

    @Test
    fun selfSelectionNotificationDoesNotChangeRevision() {
        val adapter = RomajiCompositionCursor()
        adapter.replace("にほん", "にほん")
        val cursorRevision = adapter.cursorRevision
        adapter.expectSelfSelection(relativeUtf16 = 3)

        assertEquals(
            RomajiSelectionAction.SelfUpdate,
            adapter.onUpdateSelection(23, 23, 20, 23),
        )
        assertEquals(cursorRevision, adapter.cursorRevision)
    }

    @Test
    fun setComposingEndNotificationRestoresOwnedInteriorCursor() {
        val adapter = RomajiCompositionCursor()
        adapter.replace("にほん", "にほん")
        adapter.expectSelfSelection(relativeUtf16 = 3)
        adapter.onUpdateSelection(23, 23, 20, 23)
        assertTrue(adapter.moveCursorByGrapheme(-1))
        adapter.expectSelfSelection(relativeUtf16 = 3)

        assertEquals(
            RomajiSelectionAction.RestoreCursor(22),
            adapter.onUpdateSelection(23, 23, 20, 23),
        )
    }

    @Test
    fun delayedExpectedSelectionsAreSafeWhenDeliveredInReverseOrder() {
        val adapter = RomajiCompositionCursor()
        adapter.replace("かな", "かな")
        adapter.expectSelfSelection(relativeUtf16 = 2)
        adapter.onUpdateSelection(12, 12, 10, 12)

        adapter.expectSelfSelection(relativeUtf16 = 2)
        assertTrue(adapter.moveCursorByGrapheme(-1))
        adapter.expectSelfSelection(relativeUtf16 = 1)
        val currentRevision = adapter.cursorRevision

        assertEquals(
            RomajiSelectionAction.SelfUpdate,
            adapter.onUpdateSelection(11, 11, 10, 12),
        )
        assertEquals(
            RomajiSelectionAction.RestoreCursor(11),
            adapter.onUpdateSelection(12, 12, 10, 12),
        )
        assertEquals(currentRevision, adapter.cursorRevision)
    }

    @Test
    fun middlePendingRomajiPreservesBothSuffixesUntilResolution() {
        val adapter = RomajiCompositionCursor()
        adapter.replace("かな", "かな")
        assertEquals(
            RomajiSelectionAction.CursorChanged(1),
            adapter.onUpdateSelection(101, 101, 100, 102),
        )

        assertApplied(adapter.replacePendingRomaji(display = "k", raw = "k"))
        assertEquals("かkな", adapter.displayText)
        assertEquals("k", adapter.pendingEdit!!.display)
        assertEquals("k", adapter.pendingEdit!!.raw)
        assertEquals("な", adapter.displayText.substring(adapter.cursorUtf16))
        assertNull(adapter.conversionSlice())

        assertApplied(adapter.replacePendingRomaji(display = "き", raw = "ki"))
        assertEquals("かきな", adapter.displayText)
        assertEquals("な", adapter.displayText.substring(adapter.cursorUtf16))
        assertApplied(adapter.resolvePendingRomaji("き"))
        assertEquals("かきな", adapter.displayText)
        assertEquals("な", adapter.conversionSlice()!!.resolvedSuffix)
    }

    @Test
    fun pendingInputRejectsSpaceMoveDeletionAndDictionaryRequest() {
        val adapter = RomajiCompositionCursor()
        adapter.replace("にh", "に", pendingRaw = "h")
        val before = adapter.compositionState.snapshot()

        assertFalse(adapter.moveCursorByGrapheme(-1))
        assertRejected(
            adapter.deleteGraphemeBeforeCursor(),
            CompositionCursorRejection.PENDING_INPUT_ACTIVE,
        )
        assertNull(adapter.conversionSlice())
        assertFalse(adapter.canRequestDictionary)
        assertEquals(before, adapter.compositionState.snapshot())
    }

    @Test
    fun conversionRequestContainsOnlyPrefixAndPreservesResolvedSuffix() {
        val adapter = RomajiCompositionCursor()
        adapter.replace("にほんご", "にほんご")
        adapter.onUpdateSelection(102, 102, 100, 104)

        val slice = adapter.conversionSlice()!!
        assertEquals("にほ", slice.request.reading)
        assertEquals("んご", slice.resolvedSuffix)
        assertEquals("", slice.unresolvedSuffix)
        assertEquals("", slice.unresolvedRaw)
        assertFalse(slice.isWholeComposition)
    }

    @Test
    fun compositionAndCursorRevisionsInvalidateStaleAsyncResults() {
        val adapter = RomajiCompositionCursor()
        adapter.replace("にほん", "にほん")
        val original = adapter.conversionSlice()!!.request

        assertTrue(adapter.isCurrent(original))
        assertTrue(adapter.moveCursorByGrapheme(-1))
        assertFalse(adapter.isCurrent(original))
        val moved = adapter.conversionSlice()!!.request
        assertNotEquals(original.cursorRevision, moved.cursorRevision)

        adapter.replace("にほんご", "にほんご")
        assertFalse(adapter.isCurrent(moved))
        assertNotEquals(moved.compositionRevision, adapter.compositionRevision)
    }

    @Test
    fun staleRevisionCannotMutatePendingEdit() {
        val adapter = RomajiCompositionCursor()
        adapter.replace("かな", "かな")
        val stale = adapter.compositionState.revision
        assertTrue(adapter.moveCursorByGrapheme(-1))

        assertRejected(
            adapter.replacePendingRomaji("k", "k", expected = stale),
            CompositionCursorRejection.STALE_REVISION,
        )
        assertEquals("かな", adapter.displayText)
    }

    @Test
    fun leavingCompositionRequestsSafeFinish() {
        val adapter = RomajiCompositionCursor()
        adapter.replace("かな", "かな")

        assertEquals(
            RomajiSelectionAction.FinishComposition,
            adapter.onUpdateSelection(8, 8, 10, 12),
        )
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
