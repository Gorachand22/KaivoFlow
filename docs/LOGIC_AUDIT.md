# Logic audit — historical construction notes

> **Superseded as a readiness assessment:** the later [production gap review](PRODUCTION_GAP_REVIEW.md) identifies release blockers and incorrect assumptions in scheduling, trigger filtering, security, webhook integration, and other areas. The fixes described below do not establish Android buildability, correctness, security or production readiness.

Every entry below was a real defect found while building and testing KaivoFlow, not a hypothetical.
Entries marked **▲** were silent — they would have shipped as "weird behaviour" rather than a crash.

---

## Engine & runner

1. **▲ Logic Steps read the wrong parameter bag.** `If`, `Filter`, `Switch` read
   `ctx.params()` (the *incoming item*) instead of `ctx.step.params` (the Step's own settings), so a
   condition like `status == "paid"` was compared against the item's `status` field rather than the
   configured value. Result: branches fired essentially at random. Fixed; regression-tested in
   `NodeExecutorTest`.

2. **▲ Deadlock under parallel start.** Steps were launched with `async(LAZY)` and awaited in
   order; a diamond (A → B, C → D) could wait on a future that could not start until the waiting
   branch finished. Replaced with eager topological start bounded by `maxConcurrency`. Do not revert.

3. **▲ Cancelled Runs reported success.** `runCatching` swallowed `TimeoutCancellationException`,
   so a Run cancelled by the user (or by timeout) finished as `Succeeded`. Now rethrown when the
   coroutine is no longer active and mapped to `Canceled`.

4. **▲ Edge liveness was tracked per Step, not per `fromPort`.** An `If` Step with one live branch
   made *both* outgoing Streams look active, so the "else" branch ran with the "then" branch's items.
   Liveness is now per `edge.fromPort`.

5. **▲ Disabled Steps broke the Flow.** Skipping a disabled Step left the next Step with zero input,
   which then reported `Skipped`, and the break cascaded to the end of the Flow. Disabled Steps now
   `markBypassed()` and pass input through on port 0.

6. **▲ The error Step ran at the wrong time.** A Step wired to `FlowSettings.errorStepId` was fed
   *before* the DAG had finished, so it sometimes saw partial state and sometimes never ran. It now
   runs exactly once, after the graph settles, and the Run is marked `PartialSuccess` when an error
   was handled.

7. **▲ `PartialSuccess` did not exist as an outcome.** A Flow where one branch failed with
   `OnErrorPolicy.Continue` reported `Succeeded`, which hid a broken Step for weeks. It is now a
   first-class `RunStatus` and shown on the Flow card.

---

## Expressions

8. **▲ `Values.writeJson` produced invalid nesting.** Nested objects were indented one level short,
   so `$json` printed as a valid-looking but malformed document in the inspector. Fixed; the write
   path is now recursive over depth.

9. **▲ A missing nested path threw instead of resolving to `null`.** `$json.a.b.c` where `a` is
   absent crashed the expression instead of yielding `null` plus a `warning`. Now returns `null` and
   warns.

10. **Regex-built fallback grammar abandoned.** The no-QuickJS fallback parsed expressions with a
    stack of regexes; it could not handle nested calls (`$lower($trim(x))`) and mis-split strings
    containing commas. Replaced with a hand-written recursive-descent parser. **Do not reintroduce
    the regex approach.**

11. **▲ Kotlin string literals in the prelude had to escape `$`.** `"$json.amount"` in a Kotlin test
    fixture interpolates. All JS snippets use `${'$'}json`. This one bit twice.

---

## Persistence

12. **▲ `JsonCodec` called an extension function that did not exist.** `root["vars"]?.fromJsonMap()`
    referred to a member function taking an `JsonElement` argument. It compiled in the IDE's light
    analysis but not with `kotlinc`; the Flow's variables and every Step's `params` would have
    decoded as empty maps — a data-loss bug that only appears after a save/reload cycle.
    Fixed to `root["vars"]?.let { fromJsonMap(it) }` and covered by `JsonCodecTest`.

13. **▲ Unknown enum values dropped whole Run rows.** `RunStatus.valueOf("Exploded")` threw inside
    the decode, which discarded the entire Run. Unknown statuses now fall back (`Failed` for Runs,
    `Skipped` for Steps) so history stays readable, and the test
    `unknown run status falls back instead of dropping the whole history row` locks it.

14. **▲ Handing a `RetryPolicy` field name mismatch.** The codec wrote `initialDelayMs` /
    `backoffMultiplier` / `maxDelayMs` while the model had different names; retries would have run
    with defaults regardless of user settings. Names unified and asserted in `JsonCodecTest`.

---

## Scheduling

15. **▲ Monthly on the 31st skipped February entirely.** `withDayOfMonth(31)` in February either
    throws or, worse, rolls into March; either way the Flow did not run. Day-of-month is now clamped
    to 1..28 so a monthly Flow fires every month.

16. **▲ Weekly "every Monday" could fire on Tuesday.** The weekday filter walked forward from
    *tomorrow* even when today was allowed and still ahead. The day is now evaluated before any
    advance.

17. **▲ Sub-daily intervals below 15 minutes were silently rounded.** A Flow asking for "every 5
    minutes" was clamped by the platform with no explanation. Now clamped in `ScheduleMath` and
    surfaced to the user instead of failing a `require()` at schedule time.

18. **Schedule maths was untestable** because it lived inside a class that needs a `Context`.
    Extracted to `core:model/ScheduleMath` — Android-free, 13 tests, including a European DST
    spring-forward case where 02:30 does not exist.

---

## Wiring & build

19. **▲ Two files imported `TriggerSpecs` from `core:model`.** It lives in `core:nodes`, and
    `feature:permissions` did not even depend on that module — the share-sheet handler and the SMS
    receiver could not have compiled. Found by the static import audit (see below), fixed in both
    files plus the missing Gradle dependency.

20. **▲ `CanvasScreen` read state the ViewModel never published.** Step positions and raw Step params
    were read straight off the Flow document inside composition, so a parameter edit rebuilt the
    screen from a stale copy. `CanvasUiState` now carries `positions` and `paramCache`, published by
    the ViewModel as immutable maps.

21. **`Application` called a WorkManager initializer that the manifest disables.** The app now
    implements `Configuration.Provider` (the supported on-demand path) and the manifest removes the
    automatic startup provider, so Runs get Hilt-injected workers instead of silently using the
    default factory.

22. **`GlobalScope` in the SMS foreground service.** Replaced with a scoped coroutine that stops the
    service on completion, so an SMS Spark cannot leak a foreground notification.

**Static import audit.** A script parses all 61 Kotlin files, records every declared symbol per
package, and fails if any `import com.kaivoflow.…` names a symbol that does not exist in that
package. It currently reports zero unresolved internal references. That is not a substitute for
`gradle :app:assembleRelease` (a Gradle wrapper is not included), but it catches the class of error that only shows up at the very end of
a long build.

---

## Deliberately accepted limitations

| Limitation | Why it is acceptable |
|---|---|
| FlowData `pairedIndex` / `error` are not persisted in frozen output | Frozen output is a *payload* replay; the error flag of a frozen item is not meaningful. Buttons and titles round-trip untouched. |
| Webhook Spark is HTTP/1.1, not HTTP/2 | It only ever receives provider callbacks; TLS termination is the user's tunnel or LAN. 256 KB body cap and HMAC comparison are in place. |
| Android UI is untested off-device | No Android SDK in this environment. `docs/QA_MANUAL.md` is the script that closes the gap on a real phone. |
| Brand fonts fall back to system families | `KaivoTypography` uses system sans/mono families by default; dropping Plus Jakarta Sans / Inter / JetBrains Mono `.ttf` files into `core:design/src/main/res/font` and pointing the family at them is a one-line change, deliberately not baked in so the build does not depend on missing binaries. |
