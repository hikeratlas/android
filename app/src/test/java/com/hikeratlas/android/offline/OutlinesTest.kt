package com.hikeratlas.android.offline

import com.hikeratlas.android.routes.Hike
import com.hikeratlas.android.routes.Park
import com.hikeratlas.android.routes.ParkDoc
import com.hikeratlas.android.routes.Regions
import com.hikeratlas.android.routes.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlinesTest {
    private val regions = Regions(
        listOf(
            Regions.Country(
                "Canada",
                states = listOf(
                    Regions.State(
                        "British Columbia",
                        areas = listOf(Regions.Area("2017389", "Pacific Rim NPR", bbox = listOf(-125.9, 48.5, -124.6, 49.2), ids = listOf("2017389", "2100172"))),
                    ),
                ),
            ),
        ),
    )
    private val doc = ParkDoc(
        park = Park("11", "Raging River State Forest"),
        day_hikes = listOf(Hike("Lower Lot", "Upward Mobility", "out_and_back", route = Route(10.0, gain = 500.0, line = listOf(listOf(-121.9, 47.5), listOf(-121.85, 47.52))))),
    )

    @Test
    fun aParksOutlineIsItsBoxByAnyOfItsIds() {
        val box = listOf(-125.9, 48.5, -124.6, 49.2)
        assertEquals(box, Outlines.of(OfflineAreas.Target(OfflineStore.PARK, "2100172", "West Coast Trail"), regions, null))
        assertNull(Outlines.of(OfflineAreas.Target(OfflineStore.PARK, "999", "Nowhere"), regions, null))
    }

    @Test
    fun aHikesOutlineIsItsBoxAKilometreOut() {
        val b = Outlines.of(OfflineAreas.Target(OfflineStore.HIKE, "11/day/0", "Upward Mobility"), regions, doc)!!
        // ~0.009° of latitude a kilometre.
        assertEquals(47.5 - 0.009, b[1], 0.001)
        assertEquals(47.52 + 0.009, b[3], 0.001)
        assertTrue(b[0] < -121.9 && b[2] > -121.85)
        assertNull(Outlines.of(OfflineAreas.Target(OfflineStore.HIKE, "11/day/5", "Gone"), regions, doc))
    }

    @Test
    fun theOverviewHasNoOutline() {
        assertNull(Outlines.of(OfflineAreas.OVERVIEW_TARGET, regions, doc))
    }
}
