package com.example.furiganakeyboard

import com.example.furiganakeyboard.ime.WordCandidateResolver
import com.example.furiganakeyboard.reading.WordReadingCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class WordCandidateResolverTest {
    @Test
    fun combinesAlternativesInRecognitionOrder() {
        assertEquals(
            listOf("日本", "日木", "目本", "目木"),
            WordCandidateResolver.combine("", listOf("日", "目"), listOf("本", "木"))
        )
    }

    @Test
    fun exactWordsAcrossAlternativesComeBeforeCompletions() {
        val exact = mapOf("日本" to listOf("にほん", "にっぽん"), "目下" to listOf("めした"))
        val result = WordCandidateResolver.resolve(
            surfaces = listOf("日本", "日木", "目本", "目木"),
            exactReadings = { exact[it].orEmpty() },
            suggestions = {
                if (it == "日本") listOf(WordReadingCandidate("日本語", listOf("にほんご")))
                else emptyList()
            }
        )
        assertEquals(listOf("日本", "日本語"), result.map { it.surface })
        assertEquals(listOf("にほん", "にっぽん"), result.first().readings)
    }

    @Test
    fun duplicateDictionarySurfacesAreRemoved() {
        val result = WordCandidateResolver.resolve(
            surfaces = listOf("今日", "今日"),
            exactReadings = { if (it == "今日") listOf("きょう") else emptyList() },
            suggestions = { listOf(WordReadingCandidate("今日", listOf("きょう"))) }
        )
        assertEquals(listOf("今日"), result.map { it.surface })
    }

    @Test
    fun combinesReadingsForAWordFollowedByACharacter() {
        val readings = WordCandidateResolver.composedReadings(
            root = "国",
            previousCharacters = listOf("際"),
            currentCharacters = listOf("版"),
            readings = mapOf(
                "国際" to listOf("こくさい"),
                "版" to listOf("ハン", "バン")
            )
        )

        assertEquals(listOf("こくさいはん", "こくさいばん"), readings["国際版"])
    }
}
