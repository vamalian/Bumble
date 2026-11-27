# Bumble AI — Native Android JARVIS-Style Assistant

**Bumble AI** is a native Kotlin + Jetpack Compose Android assistant powered by Google's **Gemini Live API** (`v1beta BidiGenerateContent` WebSocket for real-time bidirectional voice + tools) and **Gemini REST API** (multimodal Vision, Translator, and Daily Planner), featuring a golden holographic JARVIS-inspired HUD core.

## Core Capabilities

1. **Golden Holographic AI Core (`HolographicCore`)**
   - Custom Jetpack Compose `Canvas` core with rotating segmented HUD rings, counter-rotating telemetry ticks, orbiting glowing particles, and a 64-bar audio-reactive circular waveform driven by genuine PCM RMS amplitude.
   - 6 animated states: `IDLE`, `CONNECTING`, `LISTENING`, `PROCESSING`, `SPEAKING`, and `ERROR`.

2. **Gemini Live Bidirectional Voice (`GeminiLiveClient` + `LiveAudioEngine`)**
   - Real-time `BidiGenerateContent` WebSocket connection supporting `gemini-3.1-flash-live-preview`, `gemini-2.5-flash-native-audio-preview-12-2025`, and `gemini-2.5-flash-native-audio-preview-09-2025`.
   - Captures 16 kHz mono 16-bit PCM via `AudioRecord` (`VOICE_COMMUNICATION` with `MIC` fallback + hardware `AcousticEchoCanceler` & `NoiseSuppressor`).
   - Plays 24 kHz mono 16-bit PCM speech via `AudioTrack` (`USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`) with `AudioFocus`, bounded queue, barge-in interruption, and session resumption/reconnection.
   - Configurable official prebuilt voices (`Kore`, `Puck`, `Charon`, `Fenrir`, `Aoede`, `Zephyr`, `Orus`, `Leda`), personalities, and multilingual support (English, Urdu, Hindi, and mixed code-switching).

3. **Foreground-Only `"Hey Bumble"` Wake Word (`WakeWordManager`)**
   - Listens strictly while the app is open and `RESUMED`.
   - Immediately stops and destroys audio capture, playback, and WebSocket sessions when paused, backgrounded, or closed. No background microphone service and no floating overlays.

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

6. **Private Owner Console (`AdminScreen` + `AdminCredentialStore`)**
   - Discreet entry inside Settings gated by local authentication (PBKDF2-HMAC-SHA256 + Android Keystore AES-256-GCM, 5-attempt lockout, 5-minute inactivity timeout, background auto-lock).
   - Includes Live WebSocket & REST diagnostics, real 16 kHz mic & 24 kHz speaker tests, permission/app health telemetry, privacy-safe aggregate `COUNT(*)` stats, model/API key configuration, password update, and secure logout.

## Building & Testing

### Unit & Robolectric Tests
```bash
gradle :app:testDebugUnitTest
```

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
