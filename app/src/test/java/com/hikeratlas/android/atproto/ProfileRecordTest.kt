package com.hikeratlas.android.atproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.io.IOException

/** The profile record (actor.profile/self), against a fake PDS: made only when missing, and settings saved keeping its other fields. */
class ProfileRecordTest {
    private lateinit var fake: FakeAtproto
    private lateinit var account: Account
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = "${ProfileRecord.NSID}/self"

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

    private fun calls(nsid: String) = fake.pdsRequests.count { it.url.encodedPath == "/xrpc/$nsid" }
    private fun record(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun createdWhenMissing() = runBlocking {
        val got = ProfileRecord.ensure(account.xrpc, fake.did)!!
        val stored = fake.records[key]!!
        // createdAt only, as the website writes it: no settings until they're chosen.
        assertEquals(setOf("\$type", "createdAt"), stored.keys)
        assertEquals(ProfileRecord.NSID, stored["\$type"]!!.jsonPrimitive.content)
        assertTrue(stored["createdAt"]!!.jsonPrimitive.content.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""")))
        assertEquals(stored, got)
        val create = fake.pdsRequests.single { it.url.encodedPath == "/xrpc/com.atproto.repo.createRecord" }
        val body = record(create.body!!.utf8())
        assertEquals(fake.did, body["repo"]!!.jsonPrimitive.content)
        assertEquals("self", body["rkey"]!!.jsonPrimitive.content)
    }

    @Test
    fun aNewRecordTakesInitialSettings() = runBlocking {
        val units = record("""{"distance":"mi","elevation":"ft"}""")
        val got = ProfileRecord.ensure(account.xrpc, fake.did, mapOf("units" to units))!!
        val stored = fake.records[key]!!
        assertEquals(setOf("\$type", "createdAt", "units"), stored.keys)
        assertEquals(units, stored["units"])
        assertEquals(stored, got)
    }

    @Test
    fun keptWhenThere() = runBlocking {
        val existing = record("""{"${'$'}type":"${ProfileRecord.NSID}","createdAt":"2025-03-01T10:00:00.000Z","units":{"distance":"mi","elevation":"ft"},"license":"CC-BY-4.0"}""")
        fake.records[key] = existing
        assertEquals(existing, ProfileRecord.ensure(account.xrpc, fake.did))
        assertEquals(0, calls("com.atproto.repo.createRecord"))
        assertEquals(0, calls("com.atproto.repo.putRecord"))
        assertEquals(existing, fake.records[key])
    }

    @Test
    fun pdsDownMeansNextTime() = runBlocking {
        fake.getRecordFails = 502
        assertNull(ProfileRecord.ensure(account.xrpc, fake.did))
        assertEquals(0, calls("com.atproto.repo.createRecord"))
        assertNull(fake.records[key])
    }

    @Test
    fun savingUnitsKeepsCreatedAtAndLicense() = runBlocking {
        fake.records[key] = record("""{"${'$'}type":"${ProfileRecord.NSID}","createdAt":"2025-03-01T10:00:00.000Z","license":"hikeratlas-only"}""")
        ProfileRecord.saveSettings(account.xrpc, fake.did, mapOf("units" to record("""{"distance":"mi","elevation":"ft"}""")))
        assertEquals(
            record("""{"${'$'}type":"${ProfileRecord.NSID}","createdAt":"2025-03-01T10:00:00.000Z","license":"hikeratlas-only","units":{"distance":"mi","elevation":"ft"}}"""),
            fake.records[key],
        )
        // And again: the units replaced, the rest still kept.
        ProfileRecord.saveSettings(account.xrpc, fake.did, mapOf("units" to record("""{"distance":"km","elevation":"ft"}""")))
        val stored = fake.records[key]!!
        assertEquals("2025-03-01T10:00:00.000Z", stored["createdAt"]!!.jsonPrimitive.content)
        assertEquals("hikeratlas-only", stored["license"]!!.jsonPrimitive.content)
        assertEquals(record("""{"distance":"km","elevation":"ft"}"""), stored["units"])
    }

    @Test
    fun savingWithNoRecordMakesOne() = runBlocking {
        ProfileRecord.saveSettings(account.xrpc, fake.did, mapOf("units" to record("""{"distance":"mi","elevation":"m"}""")))
        val stored = fake.records[key]!!
        assertEquals(setOf("\$type", "createdAt", "units"), stored.keys)
    }

    @Test
    fun savingWhenItCantBeReadWritesNothing() = runBlocking {
        val existing = record("""{"${'$'}type":"${ProfileRecord.NSID}","createdAt":"2025-03-01T10:00:00.000Z","license":"CC-BY-4.0"}""")
        fake.records[key] = existing
        fake.getRecordFails = 503
        try {
            ProfileRecord.saveSettings(account.xrpc, fake.did, mapOf("units" to JsonObject(emptyMap())))
            fail("saved without reading")
        } catch (e: IOException) {
            // The licence would have been lost.
        }
        assertEquals(0, calls("com.atproto.repo.putRecord"))
        assertEquals(existing, fake.records[key])
    }
}
