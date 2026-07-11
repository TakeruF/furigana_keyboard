package com.example.furiganakeyboard.update

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class ReadingDataUpdateWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker(context, parameters) {
    override fun doWork(): Result = runCatching {
        ReadingDataUpdater.update(applicationContext)
        Result.success()
    }.getOrElse { error ->
        Log.w(TAG, "Reading data update was unavailable; keeping local data", error)
        // This is periodic work. Waiting for the next interval avoids repeatedly
        // hitting an unavailable endpoint while preserving the verified fallback.
        Result.success()
    }

    private companion object {
        const val TAG = "ReadingDataUpdate"
    }
}
