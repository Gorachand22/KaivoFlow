# 09 - Antigravity AI agents: architecture, power and security review

Scope: the Ask AI assistant, Agent Builder and credential UI added to `feature/flows` by Antigravity. Read-only review of branch `claude/phase-3-base`. The directory is not a git work tree here, so the diff against 850d58d was not run; files were read directly. n8n (`/tmp/ref/n8n`, `packages/@n8n/nodes-langchain`) was studied for behaviour only. No n8n code, identifiers or text are reproduced.

Paths below are relative to `feature/flows/src/main/kotlin/com/kaivoflow/feature/flows/` (shortened to `F/`) unless they start with `core/`. Findings carry P0/P1/P2 and effort S/M/L.

## 1. Summary

The owner's impression is correct for the UI and wrong for the backend. The screens are polished. The backend is a single-shot prompt, not an agent.

1. **No agent exists.** There is no tool-calling loop, no iteration limit, no cost limit. `AiWorkflowAgent.executeTurn` makes one HTTP call per user message and regex-extracts a fenced JSON block (`F/ai/AiWorkflowAgent.kt:179-205`, `:572-619`). `WorkflowToolExecutor` (9 pure functions, `F/ai/WorkflowToolExecutor.kt`) is only called by the offline rule engine and by one validator call. The model never calls a tool.
2. **Custom Agents are configuration that does nothing.** `AgentTool`, `AgentTrigger`, `skills`, `webSearchEnabled`, `crossSessionMemoryEnabled`, `maxIterations`, `timeoutSeconds` are stored (`F/ai/agent/CustomAgent.kt`) and pasted into a prompt as text (`F/ai/agent/AgentBuilderViewModel.kt:438-455`). Nothing in `core/engine` or `core/work` reads them. The dashboard menu says "Publish (Run 24/7)" (`F/AgentsDashboardView.kt:367`) but publishing only sets a boolean.
3. **Model output overwrites the user's Flow before they approve it (P0).** `AiAssistantViewModel.sendMessage` calls `flowRepository.save(proposed)` straight after the model answers (`F/ai/AiAssistantViewModel.kt:361-364`). The proposal keeps the open Flow's id (`AiWorkflowAgent.kt:622`). So "Apply to Canvas" is cosmetic, and an LLM reply, or an injected one, replaces a real Flow with unvalidated Step types.
4. **The generated Flows are mostly broken.** The system prompt, the rule engine and the diagnoser use ids and keys that do not exist in `NodeCatalog` (`code.script` and `script` versus real `code.javascript` and `code`; `data.aggregate`; `app.sheets`; battery `condition`). Run-time `UnknownStepTypeException` or silent no-op follows.
5. **Provider logic is duplicated in two incompatible copies.** `core/nodes/.../ai/shared/AiClient.kt` (used by the `ai.*` Steps, no Claude support, no history) and `F/ai/AiWorkflowAgent.kt` (Claude, history, no JSON mode, no tool calling). Neither is a reusable provider interface.
6. **API keys are mostly handled well but leak in two places.** The vault is Keystore AES-GCM. The Gemini key goes in the URL query string in both chat and model discovery. A second copy of the key sits in the `ai_provider_config` vault entry.

## 2. Architecture today

### 2.1 Where AI logic lives

| Concern | Location | Pure and testable? |
|---|---|---|
| Provider enum and config | `F/ai/AiProvider.kt:13-125` (Android-free) | Yes, no tests |
| Four provider HTTP clients plus prompt plus response parse | `F/ai/AiWorkflowAgent.kt:207-451` | Needs only `HttpGateway`; testable, no tests |
| System prompt and flow proposal parser | `F/ai/AiWorkflowAgent.kt:453-619` | Pure, no tests |
| Offline rule engine and diagnoser | `F/ai/AiWorkflowAgent.kt:74-174`, `:622-823` | Pure, no tests |
| Tool executor (Flow edit functions) | `F/ai/WorkflowToolExecutor.kt` | Pure, no tests |
| Model discovery | `F/ai/ModelDiscoveryService.kt` | `HttpGateway` only |
| Session store | `F/ai/AiSessionRepository.kt:428-583` (JSON files, Android `Context`) | No |
| Agent store | `F/ai/agent/AgentRepository.kt` (JSON files in `filesDir/agents`) | No |
| ViewModels | `F/ai/AiAssistantViewModel.kt`, `F/ai/agent/AgentBuilderViewModel.kt` | Hilt, no tests |
| Screens | `AiAssistantScreen.kt` 1288 lines, `AgentBuilderScreen.kt` 2000 lines, `AgentsDashboardView.kt` 752 lines | UI |

`feature/flows` has no test source set at all (find returns nothing). All AI logic is in the UI module. Nothing in `core` knows an agent exists.

### 2.2 Execution paths

- **Chat turn**: `sendMessage` (`AiAssistantViewModel.kt:336`) calls `executeTurn` with only `chatHistory.takeLast(6)` in every provider call (`AiWorkflowAgent.kt:218,260,310,360`), then the response is parsed to text plus an optional full-Flow JSON, saved, and the session is rewritten to disk.
- **Tool call**: does not exist for the model. `parseResponseToMessage` fabricates `ToolExecution` display records by diffing the old and proposed Flow (`AiWorkflowAgent.kt:540-561`). They are labels, not executions.
- **Workflow creation**: the model returns the entire Flow as JSON. `extractFlowProposal` builds `StepNode`s with no check that `type` is registered, no param validation, no port handling (`fromPort` and `toPort` are dropped, `:616`), and no cap on step count. It also accepts any ```` ```json ```` fence (`:574`), so any JSON block in an explanation can become a proposal if it has a `steps` array.
- **Agent Builder test run**: calls the same `executeTurn` with the agent instructions glued into the user prompt and `chatHistory = emptyList()` (`AgentBuilderViewModel.kt:457-462`). Status is hard-coded `"Success"` (`:468`) even when `executeTurn` fell back to the rule engine after an error (`AiWorkflowAgent.kt:196-203`). The provider is overridden by substring checks on the model name (`:426-428`, `:316-320`).

### 2.3 Duplicated and dead code

- Provider clients: `core/nodes/.../AiClient.kt` versus `AiWorkflowAgent.callX`. Different capabilities, different defaults (`max_tokens` 800 versus 2048/4096), different Gemini model URL handling.
- Credential-type filter lists and `selectCredential` logic copied between the two ViewModels (`AiAssistantViewModel.kt:68`, `AgentBuilderViewModel.kt:185`).
- `autoFixFlow` is never called (`AiWorkflowAgent.kt:~778`). `IssueSeverity.Info`, `AgentSession`, `AgentSkill`, `AgentTrigger.config`, `settings.toolCallConcurrency`, `settings.reasoningEffort`, `memoryDepth` and the `knowledge` store are write-only or prompt-only.
- `FlowListScreen.kt:212` computes `usedInFlowCount = 0` for every credential and drops the flow list it combined.
- Starter agent and four templates reference "web_search" and "phone event" tools that have no implementation.
- Hard-coded vendor model names ("nemotron-3.5-bolt") in selection logic (`AgentBuilderViewModel.kt:231-233`, `:432`).

## 3. Power versus the n8n AI family

n8n behaviour studied: the Agent node takes sub-nodes as typed inputs (chat model, optional fallback model, memory, tools, output parser). It runs a tool-calling loop with `maxIterations` (default 10), optional intermediate-step output, per-item batching, `continueOnFail`, optional streaming, binary pass-through, human-approval responses for tool calls, and the tool execution is handed back to the engine as separate node runs. Memory is a sub-node (window buffer, DB-backed). Output parsers include structured (schema), item list and an auto-fixing wrapper. Chat models are one node per provider. Tools include HTTP, code, calculator, sub-workflow and agent-as-tool.

| n8n capability | KaivoFlow now | Evidence |
|---|---|---|
| Agent as a Step in a Flow | **Missing.** Agents only run from the Agent Builder test sheet | `AgentBuilderViewModel.kt:400`; no Step in `NodeCatalog` |
| Agent on Always-On engine | **Missing.** No code path from `core/engine/alwayson` or `core/work` to `CustomAgent`; `isPublished` is read only by UI | grep of `CustomAgent` finds only `feature/flows` |
| Tool-calling loop | **Missing** | `AiWorkflowAgent.executeTurn` |
| Tools over KaivoFlow Steps | **Missing.** `WorkflowToolExecutor` edits Flows; it does not run Steps | n/a |
| Iteration, time, token, cost limits | **Settings exist, enforced nowhere.** Only `max_tokens` per call and HTTP timeout | `CustomAgent.kt:70-77` |
| Memory (window, persistent) | **Partial and misleading.** Fixed `takeLast(6)` in chat; `crossSessionMemoryEnabled` and `memoryDepth` are unused | `AiWorkflowAgent.kt:218` |
| Structured output | **Partial in core only.** `ai.extract` has typed field contract and validation (`core/nodes/.../AiExtractionSchema.kt`); chat path has none | n/a |
| Multi-provider | **Partial.** Four wire formats exist twice; core copy lacks Claude. Provider chosen by enum plus `fromKey` fallback to OpenAI for any unknown string (`AiProvider.kt:51-59`) | n/a |
| Fallback model | Missing. The fallback is a keyword rule engine that fakes success | `AiWorkflowAgent.kt:196` |
| Streaming | Missing. Every call waits up to 90-120 s, `stream:false` | `:236,:287,:378` |
| Output parser autofix, retries | Missing. Parse failure silently returns plain text | `:577-619` |
| Agent as tool / sub-agent | Declared in data model (`"sub_agent"`), not implemented | `CustomAgent.kt:44` |
| Human approval for tools | Missing in loop (no loop). UI "Apply" gate exists but is bypassed (finding S1) | n/a |
| Per-item execution, `continueOnFail` | Present in `ai.chat/extract/classify` through engine | `core/nodes/.../AiChatNode.kt:38` |
| Token usage reporting | `AiClient.Result.usage` exists, unused by the UI path | `AiClient.kt:23` |

Net: of the n8n AI family KaivoFlow has the equivalent of "Basic LLM Chain" (the three Legacy `ai.*` Steps) plus a Flow-authoring chat. It does not have an Agent.

## 4. Security findings

### S1 (P0, S) Unapproved overwrite of the open Flow
`AiAssistantViewModel.kt:361-364` saves the model's proposal immediately, with the same id as `currentFlow`. `applyProposedFlow` (`:400`) only saves again and navigates. There is no diff gate, no snapshot or undo, no confirmation, and no check that the Flow is armed in Always-On. Prompt injection or a bad answer can delete steps, change an `net.http` URL or `device.sms` recipient and, if the Flow is active, run on the next Spark. Fix: do not persist in `sendMessage`; keep proposals in memory; on Apply run validation, take a revision, then save.

### S2 (P0, M) No confirmation or allow-list model for destructive or side-effecting actions
There is no model-driven execution today, which is the only reason this is not a live exploit. The data model already advertises device, SMS and workflow tools to the user (`AgentBuilderScreen.kt:1762`). Before any loop is added, a permission model is needed (Section 8, task 6). Today the proposal can contain any Step type including `device.sms`, `net.http` and `code.javascript` with any params, and nothing classifies them by risk.

### S3 (P1, S) Prompt injection through Flow content
`buildSystemPrompt` places step names, `net.http` URLs and the first 60 characters of scripts into the system prompt (`AiWorkflowAgent.kt:453-476`). `getWorkflowOverview` also includes full `params` (`WorkflowToolExecutor.kt:378`). Flow names and imported Flows are attacker-controllable. There is no delimiting and no instruction hierarchy. When tool results arrive later (webhook payloads, SMS and notification text as Items) they will reach the same prompt unless the new core design wraps them as untrusted data and strips instructions authority.

### S4 (P1, S) Gemini key in the URL
`AiWorkflowAgent.kt:214`, `ModelDiscoveryService.kt:82`, `core/nodes/.../AiClient.kt:114`: `?key=$apiKey`. Query strings end up in OkHttp exception messages, proxy logs, crash traces, and in `KaivoException("The request could not be completed: ${e.message}")` which is shown in the chat and persisted (`KtorHttpGateway.kt:136`, `AiWorkflowAgent.kt:200`). Use the `x-goog-api-key` header.

### S5 (P1, M) Key duplication and cleartext in the chat flow
- Every provider save writes the key into two vault rows: the named credential and `ai_provider_config`, whose `toMap()` includes `apiKey` (`AiProvider.kt:90-98`, `AiAssistantViewModel.kt:218-243`). Both are Keystore-encrypted (`core/data/.../Repositories.kt:246`, `CryptoBox.kt`), so this is hygiene, not plaintext. But the internal row is shown in the credential list because `FlowListScreen.kt:207` does not filter it, and rotating a key in one place leaves the other stale (`FlowListScreen.kt:414-433` only syncs the name).
- The decrypted key lives in `AiAssistantUiState.providerConfig.apiKey` for the whole ViewModel life (`AiAssistantViewModel.kt:29`). Acceptable on-device, but it is in a `data class` with a generated `toString`; any state logging prints it.
- No log call touching keys was found (grep for `Log.`/`println` in the AI files: none). No key reaches exports: `NativeFlowBundle`/`FlowStructureBundle` redact credentials (`FlowStructureBundle.kt:19`). No key goes into prompts or the clipboard (the only clipboard use copies an agent response, `AgentsDashboardView.kt:706`).
- `AgentBuilderViewModel` stores `credentialId` only (good); agents and sessions are not vault-encrypted (S8).

### S6 (P1, M) Network: SSRF, cleartext, redirects, key forwarding to arbitrary hosts
- `baseUrl` is free text for OpenAI-compatible, Claude and Ollama (`AiProvider.kt:71`, `CredentialEditSheet`). Nothing validates scheme or host. A Flow can point `ai.chat`'s `baseUrl` at `http://169.254.x.x`, `10.x`, `192.168.x`, `localhost` or the app's own webhook server. `core/network` has no private-range guard (grep found none).
- In `core/nodes/.../AiClient.kt:62`, `baseUrl` is a Step parameter that can be an expression, so item data (webhook, SMS) can choose where the Bearer key is sent. That is key exfiltration if an attacker controls an Item field mapped to `baseUrl`. The skip-key-check also uses `contains("localhost")`, so `https://localhost.evil.com` passes (`:63`).
- Cleartext: no `networkSecurityConfig` exists (only `data_extraction_rules.xml`) and `targetSdk = 34` (`app/build.gradle.kts:16`), so the default Ollama URL `http://10.0.2.2:11434` and any LAN `http://` Ollama are blocked by the platform. This is a functional bug (P2, S): add a scoped cleartext allowance for private ranges only, with a visible warning.
- `HttpRequestSpec.followRedirects` is declared but the gateway hard-codes `followRedirects = true` on the client (`KtorHttpGateway.kt:222`, no per-request use). Whether custom auth headers (`x-api-key`) survive a cross-host redirect depends on the engine; treat as unverified and disable redirects for AI calls.
- Error text from the provider (`bodyText.take(200)`) is rendered into chat and saved to disk (`AiWorkflowAgent.kt:241,292,341,383`). Providers sometimes echo parts of the request.

### S7 (P1, S) No limits on a proposed Flow
No cap on steps, streams, param size or script length in `extractFlowProposal`; no validation against `NodeCatalog` specs or `Lifecycle`. A model reply can create thousands of nodes and bloat Room JSON.

### S8 (P1, M) Stored chat and agent history, no redaction
- `FileAiSessionRepository` writes full message text and tool descriptions as plain JSON into `filesDir/ai_sessions` (`AiSessionRepository.kt:452-453`). `FileAgentRepository` does the same for agents, including `knowledge` content and every test prompt and response, in `filesDir/agents`. Not Keystore-encrypted, unlike credentials. App-private storage and `allowBackup=false` limit exposure, but root, adb on debug builds and device forensics expose it.
- Users can paste keys or secrets into chat; there is no redaction pass before persisting or before sending history to the provider. Flow summaries include URL and script prefixes.
- Deletion works per session and `clearAll` exists. No retention limit (S-R4).
- The privacy centre text promises "Keystore-encrypted credentials ... excluded from backups" (`feature/settings/.../PrivacyAndCredentials.kt:166`), which is true, but it says nothing about chat transcripts that leave the phone to the chosen provider.

### S9 (P2, S) Unbounded loops and cost (future risk, no loop yet)
Today cost is bounded to one request per message (plus model discovery on every config load, `AiAssistantViewModel.kt:113-115`, which also sends the key). `AgentSettings.maxIterations` and `timeoutSeconds` are not enforced because nothing iterates. When a loop is added it must own hard limits (Section 8).

## 5. Robustness findings

| ID | Pri | Eff | Finding | Evidence |
|---|---|---|---|---|
| R1 | P1 | S | `catch (e: Exception)` swallows `CancellationException`, so cancelling the scope runs the rule engine fallback instead of stopping | `AiWorkflowAgent.kt:196` |
| R2 | P1 | S | No cancel for an in-flight turn: no `Job` stored, no Stop button path; `isGenerating` blocks input until the 90-120 s timeout | `AiAssistantViewModel.kt:336-376` |
| R3 | P1 | M | Process death: a pending turn is lost; the user message is persisted only after the response (`persistCurrentSession` at `:374`), so a killed app loses the prompt. `flowProposal` and `diagnosisReport` are not serialised (`AiSessionRepository.kt:490-523`), so restored sessions lose proposals; `loadSession` computes `activeProposedFlow` from them anyway (`AiAssistantViewModel.kt:135`) | cited |
| R4 | P1 | S | Memory growth: `messages` list and session file grow forever; every save rewrites the whole file pretty-printed; `loadFromDisk` reads every session synchronously in the singleton initialiser (`AiSessionRepository.kt:440,476-482`); each `AiChatMessage` may hold a full `Flow` in memory | cited |
| R5 | P1 | M | Model output parsing is regex plus a lenient `JSONObject`; a failed parse becomes plain text with no signal. Reasoning-first answers are returned as content (`extractOpenAiText` falls back to `reasoning_content`, `AiWorkflowAgent.kt:425-433`) so chain-of-thought can be shown and even parsed as a Flow. If extraction fails the raw JSON body is shown (`.ifBlank { jsonText }`, `:411,434,450`) | cited |
| R6 | P1 | S | Credential type mismatch: `saveProviderConfig` stores type `config.provider.key` ("openai", "gemini", "claude", "ollama") while both ViewModels filter for `openAiApi`/`geminiApi`/`anthropicApi`/`ollamaApi` or `credential_type_*` by `contains`. "openai" does not contain "openAiApi", so credentials saved from the Ask AI settings sheet never appear in the picker | `AiAssistantViewModel.kt:68-70,227`; `AgentBuilderViewModel.kt:185` |
| R7 | P1 | S | Wrong ids and keys in prompt, rule engine, diagnoser (see Catalog check 7.3): proposals referencing `code.script` fail at run. The diagnoser's `code.script` branch can never fire | `AiWorkflowAgent.kt:107,462,497-503,521,785-793` |
| R8 | P1 | S | Fallback hides failure: network or HTTP errors produce a keyword rule-engine answer labelled "Offline / Fallback" and agent runs record Success | `AiWorkflowAgent.kt:196-203`, `AgentBuilderViewModel.kt:468` |
| R9 | P2 | S | Concurrency: `saveSession` mutates a copied list but reads `_sessionsFlow.value` outside a lock, so two quick saves can drop one; `persistCurrentSession` launches untracked coroutines; `isGenerating` is a check-then-set race in `sendMessage` | `AiSessionRepository.kt:455-463`; `AiAssistantViewModel.kt:338-351` |
| R10 | P2 | S | `connectNodes` and `removeNode` produce success records for missing or duplicate endpoints; `addNode` does not validate type or params; `validateStructure` has no cycle, port or required-param check | `WorkflowToolExecutor.kt:186-215,315-336` |
| R11 | P2 | S | Model discovery runs on every provider config load and on every credential sheet open and sends the key each time | `AiAssistantViewModel.kt:113`, `CredentialEditSheet.kt:135-139` |
| R12 | P2 | M | `CanvasPreviewView` (304 lines) renders from the proposal with no size cap; no virtualisation checked; a very large proposal (S7) is a memory and jank risk. Not profiled | `F/ai/CanvasPreviewView.kt` |
| R13 | P2 | S | Agent `deserializeAgent`/`loadFromDisk` writes a starter agent into storage on first run and swallows parse errors per file (data loss is silent) | `AgentRepository.kt:81-99` |

## 6. Credentials

- Core vault: `EncryptedCredentialRepository` with Keystore AES-256-GCM (`core/data/.../Repositories.kt:246`, `CryptoBox.kt:15`). `resolve(ref)` is the engine path. This part is sound.
- `feature/settings/PrivacyAndCredentials.kt` (`CredentialsViewModel`) saves a credential as `mapOf("apiKey" to value)` with a free `type` string (`:68-71`) and also OAuth2. `feature/flows/CredentialEditSheet.kt` plus the Overview Credentials tab (`FlowListScreen` view model, `:355-433`) is a second, richer editor over the same repository. Two UIs, two type vocabularies: settings uses free type strings, flows uses `CredentialTypeInfo` (`core/model/.../WorkspaceResources.kt:29`) with ids `openAiApi`, `geminiApi`, and the assistant saves "openai"/"gemini". Types are not a closed registry, so what the Phase 1 credential picker can match is inconsistent. Recommended: one `CredentialTypeRegistry` in `core:model`, one editor, settings screen links to it.
- `CredentialEditSheet` auto-tests the connection on open if a key or base URL exists (`:135-139`). That is a network call with the key on every open, with a user-controlled base URL and no private-range guard (S6). It also saves the key only by sending it through `testCredentialConnection`, whose AI-specific model discovery is the only "test" (non-AI credential types get an OpenAI-style `/models` probe via `AiProvider.fromKey` fallback to OpenAI, `AiProvider.kt:58`; that sends a Slack or Telegram token to an OpenAI default URL if `baseUrl` is empty).
- Secrets are masked with `PasswordVisualTransformation` plus a reveal toggle (`CredentialEditSheet.kt:535`). No clipboard copy of secrets.
- `usedInFlowCount` is faked as 0 (`FlowListScreen.kt:212`). Deleting a credential used by a Flow, an AI Step or an agent gives no warning and breaks runs (`resolve` throws "That credential was deleted").
- Phase 1 picker: the `ai.*` Steps use a `Secret` param named `credential` (`core/nodes/.../AiSpec.kt:217`) so they integrate with the vault. The Agent Builder stores `credentialId` in its own JSON and bypasses the picker. The assistant's `ai_provider_config` row is a third pointer. Target: one reference style, `CredentialRef` by id, everywhere.

## 7. Catalog check

### 7.1 What exists in `core/nodes` now
- `NodeCatalog.kt:130-132` registers `AiChatNode` (`ai.chat`, title "Ask AI"), `AiExtractNode` (`ai.extract`), `AiClassifyNode` (`ai.classify`).
- `catalog/CategoryDefs.kt:75-77` places all three in `ai/chains` with `Lifecycle.Legacy` ("legacy trio, replaced later"). The picker hides Legacy from normal lists and shows them last in search (`feature/canvas/.../PickerLogic.kt:80,98,105,108,203`), and the inspector says "it still works, but a newer Step replaces it" (`StepBehaviour.kt:106`).
- No Agent, Chat Model, Memory, Tool or Output Parser Step exists. `docs/specs/P2-node-taxonomy.md` marks agents, models, memory, tools and parsers as "(planned)".
- `StepCategory.Ai` and the `aiSpec()` helper define the common params: provider (openai/gemini/ollama), credential, model, baseUrl, temperature, maxTokens.

### 7.2 Verification of the owner's statements
- "Ask AI Step removed or changed": **not removed**. It is Legacy, registered and runnable, and still named "Ask AI" in the Step spec. What changed is the Ask AI **screen** (assistant) reached from the Flow list and canvas (`FlowListScreen.kt:681`, `CanvasScreen.kt:673`, `CanvasViewModel.kt:949`). Two different things share the name.
- "Agent builder": it exists only as a screen plus JSON store (route `agent_builder`, `app/.../MainActivity.kt:101,169`). It is not in the catalog, not an engine feature and not in the Always-On code.
- "Credentials": the assistant adds an AI-specific credential flow beside the existing vault UI (Section 6).

### 7.3 Id and key mismatches between the assistant and the real catalog
Real ids come from `NodeCatalog` and `CatalogAliases` (`CategoryDefs.kt:121-170`). The prompt and fixers say:
- `code.script` with param `script` (`AiWorkflowAgent.kt:107,462,497,521,785`). Real id `code.javascript`; param key `code` (`core/nodes/.../code/script/CodeNode.kt:47`). `transform.code` is aliased; `code.script` is not.
- `data.aggregate` (`:501`). Real: `transform.aggregate` is Hidden pending `data.summarize`.
- `app.sheets` (`:503`). Real: `app.google_sheets`.
- `spark.battery` params `condition` is unknown; real params `threshold`, `alsoWhenCharging` (`BatteryTriggerNode.kt:22-23`).
- `spark.schedule` described as "Cron or interval" while `docs/specs/P2-node-taxonomy.md` says it supports six time zones only and no cron.
- `device.clipboard`, `device.file`, `device.notify` match the catalog. Params were not verified one by one.
The prompt is hand-written and will drift again. It must be generated from `NodeSpec`s (Section 8).

## 8. Target architecture

### 8.1 Principles
1. One pure Kotlin module, `core:ai`, depending on `core:model` and `core:engine` interfaces only (no Android, no Hilt, no Compose). Provider HTTP goes through the existing `HttpGateway`.
2. The same loop runs in three hosts: chat screen, a Flow Step family, Always-On. Hosts supply the `ToolRegistry`, `ApprovalPolicy` and `MemoryStore`.
3. Prompts and tool schemas are generated from `NodeSpec` and `ParamSpec`, never hand-written.
4. The UI keeps its screens. ViewModels are rewired to `core:ai` in small steps; `AiWorkflowAgent` stays as a thin adapter until the last step, then is deleted.

### 8.2 Layout

```
core/ai/
  provider/   ChatModel (interface: complete(request): Flow<ChatEvent> | ChatResult)
              OpenAiCompatibleModel, AnthropicModel, GeminiModel, OllamaModel
              ProviderConfig, ProviderRegistry, EndpointPolicy (scheme/host/private-range/redirect rules)
              ModelDiscovery
  message/    ChatMessage, ToolCall, ToolResult, Usage, ContentPart (text, untrusted-data)
  tools/      Tool (name, description, ParamSchema, risk: Read|Write|Destructive|External, execute)
              ToolRegistry, ToolSchemaGenerator (NodeSpec/ParamSpec -> JSON schema per provider)
              StepTool (wraps a StepExecutor), FlowEditTools (current WorkflowToolExecutor),
              SubFlowTool, SubAgentTool
  loop/       AgentLoop (iterate: model -> tool calls -> results -> model), AgentLimits
              (maxIterations, maxToolCalls, maxTokens, maxWallMs, maxCostUnits), CancellationRules,
              ToolErrorPolicy (return error to model vs fail)
  memory/     MemoryStore (interface), WindowMemory, SummaryMemory (later), RunScopedMemory
  output/     OutputParser (Structured via AiExtractionSchema, ItemList, Autofix retry)
  safety/     ApprovalPolicy, Redactor, UntrustedContentWrapper, SecretScanner
  prompt/     SystemPromptBuilder (taxonomy from NodeCatalog), FlowSummarizer
  flowedit/   ProposalParser (JSON -> Flow candidate), FlowProposalValidator (against NodeCatalog,
              Lifecycle, ParamSpec, caps), FlowDiff
core/ai/src/test/  fake ChatModel, scripted tool-call transcripts, injection corpus
```
`core:nodes` gains Steps in a new `ai/agents`, `ai/models`, `ai/memory`, `ai/tools`, `ai/parsers` tree (matching the taxonomy spec), implemented on top of `core:ai`. The three Legacy `ai.*` Steps are re-implemented on `core:ai.ChatModel` so there is exactly one provider code path (and Claude support arrives for free).

### 8.3 How the three hosts use it
- **Chat**: `AiAssistantViewModel` builds `AgentLoop` with `FlowEditTools` bound to an in-memory draft Flow; a turn returns events (text, tool call, proposal). The proposal is validated and shown; Apply is the only save. Existing screens consume a `Flow<AgentEvent>` mapped to the current `AiChatMessage` model.
- **Flow Step family** (per `P2-node-taxonomy.md` AI section): `ai.agent` Step with typed sub-inputs to `ai.model.*`, `ai.memory.*`, `ai.tool.*` and `ai.parser.*` Steps. The engine resolves sub-nodes into config, then `ai.agent.execute` runs `AgentLoop` per Item, with `StepTool` calling real Steps through the existing engine path so Run history, expressions and credentials apply. `ai.agent` declares `requiresNetwork`.
- **Always-On**: a Flow containing `ai.agent` already runs through `core/engine/alwayson` `RunDispatcher` with no engine change, provided the loop is cancellable, bounded by wall time under the foreground-service budget, and its tools are limited to the Flow's wired tools. `CustomAgent` becomes an authoring template that compiles to a Flow (Spark + `ai.agent` + wired tools), so "Publish (Run 24/7)" means "activate that Flow" and the Always-On guarantees apply. `AgentTrigger` types map to existing `spark.*` Steps; no second scheduler.

### 8.4 Confirmation and permission model
Every `Tool` declares a risk class. Policy by host:

| Risk | Examples | Chat (interactive) | Flow Step / Always-On (unattended) |
|---|---|---|---|
| Read | get overview, list Steps, read Items, HTTP GET to allow-listed host | auto | auto |
| Write-draft | add/edit/remove Step in draft Flow | auto, nothing persisted until Apply | not available |
| Write-persist | save Flow, change params of an armed Flow | explicit Apply with diff and revision | not available |
| External-effect | `device.sms`, `app.*` send, `net.http` non-GET, notifications | per-call confirmation sheet (tool, args, target) | only tools wired on the Step canvas; per-tool "allow unattended" opt-in; rate limit; every call logged to the Run |
| Destructive | delete Flow, delete data, clear credentials | confirmation with typed name; never exposed to the model in unattended mode | never available |
Always: untrusted data (Items from webhook, SMS, notifications, tool results) is wrapped as data with a fixed delimiter and the system prompt states it carries no instructions; secrets are redacted before persistence and before sending history; allow-lists for hosts per tool; hard `AgentLimits` with cost accounting from provider `usage`.

## 9. Ordered migration tasks with acceptance criteria

| # | Task | Pri | Eff | Acceptance criteria |
|---|---|---|---|---|
| 1 | Stop auto-saving proposals. `sendMessage` keeps proposal in state only; Apply validates, snapshots a revision, saves. Add FlowProposalValidator against `NodeCatalog` (type exists and not Removed/Hidden, params known, caps: 100 steps, 300 streams, 20 KB per param) | P0 | S | After a chat turn `flowRepository.save` is not called (unit test with fake repo). Proposal with unknown type is rejected with a readable message. Apply creates an undoable revision |
| 2 | Move Gemini key to `x-goog-api-key`; remove key from all URLs; scrub error messages through a Redactor; stop duplicating the key into `ai_provider_config` (store only `credentialId`, model, provider); hide `ai_provider_config` from credential lists | P1 | S | grep finds no `key=` URL; test that error text containing the key is masked; credential list shows no internal rows; rotating the key in one place updates everywhere |
| 3 | Create `core:ai` with `ChatModel` and the four providers, ported from both copies, plus `EndpointPolicy` (https required except private LAN with warning; block link-local and metadata addresses; no redirects; fix `contains("localhost")`). Add `x-api-key` and `Authorization` only for the validated host | P1 | M | JVM tests with a fake `HttpGateway` for each provider (request shape, parse, error, reasoning-only reply); policy tests for 127.0.0.1, 169.254.169.254, 10.0.0.0/8, `localhost.evil.com`, redirects |
| 4 | Port `ai.chat/extract/classify` and `ModelDiscoveryService` to `core:ai.ChatModel`; delete `AiClient` and the per-provider code in `AiWorkflowAgent`; add Claude to the Steps; treat `baseUrl` as non-expression param | P1 | M | Existing `core:nodes` AI tests pass; one provider code path remains (grep); `baseUrl` cannot be driven by an Item expression |
| 5 | Generate system prompt and node taxonomy from `NodeSpec`s (`SystemPromptBuilder`); fix `code.javascript`/`code` ids; remove rule-engine templates that use wrong ids or generate them from catalog; delete dead code (`autoFixFlow`) | P1 | S | Golden test: every step type named in the prompt exists in `NodeCatalog`; rule-engine flows pass `FlowProposalValidator` |
| 6 | `Tool`, `ToolRegistry`, risk classes, `ApprovalPolicy`; port `WorkflowToolExecutor` into `FlowEditTools` on a draft Flow with real validation (missing endpoints fail, duplicates fail, type/params validated) | P1 | M | Tests per tool for success and failure; no tool persists; risk class on every tool enforced by a registry test |
| 7 | `AgentLoop` with `AgentLimits` (iterations, tool calls, tokens, wall time, cost), cancellation (rethrow `CancellationException`), tool errors returned to the model, untrusted-content wrapper, redaction, structured-output retry. Native tool calling per provider with JSON schema from tools | P1 | L | Scripted fake model tests: stops at maxIterations; stops on cancel without fallback; tool error recovered; injection corpus does not trigger a High-risk tool without approval; usage totals accumulate |
| 8 | Rewire chat to the loop: stream events (start with non-streaming, then SSE), Stop button holding the Job, persist the user message before the call, serialise proposals, cap and paginate sessions, redact before save | P1 | M | Kill-app test: prompt survives; Stop cancels within 1 s; session file under size cap; restored session still offers Apply |
| 9 | Credentials: single `CredentialTypeRegistry` in `core:model`; fix type vocabulary and the picker filter (R6); one editor; real `usedInFlowCount` (Flows, Steps, agents); delete warning; test-connection only on user action and via `EndpointPolicy` | P1 | M | AI credentials saved anywhere appear in all pickers; deleting a used credential warns with count |
| 10 | Flow Step family: `ai.agent`, `ai.model.*`, `ai.memory.window`, `ai.tool.step` (wraps a wired Step), `ai.parser.structured`, with sub-input ports in the engine; Legacy `ai.*` get converters to `ai.agent`/chain (per taxonomy spec section 6) | P1 | L | An agent Flow runs from Manual and from a Schedule Spark; Run history shows per-iteration tool calls; engine tests for sub-input resolution; legacy Flows still load |
| 11 | Always-On: Flow with `ai.agent` runs under `RunDispatcher` with wall-time and cost caps and unattended tool opt-in; `CustomAgent` compiles to a Flow; "Publish" activates it; remove the unused `AgentTool/Trigger` fields or map them | P1 | M | Instrumented or fake-clock test of a scheduled agent Run; kill and restore keeps Run state; unattended External tools require opt-in and respect rate limit |
| 12 | Network config: scoped cleartext for private ranges only (fixes Ollama on API 28+), per-request redirect flag honoured in the gateway | P2 | S | Ollama on LAN works over http; public http host is blocked |
| 13 | Encrypt session and agent stores with `CryptoBox` (or move to Room with encrypted column); retention setting; "clear AI history" in Privacy Centre | P2 | M | Files on disk are not readable text; retention prunes; privacy text updated |
| 14 | Tests for `feature/flows` AI ViewModels and repositories (fake `HttpGateway`, fake repos); split `AgentBuilderScreen` (2000 lines) after backend swap | P2 | M | CI runs the new tests; no screen file over ~800 lines |

Suggested order: 1, 2, 5 (days; removes the real risks and the broken output), then 3, 4, 6, 7, 8 (core), then 9, 10, 11, then 12-14.

## 10. Things not verified
- Cross-host redirect header handling in the Ktor OkHttp engine.
- Rendering cost of `CanvasPreviewView` and `MarkdownChatText` with large input.
- Whether activating a Flow that the assistant just overwrote triggers an Always-On reconcile (the save path in `RoomFlowRepository.save` was seen, the reconcile hook was not traced).
- The full diff against 850d58d (no git available); statements about "what Antigravity added" are inferred from file location and the owner's list.
