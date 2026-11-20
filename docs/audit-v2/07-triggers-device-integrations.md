# Audit v2 / 07 - Triggers (Sparks), Device Steps, App Steps and integration strategy

Branch audited: `claude/phase-4-base` (= origin/main). Date: 2026-11-20. Read-only audit.
Reference: n8n 2.48.6 at `/tmp/ref/n8n` (behaviour only, own words, paths as pointers).

Short verdict: the trigger plumbing (Inbox, dedupe, lease, misfire, reliability evidence, publish-time checks) is better than n8n and better than most Android automation apps. The weak spots are on the edges: how a webhook actually reaches a phone, what the Test Step shows versus what live events carry, a handful of Android API mistakes in the Device Steps, and a very thin integration layer (6 app Steps, no OAuth sign-in, no email, no database).

---

## 1. Scope and files read

Read completely (per-file line pointers used below are real per-file lines):

| Area | Files |
|---|---|
| Spark specs | `core/nodes/.../spark/` all 17 files (`TriggerSpecs`, `EventTriggerMatcher`, 12 Spark nodes, `PassThroughTrigger`, `MobileTriggerMath`) |
| Device Steps | `core/nodes/.../device/` all 15 nodes + `DeviceGateway.kt`, `UserFileStore.kt`; Android side `core/work/.../AndroidDeviceGateway.kt` |
| App / net Steps | `app/messaging/{Telegram,Slack,Discord}Node.kt`, `app/productivity/{GoogleSheets,Notion}Node.kt`, `net/web/{Http,ServiceConnector,Rss,GraphQl,WebDav,BinaryUpload}Node.kt` (Http, Connector, Rss in full), `kit/ConnectorSupport.kt`, `data/items/{PollChanges,Dedupe}Node.kt`, `catalog/NodeCatalog.kt` |
| Engine side of triggers | `core/engine/.../alwayson/*` (Basics, EventSources, InboxCore, RunDispatcher, ScheduleSlots, WebhookCore, GeofenceCore, FgsAndLease), `SchedulePlanner`, `SparkSamples`, `EngineNeeds`, `PublishValidator`, `OAuth2TokenManager`, `FlowRunner.collectInput` |
| Android runtime | `core/work/.../alwayson/*` (Receivers, DeviceReceivers, DeviceEvents, Intake, InboxPayloads, EngineController, EngineSources, NotificationListenerHub, Geofences, Reconciler, Registrars, ScheduleFirer, AlarmScheduler, FlexibleWork, Workers, WebhookEngine, RunHost, AlwaysOnService, ActivationManager, ReliabilityCenterData part), `WebhookServer.kt`, `BootReceiver.kt`, `FlowScheduler.kt`, `ReceiverEntryPoint.kt` |
| UI wiring | `feature/permissions/*` (Permission Centre, `KaivoNotificationListener`, `SmsSparkReceiver`), `MainActivity.kt`, `app/src/{main,play,sideload}/AndroidManifest.xml` |
| Docs | `docs/08`, `docs/09`, `docs/10` (feasibility review in 3.9) |
| n8n | `core/src/execution-engine/{scheduled-task-manager,poll-trigger-executor,poll-cursor-hooks}.ts`, `nodes-base/nodes/{Schedule,Webhook,RssFeedRead,ErrorTrigger,Telegram}`, `cli/src/controllers/oauth/*` |

Not read in depth (other auditors own them): FlowRunner internals, expression engine, vault UI, AI nodes, canvas UI. Existing tests (`TriggerReliabilityTest`, `MobileTriggerTest`, `WebhookCoreTest`, `GeofenceCoreTest`, `EventSourcesTest`, `InboxAndDispatcherTest`) were listed, not run.

---

## 2. How n8n does it

### 2.1 Trigger types and who owns the clock
- A trigger node is a normal node with no input. There are three runtime families: **trigger** nodes (own a long-lived listener or a cron), **webhook** nodes (the server owns an HTTP route; the node registers/unregisters it) and **poll** nodes (a cron calls `poll()` and the node returns items or null).
- Cron work is done by one in-process scheduler (`scheduled-task-manager.ts`). Only the *leader* instance registers crons. Each tick knows the canonical time it was scheduled for. Nothing is persisted: if the process is down at fire time, the tick is lost. There is no misfire policy.
- Newer versions pass a dedupe key built from workflow id, node id and the scheduled time into the emit call, so a leader switch does not double fire.

### 2.2 Schedule Trigger
- Several rules in one node. Each rule is one of: seconds, minutes, hours, days, weeks, months, or a raw cron expression.
- "Every N days/weeks/months" is not a cron feature, so the node keeps a small recurrence counter in static data and skips ticks that do not match.
- Hour/minute pickers, weekday multi-select, day-of-month for monthly. Timezone comes from workflow settings (instance default otherwise).
- Output item carries: ISO timestamp, readable date, readable time, day of week, year, month, day, hour, minute, second, timezone label. Downstream nodes use these directly.
- Manual run of the workflow fires the node once with the current time.

### 2.3 Webhook node
- Two URLs per node: **test** (listens only while you press Listen on the canvas, shows data live) and **production** (only while the workflow is active). The path may contain route params like `:id`.
- Methods: one HTTP method or several. Authentication: none, basic, header, JWT. Optional IP allow-list, CORS allowed origins, raw body, binary upload (multipart saved as binary data).
- The output item has four parts: `headers`, `params`, `query`, `body`. This is the single most used shape in n8n expressions.
- Response modes: answer at once ("on received"), answer with the last node's data, or answer from a dedicated **Respond to Webhook** node (status code, headers, body, redirect, file, JWT). Response code and headers are configurable. Streaming response mode exists for AI.
- Service-specific webhook nodes (Telegram, GitHub, Stripe, Typeform...) register themselves on the remote service when the workflow activates (`webhookMethods.checkExists/create/delete`, e.g. `setWebhook` with a secret token for Telegram) and remove it on deactivate.
- Pre-condition for all of this: a public HTTPS URL. n8n assumes a server. Self-hosters use a reverse proxy or a tunnel.

### 2.4 Poll triggers and their dedupe state
- About 20 nodes are poll triggers (RSS, Gmail, Google Sheets, Airtable, GitHub, Jira...). The cron calls `poll()`. The node reads its own **static data** (a JSON blob stored with the workflow), asks the API for "things newer than my last cursor", updates the cursor and returns items or null.
- RSS Feed Trigger: stores the newest item date it has seen; on the first poll it uses "now", so the existing backlog is **not** emitted. In manual mode it returns just the first feed item to give you sample data.
- Cursor handling is staged: a cursor change made inside `poll()` is only saved when the poll result is emitted (or a no-item poll commits it explicitly). If the workflow was edited while a poll was in flight, the stale result is dropped and the new registration re-polls the same window. A failed first poll at activation is thrown back to the user, later failures go to the error workflow.
- The activation poll ("testing trigger") runs once when you publish, so a broken credential is reported at publish time.

### 2.5 Other triggers
- **Manual Trigger**: starts from the canvas button only.
- **Error Trigger**: has no listener. The engine starts the error workflow itself and hands it an item with execution id, url, error message and stack, last node, mode, and workflow id/name. In manual mode with empty input it returns an example item.
- **Chat Trigger / Form Trigger**: n8n serves a hosted chat page or form page from its own HTTP server and starts the workflow when the visitor submits. They need the same public URL as webhooks.
- **Cron** (old) and **Interval** are legacy forms of the Schedule Trigger.

### 2.6 Node styles
- **Programmatic**: a class with `execute()` (or `trigger()/poll()/webhook()`), full freedom. 505 node classes in this checkout.
- **Declarative**: a JSON-like description with a `routing` block per operation (method, url template, qs/body templates, pagination, output post-processing). The engine renders the form, builds the request and parses the response. 106 node files in this checkout use `routing`. Declarative nodes cannot be triggers; they have no custom logic, so anything unusual (binary, multi-step, polling) falls back to programmatic.
- Credentials are separate typed objects (416 credential files) with an `authenticate` rule (header/qs/body injection) and an optional `test` request. One node lists which credential types it accepts.
- `loadOptions` and `resourceLocator` give dynamic dropdowns (pick a sheet or database by name) backed by live API calls.

### 2.7 OAuth2
- The server owns a callback route. The editor opens the provider consent page in a popup, the callback exchanges the code (PKCE is supported), stores access+refresh tokens in the encrypted credential, and refreshes before use. CSRF state is signed. Dynamic client registration exists for MCP-style servers.
- The user usually still creates their own client id/secret at the provider for self-hosted instances. n8n Cloud ships its own client ids.

### 2.8 Community nodes
- Third-party npm packages named `n8n-nodes-*` are installed into the instance from the UI (module `community-packages`). They run with full process privileges: there is no sandbox. A "verified" list exists for cloud.
- Lesson for KaivoFlow: a community mechanism is a growth lever, but only a *data-only* format (no code) is safe on a phone.

### 2.9 Edge behaviours worth copying or avoiding
- Copy: separate test and production listening, `headers/params/query/body` webhook shape, Respond-to-Webhook node, "skip backlog on first poll", activation poll to fail early, readable date parts in the schedule item.
- Avoid: crons that are lost on restart, trust in a public URL, community code running unsandboxed.

---

## 3. How KaivoFlow does it today (verified)

### 3.1 Catalog and shape
- 12 Sparks: manual, schedule, webhook, notification, sms, share, battery, geofence, bluetooth, wifi, headset, boot (`TriggerSpecs.kt:12-28`, registered `NodeCatalog.kt:107-120`). 15 Device Steps (`NodeCatalog.kt:171-186` area). 5 SaaS presets (Telegram, Slack, Discord, Google Sheets, Notion) + RSS + generic HTTP/Connector/GraphQL/WebDAV/BinaryUpload. `net/protocols/` is an empty folder.
- Every Spark executor is a pass-through (`PassThroughTrigger.kt:9-10`). Real work is in `core:work`. A Flow can hold several Sparks; each run starts at the Spark that fired (`startAtStepId = record.sparkId`, `RunHost.kt` `AndroidRunExecutor.execute`).
- `SparkTypes` (engine) and `TriggerSpecs` (nodes) duplicate the same 12 ids; `SparkTypesMatchCatalogTest` keeps them in sync.

### 3.2 One door: persist first, run second
- Every event goes `DeviceEvents` -> `SparkIntake.dispatch` (`Intake.kt:78`) -> `EventTriggerMatcher` (fail-closed filter per Spark) -> `SparkInbox.enqueue` (durable row, dedupe key, spill >32 KB to file, `InboxCore.kt`) -> `EngineController.onInboxWritten` (`EngineController.kt:136`).
- `RunDispatcher` (`RunDispatcher.kt`) claims rows: one Run per Flow, arrival order, max 3 concurrent, 60 s lease renewed every 20 s, a started Run is never replayed after a crash (marked `Crashed`). Good at-most-once rule for SMS/HTTP side effects.
- If the Engine service is alive it hosts the Run; otherwise `FallbackHost` enqueues an expedited WorkManager `FlowRunWorker` (`Workers.kt`, `FlexibleWork.kt:329-339`). Both use the same dispatcher, so ordering holds.

### 3.3 Always-On Engine
- One foreground service, type `specialUse`, `START_STICKY`, `stopWithTask=false` (`AndroidManifest.xml`, `AlwaysOnService.kt:94`). It runs only when a live Flow has a Spark that is Required (webhook, headset) or Wanted (notification, battery, wifi) or the user turned on Always-on mode (`EngineNeeds.kt:37-44`). Schedule, SMS, geofence, boot, share, bluetooth need no Engine.
- Background start rules are explicit (`FgsAndLease.kt:22-36`): exempt reasons (alarm, boot, package replaced, geofence, user) may start the service; SMS/notification/Wi-Fi/Bluetooth/webhook events never try, they use the WorkManager fallback. A refusal becomes a visible Start-blocked state plus a throttled notification.
- Liveness: Pulse alarm every 10 min, Watchdog WorkManager job every 15 min, in-process heartbeat tick, restart alarm in `onTaskRemoved`, `onTimeout` handler. Reconciler is the one idempotent repair routine called by boot, update, time/zone change, Pulse, Watchdog, app start, exact-alarm change (`Reconciler.kt`).

### 3.4 Schedule
- Slot arithmetic is pure and DST-safe (`ScheduleSlots.kt`: gap fires at the transition instant, overlap fires once). Three precision modes: OnTime (`setAlarmClock`), Exact (`setExactAndAllowWhileIdle`), Flexible (WorkManager). Without exact-alarm permission it degrades to Flexible and records an event (`PrecisionPolicy.effective`).
- Fire path (`ScheduleFirer.kt:47-91`): arm next slot first, insert Inbox row keyed `schedule:<flow>:<slot>` (idempotent), mark expected-run Fired/Late, wake consumer. Missed slots get a misfire policy: Skip / RunOnce / RunAll(max 5), max lateness 12 h (daily+) or one interval (`MisfirePlanner`).
- Frequencies: minutes (N), hourly, daily, weekly (days list), monthly (day 1-28 only, `PublishValidator.kt:148-157`). One timezone per Spark. No cron, no seconds, no "every N days/weeks".

### 3.5 Webhook
- A hand-written HTTP/1.1 server on a plain `ServerSocket` inside the Engine (`WebhookServer.kt`). Default bind is `127.0.0.1:8787` (loopback). A "LAN" switch binds all interfaces. Plain HTTP only. 16 connections, 1 MB body, 16 KB headers, 15 s whole-request deadline, no chunked bodies.
- Auth is mandatory and header-only: `Authorization: Bearer <t>` or `X-Kaivo-Token: <t>` (`WebhookCore.kt:54-58`). Loopback accepts the app token or the per-Flow token; LAN accepts only the per-Flow token (HMAC of the flow id under an app secret, `WebhookCore.kt:110-127`). Optional per-Spark `secret` is a body HMAC in `X-Kaivo-Signature` and is checked in addition to the token (`WebhookCore.kt:410`).
- Order is auth -> length -> route -> method -> read body -> signature -> store -> reply, so a 200 means the event is durable. `Idempotency-Key` makes retries a duplicate. Wait-for-run mode returns the last successful Step's output as JSON (`RunHost.kt` `responseJson`), 202 on timeout, 500 on failed Run.
- The listener exists only while the Engine runs and at least one route exists (`WebhookEngine.kt:112`).

### 3.6 Device-event Sparks (what Android is asked)
| Spark | Source | Works without Engine | Needs |
|---|---|---|---|
| SMS | manifest receiver `SmsSparkReceiver` (sideload flavour only) | yes | RECEIVE_SMS runtime grant |
| Notification | `KaivoNotificationListener` + rebind with back-off | yes (system binds it) | Notification access (Settings) |
| Geofence | Play `GeofencingClient`, else proximity alerts, dwell emulated | yes | fine + "all the time" location, location switch on |
| Bluetooth | manifest `BluetoothAclReceiver` + Engine dynamic receiver | partly | BLUETOOTH_CONNECT (API 31+) |
| Battery, Headset | dynamic receivers registered by the Engine only | no | - |
| Wi-Fi | `NetworkCallback` registered by the Engine only | no | fine location (SSID) |
| Boot | `BootReceiver` -> Reconciler -> `intake.dispatch` (`Reconciler.kt:132-138`) | yes | - |
| Share | `MainActivity.handleShare` for `ACTION_SEND text/plain` | n/a | - |

Dedupe keys are built per kind (`EventSources.kt:18-35`): SMS by hash+timestamp, notification by key+postTime, Wi-Fi/Bluetooth/headset by a 10 s bucket, battery by a 60 s bucket, geofence by a 30 s bucket.

### 3.7 Permissions flow
- Publish-time: `PublishValidator` blocks publish when a Spark's permission is missing (SMS, notification access, geofence fine/background) and warns about environment (exact alarm, battery exemption, OEM autostart, foreground-only Steps, side effects without approval). After publish, registrars write a `trigger_registration` row per Spark (Armed / Pending / Failed + reason) and `ActivationManager.checkLive` moves a Flow to Error and back automatically.
- Runtime changes: revoked notification access -> `ListenerTracker` says AccessMissing, registration row fails, Flow goes to Error; revoked location -> `GeofenceEnvironmentReader` re-reads on every pass.
- Device Steps re-check permission at call time and fail with a message (`AndroidDeviceGateway.kt` header comment); Location, Contacts, Calendar return an "available/found=false + reason" map which the node turns into a Step error.
- In-app grant UI: Permission Centre lists exactly four items: notifications, notification access, SMS, exact alarms (`PermissionCentreScreen.kt:46-86`). There is no entry for location, background location, Bluetooth, contacts, calendar, camera (see T-09).

### 3.8 Integrations and credentials
| Step | What it does | Credential use |
|---|---|---|
| `app.telegram` | `sendMessage` only | vault ref -> `accessToken` or `apiKey` -> bot token placed in the URL path |
| `app.slack` | incoming-webhook POST | webhook URL typed into a `Secret` param stored in the Flow, or vault fallback |
| `app.discord` | webhook POST, 2000 char cut | same as Slack |
| `app.google_sheets` | append rows, read range | vault ref -> bearer. OAuth2 refresh via `OAuth2TokenManager` |
| `app.notion` | create page in a database | vault ref -> bearer |
| `net.rss` | read feed | none |
| HTTP / Connector / Poll | generic, bearer/header/basic/saved credential | `connectorHeaders` (Connector) and an inline copy in `HttpNode.kt:163-190` |
- OAuth2 support is **refresh only** (`OAuth2TokenManager.kt:9-15`): the user pastes a refresh token or client credentials; there is no browser consent, no PKCE, no redirect handler, no device-code flow. Rotated refresh tokens are saved (`:109`). One mutex per credential id.
- Export blanks `ParamType.Secret` params (`NativeFlowBundle.kt:254`), so Slack/Discord webhook URLs do not leave the phone in a Flow file.

### 3.9 Review of docs 09 and 10
**Doc 09 (declarative spec engine): good idea, wrong first step, incomplete spec.**
- Feasible: `NodeSpec`/`ParamSpec` are plain data classes and `NodeRegistry` takes a list of `StepExecutor`, so a `DeclarativeExecutor(specJson)` that returns a spec and runs the request template fits without touching the engine. `ServiceConnectorNode` already has URL, headers, auth modes, body, items path, next-page path, retries: it is the runtime core. Contract tests (`StepContractTest`, `AliasCoverageTest`, `CategoryGuardTest`) will need to accept data-defined specs.
- Missing in the doc's spec: pagination styles, response mapping, binary/multipart, rate-limit/429 handling, error message mapping, dynamic dropdowns (`loadOptions`), a credential `test` request, OAuth scopes, and **poll-trigger definitions** (cursor field, "since" param, dedupe id path). Without poll triggers the "Top 30" list cannot deliver triggers on a phone with no inbound URL.
- Wrong assumptions: the doc says GitHub, Stripe, Shopify, Typeform "triggers" and "Form Trigger / Webhook Wait" as if inbound HTTP works. It does not on a phone (see T-01). Also Postgres, MySQL, MongoDB, Redis are TCP protocols; they are not declarative HTTP and a phone should not hold a direct connection to an internet-exposed database. Use REST fronts (Supabase/PostgREST, Upstash REST, Neon HTTP) first.
- Security hole to close before "import a spec from a friend": a spec carries a base URL and an auth rule. A malicious spec can point the base URL at an attacker host and attach the user's saved token. Needs host pinning per credential type, a review screen, and no secret in URL templates (the doc example `bot{apiKey}` is exactly that).
- Order I would use: poll-trigger spec + OAuth sign-in first (they unlock most value), then pagination + loadOptions, then import/share.

**Doc 10 (Android superpowers): half is already built, half is mis-aimed.**
- Already shipped and verified in code: geofence, Bluetooth, Wi-Fi, battery, headset, boot, SMS, notification, TTS, vibrate, torch, share. Doc 10 section 6 lists them as future work; update it.
- Accessibility automation: technically possible, but (a) Play policy limits `AccessibilityService` to accessibility tools, automating other apps risks removal, (b) Android 13+ "restricted settings" makes users jump through extra steps to enable it for sideloaded APKs, (c) WhatsApp bans automated personal messaging. Only a sideload-flavour feature, never the main promise.
- The doc's headline example (auto-reply to WhatsApp) does not need accessibility. A `NotificationListenerService` already sees the notification and its reply `RemoteInput` action; firing that action sends the reply. KaivoFlow already has the listener, so this is the cheaper and policy-safer path (section 6, idea 1).
- Camera snapshot on wrong PIN: background camera is blocked on API 28+ and needs a camera-type foreground service started from a user-visible state; PIN attempt counting needs a device-admin component. Treat as not feasible.
- Incoming-call and app-launched triggers need `READ_PHONE_STATE`/call-log (Play-restricted) and usage-stats or accessibility. Possible in sideload only.
- Interactive notification with buttons is a real gap (`device.notify` has no actions, `AndroidDeviceGateway.kt:43-83`) and is cheap.

### 3.10 What is solid (so nobody rewrites it)
Inbox + lease + crash semantics, per-kind dedupe, misfire planner, DST handling, honest per-Spark registration status with deep-link fixes (`FixLauncher`), Play/sideload split for SMS and exact alarms, bounded webhook server with auth-before-route-reveal, geofence backend fallback and re-registration rules, receivers using `goAsync` + wake lock + time budget, EntryPoint instead of field injection. These are product assets.

---

## 4. Issues found

Severity: P0 = a promised capability cannot work or data/security is at risk; P1 = wrong or unreliable in normal use; P2 = polish, risk later, cleanup. File:line are per file.

| ID | Sev | File:line | What | Why it matters | Fix idea |
|---|---|---|---|---|---|
| T-01 | P0 | `WebhookServer.kt:115-133`, `WebhookEngine.kt:112`, `WebhookCore.kt:98-104,54-58`, `CanvasUiState.kt:171` | Webhook Spark cannot be used end to end. Default bind is loopback only, the LAN option is plain HTTP, there is no tunnel/relay, nothing in `feature/*` or `app` calls `WebhookAdmin` (grep: no UI consumer), so a user cannot see the full URL, copy the token or turn on LAN. The Spark hint even says `https://...` (`WebhookTriggerNode.kt:28`). Auth accepts only two custom headers, which Stripe, GitHub, Shopify, Telegram, Typeform cannot send. | The headline "service calls your phone" feature works only through adb. Competes poorly with every cloud tool. | See section 6 idea 4 and tasks R-01..R-04: show URL+token+QR, accept token in path/query, provider verifier presets, then an optional relay or poll-based triggers. |
| T-02 | P1 | `SparkSamples.kt:12-25` vs `WebhookServer.kt:199-215` | Test Step/manual-run sample for webhook is `{headers, params, query, body:{...}}`, but a live request produces the parsed body at the top level (`{message:...}`), with no headers, query or path params. Live `GET` webhooks lose all data (query string is never read). | Expressions built against the sample (`$json.body.x`) are empty live. User trusts a green test and ships a broken Flow. | Make live payload match n8n shape: `{headers, params, query, body}` and fix the sample (or the reverse), one shape both places. |
| T-03 | P1 | `SparkSamples.kt:26-34,41-54,75-88` vs `DeviceEvents.kt:67-81,87-96,98-107` | Samples differ from real payloads for notification (`appName`, `timestamp`, `executionId` do not exist live; live has `postedAt`), battery (`temperature` vs live `event`,`at`), Wi-Fi (`ipAddress` vs `at`), boot (`event`,`uptimeMs` vs `reason`,`at`), share (`type`,`timestamp` vs `subject`,`sharedAt`), SMS (`timestamp` vs `receivedAt`). | Same trap as T-02 for every device Spark. | One `PayloadShape` per Spark used by both the sample and the live builder; add a test that compares key sets. |
| T-04 | P1 | `InboxPayloads.kt:41`, `WebhookServer.kt:203` | `fromJson` turns every non-string JSON number into `Double`. Integers above 2^53 (Discord snowflakes, Telegram big chat ids, order ids, nanosecond times) lose precision. | Silent wrong id sent to the next API (wrong chat, wrong record). | Keep integers as `Long` when they parse as one; fall back to `BigDecimal`/string above 2^53. |
| T-05 | P1 | `ScheduleTriggerNode.kt:52-53`, `ScheduleSync.kt:83-86` | Time zone is a Select with six options and default `Asia/Kolkata`; the code supports `device` (`zoneOf`) and the validator accepts it, but the UI does not offer it. | Every non-Indian user who does not notice gets 08:00 in India time. A premium global product cannot ship this. | Add option `device` as default; free-text/searchable zone list. |
| T-06 | P1 | `Geofence EventTriggerMatcher.kt:41-58`, `DeviceEvents.kt:164-168`, `GeofenceCore.kt:140-143` | Matcher re-checks distance and rejects an OS ENTER whose `triggeringLocation` is more than 1.15 x radius from the centre (EXIT: less than 0.85 x). A fix with 200-300 m error and a 100-150 m fence is a legitimate OS transition that is silently dropped. Proximity backend synthesises coordinates (centre, or 1.05 x radius north) so `$json.latitude/longitude` are fake. | Geofence "randomly never fires" and gives wrong coordinates. | Trust the OS transition (filter by request id and wanted transition only); send real location when known, plus `placeLat/placeLon` separately. |
| T-07 | P1 | `VibrateNode.kt:30`, `AndroidDeviceGateway.kt:158-160` | `createWaveform(timings, -1)` treats the first number as an OFF delay. Default "120,80,120" = off 120, on 80, off 120: one 80 ms buzz. A single value "200" = 200 ms of nothing. | Vibrate Step does nothing or the wrong thing. | Prepend a 0 and alternate on/off, or use `createOneShot` for one value. |
| T-08 | P1 | `AndroidDeviceGateway.kt:394-416` | Calendar insert hard-codes `CALENDAR_ID = 1`; no `READ_CALENDAR`, so it cannot pick a calendar; no sync-adapter handling. | On many phones id 1 is missing or not writable; insert silently fails ("the calendar could not save this event"). No way to read events (the common automation). | Query writable calendars (needs READ_CALENDAR) and let the user pick; or fall back to `ACTION_INSERT` intent. |
| T-09 | P1 | `PermissionCentreScreen.kt:46-86`, `MainActivity.kt:246-250`, `FixLauncher` (`ReliabilityCenterData.kt:141`) | No in-app request path for fine/background location, Bluetooth connect, contacts, calendar. `FixAction.LocationPermission` only opens location settings and app details. `requestPermissions` result is never read; the list re-reads status right after the call (`PermissionCentreScreen.kt:170-171`) so it shows stale state; a twice-denied permission has no "open Settings" fallback. Play-flavour still lists SMS although its manifest removes it. | Geofence, Wi-Fi, Bluetooth, Contacts, Calendar, Location Steps cannot be enabled with a proper rationale; user hunts through system settings. | Add the missing entries, use the Activity Result API, two-step flow for background location, flavour-aware list. |
| T-10 | P1 | `AndroidDeviceGateway.kt:109-118,273-283`; `PublishValidator.kt:427` | `Open link/app` and `Share sheet` call `startActivity` from a background Run. Android 10+ blocks background activity starts, silently. The validator warns for `device.open` but not `device.share`, and lists the removed id `device.clipboard_read`. | Step reports success, nothing happens. | Warn for `device.share`; offer "post a notification that opens it" mode (full-screen intent only when allowed). |
| T-11 | P1 | `MainActivity.kt:61,79-92`, `Intake.kt:58` | `handleShare(intent)` runs in `onCreate` with no `savedInstanceState`/handled check, and with a random dedupe key. Activity recreation (font scale, locale, density are not in `configChanges`) re-runs the same SEND intent and starts the Flow again. Uses `getStringExtra` so a `CharSequence` extra is ignored. | Duplicate runs of a share Flow, possibly duplicate side effects. | Mark intent handled (`intent.removeExtra`, or a handled flag), dedupe key from content hash+time bucket, use `getCharSequenceExtra`. |
| T-12 | P1 | `PollChangesNode.kt:106-112`, `DedupeNode.kt` | First poll treats everything as new (state file empty): up to 200 items per run. State is saved before downstream Steps finish and written non-atomically (`UserFileStore.kt:24-28` `writeText`); a crash mid-write truncates the seen list and the next poll re-emits everything. | "New RSS item -> Telegram" floods the user on first run; crash re-floods. | Option "skip existing on first run" default on; write to temp file then rename; keep per-Flow static data in the DB. |
| T-13 | P1 | `SmsSendNode.kt` + `AndroidDeviceGateway.kt:85-102,173-174` + `KaivoPermission.Sms` | The Permission Centre requests `RECEIVE_SMS` only; the send Step checks `SEND_SMS`. Nothing requests `SEND_SMS`. SIM choice passes a slot number to `createForSubscriptionId` (needs a subscription id). No sent/failed result. | Send SMS Step fails after the user "allowed SMS". Wrong SIM on dual-SIM. | Request the whole SMS group, map slot -> subscription id via `SubscriptionManager`, add sent intent. |
| T-14 | P1 | `AndroidDeviceGateway.kt:313-341` | Location Step uses `getLastKnownLocation` only: no fresh fix, no age check. In a background Run (geofence, schedule) location is also blocked without "all the time" grant. | Step often errors "No last known location yet" or returns an old point. | `getCurrentLocation` with timeout + `maxAgeMs`/`accuracy` params; report age. |
| T-15 | P1 | `EngineSources.kt` (`ssidOf`, `registerWifi`), `SourcesRegistrar` (`fine` check), `WifiTriggerNode.kt` (no `requiresSensitivePermission`) | Wi-Fi Spark needs fine location AND (from Android 10) background location and the location switch to read the SSID from a background process. Only fine is checked; the Spark has no permission badge. A specialUse foreground service does not count as "while in use" for location. | Wi-Fi Spark shows Armed but never fires (SSID hidden). | Check background grant + location switch; add `requiresSensitivePermission`; device-test (section 8). |
| T-16 | P1 | `app/build.gradle.kts:16` | `targetSdk = 34`. Play requires a recent target level for updates. | Release blocker if the current Play minimum is higher. | Verify the current requirement, then move to 35/36 and re-test FGS, alarm, BOOT rules. |
| T-17 | P2 | `GoogleSheetsNode.kt:34,57`, `:71` | Read default range is `Sheet1!A1` (one cell) so a default read returns only the header; range/ID inserted into the URL without encoding. | Confusing first experience; sheet names with spaces/quotes may fail. | Default `Sheet1`, URL-encode, parse a pasted sheet URL. |
| T-18 | P2 | `NotionNode.kt:57,80` | Extra properties are always `rich_text`; number/select/date/checkbox/url columns return 400. Page content is one paragraph cut at 1900 chars. Database id is not parsed from a URL. | Works only for text columns. | Fetch DB schema, map by type; split content into blocks. |
| T-19 | P2 | `TelegramNode.kt:35` | Default parse mode HTML; dynamic text with `<` or `&` (or any `.`/`-` in MarkdownV2) gives HTTP 400. No retry on 429 `retry_after`. Token sits in the URL path. | Random failures on real data. | Escape helper or default Plain; honour `retry_after`; keep token out of any logged URL. |
| T-20 | P2 | `SlackNode.kt:26` | "Override channel" is ignored by modern Slack incoming webhooks. Slack/Discord offer webhook-only, no bot token API (no thread, blocks, files, read). | Misleading control; thin. | Hide param; add bot-token variant. |
| T-21 | P2 | `ScheduleFirer.kt:74`, `FlowRunner.kt:470` | Scheduled rows carry no payload, so the run item is the generic sample (`timestamp` = run time, not slot time). In a RunAll catch-up all runs look the same. No date parts. | Cannot tell which missed slot a run represents; extra expression work vs n8n. | Put `scheduledFor`, `firedAt`, `lateMs` and date parts in `meta/items`. |
| T-22 | P2 | `EventTriggerMatcher.kt:34-40` | Battery Spark titled "when battery is low" also matches a *charging* edge whenever the level is under the threshold (plug in at 15%). | Surprise fire on plug-in. | Carry `edge` in the payload; match low only on `edge=low`. |
| T-23 | P2 | `WebhookCore.kt:48` vs `PublishValidator.kt:432` | Validator lower-cases the path for checks, runtime route key keeps case. `My-Flow` validates as `my-flow` but the live key is `My-Flow`. | Caller using the shown lower-case URL gets 404. | Lower-case in `normalizeSparkPath`. |
| T-24 | P2 | `WebhookServer.kt:153,154-157` | Accept loop `break`s on any exception while status stays "Listening"; the connection cap closes sockets silently instead of a 503. `Expect: 100-continue`, chunked bodies, multipart not handled; body is decoded to String then HMAC-checked (non-UTF-8 bytes change). | Listener can die unnoticed; some clients fail. | Restart the loop and report Failed; 503 + Retry-After at cap; verify HMAC over raw bytes. |
| T-25 | P2 | `Dead params`: `WebhookTriggerNode.kt:32 requireWifi`, `BootTriggerNode.kt:22 delaySeconds`, `ShareTriggerNode.kt:23 types=image/any`, `NotificationTriggerNode.kt:29 textMatches` | Four settings are shown but never used (or blocked). Share accepts only `text/plain` in the manifest. | User sets them and nothing changes. | Implement or remove; for regex use a timeout-safe engine (RE2J). |
| T-26 | P2 | `BootReceiver.kt:33-39` | Coroutine has `try/finally` but no `catch`; an exception in `reconcile` or `heartbeat.beat` kills the process at boot. Other receivers catch. | Boot crash loop risk. | Add the same catch used in `AlarmReceiver`. |
| T-27 | P2 | `AndroidDeviceGateway.kt:120-141` | TTS has no init timeout and ends with a text-length guess (55 ms per char, 15 s cap) instead of an utterance listener; long text is cut. | Run can hang or speech is cut. | `UtteranceProgressListener` + timeout. |
| T-28 | P2 | `AndroidDeviceGateway.kt:43-83` | No `POST_NOTIFICATIONS` check; `notify` is dropped silently on API 33+ when denied but the Step succeeds. No action buttons, no stable id for update/cancel, uses a stock system icon. | False success; no interactive approvals. | Check and fail clearly; support actions, tag, ongoing. |
| T-29 | P2 | `AndroidDeviceGateway.kt:264-267` | Clipboard read returns empty from a background Run (Android 10+). Validator warns only for read. | Silent empty text. | Document + warn (exists); return an explicit `readable=false`. |
| T-30 | P2 | `AndroidManifest.xml:13,16,20,42` | `ACCESS_BACKGROUND_LOCATION`, `BLUETOOTH_SCAN`, `CAMERA` are in the main manifest for every build; `BLUETOOTH_SCAN` and `CAMERA` are not used by any code (torch needs no camera permission, `TorchNode.kt:24` shows a false "needs permission" badge). `BIND_NOTIFICATION_LISTENER_SERVICE` as `uses-permission` does nothing. | Play data-safety/permission review friction and rejection risk. | Remove unused; move background location to an opt-in build or accept the declaration form. |
| T-31 | P2 | `HttpNode.kt:163-190` vs `ConnectorSupport.kt:21-61` | Auth-header building is duplicated. Both put `shortUrl(url)` (up to 90 chars of the URL incl. query) into error text (`HttpNode.kt:216`, `ConnectorSupport.kt:77`) stored in Run history. | Drift between two copies; a `?api_key=` can land in history. | One shared helper; redact query values in error text. |
| T-32 | P2 | `OAuth2TokenManager.kt:109` | New rotated refresh token is saved after the HTTP response; a process death between response and save loses the only valid refresh token (rotating providers). No `invalid_grant` specific message. | Re-connect needed after a kill at the wrong moment. | Write-ahead: save pending token first, or retry with the previous token when allowed. |
| T-33 | P2 | `WebhookCore.kt:21` | `REQUIRE_AUTH` constant is unused (auth is always required). `SparkTypes`/`TriggerSpecs` duplicate lists. `SmsSendNode.kt:38`/`NotificationSendNode` have unused locals (`scope`, `item`). | Dead code. | Remove. |
| T-34 | P2 | `PublishValidator.kt:429`, `:336-345` | `logic.approval` does not exist, so every Flow with a webhook/SMS/notification Spark plus HTTP POST or send-SMS always gets the "no approval" warning with no way to clear it (an AI approval relay exists, `ApprovalRelay`). | Warning fatigue. | Ship an Approval Step (notification buttons) and reuse the relay. |
| T-35 | P2 | `ReconcileTrigger`/`Reconciler.kt:132-138` + `DeviceEvents` | Event payloads include internal keys `targetFlowId`, `sparkId` (geofence, `GeofenceCore.kt:144-147`) which become item fields. | Noise in the data pane. | Move to `meta`. |

Counts: **P0: 1 (T-01). P1: 15 (T-02 to T-16). P2: 19 (T-17 to T-35).**

---

## 5. Gap vs n8n

| n8n capability | KaivoFlow status | Effort | Value |
|---|---|---|---|
| Schedule: multiple rules in one node | missing (several Sparks per Flow works, so partial) | S | low |
| Schedule: seconds, cron expression | missing (min 1 min, alarms; cron would need a parser over `SlotMath`) | M | medium (power users) |
| Schedule: every N days/weeks/months | missing | S | medium |
| Schedule: timezone choice | partial (T-05) | S | high |
| Schedule: readable date parts in item | missing (T-21) | S | medium |
| Schedule: misfire catch-up | **exceeds** n8n | - | high, market it |
| Webhook: test URL with live listen | missing | M | high |
| Webhook: production URL reachable | missing (T-01) | L | high, core |
| Webhook: headers/params/query/body shape | partial/wrong (T-02) | S | high |
| Webhook: auth none/basic/header/JWT | partial (token header only) | S | high |
| Webhook: provider signature presets | missing | M | high |
| Webhook: respond modes + Respond node | partial (immediate, wait for last Step; no status/headers) | M | medium |
| Webhook: binary/multipart/raw body | missing | M | medium |
| Webhook: IP allow-list, CORS | missing (loopback/LAN scope only) | S | low |
| Poll triggers with cursor state | partial (Step-based poll, file state, no backlog skip) | M | high |
| Activation poll to fail early | missing | S | medium |
| Service webhook auto-register (Telegram setWebhook etc.) | missing; poll alternative missing too | M | high |
| Telegram trigger | missing | M | very high |
| Error Trigger / error workflow | partial (a Flow-level error Step exists; no cross-flow error Spark; not audited in depth) | S | medium |
| Manual Trigger | exact | - | - |
| Chat Trigger / Form Trigger | missing (needs hosted page; see section 6) | L | medium |
| Gmail / Google Calendar / Drive | missing | L | very high |
| Email SMTP/IMAP | missing (`net/protocols/` empty) | M | very high |
| Slack bot API, Discord bot | partial (webhook only) | M | medium |
| Google Sheets full ops | partial (append, read) | M | high |
| Notion full ops | partial (create page, text props only) | M | high |
| Airtable, WhatsApp Cloud, Stripe | missing | S each via declarative | high |
| Postgres/MySQL/Mongo/Redis | missing | L (JDBC size/security) | medium, via REST fronts first |
| Declarative node engine | missing (doc 09) | L | very high |
| Community nodes | missing (data-only spec is the safe form) | L | medium |
| OAuth2 browser sign-in + PKCE | missing (paste refresh token) | L | very high |
| Credential `test` button | missing | S | high |
| Dynamic dropdowns (`loadOptions`) | missing | M | high |
| Pagination helpers | partial (Connector next-url only) | S | medium |
| Rate-limit / 429 handling in app Steps | missing | S | medium |
| MCP client/server | missing (doc 08) | L | high |

---

## 6. Where KaivoFlow can beat n8n

Ideas ordered by value-per-effort. Each says what is concrete and why a server tool cannot do it.

1. **Reply to notifications.** `KaivoNotificationListener` already receives the notification object. Cache its `Notification.Action`s and `RemoteInput`; add a Step "Reply to this notification" and extend the payload with conversation messages (`MessagingStyle`), big text, category, group key, image, actions list. This delivers the WhatsApp/Telegram/SMS-app auto-reply without Accessibility and without WhatsApp Business. Skip group-summary notifications (add the flag check). Worth money: it is the most requested Tasker-type feature.
2. **Interactive notifications as Human-in-the-loop.** Notification buttons ("Approve / Reject") that resume a waiting Run. n8n needs a server and a page; here it is one `PendingIntent` to a manifest receiver that writes an Inbox row. Reuses `ApprovalRelay` and fixes T-34.
3. **Manual entry points no server tool has**: Quick Settings tile, home-screen widget, launcher shortcut, `kaivoflow://run/<flow>` deep link, "Intent received" Spark for Tasker/other apps (protect with a token extra or signature permission). Also NFC tag and QR/barcode scan as Sparks (foreground), and **Share target with images and files** (n8n cannot receive a photo from a gallery).
4. **Webhook without a server of ours** (pick several; they stack):
   - A. **Poll triggers in the declarative spec** (Telegram `getUpdates`, GitHub events, Stripe events list, Discord, RSS). No inbound traffic at all. Covers most "bot" use.
   - B. **Self-owned relay**: a 30-line Cloudflare Worker template the user deploys with one tap; it stores the event and sends a high-priority FCM/UnifiedPush wake; the phone pulls over HTTPS. Works under Doze, gives a real `https://` URL, no phone port. Optional paid hosted version of the same relay (business: this is the natural subscription).
   - C. **ntfy/UnifiedPush subscription** as a generic "push inbox": sender POSTs to a topic, app subscribes (SSE in the Engine, or a UnifiedPush distributor). Open, no server of ours.
   - D. **LAN mode done well**: show URL and token QR, mDNS name via `NsdManager`, a `WifiLock` while listening. This is real value for Home Assistant, ESP32, Shelly, cameras on the same Wi-Fi, which a cloud n8n cannot reach.
   - E. **Tunnel** (Cloudflare Tunnel / Tailscale Funnel in Termux): document only, do not build.
5. **Context conditions that n8n lacks.** A "Context" Spark/IF that combines live phone state: at Wi-Fi X AND charging AND after 22:00, with hysteresis. Phone state is rich (activity recognition, charging, headset, Bluetooth, car mode, DND). Backed by the existing receivers.
6. **Never-miss scheduling as a visible promise.** n8n loses ticks while down. KaivoFlow already records Fired/Late/Missed/Skipped with reasons. Surface a "Reliability score" per Flow and a one-tap OEM fix (`dontkillmyapp`) in the Flow list. Premium signal; mostly UI.
7. **On-device AI at trigger time.** Classify a notification, extract the amount from a bank SMS, OCR a shared receipt (ML Kit, on-device) with no API key or network. A server tool needs an API key and sends private text to a cloud.
8. **Native device Sparks n8n cannot have**: media added (MediaStore `ContentObserver`: new screenshot/photo), calendar event starting (`CalendarContract`), screen on/off, charging state, activity recognition (driving/walking), step counter threshold, shake, DND change, headset button. Each is a small receiver registered by the Engine or manifest, same Inbox path.
9. **Credential safety as a feature**: biometric gate (`BiometricPrompt`) before a Flow with SMS-send or payments runs; per-credential allowed hosts so a credential can never go to another domain (also what makes community specs safe).
10. **Community recipes as data, not code**: JSON spec + template Flow bundle with host pinning and a review screen. Safe on a phone, no Play-policy issue (no downloaded code).

---

## 7. Recommended ordered tasks

Small, each with an acceptance test. Order = value and dependency. R = reliability/trust fixes first, then integrations.

### Phase A - trust fixes (days, not weeks)
| # | Task | Refs | Acceptance test |
|---|---|---|---|
| R-01 | One payload shape per Spark shared by sample and live builder; webhook becomes `{headers, params, query, body}` | T-02, T-03 | JVM test: for each Spark, key set of `SparkSamples.payload` equals the key set built by `DeviceEvents`/webhook processor. Manual run of a webhook Flow shows `$json.body.x` and a live POST gives the same path |
| R-02 | Keep JSON integers as Long/BigDecimal | T-04 | Webhook body `{"id": 1234567890123456789}` reaches the next Step as the exact string `1234567890123456789` |
| R-03 | Schedule time zone option `device` default + full list | T-05 | New Flow in a non-India device zone fires at local 08:00; unit test on `zoneOf` for `device` |
| R-04 | Fix vibrate waveform | T-07 | Pattern "200" -> one 200 ms buzz; "120,80,120" -> two buzzes of 120 ms with 80 ms gap (verify on device, section 8) |
| R-05 | Geofence: trust OS transition, send real location, move internals to meta | T-06, T-35 | Unit test: ENTER with location 250 m from centre of a 100 m fence matches; payload has `placeLat`; device walk test |
| R-06 | Share intent: handled flag + content-hash dedupe + CharSequence | T-11 | Rotate font scale after share: Flow runs once |
| R-07 | Poll/Dedupe: skip backlog on first run, atomic state write | T-12 | First publish of RSS Flow emits 0 items; kill process mid-write test leaves previous state intact |
| R-08 | `BootReceiver` catch; webhook accept-loop restart + 503 at cap; lower-case path | T-23, T-24, T-26 | Fault-injection test: throw in `reconcile` -> no crash; connection 17 gets 503 |
| R-09 | Remove dead params or implement; unused permissions; dead code | T-25, T-30, T-33 | Manifest lint shows no unused dangerous permission; param audit test (every `ParamSpec` key is read somewhere) |

### Phase B - permissions and Device Steps
| # | Task | Refs | Acceptance test |
|---|---|---|---|
| P-01 | Permission Centre: location (fine, then background via Settings), Bluetooth, contacts, calendar; Activity Result API; refresh on result; Settings fallback after two denials; flavour-aware | T-09, T-13 | Instrumented test with `GrantPermissionRule`; Play flavour hides SMS |
| P-02 | Location Step: `getCurrentLocation` + max age; Wi-Fi Spark permission checks + badge | T-14, T-15 | Step returns fresh fix within timeout; Wi-Fi Spark shows `PermissionMissing: background location` when denied |
| P-03 | Calendar: choose calendar, `ACTION_INSERT` fallback; SMS: request group, subscription ids, sent result | T-08, T-13 | Device with two calendars/two SIMs |
| P-04 | Background-safe `Open link/Share`: notification mode; warnings in validator; notify checks permission, supports actions | T-10, T-28 | Flow run in background shows a notification that opens the link; no silent success |

### Phase C - webhook story
| # | Task | Refs | Acceptance test |
|---|---|---|---|
| W-01 | Webhook panel on the Spark: full URL, copy, token, QR, LAN switch, test listening mode that shows the next live request | T-01 | From a laptop on the same Wi-Fi, `curl` the copied URL and see the item in the inspector |
| W-02 | Accept token as path segment or `?token=`; provider verifier presets (Stripe, GitHub, Shopify, Telegram secret header, Slack) | T-01 | Replay recorded provider requests with valid/invalid signatures: 200/401 |
| W-03 | `Respond to Webhook` Step (status, headers, body) | gap | Flow returns 201 with custom header |
| W-04 | Relay template (Worker) + wake via FCM/UnifiedPush; ntfy subscription Spark | idea 4 | Event from the internet reaches a phone in Doze within 60 s (device test) |

### Phase D - integrations (declarative first)
| # | Task | Refs | Acceptance test |
|---|---|---|---|
| I-01 | `DeclarativeExecutor` over `ServiceConnectorNode` core: spec -> `NodeSpec` + request template, with pagination, response mapping, 429 handling, credential `test` | doc 09 | Telegram send + Airtable list defined as JSON pass `StepContractTest` and unit tests with a fake `HttpGateway` |
| I-02 | Poll-trigger spec: cursor field, `since` param, id path, activation poll, DB-backed per-Spark state | n8n 2.4 | Telegram `getUpdates` trigger: first publish emits 0, new message emits 1, restart does not repeat |
| I-03 | OAuth2 sign-in: Authorization Code + PKCE through Custom Tabs, app link redirect, per-provider presets, user-supplied client id first | gap | Connect Google on device, run Sheets append with no pasted token; token refreshes after 1 h |
| I-04 | Host pinning per credential + spec import review screen | doc 09 risk | Importing a spec whose host is not allowed by the credential is refused with a clear message |
| I-05 | Email (SMTP send, IMAP poll via the poll spec), Gmail through OAuth | gap | Send and receive test mail against a local test server |
| I-06 | Upgrade Sheets/Notion/Telegram/Slack/Discord to full operations through the spec; dynamic dropdowns | T-17..T-20 | Pick a Notion database by name; number column writes correctly |
| I-07 | Notification reply Step + extended payload (idea 1); interactive notification approval (idea 2) | section 6 | Auto-reply to a test chat notification; approve from the shade resumes a Run |

---

## 8. Not verified / needs device test

I did not run the app, the Gradle build or any test. These need a real phone (stock Pixel plus one aggressive OEM such as Xiaomi/Oppo/Samsung):

1. Webhook server reachable over Wi-Fi LAN with screen off for 30+ minutes, with and without the battery-optimisation exemption, with and without a `WifiLock` (none exists in code). Doze network suspension.
2. Does a manifest `BluetoothAclReceiver` actually get ACL events on target SDK 34/35 (the manifest comment itself says "checked per device (T-BT)").
3. Wi-Fi SSID visibility from the foreground service on Android 10-15 with only fine location, with background location, with the location switch off (T-15).
4. Geofence accuracy and whether a legit ENTER with a poor fix is dropped by the 1.15 factor (T-06); proximity-alert fallback on a phone without Play services; the 100-geofence limit behaviour.
5. Vibrate timings (T-07), calendar insert on Samsung/Xiaomi (T-08), SMS on dual SIM (T-13), TTS cut-off (T-27), notification suppression with POST_NOTIFICATIONS denied on API 33+.
6. Background `startActivity` blocking for Open link/Share in a geofence-started and an alarm-started Run (T-10); clipboard read from background (T-29).
7. Play policy: acceptance of `specialUse` FGS subtype text, `ACCESS_BACKGROUND_LOCATION`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, notification-listener use case; the current required `targetSdk` (T-16). I could not check the live Play requirements.
8. SMS dedupe key uses sender+body+timestamp; two identical OTPs in the same second collapse into one. Probably fine; check on device with a carrier that stamps seconds.
9. Whether Flow params (including a Slack webhook URL typed into a `Secret` param) are stored encrypted at rest. Export blanking is verified (`NativeFlowBundle.kt:254`); at-rest encryption of the Flow row was not.
10. FCM/UnifiedPush wake latency and battery cost for the relay idea; this is a design estimate, not a measurement.
11. The Notification Spark under heavy load (hundreds of notifications per minute): there is no cap on Inbox rows and per-Flow serial execution can build a backlog (I found no maximum pending size; `InboxCore.kt`, `RunDispatcher.kt`). Needs a stress test and probably a per-Spark rate limit and "drop oldest" policy.
