package com.hikeratlas.android.atproto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class DpopTest {
    @Test
    fun proofFields() {
        val key = MemoryDpopKeys().create("k")
        val proof = Dpop.proof(key, "GET", "https://pds.example/xrpc/a.b?repo=x#y", "n-1", accessToken = "at")
        val (h, p) = proof.split(".").take(2).map { Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(it))).jsonObject }
        assertEquals("dpop+jwt", h["typ"]!!.jsonPrimitive.content)
        assertEquals("P-256", h["jwk"]!!.jsonObject["crv"]!!.jsonPrimitive.content)
        assertEquals("https://pds.example/xrpc/a.b", p["htu"]!!.jsonPrimitive.content)
        assertEquals("n-1", p["nonce"]!!.jsonPrimitive.content)
        assertEquals(b64url(sha256("at".toByteArray())), p["ath"]!!.jsonPrimitive.content)
        val noNonce = Dpop.proof(key, "POST", "https://as.example/oauth/par", null)
        assertNull(Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(noNonce.split('.')[1]))).jsonObject["nonce"])
    }

    /** DER integers carry a sign byte, or are short a leading zero: both come out 32 bytes. */
    @Test
    fun derToJosePads() {
        val r = ByteArray(32) { 0xff.toByte() }
        val s = ByteArray(32) { if (it == 0) 0 else 1 }
        val der = FakeAtproto.joseToDer(r + s)
        assertArrayEquals(r + s, derToJose(der))
        assertEquals(64, MemoryDpopKeys().create("k").sign("x".toByteArray()).size)
    }
}
