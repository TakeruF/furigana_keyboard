package com.example.furiganakeyboard.recognizer

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import java.util.concurrent.atomic.AtomicLong

/** Optional high-accuracy, on-device recognizer backed by Google ML Kit. */
class MlKitInkRecognizer : InkRecognizer {
    private val main = Handler(Looper.getMainLooper())
    private val latestRequest = AtomicLong()
    private val modelManager = RemoteModelManager.getInstance()

    private var model: DigitalInkRecognitionModel? = null
    private var engine: DigitalInkRecognizer? = null

    @Volatile private var closed = false
    @Volatile private var state = InkRecognizer.State.PREPARING

    override var onStateChanged: ((InkRecognizer.State) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(state)
        }

    init {
        prepare()
    }

    private fun prepare() {
        try {
            val identifier = checkNotNull(
                DigitalInkRecognitionModelIdentifier.fromLanguageTag(LANGUAGE_TAG)
            ) { "ML Kit has no Japanese digital-ink model" }
            val recognitionModel = DigitalInkRecognitionModel.builder(identifier).build()
            model = recognitionModel
            engine = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(recognitionModel).build()
            )
            postState(InkRecognizer.State.PREPARING)
            modelManager.isModelDownloaded(recognitionModel)
                .addOnSuccessListener { downloaded ->
                    if (closed) return@addOnSuccessListener
                    if (downloaded) {
                        postState(InkRecognizer.State.READY)
                    } else {
                        download(recognitionModel)
                    }
                }
                .addOnFailureListener(::failPreparation)
        } catch (error: Throwable) {
            failPreparation(error)
        }
    }

    private fun download(recognitionModel: DigitalInkRecognitionModel) {
        modelManager.download(recognitionModel, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (!closed) postState(InkRecognizer.State.READY)
            }
            .addOnFailureListener(::failPreparation)
    }

    private fun failPreparation(error: Exception) = failPreparation(error as Throwable)

    private fun failPreparation(error: Throwable) {
        Log.w(TAG, "Furigana Plus model is unavailable; using bundled recognition", error)
        postState(InkRecognizer.State.ERROR)
    }

    override fun recognize(
        ink: HandwritingInk,
        preContext: String,
        callback: (List<RecognitionCandidate>) -> Unit
    ) {
        val recognizer = engine
        if (ink.isEmpty || closed || state != InkRecognizer.State.READY || recognizer == null) {
            callback(emptyList())
            return
        }

        val request = latestRequest.incrementAndGet()
        val mlInk = Ink.builder().apply {
            ink.strokes.forEach { sourceStroke ->
                val stroke = Ink.Stroke.builder()
                sourceStroke.points.forEach { point ->
                    stroke.addPoint(Ink.Point.create(point.x, point.y, point.t))
                }
                addStroke(stroke.build())
            }
        }.build()
        val context = RecognitionContext.builder()
            .setPreContext(preContext.takeLast(MAX_PRE_CONTEXT))
            .setWritingArea(WritingArea(ink.width.toFloat(), ink.height.toFloat()))
            .build()

        recognizer.recognize(mlInk, context)
            .addOnSuccessListener { result ->
                val values = result.candidates.asSequence()
                    .map { it.text.trim() }
                    .filter { it.codePointCount(0, it.length) == 1 }
                    .map { RawRecognitionCandidate(it) }
                    .toList()
                    .let {
                        RecognitionScoreNormalizer.normalize(
                            it,
                            RecognitionSource.ML_KIT,
                            RawScoreSemantics.RANK_ONLY
                        )
                    }.take(RESULT_LIMIT)
                postResult(request, values, callback)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Furigana Plus recognition failed; using bundled recognition", error)
                postState(InkRecognizer.State.ERROR)
                postResult(request, emptyList(), callback)
            }
    }

    private fun postResult(
        request: Long,
        values: List<RecognitionCandidate>,
        callback: (List<RecognitionCandidate>) -> Unit
    ) {
        main.post {
            if (!closed && request == latestRequest.get()) callback(values)
        }
    }

    private fun postState(newState: InkRecognizer.State) {
        state = newState
        main.post { if (!closed) onStateChanged?.invoke(newState) }
    }

    override fun cancelPending() {
        latestRequest.incrementAndGet()
    }

    override fun close() {
        if (closed) return
        closed = true
        latestRequest.incrementAndGet()
        engine?.close()
        engine = null
        model = null
    }

    companion object {
        private const val TAG = "MlKitInkRecognizer"
        private const val LANGUAGE_TAG = "ja-JP"
        private const val MAX_PRE_CONTEXT = 20
        private const val RESULT_LIMIT = 10
    }
}
