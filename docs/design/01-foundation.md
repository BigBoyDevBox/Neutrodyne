# 01 — Foundation

> Status: Draft v1, 2026-10-04; revised 2026-10-05 for the product owner's decisions (no flavors, per-ABI APKs, yt-dlp engine, no GPL); **revised 2026-10-05 for PO-31–PO-35** (notify-only update check in `:core:domain`/`:core:model`/`:core:data` with no `:update:*` modules and no install permission; every published APK a debug build signed with the committed keystore; non-debuggable `benchmark` build type for measurement only; the dev-tools switch; no mirror, no beta channel) · Implements: R3.7 (build side), R6.2–R6.3 (build side: placement of the update check, no install permission), N1 / N2 / N3 / N5 (APK sizes, build types) / N7 / N8 / N10 / N11 / N12 (signing config) (foundation parts) · Milestones: M0 (primary), M1–M11 (incremental foundation work per [Delivery by milestone](#delivery-by-milestone)) · Honours: D2 (amended), D3 (amended), D4, D5, D6, D7, D8, D9, D10, D12, D13 (amended), D14, D28, D35, D43, D60, D61 (amended), D62 (amended), D63 (amended), D72–D77, D78 (update check), D79, D80; PO-1, PO-2, PO-5, PO-8, PO-31, PO-32, PO-33, PO-34, PO-35 resolved; PO-3, PO-7, PO-13, PO-18 defaults · Owns: toolchain and version catalog, convention plugins, modules and dependency rules, architecture and coroutine conventions, processes (main, `:ytx`, `:acra`) and start-up, DI graph and the YouTube bindings, Nav3 wiring, build types (published `debug`, `benchmark`) and the dev-tools switch, the `neutrodyneDebug` signing config, ABI splits, Chaquopy build integration, networking baseline, Android 10–17 compliance checklist, merged manifest, licence policy (Gradle, Python and native components), M0 scaffold and spikes

Contents: [Scope](#scope) · [Toolchain and versions](#toolchain-and-versions) · [Module layout](#module-layout) · [Dependency rules](#dependency-rules) · [Architecture patterns](#architecture-patterns) · [Dependency injection](#dependency-injection) · [Navigation](#navigation) · [Build variants and ABIs](#build-variants-and-abis) · [Networking baseline](#networking-baseline) · [Platform compliance](#platform-compliance) · [Manifest and permissions](#manifest-and-permissions) · [Licensing and dependency policy](#licensing-and-dependency-policy) · [M0 scaffold checklist](#m0-scaffold-checklist) · [Spikes](#spikes) · [Testing](#testing) · [Delivery by milestone](#delivery-by-milestone) · [New names introduced here](#new-names-introduced-here) · [Open questions](#open-questions) · [Sources](#sources)

---

## Scope

This document is the build-and-architecture contract every other design document stands on. An engineer (or AI session) implementing M0 follows [M0 scaffold checklist](#m0-scaffold-checklist) top to bottom; later milestones come back here for conventions, the manifest and the dependency rules.

**Owned here** (other documents link, never restate):

| Topic | Section |
|---|---|
| Every library and tool version; `gradle/libs.versions.toml`; `settings.gradle.kts`; `gradle.properties` | [Toolchain and versions](#toolchain-and-versions) |
| `build-logic` convention plugins (`neutrodyne.*`) | [Toolchain and versions](#convention-plugins) |
| Module creation, packages, per-module plugins and dependencies | [Module layout](#module-layout) |
| Module-graph assertion rules and the licence bans (`verifyDependencyPolicy`, Licensee, `checkPythonLicences`) | [Dependency rules](#dependency-rules) |
| UDF/MVVM rules, `UiState`, events, paging in ViewModels, use-case rule, `Outcome`, `suspendRunCatching`, `Clock`, logging and redaction, coroutine/threading model, processes and app start-up, DataStore files and typed setting keys | [Architecture patterns](#architecture-patterns) |
| Hilt components, scopes, YouTube bindings, test overrides | [Dependency injection](#dependency-injection) |
| Nav3 mechanics: installers, per-tab back stacks, decorators, scene strategies, intent routing | [Navigation](#navigation) (behaviour: [08 Navigation](08-ui-ux.md#navigation)) |
| Build types (published `debug`, `benchmark`), the `neutrodyneDebug` signing config, the dev-tools switch, ABI splits, `BuildConfig`, the no-engine switch, Chaquopy build integration | [Build variants and ABIs](#build-variants-and-abis) (YouTube capability semantics: [04 Capability matrix](04-youtube.md#capability-matrix); engine design: [04 YouTube engine](04-youtube.md#youtube-engine)) |
| Shared `OkHttpClient`, derived clients, interceptors, network security config, network error taxonomy | [Networking baseline](#networking-baseline) |
| Android 10–17 rules → mechanism → owner | [Platform compliance](#platform-compliance) |
| Merged manifest: every permission and component with its declaring module | [Manifest and permissions](#manifest-and-permissions) |
| Licensee allow-list, SPDX rule, Python component lockfile and licence check, APK content scan input, AboutLibraries, packaging, About licence statement, contribution rule | [Licensing and dependency policy](#licensing-and-dependency-policy) |

**Not covered here:** schema, SQL and Room usage conventions ([02 Conventions](02-data-model.md#conventions)); feature behaviour ([03](03-feeds-and-discovery.md)–[07](07-downloads.md)); the YouTube engine's runtime design — `:ytx` lifecycle, Binder API, OkHttp bridge, engine updates ([04 YouTube engine](04-youtube.md#youtube-engine), [04 Engine updates](04-youtube.md#engine-updates)); screens, theming and the `PlayerSheet` ([08](08-ui-ux.md)); CI workflows, test infrastructure, static-analysis gates, the committed keystore's generation and its trade-offs, release assets and the update check's design ([09](09-quality-and-release.md), [09 Update check](09-quality-and-release.md#update-check) — this document only defines the Gradle-side tasks those workflows call, the signing config that uses the committed keystore, and the modules the update check's classes live in).

---

## Toolchain and versions

Serves N7, N11. Delivered in M0 (catalog grows by milestone; versions never differ from this table without amending it and [D4](../PLAN.md#3-key-decisions)).

### Version table

All verified 2026-10-04; the YouTube-engine, signature and CI-action rows on 2026-10-05 (sources in [Sources](#sources)). This is the only document that lists every version.

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
| Media | Media3 (`exoplayer`, `session`, `datasource-okhttp`, `ui-compose`, `common-ktx`, `inspector`, `test-utils`, `test-utils-robolectric`) | 1.11.1 | `@UnstableApi` opt-in module-wide only in `:playback:impl` (lint config, [Convention plugins](#convention-plugins)); `ui-compose` unused until M14 ([06 UI boundary](06-playback.md#ui-boundary)); no `media3-cast` (Chromecast not planned, [PO-6](../PLAN.md#po-6-chromecast)) |
| YouTube engine (`:youtube:ytdlp` only, [D72](../PLAN.md#3-key-decisions)) | Chaquopy Gradle plugin and runtime (`com.chaquo.python:gradle`, plugin ID `com.chaquo.python`) | 17.0.0 (latest release on Maven Central, 2025-11-30); S7 may switch to a newer release or a self-built master 17.1.0 | MIT. Its published documentation names AGP 7.3–9.2 and allows the plugin in one module per app; master carries the AGP 9.x updates up to 9.4.1 and target API 37 → [S7](#s7-chaquopy-under-agp-941) |
| | CPython runtime (Chaquopy `com.chaquo.python:target`) | 3.14.0-0 (fallback 3.13.9-0) | PSF-2.0; Python ≥ 3.12 exists only for `arm64-v8a` and `x86_64` ([D77](../PLAN.md#3-key-decisions)). Build-time `.pyc` compilation needs a build-host Python of the same minor version (`buildPython`): 3.14 in CI via `actions/setup-python`, and in 09's release container `python:3.14-slim-trixie` ([09 release.yml](09-quality-and-release.md#releaseyml); Debian trixie's own `python3` is 3.13). Bundled native libraries per [Python and native components](#python-and-native-components) |
| | yt-dlp (official zipimport release asset `yt-dlp`, incl. yt-dlp-ejs 0.8.0) | 2026.08.19 | Unlicense; vendored under `youtube/ytdlp/engine/`, not Gradle-managed and not a Renovate dependency: bumped only to canary-approved versions by `scripts/engine/bump-ytdlp.sh` ([04 Engine updates](04-youtube.md#engine-updates)); no optional extras (never `mutagen`) |
| | Tink (`com.google.crypto.tink:tink-android`) | 1.23.0 | Apache-2.0; M9b: Ed25519 verification of the engine manifest below API 33 (`java.security.Signature` supports Ed25519 from API 33) |
| | quickjs-kt (`io.github.dokar3:quickjs-kt-android`) | 1.0.15 | Apache-2.0, bundles QuickJS (MIT); M9b **only if** the JS challenge provider passes the M9a spike ([D75](../PLAN.md#3-key-decisions)), else M14 |
| | OpenPGP verification of yt-dlp's `SHA2-256SUMS.sig` | — (no library) | 04's `OpenPgpDetachedVerifier` and build-logic's `verifyBundledYtDlp` parse the v4 signature packet and verify it with the JDK's `Signature("SHA512withRSA")` against the pinned key; Bouncy Castle and PGPainless are not added |
| Quality | JUnit 4 / TestParameterInjector / Truth / Turbine / MockK | 4.13.2 / 1.24 / 1.4.5 / 1.2.1 / 1.14.11 | MockK never in `androidTest` |
| | Robolectric | 4.17 (pin `sdk=36`) | Force 4.17 and `okhttp-bom` 5.5.0 over Media3 test-utils' 4.16 / MockWebServer 4.12 |
| | Roborazzi | 1.76.0 | [D59](../PLAN.md#3-key-decisions) |
| | kxml2 | 2.3.0 | `compileOnly` + `testImplementation` in `:feeds` only |
| | androidx.test runner / ext-junit / espresso / orchestrator / uiautomator | 1.7.0 / 1.3.0 / 3.7.0 / 1.6.1 / 2.4.0 | |
| | Compose `ui-test-junit4` (v2 APIs), `ui-test-junit4-accessibility`, `ui-test-manifest` | 1.12.1 (BOM) | `ui-test-manifest` only as `testImplementation`/`androidTestImplementation`, never `debugImplementation`: its test activity must never reach a published APK ([Convention plugins](#convention-plugins)) |
| | benchmark-macro-junit4; profileinstaller | 1.5.0; 1.4.1 | M10–M11: `:benchmark` Macrobenchmarks against `:app`'s `benchmark` build type (09); the target app must include ProfileInstaller ≥ 1.3 for profile reset and shader-cache clearing ([Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)). **No `androidx.baselineprofile` plugin**: profiles are deferred because they do not apply to the debuggable published build ([D2](../PLAN.md#3-key-decisions)) |
| | LeakCanary | 2.14 | dev-tools builds only (`-Pneutrodyne.devTools=true`, [Dev-tools switch](#dev-tools-switch)) |
| Tooling | Spotless / ktlint / compose-rules | 8.10.3 / 1.8.0 / 0.6.7 | blocking |
| | detekt | 2.0.0-alpha.6 | non-blocking |
| | Licensee / module-graph-assertion | 1.14.1 / 2.9.1 | |
| | ACRA (`acra-mail`, `acra-dialog`) | 5.14.2 | [D62](../PLAN.md#3-key-decisions); never installed in `:ytx` |
| CI (09) | actions/checkout, setup-java, gradle/actions, upload-artifact, codeql-action | v7.0.1, v6.0.1, v6.4.0, v7.0.1, v4.38.2 (SHA-pinned) | android-emulator-runner v2.38.0 as GMD fallback; no release action: `release.yml` publishes with the runner's preinstalled `gh` CLI ([09 release.yml](09-quality-and-release.md#releaseyml)) |
| | actions/setup-python, actions/attest, actions/deploy-pages | v7.0.0, v4.2.2, v5.0.1 (SHA-pinned; checked 2026-10-05) | host CPython 3.14 for `.pyc` compilation and `shimTest`; provenance attestations ([D79](../PLAN.md#3-key-decisions)); engine manifest on GitHub Pages ([D76](../PLAN.md#3-key-decisions)) |

**Banned** (enforced by [`verifyDependencyPolicy`](#gradle-side-policy-tasks) and plugin guards): `org.jetbrains.kotlin.android`, `kotlin-kapt` / `org.jetbrains.kotlin.kapt`, `androidx.compose.material:material-icons-extended`, `androidx.palette:*`, `com.materialkolor:material-kolor*` (Compose artifact), OkHttp `Cache`, any `com.google.android.gms`, `com.google.firebase`, `com.google.android.play`, `com.crashlytics`, `io.sentry` artifact in any configuration (there is one build and it carries no proprietary SDK, [D62](../PLAN.md#3-key-decisions)), `androidx.security:security-crypto` (deprecated; Keystore directly), and every GPL/LGPL/AGPL component named in [D3](../PLAN.md#3-key-decisions): NewPipe Extractor (`com.github.teamnewpipe`, `com.github.TeamNewPipe`), Rhino (`org.mozilla:rhino*`), any `*youtubedl-android*` coordinate, `com.android.tools:desugar_jdk_libs*` (core-library desugaring existed only for NewPipe Extractor; `java.time` is native from API 26, and AGP's AAR-metadata check fails the M0 build if any other dependency still demands desugaring). The `com.chaquo.python` plugin is allowed only in `:youtube:ytdlp` ([common Android configuration](#common-android-configuration)).

### `gradle/libs.versions.toml`

Complete catalog. Coordinates marked `# M9b` / `# v1.x` are declared now so Renovate tracks them; nothing references them until that milestone. yt-dlp is not in the catalog (vendored file, [Python and native components](#python-and-native-components)).

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
chaquopy = "17.0.0"                 # :youtube:ytdlp only; S7 may change it (newer release, or "17.1.0" from a local build of master)
tink = "1.23.0"                     # M9b
quickjsKt = "1.0.15"                # M9b, only if the JS challenge provider ships (D75)
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
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }              # dev-tools builds only (debugImplementation behind the switch)
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-junit4-accessibility = { module = "androidx.compose.ui:ui-test-junit4-accessibility" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }    # test configurations only, never debugImplementation
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
# YouTube engine, :youtube:ytdlp only (the Chaquopy runtime comes through its plugin, not the catalog)
tink-android = { module = "com.google.crypto.tink:tink-android", version.ref = "tink" }                 # M9b
quickjs-kt-android = { module = "io.github.dokar3:quickjs-kt-android", version.ref = "quickjsKt" }      # M9b, conditional (D75)
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
androidx-profileinstaller = { module = "androidx.profileinstaller:profileinstaller", version.ref = "profileinstaller" }   # M10, Macrobenchmark target
leakcanary-android = { module = "com.squareup.leakcanary:leakcanary-android", version.ref = "leakcanary" }   # dev-tools builds only
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
chaquopy-gradlePlugin = { module = "com.chaquo.python:gradle", version.ref = "chaquopy" }   # same classloader as AGP (catalog rule 3)

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
# no androidx.baselineprofile plugin: profiles deferred while the published build is debuggable (D2)
# com.chaquo.python has no entry: it is on the build-logic classpath and applied by bare id (catalog rule 3)
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
2. Module build files apply plugins only by `alias(libs.plugins.neutrodyne-*)` or by bare `id("…")` for plugins already on the build-logic classpath; versioned `alias(...)` is used only for tooling plugins not on that classpath (`detekt`, `roborazzi`, `android-test`).
3. The Chaquopy plugin (`chaquopy-gradlePlugin`) is an `implementation` dependency of `build-logic/convention`, so it loads in the same classloader as AGP, and `:youtube:ytdlp` applies it by bare `id("com.chaquo.python")`. Unverified: that Chaquopy needs AGP's classloader (its documentation applies it with a versioned `plugins {}` entry); S7 records which form works. No other module may apply it ([common Android configuration](#common-android-configuration)). Its runtime artifacts (`com.chaquo.python:target`, runtime AARs) resolve from Maven Central, or from the local repository of S7's self-built fallback.
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
        // Only if S7 falls back to a self-built Chaquopy master: a local repository that alone serves com.chaquo.python
        // exclusiveContent { forRepository { maven(uri("third_party/chaquopy-maven")) }; filter { includeGroupByRegex("com\\.chaquo\\.python.*") } }
    }
}
rootProject.name = "Neutrodyne"
include(":app")
include(":core:model", ":core:common", ":core:domain", ":core:navigation", ":core:database", ":core:datastore",
        ":core:network", ":core:data", ":core:artwork", ":core:designsystem", ":core:ui", ":core:testing")
include(":feeds")
include(":playback:api", ":playback:impl", ":download:api", ":download:impl")
include(":youtube:api", ":youtube:impl", ":youtube:ytdlp")
// no :update:* modules: the update check lives in :core:domain, :core:model and :core:data (D13, D78)
include(":feature:feeds", ":feature:library", ":feature:groups", ":feature:podcast", ":feature:episode", ":feature:player",
        ":feature:queue", ":feature:downloads", ":feature:discover", ":feature:importexport", ":feature:settings")
// M6: include(":benchmark")   v1.x: include(":feature:widgets")
```

`:youtube:ytdlp` stays included in the emergency build without the engine; only `:app`'s dependency on it is dropped ([Emergency build without the engine](#emergency-build-without-the-engine)), so CI keeps compiling and testing it. Every repository serves immutable releases; the same repositories, minus the commented fallback, are declared for `pluginManagement` above and for `build-logic`.

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
# Version, single source of truth (scheme D63, procedure in 09; no pre-release suffixes since PO-33, S = 95):
neutrodyne.versionName=0.1.0
neutrodyne.versionCode=10095
neutrodyne.repoUrl=https://github.com/OWNER/Neutrodyne
# Approved YouTube-engine manifest on GitHub Pages (D76; 04 Engine updates):
neutrodyne.engineManifestUrl=https://OWNER.github.io/Neutrodyne/engine/ytdlp-approved.json
# false = emergency build without :youtube:ytdlp (Build variants and ABIs; risk L1):
neutrodyne.youtubeEngine=true
# Committed so every build of a tag reads the same value (empty until PO-10 names the mailbox):
neutrodyne.acraMailto=
# Read with providers.gradleProperty(...).orElse(""); supplied via -P only by release.yml to the assembleDebug run that
# builds a published release, and only after Podcast Index has granted written permission (PO-3, D26); never committed:
#   neutrodyne.podcastIndexKey, neutrodyne.podcastIndexSecret
# Read with providers.gradleProperty(...).orElse("false"); NEVER committed as true. Developers set it in
# ~/.gradle/gradle.properties or pass -P; CI sets it only in the nightly dev-tools-build job (Dev-tools switch):
#   neutrodyne.devTools=true
```

`OWNER` is replaced when PO-18 names the GitHub owner (M0 blocker only for the About link and the update and engine-manifest URLs, not for the build); `BuildInfo` derives the update-manifest URL from `neutrodyne.repoUrl` ([Build variants and ABIs](#build-variants-and-abis)). Gradle logic never reads git, never embeds timestamps (the nightly reproducibility report, [09 Reproducible builds](09-quality-and-release.md#reproducible-builds)), and never reads environment variables: the only signing input is the committed keystore ([Signing config](#signing-config), [09 Debug keystore](09-quality-and-release.md#debug-keystore)). Command-line `-P` and `~/.gradle/gradle.properties` take precedence over the project's file ([Gradle build environment](https://docs.gradle.org/current/userguide/build_environment.html)), so a developer's `neutrodyne.devTools=true` applies to every local build on that machine; the project file never sets the property, and its default is `false`.

Root `build.gradle.kts` contains only `plugins { alias(libs.plugins.neutrodyne.quality); alias(libs.plugins.detekt) apply false }`.

**Gradle dependency verification (decision):** not enabled. Google Maven, Maven Central and the Gradle Plugin Portal serve immutable releases, and full verification would make every Renovate PR fail until someone regenerates metadata locally. The one artifact outside Gradle's resolution — the vendored yt-dlp — is checked by [`verifyBundledYtDlp`](#python-and-native-components) against yt-dlp's signed checksums; a self-built Chaquopy (S7 fallback) is pinned by commit and its local repository is reviewed like source.

### Convention plugins

`build-logic/settings.gradle.kts` declares its own repositories (`dependencyResolutionManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }`, with the same `google { content { … } }` filter as the root) and reuses the root catalog (`versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }`). `build-logic/convention/build.gradle.kts` applies `kotlin-dsl` and declares as **`implementation`** (not `compileOnly`, see [S1](#s1-kgp-2420-under-agp-941)): `android-gradlePlugin`, `kotlin-gradlePlugin`, `kotlin-composeGradlePlugin`, `kotlin-serializationGradlePlugin`, `ksp-gradlePlugin`, `hilt-gradlePlugin`, `room3-gradlePlugin`, `spotless-gradlePlugin`, `licensee-gradlePlugin`, `moduleGraphAssert-gradlePlugin`, `aboutlibraries-gradlePlugin`, `chaquopy-gradlePlugin` (catalog rule 3). Plugin classes live in `build-logic/convention/src/main/kotlin/` and are registered under the canonical IDs.

| Plugin ID | Applied to | Configures |
|---|---|---|
| `neutrodyne.android.application` | `:app` | `com.android.application`; [common Android config](#common-android-configuration); `applicationId`, version from `gradle.properties`; the build types `debug` (published) and `benchmark`, AGP's default `release` variant disabled, the `neutrodyneDebug` signing config, ABI splits, the engine switch and the dev-tools switch ([Build variants and ABIs](#build-variants-and-abis)); `androidResources.generateLocaleConfig = true`; `dependenciesInfo { includeInApk = false; includeInBundle = false }` (no Google-encrypted dependency block in a GitHub APK); applies `app.cash.licensee`, `com.jraska.module.graph.assertion`, `com.mikepenz.aboutlibraries.plugin`; registers [`verifyDependencyPolicy`, `verifyManifestPermissions`](#gradle-side-policy-tasks); applies `neutrodyne.android.lint` and `neutrodyne.android.testing` |
| `neutrodyne.android.library` | every Android library | `com.android.library`; common Android config; `namespace` derived from the path; `consumerProguardFiles("consumer-rules.pro")` when present; no `buildFeatures` lines (AGP 9 already defaults `buildConfig`, `aidl`, `resValues` and `shaders` to off; only `:app` turns `buildConfig` on, and `:youtube:ytdlp`'s own build file turns `aidl` on for `IYtxEngine`); applies `neutrodyne.android.lint` and `neutrodyne.android.testing` |
| `neutrodyne.android.compose` | `:app`, `:core:designsystem`, `:core:ui`, features | `org.jetbrains.kotlin.plugin.compose`; `buildFeatures.compose = true`; `platform(compose-bom)` on `implementation`, `androidTestImplementation`, `testImplementation`; `ui-tooling-preview` (annotations only); `debugImplementation(ui-tooling)` **only when the [dev-tools switch](#dev-tools-switch) is on** (Android Studio previews need it; a library's `debugImplementation` reaches `:app`'s published `debug` runtime classpath, so the switch applies in every module); `ui-test-manifest` never as `debugImplementation` — a module whose own tests need it declares `testImplementation`/`androidTestImplementation` (Unverified: that Robolectric Compose tests find its activity that way, and how `:app`'s instrumented tests host composables without it — `createAndroidComposeRule<MainActivity>()` is the proposal; 09 decides, [Open questions](#open-questions) 14); `composeCompiler { stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf")); if (-PcomposeReports) reportsDestination/metricsDestination = build/compose }`. Adds `-opt-in=androidx.compose.material3.ExperimentalMaterial3Api` **only** when the project path is `:core:designsystem`; `Nd*` wrappers therefore must not expose experimental Material 3 types in their public signatures ([08 Theming and colour](08-ui-ux.md#theming-and-colour)) |
| `neutrodyne.android.feature` | `:feature:*` | applies `neutrodyne.android.library`, `neutrodyne.android.compose`, `neutrodyne.hilt`; adds `:core:{domain, model, common, designsystem, ui, navigation}`, `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`, `lifecycle-viewmodel-navigation3`, `hilt-lifecycle-viewmodel-compose`, `navigation3-runtime`, `adaptive-navigation3`, `paging-compose`, `kotlinx-collections-immutable`. `:*:api` modules are added explicitly per feature |
| `neutrodyne.android.testing` | applied by application/library plugins | Hook only; content owned by [09 Test infrastructure](09-quality-and-release.md#test-infrastructure) (Robolectric `sdk=36`, JDK 21, `de_DE` + `America/St_Johns`, golden switch, `okhttp-bom` and Robolectric forcing, orchestrator, GMD definitions). Adds `testImplementation(project(":core:testing"))` except in `:core:testing` itself |
| `neutrodyne.android.lint` | all modules (JVM modules via `com.android.lint`) | Gates owned by [09 Static analysis](09-quality-and-release.md#static-analysis) (`warningsAsErrors`, baseline, SARIF, `checkDependencies` in `:app`). One foundation rule: Media3's `@UnstableApi` is an AndroidX `RequiresOptIn` marker enforced by Lint (`UnsafeOptInUsageError`), not by the Kotlin compiler, so the module-wide opt-in is a lint config: when the project path is `:playback:impl`, `lint { lintConfig = file("lint.xml") }` with `<issue id="UnsafeOptInUsageError"><ignore regexp='\(markerClass = androidx\.media3\.common\.util\.UnstableApi\.class\)' /></issue>` ([UnstableApi](https://developer.android.com/reference/androidx/media3/common/util/UnstableApi)). Everywhere else an unstable Media3 call stays a lint error |
| `neutrodyne.hilt` | modules using DI | Android: `com.google.devtools.ksp` + `com.google.dagger.hilt.android`, `implementation(hilt-android)`, `ksp(hilt-compiler)`. JVM: `com.google.devtools.ksp`, `implementation(dagger)`, `ksp(dagger-compiler)` (generates `_Factory` classes for `@Inject` constructors; no Hilt modules in JVM modules). Modules with `@HiltWorker` additionally declare `implementation(androidx-hilt-work)` + `ksp(androidx-hilt-compiler)` |
| `neutrodyne.room` | `:core:database` | `androidx.room3` + KSP; `room3 { schemaDirectory("$projectDir/schemas") }` (→ `core/database/schemas/`; Unverified extension name — Room 2's is `room { }`; S2 confirms); `api(room3-runtime)`, `api(room3-paging)`, `api(paging-common)`, `implementation(sqlite-bundled)`, `ksp(room3-compiler)`, `testImplementation(room3-testing, sqlite-framework)`, `androidTestImplementation(room3-testing, sqlite-framework)` (02 runs migration tests on GMD with both drivers); `MigrationTestHelper` needs the exported JSON as test assets: the Room Gradle plugin is expected to wire that (Unverified for `androidx.room3`; S4 checks it, fallback `sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")` and the same for `test`). Room usage conventions: [02 Conventions](02-data-model.md#conventions) |
| `neutrodyne.jvm.library` | `:core:model`, `:core:common`, `:core:domain`, `:feeds`, `:*:api` | `org.jetbrains.kotlin.jvm`; no toolchain provisioning: Kotlin `jvmTarget = 17` plus `-Xjdk-release=17`, `JavaCompile.options.release = 17`; `com.android.lint`; JUnit 4 test deps; calls 09's `configureNeutrodyneTestTasks()`. Test fixtures (`java-test-fixtures` in `:core:common`; `android { testFixtures { enable = true } }` in `:core:database` and `:core:navigation`) per [09 Shared helpers](09-quality-and-release.md#shared-helpers) |
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
    pluginManager.withPlugin("com.chaquo.python") {                           // Chaquopy allows one module per app (D72)
        check(path == ":youtube:ytdlp") { "com.chaquo.python is allowed only in :youtube:ytdlp" }
    }
    afterEvaluate { check(!ext.compileOptions.isCoreLibraryDesugaringEnabled) { "core-library desugaring is not used (D3)" } }
    configurations.configureEach { resolutionStrategy { failOnDynamicVersions(); failOnChangingVersions() } }
}
// targetSdk is set explicitly in the application plugin (AGP 9 defaults it to compileSdk if unset):
//   defaultConfig.targetSdk = 37
// namespace for libraries: "ch.lkmc.neutrodyne" + path.replace(':', '.')   (":core:model" -> "ch.lkmc.neutrodyne.core.model")
```

`compose-stability.conf` (repo root) lists `ch.lkmc.neutrodyne.core.model.**`, `kotlinx.collections.immutable.*`, `kotlin.time.Duration`. It is valid only because `:core:model` types are deeply immutable by rule ([Architecture patterns](#model-and-state-rules)).

#### Gradle-side policy tasks

These run in `check`; CI (09) only invokes Gradle.

| Task | Project | Fails when |
|---|---|---|
| `assertModuleGraph` | `:app` (module-graph-assertion plugin) | any edge violates [Dependency rules](#dependency-rules) |
| `licenseeDebug` | `:app` | a runtime dependency of the published `debug` variant has a licence that is not allowed ([allow-list](#licensee-allow-list)) |
| `verifyDependencyPolicy` | `:app` | `debugRuntimeClasspath` (published) or `benchmarkRuntimeClasspath` contains a banned artifact ([list above](#version-table)) — Google Play services, Firebase, Play Core, Crashlytics and Sentry included — or any artifact whose Licensee report SPDX is GPL, LGPL, AGPL or MPL (no exceptions, [D3](../PLAN.md#3-key-decisions)); either contains LeakCanary (`com.squareup.leakcanary:*`), `androidx.compose.ui:ui-tooling` (the `-preview` artifact is allowed) or `androidx.compose.ui:ui-test-manifest` while the [dev-tools switch](#dev-tools-switch) is off; any `*AndroidTestRuntimeClasspath` contains `io.mockk` (09: no MockK on devices) |
| `verifyManifestPermissions` | `:app` | the merged manifest of the published `debug` variant (`SingleArtifact.MERGED_MANIFEST`) declares a `uses-permission` not listed in `app/policy/permissions.txt` or lacks one listed there; carries `android:testOnly`; or declares an activity from a test or tooling artifact (ui-test-manifest's `ComponentActivity`, `ui-tooling`'s `PreviewActivity`). Skipped (with a log line) while the dev-tools switch is on, because a dev-tools build's `.dev` application ID and LeakCanary's components are not the published set |
| `checkSpdxHeaders` | root | any `*.kt`/`*.java`/`*.kts`/`*.py`/`*.aidl` file contains an `SPDX-License-Identifier` naming GPL, LGPL, AGPL or MPL (any version or suffix); a file listed as copied or ported in `THIRD_PARTY_NOTICES.md` lacks its original `SPDX-License-Identifier` line or credit header ([contribution rule](#copied-code-and-contributions)). Markdown and other docs are not scanned |
| `checkBannedApis` | root | scans only the `src/main/**` Kotlin sources and manifests of root-build modules plus `app/src/devTools/`, `app/src/youtubeEngine/`, `app/src/noYouTubeEngine/` and `app/src/benchmark/`, the module directory layout and the module build scripts (not `build-logic/`; tests may build their own clients): source outside `core/designsystem/` contains `ExperimentalMaterial3Api` or `ExperimentalMaterial3ExpressiveApi`; source outside `playback/impl/` contains `UnstableApi`; source outside `youtube/ytdlp/` contains `com.chaquo.python` or a manifest declares `android:process`; any source contains `PackageInstaller` (the app installs nothing, [D78](../PLAN.md#3-key-decisions)) or `BuildConfig.DEBUG` (true in every published APK; developer behaviour keys to `BuildConfig.DEV_TOOLS`, [D2](../PLAN.md#3-key-decisions)); any manifest declares `REQUEST_INSTALL_PACKAGES` or `UPDATE_PACKAGES_WITHOUT_USER_ACTION` or sets `android:debuggable`; a module has a `src/debug/` directory, or a module build script (outside `build-logic/`, whose dev-tools wiring is the only allowed place) declares `debugImplementation` or `debugApi`; any source contains `okhttp3.Cache(`, `.cache(Cache(`, `OkHttpClient()` or `OkHttpClient.Builder()` outside `core/network/`, `api.github.com`, `DexClassLoader`, `InMemoryDexClassLoader`, `System.load(`, `ProcessBuilder(`, `Runtime.getRuntime().exec`, `GlobalScope`, `override fun onBackPressed`, `collectAsState()` in `feature/`, or `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; a `Text("` string literal in `feature/**/src/main` (hard-coded UI text, [09 String conventions](09-quality-and-release.md#string-conventions)) |
| `:youtube:ytdlp:checkPythonLicences`, `:youtube:ytdlp:verifyBundledYtDlp` | `:youtube:ytdlp` | the Python and native component lockfile or the vendored yt-dlp fails its checks ([Python and native components](#python-and-native-components)) |

`checkBannedApis` is a plain text scan (fast, configuration-cache safe); false positives are fixed by rewording, never by suppression lists. Its exec and `System.load` bans keep engine code in-process ([Platform compliance](#platform-compliance) P33–P35); fallback A2 of [S7](#s7-chaquopy-under-agp-941) would need a reviewed exception for its launcher in `youtube/ytdlp/`. `verifyDependencyPolicy` and `verifyManifestPermissions` must also stay configuration-cache safe: they take their inputs as providers (`configurations.named("debugRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }` and the same for `benchmarkRuntimeClasspath`, the Licensee JSON report file of the `debug` variant, and the `debug` variant's `SingleArtifact.MERGED_MANIFEST` from `androidComponents.onVariants`), never by resolving configurations at configuration time. They check the published `debug` variant (`verifyDependencyPolicy` also `benchmark`), which is exactly what users install, so nothing that only a developer needs may be on its classpath unless the dev-tools switch put it there; the rules above replace the former "release only" exemption. Unverified: whether `SingleArtifact.MERGED_MANIFEST` already carries the `android:debuggable="true"` that AGP injects for `debug`; 09's `check-apk.sh --published` checks the packaged APK (`application-debuggable`, no `testOnly`) in any case. `:youtube:ytdlp:shimTest` (host `python -m pytest`) is deliberately not part of `check`: it needs a host CPython of the target minor version, which CI's `unit` job provides ([09 CI pipelines](09-quality-and-release.md#ci-pipelines)).

---

## Module layout

Serves N11. Delivered in M0 (stubs), content by milestone per the "Content from" column of the canonical module list ([D13](../PLAN.md#3-key-decisions)).

Every module except `:benchmark` (M6) and `:feature:widgets` (v1.x) is created in M0 as a compiling stub: `build.gradle.kts`, the package directory, one `internal` placeholder declaration and one placeholder test. Packages and AGP namespaces follow `ch.lkmc.neutrodyne` + path (`:youtube:ytdlp` → `ch.lkmc.neutrodyne.youtube.ytdlp`; its code that runs in the `:ytx` process lives in the subpackage `ch.lkmc.neutrodyne.youtube.ytdlp.ytx`).

| Module | Plugins | Project dependencies (main) | External dependencies | Content from |
|---|---|---|---|---|
| `:app` | `neutrodyne.android.application`, `.android.compose`, `.hilt` | every feature; `:core:{model, common, domain, navigation, database, datastore, network, data, artwork, designsystem, ui}`; `:playback:{api, impl}`; `:download:{api, impl}`; `:youtube:{api, impl}`; `:youtube:ytdlp` (absent with `-Pneutrodyne.youtubeEngine=false`) | appcompat, activity-compose, core-ktx, core-splashscreen, navigation3-runtime/-ui, adaptive-navigation3, material3-adaptive-navigation-suite, lifecycle-viewmodel-navigation3, hilt-lifecycle-viewmodel-compose, androidx-hilt-work, work-runtime, coil-core, coil-compose, kotlinx-coroutines-android, acra-mail, acra-dialog; profileinstaller (M10, Macrobenchmark target, 09); `debugImplementation(leakcanary)` **only** with the [dev-tools switch](#dev-tools-switch); no `coreLibraryDesugaring` | M0 |
| `:core:model` | `neutrodyne.jvm.library` | — | — | M0; M11a: the update check's state types (`UpdateCheckState`, `UpdateInfo`, `UpdateApk`, `UpdateDisabledReason`, `UpdateCheckError`, `UpdateNotice`; package `ch.lkmc.neutrodyne.core.model.update`, [09 Update check](09-quality-and-release.md#update-check)) |
| `:core:common` | `.jvm.library`, `.hilt` | — | coroutines-core, dagger (for `javax.inject`) | M0 |
| `:core:domain` | `.jvm.library`, `.hilt` | `api`: `:core:{model, common}`, `:playback:api`, `:download:api`, `:youtube:api` | `api(paging-common)`, coroutines-core | M0; M11a: `AppUpdateChecker`, `UpdateNotices` (package `ch.lkmc.neutrodyne.core.domain.update`) |
| `:core:navigation` | `.android.library`, `kotlin.plugin.serialization` | — | `api(navigation3-runtime)`, `api(platform(compose-bom))` + `api(compose-runtime)` (for `staticCompositionLocalOf`; no Compose compiler plugin), kotlinx-serialization-json | M0 |
| `:core:database` | `.android.library`, `.hilt`, `.room` | `:core:{model, common}` | (from `neutrodyne.room`), kotlinx-serialization-json | M1 |
| `:core:datastore` | `.android.library`, `.hilt` | `:core:{model, common}` | datastore-preferences | M0 |
| `:core:network` | `.android.library`, `.hilt` | `:core:{model, common}` | `api(okhttp)` via BOM, okhttp-coroutines, okio | M0 |
| `:core:data` | `.android.library`, `.hilt`, `kotlin.plugin.serialization` | `:core:{domain, model, common, database, datastore, network, artwork}`, `:feeds`, `:youtube:api` | work-runtime, androidx-hilt-work (+ compiler), okhttp-coroutines, kotlinx-serialization-json, lifecycle-process | M1 (M0: stub that binds `CredentialLookup.None`); M11a: the update check (`AppUpdateCheckerImpl`, `UpdateNoticesImpl`, `GitHubUpdateSource`, `UpdateManifestParser`, `VersionScheme`, `UpdateCheckWorker`, `UpdateNotifier`, `VerificationTimeline`, `UpdateCheckStore` and Hilt `UpdateModule`; package `ch.lkmc.neutrodyne.core.data.update`; no new dependency, [09 Update check](09-quality-and-release.md#update-check)) |
| `:core:artwork` | `.android.library`, `.hilt` | `:core:{model, common, database, network}`, `:youtube:api` | coil-core, coil-network-okhttp, work-runtime, androidx-hilt-work (+ compiler); M10: material-color-utilities | M1 (Coil components), M4 (store) |
| `:core:designsystem` | `.android.library`, `.android.compose` | `:core:model` | compose-core bundle, material3-adaptive-navigation-suite, graphics-shapes, coil-compose, kotlinx-collections-immutable; M10: material-color-utilities | M0 |
| `:core:ui` | `.android.library`, `.android.compose` | `:core:{designsystem, model, common}`, `:download:api` (08's `DownloadRequestHandler` maps its `RequestResult`, M6) | coil-compose, kotlinx-collections-immutable, reorderable (M4) | M1 |
| `:core:testing` | `.android.library`, `.hilt` | `:core:{domain, model, common}`, `:playback:api`, `:download:api`, `:youtube:api` | `api`: junit4, truth, turbine, coroutines-test, coil-test, hilt-android-testing | M0 (M11a: `FakeAppUpdateChecker`, `FakeUpdateNotices`) |
| `:feeds` | `.jvm.library`, `kotlin.plugin.serialization` | — | jsoup, kotlinx-serialization-json; `compileOnly` + `testImplementation(kxml2)` | M1 |
| `:playback:api` | `.jvm.library`, `.hilt` (JVM: `@Inject`/qualifiers only) | `:core:{model, common}` | coroutines-core | M0 |
| `:playback:impl` | `.android.library`, `.hilt` | `:playback:api`, `:download:api`, `:youtube:api`, `:core:{domain, model, common, database, datastore, network, artwork}` | media3-exoplayer, -session, -datasource-okhttp, -common-ktx, -inspector (M5), kotlinx-coroutines-guava, lifecycle-process (`PlayerConnection`), kotlinx-serialization-json (06) | M4 |
| `:download:api` | `.jvm.library`, `.hilt` (JVM) | `:core:{model, common}` | coroutines-core | M0 |
| `:download:impl` | `.android.library`, `.hilt` | `:download:api`, `:youtube:api`, `:core:{domain, model, common, database, datastore, network, artwork}` | work-runtime, androidx-hilt-work (+ compiler), okhttp-coroutines, lifecycle-process (07's `AppVisibility`) | M6 |
| `:youtube:api` | `.jvm.library`, `.hilt` (JVM) | `:core:{model, common}` | coroutines-core | M2 (`YouTubeCapabilities`, `YouTubeCapabilitiesSource`), M3 (classifier), M4 (`YouTubeStreamResolver` contract), M8 (rest), M9a (engine contracts: `YouTubeEngine`, `EngineStatus`, …); see [YouTube bindings](#youtube-bindings) |
| `:youtube:impl` | `.android.library`, `.hilt` | `:youtube:api`, `:core:{model, common, network}` | okhttp-coroutines, kotlinx-serialization-json, jsoup | M2 (`StaticYouTubeCapabilitiesSource`), M4 (`ExternalOnlyYouTubeStreamResolver`), M8 (layer A and the other external-only implementations), M9a (`AbsentYouTubeEngine`) |
| `:youtube:ytdlp` | `.android.library`, `.hilt`, `com.chaquo.python` (the only module with it, [D72](../PLAN.md#3-key-decisions)); `buildFeatures.aidl = true` | `:youtube:api`, `:core:{model, common, network, datastore}` | Chaquopy runtime (via its plugin; CPython 3.14), work-runtime, androidx-hilt-work (+ compiler), okhttp-coroutines, kotlinx-serialization-json; M9b: tink-android, quickjs-kt-android (only if the JS provider ships) | M0 stub (Chaquopy hello-world `selftest` if [S7](#s7-chaquopy-under-agp-941) is go), M9a, M9b ([04 YouTube engine](04-youtube.md#youtube-engine)) |
| `:feature:feeds` | `neutrodyne.android.feature` | + `:playback:api`, `:download:api`, `:youtube:api` | — | M1 (All), M2 |
| `:feature:library` | feature | + `:playback:api`, `:download:api`, `:youtube:api` (group-tile actions: Play, Download all) | — | M1 |
| `:feature:groups` | feature | + `:youtube:api` (`YouTubeCapabilities` in Group settings) | reorderable | M2 |
| `:feature:podcast` | feature | + `:playback:api`, `:download:api`, `:youtube:api` | — | M1 |
| `:feature:episode` | feature | + `:playback:api`, `:download:api`, `:youtube:api` | — | M1 |
| `:feature:player` | feature | + `:playback:api`, `:download:api`, `:youtube:api` (Up next tab rows, download action, `RowCaps`) | none in v1.0: no Media3 type enters a feature ([06 UI boundary](06-playback.md#ui-boundary)); M14 adds `media3-ui-compose` for `PlayerSurface` | M4 |
| `:feature:queue` | feature | + `:playback:api`, `:download:api`, `:youtube:api` (row download buttons, `RowCaps`) | reorderable | M4 |
| `:feature:downloads` | feature | + `:download:api`, `:playback:api` (`playDownloads`), `:youtube:api` (`YouTubeHealth.retryNow`) | — | M6 |
| `:feature:discover` | feature | + `:youtube:api` | — | M1 (add by URL), M7 |
| `:feature:importexport` | feature | + `:playback:api` (pause before Replace), `:download:api` (re-download offer), `:youtube:api` (`YouTubeCapabilities`) | — | M3 |
| `:feature:settings` | feature | + `:youtube:api` (`YouTubeEngine` rows in Settings › YouTube, M9a); the update check through `:core:domain` (`AppUpdateChecker`, `UpdateNotices`: Settings › Updates, Install & updates help, M11a) | aboutlibraries-core | M0 |
| `:benchmark` | `com.android.test` only (no baseline-profile plugin, [D2](../PLAN.md#3-key-decisions)) | `targetProjectPath = ":app"`; build types `debug` (system tests against the published `debug`, M6) and `benchmark` (`initWith(debug)`, Macrobenchmarks against `:app`'s `benchmark`, M10), matched by build-type name (Unverified under AGP 9.4; [09 Macrobenchmark and profiles](09-quality-and-release.md#macrobenchmark-and-profiles)) | uiautomator (M6 system tests), benchmark-macro-junit4 (M10) | M6 ([09 Out-of-process system tests](09-quality-and-release.md#out-of-process-system-tests)) |

**`api` vs `implementation`:** a module exposes a dependency as `api` only when its types appear in that module's public signatures (`:core:domain` → `:core:model`, `paging-common`; `:core:network` → `okhttp`; `:core:database` → `room3-runtime`, `room3-paging`); everything else is `implementation`. Implementation classes in impl modules are `internal`; only Hilt modules, `@AndroidEntryPoint` components and the public API are `public`.

**External-library placement:** Room only through `:core:database`; DataStore only in `:core:datastore`; OkHttp clients only constructed in `:core:network`; Media3 player/session only in `:playback:impl`; WorkManager workers only in `:core:data` (including the update check's `UpdateCheckWorker`, M11a), `:core:artwork`, `:download:impl` and `:youtube:ytdlp` (`EngineUpdateWorker`, M9b) (configuration in `:app`); Chaquopy, Python code, AIDL and anything that runs in the `:ytx` process only in `:youtube:ytdlp`; `PackageInstaller` nowhere (the app never installs anything, [D78](../PLAN.md#3-key-decisions)); images are rendered only through `:core:designsystem`/`:core:ui` composables ([08 Artwork pipeline](08-ui-ux.md#artwork-pipeline)).

---

## Dependency rules

Serves N11, N8. Delivered in M0 (enforced from the first commit). Rules 1–9 expand PLAN [5.1](../PLAN.md#51-module-graph) rules 1–5 module by module; rules 10–13 (marked ⊕) make explicit the edges the PLAN 5.1 graph implies but its rule list leaves open.

| # | Rule |
|---|---|
| 1 | `:app` → anything. Nothing → `:app`. No module has product flavors ([D2](../PLAN.md#3-key-decisions)). Only `:app` depends on `:youtube:ytdlp` (plain `implementation`, dropped by `-Pneutrodyne.youtubeEngine=false`). |
| 2 | `:feature:*` → `:core:{domain, model, common, designsystem, ui, navigation}`, `:playback:api`, `:download:api`, `:youtube:api`. Never feature → feature; never → `:core:{data, database, datastore, network, artwork}`, `*:impl`, `:youtube:ytdlp`. |
| 3 | `:core:domain` → `:core:{model, common}`, `:playback:api`, `:download:api`, `:youtube:api`, `paging-common`. |
| 4 | `:core:data` → `:core:{domain, model, common, database, datastore, network, artwork}`, `:feeds`, `:youtube:api`. |
| 5 | `:core:artwork` → `:core:{model, common, database, network}`, `:youtube:api`. |
| 6 | `:playback:impl` → `:playback:api`, `:download:api`, `:youtube:api`, `:core:{domain, model, common, database, datastore, network, artwork}`. `:download:impl` → `:download:api`, `:youtube:api`, `:core:{domain, model, common, database, datastore, network, artwork}`. `:youtube:impl` → `:youtube:api`, `:core:{model, common, network}`. `:youtube:ytdlp` → `:youtube:api`, `:core:{model, common, network, datastore}`. `:youtube:ytdlp` counts as an implementation module: no impl → `:core:data`, no impl → another impl. The update check has no module of its own ([D13](../PLAN.md#3-key-decisions)): its interfaces are in `:core:domain`, its state types in `:core:model` and its implementation in `:core:data`, so it adds no edge. YouTube Atom feeds are fetched and parsed by the generic refresh engine in `:core:data` ([03](03-feeds-and-discovery.md#refresh-scheduling)) using `:youtube:api` helpers; no YouTube module parses Atom. |
| 7 | `:core:designsystem` → `:core:model` only. `:core:ui` → `:core:{designsystem, model, common}`, `:download:api`. `:core:navigation` → nothing project-internal. |
| 8 | JVM-only: `:core:model`, `:core:common`, `:core:domain`, `:feeds`, `:*:api`. `:feeds` depends on nothing project-internal. |
| 9 | `:core:testing` → `:core:{domain, model, common}`, `:*:api`. |
| 10 ⊕ | `:playback:api`, `:download:api`, `:youtube:api` → `:core:{model, common}`. |
| 11 ⊕ | `:core:database`, `:core:datastore`, `:core:network` → `:core:{model, common}`. |
| 12 ⊕ | `:core:model` and `:core:common` → nothing project-internal. |
| 13 ⊕ | `testImplementation`/`androidTestImplementation` edges are not asserted; Android modules' tests may use `:core:testing` (an Android library); pure-JVM modules use the `:core:common` test fixtures (`TestClock`, `MainDispatcherRule`, `Goldens`, which `:core:testing` re-exports) and test fixtures of `:core:database` and `:core:navigation` serve their consumers ([09 Shared helpers](09-quality-and-release.md#shared-helpers)); `:youtube:ytdlp` needs no Gradle test fixtures: `FakeYtDlpClient` and the Python `RecordingRH`/`ReplayRH` are its own test doubles (04; how the E7 smoke test on the published and `benchmark` APKs selects `ReplayRH` in `:ytx` is 09's). The [Gradle-side tasks](#gradle-side-policy-tasks) check runtime classpaths. |

Consequences implementers must design for:

- **Interfaces in `:core:domain`, bindings in implementations** ([D12](../PLAN.md#3-key-decisions)). An impl module that needs another impl's behaviour calls the `:core:domain` or `:*:api` interface; Hilt supplies the implementation at the `:app` root.
- **Types crossing from `:feeds` to the UI** (for example the show-notes block model of [D27](../PLAN.md#3-key-decisions)): `:feeds` cannot see `:core:model`, and `:core:ui` cannot see `:feeds`, so `:core:data` maps `:feeds` output into a `:core:model` mirror (03: `ShowNotesDocument` → `ShowNotes`, [03 Show notes](03-feeds-and-discovery.md#show-notes)). Amending rule 8 to `:feeds → :core:model` is an open architect question ([Open questions](#open-questions)); until it is decided, the mirror is the rule.
- **`:core:artwork`** is the one infrastructure service implementations may share (PLAN rule 4).

```mermaid
flowchart TB
  app[":app"]
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
  yd[":youtube:ytdlp<br/>Chaquopy, process :ytx"]
  art[":core:artwork"]
  infra[":core:database<br/>:core:datastore<br/>:core:network"]
  feeds[":feeds"]
  base[":core:model<br/>:core:common"]
  app --> feat & data & pimpl & dimpl & yimpl & yd & art & infra
  feat --> ui & ds & nav & dom & apis & base
  ui --> ds & base & apis
  ds --> base
  dom --> apis & base
  apis --> base
  data --> dom & infra & art & feeds & apis
  pimpl --> dom & apis & infra & art
  dimpl --> dom & apis & infra & art
  yimpl --> apis & infra
  yd --> apis & infra
  art --> infra & apis
  infra --> base
```

(`yimpl` → `infra` means `:core:network` only; `yd` → `infra` means `:core:network` and `:core:datastore`; `yd` → `apis` and `data` → `apis` mean `:youtube:api` only; `ui` → `apis` means `:download:api` only; `:core:testing` omitted. `app` → `yd` is absent in the no-engine build. The update check adds no node: `dom`, `base` and `data` carry it.)

### Module-graph assertion configuration

Applied to `:app` by `neutrodyne.android.application` and, with the same rules, to `:core:testing` (the only main-code module not reachable from `:app`'s main configurations, so rule 9 is otherwise unchecked); rules live in `build-logic/convention/src/main/kotlin/ModuleRules.kt` so they are reviewed like code.

```kotlin
moduleGraphAssert {
    maxHeight = 5
    configurations += setOf("api", "implementation")
    allowed = arrayOf(
        ":app -> .*",
        ":feature:[a-z]+ -> :core:(domain|model|common|designsystem|ui|navigation)",
        ":feature:[a-z]+ -> :(playback|download|youtube):api",
        ":core:domain -> :core:(model|common)", ":core:domain -> :(playback|download|youtube):api",
        ":core:data -> :core:(domain|model|common|database|datastore|network|artwork)", ":core:data -> :feeds", ":core:data -> :youtube:api",
        ":core:artwork -> :core:(model|common|database|network)", ":core:artwork -> :youtube:api",
        ":playback:impl -> :(playback|download|youtube):api", ":playback:impl -> :core:(domain|model|common|database|datastore|network|artwork)",
        ":download:impl -> :(download|youtube):api", ":download:impl -> :core:(domain|model|common|database|datastore|network|artwork)",
        ":youtube:impl -> :youtube:api", ":youtube:impl -> :core:(model|common|network)",
        ":youtube:ytdlp -> :youtube:api", ":youtube:ytdlp -> :core:(model|common|network|datastore)",
        ":core:designsystem -> :core:model", ":core:ui -> :core:(designsystem|model|common)", ":core:ui -> :download:api",
        ":core:testing -> :core:(domain|model|common)", ":core:testing -> :(playback|download|youtube):api",
        ":(playback|download|youtube):api -> :core:(model|common)",
        ":core:(database|datastore|network) -> :core:(model|common)",
    )
    restricted = arrayOf(
        ":feature:.* -X> :feature:.*",
        ":(?!app).* -X> :youtube:ytdlp",
        ":.* -X> :app",
        ":(playback|download|youtube):(impl|ytdlp) -X> :core:data",
    )
}
```

Unverified: the exact 2.9.1 DSL property names (`configurations`, `maxHeight`) — confirmed in M0 step 23. With no product flavors, the graph plugin sees every main edge, including rule 1's "only `:app` → `:youtube:ytdlp`" (the `restricted` entry).

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
4. `@ApplicationScope` carries a `CoroutineExceptionHandler` that logs at ERROR and, in dev-tools builds only (`BuildConfig.DEV_TOOLS`, [Dev-tools switch](#dev-tools-switch)), rethrows on the main thread to crash fast. Published APKs are debug builds and must not crash on a logged error, so this never keys to `BuildConfig.DEBUG`.

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
4. For each path segment: keep it if it is shorter than 15 characters or contains no digit; otherwise replace with `…` + last 2 characters (`/rss/a8F3kq09ZpLm2xQ` → `/rss/…xQ`). Token-shaped segments (Supercast, Patreon) are thereby masked while `/feed/podcast` survives.
5. Keep query parameter **names**, replace every value with `…` (`?auth=abc&id=7` → `?auth=…&id=…`).
6. Drop the fragment.

Published builds (and `benchmark`) install `LogcatSink(WARN)`; dev-tools builds `LogcatSink(DEBUG)`. M11 adds a 500-entry in-memory `RingBufferLogSink` for the diagnostics screen ([09](09-quality-and-release.md#crash-reporting-and-diagnostics)). Never log response bodies, `Authorization`/`Cookie` values, credentials or API keys. No `HttpLoggingInterceptor`; dev-tools builds get `DebugHttpLogInterceptor` (method, redacted URL, status, duration) from `app/src/devTools/` through `:core:network`'s `@DevToolsInterceptor` set ([Dev-tools switch](#dev-tools-switch)), which is empty in every other build.

### Application start-up

```kotlin
// :core:common
interface AppInitializer {
    val order: Int                  // band, see below; equal orders allowed
    suspend fun run()               // idempotent; runs once per process in @ApplicationScope after Application.onCreate
}
```

**Processes** ([D73](../PLAN.md#3-key-decisions)): the app runs in up to three processes, and `NeutrodyneApplication` decides per process what to start.

| Process | Name (published) | Started by | Runs |
|---|---|---|---|
| main | `ch.lkmc.neutrodyne` | launcher, notifications, services, receivers, jobs, WorkManager | everything below: ACRA, logging, initializers, database, DataStore, WorkManager, playback, downloads, the update check |
| `:ytx` | `ch.lkmc.neutrodyne:ytx` | `YtDlpClient` binding `YtxService` (`android:process=":ytx"`; from M0 while S7 is go, answering only `ping` and `selftest` and bound only by the smoke test; the engine methods and `YtDlpClient` from M9a; 04 owns its lifecycle) | `Log` only, then the service: one CPython interpreter, `PyHttp`. **No** `AppInitializer`, no Room database, no DataStore (DataStore is single-process; the main process passes locale, User-Agent and IP family in each call), no WorkManager, no ACRA (engine crashes are recorded by the main process as engine health, [D62](../PLAN.md#3-key-decisions)). Content providers declared without `android:process` (`ArtworkProvider`, `FileProvider`, `androidx.startup`) are instantiated only in the main process |
| `:acra` | `ch.lkmc.neutrodyne:acra` | ACRA's sender service after a crash (09) | ACRA's dialog and mail sender only |

The published `debug` and the `benchmark` builds use the names above; dev-tools builds' processes carry the `ch.lkmc.neutrodyne.dev` prefix (`ch.lkmc.neutrodyne.dev:ytx`, `ch.lkmc.neutrodyne.dev:acra`). `ProcessRole` (`:app`) classifies the current process once, before Hilt is touched: `Application.getProcessName()` on API 28+, the first NUL-terminated token of `/proc/self/cmdline` on API 26–27; a name ending in `:ytx` is `YTX`, one ending in `:acra` is `ACRA` (the process `ACRA.isACRASenderServiceProcess()` reports; matching the name avoids calling ACRA before it is installed), anything else is `MAIN`.

`NeutrodyneApplication` (main process) runs every `@IntoSet AppInitializer` sequentially, sorted by `order` and then by fully qualified class name (deterministic ties), on `@Dispatcher(Default)`; each is wrapped in `suspendRunCatching` and a failure is logged without stopping the rest. Initializers receive database-backed dependencies lazily ([Dependency injection](#components-and-scopes) rule 7), because the whole set is constructed before the first one runs. Bands:

| Band | Meaning | Hard rule |
|---|---|---|
| 0–99 | platform (channels, caches) | must not touch the database, DAOs or repositories: the database opens at 100 and initializers run sequentially, so a blocking `requireDatabase()` here would wait for an open that never starts |
| 100–199 | data (open, restore, in-memory mirrors) | 100 is the database open; everything else ≥ 101 may use the database |
| 200–299 | WorkManager scheduling | enqueue only; no network |
| 300+ | warm-ups and housekeeping | nothing on the cold-start path waits for them |

Registrations known today (each owning document defines its initializer; this table is the index):

| Order | Initializer | Module | Owner | From |
|---|---|---|---|---|
| 0 | `DevToolsInitializer`, **dev-tools builds only** (compiled from `app/src/devTools/`, absent from every published APK): StrictMode VM policy and, on the main thread (`withContext(Dispatchers.Main)`), the thread policy, both with death penalties; LeakCanary configuration, with heap dumps off while the system property `neutrodyne.instrumentedTest` is set ([09 Gradle Managed Devices](09-quality-and-release.md#gradle-managed-devices)). The main thread's policy is therefore installed shortly after `Application.onCreate`, not before it (acceptable for a development aid) | `:app` (`app/src/devTools/`) | 01 | M0 |
| 10 | Static notification channels and the `grp_new_episodes` group: `playback`, `alerts` (06 `PlaybackChannels`), `downloads`, `download_errors` (07), `new_episodes`, `import_backup` (03, 05), `updates` (09, "App updates", LOW; declared by the update check in `:core:data`); posting code also calls the idempotent `ensureChannels()` | `:playback:impl`, `:download:impl`, `:core:data` | 06, 07, 03, 05, 09 | M2–M6, M11a (`updates`) |
| 20 | Mirror `privacy.crash_reports` into ACRA's `acra` SharedPreferences (`acra.enable`) and on every change ([09 Settings](09-quality-and-release.md#settings)) | `:app` | 09 | M0 |
| 100 | `DatabaseOpener.awaitOpen()` on IO: opens the database, runs migrations or recovery, fires Room `onCreate` on a fresh install ([02 Error handling and recovery](02-data-model.md#error-handling-and-recovery)) | `:core:database` | 02 | M1 |
| 110 | `FirstLaunchRestoreInitializer` (fresh DB + `files/backup/auto-snapshot.zip`) | `:core:data` | 05 | M3 |
| 120 | `CredentialStore.awaitLoaded()` (decrypt credentials into memory) | `:core:data` | 03 | M1 |
| 130 | `LocalMediaIndex` initial load | `:download:impl` | 07 | M6 |
| 140 | Per-group `new_episodes_{groupUuid}` channel sync (reads groups, so it cannot run at 10) | `:core:data` | 05 | M2 |
| 150 | `YtDlpEngine` capability load: `youtube.engine_enabled` and the start-failure count from DataStore, `noBackupFilesDir/ytdlp/active.json`; publishes `YouTubeCapabilitiesSource` and `EngineStatus` (does not start `:ytx`) | `:youtube:ytdlp` | 04 | M9a |
| 200 | Unique periodic work: `refresh-periodic` (03), `backup-auto-snapshot` (05; the same initializer starts 05's library watcher on `BackupDao.observeLibraryShape()`), `download-cleanup` (07), `db-maintenance` (02), `engine-update` (04; only with the engine bundled and its policy not Off), `app-update-check` (09, `:core:data`; only while `updates.check_enabled` is on and never in dev-tools builds; the first enqueue carries a 24 h initial delay so the first-run card comes first), all `UPDATE` | owning modules | 03, 05, 07, 02, 04, 09 | M1, M3, M6, M9b, M11 (M11a: `app-update-check`) |
| 210 | `download-reconcile` one-time `KEEP` (07); `engine-prepare` one-time `KEEP` with a 30 s initial delay, only with the engine bundled and its bundled version not yet compiled for this app version (04) | `:download:impl`, `:youtube:ytdlp` | 07, 04 | M6, M9a |
| 220 | `RefreshForegroundObserver` added to `ProcessLifecycleOwner` on the main thread ([03 Triggers](03-feeds-and-discovery.md#triggers)) | `:core:data` | 03 | M1 |
| 300 | `PlaybackPrefs` warm-up and `PlayerConnection` registration (06), `ExportFilesCleaner` (05), one `artwork-sync` request per process (08); in the emergency no-engine build only, `AbsentYouTubeEngine`'s one-time removal of leftover engine files (04, [Emergency build without the engine](#emergency-build-without-the-engine)) | owning modules; `app/src/noYouTubeEngine/` | 06, 05, 08, 04 | M3, M4, M9a |
| 310 | `:download:impl` collectors on `@ApplicationScope` and its `ProcessLifecycleOwner` observer ([07 Start-up hooks](07-downloads.md#start-up-hooks)) | `:download:impl` | 07 | M6 |

```mermaid
sequenceDiagram
  participant Z as System
  participant A as NeutrodyneApplication
  participant P as ArtworkProvider
  participant H as Hilt SingletonComponent
  participant S as ApplicationScope
  participant M as MainActivity
  Z->>A: attachBaseContext
  A->>A: ProcessRole, then install ACRA if ACRA_MAILTO is set, the role is not YTX and neutrodyne.instrumentedTest is not set
  Z->>P: ContentProvider.onCreate (main process only, no Hilt access here)
  Z->>A: onCreate
  A->>H: super.onCreate injects dagger.Lazy fields only
  alt ACRA sender process
    A-->>Z: return immediately
  else ytx process (YouTube engine)
    A->>A: Log.install, then return (no initializers, database, DataStore, WorkManager or ACRA)
  else main process
    A->>A: Log.install
    A->>S: launch initializers in order
    S->>S: 0 dev tools (dev-tools builds only), 10 channels, 100 database open, 110 to 150 data, 200 to 210 work, 300 warm-ups
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

    private lateinit var role: ProcessRole

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        role = ProcessRole.current(this)                                   // getProcessName() on 28+, /proc/self/cmdline on 26–27
        if (role != ProcessRole.YTX && BuildConfig.ACRA_MAILTO.isNotEmpty() &&
            System.getProperty("neutrodyne.instrumentedTest") == null) installAcra(this)   // config owned by 09; never in :ytx (D62)
        // the property is set only by :app's NeutrodyneTestRunner (09 Gradle Managed Devices): no ACRA in instrumented tests
    }
    override fun onCreate() {
        super.onCreate()
        if (role == ProcessRole.ACRA) return                               // :acra process: no Hilt graph use, no WorkManager, no session
        Log.install(LogcatSink(if (BuildConfig.DEV_TOOLS) LogLevel.DEBUG else LogLevel.WARN))   // never BuildConfig.DEBUG (D2)
        if (role == ProcessRole.YTX) return                                // :ytx: YtxService alone, no initializers (D73)
        appScope.get().launch { runInitializers(initializers.get()) }      // dev-tools builds: DevToolsInitializer (0) sets StrictMode
    }
    // Entry points instead of injected fields: both may be called before onCreate (provider, early WorkManager use).
    // In production Hilt creates the component lazily on first access; in Hilt tests use @EarlyEntryPoint for any entry
    // point resolved before the test component exists (https://dagger.dev/hilt/early-entry-point).
    override val workManagerConfiguration: Configuration get() = Configuration.Builder()
        .setWorkerFactory(EntryPointAccessors.fromApplication(this, WorkEntryPoint::class.java).workerFactory())
        .setMinimumLoggingLevel(if (BuildConfig.DEV_TOOLS) android.util.Log.INFO else android.util.Log.ERROR)
        .build()
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        EntryPointAccessors.fromApplication(this, ImageEntryPoint::class.java).imageLoaderFactory().create(context)
}
```

**Splash and start-up gate.** `StartupViewModel` (`:app`) exposes `StartupState(deviceSettingsLoaded, settingsLoaded, database: Pending | Ready | Recovered(cause) | Failed(reason: DatabaseOpenException.Reason))`, fed by the first `DeviceSettingsStore` emission (the persisted tab and group selection needed for the first frame), the first `settings` emission (theme, dynamic colour) and `DatabaseOpener.awaitOpen()` ([02 Error handling and recovery](02-data-model.md#error-handling-and-recovery)).

1. `installSplashScreen().setKeepOnScreenCondition { elapsed < 1_000 ms && (!state.deviceSettingsLoaded || !state.settingsLoaded || (state.database == Pending && elapsed < 400 ms)) }`: the system splash covers the normal case (opening an up-to-date database takes milliseconds), stays at most 400 ms for the database (cold-start budget N5, [09 Performance budgets](09-quality-and-release.md#performance-budgets)) and at most 1 s in total; if `device_settings` or `settings` has not emitted by then, the first frame uses the keys' defaults (waiting for `settings` avoids a light→dark theme flash) (DataStore corruption is already handled by the corruption handler, so this only guards a stalled disk).
2. The activity's content renders `StartupGate` (visuals: [08 Banners and the startup gate](08-ui-ux.md#banners-and-the-startup-gate)) **instead of the whole `NeutrodyneRoot`** (scaffold, `NavDisplay` and `PlayerSheet`) while `database == Pending`, so **no ViewModel — feature or the activity-scoped `PlayerViewModel` — and therefore no repository is constructed before the database is open** (02's `requireDatabase()` throws on the main thread before that). A long migration shows "Updating your library…" there. `Recovered(cause)` opens the gate and shows 02's recovery message once; `Failed(reason)` keeps the gate with 08's error variant for that reason; "Try again" calls `StartupViewModel.retry()`, which sets `Pending` and runs `awaitOpen()` again (02 does not cache a failed result; an activity restart would keep the retained ViewModel's failed state).
3. A [route](#intent-routing) that arrives while the gate is shown is applied to `NavigationState` immediately (it is plain saveable state); its entries compose, and their ViewModels are created, only after the gate opens.
4. Nothing in start-up waits for the network or a snapshot restore; restore progress is shown by 05's flow after the gate opens.
5. Before M1 (no database) `database` starts as `Ready`.

**Framework components constructed on the main thread before the gate.** The start-up gate protects UI only. Services, receivers and job services (`NeutrodynePlaybackService`, `ManualDownloadJobService`, `DownloadActionReceiver`, `YouTubeAlertActionReceiver`, `SnapshotNowReceiver`) can be created by the system right after `Application.onCreate` — for example the resumption card after a reboot — while the database is still opening, and Hilt injects them on the main thread, where 02's `requireDatabase()` throws. Rule: such classes, and every class they inject eagerly (for example the `MediaLibrarySession` callback), receive anything that reaches `NeutrodyneDatabase` (DAOs, repositories, `EpisodeResolver`, controllers) as `dagger.Lazy<…>` or `Provider<…>` and dereference it only inside a coroutine on IO after `DatabaseOpener.awaitOpen()`; constructors of repositories and DAOs never touch the database. Session and player creation in `Service.onCreate` therefore needs no database. 06, 07, 04 and 05 apply this rule to their components (05's `SnapshotNowReceiver` receives `BackupRepository` as `dagger.Lazy`; the update check has no framework component: its work runs in `UpdateCheckWorker`); the [Testing](#testing) start-up test enforces it. `YtxService` is the stricter case: it runs in `:ytx` and never reaches the database at all ([process model](#application-start-up)).

**Process model:** the main process, `:ytx` and ACRA's `:acra` ([Processes](#application-start-up) table above). Rules for `:ytx`: only `:youtube:ytdlp` code runs there (`YtxService` and the `…youtube.ytdlp.ytx` subpackage); `YtxService` is `@AndroidEntryPoint` but injects nothing database- or DataStore-backed (its graph reaches `@HttpClient(YOUTUBE)`, `BuildInfo` and `Clock` only, [Networking baseline](#one-client-family)); `:ytx` dies with the engine (idle stop, kill on hang, Python crash) without affecting playback in the main process ([04 YouTube engine](04-youtube.md#youtube-engine)). `YtxProcessStartTest` ([Testing](#testing)) enforces these rules. No component is `directBootAware`; media-button events before first unlock are ignored by the platform, which is acceptable.

### DataStore files and typed setting keys

Owns the conventions of [D35](../PLAN.md#3-key-decisions); each document owns its keys in its Settings table.

| File | Path | Backed up | Content |
|---|---|---|---|
| `settings` | `filesDir/datastore/settings.preferences_pb` | yes (Auto Backup include rule and backup ZIP `settings.json`) | portable preferences |
| `device_settings` | `filesDir/datastore/device_settings.preferences_pb` | never | SAF grants, volume UUIDs, prompt flags, selected tab/group, onboarding flags |

```kotlin
// :core:model (package ch.lkmc.neutrodyne.core.model.settings)
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

1. Key names match `^(appearance|feeds|discover|groups|playback|downloads|youtube|updates|backup|privacy|diagnostics|ui)\.[a-z0-9_]+$` (`updates.*`: 09's update-check keys, M11a); `ui.*` keys must be `DEVICE`. A unit test iterates `AllSettingKeys.list` and asserts the pattern, uniqueness and the `ui.*` rule.
2. Keys are never renamed or re-typed; a replacement key gets a new name and a `DataMigration` in the store copies the value once.
3. Exactly one `DataStore` per file: `@Singleton` providers in `:core:datastore` qualified `@SettingsDataStore(SettingsFile.PORTABLE|DEVICE)`, created with `PreferenceDataStoreFactory.create(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }, scope = appScope + IO, produceFile = { context.preferencesDataStoreFile(name) })`. Corruption resets that file to defaults and logs WARN. DataStore is single-process: only the main process opens either file; `:ytx` never injects a store and receives the values it needs with each call ([D73](../PLAN.md#3-key-decisions)).
4. `SettingsStore` (portable) and `DeviceSettingsStore` (device) wrap the two files; `SettingsRepository`'s implementation in `:core:data` routes by `key.file`. Impl modules may inject the stores directly.
5. Secrets never go to DataStore; they go through `CredentialStore` into the `credential` table ([03](03-feeds-and-discovery.md#feed-moves-auth-and-paging)). Fresh-install detection uses Room's `onCreate` callback, never a DataStore flag ([05 Auto Backup](05-groups-opml-backup.md#auto-backup)).

---

## Dependency injection

Serves N11. Delivered in M0 (graph skeleton), extended per milestone. Honours [D8](../PLAN.md#3-key-decisions).

### Components and scopes

| Binding | Declared in | Component | Scope |
|---|---|---|---|
| `@Dispatcher(IO)`, `@Dispatcher(Default)`, `@ApplicationScope CoroutineScope` (`SupervisorJob() + Default + handler`), `Clock` → `DeviceClock`, `BuildInfo` (from `BuildConfig`, `Build` and `Process`, [Build variants and ABIs](#build-variants-and-abis)) | `:app` `CoreModule` (JVM modules declare no Hilt modules) | Singleton | `@Singleton` |
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
| `YouTubeCapabilitiesSource`, `YouTubeEngine`, `YouTubeStreamResolver`, `YouTubeEnricher`, `YouTubeChannelSearch`, `ExtractorChannelLookup` | `:app`'s `YouTubeBindingsModule` only ([YouTube bindings](#youtube-bindings)) | Singleton | `@Singleton` |
| `:youtube:ytdlp` internals (`YtDlpClient`, `EngineStore`, …) | `:youtube:ytdlp` `YtDlpModule` (never binds `:youtube:api` interfaces) | Singleton (per process) | `@Singleton` |
| `AppUpdateChecker`, `UpdateNotices` (interfaces in `:core:domain`) | `:core:data` `UpdateModule` (M11a), bound in every build; the checker reports `Disabled(DEV_BUILD)` in dev-tools builds (09) | Singleton | `@Singleton` |
| `@DevToolsInterceptor Set<Interceptor>` | `:core:network` `NetworkModule` declares it with `@Multibinds` (empty); only `app/src/devTools/` contributes (`DebugHttpLogInterceptor`) | Singleton | unscoped elements |
| `Set<AppInitializer>` | each owning module, `@IntoSet` | Singleton | unscoped elements |
| `Set<EntryProviderInstaller>` | each feature, `@IntoSet` | `ActivityRetainedComponent` | unscoped |
| ViewModels | features, `@HiltViewModel` | `ViewModelComponent` | per Nav entry ([decorator](#viewmodels-per-entry)) |
| `MainActivity`, `ExternalImportActivity` | `:app`, `@AndroidEntryPoint` | `ActivityComponent` | — |
| `NeutrodynePlaybackService`, `ManualDownloadJobService` | `:playback:impl`, `:download:impl`, `@AndroidEntryPoint` | `ServiceComponent` | — |
| `YtxService` (runs in `:ytx`; injects nothing database- or DataStore-backed) | `:youtube:ytdlp`, `@AndroidEntryPoint` (M9a) | `ServiceComponent` | — |
| `DownloadActionReceiver`, `YouTubeAlertActionReceiver` | `:download:impl`, `:core:data` (M9a; 04's breaker notice actions), `@AndroidEntryPoint` | — | — |
| Workers | `@HiltWorker` + `HiltWorkerFactory` via `WorkEntryPoint` | — | — |
| `ArtworkProvider` | `:core:artwork`, `@EntryPoint ArtworkProviderEntryPoint`, resolved lazily in `openFile` | — | — |

Rules:

1. **Injection sites:** constructor injection everywhere except framework-instantiated classes (`@AndroidEntryPoint`) and `ContentProvider`s / `Application` getters that may run before `Application.onCreate` (use `EntryPointAccessors.fromApplication`; never touch Hilt in `ContentProvider.onCreate`).
2. **`NeutrodyneApplication` injects only `dagger.Lazy`/`Provider` fields** so the `:acra` process constructs nothing.
3. **JVM modules** use `javax.inject` annotations (`@Inject`, `@Qualifier`, `@Singleton`) and the plain Dagger processor for factories; their interfaces are bound by Hilt modules in Android modules.
4. **Cross-module objects of external types** are bound by the impl module under a qualifier annotation declared in its JVM `:*:api` module. v1.0 has none: 06 keeps every Media3 type out of features ([06 UI boundary](06-playback.md#ui-boundary)); M14 uses this pattern to hand the session `Player` to `:feature:player` for `PlayerSurface` ([06 Video](06-playback.md#video)).
5. **Empty multibindings are declared:** `CoreModule` declares `@Multibinds abstract fun initializers(): Set<AppInitializer>`, so the graph compiles before any module contributes (Hilt fails on an undeclared empty set); `NetworkModule` does the same for the `@DevToolsInterceptor` set, which stays empty outside dev-tools builds.
6. **`:youtube:api` interfaces are bound only in `:app`'s `YouTubeBindingsModule`** (one file; from M9a its engine and no-engine versions live in two source directories of which a build compiles exactly one, [YouTube bindings](#youtube-bindings)). `:youtube:ytdlp` ships `YtDlpModule` for its internal wiring only and never binds `:youtube:api` interfaces itself.
7. **Database-backed dependencies of framework components are lazy** (`dagger.Lazy`/`Provider`, dereferenced on IO after `DatabaseOpener.awaitOpen()`), per [Application start-up](#application-start-up). The same applies to **every** `AppInitializer`: `initializers.get()` constructs the whole set before initializer 100 opens the database, so an eager DAO or repository in any initializer's constructor would block on `requireDatabase()` before the open has started.
8. **Multibound function types need `@JvmSuppressWildcards`** at the injection site: `Set<@JvmSuppressWildcards EntryProviderInstaller>`; otherwise Kotlin's `Function1<? super …>` wildcard makes Dagger report a missing binding.

### YouTube bindings

Every `:youtube:api` interface is bound in exactly one Hilt module, `YouTubeBindingsModule` in `:app` ([D2](../PLAN.md#3-key-decisions), [D77](../PLAN.md#3-key-decisions)). There are no flavors: what a device can do with YouTube is a **runtime capability** read from `YouTubeCapabilitiesSource` (shape, reasons and every consumer: [04 Capability matrix](04-youtube.md#capability-matrix)). Until M9a the module lives in `app/src/main/`; from M9a it exists twice and the [engine switch](#emergency-build-without-the-engine) adds exactly one directory to `main`'s Kotlin sources:

| Source directory | Compiled when | Binds |
|---|---|---|
| `app/src/main/kotlin/ch/lkmc/neutrodyne/youtube/` | M2–M8 (before M9a) | external-only implementations, reason `NOT_YET_AVAILABLE` |
| `app/src/youtubeEngine/kotlin/ch/lkmc/neutrodyne/youtube/` | `neutrodyne.youtubeEngine=true` (default), from M9a | engine-backed implementations from `:youtube:ytdlp` |
| `app/src/noYouTubeEngine/kotlin/ch/lkmc/neutrodyne/youtube/` | `-Pneutrodyne.youtubeEngine=false`, from M9a | external-only implementations, reason `NOT_IN_THIS_APK` |

```kotlin
// app/src/youtubeEngine/kotlin/ch/lkmc/neutrodyne/youtube/YouTubeBindingsModule.kt   (default build, from M9a)
@Module @InstallIn(SingletonComponent::class)
internal abstract class YouTubeBindingsModule {
    @Binds abstract fun capabilities(impl: YtDlpEngine): YouTubeCapabilitiesSource   // :youtube:ytdlp; @Singleton, so both
    @Binds abstract fun engine(impl: YtDlpEngine): YouTubeEngine                     // bindings share one instance
    @Binds abstract fun streamResolver(impl: YtDlpStreamResolver): YouTubeStreamResolver
    @Binds abstract fun enricher(impl: YtDlpEnricher): YouTubeEnricher
    @Binds abstract fun channelSearch(impl: YtDlpChannelSearch): YouTubeChannelSearch
    @Binds abstract fun extractorLookup(impl: YtDlpChannelLookup): ExtractorChannelLookup
}

// app/src/noYouTubeEngine/kotlin/ch/lkmc/neutrodyne/youtube/YouTubeBindingsModule.kt   (-Pneutrodyne.youtubeEngine=false;
// before M9a the same file, minus the YouTubeEngine binding and with reason NOT_YET_AVAILABLE, is app/src/main's)
@Module @InstallIn(SingletonComponent::class)
internal abstract class YouTubeBindingsModule {
    @Binds abstract fun streamResolver(impl: ExternalOnlyYouTubeStreamResolver): YouTubeStreamResolver   // :youtube:impl
    @Binds abstract fun enricher(impl: NoOpYouTubeEnricher): YouTubeEnricher
    @Binds abstract fun channelSearch(impl: UnsupportedYouTubeChannelSearch): YouTubeChannelSearch
    @Binds abstract fun extractorLookup(impl: NoExtractorChannelLookup): ExtractorChannelLookup
    @Binds abstract fun engine(impl: AbsentYouTubeEngine): YouTubeEngine            // status NOT_IN_THIS_APK; prewarm is a no-op
    companion object {
        @Provides @Singleton fun capabilities(): YouTubeCapabilitiesSource =
            StaticYouTubeCapabilitiesSource(ExternalReason.NOT_IN_THIS_APK)          // all five capabilities false
    }
}
```

In the default build the engine-backed bindings serve **every** APK and state: `YtDlpEngine` computes capabilities at runtime from `BuildInfo.youTubeEngineBundled` (false on the `armeabi-v7a` APK and in any 32-bit process), `youtube.engine_enabled` and the start-failure count, and reports `NOT_IN_THIS_APK`, `DISABLED_BY_USER` or `ENGINE_FAILED` accordingly; each engine-backed implementation then answers exactly like its external-only counterpart ([04 Capability matrix](04-youtube.md#capability-matrix)). Feature code never reads the ABI, `BuildConfig` or the build switch — only capabilities. `StaticYouTubeCapabilitiesSource` and `AbsentYouTubeEngine` are the external-only `YouTubeCapabilitiesSource` and `YouTubeEngine` of `:youtube:impl` (names proposed here; 04 owns the classes).

A binding must exist from the milestone of its **first consumer**, or Hilt fails to compile:

| From | Added to `YouTubeBindingsModule` | First consumer |
|---|---|---|
| M2 | `YouTubeCapabilitiesSource` → `StaticYouTubeCapabilitiesSource(NOT_YET_AVAILABLE)` | 05 `EffectiveSettingsResolver` (auto-download capability) |
| M4 | `YouTubeStreamResolver` → `ExternalOnlyYouTubeStreamResolver` (this one class lands in `:youtube:impl` ahead of the rest of the module) | 06 `EpisodeResolver` YouTube branch |
| M8 | `YouTubeEnricher` → `NoOpYouTubeEnricher`, `YouTubeChannelSearch` → `UnsupportedYouTubeChannelSearch`, `ExtractorChannelLookup` → `NoExtractorChannelLookup` (YouTube texts are ordinary string resources in 08's modules, no binding) | 03 YouTube source adapter, 04 channel resolver, 08 YouTube rows and Downloads texts |
| M9a | the module moves into the two directories above: engine-backed bindings (`YtDlpEngine` as `YouTubeCapabilitiesSource` and `YouTubeEngine`, `YtDlpStreamResolver`, `YtDlpEnricher`, `YtDlpChannelSearch`, `YtDlpChannelLookup`) and the no-engine set with `AbsentYouTubeEngine` and reason `NOT_IN_THIS_APK` | 06 `QueueProjector` pre-warm, 08 Settings › YouTube engine rows |

The `:youtube:api` interfaces and `YouTubeCapabilitiesSource` therefore land early (M2/M4), as compiling contracts.

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
// M11a (08's signatures): InstallHelpKey(val section: String = ""), VerificationNoticeKey (data object)
//        (UpdateBlockedKey and WhatsNewKey were removed 2026-10-05 with the in-app installer, PO-31)

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
- Predictive back: `NavDisplay` handles entries; custom surfaces use `NavigationBackHandler` (Nav3, since 1.1) or `PredictiveBackHandler`; `onBackPressed` overrides are banned ([`checkBannedApis`](#gradle-side-policy-tasks)).

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
| `…/open/settings/{page}` (e.g. `…/open/settings/updates` → `SettingsPage.UPDATES`, M11a) | no | `Push(SettingsKey(SettingsPage.valueOf(page.uppercase(Locale.ROOT))))` |
| `…/open/settings/updates/release` (M11a; the `updates` notification's "Open on GitHub" action) | no | `Push(SettingsKey(SettingsPage.UPDATES, openRelease = true))`: Settings › Updates opens and, once, if its state is `Available`, opens the validated release page like its own button ([09 Update card and links](09-quality-and-release.md#update-card-and-links)) |
| `…/open/help/install` (M11a) | no | `Push(InstallHelpKey())` (all sections collapsed) |
| `…/open/diagnostics` | no | `Push(DiagnosticsKey)` |
| anything else, malformed IDs, unknown pages | — | `None` (logged at WARN, redacted) |

Target tabs above are defaults; [08 Navigation](08-ui-ux.md#navigation) owns them. Security rules: `MainActivity` is exported, so any app can send it any of these intents; therefore **routes only navigate** — they never subscribe, play, delete or write without a confirming user action on the destination screen. The one route that may also leave the app is `…/open/settings/updates/release`: it can open only the release page Settings › Updates already shows (`info.releaseUrl`, validated by 09, never a URL taken from the intent), so a foreign app that sends it gains nothing; without an `Available` state it only navigates. The router reads at most 4 KB of text, lowercases the scheme before matching (intent-filter scheme matching is case-sensitive), ignores unknown extras, and never trusts `EXTRA_REFERRER`. Notification `PendingIntent`s target `MainActivity` explicitly with `FLAG_IMMUTABLE`; our code creates no mutable `PendingIntent`. The verification notice (`VerificationNoticeKey`) is not a route: `:app`'s root pushes it when `UpdateNotices` (`:core:domain`) has `VERIFICATION_ENFORCEMENT` pending, and the first-run update-check card is 08's root content ([08 Navigation](08-ui-ux.md#navigation)); the `updates` notification opens `…/open/settings/updates`, and its "Open on GitHub" action `…/open/settings/updates/release`, so the browser hand-off and its `ActivityNotFoundException` handling happen in the app, not in a `PendingIntent`. The update card's two links ("Open release on GitHub", "Download APK for this device") leave the app as `ACTION_VIEW` intents to the browser, with `ActivityNotFoundException` handled by offering to copy the link ([09 Update check](09-quality-and-release.md#update-check)); nothing comes back into the app from them. OPML/backup files arrive at `ExternalImportActivity`, which copies the payload and then routes to `ImportKey(sessionId)` through an explicit intent ([05 Receiving files](05-groups-opml-backup.md#receiving-files)).

---

## Build variants and ABIs

Serves R3.5–R3.7, N5, N7, N8, N12. Delivered in M0 (build types `debug` and `benchmark`, the `neutrodyneDebug` signing config on the committed keystore, the dev-tools switch, ABI splits and Chaquopy per [S7](#s7-chaquopy-under-agp-941); CI builds all three published APKs from the first commit), M9a (engine switch with its two binding directories). Honours [D2](../PLAN.md#3-key-decisions), [D61](../PLAN.md#3-key-decisions), [D63](../PLAN.md#3-key-decisions), [D72](../PLAN.md#3-key-decisions), [D77](../PLAN.md#3-key-decisions), [PO-35](../PLAN.md#48-further-product-owner-decisions).

One product, **no product flavors**: GitHub Releases is the only channel ([PO-2](../PLAN.md#po-2-distribution-channels)), so nothing differs per channel, and every YouTube difference is a runtime capability ([YouTube bindings](#youtube-bindings)). By the owner's decision ("Just build debug builds", [PO-35](../PLAN.md#48-further-product-owner-decisions)) **every published APK is a debug build**: `release.yml` builds releases with `assembleDebug`. Code therefore never asks whether it runs in a debug build — `BuildConfig.DEBUG` is true in every published APK and is banned by [`checkBannedApis`](#gradle-side-policy-tasks); developer-only behaviour keys to the [dev-tools switch](#dev-tools-switch).

| Build | `applicationId` | Code shrinking | Debuggable | Signed with | Used for |
|---|---|---|---|---|---|
| `debug` — **published** | `ch.lkmc.neutrodyne`, no suffix (frozen before the first public APK, [D61](../PLAN.md#3-key-decisions)) | none | yes (AGP's default for `debug`, [build variants](https://developer.android.com/build/build-variants)) | `neutrodyneDebug`: the committed keystore, v2 + v3, v1 off ([Signing config](#signing-config)) | every GitHub release and milestone tester build, PR artifacts, unit tests (the tested build type) and the instrumented suite; ACRA on ([D62](../PLAN.md#3-key-decisions)) and the update check on ([09 Update check](09-quality-and-release.md#update-check)) |
| `benchmark` | `ch.lkmc.neutrodyne` | R8 (`optimization { enable = true }` with the keep rules below) | no; `isProfileable = true` ([`ApplicationBuildType`](https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/ApplicationBuildType)) | `neutrodyneDebug` | N5's performance budgets (Macrobenchmark refuses debuggable targets, [Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)), the nightly minified instrumented leg and the minified E7 YouTube smoke test through `:ytx` ([09 CI pipelines](09-quality-and-release.md#ci-pipelines)); keeps the R8 configuration honest for a later release build. **Never published.** It has the published app's ID and key, so on a phone it replaces the published app: a plain `adb install -r` keeps the data, but its connected test runs (Macrobenchmarks, the minified leg) clear and then uninstall the app — use an emulator or a dedicated test device ([Dev-tools switch](#dev-tools-switch)) |
| dev-tools build: `debug` with `-Pneutrodyne.devTools=true` (a switch, not a build type) | `ch.lkmc.neutrodyne.dev` (`applicationIdSuffix ".dev"`, `versionNameSuffix "-dev"`) | none | yes | `neutrodyneDebug` | local development only ([Dev-tools switch](#dev-tools-switch)): LeakCanary, StrictMode penalties, verbose logging, Compose previews; ACRA off; update check `Disabled(DEV_BUILD)`; installs beside the published app. CI builds it only in the non-blocking nightly `dev-tools-build`, never for publishing |
| `release` | — | — | — | — | AGP's default `release` variant of `:app` is **disabled** (there is no private key, PO-35); library modules keep their `release` build type as `benchmark`'s fallback (`matchingFallbacks`) |

**Trade-offs** (the owner's decision, recorded in PLAN and not restated here): the key is public, so a matching signature proves nothing about who built an APK ([D61](../PLAN.md#3-key-decisions), risk P10); a debuggable app's data is reachable over ADB (`run-as`, JDWP, `adb backup`; risk P11, [Platform compliance](#platform-compliance) P40); without R8, ahead-of-time compilation ([ART: debuggable relies on JIT](https://android.googlesource.com/platform/art/+/a0619e2%5E%21/); current ART Service forces safe mode on debuggable apps, [Dexopter.java](https://android.googlesource.com/platform/art/+/refs/heads/main/libartservice/service/java/com/android/server/art/Dexopter.java)) or baseline profiles ([Baseline Profiles overview](https://developer.android.com/topic/performance/baselineprofiles/overview)) the APKs are larger and the app is less smooth ([Compose performance](https://developer.android.com/develop/ui/compose/performance); risk T18); the framework also starts debuggable apps with CheckJNI ([ProcessList.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ProcessList.java)), so every JNI call — Chaquopy's bridge in `:ytx`, the bundled SQLite driver — is checked and a JNI misuse aborts the process ([JNI tips](https://developer.android.com/training/articles/perf-jni)), which is why N5 measures performance on `benchmark` and records the published build report-only ([09 Performance budgets](09-quality-and-release.md#performance-budgets)). Moving to a private key and a non-debuggable `release` build type later is the owner's option and costs every user one reinstall ([D61](../PLAN.md#3-key-decisions)).

Published APKs are split per ABI with AGP's ABI splits, which apply to every build type ([D77](../PLAN.md#3-key-decisions), [configure APK splits](https://developer.android.com/build/configure-apk-splits)); AGP names the outputs `app-{abi}-debug.apk` (Unverified: AGP 9 output names), and the release workflow renames them to the asset names (09):

| Release asset | ABI | YouTube engine | Budget (N5) |
|---|---|---|---|
| `neutrodyne-{v}-arm64-v8a.apk` | `arm64-v8a` | bundled | < 60 MB (PB12) |
| `neutrodyne-{v}-x86_64.apk` | `x86_64` | bundled | < 60 MB (PB12) |
| `neutrodyne-{v}-armeabi-v7a.apk` | `armeabi-v7a` | not bundled: Chaquopy publishes no 32-bit runtime for Python ≥ 3.12 ([Chaquopy docs](https://chaquo.com/chaquopy/doc/current/android.html)); YouTube runs in external mode (`NOT_IN_THIS_APK`) | < 50 MB (PB13) |

The budgets are for the published, unminified debug APKs and are Unverified estimates (≈ 10–20 MB more dex and resources than a minified build); [S7](#s7-chaquopy-under-agp-941) measures them in M0, together with the `benchmark` sizes for comparison, and a miss goes to the PO for new budgets (PLAN M0 AC1). There is **no universal APK** (`isUniversalApk = false`; as a debug build it would carry two engines and reach ≈ 60–80 MB, Unverified). All three APKs share one `versionCode` ([D63](../PLAN.md#3-key-decisions)); the update card links the entry for `Build.SUPPORTED_ABIS[0]` and Obtainium filters by ABI (09). An arm64 device that installed the `armeabi-v7a` APK runs it as a 32-bit process, so it too is in external mode; 08's "Get the 64-bit version" hint covers it.

```kotlin
// :app/build.gradle.kts (what neutrodyne.android.application sets, plus app-specific lines)
val youtubeEngine = providers.gradleProperty("neutrodyne.youtubeEngine").orElse("true").get().toBoolean()
val devTools = providers.gradleProperty("neutrodyne.devTools").orElse("false").get().toBoolean()   // Dev-tools switch
android {
    namespace = "ch.lkmc.neutrodyne"
    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()   // 09: nightly leg -PtestBuildType=benchmark
    defaultConfig {
        applicationId = "ch.lkmc.neutrodyne"
        targetSdk = 37
        versionCode = providers.gradleProperty("neutrodyne.versionCode").get().toInt()
        versionName = providers.gradleProperty("neutrodyne.versionName").get()
        fun prop(name: String) = providers.gradleProperty(name).orElse("").get()
        buildConfigField("String", "REPO_URL", "\"${prop("neutrodyne.repoUrl")}\"")
        buildConfigField("String", "ENGINE_MANIFEST_URL", "\"${prop("neutrodyne.engineManifestUrl")}\"")
        buildConfigField("boolean", "YOUTUBE_ENGINE", youtubeEngine.toString())
        buildConfigField("boolean", "DEV_TOOLS", "false")
        buildConfigField("String", "ACRA_MAILTO", "\"${prop("neutrodyne.acraMailto")}\"")
        buildConfigField("String", "PODCASTINDEX_KEY", "\"${prop("neutrodyne.podcastIndexKey")}\"")
        buildConfigField("String", "PODCASTINDEX_SECRET", "\"${prop("neutrodyne.podcastIndexSecret")}\"")
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("neutrodyneDebug") {              // committed and public on purpose (D61, Signing config)
            storeFile = rootProject.file("signing/neutrodyne-debug.keystore")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = false              // minSdk 26: v2 + v3 only
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    splits {
        abi {                                    // Unverified: exact names under AGP 9's new DSL (S7 records them)
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    // From M9a: exactly one binding directory joins main (YouTube bindings). Unverified DSL under built-in Kotlin.
    sourceSets.getByName("main").kotlin.srcDir(if (youtubeEngine) "src/youtubeEngine/kotlin" else "src/noYouTubeEngine/kotlin")
    buildTypes {
        debug {                                  // THE PUBLISHED BUILD TYPE (PO-35): debuggable (AGP default), no R8
            signingConfig = signingConfigs.getByName("neutrodyneDebug")   // never AGP's per-machine ~/.android/debug.keystore
            // reproducibility hygiene (PNG crunching, vcsInfo): 09 Reproducible builds
        }
        create("benchmark") {                    // measurement and minified tests only; never published
            initWith(getByName("debug"))         // copies debug as configured so far, i.e. before the dev-tools lines below
            isDebuggable = false
            isProfileable = true
            optimization { enable = true }       // AGP 9.3+ DSL: R8 code + resource optimization; includes the platform default
                                                 // keep rules equivalent to proguard-android-optimize.txt. No proguardFiles(...) calls:
                                                 // keep rules come from the keepRules source set (below)
            signingConfig = signingConfigs.getByName("neutrodyneDebug")
            matchingFallbacks += listOf("release")   // library modules have no benchmark build type
        }
        if (devTools) getByName("debug") {       // Dev-tools switch: local builds only, never benchmark
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            isPseudoLocalesEnabled = true        // 09 Localisation
            buildConfigField("boolean", "DEV_TOOLS", "true")
            buildConfigField("String", "ACRA_MAILTO", "\"\"")   // ACRA off (D62)
        }
    }
    if (devTools) sourceSets.getByName("debug") {   // joins the debug variant only (same Unverified DSL as above)
        kotlin.srcDir("src/devTools/kotlin")
        res.srcDir("src/devTools/res")
    }
    packaging { jniLibs { useLegacyPackaging = false } }   // S7 decides: true compresses the .so files (≈ 6 MB less per
                                                          // 64-bit APK) and is required by fallback A2's exec'd launcher
}
androidComponents {                              // no release variant until the owner decides on a private key (PO-35)
    beforeVariants { variant -> if (variant.buildType == "release") variant.enable = false }
}
dependencies {
    if (youtubeEngine) implementation(project(":youtube:ytdlp"))
    if (devTools) debugImplementation(libs.leakcanary.android)   // added by neutrodyne.android.application: build-logic is the only
                                                                 // place allowed to declare debugImplementation (checkBannedApis)
}
```

Disabling a variant through `beforeVariants` and `enable = false` is AGP's documented filter ([build variants: filter variants](https://developer.android.com/build/build-variants#filter-variants)); `initWith`, `matchingFallbacks` and a signing config per build type are documented there too, and the `benchmark` shape follows the [Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview) except that it starts from `debug` (whose signing config it shares; `release` is a disabled variant here) and turns debuggability off itself. Unverified: that the exact DSL above compiles unchanged under AGP 9.4.1 (M0 step 16 records deviations).

```kotlin
// youtube/ytdlp/build.gradle.kts (M0 stub with S7's outcome; content: 04)
plugins { alias(libs.plugins.neutrodyne.android.library); alias(libs.plugins.neutrodyne.hilt); id("com.chaquo.python") }
android {
    buildFeatures { aidl = true }                                          // IYtxEngine, IYtxCallback (04 Binder API)
    defaultConfig { ndk { abiFilters += setOf("arm64-v8a", "x86_64") } }  // Python ≥ 3.12 is 64-bit only. Unverified under
}                                                                          // the app's ABI splits: S7 measures what each split gets
chaquopy {
    defaultConfig {
        version = "3.14"                 // fallback "3.13" (S7)
        // buildPython("python3.14")     // only if auto-detection fails; must match the app's minor version
        pip { }                          // empty: no pip packages in v1 (no yt-dlp extras, so never mutagen); a package needs a lockfile entry
    }
}
// Python sources: src/main/python/neutrodyne_ytx/ (shim, Unlicense, .pyc compiled at build time).
// Vendored yt-dlp: engine/yt-dlp + engine/bundled.json packaged as assets; unpacking and on-device .pyc: 04.
```

- **`BuildConfig` never contains timestamps or git data** (the report-only nightly reproducibility check, [09 Reproducible builds](09-quality-and-release.md#reproducible-builds)). Secrets are empty strings unless supplied by `-P`; PO-3 default B means `PODCASTINDEX_*` stay empty in every build until Podcast Index grants written permission, after which `release.yml` passes them only to the `assembleDebug` run that builds a published release ([D26](../PLAN.md#3-key-decisions)); PR, nightly, `benchmark` and dev-tools builds never carry them.
- **`BuildInfo`** (`:core:model`) is how non-`:app` modules read build facts without seeing `BuildConfig`: `versionName`, `versionCode`, `devTools` (`BuildConfig.DEV_TOOLS`), `repoUrl`, `apkAbi` (runtime: the first entry of `Build.SUPPORTED_64_BIT_ABIS` if `Process.is64Bit()`, else of `Build.SUPPORTED_32_BIT_ABIS` — the ABI of the installed APK), `youTubeEngineBundled` (`BuildConfig.YOUTUBE_ENGINE && Process.is64Bit()`), `updateManifestUrl` (`$repoUrl/releases/latest/download/neutrodyne-update.json`), `engineManifestUrl`, `shippedLocales` (generated from `app/policy/locales.txt` for 08's language picker, [09 Shipped locales and per-app language](09-quality-and-release.md#shipped-locales-and-per-app-language)), `podcastIndexKey`, `podcastIndexSecret`. Its `toString()` omits the two secrets. There is no `isDebug` (every published APK is a debug build; developer behaviour reads `devTools`), no `releasesAtomUrl` (no beta channel, [PO-33](../PLAN.md#48-further-product-owner-decisions)), no `distribution` field and no per-build licence statement ([About statements](#about-statements)).
- **Source sets:** `main`; from M9a `app/src/youtubeEngine/kotlin` or `app/src/noYouTubeEngine/kotlin` joins `main` ([YouTube bindings](#youtube-bindings)); `app/src/devTools/kotlin` and `app/src/devTools/res` join the `debug` source set only with the dev-tools switch; `app/src/benchmark/` is `benchmark`'s own build-type source set (09's `BenchmarkSeedReceiver`, M10; never in `debug`). No module has a `src/debug/` directory ([`checkBannedApis`](#gradle-side-policy-tasks)). Feature code never branches on the build; it reads `YouTubeCapabilitiesSource` or `BuildInfo`.
- **Keep rules** (AGP 9.3+ `keepRules` source set, files ending in `.keep`, [shrink-code](https://developer.android.com/build/shrink-code)) apply to `benchmark` only — the published `debug` APKs are not minified — and are maintained so that a later non-debuggable release build type ([D61](../PLAN.md#3-key-decisions)) starts from tested rules. `app/src/main/keepRules/app.keep` holds `-dontobfuscate` (allowed only in the app; AGP 9 forbids global options in library consumer rules) and app-wide keeps; kotlinx.coroutines keeps for byte-identical rebuilds are added there only if 09's report-only `repro` job shows they matter. Library modules ship consumer rules as `consumer-rules.pro` via `consumerProguardFiles` (applied by `neutrodyne.android.library` when the file exists). `youtube/ytdlp/consumer-rules.pro` keeps what Python reaches through Chaquopy's Java interop, which R8 cannot see: `-keep class ch.lkmc.neutrodyne.youtube.ytdlp.ytx.PyHttp { public <init>(...); public *; }` and its request/response holder classes, the same for `…ytx.QuickJsEngine` when the JS provider ships, and `-keep class ch.lkmc.neutrodyne.youtube.ytdlp.YtxTestHooks { *; }` (04's test hook, which 09's E7 smoke test sets on the published and the minified `benchmark` APK; it holds only a nullable directory and stays inert until an instrumentation test sets it); Unverified: whether Chaquopy's runtime AAR brings its own consumer rules (S7's minified `benchmark` build runs `selftest` to find out). With `android.r8.strictFullModeForKeepRules=true`, `-keep class A` no longer keeps constructors: write `-keep class A { <init>(...); }` explicitly.
- **Tests:** unit tests run on `debug` only (AGP 9 creates unit tests only for the tested build type); the instrumented suite runs on the published `debug` APKs, and the nightly benchmark leg runs it minified on `benchmark` (`-PtestBuildType=benchmark`), together with the E7 YouTube smoke test through `:ytx` (09).

### Signing config

Every build of `:app` — the published `debug` APKs, `benchmark`, dev-tools builds, on CI and on every developer machine — is signed by the `neutrodyneDebug` signing config with the keystore committed at `signing/neutrodyne-debug.keystore` (PKCS12, alias `androiddebugkey`, store and key password `android`; [D61](../PLAN.md#3-key-decisions), [09 Debug keystore](09-quality-and-release.md#debug-keystore)).

- **One certificate everywhere.** A build from any machine installs over any other with `adb install -r` and keeps the app's data (PLAN M0 AC8). AGP's default debug signing would use a generic, per-machine debug keystore ([build variants](https://developer.android.com/build/build-variants)), so every machine would be a different signer and updates between them would fail; `debug` therefore always names `neutrodyneDebug`.
- **Schemes:** APK Signature Scheme v2 + v3, v1 off (minSdk 26).
- **No secret, no environment variable, no unsigned build.** Gradle reads no signing input except the committed file; the nightly `repro` job's rebuilds are signed with it too ([09 Reproducible builds](09-quality-and-release.md#reproducible-builds)). The former `NEUTRODYNE_KEYSTORE*` variables and secrets no longer exist.
- **Expected certificate.** `scripts/ci/debug-cert-sha256.sh` (09 owns it) computes the certificate SHA-256 from the committed keystore; `release.yml` compares every APK's signer with it.
- **Repository files** (M0, [M0 scaffold checklist](#m0-scaffold-checklist) step 1): the keystore, generated once with the `keytool` command in [09 Debug keystore](09-quality-and-release.md#debug-keystore); `signing/README.md` (public on purpose; download Neutrodyne only from the GitHub releases page; forks must change the application ID or the key); `.gitattributes` `signing/*.keystore binary`; `.gitignore`'s `*.jks` rule does not match the file. Unverified: whether GitHub push protection flags a committed PKCS12 file; if it does, the push is completed with the documented reason.
- **Costs** are the owner's trade-offs in PLAN ([D61](../PLAN.md#3-key-decisions), risks P10, P11, T18): Android calls a debug certificate "insecure by design" ([app signing](https://developer.android.com/studio/publish/app-signing)), and ours is public, so the key proves nothing, anyone can sign an APK that installs over Neutrodyne, and a later switch to a private key means one reinstall for every user. The YouTube engine manifest's Ed25519 key is unrelated: it is a secret of the `engine-approval` environment, not an APK key ([04 Engine updates](04-youtube.md#engine-updates)).

### Dev-tools switch

The Gradle property `neutrodyne.devTools` (default `false`, never committed as `true`; developers set it in `~/.gradle/gradle.properties` or pass `-Pneutrodyne.devTools=true`) turns a local `debug` build into a **dev-tools build** ([D2](../PLAN.md#3-key-decisions)). CI sets it only in the non-blocking nightly `dev-tools-build` (`assembleDebug` and `:app:testDebugUnitTest` with the switch, so the tooling keeps compiling); `release.yml` never does ([09 CI pipelines](09-quality-and-release.md#ci-pipelines)). With the switch on, and only then:

| What | How |
|---|---|
| Application ID `ch.lkmc.neutrodyne.dev`, version name `…-dev` | `debug { applicationIdSuffix = ".dev"; versionNameSuffix = "-dev" }`; installs beside the published app (same key, different app); processes `ch.lkmc.neutrodyne.dev:ytx` and `…dev:acra` |
| `BuildConfig.DEV_TOOLS = true` → `BuildInfo.devTools` | the only build fact code may branch on for developer behaviour |
| LeakCanary | `debugImplementation(libs.leakcanary.android)`, added to `:app` by `neutrodyne.android.application` |
| `app/src/devTools/kotlin` | joins the `debug` source set: `DevToolsInitializer` ([initializer](#application-start-up) order 0: StrictMode thread and VM policies with death penalties, LeakCanary configuration), `DebugHttpLogInterceptor` (contributed to `:core:network`'s `@DevToolsInterceptor` set), debug-only screens (none planned yet) |
| `app/src/devTools/res` | `xml/network_security_config.xml`: the published file plus `<debug-overrides>` with user CAs for proxy debugging ([Network security config](#network-security-config)); app resources take precedence over library resources ([build variants](https://developer.android.com/build/build-variants)) |
| Compose `ui-tooling` (previews) | `debugImplementation` through `neutrodyne.android.compose` in every Compose module |
| Logging | `LogcatSink(DEBUG)` instead of `WARN`; WorkManager logs at `INFO` instead of `ERROR` ([Logging and redaction](#logging-and-redaction)) |
| `@ApplicationScope` exception handler | rethrows on the main thread (crash fast, [Errors](#errors)) |
| Crash reporting | `ACRA_MAILTO` forced to `""`, so ACRA is not installed ([D62](../PLAN.md#3-key-decisions)) |
| Pseudo-locales | `isPseudoLocalesEnabled` on `:app`'s `debug` ([09 Localisation](09-quality-and-release.md#localisation)) |
| Update check | `Disabled(DEV_BUILD)`; no `app-update-check` work, no first-run card, verification notice or notification ([09 Update check](09-quality-and-release.md#update-check)) |
| Policy tasks | `verifyDependencyPolicy` allows the tools above; `verifyManifestPermissions` is skipped ([Gradle-side policy tasks](#gradle-side-policy-tasks)) |
| `benchmark` | unaffected: it copies `debug` before the dev-tools lines, and the dev-tools source directories and `debugImplementation` dependencies never reach it |

Rules (PLAN 7.2, M0 AC9): no published APK contains any of this — 09's `check-apk.sh --published` rejects LeakCanary (`leakcanary`, `shark`), `ui-tooling`'s `PreviewActivity`, ui-test-manifest's activity and every class from `app/src/devTools/`, and checks that the packaged network security config has no `<debug-overrides>`; project code never reads `BuildConfig.DEBUG`; a test hook may live in `main` only if it is inert until an instrumentation test arms it (04's `YtxTestHooks`, the `:ytx` start probe of [Testing](#testing)), or if it is a shell-only trigger without intent filter, protected by `android.permission.DUMP`, that runs only production code (05's `SnapshotNowReceiver`, the one such exception, [D2](../PLAN.md#3-key-decisions)); new developer tooling always goes behind the switch.

**Local device runs never touch a real Neutrodyne.** Without the former `.debug` suffix (PO-35), `debug` and `benchmark` have the published app's ID and key. `connectedDebugAndroidTest` without the switch, `connectedBenchmarkAndroidTest` and `:benchmark`'s connected tasks therefore install over the Neutrodyne on the attached phone, and then 09's orchestrator `clearPackageData` runs `pm clear` after each test ([AndroidX Test runner](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner)) and the connected test task uninstalls the app after the run ([Gradle forum](https://discuss.gradle.org/t/how-can-i-run-espresso-tests-without-uninstalling-apk-after/15492); Unverified for AGP 9.4) — the library, downloads and settings are gone. Rule: local instrumented runs and Android Studio's Run on a physical phone use a dev-tools build (`ch.lkmc.neutrodyne.dev`, installed beside the published app, so its tests target `.dev`), or an emulator or a dedicated test device; `benchmark` runs and the published-build checks only on an emulator or a device whose Neutrodyne holds nothing worth keeping (back it up first, Settings › Backup). No runtime guard is attempted: `pm clear` and the uninstall happen outside the app. `CONTRIBUTING.md`'s testing section (09) and the release checklist's reference-device items state the rule.

### Emergency build without the engine

Risk L1: if a legal demand forces YouTube extraction out of the app, a release without the engine must ship the same day ([04 Licensing and legal](04-youtube.md#licensing-and-legal)). It is a Gradle switch, not a flavor and not a patch:

- `./gradlew assembleDebug -Pneutrodyne.youtubeEngine=false` (the published build type, signed with `neutrodyneDebug` like every other release; for a tagged release, the release branch commits `neutrodyne.youtubeEngine=false` so the tag reproduces it). `:app` then drops its dependency on `:youtube:ytdlp` — no Chaquopy, CPython, yt-dlp, `YtxService` or engine assets in any APK — compiles `app/src/noYouTubeEngine/` and sets `BuildConfig.YOUTUBE_ENGINE = false`.
- Behaviour: every APK reports `ExternalReason.NOT_IN_THIS_APK`; subscriptions and Layer A stay; YouTube episodes become external episodes; queued YouTube downloads end `FAILED(UNSUPPORTED_STREAM)` and completed files keep Delete and Share ([04 Engine absent or disabled](04-youtube.md#engine-absent-or-disabled)); engine updates stop because nothing schedules `engine-update`; `app/src/noYouTubeEngine/` contributes an `AppInitializer` (order 300) through which `AbsentYouTubeEngine` deletes `noBackupFilesDir/ytdlp/` and `cacheDir/yt-dlp/` once if they exist (engine files left by an earlier APK with the engine; idempotent, on IO, in the housekeeping band; a later APK with the engine re-extracts its bundled version).
- The engine's manual Licences entries live in their own AboutLibraries config subdirectory that `:app` includes only when the switch is on (Unverified mechanism for AboutLibraries 15.x; checked in M9a; fallback: keep the entries and mark them "not included in this build").
- It cannot rot: 09's nightly `no-engine-build` job (blocking) assembles it and runs the Hilt graph test of [Testing](#testing) with the switch off; `verifyManifestPermissions` passes unchanged because `:youtube:ytdlp` declares no permission.
- The `armeabi-v7a` APK of a normal build is not this build: it contains `:youtube:ytdlp`'s Kotlin code but no Python runtime, and reaches the same reason at runtime.

---

## Networking baseline

Serves N3, N6, N7, N9. Delivered in M0 (base client), M1 (feed client, auth), M4 (media), M6 (download), M7 (API), M9a (YouTube engine in `:ytx`), M9b (engine updates), M11a (update check). Honours [D10](../PLAN.md#3-key-decisions), [D28](../PLAN.md#3-key-decisions), [D74](../PLAN.md#3-key-decisions), PO-13 default.

### One client family

All HTTP goes through one client family built in `:core:network`: a credential-free core client and the base client derived from it, from which purpose-specific clients are derived with `newBuilder()` so they share the dispatcher, connection pool and interceptors. That includes yt-dlp's HTTP: in `:ytx`, 04's `PyHttp` executes every request of yt-dlp's `NeutrodyneOkHttpRH` on the YOUTUBE client ([D74](../PLAN.md#3-key-decisions)), and the shim leaves no other yt-dlp request handler registered ([04 Networking bridge](04-youtube.md#networking-bridge)); Python's own OpenSSL never carries network traffic. **No OkHttp `Cache` anywhere** ([D10](../PLAN.md#3-key-decisions)); feeds keep their own validators ([03 Fetch pipeline](03-feeds-and-discovery.md#fetch-pipeline)), Coil its own disk cache, Media3 its `SimpleCache`, search an in-memory LRU. Constructing `OkHttpClient()` or `OkHttpClient.Builder()` outside `:core:network` is banned.

```kotlin
// :core:network
enum class HttpClientKind { FEED, API, IMAGE, MEDIA, DOWNLOAD, YOUTUBE }
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class HttpClient(val kind: HttpClientKind)

@Qualifier @Retention(AnnotationRetention.BINARY) internal annotation class CredentialFreeCore
/** Extra application interceptors; declared empty with @Multibinds, contributed only by app/src/devTools/ (Dev-tools switch). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DevToolsInterceptor

@Module @InstallIn(SingletonComponent::class)
internal object NetworkModule {
    @Provides @Singleton @CredentialFreeCore fun core(ua: UserAgentInterceptor, lanGuard: LocalNetworkGuardInterceptor,
                                                      hints: DnsFamilyHints,
                                                      @DevToolsInterceptor devInterceptors: Set<@JvmSuppressWildcards Interceptor>): OkHttpClient =
        OkHttpClient.Builder()                      // the only OkHttpClient.Builder() in the code base
            .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 8 })
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .dns(LocalNetworkGuardDns(FamilyHintDns(Dns.SYSTEM, hints)))   // outermost: LAN guard; inner: 04's IP-family hints
            .addInterceptor(lanGuard)               // first application interceptor: IP-literal and .local hosts (Dns is skipped for IP literals)
            .addInterceptor(ua)                     // application interceptor: once per call, kept on redirects
            .apply { devInterceptors.forEach(::addInterceptor) }   // @DevToolsInterceptor set: empty except in dev-tools builds
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .build()                                // followRedirects/followSslRedirects/retryOnConnectionFailure: OkHttp defaults (true)
    @Provides @Singleton fun base(@CredentialFreeCore core: OkHttpClient, auth: AuthInterceptor): OkHttpClient =
        core.newBuilder().addNetworkInterceptor(auth).build()   // network interceptor: re-evaluated on every redirect hop
    @Provides @Singleton @HttpClient(HttpClientKind.FEED) fun feed(b: OkHttpClient) = b.newBuilder().callTimeout(120, TimeUnit.SECONDS).build()
    @Provides @Singleton @HttpClient(HttpClientKind.API) fun api(b: OkHttpClient) = b.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    @Provides @Singleton @HttpClient(HttpClientKind.IMAGE) fun image(b: OkHttpClient) = b.newBuilder().readTimeout(20, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    @Provides @Singleton @HttpClient(HttpClientKind.MEDIA) fun media(b: OkHttpClient) = b.newBuilder().addInterceptor(IdentityEncodingInterceptor).build()
    @Provides @Singleton @HttpClient(HttpClientKind.DOWNLOAD) fun download(b: OkHttpClient) =
        b.newBuilder().readTimeout(60, TimeUnit.SECONDS).addInterceptor(IdentityEncodingInterceptor).build()
    // Derived from the credential-free core, so the graph that :ytx builds for PyHttp never reaches CredentialLookup or Room
    @Provides @Singleton @HttpClient(HttpClientKind.YOUTUBE) fun youtube(@CredentialFreeCore core: OkHttpClient) =
        core.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()
}

/** :ytx per-call IP-family pinning (D74): same pool and dispatcher; every host resolves to [family]'s records only
 *  (all records when it has none). Cached per family. */
fun OkHttpClient.pinnedToFamily(family: IpFamily): OkHttpClient
```

| Kind | Consumer (owner) | Timeouts (connect / read / call) | Extras |
|---|---|---|---|
| FEED | `FeedFetcher` (03) | 15 s / 30 s / 120 s | 32 MB cap and streaming SHA-256 in 03; no `Accept-Encoding` override (OkHttp gzip) |
| API | Apple, fyyd, Podcast Index search (03); small JSON calls such as oEmbed (04) and Podcasting 2.0 chapters JSON (06); the approved engine manifest, its signature and yt-dlp's `SHA2-256SUMS`/`.sig` (04 `EngineUpdateWorker`, M9b); the update check's single GET of `neutrodyne-update.json` (09 `GitHubUpdateSource` in `:core:data`, M11a; `releases/latest/download` only, never `api.github.com`; the APK itself is downloaded by the user's browser, never by the app) | 15 s / 30 s (inherited) / 8 s | the 8 s call timeout caps the whole call and equals the canonical per-provider timeout |
| IMAGE | Coil `OkHttpNetworkFetcherFactory` (08) | 15 s / 20 s / 60 s | Coil disk cache only |
| MEDIA | Media3 `OkHttpDataSource.Factory` (06) | 15 s / 30 s / none | `Accept-Encoding: identity` (byte-exact ranges, `SimpleCache` keys) |
| DOWNLOAD | `RssTransferSource`, `YouTubeTransferSource` (07); the `yt-dlp` engine file, ≤ 10 MB (04, M9b); never an app APK (the app downloads no updates, [D78](../PLAN.md#3-key-decisions)) | 15 s / 60 s / none | `Accept-Encoding: identity`; 07 asserts it in tests |
| YOUTUBE | `PyHttp` in `:ytx` (04, M9a): every request yt-dlp makes (InnerTube, watch page) | 15 s / 30 s / 60 s | derived from the credential-free core (no `AuthInterceptor`); per call `pinnedToFamily(family)` with the family the main process passes ([D74](../PLAN.md#3-key-decisions)); cancellation of a call cancels its OkHttp `Call`s (04) |

`IdentityEncodingInterceptor` is an application interceptor that sets `Accept-Encoding: identity`, so OkHttp's bridge neither adds gzip nor decompresses. Dispatcher note: `executeAsync()`, Media3's `OkHttpDataSource` and Coil all go through the dispatcher's async queue; 64 global / 8 per host leaves headroom above the per-area semaphores (feeds 6/2, downloads 3/2, YouTube 1) owned by 03 and 07.

### Interceptors

**`UserAgentInterceptor`** sets `User-Agent: Neutrodyne/<versionName> (Android <Build.VERSION.RELEASE>; +<REPO_URL>)` **only when the request has none**, so the client-specific User-Agents that yt-dlp sets per request (passed through `PyHttp` unchanged) and any 04-mandated UA survive. Non-ASCII characters are replaced with `?` (OkHttp rejects non-ASCII header values). Some hosts reject generic UAs, so the UA is never empty or the OkHttp default.

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
- `LocalNetworkGuardInterceptor` (first application interceptor) throws for a request whose host is an IP literal in those ranges or ends in `.local` (mDNS). It exists because OkHttp does not call `Dns` for IP-literal hosts (`RouteSelector` returns the parsed address directly, [source](https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RouteSelector.kt)). Known gap: a redirect hop to an IP-literal LAN host is not seen by application interceptors and ends as an ordinary connect timeout (`NetError.Timeout`).
- Below API 37 both are pass-throughs (LAN feeds keep working there). Loopback (`127.0.0.0/8`, `::1`) always passes.
- Unverified: the exact address set Android 17 treats as "local network" (the page names local addresses and `.local` without listing ranges; a LAN DNS server on port 53 is exempt); adjust the range list when device tests show otherwise.

**`DnsFamilyHints` / `FamilyHintDns`** (requested by [04 IP-family matching](04-youtube.md#ip-family-matching)): `DnsFamilyHints` (`@Singleton`, `:core:network`) holds `hostSuffix → IpFamily?` pairs in memory (`set(hostSuffix: String, family: IpFamily?)`, `null` clears). `FamilyHintDns` returns only the A (`V4`) or only the AAAA (`V6`) records for a host equal to or ending in `.` + a hinted suffix, and all records when that family has none or no hint exists. `IpFamily` is 04's enum; it is declared in `:core:model` (not `:youtube:api`) so that `:core:network` can read it under rule 11. All derived clients inherit the chain, so MEDIA and DOWNLOAD requests to `googlevideo.com` follow the hint. Both processes build the chain ([D74](../PLAN.md#3-key-decisions)): in the main process 04's resolver sets the `googlevideo.com` hint from the `ip=` of each resolved URL; in `:ytx`, whose `DnsFamilyHints` stays empty, `PyHttp` pins each call with `pinnedToFamily(family)` instead, because two concurrent calls may ask for different families. Fallback when the bridge is unavailable (A2 host): yt-dlp's own urllib handler with `source_address` forcing the family (04).

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
    <!-- No <debug-overrides>: it applies whenever android:debuggable is true, and every published APK is debuggable (PO-35) -->
</network-security-config>
```

- **No `<debug-overrides>` in the published file.** The platform applies `<debug-overrides>` whenever `android:debuggable` is `true` and ignores it otherwise ([Network security config](https://developer.android.com/privacy-and-security/security-config)); because every published APK is a debug build, such a block would make every installed copy trust user-installed CAs. Dev-tools builds get user CAs for proxy debugging from `app/src/devTools/res/xml/network_security_config.xml` (the same file plus `<debug-overrides>` with `system` and `user` certificates), which overrides `:core:network`'s file because Gradle gives library resources the lowest priority ([build variants](https://developer.android.com/build/build-variants)); 09's `check-apk.sh --published` checks that the packaged file has no `debug-overrides`.
- `android:usesCleartextTraffic` is never set (Android 17 announces its deprecation).
- **Certificate Transparency** is enforced by default for targetSdk 37. The schema does allow `<certificateTransparency enabled="false"/>` in `base-config` or a `domain-config` ([Network security config](https://developer.android.com/privacy-and-security/security-config)), but v1 uses neither: feed hosts are user-chosen, so a static per-domain list cannot help, and a global opt-out would weaken every connection. A CT or untrusted-CA failure is a per-feed error, never a silent drop ([03 Fetch pipeline](03-feeds-and-discovery.md#fetch-pipeline)). Certificates from the user store (trusted only in dev-tools builds) are not CT-checked by the platform.
- **Scheme-less user input tries `https://` first** — implemented by 03's input normalisation, not here.
- **ECH:** for targetSdk 37 the platform uses Encrypted Client Hello when the networking library integrates it ([Android 17 behaviour changes](https://developer.android.com/about/versions/17/behavior-changes-17)); OkHttp 5.5.0's ECH support is opt-in ([OkHttp changelog](https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md)) and is not enabled in v1 (revisit in v1.x).

---

## Platform compliance

Serves N2, N7. Delivered in M0 (checklist and manifest), verified in M11. Honours [D43](../PLAN.md#3-key-decisions), [D5](../PLAN.md#3-key-decisions). Every rule that binds an app with minSdk 26 / targetSdk 37, mapped to Neutrodyne's mechanism and the owning document. Row IDs P1–P40 are platform-compliance rows, cited elsewhere as "01 Pn"; they are unrelated to PLAN's risk IDs P3–P11, which every document cites as "risk Pn". Rows P36–P39 no longer apply since 2026-10-05 and keep their IDs ([PLAN 8](../PLAN.md#8-risks-and-mitigations)).

| # | Rule (platform version, scope) | Neutrodyne mechanism | Owner |
|---|---|---|---|
| P1 | No store enforces a target API any more (GitHub-only), but the target still decides behaviour: A15 refuses to install apps with `targetSdkVersion` < 24 ([A15 all apps](https://developer.android.com/about/versions/15/behavior-changes-all)) | keep targeting the newest API: targetSdk 37, raised with each platform release | 01, 09 |
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
| P17 | User-installed CAs not trusted since target 24; `<debug-overrides>` trust anchors apply whenever the app is debuggable | every published APK trusts system CAs only: `:core:network`'s file has no `<debug-overrides>`, because published APKs are debuggable (P40); dev-tools builds add user CAs through their own override ([Network security config](#network-security-config)) | 01 |
| P18 | A17 (all apps) RAM-based per-app memory limits | bounded Coil memory cache and sized decodes ([08](08-ui-ux.md#artwork-pipeline)); streaming parse, no whole-feed strings ([03](03-feeds-and-discovery.md#parser)) | 08, 03 |
| P19 | A17 (target 37) widget `RemoteViews` bitmap memory cap | v1.x widgets pass artwork as content-URI icons | 08 |
| P20 | A17 (target 37) reflection on `static final` fields blocked; lock-free `MessageQueue` | our code uses neither; library impact checked by the API 37 instrumented smoke run (M0, including Chaquopy's `selftest` in `:ytx` when S7 is go) and the YouTube smoke test through `:ytx` on the published and the minified `benchmark` APKs (M9a). Unverified: impact on Chaquopy's Java interop and on LeakCanary (dev-tools builds only) | 01, 04, 09 |
| P21 | 16 KB page sizes (devices since A15): on a 16 KB device an app whose native libraries are not 16 KB-aligned runs only in a compatibility mode with a warning (A16+) ([page sizes](https://developer.android.com/guide/practices/page-sizes)) | native code: `sqlite-bundled` ([S6](#s6-sqlite-bundled-16-kb-alignment-and-size)); in the 64-bit APKs CPython's `libpython`, `libcrypto`, `libssl`, `libsqlite3`, Chaquopy's JNI libraries and the `lib-dynload` extension modules that Chaquopy extracts from assets ([S7](#s7-chaquopy-under-agp-941)); quickjs-kt's `.so` if shipped. CI `zipalign -c -P 16` plus `llvm-readelf -l` over every `.so`, including those inside Chaquopy's asset zips (`check-apk.sh`, [09](09-quality-and-release.md#ci-pipelines)) | 01, 04, 09 |
| P22 | A13 `POST_NOTIFICATIONS` runtime permission; media-session notifications exempt; FGS start does not need it | requested contextually (first download, first new-episode opt-in), never at launch ([03](03-feeds-and-discovery.md#new-episode-notifications), [07](07-downloads.md#progress-and-notifications)) | 03, 07 |
| P23 | A13 per-app language (`localeConfig`); AppCompat backport | `generateLocaleConfig = true`, `res/resources.properties` (`unqualifiedResLocale=en-US`), `MainActivity : AppCompatActivity` with an AppCompat theme, `AppLocalesMetadataHolderService` (`autoStoreLocales`) for API ≤ 32; picker UI per [09 Localisation](09-quality-and-release.md#localisation) | 01, 09 |
| P24 | A12 `android:exported` required on components with intent filters; `PendingIntent` mutability flag required | every component declares `exported`; all `PendingIntent`s `FLAG_IMMUTABLE` with explicit components (Unverified source: not re-checked in this research) | 01 |
| P25 | A14 (target 34) implicit intents reach only exported components; mutable `PendingIntent`s with implicit intents throw; runtime receivers need an export flag | internal intents explicit; `ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED)` (Unverified source: not re-checked in this research) | 01 |
| P26 | A11 package visibility | no `queryIntentActivities`/`resolveActivity` probing: `startActivity` + catch `ActivityNotFoundException` ("Watch on YouTube" included); the same for the update card's browser links ([Intent routing](#intent-routing)); `<queries>` entries: ACRA's `mailto` only (Unverified need); never `QUERY_ALL_PACKAGES` | 01, 09 |
| P27 | A16 Safer Intents opt-in (`android:intentMatchingFlags="enforceIntentFilter"`), planned to become default | not adopted in v1 (internal explicit intents carry `neutrodyne://open/…` data that matches no filter); see [Open questions](#open-questions) | 01 |
| P28 | Auto Backup: 25 MB cap; `<include>` disables defaults; A16 QPR2 `cross-platform-transfer` element | include-only rules, no cross-platform section ([05 Auto Backup](05-groups-opml-backup.md#auto-backup), D34) | 05 |
| P29 | No direct battery-optimisation exemption request (N2); exact alarms not needed | no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`; diagnostics links to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` only (N2) | 01, 09 |
| P30 | A14/A15 background-activity-launch hardening for `PendingIntent` senders and creators; A17 adds further BAL hardening (`MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`; Unverified details) | activities start only from a user tap on a notification (sent by the system) or from a visible activity (`ExternalImportActivity` → `MainActivity`); our code never calls `PendingIntent.send()` for an activity and uses no full-screen intents; the update notification opens Settings › Updates through an ordinary tap (09) | 01, 09 |
| P31 | A17 (target 37) Encrypted Client Hello used when the networking library supports it | OkHttp's ECH stays off in v1 ([Network security config](#network-security-config)) | 01 |
| P32 | A17 (target 37) no longer relies on implicit URI read grants for `content://` extras of `ACTION_SEND` (Unverified scope) | every share intent sets `FLAG_GRANT_READ_URI_PERMISSION` and `ClipData` explicitly (05 OPML/backup share, 07 "Share file") | 05, 07 |
| P33 | A10 (target 29+) untrusted apps cannot `execve()` files in their home directory ([A10 behaviour changes](https://developer.android.com/about/versions/10/behavior-changes-10)); A12+ limits child ("phantom") processes | the engine runs in-process in `:ytx` through Chaquopy, with no child process and no exec ([D72](../PLAN.md#3-key-decisions)); `checkBannedApis` bans `ProcessBuilder`/`Runtime.exec`. Only fallback A2 would exec, and only a launcher installed into `nativeLibraryDir` from `jniLibs` (`useLegacyPackaging = true`) | 01, 04 |
| P34 | A14 (target 34) dynamically loaded DEX/JAR/APK files must be read-only ([A14 behaviour changes](https://developer.android.com/about/versions/14/behavior-changes-14)) | no DEX/JAR/APK is ever loaded at runtime (`DexClassLoader` banned); engine updates are pure Python, compiled to `.pyc` on the device and made read-only anyway ([D76](../PLAN.md#3-key-decisions), [04 Engine updates](04-youtube.md#engine-updates)) | 01, 04 |
| P35 | A17 (target 37) native files loaded with `System.load()` must be read-only, else `UnsatisfiedLinkError` ([A17 behaviour changes](https://developer.android.com/about/versions/17/behavior-changes-17)) | our code never calls `System.load`; Chaquopy loads the extension modules it extracts from assets, and its master (17.1.0) marks them read-only for target 37 — S7 verifies on the API 37 image; engine updates never download native code | 01, 04 |
| P36 | A8 (API 26) installing APKs needs the installing app's `REQUEST_INSTALL_PACKAGES` and the user's per-source "install unknown apps" grant (`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`, [Settings](https://developer.android.com/reference/android/provider/Settings)) | **Not applicable since 2026-10-05 (no in-app install, PO-31).** Neutrodyne declares no install permission ([Manifest and permissions](#manifest-and-permissions)); the grant now belongs to the browser or file manager that opens the downloaded APK, which 08's Install & updates help explains | 08, 09 |
| P37 | A12 (API 31) unattended self-update with `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | **Not applicable since 2026-10-05 (no in-app install, PO-31)**; the permission is explicitly not requested | — |
| P38 | A14 (API 34) install constraints (`GENTLE_UPDATE`) for installers of record | **Not applicable since 2026-10-05 (no in-app install, PO-31)**; nothing waits on playback or downloads for an update ([D78](../PLAN.md#3-key-decisions)) | — |
| P39 | A16 QPR2 (API 36.1) developer-verification results reported to installers through their sessions | **Not applicable since 2026-10-05 (no in-app install, PO-31)**: Android's own installer applies developer verification when the user installs the downloaded APK, and 08's help page explains what it shows ([D80](../PLAN.md#3-key-decisions), [09 Developer verification](09-quality-and-release.md#developer-verification)) | 08, 09 |
| P40 | Published APKs are debuggable (PO-35): `android:debuggable` lets an app be debugged even on user builds of Android ([application element](https://developer.android.com/guide/topics/manifest/application-element)); `run-as <package>` works only for debuggable packages ([AOSP run-as](https://android.googlesource.com/platform/system/core/+/refs/heads/main/run-as/run-as.cpp)); a JDWP debugger can run code in the app process; the framework also enables CheckJNI, JDWP and ptrace for debuggable apps ([ProcessList.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ProcessList.java)), so a JNI misuse aborts the process (CheckJNI's cost and aborts: risk T18); on Android 12+ `adb backup` excludes app data for apps targeting 31+ unless they are debuggable ([Android 12 behaviour changes](https://developer.android.com/about/versions/12/behavior-changes-12)) | Accepted by owner decision (PLAN risk P11, [D2](../PLAN.md#3-key-decisions), [D61](../PLAN.md#3-key-decisions)): all three need USB or wireless debugging on and the computer authorised; the README and the help page advise turning USB debugging off after an ADB install (08, 09); credentials stay Keystore-encrypted at rest (03), though a debugger in the process can use the key (risk P11); no secret is compiled in except the Podcast Index key after written permission ([D26](../PLAN.md#3-key-decisions)); debuggability never widens TLS trust (no `<debug-overrides>`, P17); `android:debuggable` is never written in a source manifest (AGP sets it per build type; `checkBannedApis`), and the published APK never carries `android:testOnly` (`verifyManifestPermissions`; `aapt2 dump badging` in 09's `release.yml`). Unverified: which of 05's backup rules `adb backup` applies to a debuggable app | 01, 09, 05 |

---

## Manifest and permissions

Serves N2, N7, N3, R6.2–R6.3 (build side: the update check needs no install permission and no component of its own). Delivered in M0 (app shell), extended in M1, M3, M4, M5, M6, M9a (`YtxService`); M11a adds nothing. Each module declares what its own code needs (even if a library also merges it), so removing a library never silently drops a permission. `app/policy/permissions.txt` holds the exact expected merged set; [`verifyManifestPermissions`](#gradle-side-policy-tasks) fails on any difference.

### Permissions

| Permission | Declared by | From | Why |
|---|---|---|---|
| `INTERNET` | `:core:network` | M0 | everything |
| `ACCESS_NETWORK_STATE` | `:core:network` | M0 | `NetworkMonitor`; JobScheduler/WorkManager network constraints (A14) |
| `POST_NOTIFICATIONS` | `:app` | M0 | new-episode, download, import, alert and update-available notifications; requested contextually only |
| `FOREGROUND_SERVICE` | `:playback:impl`, `:download:impl` | M4, M6 | playback FGS; WorkManager foreground worker (API 26–33 manual downloads) |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | `:playback:impl` | M4 | `NeutrodynePlaybackService` |
| `FOREGROUND_SERVICE_DATA_SYNC` | `:download:impl` | M6 | `SystemForegroundService` type `dataSync`, only for API 26–33 manual downloads started while visible (D47) |
| `WAKE_LOCK` | `:playback:impl` (+ merged by media3, WorkManager) | M4 | ExoPlayer wake/Wi-Fi locks; WorkManager |
| `RUN_USER_INITIATED_JOBS` | `:download:impl` | M6 | UIDT manual downloads (API 34+) |
| `RECEIVE_BOOT_COMPLETED` | `:download:impl` (+ merged by WorkManager) | M6 | persisted UIDT job (`setPersisted(true)`); WorkManager reschedules — never starts an FGS |
| `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | merged by `androidx.core` (declared `signature`-level and used by the app itself) | M0 | `ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED)` on API < 33 (P25). Unverified exact merged name; the first `verifyManifestPermissions` run in M0 shows it, and `permissions.txt` lists it with the published `applicationId` (`ch.lkmc.neutrodyne.…`; dev-tools builds' `.dev` name is not checked) |

The set is closed (N7). It holds **no install permission**: the update check only links to the GitHub release and the user installs with Android's installer ([D78](../PLAN.md#3-key-decisions)), so `permissions.txt` gains nothing in M11a (the two updater permissions planned before 2026-10-05 are gone). `:youtube:ytdlp` declares no permission: `:ytx` uses the app's `INTERNET`.

**Explicitly not requested** (adding any requires a PLAN amendment): `ACCESS_LOCAL_NETWORK` (v1.x, D28), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO`, `MANAGE_EXTERNAL_STORAGE` (downloads use app-specific storage, D48), `FOREGROUND_SERVICE_SPECIAL_USE`, `BLUETOOTH_CONNECT`, `QUERY_ALL_PACKAGES`, `INSTALL_PACKAGES` (privileged), `REQUEST_INSTALL_PACKAGES` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (the app installs nothing, [D78](../PLAN.md#3-key-decisions); `checkBannedApis` also rejects them), `REQUEST_DELETE_PACKAGES`, `SYSTEM_ALERT_WINDOW`, any location permission, `com.google.android.gms.permission.AD_ID`. A library that merges one of these is fixed with `tools:node="remove"` in `:app` and a comment.

### Application element and components

```xml
<!-- Merged view (sketch). Comments name the declaring module and owning document. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
  <queries>  <!-- :app (09): ACRA mail sender; Unverified need -->
    <intent><action android:name="android.intent.action.SENDTO" /><data android:scheme="mailto" /></intent>
  </queries>
  <!-- android:debuggable is never written here: AGP sets it to true for debug (published) and false for benchmark;
       android:testOnly must never appear (P40) -->
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

    <service android:name="ch.lkmc.neutrodyne.playback.impl.NeutrodynePlaybackService" android:exported="true"
             android:foregroundServiceType="mediaPlayback" />   <!-- :playback:impl (M4); intent filters: 06 -->
    <receiver android:name="androidx.media3.session.MediaButtonReceiver" android:exported="true" />   <!-- :playback:impl (M5); MEDIA_BUTTON filter: 06 -->
    <meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc" />  <!-- :playback:impl (M5); plain meta-data, no GMS code -->

    <service android:name="ch.lkmc.neutrodyne.download.impl.ManualDownloadJobService" android:exported="false"
             android:permission="android.permission.BIND_JOB_SERVICE" />                       <!-- :download:impl (M6) -->
    <service android:name="androidx.work.impl.foreground.SystemForegroundService"
             android:foregroundServiceType="dataSync" tools:node="merge" />                       <!-- :download:impl (M6) -->
    <receiver android:name="ch.lkmc.neutrodyne.download.impl.DownloadActionReceiver" android:exported="false" />  <!-- :download:impl (M6) -->
    <receiver android:name="ch.lkmc.neutrodyne.core.data.youtube.YouTubeAlertActionReceiver" android:exported="false" />  <!-- :core:data (M9a); breaker notice actions: 04 -->
    <receiver android:name="ch.lkmc.neutrodyne.SnapshotNowReceiver" android:exported="true"
              android:permission="android.permission.DUMP" />   <!-- :app (M3); no intent filter; shell-only bmgr snapshot trigger (D2 exception): 05 -->

    <service android:name="ch.lkmc.neutrodyne.youtube.ytdlp.ytx.YtxService" android:process=":ytx"
             android:exported="false" />           <!-- :youtube:ytdlp (M0 while S7 is go: ping/selftest only; engine M9a); bound only by YtDlpClient and tests; lifecycle: 04 -->

    <provider android:name="ch.lkmc.neutrodyne.core.artwork.ArtworkProvider" android:authorities="${applicationId}.artwork"
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
| `android:process` | only `YtxService` (`:ytx`); ACRA's sender declares `:acra` itself | [Application start-up](#application-start-up); `checkBannedApis` rejects any other declaration in our manifests |
| `extractNativeLibs` / `jniLibs.useLegacyPackaging` | per [S7](#s7-chaquopy-under-agp-941): default packaging keeps `.so` files stored, page-aligned and loaded from the APK; legacy packaging compresses them (≈ 6 MB less per 64-bit APK) at the cost of extraction on install, and is required if fallback A2 execs a launcher | 01, recorded in [D77](../PLAN.md#3-key-decisions) if it deviates |
| `<queries>` | ACRA `mailto` only | P26 |
| `debuggable` | not written in any source manifest; AGP injects `true` for `debug` (every published APK, [D2](../PLAN.md#3-key-decisions)) and nothing, so `false`, for `benchmark`, which `isProfileable = true` makes profileable (Unverified: that AGP expresses this as [`<profileable android:shell="true"/>`](https://developer.android.com/guide/topics/manifest/profileable-element) in the merged manifest; 09's first Macrobenchmark run confirms it) | P40, risk P11 |
| `testOnly` | never in the published merged manifest or APK (Android Studio adds it when you click Run, [application element](https://developer.android.com/guide/topics/manifest/application-element); Unverified that a command-line `assembleDebug` never sets it, which PLAN M0 AC7 checks with `aapt2 dump badging`) | `verifyManifestPermissions`; PLAN M0 AC7 |

---

## Licensing and dependency policy

Serves N8. Delivered in M0 (allow-list, Python component lockfile and its check, About/Licences, contribution rule), M9a (engine stack complete in the lockfile, Licences screen and `THIRD_PARTY_NOTICES.md`). Honours [D3](../PLAN.md#3-key-decisions), [D60](../PLAN.md#3-key-decisions), [PO-1](../PLAN.md#po-1-licensing-of-shipped-binaries) (resolved: no GPL anywhere); mitigates risks L2, L4. YouTube-specific legal analysis and the engine's licence boundary: [04 Licensing and legal](04-youtube.md#licensing-and-legal).

### Licence structure

| Artefact | Licence |
|---|---|
| The repository and every shipped artefact — the three published APKs (from any build, including the emergency build) and every YouTube-engine update | Own code (Kotlin, the Python shim `neutrodyne_ytx`, scripts, build logic) under the Unlicense (`LICENSE` at the root), plus third-party components under permissive licences only: Apache-2.0, MIT, BSD-2/3-Clause, ISC, 0BSD, PSF-2.0, Zlib, bzip2-1.0.6, public domain; MPL-2.0 only for unmodified data files (the CA certificate bundle). **No GPL, LGPL or AGPL code anywhere** — no module exception, no build exception, no corresponding-source obligation |

Three independent checks enforce it, each in CI's `static` job ([09 CI pipelines](09-quality-and-release.md#ci-pipelines)): Licensee for Gradle dependencies ([below](#licensee-allow-list)), `checkPythonLicences` for everything Gradle cannot see ([Python and native components](#python-and-native-components)), and 09's `check-apk.sh` content scan of the built APKs. `verifyDependencyPolicy`, `checkSpdxHeaders` and the [contribution rule](#copied-code-and-contributions) keep GPL code out of the source tree.

### Licensee allow-list

Applied in `:app`, run as `licenseeDebug` (part of `check`): `debug` is the published variant, and `benchmark` adds no runtime dependency of its own.

```kotlin
licensee {
    allow("Apache-2.0"); allow("MIT"); allow("BSD-2-Clause"); allow("BSD-3-Clause"); allow("Unlicense"); allow("CC0-1.0")
    // No GPL, LGPL, AGPL or MPL artifact is ever allowed, not even scoped (D3).
    // allowUrl(...) entries only for artifacts whose POM names a known licence by URL, each with because(...)
}
```

Any further permissive licence on a Gradle dependency (e.g. ISC; a weak-copyleft licence such as EPL-2.0 needs a [D3](../PLAN.md#3-key-decisions) amendment first) needs a reviewed PR adding a scoped `allowDependency(...) { because(...) }`, never a global `allow`. Tink and quickjs-kt are Apache-2.0; Unverified: whether Chaquopy's runtime reaches `debugRuntimeClasspath` as Maven artifacts with a POM licence (S7 records it) — if not, it is covered by the lockfile below.

### Python and native components

`youtube/ytdlp/python-components.lock` lists every component that ships in an APK or an engine update but is not a Gradle dependency with a POM: the CPython runtime and the libraries it bundles, Chaquopy's runtime, the vendored yt-dlp with yt-dlp-ejs, the shim, data files, and native code inside Gradle artifacts that their POM does not describe (QuickJS inside quickjs-kt). It is edited by hand in the PR that changes a component and reviewed like code; the allow-list lives in build-logic (`PythonLicencePolicy.kt`), not in the lockfile, so widening it is a visible build-logic change, never a side effect of a lockfile edit.

```toml
# youtube/ytdlp/python-components.lock  (sketch; versions as planned, Unverified until S7 reads them from the runtime)
schema = 1
chaquopy = "17.0.0"          # must equal libs.versions.chaquopy
python = "3.14.0"            # must equal the runtime Chaquopy packages for chaquopy.defaultConfig.version
pip = []                     # must equal the chaquopy { pip { } } requirements: none in v1

[[component]]
name = "CPython"
version = "3.14.0"
origin = "maven:com.chaquo.python:target:3.14.0-0"
licence = "Python-2.0"        # PSF-2.0 with the BeOpen, CNRI and CWI terms of CPython's LICENSE
kind = "runtime"             # runtime | native | python | data
aboutLibrariesId = "cpython"  # entry text: CPython's full LICENSE incl. "Licenses and Acknowledgements for Incorporated Software"

[[component]]
name = "zstd"
version = "bundled with CPython 3.14.0"
origin = "maven:com.chaquo.python:target:3.14.0-0"
licence = "BSD-3-Clause OR GPL-2.0-only"
elected = "BSD-3-Clause"     # an OR expression is accepted only with an elected, allowed alternative
kind = "native"
aboutLibrariesId = "zstd"

[[component]]
name = "CA certificate bundle (certifi cacert.pem)"
licence = "MPL-2.0"
kind = "data"                # MPL-2.0 is accepted only for kind = "data", unmodified
aboutLibrariesId = "certifi-cacert"
# … one entry per row of the inventory below
```

Allow-list (`PythonLicencePolicy.kt`): `Unlicense`, `MIT`, `ISC`, `Apache-2.0`, `Apache-2.0 WITH LLVM-exception`, `BSD-2-Clause`, `BSD-3-Clause`, `0BSD`, `PSF-2.0`, `Python-2.0` (because CPython is distributed under the whole PSF/BeOpen/CNRI/CWI stack, not PSF-2.0 alone), `Unicode-3.0` (because CPython's `unicodedata` and `str` carry an extract of the Unicode Character Database under the Unicode License v3), `Zlib`, `bzip2-1.0.6`, `blessing` (SQLite's public-domain dedication) and `LicenseRef-PublicDomain`; `MPL-2.0` only for `kind = "data"`. Anything containing `GPL` (GPL, LGPL, AGPL) fails, also inside an `OR` expression unless an allowed alternative is `elected`.

Inventory (component versions are those of the planned stack, Unverified until S7 reads them from the Chaquopy runtime; licences per [CPython's licence page](https://docs.python.org/3/license.html), [yt-dlp](https://github.com/yt-dlp/yt-dlp#licensing), [yt-dlp-ejs](https://github.com/yt-dlp/ejs), [Chaquopy](https://github.com/chaquo/chaquopy), [quickjs-kt](https://github.com/dokar3/quickjs-kt)):

| Component | Version (planned) | Licence | Ships in |
|---|---|---|---|
| CPython runtime and standard library | 3.14.0 | Python-2.0 (PSF-2.0 with the BeOpen, CNRI and CWI terms); its Licences entry and `THIRD_PARTY_NOTICES.md` text is CPython's full licence verbatim, including every "Licenses and Acknowledgements for Incorporated Software" notice (Mersenne Twister, SipHash24, strtod and dtoa, cfuhash, Global Unbounded Sequences, the Zstandard bindings and the others listed there, [CPython licence](https://docs.python.org/3/license.html)) | `arm64-v8a`, `x86_64` APKs (ABI-independent stdlib assets possibly also in `armeabi-v7a`, S7) |
| mimalloc (CPython's allocator) | as bundled | MIT | 64-bit APKs |
| Unicode Character Database extract (`unicodedata`, `str`) | as bundled | Unicode-3.0, data | 64-bit APKs |
| OpenSSL (`libcrypto`, `libssl`; Python's `ssl` module, not used for network traffic, [D74](../PLAN.md#3-key-decisions)) | 3.0.18 | Apache-2.0 | 64-bit APKs |
| SQLite (Python's `_sqlite3`) | 3.50.4 | blessing (public domain) | 64-bit APKs |
| libffi, expat, HACL* | as bundled | MIT | 64-bit APKs |
| mpdecimal | as bundled | BSD-2-Clause | 64-bit APKs |
| zstd | as bundled | BSD-3-Clause (elected from `BSD-3-Clause OR GPL-2.0-only`) | 64-bit APKs |
| xz (liblzma) | as bundled | 0BSD | 64-bit APKs |
| bzip2 | as bundled | bzip2-1.0.6 | 64-bit APKs |
| zlib | as bundled | Zlib | 64-bit APKs |
| Chaquopy runtime (Java, JNI, bootstrap) | 17.0.0 (or S7's choice) | MIT | 64-bit APKs (Java part possibly in all three, S7) |
| `libc++_shared.so` (shipped with Chaquopy's runtime) | NDK | Apache-2.0 WITH LLVM-exception | 64-bit APKs |
| CA certificate bundle (certifi `cacert.pem`, shipped by Chaquopy) | as bundled | MPL-2.0, data only | 64-bit APKs |
| yt-dlp (official zipimport release) | 2026.08.19 | Unlicense | 64-bit APKs, engine updates |
| yt-dlp-ejs (inside the yt-dlp release) | 0.8.0 | Unlicense; its solver bundles meriyah (ISC) and astring (MIT) | 64-bit APKs, engine updates |
| `neutrodyne_ytx` shim | — | Unlicense | 64-bit APKs |
| QuickJS (inside quickjs-kt; only if the JS provider ships) | as bundled by 1.0.15 | MIT | 64-bit APKs |

Never shipped (each would be a licence regression, risk L2; [D3](../PLAN.md#3-key-decisions)): yt-dlp's PyInstaller executables (GPL parts; Linux glibc/musl builds anyway), youtubedl-android (GPL-3.0), a Termux-built Python (GNU readline), `mutagen` (GPL-2.0+, part of yt-dlp's `default` extra), `bgutil-ytdlp-pot-provider` (GPL-3.0), Deno or Node.

Tasks (all in `:youtube:ytdlp`, all configuration-cache safe):

| Task | In `check` | Fails when |
|---|---|---|
| `checkPythonLicences` | yes (from M0, whatever S7 decides; the licence allow-list check is unconditional) | a component's licence is not on the allow-list (MPL-2.0 outside `kind = "data"`; an `OR` expression without an allowed `elected`); only while `:youtube:ytdlp` applies Chaquopy (S7 go): the lockfile's `chaquopy`, `python` or `pip` differ from the build's Chaquopy plugin version, Python version or pip requirements (on a fallback host the lockfile's `python` entry is checked against the A2 package instead, and with the Kotlin port the CPython rows leave the lockfile); the vendored `engine/bundled.json` version differs from the yt-dlp entry; the top-level packages inside `engine/yt-dlp` are anything but `yt_dlp` and `yt_dlp_ejs`; a component has no AboutLibraries manual definition with its `aboutLibrariesId` (so the Licences screen and `THIRD_PARTY_NOTICES.md` cannot drift) |
| `verifyBundledYtDlp` | yes (no-op until M9a vendors the file) | the SHA-256 of `engine/yt-dlp` differs from its line in the committed `engine/SHA2-256SUMS` or from `bundled.json`; `engine/SHA2-256SUMS.sig` does not verify against `keys/yt-dlp-release-key.asc` whose fingerprint must equal the one pinned in build-logic (`AC0C BBE6 848D 6A87 3464 AF4E 57CF 6593 3B5A 7581`, [yt-dlp public key](https://github.com/yt-dlp/yt-dlp/blob/master/public.key); re-verified when M9b starts); `ORIGIN` in the zip's `yt_dlp/version.py` is not `yt-dlp/yt-dlp`. Verification uses build-logic's `OpenPgpSignatureCheck` (JDK `Signature`, no `gpg` binary) |
| `shimTest` | no (needs a host CPython of the target minor version; CI `unit` job) | the shim's pytest suite fails against the bundled yt-dlp with `ReplayRH` (04 owns the content) |

09's `check-apk.sh` closes the loop on the built APKs: it fails on forbidden content (`mutagen`, `readline`, `libreadline`, `org/schabi/newpipe`, `org/mozilla/javascript`), and it uses the lockfile as its input for what native libraries and top-level Python packages an APK may contain (09 owns the script).

### AboutLibraries and the Licences screen

- The AboutLibraries Gradle plugin in `:app` generates library metadata for the published `debug` variant (and for `benchmark`); with the dev-tools switch off — always, for published APKs — the list contains no developer tooling; all three ABI APKs of a default build show the same list, engine stack included (08 labels it on the `armeabi-v7a` APK); only the [emergency build](#emergency-build-without-the-engine) omits the engine entries. Unverified: whether 15.x uses the plugin ID `com.mikepenz.aboutlibraries.plugin` or `com.mikepenz.aboutlibraries.plugin.android` for Android variants — M0 step 18 confirms. The plugin runs in offline mode (no remote licence or funding fetches during the build) so builds stay reproducible ([09 Hygiene](09-quality-and-release.md#hygiene)); Unverified: the 15.x property names.
- `:feature:settings` renders `LicencesKey` itself with `Nd*` components from `aboutlibraries-core` data (loaded at runtime from the app's generated resource) — **not** with AboutLibraries' Compose UI artifact, which could pull a different Compose/Material3 line (the same trap as `material-kolor`). M0 step 18 checks `aboutlibraries-core` has no Compose dependency.
- Each entry shows name, version, licence name and the full licence text; Apache-2.0 `NOTICE` contents are added through AboutLibraries' `config/aboutlibraries/` overrides where a dependency ships one (Unverified which do).
- Manual library definitions in the same config directory cover code that ships in the APK but is not on a runtime classpath: every component of the [lockfile inventory](#python-and-native-components) — CPython (its full licence text with the incorporated-software notices) and its bundled libraries (OpenSSL, SQLite, libffi, expat, mpdecimal, zstd, xz, bzip2, zlib, HACL*, mimalloc, the Unicode Character Database extract), the Chaquopy runtime and `libc++_shared`, yt-dlp, yt-dlp-ejs with meriyah and astring, the CA bundle (MPL-2.0), QuickJS when the JS provider ships (quickjs-kt itself comes from its POM) — each with its `aboutLibrariesId`, plus any permissive or Unlicense code copied or ported under the [contribution rule](#copied-code-and-contributions). `THIRD_PARTY_NOTICES.md` mirrors the same list. The engine entries sit in their own subdirectory that the [emergency build](#emergency-build-without-the-engine) leaves out.
- **Packaging:** never exclude `META-INF/LICENSE*` or `META-INF/NOTICE*` wholesale; only the duplicate `/META-INF/{AL2.0,LGPL2.1}` entries are excluded ([common config](#common-android-configuration)).

### About statements

About (`:feature:settings`) shows the version, the APK's ABI (`BuildInfo.apkAbi`), one licence statement and a "Source code" link to `BuildInfo.repoUrl`. The statement is an ordinary string resource of `:feature:settings`, identical in every APK:

| Build | Statement (en) |
|---|---|
| every APK | "Neutrodyne's source code is dedicated to the public domain under the Unlicense. The app also includes third-party components under permissive licences, listed under Licences." |

On APKs with the engine, About may add the credit line "YouTube engine: yt-dlp {activeVersion}" from `YouTubeEngine.status` (08 decides placement). [04 Licensing and legal](04-youtube.md#licensing-and-legal) owns the engine-stack notice wording on the Licences screen and must use the statement above for About.

### Copied code and contributions

`CONTRIBUTING.md` and the PR template (created in M0) carry this rule verbatim:

1. **Behaviour-only reuse.** Never copy code from GPL, LGPL or AGPL projects (AntennaPod, the NewPipe app, NewPipe Extractor, LibreTube, Podcini, youtubedl-android, Seal, YTDLnis) or MPL projects (Pocket Casts) into any module — there is no exception. Re-implement behaviour from documentation and design notes.
2. **Permissive and public-domain code** (Apache-2.0, MIT, BSD, ISC, Unlicense — e.g. nav3-recipes) may be copied only with its original copyright header and `SPDX-License-Identifier` line kept, plus an entry in `THIRD_PARTY_NOTICES.md`. yt-dlp is Unlicense: porting its logic (for example the Kotlin InnerTube fallback of [D72](../PLAN.md#3-key-decisions)) is allowed, with a credit header in each ported file ("Ported from yt-dlp `<path>` at `<commit>`, Unlicense") and an entry in `THIRD_PARTY_NOTICES.md`.
3. All contributions are dedicated under the Unlicense.
4. PR template checkbox: "No code was copied from GPL, LGPL, AGPL or MPL projects; copied or ported permissive or Unlicense code keeps its header (or credit line) and is listed in THIRD_PARTY_NOTICES.md." Reviewers enforce it ([09 Static analysis](09-quality-and-release.md#static-analysis) runs `checkSpdxHeaders`).

---

## M0 scaffold checklist

Delivers [M0](../PLAN.md#m0-scaffold-and-ci). Ordered; each step ends with a green `./gradlew build` unless stated. Steps marked (09) or (08) follow those documents for content.

1. Repository hygiene: `.gitignore` (Gradle, Android Studio, `local.properties`, `*.jks`; it must not match `signing/neutrodyne-debug.keystore`), `.gitattributes` (`* text=auto eol=lf`, binaries, `signing/*.keystore binary`), `.editorconfig` (09). Generate the committed debug keystore `signing/neutrodyne-debug.keystore` once with the `keytool` command of [09 Debug keystore](09-quality-and-release.md#debug-keystore) and add `signing/README.md` (public on purpose; download only from GitHub releases; forks change the application ID or the key) ([Signing config](#signing-config)); no key ceremony, holders or backups exist ([D61](../PLAN.md#3-key-decisions)).
2. Gradle wrapper: `gradle wrapper --gradle-version 9.7.1 --distribution-type bin`; set `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` from gradle.org's published checksum.
3. Write `gradle/libs.versions.toml` exactly as in [Toolchain and versions](#gradlelibsversionstoml).
4. Write `settings.gradle.kts`, `gradle.properties` (version `0.1.0` / `10095`; `neutrodyne.devTools` documented, never set), root `build.gradle.kts` ([above](#settingsgradlekts-gradleproperties-root-build)); `compose-stability.conf`.
5. Create `build-logic/` (settings, `convention/build.gradle.kts`, plugin classes for all ten IDs, `ModuleRules.kt`); policy tasks may start as no-ops returning success, filled in step 18.
6. **Spike S1** (KGP pin). Record the result before continuing; on failure apply its fallback.
7. Create every module of [Module layout](#module-layout) (except `:benchmark`) with its plugins, namespace, an `internal` placeholder and one placeholder test (there are no `:update:*` modules; the update check arrives in `:core:domain`, `:core:model` and `:core:data` in M11a). Then **Spike S7** on `:youtube:ytdlp` ([S7](#s7-chaquopy-under-agp-941)); record the outcome before continuing. If go, `:youtube:ytdlp` keeps Chaquopy applied with the hello-world `neutrodyne_ytx/selftest.py` and the real `YtxService` declared in `:ytx` (answering only `ping` and `selftest` until M9a), and `NeutrodyneApplication`'s `ProcessRole.YTX` branch and `YtxProcessStartTest` are live from then on, so every later AGP, Kotlin or Chaquopy bump that breaks the integration fails CI at once; on a fallback it stays a plain Android library stub and D72 is amended. Every source file carries `SPDX-License-Identifier: Unlicense`.
8. `:core:common`: `Clock`, `Dispatcher`/`NeutrodyneDispatchers`, `ApplicationScope`, `Outcome`, `suspendRunCatching`, `Log`/`LogSink`/`Redactor`, `AppInitializer`, `NetworkMonitor`/`NetworkStatus` interfaces — with the unit tests listed in [Testing](#testing).
9. `:core:model`: `BuildInfo` (fields per [Build variants and ABIs](#build-variants-and-abis)), `NetError`/`TlsKind`, `IpFamily` (04's enum, placed here), `SettingsFile`/`SettingKey`, `AllSettingKeys` (empty list + test). `ExternalReason` (04's enum, placed here so that `:playback:api` and `:core:ui` can use it under rules 7 and 10) follows in M2 with its first consumer.
10. `:core:navigation`: all canonical keys plus 08's `SettingsHomeKey`, `TopLevelKey`, `EntryProviderInstaller`, `AppNavigator` (with 08's `pushDetail`), `LocalAppNavigator`, `NdSceneMetadata`, and 08's `PaneLayout`/`LocalPaneLayout`/`LocalNavTab`.
11. `:core:datastore`: both `DataStore`s, `SettingsStore`, `DeviceSettingsStore`; `:core:domain`: `SettingsRepository` interface; `:core:data` stub: `CredentialLookup.None` binding.
12. `:core:network`: `NetworkModule` (credential-free core, base, all six derived clients), `pinnedToFamily`, `UserAgentInterceptor`, `AuthInterceptor`, `IdentityEncodingInterceptor`, `LocalNetworkGuardDns`, `LocalNetworkGuardInterceptor`, `DnsFamilyHints`, `FamilyHintDns`, `NetErrorClassifier`, `ConnectivityNetworkMonitor`, `network_security_config.xml`, manifest permissions — with tests.
13. `:core:testing`: `MainDispatcherRule`, `TestClock`, `FakeNetworkMonitor` (09 owns the full inventory).
14. `:core:designsystem`: `NeutrodyneTheme` (dynamic colour on API 31+, placeholder brand scheme, light/dark) and Material Symbols for the five destinations and the gear (08).
15. Feature stubs: each top-level feature installs its `TopLevelKey` entry showing a placeholder empty state; `:feature:settings` installs `SettingsHomeKey` (the gear's target, 08), `SettingsKey` (M0 renders `ABOUT`: version, ABI, licence statement, source link; 08 adds Appearance) and `LicencesKey` (AboutLibraries plus the manual entries of the Chaquopy runtime and CPython stack when S7 is go).
16. `:app`: `NeutrodyneApplication` (`ProcessRole`, ACRA guard, initializer runner, entry points), `CoreModule` (dispatchers, scope, `DeviceClock`, `BuildInfo`, `@Multibinds` initializer set), an empty `YouTubeBindingsModule` in `app/src/main` (its first binding arrives in M2, [YouTube bindings](#youtube-bindings)), build types `debug` (published) and `benchmark` with the disabled `release` variant, the `neutrodyneDebug` signing config, ABI splits and the dev-tools switch with `app/src/devTools/` (`DevToolsInitializer`, `DebugHttpLogInterceptor`, the network-security-config override) ([Build variants and ABIs](#build-variants-and-abis), [Dev-tools switch](#dev-tools-switch)), `MainActivity` (AppCompat, splash with `StartupViewModel`, edge-to-edge), `NavigationState`, `NeutrodyneNavHost`, overlay scene strategies, `IntentRouter` (internal routes only in M0), `NeutrodyneRoot` with `NavigationSuiteScaffold` (08), `keepRules/app.keep`, themes XML, manifest per [Manifest and permissions](#manifest-and-permissions) (M0 subset), M0 backup rule files, `res/resources.properties`.
17. ACRA mail + dialog wired to `ACRA_MAILTO` from the committed `neutrodyne.acraMailto` (disabled while empty; on in the published `debug` build, forced empty in dev-tools builds, [D62](../PLAN.md#3-key-decisions)) (09).
18. Policy tooling: Licensee, module-graph assertion, AboutLibraries (confirm plugin ID; confirm `aboutlibraries-core` has no Compose dependency with `./gradlew :feature:settings:dependencies`), `verifyDependencyPolicy`, `verifyManifestPermissions` with `app/policy/permissions.txt`, `checkSpdxHeaders`, `checkBannedApis`, `PythonLicencePolicy.kt` with `youtube/ytdlp/python-components.lock` (the Chaquopy runtime and CPython stack if S7 is go, otherwise empty) and `checkPythonLicences`, `verifyBundledYtDlp` (a no-op until M9a vendors yt-dlp), `THIRD_PARTY_NOTICES.md` ([Python and native components](#python-and-native-components)).
19. Spotless/ktlint/compose-rules (blocking) and detekt (non-blocking) (09).
20. GMD definitions (`api26` `aosp`, `api36` `aosp-atd`; the API 37 16 KB image runs through android-emulator-runner) and the M0 instrumented smoke test (09).
21. `ci.yml` (`assemble` runs `assembleDebug assembleBenchmark`), `nightly.yml` (`instrumented-full` with its benchmark leg, `api37-16k`, the non-blocking `dev-tools-build` and the report-only `repro` job) and `release.yml` (`assembleDebug` → three ABI APKs signed with the committed keystore and checked against `scripts/ci/debug-cert-sha256.sh` (09 owns the script), `SHA256SUMS`, `neutrodyne-update.json`, provenance attestations, immutable normal release) ([09 Workflows](09-quality-and-release.md#workflows)), `keepalive.yml`, `changelogs/`, Renovate config, PR template and `CONTRIBUTING.md` with the [contribution rule](#copied-code-and-contributions) (09). There is no mirror ([PO-34](../PLAN.md#48-further-product-owner-decisions)).
22. **Spikes S2–S6**; write results into [Spikes](#spikes) and the owning documents.
23. Negative verification (PLAN M0 AC2), recorded in the [verification log](#verification-log): (a) add `implementation(project(":feature:library"))` to `:feature:feeds` → `assertModuleGraph` fails; (b) add a GPL-licensed artifact to `:core:data` → `licenseeDebug` and `verifyDependencyPolicy` fail; (c) add a component with `licence = "GPL-3.0-or-later"` to `python-components.lock` → `checkPythonLicences` fails; (d) add a file named `mutagen/__init__.py` (and, separately, `lib/arm64-v8a/libreadline.so`) to a published APK → 09's `scripts/ci/check-apk.sh` fails. Revert all four.
24. Acceptance run: `./gradlew check assembleDebug assembleBenchmark` green on CI in < 15 min, producing the `arm64-v8a`, `x86_64` and `armeabi-v7a` APKs of both build types and no universal APK, the debug APKs within the N5 budgets or S7's sizes sent to the PO (PLAN M0 AC1); the cross-machine certificate check and the dev-tools isolation checks of the [verification log](#verification-log) (PLAN M0 AC8, AC9); `./gradlew :app:buildEnvironment` shows `kotlin-gradle-plugin` resolved to 2.4.20; installs on API 26 and API 36 GMDs, five labelled destinations, survives rotation and dark-mode switch, back from Settings returns to the tab (predictive-back animation checked manually once on an API 36 image and noted in the log); Licences lists every runtime dependency with its licence (with the CPython stack and Chaquopy when S7 is go).
25. First release: `scripts/release.sh 0.1.0` tags the prepared version and `release.yml` publishes `v0.1.0` (`versionCode` 10095) as an immutable, normal GitHub release (never a pre-release) signed with the committed keystore: three debug APKs, `SHA256SUMS`, `neutrodyne-update.json` and attestations, verified per PLAN M0 AC7; the README's "Install and update" section states that the APKs are debug builds signed with a public key and how to check a download (09).

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
| S7 | Chaquopy embeds CPython 3.14 in `:youtube:ytdlp` under AGP 9.4.1, Gradle 9.7.1, built-in Kotlin 2.4.20 and targetSdk 37, with the app's ABI splits, and starts in `:ytx` on API 26 and on the API 37 16 KB image; the published debug APKs' sizes against PB12/PB13 | pending | [D72](../PLAN.md#3-key-decisions), [D77](../PLAN.md#3-key-decisions) if they deviate; [04 YouTube engine](04-youtube.md#youtube-engine); [Python and native components](#python-and-native-components) (versions read from the runtime) |

### S1 KGP 2.4.20 under AGP 9.4.1

- **Method:** build-logic declares `implementation(libs.kotlin.gradlePlugin)` (and the other plugin artifacts). Run `./gradlew :app:buildEnvironment` and `./gradlew :core:common:compileKotlin --info`. Add an in-build assertion to every convention plugin: `check(project.getKotlinPluginVersion() == libs.findVersion("kotlin").get().requiredVersion) { "KGP drift: …" }` with `libs` from `VersionCatalogsExtension` ([catalog rule 5](#gradlelibsversionstoml)) (Unverified API location: `org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion`). Build `assembleDebug`.
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
- **Also records** (02 depends on them, [02 Room 2 to Room 3 mapping](02-data-model.md#room-2-to-room-3-mapping)): the exact Room 3 names for the read transaction used by backup export (`useReaderConnection` + deferred transaction), `setJournalMode`, `@ColumnTypeConverters`, the `Migration.migrate` and `RoomDatabase.Callback` signatures, `@AutoMigration`, the `room3 { }` Gradle extension name, and how `androidx.sqlite.SQLiteException` exposes result codes with each driver.
- **Pass:** compiles; three consecutive pages return `(sortDate, id)` order without gaps or duplicates; an insert into `episode` invalidates; a write to an unobserved table does not.
- **Fallback:** generated `@Query` per (source × order) ([D30](../PLAN.md#3-key-decisions)); 02 records which.

### S3 foreign_keys with the bundled driver

- **Method:** with `BundledSQLiteDriver` on a GMD: (a) insert an `episode` with a missing `podcastId` → expect a constraint error; (b) delete a `podcast` → its `episode` rows cascade; (c) run `PRAGMA foreign_keys` on the writer and on reader connections (`useReaderConnection`) of the WAL pool → expect 1 on each; (d) run `PRAGMA foreign_keys` inside a test `Migration.migrate` → expect 0 (02's table rebuilds need it off, [02 Writing migrations](02-data-model.md#writing-migrations)).
- **Pass:** all four hold.
- **Fallback:** 02's `ForeignKeysDriver` — a `SQLiteDriver` decorator whose `open()` runs `PRAGMA foreign_keys = ON` on every new connection, armed only after the first open completes (so migrations still run with foreign keys off); if (d) fails, parent-table rebuilds run in a pre-Room step of `DatabaseOpener` on a raw connection, bound in `SqliteDriverModule` around the production and test drivers ([02 Conventions](02-data-model.md#conventions)); 02 records which.

### S4 Robolectric with AndroidSQLiteDriver

- **Method:** Robolectric 4.17, `sdk=36`, JDK 21: build `NeutrodyneDatabase` in memory and in a file with `AndroidSQLiteDriver`; run one DAO test and a `room3-testing` `MigrationTestHelper` create-v1 → validate test. Also try `BundledSQLiteDriver` and record the failure mode. Query `SELECT sqlite_version()`.
- **Pass:** DAO and migration tests green with `AndroidSQLiteDriver`; the reported SQLite version is recorded (02 keeps all SQL compatible with SQLite 3.18, so no minimum beyond that is needed).
- **Fallback:** if `MigrationTestHelper` fails under Robolectric, migration tests run only on GMD (09); if DAO tests fail, DAO tests move to GMD and 09 re-plans the unit-test budget.

### S5 Nav3 1.2 API names and scenes

- **Method:** in `:app`, two tabs with one list-detail pair, one sheet key, one dialog key; verify: `NavDisplay` parameter names (`entries` vs `backStack`, `sceneStrategies` list), `rememberDecoratedNavEntries` (or the 1.2 equivalent) for per-tab decoration, `rememberViewModelStoreNavEntryDecorator`, `rememberSaveableStateHolderNavEntryDecorator`, `ListDetailSceneStrategy` metadata helpers, whether `DialogSceneStrategy`/a bottom-sheet strategy ship in 1.2, `NavigationBackHandler`, `rememberNavBackStack` restoring keys declared in `:core:navigation` after "Don't keep activities" + `adb shell am kill`, `hiltViewModel(creationCallback)` per entry, and — for 08 — a custom `PaneScaffoldDirective` passed to `ListDetailSceneStrategy`, an `extraPane()` entry placed directly after a `listPane()` entry, a `PaneScaffoldDirective` built from `calculatePaneScaffoldDirective(…, HingePolicy.AvoidSeparating)` (hinge-aware panes on foldables), a sheet opened from the expanded `PlayerSheet` drawing above it (window-based sheet), and the order of `PredictiveBackHandler` (player sheet) versus `NavDisplay`'s back handling. `DialogSceneStrategy` ships since 1.1; check whether its metadata is the typed `DialogKey` and whether it accepts our `NdDialog`.
- **Pass:** process-death restore of both tabs' stacks; distinct ViewModels for two `PodcastKey`s; a tab's entry keeps its ViewModel while another tab is shown; sheet and dialog render as overlays and dismiss on back.
- **Fallback:** the bottom-sheet strategy is ours in any case (nav3-recipes pattern); if per-tab retention fails, accept ViewModel recreation on tab switch with state in `SavedStateHandle` and record it for 08; if an extra pane cannot follow a list pane, 08's documented fallback (`EpisodeKey` as `detailPane()`) applies.

### S6 sqlite-bundled 16 KB alignment and size

- **Method:** build the published `debug` APKs (and `benchmark` for comparison) with and without `sqlite-bundled`; `zipalign -c -P 16 -v 4` on each ABI APK; `llvm-readelf -l lib/arm64-v8a/*.so` → every `LOAD` segment `Align 0x4000`; compare APK size per ABI.
- **Pass:** aligned; size delta recorded (budget context, N5: published debug `arm64-v8a` and `x86_64` APKs < 60 MB each including the engine, `armeabi-v7a` < 50 MB; PB12, PB13).
- **Fallback:** `AndroidSQLiteDriver` in production — a one-line change in `SqliteDriverModule`, cheap because 02 already restricts SQL to SQLite 3.18 features; still changes [D9](../PLAN.md#3-key-decisions), PLAN amendment required.

### S7 Chaquopy under AGP 9.4.1

Gates every YouTube-engine milestone ([D72](../PLAN.md#3-key-decisions), risk T14). Facts at planning time (2026-10-05): the latest Chaquopy release on Maven Central is 17.0.0 (2025-11-30, [Maven metadata](https://repo1.maven.org/maven2/com/chaquo/python/gradle/maven-metadata.xml)); its documentation names AGP 7.3–9.2, Python 3.10–3.14 and, for Python ≥ 3.12, only `arm64-v8a` and `x86_64` ([Chaquopy docs](https://chaquo.com/chaquopy/doc/current/android.html)); master's `VERSION.txt` is 17.1.0 and carries the AGP 9.x updates up to 9.4.1, Python 3.15 and target API 37, including read-only extracted `.so` files (Unverified beyond the repository history, [chaquopy](https://github.com/chaquo/chaquopy)). Chaquopy's FAQ warns that ABI splits "won't help much" for its native components and recommends a product-flavor dimension instead ([FAQ](https://chaquo.com/chaquopy/doc/current/faq.html)).

- **Method:**
  1. Check Maven Central for a Chaquopy release newer than 17.0.0 and start with the newest.
  2. Apply `com.chaquo.python` to `:youtube:ytdlp` (build-logic classpath and bare `id`, catalog rule 3; if that fails, a versioned `plugins {}` entry) with the configuration of [Build variants and ABIs](#build-variants-and-abis): Python 3.14, `abiFilters` `arm64-v8a` + `x86_64`, empty `pip`, `buildFeatures.aidl`; a hello-world `neutrodyne_ytx/selftest.py` (returns the Python and OpenSSL versions) and `YtxService` with `android:process=":ytx"` that calls `Python.start(AndroidPlatform(context))` and answers `ping` and `selftest` (kept after a go; M9a adds the engine methods).
  3. Under AGP 9.4.1, Gradle 9.7.1, built-in Kotlin 2.4.20, targetSdk 37 and the configuration cache: `./gradlew check assembleDebug assembleBenchmark` (`benchmark` is minified, so missing keep rules show up; `selftest` also runs on it).
  4. If the released plugin fails: build master (17.1.0) at a pinned commit, publish it, together with the CPython `target` artifacts it needs (that repository then alone serves `com.chaquo.python*`), to `third_party/chaquopy-maven/` (the commented `exclusiveContent` repository in `settings.gradle.kts`; record its size) and repeat 2–3.
  5. Measure and record: the size per ABI of the published debug APKs (PB12/PB13; the budgets are Unverified estimates until this measurement, and a miss goes to the PO, PLAN M0 AC1) and of the `benchmark` APKs for comparison, with default and with legacy native packaging (`useLegacyPackaging`); the bytes of foreign-ABI Chaquopy assets in each split and of ABI-independent Python assets in the `armeabi-v7a` APK (`unzip -l`); `Python.start` + `selftest` in `:ytx` on the API 26 GMD and on the API 37 16 KB image (no `UnsatisfiedLinkError` from read-only rules, P35); `llvm-readelf -l` 16 KB alignment of every `.so`, including those inside Chaquopy's asset zips; time from binding `YtxService` to the `selftest` result on the GMDs (first indication only; the reference-device numbers come from the M9a spike); that a host Python 3.14 (`buildPython`) works in CI via `actions/setup-python` and inside 09's pinned release container `python:3.14-slim-trixie` (the image `release.yml` and the `repro` job build in); Chaquopy's Gradle configuration names and runtime coordinates (input to `checkPythonLicences`), whether its runtime appears on `debugRuntimeClasspath` (Licensee), whether its AAR ships consumer keep rules, the AGP 9 names of the `splits.abi` DSL, and the component versions bundled in the runtime (OpenSSL, SQLite, …) for the [lockfile](#python-and-native-components).
- **Pass (go):** green build and `selftest` on both images with a released or self-built Chaquopy, configuration cache intact (or a recorded, accepted exception); in each **64-bit** APK the foreign-ABI Chaquopy bytes (the other 64-bit ABI's `lib-dynload` and any other per-ABI assets) ≤ 5 MB; in the **`armeabi-v7a`** APK every Python and Chaquopy byte is unusable (target 0; estimate ≈ 12–13 MB: standard library 4.5 MB, yt-dlp 3.1 MB, shim, two 64-bit `lib-dynload` sets ≈ 2.5 MB each, Unverified) and is accepted only while that APK stays within PB13 (< 50 MB for the published debug APK), counted there ([Open questions](#open-questions) 13).
- **Fallbacks** (in this order, [D72](../PLAN.md#3-key-decisions)): foreign-ABI assets > 5 MB in a 64-bit APK, or the `armeabi-v7a` APK over PB13 because of its Python assets → go with an ABI product-flavor dimension instead of ABI splits, whose `armeabi-v7a` flavor omits `:youtube:ytdlp`'s assets as the no-engine build does (amend [D2](../PLAN.md#3-key-decisions)); no Chaquopy build works → **A2**: python.org's official Android CPython (`arm64-v8a`, `x86_64`, [Python on Android](https://docs.python.org/3/using/android.html)) as a long-lived child process of `:ytx`, started from a launcher packaged in `jniLibs` (`useLegacyPackaging = true`, so it is installed into the executable `nativeLibraryDir`, P33), JSON over stdio; A2 not viable either → the Kotlin InnerTube client ported from yt-dlp's Unlicense source. Lowering AGP to suit Chaquopy is not on the list (open question 12).
- **CI hook:** while S7 is go, `:youtube:ytdlp` keeps Chaquopy applied from M0 and the instrumented smoke test runs `selftest` in `:ytx` on the API 26 GMD and the API 37 16 KB image; Renovate puts Chaquopy bumps behind dashboard approval and every bump repeats steps 3 and 5 ([09 Dependency updates](09-quality-and-release.md#dependency-updates)).

```mermaid
flowchart LR
  a["newest Chaquopy release on Maven Central"] --> b{"builds under AGP 9.4.1 and selftest runs in ytx on API 26 and API 37 16 KB?"}
  b -->|no| m["self-built master 17.1.0 from a local Maven repository"]
  m --> b2{"builds and selftest runs?"}
  b -->|yes| c{"foreign-ABI assets at most 5 MB per 64-bit APK and armeabi-v7a APK within PB13?"}
  b2 -->|yes| c
  c -->|yes| go["go: ABI splits"]
  c -->|no| fl["go with an ABI flavor dimension, amend D2"]
  b2 -->|no| a2["fallback A2: python.org CPython child process"]
  a2 --> d{"works?"}
  d -->|yes| fa["record A2, amend D72"]
  d -->|no| kt["fallback Kotlin InnerTube port, amend D72"]
```

### Verification log

| Date | Check | Result |
|---|---|---|
| (M0) | `:feature:feeds → :feature:library` fails `assertModuleGraph` | pending |
| (M0) | GPL artifact in `:core:data` fails `licenseeDebug` and `verifyDependencyPolicy` | pending |
| (M0) | GPL-licensed entry in `python-components.lock` fails `checkPythonLicences` | pending |
| (M0) | an APK containing `mutagen/__init__.py`, and one containing `libreadline.so`, each fail `check-apk.sh` (09) | pending |
| (M0) | S7 outcome: Chaquopy version (release or master commit), Python version, packaging mode, foreign-ABI bytes per split, `selftest` on API 26 and API 37 16 KB | pending |
| (M0) | the build fails when a module other than `:youtube:ytdlp` applies `com.chaquo.python`, and when core-library desugaring is enabled | pending |
| (M0) | predictive back from Settings animates on API 36 (manual) | pending |
| (M0) | merged `debug` (published) permissions equal `app/policy/permissions.txt` (records the `androidx.core` receiver permission name) | pending |
| (M0) | the CI-built and a locally built `arm64-v8a` debug APK carry the same certificate, equal to `scripts/ci/debug-cert-sha256.sh`'s output, and each installs over the other with `adb install -r` keeping the app's data (PLAN M0 AC8) | pending |
| (M0) | `aapt2 dump badging` of the CI APKs: package `ch.lkmc.neutrodyne`, `application-debuggable`, no `testOnly`; `apksigner verify --print-certs`: v2 + v3, no v1 (PLAN M0 AC7); the `benchmark` APK is not debuggable | pending |
| (M0) | a local build with `-Pneutrodyne.devTools=true` is `ch.lkmc.neutrodyne.dev` and contains LeakCanary; the APKs of `ci.yml`'s `assemble` job and of `release.yml` (never `-Pneutrodyne.devTools`) contain no LeakCanary and no `app/src/devTools/` class (`check-apk.sh --published`); `BuildConfig.DEBUG` added to a `main` source fails `checkBannedApis` (PLAN M0 AC9) | pending |
| (M0) | GitHub push protection accepts the committed `signing/neutrodyne-debug.keystore`, or the documented bypass reason was needed | pending |
| (M0 while S7 is go, else M9a) | `YtxProcessStartTest`: no initializer, database, DataStore, WorkManager or ACRA in `:ytx` | pending |
| (M9a) | `assembleDebug -Pneutrodyne.youtubeEngine=false` contains no Chaquopy, CPython, yt-dlp or `YtxService`, and its Licences screen omits the engine entries | pending |
| (M11a) | with the update check in place, the merged `debug` permissions still equal `permissions.txt` and contain no install permission (`REQUEST_INSTALL_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION`; PLAN M11 AC6) | pending |

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
| `DnsFamilyHints`, `FamilyHintDns`, `pinnedToFamily` | JVM with a fake `Dns` returning A + AAAA | no hint → all addresses; `V4` hint for `googlevideo.com` → only A records for `rr1---sn-x.googlevideo.com`, not for `notgooglevideo.com`; `V6` hint with no AAAA → all addresses; `null` clears; `pinnedToFamily(V6)` → only AAAA for every host, shares the original's connection pool and dispatcher, and is the same instance on a second call |
| Client graph | Robolectric Hilt in `:app` | constructing `@HttpClient(YOUTUBE)` does not construct `CredentialLookup` (a binding that throws on construction proves it); every other kind carries `AuthInterceptor` |
| `NetErrorClassifier` | JVM | every row of the [taxonomy table](#network-error-taxonomy), with `FakeNetworkMonitor` connected and disconnected |
| `ConnectivityNetworkMonitor` | Robolectric (`ShadowConnectivityManager`) | initial value seeded without a collector; `status.value` follows a default-network change with no subscriber (eager sharing); metered/unmetered/VPN mapping |
| `IntentRouter` | Robolectric | every row of the [routing table](#intent-routing); upper-case scheme; 1 MB `EXTRA_TEXT` truncated to 4 KB; non-numeric IDs → `None`; unknown settings page → `None`; no route performs a write (verified with fakes recording calls) |
| Hilt graph | Robolectric `@HiltAndroidTest` in `:app`; also run by the nightly `no-engine-build` with `-Pneutrodyne.youtubeEngine=false` | graph builds; every canonical `NavKey` (and 08's `SettingsHomeKey`) has exactly one installer entry; every `AppInitializer.order` lies in a defined band; `YouTubeCapabilitiesSource` reports `NOT_YET_AVAILABLE` before M9a and `NOT_IN_THIS_APK` in the no-engine build (and `YouTubeEngine` is `AbsentYouTubeEngine` there); `TestSqliteDriverModule` replaces the driver |
| Start-up ordering (M1; each framework component from the milestone it lands in) | Robolectric in `:app` with a `DatabaseOpener` whose open completes only when the test releases it | constructing the full `Set<AppInitializer>` and every framework component (`NeutrodynePlaybackService`, `ManualDownloadJobService`, `DownloadActionReceiver`, `YouTubeAlertActionReceiver`, `SnapshotNowReceiver`) on the main thread while the open is pending neither throws nor blocks (lazy rule, [Application start-up](#application-start-up)); the runner completes within 5 s after release (no initializer below 100 waits for the database) |
| Navigation | Robolectric + Compose v2 rule + `StateRestorationTester` | push/pop per tab; back from a non-start root returns to Feeds; two `PodcastKey`s get different ViewModels; Library → Podcast, then Downloads, then Up next, then Library again: the podcast entry's `rememberSaveable` state and ViewModel instance survive (every tab decorated every composition); `LocalNavTab` inside an entry equals its tab; stacks restored after state restoration; sheet key renders as overlay; `pushDetail` replaces a same-class top entry only when `LocalPaneLayout.partitions ≥ 2` |
| `ProcessRole` | JVM (process name as a parameter) | `ch.lkmc.neutrodyne` → `MAIN`; `ch.lkmc.neutrodyne:ytx` and `ch.lkmc.neutrodyne.dev:ytx` (dev-tools builds) → `YTX`; `…:acra` → `ACRA`; a `/proc/self/cmdline` buffer with a trailing NUL and padding parses like the plain name |
| `:ytx` start (M0 while S7 is go, else M9a) | instrumented (`YtxProcessStartTest`, API 26 and API 36 GMDs; API 37 16 KB image nightly) | binding `YtxService` starts the `:ytx` process; in it no `AppInitializer` runs, `NeutrodyneDatabase` is never opened, no DataStore file is opened, WorkManager is never initialised, ACRA is not installed and no default-process `ContentProvider` is created (observed through an inert probe in `main` that records only after the instrumentation test has armed it — the pattern of 04's `YtxTestHooks` — never through a debug-only source set, since every published APK is a debug build); killing `:ytx` leaves the main process and a running playback untouched (04's `YtxIsolationTest` covers the engine side) |
| Initializer runner | JVM (`runInitializers` is a plain suspend function) | runs in ascending `order`; a throwing initializer is logged and later ones still run; cancellation propagates. The `:acra` early return is checked manually once (ACRA crash dialog appears, no WorkManager or session start in its process) |
| Startup gate | Robolectric | splash condition clears when `device_settings` emitted and the database is `Ready`; clears after 400 ms with a database still `Pending`; clears after 1 s when `device_settings` never emits; a deep-link route received while `Pending` is shown after the gate opens; `StartupGate` is shown and no ViewModel (feature or `PlayerViewModel`) is created while `Pending`; `NavDisplay` appears on `Ready` |
| Build types and dev-tools switch | CI `assemble` job and 09's `check-apk.sh --published`; nightly `dev-tools-build` | published `debug` APKs: package `ch.lkmc.neutrodyne`, debuggable, no `testOnly`, signer equal to `debug-cert-sha256.sh`, no LeakCanary, `PreviewActivity`, test activity, `app/src/devTools/` class or `debug-overrides`; `benchmark` APK: not debuggable, minified; the dev-tools build compiles and its unit tests pass (non-blocking) |
| Build policy | Gradle (CI `static` job) | `assertModuleGraph`, `licenseeDebug`, `verifyDependencyPolicy`, `verifyManifestPermissions`, `checkSpdxHeaders`, `checkBannedApis`, `checkPythonLicences`, `verifyBundledYtDlp`, KGP assertion; negative checks once per the [verification log](#verification-log) |
| `PythonLicencePolicy`, lockfile parser, `OpenPgpSignatureCheck` | JVM in `build-logic` | allowed and rejected SPDX expressions (`Python-2.0` and `Unicode-3.0` accepted; `GPL-2.0-only`, `LGPL-2.1-or-later`, `AGPL-3.0-only`, `MPL-2.0` on code vs data, `BSD-3-Clause OR GPL-2.0-only` with and without `elected`); lockfile/build mismatches; a good, a tampered and a wrong-key detached signature over a fixture `SHA2-256SUMS` |
| Smoke | GMD API 26, 36, 37 (16 KB) | launch, five labelled destinations, rotation, dark-mode switch, Settings → About shows version and ABI, Licences non-empty; while S7 is go, `selftest` returns from `:ytx` on API 26 and 37 |

Fixtures: none beyond inline tables; `FakeNetworkMonitor` and `TestClock` in `:core:testing`.

---

## Delivery by milestone

| Milestone | Foundation work |
|---|---|
| [M0](../PLAN.md#m0-scaffold-and-ci) | Everything in the [M0 scaffold checklist](#m0-scaffold-checklist): toolchain, catalog, convention plugins, all module stubs (`:youtube:ytdlp` with Chaquopy, `YtxService` in `:ytx` (`ping`, `selftest`), the `ProcessRole.YTX` branch and `YtxProcessStartTest` if S7 is go; no `:update:*` modules), dependency rules and policy tasks (including `checkPythonLicences`, the lockfile and `verifyBundledYtDlp` as a no-op), build types `debug` (published) and `benchmark` without flavors and without a `release` variant, the `neutrodyneDebug` signing config with the committed `signing/neutrodyne-debug.keystore` and `signing/README.md`, the dev-tools switch (`app/src/devTools/`, `DevToolsInitializer`, initializer 0), ABI splits, version `0.1.0`/`10095`, `:core:common`, `:core:model` basics, `:core:navigation`, `:core:datastore`, `:core:network` (credential-free core, base and derived clients, interceptors, DNS guard, error taxonomy, NSC), Hilt skeleton with an empty `YouTubeBindingsModule`, Nav3 host, `IntentRouter` (internal routes), process-aware `NeutrodyneApplication` (`ProcessRole`) and initializer runner, manifest subset, M0 backup rule files, About (version, ABI, statement)/Licences, ACRA wiring, CONTRIBUTING/PR template, spikes S1–S7 |
| [M1](../PLAN.md#m1-subscribe-and-ingest-rss) | `:core:database` with `SqliteDriverModule` and the S2–S4 outcomes applied; database-open initializer (100) and `StartupGate` wired to `DatabaseOpener`; `CredentialStore` replaces `CredentialLookup.None` (initializer 120); FEED and IMAGE clients in use; `SettingsRepository` implementation; `refresh-periodic` initializer (200); start-up ordering test; router: `AddPodcastKey` for direct feed URLs and `feed:`/`pcast:`/`podcast:`/`itpc:` (filters by 03) |
| [M2](../PLAN.md#m2-groups-and-group-feeds) | Channel initializers for `new_episodes` and `grp_new_episodes` (10) and per-group channel sync (140); router `SelectFeed`; `YouTubeBindingsModule` provides `StaticYouTubeCapabilitiesSource(NOT_YET_AVAILABLE)` |
| [M3](../PLAN.md#m3-import-export-and-backup) | `ExternalImportActivity` and `FileProvider` manifest entries; final backup rule XML (05); restore-check initializer (110); `backup-auto-snapshot` scheduling; `import_backup` channel |
| [M4](../PLAN.md#m4-playback-core) | Playback service, permissions and `ArtworkProvider` manifest entries; MEDIA client; `kotlinx-coroutines-guava`, `lifecycle-process`, `kotlinx-serialization-json` in `:playback:impl`; `playback`/`alerts` channels; `:playback:impl` lint config for `@UnstableApi`; `YouTubeBindingsModule` binds `YouTubeStreamResolver` → `ExternalOnlyYouTubeStreamResolver` |
| [M5](../PLAN.md#m5-playback-features-and-system-surfaces) | `MediaButtonReceiver`, Auto meta-data and `automotive_app_desc.xml`; `media3-inspector` |
| [M6](../PLAN.md#m6-downloads) | `include(":benchmark")` for 09's out-of-process system tests; UIDT service, `SystemForegroundService` override, `DownloadActionReceiver`, `RUN_USER_INITIATED_JOBS`/`FOREGROUND_SERVICE_DATA_SYNC`/`RECEIVE_BOOT_COMPLETED`; DOWNLOAD client; `download-reconcile` and `download-cleanup` initializers; `hasFragileUserData` confirmed; `permissions.txt` updated |
| [M7](../PLAN.md#m7-discovery) | API client in use; exported VIEW/SEND filters complete (03); `PODCASTINDEX_*` plumbing via `BuildInfo` |
| [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds) | `YouTubeBindingsModule` binds `NoOpYouTubeEnricher`, `UnsupportedYouTubeChannelSearch`, `NoExtractorChannelLookup` (every APK in external mode until M9a) |
| [M9](../PLAN.md#m9-youtube-playback-and-downloads-via-the-embedded-yt-dlp-engine) | **M9a:** `YtxService` gains the engine methods (its manifest entry, the `:ytx` start-up branch and `YtxProcessStartTest` are live since M0 while S7 is go; on a fallback host they arrive here); `YouTubeAlertActionReceiver` manifest entry (04's breaker notice); `YouTubeBindingsModule` split into `app/src/youtubeEngine/` (engine-backed bindings, `YtDlpEngine` as `YouTubeCapabilitiesSource` and `YouTubeEngine`) and `app/src/noYouTubeEngine/` (`AbsentYouTubeEngine`, `NOT_IN_THIS_APK`) with the `neutrodyne.youtubeEngine` switch and the nightly `no-engine-build` (09); YOUTUBE client in `:ytx` for `PyHttp` with `pinnedToFamily`, `DnsFamilyHints` in use in the main process; initializer 150 (`YtDlpEngine` capability load); vendored yt-dlp with `verifyBundledYtDlp` active, lockfile complete for the engine stack, AboutLibraries engine entries and `THIRD_PARTY_NOTICES.md`; `youtube/ytdlp/consumer-rules.pro`; packaging mode per S7 and the M9a spike. **M9b:** `tink-android` and, if the JS provider passed the spike, `quickjs-kt-android` (lockfile entry for QuickJS); `engine-update` in initializer 200; `engineManifestUrl` in use |
| [M10](../PLAN.md#m10-covers-theming-adaptive-layouts-and-accessibility) | `material-color-utilities` in `:core:designsystem`/`:core:artwork`; `profileinstaller` in `:app` and `benchmark-macro-junit4` in `:benchmark` for 09's Macrobenchmarks against the `benchmark` build type |
| [M11](../PLAN.md#m11-release-hardening-and-v10) | **M11a:** the update check in existing modules (`AppUpdateChecker`/`UpdateNotices` in `:core:domain`, state types in `:core:model`, implementation and `UpdateModule` in `:core:data`, fakes in `:core:testing`); channel `updates` (10); `app-update-check` (200; only while `updates.check_enabled` is on, never in dev-tools builds); `updates.*` keys; routes `…/open/settings/updates` and `…/open/help/install`; no manifest, permission or module change. **M11b:** `db-maintenance` initializer; `RingBufferLogSink`; final merged-manifest audit against [Platform compliance](#platform-compliance) (including P33–P35 and P40 on an API 37 device; P36–P39 no longer apply); release hygiene per 09. No baseline-profile plugin: profiles stay deferred while the published build is debuggable ([D2](../PLAN.md#3-key-decisions)) |

---

## New names introduced here

| Name | Kind | Module |
|---|---|---|
| `BuildInfo` (incl. `devTools`, `apkAbi`, `youTubeEngineBundled`, `updateManifestUrl`, `engineManifestUrl`; `isDebug` and `releasesAtomUrl` removed 2026-10-05) | data class | `:core:model` |
| `NetError`, `TlsKind` | sealed interface, enum | `:core:model` |
| `ExternalReason` (values owned by 04; M2) | enum | `:core:model` |
| `SettingsFile`, `SettingKey` (`Bool`, `Int32`, `Int64`, `Float32`, `Text`, `TextSet`, `Choice`), `AllSettingKeys` | settings typing | `:core:model` |
| `SettingsError` | sealed error type for `SettingsRepository.set` | `:core:domain` |
| `AppInitializer` | interface | `:core:common` |
| `LogSink`, `LogLevel`, `Redactor`, `LogcatSink`, `RingBufferLogSink` (M11), `DebugHttpLogInterceptor` (dev-tools builds only) | logging | `:core:common` (`LogcatSink`: `:app`; `DebugHttpLogInterceptor`: `app/src/devTools/`) |
| `NetworkStatus` | data class (interface `NetworkMonitor` placed in `:core:common`) | `:core:common` |
| `DeviceClock` | `Clock` implementation | `:app` |
| `HttpClientKind`, `@HttpClient`, `@CredentialFreeCore` (internal), `@DevToolsInterceptor` (empty multibound set outside dev-tools builds) | qualifier | `:core:network` |
| `IdentityEncodingInterceptor`, `LocalNetworkGuardDns`, `LocalNetworkGuardInterceptor`, `LocalNetworkUnsupportedException`, `DnsFamilyHints`, `FamilyHintDns`, `pinnedToFamily`, `NetErrorClassifier`, `ConnectivityNetworkMonitor`, `CredentialLookup`, `Origin`, `NetworkModule` | networking | `:core:network` |
| `@SettingsDataStore` | qualifier | `:core:datastore` |
| `SqliteDriverModule`, `TestSqliteDriverModule` | Hilt modules | `:core:database`, `:app/src/test` |
| `CoreModule`, `YouTubeBindingsModule`, `WorkEntryPoint`, `ImageEntryPoint`, `ArtworkProviderEntryPoint` | Hilt modules / entry points | `:app` (`ArtworkProviderEntryPoint`: `:core:artwork`) |
| `ProcessRole` (`MAIN`, `YTX`, `ACRA`) | enum + classifier | `:app` |
| `StaticYouTubeCapabilitiesSource`, `AbsentYouTubeEngine` | external-only implementations (names proposed here; 04 owns the classes) | `:youtube:impl` |
| `TopLevelKey`, `LocalAppNavigator`, `NdSceneMetadata` | navigation | `:core:navigation` |
| `NavigationState`, `NeutrodyneNavHost`, `rememberTabLocalNavEntryDecorator`, `NdBottomSheetSceneStrategy`, `NdDialogSceneStrategy`, `IntentRouter`, `Route`, `StartupViewModel`, `StartupState`, `StartupGate` | navigation / start-up | `:app` |
| `UiText` (`Res`, `Plural`, `Raw`), `UserMessage` | UI-state helpers (used by feature ViewModels and screens) | `:core:ui` |
| `assertModuleGraph` rules file `ModuleRules.kt`; tasks `verifyDependencyPolicy`, `verifyManifestPermissions`, `checkSpdxHeaders`, `checkBannedApis`; `PythonLicencePolicy`, `OpenPgpSignatureCheck` | build | `build-logic` |
| Tasks `checkPythonLicences`, `verifyBundledYtDlp`, `shimTest` (Gradle side; test content 04) | build | `:youtube:ytdlp` |
| Gradle properties `neutrodyne.youtubeEngine`, `neutrodyne.engineManifestUrl`, `neutrodyne.devTools` (never committed); `BuildConfig.YOUTUBE_ENGINE`, `BuildConfig.ENGINE_MANIFEST_URL`, `BuildConfig.DEV_TOOLS` | build | `gradle.properties`, `:app` |
| Build type `benchmark` (non-debuggable, profileable, R8; never published); signing config `neutrodyneDebug` | build | `:app` |
| Dev-tools build (`debug` with `-Pneutrodyne.devTools=true`, application ID `ch.lkmc.neutrodyne.dev`); `DevToolsInitializer` (initializer order 0) | build / start-up | `:app` (`app/src/devTools/`) |
| `compose-stability.conf`, `app/policy/permissions.txt`, `app/src/main/keepRules/app.keep`, `app/src/youtubeEngine/`, `app/src/noYouTubeEngine/`, `app/src/devTools/` (dev-tools builds only), `signing/neutrodyne-debug.keystore` and `signing/README.md` (content and generation: 09), `scripts/ci/debug-cert-sha256.sh` (owned by 09), `youtube/ytdlp/consumer-rules.pro`, `youtube/ytdlp/python-components.lock`, `third_party/chaquopy-maven/` (S7 fallback only), `playback/impl/lint.xml`, `THIRD_PARTY_NOTICES.md`, `CONTRIBUTING.md` | files | repo |
| Platform-compliance rows P33–P40 (P36–P39 not applicable since 2026-10-05) | checklist IDs | this document |

---

## Open questions

1. Resolved by [D68](../PLAN.md#3-key-decisions): option (b) — rule 8 stands, `:feeds` produces `ShowNotesDocument` and `:core:data` maps it 1:1 into the `:core:model` mirror `ShowNotes`.
2. Resolved: rules 10–12 confirmed by PLAN [5.1](../PLAN.md#51-module-graph); rule 13 now states the test-fixture split for pure-JVM modules (09).
3. Resolved (PLAN [5.1](../PLAN.md#51-module-graph)): the `NetworkMonitor` interface lives in `:core:common`, `ConnectivityNetworkMonitor` in `:core:network`.
4. Resolved (PLAN [5.1](../PLAN.md#51-module-graph)): `YouTubeCapabilitiesSource` (M2) and the `YouTubeStreamResolver` contract with `ExternalOnlyYouTubeStreamResolver` (M4) land early and are bound in `:app`'s [YouTube bindings](#youtube-bindings).
5. Resolved (PLAN [5.1](../PLAN.md#51-module-graph)): `IpFamily` lives in `:core:model`, and so does `ExternalReason` (M2; used by `:youtube:api`, `:playback:api` and `:core:ui`; 04 owns the values).
6. Obsolete (2026-10-05): core-library desugaring left with NewPipe Extractor ([D3](../PLAN.md#3-key-decisions) amended); the build now fails if it is enabled.
7. **Safer Intents (`intentMatchingFlags`).** Opt-in on Android 16 and not a target-37 change ([Android 17 behaviour changes](https://developer.android.com/about/versions/17/behavior-changes-17)); not adopted in v1. Under enforcement, explicit `VIEW neutrodyne://open/…` intents to `MainActivity` (notifications, 05's `ExternalImportActivity` hand-off) would no longer match its filters. Planned fix when it becomes default: add `<intent-filter><action VIEW/><category DEFAULT/><data scheme="neutrodyne" host="open"/></intent-filter>` to `MainActivity`; this exposes nothing new because `MainActivity` is exported anyway and routes only navigate. Revisit at the first targetSdk bump after 37.
8. Obsolete (2026-10-05): no app bundles are built (GitHub Releases ships APKs only, [PO-2](../PLAN.md#po-2-distribution-channels)); `bundle.language.enableSplit` was removed.
9. Resolved as a [PO-18](../PLAN.md#48-further-product-owner-decisions) follow-up: the PO names the GitHub owner; `OWNER` stays a placeholder until then.
10. Obsolete (2026-10-05): there is no `play` build; every APK's About links to `BuildInfo.repoUrl`.
11. **Mechanical uncertainties** resolved by M0/M9a checks above: Chaquopy on the build-logic classpath vs a versioned `plugins {}` entry (catalog rule 3); Chaquopy's Gradle configuration names and runtime coordinates for `checkPythonLicences`, and whether its runtime reaches `debugRuntimeClasspath` (Licensee); library-module `abiFilters` under the app's ABI splits; Chaquopy consumer keep rules and configuration-cache compatibility; AGP 9 names of the `splits.abi` DSL and of `sourceSets…kotlin.srcDir` under built-in Kotlin; conditional AboutLibraries config for the engine entries; AboutLibraries plugin ID and `aboutlibraries-core` having no Compose dependency; the `room3 { }` extension name for `schemaDirectory`; the `androidx.core` receiver-permission name; since 2026-10-05 also: disabling `:app`'s `release` variant and `initWith`/`matchingFallbacks` for `benchmark` under AGP 9.4.1, the AGP 9 names of the debug split outputs, that a command-line `assembleDebug` sets no `testOnly`, whether `SingleArtifact.MERGED_MANIFEST` carries the injected `android:debuggable`, build-type matching of `com.android.test` (09), and whether GitHub push protection flags the committed PKCS12 keystore. (Obtainium's package IDs no longer matter: the app has no `<queries>` entry for them.)
12. **AGP 9.2.x as a Chaquopy fallback** (architect, after S7). If released Chaquopy 17.0.0 fails under AGP 9.4.1 but a self-built master is undesirable, AGP 9.2.x sits inside Chaquopy 17.0.0's documented range (7.3–9.2), Compose 1.12's minimum (9.2) and Kotlin 2.4.20's tested range (to 9.3.1). It would need a [D4](../PLAN.md#3-key-decisions) amendment and gives up AGP 9.3+ features this document uses (`optimization {}` DSL, `keepRules` source set). Default: no — self-built master first, per [D72](../PLAN.md#3-key-decisions)'s order.
13. **Python assets in the `armeabi-v7a` APK.** ABI splits filter only `lib/<abi>/`; Chaquopy's assets (stdlib `.pyc`, the vendored yt-dlp, the shim, and the `lib-dynload` sets of both 64-bit ABIs) probably also land in the `armeabi-v7a` APK, which cannot run them (≈ 12–13 MB, Unverified). S7 measures it against its `armeabi-v7a` criterion. Options: accept (only while the published debug APK stays within PB13's 50 MB; the dead weight is counted there), an ABI flavor dimension ([D2](../PLAN.md#3-key-decisions) fallback), or a variant-API transform that strips the assets from that split (Unverified feasibility). Default: accept if within budget.
14. Resolved 2026-10-05 in 09 (its open question 24). **Compose test activities without `ui-test-manifest` in the published app**. `ui-test-manifest` may no longer be a `debugImplementation`, because `debug` is the published build and its `ComponentActivity` must never ship ([Convention plugins](#convention-plugins)). Unverified: (a) that library modules' Robolectric Compose tests find that activity with `testImplementation(ui-test-manifest)`; (b) how `:app`'s instrumented tests host composables — proposal `createAndroidComposeRule<MainActivity>()`, because an activity merged only into the test APK may not start in the process of the app under test. 09 adopted (a) `testImplementation` (its M1 check still pending) and (b) the proposal ([09 open questions](09-quality-and-release.md#open-questions) 24).
15. Resolved 2026-10-05 (PO-31, PO-35): there are no `:update:*` modules (the update check lives in `:core:domain`, `:core:model` and `:core:data`, [D13](../PLAN.md#3-key-decisions)), no install permission, and no release key or key ceremony; the published build type is `debug` with the committed keystore ([Build variants and ABIs](#build-variants-and-abis)).

---

## Sources

All checked 2026-10-04 by the research behind this plan unless marked otherwise (entries marked 2026-10-05 were checked or re-checked for the product owner's decisions of that day).

Toolchain and build:
- Kotlin releases — https://kotlinlang.org/docs/releases.html
- Kotlin Gradle plugin compatibility (KGP 2.4.20 tested to AGP 9.3.1 / Gradle 9.7.0) — https://kotlinlang.org/docs/gradle-configure-project.html
- Compose compiler Gradle plugin — https://developer.android.com/develop/ui/compose/compiler
- AGP releases and requirements — https://developer.android.com/build/releases/gradle-plugin · https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml
- AGP 9.0 breaking changes (built-in Kotlin, new DSL, KGP 2.2.10 runtime dependency, targetSdk default, R8 defaults) — https://developer.android.com/build/releases/agp-9-0-0-release-notes
- AGP 9.3 / 9.2 / 9.1 release notes — https://developer.android.com/build/releases/agp-9-3-0-release-notes · https://developer.android.com/build/releases/agp-9-2-0-release-notes · https://developer.android.com/build/releases/agp-9-1-0-release-notes
- Built-in Kotlin and kapt — https://developer.android.com/build/migrate-to-built-in-kotlin
- AGP 9.4 `CommonExtension` — https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/CommonExtension
- R8 / shrink code (AGP 9.3+ `optimization {}` DSL, `keepRules` source set with `.keep` files, default rules included), past AGP notes — https://developer.android.com/build/shrink-code · https://developer.android.com/build/releases/past-releases/agp-8-0-0-release-notes
- Android Studio releases — https://developer.android.com/studio/releases · https://developer.android.com/build/releases/about-agp
- Gradle releases — https://gradle.org/releases/ · https://services.gradle.org/versions/current
- KSP — https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-api/maven-metadata.xml · https://github.com/google/ksp/releases
- Build types and signing (checked 2026-10-05, PO-35): build variants — `debug` is debuggable and signed with a generic debug keystore by default, `signingConfigs` per build type, `initWith`, `matchingFallbacks`, filter variants with `beforeVariants`/`enable = false`, library resources and manifests have the lowest merge priority — https://developer.android.com/build/build-variants · app signing (the debug certificate is insecure by design) https://developer.android.com/studio/publish/app-signing · ABI splits apply to the configured variants, output `modulename-ABI-buildvariant.apk`, universal APK only on request https://developer.android.com/build/configure-apk-splits · `ApplicationBuildType.isProfileable` https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/ApplicationBuildType · `<profileable>` https://developer.android.com/guide/topics/manifest/profileable-element · Gradle property precedence (command line, `GRADLE_USER_HOME`, project) https://docs.gradle.org/current/userguide/build_environment.html
- Performance of the debuggable build (checked 2026-10-05): Macrobenchmark (non-debuggable, profileable target; `benchmark` build type with `initWith` and the debug signing config; ProfileInstaller ≥ 1.3 in the target; `androidx.benchmark.suppressErrors`) https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview · Baseline Profiles overview https://developer.android.com/topic/performance/baselineprofiles/overview · ART: debuggable relies on JIT (2017) https://android.googlesource.com/platform/art/+/a0619e2%5E%21/, confirmed for current ART Service ("We force vmSafeMode on debuggable apps as well: the runtime ignores their compiled code") https://android.googlesource.com/platform/art/+/refs/heads/main/libartservice/service/java/com/android/server/art/Dexopter.java · CheckJNI, JDWP and ptrace for debuggable apps https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ProcessList.java · CheckJNI behaviour (on for debuggable apps and emulators; aborts the VM) https://developer.android.com/training/articles/perf-jni · Compose performance (debug mode cost) https://developer.android.com/develop/ui/compose/performance

Libraries:
- Compose BOM mapping — https://developer.android.com/develop/ui/compose/bom/bom-mapping
- Compose UI 1.12 requirements — https://developer.android.com/jetpack/androidx/releases/compose-ui
- Material 3 releases and Expressive status — https://developer.android.com/jetpack/androidx/releases/compose-material3 · https://dl.google.com/android/maven2/androidx/compose/material3/material3-android/1.5.0-alpha29/material3-android-1.5.0-alpha29.pom
- Material 3 Adaptive — https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive
- Material icons deprecation — https://developer.android.com/develop/ui/compose/graphics/images/material
- Navigation 3 (`entries` overload, `sceneStrategies` list since 1.1, `DialogSceneStrategy` and typed metadata since 1.1, `NavigationBackHandler`, 1.2 deep-link API; checked 2026-10-05) — https://developer.android.com/jetpack/androidx/releases/navigation3 · https://developer.android.com/guide/navigation/navigation-3/custom-layouts · https://developer.android.com/guide/navigation/navigation-3/animate-destinations · https://github.com/android/nav3-recipes
- Navigation 2 maintenance mode — https://developer.android.com/jetpack/androidx/releases/navigation
- Lifecycle 2.11 — https://developer.android.com/jetpack/androidx/releases/lifecycle
- Activity 1.13 — https://developer.android.com/jetpack/androidx/releases/activity
- core 1.19.1 — https://developer.android.com/jetpack/androidx/releases/core
- Dagger/Hilt — https://github.com/google/dagger/releases · androidx.hilt https://developer.android.com/jetpack/androidx/releases/hilt · entry points before the test component (`@EarlyEntryPoint`) https://dagger.dev/hilt/early-entry-point
- Room 3 — https://developer.android.com/jetpack/androidx/releases/room3 · Room migrations https://developer.android.com/training/data-storage/room/migrating-db-versions
- SQLite drivers — https://developer.android.com/kotlin/multiplatform/sqlite · https://developer.android.com/jetpack/androidx/releases/sqlite · framework SQLite by API https://developer.android.com/reference/android/database/sqlite/package-summary
- DataStore — https://developer.android.com/jetpack/androidx/releases/datastore · file path https://github.com/androidx/androidx/blob/androidx-main/datastore/datastore/src/androidMain/kotlin/androidx/datastore/DataStoreFile.android.kt
- WorkManager — https://developer.android.com/jetpack/androidx/releases/work
- Media3 — https://developer.android.com/jetpack/androidx/releases/media3 · https://github.com/androidx/media/blob/release/RELEASENOTES.md
- Media3 `@UnstableApi` lint opt-in — https://developer.android.com/reference/androidx/media3/common/util/UnstableApi
- AndroidX default minSdk — https://developer.android.com/jetpack/androidx/versions
- OkHttp — https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml · https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md · no `Dns` lookup for IP literals (checked 2026-10-05) https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RouteSelector.kt
- Coil — https://coil-kt.github.io/coil/changelog/
- MaterialKolor — https://github.com/jordond/MaterialKolor · https://repo1.maven.org/maven2/com/materialkolor/
- Reorderable — https://github.com/Calvin-LL/Reorderable/blob/main/LICENSE
- Licensee — https://github.com/cashapp/licensee
- detekt compatibility — https://detekt.dev/docs/introduction/compatibility/
- ACRA — https://www.acra.ch/docs/Setup · https://www.acra.ch/docs/Senders
- Robolectric — https://github.com/robolectric/robolectric/releases
- Roborazzi — https://github.com/takahirom/roborazzi
- GitHub Actions releases (checked 2026-10-05) — https://github.com/actions/setup-python/releases · https://github.com/actions/attest/releases · https://github.com/actions/deploy-pages/releases

YouTube engine and signatures (checked 2026-10-05):
- Chaquopy — repository, licence (MIT) and master `VERSION.txt` 17.1.0 https://github.com/chaquo/chaquopy · https://raw.githubusercontent.com/chaquo/chaquopy/master/VERSION.txt · documentation (17.0: AGP 7.3–9.2, one module per app, Python ≥ 3.12 64-bit only, `buildPython` minor version, `pyc`, `chaquopy {}` DSL) https://chaquo.com/chaquopy/doc/current/android.html · FAQ (ABI splits "won't help much") https://chaquo.com/chaquopy/doc/current/faq.html · Maven metadata (latest 17.0.0, 2025-11-30) https://repo1.maven.org/maven2/com/chaquo/python/gradle/maven-metadata.xml · runtime builds (3.14.0-0, 3.13.9-0, …) https://repo1.maven.org/maven2/com/chaquo/python/target/maven-metadata.xml
- CPython on Android, licence and versions — https://docs.python.org/3/using/android.html · https://www.python.org/downloads/android/ · https://docs.python.org/3/license.html · https://devguide.python.org/versions/
- yt-dlp — licensing https://github.com/yt-dlp/yt-dlp#licensing · release files and channels https://github.com/yt-dlp/yt-dlp#release-files · signing key https://github.com/yt-dlp/yt-dlp/blob/master/public.key · embedding https://github.com/yt-dlp/yt-dlp#embedding-yt-dlp · stable 2026.08.19 https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19 · PyInstaller licences (why the executables are never shipped) https://github.com/yt-dlp/yt-dlp/blob/master/THIRD_PARTY_LICENSES.txt · yt-dlp-ejs https://github.com/yt-dlp/ejs
- Tink — https://github.com/tink-crypto/tink-java · `tink-android` 1.23.0 https://repo1.maven.org/maven2/com/google/crypto/tink/tink-android/maven-metadata.xml · Ed25519 in `java.security.Signature` from API 33 https://developer.android.com/reference/java/security/Signature
- quickjs-kt — https://github.com/dokar3/quickjs-kt · 1.0.15 https://repo1.maven.org/maven2/io/github/dokar3/quickjs-kt-android/maven-metadata.xml · QuickJS https://bellard.org/quickjs/
- GPL components that must never ship — youtubedl-android https://github.com/yausername/youtubedl-android · bgutil-ytdlp-pot-provider https://github.com/Brainicism/bgutil-ytdlp-pot-provider

Platform:
- Android 15 behaviour changes (targeting / all apps: no installs below targetSdk 24, checked 2026-10-05) — https://developer.android.com/about/versions/15/behavior-changes-15 · https://developer.android.com/about/versions/15/behavior-changes-all
- Android 10 behaviour changes (no `execve()` from the app's home directory; checked 2026-10-05) — https://developer.android.com/about/versions/10/behavior-changes-10
- Android 16 behaviour changes (targeting / all apps) — https://developer.android.com/about/versions/16/behavior-changes-16 · https://developer.android.com/about/versions/16/behavior-changes-all
- Android 17 behaviour changes (targeting / all apps; read-only `System.load` checked 2026-10-05), background audio — https://developer.android.com/about/versions/17/behavior-changes-17 · https://developer.android.com/about/versions/17/behavior-changes-all · https://developer.android.com/about/versions/17/changes/bg-audio
- Android 17 release — https://en.wikipedia.org/wiki/Android_17 · https://developer.android.com/about/versions/17
- Android 14 behaviour changes (network-state permission for job constraints; read-only dynamic code loading, checked 2026-10-05) — https://developer.android.com/about/versions/14/behavior-changes-14
- FGS service types and timeouts — https://developer.android.com/develop/background-work/services/fgs/service-types · https://developer.android.com/develop/background-work/services/fgs/timeout
- Background FGS-start restrictions — https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- UIDT jobs — https://developer.android.com/develop/background-work/background-tasks/uidt · https://developer.android.com/reference/android/app/job/JobService
- Data-transfer guidance — https://developer.android.com/about/versions/15/changes/datasync-migration
- Battery: restricted bucket, standby, doze exemption policy — https://developer.android.com/develop/background-work/background-tasks/optimize-battery · https://developer.android.com/topic/performance/appstandby · https://developer.android.com/training/monitoring-device-state/doze-standby
- 16 KB pages — https://developer.android.com/guide/practices/page-sizes · https://developer.android.com/16kb-page-size
- Notification permission — https://developer.android.com/develop/ui/views/notifications/notification-permission
- Per-app languages — https://developer.android.com/guide/topics/resources/app-languages
- Network security config (cleartext, user CAs, `<certificateTransparency>` opt-in/opt-out per domain, `<debug-overrides>` applied whenever `android:debuggable` is true; checked 2026-10-05) — https://developer.android.com/privacy-and-security/security-config
- Local network permission (TCP to LAN hosts typically times out, UDP `EPERM`, `.local`, LAN DNS exemption; checked 2026-10-05) — https://developer.android.com/privacy-and-security/local-network-permission
- Auto Backup — https://developer.android.com/identity/data/autobackup
- `hasFragileUserData`; `android:debuggable` (debuggable even on user builds) and `android:testOnly` (adb-only installs; added by Android Studio on Run), checked 2026-10-05 — https://developer.android.com/guide/topics/manifest/application-element
- Debuggable apps over ADB (checked 2026-10-05): AOSP `run-as` rejects non-debuggable packages https://android.googlesource.com/platform/system/core/+/refs/heads/main/run-as/run-as.cpp · Android 12: `adb backup` excludes app data for apps targeting 31+ unless they are debuggable https://developer.android.com/about/versions/12/behavior-changes-12
- `<data>` matching rules — https://developer.android.com/guide/topics/manifest/data-element
- Per-source "install unknown apps" grant (`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`, P36; checked 2026-10-05) — https://developer.android.com/reference/android/provider/Settings. The `PackageInstaller`, `SessionParams`, `InstallConstraints` and AOSP `PackageInstallerSession` sources were dropped on 2026-10-05 with the in-app installer (PO-31)
- API distribution — https://apilevels.com/

Licensing and policy:
- Unlicense — https://en.wikipedia.org/wiki/Unlicense
- SPDX licence identifiers (expressions with `OR`, `WITH LLVM-exception`, `blessing`) — https://spdx.org/licenses/
