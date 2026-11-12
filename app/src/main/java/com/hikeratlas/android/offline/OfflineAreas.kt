package com.hikeratlas.android.offline

import android.content.Context
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hikeratlas.android.pmtiles.Pmtiles
import com.hikeratlas.android.routes.ParkDoc
import com.hikeratlas.android.routes.RoutesRepository
import com.hikeratlas.android.routes.lookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An area can't be planned (not a network error): trying again won't help. */
class CantPlan(message: String) : Exception(message)

/** Whatever holds the app's [OfflineAreas] (the app; a test's own). */
interface OfflineHost {
    val offlineAreas: OfflineAreas
}

/**
 * Offline areas: what's downloaded, downloading or waiting ([areas], for the
 * Saved tab), and starting, deleting and planning them. Downloads run as
 * [AreaWorker]s, on Wi-Fi only unless [wifiOnly] is off.
 */
class OfflineAreas(
    private val context: Context,
    val store: OfflineStore,
    private val routes: RoutesRepository,
    private val basemap: () -> Pmtiles,
    private val routesArchive: () -> Pmtiles,
    /** A terrain tile from Mapterhorn itself: null where it has none. */
    private val dem: (Int, Int, Int) -> ByteArray?,
    /** The archives' current builds (their ETags, from HEAD requests). */
    private val currentBuild: suspend () -> Build,
) {
    /** Which build of each archive an area's tiles came from. */
    data class Build(val basemap: String, val routes: String) {
        val key get() = "$basemap|$routes"

        companion object {
            fun parse(key: String?): Build? = key?.split('|')?.takeIf { it.size == 2 }?.let { Build(it[0], it[1]) }
        }
    }

    /** The archives' builds when last checked: null until then, or if it couldn't be. */
    var latest by mutableStateOf<Build?>(null)
        private set

    /** Whether [a] was downloaded from an older build than [latest] (the overview: of the basemap). */
    fun updateAvailable(a: OfflineStore.Area): Boolean {
        val now = latest ?: return false
        val was = Build.parse(a.build) ?: return false
        if (!a.ready) return false
        return was.basemap != now.basemap || (a.kind != OfflineStore.OVERVIEW && was.routes != now.routes)
    }

    /**
     * Checks the archives' builds, so areas from an older one offer Update.
     * Areas from before builds were kept are taken to be current: they were
     * downloaded recently.
     */
    suspend fun checkUpdates() {
        val now = try { currentBuild() } catch (e: IOException) { return }
        withContext(Dispatchers.IO) {
            for (a in store.areas()) if (a.ready && a.build == null) store.setAreaBuild(a.id, now.key)
        }
        latest = now
        refresh()
    }

    /** The builds now, for recording with a download (null if they can't be got). */
    suspend fun buildNow(): Build? = try { currentBuild() } catch (e: IOException) { null }

    /** Downloads [id]'s map and routes again, from the current build. */
    suspend fun update(id: Long) {
        withContext(Dispatchers.IO) {
            store.setAreaState(id, OfflineStore.QUEUED)
            enqueue(id, ExistingWorkPolicy.REPLACE, refresh = true)
        }
        refresh()
    }

    /** Deletes every offline area (the fonts, icons and routes index stay: they're small). */
    suspend fun deleteAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        withContext(Dispatchers.IO) {
            for (a in store.areas()) store.deleteArea(a.id)
            vacuum()
        }
        refresh()
    }

    private fun vacuum() {
        // Gives the disk back; skipped if a download's writing.
        try { store.vacuum() } catch (e: android.database.sqlite.SQLiteException) { }
    }

    /** Bytes kept for offline use: areas' tiles and files, and the small files kept with them. */
    suspend fun bytes(): Long = withContext(Dispatchers.IO) { store.bytes() }

    /** What can be downloaded: a park (by its id) or a hike (by its route id). */
    data class Target(val kind: String, val ref: String, val name: String)

    var areas by mutableStateOf<List<OfflineStore.Area>>(emptyList())
        private set

    private val prefs get() = context.getSharedPreferences("offline", Context.MODE_PRIVATE)

    /** Downloads wait for Wi-Fi (or another unmetered network). On by default. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(v) {
            prefs.edit { putBoolean("wifi_only", v) }
            // Waiting downloads take the new rule.
            for (a in store.areas()) if (!a.ready) enqueue(a.id, ExistingWorkPolicy.REPLACE)
        }

    suspend fun refresh() {
        areas = withContext(Dispatchers.IO) { store.areas() }
    }

    fun downloader() = AreaDownloader(store, basemap(), routesArchive(), dem)

    /** What [t] needs: from the park's routes (fetched, or kept), and for a park its box. */
    suspend fun plan(t: Target): AreaPlan = when (t.kind) {
        OfflineStore.OVERVIEW -> {
            val h = withContext(Dispatchers.IO) { basemap().header() }
            AreaPlan.overview(listOf(h.minLon, h.minLat, h.maxLon, h.maxLat))
        }
        OfflineStore.PARK -> {
            // By any of the area's park ids: Pacific Rim NPR's area is three
            // OSM parks, and the West Coast Trail's is one of the others.
            val box = routes.regions().countries.flatMap { it.states }.flatMap { it.areas }
                .firstOrNull { it.covers(t.ref) }?.bbox ?: throw CantPlan("The map has no outline for ${t.name}.")
            AreaPlan.park(box, routes.park(t.ref)?.let(::routeLines).orEmpty())
        }
        OfflineStore.HIKE -> {
            val doc = routes.park(t.ref.substringBefore('/')) ?: throw CantPlan("${t.name}'s park has no routes any more.")
            val line = lookup(doc, t.ref)?.route?.line ?: throw CantPlan("${t.name} has changed since; try it from its sheet again.")
            AreaPlan.hike(line)
        }
        else -> throw IllegalArgumentException(t.kind)
    }

    /** The bytes [t] would take beyond what's kept, and the overview's if it'd come too. */
    suspend fun estimate(t: Target): Pair<Long, Long> {
        val d = downloader()
        val bytes = d.estimate(plan(t))
        val overview = if (withContext(Dispatchers.IO) { store.findArea(OfflineStore.OVERVIEW, "") } == null) {
            d.estimate(plan(OVERVIEW_TARGET))
        } else {
            0L
        }
        return bytes to overview
    }

    /** Starts downloading [t] (and the overview, the first time). */
    suspend fun download(t: Target) {
        withContext(Dispatchers.IO) {
            if (store.findArea(OfflineStore.OVERVIEW, "") == null) {
                enqueue(store.area(OVERVIEW_TARGET.kind, OVERVIEW_TARGET.ref, OVERVIEW_TARGET.name).id)
            }
            enqueue(store.area(t.kind, t.ref, t.name).id)
        }
        refresh()
    }

    /** Tries a failed download again. */
    suspend fun retry(id: Long) {
        withContext(Dispatchers.IO) {
            store.setAreaState(id, OfflineStore.QUEUED)
            enqueue(id, ExistingWorkPolicy.REPLACE)
        }
        refresh()
    }

    suspend fun delete(id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        withContext(Dispatchers.IO) {
            store.deleteArea(id)
            vacuum()
        }
        refresh()
    }

    /** The area for [t], if it's downloaded or on its way. */
    fun areaFor(t: Target) = areas.firstOrNull { it.kind == t.kind && it.ref == t.ref }

    private fun enqueue(id: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP, refresh: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<AreaWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setInputData(workDataOf(AreaWorker.AREA to id, AreaWorker.REFRESH to refresh))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), policy, request)
    }

    /** Keeps the park's routes JSON with the area, so its sheets open offline. */
    suspend fun keepParkData(area: OfflineStore.Area) {
        val parkId = when (area.kind) {
            OfflineStore.PARK -> area.ref
            OfflineStore.HIKE -> area.ref.substringBefore('/')
            else -> return
        }
        val gz = routes.parkBytes(parkId) ?: return
        withContext(Dispatchers.IO) { store.putAreaResource(area.id, routes.parkKey(parkId), gz) }
    }

    companion object {
        const val TAG = "offline-area"
        fun workName(id: Long) = "offline-area-$id"

        val OVERVIEW_TARGET = Target(OfflineStore.OVERVIEW, "", "North America overview")

        /** Every curated route's line in a park: what its fine terrain follows. */
        fun routeLines(doc: ParkDoc): List<List<List<Double>>> = buildList {
            doc.day_hikes.forEach { add(it.route.line) }
            doc.base_camps.forEach { b -> add(b.approach.line); b.hikes.forEach { add(it.route.line) } }
            doc.point_to_points.forEach { add(it.route.line) }
            doc.stops.forEach { add(it.route.line) }
        }.filter { it.isNotEmpty() }
    }
}
