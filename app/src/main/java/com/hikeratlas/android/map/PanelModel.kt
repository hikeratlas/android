package com.hikeratlas.android.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hikeratlas.android.elevation.ElevationProfiler
import com.hikeratlas.android.pmtiles.Pmtiles
import com.hikeratlas.android.routes.BundleIndex
import com.hikeratlas.android.routes.km
import com.hikeratlas.android.trail.TrailGeometry
import com.hikeratlas.android.routes.ParkDoc
import com.hikeratlas.android.routes.RouteItem
import com.hikeratlas.android.routes.RoutesRepository
import com.hikeratlas.android.routes.Selected
import com.hikeratlas.android.routes.hikeUrl
import com.hikeratlas.android.routes.itemsFromPark
import com.hikeratlas.android.routes.lookup
import com.hikeratlas.android.routes.parkUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** A park's operator and official website. */
data class ParkAbout(val operator: String?, val website: String?) {
    val isEmpty get() = operator == null && website == null

    companion object {
        fun of(operator: Any?, website: Any?): ParkAbout? {
            val op = (operator as? String)?.takeIf { it.isNotEmpty() }
            val web = (website as? String)?.takeIf { Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            return ParkAbout(op, web).takeUnless { it.isEmpty }
        }
    }
}

/** What the bottom sheet shows. */
sealed interface Panel {
    data object None : Panel
    data class Loading(val heading: String) : Panel
    data class Failed(val heading: String, val message: String, val retry: () -> Unit) : Panel

    /** Routes under a tap, routes in view, or a park's routes. */
    data class RouteList(
        val heading: String,
        val items: List<RouteItem>,
        val about: ParkAbout? = null,
        /** Set when this is a park's list. */
        val parkId: String? = null,
        val shareUrl: String? = null,
    ) : Panel

    /** An OSM trail picked in search: its length, elevation, and the park's hikes that use it. */
    data class Trail(
        val name: String,
        /** "Trail · 12.4 km · Jasper NP, Alberta" */
        val subtitle: String,
        val parkId: String?,
        val parkName: String?,
        val loading: Boolean = true,
        /** Too long to read from the tiles here. */
        val tooLong: Boolean = false,
        val profile: ElevationProfiler.Profile? = null,
        /** Metres, when [profile] is only the trail's longest stretch. */
        val along: Double? = null,
        /** Low and high points, over all of it. */
        val range: Pair<Double, Double>? = null,
        val hikes: List<RouteItem>? = null,
    ) : Panel

    data class Detail(
        val selected: Selected,
        val doc: ParkDoc,
        /** The list it was opened from, to go back to. */
        val back: RouteList?,
        val shareUrl: String?,
        val profile: ProfileState = ProfileState.Loading,
    ) : Panel
}

sealed interface ProfileState {
    data object Loading : ProfileState
    data class Ready(val profile: ElevationProfiler.Profile) : ProfileState
    data class Failed(val message: String) : ProfileState
}

/**
 * The sheet's state and the loads behind it. [onRoute] draws (or clears) the
 * selected route on the map; [fit] says whether to move the map to it.
 */
class PanelModel(
    private val scope: CoroutineScope,
    private val routes: RoutesRepository,
    private val profiler: ElevationProfiler,
    /** The basemap archive, for trails' ways; null where there's none (tests). */
    private val basemap: () -> Pmtiles? = { null },
    /** Draws a trail's lines on the map (or clears them). */
    private val onTrail: (List<List<List<Double>>>?) -> Unit = {},
    private val onRoute: (selected: Selected?, fit: Boolean) -> Unit,
) {
    var panel by mutableStateOf<Panel>(Panel.None)
        private set

    /** The section a park's list shows (its chips), shared by the sheet's header and body. */
    var section by mutableStateOf<String?>(null)

    /** A route the map should fit into the space the sheet leaves, once it can. */
    var fitPending by mutableStateOf(false)

    private var job: Job? = null
    private var index: BundleIndex? = null

    private suspend fun idx() = index ?: routes.index().also { index = it }

    fun close() {
        job?.cancel()
        fitPending = false
        onTrail(null)
        panel = Panel.None
        onRoute(null, false)
    }

    fun back() {
        val p = panel
        if (p is Panel.Detail && p.back != null) {
            showList(p.back)
        } else {
            close()
        }
    }

    fun showList(list: Panel.RouteList) {
        job?.cancel()
        onRoute(null, false)
        onTrail(null)
        fitPending = false
        if ((panel as? Panel.RouteList)?.heading != list.heading) section = list.items.firstOrNull()?.section
        panel = list
    }

    fun openPark(parkId: String, name: String, about: ParkAbout? = null) {
        load(name) {
            val doc = routes.park(parkId)
            if (doc == null) {
                Panel.RouteList(name, emptyList(), about)
            } else {
                val parkAbout = ParkAbout.of(doc.park?.operator, doc.park?.website) ?: about
                val items = itemsFromPark(doc)
                section = items.firstOrNull()?.section
                Panel.RouteList(doc.park?.name ?: name, items, parkAbout, parkId, parkUrl(idx(), parkId))
            }
        }
    }

    /** Opens a route; [fit] moves the map to it. */
    fun openRoute(parkId: String, id: String, fit: Boolean = true) {
        val from = panel as? Panel.RouteList ?: (panel as? Panel.Detail)?.back
        load("Loading…", after = { p ->
            if (p is Panel.Detail) {
                onRoute(p.selected, fit)
                fitPending = fit
                profile(p)
            }
        }) {
            val doc = routes.park(parkId) ?: error("This hike's park has no routes any more.")
            val sel = lookup(doc, id) ?: error("That hike has changed since the map was drawn.")
            Panel.Detail(sel, doc, from, hikeUrl(idx(), doc, id))
        }
    }

    /**
     * Opens an OSM trail picked in search (its name, length and box, and its
     * park as search names it), as the website's trail panel does.
     */
    fun openTrail(name: String, lengthKm: Double?, park: String?, state: String?, box: DoubleArray?) {
        job?.cancel()
        onRoute(null, false)
        onTrail(null)
        fitPending = false
        val subtitle = listOfNotNull("Trail", lengthKm?.takeIf { it > 0 }?.let { "${km(it)} km" }, listOfNotNull(park, state).joinToString(", ").ifEmpty { null })
            .joinToString(" · ")
        panel = Panel.Trail(name, subtitle, null, park)
        fun update(f: (Panel.Trail) -> Panel.Trail) {
            val now = panel as? Panel.Trail ?: return
            if (now.name == name) panel = f(now)
        }
        job = scope.launch {
            // The park's hikes that walk it: those with it among their "via".
            val parkId = park?.let { runCatching { routes.parkIdFor(state, it) }.getOrNull() }
            update { it.copy(parkId = parkId) }
            val hikes = async {
                val doc = parkId?.let { runCatching { routes.park(it) }.getOrNull() } ?: return@async emptyList()
                val want = name.trim().lowercase()
                itemsFromPark(doc).filter { item ->
                    item.park == parkId && (lookup(doc, item.id)?.route?.via ?: emptyList()).any { it.trim().lowercase() == want }
                }
            }
            val archive = basemap()
            val tiles = box?.let { TrailGeometry.tilesFor(it) }
            val geom = if (archive == null || tiles == null) {
                null
            } else {
                runCatching {
                    val gate = Semaphore(16)
                    val fetched = coroutineScope {
                        tiles.map { (x, y) ->
                            async(Dispatchers.IO) { gate.withPermit { Triple(x, y, archive.tile(TrailGeometry.Z, x, y)) } }
                        }.awaitAll()
                    }
                    withContext(Dispatchers.Default) { TrailGeometry.build(name, fetched) }
                }.getOrNull()
            }
            if (geom != null && geom.lines.isNotEmpty()) onTrail(geom.lines)
            update { it.copy(tooLong = box != null && tiles == null) }

            val elevation = try {
                withContext(Dispatchers.IO) { geom?.let(::trailElevation) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null // no elevation: the panel just leaves it out
            }
            val found = hikes.await()
            update {
                it.copy(loading = false, profile = elevation?.profile, range = elevation?.range, along = elevation?.along, hikes = found)
            }
        }
    }

    private class TrailElevation(val profile: ElevationProfiler.Profile?, val range: Pair<Double, Double>?, val along: Double?)

    /**
     * A trail's elevation: end to end if it's one line, else along its
     * longest stretch; and its low and high points over all of it. A trail in
     * pieces just has those.
     */
    private fun trailElevation(geom: TrailGeometry): TrailElevation? {
        val main = geom.single ?: geom.longest
        // Up to 50 ways, every 20 m, for the low and high points.
        fun ranges() = geom.lines.take(50).map { profiler.profile(it, step = 20.0) }.let { ps -> ps.minOf { it.low } to ps.maxOf { it.high } }
        return when {
            main != null && geom.single != null -> profiler.profile(main).let { TrailElevation(it, it.low to it.high, null) }
            main != null -> {
                val p = profiler.profile(main)
                val r = ranges()
                TrailElevation(p, minOf(p.low, r.first) to maxOf(p.high, r.second), p.length)
            }
            geom.lines.isNotEmpty() -> TrailElevation(null, ranges(), null)
            else -> null
        }
    }

    private fun profile(d: Panel.Detail) {
        job = scope.launch {
            val state = try {
                ProfileState.Ready(withContext(Dispatchers.IO) { profiler.profile(d.selected.route.line) })
            } catch (e: Exception) {
                ProfileState.Failed(e.message ?: "couldn't measure elevation")
            }
            val now = panel
            if (now is Panel.Detail && now.selected.id == d.selected.id) panel = now.copy(profile = state)
        }
    }

    private fun load(heading: String, after: (Panel) -> Unit = {}, block: suspend () -> Panel) {
        job?.cancel()
        onRoute(null, false)
        onTrail(null)
        fitPending = false
        panel = Panel.Loading(heading)
        job = scope.launch {
            val p = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Panel.Failed(heading, e.message ?: e.toString()) { load(heading, after, block) }
            }
            panel = p
            after(p)
        }
    }
}
