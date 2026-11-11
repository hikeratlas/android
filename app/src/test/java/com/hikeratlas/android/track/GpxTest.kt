package com.hikeratlas.android.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class GpxTest {
    private val gpxNs = "http://www.topografix.com/GPX/1/1"

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(xml.byteInputStream()).documentElement

    private fun Element.all(ns: String, name: String) = getElementsByTagNameNS(ns, name).let { l -> (0 until l.length).map { l.item(it) as Element } }
    private fun Element.text(ns: String, name: String) = all(ns, name).firstOrNull()?.textContent

    private val points = listOf(
        0 to Fix(1_763_000_000_000, 47.5, -121.9, 4.5f, altitude = 812.5, mslAltitude = 830.25, verticalAccuracy = 6f, speed = 1.25f),
        0 to Fix(1_763_000_010_000, 47.5001, -121.9, 3f, altitude = 815.0), // no sea-level altitude (Android < 14)
        2 to Fix(1_763_000_600_000, 0.00005, -0.00003, 5f), // no altitude at all; near 0,0
    )

    @Test
    fun aTrackIsGpxWithASegmentPerStretch() {
        val gpx = parse(Gpx.write("Tusk & <Lake> \"loop\"", 1_762_999_990_000, points))
        assertEquals("gpx", gpx.localName)
        assertEquals(gpxNs, gpx.namespaceURI)
        assertEquals("1.1", gpx.getAttribute("version"))
        // Escaped, and read back as written.
        assertEquals("Tusk & <Lake> \"loop\"", gpx.all(gpxNs, "trk").single().text(gpxNs, "name"))
        assertEquals("2025-11-13T02:13:10Z", gpx.all(gpxNs, "metadata").single().text(gpxNs, "time"))

        val segs = gpx.all(gpxNs, "trkseg")
        assertEquals(listOf(2, 1), segs.map { it.all(gpxNs, "trkpt").size })
        val (a, b) = segs[0].all(gpxNs, "trkpt")
        assertEquals("47.5", a.getAttribute("lat"))
        assertEquals("-121.9", a.getAttribute("lon"))
        assertEquals("2025-11-13T02:13:20Z", a.text(gpxNs, "time"))
        // <ele>: above sea level where Android gave it, else GPS's ellipsoid altitude.
        assertEquals("830.25", a.text(gpxNs, "ele"))
        assertEquals("815", b.text(gpxNs, "ele"))
        // Everything recorded is kept.
        assertEquals("812.5", a.text(Gpx.NS, "ellipsoid_ele"))
        assertEquals("830.25", a.text(Gpx.NS, "msl_ele"))
        assertEquals("4.5", a.text(Gpx.NS, "hacc"))
        assertEquals("6", a.text(Gpx.NS, "vacc"))
        assertEquals("1.25", a.text(Gpx.NS, "speed"))
        assertEquals(null, b.text(Gpx.NS, "msl_ele"))

        val c = segs[1].all(gpxNs, "trkpt").single()
        assertEquals(null, c.text(gpxNs, "ele"))
        // Plain decimals, not 5.0E-5, without trailing zeros.
        assertEquals("0.00005", c.getAttribute("lat"))
        assertEquals("-0.00003", c.getAttribute("lon"))
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("Raging River State Forest - 14 Nov.gpx", Gpx.fileName("Raging River State Forest · 14 Nov"))
        assertEquals("a b.gpx", Gpx.fileName("a/b:\n?b".replace("b:\n?b", " b")))
        assertEquals("Track.gpx", Gpx.fileName("///"))
        assertTrue(Gpx.fileName("x".repeat(200)).length <= 84)
    }
}
