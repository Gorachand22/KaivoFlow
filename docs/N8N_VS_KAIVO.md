# KaivoFlow vs n8n 2.48.6: master audit and roadmap

Reference: n8n 2.48.6 (read for behaviour only; KaivoFlow code is original). Base: origin/main (Antigravity merge, Nov 20 2026).
Every claim has a file:line pointer in the detail docs `docs/audit-v2/01..08`. Those docs are the source of truth; this page is the map.

## 1. Result in one table

| # | Area | Detail doc | P0 | P1 | Verdict |
|---|---|---|---|---|---|
| 1 | Engine and logic | 01-engine-and-logic.md | 0 | 14 | Good skeleton, wrong semantics in If/Switch/Filter, error handling and retry unsafe |
| 2 | Database and storage | 02-database-and-storage.md | 1 | 17 | Variables freeze into Flows, no backup, history never trimmed |
| 3 | Credentials and security | 03-credentials-and-security.md | 1 | 10 | Credential field names do not match nodes, no SSRF guard in HTTP gateway |
| 4 | Expressions and mapping | 04-expressions-and-data-mapping.md | 1 | 8 | UI-made expressions may not resolve at run time (confirm first) |
| 5 | Ask AI, Agents, connectors | 05-ai-assistant-agents-connectors.md | 1 | 7 | Great UI, fake "publish 24/7", staged streaming, inert Skills/Connectors sheet |
| 6 | Core nodes | 06-core-nodes.md | 2 | 9 | Code Step cannot run scripts, If/Filter UI may be unwired |
| 7 | Triggers, device, integrations | 07-triggers-device-integrations.md | 1 | 15 | Webhook unusable end to end, test samples differ from live payloads |
| 8 | Canvas UX and code quality | 08-editor-ux-and-code-quality.md | 0 | 14 | Last edit lost on fast exit, insert-after breaks branches, cannot publish from canvas |

Totals: 7 P0, 94 P1. Note on audit 8: its line numbers were not re-checked by a second pass (spot check of the 450 ms save debounce confirmed at CanvasViewModel.kt:663).

## 2. The 7 P0s (fix first, they break trust)

1. DB-01: after any canvas edit, global variables are copied into the Flow and win over later changes (CanvasViewModel.kt:131-158).
2. I-01: Telegram, Bearer and Header-auth credentials save field names the nodes never read (`botToken` vs `accessToken` etc). The credential looks saved and does nothing.
3. E-01 (expressions): inspector saves `{"kx":..}`; run-time resolvers read only the legacy `{{` form. Confirm with test T1, then unify.
4. AI-01: agent "Publish (Run 24/7)" only flips a flag in a JSON file.
5. CN-01: Code Step feeds statement scripts to an expression-only interpreter (QuickJS sandbox missing).
6. CN-02: If and Filter UI saves rows the executor does not read (confirm on device).
7. T-01: Webhook Spark has no usable URL, token UI or public path.

## 3. Themes behind the 94 P1s

- **Item semantics.** If, Switch, Filter use item 0 scope for every item (E-04, expressions E-02). Right-hand value never resolved (E-05, CN-03).
- **Error and retry.** Error output unreachable from the UI (E-01), retry re-runs the whole Step (E-03), error Step runs outside the normal path (E-10), killed Runs recorded as Canceled (E-09).
- **Data safety.** No backup (DB-06), edit lost on exit (DB-03, E01), DB echo reverts edits (DB-02), non-atomic file writes (DB-15, DB-17), dedupe/poll state saved before the Run succeeds (CN-04, T-12).
- **Security.** No SSRF guard on HTTP (I-02), redirects always followed (I-03), plain-text inline secrets (I-04), unredacted errors (I-05, I-06), cleartext blocked by targetSdk 34 (I-07), SMS without caps (I-08), targetSdk blocks Play (I-11).
- **Permissions.** No in-app request path for location, Bluetooth, contacts, calendar, SEND_SMS (I-09, T-09, T-13).
- **Fake or half-wired UI.** Agent publish, streaming steps, Skills/Connectors/Preferences, attach files (AI-01..04), Flow list groups, publish from canvas, error Step picker (E06..E08).
- **Test vs live.** Samples differ from live payloads (T-02, T-03); frozen output replays in production (E-14).
- **Performance and cleanup.** Run cap never applied (DB-07), pruning scans (DB-08), delete Flow leaves data (DB-09), SELECT * with full JSON (DB-12), eager scope building (expressions E-08).

## 4. What n8n has that KaivoFlow lacks (big items)

| n8n capability | KaivoFlow | Effort | Money value |
|---|---|---|---|
| Real Loop Over Items with cycle (loop/done) | batch tagger only | M | High |
| Sub-workflow (Execute Workflow + trigger + inputs mapping) | exists but runs draft, no input mapping UI | M | High |
| Wait for time/date/webhook, survives restart | in-process delay only | M | High |
| Error workflow and per-Step continue-on-fail | not reachable from UI | M | High |
| Code node with JS sandbox | broken | M | High |
| HTTP Request depth (pagination, auth variants, binary, retries) | partial | M | High |
| 300+ integrations via declarative specs, OAuth2 | about 10 hand-coded, no OAuth2 PKCE flow | L | Very high |
| Webhook with public URL | local only | L | High |
| AI sub-nodes (model, memory, tools, parser) wired on canvas, tools incl. Flow-as-tool, MCP client | slots as raw JSON, no Flow-as-tool, no MCP | L | Very high |
| AI workflow builder with validation | Ask AI works, but fake stream, no deep validation loop | M | High |
| Real-browser connector (extension plus relay) | card only, fake "Connected" | L | Very high |
| Workflow versions/history, import/export, templates | partial, backup missing | M | Medium |
| Data transformation functions library | partial | M | Medium |
| Merge modes (join by field, append, position), Compare, Summarize | partial | M | Medium |
| Evaluations, folders/tags, projects | missing | L | Low (single user) |

## 5. Where KaivoFlow can win

Phone-native triggers and actions (notifications, SMS, battery, Wi-Fi, geofence, share sheet), no server or VPS, on-device model option, Accessibility-based "no-API" automation (sideload flavour), approval cards on the phone itself, Always-On engine with a Reliability Center, premium motion and design. Business pitch: "automation that lives in your pocket and uses your own accounts safely".

## 6. Browser and connector options on Android (from audit 05, section 9)

Recommended order: (1) remote MCP client (HTTP/SSE) for immediate browser-use and any MCP tool; (2) WebView page reader, click/type behind approval and per-host allow-list; (3) desktop relay to the user's real browser (n8n-style extension plus tiny bridge); (4) Accessibility module only in sideload flavour, off by default; (5) Custom Tabs for OAuth logins; (6) phone as MCP server so desktop agents can call Flows.

## 7. Proposed phase order (user decides)

| Phase | Name | Content | Why this order |
|---|---|---|---|
| 5 | Trust fixes | The 7 P0s, error output UI, item scope for If/Switch/Filter, atomic saves, no-edit-loss, variables fix, backup/export, SSRF guard, redaction, network security config, targetSdk 35, permission screens, test/live payload parity | App must not lie or lose data before anything new |
| 6 | Engine depth | Real Loop, Wait that survives restart, Sub-Flow published revision plus inputs, error workflow, per-item retry, Code Step sandbox, HTTP depth, Merge/Compare/CSV fixes | Makes core automation equal to n8n |
| 7 | AI made real | Agent publish through engine, true streaming, Flow-as-tool, sub-node pickers on canvas, memory keys, self-healing parser, force tool choice, wire Skills/Preferences, attach files | AI is the headline feature |
| 8 | Connectors and browser | MCP client, WebView reader, desktop relay, phone as MCP server, webhook public path via relay | Biggest differentiator |
| 9 | Integrations at scale | Declarative spec engine, OAuth2 PKCE, top 30 integrations | Growth and revenue |
| 10 | Android superpowers and polish | Accessibility module, on-device models, hardware triggers, store readiness | The moat |

Each phase starts with a short plan for the user's approval, a deep n8n study by dedicated agents written into the docs first, and no rewrite of working Antigravity code without asking.

## 8. n8n areas still to study deeply before each phase

- Phase 5/6: packages/core execution engine, workflow-execute, wait/resume, error workflows; nodes-base: If, Switch, Merge, Loop, Code, HTTP Request.
- Phase 7: @n8n/agents, @n8n/instance-ai, @n8n/ai-workflow-builder.ee, @n8n/chat-hub, @n8n/nodes-langchain.
- Phase 8: @n8n/mcp-browser, @n8n/mcp-browser-extension, MCP client/trigger nodes.
- Phase 9: declarative node style in nodes-base, @n8n/client-oauth2, credential types.
- Phase 10: n/a (Android-native, no n8n equivalent).
