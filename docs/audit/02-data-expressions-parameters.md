# Audit 02: Data model, expressions, variables and parameters

Scope: `core/model`, `core/expressions`, `core/engine` (Expression.kt, Values.kt, StepExecutor.kt, FlowRunner.kt, NodeRegistry.kt), parameter use in `core/nodes`, and the inspector in `feature/canvas`.
Benchmark: n8n behaviour, read for design only. Everything below is stated as behavioural requirements in our own words. No n8n code, identifiers or text are reproduced, and KaivoFlow's implementation must be original.

All paths are relative to `/home/claude/kaivoflow`.

---

## 1. Summary

KaivoFlow has a sound but thin data layer. Items are `FlowData(json, binary)` with a proper by-reference binary handle (`BinaryDataRef`), which is a good phone-friendly design. The declarative `NodeSpec`/`ParamSpec` catalog already drives the palette, the inspector and required-field validation.

The expression system is the weakest part of the engine. It is far below what the docs and README promise:

1. **There is no expression engine.** `QuickJsExpressionEvaluator` does not use QuickJS. It delegates every call to `FallbackExpressionEvaluator` (`core/expressions/.../QuickJsExpressionEvaluator.kt:15-19`), which only understands a `$root.path[idx]` chain, a handful of `$fn()` calls and literals. There is no arithmetic, no comparison, no ternary, no string or array methods, no template literals and no date helpers. Anything else throws `ExpressionUnsupportedException` with a message that blames an unavailable "JavaScript engine" (`FallbackExpressionEvaluator.kt:44,225`).
2. **The Code step cannot work.** `CodeNode` wraps user JavaScript in an IIFE and sends it to `ctx.evaluator.evaluate` (`core/nodes/.../CodeNode.kt:58,76-82`). The fallback parser rejects it (it does not start with `$`, is not a literal). The README advertises "sandboxed JavaScript" and ARCHITECTURE.md advertises "math, string helpers". Both claims are false today.
3. **Item lineage is stubbed.** `FlowData.pairedIndex` exists but is written by exactly one node (`CompositionNodes.kt:62`) and read by nothing. There is no way to say "the source item of this output".
4. **Parameter resolution ignores the schema.** `StepContext.params()` calls `ParamResolver.resolveParams`, which expands `{{ }}` in every param. It never consults `ParamSpec.supportsExpression` (`Expression.kt:71-72`), so `Secret` and `Code`-typed fields (marked non-expression) are still interpolated. The README claim "Secret params are never expression-expanded" is only true for executors that read `step.params` raw via `secret()`.
5. **Per-item evaluation is opt-in per executor.** `params()` always evaluates against item 0 (`StepExecutor.kt:46-47`). Only nodes that call `ctx.scope(index)` or `ctx.resolve(raw, index)` are item-correct. This is easy to get wrong.

Typed schema preview exists in a basic form (inspector `SchemaView`, `StepInspectorSheet.kt:1123`), but it is computed from the last run's output only and is not available to expression autocomplete or to nodes that have never run.

---

## 2. How it works today

### 2.1 Items and routing

- `FlowData(json: Map<String,Any?>, pairedIndex: Int?, error: String?, binary: Map<String,BinaryDataRef>)` (`core/model/.../Flow.kt:245-273`). Binary bytes never live in JSON; `BinaryDataRef` carries uri, mime, file name, size and optional SHA-256 (`Flow.kt:198`).
- A Step has output ports `0..n` (`NodeSpec.outputs`, `StreamEdge.fromPort`) and a `toPort` field exists on `StreamEdge` (`Flow.kt:~178`, `toPort: Int = 0`).
- Input gathering: `FlowRunner.collectInput` (`FlowRunner.kt:~335-352`) takes every incoming Stream sorted by Stream order, takes `result.outputs[edge.fromPort]` from each, and `flatMap`s them into ONE flat list. `toPort` is never consulted. Truncation to `maxItemsPerStep` (default 5000) happens here with a log warning.
- Liveness is per port: a Step is skipped when no incoming edge carries items (`FlowRunner.kt:~296-303`). Good.
- Each Step runs once per Run (`memo` of Deferred, `FlowRunner.kt:247`). There is no loop-back, so no run index exists.
- Multi-input Steps recover the split by hand: `MergeNode` re-reads `ctx.stepOutputs` per incoming Stream and sorts by `fromPort` (`LogicNodes.kt:~110-114`), not by destination port. Merge modes: append, zip, multiply, combine-by-field. Merged items drop `pairedIndex`, `error` and `binary` (`LogicNodes.kt:~121-129` construct `FlowData(deepMerge(...))` only), so attachments are lost across a Merge.
- Step outputs are stored in `stepOutputs` keyed by **step name**, not id (`FlowRunner.kt:439,524,579`). Renaming a Step or having two Steps with the same name silently changes or breaks references.
- Frozen (pinned) output replaces execution (`FlowRunner.kt:~312`).

### 2.2 Expression language

Surface (all in `ScopeBuilder.build`, `Expression.kt:84-122`):

| Name | Meaning today |
|---|---|
| `$json`, `$item` | JSON of the current input item (same thing) |
| `$items` | list of the JSON of all input items (binary and error dropped) |
| `$index` | index of the item the scope was built for |
| `$vars` | Flow variables (`flow.vars`, merged with group variables in `FlowEngine.resolveEffectiveFlow`) |
| `$env` | `System.getenv()` plus OS/PLATFORM/TIMEZONE |
| `$now`, `$today` | ISO strings only, not date objects |
| `$run` | `{id, timestamp, timezone, step}` |
| `$step("Name")` | outputs of an earlier step; fallback evaluator supports `.path` and an optional field arg |

Fallback functions: `$step`, `$count`, `$length`, `$first`, `$last`, `$all`, `$now()`, `$today()` (`FallbackExpressionEvaluator.kt:60-71`). Property access with `.name` and `[idx|'key']`; missing path returns `null` (implicit safe navigation). Templates: `{{ }}` or `{{{ }}}` via regex (`Expression.kt:304-305`), single whole-string expression keeps its type, mixed text becomes a string.

Bugs and sharp edges in the parser:
- Argument parsing for `$fn(...)` is paren-depth counting with no quote awareness, and a string containing `)` breaks it (`FallbackExpressionEvaluator.kt:196-202`).
- Quoted keys without a closing quote are silently accepted (`readIndexOrKey`).
- Template regex is non-greedy `\}{2,3}`, so `{{ $json.a }}}` and strings containing `}}` inside an expression mis-split.
- Errors carry no position, no expression text excerpt in the user language, and no hint list. The one error message blames a missing engine, which is misleading because the engine does not exist at all.
- The `$step` helper flattens one-item outputs into the item itself and multi-item outputs into a list, and injects an `index` field into every item JSON (`Expression.kt:98`). That pollutes data (a user field called `index` is overwritten) and makes the result shape depend on item count.
- `ScopeBuilder` is rebuilt per call (`StepContext.scope`), copying `System.getenv()` and mapping all previous outputs each time. With 5000 items times several params this is O(items x steps x outputs).

Sandboxing and bounds: the fallback evaluator is a non-recursive hand parser, so it cannot loop or recurse, but it also has no time, size or depth limit and `QuickJsExpressionEvaluator(timeoutMs=400, prelude="")` accepts `timeoutMs` and `prelude` and ignores both. `quickjs-kt` is declared in `gradle/libs.versions.toml:16,53` but is not wired in. `$env` exposes the whole process environment to any imported Flow's expressions, which is an information-leak risk for imported `.kaivoflow` files.

Inspector live preview uses a fresh `FallbackExpressionEvaluator` directly (`StepInspectorSheet.kt:397`), so preview and runtime can diverge once a real engine exists.

### 2.3 Parameters

- `NodeSpec.params: List<ParamSpec>`; `ParamSpec(key,label,type,required,default,placeholder,hint,options,showWhen,supportsExpression,advanced)` (`NodeSpec.kt:46-60`). 270 `ParamSpec(` uses across `core/nodes`.
- Types: Text, Multiline, Number, Boolean, Select, Json, Expression, Url, Secret, KeyValue, Code.
- Validation: `NodeRegistry.validate` checks only that required params are non-blank, then runs the executor's own `validate` (`NodeRegistry.kt:55-80`). No type checks (a Number param can hold "abc"), no Select membership check, no JSON parse check at design time, and `showWhen` hidden-but-required fields are still required (the loop does not evaluate `showWhen`).
- `showWhen` supports only equality-in-list on sibling keys (`StepInspectorSheet.kt:373-376`).
- No composite types: no collection or repeatable group, no fixed-collection, no resource/operation pattern, no credential-slot declaration. Credentials are a plain text param holding a vault id (`StepContext.credential`, `StepExecutor.kt:~57`). `outputs` is a fixed int, so dynamic ports (Switch with N cases, Merge inputs) need hand wiring.
- Resolution: executors call `ctx.params()` (item 0 scope, all values expanded, schema ignored) or `ctx.resolve*(raw, index)` helpers (`NodeDsl.kt`). About 66 uses of the per-item helpers vs 33 of `params()`.
- Defaults live in the spec but are not applied by the engine when a key is absent; each executor re-implements fallbacks.
- Port labels and `NodeKind` are the only output metadata. There is no declared output schema per Step.

### 2.4 Schema and table preview

- `DataViewMode { Table, Json, Schema }` in the inspector (`StepInspectorSheet.kt:823`). `SchemaView` (line 1123) builds a flat field to (type, sample) map from the items of the last run, top-level keys only, first-seen type wins, no nesting, no binary section, no item count per field, no "mixed type" marker.
- Drag-from-schema to expression works through `onFieldClick` (good UX base).
- No static schema is derived from a Step before it has run, so a fresh Flow has no field list or autocomplete.

---

## 3. Gaps vs n8n behaviour

Stated as requirements, ranked by user value.

### 3.1 Items, lineage, multi-input

1. **Item linking.** In n8n every output item remembers which input item(s) it came from, and expressions can ask for "the item in step X that produced this one". This is what makes `{{ $('HTTP').item.json.id }}` stay correct after Filter, Sort, Split, Merge and enrichment steps. KaivoFlow's `$step("X")` can only return item 0, a flattened single item or the whole list; there is no way to get the corresponding item. Without it, every multi-item Flow that fans out and then references an earlier step produces wrong data silently.
2. **Per-input-index data.** Steps with several inputs (Merge, compare, compare-lists) must receive each input as its own list, keyed by destination port. KaivoFlow flattens all incoming edges and uses `toPort` nowhere.
3. **Run index and loops.** n8n steps can run multiple times (loops, batching) and expressions can address a run. KaivoFlow runs each Step once per Run; Loop/For-Each is an in-step batching feature (README), so no run index is needed yet, but a looping design needs it.
4. **Explicit "all items" vs "this item" vs "first/last item" accessors.** n8n offers current item, first item, last item and all items from any earlier step. KaivoFlow has `$first/$last/$all` only for the current input.
5. **Binary lineage and access.** Binary should be addressable from expressions (metadata: name, mime, size) and survive Merge/Set/Sort. KaivoFlow's `$items` and `$json` drop binary entirely, so `{{ $binary.photo.fileName }}` cannot be expressed.
6. **Error items.** `FlowData.error` is a string. n8n keeps a structured error object (message, node, description, time) that downstream steps and error flows can read. Expressions cannot read `error` at all (not in scope).

### 3.2 Expression capabilities (missing, ranked)

1. A real expression language: operators (+ - * / % == != < > && || ??), ternary, grouping, string and array literals, template literals, optional chaining. (P0: without it most Steps' defaults like `{{ $json.a + 1 }}` fail.)
2. Method calls on values: string (trim, split, replace, includes, toLowerCase, padStart, slice), array (map, filter, find, join, length, includes, slice, sort, reduce, unique, sum), object (keys, values, entries).
3. Date/time helpers with a real date value: now, today, add/subtract duration, format by pattern, diff, start/end of unit, timezone aware, parse with a format. n8n ships a bundled date library for this; KaivoFlow has only ISO strings.
4. Cross-step access: by step name, current/first/last/all item, item-linked, and a safe "is this step executed" check.
5. Workflow and execution context: workflow id/name/active flag, execution id, execution mode (manual, test, schedule, event), resume URL concept. KaivoFlow's `$run` has id, timestamp, timezone, step name only; no flow id/name, no mode, no trigger payload alias.
6. Built-in helper functions: if-then, now, JSON parse and stringify, jmespath-like query, random, base64/hash helpers, number formatting, currency/locale formatting.
7. Variables: read of Flow vars exists (`$vars`); there is no write path from an expression and no scoped (group/global) distinction in the scope object. Env access should be a deliberate allow-list instead of the whole process env.
8. Type coercion rules: today `Values.asString/asDouble/asBoolean` coerce silently; n8n offers a strict/lenient setting. A documented coercion table plus a "loose type validation" toggle on conditions would match.
9. Helpful errors: message with the failing sub-expression, the position, and what is available ("`$json.nam` is undefined; did you mean `name`?"). n8n shows a clear error and a "run the previous step" hint when upstream data is missing.
10. Preview and autocomplete: a variable picker backed by real data, live result with type, and errors in the editor. Present in basic form (`ExpressionPreviewBadge`) but built on the incomplete evaluator.
11. Pure-JS fallback for the Code step: multiple modes (run once for all items, run once per item), helper accessors, and returning pairs with lineage.

### 3.3 Parameter schema

n8n describes parameters declaratively with: typed values (string, number, boolean, options, multi-options, collection, fixed collection, JSON, date-time, color, resource locator, credentials select, notice), defaults, required flag, validation rules, display conditions (show/hide with AND/OR, operators, referencing other params and node version), per-field expression toggle, type options (min, max, rows, password, load options from the service), a resource plus operation pattern that makes one node cover many API calls, and a credentials declaration that binds a parameter slot to a credential type with optional display conditions. The same description drives the editor UI, default values, validation, workflow import/versioning and AI tool schemas.

KaivoFlow has the right idea (spec-driven) but a much smaller vocabulary and the spec is not the single source of truth: executors re-validate, re-default and re-coerce on their own.

### 3.4 Typed schema preview

n8n shows Schema, Table and JSON views of any step's output, with nesting, types and drag-to-map; it also infers a schema from pinned or previous data, and can pre-populate field pickers. KaivoFlow has a flat, last-run-only schema view.

---

## 4. Findings

| ID | Pri | Finding | Evidence | Fix |
|---|---|---|---|---|
| F1 | P0 | No real expression engine. `QuickJsExpressionEvaluator` is a wrapper over the path-only fallback; `timeoutMs`/`prelude` ignored; `quickjs-kt` unused. | `QuickJsExpressionEvaluator.kt:15-19`, `libs.versions.toml:16,53` | Build an original `KaivoExpr` parser and evaluator in pure Kotlin (see section 5). Remove the misleading class name. |
| F2 | P0 | Code step can never run: JS IIFE is sent to an evaluator that rejects it. README and ARCHITECTURE claims are false. | `CodeNode.kt:58,76-82`, `FallbackExpressionEvaluator.kt:31-45` | Either wire a real sandbox (QuickJS via quickjs-kt, with time and memory caps) for the Code step only, or disable the step and fix docs until it works. Add a device test. |
| F3 | P0 | `params()` ignores `supportsExpression` and expands Secret and Code values. Secret values containing `{{` get altered; code text is interpolated before running. | `Expression.kt:71-72`, `StepExecutor.kt:46-47`, `NodeSpec.kt:58` | Make `ParamResolver` take the step's `NodeSpec` and skip non-expression params; add a regression test with a secret containing braces. |
| F4 | P0 | No item linking: `pairedIndex` is written by one node and read by none; Merge drops lineage and binary; `$step()` cannot return the matching item. Multi-item Flows give wrong cross-step data silently. | `Flow.kt:248`, `CompositionNodes.kt:62`, `LogicNodes.kt:121-129`, `Expression.kt:98` | Replace `pairedIndex: Int?` with `sources: List<ItemRef(stepId, runIndex, itemIndex)>`; engine fills it for 1-to-1 and 1-to-many outputs by default; add `$step("X").matching` accessor. |
| F5 | P0 | `$env` exposes the whole process environment to any Flow, including imported ones. | `Expression.kt:101-105` | Allow-list a few keys; add a user-defined Flow settings table for constants; never expose process env. |
| F6 | P1 | Multi-input Steps get one flat list; `toPort` unused; Merge reconstructs groups by `fromPort`, which is the wrong port. | `FlowRunner.kt:~340-352`, `LogicNodes.kt:~110-114`, `Flow.kt` StreamEdge | Change `StepContext.input` to `inputs: List<List<FlowData>>` indexed by destination port; keep `input` as a convenience for single-input Steps. |
| F7 | P1 | Step outputs keyed by Step name; rename or duplicate names break references; scope rebuilt per call with env copy and full output mapping. | `FlowRunner.kt:439,524,579`, `Expression.kt:98,101-105` | Key by Step id internally; resolve names via a name-to-id table checked at save time (unique names enforced); build the scope once per item lazily. |
| F8 | P1 | Per-item evaluation depends on each executor remembering `scope(index)`; `params()` is item-0 only (33 call sites vs 66 per-item). | `StepExecutor.kt:46-52`, `NodeDsl.kt:14-31` | Provide `ctx.paramsFor(itemIndex)` and make the declarative path the default: engine resolves params per item for "per-item" executors via a `perItem` flag in the spec. |
| F9 | P1 | Binary not addressable and not preserved across Merge/Set; `$items` drops binary and error. | `Expression.kt:93-95`, `LogicNodes.kt:121-129` | Expose `$binary` (metadata) and `$error` in scope; make shared item-copy helpers preserve binary. |
| F10 | P1 | Param validation is only "non-blank"; no type, option-membership, JSON-parse or range checks; hidden required fields still required; defaults not applied by engine. | `NodeRegistry.kt:55-80` | Add `ParamSpec.validate` rules (min/max, pattern, enum, JSON-valid, expression-allowed); evaluate `showWhen` before requiring; apply spec defaults once in the resolver. |
| F11 | P1 | Error messages do not point to the failing sub-expression; misleading "engine unavailable" text. | `FallbackExpressionEvaluator.kt:225-227` | Typed `ExprError(kind, position, snippet, hint)`; surface in inspector and Run detail. |
| F12 | P1 | Parameter vocabulary lacks collections, repeatable groups, resource/operation, credential slot, dynamic ports; `outputs` fixed int. | `NodeSpec.kt` | Extend `ParamType` and `NodeSpec` per section 5.3. |
| F13 | P2 | Template splitter is a regex (non-greedy, breaks on `}}` in strings) and fallback argument parser is not quote aware. | `Expression.kt:304-305`, `FallbackExpressionEvaluator.kt:196-202` | Tokenise templates with the same lexer as the expression parser. |
| F14 | P2 | `$step` shape depends on item count and injects an `index` field into user data. | `FallbackExpressionEvaluator.kt:87`, `Expression.kt:98` | Return a stable accessor object (`items`, `first`, `last`, `item`, `count`) and never mutate user fields. |
| F15 | P2 | Date helpers: only ISO strings; no add/format/diff/zone conversion in expressions. | `Expression.kt:107-108` | Add `KDate` value type with `java.time` backed methods. |
| F16 | P2 | Schema view: flat, last-run only, no nesting, no mixed types, no schema before first run, no binary section. | `StepInspectorSheet.kt:1123-1160` | Add `core/model` `SchemaNode` inference (section 5.4) and per-Step declared output schema. |
| F17 | P2 | Inspector preview uses its own `FallbackExpressionEvaluator` instance, not the runtime evaluator. | `StepInspectorSheet.kt:397` | Inject the shared evaluator from `FlowEngine`. |
| F18 | P2 | Docs overclaim (ARCHITECTURE section 2 "sandboxed evaluation, math, string helpers"; README "sandboxed JavaScript"). | `docs/ARCHITECTURE.md`, `README.md` | Correct docs now; restore claims only when the engine ships. |

---

## 5. Target design (KaivoFlow terms)

### 5.1 Data: Streams carry Items with lineage

```
Item(
  json: JsonObject,
  binary: Map<String, BinaryRef>,
  error: ItemError?,                 // structured: message, stepId, kind, at
  sources: List<ItemRef>             // lineage: (stepId, runIndex, itemIndex)
)
Port data   = List<Item>             // one list per port
Step input  = inputs[toPort]         // List<List<Item>>
Step output = outputs[fromPort]
```

Rules for the engine (not for each executor):
- One-to-one steps: output item `i` gets `sources = [ref(input i)]`.
- Steps that produce many outputs per input (Split, HTTP pagination): every output item references its input item.
- Steps that combine items (Merge, Aggregate): `sources` is the union of the contributing items.
- Steps that create items from nothing (Sparks): `sources` empty.
- Lineage is stored as small integers, not copies, so it costs little on a phone.
- Stable Step ids are the storage key; Step names are labels resolved at save time through a unique-name table.

Expression access: `$step("Name")` returns a **Stream view** with `.items`, `.first`, `.last`, `.count`, and `.matching` (the item that is an ancestor of the current item through lineage). The older flattened forms stay as aliases during migration, and the `index` injection is removed.

### 5.2 Expression language ("KaivoExpr")

Original, pure Kotlin, no reflection, no script engine for expressions. Components:
1. **Template scanner**: splits text into literal and `{{ }}` segments with a quote-aware lexer (handles `}}` in strings).
2. **Lexer and Pratt parser** to an AST: literals, identifiers (`$json`, `$step`, `$vars`...), member and index access, optional chaining, calls, method calls, unary and binary operators, ternary, nullish, array and object literals, template literals, arrow functions with a single expression body (for `map/filter/find`).
3. **Interpreter** over a small value model (null, bool, number, string, list, map, date, binary-meta). Built-in methods are a registry of pure Kotlin lambdas grouped by receiver type. No access to JVM classes by name.
4. **Budgets**: max AST nodes, max evaluation steps (counter), max recursion depth, max produced string and list size, wall-clock deadline checked every N steps. Failure raises `ExprError` with position and kind (`Syntax`, `Unknown`, `Type`, `Budget`).
5. **Scope**: lazily built, per item, cached per Step run: `$json`, `$binary`, `$error`, `$item` (index and sources), `$items`, `$step()`, `$flow` (id, name), `$run` (id, mode, trigger kind, startedAt), `$vars` (scoped), `$env` (allow-list), `$now`, `$today` as date values.
6. **Static analysis**: `analyze(expr)` returns referenced steps and fields, used for autocomplete, "which Steps feed this field" highlighting, and detecting references to Steps that are not upstream.
7. **Code step**: separate bounded JS engine (QuickJS) only for that Step, with CPU deadline, memory cap, no host objects except a JSON-in/JSON-out bridge. Keep it off the expression path.

Compatibility: existing `{{ $json.x }}` and `$step("A").x` templates must keep working; add a golden test corpus from current tests plus the new capabilities listed in 3.2.

### 5.3 Parameters: one declarative schema

Extend `ParamSpec` into a small recursive schema:

```
ParamSpec(
  key, label, type, required, default, hint, placeholder,
  expr: Never | Allowed | Only,        // replaces supportsExpression, enforced by resolver
  rules: List<Rule>,                    // min, max, pattern, oneOf, jsonValid, urlValid
  show: Condition,                      // AND/OR tree over sibling and ancestor params, ops: eq, ne, in, gt, exists
  options: List<Option> | DynamicOptions(sourceKey),
  children: List<ParamSpec>,            // for Group (object) and Repeater (list of objects)
  credentialSlot: CredentialSlot?       // kind (oauth, apiKey, basic), required, show
)
NodeSpec(
  ..., resource/operation: optional Pair of Select params that gate subsets of params,
  inputs: List<PortSpec>, outputs: List<PortSpec(label, schemaHint)>  // dynamic by param (e.g. Switch cases)
  outputSchema: SchemaHint?            // static or computed from params
)
```

One validator (`ParamValidator`) walks the spec against `StepNode.params` and returns typed issues with a field path; the same walker drives (a) the inspector form, (b) design-time validation in `FlowValidator`, (c) default filling at resolve time, (d) import of `.kaivoflow` bundles (reject unknown keys, migrate by `spec.version`), (e) the future AI "build a Flow" tool schema. `StepContext.params(itemIndex)` returns a typed `ResolvedParams` (accessor with `string/number/bool/json/list`) so executors stop re-coercing.

### 5.4 Typed schema preview

Add `SchemaNode(type: Null|Bool|Number|String|Date?|Object|Array|Binary|Mixed, fields, element, seenIn: Int of total, sample)` in `core/model`, computed:
- from run output (merge across items; mark Mixed and Optional),
- from pinned data,
- from a Step's declared `outputSchema` before any run (e.g. HTTP: `statusCode`, `headers`, `body`; Sparks: payload shape).
Inspector shows Schema, Table and JSON with nesting and a binary section; selecting a field inserts a correct `$step("X")...` or `$json...` expression; the expression editor uses the same schema for autocomplete and for "field not present in upstream" warnings.

---

## 6. Suggested phase tasks

**Phase A: truth and safety (small, do first)**
- A1. Fix `ParamResolver` to honour `supportsExpression` / `Secret`; add tests. (F3)
- A2. Replace `$env` with an allow-list; add Flow constants. (F5)
- A3. Correct README and ARCHITECTURE claims about JS and expressions; rename `QuickJsExpressionEvaluator` or mark as stub; make the Code step show a clear "not available" state until F2 lands. (F2, F18)
- A4. Make the inspector use the shared evaluator instance. (F17)

**Phase B: expression engine**
- B1. Template scanner + lexer + Pratt parser + AST, with golden tests. (F1, F13)
- B2. Interpreter with value model, budgets and typed `ExprError`. (F1, F11)
- B3. Built-in method registry: string, array, object, number, date. (F15)
- B4. Scope v2 (`$flow`, `$run` fields, `$binary`, `$error`, stable `$step` view); keep legacy aliases. (F9, F14)
- B5. Static analyzer + autocomplete data source in the inspector.

**Phase C: item model and engine plumbing**
- C1. `Item.sources` lineage and engine-side filling; migrate `pairedIndex` in `JsonCodec`. (F4)
- C2. Per-port inputs (`inputs[toPort]`); migrate `MergeNode` and compare nodes; preserve binary in all merge-like nodes. (F6, F9)
- C3. Key outputs by Step id; unique names enforced at save and import. (F7)
- C4. `ctx.paramsFor(item)` and spec-level `perItem` execution; remove item-0 pitfalls. (F8)
- C5. Reintroduce a bounded Code step on QuickJS with CPU and memory limits and a JSON bridge. (F2)

**Phase D: declarative parameters**
- D1. Extend `ParamSpec`/`NodeSpec` (rules, conditions, groups, repeaters, credential slot, dynamic ports). (F12)
- D2. `ParamValidator` shared by `NodeRegistry`, `FlowValidator` and import. (F10)
- D3. Inspector renders groups, repeaters, new conditions; resource/operation pattern for SaaS Steps.
- D4. Migrate the 87 executors in batches by category; add a spec lint test (every executor reads only declared keys).

**Phase E: schema preview**
- E1. `SchemaNode` inference and merge. (F16)
- E2. Declared `outputSchema` for Sparks, HTTP, Set, Code, AI extract.
- E3. Nested Schema/Table/JSON views, field to expression insert, upstream warnings.

Suggested order: A, then B and C in parallel (C1/C3 first), then D, then E. Phase A alone removes the worst correctness and safety problems.
