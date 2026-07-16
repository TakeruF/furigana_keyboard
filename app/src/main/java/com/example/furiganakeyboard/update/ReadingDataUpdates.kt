package com.example.furiganakeyboard.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ReadingDataUpdates {
    fun initialize(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val firstDownload = OneTimeWorkRequestBuilder<ReadingDataUpdateWorker>()
            .setConstraints(constraints)
            .build()
        val periodic = PeriodicWorkRequestBuilder<ReadingDataUpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork(
            FIRST_DOWNLOAD_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            firstDownload
        )
        manager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
    }

    private const val WORK_NAME = "reading-data-update"
    private const val FIRST_DOWNLOAD_WORK_NAME = "reading-data-initial-full-download"
}
