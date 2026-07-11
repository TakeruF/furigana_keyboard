package com.example.furiganakeyboard.update

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppUpdateWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val latest = runCatching {
            withContext(Dispatchers.IO) { DirectUpdateChecker.fetchLatest() }
        }
            .getOrElse { return Result.retry() }
        val packageInfo = applicationContext.packageManager.getPackageInfo(
            applicationContext.packageName,
            0
        )
        if (latest.versionCode > PackageInfoCompat.getLongVersionCode(packageInfo)) {
            AppUpdateNotifications.showIfNew(applicationContext, latest.versionCode)
        }
        return Result.success()
    }
}
