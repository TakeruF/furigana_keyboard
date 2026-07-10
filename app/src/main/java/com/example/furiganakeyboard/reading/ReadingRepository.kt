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

/** Read-only access to the bundled KANJIDIC2 and JMdict snapshot. */
class ReadingRepository(context: Context) : AutoCloseable {
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

    fun readingsFor(surface: String, limit: Int = 3): List<String> {
        if (surface.isEmpty()) return emptyList()
        val isSingleCodePoint = surface.codePointCount(0, surface.length) == 1
        val sql: String
        val args: Array<String>
        if (isSingleCodePoint) {
            sql = """SELECT reading FROM kanji_reading
                WHERE literal=? ORDER BY kind, position LIMIT ?"""
            args = arrayOf(surface, limit.toString())
        } else {
            sql = """SELECT reading FROM word_reading
                WHERE surface=? ORDER BY priority, reading LIMIT ?"""
            args = arrayOf(surface, limit.toString())
        }
        return database.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    fun suggest(prefix: String, limit: Int = 8): List<WordReadingCandidate> {
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
        return surfaces.map { WordReadingCandidate(it, readingsFor(it)) }
    }

    /** JMdict conversion candidates whose kana reading starts with [prefix]. */
    fun suggestByReading(prefix: String, limit: Int = 8): List<WordReadingCandidate> {
        if (prefix.isEmpty()) return emptyList()
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
        return surfaces.map { surface ->
            val matchingReadings = database.rawQuery(
                """SELECT reading FROM word_reading
                   WHERE surface=? AND reading>=? AND reading<?
                   ORDER BY CASE WHEN reading=? THEN 0 ELSE 1 END, priority, reading
                   LIMIT 3""",
                arrayOf(surface, prefix, upperBound, prefix)
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            WordReadingCandidate(surface, matchingReadings)
        }
    }

    /** Batch-load KANJIDIC grade/frequency for recognition candidate re-ranking. */
    fun kanjiPriorities(literals: List<String>): Map<String, KanjiUsagePriority> {
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

    companion object {
        private const val DB_ASSET = "reading.db"
        private const val DB_FILE = "reading-v3.db"
        private const val DB_SHA256 =
            "2d32ffc75a600ca090724ceeece4c70c1630ecd1bf16eca4383a67b2ba27a3ae"
        private val LEGACY_DB_FILES = listOf(
            "reading-v1.db", "reading-v1.db.sha256",
            "reading-v2.db", "reading-v2.db.sha256"
        )
    }
}
