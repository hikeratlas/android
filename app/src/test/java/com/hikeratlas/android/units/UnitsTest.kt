package com.hikeratlas.android.units

import com.hikeratlas.android.routes.meta
import com.hikeratlas.android.search.SearchApi
import com.hikeratlas.android.search.SearchItem
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Formatting in each unit, against what the website's app/units.ts gives
 * for the same numbers (run in node), JavaScript's rounding and all.
 */
class UnitsTest {
    private val metric = Units.METRIC
    private val imperial = Units(DistanceUnit.MI, ElevationUnit.FT)

    @After
    fun tearDown() {
        Units.current = Units.METRIC
    }

    @Test
    fun distances() {
        // km, digits, then the website's text in km and in mi.
        val cases = listOf(
            Triple(0.15, null, "0.1 km" to "0.1 mi"), // 0.1499…: toFixed rounds the double, not "0.15"
            Triple(1.45, null, "1.4 km" to "0.9 mi"),
            Triple(9.94, null, "9.9 km" to "6.2 mi"),
            Triple(9.96, null, "10.0 km" to "6.2 mi"), // under 10 before rounding, so a decimal
            Triple(12.4, null, "12 km" to "7.7 mi"),
            Triple(15.5, null, "16 km" to "9.6 mi"),
            Triple(16.09344, null, "16 km" to "10 mi"),
            Triple(0.2414, null, "0.2 km" to "0.1 mi"),
            Triple(16.7, 1, "16.7 km" to "10.4 mi"),
            Triple(160.9344, 1, "160.9 km" to "100.0 mi"),
            Triple(2.5, 0, "3 km" to "2 mi"),
        )
        for ((km, digits, want) in cases) {
            assertEquals("$km km, $digits digits", want.first, metric.distance(km, digits))
            assertEquals("$km km in mi, $digits digits", want.second, imperial.distance(km, digits))
        }
    }

    @Test
    fun elevations() {
        val cases = listOf(
            1150.0 to ("1,150 m" to "3,773 ft"),
            2.5 to ("3 m" to "8 ft"),
            -2.5 to ("-2 m" to "-8 ft"), // Math.round: halves up, not away from zero
            0.4 to ("0 m" to "1 ft"),
            1234.5 to ("1,235 m" to "4,050 ft"),
            350.52 to ("351 m" to "1,150 ft"),
            999.9 to ("1,000 m" to "3,281 ft"),
        )
        for ((m, want) in cases) {
            assertEquals("$m m", want.first, metric.elevation(m))
            assertEquals("$m m in ft", want.second, imperial.elevation(m))
        }
        assertEquals("1,150–3,281 ft", imperial.elevationRange(350.52, 999.9))
    }

    @Test
    fun areasAndPlaces() {
        assertEquals("1.1 km²", metric.area(1.1e6))
        assertEquals("0.4 mi²", imperial.area(1.1e6))
        assertEquals("99 km²", metric.area(99e6))
        assertEquals("38 mi²", imperial.area(99e6))
        // Where along a hike, as the website's sheet: "km 3.4", "mi 2.1".
        assertEquals("km 3.4", metric.at(3.4))
        assertEquals("mi 2.1", imperial.at(3.4))
    }

    @Test
    fun fromTheProfileRecord() {
        fun of(s: String) = Units.of(Json.parseToJsonElement(s))
        assertEquals(imperial, of("""{"distance":"mi","elevation":"ft"}"""))
        assertEquals(Units(DistanceUnit.MI, ElevationUnit.M), of("""{"distance":"mi"}"""))
        // Other values are ignored, as the website's unitsOf.
        assertEquals(metric, of("""{"distance":"furlong","elevation":3}"""))
        assertEquals(metric, Units.of(null))
        assertEquals(imperial, Units.of(imperial.toJson()))
    }

    @Test
    fun listsAndSearchFollowTheUnitsShown() {
        Units.current = imperial
        assertEquals("Day hike · 10.4 mi · ↑ 4,239 ft", meta("day", 16.7, 1292.0))
        val hike = SearchItem(kind = "hike", name = "X", osm_id = "1", lat = 0.0, lon = 0.0, km = 12.4, gain = 850.0, hike = "day")
        assertEquals("Day hike · 7.7 mi · ↑ 2,789 ft", SearchApi.describe(hike))
        val lake = SearchItem(kind = "lake", name = "Y", osm_id = "2", lat = 1.0, lon = 0.0, area = 1.1e6)
        assertEquals("Lake · 0.4 mi²", SearchApi.describe(lake))
        // 111 km north: to the nearest 10 km past 100, and 69 mi.
        assertEquals("69 mi away", SearchApi.away(lake, 0.0, 0.0))
        assertEquals("110 km away", SearchApi.away(lake, 0.0, 0.0, metric))
    }
}
