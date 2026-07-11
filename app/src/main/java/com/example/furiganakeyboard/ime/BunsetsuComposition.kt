package com.example.furiganakeyboard.ime

import com.example.furiganakeyboard.conversion.ConversionSegment
import com.example.furiganakeyboard.conversion.ConversionResult
import com.example.furiganakeyboard.conversion.PosClass

data class BunsetsuSegment(
    val reading: String,
    val surface: String = reading,
)

data class CommittedBunsetsu(
    val reading: String,
    val surface: String,
)

data class BunsetsuCommitResult(
    val committedText: String,
    val remainingText: String,
)

data class BunsetsuCandidateOption(
    val surface: String,
    val reading: String,
)

data class BunsetsuConversionPlan(
    val initialSegments: List<ConversionSegment>,
    val candidates: List<BunsetsuCandidateOption>,
)

/**
 * Pure state holder for sequential bunsetsu conversion.
 *
 * Android can only own one contiguous composing region. Consequently the active
 * range always begins at the first uncommitted segment; expanding and shrinking
 * move its right boundary. Committing that range leaves the suffix contiguous
 * and ready to become the next composing region.
 */
class BunsetsuComposition private constructor(
    initialSegments: List<BunsetsuSegment>,
) {
    val committedSegments = mutableListOf<CommittedBunsetsu>()
    val composingSegments = initialSegments.toMutableList()

    var activeSegmentIndex: Int = 0
        private set

    private var activeSegmentCount: Int = if (initialSegments.isEmpty()) 0 else 1

    var candidates: List<String> = emptyList()
        private set

    val composingText: String
        get() = composingSegments.joinToString("") { it.surface }

    val activeReading: String
        get() = activeSegments().joinToString("") { it.reading }

    val activeStart: Int
        get() = composingSegments.take(activeSegmentIndex).sumOf { it.surface.length }

    val activeEnd: Int
        get() = activeStart + activeSegments().sumOf { it.surface.length }

    val canShrink: Boolean
        get() = activeSegmentCount > 1 || activeReading.codePointCount(0, activeReading.length) > 1

    val canExpand: Boolean
        get() = activeSegmentIndex + activeSegmentCount < composingSegments.size

    fun setCandidates(values: List<String>) {
        candidates = values.distinct()
    }

    /** Select a competing leading boundary before committing its candidate. */
    fun selectActiveReading(reading: String): Boolean {
        if (reading.isEmpty() || !composingText.startsWith(reading)) return false
        val remaining = composingText.substring(reading.length)
        composingSegments.clear()
        composingSegments += BunsetsuSegment(reading)
        if (remaining.isNotEmpty()) composingSegments += BunsetsuSegment(remaining)
        activeSegmentIndex = 0
        activeSegmentCount = 1
        candidates = emptyList()
        return true
    }

    /** Move the active right boundary left by one segment or Unicode code point. */
    fun shrink(): Boolean {
        if (activeSegmentCount > 1) {
            activeSegmentCount--
            candidates = emptyList()
            return true
        }
        val active = composingSegments.getOrNull(activeSegmentIndex) ?: return false
        if (active.reading.codePointCount(0, active.reading.length) <= 1 ||
            active.surface != active.reading
        ) {
            return false
        }
        val split = active.reading.offsetByCodePoints(active.reading.length, -1)
        composingSegments[activeSegmentIndex] = BunsetsuSegment(active.reading.substring(0, split))
        composingSegments.add(activeSegmentIndex + 1, BunsetsuSegment(active.reading.substring(split)))
        candidates = emptyList()
        return true
    }

    /** Move the active right boundary right across the next segment. */
    fun expand(): Boolean {
        if (!canExpand) return false
        activeSegmentCount++
        candidates = emptyList()
        return true
    }

    fun commitActive(surface: String): BunsetsuCommitResult {
        require(activeSegmentIndex == 0) { "Only a leading composing range can be committed" }
        require(activeSegmentCount > 0 && surface.isNotEmpty()) { "No active bunsetsu" }
        val reading = activeReading
        repeat(activeSegmentCount) { composingSegments.removeAt(0) }
        committedSegments += CommittedBunsetsu(reading, surface)
        activeSegmentIndex = 0
        activeSegmentCount = if (composingSegments.isEmpty()) 0 else 1
        candidates = emptyList()
        return BunsetsuCommitResult(surface, composingText)
    }

    /** Delete from uncommitted text before the editor's already committed text. */
    fun deleteLastCodePoint(): String {
        val lastIndex = composingSegments.lastIndex
        if (lastIndex < 0) return ""
        val segment = composingSegments[lastIndex]
        if (segment.surface != segment.reading) return composingText
        val end = segment.reading.offsetByCodePoints(segment.reading.length, -1)
        val remaining = segment.reading.substring(0, end)
        if (remaining.isEmpty()) composingSegments.removeAt(lastIndex)
        else composingSegments[lastIndex] = BunsetsuSegment(remaining)
        activeSegmentCount = activeSegmentCount.coerceAtMost(composingSegments.size)
        if (composingSegments.isNotEmpty() && activeSegmentCount == 0) activeSegmentCount = 1
        candidates = emptyList()
        return composingText
    }

    private fun activeSegments(): List<BunsetsuSegment> {
        if (activeSegmentCount == 0) return emptyList()
        return composingSegments.subList(
            activeSegmentIndex,
            (activeSegmentIndex + activeSegmentCount).coerceAtMost(composingSegments.size),
        )
    }

    companion object {
        /**
         * Extract leading bunsetsu candidates from every conversion path.
         * A surface shared by different readings is deliberately retained so
         * competing boundaries remain visible to the user.
         */
        fun plan(
            reading: String,
            conversions: List<ConversionResult>,
        ): BunsetsuConversionPlan? {
            var initialSegments: List<ConversionSegment>? = null
            val candidates = LinkedHashSet<BunsetsuCandidateOption>()
            conversions.forEach { conversion ->
                val state = create(reading, conversion.segments)
                if (state.composingSegments.size <= 1) return@forEach
                if (initialSegments == null) initialSegments = conversion.segments
                val firstReading = state.activeReading
                val boundary = firstReading.length
                val firstSegments = conversion.segments.takeWhile { it.end <= boundary }
                if (firstSegments.isNotEmpty() && firstSegments.last().end == boundary) {
                    candidates += BunsetsuCandidateOption(
                        surface = firstSegments.joinToString("") { it.surface },
                        reading = firstReading,
                    )
                }
            }
            val segments = initialSegments ?: return null
            if (candidates.isEmpty()) return null
            return BunsetsuConversionPlan(segments, candidates.toList())
        }

        fun create(reading: String, conversionSegments: List<ConversionSegment>): BunsetsuComposition {
            val segments = fromConversionSegments(reading, conversionSegments)
                .ifEmpty { fallbackSegments(reading) }
                .ifEmpty { listOf(BunsetsuSegment(reading)) }
            return BunsetsuComposition(segments)
        }

        private fun fromConversionSegments(
            reading: String,
            segments: List<ConversionSegment>,
        ): List<BunsetsuSegment> {
            if (segments.isEmpty() || segments.first().start != 0 ||
                segments.last().end != reading.length ||
                segments.zipWithNext().any { (left, right) -> left.end != right.start }
            ) {
                return emptyList()
            }

            val groups = mutableListOf<MutableList<ConversionSegment>>()
            segments.forEach { segment ->
                val previous = groups.lastOrNull()?.lastOrNull()
                if (previous != null && closesBunsetsu(previous) && startsContent(segment)) {
                    groups += mutableListOf(segment)
                } else {
                    groups.lastOrNull()?.add(segment) ?: groups.add(mutableListOf(segment))
                }
            }
            return groups.map { group ->
                BunsetsuSegment(reading.substring(group.first().start, group.last().end))
            }
        }

        private fun closesBunsetsu(segment: ConversionSegment): Boolean = when (
            PosClass.fromId(segment.rightId)
        ) {
            PosClass.PARTICLE, PosClass.AUXILIARY, PosClass.SUFFIX -> true
            else -> false
        }

        private fun startsContent(segment: ConversionSegment): Boolean = when (
            PosClass.fromId(segment.leftId)
        ) {
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

        /** Used only when the conversion result has no trustworthy segment metadata. */
        private fun fallbackSegments(reading: String): List<BunsetsuSegment> {
            if (reading.isEmpty()) return emptyList()
            val result = mutableListOf<BunsetsuSegment>()
            var start = 0
            var offset = 0
            while (offset < reading.length) {
                val particle = FALLBACK_PARTICLES.firstOrNull { reading.startsWith(it, offset) }
                if (particle != null && offset > start) {
                    val end = offset + particle.length
                    if (end < reading.length) {
                        result += BunsetsuSegment(reading.substring(start, end))
                        start = end
                    }
                    offset = end
                } else {
                    offset += Character.charCount(reading.codePointAt(offset))
                }
            }
            if (start < reading.length) result += BunsetsuSegment(reading.substring(start))
            return result
        }

        private val FALLBACK_PARTICLES = listOf(
            "から", "まで", "より", "って", "では", "には", "へは", "とは",
            "は", "が", "を", "に", "へ", "と", "も", "で", "の", "や", "か", "ね", "よ",
        )
    }
}
