package com.hikeratlas.android.search

import com.hikeratlas.android.offline.OfflineAreas
import com.hikeratlas.android.offline.OfflineStore
import com.hikeratlas.android.routes.Hike
import com.hikeratlas.android.routes.Park
import com.hikeratlas.android.routes.ParkDoc
import com.hikeratlas.android.routes.Regions
import com.hikeratlas.android.routes.Route
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSearchTest {
    private fun hike(dest: String, from: String, lng: Double) = Hike(
        from = from, dest = dest, kind = "out_and_back",
        route = Route(km = 18.0, gain = 900.0, line = listOf(listOf(lng, 49.9), listOf(lng + 0.05, 49.95))),
    )

    private val garibaldi = ParkDoc(
        park = Park("2226817", "Garibaldi Provincial Park"),
        day_hikes = listOf(hike("Garibaldi Lake", "Rubble Creek", -123.1), hike("Élfin Lakes", "Diamond Head", -123.0)),
    )
    private val raging = ParkDoc(
        park = Park("11", "Raging River State Forest"),
        day_hikes = listOf(hike("Upward Mobility", "Lower Lot", -121.9), hike("Raging River Lake", "Lower Lot", -121.8)),
    )
    private val regions = Regions(
        listOf(
            Regions.Country(
                "Canada", states = listOf(Regions.State("British Columbia", areas = listOf(Regions.Area("2226817", "Garibaldi Provincial Park", bbox = listOf(-123.3, 49.7, -122.4, 50.2))))),
            ),
            Regions.Country(
                "United States", states = listOf(Regions.State("Washington", areas = listOf(Regions.Area("11", "Raging River State Forest", bbox = listOf(-122.0, 47.4, -121.8, 47.6))))),
            ),
        ),
    )

    private val search = OfflineSearch(
        regions = { regions },
        saved = {
            listOf(
                OfflineAreas.Target(OfflineStore.PARK, "2226817", "Garibaldi Provincial Park"),
                // Just one hike of Raging River.
                OfflineAreas.Target(OfflineStore.HIKE, "11/day/0", "Upward Mobility"),
            )
        },
        park = { mapOf("2226817" to garibaldi, "11" to raging)[it] },
    )

    @Test
    fun findsADownloadedParkAndItsHikes() = runBlocking {
        val found = search.search("garibaldi", null, null)
        assertEquals(listOf("Garibaldi Lake from Rubble Creek", "Garibaldi Provincial Park"), found.map { it.name }.sorted())
        val park = found.single { it.kind == "park" }
        assertEquals("2226817", park.osm_id)
        assertTrue(park.hasBox)
        assertEquals("British Columbia", park.state)
        val lake = found.single { it.kind == "hike" }
        assertEquals("2226817/day/0", lake.osm_id)
        assertEquals("Garibaldi PP", lake.park)
        assertEquals("Day hike · 18 km · ↑ 900 m · Garibaldi PP, British Columbia", SearchApi.describe(lake))
    }

    @Test
    fun matchesWordStartsIgnoringAccentsAndCase() = runBlocking {
        assertEquals(listOf("Élfin Lakes from Diamond Head"), search.search("elf lak", null, null).map { it.name })
        assertEquals(emptyList<String>(), search.search("lfin", null, null).map { it.name })
    }

    @Test
    fun aDownloadedHikeIsFoundButNotTheRestOfItsPark() = runBlocking {
        assertEquals(listOf("Upward Mobility from Lower Lot"), search.search("upward", null, null).map { it.name })
        assertEquals(emptyList<String>(), search.search("raging", null, null).map { it.name })
    }

    @Test
    fun nearerFirst() = runBlocking {
        val lakes = search.search("lake", 49.9, -123.0).map { it.name }
        assertEquals(listOf("Élfin Lakes from Diamond Head", "Garibaldi Lake from Rubble Creek"), lakes)
    }
}
