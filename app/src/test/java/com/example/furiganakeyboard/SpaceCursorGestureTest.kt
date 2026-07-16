package com.example.furiganakeyboard

import com.example.furiganakeyboard.view.SpaceCursorGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceCursorGestureTest {
    private val gesture = SpaceCursorGesture(
        SpaceCursorGesture.Config(
            longPressTimeoutMillis = 500,
            activationDistance = 10f,
            cursorStepDistancePixels = 8f,
        )
    )

    @Test
    fun shortTapEmitsExactlyOneTap() {
        assertTrue(gesture.onDown(20f, 1_000).isEmpty())

        assertEquals(listOf(SpaceCursorGesture.Effect.Tap), gesture.onUp(22f, 1_100, true))
        assertTrue(gesture.onUp(22f, 1_101, true).isEmpty())
        assertFalse(gesture.isTracking)
    }

    @Test
    fun longStationaryHoldDoesNotBecomeTapOrCursorMode() {
        gesture.onDown(20f, 1_000)

        assertTrue(gesture.onLongPressTimeout(1_500).isEmpty())
        assertTrue(gesture.onUp(20f, 1_600, true).isEmpty())
    }

    @Test
    fun earlyHorizontalDragStartsWhenLongPressTimeoutArrives() {
        gesture.onDown(20f, 1_000)
        assertTrue(gesture.onMove(31f, 1_100, true).isEmpty())

        assertEquals(
            listOf(SpaceCursorGesture.Effect.CursorModeStarted),
            gesture.onLongPressTimeout(1_500),
        )
        assertTrue(gesture.isCursorMode)
        assertEquals(
            listOf(SpaceCursorGesture.Effect.CursorStep(1)),
            gesture.onMove(39f, 1_510, true),
        )
        assertTrue(gesture.onUp(39f, 1_520, true).isEmpty())
    }

    @Test
    fun dragAfterHoldStartsCursorModeOnlyWhenHorizontalThresholdIsMet() {
        gesture.onDown(20f, 1_000)
        assertTrue(gesture.onLongPressTimeout(1_500).isEmpty())
        assertTrue(gesture.onMove(29f, 1_510, true).isEmpty())

        assertEquals(
            listOf(SpaceCursorGesture.Effect.CursorModeStarted),
            gesture.onMove(30f, 1_520, true),
        )
    }

    @Test
    fun cursorStepsAreDiscreteAndDirectionReversalHasNoStaleStep() {
        startCursor(anchorX = 30f)

        assertEquals(steps(1), gesture.onMove(38f, 1_510, true))
        assertEquals(steps(1), gesture.onMove(46f, 1_520, true))
        assertEquals(steps(-1), gesture.onMove(37f, 1_530, true))
        assertEquals(steps(-1), gesture.onMove(29f, 1_540, true))
    }

    @Test
    fun largePixelMovementIsReportedAsOneIntegerStepDelta() {
        startCursor(anchorX = 30f)

        assertEquals(steps(3), gesture.onMove(55f, 1_510, true))
    }

    @Test
    fun cancelAndPointerAdditionSuppressAllLaterActions() {
        gesture.onDown(20f, 1_000)
        assertTrue(gesture.onCancel().isEmpty())
        assertTrue(gesture.onUp(20f, 1_100, true).isEmpty())

        gesture.onDown(20f, 2_000)
        assertTrue(gesture.onPointerAdded().isEmpty())
        assertTrue(gesture.onUp(20f, 2_100, true).isEmpty())
    }

    @Test
    fun leavingKeyCancelsTapButCursorDragCanContinueOutside() {
        gesture.onDown(20f, 1_000)
        assertTrue(gesture.onMove(-5f, 1_100, false).isEmpty())
        assertTrue(gesture.onUp(20f, 1_200, true).isEmpty())

        gesture.onDown(20f, 2_000)
        assertEquals(
            listOf(SpaceCursorGesture.Effect.CursorModeStarted),
            gesture.onMove(31f, 2_500, false),
        )
        assertEquals(steps(1), gesture.onMove(39f, 2_510, false))
        assertTrue(gesture.onUp(39f, 2_520, false).isEmpty())
    }

    @Test
    fun movementThresholdWithoutLongPressDoesNotEmitSpace() {
        gesture.onDown(20f, 1_000)
        assertTrue(gesture.onMove(31f, 1_100, true).isEmpty())

        assertTrue(gesture.onUp(31f, 1_200, true).isEmpty())
    }

    @Test
    fun cursorModeNeverFallsBackToSpaceTapOnRelease() {
        startCursor(anchorX = 30f)

        assertEquals(steps(1), gesture.onMove(38f, 1_510, true))
        assertTrue(gesture.onUp(38f, 1_520, true).isEmpty())
        assertFalse(gesture.isTracking)
    }

    private fun startCursor(anchorX: Float) {
        gesture.onDown(20f, 1_000)
        assertEquals(
            listOf(SpaceCursorGesture.Effect.CursorModeStarted),
            gesture.onMove(anchorX, 1_500, true),
        )
    }

    private fun steps(value: Int) = listOf(SpaceCursorGesture.Effect.CursorStep(value))
}
