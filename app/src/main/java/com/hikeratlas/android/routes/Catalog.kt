package com.hikeratlas.android.routes

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

// How the website names, lists and links curated routes (app/hikes.ts and
// components/CuratedRoutes.tsx), ported so the app reads the same.

private val TYPE_LABEL = mapOf(
    "day" to "Day hike", "approach" to "Base camp", "multi-day" to "Multi-day",
    "from-camp" to "From camp", "stop" to "Stop",
)
private val TYPE_ORDER = listOf("day", "approach", "multi-day", "from-camp", "stop")

/** A route that follows an OSM hiking route carries its name. */
private fun named(name: String?, otherwise: String) = if (!name.isNullOrEmpty()) name else otherwise

fun km(x: Double) = String.format(Locale.US, "%.1f", x)
fun metres(x: Double) = String.format(Locale.US, "%,d", x.roundToInt())

/** "Day hike · 12.3 km · ↑ 850 m", plus "· scramble" for SAC 4 and up. */
fun meta(type: String, km: Double, gain: Double, sac: Int? = null): String =
    "${TYPE_LABEL[type] ?: type} · ${km(km)} km · ↑ ${metres(gain)} m" + if ((sac ?: 0) >= 4) " · scramble" else ""

/** How far a stop is from the car: a pull-out has no walk. */
fun walk(h: Hike): String {
    val d = h.route.one_way_km ?: (h.route.km / 2)
    val end = h.from == "Road's end"
    if (d < 0.05) return if (end) "by the road" else "by the road at ${h.from}"
    return "${km(d)} km walk from ${if (end) "the road's end" else h.from}"
}

/** A route in a list: under a tap, in view, or in a park. */
data class RouteItem(
    val park: String,
    val id: String,
    val title: String,
    val meta: String,
    val section: String? = null,
)

/** A route opened from its id: <park>/day/i, /stop/i, /base/i, /base/i/j or /multi/i. */
data class Selected(
    val id: String,
    val park: String,
    val title: String,
    val label: String,
    val route: Route,
    val why: List<Pair<String, Double>> = emptyList(),
    /** For a base camp's walk in, and its day hikes: the camp. */
    val base: BaseCamp? = null,
    val baseIndex: Int? = null,
    /** Its shape: loop, out-and-back, one-way or point-to-point (routes' kind). */
    val kind: String = "",
    /** Where a day hike goes, for its trip reports' "about" pages. */
    val dest: String? = null,
)

fun lookup(doc: ParkDoc, id: String): Selected? {
    val parts = id.split('/')
    val park = parts[0]
    val i = parts.getOrNull(2)?.toIntOrNull() ?: return null
    val j = parts.getOrNull(3)?.toIntOrNull()
    fun why(h: Hike) = h.why.orEmpty().mapNotNull { e ->
        val k = e.getOrNull(0)?.content ?: return@mapNotNull null
        val v = e.getOrNull(1)?.content?.toDoubleOrNull() ?: return@mapNotNull null
        k to v
    }
    return when (parts.getOrNull(1)) {
        "day" -> doc.day_hikes.getOrNull(i)?.let { h ->
            // Titled as its page is: by its destination alone where that's
            // unique in the park; then the subtitle says where it starts.
            val short = h.name.isNullOrEmpty() && uniqueNames(doc)(h.dest)
            Selected(
                id, park, if (short) h.dest else named(h.name, "${h.dest} from ${h.from}"),
                (if (short) "Day hike from ${trailhead(h.from)}" else "Day hike") + " · ${shapeWords(h.kind, h.return_by)}",
                h.route, why(h), kind = h.kind, dest = h.dest,
            )
        }
        "stop" -> doc.stops.getOrNull(i)?.let { h ->
            Selected(id, park, h.dest, "Stop · ${walk(h)}", h.route, why(h), kind = h.kind, dest = h.dest)
        }
        "base" -> doc.base_camps.getOrNull(i)?.let { b ->
            if (j == null) {
                Selected(id, park, b.camp, "Base camp · walk in from ${b.from}", b.approach, base = b, baseIndex = i, kind = "one-way", dest = b.camp)
            } else {
                b.hikes.getOrNull(j)?.let { h ->
                    Selected(
                        id, park, named(h.name, "${h.dest} from ${b.camp}"),
                        "Day hike from base camp · ${shapeWords(h.kind, h.return_by)}", h.route, why(h), b, i, h.kind, h.dest,
                    )
                }
            }
        }
        "multi" -> doc.point_to_points.getOrNull(i)?.let { t ->
            Selected(id, park, named(t.name, "${t.from} to ${t.to}"), "Multi-day · point to point (needs a shuttle)", t.route, kind = t.kind)
        }
        else -> null
    }
}

/** Everything a park lists, in the website's order and sections. */
fun itemsFromPark(doc: ParkDoc): List<RouteItem> {
    val park = doc.park?.id ?: return emptyList()
    return buildList {
        doc.day_hikes.forEachIndexed { i, h ->
            add(RouteItem(park, "$park/day/$i", named(h.name, "${h.dest} from ${h.from}"), meta("day", h.route.km, h.route.gain, h.route.max_sac), "Day hikes"))
        }
        doc.base_camps.forEachIndexed { i, b ->
            add(RouteItem(park, "$park/base/$i", "Base camp: ${b.camp} from ${b.from}", "${b.hikes.size} day hikes from camp · walk in ${km(b.approach.km)} km", "Base camps"))
        }
        doc.point_to_points.forEachIndexed { i, t ->
            add(RouteItem(park, "$park/multi/$i", named(t.name, "${t.from} to ${t.to}"), meta("multi-day", t.route.km, t.route.gain, t.route.max_sac), "Multi-day"))
        }
        // Multi-day trips of a smaller park inside this one, opened in that park.
        doc.see_also.forEach { e ->
            add(RouteItem(e.park, e.id, named(e.name, "${e.from} to ${e.to}"), "${meta("multi-day", e.km, e.gain)} · in ${e.park_name}", "Multi-day"))
        }
        doc.stops.forEachIndexed { i, h ->
            add(RouteItem(park, "$park/stop/$i", h.dest, walk(h).replaceFirstChar { it.uppercase() }, "Stops"))
        }
    }
}

/** Items from route-layer feature properties: one per route, best first in each park. */
fun itemsFromProperties(props: List<Map<String, Any?>>, fromCamp: Boolean = true): List<RouteItem> =
    props.filter { it["id"] != null }
        .distinctBy { it["id"].toString() }
        .filter { fromCamp || it["type"] != "from-camp" }
        .sortedWith(
            compareBy<Map<String, Any?>> { it["park"].toString() }
                .thenBy { TYPE_ORDER.indexOf(it["type"]).let { i -> if (i < 0) 9 else i } }
                .thenComparator { a, b -> naturalCompare(a["id"].toString(), b["id"].toString()) },
        )
        .map { p ->
            val type = p["type"]?.toString() ?: ""
            val title = p["title"]?.toString() ?: ""
            RouteItem(
                park = p["park_id"]?.toString() ?: p["id"].toString().substringBefore('/'),
                id = p["id"].toString(),
                title = if (type == "approach") "Base camp: $title" else title,
                meta = meta(type, (p["km"] as? Number)?.toDouble() ?: 0.0, (p["gain"] as? Number)?.toDouble() ?: 0.0),
            )
        }

private fun naturalCompare(a: String, b: String): Int {
    val ra = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
    val rb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
    for (k in 0 until minOf(ra.size, rb.size)) {
        val x = ra[k]; val y = rb[k]
        val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
        if (c != 0) return c
    }
    return ra.size - rb.size
}

/** Lowercase ASCII words joined by hyphens, as basemap's bin/hike-paths.py makes them. */
fun slugify(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "")
        .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')

/**
 * Each route's slug within its park, as the website makes them: a day hike
 * "<dest> from <start>" is slugged by its destination alone where nothing else
 * in the park goes there; repeats get -2, -3, ...
 */
/** Whether a name is the park's only route of that name: a day hike's destination, a camp, a trip. */
fun uniqueNames(doc: ParkDoc): (String) -> Boolean {
    val seen = mutableMapOf<String, Int>()
    fun count(s: String) { seen.merge(s.lowercase(Locale.ROOT), 1, Int::plus) }
    doc.day_hikes.forEach { count(it.dest); it.name?.let(::count) }
    doc.base_camps.forEach { count(it.camp) }
    doc.point_to_points.forEach { count(named(it.name, "${it.from} to ${it.to}")) }
    return { s -> seen[s.lowercase(Locale.ROOT)] == 1 }
}

fun hikeSlugs(doc: ParkDoc): Map<String, String> {
    val park = doc.park?.id ?: return emptyMap()
    val unique = uniqueNames(doc)

    val used = mutableMapOf<String, Int>()
    fun claim(base: String, into: MutableMap<String, Int> = used): String {
        val n = into.merge(base, 1, Int::plus)!!
        return if (n == 1) base else "$base-$n"
    }
    val out = linkedMapOf<String, String>()
    doc.day_hikes.forEachIndexed { i, h ->
        val title = named(h.name, "${h.dest} from ${h.from}")
        val short = if (h.name == null && unique(h.dest)) h.dest else title
        out["$park/day/$i"] = claim(slugify(short).ifEmpty { "hike" })
    }
    doc.stops.forEachIndexed { i, h -> out["$park/stop/$i"] = claim(slugify(h.dest).ifEmpty { "stop" }) }
    doc.base_camps.forEachIndexed { i, b ->
        val camp = claim(slugify(b.camp).ifEmpty { "camp" })
        out["$park/base/$i"] = camp
        val inCamp = mutableMapOf<String, Int>()
        b.hikes.forEachIndexed { j, h ->
            out["$park/base/$i/$j"] = "$camp/${claim(slugify(named(h.name, h.dest)).ifEmpty { "hike" }, inCamp)}"
        }
    }
    doc.point_to_points.forEachIndexed { i, t ->
        out["$park/multi/$i"] = claim(slugify(named(t.name, "${t.from} to ${t.to}")).ifEmpty { "hike" })
    }
    return out
}

const val SITE = "https://www.hikeratlas.com"

/** The hike's page on the website, or null if its park has no path. */
fun hikeUrl(index: BundleIndex, doc: ParkDoc, id: String): String? {
    val parkPath = index.paths[doc.park?.id ?: return null] ?: return null
    val slug = hikeSlugs(doc)[id] ?: return "$SITE/hikes/$parkPath"
    return "$SITE/hikes/$parkPath/$slug"
}

fun parkUrl(index: BundleIndex, parkId: String): String? = index.paths[parkId]?.let { "$SITE/hikes/$it" }

/**
 * What a route's pages are filed under (its key, else its id), and its
 * destination's name for "More about ...": a base camp's walk in is about
 * the camp; a multi-day trip has no one destination.
 */
fun reportsKey(doc: ParkDoc, id: String): Pair<String, String?> {
    val parts = id.split('/')
    val i = parts.getOrNull(2)?.toIntOrNull()
    val j = parts.getOrNull(3)?.toIntOrNull()
    val (key, dest) = when (parts.getOrNull(1)) {
        "day" -> i?.let { doc.day_hikes.getOrNull(it) }?.let { it.key to it.dest }
        "stop" -> i?.let { doc.stops.getOrNull(it) }?.let { it.key to it.dest }
        "base" -> i?.let { doc.base_camps.getOrNull(it) }?.let { b ->
            if (j == null) b.key to b.camp else b.hikes.getOrNull(j)?.let { it.key to it.dest }
        }
        "multi" -> i?.let { doc.point_to_points.getOrNull(it) }?.let { it.key to null }
        else -> null
    } ?: (null to null)
    return (key ?: id) to dest
}
