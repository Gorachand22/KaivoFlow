# Audit 07: Storage, Data Layer, Import/Export, Scheduling, Background Reliability

Scope: `core/data`, `core/model`, `core/work`, `feature/flows/FlowTransfer.kt`, manifest backup rules.
Benchmark: n8n behaviour (read only to understand behaviour; nothing copied). Evidence format: `path:line`.
Paths are relative to `/home/claude/kaivoflow`.

---

## 1. Summary

The data layer is clean and small, and the import bundles are better hardened than most (size, depth, cycle and unique-id checks, secret redaction, id remapping, triggers disabled on import). The weak spots are all about durability and reliability, not features:

1. Any schema bump wipes the user's data (destructive Room fallback, no exported schema, only a stale v1 schema file).
2. The Android backup and device-transfer rules point at the wrong database file name, so nothing is backed up. There is also no user-facing whole-app backup.
3. The scheduler ignores `isActive`. Every app start, boot or time change re-arms schedule triggers for inactive flows, and event triggers fan out to inactive flows.
4. Daily/weekly/monthly schedules are a self-rescheduling one-shot chain with no catch-up and no persisted "next run" state. One killed process or a failed enqueue silently ends the schedule until the app is next opened.
5. Runs are persisted only once, after completion. Run history is never pruned (the prune method has zero callers). A killed worker leaves no record at all.
6. No flow schema version, no validation on save or load, no version history, no per-flow static data, no Local Table node, and `$env` leaks `System.getenv()`.

The model is a sound base (immutable Flow, hand-written codec, pure-Kotlin `ScheduleMath`). Roughly 10 focused tasks would take this layer from prototype to dependable (section 7).

---

## 2. How it works today

### 2.1 Room schema
- One DB `kaivoflow.db` (`core/data/.../DataModule.kt:21`), `version = 3`, `exportSchema = false` (`Database.kt:247-248`). Entities: `flows`, `workflow_groups`, `workflow_variables`, `runs`, `credentials`, `data_tables`, `data_table_rows` (`Database.kt:19-181`).
- **Flow storage**: one row per flow with the full definition as `flowJson` (steps, streams, groups, notes, vars, frozen outputs). Column copies exist only for list queries (`name`, `stepCount`, `isFavorite`, `groupId`, `lastRun*`). `isActive` is NOT a column; it lives only inside the JSON (`JsonCodec.kt:107`).
- **Runs**: row per run with `runJson` blob plus summary columns (`Database.kt:53-65`). Written once, at the end of the run (`FlowEngine.kt:162,182-192`). Step output is truncated to 20 items per step (`JsonCodec.kt:250`) but with no byte cap.
- **Binary**: bytes are never in JSON. `BinaryDataRef` handles (`Flow.kt:198`) point at `filesDir/binary/<uuid8>_<name>` (`AndroidDeviceGateway.kt:230-235`). Nothing ever deletes these files (the only `.delete()` in the codebase is the flow mirror, `Repositories.kt:118`).
- **Credentials**: AES-256-GCM key in Android Keystore (`CryptoBox.kt:21-59`), ciphertext in `credentials.cipherText`. Decrypt failure returns null and surfaces "re-enter it" (`Repositories.kt:219-224`).
- **Variables**: `workflow_variables(groupId nullable, key, value)`; global plus group scope, group overrides global (`Repositories.kt:558-566`). Merged into `flow.vars` at run time (`FlowEngine.kt:68-75`).
- **Data tables**: `data_tables(columnsJson)` and `data_table_rows(dataJson)` (`Database.kt:164-181`). Repository supports create, insert, delete, observe only (`Repositories.kt:277-287`).
- **Flow file mirror**: every save writes `filesDir/flows/<id>.json` (`Repositories.kt:105-111`). Nothing reads it back.
- **Migrations**: none. `fallbackToDestructiveMigration()` (`DataModule.kt:22`).
- **Encryption**: DB is plain SQLite; only credentials are encrypted.

### 2.2 Flow format and persistence
- Hand-written JSON codec (`JsonCodec.kt:48-225`), `ignoreUnknownKeys = true`. No `schemaVersion` field in the flow document. Bundles carry `format` + `version = 1` (`NativeFlowBundle.kt:32-33`), the stored flow does not.
- `decodeFlow` returns `null` on any exception (`JsonCodec.kt:225`). Repositories use `mapNotNull` / `?.let`, so a bad flow disappears from `all()` and `flow(id)` returns null (`Repositories.kt:79,83`).
- Validation (`validateGraph`: unique ids, endpoints exist, no cycles, size limits) exists only in the two bundle objects (`NativeFlowBundle.kt:265-313`, `FlowStructureBundle.kt:105-131`). `RoomFlowRepository.save` does no validation (`Repositories.kt:86-113`).
- Undo/redo is in-memory in the canvas (`CanvasViewModel.kt:85`). Saves are debounced 450 ms (`CanvasViewModel.kt:634-645`). Active/inactive is `Flow.isActive` ("Publish" in the UI, `FlowListScreen.kt:1707`), but there is no draft vs published copy: edits to an active flow are live immediately.

### 2.3 Scheduling and background
- `FlowScheduler.schedule` (`FlowScheduler.kt:59-101`): minutes/hourly use `PeriodicWorkRequest` (UPDATE policy); daily/weekly/monthly use a one-time `OneTimeWorkRequest` with `setInitialDelay`, unique name `schedule-<id>`, REPLACE. Constraints always include `setRequiresBatteryNotLow(true)`.
- Chain continuation: after each run, `FlowRunWorker` calls `flowScheduler.schedule(flow)` again (`FlowRunWorker.kt:66-74`).
- Re-arming: `KaivoApplication.onCreate` (`KaivoApplication.kt:40`), `BootReceiver` for BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIMEZONE_CHANGED, TIME_SET (`BootReceiver.kt:26-47`), and the flow list ViewModel (`FlowListScreen.kt:201`).
- Instant triggers (notification, SMS, share, battery, boot, geofence) go through `runNow` with expedited work (`FlowScheduler.kt:104-144`).
- Exact alarms: `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` are declared (`AndroidManifest.xml:33-34`), but no `AlarmManager` is used anywhere. The Settings check for exact alarms is therefore cosmetic.
- Reliability UI: `BatteryHealth` (battery optimisation, exact alarm, OEM autostart intents, Data Saver) is shown in Settings. It is a one-shot snapshot, not tied to actual schedule health.

### 2.4 Import / export
- `.kaivoflow` native bundle (`NativeFlowBundle`) and a params-stripped `kaivoflow.structure` bundle (`FlowStructureBundle`). UI in `FlowTransfer.kt`: single flow, via SAF pickers, preview dialog, one write on confirm (`FlowTransfer.kt:156-168`).
- Export redacts secret-looking keys (declared `ParamType.Secret` plus name hints) and records "rebind" hints. Import remaps all step/stream/flow ids, disables triggers, resets timestamps and `isFavorite`.

---

## 3. Findings

### P0 (data loss, wrong behaviour, or security-relevant)

| ID | Finding | Evidence | Fix |
|---|---|---|---|
| P0-1 | **Destructive migration fallback.** Any version bump deletes all flows, variables, data tables, run history and credential ciphertext. `exportSchema=false`, and `schemas/1.json` is stale (DB is v3), so migrations cannot even be tested. Flow JSON is mirrored to disk but nothing restores from it. | `DataModule.kt:22`, `Database.kt:247-248`, `core/data/schemas/.../1.json` | Remove the fallback. Set `exportSchema = true`, commit v3 schema as baseline, write explicit `Migration`s plus `MigrationTestHelper` tests. Add a pre-migration auto-snapshot (section 4.6). |
| P0-2 | **Backup rules reference a non-existent DB.** Rules include `kaivo.db`; the DB is `kaivoflow.db`. `allowBackup="false"` and `fullBackupContent="false"` contradict the rules. Net effect: no cloud backup and no device-to-device transfer of any user data. The excluded `kaivo_secrets.xml` and `kaivo_keys/` do not exist in code. | `data_extraction_rules.xml` vs `DataModule.kt:21`, `AndroidManifest.xml:54-56` | Decide the policy (section 4.7). Minimum: fix the file name, include DB and `binary/` selectively, set `allowBackup` consistently, exclude WorkManager DB and `-wal` handling. Add an in-app encrypted backup. |
| P0-3 | **Inactive flows get scheduled and triggered.** `schedule()` never checks `isActive`; `rescheduleAll()` calls it for every flow on every app start, boot, time and timezone change. `dispatchTrigger` also runs every flow with a matching trigger, active or not. The canvas cancels on save when inactive (`CanvasViewModel.kt:641-643`) but the next app launch undoes that. A user who "unpublished" a flow will see it run again after reboot. | `FlowScheduler.kt:53-65,133-144`, `KaivoApplication.kt:40` | Gate `schedule`, `syncGeofence`, `dispatchTrigger` and the webhook route on `flow.isActive && !trigger.disabled`. Add a test: inactive flow with schedule produces zero WorkManager requests. |
| P0-4 | **Credentials and secrets in plaintext in the flow mirror and variables.** The mirror `flows/<id>.json` stores full step params including inline API keys, unredacted. Variables UI describes "environment constants and secrets" but values are plaintext in SQLite and merged into `flow.vars`. `$env` exposes the entire `System.getenv()`. | `Repositories.kt:105-111`, `Database.kt:43-51`, `FlowListScreen.kt:1242`, `Expression.kt:110-115` | Delete the mirror or encrypt it (or redact via `NativeFlowBundle` rules). Add a "secret" flag on variables, store those in the vault. Replace `$env` with a curated read-only map (OS, timezone, app version, device locale). |
| P0-5 | **Shared file namespace collides with the flow mirror.** The Write/Read File node uses `filesDir/flows/` with only filename sanitising, so a file named `<flowId>.json` overwrites a flow's mirror, and flows can read each other's files and every flow definition. The code comment claims "its own directory", which is not implemented. | `AndroidDeviceGateway.kt:142-153,421-425` | Namespace per flow (`files/flowdata/<flowId>/`) and move the mirror (if kept) elsewhere. |

### P1 (reliability and correctness gaps with a clear workaround)

| ID | Finding | Evidence | Fix |
|---|---|---|---|
| P1-1 | **Schedule chain has no durable state and no catch-up.** Daily/weekly/monthly depend on the worker re-enqueueing itself after running. If the process is killed mid-run, the app is force-stopped, the OEM purges work, or the enqueue throws (swallowed by `runCatching`), the schedule stops until the user opens the app. No `lastScheduledFor`, no missed-run detection, no policy (skip/run-once/run-all). | `FlowScheduler.kt:88-99`, `FlowRunWorker.kt:66-74` | Persist per-flow schedule state (`nextDueAt`, `lastFiredAt`, `lastScheduledAt`). A single "reconciler" (runs on app start, boot, worker, time/timezone change) computes missed slots and applies a per-flow policy. Enqueue the next occurrence before running, not after. |
| P1-2 | **Worker cancels itself.** For periodic work the worker calls `schedule()` at the end, which does `cancelAllWorkByTag(schedule:<id>)` (cancelling the running work) and re-enqueues, resetting the period each run. Starts at app launch have the same effect, so frequent app opening can postpone a 15-minute job indefinitely. | `FlowScheduler.kt:67,194-196`, `FlowRunWorker.kt:67-73` | Do not reschedule periodic work from the worker. In `schedule()`, use `KEEP`/`UPDATE` and skip cancel if parameters are unchanged (store a hash of the schedule config in the input data or state table). |
| P1-3 | **`setRequiresBatteryNotLow(true)` on every schedule** silently defers a "daily at 07:00" flow on a phone at <=15% battery, with no surfaced reason. | `FlowScheduler.kt:72-75` | Make it a per-flow option (default off for time-of-day schedules) and surface "waiting for: battery/network" in the flow card from `WorkInfo`. |
| P1-4 | **Sub-15-minute intervals are silently clamped**, contradicting the class doc ("rejected with an honest message"). A user choosing 5 minutes gets 15. Hourly/minutes ignore the configured time-of-day alignment. | `ScheduleMath.kt:87-88`, `FlowScheduler.kt:79`, doc `FlowScheduler.kt:40-41` | Validate at edit time with a message. For genuinely exact or short intervals offer an opt-in foreground-service "live mode" (document battery cost). |
| P1-5 | **Exact alarms declared but unused; `USE_EXACT_ALARM` is a Play policy risk.** It is intended for alarm/calendar apps and can block review. The Settings check implies exact timing is available when WorkManager is used. | `AndroidManifest.xml:33-34`, `BatteryHealth.kt:67-82` | Either implement `AlarmManager.setExactAndAllowWhileIdle` for time-of-day schedules (with `SCHEDULE_EXACT_ALARM` only, user-granted, and a re-arm on `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`) which then triggers expedited work, or remove both permissions and the check. |
| P1-6 | **Run history is write-at-end and never pruned.** (a) A process kill or the 10-minute worker limit loses the run entirely; flow `executionTimeoutMs` default is 15 min, longer than the worker allows. (b) `pruneOlderThan` has no callers; `runs` grows without bound. (c) Per-step output has an item cap but no byte cap, so a step with 20 large items stores them all. (d) `observeRecent` decodes up to 100 full JSON blobs on every insert. | `FlowEngine.kt:162,182`, `Repositories.kt:159,192`, `JsonCodec.kt:250`, `Repositories.kt:167-168` | Insert a `Running` row at start, update at end, and mark stale `Running` rows as `Interrupted` on app start. Retention policy (count per flow + days + total MB) executed by a daily maintenance worker. Truncate outputs by bytes (e.g. 64 KB per step) and store a `truncated` flag. List queries should read columns only and decode JSON when a run is opened. |
| P1-7 | **No flow schema version; unknown fields are dropped despite the doc.** `JsonCodec` docs claim unknown fields from newer versions "are preserved", but the hand-written decoder reads known keys and `encodeFlow` writes only those, so a downgrade-then-save loses data. Opening a flow with a new step field in an older app discards it. | `JsonCodec.kt:32-39,139-225` | Add `schemaVersion` to the document and a chain of pure-Kotlin upgraders (`v1->v2`...). Keep an `extras: JsonObject` on Flow/Step/Stream round-tripped verbatim. Refuse to open (read-only) a document with a newer major version instead of silently rewriting. |
| P1-8 | **Corrupt or half-saved flow is invisible and unrecoverable.** `decodeFlow` null leaves the list row (from the entity columns) but the flow cannot be opened, exported or deleted via normal paths; `all()` silently skips it so its schedule is never armed. Decode is lenient where it matters little (missing id becomes "") and brittle where it matters (a non-primitive entry in `tags` or `stepIds` throws and nulls the whole flow). No validation on save. | `Repositories.kt:79-83`, `JsonCodec.kt:149,207`, `Repositories.kt:86-113` | `decodeFlow` returns a `Result` with a reason. Quarantine undecodable rows into a "Recovered/Broken flows" list with raw JSON export. Run `validateGraph` (shared) on save, and a lenient `repair()` on load (drop dangling streams, dedupe ids, null out missing `errorStepId`). Wrap save (row plus mirror/history) in one `@Transaction`. |
| P1-9 | **No per-flow persistent state ("static data").** Common automations ("last seen id", "last run timestamp", dedupe) have nowhere to keep state except the shared file node. No Local Table node exists even though the data table repository and UI do (`DataTable*` is referenced only in `feature/flows`, `core/data`, `core/model`, not in `core/nodes`). Repository lacks update, upsert, query, column edit, row cap. See section 5. | grep results; `Repositories.kt:277-287` | Section 5 design. |
| P1-10 | **Flow list does O(N x flow size) JSON decoding on every table change** only to read `isActive`; each run's `updateLastRun` re-emits the whole list. `rowCount` is queried per table inside a Flow map (N+1). | `Repositories.kt:57-73,295-308` | Add `isActive` (and `scheduleSummary`, `hasSchedule`) columns on `flows`; one `GROUP BY` for row counts. |
| P1-11 | **Binary files are never garbage-collected** and are excluded from nothing. Deleted flows, pruned runs and failed runs all leave `filesDir/binary/*` behind. No size budget. | `AndroidDeviceGateway.kt:230-235` | Reference-tracked store: `binary_files(id, runId, flowId, size, createdAt)` with TTL (default 7 days), delete on run prune, global quota with oldest-first eviction, "Storage" screen. |
| P1-12 | **Import is single-flow, no dedupe or conflict choice, no credential rebinding step, and no whole-workspace export.** The rebind list is text hints only; after import the user must hunt for each step. Imported flows reset `isActive` implicitly via disabled triggers but the flow's `isActive` flag itself is carried over from the file. | `FlowTransfer.kt:156-168`, `NativeFlowBundle.kt:190-223` | Force `isActive=false` on import. Add a rebind wizard (list unresolved steps, pick credential type-matched). Add a multi-flow workspace bundle (section 4.6). |
| P1-13 | **Variables have no uniqueness or validation.** Same key can exist twice in a scope; `resolveVariables` takes the last. No key format check (needs to be a valid expression identifier). `observeForGroup` isn't used for resolution; deleting a group leaves orphan rows and flows with dangling `groupId` (no foreign keys anywhere). | `Database.kt:43-51,127`, `Repositories.kt:558-566` | Unique index `(groupId, key)`, key regex, FKs with `ON DELETE` rules (flows.groupId SET NULL, variables CASCADE), `PRAGMA foreign_keys` on. |
| P1-14 | **Credentials row `createdAt` is overwritten on every edit** and surfaced as `updatedAt`, so the UI cannot show the true age; `usedInFlowCount` is hardcoded 0. | `Repositories.kt:258-266,236-243` | Add `updatedAt`; compute usage by scanning flow JSON (or a `credential_usage` index table). |

### P2 (hardening and polish)

| ID | Finding | Evidence | Fix |
|---|---|---|---|
| P2-1 | Number round-trip: every JSON number decodes to `Double`, so large integer ids (>2^53) lose precision and ints may stringify as `5.0` in downstream interpolation. The `str()` helper maps the literal string "null" to empty. | `JsonCodec.kt:353-356,361-362` | Preserve integral numbers as `Long` when the lexical form has no `.`/`e`. Use `isString` check before the "null" rule. |
| P2-2 | Default timezone hard-coded to `Asia/Kolkata` in the trigger spec and decoder fallback instead of the device zone. | `TriggerNodes.kt:103`, `JsonCodec.kt:153`, `FlowScheduler.kt:70` | Default to the device zone ("device" sentinel that follows travel) and store the IANA id explicitly otherwise. |
| P2-3 | Monthly day is clamped to 28, so "the 31st / last day of month" is impossible. Weekly with an empty list means daily. DST gap/overlap behaviour untested at the scheduler level (pure math uses zoned arithmetic, which is correct for gaps, but overlaps run once). | `ScheduleMath.kt:84`, `ScheduleMathTest` | Add "last day of month", add DST tests (spring-forward 02:30, fall-back 01:30), weekly validation message. |
| P2-4 | No DB integrity safeguards: no `PRAGMA integrity_check` at startup, no WAL checkpoint before backup, no `setJournalMode` decision, and DB is unencrypted at rest (run outputs may hold personal data). | `DataModule.kt:21-23` | Optional SQLCipher with a Keystore-wrapped key (cost: APK size, no encrypted backups through Auto Backup). At minimum document the threat model. |
| P2-5 | `triggerData` of `RunRecord` is never serialised, so a stored run cannot be re-run with the same trigger payload. | `Run.kt:23`, `JsonCodec.kt:227-258` | Persist (size-capped), enabling "Re-run with this input". |
| P2-6 | WorkManager Data payload for event triggers is capped at ~10 KB; `JsonItems.encode` of a large notification/SMS payload will throw inside `runNow`. | `FlowScheduler.kt:111-121` | Spill large payloads into a `pending_events` table and pass only the row id. This also gives crash-safe event delivery. |
| P2-7 | `BootReceiver` is `exported="true"` and the receiver accepts only matching actions, which is fine, but `goAsync` plus an unbounded coroutine scope can be killed after ~10 s on large flow sets. | `BootReceiver.kt:36-45`, manifest | Make the receiver enqueue a unique `ReconcileSchedulesWorker` (expedited) and return. |
| P2-8 | Settings `BatteryHealth.ok` for OEMs is a static guess (`ok = Stock or Other`); it can never turn green after the user fixes autostart, and the OEM component names are best-effort. | `BatteryHealth.kt:131` | Replace with evidence-based health (section 6): "did the last N scheduled fires happen within tolerance". |

---

## 4. Target design

### 4.1 Database
- Keep the "document per flow" model (a flow is always read whole; it matches n8n's own JSON-column approach) but add the columns the UI and scheduler need: `isActive`, `schemaVersion`, `revision` (int), `scheduleSummary`, `nextDueAt`, `lastFiredAt`, `hasSchedule`.
- New tables:
  - `flow_versions(flowId, revision, savedAt, label, flowJson, author=local)` for history (4.3).
  - `flow_state(flowId, scope, key, valueJson, updatedAt)` for static data (section 5).
  - `schedule_state(flowId, triggerStepId, configHash, nextDueAt, lastFiredAt, lastScheduledAt, missedPolicy, lastStatus)`.
  - `pending_events(id, flowId, kind, payloadJson, receivedAt, state)` for crash-safe event queueing and large payloads.
  - `binary_files(id, runId, flowId, path, size, sha256, createdAt, pinned)`.
  - `run_steps` optional later; at minimum split `runs` into a light header row and a `run_data(runId, json, bytes)` side table (the same split n8n uses between execution header and execution data), so lists never touch blobs.
- Turn on foreign keys and add the unique `(groupId,key)` variable index.
- `exportSchema = true`, schema JSONs committed, `MigrationTestHelper` tests per step, `autoMigrations` where possible. No destructive fallback in release builds. In debug builds keep it behind an explicit flag.

### 4.2 Flow format
- `schemaVersion` integer at the document root; `FlowMigrator.upgrade(json)` is pure Kotlin with golden-file tests (one fixture per historical version).
- `extras` map carried through decode/encode at flow, step, stream level.
- A single `FlowValidator` (extract the duplicated `validateGraph`) used by save, import, and engine start. Output: list of issues with severity (`error` blocks activation, `warning` shows in UI). Node-type unknown, missing credential, dangling stream, cycle, invalid cron/time.
- Corrupt rows quarantined (`flows_quarantine(id, rawJson, reason, at)`), never deleted.

### 4.3 Versions, draft vs published
- Active flow = **published revision**. Editing works on a draft. Concretely: `flows.flowJson` is the working draft; `flows.publishedRevision` points to a row in `flow_versions`. Schedule, event triggers and webhooks always execute the published revision; manual "Run" executes the draft. This fixes the current behaviour where each 450 ms autosave of an active flow changes live automation (`CanvasViewModel.kt:634-645`).
- Auto-snapshot on publish, on import and before restore; keep the last N (default 30) plus any labelled. "Restore version" creates a new draft. Diff view can be added later (step added/removed/params changed).
- Undo stays in memory; optionally persist the undo stack summary across process death (draft autosave already covers most of it).

### 4.4 Run storage and retention
- Insert `Running` header at start with `workerId`/attempt; update at finish; startup sweep sets stale `Running` to `Interrupted` with the last completed step id.
- Retention policy (global defaults, per-flow override): keep last K runs per flow (default 200), max age (default 30 days), always keep last failure per flow, byte budget for `run_data` (default 100 MB), oldest-first eviction. Run it from a daily unique periodic `MaintenanceWorker` that also does binary GC, WAL checkpoint, `PRAGMA optimize`, integrity check.
- Output capture: per step cap on items (20) and bytes (64 KB), with a `truncated` marker; binary payloads are references only (already true).
- Pinned/"keep forever" run flag for debugging.

### 4.5 Scheduling architecture
Principles: WorkManager is the delivery mechanism, a persisted `schedule_state` is the source of truth, a single idempotent **reconciler** repairs drift.

1. `ScheduleReconciler.reconcile(reason)` (a `@Singleton`, serialised by a Mutex; also exposed as a `ReconcileSchedulesWorker`):
   - For each flow with `isActive` and an enabled schedule trigger: compute the expected next fire from `ScheduleMath` using the stored last fire, compare `configHash` and `nextDueAt` with the live WorkManager state (`getWorkInfosForUniqueWork`), and (re)enqueue only when missing or changed (`ExistingWorkPolicy.KEEP`).
   - Detect **missed slots**: slots in `(lastFiredAt, now]` that did not fire. Apply per-flow `missedPolicy`: `skip`, `runOnceNow` (default for time-of-day), `runAll` (capped, e.g. 5), plus a max lateness (e.g. skip if >12 h late for "daily 07:00 report", but run for "daily backup").
   - Record each decision in a small `schedule_log` ring buffer (visible in the Reliability Center).
2. Triggers for reconcile: app start, boot, package replaced, timezone change, time change, exact-alarm permission change, notification of Doze exit (`ACTION_DEVICE_IDLE_MODE_CHANGED`), a daily periodic safety-net worker, and after every scheduled run.
3. Delivery:
   - Interval >= 15 min: periodic work (unchanged), but never cancelled and re-enqueued by the worker itself (P1-2).
   - Time-of-day: enqueue the **next** slot before running the current one (so a crash cannot break the chain); optionally add an `AlarmManager.setExactAndAllowWhileIdle` alarm that launches expedited work for users who granted exact alarms. WorkManager delivery remains the fallback, and a second fire for the same slot is de-duplicated by an idempotency key `(flowId, slotEpoch)` stored in `schedule_state`.
   - Opt-in "Always-on mode" foreground service (for < 15 min cadences, webhooks, SMS listening) with a persistent notification and clear battery warning.
4. Idempotency and run lease: key each scheduled run by `(flowId, slot)`; a worker that restarts after process death resumes by checking whether a run header with that key exists (prevents the double-run that WorkManager's retry-on-interruption can cause).
5. Worker limits: honour the platform 10-minute budget. For longer flows, request expedited/foreground promotion or checkpoint the run, and set `executionTimeoutMs` default below the limit.
6. Constraints become per-flow options with sane defaults (network required only if the flow declares network steps; battery-not-low off by default).
7. Time handling: store the IANA zone per schedule; default "follow device". DST: spring-forward gap fires at the next valid instant; fall-back overlap fires once (first occurrence). Add tests.

### 4.6 Backup, restore, new phone
- **Workspace backup file** `.kaivobackup` (zip, versioned manifest): flows (all revisions optional), groups, variables, data tables and rows, flow_state, schedule settings, app settings, optional run history and binary files, plus a manifest with `appVersion`, `dbVersion`, `createdAt`, counts, SHA-256 per entry.
- **Credentials**: Keystore keys cannot leave the device, so the vault is only portable via an explicit passphrase export. Wrap the decrypted credential map with a key derived from a user passphrase (Argon2id if available, else PBKDF2-HMAC-SHA256 600k+; AES-256-GCM, random salt/nonce, versioned header). Import re-encrypts under the new device's Keystore key. Without a passphrase, the backup includes credential labels and types only, and the restore wizard shows "re-enter these N credentials".
- **Restore wizard**: preview counts, choose merge vs replace, conflict policy per flow (skip / replace / keep both with new id), all schedules restored **disabled** until reviewed, then a "Activate N flows" step that runs the reconciler and the Reliability Center check on the new phone (permissions and OEM settings do not transfer).
- **Auto-snapshots**: before any DB migration, before restore, and a weekly rolling local snapshot (keep 3) in app storage; user-selectable SAF folder for auto-export (Drive/SD/NAS via DocumentsProvider), runnable by the maintenance worker. This also replaces the broken Auto Backup rules: if Auto Backup is wanted, back up only the snapshot file (a single, consistent, WAL-free artifact), not the live DB.
- **Phone-to-phone**: show a QR/"share file" flow; Android device-transfer will also carry `filesDir/backups/` if included in `dataExtractionRules` (fix the file name and domain paths).

### 4.7 Storage screen
Total size by area (flows, runs, tables, binaries, backups), per-flow run storage, retention controls, "Clear binaries older than...", "Vacuum", last integrity check result.

---

## 5. Variables, static data and data tables: comparison and design

| Capability | n8n (behaviour) | KaivoFlow today | Target |
|---|---|---|---|
| Global variables | Instance-level key/value strings, read as a built-in variable object in expressions; project-scoped in newer versions | Global + group-scoped strings, `$vars`, group overrides global (`Repositories.kt:558-566`) | Keep. Add types (string/number/bool/JSON), `secret` flag (vault-backed, masked, not exportable), unique key index, per-flow override (existing `flow.vars` already wins, `FlowEngine.kt:71`), and "used by N flows" |
| Environment | Process env vars, restricted | `$env` = full `System.getenv()` + a few constants (`Expression.kt:110-115`) | Curated device/app facts only |
| Per-flow static data | A per-workflow persistent object, writable from code/trigger nodes; saved only after production runs; global and per-node scope | None; only the shared File node (P0-5) | `flow_state` table + `$state` expression scope (read) and **Set State / Get State / Increment / Append-capped** nodes (write). Key/value JSON, per flow (and optional per-node namespace), size cap (e.g. 256 KB per flow), updated atomically at run end for Production runs only (Test runs read a copy and write nothing unless the user enables "persist in test"). Transaction with the run header, so a failed run does not commit state unless the step is marked "commit even on failure". |
| Dedup ("seen items") | Built-in remove-duplicates and dedup storage keyed by flow/node | None | `seen_items(flowId, namespace, keyHash, seenAt)` with TTL/max entries, exposed as "Remove Duplicates (across runs)" node. This covers "last seen id" for RSS/notification flows without hand-rolled state |
| Data tables | Project-scoped typed tables, row insert/update/upsert/get/delete nodes, size limits, import/export CSV | Entities + create/insert/delete only; no node; no update/query/column edit (`Repositories.kt:277-287`); rows are opaque JSON | **Local Table node** with operations: Get rows (filter, sort, limit), Insert, Update by filter, Upsert on key column, Delete, Count. Typed columns enforced on write (string/number/bool/date/json), optional unique column, default values. Row cap (e.g. 100k) and per-table byte cap with a clear error. CSV import/export. Filter pushdown: for indexed columns, add a `data_table_cells(tableId,rowId,colId,textValue,numValue)` side table or SQLite generated columns on `json_extract` so filtering is a SQL query, not a full scan. Tables accessible by name in expressions read-only (`$table("orders").first(...)` can come later). Migration-safe: column ids stable, rename = metadata only |

Rules shared by all three: writes happen via repositories inside a transaction; every store has a size budget surfaced in the Storage screen; all of it is included in the workspace backup; none of it is exported inside a single-flow bundle unless the user ticks "include data".

---

## 6. Reliability Center (user-facing)

A single screen, evidence-based, with a traffic light per flow and per device.

**Device checks** (each with a "Fix" deep link and "Test it" button):
1. Notifications permission (Android 13+) and channels not muted.
2. Battery optimisation exemption (`isIgnoringBatteryOptimizations`) and App Standby bucket (`UsageStatsManager.getAppStandbyBucket`: warn at Restricted/Rare).
3. Background restriction (`ActivityManager.isBackgroundRestricted`) and Data Saver / unrestricted data.
4. Exact alarms (only if implemented): `canScheduleExactAlarms`, plus handling the permission-changed broadcast.
5. OEM autostart and background-launch helpers (Xiaomi, Oppo/Realme/OnePlus, Vivo, Huawei, Samsung sleeping apps), linking to dontkillmyapp-style instructions. Treat these as "unverifiable", never as green.
6. Boot receiver ran since last reboot (store a timestamp; if the device booted and no reconcile happened, warn).
7. Timezone / auto-time settings (`Settings.Global.AUTO_TIME_ZONE`), and a "device time looks wrong" warning.
8. Required runtime permissions per active flow (SMS, location, contacts, calendar), listing which flow needs which.
9. Storage: free space, DB size, binaries size, last integrity check, last backup age.
10. WorkManager health: queue present for every active scheduled flow (`getWorkInfos`), count of `BLOCKED/ENQUEUED` older than expected, constraint reasons.

**Flow checks** (from `schedule_state` and `schedule_log`):
- Next planned run, last fired, last on-time/late/missed history for the last 10 slots with measured lateness.
- "Reliability score": on-time percentage over the last 14 days; "Doze-delayed" label when median lateness exceeds a threshold.
- Self-test: schedule a one-minute test run and report the actual delay; run a "simulate reboot" reconcile.
- Missed-run banner on app open with the catch-up policy applied and an option to run now.

Replace the static `ok = Stock or Other` in `BatteryHealth.kt:131` with these measured signals.

---

## 7. n8n import feasibility

Adoption value is high: n8n JSON is the de facto exchange format, and a converter can be pure Kotlin (no network, no code execution), reusing the existing preview, remap and redaction pipeline.

### 7.1 Structure mapping (high feasibility)
| n8n field (workflow JSON) | KaivoFlow target | Notes |
|---|---|---|
| `name` | `Flow.name` | direct; truncate to 160 |
| `nodes[]` each with `id`, `name`, `type`, `typeVersion`, `position [x,y]`, `parameters`, `credentials`, `disabled`, `notes`, `retryOnFail/maxTries/waitBetweenTries`, `continueOnFail`/`onError`, `executeOnce`, `alwaysOutputData` | `StepNode(id,type,name,x,y,params,disabled,notes,retry,onError)` | Map `position` to x/y (scale factor), `disabled`, `notes`, retry fields to `RetryPolicy`, `onError` (`stopWorkflow`, `continueRegularOutput`, `continueErrorOutput`) to `OnErrorPolicy.Stop/Continue/ErrorFlow`. Ids are remapped anyway (existing `freshIds`). Names are unique in n8n; keep as is |
| `connections` keyed by **source node name** -> output type (`main`) -> list per output index -> targets `{node, type, index}` | `StreamEdge(from, to, fromPort, toPort)` | Resolve names to ids, `main` output index becomes `fromPort`, target `index` becomes `toPort`. Non-`main` connection types (AI sub-node ports such as `ai_languageModel`, `ai_tool`) have no equivalent: report and drop |
| `pinData` | `StepNode.frozenOutput` | Items are `{json:{...}}`; map straight to `FlowData`. Good fit |
| `settings` (`executionOrder`, `timezone`, `errorWorkflow`, `saveDataSuccessExecution`, `executionTimeout`) | `FlowSettings` | timezone, timeout (seconds -> ms), save flags map directly; `errorWorkflow` is a flow id: warn |
| `active` | always import `isActive=false` | |
| `tags`, `meta`, `versionId`, `id` | `Flow.tags` (names only), ignored | |
| `staticData` | `flow_state` (if user opts in) | |
| `credentials` on nodes (id + display name only, no secrets in exports) | rebind list | exports contain no secret values, which matches the existing rebind UX |

### 7.2 Node-type mapping (the real work)
- Build a declarative **type map** `n8n type -> Kaivo spec id` plus per-parameter translators, kept as data (`assets/n8n-map/*.json`) so it can grow without releases. Mapping table authored from behaviour, never from copied source.
- Likely 1:1 or close (by name): Manual/Schedule/Webhook triggers, Set (Edit Fields), If, Switch, Merge, Split in Batches / Loop, Code (JavaScript), HTTP Request, Wait, No Operation, Sticky Note (to `StickyNote`), Aggregate, Filter, Sort, Limit, Remove Duplicates, Date & Time, Crypto, Convert to/from File (CSV/XML/HTML/Markdown), Execute Workflow (sub-flow), Respond to Webhook, Stop and Error, Data Table (once the Local Table node exists).
- Service nodes (Gmail, Slack, Google Sheets, Notion, Telegram, OpenAI, ...): map where a Kaivo node exists; otherwise convert to a generic **HTTP Request** step only when a safe template is known, else import as a disabled **"Unsupported: n8n.<type>"** placeholder preserving parameters, so the graph shape survives and the user can replace it.
- Desktop/server-only nodes (Execute Command, Read/Write Files from Disk, SSH, Local File Trigger, Cron-based servers, community nodes, LangChain sub-graphs): placeholder with an explanation.
- `typeVersion` matters: parameter names and defaults differ across versions. The map should be keyed by `(type, versionRange)`.

### 7.3 Expressions (medium risk, biggest compatibility surface)
- n8n parameters are strings starting with `=` containing `{{ ... }}` JS. Common built-ins: `$json`, `$input`, `$node["Name"].json`, `$('Name').item.json`, `$items()`, `$now`, `$today`, `$workflow`, `$execution`, `$vars`, `$env`, `$itemIndex`, Luxon `DateTime`, helper functions.
- KaivoFlow exposes `$json, $items, $item, $index, $vars, $env, $now, $today, $run, $step` (`Expression.kt:105-127`). A translation pass can rewrite the high-frequency forms: `={{` prefix removal, `$('X').item.json.a` and `$node["X"].json.a` to `$step.X...` (`$step` is keyed by node name), `$input.item.json` to `$json`, `$execution.id` to `$run.id`, `$workflow.name`.
- Provide `$node`, `$input`, `$` function aliases and a Luxon-compatible `DateTime` subset in the QuickJS prelude rather than rewriting everything; this keeps imported expressions unchanged and maximises success. Anything not recognised is kept verbatim and flagged "needs review" with the node highlighted, never silently dropped.
- Code nodes: JavaScript bodies using `$input.all()`, `items`, `return [{json:{}}]` conventions can run if the prelude supports those; Python code nodes cannot, flag them.

### 7.4 Semantics gaps to flag in the preview
- Execution order modes (`v0` vs `v1`), item-pairing (`pairedItem`) semantics, "execute once", "always output data", multiple outputs on error, binary property naming and `binary` data shape (Kaivo handles are URI references), webhook paths/URLs (no public server; LAN/foreground only), schedule via cron expression (Kaivo has a frequency form; add a cron-to-schedule converter for common cases and a "cron expression" mode), polling triggers (n8n's poller state vs Kaivo's WorkManager).
- Show an **import report**: N nodes mapped, M placeholders, K expressions needing review, credentials to rebind, features dropped, with a coverage percentage. Imported flows start inactive.

### 7.5 Effort and risks
- Phase A (structure, connections, pinData, settings, placeholders, report): about 1 week, pure JVM with fixture tests built from hand-written sample workflows (do not vendor n8n templates without checking their license; write own fixtures).
- Phase B (top 25 node types plus expression rewriter and prelude aliases): 2 to 3 weeks.
- Phase C (cron conversion, Luxon subset, community-maintained data-driven map): ongoing.
- Legal: implement from the documented, publicly observable JSON shape and behaviour only. Do not embed n8n source, node descriptions or text. Ship the map as independently authored data.
- Security: import must stay sandboxed (size/depth caps already exist in `NativeFlowBundle`; reuse `checkDepth`, `MAX_*`), triggers disabled, no code executed at parse time, expression text treated as data.

---

## 8. Suggested phase tasks

**Phase 0 (do first, small, high value)**
1. Gate schedule, geofence, event dispatch and webhooks on `isActive && !disabled` + tests (P0-3).
2. Replace `fallbackToDestructiveMigration`, enable `exportSchema`, commit v3 baseline schema, add `MigrationTestHelper` test scaffold (P0-1).
3. Fix `data_extraction_rules.xml` file name and `allowBackup` conflict; decide policy (P0-2).
4. Namespace the File node per flow; move or remove the plaintext mirror (P0-4, P0-5).
5. Curate `$env`; add `secret` flag on variables (P0-4).
6. Call `pruneOlderThan` from a maintenance path with a default retention (stopgap for P1-6b).

**Phase 1 (reliability core)**
7. `schedule_state` table + `ScheduleReconciler` + `ReconcileSchedulesWorker`; enqueue-next-before-run; stop worker self-cancel; per-flow constraints; missed-run policy (P1-1..P1-4).
8. Run header at start, stale-run sweep, byte-capped outputs, retention worker, binary GC with `binary_files` table (P1-6, P1-11).
9. Add `isActive`/summary columns, light run list queries, FK + unique indexes (P1-10, P1-13).
10. Decide on exact alarms: implement or drop permissions (P1-5).

**Phase 2 (format safety and history)**
11. `schemaVersion`, `FlowMigrator`, `extras` round-trip, shared `FlowValidator`, quarantine list (P1-7, P1-8).
12. `flow_versions` with draft vs published execution model, restore-version UI (4.3).

**Phase 3 (data features)**
13. `flow_state` + Get/Set State nodes + `$state` scope; `seen_items` dedup node (P1-9).
14. Local Table node with full CRUD, typed columns, caps, CSV in/out, indexed filtering (section 5).

**Phase 4 (backup and migration)**
15. `.kaivobackup` workspace export/import with restore wizard and passphrase credential export; auto-snapshots before migration/restore; scheduled export to a SAF folder (4.6).
16. Reliability Center with measured signals, self-test and missed-run banner (section 6).

**Phase 5 (adoption)**
17. n8n import: structure/connections/pinData/settings + placeholders + report (7.1), then node-type map and expression prelude aliases (7.2, 7.3), then cron converter.

**Minor cleanup**: P2-1 number fidelity, P2-2 timezone default, P2-3 month-end and DST tests, P2-5 persist `triggerData`, P2-6 spill large event payloads, P2-7 receiver delegating to a worker, P1-14 credential timestamps and usage counts.
