package com.example.furiganakeyboard.recognizer

/** Recognizer that produced a piece of handwriting evidence. */
enum class RecognitionSource {
    ZINNIA,
    ML_KIT,
    SIDE_BY_SIDE,
    LEGACY_NATIVE;

    internal val isRawRecognizer: Boolean
        get() = this != SIDE_BY_SIDE
}

/** Raw recognizer evidence retained after score normalization and reranking. */
data class RecognitionEvidence(
    val source: RecognitionSource,
    val rawScore: Float?,
    val rawRank: Int?,
    val components: List<RecognitionComponentEvidence> = emptyList()
) {
    val isRecognizerRawTop: Boolean
        get() = source.isRawRecognizer && rawRank == 0
}

/** Evidence for one character in a composed, side-by-side candidate. */
data class RecognitionComponentEvidence(
    val position: Int,
    val text: String,
    val shapeCost: Float,
    val source: RecognitionSource,
    val rawScore: Float?,
    val rawRank: Int?,
    val isRecognizerRawTop: Boolean
)

/**
 * A normalized handwriting candidate. [shapeCost] is always lower-is-better.
 *
 * The two-argument constructor is intentionally retained for the Zinnia JNI
 * boundary and legacy callers. Its [score] is raw higher-is-better evidence;
 * recognizer-facing code must normalize a batch before comparing candidates.
 */
data class RecognitionCandidate(
    val text: String,
    val shapeCost: Float,
    val evidence: RecognitionEvidence
) {
    constructor(text: String, score: Float) : this(
        text = text,
        shapeCost = Float.NaN,
        evidence = RecognitionEvidence(
            source = RecognitionSource.LEGACY_NATIVE,
            rawScore = score,
            rawRank = null
        )
    )

    /** Compatibility view for the JNI instrumentation test and old callers. */
    val score: Float
        get() = evidence.rawScore ?: -shapeCost

    val isRecognizerRawTop: Boolean
        get() = evidence.isRecognizerRawTop
}

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
     * thread with normalized candidates (best first), or an empty list on
     * failure. [RecognitionCandidate.shapeCost] is lower-is-better; native
     * score, original rank, source, and composition evidence remain available.
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
