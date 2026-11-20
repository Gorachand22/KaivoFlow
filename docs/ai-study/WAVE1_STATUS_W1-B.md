# Wave 1, lane W1-B: Agent Builder, Publish (Run 24/7), AgentNode memory

Scope: `feature/flows/ai/agent/*`, `AgentsDashboardView`, the agent parts of `FlowListScreen`, `core/nodes` AgentNode, `core/engine` PublishValidator (one agent hook).
Study: `02-agents-and-chat-hub.md` sections 11 and 12, audit AI-01, AI-06 (AI-08 belongs to W1-A, `core/ai`).

## Done

| Item | What exists now |
|---|---|
| Publish (Run 24/7) is real (AI-01) | `AgentProductionCompiler` turns a `CustomAgent` into the hidden Flow `agent:<id>` (tag `kaivo:agent`): one Spark per enabled trigger, one `ai.agent` Step, optional `device.notify` delivery Step. `AgentPublicationService` saves it and hands it to `ActivationManager` (so `ActivationCoordinator`, `PublishValidator`, the Always-On engine, boot restore and the Reconciler all apply unchanged). Publish, publish changes (revision), pause, resume, unpublish, retry, delete go to the engine. |
| Truthful Live badge | `AgentLiveState` is derived only from the engine lifecycle of the hidden Flow (Published, Paused, Error mapped to Live, Paused, Needs attention). "Changes not live" compares a fresh compile with the stored Flow through the engine's own `ChangeDetector`. `CustomAgent.isPublished` is deleted; old `isPublished: true` in files is ignored. No code path sets a live state. |
| Agent validation | Blocking: model, credential (present in vault, endpoint allowed by `EndpointPolicy`, live model-list probe works), instructions, at least one trigger that runs on its own, trigger or tool without implementation (refused, never dropped), token budget 1,000..1,000,000, iterations, tool calls, timeout, rate limit, memory key, conflicting objectives. Warnings (need "Publish anyway"): tool not allowed unattended, webhook steering tools, volatile memory. Engine checks (permissions, webhook token, schedule) run on top. |
| Unattended approval policy | Only tools the author marked `allowUnattended` (new field, default false, per-tool switch in the Capabilities list) are offered in a published run; per-tool call limit from `rateLimitPerTool`; budget from `maxTotalTokens` and `maxToolCalls` (new Settings rows). |
| Engine hook | `PublishValidator` warns `agent_unattended_tools_from_untrusted_spark` for any Flow (not only agents). |
| Run records and history | Runs of `agent:<id>` are normal Run records. The agent card and the history inspector merge them with test sessions (`AgentRunHistory`); the builder Sessions tab does too. Hidden Flow and its runs are kept out of the Flow list and recent runs. |
| Test Run (task 2) | Still only that agent, in test mode, never a production Run (`testStep`). Now shares limits, memory and tool mapping with production. The seven differences are listed in the KDoc of `AgentFlowCompiler`. |
| AgentRepository (task 3) | `AgentFileStore` (tmp, fsync, atomic rename), `AgentRepositoryImpl` (one mutex, load on IO at first read, nothing in the constructor), schema version 2 (`AgentJsonCodec`, v1 files load), corrupt or mismatched files moved to `agents/quarantine/` and reported, stray `.tmp` removed, ids validated as file names, sample agent seeded once. |
| AgentNode (task 4, AI-06) | Memory with no key is a blocking validation error `agent.memory_key`; at run time an item whose key is empty fails alone (no shared default). Stored key is `flow:step:key`, so the same key in another Step or Flow never shares history. |
| Honest lists (task 6) | Skills, Web search, Workflow, Sub-agent, Code tool, Phone event trigger, Monthly usage, Sub-agents, MCP, memory model, Reasoning, Prompt caching, tool concurrency are marked "Coming soon" and disabled. "CONNECTED ACCOUNTS" is now "SAVED CREDENTIALS". Fake HNSW and chunk-size text removed. Starter templates no longer add a web search tool. Dead duplicate private dashboard composables in `FlowListScreen.kt` removed. |

## Partial or not done

- Task 5 (Flow Step form pickers for `ai.agent`): not done. The model, tools and memory pickers in the builder are private composables inside `AgentBuilderScreen.kt`, and the Step form lives in `feature/canvas` (`ParamForm.kt`, generic `JsonParamField`), outside this lane. Proposal in the report.
- Cross-session memory is `InMemoryStore` only (lost when the process dies). Publish warns about it. Needs the Room store (W1-C, audit AI-06 second half).
- Monthly and per-session cost caps, notification approvals for unattended runs (A10), budget ledger (A11): not done. Only per-run token and tool-call limits exist.
- Webhook memory key and trigger objective have no editor in the builder (no UI added); they are read from trigger `config` (`memoryKey`, `objective`). Schedule editing is also only the preset (daily 09:00); `AgentTriggerConfig` accepts the full frequency set and simple cron text.
- "Run now" for a published agent is not added (the hidden Flow has the Manual Spark if the agent has a manual trigger).
- AI-08 (approval wait inside the wall timer) is W1-A.

## Tests

`feature/flows`: `AgentStorageTest`, `AgentPublishTest` (compile, refusals, service with fake ports, live state, run history, real expression evaluation). `core/nodes` `AiFamilyTest` (memory key rules, isolation, production step shape). `core/engine` `PublishValidatorTest` (agent hook). Run offline with the scripts in `/home/claude/scratch-w1-b`.

## Reasoned only (Android and Compose, not compiled here)

`FileAgentRepository`, `AgentPublicationAdapters`, `AgentModule`, all edits in `AgentBuilderScreen.kt`, `AgentBuilderViewModel.kt`, `AgentsDashboardView.kt`, `FlowListScreen.kt`.

## Findings against the studies

- The engine reads Step params at top level in the legacy format (a string with `{{` is an expression; `{"kx": ...}` maps are not read there, `ParamResolution.kt:191`). The production compile therefore writes plain strings, not `kx` objects, and neutralises author text.
- Study 02 section 11.1 proposed an `AgentPublication` Room row. Not needed: the hidden Flow's lifecycle row is the publication, so there is no second state to drift.
- A `{{ $json }}` object cannot go into a text param; the webhook payload is passed as `JSON.stringify($json)`.

## Follow-up needed after W1-A

None blocking. When W1-A adds notification approvals (A10) and the wall timer fix (AI-08), published agents get them with no change here.
