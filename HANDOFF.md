# KaivoFlow — Session Handoff & Architecture Status

> **Communication bridge between Cloud Agent (authoring code) and Local Agent (compilation + device verification).**
> Local Agent runs on physical Realme 9 5G (API 33) with Gradle 8.5 & Android SDK 35.
> Cloud role: pure Kotlin/Compose authoring only. Do not run or install Gradle, a JDK, npm or build tools in the cloud IDE.

---

## Current Session Status
* **Active Branch:** `main` (Fully up to date, commit `c772864`).
* **Previous Arena Branch:** `origin/arena/bfa021c7-kaivoflow` is **100% merged into main** (commit `2ec4755` is an ancestor of `main`).
* **Compilation & Device Testing:** All recent code compiled, tests passing (438 Gradle tasks, 0 test failures), and verified on physical Android phone.
* **Architecture Docs:** See `docs/ARCHITECTURE.md`, `docs/CAPABILITY_GAP.md`, `PARITY.md`, `DEVELOPER_HANDOFF.md`, and `AGENTS.md`.

---

## What Has Been Completed & Verified on Device (On `main`)
1. **Visual Step Grouping (`FlowGroup`):**
   - Jewel-toned bounding container with header drag bar, dynamic step count pill, edit dialog (color & name), and ungroup action.
   - Group dragging calculates world delta (`screenDelta / zoomScale`) and translates all member steps simultaneously without recomposing cards.
2. **Sticky Notes (`StickyNote`):**
   - Freeform world-coordinate documentation cards on canvas with title, body text, jewel tone accents, drag handle, and tap-to-edit dialog.
3. **Inline Flow Renaming & Developer Info Dialog:**
   - Tap flow title in Canvas Top Bar to rename directly via Velvet Obsidian dialog.
   - Developer `(i)` button showing Step count, Stream count, Group count, Note count, and sovereign JSON path.
4. **Live Auto-Save with Status Pill:**
   - Auto-save debounced saving pill in Canvas Top Bar ("Saving…" in champagne gold -> "Saved" with checkmark).
5. **24/7 Background Deployment Toggle (`Flow.isActive`):**
   - Canvas Top Bar toggle switch (`Active` vs `Manual`) for persistent background automation.
6. **Sovereign File Mirroring:**
   - Every Flow is saved both to Room Database and mirrored directly to internal storage: `context.filesDir/flows/${flow.id}.json`.
7. **Import / Export & Template Picker:**
   - Native `.kaivoflow` import/export dialogs and template flow picker with full Velvet Obsidian styling.

---

## Current Roadmap: Next Focus Areas for Arena AI
1. **Autonomous ReAct AI Agent Node (`ai.agent`):**
   - Multi-turn tool-calling ReAct loop: receives goal, calls available Flow steps as tools, evaluates observations, and outputs structured result.
   - Multi-provider LLM selector (Gemini 1.5/2.0 Flash, Claude 3.5 Sonnet, OpenAI GPT-4o, Local On-Device AI via Ollama/Exec).
2. **Advanced Control Flow Nodes:**
   - **SplitInBatches (`logic.split_in_batches`):** splits large lists into batches of N items; downstream loops until all batches processed.
   - **Multi-Output Dynamic Switch (`logic.switch`):** evaluates multiple expression rules and routes items to port 0, 1, 2, 3... or fallback default port.
   - **Advanced Merge Modes (`logic.merge`):** Append, Merge by Key, Multiplex, Wait for Both Inputs.
3. **Canvas Step Testing & Inspector Enhancements:**
   - **Test Step execution:** Run a single isolated Step with mock or upstream input data directly from the Inspector Sheet.
   - **Pinned / Frozen Output Viewer:** visual toggle to freeze output and edit mock JSON on any step.

---

## Summary of Changes
1. **Engine/model:** First-class `BinaryDataRef` attachments with wrapped/legacy JSON codec, bounded HTTP multipart/base64 transport and device binary storage; immutable `Flow` remains the single source of truth.
2. **Portable Flows:** `.kaivoflow` full backup with secret redaction, ID remap, graph validation, disabled imported Sparks and an explicit re-bind/save UI; `.structure` skeleton sharing still works.
3. **Mobile Sparks:** Geofence, Bluetooth, Wi-Fi, headset and boot triggers with fail-closed matchers, scheduler/receiver wiring and boot re-arming.
4. **Expanded catalog:** 36 original Steps for math, text, formats, binary, JWT/TOTP, RSS/GraphQL/WebDAV/TCP, and Android hardware; Date & Time gained explicit parse and IANA time-zone conversion actions; the catalog now has 87 executors.
5. **Composition batch:** `logic.subflow` (depth-capped Flow reuse), `saas.connector` (auth + response path + pagination + retries), `logic.poll` (ID/hash dedupe across Runs) and five editable starter Flow templates with a Flow List picker.
6. **Connected accounts:** Pure-Kotlin OAuth2 refresh manager with per-credential mutexes, encrypted rotated-token persistence, DI wiring, a connected-account form, and `accessToken` preference in HTTP/AI/SaaS Steps.
7. **Canvas:** Per-declared-output port drags, dashed bezier preview, magnetic input snapping, success-only haptics, branch-aware Step chaining, cycle rejection, and one `pixelScale` at every dp↔px boundary.
8. **Polish:** Status/navigation/IME insets and 48 dp targets across Flow List, Run History, Credentials and Canvas.
9. **Tests/docs:** Seven JVM test suites, three helper scripts and `docs/CAPABILITY_GAP.md`; `AGENTS.md`, `BACKLOG.md`, `PARITY.md`, `README.md`, `DEVELOPER_HANDOFF.md` and `docs/ARCHITECTURE.md` now match the implementation.

---

## Changes Requiring Local Verification
1. **Expanded catalog:** 36 original Steps for math, text, formats, binary, JWT/TOTP, RSS/GraphQL/WebDAV/TCP, and Android hardware, plus the three composition/connector/poll Steps.
2. **Engine/data/auth:** `OAuth2TokenManager` + encrypted `RoomOAuth2TokenStore`, DI wiring, Credential screen connected-account form, `accessToken` preference for HTTP/AI/SaaS.
3. **Canvas:** declared output-port counts, per-port drags, branch-aware Step picker chaining, haptic success feedback, dp↔px scaling for fit/minimap/streams/drags, 48 dp touch targets, inset/IME padding.
4. **Previously authored, still unverified:** binary foundation, `.kaivoflow` full backup/import, geofence/Bluetooth/Wi-Fi/headset/boot Sparks and broadcast wiring.

---

## Local Build & Test Commands
```bash
cd KaivoFlow
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle :core:nodes:test :core:engine:test :core:data:test :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
If a pure JVM toolchain is available instead, the existing helper scripts target the new tests:
`scripts/run-core-reliability-tests.sh`, `scripts/run-transfer-tests.sh`, `scripts/run-canvas-tests.sh`.

---

## NEEDS DEVICE VERIFICATION (For Local Agent)

| # | Screen / Step | Action on Device | Expected Result | Status |
|---|---|---|---|---|
| 1 | Compile | Run Gradle unit tests and `assembleDebug` | All modules compile; new suites pass; no unresolved imports | PENDING |
| 2 | Flow List | Launch app, tap "+" or "Start from scratch" | Canvas opens with theme, status-bar and navigation-bar padding | PENDING |
| 3 | Step Picker | Open "Add a Step" and count categories/nodes | 87 executors appear with correct icons and no duplicates | PENDING |
| 4 | Canvas ports | Drag from an If node's True output to another card's input | Dashed bezier follows the finger; success haptic fires exactly once; Stream has correct `fromPort` | PENDING |
| 5 | Branch chaining | Drop an output port on empty canvas, then choose a Step | New Stream uses the chosen output port; no cycle can be created | PENDING |
| 6 | Canvas scale | Pinch to 0.35x and 2x, pan, drag a card and a port | Cards, beziers, minimap and drop positions stay aligned; `Fit` centres the graph | PENDING |
| 7 | Typography | Set system font scale to 1.3x and 2.0x; visit all screens | No clipping; IME does not cover form fields; all controls remain tappable | PENDING |
| 8 | Binary HTTP | Download a small image, inspect it with Binary Tools, gzip it, upload via multipart | MIME, size, checksum and server response are correct; size caps are enforced | PENDING |
| 9 | Formats & Date | Feed CSV, XML, HTML, Markdown and iCal samples through new Steps; parse `2026-01-01`, shift one day, then convert an instant to `Asia/Kolkata` | Round-trip output is valid; malformed input gives a friendly error; parse/shift/zone results are correct | PENDING |
| 10 | JWT / TOTP | Sign/verify an HS256 token; generate a 6-digit TOTP from a Base32 secret | Verification passes only with the right secret; TOTP matches an authenticator app | PENDING |
| 11 | Native backup | Export a Flow as `.kaivoflow`, inspect the text, import on a second install | Non-secret params survive; secrets are redacted; IDs remap; Sparks are disabled; explicit save required | PENDING |
| 12 | Connected account | Add a provider via the Credentials screen and run a refresh-token grant | Rotated token is encrypted in the vault; HTTP/Sheets/AI Step uses the refreshed token | PENDING |
| 13 | Mobile Sparks | Trigger geofence, Bluetooth, Wi-Fi, headset and boot events | Only matching, fully identified events start a Run; schedules re-arm after reboot | PENDING |
| 14 | Device nodes | Exercise torch, volume, clipboard, location, Wi-Fi info, contact and calendar Steps | Permission prompts appear where required; failures give clear text instead of crashes | PENDING |
| 15 | Run history | Run a Flow with success, failure and skipped branches | Records and HUD reflect port liveness, retries and error policies | PENDING |
| 16 | Sub-Flow | Save Flow B, point `logic.subflow` in Flow A at B's ID, run A | B's terminal items return to A; recursion beyond depth 8 is refused; no duplicate top-level Run record | PENDING |
| 17 | Service connector | Call a mock two-page REST endpoint with `responsePath` and `nextUrlPath`, then retry a transient 503 | Items from both pages appear; retry succeeds; page cap prevents a loop | PENDING |
| 18 | Poll changes | Run `logic.poll` three times against a feed with repeated and new IDs | Only unseen items emit; the private state file stays bounded; Reset re-emits everything | PENDING |
| 19 | Templates | Open "Start from a template", pick each of the five, then edit and run one | Every template creates an editable Flow with fresh IDs and connected Streams | PENDING |

---

## Local-Verification Notes
* Verify runtime permissions for SMS, notifications, location, camera torch, contacts and calendar on Android 13+.
* For OAuth2, use a local mock token endpoint first; never paste a production client secret into a test screenshot.
* For binary uploads, use a loopback/mock server and files under 25 MB. WebDAV/TCP Steps are bounded and must not be pointed at untrusted hosts.
* Check Android 12+ `PendingIntent` mutability, exported receiver security, background-location limits and notification permission.
* If compilation reports API mismatches, prefer small local fixes over reverting a feature; report the exact compiler diagnostics to the cloud agent.

---

## Git Status
* **Branch:** `arena/bfa021c7-kaivoflow` (based on `b1dfe74` of `main`).
* **Remote:** `origin/arena/bfa021c7-kaivoflow` contains the complete commit series, ending with the reference-gap audit, composition/connector/poll/template batch and documentation refresh.
* **Working tree:** Clean; no uncommitted or untracked source files.
* **Local pull:** `git fetch origin arena/bfa021c7-kaivoflow && git switch arena/bfa021c7-kaivoflow && git pull --ff-only`.
* **Note:** The active Arena work branch is fixed by the workspace; do not expect these commits on `main` until the owner merges them.

---

## Next Priority Items
See `BACKLOG.md` for the ordered device-side queue, `PARITY.md` for the full capability matrix and `docs/CAPABILITY_GAP.md` for the remaining parity gaps.
