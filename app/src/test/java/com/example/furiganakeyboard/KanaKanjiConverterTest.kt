package com.example.furiganakeyboard

import com.example.furiganakeyboard.conversion.ConversionConnection
import com.example.furiganakeyboard.conversion.ConversionLexeme
import com.example.furiganakeyboard.conversion.KanaKanjiConverter
import com.example.furiganakeyboard.conversion.PosClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KanaKanjiConverterTest {
    @Test
    fun koreMoHonDaIsFirstCandidate() {
        assertFirst(
            "これもほんだ",
            words("これもほんだ", "これ" to "これ", "も" to "も", "ほん" to "本", "だ" to "だ"),
            "これも本だ",
        )
    }

    @Test
    fun koreWaHonDaConverts() {
        assertFirst(
            "これはほんだ",
            words("これはほんだ", "これ" to "これ", "は" to "は", "ほん" to "本", "だ" to "だ"),
            "これは本だ",
        )
    }

    @Test
    fun kyouWaHareDaConverts() {
        assertFirst(
            "きょうははれだ",
            words("きょうははれだ", "きょう" to "今日", "は" to "は", "はれ" to "晴れ", "だ" to "だ"),
            "今日は晴れだ",
        )
    }

    @Test
    fun watashiMoIkuConverts() {
        assertFirst(
            "わたしもいく",
            words("わたしもいく", "わたし" to "私", "も" to "も", "いく" to "行く"),
            "私も行く",
        )
    }

    @Test
    fun honWoYomuConverts() {
        assertFirst(
            "ほんをよむ",
            words("ほんをよむ", "ほん" to "本", "を" to "を", "よむ" to "読む"),
            "本を読む",
        )
    }

    @Test
    fun knownPartStillConvertsAroundUnknownText() {
        val input = "なぞほん"
        val lexemes = listOf(lexeme(input, "ほん", "本", PosClass.NOUN))

        val results = KanaKanjiConverter.convert(input, lexemes, zeroConnections(), 8)

        assertEquals("なぞ本", results.first().surface)
        assertTrue(results.first().segments.take(2).all { it.isCopy })
        assertFalse(results.first().segments.last().isCopy)
    }

    @Test
    fun noVocabularyReturnsEmpty() {
        assertTrue(KanaKanjiConverter.convert("みち", emptyList(), zeroConnections(), 8).isEmpty())
    }

    @Test
    fun allCopyPathIsNeverReturned() {
        val invalid = ConversionLexeme(1, 2, "み", "見", 3, 3, 0)

        val results = KanaKanjiConverter.convert("みち", listOf(invalid), zeroConnections(), 8)

        assertTrue(results.none { result -> result.segments.all { it.isCopy } })
    }

    @Test
    fun connectionCostDemotesUnnaturalHomophone() {
        val input = "これもはし"
        val base = words(input, "これ" to "これ", "も" to "も")
        val bridge = lexeme(input, "はし", "橋", PosClass.NOUN, wordCost = 100)
        val chopsticks = lexeme(input, "はし", "箸", PosClass.PROPER_NOUN, wordCost = 0)
        val connections = zeroConnections().filterNot {
            it.rightId == PosClass.PARTICLE.id &&
                (it.leftId == PosClass.NOUN.id || it.leftId == PosClass.PROPER_NOUN.id)
        } + listOf(
            ConversionConnection(PosClass.PARTICLE.id, PosClass.NOUN.id, 0),
            ConversionConnection(PosClass.PARTICLE.id, PosClass.PROPER_NOUN.id, 1_000),
        )

        val results = KanaKanjiConverter.convert(input, base + chopsticks + bridge, connections, 8)

        assertEquals("これも橋", results.first().surface)
        assertTrue(results.indexOfFirst { it.surface == "これも箸" } > 0)
    }

    @Test
    fun duplicateSurfacesAreRemoved() {
        val input = "ほん"
        val lexemes = listOf(
            lexeme(input, input, "本", PosClass.NOUN, 200),
            lexeme(input, input, "本", PosClass.PROPER_NOUN, 100),
            lexeme(input, input, "奔", PosClass.NOUN, 300),
        )

        val results = KanaKanjiConverter.convert(input, lexemes, zeroConnections(), 8)

        assertEquals(results.map { it.surface }.distinct(), results.map { it.surface })
        assertEquals(1, results.count { it.surface == "本" })
    }

    @Test
    fun segmentationAnalysisRetainsDuplicateSurfacesWithDifferentPaths() {
        val input = "あいう"
        val lexemes = listOf(
            lexeme(input, "あい", "甲", PosClass.PARTICLE, 100),
            lexeme(input, "う", "乙", PosClass.NOUN, 100),
            lexeme(input, "あ", "甲", PosClass.PARTICLE, 100),
            lexeme(input, "いう", "乙", PosClass.NOUN, 100),
        )

        val results = KanaKanjiConverter.convert(
            input,
            lexemes,
            zeroConnections(),
            limit = 8,
            preserveSegmentations = true,
        )

        val alternatives = results.filter { it.surface == "甲乙" }
        assertEquals(2, alternatives.size)
        assertEquals(listOf(1, 2), alternatives.map { it.segments.first().end }.sorted())
    }

    @Test
    fun limitIsStrictAndCappedAtEight() {
        val input = "こう"
        val lexemes = (0 until 10).map { index ->
            lexeme(input, input, "候$index", PosClass.NOUN, index)
        }

        assertEquals(3, KanaKanjiConverter.convert(input, lexemes, zeroConnections(), 3).size)
        assertEquals(8, KanaKanjiConverter.convert(input, lexemes, zeroConnections(), 99).size)
        assertTrue(KanaKanjiConverter.convert(input, lexemes, zeroConnections(), 0).isEmpty())
    }

    @Test
    fun resultDoesNotDependOnLexemeOrConnectionInputOrder() {
        val input = "ほんをよむ"
        val lexemes = words(input, "ほん" to "本", "を" to "を", "よむ" to "読む") +
            lexeme(input, "ほん", "奔", PosClass.NOUN, 800)
        val connections = zeroConnections()

        val forward = KanaKanjiConverter.convert(input, lexemes, connections, 8)
        val reversed = KanaKanjiConverter.convert(input, lexemes.reversed(), connections.reversed(), 8)

        assertEquals(forward, reversed)
    }

    @Test
    fun supplementaryCharacterIsCopiedWithoutSplittingSurrogatePair() {
        val input = "\uD842\uDFB7ほん"
        val result = KanaKanjiConverter.convert(
            input,
            listOf(lexeme(input, "ほん", "本", PosClass.NOUN)),
            zeroConnections(),
            8,
        ).first()

        assertEquals("\uD842\uDFB7本", result.surface)
        assertEquals("\uD842\uDFB7", result.segments.first().surface)
        assertEquals(0, result.segments.first().start)
        assertEquals(2, result.segments.first().end)
    }

    @Test
    fun fortyNineCodePointsReturnsEmpty() {
        val input = "あ".repeat(49)
        val lexeme = ConversionLexeme(0, 1, "あ", "亜", 3, 3, 0)

        assertTrue(KanaKanjiConverter.convert(input, listOf(lexeme), zeroConnections(), 8).isEmpty())
    }

    @Test
    fun cancellationReturnsEmptyPromptly() {
        val input = "あ".repeat(48)
        val lexemes = (0 until input.length).map { start ->
            ConversionLexeme(start, start + 1, "あ", "亜", 3, 3, 0)
        }
        var checks = 0

        val result = KanaKanjiConverter.convert(input, lexemes, zeroConnections(), 8) {
            ++checks >= 5
        }

        assertTrue(result.isEmpty())
        assertTrue(checks < 20)
    }

    private fun assertFirst(input: String, lexemes: List<ConversionLexeme>, expected: String) {
        val results = KanaKanjiConverter.convert(input, lexemes, zeroConnections(), 8)
        assertFalse(results.isEmpty())
        assertEquals(expected, results.first().surface)
    }

    private fun words(input: String, vararg pairs: Pair<String, String>): List<ConversionLexeme> {
        var searchFrom = 0
        return pairs.map { (reading, surface) ->
            val start = input.indexOf(reading, searchFrom)
            require(start >= 0)
            searchFrom = start + reading.length
            val pos = when (reading) {
                "これ", "わたし" -> PosClass.PRONOUN
                "は", "も", "を" -> PosClass.PARTICLE
                "だ" -> PosClass.AUXILIARY
                "いく", "よむ" -> PosClass.VERB
                else -> PosClass.NOUN
            }
            ConversionLexeme(start, searchFrom, reading, surface, pos.id, pos.id, 100)
        }
    }

    private fun lexeme(
        input: String,
        reading: String,
        surface: String,
        pos: PosClass,
        wordCost: Int = 100,
    ): ConversionLexeme {
        val start = input.indexOf(reading)
        require(start >= 0)
        return ConversionLexeme(start, start + reading.length, reading, surface, pos.id, pos.id, wordCost)
    }

    private fun zeroConnections(): List<ConversionConnection> =
        (PosClass.BOS.id..PosClass.COPY.id).flatMap { rightId ->
            (PosClass.BOS.id..PosClass.COPY.id).map { leftId ->
                ConversionConnection(rightId, leftId, 0)
            }
        }
}
