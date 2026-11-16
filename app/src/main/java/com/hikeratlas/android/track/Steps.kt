package com.hikeratlas.android.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.hikeratlas.android.logs.Log
import java.util.Locale

/**
 * Where step counts come from while recording: the phone's step counter
 * ([SensorStepSource]), or a test's own.
 */
interface StepSource {
    /**
     * Starts listening: [onCount] hears the counter's readings, steps since
     * the phone started, on the main thread. False when steps can't be
     * counted (no step counter, or the permission refused).
     */
    fun start(onCount: (Long) -> Unit): Boolean

    /**
     * Has readings held back (batched, with the screen off) delivered, then
     * calls [done]: always, and soon, even where the phone can't flush.
     */
    fun flush(done: () -> Unit)

    fun stop()
}

/**
 * A running step count from a step counter's readings, which count since
 * the phone started: what was walked since the last reading is added. With
 * no [base] yet (just started or resumed), the first reading becomes it; a
 * reading below it means the phone restarted, so it becomes the base too,
 * rather than counting back.
 */
class StepTally(var total: Long, var base: Long?) {
    fun reading(value: Long) {
        val b = base
        base = value
        if (b != null && value >= b) total += value - b
    }
}

/** "3,120 steps", "1 step": grouped as the website shows them, whatever the phone's language. */
fun stepsText(steps: Long): String = String.format(Locale.US, "%,d %s", steps, if (steps == 1L) "step" else "steps")

/** Whether the app may count steps: a runtime permission from Android 10, none before. */
fun canCountSteps(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

/**
 * Android's step counter (Sensor.TYPE_STEP_COUNTER): the low-power
 * hardware one, not Play services. Its readings are cumulative, so batching
 * with the screen off loses nothing; [flush] fetches what's held back.
 */
class SensorStepSource(context: Context) : StepSource {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var listener: SensorEventListener2? = null
    private val waiting = mutableListOf<Pending>()

    /** A flush asked for: [done] called once, when the phone says so or on giving up waiting. */
    private inner class Pending(val done: () -> Unit) {
        private var finished = false
        fun finish() {
            if (finished) return
            finished = true
            waiting.remove(this)
            done()
        }
    }

    private val sensors get() = app.getSystemService(SensorManager::class.java)

    override fun start(onCount: (Long) -> Unit): Boolean {
        stop()
        if (!canCountSteps(app)) return false.also { Log.i("recording", "Steps: no permission") }
        val sm = sensors ?: return false.also { Log.i("recording", "Steps: no sensor service") }
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return false.also { Log.i("recording", "Steps: no step counter") }
        val l = object : SensorEventListener2 {
            override fun onSensorChanged(event: SensorEvent) {
                event.values.firstOrNull()?.let { onCount(it.toLong()) }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

            override fun onFlushCompleted(sensor: Sensor?) {
                handler.post { finishFlush() }
            }
        }
        if (!sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)) {
            Log.w("recording", "Steps: the step counter wouldn't listen")
            return false
        }
        listener = l
        Log.d("recording", "Steps: counting")
        return true
    }

    override fun flush(done: () -> Unit) {
        val l = listener
        val sm = sensors
        if (l == null || sm == null) return done()
        val p = Pending(done).also { waiting += it }
        // Some phones never say the flush is done: it's taken as done after a while.
        if (!sm.flush(l)) p.finish() else handler.postDelayed({ p.finish() }, FLUSH_WAIT_MS)
    }

    private fun finishFlush() = waiting.toList().forEach { it.finish() }

    override fun stop() {
        listener?.let { sensors?.unregisterListener(it) }
        listener = null
        finishFlush()
    }

    private companion object {
        const val FLUSH_WAIT_MS = 2_000L
    }
}

/**
 * Asking for the steps permission (ACTIVITY_RECOGNITION, Android 10+) as
 * recording starts, saying why first. Asked once: refused, tracks are
 * recorded as before, without steps, and it's for the phone's settings
 * after that.
 */
object StepsPermission {
    private const val PREFS = "recording"
    private const val ASKED = "steps_asked"

    /** Whether to ask now: a phone with a step counter, the permission not had, and not asked before. */
    fun toAsk(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER) &&
            !canCountSteps(context) &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    fun asked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(ASKED, true) }
    }
}
