# 09 — Quality and release

> Status: Draft v1, 2026-10-04; revised 2026-10-05 for the product owner's decisions (GitHub Releases only, no developer-verification registration, an in-app updater, the embedded yt-dlp engine, no GPL code anywhere) · Implements: N1 / N3 / N4 / N5 / N8 / N9 / N10 / N11 / N12 (quality and release parts), R2.9 (measurement), R3.9 (engine canary), R6.1–R6.4 (updater behaviour and install guidance; screens in 08) · Milestones: M0–M11 (M9a, M9b, M11a, M11b), M12–M15 · Honours: D2, D3, D59, D60, D61, D62, D63, D76, D77, D78, D79, D80; PO-2, PO-5, PO-8 (resolved 2026-10-05), PO-3, PO-10, PO-14, PO-18, PO-31, PO-33, PO-34, PO-35, PO-36 defaults · Owns: test strategy and infrastructure, CI workflows (including the engine canary workflow), static-analysis gates, dependency updates, versioning, signing, key custody and the key-rotation runbook, GitHub-only distribution and release assets, the in-app updater design, the report-only reproducibility check, developer-verification guidance for unregistered distribution (including the README "Install and update" content), privacy policy and network inventory, crash reporting and diagnostics content, localisation workflow, performance budgets, release checklists

Contents: [Scope](#scope) · [Test strategy](#test-strategy) · [Test infrastructure](#test-infrastructure) · [CI pipelines](#ci-pipelines) · [Static analysis](#static-analysis) · [Dependency updates](#dependency-updates) · [Versioning and signing](#versioning-and-signing) · [Distribution channels](#distribution-channels) · [In-app updater](#in-app-updater) · [Reproducible builds](#reproducible-builds) · [Developer verification](#developer-verification) · [Privacy](#privacy) · [Crash reporting and diagnostics](#crash-reporting-and-diagnostics) · [Localisation](#localisation) · [Performance budgets](#performance-budgets) · [Release checklist](#release-checklist) · [Settings](#settings) · [Delivery by milestone](#delivery-by-milestone) · [New names introduced here](#new-names-introduced-here) · [Open questions](#open-questions) · [Sources](#sources)

---

## Scope

This document is the engineering-process contract: how every other document's code is tested, gated, built, signed, shipped and measured. An implementer (human or AI session) reads it at M0 to set up the machinery, and again whenever a milestone adds a test type, a workflow job or a release.

**Owned here** (other documents link, never restate):

| Topic | Section |
|---|---|
| Test pyramid, per-change test obligations, E2E journey catalogue, flakiness policy | [Test strategy](#test-strategy) |
| `neutrodyne.android.testing` content, shared test helpers, `:core:testing` inventory, fakes and contract tests, Robolectric/Room/MockWebServer/Media3/WorkManager/Roborazzi/GMD configuration, recorded-response and fixture policy | [Test infrastructure](#test-infrastructure) |
| GitHub Actions workflows `ci.yml`, `nightly.yml`, `release.yml`, `engine-canary.yml` (the workflow; its test content is 04's), helper workflows, CI scripts, caching, hardening, secrets and environments, required checks | [CI pipelines](#ci-pipelines) |
| Lint, Spotless/ktlint/compose-rules, detekt, `.editorconfig`, build-output checks, PR template | [Static analysis](#static-analysis) |
| Renovate configuration and update review rules | [Dependency updates](#dependency-updates) |
| Version scheme procedure, `scripts/release.sh`, changelogs, key ceremony and custody, signing schemes, key loss and the v3.1 rotation runbook | [Versioning and signing](#versioning-and-signing) |
| GitHub Releases as the only channel: release assets, immutable releases, provenance attestations, release body, Obtainium, beta channel, mirror | [Distribution channels](#distribution-channels) |
| `:update:api`/`:update:impl` behaviour: update manifest, checks, download, verification, install sessions per API level, idle gate, verification-failure mapping, installer of record, notices | [In-app updater](#in-app-updater) |
| Reproducibility hygiene, nightly report-only reproducibility check | [Reproducible builds](#reproducible-builds) |
| Unregistered distribution: phases, affected devices, the advanced flow, fallbacks, notice timing, watch cadence, README "Install and update" content | [Developer verification](#developer-verification) |
| `PRIVACY.md`, network inventory, redaction surfaces, `SECURITY.md` | [Privacy](#privacy) |
| ACRA configuration, `CrashReporter`/`CrashContext`, diagnostics screen content and actions | [Crash reporting and diagnostics](#crash-reporting-and-diagnostics) |
| Weblate, string conventions, shipped locales, pseudo-locales | [Localisation](#localisation) |
| Reference devices, budgets (including per-ABI APK sizes and the YouTube engine's latency and memory), Macrobenchmark wiring, baseline/startup profiles | [Performance budgets](#performance-budgets) |
| Checklists per release type and the v1.0 gate | [Release checklist](#release-checklist) |

**Not covered here:** the version catalog and convention-plugin skeletons ([01 Toolchain and versions](01-foundation.md#toolchain-and-versions)); build types, ABI splits and the no-engine switch ([01 Build variants and ABIs](01-foundation.md#build-variants-and-abis)); Gradle-side policy tasks `assertModuleGraph`, `verifyDependencyPolicy`, `verifyManifestPermissions`, `checkSpdxHeaders`, `checkBannedApis`, the Licensee allow-list ([01 Licensing and dependency policy](01-foundation.md#licensing-and-dependency-policy)) and `checkPythonLicences`, `verifyBundledYtDlp`, `shimTest` ([01 Python and native components](01-foundation.md#python-and-native-components)) — 09 only decides when CI runs them; the logging `Redactor` algorithm ([01 Logging and redaction](01-foundation.md#logging-and-redaction)); what each feature area tests (the `## Testing` sections of [02](02-data-model.md#testing), [03](03-feeds-and-discovery.md#testing), [04](04-youtube.md#testing), [05](05-groups-opml-backup.md#testing), [06](06-playback.md#testing), [07](07-downloads.md#testing), [08](08-ui-ux.md#testing)); the diagnostics screen's visuals ([08 Diagnostics](08-ui-ux.md#diagnostics)); the updater's screens, notices and wording ([08 Updates settings](08-ui-ux.md#updates-settings), [08 Install and updates help](08-ui-ux.md#install-and-updates-help)); YouTube legal texts, the engine-update trust chain, what the engine canary tests and the hotfix runbook ([04 Licensing and legal](04-youtube.md#licensing-and-legal), [04 Engine updates](04-youtube.md#engine-updates), [04 Engine canary](04-youtube.md#engine-canary), [04 Maintenance and hotfix process](04-youtube.md#maintenance-and-hotfix-process)); the playback- and download-busy signals the updater waits on ([06 App updates and playback](06-playback.md#app-updates-and-playback), [07 App update](07-downloads.md#app-update)); Auto Backup rules ([05 Auto Backup](05-groups-opml-backup.md#auto-backup)).

**Repository files owned by this document** (created in the milestone shown in [Delivery by milestone](#delivery-by-milestone)): `.editorconfig`, `.github/workflows/{ci,nightly,release,engine-canary,record-screenshots,baseline-profile}.yml`, `.github/PULL_REQUEST_TEMPLATE.md`, `.github/ISSUE_TEMPLATE/{bug.yml,feature.yml,release.md}`, `renovate.json`, `config/detekt/detekt.yml`, `app/lint-baseline.xml`, `app/policy/locales.txt`, `changelogs/<versionCode>.txt` (one per release), `scripts/release.sh`, `scripts/ci/*.sh` (including `make-update-json.sh` and `check-update-json.sh`), `scripts/l10n/update-shipped-locales.sh`, `PRIVACY.md`, `SECURITY.md`; the content of the README's "Install and update" section ([Developer verification](#developer-verification); the README itself is maintained with the PLAN). `scripts/engine/*.sh` and `scripts/youtube/record-responses.sh` are 04's; the workflows here call them. `CONTRIBUTING.md` content is 01's ([Copied code and contributions](01-foundation.md#copied-code-and-contributions)); 09 adds the testing and release sections.

---

## Test strategy

Serves N1, N9, N11, N12 and every milestone's acceptance criteria. Delivered from M0; each milestone adds the tests its documents list. Honours [D59](../PLAN.md#3-key-decisions).

### Principles

1. **Fakes over mocks.** Every `:core:domain` and `*:api` interface has a hand-written fake in `:core:testing`; MockK is allowed only in JVM `src/test` for final third-party classes, never in `src/androidTest` ([Test infrastructure](#coretesting-inventory)).
2. **The JVM is the default runner.** Pure logic runs on the plain JVM; Android-dependent logic (Room, WorkManager, Media3, Compose) runs under Robolectric; only behaviour Robolectric cannot reproduce (bundled SQLite driver, Keystore, FGS/UIDT, audio hardening, 16 KB pages, process death, real decoders at scale) runs on emulators.
3. **Goldens make format code reviewable.** Parsers, writers and codecs compare against committed golden JSON/XML; screenshots are committed PNGs. Both are rewritten only by an explicit switch.
4. **Nothing in a blocking job touches the internet.** Feeds, directories, YouTube and GitHub's release endpoints are served by MockWebServer or replayed from recorded responses; live canaries run nightly and never block. The engine canary downloads yt-dlp's release files (verified like the app does) but tests them only against recorded responses.
5. **Deterministic time and locale.** No production code reads the wall clock directly (01 `Clock`); tests use `TestClock` and run in `de_DE` / `America/St_Johns` to flush out locale and offset bugs.
6. **Every bug fix starts with a failing test** reproducing it, in the lowest layer that can show it.

### Test pyramid

| Layer | What it covers | Modules | Runner | Trigger |
|---|---|---|---|---|
| Pure JVM | parsers and writers with goldens, identity keys, URL rules, classifiers, selectors, `Redactor`, rule tables, mutation robustness | `:core:model`, `:core:common`, `:core:domain`, `:feeds`, `:*:api` | JVM (`test`) | every PR |
| Local unit (Android module, no Android API) | ViewModels with fakes and Turbine, mappers, planners with fakes | `:feature:*`, `:core:ui`, impl modules | JVM with `android.jar` stubs (`isReturnDefaultValues = false`) | every PR |
| Robolectric | DAOs and repositories on an in-memory `TestDb`, workers with the WorkManager test driver, Media3 player logic with `media3-test-utils`, Compose UI tests (v2 rule + accessibility checks), Roborazzi screenshots, Hilt graph tests | Android modules | JVM + Robolectric 4.17, `sdk=36` | every PR |
| Instrumented (GMD `ci` group) | migrations on both drivers, platform XML parser corpus, Keystore, `MediaController` ↔ service, E2E journeys, external-mode YouTube UI (E8), the `:ytx` process (`YtxProcessStartTest`, `YtxIsolationTest`), the APK inspection of the updater (`ApkInspectorTest`) | `:app`, `:core:database`, `:core:data`, `:playback:impl`, `:download:impl` | Gradle Managed Devices API 26 + 36 | push to `main`, PRs labelled `run-instrumented`, nightly |
| Instrumented nightly-only | long-running and API-specific behaviour (30-min background auto-advance, 20-min UIDT beside playback, API 33 `dataSync`, API 37 hardening and 16 KB, `selftest` in `:ytx` on the 16 KB image), the minified `release` APK (E7 through `:ytx`) | same | GMD `nightly` group + emulator-runner API 37 | nightly, release candidates |
| Out-of-process system | process death and journeys driven by UI Automator from a separate test APK; Macrobenchmark dry runs | `:benchmark` | GMD (`aosp` image) | nightly (from M6 / M11) |
| Python (shim) | `neutrodyne_ytx` against the bundled yt-dlp through `ReplayRH`: recorded scenarios, error-code mapping, option names, `selftest` API probe (04) | `:youtube:ytdlp` (`shimTest`) | pytest on a host CPython of the target minor version | every PR |
| Device and manual | Bluetooth, AVRCP, Android Auto DHU, Wear, OEM restrictions, performance budgets on the reference device (including the engine budgets), accessibility passes, `bmgr` spot checks, self-updates per API level and the developer-verification test hook ([In-app updater](#device-checklist-m11a)) | — | reference device + checklists | per milestone and [release](#release-checklist) |
| Engine canary | each new yt-dlp stable release, verified like the app verifies it, against the shim's recorded responses and API probe (04) | `:youtube:ytdlp` | [`engine-canary.yml`](#engine-canaryyml), host CPython | every 6 h; green approves the release |
| Live canaries | ~30 public feeds (03), YouTube smoke through the shim (04), the shim against yt-dlp's nightly build (informational) | `:feeds`, `:youtube:ytdlp` | JVM / host CPython with network | nightly, non-blocking |

**Placement rule:** `src/androidTest` exists only in the five modules named in the instrumented row (plus `:benchmark`, which is a test module). Feature Compose UI tests run under Robolectric; this keeps the number of emulator boots per CI run bounded (each module's managed-device task boots its own emulator, and modules without `src/androidTest` have their device-test component disabled, [Gradle Managed Devices](#gradle-managed-devices)).

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
| Python shim (`neutrodyne_ytx`) | a `shimTest` case through `ReplayRH` against the bundled yt-dlp; requests that changed are re-recorded ([04 Recorded responses](04-youtube.md#recorded-responses)), never patched into the recording |
| Code that verifies or installs code (`:update:impl`, 04's engine updates, release scripts) | a negative test for every rejection reason it implements (each must leave the installed app or the active engine untouched); for `release.yml` and its scripts, `check-update-json.sh` run on the changed output in the PR |
| Playback | `media3-test-utils` test (Robolectric); instrumented session test if it touches the service lifecycle |
| Platform-dependent behaviour (FGS, UIDT, audio focus/hardening, backup, notifications permission) | nightly instrumented test **or** a row in the owning document's manual device checklist, named in the PR |
| New user journey in the [E2E catalogue](#end-to-end-journeys) | the journey test |
| New user-visible string | resource in the module's `strings.xml`; plural via `<plurals>`; no concatenation ([Localisation](#string-conventions)) |
| Bug fix | regression test that fails before the fix |

### End-to-end journeys

E0–E9 run on GMD (`:app/src/androidTest`) against the **real** `NeutrodyneApplication` and Hilt graph (no `HiltTestApplication`); E10 runs out of process in `:benchmark` ([Out-of-process system tests](#out-of-process-system-tests)). Network comes from an on-device MockWebServer bound to `127.0.0.1` (cleartext is allowed by [D28](../PLAN.md#3-key-decisions)'s network security config; `LocalNetworkGuardDns` passes loopback). Data enters through the UI, public intents or `TestSeeder`. Journeys use `createAndroidComposeRule<MainActivity>()` with `enableAccessibilityChecks()`, so every screen a journey visits is also checked on a device ([N4](../PLAN.md#22-non-functional-requirements)). Journeys follow the [ATD rule](#gradle-managed-devices): no notification shade, lock screen, launcher or back gesture.

| ID | Test class | Journey | Asserts | From | Devices |
|---|---|---|---|---|---|
| E0 | `SmokeTest` | launch | five labelled destinations, rotation, dark-mode switch, back from Settings with `pressBack()`, About shows version and ABI, Licences non-empty (PLAN M0 AC4–5). The predictive-back animation of M0 AC4 is checked by hand on a gesture-navigation device (ATD images have no SystemUI, so no back gesture) and recorded in the M0 release issue | M0 | `ci`, API 37 |
| E1 | `SubscribeJourneyTest` | M1: Library empty state → "Add by URL" → type `http://127.0.0.1:{port}/feeds/rss2-minimal.xml` → Subscribe → Library → Podcast → Episode. From M7 a second case enters through `neutrodyne://subscribe?url=…` (the VIEW filters ship in M7, [03 Deep links and share targets](03-feeds-and-discovery.md#deep-links-and-share-targets)) | tile with cover or monogram; paged episodes; show notes rendered; refresh via pull-to-refresh hits the server with `If-None-Match` | M1 (deep link M7) | `ci` |
| E2 | `GroupFeedJourneyTest` | create group "tech" → add podcast → Feeds tab "tech" | episodes newest first; podcast in "tech" and "news" appears in both and once in All (M2 AC4); `ActivityScenario.recreate()` keeps the selected tab | M2 | `ci` |
| E3 | `PlayJourneyTest` | play from a group feed → mini player → background the app by starting the test APK's `BackgroundStandInActivity` (ATD has no launcher) → pause through the media notification: find it with `NotificationManager.getActiveNotifications()` and fire the pause action's `PendingIntent` → reopen | media notification on channel `playback`; `MediaController.mediaMetadata.artworkUri` is `content://…artwork/…`; position persisted (`episode_position` > 0 after pause); "Play group" order (M4 AC6) | M4 | `ci` |
| E4 | `DownloadJourneyTest` | download a throttled 5 MB enclosure → progress → complete → stop the server → play | file under `Android/data/…/Podcasts/`; plays with the server down (M6 AC6) | M6 | `ci` |
| E5 | `ImportJourneyTest` | VIEW a `content://` OPML (provider in the test APK) via `ExternalImportActivity` → preview → confirm | 20 pending tiles within 2 s; all fetched; report shows one `NOT_A_FEED`; zero `download` rows | M3 | `ci` |
| E6 | `BackupRestoreJourneyTest` | seed state A → create backup → mutate (delete group, mark played) → Replace restore | state A restored: groups, memberships, played, positions, Up next (M3 AC5) | M3 | `ci` |
| E7 | `YouTubeReleaseSmokeTest` | seeded YouTube podcast (`TestSeeder`) → play → download | minified `release`: R8 kept the classes Python reaches through Chaquopy (`PyHttp`); one resolve and one chunked download through `:ytx` with `ReplayRH` + a MockWebServer googlevideo stand-in ([04 Testing](04-youtube.md#testing), PLAN M9 AC9) | M9 (M9a) | nightly `api36` release leg and API 37 16 KB, release |
| E8 | `ExternalYouTubeModeTest` (04's) | seeded YouTube rows in external mode (every build until M9a; from M9a the test first sets `youtube.engine_enabled = false`) | "Watch on YouTube" fires `ACTION_VIEW` (captured with `Instrumentation.ActivityMonitor`); no queue/download actions; "Play group" skips them; Settings › YouTube shows the external reason (PLAN M8 AC5, M9 AC8) | M8 | `ci` |
| E9 | `FirstLaunchRestoreTest` | snapshot file + empty DB | 05's assertions ([05 Testing](05-groups-opml-backup.md#testing)) | M3 | `ci` |
| E10 | `ProcessDeathResumeTest` | kill the app mid-download from `:benchmark` | 07's assertions ([07 Instrumented and device tests](07-downloads.md#instrumented-and-device-tests)) | M6 | nightly |

E7 guards itself with `assumeTrue(BuildInfo.youTubeEngineBundled)`, so it skips on the no-engine build and on 32-bit images; E8 runs everywhere. `PlaybackServiceTest` (06), `UidtDownloadTest` and `DataSyncWorkerTest` (07) and the migration tests (02) are not journeys but run in the same instrumented jobs; their devices are listed in [Gradle Managed Devices](#gradle-managed-devices).

**E7 seam:** the engine's HTTP goes through `NeutrodyneOkHttpRH` inside `:ytx`, which an androidTest APK cannot reach with a Hilt override — and Hilt test overrides would not work against an R8-minified APK anyway. When the instrumentation argument `ytxReplay` is set (`-Pandroid.testInstrumentationRunnerArguments.ytxReplay=true` on the nightly release leg), E7 copies 04's `ReplayRH` and one recorded scenario from its androidTest assets into `noBackupFilesDir/ytx-test/` and sets `YtxTestHooks.replayDir` (a `@VisibleForTesting` field of `:youtube:ytdlp`, kept by its consumer rules, [01 Build variants and ABIs](01-foundation.md#build-variants-and-abis); [04 Process and lifecycle](04-youtube.md#process-and-lifecycle) owns the class) before the first bind; `YtDlpClient` passes the directory to `YtxService` in the bind `Intent`, and `YtxPython` registers the replay handler above `NeutrodyneOkHttpRH`'s preference. The recorded player response is rewritten so stream URLs point at the test's MockWebServer. Only code running as the app's UID can set the hook (`YtxService` is not exported); production code never sets it. Unverified: that R8 keeps the field with a consumer rule alone (M9a check; fallback: the same hook read from a marker file in `noBackupFilesDir/ytx-test/`).

### Untrusted-input robustness

Serves N9. Two complementary layers:

1. **Hostile inputs with caps** — owned by the format documents: [03 Golden corpus](03-feeds-and-discovery.md#golden-corpus-feedssrctestresourcesfeeds) (entity DOCTYPE, deep nesting, oversized text) and 05's `HostileInputTest` (billion laughs, 10k nesting, 100k outlines, 10 MB attribute, zip bomb, zip-slip; PLAN M3 AC3).
2. **`MutationRobustnessTest`** (09, `:feeds` and `:youtube:api`, JVM, TestParameterInjector): for every committed fixture of `FeedParser`, `OpmlReader`, `BackupCodec`, `NewPipeSubscriptions`, `LibreTubeBackupParser`, `TakeoutSubscriptionsParser` and for 200 seeded random strings of `YouTubeUrlClassifier`, apply seeded mutations — truncate at 10 offsets, flip 1–16 random bytes, duplicate a random 1 KB slice, insert `<!DOCTYPE x [<!ENTITY e "...">]>`, replace the declared encoding, insert NUL and lone surrogates. Assert: the call returns its declared result type (`ParseResult`/`Outcome` failure is fine), throws nothing except `CancellationException`, finishes in < 2 s, and stays within a 128 MB heap (enough for the largest committed fixture of ≤ 1 MB plus the test worker; an entity or nesting blow-up exceeds it at once). The class runs only in a dedicated `Test` task `mutationTest` (registered by `configureNeutrodyneTestTasks()` in modules that have the class: same `testClassesDirs` and `classpath` as `test`, `includeTestsMatching` for `*MutationRobustnessTest` and `*HostileInputTest` — 05's `HostileInputTest` needs the same heap cap — `maxHeapSize = "128m"`, wired into `check`); the regular `test` task excludes both. PR runs use 20 mutations per fixture (seed = fixture name hash); nightly runs 1,000 per fixture (`-PmutationIterations=1000`) and prints the failing seed.

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
internal fun Project.configureNeutrodyneTestTasks() {
    tasks.withType<Test>().configureEach {
        systemProperty("user.timezone", "America/St_Johns")          // UTC-3:30, DST, non-integral offset
        jvmArgs("-Duser.language=de", "-Duser.country=DE", "-Xshare:off", "-XX:+EnableDynamicAgentLoading")
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        maxHeapSize = if (name == "mutationTest") "128m" else "2g"   // set here, not in register {}: no ordering doubt
        val update = providers.gradleProperty("updateGoldens").isPresent
        systemProperty("neutrodyne.updateGoldens", update)
        systemProperty("neutrodyne.moduleDir", layout.projectDirectory.asFile.absolutePath)
        systemProperty("neutrodyne.rootDir", rootProject.layout.projectDirectory.asFile.absolutePath)
        systemProperty("neutrodyne.screenshotTier", providers.gradleProperty("screenshotTier").getOrElse("pr"))
        systemProperty("neutrodyne.mutationIterations", providers.gradleProperty("mutationIterations").getOrElse("20"))
        if (update) outputs.upToDateWhen { false }
        testLogging { events("failed"); exceptionFormat = TestExceptionFormat.FULL }
    }
    if (path == ":feeds" || path == ":youtube:api") {                // JVM modules that own MutationRobustnessTest
        val test = tasks.named<Test>("test")
        val capped = listOf("*MutationRobustnessTest", "*HostileInputTest")   // HostileInputTest: 05's caps test
        test.configure { capped.forEach(filter::excludeTestsMatching) }
        val mutation = tasks.register<Test>("mutationTest") {
            testClassesDirs = test.get().testClassesDirs; classpath = test.get().classpath
            useJUnit(); capped.forEach(filter::includeTestsMatching)
        }
        tasks.named("check") { dependsOn(mutation) }
    }
}
```

```kotlin
// build-logic: neutrodyne.android.testing (Android modules only). Catalog access per 01 catalog rule 5 (no type-safe
// accessors in plugin classes); okhttp-bom is already on test/androidTest configurations (01 catalog rule 4).
internal fun Project.configureAndroidTesting(ext: CommonExtension) {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    ext.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ext.defaultConfig.testInstrumentationRunnerArguments["clearPackageData"] = "true"
    if (providers.gradleProperty("neutrodyne.testScope").getOrElse("ci") == "ci")   // nightly/release scopes run everything
        ext.defaultConfig.testInstrumentationRunnerArguments["notAnnotation"] =
            "ch.lkmc.neutrodyne.core.testing.Nightly,androidx.test.filters.FlakyTest"
    ext.testOptions.unitTests.isIncludeAndroidResources = true   // Robolectric + Roborazzi need merged resources
    ext.testOptions.unitTests.isReturnDefaultValues = false      // unmocked android.* calls fail loudly
    ext.testOptions.animationsDisabled = true
    ext.testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"
    configureManagedDevices(ext.testOptions.managedDevices)      // see Gradle Managed Devices
    disableEmptyDeviceTests()                                    // see Gradle Managed Devices
    configureNeutrodyneTestTasks()
    dependencies {
        "testImplementation"(libs.findBundle("unit-test").get())  // junit4, truth, turbine, coroutines-test, TPI
        "testImplementation"(libs.findLibrary("robolectric").get())
        "androidTestImplementation"(libs.findLibrary("androidx-test-runner").get())
        "androidTestImplementation"(libs.findLibrary("androidx-test-ext-junit").get())
        "androidTestImplementation"(libs.findLibrary("truth").get())
        "androidTestUtil"(libs.findLibrary("androidx-test-orchestrator").get())
        if (path != ":core:testing") { "testImplementation"(project(":core:testing")); "androidTestImplementation"(project(":core:testing")) }
    }
    val robolectric = libs.findVersion("robolectric").get().requiredVersion
    configurations.configureEach { resolutionStrategy.eachDependency {   // media3-test-utils pulls Robolectric 4.16
        if (requested.group == "org.robolectric" && !requested.name.startsWith("android-all")) useVersion(robolectric)
    } }
}
```

- `src/test/resources/robolectric.properties` in every Android module: `sdk=36` (one `android-all-instrumented` jar, ~100 MB, cached in CI). Bump to 37 only together with a full Roborazzi re-record.
- Robolectric 4.16+ needs JDK 21 for SDK 36; CI uses Temurin 21 ([01](01-foundation.md#version-table)).
- AGP 9 creates unit tests only for the tested build type (`debug`): every module, `:app` included, runs `testDebugUnitTest` once (there are no product flavors, [D2](../PLAN.md#3-key-decisions)).
- `:youtube:ytdlp:shimTest` (01's task, 04's content) is not a JVM `Test` task: it runs pytest on a host CPython of the minor version Chaquopy packages (3.14, fallback 3.13 per S7), with `ReplayRH` and the bundled yt-dlp on `sys.path`. CI provides the interpreter with `actions/setup-python` ([ci.yml](#ciyml)); locally a missing interpreter fails the task with a message, never silently skips.
- Unverified: Robolectric replaces the JVM default locale with the qualifier locale (`en-rUS`), which would neutralise `-Duser.language=de` in Robolectric tests; tests that guard wire formats against locale bugs therefore live on the plain JVM or use `@Config(qualifiers = "de-rDE")` explicitly (M0 check).

### Shared helpers

`:core:testing` is an Android library and cannot be a dependency of the pure-JVM modules. The three helpers JVM tests also need are authored as **test fixtures of `:core:common`** (`java-test-fixtures` plugin, sources in `core/common/src/testFixtures/kotlin/ch/lkmc/neutrodyne/core/testing/`, package `ch.lkmc.neutrodyne.core.testing`) and re-exported by `:core:testing` with `api(testFixtures(project(":core:common")))`, so every module imports them from the same package. JVM modules declare `testImplementation(testFixtures(project(":core:common")))`; test edges are not asserted by the module graph ([01 Dependency rules](01-foundation.md#dependency-rules) rule 13).

```kotlin
package ch.lkmc.neutrodyne.core.testing

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

Package `ch.lkmc.neutrodyne.core.testing`; dependencies per rule 9 (`:core:{domain, model, common}`, `:*:api`); `api` exports junit4, truth, turbine, coroutines-test, coil-test, hilt-android-testing ([01 Module layout](01-foundation.md#module-layout)).

| Item | Fakes / provides | Interface owner | From |
|---|---|---|---|
| `MainDispatcherRule`, `TestClock`, `Goldens` (re-exported) | — | 09 | M0 |
| `FakeNetworkMonitor` | `NetworkMonitor` (`setStatus(...)`) | 01 | M0 |
| `FakeSettingsRepository` | `SettingsRepository` (in-memory map per file) | 01 | M0 |
| `FakeCrashReporter`, `FakeCrashContext` | [`CrashReporter`, `CrashContext`](#crashreporter-and-crashcontext) | 09 | M0 |
| `FakePodcastRepository`, `FakeEpisodeRepository`, `FakeRefreshController`, `FakeAddPodcastResolver`, `FakeSubscribeUseCase`, `FakeIngestionEvents` | 03 interfaces | 03 | M1 |
| `FakeSearchRepository` | `SearchRepository` | 03 | M7 |
| `FakeFeedRepository`, `FakeGroupRepository`, `FakeEffectiveSettingsResolver`, `FakeScopeSettingsRepository`, `FakePlayContextResolver` | 05 interfaces | 05 | M2 (resolver M2/M4) |
| `FakeImportRepository`, `FakeBackupRepository`, `FakeExportRepository` | 05 interfaces | 05 | M3 |
| `FakeEpisodeLiveStateSource`, `FakeArtworkRepository` | 08 interfaces | 08 | M2, M4 |
| `FakePlaybackController`, `FakePlaybackStateSource`, `FakeQueueRepository`, `FakeChapterRepository`, `FakePlaybackMaintenance` | 06 interfaces | 06 | M4 (chapters M5) |
| `FakeDownloadController`, `FakeLocalMediaIndex`, `FakeDownloadProgressSource` | 07 interfaces | 07 | M6 |
| `FakeYouTubeChannelResolver`, `FakeYouTubeStreamResolver`, `FakeYouTubeEnricher`, `FakeYouTubeChannelRepository`, `FakeYouTubeHealth`, `FakeYouTubeChannelSearch`, `FakeExtractorChannelLookup`, `FakeYouTubeAvailabilityRecorder`, `FakeYouTubeCapabilitiesSource`, `FakeYouTubeEngine`, `testCapabilities(external: ExternalReason? = null)` (all five capabilities true when null) | 04 interfaces | 04 | M2 (capabilities), M8, M9 |
| `FakeAppUpdater`, `FakeUpdateNotices` | [`:update:api`](#modules-and-api) | 09 | M11a |
| `FakeDiagnosticsRepository` | [`DiagnosticsRepository`](#diagnostics-api) | 09 | M11 |
| Data builders `podcast(…)`, `episode(…)`, `episodeRow(…)`, `group(…)`, `rowLive(…)` | `:core:model` instances with readable defaults | 09 | M1 |
| `fakeImageLoader(context)` | Coil `ImageLoader` with `FakeImageLoaderEngine` returning deterministic `ColorImage`s keyed by URL hash | 09 | M1 |
| `Nightly` annotation, `ScreenshotTier` | test selection | 09 | M0, M2 |
| `*Contract` bases | [fake contract tests](#fake-contract-tests) | 09 | with each fake |

Rule: an interface added to `:core:domain` or `*:api` by any document gets its `Fake<Name>` in the same PR. Fakes are backed by `MutableStateFlow`, expose test-only mutators (`emit…`, `failNext: <ErrorType>?`) and a call log (`calls: List<String>`), and never sleep.

**Not in `:core:testing`** (rule 9 forbids the dependencies):

| Helper | Location | Consumers |
|---|---|---|
| `TestDb`, `SeedDatabase`, `FeedFixture`, `db/v1-fixture.sql` (02's names) | `:core:database` **Android test fixtures** (`android { testFixtures { enable = true } }`, `core/database/src/testFixtures/`, as 02 places them) — so other modules can consume them | `testImplementation(testFixtures(project(":core:database")))` in `:core:data`, `:core:artwork`, `:playback:impl`, `:download:impl`, `:app` |
| `RecordingAppNavigator` (records `push`/`selectTab`/`pop`) | `:core:navigation` test fixtures | feature Compose tests via `LocalAppNavigator` |
| `RecordingRH`, `ReplayRH` (04's names; Python, never packaged), `FakeYtDlpClient` (Kotlin) | `:youtube:ytdlp` test sources; recordings in `youtube/ytdlp/src/test/resources/recorded/{scenario}/` | `shimTest`, `scripts/youtube/record-responses.sh`, the engine canary, `:youtube:ytdlp` JVM tests, `:app` `androidTest` (E7 copies `ReplayRH` and one scenario into its assets at build time) |
| `@TestInstallIn` Hilt modules (`TestSqliteDriverModule` and fake bindings) | `:app/src/test/kotlin/ch/lkmc/neutrodyne/di/` | Robolectric `@HiltAndroidTest` tests in `:app` |
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
- Every Compose test calls `enableAccessibilityChecks()` (`ui-test-junit4-accessibility`); rules and custom-action checks are 08's ([08 Automated checks](08-ui-ux.md#automated-checks)). Unverified: Google documents these checks for instrumented `AndroidComposeTestRule` tests; whether the Accessibility Test Framework reports every check under Robolectric (contrast needs a rendered frame, hence `GraphicsMode.NATIVE`) is checked in M1 with a deliberately broken component (a 20 dp clickable without a label must fail the Robolectric test). Fallback: Robolectric keeps the checks that do fire, and each screen gets one instrumented `<Screen>AccessibilityTest` in `:app/src/androidTest` rendered with fakes, run in the `instrumented` job (PLAN N4 requires instrumented checks either way; the E2E journeys provide them for the screens they visit).
- **Roborazzi**: each module with screenshot tests applies `alias(libs.plugins.roborazzi)` in its own build file (01 catalog rule 2: tooling plugins are not on the build-logic classpath) and declares `testImplementation(libs.roborazzi, libs.roborazzi.compose, libs.roborazzi.junit.rule)`; `roborazzi { outputDir.set(file("src/test/screenshots")) }`, `roborazzi.record.resizeScale=0.5` in `gradle.properties`. Determinism: `NeutrodyneTheme(dynamicColor = false)`, `TestClock`, fixed locale qualifier, `fakeImageLoader`, animations frozen.
- **Tiers** (decides 08 open question 15): `ScreenshotTier.PR` captures every subject in light and dark at font scale 1.0, LTR, plus one stress variant per subject (dark, 2.0, `ar-XB`). `ScreenshotTier.FULL` adds the remaining columns of [08's matrix](08-ui-ux.md#screenshot-matrix) (pure black, 1.5, 2.0 and RTL for every state; all widths and postures). PR CI verifies `PR`; nightly verifies `FULL`. All reference PNGs (both tiers) are committed. Budget: ≤ 800 images, ≤ 30 MB total; exceeding it requires dropping redundant variants, not Git LFS (keeps contributors' and Weblate's clones simple).

```kotlin
enum class ScreenshotTier { PR, FULL;
    companion object { val current = if (System.getProperty("neutrodyne.screenshotTier") == "full") FULL else PR }
}
fun assumeTier(required: ScreenshotTier) = assumeTrue(ScreenshotTier.current >= required)
```

- **Recording policy:** reference images are recorded only on Linux by the `record-screenshots.yml` workflow (font rasterisation differs on macOS and Windows); it uploads `screenshots-<sha>.zip` and the author applies it with `scripts/ci/apply-screenshots.sh <run-id>` (uses `gh run download`) and commits. PRs verify with `-Proborazzi.test.verify=true`; on failure the `_compare.png` files are uploaded as an artifact. Renovate groups Robolectric, Roborazzi and the Compose BOM so the re-record lands in the same PR.
- Pseudo-locales `en-XA` and `ar-XB` come from `isPseudoLocalesEnabled = true` on the `debug` build type, set by `neutrodyne.android.compose` in every module that has screenshot tests (each module's unit tests merge that module's own debug resources) and by the application plugin in `:app`. A locale filter removes generated pseudo-locales unless they are listed ([pseudolocales](https://developer.android.com/guide/topics/resources/pseudolocales)), so `:app`'s `localeFilters` always contains `en-rXA` and `ar-rXB` ([Shipped locales and per-app language](#shipped-locales-and-per-app-language)). Unverified: Robolectric resolving `@Config(qualifiers = "en-rXA")` to aapt2's generated pseudo-locale resources (M2 check; fallback: capture only `ar` once a real Arabic translation exists, plus manual pseudo-locale review on device).

### Gradle Managed Devices

Configured in `neutrodyne.android.testing` for every Android module; only the five instrumented modules have device tests.

| Name | Device | API | Image | Groups | Used for |
|---|---|---|---|---|---|
| `api26` | Pixel 2 | 26 | GMD `aosp` (`android-26;default`) | `ci`, `nightly` | minSdk floor (PLAN M0 AC4); migrations; E2E |
| `api33` | Pixel 6 | 33 | GMD `aosp-atd` | `nightly` | `DataSyncWorkerTest` (07) |
| `api34` | Pixel 6 | 34 | GMD `aosp-atd` | `nightly` | first UIDT level; `PlaybackServiceTest` (06) |
| `api36` | Pixel 6 | 36 | GMD `aosp-atd` | `ci`, `nightly` | main device; `UidtDownloadTest` (07); E7 (release leg), E8 |
| `bench34` (in `:benchmark` only) | Pixel 6 | 34 | GMD `aosp` (full image: launcher, SystemUI, root) | — | system tests, baseline-profile generation, Macrobenchmark dry runs |
| API 37 16 KB | — | 37 | `system-images;android-37.0;google_apis_ps16k;x86_64` via android-emulator-runner | nightly job `api37-16k` | Android 17 hardening, 16 KB page size (including CPython's libraries in `:ytx`), minified `:app` suite (PLAN M11 AC7) |

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
// Modules without src/androidTest get no device-test component, so `ciGroupDebugAndroidTest` never boots an emulator
// for an empty suite. Unverified accessor name under AGP 9.4 (8.x: HasDeviceTestsBuilder / androidTest.enable).
private fun Project.disableEmptyDeviceTests() = extensions.getByType<AndroidComponentsExtension<*, *, *>>()
    .beforeVariants { v -> (v as? HasDeviceTestsBuilder)?.deviceTests?.get("AndroidTest")?.enable =
        layout.projectDirectory.dir("src/androidTest").asFile.exists() }
```

- **ATD rule.** Automated Test Device images remove SystemUI, the launcher, the Settings app and bundled apps and disable hardware rendering ([GMD](https://developer.android.com/studio/test/gradle-managed-devices)). Tests that may run on an ATD device therefore never use the notification shade, lock screen, Home, recents or the back gesture: they read their own notifications with `NotificationManager.getActiveNotifications()`, fire notification actions through `PendingIntent.send()`, background the app by starting a test-APK activity (`BackgroundStandInActivity`), and press back with `pressBack()`. Anything that needs real system UI (predictive-back animation, lock-screen controls, the resumption card) is a manual or full-image check (`api26`, `bench34`, API 37 job, or the owning document's device checklist). ATD x86_64 images exist for API 30–36 (`aosp_atd` repository XML; the GMD page's "API 30 only" is stale); there is no ATD or `default` image for API 37.
- **API 26 on GMD:** the GMD page says to use API 27 and higher. Unverified whether AGP 9.4 still accepts API 26 (possibly behind `android.experimental.testOptions.managedDevices.allowOldApiLevelDevices=true`); checked in M0. Fallback: an `instrumented-api26` job with android-emulator-runner (`api-level: 26`, `target: default`, `arch: x86_64`) running `connectedDebugAndroidTest`; PLAN M0 AC4 accepts this fallback.
- **API 37:** newer system-image directories carry a minor SDK version (`android-36.1`, `android-37.0`, `android-37.2`). x86_64 API 37 images exist only with Google APIs: `google_apis` / `google_apis_playstore` (4 KB pages, 37.0) and `google_apis_ps16k` / `google_apis_playstore_ps16k` (37.0–37.2) ([repository XML](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml), read 2026-10-05). Unverified whether GMD accepts a minor-versioned Google APIs 16 KB image, hence android-emulator-runner v2.38.0 with `api-level: 37.0`, `target: google_apis_ps16k`, `arch: x86_64`; Unverified that the action accepts a minor-versioned `api-level` (fallback: `scripts/ci/start-emulator.sh` calling `sdkmanager`, `avdmanager` and `emulator` directly).
- **CI flags and parallelism:** `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`; `--max-workers=2` on every job that runs device tests, so at most two emulators exist at once on the 4-vCPU / 16 GB runner (each module's managed-device task boots its own emulators and Gradle would otherwise run several modules in parallel). No test sharding: the per-module suites are small, and sharding multiplies boots.
- **Release-build runs:** `testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")` in `:app` (the AntennaPod pattern), with `testProguardFiles("proguard-test.pro")` (keep rules for the androidTest APK only; the app's own rules stay in 01's `keepRules` source sets); nightly passes `-PtestBuildType=release` so instrumented tests run against the R8-minified APK, where AGP 9's unit tests cannot see R8 breakage. Only when that property is present and no `NEUTRODYNE_KEYSTORE` is set, `:app` signs `release` with the debug signing config so the minified APK can be installed ([Gradle signing configuration](#gradle-signing-configuration)); the release pipeline never passes the property.
- **ABI splits on emulators:** every device above is `x86_64`, so the managed-device and `connected*` tasks must install the `x86_64` split of `:app` (with the engine). Unverified: that AGP 9.4 picks the split matching the device ABI for test installs (M0 check; fallback: `splits.abi.isEnable` only when a `release`-assembling task is requested, so test builds stay single-APK).
- Instrumented hygiene: orchestrator with `clearPackageData`; debug builds disable LeakCanary heap dumps when `ActivityManager.isRunningInUserTestHarness()` or the instrumentation is present; ACRA is off in debug ([ACRA configuration](#acra-configuration)).

### Out-of-process system tests

Instrumented tests run inside the app's process, so killing the process (`am kill`, ProcessDeathResumeTest) or reinstalling the app kills the test. Such tests live in `:benchmark` (`com.android.test`, self-instrumenting, `targetProjectPath = ":app"`), drive the app with UI Automator 2.4.0 and `UiAutomation.executeShellCommand`, and run on `bench34`. Consequently `:benchmark` is created in **M6** (system tests), and gains Macrobenchmarks and the baseline-profile generator in M10/M11 ([Performance budgets](#performance-budgets)). Compose nodes are found by resource ID through `Modifier.semantics { testTagsAsResourceId = true }` on `NeutrodyneRoot` and 08's test tags ([08 Performance journeys](08-ui-ux.md#performance-journeys)). System tests target `:app`'s `debug` variant (package `ch.lkmc.neutrodyne.debug`, the `x86_64` split; the Macrobenchmark build types arrive with the baseline-profile plugin in M10), create their data through the UI (Library → "Add by URL"), and serve feeds and enclosures from a MockWebServer inside the `:benchmark` process on `127.0.0.1`, which the app reaches over loopback.

### Recorded responses

- No PR job contacts YouTube, Apple, fyyd, Podcast Index or GitHub's release endpoints. Directory JSON (03), channel pages and oEmbed (04) and the engine's InnerTube traffic (04's `RecordingRH`, driven by `scripts/youtube/record-responses.sh`) are recorded on a developer machine, scrubbed per [04 Recorded responses](04-youtube.md#recorded-responses) (`ip=`, signatures, cookies, visitor data, `expire`), and committed. The updater's GitHub responses (`latest/download` redirects, `releases.atom`, manifests) are hand-written fixtures served by MockWebServer.
- Recording never runs in CI (GitHub runners use data-centre IPs that YouTube bot-challenges).
- A replay test fails on any unrecorded request; that failure means "re-record", never "add a fallback".

### Fixture policy

- **Licensing.** The repository is Unlicense; fixtures must not carry copyrighted prose, artwork or audio. Real-world feeds and OPML are minimised to structure with `lorem` text and `https://example.invalid/…` URLs; images are generated (08's `ArtworkFixtures`); audio is synthesised with ffmpeg by `scripts/fixtures/make-playback-fixtures.sh` (06) and `make-media-fixtures.sh` (07), whose outputs are committed together with the ffmpeg version used. Each fixture directory has a `README.md` with origin URL and capture date.
- **Size.** One committed fixture ≤ 1 MB (larger inputs, e.g. 03's 831-item feed or 05's 100k-outline OPML, are generated at test time); all committed fixtures ≤ 20 MB.
- **Binaries.** No `.jar`, `.aar`, `.so`, `.dex`, `.class`, `.pyc` or `.apk` under any `src/`: every binary in an APK comes from a Gradle dependency with verification metadata or from a build step. The one vendored upstream binary, yt-dlp's release asset `youtube/ytdlp/engine/yt-dlp`, lives outside `src/` and is checked against its upstream signature on every build (`verifyBundledYtDlp`, [01 Python and native components](01-foundation.md#python-and-native-components)). Committed `.zip`/`.gz` fixtures (05's backup ZIPs, 04's `handle_mkbhd.html.gz`) are test inputs, never packaged.

---

## CI pipelines

Serves N11, N12. Delivered in M0 (`ci.yml`, `release.yml`, skeleton `nightly.yml`), M9a (engine jobs), M9b (`engine-canary.yml`), extended per milestone. Honours [D60](../PLAN.md#3-key-decisions), [D76](../PLAN.md#3-key-decisions), [D79](../PLAN.md#3-key-decisions), [PO-18](../PLAN.md#48-further-product-owner-decisions) (public GitHub repository: free 4-vCPU / 16 GB / 14 GB SSD Linux runners with KVM).

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
  cron["nightly.yml 02:17 UTC"] --> nj["instrumented-full, api37-16k, system-tests, no-engine-build, repro report-only, bmgr, screenshots-full, mutation-full, canaries"]
  tag["push tag v*"] --> rel["release.yml"]
  rel --> ghr["immutable GitHub release"]
  ghr --> upd["in-app updater"]
  ghr --> obt["Obtainium"]
  six["schedule every 6 h"] --> can["engine-canary.yml"]
  can --> pages["GitHub Pages approved engine manifest"]
  pages --> eng["apps with engine updates on"]
```

### `ci.yml`

Triggers: `pull_request`, `push` to `main` and `release/*`, `workflow_dispatch`. `permissions: contents: read` at the top; `concurrency: group ci-${{ github.ref }}`, `cancel-in-progress` for PRs only. Every job: `actions/checkout` (`fetch-depth: 0` in `static` for tag comparisons), `actions/setup-java` (Temurin 21), `actions/setup-python` with the Python minor version Chaquopy packages (3.14, fallback 3.13 per [01 S7](01-foundation.md#s7-chaquopy-under-agp-941); Chaquopy compiles `.pyc` at build time with it, and `shimTest` runs on it), `gradle/actions/setup-gradle` with `cache-provider: basic` (MIT; the default "enhanced" cache is a proprietary component) and `cache-read-only` except on `main`.

| Job | Timeout | Runs | Blocking |
|---|---|---|---|
| `static` | 30 min | `./gradlew spotlessCheck :app:lintDebug :app:assertModuleGraph :app:licenseeRelease :app:verifyDependencyPolicy :app:verifyManifestPermissions checkSpdxHeaders checkBannedApis :youtube:ytdlp:checkPythonLicences :youtube:ytdlp:verifyBundledYtDlp --continue`; KGP assertion `./gradlew -q :app:buildEnvironment \| grep -E 'kotlin-gradle-plugin:.*2\.4\.20'` (PLAN M0 AC3); `scripts/ci/check-frozen-schemas.sh`; SARIF upload (`security-events: write`); `./gradlew detekt` with `continue-on-error: true` | yes (detekt no) |
| `unit` | 45 min | `./gradlew test mutationTest :youtube:ytdlp:shimTest -Proborazzi.test.verify=true --continue`; Room schema drift: `test -z "$(git status --porcelain -- core/database/schemas)"` (KSP regenerated the schema while compiling); upload `**/build/reports/tests/` and Roborazzi `_compare.png` on failure; cache `~/.m2/repository/org/robolectric` keyed `robolectric-4.17-sdk36` | yes |
| `assemble` | 30 min | `./gradlew assembleDebug assembleRelease` (unsigned: no secrets in PR builds; each produces the `arm64-v8a`, `x86_64` and `armeabi-v7a` APKs and no universal APK); `scripts/ci/check-apk.sh` on the release APKs ([Build-output checks](#build-output-checks)); upload `debug-apks` (14 days) | yes |
| `instrumented` | 75 min | only on `main` pushes, `workflow_dispatch` or PRs labelled `run-instrumented`: free disk, enable KVM, `./gradlew --max-workers=2 ciGroupDebugAndroidTest -Pneutrodyne.testScope=ci -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`; upload `**/build/outputs/androidTest-results/` on failure | required green on `main` (DoD), not a PR merge check |

```yaml
# .github/workflows/ci.yml (excerpt; every uses: is pinned by full commit SHA with the tag in a comment)
  unit:
    runs-on: ubuntu-24.04
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@<sha> # v7.0.1
      - uses: actions/setup-java@<sha> # v6.0.1
        with: { distribution: temurin, java-version: "21" }
      - uses: actions/setup-python@<sha> # tag kept current by Renovate
        with: { python-version: "3.14" }   # = chaquopy.defaultConfig.version (01); Chaquopy's build Python and shimTest's host
      - uses: gradle/actions/setup-gradle@<sha> # v6.4.0 (also validates the wrapper checksum)
        with: { cache-provider: basic, cache-read-only: "${{ github.ref != 'refs/heads/main' }}" }
      - uses: actions/cache@<sha>
        with: { path: ~/.m2/repository/org/robolectric, key: robolectric-4.17-sdk36 }
      - run: ./gradlew test mutationTest :youtube:ytdlp:shimTest -Proborazzi.test.verify=true --continue
      - name: Room schema drift
        run: test -z "$(git status --porcelain -- core/database/schemas)" || { echo "::error::Room schema changed without a committed version"; git status --porcelain -- core/database/schemas; exit 1; }
      - uses: actions/upload-artifact@<sha> # v7.0.1
        if: failure()
        with: { name: unit-reports, path: "**/build/reports/tests/\n**/build/outputs/roborazzi/" }
```

The instrumented job's preamble (as AntennaPod does): `sudo rm -rf /usr/share/dotnet /usr/local/lib/android/sdk/ndk /opt/ghc /usr/local/.ghcup`, then the udev rule `KERNEL=="kvm", GROUP="kvm", MODE="0666"` with `udevadm trigger`. The GMD system images of the `ci` group are cached (`~/.android/avd/gradle-managed`, key = image list); `nightly` images are downloaded each night.

### `nightly.yml`

`schedule: cron "17 2 * * *"` plus `workflow_dispatch` with input `scope` (`full`, default; `youtube-smoke` = only the release leg of `instrumented-full` on `api36`, filtered to `YouTubeReleaseSmokeTest` and `SmokeTest`, ≈ 12 min, used by the APK hotfix path). A failing job runs `scripts/ci/report-nightly.sh <job>`, which opens or updates one issue per job (label `nightly-failure`, `permissions: issues: write`) and closes it after the next green run.

| Job | From | Runs | Blocks a release? |
|---|---|---|---|
| `instrumented-full` | M0 | two matrix legs with `-Pneutrodyne.testScope=nightly --max-workers=2`: **debug** `./gradlew nightlyGroupDebugAndroidTest`; **release** `./gradlew -PtestBuildType=release -Pandroid.testInstrumentationRunnerArguments.ytxReplay=true nightlyGroupReleaseAndroidTest` (minified `:app` suite including E7 through `:ytx`; library modules have no release test variant) | yes (red nightly ⇒ no tag) |
| `api37-16k` | M0 | android-emulator-runner (`api-level: 37.0`, `target: google_apis_ps16k`, `arch: x86_64`, [GMD notes](#gradle-managed-devices)): assert `adb shell getconf PAGE_SIZE` = 16384; `./gradlew --max-workers=2 -PtestBuildType=release -Pneutrodyne.testScope=nightly :app:connectedReleaseAndroidTest connectedDebugAndroidTest` — the whole `:app` suite on the debug-signed minified `x86_64` APK (E0 including `selftest` in `:ytx` while S7 is go, journeys, `YtxProcessStartTest`, 06's `PlaybackServiceTest`, whose hardening cases switch `cmd audio set-enable-hardening throw` on through `UiAutomation.executeShellCommand` and off in `@After`, so other tests keep the default muting behaviour) plus the library modules' suites (migrations on both drivers); `zipalign -c -P 16 -v 4` and `check-apk.sh --alignment-only` on the APK (PLAN M11 AC7) | yes |
| `system-tests` | M6 | `:benchmark` system tests on `bench34` (E10) | yes |
| `no-engine-build` | M9a | the emergency build without the engine ([01 Emergency build without the engine](01-foundation.md#emergency-build-without-the-engine)): `./gradlew assembleRelease -Pneutrodyne.youtubeEngine=false`, `scripts/ci/check-apk.sh --no-engine`, and 01's Hilt graph test with the switch off (`./gradlew -Pneutrodyne.youtubeEngine=false :app:testDebugUnitTest --tests '*HiltGraph*'`), so the same-day emergency release of risk L1 never rots | yes |
| `benchmark-dryrun` | M10 | Macrobenchmark journeys with `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dryRunMode.enable=true` (catches broken journeys; timings are meaningless on emulators) | no |
| `repro` | M0 | [two unsigned builds and a diff](#nightly-reproducibility-job); report-only permanently ([D79](../PLAN.md#3-key-decisions)) | no |
| `bmgr` | M3 | android-emulator-runner API 29 (`backup_rules.xml` path) and API 36 (`data_extraction_rules.xml`), image `default`: `scripts/ci/bmgr-check.sh` (05's procedure plus 07's "no `Podcasts/`" assertion). Needs uninstall/reinstall, which an in-process instrumented test cannot do | yes |
| `screenshots-full` | M10 | `./gradlew test --tests '*Screenshot*' -PscreenshotTier=full -Proborazzi.test.verify=true` | yes |
| `mutation-full` | M1 | `./gradlew mutationTest -PmutationIterations=1000` | yes |
| `live-canary` | when 03 ships `feeds/canary/feeds.txt` (M11 at the latest) | `./gradlew :feeds:liveCanary` | no |
| `youtube-canary` | M9a (04) | 04's subscribe-resolve-chunk smoke through the shim on a host CPython (live; runner IPs are bot-checked, so it is noisy by design) | no |
| `engine-nightly-canary` | M9a (04) | `shimTest` with yt-dlp's latest nightly build (`yt-dlp/yt-dlp-nightly-builds`, signature checked with the same pinned key) in place of the bundled version: the early warning for plugin or internal API drift (risk M8r). Informational; it never approves anything ([D76](../PLAN.md#3-key-decisions): nightlies are never shipped) | no |
| `mirror` | M0 | pushes `main`, `release/*` and every `v*` tag to the Codeberg mirror ([Mirror](#mirror)) | no |

### `release.yml`

Trigger: `push: tags: ['v*']`. One job `release` in GitHub environment `release` (required reviewer = a maintainer; the signing secrets live only there) with `permissions: contents: write, id-token: write, attestations: write, artifact-metadata: write` (the last three for `actions/attest`); `setup-gradle` with `cache-disabled: true` (no cache-poisoning surface for signed builds). The build runs inside the same pinned container and script as the [repro job](#nightly-reproducibility-job), so the published APKs and the nightly check share one toolchain: `python:3.14-slim-trixie@sha256:<digest>` (Docker's official Python image on Debian 13, [docker-library/python](https://github.com/docker-library/python/tree/master/3.14); Debian trixie's own `python3` is 3.13, [Debian](https://packages.debian.org/trixie/python3), and Chaquopy's build-time `.pyc` compilation needs the packaged minor version, [01 S7](01-foundation.md#s7-chaquopy-under-agp-941)) plus Debian's `openjdk-21-jdk-headless` ([Debian](https://packages.debian.org/trixie/openjdk-21-jdk-headless)). The image digest is Renovate-managed and changes only in a reviewed PR.

```mermaid
sequenceDiagram
  participant M as Maintainer
  participant G as GitHub
  participant R as release.yml
  participant E as Environment release
  participant C as Build container
  M->>M: scripts/release.sh patch
  M->>G: push release commit and tag vX.Y.Z
  G->>R: tag event
  R->>E: wait for required reviewer
  M->>E: approve
  R->>R: verify-tag.sh preconditions
  R->>C: repro-build.sh --sign assembleRelease
  C-->>R: three signed ABI APKs and the R8 mapping
  R->>R: apksigner certificate and scheme check, zipalign 16 KB, check-apk.sh
  R->>R: rename, make-update-json.sh, check-update-json.sh, SHA256SUMS
  R->>G: actions/attest provenance for APKs, update manifest, SHA256SUMS
  R->>G: draft release, upload every asset, publish as immutable
  R->>G: gh release verify and verify-asset
  G-->>M: in-app updaters and Obtainium find the release
```

Steps, in order (target: tag → published release in < 30 min, N11 and PLAN M11 AC2):

1. `scripts/ci/verify-tag.sh`: tag equals `v` + `neutrodyne.versionName`; the tagged commit is on `main` or a `release/*` branch ([hotfix branches](#scriptsreleasesh)); `changelogs/<versionCode>.txt` exists in it ([Changelogs and release notes](#changelogs-and-release-notes)); and **either** `ci.yml` concluded `success` for the tagged commit **or** the tagged commit is a `release.sh` commit — exactly one parent, `ci.yml` `success` on that parent (`gh api repos/{repo}/commits/{sha}/check-runs`), and `git diff --numstat HEAD^ HEAD` showing only `gradle.properties` with two changed lines, both matching `^neutrodyne\.version(Name|Code)=`. The second branch exists because the release commit's own `ci.yml` run starts at the same moment as `release.yml`; waiting for it would cost the 15 minutes the N11 budget cannot spare, and a version-line change cannot alter what CI verified. It also decides `make_latest`: true only for a stable tag whose `versionCode` is higher than that of the current latest release (`gh release view --json tagName`), so a late patch on an older line can never become "latest". It prints `neutrodyne.youtubeEngine` from the tagged `gradle.properties` (an emergency release commits `false` there, [01](01-foundation.md#emergency-build-without-the-engine)).
2. Decode `NEUTRODYNE_KEYSTORE_B64` to `$RUNNER_TEMP/release.p12`; `scripts/ci/repro-build.sh --sign assembleRelease` mounts it read-only into the container and passes `NEUTRODYNE_KEYSTORE*` as container environment variables ([Gradle signing](#gradle-signing-configuration)); after Podcast Index's written permission (PO-3 option A) it also passes `-Pneutrodyne.podcastIndexKey/Secret` from the environment's secrets. Nothing else from the runner environment enters the container. Output: `app-arm64-v8a-release.apk`, `app-x86_64-release.apk`, `app-armeabi-v7a-release.apk` (Unverified: AGP 9's exact split output names) and one `mapping.txt`.
3. Per APK: `apksigner verify --verbose --print-certs --min-sdk-version 26` must report the v1 scheme false and v2 and v3 true, and the signer's SHA-256 must equal `${{ vars.NEUTRODYNE_CERT_SHA256 }}` ([apksigner](https://developer.android.com/tools/apksigner)) — during rotation releases the check is per scheme: the v3.1 signer equals `NEUTRODYNE_CERT_SHA256` (key B) and the v2/v3.0 signer equals `NEUTRODYNE_PREVIOUS_CERT_SHA256` (key A), since apksigner reports A for those blocks by default; `zipalign -c -P 16 -v 4`; `check-apk.sh --release` (sizes, content, alignment of the `.so` files inside Chaquopy's asset zips; exactly three APKs, no universal APK).
4. Rename to the [asset names](#release-assets): `neutrodyne-{v}-{abi}.apk` and `neutrodyne-{v}-mapping.txt`.
5. `scripts/ci/make-update-json.sh` writes `neutrodyne-update.json` ([Update manifest](#update-manifest)); `scripts/ci/check-update-json.sh` validates it against the files, the tag, `gradle.properties`, the changelog and each APK's certificate. A release without a valid manifest is never published: the stable updater reads `releases/latest/download/neutrodyne-update.json`, which would 404.
6. `SHA256SUMS` (`sha256sum` format) over the three APKs, the mapping and `neutrodyne-update.json`; the release body from the [template](#release-body).
7. `actions/attest` (v4, SHA-pinned, [actions/attest](https://github.com/actions/attest)) with `subject-path` listing the three APKs, `neutrodyne-update.json` and `SHA256SUMS`: SLSA build-provenance attestations, verifiable with `gh attestation verify` ([artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations)).
8. `gh release create vX.Y.Z --draft --verify-tag --title "Neutrodyne X.Y.Z" --notes-file body.md` (`--prerelease` when the tag has a suffix) → `gh release upload` of all six assets → `gh release edit vX.Y.Z --draft=false --latest=<make_latest>`. Immutable releases are on in the repository settings, so publishing locks the assets and the tag and creates GitHub's release attestation ([immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases)). The `gh` CLI preinstalled on the runner replaces a third-party release action.
9. `gh release verify vX.Y.Z` and `gh release verify-asset vX.Y.Z <asset>` for every asset ([gh release verify-asset](https://cli.github.com/manual/gh_release_verify-asset)); for a stable tag, `curl -fsSL -o /dev/null {repoUrl}/releases/latest/download/neutrodyne-update.json` must succeed. Last: push the tag to the Codeberg mirror ([Mirror](#mirror); failure only warns).

A failure before step 8's publish leaves at most a draft: the maintainer deletes it and re-runs the workflow on the same tag if the cause was outside the source (Unverified: that deleting a never-published draft keeps the tag name usable under immutable releases; GitHub documents that a deleted *published* immutable release's tag name can never be reused). A problem found after publishing is fixed forward with a new PATCH release; nothing published is ever edited except its notes. Removed with the store channels ([D79](../PLAN.md#3-key-decisions)): the Play publishing step, the GPL corresponding-source bundle and the release-blocking reproducibility job.

### `engine-canary.yml`

Serves R3.9, N11, N12; mitigates risks M1r, M7r, M8r. Delivered in M9b. Honours [D76](../PLAN.md#3-key-decisions), [PO-32](../PLAN.md#48-further-product-owner-decisions). The workflow — schedule, jobs, permissions, environments, signing and deployment — is owned here; **what** it verifies and tests is [04 Engine canary](04-youtube.md#engine-canary), and the manifest format and the app-side checks are [04 Trust chain](04-youtube.md#trust-chain).

Triggers: `schedule: cron "23 */6 * * *"` (every 6 h) and `workflow_dispatch` with inputs `tag` (approve a specific yt-dlp stable tag, e.g. after re-recording, 04's runbook path 2), `revoke` (a version to list in `revoked`; dispatched together with `tag` = the last good version, 04's revocation) and `bootstrap` (the very first run, when no manifest exists yet: `sequence` starts at 1). `concurrency: group: engine-canary, cancel-in-progress: false`, so two runs never race on `sequence`. Top-level `permissions: contents: read`.

| Job | Environment, permissions | Steps |
|---|---|---|
| `test` | none; `contents: read`, `issues: write` | 1. Read the current approved manifest from `neutrodyne.engineManifestUrl` (`gradle.properties`, with a cache-busting query) and verify its Ed25519 signature against the committed `youtube/ytdlp/keys/engine-manifest-ed25519.pub`; keep `sequence`, `revoked` and the approved version. 2. Detect the candidate: the `tag` input, else the tag in the `Location` header of `https://github.com/yt-dlp/yt-dlp/releases/latest` (no REST API); stop green when it equals the approved version and no `revoke` input is given. 3. In the job's checkout (never committed): `scripts/engine/bump-ytdlp.sh <tag>` downloads `yt-dlp`, `SHA2-256SUMS` and `SHA2-256SUMS.sig` from `github.com/yt-dlp/yt-dlp/releases/download/<tag>/`, applies 04's size and zip-content rules and updates `bundled.json` and the lockfile's yt-dlp entry exactly as a bump PR would; then `./gradlew :youtube:ytdlp:verifyBundledYtDlp :youtube:ytdlp:checkPythonLicences :youtube:ytdlp:shimTest` and `:youtube:ytdlp:testDebugUnitTest --tests '*UpstreamReleaseVerifierTest*'` (signature against the pinned yt-dlp key with build-logic's and the app's OpenPGP code, SHA-256, `ORIGIN`, top-level packages, the self-test's API probe, and every recorded scenario through `ReplayRH` in contract mode with `-Preplay=contract` — the blocking gate of [04 Engine canary](04-youtube.md#engine-canary)); then `shimTest` in strict mode as a non-blocking report, whose mismatches open or update the `engine-canary` issue asking for a re-record without failing the job. 4. Compare the fingerprint of `https://github.com/yt-dlp/yt-dlp/blob/master/public.key` with the pinned one (04 open question 19); a change opens an issue but does not block. 5. On green, upload the candidate's `version`, `tag`, `sha256` and `ejsVersion` as a job output; on red, `scripts/ci/report-nightly.sh engine-canary --label engine-canary` opens or updates one issue with the failing scenarios |
| `approve` (needs `test`, only on green or with `revoke`) | `engine-approval` (deployment branch `main` only, no reviewer; secret `NEUTRODYNE_ENGINE_MANIFEST_KEY`); `contents: read` | `scripts/engine/make-engine-manifest.sh` writes `engine/ytdlp-approved.json` (`sequence + 1`, `issuedAt`, the candidate, `shimApi` = the range `shimTest` ran, `revoked` carried over plus the `revoke` input); `scripts/engine/sign-engine-manifest.sh` signs the exact bytes with the Ed25519 key (`openssl pkeyutl -sign -rawin`, [OpenSSL pkeyutl](https://docs.openssl.org/3.0/man1/openssl-pkeyutl/)) into `ytdlp-approved.json.sig` (base64) and verifies the result against the committed public key before anything leaves the job (a key mismatch fails here, not on users' devices); `actions/upload-pages-artifact` with the `engine/` directory. The key is decoded into `$RUNNER_TEMP` and deleted in an `always()` step |
| `deploy` (needs `approve`) | `github-pages`; `pages: write`, `id-token: write` | `actions/deploy-pages` ([deploy-pages](https://github.com/actions/deploy-pages)); then poll the public URL until it serves the new `sequence` (≤ 15 min, Unverified Pages cache lifetime, 04 open question 18) and record the approval latency (upstream `published_at` → served) in the job summary |

Heartbeat: `approve` runs after **every** green `test` run, not only with a new candidate; without one it re-publishes the unchanged manifest together with `engine/ytdlp-heartbeat.json` (`{"kind": "heartbeat", "lastRunAt": "…Z", "sequence": <current manifest sequence>}`) and its Ed25519 signature `ytdlp-heartbeat.json.sig`. The app fetches it with the manifest (04 [Update flow](04-youtube.md#update-flow)); a validly signed heartbeat older than 48 h puts "Engine approvals stale since {date}" into Settings › YouTube's status line and diagnostics, and never blocks anything. A canary stopped by GitHub's 60-day rule or a broken secret is thereby visible to users and maintainers; an independent check outside GitHub (for example a scheduled job on the Codeberg mirror that alerts the maintainers when the served heartbeat is older than 48 h; Unverified availability, decided with PO-34) is the second alarm.

Rules: the signing key never exists in a job that runs fetched code (`test` runs yt-dlp's code in `shimTest`; `approve` only writes and signs JSON); a Pages deployment replaces the whole site, so the artifact always contains the complete `engine/` directory (Unverified for partial uploads; nothing else is hosted on the Pages site); the repository's Pages source is "GitHub Actions" (M9b setting). The canary never approves a nightly or a version below the APK-bundled one, and it never commits to the repository: moving the bundled version stays a reviewed PR with `bump-ytdlp.sh` ([Review rules per group](#review-rules-per-group)). Time budget: upstream stable release → approved manifest served ≤ 6 h (N11; [Time budgets](#time-budgets)).

Key custody for `NEUTRODYNE_ENGINE_MANIFEST_KEY`: generated offline at the M9b key ceremony (same procedure as the [app key](#key-ceremony-and-custody), Ed25519); its public key is committed in slot 1 of `engine-manifest-ed25519.pub`, a second key prepared for rotation in slot 2 ([04 Security notes](04-youtube.md#security-notes)); an encrypted offline copy is kept with the app-key backups. A leaked manifest key can only choose among genuine upstream-signed yt-dlp releases at or above the bundled version (risk M7r); rotation = sign with slot 2, drop slot 1 in the next APK.

### Helper workflows

| Workflow | Trigger | Does |
|---|---|---|
| `record-screenshots.yml` | `workflow_dispatch` (input: branch, tier) | `./gradlew recordRoborazziDebug -PscreenshotTier=…` on `ubuntu-24.04`; uploads `screenshots-<sha>.zip` |
| `baseline-profile.yml` | `workflow_dispatch` (before each minor release, from M11) | `./gradlew :app:generateReleaseBaselineProfile` on GMD `bench34`; uploads the generated `baseline-prof.txt`/`startup-prof.txt` for the author to commit |
| `keepalive.yml` (M0) | `schedule: cron "41 4 1 * *"` (monthly) and `workflow_dispatch` | `gh workflow enable` for `nightly.yml`, `engine-canary.yml` (from M9b) and itself (`permissions: actions: write`). In a public repository GitHub disables scheduled workflows "when no repository activity has occurred in 60 days" ([disable and enable workflows](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows)), and a disabled canary would stop approving engine fixes without any failure issue. Unverified: which events count as activity and whether re-enabling resets the clock; the engine heartbeat ([engine-canary.yml](#engine-canaryyml)) detects a stopped canary either way |

Pushing commits from workflows is deliberately avoided: pushes made with `GITHUB_TOKEN` do not trigger new workflow runs, so a bot commit would leave the PR without required checks.

### CI scripts

| Script | Purpose |
|---|---|
| `scripts/ci/check-apk.sh [--release\|--no-engine\|--alignment-only] <apk…>` | per-ABI size budgets, 16 KB alignment including Chaquopy's asset-extracted `.so` files, forbidden content, allowed native libraries and Python packages, locale config, metadata hygiene ([Build-output checks](#build-output-checks)) |
| `scripts/ci/make-update-json.sh` | writes `neutrodyne-update.json` from `gradle.properties`, the tag, `changelogs/<versionCode>.txt`, `NEUTRODYNE_CERT_SHA256` and the renamed APKs ([Update manifest](#update-manifest)) |
| `scripts/ci/check-update-json.sh` | validates the manifest before the draft is published ([Update manifest](#update-manifest)); also run in PRs that touch the release scripts |
| `scripts/ci/check-frozen-schemas.sh` | for every `N.json` that exists at the newest `v*` tag, `git diff --exit-code <tag> -- <file>` ([02 Schema export and versioning](02-data-model.md#schema-export-and-versioning)) |
| `scripts/ci/repro-build.sh [--sign] [--path P] [--cpus N] [--umask U] <gradle args…>` | container build used by `repro` and `release.yml`; `--sign` mounts the keystore and passes `NEUTRODYNE_KEYSTORE*` |
| `scripts/ci/install-android-sdk.sh` | pinned cmdline-tools (version + SHA-256), `platforms;android-37`, `build-tools;36.0.0` |
| `scripts/ci/bmgr-check.sh <package>` | 05's `bmgr` procedure and assertions |
| `scripts/ci/report-nightly.sh <job> [--label L]` | issue per failing nightly or canary job |
| `scripts/ci/apply-screenshots.sh <run-id>` | download and unpack recorded screenshots locally |
| `scripts/ci/verify-tag.sh` | release preconditions and the `make_latest` decision ([release.yml](#releaseyml) step 1) |
| `scripts/ci/network-capture.sh` | v1.0 gate network capture ([v1.0 gate](#v10-gate)); maintainer-run, needs internet |
| `scripts/ci/start-emulator.sh` | fallback emulator start for API 26 / API 37 if android-emulator-runner or GMD cannot ([Gradle Managed Devices](#gradle-managed-devices)) |

### Hardening

- Every `uses:` is pinned by commit SHA; Renovate's `helpers:pinGitHubActionDigests` keeps the pins current.
- Never `pull_request_target`; fork PRs get no secrets (PR builds are unsigned by design).
- Repository rulesets: `main` and `release/*` require a PR and the checks `static`, `unit`, `assemble`; linear history; no force pushes; maintainers may bypass only to push the release commit created by `release.sh`. Tag ruleset: only maintainers create or delete `v*` tags.
- Repository settings (M0): immutable releases on; environments `release` (required reviewer) and, from M9b, `engine-approval` (deployment branch `main` only) and `github-pages`; Pages source "GitHub Actions" (M9b); GitHub secret scanning with push protection on; private vulnerability reporting on ([SECURITY.md](#security-reporting)).

| Secret / variable | Scope | Used by |
|---|---|---|
| `NEUTRODYNE_KEYSTORE_B64`, `NEUTRODYNE_KEYSTORE_PASSWORD`, `NEUTRODYNE_KEY_ALIAS`, `NEUTRODYNE_KEY_PASSWORD` | environment `release` | APK signing ([release.yml](#releaseyml) step 2) |
| `PODCASTINDEX_KEY`, `PODCASTINDEX_SECRET` | environment `release` | only after Podcast Index grants written permission (PO-3 option A): passed as `-Pneutrodyne.podcastIndexKey/Secret` to `assembleRelease` in `release.yml` |
| `NEUTRODYNE_ENGINE_MANIFEST_KEY` | environment `engine-approval` | signing `ytdlp-approved.json` ([engine-canary.yml](#engine-canaryyml)) |
| `CODEBERG_MIRROR_KEY` | repository secret (an SSH deploy key with write access to the Codeberg mirror only) | `mirror` job and the last step of `release.yml` ([Mirror](#mirror)) |
| `NEUTRODYNE_CERT_SHA256` | repository variable | signer check, `make-update-json.sh`, release body |

No other secret exists. PR, nightly and canary builds read no secret; the only build-time secret that may ever enter an APK is the Podcast Index key, and only through `release.yml` after written permission ([D26](../PLAN.md#3-key-decisions)).

### Time budgets

| Pipeline | Target | Hard timeout |
|---|---|---|
| PR checks (`static`, `unit`, `assemble` in parallel) | ≤ 15 min wall clock (PLAN M0 AC1) | 30 / 45 / 30 min |
| `instrumented` on `main` | ≤ 45 min | 75 min |
| Tag → published immutable GitHub release | ≤ 30 min incl. the environment approval (N11) | 45 min |
| Upstream yt-dlp stable release → approved engine manifest served from GitHub Pages | ≤ 6 h (N11: one canary period plus a ≈ 30-min run and the Pages deployment) | 60 min per run |
| Canary gate red (04's runbook path 2) → re-recorded fixtures merged and `engine-canary.yml` dispatched | ≤ 24 h (maintainer target, N11) | — |
| Engine heartbeat age (served `ytdlp-heartbeat.json`) | ≤ 6 h normally; > 48 h is an alarm | — |
| Nightly | ≤ 3 h total | per job |

When `unit` exceeds 15 min at p50 over a week, the fix order is: raise `maxParallelForks` only if memory allows, move slow Robolectric suites into a second `unit-2` job (split by module list), then move `FULL`-only screenshot variants out of `PR`.

---

## Static analysis

Serves N8, N10, N11. Delivered in M0 (Python licence and APK content checks live from M0, with the engine stack from M9a). Honours [D3](../PLAN.md#3-key-decisions), [D60](../PLAN.md#3-key-decisions).

### Gates

| Gate | Blocking | Where | Configuration |
|---|---|---|---|
| Android Lint | yes | `static` (`:app:lintDebug` with `checkDependencies`) | [below](#android-lint) |
| Spotless + ktlint 1.8.0 + compose-rules 0.6.7 | yes | `static` | [below](#formatting) |
| detekt 2.0.0-alpha.6 | no (SARIF only) | `static` | `config/detekt/detekt.yml` |
| Licensee, module graph, `verifyDependencyPolicy`, `verifyManifestPermissions`, `checkSpdxHeaders` (no GPL, LGPL or AGPL identifier anywhere), `checkBannedApis` | yes | `static` | [01 Gradle-side policy tasks](01-foundation.md#gradle-side-policy-tasks) |
| `checkPythonLicences` (lockfile against the licence allow-list), `verifyBundledYtDlp` (vendored yt-dlp against its upstream signature) | yes | `static` | [01 Python and native components](01-foundation.md#python-and-native-components) |
| Python shim tests (`shimTest`) | yes | `unit` | [04 Testing](04-youtube.md#testing) |
| KGP version assertion | yes | `static` | [01 S1](01-foundation.md#s1-kgp-2420-under-agp-941) |
| Room schema drift and frozen versions | yes | `unit`, `static` | [CI scripts](#ci-scripts) |
| APK size per ABI, 16 KB alignment (including Chaquopy's asset `.so` files), forbidden content | yes | `assemble`, `release.yml`, nightly `no-engine-build` and `api37-16k` | [Build-output checks](#build-output-checks) |
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
    disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion",  // Renovate's job; offline-safe
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

`scripts/ci/check-apk.sh` runs on every `assemble` (unsigned release APKs), in `release.yml` (signed APKs, `--release`), in the nightly `no-engine-build` (`--no-engine`) and `api37-16k` (`--alignment-only`). Inputs: the APKs, `app/policy/locales.txt` and `youtube/ytdlp/python-components.lock` (01: which native libraries and top-level Python packages an APK may contain). It also reads `neutrodyne.youtubeEngine` from `gradle.properties`, so an emergency release is checked with the no-engine rules.

1. **Size and set** (blocking): exactly three release APKs (`arm64-v8a`, `x86_64`, `armeabi-v7a`) and no universal APK ([D77](../PLAN.md#3-key-decisions)); `arm64-v8a` and `x86_64` < 40 MB each (PB12), `armeabi-v7a` < 30 MB (PB13). Sizes go to the job summary and a nightly artifact for trends; the engine's share is printed separately (Chaquopy's `jniLibs` and assets, the yt-dlp asset).
2. **16 KB** ([16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)): `zipalign -c -P 16 -v 4` on every release APK (the uncompressed `.so` files in `lib/<abi>/`: `sqlite-bundled` ([01 S6](01-foundation.md#s6-sqlite-bundled-16-kb-alignment-and-size)), Chaquopy's `libpython3.14.so`, `libcrypto`, `libssl`, `libsqlite3`, `libc++_shared`, and quickjs-kt's library when the JS provider ships); and `llvm-readelf -lW` on every ELF file in `lib/` and inside Chaquopy's asset zips (the `lib-dynload` extension modules, which Chaquopy extracts at run time and zipalign never sees): every `LOAD` segment aligned to ≥ 0x4000. Unverified: the `llvm-readelf` binary name on the runner image (fallback: binutils `readelf -lW`, which reads program headers of any ELF architecture). With legacy native packaging (S7) the `lib/` files are compressed and only the ELF check applies.
3. **Content** (blocking; risk L2): no zip entry, nested asset-zip entry or dex class descriptor (`dexdump`, build-tools 36.0.0) matching `mutagen`, `readline`, `libreadline`, `org/schabi/newpipe` or `org/mozilla/javascript`; native libraries and top-level Python packages only as the lockfile lists them (Python packages: `neutrodyne_ytx`, the standard library, `yt_dlp`, `yt_dlp_ejs`); the `armeabi-v7a` APK contains no Python or Chaquopy native library, and its unusable Python assets are reported with their size (tolerated only while PB13 holds, 01 open question 13); `--no-engine`: no `Lch/lkmc/neutrodyne/youtube/ytdlp/` class, no Chaquopy, CPython or yt-dlp file and no `YtxService` in the merged manifest (`aapt2 dump xmltree`). The M0 negative checks of PLAN M0 AC2 (an APK with `mutagen/__init__.py`, one with `libreadline.so`) are recorded in [01 Verification log](01-foundation.md#verification-log).
4. **Locale config:** `aapt2 dump xmltree --file res/xml/_generated_res_locale_config.xml` (Unverified generated file name; the manifest's `android:localeConfig` points at it) on every release APK lists exactly the locales of `app/policy/locales.txt` — no pseudo-locales, no library-only translations.
5. **Metadata hygiene:** `unzip -l` shows no `META-INF/version-control-info.textproto` ([vcsInfo off](#hygiene)); `--release` additionally checks with `apksigner verify --verbose` that the signing schemes are exactly v2 and v3 (the dependency-info block stays off, 01).

### PR template

`.github/PULL_REQUEST_TEMPLATE.md` checklist (each line is a checkbox):

- Tests per [Test obligations per change](#test-obligations-per-change); bug fixes include a failing-first regression test.
- UI changed → screenshots re-recorded with `record-screenshots.yml`; accessibility checks pass.
- Strings externalised, plurals used, no concatenation.
- Schema change → version bump, migration and test.
- New setting → classified `settings`/`device_settings` and registered (01).
- YouTube UI checked in both capability modes (engine present and external mode) against [04 Capability matrix](04-youtube.md#capability-matrix) ([08 Capability differences in UI](08-ui-ux.md#capability-differences-in-ui)).
- No MockK in `androidTest`.
- 01's copied-code rule (verbatim from [01](01-foundation.md#copied-code-and-contributions)).
- Design document updated if behaviour deviates (PLAN DoD).
- Shim PRs (`youtube/ytdlp/src/main/python/`): `shimTest` green against the bundled yt-dlp (CI) and against the latest approved version (locally: `scripts/engine/bump-ytdlp.sh <approved version>` without committing, then `./gradlew :youtube:ytdlp:shimTest`); recordings re-recorded when the requests changed; `SHIM_API_VERSION` bumped for incompatible changes (04).
- Bundled-engine bumps: only a version the engine canary approved; `bump-ytdlp.sh` output committed unchanged (04).

Issue templates: `bug.yml` (issue form with a `diagnostics` textarea that [Report a problem](#copy-report-and-export) pre-fills), `feature.yml`, `release.md` (the [Release checklist](#release-checklist) as checkboxes).

---

## Dependency updates

Serves N11; mitigates risks T2, T14, M3r, L2. Delivered in M0 (Renovate app installed on the repository, Chaquopy rule from the first commit). YouTube extraction fixes no longer travel through Renovate: they reach users as engine updates ([engine-canary.yml](#engine-canaryyml), [04 Hotfix runbook](04-youtube.md#hotfix-runbook)).

### Renovate configuration

Renovate (Mend-hosted GitHub app) reads `gradle/libs.versions.toml`, the wrapper, `build-logic`, workflow files and the release container's image digest in `scripts/ci/repro-build.sh` (a regex manager; Unverified exact config). Dependabot stays disabled.

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
    { "description": "Chaquopy bumps repeat S7's checks and change python-components.lock (01)", "matchPackageNames": ["/^com\\.chaquo\\.python/"], "dependencyDashboardApproval": true, "labels": ["chaquopy"] },
    { "description": "detekt has no stable release for this toolchain", "matchPackageNames": ["/^dev\\.detekt/"], "ignoreUnstable": false },
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
| Chaquopy (`com.chaquo.python`, dashboard approval) | [01 S7](01-foundation.md#s7-chaquopy-under-agp-941) steps 3 and 5 repeated on the branch: minified `release` build, `selftest` in `:ytx` on the API 26 GMD and the API 37 16 KB image (dispatch `nightly.yml`), APK sizes against PB12/PB13, 16 KB check of the asset `.so` files; `python-components.lock` updated (`chaquopy`, `python` and the runtime's bundled component versions — `checkPythonLicences` fails otherwise) and the Licences entries with it; a new CPython minor version also needs the `actions/setup-python` and release-container versions and makes `EngineStore` re-extract engine versions on devices (04) |
| Bundled yt-dlp (not Renovate-managed) | only a version the engine canary approved (the served manifest's `ytdlp.version`); `scripts/engine/bump-ytdlp.sh <version>` vendors the upstream files and updates `bundled.json` and the lockfile; CI runs `verifyBundledYtDlp`, `checkPythonLicences` and `shimTest`; the Licences screen shows the new version (04) |
| GitHub Actions | release notes for breaking input changes; SHA pins updated |

**Gradle wrapper:** Renovate's `gradle-wrapper` manager updates `gradle-wrapper.properties`; Unverified whether the hosted app also regenerates `gradle-wrapper.jar` and `distributionSha256Sum` (self-hosted needs `allowedUnsafeExecutions`). If it does not, the maintainer runs `./gradlew wrapper --gradle-version X --gradle-distribution-sha256-sum <sum>` on the PR branch. Renovate ≥ 44.14.7 fixed a command injection through the wrapper (CVE-2026-88886); the hosted app is current.

---

## Versioning and signing

Serves N11, N12; mitigates risks M4r, M6r, P9. Delivered in M0 (scheme, `release.sh`, key ceremony, first signed pre-release), M11b (rotation rehearsal). Honours [D61](../PLAN.md#3-key-decisions), [D63](../PLAN.md#3-key-decisions), [PO-8](../PLAN.md#48-further-product-owner-decisions) (resolved: `ch.lkmc.neutrodyne`, maintainer-held key), [PO-35](../PLAN.md#48-further-product-owner-decisions) default.

### Version scheme

Single source: `gradle.properties` keys `neutrodyne.versionName` and `neutrodyne.versionCode` ([01](01-foundation.md#settingsgradlekts-gradleproperties-root-build)); Gradle never reads git or the clock.

`versionCode = MAJOR·1 000 000 + MINOR·10 000 + PATCH·100 + S`, where S is:

| `versionName` suffix | S | Example |
|---|---|---|
| `-beta.N` (N = 1…79) | N | `0.1.0-beta.1` → 10001 |
| `-rc.N` (N = 1…15) | 79 + N | `1.0.0-rc.2` → 1000081 |
| none (stable) | 95 | `1.0.0` → 1000095, `1.2.3` → 1020395 |
| 96–99 | reserved (never used) | |

Rules: MINOR and PATCH ≤ 99. A version code is never reused, even for a failed release: a published immutable release locks its tag for good ([immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases)), and the in-app updater, Obtainium and Android's downgrade rule all compare codes. All three ABI APKs of a release share its `versionCode` ([D63](../PLAN.md#3-key-decisions)). A hotfix after `1.2.3` is `1.2.4`, never a rebuild. Pre-1.0 tester builds are `0.{n+1}.0-beta.N` for milestone Mn (M0 → `0.1.0`, …, M10 → `0.11.0`); an increment that lands out of order ships in the next tester build of the current line (PLAN 7.1); M11b ships `1.0.0-beta.N`, `1.0.0-rc.N`, then `1.0.0`. After 1.0: MINOR for feature releases, PATCH for fixes and YouTube hotfixes; MAJOR only by PO decision.

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

Usage: `scripts/release.sh <patch|minor|major|X.Y.Z[-beta.N|-rc.N]|finalise> [--beta|--rc] [--hotfix] [--dry-run]`. Algorithm:

1. Preconditions: on `main` (or, for `--hotfix`, on a `release/X.Y` branch, below), clean tree, `HEAD` equals its `origin` branch, `ci.yml` concluded `success` for `HEAD` (`gh run list --commit`), no open issue labelled `release-blocker`, and either the latest scheduled `nightly.yml` run is green or (with `--hotfix`, PATCH only) a `nightly.yml` run dispatched with `scope: youtube-smoke` is green for `HEAD`.
2. Compute the next `versionName` from the current one and the argument (`finalise` drops the pre-release suffix; `--beta` on a current `-beta.N` of the same X.Y.Z increments N). Compute `versionCode` per the table; refuse if it is lower than the current code, if it equals the current code while tag `v<current versionName>` exists, or if any existing tag has the new name. Equal to the current code without a tag is the "tag the prepared version" case: the very first release (`scripts/release.sh 0.1.0-beta.1`, the value 01 commits in M0) tags `HEAD` without a release commit.
3. Require `changelogs/<versionCode>.txt` **in `HEAD`** (it lands through a normal PR beforehand; `--dry-run` prints the code to name it): non-empty, ≤ 500 characters.
4. Rewrite the two `gradle.properties` lines and nothing else; commit `Release vX.Y.Z`; create an annotated (and, when the maintainer has signing configured, signed) tag `vX.Y.Z`. The single-file, two-line diff is what `verify-tag.sh` accepts without waiting for the release commit's own CI run.
5. `git push --atomic origin <branch> vX.Y.Z` (ruleset bypass for maintainers). `release.yml` takes over.

**Hotfix while `main` carries a pre-release of the next MINOR** (for example `main` at `1.1.0-beta.2`, latest stable `1.0.3`): a patch cut from `main` would reach only testers. Instead, create `release/1.0` from tag `v1.0.3` on first need (same ruleset as `main`; `ci.yml` also runs on `push` to `release/*`), cherry-pick the fix, dispatch `nightly.yml` `scope: youtube-smoke` on that branch and run `release.sh patch --hotfix` there → `v1.0.4` (1000495). The fix also lands on `main` and ships in `1.1.0-beta.3`. Version codes stay monotonic for every audience: stable users move 1000395 → 1000495, testers already hold 1010002 and get 1010003. `verify-tag.sh` accepts a tagged commit on `main` or on a `release/*` branch.

### Changelogs and release notes

- `changelogs/<versionCode>.txt`: user-facing plain text, English, ≤ 500 characters; one file feeds the GitHub release body ([Release body](#release-body)), the `notes` field of `neutrodyne-update.json` and with it the in-app update card and the "What's new" sheet ([08 Updates settings](08-ui-ux.md#updates-settings)). Not translated (excluded from Weblate).
- Release notes describe YouTube as "subscribe to YouTube channels and listen to them as audio"; nothing advertises downloading YouTube videos ([04 Posture and emergency build](04-youtube.md#posture-and-emergency-build)).
- Pre-release notes start with "Tester build for milestone Mn." and link the milestone's acceptance checklist issue.

### Key ceremony and custody

One RSA-4096 app-signing key ([D61](../PLAN.md#3-key-decisions)), created **in M0, before the first signed pre-release**: tester installs signed with a temporary key could not update to 1.0 without uninstalling. There is no store escrow: whoever holds the key is the only way every installed copy can ever be updated, and Google states that a lost key also makes a later developer registration impossible ([FAQ](https://developer.android.com/developer-verification/guides/faq)). Holders ([PO-8](../PLAN.md#48-further-product-owner-decisions) resolved, [PO-35](../PLAN.md#48-further-product-owner-decisions) default): the maintainer holds the key with an encrypted offline backup; a named second holder is recommended and holds one encrypted copy; until one is named, the maintainer keeps the two encrypted copies in two separate physical locations.

1. On an offline machine (live USB, no network), with JDK 21 `keytool`:
   `keytool -genkeypair -v -storetype PKCS12 -keystore neutrodyne-release.p12 -alias neutrodyne -keyalg RSA -keysize 4096 -validity 12000 -dname "CN=Neutrodyne"` (12,000 days ≈ 33 years).
2. Store and key passwords: two independent 7-word diceware passphrases in each holder's password manager.
3. Two encrypted copies (symmetric `age` or `gpg`, a third passphrase) on two offline media in two places (one per holder once a second holder exists); the passphrases on paper stored separately from the media.
4. Record the certificate SHA-256 (`keytool -list -v`); publish it in `README.md` and in every GitHub release body (AppVerifier format: the package name on one line, the colon-separated SHA-256 on the next, [AppVerifier](https://github.com/soupslurpr/AppVerifier)); `make-update-json.sh` writes it into every `neutrodyne-update.json`; store it as repository variable `NEUTRODYNE_CERT_SHA256` (and keep `NEUTRODYNE_PREVIOUS_CERT_SHA256` empty until a rotation).
5. Put a base64 copy into the `release` environment secrets (accepted risk for the < 30-min hotfix path; mitigated by environment approval, tag ruleset, SHA-pinned actions and a cache-less build; risk M6r).
6. Yearly (calendar issue): each holder decrypts a backup copy and verifies the fingerprint; the result is noted in the issue.

The Ed25519 engine-manifest key of [engine-canary.yml](#engine-canaryyml) is created at the M9b ceremony with the same steps 2, 3 and 6 and stored as `NEUTRODYNE_ENGINE_MANIFEST_KEY` in environment `engine-approval` only.

APK signature schemes: v1 off (minSdk 26 ≥ 24), v2 and v3 on, checked on every release APK by [release.yml](#releaseyml) step 3; v3 keeps key rotation (a proof-of-rotation lineage, v3.1 on API 33+) possible if the key is ever compromised ([APK Signature Scheme v3](https://source.android.com/docs/security/features/apksigning/v3)).

### Gradle signing configuration

Read only from `NEUTRODYNE_KEYSTORE*` environment variables; unsigned when absent (PR builds, the nightly reproducibility check), debug-signed only for CI test runs that pass `-PtestBuildType=release` ([Gradle Managed Devices](#gradle-managed-devices)). Lives in `:app` (01's build-variants sketch leaves this to 09):

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
        signingConfig = when {
            providers.environmentVariable("NEUTRODYNE_KEYSTORE").isPresent -> signingConfigs.getByName("release")
            providers.gradleProperty("testBuildType").orNull == "release" -> signingConfigs.getByName("debug") // CI test runs only
            else -> null                                                                                        // unsigned: PRs, repro check
        }
    }
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
}
```

AGP's signing configuration has no rotation-lineage option, so a rotated release (below) is signed by `apksigner` in `release.yml` after the unsigned build (Unverified for AGP 9.4; checked at the M11b rehearsal).

### Key loss or compromise

| Event | Consequence | Response |
|---|---|---|
| One copy lost | none | re-create a second copy from another copy; record it in the yearly issue |
| All copies lost | Installed apps can never be updated again: every user must uninstall and reinstall an APK signed with a new key (Android refuses an update with another signer) and restore their library from a backup ZIP (R1.7; Auto Backup data is bound to the signer, [05 Platform constraints](05-groups-opml-backup.md#platform-constraints)); a later developer registration of `ch.lkmc.neutrodyne` becomes impossible ([FAQ](https://developer.android.com/developer-verification/guides/faq)); rotation cannot help, because it needs the old key | new key ceremony; a release under the same `applicationId` with a new certificate; README, release body and the in-app updater's `Failed(CERTIFICATE_MISMATCH)` text explain the reinstall; an announcement pinned in the repository |
| Compromise suspected | An attacker with the key can sign updates that Android, the in-app updater and Obtainium accept | the v3.1 rotation runbook below in the next release; rotate every CI secret; announce with both fingerprints |

**v3.1 rotation runbook** (compromise only; rehearsed with throwaway keys in M11b, PLAN M11 AC12):

1. Key ceremony for key B (same steps as above); keep key A, which the rotation needs.
2. On the offline machine: `apksigner rotate --out neutrodyne.lineage --old-signer --ks neutrodyne-release.p12 --new-signer --ks neutrodyne-release-b.p12` ([apksigner](https://developer.android.com/tools/apksigner)); commit `neutrodyne.lineage` (public data) to the repository.
3. `release.yml` builds unsigned and signs each APK with `apksigner sign --ks A.p12 --next-signer --ks B.p12 --lineage neutrodyne.lineage`: by default the v3.1 block (API 33+) presents B, while the v3.0 and v2 blocks keep A; `--rotation-min-sdk-version 28` would let API 28–32 use B too. Both keys sit in the `release` environment for the rotation releases.
4. Effect: Android 13+ devices accept the update and trust B from then on; API 26–27 verify only v2 and keep trusting A alone, and AOSP calls rotation "not recommended" for API 31 and below ([v3 scheme](https://source.android.com/docs/security/features/apksigning/v3)) — so older devices stay exposed to a stolen A, a residual risk stated in the announcement.
5. `NEUTRODYNE_CERT_SHA256` becomes B and `NEUTRODYNE_PREVIOUS_CERT_SHA256` becomes A; the README and release bodies list B and, marked as previous, A; `neutrodyne-update.json` names B as `certSha256` and A in `previousCertSha256`. The in-app updater accepts an APK whose certificate set (signer plus history, as the device's API level reports it) contains the installed app's current certificate and one of the manifest's certificates ([Download and verification](#download-and-verification)): API 33+ devices see B with A in its history, API 26–32 devices see A alone, so both update. `release.yml` step 3 and `check-update-json.sh` check the per-scheme signers against the two variables.
6. Remove A from the CI environment after the transition releases; a later rotation extends the lineage from B (`apksigner rotate --in neutrodyne.lineage …`).

---

## Distribution channels

Serves R6.1, N12; mitigates risks P3, P7. Delivered in M0 (immutable GitHub pre-releases with every asset from `v0.1.0-beta.1`, Codeberg mirror), M11b (release hardening, mirror decision). Honours [D2](../PLAN.md#3-key-decisions), [D3](../PLAN.md#3-key-decisions), [D61](../PLAN.md#3-key-decisions), [D77](../PLAN.md#3-key-decisions), [D79](../PLAN.md#3-key-decisions), [PO-2](../PLAN.md#po-2-distribution-channels) (resolved: GitHub Releases only), [PO-34](../PLAN.md#48-further-product-owner-decisions) default.

**GitHub Releases is the only channel.** There is no Google Play, F-Droid, IzzyOnDroid or other store listing; any other copy is not Neutrodyne's. Users install an APK from the repository's releases page ([Developer verification](#developer-verification) describes what Android asks for), and updates arrive through the [in-app updater](#in-app-updater) or, for users who prefer it, Obtainium. Engine updates of the YouTube engine are not app releases: they come from yt-dlp's own GitHub releases, approved through GitHub Pages ([engine-canary.yml](#engine-canaryyml)).

| Channel | Artefact | Signing | Latency | Betas | From |
|---|---|---|---|---|---|
| GitHub Releases | the [release assets](#release-assets) of each tag | our key (v2 + v3) | minutes after the tag | GitHub pre-releases | M0 |
| In-app updater | the release's APK for the device's ABI, found through `neutrodyne-update.json` | our key, checked before install | ≤ 24 h (daily check) or on "Check now" | opt-in beta channel (PO-33) | M11a |
| Obtainium (third-party, user-installed) | the same APK, chosen by its APK filter | our key | per Obtainium's schedule | "Include prereleases" | M0 (documented) |

### Release assets

Per tag `vX.Y.Z[-beta.N|-rc.N]` ([D79](../PLAN.md#3-key-decisions)); `prerelease` for suffixed tags; "latest" only for stable tags that are the newest stable ([release.yml](#releaseyml) step 1):

| Asset | Content |
|---|---|
| `neutrodyne-{v}-arm64-v8a.apk` | 64-bit ARM phones and tablets; with the YouTube engine |
| `neutrodyne-{v}-x86_64.apk` | x86_64 devices and emulators (for example Intel or AMD Chromebooks; Unverified which of them run Android apps as x86_64); with the engine |
| `neutrodyne-{v}-armeabi-v7a.apk` | 32-bit ARM devices; no engine, YouTube in external mode ([D77](../PLAN.md#3-key-decisions)) |
| `neutrodyne-{v}-mapping.txt` | the R8 mapping of the release (one per variant, shared by the three APKs; `-dontobfuscate` keeps traces readable, the mapping still fixes line numbers after inlining) |
| `neutrodyne-update.json` | the [update manifest](#update-manifest) the in-app updater reads |
| `SHA256SUMS` | `sha256sum` format over the five files above |

All three APKs share one `versionCode`, are signed with v2 and v3 (no v1) and pass [Build-output checks](#build-output-checks). There is **no universal APK** (it would carry two engines, ≈ 45–60 MB; [D77](../PLAN.md#3-key-decisions)). The emergency build without the engine ([01](01-foundation.md#emergency-build-without-the-engine)) publishes the same asset set, with no engine in any APK.

**Integrity** (N12): every release is published as an **immutable release** — created as a draft, every asset uploaded, then published; afterwards assets cannot be changed or deleted and the tag cannot move, and a deleted release's tag name can never be reused ([immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases), GA since 2025-10-28, [changelog](https://github.blog/changelog/2025-10-28-immutable-releases-are-now-generally-available/)). Publishing creates GitHub's release attestation (verified with `gh release verify` and `gh release verify-asset`); `actions/attest` adds SLSA build-provenance attestations for the APKs, the manifest and `SHA256SUMS` (Build Level 2 on GitHub-hosted runners; GitHub calls attestations "not a security guarantee" on their own, [artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations)). The signing certificate is the trust anchor users and the updater check; the attestations show which workflow run built a file.

### Release body

Generated by `release.yml` from `changelogs/<versionCode>.txt` and this template (`{…}` filled in; the fingerprint from `NEUTRODYNE_CERT_SHA256`):

```text
{changelog text}

Install: pick the APK for your device —
  neutrodyne-{v}-arm64-v8a.apk    most phones and tablets
  neutrodyne-{v}-x86_64.apk       x86_64 devices and emulators
  neutrodyne-{v}-armeabi-v7a.apk  older 32-bit phones (YouTube opens in the YouTube app)
Install and update guide: {repoUrl}#install-and-update

Signing certificate (AppVerifier format):
  ch.lkmc.neutrodyne
  {AA:BB:…:FF}

Verify a download:
  sha256sum --check --ignore-missing SHA256SUMS
  gh release verify-asset {tag} neutrodyne-{v}-arm64-v8a.apk -R {owner}/Neutrodyne
  gh attestation verify neutrodyne-{v}-arm64-v8a.apk -R {owner}/Neutrodyne
```

`{repoUrl}#install-and-update` assumes the README heading "Install and update" (the README is maintained with the PLAN; its content is drafted in [Developer verification](#readme-install-and-update)). Pre-release bodies start with "Tester build for milestone Mn." ([Changelogs and release notes](#changelogs-and-release-notes)).

### GitHub Releases and Obtainium

- README badge: `https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/<owner>/Neutrodyne` ([Obtainium deep links](https://wiki.obtainium.imranr.dev/deep_links/)); 08's help page offers the `obtainium://add/{repoUrl}` link directly ([08 Install and updates help](08-ui-ux.md#install-and-updates-help)).
- APK choice: each release has three APKs, so Obtainium needs an APK filter regex per device, for example `neutrodyne-.*-arm64-v8a\.apk$`, or its architecture filter (`autoApkFilterByArch` in its source, [Obtainium](https://github.com/ImranR98/Obtainium)). Obtainium considers only APK assets, so `SHA256SUMS`, the manifest and the mapping do not confuse it (read from its source on 2026-10-05). The tag is always `v` + `versionName`, which Obtainium's version detection expects.
- Obtainium's GitHub source queries the REST API (`api.github.com/…/releases`), so GitHub's limit of 60 unauthenticated requests per hour per IP applies to it ([REST limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)); users behind a shared IP can add a GitHub token in Obtainium. Neutrodyne's own updater never uses the REST API ([In-app updater](#checking)).
- When Obtainium is the installer of record, the in-app updater stays off and says so (R6.3, [Installer of record and Obtainium](#installer-of-record-and-obtainium)). Developer verification applies to Obtainium's installs like to any other installer.

### Beta channels

GitHub pre-releases (`vX.Y.Z-beta.N`, `vX.Y.Z-rc.N`, flagged `prerelease`, never "latest") carry every milestone's tester build (PLAN DoD). Stable users never see them: `releases/latest/download/…` resolves to the most recent non-prerelease, non-draft release ([latest release](https://docs.github.com/en/rest/releases/releases#get-the-latest-release), [linking to releases](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases)). Testers opt in with the in-app beta channel (Settings › Updates › "Beta versions", off by default, [PO-33](../PLAN.md#48-further-product-owner-decisions)), which reads the repository's `releases.atom` ([Checking](#checking)), or with Obtainium's "Include prereleases". A beta user also receives every newer stable release; turning the channel off keeps the installed beta until a stable release with a higher `versionCode` appears.

### Mirror

GitHub is the single distribution and update channel (risk P7): a DMCA notice, an abuse report or an account suspension would remove the releases, the update manifest and the engine manifest (GitHub Pages) at once; youtube-dl's 2020 takedown was reversed, but only after weeks ([GitHub blog](https://github.blog/2020-11-16-standing-up-for-developers-youtube-dl-is-back/)).

- **From M0 (PO-34 default):** a Codeberg git push mirror of `main`, `release/*` and every tag (nightly `mirror` job and the last step of `release.yml`, deploy key `CODEBERG_MIRROR_KEY`), so the source history and the build recipe survive. Unverified: Codeberg's current policy on mirrors of this size and kind; checked when the mirror is set up.
- **Decided by the PO before M11b:** whether release assets and both manifests are mirrored too and whether the app carries a fallback URL. If yes, the design stays safe because trust never depends on the host: the in-app updater installs only an APK signed with our certificate and newer than the installed one ([Download and verification](#download-and-verification)), and the engine manifest is Ed25519-signed ([04 Trust chain](04-youtube.md#trust-chain)); a fallback host could only withhold updates or serve genuine ones. A compiled-in fallback would follow GitHub's 404 or a DNS failure only, never a successful GitHub answer.
- During an outage installed apps keep working: the updater's checks fail (`NETWORK` or `MANIFEST_INVALID`), the bundled and active engine versions stay usable, and "Reset to bundled" always works.

---

## In-app updater

Serves R6.2–R6.4, N7, N12; mitigates risks P3, P7, T17. Delivered in M0 (`:update:api`/`:update:impl` module stubs; `release.yml` publishes `neutrodyne-update.json` from the first pre-release), M11a (everything below; may start after M6b). Honours [D78](../PLAN.md#3-key-decisions), [D80](../PLAN.md#3-key-decisions), [PO-31](../PLAN.md#48-further-product-owner-decisions) (default Notify), [PO-33](../PLAN.md#48-further-product-owner-decisions) (beta off), [PO-36](../PLAN.md#48-further-product-owner-decisions) (notice timing). Screens, notification texts and the help page: [08 Updates settings](08-ui-ux.md#updates-settings), [08 Install and updates help](08-ui-ux.md#install-and-updates-help); the busy signals it waits on: [06 App updates and playback](06-playback.md#app-updates-and-playback), [07 App update](07-downloads.md#app-update); permissions and components: [01 Manifest and permissions](01-foundation.md#manifest-and-permissions).

GitHub is the only channel and most users never install Obtainium, so the app finds, verifies and installs its own updates — and, once Google's verification gate applies, explains a blocked update instead of failing silently. It never contacts `api.github.com`, never installs an APK whose hash, package, version or certificate does not match, and never installs while audio plays or a download runs. Debug builds have it off (`Disabled(DEBUG_BUILD)`).

### Modules and API

`:update:api` (JVM, [D13](../PLAN.md#3-key-decisions)), package `ch.lkmc.neutrodyne.update.api`; features (08's `:feature:settings`) and `:app`'s root use only this:

```kotlin
interface AppUpdater {
    val state: StateFlow<UpdateState>
    suspend fun checkNow(): UpdateState         // one user-initiated check, also in mode OFF; refused only in
                                                // Disabled(DEBUG_BUILD) and Disabled(MANAGED_BY_OTHER_INSTALLER)
    fun download()                              // from Available
    fun installWhenIdle()                       // from ReadyToInstall, PendingUserAction or Blocked(VERIFICATION_NETWORK);
                                                // a user action: plain commit once idle (Installing step 4 (a));
                                                // never interrupts playback or a running download
    fun skip(versionCode: Long)                 // from Available: hide this version until a newer one appears
    fun openReleasePage()                       // "Download in browser": ACTION_VIEW of the APK asset URL (else the release page)
    fun openSystemExplanation()                 // "Continue in Android": starts the EXTRA_INTENT of the last verification failure;
                                                // only while state is Blocked(systemExplanation = true)
}
sealed interface UpdateState {
    data class Disabled(val reason: UpdateDisabledReason) : UpdateState
    data class Idle(val lastCheckAtMs: Long?) : UpdateState
    data object Checking : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val bytes: Long, val total: Long) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo) : UpdateState
    data class WaitingForIdle(val info: UpdateInfo) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class PendingUserAction(val info: UpdateInfo) : UpdateState
    data class Blocked(val info: UpdateInfo, val reason: InstallBlockReason,
                       val systemExplanation: Boolean = false) : UpdateState   // true: Android supplied an explanation intent
    data class Failed(val info: UpdateInfo?, val error: UpdateError) : UpdateState
}
enum class UpdateDisabledReason { DEBUG_BUILD, MODE_OFF, MANAGED_BY_OTHER_INSTALLER }
data class UpdateInfo(val versionName: String, val versionCode: Long, val channel: UpdateChannel, val abi: String, val apkName: String,
                      val sizeBytes: Long, val sha256: String, val certSha256: String, val notes: String, val publishedAt: String,
                      val releaseUrl: String)
enum class UpdateMode { OFF, NOTIFY, AUTOMATIC }
enum class UpdateChannel { STABLE, BETA }
enum class InstallBlockReason { DEVELOPER_UNVERIFIED, VERIFICATION_NETWORK, INSTALL_PERMISSION_MISSING, BLOCKED_BY_POLICY, INCOMPATIBLE, STORAGE, UNKNOWN }
enum class UpdateError { NETWORK, RATE_LIMITED, MANIFEST_INVALID, NO_APK_FOR_ABI, HASH_MISMATCH, CERTIFICATE_MISMATCH, PACKAGE_MISMATCH, NOT_NEWER, INSTALL_FAILED }
interface UpdateNotices { val pending: StateFlow<UpdateNotice?>; fun dismiss(notice: UpdateNotice) }
enum class UpdateNotice { FIRST_RUN_CHOICE, VERIFICATION_ENFORCEMENT, WHATS_NEW }
```

`:update:impl` (package `ch.lkmc.neutrodyne.update.impl`; the only module that touches `PackageInstaller`, 01 rule) binds both interfaces in `UpdateModule`:

| Class | Responsibility |
|---|---|
| `AppUpdaterImpl`, `UpdateNoticesImpl` | state machine below; notice rules ([Notices](#notices)) |
| `GitHubUpdateSource` | stable and beta lookups ([Checking](#checking)) on 01's API client |
| `UpdateManifestParser`, `VersionScheme` | manifest parsing and validation; [D63](../PLAN.md#3-key-decisions)'s `versionCode` from a version name (the same formula as `release.sh`) |
| `UpdateCheckWorker` | `app-update-check`, `app-update-check-now` |
| `UpdateDownloadWorker` | `app-update-download`: its own resumable transfer on 01's DOWNLOAD client — never 07's download engine or a `download` row (07's rows are episode-bound, [D78](../PLAN.md#3-key-decisions)) |
| `ApkVerifier`, `ApkInspector` | checks before install; `ApkInspector` wraps `PackageManager.getPackageArchiveInfo` so the rules are testable on the JVM |
| `SelfInstaller`, `UpdateInstallWorker`, `UpdateStatusReceiver` | `PackageInstaller` sessions per API level, `app-update-install`, the session result |
| `InstallIdleGate` | idle = `!PlaybackStateSource.busy && !DownloadProgressSource.busy` |
| `InstallerOfRecordDetector` | Obtainium detection |
| `VerificationFailureMapper` | session result → `UpdateState` |
| `UpdateNotifier` | the `updates` channel's single notification `NOTIF_ID_UPDATE = 4200` (texts: 08) |
| `VerificationTimeline` | compiled-in notice and enforcement dates ([Notices](#notices)) |

```mermaid
stateDiagram-v2
  [*] --> Idle
  Idle --> Checking: daily work or Check now
  Checking --> Idle: up to date or skipped
  Checking --> Available: newer release
  Checking --> Failed: network, rate limit, bad manifest
  Available --> Downloading: Download, or Automatic mode
  Downloading --> ReadyToInstall: size, SHA-256, package, version, certificate verified
  Downloading --> Failed: mismatch, file deleted
  ReadyToInstall --> WaitingForIdle: playing or downloading
  WaitingForIdle --> Installing: idle
  ReadyToInstall --> Installing: idle
  Installing --> PendingUserAction: Android asks the user
  PendingUserAction --> Installing: user confirms
  Installing --> Blocked: verification, permission, policy, storage
  Installing --> Failed: other failure
  Installing --> [*]: success, the process restarts
```

`Disabled` overrides every other state while it applies; `state` is recomputed at process start from the settings, `noBackupFilesDir/updates/pending.json` (the `UpdateInfo` of a verified, downloaded APK, so `ReadyToInstall` survives process death) and the installer of record.

### Update manifest

`neutrodyne-update.json`, an asset of every release (written by `make-update-json.sh`, validated by `check-update-json.sh` before the draft is published, [release.yml](#releaseyml) steps 5–6):

```json
{ "schema": 1, "versionName": "1.0.0", "versionCode": 1000095, "minSdk": 26, "prerelease": false,
  "published": "2027-…Z", "certSha256": "AA:BB:…", "previousCertSha256": [], "notes": "…", "releaseUrl": "https://github.com/<owner>/Neutrodyne/releases/tag/v1.0.0",
  "apks": [ { "abi": "arm64-v8a", "file": "neutrodyne-1.0.0-arm64-v8a.apk", "size": 0, "sha256": "…" },
            { "abi": "x86_64", "file": "…", "size": 0, "sha256": "…" }, { "abi": "armeabi-v7a", "file": "…", "size": 0, "sha256": "…" } ] }
```

| Field | `check-update-json.sh` (CI) | `UpdateManifestParser` (app) |
|---|---|---|
| `schema` | 1 | 1; an unknown higher schema → `Failed(MANIFEST_INVALID)` and the card suggests "Download in browser" |
| `versionName`, `versionCode` | equal the tag without `v`, `gradle.properties` and D63's formula | `versionCode == VersionScheme.versionCode(versionName)` |
| `minSdk`, `prerelease` | 26; true ⇔ the tag has a suffix | `minSdk ≤ SDK_INT`, else `Blocked(INCOMPATIBLE)` without download |
| `published`, `notes`, `releaseUrl` | ISO-8601 UTC; equal to `changelogs/<versionCode>.txt` (≤ 500 characters); `{repoUrl}/releases/tag/{tag}` | `releaseUrl` must start with `BuildInfo.repoUrl` |
| `certSha256`, `previousCertSha256[]` | `certSha256` equals `NEUTRODYNE_CERT_SHA256`, `previousCertSha256` equals the variable `NEUTRODYNE_PREVIOUS_CERT_SHA256` (empty except after a [key rotation](#key-loss-or-compromise)); every signer `apksigner` reports for each APK, per scheme, is one of them | the accepted certificate set of [Download and verification](#download-and-verification) rule 5 |
| `apks[]` | exactly the ABIs built (three), each `file` present with matching `size` and `sha256` | entries with an unknown `abi` are ignored; `size` ≤ 100 MB |

The manifest is not signed: the APK's signing certificate is the trust anchor, so a forged manifest can at worst point to a genuine older or missing file, which the version and certificate checks reject. Unknown fields are ignored, so later schema-1 additions stay compatible; the whole manifest is ≤ 64 KB.

### Checking

| Channel | Request (01's API client, standard User-Agent, no cookies, no token) | Picks |
|---|---|---|
| `STABLE` (default) | `GET {repoUrl}/releases/latest/download/neutrodyne-update.json` (`BuildInfo.updateManifestUrl`): `github.com` answers 302 to `/releases/download/<tag>/…`, which redirects to a short-lived signed `release-assets.githubusercontent.com` URL (≈ 1 h validity, observed 2026-10-05) — no REST API ([linking to releases](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases)) | the latest stable release; pre-releases and drafts are never "latest" |
| `BETA` (PO-33) | `GET {repoUrl}/releases.atom` (`BuildInfo.releasesAtomUrl`); the tag of each entry from its `link rel="alternate"` (`…/releases/tag/<tag>`), parsed with the platform `XmlPullParser` (`:update:impl` has no `:feeds` dependency, 01); then `GET {repoUrl}/releases/download/<tag>/neutrodyne-update.json` for the entry with the highest `VersionScheme.versionCode(tag)` | the newest release of any kind, stable included. Unverified: how many entries the feed carries (≥ the last 10 is assumed) and that drafts never appear |

A check finds an update when the manifest's `versionCode` is higher than `BuildInfo.versionCode` and not equal to `updates.skipped_version_code`; it then selects the `apks[]` entry for the first ABI in `Build.SUPPORTED_ABIS` that has one — `SUPPORTED_ABIS[0]` on every device the release supports ([D78](../PLAN.md#3-key-decisions)), so a 64-bit phone that runs the `armeabi-v7a` APK moves to the 64-bit APK with its next update (08's "Get the 64-bit version"). No match → `Failed(NO_APK_FOR_ABI)`.

Failures: I/O and timeouts → `Failed(NETWORK)`; HTTP 403 or 429 from GitHub → `Failed(RATE_LIMITED)` with backoff (GitHub publishes no limit for unauthenticated `releases/download` and Atom requests; Unverified, so the updater assumes there is one); 404, an unparsable or invalid manifest → `Failed(MANIFEST_INVALID)`. `api.github.com` (60 unauthenticated requests per hour per IP, shared under CGNAT, [REST limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)) is never called; `AppUpdaterTest` asserts it.

| Work ([D78](../PLAN.md#3-key-decisions)) | Type and policy | Enqueued |
|---|---|---|
| `app-update-check` | periodic 24 h with a 6 h flex window, network `CONNECTED`, `ExistingPeriodicWorkPolicy.UPDATE`, exponential backoff from 1 h | by the order-200 initializer (01) while the mode is not `OFF` and the updater is not `Disabled`; the first enqueue has an initial delay of 24 h, so the [first-run card](#notices) is seen before the first scheduled check |
| `app-update-check-now` | one-time, `REPLACE`, network `CONNECTED` | "Check now" (`checkNow()`, at most once per 60 s); also allowed in mode `OFF` as a one-off user action (answers 08's question) |
| `app-update-download` | one-time, `KEEP`; network `CONNECTED` after a tap on Download, `UNMETERED` in mode `AUTOMATIC`; expedited (API 31+ only) after a tap | `download()`; in `AUTOMATIC` right after a check found an update |
| `app-update-install` | one-time, `REPLACE` | `installWhenIdle()`; in `AUTOMATIC` after a verified download. Whenever the commit is not handed to the platform's constraints ([Installing](#installing) step 4 (c): API 26–33, or another installer of record): returns `Result.retry()` (linear backoff 10 min) while `InstallIdleGate` reports busy or an activity of the app is started |

Modes (`updates.mode`, [PO-31](../PLAN.md#48-further-product-owner-decisions) default `NOTIFY`): `OFF` — no scheduled work, `Disabled(MODE_OFF)`, "Check now" still works; `NOTIFY` — daily check, notification when an update is available, download and install on the user's taps; `AUTOMATIC` — daily check, download on an unmetered network, install when idle, silently where Android allows (API 31+), otherwise with the confirmation prompt ([Installing](#installing)). Changing mode or channel re-enqueues or cancels `app-update-check` at once.

### Download and verification

`UpdateDownloadWorker` writes `noBackupFilesDir/updates/neutrodyne-{v}-{abi}.apk.part` (excluded from backups by the platform; 05) from the asset URL `{repoUrl}/releases/download/<tag>/<file>`, resuming with `Range: bytes=<part length>-` and accepting only a `206` whose `Content-Range` starts there (otherwise it restarts from 0). Every attempt starts at `github.com` again, because the signed redirect target expires. Progress feeds `Downloading(bytes, total)` (≤ 2 Hz). It needs ≥ 2 × `size` + 50 MB free, else `Blocked(STORAGE)`.

`ApkVerifier`, in order; the first failure deletes the file and ends in `Failed(error)`:

| # | Check | Error |
|---|---|---|
| 1 | file length = manifest `size` | `HASH_MISMATCH` |
| 2 | SHA-256 = manifest `sha256` | `HASH_MISMATCH` |
| 3 | `ApkInspector.inspect(file)` (`getPackageArchiveInfo` with `GET_SIGNING_CERTIFICATES` on API 28+, `GET_SIGNATURES` on 26–27): package name `ch.lkmc.neutrodyne` (the release package; never `.debug`) | `PACKAGE_MISMATCH` |
| 4 | archive `longVersionCode` = manifest `versionCode` > installed `versionCode` | `NOT_NEWER` |
| 5 | the archive's certificate set — its signer plus, after a rotation, its signing-certificate history, as `getPackageArchiveInfo` reports them on this API level (after a v3.1 rotation API 33+ sees the new signer B with A in its history, API 26–32 see only the original signer A of the v2/v3.0 blocks) — contains the installed app's current signing certificate, and contains at least one of the manifest's `certSha256` and `previousCertSha256[]` ([Key loss or compromise](#key-loss-or-compromise)) | `CERTIFICATE_MISMATCH` |

On success the file is renamed to `.apk`, `pending.json` is written and the state becomes `ReadyToInstall`. Android enforces signature continuity anyway; the checks make the failure explicit, avoid a broken session and keep a tampered download from ever reaching the installer. Superseded, skipped or installed APKs are deleted at the next start; the directory holds at most one APK.

### Installing

`SelfInstaller` (run by `UpdateInstallWorker`, or directly when the user taps "Install now" in the foreground):

1. `packageManager.canRequestPackageInstalls()` false → `Blocked(INSTALL_PERMISSION_MISSING)`; 08's sheet explains and opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:ch.lkmc.neutrodyne` ([Settings](https://developer.android.com/reference/android/provider/Settings)). Users who installed from a browser granted the browser, not Neutrodyne, so the first self-update always asks once.
2. `InstallIdleGate.isIdle()` false → `WaitingForIdle` (re-checked when either busy flow turns false, and by the worker's retry).
3. Session: `PackageInstaller.SessionParams(MODE_FULL_INSTALL)`, `setAppPackageName("ch.lkmc.neutrodyne")`, `setSize(size)`; API 31+: `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` with the normal permission `UPDATE_PACKAGES_WITHOUT_USER_ACTION` — silent only when Android's conditions hold (among them: the installer is updating itself, which always applies here, and the APK targets a recent enough SDK; [SessionParams](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams)); the APK is streamed into the session and `pending.json` records `whatsNewVersionCode`.
4. Commit with an explicit, mutable `PendingIntent` to `UpdateStatusReceiver` (not exported; mutable so that the system can add the result extras). The gate is re-checked immediately before every commit; which commit depends on who asked and who installed the app:
   - **(a) User-initiated** ("Install now" tapped in the foreground): plain `Session.commit` right after the gate passes — the tap is the consent. `GENTLE_UPDATE` is not used here: its "app not interacting" constraint counts playing audio, network traffic and **being visible to the user** as interacting ([InstallConstraints](https://developer.android.com/reference/android/content/pm/PackageInstaller.InstallConstraints.Builder)), so a foreground "Install now" would wait until the user leaves the app, for up to the timeout.
   - **(b) Unattended on API 34+ with Neutrodyne as installer of record** (`getInstallSourceInfo(packageName).installingPackageName` is `ch.lkmc.neutrodyne`, i.e. after the first self-update): `commitSessionAfterInstallConstraintsAreMet(sessionId, receiver, InstallConstraints.GENTLE_UPDATE, 24 h)` after the gate passes — the platform additionally waits while the app plays audio, uses the network or is visible; the 10-min paused window of 06's `busy` is covered only by our gate. The platform accepts this call only from the installer of record (it throws `SecurityException` "if the given packages' installer of record doesn't match the caller's own package name", [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller)); a `SecurityException` falls back to (c), and the constraint timeout (`STATUS_FAILURE_TIMEOUT`) returns to `ReadyToInstall`.
   - **(c) Unattended otherwise** (API 26–33, or an installer of record other than Neutrodyne — the normal case for the first self-update after a browser, Files or ADB install): plain `commit` only while the gate passes **and** no activity of the app is started (`ProcessLifecycleOwner` below `STARTED`), so a silent install on API 31–33 never kills the app while the user looks at it; otherwise `WaitingForIdle`, re-checked when the app goes to the background and by the worker's retry.

   Below API 31 Android always shows its confirmation, whichever commit is used.
5. `UpdateStatusReceiver` hands `EXTRA_STATUS` to `VerificationFailureMapper`:

| Result | Extra | State |
|---|---|---|
| `STATUS_SUCCESS` | — | the process is killed by the install; at the next start `WHATS_NEW` becomes pending and the APK is deleted |
| `STATUS_PENDING_USER_ACTION` | `Intent.EXTRA_INTENT` | `PendingUserAction`; app in the foreground: start the intent; otherwise post "Tap to finish updating" with it (background activity starts are restricted) |
| `STATUS_FAILURE_ABORTED` | `EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON` = `DEVELOPER_VERIFICATION_FAILED_REASON_DEVELOPER_BLOCKED` (API 36.1+, [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller)) | `Blocked(DEVELOPER_UNVERIFIED)`: 08's sheet with the advanced flow, "Download in browser" and ADB; `systemExplanation = true` when the result also carries `Intent.EXTRA_INTENT` ("an intent that can provide additional context", same page), which `:update:impl` keeps in memory only and `openSystemExplanation()` starts (08's "Continue in Android") |
| same | `…_NETWORK_UNAVAILABLE` | `Blocked(VERIFICATION_NETWORK)`; `installWhenIdle()` retries |
| same | `…_UNKNOWN` | `Blocked(UNKNOWN)` |
| `STATUS_FAILURE_ABORTED` | no verification extra | a user cancel → back to `ReadyToInstall`, no error — but only when Android's confirmation was actually shown for this session (`PendingUserAction` was entered); otherwise, and on the second consecutive `ABORTED` for the same `versionCode`, `Blocked(UNKNOWN)` with the help sheet and "Download in browser" (below API 36.1 a block enforced through Play Protect may arrive this way; Unverified) |
| `STATUS_FAILURE_BLOCKED` | — | `Blocked(BLOCKED_BY_POLICY)` |
| `STATUS_FAILURE_INCOMPATIBLE` / `STATUS_FAILURE_STORAGE` | — | `Blocked(INCOMPATIBLE)` / `Blocked(STORAGE)` |
| `STATUS_FAILURE_TIMEOUT` (API 34+) | — | the constraint commit of step 4 (b) timed out → back to `ReadyToInstall` |
| `STATUS_FAILURE_CONFLICT`, `STATUS_FAILURE_INVALID`, `STATUS_FAILURE`, a session `IOException` | `EXTRA_STATUS_MESSAGE` (logged, redacted) | `Failed(INSTALL_FAILED)` |

Why the mapping matters: per AOSP an installer targeting API > 36 — Neutrodyne targets 37 — receives a blocking "developer not verified" result as `STATUS_FAILURE_ABORTED` directly, without a bypass prompt ([PackageInstallerSession](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/pm/PackageInstallerSession.java), 01 P39). Unverified (risk T17): whether, with the advanced flow on, a session from a self-updater succeeds silently, shows Google's "Install anyway" warning, or fails; and how devices below Android 16 QPR2, where Google enforces through Play Protect, report a block (possibly `STATUS_FAILURE_BLOCKED`). "Download in browser" (`openReleasePage()`: `ACTION_VIEW` of `{repoUrl}/releases/download/<tag>/<file>`) is therefore offered on every `Blocked` and `Failed` state: the browser downloads the APK and Android's own installer shows whatever the verification policy allows. When a verification failure carries `Intent.EXTRA_INTENT`, `Blocked.systemExplanation` is true and 08's sheet offers "Continue in Android" (open question 23). The README and 08 never promise silent updates.

### Idle gate

`InstallIdleGate` is idle when `PlaybackStateSource.busy` (06: playing, buffering, a call's transient focus loss, and 10 min after the player stopped being engaged while the service runs) and `DownloadProgressSource.busy` (07: a transfer of this process is resolving, downloading or verifying) are both false; it re-checks both immediately before every commit (PLAN M11 AC4). Unattended commits without the platform's constraints ([Installing](#installing) step 4 (c)) additionally require that no activity of the app is started. It does not wait for refreshes, imports, restores or cleanup: they roll back or resume after process death like after any kill (03, 05, 07). A YouTube-engine activation is unrelated: it restarts only `:ytx` ([04 Update flow](04-youtube.md#update-flow)).

### Installer of record and Obtainium

`InstallerOfRecordDetector` reads `PackageManager.getInstallSourceInfo(packageName).installingPackageName` (API 30+; `getInstallerPackageName` on 26–29) at start and before every check. `dev.imranr.obtainium` or `dev.imranr.obtainium.fdroid` (Unverified both IDs; 01 declares them in `<queries>`, 01 P26) → `Disabled(MANAGED_BY_OTHER_INSTALLER)`: no work is scheduled, the first-run card is not raised, and Settings › Updates says that Obtainium manages updates (R6.3). After a self-update Neutrodyne is its own installer of record; when Obtainium later installs an update, the updater switches itself off. A shell installer (ADB, or Obtainium in Shizuku mode — Unverified attribution) leaves the updater on (open question 17).

### Notices

`UpdateNoticesImpl` raises at most one pending `UpdateNotice`; `:app`'s root shows it through 08's keys and `dismiss` records it:

| Notice | Raised when | Recorded in |
|---|---|---|
| `FIRST_RUN_CHOICE` | first start of a build with the updater (M11a or later), not `Disabled(DEBUG_BUILD)` or `MANAGED_BY_OTHER_INSTALLER` (PO-31's disclosure; "Turn off" writes `updates.mode = OFF`) | `updates.first_run_choice_done` |
| `VERIFICATION_ENFORCEMENT` | `now ≥ VerificationTimeline.NOTICE_FROM`, not in debug builds, once per installation, on every device (the app cannot tell reliably whether a device is certified). Before `GLOBAL_ENFORCEMENT` (or while it is unknown) 08 shows the pre-enforcement notice; at or after it — which is what installations made after enforcement, through the advanced flow, see on their first start — the post-enforcement hint ("If you chose '7 days' … switch to 'indefinitely'") | `updates.verification_notice_shown_at` |
| `WHATS_NEW` | the first start whose `BuildInfo.versionCode` equals the `whatsNewVersionCode` that `SelfInstaller` stored before its commit; notes from `pending.json` (empty → not raised) | `updates.whats_new_version_code` |

`VerificationTimeline` (compiled-in constants, [PO-36](../PLAN.md#48-further-product-owner-decisions)): `NOTICE_FROM = 2026-12-01`, or an earlier date as soon as Google names the global enforcement date; `GLOBAL_ENFORCEMENT` = Google's date, null until announced. Because it is a date comparison on the device, a build shipped before December shows the notice on 2026-12-01 even if the user never updates again. A change of either constant ships in the next tester or stable release ([Watch and notice timing](#watch-and-notice-timing)).

### Privacy and failure modes

- Network: `github.com` and `release-assets.githubusercontent.com` only, listed as `app-updates` in the [network inventory](#network-inventory); GitHub sees the IP address and the standard User-Agent (app version, Android release), no identifier; no request carries a cookie or token.
- No update state is backed up except the portable `updates.mode` and `updates.channel` (05's whitelist); a restored mode never overrides `Disabled(MANAGED_BY_OTHER_INSTALLER)` or `Disabled(DEBUG_BUILD)` (05).
- Process death at any step: `pending.json`, the `.part` file and WorkManager resume where they stopped; a session that the process lost is abandoned at the next start (`PackageInstaller.getMySessions()`), and the verified APK is installed through a new one.
- Logs carry versions and states, never the signed redirect URLs (01's `Redactor` masks query values).

### Tests

| Test class | Module, runner | Cases | Milestone |
|---|---|---|---|
| `UpdateManifestParserTest` | `:update:impl`, JVM | schema 1 with unknown fields; each field rule of [Update manifest](#update-manifest); `versionCode` vs D63's formula on the table examples of [Version scheme](#version-scheme) (`VersionScheme` and `release.sh` share them); ABI selection by `SUPPORTED_ABIS` order; `NO_APK_FOR_ABI` | M11a |
| `GitHubUpdateSourceTest` | `:update:impl`, JVM + MockWebServer (hosts rewritten) | stable through the two-hop redirect; a pre-release is never "latest"; Atom with stable and pre-release entries picks the highest `versionCode`; 404 → `MANIFEST_INVALID`; 403/429 → `RATE_LIMITED`; I/O → `NETWORK`; an interceptor fails the test on any request to `api.github.com` | M11a |
| `ApkVerifierTest` | `:update:impl`, JVM with a fake `ApkInspector` | each row of the verification table; rotation: signer B with history containing installed A accepted (API 33+ view); signer A alone with manifest `certSha256` B and `previousCertSha256` [A] accepted (API 26–32 view of a v3.1 rotation release); installed B with archive signer A rejected; unrelated signer rejected; a manifest naming neither certificate rejected; the file is deleted on every failure | M11a |
| `ApkInspectorTest` | `:app`, instrumented | inspects the test target's own installed APK (`ApplicationInfo.sourceDir`): package, `longVersionCode` and certificate equal `getPackageInfo`'s; no fixture APK is committed | M11a |
| `SelfInstallerTest` | `:update:impl`, Robolectric (`ShadowPackageInstaller`) | session parameters per API level 26, 31, 34, 37 (`setRequireUserAction` from 31); commit choice of step 4: user-initiated → plain commit even on 34+; unattended on 34+ with Neutrodyne as installer of record → constraints commit; unattended with a browser as installer of record, or a `SecurityException` from the constraints call → plain commit only while no activity is started; on API 31–33 an unattended commit waits while the app is visible; `STATUS_FAILURE_TIMEOUT` → `ReadyToInstall`; no commit while the gate is busy; permission missing → `Blocked(INSTALL_PERMISSION_MISSING)`. Unverified: Robolectric support for `commitSessionAfterInstallConstraintsAreMet` (fallback: a thin session facade faked in the test) | M11a |
| `VerificationFailureMapperTest` | `:update:impl`, JVM | every row of the result table, including `ABORTED` with and without the verification extra (the PLAN M11 AC5 fallback), with and without `EXTRA_INTENT` (`systemExplanation`), and `ABORTED` without the extra after a shown confirmation (cancel → `ReadyToInstall`), without one, and twice in a row (→ `Blocked(UNKNOWN)`) | M11a |
| `InstallIdleGateTest` | `:update:impl`, JVM with `FakePlaybackStateSource`, `FakeDownloadProgressSource` | busy playback or download blocks; both false → idle; a play starting between gate and commit is caught by the re-check (PLAN M11 AC4) | M11a |
| `AppUpdaterTest` | `:update:impl`, Robolectric + WorkManager test driver + MockWebServer + `TestClock` | the state machine; modes and their work; Obtainium as installer of record → `Disabled(MANAGED_BY_OTHER_INSTALLER)`; debug → `Disabled(DEBUG_BUILD)`; skip; stable never sees pre-releases, beta finds the newest via `releases.atom`; no request to `api.github.com` (PLAN M11 AC6); `pending.json` survives a restart | M11a |
| `UpdateNoticesTest` | `:update:impl`, JVM + `TestClock` | `FIRST_RUN_CHOICE` once and never in debug or under Obtainium; `VERIFICATION_ENFORCEMENT` not before `NOTICE_FROM`, once at or after it, with the pre-enforcement variant before `GLOBAL_ENFORCEMENT` and the post-enforcement variant at or after it (a fresh install after the date sees it once); `WHATS_NEW` only after a self-update | M11a |

### Device checklist (M11a)

Recorded in the M11a release issue; the self-update cases use two consecutive release candidates signed with the release key:

- API 26 device: the update installs after the system confirmation; API 31 and API 34+ devices: it installs without a prompt where Android allows, unattended with `GENTLE_UPDATE` on 34+ once Neutrodyne is the installer of record; API 37 Pixel: likewise (PLAN M11 AC3). On API 34+, the **first** self-update after a browser install (installer of record: the browser) installs both through "Install now" in the foreground and unattended in Automatic mode once the app is in the background, without a `SecurityException` reaching the user. The APK picked is the one for `Build.SUPPORTED_ABIS[0]`; an `armeabi-v7a` install on a 64-bit phone moves to `arm64-v8a`.
- An update that becomes ready while audio plays, during a call and within 10 min of a pause waits and installs once idle (06's updater additions, PLAN M11 AC4); a running chunked download is never cut.
- `adb shell pm set-developer-verification-result ch.lkmc.neutrodyne <policy> <result>` (AOSP shell command, effective only where a verifier package is configured, so on an Android 17 Pixel; `clear-developer-verification-result` afterwards) makes the next self-update end in `STATUS_FAILURE_ABORTED` with `DEVELOPER_BLOCKED`, and the app shows the blocked sheet with "Download in browser" (PLAN M11 AC5; Unverified hook availability on a retail device; fallback: `VerificationFailureMapperTest`). Retest on an enforcing certified device once Google's global rollout starts (2027), with the advanced flow on "indefinitely" and on an expired "7 days".
- Obtainium installs the release with the per-ABI filter; Settings › Updates then shows "Managed by Obtainium" and nothing is scheduled (PLAN M11 AC6).
- The install-permission prompt round trip (`ACTION_MANAGE_UNKNOWN_APP_SOURCES` and back); airplane mode during a download resumes from the `.part` file; "Download in browser" opens the asset and Android's installer accepts it.
- No request to `api.github.com` in a network capture of the session ([v1.0 gate](#v10-gate) row 8).

---

## Reproducible builds

Serves N12 (independent verifiability) and [D79](../PLAN.md#3-key-decisions). Delivered in M0 (hygiene, nightly job). **Report-only, permanently:** no store rebuilds Neutrodyne any more, so nothing gates a release on reproducibility ([D79](../PLAN.md#3-key-decisions)); the nightly check stays because a build anyone can reproduce from a tag is a cheap trust signal next to the attestations.

### Hygiene

| Source of non-determinism | Mechanism | Owner |
|---|---|---|
| Timestamps, git data in `BuildConfig` | none ever ([01 Build variants and ABIs](01-foundation.md#build-variants-and-abis)) | 01 |
| Build-time secrets | PR, nightly and repro builds read **no** `-P` secret: `neutrodyne.acraMailto`, `neutrodyne.repoUrl` and `neutrodyne.engineManifestUrl` are committed in `gradle.properties`. After Podcast Index's written permission (PO-3 option A) `release.yml` injects its key into release builds only; a published APK then differs from an unsigned rebuild of its tag in `BuildConfig` as well as in the signature (stated in the README) | 01, 09 |
| Signing | release built unsigned when `NEUTRODYNE_KEYSTORE` is absent; the nightly job compares two unsigned builds, so it needs no tool to strip signatures | 09 |
| PNG crunching | `buildTypes.release { isCrunchPngs = false }` in `:app`; PNGs committed pre-optimised | 09 (01's build-type sketch delegates it here) |
| VCS info | `buildTypes.release { vcsInfo { include = false } }` in `:app` (a rebuild from a source archive has no `.git`; nothing depends on it) | 09 (delegated by 01) |
| AboutLibraries metadata | offline mode: no remote licence or funding fetches during the build (Unverified 15.x property names, e.g. `offlineMode = true`, `fetchRemoteLicense = false`) | 01 |
| R8 non-determinism around kotlinx.coroutines | if the job shows it: `-keep class kotlinx.coroutines.CoroutineExceptionHandler`, `-keep class kotlinx.coroutines.internal.MainDispatcherFactory` in `app/src/main/keepRules/app.keep` ([01 Build variants and ABIs](01-foundation.md#build-variants-and-abis)) | 01 |
| Python bytecode | Chaquopy compiles the shim to `.pyc` at build time with the container's Python (the standard library arrives precompiled from Maven Central; yt-dlp is compiled on the device, 04); CPython writes timestamp-based `.pyc` headers unless `SOURCE_DATE_EPOCH` is set, in which case it writes checked-hash `.pyc` ([py_compile](https://docs.python.org/3/library/py_compile.html)). The container sets `SOURCE_DATE_EPOCH` to the tagged commit's time (Unverified: that Chaquopy's compile step honours it; the job reports it) | 09 |
| Vendored engine and Python runtime | the yt-dlp asset is copied byte for byte from `youtube/ytdlp/engine/`; Chaquopy's runtime comes from Maven Central with verification metadata (01) | 01, 04 |
| Compiled ART profile (`assets/dexopt/baseline.prof`/`.profm`) | kept enabled; if the repro job diffs on it, disable the ArtProfile tasks for the `release` variant (`tasks.matching { it.name.contains("ArtProfile") && it.name.contains("Release") }.configureEach { enabled = false }`) and re-measure cold start without a profile | 09 |
| Toolchain | the [release container](#releaseyml): Debian 13 with CPython 3.14 (`python:3.14-slim-trixie@sha256:<digest>`) and Debian's OpenJDK 21; `build-tools;36.0.0` and `platforms;android-37` pinned by `install-android-sdk.sh`; Gradle 9.7.1 with `distributionSha256Sum` | 09 |
| Build cache, locale, time zone | `--no-build-cache`; `LC_ALL=C.UTF-8`, `TZ=UTC` in the container | 09 |
| `dependenciesInfo` signing block | disabled: it is an encrypted block only Google Play reads | 01 |

Unverified: whether the `platforms;android-37` revision can change under the same name and alter R8 output; the repro job records `source.properties` of the platform in its log.

### Nightly reproducibility job

1. Start two containers from the release container image (digest pinned, Renovate-managed) with different checkout paths (`/build/a` and `/home/builder/src/neutrodyne`), different CPU counts (`--cpus=2` and `--cpus=4`) and different umasks (022, 002).
2. In each: install `openjdk-21-jdk-headless git unzip curl`, run `install-android-sdk.sh`, then `./gradlew --no-daemon --no-build-cache assembleRelease` (unsigned; the three ABI APKs and the mapping).
3. Compare the SHA-256 of each pair (three APKs, the mapping); on a difference run `diffoscope` and upload its HTML report.
4. Report-only: a difference opens or updates one issue (label `repro`, through `report-nightly.sh`), never a `release-blocker`; a fix ships with the next regular release.

Anyone can repeat the comparison for a tag: build it with `scripts/ci/repro-build.sh assembleRelease` and compare everything except the APK Signing Block (and, after PO-3 option A, `BuildConfig`'s Podcast Index fields) with the published APKs.

---

## Developer verification

Serves R6.1, R6.4; mitigates risks P3, P8, P9, M4r. Delivered in M0 (README "Install and update" first draft with the certificate fingerprint), M11a (in-app help, pre-enforcement notice, updater detection, README final), M11b (review against Google's then-current rules). Honours [D80](../PLAN.md#3-key-decisions), [PO-5](../PLAN.md#po-5-google-developer-verification) (resolved 2026-10-05: **do not register**), [PO-36](../PLAN.md#48-further-product-owner-decisions), [D61](../PLAN.md#3-key-decisions).

Neutrodyne does not register with Google's Android developer verification: registering would tie a legal identity to an app that extracts YouTube streams ([D80](../PLAN.md#3-key-decisions)). This section owns the facts that the README's "Install and update" section, 08's help page and notice and the updater's blocked-update handling rely on. All facts checked 2026-10-05 against Google's pages ([overview](https://developer.android.com/developer-verification), [guides](https://developer.android.com/developer-verification/guides), [FAQ](https://developer.android.com/developer-verification/guides/faq), [Help Center](https://support.google.com/android/answer/17065026?hl=en), [advanced flow](https://support.google.com/android/answer/17588095?hl=en), [blog 2026-03-19](https://android-developers.googleblog.com/2026/03/android-developer-verification.html)).

### Phases and devices

| Phase | When | Effect on a Neutrodyne installed from GitHub |
|---|---|---|
| Tooling | March–August 2026 | none; the "advanced flow" for unverified apps launched gradually in August 2026 |
| First enforcement | since 2026-09-30 | none: it covers certified devices in Brazil, Indonesia, Singapore and Thailand and only installs from seven participating stores (Google Play, HONOR App Market, OPPO App Market, Galaxy Store, Palm Store, V-Appstore, GetApps); "if users sideload your app directly, these new verification requirements won't apply to your app yet" ([guides](https://developer.android.com/developer-verification/guides), [FAQ](https://developer.android.com/developer-verification/guides/faq)). Tester builds since M0 are unaffected everywhere |
| Global rollout | "2027"; no date published (Unverified whether it is staged by region or source) | on certified devices every **new install and every update** of an unregistered app is blocked unless the user turned on the advanced flow or installs over ADB |

Affected: certified Android devices with Google Play services (phones and tablets; Android 8 and up per the Help Center, "Android 7+" on the developer site — minSdk 26 is covered by both). Not affected: AOSP and non-certified devices — GrapheneOS, LineageOS without Google apps ([LineageOS statement](https://lineageos.org/Developer-Verification/)), /e/OS, Huawei and Fire OS devices — and regions where Google Mobile Services are unsupported ([Help Center](https://support.google.com/android/answer/17065026?hl=en)). The source of the APK does not matter: browser, Obtainium and the in-app updater are all gated the same way. YouTube-engine updates are files the app downloads, not installs, so they keep reaching users whose APK updates are blocked ([04 Engine updates](04-youtube.md#engine-updates)).

### Advanced flow

The one-time device setting Google provides for unverified apps ([Help Center](https://support.google.com/android/answer/17588095?hl=en), [blog](https://android-developers.googleblog.com/2026/03/android-developer-verification.html)):

1. Turn on Developer options (Settings › About phone › tap Build number 7 times).
2. Settings › System › Developer options › "Allow apps from unverified developers".
3. Confirm that nobody is guiding you through this (Google's anti-coercion check) and re-authenticate.
4. The phone restarts.
5. After a one-time 24-hour wait, confirm with fingerprint, face or PIN.
6. Choose **"7 days"** or **"indefinitely"**.
7. Each install or update of an unverified app then shows a warning with "Install anyway".

Consequences the guidance must state: with "7 days", or after switching the setting off, **updates of unregistered apps fail again** ([FAQ](https://developer.android.com/developer-verification/guides/faq)) — the "7 days" trap, which is why every text recommends "indefinitely"; Developer options can be switched off afterwards; the flow is delivered through Google's components and can change without an Android update (risk P8). Unverified: whether the setting is per Android user or profile, whether re-enabling after "7 days" repeats the wait, and how a silent self-update behaves with the flow on (risk T17; [In-app updater](#installing)).

| Situation (from Google's global rollout) | First install | Update (in-app updater, Obtainium or browser) |
|---|---|---|
| Certified device, advanced flow off or "7 days" expired | blocked | blocked: the installed version keeps running; the updater shows `Blocked(DEVELOPER_UNVERIFIED)` with the steps and "Download in browser" |
| Certified device, advanced flow "indefinitely" | warning, "Install anyway" | warning per update where Android shows one (Unverified for silent self-updates) |
| Non-certified device | as today | as today |
| Any device, `adb install -r` | allowed | allowed |

### Exempt and fallback paths

- **ADB:** installs over ADB are exempt by design ("no changes to how ADB works", [FAQ](https://developer.android.com/developer-verification/guides/faq)); in AOSP a session whose caller runs as shell or root is marked `INSTALL_FROM_ADB` and skips the verifier ([PackageInstallerSession](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/pm/PackageInstallerSession.java)). Documented as `adb install -r neutrodyne-{v}-{abi}.apk` for users with a computer — impractical for every update.
- **Shell-UID installers such as Shizuku** (e.g. Obtainium in Shizuku mode) inherit that exemption in the current AOSP code (Unverified on an enforcing device); Shizuku must be restarted after every reboot through Wireless debugging ([Shizuku](https://shizuku.rikka.app/guide/setup/)) and Google can close the path. Documented as a power-user fallback only, never built into Neutrodyne.
- **Non-certified systems** need nothing; the README says so. Root-based hooks are not documented.
- **Android Auto** is a separate gate for any non-store install: sideloaded media apps appear only after Android Auto's developer setting "Unknown sources" ([Android Authority](https://www.androidauthority.com/sideload-apps-on-android-auto-3681820/); PLAN M5 AC4).

### Package name and key

- Package names are allocated to registered keys: a key with more than 50 % of known installs has priority, any key with at least 50 installs may register, otherwise first come, first served ([package-name rules](https://developer.android.com/developer-verification/guides/android-developer-console)). Someone could register `ch.lkmc.neutrodyne` with another key before Neutrodyne has an install base (risk P9); what that does to installs of our differently signed APK is Unverified (most likely they remain "unverified" and need the advanced flow). Mitigation: public releases build the install cluster quickly; the key is kept, so the owner can still register.
- Registration stays possible later only with the same signing key; Google states a lost key makes it impossible ([FAQ](https://developer.android.com/developer-verification/guides/faq)) — one more reason for the [key custody](#key-ceremony-and-custody) rules (risk M4r). The free limited-distribution account (at most 20 explicitly authorised devices, [limited distribution](https://developer.android.com/developer-verification/guides/limited-distribution)) is not a public-release path.
- Development builds (`ch.lkmc.neutrodyne.debug`) are installed over ADB and are exempt.

### Watch and notice timing

- **Watch:** a recurring calendar issue every 2 weeks (label `verification-watch`) re-reads the overview, guides, FAQ and Help Center pages and Google's Android Developers Blog. A change (a global date, a different advanced flow, new exemptions) updates this section, the README section, 08's help strings and `VerificationTimeline` in the next tester or stable release, and is reported to the PO, who may reconsider registration (risk P8).
- **Notice timing ([PO-36](../PLAN.md#48-further-product-owner-decisions)):** the one-time in-app notice (08's `VerificationNoticeKey`, raised by [Notices](#notices)) appears from 2026-12-01, or from the date Google names for the global rollout if that is earlier; it is a date check on the device, so builds released before December also show it. Neutral tone: what the advanced flow costs (a restart and a 24-hour wait), why "indefinitely", that non-certified systems are unaffected; no countdown, no urgency, no blame. After the global date the same one-time notice switches to 08's post-enforcement hint: every installation that has not seen the notice — in practice each fresh install, made through the advanced flow — is told once that "7 days" stops updates after a week and how to switch to "indefinitely"; the help page leads with the Google Play card (08). By the roadmap's estimates (PLAN 7.1) M11a may reach users only around Google's 2027 rollout, so the pre-enforcement notice may reach only testers; the post-enforcement hint covers everyone else.
- **Support:** a pinned GitHub discussion or issue "Updates stopped working?" links the README section when enforcement starts.
- **Testing:** CI never sees enforcement — Gradle Managed Devices and emulators install over ADB, which is exempt. The blocked-update path is tested with AOSP's `adb shell pm set-developer-verification-result` / `clear-developer-verification-result` on an Android 17 Pixel (effective only where a verifier package is configured; Unverified on retail builds) and, as the fallback, `VerificationFailureMapperTest` ([Device checklist (M11a)](#device-checklist-m11a), PLAN M11 AC5); the advanced flow itself is checked by hand on an enforcing certified device once the global rollout starts.

### README "Install and update"

The README section's content is owned here (the README is maintained with the PLAN); 08's help page mirrors it and the [release checklist](#minor-and-stable-release-additions) compares both. Draft (M0, finalised in M11a; `{…}` filled in):

1. **Download only from GitHub:** `{repoUrl}/releases`. There is no Play Store version; any other copy is not Neutrodyne's. Pick `neutrodyne-{v}-arm64-v8a.apk` for most phones and tablets, `-x86_64.apk` for x86_64 devices, `-armeabi-v7a.apk` for older 32-bit phones (YouTube episodes then open in the YouTube app).
2. **Check it (optional):** compare the file with `SHA256SUMS`, or check the signing certificate with AppVerifier: `ch.lkmc.neutrodyne` / `{AA:BB:…}`. With the GitHub CLI: `gh release verify-asset {tag} {file} -R {owner}/Neutrodyne` and `gh attestation verify {file} -R {owner}/Neutrodyne`.
3. **Allow the install:** Android asks once whether your browser (or Files app) may install apps. To install its own updates, Neutrodyne asks for the same permission once.
4. **Phones with Google Play, from 2027:** Android will install apps only from developers registered with Google unless you turn on a one-time setting. Neutrodyne is not registered (why: below). Developer options › "Allow apps from unverified developers"; follow the steps (restart, 24-hour wait, fingerprint or PIN); choose **indefinitely** — with "7 days", updates stop working after a week; each install or update then shows a warning: tap "Install anyway". You can turn Developer options off afterwards. Nothing changes before Google's global start.
5. **Phones without Google certification** (GrapheneOS, LineageOS without Google apps, /e/OS) need none of this.
6. **Other ways (advanced):** `adb install -r {file}` from a computer; installer apps that work through Shizuku. Both may stop working if Google changes its rules.
7. **Updates:** Neutrodyne checks GitHub once a day and tells you about new versions (Settings › Updates: Off, Notify, Automatically); it never installs while you listen or download. Or use Obtainium: add the repository, set the APK filter to your file (e.g. `neutrodyne-.*-arm64-v8a\.apk$`), turn on "Include prereleases" only for test versions; while Obtainium installs Neutrodyne, the built-in updater stays off. YouTube engine updates arrive separately and need no install.
8. **Android Auto:** enable "Unknown sources" in Android Auto's developer settings.
9. **Changing phones:** make a manual backup first (Settings › Backup) and restore it on the new phone. Setting up a new phone does not reinstall Neutrodyne; Android may restore your library when you install it there, but that is not guaranteed (PLAN R1.8, [05 Auto Backup](05-groups-opml-backup.md#auto-backup)).
10. **Why Neutrodyne is not registered:** registering would tie a legal identity to the app; the maintainers decided not to. One factual paragraph, no campaigning ([PO-36](../PLAN.md#48-further-product-owner-decisions)).

Wording rules: Google built the anti-coercion check against scammers who coach victims through such steps, and this guidance is legitimately similar, so it stays factual — it says what the setting turns off, never urges haste, never addresses a user mid-install with a countdown.

---

## Privacy

Serves N3. Delivered in M0 (`PRIVACY.md` v0, `SECURITY.md`), updated whenever a document adds a network destination (M7 directories, M8/M9a YouTube, M9b engine updates, M11a app updates), final in M11b. Honours [D62](../PLAN.md#3-key-decisions), [D76](../PLAN.md#3-key-decisions), [D78](../PLAN.md#3-key-decisions), [PO-31](../PLAN.md#48-further-product-owner-decisions), [PO-32](../PLAN.md#48-further-product-owner-decisions).

### Commitments

No analytics, advertising, tracking, Firebase or Google Play services in any APK (Chromecast is not planned because it would need them, PO-6); no accounts; no Neutrodyne server; network traffic only to hosts the user chose or opted into — including GitHub for app updates and YouTube-engine updates, both disclosed and each with an Off switch (PO-31, PO-32); crash reports leave the device only through the user's own mail app after per-crash consent; private feed URLs and tokens never appear in logs, crash reports, diagnostics or directory queries.

### `PRIVACY.md` outline

1. Summary (the commitments above, in plain words).
2. What stays on the device: library, groups, history, positions, Up next, downloads, settings; credentials encrypted with an Android Keystore key.
3. Where the app connects ([inventory](#network-inventory)) and what those hosts receive.
4. Backups: Android Auto Backup carries a daily library snapshot (feed URLs included, possibly with private tokens) to the user's Google account, only on devices with backup encryption (PO-15); manual backup files contain passwords only on opt-in (R1.9).
5. Crash reports and diagnostics: contents, consent, how to request deletion of an emailed report.
6. Permissions and why (from 01's table).
7. What the YouTube engine and the updaters contact and how to turn them off (Settings › YouTube "Play YouTube in the app" and "Engine updates"; Settings › Updates; external mode on the `armeabi-v7a` APK).
8. Contact and change history (git log of `PRIVACY.md`).

### Network inventory

IDs are stable; the in-app "What Neutrodyne connects to" list (Settings › Privacy) uses the same IDs and `PrivacyInventoryParityTest` (JVM, `:feature:settings`) fails when the IDs in `PRIVACY.md` and the in-app list differ.

| ID | Destination | Purpose | When | APK | Default |
|---|---|---|---|---|---|
| `feeds` | each subscribed feed's host and its redirect targets | fetch RSS/Atom ([03](03-feeds-and-discovery.md#fetch-pipeline)) | refresh (periodic, on open, pull), subscribe preview, import | all | user's subscriptions |
| `add-input` | the web page the user typed, pasted or shared into Add podcast, up to 5 well-known feed paths on that site (`/feed`, `/rss`, …) and the candidate the user picks | find the feed behind a web page ([03 Fetch, sniff and autodiscovery](03-feeds-and-discovery.md#fetch-sniff-and-autodiscovery)) | adding a podcast by URL or share | all | user action |
| `media` | enclosure hosts and the publishers' measurement redirects in front of them | stream and download audio ([06](06-playback.md#media-items-and-uri-resolution), [07](07-downloads.md#transfer-core)) | play, download, auto-download | all | user action / opt-in |
| `artwork` | artwork hosts referenced by feeds and by directory results (Apple's image CDN, fyyd's image host, Podcast Index `artwork` URLs) | covers, episode images and search-result covers ([08](08-ui-ux.md#artwork-pipeline)) | subscribe, artwork change, display, Discover results | all | on |
| `chapters` | hosts of Podcasting 2.0 chapter files | chapters ([06](06-playback.md#chapters)) | playing an episode that has a chapters URL | all | on |
| `notes-images` | image hosts inside show notes | show-notes images ([03](03-feeds-and-discovery.md#show-notes)) | per 03's `feeds.show_notes_images` setting | all | per 03 |
| `apple` | `itunes.apple.com`, `rss.marketingtools.apple.com` | search, lookup of Apple Podcasts / pod.link / Overcast links, charts ([03](03-feeds-and-discovery.md#search-and-discovery)) | Discover; adding such a link; the YouTube subscribe preview's "also has a podcast feed" check (channel title as query, `youtube.suggest_rss`, [04](04-youtube.md#prefer-the-shows-rss-feed)) | all | on |
| `fyyd` | `api.fyyd.de` | search | Discover; the same YouTube preview check | all | on |
| `podcastindex` | `api.podcastindex.org` | search, trending | with a user key, or with a release-build key injected after Podcast Index's written permission ([PO-3](../PLAN.md#po-3-podcast-index-api-key-handling) option A); then also the YouTube preview check | all | off while no key exists (PO-3 default B); on once a release-build key exists; Settings › Discover switch (`discover.podcastindex_enabled`, 03) |
| `youtube-subscriptions` | `www.youtube.com` (feeds, channel page head, oEmbed), `i.ytimg.com`, `yt3.googleusercontent.com`, `yt3.ggpht.com` | YouTube channels as podcasts, layer A ([04](04-youtube.md#channel-resolution)) | only when the user adds or has a YouTube channel | all | user action |
| `youtube-streams` | `www.youtube.com` (watch page, `/youtubei/…` InnerTube requests made by yt-dlp through our OkHttp client in `:ytx`), `*.googlevideo.com` (media, from the main process) (Unverified complete host list; the M9a network capture records it) | audio streams, downloads, durations and flags, channel lookup, back catalogue, channel search — layer B ([04 YouTube engine](04-youtube.md#youtube-engine)) | playing, downloading or refreshing YouTube items; channel search | with engine | user action; Settings › YouTube "Play YouTube in the app" turns it off |
| `youtube-engine` | `<owner>.github.io` (approved engine manifest), `github.com` (`yt-dlp/yt-dlp` release files), `release-assets.githubusercontent.com` | YouTube-engine updates without an app update ([04 Engine updates](04-youtube.md#engine-updates)) | daily, after a circuit-breaker opening (at most every 3 h), "Check for engine update"; never with policy Off | with engine | on (policy Neutrodyne-approved, PO-32) |
| `app-updates` | `github.com` (`<owner>/Neutrodyne/releases/latest/download/…`, `releases.atom` for the beta channel, release assets), `release-assets.githubusercontent.com` | app update checks and downloads ([In-app updater](#in-app-updater)) | daily, "Check now", downloads; "Download in browser" hands the URL to the browser instead | all | Notify (PO-31; first-run card with one-tap Off); off when Obtainium is the installer of record and in debug builds |
| `links` | any link the user taps (episode page, funding, person) | opened in the browser | tap | all | user action |
| `issue-tracker` | `github.com` | "Report a problem" opens the browser | tap | all | user action |
| — | Neutrodyne servers, analytics, ads, Google Play services, `api.github.com` | — | never | — | — |

What hosts receive: every request carries the device's IP address and `User-Agent: Neutrodyne/<versionName> (Android <release>; +<repo URL>)` (no device or install identifiers, [01 Interceptors](01-foundation.md#interceptors)); feed requests carry stored `If-None-Match`/`If-Modified-Since`; private feeds and their same-origin enclosures carry Basic credentials (never across origins); directories receive only the query text and country ([03 Privacy](03-feeds-and-discovery.md#privacy)); YouTube receives the consent cookie `SOCS=CAE=` and, with the engine, what yt-dlp's InnerTube clients send (a client-specific User-Agent such as Safari for `visionos`, the video or channel ID, the app locale as `hl`/`gl`; cookies live only in `:ytx` memory, [04 Networking bridge](04-youtube.md#networking-bridge)); GitHub (app and engine update checks and downloads) sees the IP address and the User-Agent, no identifier. Publishers' measurement redirects can count downloads by IP and user agent; the app adds nothing to help or hinder that. Outside the app's control and disclosed: Android's own Auto Backup transfer, system DNS (Private DNS honoured), and the user's mail app for crash reports.

### Redaction surfaces

01's `Redactor` ([01 Logging and redaction](01-foundation.md#logging-and-redaction)) is the only redaction algorithm; this table lists where it must be applied and how it is tested.

| Surface | Rule | Test |
|---|---|---|
| Logs (Logcat, `RingBufferLogSink`) | every message through `Redactor.text` (01) | 01's `Redactor` tests |
| Crash reports | `STACK_TRACE` and `CUSTOM_DATA` through `Redactor.text`; field allow-list; no `LOGCAT`, `BUILD_CONFIG`, `SHARED_PREFERENCES`, device IDs | `CrashReportRedactorTest` |
| Diagnostics text, issue pre-fill | built only from redacted values | `RedactionCoverageTest` |
| Exported database copy | 02's scrub procedure ([Copy, report and export](#copy-report-and-export)) | 02's `DiagExportScrubTest`, `DatabaseCopyExporterTest` |
| OPML export, backup ZIP | passwords only on opt-in; private-URL warning (05) | 05's tests |

`RedactionCoverageTest` (`:core:data`, Robolectric, M11) seeds a podcast with feed URL `https://alice:s3cret@feeds.example.invalid/rss/a8F3kq09ZpLm2xQr7Tz4?token=SECRETTOKEN` (the 20-character path token is longer than the 14 characters 01's `Redactor.url` rule 4 keeps) and an episode **without a GUID** whose enclosure is `https://cdn.example.invalid/ep1.mp3?auth=SECRET2` (so its identity key is a `u:` key carrying the URL, [02 Episode identityKey](02-data-model.md#episode-identitykey)), provokes a refresh failure and a crash-report collection, then asserts that none of `s3cret`, `SECRETTOKEN`, `SECRET2`, `a8F3kq09ZpLm2xQr7Tz4` occurs in: the diagnostics report text, the issue pre-fill URL, the ring-buffer log, the `CrashReportData` after `CrashReportRedactor`, and the exported database copy (read back byte-wise, so text left in free pages also fails the test).

### Security reporting

`SECURITY.md`: report vulnerabilities through GitHub private vulnerability reporting; acknowledgement within 7 days; fixes ship as PATCH releases through the normal pipeline; scope includes parsing of feeds, OPML, backups and import files (N9), the exported `ArtworkProvider`, intent handling, credential storage, the in-app updater's verification ([Download and verification](#download-and-verification)) and the engine-update trust chain ([04 Trust chain](04-youtube.md#trust-chain)). A suspected signing-key or engine-manifest-key compromise follows [Key loss or compromise](#key-loss-or-compromise) and [engine-canary.yml](#engine-canaryyml).

---

## Crash reporting and diagnostics

Serves N3, N2 (diagnosability). Delivered in M0 (ACRA wiring, disabled until PO-10 names a mailbox), M9a (engine health lines), M11a (updater lines), M11b (final configuration, diagnostics screen). Honours [D62](../PLAN.md#3-key-decisions), [PO-10](../PLAN.md#48-further-product-owner-decisions) default.

### ACRA configuration

ACRA 5.14.2 with `acra-mail` and `acra-dialog`: zero network traffic from the reporter. `installAcra` is called from `NeutrodyneApplication.attachBaseContext` when `BuildConfig.ACRA_MAILTO` is not empty and the process is not `:ytx`; the `:acra` process returns early from `onCreate` ([01 Application start-up](01-foundation.md#application-start-up)). ACRA is never installed in `:ytx` ([D62](../PLAN.md#3-key-decisions)): an engine crash or hang is recorded by the main process as engine health, never offered to the user as a crash report (PLAN M9 AC5). `ACRA_MAILTO` comes from the committed `neutrodyne.acraMailto` ([Hygiene](#hygiene)) and is forced to `""` on the `debug` build type with `buildConfigField("String", "ACRA_MAILTO", "\"\"")` in `buildTypes.debug` (a build-type field overrides `defaultConfig`'s; debug builds crash fast with StrictMode and LeakCanary instead).

```kotlin
// :app
fun installAcra(app: Application) = app.initAcra {
    buildConfigClass = BuildConfig::class.java
    sharedPreferencesName = "acra"                          // ACRA's own enable flag lives here (see Settings)
    reportFormat = StringFormat.KEY_VALUE_LIST
    reportContent = listOf(                                   // explicit allow-list
        ReportField.REPORT_ID, ReportField.APP_VERSION_NAME, ReportField.APP_VERSION_CODE,
        ReportField.ANDROID_VERSION, ReportField.BRAND, ReportField.PHONE_MODEL,
        ReportField.STACK_TRACE, ReportField.CUSTOM_DATA, ReportField.USER_COMMENT,
        ReportField.USER_CRASH_DATE, ReportField.IS_SILENT,
    )                                                         // never LOGCAT, BUILD_CONFIG (may hold a Podcast Index key, PO-3), SHARED_PREFERENCES, DEVICE_ID
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

Dialog text (en): "Neutrodyne stopped. You can send a crash report by email: your mail app opens with the report attached, and nothing is sent until you press Send there. The report contains the error, the app version, the Android version and the phone model — never your subscription list or listening history; passwords and access tokens are removed from any web address in an error message." (Accurate because `Redactor.url` keeps scheme, host, port and short path segments and masks user-info, token-like path segments and every query value.)

- **`CrashReportRedactor`** (`:app`): an ACRA `ReportingAdministrator` loaded through `META-INF/services/org.acra.config.ReportingAdministrator`; in `shouldSendReport` it replaces `STACK_TRACE` and each `CUSTOM_DATA` value with `Redactor.text(…)` and drops custom keys outside the allow-list, then returns `true` (whether reports are offered at all is ACRA's own enabled flag, mirrored from `privacy.crash_reports`, see [Settings](#settings)). In ACRA 5.14.2 `ReportExecutor` calls every administrator's `shouldSendReport` before `saveCrashReportFile`, so the stored file — what the dialog and the mail sender use — is the redacted one (read from the 5.14.2 bytecode, 2026-10-05; `CrashReportRedactorTest` pins it: a collected report with a token URL in `STACK_TRACE` is saved without it).
- **Mail app visibility:** 01's merged manifest carries `<queries>` for `SENDTO mailto:` (Unverified need on API 30+); without any mail app ACRA cannot hand off, so the diagnostics screen's "Copy diagnostics" is the fallback.
- **Engine crashes:** a native crash or `os._exit` in `:ytx` kills only that process; `YtDlpClient` ends the calls in flight with `Transient(ENGINE_UNAVAILABLE)` and counts failed starts ([04 Process and lifecycle](04-youtube.md#process-and-lifecycle)). The count, the last engine error code and the engine version appear in diagnostics (`YOUTUBE`) and in `CrashKey.YOUTUBE_HEALTH` of any later main-process report; no ACRA dialog appears for them.

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
| `YOUTUBE_HEALTH` | 04 `YouTubeHealth`, `YouTubeEngine.status` | breaker state, rate-limit level; with the engine: availability or `ExternalReason`, active yt-dlp version and source (bundled/updated), failed starts |
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
interface DiagnosticsContributor {                    // @IntoSet from :core:data, :download:impl, :playback:impl, :youtube:ytdlp, :update:impl, :app
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
| `APP` | version name/code, build type, Android release and SDK, manufacturer and model, the APK's ABI (`BuildInfo.apkAbi`) and the device's `SUPPORTED_ABIS`, app locales, SQLite version (`sqlite_version()`), Media3 version, installer of record (`getInstallSourceInfo`), first 8 hex digits of the signing certificate SHA-256; updater mode and channel, last check time and outcome, current `UpdateState` (from `:update:impl`) | `:app`, 09 `:update:impl` | installer: 30+ |
| `REFRESH` | `feeds.last_run_finished_at`, `feeds.last_run_summary`, `feeds.last_run_stop_reason` | 03 | — |
| `BACKGROUND` | standby bucket (`UsageStatsManager.getAppStandbyBucket`), background restricted (`ActivityManager.isBackgroundRestricted`), battery optimisation (`PowerManager.isIgnoringBatteryOptimizations`), Data Saver (`ConnectivityManager.getRestrictBackgroundStatus`), network status (01 `NetworkMonitor`), last 10 process exits with reason (`getHistoricalProcessExitReasons`) | `:app` | bucket and restriction 28+; exits 30+ |
| `JOBS` | for each canonical unique work name: state, run attempt count, stop reason, next schedule time | `:core:data` (WorkManager) | — (Unverified WorkManager accessor names) |
| `DOWNLOADS` | 07's `DownloadDiagnostics` (stop ring, pending job reasons, roots and free space) | 07 | pending reasons 36+ |
| `YOUTUBE` | feed outage (every APK); with the engine: breaker, rate limit, `EngineStatus` (availability or `ExternalReason`, active and bundled yt-dlp versions, source, update policy, last engine-update check and outcome, JS challenges), failed starts, `:ytx` running or stopped | 04 | — |
| `DATABASE` | file size, row counts, last `db-maintenance` step durations, `diagnostics.db_quick_check_failed_at`, last recovery cause | 02 | — |
| `NOTIFICATIONS` | notifications enabled, per-channel importance (blocked channels flagged) | `:app` | channels 26+ |
| `PARSE_WARNINGS` | per-feed parse warnings of the last ingest (in-memory LRU of 50) | 03 | — |
| `LOG` | last 500 redacted log lines | 01 `RingBufferLogSink` | — |

Threading and failures: `snapshot()` runs all contributors concurrently on `@Dispatcher(IO)` with a 2 s timeout each; a timeout or exception becomes one line `"<section> unavailable (<ExceptionClass>)"` with `DiagnosticsSeverity.WARNING`; the screen never fails as a whole. `DiagnosticsRepositoryImpl` passes every line value through `Redactor.text` once more before returning (contributors should already have redacted; parse-warning details and stop-reason texts can quote URLs), and never emits feed URLs, credentials or device identifiers.

### Copy, report and export

- **Copy diagnostics:** `toPlainText` (sections in enum order, log last, truncated from the log's oldest lines to 64 KB) to the clipboard.
- **Report a problem:** copies the full `toPlainText(report)` to the clipboard, then opens `{repoUrl}/issues/new?template=bug.yml&diagnostics=<url-encoded summary>` in the browser; issue-form fields are pre-filled by their `id` ([creating an issue from a URL query](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/creating-an-issue#creating-an-issue-from-a-url-query)). The summary is `toPlainText` without the `LOG` and `PARSE_WARNINGS` sections, cut from the end until the **encoded** URL is ≤ 7,000 characters (GitHub answers `414 URI Too Long` beyond its unpublished limit; percent-encoding inflates multi-line text two- to threefold, so a raw byte cap is not enough), followed by the line "(truncated — the full diagnostics are on your clipboard; paste them below)" when cut. `bug.yml`'s `diagnostics` textarea says the same.
- **Export database copy:** `DatabaseCopyExporter` (`:core:data`) runs 02's diagnostics export procedure — `VACUUM INTO`, scrub of credentials and of every `TEXT` column outside 02's `DiagExportScrub.KEEP` allow-list on a raw driver connection, then `VACUUM` so deleted bytes leave the file ([02 db-maintenance worker](02-data-model.md#db-maintenance-worker); the SQL is 02's and is not repeated here) — with the target `cacheDir/export/neutrodyne-diagnostics-<yyyy-MM-dd-HHmm>.db`, because only `cache/export/` is shared by the FileProvider (02 uses the same target). Flow owned here: a confirmation first states that subscriptions, titles and listening history are included and that addresses, show notes and descriptions are masked; the export fails with `NOT_ENOUGH_SPACE` when free space < 2 × database size + 50 MB and with `DATABASE_BUSY` when the writer is held longer than 30 s; the file is shared through the FileProvider and deleted after 1 h or at the next app start. A database quarantined by 02's recovery is never exported: it may be unreadable, so it cannot be scrubbed; diagnostics show only its `RecoveryCause` and quarantine date.

The diagnostics screen links to `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (fallback `ACTION_APPLICATION_DETAILS_SETTINGS`) and never requests an exemption (N2): Android documents the direct request for apps whose core function would be adversely affected, which a podcast player's is not ([Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)); 01's `checkBannedApis` blocks `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

"Detailed log for 24 hours" (`diagnostics.verbose_log_until`) lowers the ring buffer's level to `DEBUG` (still redacted) so a user can reproduce a problem and copy the log.

---

## Localisation

Serves N10. Delivered in M0 (conventions, Lint gates, per-app language plumbing), M10 (pseudo-locale screenshots), M11 (Weblate project per PLAN M11, launch languages). Honours [PO-14](../PLAN.md#48-further-product-owner-decisions) default.

### Workflow

- **Hosted Weblate, Libre plan** (free for public libre projects), project `neutrodyne`, created at the start of M11 at the latest (PLAN M11 deliverable); opening it at the end of M10, once M10's string changes have landed, is preferred so translators have the whole beta period to reach PO-14's 90 % threshold.
- **Components** via Weblate's component-discovery add-on: one component per module with strings, file mask `{module path}/src/main/res/values-*/strings.xml`, monolingual base `…/values/strings.xml` (Android string resource format). Strings stay in the module that owns them (features cannot share resources across modules). Release notes (`changelogs/`), the README and the release body are English only and not Weblate components.
- **Flow:** Weblate commits to its own branch and opens a PR (squash add-on); CI runs the normal checks (Lint fatal format and plural checks catch broken translations); maintainers merge at least weekly. Developers never edit `values-xx` by hand except to revert a broken string. Before a PR that renames or deletes many string keys, lock the Weblate component and merge Weblate's pending PR first.
- Weblate maps language codes to Android qualifiers (`pt_BR` → `values-pt-rBR`, `zh_Hant` → `values-b+zh+Hant`).

### String conventions

- Every user-visible text is a resource; ViewModels carry `UiText` (01). Counts use `<plurals>` and `pluralStringResource`; never concatenate fragments; placeholders are positional (`%1$s`) with an XML comment above the string explaining each one; non-translatable parts use `<xliff:g id="…" example="…">`; brand and technical tokens use `translatable="false"`.
- Numbers, dates and durations are formatted with the app locale (`AppCompatDelegate.getApplicationLocales()[0]`, else `Locale.getDefault()`): `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(…)`, `android.icu.text.RelativeDateTimeFormatter` for relative times, `NumberFormat` for numbers; wire formats always use `Locale.ROOT` (01).
- Icons with direction use auto-mirroring; layouts use start/end, never left/right.
- External-mode and engine texts are ordinary resources of the module that shows them; there are no per-build string sets ([08 Capability differences in UI](08-ui-ux.md#capability-differences-in-ui)). Commands, hashes, file names and package IDs in the install help are `translatable="false"` (08).
- Hard-coded Compose text is caught by review and by `en-XA` screenshots (unlocalised text stays plain ASCII there); 09 asks 01 to add the `Text("…")` literal pattern in `feature/**/src/main` to `checkBannedApis`.

### Shipped locales and per-app language

- `app/policy/locales.txt` lists the shipped locales (`en-US` plus every language ≥ 90 % translated across all string components when the minor release is prepared; PO-14). `scripts/l10n/update-shipped-locales.sh` reads Weblate's per-language statistics API and rewrites the file; it runs during the [minor-release checklist](#minor-and-stable-release-additions). Unverified: the statistics endpoint and field names.
- `:app` reads the file into `androidResources.localeFilters` (plus `en-rXA` and `ar-rXB`, so debug builds keep their pseudo-locales; release builds generate none because `isPseudoLocalesEnabled` is off there, and `check-apk.sh` asserts with `aapt2 dump xmltree` that the release APK's generated locale config lists neither) and generates `BuildInfo.shippedLocales` (request to 01) for 08's in-app picker (Appearance › Language), which calls `AppCompatDelegate.setApplicationLocales(…)`. `generateLocaleConfig = true` with `res/resources.properties` (`unqualifiedResLocale=en-US`) lists the same locales for Android 13+'s system app-language settings ([per-app languages](https://developer.android.com/guide/topics/resources/app-languages)). Unverified: that `generateLocaleConfig` honours `localeFilters` and ignores library translations (M0 check; fallback: generate `res/xml/locales_config.xml` from `locales.txt` and turn `generateLocaleConfig` off).
- Partially translated languages stay in the repository but are filtered out of the APK until they reach the threshold; missing strings in shipped languages fall back to English.

### Pseudo-locales and RTL

`isPseudoLocalesEnabled = true` on `debug`; Roborazzi captures `en-XA` (long accented text) and `ar-XB` (RTL) per [Compose UI and screenshot tests](#compose-ui-and-screenshot-tests); `android:supportsRtl="true"` (01); a manual Arabic pass and a 200 % font pass on device per release (08's manual checks).

---

## Performance budgets

Serves N5, R2.9; mitigates risks T6, T15. Delivered in M0 (per-ABI size checks), M2 (query timing), M9a (engine budgets, measured by the first-week spike), M10 (grid jank), M11b (startup, profiles, all budgets re-measured). Journeys are defined by [08 Performance journeys](08-ui-ux.md#performance-journeys); seeded data by 02's `SeedDatabase` (300 podcasts, 50,000 episodes, 20 groups).

### Reference devices

| Role | Device | Use |
|---|---|---|
| Reference (N5 "PO-agreed mid-range phone") | default until the PO names one: Google Pixel 7a on Android 16 or later | every budget below; Macrobenchmark runs before each minor release |
| Floor | any 2–3 GB RAM device on API 26–28 | functional smoke and scroll feel only, no numeric budgets; `:ytx` start on a low-memory device (informational) |
| 32-bit | any `armeabi-v7a`-only device | the `armeabi-v7a` APK: external mode with its reason (PLAN M9 AC8), functional only |
| CI emulators | GMD `api36`, `bench34` | trends and dry runs only; never gate on emulator timings (shared VMs are noisy) |

### Budgets

| ID | Metric | Budget | Measured by | Gate |
|---|---|---|---|---|
| PB1 | Cold start to Feeds, time to initial display, p50 | < 600 ms (N5) | `ColdStartToFeeds`: `StartupTimingMetric`, `StartupMode.COLD`, 15 iterations, `CompilationMode.Partial(BaselineProfileMode.Require)` | release (PLAN M11 AC1) |
| PB2 | Same, p90 | < 900 ms | same | soft (investigate) |
| PB3 | Cover-grid fling jank | < 1 % of frames late, measured as `frameOverrunMs` P99 ≤ 0 ms (Macrobenchmark reports percentiles, not shares; P99 ≤ 0 means at most 1 % of frames overran) | `CoverGridFling`: `FrameTimingMetric`, 5 iterations × 3 flings over 300 tiles | M10 AC7, release |
| PB4 | All-feed fling and group-pager swipe jank | `frameOverrunMs` P99 ≤ 0 ms | `AllFeedFling`, `GroupPagerSwipe` | release |
| PB5 | Player expand/collapse jank | `frameOverrunMs` P99 ≤ 0 ms | `PlayerExpandCollapse` | soft |
| PB6 | Group feed first page (count + 80 rows) | ≤ 60 ms | 02's `FeedQueryTimingTest`, median of 20 | M2 AC2 on the reference device; CI records on GMD |
| PB7 | All feed first page | ≤ 100 ms | same | same |
| PB8 | Subsequent page load | ≤ 20 ms | same | same |
| PB9 | Podcast screen open to content | < 300 ms | manual trace on the reference device (M1 AC5); from M11 `PodcastOpen` journey with a trace section | M1 manual, release soft |
| PB10 | 300-feed OPML → 300 pending tiles | ≤ 2 s (R1.3) | 05's import test on device | M3 |
| PB11 | Parse the 831-item 3.5 MB feed | < 1 s on the JVM | 03's corpus test | every PR |
| PB12 | `arm64-v8a` and `x86_64` release APKs, each (with the YouTube engine) | < 40 MB (N5) | `check-apk.sh` | every PR, release |
| PB13 | `armeabi-v7a` release APK (no engine runtime; counts any unusable Python assets ABI splits leave in it, ≈ 12–13 MB Unverified, [01 S7](01-foundation.md#s7-chaquopy-under-agp-941)) | < 30 MB (N5) | `check-apk.sh` | every PR, release |
| PB14 | Database at the N5 scale | ≤ 100 MB | 02's size measurement | M11 |
| PB15 | Main-process PSS peak during `CoverGridFling` + `PlayerExpandCollapse` (`:ytx` excluded, PB20) | ≤ 250 MB (starting value, Unverified) | `MemoryUsageMetric(Mode.Max)` (experimental Macrobenchmark metric; fallback `dumpsys meminfo ch.lkmc.neutrodyne` in `teardownBlock`) | soft |
| PB16 | 300-feed refresh with all feeds answering 304 | ≤ 3 min on Wi-Fi | 03's M11 performance check | soft |
| PB17 | Splash hold | ≤ 400 ms | 01's start-up rule | M0 |
| PB18 | Cold YouTube resolve (`:ytx` not running), p50 over 20 videos | ≤ 3 s (N5) | [04 Spike results](04-youtube.md#spike-results) procedure on the reference device: time from the `YtDlpClient` call to its result, `:ytx` killed before each video | M9 AC4 (M9a spike); soft at release |
| PB19 | Warm YouTube resolve (`:ytx` running), p50 | ≤ 1.5 s (N5) | same, `:ytx` kept alive | M9 AC4; soft at release |
| PB20 | `:ytx` PSS while alive, idle and peak during a resolve | ≤ 90 MB (N5) | `dumpsys meminfo ch.lkmc.neutrodyne:ytx` during the spike's runs | M9 AC4; soft at release |
| PB21 | `:ytx` gone after the last call | ≤ 3 min (N5) | `YtDlpClientTest` with `TestClock` (every PR); on the device `adb shell pidof ch.lkmc.neutrodyne:ytx` returns nothing 3 min + 10 s after the last call | every PR (logic), M9 AC4 (device) |

Budget changes are PO decisions (N5) and are recorded in this table. The engine budgets PB12 and PB18–PB20 start as estimates (≈ 15–22 MB of engine per 64-bit APK, ≈ 70 MB in `:ytx`, 1–3 s first resolve, [04 Host and packaging](04-youtube.md#host-and-packaging)); a miss in the M9a spike leads to 04's fallbacks or a PO amendment. PB1 must stay unchanged with the engine: Python never starts in the main process ([D73](../PLAN.md#3-key-decisions)).

### Macrobenchmark and profiles

- `:benchmark` (from M10 for the journeys; module exists since M6) uses `benchmark-macro-junit4` 1.5.0 and the `androidx.baselineprofile` plugin with `targetProjectPath = ":app"`, `useConnectedDevices = false`, managed device `bench34` (`aosp`, API 34; profile generation needs an `aosp` image at API 33+ or root).
- **Seeding:** the plugin-created `benchmarkRelease` and `nonMinifiedRelease` build types get an extra source directory `app/src/benchmarkShared/` (added to both via `sourceSets`) containing `BenchmarkSeedReceiver`, a receiver without intent filters, `exported="true"` and protected by `android:permission="android.permission.DUMP"` (held by the shell, not by apps), which fills the database with `SeedDatabase` (`benchmarkReleaseImplementation(testFixtures(project(":core:database")))`). The benchmark's `setupBlock` sends `am broadcast -n ch.lkmc.neutrodyne/.benchmark.BenchmarkSeedReceiver` once and waits for its marker file. The `release` variant never contains it. Unverified: variant-specific source sets and dependencies for plugin-created build types (M10 check).
- **Baseline and startup profiles:** generated from the same journeys (`ColdStartToFeeds` with `includeInStartupProfile = true`), `mergeIntoMain = true`, committed as text under `app/src/main/generated/baselineProfiles/`; `automaticGenerationDuringBuild = false` so PR, release and repro builds never need an emulator. Regenerated with `baseline-profile.yml` before each minor release.
- **Full display:** 08's Feeds route calls `ReportDrawnWhen { first page loaded }` ([08 Performance journeys](08-ui-ux.md#performance-journeys)), so `StartupTimingMetric` also reports time to full display; not budgeted in v1.
- Results (`*-benchmarkData.json`) from the reference device are attached to the release checklist issue; a regression > 10 % against the previous minor release on PB1–PB4 blocks the release until explained.
- R8: full mode via `optimization { enable = true }` and `-dontobfuscate` (01). Size tips applied when PB12 or PB13 is at risk: legacy native packaging (compressed `.so` files, ≈ 6 MB less per 64-bit APK, decided by S7, [01 Build variants and ABIs](01-foundation.md#build-variants-and-abis)); Chaquopy's foreign-ABI assets left in each split (S7 measures them; > 5 MB per APK triggers [D2](../PLAN.md#3-key-decisions)'s ABI-flavor fallback) and its ABI-independent Python assets in the `armeabi-v7a` APK (01 open question 13); exclude `/DebugProbesKt.bin`; review keeps with the R8 configuration analyzer.

---

## Release checklist

Serves N1–N12. Copied into `.github/ISSUE_TEMPLATE/release.md`; one issue per release.

### Every release

Before tagging:

- [ ] `scripts/release.sh … --dry-run` prints the expected `versionName`/`versionCode`.
- [ ] `main` green: `ci.yml` and the last `nightly.yml`, including `no-engine-build` from M9a (no open `release-blocker` issue).
- [ ] `changelogs/<versionCode>.txt` for the new `versionCode` (name it from `release.sh … --dry-run`) merged to `main` through a PR.
- [ ] Weblate PR merged (or explicitly deferred).
- [ ] Any schema change since the last tag has its migration test; the frozen-schema check passes.

Tag and publish:

- [ ] `scripts/release.sh …`; approve the `release` environment; `release.yml` green in < 30 min.
- [ ] The GitHub release is immutable and shows the three ABI APKs, `neutrodyne-{v}-mapping.txt`, `neutrodyne-update.json` and `SHA256SUMS`; pre-release flag and "latest" correct; `release.yml` step 9 passed, and one APK spot-checked locally with `sha256sum --check`, `gh release verify-asset` and `gh attestation verify`.
- [ ] A test device updates through the in-app updater (from M11a: Settings › Updates › Check now) and another through Obtainium with the per-ABI filter; the installed app's certificate matches `NEUTRODYNE_CERT_SHA256`.
- [ ] The last nightly `repro` result is noted (report-only).

### Minor and stable release additions

- [ ] `baseline-profile.yml` run and profiles committed.
- [ ] Macrobenchmarks PB1–PB5 on the reference device; results attached; no unexplained regression > 10 %; per-ABI sizes (PB12, PB13) from `check-apk.sh` attached.
- [ ] `update-shipped-locales.sh` run; `locales.txt` committed.
- [ ] Manual device matrix of [06](06-playback.md#testing) and checklist of [07](07-downloads.md#instrumented-and-device-tests) re-run on the reference device (`arm64-v8a` APK); external mode checked once on the `armeabi-v7a` APK.
- [ ] 08's manual checks: TalkBack, Switch Access, 200 % font, Arabic RTL, keyboard-only, foldable postures, grid → podcast transition review, airplane mode with downloads.
- [ ] `bmgr` check on a device (05/07).
- [ ] `PRIVACY.md` matches the network inventory (parity test green) and any new destination; when the release adds a destination or changes networking code, `network-capture.sh` re-run and its host list attached.
- [ ] The README's "Install and update" section and 08's Install & updates help page agree with [Developer verification](#developer-verification) (the latest `verification-watch` issue is closed); the release body template is current.
- [ ] The engine canary is green and the bundled yt-dlp is the latest approved version, or the difference is explained (04).

### Hotfix (YouTube fast lane)

Engine path first ([04 Hotfix runbook](04-youtube.md#hotfix-runbook)): when the fix is in a yt-dlp **stable** release and the shim needs no change, no APK ships — [engine-canary.yml](#engine-canaryyml) approves the release within 6 h (path 1), or, after re-recorded fixtures, a maintainer dispatches it with `tag` (path 2); apps activate it within 24 h, sooner after a breaker opening (N11). This reaches users whose APK updates Android blocks ([Developer verification](#developer-verification)).

APK path second (path 3: a shim change, a new `SHIM_API_VERSION`, a new pinned key, or the bundled version must move): shim fix PR or `scripts/engine/bump-ytdlp.sh <approved version>` → CI (`verifyBundledYtDlp`, `checkPythonLicences`, `shimTest`, recorded-response tests) → merge → dispatch `nightly.yml` with `scope: youtube-smoke` on `main` (minified `release` smoke through `:ytx`) → `release.sh patch --hotfix` → `release.yml`. Skipped for hotfixes: profiles, benchmarks, locales, manual matrices. N11's target is < 30 min from **tag** to the signed, published release; the whole APK path is about an hour (PR CI ≈ 15 min, `youtube-smoke` ≈ 12 min, release ≤ 30 min; timeline in 04's runbook), after which the in-app updater and Obtainium deliver it.

### Milestone tester build

Per PLAN DoD: the milestone's acceptance criteria are listed in the release issue with the test or manual check that proves each one; tag `0.{n+1}.0-beta.N` (an increment that lands out of order ships under the current line, [D63](../PLAN.md#3-key-decisions)); an immutable pre-release with the three ABI APKs, `SHA256SUMS`, `neutrodyne-update.json` and attestations; design documents updated for deviations.

### v1.0 gate

| [PLAN M11](../PLAN.md#m11-release-hardening-and-v10) acceptance | Evidence |
|---|---|
| 1 Cold start p50 < 600 ms; per-ABI APK budgets | PB1 Macrobenchmark on the reference device; PB12/PB13 from `check-apk.sh` on the `v1.0.0` APKs |
| 2 Tag → immutable release with three APKs, mapping, `SHA256SUMS`, manifest and notes in < 30 min; `gh release verify` and `gh attestation verify` pass; the previous RC's in-app updater and Obtainium install it | `release.yml` timing and step 9 on `v1.0.0-rc.N` and `v1.0.0`; device checks with the RC's updater and Obtainium's per-ABI filter |
| 3 (M11a) Updater installs on API 26, 31, 34+ and 37 with the right ABI; wrong SHA-256, certificate, `versionCode` or package never installed | `ApkVerifierTest`, `SelfInstallerTest`, `UpdateManifestParserTest`; [device checklist](#device-checklist-m11a) |
| 4 (M11a) Updates wait for idle playback and downloads | `InstallIdleGateTest`; device checklist and 06's updater additions |
| 5 (M11a) Simulated verification rejection shows the help sheet | `pm set-developer-verification-result` on an API 37 Pixel (device checklist); fallback `VerificationFailureMapperTest` |
| 6 (M11a) Obtainium → "Managed by Obtainium"; stable never sees pre-releases; beta via `releases.atom`; no `api.github.com` request | `AppUpdaterTest`, `GitHubUpdateSourceTest`; device checklist |
| 7 Instrumented suite on the minified release APK on API 26 and 36 and an API 37 16 KB image | `instrumented-full` release leg (`-PtestBuildType=release`, `api26` and `api36` among the `nightly` group) and `api37-16k` (full minified `:app` suite, `PAGE_SIZE` 16384, `zipalign -P 16` and the ELF check of the CPython libraries and extension modules) green on the release commit or its parent per `verify-tag.sh` |
| 8 Network capture of a fresh-install session shows only expected hosts | `scripts/ci/network-capture.sh`, run by a maintainer on a workstation (it needs the real internet, so it is never a CI job) against the `v1.0.0-rc.N` `x86_64` APK: emulator (`system-images;android-36;default;x86_64`, no Google apps) started with `-tcpdump`, UI Automator session (subscribe 2 real feeds, refresh, stream 30 s, download one episode, search "news", add and play one YouTube channel, Settings › Updates › Check now, Settings › YouTube › Check for engine update); `tshark` extracts DNS names and TLS SNI; every host is mapped to an inventory ID (`app-updates` and `youtube-engine` included; never `api.github.com`); OS hosts (connectivity check, NTP) listed separately. Unverified: emulator `-tcpdump` on API 36 images |
| 9 Migration from the first tester schema; a device upgraded from the last beta through the in-app updater keeps all data | 02's `MigrateAllTest`; manual upgrade on the reference device from the last `-beta` APK through Settings › Updates, comparing library, groups, history, Up next and downloads |
| 10 PO-10, PO-14, PO-31–PO-36 resolved | PLAN §4 updated (PO-1, PO-2, PO-5 and PO-8 resolved on 2026-10-05) |
| 11 Database ≤ 100 MB at the N5 scale; retention deletes exactly the unprotected absent episodes | PB14 (02's size measurement on `SeedDatabase`); 02's `RetentionTest` |
| 12 v3.1 rotation rehearsed | the [runbook](#key-loss-or-compromise) with throwaway keys A and B: an APK signed with A updates to one signed with the A → B lineage on API 33+; the behaviour on API 28–32 is recorded in the release issue |

Plus: every earlier milestone's acceptance criteria green; key ceremony done, holders per PO-35, backups verified; README "Install and update" final and identical in substance to 08's help page; mirror decided (PO-34) and, if chosen, working; `PRIVACY.md` final; Licences screen and `THIRD_PARTY_NOTICES.md` list every bundled component (CPython and its libraries, Chaquopy, yt-dlp, yt-dlp-ejs, the CA bundle; [04 Notices](04-youtube.md#notices)); ACRA mailbox configured and a test report received.

---

## Settings

Keys owned here ([01 DataStore files and typed setting keys](01-foundation.md#datastore-files-and-typed-setting-keys)). UI on 08's `PRIVACY`, `UPDATES` and `ABOUT` pages ([08 Updates settings](08-ui-ux.md#updates-settings)).

| Key | Type | Default | File | UI location | Milestone |
|---|---|---|---|---|---|
| `privacy.crash_reports` | Bool | true | `settings` | Settings › Privacy › "Offer to send crash reports" (dialog per crash; off = ACRA reports nothing) | M0 (UI M11) |
| `diagnostics.verbose_log_until` | Long? (epoch ms) | null | `device_settings` | Settings › About › Diagnostics › "Detailed log for 24 hours" | M11 |
| `updates.mode` | Choice `UpdateMode` (`OFF`, `NOTIFY`, `AUTOMATIC`) | `NOTIFY` ([PO-31](../PLAN.md#48-further-product-owner-decisions)) | `settings` | Settings › Updates, mode radio rows; the first-run card's "Turn off" | M11a |
| `updates.channel` | Choice `UpdateChannel` (`STABLE`, `BETA`) | `STABLE` ([PO-33](../PLAN.md#48-further-product-owner-decisions)) | `settings` | Settings › Updates › "Beta versions" | M11a |
| `updates.last_check_at` | Long? (epoch ms) | null | `device_settings` | Settings › Updates "Last checked" line | M11a |
| `updates.skipped_version_code` | Long? | null | `device_settings` | none ("Skip this version") | M11a |
| `updates.first_run_choice_done` | Bool | false | `device_settings` | none (first-run card) | M11a |
| `updates.verification_notice_shown_at` | Long? (epoch ms) | null | `device_settings` | none (verification notice) | M11a |
| `updates.whats_new_version_code` | Long? | null | `device_settings` | none ("What's new" sheet) | M11a |

`privacy.crash_reports` is mirrored into ACRA's own SharedPreferences file `acra` (`sharedPreferencesName` above), key `acra.enable` (`ACRA.PREF_ENABLE_ACRA`), by an `AppInitializer` (order 20, platform band) and on every change of the setting. ACRA reads that key when it initialises in `attachBaseContext` — long before DataStore can be read — and its `ErrorReporterImpl` listens for changes to it, so the switch takes effect immediately and survives process restarts (`ACRA.errorReporter.setEnabled(…)` alone would last only until the process dies). The `acra` file is outside the Auto Backup include list ([D34](../PLAN.md#3-key-decisions)), so a restored device starts enabled until the initializer mirrors the restored setting. `diagnostics.db_quick_check_failed_at` is 02's key.

`updates.mode` and `updates.channel` are portable (05's backup whitelist); the other `updates.*` keys are device-bound state and never backed up, so a restored phone shows the first-run card and the verification notice again where they apply. A restored mode never overrides a `Disabled` reason ([In-app updater](#modules-and-api)).

Settings › Privacy page content (09): crash-reports switch; "What Neutrodyne connects to" (the [inventory](#network-inventory) with each row's current state, e.g. Podcast Index "off — no key" or "on" (user key, or a release-build key under PO-3 option A), app updates "Notify", YouTube-engine updates "Neutrodyne-approved"); links to the Discover providers (03), show-notes images (03), Settings › Updates and Settings › YouTube; "Privacy policy" (opens `PRIVACY.md`).

---

## Delivery by milestone

| Milestone | Delivered in this area |
|---|---|
| [M0](../PLAN.md#m0-scaffold-and-ci) | `configureNeutrodyneTestTasks`, `neutrodyne.android.testing` content, `:core:common` test fixtures (`TestClock`, `MainDispatcherRule`, `Goldens`), `:core:testing` (`FakeNetworkMonitor`, `FakeSettingsRepository`, `FakeCrashReporter`, `FakeCrashContext`, `Nightly`), Robolectric `sdk=36`, Roborazzi wiring with one Settings screenshot, GMD `api26`/`api36` (API 26 GMD check, emulator-runner fallback if needed; split-install check), `disableEmptyDeviceTests`, and E0 (version and ABI; `selftest` in `:ytx` when S7 is go); `keepalive.yml`; `ci.yml` (static including `checkPythonLicences` and `verifyBundledYtDlp`, unit, assemble with the per-ABI `check-apk.sh`, instrumented; `actions/setup-python`); `nightly.yml` (`instrumented-full`, `api37-16k`, `repro` report-only, `mirror`); `release.yml` (container build, three signed ABI APKs, mapping, `make-update-json.sh`/`check-update-json.sh`, `SHA256SUMS`, `actions/attest`, draft → publish as an immutable release, `gh release verify`); repository settings (immutable releases, rulesets, environment `release`); Codeberg push mirror (PO-34); `changelogs/`; key ceremony with the PO-35 holders; first signed pre-release `v0.1.0-beta.1` (PLAN M0 AC7); README "Install and update" first draft with the certificate fingerprint; Renovate with the Chaquopy rule; `.editorconfig`, Lint, Spotless, detekt; PR and issue templates; `installAcra` (never in `:ytx`) + `CrashReportRedactor` (disabled until PO-10), `CrashReporter`/`CrashContext`; `PRIVACY.md` v0, `SECURITY.md`; `privacy.crash_reports` key; `:update:api`/`:update:impl` module stubs |
| [M1](../PLAN.md#m1-subscribe-and-ingest-rss) | fakes for 03's interfaces + contracts; `:core:database` test fixtures (02's `TestDb`); golden switch in `:feeds`; `MutationRobustnessTest` for `FeedParser` and `mutation-full`; platform-parser corpus in `:core:data` `androidTest`; schema drift and frozen-schema checks live; `TestServer`, E1 (Add by URL); data builders, `fakeImageLoader`; accessibility checks under Robolectric verified (or the instrumented fallback adopted); inventory row `add-input` (typed URLs) |
| [M2](../PLAN.md#m2-groups-and-group-feeds) | fakes for 05/08 interfaces, `FakeYouTubeCapabilitiesSource` and `testCapabilities`; `ScreenshotTier`; E2; `FeedQueryTimingTest` recorded on GMD and run on the reference device (R2.9, PLAN M2 AC2); reference device confirmed with the PO |
| [M3](../PLAN.md#m3-import-export-and-backup) | OPML/backup mutation providers; E5, E6, E9; nightly `bmgr` job; `RecordingAppNavigator` |
| [M4](../PLAN.md#m4-playback-core) | Media3 test-utils forcing verified; `api34` device; 06's `PlaybackServiceTest` in nightly (incl. API 37 hardening); E3 |
| [M5](../PLAN.md#m5-playback-features-and-system-surfaces) | manual device-matrix template in the release issue |
| [M6](../PLAN.md#m6-downloads) | `:benchmark` module for system tests, `bench34`, `system-tests` job, E10; `api33` device; E4; `bmgr` assertion for `Podcasts/` |
| [M7](../PLAN.md#m7-discovery) | `PRIVACY.md` and in-app inventory gain `apple`, `fyyd`, `podcastindex` and the autodiscovery probes of `add-input`; `PrivacyInventoryParityTest`; E1 deep-link case |
| [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds) | E8 (`ExternalYouTubeModeTest`) in `instrumented` (every build is in external mode until M9a); YouTube import-parser mutation providers; inventory `youtube-subscriptions` |
| [M9](../PLAN.md#m9-youtube-playback-and-downloads-via-the-embedded-yt-dlp-engine) (M9a) | E7 on the minified `release` APK through `:ytx` (`ReplayRH` hook, `ytxReplay` argument); `shimTest` in `unit`; `check-apk.sh` content scan and ELF alignment check against the real engine stack; nightly `no-engine-build` (blocking), `youtube-canary` and `engine-nightly-canary`; `FakeYouTubeEngine`; PB18–PB21 from the spike; inventory `youtube-streams` rewritten for the engine; YouTube engine lines in diagnostics and `CrashKey.YOUTUBE_HEALTH` |
| [M9](../PLAN.md#m9-youtube-playback-and-downloads-via-the-embedded-yt-dlp-engine) (M9b) | [`engine-canary.yml`](#engine-canaryyml) with environments `engine-approval` and `github-pages`, the Ed25519 manifest-key ceremony, Pages source "GitHub Actions", the bootstrap manifest, the engine heartbeat and `keepalive.yml` covering the canary; contract-mode gate and strict-mode report; label `engine-canary`; inventory `youtube-engine`; time budget "upstream stable → approved ≤ 6 h" measured on a real yt-dlp release |
| [M10](../PLAN.md#m10-covers-theming-adaptive-layouts-and-accessibility) | `FULL` screenshot tier and `screenshots-full`; pseudo-locale/RTL captures; Weblate project opened at the end of M10 if strings are stable (otherwise M11); `:benchmark` journeys, `BenchmarkSeedReceiver`, `benchmark-dryrun`; PB3 on the reference device (M10 AC7) |
| [M11](../PLAN.md#m11-release-hardening-and-v10) (M11a) | the [in-app updater](#in-app-updater) in `:update:impl` (all classes, the four work names, `updates` notification, `VerificationTimeline`), `FakeAppUpdater`, `FakeUpdateNotices`, its tests and [device checklist](#device-checklist-m11a); `updates.*` keys; inventory `app-updates` and the Settings › Privacy rows; README "Install and update" final draft and the facts for 08's help page and notice ([Developer verification](#developer-verification)); the `verification-watch` calendar issue |
| [M11](../PLAN.md#m11-release-hardening-and-v10) (M11b) | baseline/startup profiles and `baseline-profile.yml`; all budgets measured (per-ABI sizes and engine budgets included); `DiagnosticsRepository` and contributors, `DatabaseCopyExporter`, `RedactionCoverageTest`, `diagnostics.verbose_log_until`; ACRA mailbox (PO-10); `PRIVACY.md` final; launch languages (PO-14); GitHub release hardening (release body template final, immutable releases and attestations verified end to end on an RC); v3.1 rotation rehearsal (PLAN M11 AC12); mirror per PO-34; `live-canary`; network capture; v1.0 gate |
| M12–M15 | Glance widget screenshot tests (M13); SponsorBlock destination in the inventory, with the engine (M14); the JS challenge provider in the engine canary's test set if it moves to M14; revisit Compose Preview Screenshot Testing once AGP test suites are stable |

---

## New names introduced here

| Name | Kind | Location |
|---|---|---|
| `configureNeutrodyneTestTasks()`, `configureAndroidTesting()`, `configureManagedDevices()`, `disableEmptyDeviceTests()` | build-logic functions | `build-logic/convention` |
| Gradle properties `updateGoldens`, `screenshotTier`, `mutationIterations`, `testBuildType`, `neutrodyne.testScope`; instrumentation argument `ytxReplay` | build switches | — |
| System properties `neutrodyne.updateGoldens`, `neutrodyne.moduleDir`, `neutrodyne.rootDir`, `neutrodyne.screenshotTier`, `neutrodyne.mutationIterations` | test configuration | — |
| `TestClock`, `MainDispatcherRule` (canonical names; physical location), `Goldens` | test helpers | `:core:common` test fixtures, package `ch.lkmc.neutrodyne.core.testing`, re-exported by `:core:testing` |
| `Nightly`, `ScreenshotTier`, `assumeTier` | test selection | `:core:testing` |
| `Fake*` per [inventory](#coretesting-inventory) (including `FakeAppUpdater`, `FakeUpdateNotices`), `*Contract` bases, data builders, `fakeImageLoader`, `testCapabilities(external)` | fakes | `:core:testing` |
| `RecordingAppNavigator` | fake | `:core:navigation` test fixtures |
| `TestServer`, `FixtureDispatcher`, `TestSeeder`, `BackgroundStandInActivity` (declared in the androidTest manifest) | E2E helpers | `:app/src/androidTest` |
| `YtxTestHooks` (04 owns the class; 01's consumer rule keeps it) | test hook | `:youtube:ytdlp` |
| `SmokeTest`, `SubscribeJourneyTest`, `GroupFeedJourneyTest`, `PlayJourneyTest`, `DownloadJourneyTest`, `ImportJourneyTest`, `BackupRestoreJourneyTest`, `YouTubeReleaseSmokeTest` (journeys; E8 is 04's `ExternalYouTubeModeTest`) | E2E tests | `:app/src/androidTest` |
| `MutationRobustnessTest`, task `mutationTest` | N9 robustness | `:feeds`, `:youtube:api` |
| `PrivacyInventoryParityTest`, `RedactionCoverageTest`, `CrashReportRedactorTest`, `DatabaseCopyExporterTest` | tests | `:feature:settings`, `:core:data`, `:app`, `:core:data` |
| GMD devices `api26`, `api33`, `api34`, `api36`, `bench34`; groups `ci`, `nightly` | devices | build-logic, `:benchmark` |
| `CrashReporter`, `CrashContext`, `CrashKey` | interfaces/enum | `:core:common` |
| `installAcra` (01's name, defined here), `AcraCrashReporter`, `CrashReportRedactor` | ACRA integration | `:app` |
| `DiagnosticsReport`, `DiagnosticsSection`, `DiagnosticsLine`, `DiagnosticsSectionId`, `DiagnosticsSeverity` | data | `:core:model` |
| `DiagnosticsContributor`, `DiagnosticsRepository`, `DiagnosticsError` | interfaces | `:core:domain` |
| `DiagnosticsRepositoryImpl`, `DatabaseCopyExporter` | implementations | `:core:data` |
| `BenchmarkSeedReceiver` | benchmark-only receiver | `app/src/benchmarkShared` |
| `AppUpdater`, `UpdateState`, `UpdateDisabledReason`, `UpdateInfo`, `UpdateMode`, `UpdateChannel`, `InstallBlockReason`, `UpdateError`, `UpdateNotices`, `UpdateNotice` | updater API | `:update:api` |
| `AppUpdaterImpl`, `UpdateNoticesImpl`, `GitHubUpdateSource`, `UpdateManifestParser`, `VersionScheme`, `UpdateCheckWorker`, `UpdateDownloadWorker`, `UpdateInstallWorker`, `ApkVerifier`, `ApkInspector`, `SelfInstaller`, `UpdateStatusReceiver`, `InstallIdleGate`, `InstallerOfRecordDetector`, `VerificationFailureMapper`, `UpdateNotifier`, `VerificationTimeline`, `UpdateModule` | updater implementation | `:update:impl` |
| Work `app-update-check`, `app-update-check-now`, `app-update-download`, `app-update-install`; files `noBackupFilesDir/updates/` (`pending.json`, one APK) | updater | `:update:impl` |
| `UpdateManifestParserTest`, `GitHubUpdateSourceTest`, `ApkVerifierTest`, `ApkInspectorTest`, `SelfInstallerTest`, `VerificationFailureMapperTest`, `InstallIdleGateTest`, `AppUpdaterTest`, `UpdateNoticesTest` | tests | `:update:impl`, `:app` (`ApkInspectorTest`) |
| `BuildInfo.shippedLocales` | field request | `:core:model` (01) |
| `privacy.crash_reports`, `diagnostics.verbose_log_until`, `updates.mode`, `updates.channel`, `updates.last_check_at`, `updates.skipped_version_code`, `updates.first_run_choice_done`, `updates.verification_notice_shown_at`, `updates.whats_new_version_code` | setting keys | `:core:model` registry |
| Workflows `ci.yml`, `nightly.yml`, `release.yml`, `engine-canary.yml`, `record-screenshots.yml`, `baseline-profile.yml`, `keepalive.yml`; engine heartbeat `engine/ytdlp-heartbeat.json` (+ `.sig`) on GitHub Pages; jobs per [CI pipelines](#ci-pipelines) (new: `no-engine-build`, `engine-nightly-canary`, `mirror`); GitHub environments `release`, `engine-approval`, `github-pages`; labels `run-instrumented`, `nightly-failure`, `release-blocker`, `engine-canary`, `repro`, `verification-watch`, `chaquopy` | CI | `.github/` |
| Release assets `neutrodyne-{v}-{abi}.apk`, `neutrodyne-{v}-mapping.txt`, `neutrodyne-update.json`, `SHA256SUMS` | release | GitHub Releases |
| Scripts per [CI scripts](#ci-scripts) (new: `make-update-json.sh`, `check-update-json.sh`), `scripts/release.sh`, `scripts/l10n/update-shipped-locales.sh`, `scripts/ci/network-capture.sh` | scripts | `scripts/` |
| `app/policy/locales.txt`, `app/lint-baseline.xml`, `config/detekt/detekt.yml`, `renovate.json`, `changelogs/<versionCode>.txt`, `PRIVACY.md`, `SECURITY.md`, `.editorconfig`, `app/proguard-test.pro`, `neutrodyne.lineage` (only after a rotation) | files | repository |
| Secrets `NEUTRODYNE_ENGINE_MANIFEST_KEY`, `CODEBERG_MIRROR_KEY`; variables `NEUTRODYNE_CERT_SHA256`, `NEUTRODYNE_PREVIOUS_CERT_SHA256` (rotation only); manifest field `previousCertSha256` | CI configuration | GitHub |
| Build-output checks PB12, PB13 (per ABI), PB18–PB21 | budget IDs | this document |

---

## Open questions

1. Resolved: PLAN [5.1](../PLAN.md#51-module-graph) and 01's rule 13 confirm `:core:testing` as an Android library, with `TestClock`, `MainDispatcherRule` and `Goldens` in `:core:common` test fixtures re-exported by `:core:testing`.
2. Resolved: `:benchmark` is created in M6 (system tests), gains Macrobenchmarks in M10 and profiles in M11 (PLAN 5.1, M6, M10, M11; 01's module table).
3. Resolved: 01 commits `neutrodyne.acraMailto` in `gradle.properties`, [D62](../PLAN.md#3-key-decisions) records it, and [PO-3](../PLAN.md#po-3-podcast-index-api-key-handling) option A injects a Podcast Index key into release builds only (`release.yml`), after Podcast Index's written permission ([D26](../PLAN.md#3-key-decisions)).
4. Resolved: PLAN M0 delivers the key ceremony and the signed `v0.1.0-beta.1`; [PO-8](../PLAN.md#48-further-product-owner-decisions) is resolved (`ch.lkmc.neutrodyne`, maintainer-held key) and [PO-35](../PLAN.md#48-further-product-owner-decisions) blocks M0 for the key holders; [PO-5](../PLAN.md#po-5-google-developer-verification) is resolved (not registering), and directly sideloaded tester builds are unaffected until Google's global rollout.
5. Resolved: 02 moved its fixtures to `:core:database` test fixtures (`core/database/src/testFixtures/`).
6. Resolved: PLAN M0 acceptance 4 reads "API 26 device (Gradle Managed Device, or an android-emulator-runner API 26 emulator)".
7. Resolved in 02 ([02 db-maintenance worker](02-data-model.md#db-maintenance-worker)): the scrub covers `episode.identityKey`, `episode.guid`, `podcast.artworkUrl`, `episode.imageUrl`, `episode.chaptersUrl`, `episode_alt_enclosure.sourcesJson` and `artwork.url`, and the target lies under `cacheDir/export/`.
8. Resolved in 01: rule 4 keeps segments shorter than 15 characters, so the 15-character example is masked.
9. Moved to [PO-28](../PLAN.md#48-further-product-owner-decisions) (default: Pixel 7a).
10. Obsolete since 2026-10-05: there is one build; ACRA by email in every APK by default ([PO-10](../PLAN.md#48-further-product-owner-decisions)), never in `:ytx`.
11. Obsolete since 2026-10-05: there is no store listing, so no audience rating to decide.
12. Resolved in 01: committed `neutrodyne.acraMailto` (forced empty on `debug` here); AboutLibraries offline mode; `BuildInfo.shippedLocales`; the `Text("` literal pattern in `checkBannedApis`; `neutrodyne.jvm.library` calls `configureNeutrodyneTestTasks()`; test fixtures on `:core:common`, `:core:database` and `:core:navigation`; `verifyDependencyPolicy` fails on `io.mockk` in any `*AndroidTestRuntimeClasspath`; `include(":benchmark")` in M6.
13. Obsolete since 2026-10-05: no store scans the source tree; committed `.zip`/`.gz` fixtures stay test inputs ([Fixture policy](#fixture-policy)).
14. Unverified, checked in the named milestone: GMD API 26 and AGP 9.4 managed-device DSL names (M0); the device-test disable accessor (M0); that GMD and `connected*` test tasks install the ABI split matching the emulator (M0); Kotlin in Android test fixtures (M0); Robolectric default-locale override and pseudo-locale qualifiers (M0/M2); accessibility checks under Robolectric (M1); compose-rules `.editorconfig` keys (M0); `generateLocaleConfig` with `localeFilters` and the generated locale-config file name (M0); android-emulator-runner with a minor-versioned `api-level` (M0); AGP 9's ABI-split output file names and the `llvm-readelf` binary on the runner (M0); `actions/setup-python` and the `python:3.14-slim-trixie` image as Chaquopy's build Python (M0, with S7); Chaquopy's `.pyc` determinism under `SOURCE_DATE_EPOCH` (M0); Codeberg mirror policy (M0); R8 keeping `YtxTestHooks` (M9a); GitHub Pages cache lifetime and partial-deployment behaviour (M9b); plugin-created build-type source sets (M10); Renovate hosted wrapper regeneration (M0) and a regex manager for the container digest (M0); `releases.atom` entry count and format, and GitHub's limits for unauthenticated `releases/download` and Atom requests (M11a); Obtainium's package IDs and `getInstallSourceInfo` visibility for them (M11a); Robolectric support for `commitSessionAfterInstallConstraintsAreMet` and the status reported at the constraint timeout (M11a); `pm set-developer-verification-result` on a retail Android 17 Pixel (M11a); whether deleting a never-published draft keeps a tag name usable under immutable releases (M0); which events count as repository activity for GitHub's 60-day schedule rule and whether `gh workflow enable` resets it (M0, `keepalive.yml`); an independent heartbeat check on the Codeberg mirror (M9b, with PO-34); AGP 9.4 signing with a rotation lineage (M11b); emulator `-tcpdump` on API 36 (M11b); Weblate statistics API (M11).
15. Resolved (asked by 08): "Check now" works in mode `Off` as a one-off user action and is refused only in `Disabled(DEBUG_BUILD)` and `Disabled(MANAGED_BY_OTHER_INSTALLER)`.
16. Resolved (asked by 06, its open question 16): `InstallIdleGate` uses `PlaybackStateSource.busy` with 06's 10-min window after the player stops being engaged; a shorter window is not worth removing a briefly paused user's notification.
17. Installer of record for ADB and Shizuku installs (including Obtainium in Shizuku mode) is presumably the shell (Unverified). Default: the in-app updater stays on for them; a user who prefers Obtainium turns the updater Off. Revisit if both updaters fight in practice.
18. Mirror of release assets and manifests with an updater fallback URL (PO-34): the PO decides before M11b; the design constraints are in [Mirror](#mirror).
19. Unverified (risk T17): how a targetSdk-37 self-update behaves under enforcement with the advanced flow on — silent success, Google's warning, or `STATUS_FAILURE_ABORTED` — and how devices below Android 16 QPR2 report a block (possibly `STATUS_FAILURE_BLOCKED`, currently mapped to `BLOCKED_BY_POLICY`). Retest on an enforcing certified device when the global rollout starts; until then every blocked or failed state offers "Download in browser".
20. Unverified (risk P9): the consequences for our installs if someone registers `ch.lkmc.neutrodyne` with another key before Neutrodyne has 50 installs; no mitigation short of registering exists.
21. Should the engine canary also re-run against the currently approved version when the shim changes on `main` (so a shim PR cannot silently break the approved engine)? Default: no — shim PRs run `shimTest` against the bundled version in CI and against the latest approved one locally (PR template); revisit if a regression slips through.
22. Unverified: GitHub's behaviour for `make_latest` when a stable patch of an older line is published after a newer stable; `verify-tag.sh` avoids the case by computing `make_latest` itself.
23. Resolved with 08: when a `DEVELOPER_BLOCKED` result carries `Intent.EXTRA_INTENT`, `Blocked.systemExplanation` is true and the blocked sheet offers "Continue in Android" (`openSystemExplanation()`; the OS "can provide additional context" there, [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller)). The intent stays in `:update:impl` memory; after process death the action is not offered.

---

## Sources

All checked 2026-10-04 by the research behind this plan unless marked otherwise; entries marked (2026-10-05) were checked or re-checked for the product owner's decisions of that day.

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
- Gradle Managed Devices (ATD removes SystemUI, launcher and Settings; "use API levels 27 and higher"; GPU and sharding properties; re-checked 2026-10-05) — https://developer.android.com/studio/test/gradle-managed-devices
- System images: ATD x86_64 API 30–36, `default` x86_64 API 26–36, API 37 only as `android-37.x` Google APIs images incl. `ps16k` (read 2026-10-05) — https://dl.google.com/android/repository/sys-img/aosp_atd/sys-img2-3.xml · https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml · https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml · https://dl.google.com/android/repository/sys-img/google_apis_playstore/sys-img2-3.xml
- Pseudolocales and locale filters — https://developer.android.com/guide/topics/resources/pseudolocales
- AGP 9.0 defaults (tested build type only, R8 strict keep rules, GMD replaces device providers) — https://developer.android.com/build/releases/agp-9-0-0-release-notes
- R8 and shrinking — https://developer.android.com/build/shrink-code · https://developer.android.com/build/releases/past-releases/agp-8-0-0-release-notes
- Baseline profiles — https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
- Testing backup and restore (`bmgr`) — https://developer.android.com/identity/data/testingbackup
- TestParameterInjector — https://repo1.maven.org/maven2/com/google/testparameterinjector/test-parameter-injector/maven-metadata.xml
- OkHttp / MockWebServer 5.5.0 — https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml
- `py_compile`: checked-hash `.pyc` when `SOURCE_DATE_EPOCH` is set (2026-10-05) — https://docs.python.org/3/library/py_compile.html

CI and tooling:
- GitHub-hosted runners (4 vCPU / 16 GB / 14 GB for public repositories) — https://docs.github.com/en/actions/reference/runners/github-hosted-runners
- Hardware-accelerated Android emulation on Linux runners — https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/
- setup-gradle v6 (basic vs enhanced caching, wrapper validation) — https://github.com/gradle/actions/blob/main/docs/setup-gradle.md · https://github.com/gradle/actions/releases
- `actions/setup-python` — https://github.com/actions/setup-python
- android-emulator-runner — https://github.com/ReactiveCircus/android-emulator-runner
- Renovate Gradle manager and wrapper advisory — https://docs.renovatebot.com/modules/manager/gradle/ · https://www.vulncheck.com/advisories/renovate-before-44.14.7-command-injection-via-gradle-wrapper
- Dependabot version catalogs and lockfiles — https://github.blog/changelog/2023-03-13-dependabot-version-updates-keeps-gradle-version-catalogs-up-to-date/ · https://github.blog/changelog/2025-06-24-dependabot-support-for-gradle-lockfiles-is-now-generally-available/
- detekt compatibility table — https://detekt.dev/docs/introduction/compatibility/
- Licensee — https://github.com/cashapp/licensee
- Kotlin Gradle plugin compatibility — https://kotlinlang.org/docs/gradle-configure-project.html
- Gradle current version — https://services.gradle.org/versions/current
- Release container (2026-10-05): Docker's official Python 3.14 image variants incl. `slim-trixie` — https://github.com/docker-library/python/tree/master/3.14 · Debian trixie `python3` 3.13.5 — https://packages.debian.org/trixie/python3 · Debian trixie `openjdk-21-jdk-headless` — https://packages.debian.org/trixie/openjdk-21-jdk-headless
- `actions/deploy-pages` (permissions `pages: write`, `id-token: write`; environment `github-pages`; needs `actions/upload-pages-artifact`) (2026-10-05) — https://github.com/actions/deploy-pages
- GitHub Pages limits — https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits
- Scheduled workflows disabled in public repositories after 60 days without activity (checked 2026-10-05) — https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows
- OpenSSL `pkeyutl` (`-rawin` signing for Ed25519) — https://docs.openssl.org/3.0/man1/openssl-pkeyutl/

Distribution, signing and updates (2026-10-05):
- GitHub immutable releases (assets and tag locked, draft → publish, release attestation, tag names not reusable) — https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases · https://github.blog/changelog/2025-10-28-immutable-releases-are-now-generally-available/
- `actions/attest` v4 (`subject-path` globs and lists; permissions `id-token`, `attestations`, `artifact-metadata`) — https://github.com/actions/attest
- Artifact attestations (SLSA v1.0 Build Level 2; "not a security guarantee") — https://docs.github.com/en/actions/concepts/security/artifact-attestations
- `gh release verify-asset` and `gh release verify` — https://cli.github.com/manual/gh_release_verify-asset
- `releases/latest/download/<asset>` links — https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases
- Latest release = most recent non-prerelease, non-draft — https://docs.github.com/en/rest/releases/releases#get-the-latest-release
- REST API rate limits (60 unauthenticated requests per hour per IP) — https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api
- youtube-dl reinstated on GitHub (takedown reversed after weeks) — https://github.blog/2020-11-16-standing-up-for-developers-youtube-dl-is-back/
- Obtainium (APK-only asset filter, `autoApkFilterByArch`, REST source) — https://github.com/ImranR98/Obtainium · deep links and badge — https://wiki.obtainium.imranr.dev/deep_links/
- AppVerifier format (package name, colon-separated SHA-256) — https://github.com/soupslurpr/AppVerifier
- `apksigner` (`verify --print-certs`, `rotate`, `--lineage`, `--rotation-min-sdk-version`) — https://developer.android.com/tools/apksigner
- APK Signature Scheme v3 / v3.1 (rotation not recommended for API ≤ 31) — https://source.android.com/docs/security/features/apksigning/v3
- `PackageInstaller` (`STATUS_*`, `EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON` from API 36.1, `commitSessionAfterInstallConstraintsAreMet`) — https://developer.android.com/reference/android/content/pm/PackageInstaller
- `PackageInstaller.SessionParams.setRequireUserAction` (API 31 conditions) — https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams
- `InstallConstraints` (`GENTLE_UPDATE`, API 34; "not interacting" includes playing audio, network traffic and being visible to the user; checked 2026-10-05) — https://developer.android.com/reference/android/content/pm/PackageInstaller.InstallConstraints.Builder · `commitSessionAfterInstallConstraintsAreMet`/`waitForInstallConstraints` throw `SecurityException` unless the caller is the installer of record; `EXTRA_INTENT` with a verification failure "can provide additional context" — https://developer.android.com/reference/android/content/pm/PackageInstaller
- `apksigner` rotation: rotated keys go into the v3.1 block for API 33+ by default; earlier platforms use the original signer (checked 2026-10-05) — https://developer.android.com/tools/apksigner
- `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` — https://developer.android.com/reference/android/provider/Settings
- AOSP `PackageInstallerSession` (verifier skipped for shell/root callers; installers targeting > 36 get blocking results directly) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/pm/PackageInstallerSession.java
- 16 KB page sizes — https://developer.android.com/16kb-page-size · https://developer.android.com/guide/practices/page-sizes
- yt-dlp release files and signing key (engine canary inputs) — https://github.com/yt-dlp/yt-dlp#release-files · https://github.com/yt-dlp/yt-dlp/blob/master/public.key
- Chaquopy (build-time `.pyc`, Python ≥ 3.12 64-bit only) — https://chaquo.com/chaquopy/doc/current/android.html · FAQ — https://chaquo.com/chaquopy/doc/current/faq.html
- Unlicense — https://en.wikipedia.org/wiki/Unlicense

Developer verification (2026-10-05):
- Overview and timeline — https://developer.android.com/developer-verification
- Guides (participating stores, phases) — https://developer.android.com/developer-verification/guides
- FAQ (sideloads unaffected before the global rollout, ADB exempt, updates fail when the advanced flow is off, lost key prevents registration) — https://developer.android.com/developer-verification/guides/faq
- Affected devices (Help Center) — https://support.google.com/android/answer/17065026?hl=en
- Advanced flow (Help Center) — https://support.google.com/android/answer/17588095?hl=en
- Advanced flow announcement (blog 2026-03-19) — https://android-developers.googleblog.com/2026/03/android-developer-verification.html
- Package-name registration rules — https://developer.android.com/developer-verification/guides/android-developer-console
- Limited distribution (20 devices) — https://developer.android.com/developer-verification/guides/limited-distribution
- LineageOS statement — https://lineageos.org/Developer-Verification/
- Shizuku setup (restart after reboot) — https://shizuku.rikka.app/guide/setup/
- Android Auto and sideloaded apps — https://www.androidauthority.com/sideload-apps-on-android-auto-3681820/
- Doze, App Standby and battery-optimisation exemptions — https://developer.android.com/training/monitoring-device-state/doze-standby
- YouTube bot challenges from data-centre IPs (secondary) — https://www.technetexperts.com/?p=11741

Privacy, crash reporting, localisation and platform:
- ACRA setup, senders, interactions — https://www.acra.ch/docs/Setup · https://www.acra.ch/docs/Senders · https://www.acra.ch/docs/Interactions
- ACRA 5.14.2 internals (administrator `shouldSendReport` before `saveCrashReportFile`; `acra.enable`/`acra.disable` preferences, `sharedPreferencesName`, `ErrorReporter.setEnabled`), read from the published bytecode 2026-10-05 — https://repo1.maven.org/maven2/ch/acra/acra-core/5.14.2/
- GitHub issue creation from URL query parameters (issue-form fields, `414 URI Too Long`) — https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/creating-an-issue#creating-an-issue-from-a-url-query
- Hosted Weblate Libre plan — https://weblate.org/en/hosting/
- Per-app languages — https://developer.android.com/guide/topics/resources/app-languages
- Android 16 behaviour changes (targeting 36; all apps) — https://developer.android.com/about/versions/16/behavior-changes-16 · https://developer.android.com/about/versions/16/behavior-changes-all
- Android 17 behaviour changes and background audio — https://developer.android.com/about/versions/17/behavior-changes-17 · https://developer.android.com/about/versions/17/changes/bg-audio
- Prior-art reliability lessons (position-loss bug class, database growth) — https://github.com/AntennaPod/AntennaPod/releases/tag/3.12.0 · https://github.com/AntennaPod/AntennaPod/issues/4426
