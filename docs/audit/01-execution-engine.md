# Audit 01: Execution Engine and Run Persistence

Scope: `core/engine` (FlowRunner, FlowValidator, Values), `core/work` (FlowEngine, FlowScheduler, FlowRunWorker), `core/model` (Run.kt, Flow.kt), `core/data` (Repositories, Database, JsonCodec), plus the logic Steps that shape execution (`core/nodes/LogicNodes.kt`, `CompositionNodes.kt`).
Benchmark: n8n's execution behaviour, described below only as behavioural requirements in our own words. No n8n code, identifiers or text are reproduced. All KaivoFlow code that results from this audit must be original.
Paths below are relative to `/home/claude/kaivoflow`. Note: the project was authored without a Gradle build (README "Verification status"), so every runtime claim here comes from reading the code, not from running it.

## 1. Summary

KaivoFlow has a sound core idea: a compiled DAG, one eagerly started coroutine per Step, port-level liveness, bounded concurrency, per-Step retry and timeout, frozen outputs. It is a decent foundation. It is not yet an automation engine you can trust unattended, for four reasons.

1. A Run exists only in memory until it ends. The record is built once, at the end (`FlowRunner.kt:214-229`) and saved once (`FlowEngine.kt:162`). Process death, a WorkManager stop, or a user cancel can lose the Run entirely, with no trace in history.
2. Events are dropped, not queued. A second event for a Flow that is already running (second SMS, second webhook) is discarded (`FlowEngine.kt:141-152`), and the worker reports failure.
3. Execution is batch-per-Step, not item-aware. Each executor receives the whole list and returns a list. There is no per-item execution, no item lineage, no per-item error, no "run once", no real loop. `Loop` only tags items (`LogicNodes.kt:241-246`).
4. Stored Runs are too thin to debug or replay. Inputs are not stored, outputs are cut to 20 items, trigger data is not stored, nothing prunes history, and a user cannot re-run from a Step.

Counts: 4 P0, 9 P1, 8 P2.

## 2. How it works today

### 2.1 Run lifecycle

1. `FlowEngine.run` (`core/work/.../FlowEngine.kt:133`) takes a per-Flow lock via `ActiveRunRegistry.tryAcquire` (`FlowEngine.kt:141`, `ActiveRunRegistry.kt:11`). If the Flow is already running it returns a synthetic `Canceled` RunRecord with id `skipped-...`, which is never saved.
2. `resolveEffectiveFlow` merges scoped variables into `flow.vars` (`FlowEngine.kt:68-75`).
3. `FlowRunner.run` creates an `Execution` (`FlowRunner.kt:72-77`). It compiles the Flow (validator plus topological sort ordered by canvas y then x, `FlowValidator.kt:190-211`). If compile fails it returns a Failed record without events beyond start/finish.
4. It picks exactly one Spark that owns the event (`FlowRunner.kt:162-187`). Other Sparks are marked Skipped. No matching Spark means a Failed Run.
5. `runDag` (`FlowRunner.kt:239-264`) starts one `async` per Step in topological order, all inside one `coroutineScope`, all under a single `withTimeout(flow.settings.executionTimeoutMs)` (default 15 min, `Flow.kt:282`, `FlowRunner.kt:190-192`). Each Step first awaits its upstream Deferreds (`FlowRunner.kt:280-282`), so ordering comes from awaiting, and each Step runs at most once per Run.
6. A Semaphore of `maxConcurrency` (default 3, `FlowRunner.kt:34`) bounds parallel Steps. The permit wraps `runWithRetries`, including the retry delay (`FlowRunner.kt:317`, `:550`).
7. After the DAG settles, if any hard failure produced error items and the Flow has `errorStepId`, the runner runs that Step once with all error items (`FlowRunner.kt:257-262`, `:616-666`).
8. Final status: any hard failure gives Failed, any soft (Continue) failure gives PartialSuccess, else Succeeded; a timeout gives Failed; a coroutine cancel gives Canceled (`FlowRunner.kt:191-211`). The RunRecord is assembled and returned.
9. `FlowEngine.persist` saves the record only if the matching save flag allows it (`saveSuccessfulRuns` or `saveFailedRuns`, `FlowEngine.kt:182-192`), then updates `lastRunStatus` on the Flow row.

### 2.2 Ordering, branching, merging

- Ordering: topological with awaiting. Concurrent siblings. Compile order is only a tie-breaker.
- Branching: liveness is per output port. A Step runs only if at least one incoming Stream's source port carried items (`FlowRunner.kt:301-304`). A Step whose collected input is empty is Skipped (`:308-310`). Skipped Steps emit nothing, so Skipped propagates downstream.
- Merge: input to any multi-input Step is the concatenation of upstream port outputs in Stream list order (`FlowRunner.kt:327-345`). `MergeNode` then re-derives the groups by looking up upstream outputs by Step name (`LogicNodes.kt:118-121`). Because the runner skips a Step when no upstream branch is live but runs it when any branch is live, Merge never waits for "both branches or neither"; it simply sees one group and returns it (`LogicNodes.kt:121`, fallback to `listOf(ctx.input)`). Modes: append, zip, multiply, combine (join on one field).
- Disabled Step: bypass, items pass through unchanged (`FlowRunner.kt:290-293`).
- Frozen output: replayed instead of executing unless `ignoreFrozen` or TestStep mode (`:312-315`). It applies in Production mode too, so a forgotten freeze silently changes a live scheduled Run.

### 2.3 Loops, batches, sub-flows, waits

- Loop (`LogicNodes.kt:209-247`): not a loop. The graph must be acyclic (`FlowValidator.kt:85-92`), and the Loop Step only chunks input and adds a `_loop` field to each item. Downstream Steps still receive all items together in one call. No per-batch execution, no "done" output, no accumulating state.
- Sub-flow (`CompositionNodes.kt:30-65`, `FlowEngine.kt:84-104`): calls `runner.run` recursively with `ignoreFrozen = true`, depth cap 8, items capped at 100 by default. The result is "the output of the last Succeeded Step with output in compile order", which is not the same as the child Flow's terminal Step (`FlowEngine.kt:99-103`). A failed or canceled child throws, so the parent's error policy applies. Child Runs are not persisted or linked to the parent, and the Flow-level execution lock is not taken, so a Flow can be called concurrently with itself.
- Wait (`LogicNodes.kt:250-271`): an in-process `delay` capped at 900 s, holding a concurrency permit and the Worker alive. Not durable.
- Stop (`LogicNodes.kt:274-306`): "success" mode just passes items through; "error" mode throws. It does not end the Run: sibling branches keep going.
- Cancellation: user cancel cancels the calling Job (`FlowEngine.kt:178-180`, `ActiveRunRegistry.kt:20-24`). Inside the runner the cancel is caught and converted into a Canceled status, so the exception is swallowed (`FlowRunner.kt:203-205`).
- Timeouts: whole-Run timeout (`FlowRunner.kt:192`), per-Step timeout (`:504-509`), Step timeout vs Run timeout disambiguated at `:536`.

### 2.4 Error handling

- `OnErrorPolicy` has Stop, Continue, Retry, ErrorFlow (`Flow.kt:288-299`).
- Retries: `attempts = maxAttempts` if `retry.enabled` or policy is Retry (`FlowRunner.kt:476-478`); exponential delay with a cap (`Flow.kt:310-314`); every Throwable is retried, including config errors.
- Continue: after retries, the Step emits one error item as its output, the Step is recorded Failed, the Run becomes PartialSuccess (`FlowRunner.kt:556-583`). The original input items are not carried through.
- Everything else goes through `hardFail` (`:587-614`), which records Failed, adds to `failureMessages`, and returns empty outputs plus one error item. The runner never aborts sibling branches; the Run simply ends Failed at the end.
- Error Step: gets all error items once, after the DAG settles, in the same coroutine scope as the Run. It is skipped if the Run timed out or was canceled (`FlowRunner.kt:257-262` is inside the timed `runDag`). Its output is not stored (`:641-649`) and its failure does not change Run status.
- There is no code difference between Stop and ErrorFlow: both route into the same error Step if one exists.

### 2.5 Storage

- Table `runs` (`Database.kt:53-65`): id, flowId, flowName, status, startedAt, finishedAt, trigger, error, itemsOut, and one `runJson` blob. Indexed on flowId and startedAt. Database version 3, `exportSchema = false`, and `fallbackToDestructiveMigration()` (`DataModule.kt:22`).
- `RunRepository.save` upserts the whole record (`Repositories.kt:173-188`). History screens decode `runJson` for every row in the list query (`Repositories.kt:167-171`).
- `JsonCodec.encodeRun` (`JsonCodec.kt:227-258`) writes per-Step: id, name, status, start, finish, counts, error, attempts, and at most the first 20 output items (`:250`). It does not write `triggerData`.
- `StepRun` (`Run.kt:29-42`) has no input items, no per-attempt records, no port-level outputs.
- `RunStatus` has `Queued` and `Running` but nothing persists them. `StepRunStatus.Pending` and `Waiting` are never produced by the runner.
- Retention: `pruneOlderThan` exists (`Repositories.kt:159`, `:192`) and has zero callers anywhere in non-test code. History grows without bound.

### 2.6 Background execution

- Instant events: `FlowScheduler.runNow` enqueues an expedited OneTimeWork with `RUN_AS_NON_EXPEDITED_WORK_REQUEST` (`FlowScheduler.kt:105-124`). Trigger items travel as JSON in WorkManager `Data` (`FlowRunWorker.kt:129-157`), which has a 10 KB limit.
- Schedules: `minutes`/`hourly` use PeriodicWork with UPDATE; other frequencies use a OneTime with initial delay, re-armed after each run by the Worker (`FlowScheduler.kt:72-98`, `FlowRunWorker.kt:66-74`). Constraint `requiresBatteryNotLow` is always on (`FlowScheduler.kt:72-75`).
- The Worker maps Failed and Canceled to `Result.failure`, never `retry` (`FlowRunWorker.kt:85-91`). Foreground promotion is opt-in per request (`FlowRunWorker.kt:47-49`) and the header comment admits long-run promotion and timeout handling are unimplemented (`:28`).
- The webhook server calls `flowEngine.runById` on its own `SupervisorJob` scope (`WebhookServer.kt:56`, `:147-148`), not through WorkManager.

## 3. Gaps vs n8n behaviour

Stated as requirements KaivoFlow should meet, in our words.

| Behaviour n8n provides | KaivoFlow today |
|---|---|
| Each Step is evaluated per item by default: the same Step config runs once for every input item, each item produces its own output item and its own error, and every output item records which input item it came from. A per-Step switch makes it run once on the first item only. | Executors take the whole list. No per-item evaluation, no lineage (`pairedIndex` exists in `FlowData` but is only copied by SubFlow, `CompositionNodes.kt:62`), no run-once flag. |
| A Step can be told to emit one empty item when it would otherwise produce nothing, so a downstream Step still fires. | An empty output always skips everything downstream (`FlowRunner.kt:304`, `:308`). No always-emit option. |
| Three failure modes per Step: stop the Run, continue passing the regular output, or continue on a separate error output port where failed items go while good items go to the normal port. | Stop, Continue (replaces the whole output with one error item, `FlowRunner.kt:556-583`), ErrorFlow (identical to Stop). No error port, no per-item split, no pass-through of original items. |
| Retry on failure with configurable tries and a fixed wait between tries; the Run keeps the final error. | Retry exists with exponential backoff but no jitter, retries every exception type, and holds a concurrency permit while sleeping. Docs claim jitter (`docs/ARCHITECTURE.md:67`) but `Flow.kt:310-314` has none. |
| A Flow can name another Flow as its error handler. That Flow is started with the failing Run's identity, the failing Step, the error and the trigger data, and it works for scheduled, webhook and background Runs too. | Error Step lives inside the same Flow and the same Run. Gets no stack, no trigger data, no link back. Cannot be a shared Flow. Skipped on timeout and cancel. |
| Loops are real: a loop Step feeds a batch into a branch, the branch connects back, and a "done" output fires after the last batch. The graph may contain cycles. | Acyclic only. `Loop` is a tagger. Iterating means one Step call with all items. |
| Merge waits for its inputs, has modes (append, match on key, by position, every combination, choose branch), and can be told what to do when a branch is empty. | Runs as soon as any live input exists, so a late branch is silently missing. Modes match roughly, but `combine` only does a left join on one field, `append` ignores branch order guarantees when one branch is dead. |
| Wait that survives restarts: resume at a time, after an interval, or when a webhook or form is called. The Run is stored in a waiting state and resumed later by the system. | In-process delay capped at 900 s, not durable. No waiting state. |
| Partial re-run: execute from a chosen Step using stored output of earlier Steps; run only the Step and its ancestors that lack data; pin data on a Step so tests use fixed output. Retry a failed execution from the failure point or from the start, using the original trigger data. | Only Test Step (one Step, caller-supplied input) and Freeze Output. Frozen output also applies in Production mode. Stored Runs hold no input and truncated output, so a Run cannot be re-run from a Step at all. |
| Execution modes with different semantics and different saving: manual test, production trigger, retry, sub-flow, error Run, waiting-resume. | Three modes: Production, Manual, TestStep. Sub-flow, retry and error Runs have no mode. |
| Execution data model keeps the whole data graph per Run (each Step's output for each Run index, per port, with timing, source link, error and status), the Run status, the start/stop times, the mode, retry link to the original, the waiting-until time. A user can open any Run and inspect every Step. | One JSON blob, Step records without inputs, outputs cut to 20, no port, no retry link, no waiting time. Trigger payload not saved. |
| Save settings per Flow: save on error, save on success, save progress (so a crash leaves a partial record), save manual runs. Global defaults plus Flow override. | Two flags only (`Flow.kt:283-284`), applied at the end. No progress save, no manual-run control, no global default. |
| Pruning by age and by count with a hard cap, plus separate handling of big binary payloads. Pruned in the background on a timer. | `pruneOlderThan` is never called. No count cap. No size cap. |
| Run limits: global timeout with a hard maximum, per-Flow override, concurrency limit across Runs, queueing of excess Runs. | Per-Flow timeout (any value), per-Run Step concurrency only. No cross-Run concurrency cap, no queue. Events for a busy Flow are dropped. |
| Stuck or crashed executions are detected on start and marked crashed or resumed. | Nothing. A Run that never finished has no row at all. |

## 4. Findings

Severity: P0 = data loss or wrong outcome in normal use; P1 = serious limitation or risk; P2 = polish or hardening.

### P0

| # | Finding | Evidence | Fix |
|---|---|---|---|
| P0-1 | Events are dropped when the Flow is already running. A second SMS, notification or webhook arriving during a Run vanishes. The webhook server and Worker see a Canceled "skipped" record that is not saved, so there is no trace. | `FlowEngine.kt:141-152`, `ActiveRunRegistry.kt:11`, `FlowRunWorker.kt:85-91`, `WebhookServer.kt:147` | Replace the "one Run per Flow" refusal with a durable per-Flow queue (Section 5.5). Policy per Flow: queue (default), allow parallel, drop-newest, drop-and-record. Record every dropped event as a visible Run with status Skipped. |
| P0-2 | A Run is invisible until it ends, and lost if the process dies. No row is written at start or per Step; `Running` and `Queued` statuses are never persisted. After process death or Worker stop there is no record of the attempt, partial outputs or error. | `FlowRunner.kt:214-231`, `FlowEngine.kt:162`, `Run.kt:8`, `Database.kt:53-65` | Insert a Run row (status Running, trigger data) before the first Step. Write each Step transition as it happens (Section 5.2). On app start, mark any Running row older than a lease as Crashed. |
| P0-3 | Cancelled or stopped Runs are never saved. The runner swallows the cancel and returns a record, but `persist` then runs in the already-cancelled coroutine, where `withContext` and `Mutex.withLock` throw immediately. User-cancelled Runs and Worker-stopped Runs therefore leave no history and no `lastRunStatus`. Caller cancellation is also never rethrown. | `FlowRunner.kt:203-205`, `FlowEngine.kt:154-170`, `:182-192`, `Repositories.kt:173` | Do the final status write in a non-cancellable block, or hand it to a repository scope that outlives the caller. Rethrow the cancellation after saving. With P0-2 this becomes a simple status update on an existing row. |
| P0-4 | Interrupted Worker is replayed from the start, contradicting the stated design rule, and the trigger payload is not preserved for a replay. WorkManager re-runs work that was killed with the process. The Worker returns `failure` on failed runs "to avoid replay" but has no protection against a kill, so a Flow that sent an SMS before the kill sends it again. Combined with P0-2 nothing records the first attempt. | `FlowRunWorker.kt:40-92` (comment at `:88-89`), `FlowScheduler.kt:105-124` | Make a Run a durable record with an idempotency key (the Run id passed in Worker input). On Worker start, if a Run with that id exists and is not finished, apply the recovery policy (Section 5.6): resume after the last completed Step using stored outputs, or mark Crashed and do not replay, per Flow setting. |

### P1

| # | Finding | Evidence | Fix |
|---|---|---|---|
| P1-1 | No item-level semantics. Steps process lists in one call; no item lineage; no per-item error; no run-once; no always-emit. Many nodes will behave differently from user expectation (e.g. an HTTP Step receiving 50 items). | `StepExecutor.kt:88-90`, `FlowRunner.kt:317`, `FlowData` `pairedIndex` unused | Add per-Step `ItemMode` (EachItem default for item-oriented Steps, WholeList, FirstItemOnly) and `emitIfEmpty`. Runner fans out EachItem, collects, and stamps lineage (Section 5.3). |
| P1-2 | Merge does not synchronise. It runs when any input branch is live and falls back to one group; it recovers groups by Step name, which collides if two Steps share a name. | `LogicNodes.kt:118-121`, `FlowRunner.kt:132-133`, `:524`, `:301-304` | Give multi-input Steps a join rule (WaitAll, WaitAny, WaitAllLive). Key outputs by Step id; keep name as an alias. Pass the executor a map of input port to items instead of a flat list. |
| P1-3 | Loop is cosmetic and cycles are forbidden, so there is no way to iterate with state, paginate until done, or call a Step N times. | `LogicNodes.kt:209-247`, `FlowValidator.kt:85-92` | Introduce a Batch Step with two outputs (Next batch, Done) and allow one explicit back-edge into it. Validator allows a cycle only if it passes through a Batch Step. Add iteration cap and Run-wide Step-execution budget. |
| P1-4 | Error routing is weak. Continue discards the Step's good items and input; ErrorFlow equals Stop; the error Step is skipped on timeout and cancel, has no trigger data or Run link, its failure and output are not stored, and it cannot be a shared Flow. | `FlowRunner.kt:556-583`, `:257-262`, `:641-649`, `Flow.kt:288-299` | Four modes: Stop, ContinuePassThrough, ContinueErrorPort (extra output port holding failed items), Retry-then-X. Add `Flow.settings.errorFlowId` called as a separate Run (kind "error") with failing Run id, Step, error, trigger data. Run it from a non-cancellable finalizer. |
| P1-5 | Stored Run lacks what is needed to inspect or re-run. No input items, outputs cut to 20, no trigger payload, no per-port output, no attempts detail. A user cannot re-run from a Step or retry a failed Run. | `Run.kt:29-42`, `JsonCodec.kt:227-258` (take(20) at `:250`; `triggerData` not written) | Persist per-Step input and output by port with a size policy (Section 5.2). Persist trigger items. Add `RunRetry(of runId, fromStepId)`. |
| P1-6 | History is unbounded and expensive. `pruneOlderThan` has no caller; no count or size cap; list queries decode every full `runJson`; item JSON can contain big base64 bodies (HTTP binary mode). | `Repositories.kt:159`, `:167-171`, `:192`, `Database.kt:144-161` | Split summary columns from detail rows; list from summary only. Add retention (age, count, total MB) executed on app start and after each Run, plus a periodic job. Offload large payloads to files with a reference. |
| P1-7 | Frozen output applies in Production mode, so a forgotten freeze silently makes a live scheduled Run replay old data with Status "Succeeded". | `FlowRunner.kt:312-315`, `Run.kt` (Frozen status), `RunTrigger.ignoreFrozen` unused by the worker | Honour pins only in Manual and TestStep modes by default; show a banner and mark Run "used pinned data" if enabled for Production. |
| P1-8 | Wait is not durable and holds a permit; it runs in-process up to 900 s, so a Wait beyond the Worker's lifetime is impossible and a killed Run loses it. Sparks like "resume on webhook" or "resume tomorrow" cannot be built. | `LogicNodes.kt:250-271`, `FlowRunner.kt:317`, `Run.kt:10` (Waiting unused) | Implement suspension (Section 5.6): a Step may return `Suspend(until / onEvent)`; the runner persists the Run as Waiting and releases everything; an AlarmManager/WorkManager job or an event resumes it. |
| P1-9 | Background execution has no long-run policy: expedited work has a short execution window; foreground promotion is opt-in and unimplemented for timeouts; default Flow timeout 15 min exceeds many OS limits; a stop mid-Run loses it (P0-3). Worker always returns `failure`, never `retry`, even for "OS stopped us". | `FlowRunWorker.kt:28`, `:47-49`, `:85-91`, `Flow.kt:282`, `FlowScheduler.kt:112` | Promote to foreground (data-sync type) automatically when a Run has network Steps or a Wait; handle `onStopped` by persisting state and returning `retry` with resume (P0-4). Set a hard global max timeout. |

### P2

| # | Finding | Evidence | Fix |
|---|---|---|---|
| P2-1 | `stepOutputs` stores only port 0 and is keyed by name; `$step["X"]` loses branch outputs and breaks on duplicate names. | `FlowRunner.kt:133`, `:511`, `:524`, `StepExecutor.kt:37-38` (doc says id, code uses name) | Key by id, keep a name index, store all ports. Validator rejects duplicate names or disambiguates. |
| P2-2 | Retry has no jitter, retries non-retryable errors, and sleeps while holding a concurrency permit. Doc says jitter. | `Flow.kt:310-314`, `FlowRunner.kt:317`, `:550`, `docs/ARCHITECTURE.md:67` | Add jitter, a retryable-error classifier (network, 429, 5xx, timeout), and release the permit while sleeping. Fix doc. |
| P2-3 | `Stop` does not stop; siblings continue after a hard failure; docs claim "halts execution immediately". | `LogicNodes.kt:274-306`, `FlowRunner.kt:252-255`, `docs/ARCHITECTURE.md:63-64` | Add Run-level `stopOnFirstError` (default on) that cancels sibling work and marks them Skipped; make Stop an explicit end-of-Run with chosen status. |
| P2-4 | Sub-flow returns "last succeeded Step with output in compile order", not the child's terminal result; child Runs not saved or linked; child can run concurrently with itself. | `FlowEngine.kt:84-104` (esp. `:99-103`), `CompositionNodes.kt:30-65` | Add an explicit Return Step (or "result Step" setting). Persist child Runs with `parentRunId`. Share the cross-Run concurrency policy. |
| P2-5 | Sub-flow items capped silently (default 100). | `CompositionNodes.kt:56-57` | Log and show truncation in the Run; raise default to the Flow item cap. |
| P2-6 | Trigger payload in WorkManager Data is limited (about 10 KB); larger SMS, share or webhook payloads fail to enqueue or truncate. | `FlowRunWorker.kt:129-157`, `FlowScheduler.kt:112-124` | Write the event to the durable queue (P0-1) and pass only an event id to the Worker. |
| P2-7 | `fallbackToDestructiveMigration` wipes flows, credentials and runs when the schema version changes; `exportSchema = false` hides schema history. | `DataModule.kt:22`, `Database.kt:247-248` | Write real migrations and export schemas before any release. (Cross-lane: flag to the data/security audits.) |
| P2-8 | Docs and code disagree: concurrency 4 vs 3; Stop/ErrorFlow semantics; jitter. Also `ErrorFlow` policy has no distinct behaviour. | `docs/ARCHITECTURE.md:62-67`, `FlowRunner.kt:34`, `Flow.kt:288-299` | Update docs when behaviour is fixed. Add tests that pin each documented guarantee. |

Note on verification (for the reviewer): in `FlowRunWorker.kt:66-74` the Worker calls `flowScheduler.schedule(flow)`, which cancels by tag and re-enqueues unique work with REPLACE, from inside the Worker whose own unique name may be the one being replaced. I did not trace `cancelSchedule`, so confirm on a device that re-arming from inside the running Worker is safe.

## 5. Target design (KaivoFlow terms)

Vocabulary: Flow, Step, Stream, Spark, Run. New terms: Pulse (one unit of data on a Stream, today `FlowData`), Ledger (the durable Run record), Gate (join rule on a Step), Park (a suspended Run).

### 5.1 Principles

1. A Run is a durable object from its first millisecond. Memory is a cache of the Ledger, not the source of truth.
2. The engine is deterministic given (Flow snapshot, Spark event, Step outputs so far). That is what makes resume, retry-from-Step, and test-from-Step the same operation.
3. Every Spark event is captured before work is attempted. Never drop; if you must drop, record why.
4. Pure Kotlin in `:core:engine`; Android adapters only for scheduling, foreground promotion and notifications.

### 5.2 The Ledger (storage)

Replace the single `runJson` with normalised tables (names are KaivoFlow's own):

- `runs`: id, flowId, flowRevision (see snapshot below), status, mode, spark kind, sparkStepId, startedAt, finishedAt, queuedAt, parkedUntil, retryOfRunId, parentRunId, errorSummary, stepCount, itemsOut, byteSize, lastHeartbeat, leaseOwner.
- `run_events` (Spark payload): runId, items (JSON or file ref). Saved before the first Step.
- `step_runs`: runId, stepId, stepName, sequence, status (Pending, Running, Waiting, Succeeded, Failed, Skipped, Frozen, Bypassed), attempt, startedAt, finishedAt, error, errorKind, itemsIn, itemsOut, portCounts.
- `step_data`: stepRunId, direction (in or out), port, itemsJson or blob ref, truncated flag, byteSize.
- `flow_revisions`: flowId, revision, flowJson. A Run points at the exact Flow it executed, so a Run can be replayed after the Flow has been edited.

Write rules:
- Insert `runs` row (Running) and Spark data before the first Step.
- Per Step: insert `step_runs` on start; update on finish; write `step_data` in the same transaction. Batch writes for fast Steps to avoid write amplification.
- Heartbeat every N seconds while a Run is active.
- Final status written in a non-cancellable section.

Save policy per Flow (with global defaults): save on success, on error, manual runs, and progress (steps as they finish). Data policy: full, outputs only, counts only, none. Large payload policy: items above a size go to files under app storage and are referenced; binary stays a `BinaryDataRef`.

Retention: a `RunJanitor` that enforces max age, max Run count per Flow, max total bytes (global), deleting oldest first, and file blobs with their rows. Runs at app start, after each Run, and from a daily periodic job. Never delete Running or Parked Runs.

List screens read only `runs` plus counts.

### 5.3 Engine semantics

- Item mode per Step (`ItemMode`): `EachItem`, `WholeList`, `FirstItemOnly`. The catalog sets a sensible default per node type. The runner fans out EachItem calls (bounded), re-assembles output in input order, and sets lineage on every output Pulse (source Step, source item index). Errors are per item.
- `emitIfEmpty` flag on a Step: output one empty Pulse when the Step would produce none.
- Failure modes per Step: `Stop`, `ContinuePassThrough`, `ContinueErrorPort` (adds a final output port that carries failed Pulses with error details; good Pulses use the normal ports), and retry settings (tries, base wait, backoff, jitter, retry-on classifier). Retry sleeps without holding a concurrency permit.
- `stopOnFirstError` Run policy (default on): on the first hard failure the runner cancels sibling work, marks them Skipped, and finalises. Optionally "let branches finish".
- Gates: each multi-input Step declares `WaitAll` (default for Merge), `WaitAllLive`, or `WaitAny`. Executors receive `Map<inputPort, List<Pulse>>`, not a flat list.
- Batch Step with `Next` and `Done` outputs plus one permitted back-edge. Validator allows only cycles that pass through a Batch Step. Per-Run budget: max Step executions and max iterations per Batch.
- Return Step for Sub-flows. Child Runs are real Runs with `parentRunId`. The call result is the Return Step's input.
- Pins (replacing Production-time Freeze): applied in Manual and TestStep only, unless a Run option says otherwise. Runs record `usedPins`.
- Error Flow: `Flow.settings.errorFlowId` starts a new Run (kind "error") with the failing runId, Step, error text and Spark payload. Started from a finaliser that survives cancel and timeout.
- Timeouts: per-Step and per-Run, with a global hard maximum from app settings. Timeout errors are typed so retry and error routing can treat them separately.
- Run concurrency policy per Flow: `Queue` (default), `Parallel(n)`, `DropNewest`, `DropAndRecord`. A global cap protects the phone.

### 5.4 Partial re-run and inspection

- `Replay(runId, fromStepId, mode)`: load the Flow revision from the Run, seed `stepOutputs` with stored output of every Step that is an ancestor of `fromStepId` and not re-executed, and run from there. Missing or truncated data triggers a clear message and an option to re-run the ancestors.
- `RetryRun(runId, fromFailure | fromStart)`: same machinery. Marks `retryOfRunId`.
- `TestFromStep`: same machinery with live data allowed for ancestors lacking stored output.
- Inspector reads `step_data` for any Run; shows per-port items, input vs output, errors, attempts, timings, lineage.

### 5.5 Event intake and queue

- `SparkInbox` table: id, flowId, sparkStepId, kind, payload (or file ref), receivedAt, state (Pending, Claimed, Done, Dropped), dedupeKey.
- Every receiver (webhook server, SMS, notification, share, geofence...) writes to `SparkInbox` first, then pokes the `RunDispatcher`. WorkManager Data carries only the inbox id.
- `RunDispatcher` (in `:core:work`) claims inbox rows, applies the Flow's concurrency policy, and starts Runs. Dropped events get a Run row with status Skipped and a reason so the user sees them.
- Webhook server returns 202 once the inbox write succeeds; optional "respond with Flow result" mode waits for the Run (n8n-style respond behaviour) with a timeout.

### 5.6 Resilience on Android

States: `Queued`, `Running`, `Parked`, `Succeeded`, `Failed`, `Canceled`, `Crashed`, `PartialSuccess`, `Skipped`.

- Lease and heartbeat: a Running Run holds a lease refreshed by heartbeat. On app start, Worker start and BootReceiver, `RunRecovery` finds Running Runs whose lease expired and applies the Flow's `recoveryPolicy`:
  - `MarkCrashed` (default): status Crashed, keep all stored step data, notify, offer "Retry from failure".
  - `ResumeIdempotent`: resume from the first Step without a finished `step_runs` row. Only for Flows whose Steps are marked safe to repeat, or when the interrupted Step is marked idempotent in the catalog.
  - Never auto-replay a Step that has side effects (SMS, HTTP POST, notifications) unless the user opted in.
- Step side-effect class in `NodeSpec`: Pure, ReadOnly, SideEffect. Drives recovery, retry defaults and warnings.
- Park (durable Wait): a Step returns `Suspend(resumeAt | resumeOnEvent(key))`. The runner writes Parked plus resume condition, releases the permit and the Worker. Resume is driven by an exact-alarm or WorkManager job for time, and by the `SparkInbox` for events (a "resume token" webhook path). Resuming loads the Ledger and continues; no process memory is required.
- Worker contract: `doWork` receives a runId or inbox id, never replays blindly. `onStopped`: write status (Parked-or-Queued for resumable, Crashed otherwise) in a non-cancellable block and return `retry` with the same id for a bounded number of attempts. Promote to foreground (data-sync) automatically when a Run is expected to be long. Handle the foreground-service time limits on recent Android versions by parking the Run.
- Doze and OEM killers: periodic and daily Sparks stay on WorkManager; add an optional exact-alarm path for users who grant it. Record "late by N minutes" on the Run when a schedule fires late, so lateness is visible, not silent. A boot and app-start catch-up policy per schedule Spark: `SkipMissed`, `RunOnceIfMissed`.
- Database: real Room migrations and exported schemas before the Ledger ships.

## 6. Suggested phase tasks

Phase A: stop losing things (P0)
1. Create `runs`/`step_runs`/`step_data`/`run_events` tables with migrations; write Run row and Spark payload at Run start; write Step rows incrementally. (P0-2, P1-5)
2. Non-cancellable final status write; rethrow cancellation after finalising; set `lastRunStatus` from the Ledger. (P0-3)
3. `SparkInbox` plus `RunDispatcher` with queue and concurrency policy; make every receiver write to the inbox; Worker input is an inbox or Run id. (P0-1, P2-6)
4. `RunRecovery` on app start, Worker start and boot: lease/heartbeat, `Crashed` status, "Retry from failure" action; Worker `onStopped` handling. (P0-4)
5. JVM tests: kill-mid-run recovery (simulate by leaving a Running row), cancel persists, queued second event runs after the first.

Phase B: retention and inspection
6. `RunJanitor` (age, count, bytes) plus blob store for large payloads; list from summary only; remove full-JSON decode from list queries. (P1-6)
7. Save/data policies per Flow with global defaults. (Section 5.2)
8. Run inspector reads `step_data` (inputs, outputs by port, errors, attempts, timings).
9. `flow_revisions` snapshot per Run.

Phase C: engine semantics
10. `ItemMode`, lineage stamping, `emitIfEmpty`; migrate catalog defaults node by node. (P1-1)
11. Failure modes: ContinuePassThrough, ContinueErrorPort, per-item errors; retry classifier with jitter and permit release. (P1-4, P2-2)
12. Gates for multi-input Steps and id-keyed outputs with port-aware executor input. (P1-2, P2-1)
13. `stopOnFirstError`, explicit Stop/Return semantics. (P2-3, P2-4)
14. Batch Step with back-edge, validator rules, iteration and execution budgets. (P1-3)
15. Error Flow as a separate Run. (P1-4)
16. Pins limited to Manual and TestStep; mark Runs that used pins. (P1-7)

Phase D: durable waiting and replay
17. `Suspend` result type, Parked status, time and event resume; replace `WaitNode`. (P1-8)
18. `Replay(runId, fromStepId)` and `RetryRun`; "Run from here" in the inspector.
19. Foreground promotion policy and Android time-limit handling; late-fire and missed-schedule policy. (P1-9)
20. Replace destructive migration with real migrations; update `docs/ARCHITECTURE.md` to match behaviour. (P2-7, P2-8)

Test strategy: keep engine logic pure so every item above has JVM tests using the existing `TestSupport.kt` fakes (`core/engine/src/test`). Add a fake clock and fake Ledger for crash-recovery tests. Existing tests (`FlowRunnerTest.kt`, 344 lines) cover happy paths; none cover cancel persistence, concurrency drop, or crash recovery.
