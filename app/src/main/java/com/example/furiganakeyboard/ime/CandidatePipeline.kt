package com.example.furiganakeyboard.ime

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.furiganakeyboard.conversion.KanaKanjiConverter
import com.example.furiganakeyboard.conversion.ConversionSegment
import com.example.furiganakeyboard.conversion.ConversionResult
import com.example.furiganakeyboard.conversion.PosClass
import com.example.furiganakeyboard.reading.KanjiUsagePriority
import com.example.furiganakeyboard.reading.ReadingDataSource
import com.example.furiganakeyboard.reading.SurfaceLexicalEvidence
import com.example.furiganakeyboard.reading.WordReadingCandidate
import com.example.furiganakeyboard.recognizer.KanjiCandidateRanker
import com.example.furiganakeyboard.recognizer.RecognitionCandidate
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Immutable character candidate with shape and lexical evidence retained. */
data class ResolvedCharacterCandidate(
    val text: String,
    val readings: List<String>,
    val originalShapeCost: Float,
    val rankingShapeCost: Float,
    val shapeCost: Float,
    val isRecognizerRawTop: Boolean,
    val lexicalEvidence: SurfaceLexicalEvidence?,
)

/** Final handwriting word candidate; lower [shapeCost] is better. */
data class ResolvedWordCandidate(
    val surface: String,
    val readings: List<String>,
    val originalShapeCost: Float,
    val rankingShapeCost: Float,
    val shapeCost: Float,
    val isRecognizerRawTop: Boolean,
    val lexicalEvidence: SurfaceLexicalEvidence?,
)

data class HandwritingStageContext(
    val baseBeforeCurrent: String,
    val wordRootBeforeLast: String,
    val previousAlternatives: List<String>,
    val previousCandidates: List<ResolvedCharacterCandidate> = emptyList(),
)

data class RomajiConversionResult(
    val candidates: List<WordReadingCandidate>,
    val conversions: List<ConversionResult>,
) {
    val segments: List<ConversionSegment>
        get() = conversions.firstOrNull()?.segments.orEmpty()
}

private data class ConversionRequest(
    val kana: String,
    val initialRightId: Int,
    val initialContextSurface: String?,
    val requiredBoundary: Int?,
)

private data class RankedRecognitionCandidate(
    val candidate: RecognitionCandidate,
    val rankingShapeCost: Float,
)

sealed interface HandwritingPipelineResult {
    data class Characters(val candidates: List<ResolvedCharacterCandidate>) :
        HandwritingPipelineResult

    /**
     * [currentCandidates] is the lossless handoff for the next
     * [HandwritingStageContext.previousCandidates]. [currentAlternatives] remains the compatible
     * text-only view and has the same ordering.
     */
    data class Staged(
        val topSurface: String,
        val currentAlternatives: List<String>,
        val currentCandidates: List<ResolvedCharacterCandidate>,
        val candidates: List<ResolvedWordCandidate>,
    ) : HandwritingPipelineResult
}

/**
 * Serializes all immutable-dictionary work away from the IME main thread.
 * Every submission supersedes older work; completed stale results are discarded.
 */
class CandidatePipeline(
    private val dataSourceFactory: () -> ReadingDataSource,
    private val worker: ExecutorService = defaultWorker(),
    private val postToMain: ((() -> Unit) -> Unit) = defaultMainPoster()
) : AutoCloseable {
    private enum class SuggestionKind { SURFACE, READING }
    private data class SuggestionKey(
        val kind: SuggestionKind,
        val prefix: String,
        val limit: Int
    )

    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private var source: ReadingDataSource? = null

    // These maps are worker-confined. Empty results are cached as well.
    private val readingCache = LruMap<String, List<String>>(READING_CACHE_SIZE)
    private val suggestionCache =
        LruMap<SuggestionKey, List<WordReadingCandidate>>(SUGGESTION_CACHE_SIZE)
    private val conversionCache =
        LruMap<ConversionRequest, RomajiConversionResult>(CONVERSION_CACHE_SIZE)
    private val priorityCache =
        LruMap<String, KanjiUsagePriority?>(PRIORITY_CACHE_SIZE)

    /** Start asset installation/database opening without delaying the first key event. */
    fun prewarm() {
        if (closed.get()) return
        worker.execute { runCatching { dataSource() }.onFailure(::logFailure) }
    }

    fun submitSurface(prefix: String, limit: Int, callback: (List<WordReadingCandidate>) -> Unit) {
        submit(callback) { suggestions(SuggestionKind.SURFACE, prefix, limit) }
    }

    fun submitRomaji(kana: String, limit: Int, callback: (List<WordReadingCandidate>) -> Unit) {
        submit(callback, failureValue = { emptyList() }) { isCancelled ->
            conversionResult(kana, isCancelled).candidates.take(limit.coerceAtLeast(0))
        }
    }

    fun submitRomajiAnalysis(
        kana: String,
        limit: Int,
        initialRightId: Int = PosClass.BOS.id,
        initialContextSurface: String? = null,
        requiredBoundary: Int? = null,
        callback: (RomajiConversionResult) -> Unit,
    ) {
        // conversionResult() caches the complete 24-candidate pool. The caller chooses eight for
        // an interior cursor or 24 for terminal WholeCompositionCandidatePolicy classification.
        submit(callback, failureValue = { RomajiConversionResult(emptyList(), emptyList()) }) {
            isCancelled ->
            val result = conversionResult(
                kana,
                isCancelled,
                initialRightId,
                initialContextSurface,
                requiredBoundary,
            )
            result.copy(
                candidates = result.candidates.take(
                    limit.coerceIn(0, MAX_ROMAJI_ANALYSIS_CANDIDATES)
                )
            )
        }
    }

    fun submitHandwriting(
        candidates: List<RecognitionCandidate>,
        stageContext: HandwritingStageContext?,
        limit: Int,
        callback: (HandwritingPipelineResult) -> Unit
    ) {
        submit(callback) { isCancelled ->
            handwritingResult(candidates, stageContext, limit, isCancelled)
        }
    }

    private fun handwritingResult(
        candidates: List<RecognitionCandidate>,
        stageContext: HandwritingStageContext?,
        limit: Int,
        isCancelled: () -> Boolean,
    ): HandwritingPipelineResult {
        val priorities = priorities(candidates.map { it.text })
        val ranked = KanjiCandidateRanker.rank(candidates, priorities)
        var rankingFloor = 0f
        val rankedWithCosts = ranked.map { candidate ->
            rankingFloor = maxOf(rankingFloor, candidate.shapeCost)
            RankedRecognitionCandidate(candidate, rankingFloor)
        }
        if (stageContext == null) {
            val unique = rankedWithCosts.asSequence()
                .distinctBy { it.candidate.text }
                .toList()
            val shaped = takeRetainingRawTopRecognition(
                unique,
                MAX_HANDWRITING_SURFACES,
            )
            val evidence = loadLexicalEvidence(shaped.map { it.candidate.text })
            if (isCancelled()) return HandwritingPipelineResult.Characters(emptyList())
            val resolved = rankCharacters(shaped, evidence, limit)
            val loadedReadings = readings(resolved.map { it.text })
            return HandwritingPipelineResult.Characters(
                resolved.map { candidate ->
                    candidate.copy(readings = loadedReadings[candidate.text].orEmpty())
                }
            )
        }

        val current = rankedWithCosts.map { rankedCandidate ->
            val candidate = rankedCandidate.candidate
            ShapedCharacterCandidate(
                text = candidate.text,
                originalShapeCost = candidate.shapeCost,
                shapeCost = rankedCandidate.rankingShapeCost,
                isRecognizerRawTop = candidate.representsRecognizerRawTop(),
            )
        }
        val currentForNextStage = takeRetainingRawTopShapedCharacters(
            current.distinctBy { it.text },
            MAX_HANDWRITING_SURFACES,
        )
        val currentAlternatives = currentForNextStage.map { it.text }
        val root = if (stageContext.previousAlternatives.isEmpty()) {
            stageContext.baseBeforeCurrent
        } else {
            stageContext.wordRootBeforeLast
        }
        val rawTopPrefix = stageContext.previousCandidates
            .firstOrNull { it.isRecognizerRawTop }
            ?.takeIf { stageContext.previousAlternatives.isNotEmpty() }
            ?.let { root + it.text }
            ?: stageContext.baseBeforeCurrent
        val rawTopSurface = currentForNextStage.firstOrNull { it.isRecognizerRawTop }?.let {
            rawTopPrefix + it.text
        }
        val shapedSurfaces = WordCandidateResolver.combineShaped(
            root = root,
            previousCharacters = stageContext.previousAlternatives,
            previousCandidateEvidence = stageContext.previousCandidates.associate { candidate ->
                candidate.text to ShapedCharacterCandidate(
                    text = candidate.text,
                    originalShapeCost = candidate.originalShapeCost,
                    shapeCost = candidate.rankingShapeCost,
                    isRecognizerRawTop = candidate.isRecognizerRawTop,
                )
            },
            currentCharacters = current,
            rawTopSurface = rawTopSurface,
        )
        val surfaces = shapedSurfaces.map { it.surface }
        val previous = stageContext.previousAlternatives.ifEmpty { listOf("") }
        val componentSurfaces = previous.map { root + it } + currentAlternatives
        val loadedReadings = readings(surfaces + componentSurfaces)
        if (isCancelled()) return HandwritingPipelineResult.Staged(
            topSurface = rawTopSurface.orEmpty(),
            currentAlternatives = emptyList(),
            currentCandidates = emptyList(),
            candidates = emptyList(),
        )
        // Component evidence is already available from the recognizer batch. Keep it separate
        // from whole-surface lexical evidence so carrying it to the next stage never adds a DB
        // query. The next stage consumes rankingShapeCost, not the presentation-only readings.
        val currentCandidates = currentForNextStage.map { candidate ->
            ResolvedCharacterCandidate(
                text = candidate.text,
                readings = loadedReadings[candidate.text].orEmpty(),
                originalShapeCost = candidate.originalShapeCost,
                rankingShapeCost = candidate.shapeCost,
                shapeCost = candidate.shapeCost,
                isRecognizerRawTop = candidate.isRecognizerRawTop,
                lexicalEvidence = null,
            )
        }
        val composed = WordCandidateResolver.composedReadings(
            root,
            stageContext.previousAlternatives,
            currentAlternatives,
            loadedReadings,
        )
        val exact = surfaces.associateWith { surface ->
            loadedReadings[surface].orEmpty().ifEmpty { composed[surface].orEmpty() }
        }
        val completions = surfaceSuggestions(surfaces, limit)
        val shapedWords = WordCandidateResolver.shapedCandidates(
            shapedSurfaces,
            exact,
            completions,
        )
        val evidence = loadLexicalEvidence(shapedWords.map { it.surface })
        if (isCancelled()) return HandwritingPipelineResult.Staged(
            topSurface = rawTopSurface.orEmpty(),
            currentAlternatives = emptyList(),
            currentCandidates = emptyList(),
            candidates = emptyList(),
        )
        val resolved = WordCandidateResolver.resolveShaped(shapedWords, evidence, limit)
        val baseSurfaces = surfaces.toHashSet()
        val topSurface = resolved.firstOrNull { it.surface in baseSurfaces }?.surface
            ?: shapedSurfaces.firstOrNull()?.surface
            ?: stageContext.baseBeforeCurrent
        return HandwritingPipelineResult.Staged(
            topSurface = topSurface,
            currentAlternatives = currentAlternatives,
            currentCandidates = currentCandidates,
            candidates = resolved,
        )
    }

    /** Invalidate callbacks and discard queued work when input state changes. */
    fun invalidate() {
        generation.incrementAndGet()
        (worker as? ThreadPoolExecutor)?.queue?.clear()
    }

    private fun <T> submit(
        callback: (T) -> Unit,
        failureValue: (() -> T)? = null,
        operation: (() -> Boolean) -> T
    ) {
        if (closed.get()) return
        val request = generation.incrementAndGet()
        (worker as? ThreadPoolExecutor)?.queue?.clear()
        worker.execute {
            if (closed.get() || request != generation.get()) return@execute
            val isCancelled = { closed.get() || request != generation.get() }
            val result = runCatching { operation(isCancelled) }
                .onFailure(::logFailure)
                .getOrElse { failureValue?.invoke() ?: return@execute }
            postToMain {
                if (!closed.get() && request == generation.get()) callback(result)
            }
        }
    }

    private fun dataSource(): ReadingDataSource = source ?: dataSourceFactory().also { source = it }

    private fun readings(surfaces: List<String>): Map<String, List<String>> {
        val unique = surfaces.asSequence().filter(String::isNotEmpty).distinct().toList()
        val missing = unique.filterNot(readingCache::containsKey)
        if (missing.isNotEmpty()) {
            val loaded = dataSource().readingsForMany(missing)
            missing.forEach { readingCache[it] = loaded[it].orEmpty() }
        }
        return unique.associateWith { readingCache[it].orEmpty() }
    }

    private fun priorities(literals: List<String>): Map<String, KanjiUsagePriority> {
        val unique = literals.distinct()
        val missing = unique.filterNot(priorityCache::containsKey)
        if (missing.isNotEmpty()) {
            val loaded = dataSource().kanjiPriorities(missing)
            missing.forEach { priorityCache[it] = loaded[it] }
        }
        return buildMap {
            unique.forEach { literal -> priorityCache[literal]?.let { put(literal, it) } }
        }
    }

    private fun loadLexicalEvidence(
        surfaces: List<String>
    ): Map<String, SurfaceLexicalEvidence> {
        val normalized = surfaces.asSequence()
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_HANDWRITING_SURFACES)
            .toList()
        if (normalized.isEmpty()) return emptyMap()
        return runCatching { dataSource().lexicalEvidenceForSurfaces(normalized) }
            .onFailure(::logFailure)
            .getOrDefault(emptyMap())
    }

    private fun rankCharacters(
        candidates: List<RankedRecognitionCandidate>,
        evidence: Map<String, SurfaceLexicalEvidence>,
        limit: Int,
    ): List<ResolvedCharacterCandidate> {
        val ranked = candidates.mapIndexed { index, rankedCandidate ->
            val candidate = rankedCandidate.candidate
            val lexical = evidence[candidate.text]
            IndexedValue(
                index,
                ResolvedCharacterCandidate(
                    text = candidate.text,
                    readings = emptyList(),
                    originalShapeCost = candidate.shapeCost,
                    rankingShapeCost = rankedCandidate.rankingShapeCost,
                    shapeCost = lexical?.applyToShapeCost(rankedCandidate.rankingShapeCost)
                        ?: rankedCandidate.rankingShapeCost,
                    isRecognizerRawTop = candidate.representsRecognizerRawTop(),
                    lexicalEvidence = lexical,
                ),
            )
        }.sortedWith(
            compareBy<IndexedValue<ResolvedCharacterCandidate>> { it.value.shapeCost }
                .thenBy { it.index }
        ).map { it.value }
        return takeRetainingRawTop(ranked, limit.coerceIn(0, MAX_HANDWRITING_SURFACES))
    }

    private fun takeRetainingRawTop(
        candidates: List<ResolvedCharacterCandidate>,
        limit: Int,
    ): List<ResolvedCharacterCandidate> {
        if (limit <= 0) return emptyList()
        val selected = candidates.take(limit).toMutableList()
        val rawTop = candidates.firstOrNull { it.isRecognizerRawTop } ?: return selected
        if (selected.none { it.isRecognizerRawTop }) {
            if (selected.size == limit) selected.removeAt(selected.lastIndex)
            selected += rawTop
        }
        return selected
    }

    private fun takeRetainingRawTopRecognition(
        candidates: List<RankedRecognitionCandidate>,
        limit: Int,
    ): List<RankedRecognitionCandidate> {
        if (limit <= 0) return emptyList()
        val selected = candidates.take(limit).toMutableList()
        val rawTop = candidates.firstOrNull {
            it.candidate.representsRecognizerRawTop()
        } ?: return selected
        if (selected.none { it.candidate.representsRecognizerRawTop() }) {
            if (selected.size == limit) selected.removeAt(selected.lastIndex)
            selected += rawTop
        }
        return selected
    }

    private fun takeRetainingRawTopShapedCharacters(
        candidates: List<ShapedCharacterCandidate>,
        limit: Int,
    ): List<ShapedCharacterCandidate> {
        if (limit <= 0) return emptyList()
        val selected = candidates.take(limit).toMutableList()
        val rawTop = candidates.firstOrNull { it.isRecognizerRawTop } ?: return selected
        if (selected.none { it.isRecognizerRawTop }) {
            if (selected.size == limit) selected.removeAt(selected.lastIndex)
            selected += rawTop
        }
        return selected
    }

    private fun RecognitionCandidate.representsRecognizerRawTop(): Boolean =
        isRecognizerRawTop || (
            evidence.components.isNotEmpty() &&
                evidence.components.all { it.isRecognizerRawTop }
            )

    private fun suggestions(
        kind: SuggestionKind,
        prefix: String,
        limit: Int
    ): List<WordReadingCandidate> {
        val key = SuggestionKey(kind, prefix, limit)
        suggestionCache[key]?.let { return it }
        val loaded = when (kind) {
            SuggestionKind.SURFACE -> dataSource().suggest(prefix, limit)
            SuggestionKind.READING -> dataSource().suggestByReading(prefix, limit)
        }
        suggestionCache[key] = loaded
        return loaded
    }

    private fun conversionResult(
        kana: String,
        isCancelled: () -> Boolean,
        initialRightId: Int = PosClass.BOS.id,
        initialContextSurface: String? = null,
        requiredBoundary: Int? = null,
    ): RomajiConversionResult {
        if (!isCompletedKana(kana) ||
            kana.codePointCount(0, kana.length) > MAX_CONVERSION_INPUT_CODE_POINTS ||
            isCancelled()
        ) {
            return RomajiConversionResult(emptyList(), emptyList())
        }
        val request = ConversionRequest(
            kana,
            initialRightId,
            initialContextSurface,
            requiredBoundary,
        )
        conversionCache[request]?.let { return it }
        val dataSource = dataSource()
        val lexemes = dataSource.conversionLexemes(
            kana,
            MAX_CONVERSION_TOKEN_CODE_POINTS,
            MAX_CONVERSION_VOCABULARY_PER_READING
        )
        if (isCancelled()) return RomajiConversionResult(emptyList(), emptyList())
        val conversionResults = KanaKanjiConverter.convert(
            reading = kana,
            lexemes = lexemes,
            connections = dataSource.conversionConnections(),
            limit = MAX_ROMAJI_ANALYSIS_CANDIDATES,
            preserveSegmentations = true,
            initialRightId = initialRightId,
            initialContextSurface = initialContextSurface,
            requiredBoundary = requiredBoundary,
            contextModel = dataSource.conversionContextModel(),
            isCancelled = isCancelled
        )
        val converted = conversionResults.map { result ->
            WordReadingCandidate(result.surface, listOf(result.reading))
        }
        if (isCancelled()) return RomajiConversionResult(emptyList(), emptyList())
        val prefixMatches = suggestions(
            SuggestionKind.READING,
            kana,
            MAX_ROMAJI_ANALYSIS_CANDIDATES
        )
        if (isCancelled()) return RomajiConversionResult(emptyList(), emptyList())
        return RomajiConversionResult(
            candidates = (converted + prefixMatches)
                .distinctBy { it.surface }
                .take(MAX_ROMAJI_ANALYSIS_CANDIDATES),
            conversions = conversionResults,
        ).also { conversionCache[request] = it }
    }

    private fun isCompletedKana(value: String): Boolean {
        if (value.isEmpty()) return false
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HIRAGANA &&
                codePoint != JAPANESE_LONG_VOWEL_MARK.codePointAt(0)
            ) {
                return false
            }
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private fun surfaceSuggestions(
        prefixes: List<String>,
        limit: Int
    ): Map<String, List<WordReadingCandidate>> {
        val unique = prefixes.distinct()
        val missing = unique.filter { prefix ->
            !suggestionCache.containsKey(SuggestionKey(SuggestionKind.SURFACE, prefix, limit))
        }
        if (missing.isNotEmpty()) {
            val loaded = dataSource().suggestForPrefixes(missing, limit)
            missing.forEach { prefix ->
                suggestionCache[SuggestionKey(SuggestionKind.SURFACE, prefix, limit)] =
                    loaded[prefix].orEmpty()
            }
        }
        return unique.associateWith { prefix ->
            suggestionCache[SuggestionKey(SuggestionKind.SURFACE, prefix, limit)].orEmpty()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        (worker as? ThreadPoolExecutor)?.queue?.clear()
        worker.execute {
            runCatching { source?.close() }.onFailure(::logFailure)
            source = null
            readingCache.clear()
            suggestionCache.clear()
            conversionCache.clear()
            priorityCache.clear()
        }
        worker.shutdown()
    }

    private fun logFailure(error: Throwable) {
        // android.util.Log is unavailable in local JVM tests; logging must never suppress fallback.
        runCatching { Log.e(TAG, "Candidate pipeline operation failed", error) }
    }

    private class LruMap<K, V>(private val maxSize: Int) :
        LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxSize
    }

    companion object {
        private const val TAG = "CandidatePipeline"
        private const val READING_CACHE_SIZE = 512
        private const val SUGGESTION_CACHE_SIZE = 128
        private const val CONVERSION_CACHE_SIZE = 64
        private const val PRIORITY_CACHE_SIZE = 1_024
        private const val MAX_CONVERSION_INPUT_CODE_POINTS = 48
        private const val MAX_CONVERSION_TOKEN_CODE_POINTS = 16
        private const val MAX_CONVERSION_VOCABULARY_PER_READING = 12
        /** Over-fetched pool; terminal policy owns the final display limit of eight. */
        const val MAX_ROMAJI_ANALYSIS_CANDIDATES = 24
        private const val MAX_HANDWRITING_SURFACES = 24
        private const val JAPANESE_LONG_VOWEL_MARK = "ー"

        private fun defaultWorker(): ExecutorService = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            { task -> Thread(task, "furigana-candidates").apply { isDaemon = true } }
        )

        private fun defaultMainPoster(): ((() -> Unit) -> Unit) {
            val handler = Handler(Looper.getMainLooper())
            return { action -> handler.post(action) }
        }
    }
}
