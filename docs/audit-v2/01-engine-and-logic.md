# Audit v2, 01: Execution engine and logic

Area: core/engine, core/work, core/model (Run, Item, Flow), flow-control Steps.
Branch: claude/phase-4-base. Read-only audit. No source file was changed.
n8n reference: 2.48.6 at /tmp/ref/n8n. Behaviour only, described in my own words.

---

## 1. Scope and files read

### KaivoFlow, read completely
| File | Lines |
|---|---|
| core/engine/.../FlowRunner.kt | 895 |
| core/engine/.../StepExecutor.kt (StepContext, StepResult) | 270 |
| core/engine/.../FlowValidator.kt (validator + FlowCompiler) | 223 |
| core/engine/.../Lineage.kt | 135 |
| core/engine/.../FlowInvoker.kt, ActiveRunRegistry.kt, Errors.kt, RunRetention.kt | small |
| core/engine/.../alwayson/RunDispatcher.kt, InboxCore.kt, ScheduleSlots.kt, Basics.kt, WakeLocks.kt (policy part) | ~900 |
| core/engine/.../SchedulePlanner.kt | 40 |
| core/model/.../Flow.kt, Item.kt, Run.kt | ~560 |
| core/work/.../FlowEngine.kt, FlowRunWorker.kt, RunNotifications.kt | ~650 |
| core/work/.../alwayson/Reconciler.kt, RunHost.kt, ScheduleSync.kt, ScheduleFirer.kt, WakeLockManager.kt | ~1000 |
| core/nodes/.../flow/*: If, Switch, Merge, Loop (flow.loop), Wait, Stop, ErrorHandler, SubFlow, Noop | ~600 |
| core/nodes/.../kit/Conditions.kt | 135 |

### KaivoFlow, read in part (relevant slices only)
ActivationStateMachine.kt and ActivationManager.kt (lifecycle, first 120-140 lines), AlwaysOnService.kt (grep of lifecycle hooks), ScheduleMath.kt (clamp and interval constants), ScopeBuilder in Expression.kt (lines 68-143), CanvasViewModel.run (lines 470-520), StepBehaviour.kt, JsonCodec.encodeRun, Repositories.kt prune call.

### Not read (other agents own them, or out of scope)
Webhook server and processor, geofence/device event sources, reliability center data, OAuth, expression engine internals, all non-flow nodes, UI beyond the run path.

### n8n, read
- packages/core/src/execution-engine/workflow-execute.ts (main loop 2306-2760, retry 1857-1885, pinned 1893, timeout 2270-2290, paired-item stamping ~1796, waiting-node logic ~2700).
- packages/cli/src/wait-tracker.ts (waiting executions).
- packages/cli/src/execution-lifecycle/execute-error-workflow.ts (error workflows).
- packages/cli/src/concurrency/concurrency-control.service.ts (queue limits).
- packages/cli/src/executions/execution-crash.service.ts, execution-recovery.service.ts (crash handling).
- packages/nodes-base/nodes/Wait, SplitInBatches/v3, Merge/v3, If, Switch (headers and key behaviour only).
- Only skimmed: active-workflow-manager.ts, scaling/*, scheduled-task-manager.ts. I did not verify the details of those; the section 2 text about them is high level.

---

## 2. How n8n does it

### Execution loop
1. One run is a loop over an **execution stack**: a list of "node + input data" entries. Pop one, run it, push its children.
2. A node can run **many times** in one execution. Each run gets a run index. Loops in the canvas work because a node is simply pushed again when a loop connection delivers data to it.
3. An endless-loop guard stops the run when the same node and run index would be executed twice in a row.
4. Execution order "v1" (the default for new flows) is depth-first. Children of one node are sorted so the one more to the top-left runs first. The legacy order ran breadth-first. So n8n is **sequential**: one node at a time, in a stable order.
5. A node with several inputs (Merge) gets a "waiting" record. Each parent delivers into its own input slot. The node runs when all required inputs arrived. If the stack runs dry and some waiting nodes only need some inputs, they run then, one by one. A node declares which inputs are mandatory.
6. A child is only queued if the parent output feeding it has items. An empty output means "this branch did not run". The exception is a second optional input in legacy order.
7. Each node processes **all its input items in one call**, unless the node itself loops per item. Item-by-item order through a chain (A then B for item 1, then A then B for item 2) only happens inside a Loop Over Items cycle.

### Items and lineage
8. Before a node runs, every input item is stamped with where it came from (which parent item, which input). The node output keeps or rewrites that link. Expressions use it to answer "which input item made this output item".
9. A node can set "always output data": if it returns nothing, one empty item is emitted so the branch continues.
10. A node can set "execute once": only the first input item is processed.

### Error handling per node
11. Three modes: stop the workflow (default), continue with the regular output (error is put in the item), continue using a **separate error output** (a real second port on the node; failed items go there, good ones to the main port).
12. Retry on fail: 2 to 5 tries, wait 0 to 5000 ms between tries, clamped. Retries are fixed delay, not exponential. A cancel stops the retry wait. A node resuming after a failed sub-workflow is not retried.
13. If a node fails with "stop" the whole execution stops. Nothing else runs. Run status becomes error, with the failing node recorded.
14. Pinned data replaces node output only in manual runs. It is never used in production runs.

### Timeouts and cancel
15. A workflow timeout is stored as a wall-clock deadline. Each loop turn checks it. When it passes the run is marked cancelled and flagged as timed out. There is no per-node timeout in the core.
16. Cancel is a promise that can be cancelled. The retry sleep listens to an abort signal.

### Wait and resume
17. Wait node: short waits sleep in process. Long waits store a wake time on the execution, stop the run, and save the full run state to the database. Status is "waiting".
18. A leader process polls the database every 60 seconds for waiting executions and sets a timer for each. When the timer fires, the run is loaded from the database and resumes **at the Wait node's children**. Wait can also resume on a webhook or a form, with an optional time limit.
19. This is also how sub-workflows and AI agent tool calls park a parent and resume it.

### Error workflows
20. A workflow setting names another workflow as "error workflow". When a production run fails, n8n starts that workflow with a payload: execution id, link, error, last node, mode, workflow id and name. Manual runs do not trigger it.
21. Guard: if the error workflow itself fails and is its own error workflow, it is not run again.
22. If the failure happened in a trigger (no execution yet), the payload says trigger instead of execution.
23. A workflow that starts with an Error Trigger node acts as its own handler when no explicit one is set.

### Concurrency and queueing
24. A production concurrency limit (setting, default unlimited) puts extra executions in an in-memory queue. Manual and evaluation executions have their own rules. In scaling mode, workers pull jobs from Redis instead.
25. Webhook and trigger executions run in parallel with each other. There is no "one run per workflow" rule.

### Crash handling
26. On boot, executions that were "running" are marked **crashed** (a real status, not cancelled). Crashed runs are announced as events and counted in statistics.
27. A recovery service can rebuild partial run data from the event log of a crashed run. It can **auto-deactivate** a workflow after N consecutive crashes (setting).
28. Executions in "new" or "waiting" state are not lost across restarts. They are resumed.

### Loop node (Loop Over Items, v3)
29. It emits a batch on the "loop" output. The user connects the work back to the loop node's input. When all items are done it emits everything on "done". State is kept in node context. An empty input is a no-op.
30. This is a real cycle in the graph. It is the only way to run a chain per batch, or to do "repeat until a condition".

### Merge (v3)
31. Modes: append, combine by matching fields (inner, left, outer, keep unmatched, multiple matches option), combine by position, all combinations, SQL query, choose a branch. Field match has a type-strict option. The node can run when only one input has data.

### Edge cases worth copying
- Empty branch does not execute.
- Run status distinguishes success, error, crashed, waiting, canceled.
- Failed node data is kept so the user can inspect it.
- Retry wait is cancellable.
- Timeout is checked between nodes, so one slow node is not interrupted by the core.

---

## 3. How KaivoFlow does it today (verified)

### 3.1 Execution model
- The engine is a **DAG runner, not a stack loop**. `FlowCompiler.compile` (FlowValidator.kt:180-223) validates, then sorts steps topologically (Kahn, ties broken by canvas Y then X). Cycles are a blocking validation error (FlowValidator.kt:88-95), and self-streams are rejected in `Flow.plusStream` (Flow.kt:61).
- `runDag` (FlowRunner.kt:364-389) starts one `async` per Step, in topological order. Each Step first awaits **all** its upstream Steps (FlowRunner.kt:407-409), then takes a permit from a semaphore (default 3, `RunOptions.maxConcurrency`, FlowRunner.kt:52, 366, 456) and runs.
- **Every Step runs exactly once per Run.** `memo` is keyed by Step id (FlowRunner.kt:462). `runIndex` is always 0 (StepExecutor.kt:68-69, Item.kt comment on `ItemRef.runIndex`, FlowRunner.kt:545).
- Branches run in parallel, bounded by the permit count. Test: FlowRunnerTest "independent branches run concurrently" (line 197).
- Every Step gets **all items of the previous Step in one call**. There is no item-by-item walk through a chain.

### 3.2 Routing and liveness
- Liveness is per **output port** (FlowRunner.kt:422-432). A Step is skipped with reason "No items reached this Step" if no incoming stream has items. A Step with no incoming stream is skipped (line 424). Zero input items also skip (435-437).
- A disabled Step is a pass-through (FlowRunner.kt:417-420, 614-625). This matches what n8n users expect.
- A disabled or non-selected Spark is skipped (412-414). Exactly one Spark owns an event (274-299).
- If, Switch route by output port: If has ports 0/1 (IfNode.kt:25-26), Switch has 5 ports, last one is "else" (SwitchNode.kt:25-26).

### 3.3 Items and lineage (Paired Items exist)
- `Item` has `sources: List<ItemRef>` (Item.kt, `ItemRef(stepId, runIndex, itemIndex, port)`). The runner builds refs for every input item (FlowRunner.kt:498) and `Lineage.fill` infers links on output (Lineage.kt): keep valid step-set sources, then identical instance, then equal content, then count rules (same count means by position; one input means all from it; one output means from all). Otherwise left empty.
- `ScopeBuilder.build` walks `sources` back through finished Steps to answer `$step("Name")` per item (Expression.kt:105-137). The old docs claim Paired Items are missing. That is **wrong**.
- Limit: this is **inferred**, not declared by the node. Steps can set `sources` themselves (Merge and Loop do), but most rely on the count heuristic (see issue E-12).

### 3.4 Pinned data (exists)
- `PinReader`/`PinStore` used in manual runs only (FlowRunner.kt:440-449). Production runs do not use pins. This matches n8n.
- A second mechanism, `frozenOutput` ("Freeze Output"), is applied in **Production too** (FlowRunner.kt:450-454, only TestStep and `ignoreFrozen` bypass it). StepExecutor.kt:15-16 documents this as intended. See issue E-14.

### 3.5 Error handling
- Policies: `Stop`, `Continue`, `Retry`, `ErrorFlow` (Flow.kt:262-274). Retry is exponential with a cap, opt-in (Flow.kt:276-289). The inspector merges Retry into a switch (StepBehaviour.kt:20-60). Caps: 2 to 10 attempts, wait up to 300 s, backoff 1 to 5, timeout up to 3600 s (StepBehaviour.kt:21-26).
- Per-item failures are first-class: `StepContext.perItem` collects `ItemFailure`s (StepExecutor.kt:155-170). The runner decides by policy (FlowRunner.kt:681-697): Stop throws for the whole Step, Continue appends error items to port 0, ErrorFlow collects failed items and drops them from the output.
- Whole-Step failure after retries: Continue emits one error item and the Run becomes PartialSuccess (760-784). Otherwise `hardFail` (815-840): the Step is Failed, downstream is skipped because its port is empty, **sibling branches keep running**, and the Run ends Failed (314-318).
- Per-Step timeout: `withTimeout` around the executor (673-677). Distinguishes a Step timeout from a Run cancel (737-742).
- Run timeout: `withTimeout(timeout) { runDag() }` (311-313), default 15 minutes (Flow.kt:256).
- Stop Step: `StopSignal` cancels the scope (243-248). Status becomes **Canceled** unless something failed (324-329). Test: "the Stop step" runs are marked Canceled.
- Error Step: after the DAG settles, one dedicated Step (`settings.errorStepId`) receives all collected error items (382-387, 842-893).

### 3.6 Start, queue and crash safety (strong area)
- Every external trigger goes through a **durable Spark Inbox** (InboxCore.kt): persist first, then execute, with dedupe keys (`schedule:<flow>:<slot>`), lease 60 s renewed every 20 s (LeasePolicy), at-most-once start, and a crashed claim is marked Crashed, never replayed (RunDispatcher.kt:121-129, 195-228).
- `RunDispatcher`: one Run per Flow, in arrival order, global cap of 3 Runs (RunDispatcher.kt:52-56, 80-100). Busy flows get a back-off (RunDispatcher.kt:188-193).
- Two hosts, one rule set: foreground service loop (RunHost.kt:150-160) and a fallback WorkManager worker (FlowRunWorker.kt:55-85). Row claim is atomic, so a Run id never starts twice.
- Wake lock per Run = flow timeout + 30 s, capped at 15 minutes (WakeLocks.kt:9, 15-16). No standing lock.
- `FlowEngine.runOnce` (FlowEngine.kt:187-278): `ActiveRunRegistry` allows one Run per Flow (199-200). Manual double-start returns a Canceled record "already running" (205-215). Inbox start returns Queued.
- Process death: `recoverAndPrune` at app start rewrites Running/Queued rows of the old process as **Canceled** with an "Interrupted" message (FlowEngine.kt:289-299). The dispatcher later upgrades started claims to Failed with a "Crashed:" prefix (RunHost.kt:107-125). There is no separate status.

### 3.7 Scheduling (strong area)
- Pure slot math (ScheduleSlots.kt): DST-correct, per-calendar-date, interval schedules on a grid anchored at the last slot. Misfire policies Skip / RunOnce / RunAll (cap 5), max lateness 12 h or one interval (MisfirePlanner). Three precision modes: OnTime (alarm clock), Exact, Flexible (WorkManager). Exact degrades to Flexible when permission is denied, and it is recorded.
- Fire path arms next slot first, then writes the Inbox row with a slot dedupe key (ScheduleFirer.kt:49-95). The Reconciler repairs everything from the database on boot, time change, package replace, Pulse, watchdog (Reconciler.kt:60-150).
- Draft versus published: Spark Runs execute the **published revision** (RunHost.kt:42-46, `liveFlow`). Lifecycle: Draft, Published, Paused, Error with a revision log (ActivationStateMachine.kt:12-60).

### 3.8 Flow-control Steps (what each really does)
| Step | File | Real behaviour |
|---|---|---|
| If | IfNode.kt | Condition group (all/any), 20 operators, per item partition to two ports. |
| Switch | SwitchNode.kt | **4 cases + else**, string equality ignoring case, first match wins, one output per item. |
| Merge | MergeNode.kt | append, zip, multiply, combine on fields (left/inner/outer/unmatched), choose input. Sets lineage itself. |
| Loop | LoopNode.kt | **Not a cycle.** Chops the input into batches, tags items with `_loop` (batch, batches, first, last, index), or emits one item per batch. Safety limit 500 batches. One output only. |
| Wait | WaitNode.kt | `delay()` of 0 to 900 s plus jitter, **in process**. |
| Stop | StopNode.kt | Success or error. Success cancels the other work and the Run ends Canceled. |
| When a Step fails | ErrorHandlerNode.kt | Pass-through. The "Alert text" param is **never read**. |
| Run another Flow | SubFlowNode.kt | Calls `FlowInvoker`, up to 5000 items, depth 8. |
| Pass through | NoopNode.kt | Adds an optional note field. |

---

## 4. Issues found

Severity: P0 = data loss or wrong business result with no warning on a common path. P1 = serious, fix before wide release. P2 = real but contained.
**P0 count: 0. P1 count: 14. P2 count: 18.**

| ID | Sev | Where | What | Why it matters | Fix idea |
|---|---|---|---|---|---|
| E-01 | P1 | feature/canvas/.../StepBehaviour.kt:16, 30, 40; FlowRunner.kt:382-387; no UI sets `FlowSettings` (grep over feature/ and app/ finds only codecs, bundles, SelfTest) | The "Error output" choice sends failed items to `settings.errorStepId`. **No screen can set `errorStepId`**, and the "When a Step fails" Step can't be bound. With no error Step, failed items under `ErrorFlow` are simply dropped (item-level, FlowRunner.kt:683, 688-689) and the Run shows PartialSuccess. | The label promises an error output. The user gets silent loss of failed items. This is the main error-handling feature of the product. | Short term: make "Error output" create a real second output port on the Step (n8n style) and route `failedItems` to it. Long term: drop `errorStepId` or keep it only for a flow-wide handler. |
| E-02 | P1 | FlowSettings has no UI (Flow.kt:251-260) | Run timeout, max items per Step, history saving, timezone and error Step cannot be changed by the user. Only defaults apply. | A 15-minute timeout and a 5000 item cap are hard limits nobody can raise or lower. Retention toggles are dead. | One "Flow settings" sheet bound to `FlowSettings`. |
| E-03 | P1 | FlowRunner.kt:683-685, 749-755 | Retry re-runs the **whole Step** when any item failed under Stop policy. Items that already succeeded (messages sent, rows written) run again. | Duplicate Telegram messages, duplicate posts. This is the classic retry bug. | Retry only the failed item indices (pass a `retryOnly` index set in `StepContext`), or only retry when `failed.size == input.size`. At least log a warning in the inspector and document it. |
| E-04 | P1 | IfNode.kt:32; SwitchNode.kt:43; FilterNode.kt:25 | These call `ctx.scope()` (item 0) and only overwrite `json`. `$index`, `$item`, `$step("X")` and paired-item lookups resolve against **item 0 for every item**. Other nodes use `ctx.scope(index)` correctly. | A condition like `{{ $step("Lookup").price > 10 }}` silently routes all items by the first item's data. Wrong branch, no error. | Build the scope per item index (`ctx.scope(i)`) inside the loop. One-line change per node. Add a test with two items and a `$step` condition. |
| E-05 | P1 | Conditions.kt:51 | The right-hand value is never resolved. Only `left` goes through the evaluator. A right value `{{ $json.limit }}` is compared as literal text. | Common pattern "compare two fields" does not work in If, Filter. Result is false with no error. | Resolve `right` and `right2` the same way as `left` when they contain `{{`. Need a device check whether the inspector lets users type an expression there (not verified). |
| E-06 | P1 | LoopNode.kt:50-70; FlowValidator.kt:88-95 | `flow.loop` is a batch tagger. The graph must be acyclic, so there is no "do A then B per batch", no "repeat until", no pagination, no accumulate. The hint "keep at 1 when the next Step is rate-limited" (LoopNode.kt:28-31) is misleading: the next Step still receives all items at once. | Rate-limit friendly processing and pagination are the top n8n loop use cases. Users will expect them. Old docs wrongly say the Loop is missing. It exists but it is not a loop. | See task T-07: a per-batch sub-run (run the downstream chain per batch, sequentially) without opening general cycles. |
| E-07 | P1 | WaitNode.kt:30-33; FlowRunner.kt:456 | Wait is `delay()` inside the Run. It holds one of 3 permits, dies with the process, and is capped at 900 s (the Run limit is 900 s too). No wait until a date, no wait for approval or webhook. | "Remind me tomorrow" or "wait for my reply" flows are impossible. A wait that spans a kill is lost (the Run is marked Interrupted). | Durable Wait, see T-09. Run state is only finished step outputs, so this is cheaper here than in n8n. |
| E-08 | P1 | FlowEngine.kt:116-141 (`subFlowInvoker`), line 122 | Sub-Flow loads the child with `flowRepository.flow(id)`: the **draft**. Spark Runs use the published revision (RunHost.kt:42-46). A published parent runs unpublished child edits. | Editing a child in the canvas changes live production behaviour with no publish step. This breaks the publish promise. | Resolve the child through `LiveFlows.liveFlow(id)` for Production and Spark parents, fall back to draft only for Manual. Show a warning in the parent if the child is not published. |
| E-09 | P1 | FlowEngine.kt:289-299; RunStatus enum (Run.kt:8) | A Run killed by process death is stored as **Canceled** "Interrupted", the same as a user cancel. Only an Inbox-started Run is later upgraded to Failed "Crashed:" (RunHost.kt:107-125). A manual Run is not. No failure notification fires. | The user can't tell "I pressed stop" from "the phone killed my flow". Run lists and health scores are wrong. | Add `Crashed` (and `Waiting`) to `RunStatus`, one Room migration, map "Interrupted" to Crashed. |
| E-10 | P1 | FlowRunner.kt:842-893 | The error Step is run outside the normal path: no permit, no timeout, no retry, not stored in the Step data store (no `recordStep`), its outputs are ignored. If it fails it does **not** fail the Run (line 891). It does not run on Run timeout, cancel, Stop, or process death (exceptions skip it, lines 319-335). Run timeout is the most common reason to want an alert. | An alert handler that fails silently is worse than none. A timeout or crash gives no alert at all. | Run it in a `finally` path under `NonCancellable` with its own short timeout, record it, and add its failure to `softFailures`. Pass a `trigger` payload (flow, run id, error, last Step), like n8n. |
| E-11 | P1 | FlowEngine.kt:302-337; RunRetention.kt:11 | `MAX_RUNS_PER_FLOW = 500` is **never enforced** (grep: only the constant). Age cap is 30 days. `pruneHistory()` (age delete plus a full step-data scan, `RunRetention.pruneStepData`) runs after **every** Run, under the global `runLock`. | A flow that fires every minute stores up to 43,200 Runs. Each finished Run does a global scan. This degrades Room on a phone over weeks. | Enforce the per-flow cap in SQL. Prune at most every N minutes or N Runs, off the Run path. |
| E-12 | P1 | Lineage.kt:104-115 (count heuristic) | Lineage falls back to "same count means same position". A Step that sorts, reorders or filters-and-adds can get **wrong links** with no sign. | `$step("X")` in later Steps can return the wrong source item. Silent. | Let `perItem` set sources by default (it already does, StepExecutor.kt:162). Mark heuristic links (`inferred = true`) and show it in the inspector. |
| E-13 | P1 | FlowRunner code runs on the caller's dispatcher; CanvasViewModel.kt:493 inside `viewModelScope.launch`; FlowEngine.kt has no `withContext` | Manual Runs and Test Step execute Step CPU work (lineage over 5000 items, Merge, expression eval, Code Step) on the **main thread** when started from the canvas. `async` children inherit it. Only network I/O hops off. | Jank or an ANR on large item lists. A Step timeout cannot interrupt a CPU loop on the main thread. Needs a device test for severity. | In `FlowEngine.run` and `testStep` wrap with `withContext(Dispatchers.Default)`. |
| E-14 | P1 | FlowRunner.kt:450-454; StepExecutor.kt:15-16 | `frozenOutput` is replayed in **Production** Runs. A frozen test result stays live after publish. | A user freezes an HTTP Step to test, publishes, and the live flow keeps replaying old data. n8n never does this. | Apply `frozenOutput` only in Manual mode (as pins do), or show a red "Frozen" badge on the published Flow and block publish with frozen Steps unless confirmed. |
| E-15 | P2 | FlowRunner.kt:735-745, 750-755 | Failed whole-Step error items for `ErrorFlow` come from `hardFail` for any policy (line 839). So even `Stop` Steps feed the error Step. | Probably intended, but undocumented. Can cause double alerts if a user also uses Continue. | Document or gate on policy. |
| E-16 | P2 | FlowRunner.kt:360-389, test "failing step stops the run" (FlowRunnerTest:89) | A failed Step with `Stop` policy does not stop the Run. Sibling branches continue to the end. Docs on `OnErrorPolicy.Stop` say "Abort the Run" (Flow.kt:263). | Side effects after a failure (email sent after an earlier branch failed). Comment and behaviour disagree. | Decide. Option: setting "Stop everything on first failure" per Flow, default on. |
| E-17 | P2 | StopNode.kt:50; FlowRunner.kt:324-329 | Stop "success" yields RunStatus **Canceled**. The notification says "was stopped". A webhook waiting for a result gets status "Canceled". | A deliberate early exit looks like a failure or cancel. | New status or map to Succeeded with a `stoppedBy` field. |
| E-18 | P2 | FlowRunner.kt:456 | Retry delay and Wait both hold a semaphore permit. With the default of 3, three waiting Steps starve everything else. | Slow flows when using Wait or backoff. | Release the permit during `delay` (wrap the delay outside `withPermit`). |
| E-19 | P2 | FlowEngine.kt:161-162, 228 | `_events` StateFlow is written on every event but nothing reads it (grep `.events` over feature, app: none; the canvas uses the callback, CanvasViewModel.kt:493-496). StateFlow also conflates and would drop events. | Dead code that allocates per event and would mislead a future consumer. | Remove, or convert to a `SharedFlow` with replay if the Reliability UI needs it. |
| E-20 | P2 | RunNotifications.kt:41-56, 72-90 | The class comment says Runs under 10 s show no notification and silent Runs are removed. Code posts a "Starting" ongoing notification on **every** Run start and a result notification (default importance) on **every** finish, success included. Notification id is a 12-bit hash of the flow id (line 69-70). | A flow every minute produces 1440 notifications a day. Two flows can share an id and overwrite each other. | Implement the 10 s threshold and per-flow "notify on failure only" (default). Use a stable int id. |
| E-21 | P2 | FlowEngine.kt:122-141; RunHost.kt:78 | The sub-flow result and the webhook "wait for run" response both use "the last Step in **completion order** that Succeeded with output". With parallel branches the answer depends on timing. | Non-deterministic response. | Define terminal Steps from the graph (no outgoing stream), then take the one latest in topological order, or add a "Return" Step. |
| E-22 | P2 | FlowEngine.kt:127-130 | Sub-flow Runs are not persisted and not linked to the parent. Only the failure message is passed up. | Hard to debug a failing child. | Persist as hidden child Runs with `parentRunId`. |
| E-23 | P2 | ScheduleMath.kt:84; ScheduleSlots.kt `nextCalendarSlot` | Monthly day is clamped to 1 to 28. No "last day of month". `hourly` always means 60 min (`interval` ignored). Calendar and interval units are truncated to whole minutes. No cron, no seconds. | "Every 2 hours" or "last day of month" can't be expressed. | Add `everyNHours`, `lastDay`, and a cron expression field (the slot engine is pure and easy to extend). |
| E-24 | P2 | WakeLocks.kt:15-16 | Wake lock is capped at 15 min. A Flow timeout set higher (UI allows up to 3600 s for a Step, StepBehaviour.kt:26) runs without a wake lock after minute 15. | Long Runs may freeze in Doze. | Hold the lock through the foreground service for long Runs, or cap flow timeout at 15 min in UI. |
| E-25 | P2 | FlowRunner.kt:311-313 | `withTimeout(timeout)` with `timeout <= 0` times out immediately (also step timeout, line 673). Flow setting can't be set from UI today, but imports can. | Instant failure for bad data. | `coerceAtLeast(1000)`. |
| E-26 | P2 | FlowRunner.kt:679-731 | Event callbacks (`onEvent`) are called **inside** the try block. If a callback throws, the Step is treated as failed and **retried**, running its side effects again. `stepRuns += record` also happens before, giving duplicate records. | Rare, but a UI bug can duplicate real actions. | Move `onEvent` calls after the try, or wrap in `runCatching`. |
| E-27 | P2 | Conditions.kt:40-44, 85-93; MergeNode.kt combine | `Operator.fromKey` maps an unknown operator to Equals silently. Regex has no timeout (ReDoS). Merge combine joins on text, so a missing key matches every other missing key. Only the first match is used (no multiple matches). | Silent wrong condition, hung Run, wrong joins. | Fail on unknown operator. Skip null keys in combine. Use a bounded regex helper. |
| E-28 | P2 | SwitchNode.kt:33-46 | Switch: max 4 cases, string compare only, case values are not expression-resolved, the "choose a value" check runs after the loop (line 46). No "send to all matching" mode. | Cannot route by number ranges or more than 4 values. | Reuse `ConditionGroup` per case, allow N cases, add "all matches" option. |
| E-29 | P2 | ActivationStateMachine.kt:25, 92 | `LifecycleReason.AutoPaused` and the "failed-Runs rule" exist as names only. Nothing pauses a Flow after repeated failed Runs. The `Error` lifecycle state means arming failure, not Run failure. | A broken flow runs and fails forever, burning battery and filling history. n8n has this guard for crashes. | Pause after N consecutive Failed/Crashed Runs (setting), notify once. |
| E-30 | P2 | FlowRunner.kt:475-478, 501-503 | When a Step receives more than `maxItemsPerStep` (5000), it **drops the rest** and only logs a warning. Run status stays Succeeded. | Silent data loss in production for big lists. | Mark the Run PartialSuccess with a clear reason, or fail by default. |
| E-31 | P2 | ErrorHandlerNode.kt:25 | The "Alert text" param is never read. The Step does nothing. | Users who fill it in will never get an alert. | Send a notification with the interpolated text (the channel code already exists in RunNotifications). |
| E-32 | P2 | FlowRunner.kt:94, 118 | Recorder flush after a Run is bounded to 5 s. A very large run may lose its tail of step data. | Inspector shows an incomplete Run. | Accept, but record "step data incomplete". |

(Counts: P1 = E-01 to E-14. P2 = E-15 to E-32.)

---

## 5. Gap vs n8n

| n8n capability | KaivoFlow status | Effort | Value for users / business |
|---|---|---|---|
| Node runs many times in one execution (run index) | **Missing** (always 0, DAG) | L | Needed for true loops. Item model is already ready (`ItemRef.runIndex`). |
| Real Loop Over Items with loop-back and done output | **Partial** (tagger only) | M | High. Pagination, rate limits. |
| Separate error output port | **Missing** (dead-end `errorStepId`, E-01) | M | High. Core of reliable automation. |
| Continue on fail (regular output) | **Exact** | n/a | Done. |
| Retry on fail | **Exact+** (exponential backoff, cap) | n/a | Better than n8n. Add per-item retry (E-03). |
| Always output data | **Missing** | S | High. "If nothing found, tell me" flows. |
| Execute once | **Missing** | S | High on Android: one notification instead of one per item. |
| Workflow timeout | **Exact** (no UI, E-02) | S | Add UI. |
| Per-node timeout | **Exact** (better than n8n) | n/a | Done. |
| Pinned data (manual only) | **Exact** | n/a | Done. Freeze is the extra, risky one (E-14). |
| Paired items / lineage | **Partial** (inferred, E-12) | M | Works for common cases. |
| Wait: time, date | **Partial** (in-process, 900 s max) | M | High. |
| Wait: webhook / form resume, limit time | **Missing** | M | High. "Approval" flows. |
| Resume executions after restart | **Missing** (Interrupted, E-09) | M-L | High. Differentiator, see section 6. |
| Crashed status, crash events | **Partial** (text prefix on some paths) | S | Medium. |
| Auto-deactivate after crashes | **Missing** (E-29) | S | Medium. Saves battery. |
| Error workflow (another flow, with payload) | **Missing** (only in-flow error Step, weak, E-10) | M | High. |
| Error Trigger node | **Missing** | S | Pairs with the error workflow. |
| Sub-workflow with defined inputs/outputs | **Partial** (items in, last Step out, draft-only, E-08, E-21) | M | High for reuse. |
| Concurrency limit | **Partial** (global 3, one per Flow). Fine for a phone. | S | Make it a setting. |
| Parallel runs of the same workflow | **Missing by design** (one per Flow) | M | Webhook-heavy flows queue. Acceptable on a phone. |
| Execution order control (v1 top-left first) | **Partial** (topological by Y then X, but branches parallel) | S | Parallel is a plus. Offer "sequential" per Flow for predictable side effects. |
| Merge: modes | **Partial** (no SQL, no multiple-match, no type strict) | S-M | Medium. |
| Switch: many rules, ranges | **Partial** (E-28) | S | Medium. |
| If operators (dates, arrays, strict types) | **Partial** | S | Medium. |
| Retry or replay a failed execution from its data | **Missing** | M | High. "Retry this Run" button. |
| Run up to a chosen Step (partial execution) | **Missing** (Test Step only) | M | High for building. |
| Webhook "Respond to webhook" node | **Missing** (response mode on the Spark only) | M | Medium. |
| Schedule: cron, seconds | **Missing** (E-23) | S | Medium. |
| Queue mode / workers | **Not needed** | n/a | Single user, serverless. |

---

## 6. Where KaivoFlow can beat n8n

1. **Durable by design, cheap resume.** KaivoFlow already has a persisted Inbox with leases, at-most-once starts, and crash detection. Because a Run is a DAG where each Step runs once, the entire Run state is "finished Step outputs". After every finished Step, write a **checkpoint** (raw outputs, not the redacted step-data copy). After a process kill, resume by re-running the DAG and **skipping Steps that have a checkpoint**. n8n needs a whole execution-data blob and a leader. This is a few hundred lines here. Pay-off: "your phone rebooted and nothing was lost".
2. **Durable Wait using Android alarms.** Wait longer than N seconds: checkpoint the Run, write a "resume" Inbox row with a due time, arm an alarm through the existing `ScheduleSync` path (the slot engine, misfire policy and Reconciler already handle reboot and Doze). One mechanism also gives "wait until a time", "wait for a notification or SMS reply" (the Sparks exist), and "wait for approval" via a tap on a notification action. n8n waits on web forms. A phone can ask you with a notification button.
3. **Honest schedule evidence.** Expected-run records, lateness, misfire policy, degradations with explicit events. n8n does not tell you a cron missed. Keep, and surface in Run history as "ran 3 min late (Doze)".
4. **Per-item retry and safe retry.** n8n retries the whole node. Retry only failed items (E-03). Add idempotency tokens (`$run.id + item index`) that Steps like HTTP can send as a header. Sells reliability.
5. **Battery-aware execution.** "Run when charging", "run on Wi-Fi" flags exist for Flexible schedules (ScheduleSync.kt:armOne, wifiOnly, batteryNotLow). Extend to Wait resume and retry back-off.
6. **Parallel by default.** n8n is sequential. KaivoFlow already runs branches in parallel (3 permits). Keep it, add a per-Flow "sequential" switch for users who need predictable order.
7. **Run-level cost and battery report.** Wake-lock ledger already exists (WakeLocks.kt). Show per Flow: minutes of CPU, network bytes. No competitor shows this.
8. **Error workflow as a Flow plus a notification action.** An alert with "Retry now" and "Pause flow" buttons straight from the notification. Needs E-01, E-09, T-05.
9. **Live and draft separation done right.** Published revision snapshots exist. Finish the job (E-08) and offer "roll back to revision N" in one tap.

---

## 7. Recommended ordered tasks

Each task is small. Order is by value over effort, and by dependency.

| # | Task | Acceptance test |
|---|---|---|
| T-01 | **Fix scope per item** in If, Switch, Filter (E-04). Use `ctx.scope(index)`. | Unit test: 2 items, condition `{{ $step("A").v == $json.v }}` routes each item by its own pair. Fails today. |
| T-02 | **Resolve `right`/`right2`** in `Condition.evaluate` (E-05). Unknown operator throws (E-27). | `right = "{{ $json.limit }}"` with item `{amount: 5, limit: 3}` and operator Greater returns true. |
| T-03 | **Wrap runs in `Dispatchers.Default`** in `FlowEngine.run` and `testStep` (E-13). | Test with a fake main dispatcher asserts steps never run on it. Manual: 5000 item Merge does not drop frames. |
| T-04 | **Add `Crashed` and `Waiting` to RunStatus**, migration, map "Interrupted" to Crashed, include in notifications (E-09). | Kill the app mid-Run. After restart the Run shows Crashed. A user cancel still shows Canceled. |
| T-05 | **Run the error Step in a safe finally path** (E-10) with timeout, recording, and failure counted. Pass a payload (flow, run id, error, last Step). | Test: a Run that times out still calls the error Step once. A failing error Step marks the Run PartialSuccess, not silent. |
| T-06 | **Real error output port** (E-01). A Step with policy "Error output" gets output port N (last). Validator allows streams from it. Failed items go there. Keep `errorStepId` as a flow-wide fallback if set. | FlowRunnerTest: Step fails for item 2 of 3. Port 0 has 2 items, error port has 1 with the error. Canvas shows a second red output. |
| T-07 | **Per-batch chain for Loop.** New `flow.loop` mode "run the connected chain once per batch, sequentially, then continue on done". Implement as a sub-DAG run (the engine can run a sub-graph with the batch as trigger items) without allowing general cycles. | Loop of 6 items, batch 2: downstream HTTP mock sees 3 calls, in order, each with 2 items. "Done" port has all results. Safety limit still applies. |
| T-08 | **Flow settings sheet** bound to `FlowSettings` (E-02): timeout, max items, error Step, save-runs, timezone. Add `coerceAtLeast` (E-25). | Change the timeout to 60 s in UI. The next Run timeout message says 60 s. Reopen the app and the value persists. |
| T-09 | **Checkpoint plus durable Wait** (E-07, section 6 items 1 and 2). Step 1: checkpoint after each Step into a Room table, resume skipping done Steps. Step 2: Wait over 60 s writes a resume Inbox row plus an alarm. | Wait 2 minutes, force-stop the app during the wait. The Run resumes after about 2 minutes, runs only the Steps after Wait, one Run record, status Succeeded. |
| T-10 | **Sub-Flow reads the published revision** in Production/Spark mode (E-08). Add a warning if the child is a Draft. | Publish parent and child. Edit the child draft. Trigger the parent. It runs the old child. After publishing the child, it runs the new one. |
| T-11 | **Enforce run caps** and throttle pruning (E-11). Per-flow cap 500 in SQL, prune at most once per 10 minutes. | Insert 600 Runs for one Flow. After one prune, 500 remain and the newest are kept. A prune scan does not occur on each Run (counter test). |
| T-12 | **Safe retry** (E-03): retry only failed item indices, or fail without retry when partial. | Step with 3 items, item 2 fails, retry on. Items 1 and 3 execute once in total. |
| T-13 | **Apply `frozenOutput` only in Manual** (E-14). Publish sheet lists frozen Steps. | Production Run with a frozen Step executes it live. Manual Run replays. |
| T-14 | **"Always output data" and "Execute once" Step settings** (section 5). In `executeStep` replace skip with one empty item when the flag is on. | A Filter that removes all items followed by a Notify Step with the flag sends one notification. Execute once with 5 items sends one. |
| T-15 | **Notification policy**: failure only by default, 10 s threshold, stable ids (E-20). | A succeeding minutely flow produces no result notifications. A failing one produces one per N minutes. |
| T-16 | **Auto-pause after N failed Runs** (E-29), default 5 in a row, one notification with a Resume button. | Fail 5 Runs. The Flow state becomes Paused with reason AutoPaused. One notification only. |
| T-17 | **Error workflow** (another Flow with the payload) and an Error Spark. Setting per Flow. Skip for Manual Runs and for self-loops. | Flow A fails in production. Flow B (Error Spark) runs once with `{flow, run, error, lastStep}`. If B fails and is its own handler it does not retry itself. |
| T-18 | **Deterministic terminal output** for sub-flow and webhook response (E-21). | Parallel branches finishing in either order return the same payload. |
| T-19 | **Cleanups**: remove `_events` (E-19), implement or hide ErrorHandler alert text (E-31), move `onEvent` outside the try (E-26), mark truncation as PartialSuccess (E-30). | Each has a one-line unit test. |
| T-20 | **Schedule extras** (E-23): every N hours, last day of month, cron field. | `SlotMath` tests for cron `0 9 * * 1-5`, DST day, 31st in a 30-day month. |

---

## 8. Not verified / needs device test

1. **Main-thread execution (E-13).** Read from code (no dispatcher switch). Whether Ktor, Room or `perItem` hop threads inside nodes in practice is not verified. Profile a manual Run of 5000 items on a mid-range phone.
2. **If/Filter right-hand expression (E-05).** The engine ignores expressions on the right. Whether the inspector offers an expression input there is not verified (feature/canvas inspector not read).
3. **Lease expiry while the process is frozen.** Lease is 60 s (LeasePolicy). If the OS freezes a cached app while a Run holds only a PARTIAL wake lock, another host could mark the claim Crashed while the Run later finishes. The final persist then overwrites. Needs a Doze test with a long Wait.
4. **Run timeout on the wake lock edge.** Flow timeout 15 min and wake lock 15 min end at the same moment. Behaviour on a real deep-Doze device not tested.
5. **FGS lifecycle on Android 14 and 15.** Service type `specialUse`, restart after task removal, boot-time start restrictions. Read from the code only (AlwaysOnService.kt), not exercised.
6. **Exact alarm degradation and OEM kill behaviour** (Xiaomi, Oppo, Samsung). The design handles it with Pulse and watchdog; real-world misses are unmeasured.
7. **n8n details I only skimmed:** active-workflow-manager (trigger activation and retry), scaling mode, scheduled-task-manager. The gap table does not depend on them, but the section 2 summary of them is thin.
8. **Webhook server, geofence and device event sources** were not read. Anything in this document about them is limited to how they reach the Inbox.
9. **Tests.** I read the test names in FlowRunnerTest, not every test body. Some behaviour claims (for example the Stop Step status) come from reading the code, not from running the tests. I did not run Gradle.

### Facts that came from memory (user context)
None used. The memory store held only a short note about the KaivoFlow project and was not needed for this audit.
