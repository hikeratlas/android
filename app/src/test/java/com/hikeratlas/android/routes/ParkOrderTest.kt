package com.hikeratlas.android.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The website's sortParks cases (website#168), with the same parks. */
class ParkOrderTest {
    private fun park(name: String, hikes: Int, rank: Long? = null) = Regions.Area(name, name, hikes = hikes, rank = rank)
    private fun names(xs: List<Regions.Area>) = xs.map { it.name }

    private val parks = listOf(
        park("La Verendrye Provincial Park", 140, 0),
        park("Algonquin Provincial Park", 90, 50_000),
        park("Bruce Peninsula National Park", 30, 20_000),
        park("Arrowhead Provincial Park", 30),
    )

    @Test
    fun byFameThenHikes() {
        assertEquals(
            listOf("Algonquin Provincial Park", "Bruce Peninsula National Park", "La Verendrye Provincial Park", "Arrowhead Provincial Park"),
            names(sortParks(parks, ParkOrder.POPULAR)),
        )
    }

    @Test
    fun byHikesThenName() {
        assertEquals(
            listOf("La Verendrye Provincial Park", "Algonquin Provincial Park", "Arrowhead Provincial Park", "Bruce Peninsula National Park"),
            names(sortParks(parks, ParkOrder.HIKES)),
        )
    }

    // By the name the card shows ("Algonquin"), case and accents aside.
    @Test
    fun byTheNameTheCardShows() {
        assertEquals(
            listOf("Algonquin Provincial Park", "Bruce Peninsula National Park", "Écureuil Park", "Zed Park"),
            names(sortParks(listOf(park("Zed Park", 1), park("Écureuil Park", 1), park("Algonquin Provincial Park", 1), park("Bruce Peninsula National Park", 1)), ParkOrder.NAME)),
        )
    }

    @Test
    fun leavesTheListAsItWas() {
        val before = names(parks)
        sortParks(parks, ParkOrder.NAME)
        assertEquals(before, names(parks))
    }

    @Test
    fun ranksOnlyWhenSomeParkHasOne() {
        assertTrue(hasRanks(parks))
        assertFalse(hasRanks(listOf(park("A", 1), park("B", 2, 0))))
    }
}
