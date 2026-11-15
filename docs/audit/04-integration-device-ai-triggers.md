# Audit 04: Integration, Device, AI and Protocol Nodes and Triggers

Lane: SaasNodes, ProtocolNodes, CompositionNodes (connector and poll), AiNodes, DeviceNodes, ExtendedDeviceNodes, SecurityNodes, the Spark nodes, and the platform adapters behind them (WebhookServer, FlowScheduler, FlowRunWorker, BootReceiver, MobileEventReceiver, AndroidDeviceGateway, the notification and SMS sources, KtorHttpGateway, OAuth2TokenManager).

Benchmark: n8n, read only for behaviour. Nothing here is copied from it. Every n8n capability below is described in our own words, and the proposals are original designs for KaivoFlow.

Method: static read of every file in the lane, cross-reference of who calls whom (grep for producers and consumers of each Spark), and a read of the manifest. Nothing was compiled or run on a device. Statements about Android platform behaviour are from knowledge of the platform and should be confirmed on a device (marked "verify" where the risk is real).

---

## 1. Summary

KaivoFlow's node layer is cleanly structured (pure-Kotlin executors, a `DeviceGateway` seam, a single `NodeCatalog`) and has good fail-closed instincts (event matcher, SMS permission recheck, path sanitising, binary size caps). The weak points are not style; they are that several headline capabilities **do not work yet** and the integration layer is **hard-coded instead of declarative**.

Top-line verdicts:

1. **Three flagship Sparks never fire.** The SMS Spark receiver mis-parses the PDU extra and returns early on every message. The battery Spark has a matcher but no producer anywhere. The webhook server is never started, never loads routes, and is declared in the manifest as a `<service>` although the class is not a Service. The route also does not match the URL the UI tells the user to use.
2. **Per-item expressions are broken in the older nodes.** `ctx.params()` interpolates every parameter against item 0 first, so the per-item re-resolution in HTTP, Telegram, Slack, Discord, Notion, Ask AI, notification, SMS and others is a no-op. A Flow with 20 items sends the first item's message 20 times. For SMS this is a correctness and safety bug.
3. **Schedules are best-effort and can die silently.** Daily/weekly/monthly use a chained one-shot WorkManager request that is re-armed only after a successful `doWork`; a battery-not-low constraint is forced on every scheduled run; exact-alarm permissions are declared (including a Play-restricted one) but AlarmManager is never used; periodic runs cancel themselves when they re-arm.
4. **The HTTP Request node is a thin first draft.** No retries, pagination, batching, multipart/binary body, response headers, TLS options, proxy, cookie jar or redirect control. A separate "Service connector" node has a little pagination and retry, so capability is split across three overlapping nodes (HTTP, connector, poll).
5. **SaaS nodes are one-off hard-coded calls** with no resource/operation structure, no shared credential-type or auth abstraction, and OAuth2 limited to "paste a refresh token". A declarative REST spec interpreted by one executor would make each new integration roughly 40 lines of data.
6. **AI nodes are single-shot prompt wrappers.** No tool calling, no agent, no memory, no JSON-schema structured output, token usage thrown away, classify drops labels past the third.
7. **Android is the differentiator and is barely used.** Intent send/receive, notification actions and inline reply, quick-settings tiles, widgets, share-target breadth, content-URI triggers, NFC, activity recognition, and an optional always-on foreground engine are all absent and mostly cheap to build.

Counts: 49 node and trigger rows below. KEEP 13, IMPROVE 31, MERGE 4, DELETE 1.

---

## 2. Node table with verdicts

Quality scale: A = solid, B = works with gaps, C = works for the happy path only or has a real defect, D = non-functional or unsafe.

### 2.1 Sparks (triggers)

| Node | Id | Quality | Nearest n8n equivalent | Verdict | Notes |
|---|---|---|---|---|---|
| Manual Spark | trigger.manual | A | Manual trigger | KEEP | Add "run with sample payload" and pinned test data. |
| On a schedule | trigger.schedule | C | Schedule trigger (plus cron expression option) | IMPROVE | Timezone list hard-coded to six zones with Asia/Kolkata default; no cron expression; no misfire policy; run payload carries no scheduledFor/lateBy. See section 6. |
| Incoming webhook | trigger.webhook | D | Webhook trigger | IMPROVE | Server never started; URL hint wrong; secret scheme non-standard; `requireWifi` ignored; no response modes; no test vs production. See section 6. |
| Notification arrives | trigger.notification | B | none (device specific) | IMPROVE | Regex rejected at validation; no dedupe; drops notifications with no title; only EXTRA_TEXT; no remove/action events. |
| SMS arrives | trigger.sms | D | none (device specific) | IMPROVE | Receiver returns early on every message (PDU parse bug); FGS-from-receiver pattern is fragile. |
| Share sheet | trigger.share | C | none | IMPROVE | Only `text/plain` filter though param offers images; no files, multiple, or subject/URL metadata. |
| Battery low | trigger.battery | D | none | IMPROVE | No producer exists. Needs a dynamic receiver or poll plus edge detection. |
| Geofence | trigger.geofence | B | none | IMPROVE | Platform proximity alert (deprecated API, needs background location); good fail-closed matcher; no dwell/home-work presets. |
| Bluetooth connect | trigger.bluetooth | B | none | KEEP | ACL broadcasts are manifest-deliverable (verify). Add pairing-name resolve fallback when permission missing. |
| Wi-Fi changes | trigger.wifi | C | none | IMPROVE | `STATE_CHANGE` is an implicit broadcast and is likely not delivered to a manifest receiver on API 26+; SSID needs location permission. Use a network callback registered with a PendingIntent. |
| Headset plug | trigger.headset | D | none | IMPROVE | `HEADSET_PLUG` cannot be received by a manifest receiver; needs a dynamic registration in a live process. |
| After restart | trigger.boot | B | none | KEEP | Android 15 forbids starting some foreground service types from BOOT_COMPLETED; current path uses WorkManager so it is fine. |

### 2.2 SaaS, protocol and AI

| Node | Id | Quality | Nearest n8n equivalent | Verdict | Notes |
|---|---|---|---|---|---|
| HTTP request | saas.http | C | HTTP Request | IMPROVE | See HTTP gap list (section 3). Per-item expression bug. |
| Service connector | saas.connector | C | HTTP Request with pagination | MERGE | Fold into HTTP Request as its pagination/retry section. |
| Poll for changes | logic.poll | C | Polling trigger with static data | MERGE | Becomes a polling-Spark facility (section 4.6). Default state file name is shared by every poll step, so two Flows corrupt each other's "seen" set. State is a plaintext line file, non-atomic. |
| GraphQL | saas.graphql | B | GraphQL | MERGE | Make it a body mode of HTTP Request (query, variables, operationName, error array check). Keep the errors-array check. |
| Telegram | saas.telegram | C | Telegram (many resources/operations plus trigger) | IMPROVE | Only sendMessage. Replace by declarative spec: sendMessage, photo, document, edit, answerCallback, plus a getUpdates polling Spark (works with no server). |
| Slack | saas.slack | C | Slack | IMPROVE | Incoming webhook only. Add Web API operations with token credential (postMessage, update, upload, reactions, conversation history for polling). |
| Discord | saas.discord | C | Discord | IMPROVE | Webhook only; embed/file support absent. Declarative spec. |
| Google Sheets | saas.sheets | C | Google Sheets | IMPROVE | Append/read only; uses pasted access token; no update/upsert/clear/lookup, no header-aware append (requires the user to list columns in order); range concatenated unencoded into the URL. |
| Notion | saas.notion | C | Notion | IMPROVE | Create page only, every extra property forced to rich_text (fails for number/select/date/checkbox), one paragraph block, pinned API version. |
| Weather | saas.weather | B | OpenWeatherMap | KEEP | Key-free, useful. Make it a built-in "recipe" over the declarative framework later. Hard-coded city table is unnecessary since geocoding is present. |
| Feed reader | saas.rss | B | RSS Feed Read | KEEP | Add conditional GET (ETag/Last-Modified) and "only new" via the polling facility. |
| WebDAV | saas.webdav | B | HTTP Request with WebDAV methods / Nextcloud | MERGE | HTTP Request gains WebDAV verbs and binary download; keep as a preset. Bearer only. |
| TCP socket | saas.tcp | B | none (n8n has no raw socket node; used via code) | KEEP | Bounded and safe. Add hex payload, delimiter read, and UDP/Wake-on-LAN sibling. TLS uses system defaults only. |
| Ask AI | ai.chat | C | Basic LLM Chain / OpenAI chat | IMPROVE | See section 5. Per-item bug; no history, images, schema; usage dropped. |
| Extract with AI | ai.extract | B | Information Extractor / structured output parser | IMPROVE | Good validation, repair absent; shallow type map instead of JSON Schema; no native structured-output mode. |
| Sort with AI | ai.classify | C | Text Classifier | IMPROVE | Hard-wired four ports and only the first three labels can route; substring matching can mislabel. |

### 2.3 Device nodes

| Node | Id | Quality | Nearest n8n equivalent | Verdict | Notes |
|---|---|---|---|---|---|
| Show a notification | device.notify | C | none | IMPROVE | Per-item bug; no POST_NOTIFICATIONS check on API 33+ (silent drop); no action buttons, progress, grouping, or tag/replace. |
| Send an SMS | device.sms | C | Twilio (remote) | IMPROVE | Per-item bug; `createForSubscriptionId(simSlot)` passes a slot index as a subscription id; no sent/delivered result; no compose-intent fallback. |
| Copy to clipboard | device.clipboard | B | none | KEEP | Works from foreground. |
| Read clipboard | device.clipboard_read | C | none | IMPROVE | Android 10+ returns nothing unless the app is foreground; must say so in the UI and offer a share-target alternative. |
| Open link or app | device.open | C | none | IMPROVE | Background activity starts are blocked on Android 10+; from a worker this silently fails. Needs a full-screen-intent or notification-tap fallback and a reported result. |
| Speak | device.speak | B | none | IMPROVE | Waits a guessed time instead of the utterance-done callback; new engine per call. |
| Vibrate | device.vibrate | A | none | KEEP | |
| File | device.file | B | Read/Write Files from Disk | IMPROVE | JSON only and flat app directory; add binary/attachments, SAF user-picked folders, append. Folds into a shared storage node. |
| Battery status | device.battery_status | A | none | KEEP | Adds temperature already. Add health, plugged type, power-save mode. |
| Network status | device.network_status | A | none | KEEP | Add VPN flag, validated flag, bandwidth estimate. |
| Quick message (toast) | device.toast | C | none | DELETE | Toasts from a background process are throttled or hidden on modern Android and have no result. Replace with notify. |
| Share sheet | device.share | C | none | IMPROVE | Same background-start restriction; text only, no files/images. |
| Torch | device.torch | B | none | IMPROVE | Toggle state is held in a field on a shared executor (lost on restart, shared across Flows); query real state through a torch callback. CAMERA permission is not required for torch mode. |
| Volume | device.volume | B | none | IMPROVE | Music stream only; add ring/alarm/notification streams and ringer or DND mode. |
| Current location | device.location | C | none | IMPROVE | Last known location only (can be stale or null); add a one-shot fresh fix with timeout and accuracy target. |
| Wi-Fi details | device.wifi_info | C | none | IMPROVE | Deprecated `connectionInfo`; SSID is hidden without location permission; document it. |
| Find a contact | device.contact | B | none | KEEP | Single result only; add multiple results and fields. |
| Add calendar event | device.calendar_event | C | Google Calendar (remote) | IMPROVE | Hard-coded `CALENDAR_ID = 1`; no calendar picker, reminders, or update/delete. |

### 2.4 Security and file transforms (in lane)

| Node | Id | Quality | Nearest n8n equivalent | Verdict | Notes |
|---|---|---|---|---|---|
| Token tools | transform.token | B | JWT node | KEEP | HS* only; add RS256/ES256 verify with pasted public key and JWKS URL fetch. |
| One-time code | transform.totp | A | none (code step) | KEEP | Correct RFC shape. |
| PDF text | transform.pdf | C | Extract From File (PDF) | IMPROVE | Regex over content streams; fails on font-encoded (CID/ToUnicode) PDFs, which are most real invoices. Use a proper parser (PdfRenderer for OCR path or a PDF text library) and fall back to OCR. |

### 2.5 Platform adapters (not nodes, scored for the record)

| Component | Quality | Verdict |
|---|---|---|
| KtorHttpGateway | C | IMPROVE: single shared client is good; missing TLS/proxy/cookie/redirect/retry/streaming controls; no cap on text response size. |
| OAuth2TokenManager | B | IMPROVE: sound refresh logic with per-credential mutex and https-only token URL; no authorization-code plus PKCE flow, no revoke, no refresh-failure state, no token-endpoint extras. |
| FlowScheduler | C | IMPROVE: see section 6. |
| WebhookServer | D | IMPROVE: rewrite on a tested server library, see section 6. |
| MobileEventReceiver | C | IMPROVE: only wifi/bluetooth/headset/geofence; delivery assumptions flagged above. |
| AndroidDeviceGateway | B | IMPROVE: per-method permission recheck is good; defects listed per node. |
| BatteryHealth | B | KEEP: OEM guidance is useful. Its "exact reminders" check currently advertises something the scheduler does not use. |

---

## 3. HTTP gap list (HTTP Request versus n8n's depth)

What exists: method select (no OPTIONS), URL, query and header key/values, auth none/bearer/header/basic/saved credential, body json/form/raw, timeout, keep going on 4xx/5xx, split array response into items, wrap in field, per-item request loop. Gateway adds a shared OkHttp client, multipart builder (reachable only through the binary upload node), binary download capped at 25 MB (reachable only through the WebDAV node).

What is missing, grouped:

**Auth**
- Credential-driven auth by type, not by guessing fields: OAuth2 access token with auto refresh (partly there through the credential path), API key in query string, digest, OAuth1, AWS signature, custom JSON auth object, client certificate. The current credential branch infers behaviour from which fields happen to exist, and later branches silently override earlier ones (a credential with both a bearer token and a username ends up as Basic).
- Auth must apply to redirects safely (OkHttp drops Authorization on cross-host redirects; document it, add an explicit opt-in as n8n does).

**Request building**
- Separate "send query / send headers / send body" toggles with two entry modes (fields vs raw JSON) for each.
- Body types: multipart form-data with text fields plus files in one call, binary body from an attachment, raw with a chosen content type (raw is always `text/plain`), XML, JSON-from-fields. Method OPTIONS and the WebDAV verbs.
- Array query parameter formats (repeat, brackets, comma), URL-encoding control.
- cURL import (paste a command, fill the form). High value on a phone because API docs are cURL-first.

**Responses**
- Output shape control: autodetect, JSON, text, file (attachment). Today only JSON/text; no file option on this node.
- Full response toggle that includes headers and status message. Today response headers are not exposed at all (only `statusCode`, `durationMs`, `contentType`).
- Response header casing, charset decoding, multi-value headers (the gateway joins repeated headers with a comma, which corrupts `Set-Cookie`).
- Size cap for text responses (only binary is capped; a large JSON body is read fully into memory).
- Streaming, server-sent events, and NDJSON consumption (needed for LLM streaming and Telegram long poll).

**Reliability**
- Retry with attempt count, exponential backoff with jitter, `Retry-After` honouring, and a retry-on list (network errors, 429, 5xx). The connector node retries every non-2xx including 4xx with a fixed delay.
- Batching: batch size plus interval between batches, to respect provider rate limits when many items arrive. Today requests are strictly sequential with no pacing.
- Pagination: offset/page-number increment, cursor taken from the response, `Link` header, "complete when" conditions (empty page, status code, expression), max pages, delay between pages. The connector supports only a next-URL path and 20 pages.
- Separate connect, read and total timeouts. Only the request timeout is configurable.

**Transport**
- Redirect control: follow on/off and max count. `HttpRequestSpec.followRedirects` exists but the gateway ignores it (client is created with follow always on).
- TLS: ignore certificate errors (needed for home servers), custom CA or pinned self-signed certificate per credential, client certificate. Recommendation: do not offer a blanket global ignore; offer "trust this certificate fingerprint" per credential, which is safer and works better for LAN Home Assistant, Ollama or NAS.
- Proxy: per-node proxy URL with auth. System proxy is used by default only.
- Cookie jar scoped to a Run (login then call).
- Cleartext: the app declares no network security configuration. With a recent target SDK, plain `http://` (LAN Ollama, home servers, `127.0.0.1`) is likely blocked. Verify, then add a scoped configuration (RFC 1918 ranges and `.local`) and an explicit per-node "allow http" acknowledgement.

**Correctness**
- Per-item expression resolution (P0 below).
- Secret handling: secrets in params are still expression-interpolated by `ctx.params()`; URLs with keys embedded are logged by `ctx.log`; add redaction of known secret values and common key query names in logs and run records.
- Gemini puts its API key in the URL query. Use a header instead so it never lands in URLs, logs or crash reports.

---

## 4. Integration framework proposal (declarative REST nodes)

### 4.1 Current state (answers question 2)

- Each SaaS node is a bespoke class: parameters, URL, headers, body and error text are written in Kotlin. Telegram, Slack, Discord, Sheets and Notion each do one operation.
- There is no resource/operation structure. Sheets uses an "action" select with two entries; the others have none.
- Credentials are an opaque secret param holding a credential id. `bearerToken()` returns `accessToken` or `apiKey`. There is no credential type definition (fields, how to inject, how to test), so the UI cannot show a typed form per service or a "Test connection" button, and nodes cannot say "I need a Google credential".
- OAuth2 is a token manager only. No sign-in flow: users paste a refresh token and client details by hand. Several nodes (Slack webhook, Discord webhook) accept a raw secret URL as a plain parameter or a credential.
- Error handling is copy-pasted per node. There is no shared retry or rate-limit behaviour.

### 4.2 What would make new integrations cheap

A **ServiceSpec** (data, not code), executed by one generic `ServiceNode`. Proposed shape (original design):

```
ServiceSpec
  id, title, icon, docsUrl, category
  credentialType -> CredentialTypeSpec id
  baseUrl, defaultHeaders, defaultQuery
  resources: [
    Resource(name, title,
      operations: [
        Operation(name, title,
          http: method, path template with {placeholders}
          inputs: [ParamSpec + location(path|query|header|body|bodyField) + required + default + options source]
          body: none | json template | form | multipart
          pagination: none | cursor(path, param) | offset(param, step) | link-header | next-url(path)
          output: itemsPath, perItem|single, idPath, flatten rules, binary field
          errors: messagePath, retryOn(statuses), rateLimit(headerNames)
          test: sample inputs for "Test step"
        )])]
  triggers: [PollingTriggerSpec(operation, intervalMinutes, idPath, cursorPath, sortOrder, firstRun=skip|emit-last-N)]
```

The `ServiceNode` generates its `NodeSpec` from the data: a Resource select, an Operation select filtered by resource, and parameters shown through the existing `showWhen`. The editor, validator, palette search and "Test step" work unchanged because they already read `NodeSpec`.

`CredentialTypeSpec`: field list (with secret flags), an `authenticate` strategy (bearer, header, query, basic, signed request, OAuth2), and a `test` request. OAuth2 types carry authorize URL, token URL, scopes, PKCE flag, client-auth style (body or basic), and extra token parameters. One definition powers the credential form, refresh, and the test button.

Also generate the spec from sources the user already has: **cURL import** and **OpenAPI import** to draft an Operation, and an optional "AI drafts a spec from this docs page" assist (uses the AI connection, output validated against the schema before saving). Because the user is a single person with their own services, user-authored specs matter more than a big shipped catalogue.

Ship specs as versioned JSON assets (bundled plus user-imported). Keep native Kotlin only for what is not REST: SMS, notification, device actions, TCP.

### 4.3 OAuth2 without a server

Implement the standard installed-app pattern: authorization code with PKCE in a Custom Tab, redirect to an app-owned scheme or verified App Link, token exchange directly from the phone, tokens stored through the existing encrypted credential store, refreshed by the existing manager. The user supplies their own client id (and secret where the provider requires one, which is how Notion and Slack work); that is acceptable for a single-user app and avoids any KaivoFlow-hosted server. Add: refresh-failure state surfaced on the credential ("needs reconnect"), a notification when a refresh fails in the background, and revocation on delete.

### 4.4 First ten specs to prove the framework

Telegram (including `getUpdates` polling Spark), Slack Web API, Discord webhook plus bot, Google Sheets, Google Calendar and Gmail (read plus send), Notion, GitHub (issues, notifications polling), Todoist or TickTick, Home Assistant (REST service calls, states), ntfy (publish plus subscribe).

### 4.5 Migration

Keep the node ids `saas.telegram`, `saas.slack`, etc. as thin aliases that load the new spec with a parameter mapping, so saved Flows and templates keep working. Delete the Kotlin classes after migration tests pass.

### 4.6 Polling Sparks (the serverless answer to webhooks)

A phone cannot receive third-party webhooks without a tunnel. For SaaS, the correct serverless design is polling Sparks defined in the ServiceSpec: WorkManager (about every 15 minutes) when the app is idle, a long-poll or SSE loop inside the optional foreground engine for near-instant response. State (last cursor, seen-id ring buffer, ETag) lives in a Room table keyed by flow and spark, not in a text file, with atomic update after the Run is accepted. The first run policy (skip existing vs emit the latest N) must be explicit so a new Flow does not message 200 old items.

---

## 5. AI design proposal

### 5.1 Current state (answers question 3)

Three nodes over one `AiClient` supporting OpenAI-compatible, Gemini, and Ollama. Each node repeats provider, credential, model, base URL, temperature and max tokens. One user message plus an optional system message per call; no history, no images, no tools, no streaming. Extract validates a shallow type map and fails on mismatch (good), but does not retry or repair. Classify has four fixed ports. Provider `usage` is parsed and then dropped. Ollama ignores max tokens. `127.0.0.1` for Ollama means the phone itself; the hint mentions a LAN address but cleartext HTTP to a LAN host is likely blocked without a network security config.

### 5.2 How n8n structures it (own words)

n8n treats the agent as a root node with typed sub-node slots for the chat model, memory, tools and output parser, wired visually. Tools are other nodes (HTTP, workflow, code, calculators, MCP servers). Memory nodes keep a conversation window per session key in various stores. The agent loops: model call, tool calls, observation, until a final answer or a step limit. That is powerful, but five typed connections per agent is heavy on a phone canvas.

### 5.3 Best-value design for a mobile, single-user, serverless app

**A. One AI Connection (credential type)**
Provider, base URL, key, default model, defaults for temperature and max tokens, optional "model list" fetch for a picker. Nodes pick a connection and may override the model. Providers behind one `LlmProvider` interface: OpenAI-compatible (OpenAI, Groq, OpenRouter, Together, LM Studio, vLLM, Ollama's compatible endpoint), Gemini, Anthropic, native Ollama. An on-device model provider (device-vendor model service or a bundled small model runtime) is a later option for offline and privacy; availability varies by device, so treat it as optional.

The provider interface: `chat(messages, tools?, responseSchema?, stream?)` returning text, tool calls, finish reason, and usage. Normalise tool-call formats here so no node sees provider differences.

**B. Node set**
- `ai.chat` (keep id): messages list or single prompt, optional history from memory, image attachments from the Flow's binary fields, response format text, JSON object, or JSON Schema. Output: text, parsed JSON, usage, finish reason.
- `ai.agent` (new): goal prompt, tool list, memory, max steps, max tokens per run, stop-when conditions, approval policy. Output: final answer, `toolCalls` trace, usage. The trace is also written to the Run log as child step entries.
- `ai.extract` (upgrade): accept JSON Schema (convert the existing shallow map automatically), use provider-native structured output (strict schema where supported), one automatic repair retry that feeds the validation error back, then fail with the same clear message as today.
- `ai.classify` (upgrade): dynamic output ports equal to labels plus "other", optional multi-label, confidence, and exact-match against a constrained answer (ask the model for a JSON enum, not free text).
- `ai.embed` and `ai.search` (later): small local vector store in SQLite for "search my notes/messages", nothing leaves the device except calls to the user's chosen embedding endpoint.

**C. Tool calling over KaivoFlow steps (the differentiator)**
A tool is any Step or Sub-Flow the user ticks as "available to the agent". The tool definition is generated, not hand-written: name from the step title, description from its subtitle plus an optional note, parameters as JSON Schema derived from its `ParamSpec`s, with each parameter marked "model fills" or "fixed value". Execution runs the real step through the engine path used by "Test step" with the model's arguments as parameter overrides, with a timeout and result size cap, and the output is returned to the model as the observation.

Safety is the design constraint, because this phone can send SMS and open apps:
- Side-effect tools (SMS, HTTP writes, file delete, open app) default to **require approval**: the agent pauses, a notification with Approve and Deny action buttons appears, and the Run resumes or aborts. Read-only tools run freely.
- **Untrusted input rule:** if the Run was started by an SMS, notification, webhook or share Spark, the input is attacker-controllable (prompt injection). In that case the approval requirement for side-effect tools cannot be turned off for that Flow, and the system prompt marks the trigger text as data.
- Hard caps: max iterations, max tool calls per run, max tokens per run, wall-clock limit. Report each in the Run record.
- Never place secrets in prompts; resolve credentials inside tool execution only.

**D. Memory**
Two small, local pieces: (1) a windowed conversation buffer keyed by a session id expression (for example the chat id or sender), stored in Room with a configurable window and optional summarise-when-full; (2) a key-value "facts" store the agent can read and write through two built-in tools (`remember`, `recall`), stored alongside the existing data tables. No external databases.

**E. Local models and the LAN**
Ollama: add max tokens and context size options, keep-alive, a longer first-token timeout, a "test connection" button that lists models, and a network security configuration scoped to private ranges. Document that localhost is the phone, and show the LAN address of a desktop running Ollama via a manual field. Optional discovery can come later.

**F. MCP client (P2)**
Consume remote MCP servers over HTTP as tool sources. This gives the agent an ecosystem without building each integration. Must go through the same approval policy and an allowlist per server.

**G. Hygiene**
Capture usage into the Run record; redact keys; move the Gemini key into a header; show estimated cost from a user-editable price table; allow a per-flow monthly token budget that stops AI steps with a clear message.

---

## 6. Trigger reliability findings (answers question 4)

### 6.1 Webhook server

Defects, most severe first:

1. **Never started.** Nothing calls `start()` or `refreshRoutes()` (searched the whole repo). The manifest declares the class as a `<service>` with a data-sync foreground type, but the class is a plain singleton, not a Service, so starting it as a service would crash.
2. **URL mismatch.** The Spark's hint says the URL is `.../hook/<path>`, but the server looks up the whole request path (minus slashes) as the key, so `hook/my-flow` returns 404 and only `my-flow` would work.
3. **Auth model is unusable by real senders.** When a secret is set, the caller must compute an HMAC of the body and put it in a custom header; the query-string alternative compares the raw query value to that HMAC, so a static `?secret=` can never match. Common senders (static bearer token, GitHub-style signature, Telegram secret-token header, basic auth) are not supported. With no secret there is no auth at all and the path is a guessable word like `my-flow`.
4. **Network exposure.** It binds the wildcard address over plain HTTP. A phone is rarely reachable from the internet (carrier NAT, Wi-Fi isolation), so "start when a service calls your phone" works only on the LAN or through a tunnel; the UI should say so.
5. **Parsing weaknesses.** Content-Length only (a chunked body is read as empty and still accepted), header read is byte-by-byte with a per-read rather than total timeout, unlimited concurrent connections, the route map is a plain `mutableMap` rebuilt on one thread and read from many, duplicate paths silently overwrite each other.
6. **Payload is thin.** Headers, query string and raw body are not passed to the Flow; JSON objects are flattened into the item, which collides with metadata and loses non-object bodies' structure; multipart and binary bodies are not supported.
7. **No response modes.** It always answers `200 {"accepted":true}` before the Run starts, so the phone cannot act as a small API endpoint. n8n offers immediate, wait-for-last-step, and a dedicated respond step, with custom status, headers and body; KaivoFlow needs at least immediate and "respond from a Respond step".
8. **No test vs production.** n8n separates a temporary test URL (listens once while you watch the editor, captures a sample) from the always-on production URL tied to the activated Flow. KaivoFlow has neither; sample capture is the biggest authoring win.
9. **Declared options not implemented:** `requireWifi`.

Recommendations: replace the hand-rolled loop with a small tested server library already in the Ktor family (the client is already a dependency); default bind to loopback with explicit "allow LAN"; a random 128-bit path token by default; auth modes bearer/header token, basic, HMAC with selectable header and digest style, and a private-range IP allowlist; request limits (connections, rate, total request time, body size configurable up to a cap); full request exposure as `$json.headers`, `query`, `params`, `body`, `rawBody`; multipart parts as attachments; test-listen mode; response modes; an engine-level idempotency key to drop duplicate deliveries; run only inside the foreground engine (below) and show the active URL with copy button. Offer, as honest docs plus one-tap setup, the two serverless ways to reach it from outside: a tunnel the user controls, or a relay topic (section 7, item 4).

### 6.2 Schedule and Doze

Observed design: WorkManager only. Periodic work for minutes/hourly; chained one-shot work with an initial delay for daily/weekly/monthly.

Findings:

- **Inexact by design, and the UI should say how inexact.** WorkManager defers under Doze, standby buckets and battery saver; a "08:00 daily" can run late by minutes to hours. The code comment says so, but the Spark UI promises a time. Show "scheduled 08:00, last fired 08:11, late 11 min" per Flow.
- **Exact alarms declared, never used.** The manifest requests both the user-grantable exact alarm permission and the Play-restricted "use exact alarm" permission (restricted to alarm and calendar style apps; declaring it without qualifying risks review problems). No code uses AlarmManager, yet `BatteryHealth` tells the user to grant exact reminders to avoid "up to an hour" delay. Either use alarms or stop advertising them.
- **Recommended hybrid:** user-set times (daily/weekly/monthly or cron) use an exact-while-idle alarm when the permission is granted (alarm-clock style for the few Flows the user marks "must be on time"), which fires a receiver that enqueues an expedited worker; otherwise fall back to WorkManager with a flex window and say so. Intervals under 15 minutes are only honest inside the foreground engine.
- **Self-healing chain is fragile.** The next occurrence is armed only after `runById` returns normally. An exception, cancellation or process death between start and finish leaves no pending work, and the schedule stays dead until reboot or app update (the only paths that call `rescheduleAll`). Fix: arm the next occurrence first, then run; run the same re-arm in a `finally`; add a low-frequency watchdog periodic job that verifies every enabled schedule has pending work and repairs it; also call `rescheduleAll` on app open.
- **Periodic jobs cancel themselves.** After each run the worker calls `schedule(flow)`, which cancels everything tagged for that Flow's schedule (including the periodic request currently executing) and re-enqueues, so the period restarts from "now" and the running job is flagged cancelled. Re-arm only the one-shot kind.
- **Forced battery-not-low constraint** on every scheduled run. A "battery is at 12%, tell me" or "morning digest" Flow silently never runs when the battery is low. Make it per-Flow, default off.
- **Minimum interval is silently floored** to the platform minimum in code (`coerceAtLeast`), contrary to the stated "reject with an honest message". Validate in the editor and show the floor.
- **Hourly and minutes ignore wall-clock alignment**; no minute offset for hourly. Cron or "every hour at :30" cannot be expressed.
- **Time zones:** six hard-coded zones with Asia/Kolkata as default. Default to the device zone and allow any IANA id; this also fixes travelling users. Time-set and time-zone-change broadcasts already re-register (good).
- **No missed-run policy** (run late, skip, or catch up once) and no `scheduledFor`/`firedAt`/`lateBySeconds` in the trigger item. Add both.
- **No cron expression.** A 5-field cron plus a "next five run times" preview is the standard power-user input; implement it in `ScheduleMath` so it stays unit-testable.

### 6.3 Instant (event) runs

- **Concurrency drops events.** `FlowEngine.run` allows one active Run per Flow and returns a "Canceled: already running" record that is not persisted. Burst events (several SMS or notifications within a second, a chat thread) are silently lost. Add a bounded per-Flow queue with a concurrency setting (default 1 serial, max 3), and persist skipped events as "dropped (queue full)" so the user can see them.
- **WorkManager data limit.** `runNow` serialises the event payload into WorkManager input data, which is capped at about 10 KB; larger payloads (a long share, a big webhook body) throw and the event is lost. Store the event in a Room "pending events" table and pass its id.
- **Expedited work on API 26-30.** The worker requests expedited execution but does not provide `getForegroundInfo`, which expedited jobs need on those OS versions (minSdk is 26); the default throws. Implement it.
- **No dedupe anywhere.** Notification listeners re-post updates of the same notification (progress, message groups, summaries); SMS can be received twice on dual-SIM or resent; webhooks are retried by senders. Add a `TriggerEventLedger` table: (flow, spark, key, firstSeen, expiresAt) with per-Spark TTL and key strategies (notification key plus text hash, SMS hash of sender+body+timestamp bucket, webhook idempotency header or body hash, polling id). Atomic insert-or-skip before enqueue.
- **Notification Spark:** `title` is mandatory in the listener (`?: return`), so title-less notifications are dropped; only EXTRA_TEXT is read (misses big text and messaging-style lines, which is where chat apps put content); regex is rejected at validation; no "removed" or "action" events; the listener does not request rebind after a crash; listener state is not reported in the editor.
- **SMS Spark:** `SmsSparkReceiver` reads `pdus` with `getByteArray`, but that extra is an array of byte arrays, so the call returns null and the receiver returns before dispatching, on every message. Use the platform helper for message extraction, join multipart messages by originating address, pass the `format` extra. It then starts a foreground service from a broadcast receiver, which is subject to background foreground-start rules; replace with `goAsync()` plus an expedited worker, no service needed. The manifest also requests `READ_SMS`, which nothing uses and which is a Play-restricted permission; drop it.
- **Battery Spark:** no producer exists (search found only the matcher and spec). Implement with a dynamic receiver in the foreground engine for immediate edge detection (battery-low and okay, power connected/disconnected) and a WorkManager poll fallback; fire on the downward crossing, not every update.
- **Wi-Fi/headset:** see table; use a network callback with a PendingIntent for Wi-Fi so it works when the process is dead, and dynamic registration inside the foreground engine for headset.
- **Geofence:** `addProximityAlert` is deprecated, needs background location, and can be less reliable than the Play-services geofencing client on some devices. Keep as the no-Google-services path; add the Play-services client as an option where available; add dwell time and arrival/leave debouncing. `ACCESS_BACKGROUND_LOCATION` requires a store declaration.
- **Boot:** fine; remember that Android 15 forbids starting data-sync foreground services from a boot receiver, so never start the engine there; start it from the first user-visible launch or an expedited worker that posts a notification asking the user to tap.

### 6.4 Polling triggers and dedupe

`logic.poll` is a Step, not a Spark, stores state in a plaintext file (default name shared by all poll steps), grows to a configured cap, and is not atomic. Replace with the polling-Spark facility in 4.6. Add conditional GET so unchanged feeds cost almost nothing on mobile data.

---

## 7. Android-unique opportunities (answers question 5)

Prioritised by user value, with the main constraint. "Cost" is relative effort.

1. **Optional always-on Automation Engine (foreground service). Value: very high. Cost: medium.**
   One persistent, honest notification ("KaivoFlow is watching 5 Sparks"). It hosts the webhook server, dynamic receivers (battery, screen, headset, power), long-poll and SSE Sparks, sub-15-minute intervals, and the relay subscription. Use a foreground service type that matches the behaviour (data-sync has a runtime cap on recent Android; verify and choose the right type), declare the permission, request battery-optimisation exemption through the existing BatteryHealth flow, add a "restart engine" tile and boot hint. Off by default; the UI shows exactly which Flows depend on it.

2. **Intent send and receive. Value: very high. Cost: low.**
   - Send Intent node: action, data URI, type, extras, package/component, target kind (activity, broadcast, service), result capture where possible. This gives Termux, Home Assistant companion, Tasker, MacroDroid, bank apps and every deep link.
   - Receive Intent Spark: an exported entry point with a per-Flow secret token carried in the intent, so other automation apps and `adb` can run a Flow with extras as the payload.
   - Plugin-style bridges for the Tasker/Locale family can come after.

3. **Notification superpowers through the listener we already have. Value: very high. Cost: low-medium.**
   Dismiss or snooze a notification, trigger its action button, send an inline reply through its reply action (so a Flow can answer a chat message with no accessibility service), read conversation text properly, and "notification removed" events. Pair with the AI agent (approve in a notification, reply in a notification).

4. **Remote triggers without a server. Value: high. Cost: low-medium.**
   A "Relay" Spark subscribing to a push topic (ntfy or a UnifiedPush distributor, user-hosted or public), so any service that can POST to a URL can wake the phone instantly. This is the realistic serverless alternative to port forwarding. Include a one-tap tutorial for the tunnel approach for those who want a real webhook.

5. **Launch surfaces: quick-settings tiles, home-screen widgets, app shortcuts, share and text-selection targets. Value: high. Cost: low-medium.**
   Up to three user-assignable tiles, pinned and dynamic launcher shortcuts per Flow, a widget with a Run button and last result, share target for text, URLs, images, multiple files, and a "process text" entry in the text-selection menu. Today only a text/plain share filter exists. Run results surface in a notification with Re-run and Open actions.

6. **Media and file triggers via platform content observers. Value: high. Cost: low.**
   Use WorkManager's content-URI constraint so a new screenshot or photo starts a Flow with no polling and almost no battery. Combine with on-device text recognition and the AI extract node for "screenshot to spreadsheet". Add a watch-folder Spark through the Storage Access Framework (user-picked folder) and a file node that reads and writes there instead of only an app-private directory.

7. **Context Sparks that are cheap and distinctive. Value: medium-high. Cost: low each.**
   Activity transitions (walking, driving, still), screen on/off and unlock, power-save and Do-Not-Disturb changes, ringer mode, headset (via engine), NFC tag scanned, calendar event starting (reminder alarm), incoming and missed call state (permission and store-policy sensitive), clipboard changed (foreground only), foreground app changed (usage-access special permission), shake and proximity (engine), network callbacks (validated, metered, VPN).

8. **Device action nodes. Value: medium-high. Cost: low each.**
   Do-Not-Disturb and ringer mode, brightness, media keys (through the media-session from the listener), take a photo, record audio, speech to text, QR/barcode scan, text recognition on an image, set alarm and timer (alarm-clock intents), open settings pages, create a notification channel with sound, haptic patterns. Also contact and calendar pickers instead of hard-coded ids.

9. **Accessibility automation. Value: high for power users. Cost: high. Risk: high.**
   Reading screen text, tapping, typing and global actions give Tasker-class reach into apps with no API. It carries strict store policy, security review, battery and user-trust costs. Recommendation: do not ship in the store build; ship in a separate, clearly labelled build with explicit consent screens, per-app allowlist, and an on-screen kill switch. Items 3 and 7 cover most needs first.

10. **Health and wearables. Value: niche. Cost: medium.** Health Connect read Sparks (steps, sleep) and a Wear OS tile later.

**Distribution warning.** The manifest declares SMS (read, receive, send), exact alarm, background location and call-adjacent capabilities. App stores restrict several of these to a narrow set of app categories. Decide early on two flavours: a store build that avoids restricted permissions (notification-based capture, SMS compose-intent fallback, no exact-alarm-use permission, no read-SMS) and a full build (direct download or open-source store) with everything on. The flavour must be a build-time switch so manifests differ, not a runtime toggle.

---

## 8. Findings

### P0 (data-correctness, dead features, safety)

**P0-1. Per-item expression resolution is broken in the older nodes.**
Evidence: `StepContext.params()` runs `ParamResolver.resolveParams` with the item-0 scope, which interpolates every string. Executors then call `resolver.resolveValue(params[...], scope(index))` on the already-resolved value, which no longer contains `{{`. Affects HTTP, Telegram, Slack, Discord, Notion, Ask AI, Extract (text field uses same pattern), Classify, notification, SMS, clipboard, open, speak. The newer nodes (connector, poll, WebDAV, graphql, extended device) read raw params and resolve per index correctly, which is the pattern to standardise on. No test covers multi-item input.
Impact: N items produce N identical requests or messages; for SMS this can text one person repeatedly.
Fix: make executors read raw `step.params` and use `resolveText(raw, index)`; or add `ctx.paramsFor(index)`. Add a regression test per node with two items and distinct values. Stop expression-evaluating params whose spec type is Secret (`supportsExpression` exists in the spec but `resolveParams` ignores it).

**P0-2. SMS Spark never dispatches.** PDU extraction bug (section 6.3). Fix with the platform message helper, `goAsync()`, expedited worker, drop the service, drop READ_SMS. Add a unit test around the extraction using the platform helper in a Robolectric or instrumented test.

**P0-3. Battery Spark has no event source.** Implement a producer or hide the Spark until it exists. A Spark that can be configured and never fires is worse than no Spark. Add a catalog test that every `TriggerSpecs.all` entry has a registered producer.

**P0-4. Webhook Spark cannot work.** Server never started, routes never loaded, manifest declares a non-Service as a service, hint URL does not match route key. Do not leave the Spark selectable until fixed. Add a loopback integration test (start, POST, assert Run).

**P0-5. Event loss.** One-run-per-Flow lock drops concurrent events silently; 10 KB WorkManager data limit drops large payloads; no queue. Fix with persisted event queue and per-Flow concurrency.

**P0-6. Schedule chain can die and cannot be trusted.** Re-arm before run, in finally, with watchdog; remove forced battery-not-low; fix periodic self-cancel; align manifest and UI with whether exact alarms are used. Add a `TriggerReliabilityTest` case that simulates an exception in run and asserts the next occurrence is armed.

**P0-7. Webhook security gate.** Before the Webhook Spark is enabled: loopback default bind, random path token, standard auth modes, rate and connection limits, chunked-body correctness, total-request deadline. (Listed as P0 because enabling the existing code as-is would be a regression in security.)

### P1 (capability and quality)

- **P1-1. HTTP Request parity.** Everything in section 3, in this order: response headers and full-response mode, retries with backoff and Retry-After, multipart and binary body, pagination (merge the connector), batching, redirect and timeout controls, TLS trust per credential, cookie jar, cURL import, text response size cap. Merge Service connector, GraphQL and WebDAV into it.
- **P1-2. Credential types and OAuth2 sign-in** (4.2, 4.3), including "Test connection" and reconnect state.
- **P1-3. Declarative ServiceSpec framework** with Telegram, Slack, Sheets, Notion, Discord, GitHub, Home Assistant, ntfy as the first specs.
- **P1-4. AI Connection plus provider interface, native structured output, repair retry, usage capture, dynamic classify ports.** Fix classify's label-routing bug now (labels beyond the third silently go to "anything else").
- **P1-5. AI Agent with step-derived tools, memory, approvals and untrusted-input rule** (5.3).
- **P1-6. Trigger event ledger and dedupe; polling Spark facility** (6.3, 6.4, 4.6).
- **P1-7. Wi-Fi, headset and battery Spark delivery fixed** through network callbacks with PendingIntent and the engine's dynamic receivers.
- **P1-8. Optional foreground Automation Engine** (section 7, item 1) hosting webhook, dynamic receivers and sub-15-minute schedules.
- **P1-9. Background-start limits** for open link/app, share, clipboard read, and toast: surface an honest run-time result ("could not open; tap the notification instead"), provide the notification-tap fallback, and delete the toast node.
- **P1-10. SMS send correctness:** subscription id lookup from slot index, sent and delivered results in the output, compose-intent fallback for the store build.
- **P1-11. Expedited worker `getForegroundInfo`** for API 26-30.
- **P1-12. Network security configuration** (private-range cleartext, user-trusted certificate pins) so LAN Ollama and home servers work.
- **P1-13. Schedule UX and engine:** cron expression, device-timezone default and any IANA zone, misfire policy, `scheduledFor/lateBy` in payload, "last fired, late by" status.
- **P1-14. Notification Spark quality:** regex with a bounded-time matcher (run regex on a time-limited character sequence or restrict to a safe subset), big-text and messaging-style content, title optional, removal events, rebind request.
- **P1-15. Secret hygiene:** redact secret values and key-like query names from logs and run records; move Gemini key to header.

### P2 (differentiators and polish)

- Intent send and receive; notification actions and inline reply; tiles, widgets, shortcuts; share-target breadth; content-URI triggers; NFC, activity recognition and other context Sparks; device action nodes (section 7).
- Relay (push-topic) Spark and tunnel tutorial.
- MCP client tools; embeddings and local search; on-device model provider.
- PDF text via a real parser with OCR fallback; JWT RS256/ES256 and JWKS.
- Calendar picker and reminders; contact multi-results; torch and volume state queries; TTS completion callback.
- Accessibility build flavour; Health Connect.

---

## 9. Suggested phase tasks

Each phase lists tasks with acceptance criteria. Tasks marked (shared) touch the engine or credential store, which other audit lanes also cover, so sequence with them.

### Phase 0: Hotfix and honesty (small, do first)
1. Per-item resolution in all affected nodes plus two-item regression tests (P0-1). Acceptance: each listed node, given items A and B, emits distinct requests.
2. Fix SMS receiver extraction and path; remove service and READ_SMS (P0-2). Acceptance: instrumented or Robolectric test delivers a PDU and starts a Run.
3. Gate unfinished Sparks: hide Battery and Webhook in the palette (or mark "coming soon") until their producers exist; add the "every Spark has a producer" catalog test (P0-3, P0-4).
4. Classify label routing fix; Telegram, Slack and Discord truncation limits stay.
5. Remove advertised-but-unused exact-alarm text or implement it (decide in Phase 1).

### Phase 1: Reliable trigger platform
1. Persisted event queue plus per-Flow concurrency and dropped-event records (P0-5).
2. Trigger event ledger and dedupe keys per Spark type (P1-6).
3. Scheduler rewrite: arm-before-run, watchdog, per-Flow constraints, exact-alarm option with WorkManager fallback, cron and misfire policy, honest status (P0-6, P1-13). Acceptance: simulated failure still re-arms; daily Flow shows last/next/late.
4. Expedited `getForegroundInfo`; boot and package-replaced rescue verified.
5. Webhook server rewrite with security gate, test-listen mode, response modes, request exposure (P0-4, P0-7).
6. Automation Engine foreground service with Spark dependency list, battery and notification-permission guidance (P1-8), then the battery, headset, screen and power Sparks on top of it.
7. Fix Wi-Fi, headset delivery (P1-7); notification Spark improvements (P1-14).

### Phase 2: HTTP and credentials
1. HTTP Request parity tasks in the order of P1-1; merge connector, GraphQL, WebDAV as modes and presets; keep old ids as aliases.
2. Network security configuration and TLS trust per credential (P1-12).
3. Credential types, "Test connection", OAuth2 code plus PKCE sign-in, reconnect state (P1-2) (shared).
4. Secret redaction (P1-15).

### Phase 3: Declarative integrations
1. ServiceSpec schema, interpreter node, NodeSpec generation, polling Spark facility (P1-3, 4.6).
2. Port Telegram, Slack, Discord, Sheets, Notion to specs; alias old ids; add Telegram `getUpdates` Spark.
3. Add GitHub, Home Assistant, ntfy (publish and subscribe), Google Calendar and Gmail.
4. cURL and OpenAPI import; optional AI-drafted spec with schema validation.

### Phase 4: AI
1. AI Connection credential and `LlmProvider` interface with the four adapters, usage capture, key in headers (P1-4).
2. `ai.chat` upgrade (history, images, schema), `ai.extract` upgrade (JSON Schema, native structured output, repair retry), `ai.classify` dynamic ports.
3. Memory tables and `remember`/`recall` tools.
4. `ai.agent` with step-derived tools, approvals through notification actions, untrusted-input rule, caps, trace in Run log (P1-5). Acceptance: an agent started by an SMS cannot send an SMS without an approval tap.
5. Later: MCP client, embeddings.

### Phase 5: Android differentiators
1. Intent send and Receive Intent Spark with per-Flow token.
2. Notification actions: dismiss, snooze, run action, inline reply, removal events.
3. Tiles, shortcuts, widget, expanded share target and process-text.
4. Content-URI new-photo Spark plus on-device text recognition node plus watch-folder.
5. Relay Spark (push topic) and tunnel guide.
6. Context Sparks (activity, screen, DND, NFC, calendar) and device action nodes.
7. Store-build versus full-build manifest flavours; accessibility only in the full build.

### Cross-cutting test additions
- Catalog test: every Spark id has a producer; every node's `requiresSensitivePermission` appears in the manifest.
- Two-item tests for every node that issues external side effects.
- Loopback webhook tests; scheduler tests with an injected clock and failure injection; ledger dedupe tests; agent safety tests (approval required, untrusted-input rule, step caps).

---

## 10. Legal note

All n8n observations (HTTP options, webhook response modes, schedule rules, agent sub-node structure, memory and tool concepts) were gathered by reading its source for behaviour and are restated here in original words. The proposed ServiceSpec, ledger, agent tool derivation, approval flow and relay design are original to KaivoFlow. Implementation must not reuse n8n code, identifiers, property names, comments or documentation text.
