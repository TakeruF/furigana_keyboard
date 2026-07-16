package com.example.furiganakeyboard

import com.example.furiganakeyboard.reading.KanjiUsagePriority
import com.example.furiganakeyboard.reading.ReadingDataSource
import com.example.furiganakeyboard.reading.SurfaceLexicalEvidenceBatch
import com.example.furiganakeyboard.reading.WordReadingCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceLexicalEvidenceTest {
    @Test
    fun fakeSourceDefaultHandlesEmptyDuplicatesAndTheTwentyFourSurfaceLimit() {
        val input = buildList {
            add("")
            add("家")
            add("家")
            repeat(30) { add("候補$it") }
        }

        val evidence = FakeSource().lexicalEvidenceForSurfaces(input)

        assertEquals(SurfaceLexicalEvidenceBatch.MAX_SURFACES, evidence.size)
        assertEquals("家", evidence.keys.first())
        assertFalse(evidence.containsKey(""))
        assertTrue(evidence.values.all { it.unknown && it.costAdjustment == 0f })
    }

    @Test
    fun unknownEvidenceIsNeutralAndShapeCostCombinationIsClamped() {
        val unknown = FakeSource().lexicalEvidenceForSurfaces(listOf("未知語")).getValue("未知語")

        assertEquals(0.27f, unknown.applyToShapeCost(0.27f), 0f)
        assertEquals(0f, unknown.applyToShapeCost(0f), 0f)
    }

    private class FakeSource : ReadingDataSource {
        override fun readingsFor(surface: String, limit: Int): List<String> = emptyList()

        override fun readingsForMany(
            surfaces: List<String>,
            limit: Int,
        ): Map<String, List<String>> = emptyMap()

        override fun suggest(prefix: String, limit: Int): List<WordReadingCandidate> = emptyList()

        override fun suggestByReading(
            prefix: String,
            limit: Int,
        ): List<WordReadingCandidate> = emptyList()

        override fun suggestForPrefixes(
            prefixes: List<String>,
            limitPerPrefix: Int,
        ): Map<String, List<WordReadingCandidate>> = emptyMap()

        override fun kanjiPriorities(
            literals: List<String>
        ): Map<String, KanjiUsagePriority> = emptyMap()

        override fun close() = Unit
    }
}
