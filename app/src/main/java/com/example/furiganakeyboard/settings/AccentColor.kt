package com.example.furiganakeyboard.settings

/** User-selectable keyboard accent and its matching settings-page surface. */
enum class AccentColor(
    private val lightAccent: Int,
    private val lightPressed: Int,
    private val lightSettingsBackground: Int,
    private val darkAccent: Int,
    private val darkPressed: Int,
    private val darkSettingsBackground: Int
) {
    BLUE(0xFF4E8FE8.toInt(), 0xFF3E7FD5.toInt(), 0xFFEEF5FD.toInt(),
        0xFF609CF1.toInt(), 0xFF4B88DE.toInt(), 0xFF151D2A.toInt()),
    PURPLE(0xFF8568DC.toInt(), 0xFF7053C8.toInt(), 0xFFF4F0FC.toInt(),
        0xFFA18AE9.toInt(), 0xFF8970D5.toInt(), 0xFF1D1928.toInt()),
    GREEN(0xFF2E9674.toInt(), 0xFF247A5E.toInt(), 0xFFEEF8F4.toInt(),
        0xFF55B996.toInt(), 0xFF3D9E7D.toInt(), 0xFF13221D.toInt()),
    ORANGE(0xFFDF7831.toInt(), 0xFFC56324.toInt(), 0xFFFDF3EB.toInt(),
        0xFFF09A5C.toInt(), 0xFFD77C3D.toInt(), 0xFF271B14.toInt()),
    PINK(0xFFD45888.toInt(), 0xFFB94572.toInt(), 0xFFFCF0F5.toInt(),
        0xFFE779A4.toInt(), 0xFFCE5C88.toInt(), 0xFF27171E.toInt());

    fun accent(darkMode: Boolean): Int = if (darkMode) darkAccent else lightAccent

    fun pressed(darkMode: Boolean): Int = if (darkMode) darkPressed else lightPressed

    fun settingsBackground(darkMode: Boolean): Int =
        if (darkMode) darkSettingsBackground else lightSettingsBackground

    companion object {
        fun fromStored(value: String?): AccentColor =
            entries.firstOrNull { it.name == value } ?: BLUE
    }
}
