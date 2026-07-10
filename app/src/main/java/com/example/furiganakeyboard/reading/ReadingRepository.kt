package com.example.furiganakeyboard.reading

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.furiganakeyboard.data.AssetInstaller
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
}

/** Read-only access to the bundled KANJIDIC2 and JMdict snapshot. */
class ReadingRepository(context: Context) : ReadingDataSource {
    private val database: SQLiteDatabase

    init {
        val dbFile = AssetInstaller.ensure(
            context.applicationContext,
            DB_ASSET,
            DB_FILE,
            DB_SHA256
        )
        LEGACY_DB_FILES.forEach { name -> File(context.noBackupFilesDir, name).delete() }
        database = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
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
                    """SELECT surface, reading, priority AS rank1, 0 AS rank2
                       FROM word_reading WHERE surface IN ($placeholders)""".trimIndent()
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
               WHERE surface>=? AND surface<?
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
                      min(CASE WHEN reading=? THEN 0 ELSE 1 END) AS exact_match
               FROM word_reading
               WHERE reading>=? AND reading<?
               GROUP BY surface
               ORDER BY exact_match, best_priority, length(surface), surface
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
            """SELECT surface, min(priority) AS best_priority
               FROM word_reading
               WHERE reading=?
               GROUP BY surface
               ORDER BY CASE WHEN surface=? THEN 1 ELSE 0 END,
                        best_priority, length(surface) DESC, surface
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
                   WHERE surface>=? AND surface<?
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
               ORDER BY surface, CASE WHEN reading=? THEN 0 ELSE 1 END, priority, reading""",
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
        val placeholders = List(unique.size) { "?" }.joinToString(",")
        return database.rawQuery(
            "SELECT literal, grade, frequency FROM kanji_priority WHERE literal IN ($placeholders)",
            unique.toTypedArray()
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(0),
                        KanjiUsagePriority(cursor.getInt(1), cursor.getInt(2))
                    )
                }
            }
        }
    }

    fun metadata(key: String): String? = database.rawQuery(
        "SELECT value FROM metadata WHERE key=?", arrayOf(key)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    override fun close() = database.close()

    private fun upperBound(prefix: String): String =
        prefix + String(Character.toChars(Character.MAX_CODE_POINT))

    companion object {
        private const val DB_ASSET = "reading.db"
        private const val DB_FILE = "reading-v3.db"
        private const val DB_SHA256 =
            "2d32ffc75a600ca090724ceeece4c70c1630ecd1bf16eca4383a67b2ba27a3ae"
        private val LEGACY_DB_FILES = listOf(
            "reading-v1.db", "reading-v1.db.sha256",
            "reading-v2.db", "reading-v2.db.sha256"
        )
        private const val COMPOSED_PER_SPLIT_LIMIT = 4
        private val COMPOSABLE_KANA_SUFFIXES = setOf(
            "は", "が", "を", "に", "で", "と", "も", "の", "へ", "や", "か", "ね", "よ",
            "から", "まで", "より", "ので", "のに", "には", "では", "とは", "へは", "にも",
            "でも", "とも", "しか", "だけ"
        )
    }
}
