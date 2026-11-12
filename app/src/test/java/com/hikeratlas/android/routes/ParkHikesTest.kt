package com.hikeratlas.android.routes

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkHikesTest {
    private fun route(km: Double, gain: Double, sac: Int? = null, maxEle: Double = 0.0) = Route(km = km, gain = gain, max_sac = sac, max_ele = maxEle)

    @Test
    fun difficultyBySacClimbAndLength() {
        assertEquals(Difficulty.Easy, difficulty(route(5.0, 200.0)))
        assertEquals(Difficulty.Moderate, difficulty(route(10.0, 200.0)))
        assertEquals(Difficulty.Moderate, difficulty(route(5.0, 400.0)))
        assertEquals(Difficulty.Hard, difficulty(route(18.0, 0.0)))
        assertEquals(Difficulty.Hard, difficulty(route(5.0, 1000.0)))
        assertEquals(Difficulty.Hard, difficulty(route(3.0, 50.0, sac = 3)))
        assertEquals(Difficulty.Scramble, difficulty(route(3.0, 50.0, sac = 4)))
    }

    @Test
    fun walkingTimeToTheHalfHour() {
        // 12 km / 4 + 900 m / 600 = 4.5 h.
        assertEquals(4.5, hours(route(12.0, 900.0)), 0.0)
        assertEquals("~4.5 h", timeLabel(4.5))
        assertEquals("~3 h", timeLabel(3.0))
        // Under an hour: in minutes, to five, from the route.
        val r = route(2.0, 60.0)
        assertEquals("about 35 min", timeLabel(hours(r), r))
        assertEquals(0.25, hours(route(0.1, 0.0)), 0.0)
        assertEquals(TimeBand.Short, timeBand(2.0))
        assertEquals(TimeBand.Half, timeBand(5.0))
        assertEquals(TimeBand.Full, timeBand(5.5))
    }

    @Test
    fun shapes() {
        assertEquals("loop", shapeWords("loop"))
        assertEquals("out & back", shapeWords("out-and-back"))
        assertEquals("one way", shapeWords("one-way"))
        assertEquals("one way, Blue Grouse Gondola down", shapeWords("one-way", "Blue Grouse Gondola"))
    }

    @Test
    fun aOneWayHikeSaysSo() {
        val doc = Json { ignoreUnknownKeys = true }.decodeFromString(
            ParkDoc.serializer(),
            """{"park":{"id":"1","name":"Grouse Mountain"},"day_hikes":[{"from":"Grind trailhead","dest":"Grouse Mountain","kind":"one-way","return_by":"Blue Grouse Gondola","route":{"km":2.9,"gain":850}}]}""",
        )
        // Titled by its destination alone, the only route there: the label says where it starts.
        assertEquals("Grouse Mountain", lookup(doc, "1/day/0")!!.title)
        assertEquals("Day hike from Grind · one way, Blue Grouse Gondola down", lookup(doc, "1/day/0")!!.label)
        assertEquals("one way, Blue Grouse Gondola down", parkHikes(doc).single().shape)
    }

    @Test
    fun trailheads() {
        assertEquals("Helen Lake Trail", trailhead("Helen Lake Trail trailhead"))
        assertEquals("Fish Creek parking", trailhead("Fish Creek Parking Lot"))
        assertEquals("Fish Creek parking", trailhead("Fish Creek Parking"))
    }

    private val doc = Json { ignoreUnknownKeys = true }.decodeFromString(
        ParkDoc.serializer(),
        """{"park":{"id":"7","name":"P"},"day_hikes":[
          {"from":"A trailhead","dest":"Lake One","kind":"loop","dest_kind":"lake","score":5.0,"key":"k-lake",
           "route":{"km":4,"gain":80,"max_ele":900,"passes":[["lake",["Lake One","unnamed"]],["peak",["Hill"]]],
                    "along":[["lake","Lake One",1.5,[-123.0,49.0]],["bad"]]}},
          {"from":"B","dest":"Big Peak","kind":"out-and-back","dest_kind":"peak","score":9.0,"name":"Big Peak Trail",
           "route":{"km":20,"gain":1400,"max_ele":2400,"max_sac":3}}
        ]}""",
    )

    @Test
    fun parkHikesFromTheDoc() {
        val hs = parkHikes(doc) { key -> if (key == "k-lake") 3 else 0 }
        val (lake, peak) = hs
        assertEquals("7/day/0", lake.id)
        assertEquals("Lake One", lake.title)
        assertEquals("A", lake.from)
        assertTrue(lake.loop && lake.lake && !lake.summit)
        assertEquals(listOf("Lake One", "Hill"), lake.features)
        assertEquals(3, lake.reports)
        // By its id where it has no key.
        assertEquals(0, peak.reports)
        assertEquals("Big Peak Trail", peak.title)
        assertTrue(peak.summit)
        assertEquals(Difficulty.Hard, peak.diff)
        // Along: well-formed entries only.
        assertEquals(listOf(Along("lake", "Lake One", 1.5, listOf(-123.0, 49.0))), lake.route.alongs)
    }

    @Test
    fun filtersAndSorts() {
        val hs = parkHikes(doc) { if (it == "k-lake") 1 else 0 }
        assertEquals(listOf("Lake One"), hs.filter { matches(it, Filters(lakes = true)) }.map { it.title })
        assertEquals(listOf("Big Peak Trail"), hs.filter { matches(it, Filters(summits = true)) }.map { it.title })
        assertEquals(listOf("Lake One"), hs.filter { matches(it, Filters(time = TimeBand.Short)) }.map { it.title })
        assertEquals(listOf("Big Peak Trail"), hs.filter { matches(it, Filters(diffs = setOf(Difficulty.Hard))) }.map { it.title })
        assertEquals(listOf("Lake One"), hs.filter { matches(it, Filters(reports = true, loops = true)) }.map { it.title })
        assertFalse(Filters().any)
        assertEquals(listOf("Big Peak Trail", "Lake One"), sorted(hs, Sort.Best).map { it.title })
        assertEquals(listOf("Lake One", "Big Peak Trail"), sorted(hs, Sort.Shortest).map { it.title })
        assertEquals(listOf("Big Peak Trail", "Lake One"), sorted(hs, Sort.Highest).map { it.title })
    }

    @Test
    fun picksOnlyForBiggerParks() {
        fun h(i: Int, km: Double, gain: Double, score: Double) =
            ParkHike("7/day/$i", "H$i", "", route(km, gain), difficulty(route(km, gain)), 1.0, false, "loop", false, false, emptyList(), 0, score)
        val few = (0 until 7).map { h(it, 5.0, 100.0, it.toDouble()) }
        assertEquals(emptyList<Pair<String, ParkHike>>(), picks(few))
        val many = few + listOf(h(7, 12.0, 500.0, 1.0), h(8, 12.0, 500.0, 2.0), h(9, 20.0, 1200.0, 0.5))
        assertEquals(listOf("Easy" to "H6", "Half day" to "H8", "Big day" to "H9"), picks(many).map { it.first to it.second.title })
    }

    @Test
    fun smallParkSummaries() {
        fun h(km: Double, gain: Double, loop: Boolean) = route(km, gain).let {
            ParkHike("", "", "", it, difficulty(it), hours(it), loop, "", false, false, emptyList(), 0, 0.0)
        }
        assertEquals("Three short, nearly flat loops, each under an hour.", summary(List(3) { h(2.0, 20.0, true) }))
        assertEquals("One short, easy hike, under two hours.", summary(listOf(h(5.0, 200.0, false))))
        assertEquals("Two hikes, the longest about 4.5 hours.", summary(listOf(h(12.0, 900.0, false), h(3.0, 500.0, true))))
        assertEquals("", summary(emptyList()))
    }

    @Test
    fun featureAndWhyWords() {
        val r = Json { ignoreUnknownKeys = true }.decodeFromString(
            Route.serializer(),
            """{"km":10,"gain":700,"passes":[["lake",["A","B"]],["waterfall",["C"]],["peak",["D"]],["shop",["E"]]]}""",
        )
        assertEquals("Lakes, a waterfall and a summit", featureWords(r))
        val why = listOf("popularity" to 0.5, "feature_kinds" to 1.2, "loop" to 0.5, "lc_variety" to 0.9, "dest" to 3.0)
        assertEquals(listOf("Lakes, a waterfall and a summit", "Varied terrain", "A loop: no retracing your steps"), whyWords(why, r))
    }

    @Test
    fun alongIsNumberedInOrderWithoutUnnamedOrRepeats() {
        val r = Json { ignoreUnknownKeys = true }.decodeFromString(
            Route.serializer(),
            """{"km":10,"gain":700,"along":[["viewpoint","Barrier Viewpoint",5.9,[-123.08,49.94]],["lake","unnamed",6.0,[-123.0,49.9]],
               ["lake","Barrier Lake",6.1,[-123.07,49.95]],["lake","Barrier Lake",6.2,[-123.07,49.95]]],
               "passes":[["peak",["Ignored"]]]}""",
        )
        assertEquals(
            listOf(AlongFeature(1, "viewpoint", "Barrier Viewpoint", 5.9, listOf(-123.08, 49.94)), AlongFeature(2, "lake", "Barrier Lake", 6.1, listOf(-123.07, 49.95))),
            alongOf(r),
        )
        // Builds before along: from passes, without places.
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(Route.serializer(), """{"km":1,"gain":1,"passes":[["lake",["A","unnamed","B"]],["peak",["A"]]]}""")
        assertEquals(listOf(AlongFeature(1, "lake", "A"), AlongFeature(2, "lake", "B")), alongOf(old))
        assertEquals("Viewpoint", kindName("viewpoint"))
        assertEquals("hot spring", kindName("hot_spring"))
        assertEquals("A, B and C", listWords(listOf("A", "B", "C")))
    }

    @Test
    fun aRouteAsGpx() {
        val g = routeGpx("Lake & Peak", listOf(listOf(-123.1, 49.9), listOf(-123.05, 49.95)))
        assertTrue(g.contains("<name>Lake &amp; Peak</name>"))
        assertTrue(g.contains("""<trkpt lat="49.900000" lon="-123.100000"/>"""))
        assertTrue(g.contains("""<trkpt lat="49.950000" lon="-123.050000"/>"""))
    }

    @Test
    fun nearbyParksWithMoreHikes() {
        val regions = Regions(
            listOf(
                Regions.Country(
                    "Canada",
                    states = listOf(
                        Regions.State(
                            "BC",
                            areas = listOf(
                                Regions.Area("1", "Small", bbox = listOf(-123.0, 49.0, -122.9, 49.1), ids = listOf("1", "1b"), hikes = 2),
                                Regions.Area("2", "Near", bbox = listOf(-123.0, 49.2, -122.9, 49.3), hikes = 5),
                                Regions.Area("3", "Too few", bbox = listOf(-123.0, 49.1, -122.9, 49.2), hikes = 2),
                                Regions.Area("4", "Far", bbox = listOf(-120.0, 49.0, -119.9, 49.1), hikes = 40),
                                Regions.Area("5", "No box", hikes = 9),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val near = nearbyParks(regions, "1b")
        assertEquals(listOf("Near", "Far"), near.map { it.name })
        assertEquals(22, near[0].km)
        assertEquals(emptyList<NearbyPark>(), nearbyParks(regions, "999"))
    }
}
