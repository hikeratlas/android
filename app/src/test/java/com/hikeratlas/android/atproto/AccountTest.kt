package com.hikeratlas.android.atproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
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

/** Signing in, the session, and calls to the PDS, against a fake authorization server and PDS. */
class AccountTest {
    private lateinit var fake: FakeAtproto
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private val secrets = MemorySecretStore()
    private val keys = MemoryDpopKeys()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clock = System.currentTimeMillis()

    @Before
    fun setUp() {
        fake = FakeAtproto().start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        fake.close()
    }

    private fun account() = Account(http, secrets, keys, fake.config(), scope, now = { clock })

    private fun Account.awaitState(timeoutMs: Long = 5_000, ok: (AccountState) -> Boolean): AccountState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (ok(state.value)) return state.value
            Thread.sleep(10)
        }
        fail("state is ${state.value}")
        error("")
    }

    /** "Continue with Bluesky" through to signed in: PAR (after a nonce retry), the browser, the token exchange. */
    private fun signIn(account: Account): String = runBlocking {
        val page = account.beginSignIn(fake.authOrigin)
        val state = fake.pars.last()["state"]!!
        account.finishSignIn(fake.callback(state))
        page
    }

    @Test
    fun continueWithBluesky() = runBlocking {
        val account = account()
        account.awaitState { it == AccountState.Out() }
        val page = account.beginSignIn(fake.authOrigin)

        // PAR: once without the server's nonce (refused, use_dpop_nonce), then with it.
        assertEquals(1, fake.pars.size)
        val nonces = fake.proofs.map { it["nonce"]?.jsonPrimitive?.content }
        assertEquals(listOf(null, "as-1"), nonces)
        val par = fake.pars.single()
        assertEquals("https://www.hikeratlas.com/oauth-android-client-metadata.json", par["client_id"])
        assertEquals("com.hikeratlas.www:/oauth/callback", par["redirect_uri"])
        assertEquals("atproto include:com.hikeratlas.authFull blob:*/*", par["scope"])
        assertEquals("code", par["response_type"])
        assertEquals("S256", par["code_challenge_method"])
        assertFalse("Bluesky's page asks who", par.containsKey("login_hint"))

        // The browser opens the authorization endpoint with the pushed request.
        val url = page.toHttpUrl()
        assertEquals("/oauth/authorize", url.encodedPath)
        assertEquals(par["client_id"], url.queryParameter("client_id"))
        assertEquals("urn:ietf:params:oauth:request_uri:req-1", url.queryParameter("request_uri"))

        account.finishSignIn(fake.callback(par["state"]!!))
        val token = fake.tokenRequests.single()
        assertEquals("authorization_code", token["grant_type"])
        assertEquals("code-1", token["code"])
        // Signed in; the handle from describeRepo (through the PDS's own nonce
        // retry), the picture from the public Bluesky profile.
        assertEquals(AccountState.In(fake.did, fake.handle, fake.avatar), account.state.value)
        assertEquals(fake.did, account.did)
        assertTrue(fake.pdsRequests.any { it.url.encodedPath == "/xrpc/com.atproto.repo.describeRepo" && it.headers["Authorization"] == "DPoP at-1" })

        // The tokens are kept only encrypted in a real store; here, not in plain sight anywhere else.
        assertTrue(secrets.get("session")!!.contains("rt-1"))
        assertNull("the waiting sign-in is done", secrets.get("pending"))
    }

    @Test
    fun restoredAtLaunch() {
        val first = account()
        signIn(first)
        // A new process: the session is read back, with its handle and picture.
        val second = account()
        assertEquals(AccountState.In(fake.did, fake.handle, fake.avatar), second.awaitState { it is AccountState.In })
        val created = runBlocking {
            second.xrpc.procedure("com.atproto.repo.createRecord", buildJsonObject { put("repo", fake.did) })
        }
        assertEquals(fake.did, created["echo"]!!.jsonObject["repo"]!!.jsonPrimitive.content)
    }

    @Test
    fun signInByHandle() = runBlocking {
        val account = account()
        account.beginSignIn("@Alice.test ")
        val par = fake.pars.single()
        // The handle as typed (less the @), so the server's page knows who.
        assertEquals("Alice.test", par["login_hint"])
        account.finishSignIn(fake.callback(par["state"]!!))
        assertEquals(fake.did, account.did)
    }

    @Test
    fun unknownHandle() = runBlocking {
        val account = account()
        try {
            account.beginSignIn("nobody.test")
            fail("signed in as no one")
        } catch (e: SignInException) {
            assertEquals("Couldn't find nobody.test. Check the handle (e.g. you.example.com).", e.message)
        }
        assertTrue(fake.pars.isEmpty())
    }

    @Test
    fun wrongIssuerIsRefused() = runBlocking {
        val account = account()
        account.beginSignIn(fake.authOrigin)
        account.finishSignIn(fake.callback(fake.pars.single()["state"]!!, iss = "https://evil.example"))
        assertEquals(AccountState.Out(Account.FAILED), account.state.value)
        assertTrue("no code exchanged", fake.tokenRequests.isEmpty())
        assertTrue("its key is dropped", keys.aliases.isEmpty())
    }

    @Test
    fun anotherSignInsRedirectIsIgnored() = runBlocking {
        val account = account()
        account.beginSignIn(fake.authOrigin)
        account.finishSignIn(fake.callback("not-our-state"))
        assertTrue(fake.tokenRequests.isEmpty())
        assertEquals(AccountState.Out(), account.state.value)
        // The real one can still come back.
        account.finishSignIn(fake.callback(fake.pars.single()["state"]!!))
        assertEquals(fake.did, account.did)
    }

    @Test
    fun cancelledInTheBrowser() = runBlocking {
        val account = account()
        account.beginSignIn(fake.authOrigin)
        val state = fake.pars.single()["state"]!!
        account.finishSignIn("com.hikeratlas.www:/oauth/callback?error=access_denied&state=$state&iss=${FakeAtproto.enc(fake.authOrigin)}")
        assertEquals(AccountState.Out(), account.state.value)
        assertTrue(fake.tokenRequests.isEmpty())
    }

    /** The token's account must be one the authorization server speaks for: its PDS must name that server. */
    @Test
    fun accountOfAnotherServerIsRefused() = runBlocking {
        val account = account()
        fake.pdsAuthServer = "https://other.example"
        account.beginSignIn(fake.authOrigin)
        account.finishSignIn(fake.callback(fake.pars.single()["state"]!!))
        assertEquals(AccountState.Out(Account.FAILED), account.state.value)
        assertNull(secrets.get("session"))
        assertNull(account.did)
    }

    @Test
    fun pdsNonceRetry() = runBlocking {
        val account = account()
        signIn(account)
        fake.pdsNonce = "pds-2"
        val before = fake.pdsRequests.size
        val out = account.xrpc.upload("com.atproto.repo.uploadBlob", ByteArray(1234) { it.toByte() }, "application/gzip")
        assertEquals(1234, out["blob"]!!.jsonObject["size"]!!.jsonPrimitive.content.toInt())
        assertEquals("application/gzip", out["blob"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
        // Refused once for the old nonce, then sent again with the new one.
        val sent = fake.pdsRequests.drop(before)
        assertEquals(2, sent.size)
        assertEquals(1234L, sent.last().body!!.size.toLong())
        assertEquals("pds-2", fake.proofs.last()["nonce"]!!.jsonPrimitive.content)
    }

    @Test
    fun xrpcErrorsArePds() = runBlocking {
        val account = account()
        signIn(account)
        try {
            account.xrpc.query("com.atproto.repo.getRecord", mapOf("repo" to fake.did, "collection" to "x", "rkey" to "self"))
            fail("found a record")
        } catch (e: XrpcException) {
            assertEquals(400, e.status)
            assertEquals("RecordNotFound", e.error)
            assertEquals("Could not locate record", e.message)
        }
        val get = fake.pdsRequests.last()
        assertEquals("self", get.url.queryParameter("rkey"))
    }

    @Test
    fun refreshedWhenThePdsSaysExpired() = runBlocking {
        val account = account()
        signIn(account)
        fake.accessExpired = true
        account.xrpc.procedure("com.atproto.repo.createRecord", buildJsonObject { put("a", 1) })
        val refresh = fake.tokenRequests.last()
        assertEquals("refresh_token", refresh["grant_type"])
        assertEquals("rt-1", refresh["refresh_token"])
        assertEquals("DPoP at-2", fake.pdsRequests.last().headers["Authorization"])
        // The new tokens are kept: the old refresh token is spent.
        assertTrue(secrets.get("session")!!.contains("rt-2"))
        assertEquals(fake.did, account.did)
    }

    @Test
    fun refreshedBeforeExpiry() = runBlocking {
        val account = account()
        signIn(account)
        clock += 3_590_000 // 10 s left of the hour
        account.xrpc.query("com.atproto.repo.describeRepo", mapOf("repo" to fake.did))
        assertEquals("refresh_token", fake.tokenRequests.last()["grant_type"])
        assertEquals("DPoP at-2", fake.pdsRequests.last().headers["Authorization"])
    }

    @Test
    fun concurrentCallsRefreshOnce() = runBlocking {
        val account = account()
        signIn(account)
        fake.accessExpired = true
        val calls = (1..4).map {
            async(Dispatchers.IO) { account.xrpc.query("com.atproto.repo.describeRepo", mapOf("repo" to fake.did)) }
        }
        calls.forEach { it.await() }
        assertEquals(1, fake.tokenRequests.count { it["grant_type"] == "refresh_token" })
        assertEquals(fake.did, account.did)
    }

    @Test
    fun refusedRefreshSignsOut() = runBlocking {
        val account = account()
        signIn(account)
        fake.refreshFails = true
        fake.accessExpired = true
        try {
            account.xrpc.query("com.atproto.repo.describeRepo", mapOf("repo" to fake.did))
            fail("still signed in")
        } catch (e: SignedOutException) {
        }
        assertEquals(AccountState.Out(), account.state.value)
        assertNull(secrets.get("session"))
        assertTrue("its key is deleted", keys.aliases.isEmpty())
        // And at the next launch.
        assertEquals(AccountState.Out(), account().awaitState { it !is AccountState.Loading })
    }

    /** A revoked session found at launch (asking for the handle) reads as signed out. */
    @Test
    fun revokedSessionAtLaunch() {
        signIn(account())
        fake.refreshFails = true
        fake.accessExpired = true
        val next = account()
        next.awaitState { it == AccountState.Out() }
    }

    /** A session whose DPoP key is gone (another Account, a Keystore reset) reads as signed out. */
    @Test
    fun lostKeySignsOut() = runBlocking {
        val account = account()
        signIn(account)
        keys.aliases.toList().forEach(keys::delete)
        try {
            account.xrpc.query("com.atproto.repo.describeRepo", mapOf("repo" to fake.did))
            fail("used a session with no key")
        } catch (e: SignedOutException) {
        }
        assertEquals(AccountState.Out(), account.state.value)
        assertNull(account.did)
    }

    /** No network is not a revoked session: still signed in, to try again later. */
    @Test
    fun unreachableRefreshKeepsTheSession() = runBlocking {
        val account = account()
        signIn(account)
        clock += 4_000_000
        fake.auth.close()
        try {
            account.xrpc.query("com.atproto.repo.describeRepo", mapOf("repo" to fake.did))
            fail("refreshed with no server")
        } catch (e: IOException) {
        }
        assertEquals(fake.did, account.did)
        assertTrue(account.state.value is AccountState.In)
    }

    @Test
    fun signOutRevokes() = runBlocking {
        val account = account()
        signIn(account)
        account.signOut()
        assertEquals(AccountState.Out(), account.state.value)
        assertEquals(listOf("rt-1"), fake.revoked.toList())
        assertNull(secrets.get("session"))
        assertTrue(keys.aliases.isEmpty())
        try {
            account.xrpc.query("com.atproto.repo.describeRepo")
            fail("a call with no session")
        } catch (e: SignedOutException) {
        }
    }

    @Test
    fun publicReadsNeedNoSession() = runBlocking {
        // Someone else's records, from their PDS: no Authorization, no DPoP.
        fake.pds.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest) = FakeAtproto.json("""{"records":[],"auth":"${request.headers["Authorization"]}"}""")
        }
        val out = Xrpc.public(http, fake.pdsOrigin).query("com.atproto.repo.listRecords", mapOf("repo" to fake.did, "collection" to "x", "limit" to 100))
        assertEquals("null", out["auth"]!!.jsonPrimitive.content)
    }
}
