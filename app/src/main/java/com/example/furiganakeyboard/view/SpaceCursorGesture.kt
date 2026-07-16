package com.example.furiganakeyboard.view

import kotlin.math.abs

/**
 * MotionEvent-independent state machine that converts horizontal pixels into abstract cursor
 * steps. It deliberately has no knowledge of text, Unicode, or grapheme boundaries.
 */
class SpaceCursorGesture(
    val config: Config,
) {
    data class Config(
        val longPressTimeoutMillis: Long,
        val activationDistance: Float,
        val cursorStepDistancePixels: Float,
    ) {
        init {
            require(longPressTimeoutMillis > 0)
            require(activationDistance > 0f && activationDistance.isFinite())
            require(cursorStepDistancePixels > 0f && cursorStepDistancePixels.isFinite())
        }
    }

    sealed interface Effect {
        data object Tap : Effect
        data object CursorModeStarted : Effect
        data class CursorStep(val deltaInSteps: Int) : Effect {
            init {
                require(deltaInSteps != 0)
            }
        }
    }

    private enum class Phase { IDLE, PRESSED, CURSOR }

    private var phase = Phase.IDLE
    private var downTimeMillis = 0L
    private var downX = 0f
    private var lastX = 0f
    private var cursorAnchorX = 0f
    private var tapEligible = false

    val isCursorMode: Boolean get() = phase == Phase.CURSOR
    val isTracking: Boolean get() = phase != Phase.IDLE

    fun onDown(x: Float, timeMillis: Long, insideKey: Boolean = true): List<Effect> {
        phase = Phase.PRESSED
        downTimeMillis = timeMillis
        downX = x
        lastX = x
        cursorAnchorX = x
        tapEligible = insideKey
        return emptyList()
    }

    fun onMove(x: Float, timeMillis: Long, insideKey: Boolean): List<Effect> {
        if (phase == Phase.IDLE) return emptyList()
        lastX = x
        if (!insideKey || abs(x - downX) >= config.activationDistance) {
            tapEligible = false
        }
        val started = maybeStartCursor(timeMillis)
        if (started.isNotEmpty()) return started
        return cursorStepIfNeeded()
    }

    /** Called by the view's delayed runnable so an early drag can activate after the hold time. */
    fun onLongPressTimeout(timeMillis: Long): List<Effect> {
        if (phase != Phase.PRESSED) return emptyList()
        return maybeStartCursor(timeMillis)
    }

    fun onUp(x: Float, timeMillis: Long, insideKey: Boolean): List<Effect> {
        if (phase == Phase.IDLE) return emptyList()
        lastX = x
        if (!insideKey || abs(x - downX) >= config.activationDistance) {
            tapEligible = false
        }
        val effects = buildList {
            addAll(maybeStartCursor(timeMillis))
            if (phase == Phase.CURSOR) {
                addAll(cursorStepIfNeeded())
            } else if (
                tapEligible &&
                insideKey &&
                timeMillis - downTimeMillis < config.longPressTimeoutMillis
            ) {
                add(Effect.Tap)
            }
        }
        reset()
        return effects
    }

    fun onCancel(): List<Effect> {
        reset()
        return emptyList()
    }

    /** A second pointer invalidates the complete gesture; its later UP cannot become a tap. */
    fun onPointerAdded(): List<Effect> = onCancel()

    private fun maybeStartCursor(timeMillis: Long): List<Effect> {
        if (
            phase != Phase.PRESSED ||
            timeMillis - downTimeMillis < config.longPressTimeoutMillis ||
            abs(lastX - downX) < config.activationDistance
        ) {
            return emptyList()
        }
        phase = Phase.CURSOR
        cursorAnchorX = lastX
        tapEligible = false
        return listOf(Effect.CursorModeStarted)
    }

    private fun cursorStepIfNeeded(): List<Effect> {
        if (phase != Phase.CURSOR) return emptyList()
        val deltaInSteps =
            ((lastX - cursorAnchorX) / config.cursorStepDistancePixels).toInt()
        if (deltaInSteps == 0) return emptyList()
        cursorAnchorX += deltaInSteps * config.cursorStepDistancePixels
        return listOf(Effect.CursorStep(deltaInSteps))
    }

    private fun reset() {
        phase = Phase.IDLE
        tapEligible = false
    }
}
