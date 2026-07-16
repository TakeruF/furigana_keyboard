package com.example.furiganakeyboard.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Selects a verified full dictionary when one exists; otherwise installs the bundled core.
 *
 * A missing profile on an existing active record is deliberately interpreted as a legacy full
 * dictionary. Earlier app versions only ever downloaded the full dictionary, so treating that
 * state as core would silently lower conversion quality after an app update.
 */
object ReadingDataStore {
    private const val PREFERENCES = "reading-data-updates"
    private const val ACTIVE_FILE = "active-file"
    private const val ACTIVE_VERSION = "active-version"
    private const val ACTIVE_SCHEMA_VERSION = "active-schema-version"
    private const val ACTIVE_DICTIONARY_DATE = "active-dictionary-date"
    private const val ACTIVE_PROFILE = "active-profile"
    private const val DIRECTORY = "reading-updates"
    private const val PROFILE_FULL = "full"

    fun fullOrBundledCore(
        context: Context,
        coreAssetName: String,
        coreFileName: String,
        coreSha256: String
    ): File {
        val appContext = context.applicationContext
        activeFull(appContext)?.let { return it }
        val core = AssetInstaller.ensure(
            appContext,
            coreAssetName,
            coreFileName,
            coreSha256
        )
        return selectFullOrCore(appContext, core)
    }

    /** The full-dictionary version, never a bundled-core version. */
    fun installedFullVersion(context: Context): Int {
        val appContext = context.applicationContext
        migrateLegacyBundledFull(appContext)
        val preferences = preferences(appContext)
        val active = currentActiveFull(appContext, preferences)
            ?: return 0
        return preferences.getInt(ACTIVE_VERSION, 0).takeIf { active.isFile } ?: 0
    }

    fun installedDictionaryDate(context: Context): String? =
        preferences(context).getString(ACTIVE_DICTIONARY_DATE, null)

    internal fun updateDirectory(context: Context): File =
        File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() }

    /** Activates a fully verified downloaded full dictionary. */
    internal fun activateFull(
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
            .putString(ACTIVE_PROFILE, PROFILE_FULL)
            .commit()
    }

    /** Removes interrupted downloads only; valid older full dictionaries are rollback fallbacks. */
    internal fun removeTemporaryFiles(context: Context) {
        updateDirectory(context).listFiles().orEmpty().forEach { candidate ->
            if (candidate.name.endsWith(".tmp")) candidate.delete()
        }
    }

    /**
     * Finds the best locally available full dictionary and adopts the schema-8 bundled full
     * database installed by pre-core releases. The move is same-volume and atomic; it happens
     * before the core asset is ever installed under its new filename.
     */
    internal fun activeFull(context: Context): File? {
        val appContext = context.applicationContext
        migrateLegacyBundledFull(appContext)
        val preferences = preferences(appContext)
        val active = currentActiveFull(appContext, preferences)
        if (active != null) return active
        val legacy = File(updateDirectory(appContext), LEGACY_FULL_FILE)
        return legacy.takeIf { isCompatibleDatabase(it, EXPECTED_SCHEMA_VERSION) }
    }

    /** The single selection point used after both successful and failed full downloads. */
    internal fun selectFullOrCore(context: Context, core: File): File = activeFull(context) ?: core

    private fun currentActiveFull(
        context: Context,
        preferences: android.content.SharedPreferences
    ): File? = preferences.getString(ACTIVE_FILE, null)
            ?.takeIf { profileIsFull(preferences) }
            ?.takeIf(::safeFileName)
            ?.let { File(updateDirectory(context), it) }
            ?.takeIf { isCompatibleDatabase(it, EXPECTED_SCHEMA_VERSION) }

    private fun profileIsFull(preferences: android.content.SharedPreferences): Boolean =
        preferences.getString(ACTIVE_PROFILE, null).let { it == null || it == PROFILE_FULL }

    private fun migrateLegacyBundledFull(context: Context) {
        val legacy = File(context.noBackupFilesDir, LEGACY_BUNDLED_FILE)
        if (!legacy.isFile) return
        val target = File(updateDirectory(context), LEGACY_FULL_FILE)
        if (preserveLegacyFull(legacy, target, LEGACY_BUNDLED_SHA256)) {
            File(context.noBackupFilesDir, "$LEGACY_BUNDLED_FILE.sha256").delete()
        }
    }

    /** Kept internal so JVM tests can exercise the migration without a 103 MiB fixture. */
    internal fun preserveLegacyFull(source: File, target: File, expectedSha256: String): Boolean {
        if (!source.isFile) return false
        if (target.isFile) {
            if (isCompatibleDatabase(target, EXPECTED_SCHEMA_VERSION)) return true
            if (!target.delete()) return false
        }
        if (digest(source) != expectedSha256 ||
            !isCompatibleDatabase(source, EXPECTED_SCHEMA_VERSION)
        ) return false
        return source.renameTo(target)
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun safeFileName(value: String): Boolean =
        value.isNotEmpty() && value == File(value).name && !value.contains("..")

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

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
    internal const val LEGACY_BUNDLED_FILE = "reading-v8.db"
    internal const val LEGACY_FULL_FILE = "legacy-full-v8.db"
    internal const val LEGACY_BUNDLED_SHA256 =
        "991a13b8552748ea2c35fb229446809869a0ceee14ba0107a65351c8527efbc2"
}
