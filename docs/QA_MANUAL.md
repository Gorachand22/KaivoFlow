# On-device QA script

The JVM suite covers the logic (77 tests). This is the part that needs a real phone: Compose layout,
permissions, WorkManager timing and OEM battery behaviour. Run it top to bottom on a release build
(`./gradlew :app:installRelease`) — debug builds mask R8 and permission-prompt problems.

Device: Android ____ , OEM ____ , screen ____ . Build: ______ .

---

## 0. Cold start

- [ ] Launch: no white flash, the void colour (#09080E) is the first frame.
- [ ] First run shows the Flow list empty state with "Describe a Flow" — no permission dialog, no
      onboarding wall.
- [ ] Rotate to landscape and back: no duplicate Activity, canvas keeps its transform.
- [ ] Back from the Flow list exits; back from the canvas returns to the list.

## 1. Prompt to Flow

- [ ] Tap **Describe a Flow** on the empty state. The magic card appears with three starter chips.
- [ ] Type "every morning at 8, send me the weather on Telegram" → tap the sparkle button.
- [ ] Steps materialise with a ~55 ms stagger (if the device has animations disabled, they appear
      instantly instead of animating).
- [ ] The generated Flow contains a Schedule Spark (08:00), a Weather Step and a Telegram Step, wired
      in that order, with names a human wrote — not ids.
- [ ] Tap **Run**: either it succeeds, or a validation banner explains exactly what to fix. There is
      no third outcome.

## 2. Canvas — Guided Path

- [ ] Cards are 220 dp wide, at least 84 dp tall, radius 20 dp; the medallion carries the jewel tone
      for the Step's category.
- [ ] Pan with one finger: motion is 1:1 with the finger, no lag, no wobble.
- [ ] Pinch: zoom stops at 0.35× and 2.0×; cards stay legible, Streams stay attached.
- [ ] Scroll a 30-Step Flow: no jank (open Profile → GPU rendering, "janky frames" should stay < 5%).
- [ ] Airplane mode + battery saver: any ambient glow stops breathing; nothing else changes.

## 3. Canvas — Freeform

- [ ] Toggle to Freeform from the top bar. Cards keep their relative positions.
- [ ] Drag a card: it snaps to the 16 dp grid.
- [ ] The minimap in the bottom-right shows the whole Flow plus a gold viewport rectangle; tapping it
      recentres.
- [ ] Fit-to-screen frames the whole Flow with padding, never at the floor zoom unless required.
- [ ] Drag from a card's bottom port: a dashed line follows the finger, and the target card's input
      port lights up within 36 dp (magnetic snap). Release creates the Stream.
- [ ] Drop on empty canvas: the Step Picker opens and the new Step is chained to the source.

## 4. Step Picker & Inspector

- [ ] Step Picker: search "tele" → Telegram appears; search "zzz" → honest empty message.
- [ ] Only one Spark can exist per Flow; the picker hides Spark entries once one is present.
- [ ] Card tap opens the Inspector with a gold drag handle; the sheet expands past the halfway point
      and can be flung down to dismiss.
- [ ] **Configure** renders exactly the fields the Step declares. Boolean, select, multiline, JSON and
      code fields all work; the code field uses the mono font.
- [ ] Focus any text field: the expression accessory bar appears with `{{ $json. }}`, `{{ $now }}`
      and one chip per previous Step. Tapping a chip inserts the expression.
- [ ] **Test Step** shows a spinner, then real output on the Output tab; a failure shows the message
      and keeps the sheet open.
- [ ] **Freeze output** marks the card `Frozen`; the next Run replays it instead of calling the API.
- [ ] Turning a Step off keeps the Flow runnable: the item passes through to the next Step.
- [ ] Rename a Step: the card updates immediately, and `$step("New name")` resolves in later Steps.

## 5. Run experience

- [ ] Run with 5+ Steps: the HUD appears at the bottom (thumb-reachable), progress and elapsed time
      update, the current Step glows, a gold orb travels each live Stream.
- [ ] Cancel mid-Run: the HUD says "Run stopped" and the Run history shows `Canceled` — not
      `Succeeded`.
- [ ] Fail a Step deliberately (bad URL): the card turns red, the HUD shows the failure, and the Run
      appears in history with the failing Step named.
- [ ] Rotate mid-Run: the canvas keeps its transform and the HUD keeps its progress (state survives).

## 6. TalkBack

- [ ] Enable TalkBack. Every interactive element announces something meaningful: "Add a Step",
      "Run", "Stop this Run", "Back to Flows", "Fit the Flow on screen".
- [ ] Each Step card announces its name and status, not "button, button, button".
- [ ] The inspector's segmented tabs read as tabs with their selected state.
- [ ] Nothing is reachable by touch that cannot be reached by swipe.

## 7. Touch targets

- [ ] Every tappable element is at least 48 dp (Developer options → "Show taps" + a ruler, or
      Layout Inspector).
- [ ] The golden Run button is reachable one-handed on a 6.7" phone without shifting the grip.

## 8. Scheduling (the hard part)

- [ ] Create a Schedule Spark for 2 minutes from now → lock the screen → the Run fires within ~1
      minute of the target (Doze allows this for a chained one-shot, but verify on the actual OEM).
- [ ] Wait for the same Flow's *second* occurrence: it must fire again without opening the app. (This
      is the reschedule chain; a missing reschedule is the classic bug.)
- [ ] Change the device timezone → the Flow fires at the configured wall-clock time in its own
      timezone, not shifted.
- [ ] Reboot the phone → open the app → the schedule is alive and the next run is still correct.
- [ ] Set a monthly Flow to day 31 → confirm "Next run" lands in a 28-day month (never February-skip).
- [ ] Set "every 5 minutes" → the UI explains the 15-minute floor instead of silently doing something
      else.

## 9. OEM battery resilience

- [ ] Settings → Battery health shows the correct OEM row (MIUI / Samsung / OnePlus / Realme / other).
- [ ] Tapping "Open" resolves to a real screen on this device; if the OEM moved it, the app-settings
      fallback opens instead of crashing.
- [ ] After enabling the recommended settings, re-run the 2-minute schedule test with the screen off.

## 10. Permissions

- [ ] Settings → Permissions lists four rows with honest explanations and shows "Not granted" until
      granted.
- [ ] Granting notifications does not also grant anything else.
- [ ] Notification Spark: enable notification access, create a Flow "when Telegram notifies me,
      append to a file", trigger a real Telegram notification → the Run happens and the payload
      matches the notification's title/text.
- [ ] Deny notification access: the Flow still exists, and any Run shows a clear warning instead of
      hanging.
- [ ] SMS Spark: grant SMS, receive a test SMS (from another phone or an emulator `sms send`), and
      confirm the payload contains sender + body only.
- [ ] Revoke SMS in system settings → the Spark's Runs fail with a readable message, nothing crashes.

## 11. Privacy & credentials

- [ ] Privacy centre lists the three facts; every claim matches what you can observe (e.g. with
      airplane mode on, nothing is sent anywhere).
- [ ] Save an API key in Credentials → it appears masked, and the value is never shown again.
- [ ] Force-stop and relaunch: the credential still resolves for a Run.
- [ ] Uninstall/reinstall: the credential is gone (Keystore key destroyed) and nothing was restored
      from backup.
- [ ] `adb shell dumpsys package com.kaivoflow.app | grep -i backup` shows backup is effectively
      disabled for the vault.

## 12. Release-build specific

- [ ] `./gradlew :app:assembleRelease` passes with R8 and resource shrinking.
- [ ] Install the release APK (not debug) and repeat sections 1, 5 and 8 — R8 breakage shows up here
      first (serializers, JNI method names, Hilt).
- [ ] `unzip -l app-release.apk | grep lib/` — native libs are present and the APK has an
      uncompressed `lib/` entry alignment suitable for 16 KB pages (Play Console will also flag it).
- [ ] No crash on a fresh install with no data, and none after importing a Flow from a previous
      install.

---

## If something fails

1. `adb logcat -s KaivoFlow:V AndroidRuntime:E` — the runner logs every Step transition.
2. Open **Run history** and expand the Run: the failing Step, its attempts and its error are all
   recorded.
3. If it is a scheduling problem, check `adb shell dumpsys jobscheduler | grep kaivoflow`.
4. If it is a rendering problem, capture a `perfetto` trace while panning; the canvas should show a
   single graphicsLayer transform and no recomposition of the card layer.
