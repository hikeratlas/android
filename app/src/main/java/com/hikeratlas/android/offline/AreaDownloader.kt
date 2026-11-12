package com.hikeratlas.android.offline

import com.hikeratlas.android.elevation.MapterhornSource
import com.hikeratlas.android.pmtiles.Compression
import com.hikeratlas.android.pmtiles.Pmtiles
import com.hikeratlas.android.pmtiles.StoredTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Downloads an [AreaPlan]'s tiles into the [store], for an area: from the
 * basemap and routes archives (raw, in merged range reads), and from
 * Mapterhorn (a request a tile, a few at once). Tiles already kept, for
 * this area or another, aren't fetched again, so a download that stopped
 * picks up where it was.
 */
class AreaDownloader(
    private val store: OfflineStore,
    private val basemap: Pmtiles,
    private val routes: Pmtiles,
    /** A terrain tile from Mapterhorn itself: null where it has none (404). */
    private val dem: (z: Int, x: Int, y: Int) -> ByteArray?,
    private val demAtOnce: Int = 8,
) {
    private class Job(val source: String, val z: Int, val coords: List<Pair<Int, Int>>, val archive: Pmtiles?)

    private fun jobs(plan: AreaPlan): List<Job> = buildList {
        for ((z, c) in plan.vector.toSortedMap()) {
            add(Job(basemap.key, z, c.toList(), basemap))
            if (plan.withRoutes) add(Job(routes.key, z, c.toList(), routes))
        }
        for ((z, c) in plan.dem.toSortedMap()) add(Job(MapterhornSource.KEY, z, c.toList(), null))
    }

    /** About how many bytes downloading [plan] would take, beyond what's kept. */
    suspend fun estimate(plan: AreaPlan): Long = withContext(Dispatchers.IO) {
        var bytes = 0L
        for (j in jobs(plan)) {
            for (chunk in j.coords.chunked(CHUNK)) {
                val missing = chunk - store.tiles(j.source, j.z, chunk).keys
                if (missing.isEmpty()) continue
                // The archives' directories say exactly; terrain is a request a tile, so it's guessed.
                bytes += j.archive?.sizes(j.z, missing)?.values?.sumOf { it.toLong() } ?: (missing.size * DEM_TILE_BYTES)
            }
        }
        bytes
    }

    /**
     * Downloads [plan]'s tiles for [area], reporting tiles done of all. With
     * [refetchVector] (an update to a new build), the basemap and routes
     * tiles are fetched even if kept, replacing them; terrain doesn't change
     * with our builds, so kept terrain still isn't.
     */
    suspend fun download(
        area: Long,
        plan: AreaPlan,
        refetchVector: Boolean = false,
        progress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ) =
        withContext(Dispatchers.IO) {
            val jobs = jobs(plan)
            val total = jobs.sumOf { it.coords.size }
            var done = 0
            progress(0, total)
            for (j in jobs) {
                for (chunk in j.coords.chunked(CHUNK)) {
                    val kept = if (refetchVector && j.archive != null) emptySet() else store.tiles(j.source, j.z, chunk).keys
                    if (kept.isNotEmpty()) store.useTiles(area, j.source, j.z, kept)
                    val missing = chunk.filter { it !in kept }
                    if (missing.isNotEmpty()) {
                        val got = if (j.archive != null) j.archive.rawTiles(j.z, missing) else demTiles(j.z, missing)
                        store.putAreaTiles(area, j.source, j.z, got)
                    }
                    done += chunk.size
                    progress(done, total)
                }
            }
        }

    private suspend fun demTiles(z: Int, coords: List<Pair<Int, Int>>): Map<Pair<Int, Int>, StoredTile> = coroutineScope {
        val gate = Semaphore(demAtOnce)
        coords.map { c ->
            async { gate.withPermit { dem(z, c.first, c.second)?.let { c to StoredTile(Compression.NONE, it) } } }
        }.awaitAll().filterNotNull().toMap()
    }

    companion object {
        /** Tiles looked up and written at a time. */
        private const val CHUNK = 400

        /** Mapterhorn's tiles average about this along routes (sampled: 100-130 KB in BC, Washington and Alberta). */
        const val DEM_TILE_BYTES = 115_000L
    }
}
