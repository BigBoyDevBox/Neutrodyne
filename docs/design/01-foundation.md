# 01 — Foundation

> Status: Draft v1, 2026-10-04 · Implements: N1 / N2 / N3 / N7 / N8 / N10 / N11 (foundation parts) · Milestones: M0 (primary), M1, M3, M4, M5, M6, M8, M9, M11 · Honours: D2, D3, D4, D5, D6, D7, D8, D9, D10, D12, D13, D14, D28, D35, D43, D60, D61; PO-1, PO-7, PO-8, PO-13, PO-18 defaults · Owns: toolchain and version catalog, convention plugins, modules and dependency rules, architecture and coroutine conventions, DI graph, Nav3 wiring, build flavors, networking baseline, Android 14–17 compliance checklist, merged manifest, licence policy, M0 scaffold and spikes

Contents: [Scope](#scope) · [Toolchain and versions](#toolchain-and-versions) · [Module layout](#module-layout) · [Dependency rules](#dependency-rules) · [Architecture patterns](#architecture-patterns) · [Dependency injection](#dependency-injection) · [Navigation](#navigation) · [Build flavors](#build-flavors) · [Networking baseline](#networking-baseline) · [Platform compliance](#platform-compliance) · [Manifest and permissions](#manifest-and-permissions) · [Licensing and dependency policy](#licensing-and-dependency-policy) · [M0 scaffold checklist](#m0-scaffold-checklist) · [Spikes](#spikes) · [Testing](#testing) · [Delivery by milestone](#delivery-by-milestone) · [New names introduced here](#new-names-introduced-here) · [Open questions](#open-questions) · [Sources](#sources)

---

## Scope

This document is the build-and-architecture contract every other design document stands on. An engineer (or AI session) implementing M0 follows [M0 scaffold checklist](#m0-scaffold-checklist) top to bottom; later milestones come back here for conventions, the manifest and the dependency rules.

**Owned here** (other documents link, never restate):

| Topic | Section |
|---|---|
| Every library and tool version; `gradle/libs.versions.toml`; `settings.gradle.kts`; `gradle.properties` | [Toolchain and versions](#toolchain-and-versions) |
| `build-logic` convention plugins (`neutrodyne.*`) | [Toolchain and versions](#convention-plugins) |
| Module creation, packages, per-module plugins and dependencies | [Module layout](#module-layout) |
| Module-graph assertion rules and the `play` classpath ban | [Dependency rules](#dependency-rules) |
| UDF/MVVM rules, `UiState`, events, paging in ViewModels, use-case rule, `Outcome`, `suspendRunCatching`, `Clock`, logging and redaction, coroutine/threading model, app start-up, DataStore files and typed setting keys | [Architecture patterns](#architecture-patterns) |
| Hilt components, scopes, flavor bindings, test overrides | [Dependency injection](#dependency-injection) |
| Nav3 mechanics: installers, per-tab back stacks, decorators, scene strategies, intent routing | [Navigation](#navigation) (behaviour: [08 Navigation](08-ui-ux.md#navigation)) |
| Flavors, build types, `BuildConfig`, flavor source sets | [Build flavors](#build-flavors) (YouTube capability semantics: [04 Flavor matrix](04-youtube.md#flavor-matrix)) |
| Shared `OkHttpClient`, derived clients, interceptors, network security config, network error taxonomy | [Networking baseline](#networking-baseline) |
| Android 11–17 rules → mechanism → owner | [Platform compliance](#platform-compliance) |
| Merged manifest: every permission and component with its declaring module | [Manifest and permissions](#manifest-and-permissions) |
| Licensee allow-list, SPDX rule, AboutLibraries, packaging, About licence statements, contribution rule | [Licensing and dependency policy](#licensing-and-dependency-policy) |

**Not covered here:** schema, SQL and Room usage conventions ([02 Conventions](02-data-model.md#conventions)); feature behaviour ([03](03-feeds-and-discovery.md)–[07](07-downloads.md)); screens, theming and the `PlayerSheet` ([08](08-ui-ux.md)); CI workflows, test infrastructure, static-analysis gates, signing and release ([09](09-quality-and-release.md) — this document only defines the Gradle-side tasks those workflows call).

---

## Toolchain and versions

Serves N7, N11. Delivered in M0 (catalog grows by milestone; versions never differ from this table without amending it and [D4](../PLAN.md#3-key-decisions)).

### Version table

All verified 2026-10-04 (sources in [Sources](#sources)). This is the only document that lists every version.

| Area | Item | Version | Notes |
|---|---|---|---|
| Toolchain | Kotlin (KGP, Compose compiler plugin, serialization plugin) | 2.4.20 | JetBrains tests it only up to AGP 9.3.1 / Gradle 9.7.0 → [S1](#s1-kgp-2420-under-agp-941) |
| | Android Gradle Plugin | 9.4.1 (fallback 9.3.3) | Built-in Kotlin, new DSL; no `kotlin-android`, no kapt. AGP 9.4 needs Gradle ≥ 9.6.0, JDK 17, Build Tools 36.0.0 |
| | Gradle | **9.7.1** | 9.8.0 is current but outside Kotlin's tested range ([D4](../PLAN.md#3-key-decisions)) |
| | KSP | 2.3.12 | Independent versioning; min AGP 8.12.0 |
| | JDK | Temurin 21 runs Gradle; bytecode 17 | Robolectric SDK 36+ needs JDK 21 |
| | Android Studio | Rabbit 1 (2026.2.1) | Supports AGP 7.1–9.4 |
| | SDK levels | minSdk 26, compileSdk 37, targetSdk 37; Build Tools 36.0.0 | [D5](../PLAN.md#3-key-decisions), [PO-7](../PLAN.md#po-7-minsdk). Compose 1.12 requires compileSdk 37 and AGP ≥ 9.2 |
| UI | Compose BOM | 2026.09.00 → ui/foundation/animation 1.12.1, material3 1.4.0, material3-adaptive-navigation-suite 1.4.0 | Expressive (`material3` 1.5.0-alpha29) rejected: it pulls Compose core 1.13.0-alpha01 ([D6](../PLAN.md#3-key-decisions)) |
| | material3-adaptive (`adaptive`, `adaptive-layout`, `adaptive-navigation3`) | 1.3.0 | Pinned explicitly |
| | Navigation 3 (`navigation3-runtime`, `navigation3-ui`) | 1.2.0 | |
| | Lifecycle (incl. `lifecycle-viewmodel-navigation3`, `lifecycle-process`) | 2.11.0 | |
| | activity-compose / appcompat / core-ktx / core-splashscreen | 1.13.0 / 1.8.0 / 1.19.1 / 1.2.0 | AppCompat for per-app language |
| | graphics-shapes | 1.1.0 | Play/pause morph only |
| | `com.materialkolor:material-color-utilities` | 5.0.1 | Not `material-kolor`, not `androidx.palette` ([D57](../PLAN.md#3-key-decisions)). Unverified: POM licence MIT vs Apache-2.0 (both allowed) |
| | Coil 3 (`coil`, `coil-compose`, `coil-network-okhttp`, `coil-test`) | 3.6.3 | 3.6.3 fixes an AGP 9.4 R8 issue |
| | `sh.calvin.reorderable:reorderable` | 3.1.0 | Apache-2.0 |
| | AboutLibraries (Gradle plugin + `aboutlibraries-core`) | 15.2.0 | Core only, no Compose UI artifact ([Licensing](#aboutlibraries-and-the-licences-screen)) |
| | Glance (`glance-appwidget`, `glance-material3`) | 1.2.0 | v1.x (M13) only |
| DI | Dagger / Hilt (`dagger`, `dagger-compiler`, `hilt-android`, `hilt-compiler`, `hilt-android-testing`, Gradle plugin) | 2.60.1 | 2.59+ requires AGP 9 + Gradle 9.1+ |
| | androidx.hilt (`hilt-lifecycle-viewmodel-compose`, `hilt-work`, `hilt-compiler`) | 1.4.0 | |
| Data | Room 3 (`room3-runtime`, `room3-paging`, `room3-compiler`, `room3-testing`, plugin `androidx.room3`) | 3.0.3 | UUIDs as `TEXT` ([D21](../PLAN.md#3-key-decisions)) |
| | `androidx.sqlite:sqlite-bundled` / `sqlite-framework` | 2.7.1 | Bundled in production; framework (`AndroidSQLiteDriver`) in Robolectric tests ([D9](../PLAN.md#3-key-decisions)) |
| | Paging (`paging-common`, `paging-compose`, `paging-testing`) | 3.5.1 | |
| | DataStore Preferences | 1.2.1 | |
| | WorkManager (`work-runtime`, `work-testing`) | 2.12.0 | minSdk 24 |
| | documentfile | 1.1.0 | SAF, v1.x |
| Network | OkHttp BOM (`okhttp`, `okhttp-coroutines`, `okhttp-tls`, `mockwebserver3`, `mockwebserver3-junit4`) | 5.5.0 | **No OkHttp `Cache`** ([D10](../PLAN.md#3-key-decisions)) |
| | Okio | 3.18.2 | |
| | kotlinx.serialization JSON | 1.11.0 | |
| | kotlinx.coroutines (`core`, `android`, `guava`, `test`) | 1.11.0 | `-guava` for Media3 futures |
| | kotlinx-collections-immutable | 0.5.2 | |
| | jsoup | 1.23.2 | MIT |
| Media | Media3 (`exoplayer`, `session`, `datasource-okhttp`, `ui-compose`, `common-ktx`, `inspector`, `test-utils`, `test-utils-robolectric`) | 1.11.1 | `@UnstableApi` opt-in module-wide only in `:playback:impl` (lint config, [Convention plugins](#convention-plugins)); `ui-compose` unused until M14 ([06 UI boundary](06-playback.md#ui-boundary)); `media3-cast` v1.x `play` only |
| YouTube (`foss`) | NewPipe Extractor `com.github.teamnewpipe:NewPipeExtractor` | v0.26.5 | JitPack, exclusive-content repository; GPL-3.0-or-later; M9 |
| | Rhino (`org.mozilla:rhino`, `rhino-engine`) | 1.8.1, strict | Extractor-tested; 1.9 needs minSdk ≥ 26 and is untested by the extractor |
| | `com.android.tools:desugar_jdk_libs_nio` | 2.1.5 | Extractor needs it below API 33; enabled in M9 ([Build flavors](#core-library-desugaring)) |
| | android-youtube-player | 13.0.0 | v1.x, optional, `play` only |
| Quality | JUnit 4 / TestParameterInjector / Truth / Turbine / MockK | 4.13.2 / 1.24 / 1.4.5 / 1.2.1 / 1.14.11 | MockK never in `androidTest` |
| | Robolectric | 4.17 (pin `sdk=36`) | Force 4.17 and `okhttp-bom` 5.5.0 over Media3 test-utils' 4.16 / MockWebServer 4.12 |
| | Roborazzi | 1.76.0 | [D59](../PLAN.md#3-key-decisions) |
| | kxml2 | 2.3.0 | `compileOnly` + `testImplementation` in `:feeds` only |
| | androidx.test runner / ext-junit / espresso / orchestrator / uiautomator | 1.7.0 / 1.3.0 / 3.7.0 / 1.6.1 / 2.4.0 | |
| | Compose `ui-test-junit4` (v2 APIs), `ui-test-junit4-accessibility`, `ui-test-manifest` | 1.12.1 (BOM) | |
| | benchmark-macro / baselineprofile plugin; profileinstaller | 1.5.0; 1.4.1 | M11 |
| | LeakCanary | 2.14 | debug only |
| Tooling | Spotless / ktlint / compose-rules | 8.10.3 / 1.8.0 / 0.6.7 | blocking |
| | detekt | 2.0.0-alpha.6 | non-blocking |
| | Licensee / module-graph-assertion | 1.14.1 / 2.9.1 | |
| | Gradle Play Publisher | 4.1.1 | M11, only if PO-2 approves Play |
| | ACRA (`acra-mail`, `acra-dialog`) | 5.14.2 | [D62](../PLAN.md#3-key-decisions) |
| CI (09) | actions/checkout, setup-java, gradle/actions, upload-artifact, action-gh-release, codeql-action | v7.0.1, v6.0.1, v6.4.0, v7.0.1, v3.0.3, v4.38.2 (SHA-pinned) | android-emulator-runner v2.38.0 as GMD fallback |

**Banned** (enforced by [`verifyDependencyPolicy`](#gradle-side-policy-tasks) and plugin guards): `org.jetbrains.kotlin.android`, `kotlin-kapt` / `org.jetbrains.kotlin.kapt`, `androidx.compose.material:material-icons-extended`, `androidx.palette:*`, `com.materialkolor:material-kolor*` (Compose artifact), OkHttp `Cache`, any `com.google.android.gms`, `com.google.firebase`, `com.google.android.play`, `com.crashlytics`, `io.sentry` artifact in any v1.0 configuration (Cast arrives in v1.x as `playImplementation` only), `androidx.security:security-crypto` (deprecated; Keystore directly).

### `gradle/libs.versions.toml`

Complete catalog. Coordinates marked `# M9` / `# v1.x` are declared now so Renovate tracks them; nothing references them until that milestone.

```toml
[versions]
agp = "9.4.1"                       # fallback "9.3.3" (Spike S1)
kotlin = "2.4.20"
ksp = "2.3.12"
composeBom = "2026.09.00"
material3Adaptive = "1.3.0"
navigation3 = "1.2.0"
lifecycle = "2.11.0"
activity = "1.13.0"
appcompat = "1.8.0"
coreKtx = "1.19.1"
coreSplashscreen = "1.2.0"
graphicsShapes = "1.1.0"
glance = "1.2.0"                    # v1.x
materialColorUtilities = "5.0.1"
coil = "3.6.3"
reorderable = "3.1.0"
aboutlibraries = "15.2.0"
hilt = "2.60.1"
androidxHilt = "1.4.0"
room3 = "3.0.3"
sqlite = "2.7.1"
paging = "3.5.1"
datastore = "1.2.1"
work = "2.12.0"
documentfile = "1.1.0"              # v1.x
okhttp = "5.5.0"
okio = "3.18.2"
kotlinxSerialization = "1.11.0"
kotlinxCoroutines = "1.11.0"
kotlinxCollectionsImmutable = "0.5.2"
jsoup = "1.23.2"
media3 = "1.11.1"
newpipeExtractor = "v0.26.5"        # M9, foss only
rhino = "1.8.1"                     # M9, strict
desugarJdkLibsNio = "2.1.5"         # M9
androidYoutubePlayer = "13.0.0"     # v1.x, play only
acra = "5.14.2"
junit4 = "4.13.2"
testParameterInjector = "1.24"
truth = "1.4.5"
turbine = "1.2.1"
mockk = "1.14.11"
robolectric = "4.17"
roborazzi = "1.76.0"
kxml2 = "2.3.0"
androidxTestRunner = "1.7.0"
androidxTestExtJunit = "1.3.0"
espresso = "3.7.0"
androidxTestOrchestrator = "1.6.1"
uiautomator = "2.4.0"
benchmark = "1.5.0"
profileinstaller = "1.4.1"
leakcanary = "2.14"
spotless = "8.10.3"
ktlint = "1.8.0"
composeRules = "0.6.7"
detekt = "2.0.0-alpha.6"
licensee = "1.14.1"
moduleGraphAssert = "2.9.1"
playPublisher = "4.1.1"
```

```toml
[libraries]
# AndroidX core + Compose (Compose artifacts take versions from the BOM)
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-core-splashscreen = { module = "androidx.core:core-splashscreen", version.ref = "coreSplashscreen" }
androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "appcompat" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-runtime = { module = "androidx.compose.runtime:runtime" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-junit4-accessibility = { module = "androidx.compose.ui:ui-test-junit4-accessibility" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
androidx-compose-animation = { module = "androidx.compose.animation:animation" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-material3-navigationSuite = { module = "androidx.compose.material3:material3-adaptive-navigation-suite" }
androidx-compose-material3-adaptive = { module = "androidx.compose.material3.adaptive:adaptive", version.ref = "material3Adaptive" }
androidx-compose-material3-adaptive-layout = { module = "androidx.compose.material3.adaptive:adaptive-layout", version.ref = "material3Adaptive" }
androidx-compose-material3-adaptive-navigation3 = { module = "androidx.compose.material3.adaptive:adaptive-navigation3", version.ref = "material3Adaptive" }
androidx-graphics-shapes = { module = "androidx.graphics:graphics-shapes", version.ref = "graphicsShapes" }
androidx-navigation3-runtime = { module = "androidx.navigation3:navigation3-runtime", version.ref = "navigation3" }
androidx-navigation3-ui = { module = "androidx.navigation3:navigation3-ui", version.ref = "navigation3" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-navigation3 = { module = "androidx.lifecycle:lifecycle-viewmodel-navigation3", version.ref = "lifecycle" }
androidx-lifecycle-process = { module = "androidx.lifecycle:lifecycle-process", version.ref = "lifecycle" }
androidx-glance-appwidget = { module = "androidx.glance:glance-appwidget", version.ref = "glance" }
androidx-glance-material3 = { module = "androidx.glance:glance-material3", version.ref = "glance" }
materialColorUtilities = { module = "com.materialkolor:material-color-utilities", version.ref = "materialColorUtilities" }
coil-bom = { module = "io.coil-kt.coil3:coil-bom", version.ref = "coil" }
coil-core = { module = "io.coil-kt.coil3:coil" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose" }
coil-network-okhttp = { module = "io.coil-kt.coil3:coil-network-okhttp" }
coil-test = { module = "io.coil-kt.coil3:coil-test" }
reorderable = { module = "sh.calvin.reorderable:reorderable", version.ref = "reorderable" }
aboutlibraries-core = { module = "com.mikepenz:aboutlibraries-core", version.ref = "aboutlibraries" }
# DI
dagger = { module = "com.google.dagger:dagger", version.ref = "hilt" }
dagger-compiler = { module = "com.google.dagger:dagger-compiler", version.ref = "hilt" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
hilt-android-testing = { module = "com.google.dagger:hilt-android-testing", version.ref = "hilt" }
androidx-hilt-lifecycle-viewmodel-compose = { module = "androidx.hilt:hilt-lifecycle-viewmodel-compose", version.ref = "androidxHilt" }
androidx-hilt-work = { module = "androidx.hilt:hilt-work", version.ref = "androidxHilt" }
androidx-hilt-compiler = { module = "androidx.hilt:hilt-compiler", version.ref = "androidxHilt" }
# Data
androidx-room3-runtime = { module = "androidx.room3:room3-runtime", version.ref = "room3" }
androidx-room3-paging = { module = "androidx.room3:room3-paging", version.ref = "room3" }
androidx-room3-compiler = { module = "androidx.room3:room3-compiler", version.ref = "room3" }
androidx-room3-testing = { module = "androidx.room3:room3-testing", version.ref = "room3" }
androidx-sqlite-bundled = { module = "androidx.sqlite:sqlite-bundled", version.ref = "sqlite" }
androidx-sqlite-framework = { module = "androidx.sqlite:sqlite-framework", version.ref = "sqlite" }
androidx-paging-common = { module = "androidx.paging:paging-common", version.ref = "paging" }
androidx-paging-compose = { module = "androidx.paging:paging-compose", version.ref = "paging" }
androidx-paging-testing = { module = "androidx.paging:paging-testing", version.ref = "paging" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
androidx-work-runtime = { module = "androidx.work:work-runtime", version.ref = "work" }
androidx-work-testing = { module = "androidx.work:work-testing", version.ref = "work" }
androidx-documentfile = { module = "androidx.documentfile:documentfile", version.ref = "documentfile" }
# Network and serialization (OkHttp artifacts take versions from the BOM)
okhttp-bom = { module = "com.squareup.okhttp3:okhttp-bom", version.ref = "okhttp" }
okhttp = { module = "com.squareup.okhttp3:okhttp" }
okhttp-coroutines = { module = "com.squareup.okhttp3:okhttp-coroutines" }
okhttp-tls = { module = "com.squareup.okhttp3:okhttp-tls" }
okhttp-mockwebserver3 = { module = "com.squareup.okhttp3:mockwebserver3" }
okhttp-mockwebserver3-junit4 = { module = "com.squareup.okhttp3:mockwebserver3-junit4" }
okio = { module = "com.squareup.okio:okio", version.ref = "okio" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-guava = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-guava", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
kotlinx-collections-immutable = { module = "org.jetbrains.kotlinx:kotlinx-collections-immutable", version.ref = "kotlinxCollectionsImmutable" }
jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }
# Media
androidx-media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
androidx-media3-session = { module = "androidx.media3:media3-session", version.ref = "media3" }
androidx-media3-datasource-okhttp = { module = "androidx.media3:media3-datasource-okhttp", version.ref = "media3" }
androidx-media3-ui-compose = { module = "androidx.media3:media3-ui-compose", version.ref = "media3" }
androidx-media3-common-ktx = { module = "androidx.media3:media3-common-ktx", version.ref = "media3" }
androidx-media3-inspector = { module = "androidx.media3:media3-inspector", version.ref = "media3" }
androidx-media3-test-utils = { module = "androidx.media3:media3-test-utils", version.ref = "media3" }
androidx-media3-test-utils-robolectric = { module = "androidx.media3:media3-test-utils-robolectric", version.ref = "media3" }
# YouTube, foss only (M9)
newpipe-extractor = { module = "com.github.teamnewpipe:NewPipeExtractor", version.ref = "newpipeExtractor" }
rhino = { module = "org.mozilla:rhino", version.ref = "rhino" }
rhino-engine = { module = "org.mozilla:rhino-engine", version.ref = "rhino" }
desugar-jdk-libs-nio = { module = "com.android.tools:desugar_jdk_libs_nio", version.ref = "desugarJdkLibsNio" }
android-youtube-player = { module = "com.pierfrancescosoffritti.androidyoutubeplayer:core", version.ref = "androidYoutubePlayer" } # v1.x
# Crash reporting
acra-mail = { module = "ch.acra:acra-mail", version.ref = "acra" }
acra-dialog = { module = "ch.acra:acra-dialog", version.ref = "acra" }
# Test
junit4 = { module = "junit:junit", version.ref = "junit4" }
testParameterInjector = { module = "com.google.testparameterinjector:test-parameter-injector", version.ref = "testParameterInjector" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
roborazzi = { module = "io.github.takahirom.roborazzi:roborazzi", version.ref = "roborazzi" }
roborazzi-compose = { module = "io.github.takahirom.roborazzi:roborazzi-compose", version.ref = "roborazzi" }
roborazzi-junit-rule = { module = "io.github.takahirom.roborazzi:roborazzi-junit-rule", version.ref = "roborazzi" }
kxml2 = { module = "net.sf.kxml:kxml2", version.ref = "kxml2" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExtJunit" }
androidx-test-espresso-core = { module = "androidx.test.espresso:espresso-core", version.ref = "espresso" }
androidx-test-orchestrator = { module = "androidx.test:orchestrator", version.ref = "androidxTestOrchestrator" }
androidx-test-uiautomator = { module = "androidx.test.uiautomator:uiautomator", version.ref = "uiautomator" }
androidx-benchmark-macro-junit4 = { module = "androidx.benchmark:benchmark-macro-junit4", version.ref = "benchmark" }
androidx-profileinstaller = { module = "androidx.profileinstaller:profileinstaller", version.ref = "profileinstaller" }
leakcanary-android = { module = "com.squareup.leakcanary:leakcanary-android", version.ref = "leakcanary" }
# build-logic classpath only (plugin markers for third-party plugins: <id>:<id>.gradle.plugin)
android-gradlePlugin = { module = "com.android.tools.build:gradle", version.ref = "agp" }
kotlin-gradlePlugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
kotlin-composeGradlePlugin = { module = "org.jetbrains.kotlin:compose-compiler-gradle-plugin", version.ref = "kotlin" }
kotlin-serializationGradlePlugin = { module = "org.jetbrains.kotlin:kotlin-serialization", version.ref = "kotlin" }
ksp-gradlePlugin = { module = "com.google.devtools.ksp:symbol-processing-gradle-plugin", version.ref = "ksp" }
hilt-gradlePlugin = { module = "com.google.dagger:hilt-android-gradle-plugin", version.ref = "hilt" }
room3-gradlePlugin = { module = "androidx.room3:room3-gradle-plugin", version.ref = "room3" }
spotless-gradlePlugin = { module = "com.diffplug.spotless:com.diffplug.spotless.gradle.plugin", version.ref = "spotless" }
licensee-gradlePlugin = { module = "app.cash.licensee:app.cash.licensee.gradle.plugin", version.ref = "licensee" }
moduleGraphAssert-gradlePlugin = { module = "com.jraska.module.graph.assertion:com.jraska.module.graph.assertion.gradle.plugin", version.ref = "moduleGraphAssert" }
aboutlibraries-gradlePlugin = { module = "com.mikepenz.aboutlibraries.plugin:com.mikepenz.aboutlibraries.plugin.gradle.plugin", version.ref = "aboutlibraries" }

[bundles]
unit-test = ["junit4", "truth", "turbine", "kotlinx-coroutines-test", "testParameterInjector"]
compose-core = ["androidx-compose-ui", "androidx-compose-foundation", "androidx-compose-animation", "androidx-compose-material3", "androidx-compose-ui-tooling-preview"]

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
android-test = { id = "com.android.test", version.ref = "agp" }
android-lint = { id = "com.android.lint", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
room3 = { id = "androidx.room3", version.ref = "room3" }
detekt = { id = "dev.detekt", version.ref = "detekt" }            # Unverified: 2.0 plugin id (1.x was io.gitlab.arturbosch.detekt)
roborazzi = { id = "io.github.takahirom.roborazzi", version.ref = "roborazzi" }
baselineprofile = { id = "androidx.baselineprofile", version.ref = "benchmark" }
play-publisher = { id = "com.github.triplet.play", version.ref = "playPublisher" }
# convention plugins (no version: provided by the included build)
neutrodyne-android-application = { id = "neutrodyne.android.application" }
neutrodyne-android-library = { id = "neutrodyne.android.library" }
neutrodyne-android-compose = { id = "neutrodyne.android.compose" }
neutrodyne-android-feature = { id = "neutrodyne.android.feature" }
neutrodyne-android-testing = { id = "neutrodyne.android.testing" }
neutrodyne-android-lint = { id = "neutrodyne.android.lint" }
neutrodyne-hilt = { id = "neutrodyne.hilt" }
neutrodyne-room = { id = "neutrodyne.room" }
neutrodyne-jvm-library = { id = "neutrodyne.jvm.library" }
neutrodyne-quality = { id = "neutrodyne.quality" }
```

Rules for the catalog:

1. Every external coordinate lives here; build files never hard-code a version. `resolutionStrategy { failOnDynamicVersions(); failOnChangingVersions() }` is applied by every convention plugin.
2. Module build files apply plugins only by `alias(libs.plugins.neutrodyne-*)` or by bare `id("…")` for plugins already on the build-logic classpath; versioned `alias(...)` is used only for tooling plugins not on that classpath (`detekt`, `roborazzi`, `baselineprofile`, `play-publisher`, `android-test`).
3. Rhino gets a strict constraint in `:youtube:streams`: `constraints { implementation(libs.rhino) { version { strictly("1.8.1") } } }` (same for `rhino-engine`).
4. BOM-managed artifacts (Compose, OkHttp, Coil) are declared without a version, so their platform must be on the same configuration. `neutrodyne.android.library`, `neutrodyne.android.application` and `neutrodyne.jvm.library` add `platform(okhttp-bom)` and `platform(coil-bom)` to `implementation`, `testImplementation` and (Android) `androidTestImplementation` of every module (a platform adds constraints only, no artifact); `neutrodyne.android.compose` does the same for `compose-bom`. A module that exposes a BOM-managed artifact as `api` (`:core:network` → `okhttp`, `:core:navigation` → `compose-runtime`) also declares `api(platform(...))`.
5. Plugin classes in `build-logic` have no type-safe `libs` accessor. They read the catalog with `val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")` and `libs.findLibrary("okhttp-bom").get()` / `libs.findVersion("kotlin").get().requiredVersion`. Module build scripts use the type-safe accessors.

### `settings.gradle.kts`, `gradle.properties`, root build

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()
        exclusiveContent {                       // JitPack serves ONLY these groups, and these groups come ONLY from JitPack
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.teamnewpipe"); includeGroup("com.github.TeamNewPipe") }
        }
    }
}
rootProject.name = "Neutrodyne"
include(":app")
include(":core:model", ":core:common", ":core:domain", ":core:navigation", ":core:database", ":core:datastore",
        ":core:network", ":core:data", ":core:artwork", ":core:designsystem", ":core:ui", ":core:testing")
include(":feeds")
include(":playback:api", ":playback:impl", ":download:api", ":download:impl")
include(":youtube:api", ":youtube:impl", ":youtube:streams")
include(":feature:feeds", ":feature:library", ":feature:groups", ":feature:podcast", ":feature:episode", ":feature:player",
        ":feature:queue", ":feature:downloads", ":feature:discover", ":feature:importexport", ":feature:settings")
// M11: include(":benchmark")   v1.x: include(":playback:cast", ":feature:widgets")
```

`com.github.TeamNewPipe` is the group of the extractor's `nanojson` dependency. Both spellings are listed because JitPack group IDs follow the GitHub owner's case.

```properties
# gradle.properties (committed; never contains secrets)
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
kotlin.code.style=official
kotlin.daemon.jvmargs=-Xmx3g
# AGP 9 defaults we rely on; do NOT opt out:
#   android.builtInKotlin=true, android.newDsl=true, android.uniquePackageNames=true,
#   android.r8.optimizedResourceShrinking=true, android.r8.strictFullModeForKeepRules=true,
#   android.onlyEnableUnitTestForTheTestedBuildType=true, android.defaults.buildfeatures.resValues=false
# Version, single source of truth (scheme D63, procedure in 09):
neutrodyne.versionName=0.1.0-beta.1
neutrodyne.versionCode=10001
neutrodyne.repoUrl=https://github.com/OWNER/Neutrodyne
# Read with providers.gradleProperty(...).orElse(""), supplied only via -P or ~/.gradle/gradle.properties:
#   neutrodyne.acraMailto, neutrodyne.podcastIndexKey, neutrodyne.podcastIndexSecret
```

`OWNER` is replaced when PO-18 names the GitHub owner (M0 blocker only for the About link, not for the build). Gradle logic never reads git (F-Droid builds without `.git` history), never embeds timestamps, and never reads environment variables except the `NEUTRODYNE_KEYSTORE*` signing variables ([09 Versioning and signing](09-quality-and-release.md#versioning-and-signing)).

Root `build.gradle.kts` contains only `plugins { alias(libs.plugins.neutrodyne.quality); alias(libs.plugins.detekt) apply false }`.

**Gradle dependency verification (decision):** enabled **only for JitPack-hosted artifacts**. `gradle/verification-metadata.xml` stores SHA-256 checksums for `com.github.teamnewpipe` / `com.github.TeamNewPipe` artifacts and trusts everything else with a negative-lookahead rule (`<trust group="^(?!com\.github\.[Tt]eam[Nn]ew[Pp]ipe$).*$" regex="true"/>`). Rationale: JitPack builds from mutable Git state and is the only repository without signed, immutable releases; full verification would make every Renovate PR fail until someone regenerates metadata locally. Regenerate with `./gradlew --write-verification-metadata sha256 :app:assembleFossRelease` on every extractor bump (the Renovate fast lane PR template says so, [09 Dependency updates](09-quality-and-release.md#dependency-updates)). Unverified: that Gradle honours the lookahead trust regex as intended — checked in M9 when JitPack is first resolved; if it does not, drop verification and rely on exact tags plus the exclusive-content filter.

### Convention plugins

`build-logic/settings.gradle.kts` declares its own repositories (`dependencyResolutionManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }`, with the same `google { content { … } }` filter as the root) and reuses the root catalog (`versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }`). `build-logic/convention/build.gradle.kts` applies `kotlin-dsl` and declares as **`implementation`** (not `compileOnly`, see [S1](#s1-kgp-2420-under-agp-941)): `android-gradlePlugin`, `kotlin-gradlePlugin`, `kotlin-composeGradlePlugin`, `kotlin-serializationGradlePlugin`, `ksp-gradlePlugin`, `hilt-gradlePlugin`, `room3-gradlePlugin`, `spotless-gradlePlugin`, `licensee-gradlePlugin`, `moduleGraphAssert-gradlePlugin`, `aboutlibraries-gradlePlugin`. Plugin classes live in `build-logic/convention/src/main/kotlin/` and are registered under the canonical IDs.

| Plugin ID | Applied to | Configures |
|---|---|---|
| `neutrodyne.android.application` | `:app` | `com.android.application`; [common Android config](#common-android-configuration); `applicationId`, version from `gradle.properties`; flavors and build types ([Build flavors](#build-flavors)); `androidResources.generateLocaleConfig = true`; `dependenciesInfo { includeInApk = false; includeInBundle = false }`; `bundle.language.enableSplit = false` (Unverified necessity for per-app language with AABs; harmless); applies `app.cash.licensee`, `com.jraska.module.graph.assertion`, `com.mikepenz.aboutlibraries.plugin`; registers [`verifyDependencyPolicy`, `verifyManifestPermissions`](#gradle-side-policy-tasks); applies `neutrodyne.android.lint` and `neutrodyne.android.testing` |
| `neutrodyne.android.library` | every Android library | `com.android.library`; common Android config; `namespace` derived from the path; `consumerProguardFiles("consumer-rules.pro")` when present; `buildFeatures { buildConfig = false; aidl = false; shaders = false }`; applies `neutrodyne.android.lint` and `neutrodyne.android.testing` |
| `neutrodyne.android.compose` | `:app`, `:core:designsystem`, `:core:ui`, features | `org.jetbrains.kotlin.plugin.compose`; `buildFeatures.compose = true`; `platform(compose-bom)` on `implementation`, `androidTestImplementation`, `testImplementation`; `ui-tooling-preview`; `debugImplementation(ui-tooling, ui-test-manifest)`; `composeCompiler { stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf")); if (-PcomposeReports) reportsDestination/metricsDestination = build/compose }`. Adds `-opt-in=androidx.compose.material3.ExperimentalMaterial3Api` **only** when the project path is `:core:designsystem`; `Nd*` wrappers therefore must not expose experimental Material 3 types in their public signatures ([08 Theming and colour](08-ui-ux.md#theming-and-colour)) |
| `neutrodyne.android.feature` | `:feature:*` | applies `neutrodyne.android.library`, `neutrodyne.android.compose`, `neutrodyne.hilt`; adds `:core:{domain, model, common, designsystem, ui, navigation}`, `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`, `lifecycle-viewmodel-navigation3`, `hilt-lifecycle-viewmodel-compose`, `navigation3-runtime`, `adaptive-navigation3`, `paging-compose`, `kotlinx-collections-immutable`. `:*:api` modules are added explicitly per feature |
| `neutrodyne.android.testing` | applied by application/library plugins | Hook only; content owned by [09 Test infrastructure](09-quality-and-release.md#test-infrastructure) (Robolectric `sdk=36`, JDK 21, `de_DE` + `America/St_Johns`, golden switch, `okhttp-bom` and Robolectric forcing, orchestrator, GMD definitions). Adds `testImplementation(project(":core:testing"))` except in `:core:testing` itself |
| `neutrodyne.android.lint` | all modules (JVM modules via `com.android.lint`) | Gates owned by [09 Static analysis](09-quality-and-release.md#static-analysis) (`warningsAsErrors`, baseline, SARIF, `checkDependencies` in `:app`). One foundation rule: Media3's `@UnstableApi` is an AndroidX `RequiresOptIn` marker enforced by Lint (`UnsafeOptInUsageError`), not by the Kotlin compiler, so the module-wide opt-in is a lint config: when the project path is `:playback:impl`, `lint { lintConfig = file("lint.xml") }` with `<issue id="UnsafeOptInUsageError"><ignore regexp='\(markerClass = androidx\.media3\.common\.util\.UnstableApi\.class\)' /></issue>` ([UnstableApi](https://developer.android.com/reference/androidx/media3/common/util/UnstableApi)). Everywhere else an unstable Media3 call stays a lint error |
| `neutrodyne.hilt` | modules using DI | Android: `com.google.devtools.ksp` + `com.google.dagger.hilt.android`, `implementation(hilt-android)`, `ksp(hilt-compiler)`. JVM: `com.google.devtools.ksp`, `implementation(dagger)`, `ksp(dagger-compiler)` (generates `_Factory` classes for `@Inject` constructors; no Hilt modules in JVM modules). Modules with `@HiltWorker` additionally declare `implementation(androidx-hilt-work)` + `ksp(androidx-hilt-compiler)` |
| `neutrodyne.room` | `:core:database` | `androidx.room3` + KSP; `room3 { schemaDirectory("$projectDir/schemas") }` (→ `core/database/schemas/`); `api(room3-runtime)`, `api(room3-paging)`, `api(paging-common)`, `implementation(sqlite-bundled)`, `ksp(room3-compiler)`, `testImplementation(room3-testing, sqlite-framework)`. Room usage conventions: [02 Conventions](02-data-model.md#conventions) |
| `neutrodyne.jvm.library` | `:core:model`, `:core:common`, `:core:domain`, `:feeds`, `:*:api` | `org.jetbrains.kotlin.jvm`; no toolchain provisioning: Kotlin `jvmTarget = 17` plus `-Xjdk-release=17`, `JavaCompile.options.release = 17`; `com.android.lint`; JUnit 4 test deps |
| `neutrodyne.quality` | root only | Spotless (ktlint 1.8.0 + compose-rules 0.6.7 for `**/*.kt` and `**/*.kts`; settings in `.editorconfig`, owned by 09); registers [`checkSpdxHeaders` and `checkBannedApis`](#gradle-side-policy-tasks) and wires them into `check` |

#### Common Android configuration

Applied by both Android plugins (sketch; AGP 9.4 `CommonExtension` is non-generic):

```kotlin
internal fun Project.configureAndroidCommon(ext: CommonExtension) {
    ext.compileSdk = 37                          // AGP 9.4 also offers a compileSdk {} block; either, never compileSdkVersion()
    ext.buildToolsVersion = "36.0.0"
    ext.defaultConfig.minSdk = 26
    ext.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    ext.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    ext.packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")   // never META-INF/LICENSE* or NOTICE*
    extensions.configure<KotlinAndroidProjectExtension> {                     // Unverified type name under built-in Kotlin (S1)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17); allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").isPresent) }
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.android") { error("kotlin-android is banned: AGP 9 built-in Kotlin") }
    pluginManager.withPlugin("org.jetbrains.kotlin.kapt") { error("kapt is banned: use KSP") }
    configurations.configureEach { resolutionStrategy { failOnDynamicVersions(); failOnChangingVersions() } }
}
// targetSdk is set explicitly in the application plugin (AGP 9 defaults it to compileSdk if unset):
//   defaultConfig.targetSdk = 37
// namespace for libraries: "app.neutrodyne" + path.replace(':', '.')   (":core:model" -> "app.neutrodyne.core.model")
```

`compose-stability.conf` (repo root) lists `app.neutrodyne.core.model.**`, `kotlinx.collections.immutable.*`, `kotlin.time.Duration`. It is valid only because `:core:model` types are deeply immutable by rule ([Architecture patterns](#model-and-state-rules)).

#### Gradle-side policy tasks

These run in `check`; CI (09) only invokes Gradle.

| Task | Project | Fails when |
|---|---|---|
| `assertModuleGraph` | `:app` (module-graph-assertion plugin) | any edge violates [Dependency rules](#dependency-rules) |
| `licenseeFossRelease`, `licenseePlayRelease` | `:app` | a runtime dependency's licence is not allowed ([allow-list](#licensee-allow-list)) |
| `verifyDependencyPolicy` | `:app` | `playReleaseRuntimeClasspath` contains `project :youtube:streams`, `com.github.teamnewpipe:*`, `com.github.TeamNewPipe:*`, `org.mozilla:rhino*`, or any artifact whose Licensee report SPDX is GPL/LGPL/AGPL/MPL (except `desugar_jdk_libs*`); **either** release classpath contains a banned artifact (list above); `fossReleaseRuntimeClasspath` contains Google Play services / Firebase / Play Core / Crashlytics / Sentry |
| `verifyManifestPermissions` | `:app` | the merged manifest of `fossRelease` or `playRelease` (`SingleArtifact.MERGED_MANIFEST`) declares a `uses-permission` not listed in `app/policy/permissions.txt`, or lacks one listed there |
| `checkSpdxHeaders` | root | a `*.kt`/`*.java`/`*.kts` under `youtube/streams/` lacks `// SPDX-License-Identifier: GPL-3.0-or-later` as its first line, or any `*.kt`/`*.java`/`*.kts` file elsewhere contains `SPDX-License-Identifier: (A\|L)?GPL` or `MPL` (Markdown and other docs are not scanned) |
| `checkBannedApis` | root | scans only the `src/main/**` Kotlin sources and manifests of root-build modules (not `build-logic/`; tests may build their own clients): source outside `core/designsystem/` contains `ExperimentalMaterial3Api` or `ExperimentalMaterial3ExpressiveApi`; source outside `playback/impl/` contains `UnstableApi`; any source contains `okhttp3.Cache(`, `.cache(Cache(`, `OkHttpClient()` or `OkHttpClient.Builder()` outside `core/network/`, `GlobalScope`, `override fun onBackPressed`, `collectAsState()` in `feature/`, or `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |

`checkBannedApis` is a plain text scan (fast, configuration-cache safe); false positives are fixed by rewording, never by suppression lists. `verifyDependencyPolicy` and `verifyManifestPermissions` must also stay configuration-cache safe: they take their inputs as providers (`configurations.named("playReleaseRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }`, the Licensee JSON report file of the same variant, and the variant's `SingleArtifact.MERGED_MANIFEST` from `androidComponents.onVariants`), never by resolving configurations at configuration time. Both check release variants only (debug merges LeakCanary and test-only entries).

---

## Module layout

Serves N11. Delivered in M0 (stubs), content by milestone per the "Content from" column of the canonical module list ([D13](../PLAN.md#3-key-decisions)).

Every module except `:benchmark` (M11), `:playback:cast` and `:feature:widgets` (v1.x) is created in M0 as a compiling stub: `build.gradle.kts`, the package directory, one `internal` placeholder declaration and one placeholder test. Packages and AGP namespaces follow `app.neutrodyne` + path (`:youtube:streams` → `app.neutrodyne.youtube.streams`).

| Module | Plugins | Project dependencies (main) | External dependencies | Content from |
|---|---|---|---|---|
| `:app` | `neutrodyne.android.application`, `.android.compose`, `.hilt` | every feature; `:core:{model, common, domain, navigation, database, datastore, network, data, artwork, designsystem, ui}`; `:playback:{api, impl}`; `:download:{api, impl}`; `:youtube:{api, impl}`; `fossImplementation(:youtube:streams)` | appcompat, activity-compose, core-ktx, core-splashscreen, navigation3-runtime/-ui, adaptive-navigation3, material3-adaptive-navigation-suite, lifecycle-viewmodel-navigation3, hilt-lifecycle-viewmodel-compose, androidx-hilt-work, work-runtime, coil-core, coil-compose, kotlinx-coroutines-android, acra-mail, acra-dialog; `debugImplementation(leakcanary)`; M9: `coreLibraryDesugaring(desugar-jdk-libs-nio)` | M0 |
| `:core:model` | `neutrodyne.jvm.library` | — | — | M0 |
| `:core:common` | `.jvm.library`, `.hilt` | — | coroutines-core, dagger (for `javax.inject`) | M0 |
| `:core:domain` | `.jvm.library`, `.hilt` | `api`: `:core:{model, common}`, `:playback:api`, `:download:api`, `:youtube:api` | `api(paging-common)`, coroutines-core | M0 |
| `:core:navigation` | `.android.library`, `kotlin.plugin.serialization` | — | `api(navigation3-runtime)`, `api(platform(compose-bom))` + `api(compose-runtime)` (for `staticCompositionLocalOf`; no Compose compiler plugin), kotlinx-serialization-json | M0 |
| `:core:database` | `.android.library`, `.hilt`, `.room` | `:core:{model, common}` | (from `neutrodyne.room`), kotlinx-serialization-json | M1 |
| `:core:datastore` | `.android.library`, `.hilt` | `:core:{model, common}` | datastore-preferences | M0 |
| `:core:network` | `.android.library`, `.hilt` | `:core:{model, common}` | `api(okhttp)` via BOM, okhttp-coroutines, okio | M0 |
| `:core:data` | `.android.library`, `.hilt`, `kotlin.plugin.serialization` | `:core:{domain, model, common, database, datastore, network, artwork}`, `:feeds`, `:youtube:api` | work-runtime, androidx-hilt-work (+ compiler), okhttp-coroutines, kotlinx-serialization-json, lifecycle-process | M1 (M0: stub that binds `CredentialLookup.None`) |
| `:core:artwork` | `.android.library`, `.hilt` | `:core:{model, common, database, network}`, `:youtube:api` | coil-core, coil-network-okhttp, work-runtime, androidx-hilt-work (+ compiler); M10: material-color-utilities | M1 (Coil components), M4 (store) |
| `:core:designsystem` | `.android.library`, `.android.compose` | `:core:model` | compose-core bundle, material3-adaptive-navigation-suite, graphics-shapes, coil-compose, kotlinx-collections-immutable; M10: material-color-utilities | M0 |
| `:core:ui` | `.android.library`, `.android.compose` | `:core:{designsystem, model, common}` | coil-compose, kotlinx-collections-immutable, reorderable (M4) | M1 |
| `:core:testing` | `.android.library`, `.hilt` | `:core:{domain, model, common}`, `:playback:api`, `:download:api`, `:youtube:api` | `api`: junit4, truth, turbine, coroutines-test, coil-test, hilt-android-testing | M0 |
| `:feeds` | `.jvm.library`, `kotlin.plugin.serialization` | — | jsoup, kotlinx-serialization-json; `compileOnly` + `testImplementation(kxml2)` | M1 |
| `:playback:api` | `.jvm.library`, `.hilt` (JVM: `@Inject`/qualifiers only) | `:core:{model, common}` | coroutines-core | M0 |
| `:playback:impl` | `.android.library`, `.hilt` | `:playback:api`, `:download:api`, `:youtube:api`, `:core:{domain, model, common, database, datastore, network, artwork}` | media3-exoplayer, -session, -datasource-okhttp, -common-ktx, -inspector (M5), kotlinx-coroutines-guava, lifecycle-process (`PlayerConnection`), kotlinx-serialization-json (06) | M4 |
| `:download:api` | `.jvm.library`, `.hilt` (JVM) | `:core:{model, common}` | coroutines-core | M0 |
| `:download:impl` | `.android.library`, `.hilt` | `:download:api`, `:youtube:api`, `:core:{domain, model, common, database, datastore, network, artwork}` | work-runtime, androidx-hilt-work (+ compiler), okhttp-coroutines | M6 |
| `:youtube:api` | `.jvm.library`, `.hilt` (JVM) | `:core:{model, common}` | coroutines-core | M2 (`YouTubeCapabilities`), M3 (classifier), M4 (`YouTubeStreamResolver` contract), M8 (rest); see [Flavor modules](#flavor-modules) |
| `:youtube:impl` | `.android.library`, `.hilt` | `:youtube:api`, `:core:{model, common, network}` | okhttp-coroutines, kotlinx-serialization-json, jsoup | M4 (`ExternalOnlyYouTubeStreamResolver` only), M8 |
| `:youtube:streams` | `.android.library`, `.hilt` — **GPL-3.0-or-later** | `:youtube:api`, `:core:{model, common, network}` | M9: newpipe-extractor, rhino + rhino-engine (strict 1.8.1) | M9 (M0 stub) |
| `:feature:feeds` | `neutrodyne.android.feature` | + `:playback:api`, `:download:api`, `:youtube:api` | — | M1 (All), M2 |
| `:feature:library` | feature | + `:youtube:api` | — | M1 |
| `:feature:groups` | feature | — | reorderable | M2 |
| `:feature:podcast` | feature | + `:playback:api`, `:download:api`, `:youtube:api` | — | M1 |
| `:feature:episode` | feature | + `:playback:api`, `:download:api`, `:youtube:api` | — | M1 |
| `:feature:player` | feature | + `:playback:api` | none in v1.0: no Media3 type enters a feature ([06 UI boundary](06-playback.md#ui-boundary)); M14 adds `media3-ui-compose` for `PlayerSurface` | M4 |
| `:feature:queue` | feature | + `:playback:api` | reorderable | M4 |
| `:feature:downloads` | feature | + `:download:api` | — | M6 |
| `:feature:discover` | feature | + `:youtube:api` | — | M1 (add by URL), M7 |
| `:feature:importexport` | feature | — | — | M3 |
| `:feature:settings` | feature | + `:youtube:api` | aboutlibraries-core | M0 |
| `:benchmark` | `com.android.test`, `androidx.baselineprofile` | `targetProjectPath = ":app"` | benchmark-macro-junit4, uiautomator | M11 |

**`api` vs `implementation`:** a module exposes a dependency as `api` only when its types appear in that module's public signatures (`:core:domain` → `:core:model`, `paging-common`; `:core:network` → `okhttp`; `:core:database` → `room3-runtime`, `room3-paging`); everything else is `implementation`. Implementation classes in impl modules are `internal`; only Hilt modules, `@AndroidEntryPoint` components and the public API are `public`.

**External-library placement:** Room only through `:core:database`; DataStore only in `:core:datastore`; OkHttp clients only constructed in `:core:network`; Media3 player/session only in `:playback:impl`; WorkManager workers only in `:core:data`, `:core:artwork`, `:download:impl` (configuration in `:app`); images are rendered only through `:core:designsystem`/`:core:ui` composables ([08 Artwork pipeline](08-ui-ux.md#artwork-pipeline)).

---

## Dependency rules

Serves N11, N8. Delivered in M0 (enforced from the first commit). Rules 1–9 expand PLAN [5.1](../PLAN.md#51-module-graph) rules 1–5 module by module; rules 10–13 (marked ⊕) make explicit the edges the PLAN 5.1 graph implies but its rule list leaves open.

| # | Rule |
|---|---|
| 1 | `:app` → anything. Nothing → `:app`. Only `:app` has product flavors. `:youtube:streams` is added only as `fossImplementation`; `:playback:cast` (v1.x) only as `playImplementation`. |
| 2 | `:feature:*` → `:core:{domain, model, common, designsystem, ui, navigation}`, `:playback:api`, `:download:api`, `:youtube:api`. Never feature → feature; never → `:core:{data, database, datastore, network, artwork}`, `*:impl`, `:youtube:streams`. |
| 3 | `:core:domain` → `:core:{model, common}`, `:playback:api`, `:download:api`, `:youtube:api`, `paging-common`. |
| 4 | `:core:data` → `:core:{domain, model, common, database, datastore, network, artwork}`, `:feeds`, `:youtube:api`. |
| 5 | `:core:artwork` → `:core:{model, common, database, network}`, `:youtube:api`. |
| 6 | `:playback:impl` → `:playback:api`, `:download:api`, `:youtube:api`, `:core:{domain, model, common, database, datastore, network, artwork}`. `:download:impl` → `:download:api`, `:youtube:api`, `:core:{domain, model, common, database, datastore, network, artwork}`. `:youtube:impl` and `:youtube:streams` → `:youtube:api`, `:core:{model, common, network}`. No impl → `:core:data`, no impl → another impl. YouTube Atom feeds are fetched and parsed by the generic refresh engine in `:core:data` ([03](03-feeds-and-discovery.md#refresh-scheduling)) using `:youtube:api` helpers; no YouTube module parses Atom. |
| 7 | `:core:designsystem` → `:core:model` only. `:core:ui` → `:core:{designsystem, model, common}`. `:core:navigation` → nothing project-internal. |
| 8 | JVM-only: `:core:model`, `:core:common`, `:core:domain`, `:feeds`, `:*:api`. `:feeds` depends on nothing project-internal. |
| 9 | `:core:testing` → `:core:{domain, model, common}`, `:*:api`. |
| 10 ⊕ | `:playback:api`, `:download:api`, `:youtube:api` → `:core:{model, common}`. |
| 11 ⊕ | `:core:database`, `:core:datastore`, `:core:network` → `:core:{model, common}`. |
| 12 ⊕ | `:core:model` and `:core:common` → nothing project-internal. |
| 13 ⊕ | `testImplementation`/`androidTestImplementation` edges are not asserted; test code may use `:core:testing` anywhere, and the [Gradle-side tasks](#gradle-side-policy-tasks) check runtime classpaths. |

Consequences implementers must design for:

- **Interfaces in `:core:domain`, bindings in implementations** ([D12](../PLAN.md#3-key-decisions)). An impl module that needs another impl's behaviour calls the `:core:domain` or `:*:api` interface; Hilt supplies the implementation at the `:app` root.
- **Types crossing from `:feeds` to the UI** (for example the show-notes block model of [D27](../PLAN.md#3-key-decisions)): `:feeds` cannot see `:core:model`, and `:core:ui` cannot see `:feeds`, so `:core:data` maps `:feeds` output into a `:core:model` mirror (03: `ShowNotesDocument` → `ShowNotes`, [03 Show notes](03-feeds-and-discovery.md#show-notes)). Amending rule 8 to `:feeds → :core:model` is an open architect question ([Open questions](#open-questions)); until it is decided, the mirror is the rule.
- **`:core:artwork`** is the one infrastructure service implementations may share (PLAN rule 4).

```mermaid
flowchart TB
  app[":app (foss, play)"]
  feat[":feature:*"]
  ui[":core:ui"]
  ds[":core:designsystem"]
  nav[":core:navigation"]
  dom[":core:domain"]
  apis[":playback:api<br/>:download:api<br/>:youtube:api"]
  data[":core:data"]
  pimpl[":playback:impl"]
  dimpl[":download:impl"]
  yimpl[":youtube:impl"]
  ys[":youtube:streams<br/>GPL-3.0-or-later"]
  art[":core:artwork"]
  infra[":core:database<br/>:core:datastore<br/>:core:network"]
  feeds[":feeds"]
  base[":core:model<br/>:core:common"]
  app --> feat & data & pimpl & dimpl & yimpl & art & infra
  app -.->|fossImplementation| ys
  feat --> ui & ds & nav & dom & apis & base
  ui --> ds & base
  ds --> base
  dom --> apis & base
  apis --> base
  data --> dom & infra & art & feeds & apis
  pimpl --> dom & apis & infra & art
  dimpl --> dom & apis & infra & art
  yimpl --> apis & infra
  ys --> apis & infra
  art --> infra & apis
  infra --> base
```

(`yimpl`/`ys` → `infra` means `:core:network` only; `:core:testing` omitted.)

### Module-graph assertion configuration

Applied to `:app` by `neutrodyne.android.application` and, with the same rules, to `:core:testing` (the only main-code module not reachable from `:app`'s main configurations, so rule 9 is otherwise unchecked); rules live in `build-logic/convention/src/main/kotlin/ModuleRules.kt` so they are reviewed like code.

```kotlin
moduleGraphAssert {
    maxHeight = 5
    configurations += setOf("api", "implementation", "fossImplementation", "playImplementation")
    allowed = arrayOf(
        ":app -> .*",
        ":feature:[a-z]+ -> :core:(domain|model|common|designsystem|ui|navigation)",
        ":feature:[a-z]+ -> :(playback|download|youtube):api",
        ":core:domain -> :core:(model|common)", ":core:domain -> :(playback|download|youtube):api",
        ":core:data -> :core:(domain|model|common|database|datastore|network|artwork)", ":core:data -> :feeds", ":core:data -> :youtube:api",
        ":core:artwork -> :core:(model|common|database|network)", ":core:artwork -> :youtube:api",
        ":playback:impl -> :(playback|download|youtube):api", ":playback:impl -> :core:(domain|model|common|database|datastore|network|artwork)",
        ":download:impl -> :(download|youtube):api", ":download:impl -> :core:(domain|model|common|database|datastore|network|artwork)",
        ":youtube:(impl|streams) -> :youtube:api", ":youtube:(impl|streams) -> :core:(model|common|network)",
        ":core:designsystem -> :core:model", ":core:ui -> :core:(designsystem|model|common)",
        ":core:testing -> :core:(domain|model|common)", ":core:testing -> :(playback|download|youtube):api",
        ":(playback|download|youtube):api -> :core:(model|common)",
        ":core:(database|datastore|network) -> :core:(model|common)",
    )
    restricted = arrayOf(
        ":feature:.* -X> :feature:.*",
        ":(?!app).* -X> :youtube:streams",
        ":.* -X> :app",
        ":(playback|download|youtube):(impl|streams) -X> :core:data",
    )
}
```

Unverified: the exact 2.9.1 DSL property names (`configurations`, `maxHeight`) and whether the plugin distinguishes flavored configurations — confirmed in M0 step 23; the "`:youtube:streams` only in `foss`" half of rule 1 is in any case enforced by `verifyDependencyPolicy`, not by the graph plugin.

---

## Architecture patterns

Serves N1, N6, N11. Delivered in M0 (conventions, `:core:common`), refined as features land. Honours D12, D14, D35.

### Layers and data flow

```
Compose screen  ──events──▶  ViewModel  ──calls──▶  :core:domain interfaces / use cases
      ▲                          │                           │ (Hilt binds to)
      └── StateFlow<UiState> ◀───┘                 :core:data, *:impl  ──▶  Room / DataStore / OkHttp / Media3 / WorkManager
```

1. **Room is the single source of truth** ([D14](../PLAN.md#3-key-decisions)). Network results are written to Room; UI observes Room-backed `Flow`s. Nothing is cached in memory as truth; in-memory structures (`LocalMediaIndex`, `ResolvedUrlCache`, `DownloadProgressSource`) are mirrors or ephemeral state owned by their documents.
2. **Deferrable or must-survive-process-death work runs in WorkManager** (or UIDT jobs, [07](07-downloads.md#runners-and-scheduling)). Fire-and-forget work that may die with the process runs in `@ApplicationScope`. Never `GlobalScope`.
3. **Repositories** are interfaces in `:core:domain`, return `:core:model` types (never Room entities or OkHttp types), expose `Flow<T>` for observation and `suspend` functions for one-shot reads and writes, and are main-safe.
4. **Use-case rule:** a use case class exists only when logic spans ≥ 2 repositories/controllers or is reused by ≥ 2 callers. Named `VerbNounUseCase` with `operator fun invoke`. A use case that needs only domain interfaces is a concrete `@Inject` class in `:core:domain`; one that needs a multi-table transaction (e.g. `SubscribeUseCase`) is an interface in `:core:domain` implemented in `:core:data`.

### ViewModels and UI state

- One `@HiltViewModel` per Nav entry, scoped to the entry ([Navigation](#viewmodels-per-entry)). Arguments arrive through assisted injection of the whole key. The single exception is `PlayerViewModel` (`:feature:player`), which is activity-scoped because `PlayerSheet` lives outside `NavDisplay` ([D56](../PLAN.md#3-key-decisions), [08 Player sheet](08-ui-ux.md#player-sheet)); `NeutrodyneRoot` obtains it with `hiltViewModel()` outside any entry.
- Exposes `val uiState: StateFlow<XUiState>` built with `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`; events are plain functions `fun onX(...)`; no public `MutableStateFlow`.
- No `Context`, `Resources` or Android UI types in ViewModels (`SavedStateHandle` allowed). User-visible text is a `UiText` (string resource ID + args, or plural), resolved in composition.
- Paged lists are a **separate property** `val items: Flow<PagingData<T>>` (built with `flatMapLatest` over inputs, then `.cachedIn(viewModelScope)`), never inside `UiState`; UI collects with `collectAsLazyPagingItems()` and `itemKey { it.id }`.
- Screens collect with `collectAsStateWithLifecycle()` only (`collectAsState()` is banned in `feature/`).

```kotlin
// Screen-level state shape (each feature defines its own XUiState in this form)
sealed interface PodcastUiState {
    data object Loading : PodcastUiState
    data class Ready(
        val header: PodcastHeader,                          // :core:model types or feature-local immutable classes
        val groups: ImmutableList<GroupChip>,
        val messages: ImmutableList<UserMessage> = persistentListOf(),
        val pendingNavigation: NavKey? = null,              // one-shot navigation as state, acknowledged by the UI
    ) : PodcastUiState
    data class Failed(val error: UiText, val retryable: Boolean) : PodcastUiState
}
data class UserMessage(val id: Long, val text: UiText, val action: UiText? = null)
// ViewModel API: fun onMessageShown(id: Long); fun onNavigationHandled()

// :core:ui — resource-free text for ViewModels, resolved in composition
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText     // args: String/Int/Long or nested UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val value: String) : UiText                                             // user content only (titles, names), never app copy
}
@Composable fun UiText.asString(): String                                                   // nested UiText args resolved recursively
```

**One-shot events are state with acknowledgement** (snackbars as `messages`, navigation after an async result as `pendingNavigation`), never `Channel`/`SharedFlow`: state survives configuration change and cannot be lost while the collector is stopped. Navigation that follows a direct user gesture is performed by the composable itself via [`LocalAppNavigator`](#appnavigator-and-per-tab-back-stacks); ViewModels never hold the navigator.

#### Model and state rules

- `:core:model` types are deeply immutable: `val` only, collection fields never mutated after construction, no Android types. This is what makes the `compose-stability.conf` entry valid.
- `UiState` uses `kotlinx.collections.immutable` (`ImmutableList`, `persistentListOf`).

### Errors

```kotlin
// :core:common
sealed interface Outcome<out T, out E> {
    data class Success<out T>(val value: T) : Outcome<T, Nothing>
    data class Failure<out E>(val error: E) : Outcome<Nothing, E>
}
inline fun <T, E, R> Outcome<T, E>.map(f: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Success -> Outcome.Success(f(value)); is Outcome.Failure -> this
}
fun <T> Outcome<T, *>.getOrNull(): T? = (this as? Outcome.Success)?.value

/** runCatching that never swallows cancellation. */
suspend inline fun <T> suspendRunCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(e) }
```

1. Expected failures (network, parse, storage, invalid input) are values: each area defines a sealed error type (e.g. `AddPodcastError`, owned by 03) and returns `Outcome<T, ThatError>`.
2. Exceptions are for programming errors only (`check`, `require`, `error`); they crash, and ACRA reports them ([09 Crash reporting and diagnostics](09-quality-and-release.md#crash-reporting-and-diagnostics)).
3. `IOException`s are caught at the data-source boundary and converted with [`NetErrorClassifier`](#network-error-taxonomy); `catch (e: Exception)` without rethrowing `CancellationException` is a review blocker (`runCatching` in suspend code is banned in favour of `suspendRunCatching`).
4. `@ApplicationScope` carries a `CoroutineExceptionHandler` that logs at ERROR and, in debug builds only, rethrows on the main thread to crash fast.

### Coroutines and threading

```kotlin
// :core:common (javax.inject qualifiers; provided by Hilt modules in :app)
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class Dispatcher(val dispatcher: NeutrodyneDispatchers)
enum class NeutrodyneDispatchers { IO, Default }
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

interface Clock {
    fun now(): Long                // epoch milliseconds UTC (wall clock; may jump)
    fun elapsedRealtime(): Long    // monotonic milliseconds since boot (timers, durations)
}
```

| Work | Runs on | Rule |
|---|---|---|
| Compose, ViewModel state | Main (`viewModelScope` = `Dispatchers.Main.immediate`) | Never block; repositories are main-safe |
| Room DAO calls | Room's query context (`setQueryCoroutineContext(@Dispatcher(IO))`, [02](02-data-model.md#conventions)) | `suspend`/`Flow` DAOs only |
| HTTP | `okhttp-coroutines` `executeAsync()` (cancellable), body streaming inside `withContext(IO)` | One shared client family ([Networking](#networking-baseline)) |
| CPU-bound work (JSON decode, hashing, colour extraction, diffing in memory) | `@Dispatcher(Default)` | Parse-from-file stays on IO (blocking reads); owning docs set parallelism (`limitedParallelism`) |
| Media3 player and session | the player's application looper, which Neutrodyne makes the main looper by building player and session on the main thread; since 1.11 `MediaSession` getters throw off that looper | [06 UI boundary](06-playback.md#ui-boundary) |
| `ResolvingDataSource.Resolver`, `ContentProvider.openFile` | Media3 loader thread / binder thread | Blocking allowed; must use synchronous in-memory lookups; `runBlocking` permitted only here, with a timeout |
| Workers | `CoroutineWorker.doWork()` (`Dispatchers.Default`), switch to IO for blocking I/O | Resumable, idempotent, soft deadline per owning doc |
| `BroadcastReceiver` | `goAsync()` + launch in `@ApplicationScope`, `finish()` within 10 s | Never start playback from a receiver except the media-button path ([D43](../PLAN.md#3-key-decisions)) |

Time: production code never calls `System.currentTimeMillis()`/`SystemClock` directly; it injects `Clock` (`DeviceClock` in `:app`, `TestClock` in `:core:testing`). Wire formats use `Locale.ROOT`; dates in storage are epoch ms UTC.

### Logging and redaction

```kotlin
// :core:common
object Log {
    fun install(vararg sinks: LogSink)
    fun d(tag: String, msg: () -> String)
    fun i(tag: String, msg: () -> String)
    fun w(tag: String, t: Throwable? = null, msg: () -> String)
    fun e(tag: String, t: Throwable? = null, msg: () -> String)
}
fun interface LogSink { fun log(level: LogLevel, tag: String, message: String, t: Throwable?) }
object Redactor {
    fun url(raw: String): String          // redact one URL
    fun text(s: String): String           // find every http(s)/feed-like URL in free text and redact it
}
```

Every message passes through `Redactor.text` inside `Log` before reaching a sink, so a forgotten `url.redacted()` is still safe. **`Redactor.url` algorithm** (pure Kotlin, `java.net.URI` with a lenient fallback regex):

1. If unparsable, return `"<unparsable url, N chars>"`.
2. Replace user-info with `***@` (`https://user:pass@host/` → `https://***@host/`).
3. Keep scheme, host and port verbatim (needed for diagnostics).
4. For each path segment: keep it if it is ≤ 15 characters or contains no digit; otherwise replace with `…` + last 2 characters (`/rss/a8F3kq09ZpLm2xQ` → `/rss/…xQ`). Token-shaped segments (Supercast, Patreon) are thereby masked while `/feed/podcast` survives.
5. Keep query parameter **names**, replace every value with `…` (`?auth=abc&id=7` → `?auth=…&id=…`).
6. Drop the fragment.

Release builds install `LogcatSink(WARN)`; debug builds `LogcatSink(DEBUG)`. M11 adds a 500-entry in-memory `RingBufferLogSink` for the diagnostics screen ([09](09-quality-and-release.md#crash-reporting-and-diagnostics)). Never log response bodies, `Authorization`/`Cookie` values, credentials or API keys. No `HttpLoggingInterceptor`; debug builds get `DebugHttpLogInterceptor` (method, redacted URL, status, duration).

### Application start-up

```kotlin
// :core:common
interface AppInitializer {
    val order: Int                  // band, see below; equal orders allowed
    suspend fun run()               // idempotent; runs once per process in @ApplicationScope after Application.onCreate
}
```

`NeutrodyneApplication` runs every `@IntoSet AppInitializer` sequentially, sorted by `order` and then by fully qualified class name (deterministic ties), on `@Dispatcher(Default)`; each is wrapped in `suspendRunCatching` and a failure is logged without stopping the rest. Initializers receive database-backed dependencies lazily ([Dependency injection](#components-and-scopes) rule 7), because the whole set is constructed before the first one runs. Bands:

| Band | Meaning | Hard rule |
|---|---|---|
| 0–99 | platform (channels, caches) | must not touch the database, DAOs or repositories: the database opens at 100 and initializers run sequentially, so a blocking `requireDatabase()` here would wait for an open that never starts |
| 100–199 | data (open, restore, in-memory mirrors) | 100 is the database open; everything else ≥ 101 may use the database |
| 200–299 | WorkManager scheduling | enqueue only; no network |
| 300+ | warm-ups and housekeeping | nothing on the cold-start path waits for them |

Registrations known today (each owning document defines its initializer; this table is the index):

| Order | Initializer | Module | Owner | From |
|---|---|---|---|---|
| 10 | Static notification channels and the `grp_new_episodes` group: `playback`, `alerts` (06 `PlaybackChannels`), `downloads`, `download_errors` (07), `new_episodes`, `import_backup` (03, 05); posting code also calls the idempotent `ensureChannels()` | `:playback:impl`, `:download:impl`, `:core:data` | 06, 07, 03, 05 | M2–M6 |
| 100 | `DatabaseOpener.awaitOpen()` on IO: opens the database, runs migrations or recovery, fires Room `onCreate` on a fresh install ([02 Error handling and recovery](02-data-model.md#error-handling-and-recovery)) | `:core:database` | 02 | M1 |
| 110 | `FirstLaunchRestoreInitializer` (fresh DB + `files/backup/auto-snapshot.zip`) | `:core:data` | 05 | M3 |
| 120 | `CredentialStore.awaitLoaded()` (decrypt credentials into memory) | `:core:data` | 03 | M1 |
| 130 | `LocalMediaIndex` initial load | `:download:impl` | 07 | M6 |
| 140 | Per-group `new_episodes_{groupUuid}` channel sync (reads groups, so it cannot run at 10) | `:core:data` | 05 | M2 |
| 200 | Unique periodic work: `refresh-periodic` (03), `backup-auto-snapshot` (05), `download-cleanup` (07), `db-maintenance` (02), all `UPDATE` | owning modules | 03, 05, 07, 02 | M1, M3, M6, M11 |
| 210 | `download-reconcile` one-time `KEEP` | `:download:impl` | 07 | M6 |
| 300 | `PlaybackPrefs` warm-up and `PlayerConnection` registration (06), `ExportFilesCleaner` (05), one `artwork-sync` request per process (08) | owning modules | 06, 05, 08 | M3, M4 |

```mermaid
sequenceDiagram
  participant Z as System
  participant A as NeutrodyneApplication
  participant P as ArtworkProvider
  participant H as Hilt SingletonComponent
  participant S as ApplicationScope
  participant M as MainActivity
  Z->>A: attachBaseContext
  A->>A: install ACRA if ACRA_MAILTO is set
  Z->>P: ContentProvider.onCreate (no Hilt access here)
  Z->>A: onCreate
  A->>H: super.onCreate injects dagger.Lazy fields only
  alt ACRA sender process
    A-->>Z: return immediately
  else main process
    A->>A: Log.install, StrictMode in debug
    A->>S: launch initializers in order
    S->>S: 10 channels, 100 database open, 110 restore check, 200 periodic work, 210 reconcile
  end
  Z->>M: onCreate (launcher, notification or deep link)
  M->>M: installSplashScreen, keep until device_settings loaded and database open, at most 400 ms
  M->>M: enableEdgeToEdge, route intent, setContent
  M->>M: StartupGate shown until database open, then NavDisplay
```

```kotlin
@HiltAndroidApp
class NeutrodyneApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var initializers: dagger.Lazy<Set<@JvmSuppressWildcards AppInitializer>>
    @Inject @field:ApplicationScope lateinit var appScope: dagger.Lazy<CoroutineScope>

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (BuildConfig.ACRA_MAILTO.isNotEmpty()) installAcra(this)        // config owned by 09
    }
    override fun onCreate() {
        super.onCreate()
        if (ACRA.isACRASenderServiceProcess()) return                      // :acra process: no Hilt graph use, no WorkManager, no session
        Log.install(LogcatSink(if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN))
        if (BuildConfig.DEBUG) enableStrictMode()
        appScope.get().launch { runInitializers(initializers.get()) }
    }
    // Entry points instead of injected fields: both may be called before onCreate (provider, early WorkManager use).
    // In production Hilt creates the component lazily on first access; in Hilt tests use @EarlyEntryPoint for any entry
    // point resolved before the test component exists (https://dagger.dev/hilt/early-entry-point).
    override val workManagerConfiguration: Configuration get() = Configuration.Builder()
        .setWorkerFactory(EntryPointAccessors.fromApplication(this, WorkEntryPoint::class.java).workerFactory())
        .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
        .build()
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        EntryPointAccessors.fromApplication(this, ImageEntryPoint::class.java).imageLoaderFactory().create(context)
}
```

**Splash and start-up gate.** `StartupViewModel` (`:app`) exposes `StartupState(deviceSettingsLoaded, database: Pending | Ready | Recovered(cause) | Failed)`, fed by the first `DeviceSettingsStore` emission (the persisted tab and group selection needed for the first frame) and by `DatabaseOpener.awaitOpen()` ([02 Error handling and recovery](02-data-model.md#error-handling-and-recovery)).

1. `installSplashScreen().setKeepOnScreenCondition { elapsed < 1_000 ms && (!state.deviceSettingsLoaded || (state.database == Pending && elapsed < 400 ms)) }`: the system splash covers the normal case (opening an up-to-date database takes milliseconds), stays at most 400 ms for the database (cold-start budget N5, [09 Performance budgets](09-quality-and-release.md#performance-budgets)) and at most 1 s in total; if `device_settings` has not emitted by then, the first frame uses the keys' defaults (DataStore corruption is already handled by the corruption handler, so this only guards a stalled disk).
2. The activity's content renders `StartupGate` (visuals: [08 Banners and the startup gate](08-ui-ux.md#banners-and-the-startup-gate)) **instead of the whole `NeutrodyneRoot`** (scaffold, `NavDisplay` and `PlayerSheet`) while `database == Pending`, so **no ViewModel — feature or the activity-scoped `PlayerViewModel` — and therefore no repository is constructed before the database is open** (02's `requireDatabase()` throws on the main thread before that). A long migration shows "Updating your library…" there. `Recovered(cause)` opens the gate and shows 02's recovery message once; `Failed` keeps the gate with 08's error state and its "Try again" (activity restart).
3. A [route](#intent-routing) that arrives while the gate is shown is applied to `NavigationState` immediately (it is plain saveable state); its entries compose, and their ViewModels are created, only after the gate opens.
4. Nothing in start-up waits for the network or a snapshot restore; restore progress is shown by 05's flow after the gate opens.
5. Before M1 (no database) `database` starts as `Ready`.

**Framework components constructed on the main thread before the gate.** The start-up gate protects UI only. Services, receivers and job services (`NeutrodynePlaybackService`, `ManualDownloadJobService`, `DownloadActionReceiver`) can be created by the system right after `Application.onCreate` — for example the resumption card after a reboot — while the database is still opening, and Hilt injects them on the main thread, where 02's `requireDatabase()` throws. Rule: such classes, and every class they inject eagerly (for example the `MediaLibrarySession` callback), receive anything that reaches `NeutrodyneDatabase` (DAOs, repositories, `EpisodeResolver`, controllers) as `dagger.Lazy<…>` or `Provider<…>` and dereference it only inside a coroutine on IO after `DatabaseOpener.awaitOpen()`; constructors of repositories and DAOs never touch the database. Session and player creation in `Service.onCreate` therefore needs no database. 06 and 07 apply this rule to their components; the [Testing](#testing) start-up test enforces it.

**Process model:** single main process plus ACRA's `:acra`. No component is `directBootAware`; media-button events before first unlock are ignored by the platform, which is acceptable.

### DataStore files and typed setting keys

Owns the conventions of [D35](../PLAN.md#3-key-decisions); each document owns its keys in its Settings table.

| File | Path | Backed up | Content |
|---|---|---|---|
| `settings` | `filesDir/datastore/settings.preferences_pb` | yes (Auto Backup include rule and backup ZIP `settings.json`) | portable preferences |
| `device_settings` | `filesDir/datastore/device_settings.preferences_pb` | never | SAF grants, volume UUIDs, prompt flags, selected tab/group, onboarding flags |

```kotlin
// :core:model (package app.neutrodyne.core.model.settings)
enum class SettingsFile { PORTABLE, DEVICE }            // PORTABLE -> "settings", DEVICE -> "device_settings"
sealed class SettingKey<T : Any>(val name: String, val default: T, val file: SettingsFile) {
    class Bool(name: String, default: Boolean, file: SettingsFile = SettingsFile.PORTABLE) : SettingKey<Boolean>(name, default, file)
    class Int32(name: String, default: Int, file: SettingsFile = SettingsFile.PORTABLE) : SettingKey<Int>(name, default, file)
    class Int64(name: String, default: Long, file: SettingsFile = SettingsFile.PORTABLE) : SettingKey<Long>(name, default, file)
    class Float32(name: String, default: Float, file: SettingsFile = SettingsFile.PORTABLE) : SettingKey<Float>(name, default, file)
    class Text(name: String, default: String, file: SettingsFile = SettingsFile.PORTABLE) : SettingKey<String>(name, default, file)
    class TextSet(name: String, default: Set<String>, file: SettingsFile = SettingsFile.PORTABLE) : SettingKey<Set<String>>(name, default, file)
    class Choice<E : Enum<E>>(name: String, default: E, val values: List<E>, file: SettingsFile = SettingsFile.PORTABLE) :
        SettingKey<E>(name, default, file)                // stored as E.name; unknown stored name -> default
}
// Each area declares its keys as an object in this same package (:core:model, so features, :core:data and impl modules
// all see them), e.g. `object PlaybackSettingKeys { val SKIP_BACK_MS = SettingKey.Int64("playback.skip_back_ms", 10_000); val ALL = listOf(...) }`,
// and AllSettingKeys.list concatenates every area's ALL (one registry; used by 05's backup whitelist and the uniqueness test).

// :core:domain
interface SettingsRepository {
    fun <T : Any> observe(key: SettingKey<T>): Flow<T>
    suspend fun <T : Any> get(key: SettingKey<T>): T
    suspend fun <T : Any> set(key: SettingKey<T>, value: T): Outcome<Unit, SettingsError>
    suspend fun reset(key: SettingKey<*>)
    fun observePortableSnapshot(): Flow<Map<String, Any>>   // backup export (05)
}
sealed interface SettingsError {
    data object WriteFailed : SettingsError                          // IOException from DataStore (disk full, I/O error); logged at WARN
    data class OutOfRange(val key: String) : SettingsError           // value rejected by the key's own validator (e.g. Choice not in values)
}
```

Rules:

1. Key names match `^(appearance|feeds|discover|groups|playback|downloads|youtube|backup|privacy|diagnostics|ui)\.[a-z0-9_]+$`; `ui.*` keys must be `DEVICE`. A unit test iterates `AllSettingKeys.list` and asserts the pattern, uniqueness and the `ui.*` rule.
2. Keys are never renamed or re-typed; a replacement key gets a new name and a `DataMigration` in the store copies the value once.
3. Exactly one `DataStore` per file: `@Singleton` providers in `:core:datastore` qualified `@SettingsDataStore(SettingsFile.PORTABLE|DEVICE)`, created with `PreferenceDataStoreFactory.create(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }, scope = appScope + IO, produceFile = { context.preferencesDataStoreFile(name) })`. Corruption resets that file to defaults and logs WARN.
4. `SettingsStore` (portable) and `DeviceSettingsStore` (device) wrap the two files; `SettingsRepository`'s implementation in `:core:data` routes by `key.file`. Impl modules may inject the stores directly.
5. Secrets never go to DataStore; they go through `CredentialStore` into the `credential` table ([03](03-feeds-and-discovery.md#feed-moves-auth-and-paging)). Fresh-install detection uses Room's `onCreate` callback, never a DataStore flag ([05 Auto Backup](05-groups-opml-backup.md#auto-backup)).

---

## Dependency injection

Serves N11. Delivered in M0 (graph skeleton), extended per milestone. Honours [D8](../PLAN.md#3-key-decisions).

### Components and scopes

| Binding | Declared in | Component | Scope |
|---|---|---|---|
| `@Dispatcher(IO)`, `@Dispatcher(Default)`, `@ApplicationScope CoroutineScope` (`SupervisorJob() + Default + handler`), `Clock` → `DeviceClock`, `BuildInfo` (from `BuildConfig`, `R.string.licence_statement` and the injected `Distribution`) | `:app` `CoreModule` (JVM modules declare no Hilt modules) | Singleton | `@Singleton` |
| Base `OkHttpClient`, `@HttpClient(kind)` derived clients, `UserAgentInterceptor`, `AuthInterceptor`, `NetErrorClassifier`, `NetworkMonitor` → `ConnectivityNetworkMonitor` | `:core:network` `NetworkModule` | Singleton | `@Singleton` |
| `CredentialLookup` | M0: `:core:data` stub binds `CredentialLookup.None`; M1: `CredentialStore` (03) | Singleton | `@Singleton` |
| `DnsFamilyHints` | `:core:network` (`@Inject` class) | Singleton | `@Singleton` |
| `SQLiteDriver` | `:core:database` `SqliteDriverModule` (`BundledSQLiteDriver()`) | Singleton | `@Singleton` |
| `DatabaseOpener`, `NeutrodyneDatabase` (provided as `opener.requireDatabase()`), DAOs | `:core:database` `DatabaseModule` ([02](02-data-model.md#error-handling-and-recovery)) | Singleton | `@Singleton`; DAO providers unscoped (Room caches them) |
| `@SettingsDataStore(...) DataStore<Preferences>`, `SettingsStore`, `DeviceSettingsStore` | `:core:datastore` | Singleton | `@Singleton` |
| `:core:domain` repository and use-case interfaces | `@Binds` modules in the implementing module (`:core:data` for most; `:playback:impl` for `QueueRepository`, `ChapterRepository`, 06) | Singleton | `@Singleton` |
| `PlaybackController`, `PlaybackStateSource`, `PlayerConnection` | `:playback:impl` | Singleton | `@Singleton` (connection lifecycle: [06 UI boundary](06-playback.md#ui-boundary)) |
| `DownloadController`, `LocalMediaIndex`, `DownloadProgressSource` | `:download:impl` | Singleton | `@Singleton` |
| `ArtworkStore`, `NeutrodyneImageLoaderFactory` | `:core:artwork` | Singleton | `@Singleton` |
| `YouTubeUrlClassifier` (pure `@Inject` class), `YouTubeChannelResolver` | `:youtube:api` / `:youtube:impl` | Singleton | `@Singleton` |
| `Distribution`, `YouTubeCapabilities`, `YouTubeStreamResolver`, `YouTubeEnricher`, `YouTubeChannelSearch`, `ExtractorChannelLookup` | `:app/src/{foss,play}/…/FlavorModule.kt` only ([Flavor modules](#flavor-modules)) | Singleton | `@Singleton` |
| `Set<AppInitializer>` | each owning module, `@IntoSet` | Singleton | unscoped elements |
| `Set<EntryProviderInstaller>` | each feature, `@IntoSet` | `ActivityRetainedComponent` | unscoped |
| ViewModels | features, `@HiltViewModel` | `ViewModelComponent` | per Nav entry ([decorator](#viewmodels-per-entry)) |
| `MainActivity`, `ExternalImportActivity` | `:app`, `@AndroidEntryPoint` | `ActivityComponent` | — |
| `NeutrodynePlaybackService`, `ManualDownloadJobService` | `:playback:impl`, `:download:impl`, `@AndroidEntryPoint` | `ServiceComponent` | — |
| `DownloadActionReceiver` | `:download:impl`, `@AndroidEntryPoint` | — | — |
| Workers | `@HiltWorker` + `HiltWorkerFactory` via `WorkEntryPoint` | — | — |
| `ArtworkProvider` | `:core:artwork`, `@EntryPoint ArtworkProviderEntryPoint`, resolved lazily in `openFile` | — | — |

Rules:

1. **Injection sites:** constructor injection everywhere except framework-instantiated classes (`@AndroidEntryPoint`) and `ContentProvider`s / `Application` getters that may run before `Application.onCreate` (use `EntryPointAccessors.fromApplication`; never touch Hilt in `ContentProvider.onCreate`).
2. **`NeutrodyneApplication` injects only `dagger.Lazy`/`Provider` fields** so the `:acra` process constructs nothing.
3. **JVM modules** use `javax.inject` annotations (`@Inject`, `@Qualifier`, `@Singleton`) and the plain Dagger processor for factories; their interfaces are bound by Hilt modules in Android modules.
4. **Cross-module objects of external types** are bound by the impl module under a qualifier annotation declared in its JVM `:*:api` module. v1.0 has none: 06 keeps every Media3 type out of features ([06 UI boundary](06-playback.md#ui-boundary)); M14 uses this pattern to hand the session `Player` to `:feature:player` for `PlayerSurface` ([06 Video](06-playback.md#video)).
5. **Empty multibindings are declared:** `CoreModule` declares `@Multibinds abstract fun initializers(): Set<AppInitializer>`, so the graph compiles before any module contributes (Hilt fails on an undeclared empty set).
6. **No Hilt module binds the same interface in both `main` and a flavor source set.** Interfaces whose implementation differs per flavor are bound only in the two `FlavorModule`s, and `:youtube:streams` may ship Hilt modules for its internal wiring (`OkHttpNpeDownloader`) but never binds `:youtube:api` interfaces itself.
7. **Database-backed dependencies of framework components are lazy** (`dagger.Lazy`/`Provider`, dereferenced on IO after `DatabaseOpener.awaitOpen()`), per [Application start-up](#application-start-up). The same applies to **every** `AppInitializer`: `initializers.get()` constructs the whole set before initializer 100 opens the database, so an eager DAO or repository in any initializer's constructor would block on `requireDatabase()` before the open has started.
8. **Multibound function types need `@JvmSuppressWildcards`** at the injection site: `Set<@JvmSuppressWildcards EntryProviderInstaller>`; otherwise Kotlin's `Function1<? super …>` wildcard makes Dagger report a missing binding.

### Flavor modules

```kotlin
// :app/src/foss/kotlin/app/neutrodyne/flavor/FlavorModule.kt   (M9 state; before M9 it is identical to the play module below
// except for Distribution.FOSS)
@Module @InstallIn(SingletonComponent::class)
internal abstract class FlavorModule {
    @Binds abstract fun streamResolver(impl: NpeYouTubeStreamResolver): YouTubeStreamResolver      // :youtube:streams
    @Binds abstract fun enricher(impl: NpeEnricher): YouTubeEnricher
    @Binds abstract fun channelSearch(impl: NpeChannelSearch): YouTubeChannelSearch
    @Binds abstract fun extractorLookup(impl: InnertubeChannelResolver): ExtractorChannelLookup
    companion object {
        @Provides fun distribution(): Distribution = Distribution.FOSS
        @Provides @Singleton fun youTubeCapabilities(): YouTubeCapabilities = YouTubeCapabilities(
            inAppPlayback = true, downloads = true, channelSearch = true, enrichment = true, backCatalogue = true)
    }
}

// :app/src/play/kotlin/app/neutrodyne/flavor/FlavorModule.kt
@Module @InstallIn(SingletonComponent::class)
internal abstract class FlavorModule {
    @Binds abstract fun streamResolver(impl: ExternalOnlyYouTubeStreamResolver): YouTubeStreamResolver   // :youtube:impl
    @Binds abstract fun enricher(impl: NoOpYouTubeEnricher): YouTubeEnricher
    @Binds abstract fun channelSearch(impl: UnsupportedYouTubeChannelSearch): YouTubeChannelSearch
    @Binds abstract fun extractorLookup(impl: NoExtractorChannelLookup): ExtractorChannelLookup
    companion object {
        @Provides fun distribution(): Distribution = Distribution.PLAY
        @Provides @Singleton fun youTubeCapabilities(): YouTubeCapabilities = YouTubeCapabilities(
            inAppPlayback = false, downloads = false, channelSearch = false, enrichment = false, backCatalogue = false)
    }
}
```

`YouTubeCapabilities`' shape, the implementation classes and every consumer are owned by [04 Flavor matrix](04-youtube.md#flavor-matrix). A binding must exist from the milestone of its **first consumer**, or Hilt fails to compile; until M9 both flavors bind the `play` column:

| From | Added to both `FlavorModule`s | First consumer |
|---|---|---|
| M0 | `Distribution` | `BuildInfo`, About |
| M2 | `YouTubeCapabilities` (all `false`) | 05 `EffectiveSettingsResolver` (auto-download capability) |
| M4 | `YouTubeStreamResolver` → `ExternalOnlyYouTubeStreamResolver` (this one class lands in `:youtube:impl` ahead of the rest of the module) | 06 `EpisodeResolver` YouTube branch |
| M8 | `YouTubeEnricher` → `NoOpYouTubeEnricher`, `YouTubeChannelSearch` → `UnsupportedYouTubeChannelSearch`, `ExtractorChannelLookup` → `NoExtractorChannelLookup` | 03 YouTube source adapter, 04 channel resolver |
| M9 | `foss` only: the four `Npe*`/`Innertube*` bindings and all-`true` capabilities above | — |

The `:youtube:api` interfaces and `YouTubeCapabilities` therefore also land early (M2/M4), as compiling contracts. [04's emergency patch](04-youtube.md#licensing-and-legal) reverts the `foss` module to the `play` column while keeping `Distribution.FOSS`.

### Test overrides

- `:core:testing` provides hand-written fakes for every `:core:domain` and `:*:api` interface ([09 Test infrastructure](09-quality-and-release.md#test-infrastructure)). It cannot reference production Hilt modules (rule 9 forbids its edges to impl modules), so the `@TestInstallIn(replaces = [...])` modules that swap production bindings for those fakes live in `:app/src/test` and `:app/src/androidTest`.
- Robolectric Hilt tests in `:app/src/test` install `TestSqliteDriverModule` (`@TestInstallIn(replaces = [SqliteDriverModule::class])`, provides `AndroidSQLiteDriver()`), because the bundled driver's `.so` files target device ABIs, not the host JVM ([S4](#s4-robolectric-with-androidsqlitedriver)). Instrumented tests keep `BundledSQLiteDriver`.
- DAO and migration tests in `:core:database` build the database directly with the driver under test; they do not use Hilt.

---

## Navigation

Serves R2.4, R5.7, N7. Delivered in M0 (mechanics), screens per milestone. Honours [D7](../PLAN.md#3-key-decisions), [D54](../PLAN.md#3-key-decisions), [D56](../PLAN.md#3-key-decisions). This section is mechanics only; which key opens where, re-tap behaviour, pane roles and predictive-back ordering with `PlayerSheet` are owned by [08 Navigation](08-ui-ux.md#navigation).

### Contracts in `:core:navigation`

```kotlin
// :core:navigation — every key is @Serializable, implements NavKey, carries IDs and strings only
@Serializable sealed interface TopLevelKey : NavKey
@Serializable data object FeedsKey : TopLevelKey
@Serializable data object LibraryKey : TopLevelKey
@Serializable data object UpNextKey : TopLevelKey
@Serializable data object DownloadsKey : TopLevelKey
@Serializable data object DiscoverKey : TopLevelKey
@Serializable data class PodcastKey(val podcastId: Long) : NavKey
// ... all keys of the canonical key table, created in M0 so cross-feature navigation compiles from day one

typealias EntryProviderInstaller = EntryProviderScope<NavKey>.() -> Unit

interface AppNavigator {
    fun push(key: NavKey)                          // onto the selected tab's stack (sheets and dialogs too)
    fun selectTab(key: TopLevelKey)
    fun pop(): Boolean                             // false when nothing was popped
    fun resetTab(key: TopLevelKey)                 // stack back to its root
    fun open(tab: TopLevelKey, stack: List<NavKey>) // deep links: select tab, replace its stack above the root
    fun pushDetail(key: NavKey)                    // 08: replaces a same-class top entry on ≥ 2 panes, else push
}
val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> { error("AppNavigator not provided") }
// 08 adds SettingsHomeKey, PaneLayout, LocalPaneLayout and LocalNavTab here (08 Navigation); :app provides them.

object NdSceneMetadata {                           // overlay metadata understood by :app's scene strategies
    fun bottomSheet(): Map<String, Any> = mapOf(KEY_OVERLAY to "sheet")
    fun dialog(): Map<String, Any> = mapOf(KEY_OVERLAY to "dialog")
    const val KEY_OVERLAY = "nd.overlay"
}
// Nav3 1.1 added a typed metadata DSL (NavMetadataKey, e.g. DialogKey for its DialogSceneStrategy). If S5 shows that
// 1.2's strategies read only typed keys, NdSceneMetadata returns that type instead; call sites stay unchanged.
```

`:core:navigation` applies no Compose compiler plugin; it declares `api` dependencies on `navigation3-runtime` (`NavKey`, `EntryProviderScope`) and on `androidx.compose.runtime:runtime` (for `staticCompositionLocalOf`), versioned by the Compose BOM.

### Feature entry installers

```kotlin
// :feature:podcast
@Module @InstallIn(ActivityRetainedComponent::class)
internal object PodcastNavigationModule {
    @Provides @IntoSet fun entries(): EntryProviderInstaller = {
        entry<PodcastKey>(metadata = ListDetailSceneStrategy.detailPane()) { key -> PodcastRoute(key) }
        entry<PodcastSettingsKey> { key -> PodcastSettingsRoute(key) }
    }
}

@HiltViewModel(assistedFactory = PodcastViewModel.Factory::class)
internal class PodcastViewModel @AssistedInject constructor(
    @Assisted val key: PodcastKey,
    private val podcasts: PodcastRepository,
) : ViewModel() {
    @AssistedFactory interface Factory { fun create(key: PodcastKey): PodcastViewModel }
}

@Composable internal fun PodcastRoute(
    key: PodcastKey,
    vm: PodcastViewModel = hiltViewModel<PodcastViewModel, PodcastViewModel.Factory>(creationCallback = { it.create(key) }),
) { /* collectAsStateWithLifecycle, LocalAppNavigator.current for gesture navigation */ }
```

Metadata conventions: list/detail/extra panes use `ListDetailSceneStrategy.listPane()/detailPane()/extraPane()` from `adaptive-navigation3` (the pane role per key is in [08 Information architecture](08-ui-ux.md#information-architecture)); sheet keys (`AddPodcastKey`, `AddToGroupsKey`, `AllGroupsKey`, `SleepTimerKey`, `SpeedKey`) use `NdSceneMetadata.bottomSheet()`; dialog keys (`ExportKey`) use `NdSceneMetadata.dialog()`. Every key has exactly one installer; the [Hilt graph test](#testing) fails on a key with zero or two entries.

### AppNavigator and per-tab back stacks

`NavigationState` in `:app` implements `AppNavigator` (including 08's `pushDetail`, which reads `LocalPaneLayout`'s partition count through a state the root updates): one `NavBackStack<NavKey>` per `TopLevelKey`, each created with `rememberNavBackStack(root)` inside `rememberNavigationState()` (saveable across process death), plus the selected tab in `rememberSaveable`. It is provided to the tree with `CompositionLocalProvider(LocalAppNavigator provides state)`.

```kotlin
// :app — sketch; exact Nav3 1.2 names are Spike S5 outputs
@Composable fun NeutrodyneNavHost(state: NavigationState, installers: Set<@JvmSuppressWildcards EntryProviderInstaller>) {
    val provider = entryProvider { installers.forEach { install -> install() } }
    // Decorate EVERY tab's stack on every composition, in the fixed TopLevelKey order, each with its own decorator
    // instances: a stack that is not decorated in a composition loses its saveable state and ViewModelStores, and a
    // remember call inside a list of varying length breaks positional memoization (nav3-recipes "multiple back stacks").
    val decoratedByTab: Map<TopLevelKey, List<NavEntry<NavKey>>> = state.tabs.associateWith { tab ->
        key(tab) {
            rememberDecoratedNavEntries(
                backStack = state.stack(tab),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                    rememberTabLocalNavEntryDecorator(tab),        // provides 08's LocalNavTab = tab to every entry
                ),
                entryProvider = provider,
            )
        }
    }
    val entries = state.visibleTabs().flatMap { decoratedByTab.getValue(it) }
    SharedTransitionLayout {
        NavDisplay(
            entries = entries,
            onBack = { state.pop() },
            sceneStrategies = listOf(rememberNdBottomSheetSceneStrategy(), rememberNdDialogSceneStrategy(), rememberListDetailSceneStrategy()),
            sharedTransitionScope = this,
        )
    }
}
```

- `visibleTabs()` = the start tab (`FeedsKey`), then the selected tab if different. Popping a non-start tab's root therefore returns to Feeds, and back from Feeds' root leaves the app (default confirmed by [08 Navigation](08-ui-ux.md#navigation)).
- Scene strategy order: overlay strategies first (they produce overlay scenes over the underlying scene), then `ListDetailSceneStrategy` (with 08's pane directive `ndPaneLayout`), then Nav3's single-pane default.
- `NdBottomSheetSceneStrategy` and `NdDialogSceneStrategy` live in `:app/navigation/`. Nav3 ships a `DialogSceneStrategy` since 1.1 ([Navigation 3 releases](https://developer.android.com/jetpack/androidx/releases/navigation3)); no bottom-sheet strategy ships, so ours follows the nav3-recipes bottom-sheet recipe (a copied file keeps its Apache-2.0 header, [Licensing](#copied-code-and-contributions)). Both **must render through the window-based `NdModalBottomSheet`/`NdDialog`** of `:core:designsystem`, never an in-layout sheet, so a sheet opened from the expanded `PlayerSheet` (speed, sleep timer) draws above it ([08 Sheets and dialogs](08-ui-ux.md#sheets-and-dialogs)); `NdDialogSceneStrategy` therefore wraps Nav3's strategy only if it lets us supply that composable, otherwise it is ours.
- Nav3 1.2's deep-link API (`DeepLinkRequest`, `UriDeepLinkMatcher`) is not used for routing: Neutrodyne's routes choose a tab and a whole stack and carry security rules ([Intent routing](#intent-routing)); the router may use `UriDeepLinkMatcher` internally to parse paths.
- `PlayerSheet` is not a key ([D56](../PLAN.md#3-key-decisions)); it sits beside `NavDisplay` in the root scaffold and owns its back handling ([08 Player sheet](08-ui-ux.md#player-sheet)).
- Predictive back: `NavDisplay` handles entries; custom surfaces use `NavigationBackHandler` (Nav3 1.2) or `PredictiveBackHandler`; `onBackPressed` overrides are banned ([`checkBannedApis`](#gradle-side-policy-tasks)).

#### ViewModels per entry

`rememberViewModelStoreNavEntryDecorator()` (lifecycle 2.11) scopes each entry's ViewModels to that entry; without it `hiltViewModel()` scopes to the Activity and two `PodcastKey`s would share one ViewModel. The decorator clears an entry's store when the entry leaves its back stack for good.

### Intent routing

`MainActivity` (`launchMode="singleTop"`) hands every incoming intent to `IntentRouter` (`:app`): in `onCreate` only when `savedInstanceState == null`, and in every `onNewIntent`. The router returns a `Route`; the root composable applies it to `NavigationState`.

```kotlin
sealed interface Route {
    data object None : Route
    data class Navigate(val tab: TopLevelKey, val stack: List<NavKey>) : Route   // AppNavigator.open
    data class Push(val key: NavKey) : Route                                   // onto the current tab (sheets)
    data class SelectFeed(val groupUuid: String?) : Route                      // Feeds tab + persisted pager selection (08)
    data object ExpandPlayer : Route
}
```

| Incoming | Exported? | Route |
|---|---|---|
| `MAIN`/`LAUNCHER` | yes | `None` |
| `VIEW` `feed:`, `pcast:`, `podcast:`, `itpc:`, `https://podcasts.apple.com/…`, `neutrodyne://subscribe?url=…` (filters: [03 Deep links and share targets](03-feeds-and-discovery.md#deep-links-and-share-targets)) | yes | `Push(AddPodcastKey(input = dataString))` |
| `SEND` `text/plain` | yes | `Push(AddPodcastKey(input = EXTRA_TEXT))` |
| `neutrodyne://open/episode/{id}` | no (explicit intents) | `Push(EpisodeKey(id))` |
| `…/open/podcast/{id}` | no | `Navigate(LibraryKey, [PodcastKey(id)])` |
| `…/open/group/{groupUuid}` | no | `SelectFeed(groupUuid)` |
| `…/open/downloads` | no | `Navigate(DownloadsKey, [])` |
| `…/open/player` | no | `ExpandPlayer` |
| `…/open/import/{sessionId}` | no | `Navigate(LibraryKey, [ImportKey(sessionId)])` |
| `…/open/settings/{page}` | no | `Push(SettingsKey(SettingsPage.valueOf(page.uppercase(Locale.ROOT))))` |
| `…/open/diagnostics` | no | `Push(DiagnosticsKey)` |
| anything else, malformed IDs, unknown pages | — | `None` (logged at WARN, redacted) |

Target tabs above are defaults; [08 Navigation](08-ui-ux.md#navigation) owns them. Security rules: `MainActivity` is exported, so any app can send it any of these intents; therefore **routes only navigate** — they never subscribe, play, delete or write without a confirming user action on the destination screen. The router reads at most 4 KB of text, lowercases the scheme before matching (intent-filter scheme matching is case-sensitive), ignores unknown extras, and never trusts `EXTRA_REFERRER`. Notification `PendingIntent`s target `MainActivity` explicitly with `FLAG_IMMUTABLE`. OPML/backup files arrive at `ExternalImportActivity`, which copies the payload and then routes to `ImportKey(sessionId)` through an explicit intent ([05 Receiving files](05-groups-opml-backup.md#receiving-files)).

---

## Build flavors

Serves R3.5–R3.7, N8. Delivered in M0 (both flavors built and tested by CI from the first commit, PLAN PO-2). Honours [D2](../PLAN.md#3-key-decisions), [D3](../PLAN.md#3-key-decisions), [D61](../PLAN.md#3-key-decisions).

| Item | `foss` | `play` |
|---|---|---|
| Dimension | `distribution` | `distribution` |
| Channels | GitHub Releases (+ Obtainium), IzzyOnDroid, F-Droid | Google Play (publication gated by [PO-2](../PLAN.md#po-2-distribution-channels-and-youtube-per-flavor)) |
| Binary licence | GPL-3.0-or-later from M9 (contains `:youtube:streams`); Unlicense + permissive before M9 | No GPL-3.0 code: Unlicense + permissive dependencies, plus possibly the GPL-2.0-with-Classpath-Exception desugaring runtime ([below](#core-library-desugaring)) |
| `:youtube:streams` | `fossImplementation` | absent (`verifyDependencyPolicy`) |
| YouTube | layers A + B ([04 Flavor matrix](04-youtube.md#flavor-matrix)) | layer A only |
| Proprietary SDKs | none | none in v1.0 (Cast in v1.x, PO-6) |
| Self-update, links to the other flavor | none | none; the About screen links only to the repository root, never to releases or APKs |
| `applicationId` | `app.neutrodyne` | `app.neutrodyne` (same; [PO-8](../PLAN.md#48-further-product-owner-decisions), [D61](../PLAN.md#3-key-decisions)) |

```kotlin
// :app/build.gradle.kts (what neutrodyne.android.application sets, plus app-specific lines)
android {
    namespace = "app.neutrodyne"
    defaultConfig {
        applicationId = "app.neutrodyne"
        targetSdk = 37
        versionCode = providers.gradleProperty("neutrodyne.versionCode").get().toInt()
        versionName = providers.gradleProperty("neutrodyne.versionName").get()
        fun prop(name: String) = providers.gradleProperty(name).orElse("").get()
        buildConfigField("String", "REPO_URL", "\"${prop("neutrodyne.repoUrl")}\"")
        buildConfigField("String", "ACRA_MAILTO", "\"${prop("neutrodyne.acraMailto")}\"")
        buildConfigField("String", "PODCASTINDEX_KEY", "\"${prop("neutrodyne.podcastIndexKey")}\"")
        buildConfigField("String", "PODCASTINDEX_SECRET", "\"${prop("neutrodyne.podcastIndexSecret")}\"")
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            optimization { enable = true }   // AGP 9.3+ DSL: R8 code + resource optimization; includes the platform default
                                             // keep rules equivalent to proguard-android-optimize.txt. No proguardFiles(...) calls:
                                             // keep rules come from the keepRules source sets (below)
            // signingConfig only when NEUTRODYNE_KEYSTORE* env vars exist; reproducibility hygiene (crunchPngs, vcsInfo, ART profile): 09
        }
    }
}
dependencies { "fossImplementation"(project(":youtube:streams")) }
```

- **`BuildConfig` never contains timestamps or git data** (reproducible builds, [09 Reproducible builds](09-quality-and-release.md#reproducible-builds)). Secrets are empty strings unless supplied by `-P`; PO-3 default B means `PODCASTINDEX_*` stay empty in every build until written permission arrives.
- **`BuildInfo`** (`:core:model`) is how non-`:app` modules read build facts without seeing `BuildConfig`: `versionName`, `versionCode`, `distribution`, `isDebug`, `repoUrl`, `licenceStatementResId` (an `@StringRes Int` from `:app`'s flavor resources, resolved by `:feature:settings` at runtime), `podcastIndexKey`, `podcastIndexSecret`. Its `toString()` omits the two secrets.
- **Flavor source sets** contain only: `FlavorModule.kt`, `res/values/strings_flavor.xml` (string `licence_statement`, same name in both flavors so `CoreModule` in `main` can reference `R.string.licence_statement`), `keepRules/foss.keep` (`foss` only), and (09) fastlane overrides. Feature code never branches on flavor; it reads `YouTubeCapabilities` or `BuildInfo.distribution`.
- **Keep rules** (AGP 9.3+ `keepRules` source set, files ending in `.keep`, [shrink-code](https://developer.android.com/build/shrink-code)): `app/src/main/keepRules/app.keep` holds `-dontobfuscate` (allowed only in the app; AGP 9 forbids global options in library consumer rules) and app-wide keeps; `app/src/foss/keepRules/foss.keep` holds the F-Droid reproducibility keeps for kotlinx.coroutines ([09 Reproducible builds](09-quality-and-release.md#reproducible-builds)). Unverified: that a flavor-named `keepRules` source set is honoured like `src/main/keepRules` — checked once in M0 ([verification log](#verification-log)): `app.keep` temporarily adds `-printconfiguration build/outputs/r8-config.txt`, and the `fossRelease` output must contain the `foss.keep` rules while `playRelease`'s must not; fallback: `productFlavors.foss { proguardFile("keepRules/foss.keep") }` (legacy DSL, still supported). Library modules ship consumer rules as `consumer-rules.pro` via `consumerProguardFiles` (applied by `neutrodyne.android.library` when the file exists); Rhino's ship in `youtube/streams/consumer-rules.pro` (`-keep class org.mozilla.javascript.** { *; }`, `-keep class org.mozilla.classfile.ClassFileWriter`, `-dontwarn org.mozilla.javascript.tools.**`), so they reach only `foss`. With `android.r8.strictFullModeForKeepRules=true`, `-keep class A` no longer keeps constructors: write `-keep class A { <init>(...); }` explicitly.
- **Tests per flavor:** AGP 9 creates unit tests only for the tested build type; flavor-specific code stays in `:app` and `:youtube:streams`, so library tests run once.

### Core library desugaring

NewPipe Extractor needs `desugar_jdk_libs_nio` below API 33. `isCoreLibraryDesugaringEnabled` is a module-level `compileOptions` switch, not a flavor property, so **from M9 it is enabled in `:app` for both flavors** (`coreLibraryDesugaring(libs.desugar.jdk.libs.nio)`); M0–M8 builds do not enable it. Consequence: `playRelease` may contain desugared-library classes (`j$.*`, GPL-2.0-only WITH Classpath-exception-2.0). At M9 the release check records whether R8 leaves any `j$.` class in the `play` dex (our own code does not use the affected `java.nio.file` APIs, so none are expected — Unverified). Licence handling: `coreLibraryDesugaring` is a separate configuration, not part of `<variant>RuntimeClasspath`, so Licensee and AboutLibraries probably never see it (Unverified; the scoped Licensee entry in the [allow-list](#licensee-allow-list) covers the case where they do). Because the L8-compiled `j$` classes ship in the `foss` dex (and possibly `play`'s), `:app` adds a manual AboutLibraries definition for `desugar_jdk_libs_nio` with the GPL-2.0-with-Classpath-Exception text ([AboutLibraries](#aboutlibraries-and-the-licences-screen)), in both flavors unless the M9 check proves `play` free of `j$` classes. [D3](../PLAN.md#3-key-decisions)'s "no GPL-3.0 code in `play`" holds either way; its "permissively licensed dependencies" wording is an open item ([Open questions](#open-questions)).

---

## Networking baseline

Serves N3, N6, N7, N9. Delivered in M0 (base client), M1 (feed client, auth), M4 (media), M6 (download), M7 (API), M9 (YouTube). Honours [D10](../PLAN.md#3-key-decisions), [D28](../PLAN.md#3-key-decisions), PO-13 default.

### One client family

All HTTP goes through one base `OkHttpClient` built in `:core:network`; purpose-specific clients are derived with `newBuilder()` so they share the dispatcher, connection pool and interceptors. **No OkHttp `Cache` anywhere** ([D10](../PLAN.md#3-key-decisions)); feeds keep their own validators ([03 Fetch pipeline](03-feeds-and-discovery.md#fetch-pipeline)), Coil its own disk cache, Media3 its `SimpleCache`, search an in-memory LRU. Constructing `OkHttpClient()` or `OkHttpClient.Builder()` outside `:core:network` is banned.

```kotlin
// :core:network
enum class HttpClientKind { FEED, API, IMAGE, MEDIA, DOWNLOAD, YOUTUBE }
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class HttpClient(val kind: HttpClientKind)

@Module @InstallIn(SingletonComponent::class)
internal object NetworkModule {
    @Provides @Singleton fun base(ua: UserAgentInterceptor, auth: AuthInterceptor, lanGuard: LocalNetworkGuardInterceptor,
                                  hints: DnsFamilyHints): OkHttpClient =
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 8 })
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .dns(LocalNetworkGuardDns(FamilyHintDns(Dns.SYSTEM, hints)))   // outermost: LAN guard; inner: 04's IP-family hints
            .addInterceptor(lanGuard)               // first application interceptor: IP-literal and .local hosts (Dns is skipped for IP literals)
            .addInterceptor(ua)                     // application interceptor: once per call, kept on redirects
            .addNetworkInterceptor(auth)            // network interceptor: re-evaluated on every redirect hop
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .build()                                // followRedirects/followSslRedirects/retryOnConnectionFailure: OkHttp defaults (true)
    @Provides @Singleton @HttpClient(HttpClientKind.FEED) fun feed(b: OkHttpClient) = b.newBuilder().callTimeout(120, TimeUnit.SECONDS).build()
    @Provides @Singleton @HttpClient(HttpClientKind.API) fun api(b: OkHttpClient) = b.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    @Provides @Singleton @HttpClient(HttpClientKind.IMAGE) fun image(b: OkHttpClient) = b.newBuilder().readTimeout(20, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    @Provides @Singleton @HttpClient(HttpClientKind.MEDIA) fun media(b: OkHttpClient) = b.newBuilder().addInterceptor(IdentityEncodingInterceptor).build()
    @Provides @Singleton @HttpClient(HttpClientKind.DOWNLOAD) fun download(b: OkHttpClient) =
        b.newBuilder().readTimeout(60, TimeUnit.SECONDS).addInterceptor(IdentityEncodingInterceptor).build()
    @Provides @Singleton @HttpClient(HttpClientKind.YOUTUBE) fun youtube(b: OkHttpClient) = b.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()
}
```

| Kind | Consumer (owner) | Timeouts (connect / read / call) | Extras |
|---|---|---|---|
| FEED | `FeedFetcher` (03) | 15 s / 30 s / 120 s | 32 MB cap and streaming SHA-256 in 03; no `Accept-Encoding` override (OkHttp gzip) |
| API | Apple, fyyd, Podcast Index search (03); small JSON calls such as oEmbed (04) | 15 s / 30 s (inherited) / 8 s | the 8 s call timeout caps the whole call and equals the canonical per-provider timeout |
| IMAGE | Coil `OkHttpNetworkFetcherFactory` (08) | 15 s / 20 s / 60 s | Coil disk cache only |
| MEDIA | Media3 `OkHttpDataSource.Factory` (06) | 15 s / 30 s / none | `Accept-Encoding: identity` (byte-exact ranges, `SimpleCache` keys) |
| DOWNLOAD | `RssTransferSource`, `YouTubeTransferSource` (07) | 15 s / 60 s / none | `Accept-Encoding: identity`; 07 asserts it in tests |
| YOUTUBE | `OkHttpNpeDownloader` (04, `foss`, M9) | 15 s / 30 s / 60 s | 04 may derive further (e.g. an IPv4-only `Dns` retry) from this client with `newBuilder()` |

`IdentityEncodingInterceptor` is an application interceptor that sets `Accept-Encoding: identity`, so OkHttp's bridge neither adds gzip nor decompresses. Dispatcher note: `executeAsync()`, Media3's `OkHttpDataSource` and Coil all go through the dispatcher's async queue; 64 global / 8 per host leaves headroom above the per-area semaphores (feeds 6/2, downloads 3/2, YouTube 1) owned by 03 and 07.

### Interceptors

**`UserAgentInterceptor`** sets `User-Agent: Neutrodyne/<versionName> (Android <Build.VERSION.RELEASE>; +<REPO_URL>)` **only when the request has none**, so NewPipe Extractor's per-request browser UA and any 04-mandated UA survive. Non-ASCII characters are replaced with `?` (OkHttp rejects non-ASCII header values). Some hosts reject generic UAs, so the UA is never empty or the OkHttp default.

**`AuthInterceptor`** (Basic auth for private feeds and their same-origin enclosures, [03](03-feeds-and-discovery.md#feed-moves-auth-and-paging)):

```kotlin
// :core:network
data class Origin(val scheme: String, val host: String, val port: Int) {   // lowercase scheme/host, explicit port
    companion object { fun of(url: HttpUrl) = Origin(url.scheme, url.host.lowercase(), url.port) }
}
fun interface CredentialLookup {                       // in-memory, non-blocking; returns "Basic …" or null
    fun basicAuthorization(origin: Origin): String?
    suspend fun awaitLoaded() {}                       // CredentialStore: suspends until rows are decrypted into memory
    companion object { val None = CredentialLookup { null } }   // M0 binding until CredentialStore (M1)
}

internal class AuthInterceptor @Inject constructor(private val lookup: CredentialLookup) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)
        val value = lookup.basicAuthorization(Origin.of(request.url)) ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", value).build())
    }
}
```

Contract: credentials are attached **only when the hop's origin (scheme, host, port) equals the stored credential's origin**; because it is a network interceptor and OkHttp builds redirect follow-ups from the pre-network request, every hop is re-evaluated and an `https → http` or cross-host redirect never carries credentials. `CredentialStore` (03) keeps the decrypted lookup map in memory and implements `CredentialLookup` (initializer 120 loads it); callers that must not send an unauthenticated first request — `FeedFetcher` (03) and the download runners in `:download:impl` (07), which cannot see `CredentialStore` — call `awaitLoaded()` first. `credential.origin` uses the same `scheme://host:port` normalisation ([02 credential](02-data-model.md#credential)).

**LAN guard (`LocalNetworkGuardDns` + `LocalNetworkGuardInterceptor`).** On devices with `Build.VERSION.SDK_INT >= 37` the app (targetSdk 37, no `ACCESS_LOCAL_NETWORK`, [D28](../PLAN.md#3-key-decisions)) cannot reach LAN hosts, and TCP connections to them "typically result in a timeout error" rather than a clear failure ([Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)). The guard fails fast instead, with `LocalNetworkUnsupportedException` (a subclass of `UnknownHostException`):

- `LocalNetworkGuardDns` wraps the resolver chain and throws when **every** resolved address is local — IPv4 10/8, 172.16/12, 192.168/16, 169.254/16; IPv6 `fc00::/7`, `fe80::/10` — and the host is not loopback. Mixed public/private answers pass.
- `LocalNetworkGuardInterceptor` (first application interceptor) throws for a request whose host is an IP literal in those ranges or ends in `.local` (mDNS). It exists because OkHttp does not call `Dns` for IP-literal hosts. Known gap: a redirect hop to an IP-literal LAN host is not seen by application interceptors and ends as an ordinary connect timeout (`NetError.Timeout`).
- Below API 37 both are pass-throughs (LAN feeds keep working there). Loopback (`127.0.0.0/8`, `::1`) always passes.
- Unverified: the exact address set Android 17 treats as "local network" (the page names local addresses and `.local` without listing ranges; a LAN DNS server on port 53 is exempt); adjust the range list when device tests show otherwise.

**`DnsFamilyHints` / `FamilyHintDns`** (requested by [04 IP-family matching](04-youtube.md#ip-family-matching)): `DnsFamilyHints` (`@Singleton`, `:core:network`) holds `hostSuffix → IpFamily?` pairs in memory (`set(hostSuffix: String, family: IpFamily?)`, `null` clears). `FamilyHintDns` returns only the A (`V4`) or only the AAAA (`V6`) records for a host equal to or ending in `.` + a hinted suffix, and all records when that family has none or no hint exists. `IpFamily` is 04's enum; it is declared in `:core:model` (not `:youtube:api`) so that `:core:network` can read it under rule 11. All derived clients inherit the chain, so MEDIA and DOWNLOAD requests to `googlevideo.com` follow the hint.

### Network error taxonomy

```kotlin
// :core:model — what other documents store and show (podcast.lastErrorKind, download.lastError, UI strings)
sealed interface NetError {
    data object Offline : NetError                     // no validated network at failure time
    data object Timeout : NetError
    data object DnsFailure : NetError
    data object ConnectionFailed : NetError            // refused, reset, unreachable
    data object LocalNetworkUnsupported : NetError     // Android 17 LAN host, v1 policy (D28)
    data class Tls(val kind: TlsKind) : NetError
    data object Cancelled : NetError
    data class Other(val type: String) : NetError      // exception class simple name, for diagnostics
}
enum class TlsKind { UNTRUSTED_CERTIFICATE, CERTIFICATE_TRANSPARENCY, HANDSHAKE }

// :core:network
interface NetErrorClassifier { fun classify(e: IOException): NetError }
```

| Exception (cause chain inspected) | `NetError` |
|---|---|
| `LocalNetworkUnsupportedException` | `LocalNetworkUnsupported` |
| `UnknownHostException` while `NetworkMonitor.status.isConnected == false` | `Offline` |
| other `UnknownHostException` | `DnsFailure` |
| `SocketTimeoutException`, `InterruptedIOException("timeout")` | `Timeout` |
| `ConnectException`, `NoRouteToHostException`, `SocketException` | `ConnectionFailed` (or `Offline` if disconnected) |
| `SSLHandshakeException` whose chain mentions "Certificate Transparency" (case-insensitive) | `Tls(CERTIFICATE_TRANSPARENCY)` — Unverified failure text |
| `SSLHandshakeException` with `CertPathValidatorException` / "Trust anchor" | `Tls(UNTRUSTED_CERTIFICATE)` |
| other `SSLException` | `Tls(HANDSHAKE)` |
| `IOException("Canceled")` from a cancelled call | `Cancelled` |
| anything else | `Other(simpleName)` |

HTTP status handling is not part of this taxonomy; each area's response-code table owns it ([03](03-feeds-and-discovery.md#fetch-pipeline), [07](07-downloads.md#transfer-core)).

### NetworkMonitor

```kotlin
// :core:common (interface, so features and domain can observe it)
data class NetworkStatus(val isConnected: Boolean, val isValidated: Boolean, val isMetered: Boolean, val isVpn: Boolean)
interface NetworkMonitor { val status: StateFlow<NetworkStatus> }
```

`ConnectivityNetworkMonitor` (`:core:network`) registers one `registerDefaultNetworkCallback` for the process, maps `NetworkCapabilities` (`isMetered = !(NOT_METERED || TEMPORARILY_NOT_METERED on API 30+)`, `isValidated = NET_CAPABILITY_VALIDATED`, `isVpn = TRANSPORT_VPN`), seeds the initial value synchronously from `activeNetwork`, and shares with `stateIn(appScope, SharingStarted.Eagerly, initial)`. Eagerly, not `WhileSubscribed`: `NetErrorClassifier`, 03's validator rule and 07's claim conditions read `status.value` without collecting, and a `WhileSubscribed` flow would hand them a stale value. One process-lifetime callback costs nothing measurable. Data Saver handling is 07's.

### Network security config

`core/network/src/main/res/xml/network_security_config.xml`, referenced by the app manifest:

```xml
<network-security-config>
    <!-- D28 / PO-13: cleartext allowed (many feeds, enclosures and covers are http://); system CAs only -->
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors><certificates src="system" /></trust-anchors>
    </base-config>
    <!-- Debuggable builds only: user CAs for proxy debugging. Never applies to release. -->
    <debug-overrides>
        <trust-anchors><certificates src="system" /><certificates src="user" /></trust-anchors>
    </debug-overrides>
</network-security-config>
```

- `android:usesCleartextTraffic` is never set (Android 17 announces its deprecation).
- **Certificate Transparency** is enforced by default for targetSdk 37. The schema does allow `<certificateTransparency enabled="false"/>` in `base-config` or a `domain-config` ([Network security config](https://developer.android.com/privacy-and-security/security-config)), but v1 uses neither: feed hosts are user-chosen, so a static per-domain list cannot help, and a global opt-out would weaken every connection. A CT or untrusted-CA failure is a per-feed error, never a silent drop ([03 Fetch pipeline](03-feeds-and-discovery.md#fetch-pipeline)). Certificates from the user store (debug builds only) are not CT-checked by the platform.
- **Scheme-less user input tries `https://` first** — implemented by 03's input normalisation, not here.
- **ECH:** for targetSdk 37 the platform uses Encrypted Client Hello when the networking library integrates it ([Android 17 behaviour changes](https://developer.android.com/about/versions/17/behavior-changes-17)); OkHttp 5.5.0's ECH support is opt-in ([OkHttp changelog](https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md)) and is not enabled in v1 (revisit in v1.x).

---

## Platform compliance

Serves N2, N7. Delivered in M0 (checklist and manifest), verified in M11. Honours [D43](../PLAN.md#3-key-decisions), [D5](../PLAN.md#3-key-decisions). Every rule that binds an app with minSdk 26 / targetSdk 37, mapped to Neutrodyne's mechanism and the owning document.

| # | Rule (platform version, scope) | Neutrodyne mechanism | Owner |
|---|---|---|---|
| P1 | Play: new apps and updates must target API 36 from 2026-08-31 (extension to 2026-11-01) | targetSdk 37 | 01 |
| P2 | A15 (target 35) edge-to-edge enforced; A16 (target 36) opt-out removed | `enableEdgeToEdge()` in `MainActivity`; never `windowOptOutEdgeToEdgeEnforcement`; insets per [08 Adaptive layouts](08-ui-ux.md#adaptive-layouts) | 01, 08 |
| P3 | A16 (target 36) predictive back on by default; `onBackPressed()` not called, `KEYCODE_BACK` not dispatched | Nav3 back; `NavigationBackHandler`/`PredictiveBackHandler`; `android:enableOnBackInvokedCallback="true"` for Android 13–15 devices (Unverified necessity at target 37); `onBackPressed` overrides banned | 01, 08 |
| P4 | A16 (target 36, sw ≥ 600 dp) orientation/resizability/aspect locks ignored; A17 (target 37) opt-out removed | no `screenOrientation`, `resizeableActivity="false"`, `maxAspectRatio`, `minAspectRatio`; designed layouts per [08](08-ui-ux.md#adaptive-layouts) | 01, 08 |
| P5 | A14 FGS types mandatory with matching `FOREGROUND_SERVICE_*` permission | `mediaPlayback` ([06](06-playback.md#service-architecture)); `dataSync` only via WorkManager's `SystemForegroundService` ([07](07-downloads.md#runners-and-scheduling)) | 06, 07 |
| P6 | A15 `dataSync` FGS limited to 6 h / 24 h; `mediaPlayback` and `dataSync` FGS cannot start from `BOOT_COMPLETED` | `dataSync` used only for API 26–33 manual downloads (D47); no FGS from boot; resumption via `MediaButtonReceiver` + `onPlaybackResumption` ([06 System surfaces](06-playback.md#system-surfaces)) | 06, 07 |
| P7 | A16 (all apps) job runtime quota also applies to jobs started while visible and continuing, and to jobs running beside an FGS | resumable workers, 8-min soft deadlines, stop reasons logged; UIDT for manual downloads ([03](03-feeds-and-discovery.md#refresh-scheduling), [07](07-downloads.md#runners-and-scheduling)) | 03, 07 |
| P8 | A14 UIDT jobs: `RUN_USER_INITIATED_JOBS`, schedulable only while visible, `setNotification` within 10 s of `onStartJob`, not quota-bound | `ManualDownloadJobService`, namespace `downloads`, `JOB_ID_MANUAL = 1001` | 07 |
| P9 | A14 (target 34) JobScheduler network constraints require `ACCESS_NETWORK_STATE` | declared by `:core:network` | 01 |
| P10 | A14+ tasks that time out too often can push the app into the restricted bucket | soft deadlines everywhere; diagnostics shows the bucket ([09](09-quality-and-release.md#crash-reporting-and-diagnostics)) | 03, 07, 09 |
| P11 | A17 (all apps) background audio hardening: playback/focus/volume need a visible activity or a non-`shortService` FGS; target 37 requires while-in-use capability; failures are silent | [D43](../PLAN.md#3-key-decisions) start paths; "Tap to resume" after demotion; `set-enable-hardening throw` test ([06 Background restrictions](06-playback.md#background-restrictions)) | 06 |
| P12 | A15 (target 35) audio focus only for the top app or an app running an FGS | focus requested only by the playback service | 06 |
| P13 | A12+ background FGS-start restrictions (exemptions include notification/widget interaction and media buttons; running a job is not an exemption) | playback starts from UI, notification, media key; `onForegroundServiceStartNotAllowedException` handled ([06](06-playback.md#background-restrictions)); downloads never start an FGS from a job on API 34+ | 06, 07 |
| P14 | A17 (target 37) Certificate Transparency on by default (opt-out possible globally or per domain in the network security config) | opt-out not used; `NetError.Tls(CERTIFICATE_TRANSPARENCY)` surfaced per feed | 01, 03 |
| P15 | A17 (target 37) `ACCESS_LOCAL_NETWORK` runtime permission (`NEARBY_DEVICES` group); without it TCP to LAN hosts typically times out, UDP fails with `EPERM` | permission not requested in v1; the [LAN guard](#interceptors) fails fast with `LocalNetworkUnsupported` on API 37+ ([D28](../PLAN.md#3-key-decisions)) | 01, 03 |
| P16 | Cleartext blocked by default since target 28; A17 (all apps) plans to deprecate `usesCleartextTraffic` | network security config `base-config cleartextTrafficPermitted="true"` | 01 |
| P17 | User-installed CAs not trusted since target 24 | release trusts system CAs only; debug-overrides add user CAs | 01 |
| P18 | A17 (all apps) RAM-based per-app memory limits | bounded Coil memory cache and sized decodes ([08](08-ui-ux.md#artwork-pipeline)); streaming parse, no whole-feed strings ([03](03-feeds-and-discovery.md#parser)) | 08, 03 |
| P19 | A17 (target 37) widget `RemoteViews` bitmap memory cap | v1.x widgets pass artwork as content-URI icons | 08 |
| P20 | A17 (target 37) reflection on `static final` fields blocked; lock-free `MessageQueue` | our code uses neither; library impact checked by the API 37 instrumented smoke run (M0) and the minified `fossRelease` YouTube smoke test (M9). Unverified: impact on Rhino and on LeakCanary (debug only) | 01, 04, 09 |
| P21 | 16 KB page sizes required for apps targeting 35+ with native code; Play blocks non-compliant updates from 2027-02-01 | only native code is `sqlite-bundled`; [S6](#s6-sqlite-bundled-16-kb-alignment-and-size); CI `zipalign -c -P 16` ([09](09-quality-and-release.md#ci-pipelines)) | 01, 09 |
| P22 | A13 `POST_NOTIFICATIONS` runtime permission; media-session notifications exempt; FGS start does not need it | requested contextually (first download, first new-episode opt-in), never at launch ([03](03-feeds-and-discovery.md#new-episode-notifications), [07](07-downloads.md#progress-and-notifications)) | 03, 07 |
| P23 | A13 per-app language (`localeConfig`); AppCompat backport | `generateLocaleConfig = true`, `res/resources.properties` (`unqualifiedResLocale=en-US`), `MainActivity : AppCompatActivity` with an AppCompat theme, `AppLocalesMetadataHolderService` (`autoStoreLocales`) for API ≤ 32; picker UI per [09 Localisation](09-quality-and-release.md#localisation) | 01, 09 |
| P24 | A12 `android:exported` required on components with intent filters; `PendingIntent` mutability flag required | every component declares `exported`; all `PendingIntent`s `FLAG_IMMUTABLE` with explicit components (Unverified source: not re-checked in this research) | 01 |
| P25 | A14 (target 34) implicit intents reach only exported components; mutable `PendingIntent`s with implicit intents throw; runtime receivers need an export flag | internal intents explicit; `ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED)` (Unverified source: not re-checked in this research) | 01 |
| P26 | A11 package visibility | no `queryIntentActivities`/`resolveActivity` probing: `startActivity` + catch `ActivityNotFoundException`; only `<queries>` entry is ACRA's `mailto` (Unverified need); no `<queries>` for YouTube helper apps in `play` ([04](04-youtube.md#licensing-and-legal)) | 01 |
| P27 | A16 Safer Intents opt-in (`android:intentMatchingFlags="enforceIntentFilter"`), planned to become default | not adopted in v1 (internal explicit intents carry `neutrodyne://open/…` data that matches no filter); see [Open questions](#open-questions) | 01 |
| P28 | Auto Backup: 25 MB cap; `<include>` disables defaults; A16 QPR2 `cross-platform-transfer` element | include-only rules, no cross-platform section ([05 Auto Backup](05-groups-opml-backup.md#auto-backup), D34) | 05 |
| P29 | Play policy: no direct battery-optimisation exemption request for podcast apps; exact alarms not needed | no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`; diagnostics links to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` only (N2) | 01, 09 |
| P30 | A14/A15 background-activity-launch hardening for `PendingIntent` senders and creators; A17 adds further BAL hardening (`MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`; Unverified details) | activities start only from a user tap on a notification (sent by the system) or from a visible activity (`ExternalImportActivity` → `MainActivity`); our code never calls `PendingIntent.send()` for an activity and uses no full-screen intents | 01 |
| P31 | A17 (target 37) Encrypted Client Hello used when the networking library supports it | OkHttp's ECH stays off in v1 ([Network security config](#network-security-config)) | 01 |

---

## Manifest and permissions

Serves N2, N7, N3. Delivered in M0 (app shell), extended in M1, M3, M4, M5, M6, M9. Each module declares what its own code needs (even if a library also merges it), so removing a library never silently drops a permission. `app/policy/permissions.txt` holds the exact expected merged set; [`verifyManifestPermissions`](#gradle-side-policy-tasks) fails on any difference.

### Permissions

| Permission | Declared by | From | Why |
|---|---|---|---|
| `INTERNET` | `:core:network` | M0 | everything |
| `ACCESS_NETWORK_STATE` | `:core:network` | M0 | `NetworkMonitor`; JobScheduler/WorkManager network constraints (A14) |
| `POST_NOTIFICATIONS` | `:app` | M0 | new-episode, download, import and alert notifications; requested contextually only |
| `FOREGROUND_SERVICE` | `:playback:impl`, `:download:impl` | M4, M6 | playback FGS; WorkManager foreground worker (API 26–33 manual downloads) |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | `:playback:impl` | M4 | `NeutrodynePlaybackService` |
| `FOREGROUND_SERVICE_DATA_SYNC` | `:download:impl` | M6 | `SystemForegroundService` type `dataSync`, only for API 26–33 manual downloads started while visible (D47) |
| `WAKE_LOCK` | `:playback:impl` (+ merged by media3, WorkManager) | M4 | ExoPlayer wake/Wi-Fi locks; WorkManager |
| `RUN_USER_INITIATED_JOBS` | `:download:impl` | M6 | UIDT manual downloads (API 34+) |
| `RECEIVE_BOOT_COMPLETED` | `:download:impl` (+ merged by WorkManager) | M6 | persisted UIDT job (`setPersisted(true)`); WorkManager reschedules — never starts an FGS |
| `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | merged by `androidx.core` (declared `signature`-level and used by the app itself) | M0 | `ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED)` on API < 33 (P25). Unverified exact merged name; the first `verifyManifestPermissions` run in M0 shows it, and `permissions.txt` lists it with the release `applicationId` (`app.neutrodyne.…`) |

**Explicitly not requested** (adding any requires a PLAN amendment): `ACCESS_LOCAL_NETWORK` (v1.x, D28), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO`, `MANAGE_EXTERNAL_STORAGE` (downloads use app-specific storage, D48), `FOREGROUND_SERVICE_SPECIAL_USE`, `BLUETOOTH_CONNECT`, `QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`, any location permission, `com.google.android.gms.permission.AD_ID`. A library that merges one of these is fixed with `tools:node="remove"` in `:app` and a comment.

### Application element and components

```xml
<!-- Merged view (sketch). Comments name the declaring module and owning document. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
  <queries>  <!-- :app (09): ACRA mail sender; Unverified need -->
    <intent><action android:name="android.intent.action.SENDTO" /><data android:scheme="mailto" /></intent>
  </queries>
  <application
      android:name=".NeutrodyneApplication"
      android:label="@string/app_name" android:icon="@mipmap/ic_launcher"
      android:theme="@style/Theme.Neutrodyne"
      android:supportsRtl="true"
      android:appCategory="audio"
      android:enableOnBackInvokedCallback="true"
      android:networkSecurityConfig="@xml/network_security_config"
      android:allowBackup="true"
      android:dataExtractionRules="@xml/data_extraction_rules"
      android:fullBackupContent="@xml/backup_rules"
      android:hasFragileUserData="true">
    <!-- localeConfig is generated by generateLocaleConfig -->

    <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTop"
              android:theme="@style/Theme.Neutrodyne.Starting"
              android:windowSoftInputMode="adjustResize">          <!-- :app; subscribe/share filters: 03 -->
      <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>
    </activity>
    <activity android:name=".ExternalImportActivity" android:exported="true" />   <!-- :app; filters and theme: 05 (M3) -->

    <service android:name="app.neutrodyne.playback.impl.NeutrodynePlaybackService" android:exported="true"
             android:foregroundServiceType="mediaPlayback" />   <!-- :playback:impl (M4); intent filters: 06 -->
    <receiver android:name="androidx.media3.session.MediaButtonReceiver" android:exported="true" />   <!-- :playback:impl (M5); MEDIA_BUTTON filter: 06 -->
    <meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc" />  <!-- :playback:impl (M5); plain meta-data, no GMS code -->

    <service android:name="app.neutrodyne.download.impl.ManualDownloadJobService" android:exported="false"
             android:permission="android.permission.BIND_JOB_SERVICE" />                       <!-- :download:impl (M6) -->
    <service android:name="androidx.work.impl.foreground.SystemForegroundService"
             android:foregroundServiceType="dataSync" tools:node="merge" />                       <!-- :download:impl (M6) -->
    <receiver android:name="app.neutrodyne.download.impl.DownloadActionReceiver" android:exported="false" />  <!-- :download:impl (M6) -->

    <provider android:name="app.neutrodyne.core.artwork.ArtworkProvider" android:authorities="${applicationId}.artwork"
              android:exported="true" />                       <!-- :core:artwork (M4); read-only contract: 08 -->
    <provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.fileprovider"
              android:exported="false" android:grantUriPermissions="true">                        <!-- :app (M3); paths: 05, 07 -->
      <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
    </provider>
    <provider android:name="androidx.startup.InitializationProvider" android:authorities="${applicationId}.androidx-startup"
              tools:node="merge">                                                                  <!-- :app (M0) -->
      <meta-data android:name="androidx.work.WorkManagerInitializer" android:value="androidx.startup" tools:node="remove" />
    </provider>
    <service android:name="androidx.appcompat.app.AppLocalesMetadataHolderService" android:enabled="false" android:exported="false">
      <meta-data android:name="autoStoreLocales" android:value="true" />                             <!-- :app (M0) -->
    </service>
    <!-- Library-merged and kept: androidx.media3.session.BluetoothValidationActivity (BLUETOOTH_PRIVILEGED-protected, AVRCP workaround) -->
  </application>
</manifest>
```

Attribute decisions:

| Attribute | Value | Reason / owner |
|---|---|---|
| `allowBackup`, `dataExtractionRules`, `fullBackupContent` | true; include-only rules | [05 Auto Backup](05-groups-opml-backup.md#auto-backup) owns the XML. **M0 ships both files with a single include (`datastore/settings.preferences_pb`)** so tester builds never back up the Room DB before M3 adds the snapshot ([D34](../PLAN.md#3-key-decisions)) |
| `hasFragileUserData` | true | uninstall dialog offers to keep data ([07 Storage layout](07-downloads.md#storage-layout)) |
| `appCategory` | `audio` | system categorisation |
| `configChanges` on `MainActivity` | not declared | recreation is the tested path (state saved through Nav3 and ViewModels); PiP in v1.x revisits this ([06 Video](06-playback.md#video)) |
| `theme` | application: `Theme.Neutrodyne` (parent `Theme.AppCompat.DayNight.NoActionBar`); `MainActivity` only: `Theme.Neutrodyne.Starting` (parent `Theme.SplashScreen`, `postSplashScreenTheme = @style/Theme.Neutrodyne`) | `AppCompatActivity` throws without an AppCompat theme; the splash theme stays on the launcher activity so `ExternalImportActivity` (theme: 05) never shows a splash; no MDC dependency |
| `launchMode` | `singleTop` | notification and deep-link intents arrive in `onNewIntent` |
| `directBootAware` | not set | not supported |
| `intentMatchingFlags` | not set | P27 |

---

## Licensing and dependency policy

Serves N8. Delivered in M0 (allow-list, About/Licences, contribution rule), M9 (GPL module obligations). Honours [D3](../PLAN.md#3-key-decisions), [PO-1](../PLAN.md#po-1-licensing-of-shipped-binaries) default A; mitigates risks L2, L4. YouTube-specific legal analysis, source offer and F-Droid anti-feature text: [04 Licensing and legal](04-youtube.md#licensing-and-legal).

### Licence structure

| Artefact | Licence |
|---|---|
| Repository (everything except `youtube/streams/`) | Unlicense (`LICENSE` at the root) |
| `youtube/streams/` | GPL-3.0-or-later: `youtube/streams/LICENSE` (GPL text) and `README.md` from M9; every source file starts with `// SPDX-License-Identifier: GPL-3.0-or-later` from the M0 stub on |
| `foss` APK | from M9: GPL-3.0-or-later as a whole (corresponding source = the public tag `vX.Y.Z`); before M9: Unlicense code + permissive dependencies |
| `play` APK | Unlicense code + permissive dependencies (+ possibly the desugaring runtime, [Build flavors](#core-library-desugaring)) |

### Licensee allow-list

Applied in `:app`, run as `licenseeFossRelease` and `licenseePlayRelease` (part of `check`).

```kotlin
licensee {
    allow("Apache-2.0"); allow("MIT"); allow("BSD-2-Clause"); allow("BSD-3-Clause"); allow("Unlicense"); allow("CC0-1.0")
    // Scoped exceptions — never a global allow() for these licences:
    allowDependency("com.android.tools", "desugar_jdk_libs_nio", "2.1.5") { because("GPL-2.0-only WITH Classpath-exception-2.0; linking permitted (M9); only evaluated if Licensee sees coreLibraryDesugaring") }
    allowDependency("com.github.teamnewpipe", "NewPipeExtractor", "v0.26.5") { because("GPL-3.0-or-later; foss only via :youtube:streams (PO-1)") }
    allowDependency("org.mozilla", "rhino", "1.8.1") { because("MPL-2.0; NewPipe Extractor dependency, foss only") }
    allowDependency("org.mozilla", "rhino-engine", "1.8.1") { because("MPL-2.0; NewPipe Extractor dependency, foss only") }
    // nanojson (com.github.TeamNewPipe): add a scoped allowDependency at M9 once its POM licence is read (Unverified metadata)
    // allowUrl(...) entries only for artifacts whose POM names a known licence by URL, each with because(...)
}
```

Licensee is per-project, not per-variant, so the GPL/MPL entries are dependency-scoped and `verifyDependencyPolicy` separately proves they never reach `playReleaseRuntimeClasspath`. Any new licence (e.g. ISC, EPL) needs a reviewed PR adding a scoped `allowDependency`, never a global `allow`. Bumping an allow-listed GPL/MPL artifact's version requires updating its `allowDependency` line (deliberate friction on the fast lane).

### AboutLibraries and the Licences screen

- The AboutLibraries Gradle plugin in `:app` generates per-variant library metadata (so `foss` lists NewPipe Extractor, Rhino and nanojson; `play` does not). Unverified: whether 15.x uses the plugin ID `com.mikepenz.aboutlibraries.plugin` or `com.mikepenz.aboutlibraries.plugin.android` for Android variants — M0 step 18 confirms.
- `:feature:settings` renders `LicencesKey` itself with `Nd*` components from `aboutlibraries-core` data (loaded at runtime from the app's generated resource) — **not** with AboutLibraries' Compose UI artifact, which could pull a different Compose/Material3 line (the same trap as `material-kolor`). M0 step 18 checks `aboutlibraries-core` has no Compose dependency.
- Each entry shows name, version, licence name and the full licence text; Apache-2.0 `NOTICE` contents are added through AboutLibraries' `config/aboutlibraries/` overrides where a dependency ships one (Unverified which do).
- Manual library definitions in the same config directory cover code that ships in the APK but is not on a runtime classpath: from M9 `desugar_jdk_libs_nio` (GPL-2.0-only WITH Classpath-exception-2.0, [Core library desugaring](#core-library-desugaring)); and any permissive snippet copied under the [contribution rule](#copied-code-and-contributions) (mirrors `THIRD_PARTY_NOTICES.md`).
- **Packaging:** never exclude `META-INF/LICENSE*` or `META-INF/NOTICE*` wholesale; only the duplicate `/META-INF/{AL2.0,LGPL2.1}` entries are excluded ([common config](#common-android-configuration)).

### About statements

`BuildInfo.licenceStatementResId` points to a flavor string in `:app` (`strings_flavor.xml`); `:feature:settings` shows it with the version, flavor name and a "Source code" link to `BuildInfo.repoUrl`.

| Build | Statement (en) |
|---|---|
| `play`, and `foss` before M9 | "Neutrodyne's source code is dedicated to the public domain under the Unlicense. Third-party components and their licences are listed under Licences." |
| `foss` from M9 | "Neutrodyne's own source code is dedicated to the public domain under the Unlicense. This build includes NewPipe Extractor, licensed under the GNU General Public License version 3 or later, so this build as a whole is distributed under GPL-3.0-or-later. Source code for this version: {repoUrl}/tree/v{versionName}" |

[04 Licensing and legal](04-youtube.md#licensing-and-legal) owns the NewPipe Extractor notice wording on the Licences screen and must use the statement above for About.

### Copied code and contributions

`CONTRIBUTING.md` and the PR template (created in M0) carry this rule verbatim:

1. **Behaviour-only reuse.** Never copy code from GPL projects (AntennaPod, NewPipe app, LibreTube, Podcini, NewPipe Extractor internals) or MPL projects (Pocket Casts) into any module except `:youtube:streams`. Re-implement behaviour from documentation and design notes.
2. **Permissive snippets** (Apache-2.0, MIT, BSD — e.g. nav3-recipes) may be copied only with their original copyright header and an `SPDX-License-Identifier` line kept, plus an entry in `THIRD_PARTY_NOTICES.md`.
3. Contributions outside `youtube/streams/` are dedicated under the Unlicense; contributions inside it are GPL-3.0-or-later.
4. PR template checkbox: "No code was copied from GPL/MPL projects; any copied permissive code keeps its header and is listed in THIRD_PARTY_NOTICES.md." Reviewers enforce it ([09 Static analysis](09-quality-and-release.md#static-analysis) runs `checkSpdxHeaders`).

---

## M0 scaffold checklist

Delivers [M0](../PLAN.md#m0-scaffold-and-ci). Ordered; each step ends with a green `./gradlew build` unless stated. Steps marked (09) or (08) follow those documents for content.

1. Repository hygiene: `.gitignore` (Gradle, Android Studio, `local.properties`, `*.jks`), `.gitattributes` (`* text=auto eol=lf`, binaries), `.editorconfig` (09).
2. Gradle wrapper: `gradle wrapper --gradle-version 9.7.1 --distribution-type bin`; set `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` from gradle.org's published checksum.
3. Write `gradle/libs.versions.toml` exactly as in [Toolchain and versions](#gradlelibsversionstoml).
4. Write `settings.gradle.kts`, `gradle.properties`, root `build.gradle.kts` ([above](#settingsgradlekts-gradleproperties-root-build)); `compose-stability.conf`.
5. Create `build-logic/` (settings, `convention/build.gradle.kts`, plugin classes for all ten IDs, `ModuleRules.kt`); policy tasks may start as no-ops returning success, filled in step 18.
6. **Spike S1** (KGP pin). Record the result before continuing; on failure apply its fallback.
7. Create every module of [Module layout](#module-layout) (except `:benchmark`) with its plugins, namespace, an `internal` placeholder and one placeholder test; `:youtube:streams` placeholder files carry the GPL SPDX header.
8. `:core:common`: `Clock`, `Dispatcher`/`NeutrodyneDispatchers`, `ApplicationScope`, `Outcome`, `suspendRunCatching`, `Log`/`LogSink`/`Redactor`, `AppInitializer`, `NetworkMonitor`/`NetworkStatus` interfaces — with the unit tests listed in [Testing](#testing).
9. `:core:model`: `Distribution`, `BuildInfo`, `NetError`/`TlsKind`, `IpFamily` (04's enum, placed here), `SettingsFile`/`SettingKey`, `AllSettingKeys` (empty list + test).
10. `:core:navigation`: all canonical keys plus 08's `SettingsHomeKey`, `TopLevelKey`, `EntryProviderInstaller`, `AppNavigator` (with 08's `pushDetail`), `LocalAppNavigator`, `NdSceneMetadata`, and 08's `PaneLayout`/`LocalPaneLayout`/`LocalNavTab`.
11. `:core:datastore`: both `DataStore`s, `SettingsStore`, `DeviceSettingsStore`; `:core:domain`: `SettingsRepository` interface; `:core:data` stub: `CredentialLookup.None` binding.
12. `:core:network`: `NetworkModule` (base + all six derived clients), `UserAgentInterceptor`, `AuthInterceptor`, `IdentityEncodingInterceptor`, `LocalNetworkGuardDns`, `LocalNetworkGuardInterceptor`, `DnsFamilyHints`, `FamilyHintDns`, `NetErrorClassifier`, `ConnectivityNetworkMonitor`, `network_security_config.xml`, manifest permissions — with tests.
13. `:core:testing`: `MainDispatcherRule`, `TestClock`, `FakeNetworkMonitor` (09 owns the full inventory).
14. `:core:designsystem`: `NeutrodyneTheme` (dynamic colour on API 31+, placeholder brand scheme, light/dark) and Material Symbols for the five destinations and the gear (08).
15. Feature stubs: each top-level feature installs its `TopLevelKey` entry showing a placeholder empty state; `:feature:settings` installs `SettingsHomeKey` (the gear's target, 08), `SettingsKey` (M0 renders `ABOUT`: version, flavor, licence statement, source link; 08 adds Appearance) and `LicencesKey`.
16. `:app`: `NeutrodyneApplication` (ACRA guard, initializer runner, entry points), `CoreModule` (dispatchers, scope, `DeviceClock`, `BuildInfo`, `@Multibinds` initializer set), `FlavorModule` × 2 (`Distribution` only in M0, [Flavor modules](#flavor-modules)), `strings_flavor.xml` × 2, `MainActivity` (AppCompat, splash with `StartupViewModel`, edge-to-edge), `NavigationState`, `NeutrodyneNavHost`, overlay scene strategies, `IntentRouter` (internal routes only in M0), `NeutrodyneRoot` with `NavigationSuiteScaffold` (08), `keepRules/app.keep` and `keepRules/foss.keep`, themes XML, manifest per [Manifest and permissions](#manifest-and-permissions) (M0 subset), M0 backup rule files, `res/resources.properties`.
17. ACRA mail + dialog wired to `ACRA_MAILTO` (disabled when empty) (09).
18. Policy tooling: Licensee, module-graph assertion, AboutLibraries (confirm plugin ID; confirm `aboutlibraries-core` has no Compose dependency with `./gradlew :feature:settings:dependencies`), `verifyDependencyPolicy`, `verifyManifestPermissions` with `app/policy/permissions.txt`, `checkSpdxHeaders`, `checkBannedApis`.
19. Spotless/ktlint/compose-rules (blocking) and detekt (non-blocking) (09).
20. GMD definitions (API 26 `aosp`, API 36 `aosp-atd`, API 37 16 KB image) and the M0 instrumented smoke test (09).
21. `ci.yml` (static, unit, assemble), Renovate config, PR template and `CONTRIBUTING.md` with the [contribution rule](#copied-code-and-contributions) (09).
22. **Spikes S2–S6**; write results into [Spikes](#spikes) and the owning documents.
23. Negative verification (PLAN M0 AC2), recorded in the [verification log](#verification-log): (a) add `implementation(project(":feature:library"))` to `:feature:feeds` → `assertModuleGraph` fails; (b) add `implementation(project(":youtube:streams"))` (non-flavored) to `:app` → `verifyDependencyPolicy` fails; (c) add a GPL-licensed artifact to `:core:data` → `licenseeFossRelease` and `licenseePlayRelease` fail. Revert all three.
24. Acceptance run: `./gradlew check assembleFossDebug assemblePlayDebug assembleFossRelease assemblePlayRelease` green on CI in < 15 min; `./gradlew :app:buildEnvironment` shows `kotlin-gradle-plugin` resolved to 2.4.20; installs on API 26 and API 36 GMDs, five labelled destinations, survives rotation and dark-mode switch, back from Settings returns to the tab (predictive-back animation checked manually once on an API 36 image and noted in the log); Licences lists every runtime dependency with its licence.
25. Publish `v0.1.0-beta.1` as a GitHub pre-release (09).

---

## Spikes

Delivered in M0 (PLAN M0). Each spike runs on a throwaway branch or inside the real module skeleton, ends in **go** or **fallback**, and its outcome is written in the table below and in the owning document. A fallback that changes a D-id requires a PLAN amendment.

| ID | Question | Result (fill in) | Recorded also in |
|---|---|---|---|
| S1 | KGP 2.4.20 resolves and builds under AGP 9.4.1 | pending | D4 note in PLAN if fallback |
| S2 | Room 3 `@RawQuery` can return `PagingSource` | pending | [02 Key queries](02-data-model.md#key-queries) |
| S3 | `foreign_keys` enforced with `BundledSQLiteDriver` on every pooled connection | pending | [02 Conventions](02-data-model.md#conventions) |
| S4 | Robolectric runs Room 3 with `AndroidSQLiteDriver` (DAO + migration tests) | pending | [02 Migrations and schema testing](02-data-model.md#migrations-and-schema-testing), [09 Test infrastructure](09-quality-and-release.md#test-infrastructure) |
| S5 | Nav3 1.2 API names, per-tab state retention, sheet/dialog scenes | pending | [Navigation](#navigation), [08 Navigation](08-ui-ux.md#navigation) |
| S6 | `sqlite-bundled` 16 KB alignment and APK size | pending | [09 Performance budgets](09-quality-and-release.md#performance-budgets) |

### S1 KGP 2.4.20 under AGP 9.4.1

- **Method:** build-logic declares `implementation(libs.kotlin.gradlePlugin)` (and the other plugin artifacts). Run `./gradlew :app:buildEnvironment` and `./gradlew :core:common:compileKotlin --info`. Add an in-build assertion to every convention plugin: `check(project.getKotlinPluginVersion() == libs.findVersion("kotlin").get().requiredVersion) { "KGP drift: …" }` with `libs` from `VersionCatalogsExtension` ([catalog rule 5](#gradlelibsversionstoml)) (Unverified API location: `org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion`). Build `assembleFossDebug`.
- **Pass:** `kotlin-gradle-plugin:2.2.10 -> 2.4.20` (or `:2.4.20`) in the output; KSP 2.3.12, Hilt 2.60.1 and the Compose compiler plugin run; only deprecation warnings.
- **Fallback 1:** root `build.gradle.kts` `buildscript { dependencies { classpath(libs.kotlin.gradlePlugin) } }`. **Fallback 2:** set `agp = "9.3.3"` (needs Gradle ≥ 9.5.0, satisfied by 9.7.1; Compose 1.12 needs AGP ≥ 9.2, satisfied), rerun. Record which applied.
- **CI hook:** the `static` job greps `:app:buildEnvironment` for `kotlin-gradle-plugin.*2.4.20` (PLAN M0 AC3) in addition to the in-build assertion.

```mermaid
flowchart LR
  a["build-logic implementation(KGP 2.4.20)"] --> b{"buildEnvironment shows 2.4.20 and build green?"}
  b -->|yes| go["go: AGP 9.4.1"]
  b -->|no| c["root buildscript classpath(KGP)"]
  c --> d{"green?"}
  d -->|yes| go2["go: AGP 9.4.1 + buildscript pin"]
  d -->|no| e["agp = 9.3.3"]
  e --> f{"green?"}
  f -->|yes| fb["fallback: AGP 9.3.3, amend D4"]
  f -->|no| esc["escalate: AGP 9.3.1 (exact tested pair) or architect decision"]
```

### S2 Room 3 RawQuery returning PagingSource

- **Method:** in `:core:database`, three entities (`podcast`, `episode`, `podcast_group_member` subsets), `@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)`, and `@RawQuery(observedEntities = [EpisodeEntity::class, PodcastEntity::class, PodcastGroupMemberEntity::class]) fun feed(query: RoomRawQuery): PagingSource<Int, EpisodeRowTuple>` with a query built like [D30](../PLAN.md#3-key-decisions)'s `FeedQueryBuilder`. Test with `paging-testing` (`TestPager` / `asSnapshot`) under Robolectric (`AndroidSQLiteDriver`) and on a GMD (bundled driver).
- **Also records** (02 depends on them, [02 Room 2 to Room 3 mapping](02-data-model.md#room-2-to-room-3-mapping)): the exact Room 3 names for the read transaction used by backup export (`useReaderConnection` + deferred transaction), `setJournalMode`, `@ColumnTypeConverters`, the `Migration.migrate` and `RoomDatabase.Callback` signatures, `@AutoMigration`, and how `androidx.sqlite.SQLiteException` exposes result codes with each driver.
- **Pass:** compiles; three consecutive pages return `(sortDate, id)` order without gaps or duplicates; an insert into `episode` invalidates; a write to an unobserved table does not.
- **Fallback:** generated `@Query` per (source × order) ([D30](../PLAN.md#3-key-decisions)); 02 records which.

### S3 foreign_keys with the bundled driver

- **Method:** with `BundledSQLiteDriver` on a GMD: (a) insert an `episode` with a missing `podcastId` → expect a constraint error; (b) delete a `podcast` → its `episode` rows cascade; (c) run `PRAGMA foreign_keys` on the writer and on reader connections (`useReaderConnection`) of the WAL pool → expect 1 on each.
- **Pass:** all three hold.
- **Fallback:** 02's `ForeignKeysDriver` — a `SQLiteDriver` decorator whose `open()` runs `PRAGMA foreign_keys = ON` on every new connection, bound in `SqliteDriverModule` around the production and test drivers ([02 Conventions](02-data-model.md#conventions)); 02 records which.

### S4 Robolectric with AndroidSQLiteDriver

- **Method:** Robolectric 4.17, `sdk=36`, JDK 21: build `NeutrodyneDatabase` in memory and in a file with `AndroidSQLiteDriver`; run one DAO test and a `room3-testing` `MigrationTestHelper` create-v1 → validate test. Also try `BundledSQLiteDriver` and record the failure mode. Query `SELECT sqlite_version()`.
- **Pass:** DAO and migration tests green with `AndroidSQLiteDriver`; the reported SQLite version is recorded (02 keeps all SQL compatible with SQLite 3.18, so no minimum beyond that is needed).
- **Fallback:** if `MigrationTestHelper` fails under Robolectric, migration tests run only on GMD (09); if DAO tests fail, DAO tests move to GMD and 09 re-plans the unit-test budget.

### S5 Nav3 1.2 API names and scenes

- **Method:** in `:app`, two tabs with one list-detail pair, one sheet key, one dialog key; verify: `NavDisplay` parameter names (`entries` vs `backStack`, `sceneStrategies` list), `rememberDecoratedNavEntries` (or the 1.2 equivalent) for per-tab decoration, `rememberViewModelStoreNavEntryDecorator`, `rememberSaveableStateHolderNavEntryDecorator`, `ListDetailSceneStrategy` metadata helpers, whether `DialogSceneStrategy`/a bottom-sheet strategy ship in 1.2, `NavigationBackHandler`, `rememberNavBackStack` restoring keys declared in `:core:navigation` after "Don't keep activities" + `adb shell am kill`, `hiltViewModel(creationCallback)` per entry, and — for 08 — a custom `PaneScaffoldDirective` passed to `ListDetailSceneStrategy`, an `extraPane()` entry placed directly after a `listPane()` entry, a sheet opened from the expanded `PlayerSheet` drawing above it (window-based sheet), and the order of `PredictiveBackHandler` (player sheet) versus `NavDisplay`'s back handling. `DialogSceneStrategy` ships since 1.1; check whether its metadata is the typed `DialogKey` and whether it accepts our `NdDialog`.
- **Pass:** process-death restore of both tabs' stacks; distinct ViewModels for two `PodcastKey`s; a tab's entry keeps its ViewModel while another tab is shown; sheet and dialog render as overlays and dismiss on back.
- **Fallback:** the bottom-sheet strategy is ours in any case (nav3-recipes pattern); if per-tab retention fails, accept ViewModel recreation on tab switch with state in `SavedStateHandle` and record it for 08; if an extra pane cannot follow a list pane, 08's documented fallback (`EpisodeKey` as `detailPane()`) applies.

### S6 sqlite-bundled 16 KB alignment and size

- **Method:** build `fossRelease` with and without `sqlite-bundled`; `zipalign -c -P 16 -v 4 app-foss-release.apk`; `llvm-readelf -l lib/arm64-v8a/*.so` → every `LOAD` segment `Align 0x4000`; compare APK size per ABI.
- **Pass:** aligned; size delta recorded (budget context: `foss` APK < 25 MB, N5).
- **Fallback:** `AndroidSQLiteDriver` in production — a one-line change in `SqliteDriverModule`, cheap because 02 already restricts SQL to SQLite 3.18 features; still changes [D9](../PLAN.md#3-key-decisions), PLAN amendment required.

### Verification log

| Date | Check | Result |
|---|---|---|
| (M0) | `:feature:feeds → :feature:library` fails `assertModuleGraph` | pending |
| (M0) | non-flavored `:youtube:streams` in `:app` fails `verifyDependencyPolicy` | pending |
| (M0) | GPL artifact in `:core:data` fails Licensee for both variants | pending |
| (M0) | predictive back from Settings animates on API 36 (manual) | pending |
| (M0) | `foss.keep` rules present in the merged R8 configuration of `fossRelease`, absent from `playRelease` | pending |
| (M0) | merged release permissions equal `app/policy/permissions.txt` (records the `androidx.core` receiver permission name) | pending |
| (M9) | JitPack-only verification metadata works with the trust regex | pending |
| (M9) | `j$.` classes present in `playRelease` dex? | pending |

---

## Testing

Test infrastructure, runners and CI wiring: [09 Test strategy](09-quality-and-release.md#test-strategy). Foundation-specific tests (all from M0 unless stated):

| Area | Level | Cases |
|---|---|---|
| `Redactor` | JVM, parameterised (TestParameterInjector) | user-info masked; query values masked, names kept; long digit-bearing path segment masked (`/rss/a8F3kq09ZpLm2xQ` → `/rss/…xQ`); `/feed/podcast` kept; fragment dropped; `http`, `https`, IPv6 literal host, port kept; unparsable input; free text with two URLs and a trailing period; idempotence (`url(url(x)) == url(x)`) |
| `suspendRunCatching`, `Outcome` | JVM | `CancellationException` rethrown (cancel parent while block suspends); other throwables captured; `map`/`getOrNull` |
| `SettingKey` registry | JVM | names match the pattern; unique; `ui.*` keys are `DEVICE`; `Choice` with an unknown stored name returns the default |
| DataStore stores | JVM (`PreferenceDataStoreFactory` on a temp dir) | round trip per type; corrupted file → defaults + WARN; two files independent |
| `UserAgentInterceptor` | MockWebServer | UA format; explicit UA preserved; UA kept on a redirect hop; non-ASCII version name sanitised |
| `AuthInterceptor` | MockWebServer (two servers = two origins) | header added for same origin; not added after redirect to another host; not added after `https → http` redirect on the same host; not added when `Authorization` already set; lookup returning null |
| `IdentityEncodingInterceptor` | MockWebServer | MEDIA and DOWNLOAD requests carry `Accept-Encoding: identity`; FEED requests carry OkHttp's default gzip |
| `LocalNetworkGuardDns`, `LocalNetworkGuardInterceptor` | JVM with a fake `Dns` and SDK-level parameter; MockWebServer for the interceptor | all-private on 37 → throws; mixed → passes; loopback → passes; all-private on 36 → passes; IPv6 ULA and link-local; IP-literal `http://192.168.1.5/feed` and `nas.local` on 37 → `LocalNetworkUnsupportedException` without a connect attempt; same URLs on 36 → request proceeds; `http://127.0.0.1:{port}` (MockWebServer) passes on 37 |
| `DnsFamilyHints`, `FamilyHintDns` | JVM with a fake `Dns` returning A + AAAA | no hint → all addresses; `V4` hint for `googlevideo.com` → only A records for `rr1---sn-x.googlevideo.com`, not for `notgooglevideo.com`; `V6` hint with no AAAA → all addresses; `null` clears |
| `NetErrorClassifier` | JVM | every row of the [taxonomy table](#network-error-taxonomy), with `FakeNetworkMonitor` connected and disconnected |
| `ConnectivityNetworkMonitor` | Robolectric (`ShadowConnectivityManager`) | initial value seeded without a collector; `status.value` follows a default-network change with no subscriber (eager sharing); metered/unmetered/VPN mapping |
| `IntentRouter` | Robolectric | every row of the [routing table](#intent-routing); upper-case scheme; 1 MB `EXTRA_TEXT` truncated to 4 KB; non-numeric IDs → `None`; unknown settings page → `None`; no route performs a write (verified with fakes recording calls) |
| Hilt graph | Robolectric `@HiltAndroidTest` in `:app` (both flavors) | graph builds; every canonical `NavKey` (and 08's `SettingsHomeKey`) has exactly one installer entry; every `AppInitializer.order` lies in a defined band; `Distribution` and `YouTubeCapabilities` match the flavor; `TestSqliteDriverModule` replaces the driver |
| Start-up ordering (M1; each framework component from the milestone it lands in) | Robolectric in `:app` with a `DatabaseOpener` whose open completes only when the test releases it | constructing the full `Set<AppInitializer>` and every framework component (`NeutrodynePlaybackService`, `ManualDownloadJobService`, `DownloadActionReceiver`) on the main thread while the open is pending neither throws nor blocks (lazy rule, [Application start-up](#application-start-up)); the runner completes within 5 s after release (no initializer below 100 waits for the database) |
| Navigation | Robolectric + Compose v2 rule + `StateRestorationTester` | push/pop per tab; back from a non-start root returns to Feeds; two `PodcastKey`s get different ViewModels; stacks restored after state restoration; sheet key renders as overlay |
| Initializer runner | JVM (`runInitializers` is a plain suspend function) | runs in ascending `order`; a throwing initializer is logged and later ones still run; cancellation propagates. The `:acra` early return is checked manually once (ACRA crash dialog appears, no WorkManager or session start in its process) |
| Startup gate | Robolectric | splash condition clears when `device_settings` emitted and the database is `Ready`; clears after 400 ms with a database still `Pending`; `StartupGate` is shown and no feature ViewModel is created while `Pending`; `NavDisplay` appears on `Ready` |
| Build policy | Gradle (CI `static` job) | `assertModuleGraph`, Licensee ×2, `verifyDependencyPolicy`, `verifyManifestPermissions`, `checkSpdxHeaders`, `checkBannedApis`, KGP assertion; negative checks once per the [verification log](#verification-log) |
| Smoke | GMD API 26, 36, 37 (16 KB) | launch, five labelled destinations, rotation, dark-mode switch, Settings → About shows flavor and version, Licences non-empty |

Fixtures: none beyond inline tables; `FakeNetworkMonitor` and `TestClock` in `:core:testing`.

---

## Delivery by milestone

| Milestone | Foundation work |
|---|---|
| [M0](../PLAN.md#m0-scaffold-and-ci) | Everything in the [M0 scaffold checklist](#m0-scaffold-checklist): toolchain, catalog, convention plugins, all module stubs, dependency rules and policy tasks, flavors and build types, `:core:common`, `:core:model` basics, `:core:navigation`, `:core:datastore`, `:core:network` (base + derived clients, interceptors, DNS guard, error taxonomy, NSC), Hilt skeleton, Nav3 host, `IntentRouter` (internal routes), `NeutrodyneApplication` start-up and initializer runner, manifest subset, M0 backup rule files, About/Licences, ACRA wiring, CONTRIBUTING/PR template, spikes S1–S6 |
| [M1](../PLAN.md#m1-subscribe-and-ingest-rss) | `:core:database` with `SqliteDriverModule` and the S2–S4 outcomes applied; database-open initializer (100) and `StartupGate` wired to `DatabaseOpener`; `CredentialStore` replaces `CredentialLookup.None` (initializer 120); FEED and IMAGE clients in use; `SettingsRepository` implementation; `refresh-periodic` initializer (200); start-up ordering test; router: `AddPodcastKey` for direct feed URLs and `feed:`/`pcast:`/`podcast:`/`itpc:` (filters by 03) |
| [M2](../PLAN.md#m2-groups-and-group-feeds) | Channel initializers for `new_episodes` and `grp_new_episodes` (10) and per-group channel sync (140); router `SelectFeed`; both `FlavorModule`s provide `YouTubeCapabilities` (all `false`) |
| [M3](../PLAN.md#m3-import-export-and-backup) | `ExternalImportActivity` and `FileProvider` manifest entries; final backup rule XML (05); restore-check initializer (110); `backup-auto-snapshot` scheduling; `import_backup` channel |
| [M4](../PLAN.md#m4-playback-core) | Playback service, permissions and `ArtworkProvider` manifest entries; MEDIA client; `kotlinx-coroutines-guava`, `lifecycle-process`, `kotlinx-serialization-json` in `:playback:impl`; `playback`/`alerts` channels; `:playback:impl` lint config for `@UnstableApi`; both `FlavorModule`s bind `YouTubeStreamResolver` → `ExternalOnlyYouTubeStreamResolver` |
| [M5](../PLAN.md#m5-playback-features-and-system-surfaces) | `MediaButtonReceiver`, Auto meta-data and `automotive_app_desc.xml`; `media3-inspector` |
| [M6](../PLAN.md#m6-downloads) | UIDT service, `SystemForegroundService` override, `DownloadActionReceiver`, `RUN_USER_INITIATED_JOBS`/`FOREGROUND_SERVICE_DATA_SYNC`/`RECEIVE_BOOT_COMPLETED`; DOWNLOAD client; `download-reconcile` and `download-cleanup` initializers; `hasFragileUserData` confirmed; `permissions.txt` updated |
| [M7](../PLAN.md#m7-discovery) | API client in use; exported VIEW/SEND filters complete (03); `PODCASTINDEX_*` plumbing via `BuildInfo` |
| [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds) | Both `FlavorModule`s bind `NoOpYouTubeEnricher`, `UnsupportedYouTubeChannelSearch`, `NoExtractorChannelLookup` (play column in both flavors) |
| [M9](../PLAN.md#m9-youtube-playback-and-downloads-in-foss) | JitPack resolution + verification metadata; NewPipe Extractor and strict Rhino in `:youtube:streams`; GPL `LICENSE`/README; desugaring in `:app` with the AboutLibraries manual entry; YOUTUBE client and `DnsFamilyHints` in use; foss `FlavorModule` switches to the extractor bindings and all-`true` capabilities; scoped Licensee entries; foss About statement; `verifyDependencyPolicy` proven against real GPL artifacts; `j$` check |
| [M10](../PLAN.md#m10-covers-theming-adaptive-layouts-and-accessibility) | `material-color-utilities` in `:core:designsystem`/`:core:artwork`; no foundation changes otherwise |
| [M11](../PLAN.md#m11-release-hardening-and-v10) | `:benchmark` module and baseline-profile plugin; `db-maintenance` initializer; `RingBufferLogSink`; final merged-manifest audit against [Platform compliance](#platform-compliance); Play Publisher only if PO-2 approves; release hygiene per 09 |

---

## New names introduced here

| Name | Kind | Module |
|---|---|---|
| `Distribution` (`FOSS`, `PLAY`) | enum | `:core:model` |
| `BuildInfo` | data class | `:core:model` |
| `NetError`, `TlsKind` | sealed interface, enum | `:core:model` |
| `SettingsFile`, `SettingKey` (`Bool`, `Int32`, `Int64`, `Float32`, `Text`, `TextSet`, `Choice`), `AllSettingKeys` | settings typing | `:core:model` |
| `SettingsError` | sealed error type for `SettingsRepository.set` | `:core:domain` |
| `AppInitializer` | interface | `:core:common` |
| `LogSink`, `LogLevel`, `Redactor`, `LogcatSink`, `RingBufferLogSink` (M11), `DebugHttpLogInterceptor` | logging | `:core:common` (`LogcatSink`, `DebugHttpLogInterceptor`: `:app`) |
| `NetworkStatus` | data class (interface `NetworkMonitor` placed in `:core:common`) | `:core:common` |
| `DeviceClock` | `Clock` implementation | `:app` |
| `HttpClientKind`, `@HttpClient` | qualifier | `:core:network` |
| `IdentityEncodingInterceptor`, `LocalNetworkGuardDns`, `LocalNetworkGuardInterceptor`, `LocalNetworkUnsupportedException`, `DnsFamilyHints`, `FamilyHintDns`, `NetErrorClassifier`, `ConnectivityNetworkMonitor`, `CredentialLookup`, `Origin`, `NetworkModule` | networking | `:core:network` |
| `@SettingsDataStore` | qualifier | `:core:datastore` |
| `SqliteDriverModule`, `TestSqliteDriverModule` | Hilt modules | `:core:database`, `:app/src/test` |
| `CoreModule`, `WorkEntryPoint`, `ImageEntryPoint`, `ArtworkProviderEntryPoint` | Hilt module / entry points | `:app` (`ArtworkProviderEntryPoint`: `:core:artwork`) |
| `TopLevelKey`, `LocalAppNavigator`, `NdSceneMetadata` | navigation | `:core:navigation` |
| `NavigationState`, `NeutrodyneNavHost`, `rememberTabLocalNavEntryDecorator`, `NdBottomSheetSceneStrategy`, `NdDialogSceneStrategy`, `IntentRouter`, `Route`, `StartupViewModel`, `StartupState`, `StartupGate` | navigation / start-up | `:app` |
| `UiText` (`Res`, `Plural`, `Raw`), `UserMessage` | UI-state helpers (used by feature ViewModels and screens) | `:core:ui` |
| `assertModuleGraph` rules file `ModuleRules.kt`; tasks `verifyDependencyPolicy`, `verifyManifestPermissions`, `checkSpdxHeaders`, `checkBannedApis` | build | `build-logic` |
| `compose-stability.conf`, `app/policy/permissions.txt`, `app/src/main/keepRules/app.keep`, `app/src/foss/keepRules/foss.keep`, `playback/impl/lint.xml`, `strings_flavor.xml` (string `licence_statement`), `THIRD_PARTY_NOTICES.md`, `CONTRIBUTING.md` | files | repo |

---

## Open questions

1. **Architect review: `:feeds` → `:core:model`.** Rule 8 makes `:feeds` dependency-free, but the show-notes block model ([D27](../PLAN.md#3-key-decisions)) produced by `:feeds` must reach `:core:ui`, which cannot see `:feeds`. Options: (a) declare the block model in `:core:model` and allow `:feeds → :core:model` (pure JVM to pure JVM, no cycle); (b) keep rule 8 and have `:core:data` map `:feeds` types into a `:core:model` mirror. This document implements (b) by default; 03 and 08 must pick the same and the rule list is amended if (a) is chosen.
2. **Architect review: rules for modules §2.3 leaves open** (rules 10–12: `:*:api`, `:core:{database, datastore, network}` → `:core:{model, common}`; `:core:model`, `:core:common` leaf). Derived from the PLAN 5.1 graph; confirm.
3. **Architect review: `NetworkMonitor` placement.** The canonical list puts it in `:core:network`, which features cannot see; the interface is placed in `:core:common` with the implementation `ConnectivityNetworkMonitor` in `:core:network`.
4. **Architect review: Media3 `Player` for `:feature:player`.** If 06/08 use `media3-ui-compose` state holders, `:feature:player` needs `media3-common` and a `Player` instance; this document allows the external dependency and a qualifier-in-`:playback:api` binding pattern, but 06 must decide.
5. **Desugaring in `play` (D3 wording).** `play` may carry GPL-2.0-with-Classpath-Exception desugaring classes from M9; D3 says "permissively licensed dependencies". Either amend D3 to name this exception or prove at M9 that R8 removes all `j$.` classes from `play`.
6. **Safer Intents (`intentMatchingFlags`).** Not adopted in v1; when Android makes it default, internal `neutrodyne://open/…` intents need a matching (non-exported) target or an action-based scheme. Revisit at the first targetSdk bump after 37.
7. **AAB language splits** (`bundle.language.enableSplit = false`) — Unverified whether per-app language on Play-delivered AABs needs it; harmless, but costs `play` download size. Confirm before the first Play upload (M11).
8. **Repository URL** (`neutrodyne.repoUrl`, User-Agent, About) depends on PO-18 naming the GitHub owner.
9. **`play` About link to the repository.** Linking to the repository root (not releases) is assumed compatible with the PO-2 "never link to the foss APK" guardrail; [09 Distribution channels](09-quality-and-release.md#distribution-channels) confirms with the Play listing review.
10. **Gradle verification trust regex** and **AboutLibraries plugin ID / core artifact** — mechanical uncertainties resolved in M0/M9 checks above.

---

## Sources

All checked 2026-10-04 by the research behind this plan unless marked otherwise.

Toolchain and build:
- Kotlin releases — https://kotlinlang.org/docs/releases.html
- Kotlin Gradle plugin compatibility (KGP 2.4.20 tested to AGP 9.3.1 / Gradle 9.7.0) — https://kotlinlang.org/docs/gradle-configure-project.html
- Compose compiler Gradle plugin — https://developer.android.com/develop/ui/compose/compiler
- AGP releases and requirements — https://developer.android.com/build/releases/gradle-plugin · https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml
- AGP 9.0 breaking changes (built-in Kotlin, new DSL, KGP 2.2.10 runtime dependency, targetSdk default, R8 defaults) — https://developer.android.com/build/releases/agp-9-0-0-release-notes
- AGP 9.3 / 9.2 / 9.1 release notes — https://developer.android.com/build/releases/agp-9-3-0-release-notes · https://developer.android.com/build/releases/agp-9-2-0-release-notes · https://developer.android.com/build/releases/agp-9-1-0-release-notes
- Built-in Kotlin and kapt — https://developer.android.com/build/migrate-to-built-in-kotlin
- AGP 9.4 `CommonExtension` — https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/CommonExtension
- R8 / shrink code, past AGP notes — https://developer.android.com/build/shrink-code · https://developer.android.com/build/releases/past-releases/agp-8-0-0-release-notes
- Android Studio releases — https://developer.android.com/studio/releases · https://developer.android.com/build/releases/about-agp
- Gradle releases — https://gradle.org/releases/ · https://services.gradle.org/versions/current
- KSP — https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-api/maven-metadata.xml · https://github.com/google/ksp/releases

Libraries:
- Compose BOM mapping — https://developer.android.com/develop/ui/compose/bom/bom-mapping
- Compose UI 1.12 requirements — https://developer.android.com/jetpack/androidx/releases/compose-ui
- Material 3 releases and Expressive status — https://developer.android.com/jetpack/androidx/releases/compose-material3 · https://dl.google.com/android/maven2/androidx/compose/material3/material3-android/1.5.0-alpha29/material3-android-1.5.0-alpha29.pom
- Material 3 Adaptive — https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive
- Material icons deprecation — https://developer.android.com/develop/ui/compose/graphics/images/material
- Navigation 3 — https://developer.android.com/jetpack/androidx/releases/navigation3 · https://developer.android.com/guide/navigation/navigation-3/custom-layouts · https://developer.android.com/guide/navigation/navigation-3/animate-destinations · https://github.com/android/nav3-recipes
- Navigation 2 maintenance mode — https://developer.android.com/jetpack/androidx/releases/navigation
- Lifecycle 2.11 — https://developer.android.com/jetpack/androidx/releases/lifecycle
- Activity 1.13 — https://developer.android.com/jetpack/androidx/releases/activity
- core 1.19.1 — https://developer.android.com/jetpack/androidx/releases/core
- Dagger/Hilt — https://github.com/google/dagger/releases · androidx.hilt https://developer.android.com/jetpack/androidx/releases/hilt
- Room 3 — https://developer.android.com/jetpack/androidx/releases/room3 · Room migrations https://developer.android.com/training/data-storage/room/migrating-db-versions
- SQLite drivers — https://developer.android.com/kotlin/multiplatform/sqlite · https://developer.android.com/jetpack/androidx/releases/sqlite · framework SQLite by API https://developer.android.com/reference/android/database/sqlite/package-summary
- DataStore — https://developer.android.com/jetpack/androidx/releases/datastore · file path https://github.com/androidx/androidx/blob/androidx-main/datastore/datastore/src/androidMain/kotlin/androidx/datastore/DataStoreFile.android.kt
- WorkManager — https://developer.android.com/jetpack/androidx/releases/work
- Media3 — https://developer.android.com/jetpack/androidx/releases/media3 · https://github.com/androidx/media/blob/release/RELEASENOTES.md
- AndroidX default minSdk — https://developer.android.com/jetpack/androidx/versions
- OkHttp — https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml · https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md
- Coil — https://coil-kt.github.io/coil/changelog/
- MaterialKolor — https://github.com/jordond/MaterialKolor · https://repo1.maven.org/maven2/com/materialkolor/
- Reorderable — https://github.com/Calvin-LL/Reorderable/blob/main/LICENSE
- Licensee — https://github.com/cashapp/licensee
- detekt compatibility — https://detekt.dev/docs/introduction/compatibility/
- ACRA — https://www.acra.ch/docs/Setup · https://www.acra.ch/docs/Senders
- Robolectric — https://github.com/robolectric/robolectric/releases
- Roborazzi — https://github.com/takahirom/roborazzi
- Gradle Play Publisher — https://github.com/Triple-T/gradle-play-publisher/releases

Platform:
- Play target API — https://developer.android.com/google/play/requirements/target-sdk
- Android 15 behaviour changes — https://developer.android.com/about/versions/15/behavior-changes-15
- Android 16 behaviour changes (targeting / all apps) — https://developer.android.com/about/versions/16/behavior-changes-16 · https://developer.android.com/about/versions/16/behavior-changes-all
- Android 17 behaviour changes (targeting / all apps), background audio — https://developer.android.com/about/versions/17/behavior-changes-17 · https://developer.android.com/about/versions/17/behavior-changes-all · https://developer.android.com/about/versions/17/changes/bg-audio
- Android 17 release — https://en.wikipedia.org/wiki/Android_17 · https://developer.android.com/about/versions/17
- Android 14 behaviour changes (network-state permission for job constraints) — https://developer.android.com/about/versions/14/behavior-changes-14
- FGS service types and timeouts — https://developer.android.com/develop/background-work/services/fgs/service-types · https://developer.android.com/develop/background-work/services/fgs/timeout
- Background FGS-start restrictions — https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- UIDT jobs — https://developer.android.com/develop/background-work/background-tasks/uidt · https://developer.android.com/reference/android/app/job/JobService
- Data-transfer guidance — https://developer.android.com/about/versions/15/changes/datasync-migration
- Battery: restricted bucket, standby, doze exemption policy — https://developer.android.com/develop/background-work/background-tasks/optimize-battery · https://developer.android.com/topic/performance/appstandby · https://developer.android.com/training/monitoring-device-state/doze-standby
- 16 KB pages — https://developer.android.com/guide/practices/page-sizes · https://developer.android.com/16kb-page-size
- Notification permission — https://developer.android.com/develop/ui/views/notifications/notification-permission
- Per-app languages — https://developer.android.com/guide/topics/resources/app-languages
- Network security config (cleartext, user CAs, CT) — https://developer.android.com/privacy-and-security/security-config
- Local network permission — https://developer.android.com/privacy-and-security/local-network-permission
- Auto Backup — https://developer.android.com/identity/data/autobackup
- `hasFragileUserData` — https://developer.android.com/guide/topics/manifest/application-element
- `<data>` matching rules — https://developer.android.com/guide/topics/manifest/data-element
- API distribution — https://apilevels.com/

Licensing and policy:
- Unlicense GPL compatibility — https://en.wikipedia.org/wiki/Unlicense · FSF list mirror https://ftp.gwdg.de/pub/gnu/www/licenses/license-list.html
- GPL combined works — https://en.wikipedia.org/wiki/GNU_General_Public_License (quoting https://www.gnu.org/licenses/gpl-faq.html#MereAggregation)
- NewPipe Extractor (licence, JitPack coordinates, desugaring, Rhino pin, R8 rules) — https://github.com/TeamNewPipe/NewPipeExtractor · https://jitpack.io/com/github/teamnewpipe/NewPipeExtractor/v0.26.5/NewPipeExtractor-v0.26.5.pom
- desugar_jdk_libs_nio — https://dl.google.com/android/maven2/com/android/tools/desugar_jdk_libs_nio/maven-metadata.xml
- Play Device and Network Abuse — https://support.google.com/googleplay/android-developer/answer/9888379
- Play FGS declarations — https://support.google.com/googleplay/android-developer/answer/13392821
- F-Droid inclusion policy, anti-features, reproducible builds — https://f-droid.org/docs/Inclusion_Policy/ · https://f-droid.org/docs/Anti-Features/ · https://f-droid.org/docs/Reproducible_Builds/
- F-Droid dependency-info block — https://forum.f-droid.org/t/build-fails-with-found-extra-signing-block/29220
