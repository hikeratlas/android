package com.hikeratlas.android.routes

import com.uber.h3core.H3Core
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding a route key's centroid, against hikeratlas/route-keys'
 * vectors/v1.json (copied from route-keys 3c9cf90, 13 November 2026): each
 * key's centroid cell is the vector's, and the place it gives is in it.
 */
class RouteKeysTest {
    private val vectors: List<JsonObject> =
        Json.parseToJsonElement(javaClass.getResource("/route-keys/v1.json")!!.readText()).jsonObject["vectors"]!!.jsonArray.map { it.jsonObject }
    private val h3 = H3Core.newInstance()

    @Test
    fun h3LoadsOnTheJvm() {
        // The desktop library from h3-java's jar: tests don't skip decoding.
        assertTrue(RouteKeys.available)
    }

    @Test
    fun centroidCellsAreTheVectors() {
        assertTrue(vectors.size > 20)
        for (v in vectors) {
            val key = v["key"]!!.jsonPrimitive.content
            val cell = RouteKeys.centroidCell(key)
            assertEquals(v["name"]!!.jsonPrimitive.content, v["centroid"]!!.jsonPrimitive.content, cell?.let { h3.h3ToString(it) })
        }
    }

    @Test
    fun centroidsAreInTheirCellNearTheLine() {
        for (v in vectors) {
            val name = v["name"]!!.jsonPrimitive.content
            val c = RouteKeys.centroid(v["key"]!!.jsonPrimitive.content)
            assertNotNull(name, c)
            val (lon, lat) = c!![0] to c[1]
            assertEquals(name, v["centroid"]!!.jsonPrimitive.content, h3.latLngToCellAddress(lat, lon, 10))
            // And it's where the route is: within its line's box (a cell is ~70 m across).
            val line = v["line"]!!.jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.double } }
            val pad = 0.002
            assertTrue(name, lon in line.minOf { it[0] } - pad..line.maxOf { it[0] } + pad)
            assertTrue(name, lat in line.minOf { it[1] } - pad..line.maxOf { it[1] } + pad)
        }
    }

    @Test
    fun notKeys() {
        val good = "1K4RjYPAwNBJBHCDBBGCBGBOAUA.kbwpj6tSIEZ"
        assertNotNull(RouteKeys.centroid(good))
        for (bad in listOf(
            "", "nope", good.substringBefore('.'),
            // The hash must be 11 characters.
            good.dropLast(1), "$good" + "A",
            // Version 2.
            "2" + good.drop(1),
            // Shape x.
            good.substring(0, 8) + "x" + good.substring(9),
            // Not base64url.
            good.replaceFirst('R', '!'),
            // The cell's low 5 bits must be zero.
            good.substring(0, 7) + "B" + good.substring(8),
            // Too short.
            "1K4RjYPAw.kbwpj6tSIEZ",
            // A route's ids, not its key.
            "15670732/day/0",
        )) {
            assertNull(bad, RouteKeys.centroid(bad))
        }
    }
}
