# Audit v2 / 08: Editor UX and whole-repo code quality

Branch `claude/phase-4-base` (= origin/main). Read-only audit. Reference: n8n 2.48.6 at `/tmp/ref/n8n` (behaviour only; nothing copied).
Severity: P0 = data loss or crash in normal use. P1 = serious bug, or a gap that blocks a core user journey. P2 = polish, risk, cleanup.
Every KaivoFlow claim below was checked in code. Where only reading was possible, it says "verify on device" and is repeated in section 8.

Counts: P0 = 0, P1 = 14, P2 = 31 (section 4).

## 1. Scope and files read

Part A, editor UX. Read fully:
- `feature/canvas`: `CanvasViewModel.kt`, `CanvasScreen.kt`, `CanvasUiState.kt`, `CanvasOverlays.kt` (structure), `CanvasTransform.kt`, `layout/CanvasGeometry.kt` (partly), `render/StreamCanvasLayer.kt` (head), `render/JewelStepCard.kt` (animation parts), `render/CanvasGroupFrame.kt`, `sheets/WorkflowExecutionsSheet.kt`, `sheets/StepPickerSheet.kt` (head), `picker/PickerLogic.kt` (outline), `inspector/host/*` (StepInspector, CanvasInspectorGateway, InspectorTabs, panes), `inspector/state/InspectorStateHolder.kt`, `inspector/logic/*` (layout, behaviour, test plan), `inspector/panels/*` (paging, data source, DataPanel).
- `feature/flows`: `FlowListScreen.kt` (VM and list), `FlowTransfer.kt`, `PublishSheet.kt`, `RunHistoryScreen.kt`, `OverviewTabs.kt` and `AgentsDashboardView.kt` (outline), `ai/agent/AgentRepository.kt`, `ai/AiModule.kt`.
- `feature/settings` (`SettingsScreen.kt`, `ReliabilityCenterScreen.kt` VM), `app` (`MainActivity.kt`, `KaivoApplication.kt`, manifest, themes), `core/design` (`KaivoTheme.kt`, `Motion.kt`).
- Core pieces touched by the editor: `core/model/Flow.kt`, `core/data/Repositories.kt`, `NativeFlowBundle.kt`, `DataModule.kt`, `core/engine` pin and run code (grep-level), `core/nodes/ai/agents/AgentNode.kt` (params).

Part B, whole repo: all 499 `.kt` files (89,385 lines) measured by script (duplicates, dead symbols, size, tests per module), all `build.gradle.kts`, `libs.versions.toml`, `gradle.properties`, `proguard-rules.pro`, manifest.

n8n side read: `packages/frontend/editor-ui/src` features `workflows/canvas`, `ndv`, `execution`, `workflows/workflowHistory`, `workflows/templates`, and `app/composables` (`usePinnedData`, `useHistoryHelper`, `useKeybindings`, `useNodeDirtiness`, `useRunWorkflow`, `useExecutionDebugging`), `app/stores/history.store.ts`, `app/constants/limits.ts`, `@n8n/api-types` size limits.

Claims in `docs/05_CANVAS_UI_INSPECTOR_AND_DEBUGGING_GAP_ANALYSIS.md` that are wrong in code: "Pin Data missing" (it exists, see 3.2), "3-pane inspector missing" (it exists, `InspectorLayouts.kt:5-18`), "mini-map missing" (a `Minimap` composable exists but is never mounted, so the UI has none).

## 2. How n8n does it

Canvas (`features/workflows/canvas`, `app/composables/useCanvasOperations.ts`)
- The canvas is a node-graph widget. Nodes have typed handles. Main data flows left to right. AI "sub-nodes" (model, memory, tools, parser) attach through separate diamond handles under the parent node (`elements/handles`, `render-types/CanvasNodeAgent.vue`). The inspector for the parent shows a "sub connections" strip to add them (`ndv/panel/components/NDVSubConnections.vue`).
- Node render types: default node, "add nodes" placeholder, choice prompt, sticky note. Sticky notes are resizable, have a colour selector in a toolbar, and render markdown.
- Selection: click, ctrl-click, rubber-band, select all, arrow keys move selection to sibling nodes, shift+arrows select upstream or downstream. Copy writes node JSON to the system clipboard (so it pastes across tabs and instances). Cut, paste, duplicate, delete, disable (d), pin (p), rename (F2), replace node (r), tidy up (shift+alt+t), zoom keys (+ - 0 1), toggle logs (l), focus panel (shift+f), add node (n or tab), add sticky (shift+s), run (ctrl+enter), save (ctrl+s). See `Canvas.vue:542-650`.
- Undo/redo is a command stack of 100 entries (`history.store.ts`). Moves, add, remove, connect, disconnect, rename, enable-toggle and paste are commands. Several commands made together are one BulkCommand (for example delete a node plus its connections). Undo is blocked while the inspector or a modal is open (`useHistoryHelper.ts`).
- Deleting a node in a simple chain reconnects its neighbours. Dropping a node on a connection inserts it between.
- Node groups (2.x) let the user frame and collapse several nodes; there is a tidy-up auto layout.

Node details view, NDV (`features/ndv`)
- Three panels: Input, node settings (parameters, settings, docs), Output. Input has a parent-node picker and shows the data of the chosen parent. Both data panels have display modes: Table, JSON, Schema, Binary, Markdown/HTML for AI output. Items are paged (25 per page, `app/constants/nodes.ts:14`). Display is capped by size: 1 MB for table and JSON, 4 MB for schema, 512 KB in logs (`limits.ts`). Over the cap the user must click to reveal.
- Data can be dragged from the input panel into a parameter field; the field turns into an expression and shows a live preview. Hovering rows highlights the paired input item.
- "Execute step" runs the node. If a parent has no data, n8n runs the needed parents first (partial execution with a destination node, `useRunWorkflow.ts:138-260`). Result stays visible until something upstream changes; a "dirty" mark appears on nodes that are stale after a parameter, connection or enable change (`useNodeDirtiness.ts`).
- Each node has Settings: retry on fail (max tries, wait), on error (stop, continue, continue with error output), always output data, execute once, notes, display note in flow, node colour.
- Error view shows message, description, HTTP status and body, the item index, and a stack on request (`runData/components/error/NodeErrorView.vue`).

Pin data (`app/composables/usePinnedData.ts`)
- A pin freezes a node's output as JSON stored in the workflow. Pinned data replaces execution of that node in manual runs only. Limit 12 MB per pin, workflow limit 16 MB (`@n8n/api-types base-workflow.dto.ts`). The pin editor validates JSON, shows a size error, and warns if data was trimmed. Loop and sticky nodes cannot be pinned. A pinned node shows a pin icon on the canvas.

Executions and debugging (`features/execution`)
- Per-workflow executions list with a preview that shows the workflow read-only with green/red nodes and per-node data. Global executions list with filters: status, workflow, tags, date range, metadata. Actions: retry (same data or latest workflow), stop, delete (single and bulk), annotate (tags, rating).
- "Debug in editor" copies a past execution's data into the editor so the user can fix and re-run. It pins the trigger-side data, asks before overwriting existing pins, refuses redacted or trimmed data, and warns about AI-simulated data (`useExecutionDebugging.ts`).
- A logs panel under the canvas shows a tree of node runs with input and output side by side, token counts, and timing.

Workflow-level features
- Workflow history: versions with names, diff view, restore, clone (`features/workflows/workflowHistory`, `workflowDiff`).
- Templates: a browsable library; setting up a template walks the user through credentials (`features/workflows/templates`, `setupTemplate.store.ts`).
- Import from file, URL, clipboard paste, and a cURL importer. Extract selected nodes into a sub-workflow (`useWorkflowExtraction.ts`).
- Settings per workflow: timezone, error workflow, timeout, save data on success/error/manual, "save execution progress", callers policy.
- Organisation: tags, folders, projects, favourites, search and sort in the list, archive instead of hard delete.

Edge cases n8n handles that matter on a phone: large data (size gates), trimmed data detection, circular refs in JSON, invalid JSON in the pin editor, pinned data hiding a trigger, a modal open while a key is pressed, autosave conflicts.

## 3. How KaivoFlow does it today (verified)

### 3.1 Canvas (`feature/canvas`)
- Single gesture layer in `CanvasScreen.kt:234-396`: output-port drag to connect (hit radius 28 world units, `:1036`), card tap opens inspector or drag moves it, sticky note tap or drag, empty space pan and pinch. Pan and zoom are applied by one `graphicsLayer` (`:401-408`).
- Two layout modes: Guided (auto vertical chain) and Freeform (`CanvasViewModel.kt:446-461`, `CanvasGeometry.kt:74,106`). Moving a card in Guided converts to Freeform (`:420-444`).
- Streams are drawn by one Canvas for all edges plus one for travelling orbs (`StreamCanvasLayer.kt:1-30`); a card only animates while running (`JewelStepCard.kt:162-175`). Cards outside the viewport are not composed (`CanvasScreen.kt:502-509`).
- Undo/redo: snapshot stack of steps, streams, notes, groups; depth 30 (`CanvasViewModel.kt:116-124,597-645`). Typing in one field is one undo step (800 ms window, `:324-330`). Dock buttons at `CanvasOverlays.kt:266`.
- Edit actions available: add (picker or drop), delete, duplicate (one step, no streams, `:345-350`), disable, rename, connect with cycle check (`:353-381`), disconnect via a button on every stream (`CanvasScreen.kt:420-469`), sticky notes (add, edit, drag, delete), group frames (edit, ungroup, drag).
- Not present (grep verified): multi-select, copy/paste, key events (zero `onKeyEvent` in `feature/` and `app/`), minimap mount, tidy-up, create-group UI, find/replace, node search on canvas.
- Step picker: category pages, fuzzy search index with typo tolerance, recent and favourites, drag from picker onto canvas (`picker/PickerLogic.kt`, `StepPickerSheet.kt`). Good.
- Run: whole-flow only (`CanvasViewModel.kt:475-509`), live per-step status, golden stream animation, HUD with cancel. No "run to here". Test of one Step is in the inspector.

### 3.2 Inspector (matches the user's standing rules)
- Layouts: phone portrait = bottom sheet with 3 pages, phone landscape = 60% side sheet, tablet (width >= 600 dp and height >= 480 dp) = 3 resizable panes (`InspectorLayouts.kt:14-18`, `StepInspector.kt:151-185,333-374`). One drag-to-map host wraps all (`:152`).
- Data panels (`inspector/panels`): Table, JSON, Schema, Binary views; paged from the database by `StepDataStore.page` with search (`PanelDataSource.kt:42-51`, `Paging.kt`), so big outputs never sit in memory. Truncation banner (`DataPanel.kt:132-137`). Drag a field to a parameter or use "Map to" sheet (`StepInspector.kt:187-193`).
- Test Step runs only the chosen Step on its own input (`CanvasInspectorGateway.kt:229-282`, `TestStepLogic.kt:31-44`). Input sources: Pinned, Last run, Last test, Manual JSON, Spark sample (`InspectorModels.kt:28-33`, `CanvasInspectorGateway.kt:176-216`). Earlier Steps run only if the user taps "Run earlier Steps first" (`TestInputBar.kt:79`). Side-effect Steps ask for confirmation first (`CanvasInspectorGateway.kt:246-249`, `InspectorTabs.kt:70-82`). Result is stored as a manual execution, not counted as a Run.
- Pin: `PinStore` with size caps and truncation refusal (`CanvasInspectorGateway.kt:97-107`), pin strip with edit and unpin (`DataPanel.kt:123-131`), engine replays pins in manual Runs only (`FlowRunner.kt:438-448`).
- Past Run viewer: the same inspector opens read-only on one stored execution (`CanvasViewModel.kt:245-265`, `WorkflowExecutionsSheet.kt:427`). "Test from here" tests one Step on the newest stored data (`:272-280`).
- Behaviour tab: on error (stop, continue, error output), retry with attempts, wait and backoff, timeout, notes (`StepBehaviour.kt`). Missing vs n8n: execute once, always output data, display note on card.
- Expression fields have live preview, error spans, and chips (`ExpressionField.kt`, `CanvasInspectorGateway.kt:113-136`).
- Sub-node slots of the AI Agent (`chatModel`, `memory`, `tools`, `outputParser`) are `ParamType.Json` text fields with a JSON hint (`core/nodes/.../AgentNode.kt:77-82`). There is no slot picker.
- Errors: Test Step failure shows an error block with item index, copy details and "Test again" (`InspectorTabs.kt:101-115`). The store already keeps `httpStatus`, `httpBody`, `details` (`StepExecutionRecorder.kt:31-39`) but the banner does not show them.

### 3.3 Execution views
- Canvas sheet (`WorkflowExecutionsSheet.kt`): 50 latest Runs of this Flow (`Repositories.kt:181`), filter All/Succeeded/Failed (`:77-85`), expandable cards with per-step rows, "View input and output" and "Test from here" buttons (`:426-429`).
- Global Run history (`RunHistoryScreen.kt`): read-only list of the last 50 Runs, not tappable (`:82-143`).
- Live run state on canvas: status badges, item counts, durations, orbs.

### 3.4 Flow list, publish, transfer
- `FlowListScreen.kt` (2,297 lines) holds five tabs (Workflows, Agents, Credentials, Variables, Data tables) and a 400-line ViewModel (`:129-527`).
- Publish sheet is polished: checklist, per-Spark results, haptics, gold sweep (`PublishSheet.kt:108-262`). It is opened from the Flow list (`FlowListScreen.kt:555`). The canvas hosts it too (`CanvasScreen.kt:909-915`) but nothing can open it (E06).
- Import/export (`FlowTransfer.kt`): redacted structure file and `.kaivoflow` backup, size-limited read, preview dialog, fresh ids, secrets removed (`NativeFlowBundle.kt:26-36,249-277`). Good and careful.
- Sticky notes: model has width and height (`Flow.kt:122-131`) but there is no resize handle and no markdown.

### 3.5 Navigation, settings, design system
- One `NavHost`, 10 routes, slide+fade transitions that honour reduced motion (`MainActivity.kt:94-215`). No bottom bar; the Flow list is the hub.
- Settings: Reliability Center, battery checks, Privacy, Permissions, Credentials, Run history (`SettingsScreen.kt:37-88`). No theme switch, no motion setting, no per-Flow settings.
- Design system (`core/design`): tokens, motion policy with levels Full/Subtle/Off, device tier, battery-saver, system animator scale (`KaivoTheme.kt:31-50`), haptics, shared components. Dark theme "RoyalVelvet" plus light "IvorySilk" and system mode exist (`KaivoTheme.kt:15-24`) but `MainActivity.kt:63` calls `KaivoTheme { }` with the default, so only dark is reachable.

### 3.6 Part B facts
Size, tests per module (main lines / test lines / @Test count):

| Module | Main files | Main LOC | Test LOC | @Test | Note |
|---|---|---|---|---|---|
| core:model | 19 | 3,071 | 1,255 | 108 | good |
| core:engine | 40 | 8,099 | 5,382 | 400 | strongest |
| core:expressions | 14 | 3,678 | 1,695 | 159 | good |
| core:ai | 26 | 2,634 | 974 | 77 | ok |
| core:nodes | 109 | 8,848 | 3,583 | 206 | about 2 tests per node file |
| core:data | 18 | 3,957 | 1,517 | 75 | ok, migrations tested |
| core:network | 2 | 265 | 0 | 0 | none |
| core:work | 40 | 6,290 | 0 | 0 | Android adapters; logic is tested in core:engine; adb scripts in `scripts/` |
| core:design | 11 | 2,666 | 167 | 17 | motion logic only |
| feature:canvas | 67 | 16,055 | 2,535 | 197 | inspector logic well tested; `CanvasViewModel`, screen untested |
| feature:flows | 34 | 14,914 | 217 | 19 | almost untested (AI helpers only) |
| feature:settings | 3 | 904 | 0 | 0 | none |
| feature:permissions | 2 | 289 | 0 | 0 | none |
| app | 2 | 307 | 0 | 0 | `androidTest` deps declared, no androidTest folder |

Files over 600 lines (18, all main code): FlowListScreen 2297, AgentBuilderScreen 2023, AiAssistantScreen 1422, OverviewTabs 1318, AiSettingsSheet 1211, CanvasScreen 1079, CanvasViewModel 1008, StepPickerSheet 994, FlowRunner 895, CanvasOverlays 822, AgentsDashboardView 752, AiAssistantViewModel 713, DataComponents 708, ExpressionAssist 702, CredentialEditSheet 699, expressions `Library.kt` 653, `core/data/Repositories.kt` 621, ComplexWidgets 603. Close behind: BasicWidgets 590, AgentBuilderViewModel 560, ExpressionField 510.

Build and dependencies:
- Kotlin 2.0.21 (strong skipping is default), AGP 8.3.2, Compose BOM 2024.10.01, Hilt 2.51.1, Room 2.6.1, WorkManager 2.9.1, Ktor 2.3.12, coroutines 1.8.1, lifecycle 2.8.6, navigation 2.8.3, security-crypto 1.1.0-alpha06 (`libs.versions.toml:3-21`). The file comment says versions were "verified" against Maven; that proves they exist, not that they are current. All date from late 2024.
- compileSdk 34, targetSdk 34, minSdk 26, versionCode 1 (`app/build.gradle.kts:11-17`).
- Root `build.gradle.kts:21` passes `-Xskip-metadata-version-check` and `:29-31` disables `CheckAarMetadataTask` for every module. Both switch off safety checks.
- Release: R8 full mode, shrink resources, `proguard-rules.pro`; flavours `play` and `sideload`; no signing config, no lint config, no detekt/ktlint, no CI folder (`.github` absent).
- Hilt: 9 modules, 9 `@HiltViewModel`, 52 `@Provides/@Binds`. Database singleton, repositories singleton, migrations registered, no destructive fallback (`DataModule.kt:20-23`), schema exported. Wiring looks correct.

## 4. Issues found

IDs: E = editor and data, Q = code quality.

| ID | Sev | Where | What | Why it matters | Fix idea |
|---|---|---|---|---|---|
| E01 | P1 | `CanvasViewModel.kt:162-165, 659-672` | Save is a 450 ms debounced coroutine in `viewModelScope`. `onCleared` does not flush; leaving the screen (back, swipe from recents, Home then process kill) inside 450 ms drops the last edit. | Silent data loss of the last tap. | Flush in `onCleared` through an injected app-scope with `NonCancellable`; also flush on `ON_STOP`. |
| E02 | P1 | `CanvasViewModel.kt:130-144`, `Repositories.kt:92` | Every database emission replaces the in-memory `flow`. Emissions come after our own save, and also after `recordRun`, favourite, group move and lifecycle writes. An edit made between save start and the echo is overwritten, and the next debounced save writes the old copy. Typing with pauses around 450 ms is the likely trigger. | Reverted edits, lost characters. Verify on device. | Keep a local revision; ignore emissions with `updatedAt <=` local; adopt external changes only when nothing is pending. |
| E03 | P1 | `CanvasViewModel.kt:133-137, 152-158` | Global and group variables are merged into `flow.vars` and then saved with the Flow (`scheduleSave`). `(resolved + flow.vars)` makes the stored copy win. After the first save, changing a variable on the Variables tab no longer reaches that Flow in manual Runs. Values are also copied into `files/flows/<id>.json` (`Repositories.kt:107-138`) and into exports (`NativeFlowBundle.kt:99`, key-name redaction only). | Wrong data at run time; secrets duplicated per Flow. | Merge only inside `previewFlow()`; never into `flow`. Resolve at run time from the repository. |
| E04 | P1 | `CanvasViewModel.kt:191-201` | "Insert after" (drag from a port into empty space, then pick) re-links every outgoing stream of the source regardless of port, and keeps the old `fromPort` on the new Step. After an If or Switch the other branch is stolen and edges point at a port the new Step does not have. | Corrupts branching Flows. | Move only edges with `fromPort == insertPort`, reset moved edges to port 0. Add a unit test. |
| E05 | P1 | `CanvasViewModel.kt:392-399`, `StepBehaviour.kt:rename`, `Expression.kt:65-66,130`, `StepExecutor.kt:78` | Expressions reach Steps by name. Rename neither checks duplicates nor rewrites `$step("Old")` references. Add and duplicate keep names unique (`:896-901`), rename does not. | Silent breakage after a rename; ambiguous lookups. n8n updates references. | Unique-name guard; rewrite references with the expression parser; toast "Updated N expressions". |
| E06 | P1 | `CanvasViewModel.kt:678-699`; `CanvasScreen.kt:909-915` | `requestActivateToggle` and `toggleFlowActive` have no caller; the publish host in the canvas can never open. `toggleFlowActive` also writes `isActive`, which the repository treats as a read-only mirror (`Repositories.kt:96`). Publishing works only from the Flow list. | A user in the editor cannot publish; the flagship "24/7" action is missing from the screen where work happens. | Publish button in the top bar calling `requestActivateToggle`; delete `toggleFlowActive`. |
| E07 | P1 | `Flow.kt:254`, `StepBehaviour.kt:11-15`; grep shows no UI writes `errorStepId` | "Error output" is offered in the Behaviour tab, but no screen lets the user choose the Flow's error Step (`settings.errorStepId` is only read by `FlowValidator.kt:96`, `FlowRunner.kt:383`). Flow settings (timezone, timeout, save-runs, max items) have no UI at all. | The error path is unusable by hand. n8n has Error Workflow and workflow settings. | Flow settings sheet: error Step picker, timezone, timeout, save success/failed Runs. |
| E08 | P1 | `FlowListScreen.kt:725, 583, 910, 279, 307` | The list shows `state.flows`; `filteredFlows` (`:583`), `GroupFilterChip` (`:910`), `selectGroup` (`:279`) and `openMoveFlowDialog` (`:307`) have no call sites. Groups can be created but not selected or assigned. There is no search, sort or status filter for Flows (other tabs have search). | At 30+ Flows the home screen is unusable. | Search bar, sort (recent, name, last run), filter (live, failed), mount group chips, "Move to" in the card menu. |
| E09 | P1 | `AgentNode.kt:77-82` | AI Agent slots are raw JSON text with a hint that contains `<vault id>`. | The headline AI feature needs hand-typed JSON; n8n gives a sub-node picker. | Slot widgets in the Parameters pane: model picker (provider, model, credential), tools list with toggles, memory choice. |
| E10 | P1 | `StepDataDao.kt:57,92` (never called); `FlowListScreen.kt:510-516`; `CanvasViewModel.kt:338-343` | Deleting a Flow or a Step leaves its stored Step data and pins; `Run` rows of a deleted Flow stay (no per-flow delete in `RunDao`). Pins can hold real user data. | Privacy and storage bloat; "delete" does not delete. | Call both `deleteForFlow` in `FlowListViewModel.delete`; delete the pin when a Step is removed; delete Runs of the Flow. |
| E11 | P1 | `MainActivity.kt:59` | `enableEdgeToEdge()` with default style follows the system theme while the app is dark-only. On a phone in light mode, status and navigation icons can render dark on near-black. Verify on device. | First-impression defect on many phones. | `enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT), SystemBarStyle.dark(TRANSPARENT))`. |
| E12 | P1 | `FlowTransfer.kt:80-85,144,162`; `NativeFlowBundle.kt:67-71,121`; `Pins.kt:42,87` | Backup export never passes pins (the trailing lambda binds to `secretKeysForType`); import drops `Preview.pins`; `PinStore.restore` has no production caller. Core supports pins in bundles, the UI does not wire it. | A restored backup loses pinned test data. Not a promise in the warning text, so a gap not a bug. | Pass pins on export; call `restore` after `save` on import; show "N pins restored". |
| E13 | P1 | `CanvasViewModel.kt:842`; `JewelStepCard.kt:303`; `FlowRunner.kt:438-451` | The card badge reads only the legacy `frozenOutput`. Steps pinned through the inspector (`PinStore`) show nothing on the canvas, yet manual Runs silently replay them. Two pin systems exist. | "Why did my Step not run?" confusion; n8n shows a pin icon. | Observe pinned Step ids per Flow in the VM, show a pin badge; migrate `frozenOutput` into `PinStore` and remove it. |
| E14 | P1 | `CanvasScreen.kt:420-469, 502`; `CanvasTransform.kt:9-12` | The class comment promises zero recomposition while panning, but `visibleRect` reads pan and zoom in composition (`:502`), so the whole screen body recomposes per frame. Inside it, the disconnect joint (Box, Icon, clickable) is built for every stream, culled or not. Verify with a 100 and 500 Step Flow. | Pan jank grows with Flow size (O(streams) per frame). | Read the transform in a `derivedStateOf` or draw phase; cull joints; show joints only for the selected or pressed stream. |
| E15 | P2 | `CanvasViewModel.kt:475-509, 517-593` | Step statuses, item counts and failure marks of the previous Run stay until each Step starts. Skipped Steps keep stale marks (`:571` only changes Pending). | A fresh Run looks half-finished or wrongly red. | Reset step statuses in `RunStarted`. |
| E16 | P2 | `CanvasScreen.kt:309, 354` | Drag slop sums the start-to-current distance on every move event instead of using it once. A slow tap with 2 to 3 px jitter crosses the slop and becomes a drag, so the inspector does not open. | Missed taps. | `val moved = (change.position - startScreen).getDistance()`; compare to slop. |
| E17 | P2 | `CanvasViewModel.kt:801-818`; `CanvasGroupFrame.kt:93-97`; `CanvasGeometry.kt:57,63` | `moveGroup` snaps each per-frame drag delta to a 16-unit grid, so deltas under 8 become 0 (slow drag does nothing). In Guided mode it writes x/y into steps that are 0, while `CanvasScreen.kt:524-526` draws cards at stored positions and streams at guided positions. No undo snapshot. Groups can only come from AI or import (E26). | Group drag feels broken; cards detach from streams. | Accumulate and snap the total; convert Guided to Freeform like `moveStep`; push snapshot. |
| E18 | P2 | `CanvasScreen.kt:234` | `pointerInput` is keyed on `state.steps`, which changes on every run event, so an active pan or pinch restarts during a live Run. | Gesture stutter while running. | Key on `Unit`, read the latest state through `rememberUpdatedState`. |
| E19 | P2 | `CanvasViewModel.kt:289-295, 604-611, 826-884` | Each keystroke runs full-flow validation and rebuilds all UI models with O(steps x streams) lookups (`flow.step`, `registry.spec` per stream, `firstOrNull` per step). | Typing lag in big Flows. | Debounce validation (150 ms); build id-to-index maps once per sync. |
| E20 | P2 | `Repositories.kt:92, 197-201` | `observeFlow`, `observeRecent`, `observeForFlow` decode JSON in `map` with no `flowOn`; the collector runs in `viewModelScope` (Main). Credentials streams do use `flowOn(io)` (`:299`). | Main-thread JSON decode of 50 Runs or a large Flow after each save and Run. | Add `.flowOn(Dispatchers.Default)`. |
| E21 | P2 | `CanvasViewModel.kt:665-669` | Every save calls `scheduler.schedule` or `cancel`, i.e. a reconcile pass, even for drafts. (`Reconciler.kt:98-107` correctly arms published content, so no leak of drafts.) | Needless work per edit. | Call only when the lifecycle or a Spark changed. |
| E22 | P2 | `WorkflowExecutionsSheet.kt:77-85`, `Repositories.kt:181`; `RunHistoryScreen.kt:82-143` | Filters All/Succeeded/Failed only; 50 Runs max; no delete, retry, stop, search, date filter; global history rows are not clickable; no "Debug in editor". | Weak debugging story compared with n8n. | See section 5 and task T10. |
| E23 | P2 | `WorkflowExecutionsSheet.kt:331` | `if (isExpanded) "unfoldMore" else "unfoldMore"`: same icon both ways. Hard-coded greens and reds at `:284-285, 301-302`. | Cosmetic; breaks light theme. | Use tokens, rotate chevron. |
| E24 | P2 | `CanvasOverlays.kt:143,150` (`CanvasUiState.kt:84`) | HUD elapsed time is computed from `currentTimeMillis()` with no ticker, so it freezes until the next step event. | Looks hung during a long HTTP Step. | `produceState` ticker while running. |
| E25 | P2 | `StepInspector.kt:305-330` | Phone page switch is an instant jump; `swipe` is only applied on release, so the pages do not follow the finger. All three panes are always composed. | Not premium; costs memory. | `animateFloatAsState` on offset, apply `swipe` while dragging; or `HorizontalPager` with `beyondViewportPageCount = 1`. |
| E26 | P2 | `CanvasViewModel.kt:717-799`; `CanvasOverlays.kt:754` | `createGroup`, `openCreateGroupDialog`, `CreateGroupDialog` are never called. Step Groups cannot be made by the user. | Half-built feature. | Add "Group selection" once multi-select exists, or remove. |
| E27 | P2 | `CanvasScreen.kt:435`; `:669,689,709`; `CanvasOverlays.kt:283,300,340,352,364` | Touch targets of 26 dp (disconnect), 36 dp (undo, redo, zoom) and 40 dp (note, history, AI) are below the 48 dp token (`KaivoTokens.kt:52`). Port hit radius is 28 world units, shrinking on screen when zoomed out. | Fat-finger errors, accessibility. | Pad hit areas to 48 dp; port radius in screen px. |
| E28 | P2 | `CanvasScreen.kt:234-396` | Cards are activated by raw pointer input. No click or long-click semantics, so TalkBack and switch access cannot open, move or connect Steps. No hardware-key handling at all. | Accessibility and tablet or ChromeOS use. | Semantics actions on cards ("Open", "Connect to…", "Delete"); key map copied from n8n behaviour. |
| E29 | P2 | `CanvasViewModel.kt:116-121, 597-602`; `:816` | Undo covers steps, streams, notes, groups; depth 30; group moves, flow name, vars and settings are not undoable; each undo and redo shows a toast. | Surprise when undo skips a change. | Add group move snapshot; depth 100; drop the toast. |
| E30 | P2 | `Flow.kt:51-58` | Deleting a Step in the middle of a chain does not reconnect its neighbours. | Every delete needs two manual re-wires. | If exactly one in and one out, reconnect. |
| E31 | P2 | `CanvasViewModel.kt:905` | Toast id is `currentTimeMillis()`; two toasts in one millisecond share an id and are consumed together. Every add, undo, redo raises a toast. | Lost messages, noise. | Counter id; only toast on errors. |
| E32 | P2 | `Repositories.kt:318, 296, 266-273`; `FlowListScreen.kt:224` | Credential `createdAt` is overwritten on every save (rename resets the date); `usedInFlowCount` is hard-coded 0 (the `combine` with flows is unused), so deleting a used credential gives no warning and the Run fails later; every cold start deletes legacy `googleSheetsOAuth2` rows. | Misleading list, surprise failures. | Keep `createdAt`, compute usage from Flow params, remove or migrate the legacy sweep. |
| E33 | P2 | `FlowListScreen.kt:429-451` | Renaming a credential also rewrites a row `ai_provider_config`, a settings pointer stored in the credential table and filtered out by id (`:222`). | Fragile coupling. | Store AI config in its own table or preferences. |
| E34 | P2 | `FlowListScreen.kt:1129, 1437-1503, 348-359` | Variable delete has no confirm or undo; `saveVariable` ignores its `groupId` parameter. Flow delete is a hard delete (no archive). | Accidental loss. | Confirm or snackbar undo; archive. |
| E35 | P2 | `MainActivity.kt:61, 79-90` | `handleShare(intent)` runs in `onCreate` every time, including re-creation and process restore, which re-delivers the same share intent. A shared item can fire its Spark twice. | Duplicate Runs. | Mark intent handled (`intent.removeExtra` or a saved flag). |
| E36 | P2 | `MainActivity.kt:244-248` | `activity.requestPermissions(..., 4_101)` with no result callback; the permission screen has no way to learn the answer except re-checking later. | Stale permission state. | `rememberLauncherForActivityResult(RequestPermission)`. |
| E37 | P2 | `ReliabilityCenterScreen.kt:~120-124` | Diagnostics JSON is shared in `EXTRA_TEXT`. Large dumps can exceed the Binder limit and crash with `TransactionTooLargeException`. VM methods take a `Context` argument. | Crash on export. | Write to cache dir and share by `FileProvider` URI. |
| E38 | P2 | `AgentRepository.kt:39-41,86-95`; `AiSessionRepository.kt:56` | Repository constructors read every JSON file (and may write a starter agent) on the thread that first injects them, normally Main. `AgentRepository.kt:52` writes non-atomically while `AiSessionRepository.kt:73-79` uses a temp file. Starter agent text is also duplicated in `AgentBuilderViewModel.kt:68-95`. | Startup jank, possible corrupt file after a kill. | Lazy load on IO; temp-file plus rename everywhere. |
| E39 | P2 | `NodeCatalog.kt:187`; used at `CanvasViewModel.kt:75`, `CanvasScreen.kt:149`, `FlowTransfer.kt:79,141`, `AiModule.kt:30` | `registry()` constructs every executor (about 109) on each call. | Waste at screen open and on each import or export. | One `@Singleton` spec catalog (no executors) for UI use. |
| E40 | P2 | `CanvasViewModel.kt:922-1008` | `PromptToFlow` (87 lines) has no caller; it embeds fixed coordinates for one city (`:966`). | Dead, and wrong for other users. | Delete. |
| Q01 | P2 | see 4.1 | Exact duplicate groups. | Fix once, fix everywhere. | Extract. |
| Q02 | P2 | see 4.2 | Dead code. | Noise, false search hits. | Delete after one grep each. |
| Q03 | P2 | `build.gradle.kts:21, 29-31` | `-Xskip-metadata-version-check` and disabled AAR metadata check. | Hide version conflicts; possible runtime crashes. | Remove both, fix what breaks (task T14). |
| Q04 | P1 | `app/build.gradle.kts:11-13` | `compileSdk` and `targetSdk` 34 while the project ships a `play` flavour. Google Play's rule for new apps and updates requires a recent target level (35 since Aug 2025; the 2026 step is likely 36). Verify on the Play Console policy page. | Store upload can be refused. | Raise to current stable; re-test always-on and exact-alarm code paths. |
| Q05 | P2 | `libs.versions.toml` | Versions from late 2024 (Compose BOM 2024.10.01, AGP 8.3.2, Hilt 2.51.1, Room 2.6.1, Ktor 2.3.12). `security-crypto` is an alpha. | Missing fixes, harder upgrade later. | Upgrade in one dedicated branch with the device test. |
| Q06 | P2 | build files | Unused or duplicate deps: `libs.security-crypto` (core:data) has no import anywhere (CryptoBox uses Keystore directly); `quickjs-kt` and `biometric` are in the catalog and unused, and `proguard-rules.pro:37-42` keeps `app.cash.quickjs`; `ktor-client-okhttp` is declared in `app` and `core:network`, `app` has no Ktor import; `feature:canvas` declares `navigation-compose` and the serialization plugin with no use; `material-icons-extended` serves 3 files (`KaivoIcons.kt`, two AI sheets). | Bigger build, confusing graph. | Remove after a clean build. |
| Q07 | P2 | repo | No lint config, no static analysis, no CI, no `androidTest`; app declares `androidTestImplementation` for nothing. | Regressions reach the user's device. | Add Gradle `lint` baseline, detekt, GitHub Actions running `./gradlew test lint`. |
| Q08 | P2 | see 4.3 | Layering: AI agent loop and file persistence in a UI module; per-node display strings in UI; Room entity in settings UI; god ViewModel. | Hard to test and reuse. | Move, do not rewrite (4.3). |
| Q09 | P2 | `PublishSheet.kt:115`, `StepInspector.kt:126`, `InspectorPanes.kt:150`, `AgentBuilderScreen.kt:90`, `ReliabilityCenterScreen.kt:137-139` | 7 uses of `collectAsState()` where 47 others use `collectAsStateWithLifecycle()`. | Collects while stopped. | Switch. |

### 4.1 Exact duplicate groups (found by a line-window scan, then checked)

| Group | Locations | Note |
|---|---|---|
| D1 JSON string escaper `quote()` | `core/model/ParamJson.kt:74-85`, `feature/canvas/.../panels/JsonTree.kt:164-175`, `.../logic/TestInputLogic.kt:85-96` | Three JSON writers with the same escaper (one differs only on two exotic characters). Keep `ParamJson`. |
| D2 Card vs note drag gesture | `CanvasScreen.kt:283-327` and `:329-372` | About 45 lines identical but the callbacks. Extract `dragOrTap(...)`. |
| D3 Create and edit group dialog | `CanvasOverlays.kt:701-752` and `:754-800` (body `:716-731` equals `:769-784`) | One dialog with a title parameter. |
| D4 Search bar plus action row | `OverviewTabs.kt:124-135`, `:684-695`, `AgentsDashboardView.kt:98-109` | Same Row and field; extract `SearchActionBar`. |
| D5 Alert dialog cancel button and card chrome | `FlowListScreen.kt:1031-1039` = `:1604-1612`; `OverviewTabs.kt:1220-1228` = `FlowListScreen.kt:1462-1470`; `OverviewTabs.kt:316-325` = `:842-851` | Extract `KaivoDialogButtons` and `CardSurface`. |
| D6 Relative time formatting | `OverviewTabs.kt:79-92` (`formatRelativeTime`) and `FlowListScreen.kt:1988-1995` (`relativeTime`) | Two copies with different wording. |
| D7 Two `AgentsDashboardView` and two agent card composables | public `AgentsDashboardView.kt:75` + `AgentCardItem :209`; private `FlowListScreen.kt:2031` + `AgentCard :2146`. The call at `FlowListScreen.kt:763` binds to the public one. | The private pair (about 230 lines) is dead (4.2). |
| D8 Reduced-motion probe | `KaivoTheme.kt:35-37` and `StepPickerSheet.kt:199-203` | The picker should read `KaivoTheme.motionPolicy`. |
| D9 `Modifier.androidClickable` | `CanvasScreen.kt:993`, `SettingsScreen.kt:188` | Identical private helpers over `clickable`. |
| D10 Bundle id remap and structure strip | `NativeFlowBundle.kt:164-174, 354-362` and `FlowStructureBundle.kt:67-77, 115-123` | Same graph validation and fresh-id code; share one helper. |
| D11 Poll and connector node bodies | `PollChangesNode.kt:39-51` = `ServiceConnectorNode.kt:43-55`; `SlackNode.kt:40-48` = `DiscordNode.kt:37-45` | Shared HTTP step helper. |
| D12 Starter agent definition | `AgentRepository.kt:99-135` and `AgentBuilderViewModel.kt:68-95`; serialize helpers at `AgentRepository.kt:107-126` = `AgentBuilderViewModel.kt:74-93` | One source for the template. |
| D13 Dock buttons | `CanvasOverlays.kt:271-282` = `:328-339` | One `DockButton`. |
| D14 Duplicated AI screen blocks | `AiSettingsSheet.kt:944-953` = `:985-994`; `AgentBuilderScreen.kt:941-950` = `:1116-1125`, `:1759-1768` = `:1805-1814`; `AiAssistantScreen.kt:142-155` = `:211-224` | Local repetition, extract per file. |
| D15 `isExpression`, `toFixed` helpers | `core/model/ExprFieldCodec.kt:39,60,72`, `inspector/params/ParamModes.kt:27,45` | Same concept in model and UI. |

### 4.2 Dead code (declaration is the only occurrence in the repo)

Canvas and flows UI: `PromptToFlow` object (`CanvasViewModel.kt:922`), `requestActivateToggle` (`:678`), `toggleFlowActive` (`:694`), `setVar` (`:468`), `selectStep` (`:239`), `toggleLayoutMode` (`:445`, no button), `openCreateGroupDialog` (`:717`), `createGroup` (`:768`), unused param `onOpenRunHistory` (`CanvasScreen.kt:106`), `onSearch = {}` (`:859`), `StepSummaries.needsAttention` and `withRunStatus` (`CanvasUiState.kt:185,192`), `Minimap` (`CanvasOverlays.kt:188`), `EmptyCanvasHint` (`:528`), `CreateGroupDialog` (`:754`), `rememberFitToScreen` (`CanvasTransform.kt:107`), `StepCardSkeleton` (`JewelStepCard.kt:420`), `debugDrawFrame` (`StreamCanvasLayer.kt:355`), `filteredFlows` (`FlowListScreen.kt:583`), `GroupFilterChip` (`:910`), `selectGroup` (`:279`), `openMoveFlowDialog` (`:307`), `openVariablesDialog` (`:290`), the private `AgentsDashboardView` and `AgentCard` (`:2031-2257`), `KaivoNavHost(sharedText)` (`MainActivity.kt:113`), an unused `val context` (`:188`).
Design tokens never read: `portDot`, `guidedStepGap`, `snapGrid`, `streamStroke`, `liveOrb`, `springSnappy`, `springLuxurious`, `stagerStepMs`, `goldenOrbDurationMs`, `breathingDurationMs`, `KaivoTheme.radii`, several weight tokens (`KaivoTokens.kt:50-123`). Components: `ExpressionChip`, `TypeBadge`, `SectionLabel` (`core/design`). `LocalMotionController` has no consumer, so there is no way to set motion level.
Other: `Flow.stepByName`, `ScheduleMath.nextOccurrence`, `FlowEngine.runById`, `FlowScheduler.dispatchSimple`, `describeSchedule`, `ActivationManager.pauseAll`, `WebhookEngine.setLanEnabled/setPort/regenerateTokens`.
(Hilt-provided functions, Android components and receivers also show once; they are not dead.)

### 4.3 Layering and wiring
- AI backend lives in the UI module: `feature/flows/ai/AiWorkflowAgent.kt` (357 lines, tool loop), `AiAssistantViewModel.kt` (713), `FileAgentRepository`, `FileAiSessionRepository` (JSON in `filesDir` through `org.json`), `ModelDiscoveryService`. They work, but a Room-or-file store and an agent loop should sit in `core:ai` or `core:data` so the engine and Always-on service can reuse them. Antigravity's code works; propose moving, not rewriting.
- `StepSummaries.summarize` (`CanvasUiState.kt:150-181`) hard-codes strings for 18 node ids in the UI. A node's own spec should provide its summary template, or hundreds of nodes will not scale.
- `CanvasViewModel` does graph surgery (insert, relink, delete, group move) inline; none of it has a unit test (E04, E17 live here). Move to a pure `FlowEditor` object in `core:model` or `feature:canvas/logic` and test it.
- `FlowListViewModel` (`FlowListScreen.kt:129-527`) owns Flows, Groups, Variables, Credentials, Data tables, Agents, Runs in one class with about 25 StateFlows. Split by tab.
- `feature:settings` shows a Room entity (`ReliabilityCenterScreen.kt:52,97`); expose a plain model from `ReliabilityCenterRepository`.
- UI modules depend on `core:data` and `core:work` directly (canvas build file lines for both). Acceptable for MVVM with Hilt, but `feature:canvas` pulling `core:work` just for `FlowEngine` and `FlowScheduler` suggests an interface in `core:engine`.
- Hilt wiring itself is sound. Extra spots: `KaivoExpressionAssist()` and `KaivoExpressionEvaluator()` are constructed in the VM (`CanvasViewModel.kt:84-85`) rather than injected, which blocks test fakes. `FlowListViewModel.init` calls `rescheduleAll` (`:268`) although `KaivoApplication.kt:44` already does.
- Many long-lived `CoroutineScope(...)` objects (22 sites, e.g. `Repositories.kt:267`, `MainActivity.kt:56`, `FlowScheduler.kt:36`) with no owner to cancel them. Mostly fine for singletons; `EncryptedCredentialRepository.init` (`:267`) and the receivers (`Receivers.kt:32,96`, `DeviceReceivers.kt:28,64`) launch detached work with no `goAsync` handling shown in the grep. Not verified further (core:work is another auditor's area).

### 4.4 Compose recomposition and state notes
- Good: immutable UI models, `@Immutable`, immutable collections, per-card culling, cached stream geometry, infinite animation only on running cards, `derive` of live streams under `remember`.
- Watch: `CanvasScreen` reads the whole `CanvasUiState`, so any toast, run tick or selection change re-runs the body (cards skip thanks to strong skipping; the stream joints and group frames do not skip, E14).
- `CanvasUiState.vars: ImmutableMap<String, Any?>` and `Pair<Float,Float>` positions are stable only by annotation; values of type `Any?` compare by equals and can be large. Prefer a revision counter for the vars map.
- `System.currentTimeMillis()` as a default parameter (`DataPanel.kt:57`) and in a getter read during composition (`CanvasUiState.kt:84-85`) gives results that never refresh without a state change.
- `remember(totalDp, inputOpen, outputOpen)` in `ThreePaneBody` (`StepInspector.kt:350`) resets pane widths on rotation or tablet resize; pane widths should be saved per device.
- `systemReduceMotion` and `powerSave` are computed once in `remember(context)` (`KaivoTheme.kt:33-42`), so toggling battery saver while the app is open has no effect until restart.

## 5. Gap vs n8n

| n8n capability | KaivoFlow status | Effort | Value |
|---|---|---|---|
| Typed canvas, node cards, connections | exact (vertical guided + freeform; phone-appropriate) | n/a | high |
| Multi-select, rubber band, select all | missing | M | high (every power user) |
| Copy, cut, paste, duplicate with streams, cross-flow paste via clipboard JSON | missing (duplicate one Step only, `CanvasViewModel.kt:345`) | M | high; also enables sharing Flows in chat apps |
| Command-based undo (100) with bulk | partial (snapshot, 30, E29) | S | medium |
| Delete reconnects neighbours, drop on stream inserts | missing (insert after exists, buggy E04) | S | medium |
| Keyboard shortcuts | missing | S | medium (tablets, ChromeOS, accessibility) |
| Tidy-up auto layout | partial (Guided mode is a live layout; no one-shot tidy for Freeform) | S | medium |
| Sticky notes (resize, colour, markdown) | partial (colour, edit, drag; no resize, no markdown) | S | low |
| Node groups | partial (frames exist, cannot create, E26) | S | low |
| Node search on canvas, find and replace | missing | M | medium |
| Step picker with categories and search | exact, stronger (fuzzy, recents, favourites, drag) | n/a | high |
| NDV three panes, input parent picker | exact (3 layouts; input parent picker not verified) | n/a | high |
| Table, JSON, Schema, Binary views, paging | exact; stronger (DB paging, search) | n/a | high |
| Drag-to-map, expression preview | exact | n/a | high |
| Execute step only that node | exact (user's standing rule met) | n/a | high |
| Partial execution to a destination node ("run to here") | missing | M | high |
| Dirty or stale markers after edits | missing | M | medium |
| Pin data | exact in inspector; no canvas badge (E13), not in backup (E12) | S | high |
| Node settings: retry, on error, timeout, notes | exact | n/a | high |
| Execute once, always output data | missing (`StepNode` has no fields, `Flow.kt:139-153`) | M (model plus engine) | medium |
| Sub-node wiring for Agent (model, memory, tools) | partial (JSON slots, E09) | M | high (AI is the business story) |
| Error view with HTTP status and body, stack | partial (data stored, not shown, 3.2) | S | medium |
| Executions list: filters, retry, stop, delete, bulk, annotate | partial (3 filters, no actions, E22) | M | high |
| Execution preview as canvas replay | partial (read-only inspector per Step; no canvas overlay) | M | high |
| Debug in editor | partial ("Test from here" approximates; `CanvasViewModel.kt:267-271` documents the gap) | M | high |
| Logs panel (tree of node runs) | missing | M | medium |
| Workflow history, diff, restore | missing in UI (`flow_versions` table exists, used by publish only) | M | medium |
| Templates library with credential setup | missing | L | very high (acquisition) |
| Import from file, clipboard, URL, cURL | partial (file only, own formats; no n8n JSON, no cURL) | M | high |
| Extract nodes into sub-flow | missing | L | low now, high later |
| Workflow settings (timezone, error flow, timeout, save runs) | missing UI, model exists (E07) | S | high |
| Tags, folders, projects, favourites, search, sort in list | partial (favourite, groups half-wired; no tags UI; no search, E08) | M | high |
| Archive instead of delete | missing | S | medium |
| Dark and light theme | partial (light built, not selectable) | S | medium |
| Accessibility (screen reader on canvas) | partial | M | medium |

## 6. Where KaivoFlow can beat n8n

1. Phone-first debugging. A failed Run notification opens the failed Step with its input and error already loaded and a one-tap "Fix and retest" (n8n needs a desktop). Value: this is the daily loop for a phone automation owner.
2. Share a Flow as a link or QR. The `.kaivoflow` format already redacts secrets and swaps ids (`NativeFlowBundle.kt:249-277`). Add Android share sheet, "Open with KaivoFlow" intent filter and QR. n8n needs copy and paste of JSON on a desktop.
3. n8n import and a cURL importer. Convert pasted n8n JSON for supported nodes and mark the rest as Unavailable Steps (the placeholder mechanism is in `StepAliases.kt`). This turns every n8n tutorial and template online into KaivoFlow content.
4. Template gallery built from Android-only automations (bank SMS to sheet, geofence home or work, notification filter to Telegram, share-to-Notion, morning brief). n8n templates cannot do these; this is the clearest "worth money" feature.
5. Pins and test data from the device. "Pin from clipboard", "pin from last notification", "pin from camera/QR" make test data one tap. Desktop n8n cannot.
6. Haptic and sound feedback on connect, success, failure already exists in the design system (`Haptics.kt`); extend to a Run summary "pulse" so the owner feels a failure in the pocket.
7. Home-screen widget and quick-settings tile for "Run this Flow" and "Pause all", plus a lock-screen notification action with the last Run result. Serverless advantage: it works offline.
8. Offline-first data: the paged DB-backed data panels already handle 5,000 items without memory pressure (`FlowSettings.maxItemsPerStep`, `Flow.kt:257`). Market it as "large data without a server".
9. Voice and text "describe a change" on a selected Step: the Ask AI assistant exists; scope it to the selection (n8n has Focus AI only in 2.x cloud).
10. Per-Step cost and battery meter (Agent already tracks tokens and cost, `AgentNode.kt` params). Show "this Flow costs about X per month and Y percent battery". n8n has no battery.

## 7. Recommended ordered tasks

Each task is small. Acceptance test describes what a reviewer does or which test must pass.

| # | Task | Acceptance test |
|---|---|---|
| T01 | Flush the pending save on VM clear and on `ON_STOP` (E01). | Unit test: edit, call `onCleared`, repository holds the edit. Device: edit then press back in under 300 ms, reopen, edit present. |
| T02 | Ignore database echoes older than local state (E02). | Unit test with a fake repository emitting a stale Flow after an edit; `flow` keeps the edit. Device: type fast with pauses, no lost characters. |
| T03 | Stop persisting merged variables (E03). | Unit test: load a Flow with a global variable, edit a step, saved Flow JSON has no global key; change the variable, manual Run sees the new value. |
| T04 | Fix insert-after relink (E04) and move graph edits to a pure `FlowEditor` with tests. | Tests: insert after If port 1 leaves port 0 edges intact; moved edges use port 0. |
| T05 | Rename guard and reference rewrite (E05). | Test: rename to an existing name is refused; `$step("A")` becomes `$step("B")` in all downstream params. |
| T06 | Publish button in the canvas top bar; delete `toggleFlowActive` (E06). | Device: tap Publish in the editor, sheet opens, Flow goes Live; list shows Live. |
| T07 | Flow settings sheet (E07): error Step picker, timezone, timeout, save-runs. | Test: choose error Step, a failing Step routes to it in a Run; settings persist. |
| T08 | Flow list search, sort, filter, group chips, Move to (E08). | Device with 40 Flows: type 3 letters, list narrows in under 100 ms; group chip filters; move works. |
| T09 | Delete cleanup (E10): step data, pins, Runs. | Test: after delete, `step_executions`, `step_pins`, `runs` have no rows for the id. |
| T10 | Edge-to-edge dark style (E11). | Device in system light mode: status icons light and readable. |
| T11 | Canvas pin badge from `PinStore`; stop using `frozenOutput` (E13). | Pin a Step in the inspector, card shows a pin; Run shows it replayed. |
| T12 | Reset step marks at Run start (E15); fix tap slop (E16); key `pointerInput` on `Unit` (E18). | Tests for status reset; device: 20 slow taps all open the inspector; pinch works during a Run. |
| T13 | Agent slot widgets (E09): model picker, tools toggles. | Build an Agent without typing JSON; Test Step runs it. |
| T14 | Remove `-Xskip-metadata-version-check` and the AAR check disable; list what breaks (Q03). | Clean build passes without both flags, or a written list of the blocking dependency. |
| T15 | Raise target and compile SDK; check Play requirement (Q04). | `./gradlew :app:assemblePlayRelease` passes; Play policy page cited in the PR. |
| T16 | Pins in backup export and import (E12). | Round trip: back up a Flow with a pin, import, pin present. |
| T17 | Execution list upgrade: filters (running, waiting, canceled, date), delete, stop, retry, tappable global history, 200 Run window with paging (E22). | Device: delete a Run, filter by date, tap a global row opens that Flow's Run. |
| T18 | Multi-select, copy, paste (clipboard JSON), duplicate with streams, delete-reconnect (E30). | Select 5 Steps, copy, paste in another Flow, streams between them preserved; one undo reverts the paste. |
| T19 | "Run to here" (partial execution to a destination Step) and stale markers. | Pick a Step, Run to here runs only its ancestors; edit a parent param, downstream Steps show Stale. |
| T20 | Template gallery (10 Android-native templates) with a credential setup walk-through. | Install a template, finish the credential step, Run succeeds. |
| T21 | n8n JSON importer for the supported node subset. | Import 3 sample n8n files; supported Steps appear, others show as Unavailable. |
| T22 | Delete dead code (4.2) and merge duplicates D1, D6, D7, D9. | Build and tests green; grep finds no leftover references. |
| T23 | Performance pass on canvas (E14, E19, E20): joints only on selection, validation debounce, `flowOn`. | Benchmark: 100 and 500 Step Flow pan stays above 55 fps and typing latency under 50 ms on a mid-range device. |
| T24 | CI: `./gradlew test lint` on push, detekt baseline, VM unit tests for `CanvasViewModel`, `FlowListViewModel` (Q07). | CI green; coverage of the two ViewModels includes the new editor tests. |
| T25 | Accessibility: 48 dp targets, semantics actions on cards, hardware key map (E27, E28). | TalkBack can open, delete and connect a Step; Tab and Enter work on a keyboard. |

## 8. Not verified / needs device test

- E02 (database echo overwrite), E01 (lost edit on fast back), E11 (status bar icons in light mode), E14 and E19 (jank at 100 or 500 Steps), E25 (page switch feel), E16 (tap slop on real fingers). Found by reading; timing and visual effects need a device.
- Whether Compose strong skipping actually keeps `JewelStepCard` from recomposing on pan (expected from Kotlin 2.0.21 defaults, not measured).
- The inspector input parent picker for multi-parent Steps (`InputPane` was not read in full).
- Whether the Run record or Reliability code cleans step data on `recoverAndPrune` for Flows deleted earlier (only the DAO methods were checked, E10).
- `core:work` receivers and `goAsync` use (4.3), Always-on service behaviour, and permissions flow after `requestPermissions` (E36): other auditors' areas.
- Play policy target-level numbers (Q04): taken from memory of the policy cadence; confirm on the Play Console help page.
- Dependency "latest" versions (Q05): not checked online; only that they are old relative to the dates in the file.
- Visual quality of animations and haptics: not judged from code.
- n8n details were read from source for behaviour; numbers such as page size 25, 12 MB pin and 16 MB workflow caps come from `/tmp/ref/n8n` files named in section 2.
