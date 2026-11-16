package com.hikeratlas.android.atproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream

/**
 * Posting tracks and reviews against a fake PDS. The records are compared
 * with test/resources/posts/website-records.json: what the website's own
 * publishTrack and publishReview (app/atproto/posts.ts) sent for the same
 * GPX and form, run in Node with a stand-in session (createdAt blanked).
 * Under Robolectric (native graphics) for photos' sizes, read with
 * Android's decoder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PostsTest {
    private lateinit var fake: FakeAtproto
    private lateinit var account: Account
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = "1E57E06gbDEBBBICDGF.tn3dBnAuR_Q"
    private val profile = "${ProfileRecord.NSID}/self"

    private fun text(path: String) = javaClass.getResource(path)!!.readText()
    private val website = Json.parseToJsonElement(text("/posts/website-records.json")).jsonArray.map { it.jsonObject }
    private fun websiteRecord(i: Int) = website.filter { it["body"] != null }[i]["body"]!!.jsonObject["record"]!!.jsonObject
    private val gpx = text("/gpx/app-two-segments.gpx")
    private val flat = text("/gpx/no-elevation.gpx")

    @Before
    fun setUp() {
        fake = FakeAtproto().start()
        account = Account(http, MemorySecretStore(), MemoryDpopKeys(), fake.config(), scope)
        runBlocking {
            account.beginSignIn(fake.authOrigin)
            account.finishSignIn(fake.callback(fake.pars.last()["state"]!!))
        }
        assertEquals(fake.did, account.did)
    }

    @After
    fun tearDown() {
        scope.cancel()
        fake.close()
    }

    private fun setLicense(l: String) {
        fake.records[profile] = Json.parseToJsonElement(
            """{"${'$'}type":"${ProfileRecord.NSID}","createdAt":"2026-01-01T00:00:00.000Z","license":"$l"}""",
        ).jsonObject
    }

    /** [record] without createdAt, which must be a time as JavaScript's toISOString writes it. */
    private fun undated(record: JsonObject, website: Boolean = false): JsonObject {
        val at = record["createdAt"]!!.jsonPrimitive.content
        // The website's were blanked; ours must be real.
        assertTrue(at, (website && at == "<createdAt>") || at.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""")))
        return JsonObject(record - "createdAt")
    }

    private fun creates() = fake.pdsRequests.filter { it.url.encodedPath == "/xrpc/com.atproto.repo.createRecord" }
        .map { Json.parseToJsonElement(it.body!!.utf8()).jsonObject }

    @Test
    fun aTrackAndItsReviewAsTheWebsiteWritesThem() = runBlocking {
        setLicense("CC-BY-4.0")
        // The PDS's answer goes into the record as it is: the one the website got.
        fake.blobAnswer = websiteRecord(0)["gpx"].toString()
        val got = Posts.publish(
            account.xrpc, fake.did,
            Posts.Post(
                text = "  Great views.\n", hikedAt = "2026-07-02T19:00:00.000Z", tags = listOf("views", "mud"),
                routeKey = key, title = "Mount Si", gpx = gpx,
            ),
        )
        val (track, review) = creates()
        // Records keyed by TIDs the PDS makes: no rkey.
        for (c in listOf(track, review)) {
            assertEquals(setOf("repo", "collection", "record"), c.keys)
            assertEquals(fake.did, c["repo"]!!.jsonPrimitive.content)
        }
        assertEquals(Posts.TRACK, track["collection"]!!.jsonPrimitive.content)
        assertEquals(Posts.REVIEW, review["collection"]!!.jsonPrimitive.content)

        val trackRecord = track["record"]!!.jsonObject
        val webTrack = websiteRecord(0)
        assertEquals(undated(webTrack, true), undated(trackRecord))
        // Field for field, in the website's order.
        assertEquals(webTrack.keys.toList(), trackRecord.keys.toList())

        // The review points at the track just posted, by the PDS's uri and cid.
        val ref = Posts.StrongRef("at://${fake.did}/${Posts.TRACK}/3tid1", "bafy-${Posts.TRACK}/3tid1")
        assertEquals(ref, got.track)
        val reviewRecord = review["record"]!!.jsonObject
        assertEquals(JsonObject(mapOf("uri" to JsonPrimitive(ref.uri), "cid" to JsonPrimitive(ref.cid))), reviewRecord["track"])
        val webReview = websiteRecord(1)
        val expected = JsonObject(webReview + ("track" to reviewRecord["track"]!!))
        assertEquals(undated(expected, true), undated(reviewRecord))
        assertEquals(webReview.keys.toList(), reviewRecord.keys.toList())
        assertEquals("at://${fake.did}/${Posts.REVIEW}/3tid2", got.review!!.uri)
    }

    @Test
    fun theGpxIsUploadedGzipped() = runBlocking {
        Posts.publish(account.xrpc, fake.did, Posts.Post(routeKey = key, title = "Mount Si", gpx = gpx))
        val (type, bytes) = fake.blobs.single()
        assertEquals("application/gzip", type)
        assertEquals(gpx, GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.UTF_8))
        val record = creates().single()["record"]!!.jsonObject
        // The blob as the PDS gave it.
        assertEquals("bafkrei-1", record["gpx"]!!.jsonObject["ref"]!!.jsonObject["\$link"]!!.jsonPrimitive.content)
        assertEquals(bytes.size.toString(), record["gpx"]!!.jsonObject["size"]!!.jsonPrimitive.content)
        // A track alone: no review.
        assertEquals(1, creates().size)
    }

    @Test
    fun withoutALicenceSetPostsAreCc0AndATrackWithoutAltitudesHasNoClimb() = runBlocking {
        fake.blobAnswer = websiteRecord(2)["gpx"].toString()
        Posts.publish(account.xrpc, fake.did, Posts.Post(routeKey = key, title = "Mount Si", gpx = flat))
        Posts.publish(account.xrpc, fake.did, Posts.Post(text = "Just words.", hikedAt = "2026-07-02T19:00:00.000Z", routeKey = key))
        val (track, review) = creates().map { it["record"]!!.jsonObject }
        assertEquals("CC0-1.0", track["license"]!!.jsonPrimitive.content)
        assertNull(track["gain"])
        assertEquals(undated(websiteRecord(2), true), undated(track))
        // A review alone: no track, and no tags when none are chosen.
        assertEquals(undated(websiteRecord(3), true), undated(review))
        assertEquals(1, fake.blobs.size)
    }

    @Test
    fun anUnknownLicenceIsTakenAsTheDefault() = runBlocking {
        setLicense("all-rights-reserved")
        assertEquals("CC0-1.0", Posts.licenseFor(account.xrpc, fake.did))
        setLicense("hikeratlas-only")
        assertEquals("hikeratlas-only", Posts.licenseFor(account.xrpc, fake.did))
        // The profile can't be read: the default, as the website.
        fake.getRecordFails = 502
        assertEquals("CC0-1.0", Posts.licenseFor(account.xrpc, fake.did))
    }

    @Test
    fun aTrackWithoutAHike() = runBlocking {
        Posts.publish(account.xrpc, fake.did, Posts.Post(text = "Wandered.", hikedAt = "2026-07-02T19:00:00.000Z", title = "Raging River · 2 Jul", gpx = gpx))
        val (track, review) = creates().map { it["record"]!!.jsonObject }
        assertNull(track["routeKey"])
        assertEquals("Raging River · 2 Jul", track["title"]!!.jsonPrimitive.content)
        assertNull(review["routeKey"])
        assertEquals(track, fake.records["${Posts.TRACK}/3tid1"])
    }

    /** Steps go in the record only where the track was counted (the app's <ha:steps>): never 0 for one that wasn't. */
    @Test
    fun aCountedTracksStepsGoInItsRecordAndOnlyThen() = runBlocking {
        val counted = gpx.replace("<name>Raging River</name>\n", "<name>Raging River</name>\n    <extensions><ha:steps>3120</ha:steps></extensions>\n")
        assertTrue(counted != gpx)
        Posts.publish(account.xrpc, fake.did, Posts.Post(title = "Counted", gpx = counted))
        Posts.publish(account.xrpc, fake.did, Posts.Post(title = "Not counted", gpx = gpx))
        val (withSteps, without) = creates().map { it["record"]!!.jsonObject }
        assertEquals(3120L, withSteps["steps"]!!.jsonPrimitive.content.toLong())
        assertEquals(listOf("distance", "gain", "steps", "title"), withSteps.keys.filter { it in setOf("distance", "gain", "steps", "title") })
        assertNull(without["steps"])
        // Counted, with no steps walked: 0 is a count.
        val zero = Posts.trackRecord(JsonObject(emptyMap()), com.hikeratlas.android.track.TrackMeasure.ofGpx(counted).copy(steps = 0), null, null, "CC0-1.0", emptyList())
        assertEquals("0", zero["steps"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRefusalSaysWhatFailedInThePdssWords() = runBlocking {
        fake.createFails = 400 to """{"error":"InvalidRecord","message":"Invalid record: text too long"}"""
        try {
            Posts.publish(account.xrpc, fake.did, Posts.Post(text = "Hi", routeKey = key))
            fail("posted")
        } catch (e: PostException) {
            assertEquals("createRecord failed (400): Invalid record: text too long", e.message)
        }
    }

    @Test
    fun aDayIsNoonThere() {
        // As the website's noonOf: noon in the phone's zone, as UTC.
        assertEquals("2026-07-02T19:00:00.000Z", Posts.noonOf(LocalDate.of(2026, 7, 2), ZoneId.of("America/Vancouver")))
        // Auckland's winter, UTC+12.
        assertEquals("2026-07-02T00:00:00.000Z", Posts.noonOf(LocalDate.of(2026, 7, 2), ZoneId.of("Pacific/Auckland")))
    }

    @Test
    fun titlesAndTagsKeepToTheLexicon() = runBlocking {
        // At most 200 graphemes, an accented letter (two chars) being one.
        val long = "a".repeat(150) + "e\u0301".repeat(100)
        assertEquals("a".repeat(150) + "e\u0301".repeat(50), Posts.clip(long))
        assertEquals("Mount Si", Posts.clip("Mount Si"))
        Posts.publish(account.xrpc, fake.did, Posts.Post(text = "Hi", tags = Posts.TAGS.map { it.first }, routeKey = key))
        val tags = creates().single()["record"]!!.jsonObject["tags"] as JsonArray
        assertEquals(Posts.MAX_TAGS, tags.size)
    }

    // The photos website-photo-records.json was made with: one with GPS, a
    // time and a description; one with only a time; one placed on a track
    // (no EXIF), its microdegrees rounded as Math.round. Their aspectRatio
    // (website#177) was added to it by hand, in uploadPhotos' order: each is
    // 32 × 24 as stored, the first turned a quarter (Orientation 6), the
    // second a half (3). These have none of their own, as a post queued
    // before the app recorded it: they're measured as they go up.
    private fun photos(): List<Posts.Photo> {
        fun file(name: String) = java.io.File.createTempFile("photo", ".jpg").apply {
            deleteOnExit()
            writeBytes(this@PostsTest.javaClass.getResource("/photos/$name")!!.readBytes())
        }.path
        return listOf(
            Posts.Photo(file("gps-offset.jpg"), alt = "Rainier from the summit", takenAt = "2026-07-02T17:15:30.000Z", lat = 47.487500000000004, lon = -121.7234, offsetKnown = true),
            Posts.Photo(file("local.jpg"), takenAt = "2026-03-08T07:30:00.000Z"),
            Posts.Photo(file("no-exif.jpg"), alt = "  ", lat = -33.8568125, lon = 151.2152995),
        )
    }

    private val websitePhotos = Json.parseToJsonElement(text("/photos/website-photo-records.json")).jsonObject

    @Test
    fun photosOnAReviewAsTheWebsiteWritesThem() = runBlocking {
        Posts.publish(
            account.xrpc, fake.did,
            Posts.Post(text = "Great views.", hikedAt = "2026-07-02T19:00:00.000Z", routeKey = key, photos = photos()),
        )
        val sent = websitePhotos["review"]!!.jsonArray.map { it.jsonObject }
        // Each photo's JPEG uploaded as it is, in order, then the record.
        val uploads = sent.filter { it["contentType"] != null }
        assertEquals(uploads.size, fake.blobs.size)
        for ((i, u) in uploads.withIndex()) {
            assertEquals("image/jpeg", fake.blobs[i].first)
            assertEquals(u["size"]!!.jsonPrimitive.content.toInt(), fake.blobs[i].second.size)
        }
        assertTrue(fake.blobs[0].second.contentEquals(javaClass.getResource("/photos/gps-offset.jpg")!!.readBytes()))
        val web = sent.last()["body"]!!.jsonObject["record"]!!.jsonObject
        val ours = creates().single()["record"]!!.jsonObject
        assertEquals(undated(web, true), undated(ours))
        assertEquals(web.keys.toList(), ours.keys.toList())
        assertEquals(web["photos"], ours["photos"])
        // Field for field, in the website's order.
        for ((w, o) in web["photos"]!!.jsonArray.zip(ours["photos"]!!.jsonArray)) assertEquals(w.jsonObject.keys.toList(), o.jsonObject.keys.toList())
    }

    @Test
    fun photosGoOnATrackPostedAloneAndOnTheReviewOtherwise() = runBlocking {
        Posts.publish(account.xrpc, fake.did, Posts.Post(title = "Mount Si", gpx = gpx, photos = photos()))
        val web = websitePhotos["track"]!!.jsonArray.last().jsonObject["body"]!!.jsonObject["record"]!!.jsonObject
        val track = creates().single()["record"]!!.jsonObject
        // The GPX's blob first (gzipped here as Java does, so not compared), then the photos'.
        assertEquals(listOf("application/gzip", "image/jpeg", "image/jpeg", "image/jpeg"), fake.blobs.map { it.first })
        assertEquals(web["photos"], track["photos"])
        assertEquals(web.keys.toList().filter { it != "createdAt" }, track.keys.toList().filter { it != "createdAt" })

        // With a review, they go on the review alone.
        fake.pdsRequests.clear()
        Posts.publish(account.xrpc, fake.did, Posts.Post(text = "Great views.", title = "Mount Si", gpx = gpx, photos = photos()))
        val (t, r) = creates().map { it["record"]!!.jsonObject }
        assertNull(t["photos"])
        assertEquals(3, r["photos"]!!.jsonArray.size)
    }

    @Test
    fun aPhotoNoLongerOnThePhoneSaysSo() = runBlocking {
        try {
            Posts.publish(account.xrpc, fake.did, Posts.Post(text = "Hi", photos = listOf(Posts.Photo("/no/such/photo.jpg"))))
            fail("posted")
        } catch (e: PostException) {
            assertEquals("A photo is no longer on this phone", e.message)
        }
        assertTrue(creates().isEmpty())
    }

    @Test
    fun aPhotosSizeFromWhenItWasPreparedIsTheOneWritten() = runBlocking {
        val p = photos()[2].copy(aspectRatio = Photos.AspectRatio(3, 2))
        Posts.publish(account.xrpc, fake.did, Posts.Post(text = "Hi", photos = listOf(p)))
        val photo = creates().single()["record"]!!.jsonObject["photos"]!!.jsonArray.single().jsonObject
        assertEquals(Json.parseToJsonElement("""{"width":3,"height":2}"""), photo["aspectRatio"])
        assertEquals(listOf("image", "aspectRatio", "at"), photo.keys.toList())
    }

    @Test
    fun aPhotoThatCantBeMeasuredIsntUploaded() = runBlocking {
        val notAPhoto = java.io.File.createTempFile("photo", ".jpg").apply { deleteOnExit(); writeText("not a photo") }.path
        try {
            Posts.publish(account.xrpc, fake.did, Posts.Post(text = "Hi", photos = listOf(Posts.Photo(notAPhoto))))
            fail("posted")
        } catch (e: PostException) {
            assertEquals("A photo can't be read as an image: remove it and post again", e.message)
        }
        assertTrue(fake.blobs.isEmpty())
        assertTrue(creates().isEmpty())
    }

    @Test
    fun aPhotoWithoutItsSizeIsRefusedAsTheLexiconNowDoes() = runBlocking {
        val image = Json.parseToJsonElement("""{"${'$'}type":"blob","ref":{"${'$'}link":"bafk"},"mimeType":"image/jpeg","size":10}""").jsonObject
        val record = Posts.reviewRecord("Hi", null, null, null, emptyList(), "CC0-1.0", listOf(kotlinx.serialization.json.buildJsonObject { put("image", image) }))
        try {
            Posts.create(account.xrpc, fake.did, Posts.REVIEW, record)
            fail("created")
        } catch (e: PostException) {
            assertEquals("createRecord failed (400): Invalid record: a photo without its aspectRatio", e.message)
        }
        // With it, the same record goes in.
        val withRatio = Posts.reviewRecord("Hi", null, null, null, emptyList(), "CC0-1.0", listOf(Photos.record(image, Posts.Photo("f"), Photos.AspectRatio(4, 3))))
        Posts.create(account.xrpc, fake.did, Posts.REVIEW, withRatio)
        Unit
    }

    @Test
    fun aDescriptionKeepsToTheLexicon() {
        val image = kotlinx.serialization.json.buildJsonObject { }
        val alt = Photos.record(image, Posts.Photo("f", alt = "x".repeat(1200)), Photos.AspectRatio(4, 3))["alt"]!!.jsonPrimitive.content
        assertEquals(Photos.MAX_ALT, alt.length)
    }
}
