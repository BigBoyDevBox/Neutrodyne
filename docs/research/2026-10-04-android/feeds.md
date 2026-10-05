# Research notes: Feed ingestion, refresh & discovery

Scope: how Neutrodyne fetches, parses, stores and refreshes podcast feeds (RSS 2.0, iTunes, Podcasting 2.0, Atom), and how users find podcasts (directory search, add by URL, website auto-discovery, deep links). Checked on 2026-10-04. Live tests were run against real endpoints from the research sandbox; they are marked **(tested)**.

These notes assume the stack proposed in `research/stack.md`:

* minSdk 26, targetSdk/compileSdk 37
* OkHttp 5.5.0
* Room 3
* Hilt
* WorkManager
* a hand-written XmlPullParser in a pure-JVM `feeds` module

Where this document needs something more specific, it says so.

---

## Recommendation

1. **Write our own streaming parser on `XmlPullParser`.** Do not adopt prof18 RSS-Parser.
   * Use the platform parser on device (KXmlParser, via `android.util.Xml.newPullParser()`) and kxml2 2.3.0 in JVM tests.
   * Turn on namespace processing **and** kxml's `relaxed` feature.
   * Dispatch on the **namespace URI**, with a fallback to the prefix.
   * Pre-define the HTML named entities.
   * Map every format onto one normalised `ParsedFeed` / `ParsedEpisode` model.
   * The parser is pure: bytes in, model out, no I/O. It lives in the `feeds` module with a large golden-file corpus.
   * Why not RSS-Parser: it is Apache-2.0 and actively maintained (6.1.8 on 2026-07-30), but:
     * it has **no Podcasting 2.0 support**;
     * it keeps only one enclosure per item;
     * dates come back as raw strings;
     * it ignores `atom:link rel="next"`;
     * it matches tags by literal prefix (`"itunes:duration"`) instead of by namespace.

     We would end up forking it.

2. **Podcasting 2.0 tiers.**
   * **Ingest at v1:** `guid`, `chapters`, `transcript`, `funding`, `person`, `season`, `episode`, `alternateEnclosure`/`source`/`integrity`, `locked`, `medium`, `image`, `updateFrequency`, `trailer`.
   * **Also parse at v1:** Podlove Simple Chapters (`psc:`).
   * **Parse and store but don't surface at v1:** `podping`, `atom:link rel="hub"`.
   * **Ignore at v1:** `value`/`valueRecipient`, `liveItem`, `remoteItem`/`podroll`, `socialInteract`, `chat`, `soundbite`, `location`, `license`, `txt`, `block`.
   * Recognise **both** namespace URIs the spec declares identical. The Podcasting 2.0 reference feed itself uses the GitHub alias (tested).

3. **Fetch with one shared OkHttp client.** Use manual conditional GET, not OkHttp's `Cache`, for feeds.
   * Store `ETag`, `Last-Modified` and a SHA-256 of the body per feed.
   * Send `If-None-Match` / `If-Modified-Since` on every refresh, including pull-to-refresh.
   * On 304, do nothing.
   * On 200 with an unchanged hash, skip the database diff.
   * Rely on OkHttp's transparent gzip.
   * Timeouts: connect 15 s, read 30 s, call 120 s. Cap the body at 32 MB.
   * Send a specific User-Agent, `Neutrodyne/<ver> (Android <rel>; +<project-url>)`. At least one feed host (feeds.podcastindex.org) returns **403 to generic User-Agents** (tested).

4. **Buffer the body to a temp file, then parse from the file.**
   * We can hash it before deciding to parse.
   * We can re-parse with a different charset if detection was wrong.
   * We never hold a half-parsed feed in the database.
   * Big real feeds exist: 99% Invisible is 3.5 MB uncompressed, 378 KB gzipped, 831 items (tested).

5. **Episode identity uses a key ladder:**
   * `guid`
   * else the normalised enclosure URL
   * else hash(title + publication day)
   * else hash(link)

   On top of that, migration-tolerant secondary matching (enclosure URL, then title + date) prevents duplicates when a host rewrites GUIDs. Feed-derived columns and user-state columns live in **separate tables**, so a refresh can never clobber played/position/download state. Episodes that drop out of the feed are kept and flagged `inFeed = false`.

6. **Every episode gets a computed `sortDate`.**
   * `sortDate = min(pubDate ?: firstSeenAt, firstSeenAt + 24h)`, with a `feedOrder` tiebreaker.
   * This is what makes the cross-podcast **group feeds** (requirement 2) sort correctly even with missing or future-dated `pubDate`s.
   * The "new" flag is set only on non-initial refreshes and is suppressed for back-catalogue dumps.

7. **Refresh.**
   * One unique periodic WorkManager job (`refresh-periodic`) acts as a "tick". The interval is user-selectable, default 4 h (open question). Constraints: `CONNECTED` (or `UNMETERED` per setting) and `BatteryNotLow`.
   * Each feed has its own `nextRefreshAt`. The engine refreshes only feeds that are due, which gives per-feed backoff and per-feed cadence:
     * slower for `itunes:complete` / `updateFrequency complete="true"` and dormant feeds;
     * honours `Retry-After` and `Cache-Control: max-age`.
   * Pull-to-refresh, either "this group" or "all", enqueues a unique **expedited** one-time job (`refresh-now`) that forces those feeds due.
   * Parallelism: 6 feeds globally, 2 per host.
   * A time budget of about 8 minutes per run keeps us under the 10-minute worker limit. Feeds left over are picked up by a chained continuation.

8. **Feed moves.**
   * Update the stored URL only for an unbroken chain of **301/308** from the original URL, and only after the target parses as a feed.
   * Follow `itunes:new-feed-url` only after the new URL fetches and parses, and only if it isn't self-referential. 99% Invisible's `new-feed-url` points at itself (tested).
   * Keep old URLs in an alias table for OPML/dedupe.

9. **Auth.**
   * Support HTTP Basic, including `user:pass@` in pasted or OPML URLs. Strip it into a credential store encrypted with an Android-Keystore AES-GCM key. `androidx.security:security-crypto` is deprecated.
   * Use an OkHttp interceptor that adds credentials only for the same origin. The same interceptor serves enclosure streaming and downloads.
   * Treat token-in-URL premium feeds (Patreon, Supercast and similar) as secrets: never send them to directory APIs, redact them in logs.

10. **Paged feeds (RFC 5005).** On subscribe, follow `atom:link rel="next"` in the background up to a cap (e.g. 50 pages / 5,000 items). On refresh, fetch only page 1. Offer a manual "load older episodes" action.

11. **Show notes.**
    * At ingest, store the raw HTML plus a 200-character plain-text snippet.
    * At display time, sanitise with **jsoup 1.23.2** using `Safelist.basicWithImages()` plus a few block tags, and drop tracking pixels.
    * Render with a small custom jsoup-DOM → Compose renderer (paragraphs, headings, lists, links, images via Coil). Timestamps like `12:34` become seek links.
    * `AnnotatedString.fromHtml` (Compose UI ≥ 1.7) is the fallback for simple inline text only.
    * Treat YouTube and other plain-text descriptions as text plus linkify.

12. **Discovery.**
    * Pluggable `PodcastSearchProvider`s, queried in parallel and merged (dedupe by normalised feed URL or `podcast:guid`):
      * **Apple iTunes Search API**: default, no key, ~20 calls/min.
      * **fyyd**: no key, good for German and European shows.
      * **Podcast Index**: only when a key is available.
    * Browse/charts: Apple's marketing-tools RSS (top 100 per country), plus the legacy genre top-lists, which map nicely onto group names (Technology 1318, News 1489, Fiction 1483). Both are verified working but are not contractual.
    * **Do not use gpodder.net for discovery.** It answers, but its toplist still returns shows that ended a decade ago (tested). Mention gpodder/Nextcloud sync only as a future option.

13. **Podcast Index key.** Its ToS says "Developer credentials may not be embedded in open source projects."
    * Inject the key at build time from a Gradle property or CI secret. It is never committed.
    * Builds without a key hide the provider.
    * Let advanced users paste their own key in settings.
    * Ask Podcast Index for written permission to ship a key in release builds. AntennaPod commits a default key in its Gradle file, which suggests tolerance, but don't rely on it.

14. **Add by URL is one pipeline:**
    * normalise the scheme (`feed:`, `pcast:`, `podcast:`, `itpc:`);
    * recognise known hosts (Apple Podcasts `/id\d+` → lookup; Overcast `itunes\d+`; YouTube → YouTube module);
    * GET the URL and sniff the body;
    * if it is a feed, preview it;
    * if it is HTML, run RSS autodiscovery (`<link rel="alternate">`), then Apple-Podcasts links in `<a href>`, then common paths;
    * if several feeds turn up, show a chooser;
    * show a preview card, then subscribe.

    Many real podcast sites have **no** autodiscovery link. 99percentinvisible.org has none, but it links to its Apple Podcasts page (tested), so the Apple-link fallback matters.

---

## Options considered

### A. Feed parser

| Option | Licence / status | Pros | Cons | Verdict |
|---|---|---|---|---|
| **Hand-written `XmlPullParser`** (platform KXmlParser) | Platform; kxml2 2.3.0 for JVM tests (BSD / public domain) | Streaming, low memory on 3.5 MB feeds. No runtime dependency. Full control of namespaces, encodings, entities, `relaxed` mode, multiple enclosures and Podcasting 2.0. Shares a style with the OPML parser | About 1.5–2.5 k lines of code plus tests. We own every quirk. Platform kxml and kxml2 differ slightly, so run golden tests under Robolectric or instrumentation too | **Chosen** |
| prof18 **RSS-Parser** 6.1.8 | Apache-2.0. Active: 6.1.6 / 6.1.7 / 6.1.8 in Jun–Jul 2026. KMP | Mature for RSS, Atom and RDF. iTunes fields including `new-feed-url`. YouTube Atom fields. Can inject an `OkHttpClient`. Infers charset | No `podcast:` namespace at all. Only one `rawEnclosure`. Dates as strings. No paging links. Matches literal prefixes `"itunes:…"`, so a feed declaring the iTunes namespace with another prefix silently loses data. Parses from a `String` or URL, not a stream. Ownership of the data model sits outside our control | Rejected. Note as a fallback for a quick prototype only |
| ROME / rome-modules 2.1.0 | Apache-2.0. Last release 2023-03 | Many modules | JDOM/DOM: whole-document trees for 3.5 MB feeds on a phone. Stale. Heavy dependency graph | Rejected |
| xmlutil (pdvrieze) 1.0.x serialization | Apache-2.0 | KMP, typed | Schema-driven deserialisation hates messy feeds (unknown elements, wrong nesting, mixed content) | Rejected for feeds |
| Porting AntennaPod's parser | **GPL-3.0** | Battle-tested namespace handlers | Can't be copied into an Unlicense codebase without making the whole app GPL. Study its *behaviour* only | Reference only |

### B. HTTP caching strategy for feeds

| Option | Pros | Cons |
|---|---|---|
| **Manual validators in DB** (chosen) | Exact control. Validators are saved only after a successful parse and commit. A 304 skips everything. Validators can be invalidated when the parser version changes or the URL moves. No duplicate copy of 3.5 MB bodies in an HTTP cache. Pull-to-refresh is never served a stale cached copy | A few lines of code |
| OkHttp `Cache` (as `stack.md` suggests) | Free ETag/Last-Modified handling | Duplicates every feed body on disk. Honours `max-age`: simplecast sends `max-age=3600` (tested), so a pull-to-refresh within the hour would silently return the cached body unless every call sets `CacheControl.FORCE_NETWORK`, which disables validators anyway. Validators get stored even when our parse fails. Cache eviction is opaque |

Recommendation: keep OkHttp's `Cache` for **images and small JSON** (Coil has its own disk cache anyway). Build feed requests with `cacheControl(CacheControl.FORCE_NETWORK)` and our own validator headers. The plan should reconcile this with `stack.md` line 139.

### C. Show-notes rendering

| Option | Pros | Cons |
|---|---|---|
| `AnnotatedString.fromHtml` (Compose UI, since 1.7.0-alpha07) | One line. Clickable links via `LinkInteractionListener` | Built on the framework `Html` conversion: limited block support, no images (`<img>` should be stripped first), nested lists only in a specific shape. A whole show note becomes one giant `Text`, which is poor for long notes and accessibility |
| **jsoup clean → custom DOM → Compose blocks** (chosen) | Proper paragraphs, headings, lists, images (Coil, size-capped, click-to-load if the product owner wants privacy), timestamp seek links, selectable text, lazy rendering in a `LazyColumn` | About 300–400 lines of code |
| WebView | Full fidelity | Heavy, slow in lists, styling and theming mismatch, security surface (needs JS off and URL interception), Android 17 ECH / WebView quirks. Overkill |

### D. Background refresh mechanism

| Option | Pros | Cons |
|---|---|---|
| **WorkManager periodic tick + per-feed `nextRefreshAt`** (chosen) | Survives reboot. Constraints. Backoff. Observable progress. Hilt `@HiltWorker`. 15-minute minimum interval is fine | Android 16 runtime quotas apply to all jobs (including expedited jobs, and jobs running alongside the playback FGS). Long OPML imports must chunk |
| Per-feed periodic WorkRequests | Simple mental model | Hundreds of jobs; JobScheduler limits; no global parallelism control |
| AlarmManager + FGS | Exact timing | Needs exact-alarm permission, FGS type justification, battery impact. Not needed for podcasts |
| Push (WebSub hub / Podping) | Near-real-time | Needs a server we don't have. Store `hub` and `usesPodping` for the future |

### E. Directories

| Directory | Auth | Strengths | Weaknesses | Use |
|---|---|---|---|---|
| **Apple iTunes Search/Lookup** | None | Best coverage. `feedUrl`, `artworkUrl600`, genres, `trackCount`, `releaseDate` (tested). Batch lookup `id=a,b` (tested). Charts | "Approximately 20 calls per minute (subject to change)". Terms oriented at promoting Apple content. Some results lack `feedUrl` (1 of 100 for "news" and for "fiction", tested). Effective result cap observed at 100 even with `limit=200` (tested). Responds `Content-Type: text/javascript` (tested) | **Default** |
| **Podcast Index** | Key + secret, SHA-1 header auth | Open, `podcastGuid`, `dead` flag, `medium`, `byfeedurl` / `byguid` / `byitunesid`, trending, value-for-value. Spec v1.12.1 | ToS forbids credentials embedded in open-source projects. Rate limits undocumented (**could not verify**) | Optional (build-time or user key) |
| **fyyd** (api.fyyd.de/0.2) | None for `/search/*`, `/podcast/*`, `/episode/*` | No key. Strong DACH coverage. Rich image sizes. Paging metadata | Small operator. No published rate limits or terms (**could not verify**). `Cache-Control: no-store` (tested) | Optional, on by default for de/at/ch locales (open question) |
| gpodder.net | None for search/toplist | Public search works (HTTP 200, tested) | Toplist data is a decade stale (top entry: "Linux Outlaws", tested). Project has been in "basic maintenance mode" and overloaded for years | **Not used** for discovery. Sync only as a future option |
| Listen Notes / Spotify / Pod Engine | Commercial keys | — | Paid, proprietary, keys can't ship in open source | Not considered |

---

## Technical detail

### 1. Module & component layout (feeds part only)

```
feeds/                     (pure Kotlin/JVM, no Android deps)
  model/                   ParsedFeed, ParsedEpisode, ParsedEnclosure, … (immutable data classes)
  parse/                   FeedParser, namespace handlers, FeedDates, Durations, Entities
  identity/                EpisodeKeys, UrlNormalizer, PodcastGuid (UUIDv5)
  discovery/               Autodiscovery (jsoup), UrlRecognizers, PodcastSearchProvider + impls
  html/                    ShowNotesSanitizer (jsoup), PlainTextSnippet, TimestampLinkifier
core/data (Android)        FeedRepository, FeedFetcher (OkHttp), FeedRefresher (engine),
                           Room entities/DAOs, CredentialStore (Keystore)
core/work (Android)        RefreshWorker (@HiltWorker), RefreshScheduler
feature/discover           Search UI, Add-by-URL UI, preview card
feature/episode            ShowNotes composable renderer
```

The parser takes an `XmlPullParser` factory, so JVM tests run with kxml2 and the device uses the platform parser:

```kotlin
fun interface PullParserFactory { fun create(): XmlPullParser }
// device: PullParserFactory { android.util.Xml.newPullParser() }   // KXmlParser
// tests:  PullParserFactory { org.kxml2.io.KXmlParser() }           // net.sf.kxml:kxml2:2.3.0
```

### 2. Fetch pipeline

```kotlin
const val FEED_ACCEPT =
    "application/rss+xml, application/atom+xml;q=0.9, application/xml;q=0.8, text/xml;q=0.8, */*;q=0.5"
const val MAX_FEED_BYTES = 32L * 1024 * 1024

sealed interface FetchOutcome {
    data class NotModified(val maxAge: Duration?) : FetchOutcome
    data class Body(
        val file: File, val sha256: ByteString, val requestedUrl: HttpUrl, val finalUrl: HttpUrl,
        val permanentUrl: HttpUrl?,       // set only for an unbroken 301/308 chain
        val etag: String?, val lastModified: String?, val contentType: MediaType?,
        val maxAge: Duration?,
    ) : FetchOutcome
    data class Http(val code: Int, val retryAfter: Duration?, val wwwAuthenticate: String?) : FetchOutcome
    data class Network(val error: IOException) : FetchOutcome
    data object TooLarge : FetchOutcome
}

suspend fun FeedFetcher.fetch(feed: FeedFetchState): FetchOutcome = withContext(Dispatchers.IO) {
    val useValidators = feed.parserVersion == FeedParser.VERSION && feed.lastParseOk
    val request = Request.Builder()
        .url(feed.url)
        .header("Accept", FEED_ACCEPT)
        .cacheControl(CacheControl.FORCE_NETWORK)          // never served from OkHttp Cache
        .apply {
            if (useValidators) {
                feed.etag?.let { header("If-None-Match", it) }
                feed.lastModified?.let { header("If-Modified-Since", it) }
            }
        }
        .tag(FeedAuth::class.java, credentialStore.forUrl(feed.url))  // read by AuthInterceptor
        .build()
    try {
        client.newCall(request).executeAsync().use { resp ->    // okhttp-coroutines; cancellable
            when {
                resp.code == 304 -> FetchOutcome.NotModified(
                    resp.cacheControl.maxAgeSeconds.takeIf { it >= 0 }?.seconds)
                !resp.isSuccessful -> FetchOutcome.Http(resp.code, resp.retryAfter(), resp.header("WWW-Authenticate"))
                else -> resp.body.source().use { src -> bufferToTempFile(src, resp) }
            }
        }
    } catch (e: IOException) { FetchOutcome.Network(e) }
}

/** Walks the redirect chain from the ORIGINAL request; returns the target of the last
 *  consecutive permanent redirect (301/308) starting at hop 0, or null. */
fun Response.permanentRedirectTarget(): HttpUrl? {
    val hops = generateSequence(priorResponse) { it.priorResponse }.toList().asReversed()
    var target: HttpUrl? = null
    for (hop in hops) {
        if (hop.code != 301 && hop.code != 308) break
        target = hop.header("Location")?.let { hop.request.url.resolve(it) } ?: break
    }
    return target
}
```

Details:

* **gzip.** Do *not* set `Accept-Encoding` yourself. OkHttp's BridgeInterceptor adds `gzip` and decompresses transparently only when the caller did not set it. Brotli and zstd are available as optional OkHttp modules but are unnecessary.
* **Timeouts.** `connectTimeout(15s)`, `readTimeout(30s)`, `callTimeout(120s)` on a `newBuilder()` derived from the shared client, so the connection pool is shared.
* **Size cap.** Count bytes while copying `source` into the temp file. Abort above `MAX_FEED_BYTES`, which guards against misconfigured servers streaming endlessly. Compute SHA-256 on the fly with Okio `HashingSink.sha256(fileSink)`.
* **Sniff before parse.** Skip the BOM and whitespace, then look at the first non-comment tag:
  * `<rss`, `<feed`, or `<rdf:RDF` → parse;
  * `<html` or `<!DOCTYPE html` → `NotAFeed(html=true)`. In the add flow this means "run autodiscovery". In refresh it means "error, keep old data". It is typically a captive portal, a parked domain or a login page.
* **Response codes.**
  * 410 → mark the feed `gone`, stop auto-refresh, surface it in the UI.
  * 401/403 with `WWW-Authenticate: Basic` → `needsCredentials`.
  * 404 → backoff; after N consecutive failures (e.g. 10 over ≥7 days) flag it as "possibly dead".
  * 429/503 → honour `Retry-After`.
  * Other 5xx → backoff.
* **Validators.** Store `etag` and `lastModified` **only after** the parse and DB commit succeed. Clear them when the URL changes or `FeedParser.VERSION` is bumped. A parser upgrade must be able to re-ingest feeds that are otherwise 304 forever.
* **Cleartext.** Many feeds and enclosures are still `http://`. For targetSdk ≥ 28 cleartext is blocked by default, so Neutrodyne needs `network_security_config.xml` with `<base-config cleartextTrafficPermitted="true">`. When a user types a scheme-less URL, try `https://` first.
* **User CAs (self-hosted feeds).** Apps targeting API ≥ 24 do not trust user-installed CAs. Self-hosters with private CAs will fail unless we add `<certificates src="user"/>` to the trust anchors. This is a product decision (open question).
* **LAN feeds on Android 17.** With targetSdk 37, connections to local-network addresses (RFC 1918, `.local`) **require the runtime `ACCESS_LOCAL_NETWORK` permission** in the `NEARBY_DEVICES` group. Without it, TCP connects typically **time out**, with no clear error. Detect feed/enclosure hosts that resolve to private ranges and request the permission in context ("This feed is on your local network…").
* **Certificate Transparency.** CT is enforced by default for targetSdk 37. This should be invisible for publicly trusted certificates. Add a per-domain opt-out only if real reports arrive.

### 3. Parsing

#### 3.1 Parser setup

```kotlin
fun newFeedPullParser(factory: PullParserFactory, input: InputStream, charset: String?): XmlPullParser =
    factory.create().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        // kxml-specific: tolerate undeclared prefixes, unescaped '&', bad attributes, unknown entities
        runCatching { setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        setInput(input, charset)   // charset == null => BOM sniffing + <?xml encoding="…"?>, default UTF-8
        HtmlEntities.ALL.forEach { (name, text) -> defineEntityReplacementText(name, text) } // &nbsp; &eacute; …
    }
```

Facts, verified in AOSP `KXmlParser.java`:

* `setInput(InputStream, null)` detects UTF-32/UTF-16 BOMs and the XML-declaration encoding, defaulting to UTF-8.
* `relaxed` downgrades errors such as "undefined prefix" and "unresolved entity" to warnings. Unresolved entities stay as literal `&name;` text.
* `defineEntityReplacementText` lets us predefine HTML entities.

Encoding fallback:

* `InputStreamReader` silently replaces malformed bytes with U+FFFD; it does not throw.
* After parsing, if more than ~0.5% of text characters are U+FFFD, re-parse the temp file with:
  * the HTTP `Content-Type` charset, if it differs; else
  * `windows-1252`, the classic mislabelled-Latin-1 case.
* Keep whichever parse has fewer replacement characters.

Mixed content: inside `<description>` publishers use CDATA, escaped HTML, or raw unescaped HTML tags. With namespace processing on, raw `<p>` inside `<description>` arrives as child elements. The text collector for "HTML-bearing" elements must therefore **re-serialise child elements** back to markup rather than drop them. Keep a small `collectInnerXml()` helper that rebuilds `<tag attr="…">…</tag>`.

#### 3.2 Namespace registry

| Key | Canonical URI | Also accept |
|---|---|---|
| RSS 2.0 | `""` (no namespace) | — |
| ITUNES | `http://www.itunes.com/dtds/podcast-1.0.dtd` | Any case variant (e.g. `…/DTDs/Podcast-1.0.dtd`). If the prefix `itunes` is used but undeclared, use the prefix fallback |
| PODCAST | `https://podcastindex.org/namespace/1.0` | `https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md`. The spec says clients **must** treat it as identical, and pc20.xml uses it (tested). Also tolerate `http://` and a trailing `/` |
| ATOM | `http://www.w3.org/2005/Atom` | — |
| CONTENT | `http://purl.org/rss/1.0/modules/content/` | — |
| MEDIA | `http://search.yahoo.com/mrss/` | Without the trailing slash |
| DC | `http://purl.org/dc/elements/1.1/` | — |
| PSC | `http://podlove.org/simple-chapters` | — |
| FH (feed history, RFC 5005) | `http://purl.org/syndication/history/1.0` | — |
| SY | `http://purl.org/rss/1.0/modules/syndication/` | — |
| YT | `http://www.youtube.com/xml/schemas/2015` | — |
| GOOGLEPLAY | `http://www.google.com/schemas/play-podcasts/1.0` | Fallback image/description only |
| RDF / RSS 1.0 | `http://www.w3.org/1999/02/22-rdf-syntax-ns#`, `http://purl.org/rss/1.0/` | Minimal support |

Matching rule: `canonical(namespaceUri) ?: prefixFallback[prefix]`, where the prefix fallback covers `itunes`, `podcast`, `media`, `content`, `atom` and `psc` for feeds that forget the `xmlns` declaration.

#### 3.3 Field mapping (precedence left → right)

**Channel**

| Model field | Sources |
|---|---|
| `title` | `title` › `itunes:title` › atom `title` |
| `author` | `itunes:author` › `dc:creator` › atom `author/name` › `itunes:owner/itunes:name` › `managingEditor` |
| `descriptionHtml` | `description` › `itunes:summary` › `content:encoded` › atom `subtitle` › `googleplay:description` |
| `link` | `link` › atom `link[rel=alternate]` |
| `artwork` | See §7 |
| `language` | `language` (normalise `en-us` → BCP-47 `en-US`) |
| `categories` | `itunes:category@text` (nested → "Parent › Child") |
| `explicit` | `itunes:explicit` ∈ {yes, true, explicit} → true; {no, false, clean} → false |
| `showType` | `itunes:type` (`episodic` / `serial`). Use it as the default sort for the podcast screen; group feeds always sort by date |
| `complete` | `itunes:complete == "yes"` OR `podcast:updateFrequency@complete == "true"` |
| `newFeedUrl` | `itunes:new-feed-url` (ignore if it equals the current URL after normalisation) |
| `podcastGuid` | `podcast:guid` (validate 8-4-4-4-12 hex). If absent, compute UUIDv5 locally (§5.4) but flag it as *derived* |
| `locked` | `podcast:locked` (+`@owner`). Informational only; it targets hosting platforms, not players |
| `medium` | `podcast:medium` (default `podcast`; store unknowns verbatim; `*L` list media and `music` may need different UI) |
| `funding[]` | `podcast:funding@url` + text (≤128 chars) |
| `persons[]` | `podcast:person` (role default `host`, group default `cast`, `img`, `href`) |
| `updateFrequency` | `podcast:updateFrequency` text + `@rrule` + `@dtstart` + `@complete` |
| `trailers[]` | `podcast:trailer` (optional UI) |
| `paging` | `atom:link[rel=next/first/last/prev-archive]`, `fh:complete`, `fh:archive` |
| `hubUrl`, `usesPodping` | `atom:link[rel=hub]`, `podcast:podping@usesPodping` (stored for the future) |
| `ttlMinutes` | RSS `ttl`, `sy:updatePeriod` × `sy:updateFrequency` (scheduling hints) |

**Item / entry**

| Model field | Sources |
|---|---|
| `guid` | `guid` (any `isPermaLink`) › atom `id` |
| `title` | `title` › `itunes:title` › `media:title` |
| `pubDate` | `pubDate` › atom `published` › atom `updated` › `dc:date` |
| `descriptionHtml` | `content:encoded` › `description` › `itunes:summary` › atom `content` (honour `type=text/html/xhtml`) › atom `summary` › `media:description` (plain text) |
| `enclosures[]` | Every `enclosure@url,type,length` › atom `link[rel=enclosure]` › `media:content[@medium=audio/video or type audio/*,video/*]` (also inside `media:group`) |
| `primaryEnclosure` | First enclosure whose type (or extension-inferred type) is audio/video. Any `podcast:alternateEnclosure[@default=true]` overrides the transport only, not the episode identity |
| `alternateEnclosures[]` | `podcast:alternateEnclosure@type,length,bitrate,height,lang,title,rel,codecs,default` with `source@uri,contentType`[] and `integrity@type,value` |
| `durationMs` | `itunes:duration` (see Durations) › `media:content@duration` |
| `season`, `seasonName` | `podcast:season` (+`@name`) › `itunes:season` |
| `episodeNumber`, `episodeDisplay` | `podcast:episode` (decimal, +`@display`) › `itunes:episode` |
| `episodeType` | `itunes:episodeType` ∈ full / trailer / bonus |
| `explicit` | `itunes:explicit` |
| `image` | See §7 |
| `link` | `link` › atom `link[rel=alternate]` |
| `chaptersUrl`, `chaptersType` | `podcast:chapters@url,type`. Accept `application/json+chapters` **and** `application/json`. pc20.xml itself uses the latter (tested) |
| `inlineChapters[]` | `psc:chapters/psc:chapter@start,title,href,image` (start in Normal Play Time: `hh:mm:ss.mmm`, `mm:ss`, or seconds) |
| `transcripts[]` | `podcast:transcript@url,type,language,rel`. Accept `application/srt` as a synonym of `application/x-subrip` (pc20.xml uses it, tested) |
| `persons[]` | `podcast:person`. Item-level persons **replace** channel persons, per spec |
| `funding[]` | Item-level `podcast:funding` |

Atom-only feeds (including YouTube channel feeds) map via `feed/entry`, `id`, `link`, `published`/`updated`, `content`/`summary`, `media:group/*`. YouTube feeds have **no enclosure**. The YouTube module turns `yt:videoId` into a playable source. In the ingestion core they are "items without enclosure but with `externalMediaId`".

#### 3.4 Normalised model (pure Kotlin)

```kotlin
data class ParsedFeed(
    val format: FeedFormat,                // RSS2, ATOM, RDF
    val title: String?, val author: String?, val descriptionHtml: String?, val link: String?,
    val language: String?, val artwork: ArtworkCandidates, val categories: List<String>,
    val explicit: Boolean?, val showType: ShowType?, val complete: Boolean,
    val newFeedUrl: String?, val podcastGuid: String?, val locked: Boolean?, val lockedOwner: String?,
    val medium: String?, val funding: List<Funding>, val persons: List<Person>,
    val updateFrequency: UpdateFrequency?, val ttlMinutes: Int?,
    val paging: Paging, val hubUrl: String?, val usesPodping: Boolean,
    val items: List<ParsedEpisode>,        // in document order
    val warnings: List<ParseWarning>,      // unknown date formats, bad URLs, dup guids… (debug screen)
)

data class ParsedEpisode(
    val guid: String?, val title: String?, val pubDate: Instant?, val rawPubDate: String?,
    val descriptionHtml: String?, val link: String?,
    val enclosures: List<Enclosure>, val alternateEnclosures: List<AlternateEnclosure>,
    val durationMs: Long?, val season: Int?, val seasonName: String?,
    val episodeNumber: BigDecimal?, val episodeDisplay: String?, val episodeType: EpisodeType?,
    val explicit: Boolean?, val artwork: ArtworkCandidates, val chapters: ChaptersRef?,
    val inlineChapters: List<InlineChapter>, val transcripts: List<TranscriptRef>,
    val persons: List<Person>?, val funding: List<Funding>,
    val externalMediaId: String?,          // e.g. YouTube videoId
    val feedOrder: Int,
)
data class Enclosure(val url: String, val type: String?, val length: Long?)
data class Paging(val next: String?, val first: String?, val last: String?, val prevArchive: String?, val complete: Boolean)
```

#### 3.5 Dates

Facts about `java.time` (**tested** on JDK 21; the same API is on Android 26+):

* `DateTimeFormatter.RFC_1123_DATE_TIME` **rejects** a wrong weekday ("Thu, 2 Oct 2026", which was a Friday) with "Conflict found".
* It **rejects** `PDT`, `UT`, `Z` and ISO-8601.

Real feeds contain all of these. The recipe below parsed every variant in the test set:

```kotlin
object FeedDates {
    private val zones = mapOf("UT" to "+0000", "UTC" to "+0000", "GMT" to "+0000", "Z" to "+0000",
        "EST" to "-0500", "EDT" to "-0400", "CST" to "-0600", "CDT" to "-0500",
        "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700")
    private val rfc822ish = DateTimeFormatterBuilder().parseCaseInsensitive()
        .appendPattern("d MMM [yyyy][yy] H:mm[:ss][.SSS]")
        .optionalStart().appendLiteral(' ').appendOffset("+HHMM", "+0000").optionalEnd()
        .optionalStart().appendLiteral(' ').appendOffset("+HH:MM", "Z").optionalEnd()
        .parseDefaulting(ChronoField.OFFSET_SECONDS, 0)          // no zone => UTC
        .toFormatter(Locale.ENGLISH)

    fun parse(raw: String?): Instant? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var t = s.replace(Regex("\\s+"), " ")
            .replaceFirst(Regex("^[A-Za-z]{2,}\\.?,? "), "")     // drop (possibly wrong/localised) weekday
        t = localizedMonths.normalize(t)                            // "Okt"→"Oct", "Mai"→"May", "janv."→"Jan" …
        Regex(" ([A-Za-z]{1,4})$").find(t)?.let { m ->
            zones[m.groupValues[1].uppercase()]?.let { t = t.substring(0, m.range.first) + " " + it }
        }
        return runCatching { OffsetDateTime.parse(t, rfc822ish).toInstant() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()          // ISO-8601 / RFC 3339
            ?: runCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC) }.getOrNull()
            ?: runCatching { LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
    }?.takeIf { it.isAfter(EPOCH_1990) && it.isBefore(Instant.now().plus(365, ChronoUnit.DAYS)) }
}
```

Always store the raw string too (`rawPubDate`), so a later parser version can re-derive it.

#### 3.6 Durations

`itunes:duration` appears as `HH:MM:SS`, `H:MM:SS`, `MM:SS`, `M:SS` (Apple's documented forms), plain seconds (`3177`), seconds with a fraction, `"00:52:57"` (tested), empty, or garbage. Parse it as follows:

* split on `:` and accept up to 3 numeric parts;
* allow a fractional last part;
* reject anything negative or longer than 48 h.

Treat the result as a *hint*. The player's measured duration supersedes it once known (the playback area should write back `measuredDurationMs`).

#### 3.7 Enclosure type inference

`type` is often missing or wrong (`audio/mp3`, `audio/x-m4a`, `application/octet-stream`, and the reference feed's `application.x-mpegURL` with a dot, tested). Normalise known aliases, and if the type is still not audio/video, infer it from the URL path extension (`.mp3 .m4a .aac .ogg .opus .mp4 .m4v .webm .m3u8`). `length="0"` or a missing length means unknown.

### 4. Persisting & diffing

#### 4.1 Tables (Room 3; ingestion-owned columns only)

* `podcast`:
  * identity and source: `id`, `sourceType` (RSS | YOUTUBE), `feedUrl` (unique, normalised), `podcastGuid`, `podcastGuidDerived`;
  * metadata: `title`, `author`, `descriptionHtml`, `link`, `language`, `artworkUrl`, `explicit`, `showType`, `medium`, `locked`, `complete`, `updateRrule`, `ttlMinutes`;
  * paging: `pagingNextUrl`, `pagingComplete`;
  * HTTP validators: `etag`, `lastModified`, `contentSha256`, `parserVersion`, `lastParseOk`;
  * scheduling and errors: `lastAttemptAt`, `lastSuccessAt`, `nextRefreshAt`, `failureCount`, `lastErrorKind`, `lastErrorDetail`, `gone`, `needsCredentials`;
  * other: `credentialId`, `subscribedAt`, `latestEpisodeAt`.
* `podcast_url_alias(podcastId, url)`: every URL this podcast was ever known by (redirects, `new-feed-url`, OPML imports).
* `episode`:
  * identity: `id`, `podcastId`, `identityKey` (unique with `podcastId`), `guid`;
  * content: `title`, `pubDate`, `rawPubDate`, `sortDate`, `feedOrder`, `firstSeenAt`, `lastSeenAt`, `inFeed`, `isNew`;
  * media: `enclosureUrl`, `enclosureType`, `enclosureLength`, `externalMediaId`, `durationMs`;
  * numbering: `season`, `seasonName`, `episodeNumber`, `episodeDisplay`, `episodeType`;
  * extras: `explicit`, `imageUrl` (null when equal to the podcast artwork), `link`, `chaptersUrl`, `chaptersType`, `snippet` (≤200 chars, plain text), `contentHash`.
  * Indexes: `(podcastId, sortDate DESC)` and `(sortDate DESC)` for the "All" feed. Group feeds join `podcast_group_member` (owned by the groups area) on `podcastId`.
* `episode_description(episodeId PK, html)`: kept separate so list queries never load 50 KB show notes.
* `episode_transcript`, `episode_alt_enclosure` (sources as a JSON column), `episode_inline_chapter`, `person(ownerType, ownerId, …)`, `funding(ownerType, ownerId, url, label)`.
* **User state is not here.** `episode_state` (played, position, starred, download state) is owned by the playback and download areas and keyed by `episode.id`. Ingestion never writes to it.

Real-world check: in 99% Invisible, 652 of 831 item `itunes:image` values equal the channel art (tested). Storing `imageUrl = null` in that case saves space and lets the UI reuse the cached podcast cover.

#### 4.2 Identity keys

```kotlin
fun EpisodeKeys.primary(e: ParsedEpisode): String = when {
    !e.guid.isNullOrBlank()            -> "g:" + e.guid.trim()
    e.primaryEnclosureUrl != null      -> "u:" + UrlNormalizer.forIdentity(e.primaryEnclosureUrl)
    !e.title.isNullOrBlank() && e.pubDate != null ->
        "t:" + sha1(e.title.trim().lowercase() + "|" + e.pubDate.truncatedTo(ChronoUnit.DAYS))
    !e.link.isNullOrBlank()            -> "l:" + sha1(e.link.trim())
    else                               -> "h:" + sha1(e.title.orEmpty() + e.descriptionHtml.orEmpty().take(500))
}
```

`UrlNormalizer.forIdentity` does the following:

* lowercase the scheme and host; treat `http` and `https` as equal;
* drop the default port and the fragment;
* **keep** the query string, which is often essential, but also index a *query-less* variant as a weak match;
* do **not** strip analytics prefixes (`dts.podtrac.com/redirect.mp3/…`, `chrt.fm/track/…`, `pdst.fm/e/…`). They change often, so they are only a weak signal.

#### 4.3 Diff algorithm (per feed, one write transaction)

1. Load the existing rows for the podcast into maps:
   * `byKey`;
   * `byEnclosure`, keyed by the normalised URL and by the query-less URL;
   * `byTitleDay`.
2. Track keys already seen in this parse. If a guid repeats within the same document (a broken generator), the second occurrence falls back to `"u:"` + its enclosure URL.
3. Match each parsed item in this order:
   * by `byKey[primary]`;
   * else by `byEnclosure[...]`, but only if that row was **not** matched in this pass;
   * else by `byTitleDay[...]`, under the same condition.

   A fallback match means the host rewrote the guid during a migration. Apple explicitly warns that changed GUIDs cause duplicates. In that case, update `identityKey` and `guid` on the existing row, which preserves the user's played and download state.
4. For matched rows, update the feed-derived columns only if `contentHash` changed (hash of the normalised fields). Set `inFeed = true` and `lastSeenAt = now`. If the enclosure URL changed and a download exists, keep the file; the download area decides whether to re-download.
5. Insert the unmatched items:
   * `firstSeenAt = now`;
   * `sortDate = min(pubDate ?: firstSeenAt, firstSeenAt + 24h)`;
   * `isNew = !initialFetch && (pubDate ?: now) >= previousLatestEpisodeAt - 7.days`. This suppresses back-catalogue dumps and old episodes that a host re-adds.
6. Mark rows not seen in this parse as `inFeed = false`, **only if** the parse produced at least one item and paging is not partial. Never delete them during ingestion. A separate cleanup policy (open question) removes `inFeed = false` episodes that are unplayed, not downloaded and not starred after N days.
7. Update the podcast row, validators and `latestEpisodeAt`, and emit a `NewEpisodes(podcastId, ids)` event for auto-download and notifications.

Performance: an 831-item feed means about 831 lookups in in-memory maps plus batched upserts in one transaction. Hashing makes "nothing changed" refreshes nearly free. On a full 200 with an identical body SHA-256, skip steps 1–7 entirely.

#### 4.4 Podcast-level dedupe

When the user subscribes, check in this order:

1. the normalised URL against `podcast.feedUrl` and `podcast_url_alias`;
2. after the preview parse, `podcastGuid` (a real, non-derived one) against existing podcasts;
3. the final URL after redirects.

If any of these match, offer "Already subscribed". The same check applies to OPML import.

### 5. Feed moves, auth, paging, podcast:guid

#### 5.1 Moves

* **301/308**: if `permanentRedirectTarget()` is non-null **and** the parse succeeded with at least one item:
  * set `feedUrl = target`;
  * add the old URL to `podcast_url_alias`;
  * clear the validators.

  Ignore 302/303/307: captive portals and CDNs use them.
* **`itunes:new-feed-url`**: if present and it normalises to something different from the current URL:
  * fetch the new URL; require that it parses as a feed;
  * require that it is *plausibly the same show*: equal `podcastGuid`, or title similarity ≥ 0.8, or a shared episode guid;
  * then switch as above.

  Otherwise record `pendingNewFeedUrl` and retry on later refreshes. Limit to 5 hops and detect loops. Apple asks publishers to keep both the 301 and the tag for at least four weeks, so lazy adoption is fine.
* Show a small "Feed moved to …" line in podcast info. This matters for transparency when a feed is hijacked.

#### 5.2 HTTP Basic auth

* **Input.** Accept `https://user:pass@host/feed` (common in OPML), or prompt after a 401 with `Basic` in `WWW-Authenticate`. Store the credentials and strip them from `feedUrl`.
* **Storage.** Encrypt with AES-256-GCM using an Android Keystore key (`KeyGenParameterSpec`, `PURPOSE_ENCRYPT|DECRYPT`). Persist `{iv, ciphertext}` in a Room table `credential(id, origin, username, secretBlob)`. Don't use `EncryptedSharedPreferences`: all of `androidx.security:security-crypto` is deprecated (1.1.0, 2025).
* **Interceptor.** An application interceptor adds `Authorization: Basic …` **preemptively**, only when the request URL's origin equals the credential origin. OkHttp itself strips `Authorization` on cross-host redirects. The same interceptor is installed on the client used by Media3 `OkHttpDataSource` and the downloader, because enclosures of protected feeds are often on the same host.
* **Never** send credentialed or token URLs to Podcast Index `add/byfeedurl`, fyyd or any other third party. Redact the userinfo and query strings of feeds in logs and crash reports. OPML export asks "Include passwords/private feed tokens?" and defaults to no; the export side is owned by the OPML area.

#### 5.3 Paged feeds (RFC 5005)

* **Paged feeds (§3):** `atom:link rel="first|next|previous|last"`.
* **Archived feeds (§4):** `rel="prev-archive"` plus `fh:archive`.
* **Complete feeds (§2):** `fh:complete`.
* **On subscribe:** after page 1 is stored, enqueue a `FetchOlderPagesWorker` that follows `next` or `prev-archive` with these safeguards:
  * max 50 pages or 5,000 items;
  * visited-URL loop detection;
  * stop if a page yields no new keys.
* **On refresh:** fetch page 1 only. If `fh:complete` is present, items absent from the feed really are gone.
* **UI:** "Load older episodes" when `pagingNextUrl != null`.
* When paging is partial, do not mark episodes from older pages as `inFeed = false`.

#### 5.4 podcast:guid derivation

Verified against both spec examples (`917393e3-…`, `9b024349-…`):

```kotlin
private val PODCAST_NS = UUID.fromString("ead4c236-bf58-58c6-a2c6-a6b28d128cb6")

fun derivePodcastGuid(feedUrl: String): UUID {
    val seed = feedUrl.replaceFirst(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "").trimEnd('/')
    val sha1 = MessageDigest.getInstance("SHA-1").run {
        update(ByteBuffer.allocate(16).putLong(PODCAST_NS.mostSignificantBits)
            .putLong(PODCAST_NS.leastSignificantBits).array())
        update(seed.toByteArray(Charsets.UTF_8)); digest()
    }
    sha1[6] = (sha1[6].toInt() and 0x0f or 0x50).toByte()   // version 5
    sha1[8] = (sha1[8].toInt() and 0x3f or 0x80).toByte()   // IETF variant
    val bb = ByteBuffer.wrap(sha1, 0, 16)
    return UUID(bb.long, bb.long)
}
```

Use it only as a stable local and sync key when the feed has no `podcast:guid`. Never write it back as if it were canonical.

### 6. Chapters & transcripts (lazy)

* Store only the references at ingest time.
* Fetch the chapters JSON (`{version, chapters:[{startTime, title?, img?, url?, toc?, endTime?, location?}]}`) when:
  * the episode detail opens;
  * playback starts;
  * a download completes (cache it beside the file for offline use).
* Hide chapters with `toc:false` from the list, but keep them for artwork changes.
* `psc:chapters` are already inline.
* Transcripts: prefer `text/vtt`, then `application/x-subrip` / `application/srt`, then `application/json`, then `text/html`, then `text/plain`. Choose by the `language` attribute, falling back to the channel language. `rel="captions"` implies timecodes.
* All of this belongs to the playback area's UI. Ingestion only guarantees that the references exist.

### 7. Artwork selection

**Podcast cover** (first hit wins; record the source for debugging):

1. `itunes:image@href` on the channel. Some broken feeds put the URL as element text, so accept text too.
2. `podcast:image` where `purpose` contains `artwork` and the aspect is `1/1`, or no aspect is given (the spec says no aspect implies square). Choose the largest `width`.
3. Deprecated `podcast:images@srcset`: pick the largest. pc20.xml still uses it, 127 times (tested).
4. `media:thumbnail` or `media:content[medium=image]` on the channel.
5. RSS `image/url`. This is often a small logo; the RSS 2.0 spec caps it at 144 px wide.
6. Atom `logo`, then `icon`.
7. `googleplay:image@href`.
8. The directory result's art (Apple `artworkUrl600`, fyyd `imgURL`, Podcast Index `artwork`), kept from the search hit.
9. A generated placeholder: the title initials on a colour derived from the title hash.

**Episode art:**

1. item `itunes:image@href` (or its text);
2. `podcast:image` with the same rules as above;
3. `media:thumbnail`, or `media:group/media:thumbnail` for YouTube;
4. otherwise null, which means "use the podcast cover".

Covers are typically 1400–3000 px square; Apple's spec is 1400–3000 px, JPEG/PNG, RGB. Coil must always decode at the display size; that is the UI area's job. Also capture `podcast:image purpose~="canvas|banner|social"` (16:9, 9:16) for a hero header. That is optional at v1.

### 8. Show notes: sanitise & render

```kotlin
object ShowNotesSanitizer {
    private val safelist = Safelist.basicWithImages()
        .addTags("h1", "h2", "h3", "h4", "h5", "h6", "div", "hr", "figure", "figcaption")
        .addAttributes("img", "alt", "width", "height")
        .removeEnforcedAttribute("a", "rel")
    fun clean(html: String, baseUri: String): Document {
        val cleaned = Jsoup.clean(html, baseUri, safelist, Document.OutputSettings().prettyPrint(false))
        return Jsoup.parseBodyFragment(cleaned, baseUri).also { doc ->
            doc.select("img").filter { it.isTrackingPixel() }.forEach { it.remove() }   // 1×1, known trackers
            doc.select("p:matchesOwn(^\\s*$)").filter { it.childrenSize() == 0 }.forEach { it.remove() }
        }
    }
}
```

**Input normalisation, at ingest or display:**

* If the text contains no tags but does contain `&lt;p&gt;`-style escapes, unescape it once. This handles double-escaped feeds.
* If it has no tags at all (YouTube, many Atom feeds), treat it as plain text: split on blank lines into paragraphs, turn `\n` into `<br>`, and linkify URLs.
* Precompute `snippet`: the jsoup `text()` collapsed to ≤ 200 characters, used for list rows.

**Renderer** (Compose, in `feature/episode`):

* Walk the cleaned DOM into a `List<NoteBlock>`: `Paragraph(AnnotatedString)`, `Heading(level, AnnotatedString)`, `ListBlock(ordered, items)`, `Image(url, alt, w, h)`, `Rule`, `Quote`.
* Inline spans: `b/strong`, `i/em`, `u`, `code`, `a` → `LinkAnnotation.Url`, `br`.
* Render blocks in the parent `LazyColumn`.
* Timestamp linkify: the regex `\b(?:(\d{1,2}):)?([0-5]?\d):([0-5]\d)\b` outside existing links becomes `LinkAnnotation.Clickable("seek:<ms>")`, which seeks if this episode is the one playing.
* Links open in a Custom Tab.
* Images are loaded through Coil. "Load images in show notes" is a setting, because images leak IP addresses to trackers.

### 9. Refresh scheduling

```kotlin
// RefreshScheduler — called on app start, after settings change, after OPML import.
fun schedulePeriodic(interval: Duration, wifiOnly: Boolean) {
    val req = PeriodicWorkRequestBuilder<RefreshWorker>(interval.toJavaDuration(), (interval / 3).toJavaDuration())
        .setConstraints(Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
        .build()
    workManager.enqueueUniquePeriodicWork("refresh-periodic", ExistingPeriodicWorkPolicy.UPDATE, req)
}

fun refreshNow(scope: RefreshScope /* All | Group(id) | Podcasts(ids) */) {
    val req = OneTimeWorkRequestBuilder<RefreshWorker>()
        .setInputData(scope.toData(force = true))
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
        .build()
    workManager.enqueueUniqueWork("refresh-now", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
}

@HiltWorker
class RefreshWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters, private val refresher: FeedRefresher,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val deadline = TimeSource.Monotonic.markNow() + 8.minutes
        val report = refresher.refreshDue(
            scope = RefreshScope.from(inputData), deadline = deadline,
            onProgress = { done, total -> setProgress(workDataOf("done" to done, "total" to total)) })
        if (report.remaining > 0) refresher.enqueueContinuation()   // one-time, 1 min delay, KEEP
        return Result.success()   // per-feed failures are tracked per feed, not via WorkManager retry
    }
}
```

**Engine** (`FeedRefresher.refreshDue`):

* A `Mutex` ensures only one engine run at a time, because periodic, manual and OPML import all share it.
* Select feeds where `!gone && (force || nextRefreshAt <= now)`. Order them by `lastSuccessAt` ascending so starved feeds go first.
* Fan out with `Semaphore(6)` globally and a per-host `Semaphore(2)`.
  * This avoids hammering hosts like simplecast or megaphone that serve dozens of a user's feeds.
  * OkHttp's own dispatcher defaults are 64 total and 5 per host.
* Parse on `Dispatchers.Default.limitedParallelism(2)`, because parsing is CPU-bound and large feeds produce garbage. Fetch on IO.
* Each feed is an independent transaction, so cancellation (`isStopped`, Android 16 quota stop) loses at most the in-flight feeds.

**`nextRefreshAt` policy:**

| Condition | Next refresh |
|---|---|
| Success | `now + userInterval`, stretched to `max(userInterval, 24h)` if `complete` or no new episode in 180 days. If `ttl`/`max-age` exceeds `userInterval`, use `min(that, 24h)` |
| Failure | `now + min(30min · 2^(failures−1), 24h) · jitter(0.8–1.2)`, or `Retry-After` if larger |
| 410 | `gone = true`. No further auto-refresh |
| 401 | `needsCredentials = true`. No auto-refresh until the user acts |
| Forced (pull-to-refresh) | Ignores `nextRefreshAt`, but still uses conditional GET |

**Other triggers:**

* On app foreground, if the last global refresh is older than the interval, call `refreshNow(All)` without force. Due feeds only.
* On subscribe, fetch synchronously in the add flow for the preview, then mark the feed due for paging.
* OPML import: insert rows with `nextRefreshAt = now` and let the engine work through them. With 300 feeds at 6 in parallel and ~1–2 s each, that is about 1–2 minutes, which fits the budget. Show progress from `WorkInfo.progress`.

**Platform facts that shape this:**

* The periodic minimum is 15 minutes.
* Expedited work has a quota and falls back via `OutOfQuotaPolicy`.
* Workers have a 10-minute execution window unless they run as foreground.
* Android 16 applies runtime quotas by standby bucket, to jobs started while the app is on top that keep running after it goes to the background, and to jobs running alongside a foreground service. Playback is an FGS, so refresh during playback is quota-limited.
* `WorkInfo.getStopReason()` exists for diagnosing stops.
* WorkManager 2.11.1–2.11.2 fixed network-constraint bugs on Android 15+ ("connectivity blocked"), so require ≥ 2.12.0, which also raises minSdk to 24 (fine with minSdk 26).

**Notifications for new episodes** are optional and per podcast or per group. They need `POST_NOTIFICATIONS` (API 33+). Ingestion only emits the event.

### 10. Discovery

#### 10.1 Provider abstraction

```kotlin
interface PodcastSearchProvider {
    val id: ProviderId                      // APPLE, FYYD, PODCAST_INDEX
    val enabled: Boolean
    suspend fun search(query: String, country: String, limit: Int = 50): List<SearchHit>
}
data class SearchHit(
    val feedUrl: String, val title: String, val author: String?, val artworkUrl: String?,
    val description: String?, val episodeCount: Int?, val lastEpisodeAt: Instant?,
    val podcastGuid: String?, val provider: ProviderId, val providerId: String,
    val genres: List<String> = emptyList(), val explicit: Boolean? = null,
)
// Aggregator: run enabled providers in parallel with an 8 s timeout each, keep partial results,
// dedupe by UrlNormalizer.forIdentity(feedUrl) or podcastGuid, preserve first provider's order,
// annotate hits already subscribed.
```

#### 10.2 Apple iTunes Search API

* **Search:** `GET https://itunes.apple.com/search?media=podcast&entity=podcast&term=<q>&country=<cc>&limit=50`.
* **Lookup:** `GET https://itunes.apple.com/lookup?id=<id>[,<id>…]&entity=podcast` (batch tested).
* **Fields used:** `collectionId`, `collectionName`, `artistName`, `feedUrl`, `artworkUrl600`, `trackCount`, `releaseDate`, `genres`, `collectionExplicitness`.
* **Filter** out hits with no `feedUrl`; these are Apple-hosted or subscription shows.
* **Parse** with kotlinx-serialization (`ignoreUnknownKeys = true`), whatever the `text/javascript` content type says.
* **Rate limit:** "approximately 20 calls per minute (subject to change)". Use a client-side token bucket of 20/min shared by search and lookup.
  * Search on IME "search", or after a 600 ms debounce with ≥ 3 characters.
  * Cache results in memory (LRU, 30 min).
  * If Apple returns 403 or 429, back off for 60 s and show "Search is busy, try again".
* **Country:** use the device locale's country, because storefronts differ.
* **Charts:**
  * `https://rss.marketingtools.apple.com/api/v2/<cc>/podcasts/top/<n>/podcasts.json`. Works for n=100 but not 200 (tested). It returns ids only, so batch-lookup the feed URLs.
  * Per-genre: legacy `https://itunes.apple.com/<cc>/rss/toppodcasts/limit=<n>/genre=<id>/json` (tested with 1318 Technology, 1489 News, 1483 Fiction, 1303 Comedy). It is undocumented, so wrap it to fail soft.
  * When a user creates a group named like a genre, offer "Popular in <genre>" suggestions.
* **Terms:** the page is framed around promoting Apple content and the affiliate program. Every major open-source player uses it for directory search, but Apple grants no explicit licence for that use (open question / risk note).

#### 10.3 Podcast Index

* Base URL: `https://api.podcastindex.org/api/1.0`.
* Headers on every request:
  * `User-Agent: Neutrodyne/<ver>`;
  * `X-Auth-Key: <key>`;
  * `X-Auth-Date: <unix seconds>` (3-minute window, so device clock skew matters; retry once using the server `Date` header);
  * `Authorization: sha1hex(key + secret + date)` (lowercase hex).
* Without a key the API answers 403 (tested).
* Endpoints:
  * `search/byterm?q=&max=&similar`;
  * `podcasts/byfeedurl`, `podcasts/byguid`, `podcasts/byitunesid`;
  * `podcasts/trending`;
  * `episodes/byfeedid`;
  * `categories/list`.
* Filter out `dead == 1`. Optionally down-rank `medium` values other than `podcast` and `video`.
* **Key handling:**
  * `local.properties` / env var → `BuildConfig.PODCASTINDEX_KEY` / `_SECRET`; if absent, the provider is disabled.
  * A Settings → "Use my own Podcast Index API key" pair, stored with the Keystore scheme from §5.2.
  * Ask Podcast Index for permission to ship a key in official builds.
  * Note: the secret is extractable from any APK. That is acceptable for a free API, but it is the reason for their clause.

#### 10.4 fyyd

* `GET https://api.fyyd.de/0.2/search/podcast?title=<q>&count=<n>` (also `term`, `url`, `language`). No auth for `/search/*`, `/podcast/*`, `/episode/*`.
* Response fields used: `data[].title`, `xmlURL`, `htmlURL`, `imgURL`, `smallImageURL`/`thumbImageURL`, `language`, `status`, plus `meta.paging`.
* Prefix every path with `/0.2/`, otherwise you get v0.1.
* The documentation lives on Codeberg (last commit 2026-03-28). No published rate limit.

#### 10.5 Add by URL / share target / deep links

```kotlin
sealed interface AddResolution {
    data class Feed(val url: String, val preview: ParsedFeed) : AddResolution
    data class Choose(val candidates: List<DiscoveredFeed>) : AddResolution
    data class YouTube(val input: String) : AddResolution            // hand off to YouTube module
    data class Failure(val reason: AddFailure) : AddResolution       // NotAFeed, NoAudio, Http(code), …
}
```

Pipeline:

1. **Normalise input.**
   * Trim the text.
   * Unwrap these forms:
     * `feed:https://…`, `feed://`, `pcast://`, `podcast://`, `itpc://`, which become `https://` (fall back to `http://` on connect failure);
     * `subscribeonandroid.com/<url>`;
     * `antennapod.org/deeplink/subscribe?url=`;
     * `?url=` parameters on known subscribe pages.
   * Add `https://` if there is no scheme.
2. **Recognise hosts.**
   * `podcasts.apple.com/…/id(\d+)`, `itunes.apple.com/…/id(\d+)`, `pod.link/(\d+)`, `overcast.fm/itunes(\d+)` → iTunes lookup → `feedUrl`.
   * `youtube.com`, `youtu.be`, `m.youtube.com` → YouTube module.
   * `podcastindex.org/podcast/(\d+)` → PI `byfeedid` if a key exists.
   * `fyyd.de/podcast/<slug>` → fyyd API.
   * `open.spotify.com/show/…` → there is no RSS. Explain this, and offer to search for the page's `og:title`.
3. **Fetch and sniff** using the fetch pipeline.
   * **Feed:** parse and preview. Warn "No audio episodes found" when no item has an enclosure. A WordPress blog's main feed is the classic case: the podcast feed is usually `/feed/podcast`.
   * **HTML:** autodiscovery with jsoup:
     * candidates are `head link[rel~=(?i)alternate][type~=(?i)(rss|atom)\+xml|application/x-rss\+xml|text/xml][href]`, resolved against `<base href>` or the page URL;
     * drop comment feeds (`/comments/feed`, a title containing "Comments");
     * rank candidates whose title or URL contains `podcast`, `audio` or `mp3` first;
     * if there are none, scan `a[href]` for Apple Podcasts links (→ lookup) and for feed-looking URLs (`\.rss$|\.xml$|/feed/?$|/rss/?$|feeds\.|feed\.`);
     * if there are still none, probe `/feed/podcast`, `/podcast.xml`, `/feed`, `/rss`, `/rss.xml` with `HEAD`/`GET` and sniff;
     * exactly one candidate → preview it; several → `Choose`.
4. **Dedupe** (§4.4), then subscribe.

Manifest (owned with the UI area). These intent filters let browsers and other apps hand feeds to us:

```xml
<!-- Feed and podcast URL schemes -->
<intent-filter>
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:scheme="feed"/><data android:scheme="pcast"/>
    <data android:scheme="podcast"/><data android:scheme="itpc"/>
</intent-filter>
<!-- Share text/URL into the app -->
<intent-filter>
    <action android:name="android.intent.action.SEND"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <data android:mimeType="text/plain"/>
</intent-filter>
<!-- Optional: open Apple Podcasts links. These are not App Links; the user picks the app. -->
<intent-filter>
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:scheme="https" android:host="podcasts.apple.com"/>
</intent-filter>
<!-- Also: VIEW for application/rss+xml and application/atom+xml mime types (content:// and http(s)) -->
```

### 11. Testing strategy (ingestion)

* **Golden corpus** in `feeds/src/test/resources/feeds/`:
  * the anonymised real feeds above;
  * edge-case fixtures:
    * an undeclared `itunes:` prefix;
    * the GitHub-alias podcast namespace;
    * `&nbsp;` outside CDATA;
    * a windows-1252 body with a UTF-8 declaration;
    * a UTF-16 BOM;
    * duplicate guids;
    * missing guids;
    * multiple enclosures;
    * `media:content` only;
    * Atom with `link rel=enclosure`;
    * a YouTube Atom feed;
    * RFC 5005 pages;
    * `psc:chapters`;
    * every date variant listed in §3.5.
* **Snapshot assertions:** the `ParsedFeed` serialised to JSON and compared.
* **Diff tests:** scripted sequences such as "subscribe → feed v2 with rewritten guids → feed v3 with removed items", checking that user state survives.
* **Fetch tests** with MockWebServer: 304, 301 chain, 302, 410, 401 + Basic, `Retry-After`, a gzip body, an oversized body, an HTML body.
* Run the corpus once under Robolectric or instrumentation with the **platform** parser, to catch kxml2-vs-AOSP differences.
* Optional, behind a flag: a debug-only screen listing `ParseWarning`s per feed.

### 12. Future: sync

* gpodder.net's API (subscriptions + episode actions) is the de-facto protocol.
* The public server is unreliable. Self-hosted options are the **Nextcloud gPodder Sync** app and standalone gPodder-compatible servers such as goPodder.
* The Open Podcast API effort (AntennaPod, Kasts, Funkwhale and others, from Oct 2022) aims at a successor.
* Design now for later:
  * stable podcast keys (`podcastGuid` or normalised URL);
  * episode keys (guid + enclosure URL, which is what the gpodder episode actions use);
  * `podcast_url_alias`.

  Nothing else is needed at v1.

---

## Verified versions & facts

| Item | Value | Source | Checked |
|---|---|---|---|
| prof18 RSS-Parser | **6.1.8**, published 2026-07-30 (6.1.7 2026-07-07, 6.1.6 2026-06-28). Apache-2.0. KMP | https://repo1.maven.org/maven2/com/prof18/rssparser/rssparser/maven-metadata.xml · https://github.com/prof18/RSS-Parser | 2026-10-04 |
| RSS-Parser lacks the Podcasting 2.0 namespace; matches literal `itunes:` prefixes; single `rawEnclosure` | Keyword list and `RssItem` model in source | https://raw.githubusercontent.com/prof18/RSS-Parser/master/rssparser/src/commonMain/kotlin/com/prof18/rssparser/internal/RssKeyword.kt · …/model/RssItem.kt | 2026-10-04 |
| ROME | 2.1.0, last release 2023-03 | https://repo1.maven.org/maven2/com/rometools/rome/maven-metadata.xml | 2026-10-04 |
| AntennaPod licence | GPL-3.0 (don't copy code) | https://raw.githubusercontent.com/AntennaPod/AntennaPod/develop/LICENSE | 2026-10-04 |
| AntennaPod ships a default Podcast Index key in its Gradle file | `buildConfigField "PODCASTINDEX_API_KEY" …` fallback | https://raw.githubusercontent.com/AntennaPod/AntennaPod/develop/net/discovery/build.gradle | 2026-10-04 |
| WorkManager | **2.12.0** (2026-09-23), minSdk raised to 24. 2.11.x fixed network-constraint issues on Android 15+ | https://developer.android.com/jetpack/androidx/releases/work | 2026-10-04 |
| WorkManager limits | 15-minute periodic minimum; expedited quota + `OutOfQuotaPolicy`; backoff min 10 s, default exponential 30 s | https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work | 2026-10-04 |
| Worker 10-minute limit; long-running workers need an FGS type; Android 16 long-running workers consume job quota | — | https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running | 2026-10-04 |
| Android 16 job runtime quotas (standby bucket, top-state start, concurrent FGS); `getStopReason()` | — | https://developer.android.com/about/versions/16/behavior-changes-all | 2026-10-04 |
| Android 17 = API 37, stable 2026-06-16 | — | https://en.wikipedia.org/wiki/Android_17 | 2026-10-04 |
| Android 17 (target 37): `ACCESS_LOCAL_NETWORK` runtime permission, `NEARBY_DEVICES` group; applies to OkHttp; TCP failures are typically timeouts | — | https://developer.android.com/privacy-and-security/local-network-permission · https://developer.android.com/about/versions/17/behavior-changes-17 | 2026-10-04 |
| Android 17 (target 37): Certificate Transparency on by default; cleartext off by default since target 28; user CAs not trusted since target 24 | — | https://developer.android.com/privacy-and-security/security-config | 2026-10-04 |
| Play target API: new apps and updates must target API 36 from 2026-08-31 | — | https://developer.android.com/google/play/requirements/target-sdk | 2026-10-04 |
| OkHttp | **5.5.0** (2026-08-16): opt-in ECH on Android 17, new DNS API. 5.4.0 (2026-06-08), 5.3.0 `Call` tags. Apache-2.0 | https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md · Maven Central | 2026-10-04 |
| jsoup | **1.23.2** (2026-08-26), MIT. Safelist `basic` / `basicWithImages` / `relaxed` tag sets | https://repo1.maven.org/maven2/org/jsoup/jsoup/ · https://jsoup.org/apidocs/org/jsoup/safety/Safelist.html | 2026-10-04 |
| Compose | BOM **2026.09.00** (2026-09-09) → ui / ui-text **1.12.1**. `AnnotatedString.fromHtml(html, linkStyles, linkInteractionListener)` first appears in the 1.7.0-alpha07 notes | https://dl.google.com/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom · https://developer.android.com/jetpack/androidx/releases/compose-ui · https://developer.android.com/develop/ui/compose/text/style-text | 2026-10-04 |
| Room 3 | `androidx.room3` **3.0.3** (2026-09-09); Room 2.8.5 same day | https://dl.google.com/android/maven2/androidx/room3/room3-runtime/maven-metadata.xml | 2026-10-04 |
| kotlinx-serialization-json | **1.11.0** (2026-04-09) | Maven Central | 2026-10-04 |
| Coil 3 | **3.6.3** (2026-09-18) | Maven Central | 2026-10-04 |
| kxml2 (JVM tests) | `net.sf.kxml:kxml2:2.3.0` resolves on Maven Central | https://repo1.maven.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.pom | 2026-10-04 |
| Platform KXmlParser | Supports the `relaxed` feature; `setInput(is, null)` sniffs BOM + XML declaration; `defineEntityReplacementText` | https://android.googlesource.com/platform/libcore/+/refs/heads/main/xml/src/main/java/com/android/org/kxml2/io/KXmlParser.java | 2026-10-04 |
| `security-crypto` (`EncryptedSharedPreferences`) | Deprecated (1.1.0-beta01, 2025-06-04; stable 1.1.0 2025-07-30) → use Keystore directly | https://developer.android.com/jetpack/androidx/releases/security | 2026-10-04 |
| Podcast namespace | URI `https://podcastindex.org/namespace/1.0`; GitHub-URL alias must be treated as identical; tag list; `images` deprecated in favour of `image` | https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md | 2026-10-04 |
| `podcast:guid` | UUIDv5, namespace `ead4c236-bf58-58c6-a2c6-a6b28d128cb6`, seed = URL minus scheme and trailing slashes. Examples reproduced | https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/guid.md | 2026-10-04 |
| `podcast:chapters` / JSON chapters 1.2 | `url`, `type`; `startTime` required; `toc:false` = hidden | …/docs/tags/chapters.md · …/docs/examples/chapters/jsonChapters.md | 2026-10-04 |
| `podcast:transcript` | `url`, `type`, optional `language`, `rel="captions"`; multiple per item | …/docs/tags/transcript.md | 2026-10-04 |
| `funding`, `person`, `season`, `episode`, `alternateEnclosure`/`source`/`integrity`, `locked`, `medium`, `image`, `updateFrequency`, `podping` | Attributes and semantics as summarised in §3.3 | …/docs/tags/*.md | 2026-10-04 |
| Real-world quirks in the Podcasting 2.0 reference feed (pc20.xml) | GitHub alias namespace; `application.x-mpegURL`; `application/srt`; chapters typed `application/json`; deprecated `podcast:images` | https://feeds.podcastindex.org/pc20.xml (fetched) | 2026-10-04 |
| feeds.podcastindex.org rejects generic User-Agents with 403 ("Sample code UA strings, default http library UA strings and generic or vague UA strings are not allowed") | — | Fetched with curl | 2026-10-04 |
| 99% Invisible feed | 3.5 MB (378 KB gzip), 831 items; `ETag` (weak) + `Last-Modified`; 304 on both validators; `Cache-Control: max-age=3600`; self-referential `itunes:new-feed-url` | https://feeds.simplecast.com/BqbsxVfO (fetched) | 2026-10-04 |
| YouTube channel feed | Atom + `yt:` + `media:`; 15 entries; no `ETag`/`Last-Modified`; `max-age=900`; plain-text descriptions | https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ (fetched) | 2026-10-04 |
| Podlove Simple Chapters | Namespace `http://podlove.org/simple-chapters`; `psc:chapter@start,title,href,image`; NPT times | https://podlove.org/simple-chapters/ | 2026-10-04 |
| Apple feed move guidance | 301 plus `<itunes:new-feed-url>` for ≥ 4 weeks; keep GUIDs stable | https://podcasters.apple.com/support/change-the-rss-feed-url | 2026-10-04 |
| Apple `itunes:duration` forms; artwork 1400–3000 px | — | https://help.apple.com/itc/podcasts_connect/en.lproj/itcb54353390.html | 2026-10-04 |
| iTunes Search API | No key; "approximately 20 calls per minute (subject to change)"; `limit` max 200 documented, but at most 100 results observed; `Content-Type: text/javascript`; some results lack `feedUrl`; batch lookup works | https://performance-partners.apple.com/search-api + live tests | 2026-10-04 |
| Apple charts | `rss.marketingtools.apple.com/api/v2/<cc>/podcasts/top/{≤100}/podcasts.json` works (200 fails); legacy per-genre `itunes.apple.com/<cc>/rss/toppodcasts/limit=N/genre=ID/json` works | Live tests | 2026-10-04 |
| Podcast Index API | Spec 1.12.1; base `https://api.podcastindex.org/api/1.0`; headers `User-Agent`, `X-Auth-Key`, `X-Auth-Date` (3-minute window), `Authorization = sha1(key+secret+date)` lowercase hex; 403 without key | https://podcastindex-org.github.io/docs-api/pi_api.json + live test | 2026-10-04 |
| Podcast Index ToS | "Developer credentials may not be embedded in open source projects." | https://github.com/Podcastindex-org/legal/blob/main/TermsOfService.md (§4.2.1) | 2026-10-04 |
| Podcast Index rate limits | **Not documented; could not verify** | — | 2026-10-04 |
| fyyd API | v0.2 at `https://api.fyyd.de/0.2/`; `/search/*` unauthenticated; docs on Codeberg (last commit 2026-03-28); no stated rate limits | https://codeberg.org/eazy/fyyd-api + live test | 2026-10-04 |
| gpodder.net | Search and toplist answer with 200, but toplist data is stale; history of overload / maintenance mode | Live test · https://forum.antennapod.org/t/gpodder-down/423 | 2026-10-04 |
| RSS autodiscovery | `<link rel="alternate" type="application/rss+xml" href title>` in `<head>`; first link = main feed; resolve relative against the page or `<base>` | https://www.rssboard.org/rss-autodiscovery | 2026-10-04 |
| RFC 5005 (Feed Paging and Archiving) | `first/next/previous/last`, `prev-archive`, `fh:complete`, `fh:archive` | https://www.rfc-editor.org/rfc/rfc5005 (from knowledge; spec is stable since 2007) | not re-fetched |
| `java.time` RFC 1123 strictness | Rejects a mismatched weekday and `PDT`/`UT`/`Z`/ISO forms; lenient recipe in §3.5 parses them all | Local JDK 21 test | 2026-10-04 |
| F-Droid NonFreeNet anti-feature | "apps that promote or depend entirely on a proprietary network service" | https://f-droid.org/docs/Anti-Features/ | 2026-10-04 |

---

## Pitfalls & edge cases

* **Never delete on bad input.** An HTML page, an empty `<channel>`, a parse exception or a truncated body must leave existing episodes and validators untouched. Only a successful parse with ≥ 1 item can flip `inFeed`.
* **GUID churn on host migration.** Hosts sometimes regenerate GUIDs, and Apple warns this causes duplicates. The secondary matching in §4.3 is essential. Without it, migrated feeds double every episode in the group feeds.
* **Duplicate GUIDs inside one feed**, for example a generator that uses the show URL as every item's guid. Detect this and fall back to the enclosure key. Also log a `ParseWarning`.
* **Enclosure URL churn.** Analytics prefixes and per-request tokens change on every fetch for some hosts. Don't use the URL as the primary key when a guid exists, and update the URL silently.
* **Future-dated and undated items.** The `sortDate` clamp and the `feedOrder` tiebreaker stop scheduled episodes from pinning to the top of a group feed, and stop undated feeds from collapsing into one timestamp.
* **Back-catalogue dumps** (a show re-publishing 300 old episodes) must not flood "new", notifications or auto-download. See the `isNew` rule.
* **Self-referential `itunes:new-feed-url`** is common (99% Invisible), so ignore it. A malicious or stale `new-feed-url` is handled by the validation plus plausibility checks.
* **302 to captive portals or login pages.** Never adopt URLs from temporary redirects. Never treat an HTML 200 as a feed during refresh.
* **Encoding lies.** The XML declaration and HTTP `charset` disagree. UTF-8 is declared but the body is windows-1252. Some feeds have a UTF-8 BOM plus a UTF-16 declaration. Handle all of these with the replacement-character heuristic and a re-parse from the temp file.
* **Undefined entities** (`&nbsp;`, `&rsquo;`) outside CDATA are fatal in strict XML. Use predefined HTML entities plus `relaxed`.
* **Raw HTML inside `<description>`** without CDATA arrives as child elements. Re-serialise, don't drop.
* **Namespace prefix games.** An undeclared `itunes:` prefix, a different prefix for the iTunes namespace, case-variant iTunes URIs, and the Podcast Index GitHub alias all occur. Match by URI first, then by prefix.
* **Feeds that are blog feeds.** Items without enclosures should be skipped from the episode list, not displayed as unplayable episodes. Keep them only if `externalMediaId` is set (YouTube).
* **Huge feeds** (multi-MB, 1,000+ items). Stream to a file, parse with a pull parser, keep descriptions in a separate table, and do the diff in memory with maps.
* **User-Agent gating.** Some hosts 403 generic UAs (tested). Others block unknown bots. Use a stable, descriptive UA.
* **Validator storage timing.** Saving an ETag before the DB commit can strand a feed in "304 forever" after a crash. Bump `FeedParser.VERSION` whenever parsing semantics change, so feeds re-ingest.
* **Weak ETags** (`W/"…"`) are fine for `If-None-Match`. Send them verbatim.
* **Clock skew.** Podcast Index auth has a 3-minute `X-Auth-Date` window, so retry using the server `Date`. `If-Modified-Since` must echo the server's own `Last-Modified` string, never the local clock.
* **Android 17 LAN feeds** fail as *timeouts* without `ACCESS_LOCAL_NETWORK`. Map timeouts to private-range hosts to a specific "needs local network permission" error.
* **Android 16 quotas.** A refresh started while the app is visible keeps running under quota after the user backgrounds it, and refresh during playback (FGS) is also quota-bound. The engine must be cancellation-safe per feed and resume in the next run.
* **WorkManager periodic drift.** A periodic job with flex can run up to `interval` late. That's fine. Never promise exact refresh times in the UI.
* **iTunes throttling.** Typing-as-you-search can exhaust ~20/min quickly, so debounce or submit-only, plus caching. Results lacking `feedUrl` must be filtered.
* **Paging loops.** Some servers return the same `next` forever, or `next` pointing at page 1. Keep a visited set and stop when a page yields no new keys.
* **Private feed URLs are secrets.** Keep them out of analytics, logs, crash reports, directory lookups and (by default) OPML exports.
* **Show-notes privacy.** Remote images and tracking pixels leak the listener's IP. Make loading images a setting. Remove 1×1 images.
* **`podcast:person` replace semantics.** Item-level persons replace channel persons; they do not merge.
* **`podcast:medium=music` / `*L` list feeds** may contain no regular items (list media hold `remoteItem`s only). Show a clear "This feed is a playlist of other feeds (not supported yet)" message instead of an empty podcast.
* **YouTube Atom feeds** contain only the latest 15 entries and carry no validators, and the channel-level `yt:channelId` lacks the `UC` prefix (tested). The YouTube module must not assume history comes from the feed.

---

## Open questions for the product owner

1. **Podcast Index.** Should we register a key and ask Podcast Index for written permission to embed it in official builds? Or ship with Apple and fyyd only, plus a "bring your own key" setting?
2. **Distribution channels.** Google Play, F-Droid, GitHub releases? This affects whether build-time secrets exist (F-Droid builds from source without our secrets) and how prominent the Apple directory may be (F-Droid "NonFreeNet" applies only if we *depend entirely* on it).
3. **Default search provider and privacy disclosure.** Is Apple-by-default acceptable, given that queries go to Apple? Should the provider be user-selectable? Should fyyd be on by default everywhere or only for German-speaking locales?
4. **Refresh defaults.** Interval (proposal: 4 h)? Wi-Fi-only by default? Refresh on app open? Should groups have their own refresh interval?
5. **New-episode notifications.** Off, per podcast, or per group by default?
6. **Removed-from-feed episodes.** Keep forever, or auto-remove after N days when unplayed, not downloaded and not starred?
7. **Back catalogue.** On subscribe, fetch all pages of paged feeds automatically (data use), or only on demand?
8. **Self-hosted / LAN feeds.** Support them (requires `ACCESS_LOCAL_NETWORK` on Android 17 and, optionally, trusting user-installed CAs)? This is a security trade-off.
9. **Show-notes images.** Load automatically, on Wi-Fi only, or tap-to-load (privacy)?
10. **Credentials in OPML export.** Allowed (opt-in) or never?
11. **Scope of Podcasting 2.0 UI at v1.** Ingestion stores funding, persons, transcripts, alternate enclosures and chapters. Which of these get UI in v1? Value-for-value (Lightning) is assumed out of scope.
12. **Music and list-medium feeds.** Should we support them at all, or show a "not supported" notice?
