package com.hikeratlas.android.atproto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * An XRPC error from a PDS: its HTTP status and the lexicon's `error`
 * (e.g. RecordNotFound) and `message`. Not an IOException: the server
 * answered, so trying again won't help (a 5xx aside).
 */
class XrpcException(val status: Int, val error: String?, message: String?) :
    Exception(message ?: error ?: "HTTP $status")

/**
 * A server answered with a 5xx: it's up, but had a problem. An IOException,
 * as before, so whatever took one as "try again later" still does; told
 * apart from [NetworkException] so it can be said as the server's problem,
 * not the phone's signal.
 */
class ServerException(val status: Int, what: String) : java.io.IOException("HTTP $status from $what")

/**
 * The request never got an answer: no network, a name that didn't resolve,
 * a connection refused, reset or cut short, a timeout, a TLS handshake that
 * didn't finish. What OkHttp throws as it sends, wrapped so it's told apart
 * from IOExceptions that aren't the network's (a bad answer, a file).
 */
class NetworkException(cause: java.io.IOException) : java.io.IOException(cause.message ?: cause.javaClass.simpleName, cause) {
    companion object {
        /** Whether [e] is one OkHttp throws for the network, in case one reaches us unwrapped. */
        fun isTransport(e: Throwable): Boolean = e is NetworkException || e is java.net.UnknownHostException ||
            e is java.net.SocketException || e is java.io.InterruptedIOException || e is javax.net.ssl.SSLException ||
            e is java.io.EOFException
    }
}

/**
 * No session: never signed in, signed out, or a session the server no
 * longer honours (revoked, or past its two weeks). Signing in again is the
 * way on.
 */
class SignedOutException : Exception("Not signed in")

/**
 * The session as [Xrpc] needs it: its tokens, kept fresh. Implemented by
 * [Account].
 */
internal interface XrpcAuth {
    /** The session's tokens, refreshed first if they're about to expire. */
    suspend fun fresh(): Account.Stored
    /** New tokens after [used] was refused; [SignedOutException] if the server won't give any. */
    suspend fun refresh(used: Account.Stored): Account.Stored
    fun key(s: Account.Stored): DpopKey
    val nonces: DpopNonces
}

/**
 * Calls to a PDS (com.atproto.repo.*, and so on): queries (GET),
 * procedures (POST, JSON or raw bytes such as uploadBlob's).
 *
 * Signed in ([Account.xrpc]), each request carries the session's access
 * token and a DPoP proof; a use_dpop_nonce answer is signed again with the
 * PDS's new nonce, and an expired token is refreshed and the request sent
 * again, once each. Without a session ([Xrpc.public]), it reads anyone's
 * public records from their PDS.
 *
 * Network trouble is a [NetworkException], a 5xx a [ServerException]
 * (both IOExceptions); an error the PDS gives, an [XrpcException]; a
 * session that's gone, [SignedOutException].
 */
class Xrpc internal constructor(
    private val http: OkHttpClient,
    private val service: String?,
    private val auth: XrpcAuth?,
) {
    /** A GET, e.g. query("com.atproto.repo.getRecord", mapOf("repo" to did, ...)). */
    suspend fun query(nsid: String, params: Map<String, Any?> = emptyMap()): JsonObject =
        json(send("GET", nsid, params, null))

    /** A GET that gives bytes, e.g. com.atproto.sync.getBlob. */
    suspend fun queryBytes(nsid: String, params: Map<String, Any?> = emptyMap()): ByteArray =
        send("GET", nsid, params, null)

    /** A POST with a JSON body (or none), e.g. com.atproto.repo.putRecord. */
    suspend fun procedure(nsid: String, input: JsonElement? = null): JsonObject =
        json(send("POST", nsid, emptyMap(), (input?.toString() ?: "").toRequestBody(if (input != null) JSON_TYPE else null)))

    /** A POST of raw bytes, e.g. com.atproto.repo.uploadBlob with a gzipped GPX file. */
    suspend fun upload(nsid: String, bytes: ByteArray, contentType: String): JsonObject =
        json(send("POST", nsid, emptyMap(), bytes.toRequestBody(contentType.toMediaType())))

    private fun json(bytes: ByteArray): JsonObject =
        if (bytes.isEmpty()) JsonObject(emptyMap())
        else JSON.parseToJsonElement(String(bytes)) as? JsonObject ?: JsonObject(emptyMap())

    private suspend fun send(method: String, nsid: String, params: Map<String, Any?>, body: RequestBody?): ByteArray {
        var session = auth?.fresh()
        var nonceRetried = false
        var refreshed = false
        while (true) {
            val base = session?.pds ?: service ?: throw SignedOutException()
            val url = "$base/xrpc/$nsid".toHttpUrl().newBuilder().apply {
                for ((k, v) in params) when (v) {
                    null -> {}
                    is Iterable<*> -> v.forEach { addQueryParameter(k, it.toString()) }
                    else -> addQueryParameter(k, v.toString())
                }
            }.build()
            val req = Request.Builder().url(url).method(method, body).apply {
                if (session != null && auth != null) {
                    header("Authorization", "DPoP ${session.accessToken}")
                    header("DPoP", Dpop.proof(auth.key(session), method, url.toString(), auth.nonces[url.toString()], session.accessToken))
                }
            }.build()
            val (code, bytes, wwwAuth) = withContext(Dispatchers.IO) {
                try {
                    http.newCall(req).execute().use { r ->
                        auth?.nonces?.update(url.toString(), r)
                        Triple(r.code, r.body.bytes(), r.header("WWW-Authenticate"))
                    }
                } catch (e: java.io.IOException) {
                    // Everything OkHttp throws here is the request not getting through.
                    throw NetworkException(e)
                }
            }
            if (code in 200..299) return bytes
            val err = try { JSON.parseToJsonElement(String(bytes)) as? JsonObject } catch (e: IllegalArgumentException) { null }
            val error = err?.get("error")?.jsonPrimitive?.contentOrNull
            if (session != null && auth != null) {
                // A PDS asks for a nonce with 401 and WWW-Authenticate: DPoP
                // error="use_dpop_nonce" (some with the error in the body).
                val wantsNonce = (code == 401 || code == 400) &&
                    (wwwAuth?.contains("use_dpop_nonce") == true || error == "use_dpop_nonce")
                if (wantsNonce && !nonceRetried) {
                    nonceRetried = true
                    continue
                }
                if (code == 401 && !wantsNonce && !refreshed) {
                    refreshed = true
                    session = auth.refresh(session)
                    continue
                }
            }
            if (code >= 500) throw ServerException(code, nsid)
            throw XrpcException(code, error, err?.get("message")?.jsonPrimitive?.contentOrNull)
        }
    }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()

        /** Reads from someone's PDS without a session (their public records and blobs). */
        fun public(http: OkHttpClient, pds: String) = Xrpc(http, pds.trimEnd('/'), null)
    }
}
