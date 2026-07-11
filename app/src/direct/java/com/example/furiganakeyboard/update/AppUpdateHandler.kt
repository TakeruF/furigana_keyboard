package com.example.furiganakeyboard.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.example.furiganakeyboard.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AppUpdateHandler(
    private val activity: AppCompatActivity,
    @Suppress("UNUSED_PARAMETER") updateFlowLauncher: ActivityResultLauncher<IntentSenderRequest>
) {
    private val downloads = activity.getSystemService(DownloadManager::class.java)
    private val preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val verifying = AtomicBoolean(false)
    private var installPermissionDialogVisible = false
    private var receiverRegistered = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == preferences.getLong(KEY_DOWNLOAD_ID, -1L)) {
                processDownload(id, notifyFailure = true)
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            activity,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            // DownloadManager runs outside this app. The broadcast is treated
            // as untrusted and accepted only when its ID matches our request.
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    fun checkForUpdates(manual: Boolean) {
        thread(name = "direct-update-check", isDaemon = true) {
            val latest = runCatching { DirectUpdateChecker.fetchLatest() }.getOrNull()
            val currentVersion = currentVersionCode()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    latest == null -> if (manual) showToast(R.string.update_check_failed, true)
                    latest.versionCode > currentVersion -> if (manual) {
                        showUpdateDialog(latest)
                    } else {
                        AppUpdateNotifications.showIfNew(activity, latest.versionCode)
                    }
                    manual -> showToast(R.string.update_is_current)
                }
            }
        }
    }

    fun resumeUpdateIfNeeded() {
        val verifiedPath = preferences.getString(KEY_VERIFIED_APK, null)
        if (verifiedPath != null && File(verifiedPath).isFile && canRequestInstall()) {
            launchInstaller(File(verifiedPath))
            return
        }
        val downloadId = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId != -1L) {
            processDownload(downloadId, notifyFailure = false)
        } else {
            cleanupFinishedUpdate()
        }
    }

    fun onUpdateFlowResult(@Suppress("UNUSED_PARAMETER") resultCode: Int) = Unit

    fun close() {
        if (receiverRegistered) {
            runCatching { activity.unregisterReceiver(downloadReceiver) }
            receiverRegistered = false
        }
    }

    private fun showUpdateDialog(latest: DirectUpdateManifest) {
        val message = buildString {
            append(activity.getString(R.string.direct_update_available_version, latest.versionName))
            latest.releaseNotes?.let {
                append("\n\n")
                append(it)
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_notification_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.direct_update_download) { _, _ -> startDownload(latest) }
            .show()
    }

    private fun startDownload(latest: DirectUpdateManifest) {
        val directory = File(
            activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            UPDATE_DIRECTORY
        ).apply { mkdirs() }
        val apk = File(directory, "update-${latest.versionCode}.apk")
        apk.delete()

        val request = DownloadManager.Request(Uri.parse(latest.downloadUrl))
            .setTitle(activity.getString(R.string.update_notification_title))
            .setDescription(activity.getString(R.string.direct_update_downloading))
            .setMimeType(APK_MIME_TYPE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(
                activity,
                Environment.DIRECTORY_DOWNLOADS,
                "$UPDATE_DIRECTORY/${apk.name}"
            )

        runCatching { downloads.enqueue(request) }
            .onSuccess { id ->
                preferences.edit()
                    .putLong(KEY_DOWNLOAD_ID, id)
                    .putLong(KEY_VERSION_CODE, latest.versionCode)
                    .putString(KEY_SHA256, latest.sha256)
                    .putString(KEY_APK_PATH, apk.absolutePath)
                    .remove(KEY_VERIFIED_APK)
                    .apply()
                showToast(R.string.direct_update_download_started)
            }
            .onFailure { showToast(R.string.direct_update_download_failed, true) }
    }

    private fun processDownload(id: Long, notifyFailure: Boolean) {
        val cursor = downloads.query(DownloadManager.Query().setFilterById(id)) ?: return
        val status = cursor.use {
            if (!it.moveToFirst()) return
            it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> verifyDownloadedApk()
            DownloadManager.STATUS_FAILED -> {
                clearDownloadState()
                if (notifyFailure) showToast(R.string.direct_update_download_failed, true)
            }
        }
    }

    private fun verifyDownloadedApk() {
        if (!verifying.compareAndSet(false, true)) return
        val path = preferences.getString(KEY_APK_PATH, null)
        val expectedHash = preferences.getString(KEY_SHA256, null)
        val expectedVersion = preferences.getLong(KEY_VERSION_CODE, -1L)
        thread(name = "direct-update-verify", isDaemon = true) {
            val apk = path?.let(::File)
            val valid = apk != null && apk.isFile && expectedHash != null &&
                runCatching {
                    sha256(apk).equals(expectedHash, ignoreCase = true) &&
                        verifyPackage(apk, expectedVersion)
                }.getOrDefault(false)
            activity.runOnUiThread {
                verifying.set(false)
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (valid) {
                    preferences.edit()
                        .putString(KEY_VERIFIED_APK, apk!!.absolutePath)
                        .remove(KEY_DOWNLOAD_ID)
                        .apply()
                    requestInstall(apk)
                } else {
                    apk?.delete()
                    clearDownloadState()
                    showToast(R.string.direct_update_verification_failed, true)
                }
            }
        }
    }

    private fun verifyPackage(apk: File, expectedVersion: Long): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = activity.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return false
        val current = activity.packageManager.getPackageInfo(activity.packageName, flags)
        return archive.packageName == activity.packageName &&
            PackageInfoCompat.getLongVersionCode(archive) == expectedVersion &&
            expectedVersion > PackageInfoCompat.getLongVersionCode(current) &&
            signerDigests(archive) == signerDigests(current)
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }) ?: return emptySet()
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun requestInstall(apk: File) {
        if (canRequestInstall()) {
            launchInstaller(apk)
            return
        }
        if (installPermissionDialogVisible) return
        installPermissionDialogVisible = true
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.direct_update_install_permission_title)
            .setMessage(R.string.direct_update_install_permission_message)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                installPermissionDialogVisible = false
            }
            .setPositiveButton(R.string.direct_update_install_permission_action) { _, _ ->
                installPermissionDialogVisible = false
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }
            .setOnCancelListener { installPermissionDialogVisible = false }
            .show()
    }

    private fun canRequestInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.update-files",
            apk
        )
        preferences.edit().remove(KEY_VERIFIED_APK).apply()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            clipData = ClipData.newRawUri("Furigana Keyboard update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { showToast(R.string.direct_update_install_failed, true) }
    }

    private fun currentVersionCode(): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun clearDownloadState() {
        preferences.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_VERSION_CODE)
            .remove(KEY_SHA256)
            .remove(KEY_APK_PATH)
            .remove(KEY_VERIFIED_APK)
            .apply()
    }

    private fun cleanupFinishedUpdate() {
        preferences.getString(KEY_APK_PATH, null)?.let { File(it).delete() }
        preferences.edit()
            .remove(KEY_VERSION_CODE)
            .remove(KEY_SHA256)
            .remove(KEY_APK_PATH)
            .apply()
    }

    private fun showToast(resource: Int, long: Boolean = false) {
        Toast.makeText(
            activity,
            resource,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    private companion object {
        const val PREFERENCES = "direct-app-updates"
        const val KEY_DOWNLOAD_ID = "download-id"
        const val KEY_VERSION_CODE = "version-code"
        const val KEY_SHA256 = "sha256"
        const val KEY_APK_PATH = "apk-path"
        const val KEY_VERIFIED_APK = "verified-apk"
        const val UPDATE_DIRECTORY = "furigana-updates"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
