package com.example.furiganakeyboard

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.furiganakeyboard.reading.ReadingRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingRepositoryBatchTest {
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
}
