# Study 01 - n8n AI assistant and workflow builder, and what KaivoFlow should build

Subject: n8n 2.48.6 at /tmp/ref/n8n. Read-only behaviour study, written for KaivoFlow (Android, serverless, single user, premium).
Rules followed: behaviour only, own words, n8n paths are pointers, no code or text copied (Sustainable Use License).
KaivoFlow facts were re-checked in the current tree; file:line refs are to /home/claude/kaivoflow. The earlier audit (docs/audit-v2/05-ai-assistant-agents-connectors.md) still matches the code on every point I re-checked.
Scope rule from the owner: Antigravity's Ask AI and Agent Builder UI works and is loved. Nothing below proposes redoing it. Everything is "underneath and around".

Paths studied (pointers):
- packages/@n8n/instance-ai: docs/{architecture,streaming-protocol,tools,memory,prompt-profiles,sandboxing}.md, src/agent, src/runtime, src/workflow-loop, src/tools, src/domain-access, src/tracing, skills/*, evaluations/*
- packages/@n8n/ai-workflow-builder.ee: AGENTS.md, src/{agents,code-builder,tools,validation,chains,prompts}, evaluations/README.md
- packages/cli/src/modules/instance-ai: controller, service, credit service, run-limit error, telemetry services, liveness
- packages/frontend/editor-ui/src/features/ai/{instanceAi,assistant}: store, reducer, permissions, approval details
Not read in depth: Daytona sandbox provider, vector stores, most Vue components (layout only).

---

## 1. Architecture end to end

n8n has two builder generations plus an agent runtime. Knowing which is which avoids copying the wrong idea.

1. Instance AI (new, the "Assistant" page). One orchestrator agent per chat message, with skills, a task board, background tasks and a deterministic build/verify state machine. This is the one that matters most.
2. AI Workflow Builder .ee (older, canvas side panel). A graph of agents (supervisor, discovery, planner, builder, responder) that edits a JSON workflow with small tools; plus a newer "code builder" mode that edits a single source file with a text-editor tool.
3. The Agent node runtime (@n8n/agents and nodes-langchain), which Instance AI also uses as its loop engine.

### 1.1 Component diagram (text)

```
 Browser (Vue)                         n8n backend (main process only)                      Model / world
 +-----------------------+   POST /chat/:thread    +-----------------------------+
 | Chat UI + agent tree  |------------------------>| Controller                  |
 | Pinia store + reducer |   returns { runId }     |  - scope check (message)    |
 |                       |                         |  - attachment type/size     |
 |                       |<-- SSE /events/:thread -|  - 409 if run active        |
 | approval cards        |   (replay by event id)  +-------------+---------------+
 | setup panel, preview  |--POST /confirm/:id----->| InstanceAiService           |
 | Stop button           |--POST /cancel---------->|  - builds NEW agent per send |
 +-----------------------+                         |  - picks prompt profile      |
                                                   |  - per-run domain tracker    |
        +------------------------------------------+  - credit accounting         |
        |                                          +---+---------+---------+------+
        v                                              |         |         |
 Event bus (in-proc + Redis relay)             Orchestrator   Run state   Background
  -> durable event log table                    agent loop     registry    task manager
     (step facts only, ids per thread)         (<=100 steps)  (active,     (cap 5/thread,
  -> live-only deltas (text, reasoning)             |          suspended,    correct/cancel)
                                                    |          pending)
                          +-------------------------+----------------------------+
                          v                         v                            v
                   Domain tools via          Skills (on demand)         Sandbox workspace
                   Adapter -> n8n services   workflow-builder, debug,   (per thread, TS source,
                   (RBAC checked per call)   data-table, agent-builder  knowledge base files)
                          |                                                    |
                          v                                                    v
        workflows, executions, credentials,                     build tool: compile, lint,
        node loader, data tables, folders                       schema check, credential
                                                                resolve, save
   Observer/Reflector background agents compress history (memory tables)
   MCP client manager (external tools, sanitized + name-checked) ; Browser Use relay (extension)
   Tracing (LangSmith), billing claim per finished run segment, telemetry events
```

### 1.2 Data flow of one turn

1. UI posts the message with context (open workflow, attachments, time zone, open tabs, optional prompt-version pin). Reply is only a run id, so the HTTP call never carries the answer.
2. Controller checks the user scope, validates attachments (mime and size, because an oversized image that reaches history would break every later turn), refuses with 409 if the thread already has an active run.
3. Service resolves the prompt profile (system prompt version plus skill variants), loads MCP tools, builds a fresh agent, creates a per-run domain-access tracker seeded from persisted grants, starts the stream.
4. The agent loop streams chunks. Each chunk is mapped to a canonical event and published to the bus. Durable step facts go to the event log table with a per-thread increasing id; text and reasoning deltas are live only.
5. A tool needing approval suspends the run. The state is registered as suspended, a confirmation event goes to the UI, the loop is parked. The user answers over a separate POST; the run resumes from the parked state, even after a restart (suspended state is persisted and restored).
6. On finish the service emits the run-finish event, claims credits for the finished segment (retried, deduped), writes telemetry, and prunes checkpoints on a schedule.

### 1.3 Protocols

- Transport: POST to start, SSE to listen, separate POSTs for confirm, cancel, task cancel, task correction, feedback. One SSE stream per thread, not per run, so background tasks and later runs show up on the same pipe.
- Every event carries runId and agentId. The UI folds events into a tree (orchestrator and child agents), through a pure reducer shared between live and replay.
- Reconnect: client sends the last id it saw (header on auto-reconnect, query parameter after page reload, nothing on first open = replay all). Server replays rows with id greater than the cursor, then switches to live. Because ids can arrive slightly out of order across processes, the client keeps the max id and drops duplicates.
- Replay of deltas: ephemeral frames have no id. The durable log stores coalesced "text block" and "reasoning block" facts, applied with replace semantics keyed by response id, so a reconnect in the middle of a paragraph never renders it twice.
- Session restore: a messages endpoint returns rich messages with agent trees and a "next event id"; the UI starts its SSE cursor right after it. A status endpoint says whether a run is active or suspended and lists background tasks.
- Event families (names paraphrased): run start/finish (status completed, cancelled, errored, waiting), text and reasoning deltas, tool input start/call/result/error, tool interrupted, agent spawned/completed, confirmation request, task list update, instance context, setup items, status line, preferences applied, thread title updated, error.

### 1.4 The older builder (for contrast)

- Supervisor (a small routing call returning a next-agent choice plus one sentence of reasoning) routes between responder, discovery, builder and an assistant handler.
- Discovery subgraph (iteration cap 50) finds candidate node types and best-practice docs; planner (structured output: summary, trigger, ordered steps with suggested node types, optional notes) produces a plan and then interrupts to wait for approve, reject or modify with feedback; builder subgraph (cap 100) uses small graph tools.
- Builder tool set (behaviour): add node, connect, remove connection, remove node, rename node, update node parameters (a separate small chain turns an instruction into a parameter patch), read one parameter, fetch execution schema or logs, node search and details and examples, expression data mapping, resource-locator options, validate structure, validate configuration, overview, web fetch, documentation.
- Code builder mode: model holds one source file; edit by exact string replace (single and atomic batch); a validate tool returns errors; loop cap 50, validate attempts cap 10; a triage agent decides "answer a question" versus "build" first. Error feedback to the model says: look only at errors related to the last request, fix them all in one batch, validate once, no commentary.
- Session manager keeps per-user sessions with auto-compaction (summary kicks in around 150k of a 200k window), prompt length cap 5,000 characters, tool parameter reads capped at 30k chars, execution data in tool results capped at 10k chars, assistant sub-stream timeout 3 minutes.

---

## 2. Behaviours and edge cases

### 2.1 Limits

- Orchestrator: 100 model steps per run. Builder generation (old): 100 builder, 50 discovery, 10 triage. Code builder: 50 iterations, 10 validate attempts.
- Concurrency: one active run per thread (409). Background tasks: 5 per thread. A global cap on concurrent runs exists as an operator setting and returns a 429 with a reason code so the UI can say "instance busy" (retry) versus "you already have runs going" (do not retry).
- Liveness sweeper: pending confirmation expires after one day; background task idle 10 minutes, max 30 minutes; active-run idle timeout deliberately disabled because a run waiting on a long child task looks idle; max run lifetime one day, measured from first start including time parked on a human (with a written warning not to lower it blindly).
- Execution tool: default wait 5 minutes, max 10, execution cancelled on timeout.
- Threads expire after 30 days (configurable, 0 = never).
- Memory thresholds: observer at ~30k tokens of raw messages, reflector at ~40k tokens of observations.
- Data-table approval previews: 3 rows, 5 columns, 100 chars per value, with an "N omitted" line.
- Credential description preview 256 chars, stored description 512, list page 50 (max 200).

### 2.2 Retries and error handling

- Billing claim: up to 3 attempts, idempotent on the server side, in-process FIFO dedupe cache of 1000 run segments.
- Build failure signature: errors are normalised (digits and spacing removed, first paragraph only, sorted) into a signature. The same signature twice for one work item produces an escalation message ("you already tried this, use a different repair strategy"), plus targeted hints for known traps (for example raw/XML body mode on the HTTP node, restricted language subset of the SDK). The workflow loop state machine ends in a terminal failure if the same signature repeats.
- Terminal response guard: after a run ends, a guard checks that the user saw something (root text, an error, or a visible confirmation). If a run ended silent it emits a fallback message summarising work done; it distinguishes completed, cancelled, errored and waiting. This prevents the "spinner stops, nothing said" failure.
- User-facing error mapping service turns provider and quota failures into plain messages; quota exhaustion has a dedicated path with a push update to the UI.
- Stale or invalid confirmation requests are detected and not displayed; a "tool interrupted" event covers a tool that was cut by a restart.

### 2.3 Cancellation

- Stop is a POST, idempotent, stops the orchestrator and all background tasks, ends with run-finish status "cancelled". If the run already finished it is a no-op.
- The run's abort signal is passed to every tool; tool handlers race it so Stop unblocks at once; I/O tools should forward it to their fetch. Cancelled tool calls are settled as cancelled results so history never has a dangling call (important: providers reject history with a tool call that has no result).
- Three surfaces converge on cancel: the stop button, the user typing "stop that" (the agent calls the task-control tool), and a global cancel.

### 2.4 Concurrency and persistence

- Agent object is rebuilt for every message, so permissions, MCP config and prompt version can change between turns. Memory is outside the agent (database), so rebuilding is cheap.
- 12 persisted entity types: threads, messages, resources, observations, observation cursors and locks, checkpoints, event log, pending confirmations, thread tabs, thread grants, MCP registry connections.
- Suspended runs are persisted and restored by a restorer service at startup. Checkpoints are pruned by a scheduled task.
- Event bus is in-process with a Redis relay to sibling mains; ids come from a shared per-thread sequence in multi-main.

### 2.5 Security and credentials

- Everything the tools do goes through the same permission scopes as the human user, per call, via an adapter. The agent never gets more rights than the user.
- Secrets: no tool returns decrypted credential data. Listing returns id, name, type and an optional human description only. The agent cannot write secret values at all. To create or fix a credential it opens a setup card in the normal UI (pick an existing credential or create one there), or in a browser-use flow where a captured value goes into a buffer and not into the chat. Prompt rules: never ask the user to paste secrets, and if one is pasted, say it cannot be stored, tell the user to rotate it, and offer the secure path.
- Credential matching rule: when exactly one credential for a specific service exists, it is auto-selected; generic auth types (bearer, header, basic) always need an explicit confirm because the type alone does not identify a service.
- Never name provider OAuth scopes from memory; look them up in docs first (hallucinated scopes were a real failure).
- Untrusted content doctrine: fetched web pages, execution data, attachments and MCP results are reference material, never instructions. Web content passes a sanitizer. Search and URL fetch go through a per-domain gate with three tiers: trusted allow-list, thread-persistent grants (one host, or all hosts), per-run one-time grants. Web search has its own grant because it has no host. SSRF filter blocks private ranges, loopback and non-http schemes, and re-checks after redirects.
- MCP tools: schema sanitizer (flattens shapes providers reject), description sanitizer, reserved-name check so an external tool cannot shadow a built-in like "list workflows", config-hash cache.
- Modes injected into the prompt and enforced by tool errors: read-only (instance protected by source control), limited (data sharing off: cannot see parameter values or execution data, cannot save workflows), license limits, project scope (writes locked to the thread's project, credentials only from that project).
- Memory isolation per user and thread; conversation-history tool searches only the user's own past threads in the project.

### 2.6 Cost and token tracking

- Per run segment token usage is accumulated and turned into decimal "credits", claimed against a billing authority with retry and dedupe; a per-thread running total is stored for display; quota crossing triggers a one-time handler plus telemetry. Observer/Reflector background jobs have their usage recorded too.
- Prompt caching: Anthropic caching on by default (5 minute TTL). System prompt is kept as one cached prefix: per-thread values (project id, project name, time) are never written into it. Observation block first and raw block second keeps the prefix append-only.
- Reasoning level default medium, overridable per model by substring rules.
- A context-budget tool prints the token cost of the static prompt, the skill catalogue and every tool schema; an A/B benchmark compares model context between two commits.

### 2.7 Evaluation and testing

- Five harnesses: end-to-end build + mocked execution + judge; standalone agent builds; compatibility corpus scored by binary checks; discovery/routing (does the orchestrator call or avoid the right tools, in-process, no server needed); pairwise (judge panel against do and do-not lists, to compare generations on the same dataset).
- Mocked execution: every HTTP request from a node is intercepted and answered by a model using the node config; non-HTTP nodes (triggers, AI nodes, databases) get model-written pin data; logic nodes run for real; data tables are reset and seeded per scenario. About 95 percent of node types covered. Binary fixtures are synthesised per type. Failures are classified by root cause.
- Evaluator families: LLM judge, pairwise, programmatic checks, binary checks. Local run and LangSmith run share evaluators. Regression detection against a baseline.
- Latency suite, routing suite, A/B context bench, response-style cases (the concise profile was measured: about 30 percent less emitted text on a fixed case).
- Unit tests next to nearly every source file (stream runner, liveness, terminal guard, run state registry, approval copy, MCP sanitizer, build failure tracker, prompt profiles, setup preference, tool provider schema).

### 2.8 Telemetry

- Setup events (model, sandbox, web search configured), skill load events, MCP events, browser-use events, confirmation events (approve, deny, kind), prompt suggestion events, builder metric events (per build: success, error codes, number of repairs), thread provenance, error reporter service, debug run buffer with a sanitizer (debug snapshots strip secrets), per-thread debug endpoints behind a scope.
- Tracing: root span per run, child spans per LLM step (messages, reasoning, tool calls, usage, finish reason), sub-agent spans, synthetic spans for internal tools, replay support, structural ids redacted.

---

## 3. Prompts and tool design (prose)

### 3.1 System prompt structure (Instance AI)

The prompt is assembled from small named sections, in this order of meaning:
1. Identity and mission: you are the instance agent; understand the request, load the right skill(s), then follow them. The prompt itself is short; depth lives in skills.
2. Optional sections by capability: sandbox workspace, project scope, "existing resources" routing, preview tabs, past conversations, preference saving.
3. Credential guardrails (three one-paragraph rules: do not ask for secrets, what to do if pasted, never name OAuth scopes from memory).
4. Tool discovery (only when tool search is on).
5. Communication style block (this is the only part that differs between published prompt versions).
6. Capability honesty: if the thing asked for has no reliable path (blocked scraping, impossible action), do not deliver an approximation silently; label it, get buy-in with a question, and name the gap in the summary. Not a reason for friction on feasible asks.
7. Setup accuracy: do not invent provider setup mechanics; look at the node definition, not generic docs, for webhook set-up; ask which of two similar products the user means.
8. Safety, untrusted content doctrine, browser rules, license limits, read-only and limited modes.
9. Reply language: same language as the user's latest request from the first word.

Design rules visible in the source comments:
- Never interpolate per-thread values; use presence flags so one cache entry serves everyone.
- "Existing resources" routing is always-on, not a skill, because the model must check inventory before it can know whether a request is a build. Reference text is read as a name, not an instruction ("run create monthly report" names a workflow).
- Concrete values the user gives (a URL, an id) are inputs, not a request to build around that service.
- Verbs in routing text are limited to what tools can actually do; a verb the tools cannot perform is a dead end.
- Eval wording is kept distinct from prompt wording so the eval does not become string matching.

### 3.2 Skills

Ten skill files, loaded on demand through a skills tool. Largest: workflow-builder (~880 lines), agent-builder (~700), intent-recognition (~500), debugging-executions (~260). The loaded skill ids live outside the chat text and are re-rendered in a cached block, so context compaction cannot drop them.

The workflow-builder skill is the real procedure. Its content, paraphrased:
- Routing and early service connections (announce credentials as soon as the type is known, then keep building).
- Mandatory process: research only what the request needs; fetch node type definitions with exact ids and discriminators (max ten per call); read hints, defaults, enums, credential types; resolve real resource ids with the option-lookup tool for any list-backed field (calendars, sheets, channels, models); pick a stable source file path or fetch an existing workflow as code (file is written and bound); write full code once, then only targeted replacements; call the single build tool; trace every branch wiring by reading the saved graph; fix by editing the same file and rebuilding; after a successful build follow the inlined post-build instructions before any final message. "Do not produce visible output until the final step unless blocked."
- Escalation: never stop before the first build to ask for recipients, ids, channels, credentials, timezone. Use placeholders. Ask only when a missing choice changes topology or intent. Do not retry the same failing approach more than twice. Never re-ask a skipped question, and a skip is not permission.
- Placeholders: a marker helper for unknowable values; never fake values like example emails or "YOUR_API_KEY".
- Repair strategy: for a reported red node, first run the workflow and read the real error and resolved parameters, never edit on a hunch. For edits, fix only errors your change introduced; keep unrelated content; inserted write nodes replace the downstream payload, so branch in parallel or reference the data node.
- Rules sections: credentials, missing resources, compositional workflows (sub-workflows), data tables, language subset, node groups for big canvases, expression reference, pattern reference, completion.

### 3.3 Tool list design

About 70 tools. Principles:
- One tool per domain with an action field (discriminated union per action) so each action has a tight schema: executions (list, run, run one node, get, debug, get node output, get resolved parameters, stop), research (web search, fetch URL), docs (3 actions), skills (5), MCP registry (4), task control (update checklist, cancel task, correct task), conversation history (2), data tables (11 single-purpose tools), workspace file tools (4 or 8).
- Single-purpose where the shape is stable: list/get/archive/unarchive/publish/unpublish/validate workflow, versions (license-gated), folders, tags, projects, credential list/search types/setup/test/delete/describe, node search/get types/get parameter options/execute node, search models, parse file, ask user, save preference.
- Tool search: core tools always loaded; the rest sit behind "search tools" and "load tool". Two pins are explained in the docs: docs lookup is pinned so n8n questions do not cost two extra calls; the MCP registry tool is pinned because it is for the case where nothing is connected, exactly when search finds nothing.
- Capability-gated actions are absent from the union when the host cannot do them.
- Input schemas are runtime-validated; many tools also have output schemas; a provider-schema test checks every tool schema against provider limits.
- Approval metadata: the model supplies one line of "approval summary" in the user's language for build, publish and live-run calls (what changes, what external effect). The UI builds the title from the resource name and a locale key; details (rows, columns, filters, counts) come from structured fields, not the model's prose. Missing summary falls back to a generic line. Names, not ids, in the card.
- Result shaping: list tools page (default 50, max 200) and return a `hasMore` flag plus a hint; reads return a "not found" list instead of failing the whole call; errors are redacted before reaching the model.
- Execution tool knows trigger types: chat trigger gets the chat input shape, form trigger gets flat fields, webhook gets a body or an envelope with query and headers, schedule gets synthetic timestamps. Wrong input shape produces nulls that look like an expression bug, so the docs warn explicitly.
- Run-one-node tool has input modes ordered by how much they prove: real upstream data from a past execution, mocked items, or tool-call arguments for a tool node. It always runs in manual mode so pins apply.

### 3.4 Validation and self-repair of generated workflows

Layers, from cheapest to most expensive:
1. Parse and compile the source (restricted language subset, lint).
2. Graph checks: every node reachable, trigger present, connection ports valid, branch outputs wired (an IF with one dangling output is a typical error), sub-node attachments (model, memory, tool) to the right parent.
3. Node schema checks: parameter names, enum values, node version, display conditions, resource/operation discriminators, expression syntax, placeholder fields.
4. Heuristic checks born from incidents: agent prompt contains no expression (usually the chat input was forgotten), hard-coded credentials, mismatched webhook response mode, unparseable schema for the provider, chat-model validity, Slack block shape, Python code constraints.
5. Credential resolution: pick an existing credential by type; if none, leave an unresolved placeholder for the setup step.
6. Save, then deterministic loop controller (build, verify, then one of: verified, needs patch, needs rebuild, needs user input, failed terminal). Same failure signature twice ends the loop.
7. Verify by running: the workflow runs with per-run pin data that is never saved. Nodes that write, send, delete, wait, show a form page, or use mocked credentials are simulated with model-generated fixtures; a plan of execute versus simulate per node is produced at build time (deterministic classifier plus a model pass). After the run, parameter resolution is replayed for simulated nodes and any expression that resolved to empty or failed is reported. An empty-output check names the first node that returned only empty items, skipping triggers, simulated nodes, binary-only nodes and truncated outputs.
8. Completion obligation: a build is not "done" until evidence exists. States: ready to verify, verified, needs setup, not verifiable (a visible warning, never reported as verified), blocked. Claim wording is generated from the evidence (covered nodes, pending triggers, live state of draft versus published) so the model cannot say "verified" or "live and working" on partial evidence. Publishing while unverified needs an explicit acknowledgement. Each retry reserves an attempt; attempts are capped.
9. For edits, some blocking findings are demoted to informational when the cause existed before and did not change (existing auth values, existing parked Switch). New or changed ones still block. Rule given to the model: preserve unrelated nodes, report the remaining blocker, do not expand the edit.

---

## 4. UX behaviours worth matching

1. Nothing blocks while the agent works: credentials needed later are announced early and the build continues; the user fills a setup panel in parallel.
2. Approval card is attached to the exact tool call in the timeline; title names the thing ("wants to edit <name>"), one line says the effect, structured details with "N more" omission lines. Approve and deny are plain; destructive ones are visually stronger.
3. Questions come as a small wizard with typed answers (pick one, pick many, text), shown inline, and answers are remembered so nothing is asked twice.
4. Task checklist shows planned and background work, with per-task cancel and "correct this task" (send a correction mid-flight).
5. Agent activity tree: orchestrator and child agents collapse to a summary, expandable to tool steps. Tool labels are friendly names, not ids.
6. Run never ends silently (terminal guard) and a cancelled run still shows what was done.
7. Reconnect and refresh restore the full session including an unanswered approval.
8. Live preview of the workflow being built in a side tab, with open tabs reported back to the model so "this workflow" resolves correctly.
9. "Fix with AI" entry from a failed node or execution, pre-filling context (error, node, input); focused nodes can be @-mentioned into the chat.
10. Response feedback (thumbs) per response, linked to the run for evaluation.
11. Verified versus not-verified shown honestly; simulated outputs are labelled as simulated in the editor.
12. Preferences the user states are offered as a card to save, with undo and edit; applied preferences are shown on the next turn.
13. Setup progress, per-node setup cards, credential test button.
14. Empty-state suggestion cards generated from the user's own instance; onboarding opening.
15. Concise style variant: short sentences, one concrete next step, light formatting.

---

## 5. Capability matrix

Legend: exact = same behaviour; partial = some of it; missing = none. Effort S (days), M (1-2 weeks), L (more). Worth: users (U) and business (B), H/M/L.
KaivoFlow refs verified in the current tree (core/ai = core/ai/src/main/kotlin/com/kaivoflow/core/ai; UI = feature/flows/src/main/kotlin/com/kaivoflow/feature/flows/ai).

| n8n capability | KaivoFlow today (verified) | Status | Effort | Worth |
|---|---|---|---|---|
| Tool-calling loop with caps | AgentLoop with iteration, tool-call, token, cost, wall, per-tool limits (core/ai/AgentLoop.kt:8-26, :93, :188) | exact, stronger on limits | - | - |
| Streaming of model tokens and tool steps to UI | Provider interface has only `complete`; UI "live" steps are staged around one blocking call (UI/AiWorkflowAgent.kt:162-199) | missing | L | U H, B H |
| Durable event log, replay, session restore | Chat sessions saved as JSON files (UI/AiSessionRepository.kt:63-77); proposals, thoughts not stored | partial | M | U H |
| Stop / cancel run, abort passed to tools | Loop honours coroutine cancel (AgentLoop.kt:121); UI keeps no job handle, no Stop control (UI/AiAssistantViewModel.kt, tickerJob only) | partial | S | U H |
| One active run per thread | Single ViewModel turn; not enforced as a rule | partial | S | M |
| Human-in-the-loop approval on tool calls | Risk classes + ApprovalPolicy + relay, chat card and notification (Approval.kt:6-95, ApprovalRelay.kt) | exact, better for unattended | - | - |
| Approval card with structured details and model-written one-liner | Card shows tool name and args; destructive needs typed name (UI/ApprovalCard.kt) | partial | S | U H |
| Approval pause excluded from time limits | Wall timer wraps approval wait (AgentLoop.kt:93) | missing (bug) | S | U H |
| Unattended safety (always-on) | Only Read + allow-listed ExternalEffect with caps (Approval.kt:71-95) | n8n has nothing equal | - | B H |
| Orchestrator with skills loaded on demand | SkillRegistry tools exist (skills/SkillRegistry.kt:47-80) but Ask AI turn does not wire them; settings tab is local state | partial | M | U M |
| Skills kept across compaction | no compaction effect (see below) | missing | S | M |
| Prompt generated from live catalogue | AssistantCatalog.stepList, Stable/Beta only (flowedit/AssistantCatalog.kt:27) | exact (arguably better) | - | - |
| Workflow edit tools (small graph tools) | 10 tools on a private FlowDraft (flowedit/FlowEditTools.kt:26) | exact for the old builder style | - | - |
| Tools to read node types, parameter schemas, enum values | Prompt lists step ids; no "get step schema" tool | missing | M | U H |
| Resolve real resource ids (sheets, channels, models) | none | missing | L | U H |
| Read executions / run history / debug a failing node | AssistantTurn has no run tools (FlowEditTools list) | missing | M | U H |
| Run a flow or one step and read output | FlowEngine.run and testStep exist (core/work/FlowEngine.kt:182, :312) but not exposed as tools | missing (engine ready) | M | U H |
| Verify by simulated run (pin data, simulate side effects) | none; only static check_flow | missing | L | U H, B H |
| Completion gate: never say "done" without evidence | none; the model's final text is shown as is | missing | M | U H |
| Validation: structure, schema, expression syntax | FlowValidator via catalog + ExpressionGuard + Apply-time re-check (UI/AiWorkflowAgent.kt:196-198, flowedit/ProposalApply.kt:6-43) | exact | - | - |
| Heuristic checks from incidents (agent prompt without expression etc.) | none | missing | S | M |
| Self-repair loop with error feedback | 2 extra runs with error list (flowedit/AssistantTurn.kt:91-100) | partial | S | U H |
| Repeated-failure signature escalation | none | missing | S | M |
| Edit by small patches, not rewrite | param-level update tool; draft is a copy of the flow | exact | - | - |
| Atomic apply with conflict check and undo | base updatedAt check, exact undo (flowedit/ProposalApply.kt:45-75) | exact, better than n8n | - | - |
| Plan then approve (planner) | no plan step | missing | M | U M |
| Triage question vs build | regex intent classifier, greeting answered locally (flowedit/AiTriageClassifier.kt, AssistantTurn.kt:49) | partial (cheap, brittle) | S | M |
| Questions wizard (ask_user) | none as a tool | missing | M | U H |
| Placeholders instead of asking for setup values | none | missing | S | U H |
| Credentials: never expose secrets | ApiKey never prints, Redactor, secret params not writable (Safety.kt:6-42, flowedit/FlowDraft.kt:99-110) | exact | - | - |
| Credential list/search/setup/test as tools | none; Ask AI cannot see credential metadata | missing | M | U H |
| Credential picker handoff card | none | missing | M | U H |
| Pasted secret handling rule in prompt | none | missing | S | M |
| Untrusted data fencing | per-run nonce fence (Safety.kt:59-75, AgentLoop.kt:103-107) | exact, stronger | - | - |
| URL fetch gate and SSRF filter | EndpointPolicy for model URLs (EndpointPolicy.kt:22-64); no fetch tool for the assistant | partial | M | M |
| Per-domain grants (once / thread / always) | none | missing | M | M |
| Memory: thread history | files per session; last 8 bubbles sent (UI/AiWorkflowAgent.kt:193) | partial | S | M |
| Memory: observer/reflector compression | ConversationCompactor never triggers (ConversationCompactor.kt, Memory.kt:55-56) | missing | M | M |
| Cross-conversation recall | none | missing | M | M |
| Saved user preferences (assistant learns "always use X") | settings tab not persisted (audit AI-03) | missing | M | U H |
| Prompt profiles / versions pinned | single prompt, temperature fixed (flowedit/AssistantTurn.kt:41,76) | missing | S | M |
| Prompt caching | no cache fields | missing | S | B H |
| Retry/backoff for provider errors | none in Providers.kt (grep) | missing | S | U H |
| Finish-reason Length handling | FinishReason.Length parsed (Providers.kt:157,220,282) but loop ignores it | partial | S | M |
| Credits / usage ledger | tokens and cost in step output only; no assistant ledger | partial | M | B M |
| Tool search (deferred tools) | not needed at 10 tools; needed later | missing | M | M |
| MCP client with sanitizing and name check | none; Connectors tab is mock (UI/AiSettingsSheet.kt:108-110, 303-307) | missing | L | U H |
| Browser use | none | missing | L | U M |
| Task board and background tasks | none | missing | L | M |
| Sandbox workspace for code | n/a (no server) | not applicable | - | - |
| Evals: judge, pairwise, programmatic, binary, mocked execution | unit tests only (AssistantFlowEditTest etc.) | missing | L | B H |
| Routing/discovery evals without a model server | none | missing | M | B H |
| Context-budget report | none | missing | S | M |
| Telemetry | none in core/ai | missing (and should be local-only by design) | S | M |
| Error mapping to plain messages | typed ModelException, redacted (Providers.kt) | partial | S | M |
| Terminal guard (never silent end) | UI error cards; no guarantee | partial | S | U H |
| Agent Builder (user-defined agents) | CustomAgent model + UI; runtime gets 8 of ~16 fields; publish only sets a flag (audit AI-01) | partial | M | U H, B H |

---

## 6. Where KaivoFlow can beat n8n on Android

1. Truly local trust boundary. n8n needs a server, RBAC adapters and a sandbox to keep the model away from secrets. On the phone the vault is in-process; the assistant tools can be written so secret material is unreachable by construction (tools get credential ids and display names only; the HTTP gateway injects the secret at the last moment, never in tool results). Add a unit test that scans every tool result for vault values.
2. Approvals on the phone are better than a browser card: heads-up notification with Approve/Deny actions (ApprovalCallback already supports notification), biometric confirm for Destructive and ExternalEffect classes, a lock-screen-safe summary that does not leak data, and a rule that an unattended run can never self-approve. n8n's pending confirmation lives in a tab.
3. Device tools as first-class agent tools, with the same risk classes: notifications, battery and network state, clipboard, share sheet, file read, SMS/call intents (always ExternalEffect with confirm), location, calendar, sensors, app launch, Tasker-style intents. n8n cannot touch any of this.
4. On-device models. Provider seam already exists (OpenAI-compatible includes Ollama). Add a local provider (llama.cpp / MediaPipe / AICore where available) used for: triage (replace regex), greeting, parameter extraction, title generation, history summarising (observer/reflector), embeddings for local recall. Cloud model only for building. Privacy line for the product: "your data does not leave the phone unless you pick a cloud model."
5. Serverless durability. n8n needs a database table for the event log; KaivoFlow can store an append-only per-session event file (or Room table) and replay it into the same reducer, giving restore after process death for free. Use WorkManager for resuming a suspended run only if the user approves from a notification.
6. Cost control that n8n lacks: per-session and per-day budget, visible spend, hard stop. The loop already has the limits; add a ledger and show it in the UI.
7. Offline-first verify: run the flow against pin data on-device; since all engine code is local, "simulate side effects" is cheaper than n8n's model-generated fixtures: a Step spec can declare `sideEffect = true` and a deterministic fixture generator (schema-driven) provides outputs without calling any model for most nodes.
8. Share-to-assistant: Android share sheet, "explain this error" from a notification of a failed run, home-screen quick-tile "ask AI", voice input. n8n has none of these entry points.
9. Phone-sized tool UX: approval, question wizard and credential picker as bottom sheets; they already match the look the owner likes.
10. Always-on safety. The unattended policy (only Read and allow-listed capped ExternalEffect) is a genuine advantage over n8n's agent runtime; market it.

---

## 7. Build tasks, in order

Order is chosen so each task is shippable alone, touches no existing UI layout, and unblocks the next. Module owner in brackets. Acceptance tests are JVM tests unless noted.

T1. Provider reliability [core/ai]
- Add retry with exponential backoff and jitter for rate limit, 5xx and network errors (respect Retry-After), never for auth or policy errors; honour cancellation; cap total retry time inside the wall limit. Fix OpenAI reasoning-model parameter names (audit AI-07). If finish is Length with no tool calls, ask once to continue, else mark truncated in AgentResult.
- Accept: fake client returning 429 twice then success completes; auth error does not retry; cancel during backoff cancels within 100 ms; Length reply produces a continue turn or a `truncated` flag.

T2. Pause wall timer during approval [core/ai]
- Time spent awaiting ApprovalCallback does not count toward maxWallMs; add separate approval timeout (default 10 min, then denied).
- Accept: test where approval takes longer than maxWallMs still completes; approval timeout yields Denied.

T3. Streaming client and event model [core/ai]
- New `StreamingChatClient` (flow of chunks: text delta, reasoning delta, tool-call start/args delta/complete, usage, finish) with adapters for the three wire formats using SSE; `complete` stays and is implemented over the stream. Define `AgentEvent` sealed type (run start, text delta, tool start, tool result, tool error, approval request, usage, run finish with status) emitted by AgentLoop through a callback/Flow.
- Accept: recorded SSE fixtures per provider produce identical event sequences; a tool call split over many chunks reassembles; cancellation mid-stream closes the connection.

T4. Event log and replay [core/data, core/ai]
- Append-only per-session event store (durable facts only; deltas coalesced into block facts) plus a pure reducer `SessionState = reduce(events)` shared by live and restore. Sessions saved atomically (write temp, fsync, rename with result checked) with size caps.
- Accept: kill-and-restore test rebuilds the same visible state from the log; a log with a pending approval restores the card; reducer is deterministic over a golden fixture.

T5. Wire real events into the existing Ask AI UI [feature/flows/ai]
- Replace the staged steps (AiWorkflowAgent.kt:162-199) by the real stream. Keep every composable; only the source of events changes. Add Stop button state (keep Job, rethrow CancellationException, terminal event "cancelled"). Terminal guard: a run always ends with visible text, error or approval.
- Accept: instrumented UI test: Stop during a slow fake model ends in under 1 s with a cancelled bubble; no staged strings remain in the source.

T6. Read tools for the assistant [core/ai, core/data, core/work]
- New tools, all risk Read: list_flows, get_flow (summary), list_runs(flowId, status, limit), get_run (per step status, error, truncated input/output previews, redacted), get_step_schema(stepType) (params, enums, secret flags, credential type), list_credentials (id, name, type, description only), get_credential_types, get_catalog_search(query), list_variables (names only).
- Accept: no tool result contains any vault value (scan test over fixtures with planted secrets); get_run truncates to caps; unknown ids return a structured not-found list.

T7. Run and test tools with approval [core/work, core/ai]
- run_step (uses testStep, pins, TestStep mode) as WriteDraft-or-Read when the step has no side effect, ExternalEffect when it has; run_flow in TestStep mode with pin data and side-effect Steps simulated (see T9); stop_run. Each with approval summary field (one line, model-written) and structured details built by code.
- Accept: running a flow containing an SMS Step in simulate mode sends nothing (fake gateway asserts zero calls); live mode asks approval; timeout cancels the run.

T8. Credential setup handoff [core/ai, feature/flows/ai]
- Tool `request_credential(type, reason, suggestedName)` suspends the turn and shows the existing credential sheet; result is only `{credentialId, name}`. Prompt rules: never ask for secrets in chat, rotate-if-pasted message, auto-select single matching service credential, always confirm generic auth types. Add a chat-input guard that detects obvious secret shapes (key prefixes, long high-entropy tokens) and warns before sending to a cloud model.
- Accept: planted secret in a user message triggers the warning; tool result never includes the secret; model-written params containing a secret shape are rejected by FlowDraft.

T9. Simulation and verification [core/engine, core/nodes, core/ai]
- Add `sideEffect` and `fixtureShape` to NodeSpec (declarative, S per node). Deterministic fixture generator from output schema; optional model-generated fixtures only for custom shapes. verify_flow tool: run with pin data on-device, simulate side-effect Steps, then replay parameter resolution and report expressions that resolved empty, plus first-empty-output Step.
- Accept: golden flows (HTTP then Filter then Notify) report coverage per Step; a deliberately wrong expression is reported as empty; no side effect leaks (fake gateways).

T10. Completion gate and claim builder [core/ai]
- A `BuildOutcome` record (steps covered, simulated, pending triggers, armed state, draft vs applied) and a pure `claimText(outcome)` used to constrain the final message: the loop appends a system note with the allowed wording, and the UI shows a "Verified / Partly verified / Not verified" chip. Not verified is an explicit state, never silent.
- Accept: property tests: partial coverage never yields the verified claim; an applied-but-not-run flow yields not verified.

T11. Repair strategy [core/ai]
- Replace the fixed 2-retry with a state machine: build, check, verify, then patch / rebuild / ask user / fail; failure signature normaliser (strip numbers and ids, sort); same signature twice escalates with "change strategy" plus targeted hints from a small table (HTTP body modes, expression syntax, missing trigger); cap total repairs (default 4) and tokens.
- Accept: scripted fake model repeating one error ends in Failed with escalation once, not looping; different errors continue.

T12. Incident-driven static checks [core/nodes, core/ai]
- Programmatic checks: agent step with a static prompt while a chat/webhook trigger exists; dangling branch outputs; hard-coded secrets in params; schedule without timezone; unreachable steps; sub-flow cycles; trigger count. Each check has a code, severity and a fix hint string given to the model.
- Accept: one test per check with a failing and a passing fixture.

T13. ask_user wizard and placeholders [core/ai, feature/flows/ai]
- Tool `ask_user(questions[])` with single, multi, text types; suspends like approvals; answers stored and never re-asked in a session. Placeholder marker value (`<<needs: hint>>`) allowed in params, flagged by check_flow as "needs setup", rendered as a setup list in the proposal card. Prompt rule: build first with placeholders, ask only when topology changes.
- Accept: a flow with placeholders applies as draft but cannot be armed; answered question is not asked twice (fake model that re-asks gets a refusal).

T14. Planning and triage on a small model [core/ai]
- Optional plan step for big requests (structured plan: summary, trigger, ordered steps, suggested step types) shown as a card with Approve/Edit; triage via a cheap model or on-device model with the regex as fallback.
- Accept: plan schema validated; Approve continues the same turn; triage eval set (30 labelled prompts) reaches at least 90 percent agreement.

T15. Memory [core/ai, core/data]
- Fix compaction (audit AI-10: summary role and dropWhile), tag bubbles by kind so error cards are not replayed (AI-11), observer/reflector style summary stored per session, cross-session recall tool `search_past_chats` over local index (FTS), and `save_preference` with a card (save, edit, undo) persisted and fed to the prompt block. Agent memory default key must include a per-item sender key (AI-06) and persist.
- Accept: 60-message fixture stays under the context budget and still answers a fact from message 3; preference survives restart; two senders never see each other's memory.

T16. Prompt profiles, caching, budget [core/ai]
- Versioned prompt sections (identity, safety, style, language, capability honesty, project/flow scope) with a profile table pinned in settings or debug; Anthropic cache control on the stable prefix; per-turn values (date, open flow name) go in the user turn. Context-budget report command (tokens of prompt, skills, each tool schema) as a JVM test that fails if a budget is exceeded.
- Accept: prompt prefix is byte-identical across two turns of different sessions; budget test prints and enforces limits.

T17. Skills on demand [core/ai]
- Wire SkillRegistry into the assistant turn; ship five skills (flow-builder, debugging-runs, agent-builder, device-automation, credentials-setup); loaded ids stored in session state and re-injected after compaction; persist enable/disable.
- Accept: loaded skill survives compaction in a test; unknown skill id returns real names; toggling persists across restart.

T18. Usage ledger and budgets [core/ai, core/data]
- Per-session and per-day token and cost ledger (local), cap with user setting, exposed to UI as a number. No network telemetry; optional anonymous opt-in counters.
- Accept: ledger totals equal sum of AgentResult usages; cap stops a turn with CostLimit and a clear message.

T19. MCP client (replace mock Connectors tab) [core/ai, core/network, feature/settings]
- Streamable HTTP and SSE transports first; per-server allow/deny tool lists; schema sanitiser; reserved-name check; tool results fenced as untrusted; each MCP tool is ExternalEffect unless the user marks it Read; SSRF policy applies; secrets only in headers from vault. Pinned "mcp help" tool.
- Accept: fake MCP server fixture: shadowing name is rejected; tool list changes are noticed; untrusted fence present; mock UI state replaced by real persisted connections.

T20. Agent Builder runtime parity [core/nodes, feature/flows/ai/agent, core/work]
- Compile all CustomAgent fields to the agent Step (tools of every type, memory, skills, knowledge); publish actually registers triggers with FlowScheduler/WebhookServer/phone events and always-on host (audit AI-01); published agents show real last-run status.
- Accept: published schedule agent runs on WorkManager in an instrumented test; unpublish stops it; fields round-trip.

T21. Eval harness [core/ai test, scripts]
- JVM eval runner with fixtures: (a) routing cases (which tools called or not), (b) programmatic build checks (valid flow, required step types, no placeholders left), (c) pairwise or judge mode optional behind an API key, (d) mocked execution using T9, (e) regression baseline file in git, (f) latency and token report. Model calls recorded and replayable so CI is offline.
- Accept: `./gradlew :core:ai:evalOffline` replays recordings in under 60 s; a seeded regression (prompt change) is detected by baseline compare.

T22. Local model and device entry points [core/ai, feature/settings]
- Local provider adapter, used for triage, titles, summaries; share-sheet entry, "explain failed run" from the notification, voice input.
- Accept: with airplane mode on, triage and title work; cloud call count in a test is zero for greeting and summary.

T23. Domain grants and fetch/search tools [core/ai]
- Research tools with the three-tier gate (once, session, always), trusted allow-list, SSRF check including after redirects, sanitised text, grants stored locally.
- Accept: redirect to a private IP is blocked; "once" grant expires with the run.

---

## 8. Unknowns

1. Instance AI prompt and skill text was read as structure and rules; I did not measure how well each rule works. The numbers in docs (30 percent shorter output) are n8n's own.
2. The cloud proxy path (billing authority, gateway credits, hosted model) is mostly outside the repo; behaviour of quotas beyond what the credit service shows is unknown.
3. Frontend Vue components were only skimmed by name; exact animations, card layouts and keyboard behaviours are not documented here.
4. Sandbox internals (Daytona, the n8n sandbox service) were not read. KaivoFlow has no counterpart, so this matters only if we ever build code-style workflows.
5. How well n8n's simulation plan classifier performs on real data is unknown; its own eval says about 95 percent node coverage, which is the only number available.
6. Provider specifics for KaivoFlow need live tests: reasoning-model parameter rules, Gemini and Anthropic streaming tool-call deltas, Ollama tool-call support per model.
7. Which Android on-device runtimes are good enough for tool calling (AICore, MediaPipe LLM, llama.cpp builds) needs a spike; device RAM varies widely.
8. Whether Play policy allows SMS, call and accessibility-based tools for an automation app is a product and legal question, not answered here.
9. Antigravity's AgentBuilderScreen (2023 lines) was only grepped, not read in full; some fields may be wired that the audit did not see.
10. The repo moved since the audit (new HEAD); I re-verified the cited lines but not every audit item.
