# 03 Credentials and Security (whole app)

Audit date: 2026-11-20. Branch: claude/phase-4-base (= origin/main). Read-only audit.
Reference: n8n 2.48.6 at /tmp/ref/n8n. Used for behaviour only. Nothing is copied. n8n paths are pointers.

Verdict in one line: the foundation is better than the old docs claim (real Keystore AES-GCM vault, a strong webhook listener, a careful AI safety layer), but the glue around it is weak. Three of the eight credential types in the UI cannot be used by any node. There is no network guard in the HTTP gateway. Secrets typed inline into a Step are stored in plain text. Fixing about ten small things makes this a credible "your keys never leave your phone" product, which is worth money.

---

## 1 Scope and files read

KaivoFlow (all read in full unless marked "part"):

| Area | Files |
|---|---|
| Vault | core/data/.../CryptoBox.kt, Repositories.kt (lines 235-330, 480-512), Database.kt (CredentialEntity, 85-93), DataModule.kt |
| OAuth refresh | core/engine/.../OAuth2TokenManager.kt |
| Credential model/UI | core/model/.../WorkspaceResources.kt, feature/flows/.../CredentialEditSheet.kt, FlowListScreen.kt (view model, 205-460, 810-830), feature/settings/.../PrivacyAndCredentials.kt, ai/CredentialVaultBridge.kt, ai/CredentialFieldResolver.kt, canvas/.../CredentialFill.kt, ComplexWidgets.kt (credential picker) |
| Network | core/network/.../KtorHttpGateway.kt, NetworkModule.kt; core/ai/.../EndpointPolicy.kt, Providers.kt, ProviderEndpoints.kt, Safety.kt, Approval.kt, Tools.kt (part); ModelDiscoveryService.kt |
| Webhook | core/work/.../WebhookServer.kt, core/engine/.../alwayson/WebhookCore.kt (auth part) |
| Manifest and Android | app/src/main/AndroidManifest.xml, src/debug, src/play, src/sideload manifests, res/xml/data_extraction_rules.xml, proguard-rules.pro, app/build.gradle.kts, MainActivity.kt, BootReceiver.kt, NotificationAndSmsSources.kt, PermissionCentreScreen.kt, AndroidDeviceGateway.kt (parts), ReliabilityDump.kt |
| Data hygiene | Values.kt (redact), StepDataPolicy.kt (redactor), NativeFlowBundle.kt (export/import), FlowEngine.kt (part), FlowRunner.kt (part), JsonCodec.encodeRun |
| Code Step | CodeNode.kt, core/expressions (limits in Errors.kt, Library.kt grep) |
| Nodes touching secrets | HttpNode.kt, TelegramNode.kt, SlackNode.kt, DiscordNode.kt, NodeDsl.kt, SmsSendNode.kt, BinaryToolsNode.kt, MarkdownLite.kt, TokenNode.kt |

n8n (pointers): packages/core/src/encryption/{cipher,aes-256-gcm,aes-256-cbc}.ts, core/src/credentials.ts, cli/src/credentials/credentials.service.ts, cli/src/credentials-helper.ts, cli/src/oauth/oauth.service.ts, cli/src/oauth/validate-oauth-url.ts, @n8n/client-oauth2/src/*, @n8n/config/src/configs/ssrf-protection.config.ts, @n8n/backend-network/src/ssrf, workflow/src/credential-domain-restrictions.ts, cli/src/services/credentials-tester.service.ts, core/src/instance-settings. @n8n/permissions only as pointer (not studied).

Not read: the whole 89k-line UI beyond the credential paths, the node catalog beyond the nodes listed, all tests (only checked they exist).

---

## 2 How n8n does it

Keys and encryption
- One instance key encrypts every credential. It comes from an env var, or is generated once and stored in the instance settings file. Worker processes refuse to start without the same key.
- If env key and file key differ, n8n refuses to start. This protects against silent data loss.
- Credential data is a JSON object. It is encrypted as one string and stored in the credentials table. Name, type and id stay in clear.
- Old format: AES-256-CBC. New format: AES-256-GCM with a key derived per message (random salt, key derivation from the instance key, a version byte in front). Ciphertext can carry a key id prefix so keys can rotate. Text before a colon is only treated as a key id if it matches a strict id pattern, so junk input never reaches the key store.
- A custom key can be passed for special cases (export with a password style flow).
- Decryption failure is wrapped in a typed error carrying credential id, name and type, with separate messages for "no data", "decrypt failed" and "invalid JSON". The UI can tell the user which one.

Using a credential at run time
- A node asks for a credential by type. The core finds the stored row, decrypts it, then applies defaults and overwrites (an admin can force values per type).
- Credential fields may hold expressions. They are resolved at use time, per item. Password-type fields are excluded from expression resolution unless the field allows it. A field stored as an expression is never overwritten by a refreshed token value.
- Credentials can be pointed at external secret stores (vault style). A project-level switch decides which stores a credential may read. Access is checked against the project that owns the credential.
- A credential can be "dynamic" (resolved per end user). That is a server feature, not relevant for one phone.
- The type definition says how to authenticate: either generic (header, query, body, basic) or a function. A pre-authentication hook can fetch a session token and persist it back into the credential. Expirable fields are listed so n8n knows when to call the hook.
- A pre-run check refuses to run a workflow when a node uses a credential the starter cannot access ("node does not have access to the credential").

Per-credential domain allowlist
- Each credential type can add a setting "Allowed HTTP Request Domains": all, specific domains (wildcards allowed), or none. The HTTP and GraphQL nodes enforce it. Hooks that issue their own requests are wrapped so they obey it too. If both the credential and the request carry an allowlist, n8n refuses rather than pick one. Reason: a wide list on one side must not widen the other.

Redaction on read
- When the editor loads a credential, every password-type field and the OAuth token blob is replaced by a fixed placeholder string. Empty fields get a different placeholder so the UI knows "set" from "empty". Collections and JSON leaf values are handled too.
- On save the placeholder is converted back ("unredact") from stored data. Three merge modes exist: unredact (default), replace, partial. So the browser never holds a real secret after load.
- Execution data viewing has its own redaction policy that can be applied per request and per workflow context. A child workflow keeps its own policy.

Testing a credential
- Credential types can declare a test request (a cheap authenticated GET) or a test function on the node. The tester runs it with the unsaved values. It reports HTTP status and a plain message. If the test URL answers something odd, the message says the test URL may be wrong.

OAuth2
- Authorization code flow with a server callback. A CSRF secret is minted per flow, stored server-side in a cache keyed by the state token, with a time limit, and consumed once. The state in the URL is a token, not the data. PKCE verifier is stored the same way.
- A browser binding hash ties the callback to the browser that started it. A user id in the state must match the completing user.
- Authorization and token URLs must be http or https. javascript: and other schemes are refused.
- The token request goes through an SSRF guard in the OAuth client. Every redirect hop is validated.
- Token data lives inside the credential. Refresh happens before the call when the token is near expiry. Refreshed data is persisted.
- Client credentials, authorization code and PKCE flows exist in a small client library.

SSRF protection
- A central outbound HTTP layer guards every request whose destination can be influenced by users. Policy: allowed host, blocked host, allowed range, blocked range, then allow. Default blocked ranges: private, loopback, link-local, unique-local, reserved ranges.
- Checks run before connect (DNS), at connect time (to defeat rebinding), and at every redirect hop. A call site can only skip the guard by an explicit "unsafe" flag, used for fixed, n8n-owned destinations. A lint rule bans uncentralised HTTP in backend code.

Other
- Credential sharing and roles come from the permissions package (project roles, credential owner, user). Not relevant for a single-user phone app. Pointer only.
- The Code node runs in an isolated sandbox with a task runner. The expression engine also uses an isolate that must be acquired and released.
- Webhook nodes support header auth, basic auth, JWT and IP allowlists. The instance sits behind HTTPS in normal use.
- Credentials can be exported decrypted by CLI (admin only) or encrypted. Entire DB backups are useless without the key. Losing the key loses all credentials. That is the main user-visible risk.

---

## 3 How KaivoFlow does it today (verified)

### 3.1 Vault
- Table `credentials` has id, type, label, cipherText, createdAt (Database.kt:85-93). Fields are one JSON map of strings, encrypted as one blob (Repositories.kt:306-322).
- CryptoBox (CryptoBox.kt:21-65): AES-256-GCM, key generated inside Android Keystore under alias `kaivo.vault.v1` (lines 25-40). No user authentication required, no StrongBox request, random 12-byte IV from the cipher, IV stored in front of ciphertext (line 47). No associated data (AAD), so a ciphertext copied between rows still decrypts.
- encrypt/decrypt swallow every exception and return null (CryptoBox.kt:42-49, 51-59). Callers turn null into a message ("could not be unlocked on this device", Repositories.kt:261).
- Old docs/06 says credentials are "SQLite / SharedPreferences text storage". That is wrong. The vault is real and works as designed.
- Resolve path: `EncryptedCredentialRepository.resolve` decrypts and passes through `OAuth2TokenManager.refreshIfNeeded` (Repositories.kt:253-257). `ExprCredentialProvider` then evaluates expressions only in non-secret fields (UniversalExpressions.kt:105-150).
- Two writers touch the same row: the user's Save and the OAuth refresh (RoomOAuth2TokenStore.save, Repositories.kt:497-512). Both are read-modify-write on the whole blob.
- A hidden row with id `ai_provider_config` stores the assistant's provider pick (pointer only, no key; AiProvider.kt:125-134). It is filtered out in the dashboard list and the AI pickers, but not in `list()`, `observeRefs()` or the canvas credential picker (see I-10).
- Constructor of the repo deletes every credential of type `googleSheetsOAuth2` on startup (Repositories.kt:267-273) and hides that type in lists (277, 283, 289).

### 3.2 Credential types and nodes
- Eight built-in types (WorkspaceResources.kt:... ALL list): openAiApi, geminiApi, anthropicApi, ollamaApi, telegramBot, httpBasicAuth, httpHeaderAuth, bearerToken. Plus ad-hoc types saved by Settings: `apiKey` and `oauth2`. Unknown types fall back to the OpenAI form in the editor (CredentialEditSheet.kt: `find(type) ?: OPENAI`).
- Nodes read credentials through one helper: `bearerToken() = accessToken ?: apiKey` (NodeDsl.kt:35-36). Used by Telegram (TelegramNode.kt:46), Slack, Discord, Notion, Sheets, ConnectorSupport, AI slots, HTTP node.
- Field names the UI stores: `botToken` (Telegram), `token` (Bearer), `headerName`/`headerValue` (Header auth). The HTTP node reads `header` and `value` (HttpNode.kt:177-186 area). See I-01.

### 3.3 Credential UI
- Two separate screens. Dashboard Credentials tab with typed forms, test button and detail tab (CredentialEditSheet.kt, 699 lines, works and looks good). And a second older screen at route `credentials` in Settings that creates only `apiKey` and `oauth2` rows (PrivacyAndCredentials.kt, `CredentialsScreen`). Duplicate paths into one store.
- Secret fields use `PasswordVisualTransformation` and an eye toggle (CredentialEditSheet.kt ~545-565). Settings screen uses KeyboardType.Password; the sheet does not.
- Non-secret fields can hold expressions (`KaivoInputField` with `ExprFieldCodec`). Good and matches n8n.
- Test Connection calls `testCredentialConnection` (FlowListScreen.kt:~383). It maps the type to an AI provider and lists models. Any non-AI type falls to OpenAI (AiProvider.kt:82-93 `else -> OpenAI`).
- Created/Last modified: `observeCredentials` sets `updatedAt = createdAt` and `usedInFlowCount = 0` (Repositories.kt:294-296); the view model resets it to 0 again (FlowListScreen.kt:224). Save writes `createdAt = now` every time (Repositories.kt:318).
- Delete: dialog text warns that flows will stop. No usage lookup (the count is hard-coded 0), no confirmation in Settings screen (`GhostButton("Delete")` calls delete directly).

### 3.4 OAuth
- No authorization flow. The user pastes a refresh token, client id and secret (CredentialsViewModel.saveConnectedAccount, PrivacyAndCredentials.kt ~70-95). `OAuth2TokenManager` refreshes with a per-credential mutex (OAuth2TokenManager.kt:22-35), refresh skew 60 s, writes new fields back (line ~118).
- Token URL must start with https:// (line ~46). No other check (no SSRF, no private range check).
- Supports refresh_token and client_credentials. Basic or body client auth. Falls back to a regex JSON reader if content type is wrong (line ~140).
- No PKCE, no browser, no redirect handling, no state or CSRF, no per-provider presets, no `invalid_grant` marking (a revoked token retries on every run).

### 3.5 Network gateway
- One `KtorHttpGateway` (OkHttp engine) is the only HTTP exit (KtorHttpGateway.kt:56-). Good: single choke point.
- It does NOT use `HttpRequestSpec.followRedirects` or `failOnErrorStatus`. The client is built with `followRedirects = true` (line 222). So "followRedirects = false" in the AI code (Providers.kt:68, ModelDiscovery.kt:30) has no effect at transport level.
- Text responses are read fully with `bodyAsText()` (line 121). Only binary downloads have a 25 MB cap (lines 105-110, 214).
- No host or IP checks at all in the gateway. No DNS pin. `retryOnConnectionFailure(true)` (line 233) also retries POSTs after a connect failure.
- Ktor 2.3.12 + OkHttp. No network security config file exists (grep over app/: none). No `usesCleartextTraffic`. Target SDK is 34 (app/build.gradle.kts:16), so cleartext is blocked by the platform by default.

### 3.6 AI endpoint policy (good)
- `EndpointPolicy` (core/ai/EndpointPolicy.kt) is thoughtful: https only for public hosts, loopback and LAN only with an explicit switch, link-local and metadata always blocked, user-info/query/fragment refused, numeric host forms (octal, hex, short) parsed like inet_aton, IPv4-mapped IPv6 handled, `localhost.evil.com` treated as public.
- The `resolver` hook (DNS check) is never supplied by any production caller (grep `EndpointPolicy(` shows only default or allow flags: AiSlots.kt:103, ProviderEndpoints.kt:25). The class comment itself says DNS rebinding is only caught when a resolver is given.
- Only AI calls use this policy. HTTP, RSS, GraphQL, WebDAV, upload, connector and Telegram nodes do not.

### 3.7 Secrets in AI paths (good)
- `ApiKey` class prints `***` (Safety.kt:6-12). `AiCredential.toString` and `AiProviderConfig.toString` mask keys (CredentialVaultBridge.kt:13, AiProvider.kt:~141). The key lives only in the vault; the config row stores a pointer.
- `Redactor` masks exact secrets plus key shapes (sk-, AIza, bearer, key=) on errors, tool results, approval cards, memory. Prompt-injection fence `UntrustedData` with a per-run nonce.
- `StepTools.schema` excludes Secret params from what the model may fill. Author-fixed params win. `neutralize` stops model text from becoming expressions.
- `ApprovalPolicy`: risk tiers Read / WriteDraft / WritePersist / ExternalEffect / Destructive. Unattended runs only allow named ExternalEffect tools with a per-tool cap of 5. Destructive never automatic. Fetch Steps count as Read only with a locked URL and GET.
- Assistant flow context is redacted before it goes to the model (AssistantPrompt.kt:43-45). The assistant cannot set Secret params (FlowProposal.kt:95-99).

### 3.8 Webhook listener (good)
- Hand-written tiny HTTP/1.1 server (WebhookServer.kt). Binds loopback unless the LAN switch is on. Limits: header bytes, per-read timeout, whole-request deadline, connection cap, fixed generic error bodies.
- Auth in WebhookCore.kt:114-127: token must come in `Authorization: Bearer` or `X-Kaivo-Token` header, compared in constant time (line 63). Unknown routes get the same answer as bad tokens (no path probing). LAN mode accepts only the per-Flow token (HMAC of flow id under the app secret); loopback also accepts the app token. HMAC signature verify for Sparks uses `MessageDigest.isEqual` (line 134).
- The app secret is 32 random bytes saved in plain SharedPreferences `kaivo.webhook` (WebhookServer.kt:47-61). Not in the Keystore.

### 3.9 Manifest and components
- `allowBackup=false`, `fullBackupContent=false`, data extraction rules exclude the DB, `flows/`, shared prefs and `kaivo_keys/` (AndroidManifest.xml:54-56, data_extraction_rules.xml).
- Exported: MainActivity (launcher + SEND text/plain), BootReceiver, BluetoothAclReceiver. SMS receiver is sideload only and guarded by BROADCAST_SMS. Notification listener, geofence, alarm and exact-alarm receivers are not exported. DiagReceiver is exported but only in the debug manifest (src/debug).
- No ContentProvider, no FileProvider, no WebView (the only "WebView" text is a UI hint string, AiSettingsSheet.kt:1112).
- Foreground service type specialUse for the engine and for WorkManager's service; subtype text present.
- Proguard: R8 on for release, minimal keep rules, source file names kept. The quickjs rules (proguard-rules.pro "quickjs-kt") have no matching dependency in any build.gradle.kts, and the Code Step does not use QuickJS (see 3.11). Dead rules.
- `uses-permission BIND_NOTIFICATION_LISTENER_SERVICE` (Manifest:42) is a service-binding permission, not a uses-permission. It is harmless but wrong, and `tools:ignore="ProtectedPermissions"` hides the lint.

### 3.10 Permissions model
- Declared always (both flavours): fine, coarse and background location, wifi state, Bluetooth connect and scan, contacts read, calendar write, camera, notifications, boot, foreground service, battery optimisation exemption, wake lock.
- The Permission Centre (PermissionCentreScreen.kt:40-80) knows only four permissions: Notifications, Notification access, SMS (RECEIVE_SMS only), Exact alarms. MainActivity.request only routes these (MainActivity.kt:215-250).
- Nodes that need location, contacts, calendar, SEND_SMS check the grant and return "not granted" (AndroidDeviceGateway.kt:170-175, 315-325, 362, 400). There is no screen that requests them (only one `requestPermissions` call exists: MainActivity.kt:247).
- The agent permission model (ApprovalPolicy) is separate and good (3.7).

### 3.11 Code Step
- Not JavaScript in a VM. The "Code" Step feeds the script to KaivoFlow's own pure-Kotlin expression engine (CodeNode.kt:66-86, doc comment 24-31). So no reflection, no file, no network, no Android API is reachable. Limits (Errors.kt:119-136): 10,000 chars, AST depth 128, 5,000 nodes, 200,000 steps, call depth 24, 1,000,000 chars per string, 100,000 per collection, 1 s wall clock (default). The library has no user regex (only a fixed numeric regex in Runtime.kt:106), so no regex DoS from scripts. `repeat` is bounded by the string limit (Library.kt:245).
- Verdict: safe by construction. This is a real advantage over n8n's code node (which must sandbox a full JS runtime).

### 3.12 Data hygiene
- Flow export (`.kaivoflow`) removes secret-looking keys and reports "rebind" items. Import re-maps ids, disables all triggers, never runs (NativeFlowBundle.kt:25-30, 85-110, 255-272). Size, depth, step and stream caps. Good.
- Step data capture redacts keys, token shapes, bearer, URL passwords, secret query params, PEM keys (StepDataPolicy.kt:84-146). But `FlowEngine` builds the redactor with no known secret values (FlowEngine.kt:82), so the "exact known secret anywhere in text" layer is never used.
- `Run.error`, step errors and run logs are saved without any redaction (FlowRunner.kt:322-333; JsonCodec.encodeRun writes `error` as is).
- Flow JSON is stored in Room and mirrored in plain files under `files/flows/<id>.json` (Repositories.kt:128-137). Inline Secret params (HttpNode authToken/authPassword/credential, HttpNode.kt:83-86) are inside that JSON in plain text.
- AI chat sessions and custom agents are plain JSON files in `files/ai_sessions` and `files/agents` (AiSessionRepository.kt:51, AgentRepository.kt:35). No redaction on write.
- Inbox payloads (SMS, notifications) go to `files/spark_inbox` and the DB in plain text (AlwaysOnModule.kt:48).
- The diagnostics dump says it holds no flow content, but it writes flow names and event `detail` text (ReliabilityDump.kt:11-13 vs 47-49, 66).

### 3.13 Device nodes
- SMS send: one SMS per input item, no per-run cap, no confirmation, no number check (SmsSendNode.kt:~35-50, AndroidDeviceGateway.kt:85-102). `createForSubscriptionId(simSlot)` treats a slot index as a subscription id (line 89-91).
- Open link: any URI string goes to `ACTION_VIEW` (AndroidDeviceGateway.kt:109-118), no scheme allowlist, not wrapped in try/catch.
- Binary load accepts `content://` URIs from Step params (AndroidDeviceGateway.kt: loadBinary, `content://` branch).
- Unzip keeps up to 30 entries of up to 25 MB each in memory, and only strips a leading `../` from names (BinaryToolsNode.kt:224-236).
- Markdown to HTML puts link and image targets into attributes without quoting protection and without a scheme check (MarkdownLite.kt:68-74).

---

## 4 Issues found

Severity: P0 = feature broken or serious loss now. P1 = real security, data or release risk. P2 = polish, hardening, small bugs.

| ID | Sev | File:line | What | Why it matters | Fix idea |
|---|---|---|---|---|---|
| I-01 | P0 | NodeDsl.kt:35-36; WorkspaceResources.kt (TELEGRAM, BEARER_TOKEN, HEADER_AUTH); HttpNode.kt:177-186; TelegramNode.kt:46 | Field names written by the UI do not match what nodes read. `botToken`, `token`, `headerName`, `headerValue` are never read. `bearerToken()` only knows `accessToken` and `apiKey`. HTTP node reads `header`/`value`. | A user creates a Telegram, Bearer or Header-auth credential in the nice new sheet and every Step using it fails ("no bot token") or sends no auth. 3 of 8 types are dead. Premium-feel killer. | One mapping layer: give each `CredentialTypeInfo` a `secretKey` and `headerKey` and have `bearerToken()` and the HTTP node read through it. Or rename the UI keys. Add a unit test per type. |
| I-02 | P1 | KtorHttpGateway.kt:56-140, 222 | No SSRF or private-range guard in the gateway. HTTP, RSS, GraphQL, WebDAV, upload, connector, Telegram nodes can hit 127.0.0.1, LAN hosts, 169.254.169.254. Only AI calls use EndpointPolicy. | On a phone the risk is a malicious or AI-written or imported Flow, a webhook payload that fills `url`, or an agent with the HTTP tool, reaching home routers, local dev servers, other apps listening on localhost. | Move `EndpointPolicy` use into the gateway with a per-request mode (public only by default; allow LAN with a visible per-Step switch). Resolve DNS on the connect path with an OkHttp `Dns` wrapper that checks the result (also fixes rebinding). Check every redirect hop. |
| I-03 | P1 | KtorHttpGateway.kt:222; Providers.kt:68; ModelDiscovery.kt:30; StepExecutor.kt:230 | `followRedirects=false` and `failOnErrorStatus` in `HttpRequestSpec` are ignored by the gateway. Redirects are always followed. | The AI code and its comments promise "redirects refused". They are not. A redirect from a provider URL to another host can carry custom key headers (x-api-key, x-goog-api-key). Whether Ktor 2.3 strips `Authorization` cross-host is not verified. | Honour the flag: build the OkHttp client with `followRedirects(false)` and do redirects by hand (max 5, re-check policy each hop, drop auth headers on host change). |
| I-04 | P1 | HttpNode.kt:83-86; Repositories.kt:112-137; AiSessionRepository.kt:51 | Inline secrets (Token, Password, Credential id fields) are stored as plain text in Room `flowJson` and in `files/flows/*.json`. | The vault is encrypted, but a second plain store exists for the same kind of secret. Rooted device, adb backup of a debug build, or a file manager on a shared profile reads them. Misleads the Privacy Centre promise. | At save, move every `ParamKind.Secret` value into the vault as an auto-created credential and store only its id (or encrypt with CryptoBox). Drop or encrypt the `flows/` mirror. |
| I-05 | P1 | FlowRunner.kt:322-333; HttpNode.kt:216, 240, 245; JsonCodec.kt:255-262 | Run error, step error and run log text are saved unredacted. HTTP errors include the URL (first 90 chars, with query) and 300 chars of response body. | Servers often echo `Invalid API key: abc...`. APIs that take keys in the query put them in the URL. Telegram puts the bot token in the path. All land in Run history and in notifications. | Run every persisted/notified string through `StepDataRedactor` (and `Redactor` patterns). Feed it the real secret values resolved in this run (see I-06). Strip the query string in `shortUrl`. |
| I-06 | P1 | FlowEngine.kt:82; StepDataPolicy.kt:90-96, 129-139 | The step-data redactor is created with no known secret values, so the strongest layer (mask the exact credential strings anywhere) is unused. Pattern list misses Telegram bot tokens (`123:AA...`), Stripe `sk_live_` (underscore), Google `ya29.` tokens, Slack webhook URLs, and header-like keys `x-api-key`, `cookie`, `set-cookie`. | A node that returns the request body or an echo endpoint writes the live secret into stored Step data. | Collect secret values in `StepContext.credential()` per run, pass them to the redactor. Add the missing shapes. Add `x-api-key|cookie|session|sid` to key hints in both `Values.redact` and bundle hints. |
| I-07 | P1 | WorkspaceResources.kt OLLAMA; no network_security_config; app/build.gradle.kts:16 | No network security config and target SDK 34. Cleartext HTTP is blocked by Android for targetSdk 28+. | Ollama at `http://localhost:11434` or on the LAN, and any `http://` URL in the HTTP node, should fail with a "CLEARTEXT not permitted" error. Local AI is a headline feature. Not device-tested here. | Add a network security config that allows cleartext only for localhost and private ranges (domain-config cannot do CIDR, so use a base config with cleartext false plus `localhost`, `10.0.2.2` and a runtime switch via a custom OkHttp check for LAN). Document it. Needs device test. |
| I-08 | P1 | SmsSendNode.kt:35-50; AndroidDeviceGateway.kt:85-102 | SMS send has no per-run or per-day cap, no number validation, no premium-number guard, no approval. One item = one SMS. | A loop bug, a webhook storm or a prompt-injected agent can send hundreds of SMS and cost real money. Also a Play policy and fraud risk. | Hard cap per run (default 5) and per day (default 20) in the gateway, user-adjustable. Block short codes by default. Route through the same ApprovalPolicy when triggered by an agent. |
| I-09 | P1 | PermissionCentreScreen.kt:40-80; MainActivity.kt:215-250; AndroidDeviceGateway.kt:170-175, 315-325, 362, 400 | There is no way to grant Location, Contacts, Calendar, Bluetooth, Camera or SEND_SMS from the app. The Sms row requests RECEIVE_SMS only, but the send node checks SEND_SMS. The SMS error says "Settings, Privacy centre" but that screen has no grant. Status is re-read right after the request, before the system dialog answers. | Those Steps always fail with "not granted" unless the user digs into Android Settings. Bad first impression. Also no honest in-app disclosure for the sensitive ones, which Play requires. | Extend `KaivoPermission` with the missing ones, ask via `ActivityResultContracts.RequestMultiplePermissions`, refresh on result, and show a "grant now" button from the Step error. |
| I-10 | P1 | ComplexWidgets.kt:597; Repositories.kt:275-286 | The canvas credential picker lists every row, ignoring the slot's `accepts` type, and includes the internal `ai_provider_config` row. | A user can attach a Telegram token to an OpenAI Step, or choose "AI Assistant Config" as a key. With the HTTP node "credential" mode, any credential can be sent to any URL. n8n prevents this with typed credentials and domain allowlists. | Filter by `slot.accepts`. Hide `ai_provider_config` in the repo (`WHERE id != ...`) or move it to a separate settings table. Add a per-credential allowed-domains field (see section 5). |
| I-11 | P1 | app/build.gradle.kts:16; AndroidManifest.xml comment "targetSdk 35+" | Target SDK 34 while the manifest comments assume 35+. Google Play requires newer targets for new apps and updates (35 since Aug 2025; the Aug 2026 step was to 36, verify in Play Console policy page). | Cannot publish the Play flavour as it stands. Also FGS rules differ at 35 and 36 (comments in the manifest describe 35 behaviour that is not active at 34). | Raise compile and target SDK to the current Play requirement, retest foreground service start from boot, exact alarm, and notification listener rebinding on a real Android 15/16 device. |
| I-12 | P2 | CryptoBox.kt:25-40 | `secretKey()` has no lock. Two threads encrypting at first use can both generate a key under the same alias. The second replaces the first and the first ciphertext is lost. | Rare, but it destroys a credential silently on a fresh install when the AI screen and a Flow save at once. | Make `secretKey()` synchronized and cache the key object. |
| I-13 | P2 | CryptoBox.kt:42-59; Repositories.kt:259-262, 301-304 | All crypto failures become `null`. The edit sheet opens a credential whose decrypt failed with empty fields (`getFields(...).orEmpty()`, FlowListScreen.kt:374). No "this credential is broken" state. Keystore key lost (device restore, some OEM resets) looks the same as "empty". | User thinks the form is blank, retypes, loses nothing new. But a Flow run says "could not be unlocked" with no way to see which credential is broken from the list. | Return a sealed result (Ok, Missing key, Corrupt). Show a red "Re-enter" badge in the list. Log the exception class (not content). |
| I-14 | P2 | CryptoBox.kt:42-49 | No AAD, no StrongBox attempt, no `setUnlockedDeviceRequired`. A ciphertext is not bound to its credential id. | Copying one row's blob into another row works. Minor. Weaker than it could be for a "premium security" claim. | Use credential id + type as AAD. Try `setIsStrongBoxBacked(true)` with fallback. Consider `setUnlockedDeviceRequired(true)` only if background runs do not need the key while locked (they do, so skip). |
| I-15 | P2 | Repositories.kt:267-273 | Constructor silently deletes all `googleSheetsOAuth2` rows on every start. Dead legacy cleanup that can destroy user data without notice. | If the type is ever reused, silent loss. | Run once as a Room migration, then delete the code. |
| I-16 | P2 | Repositories.kt:289-296, 318; FlowListScreen.kt:224 | "Last modified" equals created time. Save resets `createdAt`. `usedInFlowCount` hard-coded 0. | The Details tab shows wrong data. "Used by N flows" is the single most useful field when deleting. | Add `updatedAt` column (migration 6 to 7). Keep `createdAt` on update. Compute usage by scanning flow params for the id. |
| I-17 | P2 | Repositories.kt:497-512, 253-257 | OAuth refresh and user Save both overwrite the whole blob from an earlier read. | A refresh finishing while the user edits can restore old fields or drop new ones. Small window. | Serialize writes per credential id with the same mutex; or write only changed keys. |
| I-18 | P2 | OAuth2TokenManager.kt:46-48 | Token URL only checked for `https://`. No host policy. No `invalid_grant` handling. Error text from the server is shown (limited to 180 chars, not redacted). | A revoked refresh token is retried every run. A custom token URL can hit private hosts. | Use the gateway guard (I-02). On `invalid_grant` mark the credential "needs reconnect" and pause the flow with the existing `CredentialMissing` state. |
| I-19 | P2 | CredentialEditSheet.kt:~145-165; FlowListScreen.kt:~383-392; AiProvider.kt:92 | Test Connection and the auto-test on open map every type to an AI provider. Telegram, Bearer, Header, Basic, apiKey and oauth2 get a false failure (or an OpenAI call with an empty key). | Honest-looking red banner on a good credential. Wrong message to the user. | Add a `testRequest` to each `CredentialTypeInfo` (Telegram `getMe`, generic: skip). Hide the button when no test exists. |
| I-20 | P2 | CredentialEditSheet.kt (secret field) | Secret text field has no `KeyboardOptions(keyboardType = Password)`, no autocorrect off. Settings screen does it right. | Keyboards may learn or suggest secrets. | Same options as `CredentialsScreen`. Set `FLAG_SECURE` on credential screens (or at least blur in Recents). |
| I-21 | P2 | PrivacyAndCredentials.kt (CredentialsScreen) | Second credentials UI that only knows `apiKey` and `oauth2`. Delete without confirmation. Shows internal row. Duplicate path to the same store. | Two ways to create credentials means two data shapes (`apiKey` generic vs typed). Confusing. | Make the Settings entry open the dashboard Credentials tab. Keep the OAuth form as a type in the new sheet. |
| I-22 | P2 | WebhookServer.kt:47-61 | App webhook secret in plain SharedPreferences. | Rooted-device only. Cheap to fix. | Store it in the vault (or encrypt with CryptoBox). Also allow per-Flow token rotation (today only all-at-once). |
| I-23 | P2 | AndroidManifest.xml:98-110 | BootReceiver is exported and lists `QUICKBOOT_POWERON`, which is not a protected broadcast. Any app can send it. | Forces a full reconcile (alarms, registrations, engine start). The Boot Spark itself is deduplicated by boot count (Reconciler.kt:132-136), so only battery and noise. | Remove QUICKBOOT or check `Intent` sender is the system (not possible reliably). Rate-limit reconcile by trigger. |
| I-24 | P2 | AndroidManifest.xml:42 | `uses-permission BIND_NOTIFICATION_LISTENER_SERVICE` is wrong (it belongs on the `<service android:permission>`, which is already there at line 91). | Lint noise, Play review confusion. | Remove the uses-permission line. |
| I-25 | P2 | AndroidManifest.xml:13 | ACCESS_BACKGROUND_LOCATION is declared in the main manifest, so also in the Play flavour. | Play requires a declaration form and a video. Declaring it without a feature using it risks rejection. | Move it to the sideload manifest, or add the feature (geofence Spark) and its disclosure. |
| I-26 | P2 | data_extraction_rules.xml | Exclusion list misses `ai_sessions/`, `agents/`, `binary/`, `user-files/`, `spark_inbox/`. `allowBackup=false` covers cloud backup. Device-to-device transfer behaviour with `allowBackup=false` plus extraction rules is not verified. | If transfer ever includes them, chat text and SMS payloads move in plain files. | Exclude all `files/` and `no_backup` explicitly in `device-transfer`. |
| I-27 | P2 | Values.kt:199-210; StepDataPolicy.kt:96 | Key hints are substrings: `token` hits `total_tokens`, `max_tokens`; `card` hits `discard`; `private` hits `isPrivate`; `otp` hits `footprint`. Masks any non-null value, including numbers. | AI usage numbers and ordinary fields show as `••••••` in stored Step data and the inspector. Users cannot debug. | Match on word boundaries or a suffix list (`_token`, `Token`, `apiKey`, `password`), and only mask strings. Keep the value-shape layer. |
| I-28 | P2 | StepExecutor.kt (ctx.credential) / ExprCredentialProvider | No per-credential domain allowlist. No "credential used by node X" check at run. | See I-10. | See section 5, row "domain allowlist". |
| I-29 | P2 | BinaryToolsNode.kt:224-236 | Unzip caps each entry (25 MB) and 30 entries but keeps them all in memory (up to 750 MB). Name sanitising only strips a leading `../` or `/`. | OOM crash on a zip bomb from a webhook. Name is only used as a label today, but a later File Step could write it. | Cap total uncompressed size (25 MB total), run names through `safeFileName`. |
| I-30 | P2 | MarkdownLite.kt:68-74 | Link and image URL go into `href`/`src` without quote escaping or scheme check. `"` is not escaped in the attribute. | Output HTML that is later sent by email or rendered somewhere can carry `javascript:` or an injected attribute. No WebView in the app, so low. | Escape quotes, allow only http(s), mailto, tel. |
| I-31 | P2 | AndroidDeviceGateway.kt:85-102, 109-118 | `createForSubscriptionId(simSlot)` uses a slot index as subscription id. `openUrlOrApp` has no scheme allowlist and no try/catch. Background `startActivity` is blocked on Android 10+ without a user-visible trigger. | Wrong SIM or crash on SIM 1. "Open link" Step does nothing when the engine runs the Flow in the background. | Map slot to subscription via `SubscriptionManager`. Wrap in try/catch and report. Use a notification action for background cases. |
| I-32 | P2 | proguard-rules.pro (quickjs section) | Dead rules for a library that is not a dependency. | Misleading, and a claim in docs that the Code Step uses QuickJS is wrong. | Delete the rules. |
| I-33 | P2 | ReliabilityDump.kt:11-13, 47-49 | Header claims "no Flow content" but names and event details are exported (shared via text intent). | A user posts it in a public bug report. | Hash names, or drop `detail` unless the user ticks "include names". |
| I-34 | P2 | KtorHttpGateway.kt:121 | Text bodies have no size limit. | A 500 MB response read into a String crashes the app (OOM kills the always-on engine too). | Stream with a cap (default 10 MB) and fail with a clear message. |
| I-35 | P2 | KtorHttpGateway.kt:233 | `retryOnConnectionFailure(true)` can resend a POST after a dropped connection. | Duplicate Telegram message or SMS-like side effects. | Disable for non-idempotent methods. |

Counts: P0 = 1, P1 = 10 (I-02 to I-11), P2 = 24.

### Things checked and found good (so nobody "fixes" them)
- Vault encryption at rest: AES-256-GCM in Keystore, random IV, fresh per write. Fine.
- Webhook listener: loopback default, constant-time token compare, no path probing, per-Flow tokens, signature verify, strict size and time limits. Better than most hobby servers.
- EndpointPolicy parsing: handles weird IP forms and IPv6 mapping. Only the DNS part is not wired.
- Flow export/import: redacts secrets, re-ids, disables triggers, caps size. Good.
- Agent safety: key objects with masked toString, redactor, untrusted-data fence, risk tiers, approval cards, unattended caps. Good. Do not rewrite.
- Code Step: safe by design (3.11).
- SMS receiver permission guard and flavour split for Play. Good.
- Debug-only DiagReceiver lives in src/debug and cannot ship in release.

---

## 5 Gap vs n8n

| n8n capability | KaivoFlow status | Effort | Value |
|---|---|---|---|
| Encrypted credential store, one blob per credential | exact (Keystore AES-GCM, hardware-backed key) | done | Core promise. Keep. |
| Key rotation / key id prefix | missing | S | Needed only with a backup format (below). Add a version byte to ciphertext now, cheap. |
| Typed errors for decrypt failure (no data, decrypt, bad JSON) | partial (one message) | S | Support cost. Users see which credential is broken. |
| Redact secrets when UI loads a credential, unredact on save | missing (UI shows real values behind a mask) | M | Medium on a single-user phone. High for screen sharing. Ship "tap to reveal with biometric". |
| Credential test request per type | partial (AI types only, wrong for others) | M | High. Fewer broken Flows. |
| Typed credentials limited to nodes that declare them | missing (picker ignores type) | S | High. Stops wrong-key mistakes. |
| Domain allowlist per credential (all, list, none) | missing | M | High and cheap. Defence for AI-generated Flows. n8n does it; do it better with a visible default for known providers. |
| SSRF guard with DNS, connect and redirect checks | partial (AI endpoints only, no DNS) | M | High. Required for any shared-flow or marketplace future. |
| OAuth2 authorization code with state, PKCE, callback | missing (paste refresh token only) | L | Very high for business. Non-technical users cannot paste refresh tokens. |
| OAuth refresh with persist, skew, per-credential lock | exact (OAuth2TokenManager) | done | Good base. Add invalid_grant state. |
| OAuth URL scheme validation | partial (https only for token URL) | S | Low. |
| Credential expressions (non-secret fields only) | exact | done | Good. |
| Credential pre-authentication hook (session token fetch) | missing | M | Medium. Needed for APIs that use login then token. |
| External secret stores | missing | L | Low for single user. Skip. Offer password-manager import instead. |
| Credential sharing, roles, projects | not applicable (single user) | none | Pointer only (@n8n/permissions). |
| Pre-run check "node cannot use this credential" | missing | S | Medium. Part of typed credentials. |
| Execution data redaction policies | partial (capture-time redaction exists, no per-flow policy, no UI) | M | Medium. Add "hide data for this Flow" switch. |
| Sandbox for code | exact in effect (own interpreter, tighter than n8n) | done | A selling point. |
| Webhook auth variants (header, basic, JWT, IP allowlist) | partial (token header, HMAC signature) | S | Medium. Add basic and IP allowlist for LAN mode. |
| Encrypted export of credentials | missing | M | High for phone migration (see section 6). |
| Safe Flow import (strip secrets, disable triggers) | exact | done | Good. |
| Instance-key mismatch guard | not applicable (Keystore key cannot be exported) | none | See backup idea. |

---

## 6 Where KaivoFlow can beat n8n

1. Hardware key, no server to hack. n8n's key sits in a file or env var next to the database. Here the key never leaves the secure chip. Say it plainly in the store listing, and make it true everywhere (fix I-04, I-05, I-06 first).
2. Biometric-gated reveal and run. Require fingerprint to reveal a secret or to export. Optionally require it for "Destructive" or SMS Steps. `BiometricPrompt` with a Keystore key `setUserAuthenticationRequired` for a second "high value" vault tier. n8n cannot do this.
3. Network Ledger. Every Run records the hosts it contacted, bytes sent, which credential went where. Show it in the Run view and in the Privacy Centre ("last 7 days: 3 hosts"). Add a per-Flow egress allowlist and a global "Lockdown" switch. This is the honest version of the Privacy Centre text and a strong sales point for privacy buyers. Cheap because there is one gateway.
4. Encrypted vault backup for phone migration. Export a `.kaivovault` file: PBKDF2 or Argon2 from a passphrase, AES-GCM, version byte, credential id and type as AAD. Import re-encrypts into the new phone's Keystore. n8n users lose everything if they lose the key; a phone upgrade is the most common event for this app. Strongly worth money.
5. OAuth with Android App Links or Custom Tabs plus PKCE, one tap "Connect Google". Preset providers (Google, Microsoft, Notion, Slack). No redirect server needed: the app itself is the callback. This beats n8n's need for a public URL.
6. Per-credential domain allowlist with sensible defaults. OpenAI key may only go to api.openai.com. Shown as a green "locked to api.openai.com" chip. Stops AI-written Flows from leaking keys through the HTTP node.
7. Spend and send caps as a product feature. "This Flow may send 5 SMS a day, spend 200 AI calls a day." A visible budget bar. n8n has no equivalent. Saves users from bill shock, which is the biggest fear with agents on a phone.
8. Screen privacy. `FLAG_SECURE` on credential and run screens, blurred Recents thumbnail, optional app lock. Easy on Android, impossible in n8n's web UI.
9. Sensitive source minimisation. Notification and SMS Sparks keep only the matched fields, delete the raw payload after the run, and show a "what is stored" line. Strong against the data-privacy reviewers of Play.
10. QR import for credentials from a desktop password manager (scan a QR that carries an encrypted payload). Good onboarding for a mobile-first product.

---

## 7 Recommended ordered tasks

Each is small. Do them in this order. "AT" = acceptance test.

1. Fix the credential field mapping (I-01).
   AT: unit test per built-in type creates a credential, resolves it, and the Telegram, HTTP (header, bearer, basic) and Slack nodes pick up the right value. A Telegram credential made in the sheet sends a message in a device test.
2. Add `ParamKind.Secret` migration to the vault (I-04). Delete or encrypt the `files/flows/` mirror.
   AT: after saving a Flow with an HTTP Bearer token, `grep` of Room DB and `files/` finds no token string. Flow still runs. Export still redacts.
3. Redact persisted run text (I-05, I-06).
   AT: a Step that fails with a response body containing the live key shows `••••••` in Run history and in the failure notification. URL query values are stripped in the log line. Unit test with Telegram `/bot123:AAA/` path.
4. Honour redirect and error flags in the gateway, and add a `Dns` guard plus manual redirect handling (I-02, I-03).
   AT: a test server that 302s to 127.0.0.1 is refused. A public name that resolves to 192.168.x.x is refused. `followRedirects=false` returns the 3xx. AI ModelDiscovery against a redirecting server gets a Redirect error.
5. Typed credential picker and hide the internal row (I-10).
   AT: the OpenAI Step lists only OpenAI-compatible credentials. `ai_provider_config` never appears anywhere.
6. Network security config and LAN switch for local models (I-07). Needs a device.
   AT: Ollama at `http://192.168.x.x:11434` works from the phone with the "allow LAN" switch on and is refused with it off. `http://example.com` in the HTTP node gives a clear "cleartext not allowed" message.
7. Permission Centre completion (I-09).
   AT: every permission a node needs can be granted from the app. The Location Step shows "Grant location" and works after granting. Status updates after the system dialog returns.
8. SMS and spend caps (I-08).
   AT: a Flow with 100 items sends at most 5 SMS and the Run shows a clear "limit reached" warning.
9. Test Connection per type (I-19) and fix Details tab data (I-16).
   AT: Telegram test calls `getMe`. Bearer shows no test button. Editing a credential keeps the created date and updates "Last modified". The usage count matches the flows that reference it.
10. Crypto robustness (I-12, I-13, I-14): synchronized key creation, sealed decrypt result, AAD, StrongBox try.
    AT: concurrent first-use test creates one key. A deliberately corrupted row shows a "Re-enter" badge. Old rows (no AAD) still decrypt via a version byte.
11. Target SDK and Play prep (I-11, I-24, I-25).
    AT: release build with the current required target SDK passes lint and Play pre-launch. Boot, alarm and notification listener tests pass on Android 15/16 devices.
12. Domain allowlist per credential and "locked to host" chips (section 5, I-28).
    AT: an OpenAI credential used in the HTTP node against another host is refused with a clear message. A new credential gets the default host list for its provider.
13. Backup rules and files cleanup (I-26, I-22, I-33).
    AT: `adb shell bmgr` and device-transfer dry run show no app data leaves. Webhook secret not readable in `shared_prefs`.
14. OAuth authorization-code flow with PKCE and App Links, with `invalid_grant` handling (I-18).
    AT: Connect Google in one tap on a device, refresh works after 1 hour, revoking the grant shows "Reconnect" and pauses the Flow.
15. Hardening batch (I-27, I-29, I-30, I-31, I-32, I-34, I-35): small fixes, each with a unit test.
16. Encrypted `.kaivovault` backup and restore (section 6 idea 4), then biometric tier, Network Ledger, spend caps UI.

---

## 8 Not verified / needs device test

- Cleartext blocking for `http://localhost` and LAN (I-07): based on Android platform rules for target SDK 28+. No device run. Check on API 26 (min) and API 34.
- Whether Ktor 2.3.12 removes `Authorization` on a cross-host redirect, and whether custom key headers are kept (I-03). Not read from the library source. Test with a local redirecting server.
- Keystore behaviour on OEM devices: key loss after factory reset or lock-screen change, StrongBox availability, speed of `getEntry` on each call (the key is fetched on every encrypt and decrypt).
- Device-to-device transfer with `allowBackup=false` plus `dataExtractionRules` (I-26): platform rules for API 31+ not confirmed.
- Foreground service start from boot, exact alarm permission and notification listener rebinding on Android 15/16 once the target SDK is raised.
- Play policy details: current target API level requirement date, background location declaration, SMS restricted-permission rules (assumed from general knowledge; check the live policy pages).
- `startActivity` from the engine in the background (OpenLink Step): expected to be blocked on Android 10+; not tested.
- Notification and SMS payload retention in `spark_inbox`: no purge job found in InboxCore.kt, but not traced through all callers.
- The old claim in docs/06 that credential Keystore encryption is "partial" is wrong (verified in code). The rest of docs/06 (test connection only for AI, no OAuth flow, no SSRF guard) is right.
- Tests: existing tests for EndpointPolicy, Redactor, webhook auth and step data exist (not run, not read). No test found for the credential-type to node field mapping, which is why I-01 slipped through.
- Expression sandbox timing (1 s default) is cooperative: a single very large library call (for example sorting 100,000 elements) is only checked between steps. Not measured on a low-end phone.
