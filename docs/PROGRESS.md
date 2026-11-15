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
| 0 | Audit of the existing code base against n8n behaviour | In progress |
| 1 | Foundation fixes: P0 security, run persistence, delete weak nodes, honest docs | Not started |
| 2 | Data and expression core: item lineage, real expression parser, parameter schema | Not started |
| 3 | Node set rebuild: n8n-class logic nodes, HTTP depth, declarative integration spec | Not started |
| 4 | Editor UX: schema-driven inspector, data views, pin data, re-run from step | Not started |
| 5 | Reliability and Android edge: scheduling, reliability center, backup, device nodes | Not started |
| 6 | AI agent, n8n workflow import, polish and release | Not started |

## Phase 0: Audit lanes

| # | Lane | Report | Status |
|---|---|---|---|
| 01 | Execution engine and run persistence | [01-execution-engine.md](audit/01-execution-engine.md) | Running |
| 02 | Data model, expressions, parameters | [02-data-expressions-parameters.md](audit/02-data-expressions-parameters.md) | Running |
| 03 | Logic, transform and text nodes | [03-logic-transform-nodes.md](audit/03-logic-transform-nodes.md) | Running |
| 04 | Integration, device, AI nodes and triggers | [04-integration-device-ai-triggers.md](audit/04-integration-device-ai-triggers.md) | Running |
| 05 | Security and privacy | [05-security.md](audit/05-security.md) | Running |
| 06 | Code quality, duplication, build health | [06-code-quality.md](audit/06-code-quality.md) | Running |
| 07 | Storage, import/export, scheduling | [07-storage-scheduling.md](audit/07-storage-scheduling.md) | Running |
| 08 | Editor UX vs n8n editor | [08-editor-ux.md](audit/08-editor-ux.md) | Running |

## Log

- 2026-11-15: Repo attached (158 files, 116 Kotlin, about 29.5k lines, 13 modules). n8n reference cloned for
  reading only. Eight parallel audit lanes started. Noted already: README links PARITY.md which does not exist.
