package com.example.furiganakeyboard.view

import android.content.Context
import android.media.AudioManager
import android.view.View

/** Respects both the app preference and Android's current ringer mode. */
object KeySounds {
    @Volatile var enabled: Boolean = false
    @Volatile var volumeStep: Int = 2

    fun key(view: View) {
        if (!enabled) return
        val audio = view.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        audio.playSoundEffect(AudioManager.FX_KEY_CLICK, VOLUMES[volumeStep.coerceIn(0, 4)])
    }

    private val VOLUMES = floatArrayOf(0.15f, 0.3f, 0.5f, 0.72f, 1f)
}
