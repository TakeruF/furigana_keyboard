package com.example.furiganakeyboard

import com.example.furiganakeyboard.conversion.ConversionConnection
import com.example.furiganakeyboard.conversion.ConversionLexeme
import com.example.furiganakeyboard.conversion.PosClass
import com.example.furiganakeyboard.ime.CandidatePipeline
import com.example.furiganakeyboard.ime.HandwritingStageContext
import com.example.furiganakeyboard.ime.HandwritingPipelineResult
import com.example.furiganakeyboard.reading.KanjiUsagePriority
import com.example.furiganakeyboard.reading.ReadingDataSource
import com.example.furiganakeyboard.reading.WordReadingCandidate
import com.example.furiganakeyboard.recognizer.RecognitionCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CandidatePipelineTest {
    @Test
    fun sentenceConversionPrecedesLegacyReadingSuggestionsAndIsCached() {
        val submittingThread = Thread.currentThread().name
        val source = FakeSource().apply {
            conversionLexemeLoader = {
                conversionThreadName = Thread.currentThread().name
                requiredPhraseLexemes()
            }
            connections = requiredPhraseConnections()
            surfaceSuggestion = {
                listOf(WordReadingCandidate("これもホンダ", listOf("これもほんだ")))
            }
        }
        val pipeline = pipeline(source)
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val delivered = mutableListOf<List<WordReadingCandidate>>()

        pipeline.submitRomaji("これもほんだ", 6) {
            delivered += it
            first.countDown()
        }
        assertTrue(first.await(2, TimeUnit.SECONDS))
        pipeline.submitRomaji("これもほんだ", 6) {
            delivered += it
            second.countDown()
        }
        assertTrue(second.await(2, TimeUnit.SECONDS))

        assertEquals("これも本だ", delivered.first().first().surface)
        assertEquals(delivered.first(), delivered.last())
        assertEquals(1, source.conversionLexemeCalls.get())
        assertEquals(1, source.connectionCalls.get())
        assertTrue(source.conversionThreadName != submittingThread)
        pipeline.close()
    }

    @Test
    fun romajiAnalysisPreservesTopConversionSegmentsForBunsetsuSelection() {
        val source = FakeSource().apply {
            conversionLexemeLoader = { requiredPhraseLexemes() }
            connections = requiredPhraseConnections()
        }
        val pipeline = pipeline(source)
        val delivered = CountDownLatch(1)

        pipeline.submitRomajiAnalysis("これもほんだ", 6) { result ->
            assertEquals("これも本だ", result.candidates.first().surface)
            assertEquals(listOf("これ", "も", "ほん", "だ"), result.segments.map { it.reading })
            delivered.countDown()
        }

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        pipeline.close()
    }

    @Test
    fun romajiAnalysisPassesCommittedRightPosAndCachesItSeparatelyFromBos() {
        val source = FakeSource().apply {
            conversionLexemeLoader = {
                listOf(
                    ConversionLexeme(0, 2, "はし", "橋", 3, 3, 100),
                    ConversionLexeme(0, 2, "はし", "箸", 4, 4, 0),
                )
            }
            connections = listOf(
                ConversionConnection(0, 3, 1_000),
                ConversionConnection(0, 4, 0),
                ConversionConnection(5, 3, 0),
                ConversionConnection(5, 4, 1_000),
                ConversionConnection(3, 1, 0),
                ConversionConnection(4, 1, 0),
            )
        }
        val pipeline = pipeline(source)
        val bosDelivered = CountDownLatch(1)
        val contextualDelivered = CountDownLatch(1)

        pipeline.submitRomajiAnalysis("はし", 6) { result ->
            assertEquals("箸", result.conversions.first().surface)
            bosDelivered.countDown()
        }
        assertTrue(bosDelivered.await(2, TimeUnit.SECONDS))
        pipeline.submitRomajiAnalysis(
            kana = "はし",
            limit = 6,
            initialRightId = PosClass.PARTICLE.id,
        ) { result ->
            assertEquals("橋", result.conversions.first().surface)
            contextualDelivered.countDown()
        }

        assertTrue(contextualDelivered.await(2, TimeUnit.SECONDS))
        assertEquals(2, source.conversionLexemeCalls.get())
        pipeline.close()
    }

    @Test
    fun invalidationAfterDeleteSuppressesInFlightRomajiResult() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val latestDelivered = CountDownLatch(1)
        val callbackCalls = AtomicInteger()
        val source = FakeSource().apply {
            conversionLexemeLoader = {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
                requiredPhraseLexemes()
            }
            connections = requiredPhraseConnections()
        }
        val pipeline = pipeline(source)

        pipeline.submitRomaji("これもほんだ", 6) { callbackCalls.incrementAndGet() }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        pipeline.invalidate()
        release.countDown()
        pipeline.submitSurface("latest", 1) { latestDelivered.countDown() }

        assertTrue(latestDelivered.await(2, TimeUnit.SECONDS))
        assertEquals(0, callbackCalls.get())
        pipeline.close()
    }

    @Test
    fun conversionFailureReturnsEmptySoScriptFallbackCanRemainVisible() {
        val delivered = CountDownLatch(1)
        val source = FakeSource().apply {
            conversionLexemeLoader = { error("database unavailable") }
        }
        val pipeline = pipeline(source)

        pipeline.submitRomaji("ほん", 6) {
            assertTrue(it.isEmpty())
            delivered.countDown()
        }

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        pipeline.close()
    }

    @Test
    fun overLimitAndUnfinishedInputNeverQueryTheDatabase() {
        val source = FakeSource()
        val pipeline = pipeline(source)
        val overLimit = CountDownLatch(1)
        val unfinished = CountDownLatch(1)

        pipeline.submitRomaji("あ".repeat(49), 6) { overLimit.countDown() }
        assertTrue(overLimit.await(2, TimeUnit.SECONDS))
        pipeline.submitRomaji("ほんd", 6) { unfinished.countDown() }
        assertTrue(unfinished.await(2, TimeUnit.SECONDS))

        assertEquals(0, source.conversionLexemeCalls.get())
        assertEquals(0, source.readingSuggestionCalls.get())
        pipeline.close()
    }

    @Test
    fun onlyLatestRequestCanUpdateTheUi() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val callbacks = Collections.synchronizedList(mutableListOf<String>())
        val source = FakeSource().apply {
            surfaceSuggestion = { prefix ->
                if (prefix == "first") {
                    firstStarted.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
                listOf(WordReadingCandidate(prefix, listOf("よみ")))
            }
        }
        val pipeline = pipeline(source)

        pipeline.submitSurface("first", 8) { callbacks += it.single().surface }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        pipeline.submitSurface("latest", 8) {
            callbacks += it.single().surface
            delivered.countDown()
        }
        releaseFirst.countDown()

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("latest"), callbacks)
        pipeline.close()
    }

    @Test
    fun repeatedHandwritingCandidatesUseWorkerCaches() {
        val source = FakeSource().apply {
            readings = mapOf("日" to listOf("ニチ"), "目" to listOf("モク"))
            priorities = mapOf("日" to KanjiUsagePriority(1, 1))
        }
        val pipeline = pipeline(source)
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val values = listOf(
            RecognitionCandidate("目", 10f),
            RecognitionCandidate("日", 9.9f)
        )

        pipeline.submitHandwriting(values, null, 8) { result ->
            assertEquals("日", (result as HandwritingPipelineResult.Characters).candidates.first().text)
            first.countDown()
        }
        assertTrue(first.await(2, TimeUnit.SECONDS))
        pipeline.submitHandwriting(values, null, 8) { second.countDown() }
        assertTrue(second.await(2, TimeUnit.SECONDS))

        assertEquals(1, source.readingBatchCalls.get())
        assertEquals(1, source.priorityBatchCalls.get())
        pipeline.close()
    }

    @Test
    fun knownWordAndCharacterProduceACompoundCandidate() {
        val source = FakeSource().apply {
            readings = mapOf(
                "国際" to listOf("こくさい"),
                "版" to listOf("ハン")
            )
        }
        val pipeline = pipeline(source)
        val delivered = CountDownLatch(1)

        pipeline.submitHandwriting(
            candidates = listOf(RecognitionCandidate("版", 10f)),
            stageContext = HandwritingStageContext(
                baseBeforeCurrent = "国際",
                wordRootBeforeLast = "国",
                previousAlternatives = listOf("際")
            ),
            limit = 8
        ) { result ->
            val candidates = (result as HandwritingPipelineResult.Staged).candidates
            assertEquals("国際版", candidates.first().surface)
            assertEquals(listOf("こくさいはん"), candidates.first().readings)
            delivered.countDown()
        }

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        pipeline.close()
    }

    private fun pipeline(source: ReadingDataSource): CandidatePipeline = CandidatePipeline(
        dataSourceFactory = { source },
        worker = Executors.newSingleThreadExecutor(),
        postToMain = { it() }
    )

    private class FakeSource : ReadingDataSource {
        var readings: Map<String, List<String>> = emptyMap()
        var priorities: Map<String, KanjiUsagePriority> = emptyMap()
        var surfaceSuggestion: (String) -> List<WordReadingCandidate> = { emptyList() }
        var conversionLexemeLoader: (String) -> List<ConversionLexeme> = { emptyList() }
        var connections: List<ConversionConnection> = emptyList()
        var conversionThreadName: String? = null
        val readingBatchCalls = AtomicInteger()
        val priorityBatchCalls = AtomicInteger()
        val conversionLexemeCalls = AtomicInteger()
        val connectionCalls = AtomicInteger()
        val readingSuggestionCalls = AtomicInteger()

        override fun readingsFor(surface: String, limit: Int): List<String> =
            readings[surface].orEmpty().take(limit)

        override fun readingsForMany(surfaces: List<String>, limit: Int): Map<String, List<String>> {
            readingBatchCalls.incrementAndGet()
            return surfaces.associateWith { readings[it].orEmpty().take(limit) }
        }

        override fun suggest(prefix: String, limit: Int): List<WordReadingCandidate> =
            surfaceSuggestion(prefix).take(limit)

        override fun suggestByReading(prefix: String, limit: Int): List<WordReadingCandidate> {
            readingSuggestionCalls.incrementAndGet()
            return surfaceSuggestion(prefix).take(limit)
        }

        override fun suggestForPrefixes(
            prefixes: List<String>,
            limitPerPrefix: Int
        ): Map<String, List<WordReadingCandidate>> = prefixes.associateWith {
            surfaceSuggestion(it).take(limitPerPrefix)
        }

        override fun kanjiPriorities(literals: List<String>): Map<String, KanjiUsagePriority> {
            priorityBatchCalls.incrementAndGet()
            return priorities.filterKeys(literals::contains)
        }

        override fun conversionLexemes(
            reading: String,
            maxTokenCodePoints: Int,
            limitPerReading: Int
        ): List<ConversionLexeme> {
            conversionLexemeCalls.incrementAndGet()
            return conversionLexemeLoader(reading)
        }

        override fun conversionConnections(): List<ConversionConnection> {
            connectionCalls.incrementAndGet()
            return connections
        }

        override fun close() = Unit
    }

    companion object {
        private fun requiredPhraseLexemes() = listOf(
            ConversionLexeme(0, 2, "これ", "これ", 2, 2, 100),
            ConversionLexeme(2, 3, "も", "も", 5, 5, 100),
            ConversionLexeme(3, 5, "ほん", "本", 3, 3, 100),
            ConversionLexeme(5, 6, "だ", "だ", 6, 6, 100)
        )

        private fun requiredPhraseConnections() = listOf(
            ConversionConnection(0, 2, 0),
            ConversionConnection(2, 5, 0),
            ConversionConnection(5, 3, 0),
            ConversionConnection(3, 6, 0),
            ConversionConnection(6, 1, 0)
        )
    }
}
