package com.example.furiganakeyboard.recognizer

import com.example.furiganakeyboard.reading.KanjiUsagePriority

/** Applies a bounded common-use bias while preserving Zinnia's shape confidence. */
object KanjiCandidateRanker {
    fun rank(
        candidates: List<RecognitionCandidate>,
        priorities: Map<String, KanjiUsagePriority>
    ): List<RecognitionCandidate> {
        val hanSlots = candidates.indices.filter { isHan(candidates[it].text) }
        if (hanSlots.size < 2) return candidates
        val han = hanSlots.map { candidates[it] }
        val scoreSteps = han.asSequence()
            .zipWithNext { first, second -> (first.score - second.score).coerceAtLeast(0f) }
            .take(4)
            .filter { it > 0f }
            .toList()
        val typicalScoreStep = scoreSteps.sorted()
            .let { if (it.isEmpty()) MIN_SCORE_STEP else it[(it.size - 1) / 2] }
            .coerceAtLeast(MIN_SCORE_STEP)

        val rankedHan = han.withIndex().sortedWith(
            compareByDescending<IndexedValue<RecognitionCandidate>> {
                it.value.score + typicalScoreStep * usageWeight(priorities[it.value.text])
            }.thenBy { it.index }
        ).map { it.value }

        return candidates.toMutableList().also { output ->
            hanSlots.forEachIndexed { index, slot -> output[slot] = rankedHan[index] }
        }
    }

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

    private const val MIN_SCORE_STEP = 0.01f
    private const val JOYO_WEIGHT = 1.0f
    private const val JINMEIYO_WEIGHT = 0.2f
    private const val VERY_COMMON_WEIGHT = 0.7f
    private const val COMMON_WEIGHT = 0.45f
    private const val FREQUENCY_WEIGHT = 0.2f
}
