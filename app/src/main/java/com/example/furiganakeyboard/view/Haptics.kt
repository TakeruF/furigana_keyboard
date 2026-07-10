package com.example.furiganakeyboard.view

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.example.furiganakeyboard.settings.HapticStrength

/**
 * Central haptic-feedback helper. [enabled] mirrors the user preference and is
 * refreshed by the IME/settings surfaces; views call the semantic helpers
 * without knowing where the preference is stored.
 */
object Haptics {

    /** Set from KeyboardPrefs.haptics whenever the keyboard is shown. */
    @Volatile
    var enabled = true

    @Volatile
    var strength: HapticStrength = HapticStrength.SYSTEM

    /** Standard key-press feedback. */
    fun key(view: View) {
        KeySounds.key(view)
        perform(view, HapticFeedbackConstants.KEYBOARD_TAP, 12)
    }

    /** Subtle feedback (pen-down on the writing pad, delete auto-repeat). */
    fun tick(view: View) {
        perform(view, HapticFeedbackConstants.CLOCK_TICK, 8)
    }

    /** Short confirmation for cards, navigation and ordinary actions. */
    fun click(view: View) {
        perform(view, HapticFeedbackConstants.VIRTUAL_KEY, 16)
    }

    /** The lightest feedback for toggles and single-choice changes. */
    fun selection(view: View) {
        perform(view, HapticFeedbackConstants.TEXT_HANDLE_MOVE, 8)
    }

    private fun perform(view: View, systemConstant: Int, durationMs: Long) {
        if (!enabled || strength == HapticStrength.NONE) return
        val amplitude = strength.amplitude
        if (amplitude == null) {
            // SYSTEM deliberately honors Android's global touch-feedback setting.
            view.performHapticFeedback(systemConstant)
            return
        }
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            view.context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }
}
