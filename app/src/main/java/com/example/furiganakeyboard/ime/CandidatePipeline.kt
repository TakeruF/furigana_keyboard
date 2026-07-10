package com.example.furiganakeyboard.ime

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.furiganakeyboard.reading.KanjiUsagePriority
import com.example.furiganakeyboard.reading.ReadingDataSource
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

/** Immutable character candidate after dictionary enrichment. */
data class ResolvedCharacterCandidate(val text: String, val readings: List<String>)

data class HandwritingStageContext(
    val baseBeforeCurrent: String,
    val wordRootBeforeLast: String,
    val previousAlternatives: List<String>
)

sealed interface HandwritingPipelineResult {
    data class Characters(val candidates: List<ResolvedCharacterCandidate>) :
        HandwritingPipelineResult

    data class Staged(
        val topSurface: String,
        val currentAlternatives: List<String>,
        val candidates: List<WordReadingCandidate>
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
        submit(callback) { suggestions(SuggestionKind.READING, kana, limit) }
    }

    fun submitHandwriting(
        candidates: List<RecognitionCandidate>,
        stageContext: HandwritingStageContext?,
        limit: Int,
        callback: (HandwritingPipelineResult) -> Unit
    ) {
        submit(callback) {
            val priorities = priorities(candidates.map { it.text })
            val ranked = KanjiCandidateRanker.rank(candidates, priorities)
            val readings = readings(ranked.map { it.text })
            if (stageContext == null) {
                HandwritingPipelineResult.Characters(
                    ranked.map { ResolvedCharacterCandidate(it.text, readings[it.text].orEmpty()) }
                )
            } else {
                val currentAlternatives = ranked.map { it.text }.distinct()
                val surfaces = WordCandidateResolver.combine(
                    root = if (stageContext.previousAlternatives.isEmpty()) {
                        stageContext.baseBeforeCurrent
                    } else {
                        stageContext.wordRootBeforeLast
                    },
                    previousCharacters = stageContext.previousAlternatives,
                    currentCharacters = currentAlternatives
                )
                val exact = readings(surfaces)
                val completions = surfaceSuggestions(surfaces, limit)
                HandwritingPipelineResult.Staged(
                    topSurface = stageContext.baseBeforeCurrent + currentAlternatives.first(),
                    currentAlternatives = currentAlternatives,
                    candidates = WordCandidateResolver.resolve(
                        surfaces,
                        exact,
                        completions,
                        limit
                    )
                )
            }
        }
    }

    /** Invalidate callbacks and discard queued work when input state changes. */
    fun invalidate() {
        generation.incrementAndGet()
        (worker as? ThreadPoolExecutor)?.queue?.clear()
    }

    private fun <T> submit(callback: (T) -> Unit, operation: () -> T) {
        if (closed.get()) return
        val request = generation.incrementAndGet()
        (worker as? ThreadPoolExecutor)?.queue?.clear()
        worker.execute {
            if (closed.get() || request != generation.get()) return@execute
            val result = runCatching(operation).onFailure(::logFailure).getOrNull() ?: return@execute
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
            priorityCache.clear()
        }
        worker.shutdown()
    }

    private fun logFailure(error: Throwable) {
        Log.e(TAG, "Candidate pipeline operation failed", error)
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
        private const val PRIORITY_CACHE_SIZE = 1_024

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
