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
│   ├── nodes/         # 38+ StepExecutors & NodeCatalog registry (Sparks, Transforms, Logic, Device, AI, SaaS)
│   ├── network/       # Ktor/OkHttp gateway for HTTP Request, Webhooks, and AI APIs
│   ├── data/          # Room DB, DAOs, FlowRepository, JSON codec, structure import/export
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
* **Dynamic Parameters:** `StepNode.params` is a typed `Map<String, Any?>` validated against `NodeSpec.params` (`ParamSpec`). This eliminates the need for 38+ separate database migration tables and allows declarative UI generation in the Step Inspector.
* **Items Model:** Data flows as `List<FlowData>`, where each `FlowData(val json: Map<String, Any?>)` represents an individual payload item passing through output ports to downstream steps.

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

## 6. Built-in Step Catalog (38+ Nodes)

* **Sparks (Triggers):** `trigger.manual`, `trigger.schedule`, `trigger.webhook`, `trigger.notification`, `trigger.sms`, `trigger.share`, `trigger.battery`.
* **Logic:** `logic.if` (2-port boolean split), `logic.switch` (5-port value router), `logic.merge`, `logic.filter`, `logic.dedupe`, `logic.loop` (batch splitter), `logic.wait`, `logic.stop`, `logic.onError`.
* **Transformations:** `transform.set` (visual field mapper), `transform.fields` (rename/delete), `transform.json` (parse/stringify), `transform.template` (text templating), `transform.date` (arithmetic & formatting), `transform.aggregate` (group & sum), `transform.code` (JavaScript execution), `transform.crypto` (SHA-256, MD5, SHA-1, HMAC-SHA256, Base64 encode/decode, UUID generation).
* **AI & Machine Learning:** `ai.chat` (Ask AI), `ai.extract` (structured data extraction), `ai.classify` (text categorization). Compatible with Google Gemini, local Ollama, and OpenAI-compatible endpoints with structured JSON schemas.
* **Device Hardware:** `device.notify` (status bar notification), `device.sms` (send text), `device.clipboard`, `device.open` (URL/App launcher), `device.speak` (Text-to-Speech), `device.vibrate` (haptic patterns), `device.file` (local state persistence), `device.battery_status` (battery level & temperature), `device.network_status` (Wi-Fi, cellular, metered status).
* **SaaS Connectors:** `saas.http` (Universal REST client), Google Sheets, Notion, Telegram, Slack, Discord, Weather.

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

---

## 9. Privacy & Security

* **Zero Backend:** No KaivoFlow account, tracking, or cloud relay server.
* **Hardware-Backed Credential Vault:** API keys and credentials are encrypted using AES-GCM via the Android Keystore and excluded from system backups.
* **Fine-Grained Permissions:** Sensitive capabilities (SMS, Notification Listener) are encapsulated in separate modules requested only when a specific flow requires them.
