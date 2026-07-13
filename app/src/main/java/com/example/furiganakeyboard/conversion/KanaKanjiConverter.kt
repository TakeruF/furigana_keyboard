package com.example.furiganakeyboard.conversion

/** Pure Kotlin lattice converter. It deliberately has no Android or storage dependencies. */
object KanaKanjiConverter {
    private const val MAX_INPUT_CODE_POINTS = 48
    private const val MAX_LEXEME_CODE_POINTS = 16
    private const val MAX_VOCABULARY_PER_READING = 12
    private const val BEAM_WIDTH = 12
    private const val MAX_OUTPUT = 8
    private const val DEFAULT_CONNECTION_COST = 3_000
    private const val COPY_WORD_COST = 4_000

    fun convert(
        reading: String,
        lexemes: List<ConversionLexeme>,
        connections: List<ConversionConnection>,
        limit: Int = MAX_OUTPUT,
        preserveSegmentations: Boolean = false,
        isCancelled: () -> Boolean = { false },
    ): List<ConversionResult> {
        if (isCancelled() || reading.isEmpty() || limit <= 0) return emptyList()
        if (reading.codePointCount(0, reading.length) > MAX_INPUT_CODE_POINTS) return emptyList()

        val utf16Boundaries = ConversionText.utf16Boundaries(reading)
        val scalarCount = utf16Boundaries.lastIndex
        val connectionCosts = HashMap<ConnectionKey, Int>()
        for (connection in connections) {
            if (isCancelled()) return emptyList()
            val key = ConnectionKey(connection.rightId, connection.leftId)
            connectionCosts[key] = minOf(connectionCosts[key] ?: Int.MAX_VALUE, connection.cost)
        }

        val usableLexemes = ArrayList<ConversionLexeme>()
        for (lexeme in lexemes) {
            if (isCancelled()) return emptyList()
            if (isUsable(lexeme, reading, utf16Boundaries)) usableLexemes += lexeme
        }
        usableLexemes.sortWith(LEXEME_ORDER)

        val vocabularyCounts = HashMap<VocabularyKey, Int>()
        val dictionaryEdges = HashMap<Int, MutableList<ConversionLexeme>>()
        for (lexeme in usableLexemes) {
            if (isCancelled()) return emptyList()
            val key = VocabularyKey(lexeme.start, lexeme.end, lexeme.reading)
            val count = vocabularyCounts[key] ?: 0
            if (count < MAX_VOCABULARY_PER_READING) {
                dictionaryEdges.getOrPut(lexeme.start, ::mutableListOf) += lexeme
                vocabularyCounts[key] = count + 1
            }
        }

        val statesAt = HashMap<Int, MutableList<PathState>>()
        statesAt[0] = mutableListOf(
            PathState(
                end = 0,
                rightId = PosClass.BOS.id,
                surface = "",
                cost = 0L,
                copyCodePoints = 0,
                segments = emptyList(),
            ),
        )

        for (start in 0 until scalarCount) {
            if (isCancelled()) return emptyList()
            val incoming = prune(statesAt[start].orEmpty(), preserveSegmentations)
            if (incoming.isEmpty()) continue

            val copyEnd = start + 1
            val copied = reading.substring(utf16Boundaries[start], utf16Boundaries[copyEnd])
            val edges = ArrayList<Edge>((dictionaryEdges[start]?.size ?: 0) + 1)
            dictionaryEdges[start]?.forEach { lexeme ->
                edges += Edge(
                    start = lexeme.start,
                    end = lexeme.end,
                    reading = lexeme.reading,
                    surface = lexeme.surface,
                    leftId = lexeme.leftId,
                    rightId = lexeme.rightId,
                    wordCost = lexeme.wordCost,
                    isCopy = false,
                )
            }
            edges += Edge(
                start = start,
                end = copyEnd,
                reading = copied,
                surface = copied,
                leftId = PosClass.COPY.id,
                rightId = PosClass.COPY.id,
                wordCost = COPY_WORD_COST,
                isCopy = true,
            )
            edges.sortWith(EDGE_ORDER)

            for (state in incoming) {
                for (edge in edges) {
                    if (isCancelled()) return emptyList()
                    val nextCost = state.cost + edge.wordCost + connectionCost(
                        connectionCosts,
                        state.rightId,
                        edge.leftId,
                    )
                    val segment = ConversionSegment(
                        start = edge.start,
                        end = edge.end,
                        reading = edge.reading,
                        surface = edge.surface,
                        leftId = edge.leftId,
                        rightId = edge.rightId,
                        wordCost = edge.wordCost,
                        isCopy = edge.isCopy,
                    )
                    statesAt.getOrPut(edge.end, ::mutableListOf) += PathState(
                        end = edge.end,
                        rightId = edge.rightId,
                        surface = state.surface + edge.surface,
                        cost = nextCost,
                        copyCodePoints = state.copyCodePoints + if (edge.isCopy) 1 else 0,
                        segments = state.segments + segment,
                    )
                }
            }
        }

        if (isCancelled()) return emptyList()
        val completed = prune(statesAt[scalarCount].orEmpty(), preserveSegmentations).map { state ->
            state.copy(
                cost = state.cost + connectionCost(connectionCosts, state.rightId, PosClass.EOS.id),
            )
        }.filter { state -> state.segments.any { !it.isCopy } }

        // Keep alternate paths when they produce the same surface with different
        // bunsetsu boundaries. The UI uses those paths to offer competing
        // interpretations; presentation code still deduplicates full surfaces.
        val uniquePaths = LinkedHashMap<ResultKey, PathState>()
        completed.sortedWith(PATH_ORDER).forEach { state ->
            uniquePaths.putIfAbsent(
                ResultKey(
                    state.surface,
                    if (preserveSegmentations) {
                        bunsetsuBoundaries(state.segments)
                    } else {
                        emptyList()
                    },
                ),
                state,
            )
        }

        return uniquePaths.values.take(limit.coerceAtMost(MAX_OUTPUT)).map { state ->
            ConversionResult(
                surface = state.surface,
                reading = reading,
                cost = ConversionCost.clampInt32(state.cost),
                segments = state.segments,
            )
        }
    }

    private fun isUsable(
        lexeme: ConversionLexeme,
        input: String,
        utf16Boundaries: IntArray,
    ): Boolean =
        lexeme.start >= 0 &&
            lexeme.end > lexeme.start &&
            lexeme.end < utf16Boundaries.size &&
            input.substring(
                utf16Boundaries[lexeme.start],
                utf16Boundaries[lexeme.end],
            ) == lexeme.reading &&
            ConversionText.scalarCount(lexeme.reading) <= MAX_LEXEME_CODE_POINTS

    private fun connectionCost(
        costs: Map<ConnectionKey, Int>,
        rightId: Int,
        leftId: Int,
    ): Int = costs[ConnectionKey(rightId, leftId)] ?: DEFAULT_CONNECTION_COST

    /** Keep the cheapest path for each (position, right ID, surface), then apply the beam. */
    private fun prune(
        states: List<PathState>,
        preserveSegmentations: Boolean,
    ): List<PathState> {
        val best = HashMap<StateKey, PathState>()
        states.forEach { state ->
            val key = StateKey(
                state.end,
                state.rightId,
                state.surface,
                if (preserveSegmentations) bunsetsuBoundaries(state.segments) else emptyList(),
            )
            val current = best[key]
            if (current == null || PATH_ORDER.compare(state, current) < 0) best[key] = state
        }
        return best.values.sortedWith(PATH_ORDER).take(BEAM_WIDTH)
    }

    private data class ConnectionKey(val rightId: Int, val leftId: Int)
    private data class VocabularyKey(val start: Int, val end: Int, val reading: String)
    private data class StateKey(
        val end: Int,
        val rightId: Int,
        val surface: String,
        val bunsetsuBoundaries: List<Int>,
    )
    private data class ResultKey(val surface: String, val bunsetsuBoundaries: List<Int>)

    private data class Edge(
        val start: Int,
        val end: Int,
        val reading: String,
        val surface: String,
        val leftId: Int,
        val rightId: Int,
        val wordCost: Int,
        val isCopy: Boolean,
    )

    private data class PathState(
        val end: Int,
        val rightId: Int,
        val surface: String,
        val cost: Long,
        val copyCodePoints: Int,
        val segments: List<ConversionSegment>,
    )

    private fun bunsetsuBoundaries(segments: List<ConversionSegment>): List<Int> = buildList {
        segments.zipWithNext().forEach { (left, right) ->
            if (closesBunsetsu(left.rightId) && startsContent(right.leftId)) add(left.end)
        }
        segments.lastOrNull()?.let { add(it.end) }
    }

    private fun compareIntLists(left: List<Int>, right: List<Int>): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = compareValues(left[index], right[index])
            if (comparison != 0) return comparison
        }
        return compareValues(left.size, right.size)
    }

    private fun closesBunsetsu(posId: Int): Boolean = when (PosClass.fromId(posId)) {
        PosClass.PARTICLE, PosClass.AUXILIARY, PosClass.SUFFIX -> true
        else -> false
    }

    private fun startsContent(posId: Int): Boolean = when (PosClass.fromId(posId)) {
        PosClass.PRONOUN,
        PosClass.NOUN,
        PosClass.PROPER_NOUN,
        PosClass.VERB,
        PosClass.ADJECTIVE,
        PosClass.ADVERB,
        PosClass.EXPRESSION,
        PosClass.PREFIX,
        PosClass.OTHER,
        PosClass.COPY -> true
        else -> false
    }

    private val LEXEME_ORDER = Comparator<ConversionLexeme> { left, right ->
        compareValues(left.start, right.start).takeUnless { it == 0 }
            ?: compareValues(left.end, right.end).takeUnless { it == 0 }
            ?: compareValues(left.wordCost, right.wordCost).takeUnless { it == 0 }
            ?: ConversionText.compareScalars(left.surface, right.surface).takeUnless { it == 0 }
            ?: compareValues(left.leftId, right.leftId).takeUnless { it == 0 }
            ?: compareValues(left.rightId, right.rightId)
    }
    private val EDGE_ORDER = Comparator<Edge> { left, right ->
        compareValues(left.end, right.end).takeUnless { it == 0 }
            ?: compareValues(left.wordCost, right.wordCost).takeUnless { it == 0 }
            ?: ConversionText.compareScalars(left.surface, right.surface).takeUnless { it == 0 }
            ?: compareValues(left.leftId, right.leftId).takeUnless { it == 0 }
            ?: compareValues(left.rightId, right.rightId).takeUnless { it == 0 }
            ?: compareValues(left.isCopy, right.isCopy)
    }
    private val PATH_ORDER = Comparator<PathState> { left, right ->
        compareValues(left.cost, right.cost).takeUnless { it == 0 }
            ?: compareValues(left.copyCodePoints, right.copyCodePoints).takeUnless { it == 0 }
            ?: compareValues(left.segments.size, right.segments.size).takeUnless { it == 0 }
            ?: ConversionText.compareScalars(left.surface, right.surface).takeUnless { it == 0 }
            ?: compareValues(left.rightId, right.rightId).takeUnless { it == 0 }
            ?: compareIntLists(
                bunsetsuBoundaries(left.segments),
                bunsetsuBoundaries(right.segments),
            )
    }
}
