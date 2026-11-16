package com.hikeratlas.android.atproto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import kotlin.random.Random

/**
 * Preparing photos as the website's preparePhoto does (with Android's real
 * JPEG codec: Robolectric's native graphics), and placing them on a track
 * as its locate does: the cases in website-locate.json are what the
 * website's own locate gave for locate.gpx, run in Node.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PhotosTest {
    @get:Rule val tmp = TemporaryFolder()

    private val toronto = ZoneId.of("America/Toronto")
    private fun bytes(name: String) = javaClass.getResource("/photos/$name")!!.readBytes()
    private fun text(name: String) = javaClass.getResource("/photos/$name")!!.readText()

    @Test
    fun aJpegThatFitsIsUploadedAsItIs() {
        val original = bytes("gps-offset.jpg")
        val p = Photos.prepare(original, toronto)
        assertSame(original, p.bytes)
        assertArrayEquals(bytes("gps-offset.jpg"), p.bytes)
        assertEquals(47.4875, p.lat!!, 1e-9)
        assertEquals("2026-07-02T17:15:30.000Z", p.takenAt)
    }

    /**
     * A 12 MP photo, stored on its side (Orientation 6: drawn turned a
     * quarter clockwise), too big for 2 MB: noise, so JPEG can't shrink it
     * much, with a red block at its stored top left.
     */
    private fun bigRotatedPhoto(): ByteArray {
        val w = 4000
        val h = 3000
        val rnd = Random(58)
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x < 400 && y < 300) Color.rgb(230, 20, 20) else Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
        }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        // The fixture's EXIF as the camera wrote it (Orientation 6, GPS, time), in place of JFIF.
        val camera = bytes("gps-offset.jpg")
        val len = ((camera[4].toInt() and 0xff) shl 8) or (camera[5].toInt() and 0xff)
        return Exif.withExif(out.toByteArray(), camera.copyOfRange(2, 4 + len))
    }

    private fun mean(b: Bitmap, x0: Int, y0: Int, size: Int): Triple<Int, Int, Int> {
        var r = 0L; var g = 0L; var bl = 0L
        for (x in x0 until x0 + size) for (y in y0 until y0 + size) {
            val c = b.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); bl += Color.blue(c)
        }
        val n = size * size
        return Triple((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    @Test
    fun aBigPhotoIsReencodedWithinTheLimitsWithItsExifUpright() {
        val original = bigRotatedPhoto()
        assertTrue("the test photo is over 2 MB", original.size > Photos.MAX_BYTES)
        val before = Exif.read(original, toronto)
        assertEquals(6, before.orientation)

        val p = Photos.prepare(original, toronto)
        assertTrue("${p.bytes.size} bytes", p.bytes.size <= Photos.MAX_BYTES)
        // The original's EXIF in place of JFIF, Orientation 1: the pixels are upright.
        assertEquals(listOf(0xff, 0xd8, 0xff, 0xe1), p.bytes.take(4).map { it.toInt() and 0xff })
        val after = Exif.read(p.bytes, toronto)
        assertEquals(1, after.orientation)
        assertArrayEquals(before.segment, after.segment)
        assertEquals(before.lat, after.lat)
        assertEquals(before.lon, after.lon)
        assertEquals(before.takenAt, after.takenAt)
        assertEquals(before.lat, p.lat)
        assertEquals(before.takenAt, p.takenAt)

        // At most 2048 a side, turned upright: 3000 × 4000 drawn, so 1536 × 2048.
        val img = BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size)
        assertEquals(1536, img.width)
        assertEquals(2048, img.height)
        // The stored top left is the upright top right.
        val (r, g, _) = mean(img, img.width - 60, 10, 40)
        assertTrue("red at the top right: $r, $g", r > 180 && g < 80)
        val (r2, g2, _) = mean(img, 10, 10, 40)
        assertFalse("not red at the top left: $r2, $g2", r2 > 180 && g2 < 80)
    }

    @Test
    fun aPhotoThatIsntAJpegBecomesOne() {
        val bmp = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val p = Photos.prepare(png, toronto)
        assertTrue(Photos.isJpeg(p.bytes))
        assertNull(p.takenAt)
        val img = BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size)
        assertEquals(40, img.width)
        assertEquals(30, img.height)
    }

    // aspectRatio (defs#photo, required since website#177): the size as
    // displayed, from the bytes uploaded. The fixtures are 32 × 24 as stored.

    @Test
    fun aJpegUploadedAsItIsHasItsOwnSizeTurnedByItsOrientation() {
        // Orientation 6: stored on its side, so 24 wide and 32 high as displayed.
        assertEquals(Photos.AspectRatio(24, 32), Photos.prepare(bytes("gps-offset.jpg"), toronto).aspectRatio)
        assertEquals(Photos.AspectRatio(32, 24), Photos.prepare(bytes("no-exif.jpg"), toronto).aspectRatio)
    }

    @Test
    fun orientationsFiveToEightSwapWidthAndHeight() {
        assertEquals(1, Exif.read(bytes("gps-time-be.jpg")).orientation)
        assertEquals(Photos.AspectRatio(32, 24), Photos.measure(bytes("gps-time-be.jpg")))
        assertEquals(3, Exif.read(bytes("local.jpg")).orientation)
        assertEquals(Photos.AspectRatio(32, 24), Photos.measure(bytes("local.jpg")))
        assertEquals(6, Exif.read(bytes("gps-offset.jpg")).orientation)
        assertEquals(Photos.AspectRatio(24, 32), Photos.measure(bytes("gps-offset.jpg")))
        assertEquals(8, Exif.read(bytes("orientation-8.jpg")).orientation)
        assertEquals(Photos.AspectRatio(24, 32), Photos.measure(bytes("orientation-8.jpg")))
        // 5 and 7 too: the same photo with its Orientation patched.
        for (o in 1..8) {
            val b = bytes("orientation-8.jpg")
            val at = indexOfOrientation(b)
            b[at] = o.toByte()
            assertEquals("orientation $o", if (o in 5..8) Photos.AspectRatio(24, 32) else Photos.AspectRatio(32, 24), Photos.measure(b))
        }
    }

    /** Where orientation-8.jpg's Orientation value is (little-endian: its low byte). */
    private fun indexOfOrientation(b: ByteArray): Int {
        for (i in 0 until b.size - 1) {
            // Tag 0x0112, type SHORT, count 1, value 8.
            if (b[i] == 0x12.toByte() && b[i + 1] == 0x01.toByte() && b[i + 2] == 3.toByte() && b[i + 8] == 8.toByte()) return i + 8
        }
        error("no Orientation")
    }

    @Test
    fun aReencodedPhotoHasTheReencodedSize() {
        // 4000 × 3000 stored, Orientation 6: re-encoded upright at 1536 × 2048.
        assertEquals(Photos.AspectRatio(1536, 2048), Photos.prepare(bigRotatedPhoto(), toronto).aspectRatio)
        val bmp = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        assertEquals(Photos.AspectRatio(40, 30), Photos.prepare(png, toronto).aspectRatio)
    }

    @Test
    fun aWebpIsMeasuredToo() {
        assertEquals(Photos.AspectRatio(40, 30), Photos.measure(bytes("thumbnail.webp")))
        assertNull(Photos.measure("not a photo".toByteArray()))
    }

    @Test(expected = java.io.IOException::class)
    fun aJpegThatCantBeDecodedIsntUploaded() {
        // A JPEG's first bytes and nothing it could be measured from: as the website, not uploaded.
        Photos.prepare(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()), toronto)
    }

    @Test
    fun theStoreKeepsItsSizeWithIt() {
        val store = Photos.Store(File(tmp.root, "photos")) { toronto }
        assertEquals(Photos.AspectRatio(24, 32), store.save(bytes("gps-offset.jpg")).aspectRatio)
        // A post saved by an older app has none: it reads as unknown, measured as it goes up.
        val old = Json.decodeFromString(Posts.Photo.serializer(), """{"file":"/x/photo-0.jpg","alt":"Rainier"}""")
        assertNull(old.aspectRatio)
    }

    @Test
    fun anEntrysSizeIsReadOnlyWhenItsValid() {
        fun entry(s: String) = Json.parseToJsonElement("""{"image":{}$s}""").jsonObject
        assertEquals(Photos.AspectRatio(4, 3), Photos.ratioOf(entry(""","aspectRatio":{"width":4,"height":3}""")))
        for (bad in listOf("", ""","aspectRatio":{"width":0,"height":3}""", ""","aspectRatio":{"width":"4","height":3}""", ""","aspectRatio":{"width":4}""")) {
            assertNull(bad, Photos.ratioOf(entry(bad)))
        }
    }

    @Test(expected = java.io.IOException::class)
    fun notAnImage() {
        Photos.prepare("not a photo".toByteArray(), toronto)
    }

    @Test
    fun keptInTheAppsStorageUntilDiscarded() {
        val dir = File(tmp.root, "photos")
        val store = Photos.Store(dir) { toronto }
        val p = store.save(bytes("gps-offset.jpg"))
        assertEquals(dir.path, File(p.file).parent)
        assertArrayEquals(bytes("gps-offset.jpg"), File(p.file).readBytes())
        assertEquals(47.4875, p.lat!!, 1e-9)
        assertEquals(true, p.offsetKnown)
        // Kept as JSON with a post that waits (#60).
        assertEquals(p, Json.decodeFromString(Posts.Photo.serializer(), Json.encodeToString(Posts.Photo.serializer(), p)))
        store.discard(p)
        assertFalse(File(p.file).exists())
        // Only its own files.
        val other = File(tmp.root, "other.jpg").apply { writeText("x") }
        store.discard(p.copy(file = other.path))
        assertTrue(other.exists())
    }

    @Test
    fun aCameraPhotoWithoutATimeTakesWhenItWasTaken() {
        val store = Photos.Store(File(tmp.root, "photos")) { toronto }
        val p = store.save(bytes("no-exif.jpg"), capturedAt = 1_783_004_400_000)
        assertEquals("2026-07-02T15:00:00.000Z", p.takenAt)
        assertEquals(true, p.offsetKnown)
        assertNull(p.lat)
        // One whose EXIF has a time keeps the EXIF's.
        assertEquals("2026-03-08T07:30:00.000Z", store.save(bytes("local.jpg"), capturedAt = 1_783_004_400_000).takenAt)
    }

    @Test
    fun placedOnTheTrackAsTheWebsiteDoes() {
        val gpx = text("locate.gpx")
        val cases = Json.parseToJsonElement(text("website-locate.json")).jsonArray.map { it.jsonObject }
        assertEquals(13, cases.size)
        for (c in cases) {
            val what = c["what"]!!.jsonPrimitive.content
            val got = Photos.locate(gpx, c["when"]!!.jsonPrimitive.content)
            val at = c["at"]
            if (at is JsonNull) {
                assertNull(what, got)
            } else {
                assertEquals(what, at!!.jsonObject["lat"]!!.jsonPrimitive.double, got!!.lat, 0.0)
                assertEquals(what, at.jsonObject["lon"]!!.jsonPrimitive.double, got.lon, 0.0)
            }
        }
        // A track without times places nothing.
        val flat = """<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg><trkpt lat="1" lon="2"/><trkpt lat="1.1" lon="2.1"/></trkseg></trk></gpx>"""
        assertNull(Photos.locate(flat, "2026-07-02T15:00:00.000Z"))
        assertNull(Photos.locate("not GPX", "2026-07-02T15:00:00.000Z"))
    }

    @Test
    fun onlyAPhotoWithoutItsOwnLocationIsPlaced() {
        val gpx = text("locate.gpx")
        val bare = Posts.Photo("f.jpg", takenAt = "2026-07-02T15:02:30.000Z")
        assertEquals(Posts.Photo("f.jpg", takenAt = "2026-07-02T15:02:30.000Z", lat = 47.5005, lon = -121.7995), Photos.placed(bare, gpx).let {
            it.copy(lat = Math.round(it.lat!! * 1e7) / 1e7, lon = Math.round(it.lon!! * 1e7) / 1e7)
        })
        val own = bare.copy(lat = 1.0, lon = 2.0)
        assertEquals(own, Photos.placed(own, gpx))
        assertEquals(bare, Photos.placed(bare, null))
        assertEquals(bare.copy(takenAt = null), Photos.placed(bare.copy(takenAt = null), gpx))
    }
}
