package com.example.furiganakeyboard.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.install.model.UpdateAvailability
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AppUpdateWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val updateInfo = awaitUpdateInfo() ?: return Result.retry()
        if (updateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
            AppUpdateNotifications.showIfNew(applicationContext, updateInfo.availableVersionCode())
        }
        return Result.success()
    }

    private suspend fun awaitUpdateInfo(): AppUpdateInfo? = suspendCancellableCoroutine { continuation ->
        AppUpdateManagerFactory.create(applicationContext).appUpdateInfo
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
            .addOnCanceledListener { if (continuation.isActive) continuation.resume(null) }
    }
}
