# KaivoFlow

> **Sovereign, Serverless, Mobile-First Visual Automation Platform for Android.**  
> Built for thumbs. Runs 100% on-device with zero cloud server requirements.

---

## What is KaivoFlow?

KaivoFlow brings visual workflow automation directly to your Android device. You design **Flows** of **Steps** connected by **Streams**, and execute them on-demand or in response to real-world triggers (**Sparks**):

* **Sparks (Triggers):** Manual run, Cron & recurring schedules, Local webhooks, Incoming SMS, Notification listener, Device battery status, Android Share Sheet.
* **Logic & Flow Control:** If/Else branching, Switch routing, Merge branches, Filter items, Deduplication, For-Each batch looping, Non-blocking wait/delay, and Error handlers.
* **Transformations & Developer Tools:** Set/Edit fields, JSON parser & stringifier, Text templating, Date arithmetic, Item aggregation, Native JavaScript code execution, and Native Cryptography (SHA-256, MD5, SHA-1, HMAC-SHA256, Base64, UUID).
* **AI & Intelligence:** Multi-provider AI step with support for Google Gemini, local Ollama (LAN or phone-side), and OpenAI-compatible endpoints with structured JSON output.
* **Hardware & Device Integrations:** Status bar notifications, SMS sending, System clipboard, Text-to-Speech (TTS), Haptic vibration patterns, File storage, Battery status interrogation, and Real-time network monitoring.
* **SaaS Connectors:** Universal HTTP Request node (GET, POST, PUT, PATCH, DELETE), Google Sheets, Notion, Telegram, Slack, Discord, and Weather.

---

## Architecture & Modules

The project is structured into 13 modular Gradle components:

| Module | Purpose |
|---|---|
| `:core:model` | Core domain models (`Flow`, `StepNode`, `StreamEdge`, `FlowData`, `RunRecord`, `ScheduleMath`). |
| `:core:engine` | Execution engine (`FlowRunner`), topological DAG execution, port liveness, retry policies. |
| `:core:expressions` | Sandboxed expression runtime (supports `{{ $json.field }}`, `{{ $now }}`, arithmetic, string helpers). |
| `:core:nodes` | 38+ Built-in node executors and the single `NodeCatalog` registry. |
| `:core:data` | Room database, DAOs, repositories, JSON serialization, and native `.structure` import/export. |
| `:core:network` | Ktor/OkHttp network gateway for HTTP, webhook listener, and AI model communication. |
| `:core:work` | WorkManager integration (`FlowRunWorker`), `FlowScheduler`, `BootReceiver`, `AndroidDeviceGateway`. |
| `:core:design` | Design tokens, typography, Velvet Obsidian & Champagne Gold theme, Jewel step cards. |
| `:feature:canvas` | Visual drag-and-drop canvas, bezier curve streams, step inspector sheet, run HUD. |
| `:feature:flows` | Flow library screen, flow management, execution history. |
| `:feature:permissions` | Android system permission center (Notification listener, SMS, Alarms). |
| `:feature:settings` | App settings, Privacy Center, Encrypted Credential Vault. |
| `:app` | Application entry point, `MainActivity`, Navigation graph, Hilt dependency injection root. |

---

## Building and Running

### Prerequisites
* **JDK:** Java 21 (or Java 17).
* **Android SDK:** Platforms 34/35 (`compileSdk = 34`).
* **Gradle:** 8.5+.

### Build Commands
```bash
# 1. Point to your local Android SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. Compile debug APK
gradle :app:assembleDebug

# 3. Run unit test suites
gradle test

# 4. Install onto connected Android device via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Developer & Cloud IDE Handoff

For detailed architectural notes, recent engine fixes, and next development milestones for AI agents and cloud IDEs, see:
👉 **[DEVELOPER_HANDOFF.md](DEVELOPER_HANDOFF.md)**
