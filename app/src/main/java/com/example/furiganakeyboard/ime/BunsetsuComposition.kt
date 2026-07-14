package com.example.furiganakeyboard.ime

import com.example.furiganakeyboard.conversion.ConversionResult
import com.example.furiganakeyboard.conversion.ConversionSegment
import com.example.furiganakeyboard.conversion.ConversionText
import com.example.furiganakeyboard.conversion.KanaKanjiConverter
import com.example.furiganakeyboard.conversion.PosClass

data class BunsetsuSegment(
    val reading: String,
    val surface: String = reading,
    val rightId: Int = PosClass.COPY.id,
)

data class CommittedBunsetsu(
    val reading: String,
    val surface: String,
    val rightId: Int = PosClass.COPY.id,
)

data class BunsetsuCommitResult(
    val committedText: String,
    val remainingText: String,
)

data class BunsetsuCandidateOption(
    val surface: String,
    val reading: String,
    val rightId: Int = PosClass.COPY.id,
    val segments: List<ConversionSegment> = emptyList(),
)

data class BunsetsuConversionPlan(
    val initialSegments: List<ConversionSegment>,
    val candidates: List<BunsetsuCandidateOption>,
    val activeLength: Int,
)

data class BunsetsuAnalysisToken(
    val generation: Long,
    val remainingReading: String,
    val activeReading: String,
    val previousRightId: Int,
    val previousContextSurface: String?,
)

/**
 * Pure state holder for contextual, sequential bunsetsu conversion.
 *
 * The original reading and the selected path survive partial commits. The
 * uncommitted suffix is therefore never silently treated as a new BOS input.
 */
class BunsetsuComposition private constructor(
    val originalReading: String,
    initialSegments: List<BunsetsuSegment>,
    initialSegmentCandidates: List<ConversionSegment>,
    initialPaths: List<List<ConversionSegment>>,
) {
    val committedSegments = mutableListOf<CommittedBunsetsu>()
    val composingSegments = initialSegments.toMutableList()

    var remainingSegmentCandidates: List<ConversionSegment> = initialSegmentCandidates
        private set

    var previousRightId: Int = PosClass.BOS.id
        private set

    var previousContextSurface: String? = null
        private set

    var generation: Long = 0
        private set

    var activeSegmentIndex: Int = 0
        private set

    private var activeSegmentCount: Int = if (initialSegments.isEmpty()) 0 else 1
    private var conversionPaths: List<List<ConversionSegment>> = initialPaths

    var candidates: List<String> = emptyList()
        private set

    val composingText: String
        get() = composingSegments.joinToString("") { it.reading }

    val remainingReading: String
        get() = composingText

    val activeReading: String
        get() = activeSegments().joinToString("") { it.reading }

    val activeStart: Int
        get() = composingSegments.take(activeSegmentIndex).sumOf { it.surface.length }

    val activeEnd: Int
        get() = activeStart + activeSegments().sumOf { it.surface.length }

    val activeRightId: Int
        get() {
            val boundary = ConversionText.scalarCount(activeReading)
            return remainingSegmentCandidates.lastOrNull { it.end == boundary }?.rightId
                ?: activeSegments().lastOrNull()?.rightId
                ?: PosClass.COPY.id
        }

    val canShrink: Boolean
        get() = activeSegmentCount > 1 || ConversionText.scalarCount(activeReading) > 1

    val canExpand: Boolean
        get() = activeSegmentIndex + activeSegmentCount < composingSegments.size

    fun setCandidates(values: List<String>) {
        candidates = values.distinct()
    }

    fun analysisToken(): BunsetsuAnalysisToken = BunsetsuAnalysisToken(
        generation = generation,
        remainingReading = remainingReading,
        activeReading = activeReading,
        previousRightId = previousRightId,
        previousContextSurface = previousContextSurface,
    )

    fun isCurrent(token: BunsetsuAnalysisToken): Boolean =
        token.generation == generation &&
            token.remainingReading == remainingReading &&
            token.activeReading == activeReading &&
            token.previousRightId == previousRightId &&
            token.previousContextSurface == previousContextSurface

    fun isCurrentCandidate(reading: String?, candidateGeneration: Long?): Boolean =
        (candidateGeneration == null || candidateGeneration == generation) &&
            (reading == null || reading == activeReading)

    /** Candidate prefixes recoverable from the retained full conversion paths. */
    fun retainedOptions(): List<BunsetsuCandidateOption> {
        val boundary = ConversionText.scalarCount(activeReading)
        val options = LinkedHashMap<Triple<String, String, Int>, BunsetsuCandidateOption>()
        conversionPaths.forEach { path ->
            val prefix = path.takeWhile { it.end <= boundary }
            if (prefix.isEmpty() || prefix.last().end != boundary) return@forEach
            val option = BunsetsuCandidateOption(
                surface = prefix.joinToString("") { it.surface },
                reading = activeReading,
                rightId = prefix.last().rightId,
                segments = path,
            )
            options.putIfAbsent(Triple(option.surface, option.reading, option.rightId), option)
        }
        return options.values.toList()
    }

    /** Install a newly scored full-suffix path while preserving the requested leading boundary. */
    fun applyPlan(plan: BunsetsuConversionPlan, token: BunsetsuAnalysisToken): Boolean {
        if (!isCurrent(token)) return false
        val segments = fromConversionSegments(
            remainingReading,
            plan.initialSegments,
            plan.activeLength,
        )
        if (segments.isEmpty()) return false
        composingSegments.clear()
        composingSegments += segments
        remainingSegmentCandidates = plan.initialSegments
        conversionPaths = plan.candidates.map { it.segments }.filter { it.isNotEmpty() }
        activeSegmentIndex = 0
        activeSegmentCount = 1
        candidates = emptyList()
        generation++
        return true
    }

    /** Select a competing path/boundary before committing its candidate. */
    fun selectActiveReading(
        reading: String,
        rightId: Int? = null,
        surface: String? = null,
    ): Boolean {
        if (reading.isEmpty() || !remainingReading.startsWith(reading)) return false
        val boundary = ConversionText.scalarCount(reading)
        val matchingPath = conversionPaths.firstOrNull { path ->
            val prefix = path.takeWhile { it.end <= boundary }
            prefix.isNotEmpty() && prefix.last().end == boundary &&
                (rightId == null || prefix.last().rightId == rightId) &&
                (surface == null || prefix.joinToString("") { it.surface } == surface)
        }
        if (matchingPath != null) {
            val rebuilt = fromConversionSegments(remainingReading, matchingPath, boundary)
            if (rebuilt.isNotEmpty()) {
                composingSegments.clear()
                composingSegments += rebuilt
                remainingSegmentCandidates = matchingPath
            }
        } else if (reading != activeReading) {
            val suffix = remainingReading.substring(reading.length)
            composingSegments.clear()
            composingSegments += BunsetsuSegment(reading, rightId = rightId ?: PosClass.COPY.id)
            if (suffix.isNotEmpty()) composingSegments += BunsetsuSegment(suffix)
            remainingSegmentCandidates = emptyList()
            conversionPaths = emptyList()
        }
        activeSegmentIndex = 0
        activeSegmentCount = 1
        candidates = emptyList()
        return true
    }

    /** Move the active right boundary left by one segment or Unicode scalar. */
    fun shrink(): Boolean {
        if (activeSegmentCount > 1) {
            activeSegmentCount--
            mutated()
            return true
        }
        val active = composingSegments.getOrNull(activeSegmentIndex) ?: return false
        if (ConversionText.scalarCount(active.reading) <= 1 || active.surface != active.reading) {
            return false
        }
        val split = active.reading.offsetByCodePoints(active.reading.length, -1)
        composingSegments[activeSegmentIndex] = BunsetsuSegment(active.reading.substring(0, split))
        composingSegments.add(
            activeSegmentIndex + 1,
            BunsetsuSegment(active.reading.substring(split), rightId = active.rightId),
        )
        mutated()
        return true
    }

    /** Move the active right boundary right across the next retained natural segment. */
    fun expand(): Boolean {
        if (!canExpand) return false
        activeSegmentCount++
        mutated()
        return true
    }

    fun commitActive(surface: String, rightId: Int? = null): BunsetsuCommitResult {
        require(activeSegmentIndex == 0) { "Only a leading composing range can be committed" }
        require(activeSegmentCount > 0 && surface.isNotEmpty()) { "No active bunsetsu" }
        val committedReading = activeReading
        val boundary = ConversionText.scalarCount(committedReading)
        val committedRightId = rightId ?: activeRightId
        val selectedPrefix = conversionPaths.asSequence().map { path ->
            path.takeWhile { it.end <= boundary }
        }.firstOrNull { prefix ->
            prefix.isNotEmpty() && prefix.last().end == boundary &&
                prefix.joinToString("") { it.surface } == surface &&
                (rightId == null || prefix.last().rightId == rightId)
        }

        repeat(activeSegmentCount) { composingSegments.removeAt(0) }
        committedSegments += CommittedBunsetsu(committedReading, surface, committedRightId)
        previousRightId = committedRightId
        previousContextSurface = selectedPrefix?.let {
            KanaKanjiConverter.contextSurfaceAfter(previousContextSurface, it)
        }

        remainingSegmentCandidates = rebaseSuffix(remainingSegmentCandidates, boundary)
        conversionPaths = conversionPaths.mapNotNull { path ->
            rebaseSuffix(path, boundary).takeIf { it.isNotEmpty() }
        }
        activeSegmentIndex = 0
        activeSegmentCount = if (composingSegments.isEmpty()) 0 else 1
        mutated()
        return BunsetsuCommitResult(surface, remainingReading)
    }

    /** Delete from the uncommitted suffix without discarding committed context. */
    fun deleteLastCodePoint(): String {
        val lastIndex = composingSegments.lastIndex
        if (lastIndex < 0) return ""
        val segment = composingSegments[lastIndex]
        if (segment.surface != segment.reading) return remainingReading
        val end = segment.reading.offsetByCodePoints(segment.reading.length, -1)
        val shortened = segment.reading.substring(0, end)
        if (shortened.isEmpty()) composingSegments.removeAt(lastIndex)
        else composingSegments[lastIndex] = BunsetsuSegment(shortened)

        val scalarCount = ConversionText.scalarCount(remainingReading)
        remainingSegmentCandidates = remainingSegmentCandidates
            .takeIf { path -> path.lastOrNull()?.end == scalarCount }
            .orEmpty()
        conversionPaths = conversionPaths.filter { it.lastOrNull()?.end == scalarCount }
        activeSegmentCount = activeSegmentCount.coerceAtMost(composingSegments.size)
        if (composingSegments.isNotEmpty() && activeSegmentCount == 0) activeSegmentCount = 1
        mutated()
        return remainingReading
    }

    private fun mutated() {
        candidates = emptyList()
        generation++
    }

    private fun activeSegments(): List<BunsetsuSegment> {
        if (activeSegmentCount == 0) return emptyList()
        return composingSegments.subList(
            activeSegmentIndex,
            (activeSegmentIndex + activeSegmentCount).coerceAtMost(composingSegments.size),
        )
    }

    companion object {
        /** Extract candidates and their retained suffix paths from full conversion results. */
        fun plan(
            reading: String,
            conversions: List<ConversionResult>,
            requestedBoundary: Int? = null,
            allowSingle: Boolean = false,
        ): BunsetsuConversionPlan? {
            data class LeadingCandidate(
                val conversion: ConversionResult,
                val firstReading: String,
                val firstSurface: String,
                val rightId: Int,
                val copySegments: Int,
            )

            val totalLength = ConversionText.scalarCount(reading)
            val utf16Boundaries = ConversionText.utf16Boundaries(reading)
            val leadingCandidates = conversions.mapNotNull { conversion ->
                val natural = fromConversionSegments(reading, conversion.segments)
                if (natural.isEmpty()) return@mapNotNull null
                val boundary = requestedBoundary
                    ?: ConversionText.scalarCount(natural.first().reading)
                if (!allowSingle && requestedBoundary == null && boundary >= totalLength) {
                    return@mapNotNull null
                }
                if (boundary !in 1..totalLength) return@mapNotNull null
                val firstSegments = conversion.segments.takeWhile { it.end <= boundary }
                if (firstSegments.isEmpty() || firstSegments.last().end != boundary) {
                    return@mapNotNull null
                }
                LeadingCandidate(
                    conversion = conversion,
                    firstReading = reading.substring(utf16Boundaries[0], utf16Boundaries[boundary]),
                    firstSurface = firstSegments.joinToString("") { it.surface },
                    rightId = firstSegments.last().rightId,
                    copySegments = conversion.segments.count { it.isCopy },
                )
            }.sortedWith(
                compareBy<LeadingCandidate> { it.conversion.cost }
                    .thenBy { it.copySegments }
                    .thenByDescending { ConversionText.scalarCount(it.firstReading) }
                    .thenBy { it.conversion.segments.size }
                    .thenBy { it.firstSurface },
            )

            val first = leadingCandidates.firstOrNull() ?: return null
            val candidates = LinkedHashMap<Triple<String, String, Int>, BunsetsuCandidateOption>()
            leadingCandidates.forEach { candidate ->
                val option = BunsetsuCandidateOption(
                    surface = candidate.firstSurface,
                    reading = candidate.firstReading,
                    rightId = candidate.rightId,
                    segments = candidate.conversion.segments,
                )
                candidates.putIfAbsent(
                    Triple(option.surface, option.reading, option.rightId),
                    option,
                )
            }
            return BunsetsuConversionPlan(
                initialSegments = first.conversion.segments,
                candidates = candidates.values.toList(),
                activeLength = ConversionText.scalarCount(first.firstReading),
            )
        }

        fun create(reading: String, plan: BunsetsuConversionPlan): BunsetsuComposition {
            val segments = fromConversionSegments(reading, plan.initialSegments, plan.activeLength)
                .ifEmpty { fallbackSegments(reading) }
                .ifEmpty { listOf(BunsetsuSegment(reading)) }
            return BunsetsuComposition(
                originalReading = reading,
                initialSegments = segments,
                initialSegmentCandidates = plan.initialSegments,
                initialPaths = plan.candidates.map { it.segments }.filter { it.isNotEmpty() },
            )
        }

        fun create(reading: String, conversionSegments: List<ConversionSegment>): BunsetsuComposition {
            val segments = fromConversionSegments(reading, conversionSegments)
                .ifEmpty { fallbackSegments(reading) }
                .ifEmpty { listOf(BunsetsuSegment(reading)) }
            return BunsetsuComposition(
                originalReading = reading,
                initialSegments = segments,
                initialSegmentCandidates = conversionSegments,
                initialPaths = listOf(conversionSegments).filter { it.isNotEmpty() },
            )
        }

        private fun fromConversionSegments(
            reading: String,
            segments: List<ConversionSegment>,
            forcedLeadingBoundary: Int? = null,
        ): List<BunsetsuSegment> {
            val scalarCount = ConversionText.scalarCount(reading)
            if (segments.isEmpty() || segments.first().start != 0 ||
                segments.last().end != scalarCount ||
                segments.zipWithNext().any { (left, right) -> left.end != right.start }
            ) {
                return emptyList()
            }

            val boundaries = buildList {
                if (forcedLeadingBoundary != null && forcedLeadingBoundary in 1..scalarCount) {
                    add(forcedLeadingBoundary)
                }
                segments.zipWithNext().forEach { (left, right) ->
                    if (closesBunsetsu(left) && startsContent(right) &&
                        left.end > (forcedLeadingBoundary ?: 0)
                    ) {
                        add(left.end)
                    }
                }
                add(scalarCount)
            }.distinct().sorted()

            val utf16Boundaries = ConversionText.utf16Boundaries(reading)
            var start = 0
            return boundaries.map { end ->
                val rightId = segments.lastOrNull { it.end == end }?.rightId ?: PosClass.COPY.id
                BunsetsuSegment(
                    reading = reading.substring(utf16Boundaries[start], utf16Boundaries[end]),
                    rightId = rightId,
                ).also { start = end }
            }.filter { it.reading.isNotEmpty() }
        }

        private fun rebaseSuffix(
            segments: List<ConversionSegment>,
            boundary: Int,
        ): List<ConversionSegment> {
            if (segments.isEmpty()) return emptyList()
            if (segments.none { it.end == boundary }) return emptyList()
            return segments.filter { it.start >= boundary }.map { segment ->
                segment.copy(start = segment.start - boundary, end = segment.end - boundary)
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

        /** Used only when conversion returns no trustworthy segment metadata. */
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
                        result += BunsetsuSegment(
                            reading.substring(start, end),
                            rightId = PosClass.PARTICLE.id,
                        )
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
