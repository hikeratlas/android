package com.hikeratlas.android.routes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// Which curated hikes route keys are (for faves, and later posts and
// profiles), as the website's app/atproto/findRoutes.ts: a key's centroid
// says roughly where its route is, the parks whose hikes' box holds it are
// loaded, and their routes' keys (the build's route_key) matched exactly. A
// key no current hike has is a route that has left curation.

/** A hike a route key names, as the app opens it: [id] is lookup's (<park>/day/i, ...). */
data class KeyedHike(val park: String, val parkName: String, val id: String, val title: String)

/**
 * Every route in a park's document that has a key (the build's
 * route_key), by key, with its id as [lookup] takes it. A key listed twice
 * (a line in the park twice) keeps its first.
 */
fun keyedRoutes(doc: ParkDoc): Map<String, String> {
    val p = doc.park?.id ?: return emptyMap()
    val out = linkedMapOf<String, String>()
    fun add(r: Route, id: String) { r.route_key?.let { out.putIfAbsent(it, id) } }
    doc.day_hikes.forEachIndexed { i, h -> add(h.route, "$p/day/$i") }
    doc.stops.forEachIndexed { i, h -> add(h.route, "$p/stop/$i") }
    doc.base_camps.forEachIndexed { i, b ->
        add(b.approach, "$p/base/$i")
        b.hikes.forEachIndexed { j, h -> add(h.route, "$p/base/$i/$j") }
    }
    doc.point_to_points.forEachIndexed { i, t -> add(t.route, "$p/multi/$i") }
    return out
}

/** At most this many parks are loaded for one search. */
const val MAX_KEY_PARKS = 60
/** Degrees around a park's hikes' box that a key's centroid may fall in. */
const val KEY_MARGIN = 0.01
/** Parks loaded at once: each is a range request on the bundle. */
private const val AT_ONCE = 6

/**
 * The hikes [keys] name, in the build [regions] and [index] describe; a key
 * missing from the result is no current hike (or one whose park couldn't be
 * loaded). [park] loads a park's routes; [centroid] places a key.
 */
suspend fun findKeyedHikes(
    keys: Collection<String>,
    regions: Regions,
    index: BundleIndex,
    park: suspend (String) -> ParkDoc?,
    centroid: (String) -> LngLat? = RouteKeys::centroid,
): Map<String, KeyedHike> {
    val found = linkedMapOf<String, KeyedHike>()
    val want = keys.toSet()
    if (want.isEmpty()) return found
    val areas = regions.countries.flatMap { c -> c.states.flatMap { it.areas } }
    // The parks around each key's centroid. A park under two OSM ids shares
    // one document: loaded once, by its offset in the bundle.
    val parks = linkedMapOf<Long, String>()
    for (c in want.mapNotNull(centroid)) {
        val (lon, lat) = c[0] to c[1]
        for (a in areas) {
            val b = a.bbox ?: continue
            if (b.size < 4 || lon < b[0] - KEY_MARGIN || lon > b[2] + KEY_MARGIN || lat < b[1] - KEY_MARGIN || lat > b[3] + KEY_MARGIN) continue
            for (id in a.ids.ifEmpty { listOf(a.id) }) {
                val at = index.parks[id]?.firstOrNull() ?: continue
                parks.putIfAbsent(at, id)
            }
        }
    }
    for (batch in parks.values.take(MAX_KEY_PARKS).chunked(AT_ONCE)) {
        val docs = coroutineScope {
            batch.map { id ->
                async {
                    try {
                        park(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // One park that won't load leaves its keys unfound, not the rest.
                        null
                    }
                }
            }.awaitAll()
        }
        for (doc in docs) {
            doc ?: continue
            for ((k, id) in keyedRoutes(doc)) {
                if (k !in want || k in found) continue
                val title = lookup(doc, id)?.title ?: continue
                found[k] = KeyedHike(id.substringBefore('/'), doc.park?.name ?: "", id, title)
            }
        }
        if (found.size == want.size) break
    }
    return found
}
