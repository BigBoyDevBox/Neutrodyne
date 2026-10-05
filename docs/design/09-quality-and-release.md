# 09 — Quality and release

> Status: Draft v1, 2026-10-04; revised 2026-10-05 for the product owner's decisions (GitHub Releases only, no developer-verification registration, the embedded yt-dlp engine, no GPL code anywhere); **revised 2026-10-05 for PO-31–PO-35**: a notify-only update check in `:core:domain`/`:core:model`/`:core:data` that links to the GitHub release (no in-app download or install, no `:update:*` modules); no beta channel, so tester builds are normal releases; no mirror; every published APK a debug build signed with the keystore committed to the repository (no release key, ceremony or rotation runbook); N5's performance measured on the non-debuggable `benchmark` build type · Implements: N1 / N3 / N4 / N5 / N8 / N9 / N10 / N11 / N12 (quality and release parts), R2.9 (measurement), R3.9 (engine canary), R6.1–R6.4 (update-check behaviour and install guidance; screens in 08) · Milestones: M0–M11 (M9a, M9b, M11a, M11b), M12–M15 · Honours: D2, D3, D13, D59, D60, D61, D62, D63, D76, D77, D78, D79, D80; PO-2, PO-5, PO-8, PO-31, PO-32, PO-33, PO-34, PO-35 (resolved 2026-10-05); PO-3, PO-10, PO-14, PO-18, PO-36 defaults · Owns: test strategy and infrastructure, CI workflows (including the engine canary workflow), static-analysis gates, dependency updates, versioning, the committed debug keystore and its public-key trade-offs, GitHub-only distribution and release assets, the update-check design, the report-only reproducibility check, developer-verification guidance for unregistered distribution (including the README "Install and update" content), privacy policy and network inventory, crash reporting and diagnostics content, localisation workflow, performance budgets, release checklists

Contents: [Scope](#scope) · [Test strategy](#test-strategy) · [Test infrastructure](#test-infrastructure) · [CI pipelines](#ci-pipelines) · [Static analysis](#static-analysis) · [Dependency updates](#dependency-updates) · [Versioning and signing](#versioning-and-signing) · [Distribution channels](#distribution-channels) · [Update check](#update-check) · [Reproducible builds](#reproducible-builds) · [Developer verification](#developer-verification) · [Privacy](#privacy) · [Crash reporting and diagnostics](#crash-reporting-and-diagnostics) · [Localisation](#localisation) · [Performance budgets](#performance-budgets) · [Release checklist](#release-checklist) · [Settings](#settings) · [Delivery by milestone](#delivery-by-milestone) · [New names introduced here](#new-names-introduced-here) · [Open questions](#open-questions) · [Sources](#sources)

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
| Version scheme procedure, `scripts/release.sh`, changelogs, the committed debug keystore, signing schemes, public-key trade-offs | [Versioning and signing](#versioning-and-signing) |
| GitHub Releases as the only channel: release assets, immutable releases, provenance attestations, release body, Obtainium, tester builds, GitHub takedown | [Distribution channels](#distribution-channels) |
| Update check behaviour (in `:core:domain`, `:core:model` and `:core:data`): update manifest, checks, update card and links, notices | [Update check](#update-check) |
| Reproducibility hygiene, nightly report-only reproducibility check | [Reproducible builds](#reproducible-builds) |
| Unregistered distribution: phases, affected devices, the advanced flow, fallbacks, notice timing, watch cadence, README "Install and update" content | [Developer verification](#developer-verification) |
| `PRIVACY.md`, network inventory, redaction surfaces, `SECURITY.md` | [Privacy](#privacy) |
| ACRA configuration, `CrashReporter`/`CrashContext`, diagnostics screen content and actions | [Crash reporting and diagnostics](#crash-reporting-and-diagnostics) |
| Weblate, string conventions, shipped locales, pseudo-locales | [Localisation](#localisation) |
| Reference devices, budgets (including the per-ABI sizes of the published debug APKs and the YouTube engine's latency and memory), where each budget is measured (`benchmark` or the published build), Macrobenchmark wiring | [Performance budgets](#performance-budgets) |
| Checklists per release type and the v1.0 gate | [Release checklist](#release-checklist) |

**Not covered here:** the version catalog and convention-plugin skeletons ([01 Toolchain and versions](01-foundation.md#toolchain-and-versions)); build types (the published `debug` and `benchmark`), the `neutrodyneDebug` signing config, the dev-tools switch, ABI splits and the no-engine switch ([01 Build variants and ABIs](01-foundation.md#build-variants-and-abis), [01 Signing config](01-foundation.md#signing-config), [01 Dev-tools switch](01-foundation.md#dev-tools-switch)); Gradle-side policy tasks `assertModuleGraph`, `verifyDependencyPolicy`, `verifyManifestPermissions`, `checkSpdxHeaders`, `checkBannedApis`, the Licensee allow-list ([01 Licensing and dependency policy](01-foundation.md#licensing-and-dependency-policy)) and `checkPythonLicences`, `verifyBundledYtDlp`, `shimTest` ([01 Python and native components](01-foundation.md#python-and-native-components)) — 09 only decides when CI runs them; the logging `Redactor` algorithm ([01 Logging and redaction](01-foundation.md#logging-and-redaction)); what each feature area tests (the `## Testing` sections of [02](02-data-model.md#testing), [03](03-feeds-and-discovery.md#testing), [04](04-youtube.md#testing), [05](05-groups-opml-backup.md#testing), [06](06-playback.md#testing), [07](07-downloads.md#testing), [08](08-ui-ux.md#testing)); the diagnostics screen's visuals ([08 Diagnostics](08-ui-ux.md#diagnostics)); the update check's screens, notices and wording ([08 Updates settings](08-ui-ux.md#updates-settings), [08 Install and updates help](08-ui-ux.md#install-and-updates-help)); YouTube legal texts, the engine-update trust chain, what the engine canary tests and the hotfix runbook ([04 Licensing and legal](04-youtube.md#licensing-and-legal), [04 Engine updates](04-youtube.md#engine-updates), [04 Engine canary](04-youtube.md#engine-canary), [04 Maintenance and hotfix process](04-youtube.md#maintenance-and-hotfix-process)); what a manually installed app update means for playback and downloads ([06 App updates and playback](06-playback.md#app-updates-and-playback), [07 App update](07-downloads.md#app-update)); Auto Backup rules ([05 Auto Backup](05-groups-opml-backup.md#auto-backup)).

**Repository files owned by this document** (created in the milestone shown in [Delivery by milestone](#delivery-by-milestone)): `.editorconfig`, `.github/workflows/{ci,nightly,release,engine-canary,record-screenshots,keepalive}.yml`, `.github/PULL_REQUEST_TEMPLATE.md`, `.github/ISSUE_TEMPLATE/{bug.yml,feature.yml,release.md}`, `renovate.json`, `config/detekt/detekt.yml`, `app/lint-baseline.xml`, `app/policy/locales.txt`, `changelogs/<versionCode>.txt` (one per release), `scripts/release.sh`, `scripts/ci/*.sh` (including `make-update-json.sh`, `check-update-json.sh` and `debug-cert-sha256.sh`), `scripts/l10n/update-shipped-locales.sh`, `PRIVACY.md`, `SECURITY.md`; the keystore `signing/neutrodyne-debug.keystore` (generated once with the command in [Debug keystore](#debug-keystore)) and the text of `signing/README.md` (01 lists both files in its M0 scaffold); the content of the README's "Install and update" section ([Developer verification](#developer-verification); the README itself is maintained with the PLAN). `scripts/engine/*.sh` and `scripts/youtube/record-responses.sh` are 04's; the workflows here call them. `CONTRIBUTING.md` content is 01's ([Copied code and contributions](01-foundation.md#copied-code-and-contributions)); 09 adds the testing and release sections.

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
| Instrumented (GMD `ci` group) | migrations on both drivers, platform XML parser corpus, Keystore, `MediaController` ↔ service, E2E journeys, external-mode YouTube UI (E8), the `:ytx` process (`YtxProcessStartTest`, `YtxIsolationTest`) | `:app`, `:core:database`, `:core:data`, `:playback:impl`, `:download:impl` | Gradle Managed Devices API 26 + 36 | push to `main`, PRs labelled `run-instrumented`, nightly |
| Instrumented nightly-only | long-running and API-specific behaviour (30-min background auto-advance, 20-min UIDT beside playback, API 33 `dataSync`, API 37 hardening and 16 KB, `selftest` in `:ytx` on the 16 KB image), the R8-minified, non-debuggable `benchmark` APK (E7 through `:ytx`) | same | GMD `nightly` group + emulator-runner API 37 | nightly, release candidates |
| Out-of-process system | process death and journeys driven by UI Automator from a separate test APK; Macrobenchmark dry runs | `:benchmark` | GMD (`aosp` image) | nightly (from M6 / M10) |
| Python (shim) | `neutrodyne_ytx` against the bundled yt-dlp through `ReplayRH`: recorded scenarios, error-code mapping, option names, `selftest` API probe (04) | `:youtube:ytdlp` (`shimTest`) | pytest on a host CPython of the target minor version | every PR |
| Device and manual | Bluetooth, AVRCP, Android Auto DHU, Wear, OEM restrictions, performance budgets on the reference device (including the engine budgets), accessibility passes, `bmgr` spot checks, the update check's notification and links with a manual install over the running app ([Update check](#device-checklist-m11a)) | — | reference device + checklists | per milestone and [release](#release-checklist) |
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
| Code that verifies or activates code, validates release data or publishes releases (04's engine updates, the update check's `UpdateManifestParser`, `release.yml` and its scripts) | a negative test for every rejection reason it implements (each must leave the active engine, or the last known update state, untouched); for `release.yml` and its scripts, `check-update-json.sh` run on the changed output in the PR |
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
| E7 | `YouTubeReleaseSmokeTest` | seeded YouTube podcast (`TestSeeder`) → play → download | on the published debug APK and on the R8-minified `benchmark` APK, where R8 must have kept the classes Python reaches through Chaquopy (`PyHttp`): one resolve and one chunked download through `:ytx` with `ReplayRH` + a MockWebServer googlevideo stand-in ([04 Testing](04-youtube.md#testing), PLAN M9 AC9) | M9 (M9a) | nightly `api36` debug and benchmark legs and API 37 16 KB; `youtube-smoke` before hotfixes |
| E8 | `ExternalYouTubeModeTest` (04's) | seeded YouTube rows in external mode (every build until M9a; from M9a the test first sets `youtube.engine_enabled = false`) | "Watch on YouTube" fires `ACTION_VIEW` (captured with `Instrumentation.ActivityMonitor`); no queue/download actions; "Play group" skips them; Settings › YouTube shows the external reason (PLAN M8 AC5, M9 AC8) | M8 | `ci` |
| E9 | `FirstLaunchRestoreTest` | snapshot file + empty DB | 05's assertions ([05 Testing](05-groups-opml-backup.md#testing)) | M3 | `ci` |
| E10 | `ProcessDeathResumeTest` | kill the app mid-download from `:benchmark` | 07's assertions ([07 Instrumented and device tests](07-downloads.md#instrumented-and-device-tests)) | M6 | nightly |

E7 guards itself with `assumeTrue(BuildInfo.youTubeEngineBundled)`, so it skips on the no-engine build and on 32-bit images; E8 runs everywhere. `PlaybackServiceTest` (06), `UidtDownloadTest` and `DataSyncWorkerTest` (07) and the migration tests (02) are not journeys but run in the same instrumented jobs; their devices are listed in [Gradle Managed Devices](#gradle-managed-devices).

**E7 seam:** the engine's HTTP goes through `NeutrodyneOkHttpRH` inside `:ytx`, which an androidTest APK cannot reach with a Hilt override — and Hilt test overrides would not work against an R8-minified APK anyway. When the instrumentation argument `ytxReplay` is set (`-Pandroid.testInstrumentationRunnerArguments.ytxReplay=true` on the nightly debug and benchmark legs and in `youtube-smoke`), E7 copies 04's `ReplayRH` and one recorded scenario from its androidTest assets into `noBackupFilesDir/ytx-test/` and sets `YtxTestHooks.replayDir` (a `@VisibleForTesting` field of `:youtube:ytdlp`, kept by its consumer rules, [01 Build variants and ABIs](01-foundation.md#build-variants-and-abis); [04 Process and lifecycle](04-youtube.md#process-and-lifecycle) owns the class) before the first bind; `YtDlpClient` passes the directory to `YtxService` in the bind `Intent`, and `YtxPython` registers the replay handler above `NeutrodyneOkHttpRH`'s preference. The recorded player response is rewritten so stream URLs point at the test's MockWebServer. Only code running as the app's UID can set the hook (`YtxService` is not exported); production code never sets it, so it stays inert in the published APK (PLAN 7.2). On the debuggable published APK, `run-as` and a JDWP debugger already reach the app's UID (risk P11), so the hook adds no exposure beyond that. Unverified: that R8 keeps the field with a consumer rule alone (M9a check; fallback: the same hook read from a marker file in `noBackupFilesDir/ytx-test/`).

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
    ext.defaultConfig.testInstrumentationRunner =
        if (path == ":app") "ch.lkmc.neutrodyne.NeutrodyneTestRunner"   // switches ACRA off (Gradle Managed Devices, hygiene)
        else "androidx.test.runner.AndroidJUnitRunner"
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
- AGP 9 creates unit tests only for the tested build type (`debug`, the published build type): every module, `:app` included, runs `testDebugUnitTest` once (there are no product flavors, [D2](../PLAN.md#3-key-decisions)). The nightly `dev-tools-build` also runs `:app:testDebugUnitTest` with `-Pneutrodyne.devTools=true`, so the dev-tools code keeps compiling and passing ([CI pipelines](#nightlyyml)).
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
| `FakeAppUpdateChecker`, `FakeUpdateNotices` | [`AppUpdateChecker`, `UpdateNotices`](#modules-and-api) (`:core:domain`) | 09 | M11a |
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
- No test-specific network security config is needed: the published build permits cleartext (D28), and no module has a `src/debug/` directory (01); dev-tools builds only add `<debug-overrides>` ([01 Dev-tools switch](01-foundation.md#dev-tools-switch)).

### Media3

`media3-test-utils` and `media3-test-utils-robolectric` (`testImplementation` in `:playback:impl` only): `TestExoPlayerBuilder` with `FakeClock`, `FakeMediaSource`/`FakeTimeline`, `TestPlayerRunHelper.advance(player).untilState(…)` / `play(player).untilPositionAtLeast(…)` (the old `run()` is deprecated). Real-decode cases use 06's generated fixtures with `ShadowMediaCodecConfig`. Instrumented session tests build a `MediaController` against `NeutrodynePlaybackService` ([06 Testing](06-playback.md#testing)). Every Media3 bump re-runs the full playback suite (risk M3r).

### WorkManager and JobScheduler

`work-testing`: `WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build())`, then `getTestDriver(ctx)!!.setAllConstraintsMet(id)` / `setPeriodDelayMet(id)`; single workers with `TestListenableWorkerBuilder`. Job quotas beside an FGS (Android 16), UIDT behaviour and audio hardening cannot be reproduced in Robolectric; they run on GMD nightly ([07](07-downloads.md#instrumented-and-device-tests), [06](06-playback.md#testing)).

### Compose UI and screenshot tests

- Compose v2 test APIs (`androidx.compose.ui.test.junit4.v2.createComposeRule` / `createAndroidComposeRule`, `StandardTestDispatcher`); `mainClock.autoAdvance = false` for indeterminate progress.
- Every Compose test calls `enableAccessibilityChecks()` (`ui-test-junit4-accessibility`); rules and custom-action checks are 08's ([08 Automated checks](08-ui-ux.md#automated-checks)). Unverified: Google documents these checks for instrumented `AndroidComposeTestRule` tests; whether the Accessibility Test Framework reports every check under Robolectric (contrast needs a rendered frame, hence `GraphicsMode.NATIVE`) is checked in M1 with a deliberately broken component (a 20 dp clickable without a label must fail the Robolectric test). Fallback: Robolectric keeps the checks that do fire, and each screen gets one instrumented `<Screen>AccessibilityTest` in `:app/src/androidTest` that opens the screen in `MainActivity` (through the UI or a `neutrodyne://open/…` deep link, with `TestSeeder` data; test hosts below), run in the `instrumented` job (PLAN N4 requires instrumented checks either way; the E2E journeys provide them for the screens they visit).
- **Roborazzi**: each module with screenshot tests applies `alias(libs.plugins.roborazzi)` in its own build file (01 catalog rule 2: tooling plugins are not on the build-logic classpath) and declares `testImplementation(libs.roborazzi, libs.roborazzi.compose, libs.roborazzi.junit.rule)`; `roborazzi { outputDir.set(file("src/test/screenshots")) }`, `roborazzi.record.resizeScale=0.5` in `gradle.properties`. Determinism: `NeutrodyneTheme(dynamicColor = false)`, `TestClock`, fixed locale qualifier, `fakeImageLoader`, animations frozen.
- **Tiers** (decides 08 open question 15): `ScreenshotTier.PR` captures every subject in light and dark at font scale 1.0, LTR, plus one stress variant per subject (dark, 2.0, `ar-XB`). `ScreenshotTier.FULL` adds the remaining columns of [08's matrix](08-ui-ux.md#screenshot-matrix) (pure black, 1.5, 2.0 and RTL for every state; all widths and postures). PR CI verifies `PR`; nightly verifies `FULL`. All reference PNGs (both tiers) are committed. Budget: ≤ 800 images, ≤ 30 MB total; exceeding it requires dropping redundant variants, not Git LFS (keeps contributors' and Weblate's clones simple).

```kotlin
enum class ScreenshotTier { PR, FULL;
    companion object { val current = if (System.getProperty("neutrodyne.screenshotTier") == "full") FULL else PR }
}
fun assumeTier(required: ScreenshotTier) = assumeTrue(ScreenshotTier.current >= required)
```

- **Recording policy:** reference images are recorded only on Linux by the `record-screenshots.yml` workflow (font rasterisation differs on macOS and Windows); it uploads `screenshots-<sha>.zip` and the author applies it with `scripts/ci/apply-screenshots.sh <run-id>` (uses `gh run download`) and commits. PRs verify with `-Proborazzi.test.verify=true`; on failure the `_compare.png` files are uploaded as an artifact. Renovate groups Robolectric, Roborazzi and the Compose BOM so the re-record lands in the same PR.
- **Test hosts without `ui-test-manifest` in the published app** (decides 01 open question 14). `debug` is the published build type, so `ui-test-manifest`'s `ComponentActivity` may never be a `debugImplementation` ([01 Convention plugins](01-foundation.md#convention-plugins)). Library modules' Robolectric Compose tests (`createComposeRule()`) get that activity from `testImplementation(ui-test-manifest)`. Unverified: that AGP merges a `testImplementation` AAR's manifest into the Robolectric unit-test manifest (M1 check with the first feature screen test; fallback: a JUnit rule in `:core:testing` that registers `ComponentActivity` with Robolectric's `ShadowPackageManager.addOrUpdateActivity` before the compose rule starts). `:app`'s instrumented tests never use `createComposeRule()`: they use `createAndroidComposeRule<MainActivity>()` and reach screens through the UI or deep links, as the journeys already do, because an activity declared only in the test APK belongs to the test package and does not run in the app's process. `BackgroundStandInActivity` (E3) is fine there for exactly that reason: it only has to put the app in the background. `verifyManifestPermissions` rejects an exported test activity in the merged manifest, and `check-apk.sh --published` rejects ui-test-manifest's activity in a published APK ([Build-output checks](#build-output-checks)).
- Pseudo-locales `en-XA` and `ar-XB` come from `isPseudoLocalesEnabled = true` on the `debug` build type of the library modules that have screenshot tests, set by `neutrodyne.android.compose` (each module's unit tests merge that module's own debug resources; feature modules own the screens, so `:app` needs no pseudo-locale captures). In `:app` only the dev-tools switch enables them ([01 Dev-tools switch](01-foundation.md#dev-tools-switch)), because `debug` is the published build. A locale filter removes generated pseudo-locales unless they are listed ([pseudolocales](https://developer.android.com/guide/topics/resources/pseudolocales)), so `:app`'s `localeFilters` contain `en-rXA` and `ar-rXB` only in dev-tools builds ([Shipped locales and per-app language](#shipped-locales-and-per-app-language)). Unverified: that the libraries' generated pseudo-locale resources never reach the published APK through that filter; `check-apk.sh --published` asserts it. Unverified: Robolectric resolving `@Config(qualifiers = "en-rXA")` to aapt2's generated pseudo-locale resources (M2 check; fallback: capture only `ar` once a real Arabic translation exists, plus manual pseudo-locale review on device).

### Gradle Managed Devices

Configured in `neutrodyne.android.testing` for every Android module; only the five instrumented modules have device tests.

| Name | Device | API | Image | Groups | Used for |
|---|---|---|---|---|---|
| `api26` | Pixel 2 | 26 | GMD `aosp` (`android-26;default`) | `ci`, `nightly` | minSdk floor (PLAN M0 AC4); migrations; E2E |
| `api33` | Pixel 6 | 33 | GMD `aosp-atd` | `nightly` | `DataSyncWorkerTest` (07) |
| `api34` | Pixel 6 | 34 | GMD `aosp-atd` | `nightly` | first UIDT level; `PlaybackServiceTest` (06) |
| `api36` | Pixel 6 | 36 | GMD `aosp-atd` | `ci`, `nightly` | main device; `UidtDownloadTest` (07); E7 (debug and benchmark legs), E8 |
| `bench34` (in `:benchmark` only) | Pixel 6 | 34 | GMD `aosp` (full image: launcher, SystemUI, root) | — | system tests, Macrobenchmark dry runs |
| API 37 16 KB | — | 37 | `system-images;android-37.0;google_apis_ps16k;x86_64` via android-emulator-runner | nightly job `api37-16k` | Android 17 hardening, 16 KB page size (including CPython's libraries in `:ytx`), the `:app` suite on the published `x86_64` debug APK and E7 on `benchmark` (PLAN M11 AC7, M9 AC9) |

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
- **Benchmark-build runs:** `testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")` in `:app` (the AntennaPod pattern), with `testProguardFiles("proguard-test.pro")` on `benchmark` (keep rules for the androidTest APK only; the app's own rules stay in 01's `keepRules` source sets). The nightly benchmark leg passes `-PtestBuildType=benchmark`, so instrumented tests run against the R8-minified, non-debuggable APK, where AGP 9's unit tests cannot see R8 breakage. `benchmark` is signed with `neutrodyneDebug` like every build ([Gradle signing configuration](#gradle-signing-configuration)), so there is no special signing branch; `release.yml` never passes the property.
- **ABI splits on emulators:** every device above is `x86_64`, so the managed-device and `connected*` tasks must install the `x86_64` split of `:app` (with the engine). Unverified: that AGP 9.4 picks the split matching the device ABI for test installs (M0 check; fallback: ABI splits only when the Gradle property `neutrodyne.abiSplits=true` is set, which `release.yml`, the `assemble` job and the nightly jobs that inspect published APKs pass, so test builds stay single-APK).
- Instrumented hygiene: orchestrator with `clearPackageData`. Locally this wipes and then (through the connected task's uninstall) removes the Neutrodyne on the attached phone, because `debug` and `benchmark` carry the published app's ID: local runs use the dev-tools switch, an emulator or a dedicated test device, never a phone whose Neutrodyne holds real data ([01 Dev-tools switch](01-foundation.md#dev-tools-switch); `CONTRIBUTING.md`'s testing section repeats it). CI's device tests never run a dev-tools build, so LeakCanary and StrictMode penalties never interfere there; locally, 01's `DevToolsInitializer` turns LeakCanary's heap dumps off while the test property below is set. ACRA is **on** in the published build ([D62](../PLAN.md#3-key-decisions)), so `:app`'s instrumented tests switch it off: the runner `NeutrodyneTestRunner` (`:app/src/androidTest`, an `AndroidJUnitRunner`) sets the system property `neutrodyne.instrumentedTest=true` in `newApplication` before calling `super`, which instantiates the application and calls `attach`, and with it `attachBaseContext` ([AOSP Instrumentation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Instrumentation.java)); 01's call site skips `installAcra` while the property is set ([ACRA configuration](#acra-configuration)). **One exception, `YtxIsolationTest`** ([04 Testing](04-youtube.md#testing), PLAN M9 AC5): its "no ACRA dialog" assertion needs ACRA as users have it, so its `@BeforeClass` calls `installAcra(application, mailTo = "ytx-isolation-test@invalid")` itself on the main thread — a test-only, non-empty address, so the assertion does not depend on PO-10 — and after each `:ytx` failure asserts that ACRA's `ACRA-unapproved` report directory ([ACRA `ReportLocator`](https://github.com/ACRA/acra/blob/master/acra-core/src/main/java/org/acra/file/ReportLocator.kt)) stays empty and that no `:acra` process runs. Unverified: that ACRA installed after `Application.onCreate` (its setup guide installs it in `attachBaseContext`, [ACRA BasicSetup](https://github.com/ACRA/acra/wiki/BasicSetup)) catches a later main-process crash fully (M9a check by hand: after a late installation, a deliberate main-process crash leaves a report file; fallback: the assertion moves to `:benchmark`'s out-of-process system tests, run in the nightly `system-tests` job against a build with `-Pneutrodyne.acraMailto=ytx-isolation-test@invalid`). That ACRA is never installed in `:ytx` itself is `YtxProcessStartTest`'s probe (01), which is vacuous until PO-10 names the mailbox — until then no process installs ACRA. The property is inert unless an instrumentation sets it inside the app's process (PLAN 7.2's test-hook rule), and R8 cannot remove a system-property read, so `benchmark` needs no keep rule. Unverified: that the orchestrator calls `newApplication` in every test process (M0 check with a deliberate crash in a test). Library modules' device tests run no `NeutrodyneApplication`, so ACRA never starts there; `:benchmark`'s out-of-process tests leave ACRA on, and a crash fails them anyway.

### Out-of-process system tests

Instrumented tests run inside the app's process, so killing the process (`am kill`, ProcessDeathResumeTest) or reinstalling the app kills the test. Such tests live in `:benchmark` (`com.android.test`, self-instrumenting, `targetProjectPath = ":app"`), drive the app with UI Automator 2.4.0 and `UiAutomation.executeShellCommand`, and run on `bench34`. Consequently `:benchmark` is created in **M6** (system tests) and gains Macrobenchmarks in M10 ([Macrobenchmark and profiles](#macrobenchmark-and-profiles); no baseline-profile generator, because profiles do not apply to the debuggable published build). Compose nodes are found by resource ID through `Modifier.semantics { testTagsAsResourceId = true }` on `NeutrodyneRoot` and 08's test tags ([08 Performance journeys](08-ui-ux.md#performance-journeys)). System tests run in `:benchmark`'s `debug` variant against `:app`'s published `debug` variant (package `ch.lkmc.neutrodyne`, the `x86_64` split); Macrobenchmarks run in its `benchmark` variant against `:app`'s non-debuggable `benchmark` build type (same package). System tests create their data through the UI (Library → "Add by URL"), and serve feeds and enclosures from a MockWebServer inside the `:benchmark` process on `127.0.0.1`, which the app reaches over loopback.

### Recorded responses

- No PR job contacts YouTube, Apple, fyyd, Podcast Index or GitHub's release endpoints. Directory JSON (03), channel pages and oEmbed (04) and the engine's InnerTube traffic (04's `RecordingRH`, driven by `scripts/youtube/record-responses.sh`) are recorded on a developer machine, scrubbed per [04 Recorded responses](04-youtube.md#recorded-responses) (`ip=`, signatures, cookies, visitor data, `expire`), and committed. The update check's GitHub responses (`latest/download` redirects, manifests) are hand-written fixtures served by MockWebServer.
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
  cron["nightly.yml 02:17 UTC"] --> nj["instrumented-full debug and benchmark legs, api37-16k, system-tests, no-engine-build, dev-tools-build, repro report-only, bmgr, screenshots-full, mutation-full, canaries"]
  tag["push tag v*"] --> rel["release.yml"]
  rel --> ghr["immutable normal GitHub release"]
  ghr --> upd["update checks of installed apps"]
  ghr --> obt["Obtainium"]
  six["schedule every 6 h"] --> can["engine-canary.yml"]
  can --> pages["GitHub Pages approved engine manifest"]
  pages --> eng["apps with engine updates on"]
```

### `ci.yml`

Triggers: `pull_request`, `push` to `main` and `release/*`, `workflow_dispatch`. `permissions: contents: read` at the top; `concurrency: group ci-${{ github.ref }}`, `cancel-in-progress` for PRs only. Every job: `actions/checkout` (`fetch-depth: 0` in `static` for tag comparisons), `actions/setup-java` (Temurin 21), `actions/setup-python` with the Python minor version Chaquopy packages (3.14, fallback 3.13 per [01 S7](01-foundation.md#s7-chaquopy-under-agp-941); Chaquopy compiles `.pyc` at build time with it, and `shimTest` runs on it), `gradle/actions/setup-gradle` with `cache-provider: basic` (MIT; the default "enhanced" cache is a proprietary component) and `cache-read-only` except on `main`.

| Job | Timeout | Runs | Blocking |
|---|---|---|---|
| `static` | 30 min | `./gradlew spotlessCheck :app:lintDebug :app:assertModuleGraph :app:licenseeDebug :app:verifyDependencyPolicy :app:verifyManifestPermissions checkSpdxHeaders checkBannedApis :youtube:ytdlp:checkPythonLicences :youtube:ytdlp:verifyBundledYtDlp --continue`; KGP assertion `./gradlew -q :app:buildEnvironment \| grep -E 'kotlin-gradle-plugin:.*2\.4\.20'` (PLAN M0 AC3); `scripts/ci/check-frozen-schemas.sh`; SARIF upload (`security-events: write`); `./gradlew detekt` with `continue-on-error: true` | yes (detekt no) |
| `unit` | 45 min | `./gradlew test mutationTest :youtube:ytdlp:shimTest -Proborazzi.test.verify=true --continue`; Room schema drift: `test -z "$(git status --porcelain -- core/database/schemas)"` (KSP regenerated the schema while compiling); upload `**/build/reports/tests/` and Roborazzi `_compare.png` on failure; cache `~/.m2/repository/org/robolectric` keyed `robolectric-4.17-sdk36` | yes |
| `assemble` | 30 min | `./gradlew assembleDebug assembleBenchmark` (the published build type and the R8-minified `benchmark`, which keeps the R8 configuration honest; both signed with the committed `neutrodyneDebug` key, so a PR build is signed exactly like a release and needs no secret; each produces the `arm64-v8a`, `x86_64` and `armeabi-v7a` APKs and no universal APK); `scripts/ci/check-apk.sh --published` on the debug APKs ([Build-output checks](#build-output-checks)); upload `debug-apks` (14 days; they are signed with the same committed public key as every release, so they install over a real Neutrodyne: test artifacts only, never offered to users, risk P10) | yes |
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

`schedule: cron "17 2 * * *"` plus `workflow_dispatch` with input `scope` (`full`, default; `youtube-smoke` = only the two legs of `instrumented-full` on `api36` — the published debug APK and the minified `benchmark` APK, in parallel — filtered to `YouTubeReleaseSmokeTest` and `SmokeTest`, ≈ 12 min, used by the APK hotfix path). A failing job runs `scripts/ci/report-nightly.sh <job>`, which opens or updates one issue per job (label `nightly-failure`, `permissions: issues: write`) and closes it after the next green run.

| Job | From | Runs | Blocks a release? |
|---|---|---|---|
| `instrumented-full` | M0 | two matrix legs with `-Pneutrodyne.testScope=nightly --max-workers=2 -Pandroid.testInstrumentationRunnerArguments.ytxReplay=true`: **debug** `./gradlew nightlyGroupDebugAndroidTest` (every module's suite on the published build type, E7 included); **benchmark** `./gradlew -PtestBuildType=benchmark :app:nightlyGroupBenchmarkAndroidTest` (the minified, non-debuggable `:app` suite including E7 through `:ytx`; library modules have no benchmark test variant) | yes (red nightly ⇒ no tag) |
| `api37-16k` | M0 | android-emulator-runner (`api-level: 37.0`, `target: google_apis_ps16k`, `arch: x86_64`, [GMD notes](#gradle-managed-devices)): assert `adb shell getconf PAGE_SIZE` = 16384; `./gradlew --max-workers=2 -Pneutrodyne.testScope=nightly -Pandroid.testInstrumentationRunnerArguments.ytxReplay=true connectedDebugAndroidTest` — the whole `:app` suite on the published `x86_64` debug APK (E0 including `selftest` in `:ytx` while S7 is go, journeys, `YtxProcessStartTest`, 06's `PlaybackServiceTest`, whose hardening cases switch `cmd audio set-enable-hardening throw` on through `UiAutomation.executeShellCommand` and off in `@After`, so other tests keep the default muting behaviour) plus the library modules' suites (migrations on both drivers); then E7 on the `benchmark` APK (`-PtestBuildType=benchmark :app:connectedBenchmarkAndroidTest`, filtered to `YouTubeReleaseSmokeTest`, PLAN M9 AC9); `zipalign -c -P 16 -v 4` and `check-apk.sh --alignment-only` on the published debug `x86_64` APK (PLAN M11 AC7) | yes |
| `system-tests` | M6 | `:benchmark` system tests on `bench34` (E10) | yes |
| `no-engine-build` | M9a | the emergency build without the engine ([01 Emergency build without the engine](01-foundation.md#emergency-build-without-the-engine)): `./gradlew assembleDebug -Pneutrodyne.youtubeEngine=false`, `scripts/ci/check-apk.sh --no-engine` (the published checks plus the no-engine content rules), and 01's Hilt graph test with the switch off (`./gradlew -Pneutrodyne.youtubeEngine=false :app:testDebugUnitTest --tests '*HiltGraph*'`), so the same-day emergency release of risk L1 never rots | yes |
| `dev-tools-build` | M0 | `./gradlew assembleDebug :app:testDebugUnitTest -Pneutrodyne.devTools=true` ([01 Dev-tools switch](01-foundation.md#dev-tools-switch)): keeps LeakCanary, StrictMode penalties and the `app/src/devTools/` code compiling; `aapt2 dump badging` shows package `ch.lkmc.neutrodyne.dev` (PLAN M0 AC9). The APKs are discarded, never uploaded | no |
| `benchmark-dryrun` | M10 | Macrobenchmark journeys against `:app`'s `benchmark` build type with `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dryRunMode.enable=true` (catches broken journeys; timings are meaningless on emulators) | no |
| `repro` | M0 | [two signed builds and a diff](#nightly-reproducibility-job); report-only permanently ([D79](../PLAN.md#3-key-decisions)) | no |
| `bmgr` | M3 | android-emulator-runner API 29 (`backup_rules.xml` path) and API 36 (`data_extraction_rules.xml`), image `default`: `scripts/ci/bmgr-check.sh ch.lkmc.neutrodyne` on the published `x86_64` debug APK (05's procedure, with its shell-only `SnapshotNowReceiver` as the snapshot trigger, [05 Testing with bmgr](05-groups-opml-backup.md#testing-with-bmgr), plus 07's "no `Podcasts/`" assertion). Needs uninstall/reinstall, which an in-process instrumented test cannot do | yes |
| `screenshots-full` | M10 | `./gradlew test --tests '*Screenshot*' -PscreenshotTier=full -Proborazzi.test.verify=true` | yes |
| `mutation-full` | M1 | `./gradlew mutationTest -PmutationIterations=1000` | yes |
| `live-canary` | when 03 ships `feeds/canary/feeds.txt` (M11 at the latest) | `./gradlew :feeds:liveCanary` | no |
| `youtube-canary` | M9a (04) | 04's subscribe-resolve-chunk smoke through the shim on a host CPython (live; runner IPs are bot-checked, so it is noisy by design) | no |
| `engine-nightly-canary` | M9a (04) | `shimTest` with yt-dlp's latest nightly build (`yt-dlp/yt-dlp-nightly-builds`, signature checked with the same pinned key) in place of the bundled version: the early warning for plugin or internal API drift (risk M8r). Informational; it never approves anything ([D76](../PLAN.md#3-key-decisions): nightlies are never shipped) | no |

The former `mirror` job was removed with [PO-34](../PLAN.md#48-further-product-owner-decisions) ([GitHub takedown](#github-takedown)).

### `release.yml`

Trigger: `push: tags: ['v*']`. One job `release` in GitHub environment `release` (required reviewer = a maintainer; the environment holds no signing secret — the APK key is committed, [Debug keystore](#debug-keystore) — and only `PODCASTINDEX_*` once Podcast Index grants written permission) with `permissions: contents: write, id-token: write, attestations: write, artifact-metadata: write` (the last three for `actions/attest`); `setup-gradle` with `cache-disabled: true` (no cache-poisoning surface for published builds). The build runs inside the same pinned container and script as the [repro job](#nightly-reproducibility-job), so the published APKs and the nightly check share one toolchain: `python:3.14-slim-trixie@sha256:<digest>` (Docker's official Python image on Debian 13, [docker-library/python](https://github.com/docker-library/python/tree/master/3.14); Debian trixie's own `python3` is 3.13, [Debian](https://packages.debian.org/trixie/python3), and Chaquopy's build-time `.pyc` compilation needs the packaged minor version, [01 S7](01-foundation.md#s7-chaquopy-under-agp-941)) plus Debian's `openjdk-21-jdk-headless` ([Debian](https://packages.debian.org/trixie/openjdk-21-jdk-headless)). The image digest is Renovate-managed and changes only in a reviewed PR.

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
  R->>R: verify-tag.sh preconditions and make_latest
  R->>C: repro-build.sh assembleDebug
  C-->>R: three debug ABI APKs signed with the committed key
  R->>R: apksigner schemes and certificate, aapt2, zipalign 16 KB, check-apk.sh --published
  R->>R: rename, make-update-json.sh, check-update-json.sh, SHA256SUMS
  R->>G: actions/attest provenance for APKs, update manifest, SHA256SUMS
  R->>G: draft release, upload every asset, publish as immutable normal release
  R->>G: gh release verify and verify-asset
  G-->>M: update checks of installed apps and Obtainium find the release
```

Steps, in order (target: tag → published release in < 30 min, N11 and PLAN M11 AC2):

1. `scripts/ci/verify-tag.sh`: the tag equals `v` + `neutrodyne.versionName` and carries no suffix (`^v[0-9]+\.[0-9]+\.[0-9]+$`; `versionCode` ends in S = 95, [Version scheme](#version-scheme)); the tagged commit is on `main` or a `release/*` branch ([hotfix branches](#scriptsreleasesh)); `changelogs/<versionCode>.txt` exists in it ([Changelogs and release notes](#changelogs-and-release-notes)); and **either** `ci.yml` concluded `success` for the tagged commit **or** the tagged commit is a `release.sh` commit — exactly one parent, `ci.yml` `success` on that parent (`gh api repos/{repo}/commits/{sha}/check-runs`), and `git diff --numstat HEAD^ HEAD` showing only `gradle.properties` with two changed lines, both matching `^neutrodyne\.version(Name|Code)=`. The second branch exists because the release commit's own `ci.yml` run starts at the same moment as `release.yml`; waiting for it would cost the 15 minutes the N11 budget cannot spare, and a version-line change cannot alter what CI verified. It also decides `make_latest`: true when the tag's `versionCode` is higher than that of the current latest release (`gh release view --json tagName`), so a late patch on an older line ([hotfix](#scriptsreleasesh)) can never become "latest". When no published, non-draft release exists yet (`v0.1.0`), `gh release view` exits non-zero with "release not found" (gh's `ErrReleaseNotFound` on the 404 of the latest-release endpoint, [gh source](https://github.com/cli/cli/blob/trunk/pkg/cmd/release/shared/fetch.go)); the script then sets `make_latest = true`, and any other error fails the run, so the first release is "latest" and step 9's `curl` succeeds (PLAN M0 AC7). It prints `neutrodyne.youtubeEngine` from the tagged `gradle.properties` (an emergency release commits `false` there, [01](01-foundation.md#emergency-build-without-the-engine)).
2. `scripts/ci/repro-build.sh assembleDebug`: nothing to decode, because the container signs with the committed keystore through `neutrodyneDebug` ([Gradle signing configuration](#gradle-signing-configuration)); never `-Pneutrodyne.devTools`. After Podcast Index's written permission (PO-3 option A) it also passes `-Pneutrodyne.podcastIndexKey/Secret` from the environment's secrets. Nothing else from the runner environment enters the container. Output: `app-arm64-v8a-debug.apk`, `app-x86_64-debug.apk`, `app-armeabi-v7a-debug.apk` (AGP names split outputs `modulename-ABI-buildvariant.apk`, [configure APK splits](https://developer.android.com/build/configure-apk-splits); Unverified for AGP 9).
3. Per APK: `apksigner verify --verbose --print-certs --min-sdk-version 26` must report the v1 scheme false and v2 and v3 true, and the signer's SHA-256 must equal the output of `scripts/ci/debug-cert-sha256.sh` (the committed keystore's certificate, [apksigner](https://developer.android.com/tools/apksigner)); `aapt2 dump badging` shows `package: name='ch.lkmc.neutrodyne'`, the tag's `versionCode` and `versionName`, and `application-debuggable` ([MASTG-TECH-0150](https://mas.owasp.org/MASTG/techniques/android/MASTG-TECH-0150/)); `aapt2 dump xmltree --file AndroidManifest.xml` shows no `android:testOnly` (Android Studio adds it to builds it launches with Run, [application element](https://developer.android.com/guide/topics/manifest/application-element); Unverified that a command-line `assembleDebug` never does, PLAN M0 AC7 checks it); `zipalign -c -P 16 -v 4`; `check-apk.sh --published` (sizes per PB12/PB13, content including the absence of developer tooling, alignment of the `.so` files inside Chaquopy's asset zips; exactly three APKs, no universal APK).
4. Rename to the [asset names](#release-assets): `neutrodyne-{v}-{abi}.apk`.
5. `scripts/ci/make-update-json.sh` writes `neutrodyne-update.json` ([Update manifest](#update-manifest)); `scripts/ci/check-update-json.sh` validates it against the files, the tag, `gradle.properties`, `neutrodyne.repoUrl` and the changelog. A release without a valid manifest is never published: the update check reads `releases/latest/download/neutrodyne-update.json`, which would 404.
6. `SHA256SUMS` (`sha256sum` format) over the three APKs and `neutrodyne-update.json`; the release body from the [template](#release-body).
7. `actions/attest` (v4, SHA-pinned, [actions/attest](https://github.com/actions/attest)) with `subject-path` listing the three APKs, `neutrodyne-update.json` and `SHA256SUMS`: SLSA build-provenance attestations, verifiable with `gh attestation verify` ([artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations)).
8. `gh release create vX.Y.Z --draft --verify-tag --title "Neutrodyne X.Y.Z" --notes-file body.md` (never `--prerelease`: every tag, tester builds included, is a normal release, [Tester builds](#tester-builds)) → `gh release upload` of all five assets → `gh release edit vX.Y.Z --draft=false --latest=<make_latest>`. Immutable releases are on in the repository settings, so publishing locks the assets and the tag and creates GitHub's release attestation ([immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases)). The `gh` CLI preinstalled on the runner replaces a third-party release action.
9. `gh release verify vX.Y.Z` and `gh release verify-asset vX.Y.Z <asset>` for every asset ([gh release verify-asset](https://cli.github.com/manual/gh_release_verify-asset)); when `make_latest` is true, `curl -fsSL {repoUrl}/releases/latest/download/neutrodyne-update.json` must succeed and name the tag's `versionCode`.

A failure before step 8's publish leaves at most a draft: the maintainer deletes it and re-runs the workflow on the same tag if the cause was outside the source (Unverified: that deleting a never-published draft keeps the tag name usable under immutable releases; GitHub documents that a deleted *published* immutable release's tag name can never be reused). A problem found after publishing is fixed forward with a new PATCH release; nothing published is ever edited except its notes. Removed with the store channels ([D79](../PLAN.md#3-key-decisions)): the Play publishing step, the GPL corresponding-source bundle and the release-blocking reproducibility job. Removed 2026-10-05 (PO-33–PO-35): decoding a keystore secret, the R8 mapping asset, the pre-release flag and the Codeberg push.

### `engine-canary.yml`

Serves R3.9, N11, N12; mitigates risks M1r, M7r, M8r. Delivered in M9b. Honours [D76](../PLAN.md#3-key-decisions), [PO-32](../PLAN.md#48-further-product-owner-decisions). The workflow — schedule, jobs, permissions, environments, signing and deployment — is owned here; **what** it verifies and tests is [04 Engine canary](04-youtube.md#engine-canary), and the manifest format and the app-side checks are [04 Trust chain](04-youtube.md#trust-chain).

Triggers: `schedule: cron "23 */6 * * *"` (every 6 h) and `workflow_dispatch` with inputs `tag` (approve a specific yt-dlp stable tag, e.g. after re-recording, 04's runbook path 2), `revoke` (a version to list in `revoked`; dispatched together with `tag` = the last good version, 04's revocation) and `bootstrap` (the very first run, when no manifest exists yet: `sequence` starts at 1). `concurrency: group: engine-canary, cancel-in-progress: false`, so two runs never race on `sequence`. Top-level `permissions: contents: read`.

| Job | Environment, permissions | Steps |
|---|---|---|
| `test` | none; `contents: read`, `issues: write` | 1. Read the current approved manifest from `neutrodyne.engineManifestUrl` (`gradle.properties`, with a cache-busting query) and verify its Ed25519 signature against the committed `youtube/ytdlp/keys/engine-manifest-ed25519.pub`; keep `sequence`, `revoked` and the approved version. 2. Detect the candidate: the `tag` input, else the tag in the `Location` header of `https://github.com/yt-dlp/yt-dlp/releases/latest` (no REST API); stop green when it equals the approved version and no `revoke` input is given. 3. In the job's checkout (never committed): `scripts/engine/bump-ytdlp.sh <tag>` downloads `yt-dlp`, `SHA2-256SUMS` and `SHA2-256SUMS.sig` from `github.com/yt-dlp/yt-dlp/releases/download/<tag>/`, applies 04's size and zip-content rules and updates `bundled.json` and the lockfile's yt-dlp entry exactly as a bump PR would; then `./gradlew :youtube:ytdlp:verifyBundledYtDlp :youtube:ytdlp:checkPythonLicences :youtube:ytdlp:shimTest` and `:youtube:ytdlp:testDebugUnitTest --tests '*UpstreamReleaseVerifierTest*'` (signature against the pinned yt-dlp key with build-logic's and the app's OpenPGP code, SHA-256, `ORIGIN`, top-level packages, the self-test's API probe, and every recorded scenario through `ReplayRH` in contract mode with `-Preplay=contract` — the blocking gate of [04 Engine canary](04-youtube.md#engine-canary)); then `shimTest` in strict mode as a non-blocking report, whose mismatches open or update the `engine-canary` issue asking for a re-record without failing the job. 4. Compare the fingerprint of `https://github.com/yt-dlp/yt-dlp/blob/master/public.key` with the pinned one (04 open question 19); a change opens an issue but does not block. 5. On green, upload the candidate's `version`, `tag`, `sha256` and `ejsVersion` as a job output; on red, `scripts/ci/report-nightly.sh engine-canary --label engine-canary` opens or updates one issue with the failing scenarios |
| `approve` (needs `test`, only on green or with `revoke`) | `engine-approval` (deployment branch `main` only, no reviewer; secret `NEUTRODYNE_ENGINE_MANIFEST_KEY`); `contents: read` | `scripts/engine/make-engine-manifest.sh` writes `engine/ytdlp-approved.json` (`sequence + 1`, `issuedAt`, the candidate, `shimApi` = the range `shimTest` ran, `revoked` carried over plus the `revoke` input); `scripts/engine/sign-engine-manifest.sh` signs the exact bytes with the Ed25519 key (`openssl pkeyutl -sign -rawin`, [OpenSSL pkeyutl](https://docs.openssl.org/3.0/man1/openssl-pkeyutl/)) into `ytdlp-approved.json.sig` (base64) and verifies the result against the committed public key before anything leaves the job (a key mismatch fails here, not on users' devices); `actions/upload-pages-artifact` with the `engine/` directory. The key is decoded into `$RUNNER_TEMP` and deleted in an `always()` step |
| `deploy` (needs `approve`) | `github-pages`; `pages: write`, `id-token: write` | `actions/deploy-pages` ([deploy-pages](https://github.com/actions/deploy-pages)); then poll the public URL until it serves the new `sequence` (≤ 15 min, Unverified Pages cache lifetime, 04 open question 18) and record the approval latency (upstream `published_at` → served) in the job summary |

Heartbeat: `approve` runs after **every** green `test` run, not only with a new candidate; without one it re-publishes the unchanged manifest together with `engine/ytdlp-heartbeat.json` (`{"kind": "heartbeat", "lastRunAt": "…Z", "sequence": <current manifest sequence>}`) and its Ed25519 signature `ytdlp-heartbeat.json.sig`. The app fetches it with the manifest (04 [Update flow](04-youtube.md#update-flow)); a validly signed heartbeat older than 48 h puts "Engine approvals stale since {date}" into Settings › YouTube's status line and diagnostics, and never blocks anything. A canary stopped by GitHub's 60-day rule or a broken secret is thereby visible to users and maintainers: the stale-heartbeat line in Settings › YouTube and in diagnostics is the alarm. There is no mirror to host a second one ([PO-34](../PLAN.md#48-further-product-owner-decisions)); a maintainer's own outside check of the served heartbeat is optional and not planned (Unverified which service would host it).

Rules: the signing key never exists in a job that runs fetched code (`test` runs yt-dlp's code in `shimTest`; `approve` only writes and signs JSON); a Pages deployment replaces the whole site, so the artifact always contains the complete `engine/` directory (Unverified for partial uploads; nothing else is hosted on the Pages site); the repository's Pages source is "GitHub Actions" (M9b setting). The canary never approves a nightly or a version below the APK-bundled one, and it never commits to the repository: moving the bundled version stays a reviewed PR with `bump-ytdlp.sh` ([Review rules per group](#review-rules-per-group)). Time budget: upstream stable release → approved manifest served ≤ 6 h (N11; [Time budgets](#time-budgets)).

Key custody for `NEUTRODYNE_ENGINE_MANIFEST_KEY`, the project's only private signing key (the APK key is public and committed, [Debug keystore](#debug-keystore)): generated once in M9b by a maintainer (`openssl genpkey -algorithm ED25519`, [OpenSSL genpkey](https://docs.openssl.org/3.0/man1/openssl-genpkey/)), stored only as that secret of the `engine-approval` environment, and the local file deleted — no ceremony, no offline copy ([04 Security notes](04-youtube.md#security-notes)). Its public key is committed in slot 1 of `engine-manifest-ed25519.pub`; slot 2 is the rotation slot and stays empty in normal operation.

- **Rotation** (planned, or at once after a suspected leak): generate the next key the same way and store it as `NEUTRODYNE_ENGINE_MANIFEST_KEY_NEXT` in `engine-approval`; ship its public half in slot 2 of the next APK. After a leak, switch at once: `approve` signs with the next key from then on, and APKs that do not pin it yet reject new manifests and keep their last approved engine (fail safe). Otherwise switch once the APK with slot 2 has been the latest release for 30 days. Switching means moving the next key into `NEUTRODYNE_ENGINE_MANIFEST_KEY` and deleting `…_NEXT`; a later APK moves it to slot 1 and empties slot 2.
- **Loss** (the secret deleted or unreadable): there is no copy, so a new key is generated and pinned in the next APK ([PLAN N12](../PLAN.md#22-non-functional-requirements)). Until users install that APK, their engines stay on the last approved version; "upstream stable" and "Reset to bundled" keep working.
- A leaked manifest key can only choose among genuine upstream-signed yt-dlp releases at or above the bundled version (risk M7r). A spoofed APK signed with the public APK key replaces the pinned keys along with the whole app, which no engine check can prevent (risk P10).

### Helper workflows

| Workflow | Trigger | Does |
|---|---|---|
| `record-screenshots.yml` | `workflow_dispatch` (input: branch, tier) | `./gradlew recordRoborazziDebug -PscreenshotTier=…` on `ubuntu-24.04`; uploads `screenshots-<sha>.zip` |
| `keepalive.yml` (M0) | `schedule: cron "41 4 1 * *"` (monthly) and `workflow_dispatch` | `gh workflow enable` for `nightly.yml`, `engine-canary.yml` (from M9b) and itself (`permissions: actions: write`). In a public repository GitHub disables scheduled workflows "when no repository activity has occurred in 60 days" ([disable and enable workflows](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows)), and a disabled canary would stop approving engine fixes without any failure issue. Unverified: which events count as activity and whether re-enabling resets the clock; the engine heartbeat ([engine-canary.yml](#engine-canaryyml)) detects a stopped canary either way |

Removed 2026-10-05: `baseline-profile.yml` (profiles are deferred, [Macrobenchmark and profiles](#macrobenchmark-and-profiles)).

Pushing commits from workflows is deliberately avoided: pushes made with `GITHUB_TOKEN` do not trigger new workflow runs, so a bot commit would leave the PR without required checks.

### CI scripts

| Script | Purpose |
|---|---|
| `scripts/ci/check-apk.sh [--published\|--no-engine\|--alignment-only] <apk…>` | per-ABI size budgets, 16 KB alignment including Chaquopy's asset-extracted `.so` files, forbidden content (developer tooling included), allowed native libraries and Python packages, manifest facts of a published build, locale config, metadata hygiene ([Build-output checks](#build-output-checks)) |
| `scripts/ci/debug-cert-sha256.sh [--colons]` | prints the SHA-256 of the certificate in the committed `signing/neutrodyne-debug.keystore` (`keytool -list -v … -storepass android -alias androiddebugkey`), lower-case hex or colon-separated; the expected signer for `release.yml` step 3 and `check-apk.sh --published` ([Debug keystore](#debug-keystore)) |
| `scripts/ci/make-update-json.sh` | writes `neutrodyne-update.json` from `gradle.properties`, the tag, `changelogs/<versionCode>.txt`, `neutrodyne.repoUrl` and the renamed APKs ([Update manifest](#update-manifest)) |
| `scripts/ci/check-update-json.sh` | validates the manifest before the draft is published ([Update manifest](#update-manifest)); also run in PRs that touch the release scripts |
| `scripts/ci/check-frozen-schemas.sh` | for every `N.json` that exists at the newest `v*` tag, `git diff --exit-code <tag> -- <file>` ([02 Schema export and versioning](02-data-model.md#schema-export-and-versioning)) |
| `scripts/ci/repro-build.sh [--path P] [--cpus N] [--umask U] <gradle args…>` | container build used by `repro` and `release.yml`; every build in it is signed with the committed keystore, so there is no signing option and no secret to mount |
| `scripts/ci/install-android-sdk.sh` | pinned cmdline-tools (version + SHA-256), `platforms;android-37`, `build-tools;36.0.0` |
| `scripts/ci/bmgr-check.sh <package>` | 05's `bmgr` procedure and assertions (package `ch.lkmc.neutrodyne`, the published build) |
| `scripts/ci/report-nightly.sh <job> [--label L]` | issue per failing nightly or canary job |
| `scripts/ci/apply-screenshots.sh <run-id>` | download and unpack recorded screenshots locally |
| `scripts/ci/verify-tag.sh` | release preconditions (no version suffix) and the `make_latest` decision, true when no published release exists yet ("release not found") ([release.yml](#releaseyml) step 1) |
| `scripts/ci/network-capture.sh` | v1.0 gate network capture ([v1.0 gate](#v10-gate)); maintainer-run, needs internet |
| `scripts/ci/start-emulator.sh` | fallback emulator start for API 26 / API 37 if android-emulator-runner or GMD cannot ([Gradle Managed Devices](#gradle-managed-devices)) |

### Hardening

- Every `uses:` is pinned by commit SHA; Renovate's `helpers:pinGitHubActionDigests` keeps the pins current.
- Never `pull_request_target`; fork PRs get no secrets. No build needs one: every build, PR builds included, is signed with the committed public key ([Debug keystore](#debug-keystore)).
- Repository rulesets: `main` and `release/*` require a PR and the checks `static`, `unit`, `assemble`; linear history; no force pushes; maintainers may bypass only to push the release commit created by `release.sh`. Tag ruleset: only maintainers create or delete `v*` tags. With a public signing key, the tag ruleset, the `release` environment's required reviewer and immutable releases are what keep an unreviewed build from becoming a release.
- Repository settings (M0): immutable releases on; environments `release` (required reviewer; no signing secret) and, from M9b, `engine-approval` (deployment branch `main` only) and `github-pages`; Pages source "GitHub Actions" (M9b); GitHub secret scanning with push protection on (the committed keystore may need a documented bypass, [Debug keystore](#debug-keystore)); private vulnerability reporting on ([SECURITY.md](#security-reporting)).

| Secret | Scope | Used by |
|---|---|---|
| `PODCASTINDEX_KEY`, `PODCASTINDEX_SECRET` | environment `release` | only after Podcast Index grants written permission (PO-3 option A): passed as `-Pneutrodyne.podcastIndexKey/Secret` to `assembleDebug` in `release.yml` |
| `NEUTRODYNE_ENGINE_MANIFEST_KEY` (and `NEUTRODYNE_ENGINE_MANIFEST_KEY_NEXT` only during a rotation) | environment `engine-approval` | signing `ytdlp-approved.json` and the heartbeat ([engine-canary.yml](#engine-canaryyml)) |

No other secret exists. PR, nightly and canary builds read no secret; the only build-time secret that may ever enter an APK is the Podcast Index key, and only through `release.yml` after written permission ([D26](../PLAN.md#3-key-decisions)). Removed 2026-10-05 (PO-34, PO-35): the keystore secrets `NEUTRODYNE_KEYSTORE_B64`, `NEUTRODYNE_KEYSTORE_PASSWORD`, `NEUTRODYNE_KEY_ALIAS` and `NEUTRODYNE_KEY_PASSWORD`, the variables `NEUTRODYNE_CERT_SHA256` and `NEUTRODYNE_PREVIOUS_CERT_SHA256`, and `CODEBERG_MIRROR_KEY`.

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

`scripts/ci/check-apk.sh` runs on every `assemble` and in `release.yml` (`--published`, on the debug APKs), in the nightly `no-engine-build` (`--no-engine`: the published checks plus the no-engine rules) and in `api37-16k` (`--alignment-only`). Inputs: the APKs, `app/policy/locales.txt`, `youtube/ytdlp/python-components.lock` (01: which native libraries and top-level Python packages an APK may contain) and the expected certificate from `debug-cert-sha256.sh`. It also reads `neutrodyne.youtubeEngine` from `gradle.properties`, so an emergency release is checked with the no-engine rules.

1. **Size and set** (blocking): exactly three published APKs (`arm64-v8a`, `x86_64`, `armeabi-v7a`) and no universal APK ([D77](../PLAN.md#3-key-decisions)); `arm64-v8a` and `x86_64` < 60 MB each (PB12), `armeabi-v7a` < 50 MB (PB13) — Unverified estimates for unminified debug builds until S7 measures them in M0; a miss goes to the PO (PLAN M0 AC1). Sizes go to the job summary and a nightly artifact for trends; the engine's share is printed separately (Chaquopy's `jniLibs` and assets, the yt-dlp asset), and so is the size of the `benchmark` APKs for comparison.
2. **16 KB** ([16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)): `zipalign -c -P 16 -v 4` on every published APK (the uncompressed `.so` files in `lib/<abi>/`: `sqlite-bundled` ([01 S6](01-foundation.md#s6-sqlite-bundled-16-kb-alignment-and-size)), Chaquopy's `libpython3.14.so`, `libcrypto`, `libssl`, `libsqlite3`, `libc++_shared`, and quickjs-kt's library when the JS provider ships); and `llvm-readelf -lW` on every ELF file in `lib/` and inside Chaquopy's asset zips (the `lib-dynload` extension modules, which Chaquopy extracts at run time and zipalign never sees): every `LOAD` segment aligned to ≥ 0x4000. Unverified: the `llvm-readelf` binary name on the runner image (fallback: binutils `readelf -lW`, which reads program headers of any ELF architecture). With legacy native packaging (S7) the `lib/` files are compressed and only the ELF check applies.
3. **Content** (blocking; risks L2 and, for developer tooling, PLAN 7.2): no zip entry, nested asset-zip entry or dex class descriptor (`dexdump`, build-tools 36.0.0) matching `mutagen`, `readline`, `libreadline`, `org/schabi/newpipe` or `org/mozilla/javascript`; native libraries and top-level Python packages only as the lockfile lists them (Python packages: `neutrodyne_ytx`, the standard library, `yt_dlp`, `yt_dlp_ejs`); the `armeabi-v7a` APK contains no Python or Chaquopy native library, and its unusable Python assets are reported with their size (tolerated only while PB13 holds, 01 open question 13). **No developer tooling** ([01 Dev-tools switch](01-foundation.md#dev-tools-switch)): no `Lleakcanary/` or `Lshark/` class, no `androidx.compose.ui.tooling.PreviewActivity` and no activity contributed by `ui-test-manifest` in the merged manifest (`aapt2 dump xmltree --file AndroidManifest.xml`), no class compiled from `app/src/devTools/` (the script derives the class descriptors from that directory's package and file names), and a packaged network security config without `<debug-overrides>`. `--no-engine`: no `Lch/lkmc/neutrodyne/youtube/ytdlp/` class, no Chaquopy, CPython or yt-dlp file and no `YtxService` in the merged manifest. The M0 negative checks of PLAN M0 AC2 (an APK with `mutagen/__init__.py`, one with `libreadline.so`) are recorded in [01 Verification log](01-foundation.md#verification-log).
4. **Manifest facts of a published build** (`--published`, PLAN M0 AC7 and AC9): package `ch.lkmc.neutrodyne` (never `.dev`), `application-debuggable` present, no `android:testOnly`, `versionCode` and `versionName` equal to `gradle.properties`.
5. **Locale config:** `aapt2 dump xmltree --file res/xml/_generated_res_locale_config.xml` (Unverified generated file name; the manifest's `android:localeConfig` points at it) on every published APK lists exactly the locales of `app/policy/locales.txt` — no pseudo-locales, no library-only translations — and `aapt2 dump configurations` shows no `en-rXA` or `ar-rXB` resource configuration ([Compose UI and screenshot tests](#compose-ui-and-screenshot-tests)).
6. **Metadata hygiene and signing:** `unzip -l` shows no `META-INF/version-control-info.textproto` ([vcsInfo off](#hygiene)); `--published` additionally checks with `apksigner verify --verbose --print-certs` that the signing schemes are exactly v2 and v3 and that the signer equals `debug-cert-sha256.sh` (the dependency-info block stays off, 01).

### PR template

`.github/PULL_REQUEST_TEMPLATE.md` checklist (each line is a checkbox):

- Tests per [Test obligations per change](#test-obligations-per-change); bug fixes include a failing-first regression test.
- UI changed → screenshots re-recorded with `record-screenshots.yml`; accessibility checks pass.
- Strings externalised, plurals used, no concatenation.
- Schema change → version bump, migration and test.
- New setting → classified `settings`/`device_settings` and registered (01).
- YouTube UI checked in both capability modes (engine present and external mode) against [04 Capability matrix](04-youtube.md#capability-matrix) ([08 Capability differences in UI](08-ui-ux.md#capability-differences-in-ui)).
- No MockK in `androidTest`.
- No developer tooling outside the dev-tools switch (`app/src/devTools/`, `-Pneutrodyne.devTools=true`), no `BuildConfig.DEBUG`, and any test hook in `main` inert until an instrumentation test sets it, except a shell-only, `DUMP`-protected trigger that runs only production code (05's `SnapshotNowReceiver`; PLAN 7.2, [D2](../PLAN.md#3-key-decisions)).
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
    { "groupName": "AndroidX Test and benchmark", "matchPackageNames": ["/^androidx\\.test/", "/^androidx\\.benchmark/"] },
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
| AGP and Lint | new Lint checks (baseline rule above); R8 behaviour changes on `benchmark` (minified nightly leg before merge: dispatch `nightly.yml`); the debug build type's defaults (debuggable, no `testOnly` from the command line, split output names) unchanged; `compileSdk` coupling with the Compose BOM |
| Screenshot stack | the PR includes the full re-record (`FULL` tier) |
| Media3 | release notes read for `@UnstableApi` changes; dispatch the nightly instrumented job on the branch (risk M3r) |
| Room and SQLite | migration tests on both drivers (dispatch instrumented); 16 KB alignment output |
| Chaquopy (`com.chaquo.python`, dashboard approval) | [01 S7](01-foundation.md#s7-chaquopy-under-agp-941) steps 3 and 5 repeated on the branch: minified `benchmark` build, `selftest` in `:ytx` on the API 26 GMD and the API 37 16 KB image (dispatch `nightly.yml`), published APK sizes against PB12/PB13, 16 KB check of the asset `.so` files; `python-components.lock` updated (`chaquopy`, `python` and the runtime's bundled component versions — `checkPythonLicences` fails otherwise) and the Licences entries with it; a new CPython minor version also needs the `actions/setup-python` and release-container versions and makes `EngineStore` re-extract engine versions on devices (04) |
| Bundled yt-dlp (not Renovate-managed) | only a version the engine canary approved (the served manifest's `ytdlp.version`); `scripts/engine/bump-ytdlp.sh <version>` vendors the upstream files and updates `bundled.json` and the lockfile; CI runs `verifyBundledYtDlp`, `checkPythonLicences` and `shimTest`; the Licences screen shows the new version (04) |
| GitHub Actions | release notes for breaking input changes; SHA pins updated |

**Gradle wrapper:** Renovate's `gradle-wrapper` manager updates `gradle-wrapper.properties`; Unverified whether the hosted app also regenerates `gradle-wrapper.jar` and `distributionSha256Sum` (self-hosted needs `allowedUnsafeExecutions`). If it does not, the maintainer runs `./gradlew wrapper --gradle-version X --gradle-distribution-sha256-sum <sum>` on the PR branch. Renovate ≥ 44.14.7 fixed a command injection through the wrapper (CVE-2026-88886); the hosted app is current.

---

## Versioning and signing

Serves N11, N12; mitigates risks P9, P10. Delivered in M0 (scheme, `release.sh`, the committed debug keystore, the first release `v0.1.0`). Honours [D2](../PLAN.md#3-key-decisions), [D61](../PLAN.md#3-key-decisions), [D63](../PLAN.md#3-key-decisions), [PO-8](../PLAN.md#48-further-product-owner-decisions) (resolved: `ch.lkmc.neutrodyne`), [PO-33](../PLAN.md#48-further-product-owner-decisions) (resolved 2026-10-05: no beta channel), [PO-35](../PLAN.md#48-further-product-owner-decisions) (resolved 2026-10-05: "Just build debug builds").

### Version scheme

Single source: `gradle.properties` keys `neutrodyne.versionName` and `neutrodyne.versionCode` ([01](01-foundation.md#settingsgradlekts-gradleproperties-root-build)); Gradle never reads git or the clock. M0 commits `neutrodyne.versionName=0.1.0` and `neutrodyne.versionCode=10095`.

`versionCode = MAJOR·1 000 000 + MINOR·10 000 + PATCH·100 + S`, where S is:

| `versionName` suffix | S | Example |
|---|---|---|
| none — every published tag since [PO-33](../PLAN.md#48-further-product-owner-decisions) | 95 | `0.1.0` → 10095, `1.0.0` → 1000095, `1.2.3` → 1020395 |
| `-beta.N` (N = 1…79) | N | reserved, unused since PO-33 (a later beta channel would need no renumbering) |
| `-rc.N` (N = 1…15) | 79 + N | reserved, unused since PO-33 |
| 96–99 | reserved (never used) | |

Rules: MINOR and PATCH ≤ 99. A version code is never reused, even for a failed release: a published immutable release locks its tag for good ([immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases)), and the update check, Obtainium and Android's downgrade rule all compare codes. All three ABI APKs of a release share its `versionCode` ([D63](../PLAN.md#3-key-decisions)). A hotfix after `1.2.3` is `1.2.4`, never a rebuild. Pre-1.0 tester builds of milestone Mn are `0.{n+1}.P` — P = 0 for its first build, then the next PATCH for later increments and fixes: M0 `0.1.0` (10095), M1a `0.2.0`, M1b for example `0.2.1`, …, M10 `0.11.0`. An increment that lands out of order (M11a before M3, M8, M9 or M10) ships as the next PATCH of the current line (PLAN 7.1's example `0.3.1`), so version codes follow release order and stay monotonic; the M11 line `0.12.P` carries M11b's release candidates (and M11a when it lands after M10), then `1.0.0`. Every tag is a normal GitHub release ([Tester builds](#tester-builds)). After 1.0: MINOR for feature releases, PATCH for fixes and YouTube hotfixes; MAJOR only by PO decision.

```mermaid
stateDiagram-v2
  [*] --> Released: release.sh 0.1.0 tags the prepared version
  Released --> Patch: release.sh patch
  Released --> Minor: release.sh minor
  Released --> Major: release.sh major, PO decision only
  Released --> Hotfix: release.sh patch --hotfix on release/X.Y
  Patch --> Released
  Minor --> Released
  Major --> Released
  Hotfix --> Released
```

### `scripts/release.sh`

Usage: `scripts/release.sh <patch|minor|major|X.Y.Z> [--hotfix] [--dry-run]` (`--beta`, `--rc` and `finalise` were removed with PO-33). Algorithm:

1. Preconditions: on `main` (or, for `--hotfix`, on a `release/X.Y` branch, below), clean tree, `HEAD` equals its `origin` branch, `ci.yml` concluded `success` for `HEAD` (`gh run list --commit`), no open issue labelled `release-blocker`, and either the latest scheduled `nightly.yml` run is green or (with `--hotfix`, PATCH only) a `nightly.yml` run dispatched with `scope: youtube-smoke` is green for `HEAD`.
2. Compute the next `versionName` from the current one and the argument (`X.Y.Z` names it explicitly; any suffix is refused). Compute `versionCode` per the table (S = 95); refuse if it is lower than the current code, if it equals the current code while tag `v<current versionName>` exists, or if any existing tag has the new name. Equal to the current code without a tag is the "tag the prepared version" case: the very first release (`scripts/release.sh 0.1.0`, the value 01 commits in M0) tags `HEAD` without a release commit.
3. Require `changelogs/<versionCode>.txt` **in `HEAD`** (it lands through a normal PR beforehand; `--dry-run` prints the code to name it): non-empty, ≤ 500 characters.
4. Rewrite the two `gradle.properties` lines and nothing else; commit `Release vX.Y.Z`; create an annotated (and, when the maintainer has git signing configured, signed) tag `vX.Y.Z`. The single-file, two-line diff is what `verify-tag.sh` accepts without waiting for the release commit's own CI run.
5. `git push --atomic origin <branch> vX.Y.Z` (ruleset bypass for maintainers). `release.yml` takes over.

**Hotfix while `main` already carries work for the next MINOR** (for example latest release `1.0.3`, while `main` holds unfinished `1.1` work): a patch cut from `main` would ship that work. Instead, create `release/1.0` from tag `v1.0.3` on first need (same ruleset as `main`; `ci.yml` also runs on `push` to `release/*`), cherry-pick the fix, dispatch `nightly.yml` `scope: youtube-smoke` on that branch and run `release.sh patch --hotfix` there → `v1.0.4` (1000495). The fix also lands on `main` and ships with `1.1.0` (1010095). Version codes stay monotonic: 1000395 → 1000495 → 1010095. `verify-tag.sh` accepts a tagged commit on `main` or on a `release/*` branch, and its `make_latest` rule keeps a late patch of an older line from ever becoming "latest" ([release.yml](#releaseyml) step 1).

### Changelogs and release notes

- `changelogs/<versionCode>.txt`: user-facing plain text, English, ≤ 500 characters; one file feeds the GitHub release body ([Release body](#release-body)) and the `notes` field of `neutrodyne-update.json`, and with it the update card and the update notification ([08 Updates settings](08-ui-ux.md#updates-settings)). Not translated (excluded from Weblate).
- Release notes describe YouTube as "subscribe to YouTube channels and listen to them as audio"; nothing advertises downloading YouTube videos ([04 Posture and emergency build](04-youtube.md#posture-and-emergency-build)).
- Tester-build notes start with "Tester build for milestone Mn." and link the milestone's acceptance checklist issue. They are normal releases, so every installed copy is told about them ([Tester builds](#tester-builds)).

### Debug keystore

Every build is signed with one keystore committed to the repository ([D61](../PLAN.md#3-key-decisions), [01 Signing config](01-foundation.md#signing-config)): `signing/neutrodyne-debug.keystore`, PKCS12, alias `androiddebugkey`, store and key password `android`. It is **public on purpose**. The owner decided to publish debug builds ([PO-35](../PLAN.md#48-further-product-owner-decisions)), and a committed key cannot be lost, needs no custody and gives CI and every developer machine the same signer, so every build installs over every other (PLAN M0 AC8). There are no key holders, backups, yearly checks or signing secrets; the costs are listed in [Public key trade-offs](#public-key-trade-offs).

1. **Created once in M0**, before the first release `v0.1.0` (installs signed with any other key could never update to later releases without uninstalling), with JDK 21 `keytool` on any machine:
   `keytool -genkeypair -v -storetype PKCS12 -keystore signing/neutrodyne-debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 12000 -dname "CN=Neutrodyne Debug, O=Neutrodyne"` (12,000 days ≈ 33 years; Android Studio's generated debug certificate expires after 30 years, [app signing](https://developer.android.com/studio/publish/app-signing)). It is committed with `.gitattributes` `signing/*.keystore binary`. Unverified: whether GitHub push protection flags a committed PKCS12 file; if it does, the push is completed with the documented bypass reason, because the key is meant to be public.
2. **`signing/README.md`** (public text, M0; content owned here): this keystore signs every Neutrodyne build and is public on purpose, so a matching signature proves nothing about who built an APK (risk [P10](../PLAN.md#8-risks-and-mitigations)); download Neutrodyne only from `{repoUrl}/releases` and check the file ([Release body](#release-body)); anyone who distributes a fork or rebuild must change the application ID or the key, or it installs over Neutrodyne and inherits its data; never use this key for anything else.
3. **Expected certificate:** `scripts/ci/debug-cert-sha256.sh` computes the certificate SHA-256 from the committed file (`keytool -list -v -keystore signing/neutrodyne-debug.keystore -storepass android -alias androiddebugkey`), so no repository variable can drift from the file; `release.yml` step 3 and `check-apk.sh --published` compare every APK's signer with it. The value is deliberately **not published as a trust anchor**: the README, release bodies and the help page list no fingerprint, because with a public key it proves nothing.

APK signature schemes: v1 off (minSdk 26 ≥ 24), v2 and v3 on, checked on every published APK by [release.yml](#releaseyml) step 3. Whether v3 key rotation away from this public key could ever spare users a reinstall is Unverified ([Public key trade-offs](#public-key-trade-offs)).

The Ed25519 engine-manifest key is unrelated: it is a secret of the `engine-approval` environment, not an APK key, and the project's only private signing key ([engine-canary.yml](#engine-canaryyml)).

### Gradle signing configuration

Lives in `:app`, applied by `neutrodyne.android.application`; 01's [Build variants and ABIs](01-foundation.md#build-variants-and-abis) sketch shows the same lines in context (09 owns the values):

```kotlin
android {
    signingConfigs {
        create("neutrodyneDebug") {                     // committed and public (Debug keystore)
            storeFile = rootProject.file("signing/neutrodyne-debug.keystore")
            storeType = "pkcs12"
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
            enableV1Signing = false; enableV2Signing = true; enableV3Signing = true
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("neutrodyneDebug") }      // the published build type
        getByName("benchmark") { signingConfig = signingConfigs.getByName("neutrodyneDebug") }  // measurement only, never published
    }
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")   // nightly benchmark leg: -PtestBuildType=benchmark
}
```

- No environment variable, no secret and no unsigned build: PR builds, the nightly `repro` rebuilds, `benchmark` and dev-tools builds are signed exactly like releases. AGP's default debug signing, a per-machine `~/.android/debug.keystore` ([build variants](https://developer.android.com/build/build-variants)), is never used, because every machine would then be a different signer.
- `:app` has no `release` variant (01 disables it), so no `release` signing config exists. A later non-debuggable release build type with a private key is the owner's option ([Public key trade-offs](#public-key-trade-offs)).

### Public key trade-offs

The owner's decision ([PO-35](../PLAN.md#48-further-product-owner-decisions), [D61](../PLAN.md#3-key-decisions)) has these recorded consequences. The README's "About these builds" item ([README "Install and update"](#readme-install-and-update)), 08's BUILDS help card and every release body state them for users:

| Consequence | Why | What Neutrodyne does |
|---|---|---|
| Anyone can sign an APK that Android installs over Neutrodyne as an update — a malicious "update" offered elsewhere or a careless fork — and it inherits the library, settings, downloads, the Android Keystore key and therefore the stored private-feed passwords (risk [P10](../PLAN.md#8-risks-and-mitigations)) | Android accepts an update signed by the installed app's certificate, and the private key is in the repository | download only from the GitHub release page (README, help page, `signing/README.md`, every release body); immutable releases, `SHA256SUMS` and provenance attestations make a genuine file checkable (N12); the update card opens only `{repoUrl}/releases/` links and shows the SHA-256 of the device's APK ([Update card and links](#update-card-and-links)); forks are told to change the application ID or the key |
| The certificate fingerprint proves nothing | the key is public | no fingerprint is published as a trust anchor; the release page, checksums and attestations are the anchors ([Release assets](#release-assets)) |
| Auto Backup data reaches any APK signed with the same certificate, a spoofed one included | restore checks the signing certificate ([05 Platform constraints](05-groups-opml-backup.md#platform-constraints)) | the same download rule; covered by P10 |
| Debuggable APKs expose app data to anyone with ADB access to an unlocked, authorised device: `run-as`, a JDWP debugger, and `adb backup` on Android 12+ (risk [P11](../PLAN.md#8-risks-and-mitigations)) | `android:debuggable="true"` ([application element](https://developer.android.com/guide/topics/manifest/application-element), [AOSP run-as](https://android.googlesource.com/platform/system/core/+/refs/heads/main/run-as/run-as.cpp), [Android 12 behaviour changes](https://developer.android.com/about/versions/12/behavior-changes-12)) | the README and the help page advise turning USB debugging off when not needed, in particular after an ADB install; credentials stay Keystore-encrypted at rest, though a debugger attached over ADB can use the key and decrypt them ([01 Platform compliance](01-foundation.md#platform-compliance) P40); no secret is compiled into the APK except a Podcast Index key after written permission ([D26](../PLAN.md#3-key-decisions)) |
| Anyone can register Neutrodyne's certificate with Google's developer verification, and Neutrodyne cannot register without a private key (risk [P9](../PLAN.md#8-risks-and-mitigations)) | registration needs proof of key possession, which the public key gives everyone | [Package name and key](#package-name-and-key) |
| Less smooth and larger than a release build (risk [T18](../PLAN.md#8-risks-and-mitigations)) | debuggable, no R8, no baseline profile | N5 is measured on `benchmark`; the published build is recorded report-only ([Performance budgets](#performance-budgets)) |

**A later switch to a private key** (owner decision; nothing is prepared beyond keeping 01's keep rules tested on `benchmark`):

- Every user must uninstall and reinstall once: Android refuses an update with another signer. The library moves through a manual backup ZIP (R1.7); Auto Backup restore does not cross the switch (05).
- APK Signature Scheme v3 key rotation (proof-of-rotation from API 28, v3.1 from API 33, [v3 scheme](https://source.android.com/docs/security/features/apksigning/v3)) could in principle let installed copies accept a new key without a reinstall. But API 26–27 cannot rotate, and whether rotating away from a key that is already public protects anything is Unverified: anyone holding the old key can also sign. The plan therefore assumes one reinstall.
- A developer registration ([D80](../PLAN.md#3-key-decisions)) becomes possible only with the new key.
- Then needed: key custody, a non-debuggable `release` build type, a signing secret in `release.yml`, and updated README, help page and release bodies. Not designed further until the owner decides.

Removed 2026-10-05 (PO-35): the RSA-4096 release key, its offline ceremony, encrypted backups, second holder and yearly checks, the repository variables `NEUTRODYNE_CERT_SHA256`/`NEUTRODYNE_PREVIOUS_CERT_SHA256`, and the v3.1 rotation runbook with its M11b rehearsal (PLAN M11 AC12 removed).

---

## Distribution channels

Serves R6.1, N12; mitigates risks P3, P7, P10. Delivered in M0 (immutable, normal GitHub releases with every asset from `v0.1.0`), M11b (release hardening). Honours [D2](../PLAN.md#3-key-decisions), [D3](../PLAN.md#3-key-decisions), [D61](../PLAN.md#3-key-decisions), [D77](../PLAN.md#3-key-decisions), [D79](../PLAN.md#3-key-decisions), [PO-2](../PLAN.md#po-2-distribution-channels) (resolved: GitHub Releases only), [PO-33](../PLAN.md#48-further-product-owner-decisions) and [PO-34](../PLAN.md#48-further-product-owner-decisions) (resolved 2026-10-05: no beta channel, no mirror).

**GitHub Releases is the only channel.** There is no Google Play, F-Droid, IzzyOnDroid or other store listing; any other copy is not Neutrodyne's. Users install an APK from the repository's releases page ([Developer verification](#developer-verification) describes what Android asks for). Updates are announced by the [update check](#update-check), which links to the release so that the user installs it with Android's installer, or arrive through Obtainium. Engine updates of the YouTube engine are not app releases: they come from yt-dlp's own GitHub releases, approved through GitHub Pages ([engine-canary.yml](#engine-canaryyml)).

| Channel | Artefact | Signing | Latency | From |
|---|---|---|---|---|
| GitHub Releases | the [release assets](#release-assets) of each tag, every tag a normal release | the committed debug key (v2 + v3), public ([Debug keystore](#debug-keystore)) | minutes after the tag | M0 |
| In-app update check | a notification and the update card with "Open release on GitHub" and "Download APK for this device"; the user downloads in the browser and installs with Android's installer | — (the app installs nothing) | ≤ 24 h (daily check) or on "Check now"; switch `updates.check_enabled` | M11a |
| Obtainium (third-party, user-installed) | the same APK, chosen by its APK filter | the committed key | per Obtainium's schedule | M0 (documented) |

### Release assets

Per tag `vX.Y.Z` ([D79](../PLAN.md#3-key-decisions)); always a normal release, never a pre-release; "latest" when its `versionCode` is higher than the current latest release's ([release.yml](#releaseyml) step 1):

| Asset | Content |
|---|---|
| `neutrodyne-{v}-arm64-v8a.apk` | 64-bit ARM phones and tablets; with the YouTube engine |
| `neutrodyne-{v}-x86_64.apk` | x86_64 devices and emulators (for example Intel or AMD Chromebooks; Unverified which of them run Android apps as x86_64); with the engine |
| `neutrodyne-{v}-armeabi-v7a.apk` | 32-bit ARM devices; no engine, YouTube in external mode ([D77](../PLAN.md#3-key-decisions)) |
| `neutrodyne-update.json` | the [update manifest](#update-manifest) the update check reads |
| `SHA256SUMS` | `sha256sum` format over the four files above |

All three APKs share one `versionCode`, are debug builds (debuggable, not minified, [D2](../PLAN.md#3-key-decisions)) signed with v2 and v3 (no v1), and pass [Build-output checks](#build-output-checks). There is **no universal APK** (as a debug build it would carry two engines and reach ≈ 60–80 MB, Unverified; [D77](../PLAN.md#3-key-decisions)). No R8 mapping is published, because nothing is minified and stack traces are readable as they are. The emergency build without the engine ([01](01-foundation.md#emergency-build-without-the-engine)) publishes the same asset set, with no engine in any APK.

**Integrity** (N12): every release is published as an **immutable release**: created as a draft, every asset uploaded, then published. Afterwards assets cannot be changed or deleted, the tag cannot move, and a deleted release's tag name can never be reused ([immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases), GA since 2025-10-28, [changelog](https://github.blog/changelog/2025-10-28-immutable-releases-are-now-generally-available/)). Publishing creates GitHub's release attestation (verified with `gh release verify` and `gh release verify-asset`); `actions/attest` adds SLSA build-provenance attestations for the APKs, the manifest and `SHA256SUMS` (Build Level 2 on GitHub-hosted runners; GitHub calls attestations "not a security guarantee" on their own, [artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations)). The signing key is public ([Public key trade-offs](#public-key-trade-offs)), so the signature says nothing about origin: **the release page, immutable releases, `SHA256SUMS` and the attestations are the trust anchors**, and the attestations show which workflow run built a file.

### Release body

Generated by `release.yml` from `changelogs/<versionCode>.txt` and this template (`{…}` filled in):

```text
{changelog text}

Install: pick the APK for your device —
  neutrodyne-{v}-arm64-v8a.apk    most phones and tablets
  neutrodyne-{v}-x86_64.apk       x86_64 devices and emulators
  neutrodyne-{v}-armeabi-v7a.apk  older 32-bit phones (YouTube opens in the YouTube app)

These APKs are debug builds signed with a public key (signing/neutrodyne-debug.keystore in this
repository). The signature does not show who built a file: download Neutrodyne only from this page
and check it:
  sha256sum --check --ignore-missing SHA256SUMS
  gh release verify-asset {tag} neutrodyne-{v}-arm64-v8a.apk -R {owner}/Neutrodyne
  gh attestation verify neutrodyne-{v}-arm64-v8a.apk -R {owner}/Neutrodyne

Debug builds also let a computer that your unlocked phone allows to use USB or wireless debugging
read and change Neutrodyne's data (turn USB debugging off when you don't need it), and they run
less smoothly than a release build would.

Install and update guide: {repoUrl}#install-and-update
```

`{repoUrl}#install-and-update` assumes the README heading "Install and update" (the README is maintained with the PLAN; its content is drafted in [Developer verification](#readme-install-and-update)). Tester-build bodies start with "Tester build for milestone Mn." ([Changelogs and release notes](#changelogs-and-release-notes)). The AppVerifier certificate block was removed on 2026-10-05: with a public key it would suggest a check that proves nothing.

### GitHub Releases and Obtainium

- README badge: `https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/<owner>/Neutrodyne` ([Obtainium deep links](https://wiki.obtainium.imranr.dev/deep_links/)); 08's help page offers the `obtainium://add/{repoUrl}` link directly ([08 Install and updates help](08-ui-ux.md#install-and-updates-help)).
- APK choice: each release has three APKs, so Obtainium needs an APK filter regex per device, for example `neutrodyne-.*-arm64-v8a\.apk$`, or its architecture filter (`autoApkFilterByArch` in its source, [Obtainium](https://github.com/ImranR98/Obtainium)). Obtainium considers only APK assets, so `SHA256SUMS` and the manifest do not confuse it (read from its source on 2026-10-05). The tag is always `v` + `versionName`, which Obtainium's version detection expects. Every tag is a normal release, so no "Include prereleases" option is needed.
- Obtainium's GitHub source queries the REST API (`api.github.com/…/releases`), so GitHub's limit of 60 unauthenticated requests per hour per IP applies to it ([REST limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)); users behind a shared IP can add a GitHub token in Obtainium. Neutrodyne's own update check never uses the REST API ([Checking](#checking)).
- Obtainium users can turn off Neutrodyne's own check (Settings › Updates › "Check for updates", R6.3); the app does not try to detect Obtainium. Developer verification applies to Obtainium's installs like to any other installer.

### Tester builds

Since [PO-33](../PLAN.md#48-further-product-owner-decisions) there is no beta channel and no `releases.atom` reader. Every milestone's tester build (PLAN DoD) is a normal, immutable GitHub release `0.{n+1}.P` ([Version scheme](#version-scheme)). `releases/latest/download/…` resolves to it, because the latest release is the most recent non-prerelease, non-draft one ([latest release](https://docs.github.com/en/rest/releases/releases#get-the-latest-release), [linking to releases](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases)). So the update check and Obtainium of every installed copy see it: before 1.0, testers are the users. Its notes start with "Tester build for milestone Mn." and link the release issue. A broken tester build is fixed forward with the next PATCH; nothing published is withdrawn. After 1.0 every tag reaches every user, so a MINOR is tested on CI artifacts (`assemble`'s `debug-apks`) and by the [release checklist](#release-checklist) before it is tagged.

### GitHub takedown

GitHub is the single distribution and update channel. Risk [P7](../PLAN.md#8-risks-and-mitigations) is **accepted** ([PO-34](../PLAN.md#48-further-product-owner-decisions): no mirror, no mirrored release assets or manifests, no fallback URL in the app). A DMCA notice, an abuse report or an account suspension would remove the releases, the update manifest and the engine manifest (GitHub Pages) at once; youtube-dl's 2020 takedown was reversed, but only after weeks ([GitHub blog](https://github.blog/2020-11-16-standing-up-for-developers-youtube-dl-is-back/)). What keeps working:

- Installed apps keep running. The update check fails quietly (`Failed(NETWORK)`, or `Failed(MANIFEST_INVALID)` on a 404) and a known `Available` stays visible, although its links would fail.
- The YouTube engine keeps its bundled and active versions; "Reset to bundled" always works. Engine-update checks fail, and the engine heartbeat goes stale and says so ([engine-canary.yml](#engine-canaryyml)).
- The source history and the build recipe survive in every clone. Because the keystore is committed, a rebuild from any clone installs over existing copies, so after a takedown users could not tell a genuine new home from a spoofed one by the signature (risk P10). A move would be announced through the project's other channels, if any; no recovery runbook is planned.

The monthly `keepalive.yml`, which counters GitHub's 60-day rule for scheduled workflows, is unrelated to takedowns and stays ([Helper workflows](#helper-workflows)).

---

## Update check

Serves R6.2–R6.4, N3, N7, N12; mitigates risks P3, P7, P10, T16. Delivered in M0 (`release.yml` publishes `neutrodyne-update.json` from `v0.1.0`), M11a (everything below; needs only M2 and M0, PLAN 7.1). Honours [D13](../PLAN.md#3-key-decisions), [D78](../PLAN.md#3-key-decisions), [D80](../PLAN.md#3-key-decisions), [PO-31](../PLAN.md#48-further-product-owner-decisions) (resolved 2026-10-05: notify only), [PO-33](../PLAN.md#48-further-product-owner-decisions) (resolved: no beta channel), [PO-36](../PLAN.md#48-further-product-owner-decisions) (notice timing). Screens, notification texts and the help page: [08 Updates settings](08-ui-ux.md#updates-settings), [08 Install and updates help](08-ui-ux.md#install-and-updates-help). What a manually installed update means for playback and downloads: [06 App updates and playback](06-playback.md#app-updates-and-playback), [07 App update](07-downloads.md#app-update). Permissions: none ([01 Manifest and permissions](01-foundation.md#manifest-and-permissions)).

GitHub is the only channel and most users never install Obtainium, so the app tells them when a newer release exists and links to it. The user downloads the APK in the browser and installs it with Android's installer. The owner's words: "Provide a link to github where the user can download and install the new build manually." The app **never** downloads, verifies or installs an APK. It holds no install permission (N7), never calls `api.github.com`, and does not interact with playback or downloads. Dev-tools builds have it off (`Disabled(DEV_BUILD)`).

### Modules and API

No modules of its own ([D13](../PLAN.md#3-key-decisions)): interfaces in `:core:domain` (package `ch.lkmc.neutrodyne.core.domain.update`), state types in `:core:model` (`ch.lkmc.neutrodyne.core.model.update`), implementation in `:core:data` (`ch.lkmc.neutrodyne.core.data.update`, Hilt `UpdateModule`). `:feature:settings` (Settings › Updates, Install & updates help) and `:app`'s root (first-run card, verification notice, gear badge) use only the interfaces. `:core:data` already hosts comparable workers, and the check needs no install permission, no `PackageInstaller` and no playback or download state, which were the only reasons for separate `:update:*` modules.

```kotlin
// :core:domain — ch.lkmc.neutrodyne.core.domain.update
interface AppUpdateChecker {
    val state: StateFlow<UpdateCheckState>
    suspend fun checkNow(): UpdateCheckState   // user action; also with "Check for updates" off; refused (returns the current
                                               // state) only in Disabled(DEV_BUILD); at most one request per 60 s
    fun skip(versionCode: Long)                // from Available: hide this version until a higher versionCode appears
}
interface UpdateNotices {
    val pending: StateFlow<UpdateNotice?>
    fun dismiss(notice: UpdateNotice)
}

// :core:model — ch.lkmc.neutrodyne.core.model.update
sealed interface UpdateCheckState {
    data class Disabled(val reason: UpdateDisabledReason) : UpdateCheckState
    data class Idle(val lastCheckAtMs: Long?) : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Available(val info: UpdateInfo, val lastCheckAtMs: Long,
                         val lastError: UpdateCheckError? = null) : UpdateCheckState   // a failed re-check keeps Available
    data class Failed(val error: UpdateCheckError, val lastCheckAtMs: Long?) : UpdateCheckState
}
enum class UpdateDisabledReason { DEV_BUILD, CHECKS_OFF }
data class UpdateInfo(val versionName: String, val versionCode: Long, val minSdk: Int, val publishedAt: String,
                      val notes: String, val releaseUrl: String,
                      val apk: UpdateApk?)              // null: the release has no APK for Build.SUPPORTED_ABIS[0]
data class UpdateApk(val abi: String, val fileName: String, val url: String, val sizeBytes: Long, val sha256: String)
enum class UpdateCheckError { NETWORK, RATE_LIMITED, MANIFEST_INVALID }
enum class UpdateNotice { FIRST_RUN_CHOICE, VERIFICATION_ENFORCEMENT }
```

| Class (`:core:data`) | Responsibility |
|---|---|
| `AppUpdateCheckerImpl` | the state rules below; reads `updates.check_enabled` (`SettingsRepository`), `BuildInfo` and `UpdateCheckStore`; enqueues or cancels `app-update-check` when the switch changes |
| `UpdateNoticesImpl` | notice rules ([Notices](#notices)) |
| `GitHubUpdateSource` | the one GET ([Checking](#checking)) on 01's API client |
| `UpdateManifestParser`, `VersionScheme` | manifest parsing and validation, including the link prefix; [D63](../PLAN.md#3-key-decisions)'s `versionCode` from a version name (the same formula as `release.sh`) |
| `UpdateCheckWorker` | `app-update-check`, `app-update-check-now` |
| `UpdateNotifier` | the `updates` channel's single notification `NOTIF_ID_UPDATE = 4200` (texts: 08) |
| `UpdateCheckStore` | `noBackupFilesDir/updates/last-check.json`: the last parsed `UpdateInfo` and the check time, so `Available` survives process death without a request |
| `VerificationTimeline` | compiled-in notice and enforcement dates ([Notices](#notices)) |
| `UpdateModule` | Hilt bindings of `AppUpdateChecker` and `UpdateNotices` |

```mermaid
stateDiagram-v2
  [*] --> Idle
  [*] --> Disabled: dev-tools build, or checks off
  Idle --> Checking: daily work or Check now
  Disabled --> Checking: Check now while checks are off
  Checking --> Idle: up to date or skipped
  Checking --> Available: newer release
  Checking --> Failed: network, rate limit, bad manifest
  Failed --> Checking: next daily work or Check now
  Available --> Checking: next daily work or Check now
  Available --> Idle: skipped, or that version now runs
```

State rules:

- **At process start** `state` is computed without a request: a dev-tools build → `Disabled(DEV_BUILD)` (always wins; a restored `updates.check_enabled = true` changes nothing). Otherwise, if `UpdateCheckStore` holds an `UpdateInfo` whose `versionCode` is higher than `BuildInfo.versionCode` and not `updates.skipped_version_code`, and the switch is on → `Available`. Otherwise, switch off → `Disabled(CHECKS_OFF)`, else `Idle(updates.last_check_at)`. A stored version at or below the running one means it was installed: the store entry is dropped and its notification cancelled.
- **Switch off** → `Disabled(CHECKS_OFF)` at once, `app-update-check` cancelled; the badge goes away. "Check now" still runs (`Checking` → its result), and that result stays until the process ends or the switch changes. **Switch on** → re-enqueue `app-update-check` and recompute as at start.
- **A failed check never hides a known `Available`:** it only sets `lastError` (08 shows "Couldn't check again"). Without a known update it ends in `Failed(error, lastCheckAtMs)`.
- `checkNow()`: a call within 60 s of the last request returns the current state without a request. **Offline** (`NetworkMonitor.status.value.isConnected == false`, [01 NetworkMonitor](01-foundation.md#networkmonitor)) it sends nothing, enqueues nothing and ends at once with `NETWORK` — `Failed(NETWORK, lastCheckAtMs)`, or a known `Available` with `lastError = NETWORK` — so "Check now" never waits for a connection. Otherwise it enqueues `app-update-check-now` (no network constraint, so a connection lost meanwhile fails fast in OkHttp as `NETWORK`) and suspends until `state` leaves `Checking`, at most 30 s; after that it cancels the work and ends with `NETWORK` the same way. The now-work never returns `Result.retry()`: every outcome ends the check (403/429 → `RATE_LIMITED`); only the periodic `app-update-check` uses backoff.

### Update manifest

`neutrodyne-update.json`, an asset of every release (written by `make-update-json.sh`, validated by `check-update-json.sh` before the draft is published, [release.yml](#releaseyml) steps 5–6). Schema 1 with this layout, introduced with PO-31 (nothing shipped reads the older draft layout):

```json
{ "schema": 1, "versionName": "1.0.0", "versionCode": 1000095, "minSdk": 26, "published": "2027-…Z",
  "releaseUrl": "https://github.com/<owner>/Neutrodyne/releases/tag/v1.0.0", "notes": "…",
  "apks": [ { "abi": "arm64-v8a", "file": "neutrodyne-1.0.0-arm64-v8a.apk",
              "url": "https://github.com/<owner>/Neutrodyne/releases/download/v1.0.0/neutrodyne-1.0.0-arm64-v8a.apk",
              "size": 0, "sha256": "…" },
            { "abi": "x86_64", "file": "…", "url": "…", "size": 0, "sha256": "…" },
            { "abi": "armeabi-v7a", "file": "…", "url": "…", "size": 0, "sha256": "…" } ] }
```

| Field | `check-update-json.sh` (CI) | `UpdateManifestParser` (app) |
|---|---|---|
| `schema` | 1 | 1; anything else → `MANIFEST_INVALID` (08's `Failed` card offers "Open releases", `{repoUrl}/releases`, built from `BuildInfo.repoUrl`) |
| `versionName`, `versionCode` | equal the tag without `v` (no suffix), `gradle.properties` and D63's formula with S = 95 | `versionCode == VersionScheme.versionCode(versionName)`, else `MANIFEST_INVALID` |
| `minSdk` | 26 | parsed; when `minSdk > SDK_INT` the card offers only the release page and no notification is posted ([Update card and links](#update-card-and-links)) |
| `published`, `notes` | ISO-8601 UTC; `notes` equal to `changelogs/<versionCode>.txt` (≤ 500 characters) | shown as plain text, never as HTML or links |
| `releaseUrl` | `{repoUrl}/releases/tag/{tag}` | canonical check, never a string prefix: parsed with OkHttp's `HttpUrl`, which resolves `.`/`..` segments including `%2e` forms ([OkHttp `HttpUrl`](https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/HttpUrl.kt)); scheme `https`, host and port those of `BuildInfo.repoUrl` (`github.com`, 443), no user-info, query or fragment, and decoded `pathSegments` exactly `[owner, "Neutrodyne", "releases", "tag", "v{versionName}"]` (owner and repository from `BuildInfo.repoUrl`); else `MANIFEST_INVALID`. The app keeps and opens only `HttpUrl.toString()`, the canonical form |
| `apks[]` | exactly the three ABIs built, each `file` present in the release with matching `size` and `sha256`, `url` = `{repoUrl}/releases/download/{tag}/{file}` | every `url` passes the same canonical check with `pathSegments` exactly `[owner, "Neutrodyne", "releases", "download", "v{versionName}", file]` and `file` equal to that entry's `file`, else `MANIFEST_INVALID`; entries with an unknown `abi` are ignored; `sha256` 64 hexadecimal characters |

Unknown fields are ignored, so later schema-1 additions stay compatible; the whole manifest is ≤ 64 KB (larger → `MANIFEST_INVALID`). Removed with PO-31/PO-33: `prerelease`, `certSha256` and `previousCertSha256`.

The manifest is **not signed**, and need not be: the app only shows text and opens links whose canonical form is this repository's release page or release asset for the manifest's own version, so a forged manifest could at worst point to another genuine release page or asset of this repository, or show wrong notes. It cannot make the app install anything, because the app installs nothing. The SHA-256 on the card is informational: it comes from the same release as the file, so it reveals a damaged or swapped download, not a fake release. Integrity comes from GitHub — the release page, immutable releases, `SHA256SUMS` and the attestations ([Release assets](#release-assets)).

### Checking

One request per check, on 01's API client (standard User-Agent, no cookies, no token): `GET {repoUrl}/releases/latest/download/neutrodyne-update.json` (`BuildInfo.updateManifestUrl`). `github.com` answers 302 to `/releases/download/<tag>/…`, which redirects to a short-lived signed `release-assets.githubusercontent.com` URL (≈ 1 h validity, observed 2026-10-05); no REST API is involved ([linking to releases](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases)). Every tag is a normal release, so `latest` is the release `release.yml` marked latest ([Tester builds](#tester-builds)).

A check finds an update when the manifest's `versionCode` is higher than `BuildInfo.versionCode` and not equal to `updates.skipped_version_code`. It then picks the `apks[]` entry whose `abi` equals `Build.SUPPORTED_ABIS[0]`, the device's preferred ABI ([D78](../PLAN.md#3-key-decisions)). So a 64-bit phone that runs the `armeabi-v7a` APK is linked to the `arm64-v8a` APK (08's "Get the 64-bit version", risk T16). No such entry → `UpdateInfo.apk = null`, and the card offers only the release page.

Failures: I/O and timeouts → `NETWORK`; HTTP 403 or 429 from GitHub → `RATE_LIMITED` (the periodic work retries with its backoff; "Check now" does not retry) (GitHub publishes no limit for unauthenticated `releases/download` requests; Unverified, so the check assumes there is one); 404, an unparsable or invalid manifest → `MANIFEST_INVALID`. `api.github.com` (60 unauthenticated requests per hour per IP, shared under CGNAT, [REST limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)) is never called; `GitHubUpdateSourceTest` asserts it.

| Work ([D78](../PLAN.md#3-key-decisions)) | Type and policy | Enqueued |
|---|---|---|
| `app-update-check` | periodic 24 h with a 6 h flex window, network `CONNECTED`, `ExistingPeriodicWorkPolicy.UPDATE`, exponential backoff from 1 h | by the order-200 initializer (01) only while `updates.check_enabled` is on and the build is not a dev-tools build; the first enqueue has an initial delay of 24 h, so the [first-run card](#notices) is seen before the first scheduled check; cancelled when the switch goes off |
| `app-update-check-now` | one-time, `REPLACE`, **no** network constraint (a constrained work would wait offline and leave the state in `Checking`), never `Result.retry()` | `checkNow()` ("Check now") when `NetworkMonitor` reports a connection, also while the switch is off; never in dev-tools builds |

`app-update-download` and `app-update-install` were removed with PO-31.

### Update card and links

What the card shows is 08's ([08 Updates settings](08-ui-ux.md#updates-settings)): the version, release date, the first lines of the notes, the size of the device's APK, a copyable SHA-256 of that APK, and the actions below. The behaviour behind it:

- **"Open release on GitHub"** → `ACTION_VIEW` (+ `CATEGORY_BROWSABLE`) of `info.releaseUrl`, the tag's release page with the notes, every file and `SHA256SUMS`.
- **"Download APK for this device"** → `ACTION_VIEW` of `info.apk.url`: the browser downloads `neutrodyne-{v}-{abi}.apk`, and Android's installer installs it when the user opens the file. The browser or Files app needs Android's "install unknown apps" permission once, and developer verification applies there where it is enforced ([Developer verification](#developer-verification)). Absent when `apk == null` or `minSdk > SDK_INT`.
- Both URLs come only from the validated manifest; the UI never builds or rewrites a download URL. `ActivityNotFoundException` (no browser) → 08's "No app can open this link" with "Copy link".
- **"Skip this version"** → `skip(versionCode)`: writes `updates.skipped_version_code`, cancels that version's notification, `state` → `Idle`. A release with a higher `versionCode` supersedes the skip.
- **Badge:** while `state` is `Available`, 08's gear badge and the Settings home row show a dot; it goes away when the state leaves `Available` (skipped, the version now runs, checks turned off).
- **Notification:** `UpdateNotifier` posts `NOTIF_ID_UPDATE` on channel `updates` when a **scheduled** check yields `Available` for a `versionCode` other than `updates.notified_version_code`, `POST_NOTIFICATIONS` is granted (API 33+) and `minSdk ≤ SDK_INT`; it then writes `updates.notified_version_code`, so each version notifies at most once. A "Check now" result never posts it (the user is looking at the card) but records `updates.notified_version_code`. Title "Neutrodyne {versionName} is available", text the first line of the notes, tap `neutrodyne://open/settings/updates`, action "Open on GitHub" (`neutrodyne://open/settings/updates/release`, explicit to `MainActivity` per 01's rule; Settings › Updates then opens `info.releaseUrl` as its own button does, so `ActivityNotFoundException` → "Copy link" applies); final texts are 08's. It is cancelled when that version runs or is skipped.
- **After a manual install** Android replaces the APK and ends the app's processes like any update. At the next start the stored version is at or below the running one, so `state` returns to `Idle` and the notification is cancelled. Positions, the queue and downloads resume as after a process death (06, 07); nothing plays by itself.

### Notices

`UpdateNoticesImpl` raises at most one pending `UpdateNotice`; `:app`'s root shows it through 08's keys and `dismiss` records it:

| Notice | Raised when | Recorded in |
|---|---|---|
| `FIRST_RUN_CHOICE` | first start of a build with the update check (M11a or later), not in dev-tools builds and not when `updates.check_enabled` is already off (a restored choice; the card is then suppressed and `updates.first_run_choice_done` is still set, so it never appears later — 05's proposed default, its open question 16). It is PO-31's disclosure; "Turn off" writes `updates.check_enabled = false` | `updates.first_run_choice_done` |
| `VERIFICATION_ENFORCEMENT` | `now ≥ VerificationTimeline.NOTICE_FROM`, not in dev-tools builds, once per installation, on every device (the app cannot tell reliably whether a device is certified). Before `GLOBAL_ENFORCEMENT` (or while it is unknown) 08 shows the pre-enforcement notice; at or after it — which is what installations made after enforcement, through the advanced flow, see on their first start — the post-enforcement hint ("If you chose '7 days' … switch to 'indefinitely'") | `updates.verification_notice_shown_at` |

`WHATS_NEW` was removed with PO-31: the app no longer installs updates, so it cannot know which start follows its own install; the notes are on the update card and the release page.

`VerificationTimeline` (compiled-in constants, [PO-36](../PLAN.md#48-further-product-owner-decisions)): `NOTICE_FROM = 2026-12-01`, or an earlier date as soon as Google names the global enforcement date; `GLOBAL_ENFORCEMENT` = Google's date, null until announced. Because it is a date comparison on the device, a build shipped before December shows the notice on 2026-12-01 even if the user never updates again. A change of either constant ships in the next release ([Watch and notice timing](#watch-and-notice-timing)).

### Privacy and failure modes

- Network: `github.com` and `release-assets.githubusercontent.com` only, one manifest GET per check, listed as `app-updates` in the [network inventory](#network-inventory). The APK download happens in the user's browser, outside the app. GitHub sees the IP address and the standard User-Agent (app version, Android release) and no identifier; no request carries a cookie or token.
- Backup: only the portable `updates.check_enabled` travels (05's whitelist); the device keys and `noBackupFilesDir/updates/last-check.json` never do. A restored `true` never overrides `Disabled(DEV_BUILD)`.
- Process death is harmless: nothing is in flight but a GET; WorkManager re-runs the work, and `UpdateCheckStore` restores `Available` without a request.
- Logs carry versions and states, never the signed redirect URLs (01's `Redactor` masks query values).
- No install permission and no `PackageInstaller` anywhere (01's `checkBannedApis`, `verifyManifestPermissions` against `permissions.txt`).

### Tests

| Test class | Module, runner | Cases | Milestone |
|---|---|---|---|
| `UpdateManifestParserTest` | `:core:data`, JVM | schema 1 with unknown fields; each field rule of [Update manifest](#update-manifest); `versionCode` vs D63's formula on the [Version scheme](#version-scheme) examples (`0.1.0` → 10095, `1.0.0` → 1000095, `1.2.3` → 1020395; `VersionScheme` and `release.sh` share them); link rejection (`releaseUrl` or any `url` outside this repository's releases, `http://`, another owner, a look-alike host, user-info, a port, dot-segments such as `{repoUrl}/releases/../../../attacker/x/releases/download/v1/evil.apk` and their `%2e%2e` forms, a tag other than `v{versionName}`, a `url` whose last segment differs from `file`); the opened URL is the canonical `HttpUrl.toString()`; ABI selection by `SUPPORTED_ABIS[0]` (`apk == null` when absent); unknown ABIs ignored; > 64 KB rejected | M11a |
| `GitHubUpdateSourceTest` | `:core:data`, JVM + MockWebServer (hosts rewritten) | the two-hop redirect; 404 → `MANIFEST_INVALID`; 403/429 → `RATE_LIMITED`; I/O → `NETWORK`; link rejection end to end (dot-segments included); an interceptor fails the test on any request to `api.github.com` | M11a |
| `AppUpdateCheckerTest` | `:core:data`, Robolectric + WorkManager test driver + MockWebServer + `TestClock` | the state rules; checks off → `Disabled(CHECKS_OFF)` and no work scheduled; `checkNow` with checks off makes exactly one request, a repeat within 60 s none; `checkNow` offline (`FakeNetworkMonitor` disconnected) → `Failed(NETWORK)` at once (or `Available` with `lastError = NETWORK`), no request and no work enqueued; `checkNow` against a 429 → `Failed(RATE_LIMITED)`, one request, no retry; a server that never answers → `NETWORK` after 30 s (`TestClock`) and the work cancelled; skip → `Idle`, notification cancelled, a higher `versionCode` shows again; notification once per version, only from the scheduled check, never with `minSdk > SDK_INT`; a failed check keeps `Available` with `lastError`; `Available` restored from `UpdateCheckStore` after a restart without a request; the stored version now running → `Idle`, notification cancelled; dev-tools build → `Disabled(DEV_BUILD)`, `checkNow` refused; no request to `api.github.com` (PLAN M11 AC6) | M11a |
| `UpdateNoticesTest` | `:core:data`, JVM + `TestClock` | `FIRST_RUN_CHOICE` once (fresh install and first update to an M11a build), never in dev-tools builds or with checks already off (then `updates.first_run_choice_done` is set without the card); `VERIFICATION_ENFORCEMENT` not before `NOTICE_FROM`, once at or after it, with the pre-enforcement variant before `GLOBAL_ENFORCEMENT` and the post-enforcement variant at or after it (a fresh install after the date sees it once) | M11a |

Removed with PO-31: `ApkVerifierTest`, `ApkInspectorTest`, `SelfInstallerTest`, `VerificationFailureMapperTest` and `InstallIdleGateTest`.

### Device checklist (M11a)

Recorded in the M11a release issue; uses two consecutive releases (normal releases, so `releases/latest` serves the newer one), PLAN M11 AC3 and AC6:

- With the older release installed and checks on, the scheduled check finds the newer release (run it with `adb shell cmd jobscheduler run -f ch.lkmc.neutrodyne <job id>`, the id from `adb shell dumpsys jobscheduler`): exactly one notification for that version, whose tap opens Settings › Updates; the gear badge appears; the card shows the notes, the size and the SHA-256 of the `Build.SUPPORTED_ABIS[0]` APK.
- "Open release on GitHub" opens the tag's release page; "Download APK for this device" downloads that APK in the browser, and its `sha256sum` equals the card's value. Android's installer installs it over the running app on an API 26 and an API 37 device, and the library, groups, positions, Up next and downloads survive (PLAN M11 AC9).
- An `armeabi-v7a` install on a 64-bit phone is linked to the `arm64-v8a` APK, and installing that over it keeps the data.
- "Skip this version" removes the card, the badge and the notification until a newer release.
- "Check for updates" off: no `app-update-check` job is scheduled (`dumpsys jobscheduler`) and nothing is sent to GitHub until "Check now".
- The merged manifest holds no install permission (`permissions.txt`); a network capture of the session shows no request to `api.github.com` ([v1.0 gate](#v10-gate) row 8).
- Obtainium installs the newer release with the per-ABI filter (the help card's instructions).

---

## Reproducible builds

Serves N12 (independent verifiability) and [D79](../PLAN.md#3-key-decisions). Delivered in M0 (hygiene, nightly job). **Report-only, permanently:** no store rebuilds Neutrodyne any more, so nothing gates a release on reproducibility ([D79](../PLAN.md#3-key-decisions)). The nightly check stays because a build anyone can reproduce from a tag is a cheap trust signal next to the attestations. With the signing key in the repository it is a strong one: a rebuild can match the published APK byte for byte, signature included.

### Hygiene

| Source of non-determinism | Mechanism | Owner |
|---|---|---|
| Timestamps, git data in `BuildConfig` | none ever ([01 Build variants and ABIs](01-foundation.md#build-variants-and-abis)) | 01 |
| Build-time secrets | PR, nightly and repro builds read **no** `-P` secret: `neutrodyne.acraMailto`, `neutrodyne.repoUrl` and `neutrodyne.engineManifestUrl` are committed in `gradle.properties`. After Podcast Index's written permission (PO-3 option A) `release.yml` injects its key into the published builds only; a published APK then differs from a rebuild of its tag in `BuildConfig` and therefore in its signature (when option A is adopted, the README's "Check it" item and the [release body](#release-body) template gain a sentence saying so; until then nothing differs, [D79](../PLAN.md#3-key-decisions)) | 01, 09 |
| Signing | every build, the repro job's included, is signed with the committed keystore through `neutrodyneDebug` ([Gradle signing configuration](#gradle-signing-configuration)), so the job compares **signed** APKs. Unverified until the job shows it: that AGP's v2/v3 signing produces identical bytes for identical input with this RSA key; if not, the job compares everything except the APK Signing Block and reports that | 09 |
| PNG crunching | `buildTypes.debug { isCrunchPngs = false }` in `:app` (`benchmark` inherits it through `initWith`); AGP documents crunching as off by default for `debug` ([AGP DSL `BuildType`](https://google.github.io/android-gradle-dsl/3.4/com.android.build.gradle.internal.dsl.BuildType.html), an old DSL version), so it is set explicitly anyway; PNGs committed pre-optimised | 09 (01's build-type sketch delegates it here) |
| VCS info | `buildTypes.debug { vcsInfo { include = false } }` in `:app` (a rebuild from a source archive has no `.git`; nothing depends on it). Unverified whether AGP adds `META-INF/version-control-info.textproto` to debug builds at all; `check-apk.sh` checks that it is absent | 09 (delegated by 01) |
| AboutLibraries metadata | offline mode: no remote licence or funding fetches during the build (Unverified 15.x property names, e.g. `offlineMode = true`, `fetchRemoteLicense = false`) | 01 |
| R8 non-determinism around kotlinx.coroutines | applies only to the minified `benchmark` build, which is never published; kept as a note for a later release build: if it ever matters, `-keep class kotlinx.coroutines.CoroutineExceptionHandler`, `-keep class kotlinx.coroutines.internal.MainDispatcherFactory` in `app/src/main/keepRules/app.keep` ([01 Build variants and ABIs](01-foundation.md#build-variants-and-abis)) | 01 |
| Python bytecode | Chaquopy compiles the shim to `.pyc` at build time with the container's Python (the standard library arrives precompiled from Maven Central; yt-dlp is compiled on the device, 04); CPython writes timestamp-based `.pyc` headers unless `SOURCE_DATE_EPOCH` is set, in which case it writes checked-hash `.pyc` ([py_compile](https://docs.python.org/3/library/py_compile.html)). The container sets `SOURCE_DATE_EPOCH` to the tagged commit's time (Unverified: that Chaquopy's compile step honours it; the job reports it) | 09 |
| Vendored engine and Python runtime | the yt-dlp asset is copied byte for byte from `youtube/ytdlp/engine/`; Chaquopy's runtime comes from Maven Central with verification metadata (01) | 01, 04 |
| Compiled ART profile (`assets/dexopt/baseline.prof`/`.profm`) | none: no baseline profile exists, and the published debuggable build would not use one ([Macrobenchmark and profiles](#macrobenchmark-and-profiles)); a note for a later non-debuggable release build | 09 |
| Toolchain | the [release container](#releaseyml): Debian 13 with CPython 3.14 (`python:3.14-slim-trixie@sha256:<digest>`) and Debian's OpenJDK 21; `build-tools;36.0.0` and `platforms;android-37` pinned by `install-android-sdk.sh`; Gradle 9.7.1 with `distributionSha256Sum` | 09 |
| Build cache, locale, time zone | `--no-build-cache`; `LC_ALL=C.UTF-8`, `TZ=UTC` in the container | 09 |
| `dependenciesInfo` signing block | disabled: it is an encrypted block only Google Play reads | 01 |

Unverified: whether the `platforms;android-37` revision can change under the same name and alter the output; the repro job records `source.properties` of the platform in its log.

### Nightly reproducibility job

1. Start two containers from the release container image (digest pinned, Renovate-managed) with different checkout paths (`/build/a` and `/home/builder/src/neutrodyne`), different CPU counts (`--cpus=2` and `--cpus=4`) and different umasks (022, 002).
2. In each: install `openjdk-21-jdk-headless git unzip curl`, run `install-android-sdk.sh`, then `./gradlew --no-daemon --no-build-cache assembleDebug` (the three ABI APKs, signed with the committed keystore).
3. Compare the SHA-256 of each pair of APKs; on a difference run `diffoscope` and upload its HTML report.
4. Report-only: a difference opens or updates one issue (label `repro`, through `report-nightly.sh`), never a `release-blocker`; a fix ships with the next regular release.

Anyone can repeat the comparison for a tag: build it with `scripts/ci/repro-build.sh assembleDebug` and compare the whole APK — signature included, since the key is in the repository — with the published APK. After PO-3 option A, the Podcast Index fields in `BuildConfig` (and with them the signature) differ, so the comparison then excludes the APK Signing Block and `BuildConfig`.

---

## Developer verification

Serves R6.1, R6.4; mitigates risks P3, P8, P9. Delivered in M0 (README "Install and update" first draft, including the debug-build and public-key statement), M11a (in-app help, pre-enforcement notice, README final), M11b (review against Google's then-current rules). Honours [D80](../PLAN.md#3-key-decisions), [PO-5](../PLAN.md#po-5-google-developer-verification) (resolved 2026-10-05: **do not register**), [PO-36](../PLAN.md#48-further-product-owner-decisions), [D61](../PLAN.md#3-key-decisions), [D78](../PLAN.md#3-key-decisions).

Neutrodyne does not register with Google's Android developer verification: registering would tie a legal identity to an app that extracts YouTube streams ([D80](../PLAN.md#3-key-decisions)). This section owns the facts that the README's "Install and update" section and 08's help page and notice rely on. The app installs nothing itself ([Update check](#update-check)), so every install and update goes through Android's own installer, which shows whatever the verification policy requires; the help page explains it. All facts checked 2026-10-05 against Google's pages ([overview](https://developer.android.com/developer-verification), [guides](https://developer.android.com/developer-verification/guides), [FAQ](https://developer.android.com/developer-verification/guides/faq), [Help Center](https://support.google.com/android/answer/17065026?hl=en), [advanced flow](https://support.google.com/android/answer/17588095?hl=en), [blog 2026-03-19](https://android-developers.googleblog.com/2026/03/android-developer-verification.html)).

### Phases and devices

| Phase | When | Effect on a Neutrodyne installed from GitHub |
|---|---|---|
| Tooling | March–August 2026 | none; the "advanced flow" for unverified apps launched gradually in August 2026 |
| First enforcement | since 2026-09-30 | none: it covers certified devices in Brazil, Indonesia, Singapore and Thailand and only installs from seven participating stores (Google Play, HONOR App Market, OPPO App Market, Galaxy Store, Palm Store, V-Appstore, GetApps); "if users sideload your app directly, these new verification requirements won't apply to your app yet" ([guides](https://developer.android.com/developer-verification/guides), [FAQ](https://developer.android.com/developer-verification/guides/faq)). Tester builds since M0 are unaffected everywhere |
| Global rollout | "2027"; no date published (Unverified whether it is staged by region or source) | on certified devices every **new install and every update** of an unregistered app is blocked unless the user turned on the advanced flow or installs over ADB |

Affected: certified Android devices with Google Play services (phones and tablets; Android 8 and up per the Help Center, "Android 7+" on the developer site — minSdk 26 is covered by both). Not affected: AOSP and non-certified devices — GrapheneOS, LineageOS without Google apps ([LineageOS statement](https://lineageos.org/Developer-Verification/)), /e/OS, Huawei and Fire OS devices — and regions where Google Mobile Services are unsupported ([Help Center](https://support.google.com/android/answer/17065026?hl=en)). The source of the APK does not matter: an APK the user downloaded from GitHub through the update card, one opened from Files and one installed by Obtainium are all gated the same way. YouTube-engine updates are files the app downloads, not installs, so they keep reaching users whose APK updates are blocked ([04 Engine updates](04-youtube.md#engine-updates)).

### Advanced flow

The one-time device setting Google provides for unverified apps ([Help Center](https://support.google.com/android/answer/17588095?hl=en), [blog](https://android-developers.googleblog.com/2026/03/android-developer-verification.html)):

1. Turn on Developer options (Settings › About phone › tap Build number 7 times).
2. Settings › System › Developer options › "Allow apps from unverified developers".
3. Confirm that nobody is guiding you through this (Google's anti-coercion check) and re-authenticate.
4. The phone restarts.
5. After a one-time 24-hour wait, confirm with fingerprint, face or PIN.
6. Choose **"7 days"** or **"indefinitely"**.
7. Each install or update of an unverified app then shows a warning with "Install anyway".

Consequences the guidance must state: with "7 days", or after switching the setting off, **updates of unregistered apps fail again** ([FAQ](https://developer.android.com/developer-verification/guides/faq)) — the "7 days" trap, which is why every text recommends "indefinitely"; Developer options can be switched off afterwards; the flow is delivered through Google's components and can change without an Android update (risk P8). Unverified: whether the setting is per Android user or profile, and whether re-enabling after "7 days" repeats the wait.

| Situation (from Google's global rollout) | First install | Update (APK downloaded from GitHub or installed by Obtainium) |
|---|---|---|
| Certified device, advanced flow off or "7 days" expired | blocked | blocked: the installed version keeps running; Android's installer shows its block, and the Install & updates help explains the advanced flow and ADB (08) |
| Certified device, advanced flow "indefinitely" | warning, "Install anyway" | warning per update, "Install anyway" |
| Non-certified device | as today | as today |
| Any device, `adb install -r` | allowed | allowed |

### Exempt and fallback paths

- **ADB:** installs over ADB are exempt by design ("no changes to how ADB works", [FAQ](https://developer.android.com/developer-verification/guides/faq)); in AOSP a session whose caller runs as shell or root is marked `INSTALL_FROM_ADB` and skips the verifier ([PackageInstallerSession](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/pm/PackageInstallerSession.java)). Documented as `adb install -r neutrodyne-{v}-{abi}.apk` for users with a computer — impractical for every update. Because the published APKs are debuggable, the guidance also says to turn USB debugging off again afterwards (risk P11, [Public key trade-offs](#public-key-trade-offs)).
- **Shell-UID installers such as Shizuku** (e.g. Obtainium in Shizuku mode) inherit that exemption in the current AOSP code (Unverified on an enforcing device); Shizuku must be restarted after every reboot through Wireless debugging ([Shizuku](https://shizuku.rikka.app/guide/setup/)) and Google can close the path. Documented as a power-user fallback only, never built into Neutrodyne.
- **Non-certified systems** need nothing; the README says so. Root-based hooks are not documented.
- **Android Auto** is a separate gate for any non-store install: sideloaded media apps appear only after Android Auto's developer setting "Unknown sources" ([Android Authority](https://www.androidauthority.com/sideload-apps-on-android-auto-3681820/); PLAN M5 AC4).

### Package name and key

- Package names are allocated to registered keys: a key with more than 50 % of known installs has priority, any key with at least 50 installs may register, otherwise first come, first served ([package-name rules](https://developer.android.com/developer-verification/guides/android-developer-console)). Someone could register `ch.lkmc.neutrodyne` with another key before Neutrodyne has an install base, and because the committed key is public, anyone can even prove possession of Neutrodyne's own certificate and register that (risk P9). The consequences for our installs are Unverified (most likely they remain "unverified" and need the advanced flow; a stranger's registration of our certificate could also lead Google to block it). Mitigation: public releases build the install cluster quickly; nothing short of registering prevents it.
- Registering ourselves would need a private key Neutrodyne does not have: a new private key, and with it one reinstall for every user ([Public key trade-offs](#public-key-trade-offs), [D61](../PLAN.md#3-key-decisions)). The free limited-distribution account (at most 20 explicitly authorised devices, [limited distribution](https://developer.android.com/developer-verification/guides/limited-distribution)) is not a public-release path.
- Dev-tools builds (`ch.lkmc.neutrodyne.dev`) are installed by developers over ADB and are exempt.

### Watch and notice timing

- **Watch:** a recurring calendar issue every 2 weeks (label `verification-watch`) re-reads the overview, guides, FAQ and Help Center pages and Google's Android Developers Blog. A change (a global date, a different advanced flow, new exemptions) updates this section, the README section, 08's help strings and `VerificationTimeline` in the next release, and is reported to the PO, who may reconsider registration (risk P8; it would need a private key, [Package name and key](#package-name-and-key)).
- **Notice timing ([PO-36](../PLAN.md#48-further-product-owner-decisions)):** the one-time in-app notice (08's `VerificationNoticeKey`, raised by [Notices](#notices)) appears from 2026-12-01, or from the date Google names for the global rollout if that is earlier; it is a date check on the device, so builds released before December also show it. Neutral tone: what the advanced flow costs (a restart and a 24-hour wait), why "indefinitely", that non-certified systems are unaffected; no countdown, no urgency, no blame. After the global date the same one-time notice switches to 08's post-enforcement hint: every installation that has not seen the notice — in practice each fresh install, made through the advanced flow — is told once that "7 days" stops updates after a week and how to switch to "indefinitely"; the help page leads with the Google Play card (08). M11a is small and needs only M2 and M0 (PLAN 7.1), so the pre-enforcement notice can reach users well before Google's date; the post-enforcement hint covers installations made afterwards.
- **Support:** a pinned GitHub discussion or issue "Updates stopped working?" links the README section when enforcement starts.
- **Testing:** CI never sees enforcement — Gradle Managed Devices and emulators install over ADB, which is exempt — and the app has no install path of its own to test. The help text and the notice are checked by hand on an enforcing certified device once the global rollout starts (2027): a fresh install and an update downloaded from the update card, with the advanced flow off, on an expired "7 days" and on "indefinitely". `UpdateNoticesTest` covers the notice dates.

### README "Install and update"

The README section's content is owned here (the README is maintained with the PLAN); 08's help page mirrors it and the [release checklist](#minor-and-stable-release-additions) compares both. Draft (M0, finalised in M11a; `{…}` filled in):

1. **Download only from GitHub:** `{repoUrl}/releases`. There is no Play Store version; any other copy is not Neutrodyne's. Pick `neutrodyne-{v}-arm64-v8a.apk` for most phones and tablets, `-x86_64.apk` for x86_64 devices, `-armeabi-v7a.apk` for older 32-bit phones (YouTube episodes then open in the YouTube app).
2. **Check it (optional):** Neutrodyne's APKs are debug builds signed with a key that is public in this repository, so the signature doesn't prove who built a file. Download only from this repository's releases page and compare the file with the release's `SHA256SUMS` (`sha256sum --check --ignore-missing SHA256SUMS`), or, with the GitHub CLI, run `gh release verify-asset {tag} {file} -R {owner}/Neutrodyne` and `gh attestation verify {file} -R {owner}/Neutrodyne`.
3. **Allow the install:** Android asks once whether your browser (or Files app) may install apps.
4. **Phones with Google Play, from 2027:** Android will install apps only from developers registered with Google unless you turn on a one-time setting. Neutrodyne is not registered (why: below). Developer options › "Allow apps from unverified developers"; follow the steps (restart, 24-hour wait, fingerprint or PIN); choose **indefinitely** — with "7 days", updates stop working after a week; each install or update then shows a warning: tap "Install anyway". You can turn Developer options off afterwards. Nothing changes before Google's global start.
5. **Phones without Google certification** (GrapheneOS, LineageOS without Google apps, /e/OS) need none of this.
6. **Other ways (advanced):** `adb install -r {file}` from a computer; installer apps that work through Shizuku. Both may stop working if Google changes its rules. Turn USB debugging off again after an `adb install` (item 8).
7. **Updates:** Neutrodyne checks GitHub once a day and, when a new version exists, notifies you and links to it (Settings › Updates › Check for updates); download the APK for your phone and install it over the old one — your library stays. Or use Obtainium with an APK filter for your file (e.g. `neutrodyne-.*-arm64-v8a\.apk$`) and turn Neutrodyne's own check off. YouTube engine updates arrive separately and need no install.
8. **About these builds:** the maintainers publish debug builds. That means: (a) the signing key is public, so anyone can sign an APK that installs over Neutrodyne and takes over its data — download only from this repository's releases page; (b) anyone with USB-debugging access to your unlocked, authorised phone can read and change Neutrodyne's data, including your subscriptions, listening history and the passwords of private feeds, so turn USB debugging off when you don't need it; (c) scrolling and start-up are less smooth than a release build would be. A later switch to a private key would need one reinstall, so keep a backup. Details: [Public key trade-offs](#public-key-trade-offs).
9. **Android Auto:** enable "Unknown sources" in Android Auto's developer settings.
10. **Changing phones:** make a manual backup first (Settings › Backup) and restore it on the new phone. Setting up a new phone does not reinstall Neutrodyne; Android may restore your library when you install it there, but that is not guaranteed (PLAN R1.8, [05 Auto Backup](05-groups-opml-backup.md#auto-backup)).
11. **Why Neutrodyne is not registered:** registering would tie a legal identity to the app; the maintainers decided not to. One factual paragraph, no campaigning ([PO-36](../PLAN.md#48-further-product-owner-decisions)).

Wording rules: Google built the anti-coercion check against scammers who coach victims through such steps, and this guidance is legitimately similar, so it stays factual — it says what the setting turns off, never urges haste, never addresses a user mid-install with a countdown. Item 8 states the trade-offs neutrally, as the owner's choice, without alarm.

---

## Privacy

Serves N3. Delivered in M0 (`PRIVACY.md` v0, `SECURITY.md`), updated whenever a document adds a network destination (M7 directories, M8/M9a YouTube, M9b engine updates, M11a update check), final in M11b. Honours [D62](../PLAN.md#3-key-decisions), [D76](../PLAN.md#3-key-decisions), [D78](../PLAN.md#3-key-decisions), [PO-31](../PLAN.md#48-further-product-owner-decisions), [PO-32](../PLAN.md#48-further-product-owner-decisions).

### Commitments

No analytics, advertising, tracking, Firebase or Google Play services in any APK (Chromecast is not planned because it would need them, PO-6); no accounts; no Neutrodyne server; network traffic only to hosts the user chose or opted into — including GitHub for the app's update check and for YouTube-engine updates, both disclosed and each with an Off switch (PO-31, PO-32); crash reports leave the device only through the user's own mail app after per-crash consent; private feed URLs and tokens never appear in logs, crash reports, diagnostics or directory queries.

### `PRIVACY.md` outline

1. Summary (the commitments above, in plain words).
2. What stays on the device: library, groups, history, positions, Up next, downloads, settings; credentials encrypted with an Android Keystore key.
3. Where the app connects ([inventory](#network-inventory)) and what those hosts receive.
4. Backups: Android Auto Backup carries a daily library snapshot (feed URLs included, possibly with private tokens) to the user's Google account, only on devices with backup encryption (PO-15); manual backup files contain passwords only on opt-in (R1.9).
5. Crash reports and diagnostics: contents, consent, how to request deletion of an emailed report.
6. Permissions and why (from 01's table).
7. What the YouTube engine and the update check contact and how to turn them off (Settings › YouTube "Play YouTube in the app" and "Engine updates"; Settings › Updates › "Check for updates"; external mode on the `armeabi-v7a` APK). The app downloads no APK: an update is downloaded by the user's browser from GitHub.
8. About the published builds: they are debug builds, so anyone with USB-debugging access to an unlocked, authorised phone can read and change the app's data, including the passwords of private feeds (PLAN risk P11); turn USB debugging off when not needed.
9. Contact and change history (git log of `PRIVACY.md`).

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
| `podcastindex` | `api.podcastindex.org` | search, trending | with a user key, or with a project key injected into the published builds after Podcast Index's written permission ([PO-3](../PLAN.md#po-3-podcast-index-api-key-handling) option A); then also the YouTube preview check | all | off while no key exists (PO-3 default B); on once a project key exists; Settings › Discover switch (`discover.podcastindex_enabled`, 03) |
| `youtube-subscriptions` | `www.youtube.com` (feeds, channel page head, oEmbed), `i.ytimg.com`, `yt3.googleusercontent.com`, `yt3.ggpht.com` | YouTube channels as podcasts, layer A ([04](04-youtube.md#channel-resolution)) | only when the user adds or has a YouTube channel | all | user action |
| `youtube-streams` | `www.youtube.com` (watch page, `/youtubei/…` InnerTube requests made by yt-dlp through our OkHttp client in `:ytx`), `*.googlevideo.com` (media, from the main process) (Unverified complete host list; the M9a network capture records it) | audio streams, downloads, durations and flags, channel lookup, back catalogue, channel search — layer B ([04 YouTube engine](04-youtube.md#youtube-engine)) | playing, downloading or refreshing YouTube items; channel search | with engine | user action; Settings › YouTube "Play YouTube in the app" turns it off |
| `youtube-engine` | `<owner>.github.io` (approved engine manifest), `github.com` (`yt-dlp/yt-dlp` release files), `release-assets.githubusercontent.com` | YouTube-engine updates without an app update ([04 Engine updates](04-youtube.md#engine-updates)) | daily, after a circuit-breaker opening (at most every 3 h), "Check for engine update"; never with policy Off | with engine | on (policy Neutrodyne-approved, PO-32) |
| `app-updates` | `github.com` (`<owner>/Neutrodyne/releases/latest/download/neutrodyne-update.json`, which redirects to the release asset), `release-assets.githubusercontent.com` | the update check: one manifest GET per check ([Update check](#update-check)); the APK itself is downloaded by the browser when the user taps the update card's link (`links`) | daily while "Check for updates" is on; "Check now" | all | on (`updates.check_enabled`, PO-31; first-run card with one-tap "Turn off"); off in dev-tools builds |
| `links` | any link the user taps (episode page, funding, person, the update card's "Open release on GitHub" and "Download APK for this device") | opened in the browser | tap | all | user action |
| `issue-tracker` | `github.com` | "Report a problem" opens the browser | tap | all | user action |
| — | Neutrodyne servers, analytics, ads, Google Play services, `api.github.com` | — | never | — | — |

What hosts receive: every request carries the device's IP address and `User-Agent: Neutrodyne/<versionName> (Android <release>; +<repo URL>)` (no device or install identifiers, [01 Interceptors](01-foundation.md#interceptors)); feed requests carry stored `If-None-Match`/`If-Modified-Since`; private feeds and their same-origin enclosures carry Basic credentials (never across origins); directories receive only the query text and country ([03 Privacy](03-feeds-and-discovery.md#privacy)); YouTube receives the consent cookie `SOCS=CAE=` and, with the engine, what yt-dlp's InnerTube clients send (a client-specific User-Agent such as Safari for `visionos`, the video or channel ID, the app locale as `hl`/`gl`; cookies live only in `:ytx` memory, [04 Networking bridge](04-youtube.md#networking-bridge)); GitHub (the app's update check, and the engine's update checks and downloads) sees the IP address and the User-Agent, no identifier. Publishers' measurement redirects can count downloads by IP and user agent; the app adds nothing to help or hinder that. Outside the app's control and disclosed: Android's own Auto Backup transfer, system DNS (Private DNS honoured), and the user's mail app for crash reports.

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

`SECURITY.md`: report vulnerabilities through GitHub private vulnerability reporting; acknowledgement within 7 days; fixes ship as PATCH releases through the normal pipeline; scope includes parsing of feeds, OPML, backups and import files (N9), the exported `ArtworkProvider`, intent handling, credential storage, the update check's manifest and link validation ([Update manifest](#update-manifest)) and the engine-update trust chain ([04 Trust chain](04-youtube.md#trust-chain)). The APK signing key is public by design ([Public key trade-offs](#public-key-trade-offs)), so a report of an APK signed with it but offered elsewhere is answered with the download guidance, not as a key compromise; a suspected engine-manifest-key compromise follows the rotation in [engine-canary.yml](#engine-canaryyml).

---

## Crash reporting and diagnostics

Serves N3, N2 (diagnosability). Delivered in M0 (ACRA wiring, disabled until PO-10 names a mailbox), M9a (engine health lines), M11a (update-check lines), M11b (final configuration, diagnostics screen). Honours [D62](../PLAN.md#3-key-decisions), [PO-10](../PLAN.md#48-further-product-owner-decisions) default.

### ACRA configuration

ACRA 5.14.2 with `acra-mail` and `acra-dialog`: zero network traffic from the reporter. `installAcra` is called from `NeutrodyneApplication.attachBaseContext` when `BuildConfig.ACRA_MAILTO` is not empty, the process is not `:ytx` and the system property `neutrodyne.instrumentedTest` is not set (only `:app`'s instrumented tests set it; the check sits at 01's call site, not inside `installAcra`, so `YtxIsolationTest` can call `installAcra` itself with a test address, [Gradle Managed Devices](#gradle-managed-devices)); the `:acra` process returns early from `onCreate` ([01 Application start-up](01-foundation.md#application-start-up)). ACRA is never installed in `:ytx` ([D62](../PLAN.md#3-key-decisions)): an engine crash or hang is recorded by the main process as engine health, never offered to the user as a crash report (PLAN M9 AC5). `ACRA_MAILTO` comes from the committed `neutrodyne.acraMailto` ([Hygiene](#hygiene)) and is forced to `""` **only in dev-tools builds**, by `buildConfigField("String", "ACRA_MAILTO", "\"\"")` in the dev-tools lines of `buildTypes.debug` ([01 Dev-tools switch](01-foundation.md#dev-tools-switch); a build-type field overrides `defaultConfig`'s; dev-tools builds crash fast with StrictMode and LeakCanary instead). ACRA, crash reporting and diagnostics are therefore active in every published debug build ([D62](../PLAN.md#3-key-decisions)) once PO-10 names the mailbox.

```kotlin
// :app
fun installAcra(app: Application, mailTo: String = BuildConfig.ACRA_MAILTO) = app.initAcra {   // mailTo: YtxIsolationTest only
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
        this.mailTo = mailTo
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
interface DiagnosticsContributor {                    // @IntoSet from :core:data (update check included), :download:impl, :playback:impl, :youtube:ytdlp, :app
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
| `APP` | version name/code, build (published `debug` or dev-tools, from `BuildInfo.devTools`), Android release and SDK, manufacturer and model, the APK's ABI (`BuildInfo.apkAbi`) and the device's `SUPPORTED_ABIS`, app locales, SQLite version (`sqlite_version()`), Media3 version, installer package (`getInstallSourceInfo`, informational), first 8 hex digits of the signing certificate SHA-256 (the public key's value on every genuine build, so a different value means a rebuild with another key — the same value proves nothing, [Public key trade-offs](#public-key-trade-offs)); update check: "Check for updates" on or off, last check time and result, available version (from `:core:data`'s update check) | `:app`, 09's update check in `:core:data` | installer: 30+ |
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

- **Hosted Weblate, Libre plan** (free for public libre projects), project `neutrodyne`, created at the start of M11 at the latest (PLAN M11 deliverable); opening it at the end of M10, once M10's string changes have landed, is preferred so translators have the whole M11 tester-build period to reach PO-14's 90 % threshold.
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
- `:app` reads the file into `androidResources.localeFilters` (plus `en-rXA` and `ar-rXB` only in dev-tools builds, the only `:app` builds that generate pseudo-locales; the published debug build generates none and filters out any a library generated, and `check-apk.sh --published` asserts with `aapt2 dump xmltree` and `aapt2 dump configurations` that the published APK carries neither, [Build-output checks](#build-output-checks)) and generates `BuildInfo.shippedLocales` (request to 01) for 08's in-app picker (Appearance › Language), which calls `AppCompatDelegate.setApplicationLocales(…)`. `generateLocaleConfig = true` with `res/resources.properties` (`unqualifiedResLocale=en-US`) lists the same locales for Android 13+'s system app-language settings ([per-app languages](https://developer.android.com/guide/topics/resources/app-languages)). Unverified: that `generateLocaleConfig` honours `localeFilters` and ignores library translations (M0 check; fallback: generate `res/xml/locales_config.xml` from `locales.txt` and turn `generateLocaleConfig` off).
- Partially translated languages stay in the repository but are filtered out of the APK until they reach the threshold; missing strings in shipped languages fall back to English.

### Pseudo-locales and RTL

`isPseudoLocalesEnabled = true` on the `debug` build type of the library modules with screenshot tests, and in `:app` only with the dev-tools switch (`debug` is the published build type, [01 Dev-tools switch](01-foundation.md#dev-tools-switch)); Roborazzi captures `en-XA` (long accented text) and `ar-XB` (RTL) in those library modules per [Compose UI and screenshot tests](#compose-ui-and-screenshot-tests); `android:supportsRtl="true"` (01); a manual Arabic pass and a 200 % font pass on device per release (08's manual checks).

---

## Performance budgets

Serves N5, R2.9; mitigates risks T6, T15, T18. Delivered in M0 (per-ABI size checks of the published debug APKs), M2 (query timing), M9a (engine budgets, measured by the first-week spike), M10 (grid jank on `benchmark`, report-only on the published build), M11b (startup on `benchmark`, report-only measurements of the published build, all budgets re-measured). Journeys are defined by [08 Performance journeys](08-ui-ux.md#performance-journeys); seeded data by 02's `SeedDatabase` (300 podcasts, 50,000 episodes, 20 groups). Honours [D2](../PLAN.md#3-key-decisions), [PO-35](../PLAN.md#48-further-product-owner-decisions).

**Where N5 is measured.** Every published APK is a debug build ([D2](../PLAN.md#3-key-decisions)): debuggable, without R8 and without a baseline profile. Macrobenchmark refuses debuggable targets unless its `DEBUGGABLE` error is suppressed, because debuggability "drastically reduces runtime performance" ([Macrobenchmark instrumentation arguments](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args), [Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)). So:

- Cold start and jank (PB1–PB5, PB15) are measured and gated on `:app`'s non-debuggable, profileable, R8-minified **`benchmark`** build type, which is never published. It keeps the code itself fast and is what a later release build would ship.
- The **published** debug build's cold start and grid jank are recorded **report-only** (PB22, PB23; risk [T18](../PLAN.md#8-risks-and-mitigations)), so the gap users feel is known. It is expected to be noticeably less smooth: ART's debuggable mode relies on JIT ([ART change](https://android.googlesource.com/platform/art/+/a0619e2%5E%21/); current ART Service forces safe mode on debuggable apps because "the runtime ignores their compiled code", [Dexopter.java](https://android.googlesource.com/platform/art/+/refs/heads/main/libartservice/service/java/com/android/server/art/Dexopter.java)), the framework turns on CheckJNI for debuggable apps ([ProcessList.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ProcessList.java)), and Compose documents a performance cost in debug mode ([Compose performance](https://developer.android.com/develop/ui/compose/performance)).
- APK sizes (PB12, PB13) and the YouTube engine budgets (PB18–PB21) are measured on the published APKs. CPython itself is native code, but Chaquopy's Java↔Python bridge and `PyHttp` run JIT-only and under CheckJNI there, so PB18–PB21 include the debuggable overhead; the `benchmark` comparison rows of 04 show its size.
- **Compilation mode:** no baseline profile exists ([Macrobenchmark and profiles](#macrobenchmark-and-profiles)). The gated `benchmark` runs therefore use `CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable, warmupIterations = 3)`: warm-up runs, then compilation of the JIT profile they recorded. That approximates an install after a few days of use and background dexopt (Unverified for a sideloaded install). The same journeys also report `CompilationMode.None()` (the first starts after an install) without a budget. Partial with `BaselineProfileMode.Disable` needs a non-zero `warmupIterations` ([CompilationMode.Partial](https://developer.android.com/reference/kotlin/androidx/benchmark/macro/CompilationMode.Partial)).

### Reference devices

| Role | Device | Use |
|---|---|---|
| Reference (N5 "PO-agreed mid-range phone") | default until the PO names one: Google Pixel 7a on Android 16 or later, **dedicated to testing** — Macrobenchmark and connected runs clear and uninstall `ch.lkmc.neutrodyne` ([01 Dev-tools switch](01-foundation.md#dev-tools-switch)) | every budget below (the `benchmark` build for PB1–PB5, the published APKs for the rest and for PB22/PB23); Macrobenchmark runs before each minor release |
| Floor | any 2–3 GB RAM device on API 26–28 | functional smoke and scroll feel of the published APK only, no numeric budgets; `:ytx` start on a low-memory device (informational) |
| 32-bit | any `armeabi-v7a`-only device | the `armeabi-v7a` APK: external mode with its reason (PLAN M9 AC8), functional only |
| CI emulators | GMD `api36`, `bench34` | trends and dry runs only; never gate on emulator timings (shared VMs are noisy) |

### Budgets

| ID | Metric | Budget | Measured by | Gate |
|---|---|---|---|---|
| PB1 | Cold start to Feeds, time to initial display, p50 | < 600 ms (N5) | `ColdStartToFeeds` on `benchmark`: `StartupTimingMetric`, `StartupMode.COLD`, 15 iterations, `CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3)` | v1.0 (M11b, PLAN M11 AC1) |
| PB2 | Same, p90 | < 900 ms | same | soft (investigate) |
| PB3 | Cover-grid fling jank | < 1 % of frames late, measured as `frameOverrunMs` P99 ≤ 0 ms (Macrobenchmark reports percentiles, not shares; P99 ≤ 0 means at most 1 % of frames overran) | `CoverGridFling` on `benchmark`: `FrameTimingMetric`, 5 iterations × 3 flings over 300 tiles | M10 AC7, v1.0 (M11b) |
| PB4 | All-feed fling and group-pager swipe jank | `frameOverrunMs` P99 ≤ 0 ms | `AllFeedFling`, `GroupPagerSwipe` on `benchmark` | v1.0 (M11b) |
| PB5 | Player expand/collapse jank | `frameOverrunMs` P99 ≤ 0 ms | `PlayerExpandCollapse` on `benchmark` | soft |
| PB6 | Group feed first page (count + 80 rows) | ≤ 60 ms | 02's `FeedQueryTimingTest`, median of 20 | M2 AC2 on the reference device; CI records on GMD |
| PB7 | All feed first page | ≤ 100 ms | same | same |
| PB8 | Subsequent page load | ≤ 20 ms | same | same |
| PB9 | Podcast screen open to content | < 300 ms | manual trace on the reference device (M1 AC5); from M11 `PodcastOpen` journey with a trace section on `benchmark` | M1 manual, v1.0 soft |
| PB10 | 300-feed OPML → 300 pending tiles | ≤ 2 s (R1.3) | 05's import test on device | M3 |
| PB11 | Parse the 831-item 3.5 MB feed | < 1 s on the JVM | 03's corpus test | every PR |
| PB12 | `arm64-v8a` and `x86_64` published debug APKs, each (with the YouTube engine) | < 60 MB (N5; Unverified estimate until S7 measures it in M0) | `check-apk.sh --published` | every PR, every release |
| PB13 | `armeabi-v7a` published debug APK (no engine runtime; counts any unusable Python assets ABI splits leave in it, ≈ 12–13 MB Unverified, [01 S7](01-foundation.md#s7-chaquopy-under-agp-941)) | < 50 MB (N5; Unverified estimate until S7) | `check-apk.sh --published` | every PR, every release |
| PB14 | Database at the N5 scale | ≤ 100 MB | 02's size measurement | M11 |
| PB15 | Main-process PSS peak during `CoverGridFling` + `PlayerExpandCollapse` (`:ytx` excluded, PB20) | ≤ 250 MB (starting value, Unverified) | `MemoryUsageMetric(Mode.Max)` on `benchmark` (experimental Macrobenchmark metric; fallback `dumpsys meminfo ch.lkmc.neutrodyne` in `teardownBlock`); the published build's value is recorded beside PB23 | soft |
| PB16 | 300-feed refresh with all feeds answering 304 | ≤ 3 min on Wi-Fi | 03's M11 performance check | soft |
| PB17 | Splash hold | ≤ 400 ms | 01's start-up rule | M0 |
| PB18 | Cold YouTube resolve (`:ytx` not running), p50 over 20 videos | ≤ 3 s (N5) | [04 Spike results](04-youtube.md#spike-results) procedure on the reference device with the published `arm64-v8a` APK: time from the `YtDlpClient` call to its result, `:ytx` killed before each video | M9 AC4 (M9a spike); soft at v1.0 |
| PB19 | Warm YouTube resolve (`:ytx` running), p50 | ≤ 1.5 s (N5) | same, `:ytx` kept alive | M9 AC4; soft at v1.0 |
| PB20 | `:ytx` PSS while alive, idle and peak during a resolve | ≤ 90 MB (N5) | `dumpsys meminfo ch.lkmc.neutrodyne:ytx` during the spike's runs (published `arm64-v8a` APK) | M9 AC4; soft at v1.0 |
| PB21 | `:ytx` gone after the last call | ≤ 3 min (N5) | `YtDlpClientTest` with `TestClock` (every PR); on the device `adb shell pidof ch.lkmc.neutrodyne:ytx` returns nothing 3 min + 10 s after the last call | every PR (logic), M9 AC4 (device) |
| PB22 | Published debug build: cold start to Feeds, p50 and p90 | none — report-only (risk T18) | `ColdStartToFeeds` on the published `arm64-v8a` APK, `androidx.benchmark.suppressErrors=DEBUGGABLE` (Unverified whether `NOT-PROFILEABLE` must be suppressed too), `CompilationMode.None()` (a debuggable app runs JIT-only anyway) | recorded in the M11b release issue (PLAN M11 AC1) |
| PB23 | Published debug build: cover-grid fling `frameOverrunMs` P50, P90 and P99 | none — report-only (risk T18) | `CoverGridFling` on the published `arm64-v8a` APK, same suppression | recorded with M10 AC7 and in M11b |

Budget changes are PO decisions (N5) and are recorded in this table. PB12 and PB13 are Unverified estimates for unminified debug builds (≈ 10–20 MB more dex and resources than a minified build); S7 measures them in M0, together with the `benchmark` sizes for comparison, and a miss goes to the PO (PLAN M0 AC1). The engine budgets PB18–PB20 start as estimates (≈ 15–22 MB of engine per 64-bit APK, ≈ 70 MB in `:ytx`, 1–3 s first resolve, [04 Host and packaging](04-youtube.md#host-and-packaging)); a miss in the M9a spike leads to 04's fallbacks or a PO amendment. PB1 must stay unchanged with the engine: Python never starts in the main process ([D73](../PLAN.md#3-key-decisions)). PB22 and PB23 have no budget by design: the owner chose debug builds knowing they are less smooth, and the numbers keep that trade-off visible.

### Macrobenchmark and profiles

- `:benchmark` (from M10 for the journeys; the module exists since M6) applies `com.android.test` only — **no `androidx.baselineprofile` plugin** — with `targetProjectPath = ":app"`, self-instrumentation (`experimentalProperties["android.experimental.self-instrumenting"] = true`) and `benchmark-macro-junit4` 1.5.0. It declares a `benchmark` build type (`initWith(getByName("debug"))`, `matchingFallbacks += listOf("release")` for its library dependencies) next to `debug`. The test module builds and tests the app variant with the same build type name ([Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)), so its `benchmark` variant measures `:app`'s `benchmark` build type (PB1–PB5, PB15) and its `debug` variant serves the system tests and the report-only PB22/PB23 runs against the published `debug`. Unverified: that AGP 9.4's `com.android.test` matches the target variant by build-type name unchanged (M10 check; 01's module table points here). Managed device `bench34` (`aosp`, API 34) for `benchmark-dryrun`; the reference device, connected, for measurements.
- **Seeding:** `BenchmarkSeedReceiver` lives in `app/src/benchmark/`, the `benchmark` build type's own source set, never in `debug` ([01 Build variants and ABIs](01-foundation.md#build-variants-and-abis)). It is a receiver without intent filters, `exported="true"` and protected by `android:permission="android.permission.DUMP"` (held by the shell, not by apps), which fills the database with `SeedDatabase` (`benchmarkImplementation(testFixtures(project(":core:database")))`). The benchmark's `setupBlock` sends `am broadcast -n ch.lkmc.neutrodyne/.benchmark.BenchmarkSeedReceiver` once and waits for its marker file. The published APK never contains it (`check-apk.sh --published`). For PB22/PB23 the run seeds through the `benchmark` APK first and then installs the published APK over it with `adb install -r`: same application ID, key and `versionCode`, so the seeded data stays. Unverified: how that sequence fits Gradle's connected test tasks, which uninstall the target after a run ([Gradle forum](https://discuss.gradle.org/t/how-can-i-run-espresso-tests-without-uninstalling-apk-after/15492); Unverified for AGP 9.4's `com.android.test`) (M10 check; fallback: install both APKs by hand and run the journeys with `adb shell am instrument`).
- **Baseline and startup profiles: deferred.** The published APKs are debuggable, so a profile would not be used: ART's debuggable mode relies on JIT, and Google documents profile installation for non-debuggable builds; non-Play installs may not apply profiles at all ([Baseline Profiles overview](https://developer.android.com/topic/performance/baselineprofiles/overview)). Generation (the `androidx.baselineprofile` plugin, `baseline-profile.yml`, startup profiles) returns only with a non-debuggable release build type, if the owner decides on one ([D2](../PLAN.md#3-key-decisions)). `benchmark` deliberately measures without a profile, so PB1 reflects the code, not a profile.
- **Full display:** 08's Feeds route calls `ReportDrawnWhen { first page loaded }` ([08 Performance journeys](08-ui-ux.md#performance-journeys)), so `StartupTimingMetric` also reports time to full display; not budgeted in v1.
- Results (`*-benchmarkData.json`) from the reference device are attached to the release checklist issue; a regression > 10 % against the previous minor release on PB1–PB4 blocks the release until explained. PB22/PB23 are attached beside them.
- R8 (on `benchmark` only, which keeps 01's keep rules tested for a later release build): full mode via `optimization { enable = true }` and `-dontobfuscate` (01); keeps reviewed with the R8 configuration analyzer. Size tips for the published debug APKs when PB12 or PB13 is at risk (resource shrinking needs R8, so it is not available): legacy native packaging (compressed `.so` files, ≈ 6 MB less per 64-bit APK, decided by S7, [01 Build variants and ABIs](01-foundation.md#build-variants-and-abis)); Chaquopy's foreign-ABI assets left in each split (S7 measures them; > 5 MB per APK triggers [D2](../PLAN.md#3-key-decisions)'s ABI-flavor fallback) and its ABI-independent Python assets in the `armeabi-v7a` APK (01 open question 13); `packaging` excludes such as `/DebugProbesKt.bin`, which apply without R8 too.

---

## Release checklist

Serves N1–N12. Copied into `.github/ISSUE_TEMPLATE/release.md`; one issue per release.

### Every release

Before tagging:

- [ ] `scripts/release.sh … --dry-run` prints the expected `versionName`/`versionCode` (no suffix, S = 95).
- [ ] `main` green: `ci.yml` and the last `nightly.yml`, including `no-engine-build` from M9a (no open `release-blocker` issue).
- [ ] `changelogs/<versionCode>.txt` for the new `versionCode` (name it from `release.sh … --dry-run`) merged to `main` through a PR.
- [ ] Weblate PR merged (or explicitly deferred).
- [ ] Any schema change since the last tag has its migration test; the frozen-schema check passes.

Tag and publish:

- [ ] `scripts/release.sh …`; approve the `release` environment; `release.yml` green in < 30 min.
- [ ] The GitHub release is immutable, a normal release (not pre-release), "latest" exactly when its `versionCode` is the highest, and shows the three ABI APKs, `neutrodyne-update.json` and `SHA256SUMS`; `release.yml` step 9 passed, and one APK spot-checked locally with `sha256sum --check`, `gh release verify-asset` and `gh attestation verify`.
- [ ] A test device with the previous release finds the new one through the update check (from M11a: Settings › Updates › Check now) and installs it from "Download APK for this device" with Android's installer, keeping its data; another updates through Obtainium with the per-ABI filter.
- [ ] The last nightly `repro` result is noted (report-only).

### Minor and stable release additions

- [ ] Macrobenchmarks PB1–PB5 on the `benchmark` build on the reference device (a dedicated test device: the runs clear and uninstall Neutrodyne, [01 Dev-tools switch](01-foundation.md#dev-tools-switch)), and PB22/PB23 on the published `arm64-v8a` APK (report-only); results attached; no unexplained regression > 10 % on PB1–PB4; per-ABI sizes (PB12, PB13) from `check-apk.sh --published` attached.
- [ ] `update-shipped-locales.sh` run; `locales.txt` committed.
- [ ] Manual device matrix of [06](06-playback.md#testing) and checklist of [07](07-downloads.md#instrumented-and-device-tests) re-run on the reference device (published `arm64-v8a` APK); external mode checked once on the `armeabi-v7a` APK.
- [ ] 08's manual checks: TalkBack, Switch Access, 200 % font, Arabic RTL, keyboard-only, foldable postures, grid → podcast transition review, airplane mode with downloads.
- [ ] `bmgr` check on a device (05/07).
- [ ] `PRIVACY.md` matches the network inventory (parity test green) and any new destination; when the release adds a destination or changes networking code, `network-capture.sh` re-run and its host list attached.
- [ ] The README's "Install and update" section and 08's Install & updates help page agree with [Developer verification](#developer-verification), including the README's "About these builds" item and the help page's BUILDS card ([Public key trade-offs](#public-key-trade-offs)); the latest `verification-watch` issue is closed; the release body template is current.
- [ ] The engine canary is green and the bundled yt-dlp is the latest approved version, or the difference is explained (04).

### Hotfix (YouTube fast lane)

Engine path first ([04 Hotfix runbook](04-youtube.md#hotfix-runbook)): when the fix is in a yt-dlp **stable** release and the shim needs no change, no APK ships — [engine-canary.yml](#engine-canaryyml) approves the release within 6 h (path 1), or, after re-recorded fixtures, a maintainer dispatches it with `tag` (path 2); apps activate it within 24 h, sooner after a breaker opening (N11). This reaches users whose APK updates Android blocks ([Developer verification](#developer-verification)).

APK path second (path 3: a shim change, a new `SHIM_API_VERSION`, a new pinned key, or the bundled version must move): shim fix PR or `scripts/engine/bump-ytdlp.sh <approved version>` → CI (`verifyBundledYtDlp`, `checkPythonLicences`, `shimTest`, recorded-response tests) → merge → dispatch `nightly.yml` with `scope: youtube-smoke` on `main` (E7 through `:ytx` on the published debug APK and the minified `benchmark` APK) → `release.sh patch --hotfix` → `release.yml`, which publishes a normal release. Skipped for hotfixes: benchmarks, locales, manual matrices. N11's target is < 30 min from **tag** to the published release; the whole APK path is about an hour (PR CI ≈ 15 min, `youtube-smoke` ≈ 12 min, release ≤ 30 min; timeline in 04's runbook). After that the update check announces the release and users install it from GitHub, or Obtainium delivers it.

### Milestone tester build

Per PLAN DoD: the milestone's acceptance criteria are listed in the release issue with the test or manual check that proves each one; tag `0.{n+1}.P` (P = 0 for the milestone's first build; an increment that lands out of order ships as the next PATCH of the current line, [D63](../PLAN.md#3-key-decisions)); an immutable, **normal** release (never pre-release) with the three ABI APKs, `SHA256SUMS`, `neutrodyne-update.json` and attestations, which the update check of every installed copy announces ([Tester builds](#tester-builds)); design documents updated for deviations.

### v1.0 gate

| [PLAN M11](../PLAN.md#m11-release-hardening-and-v10) acceptance | Evidence |
|---|---|
| 1 Cold start p50 < 600 ms on the `benchmark` build; the published debug APKs within the per-ABI budgets; the published build's cold start and grid jank recorded | PB1 Macrobenchmark on `benchmark` on the reference device; PB12/PB13 from `check-apk.sh --published` on the `v1.0.0` APKs; PB22/PB23 in the release issue |
| 2 Tag → immutable, normal release that becomes "latest", with three APKs, `SHA256SUMS`, manifest and notes, in < 30 min; `gh release verify` and `gh attestation verify` pass; the previous release candidate's update check announces it with working links; Obtainium installs it | `release.yml` timing and step 9 on a `0.12.P` release candidate and on `v1.0.0`; device checks with the candidate's update check and Obtainium's per-ABI filter |
| 3 (M11a) A newer release produces one notification per version and the update card with notes, size, SHA-256 and the two links; "Download APK for this device" links the `Build.SUPPORTED_ABIS[0]` asset; links outside `{repoUrl}/releases/` rejected after canonicalisation (dot-segments included, [Update manifest](#update-manifest)); the manual install keeps all data on API 26 and API 37 | `AppUpdateCheckerTest`, `GitHubUpdateSourceTest`, `UpdateManifestParserTest`; [device checklist](#device-checklist-m11a) |
| 4 Removed 2026-10-05 (no in-app install, PO-31; the idle gate is gone) | — |
| 5 Removed 2026-10-05 (no in-app install, PO-31: a developer-verification block happens in Android's installer, which the help page explains) | — |
| 6 (M11a) With "Check for updates" off no work is scheduled and nothing is sent until "Check now"; never `api.github.com`; no install permission in the merged manifest; the first-run card and the verification notice once each | `AppUpdateCheckerTest`, `UpdateNoticesTest`, `GitHubUpdateSourceTest`; `verifyManifestPermissions` against `permissions.txt`; device checklist |
| 7 Instrumented suite on the published debug APKs on API 26 and 36 and an API 37 16 KB image; M9's YouTube smoke test also on the `benchmark` APKs | `instrumented-full` debug leg (`api26` and `api36` among the `nightly` group) and `api37-16k` (the `:app` suite on the published `x86_64` APK, `PAGE_SIZE` 16384, `zipalign -P 16` and the ELF check of the CPython libraries and extension modules); E7 in the benchmark leg and in `api37-16k`; all green on the release commit or its parent per `verify-tag.sh` |
| 8 Network capture of a fresh-install session shows only expected hosts | `scripts/ci/network-capture.sh`, run by a maintainer on a workstation (it needs the real internet, so it is never a CI job) against the `x86_64` APK of a `0.12.P` release candidate: emulator (`system-images;android-36;default;x86_64`, no Google apps) started with `-tcpdump`, UI Automator session (subscribe 2 real feeds, refresh, stream 30 s, download one episode, search "news", add and play one YouTube channel, Settings › Updates › Check now, Settings › YouTube › Check for engine update); `tshark` extracts DNS names and TLS SNI; every host is mapped to an inventory ID (`app-updates` and `youtube-engine` included; never `api.github.com`); OS hosts (connectivity check, NTP) listed separately. Unverified: emulator `-tcpdump` on API 36 images |
| 9 Migration from the first tester schema; a device upgraded from the previous release by installing the downloaded APK over it keeps all data | 02's `MigrateAllTest`; manual upgrade on the reference device from the previous release through Settings › Updates › "Download APK for this device", comparing library, groups, history, Up next and downloads |
| 10 PO-10, PO-14 and PO-36 resolved | PLAN §4 updated (PO-1, PO-2, PO-5, PO-8 and PO-31–PO-35 resolved on 2026-10-05) |
| 11 Database ≤ 100 MB at the N5 scale; retention deletes exactly the unprotected absent episodes | PB14 (02's size measurement on `SeedDatabase`); 02's `RetentionTest` |
| 12 Removed 2026-10-05 (no release key and no rotation runbook, PO-35) | — |
| 13 README, help page and every release body state the debug-build trade-offs (public key: download only from the GitHub release page, the signature proves nothing; data readable over ADB; less smooth than a release build); no developer tooling in the v1.0 APKs | review of the README "Install and update" section, 08's BUILDS card and the [release body](#release-body) template against [Public key trade-offs](#public-key-trade-offs); `check-apk.sh --published` on the `v1.0.0` APKs |

Plus: every earlier milestone's acceptance criteria green; README "Install and update" final and identical in substance to 08's help page; `PRIVACY.md` final; Licences screen and `THIRD_PARTY_NOTICES.md` list every bundled component (CPython and its libraries, Chaquopy, yt-dlp, yt-dlp-ejs, the CA bundle; [04 Notices](04-youtube.md#notices)); ACRA mailbox configured and a test report received from a published build.

---

## Settings

Keys owned here ([01 DataStore files and typed setting keys](01-foundation.md#datastore-files-and-typed-setting-keys)). UI on 08's `PRIVACY`, `UPDATES` and `ABOUT` pages ([08 Updates settings](08-ui-ux.md#updates-settings)).

| Key | Type | Default | File | UI location | Milestone |
|---|---|---|---|---|---|
| `privacy.crash_reports` | Bool | true | `settings` | Settings › Privacy › "Offer to send crash reports" (dialog per crash; off = ACRA reports nothing) | M0 (UI M11) |
| `diagnostics.verbose_log_until` | Long? (epoch ms) | null | `device_settings` | Settings › About › Diagnostics › "Detailed log for 24 hours" | M11 |
| `updates.check_enabled` | Bool | true ([PO-31](../PLAN.md#48-further-product-owner-decisions)) | `settings` | Settings › Updates › "Check for updates"; the first-run card's "Turn off" | M11a |
| `updates.last_check_at` | Long? (epoch ms) | null | `device_settings` | Settings › Updates "Last checked" line | M11a |
| `updates.skipped_version_code` | Long? | null | `device_settings` | none ("Skip this version") | M11a |
| `updates.notified_version_code` | Long? | null | `device_settings` | none (at most one notification per version) | M11a |
| `updates.first_run_choice_done` | Bool | false | `device_settings` | none (first-run card) | M11a |
| `updates.verification_notice_shown_at` | Long? (epoch ms) | null | `device_settings` | none (verification notice) | M11a |

`privacy.crash_reports` is mirrored into ACRA's own SharedPreferences file `acra` (`sharedPreferencesName` above), key `acra.enable` (`ACRA.PREF_ENABLE_ACRA`), by an `AppInitializer` (order 20, platform band) and on every change of the setting. ACRA reads that key when it initialises in `attachBaseContext` — long before DataStore can be read — and its `ErrorReporterImpl` listens for changes to it, so the switch takes effect immediately and survives process restarts (`ACRA.errorReporter.setEnabled(…)` alone would last only until the process dies). The `acra` file is outside the Auto Backup include list ([D34](../PLAN.md#3-key-decisions)), so a restored device starts enabled until the initializer mirrors the restored setting. `diagnostics.db_quick_check_failed_at` is 02's key.

`updates.check_enabled` is portable (05's backup whitelist); the other `updates.*` keys are device-bound state and never backed up, so a restored phone shows the verification notice again where it applies, and the first-run card unless the restored switch is already off. A restored `true` never overrides `Disabled(DEV_BUILD)` ([Update check](#modules-and-api)). Removed before any build had them (PO-31, PO-33): `updates.mode`, `updates.channel` and `updates.whats_new_version_code`.

Settings › Privacy page content (09): crash-reports switch; "What Neutrodyne connects to" (the [inventory](#network-inventory) with each row's current state, e.g. Podcast Index "off — no key" or "on" (user key, or a project key under PO-3 option A), app update check "On" or "Off", YouTube-engine updates "Neutrodyne-approved"); links to the Discover providers (03), show-notes images (03), Settings › Updates and Settings › YouTube; "Privacy policy" (opens `PRIVACY.md`).

---

## Delivery by milestone

| Milestone | Delivered in this area |
|---|---|
| [M0](../PLAN.md#m0-scaffold-and-ci) | `configureNeutrodyneTestTasks`, `neutrodyne.android.testing` content (including `NeutrodyneTestRunner` and the `neutrodyne.instrumentedTest` property that keeps ACRA out of `:app`'s instrumented tests), `:core:common` test fixtures (`TestClock`, `MainDispatcherRule`, `Goldens`), `:core:testing` (`FakeNetworkMonitor`, `FakeSettingsRepository`, `FakeCrashReporter`, `FakeCrashContext`, `Nightly`), Robolectric `sdk=36`, Roborazzi wiring with one Settings screenshot, GMD `api26`/`api36` (API 26 GMD check, emulator-runner fallback if needed; split-install check), `disableEmptyDeviceTests`, and E0 (version and ABI; `selftest` in `:ytx` when S7 is go); `keepalive.yml`; `ci.yml` (static including `licenseeDebug`, `checkPythonLicences` and `verifyBundledYtDlp`, unit, assemble with `assembleDebug assembleBenchmark` and `check-apk.sh --published`, instrumented; `actions/setup-python`); `nightly.yml` (`instrumented-full` with debug and benchmark legs, `api37-16k`, `dev-tools-build`, `repro` report-only); `release.yml` (container `assembleDebug`, three debug ABI APKs signed with the committed key and checked against `debug-cert-sha256.sh`, `make-update-json.sh`/`check-update-json.sh`, `SHA256SUMS`, `actions/attest`, draft → publish as an immutable, normal release, `gh release verify`); repository settings (immutable releases, rulesets, environment `release` without signing secrets); `changelogs/`; the committed debug keystore `signing/neutrodyne-debug.keystore`, `signing/README.md` and `debug-cert-sha256.sh` ([Debug keystore](#debug-keystore)); first release `v0.1.0` (10095, PLAN M0 AC7) and the M0 AC8/AC9 checks (same certificate on CI and a developer machine with install-over; dev-tools isolation); README "Install and update" first draft with the debug-build and public-key statement; Renovate with the Chaquopy rule; `.editorconfig`, Lint, Spotless, detekt; PR and issue templates; `installAcra` (never in `:ytx`; on in published builds, off in dev-tools builds) + `CrashReportRedactor` (disabled until PO-10), `CrashReporter`/`CrashContext`; `PRIVACY.md` v0, `SECURITY.md`; `privacy.crash_reports` key |
| [M1](../PLAN.md#m1-subscribe-and-ingest-rss) | fakes for 03's interfaces + contracts; `:core:database` test fixtures (02's `TestDb`); golden switch in `:feeds`; `MutationRobustnessTest` for `FeedParser` and `mutation-full`; platform-parser corpus in `:core:data` `androidTest`; schema drift and frozen-schema checks live; `TestServer`, E1 (Add by URL); data builders, `fakeImageLoader`; accessibility checks under Robolectric verified (or the instrumented fallback adopted); inventory row `add-input` (typed URLs) |
| [M2](../PLAN.md#m2-groups-and-group-feeds) | fakes for 05/08 interfaces, `FakeYouTubeCapabilitiesSource` and `testCapabilities`; `ScreenshotTier`; E2; `FeedQueryTimingTest` recorded on GMD and run on the reference device (R2.9, PLAN M2 AC2); reference device confirmed with the PO |
| [M3](../PLAN.md#m3-import-export-and-backup) | OPML/backup mutation providers; E5, E6, E9; nightly `bmgr` job; `RecordingAppNavigator` |
| [M4](../PLAN.md#m4-playback-core) | Media3 test-utils forcing verified; `api34` device; 06's `PlaybackServiceTest` in nightly (incl. API 37 hardening); E3 |
| [M5](../PLAN.md#m5-playback-features-and-system-surfaces) | manual device-matrix template in the release issue |
| [M6](../PLAN.md#m6-downloads) | `:benchmark` module for system tests (its `debug` variant against the published `debug`), `bench34`, `system-tests` job, E10; `api33` device; E4; `bmgr` assertion for `Podcasts/` |
| [M7](../PLAN.md#m7-discovery) | `PRIVACY.md` and in-app inventory gain `apple`, `fyyd`, `podcastindex` and the autodiscovery probes of `add-input`; `PrivacyInventoryParityTest`; E1 deep-link case |
| [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds) | E8 (`ExternalYouTubeModeTest`) in `instrumented` (every build is in external mode until M9a); YouTube import-parser mutation providers; inventory `youtube-subscriptions` |
| [M9](../PLAN.md#m9-youtube-playback-and-downloads-via-the-embedded-yt-dlp-engine) (M9a) | E7 through `:ytx` on the published debug APK and the minified `benchmark` APK (`ReplayRH` hook, `ytxReplay` argument); `shimTest` in `unit`; `check-apk.sh` content scan and ELF alignment check against the real engine stack; nightly `no-engine-build` (blocking), `youtube-canary` and `engine-nightly-canary`; `FakeYouTubeEngine`; PB18–PB21 from the spike; inventory `youtube-streams` rewritten for the engine; YouTube engine lines in diagnostics and `CrashKey.YOUTUBE_HEALTH` |
| [M9](../PLAN.md#m9-youtube-playback-and-downloads-via-the-embedded-yt-dlp-engine) (M9b) | [`engine-canary.yml`](#engine-canaryyml) with environments `engine-approval` and `github-pages`, the Ed25519 manifest key generated once by a maintainer (no ceremony, [engine-canary.yml](#engine-canaryyml)), Pages source "GitHub Actions", the bootstrap manifest, the engine heartbeat and `keepalive.yml` covering the canary; contract-mode gate and strict-mode report; label `engine-canary`; inventory `youtube-engine`; time budget "upstream stable → approved ≤ 6 h" measured on a real yt-dlp release |
| [M10](../PLAN.md#m10-covers-theming-adaptive-layouts-and-accessibility) | `FULL` screenshot tier and `screenshots-full`; pseudo-locale/RTL captures; Weblate project opened at the end of M10 if strings are stable (otherwise M11); `:benchmark` journeys against the `benchmark` build type, `BenchmarkSeedReceiver` in `app/src/benchmark/`, `benchmark-dryrun`; PB3 on `benchmark` and PB23 (report-only, published build) on the reference device (M10 AC7) |
| [M11](../PLAN.md#m11-release-hardening-and-v10) (M11a) | the [update check](#update-check) in `:core:domain`, `:core:model` and `:core:data` (all classes, the two work names, the `updates` notification, the badge state, `UpdateCheckStore`, `VerificationTimeline`), `FakeAppUpdateChecker`, `FakeUpdateNotices`, its tests and [device checklist](#device-checklist-m11a); `updates.*` keys; inventory `app-updates` and the Settings › Privacy rows; README "Install and update" final draft (including "About these builds") and the facts for 08's help page, BUILDS card and notice ([Developer verification](#developer-verification), [Public key trade-offs](#public-key-trade-offs)); the `verification-watch` calendar issue |
| [M11](../PLAN.md#m11-release-hardening-and-v10) (M11b) | all budgets measured (PB1–PB5 on `benchmark`, PB22/PB23 report-only on the published build, per-ABI sizes and engine budgets included; profiles deferred); `DiagnosticsRepository` and contributors, `DatabaseCopyExporter`, `RedactionCoverageTest`, `diagnostics.verbose_log_until`; ACRA mailbox (PO-10); `PRIVACY.md` final; launch languages (PO-14); GitHub release hardening (release body template final with the public-key statement, immutable releases and attestations verified end to end on a `0.12.P` release candidate); `live-canary`; network capture; v1.0 gate (no rotation rehearsal and no mirror decision since PO-34/PO-35) |
| M12–M15 | Glance widget screenshot tests (M13); SponsorBlock destination in the inventory, with the engine (M14); the JS challenge provider in the engine canary's test set if it moves to M14; revisit Compose Preview Screenshot Testing once AGP test suites are stable |

---

## New names introduced here

| Name | Kind | Location |
|---|---|---|
| `configureNeutrodyneTestTasks()`, `configureAndroidTesting()`, `configureManagedDevices()`, `disableEmptyDeviceTests()` | build-logic functions | `build-logic/convention` |
| Gradle properties `updateGoldens`, `screenshotTier`, `mutationIterations`, `testBuildType` (`debug` or `benchmark`), `neutrodyne.testScope`, `neutrodyne.abiSplits` (only if the ABI-split fallback of [Gradle Managed Devices](#gradle-managed-devices) is needed); instrumentation argument `ytxReplay` | build switches | — |
| System properties `neutrodyne.updateGoldens`, `neutrodyne.moduleDir`, `neutrodyne.rootDir`, `neutrodyne.screenshotTier`, `neutrodyne.mutationIterations` | test configuration | — |
| System property `neutrodyne.instrumentedTest` (set only by `NeutrodyneTestRunner`; `installAcra` and 01's `DevToolsInitializer` read it) | inert test hook | `:app` process |
| `NeutrodyneTestRunner` (an `AndroidJUnitRunner`) | test runner | `:app/src/androidTest` |
| `TestClock`, `MainDispatcherRule` (canonical names; physical location), `Goldens` | test helpers | `:core:common` test fixtures, package `ch.lkmc.neutrodyne.core.testing`, re-exported by `:core:testing` |
| `Nightly`, `ScreenshotTier`, `assumeTier` | test selection | `:core:testing` |
| `Fake*` per [inventory](#coretesting-inventory) (including `FakeAppUpdateChecker`, `FakeUpdateNotices`), `*Contract` bases, data builders, `fakeImageLoader`, `testCapabilities(external)` | fakes | `:core:testing` |
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
| `BenchmarkSeedReceiver` | benchmark-only receiver | `app/src/benchmark` (the `benchmark` build type's source set) |
| `AppUpdateChecker`, `UpdateNotices` | update-check interfaces | `:core:domain`, package `ch.lkmc.neutrodyne.core.domain.update` |
| `UpdateCheckState`, `UpdateDisabledReason` (`DEV_BUILD`, `CHECKS_OFF`), `UpdateInfo`, `UpdateApk`, `UpdateCheckError`, `UpdateNotice` | update-check state types | `:core:model`, package `ch.lkmc.neutrodyne.core.model.update` |
| `AppUpdateCheckerImpl`, `UpdateNoticesImpl`, `GitHubUpdateSource`, `UpdateManifestParser`, `VersionScheme`, `UpdateCheckWorker`, `UpdateNotifier`, `UpdateCheckStore`, `VerificationTimeline`, `UpdateModule` | update-check implementation | `:core:data`, package `ch.lkmc.neutrodyne.core.data.update` |
| Work `app-update-check`, `app-update-check-now`; file `noBackupFilesDir/updates/last-check.json` | update check | `:core:data` |
| `UpdateManifestParserTest`, `GitHubUpdateSourceTest`, `AppUpdateCheckerTest`, `UpdateNoticesTest` | tests | `:core:data` |
| `BuildInfo.shippedLocales` | field request | `:core:model` (01) |
| `privacy.crash_reports`, `diagnostics.verbose_log_until`, `updates.check_enabled`, `updates.last_check_at`, `updates.skipped_version_code`, `updates.notified_version_code`, `updates.first_run_choice_done`, `updates.verification_notice_shown_at` | setting keys | `:core:model` registry |
| Workflows `ci.yml`, `nightly.yml`, `release.yml`, `engine-canary.yml`, `record-screenshots.yml`, `keepalive.yml`; engine heartbeat `engine/ytdlp-heartbeat.json` (+ `.sig`) on GitHub Pages; jobs per [CI pipelines](#ci-pipelines) (new: `no-engine-build`, `engine-nightly-canary`, `dev-tools-build`; `instrumented-full` legs `debug` and `benchmark`); GitHub environments `release`, `engine-approval`, `github-pages`; labels `run-instrumented`, `nightly-failure`, `release-blocker`, `engine-canary`, `repro`, `verification-watch`, `chaquopy` | CI | `.github/` |
| Release assets `neutrodyne-{v}-{abi}.apk`, `neutrodyne-update.json`, `SHA256SUMS` | release | GitHub Releases |
| Scripts per [CI scripts](#ci-scripts) (new: `make-update-json.sh`, `check-update-json.sh`, `debug-cert-sha256.sh`; `check-apk.sh --published`), `scripts/release.sh`, `scripts/l10n/update-shipped-locales.sh`, `scripts/ci/network-capture.sh` | scripts | `scripts/` |
| `app/policy/locales.txt`, `app/lint-baseline.xml`, `config/detekt/detekt.yml`, `renovate.json`, `changelogs/<versionCode>.txt`, `PRIVACY.md`, `SECURITY.md`, `.editorconfig`, `app/proguard-test.pro`, `signing/neutrodyne-debug.keystore` and `signing/README.md` (content) | files | repository |
| Secrets `NEUTRODYNE_ENGINE_MANIFEST_KEY`, `NEUTRODYNE_ENGINE_MANIFEST_KEY_NEXT` (only during a rotation) | CI configuration | GitHub environment `engine-approval` |
| Budget IDs PB12, PB13 (per ABI, published debug APKs), PB18–PB21, PB22 and PB23 (published build, report-only) | budget IDs | this document |

Removed 2026-10-05 (PO-31–PO-35): `AppUpdater`, `UpdateState`, `UpdateMode`, `UpdateChannel`, `InstallBlockReason`, `UpdateError`, `AppUpdaterImpl`, `UpdateDownloadWorker`, `UpdateInstallWorker`, `ApkVerifier`, `ApkInspector`, `SelfInstaller`, `UpdateStatusReceiver`, `InstallIdleGate`, `InstallerOfRecordDetector`, `VerificationFailureMapper`, `FakeAppUpdater`, their tests, the works `app-update-download` and `app-update-install`, `pending.json`, the keys `updates.mode`, `updates.channel` and `updates.whats_new_version_code`, the workflow `baseline-profile.yml`, the `mirror` job, the asset `neutrodyne-{v}-mapping.txt`, `neutrodyne.lineage`, the secrets and variables listed in [Hardening](#hardening) and the manifest fields `prerelease`, `certSha256` and `previousCertSha256`.

---

## Open questions

1. Resolved: PLAN [5.1](../PLAN.md#51-module-graph) and 01's rule 13 confirm `:core:testing` as an Android library, with `TestClock`, `MainDispatcherRule` and `Goldens` in `:core:common` test fixtures re-exported by `:core:testing`.
2. Resolved: `:benchmark` is created in M6 (system tests) and gains Macrobenchmarks against `:app`'s `benchmark` build type in M10 (PLAN 5.1, M6, M10; 01's module table). Updated 2026-10-05 (PO-35): profiles are deferred until a non-debuggable build is published ([Macrobenchmark and profiles](#macrobenchmark-and-profiles)).
3. Resolved: 01 commits `neutrodyne.acraMailto` in `gradle.properties`, [D62](../PLAN.md#3-key-decisions) records it, and [PO-3](../PLAN.md#po-3-podcast-index-api-key-handling) option A injects a Podcast Index key into the published builds only (`release.yml`), after Podcast Index's written permission ([D26](../PLAN.md#3-key-decisions)).
4. Resolved 2026-10-05 (PO-35): PLAN M0 delivers the committed debug keystore and the first release `v0.1.0`; [PO-8](../PLAN.md#48-further-product-owner-decisions) is resolved (`ch.lkmc.neutrodyne`) and [PO-35](../PLAN.md#48-further-product-owner-decisions) is resolved ("Just build debug builds": no release key, so no holders, ceremony or backups, [Debug keystore](#debug-keystore)); [PO-5](../PLAN.md#po-5-google-developer-verification) is resolved (not registering), and directly sideloaded tester builds are unaffected until Google's global rollout.
5. Resolved: 02 moved its fixtures to `:core:database` test fixtures (`core/database/src/testFixtures/`).
6. Resolved: PLAN M0 acceptance 4 reads "API 26 device (Gradle Managed Device, or an android-emulator-runner API 26 emulator)".
7. Resolved in 02 ([02 db-maintenance worker](02-data-model.md#db-maintenance-worker)): the scrub covers `episode.identityKey`, `episode.guid`, `podcast.artworkUrl`, `episode.imageUrl`, `episode.chaptersUrl`, `episode_alt_enclosure.sourcesJson` and `artwork.url`, and the target lies under `cacheDir/export/`.
8. Resolved in 01: rule 4 keeps segments shorter than 15 characters, so the 15-character example is masked.
9. Moved to [PO-28](../PLAN.md#48-further-product-owner-decisions) (default: Pixel 7a).
10. Obsolete since 2026-10-05: there is one build; ACRA by email in every APK by default ([PO-10](../PLAN.md#48-further-product-owner-decisions)), never in `:ytx`.
11. Obsolete since 2026-10-05: there is no store listing, so no audience rating to decide.
12. Resolved in 01: committed `neutrodyne.acraMailto` (forced empty only in dev-tools builds since PO-35); AboutLibraries offline mode; `BuildInfo.shippedLocales`; the `Text("` literal pattern in `checkBannedApis`; `neutrodyne.jvm.library` calls `configureNeutrodyneTestTasks()`; test fixtures on `:core:common`, `:core:database` and `:core:navigation`; `verifyDependencyPolicy` fails on `io.mockk` in any `*AndroidTestRuntimeClasspath`; `include(":benchmark")` in M6.
13. Obsolete since 2026-10-05: no store scans the source tree; committed `.zip`/`.gz` fixtures stay test inputs ([Fixture policy](#fixture-policy)).
14. Unverified, checked in the named milestone: GMD API 26 and AGP 9.4 managed-device DSL names (M0); the device-test disable accessor (M0); that GMD and `connected*` test tasks install the ABI split matching the emulator (M0); Kotlin in Android test fixtures (M0); Robolectric default-locale override and pseudo-locale qualifiers (M0/M2); that library-generated pseudo-locale resources never reach the published APK (M0, `check-apk.sh`); accessibility checks under Robolectric (M1); `testImplementation(ui-test-manifest)` merged into Robolectric's test manifest (M1); compose-rules `.editorconfig` keys (M0); `generateLocaleConfig` with `localeFilters` and the generated locale-config file name (M0); android-emulator-runner with a minor-versioned `api-level` (M0); AGP 9's ABI-split output file names for `debug` and the `llvm-readelf` binary on the runner (M0); that a command-line `assembleDebug` never sets `testOnly` (M0, PLAN M0 AC7); byte-identical signed APKs from two rebuilds with the committed key (M0, `repro`); whether GitHub push protection flags the committed PKCS12 keystore (M0); whether AGP adds VCS info to debug builds (M0); that the orchestrator calls `NeutrodyneTestRunner.newApplication` in every test process (M0); `actions/setup-python` and the `python:3.14-slim-trixie` image as Chaquopy's build Python (M0, with S7); Chaquopy's `.pyc` determinism under `SOURCE_DATE_EPOCH` (M0); R8 keeping `YtxTestHooks` (M9a); GitHub Pages cache lifetime and partial-deployment behaviour (M9b); `com.android.test` matching `:app`'s `benchmark` variant by build-type name under AGP 9.4, and seeding the published build for PB22/PB23 within Gradle's connected tasks (M10); Renovate hosted wrapper regeneration (M0) and a regex manager for the container digest (M0); GitHub's limits for unauthenticated `releases/download` requests (M11a); whether deleting a never-published draft keeps a tag name usable under immutable releases (M0); which events count as repository activity for GitHub's 60-day schedule rule and whether `gh workflow enable` resets it (M0, `keepalive.yml`); whether Macrobenchmark also needs `NOT-PROFILEABLE` suppressed for the debuggable published build (M10); emulator `-tcpdump` on API 36 (M11b); Weblate statistics API (M11). Dropped 2026-10-05 with PO-31–PO-35: the Codeberg mirror policy and heartbeat check, `releases.atom`, Obtainium's package IDs, Robolectric's install-constraint support, `pm set-developer-verification-result` on a retail device and AGP signing with a rotation lineage.
15. Resolved (asked by 08), updated 2026-10-05 (PO-31): "Check now" works while "Check for updates" is off, as a one-off user action, and is refused only in `Disabled(DEV_BUILD)`.
16. Resolved 2026-10-05 (PO-31): there is no install gate. The update check installs nothing, so it never waits for playback or downloads; a manual install ends the process like any update (06, 07).
17. Resolved 2026-10-05 (PO-31): the app does not read the installer of record and does not detect Obtainium; Obtainium users turn "Check for updates" off themselves (R6.3).
18. Resolved 2026-10-05 (PO-34): no mirror, no mirrored assets or manifests, no fallback URL; a GitHub takedown is an accepted risk ([GitHub takedown](#github-takedown)).
19. Resolved 2026-10-05 (PO-31; risk T17 retired): there is no self-update whose behaviour under enforcement could differ. Every update goes through Android's own installer, and the help text is checked by hand on an enforcing device in 2027 ([Watch and notice timing](#watch-and-notice-timing)).
20. Unverified (risk P9): the consequences for our installs if someone registers `ch.lkmc.neutrodyne` with another key before Neutrodyne has 50 installs, or registers Neutrodyne's own public certificate; no mitigation short of registering exists, and registering would need a private key and one reinstall ([Package name and key](#package-name-and-key)).
21. Should the engine canary also re-run against the currently approved version when the shim changes on `main` (so a shim PR cannot silently break the approved engine)? Default: no — shim PRs run `shimTest` against the bundled version in CI and against the latest approved one locally (PR template); revisit if a regression slips through.
22. Unverified: GitHub's behaviour for `make_latest` when a patch of an older line is published after a newer release; `verify-tag.sh` avoids the case by computing `make_latest` itself (every release is a normal release since PO-33).
23. Resolved 2026-10-05 (PO-31): no blocked sheet and no "Continue in Android" — the app installs nothing, so it never receives an installer result.
24. Resolved here (asked by 01, its open question 14): library modules' Robolectric Compose tests host composables through `testImplementation(ui-test-manifest)` (Unverified merge, M1 check, with a `ShadowPackageManager` fallback); `:app`'s instrumented tests use only `createAndroidComposeRule<MainActivity>()` and reach screens through the UI or deep links ([Compose UI and screenshot tests](#compose-ui-and-screenshot-tests)).
25. Resolved here (PO-35): ACRA is on in the published build, and `:app`'s instrumented tests keep it out through `NeutrodyneTestRunner` and the inert system property `neutrodyne.instrumentedTest` ([Gradle Managed Devices](#gradle-managed-devices)); 01's `installAcra` call site and `DevToolsInitializer` read it. The one exception is `YtxIsolationTest`, which installs ACRA itself with the test-only address `ytx-isolation-test@invalid` so that PLAN M9 AC5's "no ACRA dialog" does not depend on PO-10 (Unverified late installation, M9a check; [Gradle Managed Devices](#gradle-managed-devices)).

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
- Baseline Profiles overview (installed for non-debuggable builds; non-Play installs may not apply them) (2026-10-05) — https://developer.android.com/topic/performance/baselineprofiles/overview
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
- `apksigner` (`verify --verbose --print-certs`) — https://developer.android.com/tools/apksigner
- APK Signature Scheme v3 / v3.1 (key rotation from API 28, v3.1 from API 33; only for the "later private key" note) — https://source.android.com/docs/security/features/apksigning/v3
- AOSP `PackageInstallerSession` (verifier skipped for shell/root callers, so ADB installs are exempt) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/pm/PackageInstallerSession.java
- OpenSSL `genpkey` (Ed25519 key generation for the engine-manifest key) — https://docs.openssl.org/3.0/man1/openssl-genpkey/
- 16 KB page sizes — https://developer.android.com/16kb-page-size · https://developer.android.com/guide/practices/page-sizes
- yt-dlp release files and signing key (engine canary inputs) — https://github.com/yt-dlp/yt-dlp#release-files · https://github.com/yt-dlp/yt-dlp/blob/master/public.key
- Chaquopy (build-time `.pyc`, Python ≥ 3.12 64-bit only) — https://chaquo.com/chaquopy/doc/current/android.html · FAQ — https://chaquo.com/chaquopy/doc/current/faq.html
- Unlicense — https://en.wikipedia.org/wiki/Unlicense

Debug builds, signing and measurement (2026-10-05, PO-35):
- `android:debuggable` ("can be debugged, even when running on a device in user mode"), `android:testOnly` (added by Android Studio's Run; such APKs install only over adb) — https://developer.android.com/guide/topics/manifest/application-element
- `run-as` refuses packages that are not debuggable — https://android.googlesource.com/platform/system/core/+/refs/heads/main/run-as/run-as.cpp
- Android 12: `adb backup` excludes app data of apps targeting 31+ unless they are debuggable — https://developer.android.com/about/versions/12/behavior-changes-12
- Build types, `signingConfigs` per build type, `initWith`, `matchingFallbacks`, variant filters; AGP's default debug signing with a per-machine debug keystore — https://developer.android.com/build/build-variants
- App signing (the debug certificate is "insecure by design"; Android Studio's debug certificate expires after 30 years) — https://developer.android.com/studio/publish/app-signing
- ABI splits (outputs named `modulename-ABI-buildvariant.apk`; universal APK only with `universalApk`) — https://developer.android.com/build/configure-apk-splits
- Macrobenchmark (target must be non-debuggable; `benchmark` build type with `initWith` and the debug signing config; the test module builds the app variant with the same build type name; `CompilationMode` options) — https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- Macrobenchmark instrumentation arguments (`androidx.benchmark.suppressErrors` with `DEBUGGABLE`, `NOT-PROFILEABLE`; `dryRunMode.enable`) — https://developer.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args
- `CompilationMode.Partial` (`BaselineProfileMode.Disable` needs `warmupIterations` > 0) — https://developer.android.com/reference/kotlin/androidx/benchmark/macro/CompilationMode.Partial
- `ApplicationBuildType` (`isProfileable`, `isDebuggable`) — https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/ApplicationBuildType
- ART: debuggable apps rely on JIT (2017 change) — https://android.googlesource.com/platform/art/+/a0619e2%5E%21/; confirmed for current ART Service (safe mode forced for debuggable apps, 2026-10-05) — https://android.googlesource.com/platform/art/+/refs/heads/main/libartservice/service/java/com/android/server/art/Dexopter.java
- CheckJNI, JDWP and ptrace for debuggable apps (2026-10-05) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ProcessList.java; CheckJNI on for debuggable apps and emulators, aborts the VM on errors — https://developer.android.com/training/articles/perf-jni
- Compose performance (debug mode "imposes a performance cost") — https://developer.android.com/develop/ui/compose/performance
- PNG crunching off by default for `debug` (AGP DSL 3.4 reference) — https://google.github.io/android-gradle-dsl/3.4/com.android.build.gradle.internal.dsl.BuildType.html
- `aapt2 dump badging` prints `application-debuggable` for debuggable APKs — https://mas.owasp.org/MASTG/techniques/android/MASTG-TECH-0150/
- AOSP `Instrumentation.newApplication` instantiates the application and calls `attach` (so a runner can act before `attachBaseContext`) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Instrumentation.java

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
