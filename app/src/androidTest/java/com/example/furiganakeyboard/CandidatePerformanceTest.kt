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
    fun warmSentenceConversionReportsContextOverhead() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ReadingRepository(context).use { repository ->
            val readings = listOf(
                "がっこうへいきます",
                "しゃしんをとってください",
                "へんかんせいどをたしかめる",
                "おちゃをのみたい",
            )
            val connections = repository.conversionConnections()
            val contextModel = repository.conversionContextModel()
            val prepared = readings.associateWith { reading ->
                repository.conversionLexemes(reading, 16, 12)
            }

            fun convertPrepared(reading: String, contextual: Boolean) {
                KanaKanjiConverter.convert(
                    reading = reading,
                    lexemes = prepared.getValue(reading),
                    connections = connections,
                    contextModel = if (contextual) contextModel else
                        com.example.furiganakeyboard.conversion.ConversionContextModel.empty(),
                )
            }
            fun convertWithLookup(reading: String, contextual: Boolean) {
                KanaKanjiConverter.convert(
                    reading = reading,
                    lexemes = repository.conversionLexemes(reading, 16, 12),
                    connections = connections,
                    contextModel = if (contextual) contextModel else
                        com.example.furiganakeyboard.conversion.ConversionContextModel.empty(),
                )
            }

            repeat(4) {
                readings.forEach { reading ->
                    convertPrepared(reading, contextual = false)
                    convertPrepared(reading, contextual = true)
                    convertWithLookup(reading, contextual = false)
                    convertWithLookup(reading, contextual = true)
                }
            }
            val baselineEngine = mutableListOf<Long>()
            val contextEngine = mutableListOf<Long>()
            repeat(40) { index ->
                val reading = readings[index % readings.size]
                if (index % 2 == 0) {
                    baselineEngine += timing { convertPrepared(reading, contextual = false) }
                    contextEngine += timing { convertPrepared(reading, contextual = true) }
                } else {
                    contextEngine += timing { convertPrepared(reading, contextual = true) }
                    baselineEngine += timing { convertPrepared(reading, contextual = false) }
                }
            }
            val baselineConversion = mutableListOf<Long>()
            val contextConversion = mutableListOf<Long>()
            repeat(20) { index ->
                val reading = readings[index % readings.size]
                if (index % 2 == 0) {
                    baselineConversion += timing { convertWithLookup(reading, contextual = false) }
                    contextConversion += timing { convertWithLookup(reading, contextual = true) }
                } else {
                    contextConversion += timing { convertWithLookup(reading, contextual = true) }
                    baselineConversion += timing { convertWithLookup(reading, contextual = false) }
                }
            }
            val baselineEngineP95 = percentile95(baselineEngine)
            val contextEngineP95 = percentile95(contextEngine)
            val baselineConversionP50 = percentile(baselineConversion, 0.50)
            val contextConversionP50 = percentile(contextConversion, 0.50)
            val baselineConversionP95 = percentile95(baselineConversion)
            val contextConversionP95 = percentile95(contextConversion)
            val modelBytes = context.assets.open("context-model.bin").use { it.readBytes().size }

            Log.i(
                TAG,
                "baseline_conversion_p50_ms=$baselineConversionP50 " +
                    "context_conversion_p50_ms=$contextConversionP50 " +
                    "baseline_conversion_p95_ms=$baselineConversionP95 " +
                    "context_conversion_p95_ms=$contextConversionP95 " +
                    "context_conversion_delta_p95_ms=" +
                    "${contextConversionP95 - baselineConversionP95} " +
                    "baseline_engine_p95_ms=$baselineEngineP95 " +
                    "context_engine_p95_ms=$contextEngineP95 " +
                    "context_engine_delta_p95_ms=${contextEngineP95 - baselineEngineP95} " +
                    "context_model_bytes=$modelBytes"
            )
            assertTrue(
                "Context conversion p95 regressed: baseline=${baselineConversionP95}ms " +
                    "context=${contextConversionP95}ms",
                contextConversionP95 <= regressionLimit(baselineConversionP95)
            )
            assertTrue(
                "Context engine p95 regressed: baseline=${baselineEngineP95}ms " +
                    "context=${contextEngineP95}ms",
                contextEngineP95 <= regressionLimit(baselineEngineP95)
            )
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

    private fun timing(operation: () -> Unit): Long {
        val start = SystemClock.elapsedRealtimeNanos()
        operation()
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L
    }

    private fun regressionLimit(baselineMs: Long): Long =
        baselineMs + maxOf(10L, baselineMs / 4)

    private fun percentile95(values: List<Long>): Long {
        return percentile(values, 0.95)
    }

    private fun percentile(values: List<Long>, percentile: Double): Long {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * percentile).toInt()]
    }

    companion object {
        private const val TAG = "CandidatePerformance"
        private const val ROMAJI_BUDGET_MS = 50L
        private const val HANDWRITING_BUDGET_MS = 100L
    }
}
