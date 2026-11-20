# Wave 1, lane A (core/ai): status

Scope: provider reliability, capability table, fallback chain, tool choice, thinking round trip, approval clock and
feedback, lenient tool arguments and error contract, final-answer tool, streaming client and events, usage and cost.
All code is pure Kotlin in `core/ai`. Offline suite: 148 tests green (77 before this lane), plus `core/nodes` suites
(see the report for the run result). Nothing here was run against a live provider.

| Task | State | Notes |
|---|---|---|
| 1 Retry and taxonomy | done | `ModelErrors.kt`: n8n list (400-407, 409, 413 never retried; 408 is retried, as in n8n), 429 quota text or Retry-After over 60 s stops, else waits the hint; backoff with jitter, max 2; cancel passes through even during the wait. New kinds: Quota, ContextLength, PayloadTooLarge, Timeout, Unsupported. `ModelException` has status, retryAfterMs, providerCode, attempts, retryable, hint. `AgentError` carries them. Length cut: one continue call, then `AgentResult.truncated`. Agent default max output raised to 4096. |
| 2 Reasoning models | done | `ModelCapabilities.kt`. On api.openai.com: `max_completion_tokens` for all models, no temperature for o-series and gpt-5 (not `*chat*`), `reasoning_effort` where accepted, 4096 floor for reasoning models (the cap counts hidden thinking). Other OpenAI-compatible hosts keep `max_tokens`. Anthropic temperature by allow-list (n8n rule). Gemini 2.5+ gets the 4096 floor. |
| 3 Fallback and force tool | done | `FallbackModelClient`, `AgentLoop(fallbacks=, forceToolFirst=)`, `ToolChoice` mapped for OpenAI, Anthropic, Gemini. Ollama has no tool choice (ignored). Anthropic drops forcing while thinking is on (the API refuses it). Cancellation never falls back. |
| 4 Thinking round trip | done | Opaque `providerData` on `ToolCall` and `ChatMessage`: Anthropic thinking and redacted_thinking blocks, Gemini `thoughtSignature` on calls and text, OpenAI-compatible `reasoning_content`, Ollama `thinking`. Replayed only when written by the same provider for the same model id. Anthropic blocks are replayed first in the turn (interleaved thinking order is not kept). Needs a manual smoke against live APIs. |
| 5 Approval clock and feedback | done | Wall clock stops while a person is asked; own limit `approvalTimeoutMs` (10 min) then refusal. `ApprovalDecision.Denied.feedback`, `ApprovalRelay.respond(approved, reason, feedback)`; model sees the person's words. Denial text still starts with "Not allowed". |
| 6 Tool contract and structured output | done | `ToolContract.kt`: lenient argument reader (quotes, commas, comments, Python literals, fences, plain text into a single parameter, primitive coercion; cut-off JSON is refused, never completed). Errors: `There was an error: ...`, status first (`HTTP 401 There was an error: ...`), body redacted and capped at 2000. `FinalAnswer.kt`: hidden `format_final_answer` tool with text parsing and repair as fallback; `ItemListParser`. |
| 7 Streaming | done in core, transport missing | `StreamEvent`, `ChatModelClient.stream` (default replays `complete`), SSE for OpenAI-compatible, Anthropic, Gemini, NDJSON for Ollama (native `/api/chat`), mid-stream retry with a reset event, cut-off detection. `AgentLoop.run(onEvent)` and `runStreaming()` emit `AgentEvent`. **Needs `HttpStreamGateway` in `:core:network`** (not mine); without it clients replay the finished answer, which is honest but not incremental. |
| 8 Usage, cost, budget | done | `Usage` (cached, reasoning, estimated), `PriceTable` (bundled, replaceable by JSON), `TokenEstimator`, `UsageLedger`, `RunBudget` (shared, pure). Unknown price is null, never zero. |

## Not done or limits

- Bundled prices were written from memory of public lists, not fetched: verify before showing money to users.
- `maxCostUnits` is in the unit of `CostRates` when given, else US dollars.
- Ollama kind now uses the native API (`/api/chat`); users who point the OpenAI kind at Ollama keep SSE `/v1`.
- Streaming idle timeout is the gateway's job; the clients only cancel on collector cancellation.
- Prompt caching, Responses API, image parts: not in this lane.
