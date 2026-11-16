package com.hikeratlas.android.atproto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal val JSON = Json { ignoreUnknownKeys = true }

/** An OAuth error from the authorization server: `error` and its description, e.g. invalid_grant. */
class OAuthException(val status: Int, val error: String?, description: String?) :
    Exception("OAuth: ${error ?: "HTTP $status"}${description?.let { ": $it" } ?: ""}")

/** The authorization server's metadata, the fields we use. */
@Serializable
data class AuthServer(
    val issuer: String,
    val authorization_endpoint: String,
    val token_endpoint: String,
    val pushed_authorization_request_endpoint: String? = null,
    val revocation_endpoint: String? = null,
    val dpop_signing_alg_values_supported: List<String> = emptyList(),
    val authorization_response_iss_parameter_supported: Boolean = false,
)

@Serializable
data class TokenResponse(
    val access_token: String,
    val token_type: String,
    val expires_in: Long? = null,
    val refresh_token: String? = null,
    val scope: String? = null,
    val sub: String? = null,
)

/**
 * The server's latest DPoP nonces, by origin: each server (authorization
 * server, PDS) hands out its own and rotates them, and a proof without the
 * current one is refused with use_dpop_nonce.
 */
class DpopNonces {
    private val nonces = ConcurrentHashMap<String, String>()
    operator fun get(url: String): String? = nonces[origin(url)]
    fun update(url: String, r: Response) {
        r.header("DPoP-Nonce")?.let { nonces[origin(url)] = it }
    }
}

internal fun origin(url: String): String {
    val u = url.toHttpUrl()
    val port = if (u.port == okhttp3.HttpUrl.defaultPort(u.scheme)) "" else ":${u.port}"
    return "${u.scheme}://${u.host}$port"
}

/**
 * atproto OAuth (https://atproto.com/specs/oauth), as a public client:
 * finding a PDS's authorization server, the pushed authorization request
 * (PAR, with PKCE), and the token requests, each with a DPoP proof. Keeps
 * no state but the servers' nonces; [Account] keeps the session.
 */
class OAuth(private val http: OkHttpClient, private val config: AtConfig, val nonces: DpopNonces = DpopNonces()) {
    /**
     * The authorization server for [service]: a PDS (its protected resource
     * metadata names its server), or an authorization server itself, as
     * bsky.social answers for "Continue with Bluesky".
     */
    suspend fun authServerFor(service: String): AuthServer {
        val issuer = issuerOfPds(service)
        return authServer(issuer ?: origin(service))
    }

    /** The authorization server a PDS names; null where it has no protected resource metadata. */
    suspend fun issuerOfPds(pds: String): String? {
        val meta = getJson("${origin(pds)}/.well-known/oauth-protected-resource") ?: return null
        return try {
            (meta["authorization_servers"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** An authorization server's metadata, checked as the spec has it. */
    suspend fun authServer(issuer: String): AuthServer {
        val url = "${origin(issuer)}/.well-known/oauth-authorization-server"
        val json = getJson(url) ?: throw IOException("No OAuth metadata at $url")
        val meta = try { JSON.decodeFromJsonElement(AuthServer.serializer(), json) } catch (e: IllegalArgumentException) {
            throw IOException("Bad OAuth metadata at $url", e)
        }
        // Its issuer is the origin it's served from (no one else's metadata),
        // and it does what an atproto client needs: PAR, DPoP with ES256,
        // and iss on the redirect.
        if (meta.issuer.trimEnd('/') != origin(issuer) || origin(meta.issuer) != meta.issuer.trimEnd('/')) {
            throw IOException("OAuth metadata at $url is for ${meta.issuer}")
        }
        if (!config.allowHttp && !meta.issuer.startsWith("https://")) throw IOException("Not https: ${meta.issuer}")
        if (meta.pushed_authorization_request_endpoint == null) throw IOException("${meta.issuer} has no PAR endpoint")
        if ("ES256" !in meta.dpop_signing_alg_values_supported) throw IOException("${meta.issuer} doesn't take ES256 DPoP")
        if (!meta.authorization_response_iss_parameter_supported) throw IOException("${meta.issuer} doesn't send iss")
        return meta
    }

    /** Pushes the authorization request; gives the request_uri for the browser. */
    suspend fun par(server: AuthServer, key: DpopKey, state: String, challenge: String, loginHint: String?): String {
        val form = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("response_type", "code")
            .add("redirect_uri", config.redirectUri)
            .add("scope", config.scope)
            .add("state", state)
            .add("code_challenge", challenge)
            .add("code_challenge_method", "S256")
            .apply { if (loginHint != null) add("login_hint", loginHint) }
            .build()
        val json = post(server.pushed_authorization_request_endpoint!!, key, form)
        return json["request_uri"]?.jsonPrimitive?.contentOrNull ?: throw IOException("PAR gave no request_uri")
    }

    /** Where the browser goes to sign in. */
    fun authorizeUrl(server: AuthServer, requestUri: String): String =
        server.authorization_endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", config.clientId)
            .addQueryParameter("request_uri", requestUri)
            .build().toString()

    suspend fun exchange(tokenEndpoint: String, key: DpopKey, code: String, verifier: String): TokenResponse =
        token(tokenEndpoint, key, FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", config.redirectUri)
            .add("code_verifier", verifier)
            .add("client_id", config.clientId)
            .build())

    suspend fun refresh(tokenEndpoint: String, key: DpopKey, refreshToken: String): TokenResponse =
        token(tokenEndpoint, key, FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", config.clientId)
            .build())

    suspend fun revoke(endpoint: String, key: DpopKey, token: String) {
        post(endpoint, key, FormBody.Builder().add("token", token).add("client_id", config.clientId).build())
    }

    private suspend fun token(endpoint: String, key: DpopKey, form: FormBody): TokenResponse {
        val json = post(endpoint, key, form)
        return try { JSON.decodeFromJsonElement(TokenResponse.serializer(), json) } catch (e: IllegalArgumentException) {
            throw IOException("Bad token response", e)
        }
    }

    /**
     * A form POST with a DPoP proof. A server that wants a newer nonce says
     * use_dpop_nonce, with the nonce in its DPoP-Nonce header: once, we
     * sign again with it.
     */
    private suspend fun post(url: String, key: DpopKey, form: FormBody): JsonObject = withContext(Dispatchers.IO) {
        var retried = false
        while (true) {
            val req = Request.Builder().url(url)
                .header("DPoP", Dpop.proof(key, "POST", url, nonces[url]))
                .post(form).build()
            http.newCall(req).sent().use { r ->
                nonces.update(url, r)
                val text = try { r.body.string() } catch (e: IOException) { throw NetworkException(e) }
                val json = try { JSON.parseToJsonElement(text) as? JsonObject } catch (e: IllegalArgumentException) { null }
                if (r.isSuccessful) return@withContext json ?: JsonObject(emptyMap())
                val error = json?.get("error")?.jsonPrimitive?.contentOrNull
                if (error == "use_dpop_nonce" && !retried && r.header("DPoP-Nonce") != null) {
                    retried = true
                } else if (r.code in 400..499) {
                    throw OAuthException(r.code, error, json?.get("error_description")?.jsonPrimitive?.contentOrNull)
                } else {
                    throw ServerException(r.code, url)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun getJson(url: String): JsonObject? = withContext(Dispatchers.IO) {
        if (url.toHttpUrlOrNull() == null) return@withContext null
        http.newCall(Request.Builder().url(url).build()).sent().use { r ->
            if (r.code in 400..499) return@withContext null
            if (!r.isSuccessful) throw ServerException(r.code, url)
            try { JSON.parseToJsonElement(r.body.string()).jsonObject } catch (e: IllegalArgumentException) { null }
        }
    }

    // OkHttp's IOExceptions here are all the request not getting through.
    private fun okhttp3.Call.sent(): okhttp3.Response = try { execute() } catch (e: IOException) { throw NetworkException(e) }
}
