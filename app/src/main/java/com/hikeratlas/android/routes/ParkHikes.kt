package com.hikeratlas.android.routes

import kotlin.math.max

// What the park and hike sheets show of a park's routes, as the website's
// app/park.ts (its sheet redesign): each day hike's difficulty, time, kind of
// destination and trip reports, the filters over them, and the picks to start
// with. Pure functions of the routes document.

enum class Difficulty(val label: String) { Easy("Easy"), Moderate("Moderate"), Hard("Hard"), Scramble("Scramble") }

/**
 * From the route's hardest SAC grade, its climb and its length: SAC T4 and
 * up is a scramble; 1,000 m of climb, 18 km or SAC T3 is hard; 400 m or 10 km
 * is moderate; less is easy.
 */
fun difficulty(r: Route): Difficulty {
    val sac = r.max_sac ?: 0
    return when {
        sac >= 4 -> Difficulty.Scramble
        r.gain >= 1000 || r.km >= 18 || sac == 3 -> Difficulty.Hard
        r.gain >= 400 || r.km >= 10 -> Difficulty.Moderate
        else -> Difficulty.Easy
    }
}

/** Walking time in hours, to the half hour: 4 km an hour, plus an hour for each 600 m of climb. */
fun hours(r: Route): Double = max(0.25, Math.round((r.km / 4 + r.gain / 600) * 2) / 2.0)

/** "about 40 min", "~2.5 h", "~3 h". */
fun timeLabel(h: Double, r: Route? = null): String {
    if (h < 1) {
        val min = if (r != null) max(15L, Math.round((r.km / 4 + r.gain / 600) * 60 / 5) * 5) else Math.round(h * 60)
        return "about $min min"
    }
    return "~${if (h % 1.0 == 0.0) h.toLong().toString() else h.toString()} h"
}

enum class TimeBand { Short, Half, Full }

fun timeBand(h: Double) = when {
    h <= 2 -> TimeBand.Short
    h <= 5 -> TimeBand.Half
    else -> TimeBand.Full
}

/** What SAC grade a route's hardest stretch is, in words. */
val SAC_WORDS = mapOf(
    1 to "SAC T1: a good path, easy going",
    2 to "SAC T2 at its hardest: steep and rocky in places, sure footing needed",
    3 to "SAC T3 at its hardest: rough, exposed in places, hands sometimes needed for balance",
    4 to "SAC T4 at its hardest: scrambling, with hands on rock and exposure; route-finding may be needed",
    5 to "SAC T5 at its hardest: demanding alpine scrambling",
    6 to "SAC T6 at its hardest: difficult alpine terrain",
)

/** Land cover colours, ARGB. "other" (unmapped, farmland, towns) is drawn striped, as unknown. */
val TERRAIN_COLORS = mapOf(
    "forest" to 0xFF2F6F4E, "alpine rock" to 0xFFA8A29E, "meadow" to 0xFFA3C97A, "scrub" to 0xFFC9B38A,
    "glacier" to 0xFF9FD3EF, "water" to 0xFF5AB4E6, "wetland" to 0xFF7FB8A4, "sand" to 0xFFE4CF9A,
    "other" to 0xFFE7E5E4,
)

/** The stripes' other colour, for "other". */
const val TERRAIN_OTHER_STRIPE = 0xFFD6D3D1

val TERRAIN_TITLES = mapOf("other" to "Not mapped, or farmland, towns and the like")

/** Routes' land cover is OSM's, and ESA WorldCover's where OSM has none (basemap#167). */
const val WORLDCOVER_CREDIT = "Land cover: ESA WorldCover 2021 (CC BY 4.0)"
const val WORLDCOVER_URL = "https://esa-worldcover.org/"

/**
 * A route's shape in words: "loop", "out & back", "point to point", or for a
 * one-way hike "one way", with how you come back where known ("one way, Blue
 * Grouse Gondola down").
 */
fun shapeWords(kind: String, returnBy: String? = null): String = when (kind) {
    "loop" -> "loop"
    "out-and-back" -> "out & back"
    "point-to-point" -> "point to point"
    else -> if (!returnBy.isNullOrEmpty()) "one way, $returnBy down" else "one way"
}

/** A day hike in the park sheet. */
data class ParkHike(
    /** As [lookup] takes: <park>/day/<i>. */
    val id: String,
    val title: String,
    /** Its trailhead, without "trailhead". */
    val from: String,
    val route: Route,
    val diff: Difficulty,
    val hours: Double,
    val loop: Boolean,
    /** [shapeWords]. */
    val shape: String,
    val lake: Boolean,
    val summit: Boolean,
    /** The lakes, peaks, waterfalls... it goes by: up to three. */
    val features: List<String>,
    /** Trip reports and guides that follow it. */
    val reports: Int,
    val score: Double,
)

/** "Helen Lake Trail trailhead" -> "Helen Lake Trail"; "Fish Creek Parking Lot" -> "Fish Creek parking". */
fun trailhead(s: String): String =
    s.replace(Regex("\\s+trailhead$", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+Parking( Lot)?$", RegexOption.IGNORE_CASE), " parking")

private val FEATURE_KINDS = setOf("lake", "peak", "waterfall", "pass", "glacier", "viewpoint")

/** The kinds a route passes, with their names: [kind, names]. */
private fun passes(r: Route): List<Pair<String, List<String>>> = r.passes.mapNotNull { e ->
    val k = (e.getOrNull(0) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
    val names = (e.getOrNull(1) as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
    k to names
}

/** A park's day hikes for its sheet; [reports] counts a route's trip reports by its key. */
fun parkHikes(doc: ParkDoc, reports: (key: String) -> Int = { 0 }): List<ParkHike> {
    val park = doc.park?.id ?: ""
    return doc.day_hikes.mapIndexed { i, h ->
        val r = h.route
        val ps = passes(r)
        val features = ps.filter { it.first in FEATURE_KINDS }.flatMap { it.second }
            .filter { it.isNotEmpty() && !it.equals("unnamed", ignoreCase = true) }
        ParkHike(
            id = "$park/day/$i",
            title = h.name?.takeIf { it.isNotEmpty() } ?: h.dest,
            from = trailhead(h.from),
            route = r,
            diff = difficulty(r),
            hours = hours(r),
            loop = h.kind == "loop",
            shape = shapeWords(h.kind, h.return_by),
            lake = h.dest_kind == "lake" || ps.any { it.first == "lake" },
            summit = h.dest_kind == "peak",
            features = features.distinct().take(3),
            reports = reports(h.key ?: "$park/day/$i"),
            score = h.score ?: 0.0,
        )
    }
}

data class Filters(
    val time: TimeBand? = null,
    val diffs: Set<Difficulty> = emptySet(),
    val lakes: Boolean = false,
    val summits: Boolean = false,
    val loops: Boolean = false,
    val reports: Boolean = false,
) {
    val any get() = this != Filters()
}

fun matches(h: ParkHike, f: Filters): Boolean =
    (f.time == null || timeBand(h.hours) == f.time) &&
        (f.diffs.isEmpty() || h.diff in f.diffs) &&
        (!f.lakes || h.lake) && (!f.summits || h.summit) && (!f.loops || h.loop) && (!f.reports || h.reports > 0)

enum class Sort(val label: String) { Best("Best"), Shortest("Shortest"), LeastClimb("Least climb"), Highest("Highest") }

fun sorted(hikes: List<ParkHike>, s: Sort): List<ParkHike> = when (s) {
    Sort.Best -> hikes.sortedByDescending { it.score }
    Sort.Shortest -> hikes.sortedBy { it.route.km }
    Sort.LeastClimb -> hikes.sortedBy { it.route.gain }
    Sort.Highest -> hikes.sortedByDescending { it.route.max_ele }
}

/** Only a park with this many day hikes gets picks. */
const val PICKS_FROM = 8

/** Where to start: the best easy hike, moderate one, and hard one or scramble, each where there's one. */
fun picks(hikes: List<ParkHike>): List<Pair<String, ParkHike>> {
    if (hikes.size < PICKS_FROM) return emptyList()
    fun best(ds: Set<Difficulty>) = sorted(hikes.filter { it.diff in ds }, Sort.Best).firstOrNull()
    return listOfNotNull(
        best(setOf(Difficulty.Easy))?.let { "Easy" to it },
        best(setOf(Difficulty.Moderate))?.let { "Half day" to it },
        best(setOf(Difficulty.Hard, Difficulty.Scramble))?.let { "Big day" to it },
    )
}

/** A park with this few day hikes (and nothing else to list) gets the compact sheet. */
const val SMALL_PARK = 5

fun isSmall(doc: ParkDoc): Boolean =
    doc.day_hikes.size <= SMALL_PARK && doc.base_camps.isEmpty() && doc.point_to_points.isEmpty() && doc.see_also.isEmpty()

/** A small park's summary, from its numbers alone: "Three short, nearly flat loops, each under an hour." */
fun summary(hikes: List<ParkHike>): String {
    val n = hikes.size
    if (n == 0) return ""
    val count = listOf("", "One", "Two", "Three", "Four", "Five").getOrNull(n) ?: n.toString()
    val short = hikes.all { it.route.km <= 6 }
    val flat = hikes.all { it.route.gain < 100 }
    val loops = hikes.all { it.loop }
    val noun = if (loops) (if (n == 1) "loop" else "loops") else if (n == 1) "hike" else "hikes"
    val easy = hikes.all { it.diff == Difficulty.Easy }
    val adj = listOf(if (short) "short" else "", if (flat) "nearly flat" else if (easy) "easy" else "").filter { it.isNotEmpty() }.joinToString(", ")
    val longest = hikes.maxOf { it.hours }
    val l = if (longest % 1.0 == 0.0) longest.toLong().toString() else longest.toString()
    val whenWords = when {
        longest < 1 -> if (n == 1) ", under an hour" else ", each under an hour"
        longest <= 2 -> if (n == 1) ", under two hours" else ", each under two hours"
        n == 1 -> ", about $l hours"
        else -> ", the longest about $l hours"
    }
    return "$count ${if (adj.isNotEmpty()) "$adj " else ""}$noun$whenWords."
}

private val KIND_WORDS = mapOf(
    "lake" to ("a lake" to "lakes"), "peak" to ("a summit" to "summits"), "waterfall" to ("a waterfall" to "waterfalls"),
    "glacier" to ("a glacier" to "glaciers"), "pass" to ("a pass" to "passes"), "viewpoint" to ("a viewpoint" to "viewpoints"),
    "hut" to ("a hut" to "huts"), "cave" to ("a cave" to "caves"), "arch" to ("an arch" to "arches"),
)

/** What a route passes, in words: "Lakes, a waterfall and a summit". */
fun featureWords(r: Route): String {
    val parts = passes(r).mapNotNull { (k, n) -> KIND_WORDS[k]?.let { if (n.size > 1) it.second else it.first } }
    if (parts.isEmpty()) return ""
    val s = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    return s.replaceFirstChar { it.uppercase() }
}

/** Each score part: the bar it must reach to count, and what it says. */
private val WHY_BARS: Map<String, Pair<Double, (Route) -> String>> = mapOf(
    "feature_kinds" to (1.08 to { r -> featureWords(r) }),
    "popularity" to (0.6 to { _ -> "Popular" }),
    "loop" to (0.5 to { _ -> "A loop: no retracing your steps" }),
    "known_route" to (0.4 to { _ -> "Follows a named hiking route" }),
    "open_high" to (0.5 to { _ -> "Open and high: above the trees" }),
    "lc_variety" to (0.7 to { _ -> "Varied terrain" }),
)

/** Why a route made the list, from its score's parts: those past their bar, best first. */
fun whyWords(why: List<Pair<String, Double>>, r: Route): List<String> =
    why.filter { (k, v) -> WHY_BARS[k]?.let { v >= it.first } == true }
        .sortedByDescending { it.second }
        .map { (k, _) -> WHY_BARS.getValue(k).second(r) }
        .filter { it.isNotEmpty() }

/** Something a route passes, numbered for the chart, the map and the list. */
data class AlongFeature(val n: Int, val kind: String, val name: String, val km: Double? = null, val at: LngLat? = null)

/**
 * What a route passes: from [Route.alongs] (in order, with where), else its
 * passes (builds before routes#170). Unnamed ones and repeats left out; at
 * most 12.
 */
fun alongOf(r: Route): List<AlongFeature> {
    val seen = HashSet<String>()
    fun ok(n: String) = n.isNotEmpty() && !n.equals("unnamed", ignoreCase = true) && seen.add(n)
    val alongs = r.alongs
    val out = if (alongs.isNotEmpty()) {
        alongs.filter { ok(it.name) }.map { AlongFeature(0, it.kind, it.name, it.km, it.at) }
    } else {
        passes(r).flatMap { (k, names) -> names.filter(::ok).map { AlongFeature(0, k, it) } }
    }
    return out.take(12).mapIndexed { i, f -> f.copy(n = i + 1) }
}

/** What a feature is, in a word. */
val KIND_NAMES = mapOf(
    "lake" to "Lake", "peak" to "Summit", "waterfall" to "Waterfall", "glacier" to "Glacier", "pass" to "Pass",
    "viewpoint" to "Viewpoint", "hut" to "Hut", "cave" to "Cave", "arch" to "Arch", "spring" to "Spring",
    "saddle" to "Saddle", "camp" to "Camp",
)

fun kindName(kind: String) = KIND_NAMES[kind] ?: kind.replace('_', ' ')

/** "A, B and C". */
fun listWords(xs: List<String>): String =
    if (xs.size <= 1) xs.joinToString("") else xs.dropLast(1).joinToString(", ") + " and " + xs.last()

/** A curated route as GPX, for a GPS or phone app (the website's gpx). */
fun routeGpx(name: String, line: List<LngLat>): String {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val pts = line.joinToString("\n") { String.format(java.util.Locale.ROOT, "<trkpt lat=\"%.6f\" lon=\"%.6f\"/>", it[1], it[0]) }
    return """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Hiker Atlas" xmlns="http://www.topografix.com/GPX/1/1">
<trk><name>${esc(name)}</name><trkseg>
$pts
</trkseg></trk>
</gpx>
"""
}

/** A park near another, for a small park's "More hiking nearby". */
data class NearbyPark(val id: String, val name: String, val hikes: Int, val km: Int)

/** The parks nearest [id] with at least [min] hikes, by the distance between their boxes' centres. */
fun nearbyParks(regions: Regions, id: String, min: Int = 3, n: Int = 3): List<NearbyPark> {
    val areas = regions.countries.flatMap { c -> c.states.flatMap { it.areas } }
    val me = areas.firstOrNull { it.covers(id) } ?: return emptyList()
    val box = me.bbox ?: return emptyList()
    fun mid(b: List<Double>) = listOf((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
    val m = mid(box)
    return areas.filter { it !== me && it.bbox != null && it.hikes >= min && it.id != me.id }
        .map { a -> NearbyPark(a.id, a.name, a.hikes, Math.round(com.hikeratlas.android.elevation.ElevationProfiler.haversine(m, mid(a.bbox!!)) / 1000).toInt()) }
        .sortedBy { it.km }.take(n)
}
