package com.hikeratlas.android.track

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.floor

/**
 * A track's numbers as the website records them (app/atproto/gpx.ts
 * `stats`), so a track reads the same in the app, in its posted record and
 * on the website. [loss] is the app's own (the website has no descent):
 * the same hysteresis, downhill.
 */
data class GpxStats(
    /** The earliest point's time, as JavaScript's toISOString; null without times. */
    val startedAt: String?,
    /** Seconds from the earliest point to the latest (pauses and nights included); null without times. */
    val duration: Long?,
    /** Metres, along each segment. */
    val distance: Long,
    /** Metres climbed, from GPS altitudes; null when no point has one. */
    val gain: Long?,
    val points: Int,
    val loss: Long? = null,
    /** Steps counted while it was recorded (the app's <ha:steps>); null where they weren't. */
    val steps: Long? = null,
)

/**
 * A port of the website's GPX `stats`, line for line where it matters:
 * each <trkseg> measured on its own (the gap between two isn't walked);
 * distance by haversine (R = 6371008.8 m) between consecutive points,
 * rounded to the metre; climb from <ele> with 5 m hysteresis; times from
 * the earliest and latest point. StrictMath, as V8's Math is fdlibm too, so
 * the sums come out the same to the last bit.
 */
object TrackMeasure {
    private const val R = 6371008.8
    /** Climbs under this many metres are GPS noise (the website's GAIN_STEP). */
    const val GAIN_STEP = 5.0

    /** A track point as the website reads it from GPX: time in ms since the epoch. */
    data class Point(val lon: Double, val lat: Double, val ele: Double?, val time: Long?)

    fun metres(a: Point, b: Point): Double {
        val r = Math.PI / 180
        val s1 = StrictMath.sin(((b.lat - a.lat) * r) / 2)
        val s2 = StrictMath.sin(((b.lon - a.lon) * r) / 2)
        val h = s1 * s1 + StrictMath.cos(a.lat * r) * StrictMath.cos(b.lat * r) * s2 * s2
        return 2 * R * StrictMath.asin(StrictMath.sqrt(minOf(1.0, h)))
    }

    /**
     * The numbers so far, a point at a time: what the recording bar shows
     * while recording, and the same sums, in the same order, as [stats]
     * makes of the finished track.
     */
    class Running {
        var distance = 0.0
            private set
        private var gain = 0.0
        private var loss = 0.0
        private var anyEle = false
        private var base: Double? = null
        private var peak: Double? = null
        private var last: Point? = null
        private var seg: Int? = null
        private var first: Long? = null
        private var latest: Long? = null
        var points = 0
            private set

        /** Adds [p], in segment [segment]: a new segment isn't joined to the last. */
        fun add(p: Point, segment: Int) {
            if (segment != seg) {
                seg = segment
                last = null
                base = null
                peak = null
            }
            last?.let { distance += metres(it, p) }
            last = p
            points++
            p.ele?.let { e ->
                anyEle = true
                // Hysteresis: a climb counts once it's GAIN_STEP above the last low point.
                val b = base
                if (b == null || e < b) base = e
                else if (e - b >= GAIN_STEP) { gain += e - b; base = e }
                // And downhill, for the app's descent: GAIN_STEP below the last high point.
                val k = peak
                if (k == null || e > k) peak = e
                else if (k - e >= GAIN_STEP) { loss += k - e; peak = e }
            }
            p.time?.let { t ->
                if (first.let { it == null || t < it }) first = t
                if (latest.let { it == null || t > it }) latest = t
            }
        }

        val hasElevation get() = anyEle

        fun stats(): GpxStats {
            val f = first
            val l = latest
            return GpxStats(
                startedAt = f?.let(::iso),
                duration = if (f != null && l != null) jsRound((l - f) / 1000.0) else null,
                distance = jsRound(distance),
                gain = if (anyEle) jsRound(gain) else null,
                points = points,
                loss = if (anyEle) jsRound(loss) else null,
            )
        }
    }

    /** The website's numbers for segments of points (empty segments are skipped, as it does). */
    fun stats(segments: List<List<Point>>): GpxStats {
        val run = Running()
        segments.filter { it.isNotEmpty() }.forEachIndexed { i, s -> for (p in s) run.add(p, i) }
        return run.stats()
    }

    /** The website's numbers for a GPX file, and its steps where the app counted them. */
    fun ofGpx(text: String): GpxStats {
        val root = document(text)
        return stats(segments(root)).copy(steps = steps(root))
    }

    /**
     * The first <trk>'s <ha:steps> (in the app's namespace, in the track's
     * own extensions): a whole number, 0 or more, or none.
     */
    private fun steps(root: Element): Long? {
        val trk = byName(root, "trk").firstOrNull() ?: return null
        val ext = children(trk).firstOrNull { (it.localName ?: it.nodeName) == "extensions" } ?: return null
        val el = children(ext).firstOrNull { it.namespaceURI == Gpx.NS && it.localName == "steps" } ?: return null
        return el.textContent.trim().toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun children(el: Element): List<Element> =
        (0 until el.childNodes.length).mapNotNull { el.childNodes.item(it) as? Element }

    /**
     * A recorded track's points as [Gpx.write] writes them, so measuring
     * them is measuring the GPX the app posts: segments in order, <ele>
     * GPS's sea-level altitude else its ellipsoid one, times to the second.
     */
    fun of(points: List<Pair<Int, Fix>>): GpxStats =
        stats(points.groupBy({ it.first }, { point(it.second) }).toSortedMap().values.toList())

    /** A fix as the GPX the app writes has it. */
    fun point(f: Fix) = Point(f.lon, f.lat, f.mslAltitude ?: f.altitude, Math.floorDiv(f.t, 1000L) * 1000)

    /**
     * Each <trkseg>'s points, as the website's pointsOf reads them: any
     * namespace; <ele> and <time> the first of each inside the point; a
     * point without a usable lat and lon dropped.
     */
    fun parse(text: String): List<List<Point>> = segments(document(text))

    private fun document(text: String): Element {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // A GPX file needs no DTDs or outside entities: refusing them keeps a
            // file from reaching out. Android's parser has no such feature (it
            // never fetches them), and refuses to be asked.
            try {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: javax.xml.parsers.ParserConfigurationException) {
            }
        }
        val doc = try {
            f.newDocumentBuilder().parse(InputSource(StringReader(text)))
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a GPX file", e)
        }
        val root = doc.documentElement
        if (root == null || (root.localName ?: root.nodeName) != "gpx") throw IllegalArgumentException("Not a GPX file")
        return root
    }

    private fun segments(root: Element): List<List<Point>> {
        return byName(root, "trkseg").map { seg ->
            byName(seg, "trkpt").mapNotNull { p ->
                val ele = byName(p, "ele").firstOrNull()?.textContent
                val time = byName(p, "time").firstOrNull()?.textContent
                val lon = jsNumber(p.getAttribute("lon"))
                val lat = jsNumber(p.getAttribute("lat"))
                if (!lon.isFinite() || !lat.isFinite()) return@mapNotNull null
                Point(
                    lon, lat,
                    ele?.takeIf { it.isNotBlank() }?.let(::jsNumber)?.takeIf { it.isFinite() },
                    time?.let(::jsDateParse),
                )
            }
        }.filter { it.isNotEmpty() }
    }

    private fun byName(el: Element, name: String): List<Element> {
        val nodes = el.getElementsByTagNameNS("*", name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    // JavaScript's toISOString: milliseconds, UTC.
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    fun iso(ms: Long): String = ISO.format(Instant.ofEpochMilli(ms))

    /** JavaScript's Math.round: halves go up. */
    fun jsRound(x: Double): Long = floor(x + 0.5).toLong()

    private val DECIMAL = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")
    private val HEX = Regex("""0[xX][0-9a-fA-F]+""")

    /** JavaScript's Number(s) for the strings a GPX file has: blank is 0, junk is NaN. */
    internal fun jsNumber(s: String): Double {
        val t = s.trim()
        return when {
            t.isEmpty() -> 0.0
            DECIMAL.matches(t) -> t.toDouble()
            HEX.matches(t) -> t.substring(2).toLong(16).toDouble()
            t == "Infinity" || t == "+Infinity" -> Double.POSITIVE_INFINITY
            t == "-Infinity" -> Double.NEGATIVE_INFINITY
            else -> Double.NaN
        }
    }

    private val DATE = Regex("""(\d{4})-(\d\d)-(\d\d)(?:[T ](\d\d):(\d\d)(?::(\d\d)(?:\.(\d+))?)?(Z|[+-]\d\d:?\d\d)?)?""")

    /**
     * JavaScript's Date.parse for the ISO 8601 times GPX files have: a date
     * alone is UTC midnight, a time without a zone is local time, and
     * anything else (padding included, as V8) is no time. V8 also reads
     * older formats ("Jun 1 2026"), which GPX files don't use.
     */
    internal fun jsDateParse(s: String): Long? {
        val m = DATE.matchEntire(s) ?: return null
        val g = m.groupValues
        return try {
            val date = java.time.LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
            if (g[4].isEmpty()) return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val nanos = g[7].padEnd(3, '0').take(3).toInt() * 1_000_000
            val local = LocalDateTime.of(date, java.time.LocalTime.of(g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt(), nanos))
            val zone = g[8]
            val at = when {
                zone.isEmpty() -> local.atZone(ZoneId.systemDefault()).toInstant()
                zone == "Z" -> local.toInstant(ZoneOffset.UTC)
                else -> local.toInstant(ZoneOffset.of(if (zone.length == 5) zone.substring(0, 3) + ":" + zone.substring(3) else zone))
            }
            at.toEpochMilli()
        } catch (e: java.time.DateTimeException) {
            null
        }
    }
}
