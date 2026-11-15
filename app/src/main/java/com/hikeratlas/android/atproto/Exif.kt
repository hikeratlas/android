package com.hikeratlas.android.atproto

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A photo's EXIF (JPEG only), as the website's app/atproto/exif.ts reads
 * it, so the same file gives the same record from either: where and when it
 * was taken (defs#photo's `at` and `takenAt`), and its EXIF block, kept when
 * a photo has to be re-encoded to fit the lexicon's 2 MB. Photos of places
 * are much of what's worth sharing, and that needs where they are.
 */
object Exif {
    class Info(
        /** The APP1 segment (marker included), Orientation set to 1: a re-encoded photo is drawn upright already. */
        val segment: ByteArray?,
        val lat: Double?,
        val lon: Double?,
        /**
         * When it was taken, as JavaScript's toISOString writes it;
         * [offsetKnown] false when the camera gave only local time, read in
         * the phone's time zone.
         */
        val takenAt: String?,
        val offsetKnown: Boolean,
        /**
         * The Orientation tag as it was (1 when absent), for drawing the
         * pixels upright. The website has the browser do that
         * (imageOrientation: 'from-image'); Android's decoder doesn't.
         */
        val orientation: Int = 1,
    )

    private val NONE = Info(null, null, null, null, false)

    /** [zone]: where a time without an offset was, as the website reads it in the browser's. */
    fun read(bytes: ByteArray, zone: ZoneId = ZoneId.systemDefault()): Info {
        if (bytes.size < 2 || u8(bytes, 0) != 0xff || u8(bytes, 1) != 0xd8) return NONE
        var p = 2
        while (p + 4 <= bytes.size && u8(bytes, p) == 0xff) {
            val marker = u8(bytes, p + 1)
            if (marker == 0xda || marker == 0xd9) break // image data: no more headers
            val len = (u8(bytes, p + 2) shl 8) or u8(bytes, p + 3)
            if (marker == 0xe1 && latin1(bytes, p + 4, p + 10) == "Exif\u0000\u0000") {
                // As JavaScript's slice: clamped to what's there.
                val segment = bytes.copyOfRange(p, minOf(bytes.size, p + 2 + len))
                // Kept apart, so a photo whose EXIF breaks further on is still drawn upright.
                val orientation = intArrayOf(1)
                return try {
                    parse(segment, 10, zone, orientation)
                } catch (e: RuntimeException) {
                    // As the website's: Orientation may already be patched.
                    Info(segment, null, null, null, false, orientation[0])
                }
            }
            p += 2 + len
        }
        return NONE
    }

    /** Bytes past the end, as a DataView throws there. */
    private class OutOfRange : RuntimeException()

    // The TIFF structure at seg[base...]: GPS position, time, and (patched in
    // place) Orientation. Line for line the website's parse; offsets are
    // Longs, as a u32 in the file can be anything, and reading outside the
    // segment throws, as a DataView does.
    private fun parse(seg: ByteArray, base: Int, zone: ZoneId, orientation: IntArray): Info {
        fun byte(o: Long): Int {
            if (o < 0 || o >= seg.size) throw OutOfRange()
            return u8(seg, o.toInt())
        }
        val le = byte(base.toLong()) == 0x49 && byte(base + 1L) == 0x49
        fun u16(o: Long): Int {
            val a = byte(base + o)
            val b = byte(base + o + 1)
            return if (le) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(o: Long): Long {
            val x = LongArray(4) { byte(base + o + it).toLong() }
            return if (le) x[0] or (x[1] shl 8) or (x[2] shl 16) or (x[3] shl 24)
            else (x[0] shl 24) or (x[1] shl 16) or (x[2] shl 8) or x[3]
        }
        // As subarray: what's there of n bytes; up to the first NUL.
        fun ascii(o: Long, n: Long): String {
            val from = (base + o).coerceIn(0L, seg.size.toLong())
            val to = (base + o + n).coerceIn(from, seg.size.toLong())
            return latin1(seg, from.toInt(), to.toInt()).substringBefore('\u0000')
        }
        // Each entry of the IFD at off: tag -> (type, count, value offset, or
        // where the value is if it fits in 4 bytes).
        fun ifd(off: Long): Map<Int, Entry> {
            val out = HashMap<Int, Entry>()
            val n = u16(off)
            for (i in 0 until n) {
                val e = off + 2 + i * 12L
                val type = u16(e + 2)
                val count = u32(e + 4)
                val size = (SIZES.getOrNull(type) ?: 1) * count
                out[u16(e)] = Entry(type, count, if (size <= 4) e + 8 else u32(e + 8))
            }
            return out
        }
        fun str(d: Map<Int, Entry>, tag: Int): String? = d[tag]?.let { ascii(it.at, it.count) }
        fun rationals(d: Map<Int, Entry>, tag: Int): List<Double>? {
            val t = d[tag] ?: return null
            if (t.type != 5) return null
            val out = ArrayList<Double>()
            var i = 0L
            while (i < t.count) {
                val at = t.at + i * 8
                out += u32(at).toDouble() / (u32(at + 4).takeIf { it != 0L } ?: 1L).toDouble()
                i++
            }
            return out
        }

        val ifd0 = ifd(u32(4))
        ifd0[0x0112]?.let { o ->
            orientation[0] = u16(o.at)
            // Patched in place: what's put back is drawn upright.
            val at = (base + o.at).toInt()
            if (le) { seg[at] = 1; seg[at + 1] = 0 } else { seg[at] = 0; seg[at + 1] = 1 }
        }

        var lat: Double? = null
        var lon: Double? = null
        var gpsTime: String? = null
        ifd0[0x8825]?.let { gpsPtr ->
            val g = ifd(u32(gpsPtr.at))
            fun dms(x: List<Double>?) = if (x != null && x.size >= 3) x[0] + x[1] / 60 + x[2] / 3600 else null
            val la = dms(rationals(g, 2))
            val lo = dms(rationals(g, 4))
            if (la != null && lo != null && (la != 0.0 || lo != 0.0)) {
                lat = if (str(g, 1) == "S") -la else la
                lon = if (str(g, 3) == "W") -lo else lo
            }
            val d = str(g, 0x1d)
            val t = rationals(g, 7)
            // Date.UTC: whole hours and minutes, then the seconds' milliseconds
            // (with fewer than three values, NaN: no time).
            if (d != null && t != null && t.size >= 3 && Regex("""^\d{4}:\d\d:\d\d$""").containsMatchIn(d)) {
                val day = utc(d.substring(0, 4).toInt(), d.substring(5, 7).toInt(), d.substring(8, 10).toInt(), t[0], t[1])
                if (day != null) gpsTime = iso(day + jsRound(t[2] * 1000))
            }
        }

        var takenAt: String? = null
        var offsetKnown = false
        val e = ifd0[0x8769]?.let { ifd(u32(it.at)) }
        val local = (e?.let { str(it, 0x9003) }?.takeIf { it.isNotEmpty() }) ?: str(ifd0, 0x0132)
        val m = local?.let { Regex("""^(\d{4}):(\d\d):(\d\d) (\d\d):(\d\d):(\d\d)""").find(it) }
        if (m != null) {
            val parts = m.groupValues.drop(1).map { it.toInt() }
            val offset = e?.let { str(it, 0x9011) }
            if (offset != null && Regex("""^[+-]\d\d:\d\d$""").matches(offset)) {
                // new Date(iso + offset).toISOString(): throws on a date that isn't one.
                val at = localTime(parts) ?: throw OutOfRange()
                val oh = offset.substring(1, 3).toInt()
                val om = offset.substring(4, 6).toInt()
                if (oh > 23 || om > 59) throw OutOfRange()
                val sign = if (offset[0] == '-') -1 else 1
                takenAt = iso(at.toInstant(ZoneOffset.UTC).toEpochMilli() - sign * (oh * 3_600_000L + om * 60_000L))
                offsetKnown = true
            } else if (gpsTime != null) {
                takenAt = gpsTime
                offsetKnown = true
            } else {
                takenAt = localTime(parts)?.let { iso(it.atZone(zone).toInstant().toEpochMilli()) }
            }
        } else if (gpsTime != null) {
            takenAt = gpsTime
            offsetKnown = true
        }
        return Info(seg, lat, lon, takenAt, offsetKnown, orientation[0])
    }

    private class Entry(val type: Int, val count: Long, val at: Long)

    /**
     * A JPEG (as Bitmap.compress makes it) with [segment], the original's
     * EXIF, put back, in place of the encoder's own JFIF header.
     */
    fun withExif(jpeg: ByteArray, segment: ByteArray): ByteArray {
        var p = 2
        // Skip the encoder's APP0 (JFIF): EXIF takes its place.
        if (jpeg.size > 5 && u8(jpeg, 2) == 0xff && u8(jpeg, 3) == 0xe0) p = 4 + ((u8(jpeg, 4) shl 8) or u8(jpeg, 5))
        return byteArrayOf(0xff.toByte(), 0xd8.toByte()) + segment + jpeg.copyOfRange(p, jpeg.size)
    }

    // Bytes for each TIFF type, as the website's table (0 for type 0; 1 for any it doesn't know).
    private val SIZES = listOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun latin1(b: ByteArray, from: Int, to: Int): String =
        if (from >= to) "" else String(b, from, minOf(to, b.size) - from, Charsets.ISO_8859_1)

    /**
     * An EXIF date and time ([y, mo, d, h, mi, s]) as ECMAScript's date
     * string parse takes it: each field within its range (a day past the
     * month's end rolls over), else no date.
     */
    private fun localTime(p: List<Int>): LocalDateTime? {
        val (y, mo, d) = p
        val (h, mi, s) = p.subList(3, 6)
        if (mo !in 1..12 || d !in 1..31 || mi > 59 || s > 59 || h > 24 || (h == 24 && (mi > 0 || s > 0))) return null
        return LocalDate.of(y, mo, 1).atStartOfDay().plusDays(d - 1L).plusHours(h.toLong()).plusMinutes(mi.toLong()).plusSeconds(s.toLong())
    }

    /**
     * JavaScript's Date.UTC(y, mo - 1, d, h, mi, 0): whole hours and
     * minutes, overflow rolling over; null (NaN) past its range.
     */
    private fun utc(y: Int, mo: Int, d: Int, h: Double, mi: Double): Long? {
        // Two-digit years are 1900s, as Date.UTC has them.
        val year = if (y in 0..99) 1900 + y else y
        val day = LocalDate.of(year, 1, 1).plusMonths(mo - 1L).plusDays(d - 1L)
        val ms = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + h.toLong() * 3_600_000L + mi.toLong() * 60_000L
        return ms.takeIf { it in -8_640_000_000_000_000L..8_640_000_000_000_000L }
    }

    private fun jsRound(x: Double): Long = kotlin.math.floor(x + 0.5).toLong()

    // As toISOString, which throws past ±8.64e15 ms.
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private fun iso(ms: Long): String {
        if (ms > 8_640_000_000_000_000L || ms < -8_640_000_000_000_000L) throw OutOfRange()
        return ISO.format(Instant.ofEpochMilli(ms))
    }
}
