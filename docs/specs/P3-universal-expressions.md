# P3 Universal expressions

Goal: every input that holds runtime configuration can take `{{ }}` expressions with a fixed/fx switch, chips, live preview and tap-to-map, in a node or outside one. Policy lives in code: `core/model/.../ExprSurfaces.kt` (the table below mirrors it; a test pins the Never rows).

## 1. Behaviour studied (n8n, read only, nothing copied)
- A property accepts expressions unless its definition opts out (a per-property flag). The fixed/expression toggle exists only where the property accepts them; the stored value is text with a marker for "expression".
- Opt-outs are for values read before any data exists (trigger registration) or never data (secrets handled separately).
- Outside a node the scope is small: workflow variables only. Credentials get variables, and secrets only in credentials. No node data is reachable because no node is running.
- Preview and suggestions run against the same scope as the run; a missing upstream gives "no data yet", not a failure.

## 2. KaivoFlow pieces reused
- `ParamValue` Fixed/Expr and `ParamValueCodec` (`kx` marker); `ParamResolver` and `KaivoExpressionEvaluator` evaluate; `KaivoExpressionAssist` previews, diagnoses and completes; `ExpressionField` draws chips, accessory bar, undo and the preview line.
- New: `ExprFieldCodec` (one-string storage: plain text stays Fixed, `{"kx":..}` is Expr, never guessed from `{{`), `ExprInputModes`, `GlobalScope`, `UniversalResolver`, `VariableResolution`, `CredentialExpr`, `ExprCredentialProvider` (core/engine `UniversalExpressions.kt`), `KaivoInputField` and `ExpressionScopeProvider` (feature/canvas `input/`).

## 3. Scopes
| Scope | Gives | Never gives |
|---|---|---|
| Upstream (nodes, inspector) | `$json`, `$step()`, `$vars`, `$now`, `$run` | environment |
| Global (everything else) | `$vars`, `$now`, `$today`, `$run.flowId/flowName` (agent id and name for agents); `$json` and `$items` are empty and preview as "not available here" | item data, environment, secrets |

An expression variable sees only fixed variables in `$vars` (no cycles). Credentials see workspace variables only (not group variables).

## 4. Policy table
Decision: X = Expression-capable, F = Fixed only, N = Never.

| Id | Input | D | Scope | Resolved | Reason |
|---|---|---|---|---|---|
| agent.instructions | Agent Builder, instructions | X | Global | per call | prompt is built on each call |
| agent.knowledge.content | Agent Builder, document text | X | Global | per call | added to each prompt |
| agent.trigger.schedule | trigger cron | F | - | - | registered with the OS before data exists |
| agent.trigger.webhook | trigger webhook path | F | - | - | a route is registered once |
| agent.settings.numbers | iterations, memory, concurrency, timeout | F | - | - | limits must be known before a call |
| agent.name | agent name, skill name, document title | N | - | - | names and titles |
| agent.testPrompt, assistant.chat | chat and test message boxes | N | - | - | user message, not configuration |
| assistant.modelSearch, search.any | all search boxes | N | - | - | filters |
| variable.value | workspace and group variable value | X | Global | per Run | evaluated once at Run start |
| variable.key | variable key | N | - | - | identifier other values name |
| credential.field | non-secret credential field (base URL, user name, header name, version) | X | Global | per call | evaluated when a node asks for the credential |
| credential.secret | API key, token, password, header value, any `isSecret` field | N | - | - | secrets are never evaluated or previewed |
| credential.name | credential name | N | - | - | label |
| oauth.setup | token address, client id (Settings) | F | - | - | used by background refresh with no Run |
| oauth.secret | client secret, refresh token, secret value | N | - | - | secrets |
| flow.name | flow, group, table names and descriptions | N | - | - | names and titles |
| flow.settings | timezone, timeouts, limits | F | - | - | read by the engine before data exists |
| note.sticky | sticky note title and text | F | - | - | documentation, shown as typed |
| table.row | data table cell values | F | - | - | literal data entry (a node writes rows with expressions) |
| tool params (AI nodes) | node parameters set by the AI | X | Upstream | per item | go through the inspector rules; checked by `ExpressionGuard` |

## 5. Where each expression is resolved (exact code)
- Variable values: `core/work/.../FlowEngine.kt` `resolveEffectiveFlow` calls `VariableResolution.resolve` (core/engine `UniversalExpressions.kt`). It runs once per Run, also for Sub-Flows and every start path (all three callers use it). A failed variable is left out (reads as missing) and its message is in `Result.errors`.
- Credential fields: `ExprCredentialProvider.resolve` wraps the vault in `FlowEngine.runner` (`credentials =`). It covers `StepContext.credential()` and direct `ctx.credentials.resolve` (ConnectorSupport). Runs per call. Secret fields and unknown credential types are returned untouched (fails closed). A bad expression throws `KaivoException` naming the field; the Step fails by its error policy.
- Credential fields read outside a Run (AI screens): `feature/flows/ai/CredentialFieldResolver` in `AiAssistantViewModel.loadProviderConfig` and `selectCredential`, `AgentBuilderViewModel.selectCredential` and `runTestAgent`, `FlowListViewModel.testCredentialConnection`. A bad expression is shown (`modelDiscoveryMessage`, `credentialError`, test result) and nothing is called.
- Agent prompt: `feature/flows/ai/agent/AgentPrompt.build`, called by `AgentBuilderViewModel.runTestAgent` on every call with `GlobalScope.build(workspaceVars, agent id and name)`. A bad expression gives `Built.Failed`, an Error session and the message in the test sheet; no model call, no crash. Model call errors are caught the same way.
- AI-written parameters: `ExpressionGuard.check` (parse only) in `AiWorkflowAgent.parseResponseToMessage` and `WorkflowToolExecutor.addNode` / `updateNodeParameters`. The change is kept and the tool record is marked failed with the broken parameter. Execution of those parameters is the normal node path (`ParamResolver.resolveStored`, per item).
- Node parameters: unchanged (`StepContext.resolvedFor(index)`, per item).

## 6. Storage and compatibility
- Strings in variables, credentials and agent files use `ExprFieldCodec`. Old data has no marker and reads as Fixed. A fixed text that starts with the marker is written as `{"kxLiteral":".."}`.
- `Flow.vars` values use `ParamValueCodec`; `VariableResolution` reads both.
- `CustomAgent.instructions` and `AgentKnowledgeItem.content` stay `String` fields holding the codec form, so agent JSON files are unchanged.

## 7. Component contract
`KaivoInputField(value: ParamValue?, onValueChange, label, fieldId, scope: ExpressionScopeProvider?, ...)`. Fixed mode is a plain field; fx mode is `ExpressionField`. `scope = null` means no switch. `ExpressionScopeProvider` supplies `assist`, `scope`, `preview(text)`, an optional `onOpenMapper`, and `mapperItems` for the built-in tap tray. `GlobalExpressionScope` is the provider for every non-node screen. Never use it for rows marked N or F.

## 8. Not done here
- Node notes and behaviour fields (inspector host), sticky notes stay F.
- `CanvasViewModel` preview of variables reads raw values; route it through `VariableResolution`.
- Agent triggers and tools have no runtime yet; their per-call resolution must call `UniversalResolver` when added. Webhook response body and headers should be X (per request).
