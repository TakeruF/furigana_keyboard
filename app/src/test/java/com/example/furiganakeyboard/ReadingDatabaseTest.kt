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
            assertEquals("8", metadata(db, "schema_version"))
            assertEquals("13108", metadata(db, "kanji_characters"))
            assertTrue(metadata(db, "kanji_priorities").toInt() >= 3_000)
            assertEquals("6356", metadata(db, "model_han_labels"))
            assertEquals("0", metadata(db, "model_missing_readings"))
            assertTrue(metadata(db, "word_pairs").toInt() >= 240_000)
            assertTrue(metadata(db, "inflected_pairs").toInt() >= 15_000)
            assertTrue(metadata(db, "geographic_name_pairs").toInt() >= 90_000)
            assertTrue(metadata(db, "conversion_lexemes").toInt() >= 200_000)
            assertEquals("256", metadata(db, "connection_costs"))
            assertTrue(database.length() < 128L * 1024 * 1024)
            assertEquals("ok", db.createStatement().use { statement ->
                statement.executeQuery("PRAGMA integrity_check").use { result ->
                    check(result.next())
                    result.getString(1)
                }
            })
        }
    }

    @Test
    fun conversionSchemaUsesTheFixedPosIdsAndBounds() {
        connect().use { db ->
            val actualPos = db.createStatement().use { statement ->
                statement.executeQuery("SELECT id, name FROM conversion_pos ORDER BY id").use { result ->
                    buildList { while (result.next()) add(result.getInt(1) to result.getString(2)) }
                }
            }
            assertEquals(
                listOf(
                    0 to "BOS", 1 to "EOS", 2 to "PRONOUN", 3 to "NOUN",
                    4 to "PROPER_NOUN", 5 to "PARTICLE", 6 to "AUXILIARY",
                    7 to "VERB", 8 to "ADJECTIVE", 9 to "ADVERB", 10 to "PREFIX",
                    11 to "SUFFIX", 12 to "EXPRESSION", 13 to "SYMBOL", 14 to "OTHER",
                    15 to "COPY"
                ),
                actualPos
            )
            assertEquals(256, db.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM connection_cost").use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            })
            db.createStatement().use { statement ->
                statement.executeQuery(
                    """SELECT max(candidate_count), max(length(reading)), max(length(surface))
                       FROM (
                         SELECT reading, surface, count(*) OVER (PARTITION BY reading) AS candidate_count
                         FROM conversion_lexeme
                       )"""
                ).use { result ->
                    assertTrue(result.next())
                    assertTrue(result.getInt(1) <= 12)
                    assertTrue(result.getInt(2) <= 16)
                    assertTrue(result.getInt(3) <= 16)
                }
            }
        }
    }

    @Test
    fun conversionLexemesCoverRequiredPhraseParts() {
        connect().use { db ->
            listOf(
                Triple("これ", "これ", 2),
                Triple("も", "も", 5),
                Triple("は", "は", 5),
                Triple("ほん", "本", 3),
                Triple("だ", "だ", 6),
                Triple("きょう", "今日", 3),
                Triple("はれ", "晴れ", 3),
                Triple("わたし", "私", 2),
                Triple("いく", "行く", 7),
                Triple("を", "を", 5),
                Triple("よむ", "読む", 7)
            ).forEach { (reading, surface, posId) ->
                db.prepareStatement(
                    "SELECT 1 FROM conversion_lexeme WHERE reading=? AND surface=? AND left_id=?"
                ).use { statement ->
                    statement.setString(1, reading)
                    statement.setString(2, surface)
                    statement.setInt(3, posId)
                    statement.executeQuery().use { result ->
                        assertTrue("Missing conversion lexeme $surface/$reading/$posId", result.next())
                    }
                }
            }
        }
    }

    @Test
    fun usuallyKanaSpellingAndUsefulConnectionsHaveLowerCosts() {
        connect().use { db ->
            val kanaCost = conversionCost(db, "これ", "これ", 2)
            listOf("此", "之", "是").forEach { oldSurface ->
                db.prepareStatement(
                    "SELECT word_cost FROM conversion_lexeme WHERE reading='これ' AND surface=?"
                ).use { statement ->
                    statement.setString(1, oldSurface)
                    statement.executeQuery().use { result ->
                        if (result.next()) assertTrue("$oldSurface outranks これ", kanaCost < result.getInt(1))
                    }
                }
            }
            listOf(2 to 5, 5 to 3, 3 to 6, 3 to 5, 5 to 7, 7 to 6, 8 to 6).forEach {
                (previousId, nextId) ->
                assertTrue(connectionCost(db, previousId, nextId) < connectionCost(db, 2, 3))
            }
            db.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT count(*), min(word_cost) FROM conversion_lexeme WHERE source='jmnedict_place'"
                ).use { result ->
                    assertTrue(result.next())
                    assertTrue(result.getInt(1) >= 90_000)
                    assertTrue(result.getInt(2) >= 9_000)
                }
            }
        }
    }

    @Test
    fun jmnedictProvidesPlaceNameReadings() {
        connect().use { db ->
            assertEquals(listOf("いけぶくろ"), readings(db, "池袋", "word_reading"))
            assertTrue(readings(db, "池袋駅", "word_reading").contains("いけぶくろえき"))
            assertTrue(readings(db, "新宿", "word_reading").contains("しんじゅく"))
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
            assertEquals(listOf("げんご"), readings(db, "言語", "word_reading"))
            assertTrue(rawWordReadings(db, "言語").none { it == "げんきょ" })
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

    @Test
    fun todayIsThePreferredExactWordForKyouPhraseComposition() {
        connect().use { db ->
            db.prepareStatement(
                """SELECT surface, min(priority) AS best_priority
                   FROM word_reading
                   WHERE reading=?
                   GROUP BY surface
                   ORDER BY CASE WHEN surface=? THEN 1 ELSE 0 END,
                            best_priority, length(surface) DESC, surface
                   LIMIT 1"""
            ).use { statement ->
                statement.setString(1, "きょう")
                statement.setString(2, "きょう")
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals("今日", result.getString(1))
                }
            }
        }
    }

    @Test
    fun commonInflectionsAreGeneratedFromDictionaryBaseForms() {
        connect().use { db ->
            val expected = mapOf(
                "考えて" to "かんがえて",
                "食べて" to "たべて",
                "飲んで" to "のんで",
                "書いた" to "かいた",
                "話した" to "はなした",
                "読んだ" to "よんだ",
                "大きかった" to "おおきかった",
                "高くない" to "たかくない",
                "来て" to "きて",
                "使え" to "つかえ",
                "使えて" to "つかえて",
                "確認したい" to "かくにんしたい",
                "打ちやすい" to "うちやすい",
                "打ち易い" to "うちやすい"
            )
            expected.forEach { (surface, reading) ->
                db.prepareStatement(
                    "SELECT form_rank FROM word_reading WHERE surface=? AND reading=?"
                ).use { statement ->
                    statement.setString(1, surface)
                    statement.setString(2, reading)
                    statement.executeQuery().use { result ->
                        assertTrue("Missing generated pair $surface/$reading", result.next())
                        assertTrue(result.getInt(1) > 0)
                    }
                }
            }
        }
    }

    @Test
    fun exactInflectionPrecedesLongerPhraseAndVariantSpellings() {
        connect().use { db ->
            db.prepareStatement(
                """SELECT surface, min(priority) AS best_priority,
                          min(CASE WHEN reading=? THEN 0 ELSE 1 END) AS exact_match,
                          min(form_rank) AS best_form_rank
                   FROM word_reading
                   WHERE reading>=? AND reading<?
                   GROUP BY surface
                   ORDER BY exact_match, best_priority, best_form_rank,
                            length(surface), surface
                   LIMIT 5"""
            ).use { statement ->
                val reading = "かんがえて"
                statement.setString(1, reading)
                statement.setString(2, reading)
                statement.setString(
                    3,
                    reading + String(Character.toChars(Character.MAX_CODE_POINT))
                )
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals("考えて", result.getString(1))
                }
            }
        }
    }

    @Test
    fun commonGodanPotentialAndImperativeWinOverRareHomophones() {
        connect().use { db ->
            listOf(
                Triple("つかえ", "使え", 1),
                Triple("つかえる", "使える", 1),
                Triple("つかえて", "使えて", 5)
            ).forEach { (reading, expectedSurface, visibleLimit) ->
                db.prepareStatement(
                    """SELECT surface, min(priority) AS best_priority,
                              min(CASE WHEN reading=? THEN 0 ELSE 1 END) AS exact_match,
                              min(form_rank) AS best_form_rank
                       FROM word_reading
                       WHERE reading>=? AND reading<?
                       GROUP BY surface
                       ORDER BY exact_match, best_priority, best_form_rank,
                                length(surface), surface
                       LIMIT ?"""
                ).use { statement ->
                    statement.setString(1, reading)
                    statement.setString(2, reading)
                    statement.setString(
                        3,
                        reading + String(Character.toChars(Character.MAX_CODE_POINT))
                    )
                    statement.setInt(4, visibleLimit)
                    statement.executeQuery().use { result ->
                        val surfaces = buildList {
                            while (result.next()) add(result.getString(1))
                        }
                        assertTrue(
                            "$expectedSurface is not visible for $reading: $surfaces",
                            expectedSurface in surfaces
                        )
                    }
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

    private fun conversionCost(db: Connection, reading: String, surface: String, posId: Int): Int =
        db.prepareStatement(
            "SELECT word_cost FROM conversion_lexeme WHERE reading=? AND surface=? AND left_id=?"
        ).use { statement ->
            statement.setString(1, reading)
            statement.setString(2, surface)
            statement.setInt(3, posId)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getInt(1)
            }
        }

    private fun connectionCost(db: Connection, previousId: Int, nextId: Int): Int =
        db.prepareStatement(
            "SELECT cost FROM connection_cost WHERE previous_right_id=? AND next_left_id=?"
        ).use { statement ->
            statement.setInt(1, previousId)
            statement.setInt(2, nextId)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getInt(1)
            }
        }

    private fun readings(db: Connection, surface: String, table: String): List<String> {
        val query = if (table == "kanji_reading") {
            "SELECT reading FROM kanji_reading WHERE literal=? ORDER BY kind, position"
        } else {
            """SELECT reading FROM word_reading AS candidate
               WHERE surface=? AND (
                 reading_priority < 100 OR NOT EXISTS (
                   SELECT 1 FROM word_reading AS preferred
                   WHERE preferred.surface = candidate.surface
                     AND preferred.reading_priority < 100
                 )
               )
               ORDER BY reading_priority, reading_position, form_rank, priority, reading"""
        }
        return db.prepareStatement(query).use { statement ->
            statement.setString(1, surface)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            }
        }
    }

    private fun rawWordReadings(db: Connection, surface: String): List<String> =
        db.prepareStatement(
            "SELECT reading FROM word_reading WHERE surface=? ORDER BY reading"
        ).use { statement ->
            statement.setString(1, surface)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getString(1)) }
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
