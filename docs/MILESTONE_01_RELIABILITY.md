# Milestone 01 — execution and event safety foundation

13 November 2026. This is an incremental implementation, **not** a production release or n8n feature parity.

## Reference and approach

Reviewed the [n8n execution engine at commit d76d304](https://github.com/n8n-io/n8n/blob/d76d304d734baec9de675277cca502a2edc12170/packages/core/src/execution-engine/workflow-execute.ts): explicit start-node selection, execution stack and state for waiting executions demonstrate that execution entry points and durable state must be modeled explicitly. KaivoFlow is a separate Kotlin implementation; no n8n code was copied. n8n's published repository is fair-code licensed: review its applicable license before reusing any code. Durable waits, checkpoints and full execution parity are NOT implemented here.

## Implemented in this milestone

1. **One selected Spark per Run.** `FlowRunner` selects an enabled Spark based on an explicit Step ID or event kind. A mismatched or missing selection fails before actions run. Other Sparks and their disconnected branches receive no items. Manual Runs prefer a manual Spark and otherwise use the first enabled Spark. Timer and manual events with no supplied data seed one empty item; other empty events do not magically create an item.
2. **Device-event filters.** New Android-free `EventTriggerMatcher` matches configured package, title, text, sender and message filters; honors enabled state, ignore-own-notification and ongoing-notification defaults; and rejects unknown/malformed events. `FlowScheduler` now selects matching Sparks and passes each Spark ID into `FlowRunWorker`. Notification regex patterns deliberately fail closed and raise a blocking validation issue until a bounded-time implementation exists.
3. **In-process ownership primitive.** `ActiveRunRegistry` atomically associates one Flow with its actual coroutine Job, holds ownership until cleanup, cancels the owner and prevents stale cleanup from removing another owner. `FlowEngine` now uses it. This is not a persistent distributed lock or an event queue. A concurrent event currently receives a canceled Run result rather than being queued; it may be lost. Android wiring and cancellation/persistence behavior still need instrumentation tests.
4. **Retry safety.** `FlowRunWorker` no longer automatically retries a whole failed Flow, which could repeat successful side effects. Explicit Step retry policies remain opt-in; exactly-once effects are not guaranteed. A durable retry/dead-letter/idempotency design remains necessary.
5. **Smaller safety corrections in Android source.** Schedule updates target a schedule-specific tag rather than canceling all event work, disabled schedule Sparks are excluded, and normal schedules no longer require a network connection by default. WorkManager event input is not copied wholesale into Run metadata. Privacy copy no longer claims all Keystore keys are hardware-backed and now describes notification intake. None of these Android paths were compiled or exercised here.

## Verification

On JDK 11, Kotlin 2.1.20 and JUnit 4.13.2: `scripts/run-core-reliability-tests.sh` compiles the model, engine and node sources and runs **97 tests successfully**. New cases include manual/timer seeding, selected-Spark isolation, wrong/missing Spark IDs, empty non-timer events, filter matching and failure cases, disabled/own notifications, SMS, share, regex fail-closed behavior, ownership cancellation, and concurrent ownership races.

To reproduce with the listed jars available locally:

```sh
export KOTLINC_HOME=/path/to/kotlinc
export TEST_DEPS_DIR=/path/to/jars
./scripts/run-core-reliability-tests.sh
```

This suite uses fake HTTP/device dependencies; it does not test Android. Previously run separately: 13 ScheduleMath tests, 8 JSON codec tests, 11 canvas geometry tests, 7 fallback expression tests and 34 JavaScript assertions; those prior runs were not rerun in this milestone.

## Important remaining blockers

- **No Android build or release:** no Gradle wrapper, Android SDK build, Compose/Hilt/Room/WorkManager compile, R8, JNI, 16 KB alignment, lint, device or Play verification. All Android-source changes above remain unverified.
- **Daily/weekly/monthly recurrence is still not wired:** one-shot work is not rescheduled after completion; OS scheduling is inexact. The pre-existing alarm/permission and timezone/reboot issues remain. Do not rely on scheduled Runs.
- **Webhook:** service manifest/wiring, unauthenticated LAN exposure, ingress reliability, durable acceptance and secure secrets remain unresolved. Do not expose it.
- **Events:** notification/SMS Android sources, PDU parsing and WorkManager payload size limits need fixes and device tests. Regex is intentionally unavailable. Battery events remain unwired. WorkManager dispatch can still lose events; multiple events/Flows have no durable queue. `runNow` enqueues one Run per matching Spark; the in-process one-Run-per-Flow guard can reject overlapping Runs.
- **Security and compliance:** QuickJS containment and timeouts, raw secrets/data retention, logs, restricted Play permissions, exact alarms, foreground service restrictions and data safety disclosures need a comprehensive review. Never test with sensitive data or production credentials yet.
- **Portability and ecosystem:** import/export UI, safe native bundles, n8n translation, OAuth lifecycle, agent/tool permissions and approvals, MCP, RAG, broader connectors, durable waits and enterprise capabilities have not been implemented.
- `docs/PRODUCTION_GAP_REVIEW.md` records the pre-milestone audit. Some specific claims there describe the old implementation; this document is the status update. The broader release risks remain.

## Recommended next implementation slice

Establish a reproducible Android build/CI and fix compiler failures first. Then design durable event storage and cancellation with WorkManager integration tests, implement correctly recurring inexact schedules, remove/review restricted Play permissions, and add a versioned, redacted native export/import with disabled-by-default imports. Provider/AI breadth should follow those trust foundations.
