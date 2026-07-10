package com.example.furiganakeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class ReadingDatabaseTest {
    @Test
    fun databaseMeetsCoverageContract() {
        connect().use { db ->
            assertEquals("3", metadata(db, "schema_version"))
            assertEquals("13108", metadata(db, "kanji_characters"))
            assertTrue(metadata(db, "kanji_priorities").toInt() >= 3_000)
            assertEquals("6356", metadata(db, "model_han_labels"))
            assertEquals("0", metadata(db, "model_missing_readings"))
            assertTrue(metadata(db, "word_pairs").toInt() >= 240_000)
        }
    }

    @Test
    fun kanjidicIncludesPreviouslyUnknownAndSupplementaryKanji() {
        connect().use { db ->
            assertTrue(readings(db, "龍", "kanji_reading").isNotEmpty())
            assertTrue(readings(db, "𠮟", "kanji_reading").isNotEmpty())
            assertEquals(listOf("ドウ", "おな.じ"), readings(db, "仝", "kanji_reading"))
            assertTrue(readings(db, "鬥", "kanji_reading").contains("とうがまえ"))
        }
    }

    @Test
    fun kanjidicIncludesJoyoAndFrequencyPriority() {
        connect().use { db ->
            db.prepareStatement(
                "SELECT grade, frequency FROM kanji_priority WHERE literal=?"
            ).use { statement ->
                statement.setString(1, "日")
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(1, result.getInt(1))
                    assertEquals(1, result.getInt(2))
                }
            }
        }
    }

    @Test
    fun jmdictProvidesExactWordReadings() {
        connect().use { db ->
            assertEquals("きょう", readings(db, "今日", "word_reading").first())
            assertEquals("おとな", readings(db, "大人", "word_reading").first())
            val japan = readings(db, "日本", "word_reading").take(2).toSet()
            assertEquals(setOf("にっぽん", "にほん"), japan)
        }
    }

    @Test
    fun jmdictSupportsIndexedReadingPrefixConversion() {
        connect().use { db ->
            val indexes = db.metaData.getIndexInfo(null, null, "word_reading", false, false).use { result ->
                buildSet { while (result.next()) result.getString("INDEX_NAME")?.let(::add) }
            }
            assertTrue(indexes.contains("word_reading_reading_rank"))
            db.prepareStatement(
                """SELECT surface FROM word_reading
                   WHERE reading>=? AND reading<?
                   ORDER BY CASE WHEN reading=? THEN 0 ELSE 1 END, priority, length(surface), surface
                   LIMIT 20"""
            ).use { statement ->
                statement.setString(1, "にほん")
                statement.setString(2, "にほん" + String(Character.toChars(Character.MAX_CODE_POINT)))
                statement.setString(3, "にほん")
                statement.executeQuery().use { result ->
                    val surfaces = buildList { while (result.next()) add(result.getString(1)) }
                    assertTrue(surfaces.contains("日本"))
                }
            }
        }
    }

    private fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}")

    private fun metadata(db: Connection, key: String): String = db.prepareStatement(
        "SELECT value FROM metadata WHERE key=?"
    ).use { statement ->
        statement.setString(1, key)
        statement.executeQuery().use { result ->
            check(result.next())
            result.getString(1)
        }
    }

    private fun readings(db: Connection, surface: String, table: String): List<String> {
        val key = if (table == "kanji_reading") "literal" else "surface"
        val order = if (table == "kanji_reading") "kind, position" else "priority, reading"
        return db.prepareStatement("SELECT reading FROM $table WHERE $key=? ORDER BY $order").use { statement ->
            statement.setString(1, surface)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            }
        }
    }

    companion object {
        private lateinit var database: File

        @JvmStatic
        @BeforeClass
        fun locateDatabase() {
            Class.forName("org.sqlite.JDBC")
            database = sequenceOf(
                File("src/main/assets/reading.db"),
                File("app/src/main/assets/reading.db")
            ).first { it.isFile }
        }
    }
}
