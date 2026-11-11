package com.hikeratlas.android.track

import com.hikeratlas.android.elevation.ElevationProfiler
import com.hikeratlas.android.routes.Regions

/** Slower than this between two kept fixes is standing, not moving: m/s. */
const val MOVING_SPEED = 0.3

/**
 * A stopped track's numbers: its length; its time recording (pauses not
 * counted) and, of that, moving; and its climb and descent from [profile]
 * (the DEM), if there is one.
 */
fun statsOf(track: TrackStore.Track, points: List<Pair<Int, Fix>>, profile: ElevationProfiler.Profile?): TrackStore.Stats {
    val log = TrackLog()
    for ((seg, f) in points) log.restore(f, seg)
    var moving = 0L
    for (seg in log.segments) {
        for (i in 1 until seg.size) {
            val dt = seg[i].t - seg[i - 1].t
            if (dt <= 0) continue
            val d = ElevationProfiler.haversine(seg[i - 1].lngLat, seg[i].lngLat)
            if (d / (dt / 1000.0) >= MOVING_SPEED) moving += dt
        }
    }
    val end = track.ended ?: points.lastOrNull()?.second?.t ?: track.started
    val elapsed = (end - track.started - track.pausedMs).coerceAtLeast(0)
    return TrackStore.Stats(log.metres, elapsed, moving.coerceAtMost(elapsed), profile?.gain, profile?.loss)
}

/** The track's line, a list of [lng, lat] per stretch between pauses. */
fun segmentsOf(points: List<Pair<Int, Fix>>): List<List<List<Double>>> =
    points.groupBy({ it.first }, { it.second.lngLat }).toSortedMap().values.toList()

/**
 * The park a track starts in, from the build's regions: the smallest whose
 * box holds the start. Null outside them all.
 */
fun parkAt(regions: Regions, lng: Double, lat: Double): String? =
    regions.countries.flatMap { it.states }.flatMap { it.areas }
        .filter { a -> a.bbox?.let { b -> lng >= b[0] && lat >= b[1] && lng <= b[2] && lat <= b[3] } == true }
        .minByOrNull { a -> a.bbox!!.let { (it[2] - it[0]) * (it[3] - it[1]) } }
        ?.name
