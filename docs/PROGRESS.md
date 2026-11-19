# KaivoFlow Progress Tracker

Single place to see what is done, what is running and what is next.
Updated by the lead engineer after every round. Newest round at the top of the log.

## Mission
Make KaivoFlow the strongest serverless, single-user automation app on a phone.
Use n8n only as a *behavioural* benchmark. All KaivoFlow code is original (n8n is under the
Sustainable Use License, so no code, identifiers or text are copied from it).
Keep the Velvet Obsidian and Champagne Gold design identity.

## Phases

| Phase | Goal | Status |
|---|---|---|
| 0 | Audit of the existing code base against n8n behaviour | Done (8 reports, top P0s verified in code) |
| 1 | Stop the bleeding: data loss, secrets, per-item params, scheduler, run persistence (see [ROADMAP.md](ROADMAP.md)) | Code complete, waiting for owner build and phone gate ([PHASE1_CHECKLIST.md](PHASE1_CHECKLIST.md)) |
| 2 | Perfect base app, see [PHASE2_PLAN.md](PHASE2_PLAN.md): 2A structure and picker, 2B data and expression core, 2E always-on engine, 2C node clean-up, 2D inspector and data panels, 2F proof | 2A to 2E code complete (JVM tests green, Android never compiled); 2F (device matrix, soak, targetSdk) waits for the owner build and [PHASE2_CHECKLIST.md](PHASE2_CHECKLIST.md) |
| 3 | Node set rebuild, universal expressions, AI agent on `core:ai` | Partly done, see "Phase 3 status"; owner checks in [PHASE3_CHECKLIST.md](PHASE3_CHECKLIST.md) |
| 4 | Editor UX: schema-driven inspector, data views, pin data, re-run from step | Not started |
| 5 | Reliability and Android edge: scheduling, reliability center, backup, device nodes | Not started |
| 6 | AI agent, n8n workflow import, polish and release | Not started |

## Phase 0: Audit lanes

| # | Lane | Report | Status |
|---|---|---|---|
| 01 | Execution engine and run persistence | [01-execution-engine.md](audit/01-execution-engine.md) | Done |
| 02 | Data model, expressions, parameters | [02-data-expressions-parameters.md](audit/02-data-expressions-parameters.md) | Done |
| 03 | Logic, transform and text nodes | [03-logic-transform-nodes.md](audit/03-logic-transform-nodes.md) | Done |
| 04 | Integration, device, AI nodes and triggers | [04-integration-device-ai-triggers.md](audit/04-integration-device-ai-triggers.md) | Done |
| 05 | Security and privacy | [05-security.md](audit/05-security.md) | Done |
| 06 | Code quality, duplication, build health | [06-code-quality.md](audit/06-code-quality.md) | Done |
| 07 | Storage, import/export, scheduling | [07-storage-scheduling.md](audit/07-storage-scheduling.md) | Done |
| 08 | Editor UX vs n8n editor | [08-editor-ux.md](audit/08-editor-ux.md) | Done |

## Log

- 2026-11-15: Repo attached (158 files, 116 Kotlin, about 29.5k lines, 13 modules). n8n reference cloned for
  reading only. Eight parallel audit lanes started. Noted already: README links PARITY.md which does not exist.
- 2026-11-15: All eight audit lanes finished and pushed to branch `claude/audit-and-roadmap`. Lead engineer verified the
  top P0s in code (destructive DB migration, stub expression engine, item-0 param resolution, `$env` leak, webhook server
  never started, scheduler ignores `isActive`). Roadmap written in [ROADMAP.md](ROADMAP.md).
  Gradle cannot run in the cloud sandbox, so model, engine, nodes, expressions and codec were compiled with bundled Kotlin
  jars: 204 of 209 tests pass (5 need Room). The app modules have never been compiled by anyone.
- 2026-11-16: Phase 1 implemented on branch `claude/phase-1-foundation` (per-item params, secrets not expanded,
  `$vars` instead of `$env`, Room migrations, backup rules, scheduler and Run lifecycle, event queue, webhook security,
  SMS and battery Sparks, credential picker, receivers via Hilt EntryPoint). 185 JVM tests pass. Android modules are not compiled yet.
- 2026-11-16: Phase 2 specs written from n8n behaviour study (node taxonomy, data panels and parameters, always-on engine) and consolidated in PHASE2_PLAN.md.
- 2026-11-16: Phase 2C (clean node set) implemented on branch `claude/phase-1-foundation`: all ids renamed to `<category>.<name>` through the AliasMap, verdicts executed (merges into Edit fields, HTTP, Notify, Clipboard, Connection info, Remove Duplicates and Crypto; Merge and Loop over items rewritten; Weather converts to HTTP; TCP and Chart tombstoned; Random number and pick unavailable; Table, Aggregate, Stats, CSV and Binary hidden), webhook `responseMode`/`responseTimeoutSec` and geofence `dwellSeconds` declared. 87 executors became 67 (62 visible). 630 JVM tests pass (model, engine, expressions, nodes, pure core:data); Android modules and `NativeFlowBundle` were not compiled here.
- 2026-11-16: Phase 2 sub-phases 2A, 2B, 2C, 2D and 2E are implemented on `claude/phase-1-foundation`: category tree and picker,
  own expression engine, ParamSchema, item lineage, always-on engine (specialUse foreground service, durable inbox, alarms,
  reconciler, watchdog, webhook engine, activation state machine, publish sheet, Reliability Center, self-test), step data
  storage with pins, new Step inspector with Table, Schema, JSON and Binary panels, drag-to-map, parameter widgets. Room is at
  version 6 (migrations 1 to 6). Release verification: one combined offline run of 1,049 JVM tests passes (model 99, expressions 148,
  engine 393, nodes 164, core:data 75, feature:canvas pure logic 170). Static review of the never-compiled Android code found and
  fixed two definite build errors: Kotlin opt-in flags sat in the root project so they never reached modules (now in `subprojects`),
  and `core:data` hid `room-runtime` from `core:work`, which reads `KaivoDatabase.NAME` (now `api`).
  Owner gate: [PHASE2_CHECKLIST.md](PHASE2_CHECKLIST.md). targetSdk is still 34.

## Phase 3 status

Done and JVM-tested offline (scratch suites): `core:ai` approval relay, provider endpoint rules, model discovery; the assistant
draft/proposal engine (`flowedit`: catalog-generated prompt and Step list, parser, draft tools, `ProposalApply` with conflict
check, undo copy); AI key only in headers and never stored in provider rows; agent compiler and prompt; universal expressions policy.

Done but only reasoned (Compose, Hilt, ViewModel, FlowEngine, MainActivity, canvas never compiled here): Ask AI works on a draft
and only saves on Apply, with Undo; approve/deny cards in chat and a global dialog host; Agent Builder Test Run compiles to a one-Step
`ai.agent` Flow and runs through `FlowEngine.testStep`; `AiModule` Hilt wiring and vault bridge; Step notes through `KaivoInputField`;
variables through `VariableResolution`; `MotionSettingsSection` in Settings.

Remaining: Code Step QuickJS sandbox, HTTP depth, declarative integrations and OAuth2 PKCE, Loop done port, persistent agent memory,
streaming, legacy `ai.*` port. Known gaps: "Test from here" uses automatic input (no per-Run input override); proposals are not
persisted across a restored chat; Production Runs never prompt for approval (tools need `allowUnattended`).

## What remains

Phase 2F (needs the owner's phone): run the adb matrix and 8 hour soak, write `docs/specs/P2-device-results.md`, commit Room schemas
4, 5 and 6, raise targetSdk (35, then 36) once the matrix passes, merge to `main`.
Phase 3: n8n-class logic nodes, HTTP depth, declarative integrations, the AI family (the placeholder Steps `ai.chat`, `ai.extract`,
`ai.classify` are legacy), tests for `core:work` and the Compose screens, a pure `core:codec` module so more tests run without the SDK.
