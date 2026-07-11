package com.example.furiganakeyboard

import com.example.furiganakeyboard.conversion.ConversionSegment
import com.example.furiganakeyboard.conversion.ConversionResult
import com.example.furiganakeyboard.conversion.PosClass
import com.example.furiganakeyboard.ime.BunsetsuComposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BunsetsuCompositionTest {
    @Test
    fun conversionSegmentsProduceExpectedBunsetsuAndPartialCommitKeepsSuffix() {
        val state = BunsetsuComposition.create("わたしもそうしたい", expectedSegments())

        assertEquals(listOf("わたしも", "そうしたい"), state.composingSegments.map { it.reading })
        assertEquals("わたしも", state.activeReading)
        assertEquals(0, state.activeSegmentIndex)
        state.setCandidates(listOf("私も", "わたしも", "渡しも"))
        assertEquals(listOf("私も", "わたしも", "渡しも"), state.candidates)

        val first = state.commitActive("私も")

        assertEquals("私も", first.committedText)
        assertEquals("そうしたい", first.remainingText)
        assertEquals("私も", state.committedSegments.single().surface)
        assertEquals("そうしたい", state.activeReading)
        val second = state.commitActive("そうしたい")
        assertEquals("そうしたい", second.committedText)
        assertEquals("", second.remainingText)
        assertEquals("私もそうしたい", state.committedSegments.joinToString("") { it.surface })
    }

    @Test
    fun arrowsShrinkAndExpandTheLeadingConversionRange() {
        val state = BunsetsuComposition.create("わたしもそうしたい", expectedSegments())

        assertTrue(state.shrink())
        assertEquals("わたし", state.activeReading)
        assertEquals(listOf("わたし", "も", "そうしたい"), state.composingSegments.map { it.reading })
        assertTrue(state.expand())
        assertEquals("わたしも", state.activeReading)
        assertTrue(state.expand())
        assertEquals("わたしもそうしたい", state.activeReading)
        assertFalse(state.canExpand)
    }

    @Test
    fun backspaceEditsUncommittedSuffixWithoutTouchingCommittedSegments() {
        val state = BunsetsuComposition.create("わたしもそうしたい", expectedSegments())
        state.commitActive("私も")

        assertEquals("そうした", state.deleteLastCodePoint())
        assertEquals("私も", state.committedSegments.single().surface)
        assertEquals("そうした", state.composingText)
    }

    @Test
    fun particleFallbackIsUsedWhenConversionHasNoSegmentMetadata() {
        val state = BunsetsuComposition.create("わたしもそうしたい", emptyList())

        assertEquals(listOf("わたしも", "そうしたい"), state.composingSegments.map { it.reading })
    }

    @Test
    fun competingBunsetsuBoundariesProduceCandidatesForBothReadings() {
        val reading = "ここではきもの"
        val longFirst = listOf(
            segment(0, 2, "ここ", PosClass.PRONOUN),
            segment(2, 4, "では", PosClass.PARTICLE),
            segment(4, 7, "きもの", PosClass.NOUN, surface = "着物"),
        )
        val shortFirst = listOf(
            segment(0, 3, "ここで", PosClass.PARTICLE),
            segment(3, 7, "はきもの", PosClass.NOUN, surface = "履き物"),
        )

        val plan = BunsetsuComposition.plan(
            reading,
            listOf(
                conversion(reading, "ここでは着物", longFirst),
                conversion(reading, "ここで履き物", shortFirst),
            ),
        )!!

        assertEquals(listOf("ここでは", "ここで"), plan.candidates.map { it.surface })
        assertEquals(listOf("ここでは", "ここで"), plan.candidates.map { it.reading })
    }

    @Test
    fun selectingCompetingBoundaryCommitsOnlyItsReadingPrefix() {
        val state = BunsetsuComposition.create("ここではきもの", emptyList())

        assertTrue(state.selectActiveReading("ここで"))
        val committed = state.commitActive("ここで")

        assertEquals("ここで", committed.committedText)
        assertEquals("はきもの", committed.remainingText)
    }

    private fun expectedSegments(): List<ConversionSegment> = listOf(
        segment(0, 3, "わたし", PosClass.PRONOUN),
        segment(3, 4, "も", PosClass.PARTICLE),
        segment(4, 6, "そう", PosClass.ADVERB),
        segment(6, 7, "し", PosClass.VERB),
        segment(7, 9, "たい", PosClass.AUXILIARY),
    )

    private fun segment(
        start: Int,
        end: Int,
        reading: String,
        pos: PosClass,
        surface: String = reading,
    ) = ConversionSegment(
        start = start,
        end = end,
        reading = reading,
        surface = surface,
        leftId = pos.id,
        rightId = pos.id,
        wordCost = 0,
        isCopy = false,
    )

    private fun conversion(
        reading: String,
        surface: String,
        segments: List<ConversionSegment>,
    ) = ConversionResult(surface, reading, 0, segments)
}
