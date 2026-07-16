package com.example.furiganakeyboard

import com.example.furiganakeyboard.recognizer.RawRecognitionCandidate
import com.example.furiganakeyboard.recognizer.RawScoreSemantics
import com.example.furiganakeyboard.recognizer.RecognitionScoreNormalizer
import com.example.furiganakeyboard.recognizer.RecognitionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionScoreNormalizerTest {
    @Test
    fun nativeAndRankOnlyResultsShareLowerIsBetterCostBand() {
        val native = normalize(
            listOf(raw("嶽", 10f), raw("学", 9.9f), raw("岳", 8f)),
            RawScoreSemantics.NATIVE_HIGHER_IS_BETTER
        )
        val rankOnly = normalize(
            listOf(raw("嶽"), raw("学"), raw("岳")),
            RawScoreSemantics.RANK_ONLY,
            RecognitionSource.ML_KIT
        )

        assertCosts(listOf(0f, 0.05f, 1f), native.map { it.shapeCost })
        assertCosts(listOf(0f, 0.1f, 0.2f), rankOnly.map { it.shapeCost })
        assertTrue((native + rankOnly).all { it.shapeCost in 0f..1f })
        assertTrue(rankOnly.all { it.evidence.source == RecognitionSource.ML_KIT })
    }

    @Test
    fun nonFiniteNativeScoresFallBackToRankCostWithoutLosingRawEvidence() {
        val candidates = normalize(
            listOf(raw("日", Float.NaN), raw("目", Float.POSITIVE_INFINITY), raw("曰", 2f)),
            RawScoreSemantics.NATIVE_HIGHER_IS_BETTER
        )

        assertCosts(listOf(0f, 0.1f, 0.2f), candidates.map { it.shapeCost })
        assertTrue(candidates[0].evidence.rawScore!!.isNaN())
        assertEquals(Float.POSITIVE_INFINITY, candidates[1].evidence.rawScore)
        assertEquals(listOf(0, 1, 2), candidates.map { it.evidence.rawRank })
    }

    @Test
    fun equalNativeScoresHaveEqualCostAndKeepStableRawRanks() {
        val candidates = normalize(
            listOf(raw("日", 4f), raw("目", 4f), raw("曰", 4f)),
            RawScoreSemantics.NATIVE_HIGHER_IS_BETTER
        )

        assertEquals(listOf("日", "目", "曰"), candidates.map { it.text })
        assertCosts(listOf(0f, 0f, 0f), candidates.map { it.shapeCost })
        assertEquals(listOf(0, 1, 2), candidates.map { it.evidence.rawRank })
    }

    @Test
    fun exactDuplicatesKeepFirstOccurrenceAndOriginalRank() {
        val candidates = normalize(
            listOf(raw("日"), raw("日"), raw("目"), raw("曰")),
            RawScoreSemantics.RANK_ONLY
        )

        assertEquals(listOf("日", "目", "曰"), candidates.map { it.text })
        assertEquals(listOf(0, 2, 3), candidates.map { it.evidence.rawRank })
        assertCosts(listOf(0f, 0.1f, 0.2f), candidates.map { it.shapeCost })
    }

    @Test
    fun singleCandidateIsZeroCostAndIdentifiableAsRecognizerRawTop() {
        val candidate = normalize(
            listOf(raw("日", 42f)),
            RawScoreSemantics.NATIVE_HIGHER_IS_BETTER
        ).single()

        assertEquals(0f, candidate.shapeCost)
        assertEquals(42f, candidate.evidence.rawScore)
        assertEquals(RecognitionSource.ZINNIA, candidate.evidence.source)
        assertEquals(0, candidate.evidence.rawRank)
        assertTrue(candidate.isRecognizerRawTop)
        assertFalse(candidate.evidence.components.isNotEmpty())
    }

    @Test
    fun legacyTwoArgumentConstructorRemainsAvailableToZinniaJni() {
        val constructor = com.example.furiganakeyboard.recognizer.RecognitionCandidate::class.java
            .getConstructor(String::class.java, Float::class.javaPrimitiveType)

        val candidate = constructor.newInstance("日", 42f)

        assertEquals("日", candidate.text)
        assertEquals(42f, candidate.score)
        assertTrue(candidate.shapeCost.isNaN())
    }

    private fun normalize(
        raw: List<RawRecognitionCandidate>,
        semantics: RawScoreSemantics,
        source: RecognitionSource = RecognitionSource.ZINNIA
    ) = RecognitionScoreNormalizer.normalize(raw, source, semantics)

    private fun raw(text: String, score: Float? = null) = RawRecognitionCandidate(text, score)

    private fun assertCosts(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedCost, actualCost) ->
            assertEquals(expectedCost, actualCost, 0.00001f)
        }
    }
}
