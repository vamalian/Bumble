package com.example.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android Keystore AES-256-GCM storage for the user's Gemini API key.
 * Keys are never written to plaintext preferences, logs, or source control.
 */
class SecureKeyStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveApiKey(rawKey: String): Boolean {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            clearApiKey()
            return true
        }
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(trimmed.toByteArray(StandardCharsets.UTF_8))

            prefs.edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt API key in Keystore", e)
            false
        }
    }

    fun getApiKey(): String? {
        val cipherTextB64 = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val ivB64 = prefs.getString(KEY_IV, null) ?: return null
        return try {
            val secretKey = getOrCreateSecretKey()
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val cipherText = Base64.decode(cipherTextB64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = cipher.doFinal(cipherText)
            String(plain, StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt API key from Keystore", e)
            null
        }
    }

    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()

    fun getMaskedApiKey(): String {
        val key = getApiKey() ?: return ""
        return if (key.length <= 8) {
            "••••••••"
        } else {
            "${key.take(4)}••••••••••••${key.takeLast(4)}"
        }
    }

    fun clearApiKey() {
        prefs.edit()
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .commit()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val TAG = "SecureKeyStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "bumble_gemini_api_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PREFS_NAME = "bumble_secure_vault"
        private const val KEY_CIPHERTEXT = "enc_gemini_key"
        private const val KEY_IV = "enc_gemini_iv"
    }
}
