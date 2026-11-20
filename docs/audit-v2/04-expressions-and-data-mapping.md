# Audit v2 - 04 Expressions and data mapping

Branch: claude/phase-4-base. Read-only audit. Date: 2026-11-20.
Reference: n8n 2.48.6 (behaviour only; no code or text copied, paths are pointers).

---

## 1. Scope and files read

Question owned by this document: how expressions are parsed, evaluated, previewed, autocompleted and mapped, and whether an expression works in every input box of the app.

KaivoFlow files read in full or in the parts that matter:

| Area | Files |
|---|---|
| Expression engine | `core/expressions/.../{Engine,Interpreter,Library,Dates,Parser,Lexer,Runtime,Errors,Template,Ast,Json}.kt` (about 2,700 lines of main code) and `assist/` (about 1,000 lines) |
| Assist | `core/expressions/.../assist/{ExpressionAssist,ReferenceBuilder}.kt` |
| Scope and resolution | `core/engine/.../{Expression,ParamResolution,UniversalExpressions,StepExecutor,Lineage}.kt`, `core/work/.../FlowEngine.kt` |
| Stored format | `core/model/.../{ExprFieldCodec,ExprSurfaces,ParamValueCodec,ParamSchema,ParamSpecCompat,NodeSpec}.kt`, `ParamValidator.kt` (assignments part) |
| Inspector UI | `feature/canvas/.../input/*`, `inspector/params/*`, `inspector/mapper/{MapperInsert,DragMapState}.kt`, `inspector/host/{InspectorPanes,InspectorSession,CanvasInspectorGateway,InspectorTabs}.kt`, `inspector/state/InspectorReducer.kt`, `CanvasViewModel.kt` |
| Other screens | `feature/flows/.../{FlowListScreen,OverviewTabs,CredentialEditSheet,AgentsDashboardView}.kt`, `ai/agent/{AgentBuilderScreen,AgentPrompt,AgentFlowCompiler}.kt`, `ai/{ExpressionGuard,AiSettingsSheet,ApprovalCard}.kt`, `feature/settings/PrivacyAndCredentials.kt`, `feature/canvas/CanvasOverlays.kt` |
| Nodes (consumers) | `core/nodes/.../{IfNode,FilterNode,SwitchNode,SortNode,LimitNode,SetFieldsNode,UrlNode,VolumeNode,TorchNode,ScheduleTriggerNode}.kt`, `kit/{NodeDsl,Conditions,LogicParams}.kt` |

n8n pointers read: `packages/workflow/src/{expression.ts, workflow-data-proxy.ts, expression-sandboxing.ts, extensions/*, expressions/native-evaluation/*}`, `packages/frontend/editor-ui/src/app/utils/mappingUtils.ts`, `app/composables/useResolvedExpression.ts`, `app/components/{Draggable,DraggableTarget}.vue`, `features/shared/editors/composables/useExpressionEditor.ts`, `features/shared/editors/components/InlineExpressionEditor/*`. The `@n8n/expression-runtime` package is not in the local tree (only referenced), so its internals were not read.

Not run: nothing was compiled or executed. No Gradle cache exists on this machine. Every claim below comes from reading code. Items that need a run are in section 8.

Old docs check: `docs/03_DATA_EXPRESSIONS_AND_ITEM_LINKING_GAP_ANALYSIS.md` was not trusted. Item lineage exists (`Lineage.kt`, `Expression.kt:101-137`).

---

## 2. How n8n does it

### 2.1 Storage and syntax
- A parameter value that starts with `=` is an expression. Inside it, `{{ ... }}` marks code, the rest is literal text (`expression.ts` around line 870).
- If the whole value is one `{{ }}` block the result keeps its type (number, object). Mixed text gives a string.
- Objects returned into a text slot are turned into a string. A function result is an error with a plain message ("this is a function, please add ()"). A bad syntax gives "invalid syntax".

### 2.2 Evaluation engine
- The code inside `{{ }}` is full JavaScript. n8n first rewrites method calls (`"x".toTitleCase()`) into calls of a helper so that its own "extension" methods work on native types (`extensions/expression-extension.ts`, `extendSyntax`).
- Safety is layered. AST hooks block `this`, prototype and constructor tricks (`expression-sandboxing.ts`). A regex rejects any `.constructor` text (`expression.ts` ~line 944). A newer VM engine adds a time limit and a memory limit per expression (`bridgeTimeout`, `bridgeMemoryLimit`, lines 357-358). A "native" fast path interprets a small safe subset in-process (lines 623-625, 885-889).
- Environment access: `$env` is an empty object unless the operator opts in.
- Errors carry the node, run index and item index, a template message plus a longer description (`ExpressionError`).

### 2.3 Data variables (the "data proxy", `workflow-data-proxy.ts`)
- `$json` and `$binary`: data of the current item of the node feeding this one.
- `$input`: `item`, `all()`, `first()`, `last()`, `params`, `context` of the input node.
- `$('Node Name')`: another node. Members `item`, `first()`, `last()`, `all()`, `itemMatching(i)`, `isExecuted`, `params`, `context`. Old forms `$node["Name"]` and `$items("Name")` still work.
- `.item` uses paired-item links. If n8n cannot work out which item, it throws a long, specific error (lines 939-1055, 1202). If the node has not run, the error suggests `$if($("X").isExecuted, ...)` (line 529).
- Others: `$prevNode`, `$itemIndex`, `$runIndex`, `$mode`, `$workflow` (id, name, active), `$execution` (id, mode, resume url), `$parameter`, `$vars`, `$env`, `$now`, `$today` (Luxon DateTime in the workflow time zone), `$jmespath()`, `$fromAI()`, `$self`, `$getPairedItem()`, `$evaluateExpression()`.
- Luxon classes `DateTime`, `Duration`, `Interval` are in scope.

### 2.4 Data transformation helpers (`extensions/`)
Methods that exist on native types (names only):
- String: hash, removeMarkdown, removeTags, toDate, toDateTime, toBoolean, toNumber, toInt, toSentenceCase, toSnakeCase, toTitleCase, urlEncode, urlDecode, quote, replaceSpecialChars, isDomain, isEmail, isUrl, isNumeric, isEmpty, isNotEmpty, extractEmail, extractDomain, extractUrl, extractUrlPath, parseJson, base64Encode, base64Decode, toJsonString.
- Number: ceil, floor, round, abs, format, isInteger, isEven, isOdd, toBoolean, toDateTime.
- Array: removeDuplicates, unique, first, last, reverse, pluck, randomItem, sum, min, max, average, compact, smartJoin, chunk, renameKeys, merge, mergeIntoObject, union, difference, intersection, append, isEmpty, isNotEmpty, toJsonString.
- Object: isEmpty, isNotEmpty, hasField, removeField, removeFieldsContaining, keepFieldsContaining, compact, urlEncode, keys, values, toJsonString.
- Date: beginningOf, endOfMonth, extract, isBetween, isDst, isInLast, isWeekend, minus, plus, format, diffTo, diffToNow, toDateTime.
- Full JS also gives regex (`match`, `replace(/re/g)`, `test`), `Math`, `JSON`, `Object`, `Array`.

### 2.5 Editor behaviour (editor-ui)
- Every parameter has a Fixed / Expression switch. Switching keeps the text.
- The inline expression editor is CodeMirror. Each `{{ }}` segment is resolved on its own. A segment shows as valid, invalid or pending, with its resolved value (`useExpressionEditor.ts` lines 107-190, 397-480).
- The result line under the field is debounced 200 ms, discards stale results, and can preview with a chosen "target item" (`useResolvedExpression.ts`).
- Preview works for any ancestor node that has run data. It also handles credentials (`$secrets` preview) and redacted executions.
- Autocomplete suggests variables, fields from the last run, `$('Node')` names, and extension methods with docs.
- Drag and drop (`Draggable.vue`, `DraggableTarget.vue`): dragging a field from the schema, table or JSON view over a parameter highlights the target. On drop the field becomes an expression. Rules in `mappingUtils.ts`:
  - Immediate parent gives `$json.x`. Any other ancestor gives `$('Name').item.json.x`.
  - Names that are not valid identifiers use bracket quoting.
  - If the field already holds an expression the new reference is appended after a space.
  - Params flagged `requiresDataPath` (a field name is expected, for example Sort by) get only the property name, not an expression. "multiple" appends a comma list.
- A full-screen expression editor shows input data on the left and the live result on the right.

### 2.6 Edge cases worth copying
1. A reference to a node that did not run is a clear message, not a silent empty value.
2. Missing paired item data is a hard error with a fix hint.
3. Property-name fields never take an expression (the data path rule).
4. Preview is per segment, so the user sees which `{{ }}` is wrong.
5. Time zone comes from the workflow setting for `$now`.

---

## 3. How KaivoFlow does it today (verified)

### 3.1 The engine is its own language, not JavaScript
- `KaivoExpressionEvaluator` (`Engine.kt:192-290`) is a tree-walking interpreter in pure Kotlin. Production uses it with default limits and non-strict mode (`core/work/.../FlowEngine.kt:86-89`). No QuickJS is used. The comment in `core/engine/.../Expression.kt:9` that says "QuickJS" is stale.
- Syntax: literals, `+ - * / % **`, comparison, `&& || ??`, ternary, `?.`, arrays, objects, template literals, arrow functions (`x => x.id`). No regex literal, no `typeof`, no `new`, no assignment, no semicolons. Errors for these are friendly (`Lexer.kt:218-235`).
- Template rule: `{{ }}`, also `{{{ }}}`, quote aware (`Template.kt:11-67`). One block with only blank text around it gives a typed value (`Engine.kt:210-215`).
- Safe navigation is the default: missing property gives null. `strict = true` turns missing properties, null receivers and bad indexes into errors. Production never turns strict on (only tests do).
- Limits (`Errors.kt:119-149`): 10,000 chars, depth 128, 5,000 nodes, 200,000 steps, call depth 24, strings 1,000,000, collections 100,000, 1 s timeout. Preview limits: 20,000 steps, 50 ms. The clock is checked every 64 steps (`Interpreter.kt:23-31`).
- No reflection, no JVM class access. `$env` is always an empty object (`Interpreter.kt:130`). This is a real security advantage over a JS sandbox.
- Parse cache: 512 entries, synchronised (`Engine.kt:176-184`).

### 3.2 Variables the engine really provides
Built in `ScopeBuilder.build` (`core/engine/.../Expression.kt:119-165`):

| Name | Meaning | Note |
|---|---|---|
| `$json`, `$item` | current item data | `$item` is the same map |
| `$items` | list of all input items (plain maps) | |
| `$input` | stream view: `.items .first .last .count .length` | `.first()` works through list methods, `.all()` and `.item` do not exist |
| `$index` | current item position | |
| `$vars` | flow and group variables | evaluated once per run |
| `$now`, `$today` | ISO text (UTC `Z` for `$now`) | date methods work on them by text coercion |
| `$run` | id, timestamp, timezone, step, stepId, flowId, flowName, mode, attempt, inputItems | |
| `$step("Name")` | output of a finished step, narrowed to ancestors of the current item when lineage exists | `$step("Name", "field")` returns a column |
| `$stepAll`, `$sources` | full outputs and the ancestor list | |
| `$count() $first() $last() $all() $length()` | callable only, not variables | |
| `$binary`, `$error`, `$flow` | listed as optional names and always null | see E-05 |
| `$env` | empty object | |

Not present: `$('X')`, `$node`, `$prevNode`, `$workflow`, `$execution`, `$itemIndex`, `$runIndex`, `$mode`, `$parameter`, `$jmespath`, `$ifEmpty`, `$if`, `$fromAI`. `$(` is a syntax error with the text "a variable name must follow" (`Lexer.kt:203`).

### 3.3 Library (`Library.kt`)
- Text (31 methods), number (12), array (31), object (10), date (11), boolean (1), namespaces `Math Number Date JSON Object Array`, globals such as `coalesce typeOf isEmpty keys values merge range`. Every method is also callable as a function with the receiver first (`Library.kt:629-636`).
- Date model: instant plus zone. Formatting uses `java.time` pattern letters. `plus(n, "day")` style, not Luxon objects (`Dates.kt`).
- Strengths over n8n: array lambdas with a step budget, `sort(fn, "desc")`, did-you-mean for unknown names, line and column in every error, `pluck`, `pick`, `omit`, `get(path, default)`, `unique(fn)`.
- Differences that can surprise: `replace` replaces all (`Library.kt:191`); division by zero and non-finite results are errors (`Interpreter.kt:310-315`); `toNumber("abc")` gives null silently.
- No regex anywhere (no literal, no `match`, `test`, `replace` with a pattern). No `hash`, `urlEncode`, `base64`, `isEmail`, `extractEmail`, `chunk`, `union`, `difference`, `intersection`, `removeField`.

### 3.4 Assist (`assist/`)
- `KaivoExpressionAssist.preview` uses the same `ScopeBuilder` as a run, with preview limits (`ExpressionAssist.kt:147-165`). Good parity idea.
- `diagnostics` finds syntax errors, unknown variables, unknown functions and methods, unknown fields against the upstream schema (warning), unknown step names (error), steps that are not upstream (warning).
- `complete` offers variables, `$step("` names inside the quotes, fields from schema, methods by receiver type, namespace functions, with ranking (`ExpressionAssist.kt:384-413`).
- `ReferenceBuilder` writes `$json.a` for the immediate parent and `$step("Name")[0].a` for another step, with bracket quoting, and reads text back into chips (`ReferenceBuilder.kt:105-170, 222-285`).

### 3.5 Storage formats (two of them)
- Legacy: a string containing `{{` is an expression.
- Current: `{"kx": "<text>"}` for an expression, `{"kxLiteral": ...}` as escape (`ParamValueCodec.kt:15-24`). Used by the inspector (`CanvasViewModel.kt:289-295`) and by rows inside assignments, key-value and conditions (`ParamModes.kt:67-72`).
- For non-node screens the single-string `ExprFieldCodec` stores the same `{"kx":...}` marker as text (`ExprFieldCodec.kt:13-37`).

### 3.6 Run-time resolution paths
1. `StepContext.resolvedFor(i)` (schema path): `ParamResolver.resolveStored` decodes with the LEGACY codec and `markers=false` (`ParamResolution.kt:186-191`). Used by only 5 node files (SetFields, Agent, ChatModel, BasicLlm, StructuredParser).
2. `ctx.paramsFor(i)` / `ctx.params()` (compat path): `resolveParams` -> `resolveValue` -> `expand(..., markers=false)` (`ParamResolution.kt:158-168, 194-207`). Used by 33 node files, 45 call sites.
3. `ctx.resolve*` helpers in `kit/NodeDsl.kt:15-33`: same `resolveValue`. 69 call sites.
4. 43 node files read `ctx.step.params[...]` raw and decide themselves (50 reads).
5. Non-node: `UniversalResolver` (`UniversalExpressions.kt:36-63`), `VariableResolution` (:66), `CredentialExpr` and `ExprCredentialProvider` (:106-151), `AgentPrompt` (`AgentPrompt.kt:23-33`).
- The `markers=true` path (`ParamResolver.resolve`, `decodeAll`) is only called from tests. See E-01.

### 3.7 UI mechanics
- Fixed/fx switch: `FxPearl` (`ParamWidgetCommon.kt:76`) in `ParamFieldFrame` (:256). Which kinds show it: `ParamModes.showsPearl` (`ParamModes.kt:15-25`): Text, Number, Bool, Choice, MultiChoice, DateTime, Json, Color. Not Code, Secret, CredentialPick, containers.
- Rows (key-value, assignments, conditions, file, resource) use `InlineValueField` (`InlineWidgets.kt:62`): own pearl, own drop target.
- `ExpressionField` (`ExpressionField.kt:145`): chips drawn over real text, accessory bar (`{{ }}`, quotes, brackets, undo, redo, mapper), suggestion strip (max 12), preview line, error underline, backspace deletes a chip as one undo step, full-screen dialog with result panel (:431).
- Preview pipeline: reducer effect -> debounce 150 ms -> `launchLatest` per key (`InspectorStateHolder.kt:43,87-96`) -> `CanvasInspectorGateway.evaluatePreview` (:113-136). Item selector exists (`previewItem`).
- Drag to map: whole field frame is a drop target (`ParamWidgetCommon.kt:278`), long-press drag from schema, JSON and table views, edge auto-scroll, haptics, reject animation for Code/Secret/Credential fields, TalkBack action "Insert into field" (`panels/DragSource.kt:72`). Tap to map: the tray inserts at the focused field (`MapperTray`), `MapperInsert.apply` is shared by drag and tap so both produce the same text (`MapperInsert.kt:36-106`). Containers get a new row.
- Outside the inspector: `KaivoInputField` (`input/KaivoInputField.kt:44`) with `GlobalExpressionScope` (`ExpressionScopeProvider.kt` real lines 45-83): fixed field plus same `ExpressionField`. Tap chips for `$vars.*`, `$now`, `$today`, `$run.flowName` (max 40 vars). No drag there.
- `ExprSurfaces` (`ExprSurfaces.kt:37-62`) is the written decision table for non-node inputs. Only one UI call site checks it (`InspectorTabs.kt:195`).

### 3.8 Surface inventory (every text, number and select input found)

Legend. UI = does the input offer fixed/expression. Engine = does a run resolve it. Preview = live result line. Map = drag or tap to insert.
"E-01" means the engine part is broken for inspector-written expressions (see section 4).

#### A. Step parameters (inspector, schema from `core/nodes` ParamSpec via `toDef()`)

| # | Param kind (catalog count) | Widget (file:line) | UI | Engine at run time | Preview | Map |
|---|---|---|---|---|---|---|
| 1 | Text, Url, Multiline (88 + 25 + 5 explicit, ~80 more via `textParam`) | `TextParamField` BasicWidgets.kt:122 | yes, pearl | legacy `{{` strings yes; `{"kx"}` no (E-01); raw-read nodes ignore it (E-03) | yes | yes, whole frame |
| 2 | Number (60) | `NumberParamField` :185, expression swaps stepper and slider for `ExpressionField` | yes | same as row 1. LimitNode, VolumeNode read raw and silently use default | yes | yes |
| 3 | Boolean (24) | `BooleanParamField` :272 | yes | same | yes | yes |
| 4 | Select (64) | `ChoiceParamField` :311 | yes | same. Nodes that switch on `params["mode"]` raw break when fx is on | yes | yes |
| 5 | Json (17) | `JsonParamField` ComplexWidgets.kt:104 | yes | text with embedded `{{ }}` is interpolated as raw text, not JSON-safe (E-10) | yes | yes, inserts at caret (MapperInsert.kt:99-101) |
| 6 | KeyValue (18) | `KeyValueParamField` :282, values via `InlineValueField` | values yes, keys no | nested expand, legacy only | yes | yes per row |
| 7 | Assignments (Set Fields only) | `AssignmentsParamField` :314 | value yes, name and type no | `resolvedFor`, legacy decode: `{"kx"}` value comes out as a map (E-01) | yes | yes, drop makes a row |
| 8 | Conditions kind | `ConditionsParamField` :361 | left/right yes | no catalog node uses this kind (E-22) | yes | yes |
| 9 | Secret (23) | `SecretParamField` BasicWidgets.kt:559 | no, by design | passed verbatim | no | rejects drop |
| 10 | Code (1) | `CodeParamField` ComplexWidgets.kt:140 | no | verbatim | no | rejects drop |
| 11 | Credential pick | `CredentialParamField` :565 | no | verbatim | no | rejects drop |
| 12 | MultiChoice, DateTime, Color, FileRef, ResourcePick, Repeater/Group/Collection | widgets exist (BasicWidgets.kt:383,418,496; ComplexWidgets.kt:180-286,452,486) | yes where scalar | resolver supports them | yes | yes |
| 13 | Explicit `ParamType.Expression` / `ExprMode.Only` | resolver and pearl support it | n/a | 0 catalog nodes use it | | |
| 14 | Trigger params (Schedule interval, time, days, day of month, timezone, Wi-Fi only; webhook path; device event thresholds) | same widgets as rows 1-4 | yes (default Allowed) | NO. Scheduler reads `StepNode.paramString/paramNumber` raw (`FlowScheduler.kt:83-87`, `ScheduleSync.kt:69-71`) | yes | yes |

Catalog counts come from grep over `core/nodes/.../*.kt` (ParamType usages): Text 88, Multiline 25, Number 60, Boolean 24, Select 64, Json 17, Url 5, Secret 23, KeyValue 18, Code 1, Expression 0. Only 12 params set `supportsExpression = false` (AiSlots.kt:59-62, AgentNode.kt:74-80, StructuredParser :30, GraphQlNode :34, CodeNode :52, BasicLlm :36, HttpNode :111).

#### B. Step-level inputs outside the parameter form

| # | Input | Widget (file:line) | UI | Engine | Preview | Map |
|---|---|---|---|---|---|---|
| 15 | Step notes (Behaviour tab) | `KaivoInputField` InspectorTabs.kt:190, global scope | yes | never read by engine, by design | yes (vars, now, today, run.flow*) | tap chips only |
| 16 | On error, retries, wait, time limit | pickers and steppers (StepBehaviour) | no, by design (`step.behaviour`) | n/a | no | no |
| 17 | Step name | `OutlinedTextField` StepInspector.kt:215 | no, by design | name is the key of `$step("Name")` | no | no |
| 18 | Test input JSON | `OutlinedTextField` TestInputBar.kt:61 | no (it is data) | n/a | JSON status only | no |
| 19 | Parameters search | `SearchField` ParamForm.kt:174 | no | n/a | | |

#### C. Other screens

| # | Input | Widget (file:line) | UI | Engine | Preview | Map |
|---|---|---|---|---|---|---|
| 20 | Flow variable value, group variable value | `KaivoInputField` FlowListScreen.kt:1157, 1587 | yes | once per run in `resolveEffectiveFlow` (FlowEngine.kt:94-108) | yes (global) | tap chips |
| 21 | Variable key | `OutlinedTextField` FlowListScreen.kt:1142, 1566 | no, by design | n/a | | |
| 22 | Credential non-secret field | `KaivoInputField` OverviewTabs.kt:606, CredentialEditSheet.kt:536 | yes | per use through `ExprCredentialProvider` (`UniversalExpressions.kt:106-151`, FlowEngine.kt:147) | yes | tap chips |
| 23 | Credential secret field, credential name | `OutlinedTextField` OverviewTabs.kt:576,615; CredentialEditSheet.kt:549 | no, by design | verbatim | | |
| 24 | Agent instructions | `KaivoInputField` AgentBuilderScreen.kt:762 | yes | resolved per call by `AgentPrompt` (:23-33); used by the builder test run (`AgentBuilderViewModel.kt:471`) | yes (global) | tap chips |
| 25 | Agent knowledge content | `KaivoInputField` AgentBuilderScreen.kt:1928 | yes | same | yes | tap chips |
| 26 | Agent name, document title, skill name, test prompt, numeric settings (iterations, depth, timeout) | `OutlinedTextField` AgentBuilderScreen.kt:383,1915,1843,1659,1592 | no, by design | n/a | | |
| 27 | Ask AI chat boxes | `BasicTextField` AiAssistantScreen.kt:521,1081 | no | n/a | | |
| 28 | AI settings: custom skill name, description, system instructions, remote endpoint, directive text | `OutlinedTextField` AiSettingsSheet.kt:1051-1182 | no (not listed in ExprSurfaces) | n/a | | |
| 29 | Privacy and credentials setup: key name, secret, OAuth token address, client id, client secret, refresh token | `OutlinedTextField` PrivacyAndCredentials.kt:257-336 | no, by design (`oauth.*`) | verbatim | | |
| 30 | Flow name, group name and description, sticky note title and text | FlowListScreen.kt:972,987; CanvasOverlays.kt:559,658-665,719,772 | no, by design | n/a | | |
| 31 | Data table name, description, column names, row cell values | OverviewTabs.kt:976,998,1042,1273 | no, by design (`table.row`) | n/a | | |
| 32 | Search boxes | StepPickerSheet.kt:455; OverviewTabs.kt:133,693; FlowListScreen.kt:1343; AgentsDashboardView.kt:107 | no | n/a | | |
| 33 | Approval confirm box | `OutlinedTextField` ApprovalCard.kt:75 | no | n/a | | |

Answer to "does an expression work in every input box": No, and for most of the rest that is a deliberate choice recorded in `ExprSurfaces`. The real problems are different: (1) the inspector-saved expression form is not read by the engine (E-01), (2) the pearl is shown on params the engine reads raw or before data exists (E-03), (3) no screen outside the inspector supports drag mapping, and the tray there is small.
About 47 plain text-field call sites exist in 13 files; 7 `KaivoInputField` call sites exist in 5 files.

---

## 4. Issues found

Severity: P0 breaks the main promise, P1 wrong results or big risk, P2 polish, perf or latent.

| ID | Sev | file:line | What | Why it matters | Fix idea |
|---|---|---|---|---|---|
| E-01 | P0 | `CanvasViewModel.kt:289-295` writes `{"kx":..}`; `ParamResolution.kt:158-168, 186-191, 194-207` and `StepExecutor.kt:107-120` read legacy only; `NodeDsl.kt:15-17`; `SetFieldsNode.kt:120-130`; `Conditions.kt:121-135` | The inspector stores an expression as `{"kx":"..."}`. Run time decodes with `decodeLegacy`/`markers=false`, so that map is not unwrapped. `expand` evaluates the text inside and returns `{"kx":"<value>"}` as an object. Text params then get an object (`ParamCoercionException` or JSON text). IF/Filter turn `left` into the JSON text `{"kx":"31"}`. Set Fields writes `{"kx":"Asha"}` as the field value. The `markers=true` path that handles `kx` is only called by tests (`ParamResolverTest`). | Expressions typed or dragged in the UI would not work at run time in most nodes, while the preview line looks right. This is the headline feature. Not run on device. | One decode: use `decodeLenient` in `resolveStored`, `resolveParams`, `resolveValue` and set `markers=true`. Keep legacy strings. Add an end-to-end test (task T1). |
| E-02 | P1 | `IfNode.kt:32-35`, `FilterNode.kt:25-28`, `SwitchNode.kt:43` | Condition templates use `ctx.scope()` (item 0) plus an override of `json` only. `$item`, `$index`, `$step(..)` lineage and `$run.inputItems` stay those of item 0 for every item. Switch even rebuilds the scope per item. | `{{ $item.price > 5 }}` or `{{ $index < 3 }}` gives the same answer for all items. Wrong filtering with no error. | Use `ctx.scope(index)`; build once per item; test with 3 items. |
| E-03 | P1 | `LimitNode.kt:42-44`, `VolumeNode.kt:30`, `TorchNode.kt:40`, `SortNode.kt:55-58`, trigger specs `ScheduleTriggerNode.kt:40-54` read by `FlowScheduler.kt:83-87`, `ScheduleSync.kt:69-71` | 43 node files read `ctx.step.params` raw. For params the UI shows an fx pearl on, an expression is silently ignored: Limit count falls back to 10, Volume to 50, schedule interval to 15. | The UI promises something the engine does not do, with no message. | Per node: resolve through `resolvedFor(i)` or set `supportsExpression=false` (default for Spark specs and for pure config selects). Add a catalog test that no Spark param is Allowed. |
| E-04 | P1 | `InspectorSession.kt:141-154`, `CanvasInspectorGateway.kt:113-136` | "Upstream" means direct parents only. `buildScope` marks each parent `isImmediate=true` and puts every other step in `otherSteps`. `evaluatePreview` only loads outputs of direct parents. | A reference to a grandparent step gets a false warning ("not upstream, so its data is not available here"), a false "Test the previous Step" pending state, and the mapper tray cannot offer it. At run time it works (`stepOutputs` holds all finished steps, `Expression.kt:84-88`). | Compute ancestors with a graph walk. `isImmediate` only for direct parents. Load stored outputs for all ancestors. |
| E-05 | P1 | `Interpreter.kt:116,132`; `Expression.kt:139-164`; offered in `ReferenceBuilder.kt:118-123`, `ExpressionAssist.kt:322,547,647` | `$binary` is never put into the scope. The interpreter returns null for it. The assist, schema tree and `$step("X")[0]["$binary"]` form all suggest it. | Picture, PDF and file mapping gives null with no warning. Bad for an Android app full of files. | Add `binary` to the scope: map of name to `{fileName, mimeType, size, ...}` without bytes. Same for step items. |
| E-06 | P1 | `Expression.kt:86,132` | Step output copies get `"index"` added (`d.json + ("index" to i)`). A real field called `index` (sheet row, array position) is overwritten in `$step(..)` results. | Silent wrong data for a common field name. | Do not mix metadata into data. Carry the position in `StepStream` or as `$step("X").itemIndex`. |
| E-07 | P1 | `Lexer.kt:196-203`; `Library.kt:595-603` | No `$('Name')`, `$node["Name"]`, `$prevNode`, `$workflow`, `$execution`, `$itemIndex`, `$runIndex`, `$input.all()`, `$input.item`, `.item`, `$ifEmpty`, `$jmespath`. `$(` is a syntax error. | n8n users, n8n templates and any AI that writes n8n syntax fail on first try. This also blocks an "import from n8n" story. | Alias layer in the parser and library (task T8). |
| E-08 | P1 | `StepExecutor.kt:107-108,125`; `Expression.kt:84-88,119-137` | `scope(index)` is built eagerly for every `paramsFor` call, even when no param has `{{`. First call per Step copies every item of every finished step (`fullByName`). Each call also walks lineage (up to 10,000 refs) and rebuilds the `step` map. A new `StepContext` per Step, so the copy repeats for each Step. | O(steps x items) memory churn on a phone for big runs. | Build scope lazily only when some param contains `{{`; share `fullByName` per run; make `index` virtual (see E-06). |
| E-09 | P1 | `MapperInsert.kt:88-105`; `ParamSchema.kt:71` (`TextFormat` has no field-name kind); `SortNode.kt:58` | Dropping a field onto a "field name" param (Sort by, group by, dedupe key) creates `{{ $json.price }}`, but the node wants the name `price`. n8n has a data-path rule for exactly this. | The user drops a field, the flow runs, and sorts by the VALUE of item 0. | Add `TextFormat.FieldPath` (single and multiple); on drop insert the bare path. Mark such params `ExprMode.Never`. |
| E-10 | P2 | `ParamResolution.kt:284-285` (Json fixed -> `expand`) | Json param text with `{{ }}` inside quotes is interpolated as raw text. A value with a quote or newline breaks the JSON or injects keys. | Wrong or unsafe request bodies. | Resolve JSON-typed templates with JSON escaping inside string literals, or warn in the field and suggest `{{ JSON.stringify(..) }}`. |
| E-11 | P2 | `ExpressionAssist.kt:651-654` vs `Expression.kt:147-158` | The `$run` schema lists id, timestamp, timezone, step only. Real `$run` also has stepId, flowId, flowName, mode, attempt, inputItems. | A warning ("Field was not seen") appears on `$run.flowName`, which the global mapper itself inserts (`ExpressionScopeProvider.kt` real line 61). Chip turns amber. | Build `RUN_NODE` from the same list as `ScopeBuilder`. |
| E-12 | P2 | `ExpressionAssist.kt:632-637` vs `Interpreter.kt:118-140`, `Library.kt:604-608` | `$first $last $count $length` are in `KNOWN_VARS` but at run time only exist as calls. `{{ $first }}` passes diagnostics and fails with "Unknown variable". | Confusing. | Treat them as functions in the checker, or make bare names work. |
| E-13 | P2 | `Library.kt:191-192` | `replace` replaces all matches; JS and n8n replace the first. | Copy-pasted n8n logic gives different output. | Document or make `replace` first-only with `replaceAll` for all. |
| E-14 | P2 | `Expression.kt:145`, `Library.kt:593` | `$now` renders as UTC text ending in `Z`, not the flow time zone. `Hello at {{ $now }}` shows UTC to an IST user. | Surprising output. | Format `$now` with the flow zone and offset when stringified. |
| E-15 | P2 | `Expression.kt:9-10, 28-52` | Stale comment about QuickJS. The interface also carries a regex-based default `interpolate`, a second template parser next to `Template.kt`. Only stubs and tests use it. | Two parsers can drift. | Delete the default body or make it call the real parser. |
| E-16 | P2 | `KaivoInputField.kt:126-129` | Tapping the pearl from expression to fixed replaces the text with the evaluated preview if it is a string. No undo bar outside the inspector. | One mis-tap destroys a variable expression. | Keep the expression in a hidden slot and offer Undo, as the inspector does. |
| E-17 | P2 | `KaivoInputField.kt:64`; `ExpressionScopeProvider.kt` (real line 64); `ExpressionField.kt:184-191` | Preview, diagnostics and suggestions are computed during composition on the main thread on every key. Each `GlobalExpressionScope` also allocates its own assist and parse cache. Preview timeout is 50 ms. | Possible jank on low-end phones; slow devices can hit the 50 ms preview timeout and show an error the run would not have. Needs a device test. | Move to a debounced coroutine like the inspector does; share one assist. |
| E-18 | P2 | `ExpressionAssist.kt:693-702` | Preview text is cut at 120 characters, also in the full-screen "Result" panel. | The editor made for long results cannot show them. | Keep full text in `PreviewResult`, truncate only in the one-line view. |
| E-19 | P2 | `PublishValidator.kt` (no `{{` check) | Expression syntax is not checked when a Flow is activated. Only the editor and `ExpressionGuard` (AI path) parse. | A broken expression is found at 3 am by the first run. | Add an `ExpressionSyntaxCheck` interface in engine, implemented in expressions, called by publish validation. |
| E-20 | P2 | `ExprSurfaces.kt:28-62`; `InspectorTabs.kt:195` | The table says it is the source of truth, but only one call site reads it. | The table and the UI can drift. | One JVM test that scans UI sources for `KaivoInputField` against the Expression rows. |
| E-21 | P2 | `ConditionsModel.kt:286-295` vs `Conditions.kt:121-135`, `LogicParams.kt:17-22` | Latent: the Conditions widget writes `op`, `leftType` and `{"kx"}` left/right; `ConditionGroup.fromParams` reads `operator` and plain strings; IF/Filter/Switch still declare `conditions` as KeyValue. No node uses `ParamKind.Conditions` yet. | The day someone wires it, it fails twice (E-01 and key names). | Fix with E-01; add a round-trip test between the model and `fromParams`. |
| E-22 | P2 | `FlowEngine.kt:86-89` | `strict` is never enabled. A missing field becomes null and an empty string in text, with no signal at run time. | Silent empty messages are the top support case in automation tools. | Flow setting "Strict mode" and a run-time warning in the step log when a template part resolves to null. |
| E-23 | P2 | `ExpressionAssist.kt:163-164` | Preview `ScopeBuilder` gets no `inputRefs` or `stepPorts`, so lineage narrowing is off. At run time it is on (`Expression.kt:126-137`). | Preview of `$step("A")[0].x` for item 3 can differ from the run after a fan-out. | Pass refs and ports when known; golden test that runs the same flow through the runner and the assist. |
| E-24 | P2 | `Interpreter.kt:116,132`; `Expression.kt` (no `error`/`flow`) | `$error` and `$flow` always give null. Error branches get no error data through expressions. | Error handling flows cannot read the error. (Engine owner may supply it elsewhere; not verified.) | Fill `$error` in error-path scope. |
| E-25 | P2 | `Parser.kt`, `Lexer.kt:101` | `.5` is not a number; `-2 ** 2` is -4 (JS rejects it). | Minor surprises. | Document. |

Data-loss and security notes:
- No expression can reach the file system, network, process env or JVM. Good. `StepTools.neutralize` blocks a second evaluation of AI text (`AgentFlowCompiler.kt:76-77`).
- Credential fields: only non-secret fields evaluate (`UniversalExpressions.kt:119-122`). Secret fields never. Good.
- Variables are strings unless they are expressions. `$vars.n + 1` on a stored "5" gives "51". Same as JS; mention in docs.

---

## 5. Gap vs n8n

| n8n capability | KaivoFlow status | Effort | Value |
|---|---|---|---|
| Typed single `{{ }}` result, mixed text gives string | exact in engine (`Engine.kt:210-215`) but see E-01 for stored form | S (E-01) | very high |
| Run-time resolution of every param the UI marks as expression | partial (E-01, E-03) | M | very high |
| `$json`, `$input`, `$items` | partial (`$input.all()`, `.item` missing) | S | high |
| `$('Node')`, `$node[...]`, `.item/.first()/.last()/.all()/.itemMatching()` | partial: `$step("Name")` with auto lineage; no n8n syntax | M | high (n8n migration) |
| Paired item with clear errors | partial: lineage is inferred by counts and identity (`Lineage.kt`), no "cannot determine" error, silent fallback to full output | M | high |
| `$prevNode, $itemIndex, $runIndex, $mode, $workflow, $execution` | partial: data exists under `$index`, `$run.*` | S | medium |
| `$vars`, `$env` | exact for `$vars`; `$env` empty by design | - | - |
| `$now`, `$today` as date objects with zone | partial: text values with method coercion, UTC string | S | medium |
| Luxon `DateTime/Duration/Interval` and object args (`minus({days:1})`) | partial: own date methods `plus(n, "day")`, `diff`, `startOf` | M | medium |
| Full JavaScript | missing by design (safe subset with lambdas) | - | trade-off, see 6 |
| Regex (`match`, `test`, `replace(/x/g)`) | missing | M | high for data cleaning |
| String helpers: isEmail, isUrl, extractEmail/Url/Domain, urlEncode/Decode, base64, hash, toSnakeCase, toTitleCase, removeTags | partial: trim, upper, lower, capitalize, split, pad, etc.; the listed ones are missing | S-M | high |
| Array helpers: chunk, union, difference, intersection, randomItem, smartJoin, renameKeys, average | partial: unique, flat, pluck, sum, avg, min, max, sort, find... | S | medium |
| Object helpers: removeField, keepFieldsContaining, urlEncode | partial: pick, omit, merge, keys, values, entries | S | medium |
| Date helpers: isWeekend, isBetween, isInLast, extract, diffToNow, isDst | partial: isBefore, isAfter, startOf, endOf, diff | S | medium |
| `$jmespath`, `$ifEmpty`, `$if`, `$min/$max` | missing (`coalesce`, ternary, `min`, `max` cover part) | S | low-medium |
| Sandbox and limits | exceeds (no JS, step and time budget, nothing to escape) | - | high (trust) |
| Error text with position, did-you-mean | exceeds (`Errors.kt`, `suggest`) | - | high |
| Inline editor with fixed/expression switch on every param | exact (pearl, chips, accessory bar) | - | - |
| Per-segment resolved value and state in the editor | partial: chip colour comes from static diagnostics, not from evaluating that segment; whole-field preview only | M | medium |
| Preview for any ancestor with run data | partial: direct parents only (E-04) | M | high |
| Preview with chosen item | exact (`previewItem`) | - | - |
| Autocomplete: variables, schema fields, methods, step names | partial: no docs popup with examples; `$step("` names only after typing the quote | M | medium |
| Drag from schema/table/JSON into any param | exact in inspector, with touch long-press, haptics, auto-scroll | - | - |
| Data-path params take names, not expressions | missing (E-09) | S | high |
| Append reference to existing expression | exact (`ReferenceBuilder.insertInto`) | - | - |
| Full-screen expression editor with data | partial: result panel plus tray; result cut at 120 chars (E-18) | S | medium |
| Expressions in credentials (incl. `$secrets`) | partial: non-secret fields, global scope only | S | low-medium |
| Expressions in notes, variables, agent prompts | exceeds (n8n has none for notes) | - | medium |
| Publish-time validation of expressions | missing (n8n also weak) | S | medium |
| `$fromAI()` for tool params | different design in `core/ai` (not read in this audit) | - | - |

---

## 6. Where KaivoFlow can beat n8n

1. Safe by construction. Pitch: "an imported flow cannot touch your phone through an expression". n8n has had a long run of sandbox-escape fixes because it runs real JS. KaivoFlow has no reflection. Add a small badge "Safe expression" and show the step/time budget in errors (already in `ErrorKind.Limit/Timeout`). Worth money for trust, community flow sharing and a future marketplace.
2. n8n compatibility on import. Add the alias layer (E-07) and an expression translator (`$('X').item.json.a` to `$step("X")[0].a`). Offer "paste an n8n expression" in the field. This is an acquisition funnel: people with n8n flows can try KaivoFlow without rewriting.
3. Time-travel preview on local data. All step data is stored on device (`StepDataStore`). Let the user pick any of the last runs as the preview source, and any ancestor, offline and instant. n8n previews only the last execution.
4. Presence hints from schema stats. `SchemaNode` already has `seenIn` and `of` (`ExpressionAssist.kt:541-544`). Show "email is empty in 3 of 10 items" under the preview and a "fill missing" quick fix (`coalesce`). No competitor does this. Pair it with a Strict mode switch (E-22).
5. Phone variables. `$device.battery`, `$device.wifi`, `$device.charging`, `$trigger.sms.from`, `$trigger.notification.app`, `$clipboard`. These come from triggers the app already owns. `$now` in the user zone by default.
6. India-first helpers: `formatINR()` with lakh and crore grouping, `phone91()`, `toWords()` in the Indian system, GST add/remove, UPI link builder, IST default (Schedule node already defaults to `Asia/Kolkata`, `ScheduleTriggerNode.kt:52`). Cheap to build and not available in n8n.
7. "Describe it" button on any expression field: the AI writes the expression, `ExpressionGuard` parses it, preview runs it on real sample data, the user taps Accept. `ExpressionGuard` and preview already exist.
8. Lineage view. `Item.sources` is real data. Show "this item came from item 4 of Fetch" on tap and let `$step("X")` mean exactly that. n8n only shows an error when it fails.
9. Expression snippets and undo everywhere. Single user, local store: a saved snippets list ("my phone cleanup", "format date") reachable from the suggestion strip, plus undo for the pearl in all screens (E-16).
10. One-tap field picker with TalkBack. The "Insert into field" action already exists (`DragSource.kt:72`). Make it the default on small phones, drag the power path.

---

## 7. Recommended ordered tasks

Small, each with an acceptance test. Do T1 first; nothing else is trustworthy until it is done.

| # | Task | Acceptance test |
|---|---|---|
| T1 | E-01: single decode. `resolveStored`, `resolveParams`, `resolveValue` use `decodeLenient`, `markers=true`. Remove the unused duplicate path or make it the only path. | New test in `core/nodes` (IntegrationFlowTest style): Spark -> Set Fields with assignment value stored as `{"kx":"{{ $json.a }}"}` gives the typed value; Text param stored as `{"kx":"Hi {{ $json.n }}"}` gives "Hi Ann"; legacy strings still work; a literal object `{"kxLiteral":{...}}` stays literal. Round-trip: for every value produced by `ParamValueCodec.encode`, run-time result equals preview result. |
| T2 | E-02: per-item scope for IF, Filter, Switch. | 3 items `n=1,2,3`; condition `{{ $item.n > 1 }}` keeps 2 items; `{{ $index == 0 }}` keeps 1. |
| T3 | E-03: audit the 43 raw-reading nodes. Spark params and pure config selects get `supportsExpression=false`. Count/offset/level/state style params go through the resolver. | Catalog test: no Spark spec param is expression-capable. Limit with count `{{ $vars.n }}` and `n=2` keeps 2 items. |
| T4 | E-04: ancestors for scope, tray and preview. | Flow A -> B -> C, all tested. In C, `{{ $step("A")[0].x }}` shows no warning, previews the value, and appears in the tray under "Earlier steps". |
| T5 | E-05: `$binary` in scope. | Item with a photo: `{{ $binary.photo.fileName }}` returns the name at run and in preview; unknown key gives a warning, not silent null. |
| T6 | E-06 and E-08: take `index` out of item data; build scope only when needed; share `fullByName`. | Upstream item `{index: 7}`: `$step("X")[0].index` is 7. Benchmark: 5,000 items x 10 steps, Set Fields with no expressions allocates no step copies (assert by counting calls). |
| T7 | E-09: `TextFormat.FieldPath` (single, multiple); drop inserts bare path. Apply to Sort, Aggregate, Dedupe, Split Out keys. | Drop `price` on Sort "field": stored value is `price`, not an expression; run sorts correctly. |
| T8 | E-07: n8n alias layer: `$("Name")`, `$node["Name"]`, `.item/.first()/.last()/.all()`, `$input.all()`, `$input.item`, `$itemIndex`, `$runIndex`, `$prevNode.name`, `$workflow.name/id`, `$execution.id/mode`, `$mode`, `$ifEmpty`, `$if`. | Golden file of 25 n8n expressions with expected values; each evaluates correctly; autocomplete lists them. |
| T9 | Text/array/object/date helpers from section 5, plus safe regex. Regex must be budgeted: wrap the input in a `CharSequence` that counts reads and throws `Limit` past the step budget (no catastrophic backtracking). | Per-function unit tests; `"(a+)+$".test("aaaa...!")` fails with a Limit error in under the timeout. Catalog test: every library function appears in autocomplete. |
| T10 | E-14, E-13: `$now` stringifies in the flow zone with offset; document `replace`. | With zone Asia/Kolkata, `{{ $now }}` ends in `+05:30`. |
| T11 | E-10: JSON-safe interpolation for Json params. | Name `Ann "A" Lee` in `{"name":"{{ $json.n }}"}` yields valid JSON that parses back to the same name. |
| T12 | E-11, E-12: schema and checker use the same lists as the runtime. | Test that compares `ScopeBuilder.build` keys and `$run` keys with `KNOWN_VARS` and `RUN_NODE`. |
| T13 | E-19: syntax check at publish. | Flow with `{{ $json.a + }}` cannot be activated; message names the step, field, line, column. |
| T14 | E-16, E-17, E-18: undo for pearl outside inspector, async preview in `KaivoInputField`, full result text. | Toggle fx twice restores the exact text; typing 40 characters in a variable value does not drop frames in a debug trace (device); a 500-character result shows fully in the dialog. |
| T15 | E-22: Strict mode per flow and a null-part warning in step logs; presence hint under preview (idea 4). | In strict mode `{{ $json.missing.x }}` fails the step with the field name; in normal mode the log says "part 1 resolved to empty". |
| T16 | E-20, E-21: tests that guard the surface table and the Conditions shape. | Source scan test; model <-> `fromParams` round trip. |
| T17 | Idea 3: choose the preview run and any ancestor. | After two test runs, switching "Run 1 / Run 2" changes the preview value. |

---

## 8. Not verified / needs device test

1. E-01 was found by reading the call chain. Please confirm with the T1 test before planning around it. If the repro passes, look for a conversion layer that was missed (none found: no `kx` handling in `core/data`, `core/work` or `FlowRunner`).
2. Real latency of preview, diagnostics and suggestions on a low-end phone, with a 200-field schema (E-17). Check also the 50 ms preview timeout on slow devices.
3. Memory and time of scope building for large runs (E-08). Needs a 10,000-item trace on a mid phone.
4. Whether any saved agent runs in production outside the Agent Builder test run (`AgentBuilderViewModel.kt:471`). Only the test path was seen to resolve agent instructions.
5. Whether IF/Filter/Switch ever receive the Conditions widget shape. The widget is built but no node declares it; how the inspector draws the `conditions` KeyValue for IF was not traced.
6. How `Item.sources` is stored with step data (`StepDataStore`), needed for E-23 (preview with lineage).
7. TalkBack behaviour of chips, drag and the accessory bar. The code sets semantics and actions; real use was not tested.
8. `@n8n/expression-runtime` internals (not in the local tree). Section 2.2 limits come from `expression.ts` only.
9. Locale and key-input details: decimal comma keyboards in `NumberInput` (dot only by design), IME composition with the chip overlay (identity offsets, code suggests it is safe).
10. `core/ai` tool parameter filling (`$fromAI` equivalent) belongs to the AI audit.
