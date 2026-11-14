# KaivoFlow — Session Handoff & Device Verification

> **Communication bridge between Cloud Agent (authoring code) and Local Agent (device verification).**

---

## Current Session Status
* **Build Status:** ✅ Clean compile with `./gradlew assembleDebug` (AGP 8.3.2, Kotlin 2.0.21, compileSdk 34, JDK 21).
* **Unit Tests:** ✅ 60+ JVM tests passing in `:core:nodes:test` and `:core:model:test`.
* **Device Run:** ✅ Tested live on physical Realme 9 5G (Android 13 / API 33). Flow list, Canvas, and Inspector rendered cleanly.
* **Architecture Docs:** See `docs/ARCHITECTURE.md` for full blueprint.

---

## Recent Fixes Landed
1. Pinned build toolchains and dependencies in `gradle/libs.versions.toml`.
2. Fixed P0 Trigger execution bug: manual & scheduled triggers emit initial context payload so downstream steps execute.
3. Added auto-rescheduling loop in `FlowRunWorker` and registered `BootReceiver` hooks for boot, package update, and time changes.
4. Added `EventTriggerMatcher` filtering for Notifications, SMS, and Battery triggers.
5. Added new developer nodes: `transform.crypto`, `device.battery_status`, `device.network_status`.

---

## NEEDS DEVICE VERIFICATION (For Local Agent)

| Screen / Step | Action on Device | Expected Result | Status | Notes |
|---|---|---|---|---|
| Flow List | Launch app, tap "+" or "Start from scratch" | Canvas opens cleanly with Velvet Obsidian theme | PASS | Verified on device |
| Step Picker | Tap "Add a Step" floating button | Category sheet opens with jewel categories | PASS | Verified on device |
| Step Inspector | Tap any node card (e.g. Ask AI, HTTP) | Inspector sheet opens with parameter controls | PASS | Verified on device |
| Crypto Node | Add `transform.crypto`, run SHA-256 test | Produces correct 64-char hex hash | PENDING | To test on physical device |
| Battery Status | Add `device.battery_status`, tap test | Returns real battery percentage & charging state | PENDING | To test on physical device |
| Network Status | Add `device.network_status`, tap test | Detects WiFi / Cellular connection type | PENDING | To test on physical device |

---

## Next Priority Items
See `BACKLOG.md` and `DEVELOPER_HANDOFF.md` for prioritized feature roadmaps.
