package com.project.lol.offline

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.project.lol.service.DownloadService
import com.project.lol.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Offline auto-sync: on WiFi + charging, downloads Liked Songs and the
 * user-selected playlists/albums that are not saved yet.
 *
 * Source of truth is a payload cache: the UI saves each collection's
 * [DownloadManager.downloadCollection] JSON payload via [saveSyncPayload]
 * (Liked Songs under id [LIKED_ID]) whenever it is viewed. The worker replays
 * those payloads; [DownloadManager] itself skips tracks where
 * [OfflineStore.isTrackSaved] is true, so a sync only fetches what's missing.
 *
 * No manifest entries needed: WorkManager initialises via manifest merger.
 * See [SyncScheduler] for the periodic request (UNMETERED + charging).
 */
class AutoSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "AutoSync"
        const val LIKED_ID = "liked"
        private const val AWAIT_TIMEOUT_MS = 30 * 60_000L

        private fun syncDir(context: Context): File =
            File(context.filesDir, "sync").apply { mkdirs() }

        /** Cache a downloadCollection payload for later sync. Overwrites same id. */
        @JvmStatic
        fun saveSyncPayload(context: Context, id: String, payloadJson: String) {
            runCatching {
                File(syncDir(context), "$id.json").writeText(payloadJson)
            }.onFailure { Logger.w(TAG, "saveSyncPayload($id) failed: ${it.message}") }
        }

        @JvmStatic
        fun removeSyncPayload(context: Context, id: String) {
            runCatching { File(syncDir(context), "$id.json").delete() }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!DownloadPrefs.isAutoSyncEnabled(applicationContext)) {
            Logger.i(TAG, "auto-sync disabled, skipping")
            return@withContext Result.success()
        }
        // ponytail: payload cache only; live Spotify API fetch when an authed HTTP client exists.
        val ids = (listOf(LIKED_ID) + DownloadPrefs.syncIds(applicationContext)).distinct()
        val payloads = ids.mapNotNull { id ->
            File(syncDir(applicationContext), "$id.json")
                .takeIf { it.exists() && it.length() > 0 }
                ?.let { id to it.readText() }
        }
        if (payloads.isEmpty()) {
            Logger.i(TAG, "auto-sync: nothing cached, skipping")
            return@withContext Result.success()
        }
        runCatching {
            ContextCompat.startForegroundService(
                applicationContext,
                android.content.Intent(applicationContext, DownloadService::class.java),
            )
        }
        var synced = 0
        for ((id, payload) in payloads) {
            if (isStopped) return@withContext Result.success()
            runCatching {
                DownloadManager.downloadCollection(applicationContext, payload)
                awaitIdle()
                synced++
            }.onFailure { Logger.w(TAG, "auto-sync($id) failed: ${it.message}") }
        }
        Logger.i(TAG, "auto-sync done: $synced/${payloads.size} collections")
        Result.success()
    }

    private suspend fun awaitIdle() {
        withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
            // downloadCollection queues async; poll until the queue drains.
            delay(1_000L)
            while (DownloadManager.isDownloading() ||
                DownloadManager.isWorkPending() ||
                DownloadManager.isBatchActive()
            ) {
                if (isStopped) break
                delay(2_000L)
            }
        }
    }
}
