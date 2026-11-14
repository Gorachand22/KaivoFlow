# KaivoFlow — Engineering Backlog & Task Priorities

> **Tracking issues, technical debt, and feature enhancements by severity.**
> Cloud agents author Kotlin/Compose only; compilation, Gradle tests and physical-device runs stay local.

---

## Priority 0 (Blockers & Reliability)
- [x] **P0-01:** Build compilation failure (toolchain version mismatch, unresolved imports). -> *FIXED*
- [x] **P0-02:** Trigger emission: manual and scheduled runs pass empty lists, bypassing downstream nodes. -> *FIXED*
- [x] **P0-03:** Scheduled flows lack recurring execution loop after worker completion. -> *FIXED*
- [x] **P0-04:** Event trigger dispatch ignores package/sender/keyword filters. -> *FIXED*

---

## Priority 1 (Core Functional Parity)
- [x] **P1-01: First-Class Binary Data Model**
  *Implemented as `BinaryDataRef`/`FlowData.binary`: MIME type, file name, byte size, checksum, internal URI, wrapped/legacy JSON codec, multipart + base64 HTTP transport, 25 MB caps, device binary store/load, binary tools and upload Steps. A full document picker UI remains a device-side polish item.*
- [x] **P1-02: Canvas Touch-Linking Drag & Drop**
  *Implemented with 48 dp per-output port targets, dashed bezier preview, magnetic input snapping, branch-aware "next Step" chaining, start haptic and success-only confirmation haptic. Cycle rejection still shows the friendly loop message.*
- [x] **P1-03: Parameter-Preserving Native Flow Export/Import**
  *Implemented `.kaivoflow` full backup (`kaivoflow.flow` v1): validates graph, redacts secret-shaped values and declared secret params, remaps Flow/Step/Stream IDs, strips binary frozen output, disables imported Sparks, clears timestamps/favorites, and asks for explicit confirmation before saving. Structure-only `.structure` sharing remains available.*
- [x] **P1-04: Lightweight OAuth2 Token Manager**
  *Implemented a pure-Kotlin manager with per-credential mutexes, expiry skew, refresh-token and client-credentials grants, rotated-token persistence through the encrypted vault, and a connected-account form in the Credentials screen. HTTP/AI/SaaS Steps now prefer `accessToken` and fall back to `apiKey`.*

---

## Priority 2 (UI & Polish)
- [x] **P2-01: Font Scale & Insets Audit**
  *All new screens use `sp` typography from the theme, status/navigation bar padding and IME padding; interactive pills, segmented controls and port targets are at least 48 dp. Physical 1.3x / 2.0x font-scale screenshots are still required on a device.*
- [x] **P2-02: Card Drag Coordinate Snapping**
  *World coordinates are dp; pan/zoom, card translation, Stream paths, port centres, card heights, culling and drag deltas are converted through a single `pixelScale`. `worldDelta = screenDelta.toDp() / zoomScale`; card movement and port linking stay aligned during pinch/zoom.*

---

## Cloud-Authoring Verification (this session)
- [x] Kotlin delimiter/string sanity check: **107 files, 0 issues**.
- [x] `git diff --check`: clean.
- [x] Android manifest and resource XML parse check: clean.
- [x] JVM test suites authored/updated: `ExtendedNodeTest`, `ExtendedCompositionTest`, `FlowTemplatesTest`, `OAuth2TokenManagerTest`, `MobileTriggerTest`, `NativeFlowBundleTest`, `CanvasGeometryTest`.
- [ ] `kotlinc` / Gradle / Android SDK execution: **not run here** (cloud role is pure authoring; no JDK/Gradle installed).
- [ ] APK build and physical-phone verification: **local agent only**.

---

## Next Device-Side Priorities
1. Run `gradle :core:nodes:test :core:engine:test :core:data:test :app:assembleDebug` locally, then fix any compiler diagnostics.
2. Verify native `.kaivoflow` export → import on two phones, including the credential re-bind preview.
3. Verify geofence, Bluetooth, Wi-Fi, headset and boot Sparks with real OS broadcasts and runtime permissions.
4. Verify binary download → ZIP/gzip → upload with a real `content://` document.
5. Capture 1.3x / 2.0x font-scale screenshots of Flow List, Canvas, Inspector, Credentials and Run History.
6. Verify `logic.subflow` with two saved Flows, including recursion guard and returned items.
7. Verify `saas.connector` against a mock paginated REST endpoint with a saved credential.
8. Verify `logic.poll` across three scheduled Runs with repeated and new IDs.
9. Verify each starter template creates an editable Flow with fresh IDs and working Streams.
