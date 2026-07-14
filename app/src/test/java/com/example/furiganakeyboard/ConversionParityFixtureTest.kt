package com.example.furiganakeyboard

import com.example.furiganakeyboard.conversion.ConversionConnection
import com.example.furiganakeyboard.conversion.ConversionCost
import com.example.furiganakeyboard.conversion.ConversionContextModel
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
import java.security.MessageDigest

class ConversionParityFixtureTest {
    @Test
    fun sharedContextModelMetadataAndTargetRankingsAreStable() {
        val fixture = JSONObject(fixtureFile("context-conversion-nbest.json").readText())
        assertEquals(1, fixture.getInt("schemaVersion"))
        val expectedModel = fixture.getJSONObject("model")
        val modelBytes = contextModelFile().readBytes()
        val model = ConversionContextModel.decode(modelBytes)
        assertEquals(expectedModel.getInt("formatVersion"), model.metadata.formatVersion)
        assertEquals(expectedModel.getInt("modelVersion"), model.metadata.modelVersion)
        assertEquals(expectedModel.getInt("unigramCount"), model.metadata.unigramCount)
        assertEquals(expectedModel.getInt("bigramCount"), model.metadata.bigramCount)
        assertEquals(expectedModel.getString("sourceSha256"), model.metadata.sourceSha256)
        assertEquals(expectedModel.getInt("modelBytes"), modelBytes.size)
        assertEquals(expectedModel.getString("modelSha256"), sha256(modelBytes))
        assertTrue(model.hasBigram("学校", "行きます"))
        assertTrue(model.hasBigram("写真", "撮って"))
        assertTrue(model.hasBigram("変換", "精度"))
        assertTrue(model.hasBigram("お茶", "飲み"))
        assertEquals(0, model.cost("未登録", "未知語"))

        val targets = fixture.getJSONArray("cases").let { values ->
            (0 until values.length()).map { index ->
                val entry = values.getJSONObject(index)
                val expected = entry.getJSONArray("results").let { results ->
                    (0 until results.length()).map { resultIndex ->
                        val result = results.getJSONObject(resultIndex)
                        RankedResult(result.getString("surface"), result.getInt("cost"))
                    }
                }
                ContextCase(entry.getString("reading"), expected)
            }
        }
        databaseConnection().use { database ->
            val connections = loadConnections(database)
            targets.forEach { target ->
                val reading = target.reading
                val lexemes = loadLexemes(database, reading)
                val oneCandidate = KanaKanjiConverter.convert(
                    reading = reading,
                    lexemes = lexemes,
                    connections = connections,
                    limit = 1,
                    contextModel = model,
                    beamWidth = 12,
                )
                val production = KanaKanjiConverter.convert(
                    reading = reading,
                    lexemes = lexemes,
                    connections = connections,
                    limit = 8,
                    contextModel = model,
                    beamWidth = 12,
                )
                val wideReference = KanaKanjiConverter.convert(
                    reading = reading,
                    lexemes = lexemes,
                    connections = connections,
                    limit = 8,
                    contextModel = model,
                    beamWidth = 64,
                )
                val actual = production.map { RankedResult(it.surface, it.cost) }
                assertEquals("shared context parity fixture: $reading", target.results, actual)
                assertEquals(
                    "candidate limit: $reading",
                    target.results.first().surface,
                    oneCandidate.firstOrNull()?.surface,
                )
                assertEquals(
                    "production beam: $reading",
                    target.results.first().surface,
                    production.firstOrNull()?.surface,
                )
                assertEquals(
                    "wide beam: $reading",
                    target.results.first().surface,
                    wideReference.firstOrNull()?.surface,
                )
                assertEquals(
                    "production/wide N-best: $reading",
                    wideReference.map { RankedResult(it.surface, it.cost) },
                    production.map { RankedResult(it.surface, it.cost) },
                )
            }

            val suffixReading = "いきます"
            val contextualSuffix = KanaKanjiConverter.convert(
                reading = suffixReading,
                lexemes = loadLexemes(database, suffixReading),
                connections = connections,
                limit = 8,
                initialRightId = 5,
                initialContextSurface = "学校",
                contextModel = model,
            )
            assertEquals("confirmed bunsetsu context", "行きます", contextualSuffix.first().surface)
        }
    }

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
        assertEquals(57, cases.size)
        assertEquals(
            mapOf(
                "助詞・助動詞" to 6,
                "同音異義語" to 6,
                "活用" to 6,
                "複文節" to 6,
                "固有名詞" to 6,
                "カタカナ外来語" to 9,
                "未知語" to 6,
                "数字" to 6,
                "文節境界" to 6,
            ),
            cases.groupingBy { it.category }.eachCount(),
        )

        databaseConnection().use { database ->
            val connections = loadConnections(database)
            val model = contextModel()
            val ranked = cases.map { case ->
                val lexemes = loadLexemes(database, case.reading)
                val baseline = KanaKanjiConverter.convert(
                    reading = case.reading,
                    lexemes = lexemes,
                    connections = connections,
                    limit = 8,
                ).map { it.surface }
                val contextual = KanaKanjiConverter.convert(
                    reading = case.reading,
                    lexemes = lexemes,
                    connections = connections,
                    limit = 8,
                    contextModel = model,
                ).map { it.surface }
                case to (baseline to contextual)
            }
            val baselineReport = SentenceRegressionReport(
                ranked.map { (case, values) -> SentenceOutcome(case, values.first) }
            )
            val report = SentenceRegressionReport(
                ranked.map { (case, values) -> SentenceOutcome(case, values.second) }
            )
            val parityPayload = buildString {
                ranked.forEach { (case, values) ->
                    append(case.id).append('\u0000')
                    values.second.forEach { append(it).append('\u0000') }
                    append('\u0001')
                }
            }.toByteArray(Charsets.UTF_8)
            println("context-nbest-sha256=${sha256(parityPayload)}")
            println("baseline\n${baselineReport.render()}context\n${report.render()}")

            assertTrue(
                "context top-1 must improve over baseline\n${report.render()}",
                report.top1Correct > baselineReport.top1Correct,
            )
            baselineReport.top1ByCategory.forEach { (category, baselineTop1) ->
                assertTrue(
                    "category[$category] top-1 regressed: baseline=$baselineTop1 " +
                        "context=${report.top1ByCategory.getValue(category)}",
                    report.top1ByCategory.getValue(category) >= baselineTop1,
                )
            }
            baselineReport.top3ByCategory.forEach { (category, baselineTop3) ->
                assertTrue(
                    "category[$category] top-3 regressed: baseline=$baselineTop3 " +
                        "context=${report.top3ByCategory.getValue(category)}",
                    report.top3ByCategory.getValue(category) >= baselineTop3,
                )
            }
            baselineReport.nBestByCategory.forEach { (category, baselineNBest) ->
                assertTrue(
                    "category[$category] N-best regressed: baseline=$baselineNBest " +
                        "context=${report.nBestByCategory.getValue(category)}",
                    report.nBestByCategory.getValue(category) >= baselineNBest,
                )
            }
            assertTrue(
                "forbidden candidates increased: baseline=${baselineReport.forbiddenCount} " +
                    "context=${report.forbiddenCount}",
                report.forbiddenCount <= baselineReport.forbiddenCount,
            )
            // Known failures stay exercised; a newly failing baseline-pass case fails the build.
            assertTrue(report.regressionsMessage(), report.regressions.isEmpty())
        }
    }

    private fun contextModel(): ConversionContextModel = ConversionContextModel.decode(
        contextModelFile().readBytes()
    )

    private fun contextModelFile(): File = sequenceOf(
        File("app/src/main/assets/context-model.bin"),
        File("src/main/assets/context-model.bin"),
    ).firstOrNull(File::isFile) ?: error("cannot locate context-model.bin")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

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
                        val katakanaReading = ConversionText.toKatakana(token)
                        val stored = buildList {
                            listOf(token, katakanaReading).forEach { databaseReading ->
                                statement.setString(1, databaseReading)
                                statement.executeQuery().use { rows ->
                                    while (rows.next()) {
                                        val surface = rows.getString(1)
                                        if (databaseReading == katakanaReading &&
                                            databaseReading != token &&
                                            !ConversionText.isKatakanaTransliteration(token, surface)
                                        ) continue
                                        add(
                                            StoredLexeme(
                                                surface = surface,
                                                leftId = rows.getInt(2),
                                                rightId = rows.getInt(3),
                                                wordCost = ConversionCost.clampInt32(rows.getLong(4)),
                                            )
                                        )
                                    }
                                }
                            }
                        }.distinctBy { listOf(it.surface, it.leftId, it.rightId) }
                        stored.forEach { lexeme ->
                            add(
                                ConversionLexeme(
                                    start = start,
                                    end = end,
                                    reading = token,
                                    surface = lexeme.surface,
                                    leftId = lexeme.leftId,
                                    rightId = lexeme.rightId,
                                    wordCost = ConversionCost.adjustedWordCost(
                                        token,
                                        lexeme.surface,
                                        lexeme.leftId,
                                        lexeme.wordCost,
                                        frequencies(database, lexeme.surface),
                                    ),
                                )
                            )
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
    private data class ContextCase(val reading: String, val results: List<RankedResult>)

    private data class StoredLexeme(
        val surface: String,
        val leftId: Int,
        val rightId: Int,
        val wordCost: Int,
    )

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
        val top1Correct: Int get() = outcomes.count {
            it.case.expectedTop1 != null && it.top1Matches
        }
        val top1ByCategory: Map<String, Int> get() = outcomes.groupBy { it.case.category }
            .mapValues { (_, values) ->
                values.count { it.case.expectedTop1 != null && it.top1Matches }
            }
        val top3ByCategory: Map<String, Int> get() = outcomes.groupBy { it.case.category }
            .mapValues { (_, values) -> values.count(SentenceOutcome::top3Matches) }
        val nBestByCategory: Map<String, Int> get() = outcomes.groupBy { it.case.category }
            .mapValues { (_, values) -> values.count(SentenceOutcome::nBestMatches) }
        val forbiddenCount: Int get() = outcomes.sumOf(SentenceOutcome::forbiddenCount)

        fun regressionsMessage(): String = buildString {
            append(render())
            if (regressions.isNotEmpty()) append("\nRegression IDs: ")
                .append(regressions.joinToString { it.case.id })
        }

        fun render(): String = buildString {
            val top1Denominator = outcomes.count { it.case.expectedTop1 != null }
            val correctTop1 = top1Correct
            val top3Correct = outcomes.count(SentenceOutcome::top3Matches)
            val forbidden = forbiddenCount
            val knownFailures = outcomes.count { it.case.baseline == SentenceBaseline.KNOWN_FAILURE }
            val unresolved = outcomes.count { it.case.baseline == SentenceBaseline.KNOWN_FAILURE && !it.passes }
            val improvements = outcomes.count(SentenceOutcome::isImprovement)
            appendLine("sentence-conversion-regression: cases=${outcomes.size}")
            appendLine("top-1 accuracy: $correctTop1/$top1Denominator (${percent(correctTop1, top1Denominator)})")
            appendLine("top-3 containment: $top3Correct/${outcomes.size} (${percent(top3Correct, outcomes.size)})")
            appendLine("forbidden candidate count: $forbidden")
            appendLine("known failures: unresolved=$unresolved/$knownFailures improvements=$improvements regressions=${regressions.size}")
            outcomes.groupBy { it.case.category }.toSortedMap().forEach { (category, categoryOutcomes) ->
                val categoryTop1Denominator = categoryOutcomes.count { it.case.expectedTop1 != null }
                val categoryTop1 = categoryOutcomes.count {
                    it.case.expectedTop1 != null && it.top1Matches
                }
                appendLine(
                    "category[$category]: top1=$categoryTop1/$categoryTop1Denominator " +
                        "top3=${categoryOutcomes.count(SentenceOutcome::top3Matches)}/${categoryOutcomes.size} " +
                        "nbest=${categoryOutcomes.count(SentenceOutcome::nBestMatches)}/${categoryOutcomes.size} " +
                        "pass=${categoryOutcomes.count(SentenceOutcome::passes)}/${categoryOutcomes.size} " +
                        "forbidden=${categoryOutcomes.sumOf(SentenceOutcome::forbiddenCount)}"
                )
            }
            outcomes.filter { !it.passes }.forEach { outcome ->
                appendLine("  ${outcome.case.id} [${outcome.case.baseline.name.lowercase()}] reading=${outcome.case.reading} expectedTop1=${outcome.case.expectedTop1 ?: "-"} required=${outcome.case.requiredNBest} actual=${outcome.candidates}")
            }
        }

        private fun percent(numerator: Int, denominator: Int): String =
            if (denominator == 0) "n/a" else "%.1f%%".format(java.util.Locale.ROOT, numerator * 100.0 / denominator)
    }
}
