# Phase 2 gate: owner build and phone test plan

Branch `claude/phase-1-foundation`. Cloud result: 1,049 JVM tests pass offline (model, expressions, engine, nodes, pure
`core:data`, pure `feature:canvas` logic). Everything Android and Compose has NEVER been compiled: `core:work`,
`core:network`, `core:design`, `feature:*`, `app`, and the Room and Hilt code generation. Step 1 can show errors.
Paste the first error to Claude; fix in order (see [BUILD_RISKS.md](BUILD_RISKS.md)).

## 1. Build
```
git fetch && git checkout claude/phase-1-foundation
./gradlew help
./gradlew :core:data:kspDebugKotlin           # Room and Hilt code generation, writes schema 6.json
./gradlew :core:work:compileDebugKotlin
./gradlew :feature:canvas:compileDebugKotlin :feature:flows:compileDebugKotlin :feature:settings:compileDebugKotlin
./gradlew :app:assembleSideloadDebug          # owner APK (play flavour: :app:assemblePlayDebug)
./gradlew test                                # all JVM and Android unit tests
```
JDK 21 must be installed (Android Studio's bundled JBR 21 works).

## 2. Room schema files (commit them)
Room writes `core/data/schemas/com.kaivoflow.core.data.KaivoDatabase/<version>.json` for the version being built.
Only `1.json` is committed; 2, 3, 4 and 5 are missing.
- [ ] `6.json`: at HEAD run `./gradlew :core:data:kspDebugKotlin`, then `git add core/data/schemas`.
- [ ] `5.json` and `4.json`: check out the commit where `Database.kt` said `version = 5` (then `4`), run the same task, copy the file
      out, return to HEAD, add it. Find them with
      `git log --format=%h -S"version = 5" -- core/data/src/main/kotlin/com/kaivoflow/core/data/Database.kt`.
- [ ] Compare `6.json` tables `expected_run_daily`, `reliability_event.acknowledged` and `step_*` with the SQL in
      `core/data/.../Migrations.kt`. A mismatch throws on first open after upgrade: paste the diff.
- [ ] Optional: `2.json` and `3.json` the same way, for a complete migration test later.

## 3. Install
`adb install -r app/build/outputs/apk/sideload/debug/app-sideload-debug.apk` (path may differ). Package `com.kaivoflow.app.debug`.
Set `PKG=com.kaivoflow.app.debug` and `adb shell` helpers in `scripts/adb-*.sh`.

## 4. Owner requirement 1: runs 24/7, screen off, Doze
Build a Flow with an Exact schedule every 2 minutes and one webhook Flow; Publish both (publish sheet, accept warnings).
Commands are from [P2-always-on-engine.md](specs/P2-always-on-engine.md) section 7.
- [ ] Persistent notification appears; `adb shell dumpsys activity services $PKG` shows foreground type specialUse.
- [ ] Screen off: `adb shell input keyevent KEYCODE_SLEEP`, wait 10 minutes: every slot fired within 60 s (Runs tab).
- [ ] Deep Doze: `adb shell dumpsys battery unplug`, `adb shell dumpsys deviceidle force-idle`, `dumpsys deviceidle get deep` says IDLE;
      wait 10 minutes, slots fire. Undo: `dumpsys deviceidle unforce`, `dumpsys battery reset`.
- [ ] Standby: `adb shell am set-standby-bucket $PKG rare`, schedules still fire.
- [ ] Crash: `adb shell am crash $PKG`, Engine returns by itself, Reliability Center logs the exit reason.
- [ ] Reboot: `adb reboot`, unlock, Engine back within 3 minutes, no duplicate Runs.
- [ ] Webhook: `adb forward tcp:8787 tcp:8787`, `curl -i -H "Authorization: Bearer $TOKEN" -d '{"a":1}' http://127.0.0.1:8787/hook/<path>`.
- [ ] Settings, Reliability Center: score shown, red checks have a one-tap fix, self-test passes.
- [ ] Debug dump: `adb shell am broadcast -n $PKG/com.kaivoflow.app.debug.DiagReceiver -a com.kaivoflow.DIAG_DUMP`, then pull the JSON.
- [ ] Your phone's OEM battery steps applied (Reliability Center lists them); overnight screen-off run, note misses.

## 5. Owner requirement 2: new categories and picker
- [ ] Add Step opens the category tree: 10 top categories, Device with 9 subcategories; search finds a Step by name; recents and
      favourites stick after restart.
- [ ] Hidden or legacy Steps (Table, Aggregate, Stats, CSV, Binary, Ask AI) do not show in the picker.

## 6. Owner requirement 3: Table, Schema and JSON panels, drag to map
Open a Flow, run it, open a Step.
- [ ] Input and output panels show Table, Schema and JSON views with item count, search, run selector and paging.
- [ ] Long-press a field in a panel and drag it onto a parameter: an expression is inserted; tap-to-insert tray and TalkBack
      "Insert into field" do the same.
- [ ] Fixed or expression toggle, live expression preview, autocomplete, readable error for a bad expression.
- [ ] Pin data on a Step, Test Step uses it; Binary output shows a size and type, not bytes.

## 7. Owner requirement 4: nodes that remain
- [ ] The 62 visible Steps open and run; removed ones (TCP, Chart, Random) show as "Unavailable Step" in old Flows.
- [ ] Edit Fields, HTTP, Notify, Clipboard, Merge, Loop over items, Remove Duplicates and Crypto work on a 3-item input,
      one result per item.
- [ ] Code Step and `{{ 1 + 2 }}` style expressions evaluate.

## 8. Owner requirement 5: upgrade over an existing install, no data loss
Install the previous APK first (Phase 1 build), create two Flows, one credential, a variable and a data table.
- [ ] `adb install -r` the new APK: app opens, no crash, all Flows, credential, variable, table and Run history present.
- [ ] Old Step ids open (aliases): a Flow made with `trigger.schedule` or `saas.http` shows the new Steps.
- [ ] Previously active Flows appear as Published and are re-armed (check Reliability Center).
- [ ] Backup export, then import on a fresh install: Flows and pins restored.

## Known follow-ups (not blocking)
- targetSdk stays 34. Raising it to 35/36 is Phase 2F, needs the full device matrix first.
- `docs/specs/P2-device-results.md` is created from the results above.
