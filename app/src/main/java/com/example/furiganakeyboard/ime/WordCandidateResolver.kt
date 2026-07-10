package com.example.furiganakeyboard.ime

import com.example.furiganakeyboard.reading.WordReadingCandidate

/** Ranks exact and prefix JMdict matches across handwriting alternative combinations. */
object WordCandidateResolver {
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
}
