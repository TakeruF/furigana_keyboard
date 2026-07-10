package com.example.furiganakeyboard.view

import android.content.Context
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.example.furiganakeyboard.R

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
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        // Force Japanese glyph variants for Han characters (Han unification).
        textLocale = java.util.Locale.JAPAN
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        stateListAnimator = null // kill Material elevation animation
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        background = ContextCompat.getDrawable(
            context,
            when (kind) {
                Kind.PLAIN -> R.drawable.key_background
                Kind.FUNCTION -> R.drawable.key_function_background
                Kind.ACCENT -> R.drawable.key_accent_background
            }
        )
        setTextColor(
            ContextCompat.getColor(
                context,
                if (kind == Kind.ACCENT) R.color.kbd_on_accent else R.color.kbd_on_surface
            )
        )
        onClick?.let { cb ->
            setOnClickListener {
                Haptics.key(it)
                cb()
            }
        }
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
}
