# KaivoFlow Roadmap

Built from the eight audit reports in [audit/](audit/). Status of each task lives in
[PROGRESS.md](PROGRESS.md). Every P0 listed here was spot-checked in the code by the lead
engineer, not only taken from the reports.

## Rules for all phases

1. **Original code only.** n8n is read for behaviour. No code, identifiers or text are copied
   (Sustainable Use License). KaivoFlow keeps its own vocabulary: Flow, Step, Stream, Spark, Run.
2. **Keep the Velvet Obsidian and Champagne Gold identity.** UI changes extend `core:design`.
3. **Pure Kotlin decides, Android adapts.** New logic goes in Android-free modules with JVM tests.
4. **Every phase ends with a gate.** Unit tests pass, the owner builds with Gradle, installs with adb
   and runs the phase checklist on a real phone. Nothing in `core:work`, `core:network`, `core:design`,
   the feature modules or `app` has been compiled by anyone yet, so the first gate also finds build errors.
5. **Small commits, one branch per phase**, merged to `main` only after the gate.

## Verified P0 facts (from code)

| Fact | Evidence |
|---|---|
| Any database version bump wipes all user data | `DataModule.kt:22` destructive fallback, `Database.kt:248` `exportSchema = false` |
| "QuickJS" evaluator only wraps the path-lookup fallback, so maths and the Code step cannot work | `QuickJsExpressionEvaluator.kt:13` |
| Params resolve against item 0 for every item | `StepExecutor.kt:46-49` (`params()` uses `scope()` with index 0) |
| Every Flow can read the process environment | `Expression.kt:101`, `StepInspectorSheet.kt:116` |
| Webhook server is never started, only declared in the manifest | no reference outside `AndroidManifest.xml:152` |
| Scheduler never checks whether a Flow is active | no `isActive` in `FlowScheduler.kt` |

Other P0s come from the reports and are marked "report" below.

## Phase 1: Stop the bleeding (foundation)

Goal: nothing loses data, leaks secrets or silently does the wrong thing. No new features.

| ID | Task | Source |
|---|---|---|
| 1.1 | Per-item parameter resolution: executors resolve params for the item they process. Fix all ~29 call sites. Add multi-item tests that fail on the old behaviour. | 02, 03, 04, 06 |
| 1.2 | Fields marked secret or code are never expression-expanded. | 02, 06 |
| 1.3 | Remove `$env`. Replace with user-defined Variables. | 02, 07 |
| 1.4 | Real Room migrations, `exportSchema = true`, delete destructive fallback, migration tests. | 07 |
| 1.5 | Fix backup rules (right database name, `allowBackup` consistent). | 07 |
| 1.6 | Scheduler respects `isActive`, re-arms the next run before running, does not cancel itself, drops the forced battery constraint. | 07, 04 |
| 1.7 | Run record is created at start and updated by status. Cancelled Runs are saved. Prune old Runs. | 01 |
| 1.8 | Events for a busy Flow are queued, not dropped. | 01, 04 |
| 1.9 | Fix SMS receiver, give the battery Spark an event source, start and stop the webhook server properly. | 04 |
| 1.10 | Security quick fixes: unexport the event receiver or protect it, TLS hostname check, webhook binds to localhost by default and requires auth. | 05 |
| 1.11 | Credential picker in the inspector so users stop pasting secrets into parameters. | 08, 05 |
| 1.12 | File node gets its own directory, separate from the flow mirror. | 07 |
| 1.13 | Honest docs: README claims match the code, remove or write the missing linked docs. | 06 |

Gate 1: Gradle build green, unit tests green, owner installs and checks: no data loss across an
app update, a scheduled Flow fires after reboot, a multi-item Flow sends different payloads.

## Phase 2: Data and expression core

| ID | Task | Source |
|---|---|---|
| 2.1 | Original expression engine (parser plus evaluator) with maths, strings, arrays, dates, safe navigation, clear errors, time and depth limits. | 02 |
| 2.2 | Item lineage so `$step()` returns the matching item. Merge keeps lineage and binary. | 02, 03 |
| 2.3 | Per-input-port data and outputs keyed by Step id, not name. | 02 |
| 2.4 | Declarative parameter schema (types, defaults, conditional display, collections, credential slot) driving validation and the inspector. | 02, 08 |
| 2.5 | Sandboxed Code step with time and memory caps. | 05, 02 |
| 2.6 | Per-item error handling: one bad item does not discard the rest. | 03 |

## Phase 3: Node set rebuild

Target catalog: 31 Steps and 14 Sparks (from 40 and 12 in the audited files), with 11 new node types. Details in
[audit/03-logic-transform-nodes.md](audit/03-logic-transform-nodes.md).

| ID | Task |
|---|---|
| 3.1 | Merge with real modes, Loop Over Items, Edit Fields, Summarize, Data Table (persistent state). |
| 3.2 | Fix Stop and Remove Duplicates to do what they claim. |
| 3.3 | Delete Loop, Local table, Group and total after a migration that maps old Flows to the new nodes. Keep old ids as aliases for one release. |
| 3.4 | HTTP Request depth: retries, pagination, multipart and binary bodies, response headers, redirect and TLS control. |
| 3.5 | Declarative integration spec so a new REST service is data, not code. OAuth2 with PKCE. |
| 3.6 | Update starter templates and docs. |

## Phase 4: Editor UX (touch-first, same visual identity)

Step output history that survives restarts, mapping picker from earlier Steps, expression toggle with live preview,
retry, on-error, timeout and notes UI, input and output as Table, JSON and Schema, pin data, re-run from a Step,
and wiring up the features that exist but cannot be reached (duplicate, minimap, flow-list search). See
[audit/08-editor-ux.md](audit/08-editor-ux.md).

## Phase 5: Reliability and Android edge

Reliability Center (battery optimisation, exact alarms, OEM killers), catch-up policy for missed runs, optional foreground
engine, in-app backup and restore, flow version history, Android-only strengths (intent send and receive, notification
actions, tiles and widgets).

## Phase 6: AI agent, import and release

Agent with KaivoFlow Steps as tools, memory and structured output; import of n8n workflow JSON where fields map
cleanly; performance and accessibility pass; release checklist.

## Decisions to confirm with the owner

1. **Expression engine:** own Kotlin parser (recommended: small, fast, no native dependency, easy to sandbox) versus
   QuickJS for everything. The Code step may still use QuickJS with caps.
2. **Branching:** one branch per phase merged after each gate (recommended), or work straight on `main`.
