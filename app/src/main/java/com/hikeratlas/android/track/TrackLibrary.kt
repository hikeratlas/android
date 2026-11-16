package com.hikeratlas.android.track

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hikeratlas.android.elevation.ElevationProfiler
import com.hikeratlas.android.routes.Regions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saved (stopped) tracks: the list the Saved tab shows, and each one's line,
 * numbers and elevation profile for its summary and its view on the map.
 */
class TrackLibrary(
    private val store: TrackStore,
    private val profiler: ElevationProfiler?,
    /** The build's parks, for naming a track after the one it starts in. */
    private val regions: suspend () -> Regions?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    var tracks by mutableStateOf<List<TrackStore.Track>>(emptyList())
        private set

    /** A track as its summary and map view show it. */
    data class Detail(
        val track: TrackStore.Track,
        val segments: List<List<List<Double>>>,
        val stats: TrackStore.Stats,
        /** From the DEM; null where there's none (offline, say). */
        val profile: ElevationProfiler.Profile?,
    ) {
        val name get() = track.name ?: "Track"
    }

    suspend fun refresh() {
        tracks = withContext(io) { store.saved() }
    }

    /**
     * A track's line, numbers and profile. Numbers it doesn't have yet (just
     * stopped) are worked out and kept, and a track with no name gets one:
     * the park it starts in and the date, "Raging River State Forest · 11 Nov".
     */
    suspend fun detail(id: Long): Detail? = withContext(io) {
        val t = store.track(id) ?: return@withContext null
        val points = store.points(id)
        val segments = segmentsOf(points)
        val line = segments.flatten()
        val profile = if (profiler != null && line.size >= 2) {
            try { profiler.profile(line) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        } else {
            null
        }
        var stats = t.stats
        // Kept once worked out; worked out again while the climb's still missing.
        if (stats == null || (stats.gain == null && profile != null)) {
            stats = statsOf(t, points, profile)
            store.setStats(id, stats)
        }
        var track = t.copy(stats = stats)
        if (t.name == null) {
            val name = defaultName(t, line.firstOrNull())
            store.rename(id, name)
            track = track.copy(name = name)
        }
        Detail(track, segments, stats, profile)
    }

    private suspend fun defaultName(t: TrackStore.Track, start: List<Double>?): String {
        val date = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t.started))
        val park = start?.let { s ->
            try { regions()?.let { parkAt(it, s[0], s[1]) } } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        }
        return listOfNotNull(park, date).joinToString(" · ")
    }

    /**
     * Names and numbers for saved tracks without them (recorded before the
     * app kept them, or stopped as it was killed), then the list again.
     */
    suspend fun fillMissing() {
        val missing = tracks.filter { it.stats == null || it.name == null }
        if (missing.isEmpty()) return
        for (t in missing) detail(t.id)
        refresh()
    }

    /** Writes a track as GPX into [cacheDir]'s gpx/ (what FileProvider shares), named for the track. */
    suspend fun gpx(id: Long, cacheDir: File): File? = withContext(io) {
        val t = store.track(id) ?: return@withContext null
        val name = t.name ?: "Track"
        val dir = File(cacheDir, "gpx").apply { mkdirs() }
        // Only the one being shared is kept.
        dir.listFiles()?.forEach { it.delete() }
        File(dir, Gpx.fileName(name)).apply { writeText(Gpx.write(name, t.started, store.points(id), t.steps)) }
    }

    /** A track as GPX, as it's shared and posted. */
    suspend fun gpxText(id: Long): String? = withContext(io) {
        val t = store.track(id) ?: return@withContext null
        Gpx.write(t.name ?: "Track", t.started, store.points(id), t.steps)
    }

    /** What a track was posted as, if it was (read afresh, not from the list). */
    suspend fun posted(id: Long): TrackStore.Posted? = withContext(io) { store.track(id)?.posted }

    /** Marks a track as posted (or not, with null: its post was deleted), and the list again. */
    suspend fun setPosted(id: Long, posted: TrackStore.Posted?) {
        withContext(io) { store.setPosted(id, posted) }
        refresh()
    }

    /**
     * A post deleted (from the profile): a track posted as [trackUri] is no
     * longer posted, so it can be posted again; one whose review was
     * [reviewUri] keeps its track (still up) but not the review.
     */
    suspend fun postDeleted(trackUri: String?, reviewUri: String?) {
        withContext(io) {
            for (t in store.saved()) {
                val p = t.posted ?: continue
                when {
                    trackUri != null && p.trackUri == trackUri -> store.setPosted(t.id, null)
                    reviewUri != null && p.reviewUri == reviewUri -> store.setPosted(t.id, p.copy(reviewUri = null))
                }
            }
        }
        refresh()
    }

    /**
     * The saved tracks passing within [within] metres of [line] (a hike's):
     * boxes first, from one query, so only the tracks around it are read.
     */
    suspend fun tracksNear(line: List<List<Double>>, within: Double = NEAR_METRES): Set<Long> = withContext(io) {
        if (line.isEmpty()) return@withContext emptySet()
        val box = grow(boxOf(line), within)
        val saved = store.saved().map { it.id }.toSet()
        store.boxes().filter { (id, b) -> id in saved && overlaps(b, box) }.keys
            .filter { id -> closestApproach(segmentsOf(store.points(id)), line, within) != null }
            .toSet()
    }

    /** A track's line: a list of [lng, lat] per stretch between pauses. */
    suspend fun lines(id: Long): List<List<List<Double>>> = withContext(io) { segmentsOf(store.points(id)) }

    suspend fun rename(id: Long, name: String) {
        withContext(io) { store.rename(id, name.trim().ifEmpty { "Track" }) }
        refresh()
    }

    suspend fun delete(id: Long) {
        withContext(io) { store.delete(id) }
        refresh()
    }

    /** Makes a stopped track unfinished again, to record on; its stopped time isn't counted. */
    suspend fun reopen(id: Long) {
        withContext(io) {
            val t = store.track(id) ?: return@withContext
            store.reopen(id, t.ended ?: System.currentTimeMillis())
        }
        refresh()
    }
}
