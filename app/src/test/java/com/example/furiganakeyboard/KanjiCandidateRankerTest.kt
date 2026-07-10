package com.example.furiganakeyboard

import com.example.furiganakeyboard.reading.KanjiUsagePriority
import com.example.furiganakeyboard.recognizer.KanjiCandidateRanker
import com.example.furiganakeyboard.recognizer.RecognitionCandidate
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
    fun kanaSlotsAreNeverReorderedWithKanji() {
        val ranked = KanjiCandidateRanker.rank(
            listOf(candidate("あ", 10f), candidate("亜", 9.9f), candidate("安", 9.8f)),
            mapOf("亜" to KanjiUsagePriority(8, 1_509), "安" to KanjiUsagePriority(3, 144))
        )
        assertEquals("あ", ranked.first().text)
    }

    private fun candidate(text: String, score: Float) = RecognitionCandidate(text, score)
}
