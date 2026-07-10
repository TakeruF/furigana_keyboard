package com.example.furiganakeyboard

import com.example.furiganakeyboard.reading.RomajiConverter
import com.example.furiganakeyboard.ime.CompositionBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the pure reading/romaji logic (no Android dependencies). */
class ReadingLogicTest {

    // ---- romaji: hiragana ----

    @Test
    fun romaji_basicSyllables() {
        assertEquals("utsusu", RomajiConverter.toRomaji("うつす"))
        assertEquals("utsuru", RomajiConverter.toRomaji("うつる"))
        assertEquals("hon", RomajiConverter.toRomaji("ほん"))
    }

    @Test
    fun romaji_digraphs() {
        assertEquals("sha", RomajiConverter.toRomaji("しゃ"))
        assertEquals("chu", RomajiConverter.toRomaji("ちゅ"))
    }

    @Test
    fun romaji_sokuon_doublesConsonant() {
        assertEquals("gakko", RomajiConverter.toRomaji("がっこ"))
    }

    // ---- romaji: katakana (on-yomi are stored in katakana) ----

    @Test
    fun romaji_katakana() {
        assertEquals("sha", RomajiConverter.toRomaji("シャ"))
        assertEquals("nichi", RomajiConverter.toRomaji("ニチ"))
        assertEquals("gakko", RomajiConverter.toRomaji("ガッコ"))
    }

    @Test
    fun romaji_longVowelMark() {
        assertEquals("shoo", RomajiConverter.toRomaji("ショー"))
        assertEquals("soo", RomajiConverter.toRomaji("ソー"))
    }

    @Test
    fun romaji_okuriganaParensPassThrough() {
        // Formatted readings like うつ(す) keep their parentheses.
        assertEquals("utsu(su)", RomajiConverter.toRomaji("うつ(す)"))
    }

    @Test
    fun compositionBuffer_isUnicodeSafe() {
        val buffer = CompositionBuffer()
        assertEquals("日", buffer.append("日"))
        assertEquals("日𠮟", buffer.append("𠮟"))
        assertEquals("日", buffer.deleteLastCodePoint())
        assertEquals("日本", buffer.replace("日本"))
        buffer.clear()
        assertEquals("", buffer.text)
    }
}
