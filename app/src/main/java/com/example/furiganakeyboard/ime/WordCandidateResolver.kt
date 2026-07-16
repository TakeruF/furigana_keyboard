package com.example.furiganakeyboard.ime

import com.example.furiganakeyboard.reading.SurfaceLexicalEvidence
import com.example.furiganakeyboard.reading.WordReadingCandidate

/** Shape evidence attached to one recognizer surface before dictionary enrichment. */
internal data class ShapedCharacterCandidate(
    val text: String,
    val originalShapeCost: Float,
    val shapeCost: Float,
    val isRecognizerRawTop: Boolean,
)

/** A complete handwriting surface whose shape evidence must survive word resolution. */
internal data class ShapedSurfaceCandidate(
    val surface: String,
    val originalShapeCost: Float,
    val shapeCost: Float,
    val isRecognizerRawTop: Boolean,
)

internal data class ShapedWordCandidate(
    val surface: String,
    val readings: List<String>,
    val originalShapeCost: Float,
    val shapeCost: Float,
    val isRecognizerRawTop: Boolean,
)

/** Ranks exact and prefix JMdict matches across handwriting alternative combinations. */
object WordCandidateResolver {
    /**
     * Build readings for compounds whose complete surface is not in the dictionary.
     *
     * This lets a known word followed by a known character/word (for example
     * 「国際」 + 「版」) remain a useful candidate even when JMdict does not
     * contain the combined surface 「国際版」.
     */
    fun composedReadings(
        root: String,
        previousCharacters: List<String>,
        currentCharacters: List<String>,
        readings: Map<String, List<String>>
    ): Map<String, List<String>> {
        val previous = previousCharacters.ifEmpty { listOf("") }
        val result = LinkedHashMap<String, List<String>>()
        for (left in previous.take(MAX_CHARACTER_ALTERNATIVES)) {
            val prefix = root + left
            val prefixReadings = readings[prefix].orEmpty()
            if (prefixReadings.isEmpty()) continue
            for (right in currentCharacters.take(MAX_CHARACTER_ALTERNATIVES)) {
                val suffixReadings = readings[right].orEmpty()
                if (suffixReadings.isEmpty()) continue
                result[prefix + right] = prefixReadings.asSequence()
                    .flatMap { prefixReading ->
                        suffixReadings.asSequence().map { suffixReading ->
                            prefixReading.toHiragana() + suffixReading.toHiragana()
                        }
                    }
                    .distinct()
                    .take(MAX_COMPOSED_READINGS)
                    .toList()
            }
        }
        return result
    }

    fun resolve(
        surfaces: List<String>,
        exactReadings: (String) -> List<String>,
        suggestions: (String) -> List<WordReadingCandidate>,
        limit: Int = 8
    ): List<WordReadingCandidate> {
        val normalized = surfaces.asSequence()
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_SURFACES)
            .toList()

        val exact = normalized.mapNotNull { surface ->
            exactReadings(surface).takeIf { it.isNotEmpty() }
                ?.let { WordReadingCandidate(surface, it) }
        }
        val completions = normalized.flatMap { prefix ->
            suggestions(prefix).filter { it.surface != prefix }
        }
        return (exact + completions)
            .distinctBy { it.surface }
            .take(limit)
    }

    /** Batched equivalent used by the asynchronous candidate pipeline. */
    fun resolve(
        surfaces: List<String>,
        exactReadings: Map<String, List<String>>,
        suggestions: Map<String, List<WordReadingCandidate>>,
        limit: Int = 8
    ): List<WordReadingCandidate> {
        val normalized = surfaces.asSequence()
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_SURFACES)
            .toList()
        val exact = normalized.mapNotNull { surface ->
            exactReadings[surface]?.takeIf(List<String>::isNotEmpty)
                ?.let { WordReadingCandidate(surface, it) }
        }
        val completions = normalized.flatMap { prefix ->
            suggestions[prefix].orEmpty().filter { it.surface != prefix }
        }
        return (exact + completions).distinctBy { it.surface }.take(limit)
    }

    /**
     * Carry recognition shape evidence through exact, unknown, and completion candidates.
     * Base recognition surfaces are retained even when the dictionary has no reading.
     */
    internal fun shapedCandidates(
        surfaces: List<ShapedSurfaceCandidate>,
        exactReadings: Map<String, List<String>>,
        suggestions: Map<String, List<WordReadingCandidate>>,
    ): List<ShapedWordCandidate> {
        val normalized = distinctShapedSurfaces(surfaces)
        val output = LinkedHashMap<String, ShapedWordCandidate>()
        normalized.forEach { shaped ->
            output[shaped.surface] = ShapedWordCandidate(
                surface = shaped.surface,
                readings = exactReadings[shaped.surface].orEmpty(),
                originalShapeCost = shaped.originalShapeCost,
                shapeCost = shaped.shapeCost,
                isRecognizerRawTop = shaped.isRecognizerRawTop,
            )
        }
        normalized.forEach { shaped ->
            suggestions[shaped.surface].orEmpty().forEach { suggestion ->
                if (suggestion.surface != shaped.surface && output.size < MAX_SURFACES) {
                    output.putIfAbsent(
                        suggestion.surface,
                        ShapedWordCandidate(
                            surface = suggestion.surface,
                            readings = suggestion.readings,
                            originalShapeCost = shaped.originalShapeCost,
                            shapeCost = shaped.shapeCost,
                            isRecognizerRawTop = false,
                        ),
                    )
                }
            }
        }
        return output.values.take(MAX_SURFACES)
    }

    /** Stable lower-is-better ranking with bounded lexical evidence and raw-top retention. */
    internal fun resolveShaped(
        candidates: List<ShapedWordCandidate>,
        lexicalEvidence: Map<String, SurfaceLexicalEvidence>,
        limit: Int,
    ): List<ResolvedWordCandidate> {
        val ranked = candidates.asSequence()
            .filter { it.surface.isNotEmpty() }
            .distinctBy { it.surface }
            .take(MAX_SURFACES)
            .mapIndexed { index, candidate ->
                val evidence = lexicalEvidence[candidate.surface]
                IndexedValue(
                    index,
                    ResolvedWordCandidate(
                        surface = candidate.surface,
                        readings = candidate.readings,
                        originalShapeCost = candidate.originalShapeCost,
                        rankingShapeCost = candidate.shapeCost,
                        shapeCost = evidence?.applyToShapeCost(candidate.shapeCost)
                            ?: candidate.shapeCost,
                        isRecognizerRawTop = candidate.isRecognizerRawTop,
                        lexicalEvidence = evidence,
                    ),
                )
            }
            .sortedWith(
                compareBy<IndexedValue<ResolvedWordCandidate>> { it.value.shapeCost }
                    .thenBy { it.index }
            )
            .map { it.value }
            .toList()
        return takeRetainingRawTopWords(ranked, limit.coerceIn(0, MAX_SURFACES))
    }

    /** Recognition-order-preserving cross product, with the top/top pair first. */
    fun combine(
        root: String,
        previousCharacters: List<String>,
        currentCharacters: List<String>
    ): List<String> {
        if (currentCharacters.isEmpty()) return emptyList()
        val previous = previousCharacters.ifEmpty { listOf("") }
        return previous.asSequence()
            .take(MAX_CHARACTER_ALTERNATIVES)
            .flatMap { left ->
                currentCharacters.asSequence()
                    .take(MAX_CHARACTER_ALTERNATIVES)
                    .map { right -> root + left + right }
            }
            .distinct()
            .take(MAX_SURFACES)
            .toList()
    }

    /** Recognition-order cross product that keeps the current character's shape evidence. */
    internal fun combineShaped(
        root: String,
        previousCharacters: List<String>,
        previousCandidateEvidence: Map<String, ShapedCharacterCandidate>,
        currentCharacters: List<ShapedCharacterCandidate>,
        rawTopSurface: String?,
    ): List<ShapedSurfaceCandidate> {
        if (currentCharacters.isEmpty()) return emptyList()
        val previousValues = previousCharacters.ifEmpty { listOf("") }
            .asSequence()
            .distinct()
            .take(MAX_SURFACES)
            .toList()
        val previous = takeRetainingRawTop(
            previousValues,
            MAX_CHARACTER_ALTERNATIVES,
        ) { value -> previousCandidateEvidence[value]?.isRecognizerRawTop == true }
        val current = takeRetainingRawTopCharacters(
            currentCharacters.distinctBy { it.text },
            MAX_CHARACTER_ALTERNATIVES,
        )
        val output = LinkedHashMap<String, ShapedSurfaceCandidate>()
        previous.forEach { left ->
            current.forEach { right ->
                val surface = root + left + right.text
                val leftEvidence = previousCandidateEvidence[left]
                output.putIfAbsent(
                    surface,
                    ShapedSurfaceCandidate(
                        surface = surface,
                        originalShapeCost = leftEvidence?.let {
                            (it.originalShapeCost + right.originalShapeCost) / 2f
                        } ?: right.originalShapeCost,
                        shapeCost = leftEvidence?.let {
                            (it.shapeCost + right.shapeCost) / 2f
                        } ?: right.shapeCost,
                        isRecognizerRawTop = surface == rawTopSurface,
                    ),
                )
            }
        }
        return takeRetainingRawTop(
            output.values.toList(),
            MAX_SURFACES,
            ShapedSurfaceCandidate::isRecognizerRawTop,
        )
    }

    private fun distinctShapedSurfaces(
        surfaces: List<ShapedSurfaceCandidate>
    ): List<ShapedSurfaceCandidate> = surfaces.asSequence()
        .filter { it.surface.isNotEmpty() }
        .distinctBy { it.surface }
        .take(MAX_SURFACES)
        .toList()

    private fun <T> takeRetainingRawTop(
        values: List<T>,
        limit: Int,
        isRawTop: (T) -> Boolean,
    ): List<T> {
        if (limit <= 0) return emptyList()
        val selected = values.take(limit).toMutableList()
        val rawTop = values.firstOrNull(isRawTop) ?: return selected
        if (selected.none(isRawTop)) {
            if (selected.size == limit) selected.removeAt(selected.lastIndex)
            selected += rawTop
        }
        return selected
    }

    private fun takeRetainingRawTopCharacters(
        values: List<ShapedCharacterCandidate>,
        limit: Int,
    ): List<ShapedCharacterCandidate> = takeRetainingRawTop(
        values,
        limit,
        ShapedCharacterCandidate::isRecognizerRawTop,
    )

    private fun takeRetainingRawTopWords(
        values: List<ResolvedWordCandidate>,
        limit: Int,
    ): List<ResolvedWordCandidate> = takeRetainingRawTop(
        values,
        limit,
        ResolvedWordCandidate::isRecognizerRawTop,
    )

    private const val MAX_CHARACTER_ALTERNATIVES = 5
    private const val MAX_SURFACES = 24
    private const val MAX_COMPOSED_READINGS = 3

    private fun String.toHiragana(): String = buildString(length) {
        this@toHiragana.forEach { character ->
            append(
                if (character in '\u30a1'..'\u30f6') character - ('\u30a1' - '\u3041')
                else character
            )
        }
    }
}
