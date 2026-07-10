package com.example.furiganakeyboard.recognizer

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
     * thread with an ordered list of candidate strings (best first), or an empty
     * list on failure.
     */
    fun recognize(ink: HandwritingInk, callback: (List<String>) -> Unit)

    /** Release any native/model resources. Safe to call multiple times. */
    fun close() {}
}
