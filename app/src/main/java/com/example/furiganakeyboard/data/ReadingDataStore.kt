package com.example.furiganakeyboard.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Selects the last fully verified downloadable database, or the bundled fallback. */
object ReadingDataStore {
    private const val PREFERENCES = "reading-data-updates"
    private const val ACTIVE_FILE = "active-file"
    private const val ACTIVE_VERSION = "active-version"
    private const val ACTIVE_SCHEMA_VERSION = "active-schema-version"
    private const val ACTIVE_DICTIONARY_DATE = "active-dictionary-date"
    private const val DIRECTORY = "reading-updates"

    fun activeOrBundled(
        context: Context,
        assetName: String,
        bundledName: String,
        bundledSha256: String
    ): File {
        val appContext = context.applicationContext
        val preferences = preferences(appContext)
        val selected = preferences.getString(ACTIVE_FILE, null)
            ?.takeIf { preferences.getInt(ACTIVE_SCHEMA_VERSION, 0) == EXPECTED_SCHEMA_VERSION }
            ?.takeIf(::safeFileName)
            ?.let { File(updateDirectory(appContext), it) }
            ?.takeIf { isCompatibleDatabase(it, EXPECTED_SCHEMA_VERSION) }
        return selected ?: AssetInstaller.ensure(
            appContext,
            assetName,
            bundledName,
            bundledSha256
        )
    }

    fun installedVersion(context: Context): Int {
        val preferences = preferences(context)
        if (preferences.getInt(ACTIVE_SCHEMA_VERSION, 0) != EXPECTED_SCHEMA_VERSION) return 0
        val active = preferences.getString(ACTIVE_FILE, null)
            ?.takeIf(::safeFileName)
            ?.let { File(updateDirectory(context), it) }
            ?.takeIf { isCompatibleDatabase(it, EXPECTED_SCHEMA_VERSION) }
            ?: return 0
        return preferences.getInt(ACTIVE_VERSION, 0).takeIf { active.isFile } ?: 0
    }

    fun installedDictionaryDate(context: Context): String? =
        preferences(context).getString(ACTIVE_DICTIONARY_DATE, null)

    internal fun updateDirectory(context: Context): File =
        File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() }

    internal fun activate(
        context: Context,
        file: File,
        version: Int,
        schemaVersion: Int,
        dictionaryDate: String
    ): Boolean {
        require(file.parentFile == updateDirectory(context))
        return preferences(context).edit()
            .putString(ACTIVE_FILE, file.name)
            .putInt(ACTIVE_VERSION, version)
            .putInt(ACTIVE_SCHEMA_VERSION, schemaVersion)
            .putString(ACTIVE_DICTIONARY_DATE, dictionaryDate)
            .commit()
    }

    internal fun removeInactive(context: Context, active: File) {
        updateDirectory(context).listFiles().orEmpty().forEach { candidate ->
            if (candidate != active && !candidate.name.endsWith(".tmp")) candidate.delete()
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun safeFileName(value: String): Boolean =
        value.isNotEmpty() && value == File(value).name && !value.contains("..")

    internal fun isCompatibleDatabase(file: File, expectedSchema: Int): Boolean = runCatching {
        if (!file.isFile) return@runCatching false
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        ).use { database ->
            val integrity = database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == "ok"
            }
            if (!integrity) return@use false
            database.rawQuery(
                "SELECT value FROM metadata WHERE key='schema_version'",
                null
            ).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).toIntOrNull() == expectedSchema
            }
        }
    }.getOrDefault(false)

    internal const val EXPECTED_SCHEMA_VERSION = 8
}
