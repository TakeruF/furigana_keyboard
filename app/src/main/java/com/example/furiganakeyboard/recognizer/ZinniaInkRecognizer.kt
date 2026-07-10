package com.example.furiganakeyboard.recognizer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.furiganakeyboard.data.AssetInstaller
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Fully offline recognizer backed by the bundled Zinnia Japanese model. */
class ZinniaInkRecognizer(context: Context) : InkRecognizer {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "zinnia-recognizer").apply { isDaemon = true }
    }
    private val latestRequest = AtomicLong()

    @Volatile private var handle = 0L
    @Volatile private var closed = false
    @Volatile private var state = InkRecognizer.State.PREPARING

    override var onStateChanged: ((InkRecognizer.State) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(state)
        }

    init {
        worker.execute { prepare() }
    }

    private fun prepare(): Boolean {
        if (closed) return false
        if (handle != 0L) return true
        return try {
            postState(InkRecognizer.State.PREPARING)
            val model = AssetInstaller.ensure(
                appContext,
                MODEL_ASSET,
                MODEL_FILE,
                MODEL_SHA256
            )
            handle = NativeZinnia.nativeCreate(model.absolutePath)
            check(handle != 0L)
            postState(InkRecognizer.State.READY)
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to prepare offline handwriting model", error)
            postState(InkRecognizer.State.ERROR)
            false
        }
    }

    override fun recognize(
        ink: HandwritingInk,
        preContext: String,
        callback: (List<RecognitionCandidate>) -> Unit
    ) {
        if (ink.isEmpty || closed) {
            callback(emptyList())
            return
        }
        val request = latestRequest.incrementAndGet()
        worker.execute {
            val values = try {
                if (!prepare()) emptyList() else NativeZinnia.nativeRecognize(
                    handle,
                    ink.width,
                    ink.height,
                    ink.strokes.map { stroke ->
                        FloatArray(stroke.points.size * 2).also { points ->
                            stroke.points.forEachIndexed { index, point ->
                                points[index * 2] = point.x
                                points[index * 2 + 1] = point.y
                            }
                        }
                    }.toTypedArray(),
                    RESULT_LIMIT
                ).distinctBy { it.text }
            } catch (error: Throwable) {
                Log.e(TAG, "Offline recognition failed", error)
                postState(InkRecognizer.State.ERROR)
                emptyList()
            }
            main.post {
                if (!closed && request == latestRequest.get()) callback(values)
            }
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
        worker.execute {
            if (handle != 0L) NativeZinnia.nativeDestroy(handle)
            handle = 0L
        }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "ZinniaInkRecognizer"
        private const val MODEL_ASSET = "handwriting-ja.model"
        private const val MODEL_FILE = "handwriting-ja-v0.3.model"
        private const val MODEL_SHA256 =
            "d58618576d12c1ea38992606f30d19158a0a3beda3ac23bacccf1e48e651f2b2"
        private const val RESULT_LIMIT = 10
    }
}
