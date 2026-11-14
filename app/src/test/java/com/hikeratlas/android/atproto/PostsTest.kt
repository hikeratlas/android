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
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream

/**
 * Posting tracks and reviews against a fake PDS. The records are compared
 * with test/resources/posts/website-records.json: what the website's own
 * publishTrack and publishReview (app/atproto/posts.ts) sent for the same
 * GPX and form, run in Node with a stand-in session (createdAt blanked).
 */
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
}
