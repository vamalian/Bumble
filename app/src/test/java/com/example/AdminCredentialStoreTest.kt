package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AdminAuthResult
import com.example.data.local.AdminCredentialStore
import com.example.data.local.AdminRecordCipher
import com.example.data.local.PasswordChangeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdminCredentialStoreTest {

    private lateinit var store: AdminCredentialStore

    private val testCipher = object : AdminRecordCipher {
        override fun isHardwareVaultAvailable(): Boolean = true

        override fun encrypt(plainText: String): Pair<String, String> {
            val b64 = Base64.encodeToString(plainText.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            return b64 to "dGVzdC1pdg=="
        }

        override fun decrypt(cipherB64: String, ivB64: String): String {
            return String(Base64.decode(cipherB64, Base64.NO_WRAP), StandardCharsets.UTF_8)
        }
    }

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("bumble_admin_vault", Context.MODE_PRIVATE).edit().clear().commit()
        store = AdminCredentialStore(ctx, testCipher)
    }

    @Test
    fun `initial setup password admin succeeds and is marked default`() {
        assertTrue(store.isUsingDefaultPassword())
        assertEquals(AdminAuthResult.Success, store.verifyPassword("admin", nowMillis = 1_000L))
        assertTrue(store.verifyPassword("wrong", nowMillis = 2_000L) is AdminAuthResult.Invalid)
    }

    @Test
    fun `five consecutive invalid attempts trigger temporary lockout`() {
        val baseTime = 100_000L
        for (attempt in 1..4) {
            val res = store.verifyPassword("bad$attempt", nowMillis = baseTime + attempt)
            assertTrue(res is AdminAuthResult.Invalid)
            assertEquals(5 - attempt, (res as AdminAuthResult.Invalid).remainingAttempts)
        }
        val fifth = store.verifyPassword("bad5", nowMillis = baseTime + 5)
        assertTrue(fifth is AdminAuthResult.LockedOut)
        assertEquals(30L, (fifth as AdminAuthResult.LockedOut).remainingSeconds)

        // Even the real password must be blocked while lockout active
        val blockedDuringLock = store.verifyPassword("admin", nowMillis = baseTime + 5_000L)
        assertTrue(blockedDuringLock is AdminAuthResult.LockedOut)

        // After 30s cooldown expires, valid credential works again
        assertEquals(AdminAuthResult.Success, store.verifyPassword("admin", nowMillis = baseTime + 35_000L))
    }

    @Test
    fun `changing password clears default flag and invalidates old initial credential`() {
        val t = 50_000L
        assertTrue(
            store.changePassword("wrong", "StrongPass!23", "StrongPass!23", t) is PasswordChangeResult.Error
        )
        assertTrue(
            store.changePassword("admin", "short", "short", t + 1) is PasswordChangeResult.Error
        )
        assertTrue(
            store.changePassword("admin", "StrongPass!23", "Mismatch!23", t + 2) is PasswordChangeResult.Error
        )
        assertTrue(
            store.changePassword("admin", "admin", "admin", t + 3) is PasswordChangeResult.Error
        )

        val changed = store.changePassword("admin", "BumbleOwner#2026", "BumbleOwner#2026", t + 4)
        assertEquals(PasswordChangeResult.Success, changed)
        assertFalse(store.isUsingDefaultPassword())
        assertTrue(store.verifyPassword("admin", t + 5) is AdminAuthResult.Invalid)
        assertEquals(AdminAuthResult.Success, store.verifyPassword("BumbleOwner#2026", t + 6))
    }
}
