package com.turbotranslate.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.util.Locale

/**
 * Runs [UpdateChecker.fetchLatest] on a background thread and delivers the
 * result on the main thread — dropped if [this] has since been closed.
 */
fun Activity.fetchLatestUpdate(onResult: (Result<UpdateRelease?>) -> Unit) {
    Thread {
        val result = try {
            Result.success(UpdateChecker.fetchLatest(this))
        } catch (e: Exception) {
            Result.failure(e)
        }
        runOnUiThread {
            if (!isFinishing && !isDestroyed) onResult(result)
        }
    }.start()
}

/**
 * Asks the user whether to install [release] (noting that Wi-Fi is preferred
 * for the download), and on Install downloads it and hands it to the system
 * installer. Same prompt as Flip Launcher's.
 */
fun Activity.showUpdatePrompt(release: UpdateRelease) {
    val sizeMb = String.format(Locale.getDefault(), "%.1f", release.apkSizeBytes / 1_048_576.0)
    var message = "A new version of TurboTranslate is ready to install ($sizeMb MB).\n\n" +
        "A Wi-Fi connection is preferred for downloading updates."
    if (!UpdateChecker.isOnWifi(this)) {
        message += "\n\nYou are currently on mobile data."
    }
    AlertDialog.Builder(this)
        .setTitle("Update available: v${release.versionName}")
        .setMessage(message)
        .setPositiveButton("Install") { _, _ ->
            if (canInstallUpdates()) downloadAndInstall(release) else openInstallPermissionSettings()
        }
        .setNegativeButton("Later", null)
        .show()
}

// Android 8+ asks per app whether it may install other APKs. Checked up
// front, so the user isn't sent off mid-download.
private fun Activity.canInstallUpdates(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

private fun Activity.openInstallPermissionSettings() {
    val appPage = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
    try {
        startActivity(appPage)
    } catch (e: Exception) {
        // Some OEM builds have no per-app page; open the general list instead.
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
        } catch (e2: Exception) {
            // Nothing to open — the toast below still explains the path.
        }
    }
    Toast.makeText(
        this,
        "Turn on \"Allow from this source\" for TurboTranslate, press Back, then 0 > Check for updates again",
        Toast.LENGTH_LONG
    ).show()
}

private fun Activity.downloadAndInstall(release: UpdateRelease) {
    val progress = AlertDialog.Builder(this)
        .setMessage("Downloading update…")
        .setCancelable(false)
        .show()
    Thread {
        val apk = try {
            UpdateChecker.download(this, release)
        } catch (e: Exception) {
            null
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            progress.dismiss()
            if (apk == null) {
                Toast.makeText(this, "Update download failed", Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            try {
                startActivity(UpdateChecker.installIntent(this, apk))
            } catch (e: Exception) {
                Toast.makeText(this, "Update download failed", Toast.LENGTH_LONG).show()
            }
        }
    }.start()
}
