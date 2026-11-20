package com.example

import com.example.assistant.AndroidActionExecutor
import com.example.data.local.BumbleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BumbleSettingsTest {

    @Test
    fun `sanitizeModelId migrates legacy live and native audio models to default`() {
        assertEquals(
            BumbleSettings.DEFAULT_GEMINI_MODEL,
            BumbleSettings.sanitizeModelId("models/gemini-2.5-flash-native-audio-preview-09-2025")
        )
        assertEquals(
            BumbleSettings.DEFAULT_GEMINI_MODEL,
            BumbleSettings.sanitizeModelId("gemini-2.0-flash-live-001")
        )
        assertEquals(
            BumbleSettings.DEFAULT_GEMINI_MODEL,
            BumbleSettings.sanitizeModelId("   ")
        )
        assertEquals(
            "gemini-2.5-pro",
            BumbleSettings.sanitizeModelId("models/gemini-2.5-pro")
        )
    }

    @Test
    fun `function declarations use uppercase REST schema types`() {
        val declarations = AndroidActionExecutor.buildGeminiFunctionDeclarations()
        assertTrue(declarations.length() >= 10)
        for (i in 0 until declarations.length()) {
            val fn = declarations.getJSONObject(i)
            val params = fn.getJSONObject("parameters")
            assertEquals("OBJECT", params.getString("type"))
            val props = params.getJSONObject("properties")
            val keys = props.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val t = props.getJSONObject(k).getString("type")
                assertTrue("Expected uppercase schema type, got $t", t == "STRING" || t == "INTEGER")
            }
        }
    }
}
