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

enum class KeyboardHeight(val canvasScale: Float) {
    COMPACT(0.82f), STANDARD(1f), TALL(1.18f);

    companion object {
        fun fromStored(value: String?): KeyboardHeight =
            entries.firstOrNull { it.name == value } ?: STANDARD
    }
}

enum class CandidateTextSize(val primarySp: Float, val readingSp: Float) {
    SMALL(20f, 10f), STANDARD(24f, 11f), LARGE(28f, 12f);

    companion object {
        fun fromStored(value: String?): CandidateTextSize =
            entries.firstOrNull { it.name == value } ?: STANDARD
    }
}

enum class HapticStrength(val amplitude: Int?) {
    NONE(0), SYSTEM(null), WEAK(45), MEDIUM_WEAK(75), MEDIUM(110), MEDIUM_STRONG(155), STRONG(210);

    companion object {
        fun fromStored(value: String?): HapticStrength =
            entries.firstOrNull { it.name == value } ?: SYSTEM
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

    var hapticStrength: HapticStrength
        get() = HapticStrength.fromStored(prefs.getString(KEY_HAPTIC_STRENGTH, null))
        set(value) = prefs.edit().putString(KEY_HAPTIC_STRENGTH, value.name).apply()

    var keySound: Boolean
        get() = prefs.getBoolean(KEY_KEY_SOUND, false)
        set(value) = prefs.edit().putBoolean(KEY_KEY_SOUND, value).apply()

    /** Discrete 0..4 volume used by AudioManager's key-click effect. */
    var keySoundVolume: Int
        get() = prefs.getInt(KEY_KEY_SOUND_VOLUME, 2).coerceIn(0, 4)
        set(value) = prefs.edit().putInt(KEY_KEY_SOUND_VOLUME, value.coerceIn(0, 4)).apply()

    /** Empty means follow the device language. */
    var localeTag: String
        get() = prefs.getString(KEY_LOCALE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LOCALE, value).apply()

    /** Optional ML Kit recognition with bundled Zinnia as its fallback. */
    var plusRecognition: Boolean
        get() = prefs.getBoolean(KEY_PLUS_RECOGNITION, true)
        set(value) = prefs.edit().putBoolean(KEY_PLUS_RECOGNITION, value).apply()

    /** Accent shared by editor-action keys, highlighted candidates and settings. */
    var accentColor: AccentColor
        get() = AccentColor.fromStored(prefs.getString(KEY_ACCENT_COLOR, null))
        set(value) = prefs.edit().putString(KEY_ACCENT_COLOR, value.name).apply()

    var keyboardHeight: KeyboardHeight
        get() = KeyboardHeight.fromStored(prefs.getString(KEY_KEYBOARD_HEIGHT, null))
        set(value) = prefs.edit().putString(KEY_KEYBOARD_HEIGHT, value.name).apply()

    var candidateTextSize: CandidateTextSize
        get() = CandidateTextSize.fromStored(prefs.getString(KEY_CANDIDATE_TEXT_SIZE, null))
        set(value) = prefs.edit().putString(KEY_CANDIDATE_TEXT_SIZE, value.name).apply()

    companion object {
        private const val FILE = "hanlu_keyboard_preferences"
        private const val KEY_READING_MODE = "reading_mode"
        private const val KEY_AUTO_COMMIT = "auto_commit"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_HAPTIC_STRENGTH = "haptic_strength"
        private const val KEY_KEY_SOUND = "key_sound"
        private const val KEY_KEY_SOUND_VOLUME = "key_sound_volume"
        private const val KEY_LOCALE = "app_locale"
        private const val KEY_PLUS_RECOGNITION = "plus_recognition"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_KEYBOARD_HEIGHT = "keyboard_height"
        private const val KEY_CANDIDATE_TEXT_SIZE = "candidate_text_size"

    }
}
