# Change brief 2: the owner's answers to PO-31 to PO-35 (2026-10-05)

This brief is for the revisers of 01, 04, 05, 06, 08 and 09 and for the sweep of 02, 03, 07 and README.md. PLAN.md is already revised and is the source of truth. Before you edit, read these parts of it:

- section 1.1 principles 3 and 6, and 1.2;
- R6, N3, N5, N7, N11 and N12;
- the traceability rows for R6, N5 and N12;
- D2, D13, D26, D61, D62, D63, D77, D78, D79 and D80;
- section 4: PO-2, PO-5, PO-8, PO-18 and PO-31 to PO-36;
- 5.1 (graph, rule 3, placement bullets) and the "App update check" flow in 5.2;
- section 6, row R6;
- 7.1, 7.2, M0, M9, M10 AC7 and M11;
- risks T16, T18, P3, P7, P8, P9, P10 and P11, plus the "Retired" paragraph;
- the glossary and Appendix A.

If this brief and PLAN.md disagree, PLAN.md wins. Report the conflict.

## 0. Rules for every reviser

- **Scope.** Edit only your own document. Do not create files. Do not edit CLAUDE.md, which the orchestrator maintains. The README is edited only by whoever is given the sweep (§6.4).
- **Structure.** Keep each document's structure, its headings and its anchors. Rename a heading only where §4 says so, then fix the inbound links that live in your own document. §4 lists the links in other documents, and their owners fix them.
- **Acceptance criteria.** Do not renumber PLAN acceptance criteria. M11 AC4, AC5 and AC12 are now "Removed 2026-10-05". AC3 and AC6 keep their numbers but have new content. AC13 is new. M0 has new criteria AC8 and AC9. Any text that cites the old content of a criterion must be reworded.
- **Mermaid.** Every Mermaid block must still parse. Avoid `;`, `#`, and `:` inside participant aliases. Check with `node /tmp/claude-0/-home-user-Neutrodyne/12c40d4e-a2e3-573a-89e6-30bb02f324e2/scratchpad/mmd/check.mjs <file>`, run from that directory.
- **Links.** Check anchors with `python3 /tmp/claude-0/-home-user-Neutrodyne/12c40d4e-a2e3-573a-89e6-30bb02f324e2/scratchpad/linkcheck.py`. Until 09 is revised, PLAN shows nine missing anchors, which 09 creates: `#update-check` (7×) and `#debug-keystore` (2×).
- **Claims.** A platform claim needs a source URL (§7 lists the verified ones) or the prefix "Unverified:". Never link the research notes.
- **Bookkeeping.** In your document, update the header line (Implements / Honours / Owns / Milestones and the revision note "revised 2026-10-05 for PO-31–PO-35"), `Contents:`, `## Delivery by milestone`, `## New names introduced here`, `## Open questions` and `## Sources`. Resolved open questions are marked "Resolved 2026-10-05 (PO-3x)" and keep their numbers.
- **"Debug build".** The phrase now means **the published build**. Rewrite every sentence that assumed "debug = development only". Development-only behaviour now keys to **dev-tools builds** (`BuildInfo.devTools`, `-Pneutrodyne.devTools=true`).

## 1. Decisions in one line each

| # | Decision |
|---|---|
| Q1 (PO-31) | Notify only. The app checks GitHub once a day and on "Check now". When a newer release exists, it posts a notification and shows an update card with two browser links: "Open release on GitHub" and "Download APK for this device". The user installs the APK with Android's installer. The app never downloads, verifies or installs APKs. There is one setting, "Check for updates" (on by default). |
| Q2 (PO-32) | Resolved as designed. YouTube-engine updates are automatic, using Neutrodyne-approved yt-dlp versions. Nothing changes except "default" becomes "resolved". |
| Q3 (PO-33) | There is no beta channel and no `releases.atom`. Every tag, including milestone tester builds, is a normal (never pre-release) GitHub release. Pre-1.0 versions are `0.{n+1}.P`, with no `-beta.N`. |
| Q4 (PO-34) | There is no mirror and no fallback URL, and a GitHub takedown is an accepted risk (P7). `keepalive.yml` and the engine heartbeat stay. |
| Q5 (PO-35) | Every published APK is a **debug build**, produced by `assembleDebug` with per-ABI splits. All builds are signed with a non-secret keystore committed to the repository, the application ID is `ch.lkmc.neutrodyne` with no suffix, and there is no release key. The non-debuggable `benchmark` build type exists only for measurement. Developer tooling is enabled only with `-Pneutrodyne.devTools=true`. |

## 2. Canonical names

### 2.1 Build, IDs and signing

| Item | Name / value |
|---|---|
| Application ID (published `debug`, and `benchmark`) | `ch.lkmc.neutrodyne`. `.debug` no longer exists anywhere. |
| Application ID (dev-tools builds) | `ch.lkmc.neutrodyne.dev` (`applicationIdSuffix ".dev"`, `versionNameSuffix "-dev"`) only when `-Pneutrodyne.devTools=true`. Processes are `ch.lkmc.neutrodyne.dev:ytx` and `…dev:acra`. |
| Build types | `debug` is published: debuggable, no R8, application ID `ch.lkmc.neutrodyne`, signed with `neutrodyneDebug`, with ABI splits. `benchmark` is `initWith(debug)`, `isDebuggable = false`, `isProfileable = true`, `optimization { enable = true }` (R8 plus the keep rules), same signing config and `matchingFallbacks += "release"` for library modules; it is never published. `:app` has **no `release` variant**: disable AGP's default one with `androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }` (Unverified: exact AGP 9 DSL). The baseline-profile plugin and the `benchmarkRelease`/`nonMinifiedRelease` build types are removed. |
| Signing config | `signingConfigs.create("neutrodyneDebug")`: `storeFile = rootProject.file("signing/neutrodyne-debug.keystore")`, `storeType = "pkcs12"`, `storePassword = "android"`, `keyAlias = "androiddebugkey"`, `keyPassword = "android"`, `enableV1Signing = false`, `enableV2Signing = true`, `enableV3Signing = true`. Both `debug` and `benchmark` use it. |
| Keystore | `signing/neutrodyne-debug.keystore` (PKCS12, committed, public), created once in M0 with `keytool -genkeypair -v -storetype PKCS12 -keystore signing/neutrodyne-debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 12000 -dname "CN=Neutrodyne Debug, O=Neutrodyne"`. Add `signing/README.md` (public on purpose; download only from GitHub releases; forks must change the application ID or the key) and `.gitattributes` `signing/*.keystore binary`. Unverified: whether GitHub push protection flags a PKCS12 file; if it does, push with the documented reason. |
| Expected certificate | Computed from the committed keystore by `scripts/ci/debug-cert-sha256.sh` (`keytool -list -v … -storepass android`). The repository variable `NEUTRODYNE_CERT_SHA256` is removed. |
| Dev-tools switch | Gradle property `neutrodyne.devTools` (default `false`; never committed as `true`; developers set it in `~/.gradle/gradle.properties` or pass `-P`). It sets `BuildConfig.DEV_TOOLS`. It adds source directory `app/src/devTools/kotlin` to `main` with `DevToolsInitializer`, an `AppInitializer` in the multibinding set for StrictMode penalties, LeakCanary config and debug-only screens. It adds `implementation(libs.leakcanary.android)` in `:app`, `debugImplementation(ui-tooling)` through `neutrodyne.android.compose`, and `DebugHttpLogInterceptor`. It sets `LogcatSink` to DEBUG (otherwise WARN), forces ACRA's mailbox to `""`, enables pseudo-locales in `:app`, makes the update check `Disabled(DEV_BUILD)`, and suppresses the update notices. |
| `BuildInfo` (`:core:model`) | Remove `isDebug` and `releasesAtomUrl`. Add `devTools: Boolean`. Keep `versionName`, `versionCode`, `repoUrl`, `apkAbi`, `youTubeEngineBundled`, `updateManifestUrl` (`$repoUrl/releases/latest/download/neutrodyne-update.json`), `engineManifestUrl`, `shippedLocales` and `podcastIndexKey/Secret`. |
| Banned in project code | `BuildConfig.DEBUG` (it is true in every published APK). `PackageInstaller` anywhere (previously banned only outside `update/impl/`). `REQUEST_INSTALL_PACKAGES` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION` in any manifest. Any `debugImplementation`/`debugApi` or `src/debug/` content except through the dev-tools switch. Test hooks are allowed in `main` only if they are inert unless an instrumentation test sets them (e.g. `YtxTestHooks`). |
| Size budgets (published debug APKs) | PB12 `arm64-v8a` and `x86_64` < 60 MB. PB13 `armeabi-v7a` < 50 MB. Both are Unverified estimates (≈ 10–20 MB more than a minified build); S7 measures them in M0 and a miss goes to the PO. A universal debug APK would be ≈ 60–80 MB (Unverified). |
| Where N5 is measured | PB1–PB5 on `benchmark` (non-debuggable, R8). There is no baseline profile, so use `CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = …)` or `None()`; 09 decides and states it. The same journeys run on the published debug build with `androidx.benchmark.suppressErrors=DEBUGGABLE`, report-only (Unverified error ID). The engine budgets PB18–PB21 are measured on the published `arm64-v8a` APK. |

### 2.2 Update check (replaces the in-app updater)

| Item | Name |
|---|---|
| Modules | None of its own; `:update:api` and `:update:impl` are **deleted**. Interfaces live in `:core:domain` (package `ch.lkmc.neutrodyne.core.domain.update`), state types in `:core:model` (`ch.lkmc.neutrodyne.core.model.update`), and the implementation in `:core:data` (`ch.lkmc.neutrodyne.core.data.update`, Hilt `UpdateModule`). |
| Interfaces (`:core:domain`) | `interface AppUpdateChecker { val state: StateFlow<UpdateCheckState>; suspend fun checkNow(): UpdateCheckState /* user action; works with checks off; refused only in Disabled(DEV_BUILD); ≤ 1 per 60 s */; fun skip(versionCode: Long) }` and `interface UpdateNotices { val pending: StateFlow<UpdateNotice?>; fun dismiss(notice: UpdateNotice) }` |
| State types (`:core:model`) | `sealed interface UpdateCheckState { Disabled(reason: UpdateDisabledReason); Idle(lastCheckAtMs: Long?); Checking; Available(info: UpdateInfo, lastCheckAtMs: Long, lastError: UpdateCheckError? = null); Failed(error: UpdateCheckError, lastCheckAtMs: Long?) }`. `enum class UpdateDisabledReason { DEV_BUILD, CHECKS_OFF }`. `data class UpdateInfo(versionName, versionCode: Long, minSdk: Int, publishedAt: String, notes: String, releaseUrl: String, apk: UpdateApk?)`; `apk` is null when the release has no APK for `Build.SUPPORTED_ABIS[0]`, and then the card offers only the release page. `data class UpdateApk(abi, fileName, url, sizeBytes: Long, sha256: String)`. `enum class UpdateCheckError { NETWORK, RATE_LIMITED, MANIFEST_INVALID }`. `enum class UpdateNotice { FIRST_RUN_CHOICE, VERIFICATION_ENFORCEMENT }` (`WHATS_NEW` is removed). A failed check never hides a known `Available`; it only sets `lastError`. |
| Implementation (`:core:data`) | `AppUpdateCheckerImpl`, `UpdateNoticesImpl`, `GitHubUpdateSource` (stable `latest/download` only, on 01's API client, never `api.github.com`), `UpdateManifestParser`, `VersionScheme`, `UpdateCheckWorker`, `UpdateNotifier`, `VerificationTimeline` (constants unchanged) and `UpdateCheckStore` (`noBackupFilesDir/updates/last-check.json`: the last parsed `UpdateInfo` and check time, so `Available` survives process death without a request). |
| Fakes (`:core:testing`) | `FakeAppUpdateChecker` and `FakeUpdateNotices` (replace `FakeAppUpdater`). |
| Removed classes | `AppUpdater`, `UpdateState`, `UpdateMode`, `UpdateChannel`, `InstallBlockReason`, `UpdateError`, `UpdateDownloadWorker`, `ApkVerifier`, `ApkInspector`, `SelfInstaller`, `UpdateInstallWorker`, `UpdateStatusReceiver`, `InstallIdleGate`, `InstallerOfRecordDetector`, `VerificationFailureMapper`, `pending.json`. Also `PlaybackStateSource.busy` and `DownloadProgressSource.busy` with their fakes, because no other consumer exists. |
| Work | `app-update-check`: periodic 24 h, flex 6 h, `CONNECTED`, `ExistingPeriodicWorkPolicy.UPDATE`, exponential backoff from 1 h. It is enqueued by the order-200 initializer only while `updates.check_enabled` is on and the build is not dev-tools; the first enqueue has a 24 h initial delay so the first-run card comes first. `app-update-check-now`: one-time, `REPLACE`, `CONNECTED`. `app-update-download` and `app-update-install` are removed. |
| Settings keys | Portable (05 whitelist): `updates.check_enabled` (Boolean, default `true`). Device-bound (`device_settings`): `updates.last_check_at`, `updates.skipped_version_code`, `updates.notified_version_code` (new: at most one notification per version), `updates.first_run_choice_done` and `updates.verification_notice_shown_at`. Removed: `updates.mode`, `updates.channel` and `updates.whats_new_version_code`. |
| Notification | Channel `updates` ("App updates", importance LOW, unchanged), one notification `NOTIF_ID_UPDATE = 4200`, posted once per `versionCode` and only with `POST_NOTIFICATIONS`. Title "Neutrodyne {versionName} is available"; text is the first line of the notes. Content intent `neutrodyne://open/settings/updates`; action "Open on GitHub" (release page). It is cancelled when that version runs or is skipped. |
| Badge | A dot on the Settings gear (top bars and rail footer) and on the Settings home "Updates" row while `Available` and not skipped. |
| UI strings (08 finalises them) | Switch "Check for updates". Card actions "Open release on GitHub", "Download APK for this device", "Skip this version". "Check now". A copyable "SHA-256" line for the device's APK. Footer: "Updates come from github.com/{owner}/Neutrodyne. You install them yourself with Android's installer." |
| Help sections | `InstallHelpSection` gains `BUILDS` ("About Neutrodyne's builds": debug builds, public key, ADB data access, performance). The other values are unchanged. |
| Nav keys | `UpdateBlockedKey` and `WhatsNewKey` are removed. `SettingsKey(SettingsPage.UPDATES)`, `InstallHelpKey(section)` and `VerificationNoticeKey` are kept. |
| Network inventory | `app-updates`: `github.com` and `release-assets.githubusercontent.com`. Only the manifest GET; the APK download happens in the user's browser. |

### 2.3 Release assets, manifest and versioning

| Item | Name |
|---|---|
| Assets per tag `vX.Y.Z` | `neutrodyne-{v}-arm64-v8a.apk`, `neutrodyne-{v}-x86_64.apk`, `neutrodyne-{v}-armeabi-v7a.apk`, `SHA256SUMS` (over the three APKs and the manifest) and `neutrodyne-update.json`. **`neutrodyne-{v}-mapping.txt` is removed** because nothing is minified. AGP outputs are `app-{abi}-debug.apk` (Unverified: AGP 9 names). |
| Release flags | Always a normal release, never `--prerelease`. `make_latest` is true when the tag's `versionCode` is higher than the current latest release's. `verify-tag.sh` refuses tags with a suffix. |
| `neutrodyne-update.json` (schema 1, new layout; nothing shipped reads the old one) | `{ "schema": 1, "versionName", "versionCode", "minSdk": 26, "published", "releaseUrl": "{repoUrl}/releases/tag/v{v}", "notes", "apks": [ { "abi", "file", "url": "{repoUrl}/releases/download/v{v}/{file}", "size", "sha256" } ] }`. Removed fields: `prerelease`, `certSha256` and `previousCertSha256`. The app rejects the manifest (`MANIFEST_INVALID`) when `versionCode` ≠ `VersionScheme(versionName)` or when `releaseUrl` or any `url` does not start with `{repoUrl}/releases/`. It ignores unknown ABIs and unknown fields; the manifest is ≤ 64 KB. |
| Version scheme | The formula is unchanged and S = 95 for every published tag. S = 1–79 (`-beta.N`) and 80–94 (`-rc.N`) are reserved. Tester builds of Mn are `0.{n+1}.P` (M0 `0.1.0` = 10095; M1a `0.2.0`, M1b e.g. `0.2.1`). An out-of-order increment becomes the next PATCH of the current line. M11b candidates are `0.12.P`, then `1.0.0`. M0 commits `neutrodyne.versionName=0.1.0` and `neutrodyne.versionCode=10095`. |
| `scripts/release.sh` | `release.sh <patch|minor|major|X.Y.Z> [--hotfix] [--dry-run]`. `--beta`, `--rc` and `finalise` are removed. The first release is `scripts/release.sh 0.1.0`, which tags the prepared version. |
| Release body | The changelog, the per-ABI file guide, then **"These APKs are debug builds signed with a public key (`signing/neutrodyne-debug.keystore` in this repository). The signature does not show who built a file: download Neutrodyne only from this page and check it:"** followed by `sha256sum --check --ignore-missing SHA256SUMS`, `gh release verify-asset …` and `gh attestation verify …`, and the guide link `{repoUrl}#install-and-update`. The AppVerifier certificate block is removed. |
| Secrets and variables | `NEUTRODYNE_KEYSTORE_B64`, `NEUTRODYNE_KEYSTORE_PASSWORD`, `NEUTRODYNE_KEY_ALIAS`, `NEUTRODYNE_KEY_PASSWORD`, `NEUTRODYNE_CERT_SHA256`, `NEUTRODYNE_PREVIOUS_CERT_SHA256` and `CODEBERG_MIRROR_KEY` are removed. Environment `release` stays (a required reviewer gates publishing; it holds only `PODCASTINDEX_*` after written permission). `NEUTRODYNE_ENGINE_MANIFEST_KEY` stays in `engine-approval`. |
| CI jobs | `ci.yml` `assemble` runs `assembleDebug assembleBenchmark`. In nightly `instrumented-full`, the "release" leg becomes the **benchmark** leg (`-PtestBuildType=benchmark`, minified suite with E7 through `:ytx`). `api37-16k` runs the suite and `zipalign -c -P 16` / `check-apk.sh --alignment-only` on the **published debug** `x86_64` APK. New nightly `dev-tools-build`: `assembleDebug -Pneutrodyne.devTools=true` plus `:app:testDebugUnitTest` with it, non-blocking. `no-engine-build` uses `assembleDebug -Pneutrodyne.youtubeEngine=false`. `repro` builds `assembleDebug` twice and compares the **signed** APKs (Unverified: byte-deterministic v2/v3 signing). The `mirror` job and `baseline-profile.yml` are removed; `keepalive.yml` stays. The `nightly.yml` input `scope: youtube-smoke` now runs E7 on the published debug APK and the benchmark APK. |
| Scripts | `check-apk.sh --published` replaces `--release`. It checks sizes per PB12/PB13; exactly three APKs and no universal APK; `application-debuggable` present, `testOnly` absent and package `ch.lkmc.neutrodyne`; forbidden content now also includes LeakCanary (`leakcanary`, `shark`), `ui-tooling`'s `PreviewActivity`, ui-test-manifest's activity and anything from `app/src/devTools/`. `make-update-json.sh` and `check-update-json.sh` follow the new schema (no certificate fields). New: `debug-cert-sha256.sh`. |
| Licensee task | `licenseeDebug` replaces `licenseeRelease`. `verifyDependencyPolicy` checks `debugRuntimeClasspath` (published) and `benchmarkRuntimeClasspath`, and fails on LeakCanary, `ui-tooling` (non-preview) and `ui-test-manifest` there unless the dev-tools switch is on. `verifyManifestPermissions` reads `debug`'s merged manifest and also fails on an exported test activity. |

## 3. What changed in PLAN.md

- **Requirements.**
  - R6.1 gains the debug-build/public-key statement, and the certificate check is removed.
  - R6.2 is rewritten as notify-and-link.
  - R6.3 is now "Obtainium users can turn the check off; no request without it except Check now".
  - R6.4 is now "the help page explains blocks in Android's installer, plus the PO-36 notice"; "Download in browser" and the in-app detection are removed.
  - N3: wording only ("update check").
  - N5: debug sizes 60/60/50 MB, Unverified; cold start and jank measured on `benchmark`; the published build is recorded report-only; engine budgets on the published APK.
  - N7: no install permission; published APKs are debuggable but carry no dev tooling.
  - N11: "published" replaces "signed".
  - N12: rewritten for integrity without a private key.
- **Traceability.** R6.2–R6.4 and N12 point to `09#update-check`. R6.1 adds `09#versioning-and-signing`. N5 adds `01#build-variants-and-abis` and M0.
- **Decisions.**
  - D2: two build types, dev-tools switch, no `release`, no baseline-profile plugin.
  - D13: `:update:*` dropped, check in `:core:domain`/`:core:model`/`:core:data`.
  - D26: "published builds by `release.yml`".
  - D61: committed keystore, recorded consequences, no rotation runbook, later switch means one reinstall; engine key unchanged.
  - D62: ACRA on in published builds, off in dev-tools builds.
  - D63: one channel, S = 95, `0.{n+1}.P`, every tag a normal release.
  - D77: published (debug) APKs split per ABI, universal ≈ 60–80 MB.
  - D78 is renamed "Update check": notify only.
  - D79: normal immutable releases, no mapping, public-key body text, no mirror, repro compares signed APKs.
  - D80: help page instead of updater detection; registration would need a private key.
- **Product-owner decisions.**
  - PO-31 to PO-35 are resolved, quoting the owner.
  - PO-8 is amended (no custody; `.dev` for dev-tools builds).
  - PO-18: "no mirror".
  - PO-36's default is unchanged, with the remark that M11a can now ship early.
  - PO-2 and PO-5 have their text adjusted.
- **Architecture.** 5.1 removes `:update:api`/`:update:impl` from the graph and rules, and gives the new placement bullet. `:benchmark` measures the `benchmark` build type, and profiles are deferred. 5.2's "App update" flow is replaced by "App update check" with a Mermaid sequence.
- **Roadmap.**
  - M0 (L): `debug`/`benchmark` build types, dev-tools switch, committed keystore and `signing/README.md`, the first release `v0.1.0` (normal), `keepalive.yml`, `dev-tools-build` nightly. The key ceremony, Codeberg mirror and `:update:*` stubs are removed.
  - M0 acceptance criteria: AC1 is now `assembleDebug assembleBenchmark` plus sizes, AC2 uses `licenseeDebug`, AC7 is `v0.1.0` (10095), and AC8/AC9 are new (same certificate on CI and a dev machine with install-over; dev-tools isolation and the `BuildConfig.DEBUG` ban). Dependencies: PO-35 resolved.
  - M9 changes AC4 (60 MB, PB1 on `benchmark`), AC7 (`licenseeDebug`, `check-apk.sh --published` on the published APKs) and AC9 (smoke on published + `benchmark`). `no-engine-build` becomes `assembleDebug`.
  - M10 AC7 is now on `benchmark`, plus a report-only debug run.
  - M11a is now **S**, depends on **M2** and M0 (it previously depended on M6b), and its deliverables are rewritten (check, card, badge, help with the BUILDS card, notice, README).
  - M11b: profiles deferred, report-only debug measurements, no rotation rehearsal, no mirror, release candidates `0.12.P`.
  - M11 acceptance criteria: AC1, 2, 3, 6, 7, 9 and 10 are rewritten; AC4, 5 and 12 are Removed; AC13 is new. Graph edge `M6b --> M11a` is now `M2 --> M11a`.
  - 7.1: the two-engineer text and the out-of-order example `0.3.1` are new.
  - 7.2: `assembleBenchmark` and a new DoD bullet saying no dev tooling may reach a published APK.
- **Risks.** T17, M4r and M6r are retired. T16 now refers to the card link. New: T18 (debug-build performance and size), P10 (public key spoofing) and P11 (debuggable data exposure over ADB). P3, P7 (accepted), P8 and P9 (anyone can register our public certificate) are rewritten.
- **Glossary.** Added: `benchmark` build type, Debug build (published), Dev-tools build and Update check. Update manifest is rewritten. "Installer of record" is removed.

## 4. Heading renames and inbound links

| Document | Old heading → new heading (anchor) | Inbound links to fix (owner) |
|---|---|---|
| 09 | "In-app updater" → **"Update check"** (`#update-check`) | PLAN (done); README R6 row (sweep); 02 ~line 25 (sweep); 03 ~911 (sweep); 05 ~1297 (05); 06 ~22, ~312 (06); 07 ~24, ~1209 (sweep); 08 ~25, ~761 (08); 09-internal ~5, 24, 924, 1374, 1728 (09) |
| 09 | "Key ceremony and custody" → **"Debug keystore"** (`#debug-keystore`) | PLAN (done); 09-internal ~589, ~1308 |
| 09 | "Key loss or compromise" → **"Public key trade-offs"** (`#public-key-trade-offs`) | 09-internal ~1101, 1138, 1397, 1682 (PLAN M11 AC12 is removed) |
| 09 | "Beta channels" → **"Tester builds"** (`#tester-builds`) | none found |
| 09 | "Mirror" → **"GitHub takedown"** (`#github-takedown`) | 09-internal ~530, 569, 630, 1791 |
| 09 | The subsections of the update check "Download and verification", "Installing", "Idle gate" and "Installer of record and Obtainium" are **removed**. Add "Update card and links" (`#update-card-and-links`). Keep "Modules and API", "Update manifest", "Checking", "Notices", "Privacy and failure modes", "Tests" and "Device checklist (M11a)" (anchor `#device-checklist-m11a` kept). | 08 ~842 links `09#installing` (it goes away with the blocked sheet); 09-internal links to the removed subsections |
| 06 | "App updates and playback" is **kept** (rewritten) | — |
| 07 | "App update" is **kept** (rewritten) | — |
| 01, 08 | No renames ("Build variants and ABIs", "Manifest and permissions", "Updates settings" and "Install and updates help" are all kept) | — |
| README | "Install and update" is **kept**: release bodies link `{repoUrl}#install-and-update` | — |

## 5. Per-document checklists

### 5.1 01-foundation.md

- **Header and scope.** Remove "in-app updater" and replace it with "update check (notify only, in `:core:data`)". R6.2–R6.3 now concern the build side only (no install permission). Honours: add D13 (amended), D61, D62, D78 and PO-31 to PO-35.
- **Toolchain.**
  - LeakCanary row: "dev-tools builds only".
  - Catalog `ui-tooling`: only through the dev-tools switch.
  - `ui-test-manifest`: `testImplementation`/`androidTestImplementation` only, never `debugImplementation`. Unverified: how `:app`'s own instrumented tests host composables without it (`createAndroidComposeRule<MainActivity>()` is the default proposal; 09 decides). It must never ship an exported test activity.
  - Remove the `androidx.baselineprofile` plugin entry if the catalog lists it, and keep `benchmark-macro-junit4`.
- **Settings and properties** (`settings.gradle.kts`, `gradle.properties`).
  - Remove `include(":update:api", ":update:impl")`.
  - Document `neutrodyne.devTools` (not committed; default false; `~/.gradle/gradle.properties`).
  - `BuildInfo` no longer derives `releasesAtomUrl`.
  - Version lines: `0.1.0` / `10095`.
- **Convention plugins.** `neutrodyne.android.compose`: `debugImplementation(ui-tooling)` only when the dev-tools switch is on; drop `debugImplementation(ui-test-manifest)`. `neutrodyne.android.application` sets the two build types, the signing config and the disabled `release` variant (§2.1).
- **Gradle-side policy tasks.**
  - `verifyDependencyPolicy` and `verifyManifestPermissions` switch from `release` to `debug` (and `benchmark`). Remove the reason "(debug merges LeakCanary and test-only entries)" and replace it with the rules in §2.3, Licensee task row.
  - `checkBannedApis`: `PackageInstaller` anywhere; `BuildConfig.DEBUG`; the install permissions in manifests.
- **Module layout.**
  - Delete the `:update:api` and `:update:impl` rows.
  - `:core:testing` and `:feature:settings` no longer depend on `:update:api`; `:feature:settings` reaches the checker through `:core:domain`.
  - `:core:data`'s row gains the update-check classes (M11a).
- **Dependency rules and assertion config.**
  - Remove `:update:*` from rules 2, 8, 9 and 10 and from the module-graph assertion regexes (`:(playback|download|youtube|update):api` becomes `:(playback|download|youtube):api`; drop `:update:impl` lines).
  - Mermaid (~549–576): drop `upi` and `:update:api` from the `apis` node, and keep the diagram valid.
- **Application start-up.**
  - Order 200: `app-update-check` is enqueued only while `updates.check_enabled` is on and not in dev-tools builds; the "another installer of record" clause is removed.
  - Logging: `LogcatSink(if (BuildConfig.DEV_TOOLS) …)`. StrictMode only via `DevToolsInitializer`.
  - Remove `BuildConfig.DEBUG` everywhere (~818, 844–854).
  - The process-name table (~1894) changes `ch.lkmc.neutrodyne.debug:ytx` to `ch.lkmc.neutrodyne.dev:ytx`.
  - The ~1895 "test-only probe in debug builds" moves into the androidTest APK or becomes an inert hook.
- **DI table.**
  - `AppUpdater, UpdateNotices` → `AppUpdateChecker, UpdateNotices` bound by `:core:data` `UpdateModule`.
  - Drop `UpdateStatusReceiver`.
  - Remove the ~1016 sentence about `:update:impl` needing no switch.
- **Intent routing.** `…/open/settings/updates` stays.
- **Build variants and ABIs (main rewrite).**
  - The build-type table becomes `debug` (published), `benchmark` and dev-tools builds, with the columns applicationId, shrinking, debuggable, signing and use.
  - Kotlin sketch: `neutrodyneDebug`, `benchmark`, the disabled `release` variant, the dev-tools switch (source dir, suffix, LeakCanary, `DEV_TOOLS` field, ACRA `""`), splits unchanged (cite [configure APK splits](https://developer.android.com/build/configure-apk-splits)).
  - Budgets 60/60/50 MB; universal ≈ 60–80 MB.
  - "the in-app updater picks…" becomes "the update card links…".
  - `BuildInfo` per §2.1.
  - Source sets: `main` plus `app/src/devTools/` (dev only) plus the engine switch dirs; no `src/debug/`.
  - Keep rules apply to `benchmark` only, and stay maintained for a later release build.
  - Tests: unit tests on `debug`; the minified `benchmark` runs the nightly benchmark leg and E7.
  - Record the trade-offs briefly with links to PLAN D61/T18/P10/P11 (do not restate them in full).
  - Emergency build: `assembleDebug -Pneutrodyne.youtubeEngine=false`.
- **Networking.**
  - The DOWNLOAD client no longer carries update APKs (~1331).
  - The API client carries the update manifest GET.
  - Delivery "M9b and M11a (engine and app updates)" becomes "M11a (update check)".
  - `DebugHttpLogInterceptor` is for dev-tools builds only.
- **Platform compliance.**
  - P36, P37, P38 and P39 become "Not applicable since 2026-10-05 (no in-app install, PO-31)"; keep the rows so the IDs stay stable.
  - P26 `<queries>`: drop Obtainium's two IDs.
  - Add a row (next free P-number) for "published APKs are debuggable": run-as, JDWP and `adb backup` on API 31+, citing the sources in §7 and PLAN P11.
- **Manifest and permissions.**
  - Remove the two install permissions, the Obtainium `<queries>` and `UpdateStatusReceiver`.
  - Add `REQUEST_INSTALL_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION` and `REQUEST_DELETE_PACKAGES` (already there) to "Explicitly not requested".
  - The "Serves" line no longer serves R6.2–R6.3 install. `permissions.txt` gains nothing in M11a.
  - Note that the merged manifest has `android:debuggable="true"` (set by AGP for `debug`) and must not contain `testOnly`.
- **M0 scaffold checklist.** Add keystore and `signing/README.md`, the dev-tools switch, `debug-cert-sha256.sh` (09 owns the script, 01 lists the file) and `0.1.0`/`10095`. Remove the `:update:*` stubs.
- **Spikes.**
  - S7 measures the **debug** APK sizes (and the `benchmark` sizes for comparison).
  - Add a small M0 check (in S1 or the verification log): the certificate is identical across machines, and `aapt2 dump badging` shows no `testOnly`.
  - Verification log: negative check "GPL artifact fails `licenseeDebug`".
- **Bookkeeping.** Delivery, new names (`neutrodyneDebug`, `benchmark`, `neutrodyne.devTools`, `BuildConfig.DEV_TOOLS`, `BuildInfo.devTools`, `app/src/devTools/`, `DevToolsInitializer`, `signing/neutrodyne-debug.keystore`), open questions (resolve any about key or updater placement) and sources (§7).

### 5.2 04-youtube.md

- **Header.** PO-32 is **resolved** ("Neutrodyne-approved, automatic"). Rewrite the "defaults" wording in the header, in Engine updates › Policies (~1468) and in Settings and Delivery (~1529).
- **Engine canary and hotfix runbook** (~1406–1442).
  - "tag → signed GitHub release < 30 min" becomes "tag → published GitHub release < 30 min".
  - The `youtube-smoke` dispatch runs on the published debug APK and the `benchmark` APK (not "minified `release`").
  - "While `main` carries a pre-release of the next minor" becomes "while `main` already carries work for the next MINOR".
  - "the in-app updater and Obtainium pick it up" becomes "the update check announces it, and users install it from GitHub or through Obtainium".
- **Licensing and legal › Posture** (~1385). "risk P7 with a mirror question (PO-34)" becomes "risk P7, accepted without a mirror (PO-34)".
- **Strict/contract replay text** (~1444). "minified release smoke test" becomes "the smoke test on the published and `benchmark` APKs".
- **Security notes and trust chain.** Engine-manifest key custody: there is no app-key ceremony to reference any more. The Ed25519 key is generated once in M9b by a maintainer (`openssl genpkey -algorithm ed25519`) and kept only as `NEUTRODYNE_ENGINE_MANIFEST_KEY` in `engine-approval`; slot 2 stays as the rotation key. If both keys are lost, new keys are pinned in the next APK, and until users install it their engines stay on the last approved version ("upstream stable" still works). Keep the claim that a stolen key can only choose among genuine upstream releases. Coordinate wording with 09 › engine-canary.yml.
- **Testing and Delivery.**
  - Any "release APK" or "minified release" becomes the published debug APK, or `benchmark` where R8 matters (keep rules for `PyHttp`, `QuickJsEngine`, `YtxTestHooks`).
  - The engine budgets are measured on the published `arm64-v8a` APK.
  - The M11 row: "licence review of the engine stack on the published APKs".
- **Process names.** Any `.debug:ytx` becomes `.dev:ytx` (dev-tools builds only). Note that `run-as ch.lkmc.neutrodyne` now works on the published APK (PLAN P11): `:ytx` engine files are writable by anyone with ADB access, which the self-test does not defend against. Record it in Security notes as accepted with P11.

### 5.3 05-groups-opml-backup.md

- **Header.** "in-app updater" becomes "update check". Delivery M11a: `updates.check_enabled` travels in backups and the device keys never do.
- **Settings whitelist table** (~1290–1297).
  - Replace the `updates.mode`/`updates.channel` row with `updates.check_enabled` (`settings`, portable, M11a).
  - Device row: `updates.last_check_at`, `updates.skipped_version_code`, `updates.notified_version_code`, `updates.first_run_choice_done` and `updates.verification_notice_shown_at`. Remove `updates.whats_new_version_code`.
  - The restore note becomes: "a restored `updates.check_enabled` is a preference; dev-tools builds stay `Disabled(DEV_BUILD)`". Remove Obtainium and installer-of-record.
- **Exclusions** (~102, ~1153). The "updater's downloaded APKs (`noBackupFilesDir/updates/`)" become "the update check's cache `noBackupFilesDir/updates/last-check.json`", still never backed up.
- **Rotation and signing history** (~102–103, ~1703).
  - Remove the v3.1-rotation open question.
  - State instead that Auto Backup and restore require the same signing certificate. Every Neutrodyne build shares the public key, so any APK signed with it (a spoofed one included, PLAN P10) can receive the restored data.
  - A later move to a private key breaks Auto Backup restore across the switch, and the manual backup ZIP is the path (PLAN D61).
- **Debug-only hooks** (~1198, ~1452). The "debug builds' Write snapshot now" becomes **dev-tools builds only**. CI's `bmgr` job runs on the published build without dev tools, so give it a shell-only trigger instead. Proposed: a `SnapshotNowReceiver` in `main`, exported with `android:permission="android.permission.DUMP"` (shell-held, like 09's `BenchmarkSeedReceiver`), which calls `writeSnapshotNow()`. 05 decides and names it, and tells 08 and 09.
- **Install paths** (~1311, ~1574, ~1651). "Updates — by the in-app updater, Obtainium or a manual install" becomes "by a manual install of the downloaded APK, or Obtainium". "release APK" becomes "published APK".
- **`bmgr` package.** Everywhere it is `ch.lkmc.neutrodyne` (no `.debug`).

### 5.4 06-playback.md

- **Header and Owns.** Remove "the playback-busy signal the in-app updater waits on" and R6.2 (playback side). Remove D78 from Honours unless it is still cited.
- **Scope row** (~22). Delete the "Playback-busy signal for app updates" row, or rewrite it as "App updates: none; a manual install ends the process like any update".
- **"App updates and playback"** (keep the heading).
  - Rewrite: the user installs updates with Android's installer, and installing kills the app's processes like any update. Keep the `SessionParams.setDontKillApp` citation only if still useful, otherwise drop it.
  - Nothing waits on playback. Positions are saved at the 5-s tick and on pause (N1). After the update nothing plays by itself (D43); resumption works as specified.
  - Engine updates restart only `:ytx` (unchanged).
  - Remove `busy`, the `busy` table, `PlaybackStateHub`'s `busy` computation, `GENTLE_UPDATE` and the 10-min window.
- **API.** Remove `PlaybackStateSource.busy` (~92), its new-names row (~208), the threading rows (~1054, ~1080), `PlaybackStateHubTest (busy)` (~1128), the "Updater additions" device-matrix sentence (~1135) and open question 16 ("Resolved 2026-10-05: no install gate, PO-31").
- **Delivery.**
  - M11a: nothing in 06 (or "nothing; the update check does not touch playback").
  - M11b: "device matrix re-run, hardening and boot tests on the published debug APKs", not "minified `release`".
  - ~1129: `setForegroundServiceTimeoutMs` "in a debug build" becomes "set by the instrumentation test through an inert hook" (the published build is a debug build).
- **Sources.** Drop `SessionParams` and `InstallConstraints` if no longer cited.

### 5.5 08-ui-ux.md

- **Header, scope (~25), platform constraints (~68), screen inventory (~117–121) and deep links (~235).** Replace the updater with the update check. Remove `UpdateBlockedKey` and `WhatsNewKey`. Keep `neutrodyne://open/settings/updates` and `…/open/help/install`.
- **Settings home** (~745–753).
  - Updates row summary: "On · checked {relative}", "Update available: {v}" (with the dot badge), "Off" or "Off in dev builds". Drop "Notify · Stable", "Automatic · Beta" and "Managed by Obtainium".
  - A dot badge on the gear in top bars and on the rail footer while `Available` and not skipped.
- **Updates settings (rewrite; keep the heading).**
  - Data: `AppUpdateChecker.state` (`:core:domain`) and `updates.check_enabled`/`updates.last_check_at` via `SettingsRepository`.
  - New wireframe: version line; the update card when `Available` (title "Update available: {v}", "Released {date} · APK {size}", 3 lines of notes with "More", a copyable "SHA-256 of {file}" line, buttons "Open release on GitHub", "Download APK for this device" and "Skip this version"; when `apk == null` only "Open release on GitHub" with "No APK for this phone's processor ({abi}) in this release"; when `minSdk > SDK_INT`, "This version needs Android {x}" and only the release link); the "Check for updates" switch; "Last checked …" with "Check now"; the notifications-blocked row; "Install & updates help"; the footer from §2.2.
  - The status-card table covers only `Disabled(DEV_BUILD)` ("Update checks are off in development builds"), `Disabled(CHECKS_OFF)`, `Idle`, `Checking`, `Available` (plus the `lastError` line) and `Failed(NETWORK|RATE_LIMITED|MANIFEST_INVALID)`, with the texts reused.
  - Remove: the Mode radio rows, Beta versions, the Downloading/ReadyToInstall/WaitingForIdle/Installing/PendingUserAction/Blocked cards, the `UpdateError` texts other than the three left, the **Update blocked sheet**, the **What's new sheet** and the "Install now"/install-permission prompt.
  - First-run card text stays. "Turn off" writes `updates.check_enabled = false`, and the snackbar reads "Update checks are off" with "Undo".
  - The notification table shrinks to the single `Available` row (§2.2).
  - The verification notice is kept unchanged.
  - Wording must never promise that the app installs anything.
- **Install and updates help.**
  - CHECK card: remove the runtime certificate display and AppVerifier. New text: "Neutrodyne's APKs are signed with a public key, so a matching signature doesn't show who made a file. Download only from github.com/{owner}/Neutrodyne/releases and compare the file with SHA256SUMS", plus the two `gh` commands.
  - ALLOW card: remove "Neutrodyne needs the same permission once".
  - OBTAINIUM card: replace "the built-in updater stays off" with "you can turn off Neutrodyne's own update check in Settings › Updates", and remove "for beta versions turn on 'Include prereleases'".
  - Add a **BUILDS** card ("About Neutrodyne's builds"): debug builds by the maintainers' choice; public key, so download only from GitHub (PLAN P10); anyone with USB debugging access to an unlocked, authorised phone can read the app's data, so turn USB debugging off when you don't need it (P11); smoothness lower than a release build (T18). Keep it neutral and short.
  - Section enum and caller list updated (`BUILDS`). The `VERSION` card text "The built-in updater switches to it with the next update" becomes "Settings › Updates then links the 64-bit file".
  - The ADVANCED card adds "turn USB debugging off afterwards".
- **Backup screen** (~713). The "Debug builds only" row becomes "Dev-tools builds only", following 05's decision on the CI trigger.
- **Permission prompts** (~2005–2016). Remove the install-permission prompt. The notification prompt on the first-run card's OK stays.
- **Settings structure and keys owned** (~2077, ~2119). `updates.check_enabled`, not mode or channel.
- **Testing.** `UpdatesSettingsScreenTest` uses `FakeAppUpdateChecker` and covers the cards and links (verify that the link intents are `ACTION_VIEW` of the exact URLs), the switch, the badge and the BUILDS card. Remove the blocked-sheet and What's-new tests and their screenshot matrix rows.
- **Bookkeeping.** Delivery M11a; new names; open questions (resolve the updater ones as "Resolved 2026-10-05 (PO-31)"); sources.

### 5.6 09-quality-and-release.md (largest change)

- **Header and Scope table** (~3–35).
  - Rows: "Version scheme procedure, `release.sh`, changelogs, the committed debug keystore, signing schemes, public-key trade-offs".
  - "GitHub Releases …: release assets, immutable releases, attestations, release body, Obtainium, tester builds, GitHub takedown".
  - "Update check behaviour: update manifest, checks, update card and links, notices" (`#update-check`).
  - "Reference devices, budgets…, Macrobenchmark wiring" (no profiles).
  - Owned files: remove `baseline-profile.yml`; add `scripts/ci/debug-cert-sha256.sh`.
  - Not covered: build types and the dev-tools switch are 01's.
- **Test infrastructure.**
  - GMD "Release-build runs" (~428) becomes the "Benchmark-build runs" bullet: `-PtestBuildType=benchmark`, already signed with `neutrodyneDebug`, with no special signing branch.
  - "ABI splits on emulators" fallback: replace "only when a `release`-assembling task is requested" with a property set by `release.yml` and the `assemble` job.
  - Hygiene (~430): "debug builds disable LeakCanary heap dumps…" becomes "dev-tools builds only". ACRA is **on** in published builds, so instrumented tests must disable it (Unverified mechanism: an instrumentation argument or `isRunningInUserTestHarness()`).
  - Out-of-process system tests (~434): package `ch.lkmc.neutrodyne`, Macrobenchmarks against `benchmark`, no baseline-profile plugin.
  - Recorded responses (~438): remove `releases.atom`.
  - Decide how `:app` instrumented tests host composables without `ui-test-manifest` in the published app (§5.1).
  - Pseudo-locales (~389, ~1563): `:app` generates `en-XA`/`ar-XB` only in dev-tools builds, and `localeFilters` exclude them otherwise. Library modules keep them on `debug` for screenshot tests. Unverified that library pseudo-locale resources never reach the app's merged resources; `check-apk.sh` asserts it.
- **CI pipelines.**
  - `ci.yml` assemble: `assembleDebug assembleBenchmark`.
  - `nightly.yml` table: the "release" leg becomes the benchmark leg; `api37-16k` runs on the published debug APK; `dev-tools-build` is new; `no-engine-build` runs `assembleDebug`; `repro` runs on signed debug APKs; the `mirror` row is removed.
  - `release.yml` (sequence diagram and steps):
    - no environment secrets to decode;
    - the container runs `repro-build.sh assembleDebug`, which signs with the committed keystore;
    - step 3: `apksigner` v2+v3, no v1, signer equals `debug-cert-sha256.sh`, `aapt2 dump badging` (package, debuggable, no `testOnly`), `zipalign -c -P 16`, `check-apk.sh --published`;
    - no mapping asset;
    - the manifest per §2.3;
    - `SHA256SUMS` over four files;
    - step 8 is never `--prerelease`, with `make_latest` per §2.3;
    - the Codeberg push in step 9 is removed;
    - the diagram's "three signed ABI APKs and the R8 mapping" becomes "three debug ABI APKs", and "in-app updaters" becomes "update checks". Mermaid must stay valid.
  - `engine-canary.yml`:
    - key custody paragraph per §5.2 (no "same procedure as the app key", no "with the app-key backups");
    - heartbeat "second alarm on the Codeberg mirror" is removed; the stale-heartbeat line in Settings › YouTube and diagnostics is the alarm, and an outside check is optional and Unverified;
    - helper workflows: remove `baseline-profile.yml`; `keepalive.yml` stays;
    - secrets table: remove the keystore secrets, `CODEBERG_MIRROR_KEY` and `NEUTRODYNE_CERT_SHA256`;
    - "No other secret exists" stays true;
    - hardening: the `release` environment still has a required reviewer but holds no signing secret.
- **Static analysis and build-output checks.** `check-apk.sh --published` (§2.3); Licensee `licenseeDebug`; the PR template drops any key or updater checkbox, if one exists.
- **Versioning and signing.**
  - Version scheme table: keep the formula. Mark the `-beta.N`/`-rc.N` rows "reserved, unused since PO-33". The examples become `0.1.0` → 10095, `1.0.0` → 1000095 and `1.2.3` → 1020395.
  - Rewrite the tester-build rules per §2.3.
  - Replace the stateDiagram (Beta, RC, Stable) with a valid one (Stable, Patch, Minor, Hotfix) or remove it.
  - `release.sh`: new usage per §2.3, and the hotfix example without pre-releases.
  - Changelogs: "Tester build for milestone Mn." stays as the first line of tester-build notes (normal releases now).
  - "Key ceremony and custody" becomes **"Debug keystore"**: what is committed, the generation command, why it is public, `signing/README.md`, and the expected-certificate script. No holders, backups or yearly checks.
  - **Gradle signing configuration**: the new snippet (§2.1); no environment variables; no unsigned builds (every build is signed with the committed key, the repro job included).
  - "Key loss or compromise" becomes **"Public key trade-offs"**: the table from PLAN D61, P10 and P11 (spoofed updates and forks, ADB data access, no meaningful fingerprint) and what a later switch to a private key costs (one reinstall, Auto Backup restore lost across the switch, v3 rotation reaching API 28+ only, Unverified safety with a public old key). Remove the v3.1 runbook.
- **Distribution channels.**
  - The channel table: GitHub Releases ("committed debug key (v2 + v3)", normal releases); "In-app update check": "notification and links to the release; the user installs" (`updates.check_enabled`); Obtainium, without "Include prereleases".
  - Release assets per §2.3 (mapping row removed; "signed with v2 and v3" kept; universal ≈ 60–80 MB).
  - Integrity paragraph: "The signing certificate is the trust anchor" becomes "the key is public: the release page, immutable releases, `SHA256SUMS` and attestations are the trust anchors".
  - Release body per §2.3.
  - Obtainium: remove the installer-of-record bullet and add "Obtainium users can turn off the in-app check".
  - "Beta channels" becomes **"Tester builds"** (normal releases, `0.{n+1}.P`, every installed copy is notified).
  - "Mirror" becomes **"GitHub takedown"** (accepted, PO-34; what keeps working).
- **"In-app updater" becomes "Update check" (rewrite).**
  - Intro: notify only; never `api.github.com`; never downloads, verifies or installs.
  - **Modules and API**: §2.2 types and placement, with the table of `:core:data` classes. Replace the state diagram with a small valid one: Idle → Checking → Available | Idle | Failed, plus Disabled.
  - **Update manifest**: the new schema and the CI/app rule table; the manifest stays unsigned, and why that is fine (the app only shows links under `{repoUrl}/releases/` and an informational SHA-256; integrity comes from GitHub).
  - **Checking**: stable `latest/download` only; the failure mapping; the work table with only the two works; the ABI selection.
  - New **"Update card and links"**: what the card shows; `ACTION_VIEW` of `releaseUrl` and `apk.url`; skip; the badge; notifications once per version (`updates.notified_version_code`); `ActivityNotFoundException` → copy the link.
  - **Notices**: `FIRST_RUN_CHOICE` (not in dev-tools builds; "Turn off" writes `updates.check_enabled = false`) and `VERIFICATION_ENFORCEMENT` (unchanged rules, "not in dev-tools builds"). Remove `WHATS_NEW`.
  - **Privacy and failure modes**: the two hosts; no backup except `updates.check_enabled`; process death harmless.
  - **Tests**: `UpdateManifestParserTest`, `GitHubUpdateSourceTest` (redirect, 404, 403/429, I/O, link-prefix rejection, `api.github.com` interceptor), `AppUpdateCheckerTest` (states, checks off, `checkNow` with checks off, skip, notify once, failed check keeps `Available`, dev build) and `UpdateNoticesTest`, all in `:core:data`. Remove the APK, installer, idle-gate and verification-mapper tests.
  - **Device checklist (M11a)**: the PLAN M11 AC3 and AC6 device parts (two consecutive releases; notification; both links; manual install over the running app on API 26 and API 37 keeps data; an `armeabi-v7a` install on a 64-bit phone links `arm64-v8a`; no `api.github.com`).
- **Reproducible builds.**
  - Hygiene: signing is now deterministic with the committed key, so the job compares signed APKs (Unverified until the job shows it).
  - "release built unsigned when `NEUTRODYNE_KEYSTORE` is absent" is removed.
  - The `isCrunchPngs` and `vcsInfo` rules move from `buildTypes.release` to `debug`. AGP's debug defaults may already disable PNG crunching; still set it explicitly. Unverified: `vcsInfo` on debug.
  - The ArtProfile row is irrelevant (no profile in a debuggable build); keep it as a note.
  - The job builds `assembleDebug`. "Anyone can repeat" compares whole APKs.
- **Developer verification.**
  - Remove every updater detection (`Blocked(DEVELOPER_UNVERIFIED)`, "Download in browser", the self-update tests via `pm set-developer-verification-result`; PLAN M11 AC5 is removed).
  - The situation table's "Update" column becomes "Update (APK downloaded from GitHub or by Obtainium)", explained by the help page.
  - "Package name and key": remove "the key is kept so the owner can still register" and "key custody rules (risk M4r)". New text: the public key means anyone can register it (PLAN P9), and registering ourselves needs a private key and one reinstall.
  - "Development builds (`.debug`) are installed over ADB and are exempt" becomes "dev-tools builds (`.dev`)…".
  - Testing: CI never sees enforcement; the help text is checked by hand on an enforcing device in 2027.
- **README "Install and update" draft.**
  - Item 2: no certificate. Explain the public key, then `SHA256SUMS` and the `gh` commands.
  - Item 3: drop "Neutrodyne asks for the same permission once".
  - Item 7: "Neutrodyne checks GitHub once a day and tells you about new versions, with a link to download them (Settings › Updates › Check for updates)… Or use Obtainium…; you can turn Neutrodyne's own check off". Remove "turn on Include prereleases only for test versions".
  - Add an item **"About these builds"** (debug builds; public key, so download only from GitHub; USB-debugging data access; less smooth). Advise turning USB debugging off after an ADB install in item 6.
- **Privacy.** Network inventory `app-updates`: a check-only description. "Delivered … M11a app updates" becomes "M11a update check".
- **Crash reporting.**
  - ACRA: `ACRA_MAILTO` is forced to `""` only in dev-tools builds, so ACRA is active in published debug builds (D62).
  - "Delivered … M11a (updater lines)" becomes "update-check lines" (last check, last result).
  - Diagnostics contents: the "updater" lines become last check, result and available version.
- **Performance budgets.**
  - PB1–PB5: "measured on `benchmark`" with the compilation mode (§2.1), plus the report-only debug-build rows (add PB22 "published debug build cold start p50, report-only" and PB23 "published debug build CoverGridFling jank, report-only", or one row; 09 decides the IDs and keeps the existing ones).
  - PB12/PB13 at 60/50 MB (published debug APKs).
  - "Gate: release" becomes "Gate: v1.0 (M11b)".
  - **Macrobenchmark and profiles** (keep the heading): no `androidx.baselineprofile` plugin. `:benchmark` uses `com.android.test` with `targetProjectPath = ":app"` and the target build type `benchmark` (Unverified: the `com.android.test` property that selects the target variant). `BenchmarkSeedReceiver` lives in an `app/src/benchmark/` source set (build-type source set, never in `debug`). Profiles are deferred, with a sentence on why ([Baseline Profiles overview](https://developer.android.com/topic/performance/baselineprofiles/overview), [ART JIT for debuggable](https://android.googlesource.com/platform/art/+/a0619e2%5E%21/)). The R8 bullet applies to `benchmark`, and the size tips to the debug APKs.
- **Release checklist.**
  - Every release: no certificate step; check that the release is "latest" and not pre-release.
  - Minor and stable additions: README/help parity now includes the BUILDS card.
  - Hotfix: published release.
  - Milestone tester build: a normal release, `0.{n+1}.P`.
  - v1.0 gate: PLAN M11 AC1, 2, 3, 6, 7, 9, 10 and 13 (the AC4, AC5 and AC12 rows are removed or marked removed).
- **Settings section** (~1688–1712). `updates.check_enabled` and the device keys per §2.2.
- **Bookkeeping.**
  - Delivery: M0 (keystore, `v0.1.0`, `dev-tools-build`), M11a (update check), M11b (no rotation rehearsal, no mirror decision).
  - New names.
  - Open questions: mark the updater, mirror and key questions "Resolved 2026-10-05 (PO-31/33/34/35)" and keep the numbers.
  - Sources: remove `InstallConstraints`, `SessionParams`, `PackageInstallerSession` and AppVerifier unless still cited; add §7.

## 6. Sweep: 02, 03, 07, README.md

**6.1 02-data-model.md**

- Header: "in-app updater" becomes "update check".
- ~25: "the in-app updater's downloaded APKs (`noBackupFilesDir/updates/`)" becomes "the update check's cache file `noBackupFilesDir/updates/last-check.json`". Drop "The updater writes no `download` rows: its APK transfer…", or reduce it to "the update check writes no rows".
- ~35: `:update:impl` is removed from the list (the update check is in `:core:data`, which does use 02's interfaces through `:core:domain`; it touches no table).
- ~52: drop "never the in-app updater".
- ~1594: "A version is frozen once any tagged build (`vX.Y.Z-beta.N` or release) contains it" becomes "once any tagged release (`vX.Y.Z`) contains it".
- ~1651: "upgrade a device from the last beta through the in-app updater keeping all data (the updater re…" becomes "upgrade a device from the previous release by installing the downloaded APK over it, keeping all data (PLAN M11 AC9)".
- ~1702: "crash in debug builds" becomes "crash in dev-tools builds" (published builds are debug builds).
- ~1765: Delivery M11a: "no schema change — the update check keeps its state in DataStore and `noBackupFilesDir/updates/last-check.json`"; and "including an upgrade by a manual install".
- Fix the link `09#in-app-updater` → `09#update-check`.

**6.2 03-feeds-and-discovery.md**

- ~911: "an app update installed by the in-app updater, which waits for playback and downloads but not for a refresh" becomes "an app update the user installs (it ends the process like any update; the refresh resumes as after a process death)". Fix the link to `09#update-check`, or drop the link.
- ~1192 and any "release builds" (Podcast Index key) become "the published builds built by `release.yml`" (D26).
- ~1245: re-read it; "debug builds, PR and nightly CI builds (including the report-only reproducibility…) contain no key" must still be true. Phrase it as "dev-tools builds, PR and nightly CI builds…; only `release.yml` may inject the key".
- ~1465: "public GitHub release APKs" is fine.

**6.3 07-downloads.md**

- Header and Owns: remove "the download-busy signal the in-app updater waits on" and "R6.2 (download side…)".
- ~11: drop "and the download-busy signal for the in-app updater in M11a".
- ~24: delete the scope row, or rewrite it as "App updates: none; the update check does not download APKs".
- ~64, ~123, ~1013, ~1307: remove `DownloadProgressSource.busy`, its fake, its new-names row and the M11a test cases.
- "App update" (~1205–1209; keep the heading): rewrite as "a manually installed update ends the process; transfers resume from their `.part` files as after any process death ([Process death](#process-death)); the app never downloads APKs (PLAN D78)". Remove the links to `06#app-updates-and-playback` if they no longer add anything, or keep them because the heading is kept.
- ~1326: E7 on "the published debug APK and the minified `benchmark` APK", not "minified `release`".
- ~1330: `run-as ch.lkmc.neutrodyne.debug kill <pid>` becomes `run-as ch.lkmc.neutrodyne kill <pid>`. This now works on published builds; that is PLAN P11 and needs no comment beyond a link.
- ~1380: replace the updater-permissions sentence with "The app declares no install permission (PLAN N7)".
- ~1424: Delivery M11a: "nothing"; M11b unchanged.
- ~1469 sources: drop `SessionParams` if no longer cited.

**6.4 README.md**

The section and heading "Install and update" stay; the anchor is used by release bodies.

- Headline row R6: "Installing and updating from GitHub Releases only: per-ABI APKs (debug builds signed with a public key) with checksums and attestations; a daily update check that notifies and links to the new release; guidance for Android's developer verification". The link `09#in-app-updater` becomes `09#update-check`.
- Document table, 09 row: "in-app updater" becomes "update check (notify only)"; add "debug keystore".
- "How the documents are organised": fine as is.
- Install and update:
  - Item 1 unchanged.
  - Item 2 becomes **"Check it (optional)"**: no certificate block (delete the AppVerifier fence). Text: "Neutrodyne's APKs are debug builds signed with a key that is public in this repository, so the signature doesn't prove who built a file — download only from this repository's releases page and compare the file with the release's `SHA256SUMS`, or run `gh release verify-asset` and `gh attestation verify`."
  - Item 3: drop "To install its own updates, Neutrodyne asks for the same permission once."
  - Item 4 unchanged, plus "turn USB debugging off again after an `adb install`".
  - Item 5 becomes **"Updates"**: "Neutrodyne checks GitHub once a day and, when a new version exists, notifies you and links to it (Settings › Updates › Check for updates); download the APK for your phone and install it over the old one — your library stays. Or use Obtainium with an APK filter for your file (e.g. `neutrodyne-.*-arm64-v8a\.apk$`) and turn Neutrodyne's own check off. YouTube engine updates arrive separately and need no install."
  - New item **"About these builds"**: the owner publishes debug builds. Consequences: (a) download only from GitHub; anyone can sign an APK that installs over Neutrodyne; (b) anyone with USB-debugging access to your unlocked, authorised phone can read Neutrodyne's data; (c) scrolling and start-up are less smooth than a release build would be. A later switch to a private key would need one reinstall (make a backup).
  - Items 6 and 7 unchanged.
  - Link the full guidance (`09#readme-install-and-update`) and `09#public-key-trade-offs`.
- Licence and crash-reporting sections: no change.

## 7. Verified sources (2026-10-05) for the new claims

| Claim | Source |
|---|---|
| `android:debuggable` lets the app be debugged even in user mode; `android:testOnly` APKs install only through adb, and "Android Studio automatically adds this attribute when you click Run" | https://developer.android.com/guide/topics/manifest/application-element |
| `run-as` rejects non-debuggable packages ("package not debuggable") | https://android.googlesource.com/platform/system/core/+/refs/heads/main/run-as/run-as.cpp |
| Android 12: `adb backup` excludes app data for apps targeting 31+ unless `android:debuggable="true"` | https://developer.android.com/about/versions/12/behavior-changes-12 |
| Build types, `signingConfigs` declared and assigned per build type, `initWith`; AGP gives `debug` `debuggable true` and a generic debug keystore | https://developer.android.com/build/build-variants |
| The debug certificate is "insecure by design"; most stores reject it; it expires 30 years after creation | https://developer.android.com/studio/publish/app-signing |
| ABI splits produce `modulename-ABI-buildvariant.apk` for the configured variants; a universal APK only with `universalApk true` | https://developer.android.com/build/configure-apk-splits |
| Macrobenchmark targets must be non-debuggable and profileable; it errors otherwise (suppressible with `androidx.benchmark.suppressErrors`); recommended `benchmark` build type with `initWith` and the debug signing config | https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview |
| `ApplicationBuildType.isProfileable` | https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/ApplicationBuildType |
| Baseline Profiles are installed for local non-debuggable builds (AGP 8.4+); non-Play channels may not apply them at install | https://developer.android.com/topic/performance/baselineprofiles/overview |
| ART: "Java debuggable now solely relies on JIT" (2017; Unverified for current ART service versions) | https://android.googlesource.com/platform/art/+/a0619e2%5E%21/ |
| Compose: debug mode "imposes a performance cost"; use release mode with R8 | https://developer.android.com/develop/ui/compose/performance |
| APK Signature Scheme v3 key rotation (API 28+, v3.1 on 33+), only for the "later private key" note | https://source.android.com/docs/security/features/apksigning/v3 |

Still **Unverified** (mark them so where you write them): AGP 9 DSL for disabling the `release` variant and for `com.android.test`'s target build type; AGP 9 debug split output names; that a command-line `assembleDebug` never sets `testOnly` (M0 AC7 checks it); byte-deterministic v2/v3 signing in the repro job; whether push protection flags the committed PKCS12 file; Macrobenchmark's `DEBUGGABLE` error ID; which backup rules `adb backup` applies to a debuggable app; whether v3 key rotation from a public key gives any protection; whether `ui-test-manifest`'s activity is exported (it must not ship either way); and the debug APK size estimates.

## 8. Reporting back

Report to the orchestrator:

- every heading you renamed and every inbound link you could not fix;
- any PLAN conflict;
- every new name you introduced that is not in §2;
- any open question that another document must answer.

08 and 09 must agree on the update-card texts and the BUILDS card. 05 must tell 08 and 09 the shell-only snapshot trigger.
