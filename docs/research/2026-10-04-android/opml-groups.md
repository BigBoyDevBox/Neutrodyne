# Neutrodyne: OPML import/export, groups and backup (research notes)

Research area: subscription import and export (OPML and others), the groups feature (data model, group feeds, per-group settings, smart groups), full backup and restore, and Android Auto Backup.

All facts were checked on **2026-10-04**. Sources are listed in "Verified versions & facts". Anything I could not confirm is marked **UNVERIFIED**.

These notes are aligned with the sibling research notes in this scratchpad:

* `stack.md`: Room 3 (`androidx.room3`) 3.0.3 with `BundledSQLiteDriver`, Paging 3.5.1, kotlinx.serialization 1.11.0, a pure-JVM `:feeds` module that owns the OPML reader and writer, and Nav3.
* `feeds.md`: the `podcast` and `episode` tables, `podcast_url_alias`, `identityKey`, URL normalisation, and the refresh engine.
* `playback.md`: `queue_entry`, `PlaySession` context, and `PlaybackOverrides`.
* `downloads.md`: the `download` table and `AutoDownloadPolicy`.

Where my recommendations need something from those areas, I say so explicitly. They are tagged **[cross-area]**.

---

## Recommendation

### Decisions at a glance

| Concern | Choice for Neutrodyne |
|---|---|
| Group cardinality | **Many-to-many** podcast ↔ group (tags semantics), via `podcast_group` + `podcast_group_member` (composite PK, `WITHOUT ROWID`). The podcast library is never hidden by groups. |
| Virtual feeds | **"All"** and **"Ungrouped"** are not rows. They are cases of a sealed `FeedSource` type (`All`, `Ungrouped`, `Group(id)`, `Podcast(id)`, and later `Smart(id)`). One query builder serves all of them. |
| Group identity | Each group has a local `id` (FK target), a stable `uuid` (used for backup, merge and future sync), and a case-insensitive unique `nameKey`. |
| Group look | `colorArgb` from a curated 12-colour palette (null means derived from covers). `iconKey` is a **string** key into a curated icon set; never store a resource ID. Default artwork is a 2×2 mosaic of member covers. |
| Per-group settings | `podcast_group_settings` table, 1:1 with the group, FK `ON DELETE CASCADE`. Nullable columns mean "inherit". It covers speed, skip silence, auto-download, keep-latest, network, new-episode notifications and refresh interval. The resolution rules are below. |
| Group feed query | One `FeedQueryBuilder` produces a `RoomRawQuery` for a single `@RawQuery(observedEntities = …)` DAO function that returns `PagingSource<Int, EpisodeRow>`. Room's LIMIT/OFFSET paging is used. Order is `sortDate, id` with a direction. Filters are bound parameters. |
| Invalidation hygiene **[cross-area]** | List queries may join **only low-churn tables**. Playback position (written every 10 s) and download byte progress (every 2 s) must live in tables that the paging query does not join. Otherwise every tick re-runs `COUNT(*)` and the page query over about 50k rows. |
| OPML export format | OPML 2.0, **hybrid encoding**: each feed appears **exactly once**, nested in a folder outline for its *primary* group (the first by group order). Its `category` attribute lists **all** its groups as tags. Ungrouped feeds sit at top level and empty groups are empty folders. There is a "Flat list" option for maximum compatibility. |
| YouTube in OPML | Export the channel's Atom feed URL `https://www.youtube.com/feeds/videos.xml?channel_id=UC…` as `xmlUrl`, with the channel page as `htmlUrl`, and `type="rss"`. On import, recognise channel, playlist (`UULF…` and others), handle and legacy forms. |
| OPML import parser | Streaming `XmlPullParser` in `:feeds`. The cascade is **strict, then relaxed, then regex salvage** (for broken exports such as Google Podcasts'). Encoding is auto-detected from the BOM or the XML declaration. Attribute matching is case-insensitive. Size, depth and count are capped. |
| Import mapping | A feed's groups are **(`category` tags) ∪ (immediate parent folder)**. Known wrapper containers (`feeds`, Overcast's `playlists`, a single root folder that wraps everything) are dropped by default. Group names are matched to existing groups case-insensitively. |
| Import flow | Copy the payload to cache, then parse, then show a **preview** (selection, duplicates, invalid entries, group mapping, toggles). On confirm, one transaction creates *pending* podcasts and memberships. The **refresh engine** then fetches them, and each item's status is recorded in `import_item`. Failures are kept with Retry, Edit URL or Remove. The final report goes to a notification and a screen. |
| Other import sources | The same pipeline accepts **NewPipe JSON**, **Google Takeout YouTube `subscriptions.csv` / ZIP**, and **Neutrodyne backups**. The format is sniffed from content, not from MIME type. |
| Export UX | Export through SAF `ActivityResultContracts.CreateDocument("text/x-opml")`, writing with mode `"wt"`, or through the **share sheet** (`ACTION_SEND` + `FileProvider` + `ClipData`). There is also "Share this group" from a group's menu. |
| Receiving OPML | A dedicated exported `ExternalImportActivity` with narrow filters: VIEW and SEND for `text/x-opml`, `text/xml` and `application/xml`; SEND for `application/octet-stream`; and on API 31+ an alias that matches `content://…*.opml` by `pathSuffix`. It copies the stream immediately (the URI grant dies with the activity stack) and then hands over to `MainActivity` with an internal session ID. |
| Full backup | A **versioned ZIP** containing `manifest.json`, `library.json`, `episodes.jsonl`, `queue.json`, `settings.json` and `subscriptions.opml`. Everything is keyed by **stable identities** (normalised feed URL, `podcastGuid`, episode `identityKey`), not row IDs. It includes **episode stubs** for every episode with user state. Restore is Merge (default) or Replace. An optional scheduled backup to a user-chosen SAF folder keeps the last 5. |
| Auto Backup | **Do not back up the Room DB.** It can exceed the 25 MB cap, which would cancel the whole backup, and it is re-creatable. Back up only a daily **snapshot ZIP** (the same format as the manual backup) and the *portable* DataStore file. Exclude downloads, device-specific prefs and credentials. Restore from the snapshot on first launch, when Room has just created the database. |
| Play a group | The group's `playOrder` (newest first or oldest first, per group) drives a keyset "context tail" query: unplayed items, excluding Up Next, row-value comparison against the anchor. It plugs into playback's `PlaySession` context. |
| Smart groups | Not in v1. Reserve `kind` + `ruleJson` on `podcast_group` and `source` on `podcast_group_member` now, so that no migration is needed later. |

### Why, briefly

* **Many-to-many.** The product owner's examples ("tech", "news", "fiction") naturally overlap; a tech-news show belongs in both.
  * AntennaPod models feed tags as a `Set<String>`, i.e. many-to-many, with virtual `#root` and `#untagged` tags. That is the same shape as our "All" and "Ungrouped".
  * Pocket Casts uses a single `folder_uuid` per podcast, i.e. folders. Folder semantics can be emulated on top of many-to-many with a "Move to group" action; the reverse is impossible.
  * The costs of many-to-many are real but bounded: settings conflicts (resolution rules below), OPML encoding (the hybrid below), and a multi-select UI.
* **Hybrid OPML.** Every mainstream podcast importer I could inspect already copes with nesting, because Pocket Casts and Overcast themselves export a wrapper folder:
  * AntennaPod's reader visits every `<outline>` that has an `xmlUrl` at any depth.
  * Pocket Casts' reader scans for `xmlUrl=` line by line.
  * gPodder reads every outline and treats `title == text` outlines as sections.

  So nested folders are safe. Emitting each feed only once avoids duplicates in flattening importers. Folder-aware importers (gPodder, FreshRSS) recreate the primary group. FreshRSS would turn a flat `category="tech,news"` into a single category literally named `"tech, news"` (verified in its `ImportService.php`), so flat+category alone is worse. Neutrodyne itself gets full fidelity from `category`.
* **Snapshot-based Auto Backup.** Auto Backup silently stops backing up when the app's data exceeds 25 MB ("calls `onQuotaExceeded()` and doesn't back up data to the cloud").
  * A podcast DB with about 50k episodes plus show notes, plus downloads in `getExternalFilesDir` (which Auto Backup includes by default), would exceed the cap and the user would get nothing.
  * A compact snapshot of user state (a few MB zipped), restored by re-fetching feeds, always fits.
  * It also uses the same code path as manual backup and is schema-version independent.
* **Invalidation hygiene.** Room's `LimitOffsetPagingSource` re-runs `SELECT COUNT(*) FROM (query)` and the page query **whenever any observed table is written**. Room 3 source confirms both, and invalidation is table-granular. Joining a table that is written every 10 s would make the group feed re-query continuously during playback.

### Risks that could change the overall plan

1. **[cross-area] Schema split for high-churn columns.** Playback currently proposes `EpisodePlayState(positionMs, playedAt, …)` in one row. Downloads proposes `download(state, downloadedBytes, …)`. Both need splitting, or the list UI must not filter on them in SQL. If that is rejected, group feeds need a custom keyset `PagingSource` with throttled invalidation, which is more code.
2. **[cross-area] `identityKey` becomes a persisted contract.** Backups and the Auto Backup snapshot match episodes by `(podcastKey, identityKey)`. If the feeds area later changes the key algorithm, old backups lose their played and position state. The key algorithm needs a version number and a migration path.
3. **[cross-area] Import back-catalogue flood.** Importing 300 feeds creates tens of thousands of "unplayed" episodes. It can also fire 300 new-episode notifications and mass auto-downloads unless the first fetch after an import or restore is flagged `initialFetch` and suppressed. Feeds, downloads and notifications must honour that flag.
4. **`@RawQuery` returning `PagingSource` in Room 3 is UNVERIFIED.** Room 2 supported it. The Room 3 notes describe paging only via `PagingSourceDaoReturnTypeConverter`, which receives a `RoomRawQuery`, so it is likely. Confirm in the scaffolding spike. The fallback is about 10 generated `@Query` functions (sources × order).
5. **Interop unknowns.** I could not inspect Podcast Addict's OPML (closed source) or confirm that Apple Podcasts on iOS 26 can import or export OPML. The "Flat list" export option and the very tolerant importer mitigate this.

---

## Options considered

### 1. How podcasts relate to groups

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Many-to-many join table** (`podcast_group_member`) | Matches the "tech / news / fiction" overlap. Indexable both ways. FK cascades. Clean SQL for group feeds and the "Ungrouped" feed (`NOT EXISTS`). Precedent: AntennaPod tags | Needs settings-conflict rules, the OPML hybrid and a multi-select UI | **Chosen** |
| One group per podcast (`podcast.groupId`) | Simplest. OPML maps 1:1 to folders. No conflicts. Precedent: Pocket Casts folders, gPodder sections | Users must pick one group for a show that fits several. "News" plus "Tech" overlap is common. Changing this later is a data migration and a UX change | Rejected |
| Tags as a delimited string column (AntennaPod stores a `\u001e`-separated string in `FeedPreferences`) | Trivial to add | No index, no FK, no rename (rename means rewriting every row), `LIKE` scans for group feeds | Rejected |

### 2. Encoding groups in exported OPML

| Option | Effect on other importers | Neutrodyne round-trip | Verdict |
|---|---|---|---|
| A. Flat outlines + `category="tech,news"` | All flatteners: fine. FreshRSS: creates one category literally named `"tech, news"`. gPodder: ignores categories, so no sections | Full | Rejected as default; used as the "Flat list" option |
| B. Nested folders, a feed repeated in each of its groups | Flatteners see duplicates. Pocket Casts' reader dedupes via a `Set`. AntennaPod creates the feed once per element (dedupe behaviour on repeated `xmlUrl` UNVERIFIED). Folder-aware importers recreate every group | Full | Rejected (duplicate risk) |
| **C. Hybrid**: nested under the primary group only, plus `category` listing all groups | Flatteners: each feed once. Folder-aware (gPodder, FreshRSS): the primary group is recreated. FreshRSS ignores `category` when the outline has a parent folder | Full (categories ∪ folder) | **Chosen** |
| D. Namespaced extension (`xmlns:nd`, `nd:groups="uuid,…"`) | Ignored by everyone else ("Processors should ignore any attributes they do not understand") | Full, including colour and icon | Optional later, for colour and icon only. Membership stays in standard attributes |

### 3. OPML parsing strategy

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Pull parser cascade**: strict, then KXml `relaxed`, then regex salvage | Correct folder and category structure on valid files. Recovers something from broken files. Streaming, so low memory | Three code paths to test | **Chosen** |
| DOM (`DocumentBuilder`) | Simple tree walk | Whole document in memory (Overcast "extended" exports can be many MB). Fails hard on malformed input | Rejected |
| Line regex only (Pocket Casts' `OpmlUrlReader` splits on `<outline` and pulls `xmlUrl=`) | Survives any malformation | Loses folders, categories, titles, `subscribed="0"`, `isComment` and types | Used only as the last-resort salvage |

### 4. Import execution model

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Preview, then create pending podcasts, then let the refresh engine fetch with per-item status** | The library fills instantly. Resumable (state is in the DB). Shares the per-host limits and backoff with normal refresh. Dead feeds are kept (AntennaPod 3.1 moved to "always add feeds from OPML, even if download fails" after user complaints) | A "pending" podcast state shows in the UI | **Chosen** |
| Validate (fetch) every feed before subscribing | Only good feeds get added | Slow for 300+ feeds. Dead or temporarily down feeds are lost. Progress UI blocks the user | Rejected |
| Server-side import (Pocket Casts posts URLs to its server and polls) | Fast | No server, and a privacy cost | N/A |

### 5. Full backup format

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Versioned ZIP of JSON/JSONL + OPML** | Schema-independent, so it restores into any newer app version. Supports merge restore. Human-inspectable. Small (no show notes, re-fetched instead). The OPML inside is usable by other apps | We must write mappers and keep the format stable | **Chosen** |
| Raw DB copy (AntennaPod `DatabaseExporter`: WAL checkpoint, copy file, version check; refuses newer DBs: "import_no_downgrade") | Perfect fidelity, very little code | Replace-only. Tied to the schema version (no downgrade, and Room migrations run on foreign data). Contains device-specific rows (download paths, job IDs). Large. With Room 3 the DB must be closed and the process restarted | Rejected for user backups. `VACUUM INTO` remains useful for a "diagnostics DB export" |
| OPML only | Universal | Loses groups-as-multi, played state, positions, queue and settings | Only as a component |

### 6. Cloud backup

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Auto Backup, including only the snapshot ZIP + portable DataStore** | Zero-UI. Fits 25 MB. Covers device-to-device transfer. Same restore path as manual backup | The snapshot is at most about 24 h stale. First-launch restore needs a network refresh | **Chosen** |
| Auto Backup with default rules (DB + files + external files) | Zero code | Downloads in `getExternalFilesDir` exceed 25 MB, so **no backup at all**. Restored DB may contain stale download rows pointing to missing files | Rejected |
| Key/value `BackupAgentHelper` with OPML (AntennaPod `OpmlBackupAgent`) | Small | Subscriptions only. Key/value API. Agent runs in restricted mode | Rejected |
| `allowBackup="false"` | Simple | Users lose everything on a phone change | Rejected |

### 7. Group feed paging

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Room `LimitOffsetPagingSource`** (via `room3-paging`) | No custom code. Placeholders and jumping work. Invalidation is automatic | `COUNT(*)` plus `OFFSET` scan on every invalidation, which is acceptable at 50k rows if invalidations are rare | **Chosen**, with invalidation hygiene and benchmarks |
| Custom keyset `PagingSource` (key = `(sortDate, id)`) | O(page) loads at any depth. No count | Custom code. No placeholders or jumps. Must wire `InvalidationTracker.createFlow` ourselves | Fallback if benchmarks fail |
| Denormalised `group_feed` table maintained on write | Fastest reads | Write amplification on every membership or episode change, and consistency bugs | Rejected |

### 8. Where per-group settings live

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Typed tables per scope**: `podcast_settings(podcastId FK)`, `podcast_group_settings(groupId FK)`, global values in DataStore | FK cascade (deleting a group deletes its settings). Compile-time columns | Every new overridable setting adds a column to two tables | **Chosen** |
| One scope-keyed table (`scopeKey = "group:7"`, proposed in `playback.md`) | Generic | No FK, so orphans remain after group or podcast deletion. String parsing | Reconcile with playback: either adopt typed tables, or keep the generic table and delete scope rows inside `GroupRepository.delete()` |

### 9. Intent filters for receiving files

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Narrow filters** (OPML/XML MIME types, plus an `octet-stream` SEND, plus an API 31+ `pathSuffix=".opml"` alias) | Neutrodyne does not appear for every file. Works for the share sheet and most "Open with" cases | Some providers (Downloads `msf:` IDs, Gmail) expose neither a useful MIME type nor a file name, so the in-app picker is needed | **Chosen** |
| Broad `*/*` or `application/octet-stream` VIEW | Always offered | Hijacks "Open with" for every unknown file. Pocket Casts removed its content-scheme octet-stream filter because it "was opening 'install (1).apk'" | Rejected |

---

## Technical detail

### A. OPML as it exists in the wild

**Spec essentials (OPML 2.0, opml.org/spec2.opml):**

* `<opml version="2.0">` must have both `<head>` and `<body>`.
* Every `<outline>` **must** have `text`. "A missing text attribute in any outline element is an error."
* Subscription lists:
  * `type="rss"`, `text` and `xmlUrl` are **required**.
  * Optional: `description`, `htmlUrl`, `language`, `title` ("probably the same as text, it should not be omitted") and `version`.
  * `text` is user-editable, so "processors should not depend on it always containing the title of the feed".
* "Today, most subscription lists are a flat sequence of rss nodes, but some aggregators allow categorized subscription lists that are arbitrarily structured. A validator may flag these files, warning that some processors may not understand and preserve the structure."
* `category` is "a string of comma-separated slash-delimited category strings… To represent a 'tag,' the category string should contain no slashes." Examples: `category="/Boston/Weather"`, `category="/Harvard/Berkman,/Politics"`.
* `type` values are case-insensitive.
* `isComment="true"` comments out the subtree.
* `type="include"` and `type="link"` with a `url` attribute point to other OPML files or web pages (this is "inclusion").
* Extensions must be namespaced. "Processors should ignore any attributes they do not understand."
* Dates are RFC 822, and a 2-digit year is allowed.
* The autodiscovery MIME type is `text/x-opml`.

**What real apps write and read** (from source code where available):

| App | Export shape (verified) | Import behaviour (verified) |
|---|---|---|
| **AntennaPod** (`OpmlWriter.java`) | `<opml version="2.0">`, head `title`="AntennaPod Subscriptions", `dateCreated` (`dd MMM yy HH:mm:ss Z`). **Flat**: one outline per subscribed feed with `text`, `title` (both the feed title), `type` = **`"rss"` or `"atom"`** (non-standard `atom`), `xmlUrl`, optional `htmlUrl`. **Tags are not exported.** File name `antennapod-feeds-<date>.opml`, MIME `text/x-opml` | `OpmlReader` visits **every** `<outline>` at any depth. Folders are ignored. `title` is preferred over `text`, and the URL is used if both are missing. Outlines without `xmlUrl` are skipped. `type` is not checked. `OpmlImportActivity` shows a multiple-choice list with Select all/none. Charset comes from `BOMInputStream`, else UTF-8 (the XML declaration encoding is ignored). Picker: `GetContent("*/*")` |
| **Pocket Casts** (`OpmlExporter.kt`) | `<opml version="1.0">`, head `title`="Pocket Casts Feeds", body has **one wrapper** `<outline text="feeds">` containing `<outline type="rss" text="…" xmlUrl="…"/>` (no `title`, no `htmlUrl`). Feed URLs come from Pocket Casts' server (`exportFeedUrls`). File `podcasts_opml.xml`, shared as `text/xml`. **Folders exist in the app** (single `folder_uuid` per podcast) but **are not exported** | `OpmlUrlReader` reads **line by line**, splits on `<outline`, extracts `xmlUrl="…"` / `'…'`, unescapes 5 entities and dedupes in a `Set`. Folders and titles are ignored. URLs are posted to its server in chunks of 100 and polled. A WorkManager worker uses `setForeground(FOREGROUND_SERVICE_TYPE_DATA_SYNC)`. Import from URL is also supported |
| **Overcast** (basic export) | `<outline text="feeds">` wrapper containing `<outline type="rss" overcastId="…" text title xmlUrl htmlUrl overcastAddedDate="…"/>` (from Metacast's published sample, seen via a search snippet; the page itself returned 403 to me, so **partially verified**) | — |
| **Overcast** ("extended"/"All data", `overcast.fm/account/export_opml/extended`) | `<outline text="playlists">` containing `<outline type="podcast-playlist" title smart sorting includePodcastIds="101,202"/>`. Then `<outline text="feeds">` containing `type="rss"` outlines with `overcastId`, `subscribed="0|1"`, `notifications`, `overcastAddedDate`, **and nested child outlines** `type="podcast-episode"` with `overcastId`, `title`, `url`, `overcastUrl`, `enclosureUrl`, `played`, `progress` (seconds), `userDeleted`, `userUpdatedDate`, `pubDate`. Verified via the `overcast-to-sqlite` parser and its fixtures (third-party code targeting the real endpoint) | — |
| **gPodder** (`opml.py`) | `<opml version="2.0">`, head `title`="gPodder subscriptions" + `dateCreated`. **One folder per section**: `<outline title="X" text="X">` (each podcast is in exactly one section; the default section is derived as "Audio"/"Video"/"Other", **localised**). Feed outlines carry `title`=channel title, **`text`=channel description**, `xmlUrl` and `type="rss"`. No `htmlUrl` | Accepts `type` ∈ {`rss`, `link`} (case-insensitive) and takes **`url` as a fallback for `xmlUrl`**. An outline with `title == text` and no valid type is treated as a section |
| **Podcast Addict** | Settings → Backup/Restore → "Subscriptions only – Backup" (from Pocket Casts' help page). **Element shape UNVERIFIED** (closed source). The app also subscribes to YouTube channels and plain news RSS, so its exports can contain non-podcast feeds | UNVERIFIED |
| **Apple Podcasts** | Since macOS Catalina the Podcasts app has **no OPML export**. The subscription list lives in `~/Library/Containers/com.apple.podcasts/Data/Documents/PodcastsDB.plist`; third-party converters exist (kaspars.net, 2019). Pocket Casts' help still documents an iTunes-era "File > Library > Export Playlist → OPML" path and an iOS Shortcut. **iOS 26 status UNVERIFIED** | No OPML import (various sources) |
| **Google Podcasts** (shut down) | Exports via Takeout were known to be **malformed**: AntennaPod reported "Unterminated entity ref". Self-closing tags lacked `/`, and a developer noted "Google is known for exporting broken opml files" (Aug 2024) | — |
| **FreshRSS** (feed reader, `ImportService.php`) | — | A nested outline with children is a category, named from `text` or else `title`. **The innermost folder wins**. The `category` attribute is used **only when the outline has no parent folder**, and multiple values are joined with `", "` into **one** category name |
| **NewPipe** (YouTube) | JSON, not OPML: `{"app_version":"…","app_version_int":N,"subscriptions":[{"service_id":0,"url":"https://www.youtube.com/channel/UC…","name":"…"}]}` (`service_id` 0 = YouTube) | Accepts its JSON, and YouTube Takeout CSV/ZIP via NewPipeExtractor |
| **Google Takeout (YouTube)** | `subscriptions.csv`, always 3 columns: `Channel Id,Channel Url,Channel Title`. **Header names are localised**, column order is fixed, URLs are `http://www.youtube.com/channel/UC…`. Shipped inside a ZIP | — |

Conclusions for the importer:

* Accept **any** outline with an `xmlUrl` (or `url`, gPodder style), regardless of `type`. Map `atom` to RSS.
* **Skip** `podcast-episode` and `podcast-playlist` outlines and their subtrees. Children of a feed outline are never folders.
* Treat a single root "feeds" wrapper as **not a group**.
* gPodder's "Audio"/"Video"/"Other" become *proposed* groups that the user can untick.
* Honour Overcast `subscribed="0"` (pre-unchecked).
* Be ready for broken XML.

### B. Neutrodyne export format (hybrid)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0">
  <head>
    <title>Neutrodyne subscriptions</title>
    <dateCreated>Sun, 04 Oct 2026 21:30:00 +0000</dateCreated>
    <docs>http://opml.org/spec2.opml</docs>
  </head>
  <body>
    <!-- groups in user order; each feed appears once, under its primary (first) group -->
    <outline text="tech" title="tech">
      <outline type="rss" text="Accidental Tech Podcast" title="Accidental Tech Podcast"
               xmlUrl="https://atp.fm/rss" htmlUrl="https://atp.fm" category="tech,news"/>
      <outline type="rss" text="Marques Brownlee" title="Marques Brownlee"
               xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ"
               htmlUrl="https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ" category="tech"/>
    </outline>
    <outline text="news" title="news">
      <outline type="rss" text="The Daily" title="The Daily" xmlUrl="https://…" category="news"/>
    </outline>
    <outline text="fiction" title="fiction"/>          <!-- empty group: kept so structure round-trips -->
    <!-- ungrouped feeds at top level, sorted by title -->
    <outline type="rss" text="Some Show" title="Some Show" xmlUrl="https://…"/>
  </body>
</opml>
```

Rules:

* **Feed outline attributes:**
  * `type="rss"` always (also for Atom/YouTube; the spec has no Atom value).
  * `text` = the user's custom title if set, else the feed title. `title` = the feed title.
  * `xmlUrl` = the current canonical `feedUrl`. Basic-auth userinfo is stripped unless the user opted in.
  * `htmlUrl` if known. `language` if known.
  * `category` lists every group name, comma-separated (see escaping below).
  * Skip `description` (bloat).
* **Folder outline:** `text` = `title` = group name, with no `type` and no `xmlUrl`. Every importer above ignores it as a feed.
* **Primary group** = the member group with the lowest `sortOrder`. Ungrouped feeds go last, sorted by title.
* **Escaping inside `category`:** group names may contain `,` and `/`, which are delimiters in `category`. Percent-encode `%`, `,` and `/` inside each token (`%25`, `%2C`, `%2F`). The folder `text` keeps the raw name. On import, percent-decode a token only if it contains `%[0-9A-Fa-f]{2}` sequences.
* **Invalid XML characters:** Android's `KXmlSerializer` **throws `IllegalArgumentException("Illegal character (U+…)")`** for chars outside the XML 1.0 set. A podcast title with U+0008 would otherwise abort the entire export. Strip `[\x00-\x08\x0B\x0C\x0E-\x1F￾￿]` and lone surrogates first.
* **Options sheet:**
  * Format: Grouped (default) or Flat list (no folders, keeps `category`).
  * Include YouTube channels (default on, with the caption "Other podcast apps may not be able to play these").
  * Include passwords for private feeds (default off; feeds area Q10).
  * Show a warning when any feed URL looks tokenised: "This file contains private access links for N feeds."
* **Single-group share** ("Share group" in the group menu): the same writer with one folder.

The writer is pure JVM in `:feeds`. It is tiny, so it should be hand-written; `android.util.Xml` is not available in a JVM module:

```kotlin
class OpmlWriter {
    fun write(out: OutputStream, doc: ExportDocument, flat: Boolean) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n<opml version=\"2.0\">\n  <head>\n")
        w.write("    <title>${esc(doc.title)}</title>\n    <dateCreated>${rfc822(doc.createdAt)}</dateCreated>\n")
        w.write("    <docs>http://opml.org/spec2.opml</docs>\n  </head>\n  <body>\n")
        if (flat) doc.allFeeds.forEach { w.feed(it, indent = 4) }
        else {
            doc.groups.forEach { g ->
                val members = doc.feedsWithPrimaryGroup(g.id)
                if (members.isEmpty()) w.write("    <outline text=\"${esc(g.name)}\" title=\"${esc(g.name)}\"/>\n")
                else {
                    w.write("    <outline text=\"${esc(g.name)}\" title=\"${esc(g.name)}\">\n")
                    members.forEach { w.feed(it, indent = 6) }
                    w.write("    </outline>\n")
                }
            }
            doc.ungrouped.forEach { w.feed(it, indent = 4) }
        }
        w.write("  </body>\n</opml>\n"); w.flush()
    }

    private fun Writer.feed(f: ExportFeed, indent: Int) {
        write(" ".repeat(indent) + "<outline type=\"rss\" text=\"${esc(f.displayTitle)}\" title=\"${esc(f.feedTitle)}\"")
        write(" xmlUrl=\"${esc(f.xmlUrl)}\"")
        f.htmlUrl?.let { write(" htmlUrl=\"${esc(it)}\"") }
        f.language?.let { write(" language=\"${esc(it)}\"") }
        if (f.groupNames.isNotEmpty()) write(" category=\"${esc(f.groupNames.joinToString(",") { catToken(it) })}\"")
        write("/>\n")
    }

    private fun catToken(name: String) = name.replace("%", "%25").replace(",", "%2C").replace("/", "%2F")
    private fun esc(s: String) = buildString {
        for (ch in s.stripInvalidXmlChars()) when (ch) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\n' -> append("&#10;"); '\r' -> append("&#13;"); '\t' -> append("&#9;")
            else -> append(ch)
        }
    }
}
```

### C. OPML import pipeline

```
 Entry (picker / VIEW / SEND / URL / Settings)
   └─► 1. Acquire: copy bytes to cacheDir/imports/<sessionUuid>.bin (cap 50 MiB), sniff format
        └─► 2. Parse (:feeds, pure JVM): strict → relaxed → salvage   ──► OpmlDocument + warnings
             └─► 3. Classify & normalise each entry (URL fixes, YouTube detection, dedupe vs file & library)
                  └─► 4. Persist import_session + import_item rows (status=PREVIEW)
                       └─► 5. Preview UI (selection, group mapping, toggles)  ── Cancel → delete session
                            └─► 6. Commit (one transaction): pending podcasts + memberships + aliases
                                 └─► 7. ImportFetchWorker → refresh engine (per-host limits); per-item status
                                      └─► 8. Report: progress screen + summary notification; fix-ups
```

**1. Acquire.**

* For `content://` URIs from VIEW or SEND, the read grant lasts only "while the stack of the receiving Activity is active" (FileProvider docs). So copy the bytes **before** finishing the entry activity or handing work to WorkManager.
* Reject files over 50 MiB with a clear message.
* Sniff the format from the first bytes, not the MIME type:
  * `PK\x03\x04` means ZIP: either a Neutrodyne backup (`manifest.json`) or a Takeout export (find `*.csv` with ≥ 3 columns and `UC…` IDs).
  * `{` after whitespace means NewPipe JSON (`subscriptions` array) or a Neutrodyne `library.json`.
  * A first line with 3 comma-separated columns and a second line starting `UC` means a Takeout CSV.
  * Otherwise treat it as XML/OPML.
* **Import from URL**: in-app paste box, or text shared via SEND `text/plain` ending in `.opml`. Download with the shared OkHttp client under the same cap. Do **not** follow `type="include"` or `type="link"` outlines automatically; list them as "Linked lists (not imported)".

**2. Parse.** `org.xmlpull.v1.XmlPullParser` is available on Android (KXml) and via kxml2 in JVM tests (`stack.md`).

* `setInput(InputStream, null)`: KXmlParser auto-detects UTF-32/UTF-16 BOMs, `<?` in UTF-16/32, a UTF-8 BOM, and the `encoding="…"` in an ASCII-compatible XML declaration. It defaults to UTF-8. Do not pass a `Reader` (AntennaPod's approach ignores `encoding="ISO-8859-1"` declarations).
* Before `setInput`, **skip leading ASCII whitespace** while preserving a BOM. A file starting `\n<?xml` fails strict parsing ("processing instructions must not start with xml").
* Leave `FEATURE_PROCESS_DOCDECL` **off**, and reject documents whose prolog contains `<!ENTITY`. This blocks XXE and "billion laughs" expansion.
* Leave namespaces **off** so prefixed attributes keep their raw names.
* **Attribute lookup is case-insensitive.** Build a lowercase map per outline: `xmlurl`, `htmlurl`, `text`, `title`, `type`, `category`, `url`, `iscomment`, `subscribed`.
* **Limits:** depth ≤ 32, feed outlines ≤ 10,000, total outlines ≤ 200,000 (Overcast extended exports with episodes are large), attribute value ≤ 8 KiB.
* **Fallbacks:**
  * On `XmlPullParserException`, retry with `"http://xmlpull.org/v1/doc/features.html#relaxed"` set to `true`. This tolerates undefined entities like `&nbsp;` and some unquoted attributes.
  * If that still fails, run a **salvage scan**: Pocket Casts-style regex over the text for `<outline[^>]*>`, extracting `xmlUrl`, `text` and `title`.
  * Mark the result `recoveredBySalvage = true`; the UI says "This file is damaged; folders could not be read".

```kotlin
data class OpmlEntry(
    val ordinal: Int,
    val xmlUrlRaw: String,
    val text: String?, val title: String?, val htmlUrl: String?,
    val type: String?,                 // lowercased raw type, may be null
    val folderPath: List<FolderRef>,   // ancestor folder outlines, outermost first
    val categories: List<String>,      // raw tokens from @category
    val commented: Boolean,            // inside isComment="true"
    val extras: Map<String, String>,   // unknown attrs: overcastId, subscribed, notifications, …
)
data class FolderRef(val id: Int, val name: String, val depth: Int)

private enum class Frame { FOLDER, FEED, IGNORED }

fun parseOpml(p: XmlPullParser, limits: Limits): OpmlParseResult {
    val stack = ArrayDeque<Pair<Frame, FolderRef?>>()
    var commentDepth = Int.MAX_VALUE
    val entries = mutableListOf<OpmlEntry>(); val folders = mutableListOf<FolderRef>()
    while (true) when (p.next()) {
        XmlPullParser.START_TAG -> if (p.name.equals("outline", ignoreCase = true)) {
            check(stack.size < limits.maxDepth) { "nesting too deep" }
            val a = p.attrsLowercase()
            val parentIgnored = stack.any { it.first != Frame.FOLDER }   // children of feeds/episodes/playlists
            if (a["iscomment"] == "true") commentDepth = minOf(commentDepth, stack.size)
            val type = a["type"]?.lowercase()
            val url = a["xmlurl"] ?: a["url"]?.takeIf { type == "rss" || type == "link" && !it.endsWith(".opml", true) }
            when {
                parentIgnored || type == "podcast-episode" || type == "podcast-playlist" || type == "include" ->
                    stack.addLast(Frame.IGNORED to null)
                url != null -> {
                    entries += OpmlEntry(entries.size, url.trim(), a["text"], a["title"], a["htmlurl"], type,
                        stack.mapNotNull { it.second }, a["category"]?.split(',').orEmpty(),
                        commented = stack.size >= commentDepth, extras = a - KNOWN_KEYS)
                    check(entries.size <= limits.maxFeeds) { "too many feeds" }
                    stack.addLast(Frame.FEED to null)
                }
                else -> {
                    val name = (a["text"] ?: a["title"]).orEmpty().trim()
                    val ref = FolderRef(folders.size, name, stack.size).also { folders += it }
                    stack.addLast(Frame.FOLDER to ref)
                }
            }
        }
        XmlPullParser.END_TAG -> if (p.name.equals("outline", true)) {
            stack.removeLast(); if (stack.size < commentDepth) commentDepth = Int.MAX_VALUE
        }
        XmlPullParser.END_DOCUMENT -> return OpmlParseResult(entries, folders)
    }
}
```

**3. Classify and normalise.** Use the feeds area's `UrlNormalizer` and alias table.

* **Scheme fixes:**
  * `feed://`, `itpc://`, `pcast://` and `podcast://` become `https://`. For `feed:https://…`, strip the prefix.
  * A missing scheme (`example.com/rss`) becomes `https://`.
  * Trim whitespace and newlines. Reject relative URLs and non-http(s) schemes.
* **Embedded credentials:** move `user:pass@` into the credential store (`feeds.md` §5).
* **YouTube detection:**

  | Form | Example |
  |---|---|
  | Channel feed | `youtube.com/feeds/videos.xml?channel_id=UC…` |
  | Playlist feed | `…?playlist_id=PL…` |
  | Uploads-style playlists | `UU…`, `UULF…` (long-form uploads), etc. Map `UULF<x>` to channel `UC<x>` with the "no Shorts" option (the playlist feed's `<title>` is just **"Videos"**, so take the name from `author/name`) |
  | Channel page | `youtube.com/channel/UC…` |
  | Needs resolution by the YouTube module | `/@handle`, `/c/…`, `/user/…` |

  All of these become `kind = YOUTUBE`.
* **Duplicates within the file:** the same normalised URL in several folders is merged into one item with the **union** of group names (handles exports that repeat feeds per folder).
* **Already subscribed:** match by normalised URL against `podcast.feedUrl` and `podcast_url_alias`. Show "Already subscribed"; the item is unselected but its **group assignments still apply** if the user keeps group import on.
* **Group names** for each entry are `categories ∪ {immediate parent folder}`:
  * Category tokens: strip leading and trailing `/`, take the segment after the last `/` (so `/Harvard/Berkman` becomes `Berkman`), and percent-decode `%2C`, `%2F` and `%25`.
  * Trim, NFC-normalise and collapse inner whitespace. Drop empty names and names over 40 chars (truncate with a warning).
  * Match existing groups by `nameKey = lowercase(NFC(name))`.
* **Wrapper detection** (default on, can be flipped in the preview):
  * A top-level folder is a wrapper when it is the **only** top-level outline (no top-level feeds) and either contains every feed or is named one of `feeds`, `subscriptions`, `podcasts`, `podcast feeds` (case-insensitive).
  * Overcast's `playlists` outline never yields feeds.
* **Pre-selection:**
  * Selected by default: new and valid items.
  * Unselected by default: duplicates, already-subscribed items, invalid URLs (disabled), items inside `isComment="true"`, Overcast `subscribed="0"`, and `type="link"` HTML pages.

**4–5. Preview UI** (Compose; a `LazyColumn` handles 10k rows). It is backed by `import_item` rows, so it survives process death.

* **Header:** "142 podcasts and 3 YouTube channels in *antennapod-feeds-2026-10-01.opml*", plus warnings ("Recovered from a damaged file", "12 entries were ignored: episode lists").
* **Groups card** (shown only if folders or categories were found). One row per proposed group:
  * name, inline rename, checkbox, member count;
  * a badge: "New group" or "Adds to existing 'news'".
  * A master switch "Import folders as groups".
  * An "Import everything into group…" picker. It is also reachable from a group's menu ("Import OPML into this group"), like FreshRSS's forced category.
* **List:** monogram cover (no network in the preview), title (`title ?: text ?: host`), host, chips (YouTube / Already subscribed / Duplicate / Invalid / Private feed) and a checkbox.
* **Controls:** search field, filter "Only new", Select all/none, sticky bottom bar "Subscribe to 139".
* **Options:**
  * "Treat existing episodes as: Unplayed / Played (start fresh)". The default is an open question.
  * "Notify me about new episodes for these podcasts" (default off).

**6. Commit.** One write transaction, chunked by 500 for huge lists:

* Insert `podcast` rows with `status = PENDING_FIRST_FETCH`, `title` from OPML, `subscribedAt = now`, `nextRefreshAt = now` and `initialFetch = true` **[cross-area]**.
* Insert `podcast_url_alias` rows for the original URL forms.
* Create groups that don't exist, appending to `sortOrder`.
* Insert memberships (also for already-subscribed podcasts).
* Set `import_item.status = QUEUED` and `podcastId`.

The library and group screens show the new podcasts immediately with placeholder covers.

**7. Fetch.**

* `ImportFetchWorker` is unique work named `import-<sessionId>`, expedited with `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST` and constrained to a connected network.
* It calls the refresh engine for the session's podcast IDs. The engine applies concurrency 6 and 2 per host (`feeds.md`), and is resumable.
* Space out YouTube channels (2 concurrent on `youtube.com`).
* Per item, write the outcome:

  | Status | Meaning |
  |---|---|
  | `SUBSCRIBED` | OK |
  | `MERGED` | A redirect or `podcast:guid` matched an existing podcast; memberships moved, pending row removed |
  | `NOT_A_FEED` | HTML page; autodiscovery attempted first |
  | `NO_MEDIA` | Feed parsed but has zero audio/video enclosures and is not YouTube, e.g. a blog from a feed-reader OPML |
  | `AUTH_REQUIRED` | 401 |
  | `GONE` | 404 or 410 |
  | `FETCH_FAILED(kind)` | Other fetch failure |
  | `INVALID_URL` | — |

* No FGS is needed: progress is in the DB, and if the job is stopped by quota it resumes later.
* If testing shows that 1,000+ feed imports stall, run the batch as a user-initiated data transfer job on API 34+, reusing `downloads.md`'s UIDT `JobService` infrastructure. `dataSync` is the documented FGS type for "Import or export operations", but it carries the Play declaration burden and the 6 h/24 h cap noted in `downloads.md`.

**8. Report.**

* Live progress: "Fetched 87 of 139", from a Flow over `import_item`.
* The final notification ("139 added, 3 need attention") deep-links to the report.
* Report sections:
  * **Need attention**, with actions Retry / Edit URL / Remove / Enter password;
  * **No audio or video found** (27), with "Remove all";
  * **Merged with existing**;
  * **Done**.
* Failed podcasts stay subscribed with an error badge (the AntennaPod 3.1 behaviour).

```kotlin
@Entity(tableName = "import_session")
data class ImportSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val sourceName: String?,          // display name from OpenableColumns.DISPLAY_NAME
    val sourceFormat: ImportFormat,   // OPML, NEWPIPE_JSON, TAKEOUT_CSV, NEUTRODYNE_BACKUP
    val state: ImportState,           // PREVIEW, COMMITTED, FETCHING, DONE, CANCELLED
    val recoveredBySalvage: Boolean,
    val warningsJson: String?,
)

@Entity(
    tableName = "import_item",
    primaryKeys = ["sessionId", "ordinal"],
    foreignKeys = [ForeignKey(ImportSessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId", "status")],
)
data class ImportItemEntity(
    val sessionId: Long, val ordinal: Int,
    val title: String?, val originalUrl: String, val normalizedUrl: String?,
    val kind: SourceKind,            // RSS, YOUTUBE
    val groupNamesJson: String,      // ["tech","news"]
    val selected: Boolean,
    val status: ImportItemStatus,    // PREVIEW, QUEUED, SUBSCRIBED, ALREADY_SUBSCRIBED, MERGED, DUPLICATE_IN_FILE,
                                     // INVALID_URL, NOT_A_FEED, NO_MEDIA, AUTH_REQUIRED, GONE, FETCH_FAILED
    val podcastId: Long?, val errorDetail: String?,
)
```

Delete sessions 7 days after `DONE` and remove the cached payload file.

### D. Other import sources (same pipeline, different parsers in `:feeds`)

* **NewPipe JSON:** read `subscriptions[]` where `service_id == 0` and `url` is a YouTube channel URL. `name` becomes the title. Other services (SoundCloud = 1, PeerTube, …) are listed as "Not supported".
* **Takeout CSV/ZIP:**
  * Use a real CSV parser, because titles can contain commas and quotes. NewPipe's `split(",")` is fragile but only needs columns 0–1.
  * Skip the first line, since header names are localised.
  * Column 0 is the channel ID (`UC…`), column 2 the title.
  * For a ZIP, scan entries ending in `.csv` and take the first one that yields valid rows (NewPipeExtractor's approach).
  * Never extract to disk by entry name (zip-slip).
* **Neutrodyne backup ZIP:** routes to the restore flow (§G).
* All produce `ImportItem(kind = YOUTUBE, url = https://www.youtube.com/feeds/videos.xml?channel_id=UC…)`. Takeout and NewPipe files carry no groups, so the preview offers "Put all into group: [YouTube ▾]".

### E. Exporting: SAF and the share sheet

```kotlin
// Compose screen (feature:importexport)
val createOpml = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("text/x-opml")   // mime-typed ctor; the no-arg one is deprecated since activity 1.5.0
) { uri -> if (uri != null) viewModel.exportOpmlTo(uri, options) }
Button(onClick = { createOpml.launch("neutrodyne-subscriptions-${LocalDate.now()}.opml") }) { Text("Save to file…") }

// ViewModel / repository; run in the application scope so leaving the screen doesn't cancel it
suspend fun exportOpmlTo(uri: Uri, o: ExportOptions) = withContext(io) {
    resolver.openOutputStream(uri, "wt")!!.use { opmlWriter.write(it, exportModel(o), flat = o.flat) }  // "w" may not truncate
}

// Share sheet
suspend fun shareOpml(ctx: Context, o: ExportOptions) {
    val f = File(ctx.cacheDir, "exports/neutrodyne-subscriptions-${LocalDate.now()}.opml").apply { parentFile!!.mkdirs() }
    withContext(io) { f.outputStream().use { opmlWriter.write(it, exportModel(o), o.flat) } }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.exports", f)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/x-opml"                                  // AntennaPod and Pocket Casts both accept SEND text/x-opml
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, f.name)
        clipData = ClipData.newRawUri(f.name, uri)            // share-sheet preview + grant propagation
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, null))
}
```

```xml
<provider android:name="androidx.core.content.FileProvider"
          android:authorities="${applicationId}.exports"
          android:exported="false" android:grantUriPermissions="true">
    <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/export_paths"/>
</provider>
<!-- res/xml/export_paths.xml -->
<paths><cache-path name="exports" path="exports/"/></paths>
```

**Why the MIME type is `text/x-opml` and not `text/xml`.** Neither Android MIME table maps `.opml` (checked: `external/mime-support/mime.types` and `frameworks/base/mime/java-res/android.mime.types`). `FileUtils.splitFileName()`, used by `ExternalStorageProvider` and other file-system providers, keeps the requested display name when the extension's MIME type matches the requested one, or when the requested MIME type has no extension mapping:

* `text/x-opml` + `x.opml` gives **`x.opml`**.
* `text/xml` + `x.opml` gives **`x.opml.xml`** (it "insists that create file matches requested MIME").
* Backups use `application/zip` + `.zip`, which is consistent.

Cloud providers such as Drive have their own logic (UNVERIFIED).

Other export details:

* Delete `cache/exports/*` older than 24 h on app start.
* After "Save to file", show a snackbar with "Share" (AntennaPod pattern).
* Exporting 1,000 feeds takes well under 100 ms, so no progress UI is needed.

### F. Receiving OPML from other apps

```xml
<activity android:name=".importexport.ExternalImportActivity"
          android:exported="true"
          android:theme="@style/Theme.Neutrodyne.Translucent.NoAnimation"
          android:excludeFromRecents="true"
          android:label="@string/import_into_neutrodyne">
    <!-- "Open with" / Share for typed OPML or XML -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW"/>
        <action android:name="android.intent.action.SEND"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <!-- mimeType without scheme ⇒ content: and file: are assumed -->
        <data android:mimeType="text/x-opml"/>
        <data android:mimeType="text/xml"/>
        <data android:mimeType="application/xml"/>
    </intent-filter>
    <!-- Share of an untyped file (Files app reports .opml as application/octet-stream) -->
    <intent-filter>
        <action android:name="android.intent.action.SEND"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <data android:mimeType="application/octet-stream"/>
    </intent-filter>
</activity>

<!-- API 31+: "Open with" for content://…/*.opml regardless of reported MIME type.
     pathSuffix only exists from API 31 and path attributes need scheme AND host; on older
     platforms the filter would degrade to "every content file", so the alias is disabled there. -->
<activity-alias android:name=".importexport.OpmlByExtension"
                android:targetActivity=".importexport.ExternalImportActivity"
                android:enabled="@bool/api31_or_newer" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.VIEW"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <data android:scheme="content" android:host="*" android:mimeType="*/*" android:pathSuffix=".opml"/>
    </intent-filter>
</activity-alias>
```

How `ExternalImportActivity` behaves:

* It has no UI of its own.
* It reads `intent.data` (VIEW), or `EXTRA_STREAM` / `clipData` (SEND), or `EXTRA_TEXT` (a URL).
* It queries `OpenableColumns.DISPLAY_NAME` and `SIZE`, then copies to cache with a progress spinner if the copy takes more than 300 ms.
* It creates an `import_session` and starts `MainActivity` with an **internal explicit intent** carrying only the session ID. `MainActivity` keeps no data filters. Then it calls `finish()`.
* Non-OPML payloads get a friendly "This doesn't look like a podcast list" dialog.

Further notes on receiving:

* **Android 16 Safer Intents** is opt-in through `<application android:intentMatchingFlags="enforceIntentFilter">`. Explicit intents must match the target's filters, and action-less intents match nothing.
  * The filters above are fully specified, so opting in is safe.
  * Google's roadmap is to make strict resolution the default eventually, so design for it now.
* **`file://` URIs:** do not declare them; content URIs are the norm.
* **`http(s)` OPML links** are best handled by the in-app "Import from URL" box and by SEND `text/plain`. Browsers mostly download files instead of offering VIEW.

### G. Full backup and restore

**Archive layout** (`neutrodyne-backup-2026-10-04-2130.zip`, MIME `application/zip`):

| Entry | Content | Notes |
|---|---|---|
| `manifest.json` | format ID, `formatVersion`, `minReaderVersion`, app version, DB schema version, `createdAt`, kind (MANUAL / SCHEDULED / AUTO_SNAPSHOT), per-entry byte size + SHA-256 + record count | Read first. Refuse when `minReaderVersion > supported` ("made by a newer Neutrodyne") |
| `library.json` | podcasts (identity + user overrides), groups (uuid, name, order, colour, icon, view prefs, settings, rule), memberships by podcast key and group uuid, per-podcast settings | Small |
| `episodes.jsonl` | **one JSON object per line**: the episode stub plus user state for every episode with state (played, position > 0, starred, queued, download tombstone, downloaded) | Streamed, so no experimental API is needed |
| `queue.json` | Up Next as episode refs; `PlaySession` (current item, position, context type + group uuid + order) | — |
| `settings.json` | whitelisted portable DataStore keys with typed values | Device-specific keys excluded |
| `subscriptions.opml` | the §B export (grouped) | Lets other apps use the backup |

```kotlin
@Serializable data class BackupManifest(
    val format: String = "neutrodyne-backup",
    val formatVersion: Int = 1,
    val minReaderVersion: Int = 1,
    val createdAt: String,                 // ISO-8601 UTC
    val appVersionName: String, val appVersionCode: Long, val dbSchemaVersion: Int,
    val kind: BackupKind,
    val entries: List<EntryInfo>,          // name, bytes, sha256, records
)

@Serializable data class LibraryV1(val podcasts: List<PodcastV1>, val groups: List<GroupV1>)

@Serializable data class PodcastV1(
    val key: String,                       // UrlNormalizer.forIdentity(feedUrl): the cross-device identity
    val feedUrl: String,
    val podcastGuid: String? = null,       // only when real (podcastGuidDerived = false)
    val source: SourceKind = SourceKind.RSS,
    val youtubeChannelId: String? = null,
    val aliases: List<String> = emptyList(),
    val title: String? = null, val customTitle: String? = null, val artworkUrl: String? = null,
    val subscribedAt: String? = null,
    val includeInAll: Boolean = true,
    val groupUuids: List<String> = emptyList(),
    val settings: PodcastSettingsV1? = null,
    val needsCredentials: Boolean = false, // credentials are never in the backup (v1)
)

@Serializable data class GroupV1(
    val uuid: String, val name: String, val sortOrder: Int,
    val colorArgb: Int? = null, val iconKey: String? = null,
    val kind: String = "MANUAL", val rule: JsonElement? = null,
    val feedOrder: String = "NEWEST_FIRST", val playOrder: String = "NEWEST_FIRST",
    val filters: List<String> = emptyList(), val mediaFilter: String = "ALL",
    val hideOlderThanDays: Int? = null, val showAsTab: Boolean = true,
    val settings: GroupSettingsV1? = null,
)

@Serializable data class EpisodeLineV1(        // one line of episodes.jsonl
    val p: String,                             // podcast key
    val k: String,                             // identityKey (feeds.md §4.2), plus kv = key algorithm version
    val kv: Int = 1,
    val guid: String? = null, val t: String? = null, val d: String? = null,   // title, pubDate (ISO)
    val u: String? = null, val ty: String? = null, val dur: Long? = null,     // enclosure url/type, duration ms
    val yt: String? = null,                    // externalMediaId (YouTube videoId)
    val played: String? = null, val pos: Long? = null, val posAt: String? = null,
    val star: Boolean = false, val dl: Boolean = false, val dlDismissed: Boolean = false,
    val ts: String,                            // last user-state change, for merge conflict resolution
)
```

Writing and reading:

* Write by streaming into a `ZipOutputStream` over the SAF `OutputStream`. kotlinx.serialization: `Json { encodeDefaults = false; explicitNulls = false }` for writing, `ignoreUnknownKeys = true` for reading.
* `episodes.jsonl` is written line by line with `encodeToString`. `encodeToStream`/`decodeToSequence` exist but are still `@ExperimentalSerializationApi`.
* Snapshot consistency: read everything inside one `useReaderConnection` / read transaction, so the archive is a consistent point in time.

**Restore** (`RestoreWorker`; one preview screen first):

1. **Validate.**
   * Only known entry names are processed; never use entry names as paths.
   * Cap: uncompressed total ≤ 256 MiB, each entry ≤ 128 MiB, ≤ 16 entries (zip bomb).
   * Check SHA-256s against the manifest.
2. **Preview.** "Backup from 4 Oct 2026 (Neutrodyne 1.3): 142 podcasts, 6 groups, 3,214 played episodes, 12 in Up Next, settings." Checkboxes per category. Mode: **Merge** (default) or **Replace** (wipes library, state, queue and groups after a confirmation; downloads stay on disk and are reconciled).
3. **Transaction A, library.**
   * Groups: upsert by `uuid`, then by `nameKey` (a name match adopts the backup uuid only if the local group has no remote identity yet).
   * Podcasts: match by key, alias, or `podcastGuid`; otherwise insert as `PENDING_FIRST_FETCH`.
   * Memberships: union.
   * Per-podcast and per-group settings: backup wins in Replace mode; local wins in Merge mode where set.
4. **Transaction B, episodes** (chunks of 1,000 lines).
   * Find the episode by `(podcastId, identityKey)`, falling back to enclosure URL or `guid`.
   * If missing, **insert a stub** `episode` row (`inFeed = false`, fields from the line). The next refresh's diff matches it by `identityKey` (`feeds.md` §4.2–4.3) and fills the metadata, and the user state survives.
   * Merge state:
     * `played = local.played || backup.played`, with `playedAt = max`;
     * position = the one with the newer `posAt`;
     * `star` = OR;
     * `dlDismissed` = OR.
5. **Queue and session.**
   * Replace mode: restore as-is.
   * Merge mode: append backup Up Next items not already queued. Restore the session only if none is active.
6. **Settings.** Apply whitelisted keys; unknown keys are ignored.
7. **Refresh.** Schedule a refresh of the restored podcasts with `initialFetch = true` (no notifications or auto-download storm). Offer "Re-download the 23 episodes that were downloaded on your old device?" from the `dl` flags.

**Scheduled backup to a folder** (optional; AntennaPod precedent: every 3 days, keep 5):

* The user picks a folder with `ACTION_OPEN_DOCUMENT_TREE`, and we call `takePersistableUriPermission`. On Android 11+ the picker refuses the storage root and `Download/` itself; a subfolder such as `Download/Neutrodyne` works (`downloads.md`).
* A WorkManager periodic job (weekly by default, requires charging + battery not low) writes `neutrodyne-backup-<yyyy-MM-dd>.zip` and deletes the oldest beyond 5. It matches files with a strict regex. Watch for providers renaming to `name (1).zip`, which AntennaPod's regex would never clean up; include the time in the name.
* If the grant is lost, post an error notification (as AntennaPod does).

**Diagnostics DB export** (developer setting): `VACUUM INTO '<cache>/diag.db'` (SQLite ≥ 3.27; bundled driver) produces a consistent, minimal copy of the live DB without closing Room. Share it via `FileProvider`. Never import it.

### H. Android Auto Backup configuration

Facts (developer.android.com, "Back up user data with Auto Backup"):

* Up to **25 MB per app**. Over that, the system "calls `onQuotaExceeded()` and doesn't back up data to the cloud".
* Backup runs when at least 24 h have passed, the device is idle, and it is on Wi-Fi (unless the user allows mobile data), "roughly every night". Only changed data is uploaded.
* "During Auto Backup, the system shuts down the app".
* Restore happens at install time, "after the APK is installed but before the app is available to be launched".
* By default it includes shared prefs, `getFilesDir()`, `getDatabasePath()` and **`getExternalFilesDir()`**, and it excludes cache, code cache and `getNoBackupFilesDir()`.
* "If you specify an `<include>` element, the system no longer includes any files by default and backs up only the files specified."
* Apps targeting 31+ on Android 12+ use `android:dataExtractionRules` (`<cloud-backup>`, `<device-transfer>`, and from Android 16 QPR2 `<cross-platform-transfer platform="ios">`). Devices on Android 11 and lower use `android:fullBackupContent`. Since minSdk is 26, ship both.
* A custom `BackupAgent` runs in **restricted mode**: "the base-class `Application` is instantiated instead of any subclass". So Hilt is not initialised. Avoid needing one.

```xml
<application
    android:allowBackup="true"
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="@xml/backup_rules"
    …>
```

```xml
<!-- res/xml/data_extraction_rules.xml  (Android 12+) -->
<data-extraction-rules>
    <cloud-backup disableIfNoEncryptionCapabilities="false">
        <include domain="file" path="backup/auto-snapshot.zip"/>
        <include domain="file" path="datastore/settings.preferences_pb"/>
    </cloud-backup>
    <device-transfer>
        <include domain="file" path="backup/auto-snapshot.zip"/>
        <include domain="file" path="datastore/settings.preferences_pb"/>
    </device-transfer>
</data-extraction-rules>

<!-- res/xml/backup_rules.xml  (Android 8.0–11) -->
<full-backup-content>
    <include domain="file" path="backup/auto-snapshot.zip"/>
    <include domain="file" path="datastore/settings.preferences_pb"/>
</full-backup-content>
```

Because there are `<include>` elements, everything else is excluded: the Room DB, downloads in external files, Coil's disk cache, `datastore/device_settings.preferences_pb`, credentials, and import caches.

**[cross-area] Split DataStore into two files.**

* `settings` (portable; backed up): theme, skip intervals, default speed, refresh interval, and so on.
* `device_settings` (not backed up): SAF tree URIs for the download folder and the backup folder (persisted grants do not transfer), storage volume UUID, per-device onboarding flags, notification-permission prompts.

The file path is `filesDir/datastore/<name>.preferences_pb` (the `preferencesDataStoreFile` source).

**Snapshot production:**

* `AutoSnapshotWorker`, unique periodic work every 24 h with the device idle and charging, writes `files/backup/auto-snapshot.zip.tmp` and renames it atomically.
* Also enqueue a one-off run (10-minute initial delay, `REPLACE`) after significant changes: subscribe/unsubscribe, group edit, an import or restore finishing.
* Size guard: if the snapshot exceeds 20 MB, drop stubs for played episodes older than 365 days (keep only `p` + `k` + `played`). If it is still too large, keep subscriptions, groups and in-progress items only. Log the degradation.

**Restore on first launch:**

* In `RoomDatabase.Callback.onCreate`, set an in-memory flag `freshDatabase = true`. This is the only reliable signal: the restored DataStore could carry flags from the old device.
* After DI is ready, if `freshDatabase && files/backup/auto-snapshot.zip exists`, run the §G restore in **Replace** mode **without a preview**.
* Show a blocking-light "Restoring your library…" screen: the library appears immediately, and episodes fill in as feeds refresh.
* Then rename the file to `restored-<ts>.zip`; it is deleted after the next successful snapshot.

**Testing:**

* Local transport: `adb shell bmgr enable true`, `adb shell bmgr transport com.android.localtransport/.LocalTransport`, `adb shell bmgr backupnow <pkg>`, uninstall, reinstall.
* Device-to-device test mode: `settings put secure backup_enable_d2d_test_mode 1` and the GMS `D2dTransport` (commands as in the "Test backup and restore" page).
* Add a CI instrumentation test for "fresh DB + snapshot gives the restored library".

`disableIfNoEncryptionCapabilities="false"` means backups also happen on devices without a screen lock. The snapshot contains feed URLs, which can embed private tokens. See the open questions.

### I. Groups data model (Room 3, package `androidx.room3`)

The `podcast` and `episode` tables are the feeds area's. The tables below are owned by groups.

```kotlin
@Entity(
    tableName = "podcast_group",
    indices = [Index("uuid", unique = true), Index("nameKey", unique = true), Index("sortOrder")],
)
data class PodcastGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,                       // Uuid.random().toString(); Room's built-in kotlin.uuid.Uuid converter only arrives in 3.1.0-alpha01
    val name: String,                       // trimmed, NFC, ≤ 40 chars, as displayed
    val nameKey: String,                    // name.lowercase(Locale.ROOT) of NFC form; uniqueness and import matching
    val sortOrder: Int,                     // dense 0..n-1, rewritten on drag-reorder (n is small)
    val colorArgb: Int? = null,             // from curated palette; null = derive from member covers / theme
    val iconKey: String? = null,            // e.g. "memory", "newspaper", "auto_stories": stable string, NEVER R.drawable ids
    val kind: GroupKind = GroupKind.MANUAL, // MANUAL | SMART (reserved)
    val ruleJson: String? = null,           // smart-group rule AST (versioned), null for MANUAL
    // Group feed presentation (cheap, low-churn; kept on the row)
    val feedOrder: FeedOrder = FeedOrder.NEWEST_FIRST,
    val playOrder: FeedOrder = FeedOrder.NEWEST_FIRST,   // "Play group" order: news newest-first, fiction oldest-first
    val filterFlags: Int = 0,               // bitmask UNPLAYED=1, DOWNLOADED=2, IN_PROGRESS=4
    val mediaFilter: MediaFilter = MediaFilter.ALL,      // ALL | AUDIO | VIDEO
    val hideOlderThanDays: Int? = null,
    val showAsTab: Boolean = true,
    val lastViewedAt: Long? = null,         // "N new since last visit" badge
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "podcast_group_member",
    primaryKeys = ["groupId", "podcastId"],
    withoutRowId = true,                    // Room 3 feature; PK-ordered storage, no extra rowid b-tree
    foreignKeys = [
        ForeignKey(PodcastGroupEntity::class, ["id"], ["groupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PodcastEntity::class, ["id"], ["podcastId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("podcastId", "groupId")],           // reverse lookup: "groups of podcast", Ungrouped NOT EXISTS
)
data class PodcastGroupMemberEntity(
    val groupId: Long,
    val podcastId: Long,
    val sortOrder: Int = 0,                 // order inside the group's podcast grid (optional manual order)
    val addedAt: Long,
    val source: MemberSource = MemberSource.MANUAL,       // MANUAL | RULE (smart groups, later)
)

@Entity(
    tableName = "podcast_group_settings",
    foreignKeys = [ForeignKey(PodcastGroupEntity::class, ["id"], ["groupId"], onDelete = ForeignKey.CASCADE)],
)
data class PodcastGroupSettingsEntity(       // null = inherit
    @PrimaryKey val groupId: Long,
    val playbackSpeed: Float? = null,
    val skipSilence: Boolean? = null,
    val autoDownload: Boolean? = null,
    val autoDownloadKeepLatest: Int? = null,
    val autoDownloadNetwork: NetworkPolicy? = null,      // UNMETERED | ANY
    val includeVideoInAutoDownload: Boolean? = null,
    val notifyNewEpisodes: Boolean? = null,
    val refreshIntervalMinutes: Int? = null,
)
```

Additional column requested on `podcast` **[cross-area]**: `includeInAll: Boolean = true`. This lets a high-volume show (an hourly news bulletin) be visible only in its group and not in the "All" feed. A matching `podcast_settings` table carries per-podcast overrides with the same nullable columns.

The `foreign_keys` pragma must be on. Room 2 enabled it automatically when entities declare FKs; confirm the Room 3 + `BundledSQLiteDriver` behaviour in the spike (**UNVERIFIED**).

```kotlin
@Dao
interface GroupDao {
    @Query("SELECT * FROM podcast_group ORDER BY sortOrder")
    fun observeGroups(): Flow<List<PodcastGroupEntity>>

    @Query("SELECT g.* FROM podcast_group g JOIN podcast_group_member m ON m.groupId = g.id WHERE m.podcastId = :podcastId ORDER BY g.sortOrder")
    fun observeGroupsOf(podcastId: Long): Flow<List<PodcastGroupEntity>>

    @Query("SELECT podcastId FROM podcast_group_member WHERE groupId = :groupId")
    suspend fun memberIds(groupId: Long): List<Long>

    @Upsert suspend fun upsertMembers(rows: List<PodcastGroupMemberEntity>)

    @Query("DELETE FROM podcast_group_member WHERE podcastId = :podcastId AND groupId NOT IN (:keep)")
    suspend fun removeMembershipsExcept(podcastId: Long, keep: List<Long>)

    @Transaction
    suspend fun setGroupsForPodcast(podcastId: Long, groupIds: List<Long>, now: Long) {
        removeMembershipsExcept(podcastId, groupIds.ifEmpty { listOf(-1L) })
        upsertMembers(groupIds.map { PodcastGroupMemberEntity(it, podcastId, addedAt = now) })
    }

    @Query("UPDATE podcast_group SET sortOrder = :order, updatedAt = :now WHERE id = :id")
    suspend fun setOrder(id: Long, order: Int, now: Long)

    @Transaction
    suspend fun reorder(idsInOrder: List<Long>, now: Long) = idsInOrder.forEachIndexed { i, id -> setOrder(id, i, now) }
}
```

Note that `@Upsert` keeps the original `addedAt` only if we read it first. For memberships that does not matter.

**Effective-settings resolution** (one `EffectiveSettingsResolver` in `:core:domain`, used by playback, downloads and notifications):

| Setting | Rule when a podcast is in several groups | Source |
|---|---|---|
| Playback speed, skip silence | episode override → podcast override → **group of the current play context** (when playing a group feed) → global. Outside a group context, group values are ignored | matches `playback.md` §11 |
| Auto-download enabled / keep N / network | podcast explicit → merge across all member groups: `enabled = any`, `keepLatest = max`, `network = most restrictive` → global | matches `downloads.md` §9 |
| New-episode notifications | podcast explicit → `any(group.notify)` → global (default off) | — |
| Refresh interval | podcast explicit → `min` across groups → global | feeds area Q4 |

The settings UI shows the *effective* value and its source: "1.5× (from group 'news')", "Auto-download on (from 'tech')".

**Group lifecycle:**

* **Create:** validate the name (non-empty after trim, ≤ 40 chars, `nameKey` unique), then append with `sortOrder = max + 1`.
* **Rename:** update `name` and `nameKey`; also update the notification channel name.
* **Delete:** one transaction (cascades remove members and settings). Also delete the notification channel, remove `PlaySession` contexts pointing at the group (fall back to no context), and remove any scope-keyed rows if playback keeps its generic table. Offer **Undo** via a snackbar by keeping the deleted rows in memory for 10 s.

### J. Group feed queries, indices and paging

**One builder for every feed source:**

```kotlin
sealed interface FeedSource {
    data object All : FeedSource
    data object Ungrouped : FeedSource
    data class Group(val id: Long) : FeedSource
    data class Podcast(val id: Long) : FeedSource
    // later: data class Smart(val id: Long, val rule: RuleAst) : FeedSource
}
data class FeedFilters(
    val unplayedOnly: Boolean = false, val downloadedOnly: Boolean = false,
    val inProgressOnly: Boolean = false, val media: MediaFilter = MediaFilter.ALL,
    val minSortDate: Long? = null,              // from hideOlderThanDays, computed at query build time
)

object FeedQueryBuilder {
    private const val COLUMNS = """
        e.id, e.podcastId, e.title, e.sortDate, e.durationMs, e.isVideo,
        COALESCE(e.imageUrl, p.artworkUrl) AS artworkUrl, COALESCE(p.customTitle, p.title) AS podcastTitle,
        p.sourceType, s.playedAt, s.startedAt, d.state AS downloadState"""

    fun page(src: FeedSource, f: FeedFilters, order: FeedOrder): RoomRawQuery {
        val where = mutableListOf("p.isSubscribed = 1")
        val args = mutableListOf<Any>()
        when (src) {
            FeedSource.All -> where += "p.includeInAll = 1"
            FeedSource.Ungrouped -> where += "NOT EXISTS (SELECT 1 FROM podcast_group_member m WHERE m.podcastId = e.podcastId)"
            is FeedSource.Group -> { where += "e.podcastId IN (SELECT m.podcastId FROM podcast_group_member m WHERE m.groupId = ?)"; args += src.id }
            is FeedSource.Podcast -> { where += "e.podcastId = ?"; args += src.id }
        }
        f.minSortDate?.let { where += "e.sortDate >= ?"; args += it }
        if (f.unplayedOnly) where += "s.playedAt IS NULL"
        if (f.inProgressOnly) where += "s.startedAt IS NOT NULL AND s.playedAt IS NULL"
        if (f.downloadedOnly) where += "d.state = 'COMPLETED'"
        when (f.media) { MediaFilter.AUDIO -> where += "e.isVideo = 0"; MediaFilter.VIDEO -> where += "e.isVideo = 1"; else -> {} }
        val dir = if (order == FeedOrder.NEWEST_FIRST) "DESC" else "ASC"
        val sql = """SELECT $COLUMNS
            FROM episode e
            JOIN podcast p ON p.id = e.podcastId
            LEFT JOIN episode_play_state s ON s.episodeId = e.id
            LEFT JOIN download d ON d.episodeId = e.id
            WHERE ${where.joinToString(" AND ")}
            ORDER BY e.sortDate $dir, e.id $dir"""
        return RoomRawQuery(sql) { st -> args.forEachIndexed { i, a -> st.bindAny(i + 1, a) } }
    }
}

@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)   // room3-paging
interface FeedDao {
    @RawQuery(observedEntities = [EpisodeEntity::class, PodcastEntity::class, EpisodePlayStateEntity::class,
                                  DownloadEntity::class, PodcastGroupMemberEntity::class])
    fun feed(query: RoomRawQuery): PagingSource<Int, EpisodeRow>   // UNVERIFIED in Room 3: fallback = generated @Query per (source, order)
}

// ViewModel
val episodes: Flow<PagingData<EpisodeRow>> = groupPrefs(groupId)
    .map { g -> FeedQueryBuilder.page(FeedSource.Group(groupId), g.toFilters(clock), g.feedOrder) }
    .distinctUntilChanged()
    .flatMapLatest { q -> Pager(PagingConfig(pageSize = 40, prefetchDistance = 40, initialLoadSize = 80,
                                             enablePlaceholders = true, maxSize = 400)) { feedDao.feed(q) }.flow }
    .cachedIn(viewModelScope)
```

How the builder is used and tested:

* Only enumerated fragments are concatenated, and **values are always bound**. One builder therefore serves group feeds, the virtual feeds, the podcast page, the Android Auto browse tree (with `LIMIT 100`) and, later, smart groups.
* Because `@RawQuery` loses compile-time SQL checks, a JVM test enumerates **every** combination of source × filter × order against an in-memory Room DB (bundled driver) and runs `EXPLAIN QUERY PLAN` assertions.
* In Compose, use `LazyPagingItems` with `key = itemKey { it.id }` and `contentType = itemContentType { if (it.isVideo) 1 else 0 }`.

**Invalidation hygiene [cross-area].**

* Room 3's `LimitOffsetPagingSource` registers `invalidationTracker.createFlow(*tables)`. On *any* write to an observed table it invalidates, and the new source re-runs `SELECT COUNT(*) FROM ( <query> )` plus `SELECT * FROM ( <query> ) LIMIT n OFFSET m` (verified in `room3-paging` source).
* Invalidation is per table, not per row. So:
  * **Split `EpisodePlayState`.** `episode_play_state(episodeId PK, startedAt, playedAt, playCount, lastPlayedAt)` changes rarely: on start, finish, or mark played. `episode_position(episodeId PK, positionMs, durationMs, updatedAt)` is written every 10 s and is **not** joined by list queries.
  * The list shows progress bars from a tiny separate Flow: `SELECT episodeId, positionMs, durationMs FROM episode_position WHERE positionMs > 0` (in-progress rows, typically < 100) collected into a `Map<Long, Progress>`. The currently playing item uses live player state.
  * **Split download progress.** `download.state` (QUEUED, RUNNING, COMPLETED, FAILED…) changes rarely. `downloadedBytes` should live in `download_progress`, or only in `DownloadProgressBus` (`downloads.md` already throttles the bus to 250 ms and persists bytes every 2 s or more). The list renders bytes from the in-memory bus.
  * The membership table changes only on user edits, and episodes change on refresh. Both are fine.

**Indices** (with ~300 podcasts, ~50k episodes, ~20 groups):

| Table | Index | Serves |
|---|---|---|
| `episode` | `(podcastId, sortDate)` (feeds area proposes `(podcastId, sortDate DESC)`; either works) | Group, podcast and Ungrouped feeds: per-podcast range scans merged by a temp B-tree sort of the group's rows (k log k, k = episodes in the group, typically ≤ 10k) |
| `episode` | `(sortDate)` | "All" feed: an ordered index scan with no sort. `ORDER BY sortDate DESC, id DESC` can be satisfied by scanning backwards, because every SQLite index entry ends with the rowid (`id` is the INTEGER PRIMARY KEY). Verify there is no "USE TEMP B-TREE FOR ORDER BY" in `EXPLAIN QUERY PLAN` |
| `podcast_group_member` | PK `(groupId, podcastId)` WITHOUT ROWID | `IN (SELECT podcastId … WHERE groupId = ?)` |
| `podcast_group_member` | `(podcastId, groupId)` | `NOT EXISTS` for Ungrouped; "groups of podcast" chips |
| `episode_play_state` | PK `episodeId` (+ `(playedAt)` for History) | LEFT JOIN, filters |
| `download` | unique `episodeId` (downloads area) | LEFT JOIN, filter |
| `episode` | `(firstSeenAt)` *optional* | "new since last visit" counts |

* Run `PRAGMA optimize=0x10002` when the connection opens and `PRAGMA optimize` daily, as SQLite recommends for long-lived connections. Also run it after migrations that create indices, so the planner has statistics for choosing between "scan the `sortDate` index" and "IN-list + sort".
* **Performance budget** (to verify with a seeded DB on a low-end device): group feed initial load (count + 80 rows) under 60 ms, All feed under 100 ms, page loads under 20 ms. If that fails, switch only the "All" feed to a keyset `PagingSource`.

**Counts for the groups list** (unplayed badge, new since last visit):

```sql
SELECT m.groupId AS groupId,
       SUM(CASE WHEN s.playedAt IS NULL THEN 1 ELSE 0 END)            AS unplayed,
       SUM(CASE WHEN e.firstSeenAt > g.lastViewedAt THEN 1 ELSE 0 END) AS newSinceVisit
FROM podcast_group_member m
JOIN podcast_group g ON g.id = m.groupId
JOIN episode e ON e.podcastId = m.podcastId
LEFT JOIN episode_play_state s ON s.episodeId = e.id
WHERE e.sortDate >= :since            -- e.g. last 30 days, or the group's hideOlderThan; avoids counting 2009 back-catalogue
GROUP BY m.groupId
```

Expose this as a `Flow<List<GroupCounts>>`. If benchmarks show it is too slow, denormalise `podcast.unplayedCount`, maintained in the same transactions that change played state and ingest episodes **[cross-area]**.

**Mosaic covers:** `SELECT p.artworkUrl FROM podcast_group_member m JOIN podcast p ON p.id = m.podcastId WHERE m.groupId = ? ORDER BY p.latestEpisodeAt DESC LIMIT 4`. In Compose, render four `AsyncImage`s in a 2×2 `Box`. For Android Auto or the media session, a group needs a single bitmap URI: render the mosaic to `files/artwork/group-<uuid>.webp` and serve it through playback's artwork provider.

### K. Playing a group feed as a queue

This plugs into playback's `PlaySession(contextType = GROUP, contextId, contextOrder, contextAnchorEpisodeId)`.

* **Start item:** the first unplayed item in `group.playOrder`. For OLDEST_FIRST, bound the start by `hideOlderThanDays`, or failing that by the podcast's `subscribedAt`. Otherwise "Play fiction" would start at a 2011 episode.
* **Context tail** (the playback projector asks for the next K ≈ 20 after the anchor):

```sql
SELECT e.id FROM episode e
JOIN podcast p ON p.id = e.podcastId
LEFT JOIN episode_play_state s ON s.episodeId = e.id
WHERE p.isSubscribed = 1
  AND e.podcastId IN (SELECT podcastId FROM podcast_group_member WHERE groupId = :groupId)
  AND s.playedAt IS NULL
  AND e.id NOT IN (SELECT episodeId FROM queue_entry)
  AND e.sortDate >= :minSortDate
  AND (e.sortDate, e.id) < (:anchorSortDate, :anchorId)      -- NEWEST_FIRST; use '>' and ASC for OLDEST_FIRST
ORDER BY e.sortDate DESC, e.id DESC
LIMIT :k
```

  Row values need SQLite ≥ 3.15. Both the bundled SQLite and the framework's 3.18 on API 26 qualify.
* The group's view filters (downloaded-only, media) also apply to the queue context when set. "Play downloaded in Tech" is then a filter plus Play.
* YouTube items flow through the same query. Stream resolution, and skipping unplayable items, is playback and YouTube business.

### L. YouTube channels inside groups

* The schema is agnostic: a YouTube channel is a `podcast` row with `sourceType = YOUTUBE`, so membership, group feeds, counts and settings work unchanged.
* **Group feed UI:**
  * Video items show 16:9 thumbnails with a duration badge; audio items show square covers. Use two `contentType`s for `LazyColumn` recycling.
  * A per-group `mediaFilter` chip (All / Audio / Video).
  * The mosaic uses the channel avatar. YouTube avatars are circular-cropped in YouTube's UI but square in the feed, so crop to a rounded square for consistency.
* **Auto-download at group level** inherits `downloads.md`'s YouTube special-casing: smaller `keepLatest`, no Shorts, and a resolution cap per refresh. If "include video" is off at group level, YouTube channels in that group never auto-download.
* **Import:** NewPipe and Takeout imports default to a suggested group "YouTube" (editable).

### M. Group UI essentials

* **Main navigation.** A "Feeds" destination with a `PrimaryScrollableTabRow` + `HorizontalPager`: **All | tech | news | fiction | … | Ungrouped**.
  * Groups appear only if `showAsTab = true`. "Ungrouped" is shown only if it is non-empty and not hidden.
  * Each page has its own `Pager`. Use `beyondViewportPageCount = 0`, so only the visible group queries.
  * On wide screens (Nav3 `ListDetailSceneStrategy`, see `stack.md`), show the groups list on the left and the group feed on the right.
* **Groups screen.**
  * Cards with the mosaic or icon + colour, name, unplayed and new counts.
  * A FAB "New group".
  * Drag to reorder. Compose has no stable reorderable `LazyColumn`; `sh.calvin.reorderable:reorderable` 3.1.0 (Apache-2.0) is the usual choice, or a hand-rolled solution with `Modifier.draggable`.
* **Assigning podcasts:**
  1. From the podcast screen: "Groups" chips, opening a multi-select bottom sheet with "+ New group".
  2. From group edit: a checkbox grid of all covers with search.
  3. From the library grid: multi-select, then "Add to group…".
  4. During import (§C).
* **Group overflow menu:** Edit (name, colour, icon), Group settings (the effective-settings UI), Mark all as played (with "older than…" options), Download all unplayed (with a confirmation showing the count and size estimate), Refresh this group (`refreshNow(Group(id))` in `feeds.md`), Share as OPML, Import OPML into this group, Delete.
* **Notifications.**
  * One channel per group with notifications enabled: ID `new_episodes_<groupUuid>`, inside a `NotificationChannelGroup` "New episodes", plus a default channel for ungrouped podcasts.
  * Users can tune sound and importance per group in system settings. Rename means calling `createNotificationChannel` with the same ID, which updates the name.
  * The AOSP per-app limit is 5,000 channels (`PreferencesHelper.NOTIFICATION_CHANNEL_COUNT_LIMIT`), which is irrelevant at our scale.
  * A podcast in several notifying groups posts once, in the first such group by `sortOrder`.
  * Requires `POST_NOTIFICATIONS` on API 33+. Ask when the user first enables a group notification, not at startup.

### N. Smart groups (future, schema reserved now)

* **Rule AST** (JSON, versioned):

```json
{ "v": 1, "match": "ALL",
  "podcast": [ {"f": "category", "op": "in", "v": ["Technology"]}, {"f": "source", "op": "eq", "v": "YOUTUBE"},
               {"f": "group", "op": "in", "v": ["<uuid-of-news>"]} ],
  "episode": [ {"f": "durationMin", "op": "lt", "v": 20}, {"f": "ageDays", "op": "le", "v": 7},
               {"f": "played", "op": "eq", "v": false} ] }
```

* **Podcast-level rules** are materialised into `podcast_group_member` with `source = RULE`, recomputed after a subscription change, feed refresh (category changes) or rule edit. This means counts, notifications and settings resolution work unchanged.
* Manual additions (`source = MANUAL`) coexist with rule members. A `podcast_group_exclusion(groupId, podcastId)` table holds "never include".
* **Episode-level rules** compile to extra `WHERE` fragments in `FeedQueryBuilder` (whitelisted fields, bound values), which is why `@RawQuery` is the right foundation.
* **Onboarding nicety (optional, v1-compatible):** "Suggest groups from your podcasts' categories". It uses parsed `itunes:category` to propose manual groups such as "Technology (12)" and "News (8)". `feeds.md` notes that the iTunes genres map naturally onto group names.

### O. Module placement and tests

* **`:feeds` (pure JVM):** `OpmlReader` (cascade + salvage), `OpmlWriter`, `ImportSourceSniffer`, `NewPipeSubscriptionsParser`, `TakeoutCsvParser`, `YouTubeUrlClassifier` (or delegate to `:youtube:api`), and `BackupFormat` (serializable DTOs + JSONL codec).
* **`:core:database`:** the group, membership, settings and import entities and DAOs, plus `FeedQueryBuilder` (it needs `RoomRawQuery`).
* **`:core:data`:** `GroupRepository`, `ImportRepository`, `BackupRepository` (ZIP over `ContentResolver`), `EffectiveSettingsResolver` (or in `:core:domain`), and the workers (`ImportFetchWorker`, `AutoSnapshotWorker`, `ScheduledBackupWorker`, `RestoreWorker`).
* **Features:**
  * `:feature:groups`: list, edit, group feed, assignment sheet.
  * A new **`:feature:importexport`**: preview, progress/report, export options, backup/restore, scheduled backup settings. `stack.md` currently spreads these across `:feature:settings` and `:feature:add`; a dedicated module keeps them together.
  * `ExternalImportActivity` lives in `:app` or `:feature:importexport`.

**Tests:**

* **Golden OPML fixtures:**
  * AntennaPod (flat, `type="atom"`);
  * Pocket Casts (v1.0, `feeds` wrapper);
  * Overcast basic and extended (playlists + episodes + `subscribed="0"`);
  * gPodder (sections, `text` = description);
  * FreshRSS-style nested folders;
  * Google-broken (missing `/>`, raw `&`);
  * our hybrid and flat outputs.
* **Encodings:** UTF-8 BOM, UTF-16LE with BOM, ISO-8859-1 declared, leading newline, Windows-1252 smart quotes declared as UTF-8 (expect a replacement char, not a crash).
* **Hostile input:** a DOCTYPE with entity expansion, 10k nesting depth, 10 MB attribute, 100k outlines, a ZIP bomb, zip-slip names.
* **Round-trip property test:** random groups and memberships (including names with `, / %` and emoji), then export, then import into an empty DB, then assert identical memberships and group order.
* **Room tests** (JVM, bundled driver): every `FeedQueryBuilder` combination; `EXPLAIN QUERY PLAN` assertions; seeded 50k-episode benchmark (androidx.benchmark) for the budgets in §J.
* **Backup tests:** write, read and merge semantics; stub-then-refresh matching via the feeds ingestion diff; the size-guard degradation; `bmgr` local-transport instrumentation test.

---

## Verified versions & facts

All checked **2026-10-04**.

| Fact | Source |
|---|---|
| OPML 2.0 rules: `text` required ("A missing text attribute… is an error"); rss nodes require `type`, `text`, `xmlUrl`; `title` "should not be omitted"; nested lists allowed but "some processors may not understand and preserve the structure"; `category` = comma-separated slash-delimited strings, a tag has no slashes; type case-insensitive; `isComment`; `include`/`link` with `url`; namespaced extensions; ignore unknown attributes; RFC 822 dates (2- or 4-digit year); `text/x-opml` autodiscovery; "Version 2.0 is the last version of OPML" | http://opml.org/spec2.opml |
| AntennaPod export: flat, `text`+`title`, `type` from `Feed.getType()` (`"rss"`/`"atom"`), `xmlUrl`, `htmlUrl`, OPML 2.0, `dateCreated` "dd MMM yy HH:mm:ss Z", file `antennapod-feeds-%s.opml`, MIME `text/x-opml`. Import visits all outlines, needs `xmlUrl`, ignores folders, prefers `title`, BOM-based charset, picker `GetContent("*/*")`, multi-choice preview list. VIEW+SEND filter for `text/xml`, `text/x-opml`, `application/xml` with schemes file/content/http/https. Key/value `OpmlBackupAgent` (OPML only). `DatabaseExporter` copies the raw DB and refuses newer versions. `AutomaticDatabaseExportWorker` runs every 3 days and keeps 5. Tags are a `Set<String>` with virtual `#root`/`#untagged` | https://github.com/AntennaPod/AntennaPod (develop, commit 9c7ffa1): `storage/importexport/…/OpmlWriter.java`, `OpmlReader.java`, `OpmlBackupAgent.java`, `DatabaseExporter.java`, `AutomaticDatabaseExportWorker.java`; `app/src/main/AndroidManifest.xml`; `app/…/OpmlImportActivity.java`; `model/…/FeedPreferences.java` |
| AntennaPod 3.1: "Always add feeds from opml, even if download fails" | https://forum.antennapod.org/t/import-issues-with-opml/2676 |
| Google Podcasts exports were malformed ("Unterminated entity ref"; "Google is known for exporting broken opml files"), Aug 2024 | https://forum.antennapod.org/t/cant-import-google-podcasts-opml-file/5363 |
| Pocket Casts export: OPML 1.0, `<outline text="feeds">` wrapper, `type`/`text`/`xmlUrl` only, URLs from server, file `podcasts_opml.xml`, shared as `text/xml`. Import: line-based `xmlUrl=` scan with entity unescape and `Set` dedupe; server-side import in chunks of 100 with polling; WorkManager + `FOREGROUND_SERVICE_TYPE_DATA_SYNC`; import from URL. Manifest comment about octet-stream catching `install (1).apk`. Podcasts carry a single `folder_uuid` | https://github.com/Automattic/pocket-casts-android: `modules/services/views/…/OpmlExporter.kt`, `modules/services/repositories/…/opml/OpmlUrlReader.kt`, `OpmlImportTask.kt`, `app/src/main/AndroidManifest.xml`, `modules/services/model/…/entity/Podcast.kt` |
| Pocket Casts help: Podcast Addict export path (Settings → Backup/Restore → Subscriptions only – Backup); iTunes "Export Playlist → OPML"; Apple Podcasts shortcut | https://support.pocketcasts.com/article/opml-import/ |
| gPodder: exports OPML 2.0 with section folders (`title`=`text`=section), feed `text` = description, `type="rss"`; default sections from content type (Audio/Video/Other, localised); import accepts `rss`/`link` types and a `url` fallback | https://github.com/gpodder/gpodder `src/gpodder/opml.py`, `src/gpodder/model.py` |
| Overcast extended export structure (`playlists` with `podcast-playlist`; `feeds` with `rss` + nested `podcast-episode` children; `subscribed`, `played`, `progress`, `enclosureUrl`…); endpoint `/account/export_opml/extended` | https://github.com/hbmartin/overcast-to-sqlite (`overcast_to_sqlite/overcast.py`, `tests/test_opml.py`) |
| Overcast basic export sample (`feeds` wrapper, `overcastId`, `overcastAddedDate`): **partially verified** (search snippet; page returned 403) | https://metacast.app/blog/podcasting/opml-import-export |
| Apple Podcasts (macOS Catalina+) lacks OPML import/export; subscriptions in `PodcastsDB.plist` (2019 article; current iOS 26 status UNVERIFIED) | https://kaspars.net/blog/apple-podcasts-subscriptions |
| FreshRSS: innermost folder wins; `category` attribute used only when no parent folder, with multiple values joined by `", "` | https://github.com/FreshRSS/FreshRSS/blob/edge/app/Services/ImportService.php |
| NewPipe subscription JSON: `subscriptions[{service_id,url,name}]` + `app_version`, `app_version_int`; import worker uses a `dataSync` FGS | https://github.com/TeamNewPipe/NewPipe `…/local/subscription/workers/SubscriptionData.kt`, `ImportExportJsonHelper.kt`, `SubscriptionImportWorker.kt` |
| Takeout YouTube CSV: `Channel Id,Channel Url,Channel Title`; header localised, order fixed; ZIP contains the CSV | https://github.com/TeamNewPipe/NewPipeExtractor `…/youtube/extractors/YoutubeSubscriptionExtractor.java` |
| YouTube channel feed `feeds/videos.xml?channel_id=UC…` returns 200 `text/xml`; the `UULF…` playlist feed works and has `<title>Videos</title>` with the channel in `author/name`; the `UUMF` variant returned 404 | Fetched live: https://www.youtube.com/feeds/videos.xml?channel_id=UCLA_DiR1FfKNvjuUpBHmylQ ; sibling-captured `pl_UULF.xml`, `pl_UUMF.xml` |
| Android MIME tables contain no `.opml` mapping | https://android.googlesource.com/platform/external/mime-support/+/refs/heads/main/mime.types ; https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/mime/java-res/android.mime.types |
| `FileUtils.splitFileName`: extension kept if the MIME type matches or the requested MIME type has no extension; otherwise the MIME type's extension is appended | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/FileUtils.java |
| `ContentResolver.openOutputStream(uri)` = mode "w", which "may or may not truncate"; use `"wt"` | https://developer.android.com/reference/android/content/ContentResolver |
| Intent URI grants "remain in effect while the stack of the receiving Activity is active" | https://developer.android.com/reference/androidx/core/content/FileProvider |
| `<data>`: a mimeType without a scheme implies `content:` and `file:`; path attributes need scheme and host; `pathSuffix`/`pathAdvancedPattern` from API 31; MIME, scheme and host matching is case-sensitive | https://developer.android.com/guide/topics/manifest/data-element |
| Android 16 Safer Intents: opt-in via `android:intentMatchingFlags="enforceIntentFilter"`; explicit intents must match filters; action-less intents match nothing; plan to make it the default | https://developer.android.com/about/versions/16/behavior-changes-16 |
| Auto Backup: 25 MB/app; over quota means `onQuotaExceeded()` and no cloud backup; 24 h / idle / Wi-Fi; app is shut down during backup; restore at install; default includes DB, files, external files; `<include>` disables the defaults; Android 12+ `dataExtractionRules` with `cloud-backup`/`device-transfer`; `cross-platform-transfer` from Android 16 QPR2; a `BackupAgent` runs in restricted mode with the base `Application` | https://developer.android.com/identity/data/autobackup |
| `bmgr` local-transport and D2D test-mode commands | https://developer.android.com/identity/data/testingbackup |
| Preferences DataStore file = `filesDir/datastore/<name>.preferences_pb` | https://github.com/androidx/androidx/blob/androidx-main/datastore/datastore/src/androidMain/kotlin/androidx/datastore/DataStoreFile.android.kt |
| KXmlParser: `setInput(InputStream, null)` detects BOMs and the XML declaration encoding, defaulting to UTF-8; `relaxed` feature; entity declarations parsed only with docdecl processing | https://android.googlesource.com/platform/libcore/+/refs/heads/main/xml/src/main/java/com/android/org/kxml2/io/KXmlParser.java |
| KXmlSerializer (Android) throws `IllegalArgumentException("Illegal character (U+…)")` for chars invalid in XML 1.0 | https://android.googlesource.com/platform/libcore/+/refs/heads/main/xml/src/main/java/com/android/org/kxml2/io/KXmlSerializer.java |
| Room 3: **3.0.3** stable (2026-09-09), 3.1.0-alpha01 (2026-09-09). `@TypeConverter` renamed to `@ColumnTypeConverter`; `withoutRowId`; custom DAO return types (`PagingSourceDaoReturnTypeConverter` in `room3-paging`); Flow-based `InvalidationTracker`; built-in `kotlin.uuid.Uuid` converter **only from 3.1.0-alpha01**. `@Entity(withoutRowId)`, `Index(orders, unique)`, `ForeignKey(onDelete = CASCADE)`, `@RawQuery(observedEntities)`, `RoomRawQuery(sql, onBindStatement)` | https://developer.android.com/jetpack/androidx/releases/room3 ; androidx source `room3/room3-common/src/commonMain/kotlin/androidx/room3/{Entity,Index,ForeignKey,RawQuery}.kt` ; https://dl.google.com/android/maven2/androidx/room3/room3-runtime/maven-metadata.xml |
| Room 3 paging: `SELECT COUNT(*) FROM ( … )` on initial load; `SELECT * FROM ( … ) LIMIT n OFFSET m`; invalidates on any observed-table change | androidx source `room3/room3-paging/src/commonMain/kotlin/androidx/room3/paging/LimitOffsetPagingSource.kt`, `util/RoomPagingUtil.kt` |
| Paging **3.5.1** (maven updated 2026-08-12) | https://dl.google.com/android/maven2/androidx/paging/paging-runtime/maven-metadata.xml |
| sqlite-bundled **2.7.1** (2.8.0-alpha01 exists) | https://dl.google.com/android/maven2/androidx/sqlite/sqlite-bundled/maven-metadata.xml |
| WorkManager **2.12.0** (2026-09-23) | https://dl.google.com/android/maven2/androidx/work/work-runtime/maven-metadata.xml |
| Activity **1.13.0** (2026-03-11; 1.14.0-alpha03 exists). `CreateDocument` no-arg constructor deprecated in 1.5.0 in favour of the MIME-type constructor | https://developer.android.com/jetpack/androidx/releases/activity |
| kotlinx.serialization-json **1.11.0** stable (1.12.0-RC exists); `encodeToStream`, `decodeFromStream` and `decodeToSequence` are `@ExperimentalSerializationApi` | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml ; https://github.com/Kotlin/kotlinx.serialization/blob/master/formats/json/jvmMain/src/kotlinx/serialization/json/JvmStreams.kt |
| `sh.calvin.reorderable:reorderable` **3.1.0** (2026-04-20), Apache-2.0 | https://repo1.maven.org/maven2/sh/calvin/reorderable/reorderable/maven-metadata.xml ; https://github.com/Calvin-LL/Reorderable/blob/main/LICENSE |
| Notification channels: AOSP per-app limit 5,000 channels and 6,000 channel groups (implementation detail, not API) | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/notification/PreferencesHelper.java |
| SQLite: `PRAGMA optimize=0x10002` on open plus periodic `PRAGMA optimize` for long-lived connections; row values since 3.15.0; `VACUUM INTO` (3.27.0) for live backup copies | https://www.sqlite.org/pragma.html#pragma_optimize ; https://www.sqlite.org/rowvalue.html ; https://www.sqlite.org/lang_vacuum.html ; https://www.sqlite.org/releaselog/3_27_0.html |
| `dataSync` FGS type covers "Import or export operations" and "Backup-and-restore operations"; cannot start from `BOOT_COMPLETED` on Android 15+ | https://developer.android.com/develop/background-work/services/fgs/service-types |

---

## Pitfalls & edge cases

1. **The 25 MB Auto Backup cliff.** The default rules include `getExternalFilesDir()`, where downloads live. One downloaded episode exceeds the quota and **nothing** is backed up, silently. Always use explicit `<include>` rules.
2. **Restored DataStore lies about the device.** Persisted SAF tree URIs, storage volume UUIDs and "permission already asked" flags don't transfer. Keep them in the non-backed-up `device_settings` file.
3. **Detecting a fresh install.** Do not use a DataStore flag (it is restored too). Use Room's `onCreate` callback in this process.
4. **The URI grant dies with the activity stack.** Parsing later in a worker gives `SecurityException`. Copy on receipt.
5. **`"w"` may not truncate.** Overwriting an existing, longer document leaves trailing garbage, which makes invalid XML or ZIP. Always use `"wt"`.
6. **`.opml.xml` double extension.** Using `text/xml` or `application/xml` with `CreateDocument` on file-system providers appends `.xml`. Use `text/x-opml`.
7. **MIME type is unreliable on receipt.** `.opml` arrives as `application/octet-stream`, `text/xml`, `text/plain` or `text/x-opml` depending on the provider. Sniff the bytes and never trust the extension or MIME type.
8. **`pathSuffix`/`pathPattern` traps.**
   * Path attributes are ignored without scheme and host.
   * `pathSuffix` doesn't exist below API 31. A filter relying on it would match every content file there, hence the API-gated alias.
   * Many providers' URIs (Downloads `msf:123`, Gmail attachments) contain no file name at all.
9. **Wrapper folders become junk groups.** Pocket Casts and Overcast wrap everything in `<outline text="feeds">`. Without wrapper detection every Pocket Casts import creates a "feeds" group.
10. **Overcast extended exports contain episodes and playlists.** Naive "outline without `xmlUrl` is a folder" logic turns `podcast-playlist` outlines into groups, and treats feeds containing episode outlines as folders.
11. **gPodder's `text` is the description,** so prefer `title` for display. Localised "Audio"/"Video" sections become groups, so pre-label them in the preview.
12. **`type="atom"`** from AntennaPod, and missing `type` in general: accept them. **Case variants** (`xmlurl`, `XMLURL`): match attributes case-insensitively.
13. **Broken XML in the wild** (unescaped `&`, missing `/>`, `&nbsp;`): fall back to relaxed parsing, then to salvage, and tell the user that folders may be lost.
14. **Encoding:** a `Reader` with an assumed UTF-8 ignores `encoding="ISO-8859-1"`. Pass an `InputStream` with a null charset. Strip leading whitespace before `<?xml`.
15. **XXE and billion laughs:** keep docdecl processing off and reject `<!ENTITY` in the prolog. Never fetch `type="include"` URLs automatically. Also be aware of the Android 17 local-network permission (`feeds.md`) if a URL points at a LAN host.
16. **Invalid XML characters on export** crash Android's `XmlSerializer`. Sanitise every string, in both the hand-written writer and any `XmlSerializer` use.
17. **Group names with `,` or `/`** break the `category` attribute. Percent-encode them within `category`. Also NFC-normalise, so "Café" typed two ways does not create two groups.
18. **Multi-membership duplicates in exports:** never write a feed twice (hybrid). On import, merge same-URL entries and union their groups.
19. **Already-subscribed feeds in an import** must still get their group memberships, otherwise "import my categorised OPML into my existing library" silently does nothing.
20. **Redirect and alias merges after fetch.** `http://` vs `https://`, Feedburner moves, `podcast:guid` matches: merge into the existing podcast and move memberships. Do not leave two rows.
21. **Back-catalogue storms on import or restore:** suppress new-episode notifications and auto-download for `initialFetch`, and respect "don't backfill" from `downloads.md`. Otherwise a 300-feed import posts 300 notifications and queues gigabytes of downloads.
22. **Unplayed counts explode after an import.** Bound counts and "Play group" by a time window (`sortDate >= since`). Offer "mark older than … as played".
23. **Paging invalidation storms:** any table joined by the feed query that is written frequently (positions, download bytes) causes a full re-query and `COUNT(*)` on every write. The table split is mandatory.
24. **`OFFSET` cost at depth:** scrolling to item 20,000 of "All" costs an index walk of 20k entries per page. That is acceptable, but placeholders plus a fast-scroll thumb may trigger large jumps. Consider capping the "All" feed by `hideOlderThanDays` by default.
25. **Deterministic order:** always add `e.id` as a tiebreaker. Episodes with equal `sortDate` (batch-published, or undated ones clamped by `feeds.md`) otherwise reorder between pages and appear twice or not at all.
26. **Bound parameters vs SQL fragments in `FeedQueryBuilder`:** only enumerated fragments may be concatenated. Values (group IDs, dates, and later smart-rule values) are always bound. The smart-group compiler must whitelist fields.
27. **Room 3 has no `kotlin.uuid.Uuid` converter in 3.0.x.** Store UUIDs as `String`, or add a `@ColumnTypeConverter`.
28. **Never persist resource IDs** (`R.drawable.*`, `R.color.*`) for group icons or colours. They change between builds and break backups. Use string keys and ARGB ints.
29. **Notification channels are sticky.** User-modified channel settings survive app updates. Deleting and recreating a channel with the same ID restores the old settings. Use group-UUID-based IDs, which are never reused.
30. **Backup restore security:**
    * Treat the archive as untrusted: whitelisted entry names, size caps, SHA-256 check.
    * JSON with `ignoreUnknownKeys`.
    * Never deserialise into polymorphic classes chosen by the file.
    * Never use entry names as paths (zip-slip).
31. **Secrets in exports and backups.** Tokenised private feed URLs (Patreon, Supercast and the like) are secrets *and* the only way to resubscribe. Warn on export and share. Default to excluding Basic-auth credentials. Keystore-encrypted credentials cannot be decrypted on a new device anyway, so restored podcasts are flagged `needsCredentials`.
32. **SAF folder retention:** providers may rename `x.zip` to `x (1).zip`. Retention regexes then miss files forever, as in AntennaPod's `AutomaticDatabaseExportWorker`. Include time in names and match leniently.
33. **The scheduled backup grant can disappear** (folder deleted, SD card removed, app data cleared). Detect it with `DocumentFile.canWrite()` and notify, rather than failing silently.
34. **`identityKey` drift between app versions** breaks the matching of backup stubs. Version it (`kv`) and keep old-key computation code for migration.
35. **Huge OPMLs in the preview:** 10k rows with 10k `AsyncImage`s would hammer the network. The preview uses monograms only, and covers come after subscription.
36. **Import of non-podcast feeds** (feed-reader OPMLs, Podcast Addict's "RSS news"): detect `NO_MEDIA` after the first fetch and offer bulk removal. Do not hide them silently.
37. **`isComment="true"`** subtrees are pre-unchecked, not dropped.
38. **YouTube `UULF` feed title is "Videos".** Take the display title from `author/name` or the channel lookup, otherwise every imported channel is called "Videos".
39. **Empty groups in exports** are empty outlines with only `text`. Other apps ignore them, which is fine, but our importer must create them as groups rather than drop them.

---

## Open questions for the product owner

1. **Group semantics.** Confirm "tags" (a podcast can be in several groups). Should any group be able to hide its podcasts from the **All** feed (e.g. "kids", or an hourly news bulletin), or only individual podcasts via a per-podcast switch?
2. **Navigation.** Should groups appear as **tabs** on a "Feeds" screen (All | tech | news | … | Ungrouped), as a **Groups list** that opens each feed, or both (proposal: tabs, with a manage-groups screen)? Should "Ungrouped" be visible by default?
3. **Default play and sort order per group.** Newest-first everywhere, or let the user choose per group (proposal: per-group setting, default newest-first; suggest oldest-first when a group is named like "fiction" or "audiobooks")?
4. **Imported back-catalogue.** After importing or restoring, should existing episodes count as *unplayed* (truthful, but noisy) or *played* except the latest N per podcast ("start fresh")? Should the user be asked in the import preview?
5. **Export defaults.**
   * Grouped (folders) or flat by default?
   * Include YouTube channels by default (other apps may not play them)?
   * Include feed passwords ever (ties to feeds Q10)?
6. **Cloud backup.**
   * Is Android Auto Backup (Google Drive, end-to-end encrypted only when the user has a screen lock) acceptable for data that can include private feed URLs?
   * Should backups be skipped on devices without encryption (`disableIfNoEncryptionCapabilities="true"`)?
7. **Scheduled backups to a folder.** v1 or later? Default frequency and retention (proposal: weekly, keep 5, off by default)?
8. **Restore modes.** Expose the destructive "Replace" restore in the UI, or only "Merge" (plus automatic Replace for the first-launch Auto Backup restore)?
9. **Listening history import from other apps.** Overcast's extended OPML carries played state and progress per episode. Worth supporting in v1 (moderate effort: match by enclosure URL), or later?
10. **YouTube subscription import** (NewPipe JSON, Google Takeout CSV/ZIP). v1? It greatly eases onboarding for requirement 3.
11. **Smart (rule-based) groups.** Confirm they are out of scope for v1 (the schema is reserved). Is "Suggest groups from podcast categories" wanted at onboarding?
12. **Group limits and naming.** Is a cap on the number of groups needed (proposal: none, UI tested to 50)? Is 40 characters enough? Are emoji allowed (proposal: yes)?
13. **New-episode notifications per group.** Default off for all groups, or on for newly created groups? One system notification channel per group (lets users pick sounds per group) or a single channel?
14. **"Download all unplayed in group".** Should it exist (it can mean gigabytes), and with what confirmation threshold?
