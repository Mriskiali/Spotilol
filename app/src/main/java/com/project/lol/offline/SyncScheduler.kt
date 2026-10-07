package com.project.lol.offline

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.project.lol.util.Logger
import java.util.concurrent.TimeUnit

/**
 * Schedules the offline auto-sync periodic worker: WiFi (unmetered) + charging.
 *
 * Manifest entries required (inside <application>, only if the app ever
 * disables the default WorkManager initialiser; normally none):
 * ```
 * <provider
 *     android:name="androidx.startup.InitializationProvider"
 *     android:authorities="${applicationId}.androidx-startup"
 *     tools:node="merge" />
 * ```
 */
object SyncScheduler {
    private const val TAG = "AutoSync"
    private const val WORK_NAME = "spotilol_auto_sync"
    private const val INTERVAL_HOURS = 6L

    /** Idempotent: safe to call from Application.onCreate(). */
    @JvmStatic
    fun schedule(context: Context) {
        if (!DownloadPrefs.isAutoSyncEnabled(context)) {
            cancel(context)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi
            .setRequiresCharging(true)
            .build()
        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        Logger.i(TAG, "auto-sync scheduled (${INTERVAL_HOURS}h, WiFi + charging)")
    }

    @JvmStatic
    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }

    /** Toggle helper for a settings switch; (re)schedules or cancels accordingly. */
    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        DownloadPrefs.setAutoSyncEnabled(context, enabled)
        if (enabled) schedule(context) else cancel(context)
    }
}
