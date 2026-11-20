# 05 - AI assistant, Agent Builder, AI Steps, connectors (audit v2)

Branch audited: claude/phase-4-base (HEAD 4fca368). Reference: n8n 2.48.6 at /tmp/ref/n8n (behaviour only, nothing copied).
Rule used: read-only. Antigravity's UI and AI code is judged fairly. Where I say "ask first", the fix touches code that works today.

---

## 1. Scope and files read

KaivoFlow, all read in full unless marked "skimmed":

| Area | Files |
|---|---|
| core/ai (23 main files, 3.0k lines) | AgentLoop, Approval, ApprovalRelay, ConversationCompactor, EndpointPolicy, Json, Memory, Messages, ModelDiscovery, OutputParser, ProviderEndpoints, Providers, Safety, SystemPrompt, Tools; flowedit/* (AiTriageClassifier, AssistantCatalog, AssistantPrompt, AssistantTurn, FlowDraft, FlowEditTools, FlowProposal, OfflineFlows, ProposalApply); skills/* |
| core/nodes/ai | agents/AgentNode, chains/{BasicLlmNode, AiChatNode, AiClassifyNode, AiExtractNode}, models/ChatModelNode, parsers/StructuredParserNode, shared/{AiClient, AiSlots, AiSpec, AiExtractionSchema}; catalog/NodeCatalog.kt:154-160 and CategoryDefs.kt:75-82 for registration |
| feature/flows/ai | AiAssistantViewModel, AiWorkflowAgent, AiProvider, AiSessionRepository, ChatClientProvider, CredentialFieldResolver, CredentialVaultBridge, ExpressionGuard, ModelDiscoveryService, AiModule, ApprovalCard (head), AiSettingsSheet (state + tabs read, composables skimmed), AiActionMenuSheet (skimmed), AiAssistantScreen (state wiring read, layout skimmed), AiThoughtBlock/CanvasPreviewView/TinkeringPill/MarkdownChatText/ChatHistorySheet (skimmed, UI only) |
| feature/flows/ai/agent | AgentBuilderViewModel, AgentFlowCompiler, AgentPrompt, CustomAgent, AgentRepository, AgentModule; AgentBuilderScreen (2023 lines, skimmed by grep) |
| Wiring | core/work/FlowEngine.kt (approvals, testStep), alwayson/RunHost.kt:55-70, MainActivity.kt:54-67, AndroidManifest.xml, WebhookServer.kt (head), core/model/WorkspaceResources.kt (credential types) |
| Tests seen | AgentLoopTest, ToolsAndSafetyTest, ApprovalRelayTest, EndpointPolicyTest, ProvidersTest, ModelDiscoveryTest, SkillRegistryTest, AiFamilyTest, AssistantFlowEditTest, AgentFlowCompilerTest, AgentPromptTest, ExpressionGuardTest, AiProviderConfigTest |

n8n, read for behaviour (pointers only):

- packages/@n8n/instance-ai: docs/architecture.md, streaming-protocol.md, tools.md, memory.md, prompt-profiles.md, sandboxing.md; src/agent/system-prompt.ts, src/runtime/*, src/workflow-loop/*, src/context-budget; skills/workflow-builder/SKILL.md; evaluations/.
- packages/@n8n/ai-workflow-builder.ee: AGENTS.md, src/agents (planner, supervisor), src/code-builder (agent loop, constants, triage), src/validation/*, evaluations/README.md.
- packages/@n8n/agents: src/runtime (loop, guardrails, memory, tools, streaming), src/sdk/untrusted-content.ts, sdk/guardrail.ts.
- packages/@n8n/nodes-langchain: nodes/agents/Agent (V3 execute, options), nodes/mcp (McpClient, McpClientTool, McpTrigger), memory, tools, chains, output_parser, vector_store lists.
- packages/@n8n/mcp-browser (README, spec/browser-mcp.md, spec/technical-spec.md, tools, redaction, sensitivity), mcp-browser-extension (manifest, relayAllowlist, approvedHosts), chat-hub (tiny shared package; real chat hub is packages/cli/src/modules/chat-hub), ai-node-sdk / ai-utilities (READMEs, layout), mcp-apps (README).

Not read: n8n frontend components of the assistant, n8n vector store implementations, Daytona sandbox provider.

---

## 2. How n8n does it

n8n has three separate AI layers. They share a lot of ideas but are different products.

### 2.1 The Instance AI assistant (packages/@n8n/instance-ai)

One orchestrator agent per chat message. A new agent object is built for every send, so MCP config, user permissions and prompt version can change between messages. The agent is "deep agent" style: the model drives the loop, the code only supplies primitives (tools, memory, planning, a task board).

Prompt design:
- The system prompt is assembled from small functions (date and time, project scope, existing-resources routing, conversation recall, license limits, read-only mode, limited mode, browser use, reply language, safety). It is one cached prefix, so per-thread values are never pasted in. Project id is a presence flag only; the project name arrives in the per-turn context block.
- Long how-to knowledge lives in "skills" (workflow-builder is about 880 lines, agent-builder 700, debugging-executions 260, intent-recognition 500). The model loads a skill on demand. The loaded skill ids are kept outside the chat text and re-rendered in a cached block, so compaction cannot drop the active rules.
- Prompt "profiles" pin a system prompt version plus skill variants. A version beats a mode. Old versions stay reproducible. A concise-style variant was measured: 30 percent less output text on a fixed eval case.
- Hard rules are written as short policies, not prose: never ask for setup values before the first build (use placeholders); reply in the user's language, from the first word; never put secrets in chat; two MCP servers exist, ask which one the user means.

Tools:
- About 70 tools, mostly one tool per domain with an "action" field (a discriminated union per action, so the model sees a tight schema).
- Core tools are always loaded. The rest sit behind a tool-search layer (search_tools then load_tool). One tool is pinned on purpose: the one that explains what to do when nothing is connected, because search would show nothing and the model would give up.
- Domains: workflows, executions (list, run, run one step, get output, debug, stop), credentials (list, search types, set up, test), nodes (search_nodes, get_node_types with resource and operation discriminators, get_node_parameter_options to resolve real ids), data tables, research (web search, fetch URL), docs, files, MCP registry, past-conversation search, save-preference, ask_user, browser tools.
- Approval cards: destructive tools suspend the run. The card text is structured (resource name, row and column previews up to a cap, a one-line "approvalSummary" the model writes in the user's language). Approval is part of the stream protocol, not a modal bolted on.
- MCP tools pass through a sanitizer (schema shapes the provider cannot take are flattened), a name check against reserved tool names (no shadowing of built-in tools), and a hash-keyed cache.

Planning and building:
- Workflows are built as TypeScript source files written into a per-thread sandbox, then one build tool compiles, validates, resolves credentials and saves. The build tool is the validator; there is no separate "validate first" step.
- Edits are string replacements on a bound source file, never a full rewrite.
- After a build, a deterministic state machine takes over: build, verify, then one of verified / needs patch / needs rebuild / needs user input / failed. The same failure signature twice ends the loop.
- Verify runs the workflow with temporary pin data. Nodes that write, send, delete, wait or use mocked credentials are simulated with model-generated fixtures. A post-run check replays parameter resolution and reports expressions that resolved to empty. An empty-output check names the first node that returned only empty items.
- A "completion obligation" stops the agent from saying "done" until verification evidence exists. "Not verifiable" is a visible warning state, never silently "verified".
- A task list tool lets the orchestrator show progress, cancel, or send a correction to a background task mid-flight. Concurrency per thread is capped (5).

Streaming:
- POST /chat returns only a run id. All events come over SSE per thread: run-start, text-delta, reasoning-delta, tool-input-start, tool-call, tool-result, tool-error, agent-spawned, confirmation-request, tasks-update, status, error, run-finish and more.
- Every event has runId and agentId, so the UI renders a tree (orchestrator and child agents).
- Durable step-level events are stored with a per-thread increasing id; token deltas are live only. Reconnect uses Last-Event-ID and replays. One active run per thread (409 on a second send). Cancel is idempotent. Idle and max-lifetime sweeps exist for stuck runs and stale confirmations (one day).

Memory:
- Per-thread only. Two background agents compress history: an Observer when raw messages pass about 30k tokens, a Reflector when observations pass about 40k. The context is "observation block first, recent raw block second", which is append-only and cache friendly.
- Threads expire after 30 days by default. A separate tool can search past conversations. Working memory is switched off for the assistant.

Safety:
- Every domain call goes through the same permission scopes as the human user. Tool output never contains decrypted secrets. Credential entry happens in the normal UI, never in chat.
- External URL fetches pass a per-domain approval gate and an SSRF filter (private ranges, loopback, bad schemes, and a second check after redirects). Fetched text is treated as untrusted.
- A "limited mode" and a "read-only mode" are injected into the prompt and enforced by tool errors.

Evals:
- evaluations/ has a harness with LLM-judge, pairwise, programmatic and binary checks, a LangSmith backend, A/B bench of prompt profiles, latency and routing suites. Prompt profiles can be pinned per eval case. A context-budget tool prints how many tokens the static prompt, skill catalog and each tool schema cost.

### 2.2 The older AI Workflow Builder (packages/@n8n/ai-workflow-builder.ee)

Two generations live side by side.
- Graph generation: a supervisor routes between responder, discovery, builder and assistant. A planner agent produces a structured plan (summary, trigger, ordered steps, suggested node types) and waits for the user to approve it (interrupt and resume). The builder uses small tools: add_node, connect_nodes, remove_node, rename_node, update_node_parameters, get_node_parameter, get_execution_schema, get_expression_data_mapping, validate_structure, validate_configuration. Conversation history is compacted by a dedicated chain.
- Code builder: the model edits one workflow source file with the text-editor tool plus a batch replace tool, then calls validate. Limits: 50 loop iterations, 10 validate attempts. The error message to the model says "fix only what the last request touched, call validate once after all fixes". A triage agent decides between "answer a question" and "build".
- Programmatic validation (src/validation/checks): connections, nodes, trigger present, agent prompt contains an expression (a static agent prompt usually means the chat input was forgotten), tool wiring, fromAI placeholders, credentials, webhook response mode, parameters. Auto-fix exists for connection mistakes. Prompts are built with a small PromptBuilder (sections, conditional sections, examples) and workflow examples are rendered as Mermaid, which uses fewer tokens than JSON.

### 2.3 Agents as nodes (packages/@n8n/nodes-langchain and packages/@n8n/agents)

- The Agent node (V3) runs per item in batches (batch size, delay between batches). Options: system message, max iterations (default 10), return intermediate steps, pass binary images and PDFs to the model, streaming on by default, tracing metadata. A fallback model can be attached.
- V3 hands tool calls back to the workflow engine as a request, so each tool is a real node execution (visible in the run log). When the agent runs as a sub-agent it resolves tools inline instead.
- Sub-nodes connect by typed ports: language model, memory, tool, output parser. About 25 chat model nodes, 10 memory nodes (buffer window, Postgres, Redis, Mongo, Zep, and others), 10 tool nodes (calculator, code, HTTP, workflow-as-tool, think, search APIs, vector store), chains (basic LLM, retrieval QA, summarization, information extractor, sentiment, text classifier), parsers (structured, auto-fixing, item list), 20+ vector stores.
- MCP: a client node, a client-tool node (auth: header, bearer, OAuth2; include or exclude tool lists; timeout), a registry tool, and an MCP Trigger node that makes one workflow a server (SSE and streamable HTTP, session layer, queued execution for scaled mode). Instance-level MCP is separate.
- @n8n/agents runtime adds guardrails (PII, jailbreak style types with strategies), a budget guardrail, an untrusted-tool-output wrapper with an invisible-unicode stripper, stream stall detection, deferred tools, delegate sub-agents, a todo tool, observational and episodic memory.

### 2.4 Browser use (packages/@n8n/mcp-browser plus mcp-browser-extension)

- Three parts: an MCP server (tools), a local CDP relay (WebSocket, 127.0.0.1, random port), and a Chrome MV3 extension ("Browser Use / AI Browser Bridge") that runs CDP through chrome.debugger in the user's real profile.
- Playwright connects to the relay as if it were Chrome. Tabs are listed first and attached lazily on first use. The relay answers some CDP calls itself and forwards the rest.
- About 30 tools: connect/disconnect; tab open/list/focus/close; navigate/back/forward/reload; click, type, select, drag, hover, press, scroll, upload, dialog; snapshot (accessibility tree with element refs), screenshot, content as markdown, evaluate JS, console, pdf, network; wait; cookies and storage; capture-secret and create-credential. Every action returns a fresh snapshot so one call is one round trip. Refs go stale after navigation; selectors are the fallback.
- Safety: the extension only connects to an allow-listed relay (localhost or the cloud host), ws only on loopback; the user approves a host once and can remember it; secrets in page output are redacted (gitleaks-style prefix patterns plus DOM heuristics: password inputs, "reveal" buttons, high-entropy cells) and captured values go to a buffer, not into the chat; an HTTP transport requires a bearer token and binds to loopback.
- In Instance AI the browser tools are direct tools and a skill ("credential setup with browser use") says: stay on expected provider domains, do not follow unexpected URLs. Tool calls go through the domain gate.

### 2.5 Things worth stealing, in short

1. Verify by running with simulated side effects, not only by static checks.
2. A completion gate: "built" is not "done" until evidence exists.
3. Tool search plus pinned core tools to keep context small.
4. Structured approval copy that names the thing and the effect.
5. One event protocol with replay, so the UI is a pure reducer.
6. Skills loaded on demand and kept across compaction.
7. Redacting secrets from anything the model reads from a page or a tool.

---

## 3. How KaivoFlow does it today (verified)

### 3.1 Architecture in one picture

- core/ai is pure Kotlin (no Android): provider adapters, AgentLoop, ApprovalPolicy and ApprovalRelay, memory, redaction, untrusted-data fencing, endpoint policy, output parser, and the Ask AI "flowedit" package. It is clean, tested, and consistent. It does not look like first-draft code.
- Three consumers use the same AgentLoop:
  1. Ask AI chat (AssistantTurn, in an Android ViewModel).
  2. The `ai.agent`, `ai.basic_chain` Steps (core/nodes/ai), which run on the canvas, on schedules, and on the always-on engine.
  3. Agent Builder Test Run, which compiles the agent to a one-Step Flow and calls `FlowEngine.testStep` (AgentFlowCompiler.kt:36-87, AgentBuilderViewModel.kt:485-492).

### 3.2 Core loop (core/ai/AgentLoop.kt)

- Limits object: iterations 10, tool calls 25, tokens 200k, optional cost cap, wall 180 s, per tool 30 s, output 1024 tokens, tool result 8000 chars, context 120k chars, 4 consecutive tool errors (AgentLoop.kt:8-26).
- Never throws for provider or tool trouble. Returns AgentResult with outcome enum (Completed, IterationLimit, ToolCallLimit, TokenLimit, CostLimit, Timeout, ModelFailed, OutputInvalid, TooManyToolErrors). Only CancellationException escapes (AgentLoop.kt:119-127, 189-193).
- Tool call path (AgentLoop.kt:162-203): bad JSON arguments go back to the model as text; unknown tool name lists the real names; arguments are checked against the tool's JSON Schema (SchemaCheck, OutputParser.kt:7-72); policy decides; the call runs under a per-tool timeout; the result is redacted, cut, and fenced as untrusted data with a per-run nonce (AgentLoop.kt:145, Safety.kt:59-75).
- Old tool results are replaced by a stub when history passes the char cap (AgentLoop.kt:231-241). Only the final answer is remembered, not tool traffic (Memory.kt WindowMemory).
- Structured output: StructuredOutputParser extracts JSON from bare text, fences, or the first balanced object, validates against a schema subset, and asks the model to repair up to 2 times (OutputParser.kt:97-170). This is the equivalent of n8n's auto-fixing parser. The older doc 01 says it is missing; that is wrong.

### 3.3 Providers (core/ai/Providers.kt)

- Four wire formats: OpenAI-compatible (also used for Groq, DeepSeek, OpenRouter, Ollama /v1), Anthropic Messages, Gemini generateContent (Providers.kt:112, 165, 228). Keys go in headers, never URLs. Redirects are not followed (Providers.kt:68). Errors are redacted and typed (Auth, RateLimit, Server, Network, Policy...).
- EndpointPolicy (EndpointPolicy.kt:22-64): https only for public hosts; loopback and private LAN need an explicit allow; link-local and metadata ranges always blocked; inet_aton style odd IPv4 forms parsed; IPv4-mapped IPv6 handled; names matched exactly. DNS rebinding is only covered if the host passes a resolver (none is passed in the app: AiModule.kt:200 and AiSlots.kt:298-301 build policies without one).
- ModelDiscovery lists models with the same guard (ModelDiscovery.kt:250-297).
- No retries or backoff anywhere (grep for retry/backoff in core/ai: none). No streaming (the only client method is `complete`, Messages.kt interface ChatModelClient). No prompt caching fields.

### 3.4 Permissions and approvals

- Five risk classes: Read, WriteDraft, WritePersist, ExternalEffect, Destructive (Approval.kt:6-20).
- Interactive host: Read and WriteDraft run; others need the approval callback, else denied. Unattended host (always-on or no screen): only Read, plus ExternalEffect tools the Flow author listed in allowUnattended, each capped (default 5 calls per tool per run). Everything else is not even offered to the model (Approval.kt:71-95). Destructive asks the person to type the tool name (ApprovalCard.kt:50).
- ApprovalRelay (ApprovalRelay.kt): one open question at a time, queue by mutex, denied at once when no screen is attached, and the open question is cancelled when the last screen leaves. Global dialog host in MainActivity.kt:67; inline cards in Ask AI and Agent Builder.
- AgentNode picks the host: interactive only when a relay exists and mode is not Production (AgentNode.kt:143). RunHost runs the always-on engine in Production (RunHost.kt:67), so unattended agents get the strict path. This is a good design and better than n8n for a phone: a locked phone cannot be talked into sending an SMS.
- StepTools.riskOf (Tools.kt:74-84): data transforms and battery/network status are Read; http/rss are Read only when the URL is locked by the author and the method is GET; everything unknown is ExternalEffect; file write/delete are Destructive. Model-chosen text has "{{" neutralised so item data cannot become an expression (Tools.kt:109-118). Secret params and author-locked params are never in the model-visible schema (Tools.kt:87-106). Steps in ai.*, flow.* and spark.* are not offerable (Tools.kt:68-72).

### 3.5 AI Steps (core/nodes/ai)

| Step id | Status | What it does |
|---|---|---|
| ai.agent | Stable | Full agent per item; chatModel, memory, tools, outputParser are Json params, not canvas sub-nodes (AgentNode.kt:63-92). Output goes to a field plus an `_ai` meta object (tokens, iterations, tool trace, cost) (AiSlots.kt:324-335). |
| ai.basic_chain | Stable | Same loop, one step, no tools (BasicLlmNode.kt:231-241). |
| ai.chat_model | Stable, role AiModel | Only checks setup and reports readiness. It cannot be attached to an agent; the agent reads its own chatModel param (AgentNode.kt:114). |
| ai.parser.structured | Stable, role AiParser | Model-free schema check. |
| ai.chat, ai.extract, ai.classify | Legacy (CategoryDefs.kt:75-77), still registered (NodeCatalog.kt:154-156) | Run through the old AiClient (see issues AI-05). |

- Per-item execution: `ctx.perItem`. Wall clock default 120 s, tokens 50k, cost cap needs prices entered (AgentNode.kt:119-131).
- Credentials: slot names a vault id; `credential` is a Secret param; keys inside the chatModel JSON are rejected both in validate and at run time (AgentNode.kt:101, AiSlots.kt:278-280). baseUrl never takes an expression in the new slot (AiSlots.kt:255).

### 3.6 Ask AI assistant

- Turn: AssistantTurn.run (AssistantTurn.kt:47-115). Regex triage (AiTriageClassifier) picks Greeting (answered locally, no model call), Knowledge, or Mutation. A private FlowDraft copy of the open Flow is edited by 10 tools: get_flow_overview, list_steps, check_flow, add_step, connect_steps, disconnect_steps, remove_step, rename_step, update_step_params, rename_flow (FlowEditTools.kt:26). No tool can save, delete a Flow, or touch credentials.
- The prompt is generated from the real catalog (AssistantPrompt.kt, AssistantCatalog.stepList) so it cannot name a Step the app cannot run. Offered = Stable or Beta main Steps only (AssistantCatalog.kt:27).
- Safety on edits: secret params are not writable (FlowDraft.update, FlowDraft.kt:99-110); unknown keys refused; caps of 100 Steps, 300 Streams, 20 KB per param (FlowProposal.kt:11-17); one trigger only; type aliases mapped.
- Validation: the app's own FlowValidator through catalog.validate, plus ExpressionGuard syntax check on AI-written params (ExpressionGuard.kt, called via paramCheck in AiWorkflowAgent.kt:196-198), plus FlowProposalValidator at Apply time again (ProposalApply.kt:6-43).
- Self-heal: if the draft changed and has errors, up to 2 more loop runs with the error list as feedback (AssistantTurn.kt:89-100).
- Apply is explicit and safe: base `updatedAt` must still match or the proposal is refused as a conflict; only name, steps and streams are replaced (settings, variables, groups, notes, armed state stay); a warning shows if the Flow is armed; Undo restores the exact previous Flow and refuses if the Flow changed since (ProposalApply.kt:45-75, AiAssistantViewModel.kt:649-707). This is better than n8n, whose assistant saves directly.
- Chat storage: JSON files per session in filesDir/ai_sessions (AiSessionRepository.kt). Provider config pointer in the vault row `ai_provider_config`; keys live only in named credentials, AES-GCM with a Keystore key (CryptoBox.kt:15-28, AiProvider.kt:129-136).
- Offline helper OfflineFlows.kt is defined, but nothing outside tests calls it (grep: no non-test use).

### 3.7 Agent Builder

- CustomAgent model (CustomAgent.kt:232-252): name, icon, provider, model, credential, instructions, skills, triggers, tools, knowledge, webSearchEnabled, crossSessionMemoryEnabled, settings, sessions, isPublished. Stored as JSON files in filesDir/agents (AgentRepository.kt).
- Instructions and knowledge may contain expressions; they are resolved per call in the agent's global scope, and a bad expression stops the test with a message (AgentPrompt.kt:103-137).
- Test Run compiles to a one-Step Flow and runs only that Step in TestStep mode with the person's text as input (AgentFlowCompiler.kt:12-16, 36-87). Only tool type "device" maps to a real Step (device.notify) (AgentFlowCompiler.kt:33). Other types are dropped and listed in the result note.
- Fields that reach the runtime: provider, model, credential, instructions, knowledge, device tool, maxIterations, timeoutSeconds. Fields that do not: skills, triggers, webSearchEnabled, crossSessionMemoryEnabled, reasoningEffort, memoryDepth, toolCallConcurrency, isPublished (grep: AgentFlowCompiler.kt:75-83 is the only mapping).

### 3.8 Connectors and browser use

- There is no MCP client, no MCP server, no browser tool, no WebView, no accessibility service, no custom tab usage anywhere in the code (grep for WebView, CustomTabs, AccessibilityService: only a help string in AiSettingsSheet.kt:1112).
- The "Connectors" tab is a mock: three `remember` variables (AiSettingsSheet.kt:108-110), Save & Connect just sets a boolean (AiSettingsSheet.kt:303-307), and the card then says "Connected (DevTools / MCP)" (AiSettingsSheet.kt:705). Nothing reads it.
- A loopback/LAN webhook server exists (core/work/WebhookServer.kt, plain ServerSocket, per-Flow tokens, loopback unless LAN switch on). It is the natural base for a phone-side MCP server later.

---

## 4. Issues found

Severity: P0 = silent failure of a promised feature or data/security loss; P1 = misleading or unsafe, fix soon; P2 = quality, cost, cleanup.
"Ask first" marks fixes that change Antigravity code that works.

| ID | Sev | Where | What | Why it matters | Fix idea |
|---|---|---|---|---|---|
| AI-01 | P0 | AgentsDashboardView.kt:367; FlowListScreen.kt:179-181; AgentBuilderViewModel.kt:546-558 | "Publish (Run 24/7)" and the "Live" badge only flip `isPublished` in a JSON file. Nothing outside the UI reads it (grep isPublished: only UI and the repository). Triggers on the agent (schedule, webhook, phone_event) are labels. | A user publishes "API Health Monitor, hourly" and believes it runs. It never runs. Silent missed alerts. Worst trust risk in the AI area, and the product sells 24/7. | Short term (S): rename to "Save", badge "Draft", hide Live. Real fix (M-L): compile a published agent with AgentFlowCompiler plus a Spark from its trigger and publish through ActivationCoordinator so the always-on engine runs it. |
| AI-02 | P1 | AiWorkflowAgent.kt:151-180, 193-199, 209-226; AiAssistantViewModel.kt:555 | The "live" stream is staged. Steps "Read instance context", "Opening skill" (fixed hash 299f72cdc63f at :168, skill id "agent-builder" at :162 does not exist in BuiltInSkills) and "Resolving LLM" are hard-coded. The real work is one blocking call (`turn.run`, :199). Edit steps are replayed after it finishes. TextChunk is declared (:88) and handled (VM :555) but never emitted. | The user sees invented activity and waits up to 120 s with no text. For a "premium" assistant this is the first thing a reviewer notices. Also dishonest. Ask first. | Remove the three fake steps. Add an `onEvent` callback to AgentLoop (iteration start, tool start, tool end) and emit those. Later: provider streaming (see section 7, T6). |
| AI-03 | P1 | AiSettingsSheet.kt:100-139, 236-269, 298-308; AssistantTurn.kt:41,76 | Skills, Connectors and Preferences tabs (directives, temperature, max reasoning steps, auto-fix) are `remember` state. They reset every time the sheet opens, are not persisted, and do not reach the model (temperature fixed 0.2 at AssistantTurn.kt:76; limits fixed at :41). SkillRegistry tools (list_skills, load_skill) are never added to any ToolRegistry (grep). | Controls that do nothing. A user who adds a "directive" gets no effect. Ask first. | Persist to a small AiPrefs store (DataStore). Feed temperature, max steps and directives into AssistantTurn. Register SkillRegistry tools or delete the tab. Hide Connectors until AI-20 work exists. |
| AI-04 | P1 | AiAssistantScreen.kt:258-263 | "Attach files" attaches the fixed string "Attached workflow specifications, error traces, and execution context." It attaches nothing real. | The prompt gets a lie; the model may answer as if it saw logs. | Attach real things: the last Run record of the open Flow, the selected Step JSON, a pasted file (read through SAF). Or remove the entry. |
| AI-05 | P1 | AiClient.kt:42, 62-63, 114; AiSpec.kt:41; AiClassifyNode.kt:59-60 | Legacy ai.chat, ai.extract, ai.classify (Legacy lifecycle but still registered and still run inside saved Flows) use the old AiClient: Gemini key is placed in the URL query (:114); key can come from an `apiKey` param (:42); baseUrl is a free URL, expression-capable, no EndpointPolicy, cleartext allowed, `contains("localhost")` check is a substring (:63) so `localhost.evil.com` passes the "no key needed" branch. No untrusted-data fence, so text from an item can steer ai.classify (substring match picks the branch). | Key in URL leaks to logs and proxies. Item data can choose where the key goes. Classifier is injectable. | Re-route the three Steps through AiSlots.client plus AgentLoop (they are small) and keep ids and params so saved Flows still load. Treat label routing with an exact-match enum schema. Ask first (they are Legacy; the plan already says replace). |
| AI-06 | P1 | AgentNode.kt:59, 159 | Default memory key is `flowId:stepId`, the same for every item and every sender in a Flow. With memory enabled and no key, item B sees item A's conversation. The store is an InMemoryStore (:59), so memory also vanishes when the process dies, which is common on the always-on engine. | Cross-user leak in a Flow that serves several chats (a Telegram or webhook bot). Memory is also unreliable. | Default key to a per-item hash or require a key when memory is set (validate error). Add a Room-backed MemoryStore (the interface is ready: Memory.kt:7-12). |
| AI-07 | P1 | Providers.kt:133-134; AiProvider.kt:31 | OpenAI adapter always sends `max_tokens` and `temperature`. Current OpenAI reasoning models (the list itself offers o3-mini) reject `max_tokens` (they want `max_completion_tokens`) and reject non-default temperature. Needs a live API test, but this is well known behaviour. | A user picks a reasoning model and every call fails with a 400 that the UI shows as a provider error. | For api.openai.com: send max_completion_tokens; drop temperature for reasoning model ids (prefix list kept in one place). Add a test with a fake gateway. |
| AI-08 | P1 | AgentLoop.kt:93, 180; AgentNode.kt:125; AssistantTurn.kt:41 | The wall-clock timer (`withTimeoutOrNull(maxWallMs)`) wraps the whole run, including the time a person takes to answer an approval card. Default is 120 s for Agent and Assistant. | A person who takes 2 minutes to approve an SMS gets "ran past its time limit" and the tool never runs. | Pause the wall timer while `policy.decide` awaits the callback (measure approval wait and extend the deadline). |
| AI-09 | P2 | AgentLoop.kt (no use of `FinishReason`); AgentLoop.kt:16; AiSlots.kt:261 | A reply cut by max tokens (`FinishReason.Length`) is accepted as a final answer. Default answer length is 1024 tokens for agents. | Long summaries end mid-sentence without a warning. With a parser it is repaired or fails, without it it passes. | If finish is Length and there are no tool calls: ask once to continue, or end with a clear "cut" warning in `_ai`. Raise Agent default to 2048-4096. |
| AI-10 | P2 | AiWorkflowAgent.kt:193; ConversationCompactor.kt:22; Memory.kt:56 | History is cut to the last 8 messages before ConversationCompactor runs; the compactor only acts above 10, so it never acts. If it did, its summary is an assistant message placed first, and `WindowMemory.load` drops everything before the first user message (`dropWhile`), so the summary would vanish. The summary is also only the first line of each old message. | The assistant forgets beyond 8 messages and nothing says so. Dead code that looks like a feature. | Replace with a real summary (one small model call) stored as a user-role "context" message, or delete the class. |
| AI-11 | P2 | AiWorkflowAgent.kt:193-195 | All chat bubbles are fed back as history, including error cards ("AI Provider Error", troubleshooting text) and the long NVIDIA help text. | Wastes tokens, confuses the model. | Mark bubbles with a kind (normal, error, system) and filter. |
| AI-12 | P2 | AiSessionRepository.kt:73-81, 135-155 | Save writes a temp file, deletes the real file, then `renameTo` (result ignored); the `finally` block deletes the temp file. If rename fails, the chat is gone. Proposals, thought traces and diagnosis are not serialised, so a reopened chat has no Apply card. No cap on number or size of sessions. Chat text is stored in plain files and is not redacted. | Rare chat loss; reopened chats lose their main value; growth over months; pasted secrets stay on disk. | Rename over the target (POSIX replaces), check the result. Store the proposal Flow JSON (or id). Redact on save. Keep 50 newest sessions. |
| AI-13 | P2 | AiAssistantViewModel.kt:504, 597; AiAssistantScreen.kt (no stop control) | No Stop button; the turn job is not kept, so a running turn cannot be cancelled. `catch (e: Exception)` also catches CancellationException. | A 2-minute call cannot be stopped. | Keep the Job, add Stop (cancel and mark the bubble), rethrow CancellationException. |
| AI-14 | P2 | AiProvider.kt:24-25, 37-38, 46; AiAssistantViewModel.kt:227-230; ModelDiscovery.kt:58 | Stale defaults (claude-3-5-sonnet-20241022, gemini-2.0-flash, gpt-4o-mini). Ollama default `http://10.0.2.2:11434` is the emulator's alias for the host and does nothing on a real phone. Auto-pick after discovery prefers names with bolt/instruct/chat, else the first in an alphabetical list, which for Anthropic is the oldest model. | New users start on old or wrong models. | Pick newest by provider rule; ship defaults in a small remotely updatable JSON; default Ollama to `http://127.0.0.1:11434` and a LAN hint. |
| AI-15 | P2 | ProviderEndpoints.kt:228; AiProvider.kt:7 | Only the Ollama provider may reach loopback or LAN. The comment says OpenAI-compatible covers vLLM and LM Studio, but a LAN vLLM through the OpenAI option is refused. The Step has a `localAccess` switch (AiSlots.kt:257); the assistant and Agent Builder have none. | Self-hosters get a policy error with no way out. | Add "Allow local server" to the credential and pass it to the policy. |
| AI-16 | P2 | AgentNode.kt:148, 174, 183 | `rateLimit` is one variable, last tool wins and it applies to all tools. Duplicate tool names make the ToolRegistry constructor throw an IllegalArgumentException that is not a StepExecutionException. | Wrong limits; raw crash text in the run log. | Per-tool limit map; check duplicates and raise a normal Step error. |
| AI-17 | P2 | AssistantPrompt.kt:29; FlowEditTools.kt:54; AssistantTurn.kt:41, 97; Providers.kt:193-200 | The full Step list is in the system prompt and the `list_steps` tool returns it again. Prompt tokens are summed over every call against an 80k cap; self-heal restarts counters. No cache_control on the Anthropic system block. | About 5k tokens repeated each of up to 8 calls, so about 40k prompt tokens for one edit, and paid twice when list_steps is used. Cap can trip on a normal build. | Remove list_steps or turn it into search (see T7); add cache_control to the system block; count self-heal in the same budget. |
| AI-18 | P2 | Safety.kt:49 | Redactor pattern 4 masks any `key`, `token`, `authorization` followed by a 6+ character value, anywhere in tool output, so JSON like `"key":"PROJ-123"` (Jira, many APIs) is changed before the model sees it. Provider key shapes `nvapi-`, `gsk_`, `xai-`, `hf_` are not in the list (the exact configured key is always masked, so this only matters for keys that appear in data). | Silent corruption of tool results. | Limit pattern 4 to header-like names and obvious secrets; keep the exact-secret list. Add provider prefixes. |
| AI-19 | P2 | AiAssistantViewModel.kt:280; AgentBuilderViewModel.kt:206; AiWorkflowAgent.kt:103, 250; AgentPrompt.kt:139; CustomAgent.kt:179 | Dead or duplicated code: `saveProviderConfig` is never called (and would save type `openai`, which the picker filter at :92-94 would hide); the same `aiTypes` filter is copied in two view models; `diagnose` and `executeTurn` have no callers; `AgentPrompt.build` is used only by tests; OfflineFlows has no non-test caller; `AgentTool` clashes by name with core.ai.AgentTool. | More surface to break, confusing for new contributors and for subagents. | Delete or wire each, one commit. Move the credential filter into CredentialVaultBridge. Rename the Builder class to AgentToolConfig. |
| AI-20 | P2 | AgentFlowCompiler.kt:33; AgentRepository.kt:99-125; AgentBuilderViewModel.kt:60-110 | Agent Builder lists web_search, workflow, code, sub_agent tools and templates ("Morning News Brief" uses web_search, and it is also the seeded starter agent). Only device.notify runs. The others are dropped with a small note in the test result. | The first agent a new user opens promises something it cannot do. | Mark tools "coming soon" or hide; ship a real web_search tool (net.http to a search API, Read risk, URL locked by the app). |
| AI-21 | P2 | AgentRepository.kt:52, 91; AgentBuilderViewModel.kt:517 | Agent JSON is written with plain `writeText` (not atomic), no lock, and test sessions are appended to the agent file without a cap. | Torn file on process death; file grows with every test. | Atomic write helper shared with sessions; cap sessions at 20. |
| AI-22 | P2 | AiTriageClassifier.kt:23-34, 51-63 | Intent is picked by keyword lists, English only. "What does the Set step do?" contains "set" so it is treated as an edit request. Non-English prompts default to edit. | Wrong mode of the prompt, less helpful answers. Edits are only proposals, so it is safe. | Keep only the greeting shortcut; tell the model in the prompt to answer questions without editing. |
| AI-23 | P2 | Providers.kt (all three clients) | No retry on 429 or 5xx. A single rate limit fails an unattended run. | Flaky overnight agents. | Two retries with jitter, honour Retry-After, only for idempotent model calls (not tool calls). |
| AI-24 | P2 | AgentNode.kt:63-92 and canvas | chatModel, memory, tools and parser are four Json text params. The canvas has no AI sub-node slots (grep: no `ai.agent` or chatModel in feature/canvas). The separate `ai.chat_model` Step cannot be attached. | n8n's most visible AI UX (plug a model into an agent) is missing; JSON editing is hard on a phone. | Inspector forms for the four slots (model picker from the credential, tool picker from the Step catalog with lock toggles). See gap table. |

Counts: P0 = 1, P1 = 7, P2 = 16.

### 4.1 What is good and should not be rewritten

- AgentLoop, ApprovalPolicy, ApprovalRelay, EndpointPolicy, Redactor, UntrustedData fencing, StructuredOutputParser, StepTools.riskOf and neutralize. They are small, tested, and in several places better than n8n (nonce-fenced data, unattended allow-list with rate limits, typed confirmation for destructive tools, SSRF policy with odd IPv4 forms).
- The Draft, Apply, Undo model with conflict detection and re-validation at Apply time.
- Catalog-generated prompt (cannot drift from the app).
- Vault-only keys with a Keystore AES key, key never in params, never in URL on the new path.
- Test Run through the real engine path.

### 4.2 Older docs checked against code

| Claim in docs/01, 08 | Verdict |
|---|---|
| "AgentNode is a placeholder" | Wrong. Full per-item agent with tools, memory, parser, limits (AgentNode.kt, AiFamilyTest). |
| "Autofixing output parser missing" | Wrong. Repair loop exists (OutputParser.kt:143-170). |
| "Tool execution only Flow edit tools" | Wrong. Any offerable Step can be a tool (Tools.kt:125-170). |
| "AiSession in SQLite" | Wrong. JSON files (AiSessionRepository.kt:50-52). |
| "No unattended safety" (implied) | Wrong. Strong unattended policy exists. |
| "Sub-nodes not wired on canvas" | Correct (issue AI-24). |
| "Fallback model missing" | Correct. |
| "MCP server on port 8088" (doc 08) | Not present in code. |
| "Browser automation card exists" | A card exists but is a mock (AI-03). |

---

## 5. Gap vs n8n

Status: exact, partial, missing. Effort S under 2 days, M under 2 weeks, L more. Value: H (sells the product), M, L.

| n8n capability | KaivoFlow status | Effort | Value |
|---|---|---|---|
| Assistant that can act on the whole instance (workflows, runs, credentials, data) | Partial. Only edits the open Flow draft, 10 tools | M | H |
| Run and debug tools (run flow, run one step, read node output, read error) | Missing in assistant. Engine has testStep and run records | M | H |
| Real streaming (text and tool events) with replay | Missing. Fake staged events (AI-02). No provider streaming | M | H |
| Build, verify, debug loop with simulated side effects | Missing. Static validate plus 2 self-heal passes | M-L | H |
| Completion gate (not "done" without evidence) | Missing | S | M |
| Static validation of generated graphs | Partial. FlowValidator, ExpressionGuard, proposal limits. n8n has 9 domain checks (trigger, agent prompt has expression, tool wiring, credentials, webhook reply) | S-M | M |
| Planner with user-approved plan | Missing | S | M |
| Triage and routing | Partial (regex, AI-22) | S | L |
| Progressive node discovery (search then fetch schema) | Missing. Whole catalog in prompt | M | H (cost) |
| Resolve real ids for list-backed params (sheets, channels) | Missing | M | M |
| Credential setup inside the chat | Missing. Person picks in a sheet | M | M |
| Observational memory (observer and reflector) | Missing | M | M |
| Window memory | Exact for in-process, partial for persistence (AI-06) | S | M |
| Past-conversation search tool | Missing | S | L |
| Save-preference tool and preferences store | Missing (UI mock, AI-03) | S | M |
| Skills (on-demand instruction packs) | Partial. Registry and 4 tiny skills, not wired | S | M |
| Prompt profiles, versioning, cache-friendly prefix | Missing | S | M |
| Evals (judge, pairwise, programmatic, A/B) | Missing. Unit tests only | M | H (quality gate) |
| Context budget report | Missing | S | L |
| Approval cards with structured copy | Partial. Good policy, generic text | S | M |
| Risk classes and unattended allow-list | Exact, and stricter | - | H |
| Untrusted content fencing | Exact, nonce based | - | H |
| Domain gate for fetch and SSRF filter | Partial. Model URLs only; no fetch tool yet | S | M |
| MCP client (node, tool, registry) | Missing | M | H |
| MCP server (workflow as server, instance server) | Missing | M | H |
| Browser use (real browser) | Missing. Mock card only | L | H |
| Agent node: tool loop, limits, iterations | Exact | - | H |
| Agent: fallback model | Missing | S | M |
| Agent: batching and delay across items | Missing (items run in the Step's own perItem order) | S | L |
| Agent: image and PDF input to the model | Missing | M | M |
| Agent: streaming | Missing | M | M |
| Agent: tools run as visible node executions | Partial. Tool calls are traced in `_ai.tools` and ctx.log, not separate run rows | M | M |
| Tools: calculator, code, HTTP, workflow-as-tool, think, search | Partial. StepTool covers any Step (HTTP, code, transforms); flow.* and ai.* excluded, so no sub-flow tool or agent-as-tool | M | H |
| Memory backends (Postgres, Redis...) | Not needed on a phone. Room store is the right target | M | M |
| Chains: extractor, classifier, sentiment, summarisation, QA | Partial. Legacy extract and classify; the rest missing | M | M |
| Output parsers: structured, auto-fix, item list | Exact for structured with repair; item list missing | S | L |
| Embeddings, vector stores, RAG | Missing | L | M (on-device is a differentiator) |
| Guardrails node (PII, jailbreak) | Missing | M | M |
| Typed sub-node ports on canvas | Missing (AI-24) | M-L | H |
| Chat Hub (multi-model chat, personal agents with tools and knowledge) | Partial. Agent Builder UI exists but agents do not run (AI-01) | M | H |
| Provider breadth (25 chat nodes) | Partial. 4 wire formats cover most through OpenAI-compatible | S | M |
| AI node SDK for community nodes | Missing, not needed now | - | L |
| Tracing metadata and run-level AI view | Partial. `_ai` meta per item | S | M |

---

## 6. Where KaivoFlow can beat n8n

Concrete, Android-native or serverless.

1. Phone context as tools. The device Steps already exist (battery, network, location, contacts, calendar, notification, SMS, clipboard, files, share). Offer them to the Ask AI and to agents with the existing risk classes. "Ask my phone" (what is my battery, what is on my calendar tomorrow, who called) is something a server product cannot do. Reads are Read class, anything that leaves the phone is ExternalEffect with a card.
2. Approvals on the lock screen. n8n needs the browser tab. KaivoFlow can raise an Android notification with Approve and Deny actions and a typed-name step for destructive tools. The code comment at Approval.kt says a "notification approval path" exists; I found no implementation (only ApprovalRelay implements the callback), so this is open work. It would make unattended agents safe and usable.
3. Draft, Apply, Undo with conflict check is already ahead. Add a diff view (steps added, removed, changed, params changed) and a dry run: run the proposal with pinned sample data where Device Steps are simulated. Simulation is trivial on a phone (no real SMS), so this is cheaper than n8n's LLM-generated fixtures.
4. Cost and budget as a product feature. AgentLimits has token and cost caps and the `_ai` meta has usage. Add "today's AI spend" per provider, a monthly cap that stops unattended agents, and a per-Flow budget. n8n has no such guard for self-hosters.
5. On-device and LAN models. Local Ollama on the same Wi-Fi is already supported. Add Android's on-device model option (AICore or MediaPipe LLM) for classify, extract, and short summaries with zero cost and no data leaving. Mark Steps "private (on-device)" in the UI. Strong privacy story for a sold app.
6. Notification and share-sheet triggers for agents. The app already has a NotificationListener. "Triage my notifications with an agent" and "share a page to KaivoFlow, agent summarises it" are phone-native entry points.
7. Voice. Speech in (SpeechRecognizer) and out (the speak Step exists) for the assistant, hands-free "build me a flow that..." while walking.
8. One-tap agent packs. Agent JSON is small and has no secrets (credential is an id). Share as a file or QR. A template library is cheap to build and good for retention.
9. Honest activity timeline. Once AI-02 is fixed, show a real tool timeline with timings and token cost per step. n8n's UI shows less.
10. Serverless always-on agent. The engine already runs Flows with the phone locked. A published agent (AI-01 fixed) becomes the first "AI employee that lives in your pocket" without any server. That is the headline feature.

---

## 7. Recommended ordered tasks

Small tasks, each with an acceptance test. Order is by trust first, then power. Tasks marked (ask first) change Antigravity code.

| # | Task | Accept when |
|---|---|---|
| T1 | Stop false promises (S, ask first): rename "Publish (Run 24/7)" to "Save" and hide "Live"; hide the Connectors tab and the inert Skills/Preferences controls; remove the fixed "Attach files" text. | Grep finds no UI that shows Live, Connected or Attached without a backing behaviour. Screenshot test on device. |
| T2 | Honest activity (S-M, ask first): delete fake steps in AiWorkflowAgent; add `onEvent` to AgentLoop (iteration, tool start, tool end, usage) and map to AgentStreamEvent. | A tool call appears in the timeline while it runs, with real duration; no "Opening skill" row unless a skill was loaded. Unit test with ScriptedModel checks event order. |
| T3 | Stop and wall-timer fix (S): keep the turn Job, add Stop button; pause wall timer during approval; rethrow CancellationException. | Test: approval answered after maxWallMs still completes. Stop cancels within 1 s and the bubble says "Stopped". |
| T4 | Memory safety (S): require or hash memory key per item; Room MemoryStore. | Test: two items with memory and no key do not see each other. Kill the app, rerun, history is still there. |
| T5 | Provider correctness (S): max_completion_tokens and no temperature for OpenAI reasoning ids; handle FinishReason.Length; retry 429/5xx twice with Retry-After; refresh model defaults; Ollama default to 127.0.0.1. | Fake-gateway tests for each body shape and retry; manual smoke on OpenAI o-series and Claude. |
| T6 | Provider streaming (M): add `stream` to ChatModelClient (SSE for OpenAI and Anthropic, streamGenerateContent for Gemini); AgentLoop emits TextChunk; tool-call deltas assembled. | First token shows in under 1.5 s on a good network; cancel closes the HTTP call; fake SSE tests including split chunks. |
| T7 | Cheaper assistant prompt (S-M): drop list_steps from the always-on prompt; add `search_steps(query)` and `get_step_schema(id)` tools; cache_control on the system block; one shared token budget across self-heal. | Typical "add a notification after the webhook" edit uses under 15k prompt tokens in total (log from `_ai`/usage). |
| T8 | Legacy AI Steps on the safe path (S, ask first): re-implement ai.chat, ai.extract, ai.classify over AiSlots and AgentLoop. | Old saved Flows load unchanged; key never in URL (test); classifier uses an enum schema and rejects injected labels. |
| T9 | Real agent publish (M-L): AgentFlowCompiler for published agents plus triggers to Sparks; publish through ActivationCoordinator; show last run and spend on the agent card. | Publish an hourly agent, lock the phone, see a run record each hour on the always-on engine. Unpublish stops it. |
| T10 | Persist and use AI preferences (S): DataStore for directives, temperature, max steps, skill toggles; inject into AssistantTurn; register SkillRegistry tools. | Setting temperature 0 changes the request body (test). A directive appears in the system prompt. Values survive restart. |
| T11 | Session store hardening (S): safe rename, store proposals, redact, cap 50. Same atomic helper for agents. | Kill the process during save: old file still readable. Reopened chat shows the Apply card. |
| T12 | Run and debug tools for the assistant (M): read_last_run(flow), read_step_io(step), test_step(step, sample) with Test Step rules, all Read or approval class as appropriate. | Ask "why did my last run fail": the assistant quotes the real error and proposes a fix. Test Step never runs other Steps. |
| T13 | Verify before Apply (M): dry run of the proposal with pins; Device and net Steps simulated; show result next to the diff. | A proposal with an SMS Step runs in dry run without sending; result row shows "simulated". |
| T14 | Remote MCP client Step and agent tool provider (M): see section 9, option E. | Connect to a public test MCP server over HTTP, list tools, call one from an agent; names cannot shadow built-in tools. |
| T15 | Canvas AI slots (M-L): inspector forms for chatModel, memory, tools, parser; tool picker with "lock this param" and "allow unattended" toggles. | A new user wires an agent without typing JSON. |
| T16 | Eval harness (M): 30 prompt cases for the assistant, checked by the real validator plus pass/fail on "Flow runs in dry run"; run on CI with a scripted model plus a nightly real-model job. | A prompt change that lowers pass rate fails the job. |
| T17 | Remove dead code (S): items in AI-19. | Build green; no references left. |

---

## 8. Not verified / needs device test

- Anything visual in the AI screens (layout, animation, keyboard behaviour). I read the state wiring, not the pixels.
- OpenAI reasoning models and `max_tokens` (AI-07): based on known API behaviour, not run here.
- Gemini function calling on current thinking models may require echoing thought signatures between turns; the adapter does not (Providers.kt:241-251 builds functionCall parts without them). Needs a live test on the newest Gemini models.
- Anthropic: tool results are sent as one merged user message (correct); extended thinking is not enabled so no thinking blocks are needed. Not run live.
- Real behaviour of the approval dialog when the app is backgrounded mid-run (relay denies when no presenter, ApprovalRelay.kt:57-58). Needs a device test with the screen off and the always-on engine.
- Whether the notification approval path from the Approval.kt comment exists elsewhere (searched the repo, found none).
- How the canvas inspector edits the four Json slot params of ai.agent (inspector code not read).
- Token counts of the assistant prompt (the claim of about 5k tokens is an estimate from the Step count; measure with usage numbers from a real call).
- The size of the offered Step list at runtime (depends on registry; not executed).
- Play Store policy for any Accessibility-based automation (see section 9). Check the current policy text before any design work.
- Doze and OEM battery killers versus long agent runs inside the always-on service (belongs to the engine audit).

---

## 9. Browser and connector options on Android

Why this is needed: n8n connects a real desktop browser through an extension, a relay and CDP. Android has no extensions in Chrome and no CDP into the user's Chrome app. The user also wants no server. So there are five practical routes. They can be combined.

### 9.1 Options in one table

| Option | What it gives | Feasibility | Effort | Main risks |
|---|---|---|---|---|
| A. In-app WebView automation | A browser inside KaivoFlow that the agent drives (navigate, read DOM snapshot, click, type, wait) | High. Plain Android API. `evaluateJavascript` for snapshot and actions, `WebViewClient` for navigation events. Optional CDP over the WebView's local devtools socket for the same app, but plain JS is simpler | M | Separate cookie jar: user must log in inside the app. Bot detection and CAPTCHAs. No extensions, passkeys limited. Works best in foreground (WebView is throttled in background). Prompt injection from pages. Memory use. |
| B. Accessibility Service | Control of any app including the real Chrome app, with the user's logged-in sessions | Technically high (AccessibilityNodeInfo tree, performAction, gestures). Policy is the blocker | L | Very powerful and very sensitive (reads all screen text, OTPs, banking). Google Play restricts Accessibility use; autonomous AI action is likely not allowed there, check current policy. Android 13+ "restricted settings" slows sideload users. Banking and secure screens block it. Fragile across app updates. Needs a clear consent screen. |
| C. Chrome Custom Tabs | Open a login or OAuth page in the user's own Chrome profile | High, already close to the OpenLink Step | S | No automation at all (no DOM access). Use only for OAuth, credential creation, and "open this page for me". |
| D. Desktop relay to the user's laptop or PC browser | Same as n8n: real browser, real sessions, all tools | Medium. Needs a small desktop piece the user installs. Matches the "no server" rule if the connection is phone to laptop on the user's own network or VPN | L | User must install an extension and keep the laptop on. LAN exposure and cleartext. Chrome shows a "debugging this tab" bar. Web Store review. Per-host approval and secret redaction needed (n8n does both). |
| E. Remote MCP (KaivoFlow as client) | Any MCP server: browser servers (Playwright style, Chrome DevTools style), cloud browsers, user's own tools | High. HTTP and SSE only (no stdio, no child processes on Android). AgentTool interface maps almost 1 to 1 | M | Tool poisoning and prompt injection from tool descriptions and results. Needs token storage in the vault, SSRF policy, per-server allow-list, name-shadow check. Cloud browsers cost the user money. |
| F. Phone as MCP server (extra) | Desktop agents (Claude Desktop, Cursor) call the phone's Flows and device tools | High. Reuse WebhookServer (token, loopback or LAN switch) | M | Same-LAN exposure; strong tokens; read-only by default. |

### 9.2 Recommended path

1. First, E (remote MCP client). Smallest step, biggest reach. Gives browser use immediately through any hosted or desktop MCP browser, plus every other MCP tool. It also gives the Agent Builder a real "tools" story.
2. Second, A (WebView tool, read-first). Ship as a Read-class "web page reader" (open URL, return readable text and links, no clicks) which covers research and monitoring. Then add click and type behind ExternalEffect approval, with a per-host allow-list.
3. Third, D (desktop relay) for users who need their real logged-in browser. Build the phone side as an MCP client of a tiny bridge program plus extension that the user installs. Reuse n8n's design ideas (see 9.4), written fresh.
4. B only as an optional sideload-flavour module, off by default, with a big consent screen, and only if the policy allows. Do not put it in the Play flavour.
5. C everywhere it helps credentials (OAuth login in the user's own browser).
6. F after E. It also completes the "control my phone from my laptop agent" story.

### 9.3 What the agent should see (tool set), by risk class

| Tool | Risk class | Notes |
|---|---|---|
| browser_read_page (text, links, headings, form field names) | Read, per-host allow-list | Output goes through the Redactor and a "sensitive page" check (password fields, reveal buttons, long random tokens) like n8n's sensitivity module; if sensitive, return a masked snapshot |
| browser_screenshot | Read | Size cap; never auto-send to a third party |
| browser_navigate, back, wait | Read for allow-listed hosts; ExternalEffect for new hosts | Domain gate with remember-this-host choice |
| browser_click, type, select, press, scroll | ExternalEffect, approval on first use per host and per session | Show target text and value in the card |
| browser_upload, cookies, storage, evaluate JS | Destructive-class card, off by default | Never offered when unattended |
| browser_capture_secret (into vault) | WritePersist | Value goes to the vault, never to the chat or the model |

Unattended mode follows the existing rule: only tools the author listed in allowUnattended, with rate limits.

### 9.4 Design notes borrowed as ideas (not code)

- Element refs from a snapshot, fresh snapshot returned after every action (one call, one round trip), refs invalid after navigation, selectors as fallback.
- Host allow-list for the relay, plaintext only on loopback, TLS elsewhere, remembered approvals the user can revoke.
- Redact before the model sees page text. Treat all page and tool text as untrusted (the nonce fence already exists).
- A short "stay on expected domains" rule in the prompt for credential tasks.

### 9.5 Risks to design for on Android

- Foreground service type and OEM kill: a WebView agent in the background may stop; mark such runs "needs screen on" or run only when charging on Wi-Fi.
- Data safety: browser pages may contain secrets the user did not intend to share with a model. Offer "local model only" for sensitive hosts.
- Legal and store: Play policy for accessibility, background microphone or screen capture; keep the Play flavour clean (the repo already has separate play and sideload flavours for SMS and exact alarms: app/src/play and app/src/sideload).
- Remote MCP over cleartext LAN: reuse EndpointPolicy (https for public hosts, explicit LAN allow) and add certificate pinning for the desktop bridge.
- Cost: tool-heavy browser sessions burn tokens. Show spend (section 6, item 4) and keep default max steps low.

### 9.6 What to build first for the business

The most valuable sentence for a buyer is "my agent can use my own accounts safely". That needs the desktop relay (D) or an accessibility module (B), but both are slow. The fastest honest demo is E plus the read-only WebView page reader (A): research, price watching, page change alerts, and "read this page and fill a note", all on a locked phone through the always-on engine. Ship that, then add the relay.
