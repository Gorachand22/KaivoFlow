# 06. Core, data, logic, code, HTTP and file Steps (audit v2)

Branch: claude/phase-4-base. Read-only audit. Reference: n8n 2.48.6 (behaviour only, no code or text copied).
All KaivoFlow paths below are relative to `/home/claude/kaivoflow`. `N:` = `core/nodes/src/main/kotlin/com/kaivoflow/core/nodes/`.

## 1. Scope and files read

KaivoFlow, read completely:
- Flow: `N:flow/branching/{If,Switch,Merge}Node.kt`, `flow/looping/LoopNode.kt`, `flow/timing/{Wait,Stop,ErrorHandler}Node.kt`, `flow/composition/{Noop,SubFlow}Node.kt`.
- Data: `data/items/*` (Filter, Limit, Sort, Split, Aggregate, Dedupe, Compare, Stats, Set, Fields, Json, Math, Text, Url, PollChanges), `data/time/DateNode.kt`, `data/storage/TableNode.kt`, `data/text/*` (Xml, Html, Markdown, Ical plus the Lite parsers).
- Code: `code/script/CodeNode.kt` and the whole `core/expressions` engine (Lexer, Parser, Interpreter limits, Engine).
- Net: `net/web/*` (Http, GraphQl, WebDav, BinaryUpload, ServiceConnector, Rss), `kit/ConnectorSupport.kt`, `core/network/.../KtorHttpGateway.kt`.
- Files and security: `files/convert/*`, `files/document/PdfNode.kt`, `device/files_media/FileNode.kt`, `device/UserFileStore.kt`, `security/crypto/*`, `kit/*`.
- Catalog: `N:catalog/{NodeCatalog,CategoryDefs,Converters}.kt`, `core/model/.../{CategoryTree,NodeSpec,ParamSpecCompat}.kt`, `feature/canvas/.../picker/PickerLogic.kt`, tests `StepGoldenTest`, `NodeCatalogTest`, `MergeVerdictTest`, `StepContractTest`, `NodeTestSupport`.
- Engine contact points only: `StepExecutor.kt`, `ParamResolution.kt`, `Lineage.kt`, `FlowRunner.kt` (retry and failure handling), `Values.kt`.

n8n, read for behaviour: `packages/nodes-base/nodes/{If,Switch/V3,Merge/v3,Filter,Set,Code,HttpRequest/V3,Transform/*,DateTime/V2,Wait,SplitInBatches/v3,CompareDatasets,Files/*,Crypto,Xml,Html,Markdown}`.

Not read (other audit areas): AI nodes, Sparks, device Steps, canvas UI, engine runner internals, credentials vault.

Registry size today (from `core/nodes/src/test/resources/step-golden.txt`): 71 Steps = 63 Stable, 3 Legacy (ai.chat, ai.extract, ai.classify), 5 Hidden. n8n 2.48.6 ships 308 node folders.

## 2. How n8n does it (own words)

**Data model.** Every node gets a list of items. Each item has JSON, optional binary files and a link to the item it came from (paired item). Nodes are versioned: a workflow keeps running on the version it was built with, so n8n can fix behaviour (If v1 to v2, Merge v1 to v3, Set v1 to v3, HTTP v1 to v3) without breaking old flows. Each node has a "what to do on error" setting: stop, continue, or continue and send the error down a second output.

**Typed conditions.** If, Filter and Switch share one filter widget. Each rule has a left value, a data type (string, number, boolean, date, array, object, any), an operator that belongs to that type, and a right value. Rules can be grouped with AND or OR. Types are checked strictly by default (a number rule on text fails with a clear message) and a "be lenient" option converts. "Ignore case" is an option. Both sides accept expressions.

**If / Filter / Switch.** If has two outputs. Filter has one output (optionally a second for the discarded items). Switch v3 has two modes: rules (any number of outputs, each with its own conditions, renameable output, an optional fallback output, an option to send an item to every matching rule instead of the first) and expression (compute an output number).

**Merge v3.** Modes: append; combine by matching fields (join with options: keep matches, keep non-matches, enrich input 1, enrich input 2, keep everything; "all matches" or "first match"; fuzzy compare; dot notation switch); combine by position (with "include unpaired"); combine all (cross product); combine by SQL query; choose branch. Clash handling when both sides have the same field: prefer 1, prefer 2, add suffix, deep merge. An option waits until both inputs ran.

**Edit Fields (Set).** Two modes: manual mapping (name, type, value rows) and raw JSON. Option to keep only the set fields, include other fields (all, selected, all except), dot notation on by default, type conversion that fails loudly.

**Code.** Two run modes (once for all items, once per item) and two languages (JavaScript, native Python). JavaScript runs in a separate task runner process, not in the main process. The script is a normal function body: statements, loops, functions, `return`. It reads items through helpers, can call other nodes' data, and must return item objects (validated, with clear errors that name the line). Console output goes to the log.

**HTTP Request v3.** Method, URL, authentication through the credential system (predefined types per service, or generic: basic, bearer, header, query, digest, OAuth1, OAuth2, custom), optional client SSL certificates. Query, header and body each have "send" switches and either rows or raw JSON. Body types: JSON, form-urlencoded, multipart form data, raw, binary from an input field. Options group: batching (items per batch plus pause between batches), ignore SSL errors, array format in query, lowercase headers, redirects (follow or not, max count), response (include headers and status, never error, format auto/json/text/file, output field name), pagination (next URL expression; or parameter updates per page; completion by empty response, status code or expression; max pages; delay between requests), proxy, timeout, "send credentials on cross-origin redirect" (off by default, a safety choice), WebDAV methods.

**Item shaping nodes** (folder `Transform/`): Remove Duplicates v2 has three operations (remove duplicates in this input; remove items seen in earlier executions, by key, by increasing number, or by date; clear the history). History scope is per node or per workflow. Summarize does pivot-style aggregation (append, average, concatenate, count, count unique, max, min, sum) with split-by fields. Aggregate turns many items into one item holding a list (per field, or all data). Sort has simple (multi-field), random and custom-code modes. Limit keeps first or last N. Split Out takes one or several field names and can include other fields.

**Other core nodes.** Compare Datasets (two inputs, four outputs: only in A, same, different, only in B; fuzzy compare, skip fields). Date & Time v2 (add, subtract, format, get time between, extract part, round, current date; time zone aware). Crypto (hash, HMAC, sign, generate; several algorithms; hex or base64). Wait (after interval, until a time, on a webhook call, on a form submit) and the execution is stored while waiting. Loop Over Items (two outputs, loop and done; the loop goes back into the node; state kept per node; reset option). XML both ways with many options. HTML extract by CSS selector. Markdown both ways. Files: Read/Write Files from disk, Extract From File (CSV, XLSX, ODS, PDF, JSON, HTML, ICS, text, RTF, binary to base64), Convert To File (same list backwards), Compression, Move Binary Data, Edit Image. Also Execute Command, SSH, FTP, Execute Workflow (call another workflow with input mapping), Stop And Error, Error Trigger, No Operation, Respond To Webhook, Form, Data Table, Sticky Note, Debug Helper.

**Edge cases n8n handles that matter.** Empty input: most nodes return nothing and the run continues; Loop and Merge have explicit empty rules. Large data: items stored in memory and optionally on disk; binary mode "filesystem" avoids loading files. Numbers: JavaScript numbers (same 2^53 precision limit as KaivoFlow's Double). Errors carry item index so the user knows which item failed.

## 3. How KaivoFlow does it today (verified)

### 3.1 Executor contract and shared kit
- A Step is a `StepExecutor` with a `NodeSpec` (`engine/StepExecutor.kt:203-215`). Executors get raw `ctx.step.params` and resolve expressions themselves. The typed path (`ctx.resolved()`, `resolvedFor(i)`) exists (`StepExecutor.kt:117-123`) but only Edit Fields uses it (`SetFieldsNode.kt:128`). Everything else uses the deprecated `ctx.params()` (item 0 only), `ctx.paramsFor(i)`, or raw params plus `resolveText`. Result: expression support differs from node to node (see CN-12).
- `ctx.perItem {}` (`StepExecutor.kt:155-170`) gives per-item failure handling with the Step's error policy. Only a few Steps use it. HTTP, CSV, Date, Text and others throw and fail the whole Step.
- Lineage (paired items) is inferred by `Lineage.fill` after the Step returns (`engine/Lineage.kt:9-22`): same instance or equal content, then counts, else left empty. Merge sets sources itself (`MergeNode.kt:107-114`). Others rely on inference.
- Retired Steps live on as internal engines behind hub Steps (`kit/OpDelegate.kt:15-28`). Hub Steps: `data.set` (fields, assignments, text/math/json/url helpers), `net.http` (presets: request, paged, graphql, webdav, upload), `security.crypto` (hash, HMAC, tokens, random). Old ids load through converters (`catalog/Converters.kt:44-190`).

### 3.2 Per-Step reality (parameters, behaviour, parity)

| Step (id) | Params and behaviour (verified) | Parity vs n8n |
|---|---|---|
| If (`flow.if`) | `combinator` all/any, `conditions` rows left/operator/right (`kit/Conditions.kt:42-139`). 20 operators, loose typing. Two outputs. Raw params (`IfNode.kt:31`). `right` is never expression-resolved (`Conditions.kt:51-52`). Scope built once for item 0 (`IfNode.kt:32`). Empty condition list = everything true (`Conditions.kt:112`). | Partial. No types, no nesting, no strict mode, no right-hand expressions. UI mismatch, see CN-02. |
| Filter (`data.filter`) | Same engine (`FilterNode.kt:24-29`). One output, drops the rest. | Partial. No "discarded" output. |
| Switch (`flow.switch`) | `field` plus `case1..case4` text, case-insensitive equals, first match, fifth output "else" (`SwitchNode.kt:25-49`). Cases are raw text, not expressions. | Partial, weak. Max 4 branches, equality only, one value expression for all. |
| Merge (`flow.merge`) | Modes append, zip (shortest or longest), multiply, combine (key join: left, inner, outer, unmatched; multi-field keys; first match only; `MergeNode.kt:134-162`), choose input N. Clash: deep, input1, input2. Input ports are inferred from streams (`MergeNode.kt:170-187`). Sets lineage. | Partial, good. Missing: all-matches, suffix clash mode, SQL, fuzzy, "enrich" naming, wait-for-both option. |
| Loop (`flow.loop`) | A batcher, not a loop. Tags items with `_loop {batch, batches, size, total, first, last, index}` or emits one item per batch (`LoopNode.kt:48-80`). Safety limit 500 batches. One output. No loop-back, no done output. | Missing true Loop Over Items. Partial batching. |
| Wait (`flow.wait`) | Seconds (0..900) plus random jitter (0..60), in-process `delay` (`WaitNode.kt:28-35`). | Partial. No "until time", webhook or form resume. Not durable. |
| Stop (`flow.stop`) | Ends the Run as stopped, or fails with a message (`StopNode.kt:37-52`). | Exact (n8n splits it in Stop And Error). |
| On error (`flow.on_error`) | Pass-through. Param `message` is unused (`ErrorHandlerNode.kt:20,25`). | Partial. See CN-25. |
| Run another Flow (`flow.subflow`) | `flowId` typed by hand, send input yes/no, max 100 items (clamped 1..5000), wrap result in field (`SubFlowNode.kt:35-56`). | Partial. No input mapping, no flow picker, no wait-for-completion choice, lineage dropped. |
| Pass through (`flow.noop`) | Optional note field. | Exact-plus. |
| Limit (`data.limit`) | first, last, skip, slice, sample (`LimitNode.kt:41-58`). Raw params: expressions in count are ignored (CN-12). | Exact for first/last, plus extras. |
| Sort (`data.sort`) | One field, asc/desc, number/text/date/auto, stable, or shuffle (`SortNode.kt:54-95`). | Partial. No multi-field, no custom code. |
| Remove duplicates (`data.dedupe`) | Fields list or whole item; memory per run, per day, or across runs in a state file, up to 20 000 hashes (`DedupeNode.kt:62-92`). Can also fetch a URL first (poll mode, `PollChangesNode.kt`). | Partial. Has "seen before" memory. No increasing-number or date mode, no "clear history", no scope choice node vs flow. Data-loss risk CN-04. |
| Compare lists (`data.compare`) | One input vs a pasted JSON baseline; output one item with rows added/removed/changed/same and a summary (`CompareNode.kt:35-92`). | Partial. n8n has two real inputs and four outputs. Unicode bug CN-09. |
| Edit fields (`data.set`) | Assignments (typed rows), quick fields map, helpers (text, math, json, url), rename, remove, keepOnly (`SetFieldsNode.kt:83-161`). Best-wired Step: uses typed param kinds. Field names are flat keys, dots do not nest (`SetFieldsNode.kt:132`). | Partial-good. No raw JSON mode (helper JSON is partly that), no dot-notation nesting, no include-selected. |
| Split items (`data.split_out`) | Array field, text by separator, or chunk the whole list (`SplitNode.kt:49-84`). One source expression. | Partial. One field only, no "include" choice beyond keep-all. |
| Group and total (`transform.aggregate`) | Group by one field, count, sum, average of one field (`AggregateNode.kt:27-57`). Hidden. | Partial vs Summarize, missing vs Aggregate. |
| Number summary (`transform.stats`) | count, sum, min, max, mean, median, stddev, variance of one field. Hidden. | Partial vs Summarize. |
| Date and time (`data.date`) | parse, format, shift (min/hour/day/week/month), diff, convert zone (`DateNode.kt:49-141`). | Partial. See CN-14. |
| Local table (`logic.table`) | JSON file per table, max 5 000 rows, put/get/delete/list/increment (`TableNode.kt:61-114`). Hidden. | Partial vs Data Table. |
| Code (`code.javascript`) | See 3.3. | Effectively missing today. |
| Crypto (`security.crypto`) | sha256, md5, sha1, hmac256, base64, uuid, random string, JWT decode/verify/sign (`CryptoNode.kt`). | Partial. |
| TOTP (`security.totp`) | RFC 6238 codes. | Beyond n8n core (n8n has a TOTP node too). |
| CSV (`transform.csv`) | Parse text to items, or build text from items; first item only (`CsvNode.kt:44-51`). Hidden. | Partial vs Extract From File and Convert To File. |
| XML / HTML / Markdown / iCal (`data.*`) | XML both ways with a custom parser; HTML text, links, images, one tag attribute through regex (no CSS selectors); Markdown to HTML; iCal event builder. | Partial. HTML is weak. |
| Binary tools (`transform.binary`) | base64 and text conversion, gzip, zip (50 files max), unzip (30 files max), SHA-256, info (`BinaryToolsNode.kt`). Hidden. | Partial. |
| PDF text (`files.pdf`) | Best-effort text layer, page count (`PdfNode.kt`, `PdfLite.kt`). | Partial. No OCR, no scanned pages. |
| Save to a file (`device.file`) | Write or read text (JSON-parse on read) inside the app files folder only; traversal and symlink escape blocked (`FileNode.kt`, `UserFileStore.kt:14-46`). | Partial. No binary, list, delete, append, shared storage. |
| HTTP (`net.http`) | See 3.4. | Partial. |
| Feed reader (`net.rss`) | URL, limit, timeout; first input item only (`RssNode.kt`). | Partial. n8n RSS Feed Read is similar. |

### 3.3 Code Step state (the key finding)
- `CodeNode` wraps the script as `(function(){ <script> })()` and passes it to `ctx.evaluator.evaluate` (`CodeNode.kt:64,81,86`). The production evaluator is `KaivoExpressionEvaluator`, KaivoFlow's own small expression interpreter (`core/expressions/Engine.kt:187-200`).
- That interpreter has no statements. Its AST only has literals, arrays, objects, identifiers, member and index access, calls, unary, binary, conditional, arrow function and template literal (`Ast.kt:1-30`). The lexer rejects `;` and `=` with explicit messages (`Lexer.kt:226-235`), has no keywords except true, false, null, undefined (`Lexer.kt:196-200`), and the parser has no `function`, `return`, `let`, `const`, `if`, `for` or spread. Even the default script of the Step (`return $items.map(item => ({ ...item, doubled: ... }));`, `CodeNode.kt:51`) uses `return`, `;` and spread, so it cannot parse.
- Evidence it was never run for real: the Step's only unit test uses a stub evaluator that returns a fixed list (`NodeExecutorTest.kt:575-590`); `StepFixturesTest.kt:30` only stores a code string; the nodes test kit evaluator only looks up `$path` (`NodeTestSupport.kt:17-27`).
- QuickJS is declared but not used: `gradle/libs.versions.toml:17,54` and keep rules in `app/proguard-rules.pro:37-42` exist, but no `build.gradle.kts` depends on it (`core/expressions/build.gradle.kts` says "no native library"). Comment `engine/Expression.kt:9` still says QuickJS. Older doc `docs/04` row "JavaScript (QuickJS/Rhino) basic support" is wrong.
- Limits of the existing interpreter (good for `{{ }}`): 200 000 steps, 1 s wall clock, call depth 24 (`Errors.kt:120-136`). Safe, but not a script runtime.
- Result mapping in the Step is reasonable once a real engine exists: array of objects becomes items, a scalar becomes `{value}` (`CodeNode.kt:66-77`). Weak spots: binary is dropped, no lineage (`FlowData(Values.asMap(entry))`), per-item mode keeps only the first returned item (`CodeNode.kt:82`).

### 3.4 HTTP depth (verified)

| Area | Today | File:line |
|---|---|---|
| Methods | GET, POST, PUT, PATCH, DELETE, HEAD plus WebDAV set | `HttpNode.kt:59-65` |
| Auth | none, bearer, API key in header, basic, saved credential (bearer, header+value, basic from vault). Connected-account tokens through `accessToken`. No digest, OAuth1, query-string key, AWS, client certificate | `HttpNode.kt:168-187`, `kit/NodeDsl.kt:35-36` |
| Body | none, JSON, form-urlencoded, raw text; multipart only through the upload preset (one file) | `HttpNode.kt:189-195`, `KtorHttpGateway.kt:66-96` |
| Query and headers | key/value rows; blank values are dropped | `HttpNode.kt:166,203-204` |
| Pagination | "paged" preset: next-URL path only, 1..20 pages. No page-number, offset, cursor, Link header or stop expression | `ServiceConnectorNode.kt:85,111-135` |
| Retries | paged preset only: 0..5 retries, fixed delay up to 5 s, retries every non-2xx including 4xx. The engine also has Step-level retry with backoff (`FlowRunner.kt:640-641,752`) | `kit/ConnectorSupport.kt:64-87` |
| Redirects | always followed; `HttpRequestSpec.followRedirects` exists but the gateway ignores it | `StepExecutor.kt:230`, `KtorHttpGateway.kt:222` |
| SSL, proxy, certificates | no options; system trust store only; no proxy setting | `KtorHttpGateway.kt:220-236` |
| Timeout | 1..300 s per request; client default 120 s | `HttpNode.kt:207`, `KtorHttpGateway.kt:97,226-230` |
| Response | JSON auto-parsed by content type or by first character; else text. Array bodies split into items. Always adds `statusCode`, `durationMs`, `contentType` into the item | `HttpNode.kt:220-239`, `KtorHttpGateway.kt:121-128` |
| Binary download | only the WebDAV preset keeps a GET as an attachment (hard-coded key `download`); 25 MB cap | `WebDavNode.kt:92-114`, `KtorHttpGateway.kt:103-119,214` |
| Binary upload | multipart with one attachment plus text fields; no raw binary body | `BinaryUploadNode.kt:54-90` |
| Concurrency | strictly one request at a time per item, no batching or interval option | `HttpNode.kt:158-241` |
| Error handling | any non-2xx throws and fails the whole Step unless "keep going on 4xx/5xx" | `HttpNode.kt:213-218` |
| Cleartext | URL check accepts http://; manifest sets no cleartext flag, so Android 9+ blocks it at runtime | `HttpNode.kt:162`, `app/src/main/AndroidManifest.xml` |

### 3.5 Catalog, picker, golden test
- One manual list builds every executor (`NodeCatalog.kt:107-185`). `PlacedExecutor` adds taxonomy placement from `CategoryDefs.placements` (`CategoryDefs.kt:220-310`).
- `CategoryTree` has 10 top levels and 2 levels max (`CategoryTree.kt:28-114`). Placement rules are data only (`:117-128`). Forbidden names list stops a "misc" bucket.
- Picker: `offerable` hides Hidden and Removed Steps and sub-Steps (`PickerLogic.kt:48-52`). Search scores title, keywords, subtitle and category words, with one-edit typo tolerance for words of 5+ letters (`PickerLogic.kt:172-216`). "Aliases" in search are the `keywords` list. `AliasMap` is a different thing: old Step ids to new ones for saved Flows (`catalog/Converters.kt` and `CatalogAliases`).
- Golden test: `StepGoldenTest` snapshots id, legacy category, path, alsoIn, lifecycle, role, kind, outputs and param keys for all 71 executors against `step-golden.txt`. It protects renames and category moves. It does not check behaviour. `AliasCoverageTest` plus `old-step-ids.txt` (87 ids) protect saved Flows. `NodeCatalogTest` checks ids, metadata, search, and validates one sample Flow.
- Hidden Steps with no replacement yet: Local table, Group and total, Number summary, CSV, Binary tools (`CategoryDefs.kt:249-261`, test `MergeVerdictTest.kt:372-377` says "until their replacements ship"). Users cannot add them from the picker today.
- Stale metadata: sub-categories `ai/agents`, `ai/models`, `ai/parsers`, `data/storage`, `files/archive` are marked `planned` although Steps exist or are hidden (`CategoryTree.kt:52,59,81-85`). Harmless to the picker (empty ones are dropped, `PickerLogic.kt:96-101`), misleading to readers.

## 4. Issues found

Severity: P0 = a core promise is broken for normal use. P1 = wrong result, data loss or a missing basic feature that users will hit. P2 = polish, edge case, risk.

| ID | Sev | File:line | What | Why it matters | Fix idea |
|---|---|---|---|---|---|
| CN-01 | P0 | `CodeNode.kt:64,81,86`; `Lexer.kt:226-235`; `Ast.kt:1-30` | The Code Step feeds a statement script to an expression-only interpreter. Any script with `return`, `;`, `let`, `for`, `function` or spread fails to parse, including the Step's own default text. Tests use a stub, so CI is green. | "Code" is the escape hatch every automation user expects. Today the only way to reshape data beyond the visual Steps is blocked. Also means Aggregate-to-list and most "custom logic" cases have no path. | Add a real script engine behind an interface (see T2): QuickJS via the already-listed `quickjs-kt`, with memory and time limits. Add a test that runs the default script on the real engine. |
| CN-02 | P0 (confirm on device) | `kit/LogicParams.kt:17-21`; `ParamSpecCompat.kt:23`; `ParamForm.kt:86,88`; `kit/Conditions.kt:122-138`; `feature/canvas/.../params/ConditionsModel.kt:8-9,261-265` | If and Filter declare `conditions` as a key/value list, so the inspector shows name/value rows (stored as a map or `{name,value}` rows, `AssignmentsModel.kt:125-150`). The executor reads a list of `{left, operator, right}` and falls back to "equals" for any unknown operator (`Conditions.kt:38`). A new typed, nested condition widget exists (`ParamKind.Conditions`, `ConditionsParamField` in `ComplexWidgets.kt:366-399`, validator operators `ParamValidator.kt:335-338`) but no Step uses it (`withKinds` is only called in `SetFieldsNode.kt:73-76`). The widget stores `op` values such as `is`, `gt`, `before`, `lengthGt`, `hasKey`; the executor does not know them. | If and Filter are the most used nodes of any automation tool. If a user can only produce name/value rows, the branch result is wrong (items go to false, or all pass when the list is empty). Tests pass because they build the old executor shape by hand (`NodeExecutorTest.kt:144,161`). | Wire `ParamKind.Conditions` to If, Filter and Switch rules (T1). Write one evaluator that reads the widget shape: typed operands, nested groups, `ignoreCase`, `lenientTypes`, operator set of the validator. Keep reading the old shape for saved Flows. |
| CN-03 | P1 | `Conditions.kt:51-52,68-72,121` | Right-hand value is never expression-resolved. Equality converts both sides to numbers when possible, so "007" equals 7, phone numbers and zip codes compare as numbers, long ids lose precision (Double). `Operator.fromKey` is exact-name only. Regex is compiled per item with no time limit (`:88-91`). | Silent wrong branches; ID matching bugs are hard to notice. | New evaluator (T1) with strict types by default and a lenient option; resolve right values per item; compile regex once; guard against catastrophic patterns. |
| CN-04 | P1 | `DedupeNode.kt:62-92`; `PollChangesNode.kt:113-114`; no `ctx.mode` use outside Agent (`grep` found only `AgentNode.kt:143`) | Remove duplicates (scope day or always) and the poll mode write the "seen" set before the rest of the Flow ran, and also in Test Step and manual Runs. | If a later Step fails, the items are marked seen and never delivered again (at-most-once). Pressing Test on the Step in the editor eats the real items, so the first live Run finds nothing. This is the classic "my automation skipped data" bug. | Two-phase: write pending keys, commit on Run success (engine hook or `StepResult.commit` callback). Do not persist in TestStep. Add "forget" button already present as `reset`. |
| CN-05 | P1 | `HttpNode.kt:134-152` | Presets graphql, webdav, upload pass only bearer auth (graphql, webdav) or no auth (upload). Choosing basic, header or saved credential in those presets is silently ignored. | The request goes out unauthenticated or fails with 401 and a confusing message. Users believe the credential was used. | Pass auth through to every preset or hide the options with `showWhen`. Add a test per preset and auth mode. |
| CN-06 | P1 | `HttpNode.kt:158-218` | One failing item aborts the whole Step and throws away the results of the others. No per-item continue, no "never error" switch. | A list of 100 URLs with one dead link produces nothing. n8n and every user expectation is partial success. | Use `ctx.perItem` (`StepExecutor.kt:155`) so the Step's error policy applies per item; add "include status and headers" and "never error" options. |
| CN-07 | P1 | `HttpNode.kt:222-239`; `KtorHttpGateway.kt:121-128` | Response fields are merged into the item next to `statusCode`/`durationMs`/`contentType`; a body with its own `statusCode` overwrites it. Non-JSON or binary responses are read as text (corrupts images, PDFs). No response format choice. Whole text body is read with no size cap (only binary is capped). | Corrupted downloads, polluted data, out-of-memory risk on a phone. | Add response format (auto, JSON, text, file); keep body untouched and put metadata under one key, optional; cap text size; allow file download in the plain preset. |
| CN-08 | P1 | `CompareNode.kt:57-58` | "Ignore case and punctuation" lowercases and then deletes everything outside a-z0-9. Hindi, Odia, Arabic, Chinese and accented text becomes empty, so different values compare equal. Default is on. | Wrong "same" results for Indian-language data, a core market. | Normalise with Unicode (NFKC, lower case, strip punctuation class only). |
| CN-09 | P1 | `CsvNode.kt:44-51`; `CsvLite.kt:9,65` | CSV reads only the first item's text; text over 2 000 000 characters returns an empty list with no error; output quoting only checks comma, so `;` or tab files break when a value contains the delimiter; no BOM handling; cannot take an attachment directly. | Silent empty result and corrupt exports. | Parse per item, error on overflow, quote on the chosen delimiter, strip BOM, accept binary input (Extract From File replacement, T3). |
| CN-10 | P1 | `CategoryDefs.kt:249-261`; `PickerLogic.kt:48-52` | CSV, Group and total, Number summary, Binary tools and Local table are Hidden. Their replacements do not exist. | From the picker a user cannot read a CSV, group rows, total a field, zip a file or keep state. These work today for saved Flows only. | Un-hide the working Steps now (T3) and replace them later; do not wait for a rewrite. |
| CN-11 | P1 | `TableNode.kt:61-114`; `UserFileStore.kt:28-33` | Table (and dedupe, poll state) use whole-file read-modify-write with a plain `writeText`. Corrupt JSON reads as an empty table, and the next write replaces the file. Two Flows running together lose updates. | A crash during a write can wipe a user's saved data. Counters race. | Write to temp then atomic rename; keep one backup; use Room (SQLite) for tables (T10). |
| CN-12 | P2 | `LimitNode.kt:42-45`; `SortNode.kt:55-60`; `SplitNode.kt:50-55`; `SwitchNode.kt:36-40`; `CompareNode.kt:35-44`; `StatsNode.kt` | These executors read raw `ctx.step.params`, so a number or mode set by expression (`{{ $json.max }}`) is not resolved; `Values.asDouble("{{...}}")` is null and the default is used silently. | Wrong count with no warning; inconsistent with Edit Fields. | Move to `ctx.resolvedFor(i)`; the typed accessors throw clear errors. |
| CN-13 | P2 | `SplitNode.kt:67-82`; `engine/Lineage.kt:13-22` | Split Out with several input items and different array sizes ends up with no lineage (rule 4), so `$step("X").item` breaks after it. A missing field silently drops the item (`:74` uses `asList(null)`). Binary is copied to every child. | Hard-to-explain broken links; silent data drop. | Set sources explicitly with `ctx.refOf(i)`; error or option for a missing field; move binary only on the first child (or by option). |
| CN-14 | P2 | `DateNode.kt:105-134` | Only ISO text parses (`Instant.parse`, local date, local date-time); no epoch seconds or milliseconds, no day-first text. Shift runs in UTC, so "add 1 day" ignores the user's time zone and DST. Month difference is 30 days; differences truncate toward zero. Parse errors use a blank step id (`:117`). Format uses Java patterns, not documented anywhere in the UI. | Wrong times near midnight; confusing format letters. | Add epoch input, preset formats, zone-aware shift, calendar-correct diff; pass step id into errors. |
| CN-15 | P2 | `TextNode.kt:113,135-139` | `words`, snake, camel, word count and slug only know A-Z and 0-9; "remove accents" decomposes marks, which also strips Indic vowel signs. | Hindi/Odia text yields 0 words or damaged text. | Use Unicode letter classes; keep marks for non-Latin scripts. |
| CN-16 | P2 | `BinaryToolsNode.kt:145,213,227`; `SubFlowNode.kt:44`; `CsvLite.kt:9` | Silent caps: zip 50 files, unzip 30, Sub-Flow 5 000 items, CSV 2M chars. No warning in log or output. Unzip sums up to 30 x 25 MB in memory. | Missing files with no message. | Log or fail when a cap trims data; add a total-size cap; stream via `content://` instead of base64. |
| CN-17 | P2 | `MergeNode.kt:123,152` | Combine uses only the first right-side match. Multiply builds the full product in memory. | 1:N joins lose rows; large products can exhaust memory. | "Keep all matches" option; size guard on multiply. |
| CN-18 | P2 | `IfNode.kt:32`, `FilterNode.kt:25` | Scope is built for item 0 and only `json` is swapped per item. Item-linked expressions (`$step("A").item`) resolve against item 0 for every item. | Wrong values when branching on data from an earlier Step. | Use `ctx.scope(index)` per item. |
| CN-19 | P2 | `CryptoNode.kt:99,107-118` vs `kit/CryptoMath.kt` | Only HMAC-SHA256; hex only; hash code duplicated in two places; `base64_decode` returns an empty string on bad input without an error. | Cannot sign for APIs needing SHA-1/512 or base64 signatures; silent failure. | Use `CryptoMath` everywhere; add algorithm and encoding params; throw on bad base64. |
| CN-20 | P2 | `KtorHttpGateway.kt:222`; `StepExecutor.kt:230` | `followRedirects` field is dead; no redirect limit, no choice; no check of what happens to the Authorization header when a redirect leaves the host. | Cannot read a 302 location; possible token leak (see section 8). | Honour the field; strip credentials on cross-host redirects unless the user opts in. |
| CN-21 | P2 | `kit/ConnectorSupport.kt:64-87` | Retry loop retries all non-2xx (including 401, 404) and non-idempotent POST, fixed delay, no `Retry-After`. | Wasted calls; double submits. | Retry on 408, 429, 5xx and network errors only, with backoff and `Retry-After`; POST only by option. |
| CN-22 | P2 | `HttpNode.kt:162`; manifest | http:// is accepted by validation but blocked by Android 9+; the user sees a raw IOException text. LAN devices are a main Android use case. | Frustration for home-automation users. | Decide policy: allow cleartext to private ranges with an explicit per-Step switch; map the error to plain text. |
| CN-23 | P2 | `ErrorHandlerNode.kt:20,25` | "Alert text" param is never used. | Dead UI. | Remove it or produce the text in the output item. |
| CN-24 | P2 | `WaitNode.kt:28-35` | Wait is an in-process `delay` capped at 900 s. If the process dies the Run is lost. | Long waits are unreliable on Android. | Durable wait through WorkManager or exact alarm that resumes the Run (needs engine support). |
| CN-25 | P2 | `MathNode.kt:94` | `ln` branch exists but is not in the operation list (`MathNode.kt:36-46`). Dead code. | Cleanliness. | Add to options or remove. |
| CN-26 | P2 | `HttpNode.kt:166,203-204` | Blank header and query values are dropped, so `?flag=` or an empty header cannot be sent. | Some APIs need empty params. | Keep blank values when the key is set; drop only when the whole row is empty. |
| CN-27 | P2 | `Expression.kt:9`; `libs.versions.toml:17,54`; `proguard-rules.pro:37-42` | Comment, version entry and keep rules mention QuickJS but nothing uses it. | Misleads readers, ships useless rules. | Remove or make true through T2. |
| CN-28 | P2 | `NodeCatalog.kt:107-185`, `CategoryDefs.kt`, golden file | Adding a Step needs edits in four places (list, placement map, golden file, old ids if renamed). Tests build executors with a stub evaluator (`NodeTestSupport.kt:17-27`). | Easy to forget; stub hides expression bugs like CN-01. | Add the real evaluator to `:core:nodes` tests (T0). |

Counts: P0 = 2, P1 = 9 (CN-03 to CN-11), P2 = 17.

### Duplicated and dead code worth cleaning
- `HttpNode` plain request re-implements headers and auth that `ConnectorSupport.connectorHeaders` already has (`HttpNode.kt:165-187` vs `ConnectorSupport.kt:20-52`). Behaviour differs slightly (bearer prefix check exists only in the connector version). Use one.
- `CryptoNode.hashString/hmacSha256` duplicate `CryptoMath` (CN-19).
- Per-node `shortUrl`/`snippet` helpers exist in both `HttpNode.kt:245-248` and `ConnectorSupport.kt:121,..`.
- Retired executors (Json, Math, Text, Url, Fields, Poll, Connector, GraphQl, WebDav, BinaryUpload, Token) are kept as engines. Fine, but their `spec`s are never shown, so param text in them is dead UI copy.

## 5. Gap vs n8n

Status: exact = same job, same options that matter; partial = works with fewer options; missing = absent.

| n8n capability | KaivoFlow status | Effort | Value |
|---|---|---|---|
| If with typed, nested conditions | partial (engine yes, UI not wired, CN-02) | M | Very high: used in most Flows |
| Filter | partial (same issue) | S after T1 | High |
| Switch with rules, fallback, many outputs | partial (4 text cases) | M | High |
| Merge: append, position, all combinations, by key, choose | exact for 5 modes | - | - |
| Merge: SQL mode, all matches, suffix clash, enrich | missing | S to M | Medium |
| Edit Fields (manual mode) | exact-plus (assignments, helpers) | - | - |
| Edit Fields raw JSON mode, dot-notation nesting, include selected | partial | S | Medium |
| Code (JS) | missing in practice (CN-01) | L | Very high |
| Code (Python) | missing | L | Low on phone |
| HTTP Request: basic request, auth bearer/basic/header | exact | - | - |
| HTTP: OAuth2 with refresh | partial (connected accounts give a token; verify refresh in engine area) | M | High |
| HTTP: digest, OAuth1, AWS, client certs, query auth | missing | M each | Low to medium |
| HTTP: pagination (next URL) | partial | - | - |
| HTTP: pagination by page/offset/cursor, stop expression, Link header | missing | M | High |
| HTTP: batching and interval | missing | S | Medium (rate limits) |
| HTTP: response format, include status, never error | missing | S | High |
| HTTP: binary download/upload any method | partial (WebDAV preset, multipart only) | M | High |
| HTTP: redirect control, proxy, ignore SSL, timeouts | partial (timeout only) | S to M | Medium |
| Remove Duplicates (same input) | exact | - | - |
| Remove Duplicates (history, by number, by date, clear) | partial (keys only, CN-04) | M | High |
| Limit | exact | - | - |
| Sort (multi-field, code) | partial | S | Medium |
| Split Out (many fields) | partial | S | Medium |
| Aggregate (many items to one list) | missing | S | High (needed for AI prompts, bulk APIs) |
| Summarize (pivot) | partial (hidden single-field group) | M | High |
| Compare Datasets (two inputs, four outputs) | partial (one input vs pasted list) | M | Medium |
| Rename Keys | exact via Edit Fields rename | - | - |
| Date & Time (add, subtract, format, between, extract, round) | partial | M | High |
| Crypto (more algorithms, sign, encodings) | partial | S | Medium |
| JWT | partial (HS256/384/512 only) | S | Low |
| Wait (interval) | exact-ish | - | - |
| Wait (until date, webhook, form), durable | missing | L | High for approvals on phone |
| Loop Over Items with loop-back and done output | missing (batcher only) | L (engine) | Very high |
| Execute Workflow with mapped inputs, picker | partial | M | High |
| Stop and Error | exact | - | - |
| Error Trigger (global error flow) | missing here (check engine audit) | M | High |
| No Operation | exact | - | - |
| XML | partial (custom parser, few options) | S | Low |
| HTML Extract by CSS selector | missing (regex only) | M | High for scraping |
| Markdown (both ways) | partial (to HTML only) | S | Low |
| Extract From File (CSV, XLSX, ODS, PDF, JSON, ICS, text) | partial (CSV hidden, PDF text, no XLSX) | M | Very high |
| Convert To File (CSV, XLSX, JSON, text, HTML, ICS to binary) | partial | M | High |
| Read/Write Files on disk | partial (text in app folder only) | M | High |
| Compression (zip, gzip) | partial, capped | S | Medium |
| Move Binary Data (JSON to/from binary) | partial (base64/text actions) | S | Medium |
| Edit Image | missing | M | Medium (Android image APIs) |
| Execute Command, SSH, FTP | missing | - | Low and risky on phone |
| Respond To Webhook | missing here (check Sparks/engine audit) | M | High |
| Form Trigger and Form node | missing | L | Medium |
| Data Table | partial (hidden JSON-file table) | M | High |
| RSS Feed Read | exact | - | - |
| GraphQL | partial (preset, single item) | S | Medium |
| Sticky Note | missing (canvas audit) | S | Low |
| Debug Helper, Evaluation | missing | - | Low |
| Node versioning | missing (ids change via aliases/converters instead) | M | Medium |
| Per-node continue on error | exact at Step level; per item only for Steps that use `perItem` | M | High |

## 6. Where KaivoFlow can beat n8n

1. **Durable on-device data table.** Replace the JSON-file table with Room (SQLite): transactions, indexes, real queries, backup. One table can serve as Data Table, dedupe memory and cursor store. n8n's data table is server-side and newer. Charge value: users keep state with no cloud.
2. **Commit-after-success dedupe.** n8n records "seen" keys when the node runs. A phone Flow can record them only after the Run succeeds (CN-04 fix) and show "N waiting, M done". Fewer lost messages.
3. **Network-aware HTTP.** The device knows if it is online, on Wi-Fi, metered or in battery saver. Add "wait until online", "only on Wi-Fi", queue-and-retry after the connection returns, and clear text for LAN devices (home automation, printers, routers) with an explicit switch. n8n servers do not have this problem space.
4. **Real sandbox for Code with phone helpers.** QuickJS runs in-process with hard memory and time limits, no network or file access by default. Add read-only helpers (`$device.battery`, `$device.network`), and an AI "write the script for me" action that runs offline-safe. n8n needs a task runner process; KaivoFlow needs none.
5. **Android storage done right.** Use Storage Access Framework and `content://` URIs for read/write files (Downloads, Drive, SD card, shared folders), stream instead of base64, camera and gallery pick as binary input. n8n reads a server disk.
6. **Durable Wait.** Use WorkManager or exact alarms so a Wait of hours or days survives process death and reboot, and "wait until charging", "until Wi-Fi", "until I tap a notification". n8n needs the server up.
7. **Approvals through notifications.** A "Wait for approval" Step that posts a notification with Approve and Reject buttons and resumes the Run. n8n needs webhooks and a public URL.
8. **Honest picker.** The picker is already ahead (typo-tolerant search, category pages, recents). Add "most used in your Flows" and show hidden-but-working Steps through search only until replaced.
9. **Unicode-first text tools.** Many users write Hindi, Odia, Arabic. Correct word counts, slugs, fuzzy compare and sort order (locale collation, `Collator`) are a visible quality edge.
10. **Per-Step dry run safety.** Test Step already ignores frozen data. Extend to "dry run" for Steps with effects (HTTP POST, Table write, Dedupe) so Test never changes state (ties into CN-04).

## 7. Recommended ordered tasks

| # | Task | Size | Acceptance test |
|---|---|---|---|
| T0 | Add `testImplementation(project(":core:expressions"))` to `:core:nodes`; make `NodeTestSupport` offer a real `KaivoExpressionEvaluator`; add a contract test that runs every executor with its default params and one sample item on the real evaluator. | S | Test fails today for `code.javascript` (proves CN-01); passes after T2. Other Steps pass or reveal bugs. |
| T1 | Wire `ParamKind.Conditions` to If, Filter, Switch rules via `withKinds` and write one evaluator reading its shape (typed operands, nested groups, `ignoreCase`, `lenientTypes`, validator operator names). Keep a reader for the old `{left, operator, right}` shape. Resolve right values per item. | M | Unit tests per operator and type; an If built through the inspector JSON routes items correctly; old saved Flow still routes the same; "007" vs 7 is not equal in strict mode. |
| T2 | Real Code runtime: define `ScriptRunner` in `:core:engine`, implement with quickjs-kt in a new Android-safe module, 10 s and 64 MB limits, no network. `CodeNode` uses it. Fix default script, per-item mode (all returned items), keep binary, set lineage. | L | Default script returns doubled items; infinite loop is stopped at the limit with a plain message; script that reads `$items` over 5 000 items finishes under 2 s on a mid phone. |
| T3 | Un-hide CSV, Group and total, Number summary, Binary tools and Local table now (flip lifecycle, update golden). Fix CN-09 (CSV per item, delimiter quoting, overflow error). | S | Picker lists them under Data and Files; CSV with `;` and a value containing `;` round-trips; 3M-character input gives an error, not an empty list. |
| T4 | Dedupe and poll: commit seen keys only after Run success, skip persisting in TestStep, show count in log. | M | Flow with Dedupe then a failing Step: re-run delivers the same items again; Test Step run leaves state unchanged. |
| T5 | HTTP: per-item errors with `perItem`, options "include status and headers", "never error", "response format" (auto, JSON, text, file), body untouched by default; text size cap. | M | 3 URLs with one 404 return 2 items plus 1 error item under Continue; PNG download keeps bytes (compare SHA-256). |
| T6 | HTTP presets: pass every auth mode to graphql, webdav and upload (or hide the unsupported ones). | S | Test per preset and mode asserts the Authorization header. |
| T7 | Compare: Unicode-safe normalisation; second real input for the baseline (keep pasted list as fallback). | S | Two different Odia strings are "changed"; same string with different punctuation is "same". |
| T8 | Move Limit, Sort, Split, Switch, Compare, Stats to typed params (`resolvedFor`). | S | Limit with `{{ $json.max }}` keeps that many items; bad value gives a named error. |
| T9 | Atomic writes in `UserFileStore.write` (temp file plus rename), one backup, and do not treat a parse error as an empty table. | S | Kill-during-write simulation leaves the old content readable; corrupt file raises an error and does not wipe data. |
| T10 | Data Table on Room replacing `logic.table`; migrate old JSON files once. | M | Counter increment from two parallel Flows reaches the exact sum; 50 000 rows read in under 200 ms. |
| T11 | Gateway: honour `followRedirects`, strip credentials on cross-host redirect, add redirect limit, text size cap, per-request SSL and proxy options, cleartext policy and plain error text. | M | Test with mock server: 302 not followed when off; Authorization absent after a cross-host hop; `http://192.168.x.x` works only with the switch. |
| T12 | Add Aggregate (items to one list) and Summarize; improve Split Out to many fields with explicit lineage. | M | Aggregate of 10 items gives one item with a 10-entry list; `$step("Split").item` resolves after Split Out with uneven arrays. |
| T13 | HTTP pagination modes: page number, offset, cursor from response, Link header, stop expression, max pages up to 100, interval. | M | Mock API with 7 pages returns all rows in each mode; stops on empty page. |
| T14 | Date node: epoch input, preset formats, zone-aware shift and diff, extract part, round. | M | "2026-03-29 + 1 day" in Europe/Berlin keeps local time across DST; epoch seconds parse. |
| T15 | Loop with loop-back and done output (engine support needed, hand to the engine audit owner). | L | 25 items in batches of 10 with a Wait inside: three passes, then done output once. |
| T16 | Extract From File (CSV, XLSX, PDF, ICS, JSON, text) and Convert To File; SAF-based read/write. | L | XLSX with 2 sheets becomes items per sheet; file written to Downloads through SAF. |
| T17 | Clean-up: remove dead `message` param, `ln` branch, QuickJS leftovers (or make them true), refresh `planned` flags, correct `docs/04` rows about Merge, Switch, pagination, Code. | S | Golden file updated in same change; docs no longer claim missing features that exist. |

Order logic: T0 first (safety net). T1 and T2 fix the two broken core promises. T3 to T9 are small and fix data loss and silent errors. T10 onward add parity and the differentiators in section 6.

## 8. Not verified / needs device test

- **If/Filter inspector (CN-02).** I traced the schema path by reading code (`LogicParams` to `toDef` to `ParamForm`), not by opening the app. Confirm on a device what the If Step shows and what JSON it saves. If a hidden bridge exists elsewhere in the inspector, CN-02 drops to P1 (operator vocabulary mismatch only).
- **Code Step (CN-01).** Concluded from the lexer, parser and AST. A one-line JVM test with `KaivoExpressionEvaluator` running the default script would confirm it in seconds (T0).
- **Authorization on redirects.** Ktor's redirect behaviour for headers on cross-host hops depends on the version in `libs.versions.toml`; not tested.
- **Cleartext error text.** Whether the OkHttp message surfaces as "CLEARTEXT communication not permitted" and how the UI shows it needs a device run.
- **Dates with offsets.** `Instant.parse` with `+05:30` style offsets depends on the JDK or Android desugaring level; test on API 26 and API 34 devices.
- **Memory.** Binary flows load a whole file as base64 text (`DeviceGateway.loadBinary`, `BinarySupport.decode`) up to 25 MB; behaviour on 2 GB RAM phones with 30 unzipped entries was not measured.
- **Indic text.** CN-08 and CN-15 follow from the regex classes in code; check with real Odia and Hindi samples.
- **Wait inside the background engine.** Wake-lock and foreground promotion exist (`core/work/FlowRunWorker.kt`, `alwayson/WakeLockManager.kt`); behaviour of a 15 minute Wait under Doze was not tested.
- **Credential refresh.** OAuth2 refresh for `auth = credential` lives in `engine/OAuth2TokenManager.kt`; not audited here (credentials area).
- **Lineage after Split, Aggregate and Loop.** Inference rules read; actual canvas link display not checked.
