# Architecture

## 1. The one rule

Everything that decides *what happens* is Android-free and unit-tested. Everything that touches the
platform is a thin adapter. If a bug can be written down as "given this Flow and these items, what
comes out", it lives in `core:model`, `core:engine`, `core:expressions`, `core:nodes` or
`feature:canvas/layout`, and there is a test for it.

---

## 2. Data model

```
Flow ── steps: ImmutableList<StepNode>
     ── streams: ImmutableList<StreamEdge>
     ── vars: ImmutableMap<String, Any?>
     ── settings: FlowSettings
```

`Flow` is a value. Editing produces a new `Flow`; nothing is mutated in place. That single decision
is why undo, canvas diffing, autosave debouncing and Room persistence are all trivial instead of
subtle. `plusStream` refuses self-loops and duplicate edges, so an invalid graph cannot be
constructed through the editor at all.

`StepNode.params` is `Map<String, Any?>` rather than a per-Step class. Rationale: 34 Steps × their
own settings classes is 34 migrations; a JSON-ish map round-trips through Room, through the
expression scope, and through the webhook body with zero conversion code. `NodeSpec.params` (a
declarative list of `ParamSpec`) drives validation *and* generates the inspector UI, so a new Step
needs no UI work.

---

## 3. The runner

`FlowRunner` executes a Flow as a DAG:

1. **Seed** — each Spark produces `RunTrigger.items` (a schedule tick produces one empty item, a
   notification produces the notification payload, a webhook produces the parsed body).
2. **Order** — eager topological start, deterministic by step order. Parallel branches start as soon
   as their inputs exist, capped by `RunOptions(maxConcurrency = 4)`.
3. **Skip** — a non-Spark Step with no live input is `Skipped`, not run. A disabled Step passes
   input through on port 0 (so switching a Step off never breaks the rest of the Flow).
4. **Errors** — `OnErrorPolicy.Stop` fails the Run; `Continue` emits an item carrying
   `{error: …}` and marks the Run `PartialSuccess`; `Retry` honours `RetryPolicy.delayFor(attempt)`
   with exponential backoff clamped by `maxDelayMs`. Retries are opt-in per Step because a Step that
   sends money or a message must not repeat silently.
5. **Freeze** — a Step with `frozenOutput` replays it and reports `Frozen`. This is what makes
   developing the *rest* of a Flow cheap.
6. **Cancel/timeout** — cancellation surfaces as `TimeoutCancellationException` and is rethrown when
   the Run is no longer active; swallowing it was an early bug that made cancelled Runs look
   successful.

Every transition is published as a `RunEvent` (`StepStarted`, `StepFinished`, `StreamFlowing`,
`StepFailed`, `RunFinished`). The canvas renders the Golden Current animation *purely* from that
event stream — there is no separate animation state machine that could drift from reality.

### Expression scope

`core:expressions` binds, for every Step:

```
$json  $items  $item  $index  $vars  $run  $now  $today
$step("Name"[, "field"])      $node["Name"]      helpers: $lower, $upper, $number, $trim, …
```

Evaluation returns `{"ok":true,"value":…}` or `{"ok":false,"error","stack"}` — never throws into the
runner. QuickJS is used when the native library is present; a hand-written recursive-descent
interpreter is the fallback, so a device with a mismatched ABI degrades to "expressions work" rather
than "the app crashes at launch". (An earlier regex-built fallback grammar was abandoned — it could
not parse nested calls.)

---

## 4. Canvas

### Performance contract

* **One `Canvas` pass** draws the grid and *all* Streams. Step cards are Compose composables on top.
* **Pan/zoom are a `graphicsLayer`**, not layout: dragging writes `translationX/Y` and `scaleX/Y` on
  one layer. Zero recompositions, zero relayouts, zero allocations per frame.
* **Caching** — `Path` objects and `PathMeasure` results are `remember`ed keyed on
  `(layouts, streams)`. A gesture never rebuilds a path.
* **Culling** — `CanvasGeometry.isVisible` rejects any card outside the viewport plus a 120 dp
  buffer. Off-screen cards are not composed, measured or drawn.
* **Immutability** — every UI model is `@Immutable` with `kotlinx.collections.immutable` collections,
  so a Run that changes one Step's status does not recompose the other 999.
* **No `Modifier.blur()`** on anything that moves; glows are radial/linear gradient brushes.
* **Reduced motion** — `KaivoTheme.motion.reduceMotion` follows `ANIMATOR_DURATION_SCALE == 0` and
  the system "remove animations" setting; when set, the phase is pinned instead of animated and
  entrance staggers collapse to `snapTo`.
* **Ambient breathing** is paused when the app is backgrounded or battery saver is on
  (`ProcessLifecycleOwner` + `PowerManager.isPowerSaveMode`).

### Layout modes

| Mode | Behaviour |
|---|---|
| **Guided Path** (default on phones) | Rows by longest distance from a Spark, each row centred, 48 dp vertical rhythm. Reads top-to-bottom like a sentence. |
| **Freeform Atelier** | Positions from the model, snapped to a 16 dp grid, pinch 0.35–2.0×, glass minimap, fit-to-screen. |

Guided is the default because a phone cannot meaningfully show a 40-Step graph at once; freeform is
one tap away for people who think in space. `CanvasGeometry` implements both, plus `bezier`,
`pointAlongBezier`, `nearestPort` (36 dp magnetic ports) and `fitToScreen` — all JVM-tested.

### Creating Streams

Dragging from a Step's output port draws a dashed preview in the *canvas* coordinate space
(`toCanvas`, which divides by the current zoom). Releasing within 36 dp of another Step's input port
creates the Stream; releasing on empty canvas opens the Step Picker chained to the source Step.

---

## 5. Persistence

Room stores a Flow as one JSON document plus denormalised columns (`name`, `stepCount`,
`isFavorite`, `lastRunAt`) so the list screen never parses 200 documents to draw 20 rows.

`JsonCodec` is hand-written against `kotlinx.serialization.json`'s tree API rather than `@Serializable`
classes, for two reasons:

* **Forward compatibility.** Unknown fields from a newer build are ignored instead of failing the
  decode, so a downgrade or a beta/stable mix does not delete Flows (test:
  `unknown fields written by a newer build are ignored, not fatal`).
* **Tolerance.** A corrupt row decodes to `null` and is skipped — one bad document does not take the
  list down with it (test: `corrupt json decodes to null instead of throwing`).

Credentials are separate: AES-GCM with a key generated in the Android Keystore (hardware-backed where
available), non-exportable, and excluded from backup by `data_extraction_rules.xml`.

---

## 6. Background execution

* **WorkManager, not AlarmManager.** OEM battery managers punish raw alarms; a `ListenableWorker`
  with the Hilt worker factory is the well-behaved path.
* **Expedited work** for user-visible Runs uses
  `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` — over quota it degrades
  instead of throwing.
* **Exact daily times** are a one-shot chain that reschedules itself after each Run. Periodic work
  has a 15-minute floor and drifts by hours under Doze; a chained one-shot lands on 08:00.
* **Foreground** long Runs use a `dataSync` foreground service and handle `onTimeout()` by finishing
  cleanly rather than being killed mid-Run.
* **`BatteryHealth`** resolves the OEM-specific screens (MIUI autostart, Samsung sleeping apps,
  OnePlus/Realme battery optimisation) with `resolveActivity` + a fallback to app settings, because
  hardcoded component names break on every OEM update.
* **Webhook Spark** runs a hand-rolled HTTP/1.1 server (256 KB body cap, constant-time HMAC check)
  instead of embedding a server framework.

---

## 7. Privacy posture

There is no KaivoFlow account and no KaivoFlow backend. Data leaves the device only when a Step the
user wrote calls a third-party API with the user's own key. `PrivacyCentreScreen` states exactly that,
and it is written to match the Play Console Data Safety declaration so the two cannot disagree.
Sensitive Sparks (notification access, SMS) are separate modules requested only when a Flow needs
them, each behind an in-app disclosure with a decline path that leaves the app usable.
