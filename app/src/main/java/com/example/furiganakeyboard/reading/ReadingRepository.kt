package com.example.furiganakeyboard.reading

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.furiganakeyboard.conversion.ConversionConnection
import com.example.furiganakeyboard.conversion.ConversionContextModel
import com.example.furiganakeyboard.conversion.ConversionCost
import com.example.furiganakeyboard.conversion.ConversionLexeme
import com.example.furiganakeyboard.conversion.ConversionText
import com.example.furiganakeyboard.data.ReadingDataStore
import java.io.File

data class WordReadingCandidate(val surface: String, val readings: List<String>)
data class KanjiUsagePriority(val grade: Int, val frequency: Int) {
    val isJoyo: Boolean get() = grade in 1..6 || grade == 8
    val isJinmeiyo: Boolean get() = grade == 9 || grade == 10
}

/** Query surface used by the IME candidate worker. Implementations are worker-confined. */
interface ReadingDataSource : AutoCloseable {
    fun readingsFor(surface: String, limit: Int = 3): List<String>
    fun readingsForMany(surfaces: List<String>, limit: Int = 3): Map<String, List<String>>
    fun suggest(prefix: String, limit: Int = 8): List<WordReadingCandidate>
    fun suggestByReading(prefix: String, limit: Int = 8): List<WordReadingCandidate>
    fun suggestForPrefixes(
        prefixes: List<String>,
        limitPerPrefix: Int = 8
    ): Map<String, List<WordReadingCandidate>>
    fun kanjiPriorities(literals: List<String>): Map<String, KanjiUsagePriority>
    /**
     * Exact lexical evidence for at most the first 24 unique, non-empty surfaces.
     * Results preserve normalized input order and include neutral unknown entries.
     */
    fun lexicalEvidenceForSurfaces(
        surfaces: List<String>
    ): Map<String, SurfaceLexicalEvidence> = SurfaceLexicalEvidenceBatch.unknown(surfaces)
    fun conversionLexemes(
        reading: String,
        maxTokenCodePoints: Int,
        limitPerReading: Int
    ): List<ConversionLexeme> = emptyList()
    fun conversionConnections(): List<ConversionConnection> = emptyList()
    fun conversionContextModel(): ConversionContextModel = ConversionContextModel.empty()
}

/** Read-only access to the bundled KANJIDIC2 and JMdict snapshot. */
class ReadingRepository(context: Context) : ReadingDataSource {
    private val database: SQLiteDatabase
    private val contextModel: ConversionContextModel
    private val lexicalEvidenceReader: SQLiteSurfaceLexicalEvidenceReader
    private var cachedConversionConnections: List<ConversionConnection>? = null

    init {
        contextModel = context.applicationContext.assets.open(CONTEXT_MODEL_ASSET).use { input ->
            ConversionContextModel.decode(input.readBytes())
        }
        val dbFile = ReadingDataStore.fullOrBundledCore(
            context.applicationContext,
            CORE_DB_ASSET,
            CORE_DB_FILE,
            CORE_DB_SHA256
        )
        LEGACY_DB_FILES.forEach { name -> File(context.noBackupFilesDir, name).delete() }
        database = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
        lexicalEvidenceReader = SQLiteSurfaceLexicalEvidenceReader(database)
    }

    override fun readingsFor(surface: String, limit: Int): List<String> =
        readingsForMany(listOf(surface), limit)[surface].orEmpty()

    /** Load readings for an arbitrary mix of single kanji and word surfaces in one query. */
    override fun readingsForMany(surfaces: List<String>, limit: Int): Map<String, List<String>> {
        if (limit <= 0) return emptyMap()
        val unique = surfaces.asSequence().filter(String::isNotEmpty).distinct().toList()
        if (unique.isEmpty()) return emptyMap()
        val singles = unique.filter { it.codePointCount(0, it.length) == 1 }
        val words = unique.filterNot { it.codePointCount(0, it.length) == 1 }
        val selects = buildList {
            if (singles.isNotEmpty()) {
                val placeholders = List(singles.size) { "?" }.joinToString(",")
                add(
                    """SELECT literal AS surface, reading, kind AS rank1, position AS rank2
                       FROM kanji_reading WHERE literal IN ($placeholders)""".trimIndent()
                )
            }
            if (words.isNotEmpty()) {
                val placeholders = List(words.size) { "?" }.joinToString(",")
                add(
                    """SELECT surface, reading, reading_priority AS rank1,
                              reading_position * 10 + form_rank AS rank2
                       FROM word_reading AS candidate
                       WHERE surface IN ($placeholders)
                         AND (
                           reading_priority < 100 OR NOT EXISTS (
                             SELECT 1 FROM word_reading AS preferred
                             WHERE preferred.surface = candidate.surface
                               AND preferred.reading_priority < 100
                           )
                         )""".trimIndent()
                )
            }
        }
        val args = (singles + words).toTypedArray()
        return database.rawQuery(
            selects.joinToString(" UNION ALL ") + " ORDER BY surface, rank1, rank2, reading",
            args
        ).use { cursor ->
            val output = LinkedHashMap<String, MutableList<String>>()
            while (cursor.moveToNext()) {
                val surface = cursor.getString(0)
                val readings = output.getOrPut(surface) { mutableListOf() }
                if (readings.size < limit) readings += cursor.getString(1)
            }
            output
        }
    }

    override fun suggest(prefix: String, limit: Int): List<WordReadingCandidate> {
        if (prefix.isEmpty()) return emptyList()
        val upperBound = prefix + String(Character.toChars(Character.MAX_CODE_POINT))
        val surfaces = database.rawQuery(
            """SELECT surface, min(priority) AS best_priority
               FROM word_reading
               WHERE surface>=? AND surface<? AND form_rank=0
               GROUP BY surface
               ORDER BY CASE WHEN surface=? THEN 0 ELSE 1 END,
                        best_priority, length(surface), surface
               LIMIT ?""",
            arrayOf(prefix, upperBound, prefix, limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        val readings = readingsForMany(surfaces)
        return surfaces.map { WordReadingCandidate(it, readings[it].orEmpty()) }
    }

    /**
     * JMdict conversion candidates whose kana reading starts with [prefix].
     * Natural word + particle compositions (きょう + は -> 今日は) are
     * promoted ahead of unrelated whole-word homophones such as 教派.
     */
    override fun suggestByReading(prefix: String, limit: Int): List<WordReadingCandidate> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val upperBound = prefix + String(Character.toChars(Character.MAX_CODE_POINT))
        val surfaces = database.rawQuery(
            """SELECT surface, min(priority) AS best_priority,
                      min(CASE WHEN reading=? THEN 0 ELSE 1 END) AS exact_match,
                      min(form_rank) AS best_form_rank
               FROM word_reading
               WHERE reading>=? AND reading<?
               GROUP BY surface
               ORDER BY exact_match, best_priority, best_form_rank,
                        length(surface), surface
               LIMIT ?""",
            arrayOf(prefix, prefix, upperBound, limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        val matchingReadings = matchingWordReadings(surfaces, prefix, upperBound)
        val wholeWordCandidates = surfaces.map {
            WordReadingCandidate(it, matchingReadings[it].orEmpty())
        }
        return (composedReadingCandidates(prefix, limit) + wholeWordCandidates)
            .distinctBy { it.surface }
            .take(limit)
    }

    private fun composedReadingCandidates(
        reading: String,
        limit: Int
    ): List<WordReadingCandidate> = buildList {
        for (split in reading.lastIndex downTo 1) {
            val suffix = reading.substring(split)
            if (suffix !in COMPOSABLE_KANA_SUFFIXES) continue
            val wordReading = reading.substring(0, split)
            exactSurfacesByReading(wordReading, COMPOSED_PER_SPLIT_LIMIT)
                .asSequence()
                .filter { it != wordReading }
                .forEach { surface ->
                    add(WordReadingCandidate(surface + suffix, listOf(reading)))
                }
            if (size >= limit) break
        }
    }.distinctBy { it.surface }.take(limit)

    private fun exactSurfacesByReading(reading: String, limit: Int): List<String> =
        database.rawQuery(
            """SELECT surface, min(priority) AS best_priority,
                      min(form_rank) AS best_form_rank
               FROM word_reading
               WHERE reading=?
                GROUP BY surface
                ORDER BY CASE WHEN surface=? THEN 1 ELSE 0 END,
                        best_priority, best_form_rank, length(surface) DESC, surface
               LIMIT ?""",
            arrayOf(reading, reading, limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    /** Resolve completion candidates for several recognition alternatives in two queries. */
    override fun suggestForPrefixes(
        prefixes: List<String>,
        limitPerPrefix: Int
    ): Map<String, List<WordReadingCandidate>> {
        if (limitPerPrefix <= 0) return emptyMap()
        val unique = prefixes.asSequence().filter(String::isNotEmpty).distinct().toList()
        if (unique.isEmpty()) return emptyMap()

        val branches = unique.mapIndexed { index, _ ->
            """SELECT * FROM (
                   SELECT $index AS prefix_rank, surface, min(priority) AS best_priority
                   FROM word_reading
                   WHERE surface>=? AND surface<? AND form_rank=0
                   GROUP BY surface
                   ORDER BY CASE WHEN surface=? THEN 0 ELSE 1 END,
                            best_priority, length(surface), surface
                   LIMIT ?
               )""".trimIndent()
        }
        val args = unique.flatMap { prefix ->
            listOf(prefix, upperBound(prefix), prefix, limitPerPrefix.toString())
        }.toTypedArray()
        val surfacesByPrefix = database.rawQuery(
            branches.joinToString(" UNION ALL ") + " ORDER BY prefix_rank",
            args
        ).use { cursor ->
            val output = unique.associateWith { mutableListOf<String>() }.toMutableMap()
            while (cursor.moveToNext()) {
                output.getValue(unique[cursor.getInt(0)]) += cursor.getString(1)
            }
            output
        }
        val allSurfaces = surfacesByPrefix.values.flatten().distinct()
        val readings = readingsForMany(allSurfaces)
        return unique.associateWith { prefix ->
            surfacesByPrefix.getValue(prefix).map { surface ->
                WordReadingCandidate(surface, readings[surface].orEmpty())
            }
        }
    }

    private fun matchingWordReadings(
        surfaces: List<String>,
        prefix: String,
        upperBound: String,
        limit: Int = 3
    ): Map<String, List<String>> {
        if (surfaces.isEmpty()) return emptyMap()
        val placeholders = List(surfaces.size) { "?" }.joinToString(",")
        val args = (surfaces + listOf(prefix, upperBound, prefix)).toTypedArray()
        return database.rawQuery(
            """SELECT surface, reading FROM word_reading
               WHERE surface IN ($placeholders) AND reading>=? AND reading<?
               ORDER BY surface, CASE WHEN reading=? THEN 0 ELSE 1 END,
                        form_rank, reading_priority, reading_position, priority, reading""",
            args
        ).use { cursor ->
            val output = LinkedHashMap<String, MutableList<String>>()
            while (cursor.moveToNext()) {
                val readings = output.getOrPut(cursor.getString(0)) { mutableListOf() }
                if (readings.size < limit) readings += cursor.getString(1)
            }
            output
        }
    }

    /** Batch-load KANJIDIC grade/frequency for recognition candidate re-ranking. */
    override fun kanjiPriorities(literals: List<String>): Map<String, KanjiUsagePriority> {
        val unique = literals.asSequence()
            .filter { it.codePointCount(0, it.length) == 1 }
            .distinct()
            .toList()
        if (unique.isEmpty()) return emptyMap()
        return buildMap {
            unique.chunked(MAX_SQL_BIND_ARGS).forEach { chunk ->
                val placeholders = List(chunk.size) { "?" }.joinToString(",")
                database.rawQuery(
                    "SELECT literal, grade, frequency FROM kanji_priority WHERE literal IN ($placeholders)",
                    chunk.toTypedArray()
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        put(
                            cursor.getString(0),
                            KanjiUsagePriority(cursor.getInt(1), cursor.getInt(2))
                        )
                    }
                }
            }
        }
    }

    override fun lexicalEvidenceForSurfaces(
        surfaces: List<String>
    ): Map<String, SurfaceLexicalEvidence> = lexicalEvidenceReader.load(surfaces)

    /**
     * Expand exact and full-width katakana conversion matches at every occurrence.
     * The normal 48-code-point input produces at most 1,296 exact/transliterated readings;
     * chunking keeps each query below Android SQLite's conservative bind-variable limit.
     */
    override fun conversionLexemes(
        reading: String,
        maxTokenCodePoints: Int,
        limitPerReading: Int
    ): List<ConversionLexeme> {
        if (reading.isEmpty() || maxTokenCodePoints <= 0 || limitPerReading <= 0) {
            return emptyList()
        }
        if (reading.codePointCount(0, reading.length) > MAX_CONVERSION_INPUT_CODE_POINTS) {
            return emptyList()
        }
        val tokenLimit = minOf(maxTokenCodePoints, MAX_CONVERSION_TOKEN_CODE_POINTS)
        val vocabularyLimit = minOf(limitPerReading, MAX_CONVERSION_VOCABULARY_PER_READING)
        val occurrences = substringOccurrences(reading, tokenLimit)
        if (occurrences.isEmpty()) return emptyList()

        val databaseReadings = occurrences.keys.flatMap { token ->
            buildList {
                add(token)
                add(ConversionText.toKatakana(token))
            }
        }.distinct()
        val lexemesByReading = LinkedHashMap<String, MutableList<StoredConversionLexeme>>()
        databaseReadings.chunked(MAX_SQL_BIND_ARGS).forEach { chunk ->
            val placeholders = List(chunk.size) { "?" }.joinToString(",")
            database.rawQuery(
                """SELECT reading, surface, left_id, right_id, word_cost, form_rank
                   FROM conversion_lexeme
                   WHERE reading IN ($placeholders)
                   ORDER BY reading, word_cost, form_rank, surface, left_id, right_id""".trimIndent(),
                chunk.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val tokenReading = cursor.getString(0)
                    val values = lexemesByReading.getOrPut(tokenReading) { mutableListOf() }
                    if (values.size < vocabularyLimit) {
                        values += StoredConversionLexeme(
                            surface = cursor.getString(1),
                            leftId = cursor.getInt(2),
                            rightId = cursor.getInt(3),
                            wordCost = ConversionCost.clampInt32(cursor.getLong(4)),
                            formRank = cursor.getInt(5),
                        )
                    }
                }
            }
        }
        val usagePriorities = kanjiPriorities(
            lexemesByReading.values.asSequence()
                .flatten()
                .flatMap { ConversionCost.hanLiterals(it.surface) }
                .distinct()
                .toList()
        )
        val frequencyByHanLiteral = usagePriorities.mapValues { it.value.frequency }
        return buildList {
            occurrences.forEach { (tokenReading, positions) ->
                val katakanaReading = ConversionText.toKatakana(tokenReading)
                val stored = (
                    lexemesByReading[tokenReading].orEmpty() +
                        lexemesByReading[katakanaReading].orEmpty().filter { lexeme ->
                            ConversionText.isKatakanaTransliteration(tokenReading, lexeme.surface)
                        }
                    )
                    .distinctBy { listOf(it.surface, it.leftId, it.rightId) }
                    .sortedWith(STORED_CONVERSION_ORDER)
                positions.forEach { position ->
                    stored.forEach { lexeme ->
                        add(
                            ConversionLexeme(
                                start = position.start,
                                end = position.end,
                                reading = tokenReading,
                                surface = lexeme.surface,
                                leftId = lexeme.leftId,
                                rightId = lexeme.rightId,
                                wordCost = ConversionCost.adjustedWordCost(
                                    reading = tokenReading,
                                    surface = lexeme.surface,
                                    leftId = lexeme.leftId,
                                    rawWordCost = lexeme.wordCost,
                                    frequencyByHanLiteral = frequencyByHanLiteral,
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    /** Loaded once by the candidate worker; ReadingRepository itself is worker-confined. */
    override fun conversionConnections(): List<ConversionConnection> {
        cachedConversionConnections?.let { return it }
        return database.rawQuery(
            """SELECT previous_right_id, next_left_id, cost
               FROM connection_cost
               ORDER BY previous_right_id, next_left_id""".trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ConversionConnection(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)))
                }
            }
        }.also { cachedConversionConnections = it }
    }

    override fun conversionContextModel(): ConversionContextModel = contextModel

    private fun substringOccurrences(
        input: String,
        maxTokenCodePoints: Int
    ): LinkedHashMap<String, MutableList<TextRange>> {
        val boundaries = ConversionText.utf16Boundaries(input)
        val result = LinkedHashMap<String, MutableList<TextRange>>()
        for (startIndex in 0 until boundaries.lastIndex) {
            val finalIndex = minOf(boundaries.lastIndex, startIndex + maxTokenCodePoints)
            for (endIndex in startIndex + 1..finalIndex) {
                val start = boundaries[startIndex]
                val end = boundaries[endIndex]
                val token = input.substring(start, end)
                result.getOrPut(token) { mutableListOf() } += TextRange(startIndex, endIndex)
            }
        }
        return result
    }

    private data class TextRange(val start: Int, val end: Int)
    private data class StoredConversionLexeme(
        val surface: String,
        val leftId: Int,
        val rightId: Int,
        val wordCost: Int,
        val formRank: Int,
    )

    fun metadata(key: String): String? = database.rawQuery(
        "SELECT value FROM metadata WHERE key=?", arrayOf(key)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    override fun close() = database.close()

    private fun upperBound(prefix: String): String =
        prefix + String(Character.toChars(Character.MAX_CODE_POINT))

    companion object {
        // `reading.db` remains the transitional asset until the deterministic core generator
        // publishes `reading-core.db`. Its installed filename is intentionally distinct from
        // the old full asset so ReadingDataStore can preserve `reading-v8.db` first.
        private const val CORE_DB_ASSET = "reading.db"
        private const val CONTEXT_MODEL_ASSET = "context-model.bin"
        private const val CORE_DB_FILE = "reading-core-v8.db"
        private const val CORE_DB_SHA256 =
            "991a13b8552748ea2c35fb229446809869a0ceee14ba0107a65351c8527efbc2"
        private val LEGACY_DB_FILES = listOf(
            "reading-v1.db", "reading-v1.db.sha256",
            "reading-v2.db", "reading-v2.db.sha256",
            "reading-v3.db", "reading-v3.db.sha256",
            "reading-v4.db", "reading-v4.db.sha256",
            "reading-v5.db", "reading-v5.db.sha256",
            "reading-v6.db", "reading-v6.db.sha256",
            "reading-v7.db", "reading-v7.db.sha256"
        )
        private const val MAX_SQL_BIND_ARGS = 900
        private const val MAX_CONVERSION_INPUT_CODE_POINTS = 48
        private const val MAX_CONVERSION_TOKEN_CODE_POINTS = 16
        private const val MAX_CONVERSION_VOCABULARY_PER_READING = 12
        private val STORED_CONVERSION_ORDER = Comparator<StoredConversionLexeme> { left, right ->
            compareValues(left.wordCost, right.wordCost).takeUnless { it == 0 }
                ?: compareValues(left.formRank, right.formRank).takeUnless { it == 0 }
                ?: ConversionText.compareScalars(left.surface, right.surface).takeUnless { it == 0 }
                ?: compareValues(left.leftId, right.leftId).takeUnless { it == 0 }
                ?: compareValues(left.rightId, right.rightId)
        }
        private const val COMPOSED_PER_SPLIT_LIMIT = 4
        private val COMPOSABLE_KANA_SUFFIXES = setOf(
            "は", "が", "を", "に", "で", "と", "も", "の", "へ", "や", "か", "ね", "よ",
            "から", "まで", "より", "ので", "のに", "には", "では", "とは", "へは", "にも",
            "でも", "とも", "しか", "だけ"
        )
    }
}
