package com.example.furiganakeyboard

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.furiganakeyboard.conversion.KanaKanjiConverter
import com.example.furiganakeyboard.reading.ReadingRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingRepositoryBatchTest {
    @Test
    fun latticeConversionReturnsEveryRequiredSentenceFirst() {
        ReadingRepository(ApplicationProvider.getApplicationContext()).use { repository ->
            val required = linkedMapOf(
                "これもほんだ" to "これも本だ",
                "これはほんだ" to "これは本だ",
                "きょうははれだ" to "今日は晴れだ",
                "わたしもいく" to "私も行く",
                "ほんをよむ" to "本を読む"
            )
            val connections = repository.conversionConnections()
            required.forEach { (reading, expected) ->
                val results = KanaKanjiConverter.convert(
                    reading,
                    repository.conversionLexemes(reading, 16, 12),
                    connections
                )
                assertEquals(reading, expected, results.first().surface)
            }
        }
    }

    @Test
    fun conversionLexemesExpandEveryOccurrenceWithScalarOffsets() {
        ReadingRepository(ApplicationProvider.getApplicationContext()).use { repository ->
            val lexemes = repository.conversionLexemes("ほんほん", 16, 12)
                .filter { it.reading == "ほん" && it.surface == "本" }

            assertTrue(lexemes.any { it.start == 0 && it.end == 2 })
            assertTrue(lexemes.any { it.start == 2 && it.end == 4 })
        }
    }

    @Test
    fun conversionLexemesIncludeFullWidthKatakanaReading() {
        ReadingRepository(ApplicationProvider.getApplicationContext()).use { repository ->
            val reading = "しゃつ"
            val lexemes = repository.conversionLexemes(reading, 16, 12)

            assertTrue(lexemes.any { it.reading == reading && it.surface == "シャツ" })
            assertEquals(
                "シャツ",
                KanaKanjiConverter.convert(
                    reading,
                    lexemes,
                    repository.conversionConnections(),
                ).first().surface,
            )
        }
    }

    @Test
    fun conversionLexemeOffsetsDoNotSplitSupplementaryScalar() {
        ReadingRepository(ApplicationProvider.getApplicationContext()).use { repository ->
            val lexeme = repository.conversionLexemes("𠮟ほん", 16, 12)
                .first { it.reading == "ほん" && it.surface == "本" }

            assertEquals(1, lexeme.start)
            assertEquals(3, lexeme.end)
        }
    }

    @Test
    fun mixedBatchMatchesIndividualReadingOrder() {
        ReadingRepository(ApplicationProvider.getApplicationContext()).use { repository ->
            val surfaces = listOf("日", "𠮟", "日本", "今日")
            val batch = repository.readingsForMany(surfaces)

            surfaces.forEach { surface ->
                assertEquals(repository.readingsFor(surface), batch[surface].orEmpty())
            }
        }
    }

    @Test
    fun batchedPrefixesMatchSinglePrefixQueries() {
        ReadingRepository(ApplicationProvider.getApplicationContext()).use { repository ->
            val prefixes = listOf("日", "本", "今日")
            val batch = repository.suggestForPrefixes(prefixes, 8)

            prefixes.forEach { prefix ->
                assertEquals(repository.suggest(prefix, 8), batch[prefix])
            }
            assertTrue(repository.suggestByReading("にほん", 8).any { it.surface == "日本" })
        }
    }

    @Test
    fun readingConversionPromotesWordPlusParticleComposition() {
        ReadingRepository(ApplicationProvider.getApplicationContext()).use { repository ->
            val candidates = repository.suggestByReading("きょうは", 8)

            assertEquals("今日は", candidates.first().surface)
            assertEquals(listOf("きょうは"), candidates.first().readings)
        }
    }

    @Test
    fun suruNounDesiderativeCanBeConverted() {
        ReadingRepository(ApplicationProvider.getApplicationContext()).use { repository ->
            val candidates = repository.suggestByReading("かくにんしたい", 8)

            assertEquals("確認したい", candidates.first().surface)
            assertEquals(listOf("かくにんしたい"), candidates.first().readings)
        }
    }

    @Test
    fun easeAuxiliaryOffersKanaAndKanjiSpellings() {
        ReadingRepository(ApplicationProvider.getApplicationContext()).use { repository ->
            val candidates = repository.suggestByReading("うちやすい", 8)

            assertEquals("打ちやすい", candidates.first().surface)
            assertTrue(candidates.any { it.surface == "打ち易い" })
            assertTrue(candidates.take(2).all { it.readings == listOf("うちやすい") })
        }
    }

    @Test
    fun placeNameCanBeConvertedAsAWord() {
        ReadingRepository(ApplicationProvider.getApplicationContext()).use { repository ->
            assertEquals(listOf("いけぶくろ"), repository.readingsFor("池袋"))
            assertTrue(repository.suggestByReading("いけぶくろ", 8).any {
                it.surface == "池袋"
            })

            val shinjuku = repository.suggestByReading("しんじゅく", 8)
            assertEquals("新宿", shinjuku.first().surface)
        }
    }
}
