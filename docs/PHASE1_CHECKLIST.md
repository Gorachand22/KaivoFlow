# Phase 1 gate: build and phone checklist

Branch: `claude/phase-1-foundation`. Cloud checks done: 185 JVM tests pass (model, engine, nodes,
expressions) and the 9 per-item tests fail on the old code. The Android modules (`core:work`, `core:data`,
`core:network`, `core:design`, `feature:*`, `app`) have NEVER been compiled, so step 1 may show errors.

## 1. Build
```
git fetch && git checkout claude/phase-1-foundation
./gradlew assembleDebug
./gradlew :core:model:test :core:engine:test :core:nodes:test :core:data:test :core:work:test
```
Fix or paste compile errors to Claude. Commit the generated Room schema file `core/data/schemas/.../3.json`.

## 2. Install on a phone
`adb install -r app/build/outputs/apk/debug/app-debug.apk`

## 3. Checks (tick each)
- [ ] Update over an existing install: flows and credentials are still there, no crash on launch.
- [ ] Flow with 3 items and a Telegram/Slack/HTTP step using `{{ $json.name }}`: three different payloads.
- [ ] Secret field containing `{{ 1 }}` stays literal. Step inspector shows credential picker and plain-text hint.
- [ ] `{{ $env.PATH }}` returns nothing. `{{ $vars.x }}` returns the Flow variable.
- [ ] Deactivated Flow with a schedule does not run, also after reboot.
- [ ] Daily schedule: still armed after the first run. Run shows as Running while executing.
- [ ] Force-stop the app during a Run: the Run shows as Canceled with "Interrupted".
- [ ] Two SMS or events quickly for the same Flow: both Runs happen, in order.
- [ ] SMS Spark fires on a real SMS. Battery Spark fires on charger plug and unplug.
- [ ] Webhook Spark: `curl` to the phone without the token is rejected, with the token works. Not reachable from another device by default.
- [ ] File node cannot read `../flows/...`.
- [ ] No crash when any receiver fires (boot, geofence, Wi-Fi, SMS).

## Known gaps (planned later)
- Flow edits do not resync the webhook service immediately (Phase 5).
- Editing a Flow while its scheduled Run executes can still cancel that Run.
- No per-Flow cap of 500 Runs yet, only 30 day pruning. No separate Interrupted status.
- Android 15 limits the `dataSync` foreground service to 6 hours.
