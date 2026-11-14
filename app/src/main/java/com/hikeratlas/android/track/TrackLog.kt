package com.hikeratlas.android.track

import com.hikeratlas.android.elevation.ElevationProfiler

/**
 * One GPS fix as recorded: where, when (ms since the epoch) and how well,
 * with GPS's own altitudes. A track's climb is from these (as the website
 * measures its GPX); the DEM's only where GPS gave none.
 */
data class Fix(
    val t: Long,
    val lat: Double,
    val lon: Double,
    /** Horizontal accuracy, metres (68%). */
    val accuracy: Float?,
    /** Above the WGS84 ellipsoid, as GPS reports it. */
    val altitude: Double? = null,
    /** Above mean sea level, where Android converts it (Android 14+). */
    val mslAltitude: Double? = null,
    val verticalAccuracy: Float? = null,
    /** m/s */
    val speed: Float? = null,
) {
    val lngLat get() = listOf(lon, lat)
}

/**
 * A track's kept fixes, in segments split where it was paused, and its
 * numbers so far, measured as the website measures the finished track's
 * GPX ([TrackMeasure]), so what the recording bar shows is what the track
 * ends with. Drops fixes too vague to place ([MAX_ACCURACY]) and ones too
 * close to the last kept to be movement rather than GPS noise ([MIN_STEP]).
 */
class TrackLog {
    private val segs = mutableListOf<MutableList<Fix>>()
    val segments: List<List<Fix>> get() = segs
    private val measure = TrackMeasure.Running()

    /** Along the kept fixes, not across the gaps between segments. */
    val metres get() = measure.distance

    /** The numbers so far, as the finished track's GPX will have them. */
    fun stats(): GpxStats = measure.stats()

    /** The next fix starts a new segment (after a pause). */
    fun breakSegment() {
        if (segs.lastOrNull()?.isNotEmpty() == true) segs += mutableListOf<Fix>()
    }

    /** Adds a fix already kept (a track read back), into segment [seg]. */
    fun restore(f: Fix, seg: Int) {
        while (segs.size <= seg) segs += mutableListOf<Fix>()
        segs[seg] += f
        measure.add(TrackMeasure.point(f), seg)
    }

    /** Keeps [f] if it's good enough and far enough: true if kept. */
    fun accept(f: Fix): Boolean {
        val acc = f.accuracy ?: return false
        if (acc > MAX_ACCURACY) return false
        if (segs.isEmpty()) segs += mutableListOf<Fix>()
        val s = segs.last()
        val last = s.lastOrNull()
        if (last != null) {
            val d = ElevationProfiler.haversine(last.lngLat, f.lngLat)
            if (d < MIN_STEP) return false
        }
        s += f
        measure.add(TrackMeasure.point(f), segs.size - 1)
        return true
    }

    /** The segment the next kept fix goes into. */
    val segment get() = (segs.size - 1).coerceAtLeast(0)

    companion object {
        const val MAX_ACCURACY = 30f
        const val MIN_STEP = 5.0
    }
}
