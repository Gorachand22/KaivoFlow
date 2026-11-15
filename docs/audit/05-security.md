# Audit 05: Security and privacy

Scope: whole repo at `/home/claude/kaivoflow` (3 commits, 160 tracked files). Lane: credential vault, webhook server, JS sandbox, HTTP gateway, Android platform surface, import path, dependency and build hygiene.
Benchmark: n8n, read for behaviour only. Every n8n behaviour below is described in our own words. No n8n code, identifiers or text are reproduced, and KaivoFlow's fixes must be original work.
Date: 2026-11-15. Paths are relative to `/home/claude/kaivoflow` unless absolute. Line numbers refer to the files as audited.

Method and limits: static review only. I did not build the app, run the tests or run it on a device. The vulnerability database lookup (OSV) was blocked by the sandbox proxy, so the dependency section judges versions by age, not by CVE. Items that depend on Android or library behaviour I could not execute are marked **verify** and come with the test that settles them.

---

## 1. Summary

KaivoFlow starts from a good posture: no analytics, no `Log.*` calls anywhere in app code, no TLS-disabling code, no committed secrets, `allowBackup="false"`, no `FileProvider`, no archive extraction in import, size and depth caps on import, imported Sparks forced off, and an AES-256-GCM Android Keystore key for the vault. The weaknesses are in how those pieces are wired together. Three systemic problems matter most:

1. **The vault is not actually in the loop.** Step inspector has no credential picker, so secrets end up typed straight into Step params. Those are stored in plaintext in Room and mirrored to `files/flows/*.json`, and the backup rules include both. The Privacy Centre and README claim otherwise.
2. **Triggers can be forged or driven by untrusted data, and nothing limits what a Step may then do.** An exported receiver accepts forged geofence broadcasts. SMS, notification, share and (future) webhook data feed Steps that can send SMS, call any URL with a credential, or write files. `requiresSensitivePermission` is metadata only; nothing enforces it.
3. **Three promised protections do not exist yet.** There is no JS sandbox (the QuickJS evaluator is a stub), the webhook server is dead code with unsafe defaults, and there is no audit log, retention policy or egress policy.

Counts (severity is the severity once the feature is live as designed; "Latent" means the code is not reachable today):

| Severity | Count | IDs |
|---|---|---|
| Critical | 0 | none today; SEC-04 and SEC-05 become Critical if wired as they stand |
| High | 5 | SEC-01, SEC-02, SEC-03, SEC-04 (latent), SEC-05 (latent) |
| Medium | 12 | SEC-06 to SEC-17 |
| Low | 8 | SEC-18 to SEC-25 |

Top five: SEC-01 vault bypassed by inline plaintext secrets; SEC-02 forged geofence broadcast starts Flows; SEC-03 TCP/TLS Step skips hostname verification; SEC-04 webhook server (all interfaces, cleartext, optional auth, thread exhaustion); SEC-05 no JS sandbox, no limits designed.

Things I checked and found clean: no keystores, `.env`, `google-services`, signing configs or key-shaped strings in the working tree or in git history (3 commits); `build/reports/...configuration-cache-report.html` is tracked but contains no secrets or home paths; no `networkSecurityConfig`, no `usesCleartextTraffic`, no custom `TrustManager`/`HostnameVerifier`; no `debuggable`; no deep-link intent filters beyond `SEND text/plain`.

---

## 2. Threat model

Single user, single device, no server. The assets and the adversaries differ from n8n's multi-user server model, so the benchmark is applied selectively.

**Assets**
- A1 Credentials: API keys, bot tokens, OAuth refresh tokens, client secrets, webhook URLs.
- A2 Private device data a Flow can read: SMS bodies and senders, notification text from every app (OTPs, banking), contacts, location, clipboard, files.
- A3 Device actions a Flow can take: send SMS, open URLs/apps, write files, call arbitrary hosts and sockets.
- A4 Integrity of the user's Flows (what runs unattended at 3 a.m.).
- A5 Run history and frozen outputs, which hold copies of A2.

**Adversaries and entry points**

| ID | Adversary | Entry point | Reaches |
|---|---|---|---|
| T1 | Another app on the phone | Exported receivers (`MobileEventReceiver`, SMS/Boot receivers), share intent into `MainActivity`, shared storage, clipboard | Fire Sparks, read clipboard |
| T2 | Author of a shared `.kaivoflow` / `.structure` file | Import picker | A4, then A1/A2/A3 once the user enables it |
| T3 | Remote party who can send an SMS, post a notification, or hit the webhook | SMS/notification/share/webhook Sparks | Data that flows into URLs, SMS recipients, file names, JS |
| T4 | Malicious or compromised API endpoint / network attacker | HTTP, TCP, GraphQL, RSS, WebDAV, AI Steps | Response size, redirects, TLS interception |
| T5 | Someone with physical or ADB access, a backup, or a rooted device | Room DB, `files/`, WorkManager DB, backups, screenshots | A1, A2, A5 at rest |
| T6 | The user themselves, by mistake | Pasting a secret in the wrong place, sharing an export, enabling an imported Flow | A1, A3 |

Out of scope: Android OS compromise, supply-chain attack on Google/Maven, a hostile keyboard.

**What n8n does that matters here (behaviour, in our words).** n8n encrypts every credential with a versioned instance key (GCM, with key rotation) and never returns decrypted values to the editor; lets each credential restrict which domains it may be sent to; masks the headers it used to authenticate an incoming request before it is stored; evaluates expressions in a sandbox that blocks reaching globals and prototypes, with a bounded-time regex engine; runs user code in a separate runner process; blocks environment-variable access from nodes on request; has a dedicated outbound-request layer that guards against requests to internal addresses; and gates trigger activation on the credentials the trigger needs. KaivoFlow has the first idea (Keystore-backed AES-GCM) and none of the other seven in working form.

---

## 3. Findings table

| ID | Severity | Area | Finding | Evidence | Effort |
|---|---|---|---|---|---|
| SEC-01 | High | Vault | Secrets are typed inline into Step params and stored in plaintext; vault has no picker and is unreachable from the UI | see 4.1 | M |
| SEC-02 | High | Platform | Exported receiver accepts forged `GEOFENCE` broadcasts and starts Flows | `AndroidManifest.xml:126-136`, `MobileEventReceiver.kt:32-66` | S |
| SEC-03 | High | Network | Socket (TCP) Step with TLS never verifies the server hostname | `ProtocolNodes.kt:318-322` | S |
| SEC-04 | High (Latent) | Webhook | Server is dead code with unsafe defaults: all interfaces, cleartext, optional auth, thread exhaustion, disabled Sparks still routed | `WebhookServer.kt` | M |
| SEC-05 | High (Latent) | Sandbox | No JS engine exists; planned design has no memory/time cap, process isolation or regex bound | `QuickJsExpressionEvaluator.kt`, `CodeNode.kt` | L |
| SEC-06 | Medium | Network/Vault | Credentials and inline tokens are not bound to a destination; data-driven URLs and `baseUrl` can receive them | `HttpNode.kt:91,99-116`, `AiNodes.kt:71-89` | M |
| SEC-07 | Medium | Leakage | Secrets placed in URLs reach error text, step errors and run history | `SaasNodes.kt:57`, `AiNodes.kt:123`, `HttpNode.kt:153`, `KtorHttpGateway.kt:135-136` | S |
| SEC-08 | Medium | Data at rest | Run history and trigger payloads (SMS, notifications) stored unencrypted, unredacted, kept forever, no purge UI | `JsonCodec.kt:227-258`, `Repositories.kt:190-194` | M |
| SEC-09 | Medium | Backup | Backup rules contradict `allowBackup=false`, exclude paths that do not exist, and include the DB and flow mirror that hold plaintext | `data_extraction_rules.xml`, manifest `:54-56` | S |
| SEC-10 | Medium | Import | Redaction is by key name only; frozen outputs, notes and URL-embedded secrets are not scrubbed; `isActive` and retry/timeout values survive import; preview truncates | `NativeFlowBundle.kt` | M |
| SEC-11 | Medium | Control | Deactivating a Flow does not stop it: boot/app start re-arms every Flow, event dispatch ignores `isActive` | `FlowScheduler.kt:53-63,133-144` | S |
| SEC-12 | Medium | Network | Responses are read fully into memory (text and binary) with no streaming cap | `KtorHttpGateway.kt:108,121` | S |
| SEC-13 | Medium | Capabilities | Step permission metadata is never enforced; no per-Flow grants; SMS/URL/file Steps accept data-driven targets | `NodeSpec.kt:25`, `DeviceNodes.kt:140-153` | L |
| SEC-14 | Medium | Network | No egress policy: loopback, LAN and link-local reachable; redirect header handling unverified; cleartext relies on platform default | `HttpNode.kt:91`, `KtorHttpGateway.kt:222` | M |
| SEC-15 | Medium | Triggers | Event sources are untrusted and unauthenticated: spoofable SMS sender, all-app notification capture, any-app share intent | `EventTriggerMatcher.kt`, `MainActivity.kt:72-83` | M |
| SEC-16 | Medium | Audit | No audit log of credential use, grants, imports, trigger changes or webhook hits | whole repo | M |
| SEC-17 | Medium | Data | `fallbackToDestructiveMigration()` will silently wipe flows, runs and the vault on a schema bump | `DataModule.kt:22` | S |
| SEC-18 | Low | Vault | Keystore key lacks hardening options; ciphertext not bound to its row; errors swallowed | `CryptoBox.kt` | S |
| SEC-19 | Low | Files | File Step sandbox directory is the same directory as the flow mirrors; `content://` loader is unrestricted | `AndroidDeviceGateway.kt:142-152,253-262,421` | S |
| SEC-20 | Low | Expressions | `$env` exposes the process environment to expressions | `Expression.kt:111-115` | S |
| SEC-21 | Low | Manifest | Over-declared and invalid permissions, an unnecessarily exported `BootReceiver` | manifest `:11-42,113` | S |
| SEC-22 | Low | Build | Stale versions, unused alpha crypto/biometric deps, AGP checks disabled, no dependency/wrapper verification, `targetSdk` 34 | `libs.versions.toml`, `build.gradle.kts:20,26-27` | S |
| SEC-23 | Low | UI | No `FLAG_SECURE`, clipboard clips not marked sensitive, last Wi-Fi SSID stored in prefs | `AndroidDeviceGateway.kt:103-106`, `MobileEventReceiver.kt:76-78` | S |
| SEC-24 | Low | R8 | Keep rules reference a library that is not a dependency (and the wrong package); stack-trace attributes retained | `proguard-rules.pro:41-46,60-61` | S |
| SEC-25 | Low | Intents | `openUrlOrApp` and notification deep links open any URI built from Flow data | `AndroidDeviceGateway.kt:108-117,64-66` | S |

---

## 4. Findings in detail

### 4.1 SEC-01 Vault bypassed; inline plaintext secrets (High)

What the code does:
- Vault: `CryptoBox` (`core/data/.../CryptoBox.kt:26-61`) makes an AES-256-GCM key in the Keystore, writes `base64(ivLen|iv|ciphertext)` into `credentials.cipherText` (`Database.kt` `CredentialEntity`). That part is sound.
- Reaching nodes: `StepContext.credential()` (`StepExecutor.kt:63-68`) reads the Step param named `credential` and calls `credentials.resolve(ref)`, so the param must hold the credential's row id (a UUID).
- But the Step inspector renders a `Secret` param as a plain masked text field (`StepInspectorSheet.kt:488-513`), there is no picker (`CanvasViewModel.kt:61` injects `CredentialRepository` and never uses it), and the Credentials screen lists only label and type, never the id (`PrivacyAndCredentials.kt:239-251`). A user cannot select a vault entry. A pasted token in `credential` fails with "That credential was deleted" (`Repositories.kt:214`).
- So users type secrets into the many inline `Secret` params: `authToken`, `authPassword` (`HttpNode.kt:57-59`, `CompositionNodes.kt:96-99,198-201`), Slack/Discord `webhookUrl` (`SaasNodes.kt:88,136`), HMAC `secretKey` (`TransformNodes.kt:520`), signing and TOTP secrets (`SecurityNodes.kt:54,153`), webhook secret (`TriggerNodes.kt:137`), RSS/GraphQL tokens (`ProtocolNodes.kt:105,184`).
- Those values become part of `Flow.steps[].params`, which `RoomFlowRepository.save` writes to `flows.flowJson` and again to `files/flows/<id>.json` (`Repositories.kt:86-113`), unencrypted. `data_extraction_rules.xml` includes both (SEC-09).
- Claims that do not hold: Privacy Centre "Keystore-encrypted credentials ... excluded from backups" (`PrivacyAndCredentials.kt:166`), settings text "Saved. The key is encrypted" (only true for vault entries), `docs/ARCHITECTURE.md:118`.
- Positives: nothing returns decrypted vault fields to the UI (`getFields` has no callers), and secret-typed params are masked in the editor and redacted in the inspector JSON view (`StepInspectorSheet.kt:1075`, `Values.kt:196-207`).

Impact: A1 exposed to T5/T6 at rest, via the flow mirror, via any backup path, and via `.kaivoflow` export when key-name redaction misses (SEC-10). It also means the vault protects almost nothing in practice.

Fix: wire the vault (target architecture 6.1): `Secret` params hold only a `credentialRef`; add a credential picker with "create new" inline; a one-time migration that moves existing inline secret literals into the vault and replaces them with refs; `FlowRepository.save` refuses (or auto-moves) a literal in a `Secret` param; stop mirroring raw flow JSON or mirror only redacted JSON. Acceptance test: save a Flow containing `authToken`, grep both Room row and mirror file for the literal, expect zero hits.

### 4.2 SEC-02 Forged geofence broadcast (High)

`MobileEventReceiver` is `exported="true"` with no permission and a custom action `com.kaivoflow.action.GEOFENCE` (`AndroidManifest.xml:126-136`). `onReceive` takes latitude, longitude, transition and an optional `targetFlowId` straight from intent extras (`MobileEventReceiver.kt:57-66`) and calls `dispatchTrigger`, which starts every matching, enabled Spark (`FlowScheduler.kt:133-144`).
`EventTriggerMatcher` accepts `transition="exit"` when the reported point is at least 0.85x radius from the Spark centre (`EventTriggerMatcher.kt:54-57`). An attacker app that sends `exit` at (0, 0) therefore matches every geofence Spark set to "exit" or "both" that is not near the equator and prime meridian. For "enter" Sparks the attacker must guess the centre, which is a bounded search over populated places.
Impact: T1 starts any user Flow with attacker-chosen payload: SMS sends, HTTP calls carrying credentials, file writes.
Fix: `android:exported="false"` on `MobileEventReceiver` (the system proximity `PendingIntent` is delivered under the app's identity, so geofencing keeps working, and the other four actions are system-protected broadcasts that the system can still deliver). Drop the `targetFlowId` extra in favour of a per-Flow `PendingIntent` request code lookup. Add a unit test that `dispatchTrigger` is never reachable from an exported component. Same export change for `BootReceiver` (SEC-21). Note: Wi-Fi state, headset and (on API 26+) several implicit broadcasts do not reach manifest receivers anyway, so those Sparks probably need runtime registration; track that as a correctness item, not security.

### 4.3 SEC-03 TLS without hostname verification in the Socket Step (High)

`TcpNode.talk` uses `SSLSocketFactory.getDefault().createSocket()` then `connect(InetSocketAddress(host, port))` (`ProtocolNodes.kt:318-322`). A raw `SSLSocket` on Android validates the certificate chain but does not check that the certificate matches the host unless an endpoint-identification algorithm is set or a hostname verifier is applied by the caller. I found neither. **verify**: connect to a test server presenting a valid certificate for a different name and confirm the Step succeeds.
Impact: a network attacker with any valid certificate can intercept the Step's traffic (T4), including data from other Steps.
Fix: create the socket with host and port, set `SSLParameters.endpointIdentificationAlgorithm = "HTTPS"`, set SNI, and fail the handshake on mismatch; add an explicit, off-by-default "trust this certificate fingerprint" option for LAN devices instead of a skip-verify switch. Test with a mismatched-name server.

### 4.4 SEC-04 Webhook server (High, latent)

`WebhookServer` is never started: nothing calls `start()` or `refreshRoutes()` (grep over the repo), and the manifest declares it as a `<service>` (`AndroidManifest.xml:151-155`) although the class is a plain `@Singleton`, not a `Service`. Starting it through the manifest would crash. The class is nevertheless the planned design, and it has these defects:

| # | Defect | Evidence |
|---|---|---|
| a | Binds the wildcard address, every interface including Wi-Fi and hotspot | `WebhookServer.kt:84` `InetSocketAddress(port)` |
| b | Cleartext HTTP only, no TLS option | whole file |
| c | Auth is optional and defaults to none (`secret` blank becomes null) | `:69-76`, `:137-142` |
| d | "Signature" is an HMAC of the body only, with no timestamp or nonce, so replayable; a `secret=` query form puts the signature in the URL | `:138-141` |
| e | Disabled Sparks and inactive Flows are still routed: only `type == WEBHOOK` is filtered | `:69` |
| f | No connection cap; one coroutine per accepted socket on `Dispatchers.IO` doing blocking reads. `soTimeout` resets on every byte, so a one-byte-per-7s sender holds a thread for hours (header cap 16 KB, body cap 256 KB). Enough of these starve the shared IO dispatcher that Room and HTTP also use | `:89,102,122-128,199-212` |
| g | `routes` is a plain `mutableMapOf` cleared and refilled from another thread, a data race and a window where every request returns 404 | `:57,67-76,132` |
| h | `decodeJson` recurses without a depth guard; a 256 KB body of nested `[` is a likely `StackOverflowError`, which on Android kills the process (same pattern in `FlowRunWorker.kt:143-156`). **verify** with a fuzz test | `:184-198` |
| i | Chunked bodies are silently ignored (empty body accepted) | `:118-130` |
| j | Always answers 200 before running, so there is no test/production distinction and no way to return a result | `:145` |
| k | `requireWifi` param exists but is never read | `TriggerNodes.kt:140` |
| l | Claims in the class comment ("foreground-only", "single-threaded", "never silently open in the background") are not what the code does: the service is declared `stopWithTask="false"` and the accept loop is multi-coroutine | `WebhookServer.kt:36-47`, manifest `:151-155` |

Fix: see 6.4. Until that rewrite lands, keep the server unwired and remove the `<service>` entry so nobody can start it by accident.

### 4.5 SEC-05 No sandbox exists; none of the future limits are designed (High, latent)

- `QuickJsExpressionEvaluator` ignores its `timeoutMs` and `prelude` constructor arguments and always calls the pure-Kotlin `FallbackExpressionEvaluator` (`QuickJsExpressionEvaluator.kt:8-17`). `quickjs-kt` is in `libs.versions.toml:16,53` but no module depends on it. `FlowEngine` catches nothing because the "QuickJS" constructor cannot fail (`FlowEngine.kt:60-64`).
- Consequence today: the Code Step and every non-trivial expression cannot run (`CodeNode.kt:63,80-85` feeds an IIFE to the fallback, which rejects it). That is also why there is no escape today. Audit 02 covers the functional gap.
- The fallback itself is safe: a hand parser over a fixed set of `$` roots and a path walk with no reflection, no calls into arbitrary members.
- The planned prelude, `core/expressions/src/main/resources/js/kaivo_expression_prelude.js`, is where the real risk will live. It calls indirect `eval` (`:281`), builds `RegExp` objects from user patterns in `$match` and `$replaceAll` (`:131,150-151`, a ReDoS vector), exposes `$node` through a `Proxy` (`:247-252`), and does not freeze intrinsics or block prototype pollution through `$merge`/`$vars` data. `FlowRunner` bounds steps with `withTimeout` (`FlowRunner.kt:190-192,504-506`), but `withTimeout` cannot interrupt native code, so a `while(true){}` inside QuickJS would pin a thread past the deadline. The ProGuard keep rules target package `app.cash.quickjs` (`proguard-rules.pro:41-46`), but the dependency is `io.github.dokar3:quickjs-kt`, so the rules would not apply.

Fix: requirements in 6.5. Do not ship the Code Step until all items there pass.

### 4.6 SEC-06 Credentials and tokens not bound to a destination (Medium)

`HttpNode` accepts any `http(s)` URL (`:91`), resolves `{{ }}` in the URL per item (`resolveValue` on `params["url"]`) and attaches the credential or inline token to whatever host results (`:99-116`). Telegram builds the URL from the token (`SaasNodes.kt:57`), the AI Step sends the key to any `baseUrl` (`AiNodes.kt:71-89`, and `:72` uses `contains("localhost")`, a substring test).
Attack: a Flow whose URL is `{{ $json.callback }}` fed from an SMS, notification or webhook lets T3 pick the host that receives a bearer token. An imported Flow with a literal attacker URL does the same once the user binds their credential.
Fix: each credential gets an `allowedHosts` list (exact and `*.` wildcard, default "the provider's host" for typed credentials, mandatory for generic ones); the gateway refuses to attach a credential to any other host, after redirects as well. Reject credentialed requests whose URL host comes from an expression unless the host is on the list. This is the same control n8n offers per credential.

### 4.7 SEC-07 Secrets in URLs leak into error text and history (Medium)

Telegram (`SaasNodes.kt:57`) and Gemini (`AiNodes.kt:123`) put the secret in the request URL. `HttpNode` includes the URL in its failure message and its log line (`HttpNode.kt:153,177`), and the gateway wraps `IOException.message` into a user-visible error (`KtorHttpGateway.kt:135-136`). Ktor's request-timeout exception text includes the request URL (**verify** by forcing a timeout against a path containing a marker). Step and run errors are stored (`RunEntity.error`, `JsonCodec.kt:227-237`).
Fix: send Telegram and Gemini secrets in a header where the provider allows it (Gemini supports a key header); otherwise inject the secret at the last moment and pass a `redactedUrl` to every message and log; run all error strings through the central value-based redactor (6.1). Test: force a timeout and assert the stored error contains no secret.

### 4.8 SEC-08 Run history stores sensitive payloads in plaintext, forever (Medium)

Each `StepRun` stores up to 20 output items per Step (`JsonCodec.kt:249-253`), including the Spark seed item with full SMS body (500 chars), notification title/text (`NotificationAndSmsSources.kt:36-45,64`), share text, and whatever HTTP or AI Steps returned. Redaction exists only for the inspector view (`Values.redact`, key-name based). Retention: `pruneOlderThan` and `clear` have no callers (`Repositories.kt:190-194`, grep over `feature/` and `app/`), so history grows without bound and the Privacy Centre statement "You can delete every ... Run" has no UI behind it. Trigger payloads also sit in WorkManager's own database as input `Data` (`FlowScheduler.kt:111-121`).
Room is plain SQLite (`DataModule.kt:21-23`), so anyone with the file (T5) reads OTPs and banking alerts.
Fix: 6.6. Short term: default retention (for example 14 days / 200 runs), purge on a timer, a "Clear history" action, and metadata-only storage for SMS and notification Sparks unless the Flow opts in.

### 4.9 SEC-09 Backup and extraction rules are inconsistent (Medium)

`android:allowBackup="false"` and `fullBackupContent="false"` (`AndroidManifest.xml:54-56`) conflict with a `data_extraction_rules.xml` that includes `kaivo.db` and `flows/` for both cloud backup and device transfer. The excludes name `kaivo_secrets.xml` and `kaivo_keys/`, which the app never creates, while the comment says the vault is excluded. The vault ciphertext lives inside `kaivo.db` and is included. The ciphertext is useless without the Keystore key, but the same database holds plaintext flows, inline secrets (SEC-01), run history (SEC-08) and variables, and `flows/` holds the mirror. How Android 12+ resolves `allowBackup=false` against `dataExtractionRules` for device-to-device transfer is not something I can confirm statically. **verify** with `adb shell bmgr` and a device-transfer dry run.
Fix: make the intent explicit. Either exclude everything (`<exclude domain="root" path="."/>` in both sections) and provide an encrypted, passphrase-protected export (6.6), or include only data that is safe in plaintext. Correct the Privacy Centre text to match.

### 4.10 SEC-10 Import path (Medium)

Good: plain JSON, no archive, so no zip-slip and no path handling; hard caps 4 MB / 2 MB, 500 Steps, 2000 Streams, depth 48, 160-char names, strict UTF-8, cycle check, unique ids, all ids regenerated (`NativeFlowBundle.kt:34-39,115-177,190-223`, `FlowStructureBundle.kt:26-31`), Sparks forced off (`NativeFlowBundle.kt:201`), binaries dropped, parse does no I/O, the single write happens after confirmation (`FlowTransfer.kt:156-168`). The structure format drops all params.
Gaps in the full `.kaivoflow` format:
1. Redaction is by key name (`NativeFlowBundle.kt:225-234`). Not scrubbed: `frozenOutput` contents (`:77,202`, only binaries removed), Step `notes`, sticky-note text, secrets embedded in a URL (`?api_key=`, `user:pass@host`), headers such as `Cookie`, JS code, expression text. Export of a Flow that pinned a real API response publishes that response.
2. `Flow.isActive` is kept from the file (`freshIds` copies the Flow, `:205-222`), so an imported Flow can show as "24/7 active". Combined with SEC-11 this weakens the "imported Sparks are off" promise.
3. `retry.maxAttempts`, `initialDelayMs`, `executionTimeoutMs`, `maxItemsPerStep` and `timeoutMs` are accepted at any value (`JsonCodec.kt:175-181`, `FlowSettings`). A bundle can set an effectively infinite timeout and a huge retry count on a Step that sends SMS.
4. Frozen outputs are replayed in Production (`FlowRunner.kt:313`, `ExecutionMode` doc `StepExecutor.kt:9-13`), so an imported file can pin a fake "result" a downstream Step trusts.
5. The review dialog shows the first 12 Step types and 8 credential hints (`FlowTransfer.kt:233-235`). It does not show hosts contacted, SMS recipients, code, or capabilities, so a hostile Flow hides in the tail.
6. No notion of trust level. An imported Flow becomes an ordinary Flow after one tap.
Fix: 6.3 (safe mode and import review) plus value-based export scrubbing, caps on every numeric field, `isActive=false` forced on import, frozen outputs dropped by default.

### 4.11 SEC-11 Kill switch does not kill (Medium)

`FlowScheduler.rescheduleAll` calls `schedule(flow)` for every Flow regardless of `isActive` (`:53-57`), and `schedule` only checks the Spark's `disabled` flag (`:63`). Boot, app update, time change and every cold start run it (`BootReceiver.kt:39`, `KaivoApplication.kt:40`). `dispatchTrigger` likewise ignores `isActive` (`:133-144`). So "Unpublish (Stop 24/7)" holds only until the next restart, and event Sparks never honour it.
Fix: one `isArmed(flow)` predicate (`isActive && trigger.enabled && trust == Trusted`) used by scheduling, geofence sync, event dispatch and the webhook router. Unit test per trigger type.

### 4.12 SEC-12 Unbounded response buffering (Medium)

Non-binary responses use `bodyAsText()` with no limit (`KtorHttpGateway.kt:121`). The binary path checks `Content-Length` and then calls `readBytes()` before checking size (`:104-111`), so a server that lies or streams without a length exhausts memory (`largeHeap=false`). T4 can crash the app and stall every scheduled Flow.
Fix: stream with a byte budget (default 10 MB text, 25 MB binary, user-adjustable up to a hard ceiling), abort the call when exceeded, cap decompressed size, and cap JSON nesting before parsing.

### 4.13 SEC-13 Capabilities are metadata, not policy (Medium)

`NodeSpec.requiresSensitivePermission` is set on 10 Step types (`ExtendedDeviceNodes.kt:93,152,199,226`, `MobileTriggerNodes.kt:47,92`, `TriggerNodes.kt:171,194`, `DeviceNodes.kt:137`) and read by no engine code. Individual nodes check the Android runtime permission at call time (good: `DeviceNodes.kt:141-143`), but there is no per-Flow grant, no distinction between a Flow the user wrote and one they imported, no rate limit, and several targets are data-driven:
- `SendSmsNode` sends to `{{ expression }}` recipients with no allowlist, cap or confirmation (`DeviceNodes.kt:141-152`, `AndroidDeviceGateway.kt:84-101`). An SMS Spark feeding an SMS Step is a toll-fraud and spam relay driven by T3.
- `openUrlOrApp` and notification deep links open any `Uri.parse(...)` (SEC-25).
- File Steps accept a free file name (SEC-19).
Fix: 6.2.

### 4.14 SEC-14 No egress policy (Medium)

Nothing blocks loopback (`127.0.0.1`, `::1`), RFC 1918, link-local, CGNAT or `.local` hosts for HTTP, GraphQL, RSS, WebDAV, TCP or AI `baseUrl`. On a phone, that reaches the router admin page, LAN devices, the app's own webhook port and any other local service. Redirects: `followRedirects = true` (`KtorHttpGateway.kt:222`), Ktor refuses an https-to-http downgrade by default, but whether `Authorization` is dropped on a cross-host redirect is **verify** (test: 302 from host A to host B, inspect headers at B). Cleartext: the HTTP Step validates `http://` (`HttpNode.kt:91`), but with `targetSdk 34` and no network security config, OkHttp refuses it at runtime, including `http://localhost:11434` for the Ollama/local-AI path. The likely "fix" is a global `usesCleartextTraffic="true"`, which would be a regression. Headers: OkHttp rejects CR/LF in header values, so injection is blocked; multipart part names are sanitised by hand (`KtorHttpGateway.kt:177-181`).
Fix: 6.8, including a scoped, per-host cleartext allowance for explicitly trusted LAN hosts.

### 4.15 SEC-15 Untrusted event sources (Medium)

- SMS: sender IDs are spoofable and alphanumeric; the matcher accepts any `from`/`body` that passes substring filters (`EventTriggerMatcher.kt:30-32`). An SMS Spark with empty filters runs on every message.
- Notifications: the listener captures title and text of every app before filtering (`NotificationAndSmsSources.kt:33-52`); with `packageNames` empty, every app matches (`EventTriggerMatcher.kt:24-25`; the UI says "Leave empty to watch every app", `TriggerNodes.kt:~166`).
- Share: `MainActivity` is exported for `SEND text/plain` (manifest `:79-84`), and any app can start every enabled Share Spark with arbitrary text (`MainActivity.kt:72-83`).
- Event data is treated as data (expressions are only evaluated on the template, not on the incoming value), which is correct. The danger is what downstream Steps do with it (SEC-06, SEC-13).
Fix: Spark creation forces at least one narrowing filter for SMS and notifications (a specific package or sender), shows what a Spark can see, requires an "I understand" for "all apps"; Share Spark asks for a one-tap confirmation per share when the source app is not on a list; every Spark gets a rate limit and a loop breaker.

### 4.16 SEC-16 No audit log (Medium)

There is no record of when a credential was created, changed or used (and by which Flow and host), when permissions or grants changed, when an import happened, when a Spark was enabled, or which webhook calls were accepted or rejected. For a tool that acts unattended on SMS and money-related notifications, "what did my phone do last night and why" has no answer beyond run history, which can be pruned or fail to save (`FlowSettings.saveSuccessfulRuns`).
Fix: 6.7.

### 4.17 SEC-17 Destructive migration (Medium)

`Room.databaseBuilder(...).fallbackToDestructiveMigration()` (`DataModule.kt:22`) drops every table on a version mismatch, including `credentials`. The Keystore key survives, the rows do not, and there is no export prompt. A future schema bump becomes silent loss of Flows and credentials. Only schema version 1 exists today (`core/data/schemas/.../1.json`).
Fix: remove the fallback, write real migrations, add a migration test per version, and take an automatic encrypted snapshot before any migration.

### 4.18 Lows

**SEC-18 Vault hardening gaps** (`CryptoBox.kt`). Works, but: no `setUnlockedDeviceRequired(true)`; no StrongBox attempt with fallback; no optional user-authentication gate for "reveal/export"; no associated data, so an attacker with DB write access can move one row's ciphertext to another credential id and the app decrypts it happily; `runCatching` returns `null` for every failure, so a locked Keystore, a permanently invalidated key and a corrupted blob look identical; `secretKey()` silently generates a new key when the alias is missing (`:26-41`), which turns "key lost" into "all credentials unreadable" without telling the user. Plaintext secrets live in `String`s while a Step runs, which cannot be wiped; keep the lifetime short and avoid logging them. `androidx.security:security-crypto:1.1.0-alpha06` is declared in `core/data/build.gradle.kts:31` and unused; the library is also deprecated, so remove it rather than adopt it.

**SEC-19 File sandbox shares a directory with flow data.** `workflowDir()` is `filesDir/flows` (`AndroidDeviceGateway.kt:421`), the same place `RoomFlowRepository` mirrors `<flowId>.json` (`Repositories.kt:108-109`). `sanitize` blocks traversal (good, `:424-425`), but a file Step can read, overwrite or truncate a Flow mirror if it knows the id, and "state" files and Flow JSON share a namespace. `loadBinary` opens any `content://` URI the app can reach (`:253-262`) with no allowlist; the URI comes from item data. Use `filesDir/step-state/<flowId>/` per Flow and restrict `content://` to URIs the user picked through the Storage Access Framework in this session.

**SEC-20 `$env` exposes the environment.** `ScopeBuilder` copies `System.getenv()` into every expression scope (`Expression.kt:111-115`). On Android this is mostly `ANDROID_*` paths, but it is an information leak and a bad precedent; n8n can block it. Replace with an explicit allowlist (`OS`, `PLATFORM`, `TIMEZONE`).

**SEC-21 Manifest permissions.** Declared up front: `READ_SMS` (no code uses it; only `RECEIVE_SMS` and `SEND_SMS` are used), `ACCESS_BACKGROUND_LOCATION`, `BLUETOOTH_SCAN`, `MODIFY_AUDIO_SETTINGS`, `USE_EXACT_ALARM` alongside `SCHEDULE_EXACT_ALARM` (`USE_EXACT_ALARM` is restricted to alarm and calendar apps), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Play-restricted), and `BIND_NOTIFICATION_LISTENER_SERVICE` as a `<uses-permission>` with the lint warning silenced (`:42-43`); that permission belongs only on the service. The SMS group is Play-restricted and needs a declared use case. `BootReceiver` is `exported="true"` (`:113-114`); all its actions are system-protected, so it works with `false` and no downside. `MobileEventReceiver` is SEC-02. Positives: `KaivoNotificationListener` and `SmsTriggerService` are not exported, and the SMS receiver is guarded by `BROADCAST_SMS`.

**SEC-22 Build and dependency hygiene.**
- Versions (`gradle/libs.versions.toml:3-26`): AGP 8.3.2, Kotlin 2.0.21, Compose BOM 2024.10.01, Ktor 2.3.12, Room 2.6.1, WorkManager 2.9.1, Hilt 2.51.1, kotlinx-serialization 1.7.3, `compileSdk`/`targetSdk` 34. All are 1 to 2 years behind as of this date, and `targetSdk 34` is below what Play requires for updates. Run a scanner in CI; I could not (see Method).
- Unused and risky: `security-crypto` alpha06, `biometric` alpha05 (unused), `quickjs-kt` (unused until SEC-05).
- Checks turned off: `CheckAarMetadataTask` disabled for all modules and `-Xskip-metadata-version-check` (`build.gradle.kts:20,26-27`). These hide real incompatibilities between AGP, compileSdk and dependency metadata.
- No dependency verification metadata, no lockfiles, no `distributionSha256Sum` on the Gradle wrapper (`gradle-wrapper.properties`), wrapper JAR is committed without a documented checksum. A release signing config is absent, which is fine for now but needs a plan that keeps keys out of the repo.
- `gradle.properties:14` sets `android.enableR8.fullMode`, which is stale on AGP 8; harmless.
- Secret scan: nothing found (see Summary). `.gitignore` covers `local.properties`, APK/AAB and `build/`, but not `*.jks`, `*.keystore`, `*.p12`, `.env` or `google-services.json`; add them.
- Tracked `build/reports/...configuration-cache-report.html` should be removed from git and ignored (the root-level `/build` rule does not match because the file is already tracked).

**SEC-23 Screen, clipboard, prefs.** No `FLAG_SECURE` on the credentials or inspector screens (recents screenshots and screen capture). `copyToClipboard` sets a plain clip with no sensitive-content flag (`AndroidDeviceGateway.kt:103-106`). The inspector's "copy JSON" copies redacted data, good (`StepInspectorSheet.kt:1075`). The last Wi-Fi SSID is stored in shared prefs (`MobileEventReceiver.kt:76-78`), so excluding it from backup matters.

**SEC-24 R8 rules.** `-keep class app.cash.quickjs.** { *; }` and the member keeps (`proguard-rules.pro:41-46`) target a package that is not a dependency; `-keep class dagger.hilt.** { *; }` is broader than needed; `-keepattributes SourceFile,LineNumberTable` is fine if mappings are archived, otherwise it only helps reverse engineering. No rule strips `Log` (there are no calls today, so this is a guard for later).

**SEC-25 Data-driven intents.** `openUrlOrApp` calls `Intent(ACTION_VIEW, Uri.parse(uri))` (`AndroidDeviceGateway.kt:108-117`) and notifications build a `PendingIntent` from `deepLink` (`:64-70`, correctly `FLAG_IMMUTABLE`). With a Flow that takes the URI from an SMS, T3 can launch phishing pages or arbitrary apps. Restrict schemes to `https`, `tel`, `geo`, `mailto` by default and require the URL host to be on a grant.

---

## 5. Fix recommendations (concrete, in priority order)

| Order | Fix | Closes | Done when |
|---|---|---|---|
| 1 | `exported="false"` on `MobileEventReceiver` and `BootReceiver` | SEC-02, SEC-21 | A manifest test fails on any exported receiver without a permission |
| 2 | Hostname verification in `TcpNode` (endpoint identification, SNI) | SEC-03 | Mismatched-name test server is rejected |
| 3 | Make `isActive` and Spark `disabled` the only arming source; force `isActive=false` on import | SEC-11, SEC-10 (item 2) | Restart test: deactivated Flow never runs |
| 4 | Wire the vault: picker, ref-only `Secret` params, migration of inline literals, no raw mirror | SEC-01, SEC-09 | Grep of DB and files for a saved token returns nothing |
| 5 | Central value-based `Redactor` on outputs, errors, logs, history, export, clipboard; secrets out of URLs | SEC-07, SEC-08 (part), SEC-10 (item 1) | Forced-failure tests contain no secret |
| 6 | Backup rules rewritten to exclude everything; fix Privacy Centre wording | SEC-09 | `dumpsys backup` shows nothing eligible |
| 7 | Streaming size caps in the gateway | SEC-12 | 1 GB chunked response aborts at the cap |
| 8 | Remove `fallbackToDestructiveMigration`; migration tests | SEC-17 | Migration test suite green |
| 9 | Retention defaults, purge action, metadata-only SMS/notification storage | SEC-08 | Old runs vanish; SMS body absent by default |
| 10 | Credential host binding and egress policy | SEC-06, SEC-14 | Attach-to-other-host test fails closed |
| 11 | Capability model, safe mode, import review (6.2, 6.3) | SEC-13, SEC-10, SEC-15 | Imported Flow cannot call out until approved |
| 12 | Webhook server rewrite or removal (6.4) | SEC-04 | Test matrix in 6.4 |
| 13 | Isolated sandbox before the Code Step ships (6.5) | SEC-05 | Test matrix in 6.5 |
| 14 | Audit log (6.7) | SEC-16 | Every listed event type produces a row |
| 15 | Hygiene: dependency updates and scanning, remove unused deps, re-enable AGP checks, `.gitignore`, wrapper checksum, R8 rules | SEC-22, SEC-24, SEC-18 | CI green with scanner and checks on |

---

## 6. Target security architecture

Design aim: a single user should be safe by default without having to understand any of this, and should lose nothing when they use the app the way a power user would. Everything below can be built on top of the existing seams (`CredentialProvider`, `HttpGateway`, `DeviceGateway`, `NodeSpec`, `FlowRunner` preflight) with no rewrite of the engine.

### 6.1 Secrets handling

1. **Envelope encryption.** A random 256-bit data key (DEK) encrypts credential payloads with AES-GCM. A Keystore key (KEK: AES-GCM, StrongBox when available, `setUnlockedDeviceRequired`) wraps the DEK. Benefits: key rotation is a re-wrap, an encrypted export is possible by wrapping the DEK under a passphrase-derived key (a memory-hard KDF), and the same DEK can encrypt run history and Step outputs (6.6). Each ciphertext carries a version byte and uses the credential id and type as associated data.
2. **Typed credentials.** A credential has a type (API key, bearer, basic, header, OAuth2, Telegram bot, webhook URL, HMAC secret), typed fields, an `allowedHosts` list, and optional per-credential `allowedFlows`. The typed definitions already sketched in `WorkspaceResources.kt` are the right shape; connect them to the vault and the picker.
3. **References only.** Step params of type `Secret` store a `credentialRef`, never a literal. Literal secrets typed into any text field are detected (entropy and key-shape heuristics plus the declared param type) and the editor offers "Save to vault".
4. **Last-moment injection.** Nodes ask the gateway to send "request R with credential C"; the gateway looks up the secret, attaches it in a header (never the URL where the provider allows), checks `allowedHosts`, and passes only a redacted view back to the node. Nodes cannot read the raw value except by an explicit, audited "reveal" capability.
5. **Value-based redaction.** A `Redactor` holds the set of live secret values (and their base64 and URL-encoded forms) and scrubs every string leaving a Step: outputs, errors, logs, history, export, clipboard. This catches secrets that key-name rules miss. Secrets are wrapped in a `SecretValue` type whose `toString()` is masked, so accidental interpolation cannot leak.
6. **Reveal and export need user presence.** Biometric or device credential confirmation (the `biometric` dependency is already in the catalog) before showing a secret or exporting the vault. Add `FLAG_SECURE` on those screens.

### 6.2 Permission gating per step type

Replace the passive `requiresSensitivePermission` string with a closed capability set declared on each `NodeSpec`:

| Capability | Examples |
|---|---|
| `net.public` | HTTP, GraphQL, RSS, AI, SaaS Steps |
| `net.lan` | any host in private/loopback ranges, `.local`, TCP/WebDAV to LAN |
| `sms.send`, `sms.receive` | Send SMS, SMS Spark |
| `notif.read` | Notification Spark |
| `contacts.read`, `calendar.write`, `location`, `camera`, `bluetooth`, `wifi.info` | matching device Steps |
| `files.step` | File Steps in the Flow's own directory |
| `files.picked` | files the user picked via the system picker |
| `clipboard.read`, `clipboard.write` | clipboard Steps |
| `intent.open` | open URL/app, deep links |
| `code.run` | Code Step, expression JS |
| `flow.call` | Sub-Flow |
| `secret.reveal` | any node that needs a raw secret |

Rules:
- A Flow's **manifest** (the union of its Steps' capabilities, plus the static hosts it contacts) is computed, never hand-written, and shown on the Flow details screen.
- A **grant** is stored per Flow: capability plus constraints (hosts for `net.public`, recipients or count/day for `sms.send`, packages for `notif.read`). No grant means the Step fails closed in preflight, before any Step runs. This is where `FlowRunner`'s existing validator is extended.
- Android runtime permissions stay the second gate: capability granted *and* OS permission present.
- Widening a manifest (a new capability or a new host) after trust requires a new grant. Narrowing does not.
- Data-driven targets are checked against the grant at execution: an SMS to a number not in the grant, or a URL whose host is not granted, fails with a clear message.
- Hard limits independent of grants: SMS per hour and per day, outbound requests per minute per Flow, run concurrency, run duration.

### 6.3 Safe mode for imported Flows

States: `Draft` (user-built, trusted by authorship), `Quarantined` (imported or restored), `Trusted`.
- Import always yields `Quarantined`: all Sparks off, `isActive=false`, zero grants, retry/timeouts clamped, frozen outputs dropped unless the user keeps them.
- A **review screen** replaces the current summary: every Step type, every static host, every SMS/number target, every code body (syntax-highlighted, read-only), every Spark with what it would read, the capability manifest, credentials to rebind, and a red marker for anything dynamic (an expression building a URL or recipient). No truncation; collapsed groups instead.
- **Dry run** executes the Flow with stub gateways: `HttpGateway` returns recorded or empty responses, `DeviceGateway` records intended actions without performing them. The result is a list of "this Flow would have: sent SMS to X, called host Y, written file Z".
- Promotion to `Trusted` is explicit, per capability, and records a content hash of the Flow. Editing a trusted Flow in a way that widens its manifest drops it back to `Quarantined` for the new part only.
- Provenance is stored (source, import time, hash) and shown in the Flow list.
- Applies equally to templates and to any future shared-Flow feature.

### 6.4 Triggers and webhook server

- **Event sources.** Sparks for SMS and notifications require a narrowing filter; the Spark shows a live preview of what would match; each Spark has a rate limit and a re-entrancy guard; geofence and other broadcasts come through non-exported components only.
- **Webhook default is off and loopback.** Binding to `127.0.0.1` makes it reachable by other apps on the phone and by tooling over `adb forward`, but not by the LAN. LAN or tunnel exposure is an explicit setting with a visible persistent notification and a countdown or "until I turn it off" choice.
- **Mandatory auth when not loopback.** An auto-generated 32-byte secret per Spark. Request authentication is an HMAC over method, path, timestamp and body, a freshness window (for example 5 minutes), and a nonce cache to stop replay. Constant-time compare (already present). Secrets travel only in headers, never the query string.
- **TLS.** Use a self-signed certificate generated per install with the fingerprint shown in the app for pinning by the caller, or document use of a tunnel that terminates TLS. Plain HTTP only on loopback.
- **Resource limits.** At most 8 concurrent connections, per-IP request rate, an absolute deadline per request (not per read), header 8 KB, body 256 KB, require `Content-Length` or implement chunked with the same cap, depth-bounded JSON parsing, bounded queue of pending Runs with 429 when full.
- **Routing.** Immutable route snapshot swapped atomically; routes only for armed Sparks (6.1 `isArmed`); path is a random token by default rather than a guessable `my-flow`; a method and content-type allowlist.
- **Test vs production.** A "Listen for test event" mode that answers on a temporary path, accepts one event, shows it in the editor, and expires. Production path only exists for armed Flows. Response mode (immediate 202 or wait for the Flow and return a result with a timeout) is selectable.
- **Lifecycle.** A real foreground `Service` with the notification, started only while an armed webhook Spark exists, and stopped when none does. Remove the current manifest entry for a non-Service class.
- **Test matrix:** wildcard bind rejected; slowloris test closes at deadline; 100 parallel connections do not starve Room or HTTP; replayed request rejected; disabled Spark returns 404; deeply nested JSON returns 400 without a crash.

### 6.5 Sandboxed JavaScript (Code Step and expressions)

Requirements before the Code Step ships:
1. **Process isolation.** Run the engine in a separate isolated-process service (`isolatedProcess="true"`, no permissions, no network, no file access). Talk to it over a narrow Binder interface passing JSON in and JSON out only. This also lets the host kill a runaway script, which `withTimeout` cannot do for native code.
2. **Hard limits inside the engine.** Memory limit (QuickJS supports one), stack limit, an interrupt handler with a wall-clock deadline (for example 400 ms for expressions, 5 s for the Code Step), input and output size caps (for example 1 MB each), a cap on items and on string length.
3. **Fresh context per evaluation.** No shared state between Steps or Runs. Freeze intrinsics; remove or neutralise `eval`, `Function`, `import`, `fetch`, `XMLHttpRequest`, timers, `Proxy` and `Reflect` unless explicitly needed; block `__proto__` and `constructor` walks on injected data; prevent prototype pollution through merged data.
4. **Host objects: none.** Inject data as JSON only (the prelude's stated contract, `kaivo_expression_prelude.js:8-13`, is correct). No callbacks into Kotlin except a bounded logging call.
5. **Bounded regular expressions.** Replace `new RegExp(userPattern)` with a linear-time engine or a time-boxed matcher with a length cap on pattern and subject; keep the current `EventTriggerMatcher` stance that regex is rejected until such an engine exists.
6. **No `$env`, no vault.** The sandbox cannot see secrets; if a script needs one, it goes through `secret.reveal` and an audit entry.
7. **Capability.** The Code Step needs `code.run`; imported Flows containing code start in `Quarantined` with the code shown in review (6.3).
8. **R8 and dependency.** Pin the actual library, fix the ProGuard rules to its real package, and add a JNI-load failure path that disables the Code Step with a clear message instead of silently falling back to a weaker evaluator.
9. **Test matrix:** infinite loop is killed on time; 1 GB allocation fails fast without taking the app down; deep recursion returns an error; `constructor.constructor("return this")()` returns nothing useful; ReDoS pattern times out; output of 100 MB is rejected; two evaluations cannot see each other's globals.

### 6.6 Data at rest, retention, backups

- Encrypt the sensitive columns with the DEK from 6.1 (run payloads, frozen outputs, flow params that are not secrets but private) or move to an encrypted SQLite engine keyed by the DEK. Field-level encryption keeps list screens fast because names, status and timestamps stay plain.
- **Retention defaults:** 14 days or 200 runs, whichever comes first; per-Flow "keep data" switch; SMS and notification Sparks store metadata only (time, source, length, matched filter) unless the Flow opts in to keeping bodies; "Clear history" and "Clear everything" actions; automatic purge job.
- **Backups:** exclude everything from Android backup and device transfer. Provide an in-app export (6.1 item 1) that is encrypted with a passphrase, includes Flows, variables and optionally the vault, and is clearly separate from the shareable, redacted `.kaivoflow` file.
- **Shareable bundle scrubbing:** value-based redaction, frozen outputs and notes excluded by default, a pre-export report listing what was removed and what still looks sensitive (URLs with query strings, emails, phone numbers, long hex/base64 strings).
- **WorkManager data:** pass only a Run request id through WorkManager `Data`; fetch the payload from the encrypted store.

### 6.7 Audit log

An append-only table, separate from run history, so pruning runs never erases it.
- **Fields:** timestamp, actor (user action, Spark kind, import, system), action, subject (Flow, Step, credential, grant), outcome, redacted details, and a chain value (HMAC of the previous row and this row, keyed by a Keystore key) so tampering and gaps are detectable.
- **Events:** credential created, changed, deleted, **used** (Flow, Step, host, status); reveal and export; grant given, widened, revoked; import and trust transitions; Spark armed and disarmed; webhook accepted, rejected (reason, source IP) and replay blocked; SMS sent (recipient hash, count); permission changes; sandbox kills and egress denials; migration and backup events; app update.
- **UI:** a filterable timeline in Settings, per-Flow tab, export to a file, retention one year by default, and a visible integrity indicator.
- **Rule:** no secret or message body ever enters the log.

### 6.8 Egress policy (one choke point in the gateway)

- Default `https` only. `http` only for hosts the user marked as trusted LAN, through a scoped network security config entry, never a global switch.
- Resolve DNS in the policy layer, check every resolved address against a deny list (loopback, link-local, RFC 1918, CGNAT, unique-local IPv6, `.local`) unless the Flow holds `net.lan`, then connect to that address (prevents DNS rebinding). Re-check on every redirect hop; maximum 5; strip `Authorization` and cookies when the origin changes; never follow into a denied range.
- Credential host binding (6.1) enforced here.
- Streaming caps and timeouts (SEC-12), content-type checks, and a per-Flow request budget.
- Optional per-credential certificate pin and custom CA import for self-hosted services.
- The same layer wraps the TCP, WebDAV, GraphQL and RSS Steps so no node opens its own socket without policy, with TLS hostname verification on by default (SEC-03).

### 6.9 Android surface checklist

Non-exported by default for every component; system-only broadcasts through non-exported receivers; any receiver that must be exported is permission-guarded and treats every extra as untrusted input; Share intent confirmed per use; no `FileProvider` unless needed and then with narrow paths; `FLAG_SECURE` on sensitive screens; clipboard writes flagged sensitive; request permissions at the moment a Step needs them (not declared and unused); `targetSdk` current; `debuggable` false in release; Keystore, StrongBox and unlocked-device options on; R8 rules audited per dependency.

---

## 7. Suggested phase tasks

Sizes: S under 1 day, M 1 to 3 days, L over 3 days. Each task has an acceptance test; tests live beside the code (the repo already has JVM test modules for engine, nodes, data and work).

### Phase S0: Stop-the-bleeding hotfixes (about 2 to 3 days)
1. [S] `exported=false` on `MobileEventReceiver` and `BootReceiver`; add a manifest assertion test (SEC-02, SEC-21).
2. [S] Hostname verification and SNI in `TcpNode`; mismatched-cert test (SEC-03).
3. [S] Introduce `Flow.isArmed()` and use it in `FlowScheduler.schedule/rescheduleAll/dispatchTrigger`; force `isActive=false` on import (SEC-11, SEC-10).
4. [S] Rewrite `data_extraction_rules.xml` to exclude everything; correct Privacy Centre and README wording (SEC-09).
5. [S] Remove the `<service>` entry for `WebhookServer` until the rewrite; document that it is not shipped (SEC-04).
6. [S] Streaming byte caps in `KtorHttpGateway`; remove `$env` (use an allowlist) (SEC-12, SEC-20).
7. [S] Remove `fallbackToDestructiveMigration`; add a migration-test scaffold (SEC-17).
8. [S] Drop unused permissions (`READ_SMS`, `BLUETOOTH_SCAN`, `MODIFY_AUDIO_SETTINGS`, invalid `uses-permission` for the listener) and decide on `ACCESS_BACKGROUND_LOCATION` and `USE_EXACT_ALARM` (SEC-21).
9. [S] `.gitignore` additions; untrack `build/reports`; remove `security-crypto` and `biometric` unless wired in S1 (SEC-22).

### Phase S1: Vault and redaction (about 1 to 2 weeks)
1. [M] Envelope encryption (`DEK` wrapped by Keystore `KEK`), versioned blob with associated data; keep reading v1 blobs; StrongBox and unlocked-device options (SEC-18).
2. [M] Credential picker in the inspector, "create credential" inline, typed credentials from `WorkspaceResources`; id never shown as text.
3. [M] Migration: move inline `Secret` literals into the vault; block literals in `Secret` params at save; stop raw flow mirroring (SEC-01).
4. [M] `Redactor` plus `SecretValue`; apply to outputs, errors, history, export and clipboard; move secrets out of URLs for Telegram and Gemini (SEC-07, SEC-10).
5. [S] Biometric gate for reveal/export; `FLAG_SECURE` on credential screens (SEC-23).
6. Tests: grep-the-database test, forced-timeout error test, export scrubbing test.

### Phase S2: Capabilities, safe mode, import (about 2 weeks)
1. [M] `Capability` set on `NodeSpec`; manifest computation; preflight enforcement in `FlowRunner` (SEC-13).
2. [M] Grant store and UI; constraints (hosts, recipients, rate); runtime checks for data-driven targets.
3. [L] `Quarantined`/`Trusted` states, review screen, dry-run with stub gateways, content hash (SEC-10).
4. [S] Clamp numeric fields on import; drop frozen outputs by default; export scrubbing report (SEC-10).
5. [M] Spark creation rules: narrowing filters for SMS/notification, per-Spark rate limit and loop breaker, share confirmation (SEC-15).
6. [S] Restrict `openUrlOrApp` schemes; per-Flow file directory; SAF-only `content://` (SEC-19, SEC-25).

### Phase S3: Network and egress (about 1 week)
1. [M] `EgressPolicy` in the gateway: deny ranges, DNS pinning, redirect re-check, header stripping, credential host binding (SEC-06, SEC-14).
2. [S] Scoped cleartext allowance for trusted LAN hosts; remove any need for a global switch.
3. [S] Move TCP, WebDAV, GraphQL and RSS through the same policy.
4. Tests: redirect with `Authorization` to a second host; loopback and RFC 1918 URLs; rebinding stub resolver.

### Phase S4: Isolated JS sandbox (about 2 to 3 weeks, gate for the Code Step)
1. [L] Isolated-process service, Binder JSON interface, QuickJS with memory/stack/deadline limits (SEC-05).
2. [M] Hardened prelude: frozen intrinsics, no `eval`/`Function`, bounded regex, pollution tests.
3. [S] ProGuard rules for the real library; JNI-failure path disables the Code Step explicitly.
4. Tests: the matrix in 6.5, plus a device test on a low-memory profile.

### Phase S5: Webhook rewrite (about 1 to 2 weeks)
1. [L] Real foreground `Service`; loopback default; connection and rate limits; deadline per request; atomic route snapshot; chunked or required length; depth-bounded parsing (SEC-04).
2. [M] Authentication: generated secret, HMAC over method/path/timestamp/body, nonce cache; no query-string secrets.
3. [M] TLS option (per-install certificate with shown fingerprint) and documented tunnel use.
4. [S] Test-listen mode with expiring path; response modes.
5. Tests: the matrix in 6.4.

### Phase S6: Data at rest, retention, audit (about 2 weeks)
1. [M] Field-level encryption for run payloads and non-secret private params, keyed by the DEK (SEC-08).
2. [S] Retention defaults, purge job, "Clear history" and "Clear everything"; metadata-only SMS/notification storage; WorkManager `Data` carries only an id (SEC-08).
3. [M] Encrypted, passphrase-protected full export/import (vault optional); real migrations (SEC-09, SEC-17).
4. [M] Audit log with chained entries, viewer and export (SEC-16).
5. Tests: tamper detection, purge, restore round trip.

### Phase S7: Hygiene and continuous checks (ongoing, first pass 2 to 3 days)
1. [S] Update AGP, Kotlin, Compose BOM, Ktor, Room, WorkManager, Hilt; raise `targetSdk`; re-enable the AGP metadata checks and remove `-Xskip-metadata-version-check` (SEC-22).
2. [S] CI: dependency-vulnerability scan, Android lint security checks, secret scanner on diffs, Gradle dependency verification and wrapper checksum.
3. [S] Fuzz tests for `NativeFlowBundle.preview`, `FlowStructureBundle.preview`, webhook parser and `JsonCodec.decodeFlow` (random nesting, huge strings, duplicate ids, wrong types).
4. [S] R8 rule audit and mapping-file archive (SEC-24).
5. [S] Release signing plan: keys outside the repo, Play App Signing.

### Dependencies between phases
S0 is independent and goes first. S1 unblocks S2 (grants reference credentials) and S3 (host binding). S4 and S5 can run in parallel with S2 after S0. S6 depends on the DEK from S1. Do not enable the Code Step or the webhook Spark in any release before S4 and S5 respectively.

---

## 8. Appendix: evidence index (quick lookup)

| Topic | Where |
|---|---|
| Vault key and cipher | `core/data/src/main/kotlin/com/kaivoflow/core/data/CryptoBox.kt:26-61` |
| Vault repository, resolve, save | `core/data/.../Repositories.kt:197-271` |
| Credential id lookup from Step | `core/engine/.../StepExecutor.kt:62-68` |
| Inspector secret field, no picker | `feature/canvas/.../sheets/StepInspectorSheet.kt:488-513` |
| Flow mirror write | `core/data/.../Repositories.kt:105-112` |
| Backup rules | `app/src/main/res/xml/data_extraction_rules.xml`, manifest `:53-57` |
| Webhook server | `core/work/src/main/kotlin/com/kaivoflow/core/work/WebhookServer.kt` |
| Geofence receiver | `core/work/.../MobileEventReceiver.kt:32-66`, manifest `:126-136` |
| Trigger dispatch and arming | `core/work/.../FlowScheduler.kt:53-63,133-144` |
| Expression stub | `core/expressions/.../QuickJsExpressionEvaluator.kt:8-17` |
| Prelude risks | `core/expressions/src/main/resources/js/kaivo_expression_prelude.js:131,150-151,247-252,281` |
| HTTP gateway | `core/network/.../KtorHttpGateway.kt:61-137,222` |
| HTTP Step auth and URL | `core/nodes/.../HttpNode.kt:91,99-116,149-177` |
| TCP TLS | `core/nodes/.../ProtocolNodes.kt:318-322` |
| Device gateway (SMS, files, intents) | `core/work/.../AndroidDeviceGateway.kt:84-117,142-152,242-262,421-425` |
| Import bundles | `core/data/.../NativeFlowBundle.kt`, `FlowStructureBundle.kt`, `feature/flows/.../FlowTransfer.kt` |
| Run persistence | `core/data/.../JsonCodec.kt:227-258`, `Repositories.kt:154-195` |
| Destructive migration | `core/data/.../DataModule.kt:20-23` |
| Build config | `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/proguard-rules.pro` |
