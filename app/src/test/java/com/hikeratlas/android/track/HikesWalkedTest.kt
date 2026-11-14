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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/** Which curated hikes a recorded track walked. */
class HikesWalkedTest {
    // About 11 m a step north, 7.5 m a step east at this latitude.
    private fun line(from: Int, to: Int, lon: Double = -121.8) = (from..to).map { listOf(lon, 47.45 + it * 0.0001) }

    private fun hike(dest: String, line: List<LngLat>, key: String?) =
        Hike("Trailhead", dest, "out-and-back", route = Route(km = line.size * 0.011, gain = 0.0, line = line, route_key = key))

    private val doc = ParkDoc(
        Park("p1", "Raging River State Forest"),
        day_hikes = listOf(
            hike("Upper Lake", line(0, 200), "keyA"),
            // Shares its first 100 steps with Upper Lake, then goes its own way.
            hike("Lower Lake", line(0, 100) + (1..100).map { listOf(-121.8 + it * 0.0001, 47.46) }, "keyB"),
            // Over the hill, 1 km east: not walked.
            hike("Far Peak", line(0, 200, lon = -121.787), "keyC"),
            // Walked, but with no key from the build: can't be posted about.
            hike("Old Trail", line(0, 200), null),
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

    // The track: up Upper Lake's line with GPS wander of up to 20 m, a pause, and back down.
    private val track: List<List<LngLat>> = listOf(
        (0..400).map { i -> listOf(-121.8 + 0.00025 * sin(i * 0.7), 47.45 + i * 0.00005) },
        (400 downTo 0).map { i -> listOf(-121.8 - 0.0002 * sin(i * 0.3), 47.45 + i * 0.00005) },
    )

    @Test
    fun theHikesItFollowedBestFirst() = runBlocking {
        val got = hikesWalked(track, regions, index, { if (it == "p1") doc else null })
        assertEquals(listOf("keyA", "keyB"), got.map { it.key })
        assertEquals(1.0, got[0].share, 0.01)
        // Its first half, and where it turns off.
        assertTrue("${got[1].share}", got[1].share in 0.5..0.7)
        assertEquals("Upper Lake", got[0].hike.title)
        assertEquals("p1/day/0", got[0].hike.id)
        assertEquals("Raging River State Forest", got[0].hike.parkName)
    }

    @Test
    fun aParkThatWontLoadIsPassedOver() = runBlocking {
        assertTrue(hikesWalked(track, regions, index, { error("offline") }).isEmpty())
    }

    @Test
    fun aShortHikeInsideALongerOneComesSecond() = runBlocking {
        // Both followed whole: the longer, which the track is more likely to be, first.
        val short = hike("Viewpoint", line(0, 50), "keyS")
        val d = doc.copy(day_hikes = listOf(short) + doc.day_hikes)
        assertEquals(listOf("keyA", "keyS", "keyB"), hikesWalked(track, regions, index, { d }).map { it.key })
    }

    @Test
    fun followShareCountsTheLineNearTheTrack() {
        assertEquals(1.0, followShare(track, line(0, 200)), 0.0)
        assertEquals(0.0, followShare(track, line(0, 200, lon = -121.787)), 0.0)
        // Half of it beyond where the track turned back.
        assertEquals(0.5, followShare(track, line(0, 400)), 0.03)
    }
}
