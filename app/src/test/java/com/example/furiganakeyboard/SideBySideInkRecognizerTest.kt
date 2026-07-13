package com.example.furiganakeyboard

import com.example.furiganakeyboard.recognizer.HandwritingInk
import com.example.furiganakeyboard.recognizer.InkRecognizer
import com.example.furiganakeyboard.recognizer.RecognitionCandidate
import com.example.furiganakeyboard.recognizer.SideBySideInkRecognizer
import com.example.furiganakeyboard.recognizer.SideBySideInkSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class SideBySideInkRecognizerTest {
    @Test
    fun splitsTwoCharactersWrittenAcrossThePad() {
        val ink = sideBySideInk()

        val segments = SideBySideInkSegmenter.split(ink)

        assertEquals(2, segments.size)
        assertEquals(2, segments[0].strokes.size)
        assertEquals(2, segments[1].strokes.size)
        assertEquals(segments[0].width, segments[0].height)
        assertEquals(segments[1].width, segments[1].height)
    }

    @Test
    fun keepsCompactSingleCharacterTogether() {
        val ink = HandwritingInk().apply {
            width = 400
            height = 240
            strokes += stroke(135f, 120f, 185f, 40f)
            strokes += stroke(205f, 40f, 255f, 120f)
        }

        assertEquals(1, SideBySideInkSegmenter.split(ink).size)
    }

    @Test
    fun rightSlotStrokeContinuesResultAsSecondCharacter() {
        val firstCharacter = HandwritingInk().apply {
            width = 400
            height = 240
            strokes += stroke(35f, 30f, 130f, 210f)
        }

        assertTrue(SideBySideInkSegmenter.isSecondCharacterStart(firstCharacter, 270f))
        assertFalse(SideBySideInkSegmenter.isSecondCharacterStart(firstCharacter, 150f))
    }

    @Test
    fun deletesSideBySideInkOneCharacterAtATime() {
        val ink = sideBySideInk()

        assertTrue(SideBySideInkSegmenter.removeLastCharacter(ink))
        assertEquals(2, ink.strokes.size)
        assertTrue(SideBySideInkSegmenter.removeLastCharacter(ink))
        assertTrue(ink.isEmpty)
        assertFalse(SideBySideInkSegmenter.removeLastCharacter(ink))
    }

    @Test
    fun deletesAllStrokesOfACompactSingleCharacter() {
        val ink = HandwritingInk().apply {
            width = 400
            height = 240
            strokes += stroke(135f, 120f, 185f, 40f)
            strokes += stroke(205f, 40f, 255f, 120f)
        }

        assertTrue(SideBySideInkSegmenter.removeLastCharacter(ink))
        assertTrue(ink.isEmpty)
    }

    @Test
    fun combinesLeftAndRightRecognitionCandidatesInReadingOrder() {
        val delegate = FakeRecognizer(
            listOf(candidate("日", 10f), candidate("目", 9f)),
            listOf(candidate("本", 10f), candidate("木", 9f))
        )
        val recognizer = SideBySideInkRecognizer(delegate)
        var result = emptyList<RecognitionCandidate>()

        recognizer.recognize(sideBySideInk(), "前") { result = it }

        assertEquals("日本", result.first().text)
        assertTrue(result.any { it.text == "目木" })
        assertEquals(listOf("前", "前日"), delegate.contexts)
    }

    private fun sideBySideInk() = HandwritingInk().apply {
        width = 400
        height = 240
        strokes += stroke(35f, 35f, 125f, 200f)
        strokes += stroke(45f, 115f, 120f, 115f)
        strokes += stroke(260f, 35f, 350f, 200f)
        strokes += stroke(270f, 115f, 345f, 115f)
    }

    private fun stroke(vararg coordinates: Float) = HandwritingInk.Stroke().also { stroke ->
        coordinates.toList().chunked(2).forEachIndexed { index, point ->
            stroke.points += HandwritingInk.Point(point[0], point[1], index.toLong())
        }
    }

    private fun candidate(text: String, score: Float) = RecognitionCandidate(text, score)

    private class FakeRecognizer(vararg responses: List<RecognitionCandidate>) : InkRecognizer {
        private val responses = ArrayDeque(responses.toList())
        val contexts = mutableListOf<String>()

        override var onStateChanged: ((InkRecognizer.State) -> Unit)? = null
            set(value) {
                field = value
                value?.invoke(InkRecognizer.State.READY)
            }

        override fun recognize(
            ink: HandwritingInk,
            preContext: String,
            callback: (List<RecognitionCandidate>) -> Unit
        ) {
            contexts += preContext
            callback(responses.removeFirst())
        }
    }
}
