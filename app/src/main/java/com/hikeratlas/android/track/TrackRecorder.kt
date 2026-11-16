package com.hikeratlas.android.track

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hikeratlas.android.elevation.ElevationProfiler
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The track being recorded, if any: what the recording bar and the map show,
 * and what [RecordingService] feeds fixes into. Kept fixes go straight to
 * [store]. Steps are counted from [steps] only while recording (not while
 * paused), and only for a track counted from its start: one started without
 * a step counter, or without the permission, has none, rather than a part.
 */
class TrackRecorder(
    private val store: TrackStore,
    /** Measures the climb from the DEM, for a track without GPS altitudes; null for none. */
    private val profiler: ElevationProfiler?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val background: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "track-climb").apply { isDaemon = true } },
    /** The step counter; null for none. */
    private val steps: StepSource? = null,
) {
    data class State(
        val trackId: Long,
        val started: Long,
        val pausedMs: Long,
        /** When it was paused, if it is. */
        val pausedAt: Long?,
        /** The kept fixes as [lng, lat], a segment per stretch between pauses. */
        val segments: List<List<List<Double>>>,
        /** As the finished track's GPX will measure (the website's way). */
        val metres: Double,
        /**
         * Climb from GPS altitudes, as the finished track's; from the DEM
         * where GPS gives none (null until first measured, or where there's
         * no DEM).
         */
        val gain: Double?,
        /** Steps counted so far; null when the track isn't counted. */
        val steps: Long? = null,
    ) {
        val paused get() = pausedAt != null
        /** Time recording, not counting pauses. */
        fun elapsed(now: Long) = (pausedAt ?: now) - started - pausedMs
    }

    var state by mutableStateOf<State?>(null)
        private set

    private var log = TrackLog()
    private var measuredAt = 0L
    private var measuring = false

    // Steps: the track counted ([stepTrack]) and its tally, open while
    // recording. Closing waits for a flush of the counter (its readings are
    // batched with the screen off), so [stepGen] tells a close still
    // waiting that it was overtaken (resumed meanwhile).
    private var stepTrack: Long? = null
    private var tally: StepTally? = null
    private var stepsOpen = false
    private var listening = false
    private var stepGen = 0
    private var stepsSavedAt = 0L
    private var flushed = CompletableDeferred(Unit)

    /** Starts a new track (if none is being recorded). */
    @Synchronized
    fun start(): State {
        state?.let { return it }
        val now = clock()
        log = TrackLog()
        measuredAt = 0L
        val id = store.start(now)
        stopListening()
        stepTrack = id
        tally = StepTally(0, null)
        // Counted from the start, or not at all.
        if (!openSteps()) {
            tally = null
            stepTrack = null
        }
        return State(id, now, 0, null, emptyList(), 0.0, null, tally?.total).also { state = it }
    }

    /**
     * Picks up a track left unfinished (the app was killed while recording).
     * [paused]: as paused, from its last fix, for the user to resume or stop.
     * False if there's none.
     */
    @Synchronized
    fun resumeUnfinished(paused: Boolean): Boolean {
        if (state != null) return true
        val t = store.unfinished() ?: return false
        log = TrackLog()
        val points = store.points(t.id)
        for ((seg, f) in points) log.restore(f, seg)
        var pausedAt = t.pausedAt
        if (paused && pausedAt == null) {
            pausedAt = points.lastOrNull()?.second?.t ?: t.started
            store.setPaused(t.id, t.pausedMs, pausedAt)
        }
        if (pausedAt != null) log.breakSegment()
        measuredAt = 0L
        stopListening()
        stepTrack = t.id.takeIf { t.steps != null }
        // Still recording (restarted after being killed): counted on from the
        // counter's last saved reading, so what was walked meanwhile counts.
        // Paused: the next resume reads the counter afresh.
        tally = t.steps?.let { StepTally(it, if (pausedAt == null) t.stepsBase else null) }
        if (pausedAt != null && t.stepsBase != null) store.setSteps(t.id, t.steps ?: 0, null)
        state = State(t.id, t.started, t.pausedMs, pausedAt, lines(), metres(), gpsGain(), t.steps)
        if (pausedAt == null) openSteps(rebase = false)
        measure()
        return true
    }

    @Synchronized
    fun pause() {
        val s = state ?: return
        if (s.paused) return
        val now = clock()
        store.setPaused(s.trackId, s.pausedMs, now)
        log.breakSegment()
        state = s.copy(pausedAt = now)
        closeSteps()
    }

    @Synchronized
    fun resume() {
        val s = state ?: return
        val at = s.pausedAt ?: return
        val pausedMs = s.pausedMs + (clock() - at).coerceAtLeast(0)
        store.setPaused(s.trackId, pausedMs, null)
        state = s.copy(pausedMs = pausedMs, pausedAt = null)
        openSteps()
    }

    /** Ends the track: kept, as recorded. Its id, if there was one. */
    @Synchronized
    fun stop(): Long? {
        val s = state ?: return null
        val now = clock()
        if (s.pausedAt != null) store.setPaused(s.trackId, s.pausedMs + (now - s.pausedAt).coerceAtLeast(0), null)
        store.end(s.trackId, now)
        state = null
        closeSteps()
        return s.trackId
    }

    /**
     * Waits for the steps of a track just paused or stopped to be read to
     * the end (the counter flushed), so its numbers include them.
     */
    suspend fun stepsSettled() {
        val d = synchronized(this) { flushed }
        d.await()
    }

    /** A reading from the step counter: what was walked since the last is added, while recording. */
    @Synchronized
    fun onStepCount(value: Long) {
        val t = tally ?: return
        val id = stepTrack ?: return
        if (!stepsOpen) return
        val first = t.base == null
        val before = t.total
        t.reading(value)
        // The first reading's saved at once, as the base the rest count from.
        val now = clock()
        if (first || now - stepsSavedAt >= SAVE_STEPS_EVERY_MS) {
            store.setSteps(id, t.total, t.base)
            stepsSavedAt = now
        }
        val s = state
        if (t.total != before && s != null && s.trackId == id) state = s.copy(steps = t.total)
    }

    /**
     * Counts steps again (started or resumed) for a counted track: from the
     * next reading, unless [rebase] is false (carried on after being
     * killed), when the saved base is kept. False if the counter can't be
     * had (none, or the permission refused or taken back): what was counted
     * stays.
     */
    private fun openSteps(rebase: Boolean = true): Boolean {
        val t = tally ?: return false
        val id = stepTrack ?: return false
        stepGen++
        if (rebase) t.base = null
        if (!listening) listening = steps?.start { v -> onStepCount(v) } == true
        stepsOpen = listening
        if (!listening) return false
        stepsSavedAt = clock()
        store.setSteps(id, t.total, t.base)
        return true
    }

    /** Stops counting (paused or stopped), once the counter's flushed: what it held back was walked before. */
    private fun closeSteps() {
        val t = tally ?: return
        val id = stepTrack ?: return
        if (!stepsOpen) return
        val gen = ++stepGen
        store.setSteps(id, t.total, t.base)
        val done = CompletableDeferred<Unit>()
        flushed = done
        val source = steps ?: return run { stepsOpen = false; done.complete(Unit) }
        source.flush {
            synchronized(this) {
                if (gen == stepGen) {
                    stepsOpen = false
                    stopListening()
                    t.base = null
                    store.setSteps(id, t.total, null)
                }
            }
            done.complete(Unit)
        }
    }

    private fun stopListening() {
        if (listening) steps?.stop()
        listening = false
        stepsOpen = false
    }

    /** A fix from GPS: kept (and saved) if it's good enough and it's not paused. */
    @Synchronized
    fun onFix(f: Fix) {
        val s = state ?: return
        if (s.paused) return
        if (!log.accept(f)) return
        store.add(s.trackId, log.segment, f)
        val gps = gpsGain()
        state = s.copy(segments = lines(), metres = metres(), gain = gps ?: s.gain)
        if (gps == null && clock() - measuredAt >= MEASURE_EVERY_MS) measure()
    }

    private fun lines() = log.segments.map { seg -> seg.map { it.lngLat } }

    /** The climb from GPS altitudes, if any fix had one: the finished track's, rounded as it will be. */
    private fun gpsGain() = log.stats().gain?.toDouble()

    /** The length, rounded to the metre as the finished track's will be. */
    private fun metres() = TrackMeasure.jsRound(log.metres).toDouble()

    /** The climb so far, from the DEM, off the main thread: only while GPS has given no altitudes. */
    private fun measure() {
        val p = profiler ?: return
        val s = state ?: return
        if (log.stats().gain != null) return
        val line = s.segments.flatten()
        if (line.size < 2 || measuring) return
        measuring = true
        measuredAt = clock()
        background.execute {
            val gain = try { p.profile(line).gain } catch (e: Exception) { null }
            synchronized(this) {
                measuring = false
                val now = state
                // GPS altitudes since arrived win: they're what the track will say.
                if (gain != null && now != null && now.trackId == s.trackId && gpsGain() == null) state = now.copy(gain = gain)
            }
        }
    }

    companion object {
        /** How often the climb is measured again while recording. */
        const val MEASURE_EVERY_MS = 30_000L
        /** How often the step count is saved while counting: a kill loses none, as it's counted on from the saved reading. */
        const val SAVE_STEPS_EVERY_MS = 10_000L
    }
}
