# P2 Spec: Step inspector, data panels and parameter widgets

Status: proposal for owner review. Branch: `claude/phase-1-foundation`. Roadmap anchor: tasks 2.3, 2.4 and the data half of Phase 4 (editor UX), pulled forward because the inspector is the place where every other engine feature becomes usable.

Inputs read: `docs/audit/08-editor-ux.md`, `docs/audit/02-data-expressions-parameters.md`, `docs/ROADMAP.md`, the current `StepInspectorSheet.kt` (1424 lines today), `Flow.kt`, `Run.kt`, `NodeSpec.kt`, `Database.kt`, `core/design`.

Legal note. The n8n source (Sustainable Use License) was studied for behaviour only. Section 1 describes behaviour in our own words. Nothing in this spec or in the code it leads to may reuse n8n identifiers, strings, icons, assets or code. Vocabulary is KaivoFlow's: Flow, Step, Stream, Spark, Run, Item. Visual identity stays Velvet Obsidian and Champagne Gold from `core/design`.

Owner goal in one line: the Step inspector does everything the reference details view does, with a thumb, and looks like KaivoFlow.

---

## 0. Decisions at a glance

| # | Decision | Why |
|---|---|---|
| D1 | One recursive `ParamSchema` (section 4) drives the form, validation, defaults, docs and the future AI tool schema. `ParamSpec` is migrated, not kept in parallel. | Audit 02 section 5.3. Avoids a second form system. |
| D2 | A param value is either fixed or an expression, stored explicitly (`ParamValue`), not guessed from `{{` in a string. | Lets the fixed/expression toggle be lossless and lets number, boolean and option fields hold expressions. |
| D3 | The expression text stays the source of truth. Chips are a rendering of text spans, never a second model. | Copy, paste, undo, import and diff keep working. |
| D4 | Data is mapped by long-press-drag from any input row onto any expression-capable field, with tap-to-insert as an equal-rank alternative (accessibility and one-handed use). | Owner requirement plus audit 08 section 5.1. |
| D5 | Per-Step input and output items of every Run are persisted in their own table, keyed by Step id, with a bounded truncation policy. The `runs.runJson` blob stops carrying items. | Input panel, run selector, replay and pin-from-run all depend on it. |
| D6 | Schema is inferred from items (and from pins, and from a declared static schema before any Run) into one `SchemaNode` tree used by the schema view, autocomplete, drag paths and "field missing upstream" warnings. | One source for four features. |
| D7 | The inspector is a state holder (`InspectorStateHolder`) separate from `CanvasViewModel`, with a pure reducer tested on the JVM. | `CanvasViewModel` is already 972 lines; the inspector is 1424 lines of UI plus state mixed together. |

---

## 1. Behaviour inventory of the reference details view

Everything below is behaviour that matters for parity. "Must" items are needed for the owner goal, "Should" items ship in the same phase if cheap, "Later" items are noted so the design leaves room.

### 1.1 Layout and framing
- Opening a Step shows one modal view with three regions: upstream data on the left, the Step's own parameters in the middle, the Step's result on the right. The region widths are adjustable and the view keeps the canvas visible behind a scrim. (Must, adapted in section 3.)
- A header carries the Step name (editable inline), its icon, a "Test Step" action and a documentation link. (Must)
- The middle region has tabs: Parameters, Settings, plus docs for some Steps. (Must: Parameters, Behaviour, About.)
- A Spark (trigger) has no input region. In its place there is a "listen for event" empty state with a button that waits for a real event or lets the user pick sample data. (Must: Spark empty state with "Fetch sample" or "Listen once".)
- Inline hints appear under the header when the Step has a known caveat (credential missing, no input data, sub-Flow target missing). (Should)

### 1.2 Input panel (left)
- Shows the items the Step received on its most recent Run, or a "wire me up" state when no parent is connected, or a "no data yet, run the parent Step" state with an action. (Must)
- Parent Step selector: when the Step has more than one upstream Step, the user picks which parent's data to look at; for Merge-like Steps with several input ports the choice is per port. The selected parent changes what dragging produces (a field from a non-immediate parent produces a Step-qualified reference). (Must)
- Run selector on the input side when the parent ran several times in one Run (loops). (Should; Loop Over Items arrives in Phase 3)
- Same Table, JSON and Schema views as output; Binary view when any item carries attachments. (Must)
- Rows are draggable sources. While dragging, every expression-capable field shows a drop state. (Must, see 3.4)
- Hover preview of a value is replaced on touch by tap-to-peek. (Must)

### 1.3 Output panel (right)
- Header: item count ("n items", plus "of N" when truncated), run duration, run time, status. (Must)
- Display modes: Table, JSON, Schema, Binary (only when binary exists), Rendered (for AI text, later). The chosen mode is remembered per panel and per Flow session. (Must for first four)
- Pagination bar with page size options; default page 25; "show all" is guarded for large sets. (Must)
- Search box filters rows and highlights matches in all modes, including inside nested values. (Must)
- Pin: freezes the current output as the Step's mock output for later Runs, with a visible pinned state, a size limit, and edit-pinned-JSON. (Must: pin, unpin, edit, size budget)
- Test Step: runs the Step (and, if needed, its missing parents) and shows fresh data; failing Steps show the error with its details and any partial data. (Must)
- Multiple output ports: a branch selector (one tab per port with its own item count and label). (Must)
- Item count per branch is shown even when the branch is empty, with an explicit "no items on this branch" state rather than a blank panel. (Must)
- Error state shows message, the failing item index, optional cause/stack summary and, for HTTP-like Steps, the status code and response body. (Must)
- Large payloads: above a display budget the view degrades (collapsed JSON, no table cell rendering) and offers "download/share the raw data" instead of freezing the UI. (Must)
- Copy actions: copy whole output, copy one item, copy one value, copy path. (Must)
- Data type badges on schema rows and on table column headers (text, number, boolean, date, list, object, binary, null). (Must)

### 1.4 Table view
- Columns are the union of top-level keys across the visible items, in first-seen order. Nested objects and lists show as compact summaries that can be expanded. Missing values are blank, null is shown explicitly. A leading column shows the item index. Header stays visible while scrolling. Column headers and cells are drag sources for mapping. (Must)

### 1.5 JSON view
- A collapsible tree per item, with copy of any node, drag of any node (produces a path expression), and a toggle for raw text. (Must)

### 1.6 Schema view
- One tree for the Step: keys with types, a sample value, nested objects and lists expanded to a depth with the rest collapsed. Each parent Step is a collapsible section. Rows are draggable and tappable. The view works from run data, from pinned data, or from a Step's declared output shape when no data exists yet (shown dimmed with a "sample shape" label). (Must)

### 1.7 Binary view
- Per item, lists attachments with name, MIME type, size; image and PDF preview where the platform allows; open and share actions. (Must: list plus image preview; Should: PDF/audio)

### 1.8 Parameters region
- Parameters render from a declarative definition; conditions decide which fields are visible, based on other parameters (and sometimes on Step version). Hidden fields do not take part in validation. (Must)
- Every field that can hold an expression has a fixed/expression switch. In expression mode the field becomes a code-like editor with highlighted references, inline result preview, error marking and autocomplete from the incoming data and built-in helpers. (Must)
- Dropping a data field onto a fixed field flips it into expression mode and inserts the reference; dropping onto an expression field inserts at the drop position. (Must)
- Expression result preview shows the resolved value and its type, using the selected input item as context; an item picker lets the user preview against a different item. (Must)
- A full-screen expression editor opens from any expression field, with the template on top, the live result below, and the data browser beside or under it. (Must)
- Parameter issues (required missing, invalid format, bad expression) are shown on the field, and the Step shows a count. (Must)
- Long option lists are searchable; options may carry description text; some are loaded dynamically from the service using the chosen credential. (Must static+search; Should dynamic)
- Options can have per-value descriptions and "notice" fields that explain consequences. (Must)
- Resource picker: a field that lets the user choose a record by list (searchable, loaded remotely), by URL or by raw id, with an "open in browser" link. (Should)
- Fixed collection and collection: repeatable groups and "add optional field" menus with reorder and delete. (Must)
- Assignment list: rows of name, type, value used by Edit Fields; supports dropping fields to create rows. (Must)
- Filter builder: conditions with left value, operator (typed by the left value's type), right value, an AND/OR combinator, "ignore case", and loose type validation toggle. (Must)
- Credential picker: chooses from saved credentials of the accepted kinds, "create new", "edit", and shows a test result. (Must)
- Code editor: monospace, language aware, symbol toolbar. (Must; see 3.9)
- Hints under fields, and warnings when an expression references a Step that is not upstream or a field not seen in upstream data. (Must)

### 1.9 Settings tab
- Per Step: notes with an option to display them on the canvas, "always output data" even when empty, "execute once" using only the first item, retry on failure with tries and wait, behaviour on error (stop, continue, continue and output the error on a separate branch), timeout, and a list of version/metadata. (Must; maps to KaivoFlow's "Behaviour" section.)

### 1.10 Pinned data
- Pin from current output, from a previous Run, or by editing the JSON by hand. Pinned Steps do not execute during manual Runs; they replay the mock output. A size ceiling is enforced and explained. Pinned state is visible on the card and in the panel header. Pinned data is part of the Flow document (exported with it). (Must)

### 1.11 Not copied on purpose
Collaboration cursors, AI "from AI" parameter overrides, curl import modal and cloud-specific gating are out of scope for this spec. Curl import is a later Step-specific feature of HTTP Request.

---

## 2. Parameter type catalog

Notation: `expr` is the expression capability: **Never**, **Allowed** (fixed by default, can switch), **Only** (always an expression). Conditional display rules are the same for every type (section 2.20). Validation always runs on the resolved value for expressions at Run time and on the literal at design time; at design time an expression field is only checked for syntax and unknown references.

Common to every type: `key`, `label`, `hint`, `required`, `default`, `advanced`, `show`, `expr`, `rules`. Defaults are applied by the resolver, not by each executor (audit 02 section 2.3).

### 2.1 Text (string)
- Widget: single-line field; `multiline = true` gives an auto-growing field (3 to 8 lines, expand to full-screen editor); `format` adds a keyboard type and checks: `Plain`, `Url`, `Email`, `Phone`, `Path`, `Template`.
- Validation: required, min/max length, regex, format.
- Expr: Allowed. Chips render inside the text; a text field may mix literal text and several chips.
- Extras: character counter when max length is set; paste sanitises smart quotes only if `format` is `Url` or `Path`.

### 2.2 Number
- Widget: numeric field with stepper buttons (48dp), unit suffix, optional slider when both min and max are set and the range is under 1000 steps; integer or decimal; locale-independent storage (dot).
- Validation: numeric, min, max, step, integer.
- Expr: Allowed. In expression mode the preview must resolve to a number; text that parses as a number is accepted with a warning pill "converted from text".
- Edge: empty is "unset", not zero; zero must be distinguishable.

### 2.3 Boolean
- Widget: switch row with label and a one-line hint; tri-state is not offered (use Choice with Unset for that).
- Validation: none.
- Expr: Allowed. Expression result must be boolean or the strings "true"/"false"; anything else is a validation issue shown in the preview.

### 2.4 Choice (options)
- Widget by size: 2 to 4 options, segmented control; 5 to 8, radio sheet rows; more than 8 or `searchable`, a bottom sheet with a search field, grouped headers, and per-option description and icon. Selected option shows its description under the field.
- Validation: value must be one of the options; a stale value (option removed) shows "no longer available" and does not silently reset.
- Expr: Allowed. Result must match an option value; the preview shows the matching label.
- Dynamic options: `OptionSource.Dynamic(loader, dependsOn)`; loaded through the engine using the Step's credential; states loading, empty, failed (retry). Last good list is cached per credential for the session.

### 2.5 Multi choice
- Widget: chips with a search sheet for long lists; "select all" and "clear".
- Validation: each selected value must exist; min/max selection count.
- Expr: Allowed (whole value; result must be a list of option values).

### 2.6 Date and time
- Widget: tap opens a Material date picker plus a time picker; a segmented control picks `Date`, `DateTime`, `Time`, and a timezone chip (default Flow timezone). A "Relative" sub-mode offers now plus/minus N units. Value is stored as ISO-8601 string with offset.
- Validation: parseable, min, max.
- Expr: Allowed (result may be a date value or ISO string). Preview shows local and UTC.

### 2.7 JSON
- Widget: monospace editor with a bracket helper row (`{ } [ ] " : ,`), "Format", live parse status (first error with line and column), collapse to tree view for large values. Expressions inside string values are allowed (`"id": "{{ ... }}"` is one chip inside a JSON string); the whole value can also be one expression.
- Validation: parses as JSON after expression substitution at Run time; at design time parses with expression placeholders replaced by a neutral token. Optional `expect` of object or list.
- Expr: Allowed (whole-value or embedded).

### 2.8 Code
- Widget: monospace editor, line numbers, symbol bar (tab, brackets, quotes, `=>`), undo/redo, find, theme from tokens, full-screen mode. No autocomplete in the base app beyond built-in word completion and the data names from the schema as snippets.
- Validation: language-specific syntax check where available (JSON, JS via the sandbox parser when 2.5 lands), size limit.
- Expr: **Never** (the body is code, not a template). This is the secret/code rule from Roadmap 1.2.
- Languages: `JavaScript`, `Json`, `Sql`, `Plain`.

### 2.9 Color
- Widget: swatch row (Champagne palette plus recents) and a hex field with a picker sheet; alpha optional.
- Validation: hex pattern.
- Expr: Allowed (result must be a hex string).

### 2.10 Collection (optional fields bag)
- Widget: a card with an "Add field" button that lists the declared optional children (searchable if more than 6); each added child renders with its own widget, a remove button, and drag handle for order. Hidden when empty with a quiet empty row.
- Value shape: map of the chosen child keys.
- Validation: each child by its own rules; unknown keys flagged on import.
- Expr: Never on the container; Allowed on children.

### 2.11 Fixed collection (named groups, optionally repeatable)
- Widget: a list of group cards. Each group declares its own children. If `repeatable`, an "Add" row appends a group and cards are swipe-to-delete with undo, drag to reorder, and collapse. Group card header shows a summary from `itemLabelKey` or the first child value.
- Value shape: map from group name to a map or a list of maps.
- Validation: `minItems`, `maxItems`, each child by its rules.
- Expr: Never on the container; Allowed on children. Dropping a field onto the collection header creates a new row prefilled where a child is the obvious target (see 3.4).

### 2.12 Assignments list
- Widget: rows of `name | type | value`. Name is a text field with suggestions from the schema; type is a compact select (text, number, boolean, list, object, any); value is an expression-capable field typed by the type (a switch for boolean, number stepper for number, JSON editor for object). Row chrome: drag handle, swipe to delete, long-press for duplicate. Footer: "Add assignment" and "Drop field here". Dropping a field creates a row named after the key with the reference as value and the type inferred from the schema.
- Validation: unique non-empty names (dotted names allowed to write nested paths), type matches the resolved value.
- Expr: value Allowed; name Allowed.
- Related option: "keep other fields" lives on the Step as a Choice, not inside the list.

### 2.13 Filter conditions builder
- Widget: a vertical list of condition cards: left value (expression-capable field with drop target), operator chip, right value (hidden for unary operators). Between cards a combinator pearl toggles AND/OR (one combinator per group in v1; nested groups are Later). Toggles at the footer: "Ignore case" and "Lenient types". A plain-language summary of the whole filter shows on the card on the canvas.
- Operators are chosen by the left side's type and grouped: text (is, is not, contains, does not contain, starts with, ends with, matches pattern, is empty, is not empty), number (equals, not equals, greater, greater or equal, less, less or equal, between), boolean (is true, is false), date (is, before, after, between), list (contains, length equals, length greater, is empty), object (has key, is empty), any (exists, does not exist). Switching the left type keeps the right value if compatible, otherwise clears it with an undo toast.
- Value shape: `{ combinator, conditions: [{ id, left, op, right }], options }`.
- Validation: at least one condition when required, right value present for binary operators, type compatibility, pattern compiles.
- Expr: left and right Allowed; operator Never.

### 2.14 Resource picker
- Widget: a field with a mode selector inside it: `From list` (opens a searchable bottom sheet with pagination and remote loading), `By URL` (field plus pattern-extracted id preview), `By ID` (plain text). Shows the chosen item's name with a link-out chip. States: loading, empty, failed with retry, credential missing (CTA to pick credential).
- Value shape: `{ mode, value, cachedName? }`.
- Validation: value non-empty; URL mode must match the declared pattern; list mode value must be in the last loaded list or be refreshable.
- Expr: Allowed for value in every mode; mode selection Never.
- Search is sent to the loader with a cancellation scope; typing debounces 300 ms.

### 2.15 Credential picker
- Widget: a field showing the chosen credential name with kind badge and a status dot; tap opens a sheet of matching credentials (filtered by `accepts`), a "New" row, and an "Edit" action per row. Shown only when the Step declares credential slots, and each slot has its own `show` rule (for example, only for the "authenticated" mode). A "Test" button runs the credential's own test when it has one.
- Value shape: credential id (never the secret).
- Validation: required slots non-empty, id exists in the vault, kind accepted; deleted credential shows an error pill, not blank.
- Expr: **Never**. (Prevents secrets or ids being templated from data.)
- Current code: `CredentialPickerField` and `CredentialChoiceRow` are reused (section 7).

### 2.16 Notice and hint
- Widget: an in-form callout with tone `Info`, `Warning`, `Danger`, optional action button (for example "Open Credentials"), supports simple inline links and bold; never takes a value.
- Display: participates in `show` rules, so a notice can appear only for certain modes.
- Hint (short text under a field) is a property of every param, not a separate kind.

### 2.17 Secret
- Widget: masked field with a reveal toggle (auto-hides after 10 seconds and on backgrounding), paste button, no autofill, no suggestions, `FLAG_SECURE`-compatible; never shown in previews, copy menus or run data.
- Validation: required, optional pattern (prefix check).
- Expr: **Never**. Redacted in every data view and in exports.
- Where possible a secret should live in a credential, not a param; this type exists for tokens that are genuinely per-Step.

### 2.18 File or binary reference
- Widget: a field with two modes: `From data` (pick the binary property name from the input schema's binary section, with a thumbnail of the first item) and `From device` (system file picker via Storage Access Framework; stores a persistable URI and displays name and size). Output-file variants take a file name template.
- Value shape: `{ mode, property | uri }`.
- Validation: property exists in at least one upstream item (warning only); URI permission still valid (error with "pick again").
- Expr: Allowed for the property name and the file name; Never for the URI.

### 2.19 Key-value list (convenience)
Implemented as a Repeater of two text children (`name`, `value`), because headers, query parameters and form bodies share it. Widget is the "key-value rows editor" from audit 08 section 5.9: swipe to delete, drag handle, `+` row, each value expression-capable.

### 2.20 Conditional display rules (all types)
- `ShowWhen` is a small tree: `All(list)`, `Any(list)`, `Not(x)`, and leaves `Eq`, `Ne`, `In`, `Gt`, `Lt`, `Exists`, `Empty` on a **param path**. Paths address siblings (`mode`), parents (`../auth`) and root (`/operation`). Leaf values may refer to the Step version and to whether a credential slot is filled.
- Hidden params: not rendered, not validated, not sent to executors (the resolver omits them and applies no default), and their stored values are kept so toggling back restores them. A "Clean hidden values on save" step is not done by default.
- Evaluation uses the current **literal** value of the controlling param. If the controlling param is an expression, show rules treat it as unknown and render the field visible with a muted "depends on expression" badge.
- `advanced = true` params live under a collapsed "More options" row, and the row shows a count of non-default values inside it.

### 2.21 Validation model (all types)
- `ParamRule`: `Required`, `MinLength`, `MaxLength`, `Min`, `Max`, `Pattern`, `OneOf`, `JsonValid`, `UrlValid`, `Custom(id)` (named validators registered in code, never lambdas inside schema data).
- `ParamIssue(path: ParamPath, severity: Error|Warning, code: String, message: String)`. Messages are KaivoFlow strings via resource ids.
- One walker, `ParamValidator.validate(schema, values, context)`, is the only place issues come from (inspector, design-time `FlowValidator`, import).

---

## 3. Touch-first design

### 3.1 Principles
One thumb; nothing depends on hover, right click or a hardware keyboard (all are supported as extras); 48dp minimum targets; the canvas context is never lost; every drag has a tap equivalent; every state is reachable by TalkBack.

### 3.2 Layout by window size class

**Phone portrait (Compact width).** Full-height bottom sheet with three detents (peek 30%, half, full). Header: back chevron or close, editable Step name, status badge, overflow (Pin, Run to here, Disable, Duplicate, Delete with confirm). Below: a segmented pill with live badges: `Input 12`, `Parameters`, `Output 3`, and an optional pin pearl. Content is a **horizontal pager** between the three pages; swiping works, tapping a segment works, and the pager position is saved in the Flow session. A single gold footer button, `Test Step`, stays above the keyboard; when the sheet is at peek it collapses to a pearl. When dragging a field from Input, the sheet **auto-switches to Parameters after a 600 ms hold at the right screen edge** (edge-dwell), so a field can be carried across pages; releasing a drag over the page segments also switches pages. As a simpler default the Input page can be pulled into a **half-height Mapper tray** over the Parameters page from the accessory bar (3.5), which is the primary phone path.

**Phone landscape.** Side sheet at 60% width, with Input | Parameters | Output as the same segmented pager, plus the Mapper tray docked at the left half so Input and Parameters are visible together.

**Tablet, foldable, large landscape (Medium and Expanded width).** Three panes side by side: Input (28%), Parameters (44%), Output (28%), with draggable dividers (min 280dp per pane) and a collapse chevron on each side pane. The canvas stays visible behind a scrim at the top and edge. Drag from Input directly onto Parameters. Widths are remembered per device.

**Shared.** Every pane is the same composable (`InputPanel`, `ParamsPanel`, `OutputPanel`) hosted differently, so behaviour never forks by window size.

### 3.3 Data panels

**Panel header** (Input and Output). Row 1: source selector (Input: parent Step selector as a chip that opens a sheet listing parents with their last-run time and item count; Output: branch tabs when the Step has more than one output port). Row 2: run selector `Run 2 of 5` as a stepper (only when more than one Run exists) and a menu to pick any stored Run by time; item count `12 items` with `of 480` if truncated; mode switch `Schema | Table | JSON` plus `Binary` when present; search icon.

**Pagination.** Default 25 items per page, sizes 10, 25, 50, 100; a compact bottom bar with previous, next, page `3 / 20`, jump to page sheet. In Schema mode paging is by item for the sample selector ("shape of: all items | item 3").

**Search.** Debounced 250 ms, searches keys and values; matches highlighted; in JSON mode the tree auto-expands to matches with a match counter and next/previous buttons; in Table mode non-matching rows hide and a banner shows `4 of 25 rows`.

**Table view.** Lazy rows; sticky header row and sticky index column; column widths adapt (min 96dp, max 240dp, double-tap header to fit); numbers right aligned; nested cells show a summary chip `{3}` or `[5]` that opens an inline expander or a value sheet; null drawn as muted `null`; empty string as muted `""`. Header cells show a type icon. Long-press a cell: Copy value, Copy path, Map to field (opens the target picker), Pin this item (Later). Long-press-drag from a cell starts a mapping drag for that column (`$json.column`).

**JSON view.** Lazy tree per item with item headers; keys coloured by type tokens; expand/collapse all; copy node, copy as expression, copy raw; long-press-drag any node. Toggle "raw" shows pretty-printed text with selectable lines for very small payloads only.

**Schema view.** Tree sourced from `SchemaNode`: rows show a type icon (distinct shape and colour, not colour alone), key, and a one-line sample; arrays show `[0]` with element schema; objects expand; optional/mixed fields show a `?` or `mixed` badge and `seen in 7 of 12`. A section per Step when the Mapper tray shows several parents. Rows are both tap-to-insert and long-press-drag. A "Sample shape" tag marks inferred-from-declaration data before any Run.

**Binary view.** Cards per attachment: thumbnail (images), file name, MIME badge, size; actions Open, Share, Copy name, Map name.

**Pinned state.** When the Output page shows pinned data, a gold hairline frames it and a strip reads `Pinned, 3 items, 2 KB, 4 min ago` with Edit, Unpin. Edit opens a JSON editor with validation and the size budget.

**States.**
- Empty, no connection: illustration-free card, one line, `Connect a Step` button.
- Empty, no data: one line `Run the previous Step to see its data`, buttons `Run previous Step` and `Use sample`.
- Empty, ran with zero items: `This Step ran and produced no items`, plus the reason chip when known (filter dropped all, branch not taken).
- Loading: skeleton rows in the same shape as the content; a determinate bar when page loading from storage.
- Truncated: banner `Showing first 200 of 3,400 items, stored data is capped`, with `Run again with full data` when the Flow is in debug capture mode.
- Error: rose card with message, item index, `Copy details`, `Open Step settings`, and, when available, the HTTP status and body in a collapsible. Partial data remains viewable under it.
- Redacted: when a field is secret, the cell shows `hidden` with a lock icon; no way to reveal in data views.
- Too large: `Too large to display here` with `Share raw data` and `Show first 25 items`.

### 3.4 Drag to map (long-press), with a drop target everywhere

**Source rows.** Any row in Table cells and headers, JSON nodes and Schema rows, in both Input and Mapper tray. A 300 ms long-press starts a drag (haptic tick); the touch slop is raised to 8dp so scroll still works. The drag shadow is a pearl chip showing the field name and type icon; the chip shows the final reference text after a short press-and-hold on a target.

**Drop targets.** Every expression-capable field (`expr != Never`) registers as a target when the drag starts. Targets glow with a gold hairline; the nearest target under the finger gets a stronger glow and a label `Insert here`. Non-capable fields (code, secret, credential, notice) dim. The drag layer is the whole inspector, so targets are reachable even when partly scrolled; the parameter list auto-scrolls when the finger is within 64dp of the top or bottom edge.

**What a drop does.**
| Target | Result |
|---|---|
| Fixed text, number, boolean, choice, date, color field | Field flips to expression mode with the reference. Previous literal is kept in an undo toast ("Switched to expression, Undo"). |
| Expression field | Inserts the reference chip at the drop position (nearest caret), or replaces the selection if the drop lands on it. |
| Empty Assignments list or its footer | Creates a row: name from the key, value the reference, type from the schema. |
| Key-value list | Creates a row with name from the key and value the reference. |
| Filter condition left value | Fills the left value; operator group follows the schema type. Dropping on the card background adds a new condition. |
| Fixed collection with a clear target child | Adds a row with the reference in the first compatible child. |
| JSON field | Inserts `{{ ref }}` as a value at the caret; inside a string literal, inserts it unquoted inside the quotes. |
| Code, secret, credential, notice | Rejected with a short shake and a snackbar `This field cannot take data references`. |

**Reference text.** From the immediate input of the selected parent: `{{ $json.field.sub[0] }}`. From another Step: `{{ $step("Name").matching.json.field }}` where `matching` is the lineage-paired item (audit 02 section 5.1). Keys that are not simple identifiers use bracket quoting. Dragging an object or array node references the whole value. Dragging a Table column header references that field. A reference to a field in `binary` produces `$binary.name.fileName`-style references through the same builder (`ReferenceBuilder`, section 5).

**Tap-to-insert alternative (equal rank).**
1. A focused expression-capable field raises the **accessory bar** above the keyboard: `Fields`, `Previous Step`, `Vars`, `Now`, `fx`, and format helpers.
2. `Fields` opens the Mapper tray (half sheet): the same Schema, Table and JSON views as Input, restricted to selectable rows.
3. A tap on a row inserts the pearl chip at the caret and keeps the tray open (multi-insert); a close handle returns to the keyboard.
4. When no field is focused, long-press on an input row opens "Map to..." with a list of expression-capable fields in the current Step; one tap inserts.
5. TalkBack: each row exposes the custom action `Insert into field` that opens the same list.

### 3.5 Fixed and expression toggle
- Each expression-capable field has a trailing two-state pearl: `Fixed` (muted) and `fx` (gold). Tapping switches mode. Switching to expression keeps the current literal text as the starting expression body when the type is text, or wraps it as a string/number literal for others; switching back evaluates the expression against the selected preview item and offers the result as the literal (confirm sheet if the result is not representable). Undo is always available.
- In expression mode the field gets a gold hairline, mono font, and the preview line below. The container field of Never fields shows no pearl.
- Params that are `expr = Only` start in expression mode with no pearl and a small `fx` marker.

### 3.6 Inline expression rendering
- Text spans between `{{` and `}}` render as **chips**: Step-qualified refs show a small Step medallion and path; `$json` refs show a field icon and path; helper calls show a function icon. Chip colour uses state: gold when the reference resolves in the preview item, amber when the field is absent in upstream data, rose when the expression has a syntax or runtime error.
- Interaction: tap a chip to peek at its value and type and to edit the path in a small popup; long-press and drag a chip to move it; backspace right after a chip deletes the whole chip as one undo step; selecting text across chips copies the underlying text.
- Implementation shape: a `ParamTextField` built on `BasicTextField` with a `TextFieldValue`, an `ExprSpanParser` producing spans from the AST/template scanner, and inline content for chips drawn via `AnnotatedString` + `InlineTextContent` or, if cursor behaviour is not reliable, `VisualTransformation` with fixed-width placeholders. (Verify on device; flagged risk R1.)
- The visible text is the real expression text, so IME composition, paste and share intents behave normally.

### 3.7 Live preview
- Under every expression field a one-line preview pill: resolved value (truncated, mono), type icon, and `item 1 of 12` selector (tap to cycle or pick). Evaluation is debounced 150 ms, runs off the main thread with the engine's budgets, and is cancelled on new input.
- The pill shows: a value, or `no data yet` (grey) when the referenced Step has not run, or an error (rose) with the exact span highlighted in the field and a message such as `Unknown field "nme", did you mean "name"?`.
- Tap the pill to open the full-screen expression editor.
- The full-screen editor: template field on top with chips, a result panel below (type, value pretty-printed, copy), and the Mapper tray as a bottom sheet. It includes autocomplete (3.8).

### 3.8 Autocomplete
- Triggered after `{{`, after `.`, after `$`, after `(`, or on demand with the accessory bar's `fx`.
- Suggestions come from (a) `SchemaNode` of the selected input and of upstream Steps, (b) built-in helpers filtered by receiver type, (c) variables, (d) `$run`, `$flow`, `$now`, `$today`. Each suggestion shows a type icon, a sample value from the preview item, and one-line help.
- Shown as a horizontally scrolling **suggestion strip** in the accessory bar (phone) or a popup (tablet). Tap inserts and moves the caret to the logical next position (inside parentheses for calls).
- Ranking: exact prefix, then fuzzy; fields seen in all items before fields seen in some.

### 3.9 Other widget behaviour for thumbs
- **Choice sheets** with search and descriptions (2.4).
- **Number steppers** with long-press auto repeat (2.2).
- **Code editor** has symbol bar and a "Full screen" action; text size follows the system font scale.
- **JSON editor** bracket helper row and format button (2.7).
- **Collapsible Advanced** with a changed-count badge (2.20).
- **Search within parameters**: a search icon in the Parameters header filters fields by label and hint for Steps with more than 12 params.

### 3.10 Parameters page chrome
- Sticky header with Step medallion, name, status.
- Issue banner at top when the Step has errors: `3 issues`, tap to jump to the next one.
- Sections in the order: Credential, primary params, `More options`, then (separate tab) Behaviour and About.
- `Behaviour` tab: On error (segmented: Stop, Continue, Send to error Step), Retry (tries stepper, wait stepper), Timeout, Always output data, Run once for all items, Notes with `Show on canvas`.
- `About` tab: short Step description, field reference generated from the schema (same source as docs), version.

### 3.11 Accessibility
- All drag actions have an equivalent single-tap path (3.4). Row `semantics` include name, type and sample; type is never conveyed by colour alone (icon shape and text).
- Minimum 48dp targets, including chips (chip hit area expands with padding even when the visual is smaller).
- Expression chips expose their full text as content description, and a custom action `Edit reference`.
- Preview pills are live regions with polite announcement when the result changes after the user stops typing.
- Focus order follows reading order and field order; the accessory bar is reachable by swipe navigation.
- Respect font scale up to 200% (table switches to a card-per-item layout when columns would drop below readable width), reduced motion (no glow pulse, drop highlight stays static), and high-contrast (hairline widths grow).
- Haptics only as confirmation; every haptic has a visual or spoken equivalent.
- Contrast: gold on obsidian passes 4.5:1 for text; check disabled and muted tokens in the design-token test (task T20).

### 3.12 Visual language
Jewel Cards for groups and for each Step in the tray; pearls for chips and toggle; gold hairline for focus, active target and expression mode; Velvet Obsidian surfaces; Champagne Gold for the primary action only; amethyst type badges as today; rose for errors; violet vignette is reserved for Replay. No new colours: new roles are added as tokens in `KaivoColors.kt` only.

---

## 4. State model and ParamSchema

### 4.1 ParamSchema (pure Kotlin, in `core/model`)

```kotlin
@Immutable
data class ParamSchema(
    val version: Int,
    val params: ImmutableList<ParamDef>,
    val credentialSlots: ImmutableList<CredentialSlot> = persistentListOf(),
    val groups: ImmutableList<ParamGroup> = persistentListOf(),   // visual sections
)

@Immutable
data class ParamDef(
    val key: String,
    val label: String,
    val kind: ParamKind,
    val required: Boolean = false,
    val default: ParamValue? = null,
    val hint: String? = null,
    val placeholder: String? = null,
    val expr: ExprMode = ExprMode.Allowed,
    val rules: ImmutableList<ParamRule> = persistentListOf(),
    val show: ShowWhen = ShowWhen.Always,
    val advanced: Boolean = false,
    val group: String? = null,
    val docs: String? = null,               // long text for the About tab
)

enum class ExprMode { Never, Allowed, Only }

sealed interface ParamKind {
    data class Text(val multiline: Boolean = false, val format: TextFormat = TextFormat.Plain) : ParamKind
    data class Number(val min: Double? = null, val max: Double? = null, val step: Double? = null,
                      val integer: Boolean = false, val unit: String? = null) : ParamKind
    data object Bool : ParamKind
    data class Choice(val options: OptionSource, val searchable: Boolean = false) : ParamKind
    data class MultiChoice(val options: OptionSource, val min: Int = 0, val max: Int? = null) : ParamKind
    data class DateTime(val mode: DateTimeMode = DateTimeMode.DateTime) : ParamKind
    data class Json(val expect: JsonShape = JsonShape.Any) : ParamKind
    data class Code(val language: CodeLanguage) : ParamKind
    data object Color : ParamKind
    data class Collection(val optional: ImmutableList<ParamDef>) : ParamKind
    data class Group(val children: ImmutableList<ParamDef>) : ParamKind                // one object
    data class Repeater(val children: ImmutableList<ParamDef>, val minItems: Int = 0,
                        val maxItems: Int? = null, val itemLabelKey: String? = null) : ParamKind
    data class Assignments(val allowedTypes: ImmutableSet<ValueType> = ValueType.all) : ParamKind
    data class Conditions(val allowNested: Boolean = false) : ParamKind
    data class ResourcePick(val modes: ImmutableSet<ResourceMode>, val loader: String?,
                            val urlPattern: String? = null) : ParamKind
    data class CredentialPick(val accepts: ImmutableSet<CredentialKind>) : ParamKind
    data class Notice(val tone: NoticeTone, val action: NoticeAction? = null) : ParamKind
    data object Secret : ParamKind
    data class FileRef(val accept: ImmutableSet<String> = persistentSetOf(), val output: Boolean = false) : ParamKind
}

sealed interface OptionSource {
    data class Static(val options: ImmutableList<ParamOption>) : OptionSource
    data class Dynamic(val loader: String, val dependsOn: ImmutableSet<String>) : OptionSource
}

sealed interface ShowWhen {
    data object Always : ShowWhen
    data class All(val of: List<ShowWhen>) : ShowWhen
    data class Any(val of: List<ShowWhen>) : ShowWhen
    data class Not(val of: ShowWhen) : ShowWhen
    data class Eq(val path: String, val value: Any?) : ShowWhen
    data class In(val path: String, val values: List<Any?>) : ShowWhen
    data class Cmp(val path: String, val op: CmpOp, val value: Double) : ShowWhen
    data class Exists(val path: String) : ShowWhen
    data class CredentialSet(val slot: String) : ShowWhen
}

/** A stored parameter value: explicit mode, never inferred from text. */
sealed interface ParamValue {
    data class Fixed(val value: Any?) : ParamValue
    data class Expr(val text: String) : ParamValue
}
```

Serialization of `ParamValue`: a fixed value is stored as the plain JSON value; an expression is stored as `{"kx": "<text>"}`. A string that happens to be an object with only `kx` cannot occur as fixed because fixed objects are only produced by Json/Group kinds with known keys. A legacy reader maps any old string containing `{{` to `Expr` with the same text, and keeps all other strings `Fixed`. `StepNode.params` becomes `ImmutableMap<String, ParamValue>`; the migration is a one-way step at load, written back on first save, covered by golden tests for every bundled template.

`NodeSpec` gets `schema: ParamSchema` and loses `params: List<ParamSpec>`. A compat function `ParamSpec.toDef()` exists for the migration period only and is deleted at the end of T19.

One schema, five consumers:
1. `ParamValidator` (design-time issues, Run-time issues).
2. `ParamsPanel` (renders the form from the schema).
3. `ParamResolver` (defaults, hidden omission, per-item expression evaluation, typed `ResolvedParams` for executors).
4. Docs generator (`About` tab and `docs/steps/*.md` via a JVM task that walks the registry).
5. Tool schema exporter (the Phase 6 agent): emits JSON Schema from `ParamSchema`.

### 4.2 Inspector state (UI layer, `feature/canvas`)

```kotlin
@Immutable
data class InspectorState(
    val stepId: String,
    val tab: InspectorPage,                       // Input, Parameters, Output
    val paramsTab: ParamsTab,                     // Parameters, Behaviour, About
    val params: ParamsState,
    val input: DataPanelState,
    val output: DataPanelState,
    val drag: MapDragState?,                      // non-null while dragging
    val mapper: MapperTrayState,
    val focusedField: ParamPath?,
    val issues: ImmutableList<ParamIssue>,
    val testState: TestState,                     // Idle, Running(stepIds), Failed(...)
    val readOnlyRun: String?,                     // non-null in replay mode
)

@Immutable
data class ParamsState(
    val values: ImmutableMap<String, ParamValue>,
    val visibility: ImmutableMap<ParamPath, Boolean>,
    val previews: ImmutableMap<ParamPath, PreviewState>,
    val expandedGroups: ImmutableSet<String>,
    val advancedOpen: Boolean,
    val optionLoads: ImmutableMap<ParamPath, OptionLoadState>,
)

@Immutable
data class DataPanelState(
    val side: PanelSide,                          // Input or Output
    val sourceStepId: String?,                    // selected parent (Input) or this Step (Output)
    val availableSources: ImmutableList<SourceChip>,
    val port: Int,
    val ports: ImmutableList<PortChip>,
    val runRef: RunSelection,                     // Latest, Specific(runId, runIndex), Pinned
    val runs: ImmutableList<StepRunSummary>,
    val mode: DataViewMode,                       // Schema, Table, Json, Binary
    val page: PageRequest,                        // offset, size
    val search: String,
    val content: DataContent,                     // see below
)

sealed interface DataContent {
    data object NoConnection : DataContent
    data object NoData : DataContent
    data object Loading : DataContent
    data class Items(val page: ItemPage, val schema: SchemaNode, val pinned: PinInfo?) : DataContent
    data class Empty(val reason: EmptyReason) : DataContent
    data class Failed(val error: StepErrorInfo, val partial: ItemPage?) : DataContent
    data class TooLarge(val bytes: Long) : DataContent
}

@Immutable
data class MapDragState(
    val source: MapSource,                        // path, stepRef, schema type
    val reference: String,                        // rendered expression text
    val pointer: Offset,
    val hoverTarget: ParamPath?,
    val eligibleTargets: ImmutableSet<ParamPath>,
)

sealed interface InspectorEvent { /* ParamChanged, ToggleExpr, DropOn, InsertAt, SelectSource,
    SelectRun, SelectPort, SetMode, SetPage, Search, TestStep, Pin, Unpin, EditPin, ... */ }
```

State holder:

```kotlin
class InspectorStateHolder(
    private val flow: StateFlow<Flow>,
    private val stepData: StepDataStore,
    private val schemaService: SchemaService,
    private val exprAssist: ExpressionAssist,
    private val validator: ParamValidator,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<InspectorState>
    fun dispatch(event: InspectorEvent)
}
```
`InspectorReducer.reduce(state, event): Pair<InspectorState, List<InspectorEffect>>` is pure and JVM-tested; effects (load page, evaluate preview, run test) are executed by the holder. `CanvasViewModel` owns only "which Step is open" and forwards persistence of param changes.

Flows: `flow.map { selected step }`, `stepData.observeRuns(stepId)`, `stepData.page(...)` as a `Flow<ItemPage>` keyed by `(runRef, port, page, search)` with `flatMapLatest`; previews through `combine(values, selectedPreviewItem)` with `debounce(150)` and `mapLatest`; validation through `combine(schema, values)`.

---

## 5. Engine and storage requirements (API sketches)

All signatures below are sketches for review. No implementation is part of this spec.

### 5.1 Item model with lineage (audit 02 section 5.1)

```kotlin
data class Item(
    val json: JsonObject,
    val binary: ImmutableMap<String, BinaryRef> = persistentMapOf(),
    val error: ItemError? = null,
    val sources: ImmutableList<ItemRef> = persistentListOf(),
)
data class ItemRef(val stepId: String, val runIndex: Int, val itemIndex: Int)
data class ItemError(val message: String, val stepId: String, val kind: String, val at: Long)
```
`FlowData` is replaced by `Item`; a typealias `FlowData = Item` exists for one release only.

### 5.2 What is stored per Step execution

```kotlin
data class StepExecutionKey(val runId: String, val stepId: String, val runIndex: Int)

data class StepExecutionRecord(
    val key: StepExecutionKey,
    val status: StepRunStatus,
    val startedAt: Long,
    val finishedAt: Long?,
    val attempts: Int,
    val inputs: ImmutableList<PortSnapshot>,      // one per input port (toPort)
    val outputs: ImmutableList<PortSnapshot>,     // one per output port (fromPort)
    val error: StepErrorInfo?,
    val pinned: Boolean,                          // this execution replayed pinned data
)

data class PortSnapshot(
    val port: Int,
    val label: String?,
    val itemCount: Int,                           // true count, even if truncated
    val storedCount: Int,
    val bytes: Long,                              // size of stored JSON
    val truncation: Truncation?,                  // null when complete
    val schema: SchemaNode?,                      // computed at write time from the full set when cheap, else from stored items
    val parents: ImmutableList<ParentRef>,        // which Step and port fed this input port
)

data class Truncation(val reason: TruncationReason, val keptItems: Int, val originalItems: Int,
                      val clippedValues: Int)

enum class TruncationReason { ItemCap, ByteCap, ValueCap }
```

Room tables (new migration, with schema export on, tests required):

```kotlin
@Entity(tableName = "step_executions",
        primaryKeys = ["runId", "stepId", "runIndex"],
        indices = [Index("stepId", "startedAt"), Index("flowId", "startedAt")])
data class StepExecutionEntity(
    val runId: String, val stepId: String, val runIndex: Int, val flowId: String,
    val status: String, val startedAt: Long, val finishedAt: Long?, val attempts: Int,
    val errorJson: String?, val pinned: Boolean,
)

@Entity(tableName = "step_port_data",
        primaryKeys = ["runId", "stepId", "runIndex", "direction", "port"],
        foreignKeys = [ForeignKey(StepExecutionEntity::class, ...onDelete = CASCADE)])
data class StepPortDataEntity(
    val runId: String, val stepId: String, val runIndex: Int,
    val direction: String,                         // "in" or "out"
    val port: Int, val itemCount: Int, val storedCount: Int, val bytes: Long,
    val truncationJson: String?, val schemaJson: String?, val parentsJson: String,
    val itemsBlob: ByteArray,                      // compressed JSON lines, one item per line
    val offsetsBlob: ByteArray,                    // varint offsets for random access paging
)
```
Items are stored as gzip-compressed newline-delimited JSON with a side table of line offsets so a page of 25 can be read without decompressing everything (decompress with seek on block boundaries of 64 items). Binary bytes are never stored; only `BinaryRef`.

### 5.3 Truncation policy
Defaults (settings can lower, never exceed hard maxima):

| Limit | Default | Hard max | Applies to |
|---|---|---|---|
| Items stored per port | 200 | 1000 | each input and output port of each execution |
| Bytes stored per port | 512 KB | 2 MB | after item cap |
| Single value clipped to | 8 KB | 64 KB | strings and base64-like blobs; replaced by `"…" + marker` and counted in `clippedValues` |
| Depth stored | 12 | 24 | deeper values replaced by `{…}` marker |
| Pinned data per Step | 1 MB | 4 MB | explicit user data, never auto-truncated; refuse over the cap |
| Pinned data per Flow | 4 MB | 8 MB | sum |

Rules:
1. Always keep the first N items (never random sampling), plus the true `itemCount`.
2. Error items are kept preferentially: up to 20 error items are kept even beyond the item cap and marked.
3. Secret-marked and credential-derived values are redacted **before** writing, using the schema's `Secret` kinds and the redactor in `Values.redact`.
4. Truncated data is always labelled in the UI (3.3); features that need complete data (pin from run) refuse with an explanation.
5. Retention: manual and test Runs keep step data for the last 20 Runs per Flow; production Runs keep step data only for failed Runs by default (setting `Save data: Failed | All | None`, default Failed) and the last 5 successful ones; deleting a Run cascades. Pruning runs in `RunRetention`. A global budget (default 100 MB) evicts oldest first, never evicting pinned data or a Run currently open in the editor.
6. Writes happen asynchronously off the Run's critical path via a bounded channel; if the channel is full the write is dropped and the execution record is flagged `dataDropped` (shown as "data not captured" in the panel). The Run itself is never slowed down or failed by capture.

### 5.4 Store interface

```kotlin
interface StepDataStore {
    suspend fun write(record: StepExecutionRecord, items: StepItems)
    fun observeExecutions(flowId: String, stepId: String, limit: Int = 20): Flow<List<StepExecutionSummary>>
    suspend fun summary(key: StepExecutionKey): StepExecutionSummary?
    suspend fun latestKey(flowId: String, stepId: String): StepExecutionKey?
    suspend fun page(key: StepExecutionKey, direction: Direction, port: Int,
                     offset: Int, limit: Int, filter: ItemFilter? = null): ItemPage
    suspend fun schema(key: StepExecutionKey, direction: Direction, port: Int): SchemaNode
    suspend fun search(key: StepExecutionKey, direction: Direction, port: Int,
                       query: String, limit: Int): SearchResult
    suspend fun exportPort(key: StepExecutionKey, direction: Direction, port: Int, sink: Sink)
    suspend fun deleteRun(runId: String)
}

data class ItemPage(val items: ImmutableList<Item>, val offset: Int, val totalStored: Int,
                    val totalTrue: Int, val truncation: Truncation?)
data class ItemFilter(val query: String?, val onlyErrors: Boolean = false)
data class SearchResult(val matchingIndices: IntArray, val totalMatches: Int)
enum class Direction { In, Out }
```

Pin storage:
```kotlin
interface PinStore {
    fun observe(flowId: String, stepId: String): Flow<PinnedData?>
    suspend fun pinFromExecution(flowId: String, key: StepExecutionKey, port: Int): PinResult
    suspend fun pinFromJson(flowId: String, stepId: String, json: String): PinResult
    suspend fun unpin(flowId: String, stepId: String)
}
data class PinnedData(val ports: ImmutableList<List<Item>>, val bytes: Long, val pinnedAt: Long, val source: PinSource)
sealed interface PinResult { data class Ok(val data: PinnedData) : PinResult
    data class TooLarge(val bytes: Long, val limit: Long) : PinResult
    data class Invalid(val message: String, val line: Int?, val column: Int?) : PinResult
    data object Truncated : PinResult }
```
Pinned data moves out of `StepNode.frozenOutput` into the Flow document as `pins` (so export includes it) with the same size caps; the model keeps `frozenOutput` as a read-only legacy field for migration.

### 5.5 Schema inference

```kotlin
data class SchemaNode(
    val type: SchemaType,                         // Null, Bool, Number, String, Date, Object, Array, Binary, Mixed
    val fields: ImmutableMap<String, SchemaNode> = persistentMapOf(),   // Object
    val element: SchemaNode? = null,                                   // Array
    val seenIn: Int = 0, val of: Int = 0,                              // presence across items
    val sample: String? = null,                                        // truncated rendering
    val mixed: ImmutableSet<SchemaType> = persistentSetOf(),
    val origin: SchemaOrigin = SchemaOrigin.Observed,                  // Observed, Pinned, Declared
)

object SchemaInference {
    fun infer(items: Sequence<Item>, limits: SchemaLimits = SchemaLimits.Default): SchemaNode
    fun merge(a: SchemaNode, b: SchemaNode): SchemaNode
    fun fromDeclared(hint: SchemaHint, resolved: ResolvedParams): SchemaNode
    fun pathsOf(node: SchemaNode, maxDepth: Int = 6): List<SchemaPath>
    fun typeAt(node: SchemaNode, path: SchemaPath): SchemaType?
}
data class SchemaLimits(val maxItems: Int = 200, val maxDepth: Int = 12, val maxFields: Int = 500, val maxSampleChars: Int = 80)
```
Rules: the Date type is inferred from ISO strings only when every seen value in that field parses as an ISO date or the Step's declared hint says so; string-that-looks-like-number stays String; `Mixed` lists the types seen; arrays infer the element from the first 20 elements; binary comes from `Item.binary`, not from JSON.

Declared output shape for the Step before any Run: `NodeSpec.outputs: List<PortSpec(label, schema: SchemaHint?)>` where `SchemaHint` is a static `SchemaNode` or a function `(ResolvedParams) -> SchemaNode` registered by id (not a lambda in data).

### 5.6 Reference builder

```kotlin
object ReferenceBuilder {
    fun build(source: MapSource, target: ReferenceTarget): String
    fun parse(text: String): List<ExprSpan>        // used by chips and by "field missing" checks
}
data class MapSource(val stepId: String, val isImmediateParent: Boolean, val path: SchemaPath,
                     val kind: SourceKind /* Json, Binary, Item, Run */)
```

### 5.7 Expression assist (extends audit 02's `analyze`)

```kotlin
interface ExpressionAssist {
    fun preview(text: String, context: PreviewContext): PreviewResult
    fun complete(text: String, caret: Int, context: AssistContext): List<Completion>
    fun analyze(text: String): ExprAnalysis        // referenced steps, fields, helper calls, spans, errors
    fun diagnostics(text: String, schemas: SchemaScope): List<ExprDiagnostic>   // unknown step, missing field, type hints
}
data class PreviewContext(val flowId: String, val stepId: String, val itemIndex: Int,
                          val inputs: ImmutableList<List<Item>>, val stepOutputs: StepOutputsView,
                          val vars: ImmutableMap<String, Any?>, val budgets: ExprBudgets = ExprBudgets.Preview)
sealed interface PreviewResult { data class Value(val value: Any?, val type: SchemaType, val rendered: String) : PreviewResult
    data class Pending(val reason: PendingReason) : PreviewResult   // NoUpstreamData, NotUpstream
    data class Error(val error: ExprError) : PreviewResult }
data class Completion(val label: String, val insert: String, val kind: CompletionKind, val type: SchemaType?,
                      val sample: String?, val help: String?, val caretOffset: Int)
```
Previews must use the **same evaluator and per-item scope** as the Run (audit 02, Roadmap 2.1 and 1.1). Preview budgets are smaller than Run budgets (Run budgets stay as in audit 02 section 5.2).

### 5.8 Test Step

```kotlin
interface StepTester {
    fun test(flowId: String, stepId: String, options: TestOptions): Flow<TestEvent>
}
data class TestOptions(val runMissingParents: ParentPolicy = ParentPolicy.Ask,  // Ask, RunThem, UsePinnedOrLast
                       val itemLimit: Int = 50, val inputOverride: ImmutableList<List<Item>>? = null)
sealed interface TestEvent { data class NeedsParents(val stepIds: List<String>) : TestEvent
    data class Finished(val key: StepExecutionKey) : TestEvent
    data class Failed(val error: StepErrorInfo, val partial: StepExecutionKey?) : TestEvent }
```
Test Step writes a normal execution record (flagged `manual`) so the panels use one code path. Replaces the in-memory `lastOutputs` map.

### 5.9 Lineage-aware item pairing for the panels
Selecting output item N highlights the input item(s) in `sources` of that item (and the reverse); a tap on the badge `from item 3` scrolls the Input panel to it. API: `StepDataStore.page` returns `Item.sources`; `ItemLinks.inputsOf(outputItem)` and `outputsOf(inputItem)` are pure functions on the pages already loaded plus a store query for off-page matches.

### 5.10 Engine changes required (summary)
1. Output and input snapshots keyed by Step id and port (Roadmap 2.3), with `toPort` honoured in `collectInput`.
2. `sources` populated by the engine, not by executors (Roadmap 2.2).
3. Per-item param resolution (Roadmap 1.1) and `ParamResolver` using `ParamSchema` defaults.
4. A `StepExecutionRecorder` called from `FlowRunner` after each Step, asynchronous and bounded (5.3 rule 6).
5. `runs.runJson` stops containing `output` lists; `StepRun` keeps counts and status only. A migration drops the old arrays and converts existing runs to execution records with `dataDropped = true`.
6. `RunEvent.StepFinished` gains `runIndex` and the execution key so the inspector can refresh the open Step when its data changes.

---

## 6. Task breakdown

Ordered; each task is independently testable and merges only when its acceptance criteria pass. JVM means a pure-Kotlin unit test with no Android; UI tasks add Compose UI tests or screenshot tests where listed, and the device checklist at the end of the phase gate covers "verify on device" items. Dependencies are shown after the title.

**T1. Item model and lineage (needs Roadmap 1.1).**
Add `Item`, `ItemRef`, `ItemError`; engine fills `sources` for one-to-one, one-to-many, many-to-one and sourceless Steps; `toPort` honoured.
Acceptance: JVM tests for each of the four cases; Merge keeps binary and sources; multi-input Step receives separate `inputs[toPort]` lists; no existing node test regresses.

**T2. SchemaNode and inference.**
Implement `SchemaInference` (5.5) with limits.
Acceptance: JVM tests: nested object, array of objects, optional fields (`seenIn` 7 of 12), mixed types, ISO date detection, binary from `Item.binary`, depth and field caps, merge is associative; inference of 200 items with 50 fields under 30 ms on JVM.

**T3. ParamSchema model and ParamValue serialization.**
Types from 4.1 in `core/model`; JSON codec for `ParamValue`; legacy reader; `ParamSpec.toDef()` compat.
Acceptance: round-trip tests for every kind; legacy strings with `{{` become `Expr`; plain strings stay `Fixed`; a literal object with key `kx` inside a Json param is not mistaken for an expression (documented and tested); every bundled template loads and re-saves byte-stable.

**T4. ShowWhen evaluator and ParamValidator.**
Evaluate show rules with path resolution; one validator walking the schema; issue paths and codes.
Acceptance: JVM tests: sibling, parent and root paths; hidden required params produce no issue; expression-controlled show rule renders visible with `dependsOnExpr`; every rule type fails and passes; Select membership and Number type checks (fixing audit 02 section 2.3 gaps); validator runs on the full catalog without exceptions.

**T5. ParamResolver and typed ResolvedParams.**
Applies defaults for visible params, omits hidden, resolves expressions per item, never expands `ExprMode.Never`.
Acceptance: JVM tests: per-item resolution differs per item; Secret and Code never expanded; defaults applied once in one place; typed accessors coerce with explicit errors; legacy `ctx.params()` shim marked deprecated and covered.

**T6. Migrate the node catalog to ParamSchema (incremental, in batches by node family).**
Convert `core/nodes` specs batch by batch behind the compat function; add real kinds where audit 03 calls for them (Conditions for If/Switch/Filter, Assignments for Edit Fields, Repeater for key-values).
Acceptance: each batch ends with a registry-wide test (all ids unique, defaults valid, every `show` path resolves, every Select default is an option); old Flows still open and run (golden Flow fixtures); `ParamSpec` deleted at the last batch.

**T7. Step execution storage (Room).**
Entities, DAO, migration with exported schema, `StepDataStore` implementation with compressed lines and offsets, redaction before write.
Acceptance: instrumented-free JVM Room tests (Robolectric or in-memory SQLite) for write, page, schema, search, cascade delete; migration test from the current version; paging 25 from a 1000-item port reads at most 2 blocks (asserted via a counter); secret values never present in the stored bytes (test greps the blob).

**T8. Truncation, retention and capture channel.**
Implement 5.3 rules including error-item priority, clipping and global budget eviction; `StepExecutionRecorder` with bounded channel.
Acceptance: JVM tests for each limit and each `TruncationReason`; pinned data never evicted; 10,000-item output stores 200 items and reports `itemCount = 10000`; a slow store does not delay a Run beyond a set tolerance (test with a fake blocking store); `dataDropped` flagged when the channel overflows.

**T9. Engine wiring and run events.**
`FlowRunner` records executions per Step and port; `RunEvent.StepFinished` carries the key; `runs.runJson` slimmed with migration; `RunRetention` extended.
Acceptance: end-to-end JVM test: a three-Step Flow stores input and output for each Step; killing the process mid-Run (simulated) still leaves finished Steps' data; old runs convert to `dataDropped`; run list screen still renders.

**T10. PinStore and pinned replay.**
Pin from execution, from JSON, unpin; caps; export inclusion; engine replays pins for manual Runs and Test Step.
Acceptance: JVM tests for size caps, invalid JSON error with line and column, truncated source refusal, export and import round-trip, pinned Step skipped in manual Run but executed in a production Run unless a Run option says otherwise (decision confirmed in the checklist).

**T11. ExpressionAssist (preview, diagnostics, complete) (needs Roadmap 2.1).**
Implement on top of the engine's parser/evaluator and `SchemaScope`.
Acceptance: JVM tests: preview equals Run result for a corpus of 100 expressions; unknown field gives diagnostic with did-you-mean; reference to a non-upstream Step flagged; completion after `$json.`, `$step("`, `.` on string, array and number receivers; completion ranking; preview cancel does not leak; preview budget stops an infinite-loop expression within 50 ms.

**T12. ReferenceBuilder and expression span parser.**
Build and parse references; chips model.
Acceptance: JVM tests for identifiers, keys with spaces and dots, array indices, nested paths, binary refs, immediate-parent vs other Step form; parse then build is identity on generated paths; unterminated `{{` handled without crash.

**T13. InspectorState, reducer and holder.**
Pure reducer for every event in 4.2; holder wiring flows with `flatMapLatest`.
Acceptance: JVM tests with a fake store: selecting a parent reloads input; changing run resets paging; port switch keeps mode; search debounces and resets page; test event sequence updates state through Running to Finished; open on replay makes everything read-only; 95% line coverage of the reducer.

**T14. Design components for data and params.**
Add to `core/design`: `TypeIcon`, `TypeBadge`, `ValuePill`, `PearlToggle` (fixed/fx), `DropGlow` modifier, `SectionCard`, `SearchField`, `PagerBar`, `EmptyState`, `ErrorCard`, `SkeletonRows`, `AccessoryBar`.
Acceptance: screenshot tests (light none; Obsidian only) for each state; contrast test over tokens; touch target test (all clickable nodes at least 48dp); font scale 200% screenshot has no clipping.

**T15. DataPanel: Schema and Table views.**
Lazy schema tree, table with sticky header and index, nested summaries, type icons, search highlight, pagination, copy actions.
Acceptance: Compose tests: 1000-item page scroll stays under frame budget on a mid device profile (macrobenchmark or a lighter proxy test); sticky header remains visible; nested cell expands; search reduces rows and shows `x of y`; truncated banner; empty, loading, error states render; TalkBack semantics test for row name, type, sample.

**T16. DataPanel: JSON tree, Binary view, pinned strip, run/port/parent selectors.**
Acceptance: Compose tests: expand/collapse all; copy node yields valid JSON; binary card shows mime and size; port tabs show counts including zero; run stepper hidden with a single Run; parent selector lists all parents and persists choice; pinned strip shows size and age; editing pin validates and enforces cap.

**T17. Parameter widgets, batch 1: Text, Number, Boolean, Choice, MultiChoice, DateTime, Color, Notice, Secret.**
With fixed/expression pearl and preview line (no chips yet).
Acceptance: Compose tests per widget for value change, validation message, toggle round trip (fixed to expression and back with undo), Choice widget switches between segmented, list and searchable sheet at 4, 6 and 20 options, Secret hides on background and never appears in semantics or preview; Never fields show no pearl.

**T18. Expression field: chips, accessory bar, autocomplete strip, full-screen editor.**
Acceptance: Compose tests: typing `{{` shows suggestions; tap inserts at caret; backspace removes chip as one undo step; paste of text with `{{ }}` renders chips; IME composition not broken (device checklist item R1); error span highlighted; preview pill states (value, pending, error); full-screen editor shows result and tray.

**T19. Parameter widgets, batch 2: Json, Code, Collection, Repeater and key-value, Assignments, Conditions, FileRef, ResourcePick, CredentialPick.**
Acceptance: Compose tests: each shape written correctly to `ParamValue`; Repeater swipe delete with undo, reorder; Assignments creates a row from a dropped field with inferred type; Conditions changes operator list when left type changes and clears incompatible right value with undo; credential field never lists secrets and handles deleted credential; resource list shows loading, empty, failed states; `ParamSpec` compat function removed.

**T20. Drag to map and Mapper tray.**
Compose drag-and-drop sources on all data rows; targets on every expression-capable field; edge auto-scroll; page switch by edge dwell; tap-to-insert tray; TalkBack custom actions.
Acceptance: Compose UI tests: drag a schema row onto a text field sets expression mode with the right reference; onto a Never field rejected with snackbar; onto Assignments footer creates a row; onto filter left value fills it; tap-to-insert produces the same text as drag; auto-scroll reaches a target 3 screens down in test; every drop has an undo; accessibility action list contains `Insert into field`; device checklist: long-press vs scroll conflict (R2).

**T21. Layouts: phone pager, landscape side sheet, three-pane.**
`InspectorHost` chooses layout from window size class; panes shared composables.
Acceptance: screenshot tests at 360x800, 800x360, 840x1280 and 1280x800 dp; state survives rotation and process death (saved state for page, mode, run selection); three-pane dividers honour min widths; footer button stays above IME.

**T22. Test Step and error surfaces.**
`StepTester`, parent policy sheet, error card with copy details, pin from run, `Run to here`.
Acceptance: JVM tests for parent policy flows; UI test: Test Step on a Step with no upstream data asks to run parents; failure shows message, item index and partial data; success updates Output without reopening the sheet.

**T23. Behaviour and About tabs.**
On-error, retry, timeout, always output, run once, notes (with show-on-canvas), generated field docs.
Acceptance: values persisted on the Step; engine honours each (reuse audit 01 tasks); About text is generated from the schema (JVM test compares output of the docs generator for 3 Steps against golden files).

**T24. Executions sheet adopts the shared DataPanel.**
Replace the `Map.toString` snapshots with the shared panel in read-only mode; tapping a Step in a Run opens the inspector in replay.
Acceptance: opening a failed Run shows input and output with tables and JSON, redacted; no use of `Map.toString` left; read-only mode disables every editing control (UI test).

**T25. Remove the old inspector and finish.**
Delete replaced code; update docs and checklist; accessibility sweep.
Acceptance: `StepInspectorSheet.kt` is under 300 lines (host and wiring only); lint passes with no accessibility warnings from the Compose checks; the Phase 2 device checklist (below) signed off by the owner.

**Phase device checklist (owner, on a real phone and a tablet).**
1. Drag a field from Input into a text param, a number param, an Assignments list, a filter condition, in portrait and landscape.
2. Do the same by tap-to-insert and with TalkBack on.
3. Run a Flow with 1,000 items; Output shows Table, JSON, Schema, search, pages, truncation banner.
4. Pin, restart the app, Run again, pinned data replays.
5. Switch Run, port and parent selectors; replay a failed Run from the executions sheet.
6. IME checks: Gboard swipe typing and autocorrect inside an expression field, delete across chips.
7. Rotate with the inspector open; state survives.

**Risks.**
- R1: chip rendering inside an editable field is the single riskiest piece in Compose (caret and IME). Fallback: render chips as a read-only decorated overlay and keep the editable text styled with colour spans only; chips become editable in the full-screen editor.
- R2: long-press-drag inside a scrolling list; the fallback is a visible drag handle on each row (always shown on the Mapper tray).
- R3: storage growth; mitigated by caps, redaction, retention budget (5.3).
- R4: scope; T6 is large. Ship T1 to T5 and T7 to T9 first so the data panels work on the old parameter UI, then the widgets.

---

## 7. Reuse and rewrite from the current inspector

Current state (`feature/canvas/.../sheets/`): `StepInspectorSheet.kt` 1424 lines mixes sheet chrome, state, field widgets, credentials, expression dialog and data viewers; `WorkflowExecutionsSheet.kt` 461 lines; `CanvasViewModel.kt` 972 lines owns inspector state and test/freeze.

| Piece | Verdict | Notes |
|---|---|---|
| Sheet container with segmented Input / Configure / Output | Rewrite | Replace with pager plus detents and the three-pane host (T21); keep the idea and the `SegmentedPill` from `core/design`. |
| Schema-driven form idea (`ConfigureTab` over `NodeSpec.params`) | Keep the idea, rewrite the code | Generated form is right; it now renders `ParamSchema` with recursive kinds. |
| `isVisible` / `showWhen` | Replace | Equality-in-list only; replaced by `ShowWhen` tree and evaluator (T4). |
| `ParamField` and `ParamFieldBody` (the two near-copy text branches) | Rewrite | One `ParamTextField` for text kinds with chips; separate widgets per kind. |
| `CredentialPickerField` and `CredentialChoiceRow` | Reuse, adapt | Keep the visuals and behaviour; move to `CredentialPick` widget and wire to slots and statuses; remove substring detection by key name. |
| `ExpressionChip` (`core/design`) | Reuse, extend | Becomes the base of chip rendering and the fx pearl. |
| `ExpressionPickerDialog` and `ExpressionItemRow` | Replace | Replaced by the Mapper tray and autocomplete strip; its four tabs (Input, Variables, Environment, Time) map to tray sections; Environment is removed (Roadmap 1.3). |
| `evaluatePreview` and `ExpressionPreviewBadge` | Rewrite | Must use the engine evaluator and the per-item scope; badge style is reused. |
| `NodeDataViewer`, `TableView`, `JsonView`, `SchemaView` | Rewrite on shared components | Keep column/row look, type badges and amethyst colours; add sticky header, lazy paging, nesting, search, drag; move to a shared `DataPanel` in a new `feature/datapanel` module (or `core/design` for the pure widgets) used by the inspector and the executions sheet. |
| `OutputTab` | Rewrite | Replaced by `OutputPanel` with pin strip, branch tabs, run selector. |
| Footer (Freeze, Delete, Run) | Rewrite | One gold `Test Step` plus overflow (see audit 08 section 5.2). |
| `CanvasViewModel` inspector parts (`inspector` state, `lastOutputs`, `testStep`, freeze) | Move out | To `InspectorStateHolder`, `StepTester`, `PinStore`; the view model keeps selection only. |
| `WorkflowExecutionsSheet` list, status filters, expansion | Reuse the list and filters; rewrite the snapshot body | Body becomes `DataPanel` in read-only mode (T24); also add redaction. |
| `NodeSpec` and `ParamSpec` | Replace | By `ParamSchema` (T3, T6); compat only until T19. |
| `Values.redact` | Reuse | Becomes the redactor used at storage write time and in the panels. |
| `FallbackExpressionEvaluator` | Do not extend | Replaced by Roadmap 2.1 engine; `ExpressionAssist` depends on it. |
| Tokens (`KaivoTokens`, `KaivoColors`) | Reuse | Add roles for type badges, drop glow and pinned hairline only as tokens. |
| `StepMedallion`, `StatusBadge`, `SheetDragHandle`, `GoldButton`, `GhostButton`, `KaivoSurfaceCard`, `GlassPill`, `SectionHeader`, `SegmentedPill` | Reuse | As they are; new widgets are added beside them (T14). |

Net: roughly 15% of the existing inspector code survives (credential picker, chips, badge styling, tabs idea); the architecture it embodies (declarative schema driving the form) survives completely.
