package com.example.furiganakeyboard.recognizer

import com.example.furiganakeyboard.reading.KanjiUsagePriority

/** Applies a bounded common-use bias without crossing a clear shape-cost gap. */
object KanjiCandidateRanker {
    fun rank(
        candidates: List<RecognitionCandidate>,
        priorities: Map<String, KanjiUsagePriority>
    ): List<RecognitionCandidate> {
        val normalized = RecognitionScoreNormalizer.normalizeIfNeeded(candidates)
        // Non-Han slots never move, so usage data cannot exchange kana and kanji.
        val hanSlots = normalized.indices.filter { isHan(normalized[it].text) }
        if (hanSlots.size < 2) return normalized
        val shapeOrdered = hanSlots.map { normalized[it] }.withIndex().sortedWith(
            compareBy<IndexedValue<RecognitionCandidate>> { it.value.shapeCost }
                .thenBy { it.index }
        )
        // A cluster boundary is absolute: usage bias is only applied within it.
        val clusters = mutableListOf<MutableList<IndexedValue<RecognitionCandidate>>>()
        shapeOrdered.forEach { candidate ->
            val previous = clusters.lastOrNull()?.lastOrNull()?.value
            if (previous == null || candidate.value.shapeCost - previous.shapeCost >= CLEAR_SHAPE_GAP) {
                clusters += mutableListOf(candidate)
            } else {
                clusters.last() += candidate
            }
        }
        val rankedHan = clusters.flatMap { cluster ->
            cluster.sortedWith(
                compareBy<IndexedValue<RecognitionCandidate>> {
                    it.value.shapeCost - usageDiscount(priorities[it.value.text])
                }.thenBy { it.value.shapeCost }
                    .thenBy { it.index }
            ).map { it.value }
        }

        return normalized.toMutableList().also { output ->
            hanSlots.forEachIndexed { index, slot -> output[slot] = rankedHan[index] }
        }
    }

    private fun usageDiscount(priority: KanjiUsagePriority?): Float =
        MAX_USAGE_DISCOUNT * (usageWeight(priority) / MAX_USAGE_WEIGHT)

    private fun usageWeight(priority: KanjiUsagePriority?): Float {
        if (priority == null) return 0f
        val category = when {
            priority.isJoyo -> JOYO_WEIGHT
            priority.isJinmeiyo -> JINMEIYO_WEIGHT
            else -> 0f
        }
        val frequency = when (priority.frequency) {
            in 1..500 -> VERY_COMMON_WEIGHT
            in 501..1_000 -> COMMON_WEIGHT
            in 1_001..2_500 -> FREQUENCY_WEIGHT
            else -> 0f
        }
        return category + frequency
    }

    private fun isHan(text: String): Boolean = text.isNotEmpty() &&
        Character.UnicodeScript.of(text.codePointAt(0)) == Character.UnicodeScript.HAN

    internal const val CLEAR_SHAPE_GAP = 0.2f
    // Kept below CLEAR_SHAPE_GAP as a second guard against crossing shape evidence.
    private const val MAX_USAGE_DISCOUNT = 0.12f
    private const val JOYO_WEIGHT = 1.0f
    private const val JINMEIYO_WEIGHT = 0.2f
    private const val VERY_COMMON_WEIGHT = 0.7f
    private const val COMMON_WEIGHT = 0.45f
    private const val FREQUENCY_WEIGHT = 0.2f
    private const val MAX_USAGE_WEIGHT = JOYO_WEIGHT + VERY_COMMON_WEIGHT
}
