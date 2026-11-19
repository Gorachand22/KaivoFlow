# Phase 3 gate: owner build and phone test

Compose, Hilt and Android code of Phase 3 was NEVER compiled. Build first; paste the first error to Claude.

## 1. Build
- [ ] `./gradlew help`
- [ ] `./gradlew :core:ai:test :core:nodes:test :core:model:test :core:expressions:test :core:engine:test`
- [ ] `./gradlew :core:work:compileDebugKotlin :feature:canvas:compileDebugKotlin :feature:flows:compileDebugKotlin :feature:settings:compileDebugKotlin`
- [ ] `./gradlew :feature:flows:testDebugUnitTest :feature:canvas:testDebugUnitTest`
- [ ] `./gradlew :app:assembleSideloadDebug`
- [ ] `adb install -r app/build/outputs/apk/sideload/debug/app-sideload-debug.apk`

## 2. Test Step isolation (sirf ek step chalega)
- [ ] Flow banao: Webhook -> Notify -> Notify2. Notify par "Test Step" dabao: sirf wahi step chale, baaki nahi.
- [ ] Run history me ye Run "Test" dikhe, production Run nahi.
- [ ] Agent Builder me agent ka "Test Run" chalao: ek hi step chale, poora Flow nahi.
- [ ] WorkflowExecutionsSheet me "Test from here": sirf us step ka test (note: input automatic hai).

## 3. Expressions everywhere
- [ ] Step notes me `{{ vars.x }}` likho: preview resolve ho, save ke baad raw text wapas dikhe.
- [ ] Variables tab me expression do: canvas preview me value dikhe, Flow file me raw expression rahe.
- [ ] Behaviour tab fields aur Step name par expression NA chale (fixed only).

## 4. Motion
- [ ] Settings me "Motion" section dikhe: Full, Calm, Off.
- [ ] Off karo: animations band; Calm: halke; app restart ke baad choice yaad rahe.

## 5. AI Agent Step in Flow
- [ ] Flow me "AI Agent" Step jodo, credential chuno, Test Step chalao: reply aaye.
- [ ] Agent tool jo side effect kare (notify): chat me Approve/Deny card aaye. Deny dabao: tool na chale.
- [ ] Destructive tool kabhi auto-approve na ho.
- [ ] Key logcat me nahi: `adb logcat -d | grep -i "AIza\|sk-"` khali ho.

## 6. Always-On with an Agent Step
- [ ] Schedule/Webhook Flow me Agent Step lagao, Flow arm karo, phone lock karo.
- [ ] Run ho; agar tool ko approval chahiye aur screen band hai, wo deny ho jaye (hang nahi).
- [ ] `adb shell dumpsys deviceidle | head` aur Reliability Center me health OK.

## 7. Ask AI Apply / Undo
- [ ] Koi Flow kholo, Ask AI me "battery 20% se kam ho to notify" bolo.
- [ ] Proposal card aaye; Canvas me Flow abhi badla NA ho.
- [ ] Apply dabao: Flow badle. Undo dabao: purana Flow wapas.
- [ ] Apply se pehle Flow ko haath se edit karo, phir Apply: conflict warning aaye.
- [ ] Network band karke bhejo: offline helper ka Flow proposal bane (banner ke saath).
- [ ] Naye catalog ids hi use hon (spark.battery, device.notify); unknown id reject.
