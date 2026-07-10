package com.example.furiganakeyboard.settings

import android.content.Context

/** How readings are shown beneath each candidate. */
enum class ReadingMode {
    KANA,   // かな:   しゃ / うつす / うつる
    ROMAJI, // romaji: sha / utsusu / utsuru
    OFF;    // hidden

    /** Cycle to the next mode (used by the on-keyboard toggle button). */
    fun next(): ReadingMode = when (this) {
        KANA -> ROMAJI
        ROMAJI -> OFF
        OFF -> KANA
    }
}

/**
 * Thin SharedPreferences wrapper shared by the IME and the settings activity.
 * Both processes/components read and write the same file so changes made in
 * settings are reflected the next time the keyboard reads a value.
 */
class KeyboardPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var readingMode: ReadingMode
        get() = ReadingMode.valueOf(
            prefs.getString(KEY_READING_MODE, ReadingMode.KANA.name) ?: ReadingMode.KANA.name
        )
        set(value) = prefs.edit().putString(KEY_READING_MODE, value.name).apply()

    /** 連続手書き: starting the next character auto-commits the top candidate. */
    var autoCommit: Boolean
        get() = prefs.getBoolean(KEY_AUTO_COMMIT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_COMMIT, value).apply()

    /** Haptic feedback on keys / pen-down. */
    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    /** Empty means follow the device language. */
    var localeTag: String
        get() = prefs.getString(KEY_LOCALE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LOCALE, value).apply()

    companion object {
        private const val FILE = "furigana_keyboard_prefs"
        private const val KEY_READING_MODE = "reading_mode"
        private const val KEY_AUTO_COMMIT = "auto_commit"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_LOCALE = "app_locale"

    }
}
