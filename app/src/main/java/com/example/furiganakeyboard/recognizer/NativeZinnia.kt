package com.example.furiganakeyboard.recognizer

internal object NativeZinnia {
    init {
        System.loadLibrary("furigana_zinnia")
    }

    external fun nativeCreate(modelPath: String): Long
    external fun nativeRecognize(
        handle: Long,
        width: Int,
        height: Int,
        strokes: Array<FloatArray>,
        limit: Int
    ): Array<RecognitionCandidate>
    external fun nativeDestroy(handle: Long)
}
