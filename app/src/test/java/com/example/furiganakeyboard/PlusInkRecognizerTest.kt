package com.example.furiganakeyboard

import com.example.furiganakeyboard.recognizer.HandwritingInk
import com.example.furiganakeyboard.recognizer.InkRecognizer
import com.example.furiganakeyboard.recognizer.PlusInkRecognizer
import com.example.furiganakeyboard.recognizer.RecognitionCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class PlusInkRecognizerTest {
    private val ink = HandwritingInk().apply {
        strokes += HandwritingInk.Stroke().also {
            it.points += HandwritingInk.Point(1f, 2f, 3L)
        }
    }

    @Test
    fun usesPlusCandidatesWhenModelIsReady() {
        val primary = FakeRecognizer(InkRecognizer.State.READY, listOf(candidate("日")))
        val fallback = FakeRecognizer(InkRecognizer.State.READY, listOf(candidate("目")))
        val recognizer = PlusInkRecognizer(primary, fallback)

        var result = emptyList<RecognitionCandidate>()
        recognizer.recognize(ink, "日本") { result = it }

        assertEquals(listOf("日"), result.map { it.text })
        assertEquals(1, primary.calls)
        assertEquals("日本", primary.lastPreContext)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun usesBundledFallbackWhilePlusIsUnavailable() {
        val primary = FakeRecognizer(InkRecognizer.State.ERROR, emptyList())
        val fallback = FakeRecognizer(InkRecognizer.State.READY, listOf(candidate("漢")))
        val recognizer = PlusInkRecognizer(primary, fallback)

        var result = emptyList<RecognitionCandidate>()
        recognizer.recognize(ink, "") { result = it }

        assertEquals(listOf("漢"), result.map { it.text })
        assertEquals(0, primary.calls)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun fallsBackWhenPlusReturnsNoSingleCharacterCandidate() {
        val primary = FakeRecognizer(InkRecognizer.State.READY, emptyList())
        val fallback = FakeRecognizer(InkRecognizer.State.READY, listOf(candidate("字")))
        val recognizer = PlusInkRecognizer(primary, fallback)

        var result = emptyList<RecognitionCandidate>()
        recognizer.recognize(ink, "") { result = it }

        assertEquals(listOf("字"), result.map { it.text })
        assertEquals(1, primary.calls)
        assertEquals(1, fallback.calls)
    }

    private fun candidate(text: String) = RecognitionCandidate(text, 1f)

    private class FakeRecognizer(
        initialState: InkRecognizer.State,
        private val result: List<RecognitionCandidate>
    ) : InkRecognizer {
        private var state = initialState
        var calls = 0
        var lastPreContext = ""

        override var onStateChanged: ((InkRecognizer.State) -> Unit)? = null
            set(value) {
                field = value
                value?.invoke(state)
            }

        override fun recognize(
            ink: HandwritingInk,
            preContext: String,
            callback: (List<RecognitionCandidate>) -> Unit
        ) {
            calls += 1
            lastPreContext = preContext
            callback(result)
        }
    }
}
