# P2: Always-On Engine (spec)

Status: draft for owner review. Date of research: 2026-11-16. Branch the spec was written against: `claude/phase-1-foundation` (commit d9bea02).
Kind: specification only. No code is changed by this document.
Vocabulary: Flow, Step, Stream, Spark, Run. Other new terms: Inbox (durable queue of Spark events), Pulse (periodic liveness alarm), Reconciler (idempotent repair routine), Engine (the foreground service plus the code it hosts).

Note on numbering: "P2" here is the owner's label for this spec. It is not the same as "Phase 2" in `docs/ROADMAP.md` (data and expression core). In roadmap terms this work is a pull-forward of the Phase 5 reliability items.

Legal note: n8n is under the Sustainable Use License. It was read only to understand the behaviour of workflow activation (section 4.6). Nothing here is copied from it; every description is in our own words and every design is original to KaivoFlow.

---

## 0. Goal, scope and the one-paragraph answer

Owner goal: once a Flow is Published it must run 24/7. It must keep working when the user leaves the app, when the phone is locked with the screen off, and for days, for schedules, webhooks and event Sparks (SMS, notification, battery, geofence, Wi-Fi, Bluetooth). The APK is sideloaded (Gradle or adb), so Play Store policy is secondary and is noted in section 9.

Answer in one paragraph. No Android app can promise this on every phone (section 8). What KaivoFlow can do is get as close as the platform allows and be honest about the rest. The design has five rules:

1. **Persist first, execute second.** Every Spark event and every schedule slot is written to a durable Inbox before anything tries to run it. A crash then loses time, never events.
2. **One long-lived foreground service (the Engine) hosts everything that needs a live process**: the webhook listener, runtime-registered receivers, the Run executor, and the heartbeat. It uses foreground service type `specialUse`, not `dataSync`, because `dataSync` has a 6 hour per 24 hour cap on apps targeting Android 15 or higher.
3. **Every timer is an alarm, never an in-process delay.** Schedules use `AlarmManager` (exact while idle, or alarm-clock class for "must be on time" Flows). A CPU that is asleep does not run coroutine timers.
4. **Three independent safety nets restart a dead Engine**: the Pulse alarm, a WorkManager watchdog, and the system's own `START_STICKY` restart. A single idempotent Reconciler is called by all of them.
5. **Prove it with evidence.** The app keeps a heartbeat, an expected-versus-actual log of scheduled Runs, and the reason each process died. The Reliability Center turns those into a health score. A check that cannot be measured (OEM autostart) is never shown as green.

---

## 1. Current state of the code (read for this spec)

Several findings in `docs/audit/04` and `docs/audit/07` were fixed by Phase 1. This table reflects the code as it is now, and lists only what matters for 24/7 operation.

| Area | What the code does today | Why it is not enough for 24/7 |
|---|---|---|
| Process hosting | `KaivoApplication.onCreate` calls `rescheduleAll()`, `recoverAndPrune()` and `SparkRuntime.start()`. | Everything is bound to the process being alive. Nothing keeps the process alive once the user leaves the app, so runtime-registered receivers (battery, Wi-Fi, Bluetooth, headset) go quiet when Android caches or kills the process. |
| Webhook | `WebhookService` is a foreground service of type `dataSync`, `START_NOT_STICKY`, started by `WebhookController.sync()` from `SparkRuntime.refresh()` (`WebhookService.kt:45,52,85`). Server binds loopback by default and requires a token (`WebhookPolicy.kt`). | `dataSync` has the 6 hour cap once targetSdk reaches 35. A refused background start is swallowed by `runCatching`, so the listener silently never starts. Not sticky, no boot start, not part of any health signal. Loopback bind means no other device can reach it unless the user adds a tunnel or opts into LAN. |
| Schedules | `FlowScheduler` uses WorkManager only: periodic work for minutes/hourly, a chained one-shot with two alternating unique-work slots for daily/weekly/monthly. The next slot is armed before the Run starts (`FlowRunWorker.kt:56-68`). | Inexact by design under Doze and standby. No `AlarmManager`. No persisted schedule state, no expected-run record, so a missed run cannot be detected or caught up. The manifest declares exact-alarm permissions that nothing uses. |
| Event Sparks | `dispatchTrigger` finds matching Flows and calls `runNow`, which enqueues expedited work carrying the payload in WorkManager `Data` (about 10 KB limit) (`FlowScheduler.kt:139-180`). | A payload over the limit throws. An in-memory per-Flow queue (`PerFlowEventQueue`) is lost on process death. There is no durable intake. |
| SMS | `SmsSparkReceiver` uses the platform message helper, `goAsync()`, then `dispatchTrigger`. `SmsTriggerService` (foreground, `dataSync`) is still declared and unused. | The receiver is correct now. The dead service should go. On Android 17 SMS delivery to non-default apps can be delayed (section 2, row R27). |
| Notification Spark | `KaivoNotificationListener` posts events; returns early when the title is null. | No `onListenerConnected` / `onListenerDisconnected` tracking, no rebind request, so "listener bound" cannot be reported. |
| Battery / Wi-Fi / Bluetooth / headset | Dynamic receivers registered once in `SparkRuntime.start()`. | Alive only while the process is alive. Wi-Fi uses `NETWORK_STATE_CHANGED_ACTION` (deprecated, and implicit). |
| Geofence | `LocationManager.addProximityAlert` with a `PendingIntent` to `MobileEventReceiver` (`FlowScheduler.kt:190-208`). | Deprecated API. Not re-registered by a reconcile pass after reboot except via `rescheduleAll()` at boot or app start. Needs background location. |
| Worker | `FlowRunWorker` can call `setForeground` only when asked, with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (`FlowRunWorker.kt:47-49,106-114`). Returns `failure` for Canceled and Failed. | Fine as a fallback host. Not the primary one. No `getForegroundInfo` override for API 26 to 30 expedited work. |
| Boot | `BootReceiver` handles BOOT_COMPLETED, package replaced, timezone and time changes by calling `rescheduleAll()` and dispatching the Boot Spark. | Does not start any long-lived component. No record that a reboot happened and what was recovered. |
| Lifecycle | `Flow.isActive: Boolean`. No Draft/Published separation (audit 07 section 4.3). | No Paused, no Error, no per-Spark registration status, nowhere to say why a Flow is not actually listening. |
| Reliability UI | `BatteryHealth` returns four static checks; the OEM check's `ok` is a guess (`ok = Stock or Other`). `PermissionCentreScreen` lists four permissions. | No measured signals, no heartbeat, no log. |
| SDK | `compileSdk = 34`, `targetSdk = 34`; manifest says `tools:targetApi="36"`. | The platform's newest background rules are not enforced on this app yet. See decision D8. |
| Data layer | Phase 1 turned on `exportSchema = true` and removed destructive fallback (DB version 3). | Good: new tables in this spec need real Room migrations, and the infrastructure exists. |

---

## 2. (a) Current Android rules (research, November 2026)

How to read this table. "Applies to" says whether the rule depends on the OS version, the app's targetSdk, or both. "Verified" is `doc` when I read the cited page during this research, `api` when it is stable platform knowledge I did not re-read, and `device` when it must be confirmed on a phone (section 7). Source ids are listed in section 11. Android 17 is included because phones on it exist in November 2026, although the owner asked for 14 to 16.

| # | Rule | Applies to | What it means for KaivoFlow | Verified | Src |
|---|---|---|---|---|---|
| R1 | Every foreground service must declare a `foregroundServiceType` and hold the matching `FOREGROUND_SERVICE_<TYPE>` permission, besides `FOREGROUND_SERVICE`. Missing permission throws `SecurityException`. | targetSdk 34+ (Android 14) | Engine needs `FOREGROUND_SERVICE_SPECIAL_USE`. Existing `dataSync` declarations must be replaced or justified. | doc | S2, S3 |
| R2 | `dataSync` and `mediaProcessing` are the only types with a timeout: 6 hours total per rolling 24 hours, shared across all of the app's services of that type. At the limit the system calls `Service.onTimeout(startId, fgsType)`; the service then has a few seconds to stop, or the system throws `RemoteServiceException`. After exhaustion, starting another one throws `ForegroundServiceStartNotAllowedException` until the user brings the app to the foreground (which resets the timer). | App targets 35+, device on Android 15+. Can be force-enabled on lower targets with a compat flag for testing. | An always-on engine on `dataSync` would be killed every day once targetSdk reaches 35. Do not use `dataSync` for the Engine. | doc | S1, S4 |
| R3 | `BOOT_COMPLETED` receivers may not start foreground services of type `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone`. Doing so throws `ForegroundServiceStartNotAllowedException`. | targetSdk 35+ | `specialUse` is not on the banned list, so the Engine can start at boot. `dataSync` cannot. | doc | S4 |
| R4 | `specialUse` has no runtime prerequisites and no timeout. It needs the `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">` element in the manifest with a specific free-text explanation. | targetSdk 34+ | Best fit for the Engine. Play review needed for store builds only. | doc | S2 |
| R5 | `systemExempted` is reserved for system apps and a short list of integrations. A third-party app may declare it only when one of these holds: demo mode; device or profile owner; holds `ROLE_EMERGENCY`; device management app; **holds `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM`**; VPN app. Otherwise it throws `ForegroundServiceTypeNotAllowedException`. | targetSdk 34+ | Technically reachable through the exact-alarm permission and has no timeout, but its purpose is system use and it is not mentioned in Play's declaration guidance. Documented fallback, not chosen (section 3.4). | doc | S2, S18 |
| R6 | `remoteMessaging` has no prerequisites or timeout and is meant for moving text messages between a user's devices. | targetSdk 34+ | A stretch for a general automation engine; Play review would ask for a video of the user starting a message transfer. Not chosen. | doc | S2, S18 |
| R7 | `shortService` runs about 3 minutes, cannot be sticky, cannot start other foreground services. Extending it needs the app to be visible or exempt. | targetSdk 34+ | Not suitable as the Engine. Possible later as a short Run host. | doc | S2 |
| R8 | An app targeting 31+ cannot start a foreground service from the background unless an exemption applies. The failure is `ForegroundServiceStartNotAllowedException`. Exemptions include: transition from a visible state; user taps a notification, widget or bubble; **the user turned off battery optimisation for the app**; **an exact alarm fires to complete a user-requested action**; geofence or activity-recognition transition; `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`; `TIMEZONE_CHANGED`, `TIME_CHANGED`, `LOCALE_CHANGED` receivers; high-priority FCM; companion device manager with the right permission; device owner; `SYSTEM_ALERT_WINDOW` (on 15+ only with a visible overlay). **Not on the list**: SMS received, notification listener events, WorkManager jobs (job-based foreground promotion is a separate documented path). | targetSdk 31+ | The battery-optimisation exemption is the master key: with it the Engine can be started from any receiver. Without it we rely on alarms, boot, geofence, time-change and user taps. SMS and listener events must not depend on starting a service. | doc | S7, S3 |
| R9 | Android 15 stopped state: after the user force-stops an app, **pending intents are cancelled** (alarms, geofence, scheduled work) and widgets are disabled until the user launches the app. `ACTION_BOOT_COMPLETED` is delivered when the user's action removes the app from the stopped state. `ApplicationStartInfo.wasForceStopped()` reports it. | Android 15+ (all apps) | After a force-stop nothing can restart the app. We can only detect it afterwards and log it (section 6). | doc | S5 |
| R10 | Android 15 background network: requests started outside a valid process lifecycle throw (typically `UnknownHostException`). WorkManager or a foreground service are the documented ways to stay inside a valid lifecycle. | Android 15+ (all apps) | Runs must execute inside the Engine or inside a WorkManager job, never in a detached coroutine in a cached process. | doc | S5 |
| R11 | Doze (screen off, unplugged, stationary): network suspended, wake locks ignored, standard alarms deferred to maintenance windows, Wi-Fi scans blocked, jobs and syncs deferred. Maintenance windows get rarer over time. `setAlarmClock` alarms still fire (the system leaves Doze for them). `setAndAllowWhileIdle` and `setExactAndAllowWhileIdle` fire in Doze but at most about once per 9 minutes per app; the power-limits page states while-idle alarms are capped at 7 per hour in Doze. | All versions 6+ | Sub-10-minute cadence cannot be guaranteed in deep Doze (limit L4). Use `setAlarmClock` sparingly for hard deadlines. | doc | S8, S10, S12 |
| R12 | Apps on the battery-optimisation allowlist keep network access and partial wake locks during Doze and App Standby. Regular alarms and jobs are still deferred (so use the `AllowWhileIdle` alarm variants). The allowlist also frees the app from bucket restrictions. | All versions | Required for the Engine's network (webhook, Runs that call APIs) while the screen is off. | doc | S8, S9 |
| R13 | Play policy allows the direct "ignore battery optimisations" request for apps whose core function breaks without it. The documented acceptable cases include task automation apps. Messaging apps that can use FCM generally do not qualify. | Play distribution | KaivoFlow qualifies in principle. Sideloaded builds have no policy gate. | doc | S8 |
| R14 | App Standby buckets: Active, Working set, Frequent, Rare, Restricted. Typical limits (approximate, may change): Rare allows about 1 alarm per hour, disables network for background work, and limits jobs to 10 minutes per 24 hours. Restricted allows jobs once a day, one alarm per day, network disabled. Restricted is reached after 8 days of non-use on Android 13+ or from abusive broadcast/binding behaviour. Exemptions: battery-optimisation allowlist, `USE_EXACT_ALARM` or `ACCESS_BACKGROUND_LOCATION` held, an active widget, companion device, VPN, user "Unrestricted". | Android 9+; Restricted 12+ | The Engine must not depend on being in a good bucket. Holding the allowlist exemption (and in sideload builds `USE_EXACT_ALARM`) avoids the worst buckets. | doc | S9, S10 |
| R15 | Android 16: regular and expedited job quotas now depend on the bucket, on whether the job started while the app was in the top state, and **on whether the job ran while the app had a foreground service**. Jobs started from a foreground service follow their quota (this covers WorkManager). Active bucket gets a generous quota. User-initiated data transfer jobs are exempt. | Android 16+ (all apps) | While the Engine FGS is alive, do not use WorkManager as the Run host. Run in the Engine process instead. Use WorkManager only when the Engine is down. | doc | S6, S10 |
| R16 | Exact alarms: `SCHEDULE_EXACT_ALARM` is **denied by default** for fresh installs targeting 13+ on Android 14 (and restored apps). Without it `setExact`, `setExactAndAllowWhileIdle`, `setAlarmClock` throw `SecurityException`. Always allowed without the permission: platform-signed apps, privileged apps, **apps on the battery-optimisation allowlist**. `canScheduleExactAlarms()` checks; `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` opens the grant screen; `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` tells the app when the user grants. If the user revokes it, the app is stopped and its exact alarms are cancelled. | Android 12+; default-deny 14 | The permission may be missing on a fresh sideload. Check, route the user to the page, fall back to window alarms, and re-arm on the state-changed broadcast. Revocation kills the process, so recovery must happen at next start. | doc | S11, S12 |
| R17 | `USE_EXACT_ALARM` (API 33+) is granted at install and cannot be revoked. Play only allows it for alarm clock and calendar apps. | API 33+ | Good for a sideload flavour; forbidden in a store flavour (section 9). Holding it also exempts the app from the Restricted bucket (R14) and satisfies R5. | doc | S11, S9 |
| R18 | All alarms are cancelled at reboot. An app restores them in a `BOOT_COMPLETED` receiver, which only works if the user launched the app at least once. | All versions | Reconciler re-arms everything at boot. | doc | S12 |
| R19 | WorkManager periodic work has a 15 minute floor. WorkManager runs on JobScheduler when the app is not visible, so it is deferred in Doze and bound by bucket quotas. A normal worker is cut off at about 10 minutes. A long-running worker uses `setForeground` and must pass a foreground service type on targetSdk 34+; that type must be declared in the manifest on WorkManager's `SystemForegroundService`. Long workers can exhaust the job quota on Android 16. | WorkManager 2.x; quotas 16 | Good as the watchdog and as the fallback Run host. Not a timer for sub-15-minute or on-the-minute schedules. | doc | S13, S6, S10 |
| R20 | Partial wake locks: Android vitals flags apps holding partial wake locks for 2 hours or more in 24 hours (all locks combined, including while a foreground service runs). Exempt: audio, location, user-initiated jobs. Over 5% of sessions across 28 days can reduce Play visibility. | Play distribution | Never hold a standing wake lock. Hold one only during a Run and during Inbox intake, always with a timeout (section 3.6). | doc | S14 |
| R21 | Wi-Fi low-latency lock mode only takes effect when Wi-Fi is on, the app holds the lock and is in the foreground, and the screen is on. | Android 10+ | A Wi-Fi lock does not help a screen-off webhook listener. Do not take one. Report Wi-Fi sleep behaviour as a check instead. | doc | S15 |
| R22 | Direct Boot: after a reboot, credential-encrypted storage (where the Room database lives) is unavailable until the first unlock. `BOOT_COMPLETED` arrives after unlock. Only components marked `directBootAware` and using device-protected storage run earlier (`LOCKED_BOOT_COMPLETED`). | All versions with file-based encryption | After a reboot with a PIN, nothing that needs the database runs until the user unlocks once (limit L7). | doc | S16 |
| R23 | Restricted settings: apps installed by manual APK download (not a session-based store install) cannot be granted accessibility or notification-listener access until the user opens App info, taps the menu and chooses "Allow restricted settings". | Android 13+ | A sideloaded APK may find the notification access toggle greyed out. The Reliability Center must detect "access not grantable" and show those steps. Whether `adb install` is affected is not stated by the source; confirm on device. | doc (adb: device) | S21 |
| R24 | Notification listeners: Android 15 hides unredacted content of notifications where an OTP was detected from untrusted listeners. The system binds and unbinds listeners; `requestRebind(ComponentName)` exists; the service reports `onListenerConnected` and `onListenerDisconnected`. The permission check alone does not say whether the listener is currently bound. | Android 15+ for redaction | Notification Spark will see redacted text for OTP messages. Track connection state ourselves and probe it (section 6.4). Rebind behaviour after process death needs device confirmation. | doc (rebind: device) | S5, S26 |
| R25 | Geofencing: 100 geofences per app per user; needs fine location plus `ACCESS_BACKGROUND_LOCATION` on Android 10+; typical latency under 2 minutes, 2 to 3 minutes under background limits, up to about 6 minutes when stationary; minimum useful radius about 100 to 150 m; geofences must be re-registered after reboot, app data clear, reinstall; Wi-Fi or Wi-Fi scanning and a data connection improve accuracy. Receiving a geofence transition is an FGS-start exemption (R8). | Play services geofencing | The geofence Spark has a documented latency and needs re-registration at boot and after update. | doc | S17, S7 |
| R26 | Android 16: broadcast priority is only honoured inside one process; `setImportantWhileForeground` is ignored. | Android 16+ | Do not rely on receiver priority (the SMS receiver sets 999). Harmless, but not a design input. | doc | S6 |
| R27 | Android 17: `SMS_RECEIVED` broadcasts can be withheld for up to 3 hours for messages that contain an OTP, for apps that are not the default SMS app or a companion, and standard-format OTP SMS are affected for apps targeting 37. Android 17 also enforces per-app memory limits (reason string `MemoryLimiter:AnonSwap` in `ApplicationExitInfo`) and extends background-activity-launch limits to `IntentSender`. | Android 17 | SMS Spark may be late for OTP-style messages on Android 17 (limit L9). Memory limiter means the Engine must stay small and report memory kills. | doc | S19, S20 |
| R28 | Play Console: every foreground service type declared for targetSdk 34+ needs a declaration with a demonstration video; `specialUse` is allowed only for narrow cases and is reviewed; automatic or background data sync does not qualify for `dataSync`. | Play distribution | Store flavour needs the declaration (section 9). Sideload does not. | doc | S18 |
| R29 | OEM task killers (dontkillmyapp.com): Xiaomi needs Autostart plus Battery saver "No restrictions" plus locking in Recents; Samsung has Sleeping and Deep sleeping lists (and, on some One UI versions, puts unused apps to sleep after about 3 days); OnePlus and Oppo need auto-launch plus battery optimisation off plus Recents lock, and OnePlus has been reported to reset the optimisation setting by itself; Vivo needs Autostart plus "High background power consumption"; Huawei needs App launch set to "Manage manually" and has a `PowerGenie` component that kills non-allowlisted apps. The source lists no developer-side fix for most of them. | OEM specific | Cannot be detected or fixed from the app. See section 5 and limit L1. | doc | S22 |
| R30 | How other automation apps cope: Tasker asks for a permanent foreground notification, battery optimisation exclusion, "reliable alarms", overlay permission, location "always" and OEM-specific steps (Samsung Unmonitored apps, Xiaomi Autostart, Huawei Recents lock), and points users to dontkillmyapp. Automate's developer says the OS cannot be prevented from killing a flow, a foreground service is normally killed only under memory pressure and then restarted, and sleep breaks timing operations such as delays. | App behaviour | Our approach matches theirs, plus durable intake and measured proof. The Automate remark confirms rule 3: delays do not survive sleep. | doc | S23, S24, S25 |

---

## 3. (b) Target architecture: the Always-On Engine

### 3.1 Design rules

1. **Persist first.** A receiver or alarm does the minimum (write an Inbox row, maybe poke the Engine) and returns. Execution happens later, from the Inbox.
2. **The Engine is a cache, the database is the truth.** Anything the Engine knows can be rebuilt from Room by the Reconciler.
3. **Every timer is an alarm.** No `delay()` longer than a few seconds is used to wait for a scheduled time.
4. **Run in the Engine when alive, in WorkManager when not.** Not both (R15).
5. **At-least-once intake, at-most-once side effects.** An event is never dropped silently. A Run interrupted mid-way is marked Crashed, not replayed, unless the Flow opted in (consistent with audit 01 section 5.6).
6. **Fail loud, retry quiet.** Publish refuses when a Spark cannot be armed. After publish, failures are retried with backoff and shown as Error, with notifications throttled.

### 3.2 Component map

```
                 ┌─────────────────────────── process: com.kaivoflow.app ───────────────────────────┐
 System          │                                                                                  │
 ─────────       │  AlwaysOnService  (FGS, specialUse, START_STICKY, ongoing notification)          │
 BOOT/UPDATE ───►│    ├─ EngineCore: RunDispatcher ◄── SparkInbox (Room, durable) ◄──┐              │
 TIME/TZ ───────►│    ├─ WebhookServer (loopback or LAN, token)                      │              │
 Alarm fire ────►│    ├─ DynamicReceivers: battery, screen, headset, power           │ writes       │
 SMS broadcast ─►│    ├─ HeartbeatWriter (+ engine_session row)                      │              │
 Geofence PI ───►│    └─ WakeLockManager                                             │              │
 NetworkCb PI ──►│                                                                   │              │
 Listener bind ─►│  Manifest receivers (short, goAsync): Boot, Alarm, SMS, Geofence,  ┘              │
                 │      NetworkCallback, PermissionStateChanged                                     │
                 │  KaivoNotificationListener (system-bound)                                        │
                 │  Reconciler  ◄── called by: app start, boot, update, time/tz, Pulse, watchdog,   │
                 │                  permission-changed, Engine start, user "Fix now"               │
                 │  WorkManager: WatchdogWorker (15 min), FlowRunWorker (fallback host)             │
                 └───────────────────────────────────────────────────────────────────────────────────┘
```

| Component | Hosts | Survives | Does not survive |
|---|---|---|---|
| **AlwaysOnService** (FGS) | Run executor, webhook listener, dynamic receivers, heartbeat, wake lock manager, Inbox consumer. | Leaving the app, screen off, recents swipe on stock Android, low-memory kill (restarted by `START_STICKY`), reboot (restarted by boot path after unlock). | Force-stop (R9), OEM cleaners that ignore foreground services (L1), reboot before first unlock (L7). |
| **SparkInbox** (Room table) | Every Spark event and schedule slot, with idempotency key and state Pending, Claimed, Done, Dropped. | Process death at any point. | Database corruption, storage full (both detected and reported). |
| **AlarmScheduler** + `AlarmReceiver` (manifest) | Schedules, the Pulse, retry timers for failed Spark registration, engine restart. | Doze (with the right alarm type), process death (the system holds alarms), screen off. | Reboot (re-armed by boot path), force-stop (R9), revoked exact-alarm permission (R16). |
| **Reconciler** | Compares desired state (Published Flows) with live state (alarms re-set idempotently, geofences, network callback, listener, Engine) and repairs. Applies missed-run policy. Idempotent and serialised by a mutex. | N/A, it is a function called from many places. | N/A |
| **WatchdogWorker** (WorkManager, periodic 15 min, KEEP) | Detects a stale heartbeat or a vanished Engine, runs the Reconciler, tries to restart the Engine, posts the "engine was dead" notice. | Process death, most OEM cleanups (jobs are stored by the system, not the app). | Force-stop, Restricted bucket (one run per day), Doze deferral (runs in maintenance windows). |
| **FlowRunWorker** (fallback Run host) | Runs consumed from the Inbox when the Engine is not running (for example a first event after reboot, before the Engine is up). | Same as the watchdog. | Quota limits on Android 16 (R15). |
| **KaivoNotificationListener** | Notification Spark source. Records `onListenerConnected`/`onListenerDisconnected`. | System rebinds in normal cases. | User revokes access; restricted settings (R23); OEM kills (L1). |
| Manifest receivers (Boot, SMS, Alarm, Geofence PI, Network PI) | Wake the process for a moment. | Process death (they cold-start the process). | Force-stop. |

### 3.3 When the Engine runs

The Engine runs if and only if at least one Published Flow has a Spark that needs a live process, or the user has switched "Always-on mode" to On. `EngineNeeds.compute(flows)` is pure Kotlin and unit-tested.

| Spark | Needs the Engine? | Why |
|---|---|---|
| Schedule (any frequency) | No for delivery (alarm), yes for Runs to execute in-process; the Engine is started by the alarm if absent. | An alarm can wake the process; Runs then execute in a short-lived Engine session or the fallback worker. |
| Webhook | Yes | Needs a listening socket. |
| Battery, screen, headset, power | Yes. Implemented in M1-B: `EngineNeeds` classifies Battery as Wanted (the WorkManager constraint fallback of 3.8 is not built). | These broadcasts are only delivered to runtime-registered receivers. |
| Wi-Fi | Yes (M1-B decision). A `NetworkCallback` registered inside the Engine; the `PendingIntent` variant of the first draft is not used. | The callback reports many capability updates per connection; `WifiTracker` reduces them to real edges, which a `PendingIntent` receiver cannot do without state. SSID needs location permission, and API 31+ needs `FLAG_INCLUDE_LOCATION_INFO`. |
| Bluetooth | No for ACL connect/disconnect (manifest receiver, verify on device); Engine optional. | See limit L8 and task AO-14. |
| Notification | Not strictly (system-bound listener), but keeping the process alive makes delivery faster and more reliable. Counts as "wants Engine". | The listener is itself a bound service, but a cached or killed process can lose events. |
| SMS | No (manifest receiver) | The broadcast wakes the app. |
| Geofence | No (geofence `PendingIntent`) | Delivered to the app. |
| Share, Manual, Boot | No | User initiated or one-shot. |

If only no-Engine Sparks are Published, the Engine stays off, the notification is not shown, and the Reliability Center says so ("Your Flows use Android's own wake-ups. No background service is needed."). If any "wants Engine" Spark exists, the Engine runs continuously. The notification is therefore honest: it exists only when needed.

States: `Stopped`, `Starting`, `Running`, `Degraded` (running, but a check is failing, for example notifications blocked), `Dead-suspected` (heartbeat stale and the process is gone), `Start-blocked` (the platform refused a background start; waiting for a user tap).

### 3.4 Foreground service type decision

| Candidate | Timeout | Start at boot (target 35+) | Prerequisite | Fit | Verdict |
|---|---|---|---|---|---|
| `dataSync` | 6 h per 24 h (target 35+) | **No** | None | Wrong semantic; Play says automatic background sync does not qualify | Rejected for the Engine. Time bomb: today safe only because targetSdk is 34. |
| **`specialUse`** | None | Yes | Manifest `<property>` subtype explanation | Honest ("on-device automation engine that hosts webhook listener and event receivers for user-published Flows") | **Chosen** |
| `systemExempted` | None | Yes | Must hold `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` at start | Reserved for system use; fragile when permission is revoked | Documented fallback if `specialUse` is ever blocked on a device. Not shipped. |
| `remoteMessaging` | None | Yes | None | Intended for cross-device text relay | Rejected (misuse risk, review). |
| `shortService` | About 3 min, never sticky | n/a | None | Too short | Rejected for the Engine. Candidate for emergency Run hosting later. |
| `connectedDevice`, `location`, `mediaPlayback`, others | Varies | Some banned | Hardware or permission prerequisites | Not the Engine's job | Not used. A future "location changed" Spark would get its own `location` service. |

Declarations (spec, not code):

- Manifest: replace `FOREGROUND_SERVICE_DATA_SYNC` with `FOREGROUND_SERVICE_SPECIAL_USE`. Keep `FOREGROUND_SERVICE`.
- One service, `AlwaysOnService`, `foregroundServiceType="specialUse"`, `exported="false"`, with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property.
- WorkManager's `SystemForegroundService` entry merged with `foregroundServiceType="specialUse"` so Run promotion uses the same type (R19). Using one type everywhere avoids any `dataSync` budget being consumed by chatty Flows (a Flow that runs 20 seconds every minute would burn 5.5 hours a day of a 6 hour cap).
- `FlowRunWorker.createForegroundInfo` passes `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34 and above and no type below.
- Delete `WebhookService` and `SmsTriggerService` (the webhook server becomes part of the Engine).
- `onTimeout(startId, fgsType)` is implemented even though `specialUse` has none today: it logs a reliability event, restarts through the Reconciler, and does not crash. `onTaskRemoved` arms a restart alarm (3.10).
- `START_STICKY`. `android:stopWithTask="false"`.

Notification requirements:
- Channel `kaivo.engine`, importance LOW (no sound), ongoing, not a progress bar, one stable id. Title: "KaivoFlow is watching N Flows". Text: next scheduled time and a problem count when relevant. Actions: Open, Reliability, Pause all. Tapping counts as user interaction, which is an FGS-start exemption (R8).
- Android 13+ needs `POST_NOTIFICATIONS`. The Reliability Center shows the state; publishing a Flow that needs the Engine without notifications allowed is a warning, not a blocker (the service still runs; where the notification is shown varies by OS version, so confirm on device, test T-NOTIF).
- Users can dismiss foreground notifications on recent Android versions. The Engine keeps running; the next heartbeat re-posts if the notification was removed (verify on device).

### 3.5 Starting the Engine from the background (the part that fails silently today)

Each start path, and which exemption it relies on (R8):

| Path | Exemption | Behaviour if refused |
|---|---|---|
| User opens the app, taps the notification, a tile or a widget | Visible state or UI interaction | Always works. Publish happens here, so **the Engine is first started at publish time**, the best possible moment. |
| `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `LOCKED_BOOT_COMPLETED` | Boot or update list entry; `specialUse` is not on the Android 15 banned types (R3) | Falls through to Pulse alarm. |
| `TIME_CHANGED`, `TIMEZONE_CHANGED` receivers | Listed | Same. |
| Exact alarm receiver (schedule, Pulse, restart alarm) | Exact alarm for a user-requested action | Needs an exact alarm permission or allowlist (R16). |
| Geofence transition | Listed | Same. |
| User turned off battery optimisation | Listed (master key) | Same. |
| SMS broadcast, notification listener event, Bluetooth, Wi-Fi network callback | **Not listed** | Do not start a service from them. Write the Inbox row, then try `startForegroundService` best-effort (works if the battery exemption is held), and always also enqueue the fallback worker. |
| WatchdogWorker | Not listed as an FGS exemption | Best effort; relies on the battery exemption or a Pulse alarm. |

Failure handling: `EngineController.start()` catches `ForegroundServiceStartNotAllowedException`, records a `FgsStartDenied` reliability event, sets the Engine state to `Start-blocked`, posts a heads-up notification on a high-importance channel ("KaivoFlow needs a tap to restart its background engine", tap = user interaction), and ensures the fallback worker mode is active so scheduled and event Runs still happen at WorkManager quality. Never swallow this exception with `runCatching` as `WebhookController.sync()` does today.

### 3.6 Wake lock policy

- No standing wake lock, ever, in the default mode (R20, battery).
- `RunWakeLock`: a `PARTIAL_WAKE_LOCK` named `KaivoFlow:run:<runId>` acquired when a Run starts, with a timeout equal to the Flow's run timeout plus 30 seconds (hard maximum 15 minutes), released in `finally`. Counted in a metric so the soak test can show total held time per day. Expected total is far below the 2 hour vitals threshold for normal Flows; the Reliability Center warns if a day exceeds 60 minutes.
- `IntakeWakeLock`: 20 seconds maximum around receiver work that writes to the Inbox (a screen-off SMS arrives, the receiver holds the CPU while the row is written and the Run is queued). `goAsync()` is used together with it; the receiver finishes as soon as the Inbox write commits.
- Alarm receivers: the alarm delivery holds the CPU for the receiver callback only. Anything longer must be handed to the Engine or an expedited worker before `onReceive` returns.
- Optional "Strict mode" (off by default, labelled with its battery cost): a partial wake lock held continuously while the Engine runs, for users who need sub-10-minute cadence in deep Doze and accept the battery cost and the vitals flag. It does not overcome OEM cleaners.

### 3.7 Network and Wi-Fi

- Doze suspends network for non-allowlisted apps; the Engine relies on the battery-optimisation exemption (R12). Without it, Runs that call the network while the screen is off will fail or wait for maintenance windows. This is the second most important Reliability Center check.
- A foreground service keeps the process in a valid lifecycle (R10). A Run never starts network I/O from a detached scope.
- Do not take a Wi-Fi lock (R21). The `WifiLock` low-latency mode only works with the screen on and the app in the foreground. Instead, the Reliability Center reports the Wi-Fi sleep policy as "unknown, test with T-WEBHOOK-LAN" because some OEM firmware lets the Wi-Fi radio sleep deeply or drops the connection with the screen off; this is device behaviour no app can change.
- Runs that need network declare it; if no validated network exists at fire time the Run waits in the Inbox (state Pending) up to the Flow's `maxWait` (default 15 minutes), re-tried on a `NetworkCallback` for "validated", then fails with a clear reason (`NoNetwork`). It is not silently retried forever.
- Data Saver: shown as a warning check; an allowlisted app is exempt unless Data Saver restricts it (R10 page notes unrestricted unless Data Saver is on).
- Webhook reach modes (the listener binds to an address; reachability from outside is a separate problem):
  1. **Loopback** (default): for tunnels that run on the same phone (a VPN mesh client or a tunnel app) and for `adb forward` testing.
  2. **LAN** (explicit opt-in switch): binds all interfaces; token still required; the UI shows the phone's LAN address and warns that the listener is plain HTTP.
  3. **Relay** (later, out of scope here): outbound subscription to a push topic, which survives carrier NAT. Mentioned only so the spec is honest that a phone on mobile data is rarely reachable (limit L6).

### 3.8 How each Spark type survives screen-off, Doze and kill

| Spark | Delivery mechanism | App UI closed | Screen off | Deep Doze | Process killed by system | Reboot | Force-stop | Notes |
|---|---|---|---|---|---|---|---|---|
| **Schedule** | AlarmManager (see 3.9). Slot written to Inbox; Run executes in Engine or fallback worker. | Yes | Yes | Yes with `AllowWhileIdle` (about 1 per 9 min) or alarm-clock class | Alarm persists; process is recreated; Engine restarted by exemption | Re-armed at boot after unlock | No (L2) | Precision mode is per Flow (3.9). |
| **Webhook** | Engine socket. Inbox row written before the 200/202 reply. | Yes (Engine) | Yes with battery exemption | Network available only with exemption (R12) | Engine restarts via sticky/Pulse/watchdog; down meanwhile | Down until unlock plus boot path | No | Reach limits L6. No Engine means no listener; the heartbeat tells the user. |
| **Notification** | System-bound `NotificationListenerService`. Writes Inbox row. | Yes | Yes | Yes (system delivers binder call) | System rebinds when it can; we track connection; `requestRebind` on disconnect | Bound after unlock | No | OTP notifications redacted on Android 15 (R24). Restricted settings on sideload (R23). |
| **SMS** (sideload flavour only) | Manifest receiver with `goAsync`, writes Inbox row, no foreground-service start (the Engine or the `FlowRunWorker` fallback runs the row). | Yes | Yes | Yes (broadcast wakes the process) | Cold-starts the process | After reboot the receiver works once the process can read the database (after unlock); a message arriving before first unlock is not readable by us (L7) | No | Android 17 can delay OTP-style messages up to 3 hours (R27, L9). |
| **Battery** (low, okay, charge connect and disconnect) | Dynamic receiver in the Engine (`EngineSources`), edge detection in `BatteryTracker` (power connect and disconnect carry no level, so the last known level is used). The Engine is Wanted for battery Sparks, so the WorkManager constraint fallback is not built; only the 15 minute Pulse and watchdog cover a dead Engine. | Yes | Yes | Edge may be seen late via fallback | Fallback only until Engine returns | Re-registered by Engine start | No | Level crossings with Engine are immediate; fallback may lag up to 15 minutes in deep Doze. |
| **Geofence** | `GeofencingClient` (Play services) with a `PendingIntent` receiver when available; `addProximityAlert` as the no-Google fallback. Transition is an FGS-start exemption. | Yes | Yes | Yes, latency 2 to 6 min (R25) | System holds the fence | Re-registered by Reconciler after unlock | No | Needs "Allow all the time" location. Re-registered after update and boot. Dwell option recommended. |
| **Wi-Fi** | `ConnectivityManager.registerNetworkCallback` inside the Engine (`EngineSources`); events to Inbox with a time-bucketed dedupe key. | Only with Engine | Only with Engine | Only with Engine | Down until Engine returns | Re-registered by Engine start | No | Replaces `NETWORK_STATE_CHANGED_ACTION`. SSID needs fine location (registration row says `PermissionMissing` otherwise). |
| **Bluetooth** | Manifest receiver for ACL connect and disconnect where the platform still delivers them to the manifest (confirm on device, test T-BT); otherwise Engine dynamic receiver. Upgrade path: Companion Device Manager presence observation (also an FGS-start exemption). | Yes (if manifest delivery confirmed) | Yes | Yes | Yes | After reboot | No | Needs `BLUETOOTH_CONNECT`. |
| **Headset plug** | Engine dynamic receiver only. | Only with Engine | Only with Engine | Only with Engine | Down until Engine returns | Down until Engine returns | No | Declared "wants Engine". |
| **Screen on/off, power** (future Sparks) | Engine dynamic receiver only. | Only with Engine | n/a | Only with Engine | Same | Same | No | |
| **Boot** | Boot receiver, fires once per boot after unlock. | n/a | n/a | n/a | n/a | After unlock | n/a | Recorded in `engine_session` as a boot. |
| **Share, Manual** | User action | n/a | n/a | n/a | n/a | n/a | n/a | Not part of 24/7. |

### 3.9 Schedules in detail

**Persisted state.** New table `schedule_state(flowId, sparkId, configHash, precision, nextDueAt, lastFiredAt, lastSlotKey, armedAt, armedMode, missedPolicy, lastStatus)`. `expected_run` (section 6.3) is the append-only evidence log.

**Precision, chosen per Schedule Spark (default `Exact` when allowed):**

| Mode | API | Doze | Cost and visible effects | Use when |
|---|---|---|---|---|
| `OnTime` | `setAlarmClock` | Fires at the exact time, the system leaves Doze | The system may show an alarm icon and list the next alarm; documented as battery-significant; allowed in small numbers | The few Flows that must be on time (wake-up reminder, medication, 08:00 report). Soft cap: 5 per app, with a warning above. |
| `Exact` (default) | `setExactAndAllowWhileIdle` | Fires but throttled to about once per 9 minutes per app in Doze (R11) | No icon | Most schedules. |
| `Flexible` | WorkManager periodic or one-shot with flex | Deferred to maintenance windows; subject to buckets and quotas (R14, R15) | Best for battery | Hourly digests, anything where late is fine; also the automatic fallback when the exact-alarm permission is missing. |

If `canScheduleExactAlarms()` is false, `Exact` and `OnTime` degrade to `Flexible` with an `ExactAlarmDenied` reliability event and a Reliability Center fix button. On the sideload flavour with `USE_EXACT_ALARM` (decision D6) this state cannot occur.

**Fire path (all precision modes except Flexible):**
1. `AlarmReceiver.onReceive` (goAsync plus `IntakeWakeLock`).
2. Compute the next slot with pure `ScheduleMath`. **Arm the next alarm first** and persist `nextDueAt`.
3. Insert an Inbox row with idempotency key `schedule:<flowId>:<slotEpochMs>`; a unique index makes a duplicate fire (alarm plus watchdog catch-up) a no-op.
4. Start or notify the Engine; if the Engine is not running or cannot start, enqueue the fallback worker with only the Inbox id.
5. Mark `expected_run` as Fired with the actual time and lateness.

**Intervals under 15 minutes** use the same chained exact alarm path, not periodic work. In deep Doze the effective floor is about 9 minutes unless Strict mode is on (limit L4). The editor validates the interval, shows the floor for the chosen precision, and never silently rounds (this fixes audit finding 04 section 6.2).

**Misfire policy**, per Spark: `Skip`, `RunOnce` (default for time-of-day), `RunAll` (capped at 5). Plus `maxLateness` (default 12 hours for daily, interval for shorter). The Reconciler applies it at boot, update, engine restart and watchdog time, using `expected_run` rows still Pending whose `dueAt` has passed. Skipped slots are logged as `Skipped` with reason.

**Time and zone changes.** The receivers for `TIME_CHANGED` and `TIMEZONE_CHANGED` run the Reconciler. Wall-clock schedules store an IANA zone, defaulting to "follow device" (this also removes the hard-coded Asia/Kolkata default from audit 07 P2-2). Daylight saving: a spring-forward gap fires at the next valid instant; a fall-back overlap fires once (first occurrence). Both need unit tests in `ScheduleMath`.

**Re-arming is cheap and idempotent.** Setting an alarm again with the same `PendingIntent` identity replaces it. The Reconciler therefore re-sets every alarm for every Published Flow without needing to query the system (the system does not list alarms to apps).

### 3.10 Self-healing matrix

| Event | What happens | Who repairs | Evidence recorded |
|---|---|---|---|
| Android kills the process (low memory, cached) | `START_STICKY` restarts the Engine with a null intent after a moment. Alarms persist. | System, then Reconciler on Engine start | `ApplicationExitInfo` reason; `engine_session` closed abnormally; `EngineRestart` event |
| User swipes the app from Recents (stock) | Process and FGS continue (`stopWithTask=false`). | Nothing needed | None |
| Swipe from Recents on an OEM that kills the process | Same as kill. `onTaskRemoved` arms an exact "restart" alarm 5 seconds ahead, then returns. If the process dies first the alarm still exists. | Alarm receiver (exempt start), else Pulse, else watchdog | `TaskRemoved` and `EngineRestart` events |
| Force-stop (settings, `am force-stop`, some OEM "clear all") | **Everything stops**, alarms and jobs are cancelled (R9). Nothing can restart until the user launches the app or the boot path runs. | User launch. On launch the Reconciler runs, `ApplicationStartInfo.wasForceStopped()` is read. | `ForceStopDetected` event; the gap is computed from the last heartbeat and every missed `expected_run` is marked Missed |
| Reboot | Alarms cleared (R18). After unlock, `BOOT_COMPLETED` starts Reconciler and Engine. Geofences and network callbacks re-registered. | Boot path | `BootRecovered` event with seconds from boot to recovery and seconds waited for unlock |
| Reboot, device not yet unlocked | Database unreadable (R22). Nothing runs until first unlock. Optional later work: Direct Boot mirror (AO-24). | User unlock | Gap measured; `BootRecovered.waitedForUnlock` |
| App update through `adb install -r` or store | Process killed; `MY_PACKAGE_REPLACED` delivered; Reconciler and Engine restart. | Update receiver | `PackageReplaced` event |
| Timezone or clock change | Reconciler recomputes `nextDueAt` and re-arms. | Time receivers | `ClockChanged` event with old and new offset |
| Exact-alarm permission revoked | App stopped by the system; exact alarms cancelled (R16). Next start detects it, degrades to Flexible, shows the fix button. | Reconciler on next start | `PermissionRevoked(exactAlarm)` |
| Battery optimisation turned back on (OnePlus is reported to do this alone) | Network and wake locks lost in Doze; background start exemption lost. | Reliability Center check, notification "Battery optimisation turned itself back on" | `PermissionRevoked(batteryExemption)` found by the Pulse |
| Notification permission revoked | FGS notification hidden; Flows still run. | Check | event |
| Foreground start refused | Engine goes `Start-blocked`; fallback worker mode; tap-to-restart notification. | User tap | `FgsStartDenied` |
| Notification listener disconnected | `requestRebind`; state shown; Reconciler retries on each Pulse. | Listener callbacks, Pulse | `ListenerDisconnected` and `ListenerReconnected` with duration |
| Crash loop | Engine start records a session row. If three abnormal ends happen within 10 minutes, the Engine starts in **safe mode**: Sparks enabled one Flow at a time with 30 seconds between, the Flow that triggers the crash is moved to Error with reason `CrashLoop`, and an alert is posted. | Engine startup guard | `CrashLoop` event with the offending Flow |
| Database corruption or storage full | Intake writes fail. Receivers post a direct notification as a last resort (not through the database). | Reliability Center check | `StorageFull` or `DbError` |
| Standby bucket demotion | Reported by `getAppStandbyBucket`; with the allowlist this should not limit the Engine. | Check | warning if Rare or Restricted |

The "session row with no clean stop" is our version of a crash journal: when the Engine stops on purpose it stamps `cleanStopAt`; any session without it at the next start means the previous session ended abnormally (concept parallel only; see 4.6).

### 3.11 Run execution inside the Engine

- The `RunDispatcher` claims Inbox rows, applies the per-Flow concurrency policy (queue by default; the in-memory `PerFlowEventQueue` is replaced by the Inbox, as audit 01 section 5.5 proposes), and starts Runs on the existing `FlowEngine`/`FlowRunner`.
- A Run row is written at start (Phase 1 already does `persistStart`), holds a lease refreshed by the heartbeat, and is marked `Crashed` by the Reconciler if the lease expires with no process (replaces the current "mark Canceled / Interrupted" approximation).
- Concurrency across all Flows is capped (default 3) to protect memory (Android 17 memory limiter, R27).
- A Run longer than the Engine's patience (Flow timeout, default 15 minutes) is cancelled with `Timeout`, never left running.
- When the Engine is off, the same Inbox is consumed by `FlowRunWorker` carrying only an Inbox id (this also removes the 10 KB WorkManager `Data` limit, audit 07 P2-6). The worker promotes itself with `setForeground` (type `specialUse`) when a Run is expected to exceed a few minutes, and implements `getForegroundInfo` for API 26 to 30.

### 3.12 Manifest and permission changes

| Change | Reason |
|---|---|
| Add `FOREGROUND_SERVICE_SPECIAL_USE`; remove `FOREGROUND_SERVICE_DATA_SYNC` | R1, R2, R4 |
| Add `AlwaysOnService` (specialUse, sticky, `stopWithTask=false`, not exported); remove `WebhookService` and `SmsTriggerService` | Section 3.4 |
| Merge `SystemForegroundService` with type `specialUse` | R19 |
| Add `AlarmReceiver` (not exported), a receiver for `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, and `LOCKED_BOOT_COMPLETED` for the optional Direct Boot mirror | R16, R22 |
| Keep `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `WAKE_LOCK`, `POST_NOTIFICATIONS` | Needed |
| `RECEIVE_SMS`, `SEND_SMS` and `SmsSparkReceiver` move to the `sideload` manifest; `play` removes all SMS permissions (incl. `READ_SMS`) with `tools:node="remove"` (M1-B) | Play restricts the SMS group; the Play build has no SMS Spark |
| `GeofenceReceiver` (not exported) replaces `MobileEventReceiver`; `BluetoothAclReceiver` (exported, ACL actions only) is new (M1-B) | AO-13, AO-14 |
| Remove `READ_SMS` | Unused, and Play-restricted (audit 04) |
| Flavours: `sideload` declares `USE_EXACT_ALARM` and `SCHEDULE_EXACT_ALARM`; `play` declares only `SCHEDULE_EXACT_ALARM` | R17, section 9 |
| Set `BootReceiver` `exported="false"` after checking on a device that system broadcasts still arrive | Hygiene; system senders are not blocked by `exported=false` (device check T-BOOT) |
| Fix the `tools:targetApi="36"` versus `targetSdk = 34` mismatch | Decision D8 |

### 3.13 Target SDK policy

targetSdk 34 keeps the dataSync timeout, the BOOT_COMPLETED type ban and the Android 17 SMS withholding off this app, which helps a personal sideloaded build today. That is a convenience, not a design. The Engine is built to be correct as if targetSdk were 36, so raising the target never changes behaviour. Task AO-23 raises compile and target SDK after the device matrix passes, as a separate gated step. Until then, tests run with the compat flags from section 7 to emulate the stricter rules.

---

## 4. (c) Flow publish lifecycle

### 4.1 States

| State | Meaning | Sparks armed? | Runs on Sparks? |
|---|---|---|---|
| **Draft** | Being edited. Not listening. Manual Run and Test Step work. Default for new and imported Flows. | No | Manual only |
| **Published** | The user's intent is "run this 24/7", and every Spark armed successfully. Executes the published revision, not the draft. | Yes | Yes |
| **Paused** | Published revision kept, Sparks disarmed on purpose, optionally until a time ("Pause for 8 hours"). | No | No |
| **Error** | The user published it, but one or more Sparks cannot currently be armed or delivered (permission revoked, alarm refused, credential gone, port in use, listener unbound). The Engine keeps retrying with backoff and returns the Flow to Published by itself. | Partially or none | Only Sparks still armed |

A Flow with Published revision R and unpublished edits (draft differs) is still Published, with a "Changes not live" badge. Edits to a Published Flow never change live behaviour until the user presses "Publish changes" (this is the draft versus published model in audit 07 section 4.3).

### 4.2 Transitions

```
        publish (validate ok, all Sparks armed)
 Draft ──────────────────────────────────────────► Published
   ▲                                                 │  ▲  │
   │ unpublish                                       │  │  │ registration or delivery failure
   │◄────────────────────────────────────────────────┤  │  ▼
   │                                       pause     │  │ Error ──(retry succeeds)──► Published
   │                                 ┌───────────────┘  │  │
   │                                 ▼        resume    │  │ pause / unpublish
   └──────────────── unpublish ◄── Paused ──────────────┘  │
                                       ▲────────────────────┘
```

| From | To | Trigger | Guard | Effects |
|---|---|---|---|---|
| Draft | Published | User taps Publish | Validation (4.3) has no errors; every Spark arms synchronously | Snapshot draft as revision N; set `publishedRevision`; arm all Sparks; start Engine if needed; write `flow_lifecycle_log`; show result sheet |
| Draft | Draft | Publish refused | A validation error or a Spark could not arm | Nothing armed; list reasons with fix buttons |
| Published | Published | "Publish changes" | New draft validates and arms | Atomic swap: arm new set, then disarm removed Sparks, then record revision N+1. If arming the new set fails, keep revision N running and report. |
| Published | Paused | User, or "pause until" timer, or "Pause all" | none | Disarm Sparks, keep state; if pause has an end, arm an alarm to resume |
| Paused | Published | User, or timer | Re-validation passes; Sparks arm | Missed-run policy is **not** applied to the paused interval; `expected_run` rows for the paused span are marked `Paused` |
| Published | Error | A Spark fails to arm or to keep delivering (Reconciler finds it) | none | Record per-Spark failure (`trigger_registration`), set `errorCode`, `errorSince`, schedule retry, notify (throttled) |
| Error | Published | Retry succeeds or the user fixes the cause | All Sparks armed | Clear error, notify "Back to normal" if the Flow had been in Error more than 5 minutes |
| Error | Paused / Draft | User | none | Cancel retries |
| Published, Paused, Error | Draft | User unpublishes | Confirmation if it has Sparks with pending Inbox rows | Disarm; queued Inbox rows for this Flow are marked Dropped with reason `Unpublished` |
| any | (deleted) | User deletes | Confirm | Disarm, drop Inbox rows, keep history per retention |

An import always produces Draft (audit 07 P1-12), regardless of what the file says.

Error is about delivery, not about Run outcomes. A Published Flow whose Runs fail still shows Published, with a "Failing" badge from the last N Runs. The optional setting "Pause after N consecutive failed Runs" (default off) moves it to Paused with reason `AutoPaused`. This avoids a Flow that texts someone from looping forever.

### 4.3 What Publish validates

Blocking errors (publish refused):

1. **Structure**: the shared `FlowValidator` from audit 07 section 4.2 (unique ids, endpoints exist, no illegal cycle, size limits, unknown node types, dangling Streams, missing credentials).
2. **At least one enabled non-Manual Spark.**
3. **Per-Spark configuration**:
   - Schedule: valid time, zone, cron or interval; interval at or above the floor for the chosen precision (message states the floor).
   - Webhook: path normalises and is unique across all Published Flows; method valid; the token exists.
   - Notification: regex compiles (bounded-time matcher); at least one filter (app or text) unless the user ticks "I want every notification" (storm guard).
   - SMS: at least a sender or text filter or an explicit "all messages" acknowledgement.
   - Geofence: coordinates and radius valid; radius under 100 m produces a warning.
   - Bluetooth: a device chosen.
   - Battery: threshold in range.
4. **Permissions that cannot work without a grant, at publish time**: `RECEIVE_SMS` for an SMS Spark; fine location plus "all the time" for a Geofence Spark; notification access bound for a Notification Spark.
5. **Arming dry run succeeds**: alarms accepted, network callback registered, geofence added, listener bound, webhook route bound on a free port, Engine startable (we are in the foreground, so exemption R8 applies).

Warnings (publish allowed after the user taps "Publish anyway", each with a fix button):

- Battery optimisation not exempt (Critical for Engine Flows; screen-off webhook will not have network).
- Exact alarm not granted (schedule will be Flexible).
- Notifications blocked (Engine notice hidden).
- OEM autostart unverified.
- A side-effect Step (SMS, HTTP write) in a Flow whose Spark is attacker-controllable (SMS, notification, webhook, share) without an approval Step; plus a loop warning when an SMS Spark Flow sends SMS (sender could be the user's own number).
- Flow uses Steps that need foreground only (clipboard read, open app, from audit 04) on an unattended Spark.
- Another Published Flow already listens to the same notification or SMS pattern (possible double action).

### 4.4 What the user sees

- **Flow list row**: state chip (Draft grey, Published green with a pulsing "Live" dot only while the Engine is healthy, Paused amber, Error red), next run time and precision ("Next 08:00, exact"), last Run result, "Changes not live" badge, "Failing" badge, "Late 3 times this week" badge.
- **Publish sheet** (shown on tap): a checklist with ticks for each validation, then a result list per Spark: "Armed: next fire 08:00 Mon", "Armed: URL https://…/hook/x (LAN)", "Armed: listener connected", "Needs permission: allow notification access" with fix button. If everything is green, one line: "Live. KaivoFlow will keep this running in the background."
- **Error state**: the Flow card names the failing Spark and the plain reason, shows "Retrying in 5 min" and a "Fix now" button that runs the matching deep link and triggers the Reconciler.
- **Paused state**: shows "Resumes at 18:00" if timed.
- **Notifications**: channel "Flow problems" (default importance) for: Flow entered Error, Engine dead and recovered with missed runs, permission revoked. Throttled to one per Flow per hour per kind. Channel "Engine" (low) for the persistent notice.
- **After reboot or update** (optional setting, default on): a quiet notification "Restored 6 Flows after restart".
- **Home banner** when health score is below 70 with a button to the Reliability Center.

### 4.5 Persistence

Columns added to `flows` (indexed): `lifecycle` (DRAFT, PUBLISHED, PAUSED, ERROR), `publishedRevision` (int, nullable), `lifecycleReason` (enum), `lifecycleSince`, `pausedUntil` (nullable), `errorCode`, `errorSince`, `retryCount`, `nextRetryAt`. `isActive` is replaced by `lifecycle in (PUBLISHED, ERROR)`; a migration maps `isActive=true` to PUBLISHED with `publishedRevision` = the current JSON saved as revision 1 into `flow_versions` (audit 07 section 4.3).

New tables (all need Room migrations and `MigrationTestHelper` tests):

| Table | Purpose |
|---|---|
| `flow_versions(flowId, revision, savedAt, label, flowJson)` | Published revisions (also gives audit 07's history). |
| `trigger_registration(flowId, sparkId, kind, state, detail, since, retryCount, nextRetryAt)` with state `ARMED`, `PENDING`, `FAILED_TRANSIENT`, `FAILED_PERMANENT` | Per-Spark truth. |
| `flow_lifecycle_log(id, flowId, at, from, to, reason, actor)` with actor `user`, `engine`, `reconciler`, `import` | Why a Flow changed state. |
| `schedule_state`, `expected_run` | Section 3.9, 6.3. |
| `spark_inbox(id, flowId, sparkId, kind, payload or fileRef, receivedAt, state, dedupeKey UNIQUE, runId)` | Section 3.1. |
| `engine_session`, `engine_heartbeat`, `reliability_event` | Section 6. |

### 4.6 Behaviour borrowed in concept from n8n's activation lifecycle (own words)

Read only for behaviour (`packages/cli` active-workflow manager, activation error store, crash journal; `packages/core` scheduled task manager). The relevant ideas, restated:

- A workflow carries a persisted "active" flag plus a pointer to the version that is live. Edits do not touch what is running.
- At process start, all active workflows are re-registered, in small batches, one after another. Each registration is logged before it starts, so a crash during registration identifies the culprit.
- Registration has two outcomes with different handling. A permanent refusal (permission, policy) is logged once and not retried, so a restart does not re-alert. A transient failure goes into a retry queue with exponential backoff (1 second doubling to a ceiling of one day), and the backoff continues rather than restarting if a trigger dies again soon after recovering; it resets only after the trigger has stayed healthy for a stable period (one minute).
- If a trigger fails while running, the workflow is removed from the active-in-memory set, the error is recorded for the UI, the error workflow (if configured) is run, and the workflow is queued for reactivation.
- Deactivation removes triggers idempotently, so repeating it after a failed removal is safe.
- A crash journal file is created at startup and removed on clean shutdown; finding it at the next start means the last session crashed, and startup pauses briefly to slow a crash loop.
- Scheduled tasks are keyed by their upcoming fire time, which is the identity of a firing.

What KaivoFlow does differently, and why: retry timers are alarms or WorkManager jobs (in-memory timers do not survive sleep or a kill); the crash journal is a database session row plus `ApplicationExitInfo` (more informative and survives); backoff is capped at one hour (a phone user will not wait a day for a Flow to recover); the schedule identity is `(flowId, slotEpoch)` in a unique Inbox index; there is no multi-instance leader election.

Backoff for registration retries: 5 s, 15 s, 1 min, 5 min, 15 min, then every hour, with a stable period of 10 minutes before the counter resets. Permanent failures (`PermissionMissing`, `InvalidConfig`, `CredentialMissing`) are not retried; they wait for a user fix or for the matching permission-changed signal.

---

## 5. (d) Reliability Center (UI spec)

### 5.1 Purpose and principles

One screen that answers "will my Flows run while I am not looking, and if not, why?" It replaces the static Settings section in `BatteryHealth` and extends `PermissionCentreScreen`. Principles: measured over guessed; every red or amber item has one tap that goes to the right system screen; the plain explanation says what breaks, not what the setting is called; unverifiable items are never green; it re-checks when the user comes back from Settings (on resume) and on a 30 second timer while visible.

### 5.2 Layout

1. **Header**: health score ring (0 to 100) with a band label and one sentence ("At risk: Android may pause your Flows when the screen is off"). Under it, three chips: Engine (Running, Stopped, Blocked), Last heartbeat ("12 s ago"), Published Flows (6, 1 in Error).
2. **Fix first**: the top three failing checks by weight, each as a card with a primary "Fix" button.
3. **Device checks**: the full list (5.3), grouped Critical, Recommended, Informational, each card collapsible.
4. **Flow health**: per Published Flow: state chip, next run, on-time percentage for 14 days, last late or missed, a mini 14-day strip (green on time, amber late, red missed, grey skipped or paused).
5. **Event log** (tab): section 5.5.
6. **Self-test** (button): section 6.5.
7. Footer: OEM guide link (dontkillmyapp page for this phone's brand, opened in the browser), "Export diagnostics" (writes the reliability dump JSON), "What can't be guaranteed" link to the limits text (section 8, shipped in-app).

### 5.3 Live checks

Each check returns `OK`, `WARN`, `FAIL`, `UNKNOWN` or `NOT_NEEDED` (no Published Flow uses it). `NOT_NEEDED` checks are hidden and removed from the score.

| # | Check | How it is tested | Plain explanation shown | One-tap fix (deep link, with fallback) | Weight |
|---|---|---|---|---|---|
| C1 | **Service alive (heartbeat)** | Age of the last `engine_heartbeat` against the expected pulse interval (3.5, 6.1); Engine state; `dumpsys`-equivalent: `ActivityManager.getRunningServices` is deprecated, so rely on the heartbeat. OK under 2 pulse intervals, WARN under 4, FAIL beyond or when state is Start-blocked. | "KaivoFlow's background engine is the part that listens for SMS, webhooks and notifications. It last reported in 4 minutes ago." | "Restart engine" (starts the service; if refused shows the tap-to-restart notification), then Reconciler | 25 |
| C2 | **Battery optimisation exempt** | `PowerManager.isIgnoringBatteryOptimizations(pkg)` | "Without this, Android cuts the network and pauses KaivoFlow whenever the screen is off for a while." | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with the package URI (needs the declared permission); fallback `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; fallback app details | 20 |
| C3 | **Notifications allowed** | `NotificationManagerCompat.areNotificationsEnabled()` plus the `kaivo.engine` channel importance not NONE | "Android may hide the engine notice. Flows still run, but you will not see problems." | `Settings.ACTION_APP_NOTIFICATION_SETTINGS` (or `ACTION_CHANNEL_NOTIFICATION_SETTINGS` for the engine channel); runtime request on Android 13+ when still askable | 10 |
| C4 | **Exact alarms allowed** | `AlarmManager.canScheduleExactAlarms()` (always OK on the sideload flavour with `USE_EXACT_ALARM`); only checked if a Published Flow uses Exact or OnTime | "Without this, 'every day at 08:00' becomes 'somewhere around 08:00 to 09:00'." | `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` with package URI; re-check on resume and on the permission-changed broadcast | 10 |
| C5 | **Background restrictions** | `ActivityManager.isBackgroundRestricted()` is false; `UsageStatsManager.getAppStandbyBucket()` not Rare or Restricted (needs no extra permission for the app's own bucket) | "Android has been told to restrict KaivoFlow in the background (or has parked it because it has not been opened for days)." | `ACTION_APPLICATION_DETAILS_SETTINGS` for the package with instructions to open Battery then choose Unrestricted; open the app once to leave Rare | 10 |
| C6 | **Notification listener bound** | Notification access granted (`NotificationManagerCompat.getEnabledListenerPackages`) **and** `onListenerConnected` last event is newer than `onListenerDisconnected`; plus the self probe (6.5) result age. Only if a Notification Spark is Published. UNKNOWN if granted but never connected. | "KaivoFlow can only react to notifications while Android has connected it to the notification system." | `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` for our component on Android 11+, else `ACTION_NOTIFICATION_LISTENER_SETTINGS`; if the toggle is unavailable show the "Allow restricted settings" steps (R23); a "Reconnect" button calls `requestRebind` | part of C8 weight group |
| C7 | **SMS permission** | `RECEIVE_SMS` granted (and `SEND_SMS` if a Send Step exists in a Published Flow). Informational line on Android 17: "Some one-time-code messages may arrive up to 3 hours late." | "Without this, an SMS Spark never sees a message." | Runtime request; if permanently denied, app details | part of C8 |
| C8 | **Per-Spark permissions summary** | For each Published Flow, list permissions its Sparks and Steps need (SMS, background location "all the time", Bluetooth connect, contacts, calendar) and which are missing. Weighted across C6, C7, C9. | "Flow 'Gym check-in' needs location 'Allow all the time'." | Per row: the right runtime request or app details | 15 (group) |
| C9 | **Background location** | For Geofence Sparks: fine location, background location granted ("all the time"), location services on, Wi-Fi scanning on | "Geofences stop firing when location is limited to 'while using the app'." | Runtime request flow, then app details; `ACTION_LOCATION_SOURCE_SETTINGS`; Wi-Fi scanning settings | in C8 |
| C10 | **OEM autostart and background launch** | Cannot be read. State is `UNKNOWN` until the user taps "I did this" (stored) **and** the soak test (6.5) passes; then `OK`. Brand-specific text from `BatteryHealth.oem()` (Xiaomi, Samsung, OnePlus, Oppo, Realme, Vivo, Huawei/Honor). | Brand sentence from section 2 row R29. | Component intent from `BatteryHealth`, validated by `resolveActivity`, with fallback to app details; "Open guide" to dontkillmyapp | 5 |
| C11 | **Data Saver and background data** | `ConnectivityManager.restrictBackgroundStatus` | "Data Saver blocks network for background Flows." | `Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` (package URI), fallback `ACTION_DATA_USAGE_SETTINGS` | 3 |
| C12 | **Battery saver on** | `PowerManager.isPowerSaveMode` | "Battery saver slows or stops background work." | `Settings.ACTION_BATTERY_SAVER_SETTINGS` | 2 |
| C13 | **Force-stop history** | `ApplicationStartInfo.wasForceStopped()` for the last start (Android 15+); `ApplicationExitInfo` last reasons list (API 30+) | "KaivoFlow was force-stopped at 02:14. Nothing ran until you opened it." | Not fixable; offers "Why did this happen?" and the OEM guide | informational (score effect through measured part) |
| C14 | **Time and zone automatic** | `Settings.Global.AUTO_TIME` and `AUTO_TIME_ZONE` | "Wrong clock means schedules fire at the wrong time." | `Settings.ACTION_DATE_SETTINGS` | 3 |
| C15 | **Webhook listener** | Only if a Webhook Spark is Published: bound port, bind mode, LAN address, reach mode; self probe result | "Your webhook is reachable only from this phone (loopback)." or "Reachable on 192.168.1.20:8787 (same Wi-Fi only)." | "Open webhook settings" (switch to LAN; copy URL) | informational |
| C16 | **Storage and database** | Free space, database size, last integrity check | "Low storage can stop KaivoFlow from recording events." | `Settings.ACTION_INTERNAL_STORAGE_SETTINGS` | 2 |
| C17 | **Memory kills** | `ApplicationExitInfo` count of `REASON_LOW_MEMORY` and, on Android 17, the memory-limiter description in 7 days | "Android ended KaivoFlow 3 times this week to free memory." | "Reduce memory use" (lower Run concurrency) | informational |

Weights sum to 100 over the applicable checks; non-applicable ones are dropped and the rest renormalised.

### 5.4 Health score

Pure Kotlin `HealthScorer`, unit-tested.

```
configScore  = 100 * sum(weight_i * f(state_i)) / sum(weight_i of applicable checks)
               with f(OK)=1, f(WARN)=0.5, f(UNKNOWN)=0.6, f(FAIL)=0
measuredScore = available when >= 10 expected Runs exist in the last 14 days:
               100 * onTimeRatio  -  15 * min(engineDeadIncidents7d, 3)  -  10 * min(missedRuns7d, 3)
final        = if no measurement: min(configScore, 85)      // "not yet proven"
               else               min(configScore, measuredScore)
caps         = heartbeat FAIL: final <= 30 ; Engine Start-blocked: final <= 40 ;
               OEM check UNKNOWN on a known-aggressive brand (Xiaomi, Oppo, Vivo, Huawei, OnePlus, Samsung): final <= 85
bands        = 90+ Reliable, 70-89 Mostly reliable, 40-69 At risk, below 40 Unreliable
```

On-time means a scheduled Run started within tolerance of its due time: 60 seconds for Exact and OnTime, the flex window for Flexible. Weights, caps and bands are initial values, to be tuned after the first soak results (AO-22). The cap rule is what keeps the score honest on phones where nothing can be verified.

### 5.5 Event log (missed and late runs and everything that explains them)

Table `reliability_event(id, at, kind, flowId?, sparkId?, severity, expectedAt?, actualAt?, detailJson, acknowledged)`.

Kinds: `MissedRun`, `LateRun`, `SkippedRun` (policy), `EngineDead` (interval with start and end), `EngineRestart`, `EngineStopped(clean)`, `ProcessDied(reason from ApplicationExitInfo)`, `ForceStopDetected`, `BootRecovered`, `PackageReplaced`, `ClockChanged`, `FgsStartDenied`, `ListenerDisconnected`, `ListenerReconnected`, `PermissionRevoked(which)`, `PermissionGranted(which)`, `DroppedEvent(reason)`, `InboxBacklog`, `SelfTestResult`, `CrashLoop`, `StorageFull`.

Late and missed definitions: a scheduled Run is **Late** if it started after `dueAt + tolerance` and **Missed** if no Run started by `dueAt + max(5 min, 10% of the interval)` or the Reconciler found its slot expired while the Engine was dead. A missed slot that the misfire policy then runs shows as "Missed, caught up at 08:41". Event Sparks (SMS, notification, webhook) have no expected time, so they log `DroppedEvent` only when the Inbox refuses or drops one (queue full, Flow unpublished, dedupe).

UI: reverse-chronological list grouped by day; filters by Flow, kind and severity; each row has a one-line plain explanation ("Missed 08:00. KaivoFlow was stopped by Android from 07:12 to 08:41 (low memory). Ran once at 08:41 as set.") and an "Explain" expander showing raw fields; tapping a Flow-related row opens that Flow. Retention: 90 days or 5000 rows, whichever is smaller; "Export" writes JSON. Acknowledging clears the badge on the Reliability tab.

---

## 6. (e) Heartbeat and self-test

### 6.1 Heartbeat

**What is written** (single row `engine_heartbeat`, updated in place): `sessionId`, `lastBeatWall`, `lastBeatElapsed`, `bootCount` (`Settings.Global.BOOT_COUNT`), `processStart`, `engineState`, `pendingInbox`, `armedAlarms`, `listenerLastConnected`, `listenerLastDisconnected`, `lastAlarmFiredAt`, `wakeLockMillisToday`, `memoryClass`, `appVersion`.

**When it is written** (low rate by design, because a wake every minute with the screen off would drain the battery):
1. On every wake: any alarm, any Inbox event, any Run start or end, any receiver.
2. From the **Pulse alarm**: every 10 minutes (`setExactAndAllowWhileIdle`, so in Doze it lands about every 9 to 15 minutes).
3. From an in-process 30 second ticker only while the screen is on or the device is charging.
4. From the 15 minute WatchdogWorker.

Because the beat is event-driven, "no beat for 3 minutes" means nothing with the screen off. The dead threshold is therefore: **no beat for 2.5 Pulse intervals (25 minutes) in screen-off mode, 3 minutes in screen-on mode**. Death is always determined after the fact as well (6.2).

**Engine session.** Table `engine_session(id, startedAt, startReason, bootCount, cleanStopAt, endReason)`. Written at Engine start; `cleanStopAt` is set when the Engine stops on purpose (user turns off, no Flows need it). A previous session without it means the process died. `ApplicationExitInfo` is read at each start to attach the reason (user requested, low memory, crash, ANR, excessive resource, other incl. memory limiter). `dumpsys activity exit-info <package>` shows the same data for tests.

### 6.2 Dead-engine detection and alert

Detection layers:

1. **After the fact (always works)**: at any start (Engine, app, Reconciler) compare `lastBeatWall` to now. A gap larger than the dead threshold without a clean stop is an `EngineDead` event with start and end. The Reconciler then finds every `expected_run` row with `dueAt` inside the gap and not Fired, marks it Missed, and applies the misfire policy. It notifies once: "KaivoFlow's engine was not running from 07:12 to 08:41. 2 scheduled runs were missed, 1 was caught up."
2. **Pulse alarm** (every 10 minutes): if the Engine is not alive (`engineState != Running` or the process just started cold), start it (exact-alarm exemption, R8) and run the Reconciler.
3. **WatchdogWorker** (every 15 minutes, independent of alarms): same check. If it finds the Engine down and the start is refused, it posts the tap-to-restart notification. Two independent mechanisms because force-stop-like cleanups sometimes clear alarms and not jobs, or the reverse.
4. **Optional external dead-man's switch** (setting off by default): every Pulse also issues an HTTPS GET to a URL the user supplies (for example a self-hosted or free uptime monitor). If pings stop, that service alerts the user while the phone is dead. This is the only way to be alerted *while* the engine is dead; nothing on the phone can do that.

Alert policy: at most one notification per kind per hour; severity high when runs were missed, low when only a restart occurred; never an alert for a clean stop.

### 6.3 Expected versus actual run log

`expected_run(id, flowId, sparkId, slotKey, dueAt, armedAt, state, firedAt, runId, latenessMs, reason)` with state `PENDING`, `FIRED`, `LATE`, `MISSED`, `SKIPPED`, `PAUSED`, `SUPERSEDED` (edited or unpublished).

- Created whenever `schedule_state` arms a slot (before the alarm is set).
- `firedAt` and `runId` set when the Inbox row for the slot is claimed by a Run.
- Marked by the Reconciler and the Pulse when `dueAt + grace` passes with state still `PENDING`.
- Retention 30 days, then compacted into daily aggregates (counts, p50, p95, max lateness) kept 180 days, so the 14-day on-time figure and the strips are cheap.
- The expected-vs-actual comparison is also what the self-test (6.5) and the soak test (section 7) read.

### 6.4 Evidence for event Sparks

Event Sparks have no "expected" time, so they get probes instead of schedules:

- **Notification probe**: the app posts its own low-importance test notification on a hidden channel and expects `onNotificationPosted` within 3 seconds. Runs on demand and once a day (from the Pulse) and feeds C6.
- **Webhook probe**: HTTP request to the own listener with the token (loopback), expecting a 2xx and an Inbox row marked as probe.
- **Network callback probe**: register and unregister a throwaway callback; confirms the system accepts it.
- **SMS and Bluetooth**: only static checks (permission, receiver enabled via `PackageManager.getComponentEnabledSetting`). An SMS cannot be self-injected safely.
- **Battery**: edge detector is unit-tested; the live probe compares the last sticky battery level with the last level the Engine saw (a mismatch means the dynamic receiver is not receiving).

### 6.5 Self-test suite

Run from the Reliability Center. Uses an internal hidden Flow that writes a marker and posts a low-importance notification, never visible in the Flow list.

| Test | What it does | Pass |
|---|---|---|
| ST1 Alarm | Arms an `Exact` alarm 60 s ahead and a `OnTime` alarm 90 s ahead; measures lateness | Within 60 s |
| ST2 Inbox round trip | Inserts a synthetic Spark event; waits for the internal Run | Run starts within 5 s (Engine) or 30 s (fallback worker) |
| ST3 Engine restart | Stops the Engine service on purpose without a clean-stop mark, then waits for the Pulse or watchdog to bring it back (up to 16 minutes, runs in background and reports later) | Back within 16 minutes |
| ST4 Webhook | Loopback request with and without token | 2xx with token, 401 without |
| ST5 Listener | Notification probe | Event within 3 s |
| ST6 Foreground start from background | Sends the app to background, fires an exact alarm 30 s later that tries to start the Engine if it is stopped | Engine running (confirms exemption path on this phone) |
| ST7 Overnight soak (optional, 8 hours) | Arms one Exact and one Flexible schedule every 30 minutes with the screen off and the phone unplugged; morning report with lateness p50, p95, max, number of Engine deaths and their reasons | The pass criteria in section 7.4. A pass also upgrades C10 (OEM) from UNKNOWN to OK for 30 days |

---

## 7. (f) Device test plan (adb)

### 7.1 Setup

- Use `com.kaivoflow.app.debug` (debug build) or `com.kaivoflow.app`; `$PKG` below. Device on USB with USB debugging. Replace `$IP` with the phone's Wi-Fi address.
- Debug builds include a **diagnostics receiver** (task AO-21): `adb shell am broadcast -n $PKG/com.kaivoflow.app.debug.DiagReceiver -a com.kaivoflow.DIAG_DUMP` writes `reliability-dump.json` (heartbeat, sessions, `expected_run`, `reliability_event`, schedule state, registrations, Inbox) to the app's external files dir; `adb pull /sdcard/Android/data/$PKG/files/reliability-dump.json`.
- Build a **Beacon Flow** per Spark type. Each does one thing: append a row to a Data table and post a notification "Beacon <spark> <time>". Schedule Beacon runs every N minutes; event Beacons are triggered by the commands below.
- Log: `adb logcat -v time -s KaivoEngine KaivoAlarm KaivoInbox KaivoRun` (log tags to be added in AO-15).
- Useful state dumps: `adb shell dumpsys alarm | grep -B2 -A8 $PKG`; `adb shell dumpsys jobscheduler $PKG`; `adb shell dumpsys activity services $PKG` (shows foreground type); `adb shell dumpsys deviceidle` (current Doze state, allowlist); `adb shell dumpsys activity exit-info $PKG`; `adb shell am get-standby-bucket $PKG`; `adb shell dumpsys power | grep -E "mWakefulness|Wake Locks"`.

### 7.2 Condition commands

| Condition | Command | Undo / note |
|---|---|---|
| Screen off | `adb shell input keyevent KEYCODE_SLEEP` (223) | Wake: `KEYCODE_WAKEUP` (224). Confirm with `dumpsys power`. |
| Unplug (simulate battery) | `adb shell dumpsys battery unplug` | `adb shell dumpsys battery reset` |
| **Force deep Doze** | `adb shell dumpsys battery unplug` then `adb shell dumpsys deviceidle force-idle` | Check `adb shell dumpsys deviceidle get deep` returns IDLE; exit `adb shell dumpsys deviceidle unforce` then `dumpsys battery reset`. Light Doze: `dumpsys deviceidle force-idle light`. |
| Standby bucket | `adb shell am set-standby-bucket $PKG rare` (also `restricted`, `frequent`, `working_set`, `active`) | `adb shell am get-standby-bucket $PKG`. `adb shell am set-inactive $PKG true` for plain App Standby. |
| Battery exemption off / on | `adb shell dumpsys deviceidle whitelist -$PKG` / `+$PKG` | Verify with `adb shell dumpsys deviceidle whitelist`. Whether user-level state matches the UI check must be confirmed per device. |
| Background restricted | `adb shell cmd appops set $PKG RUN_ANY_IN_BACKGROUND deny` | `allow` to undo. Confirms C5 turns red. |
| Exact alarm permission off / on | `adb shell cmd appops set $PKG SCHEDULE_EXACT_ALARM deny` / `allow` | Verify behaviour per device. Expect the revocation to stop the app (R16). |
| Kill (safe) | `adb shell am kill $PKG` | Kills only processes that are safe to kill; an app with a foreground service is usually left alone. Used for the cached case. |
| **Process death with sticky restart** | `adb shell am crash $PKG` | Simulates a crash of the app process. Expect `START_STICKY` restart of the Engine and an `ApplicationExitInfo` record. |
| **Force-stop (documented limit)** | `adb shell am force-stop $PKG` | Expected: nothing runs afterwards until launch. Pass criterion is detection and logging after launch, not survival. |
| Reboot | `adb reboot`; wait: `adb wait-for-device; adb shell getprop sys.boot_completed` returns 1 | Unlock without PIN: `adb shell wm dismiss-keyguard` (or `input keyevent 82`). With a PIN set, unlock by hand and record the delay. |
| App update | `adb install -r app/build/outputs/apk/debug/app-debug.apk` | Expect `MY_PACKAGE_REPLACED` handling and Engine restart. |
| Simulate boot broadcast only | `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p $PKG` | Receiver logic only, not a real boot. |
| Android 15 rules on a lower target | `adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS $PKG`; `adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS $PKG` | Shorten the timeout: `adb shell device_config put activity_manager data_sync_fgs_timeout_duration 60000` (for the negative test that proves we are not on `dataSync`). Android 16 job quota rules: `adb shell am compat enable OVERRIDE_QUOTA_ENFORCEMENT_TO_FGS_JOBS $PKG`. |
| Timezone change | Change zone in Settings (real), or `adb shell am broadcast -a android.intent.action.TIMEZONE_CHANGED -p $PKG` (receiver logic only) | Verify next due time recomputed. |
| Clock change | Turn off automatic time, move the clock +1 h and back | Receiver `TIME_CHANGED` path. |

### 7.3 Event injection

| Spark | How to fire it |
|---|---|
| Webhook, loopback | `adb forward tcp:8787 tcp:8787` then `curl -i -H "Authorization: Bearer $TOKEN" -d '{"a":1}' http://127.0.0.1:8787/hook/<path>` |
| Webhook, LAN | From a laptop on the same Wi-Fi with LAN mode on: `curl -i -H "Authorization: Bearer $TOKEN" -d '{"a":1}' http://$IP:8787/hook/<path>` |
| Notification | `adb shell cmd notification post -S bigtext -t "Title" tagx "Body text"` (the sending app is the shell package, so filter the Beacon on that) |
| SMS | Real phone sends an SMS to the test phone. On an emulator: `adb emu sms send 5551234 "hello"`. Repeat with an OTP-style message on Android 17 to measure the delay (L9). |
| Battery | `adb shell dumpsys battery set level 15`, `adb shell dumpsys battery set ac 0`, `adb shell dumpsys battery set ac 1`, then `adb shell dumpsys battery reset` |
| Wi-Fi | `adb shell svc wifi disable` then `enable` (Beacon filters on the SSID) |
| Bluetooth | Connect and disconnect a real paired device (headset); `adb shell svc bluetooth disable/enable` only tests the adapter, not ACL |
| Headset | Plug a wired headset or use a Bluetooth audio device for the media route |
| Geofence | Emulator: `adb emu geo fix <lon> <lat>`. Real phone: mock-location app set with `adb shell appops set <mock-app> android:mock_location allow`, or walk |
| Schedule | A Beacon with an interval of 2 minutes (Exact) and one daily at now + 5 minutes (OnTime) |

### 7.4 Pass criteria per Spark type

The reference device is a Pixel (stock Android) and the second device is the owner's phone with its OEM steps applied. "P" must pass on stock; on an OEM phone the same criteria must pass after the OEM steps in section 8. Times are from the moment the event is injected or the slot is due.

| Spark | Screen off, 10 min | Forced deep Doze (battery exempt) | Standby bucket `rare` | After `am crash` | After reboot (no PIN) | After `install -r` |
|---|---|---|---|---|---|---|
| Schedule Exact | Fires within 60 s | Within 60 s for the first; subsequent slots under 9 minutes apart can slip (L4), others within 60 s | Within 60 s | Next slot fires within 60 s of its time, and any slot missed during the gap is marked Missed or caught up | First slot after boot fires; slots missed during boot are handled by policy | Next slot fires; no duplicate fire |
| Schedule OnTime | Within 60 s | Within 60 s | Within 60 s | Same as Exact | Same | Same |
| Schedule Flexible | Within flex window plus 15 min | May slip to a maintenance window (informational) | May slip (informational) | Same | Same | Same |
| Webhook | 2xx and Run started in 3 s | 2xx and Run in 5 s (needs exemption) | 2xx and Run in 5 s | Listener back within 60 s (sticky) or by the next Pulse; no requests lost after it is back | Listener up within 3 min of unlock | Listener up within 60 s |
| Notification | Run in 5 s | Run in 5 s | Run in 5 s | Listener reconnects and Run in 5 s after reconnect (log `ListenerReconnected`) | After unlock within 3 min | Within 60 s |
| SMS | Run in 10 s | Run in 10 s | Run in 10 s | Run in 10 s (receiver cold-starts the process) | After unlock within 3 min; SMS arriving while locked is not processed (documented, L7) | Within 60 s |
| Battery | Edge within 10 s (Engine) | Within 10 s (Engine) or within 15 min (fallback) | Same | Engine returns; edge seen from the sticky state without a duplicate | Re-registered after unlock | Re-registered |
| Geofence | Within 6 min of entering | Within 6 min | Within 6 min | Within 6 min (geofence is system held) | Re-registered by Reconciler within 3 min of unlock; fires within 6 min after | Re-registered |
| Wi-Fi | Within 15 s | Within 15 s | Within 15 s | Yes (PendingIntent) | After unlock | After update |
| Bluetooth | Within 10 s (confirm manifest delivery) | Within 10 s | Within 10 s | Yes | After unlock | After update |
| Headset | Engine only: within 5 s | Within 5 s | Within 5 s | After Engine returns | After unlock | After update |
| Boot | n/a | n/a | n/a | n/a | Fires once within 3 min of unlock | n/a |

Common criteria for every row: no duplicate Runs (dedupe works); exactly one `expected_run` per slot; an event appears in the log for every abnormal condition; after the condition ends the Flow state returns to Published without user action.

Special tests:

| ID | Test | Pass |
|---|---|---|
| T-FORCESTOP | `am force-stop`, then launch the app | Within 5 s of launch: `ForceStopDetected` event, Reconciler re-armed everything, missed slots marked, notification once. During the gap: nothing ran (expected). |
| T-REBOOT-LOCKED | Reboot with a PIN, wait 10 minutes, then unlock | Recovery within 3 min of unlock; event records the wait; expected runs inside the wait are Missed or caught up per policy. |
| T-FGS-DENIED | Revoke battery exemption, put the app in the background, fire an SMS and a notification | Inbox rows exist; fallback worker runs them; the tap-to-restart notice appears; no crash. |
| T-DATASYNC-NEG | Enable `FGS_INTRODUCE_TIME_LIMITS` and set the dataSync timeout to 60 s | Engine unaffected after 10 minutes (proves we are not on `dataSync`). |
| T-BOOT-A15 | Enable `FGS_BOOT_COMPLETED_RESTRICTIONS`, simulate boot broadcast | Engine starts (specialUse not banned). |
| T-NOTIF | Deny notifications, publish an Engine Flow | Engine runs; Reliability Center shows C3 amber; confirm where the OS shows the service. |
| T-BT | Connect a paired device with the app UI closed and the screen off | Event arrives; if not, note the device and move Bluetooth to the Engine. |
| T-ALARMS-REVOKE | `appops ... SCHEDULE_EXACT_ALARM deny` | App stops; relaunch; schedules degrade to Flexible with a visible fix. |
| T-SOAK | 8 hour screen-off run (ST7) on each target device | Stock: 99% of slots within 60 s, zero Engine deaths. OEM phone after its steps: at least 95% within 5 minutes and zero unexplained Engine deaths. Record device, OS version and result in `docs/specs/P2-device-results.md` (to be created in AO-22). |
| T-STRESS | 200 SMS or webhooks in 60 s | All 200 in the Inbox, all processed in order or dropped with explicit `DroppedEvent` records; no memory kill; wake lock total under 10 min. |

---

## 8. (h) Honest limits

Things no app can guarantee. These texts are also shipped inside the Reliability Center.

| # | Limit | Detail |
|---|---|---|
| L1 | **OEM task killers.** | Xiaomi, Oppo, Realme, Vivo, OnePlus, Samsung and Huawei/Honor phones run vendor cleaners that can stop a foreground service with no notice, or clear alarms. dontkillmyapp lists no developer-side fix for most. Even Samsung's own list moves between One UI versions, and OnePlus has been reported to reset the battery setting by itself. After the user applies the vendor steps, reliability usually becomes good but cannot be proven except by measuring (soak test). A phone can pass for weeks and then fail after a firmware update. |
| L2 | **Force-stop is final.** | Settings force-stop, and "clear all" or "kill" buttons on several OEMs, put the app in the stopped state: alarms, jobs and geofences are cancelled and no receiver runs until the user launches the app (Android 15 documents the cancellation of pending intents). We detect and log it afterwards. We cannot prevent it. |
| L3 | **No alert while dead, from the phone.** | A dead app cannot notify anyone. Only the optional external ping (6.2) can alert the user during an outage. |
| L4 | **Doze limits cadence.** | In deep Doze while-idle alarms are throttled (documented about once per 9 minutes, and 7 per hour). Schedules shorter than about 10 minutes can slip with the screen off unless Strict mode (a standing wake lock) is on, and that mode costs battery and is flagged by Play vitals. |
| L5 | **Exact timing needs a permission.** | Without exact-alarm access, schedules degrade to windows. On the sideload flavour `USE_EXACT_ALARM` removes this problem; on a store build the user must grant it, and revoking it stops the app. |
| L6 | **Webhooks need a path to the phone.** | Carrier NAT and public Wi-Fi isolation make a phone unreachable from the internet. LAN mode works on the same network only; anything else needs a tunnel or a relay (later). Some Wi-Fi radios sleep deeply with the screen off, adding latency or dropping the connection. Plain HTTP on the LAN is visible to that LAN. |
| L7 | **Reboot before first unlock.** | The database is in credential-encrypted storage, unreadable until the first unlock. A phone that reboots overnight with a PIN runs nothing until someone unlocks it. An SMS arriving in that window is not processed. A Direct Boot mirror (AO-24) can reduce this for schedules but does not remove it. |
| L8 | **Platform event delivery varies.** | Wi-Fi, Bluetooth and headset events depend on what the platform delivers to a background app; some are only delivered to a live process. Geofence latency is 2 to 6 minutes and needs "Allow all the time" location. |
| L9 | **Platform privacy features alter content and timing.** | Android 15 redacts OTP notifications for non-trusted listeners. Android 17 can withhold OTP-style SMS broadcasts for up to 3 hours for apps that are not the default SMS app. A Flow built to read one-time codes is unreliable by design of the OS. |
| L10 | **User-side toggles override us.** | Battery saver, Data Saver, "restrict background usage", an app being put to sleep after days of non-use, revoked permissions, a swiped-away notification, and OS updates that reset settings. We detect and explain; we cannot keep them on. |
| L11 | **Battery cost is real.** | A foreground service, wake locks during Runs, frequent alarms and network use cost battery. The cost has not been measured yet; AO-22 records it. The Engine notification shows what Android's own battery screen will show. |
| L12 | **Runs are not guaranteed to complete.** | If the process dies mid-Run, the Run is marked Crashed and is not replayed automatically (side effects such as SMS must not repeat). The event itself is never lost; "Retry from failure" is manual unless the Flow opted in. |
| L13 | **Platform rules change.** | The limits in section 2 were read in November 2026 from official pages, which state that quota numbers are approximate and may change. Re-check at each Android release. |

---

## 9. Play Store notes (secondary)

The owner sideloads, so none of this blocks use. It is recorded so a store release is not a surprise.

- Foreground service declaration: each type needs a Console declaration and demo video for targetSdk 34+; `specialUse` is accepted only for narrow cases and reviewed; automatic background data sync is not accepted for `dataSync` (R28). Keep the declared text identical to the manifest property.
- `USE_EXACT_ALARM` is for alarm clock and calendar apps. The store flavour declares only `SCHEDULE_EXACT_ALARM` and asks the user to grant it (R17).
- Direct battery-optimisation exemption requests are allowed for task automation apps (R13). Keep a clear rationale screen before the system dialog.
- SMS permissions are restricted to apps that are the default SMS handler or meet narrow exceptions (audit 04 recommends a store flavour with compose-intent fallback and no SMS read). `ACCESS_BACKGROUND_LOCATION` needs its own declaration. Notification listener access is a user grant with disclosure.
- Android vitals watches excessive partial wake locks (2 h per 24 h) and other battery metrics (R20).
- Two flavours are a build-time switch, not a runtime toggle: `sideload` (full permissions, `USE_EXACT_ALARM`, SMS) and `play` (restricted permissions removed).

---

## 10. (g) Task breakdown

Ordering rule: every task is independently testable and leaves the app working. Tasks marked **RISK** are the ones most likely to hit platform surprises. "JVM" means a unit test in a pure-Kotlin module; "Device" means a numbered test from section 7. Dependencies reference earlier tasks.

### Milestone M0: foundations

| ID | Task | Acceptance criteria | Tests | Risk |
|---|---|---|---|---|
| AO-01 | Build flavours `sideload` and `play`; manifest per flavour; remove `READ_SMS`; delete `SmsTriggerService` declaration. | Both flavours build; the merged manifests differ exactly as in 3.12; SMS Spark still works. | Manifest merge check script; Device: T-SMS basic | Low |
| AO-02 | Schema migration 3 to 4: `flows` lifecycle columns, `flow_versions`, `trigger_registration`, `flow_lifecycle_log`, `spark_inbox`, `schedule_state`, `expected_run`, `engine_session`, `engine_heartbeat`, `reliability_event`. `isActive` mapped to PUBLISHED with revision 1. | Migration test from the committed v3 schema passes with data; no data loss; app starts. | `MigrationTestHelper` | **RISK** (data) |
| AO-03 | Pure-Kotlin `ActivationStateMachine` (Draft, Published, Paused, Error) with transition table, retry backoff and stable-period reset, `EngineNeeds.compute`. | Every transition in 4.2 has a test; backoff sequence 5 s, 15 s, 1 min, 5 min, 15 min, 1 h; permanent failures are not retried. | JVM, exhaustive table tests | Low |
| AO-04 | `PublishValidator` implementing 4.3 (errors versus warnings) on top of the shared `FlowValidator`. | Each rule has a passing and failing fixture; warnings never block; messages name the Step or Spark. | JVM | Low |

### Milestone M1: the Engine

| ID | Task | Acceptance criteria | Tests | Risk |
|---|---|---|---|---|
| AO-05 | `SparkInbox` repository, unique dedupe key, claim and release with lease, replace `PerFlowEventQueue` and the 10 KB `Data` payload. `RunDispatcher` with per-Flow concurrency policy and global cap. | 200 events enqueue and run in order; duplicates dropped with a record; killing the process mid-claim re-offers the row after the lease. | JVM with fake clock; Device: T-STRESS | Medium |
| AO-06 | `AlwaysOnService` (specialUse, sticky, notification, `onTimeout`, `onTaskRemoved` restart alarm). `EngineController` with start, stop, state, `Start-blocked` handling, tap-to-restart notification. Remove `WebhookService`. | Service type shows `specialUse` in `dumpsys activity services`; survives screen off 10 minutes; refused start produces the notice and no crash; works at boot (T-BOOT-A15). | Device: T-DATASYNC-NEG, T-BOOT-A15, T-FGS-DENIED | **RISK** (FGS start rules) |
| AO-07 | `AlarmScheduler`, `AlarmReceiver`, precision modes, `schedule_state`, next-slot-first firing, Inbox slot rows with idempotency key, exact-alarm permission handling and state-changed receiver, Flexible fallback. Chain replaces periodic work for intervals under 15 minutes. | Exact schedule fires within 60 s in forced Doze; permission denied degrades to Flexible and shows an event; no duplicate Runs when alarm and watchdog both fire. | JVM `ScheduleMath` incl. DST fixtures; Device: Schedule rows of 7.4, T-ALARMS-REVOKE | **RISK** (exact alarm semantics, Doze rate limit) |
| AO-08 | `Reconciler`: re-arm all alarms, registrations, geofences and callbacks; apply misfire policy; mark `expected_run` Missed; mutex; callable from every trigger in 3.2. | After `am crash`, `install -r`, reboot, and time change, state converges with no manual action; calling it twice changes nothing. | JVM with fakes; Device: crash/install/reboot rows | Medium |
| AO-09 | Pulse alarm and `WatchdogWorker`; `BootReceiver` and time receivers delegate to the Reconciler; `LOCKED_BOOT` not yet used. | Engine restarted within 16 minutes after a simulated death; watchdog works with alarms cleared. | Device: ST3, T-FORCESTOP (detection only) | Medium |
| AO-10 | `WakeLockManager` (Run and intake locks with timeouts, metric). | No lock outlives its Run; metric visible in the dump; soak shows total under 10 minutes on a quiet day. | JVM for timeout rule; Device: T-SOAK | Low |
| AO-11 | Move the webhook server into the Engine; reach modes (loopback default, LAN switch); Inbox write before reply; route table refresh on publish; port-in-use error becomes a registration failure. | `curl` works over `adb forward` and over LAN; without token 401; port conflict yields Error state with reason; survives Engine restart. | Device: T-WEBHOOK rows | Medium |
| AO-12 | Event Spark rework: SMS to Inbox then fallback worker (no service start from the receiver); Notification listener connection tracking and `requestRebind`, title-less notifications accepted; Battery with Engine receiver plus WorkManager constraint fallback; headset and screen Engine receivers; Wi-Fi via `registerNetworkCallback` with a `PendingIntent`. | Each Spark row of section 7.4 passes for screen off and crash; listener state visible; Wi-Fi works with the process dead. | Device: per-Spark rows | **RISK** (several platform behaviours) |
| AO-13 | Geofence rework: `GeofencingClient` when Play services exist, proximity alert fallback; dwell option; re-registration by the Reconciler. | Geofence fires within 6 min and is re-registered after reboot and update. | Device: Geofence rows | **RISK** (location permission flow, OEM) |
| AO-14 | Bluetooth Spark: confirm manifest delivery of ACL events on the test devices, otherwise move to the Engine; write the Companion Device Manager upgrade note (below). | T-BT result recorded per device; Spark works screen off. | Device: T-BT | **RISK** |
| AO-15 | `FlowRunWorker` as fallback host: Inbox id only, `getForegroundInfo`, `specialUse` type, no replay of interrupted Runs. Run lease and `Crashed` status. | Worker never double-runs a Run id; works when the Engine is Start-blocked. | JVM for lease; Device: T-FGS-DENIED | Medium |

**AO-14 implementation note (M1-B).** Both delivery paths exist: `BluetoothAclReceiver` in the manifest (covers a dead process wherever the platform still delivers ACL broadcasts to manifest receivers for the target SDK) and the same logic registered dynamically by `EngineSources` while the Engine runs. Whether the manifest path works cannot be known from code; test T-BT records it per device. The Inbox dedupe key (`bt:<event>:<hash of address>:<10 s bucket>`) makes a double delivery harmless. From API 31 both paths need the runtime `BLUETOOTH_CONNECT` grant; without it the registration row is `PermissionMissing`.

**Companion Device Manager upgrade path (not built).** `CompanionDeviceManager` presence observation (`startObservingDevicePresence`, API 31+; `CompanionDeviceService.onDeviceAppeared` / `onDeviceDisappeared`) is the platform's supported way to learn that a specific paired device is in range, wakes a dead process, and is a listed foreground-start exemption. Its costs: the user must associate each device through the system chooser once (one device per Spark, so the Spark picks a device instead of typing an address), the signal is "in range" and not ACL connect, and association needs `REQUEST_COMPANION_RUN_IN_BACKGROUND` and `REQUEST_COMPANION_USE_DATA_IN_BACKGROUND` to run reliably under Doze. Move to it if T-BT shows the manifest path failing on a device class that matters; `BluetoothDelivery.plan` is the single place that would choose it, and `DeviceEvents.bluetooth` keeps its payload.

### Milestone M2: lifecycle and visibility

| ID | Task | Acceptance criteria | Tests | Risk |
|---|---|---|---|---|
| AO-16 | `ActivationManager`: publish, pause, resume, unpublish, publish changes (atomic swap), restore at start in batches with a pre-registration log line, retry queue backed by alarms. Draft versus published execution (Spark Runs use the published revision). | Editing a Published Flow does not change live behaviour; a failing Spark moves the Flow to Error and back by itself; restart restores all in under 5 s for 50 Flows. | JVM + Device | Medium |
| AO-17 | Publish UI (sheet with checklist, per-Spark result, fix buttons), state chips, badges, Flow list integration. | All strings from 4.4 present; publish refused with reasons when a Spark cannot arm. | Compose UI tests | Low |
| AO-18 | Heartbeat, `engine_session`, `ApplicationExitInfo` and `wasForceStopped` capture, `reliability_event` writer, dead-engine detection after the fact, alerts with throttling. | After `am crash`, `am force-stop`, reboot: the correct events with reasons exist; one notification per outage. | JVM for gap logic; Device: T-FORCESTOP, T-REBOOT-LOCKED | Medium |
| AO-19 | `expected_run` log, lateness measurement, daily aggregation and retention. | Dump shows expected versus actual for 14 days; compaction keeps aggregates. | JVM | Low |
| AO-20 | Reliability Center UI: checks C1 to C17, deep links with fallbacks, restricted-settings help, health score and caps, Flow health strips, event log tab, diagnostics export. Replace static `BatteryHealth` verdicts. | Each check turns red or amber when its condition is created with the commands in 7.2, and green when fixed; OEM check stays UNKNOWN until attested and soak-proven; score caps behave as in 5.4. | JVM `HealthScorer`; Compose tests; Device: per-check toggles | Medium |
| AO-21 | Self-test suite ST1 to ST7 and the internal hidden Flow; debug `DiagReceiver` and `scripts/adb-*.sh` helpers for section 7. | Self-test results appear as `SelfTestResult` events; scripts run the commands of 7.2 and 7.3. | Device | Low |

### Milestone M3: proof and hardening

| ID | Task | Acceptance criteria | Tests | Risk |
|---|---|---|---|---|
| AO-22 | Run the full section 7 matrix on a stock device and on the owner's OEM phone; run the 8 hour soak; create `docs/specs/P2-device-results.md`; tune score weights; measure battery cost. | All "must pass" cells pass on stock; OEM phone result and required vendor steps documented; limits text updated with measured facts. | Device | **RISK** (findings may reopen earlier tasks) |
| AO-23 | Raise `compileSdk` and `targetSdk` to 36, re-run AO-22. | No behavioural regressions; T-DATASYNC-NEG and T-BOOT-A15 still pass with real enforcement. | Device | **RISK** (library compatibility, new behaviours) |
| AO-24 | Optional: Direct Boot mirror in device-protected storage (`LOCKED_BOOT_COMPLETED`) for schedule slots and a minimal Inbox. | Schedules fire after reboot before first unlock without reading the main database. | Device: T-REBOOT-LOCKED variant | **RISK** (two stores, security of what is mirrored) |
| AO-25 | Optional: external dead-man's switch ping setting. | Ping sent on every Pulse; failures logged; off by default. | Device | Low |
| AO-26 | Optional: Companion Device Manager presence for Bluetooth; Relay Spark. | Out of scope for this spec's acceptance; scoped separately. | n/a | n/a |

Sequencing note: AO-02 and AO-06 can proceed in parallel. AO-07 needs AO-02, AO-05 and AO-06. AO-12 and AO-13 can be split by Spark and shipped one at a time behind the Publish validator (a Spark that is not yet reliable should not be publishable; audit 04 P0-3 asks for a catalog test that every Spark has a producer).

---

## 11. Sources

All fetched or searched on 2026-11-16. Items marked "api" in section 2 are platform knowledge not re-read this session; items marked "device" are to be confirmed by the section 7 tests.

- S1 Foreground service timeouts: https://developer.android.com/develop/background-work/services/fgs/timeout
- S2 Foreground service types (read through the mirror): https://developer.android.google.cn/develop/background-work/services/fgs/service-types
- S3 Changes to foreground services (versions 12 to 16): https://developer.android.com/develop/background-work/services/fgs/changes
- S4 Behaviour changes for apps targeting Android 15: https://developer.android.com/about/versions/15/behavior-changes-15
- S5 Android 15 behaviour changes, all apps (stopped state, background network, notification listener OTP redaction): https://developer.android.com/about/versions/15/behavior-changes-all
- S6 Android 16 behaviour changes, all apps (job quotas, broadcast priority): https://developer.android.com/about/versions/16/behavior-changes-all
- S7 Foreground service start restrictions and exemptions (Android 12 page): https://developer.android.com/about/versions/12/foreground-services
- S8 Doze and App Standby, including Play policy note and adb tests: https://developer.android.com/training/monitoring-device-state/doze-standby.html
- S9 App Standby buckets: https://developer.android.com/topic/performance/appstandby
- S10 Power management resource limits (bucket, Doze and Android 16 tables): https://developer.android.com/topic/performance/power/power-details
- S11 Exact alarm changes on Android 14: https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- S12 Schedule alarms: https://developer.android.com/training/scheduling/alarms
- S13 WorkManager long-running workers: https://developer.android.com/guide/background/persistent/how-to/long-running
- S14 Excessive partial wake locks (Android vitals): https://developer.android.com/topic/performance/vitals/wakelock
- S15 Wi-Fi low latency mode: https://source.android.com/docs/core/connect/wifi-low-latency
- S16 Direct Boot: https://developer.android.com/privacy-and-security/direct-boot
- S17 Geofencing: https://developer.android.com/training/location/geofencing
- S18 Play Console foreground service declaration: https://support.google.com/googleplay/android-developer/answer/13392821
- S19 Android 17 behaviour changes, all apps: https://developer.android.com/about/versions/17/behavior-changes-all
- S20 Android 17 behaviour changes, apps targeting 37: https://developer.android.com/about/versions/17/behavior-changes-17
- S21 Restricted settings for manually installed apps (secondary source): https://kaspersky.com/blog/android-restricted-settings/49991
- S22 dontkillmyapp.com, per brand: https://dontkillmyapp.com/xiaomi , https://dontkillmyapp.com/samsung , https://dontkillmyapp.com/oneplus , https://dontkillmyapp.com/oppo , https://dontkillmyapp.com/vivo , https://dontkillmyapp.com/huawei
- S23 Tasker FAQ on background running: https://tasker.joaoapps.com/userguide/en/faqs/faq-problem.html
- S24 Automate developer reply on staying alive and sleep: https://groups.google.com/g/automate-user/c/hcq1Tr3eO5E
- S25 Practitioner article on surviving OEM battery killers: https://dev.to/szymonwalczak/surviving-android-battery-killers-in-capacitor-apps-4im7
- S26 NotificationListenerService reference (class page; the rebind and process-death details in R24 were not confirmed from it, so they are marked "device"): https://developer.android.com/reference/android/service/notification/NotificationListenerService

Repo files read: `docs/audit/01-execution-engine.md`, `04-integration-device-ai-triggers.md`, `07-storage-scheduling.md`, `docs/PHASE1_CHECKLIST.md`, `app/src/main/AndroidManifest.xml`, `core/work/.../{FlowScheduler,FlowEngine,FlowRunWorker,WebhookService,WebhookServer,WebhookPolicy,SparkRuntime,BootReceiver,BatteryHealth,MobileEventReceiver}.kt`, `feature/permissions/.../{PermissionCentreScreen,NotificationAndSmsSources}.kt`, `app/.../KaivoApplication.kt`.

---

## 12. Open questions for the owner

1. Is it acceptable that the sideload flavour declares `USE_EXACT_ALARM` (auto-granted, not revocable) while a future store flavour cannot? (Decision D6.)
2. Which phone(s) are the real targets? The OEM column of section 7.4 and the soak test should run on those first.
3. Is a persistent notification acceptable at all times when any Spark needs the Engine? (Android requires it for a foreground service.)
4. Should the webhook offer LAN mode in v1, or stay loopback-only until a relay or tunnel option exists?
5. Is an external ping URL (AO-25) something you would configure?
6. Do you want "pause after N failed Runs" on by default for Flows that send SMS?

## Appendix: decisions in one list

- D1 The Engine is one foreground service of type `specialUse`, started at publish time and restarted by sticky, Pulse alarm and watchdog.
- D2 `dataSync` is not used for the Engine or for Run promotion (6 hour cap, boot ban, Play).
- D3 Every schedule is an alarm. Per-Flow precision: `OnTime` (alarm clock), `Exact` (default), `Flexible` (WorkManager).
- D4 Persist first: every Spark event and slot goes into a durable Inbox before execution; Runs execute in the Engine when alive, in a fallback worker otherwise, never both.
- D5 Publish fails loudly if any Spark cannot be armed; afterwards the Flow retries quietly in Error state and recovers by itself.
- D6 Sideload flavour declares `USE_EXACT_ALARM`; store flavour does not.
- D7 No standing wake lock and no Wi-Fi lock; Run-scoped wake locks with timeouts only.
- D8 Build the Engine correct for targetSdk 36 while the app still targets 34; raise the target only after the device matrix passes (AO-23).
- D9 The Reliability Center is evidence-based: heartbeat, expected-versus-actual log, process-death reasons; unverifiable OEM settings are never green and cap the score.
- D10 Limits are stated in the product, not hidden (section 8).
