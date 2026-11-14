package com.hikeratlas.android.atproto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

/** Who's signed in, for the UI. */
sealed interface AccountState {
    /** Reading the saved session, at start. */
    data object Loading : AccountState
    /** Signed out; [error] says why a sign-in just failed, if one did. */
    data class Out(val error: String? = null) : AccountState
    /** Back from the browser, getting the session's tokens. */
    data object SigningIn : AccountState
    /** [avatar] is from the public Bluesky profile: null without one, or for an account not on Bluesky. */
    data class In(val did: String, val handle: String?, val avatar: String?) : AccountState
}

/** Signing in didn't work: [message] is what to tell the user. */
class SignInException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The signed-in AT Protocol account (Bluesky, or any PDS), as the website's
 * app/atproto/session.ts: sign in, the session kept on the phone and
 * restored at start, refreshed when needed, and sign out. A session the
 * server no longer honours (revoked, or past its two weeks) reads as
 * signed out.
 *
 * Signing in is two steps around the browser: [beginSignIn] gives the page
 * to open, and the browser comes back to the app's redirect URI, which goes
 * to [finishSignIn]. What's between them is saved, so the app can be killed
 * while the browser is open.
 */
class Account(
    private val http: OkHttpClient,
    private val secrets: SecretStore,
    private val keys: DpopKeys,
    private val config: AtConfig = AtConfig(),
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The session, as saved (encrypted) on the phone. */
    @Serializable
    data class Stored(
        val did: String,
        val handle: String? = null,
        val avatar: String? = null,
        val pds: String,
        val issuer: String,
        val tokenEndpoint: String,
        val revocationEndpoint: String? = null,
        val keyAlias: String,
        val accessToken: String,
        val refreshToken: String? = null,
        /** When the access token expires, ms since the epoch. */
        val expiresAt: Long,
    )

    /** A sign-in waiting on the browser. */
    @Serializable
    data class Pending(
        val state: String,
        val verifier: String,
        val issuer: String,
        val tokenEndpoint: String,
        val revocationEndpoint: String? = null,
        val keyAlias: String,
        /** The account asked for by handle: the token's sub must be it. */
        val did: String? = null,
    )

    private val oauth = OAuth(http, config)
    private val identity = Identity(http, config)
    private val _state = MutableStateFlow<AccountState>(AccountState.Loading)
    val state: StateFlow<AccountState> = _state

    @Volatile private var stored: Stored? = null
    private val lock = Mutex()
    private val restored: Deferred<Unit> = scope.async(Dispatchers.IO) {
        stored = read<Stored>(SESSION)
        _state.value = stored?.let { AccountState.In(it.did, it.handle, it.avatar) } ?: AccountState.Out()
    }

    init {
        // Its handle or picture may have changed; and asking finds a revoked session.
        scope.launch {
            restored.await()
            if (stored != null) updateProfile()
        }
    }

    /** The signed-in account's DID, or null. */
    val did: String? get() = stored?.did

    /** Calls to the signed-in account's PDS; they throw [SignedOutException] once there's no session. */
    val xrpc: Xrpc by lazy { Xrpc(http, null, auth) }

    /**
     * Starts signing in: at Bluesky ([AtConfig.BLUESKY], whose page asks
     * who), or at the PDS of a handle typed. Gives the page to open in the
     * browser.
     */
    suspend fun beginSignIn(who: String): String {
        restored.await()
        val bluesky = who == config.bluesky
        val typed = who.trim().removePrefix("@")
        val (server, did) = try {
            if (bluesky) {
                oauth.authServerFor(config.bluesky) to null
            } else {
                val did = if (isDid(typed)) typed else identity.resolveHandle(typed)
                val doc = did?.let { identity.describeDid(it) } ?: throw SignInException(notFound(typed))
                oauth.authServerFor(doc.pds) to did
            }
        } catch (e: IOException) {
            throw SignInException(FAILED, e)
        }
        val alias = "atproto-dpop-${randomToken(9)}"
        val key = keys.create(alias)
        val state = randomToken()
        val verifier = randomToken()
        val requestUri = try {
            oauth.par(server, key, state, b64url(sha256(verifier.toByteArray())), loginHint = if (bluesky) null else typed)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            keys.delete(alias)
            throw SignInException(FAILED, e)
        }
        // One sign-in at a time: an earlier one left in the browser is dropped.
        read<Pending>(PENDING)?.let { keys.delete(it.keyAlias) }
        write(PENDING, Pending(state, verifier, server.issuer, server.token_endpoint, server.revocation_endpoint, alias, did))
        return oauth.authorizeUrl(server, requestUri)
    }

    /** Whether [uri] is the browser coming back from signing in. */
    fun isCallback(uri: String): Boolean = uri.startsWith(config.redirectUri)

    /** [finishSignIn] in the background, for the activity. */
    fun completeSignIn(uri: String) {
        scope.launch { finishSignIn(uri) }
    }

    /**
     * The browser's redirect: checks it's ours (`state`) and from the server
     * we sent the user to (`iss`), gets the tokens, and checks the account
     * they're for is one that server holds. Ignored if no sign-in is
     * waiting (an old intent, delivered again).
     */
    suspend fun finishSignIn(uri: String) {
        restored.await()
        val pending = read<Pending>(PENDING) ?: return
        val q = query(uri)
        if (q["state"] != pending.state) {
            // Not this sign-in's: perhaps an earlier one. The current one may still come back.
            return
        }
        secrets.remove(PENDING)
        val before = stored
        _state.value = AccountState.SigningIn
        try {
            q["error"]?.let { error ->
                keys.delete(pending.keyAlias)
                // Cancelled in the browser: nothing to say.
                _state.value = AccountState.Out(if (error == "access_denied") null else FAILED)
                return
            }
            if (q["iss"] != pending.issuer) throw SignInException(FAILED, IOException("iss ${q["iss"]} isn't ${pending.issuer}"))
            val code = q["code"] ?: throw SignInException(FAILED, IOException("no code"))
            val key = keys.load(pending.keyAlias) ?: throw SignInException(FAILED, IOException("DPoP key gone"))
            val t = oauth.exchange(pending.tokenEndpoint, key, code, pending.verifier)
            val sub = t.sub
            if (!t.token_type.equals("DPoP", ignoreCase = true)) throw IOException("token_type ${t.token_type}")
            if (t.scope?.split(' ')?.contains("atproto") != true) throw IOException("scope ${t.scope}")
            if (sub == null || !isDid(sub)) throw IOException("sub $sub")
            if (pending.did != null && sub != pending.did) throw IOException("sub $sub isn't ${pending.did}")
            // The account must be one this authorization server speaks for:
            // its DID's PDS names it.
            val doc = identity.describeDid(sub) ?: throw IOException("can't resolve $sub")
            val issuer = oauth.issuerOfPds(doc.pds) ?: throw IOException("${doc.pds} names no authorization server")
            if (origin(oauth.authServer(issuer).issuer) != origin(pending.issuer)) {
                throw IOException("$sub's PDS uses $issuer, not ${pending.issuer}")
            }
            before?.let { forget(it) }
            val s = Stored(
                did = sub, handle = doc.handle, pds = doc.pds, issuer = pending.issuer,
                tokenEndpoint = pending.tokenEndpoint, revocationEndpoint = pending.revocationEndpoint,
                keyAlias = pending.keyAlias, accessToken = t.access_token, refreshToken = t.refresh_token,
                expiresAt = expiry(t),
            )
            save(s)
            updateProfile()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            keys.delete(pending.keyAlias)
            _state.value = before?.let { AccountState.In(it.did, it.handle, it.avatar) }
                ?: AccountState.Out((e as? SignInException)?.message ?: FAILED)
        }
    }

    /** Signs out here, and asks the server to end the session (it's gone here whether or not it can). */
    suspend fun signOut() {
        restored.await()
        val s = lock.withLock {
            stored.also { drop() }
        } ?: return
        try {
            val key = keys.load(s.keyAlias)
            val token = s.refreshToken ?: s.accessToken
            if (key != null && s.revocationEndpoint != null) oauth.revoke(s.revocationEndpoint, key, token)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        } finally {
            keys.delete(s.keyAlias)
        }
    }

    /** Clears an error shown after a failed sign-in. */
    fun clearError() {
        val s = _state.value
        if (s is AccountState.Out && s.error != null) _state.value = AccountState.Out()
    }

    /** The handle (describeRepo, as the website) and the Bluesky profile picture, saved for next time. */
    private suspend fun updateProfile() {
        val s = stored ?: return
        val handle = try {
            xrpc.query("com.atproto.repo.describeRepo", mapOf("repo" to s.did))["handle"]?.jsonPrimitive?.contentOrNull
        } catch (e: SignedOutException) {
            return
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val avatar = avatarOf(s.did)
        lock.withLock {
            val cur = stored ?: return
            if (cur.did != s.did) return
            save(cur.copy(handle = handle ?: cur.handle, avatar = avatar.getOrDefault(cur.avatar)))
        }
    }

    /** From the public Bluesky profile: null without one; a failure if we couldn't ask. */
    private suspend fun avatarOf(did: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val url = "${config.publicApi}/xrpc/app.bsky.actor.getProfile".toHttpUrl().newBuilder()
                .addQueryParameter("actor", did).build()
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                when {
                    r.isSuccessful -> Result.success((JSON.parseToJsonElement(r.body.string()) as? kotlinx.serialization.json.JsonObject)
                        ?.get("avatar")?.jsonPrimitive?.contentOrNull)
                    r.code in 400..499 -> Result.success(null)
                    else -> Result.failure(IOException("HTTP ${r.code}"))
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private val auth = object : XrpcAuth {
        override val nonces get() = oauth.nonces

        // A session whose key is gone can't be used again: signed out, as
        // for a refused refresh, rather than signed in to nothing.
        override fun key(s: Stored) = keys.load(s.keyAlias) ?: run {
            if (stored?.keyAlias == s.keyAlias) drop()
            throw SignedOutException()
        }

        override suspend fun fresh(): Stored {
            restored.await()
            val s = stored ?: throw SignedOutException()
            return if (s.expiresAt - now() < EARLY_MS) refresh(s) else s
        }

        // One refresh at a time: a refresh token works once, so two at once
        // would leave one with a spent token (and the server may then end the
        // session). A caller whose tokens another refresh already replaced
        // gets those.
        override suspend fun refresh(used: Stored): Stored = lock.withLock {
            val s = stored ?: throw SignedOutException()
            if (s.accessToken != used.accessToken) return@withLock s
            val refreshToken = s.refreshToken ?: run { drop(); throw SignedOutException() }
            val key = keys.load(s.keyAlias) ?: run { drop(); throw SignedOutException() }
            val t = try {
                oauth.refresh(s.tokenEndpoint, key, refreshToken)
            } catch (e: OAuthException) {
                // Revoked, expired, or otherwise refused: signed out. (A 5xx
                // or no network is an IOException, and keeps the session.)
                drop()
                keys.delete(s.keyAlias)
                throw SignedOutException()
            }
            if (t.sub != null && t.sub != s.did) {
                drop()
                keys.delete(s.keyAlias)
                throw SignedOutException()
            }
            s.copy(accessToken = t.access_token, refreshToken = t.refresh_token ?: s.refreshToken, expiresAt = expiry(t))
                .also(::save)
        }
    }

    private fun expiry(t: TokenResponse) = now() + (t.expires_in ?: DEFAULT_EXPIRES_S) * 1000

    private fun save(s: Stored) {
        write(SESSION, s)
        stored = s
        _state.value = AccountState.In(s.did, s.handle, s.avatar)
    }

    private fun drop() {
        secrets.remove(SESSION)
        stored = null
        _state.value = AccountState.Out()
    }

    /** A session replaced by a new sign-in: its key goes, and the server is told (in the background). */
    private fun forget(s: Stored) {
        scope.launch {
            try {
                val key = keys.load(s.keyAlias)
                if (key != null && s.revocationEndpoint != null) oauth.revoke(s.revocationEndpoint, key, s.refreshToken ?: s.accessToken)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            } finally {
                keys.delete(s.keyAlias)
            }
        }
    }

    private inline fun <reified T> read(name: String): T? = secrets.get(name)?.let {
        try { JSON.decodeFromString<T>(it) } catch (e: IllegalArgumentException) { null }
    }

    private inline fun <reified T> write(name: String, value: T) = secrets.put(name, JSON.encodeToString(value))

    companion object {
        private const val SESSION = "session"
        private const val PENDING = "pending"
        /** Refreshed this long before the access token expires, so a request doesn't race it. */
        private const val EARLY_MS = 60_000L
        private const val DEFAULT_EXPIRES_S = 300L
        const val FAILED = "Signing in didn't work. Try again?"
        fun notFound(handle: String) = "Couldn't find $handle. Check the handle (e.g. you.example.com)."

        /** The redirect's query: code, state, iss (or error). */
        internal fun query(uri: String): Map<String, String> {
            val raw = try { URI(uri).rawQuery } catch (e: Exception) { null } ?: return emptyMap()
            return raw.split('&').filter { it.isNotEmpty() }.associate {
                val k = it.substringBefore('=')
                val v = it.substringAfter('=', "")
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
        }
    }
}
