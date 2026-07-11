package com.example.furiganakeyboard

import com.example.furiganakeyboard.settings.JapaneseInputMode
import org.junit.Assert.assertEquals
import org.junit.Test

class JapaneseInputModeTest {
    @Test
    fun storedModesRoundTripAndUnknownValuesFallBackToHandwriting() {
        assertEquals(
            JapaneseInputMode.HANDWRITING,
            JapaneseInputMode.fromStored(JapaneseInputMode.HANDWRITING.name)
        )
        assertEquals(
            JapaneseInputMode.ROMAJI,
            JapaneseInputMode.fromStored(JapaneseInputMode.ROMAJI.name)
        )
        assertEquals(JapaneseInputMode.HANDWRITING, JapaneseInputMode.fromStored(null))
        assertEquals(JapaneseInputMode.HANDWRITING, JapaneseInputMode.fromStored("obsolete"))
    }
}
