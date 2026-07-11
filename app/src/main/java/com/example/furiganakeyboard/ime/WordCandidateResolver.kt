package com.example.furiganakeyboard.ime

import com.example.furiganakeyboard.reading.WordReadingCandidate

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
