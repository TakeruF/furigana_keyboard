package com.example.furiganakeyboard

import com.example.furiganakeyboard.ime.RomajiKanaConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class RomajiKanaConverterTest {
    @Test
    fun convertsCommonJapaneseInput() {
        assertEquals("にほん", RomajiKanaConverter.convert("nihon").displayText)
        assertEquals("きょう", RomajiKanaConverter.convert("kyou").displayText)
        assertEquals("おとな", RomajiKanaConverter.convert("otona").displayText)
        assertEquals("こんにちは", RomajiKanaConverter.convert("konnichiha").displayText)
    }

    @Test
    fun handlesSmallTsuAndSyllabicN() {
        assertEquals("がっこう", RomajiKanaConverter.convert("gakkou").displayText)
        assertEquals("まっちゃ", RomajiKanaConverter.convert("matcha").displayText)
        assertEquals("しんぶん", RomajiKanaConverter.convert("shinbun").displayText)
        assertEquals("ん", RomajiKanaConverter.convert("nn").displayText)
    }

    @Test
    fun exposesIncompleteSyllableAsPendingText() {
        assertEquals(
            RomajiKanaConverter.Result("に", "h"),
            RomajiKanaConverter.convert("nih")
        )
    }
}
