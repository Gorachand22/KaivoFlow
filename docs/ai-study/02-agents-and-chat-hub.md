# 02 - Custom agents, Chat Hub and the agent runtime (n8n 2.48.6 study for KaivoFlow)

Scope: n8n custom agents (packages/@n8n/agents plus cli/src/modules/agents), Chat Hub (cli/src/modules/chat-hub plus the tiny shared packages/@n8n/chat-hub), ai-node-sdk and ai-utilities, and the frontend feature folders features/agents and features/ai/chatHub.
Rule: behaviour only. Nothing here is copied. n8n paths are pointers. Everything about KaivoFlow was checked in code (file:line) unless marked "from audit-v2/05".
Read first: docs/audit-v2/05-ai-assistant-agents-connectors.md and core/ai/README.md. This doc does not repeat their issue list (AI-01 .. AI-24); it explains how n8n solves the same problems and what to build.

Method note: the agents module is large (about 150 files). I read the entities, the config schema, the publish, task and budget services, the runtime reconstruction, the tool-result guard, stall detection, the untrusted-output wrapper, the Chat Hub entities and services, the builder prompts, the module docs (BUILT_IN_TOOLS.md, TEST_CHAT_RECOVERY.md, PLAN_TASK_STATUS.md) and the eval contracts. I did not read the Vue components line by line (only file names), the sandbox/workspace providers, the vector store drivers, or each chat channel adapter. See section 8.

---

## 1. Two different products that share a name

n8n has two separate "agent" products in the same codebase. They look similar in the UI and are not the same thing.

| | Chat Hub personal agent | Custom Agent (agents module, "SDK agent") |
|---|---|---|
| Where | cli/src/modules/chat-hub, frontend features/ai/chatHub | cli/src/modules/agents, frontend features/agents |
| What it is | A saved preset for one chat model: name, icon, suggested prompts, system prompt, provider, model, credential, a few knowledge files | A full versioned definition: model, instructions, tools, skills, memory, MCP servers, sub-agents, channels, scheduled tasks, budget |
| Owner | One user (ownerId) | A project (projectId), or the instance for code-defined system agents |
| Runtime | Builds a throw-away archived workflow for every reply (chat trigger, AI Agent node, model node, memory node, tool nodes) and runs it through the normal workflow engine | Its own agent runtime (the @n8n/agents loop). No workflow is generated |
| Lifecycle | Create and edit, no draft or publish | Draft, saved versions, publish, unpublish, rollback |
| Runs unattended | No. Only when a person sends a message | Yes: scheduled tasks and chat channels (Slack, Telegram, Teams, WhatsApp, Discord, Linear) |
| Sharing | Private to the owner | Project roles and scopes; optional "available in MCP"; "make available" in the n8n Chat page |

KaivoFlow's Agent Builder is the second product (it has triggers, publish, tools, skills). KaivoFlow's Ask AI is closer to a third thing (the Instance AI assistant, covered by audit-v2/05). The Chat Hub idea (a plain multi-model chat with presets) has no KaivoFlow equivalent, and for a phone product it is cheap, see T12 in section 7.

---

## 2. What an agent IS in n8n (data model)

### 2.1 The agent row (cli/src/modules/agents/entities/agent.entity.ts)

An agent is one database row plus children. The row holds:

- identity: name (128), project, scope (project or instance);
- the draft definition: one JSON column `schema` (the "agent JSON config"), a JSON map of custom code tools (source code plus a descriptor per tool), channel integrations as a separate JSON column, and a legacy skills column that is no longer read (skills moved to a shared skills module, referenced by id and optional pinned version);
- version pointers: `versionId` (the current saved draft version), `activeVersionId` (the version that is live, null when unpublished);
- two counters used for optimistic concurrency: `revision` (bumped by draft writes) and `publicationRevision` (bumped by publish, unpublish and live channel edits);
- flags: available-in-MCP, a one-time "setup completed" timestamp used for telemetry.

Design idea worth copying: the draft and the live version are different things, and the live one is a pointer to an immutable snapshot, not a boolean.

### 2.2 The JSON config (packages/@n8n/api-types/src/agents/agent-json-config.schema.ts)

The whole behaviour of an agent is one strict, validated document. Fields in plain words:

- name, description (max 512), model as "provider/model-name" (empty allowed while the draft is incomplete), credential id, instructions (free text);
- personalisation: icon plus a gradient;
- memory: enabled flag, storage backend, optional observational memory (observer and reflector models, token thresholds) and optional episodic memory (long-term entries, top-k recall, per-run write cap, include and exclude topic guidance);
- subAgents: up to a bounded number running in parallel, a list of referenced agents with a "use when" hint, and optional model-by-difficulty mapping (low, medium, high);
- tools: a list of three kinds. Custom (code, by id), workflow (an existing workflow, with per-input binding modes: model decides, fixed value, expression), node (any n8n node as a tool, with its parameters and credentials). Each tool can be disabled and can require approval;
- skills (refs), tasks (refs with enabled flag), providerTools (native provider tools such as native web search), integrations (chat channels), mcpServers (up to 20, transport SSE or streamable HTTP, auth mode, credential, per-tool read/write permissions, timeout), vectorStores (up to 20, four backends, each with a use-when text and an embedding model);
- config block: reasoning effort, prompt caching, web search (native, Brave, SearXNG), tool-call concurrency (1 to 100), max iterations (1 to 200, "do not set unless asked"), guardrails with a budget guardrail.

Two schemas exist: a lenient one for drafts and a "runnable" one that demands model and credential. The same document is what the builder AI reads and patches, what REST returns, what publish snapshots, and what the runtime is built from. One document means one validation path.

### 2.3 Children tables (entities folder)

| Table | Purpose |
|---|---|
| agent_history | Immutable snapshot of a definition (schema, tools, name, description, author, publishedById). Content is immutable, only name and description of the version can be edited |
| agent_publish_history | Append-only log: activated or deactivated, which version, who, when |
| agent_task (draft) and agent_task_snapshot | Scheduled task bodies. The draft lives in one table; each published version gets its own snapshot rows (name, objective, cron, timezone, enabled) |
| agent_skill_dependency and agent_version_skill_dependency | Draft skill refs and the pins of a saved version |
| agent_workflow_dependency, agent_credential_dependency | Index of what the agent uses, so deleting a workflow or credential can warn |
| agent_execution_thread | One session (a thread): owner (user or project scope), title, parent thread for sub-agents, task id, running totals of tokens, cost and duration |
| agent_execution | One turn in a thread: status, user message, model, token counts including cache read and write, cost, a stored timeline of events, error, finish reason, guardrail code, HITL status, source, parent execution |
| agent_message and agent_thread | The conversation transcript the model sees (message content plus separate "model content" so the model can be shown something different from the UI) |
| agent_checkpoint | Serialized run state for suspended runs (waiting for approval) and step checkpoints for crash recovery |
| agent_thread_grant | "Allow for this session" approvals, keyed by tool (and integration action) |
| agent_message_queue | Durable queue of messages sent while a turn runs, with position, steering reservation |
| agent_background_job | Detached sub-agent or workflow jobs with status, result, files, timeout |
| agent_plan and history | Structured plan with tasks, dependencies and revisions |
| agent_budget_spend and applied-call | Spend ledger per key and month, idempotent per call id |
| agent_memory_entry (+ sources, candidates, locks) and agent_observation (+ cursor, lock) | Episodic and observational memory |
| agent_chat_attachment, agent_file_grant, knowledge files (separate knowledge module) | Files |
| agent_channel_status, agent_chat_subscription | Per-channel health, platform thread subscriptions |

---

## 3. Lifecycle: draft, test, publish, run, history

### 3.1 Draft
Every edit goes through one write path that (1) validates the JSON with the lenient schema, (2) runs the policy check (see 5.6), (3) saves under a fenced write (the caller passes the revision it read; a stale write is rejected), (4) captures a new saved version in the same transaction, (5) broadcasts an update event to other tabs. The draft may be incomplete (no model yet). A one-time "setup completed" marker is stored when the draft first becomes publishable.

### 3.2 Test
Testing is the same runtime as production, run in preview chat:
- The preview chat uses a per-user draft memory scope and the draft definition. The runtime is rebuilt for the user: node and workflow tools the user cannot execute, or whose credentials the user cannot read, are dropped before the model sees them.
- Preview adds an extra instruction block telling the agent that it may modify its own setup through tools (builder tools), so the same chat can build and test.
- A runtime cache keeps built runtimes alive for 30 minutes of inactivity (sliding) with a hard cap, and rebuilds when the user's access snapshot no longer holds.
- Test turns survive a closed browser. The server stores the message in a durable queue before acknowledging, records an execution, and the client re-reads the transcript. Stop is a DELETE on the execution and works across tabs and across server processes. See TEST_CHAT_RECOVERY.md for the contract: queue panel, reorder, edit pending message, "steer" (inject a queued message into the running turn at the next safe boundary), stale-request protection, a per-chat lock shared by admission and stop.
- A "Run now" action exists for scheduled tasks and runs the live draft body, not the published one.
- A separate evaluation feature (modules/agent-evals, section 6.8) runs cases against a detached copy of the config.

### 3.3 Publish (cli/src/modules/agents/agent-publish.service.ts)
In order:
1. Reject if the caller's expected revision is stale (conflict).
2. If the selected version is already active, only update version metadata (idempotent).
3. Choose the target: the current saved version, or an older version id (this is also how rollback works: publish an old version).
4. Policy gate for the target schema (organization rules about allowed node types, MCP servers).
5. Full validation of the target (the "publish" scope is stricter than the "runtime" scope): required fields, model/credential compatibility, credentials exist and are usable by the project, each tool resolves (a workflow tool must itself be published), sub-agent refs exist, skills exist, task cron strings valid, channels have credentials, MCP servers have credentials, vector stores have no name clashes. The result is a list of issues, each with a code, a path (so the UI can jump to the field) and the capability that owns it. A publish with any issue is refused with a readable message.
6. Pre-check that channels can start (for example a Telegram bot token already claimed by another agent). This runs before anything is written, so a refusal leaves no partial state.
7. In one transaction: set the active version with a fenced compare on publicationRevision, record who published, append to publish history.
8. After commit: emit events, clear cached runtimes, reconcile services outside the transaction: connect or disconnect channels, re-register scheduled tasks. Failures here are logged and healed later by a reconciler. They do not roll back the publish.

Unpublish is the mirror: clear the active pointer, log "deactivated", disconnect channels, drop crons. Revert-to-published and revert-to-version restore a draft from a snapshot as a new draft version.

Important consequences:
- Editing the draft never changes what runs. Live behaviour changes only on publish ("republish to apply").
- Scheduled tasks and channel bodies are read from the published snapshot at fire time.
- The publishing user is stored on the version. Unattended runs use it as the execution identity pointer (nulled if that user is deleted).

### 3.4 Run
Four entry points, all through one execution orchestrator (agent-execution-orchestrator.service.ts):
- Preview chat (draft, user scoped).
- n8n Chat page and chat channels (published, project scoped or user scoped by channel).
- Scheduled task (published snapshot, project scope, task-specific memory resource).
- Workflow node: an Agent can be embedded inline in a workflow node or referenced as a tool or sub-agent.

Each turn: admit (lock, create thread and execution row, register a cancel handle), build or fetch the runtime, stream chunks to a recorder that writes the timeline, finalize (tokens, cost, finish reason, error) and update thread totals.

### 3.5 History and observability
- Sessions list per agent with filters, a timeline panel per session (model calls, tool calls with inputs and outputs, sub-agent children, approvals, guardrail stops).
- Version history panel with author, name, description per version, publish log.
- Interrupted executions: a sweeper runs periodically; an execution not updated for 2 minutes whose process is gone is marked interrupted. Valid approval checkpoints are kept.
- Optional export of sessions to an external tracing system.

---

## 4. Runtime architecture (end to end)

### 4.1 Component diagram (text)

```
 Client (Vue)                               Server (Express, one or many mains)
 +----------------+  REST + SSE             +-----------------------------------------------+
 | Agent builder  |<----------------------->| agents.controller / agent-chat.controller      |
 | Preview chat   |   (stream chunks,       |   |                                            |
 | Sessions view  |    update pushes)       |   v                                            |
 +----------------+                         | AgentExecutionOrchestrator ---> ExecutionRecorder -> agent_execution (+timeline)
                                            |   |  admit/lock, queue, steer, cancel handles   |
 Chat channels (Slack, Telegram,...)  ----->|   v                                            |
 Scheduler (cron per published task) ------>| RuntimeReconstruction (config -> Agent object) |
                                            |   | attaches: model, tools, MCP, skills, memory,|
                                            |   | knowledge, plan, sub-agents, bg jobs,       |
                                            |   | channel tools, guardrails, budget           |
                                            |   v                                            |
                                            | @n8n/agents AgentRuntime (loop) <-> model provider (AI SDK, streaming)
                                            |   |  tool calls: node / workflow / custom (isolate) / MCP / built-in
                                            |   v                                            |
                                            | RunState (checkpoint) | MemoryOrchestrator | GuardrailRunner | SpendLedger
                                            +-----------------------------------------------+
```

### 4.2 The loop (packages/@n8n/agents/src/runtime/loop/agent-runtime.ts)
Behaviours that matter:
- Hard cap of 100 loop iterations by default, overridable per run from config (1 to 200). Reaching the cap ends the run with a "can continue" flag instead of an exception, so the UI can offer Continue.
- Streaming by default. The consumer writes events (text delta, reasoning delta, tool call, tool result, suspension, finish) to a sink. A non-streaming "generate" path shares the same loop.
- Empty model turns (no text and no tool calls) are retried up to 2 times.
- Stream stall detection (runtime/streaming/stream-stall.ts): 90 seconds of silence between chunks once content started, 3 minutes before the first chunk (large prompts can legitimately take that long), one silent retry only if nothing was shown yet, then a typed error with a plain message.
- Tool calls run through a runner with configurable concurrency (default 1). A bad tool input or unknown tool name goes to a repair function: legacy tool names are mapped, and an empty-object input for a no-arg tool is fixed. Otherwise the error goes back to the model.
- Every tool result passes a guard: results above 50k tokens are truncated with head and tail, or offloaded to the workspace file system and replaced by a pointer telling the model which tool reads it. Binary and file parts are handled separately.
- Untrusted output: tools can declare their output untrusted. The runtime wraps it in a tagged block that says "reference data only", escapes any closing tag inside, strips invisible Unicode (zero-width, tag characters, bidi controls) and adds a one-line rule to the system prompt once per run (sdk/untrusted-content.ts).
- Suspension: a tool can suspend the run (human approval, question card, long wait). The state is written to a checkpoint. Resume validates the resume payload against the tool's schema, claims the checkpoint (a second resume gets a stale-resume error), and continues. A crash resume path restarts from the last step checkpoint (a step checkpoint is written at each model-step boundary); it injects a note that the previous attempt may have partly run so the model should verify before retrying side effects.
- Cancellation is an AbortSignal threaded to model calls, tool calls, MCP, sandbox. Cancellation is never turned into an answer.
- Steering: queued user messages are applied at a boundary between a finished tool batch and the next model call, after the consumer acknowledged that previous output reached the recorder.
- Memory is loaded before and saved after. A memory orchestrator also runs side calls (observer, reflector, title generation) whose usage is forwarded to the parent run's cost.
- Cost and token usage are recorded per model call, including cache read and write tokens, and prompt size. Side-call cost is added.

### 4.3 Guardrails and budget
- Guardrail builder (sdk/guardrail.ts): a type (PII or jailbreak style), a strategy (block, redact, warn), the PII types to detect, a threshold. A runner applies them to input and output; a streaming redactor handles partial text. A stop sets a guardrail code on the execution.
- Budget guardrail (cli/.../budget-guardrail.ts): config has monthly USD budget, alert percent, and a per-session cap. The ledger stores spend per key and month, shared across server processes, idempotent by call id, buffers in memory if the database fails (bounded to 10,000 calls) and flushes in order. A monthly alert fires once when the line is crossed and emails the project creator. In preview chat the "approaching budget" notice is a card and the run continues. Sub-agents take the session cap from the root; monthly spend is counted once.

### 4.4 Tools: how each kind runs
- Node tool: any node can be a tool. The schema comes from placeholders in the node parameters ("fromAI" style), else from asking the node to build its tool and reading the schema, else a single string input. Each call executes the node in an isolated executor with the stored credentials. Optional approval.
- Workflow tool: runs an existing workflow as a sub-execution. Inputs bound per field: model decides, fixed, expression. Fixed values are hidden from the model schema. Test runs use the draft workflow; production runs use the published workflow. Timeout 120 seconds by default. If the workflow parks on a Wait node: a short bounded wait (inside a window) is polled; a long wait becomes a background job with a receipt the model can check later, or a suspended card the user can resume or abandon. The registration of that job is race-safe against the workflow finishing, and never throws (a thrown error would make the model retry and duplicate the run). A tool whose workflow is gone, unpublished or incompatible still appears in the list under its configured name and returns the current reason on each call, instead of vanishing.
- Custom tool: code in an isolate pool (runtime/agent-isolate-pool.ts), with polyfills, no network unless granted.
- MCP tool: connection manager per run, tool names normalized and de-clashed, tool permission by read/write category with per-tool overrides, redirect checks, connection timeout, SSRF-guarded transport.
- Built-in tools (BUILT_IN_TOOLS.md): environment (date, timezone), read binary data, workspace file tools, import files, knowledge find/search/read, chat channel context and action tools, delegate sub-agent, todo list or plan tools, background jobs, mark session failed, load skill. The module doc states a rule: keep the count low, extend an existing tool by adding an action instead of adding a new tool, deferred discovery for large catalogs.
- Provider-native tools (native web search) run inside the model call.

### 4.5 Approvals (cli/.../agent-tool-approval.service.ts and tool approval in the SDK)
- A tool can be marked "requires approval" (always) or conditional (the tool decides from the input). MCP servers and channels use the same approval shape: global or a selected list of tool or action names.
- When a call needs approval the run suspends. The card shows display arguments (built by a function that looks at the tool's schema, so secrets and long values can be hidden).
- Decisions: approve once, approve for this session (stored as a grant keyed by tool or by channel action, so it survives reload), deny. Telemetry records the choice.
- On denial the model receives a denial result and continues.
- Approvals on a channel (for example Slack) render as platform cards and resume through the same checkpoint path.

### 4.6 Scheduled tasks (agent-task.service.ts, scheduling/)
- A task is name, objective text (max 10,000), five-field cron, optional IANA timezone. It is a draft row plus an enabled ref in the agent JSON.
- Only published snapshot rows are scheduled. Only the leader process registers crons. On leader change, jobs are dropped and re-registered. A durable scheduler path also exists with a lease; its handler must answer "started or skipped" quickly and not wait for the run.
- At fire time the handler re-reads the agent's active version. If the task is no longer published or enabled, it deregisters itself and reports stale.
- A run lock per (agent, task) with a 5 minute TTL renewed every minute. If the previous run is still active, the new tick is skipped (no overlap).
- The agent receives one message: the objective plus the current date and time and, if the schedule's timezone differs from the instance's, a note naming it. Each run is a new thread (a fresh id) with a task-scoped memory resource, so scheduled runs do not read chat memory.
- Unattended: plan tools and background tasks are switched off for task runs. Approvals cannot be answered, so tools that require approval need the human path; the product relies on the author not marking tools that way for tasks, plus the `mark_session_failed` tool for the agent to report that it could not finish.
- Manual "Run now" runs the draft body as the clicking user, in the background, and appears as a session.
- A global switch can disable the whole agents feature, which makes scheduled runs skip.

### 4.7 Channels (integrations/)
- Channels: Slack (managed and own app), Telegram (private allow-list or public), Teams, WhatsApp, Discord, Linear, and n8n Chat. A channel entry has a credential, settings, an approval policy, optional idle timeout after which a new session starts.
- One credential can be claimed by only one agent (checked before publish).
- A reconciler keeps channel connections in step with publication state, reports health per channel, rate limits per channel, splits long messages, shows typing indicators, handles file uploads and downloads with retention tasks.
- For each connected channel the runtime adds a context tool (read platform info, search users, bounded by a 30 second query deadline and paginated with cursors) and an action tool (send message and other actions, subject to the approval policy).
- Memory scope for a channel decides whether episodic memory is allowed.

### 4.8 Sub-agents and background work
- delegate_subagent: foreground waits for the child, background returns a receipt and a job row. Children inherit the parent's credential provider and, when a user was present, that user's access filter. A child can only be a published agent in the same project, or an inline copy of the parent.
- Background jobs have a timeout, a result, files, and a "notified" marker. A wake service injects a "background updates" block into the next model call (volatile instructions, not saved in history) and can start a new run when a job finishes while the user is away.
- Plans (format 2): a plan document with tasks and groups, dependencies, a single source of status per task (parent-run: stored; delegated: derived from the job; group: derived from children). Stop pauses unfinished tasks; Continue resumes a paused job or asks for a new one.

### 4.9 Memory and knowledge
- Window memory (thread-scoped transcript), observational memory (an observer model compresses raw messages past a token threshold into an observation log, a reflector compresses the log; both are locked per thread and run as side calls), episodic memory (long-term facts extracted after a run, deduplicated by content hash, recalled by embedding similarity with top-k and a per-run write cap, with include and exclude topic guidance). A memory trace per turn tells the UI which entries were used ("memory used" chip).
- Knowledge: files up to 25 MB each, 10 per upload, 1.5 GB per agent. They live in a sandbox file system; the agent gets find_file, search_text (regex) and read_file tools. Retrieval is therefore file-grep style, not vector, unless a vector store is configured (four backends, one search tool per store, named search_<name>).
- Skills: a catalog (name, description, id, optional recommended tools) is rendered into the prompt; a load_skill tool pulls the body on demand; the loaded set is kept outside the transcript and re-rendered in a block so compaction cannot lose it. A skill belongs to a project or the instance, has versions, and an agent follows "latest" or pins a version. Folder skills can have reference files.

### 4.10 Security model
- Every tool and channel runs under a trust boundary: with a user present, tools are filtered by that user's workflow-execute scope and credential-read rights, and the runtime is rebuilt if rights change. With no user (published, channel, task), the boundary is the project.
- Credentials are referenced by id in config and resolved by a credential provider scoped to project and optionally user. Config sanitizers strip credential ids the project cannot see; there is a "managed" token for credentials supplied by the platform gateway.
- Policy service reuses the workflow policy points: an agent is treated as a workflow whose nodes are its node tools and MCP servers, so org rules about blocked node types apply at save and publish.
- Code tools run in isolates. MCP traffic uses an SSRF-guarded fetch with redirect checks.
- Files carry grants per thread; a tool can read a file only if the thread can.
- Sensitive error text is kept out of the model; MCP, tool and integration errors are mapped to codes.

---

## 5. How an agent is shared, scheduled, limited

### 5.1 Sharing
- Agents live in a project; project roles carry agent scopes (read, execute, edit, publish). Test sessions are private to the user; integration, task and workflow sessions are project-scoped.
- "Available in MCP" lets MCP clients with agent scopes operate the agent.
- The n8n Chat page lists agents the project made available, with a picker, thread history, and a "make available" modal. Chat Hub personal agents are private.
- Agents can be duplicated (same project shares the same stored skills), imported and exported as JSON (a JSON import modal exists).

### 5.2 Per-user credentials
- Chat Hub: the model credential is chosen per session (stored on the session) or fixed on the personal agent. At send time the credential must be readable by the user (`credential:read` check) and the run happens in the user's personal project.
- Agents: the credential is one id inside the config. In preview, node tool credentials must be usable by the user; in production the project owns them. There is no per-end-user credential for channel users in the agents module (an unknown, see section 8: a separate dynamic-credentials module exists).

### 5.3 Usage limits
- Per agent: monthly USD budget with alert percent, session USD cap, max iterations, tool-call concurrency.
- Per message: attachment size 10 MB; knowledge limits above.
- Per tool result: 50k token guard.
- Per model call: stream stall limits.
- Chat Hub: context window trimming per model (a table of context limits; unknown models get no trimming), per-provider enable switch and allowed-model list set by admins.
- Instance: a global enable switch (Chat Hub is disabled by default).
- Not found: no per-user daily token quota, no rate limit on preview chat other than the queue.

### 5.4 Concurrency
One active execution per chat (lock on admission and stop), pending messages in a durable queue, two waiting SSE streams max, cron runs de-duplicated by a lock, publish and draft writes fenced by revisions, multi-process aware (pub/sub events, leader-only cron).

---

## 6. Chat Hub (cli/src/modules/chat-hub) in detail

### 6.1 Data model
- chat_hub_sessions: id, title (256, auto-generated after the first reply), owner, last message time, credential id, provider, model, workflow id (for "n8n workflow as model"), agent id (personal agent), cached agent name, type (production chat or manual canvas test), many-to-many to tools.
- chat_hub_messages: id, session, type (human, ai, system, tool, generic), name, content text, provider, model, workflow id, agent id, execution id (nullable, set null when the execution is deleted), previousMessageId (a tree: each message points at its parent), retryOfMessageId, revisionOfMessageId, status (running, success, error, cancelled), attachments JSON.
- chat_hub_agents: name, description, icon, suggested prompts, system prompt, owner, credential, provider, model, knowledge files.
- chat_hub_tools: a user-level library of node definitions (name, node type, version, full node JSON, enabled by default). Sessions copy the enabled set at creation and keep their own tool membership afterwards.

### 6.2 Conversation is a tree, not a list
Because every message has a previous-message pointer, "edit my message" creates a revision sibling and "regenerate" creates a retry sibling. The UI shows alternatives per position and the history sent to the model is the path from the root to the chosen message. Regenerate only works on AI messages; it replays the path up to the last human message.

### 6.3 One send, step by step (chat-hub.service.ts sendHumanMessage)
1. Resolve the credential id from the model's provider type.
2. In one transaction: get or create the session; check the previous message exists; build history along the tree; validate attachments against the model's upload policy (allowed MIME types); store attachments through binary data service; insert the human message; resolve tools (a new session copies the user's enabled tools, an existing one uses its own); prepare the reply workflow.
3. Broadcast the human message to all connections of the user (cross-device sync).
4. If a previous execution is waiting (a tool asked for input), resume it instead of starting a new one.
5. Otherwise start the execution in the background (fire and forget). On failure of step 2 attachments are cleaned up.

### 6.3b The generated reply workflow
A chat trigger, an AI Agent node (tools agent, streaming on, max tokens from memory set from the model's context window), the model node for the chosen provider, a memory node pre-filled with the history, tool nodes, optional vector search for agent knowledge. The workflow is saved archived, named with the session id, executed, then deleted (a cleanup flag exists for debugging). The workflow is policed like any other, so org policies apply.

### 6.4 The system prompt Chat Hub gives the model
Built from small parts joined by blank lines (see chat-hub-workflow.service.ts, getBaseSystemMessage, getSystemMessageMetadata, buildCustomInstructionsContext, buildFileKnowledgeContext):
1. A role line. Plain chat: "helpful assistant". Personal agent: "combine provided tools and knowledge to answer".
2. Current date and time with the user's timezone and a rule: use this when you need "now".
3. Output capability rules: the model may describe or analyze media but must not claim to generate it, and a document protocol: the model writes special XML-like commands (create a document with title, type and content; edit with exact old and new strings and a replace-all flag) directly in the answer, never in code fences, always with some conversational text. The server parses these commands out of the stream and shows documents in a side panel (packages/@n8n/chat-hub parser and artifact modules). Existing documents are summarized into the next prompt so the model can edit them.
4. For personal agents: the user's system prompt quoted with a "> " prefix on every line under a heading, so the model can tell user instructions from platform rules and so the text cannot break out of its block.
5. For agents with files: a listing of file names, a rule to search them with the search tool, not to mention them proactively, and a rule to cite the source with a footnote.

Prompt design lessons: platform rules first, user instructions fenced and labelled, knowledge described by name not pasted, the date is always injected, structured output via parseable commands rather than a new tool.

### 6.5 Streaming and reconnect
- Chunks go to the browser over a push channel (WebSocket or SSE depending on setup) with a per-session sequence number. The server buffers chunks per session (memory for a single process, Redis for multi-process or queue mode) with a TTL, so a client that reloads asks for chunks after sequence N and the stream resumes. An "execution" can contain several messages (for example tool use then answer); begin and end events exist for both.
- Stop sets the message status to cancelled after stopping the execution; only a message in status running can be stopped.
- A relay forwards events between processes via pub/sub.

### 6.6 Titles, models list, settings
- Title is generated by a small model call after the first exchange (chat-hub-title.service.ts), only if the user did not set one.
- The models list is assembled per provider from live provider APIs using the user's credentials (about 850 lines in chat-hub.models.service.ts), plus workflows exposed as models and custom agents. Models with an admin allow-list are filtered. Provider settings (enabled, credential, allowed models) are stored in the settings table per provider, and a semantic-search setting selects the vector store and embedding model for agent knowledge.
- Context window table: a hard-coded map of model to token limit; no entry or zero means "do not trim".

### 6.7 Chat Hub gaps in n8n worth noting
No cost tracking per message, no budget, no scheduling, no publishing, text history only (artifacts are parsed from text). Messages are linked to an execution so a user can open the run.

### 6.8 Agent evaluations (cli/src/modules/agent-evals, features/ai/evaluation.ee)
- Cases (input, expected answer, optional per-tool resume overrides like deny) are generated or written by hand; checks are programmatic (contains keywords, JSON validity, string similarity, tool-call accuracy) or LLM judges (correctness, helpfulness, categorization, custom criteria), with the judge response parsed defensively.
- The runner prepares a detached snapshot of the config, builds the runtime uncached with instrumentation seams (fake model transport, fake MCP transport, mocked web search, mocked node-tool data and credentials), and removes features that cannot be evaluated safely (it reports "skipped features").
- During evaluation suspended approvals are auto-approved unless the case says deny.
- Results show per-case tool calls; users can thumbs up or down a result; the system can suggest a fix and rewrite the instructions from failed cases. This is a closed loop: generate cases, run, judge, propose a fix.

### 6.9 Builder agent (the AI that edits an agent)
Prompts (builder/prompts) and skills (builder/skills) teach the builder AI to edit the JSON config. Notable rules:
- Config reads return a hash; every write passes the hash it last saw. Stale hash means re-read. Writes take only the top-level fields that change (omitted fields keep their value) or an RFC 6902 patch array. Errors say "nothing was saved, fix and retry with the same hash".
- Put all known changes in one write.
- The schema is rendered into the prompt in a compact text form generated from the same Zod schema the server validates with, so prompt and validator cannot drift.
- Never write placeholder instructions; fresh agents need real instructions before config is written.
- Never copy credential ids from lists; use a resolve-LLM tool and a credential setup tool.
- Defaults the builder must not touch unless asked: max iterations, prompt caching.
- Native web search only for providers that support it; other providers use a search tool credential.
- When removing or renaming a field, read every attached skill and update references, but ask the user before editing a skill because it may be shared.
- During an initial build, never suspend mid-build; defer setup questions (credentials, channels) to the end.
- Interactive tools exist for configure-channel, resolve-llm and finish-setup, so setup steps are cards in chat, not text instructions.

---

## 7. ai-node-sdk and ai-utilities (summary)

- ai-utilities (src folders: adapters, agent-config, chat-model, converters, gen-ai, json-schema, llm-output, memory, model-discovery, node-catalog, suppliers, web-search) is the internal toolbox: chat model adapters, message converters, JSON schema from Zod, model discovery, the node catalog used to find tool nodes and required credential slots, web search helpers, provider prefix parsing used by both the agents module and validation. Backward compatibility rule: never remove interface fields; bump an SDK version constant on breaking public changes.
- ai-node-sdk re-exports a public subset so community authors can write chat model and memory nodes without LangChain: a simple config object for OpenAI-compatible providers, or a custom class with a "supply model" hook.
- Relevance to KaivoFlow: a single-user app does not need a community SDK, but the idea of one provider catalog with model discovery and capability flags (supports tools, native search, reasoning, context window) shared by builder, validation and runtime is worth having. KaivoFlow's ModelDiscovery and ProviderEndpoints are already close; capability flags and context window are the missing part.

---

## 8. UX behaviours worth matching

1. Setup tasks list: a checklist panel (AgentSetupTasks) driven by the validation issues; each issue links to the field.
2. Publish button states: disabled with a reason, "Publish", "Republish" (changes pending), a "published" modal that shows how to reach the agent (channels, chat page). Publish shows the validation list first.
3. Version history with author, name, description, and one-tap "restore as draft" and "publish this version".
4. Collaboration banner when someone else edits; fenced writes with a friendly reload.
5. Sessions panel with filters (source: preview, channel, task, workflow), a timeline per session with each tool call expandable, child sub-agent timelines (text capped), a "memory used" chip, tool policy refusal card, retry-on-error card, task summary and "task stopped" notice.
6. Chat composer: queued messages panel with drag reorder, edit, remove, and "send to current run" (steer).
7. Approval card: what tool, what arguments (redacted), buttons once, for this session, deny.
8. Budget UI: monthly and per-session modals, notice cards in chat at the threshold, alert email.
9. Model selector with credentials grouped by provider and a "resolve for me" action.
10. Eval screens: cases list, checks, try row, thumbs, suggestion card.
11. Chat Hub: greeting with suggested prompts, side panel for generated documents, edit and regenerate with sibling navigation, title auto-generation, cross-device sync of the human message.
12. Empty states that show what the feature can do (AgentChatEmptyState, evals preview).

---

## 9. Comparison table: n8n capability vs KaivoFlow today

Effort: S under 2 days, M under 2 weeks, L more. Worth: H sells the product, M useful, L nice to have.
KaivoFlow evidence is from code read for this study (file:line) or from audit-v2/05 where noted.

| n8n capability | KaivoFlow today (verified) | Status | Effort | Worth |
|---|---|---|---|---|
| Agent as one validated JSON document with draft and lenient/runnable schemas | CustomAgent data class with 17 fields (feature/flows/.../CustomAgent.kt:74-95), hand-written JSON mapping in AgentRepository.kt (json.put at :151, optInt at :342). No schema validation, no version field | partial | M | H |
| Draft vs published as a pointer to an immutable snapshot | `isPublished: Boolean` (CustomAgent.kt:91), flipped at AgentBuilderViewModel.kt:548 and FlowListScreen.kt:180. No snapshot, no pointer | missing | M | H |
| Publish pipeline: validate, policy gate, arm, atomic swap, log | The machinery exists for Flows: ActivationCoordinator.publish/publishChanges/pause/unpublish/restoreAtStart (core/engine/.../ActivationCoordinator.kt:116,142,172,195,246), state machine Draft/Published/Paused/Error (ActivationStateMachine.kt:11). Agents do not use it | missing for agents, strong base | M-L | H |
| Validation issues with code, path and owner for setup checklist | PublishValidator exists for Flows (ActivationCoordinator.kt:70-86 uses it). No agent validator | missing | S-M | H |
| Version history, rollback, publish log | `flow_versions` for Flows (PublishedRevision, ActivationCoordinator.kt:12-14). None for agents | missing | M | M |
| Scheduled tasks bound to the published snapshot | spark.schedule Spark with frequency, time, timezone, Wi-Fi only (ScheduleTriggerNode.kt:25-55) and the always-on engine. AgentTrigger is a label (CustomAgent.kt:9-16) | partial (engine yes, agent link no) | M | H |
| Run lock per task, skip overlap | RunHost returns Busy if the Flow is running (RunHost.kt:55-56 area, `flowEngine.isRunning`) | exact (per Flow) | - | H |
| Fresh thread and task-scoped memory per scheduled run | AgentNode memory key defaults to flowId:stepId and the store is in-process (AgentNode.kt:59,140,~159) | partial and unsafe (AI-06) | S | H |
| Channels as triggers | Spark types: webhook, notification, sms, share, battery, geofence, bluetooth, wifi, headset, boot (TriggerSpecs.kt:12-23). No chat channel (Telegram etc.) | partial (phone-native yes, chat apps no) | M | M |
| Test chat uses the draft, user scope, rebuilt for the user | Test Run compiles one-Step Flow and calls testStep (AgentFlowCompiler.kt, FlowEngine.kt:312). One shot, not a chat; session list capped by nothing (AI-21) | partial | M | H |
| Durable message queue, steer, stop across tabs | None. No stop button (AI-13) | missing | S (stop) / M (queue) | M |
| Agent loop with limits, never throws, typed outcomes | AgentLoop with Limits and outcome enum (audit 3.2; AgentLoop.kt:8-26 from audit). Wall timer includes approval wait (AI-08) | exact, plus bugs | S | H |
| Streaming of text and tool events | ChatModelClient has only `complete` (Messages.kt:67-69) | missing | M | H |
| Stall detection and silent retry | none; no retry at all (AI-23) | missing | S | M |
| Empty-turn retry | none | missing | S | L |
| Tool input repair (aliases, empty args) | unknown name lists real names (audit 3.2). No alias repair | partial | S | L |
| Tool result guard: truncate or offload large results | tool result cut at 8000 chars (AgentLoop limits, audit 3.2). No pointer-to-full-result tool | partial | S-M | M |
| Untrusted output fencing | nonce-fenced data (Safety.kt:59-75 from audit) | exact, stricter (nonce) | - | H |
| Invisible Unicode stripping in tool output | not found in Safety.kt per audit text; unverified | probably missing | S | M |
| Approval: once, session grant, deny, args redaction | Approval.kt risk classes and ApprovalRelay; no "for this session" grant; destructive needs typed name (ApprovalCard.kt:50 from audit) | partial | S | M |
| Approvals off-screen (lock screen) | comment in Approval.kt:48 says notification path exists; ApprovalRelay is the only implementation | missing | M | H |
| Unattended allow-list with per-tool call caps | ApprovalPolicy Unattended (Approval.kt:56-80), `allowUnattended` per tool (AgentNode.kt:182) | exact, better than n8n | - | H |
| Tool kinds: node, workflow, custom code, MCP, built-in | Any offerable Step is a tool (StepTools) but ai.*, flow.*, spark.* excluded (Tools.kt:68-72 from audit); Agent Builder maps only "device" to device.notify (AgentFlowCompiler.kt:33) | partial | M | H |
| Workflow as tool with per-field binding (ai/fixed/expression) | none (flow.* excluded) | missing | M | H |
| Tool fixed params hidden from model | Secret and locked params hidden (Tools.kt:87-106 from audit) | exact | - | H |
| Tool deferral / search for big catalogs | none | missing | M | M |
| MCP client with permissions by read/write | none | missing | M | H |
| Sub-agents: foreground and background delegation | tool type "sub_agent" in the builder UI list but dropped at compile (AgentFlowCompiler.kt:48-52) | missing | M-L | M |
| Background jobs with receipt and wake | none; always-on engine is a different thing | missing | L | M |
| Plans / todo tool | none | missing | M | L |
| Window memory | WindowMemory (Memory.kt:34) in-process only | partial | S | M |
| Persistent memory store | MemoryStore seam (Memory.kt:6-10), only InMemoryStore | missing | S | H |
| Observational memory (observer, reflector) | none | missing | M | M |
| Episodic memory with embeddings | none | missing | L | M |
| Knowledge files with search tools | `AgentKnowledgeItem` text items, injected into the prompt via expression resolve (AgentPrompt.kt, audit 3.7) | partial (inline text only) | M | M |
| Skills catalog, load on demand, version pins | SkillRegistry exists, not wired (AI-03 from audit); AgentSkill is a name and a flag (CustomAgent.kt:42-47) | partial | S-M | M |
| Budget: monthly USD, session cap, alert, shared ledger | AgentLimits has token and optional cost cap (audit 3.2). No ledger, no monthly | partial | M | H |
| Guardrails (PII, jailbreak) | Redactor on outputs (Safety.kt); no guardrail types | partial | M | M |
| Prompt caching | none (AI-17) | missing | S | M |
| Reasoning effort config | AgentSettings.reasoningEffort exists (CustomAgent.kt:51) but not mapped (AgentFlowCompiler.kt:75-83 from audit) | UI only | S-M | M |
| Fallback model | none | missing | S | M |
| Credentials per agent, validated at publish | credentialId in agent; vault keys; endpoint policy | exact for storage, no publish check | S | H |
| Policy gate (org rules) | not needed single user | n/a | - | - |
| Session history with timeline, tokens, cost | `AgentSession` prompt/response/duration only (CustomAgent.kt:64-70) | partial | M | H |
| Interrupted execution sweeper | always-on has crash record of Runs (RunHost, "record of crashed Runs" comment) for Flows | partial (Flow only) | S | M |
| Evals: cases, programmatic and judge checks, fix suggestion | none for agents (audit T16 only proposes) | missing | M | H (quality) |
| Chat Hub: multi-model chat presets, tree history, regenerate, edit | Ask AI chat sessions in JSON files (AiSessionRepository); no presets, no branches, no regenerate | partial | M | M |
| Chat Hub: stream reconnect with sequence numbers | none (not streaming) | missing | M | L on phone |
| Artifacts (documents in side panel via commands) | none | missing | M | L-M |
| Title auto-generation | none verified | missing | S | L |
| Cross-device sync of chat | none (single device) | n/a | - | - |
| Agent import/export as JSON, duplicate | none verified | missing | S | M |
| Share agent between users/projects | not applicable (single user); share as file or QR | n/a | S | M |

---

## 10. Where KaivoFlow can beat n8n on Android

1. Serverless 24/7 agent. Publish compiles the agent to a hidden Flow and publishes it with the existing always-on engine. No server, no cron daemon, no leader election. The engine already handles boot restore, battery-aware wake locks, Doze reliability and the registration retry loop (core/work/alwayson). n8n needs a server and a leader.
2. Phone-native triggers for agents that n8n lacks: notification arrived, SMS, share sheet, geofence, headset, Wi-Fi, battery, boot (TriggerSpecs.kt:12-23). "Agent triages every notification" and "share a page, agent summarizes" are things no server agent can do.
3. Device tools as first-class agent tools with risk classes (the Step catalog already has them). Reads are free, anything leaving the phone needs approval or an author allow-list with a per-run cap.
4. Approval on the lock screen: a notification with Approve and Deny actions (and typed confirmation only inside the app for destructive tools). n8n needs a browser tab or a Slack card. This is what makes unattended agents with side effects acceptable.
5. Approval grants "for this session" and "for this agent for N days" stored locally, visible and revocable in one screen.
6. Honest, cheap simulation: a dry run of an agent where device Steps are simulated (no real SMS) is trivial on a phone; n8n needs model-generated fixtures.
7. Budget as a product: monthly cap across all agents, per-agent cap, and a hard stop for unattended runs, using the local ledger. Show "today's spend" on the agent card. n8n's budget is per agent and needs a database and email.
8. On-device or LAN models for classify, extract and cheap triage inside the same agent (with a "private" badge) and automatic fallback to a cloud model only if the user allows it per agent.
9. One-tap agent packs: an agent JSON has no secrets (credential is an id), so export and import as a file or QR works and gives a template library.
10. Speech in and out for the agent chat; the speak Step exists.
11. Sessions and spend visible next to the Run history of the engine: because an agent run is a Flow Run, KaivoFlow gets the Run record, Step trace, retry, and notifications for free. n8n keeps a separate execution table for agents.

---

## 11. Design for "Publish (Run 24/7)" (the missing piece)

Principle: do not invent a second scheduler. An agent is a Flow with a trigger and one Agent Step. Publishing an agent means producing that Flow, validating it, and handing it to ActivationCoordinator. The Agent Builder UI does not change; only the button handler and what is stored underneath.

### 11.1 Data
- Add `AgentDefinition` (pure Kotlin, in core/ai or a new package in core/model) with a `schemaVersion` and the same fields as CustomAgent (UI keeps using CustomAgent as a view model, mapped both ways). Keep one JSON codec with version number so old agent files load.
- Add `AgentPublication` row: agentId, hiddenFlowId, publishedRevision, state (mirror of LifecycleState), lastRunAt, lastRunStatus, monthSpend. Store in Room (core/data) so it survives the same way flows do.
- Hidden Flow: id `agent:<agentId>`, tagged so Flow lists and the canvas skip it (a tag or a flag on Flow; Flow.tags exists, Flow.kt:22). It contains N trigger Steps (one per enabled AgentTrigger) all wired into one `ai.agent` Step produced by AgentFlowCompiler.compileProduction (new function next to compile at AgentFlowCompiler.kt:35).
- Hidden Flow names the Steps from the agent and carries `settings.timezone` and `executionTimeoutMs` from AgentSettings.timeoutSeconds.

### 11.2 Compile for production (differences from Test Run)
- No `allowUnattended = false` blanket: the author's per-tool "allow unattended" flag (new field on AgentTool) decides; default false; destructive never.
- Memory: persistent store keyed per trigger run for scheduled triggers (fresh thread per run) and per sender for notification or SMS triggers; never the shared default key (AI-06).
- Limits from agent settings plus a monthly cap check before the run starts (skip and notify if over).
- The prompt for the task: for schedule triggers, a stored objective (new field `objective` on the schedule trigger) plus current date, time and timezone, as n8n does. For event triggers: the event payload as the user message wrapped as untrusted data.
- `outputField`, `_ai` meta remain; add a final Step option "notify me with the answer" (device.notify) so a scheduled agent has a visible result without extra wiring. Configurable per agent ("Deliver result: notification, none, webhook response").

### 11.3 Validate (agent PublishValidator additions)
Issues with code and path so the UI can show a checklist: missing model or credential; credential not in the vault; provider endpoint refused by EndpointPolicy; instructions empty; no enabled trigger; schedule frequency invalid; unattended trigger with a tool that is not allow-listed and not Read; tool types without implementation (blocked, not silently dropped); expressions in instructions that fail ExpressionGuard; a trigger that needs a permission (notification access, SMS) that is not granted (reuse PlatformFacts); battery optimization state (warning).

### 11.4 Publish, republish, pause, unpublish
Map one to one: publish -> ActivationCoordinator.publish(hiddenFlowId); edit while live -> badge "Changes not live" via ChangeDetector on the compiled Flow; "Publish changes" -> publishChanges; pause with until-time -> pause; unpublish -> unpublish. Keep the Draft/Live badge but drive it from LifecycleState, and show Error state with the registration failure text.

### 11.5 Run record and history
Runs of the hidden Flow are normal Run records. The agent card reads the last Run, Step output `_ai` (tokens, cost, tool trace) and shows: last run time, status, answer preview, cost today and this month. Sessions list in the builder becomes a view over the Run store filtered by flowId plus Test Run sessions.

### 11.6 What publish must refuse (explicit, never silent)
Never mark an agent Live if the compile dropped a tool, if no trigger arms, or if the hidden Flow validator reports an error. The current silent drop of tool types (AgentFlowCompiler.kt:48-52) is acceptable for Test Run with a note, not for publish.

---

## 12. Concrete build tasks in order

Each task: owner module, acceptance test. They extend audit-v2/05 T1-T17; where they overlap I name the audit task. Tasks marked (ask first) touch Antigravity UI code; everything else is underneath it.

| # | Task | Module | Acceptance |
|---|---|---|---|
| A1 | Honest labels first (audit T1): rename Publish to Save and hide the Live badge until A8 ships. (ask first) | feature/flows/ai | Grep finds no UI path that shows Live or "Run 24/7" without a backing publication row |
| A2 | `AgentDefinition` model plus versioned JSON codec and golden file tests; CustomAgent maps to and from it | core/model (model) and feature/flows/ai (mapper) | Old agent JSON files load; round trip equals; unknown fields kept; invalid enum rejected with a message |
| A3 | Agent validator producing issues {code, path, capability} for runtime and publish scopes | core/ai (pure) | Table-driven JVM tests: each issue code from 11.3 fires on a crafted agent and none fires on a valid one |
| A4 | Production compiler `AgentFlowCompiler.compileProduction`: triggers to Spark Steps, one ai.agent Step, delivery Step, hidden Flow flag | feature/flows/ai/agent (compiler) with spec in core/nodes | Compiled Flow passes FlowValidator and PublishValidator; each AgentTrigger type maps to the right spark id and params; dropped tool means compile failure, not a note |
| A5 | Persistent MemoryStore (Room) with per-key scoping, message cap, redaction on write (audit T4) | core/data (store) and core/nodes/ai (AgentNode key rule) | Two items without a key do not share history; history survives process kill; scheduled runs get a fresh key per run |
| A6 | Wall timer excludes approval wait; Stop; CancellationException rethrow (audit T3) | core/ai | Test: approval answered after maxWallMs still completes; cancel returns within 1 s with outcome Cancelled |
| A7 | Provider retry with backoff and Retry-After on 429 and 5xx; stall timeout between response bytes; silent retry once if nothing streamed (audit T5) | core/ai | Fake gateway tests: 429 then 200 succeeds; 3x 429 fails with typed RateLimit; stalled body ends in a typed error in under the configured limit |
| A8 | Agent publication: AgentPublication store, hidden Flow save, publish/republish/pause/unpublish via ActivationCoordinator, restoreAtStart covers agents, badge from LifecycleState | core/data, core/work (ActivationManager adapter), feature/flows/ai | Instrumented test: publish an hourly agent, lock phone, one Run record per hour for 3 hours; unpublish stops it; reboot restores it |
| A9 | Run delivery and agent card status: last run, answer preview, cost today and month, failure reason | feature/flows/ai and core/data | Card shows the last Run after a manual fire; failed run shows the typed outcome text |
| A10 | Notification approvals: implement ApprovalCallback via notification actions; session grants stored in Room; destructive needs in-app typing | core/work (adapter), core/ai (grant model), feature/flows/ai | Unattended agent with an allow-listed ExternalEffect tool beyond cap asks via notification; Approve resumes within 5 s; Deny returns a denial to the model; screen-off test on device |
| A11 | Budget ledger: per-call idempotent spend rows, monthly cap and per-run cap, alert once per month, stop unattended runs over cap | core/ai (CostRates exists), core/data (ledger), core/work (pre-run check) | Test: cap crossed mid-month stops the next scheduled run with a notification and a Run record "skipped: budget"; counters idempotent on replay |
| A12 | Streaming (audit T6) and event callback so Test Run and Ask AI show real tool timelines | core/ai, feature/flows/ai | First token under 1.5 s; cancel closes the connection; fake SSE tests with split chunks and tool-call deltas |
| A13 | Tool breadth in Agent Builder: real implementations for web_search (net.http locked URL, Read), workflow (flow.* as tool with per-input binding mode), code (sandboxed Step), sub_agent (delegation as a tool with depth 1) | core/ai (StepTools), core/nodes | Each builder tool type either runs or is hidden; workflow tool never exposes fixed params to the model; sub-agent cannot call itself |
| A14 | Tool result guard: store large results as a Run attachment and give the model a pointer plus a read_result tool; strip invisible Unicode in fenced data | core/ai | Test: 200 KB result yields head, tail and pointer; zero-width and tag characters removed from fenced output |
| A15 | Skills wired: SkillRegistry tools registered, load_skill on demand, skill ids kept across compaction; agent skill list maps to registry | core/ai (skills), feature/flows/ai | Agent with a skill loads it only on demand (event in trace); dropping skill from config removes it from catalog |
| A16 | Knowledge: text and file items stored in Room, search_text and read tools instead of pasting everything into the prompt; token budget warning | core/ai (tools), core/data | 50 KB knowledge item no longer inflates every call; answer cites item title; test with a scripted model |
| A17 | Agent export and import (JSON, QR) with credential ids replaced by placeholders | feature/flows/ai and core/model | Import on a second device prompts for credential choice; no secret in the file (test greps for key patterns) |
| A18 | Agent evals: cases and checks (contains, JSON valid, tool-call accuracy, LLM judge), runner with scripted tool results and simulated device Steps, results screen | core/ai (runner, pure), feature/flows/ai (UI later) | CI run with scripted model scores 30 cases; a prompt edit that lowers the pass rate fails the job; device Steps never execute for real in eval mode |
| A19 | Guardrails: PII detect and redact or block on input and output; stop reason code in the Run | core/ai | Phone number and card number patterns blocked or masked per strategy; Run shows guardrail code |
| A20 | Chat Hub-lite: a plain multi-model chat with presets (agent without tools), tree messages (retry and edit as siblings), auto title, optional per-model context trimming table | core/ai (history tree), feature/flows/ai (new screen, new view only) | Regenerate creates a sibling and history to the model follows the selected path; context trimmed when the model's window is known |
| A21 | Fallback model and prompt caching flags in agent settings and Providers | core/ai | On 5xx or auth error the fallback model is tried once and recorded in `_ai`; Anthropic request has cache_control on the system block (test body) |
| A22 | Observational and episodic memory (long term) | core/ai, core/data | Later; observation after 30k tokens, recall top-k with a local embedding or provider embedding; deletable list UI |

Order logic: A1-A4 make the product honest and give the compile path; A5-A7 make unattended runs safe; A8-A9 deliver Publish; A10-A11 make it trustworthy at scale; A12-A16 make the agent powerful; A17-A22 are moat and quality.

---

## 13. Edge cases and lessons to port (checklist)

- Publishing is idempotent: publishing the already-live version is a no-op that only updates metadata.
- Publish validates the snapshot that will go live, not the draft the UI is showing, when rolling back.
- Pre-checks that can fail for user-fixable reasons run before any write. Failures a retry can fix (network) are healed later and never block publish.
- Cron tick reads the live version at fire time, and a tick for an unpublished task deregisters itself.
- A run lock with TTL and renewal prevents overlapping scheduled runs; skip, never queue.
- Never throw from a tool whose side effect already started (n8n's background registration); a thrown error makes the model retry and duplicate the effect. KaivoFlow's AgentLoop already never throws, but StepTool failures after a side effect should be marked "may have run" in the message to the model.
- A missing dependency (deleted workflow, removed credential) keeps the tool in the list with a precise reason rather than disappearing.
- Resume payloads are validated against the tool's resume schema; double resume is rejected.
- After a crash, resume from the last step with a note that the previous attempt may have partly run.
- Cost of side calls (title, observer, reflector) must be added to the run cost.
- Spend ledger writes must be idempotent by call id and survive database failure.
- Instructions authored by the user are quoted and labelled inside platform rules; the date and timezone are always in the prompt.
- Tool names are normalized and de-clashed; a tool list is stable across a run for cache reasons.
- Keep the number of built-in tools small; add an action field to an existing tool instead.
- Preview mode runs under the user's own access and drops tools the user cannot use, so a draft cannot exceed the tester's rights. KaivoFlow single-user: the equivalent is "Test Run never uses allowUnattended and never runs a Production side effect without a card".
- Sessions: new session after idle timeout on channels; a fresh thread per scheduled run.
- Large attachments: size cap and MIME allow-list by model capability.

---

## 14. Unknowns and not verified

- Vue components were not read in detail; UX list in section 8 comes from component names and module docs. Verify real layouts by running n8n if exact screens matter.
- Sandbox and workspace implementation (the isolate pool, Daytona-style provider, file bridge) was not read. The statement "custom code tools run in isolates" is from file names and comments.
- Slack, Telegram, Teams, WhatsApp, Discord and Linear adapters were not read beyond schemas and the tool doc. Rate limits and message splitting details are from file names.
- Vector store drivers and embedding flows were not read; knowledge retrieval by search_text is from BUILT_IN_TOOLS.md.
- Whether end users of a channel can bring their own credentials (the dynamic-credentials and runtime-credentials modules) was not studied.
- Exact n8n numbers (50k token guard, 90 s stall, 120 s workflow tool timeout, 30 minute runtime cache, 100 default iterations) are read from constants but may change by version; do not hard-code them as KaivoFlow requirements without measuring on a phone.
- KaivoFlow: whether "notification approval path" (Approval.kt:48 comment) exists elsewhere is unconfirmed (audit says none).
- KaivoFlow: how Flow lists and the canvas would hide a tagged hidden Flow was not checked (FlowListScreen and the repository queries need a read before A4/A8). Whether ActivationStore (Room tables) accepts a Flow with no user-visible row was not checked.
- KaivoFlow: whether the Room schema needs a migration for AgentPublication (core/data Migrations.kt) was not checked.
- Play Store policy and OEM battery behaviour for long agent runs inside the always-on service (belongs to the engine audit).
- Gemini thought signatures, OpenAI reasoning parameters and similar provider details remain as in audit-v2/05 section 8.
- Token cost of the proposed prompt changes was not measured.
