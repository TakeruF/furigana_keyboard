package com.example.furiganakeyboard.update

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Base64
import com.example.furiganakeyboard.data.ReadingDataStore
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Downloads only signed, schema-compatible reading databases over HTTPS. */
object ReadingDataUpdater {
    enum class Result { UPDATED, CURRENT, INCOMPATIBLE }

    fun update(context: Context): Result {
        val manifestBytes = get(MANIFEST_URL, MAX_MANIFEST_BYTES)
        val signatureBytes = Base64.decode(
            get("$MANIFEST_URL.sig", MAX_SIGNATURE_BYTES),
            Base64.DEFAULT
        )
        check(verifySignature(manifestBytes, signatureBytes)) {
            "Reading update manifest signature is invalid"
        }
        val manifest = Manifest.parse(manifestBytes)
        if (manifest.minAppVersion > appVersion(context) ||
            manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION
        ) return Result.INCOMPATIBLE
        if (manifest.dataVersion <= ReadingDataStore.installedVersion(context)) {
            return Result.CURRENT
        }

        val directory = ReadingDataStore.updateDirectory(context)
        val temporary = File(directory, "reading-${manifest.dataVersion}.tmp")
        val target = File(directory, "reading-${manifest.dataVersion}.db")
        temporary.delete()
        downloadDatabase(manifest, temporary)
        try {
            validateDatabase(temporary, manifest.schemaVersion)
            if (target.exists()) check(target.delete()) { "Could not replace ${target.name}" }
            check(temporary.renameTo(target)) { "Could not install ${target.name}" }
            check(
                ReadingDataStore.activate(
                    context,
                    target,
                    manifest.dataVersion,
                    manifest.dictionaryDate
                )
            ) { "Could not activate ${target.name}" }
            ReadingDataStore.removeInactive(context, target)
        } finally {
            temporary.delete()
        }
        return Result.UPDATED
    }

    private fun downloadDatabase(manifest: Manifest, target: File) {
        require(manifest.databaseSize in 1..MAX_DATABASE_BYTES) { "Invalid database size" }
        val connection = connection(manifest.databaseUrl)
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(target).use { fileOutput ->
                    val output = fileOutput.buffered(DOWNLOAD_BUFFER_SIZE)
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        check(written <= manifest.databaseSize) { "Database exceeds published size" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
        check(written == manifest.databaseSize) { "Database size mismatch" }
        check(digest.digest().hex() == manifest.databaseSha256) { "Database hash mismatch" }
    }

    private fun validateDatabase(file: File, expectedSchema: Int) {
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
        db.use {
            val integrity = it.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            check(integrity == "ok") { "Database integrity check failed: $integrity" }
            val schema = it.rawQuery(
                "SELECT value FROM metadata WHERE key='schema_version'",
                null
            ).use { cursor ->
                check(cursor.moveToFirst()) { "Database schema metadata is missing" }
                cursor.getString(0).toInt()
            }
            check(schema == expectedSchema) { "Database schema mismatch" }
        }
    }

    private fun get(url: String, limit: Long): ByteArray {
        val connection = connection(url)
        return try {
            BufferedInputStream(connection.inputStream).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= limit) { "Update response is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun connection(rawUrl: String): HttpURLConnection {
        val url = URL(rawUrl)
        require(url.protocol == "https") { "Reading updates require HTTPS" }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json, application/octet-stream")
            connect()
            check(responseCode == HttpURLConnection.HTTP_OK) {
                "Reading update request failed with HTTP $responseCode"
            }
        }
    }

    private fun verifySignature(message: ByteArray, signature: ByteArray): Boolean {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_DER_BASE64, Base64.DEFAULT))
        )
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(message)
            verify(signature)
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): Long = if (Build.VERSION.SDK_INT >= 28) {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } else {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private data class Manifest(
        val dataVersion: Int,
        val schemaVersion: Int,
        val minAppVersion: Long,
        val databaseUrl: String,
        val databaseSize: Long,
        val databaseSha256: String,
        val dictionaryDate: String
    ) {
        companion object {
            fun parse(bytes: ByteArray): Manifest {
                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                check(json.getInt("formatVersion") == 1) { "Unsupported manifest format" }
                val hash = json.getString("databaseSha256").lowercase()
                check(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid database hash" }
                return Manifest(
                    dataVersion = json.getInt("dataVersion"),
                    schemaVersion = json.getInt("schemaVersion"),
                    minAppVersion = json.getLong("minAppVersion"),
                    databaseUrl = json.getString("databaseUrl"),
                    databaseSize = json.getLong("databaseSize"),
                    databaseSha256 = hash,
                    dictionaryDate = json.optString("dictionaryDate")
                )
            }
        }
    }

    private const val MANIFEST_URL =
        "https://downloads.hanlu.app/furigana/manifest.json"
    private const val PUBLIC_KEY_DER_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE9fXKWi9gKlKzeFvoERpCuEm0cpuo7LZ8bhqU0ZDU8BV1naCjNzdHDg6uW04s4P0x1Q4yFKv+w7kLN6j0HKGhGQ=="
    private const val SUPPORTED_SCHEMA_VERSION = 7
    private const val MAX_MANIFEST_BYTES = 32L * 1024
    private const val MAX_SIGNATURE_BYTES = 4L * 1024
    private const val MAX_DATABASE_BYTES = 128L * 1024 * 1024
    private const val DOWNLOAD_BUFFER_SIZE = 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 120_000
}
