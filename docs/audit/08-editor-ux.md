# Audit 08: Editor UX and feature parity (touch-first, identity preserved)

Scope: the UI layer. `feature/canvas` (CanvasScreen, CanvasViewModel, StepInspectorSheet, StepPickerSheet, WorkflowExecutionsSheet, overlays, render/), `feature/flows` (FlowListScreen, OverviewTabs, RunHistoryScreen, FlowTransfer), `feature/settings`, `core/design`, `app` (navigation, manifest).
Benchmark: the n8n editor, described only as behavioural requirements in our own words. No n8n code, identifiers, text or assets are reproduced, and every design below is KaivoFlow's own.
Constraint from the owner: keep the Velvet Obsidian and Champagne Gold identity (Jewel Cards, pearls, Golden Current, gold hairlines, glass docks). Nothing here asks for a visual rebrand.
Paths are relative to `/home/claude/kaivoflow`. The project has no Gradle build verification (see README), so every runtime claim comes from reading code. Claims that depend on Compose gesture or focus behaviour are marked "verify on device".

## 1. Summary

The visual layer is the strongest part of the app. The Jewel Card, the single-Canvas Stream layer, one `graphicsLayer` for pan and zoom, immutable UI models and a real token system are the right foundations. The editor is still a viewer plus a form, not yet an editor you can build and debug in. Five reasons, in order of damage:

1. **Credentials cannot be picked.** Every Step that needs a credential renders a masked free-text box, but the engine resolves the value as a database id (`StepExecutor.kt:63-67`, `Repositories.kt:213-215`). Nothing in the UI shows or inserts that id. Telegram, Google Sheets, AI and HTTP-with-credential are effectively unusable from the editor.
2. **Data mapping from earlier Steps does not exist.** The inspector receives `previousStepNames` and never uses it (`StepInspectorSheet.kt:94`). The expression picker offers only top-level keys of the first merged input item, plus vars, env and time. Tapping a key in the Input tab can overwrite a whole field (`:181`, `:206`). The runtime supports `$step("Name")` (`core/engine/.../Expression.kt:98,122`) but no UI reaches it.
3. **The test loop has no memory.** Step outputs live in a map keyed by Step name inside the ViewModel (`CanvasViewModel.kt:433`). After a process restart, or a rename, Input and Output are empty even though Room holds the last RunRecord. Test Step then runs on a blank item (`FlowRunner.kt:90`) and returns a plausible but wrong result, with no prompt to run upstream Steps first.
4. **Per-Step settings that the model already has are invisible.** `StepNode.onError`, `retry`, `timeoutMs` and `notes` (`Flow.kt:146-149`) have zero UI references. Retry and continue-on-fail are table stakes for unattended automation.
5. **Many editor features are built but unreachable.** The Minimap, duplicate Step, activate toggle in the canvas, layout toggle, group creation, canvas variables and flow-list group filtering are implemented in a ViewModel or composable that nothing calls (section 4, F8 and F15). The "Golden Current" run animation, the brand signature, is never lit because the Stream models are never marked active (F12).

Counts: 4 P0, 15 P1, 9 P2.

## 2. How it works today

### 2.1 Step Inspector (`feature/canvas/.../sheets/StepInspectorSheet.kt`, 1239 lines)

**Container.** A full-height `ModalBottomSheet` with a three-segment pill: Input, Configure, Output (`:134-172`). Content sits in a `Box` with `heightIn(240..460.dp)` and its own `verticalScroll` (`:175`). A fixed footer holds Freeze, Delete and Run (`:213-242`).

**Schema driven.** The Configure tab is generated from `NodeSpec.params` (`NodeSpec.kt:50-82`). A Step gets a form with no UI code. That is the right architecture and should be kept. Roughly 270 `ParamSpec`s exist across the node catalog. Conditional fields work through `showWhen` (`:350`, `:372-378`, "any key matches any allowed value").

**Field renderers** (`ParamField`, `:441-655`):

| ParamType | What the user gets |
|---|---|
| Boolean | Row plus Switch (`:451`) |
| Select | Horizontal chip row in a `LazyRow` (`:464`), no search, no dropdown |
| Secret | Masked single-line field with a show toggle (`:488`); includes the credential picker "fields" (see F1) |
| Multiline, Json, Code, Expression, KeyValue | One identical 3 to 8 line monospaced `OutlinedTextField` (`:517-584`) |
| Text, Number, Url | Single-line field (`:586-653`); Number stores the typed string |

The two text branches are near-copies (`:517-584` vs `:586-653`). `ParamSpec.advanced` exists and is set on many params but is never read by the UI. `required` is only surfaced through the canvas validation banner.

**Expressions.** A gold `fx` chip opens a modal dialog (`:658-786`) with four tabs: Input ($json), Variables, Environment, Time. It lists only top-level keys of the first input item (`:732`) and appends the snippet to the end of the field with a space (`:576-579`, `:645-648`), not at the cursor. A live "Result" badge evaluates the field with `FallbackExpressionEvaluator` (`:386-407`) against a scope with no `step`, `index` or `run` (`:113-132`), while the runtime prefers QuickJS (`FlowEngine.kt:60-64`). `{{`-detection is string-based; there is no highlighting, no autocomplete, no inline error position. The class comment promises an "Expression Accessory Bar" above the keyboard (`:80-86`). It does not exist.

**Data views** (`NodeDataViewer`, `:825-946`): Table, JSON, Schema for Input and Output. Nested values show as `{…} (n)` or `[…] (n)` (`:1040-1041`) with no drill-down. Tapping a column or schema row copies `{{ $json.key }}` to the clipboard and also calls `onParamChange(focusedField, expr)` (`:181`, `:206`, `:921-927`).

**Test and pin.** `testStep()` (`CanvasViewModel.kt:527-568`) runs the Step with `inspector.inputItems`, a snapshot taken when the sheet opened from `lastOutputs[upstream.name]` (`:198-214`). "Freeze" stores the last in-memory output on the Step (`:371-381`). There is no viewer or editor for frozen data, no "pin from execution", and no marker in the Output tab that data is pinned.

**Credentials.** Param key `credential` is a `ParamType.Secret`. Detection of "secret-like" fields is by substring on the key (`:589-591`). The Credentials screen (`PrivacyAndCredentials.kt:201-`) and the Overview Credentials tab (`FlowListScreen.kt:634-643`) manage the vault, but no code connects a vault entry to a Step param.

### 2.2 Data visibility and history

- After a manual Run, `handleRunEvent` (`CanvasViewModel.kt:435-524`) updates card badges (item count, latency) and, if the inspector is open on that Step, its output. Per-Step output is cached in `lastOutputs`. Step failures update the card but not the open inspector's error (`:489-498`).
- `WorkflowExecutionsSheet.kt` (461 lines): status filter chips, a list of runs for this flow, expandable "Step Execution Snapshots". A snapshot shows status, duration, items in/out count and, on tap, up to 800 characters of `Map.toString()` per Step (`:~440-458`). It is not JSON, not redacted (the inspector JSON view calls `Values.redact`, `StepInspectorSheet.kt:1075`; the snapshot does not), and has no Table or Schema view.
- `RunHistoryScreen.kt` (global): the last 50 runs (`FlowListScreen.kt:191`), rows not tappable, no filter, no way to open the flow at that run.
- Stored runs hold only outputs, not inputs, and outputs are truncated (see audit 01, section 1).

### 2.3 Canvas (`CanvasScreen.kt` 950 lines, `CanvasViewModel.kt` 965, `layout/CanvasGeometry.kt`, `render/`)

- **Rendering.** One `graphicsLayer` for pan and zoom (`:367-374`). All Streams plus the dot grid draw in a single `Canvas` (`StreamCanvasLayer.kt`). Step cards are composed only inside the viewport (`:416-422`). Cards report their height back for layout (`:447-453`). This is the right design for 1000 Steps.
- **Gestures.** One `awaitEachGesture` classifies the touch: output port (drag to wire), card (tap opens inspector, drag moves), sticky note, else pan and pinch (`:200-361`). Dropping a wire on empty canvas opens the Step Picker chained from that port (`:239-241`). Dragging a Step from the picker onto the canvas is supported (`StepPickerSheet.kt`).
- **Layouts.** GuidedPath (phone default, waterfall) and Freeform (grid snapped at layout time, `CanvasGeometry.kt:113`). Any card drag converts the whole flow to Freeform permanently (`CanvasViewModel.kt:320-336`). `toggleLayoutMode()` exists but has no UI.
- **Editing.** Add Step (picker), delete (inspector only), rename (pencil on the card or the inspector name field), disable (inspector switch), connect (drag), disconnect (inspector's Connected Streams list only), undo and redo (30-deep snapshot stack of steps, streams, notes, groups; `:572-620`), freeze.
- **Annotation.** Sticky notes (add from the top bar, 240x140 fixed, plain text, 6 lines shown; `CanvasStickyNote.kt`), visual Step Groups that wrap member cards (`CanvasGroupFrame.kt`), but see F8: groups cannot be created from the UI.
- **Navigation aids.** Zoom in, zoom out, fit-to-screen dock, undo and redo dock, run HUD. The Minimap composable (`CanvasOverlays.kt:154`) is never instantiated.
- **Run feedback.** Cards show Running, item count and latency, Failed, Skipped, Frozen. Validation shows as a bottom banner with a Fix button that opens the inspector.

### 2.4 Flow list and navigation

`FlowListScreen.kt` (1889 lines) holds the ViewModel (about 20 StateFlows), the screen, eight dialogs and four dashboard tabs: Workflows, Credentials, Variables, Data tables. Metrics header (executions, failed, failure rate, average run time) is computed from the last 50 runs (`:189-198`, `:432-440`). Per-flow card: Pin, Activate/Deactivate, Export, Delete (with confirm), last-run badge. Import is a single JSON file picker (`FlowTransfer.kt:190-193`). Six built-in templates (`FlowTemplates.kt`). Navigation is a flat `NavHost`: Flows, History, Canvas, Settings, Privacy, Permissions, Credentials (`MainActivity.kt`).

### 2.5 Design system (`core/design`)

Tokens for color (dark Royal Velvet plus an Ivory Silk light scheme), radii, spacing, motion (with a reduce-motion path), a declared 48dp `touchTarget` ("non-negotiable", `KaivoTokens.kt:52`), shared components (GoldButton, GhostButton, SegmentedPill with `Role.Tab`, StatusBadge, StepMedallion). Reduce-motion is honoured in the canvas.

## 3. Gaps vs the n8n editor

Legend: Have / Partial / Missing. "Touch" is the KaivoFlow-native shape of the answer, detailed in section 5.

| Area | n8n editor behaviour (own words) | KaivoFlow | Gap |
|---|---|---|---|
| Node details | Input, parameters and output visible together; run-data selector when a node ran several times | Three tabs, one at a time | Partial; no side-by-side on large screens, no multi-run selector |
| Parameter types | Collections, fixed collections, filter/condition builder, resource pickers, resource mapper, code editor, curl import | Text box for everything complex; Select as chip row | Missing (F13) |
| Expression mode | Per-field fixed/expression toggle, inline editor with autocomplete and live result, expanded modal with variable browser | `fx` chip appends text; preview badge; no autocomplete | Partial (F2) |
| Data mapping | Drag a field from the input panel into a parameter; mapping pills; paths into nested objects and arrays | Tap copies a top-level key | Missing (F2, F6) |
| Data views | Table, JSON, Schema, binary preview; search; pagination; item selection | Table, JSON, Schema; no search, pagination, binary, nesting | Partial (F6) |
| Pinning | Pin from output, edit pinned JSON, pin from execution, pinned marker, size guard | Freeze last in-memory output; no viewer/editor | Partial (F7) |
| Test a node | Executes upstream if no data, shows output and error detail | Runs on cached or blank input | Partial (F3) |
| Run from here / to here | Execute up to a selected node | Whole-flow Run only | Missing |
| Executions | Per-workflow list, filters, open a past run on the canvas with data, debug a failed run in the editor, retry, delete | Sheet with snapshots as text | Partial (F9) |
| Per-node settings | Retry, continue on fail, always output data, execute once, notes with "show on canvas" | None in UI; model has onError, retry, timeout, notes | Missing (F4) |
| Credentials | Selector in the node, create in place, test connection, usage list | None in node; vault list only; usage count hardcoded 0 | Missing (F1) |
| Node creation | Searchable panel, categories, actions, recents, "+" on every output | Searchable sheet with category chips, drag-to-place, drop-wire-to-add | Partial |
| Connections | Drag from output, drop on input, tap an edge for insert or delete, reconnect | Drag from output only; delete only via inspector | Partial |
| Selection | Rectangle select, shift-select, select all | None | Missing |
| Copy, paste, duplicate | Clipboard of nodes with connections, cross-workflow paste | VM `duplicateStep` with no UI and no connections | Missing |
| Undo and redo | Deep, includes parameter edits sensibly | 30 snapshots, flooded by per-keystroke edits | Partial (F5) |
| Auto layout | Tidy up | `toggleLayoutMode` has no UI; no tidy | Missing |
| Sticky notes | Resizable, markdown, colors | Fixed size, plain text | Partial |
| Zoom, minimap | Zoom controls, fit, minimap | Zoom and fit; minimap dead code | Partial |
| Sub-workflow navigation | Open sub-workflow from the node | `flowId` is free text; no open action | Missing |
| Error and disabled state | Red node on error, error tab, disabled node faded | Card badges; no error detail beyond text | Partial |
| Workflow organisation | Folders, tags, search, sort, archive, share | Group data model exists; filter, move and create dialogs unreachable; no search | Missing (F15) |
| Templates and import | Template library, import from file, URL, clipboard | 6 templates, file import only | Partial |
| Activate | Toggle in editor header | Only on the list screen | Partial |

## 4. Findings

Evidence is `file:line` as read. "Verify" means a Compose runtime behaviour that should be confirmed on a device or emulator.

### P0

**F1. Credentials cannot be selected in a Step.**
Evidence: credential params are `ParamType.Secret` (`SaasNodes.kt:31,187`, `AiNodes.kt:217`, `HttpNode.kt:60`). Inspector renders Secret as a masked text field (`StepInspectorSheet.kt:488-515`). Engine does `credentials.resolve(Values.asString(params["credential"]))` (`StepExecutor.kt:63-67`), which does `credentialDao.byId(ref)` (`Repositories.kt:214`). `CredentialRef.id` is never displayed. No code in `feature/canvas` mentions a vault entry.
Impact: the user must paste an opaque id they cannot see. Pasting the API key instead yields "That credential was deleted" (`Repositories.kt:214`). Related: HTTP `authToken` and `authPassword` are Secret params stored as plain Flow params (`HttpNode.kt:57-59`), so they end up in Room and in exported bundles; see the security audit.
Fix: add `ParamType.Credential(typeFilter)`; render a selector sheet listing vault entries of the matching type with "Add new" inline (returns to the field) and "Test connection"; store the id, show the label. Migrate `credential` params.

**F2. No way to map data from earlier Steps.**
Evidence: `previousStepNames` is declared (`StepInspectorSheet.kt:94`) and never read. `liveScope` has `json` of the first item of all merged upstream outputs only (`:113-132`, `CanvasViewModel.kt:198-209`). Picker lists top-level keys (`:732`). Input-tab taps call `onParamChange(focusedField, expr)` (`:181,206`), which replaces the entire field value; `focusedField` is set on focus (`:197`) but not cleared when the Configure tab leaves composition, so it can point at a field that is no longer on screen (verify). The runtime offers `$step("Name")` (`Expression.kt:98,122`; `FallbackExpressionEvaluator.kt:61,83`) and nothing in the UI inserts it.
Impact: the central n8n workflow, "pick a value from an earlier node and use it here", is not possible without typing syntax from memory.
Fix: "Mapper" described in section 5.1: upstream tree by Step, nested path insertion at the cursor, drag or tap-to-insert, insertion never replaces.

**F3. The test loop has no memory and silently tests on blank input.**
Evidence: `lastOutputs` is an in-memory `mutableMapOf<String, List<FlowData>>` keyed by Step name (`CanvasViewModel.kt:433`). `openInspector` reads it by upstream name (`:198-202`), `testStep` writes it (`:536`), `StepFinished` writes it (`:454`). `flowRuns` (including per-Step outputs) is already observed from Room (`:105-109`) but never used to hydrate. `FlowRunner.testStep` substitutes one empty item when input is empty (`FlowRunner.kt:90`). `InspectorUiState.inputItems` is a snapshot taken at open and is not refreshed after an upstream test (`:206-211`). A `$step()` lookup during a test has no upstream outputs to read (verify).
Impact: after a restart, rename or process death, Input and Output tabs are empty although data exists. Test Step on a Step with no cached upstream data runs on `{}`, so most Steps return nothing or an error, which reads as "my config is wrong".
Fix: hydrate inputs and outputs from the latest `RunRecord` (key by `stepId`), refresh inputs on tab switch, and when input is missing offer "Run upstream Steps first" (run to here). Show "Showing data from <time>, <manual run or test>" on both panels so data age is never ambiguous.

**F4. Retry, continue-on-fail, timeout and notes have no UI.**
Evidence: `StepNode.onError`, `retry`, `timeoutMs`, `notes` (`Flow.kt:146-149`), `OnErrorPolicy.Stop/Continue/Retry/ErrorFlow` (`Flow.kt:288-300`), `FlowSettings.errorStepId` (`Flow.kt:281`) have no references in `feature/*` (grep). The card has an "N tries" badge (`JewelStepCard.kt`) that is never fed (`attempts` is not set by `handleRunEvent`).
Impact: users cannot make an unattended flow resilient, which is the purpose of an on-device automation tool. Flow-level settings (timezone, timeout, error Step, which runs to save) are also unreachable.
Fix: a "Step settings" section in the inspector and a Flow settings sheet (section 5.6).

### P1

**F5. Undo is poisoned by typing.** `updateParam` calls `commit()` on every keystroke (`CanvasViewModel.kt:224-228`), `commit` calls `pushSnapshot` (`:579-586`, `:572-577`), the stack is capped at 30 (`:573`). Typing 31 characters erases all earlier undo history. Step rename in the inspector does the same (`StepInspectorSheet.kt:192-195` to `renameStep`). Also each keystroke runs full validation and `syncState()`, which rebuilds every `StepNodeUiModel` and the `paramCache` (`:781-841`). Fix: coalesce param edits (one snapshot per field focus session or per 800 ms idle), apply validation and UI-model sync only for the edited Step.

**F6. Data viewer does not scale and cannot drill down.** Table rows are a plain `Column` over all items (`StepInspectorSheet.kt:1022`) inside nested scroll containers (`:175`); JSON view builds one pretty string for the entire payload and renders it as one `Text` (`:1073-1111`); `FlowSettings.maxItemsPerStep` is 5000 (`Flow.kt:285`). Schema and Table expose only top-level keys (`:1130-1146`, `:1038-1044`). No search, no pagination, no item selection, no binary preview (`FlowData.binary` exists). Execution snapshots print `Map.toString()` and are not redacted (`WorkflowExecutionsSheet.kt`, snapshot body) while the inspector JSON view is. Fix: one shared lazy `DataViewer` composable used by inspector and executions (section 5.2).

**F7. Pinning is a stub.** `freezeOutput` stores whatever is in `lastOutputs` (`CanvasViewModel.kt:371-381`); unavailable after restart (F3). No indicator in the Output tab, no way to view or edit pinned data, no "pin from execution", no size warning, and the manual Run has no "ignore pinned data" switch (the engine has `RunTrigger.ignoreFrozen`, `Run.kt`). Freeze and Delete sit side by side in the footer and Delete has no confirmation (`StepInspectorSheet.kt:224-234`). Fix: pin editor sheet plus a "Pinned" pearl and clear unpin affordance.

**F8. Large parts of the editor are implemented but unreachable.** Grep over `feature/` and `app/` finds no caller for: `CanvasViewModel.duplicateStep` (`:242`), `toggleFlowActive` (`:649`; the header only shows a status line, `CanvasScreen.kt:541-544`), `toggleLayoutMode` (`:343`), `setVar` (`:365`), `openCreateGroupDialog` (`:672`) and the `CreateGroupDialog` composable (`CanvasOverlays.kt:719`; `showCreateGroupDialog` is never rendered), `Minimap` (`CanvasOverlays.kt:154`), `JewelStepCard.onTap/onLongPress` parameters (`:78-79`), `StepCardSkeleton`, `EmptyCanvasHint` (`:494`), `requestDisconnectStream` outside the inspector. The top bar offers only "add note" and "executions" (`CanvasScreen.kt:566-610`). Impact: duplicate, activate and layout tools exist but cannot be used; groups can only come from an imported file.

**F9. Execution history is shallow.** Snapshots are text (F6), have no input data, cannot be opened on the canvas, cannot be re-run, retried or deleted, and the only filters are status chips (`WorkflowExecutionsSheet.kt:62-87`). Global history rows are not tappable and are capped at 50 (`RunHistoryScreen.kt`, `FlowListScreen.kt:191`); the dashboard metrics are labelled as executions but computed from those 50 (`:432-440`). Depends on audit 01 (persist inputs, raise output cap).

**F10. Gesture hazards on the canvas (verify on device).**
(a) The pointer handler is keyed on `layouts, state.notes, state.steps, state.positions` (`CanvasScreen.kt:200`). Run events replace `state.steps` repeatedly (`CanvasViewModel.kt:444-473`), which restarts the handler and cancels a gesture in progress. Cleanup (`pressedStepId = null`, `liveCardDrag = null`, `dragStart/dragEnd = null`) runs after the loops (`:290-292`, `:242-246`) with no `try/finally`, so a cancelled gesture can leave the card in a stuck "pressed" or "dragging" state.
(b) Port hit radius is a constant 28 world-dp (`:907`) so on screen it is 28 x zoom dp, about 10dp at the minimum zoom of 0.35 (`CanvasGeometry.kt:60`). With 5 Switch outputs at 220dp, ports are about 37dp apart, so hit zones overlap and the first Step in list order wins. The hit zone also eats the bottom 28dp of the card, so grabbing the lower middle of a card starts a wire, not a drag.
(c) Small controls sit inside the gesture surface: the card rename pencil (12dp icon, `JewelStepCard.kt:188-195`), note edit and delete (14dp, `CanvasStickyNote.kt:110-129`). The parent handler also reacts to a tap on these areas (`CanvasScreen.kt:250-293`, `:296-338`), so rename may also open the inspector and note delete may also open the note editor.
(d) Pan and pinch share one `awaitEachGesture` with the card hit-tests, so a pinch that starts on a card does not zoom.
Fix: key the handler on stable inputs (read latest state through `rememberUpdatedState`), wrap gesture cleanup in `finally`, make port hit radius screen-space (min 24dp on screen), and move card actions to a selection toolbar (section 5.3).

**F11. Insert-after rewires the wrong edges.** `addStep` re-links all outgoing edges of the source Step to the new Step regardless of which output port was chosen, and keeps their old `fromPort` (`CanvasViewModel.kt:145-155`). Inserting after port 1 of an If moves port 0's targets too, and the new single-output Step inherits port index 1, which does not exist. When no insert point is set, the Step is chained to the last Step in list order (`:156-160`), not the selected Step. Dropping a wire on empty canvas opens the picker, but the new Step is placed at the viewport centre (`CanvasScreen.kt:736-742`), not at the release point. Fix: rewire only edges from the chosen port, reset to port 0, position at the release point, and chain from the selected Step when there is one.

**F12. The Golden Current never lights.** `StreamEdgeUiModel.isActive/isComplete/isDimmed` (`CanvasUiState.kt:58-60`) are never set (`CanvasViewModel.kt:811-822` builds models with defaults), and `RunUiState.activeStreamIds/completedStreamIds` (set at `:451`, `:461`) are not mapped onto Streams. `RunEvent.StreamFlowing` is never handled (`:522`). The attempts badge is never fed (F4). This is the brand's signature interaction. Fix: derive stream flags in the screen from `state.run`, not in the ViewModel list rebuild.

**F13. Complex parameters are raw text.** KeyValue, Json, Code and Multiline share one text box (`StepInspectorSheet.kt:517-584`). If conditions and Set fields are `KeyValue` edited as JSON text (`LogicNodes.kt:335-337`, `TransformNodes.kt:30-32`), a mobile keyboard user has to type braces and quotes. Select is a chip row without search (`:464-486`), unusable for long lists (Google sheets, timezones, models). `flowId` for Execute Sub-flow is a free text UUID (`CompositionNodes.kt:38`). `advanced` is ignored, so every field of a 15-field Step is visible at once. Number is stored as string. No inline validation for URL, JSON or required fields. Depends on the richer param types proposed in audit 02 (section 3.3).

**F14. Rename breaks references.** `renameStep` updates only the name (`CanvasViewModel.kt:289-296`); `$step("Old name")` in other Steps stops resolving, and `lastOutputs` is keyed by name (F3). Fix: key by `stepId` and rewrite references on rename (with undo).

**F15. Flow list organisation is wired but disconnected.**
- `filteredFlows` is computed (`FlowListScreen.kt:474-480`) but the list renders `state.flows` (`:603`).
- `GroupFilterChip` (`:745`) is never called; `openCreateGroupDialog`, `openMoveFlowDialog`, `openVariablesDialog`, `selectGroup` have no callers; `FlowRowCard(groupName = null)` is hard-coded (`:606`).
- There is no search or sort for Workflows although Credentials, Variables and Data tables have a search box.
- `saveVariable(key, value, groupId)` ignores `groupId` (`:287`), `usedInFlowCount` is hard-coded 0 (`:157`).
- Duplicate exists in the ViewModel (`:395`) but not in the card menu (Pin, Activate, Export, Delete only).
- Import is a file picker for JSON only (`FlowTransfer.kt:190-193`); templates are 6 entries in an `AlertDialog` list.

**F16. Accessibility gaps.**
- Targets below the declared 48dp token (`KaivoTokens.kt:52`): zoom and undo docks 36dp (`CanvasOverlays.kt:249-330`), inspector disconnect 32dp (`StepInspectorSheet.kt:299,324`), rename pencil about 16dp, note icons about 22dp, `fx` chip about 28dp high, Select chips about 38dp, table header cells have no minimum height.
- Wiring a Stream is drag-only; there is no TalkBack alternative (no `customActions`, no "Connect to..." list). Canvas cards expose a good content description (`JewelStepCard.kt` semantics), but the ports are described as "drag to connect" which a screen-reader user cannot do.
- The "Switch this Step off" switch is ON when the Step is disabled (`StepInspectorSheet.kt:339-346`); semantics read inverted, with no `stateDescription`.
- Status colour often carries meaning alone (card borders, stream colour); badges do include text, good.
- Emoji used as icons in badges (`"❄ Frozen"`, `"✓ ..."`, `StepInspectorSheet.kt:160`, `JewelStepCard.kt`), read aloud as "snowflake".
- Good: `reduceMotion` honoured, `Role.Tab` on pills, contrast tokens defined for both themes. The light scheme (Ivory Silk) was not checked for 4.5:1 on `inkMuted` (`KaivoColors.kt:98`); verify.

**F17. No adaptive layout.** No `WindowSizeClass`, rail or two-pane anywhere (grep). The manifest handles `configChanges` itself (`AndroidManifest.xml:70`), so rotation relays out without recreation, but: the inspector content box is `heightIn(240..460.dp)` plus about 190dp of chrome (`StepInspectorSheet.kt:145-242`), about 430dp, which does not fit a 360dp landscape phone, so the footer Run button can fall off screen (verify). On tablets and foldables the inspector is a phone sheet over a large canvas, and Input, Configure and Output cannot be seen together. Dock positions use fixed bottom padding (`CanvasScreen.kt:634,624`) that collides with the HUD in landscape.

**F18. Save and state races.**
(a) `scheduleSave` debounces 450ms in `viewModelScope` (`CanvasViewModel.kt:634-647`) and there is no `onCleared` flush (grep: none), so leaving the editor inside that window drops the last edit.
(b) `observeFlow` assigns `flow = loaded` on every Room emission (`:90-103`), including the echo of the editor's own save. An edit made between the save and the echo is reverted (small window, real). The undo and redo stacks are not reset.
(c) On load and on every variable change the VM merges scoped variables into `flow.vars` with the flow's own copy winning (`:93-95`, `:114`) and then saves that merged map back (`:639`), so stale copies of global variables get written into each flow and shadow later edits of the variable. Cross-reference audit 02.

**F19. Code health.**
- Giant composables: `CanvasScreen` is one function of about 770 lines (`:88-857`) mixing gesture handling, layout, HUD and 12 dialogs; `StepInspectorSheet.kt` holds container, configure form, expression picker and three data views in one 1239-line file; `FlowListScreen.kt` holds the ViewModel, screen, 8 dialogs and 4 tab bodies in 1889 lines.
- State hoisting: the screen keeps about 12 `remember`ed gesture and layout states in the top composable (`CanvasScreen.kt:102-113`), so every drag frame touches a large composable scope; the inspector keeps `focusedField` and `name` locally (`:110-111`).
- Duplication: expression field UI exists twice (`:517-584`, `:586-653`); the `fx` chip is duplicated; 8 near-identical card rows in the inspector.
- Perf risks: `layouts.values.forEach` with `state.steps.firstOrNull { it.id == ... }` is O(n squared) per recomposition (`CanvasScreen.kt:421-423`); `layouts.keys.indexOf` inside `LaunchedEffect` (`:427`); `remember(step.id) { Animatable }` is declared inside a loop that skips off-screen cards without `key(step.id)` (`:421-432`), so positional memoisation shifts as cards enter and leave the viewport and entrance animations can replay while panning (verify); `FakeFlow.from(state)` rebuilds a model Flow on each structural change (`:129`); `FallbackExpressionEvaluator()` is constructed on every preview evaluation (`StepInspectorSheet.kt:397`); `System.getenv()` is copied on each `liveScope` rebuild (`:113-122`). The cards themselves are cheap, and culling plus one-pass streams are good.
- Rule violations: AGENTS.md says no hex literals in feature modules; `CanvasViewModel.categoryColorArgb` hard-codes six hex colours (`:843-851`) and `WorkflowExecutionsSheet.kt` hard-codes `Color(0xFF10B981)` family.
- Stale or misleading comments: the Expression Accessory Bar (`StepInspectorSheet.kt:80-86`), "n8n style" (`:856`), `rawParamsFor` indirection with a no-op `step.paramSummary.let { _ -> ... }` (`CanvasScreen.kt:763-766`).
- Tests: only `CanvasGeometryTest.kt` exists under `feature/` and `app/`. There are no Compose UI tests, no screenshot tests, no ViewModel tests, no baseline profile.

### P2

**F20.** Sticky notes: fixed 240x140 (`Flow.kt:127-128`), plain text, 6-line clamp (`CanvasStickyNote.kt`), no resize, no markdown, no link to a Step. `StepNode.notes` has no UI.
**F21.** Minimap dead code (F8); no level-of-detail rendering below zoom 0.5 (cards at 0.35 zoom are unreadable text).
**F22.** Sub-flow navigation absent: no "Open" action on a sub-flow Step, no breadcrumb or back stack, no run drill-through (`CompositionNodes.kt:38` is a text field).
**F23.** The Environment tab lists the full `System.getenv()` of the app process plus invented `USER`, `DEVICE` entries (`StepInspectorSheet.kt:113-122`, `:758-767`). Meaningless on Android and a minor information exposure; make `$env` an explicit, user-defined set.
**F24.** Preview versus runtime divergence: preview uses the fallback evaluator with no `$step`, `$index`, `$run` (`:113-132`), runtime prefers QuickJS (`FlowEngine.kt:60-64`). Truncates at 60 chars (`:402`). Evaluates only after both `{{` and `}}` exist.
**F25.** Disable switch semantics inverted (F16); Delete without confirmation next to Freeze (F7); delete does not warn about connections lost.
**F26.** Validation shows only the first blocking issue (`CanvasScreen.kt:614`) as a bottom banner that can collide with the HUD (`:624`); no per-field error text, no issues list.
**F27.** Tab pills on the list screen are a `LazyRow` of four tabs (`FlowListScreen.kt:529-564`) with counts; fine on phones, but the Overview header, metrics strip and tab row consume about 220dp before the first flow card is visible.
**F28.** Run HUD "Run" and "Stop" share one button with a Pause glyph for running state (`CanvasScreen.kt:715-717`), which suggests pause, not cancel.

## 5. Touch-first design proposals (identity preserved)

Principles: one thumb, no hover, no right click, no keyboard required (but supported), no precision pointing below 48dp. Everything below reuses existing tokens: Jewel Card, pearls (ports), Golden Current, gold hairline, glass docks, SheetDragHandle, StatusBadge.

### 5.1 Mapper: data mapping without a mouse (replaces "drag from input panel")
Desktop n8n: drag a field from the left panel into a parameter, expression pills, variable browser.
KaivoFlow "Mapper":
- A focused expression-capable field raises a **gold accessory bar** above the keyboard (the feature the code comment already promises): chips for `Previous Step`, `Fields`, `Vars`, `Now`, `fx` and, when relevant, `Format` helpers.
- Tapping `Fields` opens a **half-height Mapper sheet** (detents: peek, half, full) over the keyboard area with a tree: upstream Steps as Jewel mini-cards, each expanding into its last-run data (nested objects, arrays with `[0]` and `[*]` choices, types as small amethyst/gold badges as in the current Schema view).
- Tap inserts a **pearl chip** `{{ step.path }}` at the cursor, never replacing. Long-press a row and drag it onto any field for the same result (drop target glows gold). The chip is a token inside the field; tap to see value, backspace removes whole token.
- Value preview appears under the field as a gold "Result" pill (keep the existing badge) and shows the real type; errors show a rose pill with the offending token highlighted.
- Fixed/Expression toggle per field: the `fx` chip becomes a two-state pearl; in Expression mode the field takes gold hairline and mono font.

### 5.2 Inspector: "Atelier" layout, adaptive
- Phone portrait: keep the bottom sheet but give it three detents and a **segmented header with data badges**: `Input (12)  Configure  Output (3)  Pinned`. Swipe horizontally between segments. Footer becomes one primary gold `Test step` button plus an overflow `...` (Pin, Run to here, Disable, Duplicate, Delete with confirm).
- Phone landscape: full-height side sheet at 60% width, canvas dimmed on the left.
- Tablet or foldable (width class Medium or Expanded): **three pane Atelier** (Input | Configure | Output) with the canvas still visible behind a scrim, mirroring n8n's side-by-side without cloning it.
- Shared lazy `DataViewer` (Table, JSON, Schema) used here and in executions: item pager with 25 or 50 per page, search field, nested expand, long-press a cell for "Copy value", "Copy path", "Map to field". Binary items show a Jewel thumbnail and mime badge.
- Run selector: when a Step ran several times in one execution, a small `Run 2 of 5` stepper.

### 5.3 Selection, context actions, multi-select (replaces right-click and Shift-click)
- **Tap** a card: select and raise a **floating selection toolbar** attached to the card (glass pill, 48dp buttons): Open, Test, Pin, Disable, Duplicate, More. Second tap or a toolbar "Open" opens the inspector. (Today a tap opens the inspector immediately, which makes selection impossible.)
- **Long-press** a card: enter **Selection mode** (haptic, gold halo, top bar becomes `3 selected`, with Group, Duplicate, Copy, Delete, Align). In this mode taps toggle membership; **two-finger drag** on empty canvas draws a lasso; a `Select all` pearl is in the dock.
- **Copy and paste** with connections between selected Steps; paste places at the viewport centre with a ghost preview you can drag, and works across flows (clipboard holds a compact, versioned payload, not a share URL).
- **Undo toast** after delete ("Deleted 2 Steps - Undo"), shown in the existing glass snackbar.

### 5.4 Connections without a pointer
- Every output pearl with no outgoing Stream shows a small gold **`+`** (replaces the hover-and-click "add node" affordance). Tap opens the Step Picker chained from that port; drag still wires.
- Tap an edge (Streams get a 48dp invisible hit corridor): a pearl appears at its midpoint with `+` (insert Step), a scissors (delete) and a label (branch name). This replaces the inspector-only disconnect.
- Dragging from an **input pearl** backwards reconnects or detaches an existing Stream; dropping a wire onto the **body** of a card (not only on the pearl) connects to its input.
- Accessibility: Selection toolbar entry `Connect to...` opens a list of valid targets; this is also the TalkBack path (add `customActions` to cards).

### 5.5 Add-Step picker: faster and smarter
Keep the sheet, drag to place and drop-wire-to-add. Add: Recent and Favorites rows, "Suggested next" row based on the upstream Step's output (for example HTTP Request suggests If, Set, Code), keyword ranking (title, then keyword, then subtitle), type-ahead without needing to open the sheet by first typing in a command palette (a search pearl in the dock; also bound to Ctrl+K for hardware keyboards). Clear `onSearch = {}` no-op (`CanvasScreen.kt:734`).

### 5.6 Per-Step and per-Flow settings
- Inspector gets a fourth, collapsed section **"Behaviour"**: On error (Stop, Continue, Retry with tries and delay, Send to error Step), Always output data, Run once for all items, Timeout, Notes with "Show on canvas" (a small note pearl on the card).
- Flow settings sheet from the title pill (the existing info dialog grows into it): timezone, timeout, error Step, which runs to save, max items, and an **Active** toggle in the top bar (a gold switch pill), so activation is available where the user builds.

### 5.7 Pin and mock data
- Pinned Steps show a gold **pin pearl** on the card (replaces the snowflake text) and a `Pinned` header strip in the Output segment with `Edit`, `Unpin`, size and age.
- `Pin` sources: current output, an execution, pasted JSON, or a small typed editor. A Run toggle in the Run HUD, `Use pinned data`, mirrors the engine's `ignoreFrozen`.
- Pinned data counts toward a size budget shown in the editor (Room row size and export size).

### 5.8 Run, replay and debug (the Golden Current as a debugger)
- **Run to here** and **Run from here** in the selection toolbar. Missing upstream data triggers "Run upstream first?" instead of silently using `{}`.
- Executions sheet becomes a **replay timeline**: pick a run; the canvas enters **Replay lens** (a violet vignette), every Step shows that run's status, counts and duration, every Stream shows item counts, the failed Step pulses rose. Tap a Step to open the inspector in read-only mode with that run's input and output.
- `Debug in editor`: copy that run's data into pins (opt-in, with a size summary) so the user can fix and re-test. `Retry` re-runs with the original trigger payload. Pull-to-refresh and swipe-to-delete with undo for entries.
- Re-light the Golden Current from `RunEvent` as originally intended (F12).

### 5.9 Parameter widgets for thumbs
- **Key-value rows editor** (swipe to delete, drag handle to reorder, `+` row, each value is an expression-capable field with the Mapper accessory bar).
- **Condition builder** for If and Switch: row = field chip, operator select, value; AND/OR toggle; shows plain-language summary on the card.
- **Searchable select sheet** for options over 6 entries, plus a segmented control for 2 to 4 entries (instead of a chip row for everything).
- **JSON editor** with mono font, bracket helper row (`{ } [ ] " : ,`), live validation with the first error line, format button.
- **Code editor** with symbol bar and tab-indent; no attempt at full IDE.
- **Collapsible "Advanced"** driven by `ParamSpec.advanced`.
- **Credential selector** and **Flow selector** (for Execute Sub-flow) as sheets with search, "Add new", and an "Open" shortcut.
- **Sub-flow navigation**: an `Open` chip on a sub-flow Step pushes that flow onto a breadcrumb stack in the top bar; back returns to the exact viewport.

### 5.10 Canvas tools dock (replaces the cramped top bar and floating docks)
One glass **tool dock** (bottom, thumb reach) with: Add, Select mode, Undo, Redo, Tidy (auto layout, undoable), Fit, and a `...` overflow (Notes, Group, Minimap toggle, Flow settings). Keep Run as the single gold pillar. The Minimap returns as a toggled overlay. Tidy runs the existing `guidedLayout` once on demand without flipping the flow's mode permanently.

### 5.11 Flow list, organisation, templates, import
- Search field and filter chips under the header: All, Active, Failed last run, Pinned, plus group chips (wire the existing `GroupFilterChip`). Sort: recent, name, last run.
- Card swipe actions: right = Activate or Deactivate, left = Duplicate, Archive. Delete in overflow with undo snackbar. Long-press enters multi-select for Move to group, Export, Delete.
- Group color dot and name on the card (`groupName` is wired but hard-coded null).
- Templates become a gallery sheet with categories, a preview of the flow (a static mini-canvas rendered with the real Jewel Card at low detail) and a "needs: Telegram credential" requirement row.
- Import: from file, **clipboard** and **share intent** (open from a message). A preview step lists Steps found, credentials needed and unsupported Steps before saving. A converter for n8n-exported workflow JSON is a good growth feature, but it must be written from the exported JSON shape only, with its own mapping table and no n8n source consulted or copied.
- Overview header: collapse the metrics strip on scroll to give the list room.

### 5.12 Adaptive and keyboard
- Use `WindowSizeClass`: Compact (phone), Medium (foldable, small tablet: inspector as 60% side sheet), Expanded (tablet or desktop mode: persistent Atelier panes plus a navigation rail replacing the tab row).
- Hardware keyboard shortcuts for tablets and Chromebooks: Ctrl+Z / Ctrl+Shift+Z, Ctrl+C / V / D, Delete, Ctrl+Enter (run), Ctrl+K (palette), Space+drag to pan. Touch remains the first-class path.

## 6. UI roadmap

Sequencing logic: first make the existing screens trustworthy (dead code, bugs), then close the data loop (credentials, mapping, persisted run data), then add canvas power tools, then executions and organisation, then adaptivity and polish. Items within a phase can run in parallel unless a dependency is named.

| Phase | Theme | Outcome the user feels | Depends on |
|---|---|---|---|
| U0 | Stabilise and wire | Everything that looks built works: duplicate, activate, groups, search, Golden Current | none |
| U1 | Data loop | Pick a credential, map earlier data, test with real data after a restart | audit 02 (typed schema, `$step` in preview), audit 01 (persist inputs) for full value |
| U2 | Canvas power | Select, copy, paste, insert on edges, tidy, per-Step behaviour | U0 |
| U3 | Executions as debugger | Replay a run on the canvas, debug and retry | audit 01 (stored inputs and output cap), U1 viewer |
| U4 | Organise and onboard | Folders, tags, search, template gallery, import preview | U0 |
| U5 | Adapt and polish | Tablet inspector, TalkBack path, keyboard shortcuts, perf and tests | U1, U2 |

## 7. Suggested phase tasks

Sizes: S under 1 day, M 1 to 3 days, L 3 to 8 days. File paths are the main edit targets.

### Phase U0: Stabilise and wire
- **U0-1 (M)** Fix gesture lifecycle: key `pointerInput` on `Unit` and read state via `rememberUpdatedState`; `try/finally` resets; screen-space port hit radius with a 24dp minimum; port hit does not consume the card's lower half. `CanvasScreen.kt:200-361, 895-913`. Acceptance: dragging a card during a live Run is not interrupted; at zoom 0.4 a 5-output Switch is wireable.
- **U0-2 (S)** Move card actions out of the gesture surface: keep rename in the selection toolbar, remove the 12dp pencil and note icons (or give 48dp targets outside the handler). `JewelStepCard.kt`, `CanvasStickyNote.kt`.
- **U0-3 (M)** Light the Golden Current: derive `isActive/isComplete/isDimmed` from `RunUiState` at render time (not in the list rebuild); handle `StreamFlowing`; feed `attempts`. `CanvasScreen.kt`, `CanvasViewModel.kt:435-524`, `StreamCanvasLayer.kt`.
- **U0-4 (S)** Wire dead features: Duplicate in selection toolbar (copy edges between duplicated Steps), Activate pill in the canvas top bar, Tidy button, Minimap toggle, `EmptyCanvasHint`. Delete unused code that has no planned home.
- **U0-5 (M)** Fix insert-after rewire and placement; chain from selected Step. `CanvasViewModel.kt:123-172`; add unit tests for branching insert.
- **U0-6 (S)** Flush save on exit: `onCleared` and `ON_STOP` call `flowRepository.save` immediately in `NonCancellable`; stop `observeFlow` overwriting local edits (compare `updatedAt`, ignore own echo). `CanvasViewModel.kt:88-103, 634-647`.
- **U0-7 (M)** Undo coalescing: one snapshot per field edit session; rename once on commit; validation and UI-model sync only for the touched Step. `CanvasViewModel.kt:224-228, 572-586`.
- **U0-8 (S)** Fix Flows list: render `filteredFlows`, add search, group chips, Move to group and Duplicate in the card menu, pass `groupName`, honour `groupId` in `saveVariable`, real credential usage count. `FlowListScreen.kt:157, 287, 474-480, 603-613`.
- **U0-9 (S)** Safety: confirm Delete in the inspector with undo toast; separate Delete from Freeze; correct the disabled-switch semantics (`stateDescription`).
- **U0-10 (S)** Remove hex literals in `CanvasViewModel.categoryColorArgb` and `WorkflowExecutionsSheet` by using tokens (`colors.categoryColor`, `colors.statusSuccess/Error`).

### Phase U1: Data loop
- **U1-1 (M)** Credential param type and selector sheet, create-in-place, test connection; migrate existing `credential` params; hide Secret fields that are really credentials. `NodeSpec.kt`, node catalog, `StepInspectorSheet.kt`, a new `CredentialPickerSheet.kt` in `feature/canvas`. Acceptance: Telegram Step can be configured end to end with no id typing.
- **U1-2 (L)** Mapper: accessory bar, upstream tree (Steps, nested, arrays), cursor insertion, drag-to-field, token rendering in fields. Add `$step` entries. New `feature/canvas/mapper/` package. Acceptance: map `step.body.items[0].title` into an HTTP URL field in under 10 taps.
- **U1-3 (M)** Persisted data hydration: key outputs by `stepId`; initialise inspector input and output from the latest `RunRecord`; label data age and source; "Run upstream first" prompt. `CanvasViewModel.kt:198-214, 433-454, 527-568`. Acceptance: inspector shows data after process restart; rename keeps data.
- **U1-4 (L)** Shared lazy `DataViewer` (Table, JSON, Schema, binary), paging, search, nested expand, path copy, redaction everywhere. Replace the inspector and snapshot views. Needs a size cap strategy with audit 01.
- **U1-5 (M)** Pin editor and pinned strip; `Use pinned data` run toggle; pin from execution. `CanvasViewModel.kt:371-387`, inspector footer.
- **U1-6 (M)** Preview parity: evaluate with the runtime evaluator and a scope that includes `step`, `index`, `run`; debounce; cache evaluator; stop exposing the full process environment. Needs the `$env` decision in audit 02.
- **U1-7 (M)** Rename rewrites references and re-keys data (F14), with undo.

### Phase U2: Canvas power
- **U2-1 (L)** Selection model: tap-select toolbar, long-press multi-select, lasso, select all; group, delete, align for many. New `SelectionState` in the ViewModel, `CanvasSelectionToolbar.kt`.
- **U2-2 (M)** Copy, paste, cross-flow paste payload with connections (versioned, secrets stripped). `FlowTransfer.kt` helpers reused.
- **U2-3 (M)** Edge interaction: tap-to-select, insert Step, delete, rename branch; `+` pearls on dangling outputs; reconnect by dragging from input; body-drop connect.
- **U2-4 (L)** Behaviour section: on error, retry, always output, run once, timeout, notes-on-canvas; Flow settings sheet with active toggle. Needs engine support for any option not yet implemented (audit 01).
- **U2-5 (S)** Step Picker: recents, favorites, suggested next, ranking; remove the no-op `onSearch`.
- **U2-6 (M)** Group creation from selection; resizable sticky notes with simple markdown; link a note to a Step.
- **U2-7 (M)** Parameter widgets: key-value rows, condition builder, searchable select, JSON and code editors, Advanced collapse. Depends on richer `ParamType` from audit 02.
- **U2-8 (M)** Sub-flow: Flow selector param, `Open` chip, breadcrumb back stack, run drill-through.

### Phase U3: Executions as a debugger
- **U3-1 (L)** Replay lens: select a run, canvas renders that run's per-Step status, per-Stream counts, failed Step highlight; inspector read-only. Needs stored inputs and sane output caps (audit 01).
- **U3-2 (M)** Execution list: filters (status, trigger, date), search, retry with the original trigger, delete, prune, pull to refresh. Make global history rows open the flow in Replay.
- **U3-3 (S)** Dashboard metrics from a SQL aggregate instead of the last 50 runs; label correctly.
- **U3-4 (M)** Debug in editor: run data into pins with a size summary.

### Phase U4: Organise and onboard
- **U4-1 (M)** Tags (many to many) alongside groups; list filters and sort; multi-select actions; archive. Needs a Room migration (audit 02 data owner).
- **U4-2 (M)** Template gallery with categories, mini preview, requirement chips; more starter templates for phone-native uses (SMS, notification, battery, location, share sheet).
- **U4-3 (M)** Import preview from file, clipboard and share intent; validation and "unsupported Step" report. Optional later: n8n JSON converter, written from the public JSON shape only.
- **U4-4 (S)** Split `FlowListScreen.kt` into `FlowListViewModel.kt`, `FlowListScreen.kt`, `dialogs/`, `tabs/`.

### Phase U5: Adapt and polish
- **U5-1 (L)** `WindowSizeClass` layouts: side-sheet inspector (Medium), three-pane Atelier plus rail (Expanded); landscape-safe footer; dock placement.
- **U5-2 (M)** Accessibility: raise all targets to 48dp (token enforcement via a lint or a shared `KaivoIconButton`), `customActions` on cards (Connect to, Open, Disable), `stateDescription` for toggles, replace emoji badges with icons, verify Ivory Silk contrast.
- **U5-3 (M)** Keyboard shortcuts for hardware keyboards.
- **U5-4 (M)** Performance: replace O(n squared) lookups with maps, `key(step.id)` in the card loop, split `CanvasScreen` into `CanvasGestureHost`, `CanvasLayers`, `CanvasChrome`, `CanvasDialogs`; add level-of-detail rendering below zoom 0.5; consider `derivedStateOf` for visible rect; generate Compose compiler stability reports and fix any unstable UI model.
- **U5-4b (M)** Test baseline: ViewModel unit tests (insert, connect, undo coalescing, rename rewrite), Compose UI tests for inspector fields and mapper, a macrobenchmark with a 500-Step Flow, and a baseline profile.

### Cross-lane dependencies and open questions
- Audit 01: persist per-Step inputs and raise or paginate the 20-item output cap; run-to-here and run-from-here entry points; behaviour options for `alwaysOutputData` and `executeOnce`.
- Audit 02: richer `ParamType`s (Credential, Flow reference, Conditions, KeyValue rows, Options with search), typed schema for the Mapper, final `$env` model, `$step` in the fallback evaluator parity.
- Security audit: Secret params in Flow JSON (`HttpNode.kt:57-59`) and exports; redaction in snapshots.
- Verify on device (not provable by reading): F2 stale `focusedField`, F10 (a) to (d), F17 landscape footer, F19 entrance animation replay.
