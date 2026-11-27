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
    fun `sanitizeLiveModelId preserves supported Live models and migrates legacy`() {
        assertEquals("gemini-3.1-flash-live-preview", BumbleSettings.DEFAULT_LIVE_MODEL)
        assertEquals(
            "gemini-3.1-flash-live-preview",
            BumbleSettings.sanitizeLiveModelId("models/gemini-3.1-flash-live-preview")
        )
        assertEquals(
            "gemini-2.5-flash-native-audio-preview-12-2025",
            BumbleSettings.sanitizeLiveModelId("models/gemini-2.5-flash-native-audio-preview-12-2025")
        )
        assertEquals(
            BumbleSettings.DEFAULT_LIVE_MODEL,
            BumbleSettings.sanitizeLiveModelId("gemini-2.0-flash-live-001")
        )
        assertEquals(
            BumbleSettings.DEFAULT_LIVE_MODEL,
            BumbleSettings.sanitizeLiveModelId("gemini-1.5-pro")
        )
        assertEquals(
            BumbleSettings.DEFAULT_LIVE_MODEL,
            BumbleSettings.sanitizeLiveModelId("gemini-2.5-flash")
        )
        assertEquals(
            BumbleSettings.DEFAULT_LIVE_MODEL,
            BumbleSettings.sanitizeLiveModelId("   ")
        )
    }

    @Test
    fun `sanitizeVisionModelId keeps modern vision models and migrates deprecated`() {
        assertEquals("gemini-3.8-flash", BumbleSettings.DEFAULT_VISION_MODEL)
        assertEquals(
            "gemini-4-argon",
            BumbleSettings.sanitizeVisionModelId("models/gemini-4-argon")
        )
        assertEquals(
            BumbleSettings.DEFAULT_VISION_MODEL,
            BumbleSettings.sanitizeVisionModelId("gemini-2.5-flash-native-audio-preview-09-2025")
        )
        assertEquals(
            BumbleSettings.DEFAULT_VISION_MODEL,
            BumbleSettings.sanitizeVisionModelId("gemini-3.1-flash-live-preview")
        )
        assertEquals(
            BumbleSettings.DEFAULT_VISION_MODEL,
            BumbleSettings.sanitizeVisionModelId("gemini-1.5-flash")
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
