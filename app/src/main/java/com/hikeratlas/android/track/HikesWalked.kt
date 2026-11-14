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

/** A curated hike a track walked: [share] of its line is within [FOLLOW_METRES] of the track. */
data class HikeMatch(val key: String, val hike: KeyedHike, val share: Double, val km: Double)

/** A hike line's point this close to the track counts as walked: GPS and mapping each err by 10-20 m. */
const val FOLLOW_METRES = 40.0
/** Hikes the track followed less of than this aren't offered. */
const val MIN_SHARE = 0.3
/** At most this many parks around a track are searched. */
private const val MAX_PARKS = 12
/** Hike lines and the track are compared every this many metres along them. */
private const val STEP = 10.0

/**
 * Where on a flat plane around [lat0], in metres: close enough for the
 * tens of metres compared here.
 */
private class Plane(lat0: Double) {
    private val kx = 111_320.0 * cos(Math.toRadians(lat0))
    private val ky = 110_540.0
    fun x(p: LngLat) = p[0] * kx
    fun y(p: LngLat) = p[1] * ky
}

/**
 * The track's line, sampled every [STEP] metres and put in cells
 * [FOLLOW_METRES] wide, so "is this point near the track" looks at nine
 * cells rather than every point.
 */
private class Near(segments: List<List<LngLat>>, private val plane: Plane) {
    private val cells = HashMap<Long, MutableList<DoubleArray>>()

    init {
        for (s in segments) for ((x, y) in samples(s, plane)) {
            cells.getOrPut(cell(x, y)) { mutableListOf() } += doubleArrayOf(x, y)
        }
    }

    private fun key(i: Long, j: Long) = (i shl 32) xor (j and 0xffffffffL)
    private fun cell(x: Double, y: Double) = key(floor(x / FOLLOW_METRES).toLong(), floor(y / FOLLOW_METRES).toLong())

    fun near(x: Double, y: Double): Boolean {
        val i = floor(x / FOLLOW_METRES).toLong()
        val j = floor(y / FOLLOW_METRES).toLong()
        for (di in -1L..1L) for (dj in -1L..1L) {
            val pts = cells[key(i + di, j + dj)] ?: continue
            if (pts.any { hypot(it[0] - x, it[1] - y) <= FOLLOW_METRES }) return true
        }
        return false
    }
}

/** Points every [STEP] metres along [line] (and its ends), on [plane]. */
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
 * How much of a hike's [line] the track ([segments], each a list of
 * [lng, lat]) follows: the share of points along it, every [STEP] metres,
 * within [FOLLOW_METRES] of the track. Simple, and robust to GPS wander, a
 * different start, or turning back early (which lowers it).
 */
fun followShare(segments: List<List<LngLat>>, line: List<LngLat>): Double {
    val all = segments.flatten()
    if (all.isEmpty() || line.size < 2) return 0.0
    val plane = Plane(all.sumOf { it[1] } / all.size)
    return followShare(Near(segments, plane), plane, line)
}

private fun followShare(near: Near, plane: Plane, line: List<LngLat>): Double {
    val pts = samples(line, plane)
    if (pts.isEmpty()) return 0.0
    return pts.count { (x, y) -> near.near(x, y) }.toDouble() / pts.size
}

/**
 * The curated hikes a track walked, best first: those in the parks around
 * it whose line it follows at least [MIN_SHARE] of, by how much (to the
 * nearest 5%, then the longer hike, which a short one inside it would
 * otherwise beat). Only hikes the build gave a key, as posts need one.
 * [park] loads a park's routes, as the app opens them.
 */
suspend fun hikesWalked(
    segments: List<List<LngLat>>,
    regions: Regions,
    index: BundleIndex,
    park: suspend (String) -> ParkDoc?,
    max: Int = 5,
): List<HikeMatch> {
    val all = segments.flatten()
    if (all.size < 2) return emptyList()
    val margin = 0.01
    val w = all.minOf { it[0] } - margin
    val e = all.maxOf { it[0] } + margin
    val s = all.minOf { it[1] } - margin
    val n = all.maxOf { it[1] } + margin
    fun overlaps(b: List<Double>) = b.size >= 4 && b[0] <= e && b[2] >= w && b[1] <= n && b[3] >= s
    // The parks whose box the track's crosses, smallest first: those are
    // the ones it's most likely in. A park under two ids shares a document.
    val parks = regions.countries.flatMap { c -> c.states.flatMap { it.areas } }
        .filter { a -> a.bbox?.let(::overlaps) == true }
        .sortedBy { a -> a.bbox!!.let { (it[2] - it[0]) * (it[3] - it[1]) } }
        .flatMap { a -> a.ids.ifEmpty { listOf(a.id) } }
        .filter { index.parks.containsKey(it) }
        .distinctBy { index.parks[it]?.firstOrNull() }
        .take(MAX_PARKS)
    val plane = Plane(all.sumOf { it[1] } / all.size)
    val near = Near(segments, plane)
    val found = linkedMapOf<String, HikeMatch>()
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
            if (line.size < 2) continue
            if (!overlaps(listOf(line.minOf { it[0] }, line.minOf { it[1] }, line.maxOf { it[0] }, line.maxOf { it[1] }))) continue
            val share = followShare(near, plane, line)
            if (share >= MIN_SHARE) {
                found[key] = HikeMatch(key, KeyedHike(routeId.substringBefore('/'), doc.park?.name ?: "", routeId, sel.title), share, sel.route.km)
            }
        }
    }
    return found.values.sortedWith(compareByDescending<HikeMatch> { Math.round(it.share * 20) }.thenByDescending { it.km }).take(max)
}
