package com.hikeratlas.android.atproto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.math.BigInteger
import java.net.URLDecoder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake authorization server (with Bluesky's public API and a PLC
 * directory on the same host) and a fake PDS, as atproto OAuth has them:
 * PAR, the token endpoint and the PDS all want DPoP proofs with their own
 * current nonce, which they check, signatures and all.
 */
class FakeAtproto : AutoCloseable {
    val did = "did:plc:abcdefghijklmnopqrstuvwx"
    val handle = "alice.test"
    val avatar = "https://cdn.example/avatar.jpg"

    val auth = MockWebServer()
    val pds = MockWebServer()
    val authOrigin get() = auth.url("/").toString().trimEnd('/')
    val pdsOrigin get() = pds.url("/").toString().trimEnd('/')

    /** The servers' current nonces; change them to make the client retry. */
    @Volatile var authNonce = "as-1"
    @Volatile var pdsNonce = "pds-1"
    /** The authorization server the PDS names (another one: a sign-in it can't vouch for). */
    @Volatile var pdsAuthServer: String? = null
    @Volatile var refreshFails = false
    /** The PDS refuses the current access token as expired. */
    @Volatile var accessExpired = false
    @Volatile var expiresIn = 3600L

    @Volatile var accessToken = "at-1"
    @Volatile var refreshToken = "rt-1"
    private var issued = 1

    /**
     * The account's records that have rkeys, by "collection/rkey": getRecord
     * and listRecords read them, createRecord and putRecord write them,
     * deleteRecord removes them.
     */
    val records = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
    /** Records' CIDs once putRecord has replaced them, by "collection/rkey" (before that, "bafy-<key>"). */
    private val cids = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val versions = java.util.concurrent.atomic.AtomicInteger()
    /** The CID a record has now: a new one each time putRecord writes it, as a real PDS's. */
    fun cidOf(key: String) = cids[key] ?: "bafy-$key"
    /** Blobs uploaded: their Content-Type and bytes. */
    val blobs = CopyOnWriteArrayList<Pair<String?, ByteArray>>()
    /** uploadBlob answers with this blob (JSON) instead of one made up from the upload. */
    @Volatile var blobAnswer: String? = null
    /** createRecord answers with this HTTP status and error body instead. */
    @Volatile var createFails: Pair<Int, String>? = null
    private val tids = java.util.concurrent.atomic.AtomicInteger()
    /** The PDS's getRecord answers with this HTTP status (a 5xx: it's down) instead. */
    @Volatile var getRecordFails: Int? = null
    /** The PDS's listRecords answers with this HTTP status instead. */
    @Volatile var listFails: Int? = null
    /**
     * Answers a signed-in PDS request with this instead, when it gives one
     * (a 503: no signal, as the app takes it), before doing anything.
     */
    @Volatile var fault: ((RecordedRequest) -> MockResponse?)? = null
    /** Does what a request asks, then answers 503, as when the connection drops before the answer comes. */
    @Volatile var loseAnswer: ((RecordedRequest) -> Boolean)? = null
    /** Blobs getBlob serves, by CID (uploads go here too). */
    val blobsByCid = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    val pars = CopyOnWriteArrayList<Map<String, String>>()
    val tokenRequests = CopyOnWriteArrayList<Map<String, String>>()
    val revoked = CopyOnWriteArrayList<String>()
    val pdsRequests = CopyOnWriteArrayList<RecordedRequest>()
    /** Each request's DPoP proof's payload, after checking it. */
    val proofs = CopyOnWriteArrayList<JsonObject>()
    private var parJwk: JsonObject? = null
    private var challenge: String? = null

    fun start(): FakeAtproto {
        auth.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = authServer(request)
        }
        pds.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = pdsServer(request)
        }
        auth.start()
        pds.start()
        return this
    }

    fun config() = AtConfig(
        clientId = "https://www.hikeratlas.com/oauth-android-client-metadata.json",
        bluesky = authOrigin,
        publicApi = authOrigin,
        plcDirectory = "$authOrigin/plc",
        allowHttp = true,
    )

    /** The browser's redirect back, as the authorization server would send it. */
    fun callback(state: String, iss: String = authOrigin, code: String = "code-1") =
        "com.hikeratlas.www:/oauth/callback?iss=${enc(iss)}&state=${enc(state)}&code=$code"

    private fun authServer(r: RecordedRequest): MockResponse {
        val path = r.url.encodedPath
        return when {
            path == "/.well-known/oauth-protected-resource" ->
                json("""{"resource":"$authOrigin","authorization_servers":["$authOrigin"]}""")
            path == "/.well-known/oauth-authorization-server" -> json(
                """{"issuer":"$authOrigin","authorization_endpoint":"$authOrigin/oauth/authorize",
                "token_endpoint":"$authOrigin/oauth/token","pushed_authorization_request_endpoint":"$authOrigin/oauth/par",
                "revocation_endpoint":"$authOrigin/oauth/revoke","require_pushed_authorization_requests":true,
                "dpop_signing_alg_values_supported":["ES256"],"authorization_response_iss_parameter_supported":true,
                "scopes_supported":["atproto"],"client_id_metadata_document_supported":true}""",
            )
            path == "/plc/$did" -> json(
                """{"id":"$did","alsoKnownAs":["at://$handle"],"service":[{"id":"#atproto_pds",
                "type":"AtprotoPersonalDataServer","serviceEndpoint":"$pdsOrigin"}]}""",
            )
            path.startsWith("/plc/") -> MockResponse.Builder().code(404).build()
            path == "/xrpc/com.atproto.identity.resolveHandle" ->
                if (r.url.queryParameter("handle") == handle) json("""{"did":"$did"}""")
                else json("""{"error":"InvalidRequest","message":"Unable to resolve handle"}""", 400)
            path == "/xrpc/app.bsky.actor.getProfile" -> json("""{"did":"$did","handle":"$handle","avatar":"$avatar"}""")
            path == "/oauth/par" -> withNonce(r) {
                val form = form(r)
                pars += form
                parJwk = it
                challenge = form["code_challenge"]
                json("""{"request_uri":"urn:ietf:params:oauth:request_uri:req-${pars.size}","expires_in":299}""", 201)
            }
            path == "/oauth/token" -> withNonce(r) { jwk ->
                val form = form(r)
                tokenRequests += form
                when (form["grant_type"]) {
                    "authorization_code" -> {
                        check(jwk == parJwk) { "token request signed by another key" }
                        check(b64url(sha256(form["code_verifier"]!!.toByteArray())) == challenge) { "PKCE verifier doesn't match" }
                        if (form["code"] != "code-1") json("""{"error":"invalid_grant"}""", 400) else tokens()
                    }
                    "refresh_token" ->
                        if (refreshFails || form["refresh_token"] != refreshToken) json("""{"error":"invalid_grant","error_description":"refresh token revoked"}""", 400)
                        else {
                            issued++
                            accessToken = "at-$issued"
                            refreshToken = "rt-$issued"
                            accessExpired = false
                            tokens()
                        }
                    else -> json("""{"error":"unsupported_grant_type"}""", 400)
                }
            }
            path == "/oauth/revoke" -> withNonce(r) {
                revoked += form(r)["token"]!!
                MockResponse.Builder().code(200).build()
            }
            else -> MockResponse.Builder().code(404).build()
        }
    }

    private fun tokens() = json(
        """{"access_token":"$accessToken","token_type":"DPoP","expires_in":$expiresIn,"refresh_token":"$refreshToken",
        "scope":"atproto include:com.hikeratlas.authFull blob:*/*","sub":"$did"}""",
    )

    private fun pdsServer(r: RecordedRequest): MockResponse {
        val path = r.url.encodedPath
        if (path == "/.well-known/oauth-protected-resource") {
            return json("""{"resource":"$pdsOrigin","authorization_servers":["${pdsAuthServer ?: authOrigin}"]}""")
        }
        pdsRequests += r
        // As a PDS: public records and blobs need no session.
        if (r.headers["Authorization"] == null && r.headers["DPoP"] == null) {
            return publicRead(r) ?: json("""{"error":"AuthMissing"}""", 401)
        }
        val token = r.headers["Authorization"]?.removePrefix("DPoP ")
        val proof = r.headers["DPoP"] ?: return json("""{"error":"AuthMissing"}""", 401)
        val payload = verify(proof, r)
        if (payload["nonce"]?.jsonPrimitive?.content != pdsNonce) {
            return MockResponse.Builder().code(401)
                .addHeader("WWW-Authenticate", """DPoP algs="ES256", error="use_dpop_nonce", error_description="Resource server requires nonce in DPoP proof"""")
                .addHeader("DPoP-Nonce", pdsNonce)
                .body("""{"error":"use_dpop_nonce","message":"Resource server requires nonce in DPoP proof"}""").build()
        }
        check(payload["ath"]?.jsonPrimitive?.content == b64url(sha256(token!!.toByteArray()))) { "ath isn't the token's" }
        if (token != accessToken || accessExpired) {
            return MockResponse.Builder().code(401)
                .addHeader("WWW-Authenticate", """DPoP algs="ES256", error="invalid_token", error_description="\"exp\" claim timestamp check failed"""")
                .addHeader("DPoP-Nonce", pdsNonce)
                .body("""{"error":"InvalidToken","message":"\"exp\" claim timestamp check failed"}""").build()
        }
        fault?.invoke(r)?.let { return it }
        val answer = publicRead(r) ?: write(r, path)
        return if (loseAnswer?.invoke(r) == true) json("""{"error":"InternalServerError"}""", 503) else answer
    }

    private fun write(r: RecordedRequest, path: String): MockResponse {
        return when (path) {
            "/xrpc/com.atproto.repo.createRecord" -> {
                val body = Json.parseToJsonElement(r.body!!.utf8()).jsonObject
                val rkey = body["rkey"]?.jsonPrimitive?.content
                val key = "${body["collection"]?.jsonPrimitive?.content}/$rkey"
                val invalid = body["record"]?.jsonObject?.let(::invalidPhotos)
                when {
                    createFails != null -> json(createFails!!.second, createFails!!.first)
                    invalid != null -> json("""{"error":"InvalidRecord","message":"Invalid record: $invalid"}""", 400)
                    // As a PDS: a TID it makes (here, in order), kept like any other record.
                    rkey == null -> {
                        val tid = "3tid${tids.incrementAndGet()}"
                        val k = "${body["collection"]?.jsonPrimitive?.content}/$tid"
                        body["record"]?.let { records[k] = it.jsonObject }
                        json("""{"uri":"at://$did/$k","cid":"bafy-$k","echo":$body}""")
                    }
                    // As a PDS: create never replaces.
                    records.containsKey(key) -> json("""{"error":"InvalidRequest","message":"Record already exists"}""", 400)
                    else -> {
                        records[key] = body["record"]!!.jsonObject
                        json("""{"uri":"at://$did/$key","cid":"bafy-$key"}""")
                    }
                }
            }
            "/xrpc/com.atproto.repo.putRecord" -> {
                val body = Json.parseToJsonElement(r.body!!.utf8()).jsonObject
                val key = "${body["collection"]!!.jsonPrimitive.content}/${body["rkey"]!!.jsonPrimitive.content}"
                val swap = body["swapRecord"]?.jsonPrimitive?.content
                val invalid = body["record"]?.jsonObject?.let(::invalidPhotos)
                when {
                    // As a PDS: swapRecord must be the record's current CID (and the record there).
                    swap != null && (!records.containsKey(key) || cidOf(key) != swap) ->
                        json("""{"error":"InvalidSwap","message":"Record was at ${if (records.containsKey(key)) cidOf(key) else "null"}"}""", 400)
                    invalid != null -> json("""{"error":"InvalidRecord","message":"Invalid record: $invalid"}""", 400)
                    else -> {
                        records[key] = body["record"]!!.jsonObject
                        val cid = "bafy-$key-v${versions.incrementAndGet()}"
                        cids[key] = cid
                        json("""{"uri":"at://$did/$key","cid":"$cid"}""")
                    }
                }
            }
            "/xrpc/com.atproto.repo.deleteRecord" -> {
                // As a PDS: deleting a record that isn't there is fine.
                val body = Json.parseToJsonElement(r.body!!.utf8()).jsonObject
                val key = "${body["collection"]!!.jsonPrimitive.content}/${body["rkey"]!!.jsonPrimitive.content}"
                records.remove(key)
                cids.remove(key)
                json("{}")
            }
            "/xrpc/com.atproto.repo.uploadBlob" -> {
                val bytes = r.body!!.toByteArray()
                blobs += r.headers["Content-Type"] to bytes
                blobsByCid["bafkrei-${blobs.size}"] = bytes
                json(
                    """{"blob":${blobAnswer ?: """{"${'$'}type":"blob","ref":{"${'$'}link":"bafkrei-${blobs.size}"},"mimeType":"${r.headers["Content-Type"]}","size":${bytes.size}}"""}}""",
                )
            }
            else -> json("""{"error":"MethodNotImplemented"}""", 501)
        }
    }

    /**
     * What's wrong with a record's photos by the lexicon (defs#photo, and the
     * review's and track's photos arrays), as a PDS that validates records
     * would refuse it; null when they're fine.
     */
    private fun invalidPhotos(record: JsonObject): String? {
        val photos = record["photos"] as? kotlinx.serialization.json.JsonArray ?: return null
        val max = if (record["\$type"]?.jsonPrimitive?.content == "com.hikeratlas.temp.review") 20 else 50
        if (photos.size > max) return "more than $max photos"
        for (p in photos.map { it.jsonObject }) {
            val image = p["image"]?.jsonObject ?: return "a photo without its image"
            if (image["mimeType"]?.jsonPrimitive?.content !in listOf("image/jpeg", "image/png", "image/webp")) return "a photo of type ${image["mimeType"]}"
            if (image["size"]!!.jsonPrimitive.long > 2_000_000) return "a photo over 2 MB"
            p["alt"]?.let { if (it.jsonPrimitive.content.length > 1000) return "a description over 1000 graphemes" }
            p["at"]?.jsonObject?.let { at ->
                for (k in listOf("lat", "lon")) if (at[k]?.jsonPrimitive?.content?.toLongOrNull() == null) return "at.$k not an integer"
            }
            val extra = p.keys - setOf("image", "alt", "takenAt", "at")
            if (extra.isNotEmpty()) return "unknown photo fields $extra"
        }
        return null
    }

    /** Reads anyone may make, signed in or not: describeRepo, getRecord, listRecords, getBlob; null for any other. */
    private fun publicRead(r: RecordedRequest): MockResponse? = when (r.url.encodedPath) {
        "/xrpc/com.atproto.repo.describeRepo" -> json("""{"handle":"$handle","did":"$did"}""")
        "/xrpc/com.atproto.repo.getRecord" -> {
            val key = "${r.url.queryParameter("collection")}/${r.url.queryParameter("rkey")}"
            val value = records[key]
            when {
                getRecordFails != null -> json("""{"error":"InternalServerError"}""", getRecordFails!!)
                r.url.queryParameter("repo") == did && value != null ->
                    json("""{"uri":"at://$did/$key","cid":"${cidOf(key)}","value":$value}""")
                else -> json("""{"error":"RecordNotFound","message":"Could not locate record"}""", 400)
            }
        }
        "/xrpc/com.atproto.repo.listRecords" -> {
            // By record key, as a PDS lists them; the cursor is the last key given.
            val collection = r.url.queryParameter("collection")!!
            val limit = r.url.queryParameter("limit")?.toInt() ?: 50
            val after = r.url.queryParameter("cursor")
            val page = records.keys.filter { it.startsWith("$collection/") }.map { it.removePrefix("$collection/") }
                .sorted().filter { after == null || it > after }.take(limit)
            val list = page.joinToString(",") { k ->
                """{"uri":"at://$did/$collection/$k","cid":"${cidOf("$collection/$k")}","value":${records["$collection/$k"]}}"""
            }
            val cursor = if (page.size == limit) ""","cursor":"${page.last()}"""" else ""
            if (listFails != null) json("""{"error":"InternalServerError"}""", listFails!!)
            else json("""{"records":[$list]$cursor}""")
        }
        "/xrpc/com.atproto.sync.getBlob" -> {
            val bytes = blobsByCid[r.url.queryParameter("cid")]
            if (r.url.queryParameter("did") == did && bytes != null) MockResponse.Builder().code(200).body(okio.Buffer().write(bytes)).build()
            else json("""{"error":"BlobNotFound","message":"Blob not found"}""", 400)
        }
        else -> null
    }

    /** Answers use_dpop_nonce unless the proof carries the current nonce; else [ok], given the proof's key. */
    private fun withNonce(r: RecordedRequest, ok: (JsonObject) -> MockResponse): MockResponse {
        val proof = r.headers["DPoP"] ?: return json("""{"error":"invalid_dpop_proof"}""", 400)
        val payload = verify(proof, r)
        if (payload["nonce"]?.jsonPrimitive?.content != authNonce) {
            return json("""{"error":"use_dpop_nonce","error_description":"Authorization server requires nonce in DPoP proof"}""", 400)
                .newBuilder().addHeader("DPoP-Nonce", authNonce).build()
        }
        check(payload["ath"] == null) { "no ath for the authorization server" }
        return ok(header(proof)["jwk"]!!.jsonObject).newBuilder().addHeader("DPoP-Nonce", authNonce).build()
    }

    /** Checks a DPoP proof as a server would: its signature by its own key, its method and URL. */
    private fun verify(proof: String, r: RecordedRequest): JsonObject {
        val (h, p, s) = proof.split('.')
        val header = header(proof)
        check(header["typ"]!!.jsonPrimitive.content == "dpop+jwt")
        check(header["alg"]!!.jsonPrimitive.content == "ES256")
        val jwk = header["jwk"]!!.jsonObject
        check(!jwk.containsKey("d")) { "private key in the proof" }
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, unb64(jwk["x"]!!.jsonPrimitive.content)), BigInteger(1, unb64(jwk["y"]!!.jsonPrimitive.content)))
        val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update("$h.$p".toByteArray())
            verify(joseToDer(unb64(s)))
        }
        check(ok) { "bad DPoP signature" }
        val payload = Json.parseToJsonElement(String(unb64(p))).jsonObject
        check(payload["htm"]!!.jsonPrimitive.content == r.method)
        val url = r.url.newBuilder().query(null).build().toString()
        check(payload["htu"]!!.jsonPrimitive.content == url) { "htu ${payload["htu"]} isn't $url" }
        check(payload["jti"]!!.jsonPrimitive.content.length >= 16)
        check(Math.abs(payload["iat"]!!.jsonPrimitive.long - System.currentTimeMillis() / 1000) < 60)
        proofs += payload
        return payload
    }

    private fun header(proof: String) = Json.parseToJsonElement(String(unb64(proof.substringBefore('.')))).jsonObject

    override fun close() {
        auth.close()
        pds.close()
    }

    companion object {
        fun json(body: String, code: Int = 200) =
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

        fun form(r: RecordedRequest): Map<String, String> = r.body!!.utf8().split('&').associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
        fun unb64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

        /** r and s, as JWS has them, back to the DER Java verifies. */
        fun joseToDer(sig: ByteArray): ByteArray {
            fun int(b: ByteArray): ByteArray {
                val v = BigInteger(1, b).toByteArray()
                return byteArrayOf(0x02, v.size.toByte()) + v
            }
            val body = int(sig.copyOfRange(0, 32)) + int(sig.copyOfRange(32, 64))
            return byteArrayOf(0x30, body.size.toByte()) + body
        }
    }
}
