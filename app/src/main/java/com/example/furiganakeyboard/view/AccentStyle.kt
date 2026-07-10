package com.example.furiganakeyboard.view

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import androidx.core.content.ContextCompat
import com.example.furiganakeyboard.R
import com.example.furiganakeyboard.settings.AccentColor

/** Runtime styling for the accent palette selected in settings. */
internal object AccentStyle {
    fun apply(button: Button, color: AccentColor) {
        button.background = background(button.context, color)
        button.setTextColor(ContextCompat.getColor(button.context, R.color.kbd_on_accent))
    }

    fun background(context: Context, color: AccentColor): StateListDrawable {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                rounded(context, color.pressed(dark))
            )
            addState(intArrayOf(), rounded(context, color.accent(dark)))
        }
    }

    fun isDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun rounded(context: Context, color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = KeyFactory.dp(context, 12).toFloat()
    }
}
