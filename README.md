# KaivoFlow

> **Sovereign, Serverless, Mobile-First Visual Automation Platform for Android.**  
> Built for thumbs. Runs 100% on-device with zero cloud server requirements.

---

## What is KaivoFlow?

KaivoFlow brings visual workflow automation directly to your Android device. You design **Flows** of **Steps** connected by **Streams**, and execute them on-demand or in response to real-world triggers (**Sparks**). The catalog registers **67 executors** in `NodeCatalog`, **62 of them offered in the picker** and 5 kept Hidden until their replacements ship (`StepGoldenTest` records every id and param key; `NodeCatalogTest` checks ids are unique). Step ids are `<category>.<name>` (for example `net.http`, `data.set`, `spark.webhook`); every id from earlier builds still loads through the alias map. The audit in `docs/audit/` documents known correctness gaps in several of them, so treat the list below as what is registered, not what is fully reliable:

* **Sparks:** Manual, Cron/recurring schedules, local webhooks, incoming SMS, notification listener, battery state, Share Sheet, geofence, Bluetooth, Wi-Fi, headset and phone-restarted events.
* **Logic & Flow Control:** If/Else, Switch, Merge (append, position, match on fields, every combination, pass one input), Filter, Remove Duplicates (per Run, per day or across Runs, also for web feeds), Loop over items (real batches), Wait, Stop, Error Handler, Sort (also random shuffle), Limit, Compare Lists, Local Table (hidden until Data Table ships) and Pass Through.
* **Transformations & Developer Tools:** Edit fields (one hub: typed assignments, text, math, JSON and URL helpers, rename, remove, keep only), date parsing/formatting/shifting/time-zone conversion, Code (not yet functional), Crypto (hashes, HMAC, Base64, UUID, random strings, signed tokens), Split Items, XML, HTML, Markdown, iCal, One-Time Code and PDF Text. Aggregate, Number Summary, CSV and Binary Tools stay hidden until their replacements ship.
* **AI & Intelligence:** Multi-provider AI chat (Gemini, local Ollama, OpenAI-compatible endpoints), structured extraction and classification.
* **Device & Hardware:** Notifications (also as a toast), SMS, clipboard (read or write), deep-link launcher, Text-to-Speech, haptics, file persistence, battery and connection info (including Wi-Fi details), share sheet, torch, volume, location, contact lookup and calendar events.
* **SaaS / Protocols:** Universal HTTP (plain, paged with retries, GraphQL, WebDAV-style file requests and multipart upload), RSS/Atom, Google Sheets, Notion, Telegram, Slack and Discord. The raw TCP, Chart, Weather and Random number Steps were removed; old Flows keep them as Unavailable Steps or convert (Weather becomes an HTTP request).

### Designed for a phone
* **Binary-first data:** Attachments keep their MIME type, file name, byte size, checksum and internal URI alongside JSON items.
* **Portable Flows:** `.kaivoflow` full backups preserve ordinary parameters, redact credentials, remap IDs and disable imported Sparks. The original `.structure` skeleton format remains supported.
* **Composable engine:** A Step can run another Flow and bring its items back; the HTTP Step shapes and paginates any REST API; Remove Duplicates can emit only items unseen since the last Run.
* **Starter templates:** Five original phone-first templates create fully editable Flows with fresh Step/Stream IDs.
* **Connected accounts:** Refresh-token and client-credentials grants run locally; rotated tokens are encrypted in the existing credential vault.
* **Touch-first canvas:** One draggable 48 dp target per declared output port, a dashed bezier preview, magnetic input snapping, success haptics, branch-aware Step chaining and density-correct pan/zoom coordinates.
* **Private by default:** No KaivoFlow account, tracking backend or required relay server. Sensitive OS capabilities are permission-gated.

---

## Architecture & Modules

The project is structured into 13 modular Gradle components:

| Module | Purpose |
|---|---|
| `:core:model` | Immutable domain models (`Flow`, `StepNode`, `StreamEdge`, `FlowData`, `BinaryDataRef`, `RunRecord`, `ScheduleMath`). |
| `:core:engine` | FlowRunner, DAG validation, port liveness, retries, expressions/parameters, `FlowInvoker` and `OAuth2TokenManager`. |
| `:core:expressions` | Expression runtime, **under reconstruction**: field lookup such as `{{ $json.field }}` and `{{ $now }}` work today, but arithmetic is not yet available and neither is the Code step. A new parser and evaluator is planned in Phase 2 of [docs/ROADMAP.md](docs/ROADMAP.md). |
| `:core:nodes` | 67 built-in Step executors (62 visible), pure-JVM parsers/formatters, the alias map and converters for old Flows, and the single `NodeCatalog` registry. |
| `:core:data` | Room database, DAOs, repositories, wrapped JSON codec, `.structure` and `.kaivoflow` bundles, encrypted credential/token store. |
| `:core:network` | Ktor/OkHttp gateway for HTTP, binary transport, webhooks and AI calls. |
| `:core:work` | WorkManager runs, `FlowScheduler`, event receivers, boot re-arming and `AndroidDeviceGateway`. |
| `:core:design` | Design tokens, typography, Velvet Obsidian & Champagne Gold theme, shared components. |
| `:feature:canvas` | Touch-first visual canvas, per-port Stream gestures, bezier rendering, Step Inspector and run HUD. |
| `:feature:flows` | Flow library, execution history and dual-format import/export. |
| `:feature:permissions` | Android permission center (Notification listener, SMS, Alarms). |
| `:feature:settings` | App settings, Privacy Center, Encrypted Credential Vault and connected accounts. |
| `:app` | Application entry point, `MainActivity`, Navigation graph, Hilt DI root. |

The design principle is strict: decisions about *what happens* live in Android-free pure Kotlin with JVM tests; OS access lives in thin platform adapters. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the engine blueprint and [docs/audit/](docs/audit/) for the current findings.

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
gradle :app:assembleSideloadDebug   # or :app:assemblePlayDebug

# 3. Run unit test suites
gradle :core:nodes:test :core:engine:test :core:data:test

# 4. Install onto connected Android device via ADB
adb install -r app/build/outputs/apk/sideload/debug/app-sideload-debug.apk
```

> **Verification status:** This cloud-authoring session ran static Kotlin/XML/diff checks only. Gradle compilation, JVM tests, APK assembly and physical-device verification are intentionally left to a local engineer with the Android SDK — see [docs/ROADMAP.md](docs/ROADMAP.md) for the gates and [docs/PROGRESS.md](docs/PROGRESS.md) for status.

---

## Developer & Cloud IDE Handoff

For plans, status and findings, see:
👉 **[docs/ROADMAP.md](docs/ROADMAP.md)** · **[docs/PROGRESS.md](docs/PROGRESS.md)** · **[docs/audit/](docs/audit/)**
