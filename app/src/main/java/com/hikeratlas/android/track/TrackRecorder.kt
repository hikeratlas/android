package com.hikeratlas.android.track

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hikeratlas.android.elevation.ElevationProfiler
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The track being recorded, if any: what the recording bar and the map show,
 * and what [RecordingService] feeds fixes into. Kept fixes go straight to
 * [store].
 */
class TrackRecorder(
    private val store: TrackStore,
    /** Measures the climb from the DEM, as the hike charts do; null for none. */
    private val profiler: ElevationProfiler?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val background: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "track-climb").apply { isDaemon = true } },
) {
    data class State(
        val trackId: Long,
        val started: Long,
        val pausedMs: Long,
        /** When it was paused, if it is. */
        val pausedAt: Long?,
        /** The kept fixes as [lng, lat], a segment per stretch between pauses. */
        val segments: List<List<List<Double>>>,
        val metres: Double,
        /** Climb from the DEM; null until first measured, or where there's no DEM. */
        val gain: Double?,
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

    /** Starts a new track (if none is being recorded). */
    @Synchronized
    fun start(): State {
        state?.let { return it }
        val now = clock()
        log = TrackLog()
        measuredAt = 0L
        return State(store.start(now), now, 0, null, emptyList(), 0.0, null).also { state = it }
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
        state = State(t.id, t.started, t.pausedMs, pausedAt, lines(), log.metres, null)
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
    }

    @Synchronized
    fun resume() {
        val s = state ?: return
        val at = s.pausedAt ?: return
        val pausedMs = s.pausedMs + (clock() - at).coerceAtLeast(0)
        store.setPaused(s.trackId, pausedMs, null)
        state = s.copy(pausedMs = pausedMs, pausedAt = null)
    }

    /** Ends the track: kept, as recorded. Its id, if there was one. */
    @Synchronized
    fun stop(): Long? {
        val s = state ?: return null
        val now = clock()
        if (s.pausedAt != null) store.setPaused(s.trackId, s.pausedMs + (now - s.pausedAt).coerceAtLeast(0), null)
        store.end(s.trackId, now)
        state = null
        return s.trackId
    }

    /** A fix from GPS: kept (and saved) if it's good enough and it's not paused. */
    @Synchronized
    fun onFix(f: Fix) {
        val s = state ?: return
        if (s.paused) return
        if (!log.accept(f)) return
        store.add(s.trackId, log.segment, f)
        state = s.copy(segments = lines(), metres = log.metres)
        if (clock() - measuredAt >= MEASURE_EVERY_MS) measure()
    }

    private fun lines() = log.segments.map { seg -> seg.map { it.lngLat } }

    /** The climb so far, from the DEM, off the main thread. */
    private fun measure() {
        val p = profiler ?: return
        val s = state ?: return
        val line = s.segments.flatten()
        if (line.size < 2 || measuring) return
        measuring = true
        measuredAt = clock()
        background.execute {
            val gain = try { p.profile(line).gain } catch (e: Exception) { null }
            synchronized(this) {
                measuring = false
                val now = state
                if (gain != null && now != null && now.trackId == s.trackId) state = now.copy(gain = gain)
            }
        }
    }

    companion object {
        /** How often the climb is measured again while recording. */
        const val MEASURE_EVERY_MS = 30_000L
    }
}
