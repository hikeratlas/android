package com.hikeratlas.android.track

import com.hikeratlas.android.routes.BundleIndex
import com.hikeratlas.android.routes.KeyedHike
import com.hikeratlas.android.routes.LngLat
import com.hikeratlas.android.routes.ParkDoc
import com.hikeratlas.android.routes.Regions
import com.hikeratlas.android.routes.keyedRoutes
import com.hikeratlas.android.routes.lookup
import kotlinx.coroutines.CancellationException
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * How near a hike and a track must come to be offered together: a track
 * passing within this of a hike's line. Wide enough for GPS drift, a
 * different trailhead or car park, or a side trip; and on real parks (the
 * White Mountains, the Adirondacks, Mount Hood, Garibaldi), a track along
 * one of their hikes passes within 1 km of 3 to 12 hikes (the median), 31
 * at most, which a list shows without needing a search (2 km: up to 45).
 */
const val NEAR_METRES = 1000.0

/** A curated hike near a track: [metres], the closest the track comes to its line. */
data class NearbyHike(val key: String, val hike: KeyedHike, val metres: Double)

/** At most this many parks around a track are searched. */
private const val MAX_PARKS = 20
/** Lines are compared every this many metres along them. */
private const val STEP = 25.0

/** Where on a flat plane around [lat0], in metres: close enough for the distances compared here. */
private class Plane(lat0: Double) {
    private val kx = 111_320.0 * cos(Math.toRadians(lat0))
    private val ky = 110_540.0
    fun x(p: LngLat) = p[0] * kx
    fun y(p: LngLat) = p[1] * ky
}

/** Points every [STEP] metres along [line] (and its corners), on [plane]. */
private fun samples(line: List<LngLat>, plane: Plane): List<Pair<Double, Double>> {
    if (line.isEmpty()) return emptyList()
    val out = mutableListOf(plane.x(line[0]) to plane.y(line[0]))
    for (k in 1 until line.size) {
        val ax = plane.x(line[k - 1]); val ay = plane.y(line[k - 1])
        val bx = plane.x(line[k]); val by = plane.y(line[k])
        val d = hypot(bx - ax, by - ay)
        val n = floor(d / STEP).toInt()
        for (s in 1..n) out += (ax + (bx - ax) * s * STEP / d) to (ay + (by - ay) * s * STEP / d)
        out += bx to by
    }
    return out
}

/**
 * A line's points in cells [within] wide, so the nearest within [within]
 * of a point is found looking at nine cells rather than every point.
 */
private class Grid(lines: List<List<LngLat>>, private val plane: Plane, private val within: Double) {
    private val cells = HashMap<Long, MutableList<DoubleArray>>()

    init {
        for (l in lines) for ((x, y) in samples(l, plane)) cells.getOrPut(cell(x, y)) { mutableListOf() } += doubleArrayOf(x, y)
    }

    private fun key(i: Long, j: Long) = (i shl 32) xor (j and 0xffffffffL)
    private fun cell(x: Double, y: Double) = key(floor(x / within).toLong(), floor(y / within).toLong())

    /** The closest [line] comes to these lines, if it's within [within]; else null. */
    fun closest(line: List<LngLat>): Double? {
        var best = Double.MAX_VALUE
        for ((x, y) in samples(line, plane)) {
            val i = floor(x / within).toLong()
            val j = floor(y / within).toLong()
            for (di in -1L..1L) for (dj in -1L..1L) {
                val pts = cells[key(i + di, j + dj)] ?: continue
                for (p in pts) best = minOf(best, hypot(p[0] - x, p[1] - y))
            }
        }
        return best.takeIf { it <= within }
    }
}

private fun planeFor(lines: List<List<LngLat>>): Plane? {
    val all = lines.flatten()
    return if (all.isEmpty()) null else Plane(all.sumOf { it[1] } / all.size)
}

/** The closest a track ([segments], each a list of [lng, lat]) comes to [line], if within [within] metres; else null. */
fun closestApproach(segments: List<List<LngLat>>, line: List<LngLat>, within: Double = NEAR_METRES): Double? {
    val plane = planeFor(segments) ?: return null
    if (line.isEmpty()) return null
    return Grid(segments, plane, within).closest(line)
}

/** A box, [west, south, east, north], grown by [metres] each way. */
internal fun grow(b: List<Double>, metres: Double): List<Double> {
    val dLat = metres / 110_540.0
    val dLon = metres / (111_320.0 * cos(Math.toRadians((b[1] + b[3]) / 2)).coerceAtLeast(0.01))
    return listOf(b[0] - dLon, b[1] - dLat, b[2] + dLon, b[3] + dLat)
}

internal fun boxOf(points: List<LngLat>) =
    listOf(points.minOf { it[0] }, points.minOf { it[1] }, points.maxOf { it[0] }, points.maxOf { it[1] })

internal fun overlaps(a: List<Double>, b: List<Double>) = a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1]

/**
 * Every curated hike whose line passes within [within] metres of the track,
 * closest first (to the nearest 25 m, then by name): the hikes a track may have been, for the
 * user to choose from. Only hikes the build gave a key, as posts need one.
 * [park] loads a park's routes, as the app opens them; one that won't load
 * is passed over.
 */
suspend fun hikesNear(
    segments: List<List<LngLat>>,
    regions: Regions,
    index: BundleIndex,
    park: suspend (String) -> ParkDoc?,
    within: Double = NEAR_METRES,
): List<NearbyHike> {
    val all = segments.flatten()
    val plane = planeFor(segments) ?: return emptyList()
    val box = grow(boxOf(all), within)
    // The parks whose box the track's (grown) crosses, smallest first. A park
    // under two ids shares one document.
    val parks = regions.countries.flatMap { c -> c.states.flatMap { it.areas } }
        .filter { a -> a.bbox?.let { it.size >= 4 && overlaps(it, box) } == true }
        .sortedBy { a -> a.bbox!!.let { (it[2] - it[0]) * (it[3] - it[1]) } }
        .flatMap { a -> a.ids.ifEmpty { listOf(a.id) } }
        .filter { index.parks.containsKey(it) }
        .distinctBy { index.parks[it]?.firstOrNull() }
        .take(MAX_PARKS)
    val grid = Grid(segments, plane, within)
    val found = linkedMapOf<String, NearbyHike>()
    for (id in parks) {
        val doc = try {
            park(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: continue
        for ((key, routeId) in keyedRoutes(doc)) {
            if (key in found) continue
            val sel = lookup(doc, routeId) ?: continue
            val line = sel.route.line
            if (line.isEmpty() || !overlaps(boxOf(line), box)) continue
            val d = grid.closest(line) ?: continue
            found[key] = NearbyHike(key, KeyedHike(routeId.substringBefore('/'), doc.park?.name ?: "", routeId, sel.title), d)
        }
    }
    // To the nearest STEP, the lines' own resolution: hikes the track walked
    // (all about 0 m) go by name.
    return found.values.sortedWith(compareBy<NearbyHike> { Math.round(it.metres / STEP) }.thenBy { it.hike.title })
}
