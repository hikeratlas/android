package com.hikeratlas.android.routing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hikeratlas.android.elevation.ElevationProfiler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

/**
 * A route planned by tapping points on the map, as the website's route
 * planner (components/useWaypoints.ts): each point is routed along trails and
 * roads from the one before; the last can be undone, and any point moved,
 * re-routing the legs either side of it.
 *
 * [fetch] reads tiles from the basemap at a zoom (null where there's none).
 */
class Planner(
    private val scope: CoroutineScope,
    private val fetch: suspend (z: Int, tiles: List<Pair<Int, Int>>) -> Map<Pair<Int, Int>, ByteArray?>,
    private val profiler: ElevationProfiler?,
    /** Where tiles are fetched and graphs built: tests pass their own. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    class Leg(val coords: List<List<Double>>, val metres: Double)

    /** The points, where they snapped to the trails. */
    var points by mutableStateOf<List<List<Double>>>(emptyList()); private set
    /** The route through them: empty until there are two. */
    var coords by mutableStateOf<List<List<Double>>>(emptyList()); private set
    var metres by mutableDoubleStateOf(0.0); private set
    /** Taps not yet routed, drawn at once so a tap shows it was taken. */
    var pending by mutableStateOf<List<List<Double>>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    /** Why the last leg couldn't be routed, if it couldn't. */
    var error by mutableStateOf<String?>(null); private set
    var profile by mutableStateOf<ElevationProfiler.Profile?>(null); private set

    private val placed = ArrayList<List<Double>>()
    private val legs = ArrayList<Leg>()

    // The work queue: legs are routed one at a time, in tap order, so an
    // undo or a quick second tap waits for the leg before it.
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val queued = AtomicInteger(0)
    private var profileJob: Job? = null

    init {
        scope.launch {
            for (job in queue) {
                busy = true
                try {
                    job()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Graph.TooFarException) {
                    error = "Those points are too far apart to route here."
                } catch (e: Exception) {
                    error = "Couldn't route: ${e.message ?: e}"
                } finally {
                    busy = queued.decrementAndGet() > 0
                    commit()
                }
            }
        }
    }

    private fun enqueue(job: suspend () -> Unit) {
        queued.incrementAndGet()
        queue.trySend(job)
    }

    // Tiles fetched (or being fetched) for routing, oldest first: a leg near
    // earlier ones needs none fetched.
    private val cache = object : LinkedHashMap<String, Deferred<ByteArray?>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Deferred<ByteArray?>>) = size > TILE_CACHE
    }

    /** Tiles at z, from the cache, fetching the missing ones in one batch. */
    private suspend fun tiles(z: Int, coords: List<Pair<Int, Int>>): List<Triple<Int, Int, ByteArray?>> {
        val missing = ArrayList<Pair<Int, Int>>()
        val wanted = synchronized(cache) {
            coords.map { c ->
                val key = "$z/${c.first}/${c.second}"
                cache[key] ?: CompletableDeferred<ByteArray?>().also { cache[key] = it; missing += c }
            }
        }
        if (missing.isNotEmpty()) {
            scope.launch(io) {
                val got = try {
                    fetch(z, missing)
                } catch (e: Exception) {
                    // A failed fetch isn't kept: the next leg tries again.
                    synchronized(cache) { missing.forEach { cache.remove("$z/${it.first}/${it.second}") } }
                    missing.forEach { c -> (wanted[coords.indexOf(c)] as CompletableDeferred).completeExceptionally(e) }
                    return@launch
                }
                for (c in missing) (wanted[coords.indexOf(c)] as CompletableDeferred).complete(got[c])
            }
        }
        return coords.mapIndexed { i, c -> Triple(c.first, c.second, wanted[i].await()) }
    }

    /** Fetches tiles ahead of being asked, while the next point is picked. */
    private fun prefetch(z: Int, coords: List<Pair<Int, Int>>) {
        scope.launch { runCatching { tiles(z, coords) } }
    }

    /** While planning: the tiles in view (and a margin), so a tap anywhere routes from memory. */
    fun prefetchView(west: Double, south: Double, east: Double, north: Double, viewZoom: Double) {
        val z = routeZoom(viewZoom)
        val (x0, y0) = ElevationProfiler.lngLatToTile(west, north, z)
        val (x1, y1) = ElevationProfiler.lngLatToTile(east, south, z)
        val xs = (floor(x0).toInt() - VIEW_MARGIN)..(floor(x1).toInt() + VIEW_MARGIN)
        val ys = (floor(y0).toInt() - VIEW_MARGIN)..(floor(y1).toInt() + VIEW_MARGIN)
        if (xs.count().toLong() * ys.count() > VIEW_MAX_TILES) return
        prefetch(z, xs.flatMap { x -> ys.map { y -> x to y } })
    }

    private suspend fun routeLeg(from: List<Double>, to: List<Double>, z: Int): Leg? {
        val tiles = tiles(z, Graph.tilesAround(from, to, z = z))
        return withContext(cpu) {
            val g = Graph.build(tiles, z)
            val (s, t) = Graph.snapPair(g, from, to)
            val found = Graph.shortestPath(g, s, t) ?: return@withContext null
            Leg(found.nodes.map { g.lngLat(it) }, found.metres)
        }
    }

    /** Adds a point (tapped at the view's zoom). */
    fun add(p: List<Double>, viewZoom: Double) {
        pending = pending + listOf(p)
        val z = routeZoom(viewZoom)
        enqueue {
            try {
                error = null
                if (placed.isEmpty()) {
                    placed += p
                } else {
                    val leg = routeLeg(placed.last(), p, z)
                    if (leg == null) {
                        error = "No route to that point on trails or roads."
                    } else {
                        placed += p
                        legs += leg
                    }
                }
                if (placed.lastOrNull() === p) prefetch(z, Graph.tilesAround(p, p, z = z))
            } finally {
                // Once routed (or not), the pending point gives way.
                pending = pending.drop(1)
            }
        }
    }

    fun undo() = enqueue {
        error = null
        if (placed.size > 1) legs.removeAt(legs.lastIndex)
        if (placed.isNotEmpty()) placed.removeAt(placed.lastIndex)
    }

    fun clear() = enqueue {
        placed.clear()
        legs.clear()
        error = null
    }

    /** Moves point i to p, re-routing the legs either side (what dragging does). */
    fun move(i: Int, p: List<Double>, viewZoom: Double) {
        val z = routeZoom(viewZoom)
        enqueue {
            error = null
            if (i !in placed.indices) return@enqueue
            val before = if (i > 0) routeLeg(placed[i - 1], p, z) else null
            if (i > 0 && before == null) { error = "No route to that point on trails or roads."; return@enqueue }
            val after = if (i < placed.size - 1) routeLeg(p, placed[i + 1], z) else null
            if (i < placed.size - 1 && after == null) { error = "No route from that point on trails or roads."; return@enqueue }
            if (before != null) legs[i - 1] = before
            if (after != null) legs[i] = after
            placed[i] = p
        }
    }

    /** Whether the route comes back to its start: then dragging either end moves both. */
    fun isLoop(): Boolean = placed.size > 2 && ElevationProfiler.haversine(placed.first(), placed.last()) <= SAME_END_M

    /** Moves the start and end together, for a loop. */
    fun moveEnds(p: List<Double>, viewZoom: Double) {
        val last = placed.size - 1
        move(0, p, viewZoom)
        move(last, p, viewZoom)
    }

    /** Publishes the route: the points as snapped, the line, its length, and its profile. */
    private fun commit() {
        val n = placed.size
        if (n < 2 || legs.size < n - 1) {
            points = placed.toList()
            coords = emptyList()
            metres = 0.0
        } else {
            points = placed.indices.map { i -> if (i == 0) legs[0].coords.first() else legs[i - 1].coords.last() }
            coords = legs.flatMapIndexed { i, l -> if (i == 0) l.coords else l.coords.drop(1) }
            metres = legs.sumOf { it.metres }
        }
        profileJob?.cancel()
        val line = coords
        if (line.size < 2 || profiler == null) {
            profile = null
            return
        }
        profileJob = scope.launch {
            profile = try {
                withContext(io) { profiler.profile(line) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    companion object {
        private const val TILE_CACHE = 1000
        private const val VIEW_MARGIN = 2
        private const val VIEW_MAX_TILES = 300

        /** A route that ends this close to its start is a loop: one marker. */
        const val SAME_END_M = 30.0

        /**
         * Legs are routed from tiles two zooms in from the view, z12 to z14:
         * the same trails at each, and about the same number of tiles across
         * the screen at any view zoom.
         */
        fun routeZoom(viewZoom: Double) = (floor(viewZoom).toInt() + 2).coerceIn(12, Graph.Z)
    }
}
