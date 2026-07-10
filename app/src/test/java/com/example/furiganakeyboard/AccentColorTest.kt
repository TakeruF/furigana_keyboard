package com.example.furiganakeyboard

import com.example.furiganakeyboard.settings.AccentColor
import com.example.furiganakeyboard.settings.CandidateTextSize
import com.example.furiganakeyboard.settings.KeyboardHeight
import com.example.furiganakeyboard.settings.HapticStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccentColorTest {
    @Test
    fun missingOrInvalidStoredColorFallsBackToBlue() {
        assertEquals(AccentColor.BLUE, AccentColor.fromStored(null))
        assertEquals(AccentColor.BLUE, AccentColor.fromStored("UNKNOWN"))
    }

    @Test
    fun everyPresetHasDistinctLightAccentAndBackground() {
        val presets = AccentColor.entries
        assertEquals(presets.size, presets.map { it.accent(false) }.distinct().size)
        assertEquals(presets.size, presets.map { it.settingsBackground(false) }.distinct().size)
        presets.forEach { assertNotEquals(it.accent(false), it.pressed(false)) }
    }

    @Test
    fun invalidLayoutValuesUseStableDefaults() {
        assertEquals(KeyboardHeight.STANDARD, KeyboardHeight.fromStored("UNKNOWN"))
        assertEquals(CandidateTextSize.STANDARD, CandidateTextSize.fromStored(null))
        assertEquals(HapticStrength.SYSTEM, HapticStrength.fromStored(null))
        assertEquals(0, HapticStrength.NONE.amplitude)
        assertEquals(null, HapticStrength.SYSTEM.amplitude)
    }
}
