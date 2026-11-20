# Wave 1 status, lane W1-C (data, credentials, network)

Scope: core/data, ai data in feature/flows/ai (AiProvider, AiSessionRepository, ModelDiscoveryService), WorkspaceResources and
CredentialEditSheet, core/network. Tests ran offline on the JVM (`/home/claude/scratch-w1-c/run.sh`). Android, Room (KSP),
Hilt, Ktor and Compose code could not be compiled here; those parts are listed as reasoned-only.

## Done

| Task | State | What |
|---|---|---|
| 1 Credential registry (I-01, I-10) | done | `core/model/CredentialRegistry.kt`: one type list (kind, spellings, secret fields), `CredentialFields` (bearer, bot token, header, basic; old field names still read), Step slot table (`acceptedTypes`, `pickable`). HTTP, connector (and so Poll, GraphQL-style Steps that share it) and Telegram read through it. Repository hides `ai_provider_config` and legacy rows from every list; assistant provider words (`openai`, `claude`, `ollama`, `groq`) are saved and migrated as registry types. Edit sheet falls back to a generic form, not OpenAI. |
| 2 Keys only in the vault (DB-18) | done | Plaintext `apiKey` in the pointer row moves into a named vault credential once (idempotent, crash-safe order, flagged in new `vault_meta`); googleSheetsOAuth2 delete is now a flagged one-time step, the constructor no longer deletes. Chat sessions are redacted with vault values before they are written. |
| 3 Gateway (I-02, I-03, I-07) | done, reasoned-only wiring | `NetworkGuard`, `RedirectPolicy`, `GuardedDns`, `PrefsHostAllowList`; gateway follows redirects by hand (max 5, each hop checked, credentials dropped on origin change, no https to http), honours `followRedirects=false`, no automatic retry, DNS answers re-checked at connect. Saving an Ollama credential allows its loopback or LAN host. Network security config added (see decision below). targetSdk NOT changed (34). |
| 4 Persistent memory | done | `agent_memory` table, `RoomMemoryStore` implements the unchanged core/ai `MemoryStore`: per-agent scope, hashed keys, redaction, caps (200 messages, 128 KB per key, 100 keys per agent, 8 MB total), 30-day idle expiry on read and sweep, LRU eviction. `AgentMemoryFactory.forAgent(id)` (Hilt singleton) runs the sweep once per process. |
| 5 Usage ledger | done | `ai_usage` + `ai_usage_rollup` (day and month, scopes all, agent, provider), `UsageLedger`, `AiUsageSink`, `BudgetLimits` and `check()`. Cost is integer micro-USD (`UsageRecord.usdToMicros`). |
| 6 Assistant data | partial | Sessions: atomic write (temp, fsync, rename), message/size/session-count caps, redaction. Event log: `ai_event_log` + `AiEventLog` with replay, redaction, caps; `AiSessionRepository.appendEvent/replayEvents` exposed. NOT done: nothing in the view model calls `appendEvent` yet (view model is outside this lane); sessions stay JSON files, only events are in Room. |
| 7 Room migration | done | Version 7, `MIGRATION_6_7`, `schemas/.../7.json`, JVM test comparing the migration SQL with 7.json and the unchanged 6.json tables, plus an executed-SQL run on real SQLite (below). |
| 8 Redaction hooks (DB-11, I-05, I-06) | done at the data layer | `RedactingRunRepository` masks Run and Step error text; `RedactingStepDataStore` masks error message/details/HTTP body and items with the real vault values; `SecretMasker` adds Telegram, Stripe, `ya29.`, Slack webhook and key-header shapes. Both wrap the Hilt providers. |

## Verification
- `run.sh` compiles model, engine, nodes, expressions, ai, data and the network guard and runs the suites of data, the new credential tests and the guard tests.
- Executed SQL: `python3 -I /home/claude/scratch-w1-c/run_migration_sql.py` creates the schema-6 tables from 6.json on SQLite, seeds rows, runs the real statements from Migrations.kt twice, then compares columns, keys and indices with 7.json. A JVM test (`MigrationV7Test`) runs the same on a JDBC SQLite driver when one is on the classpath and skips otherwise.

## Reasoned-only (could not compile or run here)
Room KSP output and the DAO queries, Hilt modules (`DataModule`, `NetworkModule`), `KtorHttpGateway` changes (Ktor/OkHttp API: `config { dns(...) }`, `followRedirects(false)`), `GuardedDns`, `PrefsHostAllowList`, `FileAiSessionRepository`, `CredentialEditSheet` one-line change, `network_security_config.xml` and the manifest attribute. `7.json` was generated from the migration SQL by script, its `identityHash` is provisional: the next real build (`kspDebugKotlin`) rewrites the file; commit that output.

## Decision: cleartext
Android cannot name LAN address ranges in a network security config, and a LAN Ollama is reached by a typed IP. The config therefore permits cleartext at the platform layer and `core:network` enforces the rule (https for public hosts, http only for hosts the person allowed, never link-local or metadata). The app has no WebView and one HTTP client. If a second HTTP client is ever added it must use the gateway or this decision must be revisited.

## Not done or needs another lane
- Canvas credential picker (`ComplexWidgets.kt`, W1 canvas owner): call `CredentialRegistry.pickable(refs, stepId)` or `pickableByKind`. The repository already hides internal rows; type filtering by slot is still missing in the picker.
- Inline secrets in Flow JSON (HttpNode authToken and similar, I-04) and the `params["apiKey"]` fallback in AiClient: not in this lane.
- `FlowEngine` (core/work) still builds `StepDataRedactor()` empty; the wrapper above covers storage, not in-flight logs or notifications.
- `FlowListScreen.testCredentialConnection` maps every non-AI type to OpenAI.
- View models should write `appendEvent`, register `AiUsageSink` records from the agent loop, and use `AgentMemoryFactory`; W1-A needs no interface change (`MemoryStore` unchanged).
- `CryptoBox` has no associated data, so a ciphertext copied between rows still decrypts; changing it needs a versioned format and a migration.
- DB-30 (run index), pre-migration DB backup copy (DB-13), androidTest `MigrationTestHelper`: not done.
