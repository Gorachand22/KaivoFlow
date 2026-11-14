# KaivoFlow — Cloud IDE & Arena AI Developer Handoff Guide

> **Project:** KaivoFlow (Independent Mobile Visual Automation Platform)
> **Repository:** `https://github.com/Gorachand22/KaivoFlow.git`
> **Target OS:** Android 8.0+ (API 26 → 34/35)
> **UI Architecture:** Jetpack Compose (Velvet Obsidian & Champagne Gold Theme)
> **Cloud Build Status:** ⚠️ Pure Kotlin/Compose authoring complete; **no JDK/Kotlin/Gradle/Android SDK in this environment**, so compilation and device runs are intentionally local.
> **Static Checks:** 102 Kotlin files passed delimiter/string checks; `git diff --check` and XML parse checks are clean.

---

## 1. Project Overview & Author Vision

KaivoFlow is an independent, sovereign, mobile-first visual automation system designed to run **100% on-device** without needing cloud servers, virtual machines, or external Docker deployments. Users design automation workflows (**Flows**) of nodes (**Steps**) linked by directed edges (**Streams**).

The app includes:
* **Rich Trigger System:** Manual taps, Cron schedules, webhooks, SMS, notification listener, battery state, Share Sheet, geofence, Bluetooth, Wi-Fi, headset and boot events.
* **87 Original Built-in Steps:** Logic, transforms, AI, device hardware, SaaS/protocol connectors, structured formats, binary attachments, token tools and local table state.
* **Pure Local Execution:** Flows run via Android `WorkManager`, Foreground Services, receivers and `AlarmManager`.
* **Zero External Dependencies:** Self-contained, fair, independent, and designed strictly for mobile thumbs.

---

## 2. Architecture & Module Map (13 Modules)

```
KaivoFlow/
├── app/                  # Main Android Application, Application class, MainActivity, Navigation
├── core/
│   ├── model/            # Pure Kotlin data classes (Flow, StepNode, StreamEdge, FlowData, BinaryDataRef, RunRecord)
│   ├── engine/           # FlowRunner, DAG validation, lifecycle events, OAuth2TokenManager
│   ├── expressions/      # Sandboxed Expression Evaluator ({{ $json.field }}, {{ $now }}, math)
│   ├── nodes/            # 87 StepExecutors, bounded pure-JVM parsers/formatters, composition tools, NodeCatalog
│   ├── network/          # Ktor/OkHttp gateway for HTTP, binary transport, webhooks, AI calls
│   ├── data/             # Room Database, DAOs, Repositories, JSON codecs, .structure & .kaivoflow bundles
│   ├── design/           # Theme, colors, typography, shared components, Canvas tokens
│   └── work/             # WorkManager workers, FlowScheduler, event/boot receivers, AndroidDeviceGateway
└── feature/
    ├── canvas/           # Touch-first canvas editor, per-port bezier Streams, Step Inspector
    ├── flows/            # Flow list, Run history, dual-format import/export UI
    ├── permissions/      # Android permission center (SMS, Notification Listener, Exact Alarms)
    └── settings/         # App settings, Privacy Center, Credential Vault + connected accounts
```

---

## 3. Work Authored in the Cloud Session

### Foundation already in the branch
1. **Pinned toolchain:** AGP `8.3.2`, Kotlin `2.0.21`, KSP `2.0.21-1.0.28`, Compose BOM `2024.10.01`, `jvmToolchain(21)`, `compileSdk = 34`.
2. **P0 trigger reliability:** Manual/scheduled Sparks emit real payloads; recurring work re-enqueues itself; `BootReceiver` handles boot, package replacement and time changes; `EventTriggerMatcher` enforces fail-closed filters.
3. **First-class binary data:** `BinaryDataRef` carries URI, MIME, file name, size and checksum. `FlowData.binary` is preserved by a wrapped/legacy JSON codec. HTTP supports bounded binary downloads, base64 JSON bodies and hand-built multipart uploads. `DeviceGateway` stores/loads binary attachments with a 25 MB cap.
4. **Native full backup:** `.kaivoflow` (`kaivoflow.flow` v1) validates the graph, redacts declared secrets and secret-shaped values, remaps IDs, strips binary frozen output, disables imported Sparks and rebinds credentials on import. The dual-format UI still supports `.structure` skeleton sharing.
5. **Mobile-native Sparks:** Geofence, Bluetooth, Wi-Fi, headset and boot triggers with OS broadcast/scheduler wiring and fail-closed event matching.

### Latest cloud-authored additions
6. **36 original Steps:** math/text/stats/split/url, CSV/XML/HTML/Markdown/iCal, binary tools/upload, sort/limit/compare/table/run-info/noop/chart/random, JWT/TOTP/PDF text, RSS/GraphQL/WebDAV/TCP, and clipboard/toast/share/torch/volume/location/Wi-Fi/contact/calendar device tools. Date & Time gained explicit parse and IANA time-zone conversion actions. A strict pure-JVM `MiniJson` parser backs protocol/format nodes with size and depth caps.
7. **Connected accounts:** `OAuth2TokenManager` supports per-credential mutexes, 60-second expiry skew, `refresh_token` and `client_credentials` grants, Basic/body auth styles, rotated-token persistence and typed/JSON-text responses. `RoomOAuth2TokenStore` encrypts rotated fields through `CryptoBox`; Hilt provides the manager and injects it into `EncryptedCredentialRepository`. HTTP/AI/SaaS Steps prefer `accessToken` and fall back to `apiKey`.
8. **Canvas gestures:** Declared output ports are individually draggable 48 dp targets; a dashed bezier preview snaps to input ports; success haptics fire only when a Stream is actually added; the Step picker chains from the selected output port; `connectStream` rejects cycles.
9. **Canvas coordinate contract:** A single `pixelScale` converts dp↔px for card heights, port centres, bezier lift, visible rect, fit-to-screen, minimap jumps, graphics layers and drag deltas. `worldDelta = screenDelta.toDp() / zoomScale`.
10. **Mobile polish:** Status/navigation/IME insets on Flow List, Run History and Credentials; 48 dp touch targets on pills, segmented controls, back button and ports.
11. **Reference-gap batch:** A shallow, blobless, sparse read of a large external automation catalog identified portable patterns (not code to copy). Implemented original `logic.subflow`, `saas.connector`, `logic.poll`, five starter Flow templates, a `FlowInvoker` engine contract, depth-capped nested Runs, two new JVM suites and a generic capability-gap document.

### JVM tests authored (not executed here)
`ExtendedNodeTest.kt`, `ExtendedCompositionTest.kt`, `FlowTemplatesTest.kt`, `OAuth2TokenManagerTest.kt`, `MobileTriggerTest.kt`, `NativeFlowBundleTest.kt`, `CanvasGeometryTest.kt`. Helper scripts exist for pure-JVM execution where a Kotlin toolchain is available:
`scripts/run-core-reliability-tests.sh`, `scripts/run-transfer-tests.sh`, `scripts/run-canvas-tests.sh`.

---

## 4. How to Build & Test Locally

### Prerequisites:
* **JDK:** Java 21 or Java 17.
* **Android SDK:** Platforms 34 or 35 with `build-tools;34.0.0`.
* **Gradle:** 8.5+.

### Commands:
```bash
# 1. Point to your Android SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. Run unit tests across modules
gradle :core:nodes:test :core:engine:test :core:data:test

# 3. Assemble debug APK
gradle :app:assembleDebug

# 4. Install onto connected emulator or device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Resolve compiler diagnostics locally first; report exact errors to the cloud agent rather than reverting whole features. Important local checks: serialization imports, `ParamType.Secret`, new `DeviceGateway` methods, `FlowInvoker`/`StepContext` constructor sites, Hilt providers, Compose gesture and `AlertDialog` imports, `rememberFitToScreen` call sites, Android 12+ `PendingIntent.FLAG_MUTABLE`, exported receiver security and background-location limits.

---

## 5. NEEDS DEVICE VERIFICATION

See the 19-step table in [HANDOFF.md](HANDOFF.md). Highest-priority checks:
1. `assembleDebug` and all seven JVM test suites.
2. Native `.kaivoflow` export → import between two installs, with a credential re-bind preview.
3. Binary download → ZIP/gzip → multipart upload and a real `content://` document.
4. Refreshed connected-account token used by HTTP, Sheets and AI Steps.
5. Per-output port drag, branch chaining, cycle rejection and haptics at 0.35x/2x zoom.
6. Real geofence/Bluetooth/Wi-Fi/headset/boot broadcasts plus runtime permissions.
7. 1.3x/2.0x font-scale screenshots of every screen.
8. Sub-Flow composition, Service Connector pagination/retry and `logic.poll` dedupe across three Runs.
9. All five starter templates and their fresh-ID Flow creation.

---

## 6. Next Development Milestones

* Add a Storage Access Framework document-picker UI for binary attachment selection.
* Add dynamic webhook responses and wait-for-webhook resume on top of the existing local webhook server.
* Add a global error Flow setting that invokes another Flow when a Run fails.
* Add AI agent tool-calling / local vector memory on top of the existing AI Steps.
* Add an OAuth2 authorization-code Custom Tabs receiver on top of the existing refresh-token manager.
* Add undo/redo history to the Canvas ViewModel using the existing immutable `Flow` model.
* Add per-node fixture snapshots and a visual run debugger for skipped/retried branches.
* Add exported-receiver permission hardening tests and an automated Compose screenshot suite.
