package com.hikeratlas.android.routes

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** Finding the hikes route keys name, as the website's findRoutes, in test park documents. */
class KeyedHikesTest {
    // Real keys from route-keys' vectors: a base camp's walk in near
    // Mont-Tremblant, and two Juneau routes whose keys share a cell part.
    private val walkIn = "1K4RjYPAwNBJBHCDBBGCBGBOAUA.kbwpj6tSIEZ"
    private val nugget = "1E57E06gbDEBBBICDGF.F55FRcODEp_"
    private val mendenhall = "1E57E06gbDEBBBICDGF.tn3dBnAuR_Q"

    private fun route(key: String?) = Route(km = 3.0, gain = 100.0, route_key = key)
    private fun hike(dest: String, key: String?, kind: String = "out-and-back") = Hike(from = "Lot", dest = dest, kind = kind, route = route(key))

    private val quebec = ParkDoc(
        park = Park("100", "Parc du Lac"),
        day_hikes = listOf(hike("Lac Vert", null)),
        base_camps = listOf(BaseCamp("Camp Rouge", "Road's end", approach = route(walkIn), hikes = listOf(hike("Mont Noir", "1KAAAAAAbAA.AAAAAAAAAAA")))),
    )
    private val juneau = ParkDoc(
        park = Park("200", "Juneau Trails"),
        // An older build's route: no key, so nothing to match.
        day_hikes = listOf(hike("Mendenhall Lake", null), hike("Nugget Falls", nugget)),
        stops = listOf(hike("Viewpoint", "1E57E06gbAA.BBBBBBBBBBB")),
        point_to_points = listOf(PointToPoint("A", "B", "point-to-point", route = route("1E57E06gwAA.CCCCCCCCCCC"))),
    )
    private val nyc = ParkDoc(park = Park("300", "City Park"), day_hikes = listOf(hike("Pond", "1KhByrFApAFADAAAEAGCB.BMzh7oWtefh", "loop")))

    private val regions = Regions(
        listOf(
            Regions.Country(
                "Somewhere", states = listOf(
                    Regions.State(
                        "A", areas = listOf(
                            // The walk in's centroid is just outside the box: the margin takes it.
                            Regions.Area("100", "Parc du Lac", bbox = listOf(-75.39, 47.205, -75.37, 47.22)),
                            // One park under two OSM ids, one document.
                            Regions.Area("200", "Juneau Trails", bbox = listOf(-134.6, 58.4, -134.5, 58.45), ids = listOf("200", "201")),
                            Regions.Area("300", "City Park", bbox = listOf(-74.1, 40.7, -74.0, 40.8)),
                            // In regions but not in this build's bundle.
                            Regions.Area("400", "Gone Park", bbox = listOf(-134.6, 58.4, -134.5, 58.45)),
                        ),
                    ),
                ),
            ),
        ),
    )
    private val index = BundleIndex("b.bin", mapOf("100" to listOf(0L, 10L), "200" to listOf(10L, 10L), "201" to listOf(10L, 10L), "300" to listOf(20L, 10L)))
    private val docs = mapOf("100" to quebec, "200" to juneau, "201" to juneau, "300" to nyc)
    private val loaded = Collections.synchronizedList(mutableListOf<String>())
    private val park: suspend (String) -> ParkDoc? = { id -> loaded += id; docs[id] }

    @Test
    fun everyKeyedRoute() {
        assertEquals(
            mapOf(walkIn to "100/base/0", "1KAAAAAAbAA.AAAAAAAAAAA" to "100/base/0/0"),
            keyedRoutes(quebec),
        )
        assertEquals(
            mapOf(nugget to "200/day/1", "1E57E06gbAA.BBBBBBBBBBB" to "200/stop/0", "1E57E06gwAA.CCCCCCCCCCC" to "200/multi/0"),
            keyedRoutes(juneau),
        )
    }

    @Test
    fun keysMatchedToHikes() = runBlocking {
        val r = findKeyedHikes(listOf(walkIn, nugget, mendenhall, "not a key"), regions, index, park)
        val found = r.found
        assertEquals(KeyedHike("100", "Parc du Lac", "100/base/0", "Camp Rouge"), found[walkIn])
        assertEquals(KeyedHike("200", "Juneau Trails", "200/day/1", "Nugget Falls"), found[nugget])
        // Its park was loaded (its centroid is Nugget Falls' cell), but no
        // route there has the key exactly: no longer curated.
        assertEquals(setOf(walkIn, nugget), found.keys)
        // Every park around it was searched: Mendenhall is surely gone. "not a key" can't be placed.
        assertEquals(setOf("not a key"), r.unsure)
        // Only the parks around the keys, and a shared document once.
        assertEquals(listOf("100", "200"), loaded.sorted())
    }

    @Test
    fun anUnmatchedKey() = runBlocking {
        val r = findKeyedHikes(listOf(mendenhall), regions, index, park)
        assertTrue(r.found.isEmpty())
        assertTrue(r.unsure.isEmpty())
        assertEquals(listOf("200"), loaded)
    }

    @Test
    fun aParkThatWontLoadLeavesTheOthers() = runBlocking {
        val r = findKeyedHikes(listOf(walkIn, nugget), regions, index, { id -> if (id == "100") throw java.io.IOException("offline") else docs[id] })
        assertEquals(setOf(nugget), r.found.keys)
        // Not "no longer": its park couldn't be searched.
        assertEquals(setOf(walkIn), r.unsure)
    }

    @Test
    fun noKeysLoadsNothing() = runBlocking {
        assertTrue(findKeyedHikes(emptyList(), regions, index, park).found.isEmpty())
        assertTrue(loaded.isEmpty())
    }

    // An offline area's copy of Juneau from a build before route keys.
    private val juneauBeforeKeys = ParkDoc(
        park = juneau.park,
        day_hikes = juneau.day_hikes.map { it.copy(route = it.route.copy(route_key = null)) },
        stops = juneau.stops.map { it.copy(route = it.route.copy(route_key = null)) },
        point_to_points = juneau.point_to_points.map { it.copy(route = it.route.copy(route_key = null)) },
    )

    @Test
    fun aParkDownloadedBeforeKeysIsLookedUpOnline() = runBlocking {
        val online = mutableListOf<String>()
        val r = findKeyedHikes(
            listOf(nugget, mendenhall), regions, index,
            park = { juneauBeforeKeys },
            onlinePark = { id -> online += id; docs[id] },
        )
        assertEquals(KeyedHike("200", "Juneau Trails", "200/day/1", "Nugget Falls"), r.found[nugget])
        // Searched in the build's keyed document: Mendenhall is surely gone.
        assertTrue(r.unsure.isEmpty())
        assertEquals(listOf("200"), online)
    }

    @Test
    fun aParkDownloadedBeforeKeysWithNoNetworkIsUnsure() = runBlocking {
        val r = findKeyedHikes(
            listOf(nugget, mendenhall), regions, index,
            park = { juneauBeforeKeys },
            onlinePark = { throw java.io.IOException("no network") },
        )
        assertTrue(r.found.isEmpty())
        assertEquals(setOf(nugget, mendenhall), r.unsure)
    }

    @Test
    fun aBuildWithoutKeysIsUnsureToo() = runBlocking {
        // north-america until its next routes build: no document has keys.
        val r = findKeyedHikes(listOf(nugget), regions, index, park = { juneauBeforeKeys }, onlinePark = { juneauBeforeKeys })
        assertEquals(setOf(nugget), r.unsure)
    }

    @Test
    fun parksPastTheCapAreUnsure() = runBlocking {
        // 61 parks around one point, none with the key: the 61st isn't searched.
        val areas = (0..MAX_KEY_PARKS).map { Regions.Area("p$it", "P$it", bbox = listOf(-134.6, 58.4, -134.5, 58.45)) }
        val many = Regions(listOf(Regions.Country("C", states = listOf(Regions.State("S", areas = areas)))))
        val idx = BundleIndex("b.bin", areas.mapIndexed { i, a -> a.id to listOf(i.toLong(), 1L) }.toMap())
        val r = findKeyedHikes(listOf(mendenhall), many, idx, { id -> ParkDoc(Park(id, id), day_hikes = listOf(hike("X", "x.$id"))) })
        assertEquals(setOf(mendenhall), r.unsure)
    }
}
