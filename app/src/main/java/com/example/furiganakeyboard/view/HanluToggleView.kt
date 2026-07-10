package com.example.furiganakeyboard.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.example.furiganakeyboard.R

/** Compact, neutral pill toggle matching the understated controls used by Hanlu. */
class HanluToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private var thumbPosition = 0f
    private var animator: ValueAnimator? = null
    private var listener: ((Boolean) -> Unit)? = null

    var isChecked: Boolean = false
        private set

    init {
        isClickable = true
        isFocusable = true
        minimumWidth = dp(44)
        minimumHeight = dp(28)
        setPadding(0, 0, 0, 0)
        setLayerType(LAYER_TYPE_SOFTWARE, paint)
        setOnClickListener {
            val hapticsWereEnabled = Haptics.enabled
            if (hapticsWereEnabled) Haptics.selection(this)
            setChecked(!isChecked, animate = true)
            listener?.invoke(isChecked)
            if (!hapticsWereEnabled && Haptics.enabled) Haptics.selection(this)
        }
    }

    fun setChecked(value: Boolean, animate: Boolean = false) {
        if (isChecked == value && thumbPosition == if (value) 1f else 0f) return
        isChecked = value
        animator?.cancel()
        val target = if (value) 1f else 0f
        if (!animate || !isLaidOut) {
            thumbPosition = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(thumbPosition, target).apply {
            duration = 140L
            addUpdateListener {
                thumbPosition = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    fun setOnCheckedChangeListener(value: (Boolean) -> Unit) {
        listener = value
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(44), widthMeasureSpec),
            resolveSize(dp(28), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        val inset = dp(1).toFloat()
        track.set(inset, inset, width - inset, height - inset)
        paint.color = color(if (isChecked) R.color.settings_toggle_on else R.color.settings_toggle_off)
        canvas.drawRoundRect(track, height / 2f, height / 2f, paint)

        val radius = dp(11).toFloat()
        val start = dp(3).toFloat() + radius
        val end = width - dp(3).toFloat() - radius
        val centerX = start + (end - start) * thumbPosition
        paint.color = color(R.color.settings_toggle_thumb)
        paint.setShadowLayer(dp(1).toFloat(), 0f, dp(1).toFloat(), 0x33000000)
        canvas.drawCircle(centerX, height / 2f, radius, paint)
        paint.clearShadowLayer()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = isChecked
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun color(@ColorRes res: Int) = ContextCompat.getColor(context, res)
    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()
}
