# 02 - Database and storage audit (KaivoFlow vs n8n 2.48.6)

Audit date: 2026-11-20. Branch: claude/phase-4-base. Read-only. No source file was changed.
Scope owner: database and storage (core/data, run history, step data, pins, backup/export, file storage, AI and Agent storage, settings).

Path shorthand used below:
- `D/` = `core/data/src/main/kotlin/com/kaivoflow/core/data/`
- `W/` = `core/work/src/main/kotlin/com/kaivoflow/core/work/`
- `E/` = `core/engine/src/main/kotlin/com/kaivoflow/core/engine/`
- `CV` = `feature/canvas/src/main/kotlin/com/kaivoflow/feature/canvas/CanvasViewModel.kt`
- `FL` = `feature/flows/src/main/kotlin/com/kaivoflow/feature/flows/`

---------------------------------------------------------------------------------------------------

## 1. Scope and files read

KaivoFlow, read completely:
- `D/Database.kt`, `D/Migrations.kt`, `D/DataModule.kt`, `D/Repositories.kt`, `D/EngineEntities.kt`, `D/EngineDaos.kt`, `D/EngineRepositories.kt`
- `D/StepDataEntities.kt`, `D/StepDataDao.kt`, `D/StepDataStores.kt`, `D/StepDataCodec.kt`, `D/RoomStepDataBindings.kt`
- `D/JsonCodec.kt`, `D/NativeFlowBundle.kt`, `D/FlowStructureBundle.kt`, `D/CryptoBox.kt`, `D/LifecycleLogic.kt`, `D/LifecycleMapping.kt`
- Exported schemas `core/data/schemas/.../1.json` and `6.json` (compared to the migration SQL by script)
- All 12 test files in `core/data/src/test` (names and what they cover; not run)
- `E/RunRetention.kt`, `E/stepdata/StepDataPolicy.kt`, `StepDataStore.kt`, `StepExecutionRecorder.kt`, `Pins.kt` (parser part)
- `W/FlowEngine.kt` (persist, prune, recover), `W/alwayson/LiveFlows.kt`, `ActivationManager.kt`, `RunHost.kt`, `Reconciler.kt` (retention part), `EngineSupport.kt`, `W/WebhookServer.kt` (token store), `W/AndroidDeviceGateway.kt` (binary and file part)
- `core/nodes/.../device/UserFileStore.kt`, `data/storage/TableNode.kt`, `data/items/DedupeNode.kt` (state part)
- `E/ActivationCoordinator.kt` (publish and revision part)
- UI users of storage: `CV` (load, autosave, variables), `FL FlowListScreen.kt` (delete, groups, variables, data tables), `FL FlowTransfer.kt` (export and import)
- Antigravity storage: `FL ai/AiSessionRepository.kt`, `FL ai/agent/AgentRepository.kt`, `CustomAgent.kt`, `AgentBuilderViewModel.kt` (session append)
- `app/src/main/AndroidManifest.xml` (backup flags), `app/src/main/res/xml/data_extraction_rules.xml`, `KaivoApplication.kt`

n8n, read for behaviour only (pointers, nothing copied):
- `packages/@n8n/db/src/entities/` (execution-entity, execution-data, workflow-history, processed-data, poller-state, variables, binary-data-file)
- `packages/@n8n/db/src/repositories/execution.repository.ts` (soft delete rule), `workflow-history.repository.ts`
- `packages/@n8n/config/src/configs/executions.config.ts`, `workflow-history.config.ts`, `workflow-history-compaction.config.ts`
- `packages/cli/src/services/pruning/*` (executions pruning, history pruning, compaction)
- `packages/cli/src/executions/execution-recovery.service.ts`, `packages/cli/src/workflows/workflow-history/*`
- `packages/core/src/binary-data/*` (config, blob manager)
- `packages/cli/src/commands/import|export`, `packages/modules/data-table/backend/src/*`

Method notes. I could not build or run anything on a device. All claims are from reading code. The one exception is a script that compared the hand-written migration SQL with Room's exported `6.json` (result in 3.2). Every claim in section 4 has a file:line pointer. Section 8 lists what still needs a device.

A correction to the older docs (docs/00..10): they say nothing reliable about storage. This audit found that run history, step data, pins and publish revisions are real and fairly well built. The problems are at the edges: retention, deletion, backup, and a few UI-to-storage bugs.

---------------------------------------------------------------------------------------------------

## 2. How n8n does it (architecture in my own words)

Database and migrations
1. One relational schema in TypeORM entities. Runs on SQLite (default) or Postgres. Entities live in `@n8n/db`.
2. Migrations are numbered, timestamped classes, one list per database engine plus a common list. Each has `up` and usually `down`. A registry test checks the list is complete and in order. They run automatically at start.
3. IDs are strings for workflows and numeric (stringified) for executions. Timestamps are real date columns.

Execution storage
4. An execution is split in two rows. The metadata row has workflow id, mode, status, start and stop time, `deletedAt`, a unique dedup key. The data row holds the big serialized run data plus a full copy of the workflow as it was at run time and the workflow version id. They are 1:1 with cascade delete. Listing never touches the big blob.
5. Many indexes on the metadata row: by workflow, by (workflow, id), by wait time, by finished, and a partial index for (workflow, status, id) where not soft-deleted.
6. Save policy is configurable globally and per workflow: save on success (all or none), save on error (all or none), save manual runs, and "save progress" which writes after each node so a crash keeps partial data.
7. When data is too big to show (default 100 MB) the UI gets the execution without run data. Operational reads (retry, resume, recovery) are not affected.

Pruning (retention)
8. Two phases. Phase one (soft delete, default every 60 min) marks executions as deleted when they are older than max age (default 336 h = 14 days) or beyond max count (default 10,000, computed in the same statement so it matches the rows it updates).
9. It never marks executions that are still new, running or waiting. It never marks executions that carry an annotation (a user "keep" marker).
10. Phase two (hard delete, default every 15 min) removes soft-deleted rows older than a buffer (default 1 hour, so a user debugging a fresh run does not lose it). It works in batches of 100, newest id first, sleeps 1 s between batches, can be aborted, and falls back to one-by-one delete when a batch fails, logging the ids that failed.
11. Hard delete also removes the binary files of that execution.

Binary data
12. Modes: default (inline, kept in memory and in the data row), filesystem, S3 or Azure object store, and database. Queue mode forces non-inline.
13. File ids encode workflow and execution (a path-like id). A temporary execution id is used when a file is written before the execution row exists, and it is moved later.
14. Deletion is by execution prefix, with a concurrency cap of 5 so a huge batch cannot remove every tree at once. Object stores delegate cleanup to bucket lifecycle rules.
15. Database mode has a per-file size limit (default 512 MiB, hard max 1024 MiB because of the Postgres limit). Download URLs are signed with a secret that is derived from the instance key and stored.

Workflow history (versions)
16. A history row per saved version: version id, nodes, connections, groups, authors, name, description, "autosaved" flag. Cascade delete with the workflow.
17. Retention default is forever. An optional prune time deletes old versions but always keeps the current and the active one (and named versions when licensed).
18. A compaction job merges many recent autosaves into fewer (window based, runs every ~hour) and trims older ones (daily). So history stays useful and small.
19. Publish history and "published version" are separate entities, so "what is live" is not guessed from "latest".

Crash recovery
20. If the main process dies, a recovery service rebuilds the execution from the event log (which nodes started and finished), marks the running node as crashed, writes a crashed status, and can auto-deactivate a workflow that crashed several times in a row.

Import and export
21. CLI export of workflows: all in one file or one file per workflow, pretty print, optional backup folder. Import accepts a file or folder, assigns an owner or project, and by default deactivates imported workflows.
22. The workflow JSON is portable: nodes, connections, pin data, settings, meta. Pin data travels with the workflow. Credentials are exported separately (decrypted or encrypted by choice).
23. Whole-database entity export and import exists for instance moves. Enterprise adds git source control.

Other durable state
24. Poll triggers keep a cursor per node (`poller_state`: cursor, error count, back-off time) so two poll nodes in one workflow do not fight. A `processed_data` table backs the "remove duplicates across runs" feature. An idempotency key table exists.
25. Variables are a plain key/value table. Data tables are a module with real typed SQL columns, CSV import, a total-size validator with a cached check, and a cleanup task for uploads.
26. Credentials are encrypted with one instance key that is portable (you can move the key with the data).

Edge cases n8n handles that matter here
- Delete is two-phase with a buffer, never mid-run.
- Annotated (kept) runs are exempt from pruning.
- Size cut-off for display.
- Batch failure falls back to single delete.
- Version prune never deletes current or active version.

---------------------------------------------------------------------------------------------------

## 3. How KaivoFlow does it today (verified)

### 3.1 Database overview
- One Room database `kaivoflow.db`, version 6, `exportSchema = true`. `D/Database.kt:281-282`. 20 entities (`Database.kt:265-280`).
- Builder: `addMigrations(*ALL_MIGRATIONS)`, no destructive fallback, nothing else. `D/DataModule.kt:21-24`. Good: a missing step throws instead of wiping data.
- A Flow is one row: JSON document plus list columns (`FlowEntity`, `Database.kt:24-52`). Four indexes. Lifecycle columns added in v4.
- Runs: `runs` (metadata + a slim `runJson`), `D/Database.kt:68-79`. `runJson` has counts and status per step but no items (`D/JsonCodec.kt:255-289`). The old doc claim "runs store outputs" is only true for pre-v2 run documents (decode still reads them, `JsonCodec.kt:316-317`).
- Step data (schema 5): `step_executions` (one row per step execution, primary key runId+stepId+runIndex) and `step_port_data` (one row per port, gzip blocks of 64 items plus a varint offset table). `D/StepDataEntities.kt:25-116`, `D/StepDataCodec.kt:35-110`. Port rows cascade from their execution. No foreign key to `runs` (deliberate, explained in the file header).
- Pins: `step_pins` (JSON text per step). `D/StepDataEntities.kt:109-116`, `D/RoomStepDataBindings.kt:26-43`.
- Publish revisions: `flow_versions` (flowId, revision, full flowJson). Written only by publish. `D/EngineEntities.kt:34-40`, written in one transaction with the lifecycle columns and log row (`D/EngineDaos.kt:41-61`).
- Always-On engine tables: `trigger_registration`, `flow_lifecycle_log`, `spark_inbox` (unique dedupe key, lease columns), `schedule_state`, `expected_run`, `expected_run_daily`, `engine_session`, `engine_heartbeat`, `reliability_event`.
- Other: `credentials` (Keystore ciphertext), `workflow_groups`, `workflow_variables`, `data_tables`, `data_table_rows`.

### 3.2 Migrations
- Chain 1->2->3->4->5->6, contiguous, additive only. `D/Migrations.kt:269`. Every step is idempotent (`IF NOT EXISTS`, `hasColumn` before `ALTER`). `Migrations.kt:6-13`.
- Only `1.json` and `6.json` are committed. 2 to 5 were never exported (the file header admits it, lines 8-10). So steps 1->2 and 2->3 are inferred.
- Verified by script: every table created in a migration (11 of 11 new tables since v1) has `CREATE TABLE` text that is character-equal to Room's `createSql` in `6.json`. All new indexes match too. The `flows`/`runs` v1 indexes match `1.json`. `reliability_event.acknowledged` is added by ALTER (column order differs from Room's createSql; Room validates by column info, so this is fine).
- The v3->v4 data pass reads all rows into memory first, then writes (no open cursor during writes). `Migrations.kt:150-175`. Good.
- Tests: `MigrationsTest` (11 tests) checks the chain is contiguous, entity defaults equal SQL defaults, and the JSON active-flag regex. It does not run any SQL. There is no `androidTest` folder and no `MigrationTestHelper` anywhere (`find core/data/src` shows only `main` and `test`).

### 3.3 Flow save, load, JSON
- `RoomFlowRepository.save` (`D/Repositories.kt:107-138`): reads the existing row, builds a new entity, `upsert` with `OnConflictStrategy.REPLACE` (`Database.kt:109-110`), carries lifecycle columns from the row it read, then writes a plaintext mirror `files/flows/<id>.json`. No transaction around read and write.
- Load: `decodeWithLifecycle` sets `isActive` from the lifecycle column (`Repositories.kt:96-97`). Everything else (name, groupId, isFavorite) comes from the JSON.
- `JsonCodec.decodeFlow` is wrapped in `runCatching ... getOrNull()` (`JsonCodec.kt:245`). Any parse error returns null. Malformed step or stream objects inside the array are dropped silently (`mapNotNull`, `JsonCodec.kt:160ff`).
- Missing ids decode to empty strings (`str()` returns ""), no repair.
- No document version field in the Flow JSON (runs have `"v": 2`). Unknown fields are dropped on decode; the class comment at `JsonCodec.kt:40-42` says they are preserved, which is not true.
- Numbers: `fromJson` turns every non-string number into `Double` (`JsonCodec.kt:431-432`). `long()` reads through `doubleOrNull` (`JsonCodec.kt:441-442`).
- Validation on load: none. `FlowValidator` runs in the canvas (`refreshValidation`) and at publish (`PublishValidator`), not when loading. The import bundles do validate hard (`NativeFlowBundle.kt validateGraph`: unique ids, ports 0..255, caps of 500 steps / 2000 streams, DAG check, depth 48, text caps).

### 3.4 Run history and retention
- Run row is written at start (only when both "save successful" and "save failed" are on) and replaced at the end. `W/FlowEngine.kt:278-283, 326-337`. Crash path writes a Canceled "Interrupted" record in `finally` with `NonCancellable` (`FlowEngine.kt:268-277`). App start sweep marks leftover Running or Queued rows Canceled (`FlowEngine.kt:289-299`, scans newest 2000). Always-On engine turns started claims with expired lease into Failed "Crashed:" (`W/alwayson/RunHost.kt:131-155`). Solid design.
- Retention constants: `MAX_RUNS_PER_FLOW = 500`, `MAX_AGE_DAYS = 30` (`E/RunRetention.kt:11-12`). Only the age rule is applied (`FlowEngine.kt:301-305` calls `pruneOlderThan(30)`). The 500 constant is never referenced (grep: only its declaration). The comment at `FlowEngine.kt:301` says the count cap "is applied by pruneHistory"; it is not.
- Step data retention (`E/stepdata/StepDataPolicy.kt:400-433`): per Flow keep 20 manual runs, 10 test runs, and by mode (default Failed) only the last 5 successful production runs plus all failed ones; then a global byte budget of 100 MB, oldest first, never evicting runs with pinned replays or "open" runs (`StepDataPolicy.kt:361-387`).
- `SaveDataMode` is never set from any setting: the only call uses the default (`E/RunRetention.kt:31`). `protectedRunIds` is never passed (`FlowEngine.kt:304`).
- `pruneHistory()` runs after every finished Run (`FlowEngine.kt:336`), outside the run lock.
- Capture limits: 200 items per port, 512 KB per port, 8 KB per value, depth 12 (hard max 1000 items, 2 MB, 64 KB, depth 24). Pins: 1 MB per step, 4 MB per flow (hard 4 and 8 MB). `StepDataPolicy.kt:38-77`.
- Capture is async through a bounded channel (64) with marker rows when it overflows, so a slow disk never slows a Run. `E/stepdata/StepExecutionRecorder.kt`. Good.

### 3.5 Publish revisions (versioning)
- Publish and Publish changes snapshot the draft into `flow_versions` with revision = latest+1 (`E/ActivationCoordinator.kt:127, 156`). Engine runs the published revision, not the draft (`W/alwayson/LiveFlows.kt:59-73`). This is a correct "draft vs live" design.
- Nothing reads `flow_versions` for the user: `versions()`, `version()` have no UI caller (grep). No restore, no diff. There is no history per save. Revisions are never pruned.

### 3.6 Backup, export, import
- Android backup is off: `allowBackup="false"`, `fullBackupContent="false"` (`AndroidManifest.xml:54-56`), and the extraction rules exclude the database, the flow mirror, shared prefs and key files from cloud and device transfer.
- There is no app-level full backup and no scheduled backup anywhere.
- Per-Flow export UI: two formats. `.kaivoflow` native (names, positions, params, variables, frozen output; secrets blanked; ids re-mapped on import; triggers disabled) and a structure-only JSON. `FL FlowTransfer.kt:71-101`.
- Import reads at most 4 MB (`FlowTransfer.kt:129`), previews, and saves only after a confirm (`FlowTransfer.kt:156-168`). Always a new Flow id.
- `NativeFlowBundle` can carry pins (up to 16 MB, `NativeFlowBundle.kt:38-41`), but the UI never passes pins on export (`FlowTransfer.kt:80`) and drops `native.pins` on import (`FlowTransfer.kt:144`).

### 3.7 Files and binary
- Binary attachments are written to `files/binary/<8 hex>_<name>` and referenced as `kaivo://files/<name>` in items (`W/AndroidDeviceGateway.kt:222-245`). Read back as a whole base64 string, cap 25 MB (`AndroidDeviceGateway.kt:252-270`). Nothing ever deletes these files (grep for delete on that folder: none).
- Always-On inbox payloads over 32 KB spill to `files/spark_inbox/` with write-then-rename (`W/alwayson/EngineSupport.kt:99-115`). Files are deleted when a row finishes or crashes (`RunHost.kt:116, 142`).
- File Step and Local table share a sandbox `files/user-files/` with a good path check (canonical path, no `..`, no absolute paths) (`core/nodes/.../UserFileStore.kt`).

### 3.8 Settings storage
- Eight separate SharedPreferences files: `kaivo.engine`, `kaivo.webhook`, `kaivo.evidence`, `kaivo.reliability`, `kaivo.reconciler`, `kaivo.dwell`, `kaivo.selftest`, `kaivo.picker`, plus a motion pref in `core/design`. All plaintext, all `apply()`. Not backed up, not exported.
- The webhook master secret is a 32-byte random hex in plaintext prefs (`W/WebhookServer.kt:46-62`).
- `androidx.security.crypto` is a declared dependency of core:data (`core/data/build.gradle.kts:32`) but is not used anywhere.

### 3.9 Credentials
- AES-256-GCM key in Android Keystore, ciphertext in Room (`D/CryptoBox.kt`, `Repositories.kt:246-325`). Non-portable by design. Decrypt failure gives a clear "re-enter" error.

### 3.10 Variables, groups, data tables
- Variables: key/value rows, optional group. The UI always creates global variables with a fresh random id (`FL FlowListScreen.kt:348-359`). Engine merges `group vars + global vars` under the Flow's own `vars` (flow wins) (`W/FlowEngine.kt:93-109`).
- Data Tables (Room): UI can create tables and add or delete rows. No engine node, no Step, no gateway touches them (grep for "datatable|data_table|data table" in core/nodes, core/engine, core/work: no hits).
- A second, separate store, the Local table Step, keeps whole tables as JSON files in `user-files/` (`core/nodes/.../TableNode.kt:60-107, 130-137`) with a 5,000 row cap.

### 3.11 Antigravity storage (AiSession and Agent)
- Both are plain JSON files, not Room. `FL ai/AiSessionRepository.kt` (`files/ai_sessions/<id>.json`) and `FL ai/agent/AgentRepository.kt` (`files/agents/<id>.json`).
- `AiSessionRepository`: has a mutex, writes tmp then deletes target then renames (`:67-94`). Loads every file in `init` on construction (`:55-57`). Has a "self-heal" for two JSON objects glued together (`:162-176`), which shows a past corruption.
- `AgentRepository`: no mutex, direct `writeText` (`:49-62`), loads all in `init` (`:39-41`), creates a "Morning News Brief" starter agent when no file parses (`:87-95`). Agent run history (`sessions`) is stored inside the agent file and is prepended on every test (`AgentBuilderViewModel.kt:517, 533`).
- Neither is in the Flow export, the backup (there is none) or Room. `isPublished` on an agent only flips a flag in this file (`FlowListScreen.kt:180`); nothing in `core/` reads agents (grep `AgentRepository` in `core/`: none). That is a question for the AI audit; it matters here because publish state is stored outside the engine's database.

---------------------------------------------------------------------------------------------------

## 4. Issues found

Severity: P0 = wrong behaviour in normal use or severe data loss or security hole. P1 = data loss or serious growth or security under realistic conditions. P2 = quality, cleanup, risk later.

Counts: P0 = 1, P1 = 17, P2 = 19.

### 4.1 P0 and P1

| id | sev | file:line | what | why it matters | fix idea |
|---|---|---|---|---|---|
| DB-01 | P0 | `CV:131-148`, `CV:152-158`, `CV:659-664`, `D/JsonCodec.kt` (`encodeFlow` writes `vars`), `W/FlowEngine.kt:95` | When the canvas loads a Flow it merges global (and, in the second collector, ALL) variables into `flow.vars` in memory (`scopedVars + loaded.vars`, `resolved + flow.vars`). The next autosave writes the merged map into the Flow JSON. At run time the engine does `scopedVars + flow.vars`, so the Flow's frozen copy wins. | After any edit, every global variable is copied into that Flow for good. Changing the global variable later has no effect on that Flow. Variables from other groups leak in. Secrets stored as variables are copied into every edited Flow and into every `.kaivoflow` backup (only secret-looking keys are blanked). This breaks the core promise of variables and is silent. | Keep display-only merged vars in a separate field of the VM state; save only `flow.vars` that the user edited. Unit test with fakes (see task T1). |
| DB-02 | P1 | `CV:131-148`, `D/Repositories.kt:92-93`, `D/Database.kt:100` | The canvas collects `observeFlow(id)` and sets `flow = loaded` on every emission, with no dirty check. Room re-emits whenever ANY row of `flows` changes (a run finishing calls `updateLastRun`, the engine writes lifecycle columns), and there is no `distinctUntilChanged`. | While the user types, a background run (or an Always-On transition) can reset the in-memory Flow to the older DB copy. The pending autosave then saves the older copy. The edit is lost with no message. Needs device timing test (section 8) but the code path is clear. | Ignore emissions while `isSaving` or dirty; compare `updatedAt`/hash; add `distinctUntilChanged` and key the DAO query to the row. |
| DB-03 | P1 | `CV:659-670`, `CV:162-165` | Autosave is a 450 ms debounce in `viewModelScope`. `onCleared` closes the inspector but does not flush. Leaving the screen, process death or `cancel()` inside the window drops the last edit. | Back-press right after typing loses the last change. | Flush in `onCleared` and on `ON_STOP` with `NonCancellable`; save immediately on back. |
| DB-04 | P1 | `D/Repositories.kt:107-129`, `D/Database.kt:109-110` | `save()` reads the old row, then REPLACEs the whole row with lifecycle and last-run values copied from that earlier read. Not in a transaction. | A lifecycle change (Pause, Error with retry count and next retry time, Publish) or `recordRun` that lands between the read and the replace is overwritten by the stale values. A paused Flow can wake up; Error back-off is lost. Autosave makes this window common while editing a live Flow. | Replace with `@Transaction` read-modify-write, or better a content-only `UPDATE flows SET name=..., flowJson=..., stepCount=..., updatedAt=... WHERE id=...` plus insert-if-absent. |
| DB-05 | P1 | `D/Repositories.kt:161-163, 170-172` vs `Repositories.kt:96-97, 123`; `FL FlowListScreen.kt:341-344` | `setFavorite` and `updateGroup` change only the column. Load takes `groupId` and `isFavorite` from the JSON. `save` writes `groupId = stamped.groupId` and `isFavorite` from the decoded JSON. | Move a Flow to a group, or favourite it, then edit it once on the canvas: the move or favourite is reverted. Also the engine's group-scoped variables resolve from the stale JSON `groupId` (`FlowEngine.kt:94`). Two sources of truth. | Make decode override `groupId`/`isFavorite` from columns (as done for `isActive`), or rewrite the JSON in the same transaction. |
| DB-06 | P1 | `AndroidManifest.xml:54-56`, `res/xml/data_extraction_rules.xml`, whole repo | No backup of any kind. `allowBackup=false`, DB excluded from cloud and device transfer, no app-level export of all Flows, no scheduled backup. Only a manual per-Flow `.kaivoflow` file. | Uninstall, "clear data", a lost or replaced phone, or a bad migration deletes every Flow, variable, group and data table. For an automation app people depend on, this is the biggest trust gap. See section 6. | Full backup to a user-chosen SAF folder (zip: all Flows, groups, variables, data tables, settings; credentials optional and passphrase-encrypted). Auto backup with WorkManager. |
| DB-07 | P1 | `E/RunRetention.kt:11` (dead), `W/FlowEngine.kt:301-305`, `D/Repositories.kt:229-234` | Run rows are pruned by age only (30 days). `MAX_RUNS_PER_FLOW = 500` is declared and never used. | A Flow that runs every minute creates about 43,000 `runs` rows in 30 days. Each row has a slim JSON of every step (about 250 bytes per step), so 20 steps is about 5 KB. That is about 200 MB for one Flow. Run lists then sort that Flow's rows on every emission. | Apply a per-Flow cap in `pruneOlderThan` (delete rows beyond newest N per flowId), make days and count settings, keep failed runs longer. |
| DB-08 | P1 | `W/FlowEngine.kt:336`, `E/RunRetention.kt:35-37`, `D/StepDataDao.kt:59-71` | After every finished Run, `pruneStepData` calls `runMetas()` (GROUP BY over all `step_executions`) and `usage()` (LEFT JOIN and SUM over all `step_port_data` rows), computes a plan in memory, then deletes. | Full-table aggregates on a phone for every Run. For frequent Flows this is constant CPU, I/O and battery. Needs measuring (section 8). | Throttle: run at most every N minutes or N runs, or keep a running byte counter; run on the engine's idle tick. |
| DB-09 | P1 | `FL FlowListScreen.kt:511-516`, `W/alwayson/ActivationManager.kt:122-124, 140-144`, `D/StepDataDao.kt:57, 92` (unused `deleteForFlow`) | Deleting a Flow purges versions, log, registrations, inbox rows and schedule evidence, and removes the row. It does not delete `runs` rows, `step_executions` and ports, `step_pins`, inbox payload files, or the mirror file in all paths. `deleteForFlow` on step data and pins exists but has no caller. | Pinned data (can hold real customer data, up to 4 MB per step) of a deleted Flow stays forever and is never evicted (pins are not budgeted). Step data stays until budget. The user thinks it is gone. Also `delete` runs twice (activation then repository), and if `coordinator.deleted` throws, `runCatching` swallows it and the row is removed anyway, leaving armed alarms and orphans. | One `deleteFlowCompletely(id)` transaction that calls every `deleteForFlow`; add soft delete (trash, 30 days). |
| DB-10 | P1 | `W/AndroidDeviceGateway.kt:222-245` | Binary files in `files/binary` are never garbage collected and have no quota. Writes are `writeBytes` (not atomic). The checksum stored in the ref is never verified on read. | Any Flow that downloads or creates files fills storage forever (photos, PDFs). On a phone this ends in "storage full", which also breaks the database. A crash during write leaves a truncated file that is still referenced. | Track refs per Run; delete files of pruned Runs; global byte budget like step data; write tmp then rename; verify checksum when loading. |
| DB-11 | P1 | `W/FlowEngine.kt:82`, `D/StepDataCodec.kt` (`errorToJson`, near the "small JSON columns" section), `E/stepdata/StepDataPolicy.kt:105` (`redactString` has no caller) | Step data capture uses `StepDataRedactor()` with no known secret values and no secret keys, so the credential values a Flow actually used are not masked unless they match a token shape or a sensitive key name. Step error info (`message`, `details`, `httpBody`) is stored raw: `redactString` is defined but never called. | An HTTP error body that echoes a key, or a response with a token under a harmless key name, is stored in plain text and is shown in the inspector and kept up to 30 days. | Build the redactor per Run from the resolved credential fields; run `redactString` over error message, details and httpBody before `errorToJson`. |
| DB-12 | P1 | `D/Database.kt:97-107`, `D/EngineDaos.kt:63-70`, `D/StepDataDao.kt:76-83`, `D/Repositories.kt:70-90` | List and lifecycle queries are `SELECT *` on `flows`, so they load the full `flowJson` for every Flow just to show a name or read lifecycle columns. The class comment calls the list row "lightweight" (`Repositories.kt:28`), but it is built from the full entity. `step_pins` `SELECT *` loads the whole pin text (up to 4 MB hard max). No size limit on saving a Flow. | Android's CursorWindow is about 2 MB. One row bigger than that fails with "Row too big to fit into CursorWindow", and the whole list query fails, so the home screen could go blank. A Flow with large inline text, frozen output or a big Code step can get there. Every run completion re-queries all Flows (table invalidation). | Projection classes (`FlowSummaryRow`, `LifecycleRow`) with only needed columns. Enforce a save cap (for example 1.5 MB with a clear message). Store large step text or pins as separate rows or files. |
| DB-13 | P1 | `core/data/src/test/.../MigrationsTest.kt`, `D/Migrations.kt:8-10`, `core/data/schemas/` | No test ever executes migration SQL. Schemas 2-5 were never committed so a `MigrationTestHelper` test can only go 1->6. There is no `androidTest` source set. | A hand-written ALTER or CREATE that differs from Room's expected schema crashes on first open after upgrade. My script (3.2) shows 6.json matches today, but nothing guards the next migration (v7). No pre-migration backup copy of the DB file either. | Add `androidTest` with `MigrationTestHelper` (assets from `schemas/`), test 1->6 with seeded rows (active flow JSON, runs, credentials); from now on commit every schema JSON; copy `kaivoflow.db` to `kaivoflow.db.bak-vN` before migrating. |
| DB-14 | P1 | `W/alwayson/LiveFlows.kt:59-65, 72-73` | `publishedOrDraft` returns the draft when the published revision cannot be loaded or decoded (`published()` returns null). | If a revision row is missing or its JSON fails to decode, a live Flow silently starts running the unfinished draft, which may be half edited. Violates the "live runs the published revision" guarantee. | If a Flow is live and the revision cannot be read: do not run, raise a reliability event, set Error with a clear code. |
| DB-15 | P1 | `core/nodes/.../TableNode.kt:60-107`, `.../UserFileStore.kt` (`write`), `.../DedupeNode.kt:76-95`, `W/AndroidDeviceGateway.kt:143-148` | The Local table Step reads the whole file, changes it in memory and rewrites the whole file with `writeText`: no lock, not atomic. Dedupe state is also a text file rewritten the same way. | Two Flows (or two webhook runs) using one table lose updates (counters wrong). A kill during write truncates the file; the next read parses to empty and the next write replaces the table, so a whole table or "seen" set silently disappears (duplicates get sent again). Always-On makes concurrent runs and kills normal. | Atomic write helper (tmp, fsync, rename) plus a per-file mutex now; then move the Table Step onto Room (atomic upsert and increment). |
| DB-16 | P1 | `D/JsonCodec.kt:245`, `D/Repositories.kt:99-105, 130-136`, `W/alwayson/LiveFlows.kt:34`, `CV:132` | A Flow whose JSON fails to parse decodes to null. `all()` silently skips it, the list (built from columns) still shows it, the canvas shows nothing (`if (loaded == null) return@collect`), and a live corrupted Flow just stops being armed. The plaintext mirror `files/flows/<id>.json` is written on every save but nothing reads it. | One bad write turns a Flow into an invisible, unrecoverable row with no warning. The mirror, which could have been the recovery copy, is dead code. | On decode failure: keep the raw JSON, show a "Flow damaged" card with Export raw and Restore from last revision or mirror; log a reliability event. Either use the mirror for recovery or delete it (DB-27). |
| DB-17 | P1 | `FL ai/agent/AgentRepository.kt:39-41, 49-62, 87-95`, `AgentBuilderViewModel.kt:517, 533` | Agent files: direct `writeText` (no tmp/rename), no mutex, read-modify-write of the shared list flow, loads all files synchronously in `init`, unbounded `sessions` list inside the agent file (prepended on every test run, with full prompt and response text), and a starter agent is created when no file parses. | A kill during save truncates the file, the agent silently vanishes, and if it was the only one the starter agent appears, hiding the loss. Two concurrent saves can drop one from the in-memory list. The file grows with every test and is rewritten whole on every save. Blocking file reads on the thread that creates the singleton can delay startup. | Shared atomic JSON writer; mutex; cap sessions (for example last 50, or a separate file); load on `Dispatchers.IO`; never auto-create the starter agent over unreadable files (quarantine them). |
| DB-18 | P1 | `D/Repositories.kt:266-273` (and filters at 277, 283, 289) | `EncryptedCredentialRepository.init` launches a coroutine that deletes every credential whose type is `googleSheetsOAuth2` on each construction (each app start), and every list filters that type out. | Deleting user data from a constructor, on every start, forever. The type appears nowhere else, so today it is dead cleanup, but if that type is ever offered again, saved credentials vanish at the next launch. | Make it a one-time migration (version flag, or a DB migration), then remove the filters and the init block. |

### 4.2 P2

| id | sev | file:line | what | why it matters | fix idea |
|---|---|---|---|---|---|
| DB-19 | P2 | `D/JsonCodec.kt:431-436, 441-446`, `E/stepdata/Pins.kt:150-156` | All JSON numbers decode as `Double`. Integers above 2^53 (many social and chat ids) lose precision. The pin editor parser keeps `Long`, so a pinned `5` becomes `5.0` after a storage round trip. | Silent id corruption in params, pins and stored step data. | Decode integers as `Long` when the text has no `.`, `e`, `E`; round-trip tests. |
| DB-20 | P2 | `D/JsonCodec.kt:35-60, 245`, `JsonCodec.kt:40-42` | No schema version in the Flow JSON; unknown fields are dropped (comment says preserved). | Future format changes have no hook. Opening a newer export in an older app and saving loses fields. | Add `"v"` to `encodeFlow`; keep unknown keys in an `extras` map; fix the comment. |
| DB-21 | P2 | `D/Repositories.kt:448-450, 348-363` | `deleteRow` does not touch `data_tables`, and `observeTables` only re-emits when `data_tables` changes. `observeRows` and `rowsForTable` load and parse every row (no paging). `deleteTable` is two statements, not one transaction. | Row count shown on the table list stays stale after a delete. Large tables are O(n) per change. | Observe a count query; `@Transaction` for delete; page rows. |
| DB-22 | P2 | `FL FlowListScreen.kt:348-359`, `D/Database.kt:41-42` (indexes not unique) | Variables have no unique key per scope; each save creates a new random id. `forGroup` has no ORDER BY. Deleting a group leaves its variables as orphans (`FlowListScreen.kt:329-339` re-homes Flows but not variables). | Two variables with one key give an unpredictable winner; orphans are invisible. | Unique index (groupId, key) and upsert by key; delete or re-home variables with the group. |
| DB-23 | P2 | `D/Repositories.kt:318` and `:295` | Credential `save` resets `createdAt` to now on every edit; `observeCredentials` reports `updatedAt = createdAt` and `usedInFlowCount = 0` always. | Wrong dates in the credentials screen; "used in" is not computed, so the user cannot see what breaks before deleting one. | Add `updatedAt`; compute usage by scanning step params for the credential id. |
| DB-24 | P2 | `D/CryptoBox.kt:20-33` | `secretKey()` is not synchronized: two threads that find no key both generate one; the second overwrites the first. | If the first start has two parallel credential operations, data encrypted with the lost key is unreadable. Rare but unrecoverable. | `@Synchronized` or a lazy single key. |
| DB-25 | P2 | `FL ai/AiSessionRepository.kt:55-57, 74-77, 162-176` | Session save deletes the target before renaming the tmp file (`renameTo` over an existing file is atomic on Android's file system, so the delete only adds a loss window). `init` reads every session file synchronously. No retention; each save rewrites the whole session. The self-heal code picks the wrong half in some cases. | A kill between delete and rename loses the session. Startup cost grows with history. | Drop the delete; keep last N sessions; load async. |
| DB-26 | P2 | `D/StepDataCodec.kt:109ff`, `core/engine/.../RunRetention.kt:35`, `W/FlowEngine.kt:304` | Step data is captured and gzip-written for every successful production run, then dropped soon after by the "keep last 5" rule. `SaveDataMode` and "protected (open in editor) runs" are never wired. | Write amplification on flash for no value; the documented rule "runs open in the editor are never removed" is not honoured. | Buffer in memory and persist only on failure (or last N); pass the editor's open run ids. |
| DB-27 | P2 | `D/Repositories.kt:130-136, 142-144`, `DataModule.kt:50-51`, `data_extraction_rules.xml` header | The mirror file is an extra plaintext copy of every Flow (possibly with inline API keys), written on every autosave, never read, and not in any backup. | Extra I/O and an extra secret-bearing copy with no benefit today. | Delete it, or make it the recovery source with a clear contract. |
| DB-28 | P2 | `FL FlowTransfer.kt:129, 144, 80`, `D/NativeFlowBundle.kt:38-41` | Import limit is 4 MB while export may produce up to 16 MB with pins; pins are never exported by the UI and ignored on import. | A backup the app wrote may be refused by the app; pinned fixtures do not travel (n8n exports pin data with the workflow). | Export pins (default on, size-checked); raise the import limit to 16 MB when `pins` is present; call `PinStore.restore` on confirm. |
| DB-29 | P2 | `D/JsonCodec.kt:255-289` | `triggerData` is not written to the run JSON and not restored. | The payload that started a Run (webhook body, schedule slot) is not visible in history unless trigger step data was kept. | Persist a redacted, size-capped trigger payload with the Run. |
| DB-30 | P2 | `D/Database.kt:68-79` | `runs` has separate indexes on `flowId` and `startedAt`. The Flow history query filters by `flowId` and sorts by `startedAt`. No index on `status`. | With a busy Flow, each re-emission sorts tens of thousands of rows. | Composite index (flowId, startedAt DESC); add (status, startedAt). |
| DB-31 | P2 | `D/Repositories.kt:70-93, 197-201` vs `:299, 363, 414` | `observeSummaries`, `observeFlow`, `observeRecent` map and decode JSON without `flowOn(io)`. Credentials and data tables use `flowOn(io)`. | JSON parsing of Flows and Runs on the collector thread (usually Main) can drop frames in big databases. | `flowOn(Dispatchers.Default)`. |
| DB-32 | P2 | `D/EngineDaos.kt:84-85`, `D/EngineEntities.kt:34-40`, grep callers | `flow_lifecycle_log` is never pruned (`deleteLogOlderThan` has no caller); `flow_versions` is never pruned or compacted. | Slow unbounded growth. Each revision is a full Flow JSON. | Keep last N revisions plus the published one; compress old JSON. |
| DB-33 | P2 | `E/ActivationCoordinator.kt:127, 156`, `D/EngineDaos.kt:39` | `latestRevision + 1` is computed outside the insert; the insert is IGNORE on (flowId, revision). | Two concurrent publishes could point `publishedRevision` at a revision whose stored JSON is the other writer's. Low chance in a single user app. | Allocate the revision inside the transaction. |
| DB-34 | P2 | `W/alwayson/EngineSupport.kt:99-115`, `D/EngineRepositories.kt:121, 124` | `dropOpenForFlow` and `purge` change rows but do not delete the spilled payload files. Leftover `.tmp` files after a crash are never cleaned. | Webhook bodies (possibly sensitive) stay on disk. | Sweep `spark_inbox/` for files with no row on start. |
| DB-35 | P2 | `D/Database.kt` (no size policy), whole repo | No database size cap, no "storage low" handling, no `VACUUM` or incremental auto-vacuum. | After pruning, the file does not shrink. A full phone breaks writes. | Monitor free space; stop capturing step data under a threshold; periodic `wal_checkpoint(TRUNCATE)` and occasional `VACUUM`. |
| DB-36 | P2 | `W/WebhookServer.kt:46-62` | The webhook master secret sits in plaintext SharedPreferences and is saved with `apply()`. | A crash right after "regenerate" can leave the old secret on disk while the UI showed the new one. Plain storage when a Keystore box already exists. | Store through `CryptoBox`; use `commit()` for this key. |
| DB-37 | P2 | `core/data/build.gradle.kts:32` | `androidx.security.crypto` is a dependency but unused. | Dead dependency (and a deprecated library). | Remove. |

---------------------------------------------------------------------------------------------------

## 5. Gap vs n8n

Status: exact (same behaviour), partial, missing. Effort S (under 1 day), M (a few days), L (a week or more). Value is for users and for the business (what a paying user would notice).

| n8n capability | KaivoFlow status | effort | value |
|---|---|---|---|
| Execution metadata separate from heavy data | exact in spirit: `runs` slim row, items in `step_port_data`, paged reads with a block index (`D/StepDataCodec.kt`) | done | high, keep |
| Workflow snapshot stored with each execution | missing: Run row has no revision id or flow copy, so you cannot tell which version ran | S (store `publishedRevision` on the Run) | high for debugging |
| Save policy per outcome and per Flow (success, error, manual, progress) | partial: per-Flow save successful/failed flags only (`JsonCodec.kt:64-65`); `SaveDataMode` unwired; no progress save | S-M | medium |
| Prune by age and by count | partial: age fixed 30 days, count dead (DB-07), not configurable | S | high |
| Soft delete, hard delete buffer, batch delete, abort, failure fallback | missing (plain DELETE statements, small tables so mostly fine; step data delete per run is OK) | S-M | low |
| Pruning never touches running or annotated runs | partial: running rows are age-pruned only after 30 days (safe in practice); no "keep this run" marker | S | medium |
| Binary data storage with per-execution cleanup | partial: files stored by uuid prefix, no link to Run, no cleanup, no quota (DB-10) | M | high |
| Display size cutoff | exact or better: hard caps at capture (`StepDataPolicy.kt:38-77`) | done | medium |
| Workflow version history per save, restore, compaction | partial: published revisions only, no UI, no pruning (3.5) | M | high |
| Publish history and "which version is live" | exact: lifecycle column plus `flow_versions`, immutable | done | high |
| Execution crash recovery | partial-good: Interrupted and Crashed records, lease-based inbox. No automatic stop after repeated crashes (n8n auto-deactivates) | S | medium |
| Workflow export and import (single) | partial: native and structure formats, strong validation (`NativeFlowBundle.kt`), no pins in UI | S | medium |
| Bulk export, import folder, instance backup | missing (DB-06) | M | very high |
| Import n8n workflow JSON | missing (no reference to n8n format anywhere) | L | very high for acquisition |
| Pin data travels with the workflow | partial: code exists, UI not wired (DB-28) | S | medium |
| Credentials export (encrypted) | missing: by design secrets are blanked; no portable encrypted export | M | high |
| Poller state (cursor) and processed-data table | partial: Dedupe node keeps text files (DB-15) | M | medium |
| Idempotency or dedupe keys | exact or better: `spark_inbox.dedupeKey` unique index with leases (`D/EngineEntities.kt:84-100`) | done | high |
| Variables (key/value, scoped) | partial: no unique key, no UI for scoped vars, merge bug (DB-01, DB-22) | S | medium |
| Data tables (typed columns, CSV import, size limit, node access) | partial: Room tables with string-typed column list, UI only, no node (3.10), no row update, no CSV, no size cap | M-L | high |
| Migrations with up/down and registry test | partial: additive up-only chain, tests do not run SQL (DB-13) | M | high |
| Source control sync | missing | L | low for a single-user phone app |
| Encrypted credential store | different and stronger: Keystore bound; not portable | done | high (privacy); needs export story |
| Retention settings in UI | missing | S | medium |

---------------------------------------------------------------------------------------------------

## 6. Where KaivoFlow can beat n8n

All of these use things a phone has and a server does not.

1. One-file transactional store. Everything (Flows, runs, pins, tables, inbox) is in one SQLite file, so a consistent backup is one operation. On Android 11 and newer (SQLite 3.27+) `VACUUM INTO` writes a clean copy while the app runs; on older versions use `wal_checkpoint(TRUNCATE)` and copy the file. n8n needs a database dump plus the binary folder plus the encryption key.
2. Zero-server auto-backup. A WorkManager job writes a zip to a folder the user picks with the Storage Access Framework (that can be a Drive, a USB stick, Syncthing or Nextcloud folder). No account, no cloud of ours. This is a sellable "your data is safe" feature and costs M.
3. Passphrase-encrypted backups that include credentials. Re-encrypt the vault with a key derived from a passphrase (PBKDF2 or Argon2 plus AES-GCM). That makes credentials portable between phones, which n8n only solves with a shared instance key.
4. Share a Flow like a photo. Android share sheet, a `kaivoflow://import` link and a QR code for small Flows (zip and base64). n8n needs copy-paste of JSON. The existing `NativeFlowBundle` already validates and re-maps ids, so this is mostly plumbing.
5. n8n JSON importer. Map n8n node types to KaivoFlow Steps (the alias map `CatalogAliases` already exists), report unsupported nodes, drop credentials. This is the best acquisition feature in this area; the n8n workflow format is documented by its own files and community sharing.
6. Storage meter and "free up space". `step_port_data.bytes` is already stored per execution (`D/StepDataEntities.kt`). Show per-Flow usage (runs, data, pins, files) and one-tap cleanup. n8n only has global limits.
7. Capture only what is needed. Buffer step data per Run in memory and write it only when the Run fails or the Flow is in "debug" mode. Saves flash wear and battery (DB-26); n8n writes everything by default.
8. Flow time machine. Cheap per-save revisions (coalesced, gzip JSON, last 50 plus all published) with restore and a visual diff on the canvas. SQLite makes this a few hundred lines; n8n's history needs a compaction service.
9. Real Data Tables with a node. Typed columns, atomic upsert and increment (`INSERT ... ON CONFLICT DO UPDATE`), read-only SQL query Step on a sandboxed copy, CSV import through the system file picker. That replaces the unsafe file-based Table Step (DB-15) and beats n8n for counters and "seen" lists, where n8n needs a Code node.
10. Trash for Flows. Soft delete for 30 days (cheap, one column) gives the user "undo" on the most destructive tap and solves orphan cleanup timing.
11. Search across history. An FTS table (FTS4 works on every Android version; check FTS5 on target devices) over run errors and stored step data lets the user find "the run that handled order 123" offline.
12. Honest crash proof. Because inbox leases and run records are already in SQLite, a "last 24 hours reliability report" can include data-integrity checks (orphan rows, damaged Flows, pending migrations) next to the existing reliability data.

---------------------------------------------------------------------------------------------------

## 7. Recommended ordered tasks

Each task is small, has an acceptance test, and lists the issues it closes. Order is by risk first, then business value.

| # | task | closes | acceptance test |
|---|---|---|---|
| T1 | Stop persisting merged variables. In `CV`, keep display-merged vars in UI state only; `save` writes only the Flow's own vars. Add a one-time repair that removes vars from each stored Flow whose key and value equal a global variable. | DB-01 | JVM test with fake repos: global A=1, load Flow, edit a step, save; stored JSON has no key A. Set A=2; `resolveEffectiveFlow` gives A=2. Repair test: a Flow saved with A=1 copy and global A=1 loses the copy. |
| T2 | One source of truth for `groupId` and `isFavorite`: decode from columns in `decodeWithLifecycle`; keep JSON copy only for export. | DB-05 | Test: `updateGroup(id, "g1")` then `flow(id).groupId == "g1"`, then `save(flow)` keeps "g1". Same for favourite. |
| T3 | Content-only save. Replace REPLACE-upsert with insert-if-absent plus `UPDATE ... SET name, flowJson, stepCount, updatedAt, groupId, isFavorite WHERE id`. | DB-04 | Test with an in-memory Room (Robolectric or androidTest): start a save, change lifecycle to PAUSED in between; after save lifecycle is still PAUSED and `lastRunStatus` unchanged. |
| T4 | Canvas save safety: flush in `onCleared` and `ON_STOP`; ignore echo emissions (dirty flag and `updatedAt` compare); `distinctUntilChanged`. | DB-02, DB-03 | VM test with a fake repository: edit, emit an older copy before the debounce fires; final saved Flow has the edit. Edit then `onCleared()`; repository received the save. |
| T5 | `deleteFlowCompletely`: one transaction that deletes runs, step executions (cascade ports), pins, versions, log, inbox rows and payload files, schedule rows, then the Flow. Call it from the ViewModel; remove the duplicate delete. | DB-09, DB-34 | Test: create Flow with a run, step data, a pin, a revision; delete; every table has 0 rows for that flowId, payload file is gone. |
| T6 | Retention that works: apply per-Flow cap (default 500) in `pruneOlderThan`; settings for days and count; throttle `pruneStepData` (at most every 10 min or 50 runs); pass the editor's open run ids; wire `SaveDataMode` to a Flow setting. | DB-07, DB-08, DB-26 | Test: insert 600 runs for one Flow; after prune 500 newest remain. Call prune 100 times in a loop; `runMetas()` is called at most once per throttle window. |
| T7 | Migration test rig: `androidTest` source set, schemas dir added as assets, `MigrationTestHelper` test 1->6 with seeded data (an active-flow JSON, runs, a credential). Commit every future schema JSON. Add a pre-migration file copy. | DB-13 | CI job runs the test on an emulator; test passes; deliberately breaking a CREATE statement makes it fail. |
| T8 | Projection queries and save cap: `FlowSummaryRow` (id, name, stepCount, updatedAt, flags, lifecycle columns) and `LifecycleRow`; refuse to save a Flow over 1.5 MB with a message; move pins to a chunked read. | DB-12, DB-31 | Test: 100 Flows with 1 MB JSON each; summaries load without reading `flowJson` (assert the query columns). Saving a 2 MB Flow returns a clear error. Device test for CursorWindow (section 8). |
| T9 | Atomic file helper (tmp, fsync, rename) and per-file mutex, used by `UserFileStore.write`, binary store, agent and AI session repositories. | DB-15 (part), DB-10 (part), DB-17, DB-25 | Test: kill-simulation (write interrupted after tmp) leaves the old file intact. Two coroutines incrementing one table 1000 times each end at 2000. |
| T10 | Redaction: build the capture redactor per Run from resolved credential values; pass `message`, `details`, `httpBody` through `redactString`. | DB-11 | Test: HTTP error body contains the credential value; stored `errorJson` contains the mask, not the value. |
| T11 | Damaged-Flow handling: on decode failure keep raw JSON, show a repair card (export raw, restore last revision, delete), emit a reliability event; a live damaged Flow goes to Error, not silent. Decide the fate of the mirror (use it or delete it). | DB-16, DB-14, DB-27 | Test: corrupt `flowJson`; list shows a "damaged" row; `LiveFlows` reports an error event; restoring revision N makes it load. |
| T12 | Full backup and restore (zip: manifest with version, all Flows and revisions, groups, variables, data tables, settings, optional pins; credentials only with passphrase). Settings screen: "Back up now" and auto backup to a SAF folder. | DB-06, DB-28 | Test: export, wipe DB, import; all counts equal; Flow JSONs equal; credentials absent unless passphrase given; wrong passphrase fails cleanly. |
| T13 | Binary GC and quota: record file names in the Run (or a small table), delete files of pruned Runs, global byte budget, checksum check on load. | DB-10 | Test: create 3 runs with files; prune the oldest; its file is deleted; total size over budget triggers eviction. |
| T14 | Move the Table Step and Dedupe state onto Room (atomic upsert and increment), and add a Data Table Step that uses the existing Data Tables. Add row update and a transaction for `deleteTable`. | DB-15, DB-21 | Test: 50 parallel increments give exactly 50. A Flow reads a row written through the UI. |
| T15 | Version history UI: per-save revisions (coalesced 5 min), list, restore, prune to last 50 plus published. Store `publishedRevision` on the Run. | DB-32, DB-33 | Test: save 60 times; 50 remain plus the published one; restore creates a new draft equal to revision N. |
| T16 | Small cleanup batch: one-time legacy credential cleanup (DB-18), `@Synchronized` key creation (DB-24), unique variable key (DB-22), credential `updatedAt` (DB-23), `triggerData` persisted (DB-29), composite run index (DB-30, needs migration 6->7 and a migration test), Long-preserving JSON (DB-19), Flow JSON `"v"` and extras (DB-20), webhook secret via `CryptoBox` (DB-36), remove unused dependency (DB-37), vacuum policy (DB-35). | DB-18..24, DB-29, DB-30, DB-35..37 | One test per item; migration 6->7 test passes on emulator. |
| T17 | n8n JSON importer (read-only mapping with a report of unsupported nodes). | gap | Test: import three sample public n8n workflows (HTTP, Schedule, IF); Flow opens, validator passes, unsupported nodes listed. |

---------------------------------------------------------------------------------------------------

## 8. Not verified / needs device test

1. CursorWindow behaviour (DB-12). I did not run a Flow with a 2 MB+ row. On Android the window is about 2 MB; whether the query fails or retries depends on the OS version and on how Room reads the cursor. Test on an API 26 and an API 34 device: save a Flow with a 3 MB step text and open the home list.
2. Echo overwrite timing (DB-02). The code path is clear, but I did not measure how often a background run lands inside the 450 ms window. Test: edit a param on Flow A while Flow B finishes runs every second.
3. Cost of `pruneStepData` per Run (DB-08). I have no numbers. Seed 100k execution rows and 200k port rows, then time `runMetas()` and `usage()` on a mid-range phone.
4. Growth numbers (DB-07). The 200 MB estimate uses about 250 bytes per step in `runJson`; measure a real 20-step run.
5. Migration execution from real v1 to v5 installs. The SQL matches `6.json` (checked by script), but only a device upgrade proves it. There may be no v1-v5 installs in the field; the maintainer knows.
6. `JsonPrimitive(Double.NaN)` round trip. `toJson` can produce NaN or Infinity for doubles; I believe the tree parser reads it back, but did not run it. If it fails, `decodeFlow` returns null and the Flow becomes invisible (DB-16).
7. Keystore race (DB-24) and key invalidation after a device lock-screen change: not testable by reading.
8. Behaviour under OEM process kills during file writes (DB-15, DB-17).
9. Whether the AI Assistant and Agent features need to be seen by the engine (agent `isPublished` does nothing in `core/`): outside this audit, flagged for the AI audit.
10. The unit tests in `core/data/src/test` were read but not run. No Android SDK or Gradle run was done here.
11. SQLite version per Android release for `VACUUM INTO` and FTS5: stated from memory of Android releases; verify on target devices before building on it.

Housekeeping note: I ran one read-only `git log` by mistake while checking the schemas folder. It printed nothing and changed nothing.
