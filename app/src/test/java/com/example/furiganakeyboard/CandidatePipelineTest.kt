package com.example.furiganakeyboard

import com.example.furiganakeyboard.conversion.ConversionConnection
import com.example.furiganakeyboard.conversion.ConversionLexeme
import com.example.furiganakeyboard.conversion.PosClass
import com.example.furiganakeyboard.ime.CandidatePipeline
import com.example.furiganakeyboard.ime.HandwritingStageContext
import com.example.furiganakeyboard.ime.HandwritingPipelineResult
import com.example.furiganakeyboard.ime.ResolvedCharacterCandidate
import com.example.furiganakeyboard.ime.WholeCompositionCandidatePolicy
import com.example.furiganakeyboard.reading.KanjiUsagePriority
import com.example.furiganakeyboard.reading.ReadingDataSource
import com.example.furiganakeyboard.reading.SurfaceLexicalEvidence
import com.example.furiganakeyboard.reading.WordReadingCandidate
import com.example.furiganakeyboard.recognizer.RecognitionCandidate
import com.example.furiganakeyboard.recognizer.RecognitionComponentEvidence
import com.example.furiganakeyboard.recognizer.RecognitionEvidence
import com.example.furiganakeyboard.recognizer.RecognitionSource
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
    fun romajiAnalysisOverfetchesConversionsAndPredictionsButHonorsRequestedLimit() {
        val source = FakeSource().apply {
            conversionLexemeLoader = { overfetchLexemes() }
            connections = singleNounConnections()
            surfaceSuggestion = {
                (0 until 24).map { index ->
                    WordReadingCandidate("予測$index", listOf("かなよそく$index"))
                }
            }
        }
        val pipeline = pipeline(source)
        val overFetched = CountDownLatch(1)
        val displayLimited = CountDownLatch(1)

        pipeline.submitRomajiAnalysis(
            "かな",
            CandidatePipeline.MAX_ROMAJI_ANALYSIS_CANDIDATES,
        ) { result ->
            assertEquals(24, result.candidates.size)
            assertTrue(result.conversions.isNotEmpty())
            assertTrue(result.candidates.any { it.surface.startsWith("変換") })
            assertTrue(result.candidates.drop(8).any { it.surface.startsWith("予測") })
            overFetched.countDown()
        }
        assertTrue(overFetched.await(2, TimeUnit.SECONDS))

        pipeline.submitRomajiAnalysis("かな", 8) { result ->
            assertEquals(8, result.candidates.size)
            displayLimited.countDown()
        }

        assertTrue(displayLimited.await(2, TimeUnit.SECONDS))
        assertEquals(1, source.conversionLexemeCalls.get())
        assertEquals(1, source.readingSuggestionCalls.get())
        pipeline.close()
    }

    @Test
    fun overFetchedPoolCanFillDisplayLimitAfterPrefixOnlyEntriesAreRemoved() {
        val source = FakeSource().apply {
            surfaceSuggestion = {
                (0 until 12).map { index ->
                    WordReadingCandidate("前方$index", listOf("か"))
                } + (0 until 12).map { index ->
                    WordReadingCandidate("正解$index", listOf("かな"))
                }
            }
        }
        val pipeline = pipeline(source)
        val delivered = CountDownLatch(1)

        pipeline.submitRomajiAnalysis(
            "かな",
            CandidatePipeline.MAX_ROMAJI_ANALYSIS_CANDIDATES,
        ) { result ->
            assertEquals(24, result.candidates.size)
            val laterEligible = result.candidates.drop(8).first { candidate ->
                candidate.readings.any { it == "かな" }
            }
            val displayed = WholeCompositionCandidatePolicy.build(
                reading = "かな",
                dictionaryCandidates = result.candidates,
                scriptFallbacks = emptyList(),
                limit = 8,
            )

            assertEquals(8, displayed.size)
            assertTrue(displayed.none { it.surface.startsWith("前方") })
            assertTrue(displayed.any { it.surface == laterEligible.surface })
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

    @Test
    fun lexicalEvidencePromotesNihonAcrossOnlyANearShapeDifference() {
        val submittingThread = Thread.currentThread().name
        val source = FakeSource().apply {
            readings = mapOf("日本" to listOf("にほん"))
            lexicalEvidenceLoader = { surfaces ->
                lexicalThreadName = Thread.currentThread().name
                surfaces.associateWith { surface ->
                    lexicalEvidence(surface, exact = surface == "日本")
                }
            }
        }
        val pipeline = pipeline(source)
        val delivered = CountDownLatch(1)

        pipeline.submitHandwriting(
            candidates = listOf(
                recognized("木", shapeCost = 0.04f, rawRank = 0),
                recognized("本", shapeCost = 0.08f, rawRank = 1),
            ),
            stageContext = japaneseStageContext(),
            limit = 8,
        ) { result ->
            val staged = result as HandwritingPipelineResult.Staged
            assertEquals(listOf("日本", "日木"), staged.candidates.map { it.surface })
            assertEquals("日本", staged.topSurface)
            assertEquals(0f, staged.candidates.first().shapeCost, 0f)
            assertEquals(0.08f, staged.candidates.first().originalShapeCost, 0f)
            assertTrue(staged.candidates.single { it.surface == "日木" }.isRecognizerRawTop)
            delivered.countDown()
        }

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(1, source.lexicalBatchCalls.get())
        assertEquals(listOf("日木", "日本"), source.lexicalBatchInputs.single())
        assertTrue(source.lexicalThreadName != submittingThread)
        pipeline.close()
    }

    @Test
    fun boundedLexicalAdjustmentDoesNotCrossAClearShapeGap() {
        val source = FakeSource().apply {
            readings = mapOf("日本" to listOf("にほん"))
            lexicalEvidenceLoader = { surfaces ->
                surfaces.associateWith { lexicalEvidence(it, exact = it == "日本") }
            }
        }
        val pipeline = pipeline(source)
        val delivered = CountDownLatch(1)

        pipeline.submitHandwriting(
            candidates = listOf(
                recognized("木", shapeCost = 0f, rawRank = 0),
                recognized("本", shapeCost = 0.2f, rawRank = 1),
            ),
            stageContext = japaneseStageContext(),
            limit = 8,
        ) { result ->
            val candidates = (result as HandwritingPipelineResult.Staged).candidates
            assertEquals(listOf("日木", "日本"), candidates.map { it.surface })
            assertEquals(0.12f, candidates.last().shapeCost, 0.00001f)
            delivered.countDown()
        }

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        pipeline.close()
    }

    @Test
    fun sideBySideRecognizerRawTopRemainsInNBestWhenLexicalCandidateWins() {
        val source = FakeSource().apply {
            readings = mapOf("日本" to listOf("にほん"))
            lexicalEvidenceLoader = { surfaces ->
                surfaces.associateWith { lexicalEvidence(it, exact = it == "日本") }
            }
        }
        val pipeline = pipeline(source)
        val delivered = CountDownLatch(1)
        val sideBySideRawTop = recognized(
            text = "木",
            shapeCost = 0.04f,
            rawRank = null,
            source = RecognitionSource.SIDE_BY_SIDE,
            components = listOf(
                component(0, "日", isRawTop = true),
                component(1, "木", isRawTop = true),
            ),
        )

        pipeline.submitHandwriting(
            candidates = listOf(
                sideBySideRawTop,
                recognized("本", shapeCost = 0.08f, rawRank = 1),
            ),
            stageContext = japaneseStageContext(),
            limit = 1,
        ) { result ->
            val candidate = (result as HandwritingPipelineResult.Staged).candidates.single()
            assertEquals("日木", candidate.surface)
            assertTrue(candidate.isRecognizerRawTop)
            delivered.countDown()
        }

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        pipeline.close()
    }

    @Test
    fun finalCandidatesExposeUnknownProperNameAndPlaceNameEvidence() {
        val source = FakeSource().apply {
            lexicalEvidenceLoader = { surfaces ->
                surfaces.associateWith { surface ->
                    when (surface) {
                        "佐藤" -> lexicalEvidence(surface, exact = true, properName = true)
                        "茨城" -> lexicalEvidence(
                            surface,
                            exact = true,
                            properName = true,
                            placeName = true,
                        )
                        else -> lexicalEvidence(surface, exact = false)
                    }
                }
            }
        }
        val pipeline = pipeline(source)
        val delivered = CountDownLatch(1)

        pipeline.submitHandwriting(
            candidates = listOf(
                recognized("未知語", 0f, 0),
                recognized("佐藤", 0.1f, 1),
                recognized("茨城", 0.2f, 2),
            ),
            stageContext = null,
            limit = 8,
        ) { result ->
            val candidates = (result as HandwritingPipelineResult.Characters).candidates
            assertTrue(candidates.single { it.text == "未知語" }.lexicalEvidence!!.unknown)
            assertTrue(candidates.single { it.text == "佐藤" }.lexicalEvidence!!.properName)
            assertTrue(candidates.single { it.text == "茨城" }.lexicalEvidence!!.placeName)
            delivered.countDown()
        }

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(1, source.lexicalBatchCalls.get())
        pipeline.close()
    }

    @Test
    fun previousCharacterShapeEvidenceReachesTheFinalWordRanking() {
        val source = FakeSource()
        val pipeline = pipeline(source)
        val delivered = CountDownLatch(1)
        val context = HandwritingStageContext(
            baseBeforeCurrent = "日",
            wordRootBeforeLast = "",
            previousAlternatives = listOf("日", "目"),
            previousCandidates = listOf(
                resolvedCharacter("日", 0.12f),
                resolvedCharacter("目", 0.02f),
            ),
        )

        pipeline.submitHandwriting(
            candidates = listOf(recognized("本", 0.08f, 0)),
            stageContext = context,
            limit = 8,
        ) { result ->
            val candidates = (result as HandwritingPipelineResult.Staged).candidates
            assertEquals(listOf("目本", "日本"), candidates.map { it.surface })
            assertEquals(0.05f, candidates.first().originalShapeCost, 0.00001f)
            assertEquals(0.1f, candidates.last().originalShapeCost, 0.00001f)
            delivered.countDown()
        }

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        pipeline.close()
    }

    @Test
    fun stagedCurrentCandidatesCarryShapeEvidenceIntoTheNextStageAndRetainRawTop() {
        val commonAlternatives = listOf("甲", "乙", "丙", "丁", "戊", "己")
        val source = FakeSource().apply {
            priorities = commonAlternatives.associateWith { KanjiUsagePriority(1, 1) }
        }
        val pipeline = pipeline(source)
        val firstDelivered = CountDownLatch(1)
        val secondDelivered = CountDownLatch(1)
        var firstStage: HandwritingPipelineResult.Staged? = null
        val firstCandidates = buildList {
            add(recognized("木", shapeCost = 0f, rawRank = 0))
            commonAlternatives.forEachIndexed { index, text ->
                add(recognized(text, shapeCost = (index + 1) / 100f, rawRank = index + 1))
            }
        }

        pipeline.submitHandwriting(
            candidates = firstCandidates,
            stageContext = japaneseStageContext(),
            limit = 8,
        ) { result ->
            firstStage = result as HandwritingPipelineResult.Staged
            firstDelivered.countDown()
        }
        assertTrue(firstDelivered.await(2, TimeUnit.SECONDS))

        val first = checkNotNull(firstStage)
        assertEquals(first.currentAlternatives, first.currentCandidates.map { it.text })
        val rawTop = first.currentCandidates.single { it.isRecognizerRawTop }
        assertEquals("木", rawTop.text)
        assertEquals(0f, rawTop.originalShapeCost, 0f)
        assertEquals(0.06f, rawTop.rankingShapeCost, 0.00001f)
        assertEquals(1, source.lexicalBatchCalls.get())

        pipeline.submitHandwriting(
            candidates = listOf(recognized("曜", shapeCost = 0f, rawRank = 0)),
            stageContext = HandwritingStageContext(
                baseBeforeCurrent = first.topSurface,
                wordRootBeforeLast = "日",
                previousAlternatives = first.currentAlternatives,
                previousCandidates = first.currentCandidates,
            ),
            limit = 1,
        ) { result ->
            val candidate = (result as HandwritingPipelineResult.Staged).candidates.single()
            assertEquals("日木曜", candidate.surface)
            assertTrue(candidate.isRecognizerRawTop)
            secondDelivered.countDown()
        }

        assertTrue(secondDelivered.await(2, TimeUnit.SECONDS))
        assertEquals(2, source.lexicalBatchCalls.get())
        pipeline.close()
    }

    @Test
    fun handwritingLexicalBatchDeduplicatesCapsAndHonorsResultLimit() {
        val source = FakeSource()
        val pipeline = pipeline(source)
        val delivered = CountDownLatch(1)
        val candidates = buildList {
            add(recognized("候補0", 0f, 0))
            add(recognized("候補0", 0.01f, 1))
            repeat(30) { index ->
                add(recognized("候補${index + 1}", (index + 2) / 100f, index + 2))
            }
        }

        pipeline.submitHandwriting(candidates, null, 5) { result ->
            val resolved = (result as HandwritingPipelineResult.Characters).candidates
            assertEquals(5, resolved.size)
            assertEquals(resolved.size, resolved.map { it.text }.distinct().size)
            delivered.countDown()
        }

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(1, source.lexicalBatchCalls.get())
        assertEquals(24, source.lexicalBatchInputs.single().size)
        assertEquals(24, source.lexicalBatchInputs.single().distinct().size)
        pipeline.close()
    }

    @Test
    fun lexicalFailureFallsBackToStableShapeOrder() {
        val source = FakeSource().apply {
            lexicalEvidenceLoader = { error("lexical database unavailable") }
        }
        val pipeline = pipeline(source)
        val delivered = CountDownLatch(1)

        pipeline.submitHandwriting(
            listOf(
                recognized("日", 0f, 0),
                recognized("目", 0.1f, 1),
                recognized("曰", 0.1f, 2),
            ),
            null,
            8,
        ) { result ->
            assertEquals(
                listOf("日", "目", "曰"),
                (result as HandwritingPipelineResult.Characters).candidates.map { it.text },
            )
            delivered.countDown()
        }

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(1, source.lexicalBatchCalls.get())
        pipeline.close()
    }

    @Test
    fun staleHandwritingCallbackIsSuppressedWhileLexicalBatchIsInFlight() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val latestDelivered = CountDownLatch(1)
        val staleCallbacks = AtomicInteger()
        val source = FakeSource().apply {
            lexicalEvidenceLoader = { surfaces ->
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
                surfaces.associateWith { lexicalEvidence(it, exact = false) }
            }
        }
        val pipeline = pipeline(source)

        pipeline.submitHandwriting(
            listOf(recognized("日", 0f, 0)),
            null,
            8,
        ) { staleCallbacks.incrementAndGet() }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        pipeline.invalidate()
        release.countDown()
        pipeline.submitSurface("latest", 1) { latestDelivered.countDown() }

        assertTrue(latestDelivered.await(2, TimeUnit.SECONDS))
        assertEquals(0, staleCallbacks.get())
        assertEquals(1, source.lexicalBatchCalls.get())
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
        var lexicalThreadName: String? = null
        var lexicalEvidenceLoader: (List<String>) -> Map<String, SurfaceLexicalEvidence> = {
            surfaces -> surfaces.associateWith { lexicalEvidence(it, exact = false) }
        }
        val readingBatchCalls = AtomicInteger()
        val priorityBatchCalls = AtomicInteger()
        val conversionLexemeCalls = AtomicInteger()
        val connectionCalls = AtomicInteger()
        val readingSuggestionCalls = AtomicInteger()
        val lexicalBatchCalls = AtomicInteger()
        val lexicalBatchInputs = Collections.synchronizedList(mutableListOf<List<String>>())

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

        override fun lexicalEvidenceForSurfaces(
            surfaces: List<String>
        ): Map<String, SurfaceLexicalEvidence> {
            lexicalBatchCalls.incrementAndGet()
            lexicalBatchInputs += surfaces.toList()
            lexicalThreadName = Thread.currentThread().name
            return lexicalEvidenceLoader(surfaces)
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
        private fun japaneseStageContext() = HandwritingStageContext(
            baseBeforeCurrent = "日",
            wordRootBeforeLast = "",
            previousAlternatives = listOf("日"),
        )

        private fun recognized(
            text: String,
            shapeCost: Float,
            rawRank: Int?,
            source: RecognitionSource = RecognitionSource.ZINNIA,
            components: List<RecognitionComponentEvidence> = emptyList(),
        ) = RecognitionCandidate(
            text = text,
            shapeCost = shapeCost,
            evidence = RecognitionEvidence(
                source = source,
                rawScore = null,
                rawRank = rawRank,
                components = components,
            ),
        )

        private fun component(
            position: Int,
            text: String,
            isRawTop: Boolean,
        ) = RecognitionComponentEvidence(
            position = position,
            text = text,
            shapeCost = 0f,
            source = RecognitionSource.ZINNIA,
            rawScore = null,
            rawRank = if (isRawTop) 0 else 1,
            isRecognizerRawTop = isRawTop,
        )

        private fun lexicalEvidence(
            surface: String,
            exact: Boolean,
            properName: Boolean = false,
            placeName: Boolean = false,
        ) = SurfaceLexicalEvidence(
            surface = surface,
            exact = exact,
            properName = properName,
            placeName = placeName,
            costAdjustment = when {
                properName || placeName -> -0.04f
                exact -> -0.08f
                else -> 0f
            },
        )

        private fun resolvedCharacter(
            text: String,
            shapeCost: Float,
        ) = ResolvedCharacterCandidate(
            text = text,
            readings = emptyList(),
            originalShapeCost = shapeCost,
            rankingShapeCost = shapeCost,
            shapeCost = shapeCost,
            isRecognizerRawTop = false,
            lexicalEvidence = null,
        )

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

        private fun overfetchLexemes() = (0 until 12).map { index ->
            ConversionLexeme(
                start = 0,
                end = 2,
                reading = "かな",
                surface = "変換$index",
                leftId = PosClass.NOUN.id,
                rightId = PosClass.NOUN.id,
                wordCost = index,
            )
        }

        private fun singleNounConnections() = listOf(
            ConversionConnection(PosClass.BOS.id, PosClass.NOUN.id, 0),
            ConversionConnection(PosClass.NOUN.id, PosClass.EOS.id, 0),
        )
    }
}
