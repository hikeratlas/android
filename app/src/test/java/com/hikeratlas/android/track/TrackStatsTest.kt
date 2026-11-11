package com.hikeratlas.android.track

import com.hikeratlas.android.routes.Regions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackStatsTest {
    // ~11.1 m per 0.0001° of latitude.
    private fun fix(t: Long, lat: Double) = Fix(t, lat, -123.0, 4f)

    @Test
    fun movingTimeLeavesOutStandingStill() {
        val track = TrackStore.Track(1, started = 0, ended = 1_000_000, name = null, pausedMs = 100_000, pausedAt = null)
        val points = listOf(
            0 to fix(10_000, 49.0),
            0 to fix(20_000, 49.0001), // 11 m in 10 s: walking
            0 to fix(320_000, 49.0002), // 11 m in 5 min: standing
            1 to fix(500_000, 49.001),
            1 to fix(530_000, 49.0012), // 22 m in 30 s: walking
        )
        val s = statsOf(track, points, null)
        assertEquals(10_000 + 30_000L, s.movingMs)
        assertEquals(900_000, s.elapsedMs) // ended - started - paused
        assertEquals(44.5, s.metres, 0.5) // not across the pause
        assertNull(s.gain)
    }

    @Test
    fun segmentsKeepThePauses() {
        val points = listOf(0 to fix(0, 49.0), 0 to fix(1, 49.1), 2 to fix(2, 49.2))
        assertEquals(listOf(2, 1), segmentsOf(points).map { it.size })
    }

    @Test
    fun aTrackIsNamedForTheSmallestParkItStartsIn() {
        val regions = Regions(
            listOf(
                Regions.Country(
                    "Canada", "canada", null,
                    listOf(
                        Regions.State(
                            "BC", "bc", null,
                            listOf(
                                Regions.Area("1", "Big Forest", "big", listOf(-124.0, 49.0, -122.0, 51.0)),
                                Regions.Area("2", "Little Park", "little", listOf(-123.1, 49.9, -122.9, 50.1)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals("Little Park", parkAt(regions, -123.0, 50.0))
        assertEquals("Big Forest", parkAt(regions, -123.5, 50.0))
        assertNull(parkAt(regions, -100.0, 50.0))
    }
}
