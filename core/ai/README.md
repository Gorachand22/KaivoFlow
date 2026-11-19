# core:ai

## How n8n's AI family behaves (behaviour note, own words, studied for behaviour only)

Studied from the clone in `/tmp/ref/n8n` (Sustainable Use License). Nothing below is copied code, naming or text.

1. The AI Agent is one node. It does not hold a model, memory or tools itself. Those are separate sub-nodes that
   plug into typed input sockets on the agent: one chat model (a second one can be added as a fallback), an
   optional memory, any number of tools and an optional output parser.
2. Every chat provider is its own node (OpenAI, Anthropic, Gemini, Ollama, Groq, Mistral and about twenty more).
   They differ in settings and wire format, not in what the agent does with them.
3. The agent runs a tool-calling loop. Each round the model answers with text or with tool calls, the engine runs the
   tools, the results go back, and the model is called again. The loop ends on a plain text answer.
4. An iteration cap (default 10) ends a runaway loop. Hitting it is an error for that item, not a silent answer.
5. Items are processed one at a time, or in batches with an optional pause between batches. A failure on one item can
   be allowed to continue so the other items still finish ("continue on fail").
6. Memory is a sub-node. The common one is a window buffer that keeps the last N exchanges per session key; the key
   is usually an expression so each chat or customer gets its own thread. Database-backed memories exist too.
7. Tools are sub-nodes: HTTP request, code, calculator, think, search, call another workflow, use another agent.
   Any node can be exposed as a tool; the model fills the tool's parameters, which are shown to it as a schema.
8. Structured output: an output parser sub-node holds a JSON schema. The agent gets the schema in its prompt (n8n adds
   a hidden "final answer" tool) and the reply is parsed. A wrapper parser asks a model to fix a bad reply and retries.
9. Optional extras: return the intermediate steps (each tool call and its result), pass binary images or PDFs to the
   model, stream tokens, and in the newer versions pause a tool call until a human approves it.
10. Failures show up as node errors with the provider's text. There is no hard token or cost budget and no wall-clock
    limit beyond the workflow's own timeout; token counts are only reported to tracing.

What KaivoFlow keeps, drops and adds

- Kept: agent plus attachable parts, iteration cap, per-item runs, continue-on-fail through the Step error policy,
  window memory keyed by an expression, any Step as a tool, schema output with repair, intermediate steps in the output.
- Changed: attachments live inside the Agent Step (taxonomy decision 4), not as separate wired nodes, so copy and
  duplicate of an Agent is atomic and a phone canvas needs no five extra wires.
- Added, beyond n8n: hard limits on iterations, tool calls, tokens, estimated cost and wall time; a per-tool timeout;
  cancellation that never turns into a fallback answer; a risk class on every tool with an approval model that works
  attended (chat, notification) and unattended (always-on); untrusted data fencing with a per-run nonce; host guard
  against private, loopback and link-local addresses; keys only in headers, never in URLs, never printed; windowed
  memory that is bounded when stored; token and iteration counts in every Step output.

## Layout

All code is pure Kotlin, JVM tested, Android free. Depends on `:core:engine` (which re-exports `:core:model`) for
`HttpGateway`, `StepExecutor` and `NodeSpec`. Package `com.kaivoflow.core.ai`:

| File | What it holds |
|---|---|
| `Messages.kt` | `ChatMessage`, `ToolCall`, `Usage`, `ChatRequest/Response`, `ChatModelClient`, `ModelException` |
| `Providers.kt` | `ModelConfig`, `ChatModels.create`, OpenAI-compatible (also Ollama), Anthropic and Gemini adapters |
| `EndpointPolicy.kt` | base URL guard: scheme, private and loopback and link-local ranges, IP obfuscation, optional resolver |
| `Safety.kt` | `ApiKey` (never prints), `Redactor`, `UntrustedData` fence |
| `Tools.kt` | `AgentTool`, `ToolRegistry` (allow-list), `StepTools` (risk, schema, offerable), `StepTool` |
| `Approval.kt` | `ToolRisk`, `ApprovalPolicy`, `ApprovalCallback` (chat sheet and notification both implement it) |
| `AgentLoop.kt` | the loop, `AgentLimits`, `CostRates`, `AgentResult` with structured errors and a tool trace |
| `Memory.kt` | `ConversationMemory`, `WindowMemory`, `MemoryStore` seam (persistence later) |
| `OutputParser.kt` | `SchemaCheck` (JSON Schema subset), `JsonExtractor`, `StructuredOutputParser` with repair-retry |
| `SystemPrompt.kt` | the system prompt, with the untrusted-data rules |

Flow Steps (in `:core:nodes`, `ai/`): `ai.agent`, `ai.chat_model`, `ai.basic_chain`, `ai.parser.structured`. The old
`ai.chat`, `ai.extract` and `ai.classify` stay as Legacy Steps on their own client (porting them is audit task 4).

## Known limits

- The Ktor gateway in `:core:network` hard-codes redirect following; the adapters send `followRedirects = false` and
  also refuse any 3xx answer, but a gateway that follows redirects itself would follow before the adapter sees it.
- DNS rebinding is only caught when the host app passes a `HostResolver`.
- Persistence for memory, the approval UI and streaming are not here yet.
