package com.example.furiganakeyboard

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.furiganakeyboard.conversion.KanaKanjiConverter
import com.example.furiganakeyboard.reading.ReadingRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CandidatePerformanceTest {
    @Test
    fun warmSentenceConversionStaysWithinInteractiveBudgets() {
        ReadingRepository(ApplicationProvider.getApplicationContext()).use { repository ->
            val reading = "きょうははれだ"
            val connections = repository.conversionConnections()
            fun loadLexemes() = repository.conversionLexemes(reading, 16, 12)

            val warmLexemes = loadLexemes()
            KanaKanjiConverter.convert(reading, warmLexemes, connections)
            val conversion = timings(30) {
                KanaKanjiConverter.convert(reading, loadLexemes(), connections)
            }
            val engine = timings(50) {
                KanaKanjiConverter.convert(reading, warmLexemes, connections)
            }
            val conversionP95 = percentile95(conversion)
            val engineP95 = percentile95(engine)

            Log.i(TAG, "warm_conversion_p95_ms=$conversionP95 engine_p95_ms=$engineP95")
            assertTrue(
                "Warm conversion p95 was ${conversionP95}ms",
                conversionP95 <= CONVERSION_BUDGET_MS
            )
            assertTrue("Engine p95 was ${engineP95}ms", engineP95 <= ENGINE_BUDGET_MS)
        }
    }

    @Test
    fun warmDictionaryLookupsStayWithinInteractiveBudgets() {
        ReadingRepository(ApplicationProvider.getApplicationContext()).use { repository ->
            val prefixes = listOf("日", "目", "曰", "白", "田").flatMap { left ->
                listOf("本", "木", "未", "末", "禾").map { right -> left + right }
            }.take(24)

            repository.suggestByReading("にほん", 8)
            repository.suggestForPrefixes(prefixes, 8)
            val romaji = timings(30) { repository.suggestByReading("にほん", 8) }
            val handwriting = timings(20) { repository.suggestForPrefixes(prefixes, 8) }
            val romajiP95 = percentile95(romaji)
            val handwritingP95 = percentile95(handwriting)

            Log.i(TAG, "warm_romaji_p95_ms=$romajiP95 warm_handwriting24_p95_ms=$handwritingP95")
            assertTrue("Romaji lookup p95 was ${romajiP95}ms", romajiP95 <= ROMAJI_BUDGET_MS)
            assertTrue(
                "24-prefix handwriting lookup p95 was ${handwritingP95}ms",
                handwritingP95 <= HANDWRITING_BUDGET_MS
            )
        }
    }

    private fun timings(iterations: Int, operation: () -> Unit): List<Long> =
        List(iterations) {
            val start = SystemClock.elapsedRealtimeNanos()
            operation()
            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L
        }

    private fun percentile95(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * 0.95).toInt()]
    }

    companion object {
        private const val TAG = "CandidatePerformance"
        private const val ROMAJI_BUDGET_MS = 50L
        private const val HANDWRITING_BUDGET_MS = 100L
        private const val CONVERSION_BUDGET_MS = 50L
        private const val ENGINE_BUDGET_MS = 10L
    }
}
