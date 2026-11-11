package com.hikeratlas.android.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hikeratlas.android.elevation.ElevationProfiler
import com.hikeratlas.android.routes.BundleIndex
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
import kotlinx.coroutines.launch
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
