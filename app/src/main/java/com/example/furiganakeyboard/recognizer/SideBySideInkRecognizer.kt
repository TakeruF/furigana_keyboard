package com.example.furiganakeyboard.recognizer

import kotlin.math.ceil
import kotlin.math.max

/**
 * Recognizes one character normally, or two characters written from left to
 * right on the same pad by recognizing each spatial segment and recombining
 * their candidates.
 */
class SideBySideInkRecognizer(
    private val delegate: InkRecognizer
) : InkRecognizer {
    private var generation = 0L
    private var closed = false
    private var state = InkRecognizer.State.PREPARING

    override var onStateChanged: ((InkRecognizer.State) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(state)
        }

    init {
        delegate.onStateChanged = { next ->
            state = next
            if (!closed) onStateChanged?.invoke(next)
        }
    }

    override fun recognize(
        ink: HandwritingInk,
        preContext: String,
        callback: (List<RecognitionCandidate>) -> Unit
    ) {
        if (closed) {
            callback(emptyList())
            return
        }
        val request = ++generation
        val segments = SideBySideInkSegmenter.split(ink)
        if (segments.size != 2) {
            delegate.recognize(ink, preContext) { values ->
                if (isCurrent(request)) callback(values)
            }
            return
        }

        delegate.recognize(segments[0], preContext) left@{ leftCandidates ->
            if (!isCurrent(request)) return@left
            if (leftCandidates.isEmpty()) {
                recognizeWholeInk(request, ink, preContext, callback)
                return@left
            }
            val rightContext = preContext + leftCandidates.first().text
            delegate.recognize(segments[1], rightContext) right@{ rightCandidates ->
                if (!isCurrent(request)) return@right
                if (rightCandidates.isEmpty()) {
                    recognizeWholeInk(request, ink, preContext, callback)
                    return@right
                }
                callback(combine(leftCandidates, rightCandidates))
            }
        }
    }

    private fun recognizeWholeInk(
        request: Long,
        ink: HandwritingInk,
        preContext: String,
        callback: (List<RecognitionCandidate>) -> Unit
    ) {
        delegate.recognize(ink, preContext) { values ->
            if (isCurrent(request)) callback(values)
        }
    }

    private fun combine(
        left: List<RecognitionCandidate>,
        right: List<RecognitionCandidate>
    ): List<RecognitionCandidate> = left.take(PER_CHARACTER_LIMIT).flatMapIndexed { leftIndex, l ->
        right.take(PER_CHARACTER_LIMIT).mapIndexed { rightIndex, r ->
            val rankBias = (PER_CHARACTER_LIMIT * 2 - leftIndex - rightIndex) * RANK_BIAS
            RecognitionCandidate(l.text + r.text, (l.score + r.score) / 2f + rankBias)
        }
    }.sortedByDescending { it.score }
        .distinctBy { it.text }
        .take(RESULT_LIMIT)

    private fun isCurrent(request: Long): Boolean = !closed && request == generation

    override fun cancelPending() {
        generation++
        delegate.cancelPending()
    }

    override fun close() {
        if (closed) return
        closed = true
        generation++
        delegate.close()
    }

    companion object {
        private const val PER_CHARACTER_LIMIT = 4
        private const val RESULT_LIMIT = 10
        private const val RANK_BIAS = 0.001f
    }
}

/** Spatial segmentation kept independent of Android so its thresholds are unit-testable. */
internal object SideBySideInkSegmenter {
    private data class Bounds(
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float
    ) {
        val centerX: Float get() = (minX + maxX) / 2f
        val width: Float get() = maxX - minX
        val height: Float get() = maxY - minY
    }

    fun split(ink: HandwritingInk): List<HandwritingInk> {
        val strokes = ink.strokes.filter { it.points.isNotEmpty() }
        if (strokes.size < 2 || ink.width <= 1) return listOf(ink)
        val canvasWidth = ink.width.toFloat()
        var best: Pair<Int, Float>? = null

        for (cut in 1 until strokes.size) {
            val left = bounds(strokes.subList(0, cut)) ?: continue
            val right = bounds(strokes.subList(cut, strokes.size)) ?: continue
            val gap = right.minX - left.maxX
            val overallSpan = right.maxX - left.minX
            val centerDistance = right.centerX - left.centerX
            val crossesMiddle = left.centerX < canvasWidth * LEFT_CENTER_MAX &&
                right.centerX > canvasWidth * RIGHT_CENTER_MIN
            val separated = gap >= max(MIN_GAP_PX, canvasWidth * MIN_GAP_RATIO)
            val wideEnough = overallSpan >= canvasWidth * MIN_TOTAL_SPAN_RATIO &&
                centerDistance >= canvasWidth * MIN_CENTER_DISTANCE_RATIO
            if (crossesMiddle && separated && wideEnough) {
                val score = gap + centerDistance * CENTER_DISTANCE_WEIGHT
                if (best == null || score > best.second) best = cut to score
            }
        }

        val cut = best?.first ?: return listOf(ink)
        return listOf(
            cropToSquare(strokes.subList(0, cut)),
            cropToSquare(strokes.subList(cut, strokes.size))
        )
    }

    /** Keep previous results alive when the next stroke clearly starts the right-hand slot. */
    fun isSecondCharacterStart(ink: HandwritingInk, x: Float): Boolean {
        val current = bounds(ink.strokes) ?: return false
        val canvasWidth = ink.width.toFloat().coerceAtLeast(1f)
        return current.centerX < canvasWidth * LEFT_CENTER_MAX &&
            current.maxX < canvasWidth * RIGHT_START_MAX &&
            x > canvasWidth * RIGHT_CENTER_MIN &&
            x - current.maxX >= max(MIN_GAP_PX, canvasWidth * MIN_GAP_RATIO)
    }

    private fun cropToSquare(strokes: List<HandwritingInk.Stroke>): HandwritingInk {
        val bounds = checkNotNull(bounds(strokes))
        val contentSize = max(bounds.width, bounds.height).coerceAtLeast(1f)
        val padding = max(MIN_CROP_PADDING_PX, contentSize * CROP_PADDING_RATIO)
        val side = contentSize + padding * 2f
        val offsetX = padding + (contentSize - bounds.width) / 2f - bounds.minX
        val offsetY = padding + (contentSize - bounds.height) / 2f - bounds.minY
        return HandwritingInk().also { output ->
            output.width = ceil(side).toInt().coerceAtLeast(1)
            output.height = output.width
            strokes.forEach { source ->
                output.strokes += HandwritingInk.Stroke().also { target ->
                    source.points.forEach { point ->
                        target.points += HandwritingInk.Point(
                            point.x + offsetX,
                            point.y + offsetY,
                            point.t
                        )
                    }
                }
            }
        }
    }

    private fun bounds(strokes: List<HandwritingInk.Stroke>): Bounds? {
        val points = strokes.asSequence().flatMap { it.points.asSequence() }
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var found = false
        points.forEach { point ->
            found = true
            minX = kotlin.math.min(minX, point.x)
            minY = kotlin.math.min(minY, point.y)
            maxX = kotlin.math.max(maxX, point.x)
            maxY = kotlin.math.max(maxY, point.y)
        }
        return if (found) Bounds(minX, minY, maxX, maxY) else null
    }

    private const val LEFT_CENTER_MAX = 0.48f
    private const val RIGHT_CENTER_MIN = 0.52f
    private const val RIGHT_START_MAX = 0.58f
    private const val MIN_GAP_RATIO = 0.035f
    private const val MIN_GAP_PX = 8f
    private const val MIN_TOTAL_SPAN_RATIO = 0.48f
    private const val MIN_CENTER_DISTANCE_RATIO = 0.25f
    private const val CENTER_DISTANCE_WEIGHT = 0.25f
    private const val CROP_PADDING_RATIO = 0.08f
    private const val MIN_CROP_PADDING_PX = 4f
}
