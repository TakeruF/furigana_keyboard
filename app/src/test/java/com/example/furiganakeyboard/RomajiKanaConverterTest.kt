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
        assertEquals(
            RomajiKanaConverter.Result("ん", ""),
            RomajiKanaConverter.convert("nn")
        )
        assertEquals("んな", RomajiKanaConverter.convert("nna").displayText)
    }

    @Test
    fun exposesIncompleteSyllableAsPendingText() {
        assertEquals(
            RomajiKanaConverter.Result("に", "h"),
            RomajiKanaConverter.convert("nih")
        )
    }

    @Test
    fun convertsLongRapidInputWithoutChangingSyllablePrecedence() {
        val source = "konnichiha".repeat(100)
        assertEquals("こんにちは".repeat(100), RomajiKanaConverter.convert(source).displayText)
        assertEquals("っちゃ", RomajiKanaConverter.convert("tcha").displayText)
        assertEquals("しぇ", RomajiKanaConverter.convert("she").displayText)
    }

    @Test
    fun deleteRemovesACompletedKanaInsteadOfOneRomajiKey() {
        assertEquals("", RomajiKanaConverter.deleteLastUnit("sa"))
        assertEquals("niho", RomajiKanaConverter.deleteLastUnit("nihon"))
        assertEquals("gakko", RomajiKanaConverter.deleteLastUnit("gakkou"))
        assertEquals("", RomajiKanaConverter.deleteLastUnit("shi"))
    }

    @Test
    fun deleteStillStepsThroughIncompleteRomaji() {
        assertEquals("s", RomajiKanaConverter.deleteLastUnit("sh"))
        assertEquals("ni", RomajiKanaConverter.deleteLastUnit("nih"))
        assertEquals("", RomajiKanaConverter.deleteLastUnit(""))
    }

    @Test
    fun deletePreservesCompletedSmallTsuWithoutLeakingRawConsonant() {
        val ttaRemaining = RomajiKanaConverter.deleteLastUnit("tta")
        val kattaRemaining = RomajiKanaConverter.deleteLastUnit("katta")

        assertEquals("っ", RomajiKanaConverter.convert(ttaRemaining).displayText)
        assertEquals("かっ", RomajiKanaConverter.convert(kattaRemaining).displayText)
        assertEquals("った", RomajiKanaConverter.convert(ttaRemaining + "ta").displayText)
    }

    @Test
    fun deleteFromYouonRebuildsTheVisibleBaseKana() {
        val remaining = RomajiKanaConverter.deleteLastUnit("kyu")

        assertEquals("ki", remaining)
        assertEquals("き", RomajiKanaConverter.convert(remaining).displayText)
        assertEquals("し", RomajiKanaConverter.convert(
            RomajiKanaConverter.deleteLastUnit("sha")
        ).displayText)
    }

    @Test
    fun longVowelMarkParticipatesInRomajiCompositionAndDeletion() {
        assertEquals("こーひー", RomajiKanaConverter.convert("koーhiー").displayText)
        assertEquals("ko", RomajiKanaConverter.deleteLastUnit("koー"))
    }
}
