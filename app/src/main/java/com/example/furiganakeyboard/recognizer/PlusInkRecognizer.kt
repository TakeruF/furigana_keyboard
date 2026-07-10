package com.example.furiganakeyboard.recognizer

import android.content.Context

/**
 * Uses ML Kit when its optional model is ready and transparently falls back to
 * the bundled Zinnia model while downloading or after any failure.
 */
class PlusInkRecognizer internal constructor(
    private val primary: InkRecognizer,
    private val fallback: InkRecognizer
) : InkRecognizer {

    constructor(context: Context) : this(
        MlKitInkRecognizer(),
        ZinniaInkRecognizer(context)
    )

    private var primaryState = InkRecognizer.State.PREPARING
    private var fallbackState = InkRecognizer.State.PREPARING
    private var state = InkRecognizer.State.PREPARING
    private var closed = false

    override var onStateChanged: ((InkRecognizer.State) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(state)
        }

    init {
        primary.onStateChanged = {
            primaryState = it
            updateState()
        }
        fallback.onStateChanged = {
            fallbackState = it
            updateState()
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
        if (primaryState != InkRecognizer.State.READY) {
            fallback.recognize(ink, preContext, callback)
            return
        }
        primary.recognize(ink, preContext) { candidates ->
            if (candidates.isNotEmpty() || closed) callback(candidates)
            else fallback.recognize(ink, preContext, callback)
        }
    }

    private fun updateState() {
        val next = when {
            primaryState == InkRecognizer.State.READY -> InkRecognizer.State.READY
            fallbackState == InkRecognizer.State.READY -> InkRecognizer.State.READY
            primaryState == InkRecognizer.State.ERROR &&
                fallbackState == InkRecognizer.State.ERROR -> InkRecognizer.State.ERROR
            else -> InkRecognizer.State.PREPARING
        }
        if (next != state) {
            state = next
            if (!closed) onStateChanged?.invoke(next)
        }
    }

    override fun cancelPending() {
        primary.cancelPending()
        fallback.cancelPending()
    }

    override fun close() {
        if (closed) return
        closed = true
        primary.close()
        fallback.close()
    }
}
