# KaivoFlow — Architecture & Engine Blueprint

> **Design Principle:** Everything that decides *what happens* is Android-free, pure Kotlin, and unit-tested. Everything that touches the operating system is a thin platform adapter. If a bug can be phrased as *"given this Flow and these items, what comes out"*, it lives in pure core modules with deterministic tests.

---

## 1. System Philosophy & Sovereign Mobile-First Design

KaivoFlow is an on-device visual workflow automation engine for Android 8.0+ (API 26 → 34/35). Unlike cloud-based orchestrators, KaivoFlow executes entire workflows locally:

* **Zero Cloud Dependency:** No server instances, no containers, and no subscriptions.
* **Direct Hardware Access:** Interacts directly with Android sensors, SMS, notifications, clipboard, battery, and local network.
* **Thumb-Centric UX:** Visual canvas built specifically for touchscreens in Jetpack Compose (Velvet Obsidian & Champagne Gold design system).

---

## 2. Module Decomposition (13 Gradle Modules)

```
KaivoFlow/
├── core/
│   ├── model/         # Value classes: Flow, StepNode, StreamEdge, FlowData, RunRecord, ScheduleMath
│   ├── engine/        # Topological DAG runner (FlowRunner), StepContext, DAG validation, events
│   ├── expressions/   # Sandboxed evaluation: {{ $json.field }}, {{ $now }}, math, string helpers
│   ├── nodes/         # 87 StepExecutors, bounded pure-JVM parsers/formatters & NodeCatalog registry
│   ├── network/       # Ktor/OkHttp gateway for HTTP Request, Webhooks, and AI APIs
│   ├── data/          # Room DB, DAOs, FlowRepository, JSON codec, .structure & .kaivoflow bundles, encrypted token store
│   ├── design/        # Design tokens, typography, Jewel Cards, Canvas layout math
│   └── work/          # WorkManager workers (FlowRunWorker), FlowScheduler, BootReceiver, AndroidDeviceGateway
├── feature/
│   ├── canvas/        # Visual node-graph editor, Stream bezier curves, Step Inspector sheet
│   ├── flows/         # Flow list, execution run history, native import/export
│   ├── permissions/   # Sensitive Android permission center (SMS, Notification Listener, Exact Alarms)
│   └── settings/      # Settings, Privacy Centre, Encrypted Credential Vault UI
└── app/               # Main Application, MainActivity, Navigation graph, Hilt DI root
```

---

## 3. Data Model & Immutability

```
Flow ── steps: ImmutableList<StepNode>
     ── streams: ImmutableList<StreamEdge>
     ── vars: ImmutableMap<String, Any?>
     ── settings: FlowSettings
```

* **`Flow` is an Immutable Value:** Editing any node, property, or link produces a new `Flow`. Nothing is mutated in place. This guarantees trivial undo/redo, deterministic canvas diffing, debounced autosave, and safe Room database persistence.
* **Dynamic Parameters:** `StepNode.params` is a typed `Map<String, Any?>` validated against `NodeSpec.params` (`ParamSpec`). This eliminates the need for 87 separate database migration tables and allows declarative UI generation in the Step Inspector. Secret params are read raw and are never expression-expanded.
* **Items Model:** Data flows as `List<FlowData>`, where each `FlowData(val json: Map<String, Any?>, val binary: Map<String, BinaryDataRef>)` represents one payload item passing through output ports to downstream steps. A `BinaryDataRef` records internal URI, MIME type, file name, byte size and SHA-256. The codec supports both wrapped and legacy JSON shapes and caps binary payloads at 25 MB.

---

## 4. Execution Engine (`FlowRunner`)

The engine processes workflows as a Directed Acyclic Graph (DAG):

1. **Trigger Seeding:** Every trigger (**Spark**) produces `RunTrigger.items`.
   * **Manual / Scheduled Ticks:** Emit a typed initial payload `{ "timestamp": now, "executionId": id, "trigger": kind }` so downstream action steps receive data and execute without being bypassed.
   * **Event Sparks:** Incoming SMS, push notifications, webhooks, or share actions seed the run with real-world event data.
2. **Topological Execution:** Steps execute in topological order. Independent parallel branches execute concurrently up to `maxConcurrency = 4`.
3. **Port-Level Liveness:** In branching nodes (e.g. `IfNode`, `SwitchNode`), output ports define which downstream steps become live. An un-triggered branch's downstream steps are cleanly `Skipped`.
4. **Error Handling & Retries:** Configurable per-step policies:
   * `OnErrorPolicy.Stop`: Halts execution immediately and reports failure.
   * `OnErrorPolicy.Continue`: Emits an error item and marks run as `PartialSuccess`.
   * `OnErrorPolicy.Retry`: Exponential backoff with jitter up to `maxDelayMs`.
5. **Freeze Output:** Developers can freeze a step's output to replay cached fixtures during flow design, avoiding repeated API or AI calls.

---

## 5. Expression Runtime

Step parameters can interpolate dynamic variables using `{{ expression }}`:

* Available scopes: `$json`, `$items`, `$item`, `$index`, `$vars`, `$run`, `$now`, `$today`.
* Cross-step referencing: `$step("Step Name")` or `$node["Step Name"]`.
* Pure-Kotlin sandboxed fallback engine provides robust nested call parsing, string transformations (`$lower`, `$upper`, `$trim`), and arithmetic with zero native crashes.

---

## 6. Built-in Step Catalog (87 Nodes)

* **Sparks (12):** Manual, schedule, webhook, notification, SMS, battery, share, geofence, Bluetooth, Wi-Fi, headset and boot. Device Sparks use strict, fail-closed filters and OS broadcast/scheduler wiring.
* **Logic (17):** Original `if`, `switch`, `merge`, `filter`, `dedupe`, `loop`, `wait`, `stop` and `onError`, plus `sort`, `limit`, `compare`, `table`, `run_info`, `noop`, `subflow` and `poll`.
* **Transformations (25):** Original `set`, `fields`, `json`, `template`, `date` (parse/format/shift/diff/zone conversion), `aggregate`, `code` and `crypto`, plus `math`, `text`, `stats`, `split`, `url`, `csv`, `xml`, `html`, `markdown`, `ical`, `binary`, `binary_upload`, `chart`, `random`, `token`, `totp` and `pdf`.
* **AI & Machine Learning (3):** `ai.chat`, `ai.extract`, `ai.classify`. Compatible with Google Gemini, local Ollama, and OpenAI-compatible endpoints with structured JSON schemas.
* **Device Hardware (18):** Notification, SMS, clipboard read/write, app/URL launcher, Text-to-Speech, haptics, file persistence, battery/network status, toast, share sheet, torch, volume, location, Wi-Fi info, contact lookup and calendar events.
* **SaaS / Protocols (12):** Universal REST client, Google Sheets, Notion, Telegram, Slack, Discord, Weather, RSS/Atom, GraphQL, WebDAV-style file requests, bounded TCP/TLS messages and the generic Service Connector.
* Bounded pure-JVM parsers/formatters (`MiniJson`, CSV, XML, HTML, Markdown, iCal, JWT, TOTP and best-effort PDF text) have explicit size/depth caps and return typed errors. See `PARITY.md` for the complete matrix.
* **Composition tools:** `logic.subflow` reuses another Flow through `FlowInvoker` (depth cap 8, nested Runs not persisted); `saas.connector` calls any REST API with credential auth, dot-path response extraction, bounded pagination and retries; `logic.poll` remembers item IDs/hashes in a private local file and emits only new items. Built-in `FlowTemplates` create five editable starter Flows with fresh IDs.

---

## 7. Background Scheduling & Reliability

* **WorkManager Chaining:** Scheduled tasks use `OneTimeWorkRequest` tagged with `TAG_RESCHEDULE`. Upon task completion, `FlowRunWorker` computes the subsequent occurrence via `ScheduleMath` and schedules the next run.
* **Boot & Time Synchronization:** `BootReceiver` listens to `ACTION_BOOT_COMPLETED`, `ACTION_MY_PACKAGE_REPLACED`, `ACTION_TIMEZONE_CHANGED`, and `ACTION_TIME_CHANGED` to ensure all active workflows remain scheduled across device restarts.
* **Fail-Closed Event Filtering:** `EventTriggerMatcher` evaluates incoming device events against configured package allowlists, sender regex, and keyword filters before starting a workflow.

---

## 8. Canvas & Touch Interface

* **Dual Layout Modes:**
  * **Guided Path (Default):** Centered rows grouped by topological distance from the trigger; reads top-to-bottom like a clear document.
  * **Freeform Atelier:** Spatial canvas with snap-to-grid (16 dp), pinch-to-zoom (0.35x–2.0x), and glass minimap.
* **Performance Contract:** Single hardware-accelerated Compose layer; stream lines rendered via cached cubic Beziers; off-screen nodes culled outside viewport buffer. Zero per-frame allocations during drag and pinch gestures.
* **Touch-Linking:** `NodeSpec.outputs` declares the number of output ports; `CanvasGeometry.guidedLayout`/`freeformLayout` and `JewelStepCard` use that count. Each port is a 48 dp drag target with a dashed bezier preview and magnetic input snapping. A haptic marks a successful Stream only; empty-canvas drops chain a new Step from the chosen output port. `connectStream` rejects cycles.
* **Coordinate Units:** StepLayout is world dp; screen pixels are converted once with `pixelScale` at card height, bezier lift, visible rect, fit, minimap and drag-delta boundaries. `worldDelta = screenDelta.toDp() / zoomScale` keeps cards, ports and paths aligned while panning and pinching.

---

## 9. Privacy & Security

* **Zero Backend:** No KaivoFlow account, tracking, or cloud relay server.
* **Hardware-Backed Credential Vault:** API keys and credentials are encrypted using AES-GCM via the Android Keystore and excluded from system backups.
* **Portable-Flow Redaction:** `.kaivoflow` backup excludes declared secret params, secret-shaped key/value names, credential-store references and binary frozen output. Imported Sparks are disabled until the owner explicitly enables them.
* **Connected Accounts:** `OAuth2TokenManager` refreshes tokens with per-credential mutexes and a 60-second expiry skew. Rotated `accessToken`/`refreshToken` values are encrypted by `RoomOAuth2TokenStore` through `CryptoBox`. Secret params are never expression-expanded.
* **Fine-Grained Permissions:** Sensitive capabilities (SMS, Notification Listener, location, torch, contacts and calendar) are encapsulated in platform adapters requested only when a specific Flow requires them.
* **Verification Boundary:** Cloud agents author pure Kotlin/Compose and static checks only. Gradle compilation, JVM tests, APK assembly, permission prompts and physical-device behaviour are local-engineer responsibilities; see `HANDOFF.md`.
