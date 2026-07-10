package com.example.furiganakeyboard

import com.example.furiganakeyboard.recognizer.HandwritingInk
import org.junit.Assert.assertEquals
import org.junit.Test

class HandwritingInkTest {
    @Test
    fun snapshotCapsPointsAndPreservesEndpoints() {
        val ink = HandwritingInk()
        ink.strokes += HandwritingInk.Stroke().also { stroke ->
            repeat(1_000) { index ->
                stroke.points += HandwritingInk.Point(index.toFloat(), (index * 2).toFloat(), index.toLong())
            }
        }

        val snapshot = ink.snapshot(320, 240, maxPointsPerStroke = 256)

        assertEquals(256, snapshot.strokes.single().points.size)
        assertEquals(ink.strokes.single().points.first(), snapshot.strokes.single().points.first())
        assertEquals(ink.strokes.single().points.last(), snapshot.strokes.single().points.last())
        assertEquals(320, snapshot.width)
        assertEquals(240, snapshot.height)
    }

    @Test
    fun shortStrokesAreCopiedWithoutResampling() {
        val ink = HandwritingInk()
        ink.strokes += HandwritingInk.Stroke().also { stroke ->
            stroke.points += HandwritingInk.Point(1f, 2f, 3L)
            stroke.points += HandwritingInk.Point(4f, 5f, 6L)
        }

        assertEquals(ink.strokes.single().points, ink.snapshot(1, 1, 256).strokes.single().points)
    }
}
