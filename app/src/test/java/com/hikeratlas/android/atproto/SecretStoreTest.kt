package com.hikeratlas.android.atproto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

/**
 * The encrypted store, with a software AES key in place of the Keystore's
 * (Robolectric has no Keystore).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SecretStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("atproto-test", Context.MODE_PRIVATE)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun encryptedAtRest() {
        val store = EncryptedSecretStore(prefs) { key }
        store.put("session", """{"refreshToken":"rt-secret"}""")
        assertEquals("""{"refreshToken":"rt-secret"}""", store.get("session"))
        // What's on disk isn't the token.
        val raw = prefs.getString("session", null)!!
        assertFalse(raw.contains("rt-secret"))
        store.remove("session")
        assertNull(store.get("session"))
    }

    /** Restored onto another phone, whose Keystore hasn't the key: signed out, not a crash. */
    @Test
    fun anotherKeyReadsAsMissing() {
        EncryptedSecretStore(prefs) { key }.put("session", "x")
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = EncryptedSecretStore(prefs) { other }
        assertNull(store.get("session"))
        assertNull(prefs.getString("session", null))
    }
}
