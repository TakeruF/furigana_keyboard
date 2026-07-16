package com.example.furiganakeyboard.reading

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SQLiteSurfaceLexicalEvidenceReaderTest {
    private lateinit var database: SQLiteDatabase

    @Before
    fun setUp() {
        database = SQLiteDatabase.create(null)
        database.execSQL(
            """CREATE TABLE word_reading (
                surface TEXT NOT NULL,
                reading TEXT NOT NULL
            )"""
        )
        database.execSQL(
            """CREATE TABLE kanji_reading (
                literal TEXT NOT NULL,
                reading TEXT NOT NULL
            )"""
        )
        database.execSQL(
            """CREATE TABLE conversion_lexeme (
                surface TEXT NOT NULL,
                left_id INTEGER NOT NULL,
                right_id INTEGER NOT NULL,
                source TEXT NOT NULL
            )"""
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun oneBatchQueryReturnsExactUnknownProperNameAndPlaceEvidence() {
        database.execSQL("INSERT INTO word_reading VALUES ('家', 'いえ')")
        database.execSQL("INSERT INTO kanji_reading VALUES ('鬱', 'ウツ')")
        database.execSQL("INSERT INTO word_reading VALUES ('佐藤', 'さとう')")
        database.execSQL("INSERT INTO conversion_lexeme VALUES ('佐藤', 4, 4, 'jmnedict_person')")
        database.execSQL("INSERT INTO word_reading VALUES ('茨城', 'いばらき')")
        database.execSQL("INSERT INTO conversion_lexeme VALUES ('茨城', 4, 4, 'jmnedict_place')")
        var queryCount = 0
        val reader = SQLiteSurfaceLexicalEvidenceReader(
            database = database,
            requireWorkerThread = {},
            onQuery = { queryCount += 1 },
        )

        val evidence = reader.load(listOf("", "家", "佐藤", "茨城", "未知語", "鬱", "家"))

        assertEquals(1, queryCount)
        assertEquals(listOf("家", "佐藤", "茨城", "未知語", "鬱"), evidence.keys.toList())
        evidence.getValue("家").also {
            assertTrue(it.exact)
            assertFalse(it.properName)
            assertFalse(it.placeName)
            assertEquals(-0.08f, it.costAdjustment, 0f)
            assertEquals(0f, it.applyToShapeCost(0.03f), 0f)
        }
        evidence.getValue("佐藤").also {
            assertTrue(it.exact)
            assertTrue(it.properName)
            assertFalse(it.placeName)
            assertTrue(it.named)
            assertEquals(-0.04f, it.costAdjustment, 0f)
        }
        evidence.getValue("茨城").also {
            assertTrue(it.exact)
            assertTrue(it.properName)
            assertTrue(it.placeName)
            assertEquals(-0.04f, it.costAdjustment, 0f)
        }
        evidence.getValue("未知語").also {
            assertFalse(it.exact)
            assertEquals(0f, it.costAdjustment, 0f)
        }
        assertTrue(evidence.getValue("鬱").exact)
    }

    @Test
    fun inputAboveBindLimitIsCappedBeforeIssuingOneQuery() {
        var queryCount = 0
        val reader = SQLiteSurfaceLexicalEvidenceReader(
            database = database,
            requireWorkerThread = {},
            onQuery = { queryCount += 1 },
        )

        val evidence = reader.load((0 until 40).map { "surface-$it" })

        assertEquals(1, queryCount)
        assertEquals(24, evidence.size)
        assertEquals("surface-0", evidence.keys.first())
        assertEquals("surface-23", evidence.keys.last())
    }

    @Test
    fun emptyBatchDoesNotTouchDatabaseOrRequireAWorkerThread() {
        var queryCount = 0
        val reader = SQLiteSurfaceLexicalEvidenceReader(
            database = database,
            requireWorkerThread = { error("must not be called") },
            onQuery = { queryCount += 1 },
        )

        assertTrue(reader.load(listOf("", "")).isEmpty())
        assertEquals(0, queryCount)
    }

    @Test
    fun databaseReadIsRejectedOnTheMainThread() {
        val reader = SQLiteSurfaceLexicalEvidenceReader(database)

        assertThrows(IllegalStateException::class.java) {
            reader.load(listOf("家"))
        }
    }
}
