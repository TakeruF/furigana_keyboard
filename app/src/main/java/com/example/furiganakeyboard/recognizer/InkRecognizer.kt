package com.example.furiganakeyboard.recognizer

data class RecognitionCandidate(val text: String, val score: Float)

/**
 * Abstraction over a handwriting recognition engine.
 *
 * Recognition always runs locally against the model bundled in the APK.
 */
interface InkRecognizer {

    enum class State { PREPARING, READY, ERROR }

    var onStateChanged: ((State) -> Unit)?

    /**
     * Recognize [ink] asynchronously. [callback] is always invoked on the main
     * thread with candidates and their native scores (best first), or an empty
     * list on failure. Higher scores are better.
     */
    fun recognize(ink: HandwritingInk, callback: (List<RecognitionCandidate>) -> Unit)

    /** Release any native/model resources. Safe to call multiple times. */
    fun close() {}
}
