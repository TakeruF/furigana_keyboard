package com.example.furiganakeyboard.view

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.settings.KeyboardPrefs

/**
 * Helper for building WeChat-style flat key buttons in code (used by the
 * symbol / QWERTY panels so they match the XML-defined keys exactly).
 */
internal object KeyFactory {

    /** Visual variants matching the key background drawables. */
    enum class Kind { PLAIN, FUNCTION, ACCENT }

    fun key(
        context: Context,
        label: String,
        kind: Kind = Kind.PLAIN,
        textSizeSp: Float = 17f,
        onClick: (() -> Unit)? = null
    ): Button = ImmediateKeyButton(context).apply {
        text = label
        isAllCaps = false
        // Force Japanese glyph variants for Han characters (Han unification).
        textLocale = java.util.Locale.JAPAN
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        includeFontPadding = false
        stateListAnimator = null // kill Material elevation animation
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        maxLines = 1
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            this,
            10,
            textSizeSp.toInt().coerceAtLeast(10),
            1,
            TypedValue.COMPLEX_UNIT_SP
        )
        background = when (kind) {
            Kind.PLAIN -> ContextCompat.getDrawable(context, R.drawable.key_background)
            Kind.FUNCTION -> ContextCompat.getDrawable(context, R.drawable.key_function_background)
            Kind.ACCENT -> AccentStyle.background(context, KeyboardPrefs(context).accentColor)
        }
        setTextColor(
            ContextCompat.getColor(
                context,
                if (kind == Kind.ACCENT) R.color.kbd_on_accent else R.color.kbd_on_surface
            )
        )
        onClick?.let(::setOnKeyPress)
    }

    /** LayoutParams for a key in a horizontal row: weighted width, 2dp margin. */
    fun rowParams(context: Context, weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
            val m = dp(context, 2)
            setMargins(m, m, m, m)
        }

    fun dp(context: Context, v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
    ).toInt()

    /**
     * Keyboard keys must accept the press before another finger can cancel the
     * normal Button click on ACTION_UP. Accessibility-triggered clicks still
     * dispatch the same action through [performClick].
     */
    private class ImmediateKeyButton(context: Context) : AppCompatButton(context) {
        private var keyPress: (() -> Unit)? = null
        private var skipNextPerformAction = false

        fun setOnKeyPress(action: () -> Unit) {
            keyPress = action
            isClickable = true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled || keyPress == null) return super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    isPressed = true
                    Haptics.key(this)
                    keyPress?.invoke()
                }
                MotionEvent.ACTION_UP -> {
                    isPressed = false
                    skipNextPerformAction = true
                    performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    isPressed = false
                    skipNextPerformAction = false
                }
                MotionEvent.ACTION_MOVE -> {
                    isPressed = event.x >= 0 && event.x < width &&
                        event.y >= 0 && event.y < height
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            if (skipNextPerformAction) {
                skipNextPerformAction = false
            } else {
                Haptics.key(this)
                keyPress?.invoke()
            }
            return true
        }
    }
}
