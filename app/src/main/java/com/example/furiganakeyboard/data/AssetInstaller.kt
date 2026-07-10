package com.example.furiganakeyboard.data

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.DigestInputStream

/** Installs a compressed APK asset atomically and verifies its published hash. */
object AssetInstaller {
    @Synchronized
    fun ensure(context: Context, assetName: String, outputName: String, sha256: String): File {
        val target = File(context.noBackupFilesDir, outputName)
        val marker = File(context.noBackupFilesDir, "$outputName.sha256")
        if (target.isFile && marker.isFile && runCatching { marker.readText() == sha256 }.getOrDefault(false)) {
            return target
        }
        if (target.isFile && digest(target) == sha256) {
            marker.writeText(sha256)
            return target
        }

        val temporary = File(context.noBackupFilesDir, "$outputName.tmp")
        temporary.delete()
        val copyDigest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(context.assets.open(assetName), copyDigest).use { input ->
            temporary.outputStream().buffered(ASSET_BUFFER_SIZE).use { output ->
                input.copyTo(output, ASSET_BUFFER_SIZE)
            }
        }
        check(copyDigest.digest().hex() == sha256) {
            "Bundled asset failed integrity check: $assetName"
        }
        if (target.exists()) check(target.delete()) { "Could not replace $outputName" }
        check(temporary.renameTo(target)) { "Could not install $outputName" }
        marker.writeText(sha256)
        return target
    }

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
        return md.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private const val ASSET_BUFFER_SIZE = 1024 * 1024
}
