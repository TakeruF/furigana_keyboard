package com.example.furiganakeyboard

import com.example.furiganakeyboard.conversion.ConversionConnection
import com.example.furiganakeyboard.conversion.ConversionCost
import com.example.furiganakeyboard.conversion.ConversionLexeme
import com.example.furiganakeyboard.conversion.ConversionText
import com.example.furiganakeyboard.conversion.KanaKanjiConverter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class ConversionParityFixtureTest {
    @Test
    fun sharedCostFixtureMatchesKotlinIntegerCalculation() {
        val cases = costCases()
        assertTrue("fixture must contain at least 50 reading.db rows", cases.count { it.kind == "actual" } >= 50)

        cases.forEach { case ->
            assertEquals(
                case.description,
                case.adjustedWordCost,
                ConversionCost.adjustedWordCost(
                    reading = case.reading,
                    surface = case.surface,
                    leftId = case.leftId,
                    rawWordCost = case.rawWordCost,
                    frequencyByHanLiteral = case.frequencies,
                ),
            )
        }
    }

    @Test
    fun sharedCostFixtureMatchesBundledReadingDatabase() {
        databaseConnection().use { database ->
            costCases().filter { it.kind == "actual" }.forEach { case ->
                val rawWordCost = database.prepareStatement(
                    """SELECT word_cost FROM conversion_lexeme
                       WHERE reading=? AND surface=? AND left_id=? AND right_id=?""".trimIndent()
                ).use { statement ->
                    statement.setString(1, case.reading)
                    statement.setString(2, case.surface)
                    statement.setInt(3, case.leftId)
                    statement.setInt(4, case.rightId)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "missing fixture row: ${case.description}" }
                        ConversionCost.clampInt32(rows.getLong(1))
                    }
                }
                val frequencies = frequencies(database, case.surface)
                assertEquals(case.description, case.rawWordCost, rawWordCost)
                assertEquals(
                    case.description,
                    case.adjustedWordCost,
                    ConversionCost.adjustedWordCost(
                        case.reading,
                        case.surface,
                        case.leftId,
                        rawWordCost,
                        frequencies,
                    ),
                )
            }
        }
    }

    @Test
    fun sharedNBestFixtureMatchesKotlinConverterAndBundledDatabase() {
        val fixture = JSONArray(fixtureFile("conversion-nbest.json").readText())
        databaseConnection().use { database ->
            val connections = loadConnections(database)
            for (index in 0 until fixture.length()) {
                val entry = fixture.getJSONObject(index)
                val reading = entry.getString("reading")
                val expected = entry.getJSONArray("results").let { results ->
                    (0 until results.length()).map { resultIndex ->
                        val result = results.getJSONObject(resultIndex)
                        RankedResult(result.getString("surface"), result.getInt("cost"))
                    }
                }
                val actual = KanaKanjiConverter.convert(
                    reading = reading,
                    lexemes = loadLexemes(database, reading),
                    connections = connections,
                    limit = 8,
                ).map { RankedResult(it.surface, it.cost) }

                assertEquals(reading, expected, actual)
            }
        }
    }

    @Test
    fun sharedSentenceRegressionFixtureReportsBundledDatabaseQuality() {
        val cases = sentenceCases()
        assertEquals(54, cases.size)
        assertEquals(
            mapOf(
                "助詞・助動詞" to 6,
                "同音異義語" to 6,
                "活用" to 6,
                "複文節" to 6,
                "固有名詞" to 6,
                "カタカナ外来語" to 6,
                "未知語" to 6,
                "数字" to 6,
                "文節境界" to 6,
            ),
            cases.groupingBy { it.category }.eachCount(),
        )

        databaseConnection().use { database ->
            val connections = loadConnections(database)
            val outcomes = cases.map { case ->
                val candidates = KanaKanjiConverter.convert(
                    reading = case.reading,
                    lexemes = loadLexemes(database, case.reading),
                    connections = connections,
                    limit = 8,
                ).map { it.surface }
                SentenceOutcome(case, candidates)
            }
            val report = SentenceRegressionReport(outcomes)
            println(report.render())

            // Known failures are deliberately still executed. A pass is an improvement;
            // only a previously passing expectation becoming a failure fails the build.
            assertTrue(report.regressionsMessage(), report.regressions.isEmpty())
        }
    }

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
                                            token,
                                            surface,
                                            leftId,
                                            ConversionCost.clampInt32(rows.getLong(4)),
                                            frequencies(database, surface),
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
                                rows.getInt(1),
                                rows.getInt(2),
                                ConversionCost.clampInt32(rows.getLong(3)),
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

    private fun costCases(): List<CostCase> =
        fixtureFile("conversion-cost.tsv").readLines().drop(1).filter(String::isNotBlank).map { line ->
            val fields = line.split('\t')
            require(fields.size == 8) { "invalid conversion cost fixture line: $line" }
            CostCase(
                kind = fields[0],
                reading = fields[1],
                surface = fields[2],
                leftId = fields[3].toInt(),
                rightId = fields[4].toInt(),
                rawWordCost = fields[5].toInt(),
                frequencies = parseFrequencies(fields[6]),
                adjustedWordCost = fields[7].toInt(),
            )
        }

    private fun sentenceCases(): List<SentenceCase> {
        val root = JSONObject(fixtureFile("sentence-conversion-regression.json").readText())
        require(root.getInt("schemaVersion") == 1) { "unsupported sentence fixture schema" }
        val knownFailureIds = root.stringList("baselineKnownFailureIds").toSet()
        return root.getJSONArray("cases").let { entries ->
            (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                val id = entry.getString("id")
                SentenceCase(
                    id = id,
                    category = entry.getString("category"),
                    reading = entry.getString("reading"),
                    expectedTop1 = entry.optString("expectedTop1").takeUnless { entry.isNull("expectedTop1") },
                    requiredNBest = entry.stringList("requiredNBest"),
                    forbiddenCandidates = entry.stringList("forbiddenCandidates"),
                    baseline = if (id in knownFailureIds) {
                        SentenceBaseline.KNOWN_FAILURE
                    } else {
                        SentenceBaseline.PASS
                    },
                )
            }
        }
    }

    private fun parseFrequencies(value: String): Map<String, Int> = buildMap {
        if (value == "-") return@buildMap
        value.split(',').forEach { item ->
            val (encodedScalar, encodedFrequency) = item.split('=', limit = 2)
            if (encodedFrequency != "?") {
                val codePoint = encodedScalar.removePrefix("U+").toInt(16)
                put(String(Character.toChars(codePoint)), encodedFrequency.toInt())
            }
        }
    }

    private fun fixtureFile(name: String): File = sequenceOf(
        File("fixtures", name),
        File("../fixtures", name),
    ).firstOrNull(File::isFile) ?: error("cannot locate shared fixture $name")

    private fun databaseConnection(): Connection {
        Class.forName("org.sqlite.JDBC")
        val database = sequenceOf(
            File("app/src/main/assets/reading.db"),
            File("src/main/assets/reading.db"),
        ).firstOrNull(File::isFile) ?: error("cannot locate reading.db")
        return DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}")
    }

    private data class CostCase(
        val kind: String,
        val reading: String,
        val surface: String,
        val leftId: Int,
        val rightId: Int,
        val rawWordCost: Int,
        val frequencies: Map<String, Int>,
        val adjustedWordCost: Int,
    ) {
        val description: String get() = "$reading/$surface/$leftId/$rightId"
    }

    private data class RankedResult(val surface: String, val cost: Int)

    private fun JSONObject.stringList(key: String): List<String> = getJSONArray(key).let { values ->
        (0 until values.length()).map(values::getString)
    }

    private enum class SentenceBaseline { PASS, KNOWN_FAILURE }

    private data class SentenceCase(
        val id: String,
        val category: String,
        val reading: String,
        val expectedTop1: String?,
        val requiredNBest: List<String>,
        val forbiddenCandidates: List<String>,
        val baseline: SentenceBaseline,
    )

    private data class SentenceOutcome(
        val case: SentenceCase,
        val candidates: List<String>,
    ) {
        private val top3: List<String> get() = candidates.take(3)
        val top1Matches: Boolean get() = case.expectedTop1 == null || candidates.firstOrNull() == case.expectedTop1
        val nBestMatches: Boolean get() = case.requiredNBest.all(candidates::contains)
        val top3Matches: Boolean get() = case.requiredNBest.all(top3::contains)
        val forbiddenCount: Int get() = candidates.count(case.forbiddenCandidates::contains)
        val passes: Boolean get() = top1Matches && nBestMatches && forbiddenCount == 0
        val isRegression: Boolean get() = case.baseline == SentenceBaseline.PASS && !passes
        val isImprovement: Boolean get() = case.baseline == SentenceBaseline.KNOWN_FAILURE && passes
    }

    private class SentenceRegressionReport(private val outcomes: List<SentenceOutcome>) {
        val regressions: List<SentenceOutcome> get() = outcomes.filter(SentenceOutcome::isRegression)

        fun regressionsMessage(): String = buildString {
            append(render())
            if (regressions.isNotEmpty()) append("\nRegression IDs: ")
                .append(regressions.joinToString { it.case.id })
        }

        fun render(): String = buildString {
            val top1Denominator = outcomes.count { it.case.expectedTop1 != null }
            val top1Correct = outcomes.count { it.case.expectedTop1 != null && it.top1Matches }
            val top3Correct = outcomes.count(SentenceOutcome::top3Matches)
            val forbidden = outcomes.sumOf(SentenceOutcome::forbiddenCount)
            val knownFailures = outcomes.count { it.case.baseline == SentenceBaseline.KNOWN_FAILURE }
            val unresolved = outcomes.count { it.case.baseline == SentenceBaseline.KNOWN_FAILURE && !it.passes }
            val improvements = outcomes.count(SentenceOutcome::isImprovement)
            appendLine("sentence-conversion-regression: cases=${outcomes.size}")
            appendLine("top-1 accuracy: $top1Correct/$top1Denominator (${percent(top1Correct, top1Denominator)})")
            appendLine("top-3 containment: $top3Correct/${outcomes.size} (${percent(top3Correct, outcomes.size)})")
            appendLine("forbidden candidate count: $forbidden")
            appendLine("known failures: unresolved=$unresolved/$knownFailures improvements=$improvements regressions=${regressions.size}")
            outcomes.filter { !it.passes }.forEach { outcome ->
                appendLine("  ${outcome.case.id} [${outcome.case.baseline.name.lowercase()}] reading=${outcome.case.reading} expectedTop1=${outcome.case.expectedTop1 ?: "-"} required=${outcome.case.requiredNBest} actual=${outcome.candidates}")
            }
        }

        private fun percent(numerator: Int, denominator: Int): String =
            if (denominator == 0) "n/a" else "%.1f%%".format(java.util.Locale.ROOT, numerator * 100.0 / denominator)
    }
}
