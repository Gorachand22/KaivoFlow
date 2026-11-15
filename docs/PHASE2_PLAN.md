# Phase 2 Plan: Perfect Base App

Goal: before adding any new n8n-class node family, make the base app clean and complete: a tidy node catalog, a
data and expression core, an n8n-class inspector with Table, Schema and JSON panels and drag-to-map, and a Flow
that runs 24/7 after publish, also with the screen off.

Detailed specs (written after reading n8n behaviour, all in our own words and design):
- [P2-node-taxonomy.md](specs/P2-node-taxonomy.md): categories, verdict for all 87 executors, structure, aliases
- [P2-data-panels-params.md](specs/P2-data-panels-params.md): ParamSchema, data panels, drag-to-map, step data storage (T1 to T25)
- [P2-always-on-engine.md](specs/P2-always-on-engine.md): Always-On Engine, publish lifecycle, Reliability Center (AO-01 to AO-26)

Legal rule stays: n8n is only a behavioural reference. No code, identifiers or text are copied.

## Owner requirements mapped

| # | Requirement | Where it lands |
|---|---|---|
| 1 | Published Flow runs 24/7: app closed, phone locked, screen off | 2E Always-On Engine |
| 2 | Remove weak nodes, keep structure clean, AI family comes later | 2A structure, 2C verdicts, AI slots ready but no AI code yet |
| 3 | Proper node categories (Device with subcategories, filled later from n8n) | 2A category tree and picker |
| 4 | Keep good nodes with n8n-class design: Input table, Schema, JSON, drag into parameters | 2B data core, 2D inspector |
| 5 | Earlier Phase 2 items (own expression parser, item lineage, parameter schema, per-item errors, sandboxed Code) | 2B |

## Sub-phases

Each sub-phase is one or two rounds, ends with all JVM tests green and a push to this branch.

### 2A: Structure and picker (no behaviour change, no stored data change)
Taxonomy spec section 5.7 steps 1 to 5.
- Golden test and per-Step contract test against today's code.
- `CategoryTree` and new `NodeSpec` fields; picker rewritten against the tree (10 top categories, Device with 9 subcategories, search, recent).
- Move files into one-Step-per-file package layout, one category per change.
- `AliasMap`, loader normalisation and converter framework.
Gate A: owner builds, picker shows new categories, every old Flow opens.

### 2B: Data and expression core
Roadmap 2.1 to 2.6 plus data spec T1 to T6.
- Own Kotlin expression parser and evaluator (maths, strings, arrays, dates, safe navigation, clear errors, time and depth limits).
- Item lineage and per-input-port data, outputs keyed by Step id.
- `ParamSchema`, `ParamValue` (Fixed or Expr), show rules, one validator, typed resolved params.
- Per-item errors; sandboxed Code Step with time and memory caps.
Parallel-safe with 2E (different modules).

### 2E: Always-On Engine (runs in parallel with 2B, touches core/work, manifest, Room 3 to 4)
Always-on spec AO-01 to AO-21, in its milestones.
- M0: build flavours (`sideload`, `play`), Room migration 3 to 4, activation state machine, publish validator.
- M1: one foreground service of type `specialUse`, durable Spark Inbox, alarm-based schedules (wake from Doze), Reconciler, watchdog, wake-lock policy, webhook server moved into the Engine, SMS, notification and geofence rework.
- M2: Publish lifecycle (Draft, Published, Paused, Error), publish sheet, heartbeat, expected-versus-actual run log, Reliability Center with one-tap fixes, self-test suite.
Gate E: owner runs the adb test plan (Doze, kill, reboot, screen-off scheduled Run) on the phone.

### 2C: Clean node set
Taxonomy 5.7 steps 6 and 7, after 2B so merged Steps use the new parameter kinds.
- Rename ids to `<category>.<name>` once and freeze them. Old Flows convert via aliases; removed Steps load as "Unavailable Step".
- Execute verdicts in order: merges into Edit Fields and HTTP, rewrites (Merge, Loop Over Items, Remove Duplicates, Stop), remove 7 weak Steps, remove Loop, Local table, Group and total with converters.
- Weak single-shot Ask AI becomes a marked placeholder; the AI family comes in Phase 3.

### 2D: Inspector and data panels
Data spec T7 to T25.
- Per-Step input and output stored for every Run (200 items or 512 KB per port, redacted), pinned data, Test Step with real data, re-run from a Step.
- Table, Schema and JSON panels with item count, search, run and branch selector.
- Drag-to-map by long-press plus tap-to-insert tray, fixed or expression toggle, inline expression preview and autocomplete.
- Widgets for every parameter type (collections, assignments, conditions builder, resource and credential pickers).
- New inspector state holder; retry, on-error, timeout and notes in the settings tab.
Needs Room migration 4 to 5, after Engine migration 3 to 4.
Gate D: owner checks the inspector flows on the phone.

### 2F: Proof and hardening
AO-22 and AO-23: full device matrix, 8 hour soak, raise targetSdk to 36, write `docs/specs/P2-device-results.md`.
Then merge to `main` after the owner's gates.

## Order and why

2A first (everything after lands in a clean layout), then 2B and 2E in parallel, then 2C, then 2D, then 2F.
Room migrations are numbered in that order: Engine 3 to 4, Step data 4 to 5.

## Known risks

- Android modules have never been compiled in the cloud sandbox. Each gate depends on the owner's Gradle build.
- Always-on behaviour varies by OEM. The Reliability Center reports evidence and never shows green for settings it cannot verify.
- The inspector is mostly a rewrite (about 15% of the old one survives). That is the largest single UI task.
