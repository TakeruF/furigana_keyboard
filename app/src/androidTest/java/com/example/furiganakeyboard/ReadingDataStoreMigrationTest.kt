package com.example.furiganakeyboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.furiganakeyboard.data.ReadingDataStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ReadingDataStoreMigrationTest {
    private lateinit var context: Context
    private lateinit var updates: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        updates = File(context.noBackupFilesDir, "reading-updates")
        updates.deleteRecursively()
        updates.mkdirs()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        updates.deleteRecursively()
        context.noBackupFilesDir.listFiles().orEmpty()
            .filter { it.name.startsWith("migration-test-bundled-") }
            .forEach(File::delete)
    }

    @Test
    fun activeV7FallsBackToBundledV8() = assertFallback(activeSchema = 7)

    @Test
    fun activeWithoutSchemaFallsBackToBundledV8() = assertFallback(activeSchema = null)

    @Test
    fun corruptActiveFallsBackToBundledV8() = assertFallback(
        activeSchema = 8,
        corrupt = true
    )

    @Test
    fun validActiveV8IsSelectedAndItsVersionIsInstalled() {
        val bundled = bundledDatabase()
        val active = createDatabase(File(updates, "active-v8.db"), 8)
        recordActive(active, schema = 8, version = 42)

        val selected = select(bundled)

        assertEquals(active.canonicalFile, selected.canonicalFile)
        assertEquals(42, ReadingDataStore.installedVersion(context))
    }

    private fun assertFallback(activeSchema: Int?, corrupt: Boolean = false) {
        val bundled = bundledDatabase()
        val active = File(updates, "legacy-or-corrupt.db")
        if (corrupt) active.writeText("not a sqlite database") else createDatabase(active, 7)
        recordActive(active, activeSchema, version = 99)

        val selected = select(bundled)

        assertEquals(bundled.canonicalFile, selected.canonicalFile)
        assertEquals(0, ReadingDataStore.installedVersion(context))
    }

    private fun bundledDatabase(): File = createDatabase(
        File(context.noBackupFilesDir, "migration-test-bundled-${System.nanoTime()}.db"),
        8
    )

    private fun select(bundled: File): File = ReadingDataStore.activeOrBundled(
        context = context,
        assetName = "unused-test-asset.db",
        bundledName = bundled.name,
        bundledSha256 = sha256(bundled)
    )

    private fun recordActive(file: File, schema: Int?, version: Int) {
        val edit = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("active-file", file.name)
            .putInt("active-version", version)
        if (schema != null) edit.putInt("active-schema-version", schema)
        edit.commit()
    }

    private fun createDatabase(file: File, schema: Int): File {
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            database.execSQL(
                "INSERT INTO metadata(key, value) VALUES ('schema_version', ?)",
                arrayOf(schema.toString())
            )
        }
        return file
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFERENCES = "reading-data-updates"
    }
}
