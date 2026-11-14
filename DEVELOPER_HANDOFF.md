# KaivoFlow — Cloud IDE & Arena AI Developer Handoff Guide

> **Project:** KaivoFlow (Independent Mobile Visual Automation Platform)  
> **Repository:** `https://github.com/Gorachand22/KaivoFlow.git`  
> **Target OS:** Android 8.0+ (API 26 → 34/35)  
> **UI Architecture:** Jetpack Compose (Velvet Obsidian & Champagne Gold Theme)  
> **Current Build Status:** ✅ **COMPILE DEBUG & UNIT TESTS VERIFIED (JDK 21 + Gradle 8.5 + SDK 34)**  
> **Real Device Verification:** Tested on physical Realme 9 5G (Android 13 / API 33) via ADB.

---

## 1. Project Overview & Author Vision

KaivoFlow is an independent, sovereign, mobile-first visual automation system designed to run **100% on-device** without needing cloud servers, virtual machines, or external Docker deployments. Users design automation workflows (**Flows**) consisting of nodes (**Steps**) linked by directed edges (**Streams**). 

The app includes:
* **Rich Trigger System:** Sparks (triggers) powered by Manual taps, Cron schedules, Webhooks, SMS events, Notification listeners, Battery state, and Android Share Sheet.
* **38+ Built-in Node Catalog:** Transforming JSON items, running logic (If/Switch/Merge/Loop/Wait/Filter), cryptographic hashing/encoding, HTTP REST requests, AI model completions (Gemini, Ollama, OpenAI-compatible), and local device hardware controls (Vibration, Text-to-Speech, Notifications, Clipboard, Battery, Network).
* **Pure Local Execution:** Flows run via Android `WorkManager`, Foreground Services, and `AlarmManager`.
* **Zero External Dependencies:** Self-contained, fair, independent, and designed strictly for mobile thumbs.

---

## 2. Architecture & Module Map (13 Modules)

```
KaivoFlow/
├── app/                  # Main Android Application, Application class, MainActivity, Navigation
├── core/
│   ├── model/            # Pure Kotlin data classes (Flow, StepNode, StreamEdge, FlowData, RunRecord)
│   ├── engine/           # FlowRunner execution engine, DAG topological sort, lifecycle events
│   ├── expressions/      # Sandboxed Expression Evaluator (supports {{ $json.field }}, {{ $now }}, math)
│   ├── nodes/            # 38+ StepExecutors & NodeCatalog registry (Sparks, Transforms, Logic, SaaS, Device, AI)
│   ├── network/          # Ktor/OkHttp network gateway for HTTP, Webhooks, and AI calls
│   ├── data/             # Room Database, DAOs, Repositories, JSON Codecs, Native .structure import/export
│   ├── design/           # Theme, Colors, Typography, Jewel Cards, Canvas tokens
│   └── work/             # WorkManager workers (FlowRunWorker), FlowScheduler, BootReceiver, AndroidDeviceGateway
└── feature/
    ├── canvas/           # Visual canvas editor, Stream bezier connections, Step Inspector sheet
    ├── flows/            # Flow list screen, Flow cards, Run history, Import/Export UI
    ├── permissions/      # Android permission center (SMS, Notification Listener, Exact Alarms)
    └── settings/         # App settings, Privacy Center, Credential Vault UI
```

---

## 3. What Has Already Been Fixed & Verified

The local engineering session resolved all build blockers, completed extensive unit tests, and verified physical device execution:

1. **Build Toolchain Alignment:**
   * Pinned Gradle Version Catalog (`gradle/libs.versions.toml`) to stable versions: AGP `8.3.2`, Kotlin `2.0.21`, KSP `2.0.21-1.0.28`, Compose BOM `2024.10.01`.
   * Set `jvmToolchain(21)` and `compileSdk = 34`.
2. **Trigger Execution Engine Fix (P0):**
   * Fixed `FlowRunner.kt` and `TriggerNodes.kt`. Previously, manual and scheduled triggers emitted empty lists, causing `executeStep()` to skip all subsequent action steps. Triggers now emit a rich initial payload containing timestamp, executionId, and trigger metadata.
3. **Auto-Rescheduling Loop (P0):**
   * In `FlowRunWorker.kt`, recurring schedules (daily, weekly, monthly) now calculate the next occurrence and enqueue the subsequent work request upon completion.
   * `BootReceiver.kt` now handles `ACTION_BOOT_COMPLETED`, `ACTION_MY_PACKAGE_REPLACED`, `ACTION_TIMEZONE_CHANGED`, and `ACTION_TIME_CHANGED`.
4. **Event Filter Validation:**
   * `EventTriggerMatcher.kt` enforces fine-grained filtering for incoming SMS (sender pattern, body contents), Notifications (package allowlist, title/text filters, ongoing filter), and Battery level thresholds.
5. **New Developer-Grade Nodes Added:**
   * `transform.crypto`: SHA-256, MD5, SHA-1, HMAC-SHA256, Base64 Encode/Decode, UUID generation.
   * `device.battery_status`: Real-time battery percentage, charging state, health, and temperature query.
   * `device.network_status`: Real-time connectivity check (WiFi / Cellular / Offline) and metered status.

---

## 4. How to Build & Test in the Cloud IDE

### Prerequisites:
* **JDK:** Java 21 or Java 17.
* **Android SDK:** Platforms 34 or 35 installed with `build-tools;34.0.0`.
* **Gradle:** 8.5+ (or use installed system Gradle).

### Commands:
```bash
# 1. Point to your Android SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. Run unit tests across modules
gradle :core:nodes:test
gradle :core:model:test
gradle :core:data:test

# 3. Assemble debug APK
gradle :app:assembleDebug

# 4. Install onto connected emulator or device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 5. Priority Roadmap for Cloud IDE Arena AI

When continuing development on this repository, focus on the following high-value architectural improvements:

### Priority 1: First-Class Binary Data & Android SAF Integration
* **Current state:** `FlowData` carries `json: Map<String, Any?>`.
* **Target:** Add `binary: Map<String, BinaryItem>` to `FlowData` in `core:model`:
  ```kotlin
  data class BinaryItem(
      val uri: String,      // Android content:// URI or internal file path
      val mimeType: String, // e.g. "image/png", "application/pdf"
      val fileName: String,
      val sizeBytes: Long
  )
  ```
* Wire `saas.http` to support binary downloads (saving to SAF) and multipart form-data uploads.
* Expand `device.file` to pick documents via Android Storage Access Framework (`ActivityResultContracts.OpenDocument`).

### Priority 2: Canvas Stream Drag & Drop Ergonomics
* Enhance touch-based stream connecting in `CanvasScreen.kt`:
  * Allow dragging from node output port circle directly onto an input port circle.
  * Provide haptic feedback (`HapticFeedbackType.LongPress`) on successful link connection.

### Priority 3: Native Flow Export/Import with Parameter Preservation
* `FlowStructureBundle.kt` currently strips all step parameters for skeleton sharing.
* Add a full Native Flow export (`.kaivoflow` bundle) that preserves non-sensitive parameters (URLs, condition rules, prompts, templates) while redacting credentials/API keys.
* On import, prompt the user with a Credential Re-bind sheet if any step requires an API key.

### Priority 4: OAuth2 Authorization Code Token Vault
* Expand `core:network` / `core:data` with a lightweight in-app Custom Tabs receiver for OAuth2 services (e.g. Google Drive, Slack, Notion) with automatic refresh token rotation stored securely in Android `EncryptedSharedPreferences`.
