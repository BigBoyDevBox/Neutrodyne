# Handoff — state of the Neutrodyne planning work

This file lets another person or agent take over the work at any point. It is updated at every stage. **Last updated: 2026-10-05 18:57 UTC, during the replan for Kotlin Multiplatform, desktop and sync. Steps 1–3 are done; the critics (step 4) are running.**

## 1. What this repository is

Neutrodyne is a planned open-source podcast player. So far the repository holds **only the plan**: there is no code. The sources of truth are:

- [CLAUDE.md](CLAUDE.md): the owner's standing conventions. Read this first; they are binding.
- [docs/PLAN.md](docs/PLAN.md): the master plan, holding requirements (R/N-ids), decisions (D-ids), owner decisions (PO-ids), the roadmap (M-ids), risks and the glossary.
- [docs/design/](docs/design/): the design docs `01-foundation` … `09-quality-and-release`, plus `10-sync` and `11-desktop` once the replan has written them.
- [docs/research/](docs/research/): raw research notes and the change briefs used for each revision. These are non-normative.
- [tools/doccheck/](tools/doccheck/): link/anchor and Mermaid checkers. Both must report 0 problems before any push.

## 2. Owner decisions so far (chronological)

| When | Decision |
|---|---|
| 2026-10-04 | Initial ask: Android podcast player with subscription import/export, user-defined groups each viewable as its own episode feed, YouTube channels as podcasts, streaming and downloading, a nice cover-art UI. Plan pushed to `main`. |
| 2026-10-05 | **PO-1** Avoid GPL: use **yt-dlp** (Unlicense) embedded via an embedded CPython instead of NewPipe Extractor. |
| 2026-10-05 | **PO-2** Distribution: **GitHub Releases only** (no Play, F-Droid or other stores). |
| 2026-10-05 | **PO-5** **No Google developer verification** registration. |
| 2026-10-05 | **PO-8** Package/ID prefix is always **`ch.lkmc`** (`ch.lkmc.neutrodyne`). |
| 2026-10-05 | **PO-31** Update checks are **notify-only**, linking to GitHub; the user downloads and installs manually. |
| 2026-10-05 | **PO-32** YouTube engine updates are automatic, limited to versions approved by our canary. |
| 2026-10-05 | **PO-33** No beta channel. **PO-34** No mirror. |
| 2026-10-05 | **PO-35** First answered "just build debug builds", then clarified as **"no key management"**: publish optimised release builds signed with the keystore committed to the repo. |
| 2026-10-05 | **Scope expansion:** add a **self-hosted sync server** (subscriptions, listening state and similar; never audio) and **desktop targets**. |
| 2026-10-05 | **LGPL allowed.** **Everything ships in v1.0** (Android + desktop + sync server). Desktop on **Windows, macOS and Linux, unsigned**. |
| 2026-10-05 | **Stack: Kotlin Multiplatform + Compose Multiplatform** (not Flutter), with a Kotlin/Ktor server. **Desktop bundles the Java runtime** (OpenJDK, narrow licence exception, its source attached to each release). |
| 2026-10-05 | The owner added the app icon `media-sources/icon.png` (vacuum-tube "N", amber on navy) and IntelliJ project files (`.idea/`). |

## 3. Current state

- **`main`** holds the Android-only plan with every decision up to PO-35 (commit `ad7ead2`). One item there is stale: it still describes debuggable debug builds, which the replan corrects.
- **Branch `ccr-ac54917e-u0kl2v`, PR [#4](https://github.com/L-K-M/Neutrodyne/pull/4):** the **replan for Kotlin Multiplatform + desktop + sync server** is in progress. Checkpoint commits ("Replan checkpoint") are pushed to this branch regularly.
- Replan steps (the binding instructions for all of them are in §5):
  1. **Lead pass:** rewrite `docs/PLAN.md` for the new scope and write the change brief (it will be committed as `docs/research/briefs/change-brief-3-kmp.md`).
  2. **New docs:** write `docs/design/10-sync.md` and `docs/design/11-desktop.md`, each followed by an adversarial review.
  3. **Revise `01`–`09`** following the brief's per-doc checklists.
  4. **Two critics:** coverage/consistency and technical/licensing soundness.
  5. **A fixer** applies the critics' findings and rewrites `README.md` for the new scope.
  6. **Final checks:** the doc checkers, then push the branch and fast-forward `main` (the owner wants the plan on `main`). Open a PR when none is open; GitHub marks it merged once `main` contains the branch.

### Progress log

- 16:2x UTC: replan launched; lead pass running.
- ~17:0x UTC: lead pass done. `docs/PLAN.md` is rewritten and the brief is saved as [`docs/research/briefs/change-brief-3-kmp.md`](docs/research/briefs/change-brief-3-kmp.md).
- ~17:xx UTC: first drafts of `docs/design/10-sync.md` and `docs/design/11-desktop.md` are written. Their adversarial reviews are still pending.
- By 18:57 UTC: `01`–`05` are revised and `06` is in progress. Still to do: revise `07`, `08`, `09`; review `10` and `11`; the two critics; the fixer, which also rewrites `README.md`.
- 18:47 UTC: the automatic 10-minute checkpoint job reached its 2-hour limit and stopped. Checkpoints are now made by hand.
- ~19:1x UTC: `06` revised; revising `07` and `08`.
- ~19:4x UTC: `07` revised; revising `08` and `09`. Still to do: review `10` and `11`; the critics; the fixer.
- ~20:2x UTC: `08` revised; `09` is being revised and `10-sync.md` is under review. Still to do: review `11`; the critics; the fixer.
- ~20:4x UTC: one more of these finished (`09` revision or `10` review); `11-desktop.md` review started. Still to do: whichever of the `09` revision and `10` review is still running, the `11` review, the two critics and the fixer (which also rewrites `README.md`).
- ~21:2x UTC: all drafting and per-doc reviews are done (`PLAN.md`, `01`–`11`); both critics are running. Still to do: the fixer (which also rewrites `README.md`), then the final checks and publishing (§3 step 6).
- ~22:0x UTC: both critics are done; the fixer is applying their findings and rewriting `README.md`. Still to do: the final checks and publishing.

## 4. How to resume if this session stops

1. **Find where the replan stopped:** `git fetch && git log --oneline origin/ccr-ac54917e-u0kl2v` and `git diff --stat origin/main...origin/ccr-ac54917e-u0kl2v`. The progress log above and the newest checkpoint commits show which docs were already reworked.
2. **Get the brief:** read the binding decisions in §5 and, if it exists, `docs/research/briefs/change-brief-3-kmp.md`. If the brief is missing, write it first. Use the format of `change-brief-1/2` in the same folder: canonical names, D/PO/R/N/M changes, a checklist per doc, and outlines for `10-sync.md` and `11-desktop.md`.
3. **Finish the remaining steps** of §3 in order. Ground every claim in `docs/research/2026-10-05-kmp-desktop-sync/` and `docs/research/2026-10-05-stack-choice/`, or verify it on the web, and cite source URLs in the docs. Never link to the research notes from the plan docs.
4. **Run the checks:** `python3 tools/doccheck/checkdocs.py .` and the Mermaid checker. Both must report 0 problems.
5. **Publish:** commit, push the branch, then fast-forward `main` (`git push origin HEAD:main`, never force). If `main` moved, merge `origin/main` into the branch first; the owner sometimes pushes to `main` directly.
6. **Hand back:** update this file, then give the owner a short summary and the list of open PO questions.

## 5. Binding decisions for the replan (S0–S13)

These are the instructions the replan agents received. They hold for anyone continuing the work.

- **S0** v1.0 = Android + desktop (Windows, macOS, Linux) + self-hosted sync server, all together. The server syncs state only, never audio or feed contents; each client polls feeds itself.
- **S1** Kotlin Multiplatform + Compose Multiplatform; targets `android` and `jvm("desktop")`; no iOS (would need Apple registration) but keep `commonMain` clean. Sync server in Kotlin + Ktor, sharing protocol and merge code.
- **S2** Android stays native where it matters, with designs unchanged: Media3 MediaLibraryService, Android Auto, WorkManager + user-initiated jobs, Android 15–17 rules, Chaquopy-hosted yt-dlp in `:ytx`, Auto Backup.
- **S3** Libraries: Compose Multiplatform, Material 3 multiplatform, Navigation 3 (Google runtime + JetBrains UI), lifecycle/ViewModel KMP, Room 3 KMP with `BundledSQLiteDriver`, Paging, DataStore, Coil 3, kotlinx-serialization/datetime, Ktor client on OkHttp (or OkHttp in JVM-shared modules), DI Hilt → Metro (Koin fallback). AGP 9 `com.android.kotlin.multiplatform.library`; `:app` (Android) and `:desktopApp`; JVM-only code in plain-JVM modules.
- **S4** Desktop (unsigned, GitHub only):
  - **Platforms:** Windows 10+ x64/arm64, macOS 13+ Apple Silicon only (ad-hoc signed), Linux x64/arm64 (glibc ≥ 2.31).
  - **Formats:** MSI + ZIP, DMG, DEB + RPM + tar.gz.
  - **Build:** one CI job per OS/arch. jpackage rejects 0.x versions on macOS.
  - **Updates:** notify-only checks.
- **S5** **Bundle** a jlink-trimmed, unmodified OpenJDK. It is a narrow licence exception: attach the runtime's corresponding source to every release. No ProGuard (GPL).
- **S6** LGPL allowed. Desktop playback is our own engine:
  - minimal LGPL FFmpeg build;
  - miniaudio output;
  - a Kotlin Sonic port plus silence skipping;
  - an OkHttp streaming cache.

  libmpv is the fallback. OS integration uses SMTC (Windows), Now Playing (macOS) and MPRIS (Linux). Audio-only on desktop in v1.0.
- **S7** Desktop YouTube engine: python-build-standalone CPython (trimmed to permissive parts) running yt-dlp as a child process over JSON/stdio, with the same trust chain and update policy as Android.
- **S8** Desktop behaviour:
  - an in-process scheduler and a single-instance lock;
  - closing the window keeps the app running (tray) only while playing or downloading;
  - start at login off;
  - downloads in the app data directory;
  - the Linux screen-reader gap is accepted.
- **S9** Sync uses our own protocol v1:
  - **Merging:** per-field last-writer-wins ordered by hybrid logical clocks; changes pulled since a server cursor; tombstones.
  - **Identity and order:** stable podcast UUIDs; per-item order keys for Up next and groups.
  - **Safety rules:** a position of 0 never wins without an explicit reset or mark-played; a mass unsubscribe asks first.
  - **First link** uses the backup Merge rules.
  - **Server:** Ktor with SQLite, SSE push, admin-created accounts, devices linked with short codes, no end-to-end encryption in v1. Request Android 17's `ACCESS_LOCAL_NETWORK` only for a LAN server.
  - **Data:** synced: subscriptions, groups and their order and settings, per-episode state, Up next, podcast settings, portable globals. Basic-auth passwords sync only when the user opts in. Device-local: auto-download, storage, notifications, refresh, appearance.
- **S10** Server distribution: a JAR (admin installs Java 21) plus a multi-arch GHCR image (counts as GitHub) and a reference docker-compose. Same release tags as the apps.
- **S11** Android publishes optimised release builds (R8, non-debuggable) signed with the committed keystore. The debug build type is for development only. Retire the debuggable-build risks; the public-key spoofing risk remains.
- **S12** Brand: `media-sources/icon.png`, seed colours ≈ amber `#FF8C1A` and navy `#0B1B2E`. Adaptive and monochrome Android icons and desktop ICO/ICNS/PNG are derived from it.
- **S13** Licensing: own code Unlicense; no GPL/AGPL except the S5 runtime exception; LGPL allowed (dynamically linked, notices and source offered); MPL-2.0 for unmodified files and data. Every component is listed with its licence, and CI licence checks cover Gradle, Python and native desktop libraries.

## 6. After the replan

- **Owner questions:** collect the open PO questions from `docs/PLAN.md` §4 and ask the owner. Each already has a default.
- **Implementation:** start with **M0** (scaffold and CI) per `docs/PLAN.md` §7 and `docs/design/01-foundation.md` "M0 scaffold checklist". Build Kotlin-Multiplatform-structured from the first commit.
- **Effort:** about 58–97 engineer-weeks for everything in v1.0, roughly 13–22 months solo. This is a planning estimate from `docs/research/2026-10-05-stack-choice/kmp-fit.md`.
