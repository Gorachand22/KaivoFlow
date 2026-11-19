# Phase 3 Plan: Premium Base, Real AI, Universal Expressions

Base: `origin/main` at `300b0b5` (Phase 2 plus Antigravity's build fixes, Ask AI assistant, Agent Builder, credential sheet).
Rule for every lane: the lane's agent first studies how n8n behaves for that feature (n8n clone is in `/tmp/ref/n8n`,
read for behaviour only, Sustainable Use License, no code or text copied), then writes a short spec, then builds.
Everything Antigravity built stays. We move its backend step by step, we do not rewrite its screens.

## Owner requirements

| # | Requirement | Lane |
|---|---|---|
| 1 | Verify Antigravity's changes and how strong the backend is | 3C audit (docs/audit/09) |
| 2 | Every input box, in a node or outside, accepts expressions (secrets never) | 3A |
| 3 | Test Run in a node runs only that node, shows its real input and output | 3B |
| 4 | Premium look: luxurious, fast animation everywhere | 3D |
| 5 | Be better than n8n: AI Agent family, power and reliability | 3E after the 3C audit |

## Lanes

### 3A Universal expressions (started)
One shared input component with Fixed or Expression mode, chips, live preview and mapper hook. Policy table in
`docs/specs/P3-universal-expressions.md` decides which of the app's inputs are expression-capable. Values are stored as
Fixed or Expr and resolved at run time. Secrets, API keys, chat boxes and search boxes never take expressions.

### 3B Single-node Test Run (started)
Test Step runs exactly one Step. Input order: pinned, last stored output of the parent, last test input, editable manual
input. Input and Output panes show exactly what was used and produced. Trigger Steps fire a sample event without arming
anything. Test results never count as production Runs. Running earlier Steps is a separate, explicit, optional action.

### 3C Antigravity audit (started)
Architecture, power compared with n8n's AI family, security of keys and tool execution, robustness, credential
duplication. Output: `docs/audit/09-antigravity-ai-agents.md` with an ordered migration plan.

### 3D Premium motion (started)
Motion tokens, reduced-motion policy, quality tier, haptics, shimmer, press feedback, running state on the canvas with a
gold current along Streams and item counts, animated health ring, sheet and list transitions.
Spec: `docs/specs/P3-motion-design.md`.

### 3E AI core and Agent Steps (after 3C)
Pure `core/ai` module (providers, agent loop, tool schema, memory, limits) shared by the chat screens, Flow Steps
(Agent, Chat Model, Memory, Tool, Output Parser) and the Always-On engine. Confirmation and permission model for
destructive tools. Exact tasks come from the 3C report.

### 3F Phase 3 follow-ups (queued)
Code Step sandbox (QuickJS with time and memory caps), HTTP Request depth (retries, pagination, multipart),
declarative integration spec with OAuth2 PKCE, `params()` to `resolved()` migration of old Steps, Loop Over Items with
a done port (engine cycles), more Device and App nodes from n8n's catalog, one at a time.

## Order
3A, 3B, 3C, 3D run in parallel (files do not overlap). 3E starts when 3C lands. 3F follows.
Gate: owner builds with Gradle on the laptop, installs with adb and runs the checklist written at the end of the phase.
