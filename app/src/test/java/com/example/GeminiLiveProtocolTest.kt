package com.example

import android.util.Base64
import com.example.ai.GeminiLiveClient
import com.example.ai.LiveAudioEngine
import com.example.ai.LiveErrorCategory
import com.example.ai.LiveServerEvent
import com.example.data.local.BumbleSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiLiveProtocolTest {

    @Test
    fun `buildSetupPayload uses v1beta Audio modality and voice config`() {
        val settings = BumbleSettings(
            liveModel = "gemini-3.1-flash-live-preview",
            liveVoice = "Aoede"
        )
        val payload = GeminiLiveClient.buildSetupPayload(
            settings = settings,
            memoryContext = "- [pref] Prefers concise HUD updates",
            resumptionHandle = "resume-tok-123"
        )

        val setup = payload.getJSONObject("setup")
        assertEquals("models/gemini-3.1-flash-live-preview", setup.getString("model"))
        val genCfg = setup.getJSONObject("generationConfig")
        val modalities = genCfg.getJSONArray("responseModalities")
        assertEquals(1, modalities.length())
        assertEquals("AUDIO", modalities.getString(0))
        assertEquals(
            "Aoede",
            genCfg.getJSONObject("speechConfig")
                .getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig")
                .getString("voiceName")
        )
        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))
        assertEquals("resume-tok-123", setup.getJSONObject("sessionResumption").getString("handle"))
        assertTrue(
            GeminiLiveClient.buildWebSocketUrl("test-key")
                .contains("v1beta.GenerativeService.BidiGenerateContent?key=test-key")
        )
    }

    @Test
    fun `parseServerMessage decodes setupComplete audioChunks transcripts tools and interrupted`() {
        val samplePcm = byteArrayOf(0x10, 0x05, 0x20, 0x0A, -12, 0x01)
        val sampleB64 = Base64.encodeToString(samplePcm, Base64.NO_WRAP)

        val json = """
            {
              "setupComplete": {},
              "sessionResumptionUpdate": { "newHandle": "h-42", "resumable": true },
              "serverContent": {
                "interrupted": true,
                "turnComplete": true,
                "inputTranscription": { "text": "Hey Bumble" },
                "outputTranscription": { "text": "Systems online." },
                "modelTurn": {
                  "parts": [
                    { "inlineData": { "mimeType": "audio/pcm;rate=24000", "data": "$sampleB64" } }
                  ]
                }
              },
              "toolCall": {
                "functionCalls": [
                  { "id": "call-7", "name": "get_weather", "args": { "city": "Lahore" } }
                ]
              }
            }
        """.trimIndent()

        val events = GeminiLiveClient.parseServerMessage(json)
        assertTrue(events.any { it is LiveServerEvent.SetupComplete })
        assertTrue(events.any { it is LiveServerEvent.Interrupted })
        assertTrue(events.any { it is LiveServerEvent.TurnComplete })

        val resumption = events.filterIsInstance<LiveServerEvent.SessionResumption>().single()
        assertEquals("h-42", resumption.handle)
        assertTrue(resumption.resumable)

        val inTxt = events.filterIsInstance<LiveServerEvent.InputTranscription>().single()
        assertEquals("Hey Bumble", inTxt.text)

        val outTxt = events.filterIsInstance<LiveServerEvent.OutputTranscription>().single()
        assertEquals("Systems online.", outTxt.text)

        val audio = events.filterIsInstance<LiveServerEvent.AudioChunk>().single()
        assertEquals(24000, audio.sampleRate)
        assertArrayEquals(samplePcm, audio.pcmBytes)

        val tool = events.filterIsInstance<LiveServerEvent.ToolCallRequest>().single()
        assertEquals("call-7", tool.id)
        assertEquals("get_weather", tool.name)
        assertEquals("Lahore", tool.args.getString("city"))
    }

    @Test
    fun `classifyError accurately classifies key quota model and network errors`() {
        val keyErr = GeminiLiveClient.classifyError(401, "API_KEY_INVALID", null, null, "gemini-3.1-flash-live-preview")
        assertEquals(LiveErrorCategory.INVALID_API_KEY, keyErr.category)

        val quotaErr = GeminiLiveClient.classifyError(429, "RESOURCE_EXHAUSTED", "Quota exceeded", null, "gemini-3.1-flash-live-preview")
        assertEquals(LiveErrorCategory.QUOTA_EXCEEDED, quotaErr.category)

        val modelErr = GeminiLiveClient.classifyError(1008, "Model not found or unsupported", null, null, "old-model")
        assertEquals(LiveErrorCategory.UNSUPPORTED_MODEL, modelErr.category)

        val netErr = GeminiLiveClient.classifyError(1006, "Broken pipe", null, IOException("timeout"), "gemini-3.1-flash-live-preview")
        assertEquals(LiveErrorCategory.NETWORK_ERROR, netErr.category)
    }

    @Test
    fun `computePcm16NormalizedRms distinguishes silence from active speech PCM`() {
        val silence = ByteArray(3200)
        assertEquals(0f, LiveAudioEngine.computePcm16NormalizedRms(silence, silence.size), 0.0001f)

        val active = ByteArray(3200)
        var i = 0
        while (i + 1 < active.size) {
            val sample = 6000
            active[i] = (sample and 0xFF).toByte()
            active[i + 1] = ((sample ushr 8) and 0xFF).toByte()
            i += 2
        }
        val amp = LiveAudioEngine.computePcm16NormalizedRms(active, active.size)
        assertTrue("Expected positive amplitude for non-silent PCM, got $amp", amp > 0.2f && amp <= 1f)
    }
}
