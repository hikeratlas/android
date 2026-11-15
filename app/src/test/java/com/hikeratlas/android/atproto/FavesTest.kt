package com.hikeratlas.android.atproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import java.io.IOException

/** Faves against a fake PDS: set, cleared, looked up and listed as the website does them. */
class FavesTest {
    private lateinit var fake: FakeAtproto
    private lateinit var account: Account
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A real key, from route-keys' vectors.
    private val key = "1K4RjYPAwNBJBHCDBBGCBGBOAUA.kbwpj6tSIEZ"

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
        Indexer.notifier = {}
        scope.cancel()
        fake.close()
    }

    // Each write tells the indexer its record's URI once, so it shows on hike sheets at once (website#160).
    @Test
    fun everyWriteNotifiesTheIndexer() = runBlocking {
        val heard = java.util.Collections.synchronizedList(mutableListOf<String>())
        Indexer.notifier = { heard += it }
        val fave = "at://${fake.did}/${Faves.NSID}/$key"
        Faves.set(account.xrpc, fake.did, key, true)
        Faves.set(account.xrpc, fake.did, key, false)
        assertEquals(listOf(fave, fave), heard)

        heard.clear()
        val record = kotlinx.serialization.json.buildJsonObject { put("\$type", kotlinx.serialization.json.JsonPrimitive(Posts.REVIEW)); put("text", kotlinx.serialization.json.JsonPrimitive("x")); put("createdAt", kotlinx.serialization.json.JsonPrimitive("2026-11-15T00:00:00.000Z")) }
        val made = Posts.create(account.xrpc, fake.did, Posts.REVIEW, record, rkey = "3lbaaaaaaaaaa")
        // A retry finding what a lost answer made tells it too.
        val found = Posts.find(account.xrpc, fake.did, Posts.REVIEW, "3lbaaaaaaaaaa")!!
        assertEquals(made.uri, found.uri)
        val p = Profiles.Profile(fake.did, null, fake.pdsOrigin, listOf(Profiles.Review(made.uri, made.cid, "x", "2026")), emptyList(), emptyList())
        Profiles.delete(account.xrpc, fake.did, p.deletion(p.reviews.single()))
        assertEquals(listOf(made.uri, made.uri, made.uri), heard)
    }

    private fun calls(nsid: String) = fake.pdsRequests.filter { it.url.encodedPath == "/xrpc/$nsid" }

    @Test
    fun setAndClear() = runBlocking {
        assertFalse(Faves.isFaved(account.xrpc, fake.did, key))

        Faves.set(account.xrpc, fake.did, key, true)
        // The record key is the route key; the record holds only when, as the website writes it.
        val stored = fake.records["${Faves.NSID}/$key"]!!
        assertEquals(setOf("\$type", "createdAt"), stored.keys)
        assertEquals(Faves.NSID, stored["\$type"]!!.jsonPrimitive.content)
        assertTrue(stored["createdAt"]!!.jsonPrimitive.content.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""")))
        val put = Json.parseToJsonElement(calls("com.atproto.repo.putRecord").single().body!!.utf8()).jsonObject
        assertEquals(fake.did, put["repo"]!!.jsonPrimitive.content)
        assertEquals(Faves.NSID, put["collection"]!!.jsonPrimitive.content)
        assertEquals(key, put["rkey"]!!.jsonPrimitive.content)
        assertTrue(Faves.isFaved(account.xrpc, fake.did, key))

        // Faving again replaces it: still one.
        Faves.set(account.xrpc, fake.did, key, true)
        assertEquals(1, fake.records.keys.count { it.startsWith("${Faves.NSID}/") })

        Faves.set(account.xrpc, fake.did, key, false)
        assertNull(fake.records["${Faves.NSID}/$key"])
        val del = Json.parseToJsonElement(calls("com.atproto.repo.deleteRecord").single().body!!.utf8()).jsonObject
        assertEquals(setOf("repo", "collection", "rkey"), del.keys)
        assertEquals(key, del["rkey"]!!.jsonPrimitive.content)
        assertFalse(Faves.isFaved(account.xrpc, fake.did, key))
    }

    @Test
    fun aPdsThatIsDownIsNotNotFaved() = runBlocking {
        fake.getRecordFails = 502
        try {
            Faves.isFaved(account.xrpc, fake.did, key)
            fail("a 502 read as not faved")
        } catch (e: IOException) {
            // Unknown, not "no".
        }
    }

    @Test
    fun signedOutCantFave() = runBlocking {
        account.signOut()
        try {
            Faves.set(account.xrpc, fake.did, key, true)
            fail("faved signed out")
        } catch (e: SignedOutException) {
            // The heart offers sign-in.
        }
    }

    @Test
    fun listedNewestFirstAcrossPages() = runBlocking {
        // 150 faves: two pages of the PDS's 100; listed by key there, by when here.
        for (i in 0 until 150) {
            val t = "2026-%02d-%02dT10:00:00.000Z".format(1 + i / 28, 1 + i % 28)
            fake.records["${Faves.NSID}/k$i.AAAAAAAAAAA"] = Json.parseToJsonElement("""{"${'$'}type":"${Faves.NSID}","createdAt":"$t"}""").jsonObject
        }
        // Another collection's records aren't faves.
        fake.records["${ProfileRecord.NSID}/self"] = Json.parseToJsonElement("""{"createdAt":"2027-01-01T00:00:00.000Z"}""").jsonObject
        val faves = Faves.list(account.xrpc, fake.did)
        assertEquals(150, faves.size)
        assertEquals("k149.AAAAAAAAAAA", faves.first().key)
        assertEquals("2026-06-10T10:00:00.000Z", faves.first().createdAt)
        assertEquals("k0.AAAAAAAAAAA", faves.last().key)
        assertEquals(faves.sortedByDescending { it.createdAt }, faves)
        val pages = calls("com.atproto.repo.listRecords")
        assertEquals(2, pages.size)
        assertEquals(listOf("100", "100"), pages.map { it.url.queryParameter("limit") })
        assertNull(pages[0].url.queryParameter("cursor"))
        assertEquals(Faves.NSID, pages[1].url.queryParameter("collection"))
    }

    @Test
    fun aFaveWithoutADateIsListedLast() = runBlocking {
        fake.records["${Faves.NSID}/a.AAAAAAAAAAA"] = Json.parseToJsonElement("""{"${'$'}type":"${Faves.NSID}"}""").jsonObject
        fake.records["${Faves.NSID}/b.AAAAAAAAAAA"] = Json.parseToJsonElement("""{"${'$'}type":"${Faves.NSID}","createdAt":"2026-01-01T00:00:00.000Z"}""").jsonObject
        assertEquals(
            listOf(Faves.Fave("b.AAAAAAAAAAA", "2026-01-01T00:00:00.000Z"), Faves.Fave("a.AAAAAAAAAAA", "")),
            Faves.list(account.xrpc, fake.did),
        )
    }
}
