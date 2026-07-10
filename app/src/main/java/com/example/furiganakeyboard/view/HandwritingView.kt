package com.example.furiganakeyboard.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.recognizer.HandwritingInk
import kotlin.math.hypot
import kotlin.math.max

/**
 * Finger-drawing surface, visually modeled on WeChat IME's handwriting pad.
 *
 * - Renders ink with velocity-based stroke width (slow = thick, fast = thin)
 *   for a brush-like feel, using touch history points for smoothness.
 * - Shows a faint hint text while empty.
 * - Accumulates raw points into a [HandwritingInk] and fires [onRecognize]
 *   (debounced) after the user lifts the finger.
 * - Supports "continuous handwriting": once recognition results have been
 *   delivered ([markResultsDelivered]), the next pen-down asks
 *   [onNewCharacterGate] whether to auto-commit and start a fresh character.
 */
class HandwritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Called (debounced) after the user finishes a stroke. */
    var onRecognize: ((HandwritingInk) -> Unit)? = null

    /**
     * Called on pen-down when results for the current ink were already shown.
     * Return true to treat the new stroke as a NEW character: the host commits
     * the pending candidate and this view clears its ink first.
     */
    var onNewCharacterGate: (() -> Boolean)? = null

    // ---- recognition ink (raw points) ----
    private val ink = HandwritingInk()
    private var activeStroke: HandwritingInk.Stroke? = null

    // ---- rendered ink (points + per-point width) ----
    private class RenderPoint(val x: Float, val y: Float, val w: Float)
    private class RenderStroke { val pts = mutableListOf<RenderPoint>() }

    private val renderStrokes = mutableListOf<RenderStroke>()
    private var activeRender: RenderStroke? = null

    // Velocity → width state for the stroke in progress.
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var lastW = 0f

    private val minWidth = dp(2.5f)
    private val maxWidth = dp(6f)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.kbd_stroke)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.kbd_stroke)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(15f)
        color = ContextCompat.getColor(context, R.color.kbd_hint)
        textLocale = java.util.Locale.JAPAN // Japanese glyph variants
    }
    private val hintText = context.getString(R.string.hint_write)

    private val handler = Handler(Looper.getMainLooper())
    private val recognizeRunnable = Runnable {
        if (!ink.isEmpty) onRecognize?.invoke(ink.snapshot(width, height))
    }

    // True once the host displayed candidates for the current ink.
    private var resultsDelivered = false

    /** Host calls this when candidates for the current ink are on screen. */
    fun markResultsDelivered() {
        resultsDelivered = true
    }

    /** Change the ink color (defaults to the theme stroke color). */
    fun setStrokeColor(color: Int) {
        strokePaint.color = color
        dotPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Faint hint while the pad is empty (like WeChat's 手写区域 hint).
        if (ink.isEmpty && activeRender == null) {
            canvas.drawText(
                hintText,
                width / 2f,
                height / 2f - (hintPaint.ascent() + hintPaint.descent()) / 2f,
                hintPaint
            )
            return
        }
        for (stroke in renderStrokes) drawStroke(canvas, stroke)
        activeRender?.let { drawStroke(canvas, it) }
    }

    /** Draw one stroke as width-interpolated round-cap segments. */
    private fun drawStroke(canvas: Canvas, stroke: RenderStroke) {
        val pts = stroke.pts
        if (pts.isEmpty()) return
        if (pts.size == 1) {
            // A tap: draw a dot.
            canvas.drawCircle(pts[0].x, pts[0].y, pts[0].w / 2f, dotPaint)
            return
        }
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            strokePaint.strokeWidth = (a.w + b.w) / 2f
            canvas.drawLine(a.x, a.y, b.x, b.y, strokePaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(recognizeRunnable)
                // Continuous handwriting: results already shown → maybe commit
                // the previous character and start a fresh one.
                if (resultsDelivered && !ink.isEmpty) {
                    if (onNewCharacterGate?.invoke() == true) clearInkOnly()
                }
                resultsDelivered = false

                Haptics.tick(this) // subtle pen-down feedback

                activeStroke = HandwritingInk.Stroke()
                activeRender = RenderStroke()
                lastX = event.x
                lastY = event.y
                lastT = event.eventTime
                lastW = maxWidth * 0.75f // starting width before velocity kicks in
                addPoint(event.x, event.y, event.eventTime)
                // Don't let the parent steal the gesture.
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                // Include historical points for a smoother line.
                for (h in 0 until event.historySize) {
                    addPoint(
                        event.getHistoricalX(h),
                        event.getHistoricalY(h),
                        event.getHistoricalEventTime(h)
                    )
                }
                addPoint(event.x, event.y, event.eventTime)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                addPoint(event.x, event.y, event.eventTime)
                activeStroke?.let { ink.strokes.add(it) }
                activeRender?.let { renderStrokes.add(it) }
                activeStroke = null
                activeRender = null
                // Debounce so multi-stroke characters are recognized as a whole.
                handler.removeCallbacks(recognizeRunnable)
                handler.postDelayed(recognizeRunnable, RECOGNIZE_DELAY_MS)
            }
        }
        invalidate()
        return true
    }

    /** Append a point to both the recognition ink and the rendered stroke. */
    private fun addPoint(x: Float, y: Float, t: Long) {
        activeStroke?.points?.add(HandwritingInk.Point(x, y, t))
        val render = activeRender ?: return
        val dist = hypot((x - lastX).toDouble(), (y - lastY).toDouble()).toFloat()
        val dt = max(1L, t - lastT)
        // Speed (px/ms) → width: slower strokes are thicker, like a brush.
        val norm = (dist / dt / SPEED_FOR_MIN_WIDTH).coerceIn(0f, 1f)
        val target = maxWidth - (maxWidth - minWidth) * norm
        lastW += (target - lastW) * WIDTH_SMOOTHING
        render.pts.add(RenderPoint(x, y, lastW))
        lastX = x
        lastY = y
        lastT = t
    }

    /** Erase all ink and cancel any pending recognition. */
    fun clear() {
        handler.removeCallbacks(recognizeRunnable)
        clearInkOnly()
        resultsDelivered = false
    }

    /** Reset ink/rendering without touching timers or flags. */
    private fun clearInkOnly() {
        ink.strokes.clear()
        renderStrokes.clear()
        activeStroke = null
        activeRender = null
        invalidate()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )

    companion object {
        // Delay after finger-up before recognizing, to allow the next stroke.
        private const val RECOGNIZE_DELAY_MS = 650L
        // Speed (px/ms) at which the stroke reaches its minimum width.
        private const val SPEED_FOR_MIN_WIDTH = 1.5f
        // Low-pass factor for width changes (avoids jitter).
        private const val WIDTH_SMOOTHING = 0.3f
    }
}
