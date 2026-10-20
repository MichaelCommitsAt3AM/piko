package com.piko.agent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API credentials are encrypted with an AES-256-GCM key stored in the Android
 * Keystore. Ciphertext never leaves this device. Keys are never logged.
 */
class SecureCredentialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasKey(providerId: String): Boolean = !prefs.getString(providerId, null).isNullOrBlank()

    fun getApiKey(providerId: String): String? {
        val encoded = prefs.getString(providerId, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size < 3) return null
            val version = payload[0].toInt()
            if (version != PAYLOAD_VERSION) return null
            val ivLength = payload[1].toInt() and 0xFF
            if (payload.size <= 2 + ivLength) return null
            val iv = payload.copyOfRange(2, 2 + ivLength)
            val ciphertext = payload.copyOfRange(2 + ivLength, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun setApiKey(providerId: String, apiKey: String) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        require(iv.size <= 255)
        val payload = ByteArray(2 + iv.size + ciphertext.size)
        payload[0] = PAYLOAD_VERSION.toByte()
        payload[1] = iv.size.toByte()
        iv.copyInto(payload, destinationOffset = 2)
        ciphertext.copyInto(payload, destinationOffset = 2 + iv.size)
        prefs.edit()
            .putString(providerId, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    fun deleteApiKey(providerId: String) {
        prefs.edit().remove(providerId).apply()
    }

    fun maskedKey(providerId: String): String? {
        val key = getApiKey(providerId) ?: return null
        return if (key.length <= 8) "••••••••" else "${key.take(4)}••••${key.takeLast(4)}"
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "piko_credentials"
        private const val KEY_ALIAS = "piko_credentials_aes_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PAYLOAD_VERSION = 1
    }
}
