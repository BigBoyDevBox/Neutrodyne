# Research notes: how well Kotlin Multiplatform + Compose Multiplatform fits the expanded scope

Research area: `kmp-fit`. Date: 2026-10-05.

**Question.** Is Kotlin Multiplatform (KMP) with Compose Multiplatform (CMP) the right stack for the expanded scope? That scope is:
- Android;
- desktop (Windows, macOS, Linux), unsigned and published only on GitHub Releases;
- a self-hosted sync server;
- all of it in v1.0.

**Updated owner answers.**
- LGPL is now allowed, provided it is dynamically linked and its notices are met. GPL and AGPL are still excluded.
- The owner asked: "Why Java?"

**Builds on** the session's research3 notes:
- `research3/kmp-architecture.md`
- `research3/desktop-playback.md`
- `research3/desktop-distribution.md`
- `research3/sync-server.md`

Those notes assumed LGPL was excluded. Their material is not repeated here unless a conclusion changes.

**Evidence types:**
- Primary sources (URLs in §12).
- Three local measurements made in this session on a 4-vCPU Linux x64 container (16 GB RAM, Xvfb, no GPU). Method and raw numbers are in §13.
  - **M1:** startup and memory of a Compose desktop app. The app is CMP 1.12.1 with Material3 1.9.0 and a 2,000-item cover grid. It was measured with JDK 21, JDK 25, AppCDS and the JDK 25 AOT cache.
  - **M2:** size of a jpackage app image with a jlink'd Temurin 25 runtime.
  - **M3:** a minimal FFmpeg 9.0.2 build under LGPL-2.1-or-later. It was used through a custom I/O callback (the path our OkHttp byte source would take) on the research3 test corpus.

Anything not checked against a primary source is marked **Unverified**. Nothing here is legal advice.

---

## Summary: strengths and weaknesses

**Verdict.** KMP + CMP is the strongest fit for this scope when the goal is to keep the existing Android design. The plan is already written in Kotlin/Jetpack terms. Android stays a fully native app: Media3, Android Auto, user-initiated data transfer jobs, WorkManager, and the Android 17 background-audio rules are unchanged. One language and one Gradle build cover Android, desktop and server, and the sync merge rules run as the same code on all three.

**The price is on the desktop:**
- The app must ship a Java runtime (about 93 MB) that contains GPL-2.0 code.
- It uses about 200–250 MB of RAM at idle.
- The UI is drawn by Compose (Skia) rather than native widgets.
- Desktop playback has to be built, because Media3 does not exist on desktop.

No credible way to avoid the JVM on desktop exists in 2026. LGPL now makes desktop playback cheaper and safer. FFmpeg decoders inside our own engine are recommended over libmpv.

**Strengths:**

| # | Strength | Evidence |
|---|---|---|
| S1 | **Android stays 100 % native** and keeps the plan's design for the Android-specific hard parts: `MediaLibraryService` (D37), Android Auto browse, notification controls, UIDT and WorkManager download lanes (D47), Android 17 background audio (D43), Auto Backup (D34), per-app language, TalkBack. On Android, CMP *is* Jetpack Compose: the multiplatform artefacts resolve to androidx Compose 1.12.1 | research3 `kmp-architecture.md` §1, §3 |
| S2 | **One language and one build for all three products.** Shared modules: model, domain, Room 3 schema and DAOs, feed and OPML models, effective settings, queue rules, and `:sync:protocol` (HLC, OrderKey, field-merge rules) used by both clients *and* the server | research3 `sync-server.md` §9.1 |
| S3 | **Least re-planning.** About 2.2 MB of design documents (PLAN plus 01–09) assume Kotlin, Room, Compose, Coil, OkHttp and Media3. Most of it survives verbatim | repository |
| S4 | **Desktop Compose is stable and used in production** (JetBrains Toolbox App, about 1 M monthly users). Material3, adaptive layouts, Navigation 3 and lifecycle all run on desktop | CMP compatibility page; Toolbox case study |
| S5 | **Startup can be fixed.** First frame drops from 1.7–2.1 s to **0.58–0.63 s** with the JDK 25 AOT cache (M1). CMP 1.13 adds Gradle support for this | M1; CMP PR #5644 |
| S6 | **LGPL now simplifies desktop audio.** A minimal FFmpeg LGPL-2.1 build of **2.8 MB** demuxed and decoded every podcast format in the corpus, with chapters, exact gapless trimming and accurate seeks (M3). It replaces the riskiest permissive parts: the Media3 shim on the JVM, three AAC paths, and the libopus/libvorbis glue | M3 |
| S7 | **The server is cheap to add.** It is Ktor on the JVM in the same build. The admin installs Java (we ship a fat JAR), so no GPL is distributed. Measured idle RSS was 92 MB | research3 `sync-server.md` |

**Weaknesses:**

| # | Weakness | Size of the problem |
|---|---|---|
| W1 | **Every desktop installer contains a Java runtime.** It is OpenJDK: the class library is GPL-2.0 with the Classpath Exception, HotSpot is plain GPL-2.0, and the GCC runtime pieces are GPL-3.0 with the runtime-library exception. Our licence is unaffected (§4), but we would *distribute* GPL binaries and must publish their source | Owner decision Q1. The alternative, "bring your own JRE", has poor UX on Windows and macOS |
| W2 | **Size.** The jlink'd runtime is **93 MB**. A hello-world Compose app image is **149 MB on disk and 70 MB as tar.gz** (M2). The projected full Neutrodyne desktop app is about 215–235 MB installed and 90–110 MB to download, plus 34–56 MB if the startup archive is shipped | M2 + estimate |
| W3 | **Memory.** Idle RSS is **198–256 MB**, and **283–412 MB** after scrolling a 2,000-card grid (M1, software rendering). Expect about 250–450 MB for the real app. Native apps typically use less (Unverified comparison) | M1 |
| W4 | **"Native feel" is partial.** Window frame, macOS menu bar, file dialogs and tray are native. Widgets, text rendering, scrolling physics and context menus are drawn by Compose in the Material style. Accessibility works on macOS, on Windows only through Java Access Bridge, and **not at all on Linux**. Linux runs through X11/XWayland (Unverified for current CMP) | CMP docs; research3 |
| W5 | **Desktop playback is new code** in any KMP variant: about 11–13 weeks for the recommended FFmpeg route (§5). Android keeps Media3, so two engines must behave alike | §5 |
| W6 | **No JVM-free desktop path in 2026.** GraalVM Native Image with Compose exists only as a community proof of concept (macOS arm64, 2 Oct 2026). Kotlin/Native Compose exists only for macOS. JetBrains' new desktop toolkit is early-stage | §3 |
| W7 | **KMP restructuring overhead on the Android path:** +6–9 engineer-weeks. Hilt becomes Metro, `R.string` becomes Compose resources, and OkHttp moves behind Ktor | research3 |
| W8 | **Unsigned desktop distribution is painful.** macOS needs "Open Anyway" after every update. Windows shows a SmartScreen warning, and Smart App Control blocks the app outright. This applies to every stack, not only KMP | research3 `desktop-distribution.md` |

**Key numbers (measured this session unless marked):**

| Metric | Value |
|---|---|
| jlink'd Temurin 25.0.4.1 runtime (14 modules) inside the Compose app image | 93 MB (`lib/modules` 56 MB, HotSpot `lib/server` 29 MB) |
| Compose hello-grid app image / tar.gz (Linux x64) | 149 MB / 70.0 MB (`libskiko-linux-x64.so` alone is 29 MB) |
| First frame, JDK 21 / JDK 25, no archive | 1.81–2.02 s / 1.70–2.09 s |
| First frame with AppCDS (JDK 21) / JDK 25 AOT cache | 0.73–0.80 s / **0.58–0.63 s** |
| Archive size, raw / gzip | AppCDS 33.8 / 9.6 MB; AOT cache 55.6 / 14.1 MB |
| Idle RSS (3 s after first frame) | 236–256 MB plain; **198–199 MB** with the AOT cache |
| RSS after scrolling 2,000 cards | 283–412 MB |
| Minimal FFmpeg 9.0.2 LGPL-2.1+ (avformat, avcodec, avutil, swresample; shared; stripped) | 2.83 MB on disk, 1.32 MB gz; decodes 391–1,327× real time without SIMD assembly |
| JetBrains' own number (CMP PR #5644, M1 Ultra, AOT prebuild) | 424 ms cold start, 4.8× faster than without (not measured by us) |

**Recommendations:**

1. **Stack:** KMP + CMP for Android and desktop, and Ktor/JVM for the server, as research3 laid out (A1 structure: `commonMain` first, JVM "islands", Metro, Ktor over OkHttp, Room 3 KMP). Nothing in the new owner answers argues for a different stack *if* the owner accepts Q1 (the bundled runtime). If Q1 is refused, KMP desktop is only possible as "bring your own JRE". That is the point where a non-JVM desktop stack (Flutter, Tauri and so on, covered by other researchers) becomes the better choice.
2. **Answer to "Why Java?":**
   - Kotlin itself compiles to JVM bytecode, native code and Wasm.
   - **Android ships no Java runtime.** ART is part of the OS.
   - **The server ships none either.** The admin installs Java, or uses an image we publish only if Q1 allows.
   - **Only the desktop app ships a JVM**, because Compose Multiplatform's desktop target is JVM + AWT + Skia, and no production-ready alternative exists in 2026 (§3).
3. **Runtime:** bundle an unmodified **JDK 25 LTS** runtime, jlink'd. Attach its exact source tarball to every GitHub release (§4.3). Use the JDK 25 **AOT cache** for startup: CMP 1.13 `aot { mode = AotPrebuild }` once stable, or our own training-run task on 1.12.x. Keep the `legal/` folder intact.
4. **Desktop playback (route B):**
   - Use our own engine, as designed in research3: OkHttp byte source + `SpanCache`, ported Media3 `Sonic` and `SilenceSkippingAudioProcessor`, miniaudio output, and SMTC / Now Playing / MPRIS adapters.
   - Replace the Media3-on-JVM shim and all decoder glue with **our own minimal FFmpeg build (LGPL-2.1-or-later, shared libraries, no external libraries, no network protocols)**. Call it through hand-written FFM bindings.
   - **libmpv is the fallback**, and the route for desktop *video* in v1.x. It is not the v1.0 audio engine (§5.4).
5. **Android:** no change to the Android-specific design. Accept the KMP restructuring cost.
6. **Server:** Kotlin + Ktor 3.6 on the JVM, sharing `:sync:protocol` and `:feeds` common.
   - Ship a fat JAR.
   - Publish a GHCR image (distroless Java 25: Temurin plus a Debian glibc base, which is LGPL and now allowed) **only** under the same Q1 runtime carve-out. Otherwise ship a Dockerfile that users build themselves.
7. **Effort for "everything in v1.0":** about **58–97 engineer-weeks** for one engineer with AI sessions, versus 26–51 for the Android-only plan (§8). Unverified planning estimate. Scope levers are in §8.3.

---

## 1. What changes against research3 (new owner answers)

| Topic | research3 assumed | Now | Consequence |
|---|---|---|---|
| Release gate | Android v1.0 first; desktop and sync in v1.1 | **Android, desktop and sync server all in v1.0** | Desktop and sync tracks become release-blocking. Effort roughly doubles (§8). Desktop needs `v1.0.0` anyway, because jpackage refuses a macOS version starting with 0, so tester builds before 1.0 stay workflow artefacts (research3) |
| LGPL | Excluded | **Allowed** (dynamic linking, notices) | FFmpeg-LGPL becomes plan A for desktop decoding (§5). JNA can be used under either licence. An AppImage (libfuse is LGPL) becomes possible. Linux base layers with glibc become possible for a server image. **It does not solve the JRE question**, because OpenJDK is GPL, not LGPL |
| Java runtime | Owner question Q1, default "bring your own JRE" | The owner questions bundling a JRE | §2–§4 give the facts. Recommendation: accept a narrow runtime carve-out |
| iOS | Door kept open | No iOS (no developer registration) | No change: keep `commonMain` clean at zero cost. No iOS work |
| YouTube on desktop | External mode first; engine in v1.x | "Everything in v1.0" | The desktop yt-dlp engine (CPython child process) moves into v1.0 (+2–4 weeks), or the owner accepts external mode on desktop as a scope lever |

---

## 2. What the JVM desktop target really costs

### 2.1 Size

Measured (M2): `createDistributable` of the benchmark app, Compose Gradle plugin 1.12.1, Temurin 25.0.4.1 linux-x64 runtime. Modules: `java.base java.datatransfer java.xml java.prefs java.desktop java.logging java.management java.security.sasl java.naming java.transaction.xa java.sql jdk.accessibility jdk.crypto.ec jdk.unsupported`.

| Piece | Size on disk |
|---|---|
| `lib/runtime` (jlink'd JDK 25) | 93 MB, of which `lib/modules` is 56 MB and `lib/server/libjvm.so` (HotSpot) 29 MB |
| `lib/app` (our jars plus Compose, Material3, Skiko) | 55 MB, of which `libskiko-linux-x64.so` is 29 MB and the four biggest Compose jars about 14 MB |
| Launcher (`bin/`, `libapplauncher.so`) | under 0.1 MB |
| **Total app image** | **149 MB; 70.0 MB as tar.gz** |

**Projected full desktop app per target** (estimate, Unverified until the desktop spike):

| Piece | Estimate |
|---|---|
| Compose app image above | 149 MB |
| Data and network jars: Room 3 + sqlite-bundled native, Ktor, OkHttp, Okio, Coil, kotlinx-serialization, Metro runtime | +10–20 MB |
| FFmpeg minimal (measured 2.8 MB on linux-x64) + `ndmedia` (miniaudio and OS glue, about 1–2 MB) | +4–5 MB |
| CPython 3.14 trimmed (measured in research3) + yt-dlp zip | +47 MB |
| **Subtotal** | **about 210–220 MB installed, about 90–100 MB download** |
| Optional startup archive (AOT cache 55.6 MB / 14.1 MB gz; AppCDS 33.8 / 9.6 MB gz) | +34–56 MB installed, +10–14 MB download |

**Per release** (unchanged from research3): about 11 desktop files (MSI and ZIP for Windows x64/arm64; DMG for macOS arm64; DEB, RPM and tar.gz for Linux x64/arm64), about 1.0–1.3 GB in total, plus the runtime source (about 121 MB) and the FFmpeg source (about 11 MB, §5.6).

### 2.2 Startup (measured, M1)

Setup:
- Three runs per configuration, plus the first-ever run and the archive-training runs (16 runs in total).
- "First frame" is measured from JVM start (`RuntimeMXBean.startTime`) to the end of the first `withFrameNanos`.
- `main()` was reached after 32–101 ms in every run, so almost all the time goes to class loading, linking and JIT warm-up of Compose, Skiko and AWT.

| Configuration | First frame (3 runs) | Notes |
|---|---|---|
| JDK 21 (Ubuntu OpenJDK 21.0.11), plain classpath | 1,813 / 1,997 / 2,015 ms | First-ever run 2,580 ms: Skiko unpacks its native library to `~/.skiko` |
| JDK 21 + dynamic AppCDS (`-XX:ArchiveClassesAtExit`, then `-XX:SharedArchiveFile`) | 725 / 771 / 795 ms | Archive 33.8 MB. CDS needs **jar-only** classpaths: the first attempt with a classes directory silently produced no archive |
| JDK 25 (Temurin 25.0.4.1), plain | 1,697 / 1,810 / 2,086 ms | |
| JDK 25 + AOT cache (JEP 483/514/515: one training run with `-XX:AOTCacheOutput`, then `-XX:AOTCache`) | **580 / 586 / 625 ms** | Cache 55.6 MB. Idle RSS also fell to about 199 MB |
| Packaged app image (jpackage launcher, JDK 25, no archive) | 1,930 ms | |

**Caveats:**
- These numbers come from software rendering under Xvfb. Skiko logged `Cannot create Linux GL context` and fell back.
- Real GPU machines differ, both faster rendering and driver set-up cost (Unverified).
- JetBrains reports 424 ms total on an M1 Ultra with AOT prebuild (4.8× faster), and "more modest improvements" on Windows "due to known JVM issues with classpath lookups" (PR #5644).

**Tooling:**
- CMP **1.13.0-alpha01** (September 2026) adds `compose.desktop.application { aot { mode = AotMode.AotPrebuild } }`. There is also `AppCdsPrebuild` (JDK 21+) and `AppCdsAuto` (JDK 19+, archive created on the user's first launch).
- The training run sets `compose.aot.training-run=true`.
- On 1.12.1 the same effect needs our own Gradle task (a training run on each CI runner) plus `jvmArgs("-XX:AOTCache=…")`. Unverified: how jpackage's relocatable app directory interacts with absolute cache paths; spike.

### 2.3 Memory (measured, M1)

| Configuration | Idle RSS (first frame + 3 s) | After scrolling 2,000 cards for 5 s | Java heap used |
|---|---|---|---|
| JDK 21 plain | 245–256 MB | 293–412 MB | 11–17 MB |
| JDK 21 + AppCDS | 222–241 MB | 303–389 MB | 8–77 MB |
| JDK 21 + AppCDS, `-Xmx256m -XX:+UseSerialGC` | 272 MB | 361–363 MB | 14–38 MB |
| JDK 25 plain | 237–239 MB | 283–306 MB | 17–26 MB |
| JDK 25 + AOT cache | **198–199 MB** | 335–341 MB | 13–70 MB |

**Reading:**
- The Java heap is small (under 80 MB). Most RSS is JVM baseline (code cache, metaspace, class data), the Skia/Skiko native heap and AWT.
- Heap flags did not help.
- In the real app, add Coil's memory cache (a fixed MB budget on desktop, research3), Room/SQLite page cache, the audio engine (look-ahead buffers, about 10–30 MB, Unverified) and, while YouTube is in use, the CPython child process (a separate process; about 30–60 MB, Unverified).
- **Budget proposal for N5 (desktop): idle ≤ 350 MB RSS, playing ≤ 450 MB** (to be confirmed by the spike).

### 2.4 "Native feel"

| Area | Compose desktop behaviour | Native? |
|---|---|---|
| Window frame, title bar, resize, multi-monitor | AWT frame (OS decorations). CMP 1.12 adds Window/Dialog API v2 (screen choice, min/max size) | Yes |
| macOS global menu bar, Dock | `MenuBar` maps to the macOS menu bar; jpackage `.app` gives the Dock name and icon | Yes |
| System tray / menu bar extra | AWT `SystemTray`; GNOME shows nothing without the AppIndicator extension (Unverified) | Partly |
| File open/save dialogs | AWT `FileDialog`: native on macOS and Windows; GTK on Linux (Unverified for all desktops) | Mostly |
| Widgets (buttons, lists, sheets, menus, context menus) | Compose Material 3, drawn by Skia. Looks like the Android app, not like Fluent, AppKit or GTK | **No** |
| Text rendering, fonts, IME | Skia text with platform font fallback; IME supported (Unverified quality on Linux IMEs) | Close, not identical |
| Scrolling, keyboard focus, shortcuts | Compose physics and focus system; we must add shortcuts, hover, right-click menus and visible scrollbars (research3 M10 slice) | Needs work |
| Dark mode | CMP 1.12 polls the system theme on Windows and macOS | Yes |
| Accessibility | macOS supported; Windows only through Java Access Bridge (`jdk.accessibility` in the image, enable step); **Linux not supported** | **Gap** |
| Wayland | JetBrains' own IDEs went Wayland-native in 2026.1 through JBR's WLToolkit. Compose desktop on stock OpenJDK uses AWT's X11 toolkit (XWayland). Unverified whether CMP supports WLToolkit | Partly |
| Media keys, Now Playing, notifications | Not provided by Compose: our own SMTC / MPNowPlayingInfoCenter / MPRIS adapters (research3 `desktop-playback.md`) | Yes, with our code |

**Conclusion:** the app will look like "Neutrodyne", a cover-art-centric Material app, on every OS. That suits a product whose UI is deliberately cover-art-centric, but it is not an OS-native look. It is no worse than Flutter (also self-drawn). Only a per-OS native UI (three UI codebases) would beat it.

### 2.5 Platform-matrix gaps (unchanged from research3)

- CMP 1.12.1 officially supports macOS 13 **arm64** only.
- `sqlite-bundled` 2.7.x has no `windows_arm64` and no `osx_x64` natives.
- Recommendation: no Intel Macs. Windows arm64 as the x64 build under Prism until we build the SQLite JNI library.
- **JDK 25 caveat:** Temurin 25 has no Windows aarch64 build. Either use the Microsoft Build of OpenJDK 25 for Windows arm64 (a second vendor, so a second source tarball), use it for every target, or ship Windows arm64 as x64.

---

## 3. Can we avoid shipping a JVM on desktop in 2026?

| Route | Status on 2026-10-05 | Licence effect | Verdict |
|---|---|---|---|
| **GraalVM Native Image of a Compose desktop app** | **No official support.** A community fork of JetBrains' compose-multiplatform (`thisisthepy/compose-multiplatform-extended`, PR #1 merged and issue #2 closed on 2026-10-02) built an 82 MB single executable of a Material3 Compose app on **macOS arm64 only**, with Liberica NIK 25.0.4.1. Windows has "not yet run"; Linux is not covered. `letmutex/compose-native-host` (Apache-2.0) embeds Compose in native hosts with native-image support: version 0.0.4, "experimental", macOS and Windows only. Oracle detached GraalVM from the Java SE train (Sept 2025): GraalVM for JDK 24 was the last release supported as an Oracle Java SE product, Native Image was discontinued for Java SE product customers, and startup and footprint work moved to Project Leyden. GraalVM **Community** Edition continues (25.1/25.2/25.3 releases in 2026) | **No gain.** A native image embeds SubstrateVM and the compiled JDK class library, both GPL-2.0 with the Classpath Exception (research3) | **Not credible for v1.0.** Watch the community work |
| **Kotlin/Native + Compose for desktop** | Compose UI publishes `ui-macosarm64`, but **no `ui-linuxx64` or `ui-mingwx64`** (Maven Central, checked). CMP's supported-platforms table lists only JVM desktop. JetBrains' **kotlin-desktop-toolkit** (Apache-2.0) wraps native windowing (macOS, Windows, X11, Wayland). Its README says it "will later provide an OS integration layer for Compose for Desktop", and lists "Make it Kotlin Multiplatform in the future" as a goal, so it runs on the JVM today (Unverified detail) | Permissive | **Not available.** A possible long-term path (2027+?), not a plan |
| Project Leyden (JDK 24–25 AOT cache) | Shipping. Measured 0.58 s first frame | Still a JVM | **Use it** (startup), but it does not remove the runtime |
| Bring your own JRE (we ship only jars) | Works today (research3 option D-a) | No GPL distributed | Fallback if Q1 is refused: no MSI or DMG, poor macOS/Windows UX |
| CMP for Web (Wasm, Beta) inside a native WebView shell | Possible in theory | Permissive shell; the WebView is part of the OS | Not credible: Beta, no Room/SQLite parity (Unverified), audio through HTML media, a second runtime model. Not pursued |
| A non-JVM UI stack for desktop only (Flutter, Tauri, …) | Evaluated by other researchers | Varies | Gives up shared Kotlin UI and logic on desktop |

**Answer:** in 2026, the only production-grade way to ship a Compose desktop app is with a bundled (or user-installed) JVM. GraalVM would not change the licence position anyway, because its output also contains GPL-2.0 + Classpath Exception code.

---

## 4. Licence position of the bundled runtime

### 4.1 What a jlink'd runtime image contains

Read from `legal/` of the image built in M2, plus research3:

| Component | Licence | Where stated |
|---|---|---|
| Java class library (`java.base`, `java.desktop`, …) | GPL-2.0-only **with the Classpath Exception**, applying to files whose header carries the "Oracle designates this particular file as subject to the 'Classpath' exception" line | `legal/java.base/LICENSE` |
| HotSpot VM (`lib/server/libjvm.so`) | GPL-2.0-only **without** the Classpath Exception (e.g. `src/hotspot/share/runtime/thread.cpp` header) | research3 (jdk25u source) |
| OpenJDK Assembly Exception | Lets Oracle's "Designated Exception Modules" link with the GPL2 code. Not relevant to our code | `legal/java.base/ASSEMBLY_EXCEPTION` |
| Oracle's note on the Classpath Exception | It "permits you to use that code in combination with other independent modules not licensed under the GPLv2"; do not "commingle" incompatible code into Oracle's GPL files | `legal/java.base/ADDITIONAL_LICENSE_INFO` |
| GCC libgcc/libstdc++ 14.2.0 (Linux builds; linked statically into `libjvm.so`, which `ldd` shows has no `libstdc++` dependency) | GPL-3.0 with the GCC Runtime Library Exception 3.1 | `legal/java.base/gcc.md` |
| Third-party code inside (FreeType, HarfBuzz, libpng, zlib, ICU/CLDR data, `public_suffix` MPL-2.0 data, …) | Permissive / data | `legal/java.desktop/*`, `legal/java.base/*.md` |
| Windows runtimes `vcruntime140*.dll`, `msvcp140.dll`, `ucrtbase.dll` | Microsoft redistributable terms (not GPL) | research3 (Temurin zip) |
| jpackage launcher and `wixhelper.dll` (MSI custom action) | GPL-2.0 with the Classpath Exception | research3 (jdk25u source) |

### 4.2 Why Neutrodyne's own licence (Unlicense) is not affected

1. **The Classpath Exception covers the library our code links against.** "the copyright holders of this library give you permission to link this library with independent modules to produce an executable, regardless of the license terms of these independent modules, and to copy and distribute the resulting executable under terms of your choice, provided that you also meet, for each linked independent module, the terms and conditions of the license of that module. An independent module is a module which is not derived from or based on this library." Our jars are such independent modules: they are written against the public Java API, not derived from OpenJDK source.
2. **The VM (no exception) only runs our program.** It does not link into it. The FSF's own FAQ: "When the interpreter just interprets a language, the answer is no. The interpreted program, to the interpreter, is just data". Only "bindings" (for Java, the class library and JNI) create linking. That is exactly what the Classpath Exception covers, and why it exists. GPL-2.0 §0: "The act of running the Program is not restricted."
3. **Packaging them together is aggregation.** GPL-2.0 §2: "mere aggregation of another work not based on the Program with the Program … on a volume of a storage or distribution medium does not bring the other work under the scope of this License".
4. **GCC runtime pieces:** the Runtime Library Exception lets `libjvm` (an independent module compiled by GCC) be conveyed together with libgcc/libstdc++ "under terms of your choice". No GPL-3.0 duty attaches to our code.
5. **The Adoptium FAQ:** Temurin binaries are provided "under the terms of the 'GNU General Public License, version 2 with the Classpath Exception'".

**What does not change:** the runtime itself stays GPL code that we distribute. The Classpath Exception protects *our* code; it does not remove the GPL duties for the runtime's own binaries.

### 4.3 Obligations when we distribute an unmodified runtime

| Duty | Source | What we do |
|---|---|---|
| Keep notices and give recipients the licence | GPL-2.0 §1 | Ship the runtime's `legal/` tree untouched. jlink copies the per-module `legal/` folders by default, so never post-process them away. Show them on the in-app Licences screen |
| Provide the complete corresponding source | GPL-2.0 §3 | §3(a): "Accompany it with the complete corresponding machine-readable source code". §3 also says: "If distribution of executable or object code is made by offering access to copy from a designated place, then offering equivalent access to copy the source code from the same place counts as distribution of the source code". **Attach the vendor's source tarball** (Temurin `OpenJDK25U-jdk-sources_<ver>.tar.gz`, about 121 MB) **to every GitHub release that ships that runtime**, plus a `RUNTIME-SOURCES.md` naming the vendor build and the `temurin-build` tag. GPL-2.0 has no different-server clause like GPLv3's 6(d), so do not rely on linking to Adoptium. §3(b), a written offer valid 3 years, is an alternative but needs a support process. §3(c) is "only for noncommercial distribution" and only if we received such an offer; do not rely on it |
| The source must match the binary | §3 ("complete source code means all the source code for all modules it contains, plus … scripts used to control compilation") | One vendor and version per release. A CI gate checks that `lib/runtime/release` `IMPLEMENTOR`/`JAVA_VERSION` match the attached tarball. jlink's `--strip-debug` and module selection do not change the source we owe (the full JDK source covers any subset; Unverified as legal nuance) |
| The jpackage launcher and `wixhelper.dll` | GPL-2.0 with Classpath Exception | Their source lives in the same JDK source tree (`src/jdk.jpackage`), so the same tarball covers them |
| GCC runtime | GPL-3.0 with the Runtime Library Exception | No extra duty beyond keeping `gcc.md` |
| Microsoft VC++ runtime DLLs | Microsoft redistributable terms | Ship unmodified. Unverified: exact terms of the vendor's redistribution |
| Modifying the runtime | GPL-2.0 §2 | **Never.** Use stock vendor binaries only. A patched JBR or JDK would make us the source provider for our patches |

Not legal advice. The analysis follows the licence texts and the FSF FAQ cited above.

### 4.4 What the owner is actually deciding (Q1)

"No GPL code or dependencies" can mean either of two things:

- **(a) "Nothing we write or link is under the GPL, and our licence never inherits GPL terms."** The Classpath Exception satisfies this. A bundled runtime is then fine, with the §4.3 duties.
- **(b) "No GPL-licensed bytes in anything we distribute."** Then:
  - desktop can only ship as "bring your own JRE", with no MSI or DMG installers;
  - the server can ship only as a JAR plus a Dockerfile the user builds;
  - the same rule would also exclude GPL+CE launchers and the GCC runtime pieces.

**Recommended wording (D3 amendment):** "Desktop packages and the optional server image may contain an *unmodified* OpenJDK runtime image from one named vendor (GPL-2.0 incl. HotSpot; GPL-2.0 WITH Classpath-exception-2.0; GCC runtime GPL-3.0 WITH GCC-exception-3.1; vendor-bundled platform C runtimes), the jpackage launcher, and, for the server image, an unmodified distroless base. Its exact corresponding source is attached to every release that ships it. No other GPL code, and no GPL code linked into or derived from Neutrodyne."

### 4.5 Effect on the server

- **The fat JAR:** no runtime is shipped, so nothing changes.
- **A published image:** `gcr.io/distroless/java25-debian13` contains Temurin (GPL+CE, so it needs Q1) on a Debian base with glibc (LGPL-2.1, **now allowed**). It also contains libgcc (GPL-3.0 with the runtime exception) and CA certificates (MPL-2.0 data). Unverified: the full package list of that image.
- **So:** if Q1 is accepted, one carve-out covers both desktop and server. If it is refused, users build the server image themselves (research3).

---

## 5. How LGPL changes desktop playback

### 5.1 What LGPL unlocks (and what it does not)

| Item | Licence | Useful? |
|---|---|---|
| **FFmpeg** libavformat/libavcodec/libswresample, built without `--enable-gpl` / `--enable-nonfree` | LGPL-2.1-or-later (LGPL-3.0-or-later with `--enable-version3`) | **Yes: demux and decode of every podcast format; replaces three AAC paths, the Media3 shim and the codec glue** |
| **libmpv**, built `-Dgpl=false` with LGPL FFmpeg | LGPL-2.1-or-later ("The intended use for LGPL mode is with libmpv") | A complete player core. Alternative (§5.4) |
| JNA | Apache-2.0 OR LGPL-2.1+ | Either way; we prefer FFM on JDK 25 |
| AppImage runtime (static libfuse) | MIT + LGPL-2.1 | An optional Linux format again (research3 rejected it only for LGPL) |
| OpenAL Soft, SoundTouch, GStreamer, JLayer | LGPL | Not needed (miniaudio, Sonic, FFmpeg cover them) |
| logback, argon2-jvm, MariaDB Connector/J (server) | LGPL | Not needed (research3 `sync-server.md`) |
| **OpenJDK runtime** | **GPL-2.0 (+CE)** | **Not unlocked**: Q1 remains |

### 5.2 The binaries we would actually ship, and their real licences

| Candidate binary | Licence of the binary as built | Coverage | Verdict |
|---|---|---|---|
| **Own minimal FFmpeg build** (M3: FFmpeg 9.0.2, `--disable-everything --disable-network --disable-autodetect --enable-shared`, demuxers `mov,matroska,ogg,mp3,flac,wav,aac`, decoders `aac,aac_fixed,mp3float,mp3,opus,vorbis,flac,alac,pcm_*`, parsers, `swresample`) | configure printed **"License: LGPL version 2.1 or later"**. No external libraries, so no other licences inside | All targets; built from the same script on each runner (Windows needs MSYS2 + MSVC or clang; Unverified Windows arm64 details) | **Recommended** |
| BtbN/FFmpeg-Builds `lgpl-shared` | **LGPL-3.0** (`defaults-lgpl.sh`: `--enable-version3`, `COPYING.LGPLv3`) plus many external libraries, each needing review | win64, winarm64, linux64, linuxarm64; **no macOS** | Fallback for Windows/Linux only |
| JavaCPP Presets FFmpeg 8.1.2-1.5.14 | LGPL-3.0 (version3) plus many external libraries; `-gpl` artefacts are GPL | All | Not recommended (large; licence audit; JNI glue) |
| media-kit `libmpv-darwin-build` v0.7.3 (2026-09-14) `macos-arm64-audio-default` | The README says "mpv LGPL-2.1 / ffmpeg LGPL-2.1", **but the binary reports mpv 0.36.0 and FFmpeg 6.0 configured with `--enable-version3`, i.e. "libavcodec license: LGPL version 3 or later"**. It bundles mbedTLS (Apache-2.0 or GPL-2.0+). 7.9 MB on disk (measured) | macOS only. The Windows sibling repo was archived in Oct 2024. No Linux | Not usable as is: stale mpv, LGPLv3, no Windows/Linux |
| Own libmpv build (mpv 0.41.0 + FFmpeg + **libplacebo** + **libass**, needing FreeType, HarfBuzz and FriBidi) | mpv `-Dgpl=false`, LGPL-2.1+. mpv 0.41's `meson.build` makes libplacebo, libass, libavfilter and libswscale hard dependencies even for audio-only use | All, if we build about 7 libraries per target | Only if libmpv is chosen (§5.4) |
| Distro `libmpv` (Debian, Fedora, Homebrew) | Built in GPL mode (default) | — | **Never bundle** |

### 5.3 Spike M3: minimal LGPL FFmpeg on the research3 corpus

Method:
- Built on Linux x64 with gcc. Assembly disabled because `nasm` was unavailable, which makes decoding slower than production.
- Driven by a C harness that reads through a **custom `AVIOContext` with read/seek callbacks**: the shape of our OkHttp `ByteSource` / `SpanCache` bridge.

| File | Demuxer → decoder | Duration | Chapters | Decoded samples (expected) | Seek to 61 s → first frame | Speed |
|---|---|---|---|---|---|---|
| CBR MP3 | mp3 → mp3float | 120.000 s | — | 5,292,000 (= 120 s × 44.1 kHz; **LAME delay and padding trimmed exactly**) | 60.996 s | 896× |
| VBR MP3 (Xing) | mp3 → mp3float | 120.000 s | — | 5,292,000 | 60.996 s | 890× |
| MP3 with ID3 `CHAP` | mp3 → mp3float | 120.000 s | **3: Intro 0–30, Main 30–90, Outro 90–120** | 5,292,000 | 60.996 s | 1,001× |
| M4A (AAC-LC) | mov → aac | 120.000 s | — | 5,292,000 | 60.999 s | 1,242× |
| M4A with chapters | mov → aac | 120.000 s | **3 (same titles)** | 5,292,000 | 60.999 s | 1,250× |
| Fragmented M4A (DASH, like YouTube itag 140) | mov → aac | 120.023 s | — | 5,293,056 (no edit list, so the 1,024 priming samples are not trimmed; same finding as research3) | 60.999 s | 1,238× |
| WebM Opus (like itag 251) | matroska → opus | 120.008 s | — | 5,760,000 (exact at 48 kHz; pre-skip applied) | 60.994 s | 391× |
| Ogg Opus | ogg → opus | 120.007 s | — | 5,760,000 | 60.993 s | 454× |
| Ogg Vorbis | ogg → vorbis | 120.000 s | — | 5,292,000 | 60.292 s (granule seek; decode-and-discard needed) | 1,327× |
| FLAC | flac → flac | 120.000 s | — | 5,292,000 | 60.918 s | 1,279× |
| WAV | wav → pcm_s16le | 120.000 s | — | 5,292,000 | 61.000 s | 13,501× |

- **Size:** `libavcodec` 1.23 MB, `libavformat` 0.62 MB, `libavutil` 0.86 MB, `libswresample` 0.11 MB, stripped: **2.83 MB total, 1.32 MB gzip**.
- **Harmless warnings:** "Could not update timestamps for skipped/discarded samples" (the custom-I/O path) and "Protocol name not provided". Set `AVFormatContext`/AVIO hints in production.
- **HE-AAC v1/v2:** FFmpeg's native `aac` decoder supports it. Not in this corpus; add real HE-AAC samples to the corpus (Unverified here).

### 5.4 Desktop playback routes compared (LGPL allowed)

| Route | What we build | Parity with Android (Media3) | Licence and compliance work | Native build surface | Effort (Unverified) | Verdict |
|---|---|---|---|---|---|---|
| A. Permissive engine (research3) | Engine, cache, DSP, miniaudio, **Media3 extractors on the JVM via an `android.*` shim**, **AAC via Media Foundation / AudioToolbox / JAAD**, libopus/libvorbis glue, OS adapters | Highest for demux (same extractors) and DSP | Notices only | `ndmedia` (miniaudio + Opus + Vorbis + OS glue) | 13–15 w | Superseded |
| **B. FFmpeg-LGPL decoders inside our own engine (recommended)** | Engine core, OkHttp `ByteSource` + `SpanCache` (D40 rules) feeding a custom `AVIOContext`, FFmpeg demux and decode, ported `Sonic` and `SilenceSkipper` (same parameters as Android), miniaudio output, SMTC / Now Playing / MPRIS, power and device monitors | High: same DSP algorithms, same cache rules, same position semantics. Demux differs (FFmpeg instead of Media3), so cover chapters and gapless with shared corpus tests | FFmpeg LGPL-2.1 duties (§5.6): one library family, one source tarball per FFmpeg version | Minimal FFmpeg (M3) + small `ndmedia` (miniaudio + OS glue) | **11–13 w** | **Recommended** |
| C. libmpv (LGPL build) as the engine | Hand-written FFM bindings to about 20 of libmpv's 49 exported functions, `mpv_stream_cb_add_ro` custom protocol bridged to our `ByteSource` (keeps OkHttp auth and cache), queue window mapped to mpv's playlist + `prefetch-playlist`, positions from `time-pos`, `scaletempo2` speed, skip silence by `silencedetect` + a speed ramp (our own code; the known mpv script for this is GPL-2.0, so it must not be copied), OS adapters (mpv's built-in macOS/Windows media integrations are not relied on in embedded use; Unverified) | Medium: different time-stretch (scaletempo2 vs Sonic), different skip-silence behaviour (fast-forward instead of removal), different buffering | LGPL for mpv, FFmpeg, libplacebo and FriBidi; plus ISC, MIT, FTL, … for libass, HarfBuzz and FreeType: about 7 source bundles per release | mpv 0.41 needs libplacebo, libass (+FreeType, HarfBuzz, FriBidi), libavfilter and libswscale even for audio. Meson builds on 5 targets (Windows through MSYS2/llvm-mingw). No current LGPL prebuilt for Windows or Linux | 11–14 w | Fallback. **The route for desktop video in v1.x** |
| D. JavaFX Media, vlcj/libVLC, GStreamer | — | Low (formats, no skip silence) | vlcj is GPL-3.0; GStreamer plugin audit | Large | — | Rejected (research3 reasons stand) |
| E. Per-OS native players (WinRT, AVPlayer, GStreamer) | Three engines | Low | OS components | Three | High | Rejected for audio |

**Why B over C for v1.0:**

1. **Cross-device behaviour.** With sync, the *same* episode is resumed on Android and desktop. B keeps Media3's skip-silence algorithm, Sonic speed and D40 cache semantics on both sides. C changes how skip silence feels (fast-forward instead of cut), and changes speed quality at 2–3×.
2. **Smaller compliance and attack surface.** B ships one LGPL library family of about 3 MB with no network code and no external libraries. C ships about 7 native libraries per target, including font, subtitle and shader stacks we never use, each with security updates to track.
3. **Build simplicity.** B's FFmpeg configure line is one script. C needs a full meson dependency chain on five runners, and there is no maintained LGPL prebuilt for Windows or Linux.
4. **Effort is a wash** (11–13 vs 11–14 w). B's riskiest piece (our real-time engine core) was already spiked in research3 (miniaudio through FFM, Spike B).

**When C wins:** the owner wants video podcasts in the desktop v1.0, or the engine core proves unstable in the spike (MD0). Keep the seams (`AudioEngine`, `DecoderFactory`, `ExtractorHost`) so that libmpv can sit behind the same `PlaybackController`.

### 5.5 Route B design deltas against research3 `desktop-playback.md`

- `ExtractorHost` becomes `FfDemuxer`:
  - It wraps `avformat_open_input` on a custom AVIO.
  - `read` and `seek` call into the JVM `ByteSource` through **FFM upcalls on the engine thread**, which already sits inside the `av_read_frame` downcall. The design forbids upcalls on the audio thread, and that rule is unaffected.
  - Chapters come from `AVFormatContext.chapters`, mapped to 06's chapter model. P2.0 JSON and PSC chapters are unchanged.
- `DecoderFactory` becomes `FfDecoder`:
  - `avcodec_send_packet`/`receive_frame`, then `swr_convert` to interleaved f32 at the device rate. That replaces miniaudio's converter, though either works.
  - Gapless trimming via FFmpeg's skip-samples side data, as M3 shows. For DASH fMP4 without an edit list, keep research3's rule of a default AAC priming of 1,024 samples.
- **Removed:** the Media3 `android.*` shim and its Gradle AAR-extraction transform; the MF/AudioToolbox/JAAD AAC paths; and libopus/libvorbis/libogg from `ndmedia`.
  - `ndmedia` keeps miniaudio, the ring buffer and the per-OS glue (SMTC, Now Playing, power, device listeners).
  - It does **not** link FFmpeg. The JVM loads FFmpeg's shared libraries directly through FFM `SymbolLookup.libraryLookup`. That keeps FFmpeg separately replaceable (LGPL-2.1 §6(b)).
- **Bindings:** hand-written FFM for about 25 FFmpeg functions and the `ndmedia` C API. **Do not use jextract**: its LICENSE is GPL-2.0, and CLAUDE.md bans GPL build dependencies. Struct field offsets must be read through FFmpeg's accessor functions or AVOptions where possible, because offsets change between major versions. Pin one FFmpeg major per release.
- **Runtime:** JDK 25 (FFM final since JDK 22). Launcher flag `--enable-native-access=ALL-UNNAMED`.
- **Patents:** AAC and HE-AAC decoding now uses FFmpeg's decoder on every OS, including Windows and macOS where research3 used OS decoders. Exposure is similar to JAAD on Linux. US AAC-LC patents are expired per research3's cited review; HE-AAC is Unverified. Record this in the risk register (R-DP10 updated).

### 5.6 LGPL compliance for FFmpeg on our releases

These follow FFmpeg's "License Compliance Checklist" and LGPL-2.1 §§4 and 6:

1. Build without `--enable-gpl`, `--enable-nonfree` and `--enable-version3`. The configure output must say "LGPL version 2.1 or later"; a CI check greps `config.h`/`avcodec_license()`.
2. **Dynamic linking only:** separate `avcodec-*.dll` / `libavcodec.*.dylib` / `libavcodec.so.*`, never renamed to obscure names. LGPL-2.1 §6(b): "uses at run time a copy of the library already present on the user's computer system … and (2) will operate properly with a modified version of the library, if the user installs one". On macOS, a replaced dylib breaks the ad-hoc bundle signature; document `codesign --force -s -` for users who swap it (Unverified interpretation; our own app is ad-hoc signed anyway).
3. **Source:** attach `ffmpeg-<ver>-neutrodyne-src.tar.xz` (pristine tarball, `changes.diff` (empty), `BUILD.md` with the exact configure lines per target) to every release ("Host the FFmpeg source code on the same webserver as the binary"; LGPL-2.1 §4 same-place rule).
4. **Notices:** "This software uses code of FFmpeg licensed under the LGPLv2.1 and its source can be downloaded here" on the release page and the README download section. The About/Licences screen names FFmpeg and LGPL-2.1. Ship `COPYING.LGPLv2.1`.
5. Our terms must "permit modification of the work for the customer's own use and reverse engineering" (LGPL-2.1 §6). The Unlicense already does.
6. `native-components.lock` (from research3) gains the FFmpeg rows, and the CI licence scan of the app image checks them.

### 5.7 Effort for route B (Unverified, one engineer with AI sessions)

| Work package | Estimate | Change from research3 |
|---|---|---|
| Engine core: threads, projection window, clock, transitions, errors | 3–4 w | = |
| `ByteSource` + `SpanCache` + AVIO bridge | 1.5–2 w | = (bridge replaces `DataReader`) |
| FFmpeg build matrix (5–6 targets) + LGPL source bundle + checks | 1–1.5 w | new |
| FFM bindings + `FfDemuxer`/`FfDecoder` + corpus tests | 1 w | replaces the Media3 shim (1 w) |
| `ndmedia` (miniaudio sink + OS glue) + build matrix | 1–1.5 w | was 2 w (Opus/Vorbis/MP3 glue removed) |
| AAC per OS | 0 | was 1.5 w |
| DSP ports + tests | 0.5–1 w | = |
| SMTC / Now Playing / MPRIS + power + device monitors | 3 w | = |
| **Total** | **about 11–13 w** | was 13–15 w |

---

## 6. Android: stays fully native

Nothing in the Android-specific hard parts depends on desktop, and the KMP structure does not weaken any of them:

| Android requirement | Design (unchanged) | KMP note |
|---|---|---|
| Media3 `MediaLibraryService` + `MediaSession`, notification controls, resumption card | D37, 06 | `:playback:impl` stays an Android-only `com.android.library`. Queue rules move to `:playback:core` (common), shared with the desktop controller |
| Android Auto browse | D37 browse tree | Android-only |
| Downloads: UIDT jobs (API 34+), WorkManager lanes, job quotas | D47, 07 | Transfer core in common (Ktor + Okio). Runners in `androidMain` |
| Android 17 background-audio hardening | D43: playback only from a visible UI, notification, media key or widget | Android-only; nothing in common code can start playback |
| Refresh scheduling | D25 WorkManager | `androidMain` implementation of a common scheduler interface |
| Auto Backup | D34 include-only rules | Android-only |
| Per-app language | AppCompat per-app locales | **Risk:** strings move to Compose resources. Spike S11 (research3) checks `generateLocaleConfig` and runtime locale switching. Fallback: keep Android `res/` for Android-only strings |
| Accessibility (TalkBack, ATF checks) | M10, 09 | On Android, Compose semantics are Jetpack's own; ATF tests unchanged |
| YouTube engine | D72/D73 Chaquopy in `:ytx` + AIDL | Android-only `com.android.library` (the KMP Android plugin cannot do AIDL) |
| Debug builds, notify-only updates, `ch.lkmc.neutrodyne` | D2, D78, D61 | Unchanged. `neutrodyne-update.json` gains `desktop[]`/`server` (research3) |

**Android-side costs of KMP** (research3, still valid):
- Hilt → Metro 1.4.5 (Koin as fallback). Spike S8.
- `R.string` → Compose resources. Spike S11.
- Ktor 3.6.0 API over the plan's OkHttp 5.5.0 (`preconfigured`). Spike S12.
- Room 3 KMP with the bundled driver.
- The AGP 9 KMP library plugin, whose AGP 9.4.1 support gap is already covered by S1.
- **+6–9 engineer-weeks** on the Android path.

---

## 7. Sync server in Kotlin/Ktor (sharing code)

Unchanged from research3 `sync-server.md` except for the licence notes:

- **Code sharing:**
  - `:sync:protocol` (common-only): DTOs, `Hlc`, `OrderKey`, field kinds and merge rules, conformance vectors.
  - `:feeds` common: `UrlNormalizer`, `EpisodeKeys`, backup models.
  - The server depends on nothing else from the app. One merge implementation runs in the Android client, the desktop client and the server.
- **Stack:** Ktor 3.6.0 (CIO), kotlinx-serialization, plain JDBC on SQLite (sqlite-jdbc, Apache-2.0), Bouncy Castle Argon2 (MIT), slf4j-simple (MIT). Measured prototype (research3): 27.5 MB of JARs, **92 MB idle RSS** with small-heap flags, 50,000 changes pushed in 1.7–2.0 s.
- **LGPL now allowed:** logback, argon2-jvm and MariaDB Connector/J become permissible but are still not needed. Keep the permissive set.
- **Distribution:**
  - Fat JAR on the release. The admin installs Java 17+, so we distribute no runtime and no GPL.
  - A `Dockerfile` and `compose.yaml` the admin builds.
  - A **published GHCR image only if Q1 is accepted**: base `gcr.io/distroless/java25-debian13`. glibc is LGPL, which is now fine. Temurin is GPL+CE, so it needs the carve-out.
- **JVM-free server option (not for v1.0):** Ktor supports Kotlin/Native servers with the CIO engine only, on macOS, Linux (x64/arm64) and mingwX64, and "HTTPS without a reverse proxy is not supported", which is fine behind Caddy. It would keep full code sharing and avoid the runtime question for the image. Unverified: Room/SQLite on Linux native, Tier-2 target stability, and memory. Revisit in v1.x if Q1 is refused and a published image matters.
- **Effort:** SY0 groundwork 0.5–1 w; SY1 server 3–4 w; SY2 client sync 3–4 w; SY3 live updates and handoff 1–2 w. That is **7.5–11 w** for v1.0. gpodder compatibility (SY4) stays v1.x.

---

## 8. Revised effort estimate: "everything in v1.0"

Unverified planning estimates for one engineer working with AI coding sessions, using the plan's own size scale (S ≤ 1 w, M 1–2 w, L 2–4 w).

### 8.1 Line items

| Block | Basis | Weeks |
|---|---|---|
| Android v1.0 as planned (M0–M11b) | PLAN §7.1: 10 × L, 5 × M, 1 × S | 26–51 |
| KMP restructuring on the Android path (M0a/M0b split, Metro, Compose resources, Ktor, Room KMP, `:playback:core` extraction, desktop UX slice of M10) | research3 `kmp-architecture.md` | 6–9 |
| Desktop shell, background work and OS integration (single instance, tray, URL and file handlers, `DesktopJobRunner`, paths) | research3 D2 | 1.5–2 |
| **Desktop playback, route B** | §5.7 | 11–13 |
| Desktop YouTube engine (CPython child process, stdio protocol, trust-chain host adapter, quickjs-kt bridge) | research3 D4 | 2–4 |
| Desktop packaging and release (jpackage on 5 runners, AOT cache, runtime and FFmpeg source assets, image licence scan, smoke tests, unsigned-install guides) | research3 D3 + §4.3, §5.6 | 2–3 |
| Sync SY0–SY3 | §7 | 7.5–11 |
| Cross-platform QA and release hardening (manual OS checklists, cross-device sync tests, macOS/Windows accessibility pass, desktop N5 budgets) | new | 2–4 |
| **Total** | | **≈ 58–97 engineer-weeks** (midpoint ≈ 77) |

### 8.2 Reading the total

- That is about **1.9–2.2× the Android-only plan** (26–51 w). Solo, it is roughly 13–22 months. With two engineers, about 9–13 months of calendar time:
  - one engineer on the Android path (M-milestones);
  - one on desktop playback, then sync, then desktop release;
  - joining for M10 and the release.
- LGPL saves about 2 weeks against research3, and lowers risk (no Media3-on-JVM shim, no JAAD).
- The runtime decision (Q1) costs nothing in weeks either way. "Bring your own JRE" makes D3 packaging smaller, but it pushes support load onto users.
- **Largest uncertainties:**
  - the desktop engine core (real-time code on three OSes);
  - Metro and Compose resources on Android (S8/S11);
  - unsigned-distribution friction, which may force documentation and support work.

### 8.3 Scope levers that keep "everything in v1.0" true but smaller

| Lever | Saves |
|---|---|
| Desktop YouTube in external mode ("Watch on YouTube") in v1.0; engine in v1.1 | 2–4 w |
| SY3 (live SSE updates, "Continue on this device") in v1.1; v1.0 syncs on a timer and on app focus | 1–2 w |
| Windows arm64 ships as the x64 build (Prism); no native SQLite/QuickJS builds | 0.5–1 w |
| No startup archive in 1.0 (accept about 2 s cold start); add the CMP 1.13 AOT mode in 1.0.x | about 0.5 w |
| Linux: DEB + tar.gz only (no RPM) | small |

---

## 9. Impact on the existing plan (delta to research3's tables)

| Item | Change |
|---|---|
| §1.2 non-goals | Remove "server, accounts, cross-device sync" and "desktop". Keep "no iOS". Add "no store or catalogue listings (winget, Flathub, Homebrew core), no signed or notarized desktop builds" |
| D3 / N8 licensing | (1) **LGPL allowed** with dynamic linking, notices and per-release source (FFmpeg as the first user; the AppImage runtime if adopted). (2) **Q1 runtime carve-out** worded as in §4.4. (3) Ban list adds jextract (GPL tool), the ProGuard release tasks (GPL), JavaCPP `-gpl` artefacts, distro libmpv/FFmpeg binaries, and the GPL mpv-skipsilence script |
| D4 | JDK 25 runtime vendor pinned (Temurin 25; Microsoft Build of OpenJDK for Windows arm64 or everywhere: Q-level choice). CMP 1.12.1 now, 1.13 for the `aot {}` DSL once stable |
| D13 modules | research3 list, with `:playback:engine` using FFmpeg (`FfDemuxer`, `FfDecoder`), `:playback:native` (`ndmedia`: miniaudio + OS glue only), and `build-logic` tasks `buildFfmpeg` and `assembleFfmpegSource` |
| D63 / D79 | Desktop assets from `v1.0.0`. Each release also carries `openjdk-runtime-sources-<ver>.tar.gz` and `ffmpeg-<ver>-neutrodyne-src.tar.xz` |
| Milestones | One v1.0 train: M0a/M0b (KMP), M1–M11 Android, desktop track (MD0 spike → MD1 playback → MD2 system surfaces → MD3 YouTube engine → MD4 release), sync track (SY0–SY3). A **desktop packaging spike** (research3 S-D1) in M0b now also measures AOT cache, RSS and size on real Windows/macOS hardware |
| N5 | Desktop budgets: first frame ≤ 1.0 s with the AOT cache on a mid-range 2022 laptop (proposal), idle RSS ≤ 350 MB, installed ≤ 260 MB without the archive |
| Risks | Add RT1 (runtime carve-out refused), RT2 (AOT/CDS archive mismatch after a JDK patch: regenerate per build), FF1 (FFmpeg API/ABI drift: pin a major, accessor-only bindings), FF2 (LGPL compliance slip: CI gate), PB1 (desktop engine core instability: libmpv fallback) |

---

## 10. Questions the owner must answer (with a recommended default each)

| # | Question | Recommended default |
|---|---|---|
| Q1 | **Runtime carve-out.** May desktop installers (and an optional server image) contain an unmodified OpenJDK runtime (GPL-2.0 incl. HotSpot; GPL-2.0 + Classpath Exception; GCC runtime exception), with its exact source attached to each release? Our code and every linked dependency stay Unlicense, permissive or LGPL | **Yes**, worded as in §4.4. If **No**: desktop ships as "bring your own JRE" (no MSI or DMG) and the server as a JAR plus a self-built image. At that point, compare with the non-JVM desktop stacks |
| Q2 | **Desktop playback route** | **Route B: own engine + minimal LGPL-2.1 FFmpeg.** libmpv only if desktop video is wanted in v1.0 |
| Q3 | **LGPL version.** Is LGPL-3.0 also acceptable (prebuilt FFmpeg/libmpv builds use `--enable-version3`)? | Allow it, but prefer LGPL-2.1-or-later builds we make ourselves |
| Q4 | **Desktop YouTube in v1.0:** engine, or external mode? | **Engine**, if "everything" means feature parity. External mode is the first scope lever |
| Q5 | **Desktop startup archive.** Ship the AOT cache (+56 MB installed, +14 MB download, about 0.6 s first frame) or not (about 2 s)? | **Ship it** via CMP 1.13 `AotPrebuild` once stable; until then our own training-run task |
| Q6 | **JDK vendor for Windows arm64** (Temurin 25 has none) | Ship the x64 build to Windows arm64 in v1.0; one vendor (Temurin 25) everywhere |
| Q7 | **Desktop look.** Accept a Material/cover-art look identical across OSes (not Fluent, AppKit or GTK)? | **Yes**; add desktop affordances (shortcuts, hover, context menus, scrollbars) |
| Q8 | **Linux accessibility gap** (no screen-reader support in Compose desktop) | Accept and document (as research3 Q8) |
| Q9 | **Published server image** | Only with Q1 = yes: GHCR, distroless Java 25 base. Otherwise Dockerfile only |

---

## 11. Pitfalls and risks

| Id | Risk | Likelihood / impact | Mitigation |
|---|---|---|---|
| K1 | Owner reads "no GPL" strictly (Q1 = no) | Medium / high | BYO-JRE path; or switch desktop to a non-JVM stack (other research) |
| K2 | Runtime source compliance slips (wrong tarball, `legal/` stripped, a vendor build mismatch) | Medium / medium | CI gate comparing the `release` file with the attached source; `legal/` presence check |
| K3 | AOT/CDS archive stale or rejected (JDK patch, classpath order, Windows classpath issue) | Medium / low | The JVM falls back to normal start if the archive does not match (Unverified for every flag combination); regenerate per build; smoke-test start time in CI |
| K4 | Real-hardware RSS or start time worse than measured (GPU driver set-up; Windows) | Medium / medium | Desktop spike on real Windows/macOS/Linux laptops; N5 budgets set from measurements |
| K5 | CMP 1.13 AOT DSL is alpha in September 2026 | Certain / low | Our own training-run task on 1.12.x |
| K6 | FFmpeg ABI or struct changes break FFM bindings | Medium / medium | Accessor-only bindings; pin the major; corpus tests per bump |
| K7 | LGPL relinking on macOS conflicts with ad-hoc code signing | Low / low | Document re-signing; keep dylibs as separate files |
| K8 | Desktop engine core (clock, device loss, sleep) unstable on one OS | Medium / high | MD0 spike; libmpv fallback behind `PlaybackController` |
| K9 | Wayland-only Linux sessions without XWayland | Low / medium | Document the XWayland requirement; watch JBR WLToolkit and kotlin-desktop-toolkit |
| K10 | Effort overrun: the desktop and sync tracks are release-blocking now | High / high | Scope levers (§8.3); a second engineer; desktop spike before M1 |
| K11 | GPL-licensed helpers sneak in (jextract output, mpv-skipsilence ideas copied, ProGuard tasks, JavaCPP `-gpl`) | Medium / high | Ban list in `verifyDependencyPolicy` and the image scan; clean-room rule for skip silence (port Media3's Apache-2.0 processor) |
| K12 | HE-AAC patent exposure now on every OS through FFmpeg | Low / medium | Most podcasts are AAC-LC MP3; record in the risk register; OS decoders remain a possible `DecoderFactory` alternative |

---

## 12. Verified facts (with URLs)

**Compose Multiplatform and the JVM**

- CMP 1.12.1 supported platforms: macOS 13 arm64; Windows 10 x86-64/arm64; Ubuntu 20.04 x86-64/arm64. JDK 11+ to run, 17+ to package. — https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- Maven Central `org.jetbrains.compose.*`: latest `1.13.0-alpha01`. `ui-macosarm64` exists (HTTP 200); `ui-linuxx64` and `ui-mingwx64` do not (HTTP 404). Checked 2026-10-05. — https://repo1.maven.org/maven2/org/jetbrains/compose/compose-gradle-plugin/maven-metadata.xml , https://repo1.maven.org/maven2/org/jetbrains/compose/ui/ui-macosarm64/maven-metadata.xml , https://repo1.maven.org/maven2/org/jetbrains/compose/ui/ui-linuxx64/maven-metadata.xml
- CMP 1.13.0-alpha01 (September 2026): "Implemented support for AppCDS and AOT, which can significantly speed up application startup" (#5644). — https://github.com/JetBrains/compose-multiplatform/blob/master/CHANGELOG.md
- PR #5644 "Add support for AppCDS and AOT", merged 2026-07-17. `aot { mode = AotMode.AotPrebuild }`; `AppCdsAuto` (first launch, JDK 19+), `AppCdsPrebuild` (JDK 21+), `AotPrebuild` (JDK 25+); system property `compose.aot.training-run`; "AOT Prebuild: 67ms Shell→Main, 357ms Main→Window, 424ms total (4.8× speedup vs. no AOT)" on M1 Ultra; Windows "more modest". — https://github.com/JetBrains/compose-multiplatform/pull/5644
- CMP 1.12.0 (August 2026): experimental Window/Dialog state API v2. — https://blog.jetbrains.com/kotlin/2026/08/compose-multiplatform-1-12-0/
- JetBrains Toolbox App moved to Compose for Desktop; about one million monthly active users; "installer size was halved". — https://blog.jetbrains.com/kotlin/2021/12/compose-multiplatform-toolbox-case-study/
- JDK 25 AOT cache: JEP 483 (AOT class loading and linking), JEP 514 (command-line ergonomics, `-XX:AOTCacheOutput`), JEP 515 (method profiling). — https://openjdk.org/jeps/483 , https://openjdk.org/jeps/514 , https://openjdk.org/jeps/515
- IntelliJ-based IDEs run natively on Wayland from 2026.1 via JBR's WLToolkit. — https://blog.jetbrains.com/platform/2026/02/wayland-by-default-in-2026-1-eap/
- kotlin-desktop-toolkit: "wraps OS-specific window management APIs into an idiomatic Kotlin interface"; macOS, Windows, X11, Wayland; "will later provide an OS integration layer for Compose for Desktop"; Apache-2.0. — https://github.com/JetBrains/kotlin-desktop-toolkit

**GraalVM**

- Community fork: Compose desktop as one native-image executable on macOS arm64; 82 MB; Liberica NIK 25.0.4.1; Windows "not yet run", Linux not covered (2026-10-02). — https://github.com/thisisthepy/compose-multiplatform-extended/pull/1 , https://github.com/thisisthepy/compose-multiplatform-extended/issues/2
- compose-native-host: Apache-2.0, "experimental", v0.0.4, macOS and Windows hosts, GraalVM native-image support. — https://klibs.io/project/letmutex/compose-native-host
- Oracle (Sept 2025): GraalVM for JDK 24 was the last release licensed and supported as part of Oracle Java SE products. Native Image as Early Adopter technology is discontinued for Java SE product customers, and startup and footprint goals continue in Project Leyden. Source: search-result excerpts, because the full page returned HTTP 403 to this session. — https://blogs.oracle.com/java/post/detaching-graalvm-from-the-java-ecosystem-train , https://inside.java/2025/09/17/detaching-graalvm-java-ecosystem/
- GraalVM Community Edition 2026 releases: 25.0.2 (2026-01-20), 25.1.3 (2026-06-30), 25.2.4 (2026-07-28), 25.3.4.1 (2026-08-25). — https://www.graalvm.org/release-notes/25.1/ , https://www.graalvm.org/release-notes/25.2/ , https://www.graalvm.org/release-notes/25.3/
- GraalVM CE licence is GPL-2.0 with the Classpath Exception. — https://www.graalvm.org/faq/ (via research3)

**Licences of the runtime**

- OpenJDK GPL-2.0 + Classpath Exception text. — https://openjdk.org/legal/gplv2+ce.html ; identical text in the Temurin 25.0.4.1 image `legal/java.base/LICENSE` (read locally), together with `ASSEMBLY_EXCEPTION`, `ADDITIONAL_LICENSE_INFO` and `gcc.md` (GCC 14.2.0 libgcc/libstdc++ under GPL-3.0 + GCC Runtime Library Exception 3.1: "You have permission to propagate a work of Target Code formed by combining the Runtime Library with Independent Modules … under terms of your choice")
- GPL-2.0 §0 ("The act of running the Program is not restricted"), §1, §2 (mere aggregation), §3(a)–(c) and the same-place rule. — https://www.gnu.org/licenses/old-licenses/gpl-2.0.html (text read from the runtime's `LICENSE`)
- FSF GPL FAQ: `#IfInterpreterIsGPL` ("The interpreted program, to the interpreter, is just data … The JNI … is an example of such a binding mechanism"), `#MereAggregation`, `#SourceAndBinaryOnDifferentSites` (allowed by GPLv3 6(d)). — https://www.gnu.org/licenses/gpl-faq.html
- Adoptium: "The Eclipse Temurin binaries are provided … under the terms of the 'GNU General Public License, version 2 with the Classpath Exception'". — https://adoptium.net/docs/faq/
- Temurin 25.0.4.1 linux-x64 JDK tarball 141,329,719 bytes (Adoptium API). — https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse
- jextract LICENSE is GPL-2.0 (with a Classpath mention). — https://github.com/openjdk/jextract/blob/master/LICENSE

**Playback libraries**

- mpv Copyright: GPL-2.0+ by default; `-Dgpl=false` gives LGPL-2.1+; "The intended use for LGPL mode is with libmpv"; "Linked libraries still can affect the final license". — https://github.com/mpv-player/mpv/blob/master/Copyright
- mpv 0.41.0 is the latest release (release notes require FFmpeg 6.1+ and libplacebo 6.338.2+; reported January 2026). `meson.build` v0.41.0 hard-requires libavcodec, libavfilter, libavformat, libavutil, libswresample, libswscale, libplacebo and libass. `include/mpv/client.h`: `MPV_CLIENT_API_VERSION 2.5`, 49 `MPV_EXPORT`s. `include/mpv/stream_cb.h`: `mpv_stream_cb_add_ro`, read/seek/size/close/cancel callbacks. — https://github.com/mpv-player/mpv/releases , https://www.linuxtoday.com/blog/mpv-0-41-open-source-video-player-released-with-improved-wayland-support/ , https://raw.githubusercontent.com/mpv-player/mpv/v0.41.0/meson.build , https://raw.githubusercontent.com/mpv-player/mpv/v0.41.0/include/mpv/client.h , https://raw.githubusercontent.com/mpv-player/mpv/v0.41.0/include/mpv/stream_cb.h
- media-kit libmpv-darwin-build: README claims LGPL-2.1 for the default/full flavours. Release v0.7.3 (2026-09-14); `macos-arm64-audio-default` is a 4 MB download and 7.9 MB unpacked (measured). The binary's embedded strings show mpv 0.36.0 with `-Dgpl=false -Dlibplacebo=disabled`, FFmpeg 6.0 with `--enable-version3 --enable-mbedtls`, and "libavcodec license: LGPL version 3 or later". — https://github.com/media-kit/libmpv-darwin-build , https://github.com/media-kit/libmpv-darwin-build/releases/tag/v0.7.3
- media-kit/libmpv-win32-video-build archived on 2024-10-09. — https://github.com/media-kit/libmpv-win32-video-build
- mpv-skipsilence (silencedetect + speed ramp) is GPL-2.0, so do not copy it. — https://codeberg.org/ferreum/mpv-skipsilence
- BtbN FFmpeg-Builds: win64/winarm64/linux64/linuxarm64, no macOS; variants gpl, lgpl, nonfree and their -shared forms; `defaults-lgpl.sh` = `--enable-version3`, `COPYING.LGPLv3`. — https://github.com/BtbN/FFmpeg-Builds , https://raw.githubusercontent.com/BtbN/FFmpeg-Builds/master/variants/defaults-lgpl.sh
- FFmpeg 9.0.2 is the newest release tarball (9.0, 9.0.1, 9.0.2; 8.1 before). — https://ffmpeg.org/releases/
- FFmpeg legal: LGPL-2.1+ default; "License Compliance Checklist" (no `--enable-gpl`/`--enable-nonfree`, dynamic linking, distribute the exact source, configure line, same web server, About-box and web notices, no obfuscated DLL names). — https://ffmpeg.org/legal.html
- LGPL-2.1 §4 same-place rule; §6 ("permit modification of the work for the customer's own use and reverse engineering"); §6(b) shared-library mechanism. — text in FFmpeg 9.0.2 `COPYING.LGPLv2.1`; https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
- FFmpeg codec table (native decoders: AAC, Opus, Vorbis, MP3, FLAC). — https://ffmpeg.org/general.html

**Server**

- Ktor Kotlin/Native server: CIO engine only; targets macOS, Linux x64/arm64, mingwX64; `embeddedServer` only; "HTTPS without a reverse proxy is not supported". — https://ktor.io/docs/server-native.html
- Distroless Java 25 image `java25-debian13` (Temurin). — https://github.com/GoogleContainerTools/distroless/blob/main/java/README.md (via research3)

---

## 13. Measurements: method and raw data

All measurements ran in the session scratchpad `research4/`. Nothing was added to the repository.

### M1: Compose desktop startup and memory

- **Project:** `research4/cmpbench/`.
  - Kotlin 2.4.20, `org.jetbrains.compose` 1.12.1, `org.jetbrains.compose.material3:material3:1.9.0`.
  - One `Window` 1280×800 dp, `Scaffold` + `TopAppBar`, `LazyVerticalGrid(GridCells.Adaptive(160.dp))` of 2,000 `Card`s (square coloured cover `Box` + two `Text`s), dark Material3 scheme.
  - It logs "first frame" after `withFrameNanos`, RSS (`/proc/self/status` VmRSS) 3 s later, then scrolls 100 × 400 px over about 5 s and logs RSS again, then exits.
- **Host:** 4 vCPU, 16 GB RAM, `xvfb-run -s "-screen 0 1920x1080x24"`, no GPU (Skiko fell back from OpenGL to software rendering).
- **JDKs:** Ubuntu OpenJDK 21.0.11; Temurin 25.0.4.1+1.
- **Raw first-frame times (ms from JVM start):**
  - JDK 21 plain: 2580 (first run, Skiko unpack), 2015, 1997, 1813
  - JDK 21 + AppCDS: 795, 771, 725 (archive 33,812,480 B)
  - JDK 25 plain: 2086, 1697, 1810
  - JDK 25 + AOT cache: 625, 586, 580 (cache 55,562,240 B; gzip 14,077,279 B)
  - Packaged app image (JDK 25): 1930
- RSS values are in §2.3.

### M2: app image size

- `gradle createDistributable` with `javaHome` = Temurin 25.0.4.1 → `build/compose/binaries/main/app/cmpbench`: 149 MB.
  - `lib/runtime` 93 MB (`lib/modules` 56 MB, `lib/server` 29 MB).
  - `lib/app` 55 MB (`libskiko-linux-x64.so` 29 MB).
- tar.gz: 69,991,116 B.
- `ldd lib/server/libjvm.so`: libdl, libpthread, librt, libm, libc only (GCC runtime linked statically).

### M3: minimal FFmpeg LGPL build

- **Source:** `ffmpeg-9.0.2.tar.xz`.
- **Configure line:**

  ```
  --disable-everything --disable-programs --disable-doc --disable-network --disable-autodetect --disable-x86asm --enable-shared --disable-static --disable-avdevice --disable-avfilter --disable-swscale --enable-swresample --enable-demuxer=mov,matroska,ogg,mp3,flac,wav,aac --enable-decoder=aac,aac_fixed,mp3float,mp3,opus,vorbis,flac,alac,pcm_s16le,pcm_s24le,pcm_f32le,pcm_u8 --enable-parser=aac,mpegaudio,opus,vorbis,flac --enable-bsf=aac_adtstoasc
  ```

- configure reported "License: LGPL version 2.1 or later".
- **Output:** `libavcodec.so.63` 1,232,688 B; `libavformat.so.63` 618,752 B; `libavutil.so.61` 863,104 B; `libswresample.so.7` 109,048 B (stripped). Total 2,826,354 B; tar.gz 1,319,499 B.
- **Harness:** `research4/fftest.c`, a custom `AVIOContext` over `FILE*`. Corpus: research3 `_dp/spike/media/` (generated test files with FFmpeg-written chapters). Results are in §5.3.
