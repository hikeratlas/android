package com.hikeratlas.android.routes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    // Garibaldi Provincial Park's routes, from the published bundle (lines trimmed).
    private val doc = RoutesRepository.JSON.decodeFromString(ParkDoc.serializer(), fixture("garibaldi-park.json"))

    @Test
    fun slugsMatchTheWebsite() {
        // website-expected.json: the website's own hikeEntries() run on the same park.
        val expected = Json.parseToJsonElement(fixture("website-expected.json")).jsonObject["slugs"]!!.jsonObject
            .mapValues { it.value.jsonPrimitive.content }
        assertEquals(expected, hikeSlugs(doc))
    }

    @Test
    fun hikeUrlsUseTheParkPath() {
        val index = BundleIndex("b.bin", emptyMap(), mapOf("2226817" to "canada/british-columbia/garibaldi-provincial-park"))
        assertEquals(
            "https://www.hikeratlas.com/hikes/canada/british-columbia/garibaldi-provincial-park/the-black-tusk",
            hikeUrl(index, doc, "2226817/day/5"),
        )
        assertNull(hikeUrl(index.copy(paths = emptyMap()), doc, "2226817/day/5"))
    }

    @Test
    fun looksUpEachKindOfRoute() {
        val tusk = lookup(doc, "2226817/day/5")!!
        assertEquals("The Black Tusk from Rubble Creek Trailhead", tusk.title)
        assertEquals("Day hike · out and back", tusk.label)
        assertEquals(23.6, tusk.route.km, 0.0)
        assertEquals("Taylor Meadows", tusk.route.stopovers.single().name)
        assertTrue(tusk.why.any { it.first == "appeal" })

        val camp = lookup(doc, "2226817/base/1")!!
        assertEquals("Garibaldi Lake", camp.title)
        assertEquals("Base camp · walk in from Rubble Creek Trailhead", camp.label)
        assertEquals(1, camp.baseIndex)

        val fromCamp = lookup(doc, "2226817/base/1/0")!!
        assertEquals("The Black Tusk from Garibaldi Lake", fromCamp.title)
        assertTrue(fromCamp.label.startsWith("Day hike from base camp"))

        assertTrue(lookup(doc, "2226817/multi/0")!!.label.startsWith("Multi-day"))
        assertNull(lookup(doc, "2226817/day/999"))
        assertNull(lookup(doc, "2226817/nope/0"))
    }

    @Test
    fun parkListsAreSectionedInTheWebsitesOrder() {
        val items = itemsFromPark(doc)
        assertEquals(listOf("Day hikes", "Base camps", "Multi-day"), items.mapNotNull { it.section }.distinct())
        assertEquals(doc.day_hikes.size, items.count { it.section == "Day hikes" })
        val first = items.first()
        assertEquals("2226817/day/0", first.id)
        assertEquals("Dreadnought Peak from Watersprite Lake parking", first.title)
        assertEquals("Day hike · 16.7 km · ↑ 1,292 m", first.meta)
        val camp = items.first { it.section == "Base camps" }
        assertEquals("Base camp: Taylor Meadows from Rubble Creek Trailhead", camp.title)
    }

    @Test
    fun routeLayerFeaturesBecomeOneItemEach() {
        val props = listOf(
            mapOf("id" to "2226817/day/10", "park" to "Garibaldi", "park_id" to "2226817", "type" to "day", "title" to "B", "km" to 5.0, "gain" to 100.0),
            mapOf("id" to "2226817/day/2", "park" to "Garibaldi", "park_id" to "2226817", "type" to "day", "title" to "A", "km" to 3.04, "gain" to 1234.4),
            // the same route again, from the next tile
            mapOf("id" to "2226817/day/2", "park" to "Garibaldi", "park_id" to "2226817", "type" to "day", "title" to "A", "km" to 3.04, "gain" to 1234.4),
            mapOf("id" to "2226817/base/0", "park" to "Garibaldi", "park_id" to "2226817", "type" to "approach", "title" to "Camp", "km" to 7.0, "gain" to 900.0),
            mapOf("id" to "2226817/base/0/1", "park" to "Garibaldi", "park_id" to "2226817", "type" to "from-camp", "title" to "Peak", "km" to 7.0, "gain" to 900.0),
        )
        val items = itemsFromProperties(props)
        assertEquals(listOf("2226817/day/2", "2226817/day/10", "2226817/base/0", "2226817/base/0/1"), items.map { it.id })
        assertEquals("Day hike · 3.0 km · ↑ 1,234 m", items[0].meta)
        assertEquals("Base camp: Camp", items[2].title)
        assertEquals(3, itemsFromProperties(props, fromCamp = false).size)
    }

    @Test
    fun slugifyFoldsAccents() {
        assertEquals("lac-a-l-eau-claire", slugify("Lac à l'Eau Claire"))
    }
}
