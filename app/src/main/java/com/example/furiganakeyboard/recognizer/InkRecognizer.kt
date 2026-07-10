package com.example.furiganakeyboard.recognizer

data class RecognitionCandidate(val text: String, val score: Float)

/**
 * Abstraction over a handwriting recognition engine.
 *
 * Recognition always runs locally. Implementations may use either the model
 * bundled in the APK or an optional model downloaded to the device.
 */
interface InkRecognizer {

    enum class State { PREPARING, READY, ERROR }

    var onStateChanged: ((State) -> Unit)?

    /**
     * Recognize [ink] asynchronously. [callback] is always invoked on the main
     * thread with candidates and their native scores (best first), or an empty
     * list on failure. Higher scores are better.
     */
    fun recognize(
        ink: HandwritingInk,
        preContext: String = "",
        callback: (List<RecognitionCandidate>) -> Unit
    )

    /** Discard callbacks from recognition work that is no longer relevant. */
    fun cancelPending() {}

    /** Release any native/model resources. Safe to call multiple times. */
    fun close() {}
}
