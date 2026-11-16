package com.hikeratlas.android.atproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Editing a review: the record as the website's editedReview makes it (its
 * tests in app/atproto/posts.test.ts, ported with the same inputs and
 * outputs), and saved over the old one against a fake PDS that checks
 * swapRecord as a real one does.
 */
class EditReviewTest {
    private lateinit var fake: FakeAtproto
    private lateinit var account: Account
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val heard = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun blob(cid: String) = """{"${'$'}type":"blob","ref":{"${'$'}link":"$cid"},"mimeType":"image/jpeg","size":1}"""
    private val ratio = ""","aspectRatio":{"width":4,"height":3}"""

    // The website test's review, field for field.
    private val review = obj(
        """{"text":"Muddy","createdAt":"2026-11-01T10:00:00.000Z","hikedAt":"2026-10-31T16:00:00.000Z","license":"CC-BY-4.0",
        "routeKey":"1E57E06gbDEBBBICDGF.tn3dBnAuR_Q","track":{"uri":"at://did:plc:x/com.hikeratlas.temp.track/3l","cid":"bafy"},
        "tags":["mud"],"photos":[{"image":${blob("a")},"alt":"one"},{"image":${blob("b")}}]}""",
    )

    @Before
    fun setUp() {
        fake = FakeAtproto().start()
        account = Account(http, MemorySecretStore(), MemoryDpopKeys(), fake.config(), scope)
        runBlocking {
            account.beginSignIn(fake.authOrigin)
            account.finishSignIn(fake.callback(fake.pars.last()["state"]!!))
        }
        Indexer.notifier = { u, done -> heard += u; done() }
    }

    @After
    fun tearDown() {
        Indexer.notifier = { _, done -> done() }
        scope.cancel()
        fake.close()
    }

    @Test
    fun changesOnlyWhatTheEditSays() {
        val out = Posts.editedReview(
            review,
            Posts.ReviewEdit("Muddy, then snow", "2026-10-30T16:00:00.000Z", listOf("mud", "snow"), listOf(obj("""{"image":${blob("b")},"alt":"two"}"""))),
        )
        assertEquals(
            obj(
                """{"${'$'}type":"com.hikeratlas.temp.review","text":"Muddy, then snow","createdAt":"2026-11-01T10:00:00.000Z",
                "hikedAt":"2026-10-30T16:00:00.000Z","license":"CC-BY-4.0","routeKey":"1E57E06gbDEBBBICDGF.tn3dBnAuR_Q",
                "track":{"uri":"at://did:plc:x/com.hikeratlas.temp.track/3l","cid":"bafy"},"tags":["mud","snow"],
                "photos":[{"image":${blob("b")},"alt":"two"}]}""",
            ),
            out,
        )
    }

    @Test
    fun dropsTagsPhotosAndTheDateWhenTheEditRemovesThem() {
        val out = Posts.editedReview(review, Posts.ReviewEdit("x", tags = emptyList(), photos = emptyList()))
        assertFalse(out.containsKey("tags"))
        assertFalse(out.containsKey("photos"))
        assertFalse(out.containsKey("hikedAt"))
        assertEquals("CC-BY-4.0", out["license"]!!.jsonPrimitive.content)
    }

    @Test
    fun changesTheLicenceWhenTheEditGivesOne() {
        assertEquals("CC0-1.0", Posts.editedReview(review, Posts.ReviewEdit("x", license = "CC0-1.0"))["license"]!!.jsonPrimitive.content)
        val unlicensed = JsonObject(review - "license")
        assertFalse(Posts.editedReview(unlicensed, Posts.ReviewEdit("x")).containsKey("license"))
    }

    @Test
    fun theDayKeepsItsTimeUnlessChanged() {
        val zone = ZoneId.of("America/Vancouver")
        // 16:00Z is 09:00 there, on 31 Oct.
        assertEquals("2026-10-31T16:00:00.000Z", Posts.editedHikedAt("2026-10-31T16:00:00.000Z", LocalDate.of(2026, 10, 31), zone))
        assertEquals("2026-10-30T19:00:00.000Z", Posts.editedHikedAt("2026-10-31T16:00:00.000Z", LocalDate.of(2026, 10, 30), zone))
        assertEquals("2026-10-30T19:00:00.000Z", Posts.editedHikedAt(null, LocalDate.of(2026, 10, 30), zone))
        assertNull(Posts.editedHikedAt("2026-10-31T16:00:00.000Z", null, zone))
        assertNull(Posts.localDay("not a date"))
    }

    @Test
    fun aPhotosDescriptionChangesOrGoesAndTheRestStays() {
        val entry = obj("""{"image":${blob("a")},"alt":"one","takenAt":"2026-10-31T15:00:00.000Z","at":{"lat":1,"lon":2}}""")
        assertEquals(JsonPrimitive("two"), Posts.withAlt(entry, "  two ")["alt"])
        assertEquals(JsonObject(entry - "alt"), Posts.withAlt(entry, "  "))
        assertEquals(1000, Posts.withAlt(entry, "x".repeat(1200))["alt"]!!.jsonPrimitive.content.length)
        assertEquals(entry["at"], Posts.withAlt(entry, "two")["at"])
    }

    private val rkey = "3abc"
    private val key = "${Posts.REVIEW}/$rkey"
    private val uri get() = "at://${fake.did}/$key"

    /** The review as the profile reads it: from the PDS, with its CID. */
    private fun posted(extra: String = ""): Profiles.Review {
        fake.records[key] = obj(
            """{"${'$'}type":"${Posts.REVIEW}","text":"Muddy","createdAt":"2026-11-01T10:00:00.000Z","hikedAt":"2026-10-31T16:00:00.000Z",
            "routeKey":"1E57E06gbDEBBBICDGF.tn3dBnAuR_Q","tags":["mud"],
            "photos":[{"image":${blob("a")},"alt":"one","takenAt":"2026-10-31T15:00:00.000Z"$ratio},{"image":${blob("b")}$ratio}],
            "via":"another app"$extra}""",
        )
        return runBlocking { Profiles.loadDid(http, Identity(http, fake.config()), fake.did) }.reviews.single()
    }

    private fun puts() = fake.pdsRequests.filter { it.url.encodedPath == "/xrpc/com.atproto.repo.putRecord" }
        .map { obj(it.body!!.utf8()) }

    @Test
    fun savedOverTheOldRecordAtTheSameKeySwappingItsCid() {
        val r = posted()
        val before = r.cid
        val photos = Posts.photoEntries(r.value)
        val edit = Posts.ReviewEdit(
            "Muddy, then snow", r.hikedAt, listOf("mud", "snow"),
            // The first photo's description changed; the second removed.
            listOf(Posts.withAlt(photos[0], "The bridge")),
        )
        val saved0 = runBlocking { Posts.editReview(account.xrpc, fake.did, r, edit) }
        val ref = saved0.ref
        assertEquals(uri, ref.uri)
        assertTrue(ref.cid != before)
        val put = puts().single()
        assertEquals(fake.did, put["repo"]!!.jsonPrimitive.content)
        assertEquals(Posts.REVIEW, put["collection"]!!.jsonPrimitive.content)
        assertEquals(rkey, put["rkey"]!!.jsonPrimitive.content)
        assertEquals(before, put["swapRecord"]!!.jsonPrimitive.content)
        val saved = fake.records[key]!!
        assertEquals(Posts.editedReview(r.value, edit), saved)
        // Its photos had their sizes: nothing to measure, so what was written is the edit as it was.
        assertEquals(saved, saved0.record)
        assertTrue(fake.pdsRequests.none { it.url.encodedPath.endsWith("getBlob") })
        assertEquals("Muddy, then snow", saved["text"]!!.jsonPrimitive.content)
        // What the edit doesn't touch stays, fields this app doesn't know included.
        assertEquals("another app", saved["via"]!!.jsonPrimitive.content)
        assertEquals("2026-11-01T10:00:00.000Z", saved["createdAt"]!!.jsonPrimitive.content)
        assertEquals("1E57E06gbDEBBBICDGF.tn3dBnAuR_Q", saved["routeKey"]!!.jsonPrimitive.content)
        assertEquals(
            obj("""{"image":${blob("a")},"alt":"The bridge","takenAt":"2026-10-31T15:00:00.000Z"$ratio}"""),
            (saved["photos"] as kotlinx.serialization.json.JsonArray).single(),
        )
        // No licence before, none written.
        assertFalse(saved.containsKey("license"))
        assertEquals(listOf(uri), heard)

        // Read again, it shows the edit, at its new CID.
        val again = runBlocking { Profiles.loadDid(http, Identity(http, fake.config()), fake.did) }.reviews.single()
        assertEquals("Muddy, then snow", again.text)
        assertEquals(ref.cid, again.cid)
        assertEquals(1, again.photos.size)
    }

    @Test
    fun aNewLicenceIsWritten() {
        val r = posted(""","license":"CC-BY-4.0"""")
        runBlocking { Posts.editReview(account.xrpc, fake.did, r, Posts.ReviewEdit(r.text, r.hikedAt, r.tags, license = "CC0-1.0")) }
        assertEquals("CC0-1.0", fake.records[key]!!["license"]!!.jsonPrimitive.content)
    }

    @Test
    fun aReviewThatChangedMeanwhileIsntOverwritten() {
        val r = posted()
        // Edited elsewhere (the website) since the profile read it.
        runBlocking { Posts.editReview(account.xrpc, fake.did, r, Posts.ReviewEdit("From the website")) }
        heard.clear()
        try {
            runBlocking { Posts.editReview(account.xrpc, fake.did, r, Posts.ReviewEdit("From the phone")) }
            fail()
        } catch (e: PostException) {
            assertEquals(Posts.EDIT_CHANGED, e.message)
            assertEquals("InvalidSwap", (e.cause as XrpcException).error)
        }
        assertEquals("From the website", fake.records[key]!!["text"]!!.jsonPrimitive.content)
        assertTrue(heard.isEmpty())
    }

    @Test
    fun withoutSignalItSaysSo() {
        val r = posted()
        fake.fault = { if (it.url.encodedPath.endsWith("putRecord")) FakeAtproto.json("""{"error":"Unavailable"}""", 503) else null }
        try {
            runBlocking { Posts.editReview(account.xrpc, fake.did, r, Posts.ReviewEdit("x")) }
            fail()
        } catch (e: PostException) {
            assertTrue(e.message!!, e.message!!.startsWith("Couldn't reach your account's server"))
        }
        assertEquals("Muddy", fake.records[key]!!["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun anotherErrorSaysWhatThePdsSaid() {
        val r = posted()
        fake.fault = { if (it.url.encodedPath.endsWith("putRecord")) FakeAtproto.json("""{"error":"InvalidRecord","message":"Bad tags"}""", 400) else null }
        try {
            runBlocking { Posts.editReview(account.xrpc, fake.did, r, Posts.ReviewEdit("x")) }
            fail()
        } catch (e: PostException) {
            assertEquals("Saving failed (400): Bad tags", e.message)
        }
    }

    @Test
    fun theProfileShowsTheEditedReviewInItsPlace() {
        val older = posted()
        val newer = older.copy(uri = "at://${fake.did}/${Posts.REVIEW}/3new", hikedAt = "2026-11-10T20:00:00.000Z")
        val p = Profiles.Profile(fake.did, null, fake.pdsOrigin, listOf(newer, older), emptyList(), emptyList())
        // Its day moved past the other's: first now.
        val edited = older.copy(text = "Edited", hikedAt = "2026-11-12T20:00:00.000Z", cid = "new")
        assertEquals(listOf(edited, newer), p.with(edited).reviews)
    }

    @Test
    fun onlyYourOwnReviewsAndOnlyReadWhole() {
        val r = posted()
        for (bad in listOf(r.copy(uri = "at://did:plc:someoneelse/$key"), r.copy(uri = "at://${fake.did}/${Posts.TRACK}/$rkey"), r.copy(value = JsonObject(emptyMap())))) {
            try {
                runBlocking { Posts.editReview(account.xrpc, fake.did, bad, Posts.ReviewEdit("x")) }
                fail()
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue(puts().isEmpty())
    }
}
