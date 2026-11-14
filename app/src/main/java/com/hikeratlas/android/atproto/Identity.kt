package com.hikeratlas.android.atproto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Where the app talks to the AT Protocol. The defaults are the live ones;
 * tests point them at fake servers (plain http on localhost, so [allowHttp]).
 */
data class AtConfig(
    /** The app's OAuth client metadata (website's oauth-android-client-metadata.json): its id to every PDS. */
    val clientId: String = "https://www.hikeratlas.com/oauth-android-client-metadata.json",
    val redirectUri: String = REDIRECT_URI,
    /** The website's OAUTH_SCOPE (app/atproto/scope.ts), exactly: the client metadata allows no more. */
    val scope: String = "atproto include:com.hikeratlas.authFull blob:*/*",
    /** "Continue with Bluesky" signs in here; its page asks who. */
    val bluesky: String = BLUESKY,
    /** Bluesky's public API: handles to DIDs, and profile pictures. */
    val publicApi: String = "https://public.api.bsky.app",
    val plcDirectory: String = "https://plc.directory",
    val allowHttp: Boolean = false,
) {
    companion object {
        const val BLUESKY = "https://bsky.social"
        const val REDIRECT_SCHEME = "com.hikeratlas.www"
        const val REDIRECT_URI = "$REDIRECT_SCHEME:/oauth/callback"
    }
}

/** A DID's document, as far as we need it: its PDS, and the handle it claims. */
data class DidDoc(val pds: String, val handle: String?)

private val HANDLE_RE = Regex("^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")
private val DID_RE = Regex("^did:(plc:[a-z2-7]{24}|web:[a-zA-Z0-9.-]+)$")

fun isHandle(h: String) = h.length <= 253 && HANDLE_RE.matches(h)
fun isDid(d: String) = DID_RE.matches(d)

/**
 * Finding an account, as the website's app/atproto/identity.ts: a handle to
 * its DID (its permanent id), and a DID to its PDS (the server holding its
 * data). Null where there's no such account; IOException where we couldn't
 * ask (offline).
 */
class Identity(private val http: OkHttpClient, private val config: AtConfig = AtConfig()) {
    /** A handle's DID, by Bluesky's public API (which follows the handle's DNS record or well-known file). */
    suspend fun resolveHandle(handle: String): String? {
        val h = handle.trim().removePrefix("@").lowercase()
        if (!isHandle(h)) return null
        val url = "${config.publicApi}/xrpc/com.atproto.identity.resolveHandle".toHttpUrl().newBuilder()
            .addQueryParameter("handle", h).build().toString()
        val did = getJson(url)?.get("did")?.jsonPrimitive?.contentOrNull
        return did?.takeIf(::isDid)
    }

    suspend fun describeDid(did: String): DidDoc? {
        if (!isDid(did)) return null
        val url = if (did.startsWith("did:plc:")) "${config.plcDirectory}/$did"
        else "https://${did.removePrefix("did:web:")}/.well-known/did.json"
        val doc = getJson(url) ?: return null
        return try {
            val pds = doc["service"]?.jsonArray?.map { it.jsonObject }?.firstOrNull {
                it["id"]?.jsonPrimitive?.content?.endsWith("#atproto_pds") == true &&
                    it["type"]?.jsonPrimitive?.content == "AtprotoPersonalDataServer"
            }?.get("serviceEndpoint")?.jsonPrimitive?.contentOrNull ?: return null
            if (!pds.startsWith("https://") && !(config.allowHttp && pds.startsWith("http://"))) return null
            val handle = doc["alsoKnownAs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.firstOrNull { it.startsWith("at://") }?.removePrefix("at://")
            DidDoc(pds.trimEnd('/'), handle)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** A JSON object; null on a 4xx or a body that isn't one. */
    private suspend fun getJson(url: String): JsonObject? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.code in 400..499) return@withContext null
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            try { JSON.parseToJsonElement(r.body.string()).jsonObject } catch (e: IllegalArgumentException) { null }
        }
    }
}
