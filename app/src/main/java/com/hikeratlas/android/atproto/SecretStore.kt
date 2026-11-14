package com.hikeratlas.android.atproto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small secrets (the session's tokens), by name. */
interface SecretStore {
    fun get(name: String): String?
    fun put(name: String, value: String)
    fun remove(name: String)
}

/**
 * Secrets in shared preferences, each encrypted (AES-GCM) with a key that
 * stays in the Android Keystore: a copy of the preferences file (a backup,
 * a rooted phone's files) holds nothing usable. A value that won't decrypt
 * (restored onto another phone, whose Keystore hasn't the key) reads as
 * missing, i.e. signed out.
 */
class EncryptedSecretStore(private val prefs: SharedPreferences, private val key: () -> SecretKey) : SecretStore {
    constructor(context: Context) : this(context.getSharedPreferences("atproto", Context.MODE_PRIVATE), ::keystoreKey)

    override fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
        } catch (e: Exception) {
            remove(name)
            null
        }
    }

    override fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore makes the IV (it won't take one from us).
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray())
        prefs.edit(commit = true) { putString(name, Base64.getEncoder().encodeToString(sealed)) }
    }

    override fun remove(name: String) {
        prefs.edit(commit = true) { remove(name) }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val ALIAS = "hikeratlas-atproto-secrets"

        fun keystoreKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }
    }
}

/** Secrets in memory, for tests. */
class MemorySecretStore : SecretStore {
    private val values = ConcurrentHashMap<String, String>()
    override fun get(name: String) = values[name]
    override fun put(name: String, value: String) { values[name] = value }
    override fun remove(name: String) { values.remove(name) }
}
