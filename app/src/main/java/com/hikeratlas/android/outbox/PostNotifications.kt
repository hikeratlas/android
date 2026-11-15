package com.hikeratlas.android.outbox

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hikeratlas.android.R

/**
 * Says when a waiting post went up in the background, failed for good, or
 * needs the user to sign in again. Nothing is shown when notifications
 * aren't allowed (asked for with recording; never asked again here): the
 * Waiting posts list says the same.
 */
class PostNotifications(private val context: Context) {
    fun posted(e: Outbox.Entry) = show(e, "Posted: ${e.title}", "It's on your profile.")

    fun failed(e: Outbox.Entry) = show(e, "Couldn't post ${e.title}", "${(e.error ?: "It was refused").trimEnd('.')}. See Waiting posts on your profile.")

    fun signIn(e: Outbox.Entry) =
        show(e, "Sign in to post ${e.title}", "Your session ended. It's kept on your phone, and goes up once you sign in again (You tab).")

    fun cancel(id: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(TAG, id.hashCode())
    }

    private fun show(e: Outbox.Entry, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Waiting posts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a post made without signal goes up, or can't."
            },
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // One per post: a later one (posted, after "sign in") replaces the earlier.
        nm.notify(TAG, e.id.hashCode(), n)
    }

    companion object {
        const val CHANNEL = "posts"
        private const val TAG = "post"
    }
}
