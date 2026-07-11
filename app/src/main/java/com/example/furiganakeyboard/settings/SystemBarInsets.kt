package com.example.furiganakeyboard.settings

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Keeps activity content clear of status bars, navigation bars, and display cutouts. */
internal fun View.applySystemBarPadding() {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val safeInsets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(
            initialLeft + safeInsets.left,
            initialTop + safeInsets.top,
            initialRight + safeInsets.right,
            initialBottom + safeInsets.bottom
        )
        windowInsets
    }
}
