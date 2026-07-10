package com.example.furiganakeyboard.view

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Central haptic-feedback helper. [enabled] mirrors the user preference and is
 * refreshed by the IME service; views call [key]/[tick] without knowing prefs.
 */
object Haptics {

    /** Set from KeyboardPrefs.haptics whenever the keyboard is shown. */
    @Volatile
    var enabled = true

    // IMEs conventionally vibrate regardless of the system "touch feedback"
    // toggle, hence the ignore flags (deprecated on 33 but still honored).
    @Suppress("DEPRECATION")
    private const val FLAGS =
        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING

    /** Standard key-press feedback. */
    fun key(view: View) {
        if (enabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, FLAGS)
    }

    /** Subtle feedback (pen-down on the writing pad, delete auto-repeat). */
    fun tick(view: View) {
        if (enabled) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, FLAGS)
    }
}
