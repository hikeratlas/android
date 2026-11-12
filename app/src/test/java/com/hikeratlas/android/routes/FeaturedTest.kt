package com.hikeratlas.android.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeaturedTest {
    private val regions = Regions(
        listOf(
            Regions.Country(
                "Canada", "canada",
                states = listOf(
                    Regions.State(
                        "British Columbia", "british-columbia",
                        areas = listOf(
                            Regions.Area("1", "Garibaldi Provincial Park", "garibaldi-provincial-park", bbox = listOf(-123.3, 49.7, -122.4, 50.2)),
                            Regions.Area("2", "Yoho National Park", "yoho-national-park", bbox = listOf(-116.8, 51.2, -116.2, 51.6)),
                            // Not a showcase park.
                            Regions.Area("3", "Cypress Provincial Park", "cypress-provincial-park", bbox = listOf(-123.3, 49.3, -123.1, 49.5)),
                        ),
                    ),
                ),
            ),
            Regions.Country(
                "United States", "united-states",
                states = listOf(Regions.State("Maine", "maine", areas = listOf(Regions.Area("4", "Acadia National Park", "acadia-national-park", bbox = listOf(-68.5, 44.2, -68.1, 44.5))))),
            ),
        ),
    )

    @Test
    fun theShowcaseParksTheBuildHasAlphabetically() {
        assertEquals(listOf("Acadia", "Garibaldi", "Yoho"), Featured.parks(regions).map { it.short })
    }

    @Test
    fun nearestWithin500KmElseTheDays() {
        val parks = Featured.parks(regions)
        // Vancouver: Garibaldi, near.
        assertEquals(1 to true, Featured.pick(parks, -123.1 to 49.3, day = 0))
        // Toronto: nothing within 500 km; the day's, wrapping.
        assertEquals(2 to false, Featured.pick(parks, -79.4 to 43.7, day = 5))
        assertEquals(0 to false, Featured.pick(parks, null, day = 3))
        // Asked for, wrapping either way.
        assertEquals(2 to false, Featured.pick(parks, -123.1 to 49.3, day = 0, showcase = -1))
        assertEquals(1 to false, Featured.pick(parks, null, day = 0, showcase = 4))
        assertNull(Featured.pick(emptyList(), null, 0))
    }

    private fun h(title: String, diff: Difficulty, score: Double) =
        ParkHike(title, title, "", Route(km = 1.0, gain = 1.0), diff, 1.0, false, "loop", false, false, emptyList(), 0, score)

    @Test
    fun theBestApproachableHikeAmongTheGoodOnes() {
        val best = h("Big", Difficulty.Hard, 10.0)
        assertEquals("Mid", Featured.hike(listOf(best, h("Mid", Difficulty.Moderate, 8.0), h("Easy", Difficulty.Easy, 7.5)))!!.title)
        // None good enough: the best.
        assertEquals("Big", Featured.hike(listOf(best, h("Easy", Difficulty.Easy, 6.0)))!!.title)
        assertNull(Featured.hike(emptyList()))
    }
}
