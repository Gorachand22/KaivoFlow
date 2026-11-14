# KaivoFlow — Capability Gap Analysis

> **Outcome of a read-only external-reference audit, not a port.**
> KaivoFlow remains an independent, author-owned product. No code, descriptions, icons, schemas or JSON were copied. The reference name is intentionally absent from source and documentation; capability patterns were translated into original phone-first Kotlin/Compose designs.

---

## 1. Audit method
* Performed a **shallow, blobless, sparse** read of a large external automation catalog on its default branch.
* Enumerated file trees only; a handful of small files were read to understand composition and webhook patterns.
* Deleted the temporary `.git` clone immediately. Workspace stayed under 3 MB; lightweight notes remained outside the repo in `/tmp`.
* No build tools, dependencies or reference packages were installed.

## 2. Scale observed
| Surface | Observed size |
|---|---|
| Tracked files in the reference tree | ~34,861 |
| Top-level integration folders | 308 |
| Node / trigger implementation files | 564 |
| Credential definition files | 560 |
| Frontend, engine, CLI, AI/agent and platform packages | dozens more |

A literal one-to-one port is neither legally appropriate nor technically sensible for a sovereign phone app. Most of those 308 integrations are thin wrappers around a single hosted API; many require server SDKs, OAuth app registration, a cloud account, multi-user roles, queues, a database, Docker or desktop-only runtime features.

## 3. Portable patterns extracted
1. **Composition:** reusable child workflow, error workflow, sub-workflow input mapping.
2. **Webhook lifecycle:** incoming trigger, custom response, wait/resume, form response.
3. **Polling:** interval triggers, dedupe of already-seen items, persisted checkpoints.
4. **Core data ops:** set, item lists, merge, compare datasets, split in batches, if, switch, code, no-op, stop/error.
5. **Connectors:** generic HTTP/REST, auth styles, response extraction, pagination, retries, binary response.
6. **Engine:** active runs, cancellation, retry/backoff, partial/manual execution, pinned data, paired items, execution replay.
7. **Credentials:** API key, Bearer, Basic, header, OAuth2 refresh, encrypted vault.
8. **AI/agents:** multi-provider chat, tool calling, workflow builder, local task runners.
9. **Editor:** canvas, templates, folders, tags, version history, collaboration, permissions.
10. **Platform:** expression sandbox, code runtime, auth redaction, data tables, scheduling, queue, concurrency.

## 4. Implemented in KaivoFlow before this audit
* 84 original executors across Sparks, Logic, Transform, AI, SaaS/protocol and Device families.
* Binary attachments, multipart/base64 HTTP transport, wrapped/legacy codec.
* `.kaivoflow` full backup with secret redaction, ID remap and disabled imported Sparks.
* Geofence, Bluetooth, Wi-Fi, headset and boot Sparks with fail-closed matchers.
* Encrypted connected-account OAuth2 refresh-token manager.
* Per-output port Stream gestures, success haptics, density-correct dp↔px conversion.
* 48 dp touch targets and status/navigation/IME insets.
* Strict pure-JVM parsers/formatters with size/depth caps.

## 5. Implemented from this audit
| Pattern | Original KaivoFlow implementation |
|---|---|
| Reusable child workflow | `logic.subflow` + `FlowInvoker` + `RunTrigger(kind="subflow")`, depth cap 8, nested Runs not persisted as top-level history |
| Generic API connector | `saas.connector` with credential auth, dot-path response extraction, bounded pagination, retries, timeouts, per-item expressions |
| Polling change detection | `logic.poll` with ID/hash dedupe, bounded private local state file, Reset support |
| Starter workflows | `FlowTemplates` with five editable original templates and a Flow List picker; fresh Step/Stream IDs per instantiation |
| Test coverage | `ExtendedCompositionTest` and `FlowTemplatesTest`, wired into `scripts/run-core-reliability-tests.sh` |

Catalog is now **87 executors**: 12 Sparks, 17 Logic, 25 Transform, 3 AI, 12 SaaS/protocol, 18 Device.

## 6. Still open (prioritised, not copied)
1. Dynamic **webhook response** and wait-for-webhook resume on the existing local HTTP/1.1 server.
2. **Global error Flow** setting that invokes another Flow when a Run fails.
3. **OAuth2 authorization-code** Custom Tabs receiver on top of the refresh-token manager.
4. **AI agent tool calling** and local vector memory on top of existing AI Steps.
5. **Execution replay / visual debugger** for skipped, retried and frozen branches.
6. **Undo/redo** history in the Canvas ViewModel (the immutable `Flow` model already supports it).
7. **Workflow version history**, folders and richer template management.
8. **Storage Access Framework** document-picker UI for binary attachment selection.
9. Automated Compose screenshot suite at 1.3x / 2.0x font scale.
10. 300+ integration-specific presets: only add those a phone user actually needs, and implement each as an original preset over `saas.connector`/`saas.http`, never as copied integration code.

## 7. Explicit non-goals for a phone-first sovereign app
* Multi-tenant RBAC, SSO, project sharing, CRDT collaboration and server-side queues.
* Docker/VM deployments, central execution workers, remote task runners or community-package runtimes.
* Hosted database entities, cloud blob storage and telemetry pipelines.
* Desktop-only editor features that do not improve one-handed mobile automation.

---

## 8. Verification boundary
All of the above is **static-checked source only**. No Kotlin compiler, Gradle, Android SDK, APK or physical-device run happened in the cloud IDE. The local engineer must compile, run the seven JVM suites and follow the 19-step checklist in `HANDOFF.md`.
