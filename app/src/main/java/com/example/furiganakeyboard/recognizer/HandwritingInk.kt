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
    fun snapshot(canvasWidth: Int, canvasHeight: Int): HandwritingInk = HandwritingInk().also { copy ->
        copy.width = canvasWidth.coerceAtLeast(1)
        copy.height = canvasHeight.coerceAtLeast(1)
        strokes.forEach { sourceStroke ->
            copy.strokes += Stroke().also { it.points += sourceStroke.points }
        }
    }
}
