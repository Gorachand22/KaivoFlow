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
| 2 | Perfect base app, see [PHASE2_PLAN.md](PHASE2_PLAN.md): 2A structure and picker, 2B data and expression core, 2E always-on engine, 2C node clean-up, 2D inspector and data panels, 2F proof | Planned, specs written |
| 3 | Node set rebuild: n8n-class logic nodes, HTTP depth, declarative integration spec | Not started |
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
