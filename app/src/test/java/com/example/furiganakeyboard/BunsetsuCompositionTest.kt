package com.example.furiganakeyboard

import com.example.furiganakeyboard.conversion.ConversionSegment
import com.example.furiganakeyboard.conversion.ConversionResult
import com.example.furiganakeyboard.conversion.PosClass
import com.example.furiganakeyboard.conversion.ConversionText
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
    fun mostLikelyBoundaryIsPreferredOverShortestBoundary() {
        val reading = "ここではきもの"
        val natural = listOf(
            segment(0, 2, "ここ", PosClass.PRONOUN),
            segment(2, 4, "では", PosClass.PARTICLE),
            segment(4, 7, "きもの", PosClass.NOUN, surface = "着物"),
        )
        val shorter = listOf(
            segment(0, 3, "ここで", PosClass.PARTICLE),
            segment(3, 7, "はきもの", PosClass.NOUN, surface = "履き物"),
        )

        val plan = BunsetsuComposition.plan(
            reading,
            listOf(
                conversion(reading, "ここで履き物", shorter, cost = 500),
                conversion(reading, "ここでは着物", natural, cost = 100),
            ),
        )!!

        assertEquals("ここでは", BunsetsuComposition.create(reading, plan.initialSegments).activeReading)
        assertEquals(listOf("ここでは", "ここで"), plan.candidates.map { it.reading })
    }

    @Test
    fun equallyLikelyBoundariesDoNotDefaultToShortestFirst() {
        val reading = "ここではきもの"
        val longer = listOf(
            segment(0, 2, "ここ", PosClass.PRONOUN),
            segment(2, 4, "では", PosClass.PARTICLE),
            segment(4, 7, "きもの", PosClass.NOUN),
        )
        val shorter = listOf(
            segment(0, 3, "ここで", PosClass.PARTICLE),
            segment(3, 7, "はきもの", PosClass.NOUN),
        )

        val plan = BunsetsuComposition.plan(
            reading,
            listOf(
                conversion(reading, "ここではきもの", shorter),
                conversion(reading, "ここではきもの", longer),
            ),
        )!!

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

    @Test
    fun fullCommitKeepsOriginalPathAndPreviousRightPos() {
        val state = contextualState()

        state.commitActive("私も", PosClass.PARTICLE.id)
        assertEquals("わたしもなぞをよむ", state.originalReading)
        assertEquals("なぞを", state.activeReading)
        assertEquals(PosClass.PARTICLE.id, state.previousRightId)
        assertEquals("私", state.previousContextSurface)
        assertEquals(listOf("なぞ", "を", "よむ"), state.remainingSegmentCandidates.map { it.reading })

        state.commitActive("謎を", PosClass.PARTICLE.id)
        assertEquals("よむ", state.activeReading)
        assertEquals("謎", state.analysisToken().previousContextSurface)
        state.commitActive("読む", PosClass.VERB.id)

        assertEquals("", state.remainingReading)
        assertEquals("私も謎を読む", state.committedSegments.joinToString("") { it.surface })
    }

    @Test
    fun deletionKeepsCommittedContextAndDoesNotRestartComposition() {
        val state = contextualState()
        state.commitActive("私も", PosClass.PARTICLE.id)

        assertEquals("なぞをよ", state.deleteLastCodePoint())
        assertEquals("私も", state.committedSegments.single().surface)
        assertEquals(PosClass.PARTICLE.id, state.previousRightId)
        assertEquals("なぞを", state.activeReading)
    }

    @Test
    fun rangeChangeInvalidatesOldAnalysisAndCanInstallAForcedFullPath() {
        val state = contextualState()
        val stale = state.analysisToken()

        assertTrue(state.shrink())
        assertEquals("わたし", state.activeReading)
        assertFalse(state.isCurrent(stale))

        val current = state.analysisToken()
        val plan = BunsetsuComposition.plan(
            reading = state.remainingReading,
            conversions = listOf(conversion(state.remainingReading, "私も謎を読む", contextualSegments())),
            requestedBoundary = 3,
            allowSingle = true,
        )!!
        assertTrue(state.applyPlan(plan, current))
        assertEquals("わたし", state.activeReading)
        assertEquals(listOf("わたし", "も", "なぞを", "よむ"), state.composingSegments.map { it.reading })
    }

    @Test
    fun emptyUnknownRemainderKeepsNaturalBoundaryInsteadOfOneCharacter() {
        val state = contextualState()
        state.commitActive("私も", PosClass.PARTICLE.id)
        val token = state.analysisToken()

        val emptyPlan = BunsetsuComposition.plan(
            state.remainingReading,
            emptyList(),
            allowSingle = true,
        )

        assertEquals(null, emptyPlan)
        assertTrue(state.isCurrent(token))
        assertEquals("なぞを", state.activeReading)
        assertEquals("謎を", state.retainedOptions().first().surface)
        assertTrue(ConversionText.scalarCount(state.activeReading) > 1)
    }

    @Test
    fun rapidCandidateTapCannotReuseAnOldGeneration() {
        val state = contextualState()
        val oldGeneration = state.generation
        val oldToken = state.analysisToken()

        state.commitActive("私も", PosClass.PARTICLE.id)

        assertFalse(state.isCurrentCandidate("わたしも", oldGeneration))
        assertFalse(state.isCurrent(oldToken))
        assertEquals("なぞを", state.activeReading)
    }

    private fun contextualState(): BunsetsuComposition =
        BunsetsuComposition.create("わたしもなぞをよむ", contextualSegments())

    private fun contextualSegments(): List<ConversionSegment> = listOf(
        segment(0, 3, "わたし", PosClass.PRONOUN, "私"),
        segment(3, 4, "も", PosClass.PARTICLE),
        segment(4, 6, "なぞ", PosClass.NOUN, "謎"),
        segment(6, 7, "を", PosClass.PARTICLE),
        segment(7, 9, "よむ", PosClass.VERB, "読む"),
    )

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
        cost: Int = 0,
    ) = ConversionResult(surface, reading, cost, segments)
}
