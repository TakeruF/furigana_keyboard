package com.example.furiganakeyboard

import com.example.furiganakeyboard.reading.KanjiUsagePriority
import com.example.furiganakeyboard.recognizer.KanjiCandidateRanker
import com.example.furiganakeyboard.recognizer.RawRecognitionCandidate
import com.example.furiganakeyboard.recognizer.RawScoreSemantics
import com.example.furiganakeyboard.recognizer.RecognitionCandidate
import com.example.furiganakeyboard.recognizer.RecognitionEvidence
import com.example.furiganakeyboard.recognizer.RecognitionScoreNormalizer
import com.example.furiganakeyboard.recognizer.RecognitionSource
import org.junit.Assert.assertEquals
import org.junit.Test

class KanjiCandidateRankerTest {
    @Test
    fun commonJoyoKanjiWinsWhenNativeScoresAreClose() {
        val ranked = KanjiCandidateRanker.rank(
            listOf(
                candidate("嶽", 10.0f),
                candidate("学", 9.9f),
                candidate("岳", 9.8f)
            ),
            mapOf(
                "嶽" to KanjiUsagePriority(0, 0),
                "学" to KanjiUsagePriority(1, 63),
                "岳" to KanjiUsagePriority(8, 1_314)
            )
        )
        assertEquals("学", ranked.first().text)
    }

    @Test
    fun commonUseBiasDoesNotOverrideAClearShapeScoreGap() {
        val ranked = KanjiCandidateRanker.rank(
            listOf(
                candidate("嶽", 10.0f),
                candidate("巖", 9.9f),
                candidate("学", 8.0f),
                candidate("岳", 7.9f)
            ),
            mapOf("学" to KanjiUsagePriority(1, 63))
        )
        assertEquals(listOf("嶽", "巖", "学", "岳"), ranked.map { it.text })
    }

    @Test
    fun rawTopIdentitySurvivesCommonUseReranking() {
        val normalized = RecognitionScoreNormalizer.normalize(
            listOf(
                RawRecognitionCandidate("嶽", 10f),
                RawRecognitionCandidate("学", 9.9f)
            ),
            RecognitionSource.ZINNIA,
            RawScoreSemantics.NATIVE_HIGHER_IS_BETTER
        )

        val ranked = KanjiCandidateRanker.rank(
            normalized,
            mapOf("学" to KanjiUsagePriority(1, 63))
        )

        assertEquals("学", ranked.first().text)
        assertEquals("嶽", ranked.single { it.isRecognizerRawTop }.text)
        assertEquals(10f, ranked.single { it.isRecognizerRawTop }.evidence.rawScore)
        assertEquals(0, ranked.single { it.isRecognizerRawTop }.evidence.rawRank)
    }

    @Test
    fun kanaSlotsAreNeverReorderedWithKanji() {
        val ranked = KanjiCandidateRanker.rank(
            listOf(candidate("あ", 10f), candidate("亜", 9.9f), candidate("安", 9.8f)),
            mapOf("亜" to KanjiUsagePriority(8, 1_509), "安" to KanjiUsagePriority(3, 144))
        )
        assertEquals("あ", ranked.first().text)
    }

    @Test
    fun kanaAndKanjiSlotsRemainFixedEvenWhenKanjiHasLowerCost() {
        val ranked = KanjiCandidateRanker.rank(
            listOf(
                normalizedCandidate("あ", 0.4f, 0),
                normalizedCandidate("亜", 0f, 1),
                normalizedCandidate("安", 0.05f, 2)
            ),
            mapOf("安" to KanjiUsagePriority(3, 144))
        )

        assertEquals("あ", ranked[0].text)
        assertEquals(setOf("亜", "安"), ranked.drop(1).map { it.text }.toSet())
    }

    private fun candidate(text: String, score: Float) = RecognitionCandidate(text, score)

    private fun normalizedCandidate(text: String, cost: Float, rank: Int) =
        RecognitionCandidate(
            text,
            cost,
            RecognitionEvidence(
                RecognitionSource.ZINNIA,
                10f - rank,
                rank
            )
        )
}
