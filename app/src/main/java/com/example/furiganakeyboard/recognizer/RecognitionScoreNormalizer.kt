package com.example.furiganakeyboard.recognizer

/** The information a recognizer exposes before common-cost normalization. */
data class RawRecognitionCandidate(
    val text: String,
    val score: Float? = null
)

enum class RawScoreSemantics {
    /** A finite, larger score represents a better native shape match. */
    NATIVE_HIGHER_IS_BETTER,

    /** Only candidate order carries shape information. */
    RANK_ONLY
}

/**
 * Maps recognizer-specific output to the shared lower-is-better [0, 1] shape-cost band.
 *
 * Native scores are anchored at best = 0 without stretching a narrow score range;
 * rank-only output advances by 0.1 per unique candidate. If any native score is
 * NaN, infinite, or absent, the entire batch uses the rank mapping. Equal finite
 * native scores therefore have equal cost instead of inventing a shape difference.
 */
object RecognitionScoreNormalizer {
    fun normalize(
        rawCandidates: List<RawRecognitionCandidate>,
        source: RecognitionSource,
        semantics: RawScoreSemantics
    ): List<RecognitionCandidate> {
        require(source != RecognitionSource.SIDE_BY_SIDE) {
            "Side-by-side candidates must retain component evidence"
        }

        // Exact duplicates use their first recognizer occurrence. rawRank is
        // deliberately the pre-deduplication rank so the source output remains auditable.
        val unique = LinkedHashMap<String, IndexedValue<RawRecognitionCandidate>>()
        rawCandidates.withIndex().forEach { indexed ->
            unique.putIfAbsent(indexed.value.text, indexed)
        }
        val values = unique.values.toList()
        if (values.isEmpty()) return emptyList()

        val nativeScoresAreUsable = semantics == RawScoreSemantics.NATIVE_HIGHER_IS_BETTER &&
            values.all { it.value.score?.isFinite() == true }
        val bestNativeScore = if (nativeScoresAreUsable) {
            values.maxOf { checkNotNull(it.value.score) }
        } else {
            0f
        }
        val worstNativeScore = if (nativeScoresAreUsable) {
            values.minOf { checkNotNull(it.value.score) }
        } else {
            0f
        }
        val nativeScale = (bestNativeScore - worstNativeScore)
            .coerceAtLeast(MIN_NATIVE_SCORE_RANGE)

        return values.mapIndexed { uniqueRank, indexed ->
            val rankCost = (uniqueRank * RANK_COST_STEP).coerceAtMost(MAX_SHAPE_COST)
            val shapeCost = when {
                !nativeScoresAreUsable -> rankCost
                bestNativeScore == worstNativeScore -> 0f
                else -> ((bestNativeScore - checkNotNull(indexed.value.score)) / nativeScale)
                    .coerceIn(0f, MAX_SHAPE_COST)
            }
            RecognitionCandidate(
                text = indexed.value.text,
                shapeCost = shapeCost,
                evidence = RecognitionEvidence(
                    source = source,
                    rawScore = indexed.value.score,
                    rawRank = indexed.index
                )
            )
        }.sortedWith(
            compareBy<RecognitionCandidate> { it.shapeCost }
                .thenBy { checkNotNull(it.evidence.rawRank) }
        )
    }

    /** Normalize old/JNI candidates unless they already carry a finite common cost. */
    internal fun normalizeIfNeeded(
        candidates: List<RecognitionCandidate>,
        source: RecognitionSource = RecognitionSource.LEGACY_NATIVE
    ): List<RecognitionCandidate> {
        if (candidates.all { it.shapeCost.isFinite() && it.shapeCost in 0f..MAX_SHAPE_COST }) {
            return candidates
        }
        return normalize(
            candidates.map { RawRecognitionCandidate(it.text, it.evidence.rawScore) },
            source,
            RawScoreSemantics.NATIVE_HIGHER_IS_BETTER
        )
    }

    private const val RANK_COST_STEP = 0.1f
    private const val MIN_NATIVE_SCORE_RANGE = 1f
    private const val MAX_SHAPE_COST = 1f
}
