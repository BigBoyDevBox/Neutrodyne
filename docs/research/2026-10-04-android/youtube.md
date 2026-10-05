# Research: YouTube channels as podcasts (Neutrodyne)

Research date: 2026-10-04. Everything marked **[tested]** was checked live from the research sandbox on that date with `curl` against youtube.com, or by reading the current source of the library named. Everything marked **[unverified]** could not be confirmed and should be treated as an assumption.

---

## Recommendation

### The short version

Split "YouTube support" into two layers. They carry very different technical, legal and licensing risk, so keep them in separate Gradle modules and ship them in separate product flavours.

| Layer | What it does | Licence of code | Ships in |
|---|---|---|---|
| **A: YouTube subscriptions** | Turns user input into a `UC…` channel ID. Polls YouTube's public Atom feeds. Shows the channel avatar as the podcast cover and video thumbnails as episode art. Handles groups, OPML export and import from Takeout, NewPipe and LibreTube. No API key and no stream extraction. | Unlicense (the repo's licence) | Every build, including Google Play |
| **B: YouTube playback and download** | Gets audio-only stream URLs (itag 140 AAC / 251 Opus) with **NewPipe Extractor**. Supports background playback in the normal podcast player, downloads, and SponsorBlock. | GPL-3.0-or-later (the module only) | `foss` flavour only: GitHub Releases, IzzyOnDroid and F-Droid (F-Droid will tag it `NonFreeNet`) |

Concrete choices:

1. **Episode listing:** YouTube's public Atom feed. By default use the long-form uploads playlist: `https://www.youtube.com/feeds/videos.xml?playlist_id=UULF<channel-id-without-UC>`. I tested this on 2026-10-04: it leaves out both Shorts and live streams. Fall back to `?channel_id=UC…` and filter Shorts client-side using the `/shorts/` link. Never depend on the YouTube Data API for listing.
2. **Stream extraction (foss only):** `com.github.teamnewpipe:NewPipeExtractor:v0.26.5` from JitPack, behind a small `YouTubeStreamResolver` interface defined in an Unlicense module. Do not use youtubedl-android/yt-dlp, which adds about 18 MB per ABI, a Python runtime and a JS runtime. Do not use public Piped or Invidious instances, which are unreliable and partly blocked.
3. **Playback:** episodes get a synthetic `yt://<videoId>` media URI. Media3 `ResolvingDataSource` turns it into a short-lived googlevideo URL just in time. Cache that URL until shortly before `expire`; I observed `expiresInSeconds = 21540` (about 6 h). Re-resolve on HTTP 403. Never persist stream URLs.
4. **Downloads (foss only):** resolve at start, fetch in ≤10 MiB `Range` chunks (yt-dlp uses the same `CHUNK_SIZE = 10 << 20`), and re-resolve on 403 to resume at the same offset. Default format is itag 140 (m4a/AAC, about 130 kbps), with itag 251 (Opus) or 249/250 (low-bitrate Opus) as options. Always prefer the **original** audio track over dubbed or auto-dubbed tracks.
5. **`play` flavour:** YouTube episodes are "external episodes". They have a cover, title, description and date. Tapping one opens the video in the YouTube app (`ACTION_VIEW https://www.youtube.com/watch?v=…`), or optionally in an in-app IFrame player that only plays in the foreground. There is no background audio, no download, and YouTube items are skipped by queue auto-advance.
6. **Licensing:** the repo stays Unlicense except for `:youtube-streams`, which is declared GPL-3.0-or-later. The `foss` APK as a whole is distributed under GPL-3.0-or-later. The Unlicense is GPL-compatible, so this is legally clean.
7. **Data API v3:** do not use it in the `foss` build, because using API Services while also downloading or background-playing would break the API terms and get the key revoked. In the `play` build it is optional (open question): it would only fill in durations and avatars.
8. **SponsorBlock:** optional, foss only. Use the privacy-preserving hash-prefix endpoint. The data is CC BY-NC-SA 4.0, so in-app attribution is needed.

### Risks that could change the overall plan

- **Google Play.** A Play build that extracts streams, downloads or background-plays YouTube is very likely to be rejected or removed. Play policy bans "Apps that access or use a service or API in a manner that violates its terms of service". NewPipe's README says putting NewPipe or any fork on Play violates Play's terms. If the product owner insists that YouTube audio works on Play, **the requirement cannot be met as stated.**
- **Fragile extraction.** In 2026 both NewPipe Extractor and yt-dlp fetch streams through a single undocumented InnerTube client, `VISIONOS`. YouTube has been retiring clients one by one, moving them to SABR-only or PoToken-required (WEB in 2025, android_vr in a March 2026 A/B test, ANDROID/IOS removed from NewPipe Extractor `dev` in August 2026 because nobody can generate PoTokens for them). When `VISIONOS` goes, extraction breaks until someone ships a SABR client. LibreTube has had one in development since July 2026. Plan for outages of days, a few times a year, and a fast release pipeline.
- **Legal exposure.** YouTube's ToS forbids downloading "except … as expressly authorized by the Service" and forbids automated access and circumvention. YouTube's lawyers sent Invidious a takedown demand in June 2023. Podcini's developer stopped the project on 2025-01-13 "to avoid suspicions and allegations of possible violation of some legal terms" and forked a YouTube-free successor.
- **Sideloading.** Android developer verification applies from 2026-09-30 in Brazil, Indonesia, Singapore and Thailand, and globally from 2027. The `foss` APK will need a verified developer identity unless users use the "advanced flow". F-Droid calls this an existential threat because it re-signs APKs, so we should aim for reproducible builds.
- **Atom feed reliability.** The Atom feed endpoint has had intermittent hours-long 404 outages since December 2025, still reported in spring 2026. Refresh logic must treat 404 as transient.

---

## Options considered

### 1. Where the episode list comes from

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Atom channel feed** `feeds/videos.xml?channel_id=UC…` | Public, no key, no quota. A stable, documented-by-usage format used by every RSS reader. Includes full description, thumbnail and view count. 15-minute cache headers. | Only the 15 newest entries **[tested]**. Includes Shorts and live streams **[tested]**. No duration. No channel image. No live, upcoming or members flags. Intermittent 404 outages. | Fallback |
| **Atom uploads-playlist variants** `feeds/videos.xml?playlist_id=UULF…` and similar | Same pros. `UULF` is long-form only, without Shorts or live **[tested]**. `UUSH` and `UULV` let us opt back in. | Undocumented naming convention that could change. Still 15 entries. | **Primary** |
| **YouTube Data API v3** `playlistItems.list` / `videos.list` | Official. Durations, `liveBroadcastContent`, privacy status, high-res thumbnails, pagination for the back catalogue. | Needs an API key embedded in the APK, which can be extracted. 10,000 units/day **per project, shared by all users**. API terms ban downloading, offline play, background play and separating audio, so it is incompatible with the `foss` flavour. Cached data must be refreshed within 30 days. | Optional for `play` only |
| **NewPipe Extractor channel tabs** (InnerTube `browse`) | Durations, `isShortFormContent`, `StreamType` (live/post-live), `ContentAvailability` (MEMBERSHIP/PAID/UPCOMING), back-catalogue paging. | Undocumented API that YouTube changes often: many 2026 extractor fixes were "lockupViewModel" parsing. GPL. Heavier per request. | Enrichment in `foss` only |
| **Piped / Invidious API** | One JSON call gives a list with durations. | Third-party servers. Invidious lists only 5 public instances "due to the recent YouTube issues". Google blocks instances by IP. Privacy and uptime are out of our control. | Rejected as default. Maybe an expert setting pointing at a self-hosted instance. |

### 2. Getting a playable audio stream

| Option | Licence | Size and runtime cost | Maintenance and robustness | Notes |
|---|---|---|---|---|
| **NewPipe Extractor v0.26.5** (Java library) | GPL-3.0-or-later | About 0.8 MB jar plus Rhino 1.27 MB, jsoup 0.54 MB, protobuf-javalite 1.0 MB and nanojson, before R8. Pure JVM with no native code. | Very active: 9 releases in 2026, roughly monthly, with hotfixes within days of breakage. Used by NewPipe, LibreTube and Tubular. | **Recommended for `foss`.** Needs `desugar_jdk_libs_nio` below minSdk 33. |
| **youtubedl-android 0.18.1** (yt-dlp, Python 3.12, QuickJS) | Wrapper is GPL-3.0. yt-dlp itself is Unlicense. | arm64 alone carries `libpython.zip.so` 14 MB, a 3.1 MB `ytdlp` binary and 0.9 MB QuickJS (ffmpeg adds 34 MB if needed). Unpacked on first run. Python start-up per call. | Last release 2025-11-16, so the bundled yt-dlp is old and it relies on yt-dlp's self-updater. yt-dlp now needs an external JS runtime for YouTube (EJS). | Rejected: size, latency, and the self-update mechanism would be a Play red flag even in a `play`-adjacent build. |
| **Chaquopy + yt-dlp** | MIT + Unlicense, so no GPL | Similar Python payload. Still needs a JS runtime for YouTube EJS. | We would own the integration. | A non-GPL route, but heavy. Chaquopy's MIT licence and v17.0.0 come from search results only **[unverified]**. |
| **YouTube.js 18.1.0 + googlevideo 4.1.1 + BgUtils 4.0.3** (JavaScript) | MIT | Needs a JS engine: a WebView or an embedded engine such as QuickJS. | Very active (commits September 2026). googlevideo implements SABR/UMP and BgUtils generates PoTokens, so it is ahead of NewPipe Extractor on SABR. | The only serious **non-GPL** path. Expensive to integrate on Android because of the networking bridge, CORS and threading. Keep as plan C behind the same interface. |
| **Piped/Invidious stream endpoints** | n/a | n/a | Instances die or get blocked. Media is often proxied through volunteers' bandwidth. | Rejected |
| **Official only:** YouTube app intent or IFrame player (`android-youtube-player` 13.0.0, MIT) | MIT | Small | Stable, Google-maintained player | Compliant, but no background, no audio-only, no download. This is the `play` behaviour. |

### 3. Licensing and distribution structure

Context: the repo is Unlicense. The FSF considers the Unlicense GPL-compatible, so Unlicense code can be combined into a GPL work. Including NewPipe Extractor in the same APK makes that APK a combined GPL-3.0-or-later work. The FSF says "If the modules are included in the same executable file, they are definitely combined in one program."

| Option | How it works | Pros | Cons |
|---|---|---|---|
| **(a) Relicense the whole repo to GPL-3.0-or-later** | Single codebase and licence. Distribute everywhere except that Play builds leave out extraction. | Simplest legal story. Code can be copied freely from NewPipe and LibreTube, which are GPL. | Gives up the product owner's chosen public-domain stance. Contributors' Unlicense dedications stay valid, so relicensing our own code is easy, but it is a values decision. |
| **(b) Flavours: `play` (Unlicense, no extractor) + `foss` (GPL APK)** **(recommended)** | `:youtube-streams` (GPL) is `fossImplementation` only. Everything else stays Unlicense. The `foss` APK is distributed under GPL-3.0-or-later with corresponding source = the public git tag. | Keeps the Unlicense for 95% of the code. One codebase. Play build is clean. F-Droid builds `foss`. | Two feature sets to test and explain. The README and About screen must state clearly which licence applies to which artefact. |
| **(c) Separate companion app ("Neutrodyne YouTube bridge", GPL)** | The Play main app talks to a sideloaded companion over AIDL or a bound service. The FSF regards pipes, sockets and command-line arguments as normally separate programs "unless the semantics … are intimate enough". Simple string or URL IPC is arm's length. | Main app can stay Unlicense **and** on Play while YouTube audio works for users who sideload the companion. | Play reviewers may still treat the main app as "facilitating" ToS violations: the `<queries>` entry and UI hooks are visible in the Play APK. Two apps to ship and sign. Sideloading friction grows with developer verification. Package-visibility and permission design is needed. Highest UX friction. |
| **(d) Official APIs only, everywhere** | Atom feeds plus open in YouTube or the IFrame player. | Zero legal or licensing risk. Unlicense everywhere. | Fails requirement 4 (streaming *as a podcast* and downloading) for YouTube. Only "subscribe and watch elsewhere". |

Recommendation: **(b)**, keeping (c) as a later option if Play users ask for it, and (a) if the product owner prefers one licence.

### 4. Prior art and lessons

- **NewPipe** (GPL-3.0, F-Droid with `NonFreeNet: Depends on Youtube for videos`). Its README says: "PUTTING NEWPIPE, OR ANY FORK OF IT, INTO THE GOOGLE PLAY STORE VIOLATES THEIR TERMS AND CONDITIONS." Ten releases between January and August 2026 (v0.28.1 to v0.29.1), many of them YouTube hotfixes: "Content not available" (0.28.2), SABR workaround (0.28.8 and 0.29.0). Lesson: budget for monthly extractor bumps and same-week hotfixes.
- **LibreTube** (GPL-3.0; F-Droid, IzzyOnDroid and GitHub only, not Play). Uses a fork, `com.github.libre-tube:NewPipeExtractor`. It ships a WebView BotGuard PoToken generator (`api/poToken/PoTokenWebView.kt`) and, since July 2026, its own Media3 **SABR** client (`player/parser/SabrClient.kt`, `UmpParser.kt`, commit `ad9df07` "use visionOS client for playback requests", `f7222c5` "add support for downloading SABR streams"). Lessons: SABR in Media3 is feasible, and it is the fallback if direct URLs disappear. LibreTube's backup format carries **channel groups**, which map directly onto our groups requirement.
- **Podcini** (an AntennaPod fork, GPL-3.0). Added YouTube and YouTube Music channels and playlists as podcasts, then **stopped development on 2025-01-13** "to avoid suspicions and allegations of possible violation of some legal terms". It continued as **Podcini.X** "with access to Youtube stripped off". Lesson: the legal risk is real enough that a solo developer walked away.
- **AntennaPod** (on Play). Accepts YouTube channel URLs as plain RSS feeds, but its docs say it "cannot automatically download videos because YouTube doesn't allow that". Maintainer ByteHamster (2025-01-04): "AntennaPod is a podcast player and those are not podcasts … [we] won't add additional features for them." Lessons: Atom subscriptions on Play are tolerated precedent, and the Atom feed has no channel image.
- **Podcast Addict** (on Play). Its privacy policy says YouTube channel subscriptions use **YouTube API Services** and bind users to YouTube's ToS and the API Services terms. In other words it took the official-API route. I could not confirm how it plays YouTube items (the FAQ returned 403) **[unverified]**, but compliance implies in-app embedded or YouTube-app playback. In 2015 it temporarily lost YouTube support when Google shut down the v2 API, a reminder that official APIs also change.
- **Seal** (yt-dlp GUI on youtubedl-android, GPL-3.0). GitHub and F-Droid only.
- **Podverse** (AGPL-3.0). I found no evidence of native YouTube channel support **[unverified]**.
- **Invidious.** YouTube legal demanded shutdown within 7 days (June 2023), citing the API policy. Invidious refused because it does not use the official API. Since 2024 its public instances have been heavily blocked.

---

## Technical detail

### 1. Turning user input into a channel ID (`UC…`)

**Validation:** a channel ID is 24 characters: `UC` plus 22 base64url characters encoding 128 bits. The final character can only be one of `A Q g w`, because its low 4 bits are padding.

```kotlin
val CHANNEL_ID = Regex("^UC[0-9A-Za-z_-]{21}[AQgw]$")
```

**Input classes and how to resolve each one** (hosts to accept: `youtube.com`, `www.`, `m.`, `music.youtube.com`, `youtu.be`, `youtube-nocookie.com`; always strip tracking parameters such as `si`, `pp` and `feature`):

| Input | Example | Resolution |
|---|---|---|
| Channel URL | `/channel/UCBJycsmduvYEL83R_U4JriQ` | Regex, no network |
| Feed URL | `feeds/videos.xml?channel_id=UC…` | Regex |
| Uploads playlist | `playlist?list=UU…`, `UULF…`, `UUSH…`, `UULV…`, `UUMO…` | `"UC" + id.drop(prefixLen)`, where prefixLen is 2 for `UU` and 4 for `UULF`/`UUSH`/`UULV`/`UUMO`/`UUPS`/`UULP`/`UUPV` |
| Handle | `@mkbhd`, `youtube.com/@mkbhd` | Network: see below |
| Legacy custom URL | `/c/mkbhd`, bare `/mkbhd` | Network |
| Legacy user | `/user/marquesbrownlee` | `feeds/videos.xml?user=marquesbrownlee` works **[tested]** and returns entries carrying `yt:channelId`. Otherwise use the same path as handles. |
| Video URL | `watch?v=ID`, `youtu.be/ID`, `/shorts/ID`, `/live/ID` | oEmbed → `author_url` (a handle URL) → handle path. Alternatively `<meta itemprop="channelId">` on the watch page. In foss, `StreamInfo.uploaderUrl`. |
| Plain text query | "marques brownlee" | foss: NewPipe Extractor search with the channels filter. play: no search. Prompt the user to *share* from the YouTube app or paste a link. Data API `search.list` now has its **own bucket of 100 calls/day per project**, which is useless for a shared key. |

**Handle and custom URL resolution, two methods (both [tested] 2026-10-04):**

1. *HTML autodiscovery*: works in every flavour and is what RSS readers do. `GET https://www.youtube.com/@mkbhd` with `Cookie: SOCS=CAE=` to skip the EU consent interstitial; NewPipe Extractor sends `SOCS=CAE=` ("reject all"). Then parse, in order of preference:
   - `<link rel="alternate" type="application/rss+xml" title="RSS" href="https://www.youtube.com/feeds/videos.xml?channel_id=UC…">`
   - `<link rel="canonical" href="https://www.youtube.com/channel/UC…">`
   - `<meta itemprop="identifier" content="UC…">`
   - `"externalId":"UC…"`
   - also `<meta property="og:image" content="https://yt3.googleusercontent.com/…=s900-c-k-c0x00ffffff-no-rj">`, which is the cover art.

   Cost: the page is about **2.5 MB** of HTML (measured), so stream-parse and stop at `</head>` for the `<link>` tags. The banner sits later, in the inline JSON.
2. *InnerTube `navigation/resolve_url`*: foss only. NewPipe Extractor's `YoutubeChannelHelper.resolveChannelId()` does this. A POST of about 1 KB returns `endpoint.browseEndpoint.browseId = UC…` for `@handle`, `/c/`, `/user/` and bare custom URLs; all four forms resolved to `UCBJycsmduvYEL83R_U4JriQ` in my test. It is an undocumented API, so keep it out of the `play` flavour.

**oEmbed** (official and public): `GET https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=<id>&format=json` returns `{"author_name":"Marques Brownlee","author_url":"https://www.youtube.com/@mkbhd",…}`. That is a handle, not an ID, so a second hop is needed.

**Share target** (the main way users get input to us): Android 12+ does not route unverified web links to third-party apps by default. The YouTube app is the verified handler for youtube.com, so a `VIEW` intent filter is nearly useless. Accept `ACTION_SEND text/plain` instead. The YouTube app shares strings such as `https://youtube.com/@handle?si=…` or `https://youtu.be/ID?si=…`.

```xml
<activity android:name=".share.ShareToSubscribeActivity" android:exported="true"
          android:theme="@style/Theme.Neutrodyne.Translucent">
  <intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="text/plain" />
  </intent-filter>
</activity>
```

```kotlin
sealed interface YtRef {
    data class Channel(val id: String) : YtRef
    data class Handle(val handle: String) : YtRef          // "@mkbhd"
    data class LegacyPath(val path: String) : YtRef        // "c/mkbhd", "user/x", "mkbhd"
    data class Video(val id: String) : YtRef
    data class Playlist(val id: String) : YtRef            // PL…, OLAK…, UU…
    data class Query(val text: String) : YtRef
}
// parse() is pure and unit-tested. resolve(YtRef): ChannelId uses
// HtmlAutodiscovery (all flavours) or InnertubeResolver (foss, preferred because it is light).
```

Always store the `UC…` ID, never the handle. Channel owners can change handles.

### 2. The Atom feed

**Endpoints [tested]:**

- `https://www.youtube.com/feeds/videos.xml?channel_id=UC…`
- `https://www.youtube.com/feeds/videos.xml?playlist_id=<UU|UULF|UUSH|UULV|UUMO|PL…>`
- `https://www.youtube.com/feeds/videos.xml?user=<legacy username>`

**Response headers [tested]:** `content-type: text/xml; charset=UTF-8`, `cache-control: public, max-age=900`, `server: YouTube RSS Feeds server`. There is **no `ETag` and no `Last-Modified`**, so conditional GET is impossible. Poll at most every 15 minutes and diff by video ID.

**Shape** (trimmed from a live response):

```xml
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
 <link rel="self" href="http://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ"/>
 <id>yt:channel:BJycsmduvYEL83R_U4JriQ</id>
 <yt:channelId>BJycsmduvYEL83R_U4JriQ</yt:channelId>      <!-- NB: "UC" prefix missing at feed level -->
 <title>Marques Brownlee</title>
 <link rel="alternate" href="https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ"/>
 <author><name>Marques Brownlee</name><uri>https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ</uri></author>
 <published>2008-03-21T15:25:54+00:00</published>
 <entry>
  <id>yt:video:3iRUwVzRDZQ</id>
  <yt:videoId>3iRUwVzRDZQ</yt:videoId>
  <yt:channelId>UCBJycsmduvYEL83R_U4JriQ</yt:channelId>  <!-- full ID at entry level -->
  <title>…</title>
  <link rel="alternate" href="https://www.youtube.com/watch?v=3iRUwVzRDZQ"/>  <!-- /shorts/ID for Shorts -->
  <author>…</author>
  <published>2026-10-02T20:50:25+00:00</published>
  <updated>2026-10-04T21:20:48+00:00</updated>        <!-- bumps on stats changes; not a content signal -->
  <media:group>
   <media:title>…</media:title>
   <media:content url="https://www.youtube.com/v/3iRUwVzRDZQ?version=3" type="application/x-shockwave-flash" width="640" height="390"/>
   <media:thumbnail url="https://i4.ytimg.com/vi/3iRUwVzRDZQ/hqdefault.jpg" width="480" height="360"/>
   <media:description>full description, with timestamps and links</media:description>
   <media:community>
    <media:starRating count="80182" average="5.00" min="1" max="5"/>
    <media:statistics views="7095308"/>
   </media:community>
  </media:group>
 </entry>
</feed>
```

Playlist feeds also carry `<yt:playlistId>`. Their feed-level `<yt:channelId>` *does* include `UC`. Their `<title>` is the playlist name, for example "Videos" or "Live streams", not the channel name, so take the podcast title from `<author><name>`.

**Mapping to the episode model:**

| Episode field | Source |
|---|---|
| `guid` | `<id>` (`yt:video:<id>`). Stable and identical to what other readers use. |
| `ytVideoId` | `yt:videoId` |
| `title` | `<title>` |
| `description` | `media:description` (plain text). Linkify URLs and turn `mm:ss` / `h:mm:ss` timestamps into tappable seek links and **chapters**. |
| `publishedAt` | `<published>` |
| `thumbnailUrl` | Derived from the video ID (section 3); the feed only gives `hqdefault`. |
| `isShort` | `link[rel=alternate]` path starts with `/shorts/` **[tested]** |
| `views` | `media:statistics@views`. `0` hints at upcoming or premiere **[heuristic, unverified]**. |
| `duration` | Absent. See section 4. |
| `mediaUri` | `yt://<videoId>` (foss) or `null` plus `externalUrl` (play) |

**Uploads-playlist prefixes.** These are undocumented. Tested on 2026-10-04 against MKBHD (`UCBJycsmduvYEL83R_U4JriQ`) and NASA (`UCLA_DiR1FfKNvjuUpBHmylQ`):

| Prefix | Observed content | MKBHD | NASA |
|---|---|---|---|
| `UU` | All uploads: long-form, Shorts and live | 15 entries, 3 Shorts | 15 entries; the newest 10 are live broadcasts |
| `UULF` | **Long-form only: no Shorts, no live** | 15 entries, 0 Shorts | 15 entries, none of the live IDs |
| `UUSH` | Shorts only | 15/15 `/shorts/` links | 15 |
| `UULV` | Live streams (past broadcasts) | 6 (2013–2019 streams) | 15 (all the live IDs) |
| `UUMO` | Members-only uploads | 5 | n/a |
| `UUPS` | "Popular Shorts" (by name) | 15 Shorts | n/a |
| `UULP` | "Popular long-form" (by name) | 15 | n/a |
| `UUPV` | "Popular live" (by name) **[semantics unverified]** | 6 | n/a |
| `UUMF`, `UUMS`, `UUML`, `UUPP` | HTTP 404 | 404 | n/a |

The channel feed is identical to `UU`.

Subscription filter design (per channel, user-editable):

- Default: `UULF`.
- "Include Shorts": also poll `UUSH`.
- "Include past live streams": also poll `UULV`.
- Merge by video ID.
- Optionally poll `UUMO` about once a day to mark members-only items.

If `UULF` returns 404 (it may for channels with no long-form uploads, or during an outage), fall back to `channel_id` and filter `/shorts/` client-side. There is **no live marker** in the plain feed.

**Refresh policy:**

- One request per enabled variant per channel.
- At most 4–6 parallel requests per host.
- Respect max-age=900.
- Use exponential backoff per feed.
- Treat 404 and 5xx as transient: keep a `consecutiveFailures` counter and never auto-unsubscribe.
- If more than 50% of YouTube feeds fail in one cycle, show a global "YouTube feeds are having problems" banner rather than per-podcast errors.

**Back catalogue:** the feed only ever shows 15 items.

- foss: page with NewPipe Extractor's channel `VIDEOS` tab (`ChannelTabInfo`, then `getMoreItems` with `nextPage`).
- play: Data API `playlistItems.list` on `UULF…`/`UU…` (1 unit per page of 50) if a key is configured, otherwise "latest 15 only". Whether the Data API accepts `UULF` IDs is **[unverified]**; `UU` is the documented `relatedPlaylists.uploads` value.
- Never auto-download the back catalogue.

**Regular playlists** (`PL…`) as podcasts: the feed works **[tested]**, but it returns the **first 15 items in playlist order**. For an oldest-first playlist that means new additions never appear (tested on a 2012–2013 playlist). Either refuse playlist subscriptions in v1 or require foss-side paging to the end.

### 3. Cover art, banners and episode art

**Channel avatar (podcast cover, square):**

- All flavours: `og:image` from the channel page, `https://yt3.googleusercontent.com/<token>=s900-c-k-c0x00ffffff-no-rj`. Rewrite `=s\d+` to the size needed (`s512` fetched OK **[tested]**). Use s256–s512 for grids and s900 for the detail header.
- foss: `ChannelInfo.getAvatars()` returns a `List<Image>` with sizes.
- Data API: `channels.list part=snippet` with thumbnails `default` 88², `medium` 240², `high` 800². Costs 1 unit.

**Banner (podcast detail header):**

- From page JSON `"banner":{"imageBannerViewModel":{"image":{"sources":[{"url":"https://yt3.googleusercontent.com/<token>=w1060-fcrop64=1,00005a57ffffa5a8-k-c0xffffffff-no-nd-rj","width":1060,"height":175}…` **[tested]**. Several widths (1060, 1138, 1707, …) are present.
- foss: `ChannelInfo.getBanners()`.
- Data API: `brandingSettings.image.bannerExternalUrl`.
- The banner is about 6:1. Use it as a blurred or cropped header behind the square avatar.

**Video thumbnails (episode art), all [tested]** for a 2026 video:

| URL `https://i.ytimg.com/vi/<id>/…` | Size | Notes |
|---|---|---|
| `maxresdefault.jpg` / `hq720.jpg` | 1280×720 | `maxresdefault` can 404 for older or low-res uploads **[known behaviour, not re-tested]** |
| `sddefault.jpg` | 640×480 | 4:3 letterboxed |
| `hqdefault.jpg` | 480×360 | 4:3 letterboxed. This is what the feed gives. |
| `mqdefault.jpg` | 320×180 | True 16:9, small. **Use for list rows.** |
| `default.jpg` | 120×90 | |
| `/vi_webp/<id>/maxresdefault.webp` | | WebP variants exist (`image/webp`) |
| `oar2.jpg` | vertical | Exists for Shorts (HTTP 200 for a Short) |

The Data API added `fhd`, `qhd` and `uhd` thumbnail keys on 2026-09-11.

Coil chain: `maxresdefault`, then `hq720`, then `sddefault`, then `hqdefault`. Use `ContentScale.Crop` for the letterboxed 4:3 images.

Media session or notification artwork: prefer the **channel avatar**, which is square. Center-cropped 16:9 thumbnails look bad on Android Auto and lock screens.

### 4. Durations and content flags

| Signal | Atom | foss (NewPipe Extractor) | play (Data API, optional) |
|---|---|---|---|
| Duration | none | `StreamInfoItem.getDuration()` from the channel `VIDEOS` tab (one browse call per channel, only when the feed diff found new IDs); exact `StreamInfo.getDuration()` at resolve time; Media3 also reports it | `videos.list part=contentDetails` → ISO-8601 `PT#M#S`, 1 unit per call |
| Short | `/shorts/` link; `UUSH` | `isShortFormContent()` | No field. Use the feed link. |
| Live now or upcoming | none (views==0 heuristic) | `getStreamType()` = `LIVE_STREAM`/`AUDIO_LIVE_STREAM`; `ContentAvailability.UPCOMING` | `snippet.liveBroadcastContent` ∈ `live`/`upcoming`/`none` |
| Past live (VOD) | `UULV` membership | `StreamType.POST_LIVE_STREAM` | `liveStreamingDetails` present |
| Premiere (scheduled) | appears in `UULF` before air time **[unverified]** | `UPCOMING`; player playability "offline" | `liveBroadcastContent=upcoming` |
| Members-only | `UUMO` membership | `ContentAvailability.MEMBERSHIP` | n/a (`status`) |
| Age-restricted / kids / region-blocked / private | none | Player response `playabilityStatus` (LOGIN_REQUIRED / UNPLAYABLE / ERROR) → mark the episode unavailable | `contentDetails.contentRating`, `status.privacyStatus` |

Shorts definition: from 2024-10-15, square or vertical uploads up to **3 minutes** are Shorts. Duration thresholds alone are unreliable, so use the feed link or `UUSH`.

Default behaviour (suggested; open question):

- Hide Shorts and live streams.
- Hide members-only.
- Hold back upcoming and premiere items until they become playable, re-checked on each refresh.
- Show age-restricted and kids items greyed out with an "Open in YouTube" action.

### 5. Stream extraction with NewPipe Extractor (foss only)

**State of the art as of 2026-10-04,** from reading NewPipe Extractor `dev` at commit `eb53b79` (2026-09-27):

- `YoutubeStreamExtractor.onFetchPage()` fetches stream data **only via the `VISIONOS` InnerTube client**: `fetchVisionOsClient(...)`, client version `1.04`. It fetches metadata and thumbnails via the WEB client.
- Commit `82b7e410` (2026-06-07, in v0.26.3+) "Workaround again SABR-only responses".
- Commit `9ed62db3` (2026-08-06, on `dev`, **not yet released**) removed the ANDROID, IOS and WEB_EMBEDDED_PLAYER clients: "As nobody is able to generate poTokens for first two … As a result of this removal, DASH manifests are no longer available, videos made for kids cannot be played". The earlier SABR commit already said made-for-kids videos only get "the 360p muxed stream".
- `setPoTokenProvider()` is now a **no-op**: "doesn't use any client supporting poTokens until SABR support is added". PoToken plumbing (`PoTokenProvider`) was added in January–February 2025 (`3878696b`). NewPipe added a WebView BotGuard generator at the same time.
- yt-dlp (2026.08.19) uses the same client: `_DEFAULT_CLIENTS = ('visionos', 'web')`, with `'REQUIRE_JS_PLAYER': False` for visionos. yt-dlp's PO-Token guide (edited 2026-07-12) lists PoToken as required for GVS on `web`, `mweb`, `web_music`, `web_creator`, `android` and `ios`, and says `web`/`tv` have "Only SABR formats available".
- **n-signature:** NewPipe Extractor still contains the n-param deobfuscator (`YoutubeJavaScriptPlayerManager`, Rhino); the most recent fix was `8a415cad`, 2026-01-27, for player `c9168c90`. It only runs when an `n=` parameter is present. My VISIONOS probe URLs had **no `n` parameter** and no signature cipher, so today no JS execution happens on the playback path.

**What a VISIONOS player response contained [tested once, 2026-10-04, video 3iRUwVzRDZQ]:**

- `playabilityStatus.status = OK`
- `streamingData.expiresInSeconds = 21540` (about 5 h 59 m)
- `adaptiveFormats` with direct `url`s and `serverAbrStreamingUrl`. `hlsManifestUrl` was present. **There were no muxed `formats`.**
- Audio itags: 139 (HE-AAC `mp4a.40.5`, about 50 kbps), 140 (AAC-LC `mp4a.40.2`, about 131 kbps, `contentLength` 10,471,997 for 647 s), 249/250/251 (Opus, about 55/74/142 kbps). All carried `audioTrack.displayName = "English original"` and `xtags` `acont=original:lang=en`.
- URL query parameters included `expire`, `ip`, `c=VISIONOS`, `clen`, `dur`, `sig`, `lsig`. **URLs are bound to the requesting IP.**
- A ranged GET from the sandbox got `302 → 403`. The `ip` parameter was an IPv6 address while the media request left from an IPv4 proxy egress (`mip=160.79.106.20`). This looks like an IP mismatch caused by the sandbox proxy, so **end-to-end byte playability was not verified here.** NewPipe and LibreTube users do play these streams, but the IPv4/IPv6 family mismatch is a real-world pitfall (section on pitfalls).

**Gradle (`:youtube-streams`, GPL-3.0-or-later; the version numbers are verified):**

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.teamnewpipe"); includeGroup("com.github.TeamNewPipe") } // extractor + nanojson
        }
    }
}
// youtube-streams/build.gradle.kts
dependencies {
    api(project(":youtube-api"))                                   // Unlicense interfaces
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
}
// app/build.gradle.kts
android {
    compileOptions { isCoreLibraryDesugaringEnabled = true }       // required if minSdk < 33 (extractor README)
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("foss") { dimension = "distribution" /* applicationIdSuffix: open question */ }
    }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    "fossImplementation"(project(":youtube-streams"))
}
```

R8 rules (from the extractor README):

```
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
```

**Do not let Rhino resolve to 1.9.x.** The extractor pins `rhino = "1.8.1"` with the note "rhino 1.9.0 requires Android minSDK >= 26".

DI: `src/foss/…/YouTubeModule.kt` binds `NpeYouTubeStreamResolver`. `src/play/…/YouTubeModule.kt` binds `ExternalOnlyYouTubeResolver`, which returns `Unsupported` so that the UI shows "Open in YouTube".

**Interface (Unlicense, `:youtube-api`):**

```kotlin
interface YouTubeStreamResolver {
    suspend fun resolveAudio(videoId: String, pref: AudioPref): ResolveResult
}
data class ResolvedAudio(
    val url: String, val itag: Int, val mimeType: String?, val bitrate: Int,
    val contentLength: Long?, val durationSec: Long?, val expiresAtEpochSec: Long?,
    val chapters: List<Chapter>, val sourceIp: String?)
sealed interface ResolveResult {
    data class Ok(val audio: ResolvedAudio) : ResolveResult
    data class Unavailable(val reason: Reason) : ResolveResult // AGE, MEMBERS, KIDS_ONLY_MUXED, UPCOMING, REGION, PRIVATE, LIVE
    data class Transient(val cause: Throwable) : ResolveResult // network, bot check, extractor broken
    data object Unsupported : ResolveResult                      // play flavour
}
```

**Downloader and initialisation (foss):**

```kotlin
class OkHttpNpeDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val rb = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body)
        request.headers().forEach { (k, vs) -> rb.removeHeader(k); vs.forEach { rb.addHeader(k, it) } }
        if (request.headers()["User-Agent"] == null) rb.header("User-Agent", FIREFOX_UA)
        client.newCall(rb.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha challenge", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}
// Application.onCreate (foss): NewPipe.init(OkHttpNpeDownloader(ytClient), Localization("en","US"), ContentCountry("US"))
// Use the user's locale and country instead; it changes which audio track is "default" on dubbed videos.
```

**Choosing an audio stream:**

```kotlin
fun pick(info: StreamInfo, pref: AudioPref): AudioStream? = info.audioStreams
    .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
    .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }  // avoid dubbed/AI-dubbed
    // NB: ItagItem.isAutoGenerated() (auto-dub flag) landed on dev in 676dd716, after v0.26.5
    .sortedWith(compareBy<AudioStream> { pref.rank(it.itag) }.thenByDescending { it.averageBitrate })
    .firstOrNull()
// Default ranks: Standard = 140, 251, 250, 139, 249. DataSaver = 250, 249, 139, 140. Opus = 251, 250, 140.
// Kids videos: no audio-only streams, so fall back to the muxed itag 18 (360p video + AAC) with video disabled, or mark Unavailable.
```

Media3 handles both containers: MP4/fMP4 with AAC (all API levels) and WebM/Matroska with Opus (platform decoder on API 21+). YouTube's adaptive audio files carry index ranges (`initRange`/`indexRange`), so seeking in progressive playback works through sidx/Cues. This is standard behaviour, not re-tested here. Do not set a MIME type on the `MediaItem`; let the extractor sniff, because it may be mp4 or webm.

### 6. Media3 playback integration (foss)

```kotlin
// One Factory for both playback and downloads.
class YtResolver(
    private val resolver: YouTubeStreamResolver,
    private val urlCache: ResolvedUrlCache,       // in-memory, keyed by videoId, TTL = min(expire - 10 min, 5 h)
    private val prefs: () -> AudioPref,
) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != "yt") return dataSpec
        val videoId = dataSpec.uri.host!!                        // yt://<videoId>
        val a = urlCache.getValid(videoId) ?: runBlocking { resolver.resolveAudio(videoId, prefs()) }
            .let { it as? ResolveResult.Ok ?: throw YtResolveException(it) }.audio
            .also { urlCache.put(videoId, it) }
        return dataSpec.buildUpon().setUri(Uri.parse(a.url)).setKey("yt:$videoId:${a.itag}").build()
    }
}
// resolveDataSpec runs on the loader thread, so blocking is OK. Never call it on the main thread.

class ReResolveOn403(private val upstream: DataSource, private val urlCache: ResolvedUrlCache,
                     private val resolve: (DataSpec) -> DataSpec) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long = try { upstream.open(resolve(dataSpec)) }
    catch (e: HttpDataSource.InvalidResponseCodeException) {
        if (e.responseCode != 403 || dataSpec.uri.scheme != "yt") throw e
        urlCache.invalidate(dataSpec.uri.host!!); upstream.close()
        upstream.open(resolve(dataSpec))                        // one retry; then surface the error
    }
}
```

Notes:

- `MediaItem.Builder().setMediaId(episodeId).setUri("yt://$videoId")`. Keep the cache key stable (`yt:<id>:<itag>`) so a re-resolved URL hits the same `SimpleCache` entries.
- Without the wrapper, Media3 treats a 403 on progressive media as fatal. Alternatively, handle `onPlayerError` with `ERROR_CODE_IO_BAD_HTTP_STATUS`: re-resolve, `prepare()`, `seekTo(lastPosition)`.
- Pre-resolve the **next** queue item about 60 s before the current one ends, to hide 0.5–2 s of extraction latency.
- Network changes (Wi-Fi to cellular) change the client IP. The `ip=`-bound URL may then 403, and the wrapper handles that.
- Live streams (`hlsManifestUrl`) are out of scope for a podcast app. Show them as unavailable or "Open in YouTube".

### 7. Downloads (foss)

- Resolve at job start. Write to `…/Neutrodyne/YouTube/<channel>/<videoId>.<m4a|webm>`, choosing the extension from the MIME type. Store `itag`, `clen`, the `lmt` parameter and `contentLength` with the download row.
- Fetch with HTTP `Range` in ≤10 MiB chunks. yt-dlp's `_video.py` uses `CHUNK_SIZE = 10 << 20` and `http_chunk_size` for YouTube HTTPS formats.
- On 403 or after `expire`: re-resolve, check that `itag` and `clen` are unchanged, and continue from the current byte offset. If `clen` changed (the video was re-encoded), restart.
- Android 15: `dataSync` foreground services are limited to 6 h per 24 h. Prefer user-initiated data-transfer jobs on Android 14+ or short WorkManager chunks. This is shared with the general downloads research.
- Tag files with title, artist (channel), date and cover (avatar) if cheap. Mutagen-style tagging is not available on Android, so this is optional.
- Never auto-download live, upcoming or members-only items.

### 8. SponsorBlock (optional, foss)

- **Privacy-preserving lookup [tested]:** `GET https://sponsor.ajay.app/api/skipSegments/<first 4 hex of sha256(videoId)>?categories=["sponsor","selfpromo",…]&actionTypes=["skip","mute"]`. It returns an array of `{videoID, segments:[{category, actionType, segment:[start,end], UUID, videoDuration, locked, votes, description}]}` for every video in the bucket; select ours locally. My test bucket held 48 videos.
- Direct form [tested]: `GET /api/skipSegments?videoID=3iRUwVzRDZQ&categories=[…]` returns the segment list directly (intro 0–2.7 s, selfpromo 577.7–634.8 s, outro 642.5–647.1 s), or HTTP 404 when there are none.
- Categories (server `config.ts`): `sponsor, selfpromo, exclusive_access, interaction, intro, outro, preview, hook, music_offtopic, filler, poi_highlight, chapter`.
- Playback: schedule `player.createMessage { _, _ -> player.seekTo(endMs) }.setLooper(Looper.getMainLooper()).setPosition(startMs).setDeleteAfterDelivery(false).send()` per auto-skip segment. The main looper matters because the default target thread is the playback thread, where calling `seekTo` is not allowed. Add a "Skip sponsor?" snackbar for manual categories. Show segments as coloured ticks on the seek bar. Map `chapter` and `poi_highlight` to Neutrodyne chapters.
- If `videoDuration` differs from our duration by more than about 2 s, ignore the segments (the video was re-cut).
- Cache segments when downloading. Refresh on play if they are older than 7 days.
- **Licence:** the database is "under [CC BY-NC-SA 4.0] unless you get explicit permission". Show attribution in Settings/About. A free app is non-commercial; no ads or paid tiers.

### 9. Import and export

**OPML export** (shared with the subscriptions research). YouTube subscriptions become normal RSS outlines nested inside their group outline:

```xml
<outline text="tech" title="tech">
  <outline type="rss" text="Marques Brownlee" title="Marques Brownlee"
           xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ"
           htmlUrl="https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ"
           neutrodyne:source="youtube" neutrodyne:ytVariants="UULF" />
</outline>
```

- Export the canonical `channel_id` URL, which every reader understands (AntennaPod documents this exact form). Keep the filter choice in a namespaced attribute; declare `xmlns:neutrodyne="…"` on `<opml>`.
- On import, recognise `feeds/videos.xml?(channel_id|user|playlist_id)=` and `youtube.com/(channel|@|c|user)/` in `xmlUrl` or `htmlUrl` and create a `YOUTUBE_CHANNEL` podcast rather than a plain RSS podcast. In the `play` flavour these become "external episode" podcasts.

**Google Takeout:**

- Location: `Takeout/YouTube and YouTube Music/subscriptions/subscriptions.csv`. The folder names are localised, so scan the zip for any `.csv` whose rows contain 24-character `UC` IDs.
- Format, per NewPipe Extractor's parser comment: always 3 columns, `Channel Id,Channel Url,Channel Title`. The header line is localised but the column order is fixed. Example: `UC1JTQBa5QxZCpXrFSkMxmPw,http://www.youtube.com/channel/UC1JTQBa5QxZCpXrFSkMxmPw,Raycevick`.
- Use a real RFC-4180 parser because titles can contain commas and quotes. NewPipe's `split(",")` is fragile.
- Takeout can deliver `.tgz` as well as `.zip`. Android has no built-in tar reader, so either support zip only and tell the user, or add a small tar reader.
- Legacy JSON (`subscriptions.json`, pre-2020): an array of Data-API `subscription` resources, `snippet.resourceId.channelId` plus `snippet.title`.
- I could not verify the current Takeout output with a real account **[unverified]**.

**NewPipe** subscriptions export (`.json`):

```json
{"app_version":"0.29.1","app_version_int":1015,
 "subscriptions":[{"service_id":0,"url":"https://www.youtube.com/channel/UC…","name":"…"}]}
```

- `service_id`: 0 YouTube, 1 SoundCloud, 2 media.ccc.de, 3 PeerTube, 4 Bandcamp. Import 0 only.
- URLs may be `/user/` or `/c/` in old exports, so run them through the resolver.
- Also offer **export** in this format: it is the de-facto interchange format for NewPipe, LibreTube and Tubular.
- Stretch goal: NewPipe's full backup zip (`newpipe.db` SQLite) has tables `subscriptions(uid, service_id, url, name, avatar_url, …)`, `feed_group(uid, name, icon_id, sort_order)` and `feed_group_subscription_join(group_id, subscription_id)`. This imports NewPipe **channel groups** directly into Neutrodyne groups.

**LibreTube** backup (`.json`, `"format":"Piped","version":1`):

```json
{"format":"Piped","version":1,
 "localSubscriptions":[{"channelId":"UC…","url":"https://www.youtube.com/channel/UC…","name":"…","avatar":"https://…","verified":false}],
 "groups":[{"groupName":"tech","channels":["UC…","UC…"],"index":0}], … }
```

- Accept the alternative key names `subscriptions` and `channelGroups`, and `name` for `groupName` (`@JsonNames` in LibreTube's `BackupFile`/`SubscriptionGroup`).
- This gives a **lossless group import**.
- LibreTube also exports NewPipe JSON, FreeTube `.db` and "list of URLs/IDs" (`.txt`). Supporting a plain list of URLs or IDs is nearly free.

### 10. The `play` flavour in detail

- Listing, covers, groups, import and export all work exactly as in foss.
- Episode row: a "YouTube" badge and duration if known. The primary action is **"Watch on YouTube"**: `startActivity(Intent(ACTION_VIEW, "https://www.youtube.com/watch?v=$id".toUri()))`, with an "Open with…" fallback.
- Optional in-app player via `com.pierfrancescosoffritti.androidyoutubeplayer` 13.0.0 (MIT, IFrame-based). Its README warns that background play "is not allowed, if you want to publish your app on the PlayStore".
- Required Minimum Functionality: viewport of at least 200×200 px, **no overlays** over the player, at most one autoplaying player.
- The player is a foreground Activity tied to the lifecycle. It must not register with the Media3 session or the audio-focus queue.
- Whether picture-in-picture counts as "background" under the developer policies is **[unverified]**, so leave PiP off on Play.
- Queue semantics: YouTube items cannot be enqueued. Auto-advance skips them. Do not count them as "unplayed" for auto-download.
- **Store listing**: no wording or screenshots about downloading YouTube. Play's IP policy lists "Streaming apps that allow users to download a local copy of copyrighted content without authorization" and listings that encourage it as violations.

### 11. YouTube Data API v3, if adopted for `play`

- Quotas (page updated 2026-09-15): `search.list` and `videos.insert` have their own buckets of **100 calls/day**, at 1 per call. Everything else shares **10,000 units/day**. `channels.list`, `videos.list` and `playlistItems.list` cost 1 unit each. Granular buckets launched on 2026-06-01.
- Useful calls:
  - `channels.list?part=snippet,brandingSettings,contentDetails&forHandle=@mkbhd` (`forHandle` accepts an optional `@`) for ID, avatar 800², banner and the uploads playlist.
  - `videos.list?part=contentDetails,snippet,liveStreamingDetails&id=a,b,c…` for durations and flags. The 50-ID batch limit is widely used but **not stated on the current docs page [unverified]**.
- Rules:
  - Store "Non-Authorized Data" "not longer than 30 calendar days" (§III.E.4.d), so refresh cached titles and avatars monthly.
  - A privacy policy that names YouTube API Services, as Podcast Addict's does.
  - The whole API client must comply: no download, no offline, no background, no separated audio. **Never ship the key in the foss APK.**
- A key in an APK can be extracted. Restrict it to the Android package and SHA-1, but those headers can be spoofed. Quota exhaustion by abuse breaks the feature for everyone.
- Rough budget: 1,000 daily users × 20 channels × 2 enrichment calls per day ≈ 40k units, which is well over 10k. Data API therefore only scales for **on-demand** enrichment (durations of *new* items batched, avatar once per subscription), or with a quota-extension audit, or with bring-your-own-key.

---

## Verified versions & facts

All checked on **2026-10-04**.

| Fact | Value | Source |
|---|---|---|
| NewPipe Extractor latest release | **v0.26.5**, 2026-08-15. Earlier 2026 tags: 0.25.0 (01-11), 0.25.1 (01-28), 0.25.2 (02-05), 0.26.0 (02-22), 0.26.1 (04-10), 0.26.2 (05-23), 0.26.3 (06-09), 0.26.4 (07-20) | `git ls-remote`/clone of https://github.com/TeamNewPipe/NewPipeExtractor; https://github.com/TeamNewPipe/NewPipeExtractor/releases |
| NewPipe Extractor licence | GPL-3.0-or-later (SPDX headers + LICENSE) | repo `LICENSE`, `build.gradle.kts` |
| NewPipe Extractor coordinates | `com.github.teamnewpipe:NewPipeExtractor:v0.26.5`. JitPack build status "ok". Snapshots at `net.newpipe:extractor:<hash>-SNAPSHOT` on Maven Central snapshots. | https://jitpack.io/api/builds/com.github.teamnewpipe/NewPipeExtractor/latest ; repo README |
| Extractor runtime dependencies (v0.26.5 POM) | nanojson (git e9d656d), jsoup 1.22.2, jsr305 3.0.2, protobuf-javalite 4.35.1, rhino and rhino-engine 1.8.1. The main jar is 802 KB. | https://jitpack.io/com/github/teamnewpipe/NewPipeExtractor/v0.26.5/NewPipeExtractor-v0.26.5.pom |
| Extractor needs `desugar_jdk_libs_nio` if minSdk < 33; Rhino pinned below 1.9.0 because of minSdk 26 | | README "Usage" note; `gradle/libs.versions.toml` |
| `desugar_jdk_libs_nio` latest | 2.1.5 | https://dl.google.com/android/maven2/com/android/tools/desugar_jdk_libs_nio/maven-metadata.xml |
| Extractor stream client | VISIONOS (`1.04`) for streams, WEB for metadata. PoToken provider is a no-op. | `YoutubeStreamExtractor.java`, `ClientsConstants.java` at `dev` `eb53b79` (2026-09-27) |
| SABR workaround | Commit `82b7e410` (2026-06-07), released in v0.26.3 | repo history |
| ANDROID/IOS/WEB_EMBEDDED removal; no DASH manifests; kids videos unplayable | Commit `9ed62db3` (2026-08-06), **unreleased** (not in any tag) | repo history (`git tag --contains`) |
| Auto-dubbed track flag `ItagItem.isAutoGenerated()` | Commit `676dd716`, unreleased | repo history |
| Channel tabs | `videos, shorts, livestreams, playlists, podcasts, courses, …`. "podcasts"/"courses" tabs added in `4b705766` (2026-07-20, unreleased). | `ChannelTabs.java` |
| NewPipe app latest | v0.29.1 (tag 2026-08-15; F-Droid lists 2026-08-21, versionCode 1015). 2026 releases: 0.28.1 to 0.29.1, about monthly. | https://github.com/TeamNewPipe/NewPipe tags; https://f-droid.org/packages/org.schabi.newpipe/ |
| NewPipe on F-Droid | `AntiFeatures: NonFreeNet: "Depends on Youtube for videos."` Reproducible builds via `AllowedAPKSigningKeys`. | https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/org.schabi.newpipe.yml |
| NewPipe on Play | README: "PUTTING NEWPIPE, OR ANY FORK OF IT, INTO THE GOOGLE PLAY STORE VIOLATES THEIR TERMS AND CONDITIONS." | https://github.com/TeamNewPipe/NewPipe/blob/dev/README.md |
| yt-dlp latest | 2026.08.19. Unlicense. Python 3.10+. Default YouTube clients `('visionos','web')`; JS runtime (deno/node/quickjs/bun) + yt-dlp-ejs "Required for full YouTube support". | `git ls-remote` https://github.com/yt-dlp/yt-dlp ; README; `yt_dlp/extractor/youtube/_video.py` L143–145 |
| yt-dlp YouTube HTTPS chunk size | `CHUNK_SIZE = 10 << 20` (10 MiB) | `_video.py` L3229, L3613 |
| yt-dlp PO-Token guide | GVS PoToken required for web, mweb, web_music, web_creator, android, ios, tv_simply; not for tv, web_embedded, android_vr. "Only SABR formats available" for web/tv. Last edited 2026-07-12. | https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide |
| android_vr SABR-only A/B test | Reported 2026-03-05/07 | https://github.com/yt-dlp/yt-dlp/issues/16150 |
| youtubedl-android | 0.18.1 (2025-11-16). GPL-3.0. Bundles Python 3.12.11 (bumped 2025-09-28), yt-dlp 2025.11.12, QuickJS (2025-11-07). arm64: libpython.zip.so 14 MB, ytdlp 3.1 MB, libqjs 0.9 MB, ffmpeg zip 34 MB. | clone of https://github.com/yausername/youtubedl-android (measured) |
| LibreTube | v32.1 (2026-08-20 bump). GPL-3.0. F-Droid, IzzyOnDroid, GitHub. Uses the `libre-tube` NewPipe Extractor fork. Native SABR client (commits July–September 2026). | clone of https://github.com/libre-tube/LibreTube |
| Podcini discontinued | "stop developing … as of Jan 13 2025", successor Podcini.X "with access to Youtube stripped off" | https://github.com/XilinJia/Podcini README |
| AntennaPod stance | Docs: YouTube feeds can be added as RSS, no downloads, no image. Maintainer 2025-01-04: "those are not podcasts". | https://antennapod.org/documentation/getting-started/subscribe ; https://forum.antennapod.org/t/cant-add-youtube-entries-to-queue/5937/7 |
| Podcast Addict uses YouTube API Services | Privacy policy statement | https://podcastaddict.com/privacy |
| YouTube.js / googlevideo / BgUtils | 18.1.0 / 4.1.1 / 4.0.3, all MIT, last commits 2026-09-20 to 09-23. googlevideo = "YouTube's custom UMP format and SABR streaming protocol". | clones of https://github.com/LuanRT/YouTube.js , /googlevideo , /BgUtils |
| android-youtube-player | 13.0.0 (2025-09-21), MIT, IFrame-based. README: background play "not allowed … on the PlayStore". | https://github.com/PierfrancescoSoffritti/android-youtube-player |
| Media3 latest stable | 1.11.1 (2026-09-10) | https://developer.android.com/jetpack/androidx/releases/media3 |
| Media3 formats | AAC in MP4/fMP4 (platform, all APIs); Opus in WebM/Matroska/Ogg (platform API 21+, or the decoder_opus extension) | https://developer.android.com/media/media3/exoplayer/supported-formats |
| Atom feed: 15 entries; Shorts included with `/shorts/` links; live included; no duration; `max-age=900`; no ETag/Last-Modified; feed-level `yt:channelId` missing "UC" | **[tested]** | `curl https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ` |
| UU/UULF/UUSH/UULV/UUMO/UUPS/UULP/UUPV return 200; UUMF/UUMS/UUML/UUPP return 404; UULF excludes Shorts and live | **[tested]** on MKBHD + NASA | `curl …/feeds/videos.xml?playlist_id=<prefix><id>` |
| `?user=` feed still works | **[tested]** | `curl …/feeds/videos.xml?user=marquesbrownlee` |
| `PL…` playlist feed returns the first 15 in playlist order | **[tested]** (PL6566A39B68523E18) | curl |
| InnerTube `navigation/resolve_url` resolves @handle, /c/, /user/ and bare custom URLs to UC IDs | **[tested]** | POST `https://www.youtube.com/youtubei/v1/navigation/resolve_url` |
| Channel page has RSS `<link>`, canonical, `itemprop=identifier`, og:image avatar (=s900), banner JSON; page about 2.5 MB | **[tested]** | `curl https://www.youtube.com/@mkbhd` |
| oEmbed returns `author_url` (handle) | **[tested]** | `https://www.youtube.com/oembed?url=…&format=json` |
| VISIONOS player response: `expiresInSeconds` 21540; audio itags 139/140/249/250/251 with direct URLs and no `n`; IP-bound URLs; `serverAbrStreamingUrl` and `hlsManifestUrl` present; no muxed formats | **[tested once]**; byte fetch 403 from sandbox (IP mismatch suspected) | probe modelled on `YoutubeStreamHelper.getVisionOsPlayerResponse` |
| Thumbnail variants and sizes | **[tested]** | `curl https://i.ytimg.com/vi/3iRUwVzRDZQ/<name>` |
| SponsorBlock endpoints and categories; DB licence CC BY-NC-SA 4.0; server AGPL-3.0 | **[tested]** endpoints; categories from `src/config.ts` | https://sponsor.ajay.app/api/skipSegments ; https://github.com/ajayyy/SponsorBlockServer |
| Data API quotas | search.list and videos.insert: own buckets of 100 calls/day; others share 10,000 units/day; channels/videos/playlistItems.list cost 1 unit. Page updated 2026-09-15. Granular buckets launched 2026-06-01. | https://developers.google.com/youtube/v3/determine_quota_cost ; https://developers.google.com/youtube/v3/revision_history |
| Data API channels.list | Filters `id`, `forHandle`, `forUsername`, `mine`; thumbnails 88/240/800 px; `brandingSettings.image.bannerExternalUrl` | https://developers.google.com/youtube/v3/docs/channels/list ; https://developers.google.com/youtube/v3/docs/channels |
| Data API videos resource | `contentDetails.duration` ISO-8601; `snippet.liveBroadcastContent` live/upcoming/none; thumbnails up to `uhd` (added 2026-09-11) | https://developers.google.com/youtube/v3/docs/videos |
| YouTube API Developer Policies | III.E.1.a no download/cache of AV content; III.E.1.b no offline playback; III.I.7 no separating audio/video; III.I.9 no background player; III.E.4.d ≤30 days for non-authorised data | https://developers.google.com/youtube/terms/developer-policies |
| Required Minimum Functionality | Embedded player ≥200×200 px; no overlays; ≤1 autoplaying player | https://developers.google.com/youtube/terms/required-minimum-functionality |
| YouTube ToS (effective 2023-12-15) | "You are not allowed to: access, reproduce, download … except: (a) as expressly authorized by the Service; or (b) with prior written permission…"; no circumvention of features that "prevent or restrict the copying"; no "automated means (such as robots, botnets or scrapers)" | https://www.youtube.com/static?template=terms |
| Google Play Device and Network Abuse | Example violation: "Apps that access or use a service or API in a manner that violates its terms of service." The current text no longer names YouTube explicitly; older versions said apps must not "download, monetize, or access YouTube videos in a way that violates the YouTube Terms of Service" (quoted in older forum threads). | https://support.google.com/googleplay/android-developer/answer/9888379 |
| Google Play Intellectual Property | "Streaming apps that allow users to download a local copy of copyrighted content without authorization." | https://support.google.com/googleplay/android-developer/answer/9888072 |
| F-Droid NonFreeNet | "apps that promote or depend entirely on a proprietary network service" | https://f-droid.org/docs/Anti-Features/ |
| Invidious instances | 5 listed; "short due to the recent YouTube issues" | https://docs.invidious.io/instances/ |
| Invidious takedown demand | YouTube legal, 2023-06-08, 7 days | https://alternativeto.net/news/2023/6/youtube-legal-team-asked-invidious-developers-to-take-down-the-service-within-7-days |
| Atom feed outages | Intermittent 404s from about 2025-12-20, mostly 09:00–12:00 UTC, still reported in April–May 2026. Feeder: "Sometimes YouTube's feed server answers every feed with 404 Not Found for hours at a time." | https://discuss.ai.google.dev/t/youtube-rss-feed-endpoint-returns-404-errors/113379 ; https://feeder.co/help/rss/youtube-feeds/ |
| Shorts definition | Square or vertical, ≤3 min, uploaded on or after 2024-10-15 | https://support.google.com/youtube/answer/15424877 |
| Android developer verification | Protections from 2026-09-30 in BR/ID/SG/TH for certified devices; global "2027 and beyond"; "advanced flow" for unverified apps; limited-distribution accounts (≤20 devices) | https://developer.android.com/developer-verification ; https://f-droid.org/2026/02/24/open-letter-opposing-developer-verification.html |
| Android 15 dataSync FGS limit | 6 h per 24 h, then `onTimeout` | https://developer.android.com/about/versions/15/behavior-changes-15 |
| Unlicense is GPL-compatible | "Both public domain works and the lax license provided by the Unlicense are compatible with the GNU GPL" (attributed to FSF). gnu.org itself returned 503 or reset during the check. | https://en.wikipedia.org/wiki/Unlicense ; https://www.gnu.org/licenses/license-list.html (not fetched) |
| FSF on combined works vs IPC | "If the modules are included in the same executable file, they are definitely combined"; pipes and sockets are normally separate "unless … intimate enough" | https://en.wikipedia.org/wiki/GNU_General_Public_License (quoting the GPL FAQ; https://www.gnu.org/licenses/gpl-faq.html#MereAggregation not fetchable) |
| Chaquopy | MIT, 17.0.0 (2025-11-30) per search results only | **[unverified]**: https://central.sonatype.com/artifact/com.chaquo.python.runtime/chaquopy |

---

## Pitfalls & edge cases

**Feeds**

1. **Feed-level `yt:channelId` has no `UC` prefix** in `channel_id` feeds (`BJycsmduvYEL83R_U4JriQ`). Take the ID from entry-level `yt:channelId`, `author/uri` or `link[rel=alternate]`.
2. **`<updated>` changes all the time.** It was within minutes of "now" for week-old videos, so it probably tracks view or like count updates. Do not use it to mark episodes as changed or to notify.
3. **No conditional GET.** There is no ETag or Last-Modified. Respect `max-age=900`. Diff by video ID.
4. **15-entry window.** High-volume channels such as news can push items out between refreshes. `UULF` reduces Shorts noise. Shorten the refresh interval for channels that churn more than 15 items per interval.
5. **Feed outages return 404 for every feed for hours.** Never auto-unsubscribe or mark a podcast dead on 404. Use a global banner and backoff.
6. **`PL…` playlist feeds show the first 15 in playlist order,** not the newest.
7. The **`UU…` prefix family is undocumented.** If YouTube drops `UULF`, fall back to `channel_id` with client-side `/shorts/` filtering. Live items then leak in. In foss, enrichment can still mark them.
8. **Premieres and scheduled live streams** may appear before they are playable. Resolution fails with an "upcoming" or offline status, so hold them back. Do not count them as new or auto-download them.

**Input and identity**

9. **Handles change.** Store `UC…` only. Handles can contain dots, underscores, hyphens and non-ASCII characters, so URL-encode them. `/c/` and bare custom URLs are legacy but still resolve.
10. **EU consent wall** on HTML fetches. Send `SOCS=CAE=`.
11. **Search in the `play` flavour.** Data API `search.list` is limited to 100 calls/day per project, so do not build search on it.

**Streams**

12. **Stream URLs are IP-bound** (`ip=`) and expire after about 6 h. Never persist them in the DB, in exports, or in the restored queue state after process death. Re-resolve after network changes.
13. **IPv4/IPv6 mismatch.** If the player request goes over IPv6 and the media fetch over IPv4 (or the other way round, for example with Happy Eyeballs, VPNs or CGNAT), expect 403s. My sandbox reproduced exactly this pattern. Mitigation: use the same OkHttp client and connection pool for InnerTube and googlevideo. If 403s persist, retry with an IPv4-only `Dns`. Mark this **[hypothesis, verify on devices]**.
14. **Dubbed and auto-dubbed tracks.** Videos increasingly carry several audio tracks, including AI auto-dubs. Always choose `ORIGINAL`, falling back to the user's language, and expose a per-episode "audio language" override. Initialise the extractor with the user's real locale.
15. **DRC variants.** Formats can be flagged `isDrc` (stable-volume). yt-dlp ranks them lower. Prefer non-DRC unless the user enables a "volume levelling" preference.
16. **Made-for-kids videos.** NewPipe Extractor `dev` says these "cannot be played" after the August 2026 change; v0.26.3+ offers only 360p muxed. Treat them as Unavailable with "Open in YouTube".
17. **Age-restricted, members-only, private and region-blocked items** need a login, which we will not support. Mark them unavailable at enrichment or resolve time and keep them out of auto-download.
18. **Bot checks.** "Sign in to confirm you're not a bot" / `ReCaptchaException` (HTTP 429) appears on VPN, Tor or datacenter IPs. Background refresh must not hammer YouTube: cap concurrent extractions to 1–2 and show one actionable notice. NewPipe offers a WebView captcha solver; we probably should not.
19. **Extractor breakage is routine.** Wrap every extractor call. Classify errors as `Transient` or `Unavailable`. Show "YouTube playback is temporarily broken; update Neutrodyne" when more than N resolves fail with parsing errors within an hour.
20. **Release lag.** F-Droid's build queue is typically several days **[unverified]**. IzzyOnDroid and GitHub are faster. Ship hotfixes to GitHub first, and keep reproducible builds so F-Droid publishes our signature, as NewPipe does.
21. **Do not let a Rhino upgrade sneak in** through another dependency. Rhino 1.9 needs minSdk 26.
22. **Live HLS** is present in the player response but out of scope. Do not enqueue live items.

**Distribution and legal**

23. **Play listing.** No "download YouTube" wording or screenshots. Do not include a `<queries>` entry for a companion extractor app in the Play build.
24. **Data API key.** Keep it out of the foss build. In the play build, expect extraction and abuse of the key, refresh data within 30 days, and publish a privacy policy that mentions YouTube API Services.
25. **GPL obligations for the foss APK.** The source must be available at the release tag. Include GPL and NewPipe Extractor notices on the About and licences screen. The `:youtube-streams` module's files should carry `SPDX-License-Identifier: GPL-3.0-or-later` so nobody copies them into Unlicense modules by mistake.
26. **Package names and signatures across stores.** Under developer verification, the same package name with different signing keys looks like two ownership claims, which is F-Droid's re-signing problem. Decide early: either reproducible builds with one key, or distinct application IDs for `play` and `foss`.
27. **UI and queue.** In `play`, YouTube items are external: they must not break "play next", sleep timers or auto-download counters. In `foss`, YouTube items are regular audio episodes, but their durations may be unknown until enriched or played. Render "—" rather than 0:00.
28. **Artwork.** The `hqdefault` thumbnail from the feed is 4:3 letterboxed, so derive `mqdefault` or `maxresdefault` from the video ID. `maxresdefault` can 404, so keep a fallback chain.

---

## Open questions for the product owner

1. **Is Google Play a must-have distribution channel?** If yes, are you OK with YouTube in the Play build being "subscribe, browse and open in YouTube": no background audio, no downloads, no audio-only?
2. **Licensing.** Which do you prefer?
   - (a) Keep the repo Unlicense and ship a GPL-3.0-or-later `foss` APK that includes the GPL YouTube module (recommended).
   - (b) Relicense everything to GPL-3.0-or-later.
   - (c) Avoid GPL entirely. That means the MIT YouTube.js/googlevideo route (much more work) or official-only YouTube.
3. **Risk appetite and identity.** Who publishes the `foss` build, a person or an organisation? Developer verification (global in 2027) ties a legal identity to the package. The precedents are YouTube's takedown demand to Invidious (2023) and Podcini quitting (2025).
4. **Defaults for YouTube channels.** Proposed: hide Shorts and live streams, hide members-only, hold back premieres until available. Should users be able to opt into Shorts or past live streams per channel?
5. **Audio-only or video too?** Recommendation for v1: audio-only in Neutrodyne, with "Watch on YouTube" for video. In-app video means merging adaptive video and audio and much more bandwidth.
6. **Auto-download for YouTube subscriptions:** off by default, or the same rules as podcasts?
7. **SponsorBlock:** include it? Opt-in? Which categories auto-skip (proposal: `sponsor`, `selfpromo`, `interaction`) and which are prompt-only (`intro`, `outro`, `preview`, `filler`)?
8. **Back catalogue on subscribe:** only the latest 15 (feed), or "load older" paging (foss only)?
9. **YouTube playlists** (`PL…`) as podcasts as well as channels? The feed-ordering caveat applies.
10. **Prefer real podcasts.** Many YouTube podcasts also publish a normal RSS feed. When subscribing to a channel, should we search Podcast Index for the same show and suggest the RSS feed instead? This avoids all the YouTube risk for that show.
11. **Play build and the Data API.** Should we create a Google Cloud project, privacy policy and possibly a quota audit to get durations and avatars? Or accept "duration unknown" for YouTube items on Play?
12. **Default audio quality:** AAC 128k (itag 140) for compatibility, or Opus (251, or 250 for data saver)? Should there be a per-subscription quality override?
