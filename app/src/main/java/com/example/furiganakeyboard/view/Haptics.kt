package com.example.furiganakeyboard.view

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Central haptic-feedback helper. [enabled] mirrors the user preference and is
 * refreshed by the IME/settings surfaces; views call the semantic helpers
 * without knowing where the preference is stored.
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

    /** Short confirmation for cards, navigation and ordinary actions. */
    fun click(view: View) {
        if (enabled) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, FLAGS)
    }

    /** The lightest feedback for toggles and single-choice changes. */
    fun selection(view: View) {
        if (enabled) view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE, FLAGS)
    }
}
