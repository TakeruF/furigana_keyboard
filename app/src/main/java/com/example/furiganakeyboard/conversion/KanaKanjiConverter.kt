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
        isCancelled: () -> Boolean = { false },
    ): List<ConversionResult> {
        if (isCancelled() || reading.isEmpty() || limit <= 0) return emptyList()
        if (reading.codePointCount(0, reading.length) > MAX_INPUT_CODE_POINTS) return emptyList()

        val boundaries = codePointBoundaries(reading)
        val boundarySet = boundaries.toHashSet()
        val connectionCosts = HashMap<ConnectionKey, Int>()
        for (connection in connections) {
            if (isCancelled()) return emptyList()
            val key = ConnectionKey(connection.rightId, connection.leftId)
            connectionCosts[key] = minOf(connectionCosts[key] ?: Int.MAX_VALUE, connection.cost)
        }

        val usableLexemes = ArrayList<ConversionLexeme>()
        for (lexeme in lexemes) {
            if (isCancelled()) return emptyList()
            if (isUsable(lexeme, reading, boundarySet)) usableLexemes += lexeme
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

        for (boundaryIndex in 0 until boundaries.lastIndex) {
            if (isCancelled()) return emptyList()
            val start = boundaries[boundaryIndex]
            val incoming = prune(statesAt[start].orEmpty())
            if (incoming.isEmpty()) continue

            val copyEnd = boundaries[boundaryIndex + 1]
            val copied = reading.substring(start, copyEnd)
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
        val completed = prune(statesAt[reading.length].orEmpty()).map { state ->
            state.copy(
                cost = state.cost + connectionCost(connectionCosts, state.rightId, PosClass.EOS.id),
            )
        }.filter { state -> state.segments.any { !it.isCopy } }

        val uniqueSurfaces = LinkedHashMap<String, PathState>()
        completed.sortedWith(PATH_ORDER).forEach { state -> uniqueSurfaces.putIfAbsent(state.surface, state) }

        return uniqueSurfaces.values.take(limit.coerceAtMost(MAX_OUTPUT)).map { state ->
            ConversionResult(
                surface = state.surface,
                reading = reading,
                cost = state.cost.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
                segments = state.segments,
            )
        }
    }

    private fun isUsable(
        lexeme: ConversionLexeme,
        input: String,
        boundaries: Set<Int>,
    ): Boolean =
        lexeme.start >= 0 &&
            lexeme.end > lexeme.start &&
            lexeme.end <= input.length &&
            lexeme.start in boundaries &&
            lexeme.end in boundaries &&
            input.substring(lexeme.start, lexeme.end) == lexeme.reading &&
            lexeme.reading.codePointCount(0, lexeme.reading.length) <= MAX_LEXEME_CODE_POINTS

    private fun codePointBoundaries(value: String): IntArray {
        val result = IntArray(value.codePointCount(0, value.length) + 1)
        var offset = 0
        var index = 0
        while (offset < value.length) {
            result[index++] = offset
            offset += Character.charCount(value.codePointAt(offset))
        }
        result[index] = value.length
        return result
    }

    private fun connectionCost(
        costs: Map<ConnectionKey, Int>,
        rightId: Int,
        leftId: Int,
    ): Int = costs[ConnectionKey(rightId, leftId)] ?: DEFAULT_CONNECTION_COST

    /** Keep the cheapest path for each (position, right ID, surface), then apply the beam. */
    private fun prune(states: List<PathState>): List<PathState> {
        val best = HashMap<StateKey, PathState>()
        states.forEach { state ->
            val key = StateKey(state.end, state.rightId, state.surface)
            val current = best[key]
            if (current == null || PATH_ORDER.compare(state, current) < 0) best[key] = state
        }
        return best.values.sortedWith(PATH_ORDER).take(BEAM_WIDTH)
    }

    private data class ConnectionKey(val rightId: Int, val leftId: Int)
    private data class VocabularyKey(val start: Int, val end: Int, val reading: String)
    private data class StateKey(val end: Int, val rightId: Int, val surface: String)

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

    private val LEXEME_ORDER = compareBy<ConversionLexeme>(
        ConversionLexeme::start,
        ConversionLexeme::end,
        ConversionLexeme::wordCost,
        ConversionLexeme::surface,
        ConversionLexeme::leftId,
        ConversionLexeme::rightId,
    )
    private val EDGE_ORDER = compareBy<Edge>(
        Edge::end,
        Edge::wordCost,
        Edge::surface,
        Edge::leftId,
        Edge::rightId,
        Edge::isCopy,
    )
    private val PATH_ORDER = compareBy<PathState>(
        PathState::cost,
        PathState::copyCodePoints,
        { it.segments.size },
        PathState::surface,
        PathState::rightId,
    )
}
