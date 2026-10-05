# Prior art & feature expectations — research notes for Neutrodyne

Research date: 2026-10-04. Sources were checked on that date unless noted otherwise.
Method: I read the source of AntennaPod (`develop` @ 9c7ffa1, 2026-10-04), Pocket Casts Android (`main` @ 51b9b05, 2026-10-02), LibreTube (`master` @ b265e2d, 2026-10-04), NewPipe (`dev` @ 7e5df38, 2026-09-26, sparse checkout of `database/` and `local/feed/`) and Podcini (`main` @ 93ef788, 2025-01-15), all from shallow clones. I also read release notes, official docs, forum threads and GitHub issues. Code citations point to GitHub paths on those branches.

---

## Recommendation

**Overall position:** build Neutrodyne as a *distributed* podcast app, meaning the device polls the feeds itself and no Neutrodyne server exists. AntennaPod works this way and Pocket Casts does not. Use AntennaPod's feature model as the "table stakes" baseline, and Pocket Casts' engineering stack and UX polish as the bar (Kotlin, Room, Media3, WorkManager, Compose, cover-first grid). Take the group data model from NewPipe/LibreTube "channel groups", where a group is a many-to-many set of sources with its own feed. That is the one place where every mainstream podcast app is weak, and it is the requirement the product owner (PO) singled out.

Concrete choices, each tied to evidence below:

1. **Groups are many-to-many and free, and each group has its own episode feed.** Use a join table `podcast_group_member(groupId, podcastId)` like NewPipe's `feed_group_subscription_join`. Do not use AntennaPod's single delimited `tags` TEXT column, and do not use Pocket Casts' single `folder_uuid` per podcast. Each group gets:
   - a feed screen (newest-first episodes of all member podcasts and YouTube channels, with filters),
   - a "new" badge,
   - a "refresh this group" action,
   - "play all / add to queue",
   - optional per-group auto-download and notification defaults.

   Users have asked for this for years. AntennaPod issue #5222, "Add tag filter to Episodes screen", has been open since 2021-06-13, and in Oct 2025 a maintainer said it is "on the (long) list". On the Pocket Casts forums, users asked for "folders in filters" (2023 to Feb 2026) and for "recent episodes of each folder" (Jul 2025). Pocket Casts' own docs say Smart Playlists cannot filter by folder, and folders require Plus. This is Neutrodyne's clearest differentiator.
2. **Groups survive an OPML round trip.** On export, write a flat OPML 2.0 list. Each `<outline type="rss">` carries a `category="tech,news"` attribute, which is the OPML 2.0 spec's own way to express tags. Offer an optional "nested folders" export for feed readers. On import, map both nesting and `category` to groups. Neither AntennaPod nor Pocket Casts exports groups or folders (both writers are flat), and AntennaPod's reader ignores nesting.
3. **The OPML importer tolerates malformed files.** Try a strict XmlPullParser first. If it fails, fall back to a lenient `xmlUrl=` scanner, as Pocket Casts does in `OpmlUrlReader.kt`. Google Podcasts' export was malformed and AntennaPod rejected it with "Unterminated entity ref".
4. **Feed refresh copies AntennaPod's scheduler shape and avoids its weak spots:**
   - an hourly periodic WorkManager job that skips feeds refreshed within the user's interval,
   - feeds shuffled so a cancelled run is still fair,
   - about 4 parallel fetches,
   - permanent redirects and `itunes:new-feed-url` persisted.

   Differences from AntennaPod:
   - store `ETag` and `Last-Modified` in separate columns (AntennaPod conflates them),
   - cap concurrency per host (KDE Kasts 26.04 moved to sequential per-server updates "to avoid triggering server limits"),
   - give YouTube channels a cheap two-phase refresh (see 9).
5. **Downloads use the 2026 platform primitives from day one:**
   - user-initiated (manual) downloads use user-initiated data transfer (UIDT) jobs on API 34+, falling back to a WorkManager foreground `dataSync` worker on older versions;
   - auto-downloads use WorkManager with constraints.

   Android 16 makes jobs that run while the app is visible, or alongside a foreground service, subject to runtime quotas. A podcast app is almost always running a media-playback foreground service, so this bites. Also:
   - resume with `Range` plus `If-Range`,
   - write to a temp file and rename it on success,
   - reject small `text/*` bodies (captive-portal pages saved as `.mp3`),
   - add a "reconcile files vs DB" worker (Pocket Casts has `FixDownloadsWorker`).
6. **Playback uses a Media3 `MediaLibraryService` from the first commit:**
   - one ExoPlayer playlist that mirrors the queue, so auto-advance never leaves the foreground state,
   - position saved every ~5 s and on every pause, seek or transition,
   - a "never overwrite a non-zero position with 0 unless the user asked" guard.

   AntennaPod needed a "major playback service rewrite" in 3.12.0 (Aug 2026) to reach Media3. The 3.12.0 beta regressions and the 3.12.1/3.12.2 fixes included position resets, car-audio looping, widget, notification and button-remap bugs. Podcini hit `ForegroundServiceStartNotAllowedException` when auto-advancing in the background (issue #88).
7. **The database is Room on SQLite, never a niche store, and never changes the applicationId.**
   - Podcini moved SQLite to Realm in v6 under a new applicationId ("Podcini.R") with no DB migration path. Realm's vendor then deprecated the Atlas Device SDKs (Sept 2024).
   - Keep heavy HTML show notes out of the hot episode table.
   - Use a retention policy for episodes that dropped out of the feed. AntennaPod users report 52,000 items / 80 MB and 364 MB databases with CPU/battery symptoms, and issue #4426, "Remove episodes no longer in the RSS feed", has been open since 2020.
   - Back up automatically: periodic full-DB export plus an Android Backup agent for OPML, which AntennaPod has. AntennaPod has a whole help page and a recovery tool for corrupted databases.
8. **Covers are stored and processed locally.** Pocket Casts gets pre-resized WebP artwork and server-computed palette colours from its own CDN. Neutrodyne has no server, so it must:
   - download the publisher artwork (often 1400 to 3000 px),
   - downsample on decode,
   - cache a thumbnail,
   - compute a palette once at subscribe/refresh time and store it in the DB.

   For group tiles, copy Pocket Casts' folder tile: a colour background plus a 2×2 mosaic of the first four covers.
9. **YouTube is a separately isolated "source type" with explicit risk handling.**
   - Refresh in two phases. First fetch the cheap channel RSS (`/feeds/videos.xml?channel_id=…`, 15 newest items). Do the expensive extraction only when that shows something new: LibreTube's `LocalFeedRepository` pattern, with NewPipe-style batching (3 parallel, a 6–12 s random pause every 50 channels).
   - Audio stream resolution and downloading are the high-risk part:
     - Podcini stopped all YouTube work on 2025-01-13 "to avoid suspicions and allegations of possible violation of some legal terms".
     - NewPipe, LibreTube and Tubular are not on Google Play; Tubular was discontinued in Jul 2026.
     - The YouTube API Developer Policies forbid background play, separating audio, and download/caching.
     - Extraction is an arms race (SABR enforcement, PO tokens).
   - The plan must let the PO choose the distribution channel before this feature is built (see Open questions).
10. **Licence hygiene.** Neutrodyne is Unlicense (public domain).
    - AntennaPod, Podcini, LibreTube, NewPipe and NewPipeExtractor are GPL-3.0, and Pocket Casts is MPL-2.0. Use them for ideas and behaviour only, and copy no code.
    - Shipping NewPipeExtractor inside the APK would put the distributed binary under GPL-3.0 terms. That is a PO decision.

### Differentiation opportunities (ranked by evidence of unmet demand)

1. **Group = feed.** Every group is its own newest-first episode stream with badges, filters, refresh, play-all and per-group automation defaults. It is free and supports multi-membership.
   - Unmet in AntennaPod (#5222, open 5+ years).
   - Unmet in Pocket Casts: folders are single-membership and paid, and Smart Playlists cannot select folders, per their docs and the 2023 to 2026 forum requests.
   - Proven in the YouTube-client world (NewPipe, LibreTube channel groups).
2. **YouTube channels and podcasts in the same group feed**, e.g. "tech" mixes podcasts and channels. No current mainstream Play-distributed podcast app does this credibly: Podcini left YouTube, and the Podcast Addict listing no longer advertises it. This is gated on the policy decision.
3. **Lossless group round trip through OPML** using the standard `category` attribute plus optional nesting. AntennaPod and Pocket Casts both flatten.
4. **Migration-friendly import:**
   - malformed OPML (Google Podcasts),
   - NewPipe/LibreTube JSON,
   - YouTube Takeout CSV,
   - AntennaPod and Pocket Casts OPML with their quirks: AntennaPod is flat with `title`/`text`; Pocket Casts wraps everything in a `text="feeds"` parent, which must **not** become a group called "feeds".
5. **Cover-first group UI**, borrowing Pocket Casts' colour plus 2×2 mosaic folder tile, with palettes computed locally.
6. **Modern-platform reliability from day one:** Media3, UIDT downloads, awareness of Android 15/16 FGS and job quotas, automatic backups, retention policy. These are areas where AntennaPod is still paying down debt.

---

## Options considered

### A. How to model "groups"

| Option | Who does it | How it's stored | Per-group episode feed? | User sentiment | Verdict |
|---|---|---|---|---|---|
| A1. Tags as a delimited string on the podcast row | AntennaPod (tags appear as drawer folders and, since 3.10.2 in Nov 2025, as chips above the subscriptions grid) | `Feeds.tags TEXT`, joined with `\u001e` (`FeedPreferences.TAG_SEPARATOR`); special values `#root`, `#untagged` | **No.** Only filters the *subscriptions* grid and drawer. Issue #5222 has been open since 2021. Docs: "it is not possible to create queues based on tags" | Users want folders and per-tag episode views. A Jan 2025 forum thread complains that tags show up as navigation items in both the drawer and the subscriptions tab. | Reject: not queryable relationally, no per-group feed, renaming a tag means rewriting every feed row (`TagMenuHandler` loops feeds) |
| A2. Single-membership folder | Pocket Casts (`Folder` entity + `Podcast.folder_uuid`; colour, sort position, sort type) | One FK per podcast | **No.** Folder is a container on the Podcasts page; the "recent episodes in folder" request was answered with "sort podcasts by release date" (Jul 2025) | Plus-only. Users ask for tags/multi-membership (Feb 2026 post) and for folders inside Smart Playlists (2023 to 2026, still not available) | Reject single membership; borrow folder colour, sort and mosaic tile UX |
| A3. Rule-based playlists ("Smart Playlists", formerly Filters) | Pocket Casts (free) | `PlaylistEntity` with a comma-separated `podcastUuids` plus status/date/duration/download/media-type/starred rules; per-playlist auto-download | Yes, but it is a *separate object* from folders, so users maintain two parallel structures | Liked, but "I manually maintain filters that mirror my folders" is the top complaint | Borrow the rule/filter vocabulary *inside* group feeds. Make "smart groups" a v2 idea. |
| A4. Category filter on episode lists | Podcast Addict (FAQ: episode lists such as Downloaded and Favorites have a drop-down filtering by category; tap to switch to per-podcast) | Not inspectable (closed source) | Partial: filters existing lists rather than giving a group its own home | Not verified | Shows that filter-by-group on every list is expected |
| A5. Many-to-many group + group feed | NewPipe "channel groups" (0.19.0+), LibreTube "channel groups" | NewPipe: `feed_group` + `feed_group_subscription_join` (composite PK, FK cascades); per-group feed query; per-group refresh of outdated subscriptions. LibreTube: `subscriptionGroups` table keyed **by name**, with a list of channel IDs | **Yes.** This is the core UX of those apps | Popular in the YouTube-client world | **Adopt** (with UUID keys, not names) |
| A6. Nested hierarchies ("volumes") and synthetic podcasts | Podcini.X | Realm objects | Yes (synthetic podcasts can shelve episodes) | Niche, power-user | Out of scope for MVP; leave room for `parentGroupId` later |

### B. Where feed polling happens

| Option | Example | Pros | Cons | Verdict |
|---|---|---|---|---|
| Central server polls RSS; client calls one endpoint | Pocket Casts: `POST /user/update` with the podcast list (`ServiceManager.kt`); server-side OPML resolution (`POST import/opml`, then polling) | One request per refresh, fast new-episode detection, server-resized artwork and colours, back-catalogue survives feed truncation | Needs to run a backend forever, holds user data, private feeds leak credentials to the operator | Not viable for an Unlicense hobby/OSS app without a backend budget |
| Distributed: device polls every feed | AntennaPod (default every 12 h, per its docs), Escapepod, Kasts, Podcini | No server, private, works with authenticated feeds | Battery/data cost scales with subscriptions; misses episodes removed from short feeds | **Adopt**, with conditional GET, per-host limits and shuffling |
| Hybrid (third-party index for discovery only) | AntennaPod and Podcini search iTunes, Podcast Index and fyyd (`net/discovery`) | Search without a server | API keys (Podcast Index), privacy note | Adopt for *search only* |

### C. Storage engine

- **Raw SQLite with hand-written cursors** (AntennaPod `PodDBAdapter`, DB version 3110000). It works, but carries schema debt: one column name `downloaded` is reused for two meanings, and an `IN` operator cap of 800 IDs is hard-coded.
- **Room** (Pocket Casts at schema version 139 with 180 `MIGRATION_` references; NewPipe; LibreTube). The mainstream choice.
- **Realm** (Podcini 6+): forced a breaking reinstall and was then deprecated by its vendor (Sept 2024 per MongoDB).
- **Verdict:** Room, with exported schemas and migration tests from v1.

### D. Playback service

- Legacy custom `MediaBrowserServiceCompat` (AntennaPod until 3.11) or Media3 `MediaLibraryService` (AntennaPod 3.12+, Pocket Casts, Podcini). AntennaPod's develop branch still contains both `PlaybackService.java` and `Media3PlaybackService.java`, which shows the migration cost.
- **Verdict:** Media3 only.

### E. YouTube support approaches seen in the wild

| Approach | Who | Notes |
|---|---|---|
| In-app extraction (NewPipeExtractor or own Innertube client) | NewPipe, LibreTube, Tubular, Podcini.R (until Jan 2025) | Full feature set, but GPL-3.0, F-Droid/GitHub only. Breakage: NewPipe 0.28.8 (Jun 2026) shipped a SABR workaround; LibreTube ships its own `SabrClient` and a WebView `PoTokenGenerator`. |
| Channel RSS metadata only, playback via YouTube app/embed | Feed readers (Feeder, NewsBlur) | Policy-safe-ish, but no background audio or downloads, so it is not really "as podcasts". RSS endpoint had intermittent 404s from Dec 2025 into Apr 2026. |
| External bridge server that turns YouTube into a podcast RSS with audio enclosures | Podsync, PigeonPod, yt2podcast (self-hosted, yt-dlp + ffmpeg) | The app just sees a normal podcast feed; legal and operational burden moves to the user's server |
| YouTube Data API v3 | (users mentioned switching to it after RSS 404s) | Quota-limited; still bound by the Developer Policies quoted below |
| Mainstream podcast app with YouTube | Podcast Addict historically advertised "YouTube, Twitch, SoundCloud channels" (v4.0 listing) | The current Play listing (updated 2026-10-03) no longer mentions YouTube. I could not verify whether the feature still exists. |

### F. Scope philosophy

- **Escapepod** (MIT, 1.6.7, 2026-09-18): two screens, keeps only the latest two episodes by default.
- **Podcast Addict:** maximalist (radio, news reader, alarms, Sonos, bookmarks, stats); 10M+ installs, 4.6★ from 573K reviews.
- **Neutrodyne** should aim at "AntennaPod-level core + groups-as-feeds + YouTube", not Podcast Addict breadth.

---

## Technical detail

### 1. Table-stakes feature set (2026) and MVP classification

Legend: ✔ = present (source/doc verified), ◐ = partial or paid, ? = not verified, — = absent.
AP = AntennaPod 3.12.x, PC = Pocket Casts 8.2x, PA = Podcast Addict 2026.x (Play listing), EP = Escapepod 1.6.x.

| Feature | AP | PC | PA | EP | Neutrodyne | Notes / evidence |
|---|---|---|---|---|---|---|
| Subscribe via search (iTunes / Podcast Index / fyyd) and by URL | ✔ (`net/discovery`: Itunes, PodcastIndex, Fyyd, CombinedSearcher) | ✔ (own server search) | ✔ | ◐ (simple search) | **MVP** | iTunes Search needs no key; Podcast Index needs an API key |
| OPML import/export | ✔ (flat, no tags) | ✔ (flat, all in one "feeds" outline) | ✔ | ? | **MVP**, *with groups* | Differentiator: preserve groups |
| Cover grid of subscriptions | ✔ | ✔ (+ folder mosaic tiles) | ✔ | ✔ | **MVP** | PO requirement |
| Groups/tags/folders | ◐ (tags filter the grid) | ◐ (folders, Plus) | ◐ (categories filter lists) | — | **MVP, core** | Per-group feed is the differentiator |
| Per-group episode feed | — (#5222 open) | — (folders); Smart Playlists list podcasts by hand | ◐ | — | **MVP, core** | |
| Inbox / "new" state, mark played | ✔ (`FeedItemFilter`: NEW/UNPLAYED/PLAYED/PAUSED…) | ✔ (archive model) | ✔ | ◐ | **MVP** | Decide the "new" semantics per group (Open questions) |
| Streaming with mobile-data confirmation | ✔ (StreamingConfirmationEvent) | ✔ | ✔ | ✔ | **MVP** | |
| Download queue, progress, Wi-Fi-only | ✔ | ✔ | ✔ | ✔ | **MVP** | |
| Auto-download (per podcast, limits) | ✔ | ✔ (+ per Smart Playlist) | ✔ | ✔ (latest 2) | **MVP** (per podcast and per group) | |
| Auto-delete/cleanup (after played, keep N) | ✔ (`APCleanupAlgorithm`, `APQueueCleanupAlgorithm`, `ExceptFavoriteCleanupAlgorithm`) | ✔ (auto-archive: after playing / inactive / per-podcast limit) | ✔ | ✔ | **MVP** | |
| Queue / Up Next with drag reorder | ✔ | ✔ | ✔ | — | **MVP** | Multiple queues: AP feature request (forum "Multiple queues", issue #2648 per search result); PA has custom playlists |
| Playback speed (global and per podcast) | ✔ (`feed_playback_speed`) | ✔ | ✔ (2026.8: configurable 0.01/0.05/0.1 steps) | ✔ | **MVP** | |
| Skip back/forward (configurable) | ✔ | ✔ | ✔ | ✔ | **MVP** | |
| Sleep timer: duration and end of episode, shake to reset | ✔ (`SleepTimerType` CLOCK/EPISODES; `ShakeListener`; 3.11.0 added episode-count timer) | ✔ | ✔ (2026.8 reworked shake) | ? | **MVP** (duration + end of episode); shake later | |
| Chapters (ID3 CHAP, MP4, Vorbis, Podlove PSC, `podcast:chapters` JSON) | ✔ (`parser/media/{id3,m4a,vorbis}`, `namespace/SimpleChapters`, `PodcastIndex`) | ✔ | ? | ? | **MVP** (feed and ID3/MP4); others later | |
| Media notification, lock screen, Bluetooth/headset buttons, audio focus, unplug-pause | ✔ | ✔ | ✔ | ✔ | **MVP** | Media3 gives most of this |
| Resume position, "smart mark as played" near the end | ✔ (5 s save interval; `getSmartMarkAsPlayedSecs`) | ✔ | ✔ | ✔ | **MVP** | |
| Show notes (HTML), clickable timestamps | ✔ | ✔ | ✔ | ✔ | **MVP** | |
| Dark theme / dynamic colour | ✔ | ✔ (3 themes free) | ✔ | ✔ | **MVP** | |
| Full backup/restore (DB) plus auto-backup | ✔ (3.4: automatic DB backup every 3 days) | (cloud account) | ✔ (2026.11 backup management) | ? | **MVP** | Data-loss lessons below |
| New-episode notifications (per podcast) | ✔ (`episode_notification`) | ✔ | ✔ | ? | **MVP-lite** (per group default) | |
| Skip silence / volume boost | ✔ (per feed) | ✔ | ✔ | ? | Later (v1.x) | Media3 has `setSkipSilenceEnabled` |
| Per-podcast skip intro/ending | ✔ (`feed_skip_intro/ending`) | ✔ | ? | ? | Later | |
| Keyword include/exclude, min duration filter per podcast | ✔ (`include_filter`, `exclude_filter`, `minimal_duration_filter`) | — | ✔ (advanced filtering) | — | Later (useful for news groups) | |
| Transcripts (VTT/SRT/JSON) | ✔ (3.9 VTT) | ✔ | ? | — | Later | |
| Android Auto | ✔ (3.12 "For You") | ✔ | ✔ | ? | **v1.0 if cheap** (Media3 library tree: queue + groups) | Expected by many users |
| Widgets / Quick Settings tile | ✔ (`QuickSettingsTileService`) | ✔ | ✔ | ? | Later | |
| Chromecast | ✔ (Play flavour only) | ✔ | ✔ (+ Sonos) | — | Later (needs proprietary Play Services → flavour split) | |
| Wear OS | ✔ (`app-wearos`) | ✔ | ? | — | Later | |
| Sync (gpodder.net / Nextcloud gpodder) | ✔ | own cloud | Premium sync (2026.11) | — | Later | Kasts and Podium also support gpodder |
| Statistics / year in review | ✔ (Echo) | ✔ (heatmap 8.17) | ✔ | — | Later | |
| Bookmarks | — | ✔ (Smart Bookmarks 8.22) | ✔ | — | Later | |
| Private/authenticated feeds | ✔ (username/password columns) | ? | ✔ | ? | **MVP-lite** (Basic auth) | Distributed model makes this cheap |
| Local folder as a podcast | ✔ (`LocalFeedUpdater`) | Files (upload) | ? | — | Later | |
| Video podcasts, PiP, HLS | ✔ (video) | ✔ (PiP, HLS 8.17) | ✔ | — | Later (YouTube may force video decisions) | |

The MVP list above is what reviewers compare against first. Everything marked "Later" exists in at least one competitor and will be requested.

### 2. Data model sketch (groups as first-class feeds)

```kotlin
@Entity(tableName = "podcast",
        indices = [Index(value = ["feedUrl"], unique = true)])
data class PodcastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: SourceType,          // RSS, YOUTUBE_CHANNEL (keeps YouTube isolated)
    val feedUrl: String,                 // normalized; updated on 301 / itunes:new-feed-url
    val podcastGuid: String?,            // podcast:guid when present (stable identity across URL moves)
    val title: String, val customTitle: String?,
    val imageUrl: String?, val paletteArgb: Int?,   // computed locally (Pocket Casts gets this from its CDN)
    val etag: String?, val lastModified: String?,  // SEPARATE columns (AntennaPod conflates them)
    val lastRefreshAttemptAt: Long?, val lastRefreshOkAt: Long?, val consecutiveFailures: Int = 0,
    val subscribed: Boolean = true,
)

@Entity(tableName = "podcast_group")
data class PodcastGroupEntity(
    @PrimaryKey val id: String,          // UUID — NOT the name (LibreTube keys groups by name)
    val name: String,
    val colorArgb: Int?,                 // Pocket Casts folder colour precedent
    val sortOrder: Int,
    val autoDownloadDefault: Boolean? = null,   // null = inherit global
    val notifyNewDefault: Boolean? = null,
)

@Entity(
    tableName = "podcast_group_member",
    primaryKeys = ["groupId", "podcastId"],
    foreignKeys = [
        ForeignKey(entity = PodcastGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PodcastEntity::class, parentColumns = ["id"], childColumns = ["podcastId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("podcastId")],      // NewPipe indexes the subscription side of its join table the same way
)
data class PodcastGroupMember(val groupId: String, val podcastId: Long, val position: Int)
```

Group feed query: the AntennaPod `IN (…)` list is capped at 800 IDs, so use a subquery against the join table instead. NewPipe's `FeedDAO.getStreams` hard-codes `LIMIT 500`, so use Paging 3 instead.

```kotlin
@Query("""
  SELECT e.* FROM episode e
  WHERE e.podcastId IN (SELECT podcastId FROM podcast_group_member WHERE groupId = :groupId)
    AND e.hiddenAt IS NULL
    AND (:includePlayed OR e.playState != 2)
  ORDER BY e.pubDate DESC, e.id DESC
""")
fun groupFeed(groupId: String, includePlayed: Boolean): PagingSource<Int, EpisodeEntity>
// Required index: episode(podcastId, pubDate DESC). Badge counts:
// SELECT m.groupId, COUNT(*) FROM episode e JOIN podcast_group_member m ON m.podcastId = e.podcastId
//  WHERE e.isNew = 1 GROUP BY m.groupId
```

Semantics to settle: play state lives on the episode. An episode from a podcast in both "tech" and "news" appears in both feeds, and marking it played in one updates the other. That is the correct behaviour, and it falls out of the model.

### 3. OPML with groups (export and import)

The OPML 2.0 spec defines `category` as "a string of comma-separated slash-delimited category strings… To represent a 'tag,' the category string should contain no slashes". It also says subscription lists may be "possibly multiple-level", while warning that "some processors may not understand and preserve the structure" (opml.org/spec2.opml).

Default export (safe for every importer, lossless for Neutrodyne):

```xml
<opml version="2.0" xmlns:nd="urn:neutrodyne:opml:1">
  <head><title>Neutrodyne subscriptions</title><dateCreated>Sun, 04 Oct 2026 12:00:00 GMT</dateCreated></head>
  <body>
    <outline type="rss" text="Some Show" title="Some Show"
             xmlUrl="https://example.com/feed.xml" htmlUrl="https://example.com/"
             category="tech,news"/>
    <outline type="rss" text="A Channel" title="A Channel"
             xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id=UCxxxxxxxxxxxxxxxxxxxxxx"
             category="tech" nd:source="youtube"/>
  </body>
</opml>
```

- **Optional "folders" export:** wrap entries in `<outline text="tech">…</outline>`, duplicating multi-group podcasts. Feed readers map these to folders.
- **Import:** collect group names from (a) every ancestor outline's `text`/`title` and (b) each `category` value with slashes stripped. Then deduplicate subscriptions by normalized `xmlUrl` (Pocket Casts' reader collects URLs into a `Set`).
  - Heuristic: if the document has exactly one non-rss wrapper outline that contains *every* feed, do not turn it into a group. Pocket Casts' export always wraps all feeds in `<outline text="feeds">` (`OpmlExporter.kt`).
  - Show an import preview listing the groups detected, so the user can untick them.
- **Lenient fallback:** if XmlPullParser throws, scan for `xmlUrl="…"`/`'…'`, unescape the five XML entities, and validate the URL. That is exactly `OpmlUrlReader.kt` in Pocket Casts. Groups are lost in fallback mode; tell the user.
- **YouTube entries:** offer an "include YouTube channels" toggle on export. Another *podcast* app will subscribe to the YouTube RSS URL and find no enclosures.
- **Also import:** NewPipe/LibreTube subscription JSON (`{"app_version", "app_version_int", "subscriptions":[{"service_id","url","name"}]}`) and Google/YouTube Takeout `subscriptions.csv`, where the first column is the 24-character channel ID (LibreTube `ImportHelper.kt`).

### 4. Feed refresh worker (lessons applied)

What AntennaPod does (`FeedUpdateManagerImpl.java`, `FeedUpdateWorker.java`):

- `PeriodicWorkRequest` every **1 h** with network constraint (UNMETERED unless mobile refresh is allowed).
- Inside the worker it skips feeds whose `lastRefreshAttempt` is newer than the user interval minus 15 min, so the interval is enforced by the worker, not the schedule.
- `Collections.shuffle(toUpdate)` so "if the worker gets cancelled early, every feed has a chance to be updated".
- Fixed thread pool of **4**.
- Manual refresh is expedited (`RUN_AS_NON_EXPEDITED_WORK_REQUEST` fallback) with a **20 s cooldown**.
- Updates the stored URL on permanent redirect or a redirect inside the feed.
- Runs the unsubscribed-feed cleaner, then auto-download, after refresh.

What to change:

```kotlin
// Per-host fairness: Kasts 26.04 "Podcast updates that are hosted on the same server will now be processed sequentially".
// Strictly-sequential is slow when 80 feeds live on one big host, so allow 2 per host, 4–6 overall.
private val hostPermits = ConcurrentHashMap<String, Semaphore>()
suspend fun <T> withHostPermit(host: String, block: suspend () -> T): T =
    hostPermits.getOrPut(host) { Semaphore(2) }.withPermit { block() }

// Conditional GET: send If-None-Match(etag) AND If-Modified-Since(lastModified) independently.
// AntennaPod stores one "lastModified" value that may be an ETag and only sends If-Modified-Since when
// the date is < 3 days old (HttpDownloader.java) — store both, send both, trust 304.
```

YouTube channel refresh is two-phase (LibreTube `LocalFeedRepository.kt`, NewPipe `FeedLoadManager.kt`):

1. Fetch `https://www.youtube.com/feeds/videos.xml?channel_id=…` (newest 15 per Feeder's docs). If the newest item ID is already in the DB, stop.
2. Otherwise do the expensive channel extraction.
   - LibreTube: 5 concurrent, random 0.5–1.5 s delay after every 50 *fully fetched* channels, 30-day max feed age.
   - NewPipe: 3 parallel, random 6–12 s delay every 50 YouTube extractions, inserts batched by 20.
3. Treat simultaneous 404s across all YouTube channels as "YouTube RSS is down". Back off globally instead of marking every channel failed. The endpoint had intermittent 404s from 2025-12-27 through at least late Apr 2026, often 07:30–12:00 UTC.

### 5. Download pipeline details worth copying from AntennaPod's `HttpDownloader.java`

- Media requests use `Accept-Encoding: identity` so OkHttp does not transparently gzip. Otherwise lengths and ranges break.
- Media requests use `Cache-Control: no-cache` rather than `no-store`; the code comment says "noStore breaks CDNs".
- Resume with `Range: bytes=<soFar>-` plus `If-Range: <validator>`, and accept `206` + `Content-Range`.
- Fail when the media response is `text/*` and under 100 KB, which catches captive portals and HTML error pages.
- Add `Upgrade-Insecure-Requests: 1` for http URLs.
- Retries: AntennaPod `EpisodeDownloadWorker` retries 3 times; Pocket Casts `MAX_DOWNLOAD_ATTEMPT_COUNT = 3`.
- Pocket Casts downloads into a temp file (`getOrCreatePodcastEpisodeTempFile`). It also has a user-triggered `FixDownloadsWorker` that walks every episode and re-links files that exist on disk but are not marked downloaded. Plan for DB/file drift (storage moves, restores, reinstalls).

Foreground type and quota choices:

- AntennaPod's download worker uses expedited WorkManager work and declares no `FOREGROUND_SERVICE_DATA_SYNC`.
- Pocket Casts declares `FOREGROUND_SERVICE_DATA_SYNC` and passes `FOREGROUND_SERVICE_TYPE_DATA_SYNC` to `ForegroundInfo`.
- Neutrodyne:
  - user taps "Download" → UIDT job on API 34+ (requires `RUN_USER_INITIATED_JOBS`, a notification, and can only be scheduled while the app is visible), WorkManager foreground `dataSync` below 34;
  - auto-downloads from background refresh → WorkManager, which **cannot** be UIDT because it is not user-initiated, so make it chunk-resumable;
  - remember the Android 15 `dataSync` cap of 6 h per 24 h.

### 6. Playback reliability patterns

- **Position save cadence:** AntennaPod's `Media3PlaybackService` saves every `POSITION_SAVE_INTERVAL_MS = 5000` on a 1 s ticker, plus on pause, seek and transition. Copy this, and add a guard: on media transition, persist the *outgoing* item's position before loading the next. Never write 0 over a non-zero stored position unless (a) the user reset it or (b) it was marked played. That is the AntennaPod 3.12.0-beta/3.12.2 bug class.
- **Auto-advance in background:** load the queue as an ExoPlayer playlist. The player then never passes through `STATE_ENDED` and the foreground service is never re-started from the background. Podcini issue #88 (Sept 2024, Samsung, Android 14) shows the failure: no notification and playback stopping with `ForegroundServiceStartNotAllowedException` when the next episode auto-starts.
- **Media button resumption:** override `MediaSessionService.Listener.onForegroundServiceStartNotAllowedException` and post a "tap to resume" notification instead of failing silently (Media3 API docs).
- **Notifications:** media-session notifications are exempt from the Android 13 `POST_NOTIFICATIONS` runtime permission, but refresh, download and new-episode notifications are not. Ask for the permission contextually, not at first launch.

### 7. Database growth and retention

- AntennaPod `NonSubscribedFeedsCleaner`: deletes previewed-but-not-subscribed feeds after 1 day untouched, or after 30 days if interacted with, unless they have an "episode in app".
- AntennaPod `DatabaseMaintenanceWorker` (every 3 days): only clears the old download log. There is **no** pruning of episodes that dropped out of the feed (issue #4426, Needs Decision since 2020-09-15).
- **NewPipe pattern:** delete feed links older than a threshold, but **keep one newest item per channel** as the "already seen" watermark (`FeedDAO.unlinkStreamsOlderThan`).
- **Neutrodyne policy (suggested):**
  - never delete an episode that is downloaded, queued, favourited, in progress, or played in the last 30 days;
  - otherwise hide items absent from the last N successful fetches, and hard-delete them after 90 days;
  - keep the newest item per feed as the watermark;
  - store show-notes HTML in a separate `episode_notes` table so list scans stay small.

---

## Verified versions & facts

| Fact | Value | Source | Checked |
|---|---|---|---|
| AntennaPod latest stable | 3.12.2 (release notes dated Sep 18; tag commit 2026-09-08); develop `versionName "3.12.3"` | https://github.com/AntennaPod/AntennaPod/releases/tag/3.12.2 ; `app/build.gradle` on develop | 2026-10-04 |
| AntennaPod 3.12.0 "Rewrite playback service… do report any regressions" | Released Aug 14 (2026) | https://github.com/AntennaPod/AntennaPod/releases/tag/3.12.0 | 2026-10-04 |
| AntennaPod 3.12.2 fixes | car-audio looping, button remap, Chromecast, "resetting playback position of currently playing episode" | same as above (3.12.2) | 2026-10-04 |
| AntennaPod 3.11.2 / 3.11.4 | "Reduce probability of crashes on Android 16" | https://github.com/AntennaPod/AntennaPod/releases/tag/3.11.2 | 2026-10-04 |
| AntennaPod 3.10.2 | adds subscriptions archive, shows tags above subscriptions | https://github.com/AntennaPod/AntennaPod/releases/tag/3.10.2 | 2026-10-04 |
| AntennaPod 3.4.0 | "Up to 3x faster refresh of subscriptions with 1000+ episodes"; automatic DB backup option | https://github.com/AntennaPod/AntennaPod/releases/tag/3.4.0 | 2026-10-04 |
| AntennaPod build | compileSdk 36, targetSdk 36, minSdk 23; Media3 1.10.0; WorkManager 2.10.3; Glide 4.16.0; OkHttp 4.12.0 | `common.gradle`, `gradle/libs.versions.toml` (develop) | 2026-10-04 |
| AntennaPod tags storage | `Feeds.tags TEXT`, separator `\u001e`, pseudo-tags `#root`, `#untagged` | `model/.../FeedPreferences.java`, `storage/database/.../PodDBAdapter.java` | 2026-10-04 |
| AntennaPod OPML writer/reader | flat outlines (title, type, xmlUrl, htmlUrl); reader takes any outline with xmlUrl, ignores nesting/category | `storage/importexport/.../OpmlWriter.java`, `OpmlReader.java` | 2026-10-04 |
| AntennaPod refresh scheduler | hourly periodic worker, interval enforced in worker, shuffle, 4 threads, 20 s manual cooldown | `net/download/service/.../feed/FeedUpdateManagerImpl.java`, `FeedUpdateWorker.java` | 2026-10-04 |
| AntennaPod default refresh interval | "by default every 12 hours" | https://antennapod.org/documentation/general/central-distributed | 2026-10-04 |
| AntennaPod per-tag episodes view | Issue #5222 "Add tag filter to Episodes screen", open since 2021-06-13 | https://github.com/AntennaPod/AntennaPod/issues/5222 | 2026-10-04 |
| AntennaPod maintainer on per-tag view | "Currently not, no. It's already on the (long) list of feature requests" (2025-10-31) | https://forum.antennapod.org/t/is-it-possible-to-view-new-downloaded-episodes-by-tag/7634 | 2026-10-04 |
| AntennaPod docs on tags | "it is not possible to create queues based on tags" | https://antennapod.org/documentation/subscriptions/subscription-groups | 2026-10-04 |
| AntennaPod tag UX complaint | tags appear as nav items in both drawer and subscriptions tab (Jan 2025) | https://forum.antennapod.org/t/enhancing-podcast-management-with-folders-and-tags-in-antennapod/5940 | 2026-10-04 |
| AntennaPod DB growth | 52,000 FeedItems / 80 MB; startup "a couple seconds to virtually instant" after manual prune (Dec 2024) | https://forum.antennapod.org/t/cleanup-of-old-unlisted-episodes/5885 | 2026-10-04 |
| AntennaPod battery/CPU with 364 MB DB | reported Mar 2023 | https://forum.antennapod.org/t/massive-battery-drainage/2685 | 2026-10-04 |
| AntennaPod issue #4426 | "Remove episodes no longer in the RSS feed…", opened 2020-09-15, Needs Decision | https://github.com/AntennaPod/AntennaPod/issues/4426 | 2026-10-04 |
| AntennaPod DB corruption help + recovery | `CorruptedDatabaseBackup.db`, restore from backup | https://antennapod.org/documentation/bugs-first-aid/database-error ; https://forum.antennapod.org/t/disaster-queue-downloads-all-deleted/4451 | 2026-10-04 |
| Google Podcasts OPML malformed | "Unterminated entity ref"; missing `/>` (Aug 2024) | https://forum.antennapod.org/t/cant-import-google-podcasts-opml-file/5363 | 2026-10-04 |
| AntennaPod exact-time refresh impossible | maintainer: WorkManager periodic only; "Only alarm clock apps can request an exact time" | https://forum.antennapod.org/t/option-to-refresh-podcasts-at-specific-time/5222 | 2026-10-04 |
| AntennaPod "playback stops" first aid | battery-optimisation exemption advice | https://antennapod.org/documentation/bugs-first-aid/playback-stops | 2026-10-04 |
| AntennaPod 3.12 beta regressions | position resets, widget, auto-skip, notification metadata, crashes (May–Jun 2026) | https://forum.antennapod.org/t/3-12-0-beta-bugs-in-playback-service-rewrite/8588 | 2026-10-04 |
| Pocket Casts latest stable tag | 8.21 (8.22-rc-3 in progress; `version.properties` 8.22-rc-3) | `git ls-remote` on https://github.com/Automattic/pocket-casts-android ; `CHANGELOG.md` | 2026-10-04 |
| Pocket Casts build | compileSdk 37, targetSdk 36, minSdk 24; Room 2.8.5 (DB v139); Media3 1.11.1; Work 2.12.0; Coil 3.6.3; Compose BOM 2026.08.00; Kotlin 2.4.20 | `build.gradle.kts`, `gradle/libs.versions.toml`, `AppDatabase.kt` (main) | 2026-10-04 |
| Pocket Casts folders | Plus/Patron only; folders disappear when Plus lapses; Smart Folders (≥8 podcasts, Plus) since 7.85 (2025-04-03) | https://support.pocketcasts.com/knowledge-base/folders/ ; https://blog.pocketcasts.com/2025/04/03/smart-folders/ | 2026-10-04 |
| Pocket Casts folder model | single `folder_uuid` per podcast; folder has colour, sort position, podcasts sort type | `modules/services/model/.../entity/Folder.kt`, `Podcast.kt` | 2026-10-04 |
| Pocket Casts Smart Playlists | free; rules: podcasts, status, release date, duration, download, media type, starred; "We do not currently offer a way to include or exclude episodes… based on the folder" | https://support.pocketcasts.com/knowledge-base/episode-filters/ | 2026-10-04 |
| Pocket Casts Filters → Playlists | v8.0, Nov 2025 (manual + smart) | https://9to5mac.com/2025/11/25/pocket-casts-app-introduces-smart-playlists-feature-to-organize-your-podcasts/ | 2026-10-04 (search result) |
| Pocket Casts user requests | "folders in filters" (2023-07-02 → 2026-02-15 staff: "looking into improvements to the Smart Rules") ; "recent episodes of each folder" (2025-07) | https://forums.pocketcasts.com/forums/topic/feature-request-folders-in-filters/ ; https://forums.pocketcasts.com/forums/topic/feature-request-let-me-see-recent-episodes-of-each-folder/ | 2026-10-04 |
| Pocket Casts central refresh | `POST /user/update` with podcasts; OPML resolved server-side (`import/opml`, poll ≤20 times) | `servers/.../ServiceManager.kt`, `servers/.../refresh/RefreshService.kt`, `repositories/.../opml/OpmlImportTask.kt` | 2026-10-04 |
| Pocket Casts OPML | export: one `<outline text="feeds">` wrapping flat rss outlines; import: lenient `xmlUrl=` scanner | `views/.../helper/OpmlExporter.kt`, `repositories/.../opml/OpmlUrlReader.kt` | 2026-10-04 |
| Pocket Casts folder tile UI | colour background + 2×2 covers of first 4 podcasts | `services/compose/.../folder/FolderImage.kt` | 2026-10-04 |
| Podcini YouTube exit | "decided to stop developing… as of Jan 13 2025" to avoid legal allegations; continued as Podcini.X "with access to Youtube stripped off" | https://github.com/XilinJia/Podcini (README) | 2026-10-04 |
| Podcini.X status | "Concluded", successor Podcini.A | https://github.com/XilinJia/Podcini.X | 2026-10-04 |
| Podcini v6 storage break | SQLite → Realm, new applicationId Podcini.R, old DB not importable | https://github.com/XilinJia/Podcini/blob/main/migrationTo6.md | 2026-10-04 |
| Podcini background FGS issue | #88: `ForegroundServiceStartNotAllowedException` on auto-next (Sept 2024) | https://github.com/XilinJia/Podcini/issues/88 | 2026-10-04 |
| Realm/Atlas Device SDKs deprecation | deprecated Sept 2024; EOL Sept 30, 2025 (Sync); on-device DB continues as OSS | https://www.mongodb.com/docs/atlas/device-sdks/deprecation (via search summary; page body not retrievable) | 2026-10-04 (**partially verified**) |
| Kasts 26.04.0 | 2026-04-16; unique IDs rewrite; parallel downloads setting (default 2); same-server updates sequential | https://apps.kde.org/kasts/ | 2026-10-04 |
| Escapepod | 1.6.7 (2026-09-18), MIT, keeps latest two episodes by default | https://f-droid.org/en/packages/org.y20k.escapepod/ | 2026-10-04 |
| Podium (new Compose app) | 1.0.0-alpha09 (2026-09-25), GPL-3.0, "lists", gpodder sync | https://f-droid.org/en/packages/app.podiumpodcasts.podium/ | 2026-10-04 |
| Podcast Addict listing | updated 2026-10-03; 10M+ downloads; 4.6★ / 573K reviews; listing text does not mention YouTube | https://play.google.com/store/apps/details?id=com.bambuna.podcastaddict | 2026-10-04 |
| Podcast Addict category filter on lists | FAQ #375 (via search snippet; site returns 403 to fetchers) | https://podcastaddict.com/faq/375 | 2026-10-04 (**snippet only**) |
| Podcast Addict 2026 changelog | 2026.8 speed steps and shake rework; 2026.9 custom playlist manual order; 2026.11 sync and backup management | https://podcastaddict.com/changelog/2026_8 , /2026_9 , /2026_11 (search summaries) | 2026-10-04 |
| NewPipe latest | v0.29.1 (tag 2026-08-15); v0.28.8 (2026-06-10) "fixes issues… caused by YouTube enforcing its SABR protocol, except for videos made for kids" | https://github.com/TeamNewPipe/NewPipe/releases ; tag dates via `git fetch` | 2026-10-04 |
| NewPipe feed groups | join table `feed_group_subscription_join`; group feed query `LIMIT 500`; per-group outdated refresh; 3 parallel, 6–12 s delay per 50 | `database/feed/model/*.kt`, `database/feed/dao/FeedDAO.kt`, `local/feed/service/FeedLoadManager.kt` | 2026-10-04 |
| LibreTube latest | v32.1 (tag 2026-08-20); own SABR client + WebView PO-token generator | `git ls-remote`; `repo/SabrDownloadProvider.kt`, `api/poToken/PoTokenGenerator.kt` | 2026-10-04 |
| LibreTube groups keyed by name | "in LibreTube, we identify channel groups by their name" | `db/obj/SubscriptionGroup.kt` | 2026-10-04 |
| Tubular | discontinued, archived Jul 2026; last tag v0.28.4 (2026-03-09) | https://github.com/polymorphicshade/Tubular | 2026-10-04 |
| NewPipe not on Play | putting NewPipe/forks on Play "violates their terms" (secondary source) | https://github.com/TeamNewPipe/NewPipe (search summary) | 2026-10-04 (**secondary**) |
| YouTube API Developer Policies | prohibits download/cache of audiovisual content; separating audio/video; background player | https://developers.google.com/youtube/terms/developer-policies | 2026-10-04 |
| YouTube RSS reliability | intermittent 404s from 2025-12-27 to at least late Apr 2026; Google response minimal | https://discuss.ai.google.dev/t/youtube-rss-feed-endpoint-returns-404-errors/113379 | 2026-10-04 |
| YouTube RSS size | "A channel's feed lists its 15 newest videos" | https://feeder.co/help/rss/youtube-feeds/ | 2026-10-04 |
| NewPipeExtractor licence | GPL-3.0; minSdk < 33 needs `desugar_jdk_libs_nio` | https://github.com/TeamNewPipe/NewPipeExtractor (LICENSE, README) | 2026-10-04 |
| Licences of studied apps | AntennaPod GPL-3.0; LibreTube GPL-3.0; Podcini GPL-3.0; Pocket Casts MPL-2.0; Escapepod MIT | LICENSE files in clones; F-Droid page | 2026-10-04 |
| OPML 2.0 `category` and nested lists | quoted above | http://opml.org/spec2.opml | 2026-10-04 |
| Google Podcasts shutdown | stopped working in the US on 2024-04-02; OPML export/migration until July 2024 | https://www.androidcentral.com/apps-software/google-podcasts-migration-tool-available ; https://www.techradar.com/computing/software/google-podcast-shuts-down-on-april-2-and-heres-what-you-can-do-about-it | 2026-10-04 (search summaries) |
| YouTube Music add-by-RSS | supported; RSS-added shows lack video switch, likes, sharing, captions | https://support.google.com/youtubemusic/answer/13946190 | 2026-10-04 (search summary) |
| Spotify | no OPML import/export; Spotify API gives metadata but no public audio file URL, so exclusives can't be played elsewhere | https://openrss.org/work/156 | 2026-10-04 |
| Apple artwork spec | 1400×1400 to 3000×3000 px | https://podcasters.apple.com/support/artwork-requirements | 2026-10-04 (search summary) |
| Android 15 FGS | `dataSync` 6 h / 24 h; BOOT_COMPLETED may not start `dataSync` or `mediaPlayback` FGS | https://developer.android.com/about/versions/15/behavior-changes-15 | 2026-10-04 |
| Android 16 job quotas | jobs started while visible and continuing after, or running concurrently with an FGS, "will adhere to the job runtime quota"; suggests UIDT | https://developer.android.com/about/versions/16/behavior-changes-all | 2026-10-04 |
| UIDT | API 34+, `RUN_USER_INITIATED_JOBS`, must be scheduled while visible, needs `setNotification`, no WorkManager support | https://developer.android.com/develop/background-work/background-tasks/uidt | 2026-10-04 |
| Media-session notification exemption | exempt from POST_NOTIFICATIONS; FGS notices still in Task Manager if denied | https://developer.android.com/develop/ui/views/notifications/notification-permission | 2026-10-04 |
| Media3 / Room / WorkManager stable | 1.11.1 (2026-09-10) / 2.8.5 (2026-09-09) / 2.12.0 (2026-09-23) | https://developer.android.com/jetpack/androidx/releases/media3 ; …/room ; …/work | 2026-10-04 |

---

## Pitfalls & edge cases

Grouped by the recurring bug classes in prior art.

### Refresh, battery and data

1. **Inexact scheduling is the norm.** Users expect "refresh at 7 am"; WorkManager cannot do it (AntennaPod maintainer, 2024 to 2025). Phrase the setting as "about every N hours". Add refresh-on-app-open (with a cooldown) and pull-to-refresh, and treat background refresh as best effort.
2. **OEM battery killers** stop refresh and playback. AntennaPod's first-aid docs tell users to exempt the app. Ship an in-app diagnostic: last successful refresh time, standby bucket if readable, and a deep link to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. Do **not** use `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Google Play "prohibit[s] apps from requesting direct exemption… unless the core function of the app is adversely affected", and media/podcast apps are not in the acceptable-use table (https://developer.android.com/training/monitoring-device-state/doze-standby, checked 2026-10-04). Podcini.R nevertheless asks for unrestricted background activity (README).
3. **Thundering herd on shared hosts.** Hundreds of feeds on the same host (Megaphone, Libsyn, Anchor/Spotify for Creators, Simplecast) can trip rate limits. Use a per-host semaphore plus jitter; Kasts changed to sequential per server.
4. **Validators.** Store ETag and Last-Modified separately and send both. Some servers emit a constant ETag or a stale Last-Modified; if a feed *always* returns 304 for over 7 days while its `lastBuildDate` should advance, do one unconditional fetch per week. AntennaPod's "only send If-Modified-Since when the date is < 3 days old" looks like a workaround for exactly this.
5. **Redirects and moves.** Persist the URL only on *permanent* redirects (301/308) and on `itunes:new-feed-url`; temporary 302s must not overwrite the stored URL (AntennaPod `RedirectChecker.getNewUrlIfPermanentRedirect`). Use `podcast:guid` where available to detect the same show at a new URL, and dedupe on import.
6. **Duplicate episodes when publishers re-GUID.** AntennaPod's `FeedItemDuplicateGuesser` treats items as the same if:
   - the GUID matches, or
   - the enclosure URL matches, or
   - title (normalized quotes and dashes) **and** same calendar date **and** duration within 10 min **and** same MIME major type all match.

   Copy the heuristic, not the code.
7. **YouTube RSS outages.** Many channels 404ing at the same moment means the endpoint is down, not that channels were deleted. Never auto-unsubscribe on 404, and back off globally.

### Downloads

8. **Captive portals and HTML error pages saved as episodes.** Reject `text/*` responses under 100 KB (AntennaPod) and verify the Content-Length versus bytes written.
9. **Transparent gzip** breaks byte ranges. Force `Accept-Encoding: identity` for media.
10. **Quota-killed jobs on Android 16** while playback is running. Use UIDT for manual downloads. Make auto-download chunked and resumable, and surface `WorkInfo.getStopReason()` in a download log (AntennaPod has a download log table and a 3-day log cleanup).
11. **DB/file drift** after restore, reinstall or storage change. Ship a "rescan downloads" action (Pocket Casts `FixDownloadsWorker`). The scan must update its notification sparsely; Pocket Casts' code comment notes that updating per episode made the "whole process extremely slow".
12. **Downloading the same enclosure twice** when an episode is in two groups with auto-download. Key download work by episode ID with `ExistingWorkPolicy.KEEP`.

### Database growth and corruption

13. **Unbounded episode tables** for high-frequency feeds (news groups are exactly that). Apply the retention policy above, and index `episode(podcastId, pubDate)` and `episode(isNew)`.
14. **SQLite bound-variable limits.** AntennaPod caps `IN` lists at 800 IDs; use joins and subqueries for group feeds.
15. **Hard result caps.** NewPipe's group feed is `LIMIT 500`; use Paging so old episodes stay reachable.
16. **Corruption and "everything is gone".**
    - Use Room in WAL mode.
    - Make automatic periodic backups (AntennaPod: every 3 days, rotating copies, plus an Android Backup agent that stores OPML).
    - Run `PRAGMA integrity_check` before overwriting the last good backup.
    - Show a restore prompt when the DB opens empty but backups exist.
17. **Breaking storage migrations.** Podcini's new applicationId plus Realm forced users to reinstall and re-import. Never change the applicationId; ship Room auto-migrations plus tests; keep an export format (JSON) that is stable across versions.

### Playback and position loss

18. **Position reset on auto-advance or re-open.** AntennaPod reports include forum threads "Playback position is reset when queue order is changed" (/t/8721) and "Playback position problem" (/t/8737), the 3.12.0-beta reports, and the 3.12.2 fix "resetting playback position of currently playing episode". Persist on transition before switching items, and apply the non-zero-to-zero guard.
19. **FGS start denied when the next item starts in the background** (Podcini #88). Keep a continuous ExoPlayer playlist, and handle `onForegroundServiceStartNotAllowedException`.
20. **Car audio loops and Bluetooth autoplay** (AntennaPod 3.12.2 "Fix episodes looping on some car audios"). Test against AVRCP head units and Android Auto early.
21. **Rewrites cause regressions.** AntennaPod's Media3 rewrite needed two point releases of playback fixes. Starting on Media3 avoids the rewrite, but budget for an instrumented test matrix (Bluetooth, wired headset, Auto, widget, notification, lock screen).

### Groups and OPML

22. **Group identity.** Key groups by UUID, not name; LibreTube keys by name, so a rename means delete and re-insert, and sync/import breaks.
23. **Multi-membership semantics.** One episode appears in many group feeds. Unread counts must not be summed across groups as if disjoint, so the "All" badge should be computed separately.
24. **Ungrouped podcasts.** Provide an "Ungrouped" pseudo-group (AntennaPod has `#untagged`) so nothing becomes unreachable.
25. **OPML import duplicates.** Nested exports from other tools repeat a feed under several folders. Dedupe by normalized URL (scheme, host case, trailing slash) and merge group memberships.
26. **Malformed OPML** (Google Podcasts). Use the lenient fallback and report "N feeds imported, groups could not be read".
27. **Exporting YouTube channels.** Other podcast apps get enclosure-less feeds, so make inclusion optional and mark entries with the `nd:` namespace.

### Covers and UI

28. **Giant artwork.** Publisher covers commonly run up to 3000×3000 px (Apple's max). Decode at display size, store a small thumbnail, and avoid re-fetching on every refresh unless `imageUrl` changed. Pocket Casts avoids this with server-resized WebP at fixed widths (`/discover/images/webp/960/<uuid>.webp`), which Neutrodyne cannot.
29. **Per-podcast colours.** Pocket Casts stores 7 server-computed colours per podcast (`primary_color`, `secondary_color`, …). Compute a palette locally once and store it; do not recompute in list scroll.
30. **Episode-level artwork** (`FeedItems.image_url` in AntennaPod) differs from show artwork. The group feed should show the episode image if present, else the show cover. YouTube thumbnails are 16:9 and need a crop/letterbox decision.

### YouTube, policy and licensing

31. **Play Store risk.** The YouTube API Developer Policies explicitly prohibit background players, separating audio, and download/caching without written approval. All prior apps that do this live outside Google Play (NewPipe, LibreTube, Tubular), or exited (Podcini, Jan 2025). The Podcast Addict listing no longer advertises YouTube (cause unknown).
32. **Extraction churn.**
    - NewPipe needed a SABR workaround in Jun 2026, which still does not cover "made for kids" videos.
    - LibreTube maintains a SABR client and PO-token WebView.
    - Tubular gave up in Jul 2026.
    - Budget ongoing maintenance, or depend on an upstream extractor and accept GPL.
33. **Licence contamination.** Do not copy GPL (AntennaPod, LibreTube, Podcini, NewPipe) or MPL (Pocket Casts) code into an Unlicense repo. Re-implement behaviour from these notes. Escapepod is MIT and still requires keeping its notice, so it is not public domain either.

### Migration expectations

34. **Google Podcasts refugees** arrive with malformed OPML (above).
35. **Spotify users have no export.** Exclusives cannot be played outside Spotify because its API has no public audio URL. The only path is search-by-title. A "paste a Spotify show link" helper would need metadata from Spotify; I did not verify whether that is feasible without the Spotify API.
36. **YouTube users** bring Takeout `subscriptions.csv` or NewPipe JSON; support both.

---

## Open questions for the product owner

1. **Distribution channel(s):** Google Play, F-Droid, GitHub releases, or several? This decides the YouTube design. On Play, in-app YouTube audio extraction, background play and download conflict with YouTube's policies and with how every prior app behaves. Options:
   - (a) a Play build with YouTube metadata only (RSS) that opens videos in YouTube, plus an F-Droid/GitHub build with full extraction;
   - (b) full extraction everywhere, accepting Play removal risk;
   - (c) support YouTube only via user-run bridges (Podsync/PigeonPod) that produce normal podcast feeds.
2. **Licence:** is shipping a GPL-3.0 dependency (NewPipeExtractor) acceptable? Neutrodyne's own source can stay Unlicense, but the distributed APK would then be governed by GPL-3.0. If not acceptable, YouTube support needs a clean-room extractor, which is a large ongoing cost.
3. **YouTube scope:** audio-only or video too? Downloads of YouTube items, or streaming only? Exclude Shorts and livestreams by default (LibreTube and NewPipe make tabs configurable)?
4. **Group semantics:**
   - Multi-membership (recommended: yes)?
   - Nested groups (recommended: not in v1)?
   - Should a group have its **own queue**, or just a "play all / append to queue" action? AntennaPod explicitly could not do tag-based queues without multiple queues.
5. **What a group feed shows by default:** only new/unplayed, or everything newest-first with played items dimmed? Is there a global "Inbox" in addition to per-group feeds, or is "All" just another group?
6. **Per-group automation:** should groups carry defaults for auto-download, notifications and refresh frequency (e.g. "news" hourly with auto-delete after 2 days; "fiction" daily with keep-all)? This is a strong differentiator, but it adds settings-inheritance complexity (global, then group, then podcast; and which wins when a podcast is in two groups?).
7. **MVP cut:** confirm that Android Auto, Chromecast (needs proprietary Play Services and a flavour split), Wear OS, sync (gpodder/Nextcloud), transcripts and video podcasts are post-MVP.
8. **Discovery provider:** iTunes Search API (no key, Apple-hosted) and/or Podcast Index (needs an API key embedded in the app)? Any privacy stance on third-party lookups?
9. **minSdk:** prior art uses 23 (AntennaPod) and 24 (Pocket Casts). UIDT is 34+, and NewPipeExtractor needs NIO desugaring below 33. Is minSdk 26 or 28 acceptable to simplify?
10. **Data retention defaults:** how long should Neutrodyne keep episodes that disappeared from a feed and were never touched (suggested 90 days)? Is "keep forever" a user option for archival listeners?
11. **Backups:** is automatic local DB backup on by default? Should it target a user-chosen folder (Storage Access Framework (SAF)), or app storage plus Android Auto Backup?
