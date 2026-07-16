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

/**
 * Device-level cover for full/core selection against real Android SQLite. The JVM suite owns the
 * legacy-full migration itself, which [ReadingDataStore.preserveLegacyFull] exercises without a
 * multi-megabyte fixture.
 */
@RunWith(AndroidJUnit4::class)
class ReadingDataStoreMigrationTest {
    private lateinit var context: Context
    private lateinit var updates: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        updates = ReadingDataStore.updateDirectory(context)
        updates.deleteRecursively()
        updates.mkdirs()
        clearLegacyBundledFull()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        updates.deleteRecursively()
        clearLegacyBundledFull()
        context.noBackupFilesDir.listFiles().orEmpty()
            .filter { it.name.startsWith(CORE_PREFIX) }
            .forEach(File::delete)
    }

    @Test
    fun activeFullOnAnOlderSchemaFallsBackToBundledCore() = assertCoreFallback(activeSchema = 7)

    @Test
    fun corruptActiveFullFallsBackToBundledCore() = assertCoreFallback(corrupt = true)

    @Test
    fun validActiveFullIsSelectedAndItsVersionIsInstalled() {
        val core = bundledCore()
        val active = createDatabase(File(updates, "full-42.db"), 8)
        recordActive(active, schema = 8, version = 42)

        assertEquals(active.canonicalFile, select(core).canonicalFile)
        assertEquals(42, ReadingDataStore.installedFullVersion(context))
    }

    /** The database itself, not the recorded preference, now decides schema compatibility. */
    @Test
    fun activeFullWithoutARecordedSchemaIsSelectedWhenTheDatabaseIsCompatible() {
        val core = bundledCore()
        val active = createDatabase(File(updates, "full-42.db"), 8)
        recordActive(active, schema = null, version = 42)

        assertEquals(active.canonicalFile, select(core).canonicalFile)
        assertEquals(42, ReadingDataStore.installedFullVersion(context))
    }

    private fun assertCoreFallback(activeSchema: Int = 8, corrupt: Boolean = false) {
        val core = bundledCore()
        val active = File(updates, "full-99.db")
        if (corrupt) {
            active.writeText("not a sqlite database")
        } else {
            createDatabase(active, activeSchema)
        }
        recordActive(active, schema = 8, version = 99)

        assertEquals(core.canonicalFile, select(core).canonicalFile)
        assertEquals(0, ReadingDataStore.installedFullVersion(context))
    }

    /**
     * Pre-installed under its own hash so the asset installer adopts it in place, without
     * unpacking the real bundled asset.
     */
    private fun bundledCore(): File = createDatabase(
        File(context.noBackupFilesDir, "$CORE_PREFIX${System.nanoTime()}.db"),
        8
    )

    private fun select(core: File): File = ReadingDataStore.fullOrBundledCore(
        context = context,
        coreAssetName = "unused-test-asset.db",
        coreFileName = core.name,
        coreSha256 = sha256(core)
    )

    private fun recordActive(file: File, schema: Int?, version: Int) {
        val edit = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("active-file", file.name)
            .putInt("active-version", version)
        if (schema != null) edit.putInt("active-schema-version", schema)
        edit.commit()
    }

    /** A leftover pre-core full database would otherwise migrate in and win selection. */
    private fun clearLegacyBundledFull() {
        File(context.noBackupFilesDir, ReadingDataStore.LEGACY_BUNDLED_FILE).delete()
        File(context.noBackupFilesDir, "${ReadingDataStore.LEGACY_BUNDLED_FILE}.sha256").delete()
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
        const val CORE_PREFIX = "migration-test-core-"
    }
}
