# 09 — Quality and release

> Status: Draft v1, 2026-10-04 · Implements: N1 / N3 / N4 / N5 / N8 / N9 / N10 / N11 (quality and release parts), R2.9 (measurement) · Milestones: M0–M11 · Honours: D2, D3, D59, D60, D61, D62, D63; PO-2, PO-5, PO-8, PO-10, PO-14, PO-18 defaults · Owns: test strategy and infrastructure, CI workflows, static-analysis gates, dependency updates, versioning, signing and key custody, distribution channels, reproducible builds, developer verification, privacy policy and network inventory, crash reporting and diagnostics content, localisation workflow, performance budgets, release checklists

Contents: [Scope](#scope) · [Test strategy](#test-strategy) · [Test infrastructure](#test-infrastructure) · [CI pipelines](#ci-pipelines) · [Static analysis](#static-analysis) · [Dependency updates](#dependency-updates) · [Versioning and signing](#versioning-and-signing) · [Distribution channels](#distribution-channels) · [Reproducible builds](#reproducible-builds) · [Developer verification](#developer-verification) · [Privacy](#privacy) · [Crash reporting and diagnostics](#crash-reporting-and-diagnostics) · [Localisation](#localisation) · [Performance budgets](#performance-budgets) · [Release checklist](#release-checklist) · [Settings](#settings) · [Delivery by milestone](#delivery-by-milestone) · [New names introduced here](#new-names-introduced-here) · [Open questions](#open-questions) · [Sources](#sources)

---

## Scope

This document is the engineering-process contract: how every other document's code is tested, gated, built, signed, shipped and measured. An implementer (human or AI session) reads it at M0 to set up the machinery, and again whenever a milestone adds a test type, a workflow job, a store or a release.

**Owned here** (other documents link, never restate):

| Topic | Section |
|---|---|
| Test pyramid, per-change test obligations, E2E journey catalogue, flakiness policy | [Test strategy](#test-strategy) |
| `neutrodyne.android.testing` content, shared test helpers, `:core:testing` inventory, fakes and contract tests, Robolectric/Room/MockWebServer/Media3/WorkManager/Roborazzi/GMD configuration, recorded-response and fixture policy | [Test infrastructure](#test-infrastructure) |
| GitHub Actions workflows `ci.yml`, `nightly.yml`, `release.yml`, helper workflows, CI scripts, caching, hardening, required checks | [CI pipelines](#ci-pipelines) |
| Lint, Spotless/ktlint/compose-rules, detekt, `.editorconfig`, build-output checks, PR template | [Static analysis](#static-analysis) |
| Renovate configuration and update review rules | [Dependency updates](#dependency-updates) |
| Version scheme procedure, `scripts/release.sh`, changelogs, key ceremony and custody, signing, Play App Signing | [Versioning and signing](#versioning-and-signing) |
| GitHub/Obtainium, IzzyOnDroid, F-Droid, Google Play procedures, store metadata | [Distribution channels](#distribution-channels) |
| Reproducibility hygiene, nightly repro job, F-Droid fallback | [Reproducible builds](#reproducible-builds) |
| Developer verification registration | [Developer verification](#developer-verification) |
| `PRIVACY.md`, network inventory, redaction surfaces, Data safety answers, `SECURITY.md` | [Privacy](#privacy) |
| ACRA configuration, `CrashReporter`/`CrashContext`, diagnostics screen content and actions | [Crash reporting and diagnostics](#crash-reporting-and-diagnostics) |
| Weblate, string conventions, shipped locales, pseudo-locales, fastlane translation | [Localisation](#localisation) |
| Reference devices, budgets, Macrobenchmark wiring, baseline/startup profiles, size budgets | [Performance budgets](#performance-budgets) |
| Checklists per release type and the v1.0 gate | [Release checklist](#release-checklist) |

**Not covered here:** the version catalog and convention-plugin skeletons ([01 Toolchain and versions](01-foundation.md#toolchain-and-versions)); Gradle-side policy tasks `assertModuleGraph`, `verifyDependencyPolicy`, `verifyManifestPermissions`, `checkSpdxHeaders`, `checkBannedApis`, Licensee allow-list ([01 Licensing and dependency policy](01-foundation.md#licensing-and-dependency-policy)) — 09 only decides when CI runs them; the logging `Redactor` algorithm ([01 Logging and redaction](01-foundation.md#logging-and-redaction)); what each feature area tests (the `## Testing` sections of [02](02-data-model.md#testing), [03](03-feeds-and-discovery.md#testing), [04](04-youtube.md#testing), [05](05-groups-opml-backup.md#testing), [06](06-playback.md#testing), [07](07-downloads.md#testing), [08](08-ui-ux.md#testing)); the diagnostics screen's visuals ([08 Diagnostics](08-ui-ux.md#diagnostics)); YouTube legal texts, corresponding-source content and the hotfix runbook ([04 Licensing and legal](04-youtube.md#licensing-and-legal), [04 Maintenance and hotfix process](04-youtube.md#maintenance-and-hotfix-process)); Auto Backup rules ([05 Auto Backup](05-groups-opml-backup.md#auto-backup)); the `dataSync` declaration text ([07 Manifest and Play declaration](07-downloads.md#manifest-and-play-declaration)).

**Repository files owned by this document** (created in the milestone shown in [Delivery by milestone](#delivery-by-milestone)): `.editorconfig`, `.github/workflows/{ci,nightly,release,record-screenshots,baseline-profile}.yml`, `.github/PULL_REQUEST_TEMPLATE.md`, `.github/ISSUE_TEMPLATE/{bug.yml,feature.yml,release.md}`, `renovate.json`, `config/detekt/detekt.yml`, `app/lint-baseline.xml`, `app/policy/locales.txt`, `scripts/release.sh`, `scripts/ci/*.sh`, `scripts/l10n/update-shipped-locales.sh`, `scripts/youtube/bump-extractor.sh`, `fastlane/metadata/android/**`, `fdroid/app.neutrodyne.yml` (draft of the fdroiddata recipe), `PRIVACY.md`, `SECURITY.md`. `CONTRIBUTING.md` content is 01's ([Copied code and contributions](01-foundation.md#copied-code-and-contributions)); 09 adds the testing and release sections.

---

## Test strategy

Serves N1, N9, N11 and every milestone's acceptance criteria. Delivered from M0; each milestone adds the tests its documents list. Honours [D59](../PLAN.md#3-key-decisions).

### Principles

1. **Fakes over mocks.** Every `:core:domain` and `*:api` interface has a hand-written fake in `:core:testing`; MockK is allowed only in JVM `src/test` for final third-party classes, never in `src/androidTest` ([Test infrastructure](#coretesting-inventory)).
2. **The JVM is the default runner.** Pure logic runs on the plain JVM; Android-dependent logic (Room, WorkManager, Media3, Compose) runs under Robolectric; only behaviour Robolectric cannot reproduce (bundled SQLite driver, Keystore, FGS/UIDT, audio hardening, 16 KB pages, process death, real decoders at scale) runs on emulators.
3. **Goldens make format code reviewable.** Parsers, writers and codecs compare against committed golden JSON/XML; screenshots are committed PNGs. Both are rewritten only by an explicit switch.
4. **Nothing in a blocking job touches the internet.** Feeds, directories and YouTube are served by MockWebServer or replayed from recorded responses; live canaries run nightly and never block.
5. **Deterministic time and locale.** No production code reads the wall clock directly (01 `Clock`); tests use `TestClock` and run in `de_DE` / `America/St_Johns` to flush out locale and offset bugs.
6. **Every bug fix starts with a failing test** reproducing it, in the lowest layer that can show it.

### Test pyramid

| Layer | What it covers | Modules | Runner | Trigger |
|---|---|---|---|---|
| Pure JVM | parsers and writers with goldens, identity keys, URL rules, classifiers, selectors, `Redactor`, rule tables, mutation robustness | `:core:model`, `:core:common`, `:core:domain`, `:feeds`, `:*:api` | JVM (`test`) | every PR |
| Local unit (Android module, no Android API) | ViewModels with fakes and Turbine, mappers, planners with fakes | `:feature:*`, `:core:ui`, impl modules | JVM with `android.jar` stubs (`isReturnDefaultValues = false`) | every PR |
| Robolectric | DAOs and repositories on an in-memory `TestDb`, workers with the WorkManager test driver, Media3 player logic with `media3-test-utils`, Compose UI tests (v2 rule + accessibility checks), Roborazzi screenshots, Hilt graph tests | Android modules | JVM + Robolectric 4.17, `sdk=36` | every PR |
| Instrumented (GMD `ci` group) | migrations on both drivers, platform XML parser corpus, Keystore, `MediaController` ↔ service, E2E journeys, `play` flavor UI | `:app`, `:core:database`, `:core:data`, `:playback:impl`, `:download:impl` | Gradle Managed Devices API 26 + 36 | push to `main`, PRs labelled `run-instrumented`, nightly |
| Instrumented nightly-only | long-running and API-specific behaviour (30-min background auto-advance, 20-min UIDT beside playback, API 33 `dataSync`, API 37 hardening and 16 KB), minified `fossRelease` | same | GMD `nightly` group + emulator-runner API 37 | nightly, release candidates |
| Out-of-process system | process death and journeys driven by UI Automator from a separate test APK; Macrobenchmark dry runs | `:benchmark` | GMD (`aosp` image) | nightly (from M6 / M11) |
| Device and manual | Bluetooth, AVRCP, Android Auto DHU, Wear, OEM restrictions, performance budgets on the reference device, accessibility passes, `bmgr` spot checks | — | reference device + checklists | per milestone and [release](#release-checklist) |
| Live canaries | ~30 public feeds (03), YouTube smoke (04) | `:feeds`, `:youtube:streams` | JVM with network | nightly, non-blocking |

**Placement rule:** `src/androidTest` exists only in the five modules named in the instrumented row (plus `:benchmark`, which is a test module). Feature Compose UI tests run under Robolectric; this keeps the number of emulator boots per CI run bounded (each module's managed-device task boots its own emulator).

### Test obligations per change

A PR is not mergeable without the tests in this table for the kind of code it touches (reviewers check; the PR template lists it).

| Change | Required in the same PR |
|---|---|
| Parser, writer or codec in `:feeds` (feed, OPML, backup, import formats) | one golden fixture per new quirk; the format's [mutation robustness](#untrusted-input-robustness) providers updated; a hostile-input case if a new input format or limit is added |
| SQL, DAO, index | Robolectric DAO test on `TestDb`; a `QueryPlanTest` row for key queries; an `InvalidationHygieneTest` row for anything a paged query observes ([02 Hygiene tests](02-data-model.md#hygiene-tests)) |
| Schema change | version bump, exported JSON, migration and `MigrationNToMTest` with invariants ([02 Tests](02-data-model.md#tests)) |
| Repository or use case | test against fakes of its collaborators; if it implements an interface whose fake has behaviour, run the [contract test](#fake-contract-tests) against the real implementation |
| New `:core:domain` / `*:api` interface | `Fake<Name>` in `:core:testing` (+ contract test if the fake has rules) |
| ViewModel | Turbine test of `uiState`: initial, content, empty, error, each user action, message acknowledgement |
| Worker or job | WorkManager `TestDriver` / `TestListenableWorkerBuilder` test: success, retry, stop at soft deadline (continuation enqueued), idempotent re-run |
| Composable screen or component | Compose v2 Robolectric test with `enableAccessibilityChecks()`; Roborazzi capture of every state 08 lists for it |
| Network code | MockWebServer test including every response code its owning document's table names |
| Playback | `media3-test-utils` test (Robolectric); instrumented session test if it touches the service lifecycle |
| Platform-dependent behaviour (FGS, UIDT, audio focus/hardening, backup, notifications permission) | nightly instrumented test **or** a row in the owning document's manual device checklist, named in the PR |
| New user journey in the [E2E catalogue](#end-to-end-journeys) | the journey test |
| New user-visible string | resource in the module's `strings.xml`; plural via `<plurals>`; no concatenation ([Localisation](#string-conventions)) |
| Bug fix | regression test that fails before the fix |

### End-to-end journeys

Run on GMD (`:app/src/androidTest`) against the **real** `NeutrodyneApplication` and Hilt graph (no `HiltTestApplication`); network comes from an on-device MockWebServer bound to `127.0.0.1` (cleartext is allowed by [D28](../PLAN.md#3-key-decisions)'s network security config; `LocalNetworkGuardDns` passes loopback). Data enters through the UI, public intents or `TestSeeder`.

| ID | Test class | Journey | Asserts | From | Devices |
|---|---|---|---|---|---|
| E0 | `SmokeTest` | launch | five labelled destinations, rotation, dark-mode switch, About shows flavor and version, Licences non-empty (PLAN M0 AC4–5) | M0 | `ci`, API 37 |
| E1 | `SubscribeJourneyTest` | `neutrodyne://subscribe?url=http://127.0.0.1:{port}/feeds/rss2-minimal.xml` → add sheet → Subscribe → Library → Podcast → Episode | tile with cover or monogram; paged episodes; show notes rendered; refresh via pull-to-refresh hits the server with `If-None-Match` | M1 | `ci` |
| E2 | `GroupFeedJourneyTest` | create group "tech" → add podcast → Feeds tab "tech" | episodes newest first; podcast in "tech" and "news" appears in both and once in All (M2 AC4); `ActivityScenario.recreate()` keeps the selected tab | M2 | `ci` |
| E3 | `PlayJourneyTest` | play from a group feed → mini player → Home (UI Automator) → pause from notification → reopen | notification shows cover from `content://…artwork/…`; position persisted (`episode_position` > 0 after pause); "Play group" order (M4 AC6) | M4 | `ci` |
| E4 | `DownloadJourneyTest` | download a throttled 5 MB enclosure → progress → complete → stop the server → play | file under `Android/data/…/Podcasts/`; plays with the server down (M6 AC6) | M6 | `ci` |
| E5 | `ImportJourneyTest` | VIEW a `content://` OPML (provider in the test APK) via `ExternalImportActivity` → preview → confirm | 20 pending tiles within 2 s; all fetched; report shows one `NOT_A_FEED`; zero `download` rows | M3 | `ci` |
| E6 | `BackupRestoreJourneyTest` | seed state A → create backup → mutate (delete group, mark played) → Replace restore | state A restored: groups, memberships, played, positions, Up next (M3 AC5) | M3 | `ci` |
| E7 | `YouTubeReleaseSmokeTest` | seeded YouTube podcast (`TestSeeder`) → play → download | minified `fossRelease`: R8 kept Rhino and extractor paths; one resolve and one chunked download through `ReplayDownloader` + MockWebServer googlevideo stand-in ([04 Testing](04-youtube.md#testing), M9 AC4) | M9 | nightly, release |
| E8 | `PlayFlavorYouTubeTest` (`playDebug`) | seeded YouTube rows | "Watch on YouTube" fires `ACTION_VIEW` (captured with `Instrumentation.ActivityMonitor`); no queue/download actions; "Play group" skips them (M8 AC5) | M8 | `api36` |
| E9 | `FirstLaunchRestoreTest` | snapshot file + empty DB | 05's assertions ([05 Testing](05-groups-opml-backup.md#testing)) | M3 | `ci` |
| E10 | `ProcessDeathResumeTest` | kill the app mid-download from `:benchmark` | 07's assertions ([07 Instrumented and device tests](07-downloads.md#instrumented-and-device-tests)) | M6 | nightly |

Flavor-specific journeys guard themselves with `assumeTrue(BuildConfig.FLAVOR == "foss")` (E7) or `"play"` (E8), because the `instrumented` job runs the `:app` suite for both flavors. `PlaybackServiceTest` (06), `UidtDownloadTest` and `DataSyncWorkerTest` (07) and the migration tests (02) are not journeys but run in the same instrumented jobs; their devices are listed in [Gradle Managed Devices](#gradle-managed-devices).

**E7 seam:** NewPipe Extractor's downloader is process-global (`NewPipe.init(downloader)`), so the test re-initialises it with `ReplayDownloader` after app start; recorded player responses are rewritten so stream URLs point at the test's MockWebServer. No Hilt test override is needed, which is what makes the test run against an R8-minified APK. Unverified: that a second `NewPipe.init` call fully replaces the first (M9 check; fallback: an `androidTest`-only Hilt module on a non-minified `foss` build plus the dex/R8 checks in [Build-output checks](#build-output-checks)).

### Untrusted-input robustness

Serves N9. Two complementary layers:

1. **Hostile inputs with caps** — owned by the format documents: [03 Golden corpus](03-feeds-and-discovery.md#golden-corpus-feedssrctestresourcesfeeds) (entity DOCTYPE, deep nesting, oversized text) and 05's `HostileInputTest` (billion laughs, 10k nesting, 100k outlines, 10 MB attribute, zip bomb, zip-slip; PLAN M3 AC3).
2. **`MutationRobustnessTest`** (09, `:feeds` and `:youtube:api`, JVM, TestParameterInjector): for every committed fixture of `FeedParser`, `OpmlReader`, `BackupCodec`, `NewPipeSubscriptions`, `LibreTubeBackupParser`, `TakeoutSubscriptionsParser` and for 200 seeded random strings of `YouTubeUrlClassifier`, apply seeded mutations — truncate at 10 offsets, flip 1–16 random bytes, duplicate a random 1 KB slice, insert `<!DOCTYPE x [<!ENTITY e "...">]>`, replace the declared encoding, insert NUL and lone surrogates. Assert: the call returns its declared result type (`ParseResult`/`Outcome` failure is fine), throws nothing except `CancellationException`, finishes in < 2 s, and stays within a 64 MB heap (the class runs only in a dedicated `Test` task `mutationTest` with `-Xmx64m`, wired into `check`; the regular `test` task excludes it with `filter.excludeTestsMatching("*MutationRobustnessTest")`). PR runs use 20 mutations per fixture (seed = fixture name hash); nightly runs 1,000 per fixture (`-PmutationIterations=1000`) and prints the failing seed.

### Flakiness policy

- Unit and Robolectric tests must be deterministic; a flaky JVM test is a bug fixed or reverted within 24 h.
- An instrumented test that fails intermittently is annotated `@androidx.test.filters.FlakyTest` with a comment linking its issue within 24 h; blocking runs exclude `FlakyTest`, the nightly run includes it. It must be fixed or deleted within 14 days.
- No automatic retries in CI (retries hide real races such as position-loss bugs). Managed-device infrastructure failures (emulator boot timeout) may be re-run manually once.

### Naming and style rules

- Test classes `<Subject>Test`; contract bases `<Interface>Contract`; journeys `<Name>JourneyTest`.
- Back-ticked sentence names only in `src/test`. `src/androidTest` and `:benchmark` use camelCase: D8 rejects spaces in identifiers below DEX 040 (API 30), and minSdk is 26.
- Assertions with Truth only; no `kotlin.test` assertions, no Hamcrest.
- Fixtures under `src/test/resources/<area>/` (JVM and Robolectric) or `src/androidTest/assets/<area>/` (instrumented); never read from `build/`.

---

## Test infrastructure

Serves N1, N11. Delivered in M0 (configuration, base helpers), grown per milestone. Versions: [01 Toolchain and versions](01-foundation.md#toolchain-and-versions).

### Gradle test configuration

`neutrodyne.android.testing` (applied by 01's application and library plugins) and `neutrodyne.jvm.library` both call the shared `configureNeutrodyneTestTasks()`; the Android plugin adds the Android-only parts.

```kotlin
// build-logic: shared by JVM and Android modules
internal fun Project.configureNeutrodyneTestTasks() = tasks.withType<Test>().configureEach {
    systemProperty("user.timezone", "America/St_Johns")          // UTC-3:30, DST, non-integral offset
    jvmArgs("-Duser.language=de", "-Duser.country=DE", "-Xshare:off", "-XX:+EnableDynamicAgentLoading")
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    maxHeapSize = "2g"
    val update = providers.gradleProperty("updateGoldens").isPresent
    systemProperty("neutrodyne.updateGoldens", update)
    systemProperty("neutrodyne.moduleDir", layout.projectDirectory.asFile.absolutePath)
    systemProperty("neutrodyne.rootDir", rootProject.layout.projectDirectory.asFile.absolutePath)
    systemProperty("neutrodyne.screenshotTier", providers.gradleProperty("screenshotTier").getOrElse("pr"))
    systemProperty("neutrodyne.mutationIterations", providers.gradleProperty("mutationIterations").getOrElse("20"))
    if (update) outputs.upToDateWhen { false }
    testLogging { events("failed"); exceptionFormat = TestExceptionFormat.FULL }
}
```

```kotlin
// build-logic: neutrodyne.android.testing (Android modules only)
internal fun Project.configureAndroidTesting(ext: CommonExtension) {
    ext.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ext.defaultConfig.testInstrumentationRunnerArguments["clearPackageData"] = "true"
    if (providers.gradleProperty("neutrodyne.testScope").getOrElse("ci") == "ci")   // nightly/release scopes run everything
        ext.defaultConfig.testInstrumentationRunnerArguments["notAnnotation"] =
            "app.neutrodyne.core.testing.Nightly,androidx.test.filters.FlakyTest"
    ext.testOptions.unitTests.isIncludeAndroidResources = true   // Robolectric + Roborazzi need merged resources
    ext.testOptions.unitTests.isReturnDefaultValues = false      // unmocked android.* calls fail loudly
    ext.testOptions.animationsDisabled = true
    ext.testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"
    configureManagedDevices(ext.testOptions.managedDevices)      // see Gradle Managed Devices
    configureNeutrodyneTestTasks()
    dependencies {
        "testImplementation"(platform(libs.okhttp.bom))           // aligns media3-test-utils' MockWebServer 4.12 to 5.5.0
        "testImplementation"(libs.bundles.unit.test)              // junit4, truth, turbine, coroutines-test, TPI
        "testImplementation"(libs.robolectric)
        "androidTestImplementation"(platform(libs.okhttp.bom))
        "androidTestImplementation"(libs.androidx.test.runner); "androidTestImplementation"(libs.androidx.test.ext.junit)
        "androidTestImplementation"(libs.truth); "androidTestUtil"(libs.androidx.test.orchestrator)
        if (path != ":core:testing") { "testImplementation"(project(":core:testing")); "androidTestImplementation"(project(":core:testing")) }
    }
    configurations.configureEach { resolutionStrategy.eachDependency {   // media3-test-utils pulls Robolectric 4.16
        if (requested.group == "org.robolectric" && !requested.name.startsWith("android-all")) useVersion(libs.versions.robolectric.get())
    } }
}
```

- `src/test/resources/robolectric.properties` in every Android module: `sdk=36` (one `android-all-instrumented` jar, ~100 MB, cached in CI). Bump to 37 only together with a full Roborazzi re-record.
- Robolectric 4.16+ needs JDK 21 for SDK 36; CI uses Temurin 21 ([01](01-foundation.md#version-table)).
- AGP 9 creates unit tests only for the tested build type (`debug`): library modules run `testDebugUnitTest` once; `:app` runs `testFossDebugUnitTest` and `testPlayDebugUnitTest`.
- Unverified: Robolectric replaces the JVM default locale with the qualifier locale (`en-rUS`), which would neutralise `-Duser.language=de` in Robolectric tests; tests that guard wire formats against locale bugs therefore live on the plain JVM or use `@Config(qualifiers = "de-rDE")` explicitly (M0 check).

### Shared helpers

`:core:testing` is an Android library and cannot be a dependency of the pure-JVM modules. The three helpers JVM tests also need are authored as **test fixtures of `:core:common`** (`java-test-fixtures` plugin, sources in `core/common/src/testFixtures/kotlin/app/neutrodyne/core/testing/`, package `app.neutrodyne.core.testing`) and re-exported by `:core:testing` with `api(testFixtures(project(":core:common")))`, so every module imports them from the same package. JVM modules declare `testImplementation(testFixtures(project(":core:common")))`; test edges are not asserted by the module graph ([01 Dependency rules](01-foundation.md#dependency-rules) rule 13).

```kotlin
package app.neutrodyne.core.testing

/** Deterministic Clock (01). Optionally tied to a coroutine test scheduler's virtual time. */
class TestClock(var nowMs: Long = DEFAULT_NOW, var elapsedMs: Long = 0L) : Clock {
    override fun now(): Long = nowMs
    override fun elapsedRealtime(): Long = elapsedMs
    fun advanceBy(d: Duration) { nowMs += d.inWholeMilliseconds; elapsedMs += d.inWholeMilliseconds }
    companion object {
        const val DEFAULT_NOW = 1_791_072_000_000L                         // 2026-10-04T00:00:00Z
        fun from(s: TestCoroutineScheduler, start: Long = DEFAULT_NOW) = object : Clock {
            override fun now() = start + s.currentTime
            override fun elapsedRealtime() = s.currentTime
        }
    }
}

class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

object Goldens {
    /** Compares [actual] with src/test/resources/[path]; rewrites it when -PupdateGoldens is set (refused when CI=true). */
    fun assertMatches(actual: String, path: String)
    /** Pretty-printed JSON with object keys sorted recursively and explicit nulls dropped. */
    fun canonicalJson(element: JsonElement): String
    fun fixture(path: String): File            // resolves against neutrodyne.moduleDir/src/test/resources
}
```

- Golden file naming follows the owning document (`<name>.golden.json` in 03, `*.expected.json` in 05); `Goldens` does not impose a suffix.
- The switch: `./gradlew :feeds:test -PupdateGoldens` locally, then review the diff like code. `Goldens` throws if `CI=true` and the switch is on, so CI can never "fix" a failure by rewriting.
- `runTest` reuses the scheduler of a `TestDispatcher` installed as Main (coroutines 1.11), so ViewModel tests use `MainDispatcherRule` + `runTest {}` without passing dispatchers twice.

### `:core:testing` inventory

Package `app.neutrodyne.core.testing`; dependencies per rule 9 (`:core:{domain, model, common}`, `:*:api`); `api` exports junit4, truth, turbine, coroutines-test, coil-test, hilt-android-testing ([01 Module layout](01-foundation.md#module-layout)).

| Item | Fakes / provides | Interface owner | From |
|---|---|---|---|
| `MainDispatcherRule`, `TestClock`, `Goldens` (re-exported) | — | 09 | M0 |
| `FakeNetworkMonitor` | `NetworkMonitor` (`setStatus(...)`) | 01 | M0 |
| `FakeSettingsRepository` | `SettingsRepository` (in-memory map per file) | 01 | M0 |
| `FakeCrashReporter`, `FakeCrashContext` | [`CrashReporter`, `CrashContext`](#crashreporter-and-crashcontext) | 09 | M0 |
| `FakePodcastRepository`, `FakeEpisodeRepository`, `FakeRefreshController`, `FakeAddPodcastResolver`, `FakeSubscribeUseCase`, `FakeIngestionEvents` | 03 interfaces | 03 | M1 |
| `FakeSearchRepository` | `SearchRepository` | 03 | M7 |
| `FakeFeedRepository`, `FakeGroupRepository`, `FakeEffectiveSettingsResolver`, `FakeScopeSettingsRepository`, `FakePlayContextResolver` | 05 interfaces | 05 | M2 (resolver M2/M4) |
| `FakeImportRepository`, `FakeBackupRepository` | 05 interfaces | 05 | M3 |
| `FakeEpisodeLiveStateSource`, `FakeArtworkRepository` | 08 interfaces | 08 | M2, M4 |
| `FakePlaybackController`, `FakePlaybackStateSource`, `FakeQueueRepository`, `FakeChapterRepository`, `FakePlaybackMaintenance` | 06 interfaces | 06 | M4 (chapters M5) |
| `FakeDownloadController`, `FakeLocalMediaIndex`, `FakeDownloadProgressSource` | 07 interfaces | 07 | M6 |
| `FakeYouTubeChannelResolver`, `FakeYouTubeStreamResolver`, `FakeYouTubeEnricher`, `FakeYouTubeChannelRepository`, `FakeYouTubeHealth`, `testCapabilities(foss: Boolean)` | 04 interfaces | 04 | M8, M9 |
| `FakeDiagnosticsRepository` | [`DiagnosticsRepository`](#diagnostics-api) | 09 | M11 |
| Data builders `podcast(…)`, `episode(…)`, `episodeRow(…)`, `group(…)`, `rowLive(…)` | `:core:model` instances with readable defaults | 09 | M1 |
| `fakeImageLoader(context)` | Coil `ImageLoader` with `FakeImageLoaderEngine` returning deterministic `ColorImage`s keyed by URL hash | 09 | M1 |
| `Nightly` annotation, `ScreenshotTier` | test selection | 09 | M0, M2 |
| `*Contract` bases | [fake contract tests](#fake-contract-tests) | 09 | with each fake |

Rule: an interface added to `:core:domain` or `*:api` by any document gets its `Fake<Name>` in the same PR. Fakes are backed by `MutableStateFlow`, expose test-only mutators (`emit…`, `failNext: <ErrorType>?`) and a call log (`calls: List<String>`), and never sleep.

**Not in `:core:testing`** (rule 9 forbids the dependencies):

| Helper | Location | Consumers |
|---|---|---|
| `TestDb`, `SeedDatabase`, `FeedFixture`, `db/v1-fixture.sql` (02's names) | `:core:database` **Android test fixtures** (`android { testFixtures { enable = true } }`, `core/database/src/testFixtures/`) — replaces 02's `sharedTest` path so other modules can consume them | `testImplementation(testFixtures(project(":core:database")))` in `:core:data`, `:core:artwork`, `:playback:impl`, `:download:impl`, `:app` |
| `RecordingAppNavigator` (records `push`/`selectTab`/`pop`) | `:core:navigation` test fixtures | feature Compose tests via `LocalAppNavigator` |
| `ReplayDownloader`, `RecordingDownloader` (04's names) | `:youtube:streams` test fixtures (GPL; never shipped) | `:youtube:streams` tests, `:app` `androidTest` (E7) |
| `@TestInstallIn` Hilt modules (`TestSqliteDriverModule` and fake bindings) | `:app/src/test/kotlin/app/neutrodyne/di/` | Robolectric `@HiltAndroidTest` tests in `:app` |
| `TestServer`, `TestSeeder` | `:app/src/androidTest` | E2E journeys |

Unverified: Kotlin sources in AGP Android test fixtures under AGP 9.4 (AGP 8.x needed `android.experimental.enableTestFixturesKotlinSupport`); checked in M0 with `:core:navigation`, fallback: a `src/sharedTest` directory added to each consumer's test source set by path.

### Fake contract tests

A fake that encodes rules (ordering, uniqueness, state transitions) is verified against the same contract as the real implementation, so fakes cannot drift.

```kotlin
// :core:testing (main source set; JUnit is an api dependency)
abstract class GroupRepositoryContract {
    protected abstract fun subject(clock: TestClock): GroupRepository
    @Test fun nameKeyIsUniqueAcrossCaseAndNfc() = runTest {
        val repo = subject(TestClock())
        val tech = (repo.create(GroupDraft(name = "Tech")) as Outcome.Success).value
        assertThat(repo.create(GroupDraft(name = "tech"))).isEqualTo(Outcome.Failure(GroupError.NameTaken(tech)))
        assertThat(repo.create(GroupDraft(name = "Cafe\u0301"))).isInstanceOf(Outcome.Success::class.java)
        assertThat(repo.create(GroupDraft(name = "Caf\u00e9"))).isInstanceOf(Outcome.Failure::class.java)
    }
    @Test fun reorderRejectsNonPermutation() = runTest {
        val repo = subject(TestClock())
        val a = (repo.create(GroupDraft(name = "a")) as Outcome.Success).value
        assertThat(repo.reorder(listOf(a, 999L))).isEqualTo(Outcome.Failure(GroupError.NotFound))
    }
}
class FakeGroupRepositoryContractTest : GroupRepositoryContract() {             // :core:testing/src/test
    override fun subject(clock: TestClock) = FakeGroupRepository(clock)
}
@RunWith(RobolectricTestRunner::class)
class GroupRepositoryImplContractTest : GroupRepositoryContract() {             // :core:data/src/test
    override fun subject(clock: TestClock) = GroupRepositoryImpl(TestDb.inMemory(), clock /* … */)
}
```

Contract bases exist for `GroupRepository`, `QueueRepository`, `SettingsRepository`, `PodcastRepository` (subscribe/unsubscribe visibility), `EpisodeRepository` (played/favourite), `FeedRepository` (order and filters on a 20-episode fixture) and `DownloadController` (state reported after request/pause/resume/cancel). Signatures follow the owning documents; the sketch shows the pattern only.

### ViewModel test template

```kotlin
class PodcastViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val podcasts = FakePodcastRepository()

    @Test fun `shows the podcast and then a message when editing the feed URL fails`() = runTest {
        podcasts.emitDetail(podcastDetail(id = 7, title = "Tech Talk"))      // FakePodcastRepository test API
        val vm = PodcastViewModel(PodcastKey(7), podcasts)
        vm.uiState.test {                                       // stateIn(WhileSubscribed) starts on collection
            assertThat(awaitItem()).isEqualTo(PodcastUiState.Loading)
            val ready = awaitItem() as PodcastUiState.Ready
            assertThat(ready.header.title).isEqualTo("Tech Talk")
            podcasts.failNext = AddPodcastError.NotAFeed
            vm.onEditFeedUrl("https://example.invalid/page.html")       // calls PodcastRepository.editFeedUrl
            assertThat((awaitItem() as PodcastUiState.Ready).messages).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

Paged properties (`items: Flow<PagingData<T>>`) are asserted with `paging-testing`'s `asSnapshot()`. Turbine 1.x timeouts are wall-clock, so Room-backed flows on real threads also work inside `runTest`.

### Robolectric

- Runner `RobolectricTestRunner` (or `AndroidJUnit4`), SDK 36, `@GraphicsMode(GraphicsMode.Mode.NATIVE)` on screenshot tests.
- Hilt graph tests in `:app/src/test`: `@HiltAndroidTest`, `@Config(application = HiltTestApplication::class)`, `HiltAndroidRule`; `TestSqliteDriverModule` replaces the bundled driver ([01 Test overrides](01-foundation.md#test-overrides)).
- `StateRestorationTester` for process-death-like restoration of Compose state; real process death is tested out of process.

### Room

- JVM/Robolectric: `TestDb.inMemory(driver = AndroidSQLiteDriver())` from `:core:database` test fixtures (02). The bundled driver's `.so` files target device ABIs and do not load on the host ([D9](../PLAN.md#3-key-decisions)).
- Instrumented (GMD API 26 and 36): migration tests run with **both** `BundledSQLiteDriver` and `AndroidSQLiteDriver` ([02 Tests](02-data-model.md#tests)).
- Schema assets: `sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")`. For Robolectric, spike S4 ([01 S4](01-foundation.md#s4-robolectric-with-androidsqlitedriver)) decides between Room 3's path-based `MigrationTestHelper` (schema directory read through `neutrodyne.moduleDir`) and running migrations on GMD only.

### Network

- JVM: `MockWebServerRule` from `mockwebserver3-junit4`; HTTPS cases with `okhttp-tls` `HeldCertificate`/`HandshakeCertificates`.
- On device: `TestServer` (JUnit rule in `:app/src/androidTest`) wraps `mockwebserver3.MockWebServer` bound to `127.0.0.1` with a `FixtureDispatcher` serving `androidTest/assets/e2e/**`; `{{base}}` placeholders in fixture feeds are replaced with the server URL so enclosures and artwork point back to it; `throttleBody` simulates slow downloads; `shutdown()` simulates offline. Certificate Transparency (Android 17) cannot be exercised with a local CA and is covered by 01's `NetErrorClassifier` unit test only.
- No `src/debug` network security config is needed: release and debug both permit cleartext (D28).

### Media3

`media3-test-utils` and `media3-test-utils-robolectric` (`testImplementation` in `:playback:impl` only): `TestExoPlayerBuilder` with `FakeClock`, `FakeMediaSource`/`FakeTimeline`, `TestPlayerRunHelper.advance(player).untilState(…)` / `play(player).untilPositionAtLeast(…)` (the old `run()` is deprecated). Real-decode cases use 06's generated fixtures with `ShadowMediaCodecConfig`. Instrumented session tests build a `MediaController` against `NeutrodynePlaybackService` ([06 Testing](06-playback.md#testing)). Every Media3 bump re-runs the full playback suite (risk M3r).

### WorkManager and JobScheduler

`work-testing`: `WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build())`, then `getTestDriver(ctx)!!.setAllConstraintsMet(id)` / `setPeriodDelayMet(id)`; single workers with `TestListenableWorkerBuilder`. Job quotas beside an FGS (Android 16), UIDT behaviour and audio hardening cannot be reproduced in Robolectric; they run on GMD nightly ([07](07-downloads.md#instrumented-and-device-tests), [06](06-playback.md#testing)).

### Compose UI and screenshot tests

- Compose v2 test APIs (`androidx.compose.ui.test.junit4.v2.createComposeRule` / `createAndroidComposeRule`, `StandardTestDispatcher`); `mainClock.autoAdvance = false` for indeterminate progress.
- Every Compose test calls `enableAccessibilityChecks()` (`ui-test-junit4-accessibility`); rules and custom-action checks are 08's ([08 Automated checks](08-ui-ux.md#automated-checks)).
- **Roborazzi** (`io.github.takahirom.roborazzi` plugin, applied by `neutrodyne.android.compose` modules that have screenshot tests): `roborazzi { outputDir.set(file("src/test/screenshots")) }`, `roborazzi.record.resizeScale=0.5` in `gradle.properties`. Determinism: `NeutrodyneTheme(dynamicColor = false)`, `TestClock`, fixed locale qualifier, `fakeImageLoader`, animations frozen.
- **Tiers** (decides 08 open question 15): `ScreenshotTier.PR` captures every subject in light and dark at font scale 1.0, LTR, plus one stress variant per subject (dark, 2.0, `ar-XB`). `ScreenshotTier.FULL` adds the remaining columns of [08's matrix](08-ui-ux.md#screenshot-matrix) (pure black, 1.5, 2.0 and RTL for every state; all widths and postures). PR CI verifies `PR`; nightly verifies `FULL`. All reference PNGs (both tiers) are committed. Budget: ≤ 800 images, ≤ 30 MB total; exceeding it requires dropping redundant variants, not Git LFS (keeps F-Droid and Weblate clones simple).

```kotlin
enum class ScreenshotTier { PR, FULL;
    companion object { val current = if (System.getProperty("neutrodyne.screenshotTier") == "full") FULL else PR }
}
fun assumeTier(required: ScreenshotTier) = assumeTrue(ScreenshotTier.current >= required)
```

- **Recording policy:** reference images are recorded only on Linux by the `record-screenshots.yml` workflow (font rasterisation differs on macOS and Windows); it uploads `screenshots-<sha>.zip` and the author applies it with `scripts/ci/apply-screenshots.sh <run-id>` (uses `gh run download`) and commits. PRs verify with `-Proborazzi.test.verify=true`; on failure the `_compare.png` files are uploaded as an artifact. Renovate groups Robolectric, Roborazzi and the Compose BOM so the re-record lands in the same PR.
- Pseudo-locales `en-XA` and `ar-XB` come from `isPseudoLocalesEnabled = true` on the `debug` build type; Unverified: Robolectric resolving `@Config(qualifiers = "en-rXA")` to aapt2's generated pseudo-locale resources (M2 check; fallback: capture only `ar` once a real Arabic translation exists, plus manual pseudo-locale review on device).

### Gradle Managed Devices

Configured in `neutrodyne.android.testing` for every Android module (only the five instrumented modules use them).

| Name | Device | API | `systemImageSource` | Groups | Used for |
|---|---|---|---|---|---|
| `api26` | Pixel 2 | 26 | `aosp` | `ci`, `nightly` | minSdk floor (PLAN M0 AC4); migrations; E2E |
| `api33` | Pixel 6 | 33 | `aosp-atd` | `nightly` | `DataSyncWorkerTest` (07) |
| `api34` | Pixel 6 | 34 | `aosp-atd` | `nightly` | first UIDT level; `PlaybackServiceTest` (06) |
| `api36` | Pixel 6 | 36 | `aosp-atd` | `ci`, `nightly` | main device; `UidtDownloadTest` (07); `playDebug` E8 |
| `bench34` (in `:benchmark` only) | Pixel 6 | 34 | `aosp` | — | system tests, baseline-profile generation, Macrobenchmark dry runs |
| API 37 16 KB | — | 37 | `google_apis_ps16k` via android-emulator-runner | nightly job `api37-16k` | Android 17 hardening, 16 KB page size, release smoke (PLAN M11 AC4) |

```kotlin
private fun Project.configureManagedDevices(md: ManagedDevices) = md.apply {
    localDevices {                                      // Unverified: AGP 9.4 may name this allDevices { register<ManagedVirtualDevice>() }
        create("api26") { device = "Pixel 2"; apiLevel = 26; systemImageSource = "aosp" }
        create("api33") { device = "Pixel 6"; apiLevel = 33; systemImageSource = "aosp-atd" }
        create("api34") { device = "Pixel 6"; apiLevel = 34; systemImageSource = "aosp-atd" }
        create("api36") { device = "Pixel 6"; apiLevel = 36; systemImageSource = "aosp-atd" }
    }
    groups {
        create("ci") { targetDevices += listOf(localDevices["api26"], localDevices["api36"]) }
        create("nightly") { targetDevices += listOf("api26", "api33", "api34", "api36").map { localDevices[it] } }
    }
}
```

- ATD images exist for API 31–36 x86_64 (Google's repository XML; the GMD page's "API 30 only" is stale). API 26 uses a full `aosp` image. Unverified: GMD below API 27 may need `android.experimental.testOptions.managedDevices.allowOldApiLevelDevices=true` (M0; fallback: android-emulator-runner for API 26).
- API 37 exists only as `google_apis_ps16k` / `google_apis_playstore_ps16k`; Unverified whether GMD accepts those image sources, hence android-emulator-runner v2.38.0 for that job.
- CI flags: `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`, `-Pandroid.experimental.androidTest.numManagedDeviceShards=2` (a 4-vCPU runner hosts two emulators).
- Release-build runs: `testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")` in `:app` (the AntennaPod pattern), with `testProguardFiles("proguard/test.pro")`; nightly passes `-PtestBuildType=release` so instrumented tests run against the R8-minified APK, where AGP 9's unit tests cannot see R8 breakage. Only when that property is present and no `NEUTRODYNE_KEYSTORE` is set, `:app` signs `release` with the debug signing config so the minified APK can be installed; the release pipeline never passes the property.
- Instrumented hygiene: orchestrator with `clearPackageData`; debug builds disable LeakCanary heap dumps when `ActivityManager.isRunningInUserTestHarness()` or the instrumentation is present; ACRA is off in debug ([ACRA configuration](#acra-configuration)).

### Out-of-process system tests

Instrumented tests run inside the app's process, so killing the process (`am kill`, ProcessDeathResumeTest) or reinstalling the app kills the test. Such tests live in `:benchmark` (`com.android.test`, self-instrumenting, `targetProjectPath = ":app"`, `missingDimensionStrategy("distribution", "foss")`), drive the app with UI Automator 2.4.0 and `UiAutomation.executeShellCommand`, and run on `bench34`. Consequently `:benchmark` is created in **M6** (system tests), and gains Macrobenchmarks and the baseline-profile generator in M10/M11 ([Performance budgets](#performance-budgets)). Compose nodes are found by resource ID, which requires `Modifier.semantics { testTagsAsResourceId = true }` on the root scaffold (request to 08).

### Recorded responses

- No PR job contacts YouTube, Apple, fyyd or Podcast Index. Directory JSON (03), channel pages and oEmbed (04) and NewPipe Extractor traffic (04's `RecordingDownloader`/`ReplayDownloader`) are recorded on a developer machine, scrubbed per [04 Recorded responses](04-youtube.md#recorded-responses) (`ip=`, signatures, cookies, visitor data, `expire`), and committed.
- Recording never runs in CI (GitHub runners use data-centre IPs that YouTube bot-challenges).
- A replay test fails on any unrecorded request; that failure means "re-record", never "add a fallback".

### Fixture policy

- **Licensing.** The repository is Unlicense; fixtures must not carry copyrighted prose, artwork or audio. Real-world feeds and OPML are minimised to structure with `lorem` text and `https://example.invalid/…` URLs; images are generated (08's `ArtworkFixtures`); audio is synthesised with ffmpeg by `scripts/fixtures/make-playback-fixtures.sh` (06) and `make-media-fixtures.sh` (07), whose outputs are committed together with the ffmpeg version used. Each fixture directory has a `README.md` with origin URL and capture date.
- **Size.** One committed fixture ≤ 1 MB (larger inputs, e.g. 03's 831-item feed or 05's 100k-outline OPML, are generated at test time); all committed fixtures ≤ 20 MB.
- **Binaries.** No `.jar`, `.aar`, `.so`, `.dex`, `.class` or `.apk` under any `src/` (F-Droid's source scanner flags them). Unverified whether the scanner flags committed `.zip`/`.gz` fixtures (05's backup ZIPs, 04's `handle_mkbhd.html.gz`); the M11 F-Droid dry run decides, fallback: build those archives at test time from committed directories.

---

## CI pipelines

Serves N11. Delivered in M0 (`ci.yml`, `release.yml`, skeleton `nightly.yml`), extended per milestone. Honours [D60](../PLAN.md#3-key-decisions), [PO-18](../PLAN.md#48-further-product-owner-decisions) (public GitHub repository: free 4-vCPU / 16 GB / 14 GB SSD Linux runners with KVM).

### Workflows

```mermaid
flowchart LR
  pr["pull_request"] --> st["static"]
  pr --> un["unit"]
  pr --> asm["assemble"]
  lab["label run-instrumented"] --> ins["instrumented (GMD ci)"]
  main["push to main"] --> st
  main --> un
  main --> asm
  main --> ins
  cron["nightly.yml 02:17 UTC"] --> nj["instrumented-full, api37-16k, system-tests, repro, bmgr, screenshots-full, mutation-full, canaries"]
  tag["push tag v*"] --> rel["release.yml"]
  rel --> ghr["GitHub Release"]
  ghr --> obt["Obtainium"]
  ghr --> izz["IzzyOnDroid"]
  tag --> fdr["F-Droid rebuilds the tag"]
```

### `ci.yml`

Triggers: `pull_request`, `push` to `main`, `workflow_dispatch`. `permissions: contents: read` at the top; `concurrency: group ci-${{ github.ref }}`, `cancel-in-progress` for PRs only. Every job: `actions/checkout` (`fetch-depth: 0` in `static` for tag comparisons), `actions/setup-java` (Temurin 21), `gradle/actions/setup-gradle` with `cache-provider: basic` (MIT; the default "enhanced" cache is a proprietary component) and `cache-read-only` except on `main`.

| Job | Timeout | Runs | Blocking |
|---|---|---|---|
| `static` | 30 min | `./gradlew spotlessCheck :app:lintFossDebug :app:lintPlayDebug :app:assertModuleGraph :app:licenseeFossRelease :app:licenseePlayRelease :app:verifyDependencyPolicy :app:verifyManifestPermissions checkSpdxHeaders checkBannedApis --continue`; KGP assertion `./gradlew -q :app:buildEnvironment \| grep -E 'kotlin-gradle-plugin:.*2\.4\.20'` (PLAN M0 AC3); `scripts/ci/check-frozen-schemas.sh`; `scripts/ci/check-fastlane.sh`; SARIF upload (`security-events: write`); `./gradlew detekt` with `continue-on-error: true` | yes (detekt no) |
| `unit` | 45 min | `./gradlew test mutationTest -Proborazzi.test.verify=true --continue`; Room schema drift: `test -z "$(git status --porcelain -- core/database/schemas)"` (KSP regenerated the schema while compiling); upload `**/build/reports/tests/` and Roborazzi `_compare.png` on failure; cache `~/.m2/repository/org/robolectric` keyed `robolectric-4.17-sdk36` | yes |
| `assemble` | 30 min | `./gradlew assembleFossDebug assemblePlayDebug assembleFossRelease assemblePlayRelease bundlePlayRelease` (unsigned: no secrets in PR builds); `scripts/ci/check-apk.sh` ([Build-output checks](#build-output-checks)); upload `foss-debug-apk` (14 days) | yes |
| `instrumented` | 75 min | only on `main` pushes, `workflow_dispatch` or PRs labelled `run-instrumented`: free disk, enable KVM, `./gradlew ciGroupDebugAndroidTest ciGroupFossDebugAndroidTest api36PlayDebugAndroidTest -Pneutrodyne.testScope=ci <GMD flags>`; upload `**/build/outputs/androidTest-results/` on failure | required green on `main` (DoD), not a PR merge check |

```yaml
# .github/workflows/ci.yml (excerpt; every uses: is pinned by full commit SHA with the tag in a comment)
  unit:
    runs-on: ubuntu-24.04
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@<sha> # v7.0.1
      - uses: actions/setup-java@<sha> # v6.0.1
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@<sha> # v6.4.0 (also validates the wrapper checksum)
        with: { cache-provider: basic, cache-read-only: "${{ github.ref != 'refs/heads/main' }}" }
      - uses: actions/cache@<sha>
        with: { path: ~/.m2/repository/org/robolectric, key: robolectric-4.17-sdk36 }
      - run: ./gradlew test mutationTest -Proborazzi.test.verify=true --continue
      - name: Room schema drift
        run: test -z "$(git status --porcelain -- core/database/schemas)" || { echo "::error::Room schema changed without a committed version"; git status --porcelain -- core/database/schemas; exit 1; }
      - uses: actions/upload-artifact@<sha> # v7.0.1
        if: failure()
        with: { name: unit-reports, path: "**/build/reports/tests/\n**/build/outputs/roborazzi/" }
```

The instrumented job's preamble (as AntennaPod does): `sudo rm -rf /usr/share/dotnet /usr/local/lib/android/sdk/ndk /opt/ghc /usr/local/.ghcup`, then the udev rule `KERNEL=="kvm", GROUP="kvm", MODE="0666"` with `udevadm trigger`. The GMD system images of the `ci` group are cached (`~/.android/avd/gradle-managed`, key = image list); `nightly` images are downloaded each night.

### `nightly.yml`

`schedule: cron "17 2 * * *"` plus `workflow_dispatch` with input `scope` (`full`, default; `youtube-smoke` = only the release leg of `instrumented-full` on `api36`, filtered to `YouTubeReleaseSmokeTest` and `SmokeTest`, ≈ 12 min, used by the hotfix path). A failing job runs `scripts/ci/report-nightly.sh <job>`, which opens or updates one issue per job (label `nightly-failure`, `permissions: issues: write`) and closes it after the next green run.

| Job | From | Runs | Blocks a release? |
|---|---|---|---|
| `instrumented-full` | M0 | two matrix legs with `-Pneutrodyne.testScope=nightly`: **debug** `./gradlew nightlyGroupDebugAndroidTest nightlyGroupFossDebugAndroidTest api36PlayDebugAndroidTest`; **release** `./gradlew -PtestBuildType=release nightlyGroupFossReleaseAndroidTest` (minified `:app` suite; library modules have no release test variant) | yes (red nightly ⇒ no tag) |
| `api37-16k` | M0 | android-emulator-runner (`api-level: 37`, `target: google_apis_ps16k`, `arch: x86_64`; Unverified target name): assert `getconf PAGE_SIZE` = 16384, install minified `fossRelease`, run E0 and 06's hardening `throw` test, `zipalign -c -P 16 -v 4` on the APK (PLAN M11 AC4) | yes |
| `system-tests` | M6 | `:benchmark` system tests on `bench34` (E10) | yes |
| `benchmark-dryrun` | M10 | Macrobenchmark journeys with `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dryRunMode.enable=true` (catches broken journeys; timings are meaningless on emulators) | no |
| `repro` | M0 (report-only), M11 (blocking) | [two builds and diff](#nightly-reproducibility-job) | yes from M11 |
| `bmgr` | M3 | android-emulator-runner API 29 (`backup_rules.xml` path) and API 36 (`data_extraction_rules.xml`), image `default`: `scripts/ci/bmgr-check.sh` (05's procedure plus 07's "no `Podcasts/`" assertion). Needs uninstall/reinstall, which an in-process instrumented test cannot do | yes |
| `screenshots-full` | M10 | `./gradlew test --tests '*Screenshot*' -PscreenshotTier=full -Proborazzi.test.verify=true` | yes |
| `mutation-full` | M1 | `./gradlew mutationTest -PmutationIterations=1000` | yes |
| `live-canary` | when 03 ships `feeds/canary/feeds.txt` (M11 at the latest) | `./gradlew :feeds:liveCanary` | no |
| `youtube-canary` | M9 (04) | 04's subscribe-resolve-chunk smoke | no |
| `emergency-patch-check` | M9 (04) | `git apply scripts/emergency/no-youtube-streams.patch && ./gradlew assembleFossRelease` | yes |

### `release.yml`

Trigger: `push: tags: ['v*']`. One job `release` in GitHub environment `release` (required reviewer = a maintainer; secrets live only there); `permissions: contents: write`; `setup-gradle` with `cache-disabled: true` (no cache-poisoning surface for signed builds). The build runs inside the same pinned Debian container and script as the [repro job](#nightly-reproducibility-job), so GitHub's APK, F-Droid's rebuild and the nightly check share one toolchain.

```mermaid
sequenceDiagram
  participant M as Maintainer
  participant G as GitHub
  participant R as release.yml
  participant E as Environment release
  participant C as Debian container
  M->>M: scripts/release.sh patch
  M->>G: push release commit and tag vX.Y.Z
  G->>R: tag event
  R->>E: wait for required reviewer
  M->>E: approve
  R->>R: verify-tag.sh preconditions
  R->>C: repro-build.sh assembleFossRelease with app-signing key
  R->>C: repro-build.sh bundlePlayRelease with upload key (only if Play enabled)
  C-->>R: signed APK, AAB, mapping
  R->>R: apksigner certificate check, zipalign 16 KB
  R->>G: create release with APK, mapping, SHA256SUMS, source bundle, notes
  R->>R: verify-repro job rebuilds unsigned and runs apksigcopier compare
  G-->>M: Obtainium and IzzyOnDroid pick up the release
```

Steps, in order (target: tag → published release in < 30 min, N11 and PLAN M11 AC3):

1. `scripts/ci/verify-tag.sh`: tag equals `v` + `neutrodyne.versionName`; the tagged commit is on `main`; `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` exists; `ci.yml` concluded `success` for the commit (`gh api …/check-runs`).
2. Decode `NEUTRODYNE_KEYSTORE_B64` to `$RUNNER_TEMP/release.p12`; build `assembleFossRelease` with `NEUTRODYNE_KEYSTORE*` set ([Gradle signing](#gradle-signing-configuration)).
3. `apksigner verify --print-certs --min-sdk-version 26` must print `SHA-256 digest: ${{ vars.NEUTRODYNE_CERT_SHA256 }}`; `zipalign -c -P 16 -v 4`. (The `play` dex and size checks already passed in `assemble` for this commit, which `verify-tag.sh` requires.)
4. From M9: `scripts/release/corresponding-source.sh` — `git archive` of the tag plus `third_party/` filled by `./gradlew :youtube:streams:collectGplSources` (a `Copy` task resolving the `-sources` artifacts of NewPipe Extractor, nanojson and Rhino) → `neutrodyne-{v}-foss-corresponding-source.tar.gz` ([04 Corresponding source](04-youtube.md#corresponding-source)).
5. Stage `neutrodyne-{v}-foss.apk`, `neutrodyne-{v}-foss-mapping.txt`, the source bundle and `SHA256SUMS`; body from the changelog file plus the certificate fingerprint and, from M9, the corresponding-source line.
6. `softprops/action-gh-release` (v3.0.3, SHA-pinned): `prerelease: ${{ contains(github.ref_name, '-') }}`, `make_latest` only for stable tags.
7. If repository variable `PLAY_PUBLISHING == 'true'` (after PO-2): re-run the container build for `bundlePlayRelease` with `NEUTRODYNE_KEYSTORE*` pointing at the **upload** key, then `./gradlew publishPlayReleaseBundle --track internal` (Gradle Play Publisher 4.1.1, service-account secret). Promotion beyond internal is manual in the Play Console.
8. Separate job `verify-repro` (needs `release`, non-blocking for the hotfix clock): unsigned rebuild in a fresh container at a different path, then `apksigcopier compare neutrodyne-{v}-foss.apk --unsigned rebuilt.apk`; failure opens an issue (F-Droid would reject the binary).

### Helper workflows

| Workflow | Trigger | Does |
|---|---|---|
| `record-screenshots.yml` | `workflow_dispatch` (input: branch, tier) | `./gradlew recordRoborazziDebug recordRoborazziFossDebug -PscreenshotTier=…` on `ubuntu-24.04`; uploads `screenshots-<sha>.zip` |
| `baseline-profile.yml` | `workflow_dispatch` (before each minor release, from M11) | `./gradlew :app:generateFossReleaseBaselineProfile` on GMD `bench34`; uploads the generated `baseline-prof.txt`/`startup-prof.txt` for the author to commit |

Pushing commits from workflows is deliberately avoided: pushes made with `GITHUB_TOKEN` do not trigger new workflow runs, so a bot commit would leave the PR without required checks.

### CI scripts

| Script | Purpose |
|---|---|
| `scripts/ci/check-apk.sh` | size budgets, 16 KB alignment, `play` dex check ([Build-output checks](#build-output-checks)) |
| `scripts/ci/check-play-dex.sh <apk>` | `dexdump` (build-tools 36.0.0) class descriptors: fail on `Lorg/schabi/newpipe/`, `Lorg/mozilla/javascript/`, `Lapp/neutrodyne/youtube/streams/`; print the count of `Lj$/` classes (01's M9 desugaring check) |
| `scripts/ci/check-frozen-schemas.sh` | for every `N.json` that exists at the newest `v*` tag, `git diff --exit-code <tag> -- <file>` ([02 Schema export and versioning](02-data-model.md#schema-export-and-versioning)) |
| `scripts/ci/check-fastlane.sh` | metadata limits ([Store metadata](#store-metadata)); banned words in `play` release notes |
| `scripts/ci/repro-build.sh <task> <path> <cpus>` | container build used by `repro` and `release.yml` |
| `scripts/ci/install-android-sdk.sh` | pinned cmdline-tools (version + SHA-256), `platforms;android-37`, `build-tools;36.0.0` |
| `scripts/ci/bmgr-check.sh <package>` | 05's `bmgr` procedure and assertions |
| `scripts/ci/report-nightly.sh <job>` | issue per failing nightly job |
| `scripts/ci/apply-screenshots.sh <run-id>` | download and unpack recorded screenshots locally |
| `scripts/ci/verify-tag.sh` | release preconditions |

### Hardening

- Every `uses:` is pinned by commit SHA; Renovate's `helpers:pinGitHubActionDigests` keeps the pins current.
- Never `pull_request_target`; fork PRs get no secrets (PR builds are unsigned by design).
- Repository rulesets: `main` requires a PR and the checks `static`, `unit`, `assemble`; linear history; no force pushes; maintainers may bypass only to push the release commit created by `release.sh`. Tag ruleset: only maintainers create or delete `v*` tags.
- GitHub secret scanning with push protection on; private vulnerability reporting on ([SECURITY.md](#security-reporting)).

| Secret / variable | Scope | Used by |
|---|---|---|
| `NEUTRODYNE_KEYSTORE_B64`, `NEUTRODYNE_KEYSTORE_PASSWORD`, `NEUTRODYNE_KEY_ALIAS`, `NEUTRODYNE_KEY_PASSWORD` | environment `release` | `foss` APK signing |
| `NEUTRODYNE_UPLOAD_KEYSTORE_B64`, `NEUTRODYNE_UPLOAD_KEYSTORE_PASSWORD`, `NEUTRODYNE_UPLOAD_KEY_ALIAS`, `NEUTRODYNE_UPLOAD_KEY_PASSWORD` | environment `release` | `play` AAB (mapped onto `NEUTRODYNE_KEYSTORE*` for that Gradle invocation); only after PO-2 |
| `PLAY_SERVICE_ACCOUNT_JSON` | environment `release` | Gradle Play Publisher; only after PO-2 |
| `NEUTRODYNE_CERT_SHA256`, `PLAY_PUBLISHING` | repository variables | signer check; Play step switch |

No other secret exists. In particular no API key is injected into any `foss` build ([Reproducible builds](#reproducible-builds)).

### Time budgets

| Pipeline | Target | Hard timeout |
|---|---|---|
| PR checks (`static`, `unit`, `assemble` in parallel) | ≤ 15 min wall clock (PLAN M0 AC1) | 30 / 45 / 30 min |
| `instrumented` on `main` | ≤ 45 min | 75 min |
| Tag → GitHub release | ≤ 30 min incl. the environment approval (N11) | 45 min |
| Nightly | ≤ 3 h total | per job |

When `unit` exceeds 15 min at p50 over a week, the fix order is: raise `maxParallelForks` only if memory allows, move slow Robolectric suites into a second `unit-2` job (split by module list), then move `FULL`-only screenshot variants out of `PR`.

---

## Static analysis

Serves N8, N10, N11. Delivered in M0. Honours [D60](../PLAN.md#3-key-decisions).

### Gates

| Gate | Blocking | Where | Configuration |
|---|---|---|---|
| Android Lint | yes | `static` (`:app:lintFossDebug`, `:app:lintPlayDebug` with `checkDependencies`) | [below](#android-lint) |
| Spotless + ktlint 1.8.0 + compose-rules 0.6.7 | yes | `static` | [below](#formatting) |
| detekt 2.0.0-alpha.6 | no (SARIF only) | `static` | `config/detekt/detekt.yml` |
| Licensee, module graph, `verifyDependencyPolicy`, `verifyManifestPermissions`, `checkSpdxHeaders`, `checkBannedApis` | yes | `static` | [01 Gradle-side policy tasks](01-foundation.md#gradle-side-policy-tasks) |
| KGP version assertion | yes | `static` | [01 S1](01-foundation.md#s1-kgp-2420-under-agp-941) |
| Room schema drift and frozen versions | yes | `unit`, `static` | [CI scripts](#ci-scripts) |
| APK size, 16 KB alignment, `play` dex | yes | `assemble`, `release.yml` | [Build-output checks](#build-output-checks) |
| `play` wording (`PlayStringsPolicyTest`, release-notes scan) | yes | `unit`, `static` | [08](08-ui-ux.md#unit-tests-jvm), [Store metadata](#store-metadata) |
| Accessibility checks | yes | `unit` (Robolectric), `instrumented` | [08 Automated checks](08-ui-ux.md#automated-checks) |

### Android Lint

Configured by `neutrodyne.android.lint` (hook owned by 01):

```kotlin
lint {
    warningsAsErrors = true
    abortOnError = true
    checkDependencies = true                  // :app only: one report covering every module, JVM modules via com.android.lint
    sarifReport = true
    baseline = file("lint-baseline.xml")      // :app only
    disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion",  // Renovate's job; offline/F-Droid safe
                     "MissingTranslation")                                                   // partial Weblate languages by design
    fatal += setOf("StringFormatInvalid", "StringFormatMatches", "MissingQuantity", "UnusedResources", "ExtraTranslation")
    enable += setOf("StopShip")
}
```

**Baseline policy:** `app/lint-baseline.xml` is created empty in M0. Milestone work never adds entries (PLAN DoD). The only allowed additions are new check IDs introduced by an AGP/Lint bump, added in the Renovate PR together with an issue to burn them down before the next minor release. `@Suppress`/`tools:ignore` require a comment naming the reason.

### Formatting

Spotless (root plugin `neutrodyne.quality`): `kotlin { target("**/*.kt"); targetExclude("**/build/**"); ktlint("1.8.0").customRuleSets(listOf("io.nlopez.compose.rules:ktlint:0.6.7")) }`, `kotlinGradle { target("**/*.kts"); ktlint("1.8.0") }`. Rules come from `.editorconfig`:

```ini
root = true
[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 4
[*.{kt,kts}]
max_line_length = 120
ktlint_code_style = ktlint_official
ktlint_function_naming_ignore_when_annotated_with = Composable
compose_allowed_composition_locals = LocalAppNavigator,LocalNavTab,LocalPaneLayout,LocalMiniPlayerInset,LocalReducedMotion,LocalSnackbarHost,LocalArtworkTintEnabled
compose_disallow_material2 = true
[*.{xml,yml,yaml,json,toml}]
indent_size = 2
[*.md]
trim_trailing_whitespace = false
```

A new `CompositionLocal` requires adding its name here in the same PR (review point). Unverified: the exact compose-rules 0.6.7 key names (`compose_allowed_composition_locals`, `compose_disallow_material2`), checked in M0.

### detekt

`buildUponDefaultConfig = true`, `parallel = true`, baseline `config/detekt/baseline.xml`, SARIF uploaded with category `detekt`. Tuned rules: `CyclomaticComplexMethod` threshold 15, `LongMethod` 80 lines (ignore `@Composable`), `MagicNumber` off in tests and Compose files, `ForbiddenComment` for `TODO` without an issue link, `TooGenericExceptionCaught` on (reinforces 01's `suspendRunCatching` rule). Becomes blocking when a stable detekt release supports Kotlin 2.4 and AGP 9 (1.23.8 stops at Kotlin 2.0.21 / AGP 8.8.1); Renovate must not "downgrade to stable".

### Build-output checks

`scripts/ci/check-apk.sh` runs on every `assemble` and in `release.yml`:

1. **Size:** universal `fossRelease` APK < 25 MB (N5) and `playRelease` APK < 25 MB, both blocking; sizes printed to the job summary and kept as a nightly artifact for trends.
2. **16 KB:** `zipalign -c -P 16 -v 4` on both release APKs (only native code is `sqlite-bundled`, [01 S6](01-foundation.md#s6-sqlite-bundled-16-kb-alignment-and-size)); required for Play updates of apps with native code ([16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)).
3. **`play` dex:** `check-play-dex.sh` on `playRelease` ([04 GPL boundary](04-youtube.md#gpl-boundary), PLAN M9 AC4).
4. **Metadata hygiene:** `unzip -l` on the release APK shows no `META-INF/version-control-info.textproto` ([vcsInfo off](#hygiene)). The dependency-info signing block (which F-Droid rejects; 01 disables it) only exists in signed APKs, so it is caught by `release.yml`'s `verify-repro` step, whose `apksigcopier compare` fails on extra signing blocks (Unverified: exact apksigcopier behaviour; F-Droid reported this failure as "Found extra signing block").

### PR template

`.github/PULL_REQUEST_TEMPLATE.md` checklist (each line is a checkbox):

- Tests per [Test obligations per change](#test-obligations-per-change); bug fixes include a failing-first regression test.
- UI changed → screenshots re-recorded with `record-screenshots.yml`; accessibility checks pass.
- Strings externalised, plurals used, no concatenation; `play`-specific wording rules ([08 Flavor differences in UI](08-ui-ux.md#flavor-differences-in-ui)).
- Schema change → version bump, migration and test.
- New setting → classified `settings`/`device_settings` and registered (01).
- Both flavors checked against [04 Flavor matrix](04-youtube.md#flavor-matrix).
- No MockK in `androidTest`.
- 01's copied-code rule (verbatim from [01](01-foundation.md#copied-code-and-contributions)).
- Design document updated if behaviour deviates (PLAN DoD).
- `youtube-hotfix` PRs: verification metadata regenerated and Licensee `allowDependency` bumped (`scripts/youtube/bump-extractor.sh`), `:youtube:streams:test` green, device check done (04).

Issue templates: `bug.yml` (issue form with a `diagnostics` textarea that [Report a problem](#copy-report-and-export) pre-fills), `feature.yml`, `release.md` (the [Release checklist](#release-checklist) as checkboxes).

---

## Dependency updates

Serves N11; mitigates risks T2, M1r, M3r. Delivered in M0 (Renovate app installed on the repository), fast lane active in M9.

### Renovate configuration

Renovate (Mend-hosted GitHub app) reads `gradle/libs.versions.toml`, the wrapper, `build-logic` and workflow files. Dependabot stays disabled.

```json
{
  "$schema": "https://docs.renovatebot.com/renovate-schema.json",
  "extends": ["config:recommended", "helpers:pinGitHubActionDigests", ":dependencyDashboard"],
  "timezone": "UTC",
  "schedule": ["before 6am on monday"],
  "prConcurrentLimit": 5,
  "prHourlyLimit": 2,
  "minimumReleaseAge": "3 days",
  "labels": ["dependencies"],
  "vulnerabilityAlerts": { "schedule": ["at any time"], "minimumReleaseAge": "0 days", "labels": ["security"] },
  "packageRules": [
    { "groupName": "Kotlin toolchain", "matchPackageNames": ["/^org\\.jetbrains\\.kotlin[.:]/", "/^com\\.google\\.devtools\\.ksp/"] },
    { "groupName": "AGP and Lint", "matchPackageNames": ["/^com\\.android\\.tools/", "/^com\\.android\\.(application|library|test|lint)$/"] },
    { "groupName": "Screenshot stack", "matchPackageNames": ["/^org\\.robolectric:/", "/^io\\.github\\.takahirom\\.roborazzi/", "androidx.compose:compose-bom"] },
    { "groupName": "Media3", "matchPackageNames": ["/^androidx\\.media3:/"] },
    { "groupName": "Hilt", "matchPackageNames": ["/^com\\.google\\.dagger[.:]/", "/^androidx\\.hilt:/"] },
    { "groupName": "Room and SQLite", "matchPackageNames": ["/^androidx\\.room3[.:]/", "/^androidx\\.sqlite:/"] },
    { "groupName": "AndroidX Test and benchmark", "matchPackageNames": ["/^androidx\\.test/", "/^androidx\\.benchmark/", "/^androidx\\.baselineprofile/"] },
    { "description": "D4: toolchain minors need a PLAN amendment", "matchPackageNames": ["/^org\\.jetbrains\\.kotlin[.:]/", "/^com\\.android\\.tools\\.build:gradle$/", "/^com\\.android\\.(application|library|test)$/"], "matchUpdateTypes": ["minor", "major"], "dependencyDashboardApproval": true },
    { "description": "D4: stay on Gradle 9.7.x until Kotlin's tested matrix includes 9.8", "matchManagers": ["gradle-wrapper"], "allowedVersions": "<9.8.0" },
    { "description": "Rhino is pinned strictly to the extractor-tested 1.8.1 (01)", "matchPackageNames": ["/^org\\.mozilla:rhino/"], "enabled": false },
    { "description": "detekt has no stable release for this toolchain", "matchPackageNames": ["/^dev\\.detekt/"], "ignoreUnstable": false },
    { "description": "YouTube breakages need same-day updates (04)", "matchPackageNames": ["com.github.teamnewpipe:NewPipeExtractor"], "schedule": ["at any time"], "minimumReleaseAge": "0 days", "prPriority": 10, "labels": ["youtube-hotfix"] },
    { "matchUpdateTypes": ["major"], "dependencyDashboardApproval": true },
    { "matchPackageNames": ["junit:junit", "com.google.truth:truth", "app.cash.turbine:turbine", "io.mockk:mockk", "com.google.testparameterinjector:test-parameter-injector"], "matchUpdateTypes": ["patch"], "automerge": true },
    { "matchManagers": ["github-actions"], "groupName": "GitHub Actions", "schedule": ["before 6am on the first day of the month"] }
  ]
}
```

### Review rules per group

| Group | What the reviewer checks besides green CI |
|---|---|
| Kotlin toolchain | KGP assertion updated to the new version; Kotlin's Gradle/AGP tested matrix ([compatibility](https://kotlinlang.org/docs/gradle-configure-project.html)); clean CI caches (KSP/Hilt incremental quirks) |
| AGP and Lint | new Lint checks (baseline rule above); R8 behaviour changes (minified nightly run before merge: dispatch `nightly.yml`); `compileSdk` coupling with the Compose BOM |
| Screenshot stack | the PR includes the full re-record (`FULL` tier) |
| Media3 | release notes read for `@UnstableApi` changes; dispatch the nightly instrumented job on the branch (risk M3r) |
| Room and SQLite | migration tests on both drivers (dispatch instrumented); 16 KB alignment output |
| NewPipe Extractor | 04's fast-lane checklist; `scripts/youtube/bump-extractor.sh <version or commit>` updates the catalog, regenerates `gradle/verification-metadata.xml` for the JitPack groups (`./gradlew --write-verification-metadata sha256 :app:assembleFossRelease`) and bumps the Licensee `allowDependency` line (01); the maintainer runs it on the Renovate branch and pushes |
| GitHub Actions | release notes for breaking input changes; SHA pins updated |

**Gradle wrapper:** Renovate's `gradle-wrapper` manager updates `gradle-wrapper.properties`; Unverified whether the hosted app also regenerates `gradle-wrapper.jar` and `distributionSha256Sum` (self-hosted needs `allowedUnsafeExecutions`). If it does not, the maintainer runs `./gradlew wrapper --gradle-version X --gradle-distribution-sha256-sum <sum>` on the PR branch. Renovate ≥ 44.14.7 fixed a command injection through the wrapper (CVE-2026-88886); the hosted app is current.

---

## Versioning and signing

Serves N11, N8; mitigates risk M4r. Delivered in M0 (scheme, `release.sh`, key ceremony, first signed pre-release). Honours [D61](../PLAN.md#3-key-decisions), [D63](../PLAN.md#3-key-decisions), [PO-8](../PLAN.md#48-further-product-owner-decisions) default.

### Version scheme

Single source: `gradle.properties` keys `neutrodyne.versionName` and `neutrodyne.versionCode` ([01](01-foundation.md#settingsgradlekts-gradleproperties-root-build)); Gradle never reads git or the clock.

`versionCode = MAJOR·1 000 000 + MINOR·10 000 + PATCH·100 + S`, where S is:

| `versionName` suffix | S | Example |
|---|---|---|
| `-beta.N` (N = 1…79) | N | `0.1.0-beta.1` → 10001 |
| `-rc.N` (N = 1…15) | 79 + N | `1.0.0-rc.2` → 1000081 |
| none (stable) | 95 | `1.0.0` → 1000095, `1.2.3` → 1020395 |
| 96–99 | reserved (never used) | |

Rules: MINOR and PATCH ≤ 99. A version code is never reused, even for a failed release (F-Droid and Play remember them). A hotfix after `1.2.3` is `1.2.4`, never a rebuild. Pre-1.0 tester builds are `0.{n+1}.0-beta.N` for milestone Mn (M0 → `0.1.0`, …, M10 → `0.11.0`); M11 ships `1.0.0-beta.N`, `1.0.0-rc.N`, then `1.0.0`. After 1.0: MINOR for feature releases, PATCH for fixes and YouTube hotfixes; MAJOR only by PO decision.

```mermaid
stateDiagram-v2
  [*] --> Beta: release.sh minor --beta
  Beta --> Beta: release.sh --beta (N+1)
  Beta --> RC: release.sh --rc
  RC --> RC: release.sh --rc (N+1)
  RC --> Stable: release.sh (finalise)
  Stable --> Patch: release.sh patch
  Patch --> Stable
  Stable --> Beta: release.sh minor --beta
```

### `scripts/release.sh`

Usage: `scripts/release.sh <patch|minor|major|X.Y.Z|finalise> [--beta|--rc] [--hotfix] [--dry-run]`. Algorithm:

1. Preconditions: on `main`, clean tree, `HEAD` equals `origin/main`, `ci.yml` concluded `success` for `HEAD` (`gh run list --commit`), no open issue labelled `release-blocker`, and either the latest scheduled `nightly.yml` run is green or (with `--hotfix`, PATCH only) a `nightly.yml` run dispatched with `scope: youtube-smoke` is green for `HEAD`.
2. Compute the next `versionName` from the current one and the argument (`finalise` drops the pre-release suffix; `--beta` on a current `-beta.N` of the same X.Y.Z increments N). Compute `versionCode` per the table; refuse if it is ≤ the current code or if any existing tag has the same name.
3. Require `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: non-empty, ≤ 500 characters. If `PLAY_PUBLISHING` is configured locally (`.release.env`), also require `app/src/play/play/release-notes/en-US/default.txt` updated in this release and free of the banned `play` words.
4. Rewrite the two `gradle.properties` lines; commit `Release vX.Y.Z`; create an annotated (and, when the maintainer has signing configured, signed) tag `vX.Y.Z`.
5. `git push --atomic origin main vX.Y.Z` (ruleset bypass for maintainers). `release.yml` takes over.

### Changelogs and release notes

- `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: user-facing plain text, English, ≤ 500 characters; consumed by F-Droid, IzzyOnDroid and the GitHub release body. Not translated (excluded from Weblate).
- `play` release notes (only with PO-2): `app/src/play/play/release-notes/en-US/default.txt`, written separately because `foss` notes may mention YouTube audio and downloads, which the `play` listing must not ([04 Play guardrails](04-youtube.md#play-guardrails)).
- Pre-release notes start with "Tester build for milestone Mn." and link the milestone's acceptance checklist issue.

### Key ceremony and custody

One RSA-4096 app-signing key for every channel ([D61](../PLAN.md#3-key-decisions)), created **in M0, before the first signed pre-release**: tester installs signed with a temporary key could not update to 1.0 without uninstalling. PO-8 default: held by the maintainer, offline encrypted backup held by a second person.

1. On an offline machine (live USB, no network), with JDK 21 `keytool`:
   `keytool -genkeypair -v -storetype PKCS12 -keystore neutrodyne-release.p12 -alias neutrodyne -keyalg RSA -keysize 4096 -validity 12000 -dname "CN=Neutrodyne"` (12,000 days ≈ 33 years; long-standing Play guidance wants validity beyond 2033-10-22, not re-verified).
2. Store and key passwords: two independent 7-word diceware passphrases in both holders' password managers.
3. Two encrypted copies (symmetric `age` or `gpg`, a third passphrase) on two offline media, one per holder; the passphrases on paper stored separately from the media.
4. Record the certificate SHA-256 (`keytool -list -v`); publish it in `README.md`, every GitHub release body, F-Droid's `AllowedAPKSigningKeys` (lowercase hex, no colons) and the developer-verification console; store it as repository variable `NEUTRODYNE_CERT_SHA256`.
5. Put a base64 copy into the `release` environment secrets (accepted risk for the < 30-min hotfix path; mitigated by environment approval, tag ruleset, SHA-pinned actions and a cache-less build).
6. Yearly (calendar issue): both holders decrypt their backup copy and verify the fingerprint.

APK signature schemes: v1 off (minSdk 26 ≥ 24), v2 and v3 on; v3 keeps key rotation (proof-of-rotation lineage) possible if the key is ever compromised.

### Gradle signing configuration

Read only from `NEUTRODYNE_KEYSTORE*` environment variables; unsigned when absent (PR builds, F-Droid). Lives in `:app` (01's build-flavors sketch leaves this to 09):

```kotlin
android {
    signingConfigs {
        create("release") {
            providers.environmentVariable("NEUTRODYNE_KEYSTORE").orNull?.let { path ->
                storeFile = file(path); storeType = "pkcs12"
                storePassword = providers.environmentVariable("NEUTRODYNE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("NEUTRODYNE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("NEUTRODYNE_KEY_PASSWORD").get()
                enableV1Signing = false; enableV2Signing = true; enableV3Signing = true
            }
        }
    }
    buildTypes.getByName("release") {
        if (providers.environmentVariable("NEUTRODYNE_KEYSTORE").isPresent) signingConfig = signingConfigs.getByName("release")
    }
}
```

### Play App Signing

Only after PO-2 approves Play. New Play apps default to Google-generated keys; to keep one key across channels, choose **"Use a different key → Export and upload a key from Java keystore"** (PEPK) in Play Console → App integrity **before the first release rolls out** ([Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)); this cannot be undone cheaply. Run the PEPK tool on the offline machine against `neutrodyne-release.p12`; Unverified: exact PEPK flags in 2026 (follow the console's generated command). Create a separate RSA-4096 **upload key** (`neutrodyne-upload.p12`, resettable through Play support) and register its certificate. `release.yml` signs the AAB with the upload key; Play re-signs with the app-signing key, so Play installs and GitHub installs carry the same certificate.

### Key loss or compromise

| Event | Consequence | Response |
|---|---|---|
| One copy lost | none | re-create a second copy from the other holder's backup |
| All copies lost | `foss` users cannot update; must uninstall and restore from a backup ZIP (R1.7) | new key + new release under the same `applicationId`; publish a migration notice; register the new key for developer verification; F-Droid metadata update; Play continues with the Play-held app-signing key |
| Compromise suspected | attacker could ship updates to sideload users | v3 key rotation to a new key in the next release; rotate CI secrets; announce; update `AllowedAPKSigningKeys` and developer-verification registration |

---

## Distribution channels

Serves N8; mitigates risks P1, P2, P3. Delivered in M0 (GitHub pre-releases), M11 (stores). Honours [D2](../PLAN.md#3-key-decisions), [D3](../PLAN.md#3-key-decisions), [D61](../PLAN.md#3-key-decisions), [PO-2](../PLAN.md#po-2-distribution-channels-and-youtube-per-flavor) default A.

| Channel | Artefact | Signing | Latency | Betas | From |
|---|---|---|---|---|---|
| GitHub Releases (+ Obtainium) | `neutrodyne-{v}-foss.apk` | our key | minutes | GitHub pre-releases | M0 |
| IzzyOnDroid | the same APK, fetched from GitHub | our key | hours to days | no | M11 |
| F-Droid main | F-Droid rebuilds `foss` from the tag; ships our APK when bit-identical (`Binaries:`) | our key (fallback: F-Droid's) | days | no | M11 |
| Google Play | `playRelease` AAB | upload key → Play re-signs with our key (PEPK) | hours to days (review) | internal → closed testing | when PO-2 approves |

No build self-updates or links to another build's APK; the `play` build never mentions the `foss` build ([04 Play guardrails](04-youtube.md#play-guardrails)).

### GitHub Releases and Obtainium

- Assets per tag: `neutrodyne-{v}-foss.apk`, `neutrodyne-{v}-foss-mapping.txt` (`-dontobfuscate` keeps traces readable; the mapping still fixes line numbers after inlining), `neutrodyne-{v}-foss-corresponding-source.tar.gz` (from M9), `SHA256SUMS`. The `play` AAB is never attached.
- One universal APK (no ABI splits): F-Droid needs no `VercodeOperation`, Obtainium needs no ABI choice.
- README badge: `https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/<owner>/Neutrodyne`; README states the APK filter regex `neutrodyne-.*-foss\.apk$` and that testers enable "include pre-releases".

### IzzyOnDroid

Inclusion request at M11 after `v1.0.0` (FOSS licence, APK attached to GitHub releases, updated within 12 months, screened for trackers and proprietary libraries — none). IzzyOnDroid reads `fastlane/metadata/android/` from the repository and also checks reproducibility. Stable releases only. Unverified: current per-APK size limit (our budget of 25 MB is expected to fit).

### F-Droid

Merge request to `fdroiddata` at M11 with the tag `v1.0.0`; the draft lives in `fdroid/app.neutrodyne.yml` and is updated with every release that changes build requirements.

```yaml
Categories:
  - Podcast
  - Multimedia
License: GPL-3.0-or-later          # binary licence of foss from M9 (source: Unlicense + GPL module), PO-1 default A
SourceCode: https://github.com/OWNER/Neutrodyne
IssueTracker: https://github.com/OWNER/Neutrodyne/issues
Translation: https://hosted.weblate.org/engage/neutrodyne/
Changelog: https://github.com/OWNER/Neutrodyne/releases
AntiFeatures:
  NonFreeNet:
    en-US: Subscribing to and playing YouTube channels depends on YouTube, a proprietary network service.   # 04's text
AutoName: Neutrodyne
RepoType: git
Repo: https://github.com/OWNER/Neutrodyne.git
Binaries: https://github.com/OWNER/Neutrodyne/releases/download/v%v/neutrodyne-%v-foss.apk
Builds:
  - versionName: 1.0.0
    versionCode: 1000095
    commit: v1.0.0
    subdir: app
    sudo:
      - apt-get update
      - apt-get install -y openjdk-21-jdk-headless
      - update-alternatives --auto java
    gradle:
      - foss
AllowedAPKSigningKeys: <sha256 of our certificate, lowercase, no colons>
AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+$     # stable tags only; betas are never picked up
UpdateCheckData: gradle.properties|neutrodyne.versionCode=(\d+)|.|neutrodyne.versionName=(.*)
CurrentVersion: 1.0.0
CurrentVersionCode: 1000095
```

- Requirements met by design: FLOSS toolchain and dependencies (Maven Central, Google Maven, JitPack allowed), no Play services/Firebase/Crashlytics in `foss` (01's ban), `dependenciesInfo` off (01), no prebuild patches needed (any patch would break the binary match), no git metadata read by Gradle (01), JDK 21 matching the [release container](#nightly-reproducibility-job).
- `NonFreeNet` is declared pre-emptively (undisclosed anti-features are a rejection reason); F-Droid maintainers decide. NewPipe carries it; AntennaPod (directory search only) carries none.
- M11 dry run: build the recipe locally with fdroidserver (`fdroid build -v -l app.neutrodyne` in fdroidserver's container) before opening the MR. Unverified: the current fdroidserver image name and local-build flags.

### Google Play

Only when PO-2 approves (default: built and tested in CI from M0, published later). Checklist for the first submission:

| Item | Content |
|---|---|
| Build | `bundlePlayRelease` signed with the upload key; target API 37 meets Play's API 36 requirement for new apps and updates from 2026-08-31 ([target API](https://developer.android.com/google/play/requirements/target-sdk)) |
| App signing | PEPK upload of our key before the first release ([Play App Signing](#play-app-signing)) |
| Listing | title "Neutrodyne"; short description ≤ 80; full ≤ 4,000; icon 512 px; feature graphic 1024×500; phone and tablet screenshots taken from the `play` build with synthetic demo content (no third-party podcast artwork); YouTube described only as "follow YouTube channels and open their videos in YouTube"; no download, background or audio-only wording for YouTube; no mention of other builds or stores |
| FGS declarations | `mediaPlayback` (draft below) and `dataSync` ([07's draft](07-downloads.md#manifest-and-play-declaration)), each with description, user impact and demo video ([FGS declarations](https://support.google.com/googleplay/android-developer/answer/13392821)) |
| Data safety | [answers below](#play-data-safety) |
| Content rating | IARC questionnaire: no generated content, third-party audio content unfiltered |
| Target audience | 18+ (unfiltered third-party podcasts; keeps the app outside the Families policy) — PO to confirm |
| Ads, app access, news | no ads; no login; not a news publisher |
| Privacy policy URL | `https://github.com/OWNER/Neutrodyne/blob/main/PRIVACY.md` |
| Tracks | internal (each release, automated) → closed testing (manual) → production staged rollout 10 % → 50 % → 100 % over ≥ 7 days; halt when Android vitals crash or ANR rates exceed the bad-behaviour thresholds |
| Cross-grade | before the first production rollout: install the Play build, then the GitHub `foss` APK over it and back; record whether Android 14+ "update ownership" prompts appear (PO-8 Unverified) |

**`mediaPlayback` declaration draft** (06 deferred it to 09): *Description:* "Neutrodyne is a podcast player. When the user starts an episode, a media-playback foreground service keeps audio playing with the screen off and shows the media notification with play, pause and skip controls. The service runs only while an episode is playing or paused from the notification." *User impact if not allowed:* "Podcast playback would stop as soon as the user leaves the app or turns off the screen." *Demo video:* start an episode, press Home, lock the screen, show playback continuing and the lock-screen controls, pause and resume from the notification.

The `play` About screen links to the repository root only, never to releases or APKs (answers 01 open question 9). Open-source Play apps commonly link their source; Unverified as policy text, low risk; fallback: `BuildInfo.repoUrl` empty in `play`, hiding the link.

### Beta channels

GitHub pre-releases carry every milestone's tester build (`vX.Y.Z-beta.N`, PLAN DoD); Obtainium users opt in with "include pre-releases". Play internal and closed testing exist only after PO-2. F-Droid and IzzyOnDroid ship stable tags only.

### Store metadata

| Path | Content | Limits checked by `check-fastlane.sh` |
|---|---|---|
| `fastlane/metadata/android/<locale>/title.txt` | app name | ≤ 50 characters |
| `…/short_description.txt` | one line | ≤ 80, mandatory |
| `…/full_description.txt` | limited HTML | ≤ 4,000, mandatory |
| `…/changelogs/<versionCode>.txt` | release notes (en-US only) | ≤ 500 |
| `…/images/icon.png`, `featureGraphic.png`, `phoneScreenshots/*.png` | from the `foss` build with synthetic demo data | PNG, ≤ 8 screenshots |
| `app/src/play/play/listings/<locale>/…`, `app/src/play/play/release-notes/<locale>/default.txt` | Play listing via Gradle Play Publisher (only with PO-2) | same limits; banned words: "download" near "YouTube", "background", "F-Droid", "IzzyOnDroid", "GitHub", "NewPipe" |

Screenshots are produced at M11 on the reference device with a demo library seeded through the backup-restore path from a synthetic backup (monograms and generated artwork only).

---

## Reproducible builds

Serves N8 and [D61](../PLAN.md#3-key-decisions); mitigates risk P4. Delivered in M0 (hygiene, report-only job), blocking from M11 (PLAN M11 AC2).

### Hygiene

| Source of non-determinism | Mechanism | Owner |
|---|---|---|
| Timestamps, git data in `BuildConfig` | none ever ([01 Build flavors](01-foundation.md#build-flavors)) | 01 |
| Build-time secrets | `foss` builds read **no** `-P` value that differs between our build and F-Droid's: `neutrodyne.acraMailto` and `neutrodyne.repoUrl` are committed in `gradle.properties`; Podcast Index credentials are never compiled into `foss` (PO-3: BYOK in `foss` under every option; an injected key may only ever go into `play`) | 01, 09 |
| Signing | release built unsigned when `NEUTRODYNE_KEYSTORE` is absent; F-Droid copies our signature onto its build with `apksigcopier` | 09 |
| PNG crunching | `isCrunchPngs = false` on `release`; PNGs committed pre-optimised | 01 (setting), 09 (rule) |
| VCS info | `vcsInfo { include = false }` on `release` (deterministic per commit in theory, but F-Droid's checkout may differ; nothing depends on it) | 01 |
| AboutLibraries metadata | offline mode: no remote licence or funding fetches during the build (Unverified 15.x property names, e.g. `offlineMode = true`, `fetchRemoteLicense = false`) | 01 |
| R8 non-determinism around kotlinx.coroutines | `-keep class kotlinx.coroutines.CoroutineExceptionHandler`, `-keep class kotlinx.coroutines.internal.MainDispatcherFactory` in `proguard/foss.pro` | 01 |
| Compiled ART profile (`assets/dexopt/baseline.prof`/`.profm`) | kept enabled; if the repro job diffs on it, disable the ArtProfile tasks for `foss` release only (`tasks.matching { it.name.contains("ArtProfile") && it.name.contains("Foss") }.configureEach { enabled = false }`) and re-measure cold start without a profile | 09 |
| Toolchain | JDK 21 (Debian OpenJDK in the container, same major as F-Droid's builder); `build-tools;36.0.0` and `platforms;android-37` pinned by `install-android-sdk.sh`; Gradle 9.7.1 with `distributionSha256Sum` | 09 |
| Build cache, locale, time zone | `--no-build-cache`; `LC_ALL=C.UTF-8`, `TZ=UTC` in the container | 09 |
| `dependenciesInfo` signing block | disabled | 01 |

Unverified: whether the `platforms;android-37` revision can change under the same name and alter R8 output; the repro job records `source.properties` of the platform in its log.

### Nightly reproducibility job

1. Start two containers from `debian:trixie@sha256:<digest>` (digest pinned, Renovate-managed) with different checkout paths (`/build/a` and `/home/vagrant/build/app.neutrodyne`, the latter mimicking F-Droid), different CPU counts (`--cpus=2` and `--cpus=4`) and different umasks (022, 002).
2. In each: install `openjdk-21-jdk-headless git unzip curl`, run `install-android-sdk.sh`, then `./gradlew --no-daemon --no-build-cache assembleFossRelease` (unsigned).
3. Compare SHA-256 of the two APKs; on a difference run `diffoscope` and upload its HTML report.
4. Report-only until M11; from M11 a difference opens a `release-blocker` issue.

### Fallback

If `fossRelease` cannot be made bit-identical before 1.0 (PLAN M11 AC2 alternative): F-Droid signs with its own key; we register F-Droid's certificate as an additional key for developer verification; users cannot move between F-Droid and GitHub/Obtainium builds without reinstalling; README documents it. This fallback is decided and recorded in this section before the F-Droid MR.

---

## Developer verification

Serves N8; mitigates risk P3. Delivered in M11 at the latest (PO-5 blocks M11). Honours [PO-5](../PLAN.md#po-5-google-developer-verification) default A, [D61](../PLAN.md#3-key-decisions).

| Date | Event ([developer verification](https://developer.android.com/developer-verification)) |
|---|---|
| 2026-08 | verification APIs, limited-distribution accounts and the "advanced flow" for unverified installs available |
| 2026-09-30 | enforcement for installs on certified Android devices in Brazil, Indonesia, Singapore and Thailand |
| 2027 | global rollout announced |

Steps (registrant named by the PO):

1. Create the Android Developer Console "Full Distribution" account ($25, government ID for a person; organisation verification for an organisation). The free limited-distribution account (20 devices) is not usable for public releases.
2. Register package `app.neutrodyne` with our certificate SHA-256 and complete the console's key-ownership proof (Unverified mechanics).
3. If Play is used: Play-distributed apps are registered through the Play Console; add the off-Play registration there so sideloaded GitHub, IzzyOnDroid and F-Droid installs (same key) are covered.
4. If F-Droid ships its own signature ([fallback](#fallback)): download F-Droid's APK, read its certificate and register it as an additional key (multiple keys per package are allowed; Google's open-source guide describes this case).
5. `app.neutrodyne.debug` builds are installed over ADB by developers, which stays exempt.

**Testers before registration:** release-signed pre-releases from M0 on are affected in the four enforcement countries; testers there use ADB or the advanced flow (Developer Options toggle and a 24-hour wait). Users who later turn the advanced flow off cannot update an unverified app, which is why registration must precede the 1.0 announcement. A legal identity becomes tied to an app that extracts YouTube streams (risk L1); the PO's PO-1/PO-2 risk appetite applies.

---

## Privacy

Serves N3. Delivered in M0 (`PRIVACY.md` v0, `SECURITY.md`), updated whenever a document adds a network destination (M7 directories, M8/M9 YouTube), final in M11. Honours [D62](../PLAN.md#3-key-decisions).

### Commitments

No analytics, advertising, tracking, Firebase or Google Play services in `foss` (`play` has none in v1.0 either; Cast is v1.x, PO-6); no accounts; no Neutrodyne server; network traffic only to hosts the user chose or opted into; crash reports leave the device only through the user's own mail app after per-crash consent; private feed URLs and tokens never appear in logs, crash reports, diagnostics or directory queries.

### `PRIVACY.md` outline

1. Summary (the commitments above, in plain words).
2. What stays on the device: library, groups, history, positions, Up next, downloads, settings; credentials encrypted with an Android Keystore key.
3. Where the app connects ([inventory](#network-inventory)) and what those hosts receive.
4. Backups: Android Auto Backup carries a daily library snapshot (feed URLs included, possibly with private tokens) to the user's Google account, only on devices with backup encryption (PO-15); manual backup files contain passwords only on opt-in (R1.9).
5. Crash reports and diagnostics: contents, consent, how to request deletion of an emailed report.
6. Permissions and why (from 01's table).
7. Differences between the `foss` and `play` builds (YouTube layer B only in `foss`).
8. Contact and change history (git log of `PRIVACY.md`).

### Network inventory

IDs are stable; the in-app "What Neutrodyne connects to" list (Settings › Privacy) uses the same IDs and `PrivacyInventoryParityTest` (JVM, `:feature:settings`) fails when the IDs in `PRIVACY.md` and the in-app list differ.

| ID | Destination | Purpose | When | Flavor | Default |
|---|---|---|---|---|---|
| `feeds` | each subscribed feed's host and its redirect targets | fetch RSS/Atom ([03](03-feeds-and-discovery.md#fetch-pipeline)) | refresh (periodic, on open, pull), subscribe preview, import | both | user's subscriptions |
| `media` | enclosure hosts and the publishers' measurement redirects in front of them | stream and download audio ([06](06-playback.md#media-items-and-uri-resolution), [07](07-downloads.md#transfer-core)) | play, download, auto-download | both | user action / opt-in |
| `artwork` | artwork hosts referenced by feeds | covers and episode images ([08](08-ui-ux.md#artwork-pipeline)) | subscribe, artwork change, display | both | on |
| `chapters` | hosts of Podcasting 2.0 chapter files | chapters ([06](06-playback.md#chapters)) | playing an episode that has a chapters URL | both | on |
| `notes-images` | image hosts inside show notes | show-notes images ([03](03-feeds-and-discovery.md#show-notes)) | per 03's `feeds.show_notes_images` setting | both | per 03 |
| `apple` | `itunes.apple.com`, `rss.marketingtools.apple.com` | search, lookup of Apple Podcasts links, charts ([03](03-feeds-and-discovery.md#search-and-discovery)) | Discover; adding an Apple Podcasts link | both | on |
| `fyyd` | `api.fyyd.de` | search | Discover | both | on |
| `podcastindex` | `api.podcastindex.org` | search, trending | only with a configured key | both | off |
| `youtube-subscriptions` | `www.youtube.com` (feeds, channel page head, oEmbed), `i.ytimg.com`, `yt3.googleusercontent.com` | YouTube channels as podcasts, layer A ([04](04-youtube.md#channel-resolution)) | only when the user adds or has a YouTube channel | both | user action |
| `youtube-streams` | `www.youtube.com/youtubei/…`, `*.googlevideo.com` (Unverified complete host list; M9 network capture records it) | audio streams, downloads, durations, channel search, layer B ([04](04-youtube.md#stream-resolution)) | playing, downloading or refreshing YouTube items; channel search | `foss` | user action |
| `links` | any link the user taps (episode page, funding, person) | opened in the browser | tap | both | user action |
| `issue-tracker` | `github.com` | "Report a problem" opens the browser | tap | both | user action |
| — | Neutrodyne servers, analytics, ads, Google Play services | — | never | — | — |

What hosts receive: every request carries the device's IP address and `User-Agent: Neutrodyne/<versionName> (Android <release>; +<repo URL>)` (no device or install identifiers, [01 Interceptors](01-foundation.md#interceptors)); feed requests carry stored `If-None-Match`/`If-Modified-Since`; private feeds and their same-origin enclosures carry Basic credentials (never across origins); directories receive only the query text and country ([03 Privacy](03-feeds-and-discovery.md#privacy)); YouTube receives the consent cookie `SOCS=CAE=` and, in `foss`, what NewPipe Extractor's client requests contain. Publishers' measurement redirects can count downloads by IP and user agent; the app adds nothing to help or hinder that. Outside the app's control and disclosed: Android's own Auto Backup transfer, system DNS (Private DNS honoured), and the user's mail app for crash reports.

### Redaction surfaces

01's `Redactor` ([01 Logging and redaction](01-foundation.md#logging-and-redaction)) is the only redaction algorithm; this table lists where it must be applied and how it is tested.

| Surface | Rule | Test |
|---|---|---|
| Logs (Logcat, `RingBufferLogSink`) | every message through `Redactor.text` (01) | 01's `Redactor` tests |
| Crash reports | `STACK_TRACE` and `CUSTOM_DATA` through `Redactor.text`; field allow-list; no `LOGCAT`, `BUILD_CONFIG`, `SHARED_PREFERENCES`, device IDs | `CrashReportRedactorTest` |
| Diagnostics text, issue pre-fill | built only from redacted values | `RedactionCoverageTest` |
| Exported database copy | scrubbed copy ([Copy, report and export](#copy-report-and-export)) | `DatabaseCopyExporterTest` |
| OPML export, backup ZIP | passwords only on opt-in; private-URL warning (05) | 05's tests |

`RedactionCoverageTest` (`:core:data`, Robolectric, M11) seeds a podcast with feed URL `https://alice:s3cret@feeds.example.invalid/rss/a8F3kq09ZpLm2xQ?token=SECRETTOKEN`, an enclosure with `?auth=SECRET2`, provokes a refresh failure and a crash-report collection, then asserts that none of `s3cret`, `SECRETTOKEN`, `SECRET2`, `a8F3kq09ZpLm2xQ` occurs in: the diagnostics report text, the issue pre-fill URL, the ring-buffer log, the `CrashReportData` produced by ACRA's collectors after `CrashReportRedactor`, and the scrubbed database copy.

### Play Data safety

Prepared at M11; filed only with PO-2. On-device data is exempt from declaration ([Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)).

| Question | Answer |
|---|---|
| Collects or shares user data | Crash logs only (below); nothing shared |
| App info and performance → Crash logs | collected: yes, optional (user sends each report from their mail app); shared: no; purpose: app functionality (diagnostics); not processed ephemerally |
| Encrypted in transit | Unverified: the report travels by the user's email provider; decide the answer against Play's guidance at M11 (see [Open questions](#open-questions)) |
| Deletion requests | yes, by emailing the project mailbox |
| Location, personal info, contacts, device IDs, app activity, audio files | not collected |

### Security reporting

`SECURITY.md`: report vulnerabilities through GitHub private vulnerability reporting; acknowledgement within 7 days; fixes ship as PATCH releases through the normal pipeline; scope includes parsing of feeds, OPML, backups and import files (N9), the exported `ArtworkProvider`, intent handling and credential storage.

---

## Crash reporting and diagnostics

Serves N3, N2 (diagnosability); mitigates risk P5. Delivered in M0 (ACRA wiring, disabled until PO-10 names a mailbox), M11 (final configuration, diagnostics screen). Honours [D62](../PLAN.md#3-key-decisions), [PO-10](../PLAN.md#48-further-product-owner-decisions) default.

### ACRA configuration

ACRA 5.14.2 with `acra-mail` and `acra-dialog`: zero network traffic from the reporter, F-Droid-accepted (NewPipe precedent). `installAcra` is called from `NeutrodyneApplication.attachBaseContext` when `BuildConfig.ACRA_MAILTO` is not empty; the `:acra` process returns early from `onCreate` ([01 Application start-up](01-foundation.md#application-start-up)). `ACRA_MAILTO` comes from the committed `neutrodyne.acraMailto` ([Hygiene](#hygiene)) and is forced to `""` on the `debug` build type (debug builds crash fast with StrictMode and LeakCanary instead).

```kotlin
// :app
fun installAcra(app: Application) = app.initAcra {
    buildConfigClass = BuildConfig::class.java
    reportFormat = StringFormat.KEY_VALUE_LIST
    reportContent = listOf(                                   // explicit allow-list
        ReportField.REPORT_ID, ReportField.APP_VERSION_NAME, ReportField.APP_VERSION_CODE,
        ReportField.ANDROID_VERSION, ReportField.BRAND, ReportField.PHONE_MODEL,
        ReportField.STACK_TRACE, ReportField.CUSTOM_DATA, ReportField.USER_COMMENT,
        ReportField.USER_CRASH_DATE, ReportField.IS_SILENT,
    )                                                         // never LOGCAT, BUILD_CONFIG (PI keys in play), SHARED_PREFERENCES, DEVICE_ID
    alsoReportToAndroidFramework = BuildConfig.FLAVOR == "play"   // keep Android vitals for Play (Hilt is not ready here)
    mailSender {
        mailTo = BuildConfig.ACRA_MAILTO
        reportAsFile = true
        reportFileName = "neutrodyne-crash.txt"
        subject = app.getString(R.string.crash_mail_subject)
        body = app.getString(R.string.crash_mail_body)
    }
    dialog {
        title = app.getString(R.string.crash_dialog_title)
        text = app.getString(R.string.crash_dialog_text)
        commentPrompt = app.getString(R.string.crash_dialog_comment)
    }
}
```

Dialog text (en): "Neutrodyne stopped. You can send a crash report by email: your mail app opens with the report attached, and nothing is sent until you press Send there. The report contains the error, the app version, the Android version and the phone model — never your subscriptions, feed addresses or listening history."

- **`CrashReportRedactor`** (`:app`): an ACRA `ReportingAdministrator` loaded through `META-INF/services/org.acra.config.ReportingAdministrator`; in `shouldSendReport` it replaces `STACK_TRACE` and each `CUSTOM_DATA` value with `Redactor.text(…)` and drops custom keys outside the allow-list, then returns `true` (whether reports are offered at all is ACRA's own enabled flag, mirrored from `privacy.crash_reports`, see [Settings](#settings)). Unverified: that ACRA 5.14 persists the report after administrators run (so the redacted data is what the dialog and sender see); fallback: a delegating `ReportSenderFactory` that redacts before `EmailIntentSender` formats the file.
- **Mail app visibility:** 01's merged manifest carries `<queries>` for `SENDTO mailto:` (Unverified need on API 30+); without any mail app ACRA cannot hand off, so the diagnostics screen's "Copy diagnostics" is the fallback.
- **Play vitals:** `alsoReportToAndroidFramework` is on only in `play`, so Android vitals keep receiving crashes there. Unverified: whether the framework's crash dialog then appears in addition to ACRA's (M11 device check; fallback: off, rely on ACRA in both flavors).

### `CrashReporter` and `CrashContext`

Modules cannot see ACRA; they use two small interfaces from `:core:common`, bound in `:app` (`AcraCrashReporter`, ACRA-backed `CrashContext`), with no-op bindings when ACRA is disabled.

```kotlin
// :core:common
interface CrashReporter {
    val isAvailable: Boolean                                    // false when ACRA_MAILTO is empty or privacy.crash_reports is off
    fun reportNonFatal(t: Throwable, where: String)             // shows the dialog without ending the app (ACRA handleException)
}
interface CrashContext {
    fun put(key: CrashKey, value: String)                       // value passes Redactor.text; last write wins
}
enum class CrashKey { SCREEN, DB_RECOVERY, YOUTUBE_HEALTH, PLAYBACK, RUNNING_WORK }
```

| `CrashKey` | Written by | Value |
|---|---|---|
| `SCREEN` | `:app` navigation host | top-level destination and key class name (no IDs) |
| `DB_RECOVERY` | 02 `DatabaseOpener` | `RecoveryCause` or empty |
| `YOUTUBE_HEALTH` | 04 `YouTubeHealth` | breaker state, rate-limit level, extractor version (`foss`) |
| `PLAYBACK` | 06 | player state and branch (`LOCAL`/`REMOTE`/`YOUTUBE`), never a URL |
| `RUNNING_WORK` | `:app` | unique work names currently `RUNNING` |

Callers of `reportNonFatal`: 08's `StartupGate` "Send report" when the database failed to open, and 02's recovered-database message. When `isAvailable` is false, those UIs show "Copy diagnostics" instead.

```mermaid
sequenceDiagram
  participant T as Crashing thread
  participant A as ACRA
  participant X as CrashReportRedactor
  participant D as Crash dialog in acra process
  participant U as User
  participant Mail as Mail app
  T->>A: uncaught exception
  A->>A: collect allow-listed fields and CUSTOM_DATA
  A->>X: shouldSendReport
  X->>X: Redactor.text over stack trace and custom data
  A->>D: show dialog
  U->>D: Send or Do not send
  D->>Mail: SENDTO mailto with neutrodyne-crash.txt
  U->>Mail: review and press Send
```

### Diagnostics API

The diagnostics screen (`DiagnosticsKey`, visuals in [08 Diagnostics](08-ui-ux.md#diagnostics)) reads one repository; each module contributes its own section through a Hilt multibinding, so no implementation module depends on another.

```kotlin
// :core:model
data class DiagnosticsReport(val generatedAt: Long, val sections: List<DiagnosticsSection>)
data class DiagnosticsSection(val id: DiagnosticsSectionId, val lines: List<DiagnosticsLine>)
data class DiagnosticsLine(val key: String, val value: String, val severity: DiagnosticsSeverity = DiagnosticsSeverity.INFO)   // English keys, redacted values
enum class DiagnosticsSectionId { APP, REFRESH, BACKGROUND, JOBS, DOWNLOADS, YOUTUBE, DATABASE, NOTIFICATIONS, PARSE_WARNINGS, LOG }
enum class DiagnosticsSeverity { INFO, WARNING, PROBLEM }

// :core:domain
interface DiagnosticsContributor {                    // @IntoSet from :core:data, :download:impl, :playback:impl, :app
    val section: DiagnosticsSectionId
    suspend fun collect(): List<DiagnosticsLine>
}
interface DiagnosticsRepository {                     // implemented by DiagnosticsRepositoryImpl in :core:data
    suspend fun snapshot(): DiagnosticsReport
    fun toPlainText(report: DiagnosticsReport, maxBytes: Int = 64 * 1024): String
    suspend fun exportDatabaseCopy(): Outcome<String /* content URI */, DiagnosticsError>
    fun observeLogLines(): Flow<List<String>>          // RingBufferLogSink (01), already redacted
}
enum class DiagnosticsError { NOT_ENOUGH_SPACE, DATABASE_BUSY, FAILED }
```

### Diagnostics contents

| Section | Lines | Source (owner) | API level |
|---|---|---|---|
| `APP` | version name/code, flavor, build type, Android release and SDK, manufacturer and model, ABI, app locales, SQLite version (`sqlite_version()`), Media3 and (`foss`) NewPipe Extractor versions, installer (`getInstallSourceInfo`), first 8 hex digits of the signing certificate SHA-256 | `:app` | installer: 30+ |
| `REFRESH` | `feeds.last_run_finished_at`, `feeds.last_run_summary`, `feeds.last_run_stop_reason` | 03 | — |
| `BACKGROUND` | standby bucket (`UsageStatsManager.getAppStandbyBucket`), background restricted (`ActivityManager.isBackgroundRestricted`), battery optimisation (`PowerManager.isIgnoringBatteryOptimizations`), Data Saver (`ConnectivityManager.getRestrictBackgroundStatus`), network status (01 `NetworkMonitor`), last 10 process exits with reason (`getHistoricalProcessExitReasons`) | `:app` | bucket and restriction 28+; exits 30+ |
| `JOBS` | for each canonical unique work name: state, run attempt count, stop reason, next schedule time | `:core:data` (WorkManager) | — (Unverified WorkManager accessor names) |
| `DOWNLOADS` | 07's `DownloadDiagnostics` (stop ring, pending job reasons, roots and free space) | 07 | pending reasons 36+ |
| `YOUTUBE` | breaker, rate limit, feed outage (`foss`; outage in both) | 04 | — |
| `DATABASE` | file size, row counts, last `db-maintenance` step durations, `diagnostics.db_quick_check_failed_at`, last recovery cause | 02 | — |
| `NOTIFICATIONS` | notifications enabled, per-channel importance (blocked channels flagged) | `:app` | channels 26+ |
| `PARSE_WARNINGS` | per-feed parse warnings of the last ingest (in-memory LRU of 50) | 03 | — |
| `LOG` | last 500 redacted log lines | 01 `RingBufferLogSink` | — |

Threading and failures: `snapshot()` runs all contributors concurrently on `@Dispatcher(IO)` with a 2 s timeout each; a timeout or exception becomes one line `"<section> unavailable (<ExceptionClass>)"` with `DiagnosticsSeverity.WARNING`; the screen never fails as a whole.

### Copy, report and export

- **Copy diagnostics:** `toPlainText` (sections in enum order, log last, truncated from the log's oldest lines to 64 KB) to the clipboard.
- **Report a problem:** opens `{repoUrl}/issues/new?template=bug.yml&diagnostics=<url-encoded summary>` in the browser; the summary is `toPlainText(report, maxBytes = 6 * 1024)` without the `LOG` and `PARSE_WARNINGS` sections (URL length limits). Unverified: GitHub issue-form pre-fill by field ID (`diagnostics`); fallback: `body=`.
- **Export database copy** (bundled driver only): `DatabaseCopyExporter` (`:core:data`) runs 02's `VACUUM INTO '<cacheDir>/export/neutrodyne-diagnostics-<yyyy-MM-dd-HHmm>.db'`, then opens the copy and scrubs it in one transaction: `DELETE FROM credential`, `DELETE FROM episode_description`; every URL column (`podcast.feedUrl`, `link`, `artworkUrl`, `bannerUrl`, `hubUrl`, `pagingNextUrl`; `podcast_url_alias.url`; `episode.enclosureUrl`, `link`, `imageUrl`, `chaptersUrl`; `episode_alt_enclosure.sourcesJson`; `download.sourceRef`, `finalUri`; `import_item.originalUrl`, `normalizedUrl`; `artwork.url`) replaced by `Redactor.url(value)`, and `podcast.feedKey` replaced by `'k' || id` (keeps the unique index). The file is shared through the FileProvider; a confirmation first states that subscriptions, titles and history are included. The copy is deleted after 1 h or at the next app start. Fails with `NOT_ENOUGH_SPACE` when free space < 2 × database size + 50 MB.

The diagnostics screen links to `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (fallback `ACTION_APPLICATION_DETAILS_SETTINGS`) and never requests an exemption: Play prohibits direct exemption requests for apps whose core function is not adversely affected, and podcast apps are not on the accepted list ([Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)); 01's `checkBannedApis` blocks `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

"Detailed log for 24 hours" (`diagnostics.verbose_log_until`) lowers the ring buffer's level to `DEBUG` (still redacted) so a user can reproduce a problem and copy the log.

---

## Localisation

Serves N10. Delivered in M0 (conventions, Lint gates, per-app language plumbing), M10 (Weblate project, pseudo-locale screenshots), M11 (launch languages). Honours [PO-14](../PLAN.md#48-further-product-owner-decisions) default.

### Workflow

- **Hosted Weblate, Libre plan** (free for public libre projects), project `neutrodyne`, created in M10 once strings stabilise.
- **Components** via Weblate's component-discovery add-on: one component per module with strings, file mask `{module path}/src/main/res/values-*/strings.xml`, monolingual base `…/values/strings.xml` (Android string resource format); one for `app/src/{foss,play}/res/values-*/strings_flavor.xml`; `store-metadata` (format "App store metadata files", base `fastlane/metadata/android/en-US`, `changelogs/*` excluded); `play-listing` for `app/src/play/play/listings/` only after PO-2. Strings stay in the module that owns them (features cannot share resources across modules).
- **Flow:** Weblate commits to its own branch and opens a PR (squash add-on); CI runs the normal checks (Lint fatal format and plural checks catch broken translations); maintainers merge at least weekly. Developers never edit `values-xx` by hand except to revert a broken string. Before a PR that renames or deletes many string keys, lock the Weblate component and merge Weblate's pending PR first.
- Weblate maps language codes to Android qualifiers (`pt_BR` → `values-pt-rBR`, `zh_Hant` → `values-b+zh+Hant`).

### String conventions

- Every user-visible text is a resource; ViewModels carry `UiText` (01). Counts use `<plurals>` and `pluralStringResource`; never concatenate fragments; placeholders are positional (`%1$s`) with an XML comment above the string explaining each one; non-translatable parts use `<xliff:g id="…" example="…">`; brand and technical tokens use `translatable="false"`.
- Numbers, dates and durations are formatted with the app locale (`AppCompatDelegate.getApplicationLocales()[0]`, else `Locale.getDefault()`): `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(…)`, `android.icu.text.RelativeDateTimeFormatter` for relative times, `NumberFormat` for numbers; wire formats always use `Locale.ROOT` (01).
- Icons with direction use auto-mirroring; layouts use start/end, never left/right.
- `play`-only and `foss`-only wording lives in flavor resources ([08 Flavor differences in UI](08-ui-ux.md#flavor-differences-in-ui)).
- Hard-coded Compose text is caught by review and by `en-XA` screenshots (unlocalised text stays plain ASCII there); 09 asks 01 to add the `Text("…")` literal pattern in `feature/**/src/main` to `checkBannedApis`.

### Shipped locales and per-app language

- `app/policy/locales.txt` lists the shipped locales (`en-US` plus every language ≥ 90 % translated across all `app-strings` components when the release branch is cut; PO-14). `scripts/l10n/update-shipped-locales.sh` reads Weblate's per-language statistics API and rewrites the file; it runs during the [minor-release checklist](#minor-and-stable-release-additions). Unverified: the statistics endpoint and field names.
- `:app` reads the file into `androidResources.localeFilters` and generates `BuildInfo.shippedLocales` (request to 01) for 08's in-app picker (Appearance › Language), which calls `AppCompatDelegate.setApplicationLocales(…)`. `generateLocaleConfig = true` with `res/resources.properties` (`unqualifiedResLocale=en-US`) lists the same locales for Android 13+'s system app-language settings ([per-app languages](https://developer.android.com/guide/topics/resources/app-languages)). Unverified: that `generateLocaleConfig` honours `localeFilters` and ignores library translations (M0 check; fallback: generate `res/xml/locales_config.xml` from `locales.txt` and turn `generateLocaleConfig` off).
- Partially translated languages stay in the repository but are filtered out of the APK until they reach the threshold; missing strings in shipped languages fall back to English.

### Pseudo-locales and RTL

`isPseudoLocalesEnabled = true` on `debug`; Roborazzi captures `en-XA` (long accented text) and `ar-XB` (RTL) per [Compose UI and screenshot tests](#compose-ui-and-screenshot-tests); `android:supportsRtl="true"` (01); a manual Arabic pass and a 200 % font pass on device per release (08's manual checks).

---

## Performance budgets

Serves N5, R2.9; mitigates risk T6. Delivered in M2 (query timing), M10 (grid jank), M11 (startup, profiles, size). Journeys are defined by [08 Performance journeys](08-ui-ux.md#performance-journeys); seeded data by 02's `SeedDatabase` (300 podcasts, 50,000 episodes, 20 groups).

### Reference devices

| Role | Device | Use |
|---|---|---|
| Reference (N5 "PO-agreed mid-range phone") | default until the PO names one: Google Pixel 7a on Android 16 or later | every budget below; Macrobenchmark runs before each minor release |
| Floor | any 2–3 GB RAM device on API 26–28 | functional smoke and scroll feel only, no numeric budgets |
| CI emulators | GMD `api36`, `bench34` | trends and dry runs only; never gate on emulator timings (shared VMs are noisy) |

### Budgets

| ID | Metric | Budget | Measured by | Gate |
|---|---|---|---|---|
| PB1 | Cold start to Feeds, time to initial display, p50 | < 600 ms (N5) | `ColdStartToFeeds`: `StartupTimingMetric`, `StartupMode.COLD`, 15 iterations, `CompilationMode.Partial(BaselineProfileMode.Require)` | release (PLAN M11 AC1) |
| PB2 | Same, p90 | < 900 ms | same | soft (investigate) |
| PB3 | Cover-grid fling jank | < 1 % of frames with `frameOverrunMs > 0` | `CoverGridFling`: `FrameTimingMetric`, 5 iterations × 3 flings over 300 tiles | M10 AC7, release |
| PB4 | All-feed fling and group-pager swipe jank | < 1 % | `AllFeedFling`, `GroupPagerSwipe` | release |
| PB5 | Player expand/collapse jank | < 1 % | `PlayerExpandCollapse` | soft |
| PB6 | Group feed first page (count + 80 rows) | ≤ 60 ms | 02's `FeedQueryTimingTest`, median of 20 | M2 AC2 on the reference device; CI records on GMD |
| PB7 | All feed first page | ≤ 100 ms | same | same |
| PB8 | Subsequent page load | ≤ 20 ms | same | same |
| PB9 | Podcast screen open to content | < 300 ms | manual trace on the reference device (M1 AC5); from M11 `PodcastOpen` journey with a trace section | M1 manual, release soft |
| PB10 | 300-feed OPML → 300 pending tiles | ≤ 2 s (R1.3) | 05's import test on device | M3 |
| PB11 | Parse the 831-item 3.5 MB feed | < 1 s on the JVM | 03's corpus test | every PR |
| PB12 | `foss` universal APK | < 25 MB (N5) | `check-apk.sh` | every PR |
| PB13 | `play` universal APK | < 25 MB | `check-apk.sh` | every PR |
| PB14 | Database at the N5 scale | ≤ 100 MB | 02's size measurement | M11 |
| PB15 | App PSS after `CoverGridFling` + `PlayerExpandCollapse` | ≤ 250 MB (starting value, Unverified) | `dumpsys meminfo` captured by the benchmark | soft |
| PB16 | 300-feed refresh with all feeds answering 304 | ≤ 3 min on Wi-Fi | 03's M11 performance check | soft |
| PB17 | Splash hold | ≤ 400 ms | 01's start-up rule | M0 |

Budget changes are PO decisions (N5) and are recorded in this table.

### Macrobenchmark and profiles

- `:benchmark` (from M10 for the journeys; module exists since M6) uses `benchmark-macro-junit4` 1.5.0 and the `androidx.baselineprofile` plugin with `targetProjectPath = ":app"`, `useConnectedDevices = false`, managed device `bench34` (`aosp`, API 34; profile generation needs an `aosp` image at API 33+ or root).
- **Seeding:** the plugin-created `benchmarkRelease` and `nonMinifiedRelease` build types get an extra source directory `app/src/benchmarkShared/` (added to both via `sourceSets`) containing `BenchmarkSeedReceiver`, an explicit-only receiver protected by `android:permission="android.permission.DUMP"` (held by the shell), which fills the database with `SeedDatabase` (`benchmarkReleaseImplementation(testFixtures(project(":core:database")))`). The benchmark's `setupBlock` sends `am broadcast -n app.neutrodyne/.benchmark.BenchmarkSeedReceiver` once and waits for its marker file. The `release` variant never contains it. Unverified: variant-specific source sets and dependencies for plugin-created build types (M10 check).
- **Baseline and startup profiles:** generated from the same journeys (`ColdStartToFeeds` with `includeInStartupProfile = true`), `mergeIntoMain = true`, committed as text under `app/src/main/generated/baselineProfiles/`; `automaticGenerationDuringBuild = false` so normal and F-Droid builds never need an emulator. Regenerated with `baseline-profile.yml` before each minor release.
- **Full display:** 08's Feeds screen calls `ReportDrawnWhen { first page loaded }` so `StartupTimingMetric` also reports time to full display (request to 08); not budgeted in v1.
- Results (`*-benchmarkData.json`) from the reference device are attached to the release checklist issue; a regression > 10 % against the previous minor release on PB1–PB4 blocks the release until explained.
- R8: full mode via `optimization { enable = true }` and `-dontobfuscate` (01). Size tips applied when PB12 is at risk: exclude `/DebugProbesKt.bin`, review keeps with the R8 configuration analyzer, measure `sqlite-bundled`'s four ABIs ([01 S6](01-foundation.md#s6-sqlite-bundled-16-kb-alignment-and-size)).

---

## Release checklist

Serves N1–N11. Copied into `.github/ISSUE_TEMPLATE/release.md`; one issue per release.

### Every release

Before tagging:

- [ ] `main` green: `ci.yml` and the last `nightly.yml` (no open `release-blocker` issue).
- [ ] Changelog file for the new `versionCode` written (and `play` notes, if Play publishing is on).
- [ ] Weblate PR merged (or explicitly deferred).
- [ ] Any schema change since the last tag has its migration test; the frozen-schema check passes.
- [ ] `scripts/release.sh … --dry-run` prints the expected `versionName`/`versionCode`.

Tag and publish:

- [ ] `scripts/release.sh …`; approve the `release` environment; `release.yml` green in < 30 min.
- [ ] GitHub release shows APK, mapping, `SHA256SUMS`, (from M9) corresponding source; pre-release flag correct.
- [ ] Obtainium updates a test device; the installed app's certificate matches `NEUTRODYNE_CERT_SHA256`.
- [ ] `verify-repro` green (from M11; before M11 note the result).

### Minor and stable release additions

- [ ] `baseline-profile.yml` run and profiles committed.
- [ ] Macrobenchmarks PB1–PB5 on the reference device; results attached; no unexplained regression > 10 %.
- [ ] `update-shipped-locales.sh` run; `locales.txt` committed.
- [ ] Manual device matrix of [06](06-playback.md#testing) and checklist of [07](07-downloads.md#instrumented-and-device-tests) re-run on the reference device.
- [ ] 08's manual checks: TalkBack, Switch Access, 200 % font, Arabic RTL, keyboard-only, foldable postures, grid → podcast transition review, airplane mode with downloads.
- [ ] `bmgr` check on a device (05/07).
- [ ] `PRIVACY.md` matches the network inventory (parity test green) and any new destination.
- [ ] Store metadata (`fastlane`, and Play listing if enabled) reviewed against the `play` guardrails.
- [ ] Play: internal track build tested; staged rollout started (only with PO-2).

### Hotfix (YouTube fast lane)

Follows [04 Hotfix runbook](04-youtube.md#hotfix-runbook): Renovate (or manual) bump → `bump-extractor.sh` → CI incl. recorded-response tests → merge → dispatch `nightly.yml` with `scope: youtube-smoke` on `main` (minified `fossRelease` smoke) → `release.sh patch --hotfix` → `release.yml`. Skipped for hotfixes: profiles, benchmarks, locales, manual matrices. Target < 30 min from upstream release to signed GitHub release.

### Milestone tester build

Per PLAN DoD: the milestone's acceptance criteria are listed in the release issue with the test or manual check that proves each one; tag `0.{n+1}.0-beta.N`; design documents updated for deviations.

### v1.0 gate

| [PLAN M11](../PLAN.md#m11-release-hardening-and-v10) acceptance | Evidence |
|---|---|
| 1 Cold start p50 < 600 ms; `foss` APK < 25 MB | PB1 Macrobenchmark on the reference device; `check-apk.sh` |
| 2 Two `fossRelease` builds bit-identical (or fallback decided) | nightly `repro` green for 7 consecutive nights and `verify-repro` on `v1.0.0-rc.N`; or the [fallback](#fallback) recorded with F-Droid's key registered |
| 3 Tag → signed APK, `SHA256SUMS`, mapping, notes in < 30 min; Obtainium installs | `release.yml` timing on `v1.0.0-rc.N` and `v1.0.0`; Obtainium device check |
| 4 Instrumented suite on minified `fossRelease` on API 26 and 36 GMD and an API 37 16 KB image | `instrumented-full` (`-PtestBuildType=release`) and `api37-16k` green on the release commit |
| 5 Network capture of a fresh-install session shows only expected hosts | `scripts/ci/network-capture.sh`: emulator (`aosp`, no Google apps) started with `-tcpdump`, UI Automator session (subscribe 2 real feeds, refresh, stream 30 s, download one episode, search "news", in `foss` add and play one YouTube channel); `tshark` extracts DNS names and TLS SNI; every host is mapped to an inventory ID; OS hosts (connectivity check, NTP) listed separately. Unverified: emulator `-tcpdump` on API 36 images |
| 6 Migration from the first tester schema; device upgraded from the last beta keeps data | 02's `MigrateAllTest`; manual upgrade on the reference device from the last `-beta` APK, comparing library, groups, history, Up next and downloads |
| 7 PO-2, PO-5, PO-8, PO-10, PO-14 resolved | PLAN §4 updated |

Plus: every earlier milestone's acceptance criteria green; key ceremony done and backups verified; developer verification registered; F-Droid MR opened and IzzyOnDroid request filed; `PRIVACY.md` final; GPL obligations met (About statement, NewPipe Extractor notice, corresponding source, [04 Notices](04-youtube.md#notices)); ACRA mailbox configured and a test report received.

---

## Settings

Keys owned here ([01 DataStore files and typed setting keys](01-foundation.md#datastore-files-and-typed-setting-keys)). UI on 08's `PRIVACY` and `ABOUT` pages.

| Key | Type | Default | File | UI location | Milestone |
|---|---|---|---|---|---|
| `privacy.crash_reports` | Bool | true | `settings` | Settings › Privacy › "Offer to send crash reports" (dialog per crash; off = ACRA reports nothing) | M0 (UI M11) |
| `diagnostics.verbose_log_until` | Long? (epoch ms) | null | `device_settings` | Settings › About › Diagnostics › "Detailed log for 24 hours" | M11 |

`privacy.crash_reports` is mirrored into ACRA by an `AppInitializer` (order 20) and on every change (`ACRA.errorReporter.setEnabled(…)`, Unverified accessor name), because ACRA reads its own flag at crash time, before DataStore could be read. `diagnostics.db_quick_check_failed_at` is 02's key.

Settings › Privacy page content (09): crash-reports switch; "What Neutrodyne connects to" (the [inventory](#network-inventory) with each row's current state, e.g. Podcast Index off); links to the Discover providers (03) and show-notes images (03) settings; "Privacy policy" (opens `PRIVACY.md`).

---

## Delivery by milestone

| Milestone | Delivered in this area |
|---|---|
| [M0](../PLAN.md#m0-scaffold-and-ci) | `configureNeutrodyneTestTasks`, `neutrodyne.android.testing` content, `:core:common` test fixtures (`TestClock`, `MainDispatcherRule`, `Goldens`), `:core:testing` (`FakeNetworkMonitor`, `FakeSettingsRepository`, `FakeCrashReporter`, `FakeCrashContext`, `Nightly`), Robolectric `sdk=36`, Roborazzi wiring with one Settings screenshot, GMD `api26`/`api36` and E0; `ci.yml` (static, unit, assemble, instrumented); `nightly.yml` (`instrumented-full`, `api37-16k`, `repro` report-only); `release.yml`; key ceremony; first signed pre-release `v0.1.0-beta.1`; Renovate; `.editorconfig`, Lint, Spotless, detekt; PR and issue templates; `installAcra` + `CrashReportRedactor` (disabled until PO-10), `CrashReporter`/`CrashContext`; `PRIVACY.md` v0, `SECURITY.md`; fastlane `en-US` skeleton; `privacy.crash_reports` key |
| [M1](../PLAN.md#m1-subscribe-and-ingest-rss) | fakes for 03's interfaces + contracts; `:core:database` test fixtures (02's `TestDb`); golden switch in `:feeds`; `MutationRobustnessTest` for `FeedParser` and `mutation-full`; platform-parser corpus in `:core:data` `androidTest`; schema drift and frozen-schema checks live; `TestServer`, E1; data builders, `fakeImageLoader` |
| [M2](../PLAN.md#m2-groups-and-group-feeds) | fakes for 05/08 interfaces; `ScreenshotTier`; E2; `FeedQueryTimingTest` recorded on GMD and run on the reference device (R2.9, PLAN M2 AC2); reference device confirmed with the PO |
| [M3](../PLAN.md#m3-import-export-and-backup) | OPML/backup mutation providers; E5, E6, E9; nightly `bmgr` job; `RecordingAppNavigator` |
| [M4](../PLAN.md#m4-playback-core) | Media3 test-utils forcing verified; `api34` device; 06's `PlaybackServiceTest` in nightly (incl. API 37 hardening); E3 |
| [M5](../PLAN.md#m5-playback-features-and-system-surfaces) | manual device-matrix template in the release issue |
| [M6](../PLAN.md#m6-downloads) | `:benchmark` module for system tests, `bench34`, `system-tests` job, E10; `api33` device; E4; `bmgr` assertion for `Podcasts/` |
| [M7](../PLAN.md#m7-discovery) | `PRIVACY.md` and in-app inventory gain `apple`, `fyyd`, `podcastindex`; `PrivacyInventoryParityTest` |
| [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds) | E8 (`playDebug`) in `instrumented`; YouTube import-parser mutation providers; inventory `youtube-subscriptions` |
| [M9](../PLAN.md#m9-youtube-playback-and-downloads-in-foss) | E7 on minified `fossRelease`; `check-play-dex.sh` against real GPL artifacts; `collectGplSources` and the corresponding-source asset; `emergency-patch-check`, `youtube-canary`; Renovate fast lane and `bump-extractor.sh`; inventory `youtube-streams` |
| [M10](../PLAN.md#m10-covers-theming-adaptive-layouts-and-accessibility) | `FULL` screenshot tier and `screenshots-full`; pseudo-locale/RTL captures; Weblate project; `:benchmark` journeys, `BenchmarkSeedReceiver`, `benchmark-dryrun`; PB3 on the reference device (M10 AC7) |
| [M11](../PLAN.md#m11-release-hardening-and-v10) | baseline/startup profiles and `baseline-profile.yml`; all budgets measured; `DiagnosticsRepository` and contributors, `DatabaseCopyExporter`, `RedactionCoverageTest`, `diagnostics.verbose_log_until`; ACRA mailbox (PO-10); `PRIVACY.md` final and Data safety draft; launch languages (PO-14); `repro` blocking and `verify-repro`; `live-canary`; network capture; F-Droid dry run and MR; IzzyOnDroid request; Play submission if PO-2; developer verification (PO-5); v1.0 gate |
| M12–M15 | Glance widget screenshot tests (M13); `play`-only Cast build checks and `play` proprietary allow-list (M13); SponsorBlock destination in the inventory (M14); revisit Compose Preview Screenshot Testing once AGP test suites are stable |

---

## New names introduced here

| Name | Kind | Location |
|---|---|---|
| `configureNeutrodyneTestTasks()`, `configureAndroidTesting()`, `configureManagedDevices()` | build-logic functions | `build-logic/convention` |
| Gradle properties `updateGoldens`, `screenshotTier`, `mutationIterations`, `testBuildType`, `neutrodyne.testScope` | build switches | — |
| System properties `neutrodyne.updateGoldens`, `neutrodyne.moduleDir`, `neutrodyne.rootDir`, `neutrodyne.screenshotTier`, `neutrodyne.mutationIterations` | test configuration | — |
| `TestClock`, `MainDispatcherRule` (canonical names; physical location), `Goldens` | test helpers | `:core:common` test fixtures, package `app.neutrodyne.core.testing`, re-exported by `:core:testing` |
| `Nightly`, `ScreenshotTier`, `assumeTier` | test selection | `:core:testing` |
| `Fake*` per [inventory](#coretesting-inventory), `*Contract` bases, data builders, `fakeImageLoader` | fakes | `:core:testing` |
| `RecordingAppNavigator` | fake | `:core:navigation` test fixtures |
| `TestServer`, `FixtureDispatcher`, `TestSeeder` | E2E helpers | `:app/src/androidTest` |
| `SmokeTest`, `SubscribeJourneyTest`, `GroupFeedJourneyTest`, `PlayJourneyTest`, `DownloadJourneyTest`, `ImportJourneyTest`, `BackupRestoreJourneyTest`, `YouTubeReleaseSmokeTest`, `PlayFlavorYouTubeTest` | E2E tests | `:app/src/androidTest` |
| `MutationRobustnessTest`, task `mutationTest` | N9 robustness | `:feeds`, `:youtube:api` |
| `PrivacyInventoryParityTest`, `RedactionCoverageTest`, `CrashReportRedactorTest`, `DatabaseCopyExporterTest` | tests | `:feature:settings`, `:core:data`, `:app`, `:core:data` |
| GMD devices `api26`, `api33`, `api34`, `api36`, `bench34`; groups `ci`, `nightly` | devices | build-logic, `:benchmark` |
| `CrashReporter`, `CrashContext`, `CrashKey` | interfaces/enum | `:core:common` |
| `installAcra` (01's name, defined here), `AcraCrashReporter`, `CrashReportRedactor` | ACRA integration | `:app` |
| `DiagnosticsReport`, `DiagnosticsSection`, `DiagnosticsLine`, `DiagnosticsSectionId`, `DiagnosticsSeverity` | data | `:core:model` |
| `DiagnosticsContributor`, `DiagnosticsRepository`, `DiagnosticsError` | interfaces | `:core:domain` |
| `DiagnosticsRepositoryImpl`, `DatabaseCopyExporter` | implementations | `:core:data` |
| `BenchmarkSeedReceiver` | benchmark-only receiver | `app/src/benchmarkShared` |
| `collectGplSources` | Gradle task | `:youtube:streams` |
| `BuildInfo.shippedLocales` | field request | `:core:model` (01) |
| `privacy.crash_reports`, `diagnostics.verbose_log_until` | setting keys | `:core:model` registry |
| Workflows `ci.yml`, `nightly.yml`, `release.yml`, `record-screenshots.yml`, `baseline-profile.yml`; jobs per [CI pipelines](#ci-pipelines); GitHub environment `release`; labels `run-instrumented`, `youtube-hotfix`, `nightly-failure`, `release-blocker` | CI | `.github/` |
| Scripts per [CI scripts](#ci-scripts), `scripts/release.sh`, `scripts/release/corresponding-source.sh`, `scripts/l10n/update-shipped-locales.sh`, `scripts/youtube/bump-extractor.sh`, `scripts/ci/network-capture.sh` | scripts | `scripts/` |
| `app/policy/locales.txt`, `app/lint-baseline.xml`, `config/detekt/detekt.yml`, `renovate.json`, `fdroid/app.neutrodyne.yml`, `PRIVACY.md`, `SECURITY.md`, `.editorconfig`, `proguard/test.pro` | files | repository |
| Secrets `NEUTRODYNE_UPLOAD_KEYSTORE*`, `PLAY_SERVICE_ACCOUNT_JSON`; variables `NEUTRODYNE_CERT_SHA256`, `PLAY_PUBLISHING` | CI configuration | GitHub |

---

## Open questions

1. **Architect review: `:core:testing` cannot serve JVM modules.** It is an Android library, so 01's rule 13 ("test code may use `:core:testing` anywhere") does not hold for `:core:model`, `:core:common`, `:core:domain`, `:feeds` and `:*:api`. This document places `TestClock`, `MainDispatcherRule` and `Goldens` in `:core:common` test fixtures (re-exported by `:core:testing`) and keeps fake-dependent tests in Android modules. Confirm, or make `:core:testing` a JVM module and move `fakeImageLoader` elsewhere.
2. **Architect review: `@TestInstallIn` modules.** 01 places them in `:core:testing`, but they must name the production Hilt modules they replace, which rule 9 forbids `:core:testing` to see. They live in `:app/src/test`; 01's [Test overrides](01-foundation.md#test-overrides) should say so.
3. **Architect review: `:benchmark` from M6.** Process-death tests (07's `ProcessDeathResumeTest`, PLAN M6 AC2) cannot run in-process; the skeleton's "content from M11" for `:benchmark` becomes M6 (system tests), with Macrobenchmarks in M10/M11.
4. **Architect review: reproducibility vs build-time secrets.** `neutrodyne.acraMailto` must be committed in `gradle.properties` (not passed with `-P`), and PO-3 option A ("inject the Podcast Index key into GitHub and Play builds") would make the GitHub `foss` APK differ from F-Droid's rebuild. This document restricts any injected PI key to `play`; PO-3's option text needs amending.
5. **Architect review: key ceremony in M0.** D61/PO-8 list key custody as an M11 blocker, but tester pre-releases from M0 must already carry the final key or testers reinstall at 1.0. Default here: ceremony in M0 with PO-8's default holders.
6. **Architect review: 02's `sharedTest` fixtures** move to AGP test fixtures of `:core:database` so `:core:data`, `:playback:impl`, `:download:impl` and `:app` can use `TestDb` and `SeedDatabase`.
7. **PO (N5):** name the reference device; default Pixel 7a.
8. **PO (PO-10 / Data safety):** keep ACRA in `play` (declare optional crash logs; "encrypted in transit" depends on the user's mail provider) or disable ACRA in `play` and rely on Android vitals (declare "no data collected"). Default: ACRA in both flavors.
9. **PO (Play):** target audience 18+ to stay outside the Families policy — confirm.
10. Owner 07: the nightly `bmgr` check runs on API 29 and 36 with android-emulator-runner (uninstall/reinstall is impossible in-process); 07's "GMD API 31 and 36" should read so.
11. Owner 08: set `testTagsAsResourceId = true` on the root scaffold; add `ReportDrawnWhen` on the first Feeds page.
12. Owner 01: committed `neutrodyne.acraMailto` and `ACRA_MAILTO = ""` on `debug`; AboutLibraries offline mode; `isCrunchPngs = false` and `vcsInfo { include = false }` on `release`; `BuildInfo.shippedLocales`; the `Text("…")` literal pattern in `checkBannedApis`; `neutrodyne.jvm.library` calls `configureNeutrodyneTestTasks()`; a `verifyDependencyPolicy` rule failing on `io.mockk` in any `*AndroidTestRuntimeClasspath`.
13. Owner 05: if the F-Droid dry run flags committed `.zip` fixtures, build `v1_*.zip` at test time from committed directories (same for 04's `.html.gz`).
14. Unverified, checked in the named milestone: GMD below API 27 and `ps16k` image sources (M0); AGP 9.4 managed-device DSL names and Kotlin in Android test fixtures (M0); Robolectric default-locale override and pseudo-locale qualifiers (M0/M2); compose-rules `.editorconfig` keys (M0); `generateLocaleConfig` with `localeFilters` (M0); ACRA administrator mutation semantics, `setEnabled` accessor and the `play` framework-dialog interplay (M11); a second `NewPipe.init` (M9); plugin-created build-type source sets (M10); GitHub issue-form pre-fill (M11); Renovate hosted wrapper regeneration (M0); emulator `-tcpdump` (M11); developer-verification key-proof mechanics and PEPK flags (M11); IzzyOnDroid size limit and fdroidserver local-build image (M11); Weblate statistics API (M11).

---

## Sources

All checked 2026-10-04 by the research behind this plan unless marked otherwise.

Testing and build:
- JUnit 5/6 on Android and its API 35+ instrumentation requirement — https://github.com/mannodermaus/android-junit5
- Compose testing v2 APIs — https://developer.android.com/develop/ui/compose/testing/migrate-v2
- Compose accessibility testing — https://developer.android.com/develop/ui/compose/accessibility/testing
- Robolectric 4.17 (SDK 37 support; JDK 21 for SDK 36 since 4.16) — https://github.com/robolectric/robolectric/releases
- Roborazzi 1.76.0 — https://github.com/takahirom/roborazzi
- Paparazzi status — https://github.com/cashapp/paparazzi/blob/master/CHANGELOG.md
- Compose Preview Screenshot Testing (alpha) — https://developer.android.com/studio/preview/compose-screenshot-testing
- Media3 `TestPlayerRunHelper` — https://developer.android.com/reference/kotlin/androidx/media3/test/utils/robolectric/TestPlayerRunHelper
- Media3 test-utils POM (pulls MockWebServer 4.12, Robolectric 4.16) — https://dl.google.com/android/maven2/androidx/media3/media3-test-utils/1.11.1/media3-test-utils-1.11.1.pom
- Coil testing (`FakeImageLoaderEngine`) — https://coil-kt.github.io/coil/testing/
- Room 3 releases and migration testing — https://developer.android.com/jetpack/androidx/releases/room3 · https://developer.android.com/training/data-storage/room/migrating-db-versions
- Gradle Managed Devices — https://developer.android.com/studio/test/managed-devices
- ATD and API 37 system images — https://dl.google.com/android/repository/sys-img/aosp_atd/sys-img2-3.xml · https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml
- AGP 9.0 defaults (tested build type only, R8 strict keep rules, GMD replaces device providers) — https://developer.android.com/build/releases/agp-9-0-0-release-notes
- R8 and shrinking — https://developer.android.com/build/shrink-code · https://developer.android.com/build/releases/past-releases/agp-8-0-0-release-notes
- Baseline profiles — https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
- Testing backup and restore (`bmgr`) — https://developer.android.com/identity/data/testingbackup
- TestParameterInjector — https://repo1.maven.org/maven2/com/google/testparameterinjector/test-parameter-injector/maven-metadata.xml
- OkHttp / MockWebServer 5.5.0 — https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml

CI and tooling:
- GitHub-hosted runners (4 vCPU / 16 GB / 14 GB for public repositories) — https://docs.github.com/en/actions/reference/runners/github-hosted-runners
- Hardware-accelerated Android emulation on Linux runners — https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/
- setup-gradle v6 (basic vs enhanced caching, wrapper validation) — https://github.com/gradle/actions/blob/main/docs/setup-gradle.md · https://github.com/gradle/actions/releases
- android-emulator-runner — https://github.com/ReactiveCircus/android-emulator-runner
- Renovate Gradle manager and wrapper advisory — https://docs.renovatebot.com/modules/manager/gradle/ · https://www.vulncheck.com/advisories/renovate-before-44.14.7-command-injection-via-gradle-wrapper
- Dependabot version catalogs and lockfiles — https://github.blog/changelog/2023-03-13-dependabot-version-updates-keeps-gradle-version-catalogs-up-to-date/ · https://github.blog/changelog/2025-06-24-dependabot-support-for-gradle-lockfiles-is-now-generally-available/
- detekt compatibility table — https://detekt.dev/docs/introduction/compatibility/
- Licensee — https://github.com/cashapp/licensee
- Kotlin Gradle plugin compatibility — https://kotlinlang.org/docs/gradle-configure-project.html
- Gradle current version — https://services.gradle.org/versions/current

Distribution, signing and policy:
- F-Droid inclusion policy, anti-features, reproducible builds, metadata descriptions — https://f-droid.org/docs/Inclusion_Policy/ · https://f-droid.org/docs/Anti-Features/ · https://f-droid.org/docs/Reproducible_Builds/ · https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/
- F-Droid precedents (NewPipe `NonFreeNet`, ACRA by email; AntennaPod) — https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/org.schabi.newpipe.yml · https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/de.danoeh.antennapod.yml · https://f-droid.org/en/packages/org.schabi.newpipe/
- F-Droid and the dependency-info signing block — https://forum.f-droid.org/t/build-fails-with-found-extra-signing-block/29220 · https://gitlab.com/fdroid/admin/-/issues/367
- F-Droid build farm hardware (AGP 8.12 issue resolved) — https://gitlab.com/fdroid/admin/-/issues/593
- Reproducibility study (F-Droid 94 %, IzzyOnDroid 35.8 %) — https://arxiv.org/abs/2607.01890
- IzzyOnDroid — https://apt.izzysoft.de/fdroid/index/info
- Obtainium — https://github.com/ImranR98/Obtainium · https://potatoenergy.ru/en/blog/android/obtainium-guide/
- Play target API requirement — https://developer.android.com/google/play/requirements/target-sdk
- Play App Signing (own key before first release) — https://support.google.com/googleplay/android-developer/answer/9842756
- Play Device and Network Abuse; Intellectual Property — https://support.google.com/googleplay/android-developer/answer/9888379 · https://support.google.com/googleplay/android-developer/answer/9888072
- Play FGS declarations — https://support.google.com/googleplay/android-developer/answer/13392821
- Play Data safety — https://support.google.com/googleplay/android-developer/answer/10787469
- 16 KB page sizes — https://developer.android.com/16kb-page-size · https://developer.android.com/guide/practices/page-sizes
- Gradle Play Publisher — https://github.com/Triple-T/gradle-play-publisher/releases
- Developer verification (timeline, accounts, multiple keys, ADB exemption, open-source registration) — https://developer.android.com/developer-verification · https://developer.android.com/developer-verification/guides · https://developer.android.com/developer-verification/guides/faq · https://developer.android.com/developer-verification/guides/open-source-app-registration
- Advanced sideloading flow (24-hour wait) — https://www.xda-developers.com/googles-controversial-advanced-flow-sideloading-rolling-out-ahead-of-stricter-developer-verification/
- Doze, App Standby and battery-optimisation exemptions — https://developer.android.com/training/monitoring-device-state/doze-standby
- Unlicense and GPL compatibility — https://en.wikipedia.org/wiki/Unlicense
- NewPipe Extractor (GPL-3.0-or-later, JitPack, Rhino keep rules) — https://github.com/TeamNewPipe/NewPipeExtractor
- YouTube bot challenges from data-centre IPs (secondary) — https://www.technetexperts.com/?p=11741

Privacy, crash reporting, localisation and platform:
- ACRA setup, senders, interactions — https://www.acra.ch/docs/Setup · https://www.acra.ch/docs/Senders · https://www.acra.ch/docs/Interactions
- Hosted Weblate Libre plan; app-store metadata format — https://weblate.org/en/hosting/ · https://docs.weblate.org/en/latest/formats/appstore.html
- Per-app languages — https://developer.android.com/guide/topics/resources/app-languages
- Android 16 behaviour changes (targeting 36; all apps) — https://developer.android.com/about/versions/16/behavior-changes-16 · https://developer.android.com/about/versions/16/behavior-changes-all
- Android 17 behaviour changes and background audio — https://developer.android.com/about/versions/17/behavior-changes-17 · https://developer.android.com/about/versions/17/changes/bg-audio
- Prior-art reliability lessons (position-loss bug class, database growth) — https://github.com/AntennaPod/AntennaPod/releases/tag/3.12.0 · https://github.com/AntennaPod/AntennaPod/issues/4426
