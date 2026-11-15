# Audit 03: Logic, Flow-Control, Transform and Text/Data-Format Nodes

Scope: `core/nodes/src/main/kotlin/com/kaivoflow/core/nodes` files `LogicNodes.kt`, `ExtendedLogicNodes.kt`, `TransformNodes.kt`, `ExtendedTransformNodes.kt`, `TextFormatNodes.kt`, `BinaryNodes.kt`, `BinarySupport.kt`, `CompositionNodes.kt`, `CodeNode.kt`, `Conditions.kt`, `MiniJson.kt`, `TriggerNodes.kt`, `MobileTriggerNodes.kt`, `EventTriggerMatcher.kt`, `NodeCatalog.kt`, plus the engine pieces these nodes depend on (`FlowRunner`, `StepExecutor`, `Expression`, `Values`).

Audit method: read-only. The n8n clone was read only to understand behaviour. Nothing from it (code, identifiers, option names, text) is reproduced here. Every behaviour spec below is written in the auditor's own words, and any KaivoFlow implementation must be original. Third-party libraries named in section 6 are suggestions to be licence-checked at adoption time.

Vocabulary follows the app: Flow, Step, Stream, Spark (trigger), item.

---

## 1. Summary

**What KaivoFlow has in this lane:** 52 executors (40 Steps plus 12 Sparks) that cover the usual territory (If, Switch, Merge, Filter, Sort, Limit, Set fields, Date, Code, CSV/XML/HTML/Markdown, sub-flow) and a few things n8n lacks (phone-native Sparks, local SVG chart, PDF text).

**Headline verdict:** the catalog is wide but shallow. Many nodes are one "Select an operation" dropdown wrapping a handful of one-liners (Math, Text, Crypto, BinaryTools, Date, Json, Url). A small number of nodes advertise behaviour they do not have (Loop, Stop, Dedupe "across every Run"). The most important Steps (If, Switch, Merge, Set fields) have correctness bugs that single-item unit tests do not catch. Replacing the 40 Steps with about 31 better-designed ones, built on a shared typed-condition engine, a shared per-item parameter helper and a few engine changes, would make the app much stronger than n8n for a single-user phone workload.

**Counts (52 rows):**

| Verdict | Steps | Sparks | Total |
|---|---|---|---|
| KEEP | 2 | 9 | 11 |
| IMPROVE (rewrite or heavy fix, same purpose) | 21 | 3 | 24 |
| MERGE into another node | 14 | 0 | 14 |
| DELETE (replace with a differently designed node) | 3 | 0 | 3 |
| **Total** | 40 | 12 | **52** |

**Missing n8n-class nodes:** 11 new node types (section 3), plus 11 engine or framework prerequisites (section 3.2) without which several of them cannot be built.

**Top five problems (details in section 5):**

1. P0 `ctx.params()` resolves every expression against item 0 only, then Set fields, Write text, Date and Crypto "re-resolve" an already-resolved string. With more than one input item every item receives item 0's result. Tests only use one item.
2. P0 Merge reads upstream results by Step name and primary port only, and orders inputs by the upstream's output port. Re-joining the two branches of an If (the most common use) returns the "true" items twice and loses the "false" items. There is no real Input 1 / Input 2 concept although `StreamEdge.toPort` already exists.
3. P0 Condition right-hand values are never expression-resolved, so `{{ $vars.limit }}` on the right side is compared as literal text in If, Filter and the conditions used by Ai Classify.
4. P0 Three nodes lie: Loop never loops or batches (it only tags items), Stop never stops the Run, and Remove duplicates offers "Across every Run" but ignores the option. README claims "For-Each batching".
5. P0 A Step that fails on one item throws for the whole Step. Under the Continue policy the runner then replaces all output with one error item, so 99 good items are lost when item 100 fails. There is no per-item error mode.

---

## 2. Node-by-node table with verdicts

Quality scale: good, weak (works but thin or inconsistent), buggy (wrong results in realistic use), duplicate (functionality exists elsewhere).

Columns: item handling is per-item (P) or whole-batch (B). "n8n-class" means nearest equivalent by behaviour.

### 2.1 Logic and flow control (`LogicNodes.kt`, `ExtendedLogicNodes.kt`, `CompositionNodes.kt`)

| # | Node (id) | What it does today | Items / errors / edge cases | Quality | Nearest n8n-class | Verdict and target | Key defects |
|---|---|---|---|---|---|---|---|
| 1 | If (`logic.if`) | Partitions items into true / false ports using an all/any list of conditions | P. Left side resolved per item; right side NOT resolved. No type awareness (everything coerced via double or lowercase string). Empty condition list means "true" for all. Port liveness works. | buggy | If | IMPROVE: rebuild on the typed condition engine (3.3), add "ignore case" and "loose type" toggles at node level | P0-3 right operand unresolved; `In` does not split comma strings; `Matches` has no ReDoS guard; no date, array or object operators; no per-item error handling |
| 2 | Switch (`logic.switch`) | Routes by string equality to one of 4 fixed cases plus else | P. Only case-insensitive text equality; first match wins; exactly 5 ports hard-wired in the spec; `case1..case4` params | weak | Switch | IMPROVE: rule list (N rules, each a condition group), dynamic output count, fallback port, "all matching" mode, renamable ports | Needs dynamic outputs from engine (E2). Blank-field check runs after the loop. Copy-paste helper `ParamResolverLite` duplicates the condition resolver. |
| 3 | Merge (`logic.merge`) | Append, zip, cross-product or field-keyed combine of two upstream Steps | B. Recomputes inputs from `ctx.stepOutputs` keyed by Step name, port 0 only, ordered by `fromPort`. Combine does a left join on one field, first match only. Deep merge concatenates arrays. | buggy | Merge (append, by position, by fields, all combinations, choose branch, SQL) | IMPROVE: rewrite with real input ports (E2) and the mode list in 4.2 | P0-2; no join types; one key only; no unpaired handling in zip; arrays concatenate on collision (surprising); silently falls back to `ctx.input` when one branch has no recorded output; Step-name collisions (no unique-name validation) |
| 4 | Filter (`logic.filter`) | Keeps items matching the condition group | P. Same engine as If. Zero kept means downstream skipped. | buggy | Filter | IMPROVE: typed engine; optional "discarded" second output | P0-3; shares all If defects |
| 5 | Remove duplicates (`logic.dedupe`) | Drops repeated items by listed fields (or whole item) within one batch | B. Key is fields joined by `\|` (collision-prone), whole-item key depends on map key order. | buggy | Remove Duplicates | IMPROVE: modes in 4.2 incl. persistent history | `scope` param (run / day / always) is accepted and ignored, so UI promise is false; no history store; no exclude-fields; no dot-notation toggle |
| 6 | Loop (`logic.loop`) | Intended "repeat the rest of the Flow for each item" | B. Actually re-labels items with a `_loop` object and returns the same items in the same order; `batchSize` only changes the in-batch index. Throws if item count exceeds the limit. | buggy (lying) | Loop Over Items | DELETE, replace with new Loop Over Items (3.1 #1) after engine support (E3) | Does nothing a user expects; pollutes JSON with `_loop`; README claims batching |
| 7 | Wait (`logic.wait`) | Sleeps 0 to 900 s plus up to 60 s jitter | B. Holds a concurrency permit while sleeping. Clamps silently. Cannot outlive the process. | weak | Wait | IMPROVE: modes in 4.2 (interval, until time, until signal/approval) with persisted resume (E10) | Untested; permit starvation (3 permits); 900 s cap versus a 15 min default Run timeout; no "until date" |
| 8 | Stop (`logic.stop`) | "End the Run here and return these items"; status success or error | B. Error mode throws. Success mode logs the message and passes items on. | buggy (lying) | Stop and Error | IMPROVE/rename to Stop and Error: always fails the Run with message or custom error object; "stop quietly" must really halt the branch | Success mode is a no-op; downstream still runs; untested |
| 9 | When a Step fails (`logic.onError`) | Pass-through that the runner invokes with error items when a Flow-level error Step is set | B. Runner drives it after the graph settles. | good (thin) | Error Trigger | IMPROVE: promote to a real Spark `trigger.error` carrying run id, flow, failed Step, message, stack-less cause, failed items | Currently a Step masquerading as a handler; payload shape is whatever the runner builds; untested |
| 10 | Sort items (`logic.sort`) | Stable sort on one field; auto / number / text / date | B. Auto-detects numeric when both parse. Null handling flips in descending (nulls end up first). Dates only via ISO instant. Text compare is case-insensitive, not locale-aware. | weak | Sort | IMPROVE: multi-field keys, random order mode, locale collation, nulls-last toggle | Single field only; no random; `date` parse duplicated elsewhere |
| 11 | Limit items (`logic.limit`) | first N, last N, skip N, slice, random sample | B. `count`/`offset` read from raw params so expressions are ignored (default 10 used). | good | Limit | KEEP: reduce to first / last / skip; sample moves to Sort-random; resolve numeric params | Expression params silently ignored; "slice" duplicates skip + first |
| 12 | Compare lists (`logic.compare`) | Diffs input vs a pasted JSON baseline by one key; returns ONE item holding rows and a summary | B. Fuzzy mode strips every non a-z0-9 char so all Hindi, Odia or CJK strings normalise to empty and compare as equal. Rows missing the key are dropped silently; duplicate keys collapse. | buggy | Compare Datasets | IMPROVE: rebuild as Compare Datasets with two inputs and four output ports (4.2) | Baseline can only be pasted JSON; one output item is not routable; non-Latin false "same" |
| 13 | Local table (`logic.table`) | Key-value rows persisted as one JSON file per table via `DeviceGateway.writeFile` | B/P mix. Whole file read and rewritten per Step run; concurrent branches race; 5000 row cap; values only by key. | weak | Data Table | DELETE, replace with Data Table (3.1 #4) backed by the existing Room `DataTable` repository | No query, no filter, no update-where; lost updates under concurrency; returns a fake row when list is empty |
| 14 | Run details (`logic.run_info`) | Emits run id, flow, mode, attempt, time, timezone, item count | Single item output; ignores incoming items (drops them). | weak | none (expression globals) | MERGE into Edit Fields by exposing the same data in the `$run` scope | Replaces the item list with one new item, which surprises users |
| 15 | Pass through (`logic.noop`) | Passes items; optional note written into each item | P. | good | No Operation | KEEP: drop the note feature (Edit Fields and canvas notes do this) | Note param is redundant |
| 16 | Quick chart (`transform.chart`) | Renders bar / line / area SVG and stores it as an attachment | B. Hard-coded dark theme; 200-point cap; negative values give a negative rect height (invalid SVG); label truncated to 14 chars. | weak | QuickChart-style node | IMPROVE (low priority): negatives, themes, pie, axis titles, multiple series; move to the formats group | Negative values break output |
| 17 | Random value (`transform.random`) | uuid, number, random string, pick-one, shuffle | P (shuffle B). | duplicate | Crypto (generate), Sort (random), expressions | MERGE: uuid/string into Crypto; shuffle/sample into Sort/Limit; number and pick become expression helpers | uuid duplicated in 3 places; `Math.random` used in sibling Steps |
| 18 | Run another Flow (`logic.subflow`) | Calls another saved Flow through `FlowInvoker`, optionally wraps results | B. Sends at most `maxItems` (default 100) and silently truncates without a log. No per-item mode; no way to pass named inputs; no timeout; depth guard only. | weak | Execute Sub-workflow | IMPROVE: modes in 4.2 | Silent truncation; error propagation undefined; untested for failure and depth |
| 19 | Service connector (`saas.connector`) | Generic HTTP call with pagination and path extraction | P. Forwards the same auth headers to every next-page URL even if it points to another host. Output count in the log is cumulative across source items. | duplicate | HTTP Request (pagination option) | MERGE into the HTTP Request Step (HTTP lane): pagination and response-path become HTTP options | Credential leak risk on cross-host paging; duplicates auth/retry/URL code that exists in HttpNode |
| 20 | Only new items (`logic.poll`) | GET a feed, emit items not seen before, state kept in a text file | B. Default state file name is the same for every Poll Step in every Flow. Linear scan list capped at 2000. | duplicate | Schedule Spark + HTTP Request + Remove Duplicates (cross-run) | DELETE as a node (ship as a template/recipe), functionality moves into Remove Duplicates persistent mode | Shared default state file causes cross-flow suppression; duplicates HTTP code |

### 2.2 Transform (`TransformNodes.kt`, `ExtendedTransformNodes.kt`, `CodeNode.kt`)

| # | Node (id) | What it does today | Items / errors / edge cases | Quality | Nearest n8n-class | Verdict and target | Key defects |
|---|---|---|---|---|---|---|---|
| 21 | Set fields (`transform.set`) | Adds or overwrites fields from a key/value list | P. Values are resolved by `ctx.params()` against item 0, then "re-resolved" per item (a no-op). | buggy | Edit Fields (Set) | IMPROVE into Edit Fields (4.2): typed values, JSON mode, include/exclude, dot-notation, binary pass-through | P0-1 every item gets item 0's values; no types (everything is what the expression returns); top-level keys only; no field removal |
| 22 | Rename or remove fields (`transform.fields`) | rename, remove, keep-only on top-level keys | P. Order is keep-only, then remove, then rename; no nested paths. | weak | Rename Keys, Edit Fields | MERGE into Edit Fields | No dot paths; comma-split lists; duplicates Edit Fields |
| 23 | JSON (`transform.json`) | parse text to fields, stringify, pick nested | P. "stringify" ignores its source and wraps the whole item as `json`. "parse" with an array drops the other item fields. Uses `JsonLite`, a third JSON parser. | weak | Edit Fields + Split Out + Extract From File (JSON) | MERGE: parse/stringify become expression helpers and Extract/Convert; pick becomes Split Out | Source ignored; array parse loses context; untested errors |
| 24 | Write text (`transform.template`) | Writes a templated string into a field | P. Same item-0 resolution bug as Set fields. Default output field `text`. Used by 3 starter templates. | buggy | Edit Fields (string value) | MERGE into Edit Fields; keep an id alias for the starter templates | P0-1; no tests |
| 25 | Date and time (`transform.date`) | parse, format, shift, diff, timezone | P (but item-0 bug for `source`). Shift always uses UTC arithmetic. Month diff is `ms / 30 days`. Parse supports only ISO instant, ISO date, local date-time. Throws with empty step id on bad date. | buggy | Date & Time | IMPROVE: 7 operations in 4.2, zone-aware arithmetic, flexible parse | P0-1; DST wrong across zones; no epoch input, no custom input pattern, no round/extract/current-date/relative |
| 26 | Group and total (`transform.aggregate`) | Group by one field, count, sum and average one field | B. Count is Int, total Double. Blank group and blank sum returns `{count}` only. | weak | Summarize (the name "Aggregate" in n8n is a different node) | DELETE, replaced by Summarize and a true Aggregate (3.1 #2, #3) | Single group key, single sum field; name collides with n8n's meaning of Aggregate |
| 27 | Crypto and hash (`transform.crypto`) | sha256/md5/sha1, hmac-sha256, base64 encode and decode, uuid | P (item-0 bug on `input`). Base64 decode failure silently returns "". Duplicates `CryptoMath` helpers. | weak | Crypto | IMPROVE: algorithm list, hex/base64 output, key as secret, random string/uuid, base64 moves to Convert; no silent failure | P0-1; silent decode failure; only HMAC-SHA256; untested |
| 28 | Code (`transform.code`) | Runs JavaScript in the QuickJS sandbox, once for all items or once per item | Per-item mode keeps only the first item of any returned array. Binary attachments and `pairedIndex` are dropped on output. `ctx.params()` also interpolates `{{` inside the script text. | weak | Code | IMPROVE: keep binaries, honour per-item contract, no interpolation of the script, capture `console` output into the Run log, error with line number | Script text corrupted if it contains `{{ }}`; binary lost; no log capture |
| 29 | Math (`transform.math`) | 19 single operations on two operands | P. Option list lacks `ln` though code has it; `random` uses `Math.random`; one operation per Step means chains of Steps. | duplicate | expression (QuickJS has `Math`) / Edit Fields | MERGE into Edit Fields via an expression helper picker | 19-option dropdown is the anti-pattern; dead `ln` branch |
| 30 | Text tools (`transform.text`) | 30 string operations in one dropdown | P. `words()` uses `[^A-Za-z0-9]` so snake/camel/word-count/slug return nothing for non-Latin text. Regex has no flags and no timeout. Base64 and html-escape duplicated elsewhere. | duplicate | expression string helpers | MERGE into Edit Fields via a string-helper picker; keep regex extract/replace as expression helpers | Non-Latin text broken (Hindi/Odia users); unbounded regex |
| 31 | Number summary (`transform.stats`) | count, sum, min, max, mean, median, stddev, variance for one field | B. Unknown op returns null then is removed. Empty list returns 0 for min/max (misleading). | duplicate | Summarize | MERGE into Summarize | Untested; one field only |
| 32 | Split items (`transform.split`) | Splits a list field, or text, or batches the whole list | P for array/text, B for chunks. Adds `splitIndex` always. Chunk mode puts the whole batch into one item. | weak | Split Out | IMPROVE into Split Out (4.2); chunking goes to Loop Over Items, text split becomes an expression | `chunkSize` raw param (expressions ignored); untested; keeps all other fields or none only |
| 33 | URL tools (`transform.url`) | Parse a URL to parts or build one with query | P. Port is a Double; `-1` for no port. | duplicate | none (expression / HTTP query) | MERGE into expression helpers and HTTP query builder | Untested; no relative resolve |
| 34 | CSV (`transform.csv`) | Parse CSV text to items, build CSV text | B. Hand-rolled RFC 4180 parser. No BOM strip; quoting on build checks only `,` even when the delimiter is `;` or tab; >2 MB returns zero rows silently; duplicate header names overwrite silently; all values strings. | buggy | Extract From File (CSV) / Convert To File (CSV) | MERGE into Extract From File and Convert To File | BOM header corruption (bank exports); delimiter quoting bug; silent size drop; no header-less names option; no formula-injection guard |
| 35 | XML (`transform.xml`) | XML to nested map and back | P. Hand-written parser (no entity expansion, so no XXE). Writing: attribute NAMES from map keys are not sanitised (markup injection). Numbers stay strings. Mixed content order lost. | weak | XML | IMPROVE: library parser, options in 4.2, sanitise attribute names | Attribute-name injection on build; no array coercion option; no namespace handling |
| 36 | HTML tools (`transform.html`) | Regex-based text, links, images, one attribute, title | P. Regex over HTML; no CSS selectors; entity decode uses `toChar()` so code points above 0xFFFF break. | weak | HTML (extract) | IMPROVE: parser library with CSS selectors; return types text/html/attribute/value; arrays | Fragile on nested or malformed markup; cannot target an element |
| 37 | Markdown (`transform.markdown`) | Markdown to HTML, HTML/Markdown to plain text | P. Hand-written subset (no nested lists, tables, autolinks). `[x](javascript:...)` links and image sources are emitted unfiltered. | weak | Markdown | IMPROVE: spec-compliant library, URL scheme allow-list, HTML to Markdown direction | Unsafe URLs; missing table and nested list support |
| 38 | Calendar invite (`transform.ical`) | Builds an .ics string | P. No 75-octet line folding; always UTC; `take(2000)` after escaping can cut an escape pair; random UID every run; no all-day or attendees. | weak | iCalendar | IMPROVE: fold lines, TZID, all-day, stable UID option, alarm, attendees, attach as file | Not valid for long lines; duplicates on re-import |
| 39 | Binary tools (`transform.binary`) | base64/text to attachment, attachment to base64/text/hash/info, gzip/gunzip, zip/unzip | P. Bytes travel as base64 strings through `DeviceGateway` (about 4/3 memory, 25 MB cap). Zip name sanitising strips one leading `../` only and entries silently truncate at 30; entries with equal base names overwrite each other as binary keys. | duplicate (10 operations in one) | Extract From File, Convert To File, Compression, Crypto | MERGE: split across those three nodes plus Crypto | Memory pressure; key collisions; zip-bomb budget is per entry not total |
| 40 | Upload a file (`transform.binary_upload`) | Sends one attachment as multipart/form-data | P. Own URL/headers/timeout code. | duplicate | HTTP Request (binary body) | MERGE into the HTTP Request Step (HTTP lane) | Untested; duplicates HTTP code |

### 2.3 Sparks (`TriggerNodes.kt`, `MobileTriggerNodes.kt`, `EventTriggerMatcher.kt`)

All executors are pass-throughs (`PassThroughTrigger`); behaviour lives in `:core:work` and `EventTriggerMatcher`, which is pure and fail-closed (good design).

| # | Spark (id) | Quality | Nearest n8n-class | Verdict | Notes |
|---|---|---|---|---|---|
| 41 | Manual (`trigger.manual`) | good | Manual Trigger | KEEP | Add pinned sample input per Spark so Test Step works without a real event |
| 42 | On a schedule (`trigger.schedule`) | weak | Schedule Trigger | IMPROVE | Only 6 time zones offered; no cron expression, no seconds interval, no multiple rules per Spark; time validated by regex only |
| 43 | Incoming webhook (`trigger.webhook`) | weak | Webhook | IMPROVE | No response control (needs a Respond-to-Webhook Step, cross-lane); shared-secret only; method list minimal |
| 44 | Notification arrives (`trigger.notification`) | weak | none | IMPROVE | `textMatches` is shown in the UI but `validate()` blocks any Flow that sets it ("not available yet"); needs the safe-regex service (E9) |
| 45 | SMS arrives (`trigger.sms`) | good | none | KEEP | Contains-filters only; adopt shared safe regex later |
| 46 | Share sheet (`trigger.share`) | good | none | KEEP | Image/any types accepted by spec, matcher only accepts text (`types` image/any never match for non-text payloads); verify with the platform lane |
| 47 | Battery low (`trigger.battery`) | good | none | KEEP | Name says "low" but supports charging events |
| 48 | Geofence (`trigger.geofence`) | good | none | KEEP | Hysteresis (115 %/85 %) is a good touch |
| 49 | Bluetooth (`trigger.bluetooth`) | good | none | KEEP | |
| 50 | Wi-Fi (`trigger.wifi`) | good | none | KEEP | |
| 51 | Headset (`trigger.headset`) | good | none | KEEP | Bluetooth, Wi-Fi, headset and boot could be one "Device event" Spark in future (P2 palette simplification) |
| 52 | Phone restarted (`trigger.boot`) | good | none | KEEP | |

**Adjacent nodes outside this lane's files** (not counted above): Token tools (JWT, keep), One-time code (TOTP, keep) and PDF text (fold into Extract From File) live in `SecurityNodes.kt`.

### 2.4 Cross-cutting observations about existing nodes

- **Three parameter-resolution styles** are mixed: `ctx.params()` (item-0 only, source of P0-1), raw `ctx.step.params` plus `ctx.resolve*(index)` (correct, but numeric params read from the raw map ignore expressions), and manual `ParamResolver`. The same Step often mixes two styles.
- **Item pairing is absent.** `FlowData.pairedIndex` is only set in SubFlow. Nothing maintains lineage, so a `$item` link back to the source item cannot work after Split, Filter, Merge or Aggregate.
- **Number typing is inconsistent**: counts are Int in Aggregate, Split and Loop but Double in Stats, Math and Run details.
- **Output shape surprises**: Loop adds `_loop`, Split always adds `splitIndex`, Run details and Stats drop incoming items. n8n-style nodes keep incoming items unless the node is explicitly an aggregator.

---

## 3. Missing n8n-class nodes with behaviour specs

### 3.1 Missing node types (11)

Each spec lists modes, options, outputs and edge cases so an implementer can write an original version. Names are KaivoFlow's own.

**#1 Loop Over Items (batch processor)**
- Purpose: process a large item list in fixed-size batches, running a sub-chain per batch, then continue with everything.
- Inputs: one list. Outputs: port "each batch" (current batch) and port "done" (all collected results).
- Options: batch size (default 1), "reset" toggle to restart on re-entry, max iterations safety limit, optional delay between batches (mobile rate limiting).
- Behaviour: first entry slices the list; the body chain is wired back into the Step's input; on each re-entry the node stores the body result, emits the next batch; when the input is exhausted it emits all stored results to "done". Expose iteration index, batch count and "has more" flag in the scope.
- Edge cases: empty input skips both outputs; body producing zero items still counts as an iteration; cancellation mid-loop must persist nothing half-done; cap total iterations by the Flow limit; nested loops need per-Step state keyed by Step id.
- Needs engine support (E3). Until then ship as an "each batch" mode of Run another Flow.

**#2 Summarize**
- Purpose: pivot-style statistics over the whole list or per group.
- Config: list of (field, operation) pairs; operations: count, count non-empty, count unique, sum, average, min, max, median, standard deviation, variance, first, last, join with separator, collect into list, percentile.
- Group-by: zero or more fields. Output: one item per group (default) or one item holding all groups. Option to skip items lacking group keys; option to continue when a field is missing.
- Edge cases: numeric strings parse; non-numeric values skipped with a counter in the log; stable group order of first appearance; date min/max by instant.

**#3 Aggregate (collect)**
- Purpose: combine items into lists inside one item.
- Modes: "selected fields to lists" (each chosen field becomes an array, optional rename) or "everything into one list" (each item's JSON becomes an entry in a named field).
- Options: include all, only listed, or all except listed fields; merge nested lists flatten toggle; keep null/missing values toggle; carry binaries (all or unique only); dot-notation toggle; destination field name.
- Edge cases: missing field fails with a clear message unless "keep missing" is on; empty input yields no output (skip) unless "always emit" is on.

**#4 Data Table (persistent rows)**
- Purpose: local, queryable, durable tables that Flows share. Replaces Local table.
- Backing: the existing Room `DataTable`/`DataTableRow` repository (`core/data`), exposed to the node through a small `TableGateway` interface in `StepContext` so the module stays Android-free.
- Operations: get rows (by filter, sort, limit), insert, update where, upsert by key columns, delete where, "row exists" and "row does not exist" routing outputs.
- Filters: reuse the typed condition engine (3.3). Column types: string, number, boolean, date with coercion and validation.
- Edge cases: atomic write per Step run (transaction); concurrency safe; row cap configurable with a warning at 80 %; schema mismatch is an error naming the column; return inserted ids.
- Also covers key-value "static data" use (a table with one key column), replacing Table and Run-details-style counters.

**#5 Convert To File**
- Purpose: turn items into a binary attachment.
- Formats: CSV (delimiter, header row toggle, quote policy, formula-injection guard), JSON (one document or one per item, pretty toggle), plain text (from a field), HTML (table), XML, iCalendar, Base64 text to binary, and later spreadsheet (xlsx via zip and xml, no heavy library).
- Options: file name, MIME type, binary field name, encoding, include BOM toggle.
- Behaviour: one output item carrying the attachment (default) or one per input item. Large outputs stream to a file URI, never through a base64 string (E11).

**#6 Extract From File**
- Purpose: read an attachment into items or text.
- Formats: CSV (delimiter, header row, BOM strip, skip lines, max rows, type inference toggle), JSON, XML, HTML to text, plain text, Base64 string, PDF text, spreadsheet (xlsx, first sheet or named sheet, header row) and iCalendar events.
- Behaviour: the CSV, JSON and spreadsheet readers emit one item per row; others emit one item with a field. Binary field name is selectable; first attachment is the default.
- Edge cases: charset detection (UTF-8, UTF-16 BOM, Windows-1252 fallback); ragged rows; duplicate header names get numeric suffixes; empty file yields zero items (not an error) with a log line; size caps report truncation explicitly.

**#7 Compression**
- Purpose: zip or gzip attachments and expand them.
- Operations: compress many attachments into one zip (name template), gzip one attachment, expand a zip into one attachment per entry (output field per entry or one item per entry), gunzip.
- Safety: total uncompressed budget and entry-count budget across the archive (not per entry), compression-ratio check, path sanitisation that removes every traversal segment, collision-free naming.

**#8 Called by another Flow (Spark)**
- Purpose: formal entry point for Run another Flow.
- Config: optional declared input fields (name, type) used to validate the call and to drive a mapping form in the caller; "accept any data" option.
- Behaviour: the runner starts the Flow at this Spark with the caller's items; validation failure reports a clear error to the caller. A Flow can have several, selected by name.

**#9 Run Data (annotate a Run)**
- Purpose: attach searchable key/value labels to the current Run (customer id, file name, outcome) so the history screen can filter by them.
- Config: list of (key, value) with expressions; limits (for example 10 keys, 256 chars each). Pass-through for items.

**#10 Test Data**
- Purpose: development helper. Modes: generate N sample items from a field schema (name, email, number range, date, uuid, text), and "raise an error" with a chosen message and failing-item index for testing error routes.
- Seeded randomness for reproducible tests.

**#11 Respond to Webhook (coordinate with the Spark/network lane)**
- Purpose: control the HTTP response of an incoming webhook Flow: status code, headers, body (first item, all items as JSON, text, binary, redirect, no data) and "respond now, continue running".
- Listed here because it is flow-control behaviour; implementation depends on the on-device webhook server in `:core:network`/`:core:work`.

### 3.2 Engine and framework prerequisites (not new nodes, but blockers)

| Id | Need | Why |
|---|---|---|
| E1 | `StepContext` helper that resolves a param for item i with typed accessors (`int`, `bool`, `list`, `enum`) and a spec-driven "per item or once" flag; deprecate `ctx.params()` | Fixes P0-1 and numeric params ignoring expressions |
| E2 | `NodeSpec.inputs` plus dynamic outputs from params (`outputsFor(params)`); engine passes `inputs: List<List<FlowData>>` using `StreamEdge.toPort` | Real Merge, Compare Datasets, dynamic Switch, Loop |
| E3 | Loop re-entry support in `FlowRunner` (back-edge allowed only into Loop Over Items, bounded iterations) or a sub-run abstraction | Today the validator rejects every cycle and each Step runs exactly once per Run |
| E4 | `StateStore` and `TableGateway` in `StepContext` (per Flow and per Step namespaces) | Persistent Remove Duplicates, Data Table, Run Data |
| E5 | Engine-maintained item lineage (`pairedIndex`, `$item` back-reference) | Expression correctness after Split, Merge, Aggregate |
| E6 | Record Step outputs per output port, keyed by Step id (and name for the expression API) | Merge, expressions `$step("X")` on branching nodes |
| E7 | Unique Step name validation (rename on duplicate) | `stepOutputs` and `$step()` are keyed by name; duplicates silently overwrite |
| E8 | Node alias and migration table (`legacy id` to new id plus a param converter), run at load and import | Starter templates and saved Flows use `transform.template`, `saas.connector`, etc. |
| E9 | Shared safe-regex service (length cap, deadline-checking input wrapper) | ReDoS in Conditions and Text; unlocks the blocked Notification `textMatches` |
| E10 | Run suspension and resume (persist Run state, resume via WorkManager/alarm or user action) | Wait beyond a few minutes, approvals |
| E11 | Binary streaming through file URIs instead of base64 strings | 25 MB attachments cost over 100 MB of heap today |
| E12 | Per-item error mode in the executor helper (`onItemError`: stop / continue with error field / route to error port) | P0-5; matches how n8n-style flows are built |

### 3.3 Shared typed condition engine (replaces `Conditions.kt` semantics)

Used by If, Switch, Filter, Data Table filters and Remove Duplicates "compare".

- Each condition is `left`, `type`, `operator`, `right`, with per-condition "ignore case". Types: text, number, boolean, date-time, list, object.
- Text: equals, not equals, contains, not contains, starts with, ends with, matches (safe regex), not matches, is empty, is not empty. Number: equals, not equals, greater, greater or equal, less, less or equal, between, is empty. Boolean: is true, is false. Date-time: equals, before, after, before or equal, after or equal, between (zone-aware). List: contains, not contains, length equals / greater / less, is empty. Object: has key, is empty. Universal: exists, does not exist.
- Strict versus loose typing toggle: strict fails the item (routed through E12) when types mismatch; loose coerces like today.
- Both sides are resolved per item. Combinator all/any, plus nested groups (one level is enough on a phone).
- Evaluation is pure and unit-testable; operator tables are data so the inspector can render them.

---

## 4. Proposed final node set

### 4.1 Target catalog for this lane (31 Steps, 14 Sparks)

| Group | Nodes |
|---|---|
| Sparks (14) | Manual, On a schedule, Incoming webhook, Notification, SMS, Share sheet, Battery, Geofence, Bluetooth, Wi-Fi, Headset, Phone restarted, **Called by another Flow (new)**, **Error Spark (promoted from "When a Step fails")** |
| Flow control (13) | If, Switch, Filter, Merge, **Loop Over Items**, Wait, **Stop and Error**, No Operation, Run another Flow, **Compare Datasets**, **Remove Duplicates**, Sort, Limit |
| Data shaping (7) | **Edit Fields**, **Split Out**, **Aggregate**, **Summarize**, Code, **Date and Time**, Crypto |
| Formats and files (8) | **Convert To File**, **Extract From File**, **Compression**, HTML, XML, Markdown, iCalendar, Chart |
| State and debug (3) | **Data Table**, **Run Data**, **Test Data** |
| Cross-lane (not counted) | Respond to Webhook, JWT (Token tools), One-time code, HTTP Request (absorbs Service connector, Upload a file, Poll) |

Bold means new or rewritten. Net effect: 40 Steps become 31, while capability goes up (joins, persistent state, loops, summaries, selectors, safe parsers).

### 4.2 Rewrite behaviour specs (short)

Written so implementers can design originals; options are described functionally.

**If / Filter / Switch.** Use 3.3. If has true/false ports. Filter has "kept" and optional "discarded" port. Switch holds an ordered rule list; output count equals rule count (plus a fallback port when enabled); modes: first match only, or every matching rule (an item may be copied to several ports); optional port renaming; ignore-case toggle; per-rule typed conditions. Rules can alternatively be "expression picks the port number".

**Merge.** Two (later N) real input ports. Modes:
1. Append: all of input 1 then input 2 (and so on).
2. By position: pair item i with item i; option to keep unpaired leftovers; clash policy (later input wins, earlier wins, or prefix names).
3. All combinations: cross-product.
4. By matching fields: key pairs can have different names per side; join kinds: inner (matches only), only-unmatched, full outer, enrich input 1 (left join), enrich input 2 (right join); multiple-match policy: emit every match or first only; output may come from both merged, input 1 only or input 2 only; dot-notation toggle; clash policy.
5. Pick one input: pass through the chosen input once all inputs finished; or emit one empty item.
6. Optional later: query mode using an in-memory SQLite for power users.
Arrays should replace on clash (not concatenate) unless the user chooses "concatenate lists".

**Edit Fields.** Per-item. Two modes: field list (name, type text/number/boolean/list/object/date, value expression) and raw JSON object. Include policy: keep all, keep none, keep selected, drop selected. Dot-notation writes nested values. Options: keep binaries, ignore type conversion errors. Absorbs Rename or remove fields, Write text, Run details, Math, Text tools, URL tools through an expression helper picker.

**Split Out.** For each chosen list field produce one item per element (several fields zip by position). Include policy for the rest of the item: none, all, or selected. Destination name; binaries toggle. Scalar values wrap as one item.

**Remove Duplicates.** Operations: (a) drop repeats within the current batch, (b) drop items seen in earlier Runs (persistent history), (c) clear the history. Compare: all fields, all except listed, or selected fields. Persistent logic: value is new; value is higher than any previous; date is later than any previous. History scope: Flow or this Step; history size cap with oldest eviction; stable hash key built from canonical JSON (sorted keys) rather than `|` joining.

**Sort.** Modes: by fields (ordered list of field plus direction plus type auto/number/text/date, nulls first/last, locale collation toggle), random shuffle, or by expression. Stable.

**Compare Datasets.** Two inputs, key pairs (names may differ per side), optional fuzzy compare that normalises case, punctuation and Unicode (NFKC) without destroying non-Latin letters, skip-fields list. Four outputs: only in A, same, different (with before/after and changed-field list), only in B. Warn on duplicate keys instead of dropping them. Options: prefer A or B on clash, include unchanged diff fields.

**Date and Time.** Operations: current date/time, add, subtract, round (to unit), extract part (year, quarter, month, week, day, weekday, hour, minute, second, ISO week), format (preset or custom pattern, locale), difference (unit list; calendar-aware months/years), convert zone, parse (explicit input pattern, epoch seconds or milliseconds, relative words such as "tomorrow" optional). Zone-aware arithmetic (days across DST keep local time). Output field name; keep original toggle. Invalid input is an item error, not a Run crash.

**Wait.** Modes: after interval (seconds to days), until a date/time (zone aware), until user action (notification action "Continue", with timeout and a "timed out" port), until an incoming signal from another Flow. Long waits use E10 and must not hold a concurrency permit.

**Stop and Error.** Always fails the Run: message or custom error object; optionally marks "stopped quietly" as a separate Run status. A true "end this branch" is just a Step with no outgoing Streams.

**Run another Flow.** Source: saved Flow chosen from a list (not a pasted id). Modes: run once with all items, or once per item (optionally concurrent up to N). Input mapping: named values mapped to the callee's declared fields. Wait for completion toggle (fire-and-forget is allowed). Returns the callee's last Step items, per call, with pairing. Timeout, depth guard, and a clear error that names the callee Step when it fails. No silent truncation (warn and fail if over the limit).

**Code.** Same two modes plus: return value normalisation that preserves binaries and pairing, `console.log` captured into the Run log, error shows script line, memory and time limits surfaced in settings.

**XML.** Both directions with options: explicit arrays always, ignore attributes, attribute and text key names, trim, namespace handling toggle, numeric/boolean coercion toggle, root element and pretty print on build.

**HTML.** Extract by CSS selector, each rule picks: text, inner HTML, attribute or form value; return a single value or array; trim and clean toggles. Optional "generate HTML from template" operation.

**Markdown.** Markdown to HTML (safe-mode URL allow-list, optional raw-HTML escaping, tables, nested lists) and HTML to Markdown.

**iCalendar.** One or many events; start/end or start plus duration; all-day; zone; location; description; attendees; reminder; stable UID derived from a key; output as attachment.

**Crypto.** Hash (SHA-1/256/384/512, MD5 flagged legacy), HMAC with same set, output hex or base64, random string and UUID generation, constant-time compare option. Base64 encode/decode moves to Convert/Extract and expression helpers.

**Chart.** Negative-safe axes, multiple series, theme colours from tokens, pie option.

---

## 5. Findings

Severity: P0 = wrong results or broken promise in common use, fix before adding features; P1 = significant correctness, security or robustness defect; P2 = quality and maintainability.

### P0

| Id | Finding | Where | Evidence and effect | Fix |
|---|---|---|---|---|
| P0-1 | Multi-item expression bug | `StepContext.params()` (engine), used by SetFieldsNode, TemplateNode, DateNode (source), CryptoNode (input) and others | `params()` calls `ParamResolver.resolveParams(step, scope())` with item index 0. The nodes then loop over items and call `resolveValue` again on an already-resolved string. Every item receives item 0's values. Existing tests (`set fields resolves expressions per item`) use a single item so the bug is invisible. | E1 helper; replace all `ctx.params()` uses; add a contract test (section 5, Test gaps) |
| P0-2 | Merge reads wrong data | `LogicNodes.kt` MergeNode lines 119-120 | Uses `ctx.stepOutputs[stepName]` (primary port only) and sorts incoming edges by `fromPort`. If true and If false both feed Merge, both groups equal port 0 of the same Step: "false" items vanish. Input order is undefined. `StreamEdge.toPort` is never used. | E2 and E6, rewrite Merge |
| P0-3 | Right operand never resolved | `Conditions.kt` line 51 (`val rightValue = right`) | Left side may contain expressions; right side is the raw stored value. `{{ $vars.limit }}` on the right is compared as text. Same engine feeds If, Filter and Ai Classify. | Resolve both sides per item; typed engine (3.3) |
| P0-4 | Nodes that do not do what they say | LoopNode, StopNode, DedupeNode, README | Loop returns the same items with `_loop` metadata; Stop (success) passes items downstream; Remove duplicates `scope` option ignored. README lists "For-Each batching". | Delete or rebuild (sections 2 and 3); correct README and PARITY.md |
| P0-5 | One bad item destroys the whole Step output | `FlowRunner.runWithRetries` plus executors that throw `StepExecutionException` per item | Under Continue, a thrown error replaces all output with one error item. With 100 items, 99 results are lost. No per-item mode exists. | E12 executor helper that collects per-item errors; error port; keep successes |

### P1

| Id | Finding | Where | Detail | Fix |
|---|---|---|---|---|
| P1-1 | Switch is a toy | SwitchNode | 4 fixed text cases, no operators | Rule-based Switch with dynamic outputs (E2) |
| P1-2 | Unbounded regex | `Conditions.kt` Matches, TextNode regex ops | A catastrophic pattern blocks a non-cooperative thread until the Run timeout; the Spark matcher already disables regex for this reason | E9 safe regex; reuse everywhere |
| P1-3 | Notification `textMatches` blocks the Flow | `NotificationTriggerNode.validate` | UI offers the field, validator returns a blocking error | Enable with E9 or remove the field |
| P1-4 | Poll state shared across Flows | PollChangesNode default `poll-state.txt` | Two Poll Steps (any Flow) with the default key suppress each other's items | Namespace state by Flow id and Step id (E4); delete node |
| P1-5 | Auth forwarded across hosts while paging | ServiceConnectorNode | `connectorHeaders` built once and sent to the follow-up URL even if it is another host | Strip credentials on host change (do in HTTP Request); delete node |
| P1-6 | SubFlow silently truncates input | CompositionNodes.kt line 57 | `sendItems.take(limit)` without a log | Fail or warn explicitly |
| P1-7 | CSV defects | CsvLite | BOM not stripped, delimiter not considered when quoting on build, files over 2 MB return zero rows without error, duplicate header names collapse, `\r`-only line ends unsupported | Library or fixed parser plus RFC 4180 test corpus |
| P1-8 | Non-Latin text mishandled | CompareNode fuzzy normaliser, TextNode `words`/slug/snake/camel | Regexes restrict to ASCII, so Hindi, Odia, Arabic or CJK text becomes empty (false "same", empty slugs, zero word counts) | Use Unicode-aware classes (`\p{L}\p{N}`), NFKC normalisation |
| P1-9 | Date arithmetic and parsing | DateNode | UTC-only shifts across DST, month diff by 30 days, limited parse formats, thrown error has empty step id | Rewrite on `java.time` with zone-aware APIs |
| P1-10 | Unsafe URLs in Markdown output | MarkdownLite | `javascript:` and `data:` URLs emitted in anchors and images | Allow-list schemes (http, https, mailto, tel) |
| P1-11 | XML build attribute-name injection | XmlLite.writeElement | Attribute names come from map keys unsanitised | Validate names against the XML name grammar |
| P1-12 | Chart negative values | ChartSvg | Negative bar height makes invalid SVG | Compute baseline at zero and handle sign |
| P1-13 | Code Step loses binary and re-interpolates the script | CodeNode, `ctx.params()` | Script text containing `{{ }}` is rewritten before execution; returned items drop attachments and pairing; per-item mode keeps only the first returned element | Read the script raw; carry binaries; define contract |
| P1-14 | Binary pipeline memory and archive safety | BinaryToolsNode, BinarySupport, DeviceGateway | base64 strings for up to 25 MB; unzip sanitises one leading `../`, truncates at 30 entries silently, no total budget, key collisions overwrite | E11; Compression spec in 3.1 #7 |
| P1-15 | Numeric params ignore expressions | Limit (`count`, `offset`), Split (`chunkSize`), Random (`length`), Math (`precision`), Chart (size), ServiceConnector (`maxPages`, retries) | Read from raw step params | E1 typed accessors |
| P1-16 | Step names not unique | `FlowValidator`, `stepOutputs` keyed by name | Duplicate names overwrite each other's outputs and make `$step("Name")` ambiguous | E7 |
| P1-17 | Local table races and cap | TableNode | Whole-file read-modify-write per Step; parallel branches (runner allows 3 at once) lose updates; 5000-row cap | Data Table on Room (transactions) |
| P1-18 | Wait starves the runner | WaitNode inside `permits.withPermit` | A sleeping Step holds one of 3 permits; three waits block every other branch; cap 900 s versus 15 min Run limit | Release the permit while waiting; E10 for long waits |
| P1-19 | Schedule Spark time zone list | `TriggerSpecs.timeZones` | Only 6 zones; no cron expression; no sub-minute interval | Full IANA picker, cron mode, multiple rules |
| P1-20 | Starter templates and saved Flows depend on nodes slated for removal | `FlowTemplates.kt` uses `transform.template` and `saas.connector` | Removing nodes without migration breaks imports and `.kaivoflow` bundles | E8 migration map and bundle version bump |

### P2

| Id | Finding | Detail |
|---|---|---|
| P2-1 | Copy-paste: comma-list parsing | `split(',').map { it.trim() }.filter { it.isNotEmpty() }` appears in 12 places across the node files |
| P2-2 | Copy-paste: base64, hashing, UUID | Base64 in CryptoNode, TextNode, BinaryToolsNode, BinarySupport; hashing in CryptoNode and `CryptoMath`; UUID in Crypto, Random, iCal |
| P2-3 | Copy-paste: escaping | HTML or XML escape duplicated in TextNode, XmlLite, MarkdownLite, ChartSvg and `HtmlLite.decode` |
| P2-4 | Copy-paste: ISO date parsing | DateNode, SortNode.dateValue, IcalNode.parseInstant |
| P2-5 | Copy-paste: auth and retry code | `connectorHeaders`/`requestWithRetry` versus HttpNode and BinaryUploadNode |
| P2-6 | Three hand-rolled JSON parsers | `MiniJson` (lenient numbers, `toChar` on `\u`), `JsonLite` (strict, duplicate-key rejecting), plus kotlinx in `core/data` and the gateway. Behaviour differs on duplicate keys, number grammar and size caps. Use kotlinx.serialization (already in `libs.versions.toml`) everywhere in `:core:nodes` |
| P2-7 | Hand-rolled XML, HTML, Markdown, CSV | See section 6 for library options; the hand versions are the main source of the defects above |
| P2-8 | Dead or duplicate helpers | `ParamSpecList` (unused), `ParamResolverLite`, MathNode `ln` branch missing from options, `Stats` null-then-remove pattern |
| P2-9 | Number type inconsistency | Int versus Double counts across nodes (see 2.4) |
| P2-10 | `Math.random` and `SecureRandom` mixed | Use one injectable random source so tests are deterministic |
| P2-11 | Entity decoding | `HtmlLite.decode` uses `Int.toChar()`, wrong for code points above 0xFFFF (emoji entities); MiniJson also uses `toChar()` for escapes (surrogate pairs work only by accident) |
| P2-12 | iCal output | No line folding, `take(2000)` after escaping can split an escape sequence, UID changes each run |
| P2-13 | Stats and Run details item loss | Both return new single items and drop the input; surprising for chained Steps |
| P2-14 | `Values.deepMerge` concatenates arrays | Surprising for Merge "combine"; make it a policy |
| P2-15 | Share Spark types | Spec offers image/any, matcher only matches text payloads |

### Test gaps

Existing node tests: about 1,750 lines across 9 files, most using one input item and the happy path.

- **No tests at all** for: Wait, Stop, When a Step fails, Write text, Crypto and hash, Number summary, Split items, URL tools, Calendar invite, Upload a file, Random value, Incoming webhook, and failure paths for Run another Flow.
- **Shallow tests** (one scenario): If, Switch (no regex or empty-case test), Merge (one mode only), Remove duplicates, Loop (only the limit), Date (no DST, no bad input), CSV (no BOM, no semicolon, no big file), XML/HTML/Markdown (round trip only), Binary tools (text round trip only).
- **Missing categories**: multi-item correctness, empty input, null and missing fields, Unicode and non-Latin text, large inputs, error-routing, per-port output assertions, determinism (stable sorts), every `Select` option value of every spec, cancellation.

Recommended test plan:
1. A **multi-item contract test** that runs every executor with three items whose fields differ and asserts the output differs per item (would have caught P0-1).
2. An **option-coverage test** that iterates every `ParamSpec` of type Select and asserts each option value reaches distinct code (would have caught the Dedupe `scope` and Math `ln` mismatches).
3. A **golden-file corpus** for CSV (RFC 4180 edge cases, BOM, delimiters), XML, HTML, Markdown, iCal (fold lines) stored as resources.
4. **Property tests**: Sort is stable and idempotent; Remove Duplicates is idempotent; Filter plus its complement equals the input; Merge append length equals sum.
5. **Flow-level tests** in `FlowRunnerTest` for If then Merge re-join, loop-over-items with a body, per-item error routing, Wait not blocking siblings.
6. A **migration test** per removed node id that loads an old Flow and checks the converted Steps.

---

## 6. Copy-paste and hand-rolled parsing summary

| Concern | Today | Recommended |
|---|---|---|
| JSON | `MiniJson`, `JsonLite`, `Values.stringify` | kotlinx.serialization JSON (a `JsonElement` to `Map` bridge), one place |
| CSV | `CsvLite` (about 70 lines) | Keep a small own parser but fix the defects and test with a corpus, or adopt a permissive-licence CSV library |
| XML | `XmlLite` (about 130 lines, no entity handling) | jsoup's XML mode or a pull-parser library that runs on both JVM tests and Android; `javax.xml.stream` is not available on Android |
| HTML | Regex (`HtmlLite`) | jsoup (CSS selectors, tolerant parsing) |
| Markdown | `MarkdownLite` subset | commonmark-java with safe URL handling |
| Dates | Scattered `Instant.parse` chains | One `DateParsing` object on `java.time` (native on API 26+) |
| Regex | `Regex(...)` in 3 places | One safe-regex service (E9) |
| iCal | String building | Keep hand-written, add RFC 5545 folding and escaping helpers with tests |
| Zip | `java.util.zip` | Keep, add total-size and ratio budgets |

All libraries named are widely used permissive-licence Java libraries; verify licence, size and Android compatibility at adoption. None of this involves n8n code.

---

## 7. Suggested phase tasks

Each task lists acceptance criteria. Keep the old node ids loadable (hidden from the palette) until the migration step.

**Phase 0: Correctness and foundations (do first)**
- T0.1 Fix P0-1: introduce E1 (`ctx.itemParams(i)` and typed accessors); convert Set fields, Write text, Date, Crypto; deprecate `params()`. Done when the multi-item contract test passes for all executors.
- T0.2 Fix P0-3 and build the typed condition engine (3.3) with a full operator test table; wire If and Filter; resolve both sides.
- T0.3 Add E7 (unique Step names) and E6 (per-port outputs by Step id); expose `$step` accordingly.
- T0.4 Add E12 per-item error helper and routing; update `runWithRetries` so Continue keeps successful items.
- T0.5 Add E9 safe-regex service; use it in conditions and text; enable Notification `textMatches`.
- T0.6 Correct README and PARITY.md claims (Loop, Stop, Dedupe) until the rebuilds ship.
- T0.7 Add E8 migration scaffolding and a bundle version bump.

**Phase 1: Core flow control**
- T1.1 E2 (inputs and dynamic outputs); rewrite Merge (all modes), Switch (rules, dynamic ports), Compare Datasets (four ports).
- T1.2 Stop and Error; real Error Spark.
- T1.3 Loop Over Items with engine support E3 (or interim "each batch" in Run another Flow); delete Loop.
- T1.4 Run another Flow rewrite plus Called by another Flow Spark; remove silent truncation.
- T1.5 Wait modes; release permit while waiting; E10 persistence for long waits and approvals.

**Phase 2: Data shaping**
- T2.1 Edit Fields (absorbs Rename/remove, Write text, JSON, Run details, Math, Text tools, URL tools through an expression helper picker); Split Out; Aggregate; Summarize (absorbs Group and total, Number summary).
- T2.2 Remove Duplicates with persistent history (E4); Sort multi-field and random; Limit trimmed.
- T2.3 Date and Time rewrite on `java.time`; Crypto rewrite; Random value absorbed.
- T2.4 Code Step contract fixes (binary, console capture, raw script).

**Phase 3: Formats and files**
- T3.1 Extract From File and Convert To File (CSV first, then JSON, text, XML, HTML, base64, PDF text, iCal, optional xlsx); replace Binary tools and CSV nodes.
- T3.2 Compression with archive budgets; E11 streaming attachments.
- T3.3 HTML (CSS selectors), XML, Markdown (safe) on libraries; iCalendar folding; Chart fixes.

**Phase 4: State and mobile extras**
- T4.1 Data Table node on the Room repository through `TableGateway`; delete Local table.
- T4.2 Run Data, Test Data; Schedule Spark with cron, seconds interval and full zone list; pinned sample input for Manual.
- T4.3 Respond to Webhook with the network lane.

**Phase 5: Retire legacy and harden**
- T5.1 Remove Loop, Local table, Group and total, Service connector, Only new items, Upload a file and the merged Steps after migration tests pass; keep ids as aliases for at least one release.
- T5.2 Update starter templates to new nodes; update README catalog counts and docs.
- T5.3 Land the full test plan (section 5, Test gaps); add CI check that every spec option has a test and every executor passes the multi-item contract.

**Suggested order of value:** Phase 0 (stops wrong results), Merge and Loop Over Items (largest capability gap), Edit Fields and Summarize (daily-use nodes), Extract/Convert File (replaces three weak nodes), Data Table (persistent state for a serverless app).
