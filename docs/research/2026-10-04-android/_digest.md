# Research digest (summaries of each research note, 2026-10-04)

## stack

I've written the foundation research notes to `/tmp/claude-0/-home-user-Neutrodyne/12c40d4e-a2e3-573a-89e6-30bb02f324e2/scratchpad/research/stack.md`. Versions were checked on 2026-10-04 against Maven metadata and official docs.

**Recommendations:**
- **Build:** Kotlin 2.4.20, AGP 9.4.1 with built-in Kotlin (no kotlin-android, no kapt), Gradle 9.7.1, KSP 2.3.12.
- **UI and navigation:** Compose BOM 2026.09.00 with Material 3 1.4.0 stable, plus Material 3 Adaptive 1.3.0. Navigation 3 1.2.0.
- **Libraries:** Hilt 2.60.1, Room 3.0.3 with the bundled SQLite driver, Preferences DataStore, OkHttp 5.5.0, kotlinx.serialization, Coil 3.6.3 and coroutines 1.11.0.
- **Feed parsing:** a hand-written XmlPullParser parser in a pure-JVM `:feeds` module.
- **Architecture:** MVVM/UDF with use cases only where logic spans several repositories.
- **SDK levels:** minSdk 26, compileSdk 37 (Compose 1.12 requires it), targetSdk 37. Play's floor is 36.
- **Modules:** separate api/impl modules for playback, download and youtube.

**Risks:**
- Material 3 Expressive exists only in an alpha artifact (1.5.0-alpha29). Using it at launch means accepting API churn.
- Kotlin 2.4.20 is officially tested only up to AGP 9.3.1. If the build has problems, fall back to AGP 9.3.3.
- Android 17 requires background audio to run from a foreground service started by the user. Auto-play on headphone connect and resume after reboot can't be built the naive way.
- Android 16 now counts jobs that run alongside a foreground service against quota, so WorkManager downloads during playback can be stopped.
- NewPipeExtractor is GPL-3.0. Using it makes the shipped APK GPL; that is legal with the Unlicense but is a product owner decision.

## playback

The playback notes are written to `/tmp/claude-0/-home-user-Neutrodyne/12c40d4e-a2e3-573a-89e6-30bb02f324e2/scratchpad/research/playback.md`.

**Recommendations:**
- Use Media3 1.11.1, the current stable release (2026-09-10; minSdk 23).
- Build one `PlaybackService : MediaLibraryService`, not a plain `MediaSessionService`. It covers Android Auto and Bluetooth browsing, and only the library variant shows the System UI resume card after a reboot.
- Configure one ExoPlayer with speech audio attributes, audio-focus and headphone-unplug handling, a network wake lock while streaming, and a custom audio chain for boost, skip silence and speed.
- The database owns the queue; the ExoPlayer playlist holds only the current item, "Up next" and about 20 items of the playing group.
- Episodes get stable `neutrodyne://` URIs, resolved each time a connection opens, so downloads play transparently and expired YouTube URLs refresh themselves.
- Streams go through a disk cache (default 500 MB) kept separate from downloads.
- Speed, skip silence and boost are stored per episode, podcast and group.
- The notification shows skip-back and skip-forward in the main slots.

**Risks that could change the plan:**
- **Android 17 mutes background audio silently.** Playback must start from visible UI, a notification tap or a media key; features like "auto-play when a download finishes" won't work.
- **Since 1.11, third-party controller apps are read-only by default** (a product decision).
- **YouTube video needing separate video and audio streams** would require a custom lazy media source.
- **Chromecast and F-Droid conflict.** Cast needs Google Play services, so it can't ship in F-Droid builds, and Android Auto shows non-Play installs only behind a developer toggle.
- **Dynamic ad insertion** means positions may not carry over between stream and download.

## feeds

I've written the research notes to `/tmp/claude-0/-home-user-Neutrodyne/12c40d4e-a2e3-573a-89e6-30bb02f324e2/scratchpad/research/feeds.md`.

**Key recommendations:**
- **Parser:** write our own streaming XmlPullParser, matching tags by namespace with relaxed mode and HTML entities predefined. prof18 RSS-Parser 6.1.8 is Apache-2.0 and maintained, but has no Podcasting 2.0 support and keeps only one enclosure. Recognise both Podcasting 2.0 namespace URIs; the reference feed uses the GitHub alias.
- **Fetching:** OkHttp 5.5.0 with our own ETag / Last-Modified handling and a body hash, not OkHttp's Cache. This contradicts stack.md, which proposes the Cache. Send a specific User-Agent: one feed host returns 403 to generic ones.
- **Episode identity:** guid, then enclosure URL, then title + date, with fallback matching when a host rewrites GUIDs. User state lives in a separate table so refreshes never overwrite it.
- **Sorting:** a computed `sortDate` keeps group feeds in order when dates are missing or in the future.
- **Refresh:** one WorkManager 2.12.0 periodic job, with each feed tracking its own next-refresh time and backoff.
- **Discovery:** Apple search by default (no key, about 20 calls/min), fyyd, and Podcast Index only when a key is available. Skip gpodder.net; its data is a decade stale.

**Risks:**
- Podcast Index's terms forbid embedding keys in open-source projects. We need their permission or a bring-your-own-key setting.
- Apple's search terms don't explicitly license third-party directory use.
- On Android 17 (targetSdk 37), feeds on the local network need `ACCESS_LOCAL_NETWORK`, or connections just time out.
- From Android 16, background refresh time is quota-limited, including during playback.

## downloads

The download and storage research notes are written to `/tmp/claude-0/-home-user-Neutrodyne/12c40d4e-a2e3-573a-89e6-30bb02f324e2/scratchpad/research/downloads.md`.

**Recommendation:** build our own small download engine on OkHttp 5.5.0. It writes real files: `.part` file, resumed with HTTP Range and checked before finishing. The database is the only source of download state; a throttled in-memory flow feeds progress bars.

**How downloads run:**
- **User taps Download, Android 14+:** a user-initiated transfer job. It has no time quota, but can only be scheduled while the app is visible.
- **User taps Download, API 26–33:** WorkManager 2.12.0 expedited work with a dataSync foreground service.
- **Auto-downloads:** ordinary WorkManager jobs that stop themselves at about 8 minutes and continue later. Frequent system timeouts can push the app into the restricted standby bucket.

**Rejected:** Media3 1.11.1's DownloadService (files can't be copied, one network rule for all downloads, restarts blocked in the background) and the system DownloadManager (can't write to a user-chosen folder or control resume).

**Storage:** default is the app's own external folder. An optional user-chosen folder lets other apps see the files and survives uninstall. Downloads must be excluded from Auto Backup, or the 25 MB cap disables all cloud backup.

**Risks that could change the plan:**
- **Android 16 quotas:** background downloads now use up the app's job quota, so auto-downloads on slow Wi-Fi will be slower.
- **YouTube changes often:** stream URLs expire after about 6 hours and are reportedly tied to the IP. Proof-of-origin tokens and YouTube's newer streaming protocol mean frequent extractor updates.
- **Google Play:** YouTube downloading likely violates Play policy, so the distribution channel needs deciding. The dataSync service also needs a Play Console declaration with a video.

## youtube

The research notes are in `/tmp/claude-0/-home-user-Neutrodyne/12c40d4e-a2e3-573a-89e6-30bb02f324e2/scratchpad/research/youtube.md`.

**Key recommendations:**
- **Two layers:**
  - Subscriptions in every build (Unlicense code): list episodes from YouTube's public Atom feed using `playlist_id=UULF…`, which I tested to exclude Shorts and live streams, and use the channel avatar as the cover.
  - Playback and download in a `foss` build only: NewPipe Extractor v0.26.5 (GPL-3.0-or-later) in its own module, so that APK is GPL while the repo stays Unlicense.
- **Play build:** YouTube episodes only open in the YouTube app. Background audio and downloads would break Play and YouTube rules; NewPipe says any fork on Play violates Play's terms.
- **Media3:** episodes use a `yt://` address that is turned into a real stream URL at play time. URLs last about 6 hours and are tied to the device's IP, so re-fetch on HTTP 403. Download in 10 MiB chunks and prefer the original audio track.
- **No YouTube Data API in the `foss` build**; in the Play build it is optional.

**Risks that could change the plan:**
- If YouTube audio must work on Play, the requirement can't be met as stated.
- Both NewPipe Extractor and yt-dlp currently rely on one undocumented YouTube client (`VISIONOS`), so outages of days are likely.
- Legal precedents: YouTube's 2023 takedown demand to Invidious, and Podcini stopping in 2025.
- Android developer verification starts 2026-09-30 in four countries and goes global in 2027, affecting sideloaded builds.
- The Atom feed has had hours-long 404 outages since December 2025.

## opml-groups

The research notes are written to `/tmp/claude-0/-home-user-Neutrodyne/12c40d4e-a2e3-573a-89e6-30bb02f324e2/scratchpad/research/opml-groups.md`, with sources and dates for every version and platform claim.

**Recommendations:**
- **Groups:** a podcast can be in many groups. Tables `podcast_group`, `podcast_group_member` and `podcast_group_settings`; "All" and "Ungrouped" are virtual feeds, not rows.
- **OPML export:** each feed is written once, inside the folder of its first group, with a `category` attribute listing all its groups. Gives full round-trip in Neutrodyne, no duplicates elsewhere; FreshRSS mangles flat `category` lists.
- **OPML import:** tolerant parser (strict, then relaxed, then regex salvage), then a preview with group mapping. Podcasts are added at once and the refresh engine fetches them, reporting each failure.
- **Full backup:** a versioned ZIP of JSON plus OPML, keyed by feed URL and episode identity key.
- **Auto Backup:** back up only a daily snapshot ZIP and the portable settings file, never the Room database. Downloads in external files would break the 25 MB cap and cancel the whole backup.
- **Group feeds:** Room's LIMIT/OFFSET paging behind one query builder.

**Risks that could change the plan:**
1. Paging re-runs a full count and page query on every write to a joined table. Playback position and download progress must move to separate tables.
2. The feeds area's episode identity key becomes part of the backup format.
3. Import and restore must suppress notifications and auto-downloads, or they flood the user.
4. `@RawQuery` returning a `PagingSource` is unconfirmed in Room 3.
5. Room 3.0.x has no built-in UUID column support.
6. Podcast Addict's export format and Apple Podcasts on iOS 26 are unconfirmed.

## ui

I wrote the UI/UX research notes to `/tmp/claude-0/-home-user-Neutrodyne/12c40d4e-a2e3-573a-89e6-30bb02f324e2/scratchpad/research/ui.md`.

**Recommendations:**
- **Design system:** stable Material 3 1.4.0 (Compose BOM 2026.09.00). No Material 3 Expressive at launch: it exists only in material3 1.5.0-alpha29, which also pulls Compose core 1.13.0-alpha01 (checked in its POM).
- **Navigation:** `NavigationSuiteScaffold` with five destinations: Feeds, Library, Up next, Downloads, Discover. Navigation 3 handles phone and tablet list-detail layouts.
- **Switching groups:** scrollable tabs plus a swipeable pager. Chips only filter within a group.
- **Library:** adaptive cover grid.
- **Player:** one draggable sheet at the app root; the mini player morphs into the full player.
- **Colour:**
  - Wallpaper colours on Android 12+.
  - Player and podcast header tinted from the artwork's seed colour, computed once in the background and saved.
  - Uses MaterialKolor's pure-Kotlin colour library (5.0.1), not androidx Palette.
- **Artwork:**
  - Coil 3.6.3 with two cache tiers so cover transitions don't flash.
  - A permanent local artwork store for offline art, shared with lock screen, Auto and widgets.
  - Generated initials covers for feeds without art.
  - YouTube thumbnail fallback chain (verified live).

**Risks:**
1. Shipping Expressive puts the whole Compose stack on alpha.
2. Swiping between groups conflicts with swipe actions on episode rows; row swipes are off in Feeds by default.
3. Rows need a separate live-state feed, because list queries can't join playback-position or download-progress tables.
4. Lock-screen/Auto art should come from the artwork store, not Coil's cache as `playback.md` proposes.
5. Android 17 caps bitmap memory in widgets, and playback started from a widget is unverified.

## quality

Research notes are written to `/tmp/claude-0/-home-user-Neutrodyne/12c40d4e-a2e3-573a-89e6-30bb02f324e2/scratchpad/research/quality.md`.

**Recommended stack:**
- **Tests:** JUnit 4 with TestParameterInjector, Turbine, coroutines-test and Truth, using fakes rather than MockK. Room 3 schema export with a drift check, plus MigrationTestHelper. Golden-file corpus tests for feeds, OPML and imports. MockWebServer 5.5.0, Robolectric 4.17, the Compose v2 test rules and Roborazzi 1.76 (Paparazzi 2.0 and Google's screenshot tool are still alpha). Media3 test-utils for playback.
- **Instrumented tests:** Gradle Managed Devices on KVM-enabled GitHub Linux runners.
- **Static analysis:** Lint, Spotless/ktlint and Licensee. detekt 2.0-alpha runs but does not block.
- **Tooling:** Renovate, ACRA by email only, Weblate.
- **Release:** a baseline profile, R8 with `-dontobfuscate`, and one developer-held signing key. F-Droid should publish our own signed APK after checking it rebuilds identically.

**Risks that could change the plan:**
1. Google developer verification has been enforced in BR/ID/SG/TH since 2026-09-30, with global rollout planned for 2027. Without registration (ID, $25), every sideloaded install requires a 24-hour unlock.
2. The Play build cannot ship YouTube extraction or downloads, and must not point users to the foss APK.
3. NewPipeExtractor makes the foss APK GPL-3.0.
4. Byte-for-byte reproducibility (baseline.prof, R8) is not guaranteed. If it fails, F-Droid signs with its own key.
5. F-Droid updates take days, too slow for YouTube hotfixes.
6. BundledSQLiteDriver likely won't load under Robolectric; this needs checking in a spike.

## prior-art

I wrote the prior-art notes to `/tmp/claude-0/-home-user-Neutrodyne/12c40d4e-a2e3-573a-89e6-30bb02f324e2/scratchpad/research/prior-art.md`. Sources were AntennaPod, Pocket Casts, NewPipe, LibreTube and Podcini code, plus docs and forums, all cited.

**Recommendations:**
- **Groups as their own feeds** are the main differentiator. Groups should be many-to-many (a podcast can be in several) and free. AntennaPod's "episodes by tag" request has been open since 2021; Pocket Casts folders are paid, one-per-podcast, and can't be used in Smart Playlists. Model it on NewPipe's channel groups, keyed by UUID.
- **OPML should keep groups.** Export them in the standard `category` attribute; import nesting and `category`, with a lenient fallback for malformed files. Neither AntennaPod nor Pocket Casts keeps groups.
- **Build on Media3 and Room from the start.** AntennaPod's 2026 move to Media3 caused regressions, including lost playback positions. Podcini's switch to Realm (under a new app ID) forced users to reinstall.
- **Download and refresh:** user-started downloads use Android 14+ user-initiated jobs, because Android 16 limits background jobs that run during playback. Refresh caps parallel fetches per host and stores ETag and Last-Modified separately.
- **Database:** prune old episodes and back up automatically. AntennaPod users report 80–364 MB databases and data loss.

**Risks that could change the plan:**
- **YouTube:** YouTube's API policies forbid background play and downloads. Podcini dropped YouTube in Jan 2025 over legal concerns; NewPipe, LibreTube and Tubular stay off Google Play, and Tubular shut down in Jul 2026.
- **Licence:** the YouTube extraction library (NewPipeExtractor) is GPL-3.0, which conflicts with the Unlicense.
- **Distribution:** the product owner must choose Play Store, F-Droid or both before YouTube work starts.

