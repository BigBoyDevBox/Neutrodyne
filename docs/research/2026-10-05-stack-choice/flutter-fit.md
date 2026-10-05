# Research notes: Flutter (Dart) as Neutrodyne's single codebase

Research area: **flutter-fit**. Date: 2026-10-05. Scope: whether Flutter can carry Neutrodyne v1.0 on Android, Windows, macOS and Linux, plus a self-hosted sync server, under the owner's current rules:

- Unlicense repository. No GPL or AGPL. **LGPL allowed** when dynamically linked and its notices are met.
- GitHub Releases only. No platform developer registration. Unsigned desktop builds.
- Android ships debug builds signed with the committed key (PO-35), notify-only update checks, `ch.lkmc` IDs.
- yt-dlp runs in an embedded CPython with runtime engine updates.

This note assesses Flutter only. **It makes no recommendation.** It sits beside the KMP notes in `research3/` (`kmp-architecture.md`, `desktop-playback.md`, `desktop-distribution.md`, `sync-server.md`), which assumed LGPL was excluded.

**Evidence labels:**

- **Verified** means checked today against a primary source: a URL, pub.dev's API, a package's own git repository, or a Flutter engine artifact.
- **Measured** means I downloaded the artifact in this session and measured it on a Linux x64 host.
- **Unverified** marks everything else.

**How the checks were done:**

- Package metadata comes from the pub.dev JSON API (`/api/packages/<name>`, `/score`, `/publisher`).
- Commit and author counts come from blobless clones of each repository: `git log --all --since=2025-10-05 --no-merges`, with bot authors removed.
- Open-issue counts come from GitHub's `open_issues_count`, which **includes open PRs**.

---

## Strengths and weaknesses at a glance

### Strengths (what Flutter gives Neutrodyne)

1. **No Java runtime anywhere on desktop.**
   - Flutter desktop apps are native executables: AOT-compiled Dart plus a C++ engine. The question of bundling OpenJDK (GPL-2.0 + Classpath Exception), which blocks the JVM/KMP desktop route, does not arise.
   - The engine's aggregated licence file contains **no GPL or LGPL component**. Measured: 74 package groups in `sky_engine/LICENSE` for engine `692136cb…`.
   - On Linux the engine links the system's GTK3, GLib, Pango and ATK. These are LGPL system libraries that we would not ship in DEB, RPM or tar.gz packages, and LGPL is now allowed anyway.
2. **A Dart sync server can share code with the clients and still avoid a JVM.**
   - DTOs, the HLC, `OrderKey`, merge rules and even drift table definitions are plain Dart packages, usable by the Flutter app and by the server.
   - `dart compile exe` / `dart build cli` produce a self-contained native executable ("a small Dart runtime" is included) and can cross-compile to Linux x64, arm64, arm and riscv64 from any host (Dart ≥ 3.8/3.9). The server image therefore needs no OpenJDK.
3. **Broader desktop target matrix than Compose Multiplatform 1.12.1.**
   - Flutter 3.47 supports Windows 10/11 on **x64 and arm64**, macOS 12–27 on **x64 and arm64**, and Debian 10–13 / Ubuntu 20.04–24.04 LTS on x64 and arm64.
   - The macOS engine binary is universal (Measured: `FlutterMacOS` is a Mach-O universal binary, x86_64 + arm64).
4. **Persistence works the same everywhere.**
   - drift 2.35.1 (MIT) sits on `sqlite3` 3.7.0, which bundles SQLite 3.53.x through Dart build hooks on Android, iOS, Windows, macOS and Linux.
   - It provides typed SQL, `.drift` files that accept plain `CREATE TABLE`, reactive `watch()` queries, and schema export with migration-test tooling.
   - The plan's SQL-level schema (02) ports close to mechanically.
5. **One UI toolkit with a mature Material 3 implementation.**
   - Material now ships as `material_ui` 1.5.0 (BSD-3, flutter.dev).
   - Mature pieces include `Hero` shared-element transitions for R5.7, slivers for cover grids, `NavigationBar`/`NavigationRail`, and `dynamic_color` 2.1.0 for Android 12+ wallpaper colour.
   - Accessibility test guidelines are built into `flutter_test`: `androidTapTargetGuideline`, `textContrastGuideline`, `labeledTapTargetGuideline`.
6. **Native escape hatches are first-class.**
   - Pigeon 29.0.6, `jni`/`jnigen` 1.x and `ffigen` 22 are BSD-3 and maintained by the Dart and Flutter teams.
   - The Android host is a normal Gradle app module. Kotlin code from the existing plan (Media3 service, UIDT JobService, Chaquopy `:ytx` process, `ArtworkProvider`, Auto Backup rules) can live there unchanged in shape.
7. **Active, well-staffed core.**
   - Monthly stable patch releases: 3.47.0 on 2026-08-12 through 3.47.6 on 2026-10-01.
   - Impeller is now the default renderer on all desktops (3.47).
   - Host-OS-native builds for each desktop exist in `flutter_tools`.

### Weaknesses (what Flutter costs or risks)

1. **PO-35 conflict: published "debug builds" mean JIT debug mode.**
   - Flutter's Gradle plugin maps every *debuggable* build type to Flutter **debug mode**: JIT, assertions on, service extensions, larger, "janky" (`FlutterPluginUtils.buildModeFor`, Verified in 3.47.6 source).
   - The arm64 debug engine alone is 38.8 MB stripped (Measured), plus a 15.2 MB Vulkan validation layer in the same artifact.
   - Shipping debuggable APKs as D2/D61 require would ship a slow, oversized app. The way out is publishing Flutter *release-mode*, non-debuggable APKs signed with the committed key, which needs an owner decision (Q-F1).
2. **The Android media stack in the Flutter ecosystem is thin and single-maintainer.**
   - `audio_service` 0.18.19 still runs on the legacy `androidx.media:media:1.7.0` `MediaBrowserServiceCompat`, not Media3. Issue #942 "Update Android implementation to use media3" has been open since 2022-06-21.
   - It had 7 commits from 2 authors in 12 months. Its README recommends asking the user to disable battery optimisation, which N2 forbids.
   - `just_audio` 0.10.6 pins Media3 **1.4.1** (current plan: 1.11.x) and had **one** commit in 12 months.
   - A Media3-grade result (D37–D43) means writing **a custom Kotlin Media3 plugin**, which reuses 06 but splits the database between Dart and Kotlin (weakness 3).
3. **Dart–Kotlin data split on Android.**
   - Media3's service, Android Auto browsing, resumption, UIDT downloads and WorkManager refresh all run when no Flutter UI exists.
   - Two ways to cover that:
     - start headless Flutter engines (audio_service and workmanager do this);
     - let Kotlin read and write the drift database directly.
   - Two SQLite copies in one process (Dart's bundled build and Android's) break SQLite's POSIX-lock bookkeeping; the SQLite authors document this as a corruption cause. That forces either one shared SQLite library or a single writer.
4. **Desktop audio: no permissive, complete, maintained Flutter player.** Every desktop path needs native work:
   - **`media_kit` 1.2.6** (libmpv):
     - Last release 2025-12-13.
     - Its Windows audio libs bundle **mpv v0.35.1** from release `20251213` of `libmpv-win32-audio-cmake`.
     - Its LGPL FFmpeg build enables only the `overlay` and `equalizer` filters, so there is no `silenceremove`.
     - On Linux it links the **distro's libmpv**, and Debian's mpv binaries are **GPL-3+** (Debian copyright file).
   - **`just_audio`** desktop back-ends lack skip silence, request headers (Windows) and caching.
   - **`audioplayers`** lacks playlists, gapless playback and skip silence.
   - Skip silence on desktop needs native DSP in every option.
5. **OS media integration on desktop is fragmented.**
   - **macOS:** covered by `audio_service`.
   - **Windows SMTC:** either `smtc_windows` (Rust via flutter_rust_bridge, no commits in 12 months) or `audio_service_win` 0.0.3 (5 stars).
   - **Linux MPRIS:** `audio_service_mpris`, which depends on Canonical's `dbus` package under **MPL-2.0**. D3 allows MPL-2.0 only for unmodified data files (Q-F2).
   - Expect C++/WinRT and GDBus code of our own.
6. **Linux accessibility is weak.**
   - The engine uses ATK (Measured `NEEDED libatk-1.0.so.0`).
   - Canonical's own Flutter installer has Orca-unreadable dropdowns, reported 2025-12-21 and still open in Ubuntu 26.04 dailies on 2026-01-17.
   - That is better than Compose Desktop ("not supported" on Linux), but N4 on Linux is not assured.
7. **Ecosystem churn in 2026.**
   - Material and Cupertino moved out of the SDK into `material_ui`/`cupertino_ui`. The SDK copies are "scheduled for formal deprecation in the upcoming Fall stable release in November".
   - Impeller became the default desktop renderer only in 3.47 (Aug 2026), and the Skia fallback "will be removed".
   - Multi-window is still experimental.
   - `flutter_adaptive_scaffold` is discontinued.
   - Dart macros were cancelled (Jan 2025), so code generation stays on `build_runner`.
8. **Bus factor of key community packages.**

   | Package or area | Concentration (commits in the last 12 months) |
   |---|---|
   | drift and sqlite3 | Simon Binder: 320 of 359 and 399 of 403 |
   | background_downloader | one main author: 128 of 146 |
   | workmanager | 145 of 147 |
   | serious_python | 57 of 70 |
   | LeanFlutter desktop plugins (`window_manager`, `tray_manager`, `fastforge`) | essentially one author |
   | Audio stack | one owner (Ryan Heise) |

9. **The Kotlin/Compose design work is mostly rewritten, not ported.**
   - Requirements, schema, formats, algorithms, the YouTube trust chain and the UX specification carry over.
   - Module graph, DI, Compose UI, Room DAOs, Paging 3, Gradle policy tasks, Roborazzi/GMD testing and most of 09 do not.
   - The existing Kotlin designs for Media3, UIDT and Chaquopy survive only inside the Android host.
10. **Builds need one native runner per desktop OS and architecture.**
    - `flutter build windows` and `flutter build macos` work only on their own OS.
    - Linux cross-builds from x64 to arm64 are "not currently supported", so a Linux arm64 build needs an arm64 runner.
    - Windows arm64 also needs an arm64 host.

---

## Versions and baseline facts (2026-10-05)

| Item | Value | Source |
|---|---|---|
| Flutter stable | **3.47.6** (2026-10-01), engine `692136cb6582dbfc5af3fb33c2515a069f2f66d0`; 3.47.0 released 2026-08-12 | [releases_linux.json](https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json) (Verified) |
| Dart | **3.13.5** | same |
| flutter/flutter repository | 179k stars; **13,294** open issues + PRs; BSD-3-Clause | GitHub search API (Verified) |
| Flutter 3.47 Android toolchain | Java 17 minimum, KGP 2.4.0, AGP 9.1.0, Gradle 9.3.1; `flutter.minSdkVersion` 24; template compileSdk 36, targetSdk 36, NDK 28.2.13676358 | [What's new in 3.47](https://flutter.dev/blog/whats-new-in-flutter-3-47); `FlutterExtension.kt` in the 3.47.6 tag (Verified). Unverified: Flutter's Gradle plugin with the plan's AGP 9.4.1 / Gradle 9.7.1 |
| Supported platforms | Android API 24–37 (CI tested 24–36); Windows 10/11 x64 and arm64; macOS 12–27 x64 and arm64 ("Intel deprecation in progress"); Debian 10–13 and Ubuntu 20.04–24.04 LTS, x64 and arm64 | [Supported platforms](https://docs.flutter.dev/reference/supported-platforms) (Verified). Ubuntu 26.04 LTS is not yet listed |
| Renderer | Impeller is the default on macOS, Windows and Linux since 3.47; Skia opt-out exists, but "fallback options will be removed in a future release" | [What's new in 3.47](https://flutter.dev/blog/whats-new-in-flutter-3-47) (Verified) |
| Material | `material_ui` 1.5.0 (2026-09-28, BSD-3, flutter.dev; first release 2026-02-18); migration via `dart fix --apply --code=migrate_design_widgets`; M3 Expressive appears as a `StyleVariant` (1.2.0) with partial widget support (1.5.0: IconButton) | [material_ui](https://pub.dev/packages/material_ui), [changelog](https://pub.dev/packages/material_ui/changelog) (Verified) |
| Build hooks (native assets) | Stable since Dart 3.10; `dart build cli` bundles code assets | [hooks](https://dart.dev/tools/hooks), [dart build](https://dart.dev/tools/dart-build) (Verified) |

---

## 1. Android media: `audio_service` + `just_audio`, or a custom Media3 plugin

### 1.1 Packages

| Package | Version / date | Licence | Maintainers (12-month commits) | Repository health | Android base |
|---|---|---|---|---|---|
| `audio_service` | 0.18.19 / 2026-06-29 (first 2018-11-28; 81 versions) | MIT | Ryan Heise; 7 commits by 2 authors | 871 stars; 205 open issues + PRs; #942 "use media3" open since 2022; #996 `ForegroundServiceStartNotAllowedException` open since 2023-02 (updated 2026-07); #1137 "Problem with android 15" open | **`androidx.media:media:1.7.0`**, `MediaBrowserServiceCompat`, compileSdk 35 |
| `just_audio` | 0.10.6 / 2026-06-29 (124 versions); Flutter Favorite | MIT/Apache-2.0 (per pub tags) | Ryan Heise; **1 commit** in 12 months (AGP 9 support) | 1,219 stars; 350 open | **Media3 1.4.1** (`media3-exoplayer`, `-dash`, `-hls`, `-smoothstreaming`), minSdk 16 |
| `just_audio_background` | 0.0.1-beta.17 / 2025-05-13 | MIT | same | beta for years | wraps audio_service |
| `audio_session` | 0.2.4 / 2026-06-29 | MIT | 8 commits, 2 authors | 150 stars; 42 open | audio focus / AVAudioSession |

Sources: pub.dev API; [audio_service #942](https://github.com/ryanheise/audio_service/issues/942); [#996](https://github.com/ryanheise/audio_service/issues/996); [#1137](https://github.com/ryanheise/audio_service/issues/1137). Build files were read in the `minor` branches (Verified).

### 1.2 What works today with `audio_service` + `just_audio` (Verified from READMEs and source)

- **MediaSession, notification, lock screen, headset and Bluetooth buttons:** yes, through `MediaSessionCompat`.
- **Android Auto browse tree:**
  - Yes: `onGetRoot`, `onLoadChildren` and `onSearch` are forwarded to Dart `getChildren`/`search`.
  - The service starts a shared `FlutterEngine` (`AudioServicePlugin.getFlutterEngine`), so a cold start from Auto runs Dart `main()` headless.
- **System UI resumption card after reboot:**
  - The service answers `BrowserRoot.EXTRA_RECENT` with a `RECENT_ROOT_ID`, and Dart must return the recent item.
  - Unverified: reliability on Android 15–17 and artwork without network.
- **Speed with pitch preserved:** `setSpeed` everywhere. `setPitch`, **skip silence**, the equaliser and volume boost are **Android only** (just_audio's platform table).
- **Gapless playback:** yes on Android (concatenated sources).
- **Caching while streaming:**
  - Only `LockCachingAudioSource`, marked **experimental**. It runs through a **local HTTP proxy on localhost**, which needs cleartext to localhost.
  - Media3's `SimpleCache`/`CacheDataSource` is not exposed, so D40 (500 MB LRU, DAI-safe keys) is not reachable from Dart.
- **Request headers** (Basic-auth feeds): implemented through the same proxy by default.
- **Chapters:**
  - No embedded ID3 `CHAP` or MP4 chapter API; only ICY metadata.
  - Podcasting 2.0 JSON and Podlove chapters would be Dart code. Embedded chapters need a Dart parser or native code.
- **D39 media URI** (`neutrodyne://episode/{id}` resolved per connection): no equivalent. Sources are URLs, files or Dart `StreamAudioSource` (proxy-based).
- **Android 17 background-audio hardening (D43):**
  - Not addressed by the package.
  - Its README offers two ways around the Android 12+ FGS exception: `androidStopForegroundOnPause: false`, or asking the user to turn off battery optimisation through `optimize_battery`. The second is excluded by N2.
  - The Tap-to-resume path (06 Lifecycle) would be custom Kotlin.

### 1.3 The alternative: a custom Kotlin Media3 plugin inside the Flutter Android host

What carries over from 06:

- `MediaLibraryService` (`NeutrodynePlaybackService`), ExoPlayer configuration and load control.
- `ResolvingDataSource`, `SimpleCache`, Sonic, the silence skipper, chapters, Auto browse and resumption.
- Tap to resume, position saving and `QueueProjector`.

These stay **Kotlin**. The Dart UI talks to a Kotlin `PlaybackController` through **Pigeon** (commands) plus an `EventChannel` or Pigeon `@FlutterApi` (state stream).

What must be designed anew:

1. **Who owns the queue and positions when no Flutter UI exists** (Auto, media buttons, resumption, auto-advance). D38 and D41 assume Kotlin DAOs over Room. Options:
   - **(a) Kotlin opens the same SQLite file as drift.**
     - SQLite warns that two **copies** of the SQLite library in one process lose each other's POSIX locks: "A close() operation on one connection might unknowingly clear the locks on a different database connection, leading to database corruption" ([How to corrupt](https://www.sqlite.org/howtocorrupt.html), Verified).
     - That requires one shared library. `sqlite3` 3.6.0+ hooks can use `source: system` on Android, and Kotlin would then use `android.database.sqlite` against the same OS `libsqlite.so`. That gives up D9's "same current SQLite everywhere".
     - drift's stream invalidation does not see Kotlin's writes, so a notify channel is needed (Unverified design).
   - **(b) A single writer in Dart.**
     - The service calls into a headless `FlutterEngine` for queue and position writes.
     - Costs: engine start-up on every cold service start, and in Flutter debug mode a JIT start (Unverified latency; Android Auto's browse timeouts are a risk).
   - **(c) Split ownership.** Kotlin owns `queue_entry`, `play_session` and `episode_position` in its own SQLite file, and Dart reads them over Pigeon. This means two databases, which complicates backup, sync and invariants.
2. **Artwork for system surfaces** (`ArtworkProvider`, D42) needs file paths from the database: the same three options apply.

### 1.4 Native work needed on Android (either path)

| Need | `audio_service` + `just_audio` path | Custom Media3 plugin path |
|---|---|---|
| MediaLibraryService, Auto, resumption, notification | Partly provided (legacy compat APIs) | Kotlin (06 reused) |
| Skip silence, Sonic, boost | Provided on Android | Media3 built-in |
| D39 resolver, D40 cache, D50 URL TTL | Not available; custom Kotlin data source or Dart proxy | Kotlin (06 reused) |
| Embedded chapters | Dart parser or Kotlin (Media3 metadata) | Media3 metadata |
| Android 17 tap to resume, FGS edge cases | Fork or patch the plugin (Kotlin) | Kotlin (06 reused) |
| Queue and positions while UI is absent | Headless Dart engine | Database-ownership design (1.3) |

---

## 2. Desktop playback and OS media integration

### 2.1 Players

| Package | Version / date | Licence | Maintainers (12 months) | Back-end per OS | Fit against R4.8 (speed with pitch, skip silence, chapters, gapless, cache) |
|---|---|---|---|---|---|
| `media_kit` | 1.2.6 / **2025-12-13** (first 2023-02-27) | MIT (Dart), native libs separate | 52 commits by 16 authors (H. K. Saini 21); 1,831 stars; **354 open** | libmpv everywhere | Speed with pitch: mpv `scaletempo2` (Unverified that it is on by default). Gapless: mpv `--gapless-audio`. Chapters: from the demuxer (FFmpeg reads ID3 CHAP and MP4 chapters; Unverified via media_kit's API). Cache: mpv demuxer cache, not a persistent sparse cache. **Skip silence: not possible with the shipped Windows audio FFmpeg** (filters limited to `overlay` and `equalizer`) |
| `media_kit_libs_windows_audio` | 1.0.9 / **2023-09-27** | MIT wrapper | — | Downloads `libmpv-win32-audio-cmake` release `20251213`: FFmpeg `--disable-gpl --disable-nonfree --enable-version3` (**LGPL-3.0**), mpv `-Dgpl=false` at tag **v0.35.1**, statically linking openal-soft (LGPL), libass, libarchive, mbedtls and others into one `libmpv-2.dll` | LGPL-3.0-or-later DLL, dynamically loaded: acceptable now, with corresponding source and notices. mpv is **3+ years old** (Verified in the CMake files) |
| `media_kit_libs_macos_audio` | 1.1.4 / 2023-09-27 | MIT wrapper | `libmpv-darwin-build`: 16 commits by 6 authors | "default" flavour: FFmpeg `--enable-version3` without `--enable-gpl`, mpv `-Dgpl=false` | LGPL. Its repository LICENSE says "MIT" for the build scripts; that is not the binaries' licence |
| `media_kit_libs_linux` | 1.2.1 / 2025-03-24 | MIT wrapper | — | **Uses the system libmpv** ("System shared libraries from distribution specific user-installed packages are used by-default", `apt install libmpv-dev mpv`) | Debian trixie's mpv binaries "are distributed under the GPL-3+ license because they are linked to the GPL-3+ libsmbclient library" ([Debian copyright](https://sources.debian.org/data/main/m/mpv/0.40.0-3+deb13u1/debian/copyright), Verified). Linking Neutrodyne against that conflicts with the no-GPL rule; an LGPL libmpv for Linux must be built and shipped by us |
| `just_audio_media_kit` | 2.1.0 / 2025-04-13 | Unlicense | **1 commit** in 12 months | just_audio API over media_kit (Linux, Windows) | Inherits media_kit's limits plus just_audio's API |
| `just_audio_windows` | 0.2.3 / 2026-04-04 | MIT | 11 commits by 3 authors | WinRT `Windows.Media.Playback.MediaPlayer` | No request headers, no skip silence; pitch supported. Opus/Vorbis/WebM depend on Windows codec extensions (Unverified per edition) |
| `just_audio` (macOS) | 0.10.6 | MIT/Apache | as above | AVPlayer | No skip silence; no WebM/Ogg Opus in AVPlayer (as research3 found) |
| `audioplayers` | 6.8.1 / 2026-06-27 | MIT | 32 commits by 13 authors; 2,143 stars; 233 open | Windows Media Foundation, macOS AVPlayer, Linux **GStreamer** (system, LGPL) | Single-source player: no playlists or gapless, no skip silence; rate supported |

Sources: pub.dev API; repository clones of `media-kit/media-kit`, `media-kit/libmpv-win32-audio-cmake`, `media-kit/libmpv-darwin-build` and `media-kit/libmpv-android-audio-build`; [media_kit README](https://github.com/media-kit/media-kit).

**What LGPL changes** (relative to research3's permissive-only engine):

- **An own minimal FFmpeg-LGPL build becomes legitimate:** `libavformat`, `libavcodec` and `libswresample` as shared libraries, plus `silenceremove` or our own DSP. It can be driven from Dart through `ffigen` and build hooks.
- **The same goes for an own LGPL libmpv build.** mpv `-Dgpl=false` with a newer mpv than 0.35.1 and the filters we need, built for all three operating systems, including Linux, so Neutrodyne does not depend on the distro's GPL libmpv.
- **Remaining native work in both cases:**
  - **Skip silence with an exact clock:** mpv's lavfi `silenceremove` alters the timeline (research3's finding; Unverified for current mpv).
  - **D40-like persistent span cache semantics.**
  - **D39 per-connection URL resolution** for expired YouTube URLs: mpv `stream-open` hooks or a local proxy.
  - **Position-save cadence:** this part is Dart.
- **The research3 permissive engine stays possible in C/C++** (miniaudio + Sonic + per-OS AAC + Media3-derived demux logic). Its Media3-extractors-on-JVM shim, however, does not exist in a Dart/C++ world; demuxing would be FFmpeg-LGPL or a C library.

### 2.2 OS media integration and media keys

| OS | Package(s) | Licence / health | What is missing |
|---|---|---|---|
| macOS: Now Playing + MPRemoteCommandCenter (media keys, AirPods, Control Centre) | `audio_service` (macOS supported: background, headset clicks, play/pause/seek/rate, queue, notifications/control centre, art) | MIT, as in §1 | Lock screen is not listed for macOS; it is fine for a desktop app |
| Windows SMTC (media keys, overlay, Bluetooth) | `smtc_windows` 1.1.0 (2025-08-18; Rust + flutter_rust_bridge; repo `KRTirtho/frb_plugins`: **0 commits** in 12 months) · `audio_service_win` 0.0.3 (2026-03-29; 5 stars; 3 open) | MIT both | Either a stale Rust toolchain dependency or a very young plugin. An own C++/WinRT plugin (`ISystemMediaTransportControlsInterop::GetForWindow`) is about the same work research3 sized for the JVM |
| Linux MPRIS 2 | `audio_service_mpris` 0.2.1 (2026-03-15; 13 stars; 4 commits by 2 authors) · `anni_mpris_service` 0.1.0 (2022) · `mpris` (**GPL-3.0**, excluded) | MIT, **but depends on `dbus` 0.8.0 (Canonical, MPL-2.0)** | MPL-2.0 is file-level copyleft and is not on D3's allow-list for code (Q-F2). Alternative: an own C plugin on GDBus (GLib is LGPL, a system library) |
| Power (inhibit sleep while playing; pause on suspend) | none found as a maintained cross-platform package | — | Native code per OS (as in research3) |

`hotkey_manager` 0.2.3 (2024-05-18) offers global hotkeys, but the OS media sessions already route the hardware media keys.

---

## 3. Background downloads

| Package | Version / date | Licence | Health (12 months) | Android | Desktop | Notes for D46/D47 |
|---|---|---|---|---|---|---|
| `background_downloader` | **9.6.3 / 2026-09-25** (138 versions since 2022) | BSD-3 (pub also tags MIT) | 146 commits by 8 authors (**128 by one**); 238 stars; **2 open** | WorkManager worker; **UIDT** `JobService` with `setUserInitiated(true)` when `priority: 0` and `RUN_USER_INITIATED_JOBS` is declared (falls back to WorkManager when not granted); `TransferHint.userInitiated/largeFile`; pause/resume; notifications | In-process (Dart) on Windows and Linux; `URLSession` on macOS; "No setup is required for Windows or Linux" | Owns its **own task state** (Dart database plus `SharedPreferences` in Kotlin) and HTTP stack (`HttpURLConnection`). That is a second source of truth beside D46's `download` rows, reconciled through its callbacks and `start(autoCleanDatabase)`. File placement is controllable. Its Linux side pulls `connectivity_plus` → `nm` (**MPL-2.0**) → `dbus` (MPL-2.0) |
| `flutter_downloader` | 1.12.1 / 2026-08-22 | BSD-3 | **2 commits** in 12 months; 351 open | WorkManager | none (Android/iOS only) | Unsuitable for desktop |
| `workmanager` | 0.10.10 / 2026-09-07 | MIT | 147 commits by 3 authors (145 by one); 9 open | Periodic/one-off WorkManager work running a Dart callback in a **headless FlutterEngine** | none (Android/iOS/macOS) | For D25 refresh. Each run boots a Dart isolate (Unverified cost; slow in JIT debug mode) |

Sources: pub.dev API; `background_downloader` repository (README, CHANGELOG, `BDPlugin.kt`, `UIDTJobService.kt`), Verified.

**Android 14–16 fit:**

- **UIDT:** covered by `background_downloader` (Verified in source).
- **D47's API 26–33 path** (expedited work promoted to a `dataSync` FGS only when started while visible) and **the 8-minute soft deadlines of the N2 auto lane:** not modelled by the plugin. Its WorkManager lane resumes across "9-minute cycles" with `allowPause`.
- **Android 16 quota semantics:** not modelled by the plugin.
- **Exact D46/D47 behaviour** (DB as the only state, `.part` → verify → rename, `If-Range`, magic-byte check, wait reasons in R4.6) means either:
  - accepting the plugin's model and reconciling it; or
  - writing the plan's Kotlin runners (07) in the Android host and a Dart runner for desktop. That splits the engine into Kotlin and Dart implementations of one state machine.

**Desktop downloads:** a Dart `HttpClient` streaming to files, with `Range`/`If-Range`, runs in-process while the app runs, matching research3's in-process scheduler model. There is no OS-level background transfer on Windows or Linux.

---

## 4. Persistence and sync

### 4.1 drift on every platform

| Package | Version / date | Licence | Health |
|---|---|---|---|
| `drift` / `drift_dev` | 2.35.1 / 2026-09-30 (Flutter Favorite) | MIT | 3,288 stars; 208 open; 359 commits by 27 authors, **320 by Simon Binder** |
| `drift_flutter` | 0.3.1 / 2026-07-11 | MIT | same author |
| `sqlite3` | 3.7.0 / 2026-09-30 | MIT | 403 commits, **399 by one author**; 20 open |
| `sqlite3_flutter_libs` | 0.6.0+**eol** | MIT | replaced by `sqlite3`'s build hooks |

**What works:**

- Typed tables or `.drift` SQL files.
- Generated DAOs (`build_runner`).
- `watch()` streams invalidated by table.
- `customSelect(..., readsFrom: {...})`, which maps to D30's `FeedQueryBuilder` → `@RawQuery`.
- Isolates.
- **Migrations** with exported schema snapshots and generated step-by-step migrations and tests (`drift_dev make-migrations`; Unverified for the exact current command set).
- Bundled SQLite 3.53.x through build hooks:
  - Android `armv7a`, `aarch64`, `x86`, `x64`.
  - Linux prebuilt binaries need glibc ≥ 2.24.
  - Hooks can switch to the system SQLite per OS (`source: {android: system, default: sqlite3}`, 3.6.0).
  - Prebuilt binaries are **downloaded at build time**: a supply-chain step to pin.

**What needs work:**

- **No Paging 3 equivalent.** Use keyset or limit/offset windows with `watch()`, or `infinite_scroll_pagination` 5.1.1 (MIT, Flutter Favorite, last release 2025-08-28).
- **D16 invalidation hygiene still applies:** drift invalidates per table.
- **R2.9 query-time targets:** the same SQLite, so the plan's indexes carry over (Unverified until measured).

### 4.2 Sharing sync logic with a server

| Option | Shares code with the Flutter client? | Runtime shipped | Licence notes | Health |
|---|---|---|---|---|
| **Dart server on `shelf`** 1.4.2 (2024-06-21) + `shelf_router` 1.1.4 (2023-05-03) | Yes: one `sync_protocol` package (DTOs, HLC, OrderKey, field-merge rules, conformance vectors) used by app and server | Native executable from `dart compile exe` or `dart build cli` (with hooks, e.g. `sqlite3`); "includes a small Dart runtime"; cross-compiles to Linux targets from any host (x64/arm64 since Dart 3.8, arm/riscv64 since 3.9) | BSD-3 / Apache-2.0. No JVM, so a published OCI image needs only glibc (LGPL, now allowed) or a static base. Unverified: a fully static Dart executable | Dart-team owned; slow release cadence |
| Dart server on `dart_frog` 1.2.6 (2025-11-03) | Yes | same | MIT | 27 commits by 6 authors (21 by Felix Angelov) |
| Dart server on `relic` 1.2.0 (2026-03-11) | Yes | same | BSD-3 (Serverpod's HTTP layer) | young |
| `serverpod` 4.0.3 | Yes | same | **SSPL-1.0** for the `serverpod` package since 2023-10-18 ([licence](https://pub.dev/packages/serverpod/license), Verified) | **Excluded** (not open source by OSI; copyleft-like) |
| Kotlin/Ktor server (research3) | Only through a shared JSON schema or conformance vectors; no shared code with Dart | JVM: the OpenJDK GPL+CE question again for any published image | as research3 | as research3 |
| Go server (research3's alternative) | Same as Kotlin: vectors only | Static binary | permissive | as research3 |

Other points:

- **drift on the server:** drift runs on the Dart VM with `sqlite3`, and `drift_postgres` exists. The client and server *could* share table definitions; the server's schema is its own (Unverified fit).
- **Server-side helpers (pub.dev):**
  - `pointycastle` 4.0.0 (MIT, Bouncy Castle) for Argon2id (Unverified API);
  - `cryptography` 2.9.0 (Apache-2.0);
  - `shelf_web_socket` 3.0.0, which is not needed with research3's SSE design.

---

## 5. YouTube: yt-dlp in CPython

| Route | Android | Desktop | Licence | Health | Fit with D72–D77 |
|---|---|---|---|---|---|
| **Chaquopy in a Kotlin library module of the Flutter Android host** | The plan's `:youtube:ytdlp` as is: Chaquopy, `:ytx` process, AIDL `IYtxEngine`, OkHttp bridge (D74). Dart talks to a Kotlin client through Pigeon | n/a | Chaquopy (MIT); CPython (PSF) plus bundled libraries | Chaquopy 17.0.0 (plan S7); the Flutter plugin `chaquopy` 0.0.20 (2024-04-07) shows the approach works but is unmaintained | **Highest reuse**: D73 process isolation, D74 networking, D76 trust chain unchanged. Unverified: applying `com.chaquo.python` inside a Flutter-generated Android project under Flutter's AGP pin (S7 repeated) |
| **`serious_python` 5.0.0** (2026-09-26, Apache-2.0, flet.dev) | CPython 3.12.14 / 3.13.15 / **3.14.7**; Android ABIs `arm64-v8a`, `x86_64`, **`armeabi-v7a`**; runtime from `flet-dev/python-build` (MIT scripts; 3.13+ uses CPython's official `Android/android.py`) | macOS, Windows and Linux runtimes derived from python-build-standalone (`python-build` workflow) | Apache-2.0 plugin; runtime PSF plus bundled libraries | 325 stars; 13 open; 70 commits by 3 authors (57 by one); frequent releases (4.5–5.0 in Jul–Sep 2026) | Python runs **in the app process on a background thread**, talking through `dart_bridge` ports. That conflicts with D73 (`:ytx` isolation) and N5 ("the YouTube engine never runs in the main process"). Runtime engine updates (D76) are plain files on disk (Unverified with its app-packaging model) |
| **Child process with python-build-standalone on desktop** (research3 model) | n/a | `Process.start` from Dart with JSON-RPC over stdio, matching 04's A2 protocol | PSF plus permissive; `_dbm`/Tk removed as research3 measured (44 MB on disk, linux-x64) | — | Kill on idle, restart on crash; Dart's `dart:io` makes this easy |
| Pure-Dart YouTube extraction (e.g. `youtube_explode_dart`) | — | — | — | — | Out of scope: D72 mandates yt-dlp |

Sources: [serious_python README and CHANGELOG](https://github.com/flet-dev/serious-python), `python_versions.properties` (python-build 20260921), [flet-dev/python-build](https://github.com/flet-dev/python-build) (Verified); [chaquopy plugin](https://pub.dev/packages/chaquopy) (Verified).

**Notes:**

- yt-dlp-related Flutter plugins exist (`flutter_yt_dlp` 0.2.2, `flutter_ytdlp_plugin` 2.0.2). Both are Android-only, have under 10 likes, and their bundled components are Unverified. They are not suitable as dependencies.
- **Licences** are the same as the plan for Chaquopy. For python-build's runtimes, a `python-components.lock` review is needed: OpenSSL, libffi, bzip2, xz, sqlite, zstd, mpdecimal, expat; the GPL-sensitive readline and gdbm are absent upstream (Unverified per release).

---

## 6. UI: Material 3, adaptive layouts, covers, accessibility, desktop polish

### 6.1 Material 3 and layouts

- **Material 3 is the default design system.** Since 2026 it lives in `material_ui` (1.0 shipped with 3.47).
  - The SDK copy is scheduled for formal deprecation in the November stable release.
  - A one-time `dart fix` migration and the `MaterialUiCompatibilityBridge` handle third-party widgets that still import the SDK copy (Verified).
  - Expressive support is partial (StyleVariant, IconButton). PO-4 rejected Expressive anyway.
- **Dynamic colour:** `dynamic_color` 2.1.0 (Apache-2.0, material.io; depends on `material_ui`). `material_color_utilities` 0.13.1 provides artwork-seeded schemes (D57's MCU algorithm, same library family).
- **Adaptive layouts:**
  - `flutter_adaptive_scaffold` is **discontinued** (last release 2025-05-06).
  - The plan's compact/medium/expanded behaviour (`NavigationSuiteScaffold`, list-detail, ≥ 840 dp side player) is built by hand from `LayoutBuilder`/`MediaQuery` breakpoints, `NavigationBar`/`NavigationRail` and two-pane layouts (Unverified effort).
- **Navigation:** `go_router` 18.0.2 (BSD-3, flutter.dev) or `auto_route` 11.2.0 (MIT). The plan's one back stack per tab maps to `StatefulShellRoute` (Unverified detail).
- **State and DI:** `flutter_riverpod` 3.4.3 (MIT), `get_it` 9.3.0 (MIT) or `flutter_bloc` 9.1.1 (MIT). This replaces Hilt and ViewModels.

### 6.2 Cover grids and image caching

- **Image caching:** `cached_network_image` 4.0.4 (2026-09-30, MIT, Baseflow; 28 commits by 5 authors; 325 open) over `flutter_cache_manager` 3.4.5, or `extended_image` 10.1.0 (MIT).
- **Decode-size control:** `cacheWidth`/`ResizeImage` decode at tile size, which the 72/100/152 dp densities of R5.1 need.
- **D42's pinned `ArtworkStore`** becomes a Dart file store. On Android its files must also be readable by the Kotlin `ArtworkProvider`, a database-boundary question (§1.3).
- **Grid performance** (jank below 1 %, N5) is not measurable on the published debug build (weakness 1). Flutter's profile mode (`flutter run --profile`) is the measurement mode.
- Impeller is default on Android and now on desktop. Unverified: grid jank numbers for the 300-podcast scale.

### 6.3 Accessibility

| Platform | Status | Source |
|---|---|---|
| Android TalkBack | Supported. Semantics tree; `CustomSemanticsAction` for swipe/drag replacements (N4's custom actions); test guidelines in `flutter_test` | [Assistive technologies](https://docs.flutter.dev/ui/accessibility/assistive-technologies); `flutter_test/lib/src/accessibility.dart` (Verified) |
| Windows Narrator / NVDA / JAWS | Supported. The engine's `AXPlatformNodeWin` implements MSAA `IAccessible`, `IAccessibleEx` and UIA `IRawElementProviderFragment` | [Accessibility on Windows](https://flutter.googlesource.com/mirrors/flutter/+/HEAD/docs/platforms/desktop/windows/Accessibility-on-Windows.md) (Verified) |
| macOS VoiceOver | Listed as supported | docs (Verified) |
| Linux Orca | Listed. The engine uses **ATK** (GTK3); real apps show gaps (Ubuntu installer dropdowns unreadable by Orca: reported 2025-12-21, persisting 2026-01-17, LP #2138547) | readelf `NEEDED libatk-1.0.so.0` (Measured); [Ubuntu Discourse](https://discourse.ubuntu.com/t/accessibility-flutter-based-ubuntu-25-10-installer-dropdown-menus-are-not-readable-by-orca-manual-disk-partitioning/73970) (Verified) |

Android-specific items:

- **Per-app language (N10):** needs a hand-written `locales_config.xml`, because Flutter's ARB-based l10n generates no Android `res/values-xx`, plus a small `LocaleManager` bridge for an in-app picker (Unverified details).
- **Weblate supports ARB.** The plan's Android-XML string pipeline changes format.

### 6.4 Desktop polish

| Need | Package / API | Licence / health | Gaps |
|---|---|---|---|
| Window size, position, close-to-tray, single instance | `window_manager` 0.5.2 (2026-07-04) | MIT; **1 author** in 12 months (8 commits); issue tracker shows 0 open | Single instance and argument forwarding are own code (Unverified package options) |
| Tray icon and menu | `tray_manager` 0.7.0 (2026-09-19), now on `nativeapi` 0.4.1 (new C++ library, same author) | MIT; 24 commits by 4 authors | On GNOME it needs the AppIndicator extension (StatusNotifierItem over D-Bus) |
| Menus | `PlatformMenuBar` (native menu bar on macOS); Material `MenuBar`/`MenuAnchor` in-window on Windows and Linux | SDK | No native Windows/Linux menu bar |
| Multiple windows, popups | Experimental windowing API (3.47 adds popups on Windows/Linux, sized-to-content, `windowHandle`) | SDK | Experimental |
| Keyboard shortcuts | `Shortcuts`/`Actions`/`CallbackShortcuts` (SDK); `hotkey_manager` 0.2.3 for global keys | SDK / MIT | — |
| Notifications | `flutter_local_notifications` 22.3.1 (Windows, Linux, macOS, Android) | BSD-3; Linux part depends on `dbus` (**MPL-2.0**) | Q-F2 |
| Start at login, URL schemes | `launch_at_startup` 0.5.1 (2025-04-01); `protocol_handler` 0.2.0 (2024-01-28; **no Linux**) | MIT | Stale; own code likely |
| Drag-and-drop of OPML files | `desktop_drop` 0.8.4 (Apache-2.0) or `super_drag_and_drop` 0.9.1 (MIT) | — | — |
| Native look | `fluent_ui`, `macos_ui`; **`yaru` is MPL-2.0** | — | Not needed for a Material app |

---

## 7. Distribution, sizes, start-up and licences

### 7.1 Formats (unsigned, GitHub Releases only)

| Target | Build | Formats available | Unsigned implications |
|---|---|---|---|
| Android | `flutter build apk --split-per-abi` (`arm64-v8a`, `armeabi-v7a`, `x86_64`) | Per-ABI APKs (D77 shape) | Same as the plan (committed key, D61). **Mode question:** a debuggable APK is Flutter debug mode (§7.3) |
| Windows | `flutter build windows` (Windows host only; arm64 needs an arm64 host) | Folder or ZIP (exe + `flutter_windows.dll` + `data/` + **`msvcp140.dll`, `vcruntime140.dll`, `vcruntime140_1.dll`**, Microsoft redistributables); EXE installer (fastforge uses **Inno Setup**); MSIX | **MSIX** needs a `.pfx`; self-hosted needs "a certificate signed by a Certificate Authority known to Windows". A self-signed certificate works only after users import it into Trusted Root, so MSIX is impractical. ZIP or Inno EXE then hit SmartScreen "Run anyway"; Smart App Control blocks them (research3) |
| macOS | `flutter build macos` (macOS host) | `.app` in a DMG (fastforge `dmg`, or `create-dmg`/`hdiutil`), PKG | Ad-hoc signed (arm64 needs at least ad hoc; Xcode's "Sign to Run Locally"); Gatekeeper "Open Anyway" flow (research3); no notarisation |
| Linux | `flutter build linux` (x64 host for x64; arm64 needs an arm64 host) | `bundle/` tar.gz; DEB, RPM, AppImage, Pacman via fastforge; Snap Store and Flathub are stores, so excluded | DEB/RPM declare GTK3 dependencies (`libgtk-3-0` etc.). AppImage's libfuse (LGPL-2.1) is now acceptable |

Sources: [Windows building](https://docs.flutter.dev/platform-integration/windows/building), [Linux building](https://docs.flutter.dev/platform-integration/linux/building), `flutter_tools` 3.47.6 (`build_windows.dart`, `build_linux.dart`, `build_macos.dart`: "only supported on … hosts"; "Cross-build from Linux x64 host to Linux arm64 target is not currently supported"), [fastforge](https://github.com/fastforgedev/fastforge) (Verified).

**Tools:**

- `fastforge` 0.6.12 (MIT; 158 commits by 7 authors, **146 by one**; successor of the discontinued `flutter_distributor`).
- `msix` 3.18.0 (MIT).
- Inno Setup's licence is a custom permissive licence: not on D3's list (Q-F3).

### 7.2 Engine sizes (Measured, engine `692136cb…`, Flutter 3.47.6)

| Artifact | Size | Note |
|---|---|---|
| Android `libflutter.so` release, stripped | arm64 **11.7 MB** (5.5 MB gzip -9); armeabi-v7a 8.6 MB (4.7); x86_64 13.1 MB (5.6) | `llvm-strip --strip-unneeded` of the Maven artifacts. ELF LOAD alignment 0x10000, so **16 KB-page compatible** (N7) |
| Android `libflutter.so` **debug**, stripped | arm64 **38.8 MB** (14.0 MB gzip) **plus `libVkLayer_khronos_validation.so` 15.2 MB** (4.2 MB) in the same artifact's `lib/arm64-v8a/` | The engine build packs the validation layer into the arm64 debug artifact (`BUILD.gn`). Unverified that AGP packages it into the APK (likely, as a dependency `jniLib`) |
| Linux `libflutter_linux_gtk.so` (release) | **17.2 MB** (6.8 MB gzip) | Links GTK3/GDK3, Pango, ATK, Cairo, GIO/GObject/GLib, epoxy, fontconfig |
| Windows `flutter_windows.dll` (release) | **21.3 MB** | plus VC++ redistributable DLLs (≈ 1 MB; Unverified) |
| macOS `FlutterMacOS.framework` (release) | **30.1 MB** universal (12.8 MB zipped) | x86_64 + arm64 |
| `icudtl.dat` | 0.86 MB | ICU data |

**Estimated installed app sizes:**

- Engine plus Dart AOT `libapp.so`/`App.framework`: Unverified 8–15 MB for an app of Neutrodyne's size.
- Plus plugins, an LGPL libmpv (Unverified 15–25 MB) and trimmed CPython (44 MB, research3).

| Target | Installed (Unverified) |
|---|---|
| Windows | ≈ 90–110 MB |
| Linux | ≈ 85–105 MB |
| macOS universal | ≈ 120–150 MB |

Compressed downloads are about 40–50 % of these figures (Unverified).

**Android APKs per ABI** (Unverified until built):

- **Release mode:** engine 5.5 MB compressed + AOT + Media3/AndroidX dex + Chaquopy/Python (plan estimate ≈ 20 MB+) ≈ **35–50 MB**.
- **Debug mode:** engine 14 MB + validation layer 4 MB compressed + JIT kernel blob and snapshots (typically tens of MB) + Python ≈ **60–90 MB**, likely over PB12 (< 60 MB, 64-bit) and PB13 (< 50 MB, armeabi-v7a).

### 7.3 Start-up times

Not measured in this session; all figures Unverified.

- **Release-mode Flutter:** cold start on a mid-range Android phone is typically in the same range as a Compose app. Desktop AOT apps show a first frame in well under a second.
- **Flutter debug mode:** JIT-compiles on device. The docs warn "Application performance can be janky in debug mode", and debug builds "are NOT representative of production app size" ([build modes](https://docs.flutter.dev/testing/build-modes), [app size](https://docs.flutter.dev/perf/app-size), Verified).
- **Measurement plan:**
  - `flutter run --profile --trace-startup` per platform.
  - Macrobenchmark on an Android release-mode build: the plan's `benchmark` type maps to Flutter **profile** or **release** mode.

### 7.4 Licences of what would ship

| Component | Licence | Status against D3 |
|---|---|---|
| Flutter framework, engine, Dart VM/runtime, Skia, Impeller | BSD-3-Clause | allowed |
| Engine third parties (74 groups in `sky_engine/LICENSE`): abseil, BoringSSL, brotli, double-conversion, expat, FreeType, HarfBuzz, ICU, libc++/libc++abi (Apache-2.0 WITH LLVM-exception), libjpeg-turbo, libpng, libwebp, SQLite (public domain), Vulkan headers and loader, zlib, wuffs and others | Mostly BSD, MIT, Apache-2.0, zlib. Also **FreeType Project License (FTL)** (FreeType is dual FTL/GPL; the LICENSE carries FTL), **Unicode** (ICU) and BoringSSL's OpenSSL/ISC texts | **No GPL/LGPL** (Measured: no "GNU General Public License" text). FTL, Unicode and OpenSSL-style licences are permissive but **not in D3's list** (Q-F3) |
| Linux system libraries (GTK3, GLib, Pango, ATK, Cairo) | LGPL-2.1+ / MPL-1.1 (Cairo dual) | Not shipped in DEB/RPM/tar.gz; would be inside an AppImage or Flatpak if bundled. LGPL is allowed now |
| Windows VC++ runtime DLLs | Microsoft redistributable (proprietary) | Same as research3's JVM finding |
| libmpv / FFmpeg (if used) | LGPL-2.1+/LGPL-3.0 (own or media_kit builds) | Allowed with source offer and notices; the Linux distro libmpv is GPL-3+ in Debian (excluded) |
| CPython plus bundled libraries | PSF-2.0 plus permissive (and Sleepycat `_dbm` to remove) | As research3 / plan |
| Pub dependencies | Check with `very_good packages check licenses --allowed=…` (very_good_cli 1.5.0, MIT; transitive; pub only; SPDX matching) | **MPL-2.0 packages appear transitively on Linux**: `dbus`, `nm` (via `connectivity_plus`), `gsettings`, `upower` |
| Avoid | `mpris` (GPL-3.0), `android_auto` (GPL-3.0), `serverpod` (SSPL-1.0), `yaru` (MPL-2.0) | — |

---

## 8. Testing, CI, tooling, risks and carry-over

### 8.1 Testing and tooling

- **Unit and widget tests:** `flutter test` runs headless with no device or display. It has built-in accessibility guidelines.
- **Golden tests:** `matchesGoldenFile`, or `alchemist` 0.14.0 (MIT), whose CI mode uses a font that does not depend on the OS. `golden_toolkit` is discontinued.
- **Integration tests:** the SDK's `integration_test` (the old pub package is discontinued) on Android emulators and desktop hosts. Linux runs need a display (Xvfb; Unverified).
- **Patrol** 4.10.0 (Apache-2.0, LeanCode) drives **native** UI (permission dialogs, notifications) on Android, iOS, macOS and web; **not Windows or Linux**.
- **Native code** (Kotlin playback, downloads, `:ytx`) keeps Kotlin unit and instrumented tests (Robolectric, GMD) in the Android host project.
- **Lints:** `flutter_lints` 6.0.0, `very_good_analysis` 11.0.0. `custom_lint` 0.8.1 for project rules (D12's layering checks become import-lint rules; Unverified tooling).
- **Code generation:** `build_runner` 2.16.1 is needed by drift, riverpod and freezed or json_serializable; Dart macros were cancelled in Jan 2025.
- **Licence policy:**
  - `very_good packages check licenses` for pub packages.
  - Gradle Licensee still applies to the Android host's Maven dependencies (Media3, Chaquopy, AndroidX).
  - CMake/CocoaPods/SwiftPM native downloads by plugins (media_kit libs, sqlite3 prebuilts, python-build runtimes) need their own lockfile and allow-list.
- **CI matrix:** Linux x64 (tests, Android, Linux x64 bundle); Linux arm64 runner; Windows x64 and Windows arm64 runners; macOS runner (universal app). Five to six runner types per release, about the same as research3's JVM route.

### 8.2 Risk register (Flutter route)

| ID | Risk | Likelihood / impact | Mitigation available |
|---|---|---|---|
| F1 | PO-35 debuggable APKs force Flutter debug mode (JIT, assertions, oversized) | High / high | Owner decision Q-F1: publish Flutter release-mode, non-debuggable APKs signed with the committed key; or patch Flutter's Gradle plugin (fragile) |
| F2 | The Android audio stack (`audio_service`, `just_audio`) lags platform APIs (legacy media compat, Media3 1.4.1) and depends on one maintainer | High / high | Custom Kotlin Media3 plugin (reuses 06); forking is possible (MIT) |
| F3 | Dart and Kotlin both touch the database on Android: two SQLite copies corrupt locks, and stream invalidation misses cross-runtime writes | Medium / critical (N1) | One SQLite library (system SQLite on Android) and a single-writer design, or headless-engine writes; spike before M1 |
| F4 | Desktop audio: media_kit stale (Windows mpv v0.35.1), Linux uses the distro GPL libmpv; no desktop skip silence | High / high | Own LGPL FFmpeg or libmpv builds per OS, and native DSP; or research3's C engine via FFI |
| F5 | Windows SMTC and Linux MPRIS plugins are immature or stale; MPRIS depends on MPL-2.0 `dbus` | High / medium | Own C++/WinRT and GDBus plugins |
| F6 | Linux screen-reader support incomplete (ATK) | High / medium (N4 on Linux) | Document as a known limitation; test with Orca per release |
| F7 | 2026 churn: Material decoupling (formal deprecation Nov 2026), Impeller just default on desktop, experimental multi-window | Medium / medium | Start on `material_ui` directly; pin Flutter per milestone; keep desktop single-window |
| F8 | Community plugin bus factor (drift/sqlite3, background_downloader, workmanager, LeanFlutter desktop plugins, serious_python) | Medium / high | Vendor or fork policy; keep native fallbacks for critical paths (downloads, playback, YouTube) |
| F9 | Background Dart work on Android needs headless engines (workmanager, audio_service); start-up cost and memory, worse in debug mode | Medium / medium | Keep scheduled refresh and downloads in Kotlin where latency matters; measure |
| F10 | serious_python runs Python in the main process (violates D73/N5) | High if chosen / medium | Use Chaquopy `:ytx` in Kotlin (plan S7) on Android; child process on desktop |
| F11 | Licence allow-list gaps: MPL-2.0 (Linux Dart packages), FTL/Unicode/OpenSSL texts (engine), Inno Setup, Microsoft VC++ redistributables | Certain / low–medium | Owner decisions Q-F2 and Q-F3 |
| F12 | No cross-compilation for desktop builds; arm64 builds need arm64 runners | Certain / low | GitHub-hosted arm64 runners for Windows and Linux (as research3) |
| F13 | Two languages on Android (Dart + Kotlin), plus C++/Swift/ObjC on desktop for media sessions and power handling | Certain / medium | Accept; keep native surfaces small behind Pigeon |
| F14 | Platform currency: Flutter's CI tests Android only to API 36 and Ubuntu to 24.04; the plan targets API 37 and Android 17 behaviours | Medium / medium | Device-matrix tests (M5-style) on API 37; track Flutter issues |
| F15 | Build-time binary downloads (sqlite3 prebuilts, media_kit libs, python-build runtimes, Flutter engine artifacts) | Medium / medium | Pin hashes, mirror into release provenance, or build from source |

### 8.3 What carries over from the existing plan

| Document | Carries over | Must be rewritten or redesigned |
|---|---|---|
| PLAN.md §1–2 (vision, R1–R6, N1–N12), §4 owner decisions | Requirements and acceptance criteria are platform-neutral | N4's ATF check → `flutter_test` guidelines; N5's `benchmark` build → Flutter profile/release; N7/N8 enforcement tooling; D2/D61 under Q-F1 |
| D-decisions | Product and data decisions (D1, D15–D24, D29–D36, D38 semantics, D41 rules, D44–D45, D49–D53, D65–D80) | Toolchain and stack decisions (D4, D6–D10, D12–D14, D58–D60) are replaced; D11 parser re-implemented in Dart; D37–D43 depend on the §1 choice |
| 01 Foundation | Licensing policy, IDs, process model for `:ytx`, S7 | Module graph, Gradle conventions, Hilt, Compose, threading and start-up sections: rewritten for a Dart workspace plus an Android host |
| 02 Data model | **Schema (tables, keys, indexes, triggers), identity ladder, retention, invalidation rules**: port to drift `.drift` files near verbatim | Room annotations and DAOs, Paging 3, `MigrationTestHelper` → drift equivalents; Kotlin/Dart database boundary (§1.3) |
| 03 Feeds and discovery | Algorithms (identity, dates, sortDate, redirects, backoff, paged feeds), golden corpus as data | Parser on `xml` 7.1.0 (MIT, event/streaming API; Unverified relaxed-mode parity) and sanitiser on `html` 0.15.7 (MIT; pub shows "unknown" but the text is MIT); HTTP on `dart:io`/`http` (no OkHttp interceptors) |
| 04 YouTube | **Python shim, D76 trust chain, manifest, canary workflow, `:ytx` + AIDL + OkHttp bridge on Android** (Kotlin in the host) | Dart client over Pigeon; desktop host process; Layer A (Atom feeds, channel resolution) in Dart |
| 05 Groups, OPML, backup | Formats (OPML, backup ZIP), merge rules, import cascade | Code in Dart; Auto Backup stays Android XML; SAF access through `file_picker` and own Kotlin |
| 06 Playback | With a custom Media3 plugin: most of the Kotlin design | Database ownership; with audio_service/just_audio, large parts unreachable (cache, resolver, Tap to resume); desktop is new work (§2) |
| 07 Downloads | State machine, policies, file layout, cleanup rules | Runners: background_downloader (different state model) or the plan's Kotlin runners plus a new Dart desktop runner |
| 08 UI/UX | Information architecture, screens, interactions, colour rules, accessibility intent | All Compose code, component wrappers and Nav3 mechanics → Flutter widgets and router; desktop UX additions |
| 09 Quality and release | Release policy, SHA256SUMS/attestations, update manifest, engine canary | CI jobs, Licensee scope, Roborazzi/GMD, size budgets (debug-mode problem), desktop packaging |

**Effort, as a relative and Unverified planning estimate:**

- Implementation-level design (01, 06–09 mechanics, half of 02–05) must be redone.
- The Android-native Kotlin surfaces (Media3 service, UIDT, `:ytx`, artwork provider) remain about as large as in the plan.
- Desktop adds native plugins (SMTC, MPRIS or GDBus, power, an LGPL media back-end) comparable to research3's desktop work.
- The JVM runtime packaging work disappears.

---

## Questions for the owner raised by this assessment

| ID | Question | Why it matters | Default if unanswered (no preference implied) |
|---|---|---|---|
| Q-F1 | Does PO-35 ("Just build debug builds") mean **debuggable** APKs, or only "no private signing key"? | Flutter maps debuggable build types to JIT debug mode; release mode is non-debuggable | Treat it as a signing-key decision; ask before shipping |
| Q-F2 | Is MPL-2.0 acceptable for **unmodified code** packages (Canonical's `dbus`, `nm`, `gsettings`) used on Linux? | MPRIS, notifications and connectivity on Linux pull them in transitively | Exclude, and replace with own GDBus code |
| Q-F3 | Add FTL, Unicode, OpenSSL/ISC (BoringSSL), the Inno Setup licence and the Microsoft VC++ redistributable to the D3 allow-list for shipped desktop artefacts? | They are in every Flutter build or installer | They are permissive; list them explicitly |
| Q-F4 | On Linux, may Neutrodyne depend on the distribution's libmpv (GPL-3+ on Debian), or must we ship our own LGPL build? | The no-GPL rule versus dynamic linking to a system library | Ship our own LGPL build |

---

## Unverified items (consolidated)

- Flutter's Gradle plugin with AGP 9.4.1 / Gradle 9.7.1 (Flutter 3.47 documents AGP 9.1.0 / Gradle 9.3.1).
- Chaquopy applied inside a Flutter Android host under Flutter's AGP pin (S7 to repeat).
- Android Auto cold-start latency with a headless Flutter engine, and audio_service resumption on Android 15–17.
- Whether the arm64 debug Vulkan validation layer lands in APKs; the actual debug and release APK sizes per ABI.
- Desktop and Android start-up times; cover-grid jank at N5 scale.
- media_kit's mpv `scaletempo2` default; chapter exposure; size of an own LGPL libmpv per OS.
- just_audio_windows codec coverage per Windows edition; AVPlayer pitch algorithm in just_audio.
- drift's current migration-tooling commands; `xml` package relaxed-mode and entity handling parity with D11.
- Static Dart server executables; python-build runtime contents per release.
- Linux integration tests under Xvfb; `window_manager` single-instance support.

---

## Sources

**Flutter and Dart:**

- [Flutter release metadata (releases_linux.json)](https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json)
- [What's new in Flutter 3.47](https://flutter.dev/blog/whats-new-in-flutter-3-47)
- [Supported deployment platforms](https://docs.flutter.dev/reference/supported-platforms)
- [Build modes](https://docs.flutter.dev/testing/build-modes) · [App size](https://docs.flutter.dev/perf/app-size)
- [Windows building and packaging](https://docs.flutter.dev/platform-integration/windows/building) · [Linux building](https://docs.flutter.dev/platform-integration/linux/building) · [Desktop support](https://docs.flutter.dev/platform-integration/desktop)
- [Assistive technologies](https://docs.flutter.dev/ui/accessibility/assistive-technologies) · [Accessibility on Windows (engine doc)](https://flutter.googlesource.com/mirrors/flutter/+/HEAD/docs/platforms/desktop/windows/Accessibility-on-Windows.md) · [Ubuntu installer Orca issue](https://discourse.ubuntu.com/t/accessibility-flutter-based-ubuntu-25-10-installer-dropdown-menus-are-not-readable-by-orca-manual-disk-partitioning/73970)
- Flutter 3.47.6 source (`git clone --depth 1 --branch 3.47.6 https://github.com/flutter/flutter`): `packages/flutter_tools/gradle/src/main/kotlin/FlutterPluginUtils.kt` (`buildModeFor`), `FlutterExtension.kt`, `lib/src/commands/build_{windows,linux,macos}.dart`, `engine/src/flutter/shell/platform/android/BUILD.gn`, `packages/flutter_test/lib/src/accessibility.dart`
- Engine artifacts (Measured): `https://storage.googleapis.com/flutter_infra_release/flutter/692136cb6582dbfc5af3fb33c2515a069f2f66d0/{sky_engine.zip, linux-x64-release/linux-x64-flutter-gtk.zip, windows-x64-release/windows-x64-flutter.zip, darwin-x64-release/FlutterMacOS.framework.zip, linux-x64/artifacts.zip}`; `https://storage.googleapis.com/download.flutter.io/io/flutter/{arm64_v8a,armeabi_v7a,x86_64}_release/…` and `arm64_v8a_debug/…`
- [dart compile](https://dart.dev/tools/dart-compile) · [dart build](https://dart.dev/tools/dart-build) · [Build hooks](https://dart.dev/tools/hooks) · [Dart macros cancelled (summary)](https://occasionalflutter.substack.com/p/dart-macros-are-not-coming)

**Packages and licences:**

- pub.dev API for every package named above (`https://pub.dev/api/packages/<name>`, `/score`, `/publisher`), and [serverpod licence (SSPL)](https://pub.dev/packages/serverpod/license)
- [material_ui](https://pub.dev/packages/material_ui) and [changelog](https://pub.dev/packages/material_ui/changelog)
- Repositories (blobless clones, 12-month `git log`):
  - audio: [audio_service](https://github.com/ryanheise/audio_service), [just_audio](https://github.com/ryanheise/just_audio), [audio_session](https://github.com/ryanheise/audio_session), [audioplayers](https://github.com/bluefireteam/audioplayers), [just_audio_windows](https://github.com/bdlukaa/just_audio_windows), [just_audio_media_kit](https://github.com/Pato05/just_audio_media_kit)
  - media_kit: [media-kit](https://github.com/media-kit/media-kit), [libmpv-win32-audio-cmake](https://github.com/media-kit/libmpv-win32-audio-cmake), [libmpv-win32-audio-build](https://github.com/media-kit/libmpv-win32-audio-build), [libmpv-darwin-build](https://github.com/media-kit/libmpv-darwin-build), [libmpv-android-audio-build](https://github.com/media-kit/libmpv-android-audio-build)
  - OS integration: [audio-service-mpris](https://github.com/bdrazhzhov/audio-service-mpris), [frb_plugins/smtc_windows](https://github.com/KRTirtho/frb_plugins), [dbus.dart](https://github.com/canonical/dbus.dart)
  - downloads and storage: [background_downloader](https://github.com/781flyingdutchman/background_downloader), [flutter_downloader](https://github.com/fluttercommunity/flutter_downloader), [flutter_workmanager](https://github.com/fluttercommunity/flutter_workmanager), [drift](https://github.com/simolus3/drift), [sqlite3.dart](https://github.com/simolus3/sqlite3.dart)
  - Python: [serious-python](https://github.com/flet-dev/serious-python), [python-build](https://github.com/flet-dev/python-build)
  - desktop and packaging: [window_manager](https://github.com/leanflutter/window_manager), [tray_manager](https://github.com/leanflutter/tray_manager), [fastforge](https://github.com/fastforgedev/fastforge), [msix](https://github.com/YehudaKremer/msix)
  - server: [serverpod](https://github.com/serverpod/serverpod), [dart_frog](https://github.com/dart-frog-dev/dart_frog)
  - images: [flutter_cached_network_image](https://github.com/Baseflow/flutter_cached_network_image)
- Issues: [audio_service #942](https://github.com/ryanheise/audio_service/issues/942), [#996](https://github.com/ryanheise/audio_service/issues/996), [#1137](https://github.com/ryanheise/audio_service/issues/1137)
- [Debian mpv 0.40.0 copyright (GPL-3+ binaries)](https://sources.debian.org/data/main/m/mpv/0.40.0-3+deb13u1/debian/copyright) · [Debian sources API: mpv](https://sources.debian.org/api/src/mpv/)
- [SQLite: How to corrupt (multiple copies of SQLite)](https://www.sqlite.org/howtocorrupt.html)
- [Very Good CLI license checker](https://cli.vgv.dev/docs/commands/check_licenses)
- Sibling notes: `research3/kmp-architecture.md`, `research3/desktop-playback.md`, `research3/desktop-distribution.md`, `research3/sync-server.md`; plan: `/home/user/Neutrodyne/docs/PLAN.md` (D2, D3, D37–D47, D61, D72–D77, PB12/PB13), `docs/design/01-foundation.md` (S7), `06-playback.md` (Android 17 rules)
