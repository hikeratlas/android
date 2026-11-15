package com.hikeratlas.android.track

import com.hikeratlas.android.routes.BundleIndex
import com.hikeratlas.android.routes.Hike
import com.hikeratlas.android.routes.LngLat
import com.hikeratlas.android.routes.Park
import com.hikeratlas.android.routes.ParkDoc
import com.hikeratlas.android.routes.Regions
import com.hikeratlas.android.routes.Route
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The curated hikes near a recorded track: all within NEAR_METRES, closest first. */
class HikesNearTest {
    // At 47.45°N: 0.0001° of latitude is 11.05 m; 0.0001° of longitude, 7.53 m.
    private fun north(lon: Double, from: Int = 0, to: Int = 200) = (from..to).map { listOf(lon, 47.45 + it * 0.0001) }
    /** [metres] east of the track's line (-121.8). */
    private fun east(metres: Double) = -121.8 + metres / 75_300.0

    private fun hike(dest: String, line: List<LngLat>, key: String?) =
        Hike("Trailhead", dest, "out-and-back", route = Route(km = 2.0, gain = 0.0, line = line, route_key = key))

    private val doc = ParkDoc(
        Park("p1", "Raging River State Forest"),
        day_hikes = listOf(
            hike("Upper Lake", north(-121.8), "keyA"),
            hike("Beaver Pond", north(east(600.0)), "keyC"),
            hike("Far Peak", north(east(1_300.0)), "keyD"),
            // Also on the track: ties go by name.
            hike("Alder Loop", north(-121.8, 50, 120), "keyB"),
            hike("Ridge", north(east(250.0)), "keyE"),
            // Near, but the build gave it no key: posts can't name it.
            hike("Old Trail", north(-121.8), null),
        ),
    )
    private val regions = Regions(
        listOf(
            Regions.Country(
                "United States", "us", null,
                listOf(Regions.State("Washington", "wa", null, listOf(Regions.Area("p1", "Raging River State Forest", "rr", listOf(-122.0, 47.4, -121.7, 47.6))))),
            ),
        ),
    )
    private val index = BundleIndex("b", mapOf("p1" to listOf(0L, 10L)))
    // Up Upper Lake's line and back, with a pause.
    private val track: List<List<LngLat>> = listOf(north(-121.8, 0, 200), north(-121.8, 0, 200).reversed())

    @Test
    fun everyHikeWithinAKilometreClosestFirst() = runBlocking {
        assertEquals(1_000.0, NEAR_METRES, 0.0)
        val got = hikesNear(track, regions, index, { if (it == "p1") doc else null })
        // On the track (by name), then 250 m, then 600 m; Far Peak, 1.3 km away, isn't offered.
        assertEquals(listOf("keyB", "keyA", "keyE", "keyC"), got.map { it.key })
        assertEquals(0.0, got[0].metres, 1.0)
        assertEquals(250.0, got[2].metres, 5.0)
        assertEquals(600.0, got[3].metres, 5.0)
        assertEquals("Alder Loop", got[0].hike.title)
        assertEquals("p1/day/3", got[0].hike.id)
        assertEquals("Raging River State Forest", got[0].hike.parkName)
    }

    @Test
    fun aParkThatWontLoadIsPassedOver() = runBlocking {
        assertTrue(hikesNear(track, regions, index, { error("offline") }).isEmpty())
    }

    @Test
    fun closestApproachWithinTheRadiusOnly() {
        assertEquals(600.0, closestApproach(track, north(east(600.0)))!!, 5.0)
        assertEquals(990.0, closestApproach(track, north(east(990.0)))!!, 5.0)
        assertNull(closestApproach(track, north(east(1_010.0))))
        // Near one end only: the closest point counts.
        assertEquals(0.0, closestApproach(track, listOf(listOf(-121.8, 47.45), listOf(east(5_000.0), 47.45)))!!, 1.0)
    }
}
