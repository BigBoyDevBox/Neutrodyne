# Neutrodyne

Neutrodyne is a server-less, open-source Android podcast player organised around groups. A group such as "tech", "news" or "fiction" is a user-defined set of podcasts and YouTube channels, and every group is its own newest-first episode feed. Everything runs on the device: the phone polls feeds itself, with no Neutrodyne backend, no account and no tracking.

**Status: planning — no code yet.** The repository holds the master plan and nine design documents. Implementation starts with milestone M0 ([roadmap](docs/PLAN.md#7-roadmap)).

## Headline features

| ID | Feature | Plan |
|---|---|---|
| R1 | Import and export of subscriptions: tolerant OPML import with preview and report, grouped OPML export, NewPipe/LibreTube/Takeout import, full backup and restore, automatic Android backup (Android 9+ with a screen lock and a system backup transport such as Google backup or Seedvault) | [R1](docs/PLAN.md#21-functional-requirements), [05](docs/design/05-groups-opml-backup.md) |
| R2 | Groups, each with its own episode feed: many-to-many groups shown as swipeable tabs, with filters, counts and per-group defaults | [R2](docs/PLAN.md#21-functional-requirements), [05](docs/design/05-groups-opml-backup.md#group-feeds) |
| R3 | YouTube channels as podcasts: subscribe by link or handle; audio playback and downloads with the built-in yt-dlp engine (64-bit APKs), "Watch on YouTube" otherwise; the engine updates itself to verified yt-dlp releases without an app update | [R3](docs/PLAN.md#21-functional-requirements), [04](docs/design/04-youtube.md) |
| R4 | Streaming and downloading: background player with a database-owned queue, resumable downloads, auto-download and cleanup | [R4](docs/PLAN.md#21-functional-requirements), [06](docs/design/06-playback.md), [07](docs/design/07-downloads.md) |
| R5 | A cover-first UI: adaptive cover grid, group mosaics, artwork-derived colour, offline artwork for system surfaces | [R5](docs/PLAN.md#21-functional-requirements), [08](docs/design/08-ui-ux.md) |
| R6 | Installing and updating from GitHub Releases only: per-ABI APKs (debug builds signed with a public key) with checksums and attestations; a daily update check that notifies and links to the new release; guidance for Android's developer verification | [R6](docs/PLAN.md#21-functional-requirements), [09](docs/design/09-quality-and-release.md#update-check) |

## How the documents are organised

[docs/PLAN.md](docs/PLAN.md) is the single source of truth. It holds the requirement IDs (R1–R6, N1–N12), the decision register (D-ids), the product-owner decisions with their defaults or resolutions (PO-ids), the roadmap with acceptance criteria per milestone (M0–M11) and the risks. Where a design document and the plan disagree, the plan wins until it is amended.

The design documents elaborate the plan; each owns its area and links to the others instead of restating them:

| Document | Covers |
|---|---|
| [01-foundation.md](docs/design/01-foundation.md) | Toolchain and version catalog, modules and dependency rules, architecture patterns, DI, build variants and ABIs, networking baseline, platform compliance, licensing policy (Gradle and Python components), M0 scaffold and spikes |
| [02-data-model.md](docs/design/02-data-model.md) | Room 3 schema, identity keys, indices, key queries, invalidation hygiene, retention, migrations |
| [03-feeds-and-discovery.md](docs/design/03-feeds-and-discovery.md) | Feed parser, fetch pipeline, ingestion, refresh scheduling, show notes, add-podcast flow, search, deep links, notifications |
| [04-youtube.md](docs/design/04-youtube.md) | Capability matrix, channel resolution, Atom ingestion, the yt-dlp engine, stream resolution, playback and download integration, engine updates, licensing and hotfix process |
| [05-groups-opml-backup.md](docs/design/05-groups-opml-backup.md) | Groups, group feeds, effective settings, OPML import and export, backup and restore, Auto Backup |
| [06-playback.md](docs/design/06-playback.md) | Media3 service, URI resolution, streaming cache, queue and play context, positions, sleep timer, chapters, system surfaces |
| [07-downloads.md](docs/design/07-downloads.md) | Download engine, state machine, runners, storage layout, auto-download, cleanup, reconciliation |
| [08-ui-ux.md](docs/design/08-ui-ux.md) | Information architecture, navigation, screens, player sheet, theming, artwork pipeline, adaptive layouts, accessibility |
| [09-quality-and-release.md](docs/design/09-quality-and-release.md) | Testing, CI, static analysis, versioning and signing (debug keystore), GitHub-only distribution, update check (notify only), developer verification, reproducibility check (report-only), privacy, crash reporting, performance budgets |

## Install and update

Neutrodyne is distributed **only through GitHub Releases** — there is no Google Play, F-Droid or other store version, and any other copy is not Neutrodyne's. Nothing is released yet; the first tester build comes with milestone M0. In short ([full guidance](docs/design/09-quality-and-release.md#readme-install-and-update), [developer verification](docs/design/09-quality-and-release.md#developer-verification)):

1. **Download** from the repository's releases page: `neutrodyne-{version}-arm64-v8a.apk` for most phones and tablets, `-x86_64.apk` for x86_64 devices, `-armeabi-v7a.apk` for older 32-bit phones (YouTube episodes then open in the YouTube app).
2. **Check it (optional):** Neutrodyne's APKs are debug builds signed with a key that is public in this repository (`signing/neutrodyne-debug.keystore`), so the signature doesn't prove who built a file — download only from this repository's releases page and compare the file with the release's `SHA256SUMS`, or run `gh release verify-asset` and `gh attestation verify`, for example:

   ```sh
   sha256sum --check --ignore-missing SHA256SUMS
   gh release verify-asset v{version} neutrodyne-{version}-arm64-v8a.apk -R {owner}/Neutrodyne
   gh attestation verify neutrodyne-{version}-arm64-v8a.apk -R {owner}/Neutrodyne
   ```

3. **About these builds:** the maintainers publish debug builds instead of release builds. For you this means: anyone can sign an app with the public key that installs over Neutrodyne like an update and takes over its data, so download Neutrodyne only from this repository's releases page; a computer that your unlocked phone has allowed to use USB or wireless debugging can read and change Neutrodyne's data, including your subscriptions, listening history and the passwords of private feeds, so turn USB debugging off when you don't need it; scrolling and start-up are less smooth than a release build would be; and if Neutrodyne switches to release builds later, you will need to uninstall and reinstall it once — make a backup first (Settings › Backup). Details: [public-key trade-offs](docs/design/09-quality-and-release.md#public-key-trade-offs), [D61](docs/PLAN.md#3-key-decisions).
4. **Allow the install** when Android asks whether your browser or Files app may install apps.
5. **Phones with Google Play, from Google's global rollout in 2027:** Android will install apps only from developers registered with Google unless you turn on a one-time setting, and Neutrodyne is not registered. Turn on Developer options › "Allow apps from unverified developers", follow the steps (restart, 24-hour wait, fingerprint or PIN) and choose **"indefinitely"** — with "7 days", updates stop working after a week. Each install or update then shows a warning; tap "Install anyway". Nothing changes before Google's global start. Phones without Google certification (GrapheneOS, LineageOS without Google apps, /e/OS) need none of this; `adb install -r` and Shizuku-based installers are power-user fallbacks that Google may close — turn USB debugging off again after an `adb install`.
6. **Updates:** Neutrodyne checks GitHub once a day and, when a new version exists, notifies you and links to it (Settings › Updates › Check for updates, on by default; "Check now" works even when it is off). Its update card offers "Open release on GitHub" and "Download APK for this device" and shows that APK's SHA-256; download the APK in your browser and install it over the old one — your library stays. Neutrodyne never downloads or installs an update itself. Or use [Obtainium](https://github.com/ImranR98/Obtainium) with an APK filter for your file (e.g. `neutrodyne-.*-arm64-v8a\.apk$`) and turn Neutrodyne's own check off. YouTube engine updates arrive separately and need no install.
7. **Changing phones:** make a manual backup (Settings › Backup) and restore it on the new phone; Android may restore your library when you install Neutrodyne there, but that is not guaranteed for apps installed outside a store ([R1.8](docs/PLAN.md#21-functional-requirements)).
8. **Android Auto:** enable "Unknown sources" in Android Auto's developer settings.

The same guidance is in the app under Settings › About › Install & updates.

## Licence

The repository — own code, Python shim and documentation — is released into the public domain under the [Unlicense](LICENSE). Every shipped APK and every YouTube-engine update contains only Unlicense code and permissively licensed third-party components; **no GPL, LGPL or AGPL code anywhere** ([D3](docs/PLAN.md#3-key-decisions), [PO-1](docs/PLAN.md#po-1-licensing-of-shipped-binaries), resolved 2026-10-05). Licensee, a Python-component lockfile and an APK content scan enforce this in CI.

Bundled in the 64-bit APKs as the YouTube engine: yt-dlp with yt-dlp-ejs (Unlicense; the solver adds meriyah, ISC, and astring, MIT); CPython (Python-2.0, the PSF licence stack) with OpenSSL (Apache-2.0), SQLite (public domain), libffi, expat, mimalloc and HACL* (MIT), mpdecimal (BSD-2-Clause), zstd (BSD-3-Clause), xz (0BSD), bzip2, zlib and the Unicode Character Database extract (Unicode-3.0); the Chaquopy runtime (MIT; `libc++_shared` Apache-2.0 WITH LLVM-exception); a CA certificate bundle (MPL-2.0, unmodified data); and quickjs-kt with QuickJS (Apache-2.0, MIT) if the JS challenge provider ships. Gradle dependencies follow the Licensee allow-list ([01 Licensing and dependency policy](docs/design/01-foundation.md#licensing-and-dependency-policy)). The app's Licences screen and `THIRD_PARTY_NOTICES.md` list every component with its full licence text.
