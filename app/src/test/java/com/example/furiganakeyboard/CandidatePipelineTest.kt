package com.example.furiganakeyboard

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
        val readingBatchCalls = AtomicInteger()
        val priorityBatchCalls = AtomicInteger()

        override fun readingsFor(surface: String, limit: Int): List<String> =
            readings[surface].orEmpty().take(limit)

        override fun readingsForMany(surfaces: List<String>, limit: Int): Map<String, List<String>> {
            readingBatchCalls.incrementAndGet()
            return surfaces.associateWith { readings[it].orEmpty().take(limit) }
        }

        override fun suggest(prefix: String, limit: Int): List<WordReadingCandidate> =
            surfaceSuggestion(prefix).take(limit)

        override fun suggestByReading(prefix: String, limit: Int): List<WordReadingCandidate> =
            surfaceSuggestion(prefix).take(limit)

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

        override fun close() = Unit
    }
}
