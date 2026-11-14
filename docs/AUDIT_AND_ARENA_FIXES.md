# KaivoFlow — Core Engine Architecture & Mobile-First Production Roadmap

> **Author / Sovereign Project:** KaivoFlow (Independent Mobile Visual Automation Engine)  
> **Target Platform:** Android (Serverless, Local-First, Zero-Cloud-Dependency)  
> **Theme:** Velvet Obsidian & Champagne Gold  
> **Status:** Base Build Stable & Verified on Physical Device (`com.kaivoflow.app.debug`)  

---

## 1. Architectural Vision: Sovereign Mobile-First Automation

KaivoFlow is designed as a self-contained, enterprise-grade visual workflow automation platform that runs **entirely on-device**. Unlike traditional server-bound automation tools that require dedicated cloud instances, Docker setups, and recurring infrastructure costs, KaivoFlow executes complex multi-step pipelines directly on the user's mobile device with native access to:

1. **Hardware & Sensors:** Battery state, cellular/WiFi status, haptic feedback, camera, flashlight, clipboard.
2. **Local Communication:** SMS interception and sending, rich push notification listener and creation.
3. **Local-First Storage:** Android Storage Access Framework (SAF), encrypted app storage, SQLite/Room.
4. **Resilient Scheduling:** Exact alarms (`AlarmManager.setExactAndAllowWhileIdle`), background WorkManager execution, and automatic boot recovery.
5. **AI & Intelligence:** On-device or custom API LLMs (Gemini, local Ollama, Groq, OpenAI-compatible) with native tool-calling capabilities.

---

## 2. Core Execution Engine: Gaps & Direct Implementations

### Gap 1: Trigger Data Emission (P0 — Immediate Engine Fix)
- **Problem:** When a user initiates a manual run or a scheduled flow triggers, the trigger emission previously produced an empty data list (`items = emptyList()`). Downstream transformation and action steps check `incomingItems.isEmpty()` and skipped execution entirely.
- **Implementation:** Every trigger step (Manual Run, Cron Schedule, Webhook, SMS, Notification) emits an initial typed execution item:
  ```json
  {
    "timestamp": 1731512400000,
    "executionId": "run_01j...",
    "triggerType": "manual",
    "device": { "model": "RMX3461", "battery": 85, "network": "WIFI" }
  }
  ```
  This guarantees that downstream steps (HTTP Requests, Code, Logic, AI) receive rich context and execute without being bypassed.

### Gap 2: Exact Scheduling & Rescheduling Loop (P0)
- **Problem:** WorkManager one-time tasks finish without enqueuing the subsequent occurrence. Additionally, Android Doze mode causes standard workers to suffer from 15+ minute battery jitter.
- **Implementation:** 
  1. A two-tier scheduler:
     - **Exact Schedules:** `AlarmManager` with `setExactAndAllowWhileIdle` and a `WakefulBroadcastReceiver` for time-critical flows.
     - **Coarse/Periodic Schedules:** `PeriodicWorkRequestBuilder` with automatic fallback.
  2. Auto-rescheduling hook inside `FlowRunWorker.doWork()` that calculates the subsequent trigger time using `ScheduleMath` and enqueues the next cycle upon completion.
  3. `BootReceiver` hook to re-register all active schedules upon `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED`.

### Gap 3: Fine-Grained Event Trigger Filtering (P0)
- **Problem:** SMS and Notification listeners triggered flows based only on event type, ignoring configured parameters like sender regex, keyword search, or application package name.
- **Implementation:** Implement `EventTriggerMatcher` that runs predicate checks against:
  - SMS: `senderRegex`, `bodyContains`, `minAmount`.
  - Notification: `packageAllowList`, `titleRegex`, `textContains`, `importanceThreshold`.
  - Battery/Power: `chargingState`, `levelThreshold`, `temperature`.

### Gap 4: Binary File Support & Android SAF Integration (P1)
- **Problem:** Data model was constrained to pure JSON maps (`Map<String, Any?>`), preventing flows from processing images, documents, audio clips, or downloaded PDFs.
- **Implementation:** Enhance `FlowData` with a first-class binary container:
  ```kotlin
  data class FlowData(
      val json: Map<String, Any?> = emptyMap(),
      val binary: Map<String, BinaryItem> = emptyMap()
  )
  data class BinaryItem(
      val uri: String,
      val mimeType: String,
      val fileName: String,
      val sizeBytes: Long
  )
  ```
  Add a dedicated `device.storage` node capable of reading, writing, and sharing files using Android `content://` URIs.

### Gap 5: Sovereign Native Flow Format (.kaivoflow / .structure)
- **Problem:** Need an independent, secure, and robust flow interchange format native to KaivoFlow only.
- **Implementation:** 
  - Standardized JSON specification with magic header: `"format": "kaivoflow.structure.v1"`.
  - Includes node metadata, coordinates, stream connections, and parameter definitions.
  - Excludes sensitive credentials (API keys, tokens) by design, with a structured re-binding wizard upon import.

---

## 3. Developer-Grade Node Library Additions

1. **Transform Nodes:**
   - `transform.set`: Visual key-value field editor (add, rename, delete fields without code).
   - `transform.crypto`: Native SHA-256, HMAC, MD5, Base64 encode/decode, and UUID generation.
   - `transform.filter`: Filter array items by field comparisons.
2. **Logic Nodes:**
   - `logic.condition`: True/False dual-output branching based on expressions.
   - `logic.delay`: Non-blocking coroutine delays with timeout protections.
3. **Hardware Nodes:**
   - `device.notification`: Emit rich system notifications with action buttons.
   - `device.clipboard`: Read/Write clipboard with security checks.
   - `device.vibrate`: Custom haptic patterns.
   - `device.battery`: Real-time battery status & temperature interrogation.
   - `device.connectivity`: Network type detection (WiFi/Cellular/Offline) and host reachability test.
4. **AI & Agent Nodes:**
   - Universal OpenAI-compatible client supporting Gemini, Ollama, Groq, and custom self-hosted LLMs.
   - Structured JSON schema response enforcement.
   - Autonomous Tool Calling node capable of orchestrating other KaivoFlow device actions.
