package com.example.furiganakeyboard

import com.example.furiganakeyboard.ime.WholeCompositionCandidateKind
import com.example.furiganakeyboard.ime.WholeCompositionCandidatePolicy
import com.example.furiganakeyboard.reading.WordReadingCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WholeCompositionCandidatePolicyTest {
    @Test
    fun wholeConversionsKeepConverterOrderAheadOfPredictionsAndFallbacks() {
        val candidates = WholeCompositionCandidatePolicy.build(
            reading = "いえに",
            dictionaryCandidates = listOf(
                candidate("家に", "いえに"),
                candidate("家煮", "いえに"),
                candidate("家に帰る", "いえにかえる"),
            ),
            scriptFallbacks = listOf(
                candidate("いえに", "いえに"),
                candidate("イエニ", "いえに"),
            ),
            limit = 8,
        )

        assertEquals(
            listOf("家に", "家煮", "家に帰る", "いえに", "イエニ"),
            candidates.map { it.surface },
        )
        assertEquals(
            listOf(
                WholeCompositionCandidateKind.WHOLE_CONVERSION,
                WholeCompositionCandidateKind.WHOLE_CONVERSION,
                WholeCompositionCandidateKind.PREDICTION,
                WholeCompositionCandidateKind.SCRIPT_FALLBACK,
                WholeCompositionCandidateKind.SCRIPT_FALLBACK,
            ),
            candidates.map { it.kind },
        )
    }

    @Test
    fun prefixOnlyDictionaryEntriesCannotOccupyTerminalCandidates() {
        val ieni = WholeCompositionCandidatePolicy.build(
            reading = "いえに",
            dictionaryCandidates = listOf(
                candidate("家", "いえ"),
                candidate("家に", "いえに"),
            ),
            scriptFallbacks = emptyList(),
            limit = 8,
        )
        val ibaraki = WholeCompositionCandidatePolicy.build(
            reading = "いばらき",
            dictionaryCandidates = listOf(
                candidate("荊", "いばら"),
                candidate("茨城", "いばらき"),
            ),
            scriptFallbacks = emptyList(),
            limit = 8,
        )

        assertEquals(listOf("家に"), ieni.map { it.surface })
        assertFalse(ieni.any { it.surface == "家" })
        assertEquals(listOf("茨城"), ibaraki.map { it.surface })
        assertFalse(ibaraki.any { it.surface == "荊" })
    }

    @Test
    fun exactReadingOutranksPredictionEvenWhenSuggestionInputIsInterleaved() {
        val candidates = WholeCompositionCandidatePolicy.build(
            reading = "いばらき",
            dictionaryCandidates = listOf(
                candidate("茨城県", "いばらきけん"),
                candidate("茨城", "いばらき"),
                candidate("茨木", "いばらき"),
            ),
            scriptFallbacks = emptyList(),
            limit = 8,
        )

        assertEquals(listOf("茨城", "茨木", "茨城県"), candidates.map { it.surface })
        assertEquals(
            listOf(
                WholeCompositionCandidateKind.WHOLE_CONVERSION,
                WholeCompositionCandidateKind.WHOLE_CONVERSION,
                WholeCompositionCandidateKind.PREDICTION,
            ),
            candidates.map { it.kind },
        )
    }

    @Test
    fun unrelatedReadingsAreDroppedAndHigherPriorityKindsWinDeduplication() {
        val candidates = WholeCompositionCandidatePolicy.build(
            reading = "かな",
            dictionaryCandidates = listOf(
                WordReadingCandidate("仮名", listOf("かめい", "かな")),
                candidate("かな", "かなしい"),
                candidate("無関係", "むかんけい"),
            ),
            scriptFallbacks = listOf(candidate("かな", "かな")),
            limit = 8,
        )

        assertEquals(listOf("仮名", "かな"), candidates.map { it.surface })
        assertEquals(
            listOf(
                WholeCompositionCandidateKind.WHOLE_CONVERSION,
                WholeCompositionCandidateKind.PREDICTION,
            ),
            candidates.map { it.kind },
        )
    }

    @Test
    fun limitIsAppliedAfterWholePredictionFallbackPrecedence() {
        val candidates = WholeCompositionCandidatePolicy.build(
            reading = "かな",
            dictionaryCandidates = listOf(
                candidate("かな予測", "かなよそく"),
                candidate("仮名", "かな"),
                candidate("カナ変換", "かな"),
            ),
            scriptFallbacks = listOf(candidate("かな", "かな")),
            limit = 2,
        )

        assertEquals(listOf("仮名", "カナ変換"), candidates.map { it.surface })
    }

    @Test
    fun emptyReadingOrNonPositiveLimitProducesNoCandidates() {
        val dictionary = listOf(candidate("仮名", "かな"))

        assertEquals(
            emptyList<Any>(),
            WholeCompositionCandidatePolicy.build("", dictionary, emptyList(), 8),
        )
        assertEquals(
            emptyList<Any>(),
            WholeCompositionCandidatePolicy.build("かな", dictionary, emptyList(), 0),
        )
    }

    private fun candidate(surface: String, reading: String) =
        WordReadingCandidate(surface, listOf(reading))
}
