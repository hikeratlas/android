package com.hikeratlas.android.routes

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class LinkTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    // Garibaldi Provincial Park's routes, from the published bundle (lines trimmed).
    private val doc = RoutesRepository.JSON.decodeFromString(ParkDoc.serializer(), fixture("garibaldi-park.json"))

    private val bc = Regions.State(
        "British Columbia", "british-columbia", listOf(-139.0, 48.0, -114.0, 60.0),
        listOf(Regions.Area("2226817", "Garibaldi Provincial Park", "garibaldi-provincial-park", listOf(-123.3, 49.7, -122.4, 50.2))),
    )
    private val regions = Regions(listOf(Regions.Country("Canada", "canada", listOf(-141.0, 41.0, -52.0, 70.0), listOf(bc))))

    private fun resolve(path: String) = runBlocking {
        Link.resolve(Link.parts(path)!!, regions) { id -> if (id == "2226817") doc else null }
    }

    @Test
    fun partsAreTheWebsites() {
        assertEquals(listOf("canada", "alberta"), Link.parts("/hikes/Canada/alberta/"))
        assertEquals(emptyList<String>(), Link.parts("/hikes"))
        assertEquals(listOf("a+b c"), Link.parts("/hikes/a+b%20c"))
        assertNull(Link.parts("/map"))
        assertNull(Link.parts("/hikesx"))
    }

    @Test
    fun resolvesRegionsParksAndHikes() {
        assertEquals(Link.Region(listOf(-141.0, 41.0, -52.0, 70.0)), resolve("/hikes/canada"))
        assertEquals(Link.Region(bc.bbox!!), resolve("/hikes/canada/british-columbia"))
        assertEquals(
            Link.Park("2226817", "Garibaldi Provincial Park", listOf(-123.3, 49.7, -122.4, 50.2)),
            resolve("/hikes/canada/british-columbia/garibaldi-provincial-park"),
        )
        assertEquals(
            Link.Hike("2226817", "2226817/day/5", "The Black Tusk from Rubble Creek Trailhead"),
            resolve("/hikes/canada/british-columbia/garibaldi-provincial-park/the-black-tusk"),
        )
        // A day hike from a base camp: <camp>/<hike>.
        val fromCamp = hikeSlugs(doc)["2226817/base/1/0"]!!
        assertEquals("2226817/base/1/0", (resolve("/hikes/canada/british-columbia/garibaldi-provincial-park/$fromCamp") as Link.Hike).id)
    }

    @Test
    fun unknownPartsFallBackAsTheWebsiteDoes() {
        assertNull(resolve("/hikes/narnia"))
        assertNull(resolve("/hikes"))
        // An unknown state or park: the level above.
        assertEquals(Link.Region(regions.countries[0].bbox!!), resolve("/hikes/canada/narnia"))
        assertEquals(Link.Region(bc.bbox!!), resolve("/hikes/canada/british-columbia/narnia"))
        // An unknown hike: the park; a renamed one: its close match.
        assertEquals("2226817", (resolve("/hikes/canada/british-columbia/garibaldi-provincial-park/nowhere") as Link.Park).id)
        assertEquals("2226817/day/5", (resolve("/hikes/canada/british-columbia/garibaldi-provincial-park/the-black-tusk-2") as Link.Hike).id)
    }

    @Test
    fun findsCloseSlugs() {
        val slugs = mapOf("a" to "black-tusk", "b" to "black-tusk-from-rubble-creek", "c" to "camp/black-tusk")
        assertEquals("a", Link.findHike(slugs, "black-tusk"))
        assertEquals("a", Link.findHike(slugs, "black-tusk-north"))
        assertEquals("b", Link.findHike(mapOf("b" to "black-tusk-from-rubble-creek"), "black-tusk"))
        assertEquals("c", Link.findHike(slugs, "camp/black-tusk"))
        assertNull(Link.findHike(slugs, "camp/other"))
    }

    /** The link from the report: a plain day hike in Washington. Opt in with HIKERATLAS_LIVE=1. */
    @Test
    fun resolvesALiveLink() = runBlocking {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        val repo = RoutesRepository(OkHttpClient())
        val link = Link.resolve(
            Link.parts("/hikes/united-states/washington/raging-river-state-forest/upward-mobility")!!,
            repo.regions(),
        ) { repo.park(it) }
        val hike = link as Link.Hike
        assertEquals("upward-mobility", hikeSlugs(repo.park(hike.park)!!)[hike.id])
    }
}
