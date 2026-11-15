package com.hikeratlas.android.outbox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.hikeratlas.android.R

/** Whatever holds the app's [Outbox] (the app; a test's own). */
interface OutboxHost {
    val outbox: Outbox
}

/**
 * Posts one waiting post, once there's a network (its constraint), in the
 * foreground while it uploads (photos can take a while on poor signal).
 * A network error or 5xx tries again with backoff, for as long as it takes;
 * a refusal stops it (failed, said in a notification); so do an ended
 * session and another account signed in, until the account signs in
 * ([Outbox.start] resumes it then).
 */
class PostWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(POST) ?: return Result.success()
        val outbox = (applicationContext as OutboxHost).outbox
        val entry = outbox.entry(id) ?: return Result.success() // posted or discarded meanwhile
        try {
            setForeground(foregroundInfo(entry.title))
        } catch (e: IllegalStateException) {
            // Not allowed to show (started from the background): carry on without.
        }
        return when (outbox.upload(id, notify = true)) {
            is Outbox.Outcome.Waiting -> Result.retry()
            is Outbox.Outcome.Failed -> Result.failure()
            // Posted, gone, or waiting for a sign-in (which schedules it again).
            else -> Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("your post")

    private fun foregroundInfo(title: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Posting", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a post made without signal goes up."
            },
        )
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Posting $title")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(NOTIFICATION_ID, n, type)
    }

    companion object {
        const val POST = "post"
        private const val CHANNEL = "posting"
        private const val NOTIFICATION_ID = 3
    }
}
