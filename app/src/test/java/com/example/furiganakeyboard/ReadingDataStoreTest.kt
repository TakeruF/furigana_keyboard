package com.example.furiganakeyboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.furiganakeyboard.data.ReadingDataStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class ReadingDataStoreTest {
    private lateinit var context: Context
    private lateinit var updateDirectory: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        updateDirectory = ReadingDataStore.updateDirectory(context)
        updateDirectory.deleteRecursively()
        check(updateDirectory.mkdirs())
        context.getSharedPreferences("reading-data-updates", Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.noBackupFilesDir, ReadingDataStore.LEGACY_BUNDLED_FILE).delete()
        File(context.noBackupFilesDir, "${ReadingDataStore.LEGACY_BUNDLED_FILE}.sha256").delete()
    }

    @After
    fun tearDown() {
        updateDirectory.deleteRecursively()
        File(context.noBackupFilesDir, ReadingDataStore.LEGACY_BUNDLED_FILE).delete()
    }

    @Test
    fun legacyActiveWithoutProfileRemainsTheSelectedFullDictionary() {
        val full = database("reading-42.db")
        context.getSharedPreferences("reading-data-updates", Context.MODE_PRIVATE).edit()
            .putString("active-file", full.name)
            .putInt("active-version", 42)
            .putInt("active-schema-version", 8)
            .commit()

        assertEquals(full, ReadingDataStore.activeFull(context))
        assertEquals(42, ReadingDataStore.installedFullVersion(context))
    }

    @Test
    fun verifiedLegacyBundledFullIsMovedBeforeCoreCanReplaceItsOldPath() {
        val source = File(context.noBackupFilesDir, ReadingDataStore.LEGACY_BUNDLED_FILE)
        createDatabase(source)
        val target = File(updateDirectory, ReadingDataStore.LEGACY_FULL_FILE)

        assertTrue(ReadingDataStore.preserveLegacyFull(source, target, sha256(source)))
        assertFalse(source.exists())
        assertTrue(target.isFile)
        assertEquals(target, ReadingDataStore.activeFull(context))
    }

    @Test
    fun invalidLegacyFullIsNotMovedAndCoreFallbackRemainsAvailable() {
        val source = File(context.noBackupFilesDir, ReadingDataStore.LEGACY_BUNDLED_FILE)
        source.writeText("not sqlite")
        val target = File(updateDirectory, ReadingDataStore.LEGACY_FULL_FILE)

        assertFalse(ReadingDataStore.preserveLegacyFull(source, target, sha256(source)))
        assertTrue(source.isFile)
        assertFalse(target.exists())
        assertNull(ReadingDataStore.activeFull(context))
        val core = File(context.noBackupFilesDir, "test-core.db").apply { writeText("core") }
        assertEquals(core, ReadingDataStore.selectFullOrCore(context, core))
        core.delete()
    }

    @Test
    fun corruptActiveFullFallsBackToAnotherVerifiedLegacyFull() {
        val corrupt = File(updateDirectory, "full-10.db").apply { writeText("not sqlite") }
        val fallback = database(ReadingDataStore.LEGACY_FULL_FILE)
        context.getSharedPreferences("reading-data-updates", Context.MODE_PRIVATE).edit()
            .putString("active-file", corrupt.name)
            .putString("active-profile", "full")
            .putInt("active-version", 10)
            .putInt("active-schema-version", 8)
            .commit()

        assertEquals(fallback, ReadingDataStore.activeFull(context))
        assertEquals(0, ReadingDataStore.installedFullVersion(context))
    }

    private fun database(name: String): File = File(updateDirectory, name).also(::createDatabase)

    private fun createDatabase(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            database.execSQL("INSERT INTO metadata VALUES ('schema_version', '8')")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
