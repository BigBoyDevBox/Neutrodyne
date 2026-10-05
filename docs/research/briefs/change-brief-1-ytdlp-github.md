# Change brief — revision for the product owner's decisions of 2026-10-05

For the nine design-doc revisers (01–09). PLAN.md has already been revised and is the source of truth: read its sections 2 (R3, R6, N3, N5, N7, N8, N11, N12), 3 (D2, D3, D13, D26, D39, D51, D52, D60–D64, D72–D80), 4, 5, 7 (M0, M8, M9, M11) and 8 before editing. Where this brief and PLAN.md disagree, PLAN.md wins; report the conflict.

## 0. Rules for every reviser

- Edit only your own document. Do not create files. Do not edit README.md (see §11, handled by the lead/orchestrator).
- Keep each document's style, structure, heading names and anchors wherever possible. Rename a heading only where §8 or your checklist says so; then fix every inbound link listed in §8 that lives in **your** document (other documents' links are in their own checklists).
- GitHub-flavoured Markdown. Every Mermaid block must still parse (no `;` in messages, no `:` in participant aliases, avoid `#`). A checker exists: `node /tmp/claude-0/-home-user-Neutrodyne/12c40d4e-a2e3-573a-89e6-30bb02f324e2/scratchpad/mmd/check.mjs <file>` (run it from that directory). Anchor/link checker: `python3 /tmp/claude-0/-home-user-Neutrodyne/12c40d4e-a2e3-573a-89e6-30bb02f324e2/scratchpad/linkcheck.py`.
- Platform/library claims: cite a source URL (§12 lists verified ones) or write "Unverified:". Never link the research notes.
- Update the header line of your document (Implements / Honours / Owns / Milestones), its `Contents:` line, `## Delivery by milestone`, `## New names introduced here`, `## Open questions` and `## Sources`.
- Use the canonical names below verbatim. Milestone references: "M9" covers M9a+M9b, "M11" covers M11a+M11b; tag work with the increment when it matters.
- Do not blindly replace the word "play": `playEpisode`, `PlayResult`, "Play group", `PlaybackController` are not the flavor.

## 1. Decisions in one line each

| # | Decision |
|---|---|
| O1 | `applicationId` and base package `ch.lkmc.neutrodyne` (debug `ch.lkmc.neutrodyne.debug`), frozen before the first public APK. PO-8 resolved. |
| O2 | GitHub Releases is the only channel. No `foss`/`play` flavors (build types only), no Play, F-Droid, IzzyOnDroid, fastlane, `apksigcopier`, anti-features, reproducible-build release gate (nightly `repro` stays report-only), no GPL corresponding source. Obtainium documented. GitHub is a single point of failure (risk P7, PO-34). |
| O3 | No developer-verification registration (PO-5). Install/update guidance (README + in-app), one-time pre-enforcement notice, verification-block detection in the updater, ADB/Shizuku as power-user fallbacks. |
| O4 | In-app updater `:update:api`/`:update:impl` (D78), lands in M11a; release workflow emits `neutrodyne-update.json` and `SHA256SUMS` from M0; immutable releases, provenance attestations, v2+v3 signing, v3.1 rotation runbook (D79). |
| O5 | yt-dlp (Unlicense) embedded via Chaquopy in CPython replaces NewPipe Extractor; module `:youtube:ytdlp`; process `:ytx`; OkHttp bridge; JS provider if viable; runtime engine updates with a trust chain; per-ABI APKs; `armeabi-v7a` without engine (external mode). |
| O6 | Repository and all binaries: Unlicense + permissive; no GPL/LGPL/AGPL anywhere. |
| O7–O9 | PO, requirement and roadmap changes as in §4–§6. |

## 2. Canonical names

### 2.1 IDs, packages, processes, build

| Item | Name |
|---|---|
| applicationId / namespace | `ch.lkmc.neutrodyne`; debug `ch.lkmc.neutrodyne.debug` (`applicationIdSuffix ".debug"`) |
| Packages | `ch.lkmc.neutrodyne.<module path>`; `:youtube:ytdlp` → `ch.lkmc.neutrodyne.youtube.ytdlp` (code that runs in `:ytx`: subpackage `ch.lkmc.neutrodyne.youtube.ytdlp.ytx`); `:update:api` → `ch.lkmc.neutrodyne.update.api`; `:update:impl` → `ch.lkmc.neutrodyne.update.impl` |
| Processes | main; `:ytx` (full name `ch.lkmc.neutrodyne:ytx`, YouTube engine); `:acra` (ACRA sender). `:ytx` runs no `AppInitializer`, never opens Room, **never opens DataStore** (DataStore is single-process: the main process passes locale, User-Agent and IP family in each call) and has no ACRA |
| Build types | `debug`, `release`, plus the baseline-profile plugin's `benchmarkRelease`/`nonMinifiedRelease`. **No product flavors.** |
| ABI splits | AGP ABI splits: `arm64-v8a`, `x86_64`, `armeabi-v7a`; `isUniversalApk = false`. Chaquopy `abiFilters` (`arm64-v8a`, `x86_64`) only in `:youtube:ytdlp` (Unverified that this works under app-level splits: spike S7). Same `versionCode` for all three APKs. Fallback if S7 shows > 5 MB of foreign-ABI Chaquopy assets per APK: an ABI product-flavor dimension (needs a D2 amendment) |
| Engine presence | engine bundled ⇔ `BuildConfig.YOUTUBE_ENGINE` (Gradle property `neutrodyne.youtubeEngine`, default `true`) **and** `Process.is64Bit()` |
| Emergency build | `./gradlew assembleRelease -Pneutrodyne.youtubeEngine=false`: `:app` omits `:youtube:ytdlp`; source dir `app/src/noYouTubeEngine/` instead of `app/src/youtubeEngine/` provides `YouTubeBindingsModule` with external bindings (reason `NOT_IN_THIS_APK`); nightly job `no-engine-build` |
| `gradle.properties` | existing `neutrodyne.versionName`, `neutrodyne.versionCode`, `neutrodyne.repoUrl`, `neutrodyne.acraMailto`; new `neutrodyne.engineManifestUrl` (committed, `https://<owner>.github.io/Neutrodyne/engine/ytdlp-approved.json`), `neutrodyne.youtubeEngine=true`; `neutrodyne.podcastIndexKey/Secret` only via `-P` in `release.yml` after Podcast Index's written permission (PO-3) |
| `BuildInfo` (`:core:model`) | remove `distribution`, `licenceStatementResId`; add `apkAbi: String` (runtime: first of `Build.SUPPORTED_64_BIT_ABIS`/`SUPPORTED_32_BIT_ABIS` matching the process bitness), `youTubeEngineBundled: Boolean`, `updateManifestUrl` (`$repoUrl/releases/latest/download/neutrodyne-update.json`), `releasesAtomUrl` (`$repoUrl/releases.atom`), `engineManifestUrl`; keep `versionName`, `versionCode`, `isDebug`, `repoUrl`, `shippedLocales`, `podcastIndexKey/Secret` |
| Removed enum | `Distribution` (`FOSS`, `PLAY`) — delete |

### 2.2 Modules (D13)

| Module | Kind | Project deps (main) | External deps | Content from |
|---|---|---|---|---|
| `:youtube:api` | JVM | `:core:{model, common}` | coroutines-core | M2/M3/M4/M8 as before; engine contracts M9a |
| `:youtube:impl` | Android | `:youtube:api`, `:core:{model, common, network}` | as before | Layer A + external-only implementations (unchanged list) |
| `:youtube:ytdlp` **(new, replaces `:youtube:streams`)** | Android library; plugins `neutrodyne.android.library`, `neutrodyne.hilt`, `com.chaquo.python` (only module with it), `buildFeatures.aidl = true` | `:youtube:api`, `:core:{model, common, network, datastore}` | Chaquopy runtime (via plugin), work-runtime, androidx-hilt-work (+compiler), okhttp-coroutines, kotlinx-serialization-json; M9b: `com.google.crypto.tink:tink-android` 1.23.0 (Ed25519 below API 33), `io.github.dokar3:quickjs-kt-android` 1.0.15 (only if the JS provider ships) | M0 stub (Chaquopy hello-world `selftest` if S7 go), M9a, M9b |
| `:update:api` **(new)** | JVM, `.hilt` | `:core:{model, common}` | coroutines-core | M0 stub, M11a |
| `:update:impl` **(new)** | Android | `:update:api`, `:playback:api`, `:download:api`, `:core:{model, common, network, datastore}` | work-runtime, androidx-hilt-work (+compiler), okhttp-coroutines, kotlinx-serialization-json | M0 stub, M11a |
| `:app` | | + `:youtube:ytdlp` (absent with `-Pneutrodyne.youtubeEngine=false`), `:update:{api, impl}`; **no** `fossImplementation`, no `coreLibraryDesugaring` | — | |
| features | | may use `:update:api` (rule 2 regex becomes `:(playback|download|youtube|update):api`) | | |
| `:core:testing` | | + `:update:api` | | |

Deleted: `:youtube:streams`, `:playback:cast` (Chromecast not planned). Dependency rules: `:youtube:ytdlp` and `:update:impl` are implementation modules (rule 4: never → features, `:core:data` or another impl). Only `:youtube:ytdlp` references Chaquopy, Python or `:ytx` classes.

### 2.3 `:youtube:api` contract changes (04 owns; others consume)

```kotlin
data class YouTubeCapabilities(val inAppPlayback: Boolean, val downloads: Boolean, val channelSearch: Boolean,
                               val enrichment: Boolean, val backCatalogue: Boolean,
                               val externalReason: ExternalReason?)        // null ⇔ all five true
enum class ExternalReason { NOT_YET_AVAILABLE /* builds before M9a */, NOT_IN_THIS_APK /* armeabi-v7a or no-engine build */,
                            DISABLED_BY_USER /* youtube.engine_enabled = false */, ENGINE_FAILED /* 3 failed starts */ }
interface YouTubeCapabilitiesSource { val capabilities: StateFlow<YouTubeCapabilities> }   // replaces injecting YouTubeCapabilities

interface YouTubeEngine {                                   // implemented by YtDlpEngine (:youtube:ytdlp) from M9a
    val status: StateFlow<EngineStatus>
    fun prewarm(reason: PrewarmReason)                      // binds :ytx and starts the interpreter; no-op without the engine
    suspend fun checkForUpdate(): EngineUpdateOutcome        // M9b; enqueues/awaits engine-update-now
    suspend fun resetToBundled()                             // M9b
    fun retryStart()                                         // clears ENGINE_FAILED
}
data class EngineStatus(val availability: EngineAvailability, val activeVersion: String?, val bundledVersion: String?,
                        val source: EngineSource, val updatePolicy: EngineUpdatePolicy, val lastCheckAtMs: Long?,
                        val lastOutcome: EngineUpdateOutcome?, val jsChallenges: Boolean)
enum class EngineAvailability { STOPPED, STARTING, READY, NOT_IN_THIS_APK, DISABLED, FAILED }
enum class EngineSource { BUNDLED, UPDATED }
enum class EngineUpdatePolicy { APPROVED, UPSTREAM_STABLE, OFF }
enum class PrewarmReason { PROJECTION, SCREEN, DOWNLOAD, SEARCH }
sealed interface EngineUpdateOutcome { data object UpToDate; data class Staged(val version: String); data class Activated(val version: String)
    data class Rejected(val version: String?, val reason: EngineRejectReason); data class Failed(val kind: TransientKind) }
enum class EngineRejectReason { MANIFEST_SIGNATURE, MANIFEST_REPLAYED, UPSTREAM_SIGNATURE, HASH_MISMATCH, ORIGIN, BELOW_BUNDLED,
                                SHIM_INCOMPATIBLE, SIZE_CAP, SELFTEST_FAILED, ROLLED_BACK, REVOKED }
```

Other changes: `ResolvedAudio` gains `availableAtMs: Long?` (yt-dlp `available_at`; resolver/06/07 wait ≤ 30 s or treat as `Transient`); `TransientKind` gains `ENGINE_UNAVAILABLE` (engine died, could not start; 06/07 treat like `TIMEOUT`); `ResolveResult.Unsupported` = external mode; `UploadsCursor(val token: String)` and `SearchCursor(val token: String)` become cross-process opaque tokens (Python generators live in `:ytx`; after a `:ytx` restart the engine answers `CURSOR_EXPIRED` → restart at page 1, skip known IDs); `YtRef.Query` comment "engine channel search only"; `ExtractorChannelLookup`, `YouTubeEnricher`, `YouTubeChannelSearch`, `YouTubeStreamResolver`, `ResolvedVia.EXTRACTOR`, `YouTubeHealth`, `AudioStreamSelector`, `ResolvedUrlCache` keep their names.

### 2.4 `:youtube:ytdlp` names

| Where | Names |
|---|---|
| Main process | `YtDlpEngine` (implements `YouTubeEngine` + `YouTubeCapabilitiesSource`), `YtDlpClient` (bind, single flight, deadlines, cancel, kill-on-hang, idle stop 3 min), `YtxConnection` (ServiceConnection + DeathRecipient), `YtDlpStreamResolver`, `YtDlpEnricher`, `YtDlpChannelSearch`, `YtDlpChannelLookup` (replaces `InnertubeChannelResolver`), `YtDlpErrorMapper`, `YtDlpAudioMapper`, `EngineStore`, `EngineUpdateWorker`, `EngineManifestVerifier`, `UpstreamReleaseVerifier`, `OpenPgpDetachedVerifier`, `EngineSelfTest`, `EngineRollbackMonitor`, `EngineKeys`, Hilt `YtDlpModule` (internal wiring only — never binds `:youtube:api` interfaces) |
| `:ytx` process (`…youtube.ytdlp.ytx`) | `YtxService` (bound, `@AndroidEntryPoint`, injects nothing database- or DataStore-backed), `YtxPython` (Chaquopy `Python.start`, `sys.path` → active engine version, on-device `compileall`), `PyHttp` (OkHttp from 01's YOUTUBE client config, per-call registry, IP-family pinning), `YtxCallRegistry`, `QuickJsEngine` (only with the JS provider) |
| AIDL (`youtube/ytdlp/src/main/aidl/ch/lkmc/neutrodyne/youtube/ytdlp/ytx/`) | `IYtxEngine`: `void call(long callId, String method, String payloadJson, long deadlineAtMs, IYtxCallback cb)`, `void cancel(long callId)`, `String status()`; `oneway interface IYtxCallback`: `onResult(long callId, String resultJson)`, `onError(long callId, String code, String message)`. JSON strings only; payloads ≪ 1 MB; never send player JS over Binder |
| Python (`youtube/ytdlp/src/main/python/neutrodyne_ytx/`) | `bridge.py` (methods `ping`, `version`, `selftest`, `resolve`, `facts`, `lookup`, `tab_open`, `tab_next`, `search_open`, `search_next`), `okhttp_rh.py` (`NeutrodyneOkHttpRH`, `RH_KEY = "NeutrodyneOkHttp"`, preference 1000), `errors.py`, `selftest.py`, `jsc_quickjs.py` (`NeutrodyneQuickJsJcp`, M9b if viable); constant `SHIM_API_VERSION = 1`. Test-only: `RecordingRH`, `ReplayRH` |
| Shim error codes | `RATE_LIMITED`, `AGE_RESTRICTED`, `MEMBERS_ONLY`, `PRIVATE`, `REGION_BLOCKED`, `UPCOMING`, `LIVE`, `KIDS_ONLY`, `UNAVAILABLE`, `EXTRACTION`, `NETWORK`, `TIMEOUT`, `CANCELLED`, `CURSOR_EXPIRED` (structured yt-dlp fields `availability`, `live_status`, `age_limit` first; messages only as fallback) |
| yt-dlp options (shim) | `extract_info(…, download=False, process=False)`; `quiet`, logger, `skip_download`, `noplaylist`, `cachedir = cacheDir/yt-dlp`, `js_runtimes = {}` (unless the JS provider is registered), `remote_components = set()`, `extractor_args youtube.skip = [hls, dash, translated_subs]`; channel tab `https://www.youtube.com/channel/{UC}/videos` (`/shorts`, `/streams`) with `extract_flat='in_playlist'`, `lazy_playlist=True`; search `https://www.youtube.com/results?search_query=…&sp=EgIQAg%253D%253D`; handle lookup `https://www.youtube.com/@handle` flat, playlist-level fields only |
| Repository files | `youtube/ytdlp/engine/yt-dlp` (official zipimport asset, vendored), `youtube/ytdlp/engine/SHA2-256SUMS`, `…/SHA2-256SUMS.sig`, `…/bundled.json` (`version`, `tag`, `sha256`, `ejsVersion`); `youtube/ytdlp/keys/yt-dlp-release-key.asc` (fingerprint `AC0C BBE6 848D 6A87 3464 AF4E 57CF 6593 3B5A 7581`, re-verify at M9b start; allow a second pinned key for upstream rotation); `youtube/ytdlp/keys/engine-manifest-ed25519.pub` (1–2 pinned Neutrodyne keys); `youtube/ytdlp/python-components.lock` |
| Gradle tasks | `:youtube:ytdlp:verifyBundledYtDlp` (SHA-256 + OpenPGP of the vendored zip at build time), `:youtube:ytdlp:checkPythonLicences` (lockfile vs allow-list), `:youtube:ytdlp:shimTest` (host `python -m pytest` with `ReplayRH`; needs a host CPython of the target minor version) |
| On device | `noBackupFilesDir/ytdlp/active.json` (`current`, `previous`, `bundled`, `rejected[]`, `lastManifestSequence`), `noBackupFilesDir/ytdlp/versions/<version>/` (compiled `.pyc`, read-only), `noBackupFilesDir/ytdlp/staging/`; yt-dlp cache `cacheDir/yt-dlp/` (player JS only, never URLs) |

### 2.5 `:update:api` / `:update:impl` names (09 owns the design, 08 the UI)

```kotlin
// :update:api (sketch: state variants are data classes/objects with the listed fields)
interface AppUpdater {
    val state: StateFlow<UpdateState>
    suspend fun checkNow(): UpdateState
    fun download()                      // from Available
    fun installWhenIdle()               // from ReadyToInstall; never interrupts playback or a running download
    fun skip(versionCode: Long)
    fun openReleasePage()               // "Download in browser": ACTION_VIEW of the release (or asset) URL
}
sealed interface UpdateState { Disabled(reason: UpdateDisabledReason); Idle(lastCheckAtMs: Long?); Checking; Available(info); Downloading(info, bytes, total);
    ReadyToInstall(info); WaitingForIdle(info); Installing(info); PendingUserAction(info); Blocked(info, reason: InstallBlockReason); Failed(info?, error: UpdateError) }
enum class UpdateDisabledReason { DEBUG_BUILD, MODE_OFF, MANAGED_BY_OTHER_INSTALLER }
data class UpdateInfo(val versionName: String, val versionCode: Long, val channel: UpdateChannel, val abi: String, val apkName: String,
                      val sizeBytes: Long, val sha256: String, val certSha256: String, val notes: String, val publishedAt: String, val releaseUrl: String)
enum class UpdateMode { OFF, NOTIFY, AUTOMATIC }
enum class UpdateChannel { STABLE, BETA }
enum class InstallBlockReason { DEVELOPER_UNVERIFIED, VERIFICATION_NETWORK, INSTALL_PERMISSION_MISSING, BLOCKED_BY_POLICY, INCOMPATIBLE, STORAGE, UNKNOWN }
enum class UpdateError { NETWORK, RATE_LIMITED, MANIFEST_INVALID, NO_APK_FOR_ABI, HASH_MISMATCH, CERTIFICATE_MISMATCH, PACKAGE_MISMATCH, NOT_NEWER, INSTALL_FAILED }
interface UpdateNotices { val pending: StateFlow<UpdateNotice?>; fun dismiss(notice: UpdateNotice) }
enum class UpdateNotice { FIRST_RUN_CHOICE, VERIFICATION_ENFORCEMENT, WHATS_NEW }
```

`:update:impl`: `GitHubUpdateSource`, `UpdateManifestParser`, `UpdateCheckWorker`, `UpdateDownloadWorker`, `ApkVerifier`, `SelfInstaller`, `UpdateStatusReceiver`, `InstallIdleGate`, `InstallerOfRecordDetector`, `VerificationFailureMapper`, `UpdateNotifier`, `VerificationTimeline` (compiled-in notice date: earlier of 2026-12-01 and a Google-announced date, PO-36), Hilt `UpdateModule`. The updater downloads with its own small resumable transfer on 01's DOWNLOAD client — **not** 07's episode engine (07's rows are episode-bound). Manifest/Atom requests use the API client. Never `api.github.com`. Disabled in debug builds.

### 2.6 WorkManager unique work names (new)

| Name | Type, policy | Owner |
|---|---|---|
| `engine-update` | periodic 24 h, network constraint, `UPDATE` | 04 (`:youtube:ytdlp`), M9b |
| `engine-update-now` | one-time, `KEEP`; enqueued after a breaker opening (at most every 3 h) and by "Check for engine update" | 04, M9b |
| `app-update-check` | periodic 24 h with flex/jitter, network constraint, `UPDATE` | 09 (`:update:impl`), M11a |
| `app-update-check-now` | one-time, `REPLACE` ("Check now") | 09, M11a |
| `app-update-download` | one-time, `KEEP`, resumable | 09, M11a |
| `app-update-install` | one-time; API 26–33 idle re-check (returns retry while busy) | 09, M11a |

Removed: none (no flavor-specific work existed). Initializers (01's table): order 10 adds channel `updates` (`:update:impl`, M11a); order 150 `YtDlpEngine` capability load (DataStore + `active.json`, M9a); order 200 adds `engine-update` (M9b) and `app-update-check` (M11a).

### 2.7 Notification channels and IDs

| Channel | Name / importance | Content | Owner |
|---|---|---|---|
| `updates` (new) | "App updates", LOW | update available, downloading, "Tap to finish updating" (`STATUS_PENDING_USER_ACTION` in background), "Update blocked by Android"; one notification `NOTIF_ID_UPDATE = 4200` | 09/08, M11a |
| `alerts` (existing) | unchanged | breaker notice `NOTIF_ID_YT_BREAKER = 4100`, new text: title "YouTube playback is temporarily broken", text "Neutrodyne can't read YouTube streams right now and is checking for a YouTube engine update. It retries automatically at {time}.", actions "Try now", "Check for engine update" (the "Releases" action goes) | 04 |

### 2.8 Settings keys

| Key | Type | Default | File | Owner, milestone |
|---|---|---|---|---|
| `youtube.engine_enabled` | Bool | `true` | `settings` | 04, M9a — Settings › YouTube "Play YouTube in the app" |
| `youtube.engine_updates` | Choice `EngineUpdatePolicy` | `APPROVED` (PO-32) | `settings` | 04, M9b |
| `youtube.engine_last_check_at` | Long? | null | `device_settings` | 04, M9b |
| `youtube.engine_last_outcome` | Text | `""` | `device_settings` | 04, M9b |
| `youtube.engine_start_failures` | Int | 0 | `device_settings` | 04, M9a (3 consecutive failures per app+engine version → `ENGINE_FAILED`) |
| `youtube.breaker_engine_version` | Text | `""` | `device_settings` | 04, M9a (a changed engine version closes the breaker, like `breaker_version_code`) |
| `updates.mode` | Choice `UpdateMode` | `NOTIFY` (PO-31) | `settings` | 09, M11a |
| `updates.channel` | Choice `UpdateChannel` | `STABLE` (PO-33) | `settings` | 09, M11a |
| `updates.last_check_at` | Long? | null | `device_settings` | 09, M11a |
| `updates.skipped_version_code` | Long? | null | `device_settings` | 09, M11a |
| `updates.first_run_choice_done` | Bool | false | `device_settings` | 09, M11a |
| `updates.verification_notice_shown_at` | Long? | null | `device_settings` | 09, M11a |
| `updates.whats_new_version_code` | Long? | null | `device_settings` | 09, M11a |

Existing `youtube.audio_quality`, `youtube.volume_levelling`, `youtube.auto_download*` lose "`foss`" and become "with the engine". 05 adds the four portable keys to the backup whitelist.

### 2.9 Navigation, Settings pages, deep links (08 owns keys, 01 routing)

`SettingsPage.UPDATES` (new; `SettingsKey(UPDATES)`); `InstallHelpKey`, `UpdateBlockedKey(reason: InstallBlockReason)` (sheet), `VerificationNoticeKey` (dialog), `WhatsNewKey(versionCode)` (sheet), all served by `:feature:settings`; `:app`'s root observes `UpdateNotices` and opens the dialog/sheet keys. Deep links: `neutrodyne://open/settings/updates`, `neutrodyne://open/help/install`. 08 headings: `### Updates settings` (anchor `#updates-settings`), `### Install and updates help` (`#install-and-updates-help`).

### 2.10 Manifest and permissions (01 owns; PLAN N7 amended)

| Entry | Declared by | From |
|---|---|---|
| `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>` | `:update:impl` | M11a |
| `<uses-permission android:name="android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"/>` (normal, API 31+) | `:update:impl` | M11a |
| `<queries><package android:name="dev.imranr.obtainium"/><package android:name="dev.imranr.obtainium.fdroid"/></queries>` (installer-of-record name visibility; Unverified both IDs, M11a check) | `:update:impl` | M11a |
| `<service android:name="ch.lkmc.neutrodyne.youtube.ytdlp.ytx.YtxService" android:process=":ytx" android:exported="false"/>` | `:youtube:ytdlp` | M9a |
| `<receiver android:name="ch.lkmc.neutrodyne.update.impl.UpdateStatusReceiver" android:exported="false"/>` | `:update:impl` | M11a |
| `jniLibs.useLegacyPackaging` / `extractNativeLibs` | per S7 (fallback A2 requires legacy packaging) | M0/M9a |

Add to "explicitly not requested": `INSTALL_PACKAGES`, `REQUEST_DELETE_PACKAGES` (and keep `QUERY_ALL_PACKAGES`). `permissions.txt` gains the two permissions at M11a.

### 2.11 Release assets, manifests, files

Per tag `vX.Y.Z[-beta.N|-rc.N]` (immutable release; `prerelease` for suffixed tags; `make_latest` only for stable):

| Asset | Notes |
|---|---|
| `neutrodyne-{v}-arm64-v8a.apk`, `neutrodyne-{v}-x86_64.apk`, `neutrodyne-{v}-armeabi-v7a.apk` | signed v2+v3 (no v1), same `versionCode` |
| `neutrodyne-{v}-mapping.txt` | one R8 mapping for the variant |
| `neutrodyne-update.json` | schema below; validated by `scripts/ci/check-update-json.sh` before publishing |
| `SHA256SUMS` | `sha256sum` format over the APKs, mapping and `neutrodyne-update.json` |
| (GitHub) provenance attestations | `actions/attest` over the APKs, `SHA256SUMS`, `neutrodyne-update.json`; release attestation from immutable releases |

```json
{ "schema": 1, "versionName": "1.0.0", "versionCode": 1000095, "minSdk": 26, "prerelease": false,
  "published": "2027-…Z", "certSha256": "AA:BB:…", "notes": "…", "releaseUrl": "https://github.com/<owner>/Neutrodyne/releases/tag/v1.0.0",
  "apks": [ { "abi": "arm64-v8a", "file": "neutrodyne-1.0.0-arm64-v8a.apk", "size": 0, "sha256": "…" },
            { "abi": "x86_64", "file": "…", "size": 0, "sha256": "…" }, { "abi": "armeabi-v7a", "file": "…", "size": 0, "sha256": "…" } ] }
```

Engine manifest on GitHub Pages (`neutrodyne.engineManifestUrl`): `engine/ytdlp-approved.json` + `engine/ytdlp-approved.json.sig` (base64 of the 64-byte Ed25519 signature over the exact JSON bytes):

```json
{ "schema": 1, "sequence": 42, "issuedAt": "…Z",
  "ytdlp": { "version": "2026.08.19", "tag": "2026.08.19", "repo": "yt-dlp/yt-dlp", "asset": "yt-dlp", "sha256": "1fa6733c…d4d6", "ejsVersion": "0.8.0" },
  "shimApi": { "min": 1, "max": 1 }, "revoked": [] }
```
`sequence` is monotonic (replay protection: never accept a lower sequence than the last accepted); `revoked` lists versions that must be rolled back. Upstream files: `https://github.com/yt-dlp/yt-dlp/releases/download/<tag>/{yt-dlp,SHA2-256SUMS,SHA2-256SUMS.sig}`; `ORIGIN` in `yt_dlp/version.py` must be `yt-dlp/yt-dlp`; size cap 10 MB.

Other files: `changelogs/<versionCode>.txt` (replaces `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`; release body, `notes`, in-app What's new); `.github/workflows/engine-canary.yml`; `scripts/ci/make-update-json.sh`, `scripts/ci/check-update-json.sh`, `scripts/engine/bump-ytdlp.sh`, `scripts/engine/make-engine-manifest.sh`, `scripts/engine/sign-engine-manifest.sh`; `scripts/youtube/record-responses.sh` (kept, now drives the shim with `RecordingRH`); `youtube/ytdlp/src/test/resources/recorded/{scenario}/`.

### 2.12 CI, Gradle tasks, environments

| Item | Change |
|---|---|
| `ci.yml` `static` | `spotlessCheck :app:lintDebug :app:assertModuleGraph :app:licenseeRelease :app:verifyDependencyPolicy :app:verifyManifestPermissions checkSpdxHeaders checkBannedApis :youtube:ytdlp:checkPythonLicences :youtube:ytdlp:verifyBundledYtDlp`; KGP assertion; frozen schemas; detekt non-blocking. Removed: `lintPlayDebug`, `licenseeFossRelease`, `licenseePlayRelease`, `check-fastlane.sh` |
| `ci.yml` `unit` | + `:youtube:ytdlp:shimTest` (setup-python with the target minor version) |
| `ci.yml` `assemble` | `assembleDebug assembleRelease` (three ABI APKs) + `check-apk.sh`; removed `assemblePlayDebug`, `bundlePlayRelease` |
| `nightly.yml` | keep `instrumented-full` (release leg `-PtestBuildType=release`, no flavor in task names), `api37-16k`, `system-tests`, `benchmark-dryrun`, `repro` (**report-only permanently**), `bmgr`, `screenshots-full`, `mutation-full`, `live-canary`, `youtube-canary` (live, non-blocking); add `engine-nightly-canary` (shim vs yt-dlp nightly, informational), `no-engine-build` (blocking); remove `emergency-patch-check` |
| `release.yml` | verify-tag (changelog path `changelogs/`) → container build `assembleRelease` signed → per APK `apksigner verify --print-certs` = `NEUTRODYNE_CERT_SHA256`, v2+v3/no v1, `zipalign -c -P 16`, `check-apk.sh` → rename to asset names → `make-update-json.sh` + `check-update-json.sh` → `SHA256SUMS` → `actions/attest` (permissions `id-token: write`, `attestations: write`, `artifact-metadata: write`, `contents: write`) → draft release, upload, publish (immutable) → `gh release verify`. Removed: Play step, `corresponding-source.sh`, `verify-repro` job |
| `engine-canary.yml` (new; 09 owns workflow, 04 owns test content) | schedule every 6 h + `workflow_dispatch(tag)`; finds the latest yt-dlp stable (Location of `github.com/yt-dlp/yt-dlp/releases/latest`), verifies `SHA2-256SUMS.sig` and SHA-256, runs `shimTest` (recorded responses + API probe + selftest) against it; on green signs the manifest (`sequence + 1`) in environment `engine-approval` (secret `NEUTRODYNE_ENGINE_MANIFEST_KEY`, deployment branch `main` only, no reviewer) and deploys to GitHub Pages (`actions/deploy-pages`, permissions `pages: write`, `id-token: write`); on red opens/updates an issue labelled `engine-canary` |
| Gradle | `licenseeRelease` (single variant); `verifyDependencyPolicy` keeps banning GPL/LGPL/AGPL coordinates and `io.mockk` in androidTest (no flavor classpath checks); `checkSpdxHeaders` now fails on any GPL/LGPL/AGPL `SPDX-License-Identifier` anywhere and checks copied permissive files keep their header; `checkPythonLicences`, `verifyBundledYtDlp`, `shimTest` |
| Environments / secrets | `release`: `NEUTRODYNE_KEYSTORE_B64`, `…_PASSWORD`, `NEUTRODYNE_KEY_ALIAS`, `NEUTRODYNE_KEY_PASSWORD`; `PODCASTINDEX_KEY/SECRET` only after written permission (passed to `assembleRelease`). `engine-approval`: `NEUTRODYNE_ENGINE_MANIFEST_KEY`. Variables: `NEUTRODYNE_CERT_SHA256`. Removed: `NEUTRODYNE_UPLOAD_KEYSTORE*`, `PLAY_SERVICE_ACCOUNT_JSON`, `PLAY_PUBLISHING` |
| Repository settings | immutable releases on (M0); GitHub Pages source "GitHub Actions"; Codeberg push mirror (PO-34 default) |
| Renovate | remove the NewPipe Extractor fast lane and the Rhino rule; add a Chaquopy rule (`com.chaquo.python`, dashboard approval, re-run S7 checks); yt-dlp bundled version is not Renovate-managed (`bump-ytdlp.sh`, only versions the canary approved) |
| Scripts removed | `scripts/ci/check-play-dex.sh`, `scripts/ci/check-fastlane.sh`, `scripts/release/corresponding-source.sh`, `scripts/youtube/bump-extractor.sh`, `scripts/emergency/no-youtube-streams.patch`; files `fdroid/ch.lkmc.neutrodyne.yml`, `fastlane/metadata/**`, `app/src/play/**`, `app/src/foss/**` |
| `check-apk.sh` | per-ABI size budgets (PB12/PB13), 16 KB check including `.so` files inside Chaquopy asset zips (`llvm-readelf -l`), forbidden content (`mutagen`, `readline`, `libreadline`, `org/schabi/newpipe`, `org/mozilla/javascript`), locale config, no `version-control-info.textproto` |

### 2.13 Network inventory (09 owns; `Flavor` column → `APK` column: all / with engine)

| ID | Change |
|---|---|
| `youtube-streams` | rewrite: APKs with the engine; `www.youtube.com` InnerTube requests made by yt-dlp through our OkHttp client (`/youtubei/…`, watch page), `*.googlevideo.com`; Unverified complete host list (M9a capture) |
| `youtube-engine` (new) | `<owner>.github.io` (engine manifest), `github.com` (`yt-dlp/yt-dlp` release downloads), `release-assets.githubusercontent.com`; when engine updates are not Off (PO-32) |
| `app-updates` (new) | `github.com` (`releases/latest/download/…`, `releases.atom`), `release-assets.githubusercontent.com`; per PO-31 |
| others | unchanged; "What hosts receive": GitHub sees IP and User-Agent for update checks, no identifiers |

### 2.14 Tests (renamed / new)

E8 `PlayFlavorYouTubeTest` → `ExternalYouTubeModeTest`; E7 `YouTubeReleaseSmokeTest` kept (minified `release`, recorded responses via `:ytx`); `NpeYouTubeStreamResolverTest`/`NpeErrorClassifierTest`/`NpeEnricherTest`/`NpeChannelSearchTest` → `YtDlpStreamResolverTest`, `YtDlpErrorMapperTest`, `YtDlpEnricherTest`, `YtDlpChannelSearchTest` (JVM, recorded shim JSON via `FakeYtDlpClient`) + Python `shimTest`; new `YtxIsolationTest` (instrumented), `YtxProcessStartTest` (no initializer/DB/DataStore in `:ytx`), `EngineUpdateWorkerTest`, `EngineManifestVerifierTest`, `UpstreamReleaseVerifierTest`, `EngineRollbackMonitorTest`, `UpdateManifestParserTest`, `GitHubUpdateSourceTest`, `ApkVerifierTest`, `SelfInstallerTest`, `InstallIdleGateTest`, `VerificationFailureMapperTest`, `AppUpdaterTest`; fakes in `:core:testing`: `FakeYouTubeCapabilitiesSource`, `FakeYouTubeEngine`, `FakeAppUpdater`, `FakeUpdateNotices`. Removed: `PlayStringsPolicyTest`, `check-play-dex`, `RecordingDownloader`/`ReplayDownloader`.

### 2.15 Performance budgets (09 owns IDs)

PB12 → `arm64-v8a` and `x86_64` release APKs < 40 MB each; PB13 → `armeabi-v7a` release APK < 30 MB (was `play` APK); new PB18 cold YouTube resolve (`:ytx` not running) p50 ≤ 3 s; PB19 warm resolve p50 ≤ 1.5 s; PB20 `:ytx` PSS ≤ 90 MB while alive; PB21 `:ytx` gone ≤ 3 min after the last call. PB18–PB20 measured in the M9a spike on the reference device (gate: M9 AC4), soft at release.

## 3. D-ids (amended or new)

| ID | One line |
|---|---|
| D2 (amended) | No product flavors; build types only; per-ABI splits; emergency no-engine build is a Gradle switch. |
| D3 (amended) | Unlicense + permissive everywhere, no GPL/LGPL/AGPL; never-ship list; enforced by Licensee, Python lockfile, APK scan. |
| D13 (amended) | `:youtube:streams` → `:youtube:ytdlp`; `:update:api`/`:update:impl` added. |
| D26 (amended) | Podcast Index BYOK by default; a key may be injected into release builds after written permission. |
| D39 (amended) | YouTube branch of the `neutrodyne://episode/{id}` resolver applies when the engine is present. |
| D51 (amended) | Layer A every APK; Layer B = yt-dlp engine where present; external episodes otherwise; no Data API. |
| D52 (wording) | Unchanged ranks; `formatId` matches yt-dlp's `format_id`; selector ranks yt-dlp formats. |
| D60 (amended) | CI adds `checkPythonLicences`, APK scan, `engine-canary.yml`; no extractor fast lane. |
| D61 (resolved) | `ch.lkmc.neutrodyne`; one offline RSA-4096 key, maintainer + encrypted backup (second holder PO-35); v2+v3; v3.1 rotation runbook. |
| D62 (amended) | No Play services anywhere; ACRA not installed in `:ytx`; no `alsoReportToAndroidFramework`. |
| D63 (amended) | Same `versionCode` for all ABI APKs; out-of-order increments ship under the current line. |
| D64 (amended) | Auto polish v1.x (no store car review; sideloaded apps need Auto's "Unknown sources"); Chromecast not planned. |
| D72 (new) | yt-dlp zipimport in CPython via Chaquopy in `:youtube:ytdlp`; S7 + M9a spike gates; fallbacks A2 (python.org CPython child process) and Kotlin InnerTube port. |
| D73 (new) | `:ytx` process, one interpreter, pre-warm, 3-min idle stop, AIDL `IYtxEngine`, kill on hang, no initializers/DB/DataStore/ACRA in `:ytx`. |
| D74 (new) | yt-dlp HTTP through `NeutrodyneOkHttpRH` → `PyHttp` (OkHttp) with IP-family pinning; fallback urllib + `source_address`. |
| D75 (new) | JS-free `visionos` path in v1; quickjs-kt JS challenge provider in M9b if the spike passes, else M14; never Deno/Node/exec. |
| D76 (new) | Engine updates: approved Ed25519 manifest on GitHub Pages + upstream OpenPGP + SHA-256 + origin + anti-rollback + self-test + rollback; expert upstream-stable policy; pure Python only. |
| D77 (new) | Per-ABI APKs, no universal; `armeabi-v7a` without engine; external mode as runtime capability (`YouTubeCapabilitiesSource`, `ExternalReason`). |
| D78 (new) | In-app updater (`latest/download/neutrodyne-update.json`, beta via `releases.atom`, SHA-256 + cert check, PackageInstaller per API level, hold until idle, verification-failure help, Obtainium off-switch, two new permissions). |
| D79 (new) | GitHub-only release engineering: immutable releases, asset set, `actions/attest`, cert SHA-256 in README/body, `changelogs/`, Obtainium documented, repro report-only. |
| D80 (new) | No developer verification; phase-by-phase consequences; README + in-app help, one-time notice, block detection, ADB/Shizuku power-user only; key kept for possible later registration. |

## 4. PO-ids

| ID | State |
|---|---|
| PO-1 | **Resolved**: no GPL anywhere; yt-dlp. Heading unchanged (`#po-1-licensing-of-shipped-binaries`). |
| PO-2 | **Resolved**: GitHub Releases only. **Heading renamed** "PO-2: Distribution channels" → anchor `#po-2-distribution-channels` (was `#po-2-distribution-channels-and-youtube-per-flavor`). |
| PO-3 | Open, default B (BYOK); option A now "inject into release builds after written permission". |
| PO-5 | **Resolved**: do not register. Heading unchanged. |
| PO-6 | Chromecast: default "not planned". Heading unchanged. |
| PO-7 | Unchanged (NIO-desugaring note removed). |
| PO-8 | **Resolved** (row in 4.8). |
| PO-10 | ACRA by email, not in `:ytx`; no `play` option. |
| PO-18 | GitHub; owner name also feeds update and engine-manifest URLs. |
| PO-22, PO-23, PO-29 | **Obsolete** (rows kept, marked). Do not link them as live decisions. |
| PO-31 (new) | Update-check default: **Notify**, first-run card with one-tap Off; Obtainium → off. Blocks M11a. |
| PO-32 (new) | Engine-update default: **Neutrodyne-approved, automatic**; upstream stable expert; Off available. Blocks M9b. |
| PO-33 (new) | Beta update channel: offered in Settings › Updates, **off by default**. Blocks M11a. |
| PO-34 (new) | Mirror: **Codeberg git push mirror from M0**; asset/manifest mirroring and an updater fallback URL decided before M11b. |
| PO-35 (new) | Second key holder: **a named second holder recommended**; until then two encrypted copies in two places. Blocks M0. |
| PO-36 (new) | Verification notice: **first release after 2026-12-01 or when Google names the date, whichever is earlier**; neutral tone. Blocks M11a or earlier. |

## 5. Requirements

R3.1 (channel search needs the engine), R3.5/R3.6 (with the engine: 64-bit APKs, engine on), R3.7 (rewritten: external mode = `armeabi-v7a`, engine off or unusable; Settings › YouTube explains), R3.8 (breaker also triggers an engine-update check; new notice text), **R3.9 (new: engine updates without an app update, visible version, Check / Reset to bundled / policy)**, **R6.1–R6.4 (new group "Installing and updating")**, N3 (no Play services anywhere; GitHub update hosts), N5 (per-ABI sizes 40/40/30 MB; `:ytx` PSS ≤ 90 MB, 3-min idle stop, cold ≤ 3 s, warm ≤ 1.5 s), N7 (16 KB covers CPython; closed permission set incl. the two updater permissions), N8 renamed "Licensing" (no GPL; Licensee + Python lockfile + APK scan; Licences-screen list), N11 (engine updates within hours: canary ≤ 6 h, apps ≤ 24 h; APK hotfix < 30 min as backup), **N12 (new: release and update integrity)**. Traceability rows in PLAN 2.3 point at the new headings listed in §8.

## 6. Milestones

| Milestone | Change |
|---|---|
| M0 | No flavors; ABI splits; spike **S7 Chaquopy build integration** (01 `### S7 Chaquopy under AGP 9.4.1`); `:youtube:ytdlp` stub with Chaquopy if go; `:update:*` stubs; `python-components.lock` + `checkPythonLicences`; release workflow emits per-ABI APKs, `SHA256SUMS`, `neutrodyne-update.json`, attestations, immutable release; `changelogs/`; Codeberg mirror; key holders per PO-35. AC1/2/5/6/7 rewritten (PLAN). Advances + N12. |
| M3/M4/M5 | "both flavors" → `YouTubeBindingsModule`; M5 AC4: every install is sideloaded → Auto "Unknown sources". |
| M8 | Heading **unchanged** ("YouTube subscriptions in all builds"); external-only bindings with reason `NOT_YET_AVAILABLE` until M9a; AC5 = `ExternalYouTubeModeTest`. |
| M9 | **Renamed** "M9: YouTube playback and downloads via the embedded yt-dlp engine"; increments **M9a** (spike week, engine host, resolver, playback/download integration, enrichment, search, back catalogue, breaker, licences, no-engine nightly) and **M9b** (engine updates, canary, JS provider if viable). 12 ACs (PLAN). Size L + M. |
| M10 | Unchanged. |
| M11 | Heading unchanged; increments **M11a** (in-app updater, Settings › Updates, help page, blocked sheet, notice, README Install and update; may start after M6b) and **M11b** (release hardening and v1.0: profiles, maintenance, ACRA, PRIVACY, GitHub release hardening, rotation rehearsal, mirror). Store/F-Droid/IzzyOnDroid/Play/registration/repro-gate items removed. 12 ACs (PLAN). |
| M13/M14 | M13 loses Chromecast and "car-quality review"; M14 gains the JS provider if not shipped; SponsorBlock no longer "`foss`". |
| Graph | `M8 → M9a → M9b`, `M5/M6b → M9a`, `M6b → M11a`, `M9b, M10, M11a → M11b`. Tester-build versions follow release order (D63). |

## 7. Risks (PLAN §8)

Retired: P1, P2, P4, P5. Rewritten: P3 (verification friction), L1 (legal; emergency no-engine build; JS solving is the circumvention-sensitive part), L2 (licence regression), M1r (yt-dlp/visionos breakage), M4r (key loss, no escrow, later registration impossible), M6r (v3.1 note). New: T14 (Chaquopy toolchain lag), T15 (engine budgets), T16 (32-bit gap), T17 (self-updater under verification undocumented), P7 (GitHub single channel), P8 (Google tightens/early date), P9 (package name claimed), M7r (engine-update supply chain), M8r (yt-dlp plugin/internal API drift). Update every "mitigates risk …" mention in your document accordingly (e.g. "P1" → remove; "P4" → remove).

## 8. Renamed or removed headings and the inbound links to fix

New anchor targets PLAN already links to (create exactly these headings): 01 `## Build variants and ABIs` (`#build-variants-and-abis`), 01 `### YouTube bindings` (`#youtube-bindings`), 01 `### S7 Chaquopy under AGP 9.4.1` (`#s7-chaquopy-under-agp-941`), 01 `### Python and native components` (`#python-and-native-components`), 04 `## Capability matrix` (`#capability-matrix`), 04 `## YouTube engine` (`#youtube-engine`), 04 `## Engine updates` (`#engine-updates`), 08 `## Capability differences in UI` (`#capability-differences-in-ui`), 08 `### Updates settings` (`#updates-settings`), 08 `### Install and updates help` (`#install-and-updates-help`), 09 `## In-app updater` (`#in-app-updater`), 09 ``### `engine-canary.yml` `` (`#engine-canaryyml`). PLAN also links (unchanged headings, keep them): 04 `#channel-search`, `#stream-resolution`, `#playback-integration`, `#download-integration`, `#error-handling-and-circuit-breaker`, `#licensing-and-legal`, `#maintenance-and-hotfix-process`, `#hotfix-runbook` (09 → 04); 09 `#distribution-channels`, `#developer-verification`, `#versioning-and-signing`, `#key-loss-or-compromise`, `#releaseyml`, `#key-ceremony-and-custody`, `#v10-gate`, `#performance-budgets`, `#budgets`; 08 `#settings`.

| Old anchor | New | Inbound links to fix (file: count) |
|---|---|---|
| PLAN `#m9-youtube-playback-and-downloads-in-foss` | `#m9-youtube-playback-and-downloads-via-the-embedded-yt-dlp-engine` | 01: 1 (Delivery, line ~1702), 02: 1 (~1758), 03: 1 (~1417), 04: 1 (~1125), 05: 1 (~1627), 06: 1 (~1090), 07: 2 (~11, ~1369), 08: 1 (~2025), 09: 1 (~1412) |
| PLAN `#po-2-distribution-channels-and-youtube-per-flavor` | `#po-2-distribution-channels` | 01: 1 (~1143), 04: 3 (~218, ~973, ~1148), 07: 1 (~1310), 08: 1 (~1841), 09: 1 (~871) |
| 01 `#build-flavors` | `#build-variants-and-abis` | 01: 3, 02: 1, 04: 1, 09: 2 |
| 01 `#flavor-modules` | `#youtube-bindings` | 01: 4, 04: 2, 08: 1 |
| 01 `#core-library-desugaring` (section deleted) | — remove links | 01: 4, 04: 1 |
| 04 `#flavor-matrix` | `#capability-matrix` | 01: 3, 04: 1, 09: 1 (PLAN done) |
| 04 `### UI per flavor (hand-off …)` | `### UI per capability (hand-off to [08 Capability differences in UI](08-ui-ux.md#capability-differences-in-ui))` | 08: 2 (`#ui-per-flavor…`) |
| 04 `#foss-extractor-lookup` | `### Engine channel lookup` → `#engine-channel-lookup` | 04: 1 |
| 04 `#play-flavor-and-cross-grades` | `### Engine absent or disabled` → `#engine-absent-or-disabled` | 04: 1, 07: 2 |
| 04 `#gpl-boundary` | `### Licence boundary` → `#licence-boundary` | 09: 1 |
| 04 `#corresponding-source` (deleted) | — | 09: 1 |
| 04 `#play-guardrails` (deleted) | — | 04: 1, 08: 1, 09: 2 |
| 04 `### F-Droid and IzzyOnDroid` (deleted) | — | none |
| 04 `### Renovate fast lane` | `### Engine canary` (`#engine-canary`) | none |
| 04 `### Plan C` | `### Fallback engines` (`#fallback-engines`) | none |
| 04 `### Initialisation and downloader` | `### Engine call and transport` | none |
| 07 `#manifest-and-play-declaration` | `## Manifest entries` → `#manifest-entries` | 07: 1, 09: 2 |
| 07 `### play flavor and cross-grades` | `### Engine absent or disabled` → `#engine-absent-or-disabled` | 07: 1 (~423) |
| 08 `#flavor-differences-in-ui` | `#capability-differences-in-ui` | 04: 1, 08: 3, 09: 2 (PLAN done) |
| 09 `### IzzyOnDroid`, `### F-Droid`, `### Google Play`, `### Store metadata`, `### Play App Signing`, `### Play Data safety`, `### Fallback` (Reproducible builds) | deleted | `#store-metadata` 09: 2; `#play-app-signing` 09: 1; `#play-data-safety` 09: 1; `#google-play` 01: 1 |
| 09 `### Hotfix (YouTube fast lane)` | keep heading (APK path) | 04: 1 |

## 9. Per-document checklists

### 01-foundation.md

- **Header/Scope:** Honours add D72–D80, D2/D3 amended, PO-1/2/5/8 resolved, PO-35; "Owns" replaces "build flavors" with "build variants and ABI splits, Chaquopy integration, processes". Scope table rows: "`play` classpath ban" → licence bans; "flavor bindings" → "YouTube bindings"; "Flavors, build types, `BuildConfig`, flavor source sets" → "Build types, ABI splits, `BuildConfig`, the no-engine switch"; licensing row adds Python lockfile and APK scan.
- **Toolchain:** remove NewPipe Extractor, Rhino/rhino-engine, nanojson, `desugar_jdk_libs_nio`, the JitPack repository, its content filter and JitPack verification-metadata notes; add Chaquopy Gradle plugin `com.chaquo.python` (17.0.0 is the latest release on Maven Central on 2026-10-05; AGP 9.4.1 needs master 17.1.0 or a newer release — S7), CPython target version (3.14, fallback 3.13; build-host Python of the same minor version for `.pyc` compilation, Unverified availability in the Debian release container), Tink `tink-android` 1.23.0, quickjs-kt 1.0.15 (conditional), `actions/setup-python` for CI. `gradle.properties`: `neutrodyne.engineManifestUrl`, `neutrodyne.youtubeEngine`; Podcast Index comment "release builds after written permission (PO-3)", drop "never to foss".
- **Module layout:** per §2.2 (rows for `:youtube:ytdlp`, `:update:api`, `:update:impl`; delete `:youtube:streams`, `:playback:cast`); example package `:youtube:ytdlp` → `ch.lkmc.neutrodyne.youtube.ytdlp`; external-library placement: workers also in `:youtube:ytdlp` and `:update:impl`; Chaquopy/Python only in `:youtube:ytdlp`; `PackageInstaller` only in `:update:impl`.
- **Dependency rules:** rewrite rules 1, 2, 6, 8, 9, 10, 13 per §2.2 (no `fossImplementation`/`playImplementation`; features may use `:update:api`; `:youtube:ytdlp` → `:youtube:api`, `:core:{model, common, network, datastore}`; `:update:impl` → `:update:api`, `:playback:api`, `:download:api`, `:core:{model, common, network, datastore}`). Mermaid: `app[":app"]`, replace `ys` by `yd[":youtube:ytdlp<br/>Chaquopy, process :ytx"]`, add `upi`, remove the `fossImplementation` edge. Assertion config: `configurations += setOf("api", "implementation")`, allowed/restricted regexes with `ytdlp` and `update`, `":(?!app).* -X> :youtube:ytdlp"`, `":(?!app).* -X> :update:impl"`; drop the "flavored configurations" Unverified note.
- **Application start-up:** process model paragraph (main, `:ytx`, `:acra`); `NeutrodyneApplication` detects `:ytx` (`Application.getProcessName()` on API 28+, `/proc/self/cmdline` on 26–27): no ACRA install in `attachBaseContext`, `onCreate` returns after `Log.install`; `:ytx` rules (§2.1); initializer table additions (§2.6); start-up test `YtxProcessStartTest`.
- **DI:** components table row (`Distribution`… `FlavorModule`) → `YouTubeBindingsModule`; rule 6 rewritten; `### Flavor modules` → `### YouTube bindings`: one module in `app/src/main` until M9a, then `app/src/youtubeEngine/` vs `app/src/noYouTubeEngine/` (property switch); binding timeline M2 (`YouTubeCapabilitiesSource` static, `NOT_YET_AVAILABLE`), M4 (`ExternalOnlyYouTubeStreamResolver`), M8 (no-op enricher/search/lookup), M9a (engine-backed: `YtDlpStreamResolver`, `YtDlpEnricher`, `YtDlpChannelSearch`, `YtDlpChannelLookup`, `YtDlpEngine` as `YouTubeEngine` + `YouTubeCapabilitiesSource`); `:update:impl` binds its own interfaces in `UpdateModule`. Remove `YouTubeFlavorTexts` binding.
- **Navigation/intent routing:** add `neutrodyne://open/settings/updates`, `neutrodyne://open/help/install`.
- **`## Build flavors` → `## Build variants and ABIs`:** build-type table; ABI splits DSL (Unverified AGP 9 names); APK naming `neutrodyne-{v}-{abi}.apk`; `BuildConfig` fields (`YOUTUBE_ENGINE`, `ENGINE_MANIFEST_URL`, PI key); `BuildInfo` per §2.1; delete flavor source sets, `strings_flavor.xml`, `keepRules/foss.keep` (coroutines repro keeps may move to `app.keep` if the report-only repro job needs them); keep rules for Chaquopy and Python-called classes in `youtube/ytdlp/consumer-rules.pro` (`PyHttp`, `QuickJsEngine`; Unverified Chaquopy's own rules); remove "Tests per flavor". New subsection `### Emergency build without the engine`. **Delete `### Core library desugaring`** (it existed only for NewPipe's NIO needs; confirm nothing else uses desugaring at minSdk 26) and its 4+1 inbound links.
- **Networking:** the YOUTUBE client configuration is also built in `:ytx` for `PyHttp` (no `CredentialLookup`/Room dependency in that graph); `FamilyHintDns` in both processes with per-call pinning (D74); updater uses API (manifest, Atom) and DOWNLOAD (APK, yt-dlp file) clients.
- **Platform compliance:** P1 (Play target API) → "keep targeting the newest API; Android 15+ refuses targetSdk < 24 installs; our targetSdk decides how verification failures reach the self-updater"; add rows: A10 no `execve` from app data (engine in-process; A2 fallback execs only from `nativeLibraryDir`); A14 read-only dynamic code (no DEX downloaded); A17 read-only `System.load` (Chaquopy master marks extracted `.so` read-only; S7); `REQUEST_INSTALL_PACKAGES`/`canRequestPackageInstalls` (API 26); `setRequireUserAction` + `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (API 31); `InstallConstraints` (API 34); `EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON` (API 36.1); background activity starts → notification for `STATUS_PENDING_USER_ACTION`; 16 KB covers Chaquopy's libraries and asset-extracted `.so`.
- **Manifest and permissions:** §2.10; merged-manifest sketch adds `YtxService`, `UpdateStatusReceiver`, `<queries>`.
- **Licensing and dependency policy (keep heading):** `### Licence structure` → one row (repository and every artefact: Unlicense + permissive; no GPL/LGPL/AGPL); `### Licensee allow-list` → single `licenseeRelease`, delete all scoped GPL/MPL entries; **new `### Python and native components`** (lockfile format, allow-list: Unlicense, MIT, ISC, Apache-2.0, Apache-2.0 WITH LLVM-exception, BSD-2/3-Clause, 0BSD, PSF-2.0, Zlib, bzip2-1.0.6, public domain; MPL-2.0 only for unmodified data files; `checkPythonLicences`, `verifyBundledYtDlp`, APK scan list; component inventory with versions as read from the Chaquopy runtime, e.g. CPython 3.14.0 with OpenSSL 3.0.18 and SQLite 3.50.4, Unverified until S7); `### AboutLibraries and the Licences screen`: manual entries for CPython + bundled libraries, Chaquopy runtime, yt-dlp, yt-dlp-ejs/meriyah/astring, CA bundle, QuickJS/quickjs-kt (if shipped); remove NewPipe/Rhino/nanojson/desugaring; `### About statements` → one statement ("Neutrodyne's source code is dedicated to the public domain under the Unlicense. The app also includes third-party components under permissive licences, listed under Licences."; optional "YouTube engine: yt-dlp" credit); `### Copied code and contributions`: never copy GPL/LGPL/AGPL/MPL code into any module (no module exception); yt-dlp is Unlicense — porting its logic is allowed with credit in the file header and `THIRD_PARTY_NOTICES.md`; all contributions Unlicense; PR checkbox reworded; `checkSpdxHeaders` per §2.12.
- **M0 checklist:** no flavor steps; ABI splits; S7; `:youtube:ytdlp`/`:update:*` stubs; lockfile; `YouTubeBindingsModule`.
- **Spikes:** add `### S7 Chaquopy under AGP 9.4.1` (method/pass/fallback from PLAN M0 deliverable; record released vs master, Python version, packaging mode, foreign-ABI asset bytes per split, `Python.start` in `:ytx` on API 26 and API 37 16 KB, cold start of `:ytx`); S6 budget context → per-ABI budgets.
- **Verification log:** remove the `:youtube:streams`/`verifyDependencyPolicy`, `foss.keep`, JitPack and `j$` rows; add `checkPythonLicences` negative check, `check-apk.sh` forbidden-content check, S7 outcome, `:ytx` start test.
- **Testing:** Hilt graph once (no "both flavors"); `Distribution` check → `YouTubeCapabilitiesSource` reason when engine absent; Smoke "About shows flavor" → "version and ABI".
- **Delivery, New names, Open questions, Sources:** per PLAN milestones (M9a/M9b, M11a/M11b); delete `Distribution`, `strings_flavor.xml`, `foss.keep`; add §2 names you own; open questions 6, 8 (AAB), 10 → obsolete; 11 → Chaquopy mechanics; sources: Chaquopy docs/FAQ/Maven metadata, python.org Android, Android 10/14/17 behaviour pages, PackageInstaller refs (§12); remove NewPipe/JitPack/desugaring/F-Droid sources.

### 02-data-model.md

- `youtubePlayable` = `YouTubeCapabilitiesSource.capabilities.value.inAppPlayback` (false in external mode, R3.7); callers re-run context-tail queries when capabilities change.
- `ContextTailTest`: "`play`-flavour YouTube" → "external-mode YouTube".
- Persisted enum `WaitReason`: append `YOUTUBE_ENGINE_OFF` (07 semantics; append-only rule, `ConverterTest` case).
- Delivery row M9 → new anchor, "M9a"; note: engine and updater state live in files (`noBackupFilesDir/ytdlp/`) and DataStore, never Room; the updater writes no `download` rows.
- Anchor `01-foundation.md#build-flavors` (1 link) → `#build-variants-and-abis`.
- No other change expected; grep §10.

### 03-feeds-and-discovery.md

- `foss` mentions (3): `afterIngest` enrichment "(engine present, 04)"; M9 row anchor and "M9a"; any "YouTube channel search in `foss`" → "with the engine".
- Podcast Index key: "empty in every build until written permission" stays; PO-3 option A = release builds.
- Sources: remove the F-Droid NonFreeNet link.

### 04-youtube.md (largest)

- **Header/Contents/Scope:** Implements R3.1–R3.9; Honours D2, D3, D39, D45, D49–D53, D64, D66, D67, D72–D77, PO-1/PO-2 resolved, PO-9, PO-32; Owns: capability matrix and `YouTubeCapabilitiesSource`, `:youtube:*` incl. `:youtube:ytdlp`, the engine (host, `:ytx`, Binder API, OkHttp bridge, JS provider), engine updates and trust chain, licence boundary, engine canary content, runbooks. Layer B text → engine. Responsibilities table rows per §8.
- **Modules and public API:** table per §2.2/§2.4; Kotlin blocks per §2.3; "`:youtube:streams` never binds…" → "`:youtube:ytdlp` never binds `:youtube:api` interfaces; `:app`'s `YouTubeBindingsModule` does ([01 YouTube bindings](01-foundation.md#youtube-bindings))".
- **Threading:** replace `NpeCalls.blocking`/`NewPipe.init` rows with `YtDlpClient` (suspend, IO, async Binder, cancel → `IYtxEngine.cancel` → OkHttp `Call.cancel` + shim cancel flag; deadline + 5 s → kill `:ytx` → `Transient(TIMEOUT)`), `:ytx` start (lazy on first bind/pre-warm; `Python.start`, `sys.path` to the active version), 2 Python worker threads, first call after a cold start 25 s.
- **New names:** replace `Npe*`, `NpeCalls`, `OkHttpNpeDownloader`, `InnertubeChannelResolver`, `RecordingDownloader`/`ReplayDownloader`, patch/jobs with §2 names.
- **`## Flavor matrix` → `## Capability matrix`:** columns "With the engine" / "External mode"; mechanism `YouTubeCapabilitiesSource`; "R3 is fully delivered by `foss`" → by 64-bit APKs with the engine on; external mode delivers R3.1 (links), R3.2 (no premiere hold-back/flags), R3.3, R3.4, R3.7. `### DI bindings` → engine-backed vs external bindings, capability computation (`youTubeEngineBundled`, `youtube.engine_enabled`, start failures → `ExternalReason`). `### Capability consumers` + runtime changes (projector re-diff, planner, claim, Settings). `### UI per flavor` → `### UI per capability …` (About/Licences now identical; add external-reason line in Settings › YouTube).
- **Channel resolution:** Mermaid labels "(foss)" → "(engine)", "play, or extractor failed" → "external mode, or engine failed"; HTML autodiscovery "only network path in `play`" → "in external mode"; `### foss extractor lookup` → `### Engine channel lookup` (`YtDlpChannelLookup`: `lookup` on `https://www.youtube.com/@handle` / `/channel/UC…`, flat, playlist-level `channel_id`, `channel`, `uploader_id`, `thumbnails` (avatar/banner), `description`; gate-aware; null on failure → HTML); error row "Typed name in `play`" → external mode; `### Channel search` → `YtDlpChannelSearch` via the search URL with `sp=EgIQAg%253D%253D`, token cursor, ≤ 3 pages.
- **Atom ingestion:** enrichment via `YtDlpEnricher` (`tab_open` on `/videos`, `/shorts`, `/streams`; per-video `facts` for off-page UPCOMING/LIVE ≤ 5 per channel); fact mapping from yt-dlp fields (`duration`; `availability` `subscriber_only`/`premium_only` → `MEMBERS_ONLY`; `live_status` `is_upcoming` → `UPCOMING`, `is_live`/`post_live` → `LIVE`, `was_live`/`not_live` → `AVAILABLE`; shorts tab → `isShort`); Mermaid "(foss)" → "(engine)"; back catalogue via `tab_open`/`tab_next` (approximate `timestamp` → UTC day, `rawPubDate = "approx"`), token cursor, `CURSOR_EXPIRED` handling.
- **Artwork:** "foss: `ChannelInfo.getAvatars()`/`getBanners()`" → engine lookup `thumbnails`.
- **Content flags:** columns "Engine source" / "External mode"; sources from yt-dlp fields and shim codes; participation "`foss` yes, `play` no" → engine/external; "`play` limitation" → external-mode limitation; Check again → engine.
- **New `## YouTube engine`** (place before `## Stream resolution`): `### Host and packaging` (D72; yt-dlp 2026.08.19 bundled, zipimport incl. ejs 0.8.0, no extras; Chaquopy; sizes; S7/M9a spike gates and results table; fallbacks A2 and Kotlin InnerTube port), `### Process and lifecycle` (D73: pre-warm triggers, idle stop, kill on hang, crash handling, `ENGINE_FAILED`, `:ytx` rules), `### Binder API` (AIDL, methods and JSON schemas, error codes, cursors, size limits), `### Networking bridge` (D74, IP-family pinning), `### JS challenge provider` (D75, thresholds, `NeutrodyneQuickJsJcp`, cache, never Deno/Node).
- **Stream resolution:** intro → yt-dlp facts (`_DEFAULT_JSLESS_CLIENTS = ('visionos',)`, `android_vr` 403 since 2026-08-17, sources §12); `### Initialisation and downloader` → `### Engine call and transport`; `### Resolve algorithm` for `YtDlpStreamResolver` (bridge `resolve`, formats with `vcodec == 'none'`, `YtDlpAudioMapper` from `format_id` + URL query `itag`/`clen`/`lmt`/`expire`/`ip`/`xtags`, KIDS_ONLY, `availableAtMs`); `### Format selection` mapping sentence; `### Exception classification` keyed by shim codes + structured fields, cluster rule kept; IP-family per D74 (`IP_FAMILY_MATCHING_ENABLED` moves to `:youtube:ytdlp`); `### Costs` columns engine/external, ≈ 3 InnerTube requests per resolve (`player_skip` measured in the spike).
- **Playback integration:** "`play`" row → external mode; pre-warm; `Transient(ENGINE_UNAVAILABLE)` row; `availableAtMs` row. **Watch on YouTube:** "In `foss`… external (`play`)" → engine / external mode.
- **Download integration:** `Unsupported` row → external mode; `### play flavor and cross-grades` → `### Engine absent or disabled` (`DISABLED_BY_USER`/`ENGINE_FAILED`/`NOT_YET_AVAILABLE`: claim skips YouTube rows, they wait `QUEUED(YOUTUBE_ENGINE_OFF)`; `NOT_IN_THIS_APK`: reconcile → `FAILED(UNSUPPORTED_STREAM)`, `.part` deleted; completed files listed with Delete and Share; Open question: let completed YouTube downloads play in external mode — default no in v1).
- **Error handling:** "extractor" → "engine"; taxonomy row for engine death/start failure → `Transient(ENGINE_UNAVAILABLE)`; breaker reset on app **or engine** version change (`youtube.breaker_engine_version`); opening enqueues `engine-update-now`; notice per §2.7; `YouTubeAlertNotifier` "engine present only".
- **New `## Engine updates`** (after `## Error handling and circuit breaker`): `### Trust chain` (D76, pinned keys incl. rotation, manifest schema §2.11, `sequence`, `revoked`), `### Update flow` (`EngineUpdateWorker`, steps, staging, `.pyc`, read-only, self-test in fresh `:ytx`, activation at idle, `:ytx` restart), `### Rollback and reset` (≥ 3 parse failures on distinct videos within 30 min and no success; revoked; Reset to bundled; keep at most 2 downloaded versions), `### Policies` (APPROVED default PO-32, UPSTREAM_STABLE expert, OFF), `### Security notes` (same UID, no `isolatedProcess`, 10 MB cap, origin allow-list, no `remote_components`, W^X not engaged).
- **Licensing and legal (keep heading):** `### GPL boundary` → `### Licence boundary` (what ships/never ships, checks — per PLAN PO-1 table); `### Notices` (engine stack entries; optional yt-dlp credit); delete `### Corresponding source`, `### F-Droid and IzzyOnDroid`, `### Play guardrails`; `### Posture and emergency build` rewritten (layer A posture unchanged; layer B outside Play; JS n/sig solving is what German courts treated as circumvention — OLG Hamburg 2024; emergency build = `-Pneutrodyne.youtubeEngine=false`, nightly `no-engine-build`, same-day release); `### Later`: SponsorBlock with the engine; delete the `play` IFrame player.
- **Maintenance and hotfix process (keep heading):** signals (breaker, canary issues, yt-dlp issues/nightlies); `### Renovate fast lane` → `### Engine canary` (what `engine-canary.yml` checks; GitHub runners are bot-checked → recorded responses + API probe; optional live smoke non-blocking); `### Hotfix runbook` (keep heading): path 1 engine update (upstream stable → canary ≤ 6 h → apps ≤ 24 h, sooner after a breaker opening), path 2 dispatch `engine-canary.yml` with a `tag` input for a specific stable, path 3 APK hotfix for shim changes (`release.sh patch --hotfix`, < 30 min); remove IzzyOnDroid/F-Droid/JitPack lines; `### Recorded responses` → `RecordingRH`/`ReplayRH`, `youtube/ytdlp/src/test/resources/recorded/`; `### Plan C` → `### Fallback engines` (A2, Kotlin InnerTube port, YouTube.js + googlevideo + BgUtils; why not NewPipe Extractor: GPL).
- **Settings:** "(`foss`)" → "(engine)"; add §2.8 `youtube.engine_*` and `youtube.breaker_engine_version`.
- **Testing:** per §2.14; `playDebug` row → `ExternalYouTubeModeTest`; minified `fossRelease` smoke → minified `release` through `:ytx`; dex/classpath checks → APK content scan; fixtures path; M9 device checklist adds engine latency/PSS/idle stop, update activation waits during playback, rollback, `armeabi-v7a` external mode.
- **Delivery:** M0 (stub with Chaquopy hello-world per S7; no GPL LICENSE), M2 (`YouTubeCapabilitiesSource` static), M4, M8 (external bindings, reason `NOT_YET_AVAILABLE`), M9a, M9b, M11 (no stores), M14 (JS provider if not shipped; no IFrame player).
- **Open questions:** 13, 14, 15 obsolete; 9 restated for yt-dlp (`live_status = is_upcoming` for premieres); add Unverified items: preroll `available_at` on `visionos`, GitHub Pages cache TTL vs approval latency, upstream key rotation handling.
- **Sources:** replace NewPipe Extractor facts with yt-dlp/Chaquopy/quickjs-kt/Tink/OLG Hamburg/Invidious sources (§12); remove Play policy and F-Droid links (keep YouTube ToS, API policies, robots.txt); NewPipe Takeout/JSON format references may stay.

### 05-groups-opml-backup.md

- "count in both flavors" → "with and without the engine"; capability text "(`play`, and `foss` before M9)" → "(external mode, and every APK before M9)"; "flavor matrix" → "capability matrix" link; "(never in `play`, R3.7)" → "(never in external mode, R3.7)"; "in `play`, a group whose unplayed items…" → "in external mode, …".
- Backup manifest `AppInfoV1(versionName, versionCode, flavor)` → `AppInfoV1(versionName, versionCode, abi)` (no backup has shipped yet).
- Settings whitelist: portable `updates.mode`, `updates.channel`, `youtube.engine_enabled`, `youtube.engine_updates`; the `device_settings` keys of §2.8 are never backed up; note `noBackupFilesDir` (engine versions, update APKs) is excluded by the platform.
- `PlayContextResolverTest`: "`play` flavor all-YouTube group → null" → "external mode …".
- M9 delivery row → new anchor, "with the engine".
- NewPipe JSON format and the Takeout reference stay.

### 06-playback.md

- Header: "M8 (`play` exclusions)" → "M8 (external-mode exclusions)"; "M9 (YouTube branch)" → M9a.
- `PlayResult.NothingToPlay(externalOnly)` comment → "only external-mode YouTube items left"; `UnplayableReason.NotInThisBuild` → `YouTubeExternal(reason: ExternalReason)`; start gates (§Starting playback) accordingly.
- `### YouTube branch`: external mode instead of `play`; `QueueProjector` calls `YouTubeEngine.prewarm(PROJECTION)` when a YouTube item enters the window; capability flips → re-diff (external YouTube items leave the window; Up next rows stay); `Transient(ENGINE_UNAVAILABLE)` handled like `TIMEOUT`; `availableAtMs` wait ≤ 30 s; first resolve after a cold `:ytx` start fits the existing 25 s wrap.
- Delivery: M8 "(and temporarily in `foss`)" → "(every APK until M9)"; M9 → M9a anchor; M11 drop "Play `mediaPlayback` FGS declaration text"; M13 drop `:playback:cast` in `play`.
- Testing/device matrix: "minified `fossRelease`" → "minified release"; `EpisodeResolverTest` adds `ENGINE_UNAVAILABLE` and `availableAtMs`. Android Auto "Unknown sources" mention stays.

### 07-downloads.md

- Header/scope: M9 anchor; "M9 (YouTube transfers)" → M9a, "APKs with the engine".
- Transition row "reconcile in a build without YouTube downloads (`foss` → `play` cross-grade)" → "reconcile on an APK without the engine (`NOT_IN_THIS_APK`)", link `#engine-absent-or-disabled`.
- `## YouTube transfers`: "`foss` only" → "with the engine"; `### play flavor and cross-grades` → `### Engine absent or disabled` (rules as in 04's section of the same name; new `WaitReason.YOUTUBE_ENGINE_OFF` with Downloads text "Waiting — in-app YouTube is off" / "Waiting for the YouTube engine"; claim `youtubeAllowed = capabilities.downloads`).
- Reconciler step 6 → `NOT_IN_THIS_APK` only; other reasons wait.
- Settings row "(`foss`)" → "(engine)".
- `## Manifest and Play declaration` → `## Manifest entries`: keep the entries table; delete the Play policy paragraph, the `dataSync` declaration draft and the refusal fallback (risk P2 retired).
- Delivery: M8 "in both flavors" → "on every APK"; M9 → M9a anchor, "engine-absent reconcile rule".
- State that the in-app updater does not use the download engine (D78).
- Tests: `DownloadReconcilerTest` cases for each `ExternalReason`.

### 08-ui-ux.md

- Header/scope/responsibilities: flavor → capability; Implements add R3.9 (display), R6.1–R6.4 (screens).
- Screens: Episode detail ("YouTube in `play` or unavailable" → "external mode or unavailable"; overflow "(YouTube, foss, …)" and "Check again (`foss`…)" → engine); Podcast detail "YouTube back catalogue (foss)" → "(engine)"; Up next wireframe "play-flavor YouTube" → "external-mode YouTube"; Discover "M9 (YouTube channel search in `foss`)", chip "YouTube search chip in foss" → "with the engine (`channelSearch`)"; Add sheet helper "(`play` and `foss` alike…)" → drop flavor; Settings home wireframe "1.0.0 (foss)" → "1.0.0 (arm64-v8a)"; Diagnostics "APP 1.0.0 (1000095) foss" → ABI, engine version, updater mode; `EpisodeAction.CheckAvailability` comment → engine; EpisodeRow "Unavailable (… foss)" → engine.
- **New `### Updates settings`** and **`### Install and updates help`** (under `## Screens`): Settings › Updates (mode radio Off/Notify/Automatic, channel Stable/Beta, Check now, last check, current `UpdateState`, "Managed by Obtainium", link to help), first-run card, update notification states, `UpdateBlockedKey` sheet (per `InstallBlockReason`; "Download in browser"), `VerificationNoticeKey` dialog (once; recommends "indefinitely"; neutral wording, no countdown or urgency), `WhatsNewKey` sheet; help page sections mirroring the README (choose the APK for your device, verify (`SHA256SUMS`, certificate SHA-256, `gh attestation verify`), "install unknown apps", the advanced flow with "indefinitely", ADB and Shizuku as power-user fallbacks, uncertified ROMs, Obtainium with the per-ABI filter, Android Auto "Unknown sources"); unknown-sources permission rationale before `ACTION_MANAGE_UNKNOWN_APP_SOURCES` in `### Permission prompts`.
- Settings structure: `UPDATES` page row; `YOUTUBE` rows: "Play YouTube in the app" switch, engine version and source, engine updates policy, "Check for engine update", "Reset to bundled", engine status line (replaces "extractor status line"), external-reason explanation; `ABOUT`: "version and ABI", "Install & updates"; `DOWNLOADS` YouTube row "(engine, M9a)".
- **`## Flavor differences in UI` → `## Capability differences in UI`:** columns "Engine present" / "External mode"; delete the About/Licences row (identical now) and the whole `play` wording-rules paragraph, `YouTubeFlavorTexts`, `PlayStringsPolicyTest`; strings move to normal resources; add external-reason texts: `NOT_IN_THIS_APK` "This version of Neutrodyne can't play YouTube in the app on this device. Videos open in YouTube." (+ "Get the 64-bit version" → Install help when `Build.SUPPORTED_64_BIT_ABIS` is non-empty), `DISABLED_BY_USER` "In-app YouTube is off" + switch, `ENGINE_FAILED` "The YouTube engine couldn't start" + "Try again"; YouTube-logo rule stays. Fix 3 internal links.
- Navigation: keys and deep links of §2.9; banners: `:app` root shows `UpdateNotices` dialogs.
- Testing: drop `PlayStringsPolicyTest`; `EpisodeRowTest` "flavor state" → capability; screenshot matrix "external (`play`)" → "external mode"; add tests for the new screens and sheets.
- Delivery: M9 → M9a/M9b (engine rows, external texts); M11a (updates UI). New names: remove `YouTubeFlavorTexts`; add keys/screens. Open questions 8, 9: link/wording updates.

### 09-quality-and-release.md (large)

- **Header/Scope:** Honours D2, D3, D59–D63, D76, D78–D80; PO-2/PO-5/PO-8 resolved, PO-10, PO-14, PO-18, PO-31, PO-33–PO-36; Owns: GitHub-only distribution and release assets, in-app updater design, engine-canary workflow, developer-verification guidance (unregistered distribution); scope table rows for distribution/repro/verification/privacy updated; repository files list: drop `scripts/youtube/bump-extractor.sh`, `fastlane/**`, `fdroid/…`; add `engine-canary.yml`, `changelogs/`, scripts of §2.11.
- **Test strategy:** E7 seam (NewPipe.init) → engine replay seam (`ReplayRH` selected by an instrumentation argument passed to `YtxService`; googlevideo stand-in on MockWebServer); E8 rename; recorded-response policy → shim; "No PR job contacts YouTube" stays.
- **CI pipelines:** workflow Mermaid: drop IzzyOnDroid/F-Droid nodes; add engine-canary → GitHub Pages; `ci.yml`, `nightly.yml`, `release.yml` per §2.12 (rewrite the sequence diagram; steps 1–9; no Play, no corresponding source, no `verify-repro`); **new ``### `engine-canary.yml` ``**; helper workflows without flavors (`:app:generateReleaseBaselineProfile`); CI scripts table per §2.12; hardening secrets table; time budget row "upstream yt-dlp stable → approved manifest ≤ 6 h".
- **Static analysis:** build-output checks per §2.12 (`check-apk.sh`); remove `play` dex and F-Droid signing-block rationale; PR template: drop `play` wording rule and fast-lane line, "Both flavors" → "both capability modes", add shim-PR line (`shimTest` green against the bundled and latest approved yt-dlp; re-record when requests change).
- **Dependency updates:** Renovate JSON (remove Rhino and NewPipe rules; add Chaquopy rule); review table: replace NewPipe row with Chaquopy row and a "bundled yt-dlp" row (`bump-ytdlp.sh`, canary-approved versions only).
- **Versioning and signing:** "never reused (F-Droid and Play remember them)" → immutable releases lock tags, updater/Obtainium compare codes; changelog path `changelogs/`; remove Play notes, `PLAY_PUBLISHING`; key ceremony holders per PO-35, step 4 without F-Droid and developer-verification console (README + release bodies + `update.json` + `NEUTRODYNE_CERT_SHA256`); Gradle signing "unsigned … F-Droid" → PRs; **delete `### Play App Signing`**; `### Key loss or compromise`: loss = users must reinstall, later registration impossible; compromise = v3.1 runbook (`apksigner rotate --out lineage --old-signer --ks old.p12 --new-signer --ks new.p12`, sign with `--lineage`, default v3.1 targets API 33+, `--rotation-min-sdk-version 28` option; API 26–27 verify only v2 and keep trusting the old key; rotation needs the old key, so it does not help after loss), rehearsal in M11b.
- **Distribution channels (keep heading):** GitHub Releases only; asset table §2.11; immutable releases and attestations; release body template (certificate SHA-256 in AppVerifier format, verify commands); `### GitHub Releases and Obtainium` (per-ABI filter regex, e.g. `neutrodyne-.*-arm64-v8a\.apk$`, or Obtainium's `autoApkFilterByArch`; badge `https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/<owner>/Neutrodyne`; Obtainium uses the REST API, 60 requests/h per IP, users may add a token); `### Beta channels` (pre-releases, in-app beta channel PO-33, Obtainium "include prereleases"); new `### Mirror` (PO-34); delete `### IzzyOnDroid`, `### F-Droid`, `### Google Play`, `### Store metadata`.
- **New `## In-app updater`** (after `## Distribution channels`): design per D78 and §2.5–§2.11 (manifest schema, check, download, verification, install session per API level, `STATUS_*` handling, verification-failure mapping, background → notification, idle gate, Obtainium detection via `getInstallSourceInfo`, debug builds off, privacy, tests, device checklist incl. `pm set-developer-verification-result`).
- **Reproducible builds (keep heading):** report-only permanently, no F-Droid rationale, no `apksigcopier`, delete `### Fallback`; hygiene rows kept as good practice; build-time secrets row: a PI key may be injected into release builds after permission (repro check runs without it).
- **Developer verification (keep heading):** replace registration steps with: phase table, affected/unaffected devices, advanced-flow steps, update consequences ("7 days" trap), exempt paths (ADB; shell-UID installers such as Shizuku — power-user only), package-name squatting (P9), notice timing PO-36, watch cadence (calendar issue every 2 weeks), testing hooks; README "Install and update" content owner (draft wording; neutral tone).
- **Privacy:** commitments without "in `foss`"/`play`; `PRIVACY.md` outline item 7 → "What the YouTube engine and the updaters contact and how to turn them off"; inventory §2.13; delete `### Play Data safety`; "What hosts receive" sentence per §2.13.
- **Crash reporting/diagnostics:** drop "F-Droid-accepted (NewPipe precedent)" and Android-vitals/`alsoReportToAndroidFramework`; ACRA not in `:ytx`; diagnostics `APP` section: ABI, engine version/source/health, updater mode, installer of record.
- **Localisation:** drop fastlane translation/store listings.
- **Performance budgets:** §2.15.
- **Release checklist:** every release (three APKs, mapping, `SHA256SUMS`, `neutrodyne-update.json`, immutability, attestations, in-app updater and Obtainium install on test devices); minor/stable (no store items); `### Hotfix (YouTube fast lane)` (keep heading): engine path first (04 runbook), APK hotfix second; v1.0 gate table per PLAN M11 ACs; "Plus" sentence: remove registration/F-Droid/IzzyOnDroid/GPL; add README Install and update final, rotation rehearsal, PO-34 decided.
- **Settings:** add `updates.*` keys (§2.8).
- **Delivery, New names, Open questions, Sources:** per PLAN; open questions 3 (PO-3 → release builds), 10, 11, 13 → obsolete; 14: drop PEPK/IzzyOnDroid/fdroidserver/registration items, add Chaquopy + host Python in the release container, Obtainium package names, `getInstallSourceInfo` visibility, test-hook availability, GitHub limits for `releases/download` and Atom, Pages cache TTL; sources §12.

## 10. Global residue to eliminate

Search (case-insensitive) in your document and resolve every hit:

```
grep -n -i -E 'foss|`play`|play build|play flavor|playRelease|playDebug|bundlePlay|playImplementation|fossImplementation|flavor|FlavorModule|Distribution\.|strings_flavor|YouTubeFlavorTexts|:youtube:streams|youtube/streams|NewPipe Extractor|NewPipeExtractor|Npe[A-Z]|NpeCalls|Innertube|nanojson|Rhino|mozilla\.javascript|JitPack|desugar|j\$|collectGplSources|corresponding.source|bump-extractor|no-youtube-streams|emergency-patch-check|check-play-dex|SPDX.*GPL|GPL|Google Play|Play Console|Play policy|Play guardrail|Data safety|PEPK|upload key|Play App Signing|Play Publisher|PLAY_|Families|target audience|store listing|Android vitals|alsoReportToAndroidFramework|update ownership|F-Droid|fdroid|IzzyOnDroid|AllowedAPKSigningKeys|NonFreeNet|anti-feature|apksigcopier|verify-repro|fastlane|universal APK|25 MB|register|registration|Full Distribution|app\.neutrodyne|PO-22|PO-23|PO-29|:playback:cast|Chromecast' docs/design/<your doc>.md
```

Legitimate remaining mentions (keep):
- "Why not NewPipe Extractor (GPL-3.0)" in an alternatives/fallback table or Sources; prior-art and behaviour-only references (AntennaPod, NewPipe app, LibreTube, Podcini) in the copied-code rule.
- The NewPipe **app's subscription JSON format** (`NewPipeSubscriptions`, `NEWPIPE_JSON`, "Export YouTube channels (NewPipe)") and NewPipe's Takeout parser as a format reference.
- Android Auto's "Unknown sources" developer setting for sideloaded apps.
- "No Google Play services" privacy commitments; Chromecast "not planned" because Cast needs Play services; Android Auto Backup through Google's transport/Play services (R1.8).
- "Developer verification" when explaining unregistered distribution; "register" only in "not registered" / "registration stays possible later".
- "GPL" in never-ship lists, licence checks and the "no GPL" rule; "universal APK" only as "no universal APK".
- `dev.imranr.obtainium.fdroid` as a package ID in `<queries>`.
- `play` as a verb or in `playEpisode`, `PlayResult`, "Play group", `PlaybackController`, `play_session`.

## 11. README.md (not assigned to a design-doc reviser — lead/orchestrator)

Licence section → Unlicense + permissive, no GPL, list of bundled components (PLAN PO-1 table); R3 row → "audio playback and downloads with the built-in yt-dlp engine (64-bit APKs); 'Watch on YouTube' otherwise"; add R6 row; "R1–R5, N1–N11" → "R1–R6, N1–N12"; docs table wording for 01 ("build variants and ABIs"), 04 ("capability matrix, yt-dlp engine, engine updates"), 09 ("GitHub-only distribution, in-app updater"); new "Install and update" section (content: 09 Developer verification / D80; certificate SHA-256 placeholder until the M0 key ceremony).

## 12. Sources verified 2026-10-05 (cite these; mark anything else "Unverified:")

- yt-dlp: licence/README licensing https://github.com/yt-dlp/yt-dlp#licensing · release files and channels https://github.com/yt-dlp/yt-dlp#release-files · signing key https://github.com/yt-dlp/yt-dlp/blob/master/public.key · embedding https://github.com/yt-dlp/yt-dlp#embedding-yt-dlp · stable 2026.08.19 https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19 · client defaults (`visionos`, `android_vr` 403) https://github.com/yt-dlp/yt-dlp/blob/51bab8a0116f4d8004c315706d809782607d5847/yt_dlp/extractor/youtube/_base.py and https://github.com/yt-dlp/yt-dlp/pull/17461 · EJS https://github.com/yt-dlp/yt-dlp/wiki/EJS · JS challenge provider API https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/jsc/README.md · JS runtime requirement https://github.com/yt-dlp/yt-dlp/issues/15012 · PyInstaller licences https://github.com/yt-dlp/yt-dlp/blob/master/THIRD_PARTY_LICENSES.txt · PO Token Guide https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide · yt-dlp-ejs https://github.com/yt-dlp/ejs
- Chaquopy: https://github.com/chaquo/chaquopy · docs (17.0: AGP 7.3–9.2; Python 3.12+ 64-bit only; one module per app) https://chaquo.com/chaquopy/doc/current/android.html · FAQ (ABI splits "won't help much") https://chaquo.com/chaquopy/doc/current/faq.html · Maven metadata (latest 17.0.0, 2025-11-30) https://repo1.maven.org/maven2/com/chaquo/python/gradle/maven-metadata.xml · runtime artifacts https://repo1.maven.org/maven2/com/chaquo/python/target/
- CPython on Android: https://docs.python.org/3/using/android.html · https://www.python.org/downloads/android/ · licence/incorporated software https://docs.python.org/3/license.html · versions/EOL https://devguide.python.org/versions/
- JS/crypto: quickjs-kt https://github.com/dokar3/quickjs-kt · QuickJS https://bellard.org/quickjs/ · androidx javascriptengine https://developer.android.com/jetpack/androidx/releases/javascriptengine · Tink https://github.com/tink-crypto/tink-java (tink-android 1.23.0 on Maven Central) · `Signature` Ed25519 API 33+ https://developer.android.com/reference/java/security/Signature
- Platform: Android 10 W^X https://developer.android.com/about/versions/10/behavior-changes-10 · Android 14 safer dynamic code https://developer.android.com/about/versions/14/behavior-changes-14 · Android 17 read-only `System.load` https://developer.android.com/about/versions/17/behavior-changes-17 · 16 KB https://developer.android.com/guide/practices/page-sizes · PackageInstaller (API 36.1 verification reasons) https://developer.android.com/reference/android/content/pm/PackageInstaller · SessionParams https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams · InstallConstraints https://developer.android.com/reference/android/content/pm/PackageInstaller.InstallConstraints.Builder · Settings `ACTION_MANAGE_UNKNOWN_APP_SOURCES` https://developer.android.com/reference/android/provider/Settings · apksigner https://developer.android.com/tools/apksigner · v3 scheme https://source.android.com/docs/security/features/apksigning/v3 · AOSP verification code https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/pm/PackageInstallerSession.java
- Developer verification: https://developer.android.com/developer-verification · guides https://developer.android.com/developer-verification/guides · FAQ https://developer.android.com/developer-verification/guides/faq · Help Center https://support.google.com/android/answer/17065026?hl=en · advanced flow https://support.google.com/android/answer/17588095?hl=en · blog 2026-03-19 https://android-developers.googleblog.com/2026/03/android-developer-verification.html · package-name rules https://developer.android.com/developer-verification/guides/android-developer-console · limited distribution https://developer.android.com/developer-verification/guides/limited-distribution · LineageOS https://lineageos.org/Developer-Verification/ · Shizuku https://shizuku.rikka.app/guide/setup/ · Android Auto sideloading https://www.androidauthority.com/sideload-apps-on-android-auto-3681820/
- GitHub: immutable releases https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases · changelog https://github.blog/changelog/2025-10-28-immutable-releases-are-now-generally-available/ · `actions/attest` https://github.com/actions/attest · attestations (SLSA L2) https://docs.github.com/en/actions/concepts/security/artifact-attestations · `latest/download` https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases · REST limits https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api · latest release semantics https://docs.github.com/en/rest/releases/releases#get-the-latest-release · Pages limits https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits · youtube-dl reinstated https://github.blog/2020-11-16-standing-up-for-developers-youtube-dl-is-back/ · Obtainium https://github.com/ImranR98/Obtainium · deep links https://wiki.obtainium.imranr.dev/deep_links/
- Legal: YouTube ToS https://www.youtube.com/static?template=terms · API policies https://developers.google.com/youtube/terms/developer-policies · OLG Hamburg (heise) https://heise.de/-10179284 · Invidious takedown https://alternativeto.net/news/2023/6/youtube-legal-team-asked-invidious-developers-to-take-down-the-service-within-7-days · youtubedl-android (GPL, do not use) https://github.com/yausername/youtubedl-android · bgutil (GPL, do not use) https://github.com/Brainicism/bgutil-ytdlp-pot-provider
