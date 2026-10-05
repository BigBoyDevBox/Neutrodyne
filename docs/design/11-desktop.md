# 11 — Desktop app

> Status: Draft v1, 2026-10-05 · scope revision 2026-10-05 (S0–S13): new document — the desktop app for Windows, macOS and Linux, built from the shared Kotlin Multiplatform code, with its own shell, in-process background runner, OS media integration, FFmpeg-based audio engine, CPython-hosted YouTube engine and jpackage installers under the runtime exception · Implements: R8.1–R8.11, R6.5, R6.6 (desktop behaviour), R1.1 (desktop file opening), R3.5, R3.6, R3.8, R3.9 (desktop host), R4.1, R4.2, R4.3, R4.8 (desktop), R5.2, R5.3, R5.7 (desktop surfaces) / N1, N2, N3, N4, N5, N6, N7, N8, N9, N11, N12 (desktop parts) · Milestones: M0b, MD0, M1a, M3, M6 (M6a, M6b), MD1 (MD1a, MD1b), MD2, MD3, M10, MD4, M11a, MD5, M11b; desktop parts of MS2 and MS3 · Honours: D2, D3, D4, D14, D25, D38–D45, D47–D49, D52, D57, D61–D64, D72–D84, D91–D93, D97; PO-2, PO-5, PO-10, PO-19, PO-27, PO-39, PO-40, PO-42, PO-43, PO-44 · Owns: D85–D90, R8, R6.5; spikes S13 and S18 (= MD0); the measurement procedure of budgets PB24–PB29 (09 owns the table); the modules `:desktopApp`, `:playback:engine`, `:playback:native`, `:playback:desktop`, `:desktop:system`, `:youtube:ytdlp-desktop`; the `AppDirs` table, the frozen desktop identifiers (including the MSI `upgradeUuid`), the desktop install and update guidance text, `DesktopJobRunner` and its lane contract, the desktop crash files

Contents: [Scope](#scope) · [Platform matrix](#platform-matrix) · [Desktop shell](#desktop-shell) ([Window and tray behaviour](#window-and-tray-behaviour)) · [Background work](#background-work) · [OS integration](#os-integration) · [Desktop playback engine](#desktop-playback-engine) · [Desktop downloads and storage](#desktop-downloads-and-storage) · [Desktop YouTube engine host](#desktop-youtube-engine-host) · [Packaging and the runtime exception](#packaging-and-the-runtime-exception) · [Install and update](#install-and-update) · [Desktop UX](#desktop-ux) · [Accessibility](#accessibility) · [Desktop diagnostics and crash files](#desktop-diagnostics-and-crash-files) · [Testing](#testing) · [Delivery by milestone](#delivery-by-milestone) · [New names introduced here](#new-names-introduced-here) · [Open questions](#open-questions) · [Sources](#sources)

---

## Scope

Serves R8, R6.5, R6.6 and the desktop halves of R1, R3, R4 and R5. Delivered across M0b (shell and packaging), MD0 (engine spike), M1a/M3/M6 (shared features on the desktop), MD1–MD5 (playback, OS integration, YouTube, UX, release) and M11b; see [Delivery by milestone](#delivery-by-milestone).

The desktop app is the same Kotlin Multiplatform product as the Android app ([D81](../PLAN.md#3-key-decisions)): every screen, ViewModel, repository, rule, the database schema and the sync client are `commonMain` code shared with Android ([01 Source sets and JVM islands](01-foundation.md#source-sets-and-jvm-islands)). This document owns only what is desktop-specific: a JVM process with one Compose window ([D85](../PLAN.md#3-key-decisions)); an in-process scheduler instead of WorkManager; our own audio engine instead of Media3 ([D86](../PLAN.md#3-key-decisions)); the OS media sessions of Windows, macOS and Linux ([D87](../PLAN.md#3-key-decisions)); the YouTube engine in a CPython child process instead of Chaquopy's `:ytx` ([D90](../PLAN.md#3-key-decisions)); per-OS installers that bundle an unmodified OpenJDK runtime under the runtime exception ([D88](../PLAN.md#3-key-decisions), [D89](../PLAN.md#3-key-decisions), [D3](../PLAN.md#3-key-decisions)). Everything a user can do on Android in R1–R5 they can do on the desktop, except what [Behaviour differences from Android](#behaviour-differences-from-android) lists.

### Responsibilities and boundaries

| This document owns | Owned elsewhere (link, do not restate) |
|---|---|
| `main()`, start-up and shutdown order, `DesktopAppGraph` contents specific to the desktop, single instance, `AppDirs`, OS link and file registration, smoke mode | Shared start-up bands and `AppInitializer` — [01 Application start-up](01-foundation.md#application-start-up); Metro graphs — [01 Dependency injection](01-foundation.md#dependency-injection); route table — [01 Intent routing](01-foundation.md#intent-routing) |
| Window, close behaviour, tray, start at login, window state | Screens, navigation suite, adaptive layouts — [08 Navigation](08-ui-ux.md#navigation), [08 Adaptive layouts](08-ui-ux.md#adaptive-layouts) |
| `DesktopJobRunner`, the `JobLane` contract, wake and restart catch-up | What each lane does — [03 Desktop refresh](03-feeds-and-discovery.md#desktop-refresh), [07 Desktop runners](07-downloads.md#desktop-runners), [08 Artwork pipeline](08-ui-ux.md#artwork-pipeline), [09 Update check](09-quality-and-release.md#update-check), [04 Engine updates](04-youtube.md#engine-updates), [10 Client sync engine](10-sync.md#client-sync-engine), [02 Retention and maintenance](02-data-model.md#retention-and-maintenance) |
| SMTC, Now Playing, MPRIS, power, audio-route monitoring, `DesktopNotifier`, `ndmedia`'s OS shims | Artwork files the sessions read — [08 Artwork pipeline](08-ui-ux.md#artwork-pipeline); notification texts — [03 New-episode notifications](03-feeds-and-discovery.md#new-episode-notifications), [07 Progress and notifications](07-downloads.md#progress-and-notifications) |
| `AudioEngine`, sources, `SpanCache`, the FFmpeg build and FFM bindings, DSP ports, output and clock, `DesktopPlaybackController` | Queue window, position, played, start, sleep-timer and chapter rules — [06 Shared playback core](06-playback.md#shared-playback-core); cache rules — [06 Streaming cache](06-playback.md#streaming-cache) ([D40](../PLAN.md#3-key-decisions)); player UI — [08 Player sheet](08-ui-ux.md#player-sheet) |
| Default and chosen download folders, moves on the desktop, Windows path rules beyond 07's names, "Show in folder", desktop disk-full and removable-drive handling | Transfer core, state machine, naming algorithm, move algorithm — [07 Transfer core](07-downloads.md#transfer-core), [07 State machine](07-downloads.md#state-machine), [07 Storage layout](07-downloads.md#storage-layout), [07 Moving between roots](07-downloads.md#moving-between-roots) |
| CPython selection, trim, child process, stdio framing, desktop engine paths, JS bridge over stdio | Engine methods, shim, trust chain, update policy, capability rules — [04 Shared engine module](04-youtube.md#shared-engine-module), [04 Engine updates](04-youtube.md#engine-updates), [04 Capability matrix](04-youtube.md#capability-matrix) |
| `nativeDistributions` per target, jlink modules, JVM options, AOT cache, resources layout, macOS ad-hoc signing and the 0.x ZIP, MSI `upgradeUuid`, runtime-exception and FFmpeg-LGPL obligations, `check-desktop-image.sh` rules | Release workflow and jobs — [09 release.yml](09-quality-and-release.md#releaseyml); licence policy, locks and allow-lists — [01 Licensing and dependency policy](01-foundation.md#licensing-and-dependency-policy), [01 Python and native components](01-foundation.md#python-and-native-components) |
| Install, update and uninstall guidance per OS (the README source text), desktop asset selection | Update-check logic and manifest — [09 Update check](09-quality-and-release.md#update-check); UI of the update card and help page — [08 Updates settings](08-ui-ux.md#updates-settings), [08 Install and updates help](08-ui-ux.md#install-and-updates-help) |
| Menus, global shortcut list, tray menu, drag and drop, window sizing, Settings › Desktop | Per-screen keyboard, mouse and context-menu behaviour — [08 Keyboard and mouse](08-ui-ux.md#keyboard-and-mouse) |
| VoiceOver and Java Access Bridge support, the Linux gap, the MD4 checklist | Shared accessibility rules and the custom-actions catalogue — [08 Accessibility](08-ui-ux.md#accessibility) |
| Desktop log files, crash files and their dialog, desktop diagnostics rows | Diagnostics API and redaction — [09 Crash reporting and diagnostics](09-quality-and-release.md#crash-reporting-and-diagnostics), [01 Logging and redaction](01-foundation.md#logging-and-redaction) |
| `DesktopSecretStore` file format ([PO-44](../PLAN.md#48-further-product-owner-decisions)) | `SecretStore` contract and its users — [03 Basic auth and CredentialStore](03-feeds-and-discovery.md#basic-auth-and-credentialstore), [10 Client sync engine](10-sync.md#client-sync-engine) |

### Modules

Packages follow `ch.lkmc.neutrodyne` + module path; `:desktopApp` uses `ch.lkmc.neutrodyne.desktop` ([PLAN 5.1](../PLAN.md#51-module-graph)).

| Module | Kind | Contents owned here | Depends on (project) |
|---|---|---|---|
| `:desktopApp` | `neutrodyne.desktop.application` (kotlin("jvm"), Compose application, Metro) | `MainKt`, `DesktopAppGraph`, `DesktopYouTubeBindingsModule`, `NeutrodyneWindow`, `DesktopMenuBar`, `SingleInstanceLock`, `InstanceHandshake`, `DesktopOpenHandler`, `UrlSchemeRegistrar`, `DesktopCrashReporter`, `ShutdownCoordinator`, `SmokeMode`, `BuildInfo`, `nativeDistributions` configuration, AOT training | features, shared implementations, the desktop-only modules (composition root, [PLAN 5.1](../PLAN.md#51-module-graph) rule 1) |
| `:playback:engine` | `neutrodyne.desktop.library` (JVM, `jvmTarget` 25) | `AudioEngine`, `FfAudioEngine`, `EngineItem`, `EngineState`, `EngineEvent`, `DesktopSourceResolver`, `ResolvedSource`, `ByteSource`, `FileByteSource`, `HttpByteSource`, `SpanCache`, `AvioBridge`, `DemuxerFactory`, `FfDemuxer`, `DecoderFactory`, `FfDecoder`, `FfmpegLibrary`, `SilenceSkipper`, `Sonic`, `GainStage`, `TimelineClock`, `LookAheadLoader`; `MpvAudioEngine` only if MD0 chooses the fallback | `:playback:native`, `:core:network:okhttp`, `:core:{model, common}`, `:playback:api` |
| `:playback:native` | `neutrodyne.desktop.library` + `neutrodyne.desktop.native` | `ndmedia` C/C++/Objective-C sources and CMake project, `NdmediaLibrary`, `NdOutput`, the OS-shim bindings, `playback/native/ffmpeg/build.sh`, `ffoffsets.c`, `native-components.lock` | — |
| `:playback:desktop` | `neutrodyne.desktop.library` | `DesktopPlaybackController`, `DesktopQueueProjector`, `DesktopPlaybackModule` | `:playback:core`, `:playback:engine`, `:desktop:system`, `:download:api`, `:youtube:api`, `:core:artwork`, `:core:database`, `:core:datastore` |
| `:desktop:system` | `neutrodyne.desktop.library` | `SystemMediaSession`, `WindowsSmtcSession`, `MacNowPlayingSession`, `LinuxMprisSession`, `PowerMonitor`, `IdleSleepInhibitor`, `AudioRouteMonitor`, `TrayController`, `LoginItemRegistrar` (`WindowsRunKeyRegistrar`, `MacLoginItemRegistrar`, `XdgAutostartRegistrar`), `DesktopNotifier` | `:playback:native` (shims), `:core:{model, common}` |
| `:youtube:ytdlp-desktop` | `neutrodyne.desktop.library` | `YtxProcess`, `StdioYtxTransport`, `PythonRuntimeLocator`, `DesktopEngineStorePaths`, `DesktopEngineUpdateLane`, `QuickJsBridge` (only with the JS provider); PBS bundling; `python-components.lock` | `:youtube:engine`, `:youtube:api`, `:core:datastore` |

Desktop code that lives in other owners' modules and follows this document's rules: `AppDirs` and `JobLane` (`:core:common` `desktopMain`); `DesktopJobRunner`, `DesktopRefreshLane`, `DesktopUpdateCheckLane`, `DesktopUpdateNotifier`, `DesktopSecretStore`, `DesktopMaintenanceLane` (`:core:data` `desktopMain`); `DesktopDownloadLane`, `DesktopMoveLane` (`:download:impl` `desktopMain`, 07); `DesktopArtworkLane` (`:core:artwork`, 08); `DesktopSyncLane` (`:sync:impl`, 10); `DesktopNetworkMonitor` (`:core:network`, 01); the `PlatformActions` implementations (`:core:ui` `desktopMain`, 08). Only `:youtube:ytdlp-desktop` may start a process; only `:playback:native` and `:desktop:system` make FFM downcalls into our own native code; `:playback:engine` makes FFM downcalls into FFmpeg only ([PLAN 5.1](../PLAN.md#51-module-graph) rule 6; `checkBannedApis`, [01 Dependency rules](01-foundation.md#dependency-rules)).

### Threading model

| Thread | Owner | Runs | Never |
|---|---|---|---|
| AWT event dispatch thread (`Dispatchers.Main` via `kotlinx-coroutines-swing`) | Compose, window, menus, tray, `java.awt.Desktop` handlers | Composition, ViewModel state collection, window and tray events, file dialogs | Disk or network I/O, database access, waiting on the engine thread |
| AppKit main thread (macOS) | the AWT toolkit | `MPRemoteCommandCenter` handlers, `NSWorkspace` notifications through the Objective-C shim | JVM work beyond enqueueing (one FFM upcall that posts to a queue) |
| `nd-playback` (one coroutine dispatcher: `Dispatchers.Default.limitedParallelism(1)`) | `DesktopPlaybackController` | All controller state: command handling, engine events, `PositionSaver`, sleep timer, session publishing | Blocking I/O (database writes are `suspend` calls on `Dispatchers.IO`) |
| `nd-engine` (one platform thread) | `FfAudioEngine` | Demux, decode, DSP, ring-buffer writes, AVIO read and seek upcalls (inside the `av_read_frame` downcall), transitions | Database access, network waits longer than the read timeout, Compose |
| `nd-loader` (one platform thread per open HTTP resource, at most 2) | `LookAheadLoader` | OkHttp `Range` reads into `SpanCache` | Decoding |
| miniaudio device thread (native) | `ndmedia` | Copies PCM from the ring buffer, advances `framesPlayed` | Any JVM code (no upcalls on the audio path, [D87](../PLAN.md#3-key-decisions)) |
| `nd-win-shim` (Windows, native, owned by `ndmedia`) | hidden top-level window | SMTC, `WM_POWERBROADCAST`, `IMMNotificationClient` callbacks, toasts; one upcall per event that only enqueues | JVM work beyond enqueueing |
| dbus-java worker threads (Linux) | `LinuxMprisSession`, logind and portal clients | D-Bus method calls and signals; posts commands to `nd-playback` | Long work |
| `nd-ytx-out`, `nd-ytx-err` (two platform threads per child) | `StdioYtxTransport` | Reading the child's stdout (protocol) and stderr (log) | Writes to the database |
| `Dispatchers.IO` / `Dispatchers.Default` | lanes of `DesktopJobRunner`, repositories, Room (`setQueryCoroutineContext(Dispatchers.IO)`) | Refresh, downloads, artwork, sync, update checks, maintenance | Touching Compose state directly |
| `nd-handshake` (virtual thread) | `InstanceHandshake` | Accepting loopback connections from a second launch | Anything but parsing and posting the hand-off |

### Processes and files at run time

One JVM process per user ([Single instance](#single-instance-and-handshake)) and, while YouTube is in use, one CPython child ([Process model](#process-model)). No other process is ever started: links and folders open through `java.awt.Desktop`, OS APIs or D-Bus, never through a shell ([01 Platform compliance](01-foundation.md#platform-compliance)). Files live only in the directories of [AppDirs](#appdirs) and in the user's chosen download folder.

---

## Platform matrix

Serves R8.1, R8.2, N7. Delivered in M0b (matrix and CI runners), MD5 (final). Honours [D88](../PLAN.md#3-key-decisions), [PO-40](../PLAN.md#48-further-product-owner-decisions).

### Supported targets

| Target ID | OS versions | CPU | CI runner | Bundled runtime | Formats | Notes |
|---|---|---|---|---|---|---|
| `windows-x64` | Windows 10 22H2 and Windows 11 | x64; also Windows 11 on Arm under Prism emulation | `windows-2025` | Temurin 25 x64 | MSI (per user), ZIP | Windows 10 on Arm is unsupported (it emulates only 32-bit x86, [Windows on Arm emulation](https://learn.microsoft.com/en-us/windows/arm/apps-on-arm-x86-emulation)) |
| `macos-arm64` | macOS 13 or later | Apple silicon | `macos-15` | Temurin 25 aarch64 | DMG from `1.0.0`; ZIP of the app before ([PO-39](../PLAN.md#48-further-product-owner-decisions)) | No Intel Macs ([D88](../PLAN.md#3-key-decisions)); see [macOS floor](#macos-floor) |
| `linux-x64` | glibc ≥ 2.31 (Ubuntu 20.04 class), PulseAudio or PipeWire with `pipewire-pulse` (ALSA as last resort), X11 or XWayland | x64 | `ubuntu-24.04` | Temurin 25 x64 | DEB, RPM, tar.gz | Natives built in `manylinux_2_28` containers |
| `linux-arm64` | as `linux-x64` | AArch64 | `ubuntu-24.04-arm` | Temurin 25 aarch64 | DEB, RPM, tar.gz | — |

Compose Multiplatform 1.12.1 supports macOS 13 arm64, Windows 10 x64 and arm64, and Ubuntu 20.04 x64 and arm64 ([CMP compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)). There is no universal (fat) desktop build and no 32-bit build ([D77](../PLAN.md#3-key-decisions)). jpackage cannot cross-package, so each target is built on its own runner ([native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)).

### Native-library coverage

Every native library in the image must exist for every target before it is accepted; a library without a target blocks that target or moves it to emulation.

| Library | Source | windows-x64 | macos-arm64 | linux-x64 | linux-arm64 | windows-arm64 (not built) |
|---|---|---|---|---|---|---|
| Skiko (Compose rendering) | JetBrains | yes | yes | yes | yes | yes |
| `sqlite-bundled-jvm` 2.7.1 (Room's `BundledSQLiteDriver`) | androidx | `windows_x64` | `osx_arm64` | `linux_x64` | `linux_arm64` | **missing** ([jar contents](https://dl.google.com/android/maven2/androidx/sqlite/sqlite-bundled-jvm/2.7.1/sqlite-bundled-jvm-2.7.1.jar)) |
| quickjs-kt-jvm 1.0.15 (JS provider, only if it ships) | dokar3 | `windows_x64` | `macos_aarch64` | `linux_x64` | `linux_aarch64` | **missing** ([artefacts](https://repo1.maven.org/maven2/io/github/dokar3/quickjs-kt-jvm/1.0.15/)) |
| JNA 5.19.1 (DPAPI, Run key, shell calls) | JNA | yes | yes | yes | yes | yes |
| python-build-standalone CPython 3.14 | Astral | `x86_64-pc-windows-msvc` | `aarch64-apple-darwin` | `x86_64-unknown-linux-gnu` | `aarch64-unknown-linux-gnu` | `aarch64-pc-windows-msvc` |
| FFmpeg 9.0.x minimal (`avutil`, `swresample`, `avcodec`, `avformat`) | built by us | MSYS2 + MSVC | Apple clang | gcc in `manylinux_2_28` | gcc in `manylinux_2_28` aarch64 | not built |
| `ndmedia` (miniaudio, ring buffer, OS shims) | built by us | MSVC, static CRT | Apple clang | gcc | gcc | not built |
| dbus-java 5.2.2 (MPRIS, logind, portals, notifications) | pure Java | — | — | yes | yes | — |

Temurin 25 publishes no Windows AArch64 build ([D88](../PLAN.md#3-key-decisions)); together with the two missing natives this is why Windows 11 on Arm runs the x64 build ([PO-40](../PLAN.md#48-further-product-owner-decisions)). An arm64 JVM cannot load x64 JNI libraries, so a Windows arm64 build would have to be arm64 throughout.

### Windows on Arm

The x64 MSI or ZIP runs under Prism on Windows 11 on Arm ([Windows on Arm emulation](https://learn.microsoft.com/en-us/windows/arm/apps-on-arm-x86-emulation)): the JVM, Skiko, SQLite, FFmpeg, `ndmedia` and the x64 CPython child all run emulated (user-mode code only). The update check offers the x64 asset ([Desktop update check](#desktop-update-check)). Unverified: start-up time and audio-engine CPU load under emulation; S13 records them on one Arm laptop if available ([Open questions](#open-questions) 4). A native build becomes possible when `sqlite-bundled` (and quickjs-kt, if the JS provider ships) publish `windows_arm64` natives and a single runtime vendor covers all targets ([D88](../PLAN.md#3-key-decisions)).

### macOS floor

The JDK 25 runtime is built with a macOS deployment target of 11.0 (`MACOSX_VERSION_MIN=11.00.00` in [`make/autoconf/flags.m4`](https://raw.githubusercontent.com/openjdk/jdk25u/master/make/autoconf/flags.m4)), and Compose Multiplatform supports macOS 13, so macOS 13 runs the app technically. Oracle's certification list for JDK 25 names macOS 26, 15 and 14 (14 marked "No Longer Supported") and not macOS 13; it also lists Windows 11 but not Windows 10 ([Oracle JDK 25 certified configurations](https://www.oracle.com/java/technologies/javase/products-doc-jdk25certconfig.html), read 2026-10-05). Temurin follows its own support matrix (Unverified for macOS 13 and Windows 10). The floor therefore stays macOS 13 and Windows 10 22H2 as [D88](../PLAN.md#3-key-decisions) says, S13 runs the packaged app once on each, and the PO may raise the floors ([Open questions](#open-questions) 1). `LSMinimumSystemVersion` is `13.0` (`macOS.minimumSystemVersion`), so older macOS versions refuse to open the app with the system's own message.

### Behaviour differences from Android

R8.1 requires every difference to be listed here. Everything not in this table behaves as on Android.

| Area | Android | Desktop | Where |
|---|---|---|---|
| Automatic backup | Auto Backup snapshot (R1.8) | None; manual backup ZIP and sync; Settings › Backup says so | [05 Auto Backup](05-groups-opml-backup.md#auto-backup) |
| Background work | WorkManager, UIDT jobs, quotas, 8-min soft deadlines | `DesktopJobRunner` while the app runs; nothing while it is quit; no soft deadlines | [Background work](#background-work) |
| Network policy | Metered detection, Wi-Fi-only policies, `playback.stream_on_metered` | Every network is unmetered in v1.0; metered and Wi-Fi-only rows show "Not used on computers" | [D85](../PLAN.md#3-key-decisions) |
| Charging | Optional "only while charging" | Ignored (rows hidden) | [07 Desktop runners](07-downloads.md#desktop-runners) |
| Audio focus | Pause or duck on calls, navigation and other media apps; `playback.pause_for_navigation` | None: the desktop mixes audio; the setting is hidden | [06 Player configuration](06-playback.md#player-configuration) |
| Becoming noisy | Pause on headphone unplug | Pause when the output device in use disappears (Windows, macOS; Linux best effort) | [Audio-route monitoring](#audio-route-monitoring) |
| Media controls | Media notification, lock screen, Bluetooth, Android Auto | SMTC, Now Playing, MPRIS, media keys and headset buttons through them; no car surface | [OS integration](#os-integration) |
| Resumption after reboot | System UI resumption card | The last session is restored paused at start; never auto-play | [Device loss, sleep and session restore](#device-loss-sleep-and-session-restore) |
| System sleep | Android doze | Pause and save on suspend, idle-sleep inhibited only while playing, nothing resumes on wake | [Power](#power-suspend-wake-and-idle-sleep) |
| Notifications | Channels per type and per group, permission prompt (API 33+) | One notification per event through the OS notification centre; per-group new-episode switches still apply; no channel UI; macOS asks for permission at the first notification | [Notifications](#notifications) |
| Process model | Main process, `:ytx`, `:acra` | One JVM process, one CPython child while YouTube is used | [Processes and files at run time](#processes-and-files-at-run-time) |
| YouTube engine | Chaquopy in `:ytx`; none on `armeabi-v7a` | python-build-standalone child process on every build | [Desktop YouTube engine host](#desktop-youtube-engine-host) |
| Downloads location | App-specific storage; SAF folder v1.x | `<data>/Downloads` or any folder the user chooses | [Desktop downloads and storage](#desktop-downloads-and-storage) |
| Local-network feeds | Blocked by the LAN guard (sync server excepted) | Allowed (no LAN guard on the desktop); macOS may show its Local Network prompt | [01 Networking baseline](01-foundation.md#networking-baseline) |
| Credentials | Android Keystore AES-GCM | DPAPI file on Windows, `0600` file on macOS and Linux ([PO-44](../PLAN.md#48-further-product-owner-decisions)) | [Secrets](#secrets) |
| Crash reports | ACRA dialog and email | Crash file and a dialog at the next start that offers an email | [Desktop diagnostics and crash files](#desktop-diagnostics-and-crash-files) |
| Colour | Dynamic colour on Android 12+ | Brand scheme seeded from amber; follows the OS light or dark setting | [08 Theming and colour](08-ui-ux.md#theming-and-colour) |
| Language | Per-app language (AppCompat) | Settings › Desktop › Language (`desktop.language`) | [Desktop settings](#desktop-settings) |
| Input | Touch, swipe, long-press, predictive back | Keyboard shortcuts, context menus, hover, scrollbars, Esc as back, drag and drop | [Desktop UX](#desktop-ux) |
| Sharing | Android share sheet, `FileProvider` | "Copy link", "Show in folder"; no share sheet | [Show in folder](#show-in-folder) |
| Updates | APK for `Build.SUPPORTED_ABIS[0]` | Asset for OS, architecture and install kind | [Desktop update check](#desktop-update-check) |
| Developer verification | Google's verification gate from 2027 | Gatekeeper "Open Anyway", SmartScreen, Smart App Control | [Install and update](#install-and-update) |
| Screen readers | TalkBack | VoiceOver; NVDA through Java Access Bridge; none on Linux | [Accessibility](#accessibility) |
| Video podcasts | Played as audio in v1.0 (video surface v1.x) | Played as audio; video through libmpv in v1.x (M17) | [D64](../PLAN.md#3-key-decisions) |
| Widgets, mini-player window, Quick Settings | v1.x / later | None | [PLAN 1.2](../PLAN.md#12-non-goals-for-v10) |
| Hardware next/previous (`playback.hardware_buttons`) | Headset and Bluetooth keys | The same setting maps media-key next/previous from the OS sessions | [Remote commands](#remote-commands) |

---
