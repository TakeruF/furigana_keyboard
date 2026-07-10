package com.example.furiganakeyboard.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.recognizer.HandwritingInk
import com.example.furiganakeyboard.recognizer.SideBySideInkSegmenter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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

    /** Called as soon as new ink makes an in-flight recognition result stale. */
    var onInkChanged: (() -> Unit)? = null

    /**
     * Called on pen-down when results for the current ink were already shown.
     * Return true to treat the new stroke as a NEW character: the host commits
     * the pending candidate and this view clears its ink first.
     */
    var onNewCharacterGate: (() -> Boolean)? = null

    // ---- recognition ink (raw points) ----
    private val ink = HandwritingInk()
    private var activeStroke: HandwritingInk.Stroke? = null

    // Completed and active segments are drawn once into this retained buffer.
    private var renderBitmap: Bitmap? = null
    private var renderCanvas: Canvas? = null
    private var hasRenderPoint = false

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
    private val characterGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ContextCompat.getColor(context, R.color.kbd_hint)
        alpha = 90
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(6f)), 0f)
    }
    private val hintText = context.getString(R.string.hint_write)

    private val handler = Handler(Looper.getMainLooper())
    private val recognizeRunnable = Runnable {
        if (!ink.isEmpty) {
            onRecognize?.invoke(ink.snapshot(width, height, MAX_POINTS_PER_STROKE))
        }
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
        redrawInk()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        renderBitmap?.recycle()
        renderBitmap = if (w > 0 && h > 0) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) else null
        renderCanvas = renderBitmap?.let(::Canvas)
        ink.width = w.coerceAtLeast(1)
        ink.height = h.coerceAtLeast(1)
        redrawInk()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val verticalPadding = dp(12f)
        canvas.drawLine(centerX, verticalPadding, centerX, height - verticalPadding, characterGuidePaint)
        // Faint hint while the pad is empty (like WeChat's 手写区域 hint).
        if (ink.isEmpty && activeStroke == null) {
            canvas.drawText(
                hintText,
                width / 2f,
                height / 2f - (hintPaint.ascent() + hintPaint.descent()) / 2f,
                hintPaint
            )
            return
        }
        renderBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(recognizeRunnable)
                onInkChanged?.invoke()
                // Continuous handwriting: results already shown → maybe commit
                // the previous input and start fresh. Ink beginning clearly in
                // the right-hand slot instead extends the current input to two
                // side-by-side characters.
                if (resultsDelivered && !ink.isEmpty) {
                    val beginsSecondCharacter =
                        SideBySideInkSegmenter.isSecondCharacterStart(ink, event.x)
                    if (!beginsSecondCharacter && onNewCharacterGate?.invoke() == true) {
                        clearInkOnly()
                    }
                }
                resultsDelivered = false

                Haptics.tick(this) // subtle pen-down feedback

                activeStroke = HandwritingInk.Stroke()
                hasRenderPoint = false
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
                activeStroke = null
                hasRenderPoint = false
                // Debounce so multi-stroke characters are recognized as a whole.
                handler.removeCallbacks(recognizeRunnable)
                handler.postDelayed(recognizeRunnable, RECOGNIZE_DELAY_MS)
            }
        }
        return true
    }

    /** Append a point to both the recognition ink and the rendered stroke. */
    private fun addPoint(x: Float, y: Float, t: Long) {
        activeStroke?.points?.add(HandwritingInk.Point(x, y, t))
        val canvas = renderCanvas ?: return
        if (!hasRenderPoint) {
            canvas.drawCircle(x, y, lastW / 2f, dotPaint)
            hasRenderPoint = true
            invalidateSegment(x, y, x, y, lastW)
            lastX = x
            lastY = y
            lastT = t
            return
        }
        val dist = hypot((x - lastX).toDouble(), (y - lastY).toDouble()).toFloat()
        val dt = max(1L, t - lastT)
        // Speed (px/ms) → width: slower strokes are thicker, like a brush.
        val norm = (dist / dt / SPEED_FOR_MIN_WIDTH).coerceIn(0f, 1f)
        val target = maxWidth - (maxWidth - minWidth) * norm
        lastW += (target - lastW) * WIDTH_SMOOTHING
        strokePaint.strokeWidth = lastW
        canvas.drawLine(lastX, lastY, x, y, strokePaint)
        invalidateSegment(lastX, lastY, x, y, lastW)
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
        activeStroke = null
        hasRenderPoint = false
        renderCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        invalidate()
    }

    private fun invalidateSegment(x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
        val pad = width + dp(2f)
        postInvalidateOnAnimation(
            floor(min(x1, x2) - pad).toInt(),
            floor(min(y1, y2) - pad).toInt(),
            ceil(max(x1, x2) + pad).toInt(),
            ceil(max(y1, y2) + pad).toInt()
        )
    }

    private fun redrawInk() {
        val canvas = renderCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        strokePaint.strokeWidth = maxWidth * 0.75f
        ink.strokes.forEach { stroke ->
            val points = stroke.points
            if (points.size == 1) {
                canvas.drawCircle(points[0].x, points[0].y, strokePaint.strokeWidth / 2f, dotPaint)
            } else {
                for (index in 1 until points.size) {
                    canvas.drawLine(
                        points[index - 1].x,
                        points[index - 1].y,
                        points[index].x,
                        points[index].y,
                        strokePaint
                    )
                }
            }
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )

    companion object {
        // Delay after finger-up before recognizing, to allow the next stroke.
        private const val RECOGNIZE_DELAY_MS = 450L
        private const val MAX_POINTS_PER_STROKE = 256
        // Speed (px/ms) at which the stroke reaches its minimum width.
        private const val SPEED_FOR_MIN_WIDTH = 1.5f
        // Low-pass factor for width changes (avoids jitter).
        private const val WIDTH_SMOOTHING = 0.3f
    }
}
