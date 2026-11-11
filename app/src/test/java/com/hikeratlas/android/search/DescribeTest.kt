package com.hikeratlas.android.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DescribeTest {
    @Test
    fun secondLinesMatchTheWebsite() {
        // Real search results, each with what the website's describe() made of it.
        val text = javaClass.getResource("/fixtures/website-expected.json")!!.readText()
        val cases = Json.parseToJsonElement(text).jsonObject["described"]!!.jsonArray
        for (c in cases) {
            val item = SearchApi.JSON.decodeFromJsonElement(SearchItem.serializer(), c.jsonArray[0])
            assertEquals(item.name, c.jsonArray[1].jsonPrimitive.content, SearchApi.describe(item))
        }
    }

    @Test
    fun farAwayResultsSayHowFar() {
        val item = SearchItem(kind = "peak", name = "X", osm_id = "1", lat = 50.0, lon = -120.0)
        assertEquals("", SearchApi.away(item, 50.1, -120.0))
        assertEquals("71 km away", SearchApi.away(item, 50.0, -119.0))
        assertEquals("", SearchApi.away(item, null, null))
    }

    @Test
    fun lookalikesGetTheirCountry() {
        val a = SearchItem(kind = "lake", name = "Lake Agnes", osm_id = "1", lat = 51.4, lon = -116.2, country = "Canada", state = "Alberta")
        val b = a.copy(osm_id = "2", country = "United States")
        val lines = SearchApi.secondLines(listOf(a, b), null, null)
        assertEquals(listOf("Lake · Alberta · Canada", "Lake · Alberta · United States"), lines)
    }
}
