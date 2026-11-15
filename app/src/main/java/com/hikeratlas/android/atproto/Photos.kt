package com.hikeratlas.android.atproto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import com.hikeratlas.android.track.TrackMeasure
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Photos for tracks and reviews (com.hikeratlas.temp.defs#photo), as the
 * website's app/atproto/photos.ts: within the lexicon's 2 MB. A JPEG that
 * fits is uploaded as it is; a bigger photo is re-encoded as a JPEG of at
 * most [MAX_SIDE] pixels a side, with the original's EXIF put back. Either
 * way it keeps where and when it was taken: photos of places are much of
 * what's worth sharing. Where and when also go in the record (at, takenAt),
 * from EXIF, so apps needn't read the image for them.
 */
object Photos {
    const val MAX_SIDE = 2048
    const val MAX_BYTES = 2_000_000
    /** A review takes at most 20 photos (a track 50); photos go on one or the other. */
    const val MAX_PHOTOS = 20
    /** The lexicon's alt is at most 1000 graphemes; the form counts UTF-16 units, which is never fewer. */
    const val MAX_ALT = 1000
    /** Bigger than any phone's photo: a file past this isn't read into memory. */
    const val MAX_INPUT = 64_000_000

    /** A photo ready to upload: its bytes, and where and when it was taken (from its EXIF). */
    class Prepared(val bytes: ByteArray, val lat: Double?, val lon: Double?, val takenAt: String?, val offsetKnown: Boolean)

    /** [bytes] as the website's preparePhoto would upload them. Throws [IOException] for what isn't an image Android can read. */
    fun prepare(bytes: ByteArray, zone: ZoneId = ZoneId.systemDefault()): Prepared {
        val exif = Exif.read(bytes, zone)
        val out = if (isJpeg(bytes) && bytes.size <= MAX_BYTES) bytes else reencode(bytes, exif)
        return Prepared(out, exif.lat, exif.lon, exif.takenAt, exif.offsetKnown)
    }

    fun isJpeg(b: ByteArray) = b.size > 2 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte()

    /**
     * Re-encoded as the website does it: upright, at most [MAX_SIDE] a side,
     * JPEG quality 85, 75, 65, 55, then 0.8 the size each time until it fits
     * in 2 MB with the original's EXIF segment, which then takes the place of
     * the encoder's JFIF header. Decoded at a power-of-two fraction of its
     * size first (inSampleSize), so a 50 MP photo isn't held at full size.
     */
    internal fun reencode(bytes: ByteArray, exif: Exif.Info): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image Android can read")
        // Its size drawn upright: orientations 5 to 8 turn it a quarter.
        val turned = exif.orientation in 5..8
        val width = if (turned) bounds.outHeight else bounds.outWidth
        val height = if (turned) bounds.outWidth else bounds.outHeight
        var sample = 1
        while (max(width, height) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Not an image Android can read")
        try {
            var scale = min(1.0, MAX_SIDE.toDouble() / max(width, height))
            val room = MAX_BYTES - (exif.segment?.size ?: 0)
            var quality = 0.85
            while (true) {
                val w = max(1L, TrackMeasure.jsRound(width * scale)).toInt()
                val h = max(1L, TrackMeasure.jsRound(height * scale)).toInt()
                val bitmap = upright(decoded, exif.orientation, w, h)
                val out = ByteArrayOutputStream()
                try {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, TrackMeasure.jsRound(quality * 100).toInt(), out)
                } finally {
                    if (bitmap !== decoded) bitmap.recycle()
                }
                if (out.size() <= room) return exif.segment?.let { Exif.withExif(out.toByteArray(), it) } ?: out.toByteArray()
                if (quality > 0.6) quality -= 0.1 else scale *= 0.8
            }
        } finally {
            decoded.recycle()
        }
    }

    /** [src] at [w] × [h] once drawn upright, per its EXIF [orientation]: scaled first (smaller), then turned or flipped. */
    private fun upright(src: Bitmap, orientation: Int, w: Int, h: Int): Bitmap {
        val turned = orientation in 5..8
        val sw = if (turned) h else w
        val sh = if (turned) w else h
        val scaled = if (src.width == sw && src.height == sh) src else src.scale(sw, sh)
        val m = orientationMatrix(orientation) ?: return scaled
        val out = Bitmap.createBitmap(scaled, 0, 0, sw, sh, m, true)
        if (scaled !== src && scaled !== out) scaled.recycle()
        return out
    }

    /** What draws a photo with EXIF Orientation [o] upright; null for 1 (or a value that isn't one). */
    internal fun orientationMatrix(o: Int): Matrix? = when (o) {
        2 -> Matrix().apply { setScale(-1f, 1f) }
        3 -> Matrix().apply { setRotate(180f) }
        4 -> Matrix().apply { setScale(1f, -1f) }
        5 -> Matrix().apply { setRotate(90f); postScale(-1f, 1f) }
        6 -> Matrix().apply { setRotate(90f) }
        7 -> Matrix().apply { setRotate(-90f); postScale(-1f, 1f) }
        8 -> Matrix().apply { setRotate(-90f) }
        else -> null
    }

    /** A small upright copy of a prepared photo, for the form: about [px] on its short side. */
    fun thumbnail(file: File, px: Int): Bitmap? {
        val bytes = file.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val m = orientationMatrix(Exif.read(bytes).orientation) ?: return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { if (it !== b) b.recycle() }
    }

    /** Where a photo was, from a track: latitude and longitude in degrees. */
    data class At(val lat: Double, val lon: Double)

    // A photo more than this far outside the track's times isn't placed on
    // it, nor one in a gap longer than GAP (a pause, a night).
    private const val SLACK = 10 * 60_000L
    private const val GAP = 30 * 60_000L

    /**
     * Where [gpx] was at [whenIso], between the two points either side of it,
     * as the website's locate: null if the track has no times, or the time is
     * more than 10 minutes outside it or in a gap of more than 30 minutes.
     * For placing a photo without GPS by when it was taken.
     */
    fun locate(gpx: String, whenIso: String): At? {
        val t = TrackMeasure.jsDateParse(whenIso) ?: return null
        val pts = try {
            TrackMeasure.parse(gpx)
        } catch (e: IllegalArgumentException) {
            return null
        }.flatten().filter { it.time != null }.sortedBy { it.time }
        if (pts.isEmpty()) return null
        val first = pts.first()
        if (t <= first.time!!) return if (first.time - t <= SLACK) At(first.lat, first.lon) else null
        val last = pts.last()
        if (t >= last.time!!) return if (t - last.time <= SLACK) At(last.lat, last.lon) else null
        var lo = 0
        var hi = pts.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (pts[mid].time!! <= t) lo = mid else hi = mid
        }
        val a = pts[lo]
        val b = pts[hi]
        if (b.time!! - a.time!! > GAP) return null
        val f = if (b.time == a.time) 0.0 else (t - a.time).toDouble() / (b.time - a.time).toDouble()
        return At(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    /** [p] placed by its time on [gpx], when it has no location of its own (the website's PostDialog does this as it posts). */
    fun placed(p: Posts.Photo, gpx: String?): Posts.Photo {
        if (p.lat != null || p.takenAt == null || gpx == null) return p
        val at = locate(gpx, p.takenAt) ?: return p
        return p.copy(lat = at.lat, lon = at.lon)
    }

    /** Degrees as the lexicon's integer microdegrees (defs#point), rounded as Math.round. */
    fun micro(x: Double): Long = TrackMeasure.jsRound(x * 1e6)

    /** A photo's entry in a record, field for field as the website's uploadPhotos writes it. */
    fun record(image: JsonObject, p: Posts.Photo): JsonObject = buildJsonObject {
        put("image", image)
        p.alt?.trim()?.takeIf { it.isNotEmpty() }?.let { put("alt", Posts.clip(it, MAX_ALT)) }
        p.takenAt?.let { put("takenAt", it) }
        if (p.lat != null && p.lon != null) put("at", buildJsonObject { put("lat", micro(p.lat)); put("lon", micro(p.lon)) })
    }

    /**
     * Prepared photos kept in the app's own storage until they're posted, so
     * a post can wait on the phone and go up later (#60): each a JPEG file
     * in [dir], its details in the [Posts.Photo] that names it.
     */
    class Store(private val dir: File, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
        /**
         * Prepares [bytes] and keeps them. [capturedAt]: when a photo just
         * taken with the camera was taken, for one whose EXIF doesn't say.
         */
        fun save(bytes: ByteArray, capturedAt: Long? = null): Posts.Photo {
            val p = prepare(bytes, zone())
            dir.mkdirs()
            val f = File(dir, "${UUID.randomUUID()}.jpg")
            f.writeBytes(p.bytes)
            val fallback = p.takenAt == null && capturedAt != null
            return Posts.Photo(
                file = f.path, lat = p.lat, lon = p.lon,
                takenAt = p.takenAt ?: capturedAt?.let(TrackMeasure::iso),
                offsetKnown = p.offsetKnown || fallback,
            )
        }

        /** Drops a photo's file (removed from the form, or posted): only ever one of this store's. */
        fun discard(p: Posts.Photo) {
            val f = File(p.file)
            if (f.parentFile?.canonicalPath == dir.canonicalPath) f.delete()
        }
    }
}
