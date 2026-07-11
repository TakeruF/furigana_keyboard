package com.example.furiganakeyboard.data

import android.content.Context
import java.io.File

/** Selects the last fully verified downloadable database, or the bundled fallback. */
object ReadingDataStore {
    private const val PREFERENCES = "reading-data-updates"
    private const val ACTIVE_FILE = "active-file"
    private const val ACTIVE_VERSION = "active-version"
    private const val ACTIVE_DICTIONARY_DATE = "active-dictionary-date"
    private const val DIRECTORY = "reading-updates"

    fun activeOrBundled(
        context: Context,
        assetName: String,
        bundledName: String,
        bundledSha256: String
    ): File {
        val appContext = context.applicationContext
        val selected = preferences(appContext).getString(ACTIVE_FILE, null)
            ?.takeIf(::safeFileName)
            ?.let { File(updateDirectory(appContext), it) }
            ?.takeIf(File::isFile)
        return selected ?: AssetInstaller.ensure(
            appContext,
            assetName,
            bundledName,
            bundledSha256
        )
    }

    fun installedVersion(context: Context): Int =
        preferences(context).getInt(ACTIVE_VERSION, 0)

    fun installedDictionaryDate(context: Context): String? =
        preferences(context).getString(ACTIVE_DICTIONARY_DATE, null)

    internal fun updateDirectory(context: Context): File =
        File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() }

    internal fun activate(
        context: Context,
        file: File,
        version: Int,
        dictionaryDate: String
    ): Boolean {
        require(file.parentFile == updateDirectory(context))
        return preferences(context).edit()
            .putString(ACTIVE_FILE, file.name)
            .putInt(ACTIVE_VERSION, version)
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
}
