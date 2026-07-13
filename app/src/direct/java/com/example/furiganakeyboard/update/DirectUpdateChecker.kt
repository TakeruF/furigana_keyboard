package com.example.furiganakeyboard.update

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

data class DirectUpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val releaseNotes: String?
) {
    companion object {
        fun parse(json: String): DirectUpdateManifest {
            val value = JSONObject(json)
            val versionCode = value.getLong("versionCode")
            val versionName = value.getString("versionName").trim()
            val downloadUrl = value.getString("downloadUrl").trim()
            val sha256 = value.getString("sha256").trim().lowercase()
            val releaseNotes = value.optString("releaseNotes").trim().ifEmpty { null }

            require(versionCode > 0) { "versionCode must be positive" }
            require(versionName.isNotEmpty()) { "versionName must not be empty" }
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "sha256 must be a SHA-256 digest" }
            validateDownloadUrl(downloadUrl)
            return DirectUpdateManifest(versionCode, versionName, downloadUrl, sha256, releaseNotes)
        }

        private fun validateDownloadUrl(value: String) {
            val url = URL(value)
            require(url.protocol == "https") { "APK download must use HTTPS" }
            require(url.host.equals(DOWNLOAD_HOST, ignoreCase = true)) {
                "APK download must use $DOWNLOAD_HOST"
            }
            val fileName = url.path.removePrefix(DOWNLOAD_PATH_PREFIX)
            require(
                url.path.startsWith(DOWNLOAD_PATH_PREFIX) &&
                    fileName.isNotEmpty() &&
                    !fileName.contains('/')
            ) {
                "APK download must use $DOWNLOAD_PATH_PREFIX"
            }
            require(url.path.endsWith(".apk", ignoreCase = true)) {
                "APK download URL must point to an APK"
            }
        }
    }
}

object DirectUpdateChecker {
    fun fetchLatest(): DirectUpdateManifest {
        val connection = URL(MANIFEST_URL).openConnection() as HttpsURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "FuriganaKeyboard-Android")
        return try {
            require(connection.responseCode == HttpsURLConnection.HTTP_OK) {
                "Update manifest request failed with HTTP ${connection.responseCode}"
            }
            require(connection.url.protocol == "https" &&
                connection.url.host.equals(DOWNLOAD_HOST, ignoreCase = true)
            ) { "Update manifest redirected outside $DOWNLOAD_HOST" }
            val json = connection.inputStream.use(::readDirectUpdateManifest)
            DirectUpdateManifest.parse(json)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun readDirectUpdateManifest(stream: InputStream): String {
    val output = ByteArrayOutputStream()
    stream.buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_MANIFEST_BYTES) {
                "Update manifest is too large"
            }
            output.write(buffer, 0, count)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}

private const val DOWNLOAD_HOST = "downloads.hanlu.app"
private const val DOWNLOAD_PATH_PREFIX = "/furigana-keyboard/"
private const val MANIFEST_URL = "https://downloads.hanlu.app/latest.json"
private const val MAX_MANIFEST_BYTES = 64 * 1024
