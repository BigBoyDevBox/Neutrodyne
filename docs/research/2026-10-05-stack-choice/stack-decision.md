# Stack decision for Neutrodyne v1.0: Kotlin Multiplatform + Compose vs Flutter

Judge's verdict. Date: 2026-10-05.

**Inputs:**

- `research4/kmp-fit.md` and `research4/flutter-fit.md`, both read in full.
- The four `research3/` notes: `kmp-architecture.md`, `desktop-playback.md`, `desktop-distribution.md` and `sync-server.md`.
- `docs/PLAN.md`: D2, D3, D61, PO-35, T18 and §7.

I re-checked the key facts today against primary sources (§10).

**Evidence labels:**

- **Unverified:** not checked against a primary source.
- **Measured:** a number measured by the researchers in this session.

Scores are my judgement, and the reasoning for each one is given. Nothing here is legal advice.

---

## 1. Recommendation

**Build Neutrodyne v1.0 with Kotlin Multiplatform (KMP) and Compose Multiplatform for both apps (Android and desktop), and with Kotlin + Ktor for the sync server.**

- **Weighted score: KMP 70 / 100, Flutter 55 / 100.**
- KMP wins on Android quality, effort and risk, long-term dependency risk, and reuse of the plan.
- Flutter wins on desktop size and polish, and on licensing.
- **The result is robust.** In every sensitivity case I tried (§3), KMP stays ahead. That includes each of the three ways to handle the Java runtime and a desktop-heavy weighting. Flutter's best plausible case reaches 66.

**Conditions attached to the recommendation:**

1. **The owner picks one of the three runtime options in §6.**
   - My default is (A): bundle an unmodified OpenJDK runtime under a narrow D3 carve-out, with its source attached to every release.
   - Options (B) and (C) put no GPL bytes in our downloads, and KMP still wins with either.
   - **The stack choice does not depend on the Java question.**
2. **A desktop spike on real laptops in M0b confirms the budgets.**
   - Spike scope: Windows, an Apple-silicon Mac and Linux, mid-range 2022 hardware.
   - Budgets: first frame ≤ 1 s with the JDK 25 AOT cache, idle memory ≤ 350 MB, playing ≤ 450 MB.
   - These were measured only under Xvfb with software rendering so far.
3. **The desktop engine spike (MD0) runs before M1.** It covers our own audio engine with a minimal LGPL FFmpeg. It is the largest new technical risk, and it is the same risk for both stacks.

**"Why Java?" in plain words:**

- **We would not write Java.** We would write Kotlin, a modern language that compiles to several targets.
- **Android:** the app ships **no** Java runtime. Android runs Kotlin with its own built-in runtime.
- **Sync server:** we ship a single program file. Whoever hosts it installs Java, the same as installing any server software. We may also publish a ready-made container image under the same runtime decision.
- **Desktop:** this is the only place a runtime is bundled, because Compose for desktop runs on the Java Virtual Machine.
  - In 2026 there is no production-ready way around this. A native-image version exists only as a community proof of concept on macOS, and Kotlin's native Compose exists only for macOS.
  - That runtime is OpenJDK under GPL-2.0 with the "Classpath Exception". It does **not** change Neutrodyne's own licence. It does mean our desktop downloads would contain GPL program files whose source we must publish alongside.
- **Flutter avoids this entirely** because it compiles straight to machine code. That is Flutter's real advantage. It pays for it on Android, which is where this plan's hardest and most valuable work lies.

---

## 2. Score table

Scores run from 0 to 10. Weighted total = Σ(weight × score) / 10.

| # | Criterion | Weight | KMP + Compose | Flutter | Decisive facts |
|---|---|---|---|---|---|
| 1 | Android quality and compliance (Android 14–17 rules, Android Auto, MediaSession) | 25 | **9** | **5** | **KMP:** the Android app stays the plan's fully native Jetpack app. **Flutter:** a custom Kotlin Media3 plugin, a new Dart/Kotlin database boundary, headless Dart engines, and PO-35 debug builds become slow JIT builds |
| 2 | Desktop experience (size, startup, memory, native feel, accessibility, OS media integration) | 20 | **4.5** | **7.5** | **KMP:** about 210–235 MB installed and 200–450 MB of RAM; screen readers on Windows only through Java Access Bridge; none on Linux. **Flutter:** native code, about half the size, native Windows accessibility, partial Linux support, wider OS/CPU matrix |
| 3 | Licensing fit | 10 | **5** | **9** | **KMP:** desktop installers carry a GPL-2.0 runtime (lawful, with a source duty), unless option B or C is taken. **Flutter:** no GPL or LGPL in the engine. Its leftovers are list chores (MPL-2.0 Dart packages on Linux; FTL, Unicode and Inno Setup texts) |
| 4 | Effort and risk to ship everything in v1.0 | 20 | **7** | **4** | **KMP:** 58–97 engineer-weeks, with the Android risk already designed down. **Flutter:** about 59–103 weeks (Unverified, my estimate), and the critical new risks sit on Android |
| 5 | Dependency and maintenance risk over 5 years | 15 | **7** | **4.5** | **KMP:** critical paths are Google and JetBrains libraries. **Flutter:** the database, downloads, background work, audio and desktop window plugins each rest on one maintainer; desktop stewardship moved to Canonical in 2026 |
| 6 | Reuse of the plan and code sharing with the server | 10 | **9** | **4** | **KMP:** the plan survives largely verbatim, and one Kotlin `:sync:protocol` runs on all three. **Flutter:** requirements and schema carry over, but the implementation half of 01 and 06–09 must be re-planned |
| | **Weighted total** | 100 | **70.0** | **55.3** | |

### 2.1 Android quality and compliance (weight 25): KMP 9, Flutter 5

**KMP: 9.**

- On Android, Compose Multiplatform *is* Jetpack Compose: the artefacts resolve to androidx Compose 1.12.1.
- Everything below stays exactly as designed, as an Android-only Kotlin module (kmp-fit §6):
  - D37 `MediaLibraryService` + `MediaSession`;
  - Android Auto browse and resumption;
  - notification controls;
  - user-initiated data transfer (UIDT) jobs and WorkManager lanes (D47);
  - the Android 17 background-audio rules (D43);
  - Auto Backup (D34);
  - TalkBack and ATF checks;
  - Chaquopy in `:ytx`.
- **One point off** because the KMP restructuring touches Android:
  - Hilt is replaced by Metro.
  - Ktor sits over OkHttp.
  - `R.string` moves to Compose resources. That is a real risk for per-app language (spike S11), with a fallback of keeping Android `res/` for Android-only strings.
- PO-35's debug-build costs (T18) are the same as in today's plan.

**Flutter: 5.** Achievable, but weaker and riskier at every hard point:

- **The ready-made audio stack is behind the platform.**
  - `audio_service` 0.18.19 (2026-06-29) still uses the legacy `androidx.media` compat service. Its issue #942, "Update Android implementation to use media3", has been open since 2022-06-21.
  - `just_audio` 0.10.6 pins Media3 1.4.1 and had one commit in 12 months.
  - D39 (per-connection URL resolution), D40 (persistent cache) and D43 (Tap to resume) cannot be reached through these packages.
- **A custom Kotlin Media3 plugin fixes playback** by reusing design 06, but it creates a new critical problem.
  - The Kotlin service must read and write the queue and positions while Flutter's UI is not running (Auto, media buttons, resumption).
  - SQLite documents that two copies of the SQLite library in one process can corrupt a database through lost POSIX locks.
  - So the plan needs a new single-writer or shared-library design that does not exist yet (flutter-fit F3, likelihood medium, impact critical).
- **Headless Dart engines** are started for Auto cold starts and WorkManager refreshes. Unverified: latency against Auto's browse timeouts.
- **PO-35 conflict.**
  - Flutter's Gradle plugin turns every *debuggable* build into Flutter **debug mode**: JIT compilation, assertions on. The arm64 debug engine is 38.8 MB plus a 15.2 MB Vulkan validation layer (Measured).
  - Published APKs would likely break the size budgets PB12/PB13 and run visibly worse than T18 already accepts.
  - Fix options:
    - the owner allows non-debuggable release-mode APKs, which can still be signed with the committed key;
    - Unverified: the host's `debug` build type consumes Flutter's release-mode AAR. The add-to-app docs map AARs per build type, but nobody has tested a release engine inside a debuggable host.
- **Per-app language** needs a hand-written `locales_config.xml` and a bridge, and the string pipeline moves to ARB.

### 2.2 Desktop experience (weight 20): KMP 4.5, Flutter 7.5

| Aspect | KMP + Compose (JVM) | Flutter |
|---|---|---|
| Installed / download size | About 210–235 MB / 90–110 MB, plus 34–56 MB for an optional startup archive. Hello-world image: 149 MB, of which the runtime is 93 MB (Measured) | About 85–150 MB installed (Unverified estimate). Engine 17–30 MB per OS (Measured) |
| Startup (first frame) | 1.7–2.1 s plain; **0.58–0.63 s with the JDK 25 AOT cache** (Measured, Xvfb software rendering) | Native AOT code; fast, but Unverified, not measured |
| Memory | Idle 198–256 MB; 283–412 MB after scrolling 2,000 covers (Measured) | Unverified; expected lower, with no JVM |
| Look | Same Material cover-art UI on every OS. Native window frame, macOS menu bar, file dialogs | Same: self-drawn Material. Native macOS menu bar |
| Screen readers | macOS: yes. Windows: only through Java Access Bridge, **disabled by default** (`jabswitch /enable`). Linux: **not supported** | macOS: yes. Windows: native UI Automation. Linux: ATK, with gaps (Orca problems in Canonical's own Flutter installer) |
| Wayland | Through XWayland | Through GTK3 (Unverified detail) |
| OS / CPU matrix | Apple-silicon Macs only (CMP 1.12.1). Windows arm64 runs the x64 build emulated until we build SQLite natives | Windows x64/arm64, macOS universal (Intel + ARM), Linux x64/arm64 |
| OS media keys, Now Playing | Our own SMTC, Now Playing and MPRIS code (about 3 weeks) | macOS through `audio_service`. Windows and Linux plugins are stale or young, so most of the same own code is needed |
| Desktop audio engine | Must be built: our own engine plus LGPL FFmpeg (11–13 weeks) | Must be built too: no complete, maintained, licence-clean Flutter player. `media_kit`'s Windows libmpv is 0.35.1, and on Linux it links the distro's GPL-3+ libmpv |

**Reading:**

- Flutter wins clearly on weight, memory, accessibility and platform reach.
- Both are self-drawn, so neither feels like Fluent, AppKit or GTK.
- Both need the same native media work.
- KMP's 4.5 assumes the AOT cache ships. Without it, about 2 s cold start would merit 3.5.

### 2.3 Licensing fit (weight 10): KMP 5, Flutter 9

**KMP: 5.** Kotlin, Compose, Room, Ktor, Coil and Media3 are all permissive. The own minimal FFmpeg build is LGPL-2.1 and fine now. The problem is the desktop runtime:

- **What it contains:**
  - HotSpot: GPL-2.0-only;
  - the class library and the jpackage launcher: GPL-2.0 with the Classpath Exception;
  - the GCC runtime: GPL-3.0 with the runtime-library exception.
- **Why it is lawful:** it is compatible with an Unlicense app (Classpath Exception, mere aggregation; kmp-fit §4.2).
- **What it costs:**
  - It is GPL **distribution**, the very thing the owner questioned.
  - It brings a per-release source duty: an about 121 MB tarball, plus a CI gate.
  - It extends the ban list (jextract, ProGuard tasks).
- **The server is unaffected:** the fat JAR ships no runtime.
- **The score rises to 9–10** with runtime option B or C (§6), which ship no GPL bytes.

**Flutter: 9.**

- The engine is BSD-3, and its aggregated licence contains no GPL or LGPL (Measured).
- The Linux GTK libraries are LGPL system libraries that we do not ship.
- Own LGPL FFmpeg or libmpv builds are fine.
- The Dart server compiles to a native file, so a published container image needs no JVM.
- **One point off for chores:**
  - never link the distro libmpv (GPL-3+ on Debian);
  - Canonical's `dbus`/`nm` Dart packages are MPL-2.0 (Q-F2);
  - FreeType (FTL), Unicode, OpenSSL/ISC and Inno Setup texts must be added to the allow-list.

### 2.4 Effort and risk to ship everything in v1.0 (weight 20): KMP 7, Flutter 4

Unverified planning estimates, for one engineer working with AI sessions:

| Block | KMP (kmp-fit §8) | Flutter (my estimate) | Why they differ |
|---|---|---|---|
| Android v1.0 features (M0–M11b) | 26–51 | 26–51 | Same scope. Writing the UI in Compose or Flutter costs about the same; no code exists yet |
| Stack overhead on the Android path | 6–9 (KMP restructuring) | 7–13 | **Flutter:** re-plan 01 and 06–09 mechanics (3–5); Pigeon bridge and database-ownership design (2–4); Chaquopy in a Flutter host, ARB l10n, Kotlin and Dart test infrastructure (2–4) |
| Desktop shell and OS integration | 1.5–2 | 1.5–2 | Similar |
| Desktop playback | 11–13 | 11–15 | **Flutter:** the engine in C/C++ or Dart over FFI. The Kotlin DSP ports and `:playback:core` queue rules cannot be reused |
| Desktop YouTube engine | 2–4 | 2–4 | Same CPython child process |
| Desktop packaging and release | 2–3 | 1.5–2.5 | **Flutter:** no runtime packaging, AOT archive or runtime source |
| Sync SY0–SY3 | 7.5–11 | 7.5–11 | Both share one protocol package with a server in the same language |
| Cross-platform QA | 2–4 | 2–4 | Similar |
| **Total** | **58–97** | **≈ 59–103** | |

**Risk shape matters more than the totals:**

- **KMP:**
  - Its largest risk is the desktop engine core, which Flutter shares.
  - Next come Metro and Compose resources on Android (spikes S8 and S11), both with fallbacks (Koin; Android `res/`).
- **Flutter:**
  - It adds an uncharted Dart/Kotlin database boundary on the main platform.
  - It needs a PO-35 change.
  - It brings three languages: Dart, Kotlin, and C/C++/Swift on desktop.
  - It requires re-planning before coding starts.

Both stacks roughly double the Android-only plan (26–51 weeks): about 13–22 months solo.

### 2.5 Dependency and maintenance risk over 5 years (weight 15): KMP 7, Flutter 4.5

**KMP: 7.** Critical paths sit on two vendors with strong incentives:

- **Google:**
  - Android, Media3, Room, WorkManager and Jetpack;
  - officially supports KMP, and ships Room, DataStore, Lifecycle and Paging as KMP libraries.
- **JetBrains:**
  - Kotlin, Compose Multiplatform and Ktor;
  - ships its Toolbox App on Compose for Desktop;
  - moved its Compose-based Jewel UI into the IntelliJ Platform.

Points off:

- Compose *desktop* gets less attention than iOS.
- Linux accessibility may stay missing for years.
- Metro is essentially one maintainer (Koin is the fallback).
- We own the desktop engine and the FFmpeg bindings ourselves.

**Flutter: 4.5.**

- The core is Google's, despite the 2024 layoffs, and releases are monthly (3.47.6 on 2026-10-01).
- **Desktop stewardship moved to Canonical (Google I/O 2026).** That is stable for Linux; whether Canonical keeps investing in Windows and macOS is less certain.
- **Critical-path packages are single-maintainer:**
  - drift and sqlite3: one author has 320 of 359 and 399 of 403 commits;
  - background_downloader: 128 of 146;
  - workmanager: 145 of 147;
  - the audio stack (Ryan Heise);
  - window_manager and tray_manager;
  - serious_python.
- **2026 churn:**
  - Material moved out of the SDK into `material_ui`, with formal deprecation in November;
  - Impeller became the default desktop renderer only in August 2026.
- The mitigation, "fork when needed", moves that maintenance onto us.

### 2.6 Plan reuse and server sharing (weight 10): KMP 9, Flutter 4

No code exists yet, so what is reused is design.

**KMP: 9.**

- PLAN and 01–09 (about 2.2 MB) assume Kotlin, Room, Compose, Coil, OkHttp and Media3, and survive largely verbatim.
- research3 already re-shaped the plan for KMP.
- One `:sync:protocol` and one `:feeds` common module (`UrlNormalizer`, `EpisodeKeys`) run on Android, desktop and server. That matters for sync correctness: every client must compute identical identity keys.

**Flutter: 4.**

- **What carries over:** requirements, schema, formats, algorithms, the YouTube trust chain and the UX spec.
- **What is rewritten:**
  - the module graph, DI, Compose UI mechanics, Room DAOs, Paging, and testing and release tooling;
  - most of 01 and 09, and the mechanics of 06–08.
- Dart-to-Dart server sharing is as good as KMP's, but the Android Kotlin service needs some of the same rules on its side.

---

## 3. Sensitivity: what it takes to change the ranking

| Scenario | KMP | Flutter |
|---|---|---|
| Base case | 70.0 | 55.3 |
| Owner allows non-debuggable release-mode APKs (Flutter Android 5 → 6) | 70.0 | 57.8 |
| Flutter best case: a maintained Media3-based plugin plus a solved database boundary (Android 7.5, effort 5.5, maintenance 5.5) | 70.0 | 66.0 |
| Desktop-heavy weights (Android 10, desktop 35) | 63.3 | 59.0 |
| The desktop spike disappoints (KMP desktop 3) | 67.0 | 55.3 |
| KMP with runtime option B, a launcher that downloads Temurin (licensing 9, desktop 4, effort 6.5) | 72.0 | 55.3 |
| KMP with runtime option C, bring-your-own Java (licensing 10, desktop 2.5) | 71.0 | 55.3 |

**Conclusion:** no single change flips the result. Flutter would need its Android gap closed *and* the weights moved towards desktop.

---

## 4. Other stacks (one paragraph each)

**Tauri 2: web UI in the system WebView, Rust core. Dismissed (indicative score 47).**

- Status:
  - `tauri` 2.12.1 was released on 2026-09-30, and **3.0.0-alpha.4 on 2026-10-01**, so a major-version migration is ahead. Licence: Apache-2.0 OR MIT.
  - It uses the OS WebView: WebView2 (Chromium) on Windows, WKWebView on macOS, the Android System WebView, and **WebKitGTK** on Linux, whose version varies by distro.
  - None of the official plugins covers media sessions, background audio or foreground services.
- Desktop is attractive: small downloads, no bundled engine, mature web accessibility in all three WebViews (Unverified for WebKitGTK quality), and a licence-clean result. Desktop audio would still need our own Rust or FFmpeg engine plus SMTC/MPRIS code.
- **Android is where it fails.** The UI becomes a web page inside a WebView, and everything the plan treats as hard must be written as custom Kotlin plugins bridged to a Rust core:
  - Media3 service, Auto, UIDT, WorkManager, Chaquopy.
- The result is four languages (TypeScript, Rust, Kotlin, and C++/Swift glue) and almost no reuse of the implementation design.

**.NET 10 + Avalonia 12. Dismissed (indicative score 50.5).**

- The licence fit is the best of all: Avalonia 12.1.3 and .NET are MIT.
- Avalonia 12 (April 2026) is the only candidate with a **native Linux screen-reader backend (AT-SPI2)**. Its Wayland support is not yet generally available.
- Android is possible: Microsoft's bindings track Media3 1.11.1 and WorkManager 2.11.
- Three things sink it:
  1. **Chaquopy is "distributed as a plugin for Android's Gradle-based build system"**, so D72–D77 (yt-dlp on Android) would need a self-built CPython embedding.
  2. None of the Kotlin design survives; Android services would be written in C# over bindings.
  3. Avalonia's media player is a paid "pro" component, and LibVLCSharp is LGPL with GPL-licensed VLC plugins, so we would still build our own engine.
- Revisit only if Linux screen-reader support becomes a hard v1.0 requirement.

**React Native + desktop forks. Dismissed: a hard requirement fails.**

- `react-native` is at 0.87.1 (2026-08-26).
- Microsoft's `react-native-windows` is at 0.84.0 (2026-06-18) and `react-native-macos` at 0.83.0 (2026-09-30), so both lag the core.
- **No Linux target** exists from Meta or Microsoft, only unmaintained community efforts (Proton Native, React Native Desktop on Qt).
- Linux is a v1.0 target.

**Kotlin for Android plus a separate non-JVM desktop app (for example a Flutter desktop app). Dismissed now; it is the fallback if every JVM option is refused (indicative score 60).**

- Android stays exactly as planned and even saves the 6–9 weeks of KMP restructuring.
- The desktop gets Flutter's size and accessibility.
- But the whole domain layer is written twice and kept in step for ever:
  - the feed parser (03);
  - schema and identity ladder (02);
  - groups, OPML and backup (05);
  - the download state machine (07);
  - YouTube layer A and the engine host (04);
  - the sync client.
- Sync correctness then depends on two implementations of URL normalisation and episode keys agreeing exactly. Effort is about 70–115 weeks (Unverified), and every later feature costs double.
- A variant compiles the KMP logic with Kotlin/Native as a C library under a Flutter UI. Compose has no `linuxX64`/`mingwX64` UI artefacts, so only the logic could go that way. It is research-grade, so not considered further.

---

## 5. The five strongest arguments each way

### For KMP + Compose (and against Flutter)

1. **The hardest, highest-weighted platform stays native.**
   - Android Auto, MediaSession, notification controls, UIDT downloads and the Android 17 background-audio rules stay exactly as designed in Jetpack.
   - Flutter needs a custom Kotlin Media3 plugin *plus* a new Dart/Kotlin database split. SQLite's own docs warn that a wrong split can corrupt data.
2. **One language, one build, one implementation of the sync rules** on phone, desktop and server. This is the strongest guard against the worst sync bugs: duplicate podcasts and lost positions.
3. **The plan survives.**
   - About 2.2 MB of decided design (D-ids, schema, playback, downloads, YouTube) is written for Kotlin and Jetpack.
   - Flutter means re-planning the implementation half before writing code.
   - It also needs PO-35 changed, because Flutter turns debug builds into slow JIT builds.
4. **The same listening behaviour on both devices.**
   - The desktop engine ports Media3's speed (Sonic) and skip-silence processors and shares the queue rules.
   - An episode resumed on the laptop sounds and skips the same as on the phone.
5. **Lower 5-year dependency risk on critical paths.**
   - Google (Media3, Room, WorkManager, official KMP support) and JetBrains (Kotlin, Compose, Ktor) own them.
   - In Flutter, one person maintains each of the database, downloads, background work, audio and desktop window packages.

### For Flutter (and against KMP)

1. **No Java runtime.**
   - No GPL program files in our desktop downloads and no runtime source tarball per release.
   - The server compiles to one native file for a JVM-free container image.
2. **A leaner desktop app.**
   - About half the install size (Unverified estimate).
   - Native startup without a startup archive.
   - Likely lower memory (Unverified). KMP measured 200–450 MB.
3. **Better desktop accessibility and reach.**
   - Native Windows screen-reader support, against Java Access Bridge, which is off by default.
   - Partial Linux support, against none.
   - Official Windows-on-ARM, Intel Mac and Linux ARM builds.
4. **A UI toolkit built from day one for many platforms.**
   - A mature Material 3 implementation and monthly stable releases.
   - Desktop is now stewarded by Canonical, which builds Ubuntu's own apps with it.
5. **Licensing stays simple everywhere.** The owner's "no GPL" instinct is met without a carve-out, a download launcher or bring-your-own-Java, while server code sharing (one Dart protocol package) is just as good as KMP's.

---

## 6. What the owner must accept with KMP

1. **One Java-runtime option for the desktop.** Pick one:

   | Option | What users get | What we distribute | Cost |
   |---|---|---|---|
   | **A. Bundled runtime (my default)** | Normal installers (MSI, DMG, DEB/RPM); works offline | An unmodified OpenJDK 25 runtime from one vendor, which includes GPL-2.0 files. We attach its about 121 MB source tarball to every release and keep its `legal/` notices | A D3 amendment worded as in kmp-fit §4.4; a CI compliance gate |
   | B. Launcher downloads the runtime | Normal installers, but the first start downloads about 50 MB of Temurin from Adoptium (pinned version, SHA-256 checked) | No GPL bytes | Our own small launcher instead of jpackage's (+1–2 weeks); a new third-party host to name in N3; needs internet on first start. Unverified: antivirus and Gatekeeper reactions to a launcher that fetches a runtime |
   | C. Bring your own Java | Users install Java 21+ themselves. Good on Linux, poor on Windows and macOS (no MSI/DMG, no proper app bundle) | No GPL bytes | Support burden; weakest desktop UX |

2. **Desktop size and memory.** About 90–110 MB per download and 210–235 MB installed; 200–450 MB of RAM while running.
3. **Desktop look and accessibility.**
   - The same Material cover-art look on every OS, not native widgets.
   - **No screen-reader support on Linux.**
   - On Windows, screen readers work only after Java Access Bridge is enabled; we document it.
4. **Platform matrix.**
   - Apple-silicon Macs only; no Intel Macs.
   - Windows on ARM runs the x64 build emulated in v1.0.
   - Linux runs through XWayland.
5. **Time.**
   - About 58–97 engineer-weeks in total, roughly twice the Android-only plan: about 13–22 months solo.
   - Android itself gets 6–9 weeks slower because of the KMP restructuring.
   - Scope levers exist (kmp-fit §8.3): desktop YouTube in external mode, SY3 live updates in v1.1, and others.
6. **LGPL FFmpeg in the desktop builds.** Its exact source and build script ship with every release.
   - **Note:** `CLAUDE.md` and D3 still say "never LGPL". Both must be updated when the owner confirms.
7. **Desktop builds only from v1.0.0.** jpackage rejects a macOS version starting with 0, so tester desktop builds before 1.0 stay CI artefacts.
8. **Server distribution.**
   - A fat JAR whose admins install Java 17+.
   - A published container image only under runtime option A. Also confirm that GHCR counts as "GitHub only".
9. **Unsigned desktop friction** (same with any stack): macOS "Open Anyway", the SmartScreen warning, and Smart App Control must be off.

---

## 7. What would flip the decision

1. **The owner refuses every JVM option on desktop** (A, B and C in §6).
   - The desktop must then be non-JVM.
   - On the current weights, "Kotlin Android + Flutter desktop" scores about 60 and "Flutter everywhere" 55.
   - The hybrid doubles all domain logic for ever, so with one developer I would re-score with the owner before choosing. With two developers, I would take the hybrid.
2. **Flutter's Android gap closes**, *and* the owner moves weight from Android to desktop.
   - The gap closes if a maintained Media3-based Flutter audio plugin ships (for example `audio_service` #942 resolved) with a supported way for native services to share the database, and the owner allows release-mode APKs.
   - Each change alone is not enough (§3).
3. **The desktop spike fails badly on real laptops.** For example, idle memory above 500 MB or a first frame above 2 s with the AOT cache, or Java Access Bridge unusable with NVDA, *and* desktop users matter more than Android users.
4. **JetBrains deprioritises Compose desktop** (desktop target deprecated or left unreleased for 12 months), or Google withdraws KMP support. Watch the CMP release notes each milestone.
5. **Linux screen-reader support becomes a hard v1.0 requirement.** Neither KMP nor Flutter meets it fully; re-open Avalonia (§4) for the desktop.
6. **This would strengthen KMP further:** Compose running natively on Windows and Linux (Kotlin/Native Compose, or kotlin-desktop-toolkit growing into a Compose host). That would remove the bundled JVM entirely.

---

## 8. Plan changes if the recommendation is accepted

- **D3, N8 and `CLAUDE.md`:**
  - LGPL allowed with dynamic linking, notices and per-release source;
  - the chosen runtime option;
  - the ban list adds jextract, ProGuard tasks, JavaCPP `-gpl`, distro libmpv/FFmpeg and the GPL mpv-skipsilence script.
- **§1.2 non-goals:** remove "desktop" and "server/sync"; keep "no iOS"; add "no signed or notarised desktop builds, no package-manager catalogues".
- **D4, D8, D10, D13, D14:** as in research3 `kmp-architecture.md`:
  - Metro;
  - Ktor over OkHttp;
  - Room 3 KMP;
  - the `commonMain` first rule;
  - JDK 25 vendor pinning;
  - `:playback:engine` with `FfDemuxer`/`FfDecoder`.
- **Roadmap:** one v1.0 train made of:
  - M0a/M0b (KMP scaffold plus desktop shell and packaging spike);
  - M1–M11 for Android;
  - the desktop track: MD0 spike → MD1 playback → MD2 system surfaces → MD3 YouTube engine → MD4 release;
  - the sync track SY0–SY3.
- **Spikes before M1:**
  - S8 Metro, S11 Compose resources and per-app language, S12 Ktor over OkHttp;
  - S-D1 desktop packaging and performance on real hardware;
  - MD0 desktop engine core with the minimal FFmpeg.

---

## 9. Facts I re-checked today (2026-10-05)

| Fact | Source |
|---|---|
| Flutter stable 3.47.6 (2026-10-01), Dart 3.13.5 | https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json |
| `audio_service` 0.18.19 and `just_audio` 0.10.6 (both 2026-06-29); `media_kit` 1.2.6 (2025-12-13) | https://pub.dev/api/packages/audio_service , https://pub.dev/api/packages/just_audio , https://pub.dev/api/packages/media_kit |
| `audio_service` #942 "Update Android implementation to use media3": open since 2022-06-21 | https://github.com/ryanheise/audio_service/issues/942 |
| Flutter add-to-app maps host build types to AAR build modes (`debugImplementation` / `releaseImplementation`) | https://docs.flutter.dev/add-to-app/android/project-setup |
| Canonical became lead maintainer and "strategic steward" of Flutter desktop (Google I/O 2026) | https://www.omgubuntu.co.uk/2026/05/flutter-desktop-canonical-maintained |
| Compose Multiplatform Gradle plugin: latest is 1.13.0-alpha01 (1.12.1 stable per kmp-fit) | https://repo1.maven.org/maven2/org/jetbrains/compose/compose-gradle-plugin/maven-metadata.xml |
| Compose desktop accessibility: macOS fully supported; Windows via Java Access Bridge (disabled by default, `jabswitch.exe /enable`); Linux not supported | https://kotlinlang.org/docs/multiplatform/compose-desktop-accessibility.html |
| Google officially supports KMP; Jetpack libraries (Room, DataStore, Lifecycle, Paging, …) are KMP | https://developer.android.com/kmp |
| Jewel (Compose for Desktop UI) moved into the IntelliJ Platform | https://github.com/JetBrains/jewel |
| Tauri 2.12.1 (2026-09-30), 3.0.0-alpha.4 (2026-10-01), Apache-2.0 OR MIT | https://crates.io/api/v1/crates/tauri |
| Tauri official plugins: no media-session, background-audio or foreground-service plugin | https://v2.tauri.app/plugin/ |
| Tauri WebViews: WebView2 (Windows), WKWebView (macOS/iOS), Android System WebView, WebKitGTK (Linux, distro-dependent) | https://v2.tauri.app/reference/webview-versions/ |
| Avalonia 12 (2026-04-07): AT-SPI2 Linux accessibility, Android/iOS support, Wayland not yet GA, paid Media Player component | https://avaloniaui.net/blog/avalonia-12 |
| Avalonia 12.1.3 latest stable, MIT; `Avalonia.Android` 12.1.3; `Avalonia.FreeDesktop.AtSpi` 12.1.3 | https://api.nuget.org/v3-flatcontainer/avalonia/index.json , https://api.nuget.org/v3-flatcontainer/avalonia/12.1.3/avalonia.nuspec |
| .NET Android bindings `Xamarin.AndroidX.Media3.Session` 1.11.1 (2026-09-21); `Xamarin.AndroidX.Work.Runtime` 2.11.2.1 | https://api.nuget.org/v3-flatcontainer/xamarin.androidx.media3.session/index.json , https://api.nuget.org/v3-flatcontainer/xamarin.androidx.work.runtime/index.json |
| LibVLCSharp 3.10.1 is LGPL-2.1-or-later | https://api.nuget.org/v3-flatcontainer/libvlcsharp/3.10.1/libvlcsharp.nuspec |
| Chaquopy "is distributed as a plugin for Android's Gradle-based build system" | https://chaquo.com/chaquopy/doc/current/android.html |
| `react-native` 0.87.1 (2026-08-26); `react-native-windows` 0.84.0 (2026-06-18); `react-native-macos` 0.83.0 (2026-09-30); no official Linux package | https://registry.npmjs.org/react-native , https://registry.npmjs.org/react-native-windows , https://registry.npmjs.org/react-native-macos |

**Inherited facts.** These come from the research notes with their own primary sources and measurements. I did not re-measure them:

- **kmp-fit:** M1, M2 and M3; CMP 1.12.1 platform support; GraalVM status; OpenJDK licence texts.
- **flutter-fit:** engine sizes and licences; `buildModeFor`; package health; the SQLite "How to corrupt" page; Debian's GPL-3+ mpv.
- **research3:** effort sizes and the sync-server prototype numbers.

**Unverified items that matter for this decision:**

- Flutter desktop memory and startup.
- Flutter installed sizes.
- The add-to-app release-AAR workaround for PO-35.
- The UX of the option-B launcher (antivirus and Gatekeeper).
- Compose desktop memory and startup on real GPU hardware.
- All effort estimates.
