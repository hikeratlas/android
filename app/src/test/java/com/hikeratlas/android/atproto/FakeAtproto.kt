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
        return when (path) {
            "/xrpc/com.atproto.repo.describeRepo" -> json("""{"handle":"$handle","did":"$did"}""")
            "/xrpc/com.atproto.repo.getRecord" -> json("""{"error":"RecordNotFound","message":"Could not locate record"}""", 400)
            "/xrpc/com.atproto.repo.createRecord" -> json("""{"uri":"at://$did/x/1","cid":"bafy","echo":${r.body!!.utf8()}}""")
            "/xrpc/com.atproto.repo.uploadBlob" -> json(
                """{"blob":{"${'$'}type":"blob","mimeType":"${r.headers["Content-Type"]}","size":${r.body!!.size}}}""",
            )
            else -> json("""{"error":"MethodNotImplemented"}""", 501)
        }
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
