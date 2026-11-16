# Build risks before the first real Gradle build

Verified offline at HEAD (Kotlin 2.0.21 jars from Gradle's lib dir, collections and Room stubs):
core:model, core:expressions, core:engine (alwayson, stepdata), core:nodes, core:data (all classes except
CryptoBox and DataModule, which are stubbed or skipped) and the pure feature:canvas logic compile and
pass 888 tests. Everything below was only reviewed by reading and by grep scripts.
Ordered by how likely it is to break the first build. Paste the first compiler error for any item.

## 1. core:work against real Android, WorkManager and Play services (34 files, never compiled)
- Files: `core/work/src/main/kotlin/com/kaivoflow/core/work/**` (AndroidDeviceGateway, alwayson/EngineSources,
  Geofences, AlwaysOnService, FlowRunWorker, Receivers).
- Check: `./gradlew :core:work:compileDebugKotlin`. Likely errors: SDK signature mismatches (NetworkCallback overrides,
  GeofencingClient calls, TextToSpeech bridge, `ServiceCompat.startForeground`), missing imports.
- Already fixed: positional `ExpectedRunEntity(...)` call in alwayson/ScheduleSync.kt.

## 2. Compose UI modules (never compiled)
- Files: `core/design`, `feature/canvas` (CanvasScreen 800+ lines, StepInspectorSheet, StepPickerSheet),
  `feature/flows`, `feature/settings`, `feature/permissions`, `app/MainActivity.kt`.
- Check: `./gradlew :feature:canvas:compileDebugKotlin`, then the other features. Look for opt-in errors.

## 3. Hilt / KSP graph
- Files: `core/data/.../DataModule.kt`, `core/work/.../WorkModule.kt`, `alwayson/AlwaysOnModule.kt`,
  `core/network/.../NetworkModule.kt`, `KaivoApplication.kt`.
- Check: `./gradlew :app:kspSideloadDebugKotlin`. A script confirmed every injected type name has a provider and no
  type is bound twice, but it cannot see qualifiers, scopes or the `@JvmSuppressWildcards Set<SparkRegistrar>` multibinding
  (`Reconciler`). `@HiltWorker` constructors (Workers.kt, FlowRunWorker.kt) are unchecked.

## 4. Room: KSP validation and migrated-schema equality
- Files: `core/data/.../Database.kt`, `EngineDaos.kt`, `StepDataDao.kt`, `Migrations.kt`.
- Check: `./gradlew :core:data:kspDebugKotlin` (13 DAOs, 110 queries; table and column names were cross-checked by
  script, all 19 entities are in `@Database`, version 5 equals MIGRATION_4_5, ALL_MIGRATIONS is 1..5 contiguous).
- Runtime risk: hand-written SQL in `createEngineTables` and `createStepDataTables` must match the generated schema
  exactly or the first open after upgrading throws. Compare with `core/data/schemas/.../5.json` once exported.
  Only `1.json` is committed, so no migration instrumentation test exists.

## 5. Gradle and dependency resolution
- Files: `build.gradle.kts` (root), `gradle/libs.versions.toml`, `app/build.gradle.kts`.
- `CheckAarMetadataTask` is disabled for all subprojects, which hides compileSdk or AGP minimums of dependencies
  (navigation 2.8.3, lifecycle 2.8.6, activity-compose 1.9.3 with compileSdk 34 and AGP 8.3.2). Remove that block once
  the build works and read any failure.
- Root `tasks.withType<KotlinCompile>` configures only the root project, so `-opt-in` flags never reach modules; files
  rely on their own `@OptIn`. Move it into `subprojects { }` if opt-in errors appear.
- Check: `./gradlew help` then `./gradlew :app:assembleSideloadDebug`.

## 6. JDK toolchain
- Files: `core/{model,engine,expressions,nodes}/build.gradle.kts` set `java { toolchain 17 }` and `kotlin { jvmToolchain(21) }`
  together (the last one wins). All Android modules use 21. No foojay resolver in settings, so JDK 21 must be installed.

## 7. Manifest merge
- Files: `app/src/main/AndroidManifest.xml`, `app/src/sideload`, `app/src/play`.
- `SystemForegroundService` uses `tools:replace="android:foregroundServiceType"`; if WorkManager 2.9.1 does not declare
  that attribute the merger may warn or fail. Check `:app:processSideloadDebugManifest` and the merged manifest report.
- All 8 declared components exist as classes; no duplicates across flavours; every permission used in code is declared.

## 8. Unit tests inside Android library modules
- `core/data/src/test` (Room repository tests with fakes) and `feature/canvas/src/test` run on the Android unit-test
  classpath. `CryptoBox` touches Keystore and `android.util.Base64`; any test that reaches it fails with "not mocked".
  Check: `./gradlew :core:data:testDebugUnitTest`.
- Not a build error: `core/engine/.../PublishValidator.kt` still lists `"device.clipboard_read"` and `"logic.approval"`,
  which match no live Step after the rename.
