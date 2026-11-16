# P2 Spec: Node taxonomy, base clean-up, AI-ready structure

Status: proposal for owner approval. Branch: claude/phase-1-foundation.
Scope: specification only, no code. Written in KaivoFlow vocabulary (Flow, Step, Stream, Spark, Run). n8n was read only to understand how it groups and surfaces nodes (its node folders, node-creator panel and AI node family); nothing here copies its code, identifiers or text, and none of the ids below are n8n's.

Inputs used: docs/audit/03, 04, 06, docs/ROADMAP.md, `core/nodes/NodeCatalog.kt`, `core/model/NodeSpec.kt` (`StepCategory`), `feature/canvas/.../StepPickerSheet.kt`.

## 0. Findings that drive the design

1. Today there are 87 registered executors in one flat `NodeCatalog.executors()` list: 12 Sparks, 17 Logic, 25 Transform, 3 AI, 12 SaaS, 18 Device. Six hard-coded enum values (`StepCategory`: Trigger, Ai, Saas, Logic, Transform, Device) are the whole taxonomy. The picker shows those six as chips; Transform is a 25-item junk drawer and "SaaS" holds HTTP, RSS, TCP and Slack side by side.
2. Category is an enum in `core:model`, so adding a subcategory means editing model, picker and colour code. The id prefix (`transform.`, `saas.`) doubles as the category, so recategorising means renaming.
3. Executors live in thematic multi-node files (`TransformNodes.kt`, `ExtendedTransformNodes.kt`, `SaasNodes.kt`, ...). "Extended" files are a growth-by-accretion smell. Several nodes duplicate each other (audit 03: 14 MERGE rows, 04: four overlapping HTTP-ish nodes).
4. The engine throws `UnknownStepTypeException` for an unregistered type, so any id removal or rename without an alias breaks saved Flows today. Flow files and the native bundle carry a `version`, but no per-Step migration exists.
5. The picker's filter runs inside a `remember` in the composable and has no recents, favourites, breadcrumbs or Spark/Step split beyond a `hasTrigger` flag.

## 1. Principles

- Fewer, stronger Steps. One Step with a mode beats five Steps. A Step that an expression helper or another Step does better is removed.
- A Step has exactly one home category. Discovery across categories uses tags and search, never duplicate entries.
- Ids are permanent once released. Category is metadata and can change without touching ids.
- Everything a Step needs to appear in the UI (title, category, params, keywords, aliases, lifecycle) is declared in its spec. The picker has zero knowledge of individual Steps.
- Removed or renamed ids never break a stored Flow (section 6).

## 2. Category tree (a)

Ten top levels. Two-level maximum (top level, subcategory). Each top level has a stable key, a label and an accent hue family (section 6 of the picker proposal).

| Key | Label | Subcategories (key: label) | What lives here now |
|---|---|---|---|
| spark | Sparks | start: Start and time; incoming: Incoming; device_events: Device events | all `trigger.*` |
| flow | Flow control | branching: Branching; looping: Looping; timing: Timing and errors; composition: Composition | if, switch, merge, loop, wait, stop, error handler, sub-Flow, no-op |
| data | Data | items: Fields and items; text: Text and formats; time: Date and time; storage: Tables (planned) | Edit Fields, filter, sort, limit, dedupe, compare, split, date, xml, html, markdown, calendar text |
| files | Files and binary | convert: Read and convert; archive: Compress (planned); document: Documents | pdf; planned Extract/Convert/Compress |
| net | Network | web: HTTP and web; protocols: Protocols (planned) | HTTP request, RSS read |
| app | Apps | messaging: Messaging; productivity: Productivity and docs; (more added per vendor group: google, cloud, dev, social, commerce) | Telegram, Slack, Discord, Sheets, Notion |
| ai | AI | chains: Chains; agents: Agents (planned); models: Chat models (planned); memory: Memory (planned); tools: Tools (planned); parsers: Output parsers (planned); rag: Retrieval (planned) | legacy Ask AI trio until replaced |
| code | Code | script: Scripts | JavaScript Code |
| security | Security | crypto: Hashing and keys; auth: One-time codes | Crypto, TOTP |
| device | Device | sensors: Sensors; connectivity: Connectivity; telephony: Telephony and messaging; notifications: Notifications; files_media: Files and media; system: System and apps; location: Location; contacts_calendar: Contacts and calendar; io: Input and output | all `device.*` actions |

Planned Device growth (from n8n-style breadth and Android capabilities, added one at a time after the base is clean): Sensors (accelerometer, light, step counter, pressure), Connectivity (bluetooth scan, hotspot, airplane state), Telephony (call state, dial), Notifications (dismiss, channels), Files and media (camera capture, gallery query, media scan), System and apps (launch app, installed apps, brightness, DND, alarms, settings), Location (geocode, distance), Input and output (dialogs, text input, screen state).

### Rule for placing a Step (first matching question wins)

1. Does it start a Flow (kind Spark)? Spark, subcategory by event source: Start and time (manual, schedule), Incoming (webhook, share sheet), Device events (everything the phone raises). Device Sparks also carry `alsoIn` a Device subcategory so the Device page can show them (cross-listing is a tag, not a second entry).
2. Does it read or change phone hardware, OS state or an Android system service? Device, subcategory by the capability it touches (use the Device list above; if none fits, propose a new subcategory, never "Other").
3. Does it talk to one named third-party product? Apps, grouped by what users do with it.
4. Is it a generic protocol or web client used with any server? Network.
5. Does it run an AI model or is it an AI building block (model, memory, tool, parser)? AI.
6. Does it run user-written code? Code.
7. Is its main job cryptography, secrets or authentication codes? Security.
8. Does it mainly operate on file or binary bytes (convert, parse, compress, extract text)? Files and binary.
9. Does it only control routing, timing, error paths or composition without reshaping data? Flow control.
10. Otherwise it reshapes data. Data.

Hard rules: no "Misc", no "Utilities", no subcategory with fewer than 2 Steps unless it is a Device or AI subcategory reserved by this spec. A category page with more than ~12 Steps must be split by the next review. A Step's `keywords` carry alternate names (including names users know from other tools and old KaivoFlow names) so search finds it regardless of placement.

## 3. Current executors and verdicts (b)

Verdicts: KEEP (stays, maybe recategorised or renamed), REWORK (stays, behaviour must be rebuilt per the audit; no picker change meanwhile), MERGE-INTO (executor goes away, behaviour moves to the target, converter required), REMOVE (executor goes away; converter to a stated replacement where one exists, otherwise a tombstone). "(planned)" targets do not exist yet: until they ship, the old Step stays registered with lifecycle Hidden (runs, not offered in the picker, inspector shows an upgrade notice). "New id" follows the naming rule in section 5. "Cat" is `top/sub`.

### 3.1 Sparks

| Current id | New id | Cat | Verdict | Reason |
|---|---|---|---|---|
| trigger.manual | spark.manual | spark/start | KEEP | Needed; default start of every Flow. |
| trigger.schedule | spark.schedule | spark/start | REWORK | Audit 03: six time zones only, no cron, one rule per Spark. |
| trigger.webhook | spark.webhook | spark/incoming | REWORK | Audit 04: shared secret only, no response control, parser weaknesses. |
| trigger.share | spark.share | spark/incoming | KEEP | Android share sheet entry is a phone-native strength. |
| trigger.notification | spark.notification | spark/device_events | REWORK | `textMatches` is shown but blocked by validation; needs safe-regex service. alsoIn device/notifications. |
| trigger.sms | spark.sms | spark/device_events | KEEP | Fail-closed permission recheck is good. alsoIn device/telephony. |
| trigger.battery | spark.battery | spark/device_events | KEEP | Simple and correct. alsoIn device/sensors. |
| trigger.geofence | spark.geofence | spark/device_events | KEEP | Core mobile value. alsoIn device/location. |
| trigger.bluetooth | spark.bluetooth | spark/device_events | KEEP | alsoIn device/connectivity. |
| trigger.wifi | spark.wifi | spark/device_events | KEEP | alsoIn device/connectivity. |
| trigger.headset | spark.headset | spark/device_events | KEEP | alsoIn device/io. |
| trigger.boot | spark.boot | spark/device_events | KEEP | alsoIn device/system. |

### 3.2 Logic (split between Flow control and Data)

| Current id | New id | Cat | Verdict | Reason |
|---|---|---|---|---|
| logic.if | flow.if | flow/branching | REWORK | Move to shared condition groups; keep two outputs. |
| logic.switch | flow.switch | flow/branching | REWORK | Four fixed cases is the anti-pattern; needs rule list and dynamic outputs (engine E2). |
| logic.merge | flow.merge | flow/branching | REWORK | Largest capability gap in audit 03 (append, by key, by position). |
| logic.loop | flow.loop | flow/looping | REWORK | Becomes batch loop (loop over items) with done output. |
| logic.wait | flow.wait | flow/timing | REWORK | Holds a concurrency permit; needs persisted resume. |
| logic.stop | flow.stop | flow/timing | KEEP | Small, correct. |
| logic.onError | flow.on_error | flow/timing | REWORK | Keep; define error-path contract in engine. |
| logic.subflow | flow.subflow | flow/composition | REWORK | Silent truncation, no timeout, no named inputs. |
| logic.noop | flow.noop | flow/composition | KEEP | Drop the note param (canvas notes cover it). |
| logic.filter | data.filter | data/items | KEEP | Reuses shared condition groups. |
| logic.sort | data.sort | data/items | REWORK | Multi-field, null handling, random mode (absorbs random shuffle). |
| logic.limit | data.limit | data/items | KEEP | Add "last N" mode only. |
| logic.dedupe | data.dedupe | data/items | REWORK | Gains "across Runs" mode (absorbs poll). |
| logic.compare | data.compare | data/items | KEEP | Compares two lists; unique capability. |
| logic.poll | data.dedupe | data/items | MERGE-INTO data.dedupe | Mid-flow "seen before" memory is a mode of dedupe; fixes shared state-file bug by keying state on Step id. |
| logic.table | data.table (planned) | data/storage | REMOVE | Whole-file rewrite, races, fake row; replaced by Room-backed Data Table. Hidden until that ships. |
| logic.run_info | data.set | data/items | REMOVE | Drops incoming items; the data belongs in a `$run` expression scope. Converter writes a Edit Fields Step with the same expressions. |

### 3.3 Transform (split across Data, Files, Code, Security)

| Current id | New id | Cat | Verdict | Reason |
|---|---|---|---|---|
| transform.set | data.set | data/items | REWORK | Becomes the hub Step ("Edit Fields"): assign, rename, remove, keep-only, helper picker for text/math/url. Fix item-0 resolution bug. |
| transform.fields | data.set | data/items | MERGE-INTO data.set | Rename/remove/keep are Edit Fields operations. |
| transform.template | data.set | data/items | MERGE-INTO data.set | A string assignment. Three starter templates use it, so alias is mandatory. |
| transform.json | data.set | data/items | MERGE-INTO data.set | Parse and stringify become expression helpers; file JSON goes to files.convert (planned). Source-ignoring stringify bug disappears. |
| transform.math | data.set | data/items | MERGE-INTO data.set | 19-option dropdown duplicates what expressions do. |
| transform.text | data.set | data/items | MERGE-INTO data.set | Same; also non-Latin text bug is fixed once in the helper layer. |
| transform.url | data.set | data/items | MERGE-INTO data.set | Parse/build as helpers; query building belongs to net.http. |
| transform.date | data.date | data/time | REWORK | Needed; time zone and parse formats weak. |
| transform.split | data.split_out | data/items | REWORK | Raw `chunkSize` ignores expressions; chunking moves to flow.loop. |
| transform.aggregate | data.summarize (planned) | data/items | REMOVE | Single group key, wrong name versus the common meaning of aggregate. Hidden until Summarize ships. |
| transform.stats | data.summarize (planned) | data/items | MERGE-INTO data.summarize | Same engine as grouped totals. Hidden until it ships. |
| transform.xml | data.xml | data/text | REWORK | Attribute-name injection on build; use library parser. |
| transform.html | data.html | data/text | REWORK | Regex over HTML; needs CSS selectors. |
| transform.markdown | data.markdown | data/text | REWORK | Unsafe URL schemes; use a real parser. |
| transform.ical | data.ical | data/text | REWORK | Low priority; line folding, time zones. Keep because Calendar invites are phone-relevant. |
| transform.csv | files.convert (planned) | files/convert | MERGE-INTO files.convert | Buggy (BOM, delimiter quoting, silent size drop); belongs with Extract/Convert. Hidden until it ships. |
| transform.binary | files.convert / files.compress (planned) | files/convert | MERGE-INTO files.convert | Ten operations in one Step; split by purpose. Hidden until they ship. |
| transform.binary_upload | net.http | net/web | MERGE-INTO net.http | Multipart upload is an HTTP body mode; duplicate URL/header/timeout code. |
| transform.pdf | files.pdf | files/document | REWORK | Keep; text extraction is useful on a phone. |
| transform.chart | (none) | - | REMOVE | Buggy SVG, hard-coded theme, low value. Tombstone. Rebuild later only as a proper Step with its own spec. |
| transform.random | security.crypto / data.sort | - | REMOVE | uuid/string go to Crypto generate; shuffle to Sort; number/pick are expression helpers. Converter maps uuid and string; others tombstone. |
| transform.token | security.crypto | security/crypto | MERGE-INTO security.crypto | Secure token generation is a Crypto operation. |
| transform.crypto | security.crypto | security/crypto | REWORK | Algorithm list, key as secret, no silent decode failure. |
| transform.totp | security.totp | security/auth | KEEP | Distinct, small, useful. |
| transform.code | code.javascript | code/script | REWORK | Per-item contract, binary loss, `{{` mangling, log capture. |

### 3.4 AI (placeholder until the family in section 4 ships)

| Current id | New id | Cat | Verdict | Reason |
|---|---|---|---|---|
| ai.chat | ai.chat | ai/chains | REWORK | Owner: weak, to be replaced. Stays, flagged Legacy in the picker (badge), reserved alias to `ai.basic_chain` (planned). |
| ai.extract | ai.extract | ai/chains | REWORK | Reserved alias to `ai.information_extractor` (planned). Keep safety tests (AiSafetyTest). |
| ai.classify | ai.classify | ai/chains | REWORK | Reserved alias to `ai.text_classifier` (planned). |

### 3.5 SaaS (split between Network and Apps)

| Current id | New id | Cat | Verdict | Reason |
|---|---|---|---|---|
| saas.http | net.http | net/web | REWORK | The single web Step. Absorbs connector, GraphQL, WebDAV verbs, multipart upload. Retries, pagination, binary, cross-host header safety. |
| saas.connector | net.http | net/web | MERGE-INTO net.http | Duplicate; leaks auth headers on cross-host paging (audit 03). |
| saas.graphql | net.http | net/web | MERGE-INTO net.http | Becomes a body mode; keep the errors-array check. |
| saas.webdav | net.http | net/web | MERGE-INTO net.http | WebDAV verbs plus a preset; no standalone Step. |
| saas.rss | net.rss | net/web | REWORK | Keep (feed reading is common); parser hardening. |
| saas.tcp | (none) | - | REMOVE | Raw sockets: niche, security surface, no n8n parity. Tombstone; may return as an opt-in Protocols Step. |
| saas.weather | net.http | net/web | REMOVE | One hard-wired provider; HTTP does it. Converter builds an HTTP Step with the same URL. |
| saas.telegram | app.telegram | app/messaging | KEEP | |
| saas.slack | app.slack | app/messaging | KEEP | |
| saas.discord | app.discord | app/messaging | KEEP | |
| saas.sheets | app.google_sheets | app/productivity | KEEP | |
| saas.notion | app.notion | app/productivity | KEEP | |

### 3.6 Device

| Current id | New id | Cat | Verdict | Reason |
|---|---|---|---|---|
| device.notify | device.notify | device/notifications | KEEP | Gains a "show as toast" mode. |
| device.toast | device.notify | device/notifications | MERGE-INTO device.notify | Same intent, different surface. |
| device.sms | device.sms | device/telephony | KEEP | |
| device.contact | device.contact | device/contacts_calendar | KEEP | |
| device.calendar_event | device.calendar_event | device/contacts_calendar | KEEP | |
| device.location | device.location | device/location | KEEP | |
| device.battery_status | device.battery_status | device/sensors | KEEP | |
| device.network_status | device.network_status | device/connectivity | KEEP | Becomes "Connection info" (absorbs Wi-Fi details). |
| device.wifi_info | device.network_status | device/connectivity | MERGE-INTO device.network_status | Same data source. |
| device.file | device.file | device/files_media | REWORK | Read, write, list, delete with a clear sandbox; storage access per audit 04. |
| device.open | device.open | device/system | KEEP | Open link or app. |
| device.volume | device.volume | device/system | KEEP | |
| device.clipboard | device.clipboard | device/io | KEEP | Gains operation read/write. |
| device.clipboard_read | device.clipboard | device/io | MERGE-INTO device.clipboard | One Step, two operations. |
| device.share | device.share | device/io | KEEP | Android share sheet out. |
| device.speak | device.speak | device/io | KEEP | |
| device.vibrate | device.vibrate | device/io | KEEP | |
| device.torch | device.torch | device/io | KEEP | |

Tally: 34 KEEP, 28 REWORK, 18 MERGE-INTO, 7 REMOVE (87 total). After clean-up 62 executors remain visible in the picker. Five more (table, aggregate, stats, csv, binary) stay Hidden until their planned replacements ship, then are deleted. The golden test (5.6) is the authority for counts.

## 4. AI family target structure (c)

Goal: the structure exists now (spec fields, categories, slot model); the content arrives later.

### 4.1 Families

| Family | Category | Examples (planned ids) | Notes |
|---|---|---|---|
| Agent | ai/agents | ai.agent | One Step, tool-calling loop with iteration and token budget per Run. |
| Chains | ai/chains | ai.basic_chain, ai.information_extractor, ai.text_classifier, ai.summarizer | Single model call with a prompt; the current trio migrate here. |
| Chat model | ai/models | ai.model.openai, ai.model.anthropic, ai.model.gemini, ai.model.ollama, ai.model.openai_compatible | Sub-Steps. One executor per distinct wire protocol; OpenAI-compatible providers (Groq, DeepSeek, Mistral, OpenRouter, xAI, local servers) are presets of one executor driven by a provider table, not new code. |
| Memory | ai/memory | ai.memory.window, ai.memory.summary | Room-backed, scoped per Flow and conversation key. Server databases (Redis, Postgres) are out of scope on-device. |
| Tool | ai/tools | ai.tool.calculator, ai.tool.http, ai.tool.code, ai.tool.flow, ai.tool.think, ai.tool.search, ai.tool.mcp | Plus "any Step as a tool" (wrap an existing Step spec). Device Steps as tools require a confirmation policy. |
| Output parser | ai/parsers | ai.parser.structured, ai.parser.list, ai.parser.autofix | JSON-schema driven; reuse `AiExtractionSchema`. |
| Retrieval | ai/rag | ai.embed, ai.store.local, ai.retriever, ai.loader, ai.splitter | Last; on-device vector store only. |
| Guardrails | ai/chains | ai.guard | Reuse the AiSafetyTest rules. |

### 4.2 How sub-Steps attach on a phone canvas

- A new `connection role` on every spec: `Main` (normal Stream), or one of `AiModel`, `AiMemory`, `AiTool`, `AiParser`. Sub-Steps (non-Main roles) never have Stream ports and are never placed as free nodes by default.
- The Agent card on the canvas shows slot chips under its title: Model (required, one), Memory (optional, one), Tools (many), Parser (optional, one). Each chip is a tap target: empty chips read "Add model" and open the picker pre-filtered to that role.
- Stored as `step.attachments: Map<slot, List<AttachedStep>>`, where an attached Step has its own id, type, params and credential reference but lives inside the parent in the Flow JSON. This avoids drawing five extra wires on a 6 inch screen and makes copy/paste and duplicate of an Agent atomic.
- Optional expanded view (long-press the Agent, "Show attachments") lays attached Steps out as small cards on a ring around the Agent for editing; this is a view mode, not a different data model.
- Engine: the executor of the parent resolves attachments directly (the engine calls sub-executors with the parent context); Streams stay purely `Main`. The Run log nests sub-Step traces (model call, each tool call) under the parent Step. The validator enforces slot rules (exactly one model, tools unique by name, parser needs schema).
- Tool safety: each tool declares side-effect level (read, write, device, sensitive); anything above read requires a per-Flow approval stored in the Flow, and sensitive device tools prompt at Run time unless the owner has pre-approved. Iteration cap, token cap and a Run-wide cost budget are Flow-level settings.
- Credentials: provider keys in the existing vault, referenced by id; never in step params.

### 4.3 What must be ready now (cheap, no AI code)

- `NodeSpec.role` field and the five roles; `NodeSpec.attachSlots` (slot key, label, role, min, max) for parent Steps.
- Picker filter by role. `ai` category and its subcategories exist and are hidden while empty (a subcategory is shown only when it has at least one visible Step).
- Run log supports child entries.

## 5. Target project structure (d)

### 5.1 Layout

Phase A keeps one Gradle module `:core:nodes` but reorganises packages and files. Phase B (when the count passes about 150) splits Gradle modules without changing package names.

```
core/model/      NodeSpec, NodeRole, Lifecycle, CategoryTree (data, no Android)
core/engine/     unchanged (NodeRegistry, runner)
core/nodes/src/main/kotlin/com/kaivoflow/core/nodes/
  kit/           StepDsl (spec builders), ParamKit (textParam, selectOptions...), Conditions, MiniJson,
                 BinarySupport, resolve helpers (today's NodeDsl.kt, CryptoMath, MiniJson, etc.)
  catalog/       NodeCatalog (assembles groups), CategoryDefs, AliasMap (assembled), Converters registry
  spark/         schedule/ webhook/ manual/ ... one folder or file per Spark
  flow/          if/ switch/ merge/ ...
  data/          set/ filter/ sort/ ...
  files/  net/  code/  security/
  app/           google/ messaging/ productivity/ ...   (vendor folders, shared auth client per vendor)
  ai/            agent/ chains/ models/ memory/ tools/ parsers/ rag/ shared/
  device/        sensors/ connectivity/ telephony/ notifications/ files_media/ system/ location/ contacts_calendar/ io/
  legacy/        hidden executors still needed for old Flows, plus their converters (deleted after sunset)
```

The path of a Step on disk mirrors its category (`device/connectivity/NetworkStatusStep.kt`), so the folder tree is the taxonomy. Recategorising a Step means `git mv` plus one spec line.

Phase B Gradle layout (when needed): `:nodes:kit`, `:nodes:spark`, `:nodes:flow`, `:nodes:data`, `:nodes:device`, `:nodes:apps`, `:nodes:ai`, `:nodes:misc` (files, net, code, security) and `:nodes:all` as the only module the app depends on. Device and Apps modules depend on `kit` plus the gateway interfaces, never on each other. This keeps incremental builds fast as nodes grow and enforces dependency direction.

### 5.2 One Step = one small unit

- Simple Step: one file `<Name>Step.kt` containing the spec and the executor class `<Name>Step`.
- Larger Step: folder `<name>/` with `<Name>Step.kt` (class and spec), `<Name>Params.kt` (param specs, if long), `<Name>Ops.kt` (pure logic, no `StepContext`) and the matching test files under the same path in `src/test`.
- Spec is declarative and lives at the top of the file as `val spec = step(...) { ... }` using kit builders: id, title, subtitle, category (typed constant from `CategoryDefs`, never a string), icon key, kind, role, params, keywords, `replaces` (old ids absorbed), lifecycle, permissions, `alsoIn`. The executor only implements `execute(ctx)`.
- Pure logic (parsing, formatting, maths) goes to `Ops` files with no Android or engine types so tests need no fakes.
- Maximum guideline: executor file under ~300 lines; if bigger, split Ops. No file holds more than one Step (kills `ExtendedXxxNodes.kt`).

### 5.3 Naming rules

- Step id: `<top>.<name>`; sub-Step ids add the family: `ai.model.openai`. Lowercase, `a-z0-9_`, dots only as separators. The top part is the category at birth and never changes afterwards (a Step recategorised later keeps its id).
- Class: `<PascalName>Step`. File name equals class name. Test: `<PascalName>StepTest`. Package: category path in lowercase.
- Title: noun or verb phrase, sentence case, no "Node", no brand unless an Apps Step; subtitle one plain sentence under 60 characters.
- Param keys: lowerCamelCase, stable forever (they are stored in Flows); labels are free to change.
- Keywords: at least 3, include legacy names and common names from other automation tools.

### 5.4 Registry auto-assembly

- Each category package exposes one file `<Category>Steps.kt` with a single `val steps: List<() -> StepExecutor>` built from the Step classes in that package. `NodeCatalog` concatenates the ten groups; nothing else lists executors. Device-backed Steps take the gateway through a `StepEnv` parameter instead of per-class constructor wiring.
- Guard test `CatalogCompletenessTest` walks `src/main` for `*Step.kt` classes implementing `StepExecutor` and fails if one is missing from its group, if a file sits in a folder that disagrees with its spec category, or if two specs share an id or alias.
- Escalation: when the guard test becomes tedious (about 150 Steps), replace the group lists with a KSP annotation processor (`@RegisterStep`) that generates the same lists. The spec and file layout do not change, so this is a drop-in switch.
- Specs must be readable without instantiating executors (picker, search index and validator use specs only) so cold start does not build 300 objects.

### 5.5 Test rule per Step

Every Step must have, before merge:

1. Generated contract test (automatic, parameterised over the registry): id format, unique id and aliases, category valid in the tree and matching folder, title and subtitle length, icon exists, at least 3 keywords, every param has key, label and valid `showWhen`, required params have no unsafe default, lifecycle consistent with replacement.
2. A `<Name>StepTest` with: happy path; each mode or operation once; invalid or missing required param gives a typed error, not a crash; expression in each resolvable param is resolved per item (the item-0 bug class, audit 06); empty input; and for Device and App Steps a fake gateway failure and a permission-denied case.
3. For Steps that replace others: one converter fixture per absorbed id (section 6).
4. No Step may call `Math.random`, `System.currentTimeMillis` or read files directly; time, randomness and IO come through the kit so tests are deterministic.

### 5.6 Characterisation first

Before any move: a golden test records today's id list, category, param keys and outputs count for all 87 specs. Moves must keep it green until the verdict step deliberately edits the golden file in the same change. This catches accidental param or id drift.

### 5.7 Migration from the flat files: ordered, low risk

Each step is its own change with the full test suite green. Steps 1 to 4 change no stored data.

1. Add the golden test (5.6) and the contract test (5.5.1) against the current code.
2. Introduce `CategoryTree` and the new `NodeSpec` fields (`category` as path, `lifecycle`, `role`, `alsoIn`, `replaces`) with defaults; derive today's `StepCategory` from the path so existing code compiles. Assign the new category to every spec (metadata only). No id changes yet.
3. Rewrite the picker against the tree (section 7). The user-visible win (goal 2) lands here with zero risk to Flows.
4. Move files into the package layout one category per change, lowest risk first: device, spark, flow, data, files/net/code/security, apps, ai. Within each, split multi-Step files into one file per Step. Pure `git mv` plus package rename, no logic edits.
5. Add AliasMap, loader normalisation and converter framework (section 6) with tests, still with no id changes.
6. Rename ids to the new scheme through aliases (mechanical, one change; golden file updated; Room/bundle migration shipped in the same release).
7. Execute verdicts in this order, one change each: merges that need no new Step (device: clipboard_read, toast, wifi_info; logic: poll into dedupe; transform: fields, template, json, math, text, url into data.set; token into crypto; connector, graphql, webdav, binary_upload into net.http), then removals with no replacement (tcp, chart, weather, random, run_info), then Hidden-until-replaced ones as their replacements ship (table, aggregate, stats, csv, binary).
8. Split Gradle modules (5.1 Phase B) only after step 7; package names make it a build-file change.
9. Optional: switch to KSP assembly.

If no public release of KaivoFlow exists yet when step 6 and 7 happen, skip the Hidden grace period and convert at load only; the converter and tombstone machinery stays because imported Flow files from older builds still exist.

## 6. Compatibility for saved Flows (f)

### 6.1 Alias map

- A single `AliasMap: Map<oldId, AliasTarget>` assembled from every spec's `replaces` plus the rename list from step 5.7.6. `AliasTarget` holds the new id and an optional converter id. Aliases are append-only and never reused; a test fails if an alias equals a live id or points to a missing id.
- Chains are flattened at build time (old to current), so lookup is one hop.

### 6.2 Where aliases are applied

- Flow load from Room: normalise in memory, write back on next save.
- Import of Flow files and native bundles: accept old ids; bump the bundle `VERSION`; always export new ids.
- Starter templates (`FlowTemplates`: device.notify, device.file, saas.connector, transform.template, trigger.*): rewrite to new ids in the same change as step 6 and keep the alias as a safety net.
- Spark registrations (schedules, webhook routes, event matchers, geofences): the registration table is keyed by Flow and Step id, not by type string, and the dispatcher normalises the type before matching. This needs a one-time check in `EventTriggerMatcher` and the work scheduler (see risk R2).
- Run history and logs keep the type string they were recorded with; the display layer resolves it through the AliasMap for title and icon. History is never rewritten.
- Registry lookup: `NodeRegistry.get(type)` resolves the alias before throwing `UnknownStepTypeException`.

### 6.3 Converters

- `StepConverter(id, from, to, convert(params) -> ConvertedStep)`: pure, deterministic, idempotent, no IO. It maps param keys and values, can set the target's mode, and returns warnings for anything it could not map (shown once to the owner, never silent).
- Required converters: clipboard_read to clipboard (mode read); toast to notify (mode toast); wifi_info to network_status; poll to dedupe (mode across Runs, state key derived from the old Step id so existing "seen" data is preserved); fields, template, json, math, text, url to set (each operation becomes an assignment or a helper expression); run_info to set (assignments using `$run` expressions); token and random (uuid, string) to crypto; connector, graphql, webdav, binary_upload, weather to http (preset fields); stats and aggregate to summarize and csv and binary to files steps when those ship.
- Params with no equivalent are preserved in `step.legacyParams` so nothing is lost and a later converter version can use it.
- Converters have a version; a Step records `convertedFrom` and the converter version, so a fixed converter can be re-run safely.

### 6.4 Lifecycle and tombstones

Lifecycle values on a spec: Stable, Beta, Legacy (visible with badge, e.g. ai.chat), Hidden (runs, not in picker or search, inspector banner "Replaced by X, upgrade"), Removed (no executor; tombstone entry only).

- A removed Step with no converter (tcp, chart, random number and pick) loads as an "Unavailable Step" placeholder that keeps its id, params and wiring, fails validation with a clear message, and can be deleted or left. A Flow is never silently mutated or dropped.
- A manual "Upgrade Step" action in the inspector runs the converter and shows a before/after diff; the load-time normalisation is applied only for pure renames, never for merges that change behaviour (merges are offered, then auto-applied on the next major app version).
- Sunset: a Hidden Step is deleted from `legacy/` two releases after its replacement ships, never before its converter has a passing fixture test for every parameter combination used in templates and audit examples.

### 6.5 Tests for compatibility

- Fixture Flows (JSON) per old id, stored under test resources, covering every absorbed id; test loads, normalises, validates, runs against fakes, and compares item output with the old executor's golden output where the behaviour is meant to be equal.
- A "round trip" test: export (new ids), import, equal.
- A "forward only" test: an unknown future id loads as an Unavailable Step instead of crashing.

## 7. Picker UX proposal (e)

Keeps the existing bottom sheet, tap-to-add and drag-to-place behaviours of `StepPickerSheet` and its Royal Velvet look (canvasVoid background, StepMedallion icons, `KaivoShapes`, `KaivoIcons`, haptics on drag lift). No emoji, no new colour system.

Structure:

1. Search field pinned at the top with clear button. Query logic lives in the ViewModel, backed by an index built once from specs (title, keywords, aliases and legacy names, subtitle, category path), not in a composable `remember`.
2. Empty-query home, top to bottom: Recent (last 8 added Steps, horizontal row, stored per device), Favourites (starred Steps, horizontal row; star via long-press menu or a star in the detail row), then category tiles in a two-column grid. Each tile: top-level label, Step count, accent colour from the category hue family, medallion icon. Sparks tile appears first when the Flow has no Spark.
3. Category page (tap a tile): header with breadcrumb ("Steps / Device"), subcategory sections (Device shows Sensors, Connectivity, Telephony and messaging, ...), each a list of rows; subcategory chips at the top jump to sections. Sections with no visible Steps are not shown. Back returns to home with scroll state kept.
4. Search results: flat ranked list; each row shows medallion, title, subtitle, and a breadcrumb line ("Device > Connectivity") plus badges (Spark, Beta, Legacy, permission lock icon for `requiresSensitivePermission`). Ranking: exact title, title prefix, keyword/alias exact, keyword prefix, subtitle word, category name. Typo tolerance by edit distance 1 for words of 5 or more letters. Search also matches names users know from other tools and old KaivoFlow names, listed in keywords, so removing a Step does not strand people.
5. Spark versus Step: if the Flow has no Spark, the sheet opens in "Start with a Spark" mode (Sparks only, grouped Start and time, Incoming, Device events, with Manual pre-highlighted). Otherwise Sparks are hidden from home and results except behind a "Add another Spark" chip at the end of the Sparks tile row, honouring the existing `hasTrigger` rule. Search always returns Sparks when the query clearly matches one, marked with a Spark badge and a message if adding is not allowed.
6. Context awareness: opened from an output port, show a "Suggested next" row of up to 4 (e.g. after Branching suggest Merge; after Device sensors suggest Compare or Filter) derived from a static `suggestNext` list on the spec, never from usage tracking. Opened from an Agent slot chip, show only Steps with the matching role and ignore categories.
7. Colours: replace the six hues of `KaivoColors.categoryColor` with ten, grouped into a small hue set so the canvas stays calm: Sparks (existing stepTrigger), Flow control and Code (stepLogic family), Data and Files (stepTransform family), Device and Security (stepDevice family), Apps and Network (stepSaas family), AI (stepAi). Subcategories reuse the parent hue; icons differentiate.
8. Accessibility and performance: 48 dp rows, content descriptions with the category path, `LazyColumn`/`LazyVerticalGrid` with stable keys (spec id), list item animations off in reduced-motion, search debounced about 120 ms.
9. Persistence: Recent and Favourites in DataStore, stored as spec ids and resolved through the AliasMap on read so renamed Steps keep their stars. Included in backup.
10. Empty and error states: no results shows "Nothing matches" plus the three nearest categories; a Step in the list whose spec is Hidden or Removed is never shown.

## 8. Risks and open decisions for the owner

- R1: Renaming all ids (step 5.7.6) is a one-time cost for permanent consistency. Alternative: keep old ids for survivors and apply the new scheme only to new Steps. Recommendation: rename now, while the Flow base is small.
- R2: Spark registrations and persisted event matchers reference type strings in several places (`TriggerSpecs` constants, `EventTriggerMatcher`, work scheduler). Needs a grep audit before step 5.7.5.
- R3: Merges that depend on planned Steps (Summarize, Extract/Convert, Data Table) keep old Steps registered but hidden; the base is not "perfect" until those ship. Accept or schedule them before the next release.
- R4: Removing `transform.chart`, `saas.tcp` and `random` number/pick leaves existing Flows with Unavailable Steps. Acceptable if there are no external users yet; confirm.
- R5: Whether `ai.chat`, `ai.extract`, `ai.classify` stay visible (Legacy badge) or move to Hidden once the Agent exists.

## Implementation notes 2A

Status of migration step 5.7.4 (file moves and one-file-per-Step split). No id, param key or port changed; `StepGoldenTest` stayed byte-identical after every category. Categories were moved in the order device, spark, flow, data, files/net/code/security, apps, ai, then the shared kit and catalog files. The full core:model, core:engine, core:nodes (and the pure core:data) suites were green after each one.

### Final layout (`core/nodes/src/main/kotlin/com/kaivoflow/core/nodes/`)

```
kit/       NodeDsl, Conditions, MiniJson, BinarySupport, CryptoMath, JsonLite, LogicParams, ConnectorSupport
catalog/   NodeCatalog, CategoryDefs (Placement, CategoryDefs, CatalogAliases, PlacedExecutor)
spark/     TriggerSpecs, EventTriggerMatcher, MobileTriggerMath, PassThroughTrigger
  start/ (Manual, Schedule)  incoming/ (Webhook, Share)
  device_events/ (Notification, Sms, Battery, Geofence, Bluetooth, Wifi, Headset, Boot)
flow/      branching/ (If, Switch, Merge)  looping/ (Loop)  timing/ (Wait, Stop, ErrorHandler)  composition/ (SubFlow, Noop)
data/      items/ (Filter, Sort, Limit, Dedupe, Compare, PollChanges, RunInfo, SetFields, Fields, Template, Json,
                   Math, Text, Url, Split, Aggregate, Stats, Chart)
           storage/ (Table)  time/ (Date)  text/ (Xml, Html, Markdown, Ical + XmlLite, HtmlLite, MarkdownLite)
files/     convert/ (Csv, BinaryTools, CsvLite)  document/ (Pdf, PdfLite)
net/       web/ (BinaryUpload, Http, ServiceConnector, GraphQl, WebDav, Rss, Weather)  protocols/ (Tcp)
security/  crypto/ (Random, Token, Crypto)  auth/ (Totp)
code/      script/ (Code)
app/       messaging/ (Telegram, Slack, Discord)  productivity/ (GoogleSheets, Notion)
ai/        chains/ (AiChat, AiExtract, AiClassify)  shared/ (AiClient, AiSpec, AiExtractionSchema)
device/    DeviceGateway, UserFileStore
  notifications/ (NotificationSend, Toast)  telephony/ (SmsSend)  contacts_calendar/ (ContactLookup, CalendarEvent)
  location/ (Location)  sensors/ (BatteryStatus)  connectivity/ (NetworkStatus, WifiInfo)
  files_media/ (File)  system/ (OpenLink, Volume)  io/ (Clipboard, ClipboardRead, ShareText, Speak, Vibrate, Torch)
```

Each executor sits in its own file named after its class, in a package equal to its folder (`com.kaivoflow.core.nodes.device.io`). A private helper used by one Step moved with it (`ChartPoint`, `ChartSvg`, `ParamResolverLite`, `connectorBody`, `nextPageUrl`, `snippet`). A helper shared by several Steps became `internal` in a small shared file (`kit/ConnectorSupport.kt`, `ai/shared/AiSpec.kt`).

### Kept in place, and why

- Package names kept as `com.kaivoflow.core.nodes` for every class that Android modules import: `NodeCatalog`, `CatalogAliases`, `TriggerSpecs`, `EventTriggerMatcher`, `DeviceGateway`, `NoopDeviceGateway`, `UserFileStore`, `BinarySupport`, `CryptoMath`. Their files moved into folders anyway (Kotlin lets folder and package differ). The same applies to every `kit/` and `catalog/` file, so `kit` and `catalog` are folders only, not packages.
- Class names keep the `...Node` suffix. The `<Name>Step` rename of 5.3 is deferred: it touches every test and every reference for no layout benefit, and belongs with the id rename (5.7.6).
- Tests stay flat in the package `com.kaivoflow.core.nodes`; only their imports changed. Mirroring the folders under `src/test` is left for the per-Step test work (5.5.2).
- `<Category>Steps.kt` auto-assembly (5.4) and `CatalogCompletenessTest` are not part of this change: `NodeCatalog` still lists every executor by hand.
- Executors were not split into `Params` and `Ops` files (5.2 "larger Step"); this change is a move and a one-file-per-Step split only. Several files remain above the ~300 line guideline (for example `BinaryToolsNode`, `TokenNode`, `WeatherNode`, `XmlLite`).
- The `app/` folder uses the singular name from 5.1 (the task list said "apps"); the picker tree path is `app/...` too.
