package com.example.furiganakeyboard.view

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout

/**
 * Keeps the handwriting canvas at half of the keyboard's available width.
 * The fixed-height mode row sits below that canvas inside the same container.
 */
class KeyboardPanelContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var canvasScale: Float = 1f
        set(value) {
            field = value.coerceIn(0.75f, 1.25f)
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        // The canvas has 2dp margins above and below in keyboard_view.xml.
        val desiredHeight = (availableWidth / 2f * canvasScale).toInt() +
            dp(MODE_ROW_HEIGHT_DP + CANVAS_VERTICAL_MARGINS_DP)
        // A tablet in landscape (or a split-screen window) can be wider than
        // the height available to the IME. Honor the parent's constraint so
        // the input view is not measured outside the IME window.
        val resolvedHeight = View.resolveSize(desiredHeight, heightMeasureSpec)
        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(resolvedHeight, View.MeasureSpec.EXACTLY)
        )
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    companion object {
        private const val MODE_ROW_HEIGHT_DP = 48
        private const val CANVAS_VERTICAL_MARGINS_DP = 4
    }
}
