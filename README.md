# KaivoFlow

Automation for your phone, built for thumbs.

> **Prototype — not production-ready.** A follow-up review found serious issues in trigger dispatch, scheduling, execution cancellation, webhook integration, privacy and sandbox assumptions. Read [the production gap review](docs/PRODUCTION_GAP_REVIEW.md) and [milestone 02 status](docs/MILESTONE_02_TRANSFER_AI.md) before building, testing with sensitive data, or publishing. Some engine and event-safety fixes are in place, but major blockers remain.

KaivoFlow is a mobile-first automation app for Android 8.0+ (API 26 → 36). You describe or draw a
**Flow** of **Steps** connected by **Streams**, then let Sparks (triggers), schedules, notifications,
SMS, webhooks or a share from any other app start a **Run**.

This repository contains an implementation in 13 Gradle modules, including an expression engine,
Flow runner, canvas, and Android integration code. It has not been built or tested on Android;
performance, release readiness, and compliance with all production requirements remain unverified.

---

## Build it

```bash
# A Gradle wrapper is not included. Install a Gradle version compatible with
# the pinned Android Gradle Plugin and use a compatible JDK and Android SDK.
echo "sdk.dir=/path/to/Android/sdk" > local.properties

gradle :app:assembleDebug           # debug APK; resolve build errors first
gradle :app:assembleRelease         # R8 + resource shrinking
gradle test                         # JVM unit tests
gradle :app:installDebug            # to a connected device
```

Version catalogue lives in `gradle/libs.versions.toml`; nothing is hardcoded in module build files
and dependency resolution and compatibility still need to be checked with a real Gradle build.

Release builds configure `packaging { jniLibs { useLegacyPackaging = false } }`. This alone does not prove native libraries are 16 KB-page aligned; inspect and test an actual release artifact.

---

## Module map

| Module | What it owns | Unit tested here |
|---|---|---|
| `:core:model` | `Flow`, `StepNode`, `StreamEdge`, `RunRecord`, `ScheduleMath` | ✅ 13 tests |
| `:core:engine` | runner, validation, expression binding, item plumbing | ✅ (via nodes) |
| `:core:expressions` | QuickJS bridge + a hand-written fallback expression interpreter | ✅ 7 JVM + 34 JS |
| `:core:nodes` | Step implementations + the single Step registry | ✅ (via combined suite) |
| `:core:design` | colours, tokens, typography, icons, components | — |
| `:core:data` | Room database, JSON codec, structure transfer, credential vault | ✅ 21 codec/transfer tests |
| `:core:network` | Ktor/OkHttp `HttpGateway` used by HTTP, AI and SaaS Steps | — |
| `:core:work` | WorkManager Runs, scheduler, webhook server, device gateway, notifications | — |
| `:feature:canvas` | the canvas, cards, Streams, sheets, Run HUD, prompt-to-Flow | ✅ 11 tests |
| `:feature:flows` | Flow list, Run history | — |
| `:feature:settings` | Settings, Privacy centre, credential vault UI | — |
| `:feature:permissions` | permission centre, Notification Spark listener, SMS Spark receiver | — |
| `:app` | Activity, navigation, manifest, R8 rules, theme | — |

`core:model`, `core:engine`, `core:nodes`, `core:expressions` and the canvas layout maths are
Android-free on purpose: they run on a plain JVM so the logic that matters is testable without an
emulator.

---

## Vocabulary (enforced everywhere)

| Say | Never say |
|---|---|
| Flow | Workflow |
| Step | Node |
| Spark (the first Step) | Trigger node |
| Stream | Connection / Edge |
| Run | Execution |
| Freeze output | Pin data |
| Nested Flow | Sub-workflow |

---

## What has actually been verified

Run in this workspace, on JDK 11 with `kotlinc` 2.1.20 and JUnit 4.13.2:

```
core:model + core:engine + core:nodes     104 tests   OK (current milestone)
core:model (ScheduleMath)                  13 tests   OK
core:data (codec + structure transfer)     21 tests   OK
feature:canvas (layout geometry)           11 tests   OK
core:expressions (JVM harness)              7 tests   OK
core:expressions (JS prelude, node)        34 assertions OK
                                          ─────────────
                                         156 JVM tests, all green (separate runs)
```

A heuristic static import audit across the Kotlin files found no unresolved internal references at the time it was run; it does not check all Kotlin symbols or build compatibility.

**Not verified here:** anything that requires the Android SDK (Compose UI, Room, WorkManager,
Hilt codegen, R8). This sandbox has no Android SDK and 1 GB RAM, so `gradle :app:assembleDebug` followed by `gradle :app:assembleRelease` are
the first builds to run on your machine — the code was written against the pinned catalogue, but
only the compiler can prove it. `docs/QA_MANUAL.md` is the on-device test script that follows.

See `docs/MILESTONE_02_TRANSFER_AI.md` for current progress and `docs/PRODUCTION_GAP_REVIEW.md` for the earlier detailed findings and blockers.
`docs/LOGIC_AUDIT.md` is a historical record, not a release or security sign-off; see it for issues found during construction,
`docs/ARCHITECTURE.md` for how the engine and canvas work, and `prototype/kaivoflow.html` for a
clickable visual prototype of the final UI.
