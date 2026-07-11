package com.example.furiganakeyboard.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import com.example.furiganakeyboard.R
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

class AppUpdateHandler(
    private val activity: AppCompatActivity,
    private val updateFlowLauncher: ActivityResultLauncher<IntentSenderRequest>
) {
    private val manager = AppUpdateManagerFactory.create(activity)

    fun checkForUpdates(manual: Boolean) {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                    if (manual) {
                        if (info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                            manager.startUpdateFlowForResult(
                                info,
                                updateFlowLauncher,
                                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                            )
                        } else {
                            openPlayStore()
                        }
                    } else {
                        AppUpdateNotifications.showIfNew(
                            activity,
                            info.availableVersionCode().toLong()
                        )
                    }
                } else if (manual) {
                    Toast.makeText(activity, R.string.update_is_current, Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                if (manual) {
                    Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                }
            }
    }

    fun resumeUpdateIfNeeded() {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() ==
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
            ) {
                manager.startUpdateFlowForResult(
                    info,
                    updateFlowLauncher,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                )
            }
        }
    }

    fun onUpdateFlowResult(resultCode: Int) {
        if (resultCode != Activity.RESULT_OK) {
            Toast.makeText(activity, R.string.update_cancelled, Toast.LENGTH_SHORT).show()
        }
    }

    fun close() = Unit

    private fun openPlayStore() {
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=${activity.packageName}")
        )
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=${activity.packageName}")
        )
        runCatching { activity.startActivity(marketIntent) }
            .onFailure { activity.startActivity(webIntent) }
    }
}
