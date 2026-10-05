# 03 — Feeds and discovery

> Status: Draft v1, 2026-10-04; revised 2026-10-05 for the owner decisions (GitHub-only builds, YouTube engine and external mode); revised 2026-10-05 for PO-31–PO-35 (notify-only update check, published debug builds) · Implements: R1.3 (fetch side), R1.9 (feed-URL secrecy), R2.6 (refresh a group), R2.7 (refresh interval, new-episode notifications), R3.1 (add-flow hand-off; channel search by name is 04's and needs the YouTube engine), R3.3 (refresh-engine side), R4.8 (show-notes timestamps), R5.2 (artwork selection) / N1, N2, N3, N6, N9 · Milestones: M1, M2, M3, M4, M5, M6, M7, M8, M9 (M9a), M11 (M11b) · Honours: D1, D2, D10, D11, D15, D18, D19, D24, D25, D26 (amended), D27, D28, D36, D45, D66, D73, D77, D78; PO-3 · Owns: `:feeds` feed formats, feed fetching and HTTP policy, ingestion diff, feed moves, aliases, Basic auth and `CredentialStore`, RFC 5005 paging, refresh engine and scheduling, show-notes sanitising, add-podcast pipeline and autodiscovery, directory search and charts, subscribe intents, `IngestionEvents` and new-episode notifications

Contents: [Scope](#scope) · [Parser](#parser) · [Fetch pipeline](#fetch-pipeline) · [Ingestion and diff](#ingestion-and-diff) · [Feed moves, auth and paging](#feed-moves-auth-and-paging) · [Refresh scheduling](#refresh-scheduling) · [Show notes](#show-notes) · [Add podcast flow](#add-podcast-flow) · [Search and discovery](#search-and-discovery) · [Deep links and share targets](#deep-links-and-share-targets) · [New-episode notifications](#new-episode-notifications) · [Settings](#settings) · [Testing](#testing) · [Delivery by milestone](#delivery-by-milestone) · [New names introduced here](#new-names-introduced-here) · [Open questions](#open-questions) · [Sources](#sources)

---

## Scope

Serves R1.3, R2.6, R2.7, R3.1, R3.3, N1, N2, N3, N6, N9. The phone polls every feed itself; there is no Neutrodyne server ([D1](../PLAN.md#3-key-decisions)). Engineers implement the `:feeds` module, the refresh engine in `:core:data`, the add-podcast pipeline and directory search from this document.

| Owned here | Not here (link instead) |
|---|---|
| `:feeds` feed-format API: `FeedParser`, namespaces, field mapping, dates, durations, enclosure types, artwork candidates, `EpisodeKeys`, `UrlNormalizer`, `PodcastGuid`, `PrivateFeedUrls`, `ShowNotesSanitizer`, `Autodiscovery`, `AddInputNormalizer`, directory-response parsers | Entities, column types, all SQL — [02 Tables](02-data-model.md#tables), [02 Key queries](02-data-model.md#key-queries) (this document lists only the columns it writes) |
| `FeedFetcher`, the feed response-code policy, `FeedErrorKind` values | Shared `OkHttpClient`, `AuthInterceptor`, `LocalNetworkGuardDns`, `NetError` — [01 Networking baseline](01-foundation.md#networking-baseline) |
| Ingestion diff and identity-key *computation*, `sortDate`, `isNew`, `inFeed` | Identity-key storage and versioning — [02 Identity keys](02-data-model.md#identity-keys) |
| Feed moves, aliases, merge decisions, Basic auth, `CredentialStore`, private-URL heuristic, RFC 5005 paging, `podcast:guid` derivation | OPML, import pipeline, backup — [05](05-groups-opml-backup.md) |
| Refresh engine (`FeedRefresher`), `RefreshWorker`, `RefreshScheduler`, `RefreshController` | YouTube Atom variants, entry rules, outage threshold, enrichment — [04 Atom feed ingestion](04-youtube.md#atom-feed-ingestion), plugged into this engine through `YouTubeSourceAdapter` |
| Show-notes sanitising, `ShowNotesDocument` block model, snippets, timestamp grammar | Rendering — [08 Components](08-ui-ux.md#components); seeking — [06 Chapters](06-playback.md#chapters) |
| Add-podcast pipeline, in-memory preview, `SubscribeUseCase`, unsubscribe | YouTube channel resolution — [04 Channel resolution](04-youtube.md#channel-resolution); screens — [08 Screens](08-ui-ux.md#screens) |
| Directory providers, charts, genre mapping, Podcast Index key handling (BYOK storage, optional release-build key) | Discover and Directory screens — [08 Screens](08-ui-ux.md#screens); YouTube channel search (YouTube engine only) — [04 Channel search](04-youtube.md#channel-search) |
| VIEW/SEND intent filters of `MainActivity`, input unwrapping | Routing mechanics — [01 Intent routing](01-foundation.md#intent-routing); file intents — [05 Receiving files](05-groups-opml-backup.md#receiving-files) |
| `IngestionEvents`, `NewEpisodeNotifier` (posting, channel choice, permission rule) | Channel create/rename/delete with groups — [05 Group model and lifecycle](05-groups-opml-backup.md#group-model-and-lifecycle); resolution rules — [05 Effective settings resolution](05-groups-opml-backup.md#effective-settings-resolution) |
| PSC chapter rows, chapter and transcript references at ingest | Chapter fetching and source priority — [06 Chapters](06-playback.md#chapters) |

```mermaid
flowchart LR
  subgraph FEEDS[":feeds (pure JVM)"]
    FP["FeedParser"]
    KEYS["EpisodeKeys, UrlNormalizer, PodcastGuid"]
    SNS["ShowNotesSanitizer"]
    DISC["AddInputNormalizer, Autodiscovery"]
    DIRP["directory parsers"]
  end
  subgraph DATA[":core:data"]
    RW["RefreshWorker, RefreshScheduler"]
    FR["FeedRefresher"]
    SA["RssSourceAdapter, YouTubeSourceAdapter"]
    FF["FeedFetcher"]
    FI["FeedIngestor"]
    CS["CredentialStore"]
    APR["AddPodcastResolverImpl, PreviewCache"]
    SUB["SubscribeUseCaseImpl"]
    SR["SearchRepositoryImpl"]
    NN["NewEpisodeNotifier"]
    BUS["IngestionEventBus"]
  end
  RW --> FR --> SA --> FF
  SA --> FP
  FR --> FI --> KEYS
  FI --> BUS
  FR --> NN
  APR --> FF
  APR --> DISC
  SUB --> FI
  SR --> DIRP
  FF -.->|"Basic auth via CredentialLookup"| CS
  BUS --> AD["AutoDownloadPlanner (07)"]
  IMP["ImportFetchWorker (05)"] --> FR
```

| Module | Contents from this document |
|---|---|
| `:feeds` (JVM) | The packages in [Package layout](#package-layout) |
| `:core:model` | `FeedErrorKind`, `FeedPreview`, `PreviewEpisode`, `FeedCandidate`, `AlreadySubscribed`, `BasicCredentials`, `DirectoryHit`, `ProviderId`, `ProviderStatus`, `ProviderInfo`, `SearchResults`, `ChartGenre`, `ShowNotes` (mirror of `ShowNotesDocument`), `ShowNotesImages`, read models `LibraryTile`, `PodcastDetail`, `FeedHealth`, `EpisodeDetail`, `FeedInfo`, `CategoryCount` |
| `:core:domain` | `PodcastRepository`, `EpisodeRepository`, `RefreshController` with its `RefreshScope` and `RefreshStatus`, `AddPodcastResolver`, `AddResolution`, `AddPodcastError`, `SubscribeUseCase`, `SubscribeError`, `UnsubscribeUseCase`, `SearchRepository`, `IngestionEvents` |
| `:core:data` | Implementations, plus `FeedFetcher`, `FeedTempFiles`, `FeedIngestor`, `FeedRefresher`, `SourceAdapter`s, `RefreshWorker`, `RefreshScheduler`, `CredentialStore`, `PreviewCache`, search providers, `NewEpisodeNotifier`, `IngestionEventBus`, `RefreshForegroundObserver`; uses 02's `FetchStateBatcher`; hosts 04's `YouTubeSourceAdapter` and `YouTubeOutageMonitor` (M8) |
| `:feature:discover` | Add-podcast sheet, Discover, Directory screens (visuals: 08) |
| `:app` | `MainActivity` intent filters ([Deep links and share targets](#deep-links-and-share-targets)) |

Everything specified here runs in the main process. The YouTube engine's `:ytx` process runs no `AppInitializer`, opens neither Room nor DataStore and hosts none of these classes ([D73](../PLAN.md#3-key-decisions), [01 Application start-up](01-foundation.md#application-start-up)), so refresh runs, `CredentialStore`, previews, directory search and notifications never execute there. Only 04's engine-backed implementations (enricher, channel lookup, channel search) talk to `:ytx`, from the main process through `YtDlpClient`.

---

## Parser

Serves N9, R5.2. Delivered in M1 ([D11](../PLAN.md#3-key-decisions)); Atom entries with `yt:` fields mapped generically from M1, interpreted by 04 from M8.

A hand-written streaming `XmlPullParser` maps RSS 2.0, Atom, RSS 1.0/RDF, iTunes, Podcasting 2.0, Media RSS and Podlove Simple Chapters onto one normalised model. prof18 RSS-Parser (Apache-2.0, 6.1.8) was rejected: no Podcasting 2.0 namespace, one enclosure per item, dates as strings, no paging links, literal-prefix tag matching ([RSS-Parser source](https://raw.githubusercontent.com/prof18/RSS-Parser/master/rssparser/src/commonMain/kotlin/com/prof18/rssparser/internal/RssKeyword.kt)). AntennaPod's parser is GPL-3.0: its behaviour may be studied, its code never copied ([N8](../PLAN.md#22-non-functional-requirements), risk L4).

### Package layout

```
feeds/src/main/kotlin/ch/lkmc/neutrodyne/feeds/
  model/      ParsedFeed, ParsedEpisode, Enclosure, AlternateEnclosure, ArtworkCandidate, Person, Funding,
              TranscriptRef, InlineChapter, Paging, ParseWarning, WarningCode, FeedFormat
  parse/      FeedParser, PullParserFactory, ParseLimits, Namespaces, HtmlEntities, InnerXml, PrologGuard,
              FeedDates, Durations, EnclosureTypes, FeedSniffer
  identity/   EpisodeKeys, KeyInput, EpisodeContentHash, TitleMatch, UrlNormalizer, PodcastGuid, PrivateFeedUrls
  html/       ShowNotesSanitizer, ShowNotesDocument (NoteBlock, NoteSpan), PlainTextSnippet, TimestampLinkifier
  discovery/  AddInputNormalizer, NormalizedInput, HostRecognizer, Autodiscovery, DiscoveredFeed
  directory/  AppleSearchParser, AppleChartParser, FyydSearchParser, PodcastIndexParser, AppleGenres
  opml/, backup/ (05) · youtube/ formats (04)
```

`:feeds` has no project dependencies, no Android and no I/O beyond reading the streams it is handed ([01 Dependency rules](01-foundation.md#dependency-rules)). kxml2 2.3.0 is `compileOnly` + `testImplementation` because `org.xmlpull.v1` is in `android.jar` ([kxml2 POM](https://repo1.maven.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.pom)).

```kotlin
// :feeds — ch.lkmc.neutrodyne.feeds.parse
fun interface PullParserFactory { fun create(): XmlPullParser }
// device (bound in :core:data): PullParserFactory { android.util.Xml.newPullParser() }  — AOSP KXmlParser
// JVM tests:                     PullParserFactory { org.kxml2.io.KXmlParser() }

class FeedParser(private val factory: PullParserFactory, private val limits: ParseLimits = ParseLimits()) {
    /** Pure: reads only through [open] (called a second time for a charset re-parse); never throws for
     *  malformed input. [baseUrl] resolves relative URLs (xml:base and atom links win when present). */
    fun parse(open: () -> InputStream, httpCharset: String?, baseUrl: String): ParseResult
    companion object { const val VERSION = 1 }
}
sealed interface ParseResult {
    data class Ok(val feed: ParsedFeed) : ParseResult
    data class Failed(val reason: ParseFailure, val detail: String) : ParseResult
}
enum class ParseFailure { NOT_A_FEED, HOSTILE, MALFORMED, TOO_DEEP }
data class ParseLimits(val maxDepth: Int = 64, val maxItems: Int = 10_000, val maxTextChars: Int = 512 * 1024,
                       val maxUrlChars: Int = 4_096, val prologScanBytes: Int = 64 * 1024)
```

```kotlin
// :feeds — ch.lkmc.neutrodyne.feeds.model (immutable, @Serializable for golden tests)
data class ParsedFeed(
    val format: FeedFormat,                              // RSS2, ATOM, RDF
    val title: String?, val author: String?, val descriptionHtml: String?, val link: String?,
    val language: String?, val categories: List<List<String>>, val explicit: Boolean?,
    val showType: String?, val complete: Boolean, val newFeedUrl: String?, val podcastGuid: String?,
    val locked: Boolean?, val medium: String?, val artwork: List<ArtworkCandidate>, val bannerUrl: String?,
    val funding: List<Funding>, val persons: List<Person>, val updateFrequencyRrule: String?, val ttlMinutes: Int?,
    val paging: Paging, val hubUrl: String?, val usesPodping: Boolean,
    val ytChannelId: String?,                            // raw feed-level yt:channelId; interpreted by 04
    val items: List<ParsedEpisode>,                      // document order
    val warnings: List<ParseWarning>,
)
data class ParsedEpisode(
    val feedOrder: Int, val guid: String?, val title: String?, val pubDate: Long?, val rawPubDate: String?,
    val descriptionHtml: String?, val descriptionIsHtml: Boolean, val link: String?,
    val enclosures: List<Enclosure>, val primaryEnclosure: Enclosure?, val alternateEnclosures: List<AlternateEnclosure>,
    val durationMs: Long?, val season: Int?, val seasonName: String?, val episodeNumber: String?,
    val episodeDisplay: String?, val episodeType: String?, val explicit: Boolean?, val artwork: List<ArtworkCandidate>,
    val chaptersUrl: String?, val chaptersType: String?, val inlineChapters: List<InlineChapter>,
    val transcripts: List<TranscriptRef>, val persons: List<Person>?, val funding: List<Funding>,
    val externalMediaId: String?,                        // yt:videoId
)
data class Enclosure(val url: String, val type: String?, val length: Long?, val effectiveType: String?)
data class Paging(val next: String?, val prevArchive: String?, val first: String?, val fhComplete: Boolean, val fhArchive: Boolean)
data class ParseWarning(val code: WarningCode, val itemIndex: Int?, val detail: String)
```

`showType` and `episodeType` stay lowercase strings in `:feeds` (it cannot see `:core:model`); `:core:data` maps them to `ShowType`/`EpisodeType` (02) and drops unknown values.

### Parser setup and charset

1. **Prolog guard** (`PrologGuard`, before any parser): scan the first 64 KiB up to the first element start tag; if it contains `<!ENTITY` (case-insensitive) return `Failed(HOSTILE)`. `FEATURE_PROCESS_DOCDECL` stays off, and no external DTD is ever fetched ([N9](../PLAN.md#22-non-functional-requirements)).
2. `setFeature(FEATURE_PROCESS_NAMESPACES, true)`; `runCatching { setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }` (relaxed tolerates undefined prefixes, unescaped `&`, bad attributes, unknown entities, which stay as literal `&name;` text).
3. First pass: `setInput(stream, null)`, so KXmlParser sniffs UTF-32/UTF-16 BOMs and the XML declaration and defaults to UTF-8 ([AOSP KXmlParser](https://android.googlesource.com/platform/libcore/+/refs/heads/main/xml/src/main/java/com/android/org/kxml2/io/KXmlParser.java)). The HTTP `charset` is deliberately not used for the first pass: an `ISO-8859-1` header on a UTF-8 body (a common server default) would yield mojibake that no heuristic can detect, because every byte is valid ISO-8859-1; a wrong declaration, in contrast, shows up as U+FFFD (step 5). Never pass a `Reader`.
4. Predefine all HTML 4 named entities with `defineEntityReplacementText` (`HtmlEntities.ALL`: `&nbsp;`, `&rsquo;`, `&eacute;`, …).
5. **Re-parse heuristic:** `InputStreamReader` replaces malformed bytes with U+FFFD silently. If more than 0.5 % of collected text characters are U+FFFD, re-parse via `open()` with (a) the HTTP `charset` if present and different from the detected encoding, else (b) `windows-1252`; keep the result with fewer replacement characters and add `CHARSET_REPARSED`.
6. **Mixed content:** in HTML-bearing elements (`description`, `content:encoded`, `itunes:summary`, Atom `content`/`summary` with `type="html"` or `"xhtml"`), child elements produced by unescaped markup are re-serialised (`InnerXml.collect`, rebuilding `<tag attr="…">…</tag>`), never dropped.
7. Root dispatch: `rss` → RSS 2.0; `feed` (Atom NS or none) → Atom; `RDF` → RSS 1.0; anything else → `Failed(NOT_A_FEED)`.

### Namespace registry

Matching rule: `canonical(namespaceUri) ?: prefixFallback[prefix]`; the prefix fallback covers `itunes`, `podcast`, `media`, `content`, `atom`, `psc` for feeds that forget the `xmlns` declaration (warning `UNDECLARED_PREFIX`).

| Key | Canonical URI | Also accepted |
|---|---|---|
| RSS | `""` | — |
| ITUNES | `http://www.itunes.com/dtds/podcast-1.0.dtd` | any case variant (`…/DTDs/Podcast-1.0.dtd`), `https` |
| PODCAST | `https://podcastindex.org/namespace/1.0` | `https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md` (spec: must be treated as identical; the reference feed uses it), `http://`, trailing `/` |
| ATOM | `http://www.w3.org/2005/Atom` | — |
| CONTENT | `http://purl.org/rss/1.0/modules/content/` | — |
| MEDIA | `http://search.yahoo.com/mrss/` | without trailing `/` |
| DC | `http://purl.org/dc/elements/1.1/` | — |
| PSC | `http://podlove.org/simple-chapters` | trailing `/` |
| FH | `http://purl.org/syndication/history/1.0` | — |
| SY | `http://purl.org/rss/1.0/modules/syndication/` | — |
| YT | `http://www.youtube.com/xml/schemas/2015` | — |
| GOOGLEPLAY | `http://www.google.com/schemas/play-podcasts/1.0` | fallback image/description only |
| RDF, RSS1 | `http://www.w3.org/1999/02/22-rdf-syntax-ns#`, `http://purl.org/rss/1.0/` | minimal |

### Field mapping

Precedence left to right; the first non-blank value wins.

| Channel field | Sources |
|---|---|
| `title` | `title` › `itunes:title` › Atom `title` |
| `author` | `itunes:author` › `dc:creator` › Atom `author/name` › `itunes:owner/itunes:name` › `managingEditor` |
| `descriptionHtml` | `description` › `itunes:summary` › `content:encoded` › Atom `subtitle` › `googleplay:description` |
| `link` | `link` › Atom `link[rel=alternate]` |
| `language` | `language`, normalised with `Locale.forLanguageTag(raw.replace('_','-')).toLanguageTag()`; `und` → null |
| `categories` | `itunes:category@text`, nested → path (`["Society & Culture","Documentary"]`) |
| `explicit` | `itunes:explicit` ∈ {yes, true, explicit} → true; {no, false, clean} → false |
| `showType` | `itunes:type` ∈ {episodic, serial} |
| `complete` | `itunes:complete == "yes"` OR `podcast:updateFrequency@complete == "true"` |
| `newFeedUrl` | `itunes:new-feed-url` |
| `podcastGuid` | `podcast:guid`, kept only if it matches 8-4-4-4-12 hex (lowercased) |
| `locked`, `medium` | `podcast:locked` (owner ignored), `podcast:medium` (verbatim, lowercase) |
| `funding`, `persons` | `podcast:funding@url` + text ≤ 128 chars; `podcast:person` (role default `host`, group `cast`, `img`, `href`) |
| `updateFrequencyRrule` | `podcast:updateFrequency@rrule` |
| `ttlMinutes` | RSS `ttl`, else `sy:updatePeriod` (hourly 60, daily 1440, weekly 10080, monthly 43200, yearly 525600) ÷ `sy:updateFrequency` |
| `paging` | `atom:link[rel=next/first/prev-archive]`, `fh:complete`, `fh:archive` |
| `hubUrl`, `usesPodping` | `atom:link[rel=hub]`, `podcast:podping@usesPodping` (stored, unused in v1) |
| `bannerUrl` | `podcast:image` whose `purpose` contains `banner` or `canvas` and aspect is `16/9` |

| Item field | Sources |
|---|---|
| `guid` | `guid` (any `isPermaLink`) › Atom `id` |
| `title` | `title` › `itunes:title` › `media:title` |
| `pubDate` | `pubDate` › Atom `published` › Atom `updated` › `dc:date` (raw string always kept in `rawPubDate`) |
| `descriptionHtml` | `content:encoded` › `description` › `itunes:summary` › Atom `content` › Atom `summary` › `media:description` (plain text, `descriptionIsHtml = false`) |
| `enclosures` | every `enclosure@url,type,length` › Atom `link[rel=enclosure]` › `media:content` with `medium` audio/video or type `audio/*`/`video/*` (also inside `media:group`; `isDefault="true"` first) |
| `alternateEnclosures` | `podcast:alternateEnclosure@type,length,bitrate,height,lang,title,rel,codecs,default` with `source@uri,contentType` and `integrity@type,value` |
| `durationMs` | `itunes:duration` › `media:content@duration` |
| `season`, `seasonName` | `podcast:season` (+`@name`) › `itunes:season` |
| `episodeNumber`, `episodeDisplay` | `podcast:episode` (decimal kept as string, `@display`) › `itunes:episode` |
| `episodeType`, `explicit` | `itunes:episodeType` ∈ {full, trailer, bonus}; `itunes:explicit` |
| `link` | `link` › Atom `link[rel=alternate]` |
| `chaptersUrl`, `chaptersType` | `podcast:chapters@url,type`; `application/json+chapters` and `application/json` both accepted |
| `inlineChapters` | `psc:chapters/psc:chapter@start,title,href,image` |
| `transcripts` | `podcast:transcript@url,type,language,rel`; `application/srt` ≡ `application/x-subrip` |
| `persons`, `funding` | item-level `podcast:person` **replaces** the channel list (spec); item `podcast:funding` |
| `externalMediaId` | `yt:videoId` |

### Podcasting 2.0 tiers

| Tier | Tags |
|---|---|
| Ingest and store (v1) | `guid`, `chapters`, `transcript`, `funding`, `person`, `season`, `episode`, `alternateEnclosure`/`source`/`integrity`, `locked`, `medium`, `image`, `images` (deprecated, still parsed), `updateFrequency` |
| Store only (no UI) | `podping`, `atom:link rel=hub`, `ttl`/`sy:*` hints |
| Parse, not stored in v1 | `trailer` (no column; UI later) |
| Ignored in v1 | `value`, `valueRecipient`, `liveItem`, `remoteItem`, `podroll`, `socialInteract`, `chat`, `soundbite`, `location`, `license`, `txt`, `block` |

`medium` values ending in `L` (`podcastL`, `musicL`, …) are lists of other feeds (`remoteItem` only): ingestion fails them with `UNSUPPORTED_LIST_FEED` instead of showing an empty podcast. `music` and `video` media with enclosures ingest normally.

### Dates

`FeedDates.parse(raw): Long?` (epoch ms UTC). `DateTimeFormatter.RFC_1123_DATE_TIME` is unusable: it rejects a wrong weekday and `PDT`/`UT`/`Z`/ISO forms (tested on JDK 21). Algorithm:

1. Trim; collapse whitespace; drop a leading weekday token (`^\p{L}{2,}\.?,?\s`), even a wrong or localised one (`Mié,`).
2. Normalise localised month abbreviations to English (German, French, Spanish, Italian, Dutch, Portuguese tables, e.g. `Okt`→`Oct`, `Mai`→`May`, `janv.`→`Jan`; heuristic).
3. Replace a trailing zone token: `UT, UTC, GMT, Z → +0000`, `EST -0500`, `EDT -0400`, `CST -0600`, `CDT -0500`, `MST -0700`, `MDT -0600`, `PST -0800`, `PDT -0700`.
4. Parse with a case-insensitive `Locale.ENGLISH` builder `d MMM [yyyy][yy] H:mm[:ss][.SSS]` + optional `+HHMM` or `+HH:MM`, defaulting the offset to UTC.
5. Fallbacks in order: `OffsetDateTime.parse` (ISO-8601/RFC 3339), `LocalDateTime.parse` (as UTC; a space between date and time is replaced by `T` first), `LocalDate.parse` (UTC midnight).
6. Unparsable → `null` + warning `UNKNOWN_DATE`. The parser applies **no** plausibility window (it is clock-free); ingestion does ([sortDate and clock](#sortdate-and-clock)).

### Durations

`Durations.parseMs(raw)`: split on `:`, accept 1–3 numeric parts (`H:MM:SS`, `MM:SS`, `M:SS`, seconds, `00:52:57`), allow a fractional last part, reject negative values, values over 48 h, empty and garbage (`null` + `BAD_DURATION`). The value is a hint; 06's measured duration supersedes it ([06 Positions and played state](06-playback.md#positions-and-played-state)).

### Enclosure types and media acceptance

`EnclosureTypes.effective(type, url)`: lowercase and strip parameters, then map aliases: `audio/mp3`, `audio/x-mp3`, `audio/mpeg3`, `audio/x-mpeg` → `audio/mpeg`; `audio/x-m4a`, `audio/m4a`, `audio/x-m4b` → `audio/mp4`; `audio/x-aac` → `audio/aac`; `application/ogg` → `audio/ogg`; `audio/x-wav` → `audio/wav`; `video/x-m4v` → `video/mp4`; `application.x-mpegurl` (sic, seen in the reference feed), `application/vnd.apple.mpegurl` → `application/x-mpegurl`. If the result is not `audio/*`, `video/*` or `application/x-mpegurl`, infer from the URL path extension: `.mp3` mpeg, `.m4a`/`.m4b` audio/mp4, `.aac`, `.ogg`/`.oga` audio/ogg, `.opus` audio/opus, `.flac`, `.wav`, `.mp4`/`.m4v` video/mp4, `.mov` video/quicktime, `.webm` video/webm, `.m3u8` application/x-mpegurl. `length` ≤ 0 or missing → `null`.

**Primary enclosure:** the first enclosure with an `audio/*` effective type; else the first `video/*`; else the first `application/x-mpegurl`; else none. `podcast:alternateEnclosure[@default=true]` changes transport only, never identity (06 decides whether to use it). `isVideo = effectiveType.startsWith("video/")`. HLS-only items are accepted and stored (so they are visible and the feed is not reported as `NO_MEDIA`), but v1 ships no Media3 HLS module: 06 skips them as `UnsupportedFormat` ([06 Media items and URI resolution](06-playback.md#media-items-and-uri-resolution)). The `hls-enclosure` fixture pins this.

### Artwork candidates

Podcast cover, first valid absolute `http(s)` URL wins (source recorded in the golden JSON):

1. channel `itunes:image@href` (or its text, a common error);
2. `podcast:image` with `purpose` containing `artwork` (or absent) and aspect `1/1` or absent, largest `width`;
3. deprecated `podcast:images@srcset`, largest;
4. channel `media:thumbnail` / `media:content[medium=image]`;
5. RSS `image/url` (often a ≤ 144 px logo);
6. Atom `logo`, then `icon`;
7. `googleplay:image@href`;
8. at subscribe time only: the directory hit's art (Apple `artworkUrl600`, Podcast Index `artwork`, fyyd `imgURL`);
9. none → monogram key ([02 podcast](02-data-model.md#podcast): `artworkKey = m-{sha1hex(feedKey)}`).

Episode art: item `itunes:image@href` (or text) › item `podcast:image` › `media:thumbnail` (also in `media:group`) › none. Ingestion stores `episode.imageUrl = null` when it equals the podcast cover after `UrlNormalizer.forIdentity` comparison (652 of 831 items in the 99% Invisible feed do). Artwork keys are derived with 08's key function in `:core:artwork` ([08 Artwork pipeline](08-ui-ux.md#artwork-pipeline)).

### Limits and version policy

| Limit | Value | On breach |
|---|---|---|
| Body size | 32 MB (fetch, decompressed) | `TOO_LARGE` |
| Element depth | 64 | `Failed(TOO_DEEP)` |
| Items per document | 10,000 | stop reading items, `ITEMS_TRUNCATED` |
| Text per element | 512 Ki chars | truncate, `TEXT_TRUNCATED` |
| URL length | 4,096 chars | URL dropped, `BAD_URL` |
| `<!ENTITY` in prolog | — | `Failed(HOSTILE)` |

`FeedParser.VERSION` (stored per podcast in `podcast.parserVersion` after each successful ingest) is bumped whenever a change would alter any value ingestion writes for an existing golden fixture; warnings-only changes do not bump. A podcast with `parserVersion < VERSION` is fetched once without validators and without the unchanged-SHA-256 shortcut, so every feed re-ingests after an upgrade ([Validators](#validators)).

---

## Fetch pipeline

Serves N3, N6, N9. Delivered in M1. Honours [D10](../PLAN.md#3-key-decisions) (no OkHttp `Cache`), [D28](../PLAN.md#3-key-decisions) (cleartext allowed, LAN unsupported).

```kotlin
// :core:data (internal)
internal class FeedFetcher @Inject constructor(
    @HttpClient(HttpClientKind.FEED) private val client: OkHttpClient,   // 15 s / 30 s / 120 s (01)
    private val credentials: CredentialStore, private val temp: FeedTempFiles,
    private val classifier: NetErrorClassifier, private val clock: Clock,
) { suspend fun fetch(req: FeedRequest): FetchOutcome }

data class FeedRequest(val url: String, val etag: String?, val lastModified: String?,
                       val conditional: Boolean, val maxBytes: Long = 32L * 1024 * 1024, val sniffOnlyBytes: Int? = null,
                       val credentials: BasicCredentials? = null)   // not-yet-stored credentials (add flow, setCredentials probe)
sealed interface FetchOutcome {
    data class NotModified(val maxAgeSec: Long?, val serverDateMs: Long?) : FetchOutcome
    data class Body(val file: File, val sha256Hex: String, val requestedUrl: String, val finalUrl: String,
        val permanentUrl: String?,                 // URL reached by the leading run of 301/308 hops, else null (Request rules)
        val etag: String?, val lastModified: String?, val charset: String?, val maxAgeSec: Long?,
        val serverDateMs: Long?, val sniff: Sniff) : FetchOutcome
    data class Http(val code: Int, val retryAfterMs: Long?, val basicChallenge: Boolean, val realm: String?) : FetchOutcome
    data class Network(val error: NetError) : FetchOutcome
    data object TooLarge : FetchOutcome
    data object RedirectLoop : FetchOutcome        // OkHttp "Too many follow-up requests"
}
enum class Sniff { RSS, ATOM, RDF, OPML, HTML, JSON, OTHER }
```

### Request rules

| Header / option | Rule |
|---|---|
| `Accept` | `application/rss+xml, application/atom+xml;q=0.9, application/xml;q=0.8, text/xml;q=0.8, */*;q=0.5` |
| `User-Agent` | set by `UserAgentInterceptor` (01); feeds.podcastindex.org returns 403 to generic UAs (tested) |
| `Accept-Encoding` | never set: OkHttp adds gzip and decompresses only when the caller did not set it |
| `If-None-Match` / `If-Modified-Since` | only when `conditional`; the stored `etag` (weak `W/"…"` sent verbatim) and the stored `Last-Modified` **string** (never a locally formatted date) |
| `Authorization` | normally never set by `FeedFetcher`: `AuthInterceptor` adds stored Basic credentials when the hop's origin equals the credential's origin, re-evaluated on every hop ([01 Interceptors](01-foundation.md#interceptors)). Only when `FeedRequest.credentials` is non-null (credentials typed in the add sheet or "Enter password", not stored yet) does `FeedFetcher` set `Authorization: Basic …` itself; `AuthInterceptor` then leaves the header alone and OkHttp drops it on a redirect to another scheme/host/port, so it never leaks cross-origin |
| Before the call | `credentials.awaitLoaded()` so a cold start never fetches a private feed without its credentials |
| Redirects | OkHttp follows up to 20 follow-ups. Collect the hop list by walking `priorResponse` from the final response back to hop 0, reverse it, and count the **leading** hops whose status is 301 or 308: `k = 0` → `permanentUrl = null`; otherwise `permanentUrl` = the request URL of hop `k` (for `A -301→ B -302→ C`, `permanentUrl = B`: B is the permanent new home that currently redirects temporarily; for `A -302→ B -301→ C`, `null`) |
| Cleartext | `http://` is fetched as given (network security config allows it, PO-13 default); only scheme-less user input tries `https://` first ([Input normalisation](#input-normalisation)) |

### Body, hashing and sniffing

1. Stream the response body into `cacheDir/feeds/{uuid}.tmp` through Okio `HashingSink.sha256`, counting decompressed bytes; above `maxBytes` cancel the call, delete the file and return `TooLarge` (also bounds gzip bombs).
2. `sniffOnlyBytes` (probes in [Autodiscovery](#fetch-sniff-and-autodiscovery)) stops after N bytes.
3. `FeedSniffer` reads at most the first 64 KiB (the prolog-guard window; long comments or DOCTYPEs before the root exist): skip BOM (UTF-8/16/32), whitespace, `<?xml…?>`, comments and `<!DOCTYPE …>` (except `<!DOCTYPE html`), then classify the first element: `rss` → RSS, `feed` → ATOM, `RDF`/`rdf:RDF` → RDF, `opml` → OPML, `html` or `<!DOCTYPE html` → HTML; a first non-space byte `{` or `[` → JSON; otherwise OTHER.
4. `FeedTempFiles` deletes each file in a `finally` after parsing; `sweep()` at every engine start deletes files older than 1 h.

### Response handling

| Response | Outcome | Podcast state (refresh) |
|---|---|---|
| 200, sniff RSS/ATOM/RDF | `Body` → parse | see [Ingestion and diff](#ingestion-and-diff) |
| 200, identical SHA-256, `parserVersion == VERSION`, `lastParseOk` | `Unchanged` (no parse) | success scheduling; the response's `etag`/`lastModified` (and `lastFullFetchAt` when the request was unconditional) go into the batched fetch-state write ([Validators](#validators)) |
| 200, sniff HTML | `NOT_A_FEED` | existing data untouched; failure backoff. Pending first fetch: [autodiscovery](#refresh-of-pending-podcasts) |
| 200, sniff OPML/JSON/OTHER | `NOT_A_FEED` | as above |
| 200, parse failed | `PARSE_ERROR` | data untouched, `lastParseOk = 0`, validators not stored |
| 304 (validators were sent) | `NotModified` | success scheduling only. A 304 to an unconditional request is `HTTP_CLIENT` |
| 401 or 403 with `WWW-Authenticate: Basic` | `HTTP_AUTH` | `needsCredentials = 1`; no auto-refresh until the user acts |
| 401 other schemes, 403 | `HTTP_FORBIDDEN` | failure backoff |
| 404 | `HTTP_NOT_FOUND` | failure backoff (never `gone`: hosts return 404 during migrations); may become "possibly dead" ([Per-feed states](#per-feed-states)). YouTube: transient, never `gone`, and during a YouTube-wide outage the adapter defers instead of failing ([04 Fetch policy](04-youtube.md#fetch-policy)) |
| 410 | `HTTP_GONE` | `gone = 1`; excluded from refresh until "Try again" |
| 429, 503 | `HTTP_RATE_LIMITED` / `HTTP_SERVER` | `Retry-After` (delta-seconds or HTTP-date) honoured when larger than backoff, capped at 7 d |
| other 5xx / 4xx | `HTTP_SERVER` / `HTTP_CLIENT` | failure backoff |
| too many redirects | `REDIRECT_LOOP` | failure backoff |
| body over 32 MB | `TOO_LARGE` | failure backoff |
| `IOException` | `NetErrorClassifier` → kind below | `OFFLINE` changes no column (reported as `FeedOutcome.Failed(OFFLINE)`, the feed stays due); a cancelled call (`NetError.Cancelled`: deadline, worker stop) produces no outcome; `LOCAL_NETWORK_UNSUPPORTED` → `nextRefreshAt = now + 24 h`; others failure backoff |

### Error kinds

`FeedErrorKind` (`:core:model`, stored by name in `podcast.lastErrorKind`, [02 podcast](02-data-model.md#podcast); 02's converter falls back to `UNKNOWN`; constants are only ever appended); `lastErrorDetail` holds the HTTP status or the redacted exception class, never a URL with credentials.

```kotlin
enum class FeedErrorKind {   // 24 values
    OFFLINE, TIMEOUT, DNS, CONNECTION, LOCAL_NETWORK_UNSUPPORTED, TLS_UNTRUSTED, TLS_CERTIFICATE_TRANSPARENCY, TLS_HANDSHAKE,
    HTTP_AUTH, HTTP_FORBIDDEN, HTTP_NOT_FOUND, HTTP_GONE, HTTP_RATE_LIMITED, HTTP_SERVER, HTTP_CLIENT, REDIRECT_LOOP, TOO_LARGE,
    NOT_A_FEED, PARSE_ERROR, NO_MEDIA, UNSUPPORTED_LIST_FEED, IDENTITY_CONFLICT, STORAGE, UNKNOWN,
}
```

| `FeedErrorKind` | From |
|---|---|
| `OFFLINE`, `TIMEOUT`, `DNS`, `CONNECTION`, `LOCAL_NETWORK_UNSUPPORTED`, `TLS_UNTRUSTED`, `TLS_CERTIFICATE_TRANSPARENCY`, `TLS_HANDSHAKE` | `NetError.Offline`, `Timeout`, `DnsFailure`, `ConnectionFailed`, `LocalNetworkUnsupported`, `Tls(UNTRUSTED_CERTIFICATE / CERTIFICATE_TRANSPARENCY / HANDSHAKE)` ([01 Network error taxonomy](01-foundation.md#network-error-taxonomy)); `NetError.Cancelled` is not recorded |
| `HTTP_AUTH`, `HTTP_FORBIDDEN`, `HTTP_NOT_FOUND`, `HTTP_GONE`, `HTTP_RATE_LIMITED`, `HTTP_SERVER`, `HTTP_CLIENT`, `REDIRECT_LOOP`, `TOO_LARGE` | table above |
| `NOT_A_FEED`, `PARSE_ERROR`, `NO_MEDIA`, `UNSUPPORTED_LIST_FEED` | sniffing, parser, [accepted items](#accepted-items) |
| `IDENTITY_CONFLICT` | unique-index abort during ingest ([02 Uniqueness](02-data-model.md#uniqueness-and-in-place-re-keying)) |
| `STORAGE` | `SQLITE_FULL` or no space for the temp file |
| `UNKNOWN` | anything else (`NetError.Other`) |

Android 17 specifics: `LocalNetworkGuardDns` fails LAN hosts fast on API 37+ (v1 never requests `ACCESS_LOCAL_NETWORK`, [D28](../PLAN.md#3-key-decisions)); the UI text is "Feeds on your local network aren't supported yet". Certificate Transparency is enforced for targetSdk 37 and surfaces as `TLS_CERTIFICATE_TRANSPARENCY` per feed, never silently ([Local network permission](https://developer.android.com/privacy-and-security/local-network-permission), [Network security config](https://developer.android.com/privacy-and-security/security-config)).

### Validators

- `etag`, `lastModified`, `contentSha256` and `parserVersion` are written **only inside the ingest transaction that commits the parsed body**, or — for an `Unchanged` outcome, whose body equals the last committed one — `etag`, `lastModified` and `lastFullFetchAt` through 02's batched `updateFetchStates` (its `PodcastFetchState` partial row must carry these three columns and `lastParseOk`, filled from the response or, when absent, from the `DueFeed` snapshot; requested from 02); a `PARSE_ERROR` writes `lastParseOk = 0` the same way. A crash before commit can therefore never strand a feed in "304 forever". `lastFullFetchAt` = time of the last unconditional 200 that was ingested or `Unchanged`.
- Validators are not sent when `parserVersion < FeedParser.VERSION`, when `lastParseOk = 0`, for previews, for `new-feed-url` probes, for paging requests, and for the **fortnightly full fetch**: when `lastFullFetchAt < now − 14 d` **and** the current network is unmetered (`NetworkMonitor.status.isMetered == false`), the request is unconditional. This catches servers that answer 304 forever although the feed changed (prior-art pitfall: constant ETag / stale `Last-Modified`), while keeping the extra data on Wi-Fi.
- A URL change (move) discards the old validators; the new URL's validators are stored from the response that was ingested.
- Feed requests never use OkHttp's `Cache` ([D10](../PLAN.md#3-key-decisions)): simplecast sends `max-age=3600` (tested), which an HTTP cache would honour on pull-to-refresh; `max-age` is used only as a lower bound for success scheduling.

---

## Ingestion and diff

Serves N1, R2.3, R2.8 (inputs), R3.3. Delivered in M1. Honours [D15](../PLAN.md#3-key-decisions), [D18](../PLAN.md#3-key-decisions), [D19](../PLAN.md#3-key-decisions), [D66](../PLAN.md#3-key-decisions). DAO primitives: [02 Ingestion support](02-data-model.md#ingestion-support).

```kotlin
// :core:data (internal)
internal class FeedIngestor @Inject constructor(/* db, IngestDao, PodcastDao, Clock, ArtworkPinner?, IngestionEventBus */) {
    suspend fun ingest(podcast: DueFeed, parsed: ParsedFeed, ctx: IngestContext): IngestResult  // opens one withWriteTransaction
    suspend fun ingestInTransaction(podcast: DueFeed, parsed: ParsedFeed, ctx: IngestContext): IngestResult // caller's transaction (SubscribeUseCase)
}
data class IngestContext(val mode: IngestMode, val partial: Boolean, val fetch: FetchMeta,
                         val rowHints: Map<String, RowHint> = emptyMap(),   // keyed by externalMediaId (04)
                         val absenceFloor: Long? = null)                    // 04: replaces the partial-window floor of step 8
enum class IngestMode { REFRESH, INITIAL, OLDER_PAGE }
data class FetchMeta(val finalUrl: String, val permanentUrl: String?, val etag: String?, val lastModified: String?,
                     val sha256Hex: String, val serverDateMs: Long?, val maxAgeSec: Long?)
data class RowHint(val availability: Availability?, val isShort: Boolean?, val isVideo: Boolean?)
data class IngestResult(val inserted: List<Long>, val newIds: List<Long>, val updated: Int, val accepted: Int,
                        val flippedOut: Int, val firstIngest: Boolean, val artworkChanged: Boolean,
                        val warnings: List<ParseWarning>)
```

`IngestMode.INITIAL` is used when `podcast.initialFetch = 1` and by `SubscribeUseCase`; `OLDER_PAGE` by [paging](#rfc-5005-paging). `DueFeed` is 02's `dueForRefresh` row ([Refresh selection](02-data-model.md#refresh-selection-and-fetch-state-writes)).

### Accepted items

An item is **accepted** only if it has a primary enclosure or an `externalMediaId`; others (blog posts) are skipped and counted (`NO_MEDIA_ITEM` warning). This enforces 02's invariant `enclosureUrl IS NOT NULL OR externalMediaId IS NOT NULL`.

| Parse result | Effect |
|---|---|
| ≥ 1 accepted item | normal diff |
| items present, 0 accepted | `NO_MEDIA`: nothing written except `lastErrorKind = NO_MEDIA` and success scheduling; `status`/`initialFetch` unchanged (a blog that later becomes a podcast still ingests with initial-fetch semantics) |
| 0 items, `medium` ends in `L` | `UNSUPPORTED_LIST_FEED` |
| 0 items | metadata updated; no episode touched; a pending podcast becomes `ACTIVE` (new show without episodes yet) |
| Accepted item with an empty title | episode `title` = `pubDate` as `yyyy-MM-dd` (UTC) `?:` enclosure file name (last path segment, URL-decoded) `?:` `"…"` (02 requires non-empty) |

### Episode keys and matching helpers

```kotlin
// :feeds — ch.lkmc.neutrodyne.feeds.identity (algorithm owned here, storage format: 02 Identity keys)
object EpisodeKeys {
    const val VERSION = 1
    fun primary(e: ParsedEpisode): String       // g: → u: → t: → l: → h: (02 grammar)
    fun fallbacks(e: ParsedEpisode): List<String>  // [u:, t:] used when primary repeats within one document
    fun candidates(e: ParsedEpisode): List<String> // current-version primary first, then older versions (v1: [primary])
    fun keyFor(e: KeyInput, version: Int): String   // stored episode → key of a given version (restore, 05)
    fun versionOf(key: String): Int                 // optional numeric prefix; absent = 1
}
data class KeyInput(val guid: String?, val enclosureUrl: String?, val title: String?, val pubDate: Long?,
                    val link: String?, val descriptionHead: String?)
object EpisodeContentHash { fun of(e: ParsedEpisode): Long }   // first 8 bytes (big-endian) of SHA-256
object TitleMatch { fun normalise(title: String): String }     // NFKC, lowercase ROOT, quotes/dashes folded, spaces collapsed
```

- `g:` = `guid.trim()` (non-blank); `u:` = `UrlNormalizer.forIdentity(primaryEnclosure.url)` (non-null); `t:` (needs a non-blank title and a parsed `pubDate`) = sha1hex(`title.trim().lowercase(Locale.ROOT)` + `"|"` + `Instant.ofEpochMilli(pubDate).truncatedTo(ChronoUnit.DAYS).toString()`), exactly 02's grammar ([02 Episode identityKey](02-data-model.md#episode-identitykey)); `l:` = sha1hex(`link.trim()`); `h:` = sha1hex(`title.orEmpty() + description.orEmpty().take(500)`). `primary` takes the first applicable kind in that order (`h:` always applies). Enclosure URLs that are not absolute `http(s)` are dropped at parse time (`BAD_URL`), so a primary enclosure always yields a `u:` key.
- `EpisodeContentHash` hashes, separated by U+001F (list entries by U+001E): title, pubDate (or rawPubDate), primary enclosure url/type/length, isVideo, durationMs, season, seasonName, episodeNumber, episodeDisplay, episodeType, explicit, chosen image URL, link, chaptersUrl/Type, externalMediaId, SHA-256 of the description, transcripts, alternate enclosures, persons, funding, inline chapters. `feedOrder` is excluded, so re-ordering alone causes no write.

### Diff algorithm

All CPU work runs before the transaction on `@Dispatcher(Default)`; the transaction is one `withWriteTransaction` per feed ([02 Transactions and threading](02-data-model.md#transactions-and-threading)), ≤ 150 ms for 831 items.

**Prepare (outside the transaction)**

1. Filter accepted items; compute for each: key candidates, normalised enclosure URL and its query-less variant (`UrlNormalizer.forIdentityNoQuery`), `TitleMatch` + UTC day, `contentHash`, encoded description (`EpisodeDescriptionCodec`, 02), snippet ([Show notes](#show-notes)), child rows (transcripts, alternate enclosures, persons, funding, PSC chapters).
2. **In-document key dedupe**, document order: if an item's primary key was already used by an earlier item (a generator that repeats GUIDs), try its `fallbacks()` in order; if every key is taken, drop the item with `DUPLICATE_ITEM`; record `DUPLICATE_GUID`.

**Transaction**

3. Load `IngestDao.existing(podcastId)`; build `byKey`, `byEnclosure`, `byEnclosureNoQuery`, `byTitleDay` maps.
4. **Pass 1 (exact):** for each item, the first of `candidates(item)` present in `byKey` and not yet matched matches; a match on an older-version key rewrites the row's key in place (`IngestDao.rekey`).
5. **Pass 2 (fallback, GUID rewrites):** for each unmatched item, consider only stored rows that are unmatched **and** whose `identityKey` is not a primary key of any item in this document. Match by normalised enclosure URL, then query-less enclosure URL, then `TitleMatch` + same UTC day with guards: both durations known ⇒ within 10 min; both MIME major types known ⇒ equal (behaviour of AntennaPod's duplicate guesser, re-implemented). A match calls `rekey(id, newKey, newGuid)`, preserving the row and all user state ([02 Uniqueness](02-data-model.md#uniqueness-and-in-place-re-keying)). Because in-document keys are unique and pass 1 runs first, the new key is never held by another stored row.
6. **Matched rows:** if `contentHash` differs, `updateFeedFields` and `replaceChildren` (column rules below); if the stored `chaptersUrl` differs from the new non-null one, also delete the row's `chapter` rows with `source = PODCASTING20_JSON` so 06 re-fetches them ([06 Chapters](06-playback.md#chapters)); if `inFeed = 0`, flip to 1.
7. **Unmatched items:** insert with `firstSeenAt`, `sortDate`, `isNew` (rules below), `lastSeenAt = firstSeenAt`, `inFeed = 1`, in **descending `feedOrder`** (02 invariant: the first item in the document gets the highest `id`), then their children; PSC chapters become `chapter` rows with `source = PSC` (start in Normal Play Time `hh:mm:ss.mmm`, `mm:ss` or seconds; ordered by start).
8. **Absent rows:** only if `accepted ≥ 1` and mode is not `OLDER_PAGE`: stored rows not matched get `inFeed = 0`. If the document is **partial** (`paging.next` or `prevArchive` present without `fh:complete`, or the adapter says `partial = true`), only absent rows with `sortDate ≥ floor` are flipped, where `floor = ctx.absenceFloor ?: min(sortDate of the rows this document matched or inserted)` (04 supplies `absenceFloor` for merged YouTube variants; `Long.MAX_VALUE` flips nothing); older rows outside the window are left alone (they are on older pages, or beyond YouTube's 15-entry window). Ingestion never deletes an episode.
9. `touchSeen(podcastId, now)`.
10. Moves accepted for this body ([Permanent redirects](#permanent-redirects), renormalisation) are applied first in the same transaction. Then `applyFeedMetadata` (02; for `YOUTUBE_CHANNEL` rows its `YouTubeFeedMetadata` variant, which additionally leaves `artworkUrl`, `artworkKey`, `bannerUrl`, `descriptionHtml`, `link` and `youtubeChannelId` to 04): feed metadata (title only when non-blank; `customTitle`, `includeInAll`, `episodeOrder`, `youtubeVariants`, `autoDownloadEligibleAfter`, `channelMetadataAt` are never touched), categories as `categoriesJson`, `artworkUrl`/`artworkKey`, `bannerUrl`, `podcastGuid` (real one, `podcastGuidDerived = 0`; else keep a stored real one; else derive once, `podcastGuidDerived = 1`), `ttlMinutes`, `updateFrequencyRrule`, `hubUrl`, `usesPodping`, paging columns ([RFC 5005 paging](#rfc-5005-paging)), `latestEpisodeAt = max(sortDate)`, validators, `parserVersion = VERSION`, `lastParseOk = 1`, `contentSha256`, `lastFullFetchAt`, scheduling columns ([nextRefreshAt policy](#nextrefreshat-policy)), `failureCount = 0`, `lastErrorKind = null`, and on the first accepted ingest `status = ACTIVE`, `initialFetch = 0`.
11. After commit: if `artworkUrl` changed and `ArtworkStore` exists (M4+), request a pin of the new key (08 enqueues `artwork-sync`; the old key is garbage-collected by 02's reference query); then the engine calls `ids = adapter.afterIngest(podcastId, inserted, newIds)` ([Source adapters](#source-adapters); RSS returns `newIds` unchanged; 04's enrichment, which runs only with the YouTube engine, may drop IDs, e.g. upcoming premieres); then emit `NewEpisodes` with `ids` ([Events](#events)). The emission runs in a `finally` under `NonCancellable`, so a deadline cancellation inside `afterIngest` never loses the event (a cancelled `afterIngest` emits `newIds`).

A `SQLITE_CONSTRAINT_UNIQUE` from step 4–7 rolls the feed back and records `IDENTITY_CONFLICT` (a bug or a hostile feed); the next refresh retries.

### isNew and back-catalogue guard

```
newFloor = max(previousLatestEpisodeAt ?: Long.MIN_VALUE, podcast.subscribedAt) − 7 d
isNew    = mode == REFRESH && podcast.initialFetch == 0 && (pubDateValid ?: now) ≥ newFloor
```

- `INITIAL` and `OLDER_PAGE` ingests never set `isNew` ([D66](../PLAN.md#3-key-decisions)); imports, restores and subscribes therefore produce no notifications and no auto-downloads.
- A host re-adding old episodes, or a podcast with no episodes yet receiving its back catalogue, fails the floor.
- **Dump guard:** if more than 20 inserted items qualify in one ingest, only the newest 3 by `(sortDate, feedOrder)` keep `isNew = 1`; warning `BACK_CATALOGUE_DUMP`.
- `isNew` is never cleared by user actions (02); "new since last visit" is 05's `isNew && firstSeenAt > lastViewedAt`.

### sortDate and clock

- `now` comes from `Clock`. If the response carried a `Date` header and `|now − serverDate| > 24 h`, `firstSeenAt` uses the server date (device clock wrong).
- `pubDateValid = pubDate` if `1990-01-01 ≤ pubDate ≤ now + 365 d`, else `null` (`rawPubDate` keeps the original).
- `sortDate = min(pubDateValid ?: firstSeenAt, firstSeenAt + 24 h)` ([D19](../PLAN.md#3-key-decisions)); recomputed with the stored `firstSeenAt` whenever an update changes `pubDate`. Ties are broken by `id` (insertion in descending `feedOrder`).

### Column rules on update

`updateFeedFields` (02) writes every feed column except `id`, `podcastId`, `identityKey`, `firstSeenAt` and `isNew`, with these rules:

| Column | Rule |
|---|---|
| `durationMs`, `imageUrl`/`artworkKey`, `chaptersUrl` | a `null` parsed value never overwrites a stored non-null value (feeds drop optional tags intermittently; YouTube Atom never carries durations, which 04's enrichment writes) |
| `availability`, `isShort`, `isVideo` for `externalMediaId` rows | taken from the adapter's `RowHint` when present, else unchanged (04 owns the rules) |
| `enclosureUrl` | updated silently (tracking prefixes rotate); an existing download keeps its file and 07 decides about re-downloading |
| `snippet`, `episode_description` | rewritten together with the description |

### Podcast dedupe and merge

- **Subscribe-time dedupe** ([Preview and dedupe](#preview-and-dedupe)): normalised input URL and final URL against `podcast.feedKey` and `podcast_url_alias.url` (exact: "Already subscribed"), then a **real** `podcastGuid` (soft: "You may already be subscribed"). Derived GUIDs are never used ([02 podcastGuid](02-data-model.md#podcastguid)).
- **Automatic merge** happens only on URL identity: when an accepted move (301/308 chain, validated `new-feed-url`, user "Edit URL") produces a `feedKey` that is another podcast's `feedKey` or alias. Winner = the podcast that owns that key; loser = the moving podcast. The engine detects the collision **before** opening the ingest transaction (`feedKey`/alias lookup of the new key) and then:
  1. flushes `FetchStateBatcher`; matches loser episodes to winner episodes in Kotlin exactly as 02's merge does (`identityKey`, then normalised enclosure URL);
  2. (M6+) deletes, through `DownloadController.delete(ids, byUser = false)`, the downloads of **matched** loser episodes whose winner episode already has a `download` row — 02's `UPDATE OR IGNORE download` would otherwise leave that loser file orphaned. Downloads of unmatched loser episodes are kept: those episodes are re-parented to the winner with their `download` rows ([07 Unsubscribe, merge and retention](07-downloads.md#unsubscribe-merge-and-retention));
  3. runs 02's merge transaction ([02 Unsubscribe and merge](02-data-model.md#unsubscribe-and-merge));
  4. ingests the already parsed body into the winner (mode `INITIAL` if the winner is still pending, else `REFRESH`; the loser's validators are discarded);
  5. reports `FeedOutcome.Merged(winnerId)` for the loser and calls `reschedulePeriodic()`.
- Equal real `podcastGuid` alone never merges (an ad-free premium feed legitimately shares the public feed's GUID); the outcome carries `sameGuidAs` so 05's import report can say "possibly the same show as …".

### Events

```kotlin
// :core:domain (canonical)
interface IngestionEvents { val newEpisodes: SharedFlow<NewEpisodes> }
// :core:model (canonical): data class NewEpisodes(val podcastId: Long, val episodeIds: List<Long>, val initialFetch: Boolean)
```

- `IngestionEventBus` (`:core:data`, singleton, bound as `IngestionEvents`) = `MutableSharedFlow(replay = 0, extraBufferCapacity = 256, onBufferOverflow = DROP_OLDEST)`, emitted with `tryEmit` (never suspends) **after** the feed's transaction commits and after the adapter's `afterIngest`.
- `initialFetch = false`: `episodeIds` = rows inserted with `isNew = 1`, as filtered by the adapter's `afterIngest`; emitted only if non-empty. The same list goes into `RefreshReport.newEpisodes` for the notifier. `initialFetch = true` (INITIAL ingests): `episodeIds` = all inserted rows, none of them new; consumers must not react user-visibly to these.
- Delivery is in-process and best effort. Consumers that must not miss work re-derive it from the database (07's planner queries `isNew` and `firstSeenAt > autoDownloadEligibleAfter`, [02 Auto-download candidates](02-data-model.md#auto-download-candidates)). New-episode notifications are not driven by this flow but by the run report ([New-episode notifications](#new-episode-notifications)).

```mermaid
sequenceDiagram
  participant E as FeedRefresher
  participant A as SourceAdapter
  participant F as FeedFetcher
  participant P as FeedParser
  participant I as FeedIngestor
  participant DB as Room
  participant B as IngestionEventBus
  E->>A: fetchAndParse(feed, mode)
  A->>F: fetch(url, validators)
  alt 304 or identical SHA-256
    F-->>A: NotModified or Body
    A-->>E: NotModified or Unchanged
    E->>DB: batched fetch-state write
  else feed body
    F-->>A: Body(temp file, sha256)
    A->>P: parse(temp file)
    P-->>A: ParsedFeed
    A-->>E: Parsed(feed, partial)
    E->>I: ingest(podcast, feed, context)
    I->>DB: one write transaction (diff, metadata, validators, schedule)
    I-->>E: IngestResult
    E->>A: afterIngest(podcastId, inserted, newIds)
    A-->>E: IDs to announce
    E->>B: emit NewEpisodes (finally, NonCancellable)
  else failure
    A-->>E: Failed(kind, retryAfter)
    E->>DB: batched fetch-state write with backoff
  else deferred (04, YouTube outage)
    A-->>E: Deferred(untilMs)
    E->>DB: batched write of nextRefreshAt only
  end
```

---

## Feed moves, auth and paging

Serves R1.9, N1, N3. Delivered in M1. Storage: [02 Podcast feedKey and aliases](02-data-model.md#podcast-feedkey-and-aliases), [02 credential](02-data-model.md#credential).

### URL normalisation

```kotlin
// :feeds — ch.lkmc.neutrodyne.feeds.identity
object UrlNormalizer {
    const val VERSION = 1                                   // a change is an identity-key version change (02)
    fun forIdentity(url: String): String?                   // null if not http(s)
    fun forIdentityNoQuery(url: String): String?            // weak enclosure match only
    fun splitUserInfo(url: String): Pair<String, BasicCredentials?>
    fun origin(url: String): String?                        // "https://host[:port]", the credential.origin form
}
```

`forIdentity` (scheme-free, so `http` and `https` compare equal): parse with `java.net.URI` (lenient fallback for unescaped spaces); host lowercased, IDN → ASCII, trailing dot removed; ports 80 and 443 dropped; empty path → `/`; percent-encoding normalised (unreserved characters decoded, other escapes uppercased); dot segments removed; one trailing `/` removed unless the path is `/`; query kept verbatim in order (empty `?` dropped); userinfo and fragment dropped. Output `host[:port]/path[?query]`, e.g. `HTTPS://Feeds.Example.com:443/Show/?a=1#x` → `feeds.example.com/Show?a=1`. Analytics prefixes (`dts.podtrac.com/redirect.mp3/…`, `chrt.fm/track/…`, `pdst.fm/e/…`) are **not** stripped: they rotate, so enclosure URLs are a secondary signal only.

### Permanent redirects

- Adopt `permanentUrl` (the URL reached by the leading run of 301/308 hops from the **original** request, [Request rules](#request-rules)) only when it is non-null **and** the body parsed with ≥ 1 accepted item. Then, in the ingest transaction: `feedUrl = permanentUrl`, `feedKey` recomputed, old `feedKey` inserted as alias (`REDIRECT`), an alias equal to the new `feedKey` deleted first, old validators discarded (02 rules). A collision with another podcast's key → [merge](#podcast-dedupe-and-merge).
- 302, 303 and 307 are never adopted (captive portals, CDNs, login pages).
- If only the scheme differs (`http` → `https`, same `feedKey`), `feedUrl` is updated without an alias.
- After every successful refresh `feedKey` is recomputed from `feedUrl`; a difference (normaliser version change) is applied as a move with reason `RENORMALISED`.
- Podcast info shows "Feed moved to … on <date>" from aliases with reason `REDIRECT`/`NEW_FEED_URL` (`PodcastRepository.observeFeedInfo`), which matters when a feed is hijacked.

### itunes:new-feed-url

1. Ignore it when `forIdentity(newFeedUrl)` equals the current `feedKey` (self-referential, as in 99% Invisible), is not `http(s)`, or equals an alias this podcast moved away from (loop).
2. Otherwise store it in `podcast.pendingNewFeedUrl` (every ingest rewrites the column from the body, so it is cleared as soon as the publisher removes the tag) and, after the current ingest commits, probe it in the same run: unconditional `FeedRequest`, require a feed with ≥ 1 accepted item and plausibility — equal real `podcastGuid`, **or** at least one shared `g:` key among the newest 20 items, **or** normalised-title similarity ≥ 0.8 (1 − Levenshtein distance / max length on `TitleMatch` forms).
3. Plausible → move (alias reason `NEW_FEED_URL`) and ingest the probed body in REFRESH mode in one transaction; `pendingNewFeedUrl = null`. Not plausible or failed → keep it pending; the probe is repeated only after a later ingest of a changed body (304 and `Unchanged` outcomes never probe), and an in-memory LRU (100 URLs, 24 h) of implausible targets prevents repeats within a process, so a stale or hostile tag costs at most one extra request per published episode. Diagnostics list podcasts with a pending URL. At most 5 hops (chained `new-feed-url`s) per run.
Apple asks publishers to keep both the 301 and the tag for at least four weeks, so lazy adoption is safe ([Apple: change the RSS feed URL](https://podcasters.apple.com/support/change-the-rss-feed-url)).

### Basic auth and CredentialStore

```kotlin
// :core:data — implements :core:network CredentialLookup (01) and replaces CredentialLookup.None in M1
@Singleton internal class CredentialStore @Inject constructor(/* CredentialDao, CipherProvider, @ApplicationScope */) : CredentialLookup {
    override suspend fun awaitLoaded()                                     // suspends until the first load completed
    override fun basicAuthorization(origin: Origin): String?               // "Basic base64(user:pass)", non-blocking map read
    suspend fun put(origin: String, username: String, secret: CharArray): Long   // replaces the row for that origin
    suspend fun remove(id: Long)
    suspend fun forPodcast(podcastId: Long): BasicCredentials?             // 05's opted-in exports
    suspend fun podcastIndexKey(): Pair<String, String>?                   // origin = "podcastindex"
}
```

- **In-memory map:** keyed by `Origin` (01): the stored `credential.origin` string (`scheme://host[:port]`, default port omitted, [02 credential](02-data-model.md#credential)) is parsed into `Origin(scheme, host, port)` with 80/443 filled in, so it equals `Origin.of(request.url)`. The store collects `CredentialDao.observeAll()` on `@ApplicationScope` and rebuilds the map (decrypting only rows it has not seen), so rows removed by 02's unsubscribe cascade, merge or the `db-maintenance` sweep leave memory on the same commit; `awaitLoaded()` completes after the first emission.

- **Crypto:** Android Keystore key alias `neutrodyne_credentials_v1`, AES-256-GCM, `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, no padding, 12-byte IV generated by the cipher, 128-bit tag, AAD = `origin + "\n" + username`; rows in `credential` (`secretCipher`, `iv`). `androidx.security:security-crypto` is deprecated and not used ([security releases](https://developer.android.com/jetpack/androidx/releases/security)). `CipherProvider` is an interface so JVM tests use a software AES key.
- **One credential per origin:** `put` replaces an existing row with the same origin (all podcasts on that origin share it), because `AuthInterceptor` looks up by origin only. Two accounts on one host are not supported in v1 ([Open questions](#open-questions)).
- **Input:** `https://user:pass@host/feed` in typed input, intents or OPML → `UrlNormalizer.splitUserInfo` (percent-decoded), stored via `put`, `podcast.credentialId` set, `feedUrl` without userinfo. A 401/403 with a `Basic` challenge in the add flow → `AddPodcastError.AuthRequired(realm)`; the sheet asks for user name and password and calls `resolve(input, credentials)`, which fetches with `FeedRequest.credentials` set; credentials are held only in the [preview cache](#preview-and-dedupe) until subscribe and are never written for a preview that is not subscribed.
- **On refresh:** `HTTP_AUTH` → `needsCredentials = 1`; the podcast shows "Enter password"; `PodcastRepository.setCredentials(podcastId, credentials)` first probes `feedUrl` with `FeedRequest(credentials = …, conditional = false)`: 401/403 → `AuthRequired` (nothing stored); any other outcome → `put`, set `podcast.credentialId`, clear `needsCredentials` (after flushing `FetchStateBatcher`), then `refreshNow(Podcasts([id]))`.
- **Key loss** (decrypt fails, e.g. Keystore reset): the row is deleted and every podcast referencing it gets `needsCredentials = 1`. Restored or imported podcasts never have credentials on a new device ([05 Full backup and restore](05-groups-opml-backup.md#full-backup-and-restore)).
- Start-up: an `AppInitializer` (order 120, after the database at 100) calls `awaitLoaded()`; `FeedFetcher` also awaits it.

### Private feed URLs

Token-in-URL feeds (Patreon, Supercast, Memberful and similar) are secrets ([N3](../PLAN.md#22-non-functional-requirements), [R1.9](../PLAN.md#21-functional-requirements)):

- Feed URLs are never sent to any directory or third party (no Podcast Index `byfeedurl` call exists in v1); logs and crash reports pass through 01's `Redactor` ([01 Logging and redaction](01-foundation.md#logging-and-redaction)).
- `PrivateFeedUrls.looksPrivate(url): Boolean` (`:feeds`, used by 05's export warning and by the "Private feed" chip): true if the URL has userinfo; or a query parameter name (case-insensitive) in {`token`, `auth`, `key`, `apikey`, `api_key`, `secret`, `sig`, `signature`, `access_token`, `session`, `sid`, `uid`, `user`, `pass`, `password`, `hash`, `subscriber`, `member`, `premium`}; or any path segment or query value matching `[A-Za-z0-9_-]{20,}` containing both a letter and a digit; or the host ends with `patreon.com`, `supercast.tech`, `memberful.com`, `memberfulcontent.com` (heuristic list, extended from bug reports). False positives only cost a warning.

### RFC 5005 paging

- Page 1 is fetched on every refresh. `next` (paged feeds) and `prev-archive` (archived feeds) both point to older documents; `fh:complete` means the document is the complete feed and normal (non-partial) absence rules apply ([RFC 5005](https://www.rfc-editor.org/rfc/rfc5005)).
- Paging state lives in `podcast.pagingNextUrl` and `podcast.pagingComplete`:

| State | Meaning | Set by |
|---|---|---|
| `pagingNextUrl = null, pagingComplete = 0` | unknown (never ingested) | insert default (imports, restores, YouTube) |
| `pagingNextUrl = X, pagingComplete = 0` | background paging pending | subscribe from a preview with `feeds.backfill_paged_feeds` on; "Load older episodes" |
| `pagingNextUrl = X, pagingComplete = 1` | older pages exist, not wanted automatically ("Load older episodes" shown) | caps reached; first ingest of a podcast in the "unknown" state; subscribe with the setting off |
| `pagingNextUrl = null, pagingComplete = 1` | nothing older | last page reached; first ingest without an older-page link |

- `applyFeedMetadata` writes paging columns only in the "unknown" state (→ row 3 or 4 from page 1's `next ?: prevArchive`); later page-1 refreshes never rewind or restart a session. YouTube rows are never paged (04's back catalogue is separate).
- **Paging session** (engine, mode `OLDER_PAGE`, unconditional `FeedRequest`): fetch `pagingNextUrl`, ingest with `OLDER_PAGE` (no `isNew`, no absence flips, no metadata changes except the paging columns), set `pagingNextUrl` to the page's own `next ?: prevArchive` in the same transaction (so a deadline stop resumes at the right page), repeat. Stop with `pagingComplete = 1` when: no further link (`pagingNextUrl = null`); the URL was already visited in this run (loop); a page yields no new keys (`IngestResult.inserted` empty and nothing matched by pass 2); the run's page budget is used (50 pages per podcast per run, counted in memory); or — for sessions not started by "Load older episodes" in this run — the podcast holds ≥ 5,000 episodes. The budget and item-cap stops keep `pagingNextUrl`, so "Load older episodes" stays available; a fetch or parse failure leaves the state unchanged and the session retries in a later run.
- A failed page never touches `failureCount` or the podcast's error state (page 1 owns them).
- Automatic sessions exist only for podcasts subscribed from a preview; imports and restores never backfill automatically (300 feeds × 50 pages would be gigabytes).

### podcast:guid

`PodcastGuid.parse(raw)` validates and lowercases a feed-supplied GUID. `PodcastGuid.derive(feedUrl)` computes UUIDv5 with namespace `ead4c236-bf58-58c6-a2c6-a6b28d128cb6` over the URL with the scheme and trailing slashes removed (verified against both spec examples, [podcast:guid](https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/guid.md)). Derived values are a local key only (`podcastGuidDerived = 1`), never exported as real, never used for dedupe.

---

## Refresh scheduling

Serves N2, R2.6, R2.7, R3.3, R1.3. Delivered in M1 (engine, periodic and manual work), M2 (group scope, effective intervals), M3 (import interplay), M8 (YouTube adapter). Honours [D25](../PLAN.md#3-key-decisions), [D45](../PLAN.md#3-key-decisions); mitigates risks T3 and T8.

### API

```kotlin
// :core:domain (canonical members first; refreshNow is always forced and user-initiated)
interface RefreshController {
    fun refreshNow(scope: RefreshScope)
    suspend fun reschedulePeriodic()
    fun observeStatus(): Flow<RefreshStatus>
    fun refreshFeed(source: FeedSource)          // pull-to-refresh: All → All, Group → Group, Podcast → Podcasts([id]),
                                                 // Ungrouped → Podcasts(ids of podcasts in no group); 20 s cooldown per scope
    fun loadOlderEpisodes(podcastId: Long)       // pagingComplete = 0 (needs pagingNextUrl), enqueues a pages-only run
}
sealed interface RefreshScope {
    data object All : RefreshScope
    data class Group(val groupId: Long) : RefreshScope
    data class Podcasts(val ids: List<Long>) : RefreshScope
}
data class RefreshStatus(val running: Boolean, val scope: RefreshScope?, val done: Int, val total: Int,
                         val lastRunFinishedAt: Long?)
```

```kotlin
// :core:data (internal); RefreshControllerImpl delegates to RefreshScheduler and FeedRefresher.status
internal class RefreshScheduler @Inject constructor(/* WorkManager, EffectiveSettingsResolver, SettingsRepository, PodcastDao, Clock */) {
    fun enqueueNow(scope: RefreshScope, force: Boolean, pagesOnly: Boolean, origin: RefreshOrigin)  // refresh-now
    suspend fun reschedulePeriodic()             // tick + constraints + nextRefreshAt rebase (Periodic tick)
    fun enqueueContinuation()                    // refresh-continuation, KEEP
}
internal class FeedRefresher @Inject constructor(/* adapters, FeedIngestor, PodcastDao, NewEpisodeNotifier, Clock, … */) {
    val status: StateFlow<RefreshStatus>
    val events: SharedFlow<FeedRunEvent>                 // per-feed outcomes; ImportFetchWorker (05) collects
    suspend fun run(request: RefreshRequest): RefreshReport
}
data class RefreshRequest(val scope: RefreshScope, val force: Boolean, val pagesOnly: Boolean,
                          val origin: RefreshOrigin, val deadlineElapsedMs: Long)
enum class RefreshOrigin { PERIODIC, MANUAL, FOREGROUND, SUBSCRIBE, CONTINUATION, IMPORT, RESTORE }
data class RefreshReport(val outcomes: Map<Long, FeedOutcome>, val newEpisodes: List<NewEpisodes>,
                         val remaining: Int, val stoppedByDeadline: Boolean)
sealed interface FeedOutcome {
    data class Ingested(val inserted: Int, val newCount: Int, val firstIngest: Boolean, val sameGuidAs: Long?) : FeedOutcome
    data object NotModified : FeedOutcome
    data object Unchanged : FeedOutcome
    data class Merged(val intoPodcastId: Long) : FeedOutcome
    data class Failed(val kind: FeedErrorKind, val httpStatus: Int?) : FeedOutcome
    data class Deferred(val untilMs: Long) : FeedOutcome          // 04: not attempted (YouTube outage or rate limit);
                                                                  // 05's ImportFetchWorker records FETCH_FAILED(DEFERRED)
}
data class FeedRunEvent(val podcastId: Long, val origin: RefreshOrigin, val outcome: FeedOutcome)
```

`ImportFetchWorker` (05) calls `run(RefreshRequest(Podcasts(pending), force = true, pagesOnly = false, origin = IMPORT, deadlineElapsedMs = …))` from its own worker (no `refresh-*` work involved) and derives each `import_item` status from the outcomes, the `FeedRunEvent`s it saw and the persisted podcast row ([05 OPML import](05-groups-opml-backup.md#opml-import)); restore sessions run through the same worker (05 may pass origin `RESTORE`). Origins `IMPORT` and `RESTORE` change nothing in the engine except diagnostics and the autodiscovery alias reason: imported and restored rows are `PENDING_FIRST_FETCH`, so ingestion is `INITIAL` anyway.

### Periodic tick

- **Tick interval** = max(60 min, min over subscribed podcasts of the effective refresh interval), where the effective interval follows [D45](../PLAN.md#3-key-decisions) (podcast override → minimum over its groups' overrides → global `feeds.refresh_interval_minutes`) and comes from `EffectiveSettingsResolver.refreshIntervals()` (`null` = manual only, [05 Effective settings resolution](05-groups-opml-backup.md#effective-settings-resolution)). Overrides are 0 ("Manual only", offered at podcast and group scope as well, [PO-21](../PLAN.md#48-further-product-owner-decisions)) or one of 60, 120, 240, 480, 720, 1440 min; a 0 resolves to `null` by 05's rule (podcast 0 → `null`; a group 0 counts as +∞ in the minimum over groups; global 0 → `null`); if every podcast resolves to `null` (or there are no podcasts), `refresh-periodic` is cancelled. In M1 (before 05's resolver) the tick is the global value.
- `RefreshScheduler.reschedulePeriodic()`:
  1. computes the tick and the constraint set (`CONNECTED`, or `UNMETERED` when `feeds.refresh_wifi_only`; `setRequiresBatteryNotLow(true)`), compares them with `feeds.scheduled_tick_minutes`/`feeds.scheduled_tick_unmetered` (`device_settings`), and only when they differ enqueues `refresh-periodic` with `ExistingPeriodicWorkPolicy.UPDATE`, flex = interval / 3 (≥ WorkManager's 5-min flex minimum for every allowed tick), then stores the new values;
  2. **rebases `nextRefreshAt`** so interval changes take effect without waiting for the old schedule: for every podcast with `failureCount = 0`, `gone = 0`, `needsCredentials = 0`, `target = if (I == null) NEVER else COALESCE(lastSuccessAt, subscribedAt) + I`; write `nextRefreshAt = target` when `I == null`, or when the stored value is `NEVER`, `NULL` or later than `target` (interval shortened or manual-only lifted; a past `target` makes the feed due at the next run). Writes go through 02's batched fetch-state update (a few hundred rows; one transaction). Failing feeds keep their backoff; forced rows (`nextRefreshAt = 0`) stay forced.

  Called by the start-up initializer (order 200, [01 Application start-up](01-foundation.md#application-start-up)), after changes to the global interval or Wi-Fi setting, after any podcast or group refresh-interval change (05 calls it), and after subscribe, unsubscribe, merge and membership changes (05 calls it for memberships).
- WorkManager's periodic minimum is 15 min and periodic work drifts by up to the flex window; the UI says "about every N hours", never a clock time ([define work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)).

### Work requests

| Unique name | Request | Constraints | Policy | Input |
|---|---|---|---|---|
| `refresh-periodic` | periodic, tick interval, flex interval / 3 | `CONNECTED` or `UNMETERED`; battery not low | `UPDATE` | origin `PERIODIC`, scope All |
| `refresh-now` | one-time; **API ≥ 31:** `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`; **API 26–30:** not expedited | `CONNECTED` only (expedited jobs accept only network and storage constraints) | `APPEND_OR_REPLACE` | scope, `force`, `pagesOnly`, origin |
| `refresh-continuation` | one-time, initial delay 1 min, `setBackoffCriteria(LINEAR, 60 s)` | same as `refresh-periodic` | `KEEP` | origin `CONTINUATION` (scope All, not forced: pure due selection) |

All three run `RefreshWorker` (`@HiltWorker`, tag `refresh`). `refresh-now` ignores `feeds.refresh_wifi_only` and the battery constraint because the user asked for it; automatic triggers honour both. Below Android 12, WorkManager runs expedited work as a foreground service and requires `getForegroundInfo()`, and expedited requests reject every constraint except network and storage and any initial delay (`WorkRequest.Builder.build()` throws, [define work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [WorkRequest source](https://github.com/androidx/androidx/blob/androidx-main/work/work-runtime/src/main/java/androidx/work/WorkRequest.kt)); refresh deliberately avoids the FGS path (no notification channel, no `dataSync` use for refresh), accepting slightly later starts on API 26–30, where non-expedited one-time work still starts promptly while the app is in the foreground. Scope serialisation in `Data`: `"all"`, `"group:<id>"`, or a `LongArray` of ≤ 500 ids (`Data` is capped at 10 KB, `Data.MAX_DATA_BYTES`; larger sets are marked due with 02's `forceDue` first and sent as `"all"` with `force = false`).

**Continuations and `KEEP`.** `KEEP` ignores a new request while unique work of that name is *uncompleted*, which includes `RUNNING` ([ExistingWorkPolicy source](https://github.com/androidx/androidx/blob/androidx-main/work/work-runtime/src/main/java/androidx/work/ExistingWorkPolicy.kt)). A running continuation therefore cannot enqueue its successor; it returns `Result.retry()` instead (linear backoff 60 s × attempt), at most 10 times, after which the periodic tick takes over. Other origins enqueue the continuation; `KEEP` then collapses concurrent requests into one.

```kotlin
@HiltWorker
internal class RefreshWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
    private val refresher: FeedRefresher, private val scheduler: RefreshScheduler, private val clock: Clock,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val request = RefreshRequest.from(inputData, deadlineElapsedMs = clock.elapsedRealtime() + 8 * 60_000L)
        val report = refresher.run(request)
        val more = report.remaining > 0 || report.stoppedByDeadline
        return when {
            !more -> Result.success()          // per-feed failures live on the podcast rows, not in WorkManager retries
            request.origin != RefreshOrigin.CONTINUATION -> { scheduler.enqueueContinuation(); Result.success() }
            runAttemptCount < 10 -> Result.retry()
            else -> Result.success()
        }
    }
}
```

### Engine run

```mermaid
sequenceDiagram
  participant WM as WorkManager
  participant W as RefreshWorker
  participant R as FeedRefresher
  participant N as NewEpisodeNotifier
  WM->>W: doWork (periodic, now or continuation)
  W->>R: run(request, deadline = start + 8 min)
  R->>R: acquire mutex (wait at most until deadline)
  R->>R: sweep temp files, mark scope due if forced
  R->>R: select due feeds, fan out 6 global and 2 per host
  R->>R: background paging while more than 2 min remain
  R->>N: post(new episodes of this run) in finally
  R-->>W: RefreshReport
  opt stopped by deadline or work remaining
    W->>WM: enqueue refresh-continuation, or Result.retry() when W is the continuation
  end
```

1. **Mutex.** One engine run per process (`Mutex`); a second caller waits with `withTimeoutOrNull(deadline − now − 30 s)` and, on timeout, returns `remaining = scope size, stoppedByDeadline = true`, so a `RefreshWorker` enqueues a continuation and 05's `ImportFetchWorker` returns `Result.retry()`.
2. `FeedTempFiles.sweep()`.
3. **Force.** If `force`, 02's `forceDue(scope)` (`nextRefreshAt = 0` for the scope's rows with `gone = 0 AND needsCredentials = 0`). Forced work is thereby persisted: a continuation needs no ids, and `KEEP` cannot lose them.
4. **Selection.** 02's `dueForRefresh(dueBefore = now + slack, scope)` with `slack = tick / 4` (a feed due shortly after this tick is refreshed now, not one tick later); rows with `sourceType = YOUTUBE_PLAYLIST` are skipped. Order (02): pending first fetches, then `COALESCE(lastSuccessAt, 0)` ascending. `pagesOnly` runs use 02's `pagingPending(scope)` instead.
5. **Fan-out.** For each feed: stop launching when `elapsed ≥ deadline − 30 s`; acquire the global `Semaphore(6)`, then the per-host `Semaphore(2)` keyed by `adapter.hostKey(feed)` (lowercase request host; the limit of 2 applies to `www.youtube.com` too); launch `refreshOne`. OkHttp's dispatcher (64 / 8 per host, 01) is never the bottleneck.
6. **`refreshOne`** = adapter `fetchAndParse` → `FeedIngestor.ingest` (then `afterIngest` and the `NewEpisodes` emission, [Diff algorithm](#diff-algorithm) step 11) or a fetch-state outcome → `events.emit` → `status` update (`done`/`total`). `AdapterResult.Deferred(untilMs)` writes only `nextRefreshAt = untilMs` (no `lastAttemptAt`, `failureCount` or `lastErrorKind` change) and does not count as remaining work.
7. **Background paging.** If ≥ 2 min remain and the run is not `pagesOnly`, run `pagingPending(scope)` sessions round-robin, one page per podcast per round, under the same semaphores.
8. **Fetch-state flush.** Outcomes without a body (304, unchanged, failures, deferrals) go to 02's `FetchStateBatcher` (`updateFetchStates`, ≤ 20 rows or every 5 s; flushed under `NonCancellable` at the end or deadline), so a 300-feed refresh invalidates open lists a few times, not 300 ([02 Refresh selection](02-data-model.md#refresh-selection-and-fetch-state-writes)).
9. **Deadline.** At `deadline` all in-flight feeds are cancelled; each feed is one transaction, so a cancelled feed leaves no partial ingest and is retried by the continuation (still due).
10. **Finish** (in a `finally` under `NonCancellable`, so a worker stop does not swallow the notifications of feeds that already committed): `NewEpisodeNotifier.post(newEpisodes committed in this run)` (M2+); diagnostics keys written; `RefreshStatus.running = false`. YouTube outage accounting happens inside 04's adapter and `YouTubeOutageMonitor`, not here ([04 Errors and global outage](04-youtube.md#errors-and-global-outage)).

Expected cost: 300 feeds at 6 parallel and 1–2 s each ≈ 1–2 min on a fresh import; a steady-state run is mostly 304s.

### nextRefreshAt policy

`I` = effective interval of the podcast (D45); `NEVER = 253_402_300_799_000` (9999-12-31) for "Manual only".

| Outcome | `nextRefreshAt` | Other columns |
|---|---|---|
| Success (ingested, 304, unchanged, `NO_MEDIA`), `I == null` (manual only) | `NEVER` | as below |
| Success (ingested, 304, unchanged, `NO_MEDIA`) | `now + I'`, where `I' = max(I, 24 h)` if `complete` or `latestEpisodeAt < now − 180 d`, else `I`; then, when a publisher hint exists, `I' = max(I', min(hint, 24 h))` with `hint` = the larger of `ttlMinutes` and `Cache-Control: max-age` | `lastSuccessAt = lastAttemptAt = now`, `failureCount = 0`, `lastErrorKind = null` except `NO_MEDIA` |
| Failure | `now + min(30 min · 2^(n − 1), 24 h) · random(0.8–1.2)` with `n` = the incremented `failureCount`, or `now + Retry-After` (≤ 7 d) if later | `failureCount = n`, `lastAttemptAt`, `lastErrorKind`, `lastErrorDetail` |
| `Deferred(untilMs)` (04) | `untilMs` | none |
| 410 | unchanged; excluded by `gone = 1` | `gone = 1` |
| 401/403 Basic | unchanged; excluded by `needsCredentials = 1` | `needsCredentials = 1` |
| `OFFLINE`, cancelled | unchanged | none |
| `LOCAL_NETWORK_UNSUPPORTED` | `now + 24 h` | kind recorded |
| Forced | ignored for selection (step 3); still a conditional GET | — |

The adapter's `nextRefreshAt(feed, result, base)` may only raise `base`; YouTube channels use it for their 15-minute floor (`max-age=900`) and gap pull-in ([04 Contract with 03's engine](04-youtube.md#contract-with-03s-engine)).

### Per-feed states

```mermaid
stateDiagram-v2
  [*] --> Pending: import or restore commit
  [*] --> Healthy: subscribe from preview
  Pending --> Healthy: first ingest or empty feed
  Pending --> Pending: failure or no media, backoff
  Healthy --> Failing: fetch or parse failure
  Failing --> Healthy: success
  Failing --> PossiblyDead: 10 failures and no success for 7 days
  PossiblyDead --> Healthy: success
  Healthy --> NeedsCredentials: 401 or 403 with Basic challenge
  Failing --> NeedsCredentials: 401 or 403 with Basic challenge
  Pending --> NeedsCredentials: 401 or 403 with Basic challenge
  NeedsCredentials --> Healthy: password entered and refresh succeeds
  Healthy --> Gone: 410
  Failing --> Gone: 410
  Gone --> Healthy: Try again succeeds
```

`Pending` = `status = PENDING_FIRST_FETCH`. `Failing` = `failureCount > 0`. **`PossiblyDead`** (derived, no column; same rule for every failure kind and for YouTube channels, which 04 words differently) = `gone = 0 AND failureCount ≥ 10 AND COALESCE(lastSuccessAt, subscribedAt) < now − 7 d`; it is computed in `:core:data` mappers (`FeedHealth.possiblyDead`) from the row and `Clock`, and nothing automatic ever unsubscribes such a podcast. 04's `Deferred` outcomes never increment `failureCount`, so a YouTube-wide outage cannot make channels "possibly dead" ([R3.3](../PLAN.md#21-functional-requirements)). Badges are 08's ([08 Components](08-ui-ux.md#components)). "Try again" (`PodcastRepository.retry`) flushes `FetchStateBatcher`, clears `gone`/`needsCredentials`, and forces a refresh unless called with `refresh = false` (05's import report, which re-runs its own worker).

### Refresh of pending podcasts

For `status = PENDING_FIRST_FETCH` (imports, restores, YouTube subscribes) the engine differs from a normal refresh in exactly three ways:

1. ingestion uses `INITIAL`;
2. for RSS rows, an HTML body (`AdapterResult.Failed(NOT_A_FEED, htmlBody = file)`) runs the offline part of [autodiscovery](#fetch-sniff-and-autodiscovery) — `<link rel=alternate>` and same-site feed-looking anchors only; no Apple lookup (the shared 20/min bucket) and no path probes — and, if it yields a top-ranked candidate that does not classify as YouTube, fetches that one URL, and on a parse with ≥ 1 accepted item adopts it as a move (the page URL becomes an alias with reason `IMPORT`, or `RESTORE` for origin `RESTORE`); otherwise the outcome stays `NOT_A_FEED`. This runs on every attempt while pending (at most one extra request per attempt);
3. paging columns go from "unknown" to "older pages exist, not wanted" ([RFC 5005 paging](#rfc-5005-paging)).

`Autodiscovery.candidates` (the `:feeds` part) therefore lands in M3 for this rule; the add flow uses it from M7.

### Source adapters

The engine is source-agnostic; per-source behaviour sits behind one internal interface in `:core:data` (YouTube Atom is fetched and parsed here, never in a YouTube module — [01 Dependency rules](01-foundation.md#dependency-rules)).

```kotlin
// :core:data (internal)
internal interface SourceAdapter {
    val sourceType: SourceType
    fun hostKey(feed: DueFeed): String
    suspend fun fetchAndParse(feed: DueFeed, mode: FetchMode): AdapterResult
    fun nextRefreshAt(feed: DueFeed, result: AdapterResult, base: Long): Long   // may only raise base (policy table)
    /** Runs after the ingest commit (also after Unchanged, with empty lists) and before the emit; returns the IDs to announce. */
    suspend fun afterIngest(podcastId: Long, inserted: List<Long>, newIds: List<Long>): List<Long> = newIds
}
enum class FetchMode { REFRESH, FULL, OLDER_PAGE }   // FULL = unconditional (parser bump, fortnightly, previews, probes)
internal sealed interface AdapterResult {
    data class Parsed(val feed: ParsedFeed, val partial: Boolean, val meta: FetchMeta,
                      val rowHints: Map<String, RowHint> = emptyMap(),
                      val absenceFloor: Long? = null) : AdapterResult        // 04: overrides the partial-window floor
    data class NotModified(val meta: FetchMeta?) : AdapterResult
    data class Unchanged(val meta: FetchMeta) : AdapterResult
    data class Failed(val kind: FeedErrorKind, val http: Int?, val retryAfterMs: Long?, val transient: Boolean,
                      val htmlBody: File? = null) : AdapterResult   // NOT_A_FEED with HTML: kept for pending autodiscovery
    data class Deferred(val untilMs: Long) : AdapterResult           // 04: not attempted; only nextRefreshAt changes
}
```

`transient = true` means: failure backoff and `failureCount + 1`, but never `gone` (a 404/410 from YouTube). `RssSourceAdapter` chooses `FetchMode.FULL` itself from the [Validators](#validators) rules and `REFRESH` otherwise.

| Adapter | Owner | Behaviour |
|---|---|---|
| `RssSourceAdapter` (`RSS`) | 03 | Everything in this document |
| `YouTubeSourceAdapter` (`YOUTUBE_CHANNEL`, M8; lives in `:core:data`, specified by 04) | [04 Contract with 03's engine](04-youtube.md#contract-with-03s-engine) | Polls the variant URLs of `youtubeVariants` (unconditional; Atom has no validators), parses each with `FeedParser`, merges entries by `externalMediaId`, returns `Unchanged` when its merged digest equals `contentSha256`, supplies `RowHint`s and `absenceFloor`, always `partial = true`; 404/5xx are `transient`; `Deferred` during a YouTube-wide outage (it reports attempts and failures to `YouTubeOutageMonitor`, whose threshold, notice and backoff are 04's); `nextRefreshAt` ≥ last attempt + 15 min; `afterIngest` runs channel-art refresh and, from M9a and only while `YouTubeCapabilities.enrichment` is true (the YouTube engine is in the APK and on, [04 Capability matrix](04-youtube.md#capability-matrix)), enrichment through `YouTubeEnricher` (it may start the `:ytx` process; 04's `YtDlpClient` deadlines bound each call, and a deadline cancellation still emits `newIds`, [Diff algorithm](#diff-algorithm) step 11), and returns the IDs to announce; in external mode it returns `newIds` unchanged, so premieres are not held back (R3.7) |
| `YOUTUBE_PLAYLIST` | — | Reserved, never created ([D53](../PLAN.md#3-key-decisions)); the engine skips such rows |

Before M8 no adapter is bound for `YOUTUBE_CHANNEL` and no such rows exist (05 reports YouTube import items as `YOUTUBE_UNSUPPORTED_YET`, the add flow returns `YouTubeNotYetSupported`); the engine skips a row whose source type has no adapter. Adapters are a Hilt map multibinding (`@IntoMap` with a `@MapKey` annotation `SourceTypeKey`).

### Triggers

| Trigger | Call | Notes |
|---|---|---|
| Periodic tick | `refresh-periodic` | due feeds only |
| Pull-to-refresh in a feed (08) | `refreshFeed(source)` → `refreshNow(scope)` (forced, origin `MANUAL`) | same scope ignored within 20 s (in-memory cooldown, as AntennaPod) |
| Group action "Refresh" ([R2.6](../PLAN.md#21-functional-requirements)) | `refreshNow(Group(id))` | M2 |
| App comes to foreground | `RefreshForegroundObserver` → `RefreshScheduler.enqueueNow(All, force = false, pagesOnly = false, FOREGROUND)` | only if `feeds.refresh_on_app_open`, `feeds.last_all_run_finished_at` is older than the tick interval, the last foreground trigger was ≥ 10 min ago, and — when `feeds.refresh_wifi_only` — `NetworkMonitor.status.isMetered == false`. Registered by an `AppInitializer` (order 220) that adds the observer to `ProcessLifecycleOwner` on the main thread; a late registration still receives the current `ON_START` |
| Subscribe with older pages | `enqueueNow(Podcasts([id]), force = false, pagesOnly = true, SUBSCRIBE)` | after `SubscribeUseCase` commits |
| "Load older episodes" | `loadOlderEpisodes(id)` → `enqueueNow(Podcasts([id]), force = false, pagesOnly = true, MANUAL)` | manual session: 50 pages per run, no item cap |
| Credentials entered, "Try again" | `refreshNow(Podcasts([id]))` | "Edit URL" ingests its own fetch and needs no refresh |
| Subscribe to a YouTube channel (04) | `refreshNow(Podcasts([id]))` | first fetch of a pending row |
| Import / restore | `ImportFetchWorker` / 05 | through `FeedRefresher.run`, no `refresh-*` work |

### Threading model

| Work | Dispatcher / context | Bound |
|---|---|---|
| Worker body, orchestration | `CoroutineWorker` (`Dispatchers.Default`) | one run per process (mutex) |
| HTTP | `okhttp-coroutines` `executeAsync()` (cancellable); body copy and hashing in `withContext(@Dispatcher(IO))` | 6 global, 2 per host |
| Parse from temp file | `@Dispatcher(IO).limitedParallelism(2)` (blocking reads, CPU-heavy; 01 rule) | 2 |
| Diff preparation, codec, hashing | `@Dispatcher(Default)` | — |
| Ingest transaction | Room query context (`@Dispatcher(IO)`, one writer) | serialised by SQLite |
| Events, status | `IngestionEventBus`, `StateFlow` (thread-safe) | — |
| Notification posting | `@Dispatcher(Default)` | once per run |

Every suspend call site uses `suspendRunCatching` (never `runCatching`), so worker stops propagate `CancellationException` and roll back the open transaction ([01 Errors](01-foundation.md#errors)).

### Platform constraints

| Constraint | Consequence | Source |
|---|---|---|
| Periodic work ≥ 15 min; flex; exponential backoff ≥ 10 s | Tick floor 60 min; "about every N hours" wording | [define work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work) |
| Workers get ~10 min unless foreground | 8-min soft deadline + `refresh-continuation` | [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) |
| Expedited work has a quota (`OutOfQuotaPolicy`); before API 31 it runs as an FGS needing `getForegroundInfo()`; expedited jobs accept only network/storage constraints | `refresh-now` expedited only on API ≥ 31, network constraint only | [define work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [JobInfo.Builder.setExpedited](https://developer.android.com/reference/android/app/job/JobInfo.Builder#setExpedited(boolean)) |
| Android 16: runtime quota also for jobs started while visible that continue, and for jobs running beside an FGS (playback) | Resumable per feed; deadline; stop reason logged | [Android 16 behaviour changes](https://developer.android.com/about/versions/16/behavior-changes-all), [power details](https://developer.android.com/topic/performance/power/power-details) |
| Frequent timeouts can demote the app to the restricted bucket | Never run into the hard limit; diagnostics show the bucket (09) | [optimise battery](https://developer.android.com/develop/background-work/background-tasks/optimize-battery) |
| WorkManager 2.11.x fixed network-constraint bugs on Android 15+; 2.12.0 required | Catalog pins 2.12.0 (01) | [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work) |
| `ExistingWorkPolicy.KEEP` treats `RUNNING` work as existing | A running continuation retries instead of re-enqueueing ([Work requests](#work-requests)) | [ExistingWorkPolicy source](https://github.com/androidx/androidx/blob/androidx-main/work/work-runtime/src/main/java/androidx/work/ExistingWorkPolicy.kt) |
| No exact alarms, no battery-exemption request | Refresh is best effort ([N2](../PLAN.md#22-non-functional-requirements)) | [doze and standby](https://developer.android.com/training/monitoring-device-state/doze-standby) |
| Android 17 LAN permission, CT | [Error kinds](#error-kinds) | above |

### Failure modes

| Failure | Behaviour |
|---|---|
| Worker stopped (quota, constraint loss, process death, or an app update that the user or Obtainium installs — the app never installs one itself, [D78](../PLAN.md#3-key-decisions), [09 Update check](09-quality-and-release.md#update-check); the install ends the process like any update ([`REASON_PACKAGE_UPDATED`](https://developer.android.com/reference/android/app/ApplicationExitInfo)), so the refresh resumes as after a process death) | In-flight feeds roll back; WorkManager reschedules the stopped work; forced work persists as `nextRefreshAt = 0` |
| Disk full (`STORAGE`) | Run ends early; feeds untouched; next tick retries |
| Database write error in one feed | That feed records `IDENTITY_CONFLICT`/`UNKNOWN`; others continue |
| Clock jumps | Scheduling uses `Clock.now()` for stored times and `elapsedRealtime()` for deadlines; `firstSeenAt` corrected by the server date |
| Many feeds on one host 429 | Per-host semaphore 2 + `Retry-After` per feed |

### Diagnostics

`device_settings` keys written at the end of every run (shown by 09's diagnostics screen, M11): `feeds.last_run_finished_at`, `feeds.last_all_run_finished_at` (only runs with scope All that were not stopped by the deadline; gates the foreground trigger), `feeds.last_run_summary` (JSON: origin, attempted, ingested, not modified, failed by kind, stopped by deadline), `feeds.last_run_stop_reason` (`WorkInfo`/`ListenableWorker.stopReason`, −1 when none). Per-feed parse warnings of the last ingest are kept in an in-memory LRU (50 podcasts) for the debug screen.

---

## Show notes

Serves R4.8 (timestamps), N3, N6. Delivered in M1 (storage, sanitiser, block model), M5 (seek links wired by 06). Honours [D27](../PLAN.md#3-key-decisions).

### At ingest

- Raw description (HTML, or plain text for Atom/YouTube) goes to `episode_description` through 02's `EpisodeDescriptionCodec`; nothing is sanitised at ingest ([02 episode_description](02-data-model.md#episode_description)).
- `snippet` = `PlainTextSnippet.of(text, isHtml)`: jsoup `text()` (or the plain text), whitespace collapsed, cut at the last word boundary ≤ 199 chars plus `…`; used by list rows (02 keeps it out of descriptions).

### Sanitiser and block model

`ShowNotesSanitizer.toDocument(raw, isHtml, baseUri): ShowNotesDocument` runs at display time on `@Dispatcher(Default)`; `EpisodeRepository.observeShowNotes` caches the last 16 documents in memory.

1. **Normalise input:** no tags but escaped markup (`&lt;p&gt;`, `&lt;br`) → unescape once (double-escaped feeds); no tags at all (YouTube, many Atom feeds) → plain text: blank lines split paragraphs, `\n` → line break, URLs linkified.
2. **Clean:** `Cleaner(safelist).clean(Jsoup.parseBodyFragment(html, baseUri))` and walk the returned `Document` directly (no re-serialisation and re-parse). `safelist` = `Safelist.basicWithImages()` + `addTags("h1".."h6", "div", "hr", "figure", "figcaption")`; `img` attributes `alt`, `width`, `height`, `src`; `a` protocols `http`, `https`, `mailto` (`removeProtocols("a", "href", "ftp")`); `img` protocols `http`, `https`; `removeEnforcedAttribute("a", "rel")`. Relative `href`/`src` are made absolute against `baseUri` (jsoup's default `preserveRelativeLinks(false)`; unresolvable ones are dropped) ([jsoup Safelist](https://jsoup.org/apidocs/org/jsoup/safety/Safelist.html)). `baseUri` = episode `link`, else the feed URL. `script`, `style`, `iframe`, event attributes and `javascript:`/`data:` URLs never survive the safelist.
3. **Drop:** images with `width` or `height` ≤ 2 and images whose URL matches `(?i)(/pixel|/track|/beacon|1x1|spacer)[^/]*\.(gif|png)`; empty paragraphs.
4. **Walk** the cleaned DOM into blocks; caps: 2,000 blocks, list nesting 4 (deeper flattened), 50 images.

```kotlin
// :feeds — ch.lkmc.neutrodyne.feeds.html. Mirrored 1:1 into :core:model as ShowNotes / ShowNoteBlock / ShowNoteSpan
// by :core:data (01 open question 1, option b); 08 renders the :core:model mirror.
data class ShowNotesDocument(val blocks: List<NoteBlock>)
sealed interface NoteBlock {
    data class Paragraph(val spans: List<NoteSpan>) : NoteBlock
    data class Heading(val level: Int, val spans: List<NoteSpan>) : NoteBlock
    data class ListBlock(val ordered: Boolean, val items: List<List<NoteBlock>>) : NoteBlock
    data class Quote(val blocks: List<NoteBlock>) : NoteBlock
    data class Image(val url: String, val alt: String?, val width: Int?, val height: Int?) : NoteBlock
    data object Rule : NoteBlock
}
sealed interface NoteSpan {
    data class Text(val text: String, val style: Int) : NoteSpan          // bits: BOLD 1, ITALIC 2, UNDERLINE 4, CODE 8
    data class Link(val text: String, val url: String, val style: Int) : NoteSpan
    data class Timestamp(val text: String, val positionMs: Long) : NoteSpan
    data object LineBreak : NoteSpan
}
```

Mapping of every tag the safelist lets through — blocks: `p` → `Paragraph`; `h1`–`h6` → `Heading(level)`; `ul`/`ol` + `li` → `ListBlock`; `blockquote` → `Quote`; `img` → `Image` (after step 3); `hr` → `Rule`; `pre` → `Paragraph` whose text keeps its line breaks (`LineBreak` spans) with style CODE; `dl` → one `Paragraph` per `dt` (BOLD) and `dd`; `div`/`figure` contents flatten into paragraphs; `figcaption` → italic `Paragraph`; bare text between blocks → `Paragraph`. Inline: `b`/`strong` BOLD, `i`/`em`/`cite` ITALIC, `u` UNDERLINE, `code` CODE, `a[href]` → `Link` (an `a` without a surviving `href` becomes text), `br` → `LineBreak`; `span`, `q`, `small`, `strike`, `sub`, `sup` contribute their text only. Adjacent `Text` spans with equal style are merged; whitespace is collapsed as in HTML except inside `pre`.

### Timestamp linkifier

`TimestampLinkifier` turns text outside links into `Timestamp` spans:

```
(?<![\d:.])(?:(\d{1,2}):([0-5]\d):([0-5]\d)|(\d{1,3}):([0-5]\d))(?![\d:])(?!\s?(?i:am|pm|a\.m\.|p\.m\.|uhr|h\b))
```

Groups 1–3 are `H:MM:SS`, groups 4–5 `M:SS` with minutes up to 999 (show notes often write "75:12" for long episodes); `positionMs = (h · 3600 + m · 60 + s) · 1000`. Seconds must be two digits, so ratios such as "16:9" never match. The renderer (08) shows spans beyond a known episode duration as plain text; a tap seeks when the episode is current and otherwise calls `PlaybackController.playEpisodeAt(episodeId, positionMs)` ([06 Chapters](06-playback.md#chapters)). Times of day ("10:30 am", "20:15 Uhr", "10:30h") are excluded by the look-ahead; remaining false positives (a bare "14:30") are accepted because they only seek.

### Images and links

- `feeds.show_notes_images` (`ALWAYS`, `WIFI_ONLY`, `TAP_TO_LOAD`; default `TAP_TO_LOAD`): remote images reveal the listener's IP address to arbitrary hosts, so they are not fetched until the user taps "Load images" in that episode (or changes the setting) — consistent with [N3](../PLAN.md#22-non-functional-requirements). Images load through Coil's IMAGE client (no cookies).
- Links open outside the app (08 decides the mechanism); `mailto:` opens the mail app.

---

## Add podcast flow

Serves R3.1 (hand-off), R1.9, N3. Delivered in M1 (direct feed URLs, scheme normalisation, preview, subscribe, unsubscribe), M7 (host recognition, autodiscovery, chooser, intents), M8 (YouTube branch). Honours [D24](../PLAN.md#3-key-decisions), [D77](../PLAN.md#3-key-decisions).

The pipeline has no build- or capability-dependent branch: YouTube recognition and the hand-off to 04 are identical in every APK and whether the YouTube engine is present, on, off or failed. Only what happens after the hand-off depends on `YouTubeCapabilitiesSource` — 04 resolves a handle through the engine's channel lookup or through HTML autodiscovery ([04 Channel resolution](04-youtube.md#channel-resolution)), and Discover offers YouTube channel search only with the engine ([Typed names](#host-recognition) below).

```kotlin
// :core:domain
interface AddPodcastResolver {
    suspend fun resolve(input: String): AddResolution                               // canonical; a Choose pick calls resolve(candidate.url)
    suspend fun resolve(input: String, credentials: BasicCredentials): AddResolution // retry after AuthRequired
    suspend fun preview(feedUrl: String): Outcome<FeedPreview, AddPodcastError>   // PodcastPreviewKey(feedUrl)
}
sealed interface AddResolution {
    data class Feed(val preview: FeedPreview) : AddResolution
    data class Choose(val candidates: List<FeedCandidate>) : AddResolution
    data class YouTube(val ref: YtRef) : AddResolution                     // 04 continues
    data class Failure(val error: AddPodcastError) : AddResolution
}
sealed interface AddPodcastError {
    data class NotAUrl(val query: String) : AddPodcastError               // UI offers "Search for …"
    data object InvalidUrl : AddPodcastError
    data class Network(val error: NetError) : AddPodcastError
    data class Http(val code: Int) : AddPodcastError
    data class AuthRequired(val realm: String?) : AddPodcastError
    data object NotAFeed : AddPodcastError
    data object NoMedia : AddPodcastError
    data object TooLarge : AddPodcastError
    data object Malformed : AddPodcastError
    data object UnsupportedListFeed : AddPodcastError
    data object AppleOnlyShow : AddPodcastError                           // Apple lookup has no feedUrl
    data object SpotifyShow : AddPodcastError                             // no RSS exists; explanation only (helper: v1.x)
    data class SubscriptionList(val url: String) : AddPodcastError        // OPML → offer import (05)
    data object DirectoryBusy : AddPodcastError                           // Apple token bucket empty
    data object YouTubeNotYetSupported : AddPodcastError                  // builds before M8 only
}
```

```kotlin
// :core:model
data class FeedPreview(
    val previewId: String, val feedUrl: String, val title: String, val author: String?, val description: ShowNotes?,
    val artworkUrl: String?, val link: String?, val categories: List<List<String>>, val language: String?,
    val explicit: Boolean?, val episodeCount: Int, val latestEpisodeAt: Long?,
    val episodes: List<PreviewEpisode>,          // newest 200 for display
    val hasOlderPages: Boolean, val isPrivate: Boolean, val alreadySubscribed: AlreadySubscribed?,
    val emptyFeed: Boolean,
)
data class PreviewEpisode(val title: String, val pubDate: Long?, val durationMs: Long?, val snippet: String?,
                          val imageUrl: String?, val isVideo: Boolean)
data class FeedCandidate(val url: String, val title: String?, val episodeCount: Int?, val source: String)
data class AlreadySubscribed(val podcastId: Long, val exact: Boolean)   // exact: URL identity; else same real podcast:guid
data class BasicCredentials(val username: String, val password: String)
```

### Input normalisation

**YouTube pre-check (first).** `AddPodcastResolverImpl` (`:core:data`, which may depend on `:youtube:api`; `:feeds` may not, [D68](../PLAN.md#3-key-decisions)) trims the input (cap 4,096 chars) and calls `YouTubeUrlClassifier.classify` on the whole text and, if it contains whitespace, on each whitespace-separated token in order; the first non-null `YtRef` ends resolution with the YouTube branch of [Host recognition](#host-recognition). This is the only path for a bare `@handle` (R3.1: `@mkbhd`, `@some.name`) or a bare `UC…` ID, which the normaliser below would otherwise turn into `NotAUrl` or a scheme-less host. From M3 the classifier exists; in M1–M2 the pre-check is the host check of [Host recognition](#host-recognition), so a bare handle yields `NotAUrl` there.

`AddInputNormalizer.normalize(raw): NormalizedInput` (`:feeds`, pure; runs only when the pre-check returned null; returns the fetch URL, `schemeGuessed`, extracted credentials, or "not a URL"):

1. Trim; cap at 4,096 chars. If the text contains whitespace (share intents: "Listen to X https://…"), take the first token that is a URL (`https?://`, `feed:`, `pcast:`, `podcast:`, `itpc:`, `neutrodyne:`) or a bare `host.tld/…`; tokens starting with `@` are never taken as hosts.
2. `neutrodyne://subscribe?url=<enc>` → the decoded `url` (recursively, once).
3. `feed:https://x` / `feed:http://x` → `https://x` / `http://x`; `feed://x`, `pcast://x`, `podcast://x`, `itpc://x` → `https://x` with `schemeGuessed = true`.
4. Subscribe wrappers: `subscribeonandroid.com/<rest>` and `www.subscribeonandroid.com/<rest>` → `<rest>` (adding `https://` when scheme-less); `antennapod.org/deeplink/subscribe?url=<enc>` → the `url` parameter; `podcasts.google.com/feed/<base64url>` → decoded feed URL (**Unverified:** the encoding; the shut-down service's links still circulate).
5. Scheme-less `host.tld/…` → `https://…` with `schemeGuessed = true`. When `schemeGuessed` and the `https` attempt fails with `CONNECTION`, `TLS_*` or `TIMEOUT`, the resolver retries once with `http://`.
6. Reject anything that is not `http(s)` after unwrapping (`file:`, `content:`, `javascript:`, …) as `InvalidUrl`; text with no URL-like token and no dot → `NotAUrl(query = text)`.
7. `UrlNormalizer.splitUserInfo` moves `user:pass@` into credentials.

### Host recognition

Order, first match wins (`AddPodcastResolverImpl` for the YouTube row, `HostRecognizer` for the rest):

| Input | Action |
|---|---|
| YouTube input: `YouTubeUrlClassifier.classify(input) != null` for the raw text or a token (the pre-check of [Input normalisation](#input-normalisation)) or for the unwrapped URL (M3+; `classify` never returns `Query`, [04 Channel resolution](04-youtube.md#channel-resolution)). Run by `AddPodcastResolverImpl` in `:core:data`, not by `HostRecognizer` (`:feeds` cannot see `:youtube:api`); in M1–M2, before the classifier exists, a host check (`youtube.com` and subdomains, `youtu.be`) stands in | M8+: `AddResolution.YouTube(ref)`; the add sheet continues with 04's [Subscribe flow](04-youtube.md#subscribe-flow). Before M8: `YouTubeNotYetSupported` |
| `podcasts.apple.com/…/id(\d+)`, `itunes.apple.com/…/id(\d+)`, `pod.link/(\d+)`, `overcast.fm/itunes(\d+)` | Apple lookup (`lookup?id=<id>&entity=podcast`, consumes one token of the [Apple bucket](#apple-itunes-search)) → `feedUrl` → fetch; no `feedUrl` → `AppleOnlyShow`; keep `artworkUrl600` as cover fallback |
| `podcastindex.org/podcast/(\d+)` and a Podcast Index key exists | `podcasts/byfeedid?id=<id>` → `url` → fetch; no key → generic fetch |
| `open.spotify.com/show/…` | `SpotifyShow` ("Spotify shows have no public RSS feed; search for the show by name instead") |
| anything else (incl. `fyyd.de/podcast/…`, other podcast-app pages) | fetch and sniff |

**Every URL the pipeline is about to fetch** — an Apple `feedUrl`, a Podcast Index `url`, an autodiscovery candidate, a `Choose` pick, a directory hit — passes the same YouTube check first, so a YouTube channel feed found on a web page always takes 04's branch and is never subscribed as `sourceType = RSS`. `SubscribeUseCase.invoke` re-checks the preview's final URL and refuses a YouTube one (`SubscribeError.Fetch(InvalidUrl)`, a bug guard).

**Typed names.** `NotAUrl(query)` leads only to directory search (08's "Search for …" → `DirectoryKey`, [Search and discovery](#search-and-discovery)); `AddPodcastResolver` and `SearchRepository` never send text to YouTube. Finding a YouTube channel by name is 04's explicit Discover action "Search YouTube channels" ([04 Channel search](04-youtube.md#channel-search)), offered only while `YouTubeCapabilitiesSource.capabilities.value.channelSearch` is true — the YouTube engine is in the APK and on, from M9a (R3.1). In external mode (`armeabi-v7a` APK, engine off or failed) the user pastes or shares a channel link or types an `@handle`; both take the YouTube pre-check and 04's engine-free resolution (R3.7, [08 Capability differences in UI](08-ui-ux.md#capability-differences-in-ui)).

### Fetch, sniff and autodiscovery

```mermaid
flowchart TD
  IN["input text or intent"] --> YP{"YouTube pre-check: classify text, then each token"}
  YP -->|"YtRef, incl. bare @handle"| YT["AddResolution.YouTube (04)"]
  YP -->|"null"| N["AddInputNormalizer"]
  N -->|"not a URL"| Q["Failure NotAUrl, offer search"]
  N -->|"http(s) URL"| Y{"YouTube? (unwrapped URL)"}
  Y -->|yes| YT
  Y -->|no| H{"known host?"}
  H -->|"Apple, pod.link, Overcast id"| L["Apple lookup"] --> FE
  H -->|"Podcast Index id"| PI["byfeedid"] --> FE
  H -->|Spotify| SP["Failure SpotifyShow"]
  H -->|other| FE["fetch and sniff, no validators"]
  FE -->|"RSS, Atom, RDF"| PV["parse in memory, dedupe"] --> FP["AddResolution.Feed"]
  FE -->|HTML| AD["Autodiscovery"]
  AD -->|"one candidate"| FE
  AD -->|"several candidates"| CH["AddResolution.Choose"]
  AD -->|none| NF["Failure NotAFeed"]
  FE -->|OPML| OP["Failure SubscriptionList, offer import (05)"]
  FE -->|"401 or 403 Basic"| AU["Failure AuthRequired"]
```

`Autodiscovery.candidates(html: InputStream, charset, pageUrl): List<DiscoveredFeed>` (`:feeds`, jsoup; reads at most the first 2 MiB of the page) and the resolver's probing. An OPML body yields `SubscriptionList(url)`; the sheet's "Import it" action calls 05's `ImportRepository.create(ImportSource(url, displayName = null))`.

1. **`<link rel=alternate>`**: `head link[rel~=(?i)\balternate\b][href]` with `type` matching `(?i)^(application/(rss|atom)\+xml|application/x-rss\+xml|text/xml)$`, resolved against `<base href>` or the page URL ([RSS autodiscovery](https://www.rssboard.org/rss-autodiscovery)). Drop comment feeds (URL contains `/comments/` or title contains "comment"). Rank: title or URL containing `podcast`, `audio`, `mp3` or `episodes` first, then document order (the first link is the site's main feed).
2. **Anchors** (only if step 1 found nothing): Apple Podcasts links (`podcasts.apple.com/…/id\d+`, first one → Apple lookup), then feed-looking anchors matching `(?i)(\.rss|\.xml)$|/feed/?$|/rss/?$|//feeds?\.` on the same site. 99percentinvisible.org has no `<link>` but links to Apple (tested), hence this step.
3. **Probes** (only if steps 1–2 found nothing): `/feed/podcast`, `/podcast.xml`, `/feed`, `/rss`, `/rss.xml` relative to the site root, sequential `GET` with `sniffOnlyBytes = 65,536`, stop at the first RSS/Atom; at most 5 requests within 15 s.
4. **Verification:** up to 5 candidates are fetched (2 in parallel) and parsed to obtain titles and episode counts; non-feeds are dropped. One survivor → preview it; several → `Choose`; none → `NotAFeed`.
5. **WordPress blog feeds:** if a parsed feed has items but no accepted item and its path ends with `/feed`, probe `<path>/podcast` (the usual podcast feed) before returning `NoMedia`.

### Preview and dedupe

- Parse in memory with the same `FeedParser`; never persisted ([D24](../PLAN.md#3-key-decisions)). The parsed feed, fetch metadata (final and permanent URL, validators, SHA-256), pending credentials and the directory cover fallback go into `PreviewCache` (`:core:data`, in memory): at most 2 entries, 15-min TTL, keyed by `previewId`; eviction drops the credentials.
- **Dedupe:** `forIdentity` of the input URL, final URL and permanent URL against `podcast.feedKey` and `podcast_url_alias.url` → `AlreadySubscribed(id, exact = true)` (the sheet shows "Already subscribed — Open"); a real `podcastGuid` equal to a subscribed podcast's real GUID → `AlreadySubscribed(id, exact = false)` ("You may already be subscribed to this show. Subscribe anyway?").
- **Subscribe button rules:** disabled for `NoMedia` (items exist, none playable); enabled for an empty feed (new show, `emptyFeed = true`); `isPrivate` (from `PrivateFeedUrls`) shows a "Private feed" chip.
- `PodcastPreviewKey(feedUrl)` (directory results) calls `preview(feedUrl)`, which reuses a cached entry or fetches anew.

### Subscribe transaction

```kotlin
// :core:domain (interface; implemented in :core:data because it needs one multi-table transaction)
interface SubscribeUseCase {
    suspend operator fun invoke(previewId: String, groupIds: Set<Long>): Outcome<Long, SubscribeError>
    suspend fun youTube(channel: ChannelResolution.Resolved, variants: Int, groupIds: Set<Long>): Outcome<Long, SubscribeError>
}
sealed interface SubscribeError {
    data class AlreadySubscribed(val podcastId: Long) : SubscribeError
    data class Fetch(val error: AddPodcastError) : SubscribeError        // preview expired and re-fetch failed
    data object NoMedia : SubscribeError
    data object Storage : SubscribeError
}
```

RSS subscribe (`invoke`):

1. Look up `previewId`; if evicted, re-fetch and re-parse the preview URL first (`Fetch(error)` on failure); a feed with items but no accepted item → `NoMedia`.
2. Credentials (if any): `CredentialStore.put` **before** the transaction (it encrypts outside SQLite); if the transaction below fails, `CredentialStore.remove(id)` unless another podcast references that row.
3. One `withWriteTransaction`:
   1. dedupe again: `feedKey` of `permanentUrl ?: requestedUrl` and the input URL against `podcast.feedKey` and aliases → `AlreadySubscribed(id)` (a `feedKey` unique violation maps to the same);
   2. insert `podcast`: `sourceType = RSS`, `feedUrl = permanentUrl ?: requestedUrl` (without userinfo), `feedKey`; metadata as in `applyFeedMetadata`; `status = ACTIVE`, `initialFetch = 0`, `subscribedAt = lastAttemptAt = lastSuccessAt = now`; `etag`, `lastModified`, `contentSha256`, `parserVersion = VERSION`, `lastParseOk = 1`, `lastFullFetchAt = now`, `nextRefreshAt` per [policy](#nextrefreshat-policy); `credentialId`; `artworkUrl` from the [artwork candidates](#artwork-candidates) (directory art as last resort) and its `artworkKey`; paging state "pending" when `feeds.backfill_paged_feeds` is on and `next ?: prevArchive` exists, "older pages exist, not wanted" when it is off, else "nothing older";
   3. aliases: the normalised input URL and every redirect hop URL that differs from `feedKey` (`SUBSCRIBE_INPUT`, `REDIRECT`);
   4. episodes: `FeedIngestor.ingestInTransaction` in `INITIAL` mode (no `isNew`; [D67](../PLAN.md#3-key-decisions) holds because nothing inserted now is new);
   5. memberships: `INSERT OR IGNORE INTO podcast_group_member(groupId, podcastId, sortOrder, addedAt, source)` for `groupIds` (`source = MANUAL`, `sortOrder` per 05).
4. After commit: emit `NewEpisodes(initialFetch = true)`; request the artwork pin (M4+); `enqueueNow(Podcasts([id]), force = false, pagesOnly = true, SUBSCRIBE)` when paging is pending; `reschedulePeriodic()`; drop the preview entry.

YouTube subscribe (`youTube`, M8): the columns come from `ChannelResolution.Resolved` exactly as listed in [04 Subscribe flow](04-youtube.md#subscribe-flow) (`status = PENDING_FIRST_FETCH`, `initialFetch = 1`, `nextRefreshAt = now`, `channelMetadataAt` when an avatar was resolved, provisional title `resolved.title ?: channelId`). 03's part: one transaction with the dedupe by `feedKey = forIdentity(YouTubeFeedUrls.canonical(id))` (no alias rows), the insert and the memberships; after commit `refreshNow(Podcasts([id]))`, the artwork pin request (M4+) and `reschedulePeriodic()`. The first fetch then runs through the engine as a pending podcast.

### Unsubscribe and other podcast operations

```kotlin
// :core:domain (03 owns the interface; library tile SQL: 02, rendering: 08)
interface PodcastRepository {
    fun observeLibraryTiles(groupId: Long?): Flow<List<LibraryTile>>   // 02 PodcastDao.observeLibraryTiles
    fun observePodcast(podcastId: Long): Flow<PodcastDetail?>
    fun observeFeedInfo(podcastId: Long): Flow<FeedInfo?>              // redacted URL, aliases, last refresh, error
    suspend fun unsubscribe(podcastIds: List<Long>)                    // DB part only; callers use UnsubscribeUseCase
    suspend fun downloadedEpisodeIds(podcastIds: List<Long>): List<Long>
    suspend fun setIncludeInAll(podcastId: Long, include: Boolean)
    suspend fun setCustomTitle(podcastId: Long, title: String?)
    suspend fun setCredentials(podcastId: Long, credentials: BasicCredentials): Outcome<Unit, AddPodcastError>
    suspend fun editFeedUrl(podcastId: Long, input: String): Outcome<Unit, AddPodcastError>
    suspend fun retry(podcastId: Long, refresh: Boolean = true)        // refresh = false: 05's import report re-runs its worker
    fun observeCategoryCounts(): Flow<List<CategoryCount>>             // suggested groups (M7)
}
interface EpisodeRepository {
    fun observeEpisode(episodeId: Long): Flow<EpisodeDetail?>
    fun observeShowNotes(episodeId: Long): Flow<ShowNotes?>
    suspend fun setPlayed(episodeIds: List<Long>, played: Boolean)     // semantics: 06 Positions and played state
    suspend fun markFeedPlayed(source: FeedSource, sortDateBefore: Long?)   // R2.6, SQL: 02 User-state writes
    suspend fun setFavorite(episodeId: Long, favorite: Boolean)
}
```

```kotlin
// :core:model — read models (SQL: 02 Library tiles and mosaics; rendering: 08). Display title = customTitle ?: title.
data class FeedHealth(val gone: Boolean, val needsCredentials: Boolean, val failureCount: Int, val lastSuccessAt: Long?,
                      val lastErrorKind: FeedErrorKind?, val possiblyDead: Boolean)
data class LibraryTile(val podcastId: Long, val displayTitle: String, val sourceType: SourceType, val status: PodcastStatus,
    val artwork: ArtworkRef, val artworkAvgArgb: Int?, val health: FeedHealth, val latestEpisodeAt: Long?,
    val subscribedAt: Long, val unplayedCount: Int)
data class PodcastDetail(val id: Long, val displayTitle: String, val author: String?, val description: ShowNotes?,
    val artwork: ArtworkRef, val bannerUrl: String?, val sourceType: SourceType, val link: String?, val episodeCount: Int,
    val latestEpisodeAt: Long?, val status: PodcastStatus, val health: FeedHealth, val isPrivate: Boolean,
    val episodeOrder: FeedOrder?, val showType: ShowType?, val hasOlderPages: Boolean)   // pagingNextUrl != null
data class EpisodeDetail(val id: Long, val podcastId: Long, val podcastTitle: String, val title: String, val pubDate: Long?,
    val durationMs: Long?, val artwork: ArtworkRef, val isVideo: Boolean, val sourceType: SourceType,
    val externalMediaId: String?, val availability: Availability?, val episodeDisplay: String?, val link: String?,
    val playedAt: Long?, val isFavorite: Boolean, val downloadState: DownloadState?)   // position: EpisodeLiveStateSource
data class FeedInfo(val feedUrl: String, val redactedUrl: String, val isPrivate: Boolean, val moves: List<FeedMove>,
    val lastAttemptAt: Long?, val lastSuccessAt: Long?, val nextRefreshAt: Long?, val lastErrorKind: FeedErrorKind?,
    val lastErrorDetail: String?, val pendingNewFeedUrl: String?)
data class FeedMove(val fromHost: String, val reason: AliasReason, val at: Long)   // aliases REDIRECT / NEW_FEED_URL
data class CategoryCount(val category: String, val podcastIds: List<Long>)
```

`FeedInfo.feedUrl` (full, for "Copy feed URL" and "Edit URL") is shown only after an explicit tap; everything else displays `redactedUrl` (01's `Redactor.url`).

- **Unsubscribe** ([D24](../PLAN.md#3-key-decisions)) spans three controllers, so it is a use case (01's use-case rule): `UnsubscribeUseCase` (concrete `@Inject` class in `:core:domain`, `operator fun invoke(podcastIds: List<Long>)`), invoked after the UI's confirmation (08 shows the downloaded-episode count), (1) calls `PlaybackController.pause()` if `PlaybackStateSource.nowPlaying` belongs to one of the podcasts (the cascade then sets `play_session.currentEpisodeId = NULL` and 06 clears the player, [06 Queue and play context](06-playback.md#queue-and-play-context)); (2) deletes download files via `DownloadController.delete(ids, byUser = false)` for `PodcastRepository.downloadedEpisodeIds(podcastIds)` (07 defers the current item's file until the cascade clears it); (3) calls `PodcastRepository.unsubscribe(ids)`, which flushes `FetchStateBatcher`, runs 02's `deleteCascade` per podcast (it also deletes credentials no longer referenced; `CredentialStore` drops them from memory through its DAO observation), unpins artwork (M4+), cancels this podcast's lines in active new-episode notifications (re-posting the channel's notification without them) and calls `reschedulePeriodic()`. `PlaybackController` and `DownloadController` are injected as `java.util.Optional<…>` (`@BindsOptionalOf` declared in `:core:data`'s Hilt module), present from M4 and M6; the merge path of `FeedRefresher` uses the same optional `DownloadController`.
- **Edit URL** (podcast settings, 05's import report; RSS only — hidden for `YOUTUBE_CHANNEL`): normalise the input ([Input normalisation](#input-normalisation)), refuse a YouTube URL (`InvalidUrl`), fetch and parse it in memory with `FetchMode.FULL` (HTML → `NotAFeed`; autodiscovery is not run), require ≥ 1 accepted item or an empty feed, then apply it as a move in the ingest transaction: new `feedUrl`/`feedKey`, the old `feedKey` becomes an alias (`SUBSCRIBE_INPUT`), old validators discarded, `gone`/`needsCredentials`/`failureCount` reset; ingest in `REFRESH` mode (`INITIAL` while pending). A key collision with another podcast merges ([Podcast dedupe and merge](#podcast-dedupe-and-merge)).
- **Played state:** `setPlayed`, `markFeedPlayed` and `setFavorite` use 02's column-scoped writes ([02 User-state writes](02-data-model.md#user-state-writes)) with 06's semantics ([06 Positions and played state](06-playback.md#positions-and-played-state)). Both run 02's chains in **one** write transaction (IDs chunked at 500 inside it): `played = true` → `ensureAll` + `markPlayed` + `PositionDao.reset` + removal from `queue_entry` (06's Up-next invariant); `played = false` → `markUnplayed` (clears `playedAt` and `startedAt`) + `PositionDao.reset`. `markFeedPlayed(source, before)` selects its IDs with 02's `FeedDao.unplayedIds` inside the same transaction, so the confirmation count and the rows marked share one predicate.

---

## Search and discovery

Serves onboarding for R1/R2/R5 and N3. Delivered in M7. Honours [D26](../PLAN.md#3-key-decisions) (amended 2026-10-05), [PO-3](../PLAN.md#po-3-podcast-index-api-key-handling) default B (option A: a key in the published builds built by `release.yml` once Podcast Index grants written permission); mitigates risk L3. Podcast directories only: YouTube channel search is 04's ([04 Channel search](04-youtube.md#channel-search)) and never part of `SearchRepository`.

```kotlin
// :core:domain
interface SearchRepository {
    fun search(query: String): Flow<SearchResults>               // emits after each provider answers
    suspend fun charts(genre: ChartGenre?): Outcome<List<DirectoryHit>, ProviderStatus>
    fun genreForGroupName(name: String): ChartGenre?
    fun observeProviders(): Flow<List<ProviderInfo>>              // for the disclosure line and Settings › Discover
    suspend fun setPodcastIndexKey(key: String, secret: CharArray): Outcome<Unit, ProviderStatus> // verified by one byterm call
    suspend fun clearPodcastIndexKey()
}
// :core:model
enum class ProviderId { APPLE, FYYD, PODCAST_INDEX }
data class ProviderInfo(val id: ProviderId, val host: String, val enabled: Boolean, val needsKey: Boolean, val hasKey: Boolean)
data class DirectoryHit(val feedUrl: String, val title: String, val author: String?, val artworkUrl: String?,
    val description: String?, val episodeCount: Int?, val lastEpisodeAt: Long?, val podcastGuid: String?,
    val providers: Set<ProviderId>, val genres: List<String>, val explicit: Boolean?, val subscribedPodcastId: Long?)
data class SearchResults(val query: String, val hits: List<DirectoryHit>, val statuses: Map<ProviderId, ProviderStatus>,
                         val complete: Boolean)
sealed interface ProviderStatus {
    data object Ok : ProviderStatus; data object Pending : ProviderStatus; data object RateLimited : ProviderStatus
    data class Failed(val error: NetError?) : ProviderStatus
}
// :core:data (internal)
internal interface PodcastSearchProvider {
    val id: ProviderId
    suspend fun enabled(): Boolean
    suspend fun search(query: String, country: String, limit: Int = 50): List<DirectoryHit>
}
```

The ViewModel debounces: search fires 600 ms after typing stops with ≥ 3 characters, or immediately on the IME search action. All providers use the API client (8 s call timeout, [01](01-foundation.md#one-client-family)); no cookies are stored.

### Apple iTunes Search

- Search `GET https://itunes.apple.com/search?media=podcast&entity=podcast&term=<q>&country=<cc>&limit=50`; lookup `GET https://itunes.apple.com/lookup?id=<id>[,<id>…]&entity=podcast` (batch works, tested).
- Fields: `collectionId`, `collectionName`, `artistName`, `feedUrl`, `artworkUrl600`, `trackCount`, `releaseDate`, `genres`, `collectionExplicitness`. Parsed with kotlinx.serialization (`ignoreUnknownKeys`) whatever the `text/javascript` content type says. Hits without `feedUrl` are dropped (about 1 in 100, tested); at most 100 results arrive even with larger limits.
- **Rate limit:** "approximately 20 calls per minute (subject to change)". `TokenBucket(capacity = 20, refill = 1 per 3 s)` shared by search, lookup (add flow) and charts; an empty bucket returns `RateLimited` at once (no queueing). A 403 or 429 drains the bucket for 60 s ("Search is busy, try again").
- Results cache: in-memory LRU, 50 entries, 30 min, keyed by (provider, country, lowercased trimmed query).
- Country: `discover.country`, else `Locale.getDefault().country`, else `US`.
- Terms: Apple's page frames the API around promoting Apple content and grants no explicit licence for directory use; every major open-source player uses it (risk L3, [Search API](https://performance-partners.apple.com/search-api)).

### fyyd

`GET https://api.fyyd.de/0.2/search/podcast?title=<q>&count=50` (the `/0.2/` prefix is mandatory, otherwise v0.1 answers); no key; fields `data[].title`, `xmlURL`, `htmlURL`, `imgURL`, `language`, `status`. No published rate limit or terms (Unverified); on by default in every locale (canonical default). Docs: [fyyd API](https://codeberg.org/eazy/fyyd-api).

### Podcast Index

- Base `https://api.podcastindex.org/api/1.0`; `search/byterm?q=<q>&max=50`, `podcasts/byfeedid?id=`, `podcasts/byitunesid?id=`, `podcasts/trending`. Fields used: `url`, `title`, `author`, `artwork`/`image`, `description`, `podcastGuid`, `dead`, `medium`, `episodeCount`, `newestItemPubdate` (names other than `dead`, `medium`, `podcastGuid`, `artwork`: Unverified against [the spec](https://podcastindex-org.github.io/docs-api/pi_api.json)).
- Headers: `User-Agent` (01), `X-Auth-Key: <key>`, `X-Auth-Date: <unix seconds>`, `Authorization: sha1hex(key + secret + date)` (lowercase). The date window is 3 min: on 401, if the response `Date` differs from the device clock by > 60 s, retry once with the server-derived time and keep the offset in memory. 403 without a key (tested).
- Filter `dead == 1`; hits with `medium` other than `podcast`, `video` or absent are down-ranked (score × 0.5).
- **Key ([PO-3](../PLAN.md#po-3-podcast-index-api-key-handling), [D26](../PLAN.md#3-key-decisions)):** two sources, a user key taking precedence over a build key.
  - **Build key** `BuildInfo.podcastIndexKey/Secret`: empty in every build until Podcast Index grants written permission (default B). After that (option A), only `release.yml` passes it — `-Pneutrodyne.podcastIndexKey/Secret` from the `release` environment's `PODCASTINDEX_KEY`/`PODCASTINDEX_SECRET` secrets, never committed ([01 Build variants and ABIs](01-foundation.md#build-variants-and-abis), [09 CI pipelines](09-quality-and-release.md#ci-pipelines)). The published APKs are debug builds like every other build ([D2](../PLAN.md#3-key-decisions)), so the build type never decides whether a key is present; only `release.yml` may inject it. Dev-tools builds, PR and nightly CI builds (including the report-only reproducibility check, [09 Reproducible builds](09-quality-and-release.md#reproducible-builds)) and local or third-party rebuilds stay keyless, and with GitHub Releases as the only channel no store-side rebuild has to match ours. The key is then in all three ABI APKs of a release and extractable from them: an abused key is revoked and replaced in the next release, and APKs carrying the revoked key fail soft (provider status `Failed`, Apple and fyyd unaffected) until updated.
  - **User key** from Settings › Discover › "Use my own Podcast Index API key", stored by `CredentialStore.put(origin = "podcastindex", username = key, secret)`; clearing it falls back to the build key, if any.
  - The provider is enabled only when a key exists and `discover.podcastindex_enabled` is on; otherwise it is hidden. The terms forbid embedding credentials in open-source projects ([Podcast Index ToS §4.2.1](https://github.com/Podcastindex-org/legal/blob/main/TermsOfService.md)); rate limits are undocumented.

### Aggregation

1. Run enabled providers in parallel, each wrapped in `withTimeoutOrNull(8 s)`; emit `SearchResults(complete = false)` after each answer and a final `complete = true`.
2. Merge with reciprocal-rank fusion: `score = Σ 1 / (10 + rank)` over providers listing the hit; ties by provider order APPLE, PODCAST_INDEX, FYYD.
3. Dedupe by `UrlNormalizer.forIdentity(feedUrl)`, then by real `podcastGuid`. Merged fields: first non-blank title; artwork Apple 600 › Podcast Index › fyyd; `podcastGuid` from Podcast Index or fyyd; max `episodeCount`.
4. Annotate `subscribedPodcastId` via `feedKey`/alias lookup (one query, ≤ 150 keys).
5. A failing provider yields partial results and a per-provider status (M7 acceptance 1).

### Charts and genres

- Top list: `https://rss.marketingtools.apple.com/api/v2/<cc>/podcasts/top/100/podcasts.json` (100 works, 200 fails, tested) → IDs (`feed.results[].id`, path Unverified) → one batch lookup → hits in chart order.
- Per genre: legacy `https://itunes.apple.com/<cc>/rss/toppodcasts/limit=100/genre=<id>/json` (tested; undocumented; entry ID path `feed.entry[].id.attributes["im:id"]` Unverified) → batch lookup. Both fail soft ("Charts unavailable"); in-memory cache 6 h.

| `ChartGenre` | Apple ID | Group-name synonyms (`genreForGroupName`, NFC, case-insensitive) |
|---|---|---|
| TECHNOLOGY | 1318 (tested) | tech, technology, technik |
| NEWS | 1489 (tested) | news, nachrichten, actualités, noticias |
| FICTION | 1483 (tested) | fiction, stories, audio drama, hörspiel |
| COMEDY | 1303 (tested) | comedy, humour, humor |
| SCIENCE / HISTORY / SPORTS / TRUE_CRIME / BUSINESS / ARTS / EDUCATION / HEALTH_FITNESS / KIDS_FAMILY / MUSIC / SOCIETY_CULTURE / TV_FILM / RELIGION / GOVERNMENT / LEISURE | 1533 / 1487 / 1545 / 1488 / 1321 / 1301 / 1304 / 1512 / 1305 / 1310 / 1324 / 1309 / 1314 / 1511 / 1502 (**Unverified**, checked in M7) | the English and German genre name, e.g. science/wissenschaft, history/geschichte, sport/sports, true crime/crime |

"Popular in <genre>" appears when a group's name maps to a genre (group screens, 08).

### Suggested groups

`PodcastRepository.observeCategoryCounts()` counts subscribed podcasts per top-level `itunes:category` (first path element of `categoriesJson`), excludes categories whose `GroupNames.key(category)` (05, `:core:model`) equals an existing group's `nameKey`, drops counts < 2, sorts by count descending then name, returns ≤ 5 (counted in Kotlin from `PodcastDao`'s `categoriesJson` column; no SQL reads JSON, 02). 08's onboarding card ([08 Onboarding and empty states](08-ui-ux.md#onboarding-and-empty-states)) offers them; a tap creates the group with those members through 05's `GroupRepository`.

### Privacy

Only the query text and the country go to directories; subscriptions and feed URLs never do. Discover shows "Searches are sent to Apple and fyyd" (plus "and Podcast Index" when enabled, also when the key is a release-build key) under the search field (M7 acceptance 6). Typed podcast searches never reach YouTube: 04's channel search sends a query to YouTube only on its explicit action and only with the engine (N3); `PRIVACY.md` lists the hosts ([09 Privacy](09-quality-and-release.md#privacy)). gpodder.net is not used (toplist a decade stale, tested).

---

## Deep links and share targets

Serves R3.1 (links), onboarding. Delivered in M7 (M1 handles the same schemes only when typed into the add sheet). Routing: [01 Intent routing](01-foundation.md#intent-routing) maps every match to `AddPodcastKey(input)`; nothing subscribes without the user tapping Subscribe.

```xml
<!-- :app manifest, inside <activity android:name=".MainActivity" android:exported="true"> -->
<intent-filter>   <!-- feed:, pcast:, podcast:, itpc: (each filter keeps scheme-only data: no host cross-product) -->
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <category android:name="android.intent.category.BROWSABLE" />
  <data android:scheme="feed" /> <data android:scheme="pcast" />
  <data android:scheme="podcast" /> <data android:scheme="itpc" />
</intent-filter>
<intent-filter>   <!-- neutrodyne://subscribe?url=…  (host restricts it: neutrodyne://open/… stays internal) -->
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <category android:name="android.intent.category.BROWSABLE" />
  <data android:scheme="neutrodyne" android:host="subscribe" />
</intent-filter>
<intent-filter>   <!-- Apple Podcasts web links; not App Links (no autoVerify): active on API 31+ only after the user adds the domain -->
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <category android:name="android.intent.category.BROWSABLE" />
  <data android:scheme="https" android:host="podcasts.apple.com" />
</intent-filter>
<intent-filter>   <!-- share text or a URL into the app -->
  <action android:name="android.intent.action.SEND" />
  <category android:name="android.intent.category.DEFAULT" />
  <data android:mimeType="text/plain" />
</intent-filter>
```

- Within one `<intent-filter>`, `<data>` attributes combine as a cross-product, so schemes and hosts of unrelated forms are never mixed in one filter ([data element](https://developer.android.com/guide/topics/manifest/data-element)). Scheme matching is case-sensitive; the router lowercases before matching (01).
- **Android 12+ web links.** Since API 31 a generic `https` VIEW intent resolves to an app only if the app is approved for that domain (verified App Links or a user choice in system settings); otherwise it opens the default browser, with no chooser ([Android 12 web intent resolution](https://developer.android.com/about/versions/12/behavior-changes-all#web-intent-resolution)). We cannot verify `podcasts.apple.com`, so on API 31+ the filter only works after the user enables it: Settings › Discover shows "Open Apple Podcasts links in Neutrodyne", which launches `Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` with `package:` URI (API 31+; row hidden below). The reliable path everywhere is **Share** (`ACTION_SEND`), which M7 acceptance 4 tests. Custom schemes (`feed:` …, `neutrodyne:`) are not affected.
- Not registered: `itunes.apple.com` (also music and app links), `http(s)` with RSS MIME types (browsers rarely hand them off; content URIs would need copying), OPML/ZIP files (05's `ExternalImportActivity`), and `neutrodyne://open/…` — the internal routes (`open/episode/{id}`, `open/group/{uuid}`, from M11a 08's `open/settings/updates` and `open/help/install`) stay unexported; notifications reach them through explicit `PendingIntent`s ([01 Intent routing](01-foundation.md#intent-routing)).
- Unwrapping of wrapper URLs: [Input normalisation](#input-normalisation). Shared text keeps only the first URL-like token (`EXTRA_TEXT` capped at 4 KB by the router).
- Spotify links: `SpotifyShow` explanation only; a "search by title" helper is v1.x ([PLAN 6](../PLAN.md#6-feature-scope)).

---

## New-episode notifications

Serves R2.7. Delivered in M2. Honours [D36](../PLAN.md#3-key-decisions), [D45](../PLAN.md#3-key-decisions), [D66](../PLAN.md#3-key-decisions). Channel IDs: `new_episodes` (default) and `new_episodes_{groupUuid}` in channel group `grp_new_episodes`; their creation, renaming and deletion with groups is 05's.

`NewEpisodeNotifier.post(batch: List<NewEpisodes>)` is called once per engine run with the run's `newEpisodes` (not via `IngestionEvents`, so one run yields at most one notification per channel even across 300 feeds):

1. Drop events with `initialFetch = true` or empty `episodeIds`.
2. Load the rows (episode title, podcast display title, `artworkKey`, and the podcast's groups with `uuid`, `name`, `sortOrder` and their `podcast_group_settings.notifyNewEpisodes`) in one query; drop episodes hidden by 04's visibility rules (the `VISIBLE` fragment, [02 Key queries](02-data-model.md#key-queries)) and episodes already marked played.
3. Per podcast, `EffectiveSettingsResolver.notifications(podcastIds)` ([D45](../PLAN.md#3-key-decisions); rules in [05 Effective settings resolution](05-groups-opml-backup.md#effective-settings-resolution), global `feeds.notify_new_episodes`, default off); skip podcasts resolving to `false`. In M1 nothing is posted (the notifier lands in M2).
4. **Channel:** the first group by `sortOrder` among the podcast's groups whose own `notifyNewEpisodes = 1` → `new_episodes_{uuid}`; none (the podcast or global setting enabled it) → `new_episodes`. An episode is therefore notified once even if its podcast is in two notifying groups.
5. Per channel: merge with the currently shown notification of that channel (read `nd.episode_ids` from `NotificationManager.getActiveNotifications()` extras), keep the newest 50.
6. Build: one episode → title = podcast title, text = episode title, large icon = cover from `ArtworkStore` (M4+, ≤ 256 px), tap → `neutrodyne://open/episode/{id}`; several → title "N new episodes", subtext = group name, `InboxStyle` with up to 6 lines "Podcast — Episode", tap → `neutrodyne://open/group/{groupUuid}` (default channel: the launcher intent, which shows the app as last left). `setOnlyAlertOnce(false)` (each run that adds episodes alerts once per channel; the per-run batching already bounds it), `setAutoCancel(true)`, `setWhen(now)`, `setCategory(CATEGORY_RECOMMENDATION)`, extra `nd.episode_ids` (`LongArray`), explicit `PendingIntent`s to `MainActivity` with `FLAG_IMMUTABLE` and a request code derived from the channel, no action buttons in v1.
7. Post with `notify(tag = channelId, id = 5000, …)` (ID range 5000+, one notification per channel, replaced in place); Android groups ≥ 4 notifications of one app automatically, so no summary notification is posted.
8. Before posting: `ensureChannels()` creates `new_episodes` and `grp_new_episodes` idempotently and, as a safety net, a missing group channel with the group's current name. Posting is skipped (and counted in diagnostics) when `POST_NOTIFICATIONS` is not granted (API 33+), `areNotificationsEnabled()` is false, or the channel's importance is `NONE`.

**Permission rule:** never at launch. When the user switches a new-episode setting on (global, group or podcast — screens by 05/08) on API 33+ without the permission, the screen requests `POST_NOTIFICATIONS` right then; if denied, the setting stays on with an inline "Notifications are blocked — Open settings" row ([notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)). Media notifications are exempt (06).

**YouTube and other notifications.** YouTube episodes are announced like any episode, with or without the YouTube engine: only the IDs `afterIngest` returns are announced, so with the engine upcoming premieres wait until playable, while in external mode they are announced on arrival (R3.7); the tap opens the episode screen, which in external mode offers "Watch on YouTube" (08). This notifier posts nothing else. Other notification IDs: playback `NOTIF_ID_PLAYBACK = 1001` and `NOTIF_ID_TAP_TO_RESUME = 4001` (06), downloads 2000–2999 (07), import and backup 3001–3004 and 3010 on `import_backup` (05), the YouTube breaker notice `NOTIF_ID_YT_BREAKER = 4100` on `alerts` (04), app updates `NOTIF_ID_UPDATE = 4200` on channel `updates` (09/08, M11a); new-episode notifications keep ID 5000 (tag = channel ID), so no ID is shared.

---

## Settings

Keys follow 01's `SettingKey` registry ([01 DataStore files](01-foundation.md#datastore-files-and-typed-setting-keys)). Per-podcast and per-group refresh intervals and notification switches live in `podcast_settings`/`podcast_group_settings` (`refreshIntervalMinutes`, `notifyNewEpisodes`; UI by 05).

| Key | Type | Default | File | UI location | Milestone |
|---|---|---|---|---|---|
| `feeds.refresh_interval_minutes` | Int32 ∈ {0 = manual only, 60, 120, 240, 480, 720, 1440} | 240 | `settings` | Settings › Feeds | M1 |
| `feeds.refresh_wifi_only` | Bool | false | `settings` | Settings › Feeds | M1 |
| `feeds.refresh_on_app_open` | Bool | true | `settings` | Settings › Feeds | M1 |
| `feeds.backfill_paged_feeds` | Bool | true | `settings` | Settings › Feeds ("Load older episodes when subscribing") | M1 |
| `feeds.show_notes_images` | Choice `ShowNotesImages` {ALWAYS, WIFI_ONLY, TAP_TO_LOAD} | TAP_TO_LOAD | `settings` | Settings › Feeds | M1 |
| `feeds.notify_new_episodes` | Bool | false | `settings` | Settings › Notifications section of Feeds | M2 |
| `discover.country` | Text ("" = device locale) | "" | `settings` | Settings › Discover | M7 |
| `discover.apple_enabled` | Bool | true | `settings` | Settings › Discover | M7 |
| `discover.fyyd_enabled` | Bool | true | `settings` | Settings › Discover | M7 |
| `discover.podcastindex_enabled` | Bool (effective only with a key) | true | `settings` | Settings › Discover | M7 |
| `discover.suggested_groups_dismissed` | Bool | false | `device_settings` | onboarding card (08) | M7 |
| `feeds.scheduled_tick_minutes`, `feeds.scheduled_tick_unmetered` | Int32 (−1 = none), Bool | −1, false | `device_settings` | internal | M1 |
| `feeds.last_run_finished_at`, `feeds.last_all_run_finished_at`, `feeds.last_run_summary`, `feeds.last_run_stop_reason` | Int64, Int64, Text (JSON), Int32 | 0, 0, "", −1 | `device_settings` | diagnostics (09); `last_all_run_finished_at` also gates the foreground trigger | M1 |

The user's Podcast Index key and Basic-auth passwords are never in DataStore; they are `credential` rows ([Basic auth and CredentialStore](#basic-auth-and-credentialstore)). `RefreshScheduler` collects `feeds.refresh_interval_minutes` and `feeds.refresh_wifi_only` on `@ApplicationScope` (`distinctUntilChanged`, skipping the initial value, which the order-200 initializer handles) and calls `reschedulePeriodic()`, so no screen has to remember to. A setting switched to a "Manual only" value takes effect through the [rebase](#periodic-tick).

---

## Testing

Infrastructure, runners and CI wiring: [09 Test strategy](09-quality-and-release.md#test-strategy). Mitigates risk T8.

### Golden corpus (`feeds/src/test/resources/feeds/`)

Each fixture `<name>.xml` has `<name>.golden.json` (the `ParsedFeed` serialised with sorted keys and `explicitNulls = false`); `FeedParserGoldenTest` compares them through 09's `Goldens` helper, and `./gradlew :feeds:test -PupdateGoldens` rewrites them locally (refused on CI, [09 Test strategy](09-quality-and-release.md#test-strategy)). Real-world fixtures are trimmed and their text replaced, keeping structure and quirks; `README.md` records each origin URL. At least these 52 fixtures (M1 acceptance 1 requires ≥ 40):

| Area | Fixtures |
|---|---|
| Namespaces | `itunes-undeclared-prefix`, `itunes-uri-case-variant`, `itunes-custom-prefix`, `pc20-canonical-ns`, `pc20-github-alias-ns` (reference-feed quirks: `application.x-mpegURL`, `application/srt`, chapters typed `application/json`, deprecated `podcast:images`), `media-ns-no-trailing-slash` |
| Text and encoding | `nbsp-outside-cdata`, `raw-html-in-description`, `double-escaped-html`, `cdata-description`, `windows1252-declared-utf8`, `utf16-bom`, `utf8-bom-utf16-declaration`, `latin1-declared`, `leading-whitespace-before-declaration` |
| Identity | `duplicate-guids`, `missing-guids`, `missing-guid-and-enclosure`, `guid-is-permalink-false` |
| Enclosures | `multiple-enclosures-audio-video`, `media-content-only`, `media-group-content`, `atom-link-enclosure`, `enclosure-type-missing`, `enclosure-octet-stream`, `enclosure-length-zero`, `hls-enclosure` |
| Formats | `rss2-minimal`, `atom-xhtml-content`, `rdf-rss1`, `youtube-atom-channel`, `youtube-atom-playlist-title-videos` |
| Paging and moves | `rfc5005-page1`, `rfc5005-page2`, `rfc5005-loop`, `fh-complete`, `new-feed-url-self`, `new-feed-url-other` |
| P2.0 and chapters | `psc-chapters`, `item-persons-replace-channel`, `funding-and-transcripts`, `alternate-enclosure-integrity`, `medium-podcastL`, `update-frequency-complete` |
| Dates and durations | `dates-rfc822-variants` (wrong weekday, `PDT`, `UT`, `Z`, two-digit year, German and French months), `dates-iso-variants` (offset, no zone, date only, space separator, garbage), `durations-variants` (`H:MM:SS`, `M:SS`, seconds, fraction, empty, > 48 h) |
| Artwork | `artwork-precedence` (itunes text-only href, `podcast:image` widths, RSS image fallback) |
| Hostile | `hostile-entity-doctype`, `hostile-deep-nesting`, `oversized-text` |
| Scale | `large-831-items` (generated, 3.5 MB, structure of 99% Invisible): parses in < 1 s on the JVM |

Also: `no-media-blog` and `empty-channel` for the accepted-items rules. The corpus runs (a) on the JVM with kxml2 (`:feeds:test`, every PR), (b) under Robolectric with `android.util.Xml.newPullParser()` (`:core:data` `test`, which reads the fixtures from `:feeds`' test resources), (c) as an instrumented test in `:core:data` `androidTest` on a Gradle Managed Device with the platform parser (once per `main` run, 09); all three must match the goldens (kxml2 vs AOSP differences). 09's `MutationRobustnessTest` additionally mutates every fixture and requires `FeedParser.parse` to return without throwing ([09 Test strategy](09-quality-and-release.md#test-strategy)).

### Unit and integration tests

| Test | Env | Cases | Milestone |
|---|---|---|---|
| `FeedDatesTest`, `DurationsTest`, `EnclosureTypesTest`, `UrlNormalizerTest`, `EpisodeKeysTest`, `PodcastGuidTest`, `PrivateFeedUrlsTest`, `TimestampLinkifierTest`, `AddInputNormalizerTest` | JVM, TestParameterInjector tables | every row of the rules in this document; `PodcastGuid.derive` reproduces both spec examples; `forIdentity` idempotent; times of day not linkified | M1 (normaliser wrappers M7) |
| `ShowNotesSanitizerTest` | JVM | script/style/iframe removed, `javascript:`/`data:`/`ftp:` links dropped, relative links absolutised, 1×1 pixels removed, every tag of the mapping table, plain text paragraphs, double-escaped input, caps | M1 |
| `AutodiscoveryTest`, directory parser tests (`AppleSearchParserTest`, `AppleChartParserTest`, `FyydSearchParserTest`, `PodcastIndexParserTest`) | JVM, HTML and recorded JSON fixtures in `:feeds` | ranking, comment feeds dropped, `<base href>`, Apple anchors, same-site anchors, 2 MiB cut; hits without `feedUrl`, `text/javascript` content type, unknown fields | M3 (link part), M7 |
| `FeedFetcherTest` | MockWebServer | 304 with validators; weak ETag sent verbatim; `If-Modified-Since` echoes the server string; no validators after a parser bump and on the fortnightly unmetered fetch; gzip body; 32 MB cap; HTML 200 → `NOT_A_FEED`; 401 Basic → challenge flagged; 401 Bearer; UA required by the server; 429 with seconds and HTTP-date `Retry-After`; 503; 410; redirect loop; `permanentUrl`: 301→301→200 = final URL, 301→302→200 = the 301's target, 302→301→200 and 302 alone = null; `FeedRequest.credentials` sent, and dropped on a cross-host redirect | M1 |
| `IngestDiffTest` | JVM + in-memory DB (02 `TestDb`) | subscribe → v2 with rewritten GUIDs → v3 with removed items: no duplicates, `episode_state` kept, removed items `inFeed = 0` (M1 acceptance 3); duplicate GUIDs in one document; enclosure prefix rotation; query-only change; title edit updates `contentHash`; `chaptersUrl` change deletes `PODCASTING20_JSON` chapter rows; null `durationMs` keeps the stored value; future-dated item clamped; undated feed order stable; back-catalogue dump guard (300-item dump → ≤ 3 new); re-published old episode not new; `INITIAL` never new; partial-window rule for paged documents and with an `absenceFloor`; empty channel and HTML body change nothing; unique violation aborts only that feed; older-key-version rekey (test-only `EpisodeKeys` v2) | M1 |
| `FeedMovesTest` | MockWebServer + DB | 301 chain updates `feedUrl` only after a successful parse; alias written; collision merges into the key owner, ingests the body into the winner, deletes only matched loser downloads whose winner has one (fake `DownloadController`); `new-feed-url` self-reference ignored, implausible target stays pending and is not re-probed on 304, plausible target adopted; scheme-only change without alias; renormalisation | M1 |
| `PagingTest` | MockWebServer + DB | 50-page budget per run keeps `pagingNextUrl`; 5,000-item cap; loop; no-new-keys stop; deadline stop resumes at the next page; refresh never rewinds a session; refresh of a paged feed never flips older-page episodes to `inFeed = 0`; imports do not backfill; "Load older" resumes | M1 |
| `RefreshEngineTest` | `TestDb` + fake adapters + MockWebServer + `TestClock` | only due feeds fetched; `refreshNow(Group(id))` fetches exactly that group's member podcasts (M2 acceptance 11); slack; force marks due and survives a stop; mutex; ≤ 6 concurrent and ≤ 2 per host (MockWebServer dispatcher counts); deadline stops launching and cancelling mid-run leaves no partial ingest (M1 acceptance 4); 304 writes nothing to `episode` and identical SHA-256 runs no diff (Room invalidation tracker counts) and HTML 200 leaves episodes untouched (M1 acceptance 2); `nextRefreshAt` table incl. `NEVER` and `Deferred`; possibly-dead derivation; `afterIngest` runs before the emit and the emit survives cancellation; notifications posted for committed feeds after a stop; origin `IMPORT` run yields zero `initialFetch = false` events (M3 acceptance 4); parser-version bump re-ingests a feed that answers 304 to conditional requests | M1, M3, M8 |
| `RefreshWorkerTest` | WorkManager `TestDriver` (`work-testing`) | deadline → `refresh-continuation` enqueued once (`KEEP`); a continuation that still has work returns `retry` and stops after 10 attempts (M1 acceptance 4); API 30 `refresh-now` not expedited, API 31 expedited; `refresh-now` constraints network-only | M1 |
| `RefreshSchedulerTest` | JVM with fake `WorkManager` wrapper | tick = min effective interval with 60-min floor; manual-only cancels; unchanged spec does not re-enqueue; rebase: 1440 → 60 makes a feed due, manual-only lifted, failing feed untouched, forced row untouched; a podcast override 0 sets that feed to `NEVER` while the others keep the global tick; a 0-group plus a 60-group keeps the feed at 60; settings collectors call `reschedulePeriodic()` | M1, M2 |
| `RefreshForegroundObserverTest` | Robolectric + `TestLifecycleOwner` | enqueues only when enabled, older than the tick, ≥ 10 min since the last trigger, and not metered under Wi-Fi-only | M1 |
| `CredentialStoreTest` | JVM with software `CipherProvider`; instrumented with Keystore | round trip; one row per origin; AAD mismatch fails; key loss sets `needsCredentials`; `awaitLoaded` before first lookup; a row deleted by the cascade leaves the map; stored origin without port equals `Origin.of` with 443 | M1 |
| `SubscribeUseCaseTest`, `UnsubscribeUseCaseTest` | `TestDb` + fakes | aliases, memberships, `INITIAL` episodes, paging state per setting, credential removed when the transaction fails, YouTube URL refused by `invoke`; YouTube `youTube()` inserts a pending row and refreshes; unsubscribe pauses only when the current episode belongs to the podcast, deletes downloads before the cascade, flushes the batcher | M1, M4, M6, M8 |
| `NewEpisodeNotifierTest` | Robolectric | channel choice; one notification per channel per run (M2 acceptance 7); merge with an active notification; initial fetch silent; permission denied posts nothing; hidden YouTube items skipped; unsubscribe removes the podcast's lines | M2 |
| `AddPodcastResolverTest` | MockWebServer + fakes | `@mkbhd`, `@some.name`, "Subscribe to @mkbhd" and a bare `UC…` ID → `AddResolution.YouTube` (M8; `YouTubeNotYetSupported` in M3–M7) and never `NotAUrl` or a fetch; `neutrodyne://subscribe?url=` wrapping a YouTube URL → `AddResolution.YouTube`; direct feed; scheme-less https then http fallback; Apple link via recorded lookup JSON (M7 acceptance 4); autodiscovery fixtures: `<link rel=alternate>` page, Apple-link-only page, WordPress `/feed/podcast`, several candidates → `Choose` (M7 acceptance 3); a page whose only feed is a YouTube channel feed → `AddResolution.YouTube`; OPML → `SubscriptionList`; Spotify; NoMedia vs empty feed; credentials flow; already subscribed exact and by GUID; preview eviction → re-fetch; a plain name ("the daily") → `NotAUrl` with no request to any YouTube host; the resolver has no `YouTubeCapabilitiesSource` dependency, so every YouTube case gives the same result in engine and external mode (M8 acceptance 8) | M1, M7, M8 |
| `SearchRepositoryTest` | MockWebServer with recorded JSON + `TestClock` | merged, de-duplicated Apple + fyyd for "news"; one failing provider → partial results; hits without `feedUrl` dropped (M7 acceptance 1); ≤ 20 Apple requests per minute (M7 acceptance 2); PI hidden without key, visible with a user key stored encrypted (M7 acceptance 5); a build key (`BuildInfo.podcastIndexKey`) enables PI, a user key overrides it and clearing the user key falls back to it; a revoked key (401 without clock skew) yields `Failed` for PI only; PI auth header for a fixed clock; clock-skew retry; RRF order; cache hit; `observeProviders` drives the disclosure line (M7 acceptance 6) | M7 |

**Nightly live canary** (non-blocking, workflow `live-canary` owned by 09): `./gradlew :feeds:liveCanary` (a `JavaExec` task over a separate `canary` source set of `:feeds` that uses `java.net.http.HttpClient`, so `main` stays I/O-free) fetches and parses the ~30 public feeds in `feeds/canary/feeds.txt` (including the Podcasting 2.0 reference feed) and reports new warnings or parse failures; it never gates merges and never touches YouTube.

---

## Delivery by milestone

| Milestone | Delivered in this area |
|---|---|
| [M1](../PLAN.md#m1-subscribe-and-ingest-rss) | `:feeds` parser, model, namespaces, dates, durations, enclosure types, artwork candidates, `EpisodeKeys`, `UrlNormalizer`, `PodcastGuid`, `PrivateFeedUrls`, `ShowNotesSanitizer` + block model + snippet + linkifier, golden corpus (52 fixtures; PLAN asks ≥ 40); `FeedFetcher`, `FeedIngestor`, `FeedRefresher` with the full `SourceAdapter` contract, `RssSourceAdapter`, `RefreshWorker`, `RefreshScheduler` (`refresh-periodic`, `refresh-now`, `refresh-continuation`, global interval only), app-foreground trigger; increment M1b ([PLAN M1](../PLAN.md#m1-subscribe-and-ingest-rss)): `CredentialStore`, moves, redirect and `new-feed-url` aliases, merges (without download deletion), RFC 5005 paging; add by URL for direct feed URLs with scheme normalisation and the YouTube host guard, in-memory preview, `SubscribeUseCase` (RSS), `UnsubscribeUseCase` (database part), `PodcastRepository`/`EpisodeRepository` incl. `setPlayed`; refresh and show-notes settings |
| [M2](../PLAN.md#m2-groups-and-group-feeds) | `RefreshScope.Group` and `refreshFeed(source)`; effective refresh intervals (05 resolver) in the tick and the rebase; `NewEpisodeNotifier` with per-group channels, permission rule; `markFeedPlayed`; `IngestionEvents` consumers start |
| [M3](../PLAN.md#m3-import-export-and-backup) | Engine API for `ImportFetchWorker` (`FeedRefresher.run`, `events`, outcomes incl. `Merged`, `firstIngest`, `sameGuidAs`); pending-podcast rules with the link/anchor part of `Autodiscovery`; `editFeedUrl`, `retry(refresh = false)`; `CredentialStore.forPodcast` and `PrivateFeedUrls` consumed by 05's export; the YouTube guard switches to `YouTubeUrlClassifier` |
| [M4](../PLAN.md#m4-playback-core) | Artwork pin requests on subscribe and artwork change; notification large icons; unsubscribe pauses current playback |
| [M5](../PLAN.md#m5-playback-features-and-system-surfaces) | Show-notes timestamp spans wired to 06's seek and `playEpisodeAt` (M5 acceptance 5) |
| [M6](../PLAN.md#m6-downloads) | Unsubscribe and merge delete download files through `DownloadController` |
| [M7](../PLAN.md#m7-discovery) | `SearchRepository` with Apple, fyyd, Podcast Index (BYOK; a build key, injected into the published builds by `release.yml`, only after Podcast Index's written permission, PO-3); charts and genre mapping; suggested groups; host recognition, full autodiscovery with Apple anchors and probes, chooser, wrapper unwrapping; `MainActivity` VIEW/SEND filters and the "Open Apple Podcasts links" settings row; Spotify explanation; provider disclosure |
| [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds) | 04's `YouTubeSourceAdapter` and `YouTubeOutageMonitor` plugged into the engine (`absenceFloor`, `Deferred`, `afterIngest` IDs), `SubscribeUseCase.youTube`, `AddResolution.YouTube` live for links, bare `@handle`s and `UC…` IDs (removes `YouTubeNotYetSupported`) |
| [M9](../PLAN.md#m9-youtube-playback-and-downloads-via-the-embedded-yt-dlp-engine) | M9a: `afterIngest` enrichment through `YouTubeEnricher` on APKs with the YouTube engine on (04; external mode: none); Discover's engine-only YouTube channel search (04, 08) appears beside the unchanged `SearchRepository` ([Typed names](#host-recognition)). M9b: nothing in this area |
| [M11](../PLAN.md#m11-release-hardening-and-v10) | M11b: diagnostics content (run summary, stop reasons, parse warnings), nightly live canary, performance check of the 300-feed refresh. M11a: nothing in this area (the update check only notifies; an app update the user installs may stop a refresh run, which resumes as after a process death, [Failure modes](#failure-modes)) |

---

## New names introduced here

| Name | Kind | Module |
|---|---|---|
| `ParsedFeed`, `ParsedEpisode`, `Enclosure`, `AlternateEnclosure`, `ArtworkCandidate`, `Person`, `Funding`, `TranscriptRef`, `InlineChapter`, `Paging`, `ParseWarning`, `WarningCode`, `FeedFormat` | parser model | `:feeds` |
| `FeedParser.parse`, `ParseResult`, `ParseFailure`, `ParseLimits`, `PullParserFactory`, `Namespaces`, `HtmlEntities`, `InnerXml`, `PrologGuard`, `FeedDates`, `Durations`, `EnclosureTypes`, `FeedSniffer` | parsing | `:feeds` |
| `KeyInput`, `EpisodeKeys.primary/fallbacks`, `EpisodeContentHash`, `TitleMatch`, `UrlNormalizer` members, `PrivateFeedUrls` | identity | `:feeds` |
| `ShowNotesDocument`, `NoteBlock`, `NoteSpan`, `PlainTextSnippet`, `TimestampLinkifier` | show notes | `:feeds` |
| `AddInputNormalizer`, `NormalizedInput`, `HostRecognizer`, `Autodiscovery`, `DiscoveredFeed` | add flow | `:feeds` |
| `AppleSearchParser`, `AppleChartParser`, `FyydSearchParser`, `PodcastIndexParser`, `AppleGenres` | directory parsers | `:feeds` |
| `FeedErrorKind` and its 24 values, `FeedPreview`, `PreviewEpisode`, `FeedCandidate`, `AlreadySubscribed`, `BasicCredentials`, `DirectoryHit`, `ProviderId`, `ProviderStatus`, `ProviderInfo`, `SearchResults`, `ChartGenre`, `ShowNotes`, `ShowNoteBlock`, `ShowNoteSpan`, `ShowNotesImages`, `LibraryTile`, `PodcastDetail`, `FeedHealth`, `EpisodeDetail`, `FeedInfo`, `FeedMove`, `CategoryCount` | model types | `:core:model` |
| `AddResolution`, `AddPodcastError`, `SubscribeError`, `UnsubscribeUseCase`; `AddPodcastResolver.resolve(input, credentials)`/`preview`; `RefreshController.refreshFeed/loadOlderEpisodes`; `SearchRepository.setPodcastIndexKey/clearPodcastIndexKey`; `PodcastRepository`/`EpisodeRepository` members above (incl. `retry(podcastId, refresh)`) | domain contracts | `:core:domain` |
| `FeedFetcher` types `FeedRequest`, `FetchOutcome`, `Sniff`; `FeedTempFiles`; `FeedIngestor` (`ingest`, `ingestInTransaction`), `IngestContext`, `IngestMode`, `FetchMeta`, `RowHint`, `IngestResult`; `FeedRefresher` types `RefreshRequest`, `RefreshOrigin`, `RefreshReport`, `FeedOutcome` (incl. `Deferred`), `FeedRunEvent`; `RefreshScheduler.enqueueNow/enqueueContinuation`; `SourceAdapter`, `FetchMode`, `AdapterResult` (incl. `Deferred`, `Parsed.absenceFloor`), `SourceTypeKey`, `RssSourceAdapter`, `YouTubeSourceAdapter`, `YouTubeOutageMonitor` (behaviour: 04); `RefreshForegroundObserver`; `PreviewCache`; `CipherProvider`; `CredentialStore.forPodcast`; `IngestionEventBus`; `PodcastSearchProvider`, `AppleSearchProvider`, `FyydSearchProvider`, `PodcastIndexSearchProvider`, `TokenBucket` | implementation | `:core:data` |
| `DueFeed` | name for 02's `dueForRefresh` projection | `:core:database` (02) |
| `feeds.*`, `discover.*` keys in [Settings](#settings) (incl. `feeds.last_all_run_finished_at`) | setting keys | `:core:model` registry |
| Keystore alias `neutrodyne_credentials_v1`; notification extra `nd.episode_ids`; notification ID 5000 with tag = channel ID; `NEVER = 253_402_300_799_000` | constants | `:core:data` |
| `FeedParserGoldenTest`, `RefreshWorkerTest`, `RefreshForegroundObserverTest`, `SubscribeUseCaseTest`, `UnsubscribeUseCaseTest`, `AutodiscoveryTest`; Gradle task `:feeds:liveCanary`, source set `canary` | tests and tooling | `:feeds`, `:core:data` |

---

## Open questions

1. Resolved by [D68](../PLAN.md#3-key-decisions): option (b) stays — `:feeds` produces `ShowNotesDocument`, `:core:data` maps it into `ShowNotes` (`:core:model`).
2. Resolved: [D25](../PLAN.md#3-key-decisions) now says `refresh-now` is expedited on API 31+ only.
3. Resolved: [D25](../PLAN.md#3-key-decisions) records `KEEP` with `Result.retry()` (linear 60 s backoff, at most 10 attempts) for the continuation's own follow-up ([Work requests](#work-requests)).
4. Accepted for v1 as risk [T13](../PLAN.md#8-risks-and-mitigations): `CredentialLookup` stays keyed by origin; a per-request credential hint in `AuthInterceptor` and 06/07's enclosure requests is v1.x work.
5. Resolved: PLAN M7's deliverables state that the `https://podcasts.apple.com` VIEW filter works on API 31+ only after the user approves the domain, and that Share is the working path.
6. Resolved in 02: `PodcastFetchState` carries `etag`, `lastModified`, `lastFullFetchAt`, `lastParseOk`; `CredentialDao.observeAll()`; the merge preamble deletes only matched loser downloads whose winner has a `download` row; chapter rows are replaced per `(episodeId, source)`; `dueForRefresh`/`pagingPending` select `lastAttemptAt` and `lastErrorKind`; "possibly dead" uses `COALESCE(lastSuccessAt, subscribedAt)` and `gone = 0`. The optional unique index on `credential.origin` is not added (`CredentialStore` keeps one row per origin).
7. Moved to [PO-21](../PLAN.md#48-further-product-owner-decisions) (defaults: `TAP_TO_LOAD`; "Manual only" offered at every scope).
8. Recorded as risk [T11](../PLAN.md#8-risks-and-mitigations): dump-guard numbers (more than 20 qualifying → newest 3) and the fortnightly unmetered full fetch are first estimates; M11 diagnostics data may tune them.
9. Unverified facts to check in their milestone: Apple genre IDs other than 1318/1489/1483/1303 and the chart JSON paths (M7); Podcast Index response field names (M7); Google Podcasts link encoding (M7); fyyd rate limits and terms; Android 17's exact "local network" address set (01).
10. Apple's search terms grant no explicit licence for directory use (risk L3); if Apple objects, fyyd becomes the default and Apple an opt-in.
11. Podcast Index permission ([PO-3](../PLAN.md#po-3-podcast-index-api-key-handling) option A): the request must say that the key would be compiled into public GitHub release APKs (debug builds, [D2](../PLAN.md#3-key-decisions)), where anyone can extract it, and ask for the applicable rate limits. Until a written answer arrives, every build is keyless (default B). **Owner 09:** once a key is injected, a keyless rebuild of a tag no longer matches the published APK byte for byte ([D79](../PLAN.md#3-key-decisions)'s "anyone can rebuild a tag"); 09's [Reproducible builds](09-quality-and-release.md#reproducible-builds) must then say how the comparison treats `BuildConfig`.

---

## Sources

Checked 2026-10-04 by the research behind this plan unless marked otherwise.

Parsing and formats:
- AOSP KXmlParser (`relaxed`, BOM and declaration sniffing, `defineEntityReplacementText`) — https://android.googlesource.com/platform/libcore/+/refs/heads/main/xml/src/main/java/com/android/org/kxml2/io/KXmlParser.java
- kxml2 2.3.0 — https://repo1.maven.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.pom
- prof18 RSS-Parser 6.1.8 (rejected) — https://repo1.maven.org/maven2/com/prof18/rssparser/rssparser/maven-metadata.xml · https://github.com/prof18/RSS-Parser · https://raw.githubusercontent.com/prof18/RSS-Parser/master/rssparser/src/commonMain/kotlin/com/prof18/rssparser/internal/RssKeyword.kt
- ROME 2.1.0 (rejected) — https://repo1.maven.org/maven2/com/rometools/rome/maven-metadata.xml
- AntennaPod licence (GPL-3.0, behaviour reference only) — https://raw.githubusercontent.com/AntennaPod/AntennaPod/develop/LICENSE
- Podcasting 2.0 namespace (URI and GitHub alias, tags, `images` deprecation) — https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md
- `podcast:guid` UUIDv5 — https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/guid.md
- `podcast:chapters`, JSON chapters, `podcast:transcript` — https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/chapters.md · https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/examples/chapters/jsonChapters.md · https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/transcript.md
- Podcasting 2.0 reference feed quirks — https://feeds.podcastindex.org/pc20.xml
- Podlove Simple Chapters — https://podlove.org/simple-chapters/
- Apple: `itunes:duration` forms, artwork 1400–3000 px — https://help.apple.com/itc/podcasts_connect/en.lproj/itcb54353390.html
- Apple: change the RSS feed URL (301 + `new-feed-url` for ≥ 4 weeks, stable GUIDs) — https://podcasters.apple.com/support/change-the-rss-feed-url
- RFC 5005 Feed Paging and Archiving (not re-fetched) — https://www.rfc-editor.org/rfc/rfc5005
- RSS autodiscovery — https://www.rssboard.org/rss-autodiscovery
- jsoup 1.23.2 and `Safelist` — https://repo1.maven.org/maven2/org/jsoup/jsoup/ · https://jsoup.org/apidocs/org/jsoup/safety/Safelist.html
- Compose `AnnotatedString.fromHtml` (fallback considered) — https://developer.android.com/jetpack/androidx/releases/compose-ui · https://developer.android.com/develop/ui/compose/text/style-text
- Real-world feeds used for facts: 99% Invisible (3.5 MB, 831 items, weak ETag, `max-age=3600`, self-referential `new-feed-url`) — https://feeds.simplecast.com/BqbsxVfO · YouTube channel feed (15 entries, no validators, `max-age=900`) — https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ

Networking and platform:
- OkHttp 5.5.0 changelog — https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md
- Network security config (cleartext, user CAs, Certificate Transparency) — https://developer.android.com/privacy-and-security/security-config
- Android 17 local network permission and behaviour changes — https://developer.android.com/privacy-and-security/local-network-permission · https://developer.android.com/about/versions/17/behavior-changes-17
- `security-crypto` deprecation — https://developer.android.com/jetpack/androidx/releases/security
- Android Keystore (not re-checked 2026-10-04) — https://developer.android.com/privacy-and-security/keystore
- WorkManager 2.12.0 and limits (periodic minimum, expedited quota, `getForegroundInfo` before Android 12, backoff) — https://developer.android.com/jetpack/androidx/releases/work · https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- Expedited job constraints — https://developer.android.com/reference/android/app/job/JobInfo.Builder#setExpedited(boolean)
- Long-running workers (10-minute window) — https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- Android 16 job quotas and stop reasons — https://developer.android.com/about/versions/16/behavior-changes-all · quota table https://developer.android.com/topic/performance/power/power-details
- App updates end the app's processes (`ApplicationExitInfo.REASON_PACKAGE_UPDATED`: "Application process was killed because it was updated", API 34+; earlier releases report `REASON_USER_REQUESTED`) — https://developer.android.com/reference/android/app/ApplicationExitInfo (checked 2026-10-05)
- Battery and standby buckets, no exemption requests — https://developer.android.com/develop/background-work/background-tasks/optimize-battery · https://developer.android.com/training/monitoring-device-state/doze-standby
- Notification runtime permission — https://developer.android.com/develop/ui/views/notifications/notification-permission
- Manifest `<data>` matching — https://developer.android.com/guide/topics/manifest/data-element
- Android 12 web intent resolution (generic `https` intents need domain approval) — https://developer.android.com/about/versions/12/behavior-changes-all#web-intent-resolution · user approval — https://developer.android.com/training/app-links/verify-android-applinks (checked 2026-10-05)
- `ExistingWorkPolicy` semantics (`KEEP` and running work) and expedited-request validation in `WorkRequest.Builder.build()` — https://github.com/androidx/androidx/blob/androidx-main/work/work-runtime/src/main/java/androidx/work/ExistingWorkPolicy.kt · https://github.com/androidx/androidx/blob/androidx-main/work/work-runtime/src/main/java/androidx/work/WorkRequest.kt (checked 2026-10-05)

Directories:
- Apple iTunes Search API (no key, ~20 calls/min) — https://performance-partners.apple.com/search-api
- Podcast Index API 1.12.1 — https://podcastindex-org.github.io/docs-api/pi_api.json
- Podcast Index Terms of Service §4.2.1 — https://github.com/Podcastindex-org/legal/blob/main/TermsOfService.md
- AntennaPod's committed Podcast Index key (not followed) — https://raw.githubusercontent.com/AntennaPod/AntennaPod/develop/net/discovery/build.gradle
- fyyd API v0.2 — https://codeberg.org/eazy/fyyd-api
- gpodder.net state (not used) — https://forum.antennapod.org/t/gpodder-down/423

Prior art:
- AntennaPod refresh scheduler and 20 s manual cooldown (`FeedUpdateManagerImpl.java`, `FeedUpdateWorker.java`), duplicate guesser behaviour (`FeedItemDuplicateGuesser`) — https://github.com/AntennaPod/AntennaPod
- AntennaPod: exact-time refresh impossible — https://forum.antennapod.org/t/option-to-refresh-podcasts-at-specific-time/5222
- Kasts 26.04 (same-server updates sequential) — https://apps.kde.org/kasts/
- Google Podcasts malformed OPML (refugee input) — https://forum.antennapod.org/t/cant-import-google-podcasts-opml-file/5363
- Spotify has no public audio URL — https://openrss.org/work/156
