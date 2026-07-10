package com.example.furiganakeyboard

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.util.Log
import com.example.furiganakeyboard.data.AssetInstaller
import com.example.furiganakeyboard.reading.ReadingRepository
import com.example.furiganakeyboard.recognizer.NativeZinnia
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureNanoTime
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineNativeRecognizerTest {
    @Test
    fun bundledModelRecognizesKnownJisCharacter() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.noBackupFilesDir, "native-test.model").delete()
        File(context.noBackupFilesDir, "native-test.model.sha256").delete()
        lateinit var model: File
        val prepareMs = measureNanoTime {
            model = AssetInstaller.ensure(
                context,
                "handwriting-ja.model",
                "native-test.model",
                "d58618576d12c1ea38992606f30d19158a0a3beda3ac23bacccf1e48e651f2b2"
            )
        } / 1_000_000.0
        assertTrue("model preparation took ${prepareMs}ms", prepareMs <= 5_000.0)
        val handle = NativeZinnia.nativeCreate(model.absolutePath)
        try {
            val strokes = arrayOf(
                floatArrayOf(213f, 203f, 166f, 856f),
                floatArrayOf(270f, 170f, 833f, 216f, 726f, 910f),
                floatArrayOf(250f, 560f, 760f, 553f),
                floatArrayOf(213f, 886f, 726f, 926f)
            )
            val candidates = NativeZinnia.nativeRecognize(handle, 1000, 1000, strokes, 10)
            assertTrue(
                "日 should be in top 10: ${candidates.toList()}",
                candidates.any { it.text == "日" }
            )
            assertTrue("native scores should be descending", candidates.toList().zipWithNext().all {
                it.first.score >= it.second.score
            })

            val durationsMs = List(20) {
                measureNanoTime {
                    NativeZinnia.nativeRecognize(handle, 1000, 1000, strokes, 10)
                } / 1_000_000.0
            }.sorted()
            val p95 = durationsMs[18]
            Log.i("RecognizerPerformance", "native_recognition_p95_ms=$p95")
            assertTrue("recognition p95 was ${p95}ms", p95 <= 250.0)
        } finally {
            NativeZinnia.nativeDestroy(handle)
        }
    }

    @Test
    fun corruptModelFailsWithoutNativeCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val corrupt = File(context.cacheDir, "corrupt-zinnia.model").apply {
            writeBytes(ByteArray(64))
        }
        var failedSafely = false
        try {
            NativeZinnia.nativeCreate(corrupt.absolutePath)
        } catch (_: IllegalStateException) {
            failedSafely = true
        }
        assertTrue("corrupt model should throw", failedSafely)
    }

    @Test
    fun bundledDictionaryPreparesWithinBudget() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.noBackupFilesDir, "reading-v3.db").delete()
        File(context.noBackupFilesDir, "reading-v3.db.sha256").delete()
        var date: String? = null
        lateinit var exactWord: String
        lateinit var exactReadings: List<String>
        val prepareMs = measureNanoTime {
            ReadingRepository(context).use {
                date = it.metadata("kanjidic_date")
                val candidate = it.suggest("日本").first()
                exactWord = candidate.surface
                exactReadings = candidate.readings
                val priorities = it.kanjiPriorities(listOf("日", "本", "龍"))
                assertTrue(priorities.getValue("日").isJoyo)
                assertEquals(1, priorities.getValue("日").frequency)
            }
        } / 1_000_000.0
        assertTrue("dictionary preparation took ${prepareMs}ms", prepareMs <= 5_000.0)
        assertTrue("dictionary metadata missing", !date.isNullOrEmpty())
        assertEquals("日本", exactWord)
        assertEquals(setOf("にほん", "にっぽん"), exactReadings.take(2).toSet())
    }
}
