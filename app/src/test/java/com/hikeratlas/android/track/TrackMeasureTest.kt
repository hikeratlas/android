package com.hikeratlas.android.track

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The port of the website's GPX `stats` (app/atproto/gpx.ts), on GPX files
 * shared with it: test/resources/gpx/expected.json is what the website's
 * own `stats` gave for each, run in Node (with @xmldom/xmldom as its
 * DOMParser). rawDistance and rawGain are the same function's sums before
 * rounding, so a different Earth radius or summing order shows, which the
 * rounded metres alone would hide.
 */
class TrackMeasureTest {
    private fun text(name: String) = javaClass.getResource("/gpx/$name")!!.readText()
    private val expected = Json.parseToJsonElement(text("expected.json")).jsonObject

    @Test
    fun theWebsitesNumbersForEachSharedFile() {
        assertEquals(6, expected.size)
        for ((file, e) in expected) {
            val want = e.jsonObject
            val got = TrackMeasure.ofGpx(text(file))
            val what = "$file: $got"
            assertEquals(what, want.str("startedAt"), got.startedAt)
            assertEquals(what, want.num("duration"), got.duration)
            assertEquals(what, want["distance"]!!.jsonPrimitive.long, got.distance)
            assertEquals(what, want.num("gain"), got.gain)
            assertEquals(what, want["points"]!!.jsonPrimitive.long, got.points.toLong())
            val segs = TrackMeasure.parse(text(file))
            val run = TrackMeasure.Running()
            segs.forEachIndexed { i, s -> s.forEach { run.add(it, i) } }
            assertEquals(what, want["rawDistance"]!!.jsonPrimitive.double, run.distance, 1e-9)
        }
    }

    @Test
    fun rawClimbAsTheWebsiteSumsIt() {
        // The climb before rounding: from the same <ele>s, in the same order.
        for ((file, e) in expected) {
            val raw = e.jsonObject["rawGain"]
            if (raw == null || raw is JsonNull) continue
            val segs = TrackMeasure.parse(text(file))
            var gain = 0.0
            for (s in segs) {
                var base: Double? = null
                for (p in s) {
                    val v = p.ele ?: continue
                    val b = base
                    if (b == null || v < b) base = v else if (v - b >= TrackMeasure.GAIN_STEP) { gain += v - b; base = v }
                }
            }
            assertEquals(file, raw.jsonPrimitive.double, gain, 1e-9)
            assertEquals(file, TrackMeasure.jsRound(gain), TrackMeasure.ofGpx(text(file)).gain)
        }
    }

    @Test
    fun aRecordedTracksFixesMeasureAsItsGpx() {
        // Times with milliseconds (GPX keeps whole seconds), MSL altitude
        // where Android gave one and the ellipsoid's where not, a pause.
        val points = buildList {
            var t = 1_783_000_000_456L
            for (i in 0 until 120) {
                val seg = if (i < 70) 0 else 1
                t += 4_321
                val up = if (seg == 0) i * 0.9 else (140 - i) * 0.7
                val msl = if (i % 7 == 3) null else 300.0 + up + (i % 3) * 1.7
                add(seg to Fix(t, 47.45 + i * 0.00011, -121.81 + i * 0.00007, 5f, altitude = 300.0 + up - 21.5, mslAltitude = msl))
            }
        }
        val gpx = Gpx.write("Test · 1 Jul", 1_783_000_000_000L, points)
        val fromGpx = TrackMeasure.ofGpx(gpx)
        assertEquals(fromGpx, TrackMeasure.of(points))
        // And while recording: the running numbers end where the finished track's start.
        val log = TrackLog()
        for ((seg, f) in points) log.restore(f, seg)
        assertEquals(fromGpx, log.stats())
        assertEquals(fromGpx.distance, TrackMeasure.jsRound(log.metres))
    }

    @Test
    fun noAltitudesNoClimb() {
        val points = (0 until 10).map { 0 to Fix(1_700_000_000_000L + it * 5_000, 49.0 + it * 0.0002, -123.0, 5f) }
        val s = TrackMeasure.of(points)
        assertNull(s.gain)
        assertNull(s.loss)
        assertEquals(TrackMeasure.ofGpx(Gpx.write("x", 0, points)), s)
    }

    @Test
    fun climbCountsOnceFiveMetresAboveTheLastLow() {
        fun seg(vararg e: Double) = listOf(e.mapIndexed { i, v -> TrackMeasure.Point(0.0, i * 0.0001, v, null) })
        assertEquals(5L, TrackMeasure.stats(seg(100.0, 105.0)).gain)
        assertEquals(0L, TrackMeasure.stats(seg(100.0, 104.9)).gain)
        // Down to a new low, then up: from the low.
        assertEquals(8L, TrackMeasure.stats(seg(100.0, 103.0, 95.0, 103.0)).gain)
        assertEquals(8L, TrackMeasure.stats(seg(100.0, 103.0, 95.0, 103.0)).loss)
    }

    @Test
    fun timesAsJavaScriptReadsThem() {
        assertEquals(1_780_308_000_123L, TrackMeasure.jsDateParse("2026-06-01T10:00:00.1239Z"))
        assertEquals(1_780_300_800_000L, TrackMeasure.jsDateParse("2026-06-01T10:00:00+0200"))
        assertEquals(1_780_300_800_000L, TrackMeasure.jsDateParse("2026-06-01T10:00:00+02:00"))
        assertEquals(1_780_308_000_000L, TrackMeasure.jsDateParse("2026-06-01 10:00:00Z"))
        assertEquals(1_780_308_000_000L, TrackMeasure.jsDateParse("2026-06-01T10:00Z"))
        assertEquals(1_780_272_000_000L, TrackMeasure.jsDateParse("2026-06-01"))
        assertNull(TrackMeasure.jsDateParse(" 2026-06-01T10:00:00Z "))
        assertNull(TrackMeasure.jsDateParse("not a time"))
        assertEquals("2026-06-01T10:00:00.123Z", TrackMeasure.iso(1_780_308_000_123L))
    }

    @Test
    fun numbersAsJavaScriptReadsThem() {
        assertEquals(12.0, TrackMeasure.jsNumber(" 12 "), 0.0)
        assertEquals(110.0, TrackMeasure.jsNumber("1.1e2"), 0.0)
        assertEquals(5.0, TrackMeasure.jsNumber("5."), 0.0)
        assertEquals(0.5, TrackMeasure.jsNumber(".5"), 0.0)
        assertEquals(0.0, TrackMeasure.jsNumber(""), 0.0)
        assertEquals(Double.NaN, TrackMeasure.jsNumber("12f"), 0.0)
        assertEquals(Double.NaN, TrackMeasure.jsNumber("abc"), 0.0)
        // Halves round up, as Math.round.
        assertEquals(2L, TrackMeasure.jsRound(1.5))
        assertEquals(-1L, TrackMeasure.jsRound(-1.5))
    }

    private fun JsonObject.str(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.num(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long
}
