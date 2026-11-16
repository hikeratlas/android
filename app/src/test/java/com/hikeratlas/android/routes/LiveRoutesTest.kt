package com.hikeratlas.android.routes

import com.hikeratlas.android.Http
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Reads the published routes bundle. Opt in with HIKERATLAS_LIVE=1. */
class LiveRoutesTest {
    @Test
    fun readsGaribaldi() = runBlocking {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        val repo = RoutesRepository(Http.builder().build())
        assertTrue(repo.index().parks.size > 1000)
        val doc = repo.park("2226817")!!
        assertEquals("Garibaldi Provincial Park", doc.park?.name)
        assertTrue(doc.day_hikes.isNotEmpty())
        assertTrue(doc.day_hikes.first().route.line.size > 10)
    }

    /**
     * Every keyed route in the Nova Scotia build (the first with route_key,
     * basemap#325) is found again from its key alone, as a fave would be,
     * where its park has a box in regions.json.
     */
    @Test
    fun findsNovaScotiasRoutesByKey() = runBlocking {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        val repo = RoutesRepository(Http.builder().build(), "https://public.hikeratlas.com/routes/nova-scotia")
        val index = repo.index()
        val regions = repo.regions()
        // A route listed in two parks (or twice in one) may be found in either.
        val routes = mutableMapOf<String, MutableSet<String>>()
        for (id in index.parks.keys) {
            val doc = repo.park(id) ?: continue
            for (r in keyedRoutesAll(doc)) routes.getOrPut(r.first) { mutableSetOf() } += r.second
        }
        assertTrue(routes.size > 50)
        val found = findKeyedHikes(routes.keys, regions, index, { repo.park(it) }).found
        // A park regions.json doesn't list has no box to look in (as on the
        // website, which has no page for it either): its routes can't be found.
        val areas = regions.countries.flatMap { c -> c.states.flatMap { it.areas } }
        val boxed = routes.filterValues { ids -> ids.any { id -> areas.any { it.covers(id.substringBefore('/')) } } }.keys
        assertTrue(boxed.size > 50)
        val missed = boxed - found.keys
        assertTrue("not found: $missed", missed.isEmpty())
        for ((k, h) in found) assertTrue("$k: ${h.id} isn't one of ${routes[k]}", h.id in routes.getValue(k))
    }

    private fun keyedRoutesAll(doc: ParkDoc): List<Pair<String, String>> {
        val p = doc.park?.id ?: return emptyList()
        return buildList {
            doc.day_hikes.forEachIndexed { i, h -> h.route.route_key?.let { add(it to "$p/day/$i") } }
            doc.stops.forEachIndexed { i, h -> h.route.route_key?.let { add(it to "$p/stop/$i") } }
            doc.base_camps.forEachIndexed { i, b ->
                b.approach.route_key?.let { add(it to "$p/base/$i") }
                b.hikes.forEachIndexed { j, h -> h.route.route_key?.let { add(it to "$p/base/$i/$j") } }
            }
            doc.point_to_points.forEachIndexed { i, t -> t.route.route_key?.let { add(it to "$p/multi/$i") } }
        }
    }
}
