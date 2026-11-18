package com.hikeratlas.android.track

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hikeratlas.android.R
import com.hikeratlas.android.logs.Log
import com.hikeratlas.android.units.dist

/** Whatever holds the app's [TrackRecorder] (the app; a test's own). */
interface RecorderHost {
    val recorder: TrackRecorder
}

/**
 * Records a track with the screen off or the app in the background: a
 * foreground service (so a notification shows while it runs) listening to
 * the GPS itself, not Google's fused location, so it works the same without
 * Play services and records as often as asked. Stops listening while paused.
 */
class RecordingService : Service() {
    private val recorder get() = (application as RecorderHost).recorder
    private var listening = false
    private var notifiedAt = 0L
    /** Whether GPS has given a fix since it was turned on, for the log. */
    private var hadFix = false

    private val listener = LocationListener { l -> onLocation(l) }

    // To learn how much recording uses: logged as it starts, pauses and
    // stops, and as the level drops (read on fixes: it wakes nothing).
    private val battery by lazy { BatteryLog({ readBattery(this) }) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(LOG, "Command ${intent?.action?.substringAfterLast('.') ?: "restart"}")
        when (intent?.action) {
            ACTION_START -> {
                foreground()
                // A track left paused (the app was killed) carries on; else a new one.
                if (!recorder.resumeUnfinished(paused = false)) recorder.start()
                recorder.resume()
                listen()
                battery.recording()
            }
            ACTION_PAUSE -> {
                recorder.pause()
                unlisten()
                notifyNow()
                battery.paused()
            }
            ACTION_STOP -> {
                recorder.stop()
                stop()
                battery.stopped()
            }
            // Restarted by the system after the app was killed mid-track.
            null -> if (recorder.resumeUnfinished(paused = false) && recorder.state?.paused == false) {
                Log.i(LOG, "Restarted by the system after the app was killed: recording on")
                try {
                    foreground()
                    listen()
                    battery.recording()
                } catch (e: Exception) {
                    // Not allowed to start in the background: it's left
                    // unfinished, and comes back paused when the app opens.
                    Log.w(LOG, "Couldn't carry on in the background: left for the app to resume", e)
                    stop()
                }
            } else {
                Log.i(LOG, "Restarted by the system with nothing recording: stopping")
                stop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unlisten()
        super.onDestroy()
    }

    private fun stop() {
        unlisten()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission") // The app asks for it before starting this.
    private fun listen() {
        if (listening) return
        val lm = getSystemService(LocationManager::class.java) ?: return Log.w(LOG, "No location service: no GPS")
        val enabled = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }
        if (enabled) Log.i(LOG, "GPS on") else Log.w(LOG, "GPS is turned off: no fixes until it's on")
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
        listening = true
    }

    private fun unlisten() {
        if (!listening) return
        getSystemService(LocationManager::class.java)?.removeUpdates(listener)
        listening = false
        hadFix = false
        Log.d(LOG, "GPS off")
    }

    private fun onLocation(l: Location) {
        if (!hadFix) {
            hadFix = true
            Log.i(LOG, "First GPS fix, ±${if (l.hasAccuracy()) l.accuracy.toInt() else "?"} m")
        }
        Log.d(LOG, "Fix ${Log.point(l.latitude, l.longitude)} ±${if (l.hasAccuracy()) l.accuracy.toInt() else "?"} m")
        recorder.onFix(l.toFix())
        battery.check()
        val now = System.currentTimeMillis()
        if (now - notifiedAt >= NOTIFY_EVERY_MS) notifyNow()
    }

    private fun foreground() {
        channel(this)
        // The type is required from Android 14; it exists from 10.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        notifiedAt = System.currentTimeMillis()
    }

    private fun notifyNow() {
        notifiedAt = System.currentTimeMillis()
        if (recorder.state == null) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val s = recorder.state
        val text = when {
            s == null -> "Starting…"
            s.paused -> "Paused · ${dist(s.metres / 1000, 1)}"
            else -> "Recording · ${dist(s.metres / 1000, 1)}"
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Hiker Atlas")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.hikeratlas.android.track.START"
        const val ACTION_PAUSE = "com.hikeratlas.android.track.PAUSE"
        const val ACTION_STOP = "com.hikeratlas.android.track.STOP"
        private const val LOG = "recording"

        /** How often GPS is asked for a fix. */
        const val INTERVAL_MS = 2_000L
        private const val NOTIFY_EVERY_MS = 10_000L
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL = "recording"

        /** Starts recording, or resumes a paused track. Location permission must be granted. */
        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(ACTION_START))

        fun pause(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_PAUSE))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }

        private fun channel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Track recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a track is being recorded."
                },
            )
        }

        fun Location.toFix() = Fix(
            t = time,
            lat = latitude,
            lon = longitude,
            accuracy = if (hasAccuracy()) accuracy else null,
            altitude = if (hasAltitude()) altitude else null,
            mslAltitude = if (Build.VERSION.SDK_INT >= 34 && hasMslAltitude()) mslAltitudeMeters else null,
            verticalAccuracy = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
            speed = if (hasSpeed()) speed else null,
        )
    }
}
