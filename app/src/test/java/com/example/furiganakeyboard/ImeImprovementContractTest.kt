package com.example.furiganakeyboard

import com.example.furiganakeyboard.conversion.ConversionConnection
import com.example.furiganakeyboard.conversion.ConversionCost
import com.example.furiganakeyboard.conversion.ConversionLexeme
import com.example.furiganakeyboard.conversion.ConversionSegment
import com.example.furiganakeyboard.conversion.ConversionText
import com.example.furiganakeyboard.conversion.KanaKanjiConverter
import com.example.furiganakeyboard.conversion.PosClass
import com.example.furiganakeyboard.ime.BunsetsuCandidateOption
import com.example.furiganakeyboard.ime.BunsetsuComposition
import com.example.furiganakeyboard.ime.BunsetsuConversionPlan
import com.example.furiganakeyboard.ime.CompositionBuffer
import com.example.furiganakeyboard.ime.RomajiConversionState
import com.example.furiganakeyboard.ime.RomajiKanaConverter
import com.example.furiganakeyboard.reading.KanjiUsagePriority
import com.example.furiganakeyboard.recognizer.KanjiCandidateRanker
import com.example.furiganakeyboard.recognizer.RecognitionCandidate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.text.Normalizer

/** Acceptance contracts for the IME improvement work, without changing production code. */
class ImeImprovementContractTest {
    @Test
    fun fixtureCoversEveryRequestedContractAndLabelsTheCurrentBaseline() {
        val cases = fixtureCases()
        val casesById = cases.associateBy { it.id }
        val requiredIds = setOf(
            "conversion-ibaraki",
            "conversion-ieni",
            "conversion-sumomo-boundary",
            "cursor-start",
            "cursor-middle",
            "cursor-end",
            "romaji-unresolved-n",
            "romaji-consonant-only",
            "romaji-delete",
            "candidate-cancel",
            "space-tap",
            "space-hold",
            "space-drag",
            "space-direction-reversal",
            "space-cancel",
            "space-multi-touch",
            "handwriting-hon-ki",
            "handwriting-mi-sue",
            "handwriting-tsuchi-shi",
            "handwriting-kuchi-hi",
            "candidate-proper-place-rare-retention",
            "unicode-supplementary",
            "unicode-combining-grapheme",
            "unicode-precomposed-dakuten",
            "unicode-canonical-equivalence",
        )

        assertEquals(requiredIds, casesById.keys)
        assertEquals("fixture IDs must be unique", cases.size, casesById.size)
        assertEquals(Baseline.PASS, casesById.getValue("conversion-ibaraki").baseline)
        assertEquals(Baseline.PASS, casesById.getValue("conversion-ieni").baseline)
        assertEquals(Baseline.KNOWN_FAILURE, casesById.getValue("cursor-middle").baseline)
        assertEquals(Baseline.KNOWN_FAILURE, casesById.getValue("space-cancel").baseline)
        assertEquals(Baseline.KNOWN_FAILURE, casesById.getValue("space-multi-touch").baseline)
        assertEquals(Baseline.KNOWN_FAILURE, casesById.getValue("unicode-combining-grapheme").baseline)
    }

    @Test
    fun bundledDictionaryRanksTargetPhrasesWithoutStandalonePrefixCandidates() {
        databaseConnection().use { database ->
            val connections = loadConnections(database)
            val ibaraki = convert(database, connections, "いばらき")
            val ieni = convert(database, connections, "いえに")

            assertEquals("茨城", ibaraki.firstOrNull())
            assertFalse("standalone 荊 must not be a normal candidate: $ibaraki", "荊" in ibaraki)
            assertEquals("家に", ieni.firstOrNull())
            assertTrue("家煮 must not be top-1: $ieni", ieni.firstOrNull() != "家煮")
            assertFalse("standalone 家 must not be a normal candidate: $ieni", "家" in ieni)
        }
    }

    @Test
    fun partialConversionCommitsOnlyTheLeftSideAndPreservesTheRightSide() {
        val reading = "すもももも"
        val left = segment(0, 3, "すもも", "李")
        val right = segment(3, 5, "もも", "桃")
        val path = listOf(left, right)
        val plan = BunsetsuConversionPlan(
            initialSegments = path,
            candidates = listOf(
                BunsetsuCandidateOption(
                    surface = "李",
                    reading = "すもも",
                    rightId = PosClass.NOUN.id,
                    segments = path,
                )
            ),
            activeLength = 3,
        )
        val state = BunsetsuComposition.create(reading, plan)

        assertEquals("すもも", state.activeReading)
        val result = state.commitActive("李", PosClass.NOUN.id)

        assertEquals("李", result.committedText)
        assertEquals("もも", result.remainingText)
        assertEquals("もも", state.activeReading)
        assertEquals("もも", state.originalReading.substring(3))
    }

    @Test
    fun unresolvedRomajiDeletionAndCandidateCancellationHaveStableStateTransitions() {
        val unresolvedN = RomajiKanaConverter.convert("n")
        val consonant = RomajiKanaConverter.convert("k")

        assertEquals("ん", unresolvedN.displayText)
        assertTrue(unresolvedN.hasUnresolvedInput)
        assertEquals("k", consonant.displayText)
        assertTrue(consonant.hasUnresolvedInput)
        assertEquals("ki", RomajiKanaConverter.deleteLastUnit("kyu"))
        assertEquals("き", RomajiKanaConverter.convert("ki").displayText)

        val selection = RomajiConversionState().apply {
            onCompositionEdited(hasComposition = true)
        }
        assertEquals(
            RomajiConversionState.KeyAction.SelectCandidate(0),
            selection.onSpace(candidateCount = 3),
        )
        selection.clear()
        assertEquals(RomajiConversionState.Phase.EMPTY, selection.phase)
        assertEquals(null, selection.selectedCandidateIndex(3))
    }

    @Test
    fun handwritingRankingRetainsSimilarProperPlaceAndRareCandidates() {
        val similarPairs = listOf(
            listOf("本", "木"),
            listOf("未", "末"),
            listOf("土", "士"),
            listOf("口", "日"),
        )
        similarPairs.forEach { pair ->
            val input = pair.mapIndexed { index, text ->
                RecognitionCandidate(text, 10f - index * 0.01f)
            }
            val output = KanjiCandidateRanker.rank(input, emptyMap())
            assertEquals(pair.toSet(), output.map { it.text }.toSet())
            assertEquals(pair.size, output.size)
        }

        val retained = listOf(
            RecognitionCandidate("髙橋", 10f),
            RecognitionCandidate("茨城", 9.99f),
            RecognitionCandidate("𠮟", 9.98f),
            RecognitionCandidate("邊", 9.97f),
        )
        val priorities = mapOf("茨城" to KanjiUsagePriority(1, 100))
        val ranked = KanjiCandidateRanker.rank(retained, priorities)

        assertEquals(retained.map { it.text }.toSet(), ranked.map { it.text }.toSet())
        assertEquals(retained.size, ranked.size)
    }

    @Test
    fun unicodeBaselineSeparatesPassingScalarSafetyFromKnownGraphemeGaps() {
        val supplementary = CompositionBuffer().apply { append("日𠮟") }
        assertEquals("日", supplementary.deleteLastCodePoint())

        val precomposed = CompositionBuffer().apply { append("が") }
        assertEquals("", precomposed.deleteLastCodePoint())

        assertBaseline(
            id = "unicode-combining-grapheme",
            passes = CompositionBuffer().apply { append("か\u3099") }
                .deleteLastCodePoint().isEmpty(),
        )

        val katakanaNfc = RomajiKanaConverter.toKatakana("が")
        val katakanaNfd = RomajiKanaConverter.toKatakana("か\u3099")
        assertBaseline("unicode-canonical-equivalence", katakanaNfc == katakanaNfd)
        assertEquals(
            Normalizer.normalize(katakanaNfc, Normalizer.Form.NFC),
            Normalizer.normalize(katakanaNfd, Normalizer.Form.NFC),
        )
    }

    private fun convert(
        database: Connection,
        connections: List<ConversionConnection>,
        reading: String,
    ): List<String> = KanaKanjiConverter.convert(
        reading = reading,
        lexemes = loadLexemes(database, reading),
        connections = connections,
        limit = 8,
    ).map { it.surface }

    private fun loadLexemes(database: Connection, reading: String): List<ConversionLexeme> =
        buildList {
            val scalarCount = ConversionText.scalarCount(reading)
            for (start in 0 until scalarCount) {
                for (end in start + 1..minOf(scalarCount, start + 16)) {
                    val token = ConversionText.scalarSubstring(reading, start, end)
                    database.prepareStatement(
                        """SELECT surface, left_id, right_id, word_cost
                           FROM conversion_lexeme WHERE reading=?
                           ORDER BY word_cost, form_rank, surface, left_id, right_id
                           LIMIT 12""".trimIndent()
                    ).use { statement ->
                        statement.setString(1, token)
                        statement.executeQuery().use { rows ->
                            while (rows.next()) {
                                val surface = rows.getString(1)
                                val leftId = rows.getInt(2)
                                add(
                                    ConversionLexeme(
                                        start = start,
                                        end = end,
                                        reading = token,
                                        surface = surface,
                                        leftId = leftId,
                                        rightId = rows.getInt(3),
                                        wordCost = ConversionCost.adjustedWordCost(
                                            reading = token,
                                            surface = surface,
                                            leftId = leftId,
                                            rawWordCost = ConversionCost.clampInt32(rows.getLong(4)),
                                            frequencyByHanLiteral = frequencies(database, surface),
                                        ),
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

    private fun loadConnections(database: Connection): List<ConversionConnection> =
        database.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT previous_right_id, next_left_id, cost FROM connection_cost"
            ).use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            ConversionConnection(
                                rightId = rows.getInt(1),
                                leftId = rows.getInt(2),
                                cost = ConversionCost.clampInt32(rows.getLong(3)),
                            )
                        )
                    }
                }
            }
        }

    private fun frequencies(database: Connection, surface: String): Map<String, Int> = buildMap {
        ConversionCost.hanLiterals(surface).distinct().forEach { literal ->
            database.prepareStatement(
                "SELECT frequency FROM kanji_priority WHERE literal=?"
            ).use { statement ->
                statement.setString(1, literal)
                statement.executeQuery().use { rows ->
                    if (rows.next()) put(literal, rows.getInt(1))
                }
            }
        }
    }

    private fun segment(start: Int, end: Int, reading: String, surface: String) = ConversionSegment(
        start = start,
        end = end,
        reading = reading,
        surface = surface,
        leftId = PosClass.NOUN.id,
        rightId = PosClass.NOUN.id,
        wordCost = 0,
        isCopy = false,
    )

    private fun assertBaseline(id: String, passes: Boolean) {
        when (fixtureCases().single { it.id == id }.baseline) {
            Baseline.PASS -> assertTrue("baseline PASS regressed: $id", passes)
            Baseline.KNOWN_FAILURE -> if (passes) println("IME contract improvement: $id")
        }
    }

    private fun fixtureCases(): List<FixtureCase> {
        val root = JSONObject(fixtureFile().readText())
        assertEquals(1, root.getInt("schemaVersion"))
        val values = root.getJSONArray("cases")
        return (0 until values.length()).map { index ->
            val entry = values.getJSONObject(index)
            val id = entry.getString("id")
            FixtureCase(id, Baseline.valueOf(entry.getString("baseline")))
        }
    }

    private fun fixtureFile(): File = sequenceOf(
        File("fixtures/ime-improvement-contracts.json"),
        File("../fixtures/ime-improvement-contracts.json"),
    ).firstOrNull(File::isFile) ?: error("cannot locate IME improvement fixture")

    private fun databaseConnection(): Connection {
        Class.forName("org.sqlite.JDBC")
        val database = sequenceOf(
            File("app/src/main/assets/reading.db"),
            File("src/main/assets/reading.db"),
        ).firstOrNull(File::isFile) ?: error("cannot locate reading.db")
        return DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}")
    }

    private enum class Baseline { PASS, KNOWN_FAILURE }

    private data class FixtureCase(val id: String, val baseline: Baseline)
}
