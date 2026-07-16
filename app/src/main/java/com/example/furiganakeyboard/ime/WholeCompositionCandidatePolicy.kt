package com.example.furiganakeyboard.ime

import com.example.furiganakeyboard.reading.WordReadingCandidate

/** Why a candidate is eligible at the terminal edge of a composition. */
enum class WholeCompositionCandidateKind {
    /** A conversion whose reading consumes the complete composition. */
    WHOLE_CONVERSION,

    /** A completion whose reading strictly extends the complete composition. */
    PREDICTION,

    /** Plain hiragana/katakana supplied by the caller as a last-resort choice. */
    SCRIPT_FALLBACK,
}

/** A terminal-composition candidate with its presentation policy made explicit. */
data class WholeCompositionCandidate(
    val surface: String,
    val readings: List<String>,
    val kind: WholeCompositionCandidateKind,
)

/**
 * Pure ordering and eligibility policy for candidates shown at the composition end.
 *
 * Dictionary/converter order is preserved within each category. Exact whole-reading candidates
 * precede predictions, and script fallbacks come last. A candidate supported only by a strict
 * reading prefix is excluded: terminal conversion must never turn a partial lexeme such as the
 * left side of `word + particle` into an ordinary whole-composition candidate.
 */
object WholeCompositionCandidatePolicy {
    /**
     * [dictionaryCandidates] must retain converter/repository order and be over-fetched beyond the
     * display capacity. This function owns [limit], so rejected prefix-only entries cannot consume
     * slots that should be available to later whole candidates, predictions, or fallbacks.
     */
    fun build(
        reading: String,
        dictionaryCandidates: List<WordReadingCandidate>,
        scriptFallbacks: List<WordReadingCandidate>,
        limit: Int,
    ): List<WholeCompositionCandidate> {
        if (reading.isEmpty() || limit <= 0) return emptyList()

        val classified = dictionaryCandidates.mapNotNull { candidate ->
            if (candidate.surface.isEmpty()) return@mapNotNull null
            val kind = when {
                candidate.readings.any { it == reading } ->
                    WholeCompositionCandidateKind.WHOLE_CONVERSION
                candidate.readings.any { it.isStrictExtensionOf(reading) } ->
                    WholeCompositionCandidateKind.PREDICTION
                else -> null
            } ?: return@mapNotNull null
            candidate.toPolicyCandidate(kind)
        }
        val wholeConversions = classified.filter {
            it.kind == WholeCompositionCandidateKind.WHOLE_CONVERSION
        }
        val predictions = classified.filter {
            it.kind == WholeCompositionCandidateKind.PREDICTION
        }
        val fallbacks = scriptFallbacks.asSequence()
            .filter { it.surface.isNotEmpty() }
            .map { it.toPolicyCandidate(WholeCompositionCandidateKind.SCRIPT_FALLBACK) }

        val unique = LinkedHashMap<String, WholeCompositionCandidate>()
        (wholeConversions.asSequence() + predictions.asSequence() + fallbacks).forEach { candidate ->
            unique.putIfAbsent(candidate.surface, candidate)
        }
        return unique.values.take(limit)
    }

    private fun String.isStrictExtensionOf(prefix: String): Boolean =
        startsWith(prefix) && codePointCount(0, length) > prefix.codePointCount(0, prefix.length)

    private fun WordReadingCandidate.toPolicyCandidate(
        kind: WholeCompositionCandidateKind,
    ) = WholeCompositionCandidate(surface, readings, kind)
}
