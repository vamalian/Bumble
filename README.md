# Bumble AI — Native Android JARVIS-Style Assistant

**Bumble AI** is a native Kotlin + Jetpack Compose Android assistant powered by Google's **Gemini API** (`generateContent` with multi-turn chat, function calling, and multimodal vision), featuring a golden holographic JARVIS-inspired HUD core.

## Core Capabilities

1. **Golden Holographic AI Core (`HolographicCore`)**
   - Custom Jetpack Compose `Canvas` core with rotating segmented HUD rings, counter-rotating telemetry ticks, orbiting glowing particles, and a 64-bar audio-reactive circular waveform.
   - Distinct animated states: `IDLE`, `LISTENING`, `THINKING`, `SPEAKING`, and `ERROR`.

2. **Normal Gemini Assistant & Voice I/O (`GeminiRestClient` + `VoiceInputManager` + `VoiceOutputManager`)**
   - Multi-turn text & voice conversations via Google Gemini REST API (`gemini-2.5-flash`, `gemini-2.5-flash-lite`, `gemini-2.5-pro`, or custom model ID) with multi-round function calling.
   - Foreground Android `SpeechRecognizer` voice capture with RMS audio-reactive core telemetry and Android `TextToSpeech` spoken replies with configurable voice profiles, personalities, and multilingual support (English, Urdu, Hindi, and mixed code-switching).

3. **Foreground-Only `"Hey Bumble"` Wake Word (`WakeWordManager`)**
   - Listens strictly while the app is open and `RESUMED`.
   - Immediately stops and destroys audio capture and speech recognition when paused, backgrounded, or closed. No background microphone service and no floating overlays.

4. **Real Android Assistant Actions (`AndroidActionExecutor`)**
   - Launch installed applications via `PackageManager` launch intents.
   - Open Android Wi-Fi, Bluetooth, Display, Sound, Location, Battery, and Notification settings.
   - Adjust device audio volume via `AudioManager` and brightness via `Settings.System` (when `WRITE_SETTINGS` is granted).
   - Set alarms and countdown timers via `AlarmClock`.
   - Create and manage local Notes & scheduled Reminders (Room + `AlarmManager`).
   - Read recent notifications via `BumbleNotificationListenerService` only when explicitly enabled in Android Notification Access.

5. **Multimodal Camera & Screen Vision (`VisionScreen` + `ScreenCaptureService`)**
   - Live CameraX viewfinder and capture.
   - Zero-permission Android Photo Picker (`PickVisualMedia`).
   - Consented one-shot Screen Understanding (`MediaProjection`) that stops immediately after capturing a single frame.

6. **Personal AI Utilities & Local Storage**
   - Hardware-backed **Android Keystore AES-256-GCM** encrypted Gemini API key storage (`SecureKeyStore`).
   - Live Weather (Open-Meteo), Web Search (DuckDuckGo / Wikipedia), Scientific Calculator (`MathEvaluator`), Multilingual Translator, and Daily Planner.
   - User-approved Personal Memory & Conversation History stored locally in Room.

## Building APKs

### Debug APK
```bash
gradle :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK
Configure signing environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`) and run:
```bash
gradle :app:assembleRelease
```

### Unit & Robolectric Tests
```bash
gradle :app:testDebugUnitTest
```
