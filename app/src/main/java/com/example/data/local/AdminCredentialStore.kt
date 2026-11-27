package com.example.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

sealed interface AdminAuthResult {
    data object Success : AdminAuthResult
    data class Invalid(val remainingAttempts: Int) : AdminAuthResult
    data class LockedOut(val remainingSeconds: Long) : AdminAuthResult
}

sealed interface PasswordChangeResult {
    data object Success : PasswordChangeResult
    data class Error(val message: String) : PasswordChangeResult
}

interface AdminRecordCipher {
    fun encrypt(plainText: String): Pair<String, String>?
    fun decrypt(cipherB64: String, ivB64: String): String?
    fun isHardwareVaultAvailable(): Boolean
}

private object AndroidKeystoreAdminCipher : AdminRecordCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "bumble_admin_auth_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    override fun isHardwareVaultAvailable(): Boolean = runCatching {
        getOrCreateSecretKey()
        true
    }.getOrElse { false }

    override fun encrypt(plainText: String): Pair<String, String>? = runCatching {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        Base64.encodeToString(encrypted, Base64.NO_WRAP) to Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }.getOrNull()

    override fun decrypt(cipherB64: String, ivB64: String): String? = runCatching {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivB64, Base64.NO_WRAP))
        )
        val plain = cipher.doFinal(Base64.decode(cipherB64, Base64.NO_WRAP))
        String(plain, StandardCharsets.UTF_8)
    }.getOrNull()

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
}

/**
 * Hardware-backed Android Keystore AES-256-GCM + PBKDF2-HMAC-SHA256 credential store
 * for the private Owner/Admin Panel.
 *
 * Security notes:
 * - Never stores plaintext passwords or logs credential inputs.
 * - Initial setup credential uses a salted PBKDF2-HMAC-SHA256 verifier (65,536 iterations).
 * - Because Bumble AI is a standalone client-side Android app with no dedicated backend auth server,
 *   local password checks cannot resist rooted/decompiled client tampering; production multi-user
 *   deployments should enforce server-side authentication.
 */
class AdminCredentialStore internal constructor(
    context: Context,
    private val recordCipher: AdminRecordCipher
) {

    constructor(context: Context) : this(context, AndroidKeystoreAdminCipher)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    private data class StoredRecord(
        val saltB64: String,
        val hashB64: String,
        val isDefaultPassword: Boolean,
        val updatedAtMillis: Long
    )

    fun isHardwareVaultReady(): Boolean = recordCipher.isHardwareVaultAvailable()

    fun isUsingDefaultPassword(): Boolean {
        val rec = loadEncryptedRecord()
        return rec?.isDefaultPassword ?: true
    }

    fun getLastPasswordChangeMillis(): Long {
        return loadEncryptedRecord()?.updatedAtMillis ?: 0L
    }

    fun getLockoutRemainingSeconds(nowMillis: Long = System.currentTimeMillis()): Long {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        if (lockoutUntil <= nowMillis) return 0L
        return ((lockoutUntil - nowMillis) + 999L) / 1000L
    }

    fun verifyPassword(
        candidate: String,
        nowMillis: Long = System.currentTimeMillis()
    ): AdminAuthResult {
        val remainingLock = getLockoutRemainingSeconds(nowMillis)
        if (remainingLock > 0L) {
            return AdminAuthResult.LockedOut(remainingLock)
        }

        if (candidate.isEmpty()) {
            return recordFailure(nowMillis)
        }

        val record = loadEncryptedRecord() ?: StoredRecord(
            saltB64 = INITIAL_SALT_B64,
            hashB64 = INITIAL_HASH_B64,
            isDefaultPassword = true,
            updatedAtMillis = 0L
        )

        val salt = Base64.decode(record.saltB64, Base64.NO_WRAP)
        val expectedHash = Base64.decode(record.hashB64, Base64.NO_WRAP)
        val candidateHash = derivePbkdf2(candidate.toCharArray(), salt)

        val matched = MessageDigest.isEqual(candidateHash, expectedHash)
        candidateHash.fill(0)

        return if (matched) {
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0L)
                .commit()
            AdminAuthResult.Success
        } else {
            recordFailure(nowMillis)
        }
    }

    fun changePassword(
        currentPassword: String,
        newPassword: String,
        confirmPassword: String,
        nowMillis: Long = System.currentTimeMillis()
    ): PasswordChangeResult {
        when (val auth = verifyPassword(currentPassword, nowMillis)) {
            is AdminAuthResult.LockedOut -> {
                return PasswordChangeResult.Error("Too many failed attempts. Wait ${auth.remainingSeconds}s and retry.")
            }
            is AdminAuthResult.Invalid -> {
                return PasswordChangeResult.Error(
                    "Current password is incorrect (${auth.remainingAttempts} attempts remaining)."
                )
            }
            AdminAuthResult.Success -> Unit
        }

        if (newPassword.length < MIN_PASSWORD_LENGTH) {
            return PasswordChangeResult.Error("New password must be at least $MIN_PASSWORD_LENGTH characters.")
        }
        if (newPassword != confirmPassword) {
            return PasswordChangeResult.Error("New password and confirmation do not match.")
        }
        if (newPassword == currentPassword) {
            return PasswordChangeResult.Error("Choose a new password different from the current password.")
        }

        // Prevent re-using the initial setup verifier
        val initialSalt = Base64.decode(INITIAL_SALT_B64, Base64.NO_WRAP)
        val initialHash = Base64.decode(INITIAL_HASH_B64, Base64.NO_WRAP)
        val newVsInitial = derivePbkdf2(newPassword.toCharArray(), initialSalt)
        val matchesInitial = MessageDigest.isEqual(newVsInitial, initialHash)
        newVsInitial.fill(0)
        if (matchesInitial) {
            return PasswordChangeResult.Error("Do not reuse the initial setup password.")
        }

        val freshSalt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val freshHash = derivePbkdf2(newPassword.toCharArray(), freshSalt)
        val saved = saveEncryptedRecord(
            StoredRecord(
                saltB64 = Base64.encodeToString(freshSalt, Base64.NO_WRAP),
                hashB64 = Base64.encodeToString(freshHash, Base64.NO_WRAP),
                isDefaultPassword = false,
                updatedAtMillis = nowMillis
            )
        )
        freshHash.fill(0)

        return if (saved) {
            PasswordChangeResult.Success
        } else {
            PasswordChangeResult.Error("Unable to store encrypted admin credentials cleanly.")
        }
    }

    private fun recordFailure(nowMillis: Long): AdminAuthResult {
        val nextFailures = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        return if (nextFailures >= MAX_FAILED_ATTEMPTS) {
            val lockoutUntil = nowMillis + LOCKOUT_DURATION_MS
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
                .commit()
            AdminAuthResult.LockedOut(LOCKOUT_DURATION_MS / 1000L)
        } else {
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, nextFailures).commit()
            AdminAuthResult.Invalid(MAX_FAILED_ATTEMPTS - nextFailures)
        }
    }

    private fun derivePbkdf2(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun saveEncryptedRecord(record: StoredRecord): Boolean {
        val json = JSONObject()
            .put("salt", record.saltB64)
            .put("hash", record.hashB64)
            .put("isDefault", record.isDefaultPassword)
            .put("updatedAt", record.updatedAtMillis)
            .toString()

        val (cipherB64, ivB64) = recordCipher.encrypt(json) ?: return false
        return prefs.edit()
            .putString(KEY_RECORD_CIPHER, cipherB64)
            .putString(KEY_RECORD_IV, ivB64)
            .commit()
    }

    private fun loadEncryptedRecord(): StoredRecord? {
        val cipherB64 = prefs.getString(KEY_RECORD_CIPHER, null) ?: return null
        val ivB64 = prefs.getString(KEY_RECORD_IV, null) ?: return null
        val plainJson = recordCipher.decrypt(cipherB64, ivB64) ?: return null
        return runCatching {
            val obj = JSONObject(plainJson)
            StoredRecord(
                saltB64 = obj.getString("salt"),
                hashB64 = obj.getString("hash"),
                isDefaultPassword = obj.optBoolean("isDefault", false),
                updatedAtMillis = obj.optLong("updatedAt", 0L)
            )
        }.getOrNull()
    }

    companion object {

        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 65536
        private const val PBKDF2_KEY_BITS = 256

        private const val PREFS_NAME = "bumble_admin_vault"
        private const val KEY_RECORD_CIPHER = "admin_rec_cipher"
        private const val KEY_RECORD_IV = "admin_rec_iv"
        private const val KEY_FAILED_ATTEMPTS = "admin_failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "admin_lockout_until"

        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L
        const val MIN_PASSWORD_LENGTH = 8
        const val SESSION_TIMEOUT_MS = 5 * 60_000L

        // Precomputed PBKDF2-HMAC-SHA256 (65536 iterations) verifier for initial setup credential
        private const val INITIAL_SALT_B64 = "ek+cIbjjBdYZxKfwLoFbPQ=="
        private const val INITIAL_HASH_B64 = "YnUdLOaKXnRItgbGMgFjNKE+f2KGLLBB+a0aZeuD8wc="
    }
}
