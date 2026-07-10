package com.example.furiganakeyboard.recognizer

/**
 * Framework-independent representation of handwriting input.
 *
 * The model is independent of the Zinnia JNI layer and can be snapshotted for
 * safe processing on the recognizer worker thread.
 */
class HandwritingInk {
    /** A single sampled point. [t] is retained for rendering/engine upgrades. */
    data class Point(val x: Float, val y: Float, val t: Long)

    /** A single continuous stroke (finger-down to finger-up). */
    class Stroke {
        val points = mutableListOf<Point>()
    }

    val strokes = mutableListOf<Stroke>()

    var width: Int = 1
    var height: Int = 1

    val isEmpty: Boolean get() = strokes.isEmpty()

    /** Immutable-by-convention deep copy safe to pass to a worker thread. */
    fun snapshot(
        canvasWidth: Int,
        canvasHeight: Int,
        maxPointsPerStroke: Int = Int.MAX_VALUE
    ): HandwritingInk = HandwritingInk().also { copy ->
        copy.width = canvasWidth.coerceAtLeast(1)
        copy.height = canvasHeight.coerceAtLeast(1)
        strokes.forEach { sourceStroke ->
            copy.strokes += Stroke().also {
                it.points += resample(sourceStroke.points, maxPointsPerStroke)
            }
        }
    }

    private fun resample(points: List<Point>, maximum: Int): List<Point> {
        if (maximum <= 1 || points.size <= maximum) return points
        if (maximum == 2) return listOf(points.first(), points.last())
        val lastIndex = points.lastIndex
        return List(maximum) { outputIndex ->
            val sourceIndex = (outputIndex.toLong() * lastIndex / (maximum - 1)).toInt()
            points[sourceIndex]
        }
    }
}
