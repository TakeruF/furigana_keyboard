package com.example.furiganakeyboard.ime

/** View-to-service boundary for incremental space-drag cursor movement. */
fun interface RomajiCursorDeltaReceiver {
    fun onSpaceCursorDelta(deltaInGraphemes: Int)
}

/** Identifies the exact composition slice used by an asynchronous conversion request. */
data class RomajiCursorRequest(
    val reading: String,
    val compositionRevision: Long,
    val cursorRevision: Long,
)

/** The resolved reading before the cursor and the suffix that conversion must not replace. */
data class RomajiConversionSlice(
    val request: RomajiCursorRequest,
    val resolvedSuffix: String,
    val unresolvedSuffix: String,
    val unresolvedRaw: String,
    val isWholeComposition: Boolean,
)

/** Adapter-only information for an unresolved romaji edit at an arbitrary scalar range. */
data class RomajiPendingEdit(
    val range: CompositionScalarRange,
    val rangeStartUtf16: Int,
    val rangeEndUtf16: Int,
    val display: String,
    val raw: String,
)

sealed interface RomajiSelectionAction {
    data object Ignored : RomajiSelectionAction
    data object SelfUpdate : RomajiSelectionAction
    data class CursorChanged(val cursorUtf16: Int) : RomajiSelectionAction
    data class RestoreCursor(val absoluteUtf16: Int) : RomajiSelectionAction
    data object FinishComposition : RomajiSelectionAction
}

/**
 * Android selection arbitration around one authoritative [CompositionCursorState].
 *
 * This adapter owns no text, cursor, or revision counters. It only translates absolute Android
 * UTF-16 selections, remembers expected self-notifications, and carries raw romaji for the
 * state's explicit pending display range.
 */
class RomajiCompositionCursor(
    val compositionState: CompositionCursorState = CompositionCursorState.create(),
) {
    private data class ExpectedSelection(
        val compositionRevision: Long,
        val cursorRevision: Long,
        val textUtf16Length: Int,
        val relativeUtf16: Int,
    )

    private var compositionStartUtf16: Int? = null
    private val expectedSelections = ArrayDeque<ExpectedSelection>()
    private var pendingRaw = ""

    val compositionRevision: Long get() = compositionState.compositionRevision
    val cursorRevision: Long get() = compositionState.cursorRevision
    val isActive: Boolean get() = compositionState.text.isNotEmpty()
    val displayText: String get() = compositionState.text
    val cursorUtf16: Int get() = compositionState.cursorUtf16
    val resolvedEndUtf16: Int get() = compositionState.resolvedUtf16End
    val absoluteCursorUtf16: Int? get() = compositionStartUtf16?.plus(cursorUtf16)
    val canRequestDictionary: Boolean get() = isActive && compositionState.canRequestDictionary

    val pendingEdit: RomajiPendingEdit?
        get() = compositionState.pendingRange?.let { range ->
            RomajiPendingEdit(
                range = range,
                rangeStartUtf16 = checkNotNull(compositionState.scalarToUtf16(range.start)),
                rangeEndUtf16 = checkNotNull(compositionState.scalarToUtf16(range.end)),
                display = checkNotNull(compositionState.pendingDisplay),
                raw = pendingRaw,
            )
        }

    /** Compatibility input for a pending suffix. New middle edits use [replacePendingRomaji]. */
    fun replace(display: String, resolvedPrefix: String, pendingRaw: String = "") {
        val wasActive = isActive
        val result = compositionState.replaceComposition(display, resolvedPrefix)
        require(result is CompositionCursorMutation.Applied) {
            "Invalid romaji composition: $result"
        }
        this.pendingRaw = pendingRaw.takeIf { compositionState.hasPendingInput }.orEmpty()
        if (!wasActive) compositionStartUtf16 = null
        trimExpectedSelections()
    }

    /** Ends the lifecycle while retaining the same authoritative state instance and revisions. */
    fun clear() {
        compositionState.replaceComposition(
            displayText = "",
            cursorScalar = 0,
            pendingRange = null,
        )
        pendingRaw = ""
        compositionStartUtf16 = null
        expectedSelections.clear()
    }

    /**
     * Inserts or refreshes pending romaji at the current cursor/range without rebuilding suffixes.
     * Raw changes advance composition revision even when their visible display is unchanged.
     */
    fun replacePendingRomaji(
        display: String,
        raw: String,
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation {
        val result = compositionState.replacePendingDisplay(
            display = display,
            logicalInputChanged = raw != pendingRaw,
            expected = expected,
        )
        if (result is CompositionCursorMutation.Applied) {
            pendingRaw = raw.takeIf { compositionState.hasPendingInput }.orEmpty()
        }
        return result
    }

    /** Replaces only the pending middle range and keeps its left/right resolved text exact. */
    fun resolvePendingRomaji(
        replacement: String,
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation {
        val result = compositionState.resolvePendingDisplay(replacement, expected)
        if (result is CompositionCursorMutation.Applied) pendingRaw = ""
        return result
    }

    fun insertResolvedAtCursor(
        value: String,
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation = compositionState.insertAtCursor(value, expected)

    fun deleteGraphemeBeforeCursor(
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation = compositionState.deleteGraphemeBeforeCursor(expected)

    /** Register the callback caused by setComposingText or setSelection. */
    fun expectSelfSelection(relativeUtf16: Int) {
        if (!isActive || relativeUtf16 !in 0..displayText.length) return
        expectedSelections.addLast(
            ExpectedSelection(
                compositionRevision = compositionRevision,
                cursorRevision = cursorRevision,
                textUtf16Length = displayText.length,
                relativeUtf16 = relativeUtf16,
            )
        )
        trimExpectedSelections()
    }

    fun onUpdateSelection(
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ): RomajiSelectionAction {
        if (!isActive) return RomajiSelectionAction.Ignored
        if (newSelStart != newSelEnd || candidatesStart < 0 || candidatesEnd < candidatesStart) {
            return RomajiSelectionAction.FinishComposition
        }

        val candidateLength = candidatesEnd - candidatesStart
        val relativeUtf16 = newSelStart - candidatesStart
        val currentExpected = expectedSelections.firstOrNull { expected ->
            expected.compositionRevision == compositionRevision &&
                expected.cursorRevision == cursorRevision &&
                expected.textUtf16Length == candidateLength &&
                expected.relativeUtf16 == relativeUtf16
        }
        if (currentExpected != null) {
            expectedSelections.remove(currentExpected)
            compositionStartUtf16 = candidatesStart
            return if (relativeUtf16 == cursorUtf16) {
                RomajiSelectionAction.SelfUpdate
            } else {
                RomajiSelectionAction.RestoreCursor(candidatesStart + cursorUtf16)
            }
        }

        // A callback may arrive after a later expected callback or composition edit. Consume only
        // the matching stale entry; never reinterpret it as an external cursor move.
        val delayedExpected = expectedSelections.firstOrNull { expected ->
            expected.textUtf16Length == candidateLength &&
                expected.relativeUtf16 == relativeUtf16
        }
        if (delayedExpected != null) {
            expectedSelections.remove(delayedExpected)
            return RomajiSelectionAction.RestoreCursor(candidatesStart + cursorUtf16)
        }

        if (candidateLength != displayText.length) {
            return RomajiSelectionAction.FinishComposition
        }
        compositionStartUtf16 = candidatesStart
        if (relativeUtf16 !in 0..displayText.length) {
            return RomajiSelectionAction.FinishComposition
        }
        val scalar = compositionState.utf16ToScalar(relativeUtf16)
            ?: return RomajiSelectionAction.RestoreCursor(candidatesStart + cursorUtf16)
        if (!compositionState.isGraphemeBoundary(scalar)) {
            return RomajiSelectionAction.RestoreCursor(candidatesStart + cursorUtf16)
        }
        if (scalar == compositionState.cursorScalar) return RomajiSelectionAction.SelfUpdate

        return when (compositionState.moveCursorToScalar(scalar)) {
            is CompositionCursorMutation.Applied -> RomajiSelectionAction.CursorChanged(relativeUtf16)
            is CompositionCursorMutation.Rejected -> {
                RomajiSelectionAction.RestoreCursor(candidatesStart + cursorUtf16)
            }
        }
    }

    /** User-facing Space movement; [deltaInGraphemes] is never a scalar or UTF-16 delta. */
    fun moveCursorByGrapheme(deltaInGraphemes: Int): Boolean {
        if (!isActive || deltaInGraphemes == 0) return false
        val result = compositionState.moveCursorByGrapheme(deltaInGraphemes)
        return result is CompositionCursorMutation.Applied && result.cursorChanged
    }

    /**
     * Temporary source-compatible handoff for the service. Its delta is interpreted as graphemes;
     * A0 should rename the call site to [moveCursorByGrapheme].
     */
    @Deprecated("Use moveCursorByGrapheme; Space deltas are grapheme units")
    fun moveByScalarDelta(deltaInGraphemes: Int): Boolean =
        moveCursorByGrapheme(deltaInGraphemes)

    /** No dictionary request is produced while any raw/pending romaji range exists. */
    fun conversionSlice(): RomajiConversionSlice? {
        if (!canRequestDictionary) return null
        val cursor = cursorUtf16
        val reading = displayText.substring(0, cursor)
        if (reading.isEmpty()) return null
        return RomajiConversionSlice(
            request = RomajiCursorRequest(reading, compositionRevision, cursorRevision),
            resolvedSuffix = displayText.substring(cursor),
            unresolvedSuffix = "",
            unresolvedRaw = "",
            isWholeComposition = cursor == displayText.length,
        )
    }

    fun isCurrent(request: RomajiCursorRequest): Boolean =
        canRequestDictionary &&
            request.compositionRevision == compositionRevision &&
            request.cursorRevision == cursorRevision &&
            request.reading == displayText.substring(0, cursorUtf16)

    private fun trimExpectedSelections() {
        while (expectedSelections.size > MAX_EXPECTED_SELECTIONS) {
            expectedSelections.removeFirst()
        }
    }

    private companion object {
        const val MAX_EXPECTED_SELECTIONS = 16
    }
}
