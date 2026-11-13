# Milestone 02 — structure transfer and AI output checks

13 November 2026. This milestone implements a bounded portability slice and adds AI validation. **The project remains a prototype, not a production release.**

## What was added

### Flow structure import/export

- `FlowStructureBundle` defines the versioned `kaivoflow.structure` JSON format. It is **not a full Flow backup**: it exports the graph, Step types, display names, positions, Stream endpoints/ports and labels. Step settings, credential references and secrets in settings, variables, notes, tags, frozen output, retry configuration and execution history are deliberately omitted. Flow/Step names, types and Stream labels remain and may contain private data. Users must inspect before sharing.
- Import accepts only this native format, not n8n JSON. It bounds the file to 2 MiB, validates UTF-8 and nesting, limits graph size, rejects malformed graphs, duplicate IDs, missing endpoints, invalid ports and cycles, generates fresh Flow/Step/Stream IDs, remaps the error Step, and lists unknown Step types for review.
- Imported Steps are **all disabled**, including every Spark; settings are stripped again regardless of what the file claims. A preview performs no database write, network request or execution. A user explicitly confirms before the single Flow is saved. The user must manually configure and enable the Steps. No file permission beyond Android's system file picker is required.
- New `FlowTransferHost` adds an import action, an export action on each Flow, and review/confirmation dialogs using existing design components and system file pickers. Display text is in module string resources and new spacing uses design tokens. These Android/Compose/Hilt paths are **not compiled or tested on a device** in this environment. One previous `onOpenPrompt` callback type mismatch was also corrected by creating a Flow before navigation; repository Hilt bindings now use explicit providers. Both changes remain Android-unverified.
- `prototype/kaivoflow.html` includes concept previews for import/export review. This HTML is a visual mockup, not the Android implementation or a real file transfer.

### AI extraction

- The extraction Step now checks a deliberately **shallow field-type contract**, not full JSON Schema. Supported types: `string`, `number`, `integer`, `boolean`, `object` and `array`. A named key must be present; null is permitted for unavailable values. Unexpected keys and wrong types fail the Step instead of silently entering downstream actions. Invalid schemas fail before the provider request. The contract checks format, **not truthfulness, prompt-injection resistance or model reliability**.
- The shared Android-free JSON parser now rejects incomplete objects, invalid numbers/escapes, duplicate keys, trailing content and excessive nesting; input is limited to one million characters. It still represents both a top-level JSON `null` and parse failure as Kotlin `null`; this is a known limitation.

## Tests

On JDK 11, Kotlin 2.1.20, JUnit 4.13.2, in this workspace:

| Suite | Result |
|---|---:|
| Model + engine + nodes, including AI checks and trigger reliability | 104 JVM tests passed |
| JSON codec + structure transfer, including malformed/adversarial cases | 21 JVM tests passed |
| ScheduleMath | 13 JVM tests passed |
| Canvas geometry | 11 JVM tests passed |
| Fallback expression evaluator | 7 JVM tests passed |
| JS expression prelude | 34 assertions passed |

Total: **156 JVM test executions and 34 JS assertions**, run in separate suites. The AI tests use fake HTTP providers; none call real models. Reproducible JVM scripts: `scripts/run-core-reliability-tests.sh` and `scripts/run-transfer-tests.sh` (set `KOTLINC_HOME` and `TEST_DEPS_DIR` as described in the scripts). Gradle/Android, real file providers, accessibility, performance and release builds have not been tested.

## Important limitations and remaining work

- An export intentionally loses settings: no credential migration, full backups, n8n importer, copy/paste, images/files or cloud sync. A production full-backup design needs encryption, authentication, recovery, explicit consent and review. Never assume arbitrary names or labels are secret-free.
- Android SAF and UI integration, Hilt code generation and Room save must be compiled and tested with real file providers, cancellation, screen rotation, corrupted streams and TalkBack. No claim of Android release readiness is made.
- Scheduling, webhook service, durable event queue, WorkManager payload limits, cross-process locking, restricted Play permissions, secure JS isolation, sensitive-data storage, OAuth and real-provider integration remain blockers from `PRODUCTION_GAP_REVIEW.md` and `MILESTONE_01_RELIABILITY.md`. Daily/weekly/monthly scheduling is not yet recurring. Do not use sensitive or production data.
- AI extraction validation does not cover nested field schemas, hallucinations, semantic correctness, safety policies, model cost controls, multi-turn agents, MCP, tool approvals or live provider contracts.
- The required next foundation is a reproducible Android SDK/Gradle build and CI. After fixing compile errors and running instrumentation tests, implement durable scheduling/events, complete secure backup/import, then broader connectors and AI agents with explicit approval controls.
