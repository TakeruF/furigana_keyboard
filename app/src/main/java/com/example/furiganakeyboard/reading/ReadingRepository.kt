package com.example.furiganakeyboard.reading

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.furiganakeyboard.data.AssetInstaller

data class WordReadingCandidate(val surface: String, val readings: List<String>)

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

    fun metadata(key: String): String? = database.rawQuery(
        "SELECT value FROM metadata WHERE key=?", arrayOf(key)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    override fun close() = database.close()

    companion object {
        private const val DB_ASSET = "reading.db"
        private const val DB_FILE = "reading-v1.db"
        private const val DB_SHA256 =
            "b505bacfb48bff9f328123dc4239104a2405cace90abdd098b5566d28948134f"
    }
}
