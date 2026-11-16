# Build risks before the first real Gradle build

Verified offline at HEAD (Kotlin 2.0.21 jars from Gradle's lib dir, collections and Room stubs): core:model, core:expressions,
core:engine (alwayson, stepdata, reliability), core:nodes, core:data (all classes except CryptoBox and DataModule) and the pure
feature:canvas logic (state, logic, host gateway, mapper, panels models, params models, layout, picker) compile and pass
1,049 tests (model 99, expressions 148, engine 393, nodes 164, core:data 75, feature:canvas 170). The Compose files, core:work,
core:network, core:design, the other features and app were only reviewed by reading and grep scripts; nobody has compiled them.
Ordered by how likely each is to break the first build. Paste the first compiler error for any item.

Fixed in the last review (no longer risks, listed so you know they changed):
- Root `build.gradle.kts` set `-opt-in` flags with a top-level `tasks.withType`, which only configures the root project. The flags
  now sit in `subprojects { }`, so `ModalBottomSheet` (PublishSheet, WorkflowExecutionsSheet and others) no longer needs a per-file `@OptIn`.
- `core:data` declared `room-runtime` as `implementation`, but `core:work` reads `KaivoDatabase.NAME` (a class whose supertype is
  `RoomDatabase`). It is now `api`.

## 1. core:work against real Android, WorkManager and Play services (40 files, never compiled)
- Files: `core/work/src/main/kotlin/com/kaivoflow/core/work/**` (AndroidDeviceGateway, alwayson/EngineSources, Geofences,
  AlarmScheduler, AlwaysOnService, FlowRunWorker, Workers, Receivers, ReliabilityCenterData).
- Check: `./gradlew :core:work:compileDebugKotlin`. Likely errors: SDK signature mismatches (NetworkCallback overrides,
  GeofencingClient calls, TextToSpeech bridge), `Service.onTimeout(Int)` (API 34 override; the two-argument form is API 35 and is
  declared without `override`), missing imports.

## 2. Compose UI modules (never compiled)
- Files: `core/design`, `feature/canvas` (CanvasScreen, `inspector/host/*`, `inspector/params/*` widgets, `inspector/panels/*Views`,
  `inspector/mapper/DragMapCompose`, StepPickerSheet), `feature/flows` (FlowListScreen 1,900 lines, OverviewTabs, PublishSheet),
  `feature/settings`, `feature/permissions`, `app/MainActivity.kt`.
- Compose BOM 2024.10.01 (ui and foundation 1.7.x, material3 1.3.0). No Compose 1.8+ API was found in the sources; drag and drop
  is hand-written with `pointerInput`, not `dragAndDropSource`. Uncertain: icon names in `KaivoIcons.kt` (`Route`, `Webhook`,
  `Token`, `Numbers`) must exist in material-icons-extended 1.7.
- Check `:feature:canvas:compileDebugKotlin` first, then flows, settings, permissions, app.

## 3. Hilt and KSP graph
- Files: `core/data/.../DataModule.kt`, `core/work/.../WorkModule.kt`, `alwayson/AlwaysOnModule.kt`, `ReceiverEntryPoint.kt`,
  `core/network/.../NetworkModule.kt`, `KaivoApplication.kt`.
- Check: `./gradlew :app:kspSideloadDebugKotlin`. Scripts confirmed every injected type has a provider, no field injection in
  receivers, `@AndroidEntryPoint` only on MainActivity, AlwaysOnService and KaivoNotificationListener, both EntryPoints are in
  SingletonComponent. Not checkable by script: Dagger cycles (Reconciler uses `dagger.Lazy<ActivationManager>` to break one),
  qualifiers and the `@JvmSuppressWildcards Set<SparkRegistrar>` multibinding, `@HiltWorker` constructors (4 Workers).
- Hilt with KSP (Dagger 2.51.1, KSP 2.0.21-1.0.28) in library modules is supported but is where odd errors appear first.

## 4. Room: KSP validation and migrated-schema equality
- Files: `core/data/.../Database.kt`, `EngineDaos.kt`, `StepDataDao.kt`, `Migrations.kt`.
- Scripts: 20 entities all in `@Database`, `version = 6` equals MIGRATION_5_6, `ALL_MIGRATIONS` is 1 to 6 contiguous, every DAO
  table and column exists, no entity needs a TypeConverter.
- Runtime risk: the hand-written SQL in `createEngineTables`, `createStepDataTables` and `createReliabilityV6` must equal the
  generated schema exactly or the first open after upgrading throws. Compare with the generated `6.json`. Only `1.json` is
  committed, so no migration instrumentation test exists. See PHASE2_CHECKLIST.md section 2.

## 5. Gradle, dependency resolution and AAR metadata
- `CheckAarMetadataTask` is disabled for all subprojects; that hides compileSdk or AGP minimums of dependencies (navigation 2.8.3,
  lifecycle 2.8.6, activity-compose 1.9.3, compose BOM 2024.10 with compileSdk 34 and AGP 8.3.2). Remove the block once the build
  works and read any failure; the real fix is usually AGP 8.5+ and compileSdk 35 together.
- Library and plugin coordinates were cross-checked against `libs.versions.toml` (no missing alias). Module edges and imports were
  cross-checked against each module's dependencies (no missing dependency found).
- Check: `./gradlew help`, then `./gradlew :app:assembleSideloadDebug`. The wrapper wants Gradle 8.5.

## 6. JDK toolchain
- `core/{model,engine,expressions,nodes}/build.gradle.kts` set `java { toolchain 17 }` and `kotlin { jvmToolchain(21) }` together;
  the Kotlin setting wins and also configures Java. All Android modules use 21. No foojay resolver in settings, so JDK 21 must be installed.

## 7. Manifest merge
- Files: `app/src/main/AndroidManifest.xml`, `app/src/sideload`, `app/src/play`, `app/src/debug`.
- All 10 components exist as classes and are declared once per flavour. Services have `foregroundServiceType="specialUse"` plus the
  special-use property; exported flags are explicit; every permission used in code is declared.
- `SystemForegroundService` uses `tools:replace="android:foregroundServiceType"`; if the merger rejects it, check
  `:app:processSideloadDebugManifest` and the merged manifest report.
- targetSdk stays 34 on purpose. Follow-up: raise to 35, then 36 after the device matrix (Phase 2F); that also brings Android 15
  foreground-service and edge-to-edge rules.

## 8. Unit tests inside Android library modules
- `core/data/src/test` and `feature/canvas/src/test` run on the Android unit-test classpath. The offline run executed them against
  Room and Crypto stubs, so real Room, `android.util.Base64` and Keystore behaviour is untested. `CryptoBox` touches Keystore;
  any test that reaches it fails with "not mocked".
- Check: `./gradlew :core:data:testDebugUnitTest`.
- Not a build error: `core/engine/.../PublishValidator.kt` lists `"device.clipboard_read"` and the reserved `"logic.approval"`,
  which match no live Step.
