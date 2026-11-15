# Audit 06: Code quality, duplication, architecture and build health

Lane: code quality. Scope: 116 Kotlin files, 29,565 lines, 13 Gradle modules. Read-only analysis.
Method: grep/wc/reading with file:line evidence, plus a pure-JVM compile-and-run of the core
modules in a scratch directory outside the repo (details in section 6). Findings marked
"verified by execution" were reproduced with a probe program, not only read.

## 1. Summary

The owner's suspicion is partly right and partly wrong.

What is right: there is a lot of copy-paste at the node level (seven separate JSON converters,
four copies of the same JsonElement-to-Any decoder, three date parsers, 92 hand-written error
throws), three UI files above 1,000 lines each, a fake "QuickJS" evaluator that is a thin
renamed wrapper, and at least ten dead functions or composables.

What is wrong: the module layering is mostly honest. `core:model`, `core:engine` and `core:nodes`
really are Android-free plain JVM modules and compile and test without the Android SDK. Test
names are descriptive, not padding. The engine (`FlowRunner`) is long but coherent.

The more serious problem is not style. Three correctness defects were found that the existing
tests cannot see, because the tests use single-item inputs and stub evaluators:

1. The expression evaluator that ships in the app cannot evaluate anything except `$a.b.c` path
   lookups. Arithmetic, comparisons, method calls and the Code node's JavaScript all throw or
   silently return null (verified by execution). Docs and README claim "sandboxed JavaScript".
2. About 29 call sites in 6 node files resolve parameters twice. The first resolve is against
   item 0 only, so every later item gets item 0's text, URL, recipient, prompt or message
   (verified by execution for Slack, Discord and Telegram; same pattern by reading in HTTP,
   Notification, SMS, AI chat, Date, Crypto and others).
3. `ctx.params()` expression-expands Secret params too, contradicting the documented guarantee
   (verified by execution with the Slack webhook URL).

Build status: Gradle cannot run here (no network to Maven, no Android SDK, wrapper wants 8.5 but
8.14.3 is installed and cannot fetch plugins). I compiled and ran 204 of the 209 tests on the
plain JVM using the Kotlin compiler jars bundled with Gradle. All 204 pass. The remaining 5 need
Room/Android classes.

Docs: PARITY.md, HANDOFF.md, DEVELOPER_HANDOFF.md, BACKLOG.md and docs/CAPABILITY_GAP.md are all
linked from the README and none exist. About 10 other doc claims are not backed by code (section 7).

## 2. Duplication groups

Each group lists evidence and one proposed shared abstraction.

### D1. JSON handling: seven implementations of the same two jobs (P1)

Job A, parse text to `Any?`: `JsonLite` (core/nodes/.../TransformNodes.kt:379, 1 MB cap, returns
null for both "invalid" and "JSON null", so callers cannot tell them apart) and `MiniJson`
(core/nodes/.../MiniJson.kt:12, depth cap) live in the same module and do the same thing.
Callers split between them: JsonLite at AiNodes.kt:108/148/185, AiExtractionSchema.kt:8,
DeviceNodes.kt:273, TransformNodes.kt:158; MiniJson at CompositionNodes.kt:354,
ProtocolNodes.kt:146, SecurityNodes.kt:136, ExtendedLogicNodes.kt:299.

Job B, `JsonElement` to `Any?`, copy-pasted four times with different edge-case behaviour:
- core/data/.../JsonCodec.kt:346 (handles the literal "null" string and booleans)
- core/network/.../KtorHttpGateway.kt:192 (falls back to raw text for unparseable numbers)
- core/work/.../WebhookServer.kt:184 (returns null for unparseable primitives)
- core/work/.../FlowRunWorker.kt:143 (same as WebhookServer)

Behavioural drift is real: a webhook body number that fails `toDoubleOrNull` becomes null in
WebhookServer but stays a string in KtorHttpGateway. All four convert every integer to Double,
so IDs above 2^53 lose precision.

Job C, `Any?` to JSON text: `Values.writeJson` (core/engine/.../Values.kt:130),
`JsonCodec.toJson` (JsonCodec.kt:325), `KtorHttpGateway.serialize` (KtorHttpGateway.kt:~205).

Proposal: add `kotlinx-serialization-json` to `:core:engine` (it is plain JVM, no Android) and
create one `JsonValues` object there: `parse(text): ParseResult`, `toAny(JsonElement)`,
`toElement(Any?)`, `stringify(Any?, pretty)`. Delete `JsonLite`, `MiniJson`, the three
`fromJson` copies and `Values.writeJson`. Preserve integers as `Long` when they fit.
Effort M. Risk low-medium (golden-file tests in JsonCodecTest and ValuesTest cover most shapes).

### D2. Parameter resolution: three competing styles (P0, see F1)

- `ctx.params()` (StepExecutor.kt:46): resolves every param against item 0.
- `ParamResolver(ctx.evaluator)` constructed by hand 22 times in core/nodes.
- `ctx.resolveText/resolveNumber/resolve` (NodeDsl.kt:15-30): the right API, used 51 times, but
  only in the newer Extended*/TextFormat files.
- `ctx.stringParam` and `ctx.requireParam` (StepExecutor.kt:57, 59): never called.
- `Values.asString(params["x"])` pattern: 105 occurrences.
- `outputField(...)` helper plus `ctx.outputName(...)` plus 53 inline `"outputField"` lookups.

Proposal: one typed reader. `ctx.text("key", item = i)`, `ctx.number(...)`, `ctx.bool(...)`,
`ctx.secret(...)` (raw, never expanded), `ctx.outputName(default)`. Make `params()` private or
remove it. `ctx.credential()` (StepExecutor.kt:62) must stop calling `params()`; it re-resolves
every param and rebuilds the scope on each call. Effort M (mechanical, 29 sites to convert).
Risk medium, but it fixes F1 and F2.

### D3. HTTP-calling boilerplate in SaaS/AI/protocol nodes (P1)

17 hand-built `HttpRequestSpec(` literals in core/nodes (SaasNodes.kt:55,108,153,218,242,307,393,414;
AiNodes.kt:87,137,166; ProtocolNodes.kt:50,137,216; CompositionNodes.kt:150,233; BinaryNodes.kt:300;
HttpNode.kt:136). 7 repeat `"Content-Type" to "application/json"`; 15 repeat `if (!response.isSuccess) throw
StepExecutionException(ctx.step.id, ctx.step.name, "<Vendor> ... HTTP ${response.status} ...")`. Telegram,
Slack and Discord (SaasNodes.kt:23-170) are the same node three times with different URL and body key names.

Proposal: `ctx.postJson(url, body, headers, timeoutMs)` and `HttpResponseSpec.orFail(ctx, "Slack")`
in NodeDsl.kt; a small `WebhookPostNode(spec, bodyBuilder)` base for Slack/Discord/Telegram.
Effort S-M. Risk low.

### D4. Error construction (P2)

92 occurrences of `throw StepExecutionException(ctx.step.id, ctx.step.name, ...)` across 16 files.
One inconsistent site passes empty strings (TransformNodes.kt:304: `StepExecutionException("", "", ...)`),
so that error loses its step identity in the Run record. Proposal: `ctx.fail("message"): Nothing`
in NodeDsl.kt. Effort S. Risk none.

### D5. Date parsing (P1)

Two private `parseInstant` implementations with different rules: TransformNodes.kt:297 (ISO instant,
date, local datetime in the step's zone, no epoch) and TextFormatNodes.kt:256 (accepts epoch millis,
interprets local datetimes as UTC). The same text yields different instants depending on which node
reads it. Other `Instant.parse`, `ZoneId.of`, `DateTimeFormatter.ofPattern` call sites: 21 across 7
files (TransformNodes 7, TextFormatNodes 6, ExtendedDeviceNodes 2, ScheduleMath 2, Expression.kt 2,
FlowScheduler 1, ExtendedLogicNodes 1). `ZoneId.of(...)` with a silent `runCatching` fallback
appears in Expression.kt:95 and FlowScheduler.kt:217, with different fallbacks (UTC vs system default).

Proposal: one `core/model` `DateText` object with `parse(text, zone)`, `format(instant, pattern, zone)`,
`zoneOrDefault(id)`. Effort S. Risk low-medium (pick one rule set and test it).

### D6. Text escaping (P2)

Six private escape helpers with the same name and different behaviour: ExtendedLogicNodes.kt:500
(XML/SVG, escapes quotes), TextFormatNodes.kt:268 (iCal), :332 (CSV), :382/:385 (XML text/attr),
:624 (Markdown HTML, does not escape quotes). Proposal: an `Escapes` object (`xml`, `xmlAttr`, `html`,
`csv`, `ical`). Effort S. Risk low.

### D7. UI duplication (P1)

- Relative time formatters: `relativeTime` (FlowListScreen.kt:1881, "min ago") and `formatRelativeTime`
  (OverviewTabs.kt:75, "m ago", adds "mo"). Same screen family, different strings. Move one into `core:design`.
- 21 `AlertDialog(` calls across 4 files with no shared `KaivoDialog` (CanvasOverlays.kt 7, FlowListScreen.kt 6,
  OverviewTabs.kt 5, FlowTransfer.kt 3). 33 `OutlinedTextField(` calls with 16 separate hand-written
  `OutlinedTextFieldDefaults.colors(...)` blocks; `core:design` has no `KaivoTextField`.
  `core:design/Components.kt` offers only 10 components and one of them (`KaivoSurfaceCard`) is unused.
- The four `Create*Dialog` composables (workflow group, variable, credential, data table; FlowListScreen.kt:783,
  :1357, OverviewTabs.kt:372, :783) share the same title/field/confirm/cancel shape.
- Proposal: `KaivoDialog(title, confirmLabel, onConfirm, content)` and `KaivoTextField(...)` in `core:design`.
  Effort M. Risk low (visual regression only, no tests exist to catch it, see section 6).

### D8. UI model mirrors domain model, then is converted back (P1)

`CanvasViewModel.syncState` (CanvasViewModel.kt:781-842) copies `Flow` into `StepNodeUiModel`,
`StreamEdgeUiModel`, a `positions` map and a `paramCache` map. `CanvasScreen` then rebuilds a throwaway `Flow`
from that state, in an object named `FakeFlow` (CanvasScreen.kt:129, :871), just to call `CanvasGeometry`.
The same data now exists in three shapes (Flow, UI model, positions/paramCache) and must be kept in sync by hand.
Proposal: give `CanvasGeometry` a small input type (`List<LayoutNode>`, `List<LayoutEdge>`) or let it read
`Flow` directly; keep `Flow` plus a derived `StepStatus` map in state instead of mirrored models. Effort M-L.
Risk medium (touches the most delicate Compose code, and only CanvasGeometry has tests).

### D9. Node catalog instantiated five times (P2)

`NodeCatalog.registry()` builds 87 executors each call. Called at CanvasViewModel.kt:68, CanvasScreen.kt:115,
FlowTransfer.kt:79, FlowTransfer.kt:141, and FlowEngine.kt:66. The four UI call sites also use the
default `NoopDeviceGateway`, which silently does nothing. Provide `NodeRegistry` once through Hilt.
Effort S. Risk low.

## 3. Over-engineering and roundabout logic

### O1. "QuickJS" evaluator that is not QuickJS (P0)

`QuickJsExpressionEvaluator` (core/expressions/.../QuickJsExpressionEvaluator.kt:1-27) is a 27-line class that
delegates every call to `FallbackExpressionEvaluator`. Its constructor takes `timeoutMs` and `prelude`, both
unused; its `TAG` constant is unused; `close()` is a no-op. `FlowEngine.kt:60-63` wraps it in
`runCatching { QuickJsExpressionEvaluator() }.getOrElse { FallbackExpressionEvaluator() }`, a try/catch around a
constructor that cannot fail, with the comment "QuickJS if it loads". The catalog still declares `quickjs-kt`
(gradle/libs.versions.toml:16,53) but no module depends on it, and app/proguard-rules.pro:37-41 keeps rules for a
library that is not present. `FallbackExpressionEvaluator.kt:22` documents the truth: "Anything richer ... requires
the JS engine and raises an actionable error".

Consequence, verified by execution against the real class:

```
$json.a            => 2
$json.a * 2        => throws ExpressionUnsupportedException
$json.name.toUpperCase() => null          (silently wrong, no error)
(function(){ return $items.map(i => i.a*2) })() => throws
$json.a > 1 ? 'y':'n'    => throws
"Total {{ $json.a + 1 }}" => throws
```

The Code node (CodeNode.kt:16-81) wraps user script in `(function(){...})()` and sends it to this evaluator, so the
Code node cannot work in the shipped app. Its only test (NodeExecutorTest.kt:578-591) injects a stub evaluator that
returns a canned value regardless of the script, so the test proves nothing about running code.
Fix options: (a) integrate a real engine, or (b) write a real small expression parser (the PROGRESS.md roadmap already
plans this in phase 2), and remove the Code node until it works. Either way delete `QuickJsExpressionEvaluator`.
Effort L for a real parser; S to delete the wrapper and hide the Code node. Risk: high for the parser, none for removal.

### O2. Double parameter resolution (P0, F1)

`ctx.params()` (StepExecutor.kt:46) resolves all params against `scope()` of item 0. Nodes then loop over items and call
`resolver.resolveValue(params["text"], scopeForItem)` on the already-resolved value, which no longer contains `{{ }}`.
Sites (calling `ctx.params()` then re-resolving):
HttpNode.kt:87,132; DeviceNodes.kt:111-116, 144-150, 170, 191-193, 215-217, 268-277; AiNodes.kt:250-257, 289-297,
347-357; SaasNodes.kt (Telegram, Slack, Discord, Sheets, Notion, Weather: 7 re-resolves); TransformNodes.kt:254-277,
528-542; BinaryNodes.kt (3). Verified by execution (3 items named alice, bob, carol, text "hi {{ $json.name }}"):

```
Slack texts:    [hi alice, hi alice, hi alice]
Discord texts:  [hi alice, hi alice, hi alice]
Telegram texts: [hi alice, hi alice, hi alice]
```

Not caught by tests: 7 multi-item tests exist in NodeExecutorTest and none exercise per-item expressions on these nodes.
Per-item output with the Extended* nodes is correct (they use `ctx.resolveText(raw, index)` against raw `step.params`).

### O3. Secrets are expression-expanded (P0, F2)

`ParamResolver.resolveParams` (core/engine/.../Expression.kt:~77) iterates all `step.params` with no regard to
`ParamType.Secret`. `ctx.params()["webhookUrl"]` in SlackNode, DiscordNode and others therefore expands `{{ }}`.
Verified: webhook `https://x/{{ $json.name }}` was sent to `https://x/alice`. ARCHITECTURE.md section 3 and section 9
claim "Secret params are read raw and are never expression-expanded". This is true only for the nodes that call
`ctx.secret(...)` (NodeDsl.kt:34). Fix: resolve per key using the spec's `supportsExpression` and `ParamType.Secret`
inside the engine so no node can get it wrong. Effort S. Risk low.

### O4. Single-implementation interface layers (P2)

Six repository interfaces each have exactly one Room implementation (Repositories.kt:37/51, 154/162, 198/206, 277/289,
459/465, 501/509) plus three hand-written Hilt `@Provides` for each. The tests fake the DAO, not the repository, so the
interface is not the test seam. Not harmful, but it is 6 files-worth of ceremony in a 1-user app. Keep `FlowRepository`
and `RunRepository` (used by `FlowEngine`, which benefits from a seam), collapse the rest to concrete classes with
`@Inject constructor`. Effort S. Risk low. Priority low.

`DeviceGateway` (nodes/DeviceNodes.kt:23) plus `NoopDeviceGateway` plus `AndroidDeviceGateway` is a justified seam.

### O5. Stringly-typed everything (P1)

- `StepNode.type` is a `String`; 32 string literals like `"saas.weather"` or `"device.notify"` are spread through
  feature, work, data and model code (only trigger ids have constants in `TriggerSpecs`, TriggerNodes.kt:27; there are
  22 more `"trigger.*"` literals outside it).
- `Map<String, Any?>` appears 132 times and `Any?` 226 times in main code. Params, `FlowData.json`, scope and
  vars all travel as untyped maps; nodes read them with `Values.asX(params["key"])` (345 `Values.as*` calls in core/nodes).
- Option values are strings compared inline: `frequency == "hourly"` (FlowScheduler.kt:79), `mode == "each"`
  (CodeNode.kt:62), provider `"openai"`/`"gemini"`/`"ollama"` (AiNodes.kt:41-56).
- 270 `ParamSpec(` constructions in core/nodes declare keys as string literals that are repeated again in each executor
  (`params["text"]`), so a typo in one place is found only at runtime.
Proposal: a `StepTypes` constants object (S), then per-node typed config classes decoded from the param map once (L,
incremental, per node family).

### O6. JSON round-trip and eager decode (P1)

- `JsonCodec.encodeFlow` and `decodeFlow` are hand-written (JsonCodec.kt:48-226) while the `kotlin.serialization`
  Gradle plugin is applied in three modules (canvas, data, network) and no `@Serializable` class exists. Pure plugin
  weight with no use. Either adopt `@Serializable` DTOs or drop the plugin from canvas and network.
- `RoomFlowRepository.observeSummaries` (Repositories.kt:57-62) fully decodes every flow's JSON on each database
  change just to read `isActive`, inside a `map` on the collector's dispatcher. Store `isActive` as a column.
- `RoomFlowRepository.save` writes a second copy of every flow to `filesDir/flows/<id>.json` (Repositories.kt:105-111)
  inside a swallow-all `runCatching`. Nothing in the repo reads that mirror back. `AndroidDeviceGateway.workflowDir()`
  (AndroidDeviceGateway.kt:421) uses the same `filesDir/flows` directory for the user-facing File node, so user files
  and flow mirrors share a folder and `delete(id)` can remove a user file named `<id>.json`.

### O7. Wrappers and flags that add nothing (P2)

- `AndroidDeviceGateway.kt:89`: `runCatching { slot }.getOrDefault(0)`, a catch around reading a local variable.
- `FlowEngine.kt:60-63`, as above.
- `ExpressionEvaluator` interface with `interpolate/interpolateAll` default methods that call a regex per evaluation
  (Expression.kt:33-70); regex compiled in a companion so fine, but `ScopeBuilder` is rebuilt on every `ctx.scope(i)`
  call and does `stepOutputs.mapValues {...}` copying all prior outputs for every item and every param
  (Expression.kt:~90-110). Per-item cost is O(items x steps x outputs). Build the scope once per Run step, update the
  item pointer per item.

### O8. Giant files and god classes

Files over 500 lines (12), with split guidance:

| Lines | File | Split into |
|---|---|---|
| 1889 | feature/flows/.../FlowListScreen.kt | `FlowListViewModel` (lines 109-427) to its own file; `FlowListScreen` (444-745); `FlowRowCard` (1581-1848, 270 lines) own file; Variables UI (888-1456: GroupVariablesDialog, VariablesDashboardView, VariableRowItem, CreateVariableDialog) to `VariablesUi.kt`; group dialogs (783-887, 1457-1518) to `GroupDialogs.kt` |
| 1239 | feature/canvas/.../sheets/StepInspectorSheet.kt | `ParamField` (442-657, 215 lines) to `ParamFields.kt`; data viewer (826-1212: NodeDataViewer, TableView, JsonView, SchemaView) to `NodeDataViewer.kt`; `ExpressionPickerDialog` (658-822) own file; keep `StepInspectorSheet`+`ConfigureTab`+`OutputTab` |
| 1191 | feature/flows/.../OverviewTabs.kt | `CredentialsUi.kt` (101-541), `DataTablesUi.kt` (542-1190); the file mixes two unrelated screens |
| 965 | feature/canvas/.../CanvasViewModel.kt | see god-class note below |
| 950 | feature/canvas/.../CanvasScreen.kt | hit-testing helpers (findOutputPort, findStepCard, findStickyNote, ~line 880-950) to `CanvasHitTest.kt`; `FakeFlow` disappears with D8 |
| 788 | feature/canvas/.../CanvasOverlays.kt | seven dialogs, group by purpose; delete dead `Minimap`, `EmptyCanvasHint`, `CreateGroupDialog` first (section 4) |
| 668 | core/engine/.../FlowRunner.kt | acceptable; extract `Execution.runWithRetries` (472-587) into a `StepRetryPolicy` function and `runErrorStep` (616-668); not urgent |
| 626 | core/nodes/.../TextFormatNodes.kt | one file per format: `CsvNode+CsvLite`, `XmlNode+XmlLite` (XmlLite alone is 160 lines), `HtmlNode+HtmlLite`, `MarkdownNode+MarkdownLite`, `IcalNode` |
| 591 | core/nodes/.../test/NodeExecutorTest.kt | one test class per node family, mirror source layout |
| 568 | core/nodes/.../TransformNodes.kt | `JsonLite` goes away (D1); `DateNode` (207-330) own file; `CryptoNode` is mis-filed here and again in SecurityNodes |
| 567 | core/data/.../Repositories.kt | one file per repository; `RoomOAuth2TokenStore` to `OAuth2Store.kt` |
| 566 | core/nodes/.../ExtendedLogicNodes.kt | "Extended" is a history marker not a domain. Move Sort/Limit/Compare/Table/RunInfo/Noop to `LogicNodes`, Chart+ChartSvg to `ChartNode.kt`, Random to `TransformNodes`. Same for ExtendedTransformNodes and ExtendedDeviceNodes. |

God classes:

- `CanvasViewModel` (965 lines, 63 public functions): graph editing, undo/redo, run orchestration, step test,
  sticky notes, groups, dialog open/close flags (12 trivial open/close pairs, lines 174-220, 280-312, 622-680),
  persistence debounce, validation, toast queue, UI-model projection, and the unused `PromptToFlow` (line 879).
  Split: `GraphEditor` (pure, testable, owns Flow + undo stack), `RunController` (run/test/freeze/events),
  `CanvasDialogState` (dialog flags, can live in the composable via `rememberSaveable`), and
  `NotesAndGroups`. `CanvasUiState` currently has 36 fields including 6 dialog booleans and 4 selection ids.
- `FlowListScreen.kt`: one file holds the ViewModel, 17 composables, color mapping and time formatting.
- `StepInspectorSheet.kt`: configuration form, expression picker, three data views and preview evaluator
  (whose `evaluatePreview` at line 386 constructs a new `FallbackExpressionEvaluator` per keystroke, so the preview
  badge can only ever show path lookups, see O1).
- `FlowRunner`: size is fine; it already delegates to `ParamResolver`, `FlowValidator`, `NodeRegistry`. Not a priority.

## 4. Dead and fake code

### 4.1 Dead code (verified by whole-repo identifier search, tests included)

Unreferenced composables and classes: `Minimap` (CanvasOverlays.kt:154, docs promise a "glass minimap"),
`EmptyCanvasHint` (CanvasOverlays.kt:494), `CreateGroupDialog` (CanvasOverlays.kt:720), `GroupFilterChip`
(FlowListScreen.kt:745), `StepCardSkeleton` (JewelStepCard.kt:329), `KaivoSurfaceCard` (Components.kt:273),
`rememberFitToScreen` (CanvasTransform.kt:107), `debugDrawFrame` (StreamCanvasLayer.kt:334),
`PromptToFlow` (CanvasViewModel.kt:879, a 85-line keyword matcher with a hard-coded city, never called),
`ParamSpecList` (LogicNodes.kt:344), `LiveStreamPulse` (CanvasUiState.kt:64), `StreamFlowing` (Run.kt:62).

Unreferenced functions: `CanvasViewModel.duplicateStep` (242), `unfreezeOutput` (383), `selectStep` (194), `setVar` (365),
`toggleLayoutMode` (343, so layout mode only changes implicitly when a card is dragged), `FlowListViewModel.filteredFlows`
(474) `selectGroup` (212) `openVariablesDialog` (224) `openMoveFlowDialog` (240), `FlowScheduler.describeSchedule` (229),
`ScheduleMath.nextOccurrence` (91; only its own test calls it), `StepContext.stringParam` (57), `requireParam` (59),
`outputOf` (75), `NodeRegistry.requireExecutor` (23), `ParamResolver.resolveString` (Expression.kt:74),
`NodeDsl.resolveBoolean` (25), `CryptoMath.hmacHex` (25), `base32Encode` (70), `MiniJson.stringMap` (21),
`withExtension` (BinarySupport.kt:65), `Flow.stepByName` (33), `hasBinary`/`plusBinary` (Flow.kt:256/264),
`StepExecutionException.forStep` (Errors.kt:42), `isInteractive` (StepExecutor.kt:21), `needsAttention`/`withRunStatus`
(CanvasUiState.kt:193/200), `topCenter` (CanvasGeometry.kt:44).
Unused design tokens in KaivoTokens.kt: `xxs..xxl`, `lg`, `snapGrid` (docs claim 16 dp snap-to-grid, nothing uses it),
`guidedStepGap`, `portDot`, `streamStroke`, `liveOrb`, `springSnappy`, `springLuxurious`, `KaivoEaseFlowing`, four
font-weight helpers, `lineHeightStyle`, plus `KaivoTheme.radii`.

Unreferenced dependencies and plugins: `quickjs-kt` (libs.versions.toml:16,53; plus Proguard rules), the
kotlin.serialization plugin in `core:network` and `feature:canvas`, `kotlinx.serialization.json` in `core:expressions`,
`implementation(project(":core:nodes"))` in `core:data` (zero imports), `feature:flows -> feature:canvas` (zero imports),
`feature:settings -> feature:permissions` (zero imports), `feature:permissions -> core:data` and `core:model` (zero imports),
`feature:flows -> core:engine` (zero imports).

### 4.2 Features that exist on paper but do not run end-to-end

- Webhook Spark: `WebhookServer` is an `@Inject` singleton, not an Android `Service`. Nothing in the code calls
  `start()` or `refreshRoutes()` (whole-repo grep: no references outside the class). The manifest declares it as a
  `<service android:name="com.kaivoflow.core.work.WebhookServer" android:foregroundServiceType="dataSync">`
  (AndroidManifest.xml:152). The class does not extend `Service`, so that manifest entry is invalid; starting it would
  throw. Treat the incoming-webhook trigger as not shipped.
- Code node and every expression beyond path lookup (O1).
- Minimap and snap-to-grid (listed above).
- Geofence uses `LocationManager.addProximityAlert` (FlowScheduler.kt:154-171) with failures swallowed by `runCatching`;
  reliability is for the storage/triggers lane, noted here only because of the swallow.

### 4.3 Mock or placeholder data on production paths

- `CanvasScreen.kt:871` `FakeFlow`: not mock data, but a misleading name for a real adapter. Rename or delete (D8).
- `NoopDeviceGateway` is the default for `NodeCatalog.registry()` / `executors()`. The four UI call sites use the default.
  Today those registries are only used for specs, but any future call of `.execute` through them would report success
  while doing nothing (sendSms, copyToClipboard, vibrate all return `Unit`, DeviceNodes.kt:68-77). Make `devices` a
  required parameter.
- Hard-coded owner locale: default timezone `"Asia/Kolkata"` (FlowScheduler.kt:70) and city `"Bhubaneswar"`
  (PromptToFlow, a template, NodeCatalogTest). Seven `"UTC"` literals in core. Use `ZoneId.systemDefault()`.
- `CalendarEventNode` hard-codes `CALENDAR_ID = 1L` (AndroidDeviceGateway.kt:~412) and catches every exception to `null`,
  so on most devices the calendar event silently is not created. 
- `AI` templates in `PromptToFlow` hard-code `gpt-4o-mini`.

### 4.4 Swallowed exceptions, unsafe casts, `!!`

- `catch` blocks: 35 in main code. The engine ones are intentional and rethrow `CancellationException` first.
  Generic `catch (e: Exception)` that returns a value: AndroidDeviceGateway.kt:359 (Wi-Fi), :392 (contacts), :416 (calendar,
  returns null, message lost); NativeFlowBundle.kt:121, 186 and FlowStructureBundle.kt:54, 56 (rethrown as
  `InvalidBundle`, acceptable); CompositionNodes.kt:345 (after CancellationException, acceptable).
  `FlowTransfer.kt:98-166` repeats an identical 2-line `catch (CancellationException) { throw e } catch (Exception) { showError }`
  four times; extract `runCatchingUi`.
- `runCatching` appears 100 times in main code. 33 turn failures into `getOrNull()` or `getOrDefault()` with no logging.
  Notable: `FlowEngine.kt:69` (`resolveVariables(...)` failure silently drops all group variables, so a run proceeds with
  missing `$vars`), `Repositories.kt:249` (decrypt failure is indistinguishable from "no credential"),
  `Repositories.kt:404/417` (invalid JSON in data tables becomes empty), `RoomFlowRepository.save` mirror write (O6),
  `JsonCodec.kt:171/266/276` (unknown enum values silently become `Stop`/`Failed`/`Skipped`, hiding data corruption).
- `!!`: 2 occurrences. ExtendedLogicNodes.kt:186 (`old!!`/`new!!` inside a branch that proves non-null, replace with a
  smart-cast) and CanvasViewModel.kt:727 (`selectedStepId!!` after a null check on a mutable `StateFlow` snapshot, race-prone,
  capture the value first). Good discipline overall.
- Unchecked/hard casts: 10. `Values.deepMerge(...) as Map<String, Any?>` at AiNodes.kt:318 and LogicNodes.kt:125,128,138 (four
  copies; `deepMerge` should return `Map<String, Any?>` for map inputs), and NativeFlowBundle.kt:82,218. 2 `@Suppress("UNCHECKED_CAST")`.
- Engine `catch (e: Throwable)` (FlowRunner.kt:206, 541, 653) also catches `OutOfMemoryError`/`LinkageError` and keeps going.
  Prefer `Exception`.

## 5. Module boundary check

Declared in settings.gradle.kts: "Core layers (Android-free where possible)". Actual state from the build files and imports:

| Module | Plugin | Android-free? | Verdict |
|---|---|---|---|
| core:model | kotlin.jvm | yes; no `android.*` or `androidx.*` imports | true to claim |
| core:engine | kotlin.jvm | yes | true to claim |
| core:nodes | kotlin.jvm | yes; DeviceGateway is the seam | true to claim |
| core:expressions | android.library | contains zero Android code | wrong plugin: should be kotlin.jvm. It pulls AGP, `compileSdk`, and `coroutines-android` for nothing, and it means its 9 tests cannot run on the plain JVM in Gradle without the SDK |
| core:design | android.library + Compose | no (expected) | OK, but depends `api(project(":core:model"))` for a single import (`StepCategory` in KaivoColors.kt:6) |
| core:data | android.library | no (Room, Keystore, EncryptedSharedPreferences) | contains pure code too: `JsonCodec`, `FlowStructureBundle`, `NativeFlowBundle` have no Android imports and are already tested outside Gradle by scripts/run-transfer-tests.sh. Split a pure `core:codec` (or move into `core:model`) so its 33 tests run without the SDK. |
| core:network | android.library | yes, uses no Android API | should be kotlin.jvm (Ktor OkHttp engine is plain JVM); `NetworkModule` Hilt bindings could live in `app` |
| core:work | android.library | no (WorkManager, receivers) | OK, but it is also where non-Android logic lives: `FlowEngine` (wiring plus sub-flow invoker), `ScheduleMath` consumers, `EventTriggerMatcher` use |

Dependency direction problems:

1. `core:expressions` has `api(project(":core:engine"))` and the Android plugin; `core:engine` defines the
   `ExpressionEvaluator` interface. That is the right direction. Fine.
2. `core:data -> core:nodes` (`implementation`): zero imports. Remove. It makes persistence rebuild whenever a node changes.
3. `feature:*` depend directly on `core:data` repositories (13 import sites) and on `core:work` (`FlowScheduler`, `FlowEngine`,
   `BatteryHealth`) and `core:nodes` (`NodeCatalog`, `TriggerSpecs`). There is no domain/use-case layer; ViewModels talk to Room
   repositories directly. For a single-user app this is acceptable, but then the claim "thin platform adapters" in
   ARCHITECTURE.md is not what exists: `feature:canvas` (6,353 lines) contains business rules (cycle rejection in
   `connectStream`, undo stack, validation refresh, run orchestration).
4. `feature:flows -> feature:canvas` and `feature:settings -> feature:permissions`: declared, never used. Feature-to-feature
   edges are wrong direction in principle; both are dead and should be deleted. `app` depends on 12 of 12 other modules
   (needed for Hilt aggregation, fine).
5. `core:work` depends on `core:network` and `core:expressions` only to construct `KtorHttpGateway` and the evaluator
   inside `FlowEngine`. Better: `app` provides `HttpGateway` and `ExpressionEvaluator` bindings; `core:work` depends only
   on interfaces. Removes two edges.
6. `feature:canvas` imports `FallbackExpressionEvaluator` from `core:expressions` directly (StepInspectorSheet.kt:54, :397)
   to preview expressions: the preview uses a separate evaluator instance from the runtime one. Inject the same one.

Toolchain inconsistency: `core:model/engine/nodes` declare `java { toolchain { languageVersion.set(17) } }` and
`kotlin { jvmToolchain(21) }` in the same file (core/model/build.gradle.kts:4-10, engine :4-5, nodes :4-5), which asks for two
different JDKs. Android modules use `JavaVersion.VERSION_21`. README says "Java 21 (or Java 17)": with these files, 17
alone cannot build. Pick 17 everywhere (matches AGP 8.3.2 and Android runtime desugaring).

Build hygiene red flags in build.gradle.kts (root):
- Lines 26-30 disable `CheckAarMetadataTask` for every subproject and line 20 adds `-Xskip-metadata-version-check`. These switch off
  the two checks that would tell you that your dependency versions (Compose BOM 2024.10, activity 1.9.3, lifecycle 2.8.6, navigation 2.8.3)
  require a newer `compileSdk` or Kotlin than the build uses (`compileSdk = 34` everywhere). Remove both, then raise `compileSdk` to 35,
  which is what the README prerequisites already say ("34/35").
- `gradle.properties` turns on `org.gradle.configuration-cache=true` together with `android.nonFinalResIds` and a task-disabling `subprojects` hook; verify the
  config cache is not rejecting silently once SDK-based builds run.
- `app/build.gradle.kts` has `isMinifyEnabled = true` for release with Proguard rules for libraries not on the classpath (quickjs).
- `exportSchema = false` and `fallbackToDestructiveMigration()` at schema `version = 3` (Database.kt:247-248, DataModule.kt:22): any
  future schema bump will silently delete all flows, runs, tables and credentials on app update. For a local-only app whose flows are
  the user's data, this is data loss waiting for the next release. Treat as P0 before any schema change ships.

## 6. Test and build status

### 6.1 Can the build run here?

No for Gradle, yes for a manual JVM compile.
- `./gradlew` wants Gradle 8.5 from services.gradle.org; the sandbox proxy returns 403 (checked with curl). No `~/.gradle/wrapper`.
- System Gradle is 8.14.3 (`gradle --version` works) but plugin resolution (AGP, Kotlin, KSP, Hilt) and all libraries need
  repo.maven.apache.org and dl.google.com; both are blocked (403). No Android SDK present (`ANDROID_HOME`, `local.properties` absent).
- `scripts/*.sh` require `KOTLINC_HOME` and `TEST_DEPS_DIR`; neither exists and there is no `kotlinc` binary. They cannot run as shipped.

Workaround used (all in the session scratchpad, nothing in the repo changed): run the Kotlin 2.0.21 compiler jar that ships inside
Gradle's own `lib/` directory (`kotlin-compiler-embeddable`, `kotlin-stdlib`, JUnit 4.13.2, coroutines 1.6.4, serialization 1.6.2),
plus a 10-line scratch shim that supplies `ImmutableList`, `ImmutableMap`, `persistentListOf`, `persistentMapOf`, `toImmutableList`,
`toImmutableMap` (the real `kotlinx-collections-immutable` jar is not available offline).

Results:
- core:model + core:engine + core:nodes (15 test classes): compile clean, **154 tests, 0 failures** (1.2 s).
- core:expressions + CanvasGeometry + JsonCodec/FlowStructureBundle/NativeFlowBundle tests: **50 tests, 0 failures**.
- Not runnable without Room/Android classes: `WorkflowVariableScopingTest` (3), `RoomFlowRepositoryTest` (1), `DataTableRepositoryTest` (1).
- Total: 204 of 209 `@Test` functions executed and passed. No compile errors or warnings-as-errors in the pure modules, which
  is a good sign for the Android side compiling as well, but nothing in `feature:*`, `core:work`, `core:network`, `core:design` or `app`
  was compiled. I cannot claim the APK builds.

### 6.2 What the scripts do and do not do

- Hard-coded jar versions do not match the Gradle catalog: scripts use coroutines 1.10.2 and serialization 1.8.1; `libs.versions.toml` pins
  coroutines 1.8.1 and serialization 1.7.3. A passing script run is therefore not evidence about the Gradle build.
- `-jvm-target 11` in all three scripts while modules target 17/21.
- Test class lists are hand-maintained: `run-core-reliability-tests.sh` omits `ScheduleMathTest` (13 tests). The
  other two scripts do not cover `FallbackExpressionEvaluatorTest` (9 tests). Replace the three scripts with `./gradlew test` for
  the JVM modules once the build runs; keep one script only if the SDK-free path is wanted for CI. In that case make
  `core:expressions`, `core:network` and a pure `core:codec` real `kotlin.jvm` modules so a plain `gradle test` needs no SDK for them.

### 6.3 Coverage map

| Area | Lines (main) | Lines (test) | Tests | Assessment |
|---|---|---|---|---|
| core:engine | 1,745 | 968 | 60 | good: runner, validator, retries, OAuth, registry |
| core:nodes | 6,873 | 1,748 | 78 | wide but shallow; see below |
| core:model | 932 | 154 | 14 | ScheduleMath well covered |
| core:data | 1,817 | 812 | 41 | codec and bundles well covered; repositories nearly not |
| core:expressions | 254 | 97 | 9 | tests pass only because they assert errors for unsupported input |
| feature:canvas | 6,353 | 177 | 13 | only `CanvasGeometry`; ViewModel, screen, inspector: zero |
| feature:flows | 3,514 | 0 | 0 | none |
| feature:settings, feature:permissions | 852 | 0 | 0 | none |
| core:work | 1,777 | 0 | 0 | none (scheduler, webhook server, receivers, device gateway) |
| core:network | 265 | 0 | 0 | none (the real HTTP gateway) |
| core:design, app | 1,227 | 0 | 0 | none |

Overall: 3,956 test lines against 29,565 total (13 percent). About 55 percent of main code (everything except model, engine, nodes, expressions and the data codec) has no tests: 13,988 of 25,609 main lines.

Node coverage: about 28 of 87 executors have no test that names the class or its spec id (checked against all test sources):
ai.classify, device.battery_status, transform.binary_upload, device.calendar_event, device.clipboard, device.clipboard_read, device.contact,
transform.crypto, saas.discord, transform.ical, device.location, device.network_status, saas.notion, device.open, transform.random,
device.share, saas.slack, device.speak, transform.split, transform.stats, logic.stop, saas.tcp, device.toast, transform.url,
device.vibrate, device.volume, logic.wait, device.wifi_info, plus several triggers that are only touched via the matcher.
`NodeCatalogTest` verifies metadata invariants (ids unique, titles, icons, required defaults), which is valuable and honest.

Quality of the tests: names are descriptive (median 45 characters, "scheduling a one-shot at midnight rolls over the month" style), not padding.
Two weaknesses:
1. Stub evaluators hide real behaviour: `TestEvaluator` (NodeTestSupport.kt:17) and `StubEvaluator` (engine TestSupport.kt:17) resolve
   paths only; `code node returns whatever the script produces` (NodeExecutorTest.kt:578) injects a lambda that ignores the script. No test uses
   `FallbackExpressionEvaluator` with a node, which is why F1 and F2 are invisible. `FallbackExpressionEvaluatorTest` has 9 tests and one,
   `js-only expressions raise a clear error`, locks in the broken behaviour.
2. Single-item inputs: only 7 multi-item node tests in NodeExecutorTest, none for per-item expressions.

Add first (cheap, high value): a parameterised "per-item expression" test over every node that calls `ctx.params()`; a test that a Secret param
is not expanded; a real-evaluator Run through `FlowRunner` for `{{ $json.a + 1 }}`; a test that wires `WebhookServer.start()` and POSTs to it
(will fail today, which is the point).

## 7. Docs vs reality

docs/ARCHITECTURE.md exists. docs/PROGRESS.md exists (not in the README). PARITY.md does not exist.

Files linked but missing (none exist anywhere in the repo): README.md links `PARITY.md` (lines for architecture, handoff, parity),
`HANDOFF.md`, `DEVELOPER_HANDOFF.md`, `BACKLOG.md`, `docs/CAPABILITY_GAP.md`; ARCHITECTURE.md links `PARITY.md` and `HANDOFF.md` (sections 6 and 9).

Claims the code does not back up:

| # | Claim (source) | Reality |
|---|---|---|
| 1 | "Sandboxed JavaScript" (README transform list); Code node "runs inside the same sandbox as expressions ... time-boxed by the evaluator" (CodeNode.kt:15-24) | No JS engine in any module; evaluator cannot run a function or an operator (O1). No time-box exists |
| 2 | "Pure-Kotlin sandboxed fallback ... arithmetic, `$lower/$upper/$trim`" (ARCHITECTURE.md section 5) | Arithmetic throws; `$lower`, `$upper`, `$trim` do not exist in FallbackExpressionEvaluator (only step/count/length/first/last/all/now/today) |
| 3 | "Expression runtime ... `{{ $now }}`, arithmetic, string helpers" (README modules table) | Same as above |
| 4 | QuickJS used where available (FlowEngine.kt:61 comment; Proguard rules; libs.versions) | Not a dependency; wrapper over the fallback |
| 5 | "Secret params are read raw and are never expression-expanded" (ARCHITECTURE.md sections 3, 9) | False for every node that uses `ctx.params()` (O3) |
| 6 | "Exponential backoff with jitter up to `maxDelayMs`" (ARCHITECTURE.md section 4) | `RetryPolicy.delayFor` (Flow.kt:310) is deterministic exponential, no jitter |
| 7 | "`maxConcurrency = 4`" (ARCHITECTURE.md section 4) | Default is 3 (FlowRunner.kt:34) |
| 8 | "Five original phone-first templates" (README, ARCHITECTURE.md) | Matches: five templates (FlowTemplates.kt:22-93). Not a discrepancy; listed so nobody re-checks it |
| 9 | Webhook Spark: "local webhooks" (README Sparks) | Server never started, manifest entry is not a Service (section 4.2) |
| 10 | "Freeform Atelier ... snap-to-grid (16 dp) ... glass minimap" (ARCHITECTURE.md section 8) | Snap-grid token unused; `Minimap` composable unreferenced; no layout-toggle UI |
| 11 | "`core:design` ... Jewel Cards, Canvas layout math" (ARCHITECTURE.md section 2) | `JewelStepCard` and `CanvasGeometry` live in `feature:canvas`; `core:design` has tokens, theme, icons and 10 components |
| 12 | "Android-free where possible ... core is Android-free" (README, AGENTS.md) | True for model/engine/nodes; `core:expressions`, `core:network` and the codec in `core:data` are Android-free code in Android modules (section 5) |
| 13 | "Java 21 (or Java 17)", "Gradle 8.5+" (README) | Build files request JDK 17 and JDK 21 simultaneously; Java 17 alone fails the Kotlin `jvmToolchain(21)` request |
| 14 | "13 modular Gradle components" | True (app + 8 core + 4 feature) |
| 15 | "87 original executors" | True numerically (`NodeCatalog.executors` lists 87; per-category counts 12/17/25/3/12/18 match). "Original" and "working" are different claims: see 1, 2, 9 and the roughly 28 untested executors. 12 of the 87 are Sparks whose pass-through logic is shared (`PassThroughTrigger`) |
| 16 | "Sovereign, serverless, on-device" file mirror and "hardware-backed vault" | Vault uses AndroidKeyStore AES/GCM (CryptoBox.kt:62-63): backed. Flow file mirror is write-only (O6) |
| 17 | "Verification status: static checks only" (README) | Honest. Matches what this audit found |

Claims that check out: 87 executors; module count; provider list (OpenAI-compatible, Gemini, Ollama in AiNodes.kt:23-56); OAuth2
refresh and client-credentials grants (OAuth2TokenManager.kt:31, 53); AES-GCM vault; Boot/timezone/time-change receivers
(BootReceiver.kt:28-32); `allowBackup=false` plus data-extraction rules; engine port liveness, retries, freeze output.

## 8. Refactor plan, ordered by value over risk

Effort: S under half a day, M 1-3 days, L over 3 days. Risk is regression risk.

| # | Item | Effort | Risk | Why now |
|---|---|---|---|---|
| 1 | Fix F1/F2: resolve params per item and never expand Secret params, in the engine (`ParamResolver` uses `ParamSpec`); convert the 29 sites to `ctx.text(key, i)` | M | Low-med | Real data bugs in the most-used nodes; also deletes duplication D2 |
| 2 | Add per-item and secret tests with the real `FallbackExpressionEvaluator` (section 6.3) | S | None | Locks #1 in; today the tests cannot see it |
| 3 | Hide or disable Code node until it works; delete `QuickJsExpressionEvaluator`; fix docs claims 1-4 | S | None | Stops shipping a node that always fails, and a misleading class name |
| 4 | Delete dead code (section 4.1): ~12 composables/classes, ~30 functions, unused tokens, unused deps and plugins (quickjs, serialization plugin x2, 7 unused `project()` edges) | S | Very low | About 700-900 lines gone, faster builds, honest module graph |
| 5 | Replace `fallbackToDestructiveMigration` with real migrations and `exportSchema = true` before the next schema bump | S | Low | Prevents silent data loss on update |
| 6 | Wire or remove `WebhookServer` (real foreground `Service`, call `refreshRoutes` on flow save, add test) | M | Medium | A documented Spark is non-functional |
| 7 | Single `JsonValues` in `core:engine` (D1); remove JsonLite/MiniJson and 3 `fromJson` copies | M | Low-med | One behaviour, fixes null-vs-invalid ambiguity and Long precision |
| 8 | NodeDsl helpers `ctx.fail`, `ctx.postJson`, `orFail`; `WebhookPostNode` base; `Escapes`, `DateText` (D3-D6) | M | Low | Cuts roughly 400-600 lines from core/nodes |
| 9 | Split `core:expressions`, `core:network`, and pure codec to `kotlin.jvm`; pin one JDK (17); remove the two build-check disables and raise `compileSdk` to 35 | M | Medium (surfaces latent version conflicts) | Makes JVM tests runnable in CI without the SDK; surfaces real dependency drift |
| 10 | Provide `NodeRegistry`, `ExpressionEvaluator`, `HttpGateway` through Hilt once; make `DeviceGateway` a required parameter; drop `core:work -> network/expressions` edges (D9, 4.3, section 5) | S-M | Low | Removes the Noop-gateway footgun |
| 11 | Move `isActive` into `FlowEntity`; drop file mirror and the shared `flows` folder; build scope once per step (O6, O7) | M | Low-med | Perf on list screen, removes second source of truth |
| 12 | Split giant UI files (O8 table) mechanically; no behavior change | M | Very low | Reviewability; do after #4 so dead code is not split too |
| 13 | `KaivoDialog`, `KaivoTextField`, one relative-time formatter in `core:design` (D7) | M | Low | Removes 21+33 repeated blocks; no test net, so do with screenshots |
| 14 | Break up `CanvasViewModel` (graph editor / run controller / dialog state), remove UI-model mirror and `FakeFlow` (D8) | L | Medium-high | Largest design debt; needs ViewModel tests written first |
| 15 | Typed step ids and typed per-node config (O5) | L | Medium | Incremental; start with `StepTypes` constants (S) |
| 16 | Real expression parser (arithmetic, comparisons, ternary, string/array helpers, no eval) with time/size limits; re-enable Code node via a real sandbox if wanted | L | High | Core capability promised by docs; already planned as phase 2 |
| 17 | Add tests for `core:work`, `core:network`, `feature:flows` ViewModel, and the roughly 28 untested executors | L | None | Coverage |

Quick wins (all S): #2, #3, #4, #5, `ctx.fail`, delete 7 unused project deps, fix 9 doc claims, remove stray `catch` around a local read
(AndroidDeviceGateway.kt:89), replace the two `!!`.

## 9. Findings

### P0 (wrong behaviour or data loss in shipped paths)

- P0-1. Expression engine cannot evaluate anything beyond path lookup; "QuickJS" and Code node are not real (O1). Verified by execution.
  core/expressions/.../QuickJsExpressionEvaluator.kt:1, FallbackExpressionEvaluator.kt:22-80, CodeNode.kt:57-81, FlowEngine.kt:60-63.
- P0-2. Per-item expressions broken in about 29 call sites; every item gets item 0's value (O2). Verified by execution for Slack, Discord, Telegram.
  HttpNode.kt:87,132; DeviceNodes.kt:111-277; AiNodes.kt:250-357; SaasNodes.kt (7 sites); TransformNodes.kt:254-542; BinaryNodes.kt.
- P0-3. Secret params are expression-expanded, contradicting the documented guarantee (O3). Verified by execution. Expression.kt:~77, StepExecutor.kt:46.
- P0-4. `fallbackToDestructiveMigration()` at version 3 with `exportSchema = false` will wipe all user data on the next schema change.
  core/data/.../DataModule.kt:22, Database.kt:247-248.
- P0-5. Webhook Spark is dead end-to-end and its manifest entry names a class that is not a `Service`. AndroidManifest.xml:152, WebhookServer.kt:49,80.

### P1 (design problems that will keep producing bugs)

- P1-1. Seven JSON converters with drifting edge cases and Long-to-Double precision loss (D1).
- P1-2. Two date parsers with different rules in different nodes (D5). TransformNodes.kt:297 vs TextFormatNodes.kt:256.
- P1-3. Test net has structural blind spots: stub evaluators, single-item inputs, 0 tests for `core:work`, `core:network`, `feature:flows`, `app`, and the
  ViewModels; about 28 of 87 executors untested (section 6).
- P1-4. Build configuration masks version mismatches (`CheckAarMetadataTask` disabled, `-Xskip-metadata-version-check`), mixed JDK 17/21 toolchains, `compileSdk = 34`
  against dependencies that want 35 (section 5). The APK build is unproven; no module outside the pure core was compiled in this audit.
- P1-5. `CanvasViewModel` (965 lines, 63 public functions) plus a mirrored UI model plus `FakeFlow` adapter (D8, O8).
- P1-6. Three UI files above 1,000 lines mixing unrelated screens (FlowListScreen 1,889, StepInspectorSheet 1,239, OverviewTabs 1,191).
- P1-7. Stringly-typed step ids, params and option values (O5). A typo is a runtime failure.
- P1-8. `RoomFlowRepository.observeSummaries` decodes every flow on every DB change (Repositories.kt:57-62); `ScopeBuilder` copies all prior outputs per item per param.
- P1-9. Silent failure handling: 33 `runCatching` to `getOrNull/Default`, notably `FlowEngine.kt:69` (variables dropped), `Repositories.kt:249` (decrypt), JsonCodec enum fallbacks.
- P1-10. Docs: 5 linked documents missing (PARITY, HANDOFF, DEVELOPER_HANDOFF, BACKLOG, CAPABILITY_GAP) and at least 10 inaccurate claims (section 7).

### P2 (cleanliness, low risk)

- P2-1. Dead code and unused dependencies (section 4.1) and unused project edges (data to nodes, flows to canvas, settings to permissions, and 4 more).
- P2-2. Duplicate helpers: escaping (D6), relative time (D7), HTTP boilerplate (D3), error construction (D4), 22 hand-built `ParamResolver` instances.
- P2-3. `core:expressions`, `core:network` as Android libraries without Android code; codec lumped into `core:data`.
- P2-4. Six single-implementation repository interfaces with hand-written Hilt providers (O4).
- P2-5. "Extended*" file names are history, not domain (O8).
- P2-6. Hard-coded owner locale (Asia/Kolkata, Bhubaneswar), hard-coded `CALENDAR_ID = 1L`, hard-coded model `gpt-4o-mini`.
- P2-7. `NodeCatalog.registry()` rebuilt five times; `NoopDeviceGateway` as a default parameter.
- P2-8. `catch (e: Throwable)` in the engine; `!!` at two sites; four copies of `deepMerge(...) as Map`.
- P2-9. The three shell scripts are unrunnable without a manually provisioned Kotlin compiler and use dependency versions that differ from the Gradle catalog.

## 10. Notes on method and limits

- Whole-repo identifier search treated any name appearing only at its definition (tests and XML included) as unreferenced.
  Hilt `@Provides` functions, `@Module` classes and manifest-referenced classes were excluded from the dead list. Reflection or
  Compose-preview use would not be detected; none was seen.
- The n8n clone was not read for this lane. No third-party code, identifiers or text appear in this report.
- I accidentally created two stray files in the repo root (`canvas:`, `data`) with unquoted `->` in shell `echo` commands while
  gathering evidence. They were deleted immediately; `git status` is clean. The only repo file changed by this lane is this report.
- Scratch artefacts (compiled classes, probe programs, the collections shim) are in the session scratchpad, not in the repo.
