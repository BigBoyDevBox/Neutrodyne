# Neutrodyne: Android platform and project foundation (research notes)

Research area: toolchain, libraries, architecture, SDK levels, module layout, platform rules, licensing.
All versions and dates below were checked on **2026-10-04** against the sources listed in
"Verified versions & facts". Where something could not be verified it is marked **UNVERIFIED**.

---

## Recommendation

### The stack in one table

| Concern | Choice for Neutrodyne | Version (2026-10-04) |
|---|---|---|
| Language | Kotlin (K2), Compose compiler plugin + serialization plugin at the same version | **2.4.20** |
| Build | Android Gradle Plugin with **built-in Kotlin** (no `org.jetbrains.kotlin.android`), new DSL | **AGP 9.4.1** (fallback 9.3.3, see pitfalls) |
| Gradle | Gradle wrapper, configuration cache + build cache on | **9.7.1** |
| JDK | JDK 17 minimum (AGP requirement). Run Gradle on JDK 21 LTS, emit bytecode for JVM 17 | 17 / 21 |
| Annotation processing | KSP2 only (no kapt anywhere) | **KSP 2.3.12** |
| UI | Jetpack Compose via BOM; Material 3 **stable**; Material 3 Adaptive for phone, tablet and foldable layouts | **BOM 2026.09.00** (Compose 1.12.1, material3 1.4.0, adaptive 1.3.0) |
| Material 3 Expressive | Not at launch. It exists only in `material3` **1.5.0-alpha29**. We wrap the design system so we can adopt it once 1.5.0 is stable | — |
| Navigation | **Navigation 3** with `lifecycle-viewmodel-navigation3` and `adaptive-navigation3` | **navigation3 1.2.0**, lifecycle 2.11.0 |
| DI | **Hilt** (Dagger) via KSP, plus `androidx.hilt` for Compose ViewModels and WorkManager | **Dagger/Hilt 2.60.1**, androidx.hilt 1.4.0 |
| Database | **Room 3** (`androidx.room3`) with `BundledSQLiteDriver`, Paging integration | **room3 3.0.3**, sqlite-bundled 2.7.1, paging 3.5.1 |
| Preferences | Preferences DataStore. Anything relational stays in Room | **datastore 1.2.1** |
| HTTP | **OkHttp 5**, one shared client for feeds, images (Coil), streaming (Media3 `OkHttpDataSource`) and downloads | **5.5.0** |
| JSON | kotlinx.serialization (JSON APIs, Nav3 keys, typed settings) | **1.11.0** |
| XML (RSS/Atom/OPML) | Hand-written **XmlPullParser** streaming parser in a pure-JVM module. Platform parser on device, kxml2 in JVM tests. OPML export uses `XmlSerializer` | platform + kxml2 2.3.0 (test only) |
| Images | **Coil 3** (`coil-compose` + `coil-network-okhttp`) | **3.6.3** |
| Concurrency | kotlinx.coroutines + Flow; `StateFlow` UI state; injected dispatchers and app scope | **1.11.0** |
| Background | WorkManager for feed refresh and deferrable work; Media3 `MediaSessionService` for playback | work 2.12.0, media3 1.11.1 |
| Architecture | UDF / MVVM: Compose screen to ViewModel (`StateFlow<UiState>`) to repositories (Room is the single source of truth). Use cases **only** for logic that spans several repositories | — |
| SDK levels | **minSdk 26**, **compileSdk 37**, **targetSdk 37** (Play's floor is 36) | — |
| Modules | `app`, `build-logic`, `core:*`, `feature:*`, `feeds`, `youtube:{api,impl}`, `playback:{api,impl}`, `download:{api,impl}` | see Technical detail |
| IDE | Android Studio Rabbit 1 (2026.2.1), which supports AGP 7.1 to 9.4 | — |

### Why, briefly

* **Toolchain.** AGP 9 is the only current line. It enables built-in Kotlin and the new DSL by default, and AGP 10 removes the opt-outs. A greenfield project should start on built-in Kotlin and the new variant API so there is nothing to migrate later. Compose 1.12 (the current BOM) already **requires compileSdk 37 and AGP ≥ 9.2.0**, so AGP 8.x is ruled out.
  * AGP 9.4.1 is the current stable release. Android Studio Rabbit 1 supports it.
  * One caveat: JetBrains' compatibility table lists Kotlin 2.4.20 as tested with AGP up to 9.3.1 and Gradle up to 9.7.0. Newer versions "might" produce deprecation warnings. That is why Gradle is pinned at **9.7.1** rather than 9.8.0, and why AGP 9.3.3 is the documented fallback.
* **Compose + Material 3 stable.** The 1.4.0 stable branch dropped every `@ExperimentalMaterial3ExpressiveApi` API (1.4.0-beta01, July 2025). Expressive lives on in 1.5.0-alpha, which has now shipped 29 alphas over about 14 months and still makes source-breaking changes (alpha29 changed the `Slider` signature).
  * Shipping a v1 on an alpha artifact that changes the whole app's component set is avoidable risk.
  * We isolate visual decisions in `core:designsystem` so that adopting Expressive later is a contained change. The open questions ask whether the product owner wants Expressive at launch anyway.
* **Navigation 3.** It has been stable since Nov 2025. 1.2.0 (23 Sep 2026) adds a deep-link API and a result API.
  * The Navigation 2.x release page now carries a "maintenance mode … new features are not planned" caution.
  * Nav3's user-owned back stack (`SnapshotStateList<NavKey>`) is a natural fit for a podcast app's persistent mini-player plus list-detail panes.
  * `adaptive-navigation3` gives `ListDetailSceneStrategy` for group to episode-feed on tablets and foldables. Android 16 and 17 force resizability on ≥600dp screens anyway.
* **Hilt.** It has first-party integrations for every Android entry point Neutrodyne needs:
  * `@AndroidEntryPoint` on the Media3 `MediaSessionService`;
  * `@HiltWorker` for WorkManager;
  * `hiltViewModel()` with assisted injection for Nav3 keys;
  * an official Nav3 "Hilt modularized" recipe.
  * It is compile-time checked, and it is the DI framework every Android doc and sample assumes. Metro (1.x stable since Apr 2026) is the credible alternative and is noted below.
* **Room 3.** It has been stable since 1 Jul 2026, is coroutine- and Kotlin-first, and is the forward path. It adds FTS5 support, custom DAO return types and `WITHOUT ROWID`. Starting a new app on Room 2.x would mean a package-rename migration later.
  * `BundledSQLiteDriver` gives every device the same modern SQLite. Framework SQLite on API 26 is 3.18, which lacks window functions (3.25+) and SQL `UPSERT` (3.24+). Those are useful for "latest N episodes per podcast in a group" queries.
* **OkHttp over Ktor.** The app is Android-only. Media3 streams through `OkHttpDataSource`, Coil has `coil-network-okhttp`, and downloads need byte-range control. One `OkHttpClient` (one connection pool, one cache, one User-Agent) serves all of them. Ktor would add an abstraction layer with no multiplatform payoff.
* **XmlPullParser.** Podcast feeds are large, often malformed (undefined HTML entities, wrong encodings) and use many namespaces (`itunes:`, `podcast:`, `media:`, `content:`, `atom:`, `psc:`, `googleplay:`). A streaming pull parser that we control is the most robust and fastest option and adds no runtime dependency. A pure-JVM parser module keeps the hundreds of fixture tests fast.
* **minSdk 26.**
  * The AndroidX default floor is now **24**; WorkManager 2.12 and Navigation 2.10 already require it.
  * 26 costs about 0.5 percentage points of reach over 24 (Statcounter data, see below).
  * In return it removes pre-O branches: notification channels always exist, adaptive icons, `java.time` without desugaring, `startForegroundService` semantics.
  * 29 or 31 would shrink the QA matrix further but give up about 5 and 18 points of reach respectively.
* **targetSdk 37.**
  * Play requires 36 for new apps and updates from 31 Aug 2026.
  * Neutrodyne will realistically ship in 2027, by which time 37 will almost certainly be required (UNVERIFIED: not yet announced; this follows the yearly pattern).
  * The Android 17 changes that matter to us are things we want to design for from day one, not retrofit:
    * background-audio hardening (a while-in-use FGS is required);
    * the orientation and resizability opt-out is removed on large screens;
    * Certificate Transparency is on by default;
    * the local-network permission.

### Risks that could change the overall plan

1. **Material 3 Expressive.** If the product owner wants it at launch, we must depend on `material3:1.5.0-alpha` and accept API churn.
2. **Toolchain matrix.** Kotlin 2.4.20, AGP 9.4.x and Gradle 9.7 sit slightly outside JetBrains' tested AGP range. A scaffolding spike must confirm a clean build. Fallback: AGP 9.3.3.
3. **Licensing.** If the YouTube module uses NewPipeExtractor (**GPL-3.0**), the distributed APK as a whole is GPL-3.0. That is legal with an Unlicense codebase, but it is a product decision.
4. **Android 17 background-audio hardening.** "Auto-play on Bluetooth connect", "resume after reboot" and alarm-style features cannot be built the naive way.
5. **Android 16 job quotas** now apply to jobs running alongside a foreground service. Downloads scheduled in WorkManager while audio is playing can be stopped. The download design must account for this (user-initiated data transfer jobs or a dedicated FGS).

---

## Options considered

### 1. Build toolchain

| Option | Pros | Cons |
|---|---|---|
| **AGP 9.4.1 + Gradle 9.7.1 + Kotlin 2.4.20 + KSP 2.3.12** (chosen) | Latest stable everything. Max API 37. Supported by current Studio (Rabbit 1). Coil 3.6.3 already fixed an AGP 9.4/R8 issue, so the ecosystem is on it | AGP 9.4 is outside KGP 2.4.20's *tested* AGP range (≤ 9.3.1). Expect warnings at worst |
| AGP 9.3.3 + Gradle 9.7.x + Kotlin 2.4.20 | Within JetBrains' tested matrix (9.3.x patch). Supports API 37 | One minor version behind |
| AGP 8.13 + kotlin-android plugin | Familiar | Compose 1.12 requires AGP ≥ 9.2. Max API 36.1. Would need a migration anyway before AGP 10 |
| Kotlin 2.4.10 instead of 2.4.20 | Bug-fix release, older | KGP 2.4.0–2.4.10 is tested only up to AGP 9.1.0 / Gradle 9.5.0, which is worse for our AGP |

Decision: AGP 9.4.1. Re-evaluate when Kotlin 2.4.21 or 2.5.0 publishes an updated table. Renovate or Dependabot handles bumps.

### 2. UI toolkit, Material 3 and Expressive

| Option | Pros | Cons |
|---|---|---|
| **Compose BOM 2026.09.00, material3 1.4.0 stable** (chosen) | Stable, no opt-ins. Includes `material3-adaptive-navigation-suite` 1.4.0 (bottom bar ↔ rail ↔ drawer) | No Expressive components (wavy progress, button groups, floating toolbar, expressive motion) |
| Override `material3` to 1.5.0-alpha29 | Expressive theme, `ButtonGroup`, `FloatingToolbar`, `WavyProgressIndicator`, FAB menu, and expressive list items. Most are already "graduated" (non-experimental) *inside* the alpha | Alpha artifact app-wide (you cannot isolate one version per module). Ongoing source breaks (alpha29: `Slider` `onValueChange` became required and moved). `MaterialShapes` and `LoadingIndicator` were reverted to experimental |
| Views / XML + MDC | — | No reason for a 2026 greenfield app |

Cover-forward theming: use **dynamic colour** on API 31+ (`dynamicLightColorScheme`/`dynamicDarkColorScheme`). Optionally derive per-podcast accent schemes from cover art with **MaterialKolor 5.0.1 (MIT)** or `androidx.palette`. Fall back to a static brand scheme below API 31.

Icons: do **not** use `material-icons-extended`. It is frozen at 1.7.8, and Google now says it is "no longer maintained or recommended". Import the specific Material Symbols you need as vector drawables.

### 3. Navigation

| Option | Status | Fit |
|---|---|---|
| **Navigation 3 1.2.0** (chosen) | Stable since 1.0.0 (19 Nov 2025). 1.1.0 added shared elements between scenes, `SceneDecoratorStrategy` and a metadata DSL. 1.2.0 added deep links (`DeepLinkMatcher`, `BackStackMatcher`), `ResultEventBus` and `NavigationBackHandler` | Back stack is plain state. Typed `@Serializable` keys. `ListDetailSceneStrategy` / `SupportingPaneSceneStrategy` from `adaptive-navigation3` 1.3.0. Official recipes for Hilt, Koin and Metro modularisation, multiple back stacks and deep links |
| Navigation Compose 2.10.2 | Stable, but the release page carries a "maintenance mode … only critical fixes" caution. Still got predictive-back transition overloads in 2.10.0 | Graph DSL. Awkward for adaptive panes and multiple top-level back stacks |

### 4. Dependency injection

| Option | Version / status | Pros | Cons |
|---|---|---|---|
| **Hilt (Dagger)** (chosen) | 2.60.1 (6 Jul 2026). 2.59+ supports and **requires** AGP 9 and Gradle 9.1+. 2.60 min SDK 23. androidx.hilt 1.4.0 (1 Jul 2026) | Compile-time graph validation. `@AndroidEntryPoint` for Activity and Service (Media3 service). `@HiltWorker`. `hiltViewModel(creationCallback=…)` assisted injection for Nav3 key arguments. `rememberHiltViewModelFactory()`. Nav3 Hilt recipe. Largest body of docs and samples | KSP codegen adds build time. Annotation-heavy. Android-only (irrelevant here) |
| Metro | 1.0.0 (27 Apr 2026), 1.4.5 (24 Sep 2026). Apache-2.0. Kotlin compiler plugin. Supports Kotlin 2.3.0 → 2.5-dev | Fast builds (no KSP). Dagger + Anvil-style aggregation (`@ContributesTo`). MetroX Android, ViewModel and ViewModel-Compose artifacts. Nav3 recipe exists | Compiler plugins are coupled to Kotlin compiler versions, so every Kotlin bump waits on Metro. Smaller ecosystem. No first-party WorkManager or Service integration equivalent to Hilt's (UNVERIFIED in detail). The docs warn about Kotlin 2.3.0/2.3.10 bugs |
| Koin | 4.2.2 (15 Jun 2026). Koin Compiler Plugin 1.0.0-RC1 adds compile-time safety (aligned with 4.2.1) | Simple DSL. Nav3 recipe. `koinViewModel()` | Runtime resolution by default. Compile safety plugin was only RC at last check. Service-locator style hides dependencies |
| Manual DI | — | No codegen | Painful for Services, Workers and ViewModels with arguments across about 20 modules |

### 5. Persistence

| Option | Pros | Cons |
|---|---|---|
| **Room 3.0.3 + BundledSQLiteDriver** (chosen) | Stable 1 Jul 2026. Kotlin codegen and KSP-only (fits our no-kapt rule). All DAO functions are `suspend` or reactive. Flow-based `InvalidationTracker`. `@Fts5`. `@DaoReturnTypeConverters` (Paging via `room3-paging`). Default parameter values. `WITHOUT ROWID`. Built-in `kotlin.uuid.Uuid`. Room Gradle plugin `androidx.room3` for schema export. The new package means it coexists with WorkManager's internal Room 2 | Only about 3 months stable, and most Stack Overflow and sample content is Room 2. `SupportSQLite`, `Cursor` and `runInTransaction` are gone (use `withWriteTransaction`, `useReaderConnection`). Migrations receive a `SQLiteConnection`. Bundled SQLite adds a native `.so` per ABI (size, 16 KB alignment check) |
| Room 2.8.5 | Mature, still maintained (2.8.5 on 9 Sep 2026) | Dead-end package. A 3.x migration later |
| SQLDelight 2.4.0 (18 Sep 2026, Apache-2.0) | SQL-first, excellent for complex queries, KMP | No first-party Paging3 or Android lifecycle integration of the same depth. Less familiar to Android contributors. No real benefit without KMP |
| `AndroidSQLiteDriver` (framework SQLite) with Room 3 | No native lib, smaller APK | SQLite version varies by API level (3.18 on API 26 … 3.50 on API 37). Window functions need API 30+, `UPSERT` API 30+, `RETURNING` API 34+. FTS5 availability on framework SQLite is UNVERIFIED |

DataStore: **Preferences DataStore 1.2.1** for scalar settings (theme, dynamic-colour toggle, skip intervals, default speed, refresh interval, download constraints, storage location). Don't put collections or relational settings (per-group sort order, per-podcast speed) in DataStore. They belong in Room tables.

### 6. Networking and serialization

| Option | Pros | Cons |
|---|---|---|
| **OkHttp 5.5.0 (+ `okhttp-coroutines`)** (chosen) | Same client reused by Coil (`coil-network-okhttp`) and Media3 (`media3-datasource-okhttp`). HTTP cache with conditional GET (ETag / Last-Modified) for feed refresh. Interceptors for User-Agent. Android 17 lists OkHttp among libraries that can negotiate ECH | JVM/Android only (fine) |
| Ktor client 3.6.0 | KMP, nice DSL | Extra layer. On Android you would run it on the OkHttp engine anyway. Media3 and Coil integrations would still want the raw OkHttp client |
| Retrofit 3.0.0 | Typed REST | We mostly fetch arbitrary feed URLs, not a typed REST API. For the few JSON endpoints (podcast search, YouTube helpers), plain OkHttp + kotlinx.serialization is enough |

Serialization: **kotlinx.serialization 1.11.0** (1.12.0-RC exists, not used). It covers:
* Nav3 `@Serializable` `NavKey`s;
* JSON APIs (podcast directory search, `podcast:chapters` JSON, YouTube helpers);
* any typed DataStore.

It is **not** used for XML.

### 7. XML parsing (RSS 2.0, Atom, OPML; YouTube channel feeds are Atom)

| Option | Pros | Cons |
|---|---|---|
| **XmlPullParser, hand-written** (chosen) | Zero runtime deps (`org.xmlpull.v1` is in the platform). Streaming. Full control over namespace handling and recovery. Tiny allocations. On JVM tests use kxml2 (`compileOnly` + `testImplementation`) | We write and maintain the mapping code (it is the core domain logic anyway) |
| SAX (`javax.xml.parsers`, Expat-backed on Android) | Fast (native) | Push model is clumsier. Same mapping work |
| xmlutil 1.0.2 (pdvrieze, Apache-2.0) | Pure-Kotlin pull reader works identically on JVM and Android. Serialization-based mapping | Declarative mapping is brittle against messy feeds. Extra dependency |
| RSS-Parser 6.1.8 (prof18, Apache-2.0) | Ready-made, KMP, has an iTunes namespace | Limited Podcasting 2.0 (`podcast:`) coverage. Less control over recovery and redirects. Would likely be forked |

OPML export: `android.util.Xml.newSerializer()` (platform `XmlSerializer`), or a tiny hand-written writer in the pure-JVM module (OPML is trivial).

### 8. Image loading

| Option | Pros | Cons |
|---|---|---|
| **Coil 3.6.3** (chosen) | Kotlin- and Compose-first (`AsyncImage`, `SubcomposeAsyncImage`, `rememberAsyncImagePainter`). Shares OkHttp. Request de-duplication (`ConcurrentRequestStrategy`, 3.4+). Crossfade. Memory and disk caches. minSdk 23 (3.5+). Apache-2.0 | — |
| Glide (Compose integration) | Mature | Compose integration has historically been second-class. Java-centric |
| Landscapist | Wrappers over Coil, Glide or Fresco | Extra layer, no gain |

### 9. Architecture pattern

| Option | Verdict |
|---|---|
| **UDF / MVVM with repositories; use cases only where logic spans repositories** (chosen) | Matches Google's architecture guide and the Nav3 + Hilt ViewModel model. Keeps trivial reads (`podcastRepository.observePodcast(id)`) free of pass-through use-case classes |
| MVI framework (Orbit, Molecule-based presenters) | Adds a dependency and a learning curve. `StateFlow` + `combine` in ViewModels is enough. Molecule could be revisited for complex screens such as the player |
| Mandatory "one use case per action" clean architecture | Boilerplate without benefit at this size |

Use cases we do expect, because they orchestrate several repositories:
* `SubscribeUseCase`: resolve a URL into an RSS feed, YouTube channel or playlist, then fetch, persist and assign groups;
* `ImportOpmlUseCase` and `ExportOpmlUseCase`;
* `RefreshFeedsUseCase` (fan-out with a concurrency limit);
* `ObserveGroupFeedUseCase` (joins group membership, episodes, played state and download state);
* `EnqueueEpisodeUseCase`;
* `DeletePodcastUseCase`, which cascades to downloads on disk.

### 10. SDK levels

minSdk candidates. Coverage is from apilevels.com (Statcounter, April 2026 data). Statcounter is a web-traffic sample, not Play check-ins; AndroidX states its default minSdk of 24 is chosen to cover about 99% of Play users.

| minSdk | Cumulative reach | What it buys | What it costs |
|---|---|---|---|
| 24 (Android 7.0) | ≈96.6% | Matches the AndroidX floor. Maximum reach | Pre-O code paths: notification channels optional, no adaptive icons, `java.time` needs desugaring, FGS start semantics differ |
| **26 (Android 8.0)** (chosen) | ≈96.1% | Channels always exist. Adaptive icons. `java.time` and NIO `Files` natively. `startForegroundService()`. Autofill. XML font resources | Still 12 API levels to QA (26–37). `foregroundServiceType` is only honoured on 29+ (harmless attribute) |
| 29 (Android 10) | ≈91.1% | Scoped-storage world only: a MediaStore `Downloads` collection with no `WRITE_EXTERNAL_STORAGE` legacy path if we ever save to public folders. System dark theme. Gesture navigation everywhere. `foregroundServiceType` enforced | About 5 points of reach lost |
| 31 (Android 12) | ≈78.8% | Dynamic colour everywhere. Platform splash screen. Exact-alarm and PendingIntent rules uniform | About 18 points lost. Too high for a podcast player |

targetSdk: **37**. 36 is the Play minimum for submissions after 31 Aug 2026 (extension to 1 Nov 2026), so 36 is the fallback if Android 17 testing capacity is lacking. compileSdk must be **37**, because Compose 1.12 requires it. Compose 1.13 (currently alpha) will require **37.1**.

---

## Technical detail

### A. Version catalog (`gradle/libs.versions.toml`): starting point

```toml
[versions]
agp = "9.4.1"
kotlin = "2.4.20"
ksp = "2.3.12"
composeBom = "2026.09.00"
activity = "1.13.0"
appcompat = "1.8.0"            # needed for per-app language on API < 33
coreKtx = "1.19.1"
coreSplashscreen = "1.2.0"
lifecycle = "2.11.0"
navigation3 = "1.2.0"
material3Adaptive = "1.3.0"
hilt = "2.60.1"
androidxHilt = "1.4.0"
room3 = "3.0.3"
sqlite = "2.7.1"
paging = "3.5.1"
datastore = "1.2.1"
work = "2.12.0"
media3 = "1.11.1"
okhttp = "5.5.0"
coil = "3.6.3"
coroutines = "1.11.0"
serialization = "1.11.0"
collectionsImmutable = "0.5.2"
# test
turbine = "1.2.1"
robolectric = "4.17"
roborazzi = "1.76.0"
kxml2 = "2.3.0"
# tooling
aboutlibraries = "15.2.0"
licensee = "1.14.1"
moduleGraphAssert = "2.9.1"
spotless = "8.10.3"

[libraries]
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-material3-navigationSuite = { module = "androidx.compose.material3:material3-adaptive-navigation-suite" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity" }
androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "appcompat" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-core-splashscreen = { module = "androidx.core:core-splashscreen", version.ref = "coreSplashscreen" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-navigation3 = { module = "androidx.lifecycle:lifecycle-viewmodel-navigation3", version.ref = "lifecycle" }
androidx-navigation3-runtime = { module = "androidx.navigation3:navigation3-runtime", version.ref = "navigation3" }
androidx-navigation3-ui = { module = "androidx.navigation3:navigation3-ui", version.ref = "navigation3" }
androidx-material3-adaptive = { module = "androidx.compose.material3.adaptive:adaptive", version.ref = "material3Adaptive" }
androidx-material3-adaptive-layout = { module = "androidx.compose.material3.adaptive:adaptive-layout", version.ref = "material3Adaptive" }
androidx-material3-adaptive-navigation3 = { module = "androidx.compose.material3.adaptive:adaptive-navigation3", version.ref = "material3Adaptive" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
androidx-hilt-lifecycle-viewmodel-compose = { module = "androidx.hilt:hilt-lifecycle-viewmodel-compose", version.ref = "androidxHilt" }
androidx-hilt-work = { module = "androidx.hilt:hilt-work", version.ref = "androidxHilt" }
androidx-hilt-compiler = { module = "androidx.hilt:hilt-compiler", version.ref = "androidxHilt" }
androidx-room3-runtime = { module = "androidx.room3:room3-runtime", version.ref = "room3" }
androidx-room3-compiler = { module = "androidx.room3:room3-compiler", version.ref = "room3" }
androidx-room3-paging = { module = "androidx.room3:room3-paging", version.ref = "room3" }
androidx-sqlite-bundled = { module = "androidx.sqlite:sqlite-bundled", version.ref = "sqlite" }
androidx-paging-compose = { module = "androidx.paging:paging-compose", version.ref = "paging" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
androidx-work-runtime = { module = "androidx.work:work-runtime", version.ref = "work" }
androidx-media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
androidx-media3-session = { module = "androidx.media3:media3-session", version.ref = "media3" }
androidx-media3-datasource-okhttp = { module = "androidx.media3:media3-datasource-okhttp", version.ref = "media3" }
okhttp-bom = { module = "com.squareup.okhttp3:okhttp-bom", version.ref = "okhttp" }
okhttp = { module = "com.squareup.okhttp3:okhttp" }
okhttp-coroutines = { module = "com.squareup.okhttp3:okhttp-coroutines" }
okhttp-mockwebserver3 = { module = "com.squareup.okhttp3:mockwebserver3" }
coil-bom = { module = "io.coil-kt.coil3:coil-bom", version.ref = "coil" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose" }
coil-network-okhttp = { module = "io.coil-kt.coil3:coil-network-okhttp" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-collections-immutable = { module = "org.jetbrains.kotlinx:kotlinx-collections-immutable", version.ref = "collectionsImmutable" }
kxml2 = { module = "net.sf.kxml:kxml2", version.ref = "kxml2" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
# build-logic classpath
android-gradlePlugin = { module = "com.android.tools.build:gradle", version.ref = "agp" }
kotlin-gradlePlugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
ksp-gradlePlugin = { module = "com.google.devtools.ksp:symbol-processing-gradle-plugin", version.ref = "ksp" }
compose-gradlePlugin = { module = "org.jetbrains.kotlin:compose-compiler-gradle-plugin", version.ref = "kotlin" }
room3-gradlePlugin = { module = "androidx.room3:room3-gradle-plugin", version.ref = "room3" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
room3 = { id = "androidx.room3", version.ref = "room3" }
aboutlibraries = { id = "com.mikepenz.aboutlibraries.plugin", version.ref = "aboutlibraries" }
licensee = { id = "app.cash.licensee", version.ref = "licensee" }
module-graph-assert = { id = "com.jraska.module.graph.assertion", version.ref = "moduleGraphAssert" }
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
# NOTE: no `kotlin-android` and no `kotlin-kapt`: AGP 9 built-in Kotlin replaces kotlin-android, and kapt is banned.
```

There is no `org.jetbrains.kotlin.android` entry. AGP 9 applies Kotlin itself and fails if the plugin is applied.

**Pinning the KGP version.** AGP 9 depends on KGP 2.2.10 at runtime and will auto-upgrade older KGP/KSP. To use 2.4.20, KGP must be on the build classpath:
* via `buildscript { dependencies { classpath(...) } }` per the AGP 9 notes; or
* via `build-logic` declaring `implementation(libs.kotlin.gradlePlugin)` (not `compileOnly`, or it doesn't win resolution — **verify in the scaffolding spike**).

Check the result with `./gradlew buildEnvironment`.

### B. `settings.gradle.kts` and `gradle.properties`

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // Only if youtube:impl uses NewPipeExtractor (JitPack). Lock it down:
        // maven("https://jitpack.io") { content { includeGroup("com.github.teamnewpipe") } }
    }
}
rootProject.name = "Neutrodyne"
include(":app")
include(":core:model", ":core:common", ":core:database", ":core:datastore", ":core:network",
        ":core:data", ":core:domain", ":core:designsystem", ":core:ui", ":core:navigation", ":core:testing")
include(":feeds")
include(":youtube:api", ":youtube:impl")
include(":playback:api", ":playback:impl")
include(":download:api", ":download:impl")
include(":feature:library", ":feature:groups", ":feature:podcast", ":feature:episode",
        ":feature:player", ":feature:add", ":feature:downloads", ":feature:queue", ":feature:settings")
```

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
kotlin.code.style=official
# AGP 9 defaults we rely on (do NOT opt out):
#   android.builtInKotlin=true, android.newDsl=true, android.uniquePackageNames=true,
#   android.r8.optimizedResourceShrinking=true, android.defaults.buildfeatures.resValues=false
```

AGP 9 changes defaults: `targetSdk` now defaults to `compileSdk` if unset, so always set it explicitly in the convention plugin. `resValues` and `shaders` are off. R8 optimized resource shrinking is on. `getDefaultProguardFile()` only accepts `proguard-android-optimize.txt`.

### C. `build-logic` convention plugins

`build-logic/convention/build.gradle.kts` uses `kotlin-dsl` and depends on the AGP, KGP, KSP, compose-compiler and room3 Gradle plugin artifacts from the catalog. Plugins (IDs prefixed `neutrodyne.`):

| Plugin ID | Applies / configures |
|---|---|
| `neutrodyne.android.application` | `com.android.application`; compileSdk 37, minSdk 26, targetSdk 37; JVM 17 bytecode; R8 full mode on release; `androidResources.generateLocaleConfig = true`; packaging excludes (but keep licence data, see Licensing) |
| `neutrodyne.android.library` | `com.android.library`; same SDK and Kotlin options; `consumerProguardFiles`; disables unused build features |
| `neutrodyne.android.compose` | `org.jetbrains.kotlin.plugin.compose`; adds Compose BOM, `ui-tooling-preview`, debug `ui-tooling`; Compose compiler stability config file and metrics/reports under a flag |
| `neutrodyne.android.feature` | library + compose + hilt; adds `core:designsystem`, `core:ui`, `core:navigation`, `core:domain`, `core:model`, lifecycle-runtime-compose, `hilt-lifecycle-viewmodel-compose`, navigation3-runtime, kotlinx-collections-immutable |
| `neutrodyne.hilt` | `com.google.devtools.ksp` + `com.google.dagger.hilt.android`; `ksp(hilt-compiler)` |
| `neutrodyne.room` | `androidx.room3` + KSP; `room3 { schemaDirectory("$projectDir/schemas") }`; `room3-runtime`, `ksp(room3-compiler)`, `sqlite-bundled` |
| `neutrodyne.jvm.library` | `org.jetbrains.kotlin.jvm` for pure-Kotlin modules (`core:model`, `feeds`, `*:api`); JVM toolchain 17; JUnit |
| `neutrodyne.android.lint` | shared lint config: `warningsAsErrors` for NewApi, MissingPermission, etc.; baseline |
| `neutrodyne.quality` (root) | Spotless (ktlint), module-graph-assertion rules, Licensee allowlist |

Sketch (AGP 9 new DSL: `CommonExtension` is no longer generic in 9.4, and `compileSdk {}` blocks replace `compileSdkVersion()`):

```kotlin
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
        }
        // Built-in Kotlin registers the `kotlin` extension; configure compilerOptions there.
        extensions.configure<KotlinAndroidProjectExtension> {   // UNVERIFIED type name under built-in Kotlin: confirm in spike
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map(String::toBoolean).orElse(false))
                freeCompilerArgs.add("-Xjvm-default=all")
            }
        }
    }
}

internal fun Project.configureAndroidCommon(ext: CommonExtension) {
    ext.compileSdk = 37
    ext.defaultConfig.minSdk = 26
    ext.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    ext.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    ext.lint.abortOnError = true
}
```

### D. Module layout and dependency rules

```
:app                       Application (@HiltAndroidApp), MainActivity (AppCompatActivity),
                           NavDisplay host, top-level scaffold (NavigationSuiteScaffold + mini-player),
                           manifest (permissions, services), Coil ImageLoader factory, WorkManager config
:build-logic:convention    convention plugins (included build)

:core:model         (jvm)  Podcast, Episode, Group, FeedSource (sealed: Rss, YouTubeChannel, YouTubePlaylist),
                           PlaybackPosition, DownloadState: plain data, no Android
:core:common        (jvm)  @Dispatcher qualifiers, ApplicationScope, Result/Outcome types, Clock, logging facade
:core:navigation    (jvm)  @Serializable NavKey types shared across features (LibraryKey, GroupFeedKey(id),
                           PodcastKey(id), EpisodeKey(id), PlayerKey, SettingsKey ...); EntryProviderInstaller typealias
:core:database      (and)  Room 3 database, entities, DAOs, migrations, exported schemas, FTS tables
:core:datastore     (and)  Preferences DataStore + typed SettingsRepository
:core:network       (and)  the single OkHttpClient, User-Agent interceptor, HTTP cache, NetworkMonitor (ConnectivityManager)
:core:data          (and)  repositories: PodcastRepository, EpisodeRepository, GroupRepository,
                           SubscriptionRepository, SettingsRepository; RefreshWorker; OfflineFirst merges
:core:domain        (jvm)  cross-repository use cases (Subscribe, ImportOpml, ExportOpml, RefreshFeeds, ObserveGroupFeed …)
:core:designsystem  (and)  NeutrodyneTheme (dynamic colour, cover-derived colour), typography, shapes, motion,
                           CoverArt composable, PlayerBar, EpisodeRow skeleton, Material Symbols vectors
:core:ui            (and)  model-aware shared composables (EpisodeListItem with download/progress badges, PodcastGridCell)
:core:testing       (and)  fakes for repositories/controllers, MainDispatcherRule, test data builders

:feeds              (jvm)  RSS 2.0 / Atom / Podcasting 2.0 / iTunes namespace parser (XmlPullParser),
                           OPML reader/writer, feed URL normalisation & redirect/new-feed-url handling
:youtube:api        (jvm)  YouTubeResolver (url → channel/playlist id → feed URL), StreamResolver (videoId → playable/downloadable URL)
:youtube:impl       (and)  implementation (YouTube Atom feeds + extractor of choice, see the YouTube research area)
:playback:api       (jvm)  PlaybackController (play/pause/seek/speed/queue), PlayerState Flow, SleepTimer API
:playback:impl      (and)  Media3 MediaSessionService (@AndroidEntryPoint), ExoPlayer w/ OkHttpDataSource,
                           MediaController-backed PlaybackController, position persistence, audio focus/noisy handling
:download:api       (jvm)  DownloadController, DownloadState Flow
:download:impl      (and)  download engine (WorkManager / UIDT / Media3 DownloadService: see download research area)

:feature:library    subscriptions grid (covers), sort/filter
:feature:groups     group list, create/rename/reorder groups, assign podcasts, **group feed** screen
:feature:podcast    podcast detail (cover hero, episodes, group membership chips)
:feature:episode    episode detail / show notes (HTML → AnnotatedString), chapters
:feature:player     full-screen now playing (cover, scrubber, speed, sleep timer, chapters)
:feature:add        add by URL / search directory / YouTube URL paste; OPML import entry point
:feature:downloads  downloads management, storage usage
:feature:queue      up-next queue
:feature:settings   settings, OPML import/export, language picker, licences screen
```

Dependency rules (enforced by `com.jraska.module.graph.assertion` in the root build plus review):

1. `:app` → anything (it is the composition root). Nothing depends on `:app`.
2. `:feature:*` → `:core:{designsystem, ui, navigation, domain, model, common}`, `:playback:api`, `:download:api`.
   * **Never** `feature → feature`. Cross-feature navigation goes through `NavKey`s in `:core:navigation`.
   * **Never** `feature → core:database | core:network | *:impl`.
   * Features *may* depend on `:core:data` for simple reads. Alternatively, put repository interfaces in `:core:data` and only expose them through `:core:domain`; pick one rule and enforce it.
3. `:core:data` → `:core:{database, datastore, network, model, common}`, `:feeds`, `:youtube:api`.
4. `:*:impl` → its own `:*:api`, `:core:data`, `:core:network`, `:core:model`, `:core:common` (and `:youtube:api` for playback and download stream resolution). `:*:impl` modules never depend on features or on each other's impl.
5. `:feeds`, `:core:model`, `:core:domain`, `:core:navigation` and `:*:api` are **pure JVM**: no Android, no Compose. Unit tests run on the JVM without Robolectric.
6. `:core:designsystem` depends on nothing project-internal except `:core:model` (for `CoverArt` sizing types, optional).

Graph assertion sketch (root `build.gradle.kts`):

```kotlin
moduleGraphAssert {
    maxHeight = 6
    allowed = arrayOf(
        ":app -> .*",
        ":feature:.* -> :core:(designsystem|ui|navigation|domain|data|model|common)",
        ":feature:.* -> :(playback|download):api",
        ":core:.* -> :core:.*",
        ":core:data -> :feeds|:youtube:api",
        ":(playback|download|youtube):impl -> :core:.*|:(playback|download|youtube):api",
    )
    restricted = arrayOf(":feature:.* -X> :feature:.*", ":core:.* -X> :feature:.*", ":.*:api -X> :.*:impl")
}
```

Why api/impl only for playback, download and youtube: these are the heavy, platform-coupled subsystems (Media3, WorkManager/JobScheduler, the extractor). Splitting keeps every feature module's compile classpath free of them, which improves incremental builds and lets features unit-test against fakes. `:feeds` needs no split because it is already pure JVM with a narrow API.

### E. Architecture: data flow, ViewModels, DI, navigation wiring

* **Single source of truth = Room.** Network refresh writes to Room. UI observes Room Flows. Offline works by construction.
* **UI state**: one immutable `UiState` per screen exposed as `StateFlow`. Events are plain ViewModel functions. One-shot effects (snackbars) go through a `Channel` consumed with `LaunchedEffect`, or are modelled as state with acknowledgement.
* **Collect with** `collectAsStateWithLifecycle()` (lifecycle-runtime-compose).
* Long lists (a group feed with thousands of episodes) use **Paging 3** from a Room 3 `PagingSource` via `room3-paging` and `@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)`, collected with `collectAsLazyPagingItems()`.

ViewModel with a Nav3 key argument (assisted injection, androidx.hilt 1.3+):

```kotlin
@HiltViewModel(assistedFactory = GroupFeedViewModel.Factory::class)
class GroupFeedViewModel @AssistedInject constructor(
    @Assisted private val groupId: Long,
    observeGroupFeed: ObserveGroupFeedUseCase,
    groupRepository: GroupRepository,
    private val playback: PlaybackController,      // from :playback:api
    private val downloads: DownloadController,     // from :download:api
) : ViewModel() {

    @AssistedFactory interface Factory { fun create(groupId: Long): GroupFeedViewModel }

    val episodes: Flow<PagingData<EpisodeListItem>> =
        observeGroupFeed(groupId).cachedIn(viewModelScope)

    val uiState: StateFlow<GroupFeedUiState> =
        combine(groupRepository.observeGroup(groupId), playback.state) { group, player ->
            GroupFeedUiState(title = group?.name.orEmpty(), nowPlayingId = player.currentEpisodeId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupFeedUiState.Loading)

    fun play(episodeId: Long) = playback.play(episodeId)
    fun download(episodeId: Long) = downloads.enqueue(episodeId)
}
```

Modular Nav3 wiring with Hilt multibinding (pattern from the official "Hilt – Modularized navigation" recipe; exact parameter names as of Nav3 1.2: **verify at scaffolding**):

```kotlin
// :core:navigation
@Serializable data object LibraryKey : NavKey
@Serializable data object GroupsKey : NavKey
@Serializable data class GroupFeedKey(val groupId: Long) : NavKey
@Serializable data class PodcastKey(val podcastId: Long) : NavKey
typealias EntryProviderInstaller = EntryProviderScope<NavKey>.() -> Unit

// :feature:groups
@Module @InstallIn(ActivityRetainedComponent::class)
object GroupsNavigationModule {
    @Provides @IntoSet
    fun groupsEntries(): EntryProviderInstaller = {
        entry<GroupsKey>(metadata = ListDetailSceneStrategy.listPane()) { GroupsRoute() }
        entry<GroupFeedKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
            val vm = hiltViewModel<GroupFeedViewModel, GroupFeedViewModel.Factory>(
                creationCallback = { it.create(key.groupId) })
            GroupFeedRoute(vm)
        }
    }
}

// :app
@Composable fun NeutrodyneNavHost(installers: Set<@JvmSuppressWildcards EntryProviderInstaller>) {
    val backStack = rememberNavBackStack(LibraryKey)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        sceneStrategies = listOf(rememberListDetailSceneStrategy()),   // List<SceneStrategy> since Nav3 1.1
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),   // scopes ViewModels to entries, not the Activity
        ),
        entryProvider = entryProvider { installers.forEach { it() } },
    )
}
```

Top-level destinations (Library, Groups, Downloads/Queue, Settings) each keep their own back stack: use the "multiple back stacks" recipe and keep one `NavBackStack` per tab. A persistent **mini-player** sits outside `NavDisplay` in the scaffold's bottom area. The full player is a `PlayerKey` entry (or a custom overlay scene) so that predictive back collapses it.

Hilt scopes:

| Binding | Component | Scope |
|---|---|---|
| `OkHttpClient`, `NeutrodyneDatabase`, DAOs, `DataStore<Preferences>`, repositories, `@ApplicationScope CoroutineScope` | `SingletonComponent` | `@Singleton` |
| `PlaybackController` (MediaController wrapper) | `SingletonComponent` | `@Singleton`, lazily connects to the session |
| Nav entry installers | `ActivityRetainedComponent` | unscoped `@IntoSet` |
| ViewModels | `ViewModelComponent` | per Nav3 entry via the decorator |
| `NeutrodynePlaybackService : MediaSessionService` | `ServiceComponent` | `@AndroidEntryPoint` |
| Workers | `@HiltWorker` + `HiltWorkerFactory` | `Application : Configuration.Provider`; remove the default `WorkManagerInitializer` via a manifest `tools:node="remove"` on the startup provider meta-data |

Dispatchers and scopes (`:core:common`):

```kotlin
@Qualifier @Retention(BINARY) annotation class Dispatcher(val d: NeutrodyneDispatchers)
enum class NeutrodyneDispatchers { Default, IO }
@Qualifier @Retention(BINARY) annotation class ApplicationScope

@Module @InstallIn(SingletonComponent::class)
object CoroutinesModule {
    @Provides @Dispatcher(NeutrodyneDispatchers.IO) fun io(): CoroutineDispatcher = Dispatchers.IO
    @Provides @Singleton @ApplicationScope
    fun appScope(@Dispatcher(NeutrodyneDispatchers.Default) d: CoroutineDispatcher) =
        CoroutineScope(SupervisorJob() + d)
}
```

Use `@ApplicationScope` for work that must outlive a screen, for example finishing an OPML import after the user navigates away. If the work must survive process death, use WorkManager instead. Feed refresh fan-out: `Dispatchers.IO.limitedParallelism(6)` plus `supervisorScope`, so that one broken feed never cancels the others.

### F. Persistence detail

Database builder (Room 3; package `androidx.room3`):

```kotlin
@Provides @Singleton
fun database(@ApplicationContext ctx: Context): NeutrodyneDatabase =
    Room.databaseBuilder<NeutrodyneDatabase>(ctx, ctx.getDatabasePath("neutrodyne.db").absolutePath)
        .setDriver(BundledSQLiteDriver())          // consistent modern SQLite on API 26–37
        .setQueryCoroutineContext(Dispatchers.IO)  // Room 3: CoroutineContext instead of Executor
        .addMigrations(/* hand-written, tested with room3-testing */)
        .build()
```

Indicative schema. The data-model research area owns the final shape; this sketch is here to show the foundation choices hold:

```
podcast(id PK, source_type TEXT /*RSS|YT_CHANNEL|YT_PLAYLIST*/, feed_url TEXT UNIQUE, title, author,
        description, image_url, link, language, explicit, etag, last_modified, last_refreshed_at,
        refresh_error, cover_seed_color INT NULL, added_at)
episode(id PK, podcast_id FK→podcast ON DELETE CASCADE, guid, title, description_html, published_at,
        duration_ms, enclosure_url, enclosure_mime, enclosure_bytes, image_url, season, number,
        chapters_url, transcript_url, UNIQUE(podcast_id, guid))  INDEX(podcast_id, published_at DESC)
podcast_group(id PK, name, sort_index, icon NULL)           -- "tech", "news", "fiction" …
podcast_group_member(group_id FK, podcast_id FK, PRIMARY KEY(group_id, podcast_id))  INDEX(podcast_id)
episode_state(episode_id PK FK, position_ms, played, played_at, starred)
download(episode_id PK FK, state, local_uri, bytes_done, bytes_total, error)
queue_item(position PK, episode_id FK UNIQUE)
episode_fts USING fts4/fts5(title, description, content=episode)  -- see pitfalls re FTS5
```

Group feed query: a many-to-many membership table means a podcast can be in several groups.

```sql
SELECT e.*, p.title AS podcast_title, p.image_url AS podcast_image, s.position_ms, s.played, d.state AS dl_state
FROM episode e
JOIN podcast_group_member m ON m.podcast_id = e.podcast_id AND m.group_id = :groupId
JOIN podcast p ON p.id = e.podcast_id
LEFT JOIN episode_state s ON s.episode_id = e.id
LEFT JOIN download d ON d.episode_id = e.id
ORDER BY e.published_at DESC, e.id DESC
```

DataStore: one `DataStore<Preferences>` named `settings`, wrapped by `SettingsRepository` that exposes `Flow<Settings>` (a data class). Never create two DataStore instances for the same file; create it with a top-level `preferencesDataStore` delegate or a `@Singleton` provider.

### G. Networking detail

```kotlin
@Provides @Singleton
fun okHttp(@ApplicationContext ctx: Context): OkHttpClient = OkHttpClient.Builder()
    .cache(Cache(ctx.cacheDir.resolve("http"), 64L * 1024 * 1024))   // feeds: conditional GET
    .addNetworkInterceptor { chain ->
        chain.proceed(chain.request().newBuilder()
            .header("User-Agent", "Neutrodyne/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})")
            .build())
    }
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()
```

* Media3: `OkHttpDataSource.Factory(okHttp)` so streaming shares the pool and UA. Many podcast hosts and IAB-certified analytics key on the UA.
* Coil: `OkHttpNetworkFetcherFactory(callFactory = { okHttp })`. Coil keeps its own disk cache for decoded image bytes. Optionally build `okHttp.newBuilder().cache(null)` for Coil to avoid double-caching images.
* Downloads: same client with a longer read timeout (`newBuilder()` shares the pool).
* **Cleartext:** many enclosures and feeds are still `http://`. Use a network security config, not `usesCleartextTraffic`, which Android 17 announces will be deprecated:

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors><certificates src="system" /></trust-anchors>
    </base-config>
</network-security-config>
```

### H. XML parsing detail (`:feeds`, pure JVM)

```kotlin
class FeedParser(private val newParser: () -> XmlPullParser = {
    XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
}) {
    fun parse(stream: InputStream, httpCharset: String?): ParsedFeed {
        val p = newParser()
        // Best effort: tolerate undefined entities like &nbsp; (KXmlParser "relaxed" feature). UNVERIFIED on platform parser → test.
        runCatching { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        p.setInput(stream, httpCharset)   // null ⇒ detect from BOM / XML declaration
        // dispatch on root: <rss>, <feed> (Atom, incl. YouTube), <opml>
        ...
    }
}
```

* Gradle: `compileOnly(libs.kxml2)` and `testImplementation(libs.kxml2)`. Never `implementation`: `org.xmlpull.v1` is already in `android.jar`, and bundling it triggers lint `DuplicatePlatformClasses`.
* Match namespaces by **URI**, not by prefix. Feeds use `itunes:`, `iTunes:` or arbitrary prefixes for `http://www.itunes.com/dtds/podcast-1.0.dtd`. Podcasting 2.0 is `https://podcastindex.org/namespace/1.0`.
* Fixture corpus: commit 50–100 real-world feeds (anonymised if needed) under `feeds/src/test/resources`, including YouTube Atom feeds and OPML files from other apps (AntennaPod, Pocket Casts, Podcast Addict, Apple Podcasts).

### I. Images (Coil 3)

```kotlin
@HiltAndroidApp
class NeutrodyneApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    @Inject lateinit var okHttp: dagger.Lazy<OkHttpClient>
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttp.get() })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("covers").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()

    override val workManagerConfiguration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
```

`CoverArt(url, size)` in `:core:designsystem` always requests an explicit pixel size: grid cell size, or 2× for the hero. That prevents 3000×3000 covers from being decoded at full resolution, which matters for Android 17's new per-app memory limits. Set a stable `memoryCacheKey` per podcast, and use a placeholder painter derived from `cover_seed_color` so grids don't flash. Shared-element transitions from grid cover to podcast hero use `SharedTransitionLayout` with Nav3's scene shared-element support (1.1+).

### J. Platform requirements checklist (what each means for Neutrodyne)

| Requirement | Applies when | What we do |
|---|---|---|
| **Play target API** | New apps and updates from 31 Aug 2026 must target ≥ 36 (extension to 1 Nov 2026). Existing apps must target ≥ 35 to stay visible to new users on newer OS versions | targetSdk 37 (≥ 36 satisfies) |
| **Edge-to-edge** | Enforced for targetSdk 35 on Android 15+. **Opt-out removed** for targetSdk 36 (`windowOptOutEdgeToEdgeEnforcement` disabled) | `enableEdgeToEdge()` before `super.onCreate` (activity 1.13; 1.14-alpha moves to `WindowCompat.enableEdgeToEdge(window)`). M3 `Scaffold` / `NavigationSuiteScaffold` inset handling. Mini-player and bottom bar consume `WindowInsets.navigationBars`. `imePadding()` on search |
| **Predictive back** | targetSdk 36 on Android 16+: system animations on by default, `onBackPressed()` **not called**, `KEYCODE_BACK` not dispatched | Never override `onBackPressed`. Nav3 `NavDisplay` handles back. Custom surfaces (full player, search bar, bottom sheets) use `NavigationBackHandler` (Nav3 1.2) or `PredictiveBackHandler`. Never call `onBackPressedDispatcher.onBackPressed()` inside a handler |
| **Large screens** | targetSdk 36: orientation, resizability and aspect-ratio restrictions ignored on sw ≥ 600dp (opt-out via `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`). **targetSdk 37: opt-out removed** | No `screenOrientation` locks. Adaptive layouts: `NavigationSuiteScaffold`, `ListDetailSceneStrategy` (Groups ↔ Group feed, Library ↔ Podcast). Player shows cover + controls side by side in landscape |
| **16 KB page size** | Apps targeting 35+ must support 16 KB pages on 64-bit devices. Per the current page, from 1 Feb 2027 non-compliant updates are blocked. AGP ≥ 8.5.1 and NDK r28+ align by default | We ship no own native code. Native code comes from `sqlite-bundled` and possibly a JS engine or extractor in `youtube:impl`. CI step: `zipalign -c -P 16 -v 4 app-release.apk` and APK Analyzer review |
| **FGS types** | Must declare `foregroundServiceType` and the matching `FOREGROUND_SERVICE_*` permission (Android 14+). Android 15: `dataSync` limited to **6 h per 24 h** (`Service.onTimeout`). `mediaPlayback` and `dataSync` FGS **cannot start from `BOOT_COMPLETED`** | Playback: `mediaPlayback` on the Media3 service. Downloads: avoid a long-running `dataSync` FGS; prefer WorkManager for background downloads and a **user-initiated data transfer job** for user-tapped downloads (download area to confirm; WorkManager 2.12 release notes do not mention UIDT support) |
| **Android 16 job quotas** | Jobs started while the app is visible and continuing after it is hidden, **and jobs running concurrently with an FGS**, now count against runtime quota | A refresh or download worker running while audio plays can be stopped. Design workers to be resumable (byte ranges, idempotent upserts). Log `WorkInfo.getStopReason()` |
| **Android 17 background-audio hardening** | All apps on Android 17: background audio, focus and volume calls need a visible activity or a non-`shortService` FGS. **targetSdk 37: the FGS must have while-in-use (WIU) capability.** Failures are *silent* (focus returns `AUDIOFOCUS_REQUEST_FAILED`) | Use Media3 `MediaSessionService` (Google's stated mitigation). Start playback only from UI, notification, widget, or external media-key events (these grant WIU). Keep the FGS alive through transient failures under 10 min (buffering, `AUDIOFOCUS_LOSS_TRANSIENT`). Stop the FGS on a permanent end. **No** auto-start from boot or alarms. Test with `adb shell cmd audio set-enable-hardening throw` |
| **Notification permission** | `POST_NOTIFICATIONS` runtime permission on API 33+. **Media-session notifications are exempt.** Apps don't need the permission to start an FGS, but without it FGS notices appear only in Task Manager | Declare it. Request it contextually (first download, or enabling "new episode" alerts), not at first launch. Channels: `playback` (exempt in practice), `downloads`, `new_episodes`, `errors` |
| **Per-app language** | Android 13+ system picker uses `localeConfig`. Backport via AppCompat | `androidResources { generateLocaleConfig = true }`, plus `res/resources.properties` with `unqualifiedResLocale=en-US`. **MainActivity extends `AppCompatActivity`** (required for Compose + `AppCompatDelegate.setApplicationLocales`) with an AppCompat-derived theme. `AppLocalesMetadataHolderService` with `autoStoreLocales=true` for API < 33. In-app picker in Settings |
| **Android 17 Certificate Transparency** | targetSdk 37: CT enabled by default | Self-hosted feeds with certificates lacking SCTs may fail TLS. Surface a clear per-feed error (spike: confirm failure mode and any NSC opt-out) |
| **Android 17 local network permission** | targetSdk 37: `ACCESS_LOCAL_NETWORK` required for LAN access | Only matters if we add Cast/DLNA/local sync. Not in v1 scope |
| **Android 17 app memory limits** | All apps on Android 17: RAM-based per-app limits (exit reason `MemoryLimiter:AnonSwap`) | Bounded Coil memory cache, sized decodes, no full-feed `String` buffering |
| **Android 17 widget bitmap limit** | targetSdk 37: RemoteViews bitmap memory capped (1.5 × screen w × h × 4) | If a home-screen widget with cover art is added later, downscale covers |

Manifest skeleton (app module; services and permissions from impl modules merge in from their own manifests):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <!-- from :playback:impl manifest -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <!-- from :download:impl, depending on its design: RUN_USER_INITIATED_JOBS and/or FOREGROUND_SERVICE_DATA_SYNC -->

    <application
        android:name=".NeutrodyneApplication"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:networkSecurityConfig="@xml/network_security_config"
        android:theme="@style/Theme.Neutrodyne.Starting">   <!-- Theme.SplashScreen → postSplashScreenTheme = AppCompat DayNight NoActionBar -->

        <activity android:name=".MainActivity" android:exported="true"
                  android:launchMode="singleTop" android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- subscribe deep links: podcast:// / pcast:// / itpc:// / feed:// schemes, and OPML VIEW intents (owned by feature research) -->
        </activity>

        <service android:name="androidx.appcompat.app.AppLocalesMetadataHolderService"
                 android:enabled="false" android:exported="false">
            <meta-data android:name="autoStoreLocales" android:value="true" />
        </service>

        <!-- Hilt-provided WorkManager factory: disable default initializer -->
        <provider android:name="androidx.startup.InitializationProvider"
                  android:authorities="${applicationId}.androidx-startup" tools:node="merge">
            <meta-data android:name="androidx.work.WorkManagerInitializer"
                       android:value="androidx.startup" tools:node="remove" />
        </provider>
    </application>
</manifest>
```

```xml
<!-- :playback:impl AndroidManifest.xml -->
<service android:name=".NeutrodynePlaybackService"
         android:exported="true"
         android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
        <action android:name="android.media.browse.MediaBrowserService" />  <!-- Android Auto / legacy controllers if MediaLibraryService -->
    </intent-filter>
</service>
```

MainActivity:

```kotlin
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {          // AppCompat needed for per-app locales on API < 33
    @Inject lateinit var navInstallers: Set<@JvmSuppressWildcards EntryProviderInstaller>
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { NeutrodyneTheme { NeutrodyneApp(navInstallers) } }
    }
}
```

### K. Licensing (the codebase is under the Unlicense)

* The project's own code stays under the Unlicense (a public-domain dedication with a permissive fallback licence).
* Every recommended runtime dependency is **Apache-2.0**: AndroidX, Kotlin stdlib/coroutines/serialization/collections-immutable, Dagger/Hilt, OkHttp/Okio, Coil, Room 3, sqlite-bundled, Media3, Navigation 3, plus Metro, Koin, Ktor, SQLDelight, xmlutil and RSS-Parser if chosen. Optional MaterialKolor is **MIT**.
* Test-only: Robolectric (MIT), Turbine, Roborazzi, MockWebServer (Apache-2.0), kxml2 (BSD-style, with the XmlPull API in the public domain). Test dependencies are not distributed.
* Compatibility: permissive licences impose no requirement on our source. Distributing the APK requires preserving their licence texts and NOTICE files (Apache-2.0 §4).
  * Plan an in-app **"Open-source licences"** screen generated by **AboutLibraries 15.2.0** (Apache-2.0).
  * Add a CI gate with **Licensee 1.14.1** (Apache-2.0) allowlisting `Apache-2.0`, `MIT`, `BSD-2-Clause`, `BSD-3-Clause`, `Unlicense` and `CC0-1.0`, and failing on anything else (GPL, LGPL, unknown).
* `desugar_jdk_libs` (if core-library desugaring is enabled, for example because the YouTube extractor needs newer `java.*` APIs) is **GPL-2.0 with Classpath Exception**. That is fine for linking, but it must be explicitly allowlisted. With minSdk 26 we otherwise don't need desugaring.
* **NewPipeExtractor is GPL-3.0.** The FSF lists the Unlicense as GPL-compatible, so the combination is legal. The distributed APK as a whole must then be offered under GPL-3.0 terms: full corresponding source, licence text, no additional restrictions. Our source is public already, so compliance is easy. But it is a policy decision that also affects any future proprietary SDK (for example Google Cast). Escalate to the product owner (see Open questions).

---

## Verified versions & facts

All checked **2026-10-04**. Maven metadata URLs were read directly. `last-modified` dates come from the published POMs.

| Fact | Value | Source |
|---|---|---|
| Kotlin latest stable | 2.4.20 (tooling release, 7 Sep 2026); 2.4.10 (14 Jul 2026); 2.4.0 (3 Jun 2026); 2.4 line supported to 3 Dec 2027 | https://kotlinlang.org/docs/releases.html |
| KGP ↔ AGP/Gradle tested matrix | KGP 2.4.20: Gradle 7.6.3–9.7.0, AGP 8.5.2–9.3.1. KGP 2.4.0–2.4.10: Gradle ≤ 9.5.0, AGP ≤ 9.1.0. Newer versions "might" give deprecation warnings | https://kotlinlang.org/docs/gradle-configure-project.html |
| Compose compiler plugin | `org.jetbrains.kotlin.plugin.compose`, version = Kotlin version | https://developer.android.com/develop/ui/compose/compiler |
| AGP latest stable | 9.4.0 (September 2026); 9.4.1 POM published 18 Sep 2026. Requires Gradle ≥ 9.6.0, JDK 17, Build Tools 36.0.0; max API 37 | https://developer.android.com/build/releases/gradle-plugin · https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml |
| AGP 9.3 / 9.2 / 9.1 | 9.3.0 (Jul 2026, Gradle ≥ 9.5.0); 9.2.0 (Apr 2026, Gradle ≥ 9.4.1); 9.1.1 (Apr 2026, Gradle ≥ 9.3.1). Latest 9.3.x = 9.3.3 | https://developer.android.com/build/releases/agp-9-3-0-release-notes · …/agp-9-2-0-release-notes · …/agp-9-1-0-release-notes |
| AGP 9.0 breaking changes | Released Jan 2026. Built-in Kotlin on by default (don't apply `kotlin-android`). New DSL only (`BaseExtension` gone; opt-out removed in AGP 10). Runtime dependency on KGP 2.2.10 (upgrade via buildscript classpath). `targetSdk` defaults to `compileSdk`. `resValues` off. R8 optimized resource shrinking on. Only `proguard-android-optimize.txt` | https://developer.android.com/build/releases/agp-9-0-0-release-notes |
| Built-in Kotlin & kapt | `kotlin-kapt` incompatible with built-in Kotlin. Migrate to KSP or use `com.android.legacy-kapt`. KMP modules still use `org.jetbrains.kotlin.multiplatform` + `com.android.kotlin.multiplatform.library` | https://developer.android.com/build/migrate-to-built-in-kotlin |
| AGP 9.4 `CommonExtension` | Non-generic `interface CommonExtension : ExtensionAware`. `compileSdkVersion()` deprecated in favour of the `compileSdk {}` block (removal in AGP 10) | https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/CommonExtension |
| Android Studio stable | Rabbit 1, 2026.2.1. Supports AGP 7.1–9.4 | https://developer.android.com/studio/releases · https://developer.android.com/build/releases/about-agp |
| Gradle | 9.8.0 (24 Sep 2026), 9.7.1 (19 Aug 2026), 9.7.0 (6 Aug 2026), 9.6.0 (18 Jun 2026) | https://gradle.org/releases/ |
| KSP | 2.3.12 (Maven Central, 9 Sep 2026). Independent versioning (no longer `kotlin-ksp`). 2.3.10 fixed AGP 9 built-in-Kotlin R-class resolution and Kotlin 2.4.0 compatibility. 2.3.12 min AGP 8.12.0 | https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-api/maven-metadata.xml · https://github.com/google/ksp/releases |
| Compose BOM | 2026.09.00 (published 9 Sep 2026) → ui/foundation/runtime/animation 1.12.1, material3 1.4.0, adaptive-navigation 1.3.0, material-icons 1.7.8 | https://developer.android.com/develop/ui/compose/bom/bom-mapping |
| Compose 1.12 build requirement | 1.12.0-alpha01 (22 Apr 2026): "Updated Compose compileSdk to API 37 … minimum AGP version of 9.2.0 is required". 1.13.0-alpha03: compileSdk 37.1 | https://developer.android.com/jetpack/androidx/releases/compose-ui |
| Material 3 stable | 1.4.0 (24 Sep 2025). 1.4.0-beta01 removed all `ExperimentalMaterial3ExpressiveApi` APIs ("switch to 1.5.0-alpha") | https://developer.android.com/jetpack/androidx/releases/compose-material3 |
| Material 3 Expressive status | Only in 1.5.0-alpha (alpha29, 23 Sep 2026). Many APIs promoted to non-experimental within the alpha (expressive theme and colour scheme, buttons, FAB menu, ToggleButtons, WavyProgressIndicator, ButtonGroup, FloatingToolbar, menus, list items). `MaterialShapes`/`LoadingIndicator` reverted to experimental. alpha29 source-breaking `Slider` change | same as above |
| Material icons artifact | "no longer maintained or recommended". Use Material Symbols via Google Fonts icons | https://developer.android.com/develop/ui/compose/graphics/images/material |
| Material 3 Adaptive | adaptive, adaptive-layout, adaptive-navigation, adaptive-navigation3 1.3.0 stable (`ListDetailSceneStrategy`, `SupportingPaneSceneStrategy`) | https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive · Google Maven metadata |
| Navigation 3 | 1.0.0 stable 19 Nov 2025; 1.1.0 8 Apr 2026 (shared elements between scenes, `List<SceneStrategy>`); 1.2.0 23 Sep 2026 (deep-link API, ResultEventBus, `NavigationBackHandler`); 1.3.0-alpha01 | https://developer.android.com/jetpack/androidx/releases/navigation3 |
| Navigation 2.x | 2.10.0 (26 Aug 2026), 2.10.2 (23 Sep 2026). Release page caution: "maintenance mode … only receive critical fixes". minSdk moved to 24 "along with the rest of the AndroidX libraries" | https://developer.android.com/jetpack/androidx/releases/navigation |
| Nav3 recipes | Hilt, Koin and Metro modularized navigation; Hilt/Koin/Metro injected ViewModel; multiple back stacks; list-detail; deep links | https://github.com/android/nav3-recipes |
| Lifecycle | 2.11.0 stable (17 Jun 2026), incl. `lifecycle-viewmodel-navigation3` (`rememberViewModelStoreNavEntryDecorator`) | https://developer.android.com/jetpack/androidx/releases/lifecycle |
| Activity | 1.13.0 stable (11 Mar 2026). 1.14.0-alpha03 deprecates `EdgeToEdge#enable()` → `WindowCompat#enableEdgeToEdge(Window)`. Back handling rebuilt on NavigationEvent | https://developer.android.com/jetpack/androidx/releases/activity |
| core-ktx | 1.19.1 (23 Sep 2026) | https://developer.android.com/jetpack/androidx/releases/core |
| Dagger/Hilt | 2.60.1 (6 Jul 2026). 2.59 added AGP 9 support and requires AGP 9 + Gradle 9.1+ for Hilt Gradle plugin users. 2.60 minSdk 23 | https://github.com/google/dagger/releases · Maven Central metadata |
| androidx.hilt | 1.4.0 (1 Jul 2026): `rememberHiltViewModelFactory()`. 1.3.0 moved `hiltViewModel()` to `hilt-lifecycle-viewmodel-compose`. Assisted-injection `hiltViewModel` overloads | https://developer.android.com/jetpack/androidx/releases/hilt |
| Metro | 1.0.0 (27 Apr 2026), 1.4.5 (24 Sep 2026). Apache-2.0. Supports Kotlin 2.3.0 → 2.5.0-dev. Recommends ≥ 2.3.20 | https://repo1.maven.org/maven2/dev/zacsweers/metro/runtime/maven-metadata.xml · https://zacsweers.github.io/metro/latest/compatibility/ |
| Koin | 4.2.2 (15 Jun 2026). Koin Compiler Plugin 1.0.0-RC1 aligned with Koin 4.2.1, needs Kotlin 2.3+ | Maven Central metadata · https://blog.insert-koin.io/unlocking-koin-compile-safety-6278840ab171 |
| Room 3 | 3.0.0 stable 1 Jul 2026; 3.0.3 (9 Sep 2026). Package `androidx.room3`. KSP-only, Kotlin codegen only. Coroutines required. `SQLiteDriver` required. FTS5. `DaoReturnTypeConverter`. Gradle plugin id `androidx.room3` | https://developer.android.com/jetpack/androidx/releases/room3 |
| Room 2.x | 2.8.5 (9 Sep 2026) | Google Maven metadata |
| SQLite drivers | `BundledSQLiteDriver` recommended ("most up-to-date version and consistency"). sqlite 2.7.1 | https://developer.android.com/kotlin/multiplatform/sqlite · https://developer.android.com/jetpack/androidx/releases/sqlite |
| Framework SQLite by API | 26 → 3.18, 27 → 3.19, 28 → 3.22, 30 → 3.28, 31–33 → 3.32, 34 → 3.39/3.42, 35 → 3.44, 36.1/37 → 3.50 | https://developer.android.com/reference/android/database/sqlite/package-summary |
| DataStore | 1.2.1 (11 Mar 2026); 1.3.0-alpha11 | https://developer.android.com/jetpack/androidx/releases/datastore |
| WorkManager | 2.12.0 (23 Sep 2026), minSdk 24 | https://developer.android.com/jetpack/androidx/releases/work |
| Media3 | 1.11.1 (10 Sep 2026), minSdk 23 | https://developer.android.com/jetpack/androidx/releases/media3 |
| Paging | 3.5.1 | Google Maven metadata |
| AndroidX default minSdk | **24**, updated yearly, chosen to cover 99% of Play users | https://developer.android.com/jetpack/androidx/versions |
| OkHttp | 5.5.0 (16 Aug 2026); `okhttp-coroutines` 5.5.0 | https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml |
| Ktor | 3.6.0 (16 Sep 2026) | Maven Central metadata |
| Retrofit | 3.0.0 | Maven Central metadata |
| kotlinx.serialization | 1.11.0 stable (9 Apr 2026); 1.12.0-RC | Maven Central metadata |
| kotlinx.coroutines | 1.11.0 (7 May 2026) | Maven Central metadata |
| Coil 3 | 3.6.3 (18 Sep 2026; fixed AGP 9.4.0/R8 issue); 3.5.0 raised minSdk to 23 | https://coil-kt.github.io/coil/changelog/ |
| SQLDelight | 2.4.0 (18 Sep 2026) | Maven Central metadata |
| xmlutil | 1.0.2 | Maven Central metadata |
| RSS-Parser | 6.1.8 | Maven Central metadata |
| Google Play target API | From 31 Aug 2026: new apps/updates → API 36 (Wear/AAOS 35, TV/XR 34). Existing apps → ≥ 35 to stay available to new users. Extension to 1 Nov 2026 | https://developer.android.com/google/play/requirements/target-sdk |
| 16 KB pages | Apps targeting 35+ must support 16 KB on 64-bit. "Starting February 1, 2027, if your app updates don't support 16 KB … you won't be able to release". AGP ≥ 8.5.1 + NDK r28 aligned by default. Java/Kotlin-only apps already compatible | https://developer.android.com/guide/practices/page-sizes |
| Android 15 changes | Edge-to-edge enforced for targetSdk 35. `dataSync` FGS 6 h / 24 h. `mediaPlayback`/`dataSync` FGS can't start from `BOOT_COMPLETED` | https://developer.android.com/about/versions/15/behavior-changes-15 · https://developer.android.com/develop/background-work/services/fgs/service-types |
| Android 16 (targeting 36) | Edge-to-edge opt-out disabled. Predictive back default (`onBackPressed` not called). Large-screen orientation/resizability ignored (temporary opt-out). `elegantTextHeight` ignored | https://developer.android.com/about/versions/16/behavior-changes-16 |
| Android 16 (all apps) | Job runtime quota now applies to jobs started in top state and to jobs running alongside an FGS. Suggests UIDT for user-initiated transfers | https://developer.android.com/about/versions/16/behavior-changes-all |
| Android 17 (targeting 37) | Background audio needs a WIU FGS. Large-screen opt-out removed. CT on by default. `ACCESS_LOCAL_NETWORK` required. ECH used if the library supports it. Static-final reflection blocked. Lock-free MessageQueue. Widget bitmap memory limit | https://developer.android.com/about/versions/17/behavior-changes-17 · https://developer.android.com/about/versions/17/changes/bg-audio |
| Android 17 (all apps) | Background audio hardening (visible activity or non-shortService FGS). App memory limits. `usesCleartextTraffic` deprecation planned | https://developer.android.com/about/versions/17/behavior-changes-all |
| Android 17 release date | Stable 16 Jun 2026; platform stability Beta 3 (Mar 2026). **Third-party sources only**; the official page confirms QPR1/QPR2 (37.1/37.2) betas exist, implying 37 is final | https://www.androidauthority.com/android-17-release-date-3639790 · https://developer.android.com/about/versions/17 |
| Notification permission | `POST_NOTIFICATIONS` on 33+. Media-session notifications exempt. No permission needed to start an FGS | https://developer.android.com/develop/ui/views/notifications/notification-permission |
| Per-app language | `generateLocaleConfig = true` + `resources.properties`. Compose apps must extend `AppCompatActivity` for `setApplicationLocales`. `autoStoreLocales` service for API ≤ 32 | https://developer.android.com/guide/topics/resources/app-languages |
| Data-transfer guidance | WorkManager (< 10 min, deferrable), UIDT for user-initiated with progress, FGS types for special cases | https://developer.android.com/about/versions/15/changes/datasync-migration |
| API distribution | Cumulative: 24 ≈ 96.6%, 26 ≈ 96.1%, 28 ≈ 93.5%, 29 ≈ 91.1%, 31 ≈ 78.8%, 33 ≈ 68.9%, 35 ≈ 41.0%, 36 ≈ 22.3% (Statcounter, Apr 2026 data, updated 28 May 2026) | https://apilevels.com/ |
| Licences (from POMs) | Apache-2.0: AndroidX (compose, room3, media3, navigation3, sqlite-bundled), Dagger/Hilt, Koin, Metro, OkHttp, Okio, Ktor, Coil, kotlinx.*, SQLDelight, xmlutil, RSS-Parser, AboutLibraries, Licensee. MIT: MaterialKolor, Robolectric. GPL-2.0+CPE: desugar_jdk_libs. BSD-style/PD: kxml2 | Maven POMs on repo1.maven.org / dl.google.com |
| NewPipeExtractor licence | GPL-3.0 (JitPack distribution) | https://github.com/TeamNewPipe/NewPipeExtractor |
| Unlicense GPL compatibility | FSF: "Both public domain works and the lax license provided by the Unlicense are compatible with the GNU GPL" | https://en.wikipedia.org/wiki/Unlicense (quoting FSF) · https://ftp.gwdg.de/pub/gnu/www/licenses/license-list.html (FSF mirror; gnu.org returned 503) |

UNVERIFIED items (flagged in text):
* FTS5 availability with framework SQLite and the exact compile options of `sqlite-bundled`.
* The `KotlinAndroidProjectExtension` type name under built-in Kotlin.
* The exact `NavDisplay` parameter name for scene strategies in 1.2.
* WorkManager support for UIDT (absent from the notes).
* Whether the platform XmlPullParser honours the `relaxed` feature.
* CT failure mode and any opt-out.
* That API 37 will be the Aug 2027 Play requirement.

---

## Pitfalls & edge cases

**Build and toolchain**
1. **Applying `org.jetbrains.kotlin.android` with AGP 9 fails the build.** So does `kotlin-kapt`. Any third-party library or sample that still says "apply kotlin-android" must be adapted.
2. **KGP version drift.** AGP 9 depends on KGP 2.2.10 at runtime and silently upgrades *lower* versions. If build-logic only has KGP as `compileOnly`, you might compile with a different Kotlin than you think. Assert in CI: `./gradlew buildEnvironment | grep kotlin-gradle-plugin:2.4.20`.
3. **Outside the tested matrix.** Kotlin 2.4.20 is tested to AGP 9.3.1 and Gradle 9.7.0. Don't bump Gradle to 9.8 until Kotlin's table includes it. Keep AGP 9.3.3 as the fallback.
4. **Compose dictates compileSdk.** Compose 1.12 needs compileSdk 37 and AGP ≥ 9.2. The next Compose (1.13) needs **37.1**, which may in turn require a newer AGP. Plan BOM bumps together with AGP bumps.
5. **`targetSdk` defaults to `compileSdk` in AGP 9.** Forgetting to set it means a compileSdk bump silently changes runtime behaviour. Set it explicitly in the convention plugin.
6. **Material 3 versions.** Overriding `material3` to an alpha affects the whole app, and the BOM no longer protects you from mismatched `material3-adaptive-navigation-suite`. Pin both together. Wrap component usage in `:core:designsystem` so an alpha API change is a one-module fix.
7. **`material-icons-extended`** is huge and unmaintained. Don't add it "for convenience".
8. **KSP and Hilt incremental quirks** after Kotlin bumps. Clean CI caches when bumping Kotlin, KSP or Hilt together.

**Data**
9. **Room 3 is new.** Only about 3 months stable, so search results and AI suggestions will often show Room 2 APIs:
   * `runInTransaction` becomes `withWriteTransaction`;
   * `Cursor` queries become `useReaderConnection` / `usePrepared`;
   * `Migration.migrate(SupportSQLiteDatabase)` becomes the `SQLiteConnection` variant;
   * there is no `allowMainThreadQueries`.

   Write one reference DAO and migration test early.
10. **Two Room runtimes.** WorkManager still uses Room 2 internally. Room 3's new package avoids conflicts, but both runtimes ship. That is acceptable, but expect a few hundred KB (UNVERIFIED size).
11. **Bundled SQLite adds native libraries** per ABI. Check the 16 KB alignment of the shipped `.so` (CI `zipalign -P 16`) and measure APK and AAB size. If unacceptable, switch to `AndroidSQLiteDriver` and avoid SQL features newer than 3.18: window functions, `UPSERT` syntax, `RETURNING`, JSON functions (JSON1 may also be unavailable on old platform builds, UNVERIFIED). Room's `@Upsert` annotation does not need SQL `UPSERT`.
12. **FTS5 vs FTS4.** Room 3 supports `@Fts5`, but availability depends on the driver's SQLite build (UNVERIFIED for both drivers). Start with `@Fts4`, which is universally available, and spike FTS5 with the bundled driver before relying on it.
13. **DataStore single-instance rule.** Two `DataStore` objects on the same file crash at runtime. Always inject the singleton.

**UI and navigation**
14. **AppCompatActivity for per-app locales** requires an AppCompat theme (`Theme.AppCompat.DayNight.NoActionBar` descendant) as `postSplashScreenTheme`, or `AppCompatActivity` throws at launch. `setApplicationLocales` recreates the Activity: Nav3 back stack and ViewModels must survive recreation (`rememberNavBackStack` is saveable).
15. **Nav3 ViewModel scoping.** Without `rememberViewModelStoreNavEntryDecorator()`, `hiltViewModel()` inside an entry scopes to the Activity. Two `GroupFeedKey(1)` and `GroupFeedKey(2)` entries would then share one VM, so the wrong feed shows.
16. **Nav3 keys must be `@Serializable` and implement `NavKey`** for `rememberNavBackStack` state saving. Adding a non-serializable field (for example a `Uri` or `Episode`) breaks process-death restore. Pass IDs only.
17. **Edge-to-edge + mini-player.** The mini-player must sit above the gesture handle or 3-button bar, and the list's bottom content padding must include *both* the mini-player height and the nav-bar inset, or the last episode is unreachable. Test 3-button navigation, gesture navigation, landscape with a side nav bar, and display cutouts.
18. **Predictive back with the full-screen player.** If the player is an overlay rather than a nav entry, it needs its own back handler. Calling `onBackPressedDispatcher.onBackPressed()` from inside a handler breaks the animation (activity lint warns).
19. **Large screens at targetSdk 37.** There is no orientation lock or letterboxing escape hatch. Every screen must work at tablet, foldable-inner and landscape-phone sizes, and in split-screen.
20. **Cover images.** Podcast covers are often 3000×3000 PNGs, sometimes animated GIFs, sometimes broken (HTML error pages served as `image/jpeg`). YouTube channel avatars are square but video thumbnails are 16:9, so `CoverArt` must crop or letterbox consistently.
    * Palette or MaterialKolor extraction needs a software bitmap (`allowHardware(false)` on that request only).
    * Sized requests prevent OOM, especially under Android 17 memory limits.

**Networking and parsing**
21. **User-Agent.** Some hosts reject OkHttp's default UA. Analytics prefixes and redirect chains (Podtrac, Chartable successors, etc.) need `followRedirects` and correct UA forwarding.
22. **Cleartext.** Many feeds and enclosures are `http://`. Without the network-security config the app fails with `CLEARTEXT communication not permitted`.
23. **Certificate Transparency on targetSdk 37.** Some self-hosted feeds may start failing TLS. Show a per-feed error and don't silently drop the feed.
24. **XML in the wild:**
    * undefined entities (`&nbsp;`, `&rsquo;`) outside CDATA;
    * a declared charset that differs from the HTTP header or a BOM;
    * HTML inside `<description>` with and without CDATA;
    * `itunes:` prefix variations;
    * duplicate GUIDs, or no GUIDs at all (fall back to enclosure URL + pubDate hash);
    * RFC-822 dates in many broken variants;
    * multi-MB feeds with 2,000+ items. Stream, don't buffer.

    Handle 301s and `itunes:new-feed-url` / `podcast:newFeedUrl` by updating `feed_url`.
25. **`runCatching` in coroutines** swallows `CancellationException`, which breaks structured cancellation of refreshes. Use a `suspendRunCatching` helper that rethrows it.

**Background, playback and permissions**
26. **Android 17 background-audio hardening fails silently.** Features like "auto-play when headphones connect" (via a BroadcastReceiver), "resume after reboot" or "alarm wake-up podcast" will simply produce no sound on targetSdk 37. They must route through user-initiated or media-key paths, or be dropped. Keep the FGS alive during buffering stalls of up to 10 minutes.
27. **Android 15 `BOOT_COMPLETED`.** You can't start a `mediaPlayback` or `dataSync` FGS from boot. Use WorkManager for post-boot scheduling (it reschedules itself).
28. **Android 16 job quotas.** A WorkManager download running while music plays is now quota-limited. Workers must checkpoint (HTTP Range resume) and tolerate stops.
29. **`dataSync` 6 h / 24 h limit** (Android 15+). A large OPML import that triggers hundreds of episode downloads must not rely on a single long `dataSync` FGS.
30. **POST_NOTIFICATIONS denied.** Download-progress notifications become invisible (FGS notices go only to Task Manager), but the media notification still shows (exempt). The UI must expose download progress in-app.

**Licensing**
31. **Packaging excludes.** Common `packaging { resources.excludes += "META-INF/LICENSE*" }` snippets strip licence files from the APK. That is only OK if the AboutLibraries screen reproduces them. Keep NOTICE content available.
32. **JitPack** (if NewPipeExtractor is used) is a supply-chain risk. Restrict it with a `content { includeGroup(...) }` filter and pin exact versions or commit hashes. Consider Gradle dependency verification metadata.

---

## Open questions for the product owner

1. **Material 3 Expressive at launch?** Do you want the new "Expressive" look (wavy progress, button groups, floating toolbars, expressive motion) in v1, accepting an alpha `material3` dependency? Or ship on stable Material 3 and adopt Expressive when 1.5.0 is stable?
2. **Licence of the shipped app.** Is it acceptable for the distributed APK to be GPL-3.0 (for example, if the YouTube module uses NewPipeExtractor)? Or must every dependency be permissive so the app stays effectively public domain?
3. **Distribution channels.** Google Play only, or also F-Droid or GitHub releases? This affects the YouTube approach, the use of any proprietary SDKs (Cast, Firebase, Play Services) and the GPL question.
4. **Minimum Android version.** Is Android 8.0 (minSdk 26, about 96% of devices) right? Would you trade reach for a smaller test matrix with Android 10 (29, about 91%)? Dynamic colour will only be available on Android 12+ either way.
5. **Tablets, foldables, landscape.** Android 16/17 force our app to resize on large screens. Should v1 have *designed* two-pane tablet layouts (groups beside the feed, list beside detail), or just "not broken"?
6. **Auto-start behaviours.** Do you want "auto-play when headphones/Bluetooth connect", "resume after reboot" or alarm-style wake-up podcasts? Android 15/17 restrict these heavily, and some may be impossible.
7. **Where should downloads live?** In app-private storage (simplest, no permission, deleted on uninstall) or a user-visible folder chosen by the user (more complex, survives uninstall)?
8. **Plain-HTTP feeds.** OK to allow `http://` feeds and episode files? Many podcasts still use them; the recommendation is yes.
9. **Other surfaces in scope later?** Android Auto, Wear OS, Chromecast, home-screen widgets. These affect module layout and permissions (casting needs the new local-network permission on Android 17).
10. **Languages for v1.** Which ones? This drives the per-app language list and translation workflow.
11. **Crash reporting and analytics.** None, a self-hosted option (for example ACRA), or Firebase Crashlytics (proprietary; affects F-Droid and the licence posture)?
12. **App ID.** What application ID (package name) should Neutrodyne use? It is permanent once published on Play.
