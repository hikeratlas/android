package com.hikeratlas.android.track

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.hikeratlas.android.logs.Log
import java.util.Locale

/** The battery as it is now: what's needed to tell how fast recording drains it. */
data class BatteryReading(
    /** 0–100; null if the phone won't say. */
    val percent: Int?,
    /** Plugged in: drain then means nothing. */
    val charging: Boolean,
    /** What's left, in µAh (finer than the percent); null where the phone doesn't report it. */
    val chargeUah: Long? = null,
    /** Battery saver: it slows GPS and other work, so drain differs. */
    val powerSave: Boolean = false,
    /** The screen's on: often the biggest drain, apart from GPS. */
    val screenOn: Boolean = false,
)

/**
 * Reads the battery without waking anything: the sticky battery broadcast
 * (no receiver kept) and BatteryManager's properties.
 */
fun readBattery(context: Context): BatteryReading {
    val app = context.applicationContext
    val sticky: Intent? = try { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null }
    val bm = app.getSystemService(BatteryManager::class.java)
    val pm = app.getSystemService(PowerManager::class.java)
    val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val percent = if (level >= 0 && scale > 0) level * 100 / scale
    else bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    // Unsupported, it's Int.MIN_VALUE or 0 (it varies by phone).
    val charge = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 }
    return BatteryReading(percent, plugged, charge, pm?.isPowerSaveMode == true, pm?.isInteractive == true)
}

/**
 * The battery in the log while a track is recorded, to learn how much
 * recording uses: a line as it starts, pauses and stops, and between, each
 * time the percent changes (read at most every [checkEveryMs], on fixes the
 * phone is awake for anyway: it never wakes the phone itself). Each line
 * says what's been used since recording started, and how fast.
 */
class BatteryLog(
    private val read: () -> BatteryReading,
    private val clock: () -> Long = System::currentTimeMillis,
    private val checkEveryMs: Long = 60_000L,
    private val log: (String) -> Unit = { Log.i(LOG, it) },
) {
    private var start: BatteryReading? = null
    private var startedAt = 0L
    // Time spent recording (not paused) since the start: drain is per hour of that.
    private var recordingMs = 0L
    private var runningSince: Long? = null
    private var last: BatteryReading? = null
    private var checkedAt = 0L

    /** Recording started or resumed. */
    @Synchronized
    fun recording() {
        val now = clock()
        val r = read()
        if (start == null || r.charging) {
            // A first reading, or charging since: counted afresh from here.
            start = r
            startedAt = now
            recordingMs = 0
        }
        if (runningSince == null) runningSince = now
        line("recording", r, now)
    }

    /** Recording paused. */
    @Synchronized
    fun paused() {
        val now = clock()
        runningSince?.let { recordingMs += now - it }
        runningSince = null
        line("paused", read(), now)
    }

    /** Recording stopped: the last line, with the whole track's drain. */
    @Synchronized
    fun stopped() {
        val now = clock()
        runningSince?.let { recordingMs += now - it }
        runningSince = null
        line("stopped", read(), now)
        start = null
        last = null
    }

    /** A fix came in: the battery's read now and then, and logged when it's changed. */
    @Synchronized
    fun check() {
        if (start == null) return
        val now = clock()
        if (now - checkedAt < checkEveryMs) return
        checkedAt = now
        val r = read()
        // Unplugged since: the drain's counted from here.
        if (start?.charging == true && !r.charging) {
            start = r
            startedAt = now
            recordingMs = 0
            runningSince = runningSince?.let { now }
        }
        val l = last
        if (l != null && r.percent == l.percent && r.charging == l.charging && r.powerSave == l.powerSave) return
        line(null, r, now)
    }

    private fun line(event: String?, r: BatteryReading, now: Long) {
        last = r
        checkedAt = now
        val s = start
        val ms = recordingMs + (runningSince?.let { now - it } ?: 0)
        log("Battery${event?.let { " ($it)" } ?: ""}: ${describe(r, s, now - startedAt, ms)}")
    }

    companion object {
        private const val LOG = "recording"

        /** "71%, 2950 mAh, on battery, screen off · −9% in 1 h 05 min recording (8.3%/h), −410 mAh". */
        internal fun describe(r: BatteryReading, start: BatteryReading?, sinceStartMs: Long, recordingMs: Long): String {
            val now = listOfNotNull(
                r.percent?.let { "$it%" } ?: "level unknown",
                r.chargeUah?.let { "${it / 1000} mAh" },
                if (r.charging) "charging" else "on battery",
                if (r.screenOn) "screen on" else "screen off",
                "battery saver".takeIf { r.powerSave },
            ).joinToString(", ")
            if (start == null || r.charging || start.charging || sinceStartMs <= 0) return now
            val used = listOfNotNull(
                if (r.percent != null && start.percent != null) {
                    val d = start.percent - r.percent
                    val rate = if (recordingMs > 0) String.format(Locale.US, " (%.1f%%/h)", d * 3_600_000.0 / recordingMs) else ""
                    "−$d% in ${duration(recordingMs)} recording$rate"
                } else null,
                if (r.chargeUah != null && start.chargeUah != null) "−${(start.chargeUah - r.chargeUah) / 1000} mAh" else null,
            )
            return if (used.isEmpty()) now else "$now · ${used.joinToString(", ")}"
        }

        private fun duration(ms: Long): String {
            val m = ms / 60_000
            return if (m >= 60) String.format(Locale.US, "%d h %02d min", m / 60, m % 60) else "$m min"
        }
    }
}
