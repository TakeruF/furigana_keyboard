package com.example.furiganakeyboard.ime

/** Pair checked by asynchronous work and externally initiated state transitions. */
data class CompositionCursorRevision(
    val composition: Long,
    val cursor: Long,
)

/** Half-open Unicode-scalar range. */
data class CompositionScalarRange(
    val start: Int,
    val end: Int,
) {
    init {
        require(start >= 0 && end >= start) { "Invalid scalar range [$start, $end)" }
    }
}

/** Immutable view of the current pure composition state. */
data class CompositionCursorSnapshot(
    val text: String,
    val cursorScalar: Int,
    val cursorUtf16: Int,
    /** Compatibility boundary: start of pending input, or the text end when fully resolved. */
    val resolvedScalarEnd: Int,
    val resolvedUtf16End: Int,
    val pendingRange: CompositionScalarRange?,
    val revision: CompositionCursorRevision,
)

/** Text around the cursor. [suffix] is always preserved by prefix replacement. */
data class CompositionCursorSlice(
    val prefix: String,
    val suffix: String,
    val resolvedSuffix: String,
    /** Pending display text, which may be in the middle rather than at the text end. */
    val unresolvedSuffix: String,
    val revision: CompositionCursorRevision,
)

enum class CompositionCursorRejection {
    STALE_REVISION,
    SCALAR_OUT_OF_BOUNDS,
    INVALID_UTF16_BOUNDARY,
    INVALID_GRAPHEME_BOUNDARY,
    REVERSED_RANGE,
    UNRESOLVED_BOUNDARY,
    PENDING_INPUT_ACTIVE,
    PENDING_RANGE_MISMATCH,
    RESOLVED_PREFIX_MISMATCH,
    MALFORMED_UTF16,
}

sealed interface CompositionCursorMutation {
    val snapshot: CompositionCursorSnapshot

    data class Applied(
        override val snapshot: CompositionCursorSnapshot,
        val compositionChanged: Boolean,
        val cursorChanged: Boolean,
    ) : CompositionCursorMutation

    data class Rejected(
        override val snapshot: CompositionCursorSnapshot,
        val reason: CompositionCursorRejection,
    ) : CompositionCursorMutation
}

/**
 * InputConnection-independent source of truth for composition text, cursor, and revisions.
 *
 * All owned positions and editable ranges are Unicode-scalar offsets. Android UTF-16 offsets are
 * accepted or emitted only through explicit conversion APIs. Pending romaji is a half-open range,
 * not an assumed suffix, so resolved text may exist on both sides without being duplicated.
 */
class CompositionCursorState private constructor(
    initialText: String,
    initialPendingRange: CompositionScalarRange?,
    initialCursorScalar: Int,
) {
    var text: String = initialText
        private set

    var cursorScalar: Int = initialCursorScalar
        private set

    var pendingRange: CompositionScalarRange? = initialPendingRange
        private set

    var compositionRevision: Long = 0
        private set

    var cursorRevision: Long = 0
        private set

    val revision: CompositionCursorRevision
        get() = CompositionCursorRevision(compositionRevision, cursorRevision)

    val cursorUtf16: Int
        get() = scalarToUtf16Internal(cursorScalar)

    /** Compatibility view for callers that previously modeled pending input as a suffix. */
    val resolvedScalarEnd: Int
        get() = pendingRange?.start ?: scalarLength

    val resolvedUtf16End: Int
        get() = scalarToUtf16Internal(resolvedScalarEnd)

    val scalarLength: Int
        get() = scalarCount(text)

    val hasPendingInput: Boolean
        get() = pendingRange != null

    val canRequestDictionary: Boolean
        get() = pendingRange == null

    val pendingDisplay: String?
        get() = pendingRange?.let { range -> scalarSubstring(range.start, range.end) }

    fun snapshot(): CompositionCursorSnapshot = CompositionCursorSnapshot(
        text = text,
        cursorScalar = cursorScalar,
        cursorUtf16 = cursorUtf16,
        resolvedScalarEnd = resolvedScalarEnd,
        resolvedUtf16End = resolvedUtf16End,
        pendingRange = pendingRange,
        revision = revision,
    )

    fun slice(): CompositionCursorSlice {
        val cursor = cursorUtf16
        val pending = pendingRange
        val pendingStart = pending?.let { scalarToUtf16Internal(it.start) }
        val pendingEnd = pending?.let { scalarToUtf16Internal(it.end) }
        val resolvedSuffix = when {
            pending == null -> text.substring(cursor)
            cursor <= checkNotNull(pendingStart) -> text.substring(cursor, pendingStart)
            cursor >= checkNotNull(pendingEnd) -> text.substring(cursor)
            else -> ""
        }
        return CompositionCursorSlice(
            prefix = text.substring(0, cursor),
            suffix = text.substring(cursor),
            resolvedSuffix = resolvedSuffix,
            unresolvedSuffix = if (pending == null) {
                ""
            } else {
                text.substring(checkNotNull(pendingStart), checkNotNull(pendingEnd))
            },
            revision = revision,
        )
    }

    /** Returns null for out-of-range scalar offsets. */
    fun scalarToUtf16(scalarOffset: Int): Int? =
        scalarOffset.takeIf { it in 0..scalarLength }?.let(::scalarToUtf16Internal)

    /** Returns null for out-of-range offsets and offsets that split a surrogate pair. */
    fun utf16ToScalar(utf16Offset: Int): Int? {
        if (utf16Offset !in 0..text.length || !isScalarBoundary(text, utf16Offset)) return null
        return text.codePointCount(0, utf16Offset)
    }

    fun isGraphemeBoundary(scalarOffset: Int): Boolean =
        scalarOffset in UnicodeGraphemeBoundaries.scalarOffsets(text)

    fun isCurrent(expected: CompositionCursorRevision): Boolean = expected == revision

    /** Low-level scalar movement for conversion algorithms. */
    fun moveCursorToScalar(
        targetScalar: Int,
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation {
        rejectIfStale(expected)?.let { return it }
        if (targetScalar !in 0..scalarLength) {
            return rejected(CompositionCursorRejection.SCALAR_OUT_OF_BOUNDS)
        }
        if (pendingRange != null && targetScalar != cursorScalar) {
            return rejected(CompositionCursorRejection.PENDING_INPUT_ACTIVE)
        }
        return applyCursor(targetScalar)
    }

    fun moveCursorToUtf16(
        targetUtf16: Int,
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation {
        rejectIfStale(expected)?.let { return it }
        val scalar = utf16ToScalar(targetUtf16)
            ?: return rejected(CompositionCursorRejection.INVALID_UTF16_BOUNDARY)
        return moveCursorToScalar(scalar, expected)
    }

    fun moveCursorByScalar(
        delta: Int,
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation {
        rejectIfStale(expected)?.let { return it }
        val target = cursorScalar.toLong() + delta.toLong()
        if (target !in 0L..scalarLength.toLong()) {
            return rejected(CompositionCursorRejection.SCALAR_OUT_OF_BOUNDS)
        }
        return moveCursorToScalar(target.toInt(), expected)
    }

    /** User-facing movement. It clamps at the text ends and never enters pending input. */
    fun moveCursorByGrapheme(
        delta: Int,
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation {
        rejectIfStale(expected)?.let { return it }
        if (delta == 0) return appliedUnchanged()
        if (pendingRange != null) {
            return rejected(CompositionCursorRejection.PENDING_INPUT_ACTIVE)
        }
        val boundaries = UnicodeGraphemeBoundaries.scalarOffsets(text)
        val currentIndex = boundaries.indexOf(cursorScalar)
        if (currentIndex < 0) {
            return rejected(CompositionCursorRejection.INVALID_GRAPHEME_BOUNDARY)
        }
        val targetIndex = (currentIndex.toLong() + delta.toLong())
            .coerceIn(0L, boundaries.lastIndex.toLong())
            .toInt()
        return applyCursor(boundaries[targetIndex])
    }

    /** Inserts fully resolved text immediately before the cursor. */
    fun insertAtCursor(
        value: String,
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation = replaceScalarRange(
        startScalar = cursorScalar,
        endScalar = cursorScalar,
        replacement = value,
        expected = expected,
    )

    /** Deletes one Unicode scalar before the cursor. Conversion-only low-level API. */
    fun deleteBeforeCursor(
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation {
        rejectIfStale(expected)?.let { return it }
        if (cursorScalar == 0) return appliedUnchanged()
        return replaceScalarRange(cursorScalar - 1, cursorScalar, "", expected)
    }

    /** Deletes one complete user-perceived grapheme before the cursor. */
    fun deleteGraphemeBeforeCursor(
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation {
        rejectIfStale(expected)?.let { return it }
        if (pendingRange != null) {
            return rejected(CompositionCursorRejection.PENDING_INPUT_ACTIVE)
        }
        if (cursorScalar == 0) return appliedUnchanged()
        val boundaries = UnicodeGraphemeBoundaries.scalarOffsets(text)
        val cursorIndex = boundaries.indexOf(cursorScalar)
        if (cursorIndex <= 0) {
            return rejected(CompositionCursorRejection.INVALID_GRAPHEME_BOUNDARY)
        }
        return replaceScalarRange(boundaries[cursorIndex - 1], cursorScalar, "", expected)
    }

    /** Deletes one Unicode scalar after the cursor. Conversion-only low-level API. */
    fun deleteAfterCursor(
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation {
        rejectIfStale(expected)?.let { return it }
        if (cursorScalar >= scalarLength) return appliedUnchanged()
        return replaceScalarRange(cursorScalar, cursorScalar + 1, "", expected)
    }

    /** Replaces `[composition start, cursor]` and preserves the exact right suffix. */
    fun replacePrefixAtCursor(
        replacement: String,
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation = replaceScalarRange(
        startScalar = 0,
        endScalar = cursorScalar,
        replacement = replacement,
        expected = expected,
    )

    /** Resolved range replacement. A range intersecting pending display is rejected. */
    fun replaceScalarRange(
        startScalar: Int,
        endScalar: Int,
        replacement: String,
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation {
        rejectIfStale(expected)?.let { return it }
        validateReplacement(startScalar, endScalar, replacement)?.let { return it }

        val pending = pendingRange
        if (pending != null && intersectsPending(startScalar, endScalar, pending)) {
            return rejected(CompositionCursorRejection.UNRESOLVED_BOUNDARY)
        }
        val replacementScalars = scalarCount(replacement)
        val nextPending = pending?.let { range ->
            if (endScalar <= range.start) {
                val delta = replacementScalars - (endScalar - startScalar)
                CompositionScalarRange(range.start + delta, range.end + delta)
            } else {
                range
            }
        }
        return replaceRangeInternal(
            startScalar,
            endScalar,
            replacement,
            nextPending,
            startScalar + replacementScalars,
        )
    }

    fun replaceUtf16Range(
        startUtf16: Int,
        endUtf16: Int,
        replacement: String,
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation {
        rejectIfStale(expected)?.let { return it }
        if (startUtf16 > endUtf16) {
            return rejected(CompositionCursorRejection.REVERSED_RANGE)
        }
        val startScalar = utf16ToScalar(startUtf16)
            ?: return rejected(CompositionCursorRejection.INVALID_UTF16_BOUNDARY)
        val endScalar = utf16ToScalar(endUtf16)
            ?: return rejected(CompositionCursorRejection.INVALID_UTF16_BOUNDARY)
        return replaceScalarRange(startScalar, endScalar, replacement, expected)
    }

    /**
     * Inserts or updates pending display at its explicit middle range while preserving both sides.
     * [logicalInputChanged] lets the adapter advance composition revision when raw romaji changes
     * but renders to the same display text.
     */
    fun replacePendingDisplay(
        display: String,
        logicalInputChanged: Boolean = false,
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation {
        rejectIfStale(expected)?.let { return it }
        if (!isWellFormedUtf16(display)) {
            return rejected(CompositionCursorRejection.MALFORMED_UTF16)
        }
        val current = pendingRange
        val start = current?.start ?: cursorScalar
        val end = current?.end ?: cursorScalar
        val displayScalars = scalarCount(display)
        val nextPending = if (display.isEmpty()) {
            null
        } else {
            CompositionScalarRange(start, start + displayScalars)
        }
        return replaceRangeInternal(
            start,
            end,
            display,
            nextPending,
            start + displayScalars,
            forceCompositionChanged = logicalInputChanged,
        )
    }

    /** Resolves the pending middle range and preserves its exact left and right text. */
    fun resolvePendingDisplay(
        replacement: String,
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation {
        rejectIfStale(expected)?.let { return it }
        if (!isWellFormedUtf16(replacement)) {
            return rejected(CompositionCursorRejection.MALFORMED_UTF16)
        }
        val pending = pendingRange
            ?: return rejected(CompositionCursorRejection.PENDING_RANGE_MISMATCH)
        val replacementScalars = scalarCount(replacement)
        return replaceRangeInternal(
            pending.start,
            pending.end,
            replacement,
            nextPendingRange = null,
            nextCursorScalar = pending.start + replacementScalars,
        )
    }

    /** Compatibility API that describes an unresolved suffix through a resolved prefix. */
    fun replaceComposition(
        displayText: String,
        resolvedPrefix: String,
        cursorScalar: Int? = null,
        expected: CompositionCursorRevision? = null,
    ): CompositionCursorMutation {
        rejectIfStale(expected)?.let { return it }
        if (!isWellFormedUtf16(displayText) || !isWellFormedUtf16(resolvedPrefix)) {
            return rejected(CompositionCursorRejection.MALFORMED_UTF16)
        }
        if (!displayText.startsWith(resolvedPrefix)) {
            return rejected(CompositionCursorRejection.RESOLVED_PREFIX_MISMATCH)
        }
        val resolvedEnd = scalarCount(resolvedPrefix)
        val length = scalarCount(displayText)
        val pending = if (resolvedEnd == length) {
            null
        } else {
            CompositionScalarRange(resolvedEnd, length)
        }
        val nextCursor = cursorScalar ?: resolvedEnd
        return replaceComposition(displayText, nextCursor, pending, expected)
    }

    /** General composition replacement with a pending range at any scalar position. */
    fun replaceComposition(
        displayText: String,
        cursorScalar: Int,
        pendingRange: CompositionScalarRange?,
        expected: CompositionCursorRevision? = null,
        logicalInputChanged: Boolean = false,
    ): CompositionCursorMutation {
        rejectIfStale(expected)?.let { return it }
        if (!isWellFormedUtf16(displayText)) {
            return rejected(CompositionCursorRejection.MALFORMED_UTF16)
        }
        val length = scalarCount(displayText)
        if (cursorScalar !in 0..length) {
            return rejected(CompositionCursorRejection.SCALAR_OUT_OF_BOUNDS)
        }
        if (pendingRange != null &&
            (pendingRange.start == pendingRange.end ||
                pendingRange.end > length ||
                cursorScalar in (pendingRange.start + 1) until pendingRange.end)
        ) {
            return rejected(CompositionCursorRejection.PENDING_RANGE_MISMATCH)
        }
        return applyText(
            nextText = displayText,
            nextPendingRange = pendingRange,
            nextCursorScalar = cursorScalar,
            forceCompositionChanged = logicalInputChanged,
        )
    }

    private fun validateReplacement(
        startScalar: Int,
        endScalar: Int,
        replacement: String,
    ): CompositionCursorMutation.Rejected? {
        if (!isWellFormedUtf16(replacement)) {
            return rejected(CompositionCursorRejection.MALFORMED_UTF16)
        }
        if (startScalar > endScalar) {
            return rejected(CompositionCursorRejection.REVERSED_RANGE)
        }
        if (startScalar !in 0..scalarLength || endScalar !in 0..scalarLength) {
            return rejected(CompositionCursorRejection.SCALAR_OUT_OF_BOUNDS)
        }
        return null
    }

    private fun intersectsPending(
        start: Int,
        end: Int,
        pending: CompositionScalarRange,
    ): Boolean = if (start == end) {
        start > pending.start && start < pending.end
    } else {
        start < pending.end && end > pending.start
    }

    private fun replaceRangeInternal(
        startScalar: Int,
        endScalar: Int,
        replacement: String,
        nextPendingRange: CompositionScalarRange?,
        nextCursorScalar: Int,
        forceCompositionChanged: Boolean = false,
    ): CompositionCursorMutation {
        val startUtf16 = scalarToUtf16Internal(startScalar)
        val endUtf16 = scalarToUtf16Internal(endScalar)
        val nextText = buildString(startUtf16 + replacement.length + text.length - endUtf16) {
            append(text, 0, startUtf16)
            append(replacement)
            append(text, endUtf16, text.length)
        }
        return applyText(
            nextText,
            nextPendingRange,
            nextCursorScalar,
            forceCompositionChanged,
        )
    }

    private fun rejectIfStale(
        expected: CompositionCursorRevision?,
    ): CompositionCursorMutation.Rejected? = expected?.takeUnless(::isCurrent)?.let {
        rejected(CompositionCursorRejection.STALE_REVISION)
    }

    private fun applyCursor(nextCursorScalar: Int): CompositionCursorMutation.Applied {
        val changed = nextCursorScalar != cursorScalar
        if (changed) {
            cursorScalar = nextCursorScalar
            cursorRevision++
        }
        return CompositionCursorMutation.Applied(snapshot(), false, changed)
    }

    private fun applyText(
        nextText: String,
        nextPendingRange: CompositionScalarRange?,
        nextCursorScalar: Int,
        forceCompositionChanged: Boolean = false,
    ): CompositionCursorMutation.Applied {
        val compositionChanged = forceCompositionChanged ||
            nextText != text || nextPendingRange != pendingRange
        val cursorChanged = nextCursorScalar != cursorScalar
        text = nextText
        pendingRange = nextPendingRange
        cursorScalar = nextCursorScalar
        if (compositionChanged) compositionRevision++
        if (cursorChanged) cursorRevision++
        return CompositionCursorMutation.Applied(snapshot(), compositionChanged, cursorChanged)
    }

    private fun appliedUnchanged() = CompositionCursorMutation.Applied(snapshot(), false, false)

    private fun rejected(reason: CompositionCursorRejection) =
        CompositionCursorMutation.Rejected(snapshot(), reason)

    private fun scalarSubstring(start: Int, end: Int): String =
        text.substring(scalarToUtf16Internal(start), scalarToUtf16Internal(end))

    private fun scalarToUtf16Internal(scalarOffset: Int): Int =
        text.offsetByCodePoints(0, scalarOffset)

    companion object {
        fun create(
            displayText: String = "",
            resolvedPrefix: String = displayText,
            cursorScalar: Int? = null,
        ): CompositionCursorState {
            require(isWellFormedUtf16(displayText)) { "displayText is malformed UTF-16" }
            require(isWellFormedUtf16(resolvedPrefix)) { "resolvedPrefix is malformed UTF-16" }
            require(displayText.startsWith(resolvedPrefix)) {
                "resolvedPrefix must be a displayText prefix"
            }
            val resolvedEnd = scalarCount(resolvedPrefix)
            val length = scalarCount(displayText)
            val pending = if (resolvedEnd == length) {
                null
            } else {
                CompositionScalarRange(resolvedEnd, length)
            }
            val initialCursor = cursorScalar ?: resolvedEnd
            require(initialCursor in 0..resolvedEnd) {
                "cursor must be inside the resolved prefix"
            }
            return CompositionCursorState(displayText, pending, initialCursor)
        }

        private fun scalarCount(value: String): Int = value.codePointCount(0, value.length)

        private fun isScalarBoundary(value: String, utf16Offset: Int): Boolean =
            utf16Offset == 0 || utf16Offset == value.length ||
                !(value[utf16Offset].isLowSurrogate() && value[utf16Offset - 1].isHighSurrogate())

        private fun isWellFormedUtf16(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                val character = value[index]
                when {
                    character.isHighSurrogate() -> {
                        if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                            return false
                        }
                        index += 2
                    }
                    character.isLowSurrogate() -> return false
                    else -> index++
                }
            }
            return true
        }
    }
}
