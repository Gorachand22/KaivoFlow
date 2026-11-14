# KaivoFlow — Session Handoff & Device Verification

> **Communication bridge between Cloud Agent (authoring code) and Local Agent (compilation + device verification).**
> Cloud role: pure Kotlin/Compose authoring only. Do not run or install Gradle, a JDK, npm or build tools in the cloud IDE.

---

## Current Session Status
* **Branch:** `arena/bfa021c7-kaivoflow` (based on `b1dfe74` of `main`).
* **Source:** 84 registered Step executors, five new mobile Sparks, binary transport, native full backup, connected-account token refresh, per-output Stream gestures and density-correct canvas coordinates.
* **Static checks:** Kotlin delimiter/string check: 102 files, 0 issues. `git diff --check`: clean. Android/resource XML parse: clean.
* **Compilation:** ❌ Not run here — no JDK/Kotlin/Gradle/Android SDK in the cloud IDE.
* **Unit Tests:** ❌ Not run here — five JVM suites authored/updated, ready for local Gradle.
* **Device Run:** ❌ Not run here — see step-by-step checklist below.
* **Architecture Docs:** See `docs/ARCHITECTURE.md`, `PARITY.md` and `DEVELOPER_HANDOFF.md`.
* **Git Status:** Clean working tree. All work is committed in small logical commits and pushed to `origin/arena/bfa021c7-kaivoflow` (active Arena work branch). Pull this branch for local verification; the legacy instruction referring to `main` is stale.

---

## Summary of Changes
1. **Engine/model:** First-class `BinaryDataRef` attachments with wrapped/legacy JSON codec, bounded HTTP multipart/base64 transport and device binary storage; immutable `Flow` remains the single source of truth.
2. **Portable Flows:** `.kaivoflow` full backup with secret redaction, ID remap, graph validation, disabled imported Sparks and an explicit re-bind/save UI; `.structure` skeleton sharing still works.
3. **Mobile Sparks:** Geofence, Bluetooth, Wi-Fi, headset and boot triggers with fail-closed matchers, scheduler/receiver wiring and boot re-arming.
4. **Expanded catalog:** 36 original Steps for math, text, formats, binary, JWT/TOTP, RSS/GraphQL/WebDAV/TCP, and Android hardware; Date & Time gained explicit parse and IANA time-zone conversion actions; the catalog now has 84 executors.
5. **Connected accounts:** Pure-Kotlin OAuth2 refresh manager with per-credential mutexes, encrypted rotated-token persistence, DI wiring, a connected-account form, and `accessToken` preference in HTTP/AI/SaaS Steps.
6. **Canvas:** Per-declared-output port drags, dashed bezier preview, magnetic input snapping, success-only haptics, branch-aware Step chaining, cycle rejection, and one `pixelScale` at every dp↔px boundary.
7. **Polish:** Status/navigation/IME insets and 48 dp targets across Flow List, Run History, Credentials and Canvas.
8. **Tests/docs:** Five JVM test suites and three helper scripts; `AGENTS.md`, `BACKLOG.md`, `PARITY.md`, `README.md`, `DEVELOPER_HANDOFF.md` and `docs/ARCHITECTURE.md` now match the implementation.

---

## Changes Requiring Local Verification
1. **Expanded catalog:** 36 original Steps for math, text, formats, binary, JWT/TOTP, RSS/GraphQL/WebDAV/TCP, and Android hardware.
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
| 3 | Step Picker | Open "Add a Step" and count categories/nodes | 84 executors appear with correct icons and no duplicates | PENDING |
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
* **Remote:** `origin/arena/bfa021c7-kaivoflow` contains the complete commit series, ending with the Date & Time enhancement, handoff updates and test-namespace cleanup.
* **Working tree:** Clean; no uncommitted or untracked source files.
* **Local pull:** `git fetch origin arena/bfa021c7-kaivoflow && git switch arena/bfa021c7-kaivoflow && git pull --ff-only`.
* **Note:** The active Arena work branch is fixed by the workspace; do not expect these commits on `main` until the owner merges them.

---

## Next Priority Items
See `BACKLOG.md` for the ordered device-side queue and `PARITY.md` for the full capability matrix.
