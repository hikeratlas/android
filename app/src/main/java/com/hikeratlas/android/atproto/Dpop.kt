package com.hikeratlas.android.atproto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * A session's DPoP key (EC P-256): its tokens are bound to it, so they're no
 * use to anyone who copies them without it. [sign] gives a JWS signature
 * (r and s, 32 bytes each), not Java's DER.
 */
interface DpopKey {
    val jwk: JsonObject
    fun sign(data: ByteArray): ByteArray
}

/** Where DPoP keys live, by alias: one per sign-in. */
interface DpopKeys {
    fun create(alias: String): DpopKey
    fun load(alias: String): DpopKey?
    fun delete(alias: String)
}

/**
 * Keys in the Android Keystore: the private key never leaves it (in secure
 * hardware where the phone has it), so it can't be copied off the device.
 */
class KeystoreDpopKeys : DpopKeys {
    private val store by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    override fun create(alias: String): DpopKey {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        gen.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        val pair = gen.generateKeyPair()
        return EcKey(pair.private, pair.public as ECPublicKey)
    }

    override fun load(alias: String): DpopKey? {
        val entry = try { store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry } catch (e: Exception) { null } ?: return null
        return EcKey(entry.privateKey, entry.certificate.publicKey as ECPublicKey)
    }

    override fun delete(alias: String) {
        try { store.deleteEntry(alias) } catch (e: Exception) { }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}

/** Keys in memory, for tests (Robolectric has no Android Keystore). */
class MemoryDpopKeys : DpopKeys {
    private val keys = ConcurrentHashMap<String, DpopKey>()
    val aliases: Set<String> get() = keys.keys

    override fun create(alias: String): DpopKey {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = gen.generateKeyPair()
        return EcKey(pair.private, pair.public as ECPublicKey).also { keys[alias] = it }
    }

    override fun load(alias: String) = keys[alias]
    override fun delete(alias: String) { keys.remove(alias) }
}

private class EcKey(private val private: PrivateKey, public: ECPublicKey) : DpopKey {
    override val jwk = buildJsonObject {
        put("kty", "EC")
        put("crv", "P-256")
        put("x", b64url(unsigned32(public.w.affineX)))
        put("y", b64url(unsigned32(public.w.affineY)))
    }

    override fun sign(data: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(private)
            update(data)
            sign()
        }
        return derToJose(der)
    }
}

/** DPoP proofs (RFC 9449): a JWT signed by the session's key, one per request. */
object Dpop {
    private val random = SecureRandom()

    /**
     * A proof for [method] [url] (its query and fragment dropped, as the
     * spec has it). [nonce] is the server's latest; [accessToken] binds a
     * proof for a PDS request to its token (`ath`).
     */
    fun proof(key: DpopKey, method: String, url: String, nonce: String?, accessToken: String? = null, now: Long = System.currentTimeMillis()): String {
        val header = buildJsonObject {
            put("typ", "dpop+jwt")
            put("alg", "ES256")
            put("jwk", key.jwk)
        }
        val payload = buildJsonObject {
            put("jti", b64url(ByteArray(16).also(random::nextBytes)))
            put("htm", method)
            put("htu", url.substringBefore('#').substringBefore('?'))
            put("iat", now / 1000)
            if (nonce != null) put("nonce", nonce)
            if (accessToken != null) put("ath", b64url(sha256(accessToken.toByteArray())))
        }
        val signing = b64url(header.toString().toByteArray()) + "." + b64url(payload.toString().toByteArray())
        return signing + "." + b64url(key.sign(signing.toByteArray()))
    }
}

internal fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun randomToken(bytes: Int = 32): String = b64url(ByteArray(bytes).also(SecureRandom()::nextBytes))

/** A coordinate as JWK has it: unsigned, big-endian, exactly 32 bytes. */
private fun unsigned32(n: BigInteger): ByteArray {
    val b = n.toByteArray()
    return when {
        b.size == 32 -> b
        b.size > 32 -> b.copyOfRange(b.size - 32, b.size) // a leading sign byte
        else -> ByteArray(32 - b.size) + b
    }
}

/**
 * Java's ECDSA signature (DER: a SEQUENCE of two INTEGERs) as JWS wants it,
 * r then s, each 32 bytes.
 */
internal fun derToJose(der: ByteArray): ByteArray {
    var i = 0
    require(der[i++].toInt() == 0x30) { "not a DER sequence" }
    // The sequence's length: short form, or 0x81 and one byte.
    if (der[i++].toInt() and 0xff == 0x81) i++
    fun integer(): ByteArray {
        require(der[i++].toInt() == 0x02) { "not a DER integer" }
        val len = der[i++].toInt() and 0xff
        val v = der.copyOfRange(i, i + len)
        i += len
        return unsigned32(BigInteger(1, v))
    }
    return integer() + integer()
}
