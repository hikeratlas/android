package com.hikeratlas.android.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hikeratlas.android.R
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Downloads one offline area, in the foreground (a notification shows its
 * progress). WorkManager runs it when its network rule is met (Wi-Fi only,
 * by default), retries it with backoff after a network error, and stops it
 * if the rule stops being met; it then picks up where it was.
 */
class AreaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val areas get() = (applicationContext as OfflineHost).offlineAreas
    private var notifiedAt = 0L

    override suspend fun doWork(): Result {
        val id = inputData.getLong(AREA, -1)
        val store = areas.store
        val area = store.areaById(id) ?: return Result.success() // deleted meanwhile
        val refresh = inputData.getBoolean(REFRESH, false)
        // Already downloaded (a duplicate run), unless it's an update.
        if (area.ready && !refresh) return Result.success()
        var finished = false
        try {
            try {
                setForeground(foregroundInfo(area.name, 0, 0))
            } catch (e: IllegalStateException) {
                // Not allowed to show (started from the background): carry on without.
            }
            store.setAreaState(id, OfflineStore.DOWNLOADING)
            areas.refresh()
            val target = OfflineAreas.Target(area.kind, area.ref, area.name)
            // The builds it's downloaded from, so a newer one can offer an update.
            val build = areas.buildNow()
            areas.keepParkData(area)
            areas.downloader().download(id, areas.plan(target), refetchVector = refresh) { done, total ->
                store.setAreaProgress(id, done, total)
                setProgress(workDataOf(DONE to done, TOTAL to total))
                val now = System.currentTimeMillis()
                if (now - notifiedAt >= NOTIFY_EVERY_MS) {
                    notifiedAt = now
                    try { setForeground(foregroundInfo(area.name, done, total)) } catch (e: IllegalStateException) { }
                    areas.refresh()
                }
            }
            store.finishArea(id)
            if (build != null) store.setAreaBuild(id, build.key)
            finished = true
            return Result.success()
        } catch (e: CancellationException) {
            // Stopped (Wi-Fi went, say): it waits, and resumes from where it was.
            throw e
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            store.setAreaState(id, OfflineStore.FAILED, e.message ?: e.toString())
            finished = true
            return Result.failure()
        } finally {
            if (!finished && store.areaById(id)?.ready == false) store.setAreaState(id, OfflineStore.QUEUED)
            areas.refresh()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("offline area", 0, 0)

    private fun foregroundInfo(name: String, done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Offline downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a park or hike downloads for offline use."
            },
        )
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Downloading $name")
            .setContentText(if (total > 0) "${done * 100 / total}% · for offline use" else "For offline use")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(NOTIFICATION_ID, n, type)
    }

    companion object {
        const val AREA = "area"
        /** An update: the map and routes fetched again even where kept. */
        const val REFRESH = "refresh"
        const val DONE = "done"
        const val TOTAL = "total"
        private const val CHANNEL = "offline"
        private const val NOTIFICATION_ID = 2
        private const val NOTIFY_EVERY_MS = 2_000L
        private const val MAX_ATTEMPTS = 5
    }
}
