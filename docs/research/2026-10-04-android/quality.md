# Neutrodyne: testing, CI/CD, distribution and privacy

Research date: 2026-10-04. Every version below was checked on that date. Most came from Maven Central or Google Maven `maven-metadata.xml`, or from `git ls-remote` for GitHub Actions; the full table with source URLs is in "Verified versions & facts". Anything I could not confirm is marked **UNVERIFIED**.

These notes assume the baseline in the sibling notes:

* **Toolchain:** AGP 9.4.1 with built-in Kotlin, Kotlin 2.4.20, KSP 2.3.12, Gradle 9.7.x, JDK 21 to run Gradle.
* **SDK levels:** minSdk 26, compileSdk and targetSdk 37.
* **Libraries:** Room 3 (`androidx.room3`) with `BundledSQLiteDriver`, OkHttp 5, Coil 3, Media3 1.11.1, Hilt.
* **Modules:** a `build-logic` convention-plugin build, a pure-JVM `:feeds` module (XmlPullParser, with kxml2 in tests) and `:core:testing`.
* **Flavours:** `foss` (YouTube stream extraction via NewPipeExtractor, GPL-3.0) and `play` (no stream extraction, plus Cast).

---

## Recommendation

### The engineering process in one table

| Concern | Choice for Neutrodyne | Why |
|---|---|---|
| Unit-test framework | **JUnit 4.13.2** everywhere. Use **TestParameterInjector 1.24** for parameterised and corpus tests. | Robolectric, Compose test rules, Room `MigrationTestHelper`, Roborazzi and Media3 test utils are all JUnit 4 rules or runners. JUnit 6 on Android needs the third-party `android-junit5` plugin, and its instrumentation support requires API 35+ devices. One framework keeps the convention plugin simple. |
| Assertions | **Truth 1.4.5**. Media3 test-utils already pulls it in transitively. | Same assertion style everywhere; good failure messages for collections and goldens. |
| Coroutines and Flow | **kotlinx-coroutines-test 1.11.0** (`runTest`, `StandardTestDispatcher`, a `MainDispatcherRule` in `:core:testing`) plus **Turbine 1.2.1**. | Turbine is the de facto standard for asserting `StateFlow`/`Flow` emissions. Dispatchers are injected (see stack.md), so tests never touch `Dispatchers.IO`. |
| Test doubles | **Hand-written fakes first**, in `:core:testing`: `FakePodcastRepository`, `FakeEpisodeRepository`, `FakeGroupRepository`, `FakePlaybackController`, `FakeDownloadManager`, `FakeYouTubeStreamResolver`, `TestClock`. Use **MockK 1.14.11** only for JVM-side edge cases such as final third-party classes and verifying a single callback. Do not use it in `androidTest`. | The same fakes drive ViewModel tests, Compose UI tests, screenshot tests and the debug "demo data" build. Mocks rot with refactors; fakes don't. |
| Database | Room 3 **schema export committed** to `core/database/schemas/`. DAO tests run under Robolectric with a real in-memory database. **Migration tests** use `MigrationTestHelper` on every version pair, plus a "migrate all" test. CI fails if the schema JSON changes without a version bump. | Subscriptions, groups and play positions are user data we must never lose. |
| Parsers | **Golden-file corpus tests** in `:feeds`, which is pure JVM with no Robolectric. Each fixture is a minimised real-world RSS, Atom or OPML file, or a NewPipe JSON or Takeout CSV export, paired with an `.expected.json` golden. A nightly **live-feed canary** runs against about 50 real URLs and only reports; it never blocks. | Feeds in the wild are the biggest source of bugs. Goldens make parser changes reviewable as diffs. |
| Network | **MockWebServer 5.5.0** (`mockwebserver3` + `mockwebserver3-junit4`). Align all OkHttp artifacts with `okhttp-bom` in test configurations as well. | Exercises the real OkHttp stack: conditional GET, redirects, Range/206 resume, throttling, TLS. |
| Android-in-JVM | **Robolectric 4.17** (supports SDK 37), pinned to one SDK in `robolectric.properties`. | Fast Compose UI, DAO, WorkManager and Media3 tests without an emulator. |
| Compose UI tests | The **v2 testing APIs** (`androidx.compose.ui.test.junit4.v2.createComposeRule`, which uses `StandardTestDispatcher`). They run mostly under Robolectric with fakes, plus a few instrumented end-to-end flows. Add accessibility checks via `ui-test-junit4-accessibility`. | The v1 APIs are deprecated. v2 matches `runTest` semantics. |
| Screenshot tests | **Roborazzi 1.76.0** on Robolectric Native Graphics. Coil's `FakeImageLoaderEngine` provides deterministic covers. Reference PNGs are committed. Record and verify **only on Linux CI**. | Paparazzi's last stable release (1.3.5, Nov 2024) predates AGP 9, and 2.0 is still alpha. Google's Compose Preview Screenshot Testing is still alpha (0.0.1-alpha16) and is being folded into AGP "test suites". Roborazzi is stable, releases monthly and shares the Robolectric setup. |
| Playback tests | **media3-test-utils / media3-test-utils-robolectric 1.11.1**: `TestExoPlayerBuilder`, `FakeClock`, `FakeMediaSource`/`FakeTimeline`, `TestPlayerRunHelper.advance(player).untilState(...)`. Add a few instrumented `MediaController` ↔ `MediaSessionService` tests. | Media3's own approach. Covers resume position, speed, sleep timer and queue advance deterministically. |
| Instrumented tests | **Gradle Managed Devices (GMD)**: `aosp-atd` API 36 x86_64 as the main device, plus API 26 `aosp` for the minSdk floor. They run on GitHub-hosted Ubuntu runners with **KVM enabled**. Triggers: pushes to `main`, nightly, and PRs labelled `run-instrumented`. | KVM-accelerated emulators run on standard hosted Linux runners. GMD keeps the device definition in Gradle so local and CI runs are identical. |
| Static analysis | **Android Lint**: `warningsAsErrors`, a baseline, SARIF uploaded to code scanning. **Spotless 8.10.3 + ktlint 1.8.0 + compose-rules 0.6.7** (blocking). **detekt 2.0.0-alpha.6** non-blocking until 2.0 is stable. **Licensee 1.14.1** (licence allow-list). A **proprietary-dependency ban** on `fossRelease`. | detekt 1.23.8, the last stable release, targets Kotlin 2.0.21 and AGP 8.8, which does not match our toolchain. |
| CI | **GitHub Actions** with `gradle/actions/setup-gradle@v6`. Jobs: `static`, `unit` (including Roborazzi verify and the Room-schema drift check), `assemble` (both flavours, unsigned), `instrumented` (GMD), `repro` (nightly and on tags). `release.yml` runs on `v*` tags. All actions are pinned by SHA. | Free and unlimited for public repos on 4-vCPU/16 GB Linux runners. Everything except real-device performance runs there. |
| Dependency updates | **Renovate** (Mend-hosted GitHub app). Grouping for Kotlin/KSP/Compose, AGP, Media3 and AndroidX test. Digest-pinned Actions. A **fast lane for NewPipeExtractor**. | Better grouping and custom-registry handling (JitPack) than Dependabot, and it updates Action digests. Dependabot is the fallback. |
| Versioning | SemVer `versionName`. AntennaPod-style `versionCode = MAJOR·1 000 000 + MINOR·10 000 + PATCH·100 + 95` for stable, `01..94` for betas and RCs. The single source of truth is `gradle.properties`. Tag `vX.Y.Z`. | Deterministic from source, with no git-count magic, which matters for F-Droid and reproducibility. Leaves room for 94 pre-releases per patch. |
| Signing | **One developer-held app-signing key** (RSA 4096, validity of 30 years or more) used for **every channel**: GitHub, Obtainium, IzzyOnDroid, F-Droid (via reproducible builds) and Play (opt out of Google-generated keys **before the first Play release**). | Users can move between channels without uninstalling. Only one key has to be registered for Google's developer verification. |
| Distribution (priority order) | 1. **GitHub Releases** (`foss` APK, SHA256SUMS, cert fingerprint) with an **Obtainium** badge. 2. **IzzyOnDroid** (picks up the GitHub APK within days). 3. **F-Droid main repo** with `Binaries:` + `AllowedAPKSigningKeys`, so F-Droid publishes our developer-signed APK after verifying it rebuilds bit-for-bit. Expect a `NonFreeNet` anti-feature for YouTube. 4. **Google Play** `play` flavour (no YouTube stream extraction or download), if the product owner wants Play. | The `foss` build is the "real" app. YouTube breakages need same-day hotfixes, which only GitHub/Obtainium and IzzyOnDroid can deliver. F-Droid takes days, and Play bans the feature outright. |
| Crash reporting | **ACRA 5.14.2** with `acra-mail` + `acra-dialog`: per-crash user consent, then a pre-filled email with the report attached as a file. `reportContent` is limited to stack trace, versions and device model. **No LOGCAT**, URLs redacted, no network sender. A "Copy diagnostics / open GitHub issue" screen in Settings. | Zero network traffic from the reporter. NewPipe ships exactly this setup on F-Droid without an anti-feature (its fdroiddata maintainer note says ACRA is only used via email that users send themselves). |
| Privacy | **No analytics, no ads, no Firebase, no Play Services in `foss`**, and no accounts. The app talks only to hosts the user subscribed to, the hosts their feeds reference, an optional directory search and (in `foss`, when used) YouTube. Publish a `PRIVACY.md`. Play's Data safety form: "Crash logs: optional, user-initiated" only. | It is a product value, and it keeps F-Droid free of the `Tracking` anti-feature. |
| Localisation | **Hosted Weblate (Libre plan)**. Components: Android `strings.xml` (strings centralised in a few modules) plus fastlane metadata. Weblate opens PRs and CI lints them. `generateLocaleConfig = true`. Pseudo-locales in debug, plus screenshot tests for `en-XA`/`ar-XB`. | Free for public FOSS projects (160k hosted strings). It is what F-Droid apps use, and AntennaPod and NewPipe are both on Hosted Weblate. |
| Performance | A **Baseline Profile + Startup Profile** (`androidx.baselineprofile` 1.5.0) generated on a GMD on demand and committed as text. **Macrobenchmark** (startup, cover-grid scroll) run on a physical device before releases. **R8 full mode** (default) via the AGP 9.3+ `optimization { enable = true }` DSL, with `-dontobfuscate`. An APK-size budget check in CI. | Profiles measurably help Compose cold start and scrolling. With `-dontobfuscate`, stack traces in emailed crash reports stay readable without mapping files. |

### Risks that could change the overall plan

1. **Google developer verification.** Enforcement for apps installed on certified devices began **2026-09-30** in Brazil, Indonesia, Singapore and Thailand, and Google says it will go global in **2027**. If the developer does not register (identity check, $25 "Full Distribution" Android Developer Console account, registered package name and signing key), every sideloaded install (GitHub, Obtainium, IzzyOnDroid, F-Droid) needs the user to go through Google's "advanced flow". That flow involves Developer Options and a **24-hour wait**. This is a product-owner decision with legal-identity implications, and it is the single biggest distribution risk.
2. **Play and YouTube.** Play policy prohibits apps that "access or use a service or API in a manner that violates its terms of service". A Play build cannot ship YouTube stream extraction or downloads; see youtube.md for the layer A/B split. The Play flavour also **must not self-update or steer users to the `foss` APK**. If the product owner considers Play essential *with* YouTube audio, the requirement cannot be met.
3. **Licence.** NewPipeExtractor is GPL-3.0, so the `foss` binary is a GPL-3.0 combined work. The source code can stay Unlicense. The F-Droid `License:` field and the in-app licence screen must reflect this.
4. **Reproducible builds are a commitment, not a checkbox.** Known sources of non-determinism are the `baseline.prof` produced by the ArtProfile tasks, some R8 optimisations, timestamps, SDK platform revision and JDK version. If we cannot keep `fossRelease` bit-for-bit reproducible, F-Droid falls back to signing with **its own key**. That breaks channel interchangeability, and we would also have to register F-Droid's key with Google.
5. **YouTube moves faster than F-Droid.** F-Droid's build farm handles about 100 to 200 app builds a week and can take days. IzzyOnDroid and Obtainium are the hotfix channels, so the release pipeline must produce a signed, tagged release in under 30 minutes.
6. **Tooling on alpha.** detekt 2.0 and Paparazzi 2.0 are both alpha. Our plan uses neither as a gate. If the product owner wants strict static analysis on day one, detekt 2.0-alpha would have to be a blocking check and pinned exactly.

---

## Options considered

### Unit-test framework: JUnit 4 vs JUnit 5/6

| | JUnit 4.13.2 (+ TestParameterInjector) | JUnit Jupiter 6.1.3 (via `android-junit5` 2.0.1 on Android) |
|---|---|---|
| Robolectric | Native (`RobolectricTestRunner`) | Only through the Vintage engine, so JUnit 4 is still needed |
| Compose, Room, Media3, Roborazzi | All JUnit 4 rules | Need the Vintage engine or rule adapters |
| Instrumented | `AndroidJUnitRunner` | JUnit 6 instrumentation needs **API 35+ devices** (plugin README), and our minSdk is 26 |
| Parameterised and corpus tests | TestParameterInjector (`@TestParameter(valuesProvider = …)`) | `@ParameterizedTest`, `@TestFactory` (nicer) |
| Pure-JVM modules (`:feeds`) | Works | Works out of the box (`useJUnitPlatform()`) |

**Decision:** JUnit 4 everywhere. The only real Jupiter win is `@TestFactory` dynamic tests for the corpus, and TestParameterInjector's value providers cover that. Revisit if Robolectric gains a first-class Jupiter extension.

### Fakes vs MockK

* **Fakes** are written once, tested themselves (a small `FakeEpisodeRepositoryTest` checks the fake honours the interface contract), and reused by UI, screenshot and demo builds. The cost is that they need a stable `api`/interface layer, which stack.md's `:*:api` modules already provide.
* **MockK** is fast to write but couples tests to call sequences. Inline mocking on Android instrumentation has API-level caveats.
* **Decision:** use fakes. Keep MockK as a `testImplementation`-only tool for the rare final third-party class. Lint/detekt cannot enforce this, so the PR template says "no MockK in androidTest".

### Screenshot testing: Roborazzi vs Paparazzi vs Compose Preview Screenshot Testing

| | Roborazzi 1.76.0 | Paparazzi 2.0.0-alpha05.1 (stable 1.3.5) | Compose Preview Screenshot Testing 0.0.1-alpha16 |
|---|---|---|---|
| Engine | Robolectric Native Graphics (real framework rendering on the JVM) | layoutlib (Android Studio's renderer) | layoutlib; runs `@Preview`s from a `screenshotTest` source set |
| Status (2026-10) | Stable 1.x, released 2026-09-29 | 1.3.5 was the last stable (2024-11-06). 2.0 has been alpha since 2025-04 and needs Java 21+. 2.0.0-alpha05.1 states AGP 9 support | **Alpha.** AGP 9.5.0-alpha03+ steers configuration to "AGP test suites" |
| Coexists with Robolectric in one module | Yes, it *is* Robolectric | No (Roborazzi's README states Paparazzi is incompatible with Robolectric) | Separate source set |
| Hilt / ViewModels / interactions | Yes; can capture after clicks and capture GIFs | Static rendering only | Previews only |
| Preview scanning | `generateComposePreviewRobolectricTests` (ComposablePreviewScanner) | Via a third-party scanner | Built in |
| Speed | Slower (boots Robolectric) | Fastest | Fast |

**Decision:** Roborazzi. It is the only stable option that matches AGP 9 today, and it reuses our Robolectric harness, fakes and `@Config` qualifiers. Revisit Google's tool when AGP test suites are stable.

### Emulator in CI: GMD vs `reactivecircus/android-emulator-runner`

* **GMD.** The device is defined in `build.gradle.kts`, so local `./gradlew pixel…AndroidTest` runs are identical to CI. Gradle provisions the system image and manages snapshots, and sharding is a property. AGP 9 removed the old `deviceProvider` / `testServer` APIs in favour of GMD.
* **android-emulator-runner v2.38.0** is battle-tested; AntennaPod uses it on API 23, 30 and 36 `aosp_atd`. It gives finer control over emulator flags and has a `channel: canary` option.
* **Decision:** start with GMD for parity with local runs. Keep android-emulator-runner as a documented fallback if GMD + KVM is flaky on hosted runners.

### Static analysis

* **Android Lint.** Mandatory; it runs anyway (`lintVital` on release). Use `warningsAsErrors` with a committed baseline and upload SARIF.
* **ktlint vs ktfmt.** ktlint 1.8.0 via Spotless 8.10.3. It auto-fixes, supports `.editorconfig`, and the compose-rules ktlint ruleset (0.6.7) lints Compose conventions such as modifier ordering, state hoisting and `remember` keys. ktfmt is equally good; ktlint wins only because compose-rules plugs into it.
* **detekt.** Valuable for complexity and code-smell rules. However, the compatibility table lists **1.23.8 → Kotlin 2.0.21 / AGP 8.8.1** and **2.0.0-alpha.6 → Kotlin 2.4.10 / AGP 9.3.1 / Gradle 9.6.1**, so only the alpha fits our toolchain. Run it non-blocking with an exact pin.
* **Slack compose-lint-checks 1.6.0.** An Android Lint alternative to compose-rules. Use one or the other; I'd pick compose-rules (ktlint), so formatting and Compose style come from one tool.

### Dependency updates: Renovate vs Dependabot

| | Renovate (Mend-hosted app) | Dependabot |
|---|---|---|
| Version catalogs, plugins, build-logic | Yes (custom parser) | Yes (`libs.versions.toml` supported since 2023-03) |
| Gradle wrapper | Has a `gradle-wrapper` manager; regenerating the wrapper JAR requires running Gradle (self-hosted needs `allowedUnsafeExecutions: ["gradleWrapper"]`). **Behaviour on the hosted app is UNVERIFIED.** | A secondary source says it runs Gradle to update all wrapper files (**UNVERIFIED** in GitHub's own docs) |
| Grouping and scheduling | Very flexible (`packageRules`, `groupName`, schedules, automerge, dependency dashboard) | `groups:` supported, less flexible |
| GitHub Actions digest pinning | `helpers:pinGitHubActionDigests` | Updates SHAs if you pin them |
| JitPack (NewPipeExtractor) | Reads repository URLs from Gradle files | Works, less configurable |
| Lockfiles | Supported | Gradle lockfiles GA 2025-06-24 |

**Decision:** Renovate, for grouping (Kotlin + KSP + Compose compiler plugin must move together; AGP + Lint move together) and a NewPipeExtractor fast lane. If the hosted app does not regenerate `gradle-wrapper.jar`, add `gradle-update/update-gradle-wrapper-action` on a monthly schedule, or just do it manually; it is a one-line Renovate exclusion.

### Distribution channels

| Channel | Build | Signing | Latency | Constraints |
|---|---|---|---|---|
| GitHub Releases + Obtainium | `fossRelease` APK | Developer key | Minutes | Needs the release pipeline. Obtainium picks the APK by regex, so name assets predictably |
| IzzyOnDroid | Same APK as GitHub | Developer key | Hours to days | FOSS licence, APK attached to GitHub releases, active within 12 months, screens for trackers and proprietary libs |
| F-Droid main | F-Droid rebuilds `foss` from the tag | Developer key **if** reproducible (`Binaries:`), otherwise F-Droid's key | Days | 100% FLOSS toolchain and dependencies (Maven Central, Google Maven, JitPack are allowed). No Play Services, Firebase or Crashlytics. No Google "dependency info block". Anti-features declared. Their recipe may `rm` the test dirs |
| Google Play | `playRelease` AAB | Developer key uploaded via "Provide a copy of your app signing key", or Google-generated | Hours to days (review) | No YouTube extraction or downloads; target API 36+ (from 2026-08-31); FGS declarations with demo videos; Data safety form; no self-update; AAB with Play App Signing |

### Crash reporting

| Option | Network? | F-Droid view | Play Data safety | Verdict |
|---|---|---|---|---|
| **ACRA + mail sender + dialog** | No (hands off to the user's mail app) | Accepted (NewPipe precedent) | "Crash logs, optional" | **Chosen** |
| ACRA + HTTP sender to self-hosted Acrarium | Yes | Possibly `Tracking` ("reports your activity… even when it can be turned off"); needs care | Must declare | Rejected: server ops plus privacy cost |
| Sentry or Crashlytics | Yes | Crashlytics is explicitly disallowed; Sentry is FOSS but SaaS by default | Must declare | Rejected |
| Nothing (Play Android vitals only) | No | Fine | Nothing | Too little signal for `foss` users, who are the majority |

---

## Technical detail

### 1. Test pyramid and where each test runs

```
                ┌────────────────────────────────────────────┐
  GMD (nightly, │ ~15 E2E: subscribe→group feed→play→download│  :app androidTest
   main, label) │ MediaController↔Service, Room migrations*  │  :core:database androidTest
                └────────────────────────────────────────────┘
          ┌──────────────────────────────────────────────────────┐
 JVM +    │ Compose UI (v2 rule) with fakes, Roborazzi screenshots│ feature:* test/
 Robolectric│ DAO tests, WorkManager TestDriver, Media3 player logic│ core:database, playback:impl
          └──────────────────────────────────────────────────────┘
   ┌──────────────────────────────────────────────────────────────────┐
   │ Pure JVM: feed/OPML/NewPipe-JSON/Takeout-CSV corpus goldens,       │ :feeds, :core:domain,
   │ ViewModels (Turbine), repositories (fakes + MockWebServer), utils  │ :core:data, :youtube:impl
   └──────────────────────────────────────────────────────────────────┘
* Migration tests can also run under Robolectric (see §4); keep one instrumented copy.
```

Rules of thumb:

* A PR must be green on `static`, `unit` and `assemble`, which takes about 12 minutes.
* `instrumented` runs on `main`, nightly, or a PR labelled `run-instrumented`.
* Release tags additionally require `instrumented` on the `fossRelease` **minified** build, because AGP 9 only creates unit tests for the tested build type, so R8 bugs show up only here.

### 2. Shared Gradle test configuration (convention plugin in `build-logic`)

```kotlin
// build-logic/convention/src/main/kotlin/neutrodyne.android.testing.gradle.kts (sketch)
extensions.configure<com.android.build.api.dsl.CommonExtension> {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true      // Robolectric + Roborazzi need merged resources
            isReturnDefaultValues = false         // fail loudly on unmocked android.* calls
        }
        animationsDisabled = true
        execution = "ANDROIDX_TEST_ORCHESTRATOR"  // isolates instrumented tests (orchestrator 1.6.1)
    }
}
tasks.withType<Test>().configureEach {
    // Catch Locale/TimeZone bugs: RFC-822 dates parsed with default locale, "%.1f" decimal commas, etc.
    systemProperty("user.timezone", "America/St_Johns")   // UTC-3:30, DST, non-integral offset
    jvmArgs("-Duser.language=de", "-Duser.country=DE", "-Xshare:off")
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    // Golden update switch: ./gradlew :feeds:test -PupdateGoldens
    systemProperty("neutrodyne.updateGoldens", providers.gradleProperty("updateGoldens").isPresent)
    systemProperty("neutrodyne.fixturesDir",
        layout.projectDirectory.dir("src/test/resources/fixtures").asFile.absolutePath)
}
dependencies {
    "testImplementation"(platform(libs.okhttp.bom))   // force mockwebserver 4.x from media3-test-utils → 5.5.0
    "testImplementation"(libs.junit4)
    "testImplementation"(libs.truth)
    "testImplementation"(libs.kotlinx.coroutines.test)
    "testImplementation"(libs.turbine)
    "testImplementation"(libs.robolectric)
    "testImplementation"(project(":core:testing"))
}
```

* `src/test/resources/robolectric.properties` in each Android module: `sdk=36`. Pinning one SDK avoids downloading several `android-all-instrumented` jars, which are about 100 MB each. Bump to 37 once Roborazzi baselines are re-recorded. Robolectric 4.17 supports SDK 37.
* Robolectric 4.16+ needs **JDK 21** to run SDK 36 tests (4.16 release notes). Our CI uses Temurin 21.
* With AGP 9, `android.onlyEnableUnitTestForTheTestedBuildType=true` is the default, so only `test<Flavor>DebugUnitTest` exists. Keep flavour-specific code in a handful of modules (`:app`, `:youtube:impl-streams`, `:playback:impl-cast`) so library tests run once, not once per flavour.

### 3. `:core:testing` contents

* `MainDispatcherRule(dispatcher = StandardTestDispatcher())`, a JUnit 4 `TestWatcher` that calls `Dispatchers.setMain` and `Dispatchers.resetMain`.
* `TestClock` implementing the `Clock` interface from `:core:common`, so tests never call `System.currentTimeMillis()` (sleep timer, "new episodes since", retention).
* Fakes backed by `MutableStateFlow`, each with a test-only `emit…()`/`fail…()` API.
* Test data builders such as `podcast(id = 1, title = "Tech Talk", groups = setOf(TECH))` and `episode(…)`, which keep tests readable.
* `fakeImageLoader(context)`: a Coil `ImageLoader` with `FakeImageLoaderEngine` that returns deterministic `ColorImage`s keyed by URL hash. Used by Compose and screenshot tests.

```kotlin
// ViewModel test with Turbine
class GroupFeedViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val episodes = FakeEpisodeRepository()

    @Test fun `group feed is newest first and excludes other groups`() = runTest {
        val vm = GroupFeedViewModel(GroupFeedKey(TECH), episodes, TestClock(NOW))
        vm.uiState.test {
            assertThat(awaitItem()).isEqualTo(GroupFeedUiState.Loading)
            episodes.emit(
                episode(1, group = TECH, published = "2026-10-01T08:00Z"),
                episode(2, group = NEWS, published = "2026-10-03T08:00Z"),
                episode(3, group = TECH, published = "2026-10-03T09:00Z"),
            )
            val loaded = awaitItem() as GroupFeedUiState.Loaded
            assertThat(loaded.episodes.map { it.id }).containsExactly(3L, 1L).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

### 4. Room 3: schema export, migration tests, drift check

* **Schema export.** The `androidx.room3` Gradle plugin with `room3 { schemaDirectory("$projectDir/schemas") }`. The JSON files are committed and reviewed like code.
* **Assets for tests:**
  * `android { sourceSets { getByName("androidTest").assets.srcDir("$projectDir/schemas") } }` for instrumented tests;
  * the same for the `test` source set if migrations run under Robolectric.
* **Helper.** Room 3's `MigrationTestHelper` takes a driver and a database file:

```kotlin
@RunWith(AndroidJUnit4::class)          // works on GMD; also under Robolectric (verify in spike)
class MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        databaseClass = NeutrodyneDatabase::class,
        driver = AndroidSQLiteDriver(),   // see pitfall about BundledSQLiteDriver under Robolectric
        file = instrumentation.targetContext.getDatabasePath("migration-test"),
    )

    @Test fun migrate3to4_keepsGroupMembershipAndPlayPositions() = runTest {
        helper.createDatabase(3).use { c ->
            c.execSQL("INSERT INTO podcast(id, feed_url, title) VALUES (1,'https://ex/a.xml','A')")
            c.execSQL("INSERT INTO podcast_group(podcast_id, group_id) VALUES (1, 7)")
            c.execSQL("INSERT INTO episode(id, podcast_id, guid, position_ms) VALUES (10,1,'g',123456)")
        }
        helper.runMigrationsAndValidate(4, listOf(MIGRATION_3_4)).use { c ->
            c.prepare("SELECT position_ms FROM episode WHERE id = 10").use {
                it.step(); assertThat(it.getLong(0)).isEqualTo(123456)
            }
        }
    }

    @Test fun migrateAll_fromV1() = runTest { /* createDatabase(1), then Room.databaseBuilder(...).addMigrations(*ALL).build() and touch every DAO */ }
}
```

* **Drift check in CI.** After `./gradlew :core:database:kspDebugKotlin`, run `git diff --exit-code core/database/schemas/`. If someone edits an entity without bumping the version, Room rewrites the existing `N.json` and the job fails with a clear message.
* **Policy.** Every released DB version has a `migrateXtoY` test. Auto-migrations still get a test, because `@RenameColumn` and `@DeleteColumn` specs are easy to get wrong.

### 5. Parser and import corpus (`:feeds`, `:youtube:impl`, OPML)

Layout:

```
feeds/src/test/resources/fixtures/
  rss/   itunes-basic.xml + itunes-basic.expected.json
         podcasting20-full.xml        (podcast:guid/chapters/transcript/person/funding/locked/alternateEnclosure)
         atom-paged-rfc5005.xml       (rel="next" pagination)
         dates-mixed.xml              ("Sat, 4 Oct 2026 10:00:00 PDT", "2026-10-04", "04 Okt 2026", no pubDate)
         guid-missing-and-dupes.xml   enclosure-length-zero.xml  relative-urls.xml
         encoding-windows-1252.xml    encoding-lies-utf8-declared-latin1.xml  bom-utf8.xml
         html-cdata-and-entities.xml  undefined-html-entities.xml (&nbsp; &hellip; without DTD)
         control-chars.xml            itunes-new-feed-url.xml     itunes-block-complete.xml
         durations.xml ("1:02:03","3723","62:03","PT1H2M") explicit-variants.xml ("yes","true","explicit","clean")
         images-precedence.xml        huge-5000-items.xml (generated at test time, not committed)
  youtube/ channel-atom.xml  playlist-uulf-atom.xml  shorts-and-live.xml
  opml/  antennapod.opml  pocketcasts.opml  podcastaddict.opml  gpodder.opml  overcast.opml
         nested-categories.opml (→ groups)  type-link-vs-rss.opml  url-vs-xmlUrl.opml  duplicates.opml
         xxe-file.opml  billion-laughs.opml (must be rejected/neutralised, never resolved)
  imports/ newpipe-subscriptions.json  takeout-subscriptions.csv (+ BOM, + localised header)
```

* **Fixture provenance and licence.** Real feeds contain copyrighted text, and this repo is Unlicense (public domain). Minimise every captured feed to its *structure*: keep the quirk, replace the prose with `lorem`, and keep image URLs as `https://example.invalid/...`. Record the origin and capture date in `fixtures/README`. Do not commit binary blobs such as `.jar`, `.so` or `.apk` under `src/test`, because F-Droid's source scanner flags binaries.
* **Golden format.** Serialise the parser's output model to pretty-printed, key-sorted JSON with kotlinx.serialization, so diffs are stable and reviewable.

```kotlin
@RunWith(TestParameterInjector::class)
class FeedCorpusTest(
    @TestParameter(valuesProvider = RssFixtures::class) private val name: String,
) {
    @Test fun parsesToGolden() {
        val xml = fixture("rss/$name.xml")
        val actual = Json.encodeToString(FeedParser().parse(xml.inputStream(), baseUrl = "https://ex/feed"))
        val golden = File(fixturesDir, "rss/$name.expected.json")
        if (updateGoldens) golden.writeText(actual) else assertThat(actual).isEqualTo(golden.readText())
    }
}
class RssFixtures : TestParameterValuesProvider() {     // API name per TPI 1.x; verify against 1.24
    override fun provideValues(context: Context) =
        File(fixturesDir, "rss").list()!!.filter { it.endsWith(".xml") }.map { it.removeSuffix(".xml") }.sorted()
}
```

* **Robustness tests.**
  * For every fixture, truncate at 10 random offsets and assert the parser returns a `ParseResult.Partial` or `Error`, never throws.
  * Assert the 5,000-item generated feed parses in under N seconds on CI with bounded allocation, which guards against accidental DOM building.
* **Security tests.**
  * `xxe-file.opml` references `file:///etc/passwd`. The parser must not resolve external entities. With `XmlPullParser` there is no DTD processing by default, but assert it anyway.
  * `billion-laughs.opml` must finish quickly.
* **JVM parser vs platform parser.** `:feeds` tests use kxml2 (see stack.md). Android's `Xml.newPullParser()` is also kxml-derived, but behaviour can differ (entity handling, `FEATURE_PROCESS_DOCDECL`). Keep a **small instrumented copy** of the corpus test (about 10 fixtures, run on GMD) that uses the platform parser.
* **Live canary** (`nightly.yml`):
  * Runs `./gradlew :feeds:liveCanary`, a separate JVM test task excluded from `test`. It fetches about 50 URLs listed in `fixtures/live-feeds.txt` (top podcasts across hosts such as Libsyn, Megaphone, Acast, Simplecast, Buzzsprout, Podbean, Substack, Patreon-style tokenised URLs excluded) plus five YouTube channels.
  * On failure it opens or updates a GitHub issue. It never blocks merges.
  * YouTube requests from GitHub's Azure IP ranges are often bot-challenged, so the YouTube part is informational only.

### 6. Network tests with MockWebServer 5.5.0

```kotlin
class FeedFetcherTest {
    @get:Rule val server = MockWebServerRule()   // mockwebserver3-junit4

    @Test fun `304 Not Modified keeps episodes and sends validators`() = runTest {
        server.server.enqueue(MockResponse.Builder().code(200)
            .addHeader("ETag", "\"v1\"").addHeader("Last-Modified", "Sat, 04 Oct 2026 08:00:00 GMT")
            .body(fixture("rss/itunes-basic.xml")).build())
        server.server.enqueue(MockResponse.Builder().code(304).build())
        val fetcher = FeedFetcher(testOkHttp(), FakeFeedCacheStore())
        fetcher.fetch(server.server.url("/feed.xml"))
        val second = fetcher.fetch(server.server.url("/feed.xml"))
        assertThat(second).isInstanceOf(FetchResult.NotModified::class.java)
        server.server.takeRequest()
        assertThat(server.server.takeRequest().headers["If-None-Match"]).isEqualTo("\"v1\"")
    }
}
```

Scenarios to cover:

* **Feeds:**
  * 301/308 updates the stored feed URL; 302/307 does not.
  * Redirect loop.
  * HTTP→HTTPS upgrade.
  * gzip and br.
  * 404 counts as transient (YouTube Atom outages); 410 Gone marks the feed dead.
  * 429/503 with `Retry-After`.
  * Slow body via throttling, testing timeouts and cancellation.
  * Huge body above the size cap is aborted.
  * HTML returned instead of XML (captive portal).
  * Wrong `Content-Type`.
  * Basic auth and token query strings (private feeds), which **must be redacted** in logs and crash reports. Add a test that renders a failure into the diagnostics text and asserts no `?token=` or `user:pass@` survives.
* **Downloads** (coordinate with downloads.md):
  * `Range` resume gets 206 with a correct `Content-Range`.
  * A server that ignores `Range` (returns 200) causes a restart.
  * `Content-Length` mismatch.
  * Mid-body disconnect.
  * Disk-full simulation via a fake file sink.
  * Cancel.
  * CDN redirect chains with tracking prefixes.
* **TLS.** Use `okhttp-tls` `HeldCertificate`/`HandshakeCertificates` for HTTPS tests, including the certificate-transparency-on-by-default behaviour stack.md mentions for Android 17. That one is instrumented only.
* **Instrumented tests** need the debug network-security config to allow cleartext to `localhost`/`127.0.0.1`, or use the HTTPS setup. Put it in `src/debug/res/xml/network_security_config.xml` so release behaviour is unaffected.

### 7. Media3 test utilities

Dependencies (playback modules, `testImplementation`):

```
androidx.media3:media3-test-utils:1.11.1
androidx.media3:media3-test-utils-robolectric:1.11.1
```

Media3 1.11.1's POM pulls in `mockwebserver:4.12.0`, `mockito-core:3.12.4`, `media3-transformer`, `media3-effect` and `robolectric:4.16` at compile scope. Our explicit Robolectric 4.17 and the OkHttp BOM win conflict resolution. Check `./gradlew :playback:impl:dependencies --configuration debugUnitTestRuntimeClasspath` in the spike.

* **Logic tests** cover queue advance, resume position, playback speed persistence, skip-silence toggle, chapter seek and sleep timer. Use a real ExoPlayer with fake media and a fake clock:

```kotlin
@RunWith(AndroidJUnit4::class)
class ResumePositionTest {
    @Test fun pausingPersistsPosition() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val player = TestExoPlayerBuilder(ctx).setClock(FakeClock(/* isAutoAdvancing = */ true)).build()
        val store = FakePlaybackStateStore()
        PlaybackStateSaver(player, store, episodeId = 42)            // our class under test
        player.setMediaSource(FakeMediaSource(FakeTimeline(/* windowCount = */ 1)))
        player.prepare()
        TestPlayerRunHelper.play(player).untilPositionAtLeast(10_000) // see API note below
        player.pause()
        TestPlayerRunHelper.advance(player).untilPendingCommandsAreFullyHandled()
        assertThat(store.position(42)).isAtLeast(10_000)
        player.release()
    }
}
```

  `TestPlayerRunHelper` in 1.11 exposes `advance(player)` and `play(player)` with chained `untilState`, `untilPosition`, `untilPositionAtLeast`, `untilStartOfMediaItem`, `untilPlayerError`, `untilPendingCommandsAreFullyHandled` and `ignoringNonFatalErrors()`. The old `run(player)` is deprecated with `@InlineMe(replacement = "TestPlayerRunHelper.advance(player)")`. Exact chaining signatures: check the reference page during the spike.
* **Real decode tests** cover only a few cases, such as MP3 with Xing/VBR seeking and M4A chapters. Use a tiny generated sine-wave asset in `src/test/assets/` (public domain, a few KB) and the `ShadowMediaCodecConfig` rule from `media3-test-utils-robolectric` so Robolectric exposes decoders.
* **Session/service tests** are instrumented on GMD:
  * Build `MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()` and assert that `playWhenReady` survives `pressHome()`.
  * Check the notification via UI Automator 2.4.0 `device.openNotification()`.
  * Assert that the `MediaLibraryService` browse tree (Android Auto) lists groups as browsable nodes.
* **`StubPlayer`/`SimpleBasePlayer`** back a `FakePlaybackController` for Compose UI and screenshot tests (mini-player and full-player states), with no real ExoPlayer needed.

### 8. Compose UI and screenshot tests

```kotlin
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel5)     // Roborazzi helper; add a tablet qualifier class too
class LibraryGridScreenshotTest {
    @get:Rule val compose = createComposeRule()             // androidx.compose.ui.test.junit4.v2
    @Before fun images() { SingletonImageLoader.setUnsafe(fakeImageLoader(ApplicationProvider.getApplicationContext())) }

    @Test fun coverGrid_dark_groupsChips() {
        compose.setContent { NeutrodyneTheme(darkTheme = true, dynamicColor = false) { LibraryScreen(sampleLibraryState()) } }
        compose.onNodeWithText("tech").performClick()
        compose.onRoot().captureRoboImage()                 // → src/test/screenshots/…png
    }
}
```

* **Determinism:**
  * `dynamicColor = false` (wallpaper colours vary).
  * Fixed `TestClock` ("3 days ago" labels).
  * Fixed locale.
  * `FakeImageLoaderEngine` covers.
  * Disable infinite animations. The v2 rule uses `StandardTestDispatcher`; use `mainClock.autoAdvance = false` for progress indicators.
* **Recording policy:**
  * Reference images live under `src/test/screenshots/` and are committed. Keep them small with `roborazzi.record.resizeScale=0.5`; budget under 10 MB total.
  * Record **only** via the `record-screenshots` `workflow_dispatch` job, or by running in the same Linux container locally. Font rasterisation differs on macOS and Windows.
  * PRs run `verifyRoborazziFossDebug`. On failure, upload the `_compare.png` artifacts and post a PR comment, following Roborazzi's sample workflow.
* **Coverage set** (about 40 images):
  * Library grid (phone and tablet, light and dark, 0/1/many podcasts, long titles, missing cover).
  * Group feed.
  * Episode detail (with chapters and transcript link).
  * Mini- and full-player states.
  * Download states.
  * OPML import result.
  * YouTube subscription (both flavours).
  * **`en-XA` and `ar-XB` pseudo-locales.**
  * 200% font scale.
* **Accessibility.** Add `androidx.compose.ui:ui-test-junit4-accessibility:1.12.1` and call `compose.enableAccessibilityChecks()` in UI tests. It fails on missing content descriptions for cover images, small touch targets and low contrast.
* **Preview scanning** (`generateComposePreviewRobolectricTests`) is optional. It turns every `@Preview` into a screenshot test, which is cheap coverage, but it doubles image count. Enable it once the design system stabilises.

### 9. WorkManager and background work

* Use `work-testing:2.12.0`. Call `WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build())`, then `getTestDriver(ctx)!!.setAllConstraintsMet(id)` / `setPeriodDelayMet(id)` to test the refresh schedule and download constraints (unmetered, charging) under Robolectric.
* Android 16 job quotas while an FGS runs, and Android 17 background-audio hardening (see stack.md and downloads.md), **cannot be faithfully reproduced in Robolectric**. Add two GMD tests on API 36 and API 37:
  1. A download continues while audio plays.
  2. Playback started from the notification after 10 minutes in the background still works.
* Nightly only, since they are slow.

### 10. Gradle Managed Devices

```kotlin
// :app build.gradle.kts (via a convention plugin)
android {
    testOptions {
        managedDevices {
            localDevices {
                create("pixel6Api36Atd") { device = "Pixel 6"; apiLevel = 36; systemImageSource = "aosp-atd" }
                create("pixel2Api26")    { device = "Pixel 2"; apiLevel = 26; systemImageSource = "aosp" }  // minSdk floor; ATD starts at 30/31
            }
            groups { create("ci") { targetDevices.add(localDevices["pixel6Api36Atd"]); targetDevices.add(localDevices["pixel2Api26"]) } }
        }
    }
}
```

* **Run:** `./gradlew ciGroupFossDebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`.
* **Sharding:** `android.experimental.androidTest.numManagedDeviceShards=2` in CI only. A 4-vCPU runner can host about two emulators.
* **Images:**
  * `aosp_atd`/`google_atd` x86_64 images exist for **API 31 to 36** in Google's repository XML (fetched 2026-10-04). The GMD doc page still says "ATD supports only API 30", which is stale.
  * API 37 images currently exist only as `google_apis_ps16k`/`google_apis_playstore_ps16k` (16 KB page size). **UNVERIFIED** whether GMD can target the `ps16k` variants. If it can't, use android-emulator-runner for the API 37 nightly job.
  * That job also serves as our 16 KB page-size test, which matters because `BundledSQLiteDriver` ships native `.so` files.
* **Release-build instrumented run.** Set `testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")` (the AntennaPod pattern) so nightly runs `-PtestBuildType=release` against the **R8-minified** APK, with `testProguardFiles("proguard-test.pro")`.

### 11. Static analysis configuration

```kotlin
// in the app/library convention plugins
lint {
    warningsAsErrors = true
    abortOnError = true
    checkDependencies = true          // single report from :app covering all modules
    sarifReport = true
    baseline = file("lint-baseline.xml")
    disable += setOf(
        "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", // Renovate's job; also breaks offline/F-Droid builds
        "MissingTranslation",                                                     // Weblate languages are partial by design
    )
    fatal += setOf("StringFormatInvalid", "MissingQuantity", "UnusedResources")
}
```

* **Spotless:** `kotlin { target("**/*.kt"); ktlint("1.8.0").customRuleSets(listOf("io.nlopez.compose.rules:ktlint:0.6.7")) }`, `kotlinGradle { ktlint() }`. Settings come from `.editorconfig`: `ktlint_code_style = ktlint_official`, `ktlint_function_naming_ignore_when_annotated_with = Composable`, `max_line_length = 120`.
* **detekt 2.0.0-alpha.6:** `buildUponDefaultConfig = true`, a baseline, and a CI step with `continue-on-error: true`. Promote it to blocking when 2.0.0 is final.
* **Licensee 1.14.1:**

```kotlin
licensee {
    allow("Apache-2.0"); allow("MIT"); allow("BSD-2-Clause"); allow("BSD-3-Clause"); allow("ISC")
    allow("MPL-2.0")                       // Rhino (NewPipeExtractor JS engine)
    allow("GPL-3.0-or-later")              // NewPipeExtractor – only reachable in the foss variant; see licence note
    allow("Unlicense"); allow("CC0-1.0")
    allowUrl("https://developer.android.com/studio/terms.html") { because("Android SDK artifacts; review on each new hit") }
}
```

  Run it as `licenseeFossRelease licenseePlayRelease`. Use AboutLibraries 15.2.0 for the in-app licences screen. Google's `oss-licenses` plugin pulls Play Services, which is not acceptable in `foss`.
* **Proprietary-dependency ban** (F-Droid safety net). A CI shell step:

```bash
./gradlew -q :app:dependencies --configuration fossReleaseRuntimeClasspath \
  | grep -E 'com\.google\.(android\.gms|firebase|android\.play)|com\.crashlytics|io\.sentry' \
  && { echo "::error::Proprietary dependency in foss flavour"; exit 1; } || true
```

* **AGP 9 R8 behaviour to bake into lint and review:**
  * `android.r8.strictFullModeForKeepRules=true` means `-keep class A` no longer keeps the constructor.
  * Library modules may not ship global options such as `-dontobfuscate` in consumer rules.
  * Keep rules live in `src/main/keepRules/*.keep` with the 9.3+ DSL.

### 12. Release signing, versioning, artifacts

**Key ceremony (once).**

* Command: `keytool -genkeypair -alias neutrodyne -keyalg RSA -keysize 4096 -validity 12000 -keystore neutrodyne-release.jks`.
  * 12,000 days is about 33 years. The validity must extend beyond 2033-10-22 for Play; that is long-standing Play guidance, not re-verified today.
* Store it in a password manager, with an offline encrypted backup held by two people.
* Publish the **SHA-256 certificate fingerprint** in the README, on the website and in F-Droid's `AllowedAPKSigningKeys`. That lets Obtainium/AppVerifier users and Google's developer verification check it.

**Gradle signing (configuration-cache-safe; unsigned when secrets are absent, which is what F-Droid wants).**

```kotlin
android {
    signingConfigs {
        create("release") {
            providers.environmentVariable("NEUTRODYNE_KEYSTORE").orNull?.let { path ->
                storeFile = file(path)
                storePassword = providers.environmentVariable("NEUTRODYNE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("NEUTRODYNE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("NEUTRODYNE_KEY_PASSWORD").get()
                enableV1Signing = false   // minSdk 26 ≥ 24: v2/v3 suffice
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            optimization { enable = true }   // AGP 9.3+ DSL: R8 full mode + optimized resource shrinking
            if (providers.environmentVariable("NEUTRODYNE_KEYSTORE").isPresent)
                signingConfig = signingConfigs.getByName("release")
        }
    }
    dependenciesInfo { includeInApk = false; includeInBundle = false }   // F-Droid rejects Google's encrypted DEPENDENCY_INFO_BLOCK
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") { dimension = "distribution" }   // GitHub, Obtainium, IzzyOnDroid, F-Droid
        create("play") { dimension = "distribution" }   // Google Play
    }
    androidResources { generateLocaleConfig = true }    // + res/resources.properties: unqualifiedResLocale=en-US
}
```

* `-dontobfuscate` goes in the **app's** keep rules, which is allowed. Library consumer rules may not contain it in AGP 9. Shrinking and optimisation stay on. Stack traces in user emails stay readable. `mapping.txt` is still attached to each GitHub release, because R8 inlining also changes line numbers.
* **Version source:** `gradle.properties` holds `neutrodyne.versionName=1.2.3` and `neutrodyne.versionCode=1020395`. The `scripts/release.sh X.Y.Z`:
  1. bumps both;
  2. requires `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters max);
  3. commits, then creates the `vX.Y.Z` tag.

  Don't use git commit counts or timestamps; F-Droid builds from a tarball-like checkout, and timestamps break reproducibility.
* **Same `applicationId` for both flavours** (recommended; open question). This allows a user to move from the Play build to the `foss` build by installing over it, as long as both are signed with the same key. Use `applicationIdSuffix = ".debug"` for debug builds.
* **GitHub release assets:**
  * `neutrodyne-1.2.3-foss.apk`, renamed in CI rather than via internal AGP output APIs.
  * `neutrodyne-1.2.3-foss-mapping.txt`.
  * `SHA256SUMS`.
  * Release notes taken from the fastlane changelog.
  * Pre-releases (`-beta.N`, `-rc.N`) are marked as GitHub pre-releases. Obtainium users can opt in.
  * README badge: `https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/<owner>/Neutrodyne`.

### 13. F-Droid specifics

**Repo-side requirements:**

* fastlane metadata at `fastlane/metadata/android/<locale>/`:
  * `title.txt` (50 characters max);
  * `short_description.txt` (80 max, mandatory);
  * `full_description.txt` (4000 max, mandatory; limited HTML);
  * `changelogs/<versionCode>.txt` (500 max);
  * `images/icon.png`, `images/featureGraphic.png`, `images/phoneScreenshots/1.png…`.
  * Flavour-specific overrides can go under `app/src/foss/fastlane/...`.
* No Play Services, Firebase or Crashlytics in `foss`. Media3 Cast (Play Services) is `play`-only (playback.md).
* `dependenciesInfo` disabled (above).
* A reproducibility hygiene list, from F-Droid's Reproducible Builds doc:
  * No timestamps. Don't put build time into `BuildConfig`.
  * Same JDK major as F-Droid's builder. They offer **17, 21 and 25 from Debian**; we use **21** and set it in the recipe.
  * Pinned `compileSdk`/build-tools.
  * Commit pre-optimised PNGs (`crunchPngs = false` for release) because PNG crunching is non-deterministic.
  * The `baseline.prof` workaround if needed:

```kotlin
// ONLY if the repro job shows baseline.prof differences; applies to foss release builds
tasks.matching { it.name.contains("ArtProfile") && it.name.contains("Foss", ignoreCase = true) }
     .configureEach { enabled = false }
```

    The `foss` APK then ships without a compiled ART profile. That is a modest startup cost, accepted for reproducibility. `play` keeps it.
  * F-Droid also documents some **non-deterministic R8 optimisations around kotlinx.coroutines**, with the keep-rule workaround `-keep class kotlinx.coroutines.CoroutineExceptionHandler` and `-keep class kotlinx.coroutines.internal.MainDispatcherFactory`. Add these pre-emptively to `foss` rules.
  * AGP 8.3+ embeds VCS info (`META-INF/version-control-info.textproto`) in release builds. It is deterministic for a given commit, but **UNVERIFIED** whether F-Droid's checkout yields identical content. If the repro job diffs on it, set `vcsInfo { include = false }` for release.

**`fdroiddata` metadata sketch (`metadata/<applicationId>.yml`):**

```yaml
Categories: [Multimedia, Podcast]          # "Podcast" category exists (AntennaPod uses it)
License: GPL-3.0-or-later                  # binary licence of the foss build (NewPipeExtractor); source is Unlicense — see open questions
SourceCode: https://github.com/<owner>/Neutrodyne
IssueTracker: https://github.com/<owner>/Neutrodyne/issues
Translation: https://hosted.weblate.org/engage/neutrodyne
Changelog: https://github.com/<owner>/Neutrodyne/releases
AntiFeatures:
  NonFreeNet:
    en-US: Optional YouTube channel subscriptions use YouTube.   # final call is F-Droid's
AutoName: Neutrodyne
RepoType: git
Repo: https://github.com/<owner>/Neutrodyne.git
Binaries: https://github.com/<owner>/Neutrodyne/releases/download/v%v/neutrodyne-%v-foss.apk
Builds:
  - versionName: 1.0.0
    versionCode: 1000095
    commit: v1.0.0
    subdir: app
    sudo:
      - apt-get update
      - apt-get install -y openjdk-21-jdk-headless
      - update-alternatives --auto java
    gradle: [foss]
AllowedAPKSigningKeys: <sha256-of-our-cert-lowercase-no-colons>
AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$          # stable tags only; betas are not picked up
UpdateCheckData: gradle.properties|neutrodyne.versionCode=(\d+)|.|neutrodyne.versionName=(.*)
CurrentVersion: 1.0.0
CurrentVersionCode: 1000095
```

* **Anti-features.**
  * NewPipe is tagged `NonFreeNet` with "Depends on Youtube for videos". AntennaPod has **no** anti-feature even though it offers iTunes/Podcast-Index search.
  * Neutrodyne does not *depend entirely* on YouTube, so `NonFreeNet` may or may not be applied. Declare it pre-emptively and let maintainers decide; undisclosed anti-features are a rejection reason.
* **F-Droid build capacity.** F-Droid's 2025 aapt2/old-CPU problem (AGP 8.12 needed newer CPU instructions) was resolved by new build hardware in November 2025. AGP 9.x is fine.

### 14. Google Play specifics (`play` flavour)

* **AAB with Play App Signing.** Since 2026 the default for new apps is "quantum-ready, hybrid signing with Google-generated keys". To keep **one key across channels**, choose "Provide a copy of your app signing key" (PEPK upload) **before the first release rolls out**. After that it cannot be changed back cheaply. The upload key should be a *separate* key.
* **Target API.**
  * New apps and updates must target **API 36+ from 2026-08-31** (extension to 2026-11-01 available).
  * We target 37 (stack.md), so this is met.
* **Foreground service declarations** (Play Console → App content), each with description, user impact and a **demo video**:
  * `mediaPlayback` (playback service);
  * `dataSync`, if downloads use an FGS. Prefer user-initiated data-transfer jobs, which Play's guidance points to; see downloads.md.
* **Data safety.**
  * No data collected except **Crash logs** (optional, user-initiated email). Being conservative, declare it.
  * The YouTube Data API is not used in `play` unless the product owner opts in (youtube.md).
* **16 KB page size.** Required since 2025-11-01 for apps with native code targeting Android 15+. `BundledSQLiteDriver` adds `.so` files; Google's prebuilt artifacts should be aligned, but verify with Play Console's App Bundle Explorer and the API 37 `ps16k` emulator nightly.
* **Policy guardrails for the `play` build:**
  * no stream extraction or download of YouTube media;
  * no background playback of YouTube;
  * **no self-update and no in-app prompt to install the foss build** (Play's device-and-network-abuse rules forbid updating "using any method other than Google Play's update mechanism", from long-standing policy text; the 2026 page wasn't fully re-quoted);
  * the listing text must not encourage downloading copyrighted content.
* **Publishing automation (optional).** Gradle Play Publisher **4.1.1**, where 4.x is required for AGP 9: `./gradlew publishPlayReleaseBundle --track internal` from the release workflow, using a service-account JSON secret. Promote to production manually in the Console.

### 15. Developer verification and other sideloading changes

* **Timeline** (Google's page):
  * APIs, limited-distribution accounts and the advanced flow arrived in August 2026.
  * Enforcement began **2026-09-30** for installs on certified Android 7+ devices in **BR, ID, SG, TH**.
  * Google says it will expand globally in **2027**.
* **Accounts and fees:**
  * The Full Distribution Android Developer Console account costs **$25** and requires government ID.
  * A free "limited distribution" account covers up to 20 devices, which is useless for public release.
  * Play developers are auto-registered for Play-distributed apps. For "both on and off Play" distribution, use the Play Console's new off-Play registration.
* **Keys.**
  * The console supports **multiple signing keys per package**.
  * Google's open-source guide says that if a store re-signs (F-Droid's key), download that APK and register its SHA-256 fingerprint too.
  * With our "one key, reproducible on F-Droid" plan, only our key needs registering.
* **ADB installs** stay exempt. That is useful for testers, but it is not a distribution strategy.
* **What we need from the product owner:** who (person or organisation) will register, and when. The answer should come before a public 1.0, ideally before 2027.

### 16. CI workflow sketches

`.github/workflows/ci.yml`:

```yaml
name: CI
on:
  pull_request:
  push:
    branches: [main]
  workflow_dispatch:
  schedule:
    - cron: "17 2 * * *"          # nightly: instrumented (incl. release build), repro, API 37
permissions:
  contents: read
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

jobs:
  static:
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    permissions: { contents: read, security-events: write }
    steps:
      - uses: actions/checkout@<sha>            # v7.0.1
      - uses: actions/setup-java@<sha>          # v6.0.1
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@<sha> # v6.4.0 — also validates the wrapper checksum
        with:
          cache-provider: basic                 # MIT; omit to use the proprietary "enhanced" cache (free for public repos)
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}
      - run: ./gradlew spotlessCheck lintFossDebug lintPlayDebug licenseeFossRelease licenseePlayRelease --continue
      - name: Ban proprietary deps in foss
        run: |
          ./gradlew -q :app:dependencies --configuration fossReleaseRuntimeClasspath \
            | grep -E 'com\.google\.(android\.gms|firebase|android\.play)|crashlytics' && exit 1 || true
      - uses: github/codeql-action/upload-sarif@<sha>   # v4.38.2
        if: always()
        with: { sarif_file: app/build/reports/ }
      - run: ./gradlew detekt
        continue-on-error: true                  # detekt 2.0 is alpha

  unit:
    runs-on: ubuntu-24.04
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@<sha>
      - uses: actions/setup-java@<sha>
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@<sha>
        with: { cache-provider: basic, cache-read-only: "${{ github.ref != 'refs/heads/main' }}" }
      - uses: actions/cache@<sha>                # Robolectric android-all jars (~100 MB each)
        with: { path: ~/.m2/repository/org/robolectric, key: robolectric-4.17-sdk36 }
      - run: ./gradlew test verifyRoborazziFossDebug koverXmlReport --continue
      - name: Room schema drift
        run: git diff --exit-code -- '**/schemas/**' || { echo "::error::Room schema changed without version bump"; exit 1; }
      - uses: actions/upload-artifact@<sha>     # v7.0.1
        if: failure()
        with:
          name: unit-reports
          path: |
            **/build/reports/tests/
            **/build/outputs/roborazzi/

  assemble:
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@<sha>
      - uses: actions/setup-java@<sha>
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@<sha>
        with: { cache-provider: basic, cache-read-only: "${{ github.ref != 'refs/heads/main' }}" }
      - run: ./gradlew assembleFossRelease bundlePlayRelease assembleFossDebug   # unsigned: no secrets on PRs
      - name: APK size budget
        run: |
          size=$(stat -c%s app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk)
          echo "foss release: $size bytes"; test "$size" -lt $((25*1024*1024))
      - uses: actions/upload-artifact@<sha>
        with: { name: foss-debug-apk, path: app/build/outputs/apk/foss/debug/*.apk, retention-days: 14 }

  instrumented:
    if: github.event_name != 'pull_request' || contains(github.event.pull_request.labels.*.name, 'run-instrumented')
    runs-on: ubuntu-24.04
    timeout-minutes: 75
    strategy:
      fail-fast: false
      matrix:
        buildType: ${{ github.event_name == 'schedule' && fromJSON('["debug","release"]') || fromJSON('["debug"]') }}
    steps:
      - name: Free disk space          # 14 GB SSD fills up with images + caches
        run: sudo rm -rf /usr/share/dotnet /usr/local/lib/android/sdk/ndk /opt/ghc /usr/local/.ghcup
      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm
      - uses: actions/checkout@<sha>
      - uses: actions/setup-java@<sha>
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@<sha>
        with: { cache-provider: basic, cache-read-only: "${{ github.ref != 'refs/heads/main' }}" }
      - run: >
          ./gradlew ciGroupFossDebugAndroidTest
          -PtestBuildType=${{ matrix.buildType }}
          -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
          -Pandroid.experimental.androidTest.numManagedDeviceShards=2
      - uses: actions/upload-artifact@<sha>
        if: failure()
        with: { name: android-test-reports-${{ matrix.buildType }}, path: "**/build/outputs/androidTest-results/" }

  repro:
    if: github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'
    runs-on: ubuntu-24.04
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@<sha>
      - name: Build twice in an F-Droid-like container (Debian, OpenJDK 21) at different paths/CPU counts
        run: |
          for dir in /build/a /home/vagrant/build/app; do
            docker run --rm --cpus=$([ "$dir" = /build/a ] && echo 2 || echo 4) -v "$PWD:/src:ro" debian:trixie bash -c "
              apt-get update && apt-get install -y openjdk-21-jdk-headless git unzip curl &&
              mkdir -p $dir && cp -a /src/. $dir && cd $dir && ./scripts/ci/install-android-sdk.sh &&
              ./gradlew --no-daemon --no-build-cache assembleFossRelease" ;
          done
          # then: diffoscope / sha256sum of the two unsigned APKs; on tag builds, apksigcopier compare against the signed release
```

`.github/workflows/release.yml` (on `push: tags: ['v*']`):

```yaml
permissions: { contents: write }
jobs:
  release:
    runs-on: ubuntu-24.04
    environment: release            # required reviewer + secrets live only here
    steps:
      - uses: actions/checkout@<sha>
      - uses: actions/setup-java@<sha>
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@<sha>
        with: { cache-disabled: true }            # no cache-poisoning surface for signed builds
      - name: Decode keystore
        run: echo "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/release.jks"
        env: { KEYSTORE_B64: "${{ secrets.NEUTRODYNE_KEYSTORE_B64 }}" }
      - run: ./gradlew assembleFossRelease bundlePlayRelease
        env:
          NEUTRODYNE_KEYSTORE: ${{ runner.temp }}/release.jks
          NEUTRODYNE_KEYSTORE_PASSWORD: ${{ secrets.NEUTRODYNE_KEYSTORE_PASSWORD }}
          NEUTRODYNE_KEY_ALIAS: ${{ secrets.NEUTRODYNE_KEY_ALIAS }}
          NEUTRODYNE_KEY_PASSWORD: ${{ secrets.NEUTRODYNE_KEY_PASSWORD }}
      - name: Verify signer is the published cert
        run: |
          $ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/foss/release/app-foss-release.apk \
            | grep -qi "SHA-256 digest: ${{ vars.NEUTRODYNE_CERT_SHA256 }}"
      - name: Stage assets
        run: |
          v=${GITHUB_REF_NAME#v}; mkdir out
          cp app/build/outputs/apk/foss/release/app-foss-release.apk out/neutrodyne-$v-foss.apk
          cp app/build/outputs/mapping/fossRelease/mapping.txt out/neutrodyne-$v-foss-mapping.txt
          (cd out && sha256sum * > SHA256SUMS)
          vc=$(grep '^neutrodyne.versionCode=' gradle.properties | cut -d= -f2)
          cp fastlane/metadata/android/en-US/changelogs/$vc.txt out/notes.md
      - uses: softprops/action-gh-release@<sha>   # v3.0.3
        with:
          files: |
            out/*.apk
            out/*-mapping.txt
            out/SHA256SUMS
          body_path: out/notes.md
          prerelease: ${{ contains(github.ref_name, '-') }}
      # optional: ./gradlew publishPlayReleaseBundle --track internal  (GPP 4.1.1, service-account secret)
```

Hardening notes:

* Pin every action by commit SHA; Renovate's `helpers:pinGitHubActionDigests` keeps them current.
* Never use `pull_request_target` with a checkout of PR code.
* Fork PRs get no secrets, which is fine because PR builds are unsigned.
* `nightly.yml` holds the live-feed canary and the API 37 / ps16k emulator job.
* `record-screenshots.yml` is a `workflow_dispatch` job that runs `recordRoborazziFossDebug` and opens a PR with the new PNGs.

What runs on hosted runners:

* **Yes:**
  * everything JVM (unit, Robolectric, Roborazzi, Lint, Spotless, detekt, Licensee);
  * assemble and bundle;
  * GMD and android-emulator-runner with KVM;
  * Docker-based reproducibility builds;
  * baseline-profile generation on an `aosp` API 33+ GMD (slow, about 20 minutes, so on demand only).
* **No:**
  * meaningful Macrobenchmark *timings*, because emulators on shared VMs are noisy (use a physical mid-range device locally before release);
  * Android Auto head-unit testing (DHU needs a display; test the browse tree via `MediaBrowser` instead);
  * Chromecast.
* macOS arm64 runners do not support nested virtualisation, so keep emulators on Linux.

### 17. Renovate configuration sketch (`renovate.json`)

```json
{
  "$schema": "https://docs.renovatebot.com/renovate-schema.json",
  "extends": ["config:recommended", "helpers:pinGitHubActionDigests", ":dependencyDashboard"],
  "schedule": ["before 6am on monday"],
  "prConcurrentLimit": 5,
  "packageRules": [
    { "groupName": "Kotlin + KSP + Compose compiler",
      "matchPackageNames": ["/^org\\.jetbrains\\.kotlin/", "/^com\\.google\\.devtools\\.ksp/"] },
    { "groupName": "AGP + Lint", "matchPackageNames": ["/^com\\.android\\.tools/", "/^com\\.android\\.(application|library|test)$/"] },
    { "groupName": "Media3", "matchPackageNames": ["/^androidx\\.media3/"] },
    { "groupName": "AndroidX test", "matchPackageNames": ["/^androidx\\.test/", "/^androidx\\.compose\\.ui:ui-test/"] },
    { "description": "YouTube breakages need same-day updates",
      "matchPackageNames": ["com.github.teamnewpipe:NewPipeExtractor"],
      "schedule": ["at any time"], "prPriority": 10, "labels": ["youtube-hotfix"] },
    { "matchUpdateTypes": ["patch"], "matchDepTypes": ["testImplementation", "androidTestImplementation"],
      "automerge": true },
    { "matchPackageNames": ["dev.detekt:detekt-gradle-plugin", "app.cash.paparazzi:paparazzi-gradle-plugin"],
      "ignoreUnstable": false, "automerge": false }
  ]
}
```

KSP2 now versions independently of Kotlin (2.3.x against Kotlin 2.4.x), so the "Kotlin + KSP" group is about testing them together, not matching numbers.

### 18. Crash reporting (ACRA) and diagnostics

```kotlin
class NeutrodyneApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        initAcra {
            buildConfigClass = BuildConfig::class.java
            reportFormat = StringFormat.KEY_VALUE_LIST
            reportContent = listOf(                         // explicit allow-list — no LOGCAT, no SHARED_PREFERENCES, no DEVICE_ID
                ReportField.APP_VERSION_NAME, ReportField.APP_VERSION_CODE, ReportField.ANDROID_VERSION,
                ReportField.PHONE_MODEL, ReportField.BRAND, ReportField.STACK_TRACE, ReportField.CUSTOM_DATA,
                ReportField.USER_COMMENT, ReportField.BUILD_CONFIG,
            )
            mailSender {
                mailTo = "crashes@<project-domain>"
                reportAsFile = true
                reportFileName = "neutrodyne-crash.txt"
                subject = getString(R.string.crash_mail_subject)
                body = getString(R.string.crash_mail_body)
            }
            dialog {
                title = getString(R.string.crash_dialog_title)
                text = getString(R.string.crash_dialog_text)        // "Nothing is sent unless you press Send in your mail app"
                commentPrompt = getString(R.string.crash_dialog_comment)
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        if (ACRA.isACRASenderServiceProcess()) return          // ACRA's :acra process: skip Hilt/WorkManager/Media3 init
        // normal init…
    }
}
```

* **`CUSTOM_DATA`** carries only redacted context such as the current screen and the flavour. Use a `Redactor` that strips URL userinfo and query strings and hashes feed hosts. Unit-test the redactor.
* **`BUILD_CONFIG`** must contain no secrets. It will not, because there are no API keys in `foss`.
* **Mail intent visibility.** ACRA's docs don't mention Android 11+ package visibility for the mail intent. Verify in the spike that it resolves an email app on API 30+. If not, add `<queries><intent><action android:name="android.intent.action.SENDTO"/><data android:scheme="mailto"/></intent></queries>`.
* **Settings → "Diagnostics" screen:**
  * shows the last N redacted log lines from our own ring buffer, kept in memory and on disk with a cap;
  * offers "Copy" and "Open GitHub issue". The latter is a pre-filled `issues/new?template=bug.yml&body=…` URL, truncated to about 6 KB because URL length is limited.
* **Debug builds:** `StrictMode` (thread and VM policies, penalty log plus flash) and **LeakCanary 2.14** (`debugImplementation` only; 3.0 is still alpha).

### 19. Privacy implementation checklist

* **No analytics or tracking SDKs** in any flavour. The licensee and proprietary-dependency checks enforce this for `foss`; for `play`, it is enforced by review plus the same grep with a smaller allow-list (Cast, which has no analytics).
* **Network egress inventory** goes in `PRIVACY.md`:
  1. feed hosts, with conditional GETs;
  2. cover and image hosts (Coil, sharing the OkHttp cache);
  3. media hosts (streaming and downloads);
  4. the optional directory search the product owner chooses (see open questions);
  5. `foss` only, when a YouTube subscription exists: `youtube.com` Atom feeds and stream resolution;
  6. never: our own servers (we have none).
* **User-Agent:** `Neutrodyne/<version> (Android)`. It contains no device identifiers and no per-install IDs. Hosts that count downloads by IP+UA are unaffected, and we don't add tracking.
* **Cleartext.** Many feeds are still `http://`. Decide whether to permit cleartext globally, as most podcast apps do, or to try HTTPS first and fall back with a per-feed flag; that one is a product-owner question. Either way, debug-only localhost cleartext for tests lives in the debug source set.
* **Backups.** Ship `dataExtractionRules` and `fullBackupContent` (opml-groups.md) that include the database and settings and exclude downloads, the Coil cache and the Media3 cache. Lint checks the XML.
* **Android 17 `ACCESS_LOCAL_NETWORK`.** It is only needed if users subscribe to LAN-hosted feeds (Audiobookshelf, self-hosted). Request it at runtime on first LAN fetch, never up front. Write an instrumented test on API 37.

### 20. Localisation workflow

* **Hosted Weblate Libre plan.** Free for public libre projects, with the same limits as the 160k-strings plan.
* **Components:**
  1. `app-strings`: Android string resource format, monolingual base `core/ui/src/main/res/values/strings.xml`, file mask `core/ui/src/main/res/values-*/strings.xml`. Centralise user-facing strings in `:core:ui` (plus one file per feature module if needed) to keep the number of components small.
  2. `store-metadata`: format "App store metadata files", file mask `fastlane/metadata/android/*`, base `fastlane/metadata/android/en-US`. Mark `changelogs/*` read-only, or hide them via a key filter, if we don't want translated changelogs.
* **Flow.** Weblate commits to its own branch and opens a PR, which squashes commits through the Weblate add-on. CI runs Lint with `StringFormatInvalid` and `MissingQuantity` as fatal. Developers never hand-edit `values-xx`. Weblate's Android language-code style maps `pt_BR` → `values-pt-rBR` and `zh_Hant` → `values-b+zh+Hant`.
* **Per-app language picker.** `androidResources { generateLocaleConfig = true }` plus `res/resources.properties` with `unqualifiedResLocale=en-US`. This needs AGP 8.1+ and compileSdk 33+. The build fails if a manual `locale_config.xml` also exists.
* **QA:**
  * Debug builds set `isPseudoLocalesEnabled = true`.
  * Roborazzi captures `en-XA` (long accented text) and `ar-XB` (RTL) for the main screens.
  * Use `pluralStringResource` for all counts, such as "3 new episodes".
  * Lint `UnusedResources` keeps Weblate free of dead strings.

### 21. Performance engineering

* **Baseline + Startup Profiles** (`androidx.baselineprofile` 1.5.0, `benchmark-macro-junit4` 1.5.0, `profileinstaller` 1.4.1):
  * **Module:** `:baselineprofile` (`com.android.test`) with `targetProjectPath = ":app"`, a GMD `pixel6Api34` with `systemImageSource = "aosp"` (root, or API 33+), and `useConnectedDevices = false`.
  * **Journeys:**
    1. cold start to the library cover grid (`includeInStartupProfile = true`, which drives DEX layout optimisation);
    2. open the "tech" group feed and fling the list;
    3. open an episode and start playback (fake local media served from assets);
    4. open the full player.
  * **Generation:** run `./gradlew :app:generateFossReleaseBaselineProfile` via `workflow_dispatch` before each minor release. Commit the text output under `app/src/<variant>/generated/baselineProfiles/`. Keep `automaticGenerationDuringBuild = false` so normal and F-Droid builds never need an emulator.
* **Macrobenchmark:**
  * `StartupTimingMetric` (cold and warm), and `FrameTimingMetric` on cover-grid scroll and group-feed scroll, with `CompilationMode.Partial()` vs `None()` to prove the profile's value.
  * Run on a physical device; record results in the release checklist.
  * Budgets to start with: cold start p50 under 600 ms on a mid-range device, and grid scroll jank under 1%. These are product-owner-adjustable.
* **R8:**
  * Full mode is the default (since AGP 8.0), and optimized resource shrinking is the default in AGP 9.
  * Use the AGP 9.3+ `optimization { enable = true }` DSL and keep rules in `src/main/keepRules/*.keep`.
  * NewPipeExtractor/Rhino needs `-keep class org.mozilla.javascript.** { *; }` (its README). That goes in the `foss` keep rules only.
  * kotlinx.serialization keep rules come with the plugin.
  * Use the **R8 Configuration Analyzer** (AGP 9.3.0-alpha05+) to find over-broad keeps.
* **Compose:**
  * Strong skipping is on by default.
  * Add a `compose_stability.conf` for `:core:model` types, which are pure JVM and therefore not inferred stable.
  * Optionally enable Compose compiler reports in a CI job to catch regressions in the cover grid's restartability.
* **Size:**
  * The CI budget check is set initially at 25 MB for the universal `foss` APK, which includes four ABIs of `sqlite-bundled`.
  * Universal APK only: no ABI splits, which keeps F-Droid (no `VercodeOperation`) and Obtainium (no regex needed) simple.

---

## Verified versions & facts

All checked **2026-10-04**. "MC" means Maven Central `maven-metadata.xml` under `https://repo1.maven.org/maven2/<group path>/<artifact>/maven-metadata.xml`. "GM" means Google Maven under `https://dl.google.com/android/maven2/<group path>/<artifact>/maven-metadata.xml`. Publish dates come from the Maven Central directory listings.

| Item | Version / fact | Source |
|---|---|---|
| AGP | **9.4.1** latest stable; 9.5.0-alpha08 in preview. AGP 9.4.0 (Sep 2026) needs **Gradle 9.6.0**, JDK 17, Build Tools 36.0.0; max API **37** | GM `com/android/tools/build/gradle`; https://developer.android.com/build/releases/gradle-plugin |
| AGP 9.0 defaults | built-in Kotlin; `onlyEnableUnitTestForTheTestedBuildType=true`; `optimizedResourceShrinking` and `strictFullModeForKeepRules` on; `defaultTargetSdkToCompileSdkIfUnset`; `deviceProvider`/`testServer` replaced by GMD | https://developer.android.com/build/releases/agp-9-0-0-release-notes |
| R8 | Full mode default since AGP 8.0; 9.3+ `optimization {}` DSL and `keepRules` source set; R8 Configuration Analyzer | https://developer.android.com/build/releases/past-releases/agp-8-0-0-release-notes ; https://developer.android.com/build/shrink-code |
| Gradle | **9.8.0** current (stack.md pins 9.7.1) | https://services.gradle.org/versions/current |
| Kotlin | **2.4.20** stable (2026-09-07); 2.4.21-RC, 2.5.0-Beta1 | MC `org/jetbrains/kotlin/kotlin-gradle-plugin` |
| KSP | 2.3.12 | MC `com/google/devtools/ksp/symbol-processing-gradle-plugin` |
| Compose BOM | 2026.09.00 → ui / ui-test-junit4 **1.12.1**, material3 1.4.0; `ui-test-junit4-accessibility` 1.12.1 | GM `androidx/compose/compose-bom/2026.09.00/*.pom` |
| Compose v2 test APIs | v1 deprecated; v2 uses `StandardTestDispatcher` | https://developer.android.com/develop/ui/compose/testing/migrate-v2 |
| Room | 2.8.5 (2026-09-09); **Room 3.0.3** (`androidx.room3`, 3.0.0 stable 2026-07-01; KSP-only, coroutines required, `SQLiteDriver` required); `room3-testing` 3.0.3 | GM; https://developer.android.com/jetpack/androidx/releases/room3 ; https://developer.android.com/training/data-storage/room/migrating-db-versions |
| Media3 | **1.11.1**, including `media3-test-utils` and `-robolectric`. test-utils POM pulls `mockwebserver:4.12.0`, `mockito-core:3.12.4`, `robolectric:4.16` | GM; GM POM `androidx/media3/media3-test-utils/1.11.1` |
| `TestPlayerRunHelper` | `advance()`/`play()` with `untilState`, `untilPositionAtLeast`, …; `run()` deprecated | https://developer.android.com/reference/kotlin/androidx/media3/test/utils/robolectric/TestPlayerRunHelper |
| Robolectric | **4.17** (2026-09-10), supports SDK 37; 4.16 (2025-08-25) required JDK 21 for SDK 36 tests | MC; https://github.com/robolectric/robolectric/releases |
| Roborazzi | **1.76.0** (2026-09-29) | MC `io/github/takahirom/roborazzi/roborazzi`; https://github.com/takahirom/roborazzi |
| Paparazzi | 2.0.0-alpha05.1 (2026-09-28); last stable 1.3.5 (2024-11-06); 2.0 needs Java 21 | MC; https://github.com/cashapp/paparazzi/blob/master/CHANGELOG.md |
| Compose Preview Screenshot Testing | 0.0.1-alpha16, experimental; AGP 9.5.0-alpha03+ recommends AGP test suites | https://developer.android.com/studio/preview/compose-screenshot-testing |
| JUnit | 4.13.2; Jupiter 6.1.3; android-junit5 2.0.1 (2026-01) — JUnit 6 instrumentation needs API 35+ devices | MC; https://github.com/mannodermaus/android-junit5 |
| TestParameterInjector | 1.24 (2026-09-28) | MC `com/google/testparameterinjector/test-parameter-injector` |
| kotlinx-coroutines-test | 1.11.0 (2026-05-07) | MC |
| Turbine | 1.2.1 (2025-06-11) | MC `app/cash/turbine/turbine` |
| MockK | 1.14.11 (2026-05-29) | MC `io/mockk/mockk` |
| OkHttp / MockWebServer | **5.5.0** (2026-08-16); `mockwebserver3`, `-junit4`, `-junit5`, `okhttp-tls` | MC `com/squareup/okhttp3/*` |
| Truth / AssertK | 1.4.5 / 0.28.1 | MC |
| Coil + coil-test | 3.6.3; `FakeImageLoaderEngine` | MC; https://coil-kt.github.io/coil/testing/ |
| androidx.test | runner 1.7.0, ext-junit 1.3.0, espresso 3.7.0, orchestrator 1.6.1, uiautomator 2.4.0; work-testing 2.12.0 | GM |
| Benchmark / Baseline Profile plugin | 1.5.0; profileinstaller 1.4.1 | GM; https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile |
| GMD | DSL, `aosp-atd`, sharding property, `swiftshader_indirect` flag for CI | https://developer.android.com/studio/test/managed-devices |
| ATD and API 37 images | `aosp_atd`/`google_atd` x86_64 for API 31 to 36; API 37 only as `google_apis_ps16k` / `google_apis_playstore_ps16k` (37.2) | https://dl.google.com/android/repository/sys-img/aosp_atd/sys-img2-3.xml ; …/google_apis/sys-img2-3.xml |
| detekt | stable 1.23.8 (2025-02-21; Kotlin 2.0.21, AGP 8.8.1); **2.0.0-alpha.6** (2026-08-04; Kotlin 2.4.10, AGP 9.3.1, Gradle 9.6.1) | MC; https://detekt.dev/docs/introduction/compatibility/ |
| ktlint / Spotless / compose-rules | 1.8.0 (2025-11-14) / 8.10.3 / 0.6.7 | MC |
| Slack compose-lint-checks | 1.6.0 | MC `com/slack/lint/compose/compose-lint-checks` |
| Licensee / AboutLibraries / Kover | 1.14.1 / 15.2.0 / 0.9.11 | MC; https://github.com/cashapp/licensee |
| LeakCanary | 2.14 stable; 3.0-alpha-9 (2026-06-24) | MC |
| ACRA | **5.14.2** (2026-10-01); `initAcra { mailSender {} dialog {} }` DSL | MC `ch/acra/acra-core`; https://www.acra.ch/docs/Setup ; https://www.acra.ch/docs/Senders ; https://www.acra.ch/docs/Interactions |
| Gradle Play Publisher | 4.1.1; 4.x required for AGP 9 | https://github.com/Triple-T/gradle-play-publisher/releases ; plugins.gradle.org metadata |
| GitHub Actions | checkout **v7.0.1**, setup-java **v6.0.1**, gradle/actions **v6.4.0**, android-emulator-runner v2.38.0, upload-artifact v7.0.1, action-gh-release v3.0.3, codeql-action v4.38.2 | `git ls-remote --tags https://github.com/<repo>.git` |
| setup-gradle v6 | Default "enhanced caching" is proprietary (`gradle-actions-caching`), free for public repos; `cache-provider: basic` is MIT; wrapper validation is automatic | https://github.com/gradle/actions/blob/main/docs/setup-gradle.md ; https://github.com/gradle/actions/releases |
| Hosted runners | Public repos: Linux 4 vCPU / 16 GB / 14 GB SSD; `ubuntu-latest` = 24.04; `ubuntu-24.04-arm` available; HW-accelerated Android emulation on Linux runners | https://docs.github.com/en/actions/reference/runners/github-hosted-runners ; https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/ |
| Dependabot | Version catalogs since 2023-03; Gradle lockfiles GA 2025-06-24 | https://github.blog/changelog/2023-03-13-dependabot-version-updates-keeps-gradle-version-catalogs-up-to-date/ ; https://github.blog/changelog/2025-06-24-dependabot-support-for-gradle-lockfiles-is-now-generally-available/ |
| Renovate | Gradle manager covers `*.versions.toml`, plugins, buildSrc; wrapper execution needs `allowedUnsafeExecutions` (self-hosted); CVE-2026-88886 fixed in 44.14.7 | https://docs.renovatebot.com/modules/manager/gradle/ ; https://www.vulncheck.com/advisories/renovate-before-44.14.7-command-injection-via-gradle-wrapper |
| F-Droid inclusion | FLOSS only; no Play Services, Firebase, Crashlytics; FLOSS toolchain; Maven Central, Google Maven and JitPack allowed; undisclosed anti-features mean rejection | https://f-droid.org/docs/Inclusion_Policy/ |
| F-Droid anti-features | Includes `NonFreeNet`, `Tracking`, `NonFreeDep`, … | https://f-droid.org/docs/Anti-Features/ |
| F-Droid reproducible builds | `Binaries:`, `AllowedAPKSigningKeys`, baseline.prof and R8 workarounds; JDK 17/21/25 from Debian | https://f-droid.org/docs/Reproducible_Builds/ |
| F-Droid fastlane | Paths and limits (title 50, short 80, full 4000, changelog 500) | https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/ |
| F-Droid precedents | NewPipe: `NonFreeNet` "Depends on Youtube for videos"; ACRA via user email noted acceptable; dev-signing keys listed. AntennaPod: categories Multimedia/News/**Podcast**, `free` flavour, no anti-features, F-Droid-signed | https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/org.schabi.newpipe.yml ; …/de.danoeh.antennapod.yml ; https://f-droid.org/en/packages/org.schabi.newpipe/ |
| F-Droid dependency info block | Must be disabled (`dependenciesInfo`) | https://forum.f-droid.org/t/build-fails-with-found-extra-signing-block/29220 ; https://gitlab.com/fdroid/admin/-/issues/367 |
| F-Droid build farm | AGP 8.12 aapt2/CPU issue resolved by new hardware (TWIF 2025-11-13) | https://gitlab.com/fdroid/admin/-/issues/593 |
| F-Droid reproducibility study | 94% of rebuilt previously-reproducible versions still bit-identical; IzzyOnDroid 35.8% reproducible | https://arxiv.org/abs/2607.01890 |
| IzzyOnDroid | APKs from GitHub/GitLab/Codeberg releases; FOSS; updated within 12 months; screens trackers | https://apt.izzysoft.de/fdroid/index/info |
| Obtainium | `obtainium://add/<url>` via the `apps.obtainium.imranr.dev/redirect.html?r=` wrapper; APK regex filters | https://github.com/ImranR98/Obtainium ; https://potatoenergy.ru/en/blog/android/obtainium-guide/ |
| NewPipeExtractor | GPL-3.0-or-later; JitPack; Rhino keep rules; `desugar_jdk_libs_nio` when minSdk < 33 | https://github.com/TeamNewPipe/NewPipeExtractor |
| Unlicense | OSI- and FSF-approved; GPL-compatible | https://en.wikipedia.org/wiki/Unlicense |
| Play target API | API 36 for new apps and updates from 2026-08-31 (extension to 2026-11-01) | https://developer.android.com/google/play/requirements/target-sdk |
| Play App Signing | New apps default to Google-generated "quantum-ready hybrid" keys; can provide own key before first release (PEPK) | https://support.google.com/googleplay/android-developer/answer/9842756 |
| Play policy | "Apps that access or use a service or API in a manner that violates its terms of service"; no executable code from outside Play | https://support.google.com/googleplay/android-developer/answer/9888379 ; IP policy https://support.google.com/googleplay/android-developer/answer/9888072 |
| Play FGS declarations | Required for API 34+ FGS types, with video; UIDT recommended for user-initiated transfers | https://support.google.com/googleplay/android-developer/answer/13392821 |
| Play Data safety | On-device-only data exempt; ephemeral processing rules | https://support.google.com/googleplay/android-developer/answer/10787469 |
| 16 KB pages | Required on Play from 2025-11-01 for native code targeting Android 15+ | https://developer.android.com/16kb-page-size |
| Developer verification | Aug 2026 APIs and advanced flow; **2026-09-30** BR/ID/SG/TH; global 2027; $25 Full Distribution; free Limited (20 devices); multiple keys per package; ADB exempt; open-source guide for re-signed apps | https://developer.android.com/developer-verification ; …/guides ; …/guides/faq ; …/guides/open-source-app-registration |
| Advanced flow | Developer Options toggle, 24 h wait, indefinite or 7-day | https://www.xda-developers.com/googles-controversial-advanced-flow-sideloading-rolling-out-ahead-of-stricter-developer-verification/ (2026-08-19) |
| Android 16 / 17 | 16: no edge-to-edge opt-out, predictive back on, sw≥600dp ignores orientation locks. 17 (stable 2026-06-16, API 37): background-audio hardening, resizability opt-out removed, `ACCESS_LOCAL_NETWORK` | https://developer.android.com/about/versions/16/behavior-changes-16 ; https://developer.android.com/about/versions/17/behavior-changes-17 ; https://en.wikipedia.org/wiki/Android_17 |
| Weblate | Libre plan free for public libre projects (160k strings); fastlane "App store metadata files" format | https://weblate.org/en/hosting/ ; https://docs.weblate.org/en/latest/formats/appstore.html |
| Per-app languages | `generateLocaleConfig = true` + `resources.properties` (AGP 8.1+) | https://developer.android.com/guide/topics/resources/app-languages |
| YouTube from datacenter IPs | Frequent "Sign in to confirm you're not a bot" on cloud IPs, so live YouTube CI tests are flaky | https://www.technetexperts.com/?p=11741 (secondary) |

Not verified:

* NewPipeExtractor's current JitPack version (youtube.md cites v0.26.5).
* Whether the Mend-hosted Renovate app regenerates `gradle-wrapper.jar`.
* GMD support for `ps16k` images.
* Whether AGP's `vcsInfo` affects F-Droid reproducibility.
* The exact `TestParameterValuesProvider` signature in TPI 1.24.
* Whether ACRA's mail sender needs a `<queries>` entry.
* The current Play "self-update" policy wording (not re-quoted).

---

## Pitfalls & edge cases

1. **`BundledSQLiteDriver` under Robolectric.** The Android `sqlite-bundled` artifact ships `.so` files for Android ABIs, not for the host JVM. Room DAO tests under Robolectric should inject `AndroidSQLiteDriver`, which is backed by Robolectric's native SQLite. The bundled driver is then exercised only in instrumented tests, so SQL that relies on SQLite ≥ 3.25 features (window functions, UPSERT) gets real-device coverage. Make the driver a Hilt binding. **Verify in the scaffolding spike.**
2. **AGP 9 tests only the tested build type.** R8 full-mode breakage (reflection, `-keep class A` no longer keeping `<init>`, Rhino in NewPipeExtractor) never shows in unit tests. Run the nightly **instrumented release-build** job plus a YouTube resolution test with recorded responses.
3. **Media3 test-utils drags in OkHttp 4 MockWebServer and Robolectric 4.16.** Align with `okhttp-bom` 5.5.0 and an explicit Robolectric 4.17, or `mockwebserver3` and the legacy `mockwebserver` can disagree at runtime.
4. **Robolectric SDK and JDK.** SDK 36+ needs JDK 21. Each SDK level downloads a large android-all jar on first run, so cache it in CI and pin `sdk=` in `robolectric.properties`.
5. **Screenshot drift across machines.** Never record on macOS or Windows. Fix fonts, disable dynamic colour, freeze time and animations, and use fake images. Expect a mass re-record whenever Robolectric, Compose or Roborazzi update, so Renovate should group those three and the PR should include the re-record.
6. **Compose v1 vs v2 test rules.** Migrating to v2 changes when coroutines run (`StandardTestDispatcher`). Tests that "just worked" under `UnconfinedTestDispatcher` may need `advanceUntilIdle()` or `waitForIdle()`. Start on v2.
7. **Locale and timezone bugs.** Parsing RFC-822 dates with a default-locale `SimpleDateFormat`/`DateTimeFormatter` fails for German or French users ("Okt"). `String.format("%.1f")` produces decimal commas inside URLs or JSON. Our test JVM runs in `de_DE` and `America/St_Johns` on purpose. Production code uses `Locale.ROOT`/`Locale.US` for wire formats.
8. **XXE and billion-laughs in OPML.** OPML import is a file-open attack surface (files from email or downloads). Never enable DTD processing, and test it explicitly.
9. **MockWebServer on devices.** It needs cleartext to localhost in the *debug* network-security config, or the okhttp-tls HTTPS setup. Forgetting this gives confusing `CLEARTEXT communication not permitted` failures on API 28+.
10. **YouTube in CI.** GitHub runners use Azure IPs that YouTube often bot-challenges. Never make a live YouTube request a blocking check. Use recorded responses; NewPipeExtractor itself tests with recorded mocks.
11. **F-Droid recipe differences.** F-Droid may `rm` test directories, run `prebuild` `sed` patches and build without `.git` metadata. Anything that reads git at build time breaks. If F-Droid patches anything, our signed APK will not match, so we must keep the source "F-Droid-ready" so the recipe needs no patches.
12. **F-Droid lag vs YouTube breakage.** A YouTube change can break extraction for everyone, and F-Droid's update can take days. Users of the F-Droid build (signed with our key if reproducible) can update from GitHub/Obtainium without reinstalling. That is a strong argument for the single-key strategy.
13. **Proprietary creep in `foss`.** Media3 Cast, Google's `oss-licenses`, Play In-App Review/Update and ML Kit all pull Play Services. Keep them strictly in `play*` configurations. The CI ban step catches mistakes.
14. **Play policy traps.**
    * Even the `play` build's YouTube layer A (Atom feeds plus "open in YouTube") should avoid background playback of YouTube.
    * Never mention or link to the `foss` APK from inside the Play app or listing.
    * FGS declarations must match real behaviour (demo videos).
    * Choose your own app-signing key *before* the first Play release.
15. **Developer verification and update continuity.** If a user installed an unregistered build through the advanced flow and later turns the setting off, they **cannot update** that app. Register the key before the 2027 global rollout, or many users will be stranded.
16. **Same `applicationId` across flavours.**
    * A user who installs `foss` over `play` keeps their data. Going back requires an uninstall if `versionCode`s diverge.
    * On Android 14+, Play may hold "update ownership" of an app it installed, and may prompt when another source updates it. Test the cross-grade on a device before 1.0 (**UNVERIFIED** behaviour for same-key sideload over a Play install).
    * If this proves messy, give `play` the suffix `.play`. The cost is that data must then move via OPML/backup export.
17. **Version codes.** Never reuse one, even for a failed release; F-Droid and Play remember them. Betas use `…01–94`; stable is `…95`. A hotfix after `1.2.3` is `1.2.4`, not a rebuild.
18. **Weblate merge conflicts.** Renaming string keys in a PR while Weblate has pending changes creates conflicts. Lock the Weblate component during big refactors, and merge Weblate's PR first.
19. **ACRA process.** ACRA's dialog runs in a separate `:acra` process. Unguarded `Application.onCreate` would initialise Hilt, WorkManager and the media session there. Use `isACRASenderServiceProcess()`.
20. **Crash reports leaking private feed tokens.** Patreon or Supercast-style private feed URLs carry auth in the URL. Exclude `LOGCAT`, redact `CUSTOM_DATA`, and unit-test the redactor.
21. **detekt 1.23.x and Paparazzi 1.3.x.** Their documented compatibility stops at Kotlin 2.0.21/AGP 8.8 (detekt), and Paparazzi 1.3.5 predates AGP 9. Don't let Renovate "downgrade to stable" either of them.
22. **setup-gradle v6 caching.** Its default enhanced cache is a proprietary component with separate terms. Use `cache-provider: basic` if the project wants an all-FOSS CI; it still works fine at our size. Release builds should run with `cache-disabled: true`.
23. **Hosted runner disk.** 14 GB of SSD fills up with emulator images, Gradle caches and the Robolectric jars, so free space first (as AntennaPod does).
24. **Baseline profile vs reproducibility.** The committed `baseline-prof.txt` is deterministic. The *compiled* `assets/dexopt/baseline.prof` is where F-Droid has seen non-determinism. Detect it with the nightly `repro` job (two builds, different paths and CPU counts) before submitting to F-Droid, not after.
25. **Gradle Managed Devices images.** ATD images go up to API 36 only. API 37 images are 16 KB-page `ps16k` variants. The minSdk-26 device must use a full `aosp` image, which is slower to boot.

---

## Open questions for the product owner

1. **Channels.** Is Google Play a goal at all, given that its build cannot stream or download YouTube? Or is Neutrodyne "F-Droid/GitHub first, Play maybe later"? What priority do IzzyOnDroid and F-Droid main have?
2. **Developer verification.** Will a named person or organisation register with Google, with government ID and the $25 Full Distribution fee, and register the package name and signing key? Without that, from 2027 every sideloaded install requires Google's 24-hour advanced flow.
3. **Licence.**
   * Is it acceptable that the `foss` APK is distributed under GPL-3.0-or-later (because of NewPipeExtractor) while the source stays Unlicense?
   * Or should the whole repository simply be GPL-3.0-or-later, which is simpler for F-Droid metadata and contributors?
4. **Signing key custody.**
   * Who holds the release key and its backup?
   * Is "one key for all channels, including Play via your own key upload" acceptable? It is convenient, but if the key is lost every channel is affected.
5. **One package name or two?** Should Play and FOSS share an `applicationId`, so users can switch editions and keep their data, or be separate apps that can be installed side by side?
6. **Crash reporting.**
   * Is opt-in ACRA by email acceptable?
   * If so, which mailbox receives the reports, and who triages them?
   * Or do you prefer no crash reporter at all?
7. **Privacy statement.**
   * Should any optional network service be included: podcast directory search (iTunes Search, Podcast Index, fyyd), SponsorBlock (`foss`), or gpodder.net/Nextcloud sync?
   * Each one needs a line in `PRIVACY.md`, and some may affect F-Droid anti-features.
8. **Cleartext HTTP feeds.** Should they be allowed by default, as most podcast apps do, or should we try HTTPS first and warn?
9. **Launch languages and Weblate.** Which languages should ship at launch? Is a public Weblate project (community translations) acceptable?
10. **Release cadence and betas.**
    * Should there be a public beta channel (GitHub pre-releases via Obtainium, Play open testing)?
    * What is the target time from "YouTube broke" to a fix release?
11. **Repository visibility and hosting.** Will the GitHub repo be public from day one? That is needed for free hosted-runner minutes at 4 vCPU, the Weblate Libre plan, IzzyOnDroid and F-Droid. Is GitHub the long-term home, or is Codeberg a consideration? F-Droid and IzzyOnDroid support both; the CI design here is GitHub-specific.
12. **Performance budgets.** Are the starting budgets acceptable: cold start p50 under 600 ms on a mid-range device, scroll jank under 1%, and a `foss` APK under 25 MB? Which reference device should benchmarks use?
