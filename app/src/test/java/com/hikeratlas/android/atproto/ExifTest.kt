package com.hikeratlas.android.atproto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/**
 * Reading EXIF as the website does. The fixtures (test/resources/photos,
 * made by make-fixtures.py) are compared with website-exif.json: what the
 * website's own readExif (app/atproto/exif.ts) gave for each, run in Node
 * with TZ=America/Toronto, segment (Orientation patched) and all.
 */
class ExifTest {
    private val toronto = ZoneId.of("America/Toronto")
    private fun bytes(name: String) = javaClass.getResource("/photos/$name")!!.readBytes()
    private val website = Json.parseToJsonElement(javaClass.getResource("/photos/website-exif.json")!!.readText()).jsonObject

    private fun hex(b: ByteArray?) = b?.joinToString("") { "%02x".format(it) }

    private fun assertAsTheWebsite(name: String) {
        val want = website[name]!!.jsonObject
        val got = Exif.read(bytes(name), toronto)
        fun str(k: String) = want[k].takeIf { it !is JsonNull }?.jsonPrimitive?.content
        fun num(k: String) = want[k].takeIf { it !is JsonNull }?.jsonPrimitive?.double
        assertEquals("$name lat", num("lat"), got.lat)
        assertEquals("$name lon", num("lon"), got.lon)
        assertEquals("$name takenAt", str("takenAt"), got.takenAt)
        assertEquals("$name offsetKnown", want["offsetKnown"]!!.jsonPrimitive.boolean, got.offsetKnown)
        assertEquals("$name segment", str("segment"), hex(got.segment))
    }

    @Test
    fun everyFixtureReadsAsTheWebsiteReadsIt() {
        assertEquals(8, website.size)
        for (name in website.keys) assertAsTheWebsite(name)
    }

    @Test
    fun gpsAndATimeWithItsOffset() {
        val e = Exif.read(bytes("gps-offset.jpg"), toronto)
        assertEquals(47.4875, e.lat!!, 1e-9)
        assertEquals(-121.7234, e.lon!!, 1e-9)
        // 10:15:30 at -07:00.
        assertEquals("2026-07-02T17:15:30.000Z", e.takenAt)
        assertEquals(true, e.offsetKnown)
        // Rotated as it was; patched to upright in the segment kept.
        assertEquals(6, e.orientation)
        assertEquals(1, Exif.read(Exif.withExif(bytes("no-exif.jpg"), e.segment!!), toronto).orientation)
    }

    @Test
    fun aTimeWithoutOffsetTakesGpsTimeElseThePhonesZone() {
        // Big-endian, south and east; local 08:00 without offset, GPS time given: GPS time.
        val be = Exif.read(bytes("gps-time-be.jpg"), toronto)
        assertEquals(-33.8568, be.lat!!, 1e-9)
        assertEquals(151.2153, be.lon!!, 1e-9)
        assertEquals("2026-01-14T21:00:12.500Z", be.takenAt)
        assertEquals(true, be.offsetKnown)
        // No GPS time: local time where the phone is. (02:30 on 8 March 2026
        // isn't a time in Toronto, the clocks going forward: 03:30 EDT, as
        // JavaScript takes it.)
        val local = Exif.read(bytes("local.jpg"), toronto)
        assertEquals("2026-03-08T07:30:00.000Z", local.takenAt)
        assertEquals(false, local.offsetKnown)
        assertEquals("2026-03-08T02:30:00.000Z", Exif.read(bytes("local.jpg"), ZoneId.of("UTC")).takenAt)
        assertNull(local.lat)
    }

    @Test
    fun noExifOrBrokenExif() {
        val none = Exif.read(bytes("no-exif.jpg"), toronto)
        assertNull(none.segment)
        assertNull(none.takenAt)
        // A segment that can't be read is kept as it is, with nothing from it.
        val broken = Exif.read(bytes("broken.jpg"), toronto)
        assertEquals(null, broken.lat)
        assertEquals(1, broken.orientation)
        assertEquals(hex(bytes("broken.jpg").copyOfRange(2, 2 + broken.segment!!.size)), hex(broken.segment))
        // Not a JPEG.
        assertNull(Exif.read(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), toronto).segment)
        assertNull(Exif.read(ByteArray(0), toronto).segment)
    }

    @Test
    fun theOriginalIsLeftAsItWas() {
        val b = bytes("gps-offset.jpg")
        Exif.read(b, toronto)
        assertArrayEquals(bytes("gps-offset.jpg"), b)
    }

    @Test
    fun exifTakesThePlaceOfJfif() {
        val segment = Exif.read(bytes("gps-offset.jpg"), toronto).segment!!
        // A JPEG as an encoder writes it: SOI, APP0 (JFIF), then the rest.
        val jfif = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 6, 'J'.code.toByte(), 'F'.code.toByte(), 0, 0)
        val rest = byteArrayOf(0xff.toByte(), 0xdb.toByte(), 1, 2, 3)
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte()) + segment + rest, Exif.withExif(jfif + rest, segment))
        // Without JFIF: put in after SOI.
        val bare = byteArrayOf(0xff.toByte(), 0xd8.toByte()) + rest
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte()) + segment + rest, Exif.withExif(bare, segment))
    }
}
