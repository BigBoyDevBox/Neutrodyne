# Permissive YouTube audio extraction for Neutrodyne (second opinion)

Research area: permissive-extractors · Date: 2026-10-05 · Scope: every realistic way for an Unlicense Android app (Kotlin, Media3, OkHttp; minSdk 26; GitHub Releases only; no Google developer verification; package prefix `ch.lkmc`) to obtain playable YouTube audio stream URLs **without shipping GPL code**. Not legal advice.

Method: I read primary sources: LICENSE files, PyPI/npm/Maven metadata, git history and source at stated commits, GitHub issues and PRs. I checked yt-dlp release binaries by reading their ELF headers. I benchmarked yt-dlp's JS challenge solver (EJS 0.8.0) in QuickJS and Node on a real YouTube player file, and measured the import time and RSS of yt-dlp 2026.08.19. Live extraction from the sandbox was **bot-checked**: HTTP 429 on the watch page and "Sign in to confirm you're not a bot" on `/player`. So I did not re-probe whether VISIONOS works today; I rely on yt-dlp's source (2026-09-27) and the plan's own probe (04-youtube.md, 2026-10-04).

---

## Recommendation

**Short answer to the owner's question ("Can't we include the binary for yt-dl?"):** Yes in spirit, no in letter.

- **Yes:** yt-dlp is public domain (Unlicense), the same licence as Neutrodyne, and it is the best-maintained YouTube extractor that exists.
- **No to "the binary":** the official release binaries cannot run on Android. They are PyInstaller builds linked against glibc (`/lib/ld-linux-aarch64.so.1`) or musl (`/lib/ld-musl-aarch64.so.1`), and Android has neither loader.
- **They also contain GPL code:** GNU Readline (GPL-3.0-or-later, Linux builds) and mutagen (GPL-2.0-or-later).
- **What works:** embed yt-dlp as a *Python library*. Use Chaquopy (MIT) and CPython (PSF), install yt-dlp and yt-dlp-ejs without their optional extras, and plug in a small JS engine (QuickJS through quickjs-kt, Apache-2.0/MIT) for YouTube's JS challenges. Everything shipped is then Unlicense, MIT, ISC, Apache-2.0, PSF-2.0 or BSD/zlib, plus MPL-2.0 for the certifi CA data. No GPL.

**Ranked recommendation**

1. **Winner: yt-dlp embedded in-process (option A).**
   - **Stack:** Chaquopy 17 + CPython 3.13 + yt-dlp (pinned stable) + yt-dlp-ejs. A custom JS-challenge provider plugin runs the solver in quickjs-kt in-process.
   - **Bridge:** a Kotlin `YtDlpStreamResolver` implements the existing `:youtube:api` interfaces. It replaces the GPL `:youtube:streams` module with an Unlicense `:youtube:ytdlp` module.
   - **Signed updates without an app release:** the app may download a newer yt-dlp and yt-dlp-ejs at runtime. They are pure Python and JS, so Android's W^X rule does not apply. A manifest signed by the project key gates each update, and it is published only after the project's canary passes.
   - **Why it wins:**
     - It moves the real burden, keeping up with YouTube, to the most active team in this space: 500 commits and 142 nightlies in the last 12 months.
     - Upstream fixed the last two big breaks in 0–2 days: the `android_vr` 403s of 2026-08-17 and the n-challenge break of 2026-03-03.
     - Runtime updates deliver those fixes to a GitHub-only, unverified-developer app without asking users to install a new APK.
     - It covers everything NewPipe Extractor did for Neutrodyne: stream URLs, duration and availability, `@handle` resolution, channel tabs for the back catalogue, and channel search via the `sp=EgIQAg==` search URL.
   - **Conditions — a spike in M9's first week must confirm:**
     - APK growth is acceptable: estimated +13–20 MB per ABI. That breaks N5's "foss APK < 25 MB" unless the APK is split per ABI and yt-dlp is trimmed.
     - Cold start of Python + yt-dlp is ≤ 2 s on the reference device. Unverified; on the x86 host it was 0.19 s warm.
     - Extra RSS is ≤ 60 MB. It was ~39 MB on the x86 host.
     - Chaquopy 17 builds under AGP 9.4.1 with built-in Kotlin 2.4.20. Its changelog says AGP 9.0–9.4 is supported.
2. **Runner-up: a Kotlin InnerTube client ported from yt-dlp's Unlicense source (option C).**
   - **Fast path:** a VISIONOS `/player` call, which needs no JS and no PO token today — one HTTP POST through the app's own OkHttp.
   - **Client config:** a signed remote config holds client names, versions, user agents and the fallback order.
   - **JS fallback:** the yt-dlp-ejs solver (Unlicense+MIT+ISC) in quickjs-kt handles the `tv_downgraded` and `web_embedded` clients.
   - **Strengths:** smallest (≈1 MB/ABI with QuickJS, ≈0.1 MB without), fastest, best Media3/OkHttp integration, and 100 % own Unlicense code.
   - **Weakness:** the owner becomes the extractor maintainer. Every structural YouTube change must be ported by hand, with a lag of days to weeks, and browse/search parsing churns constantly.
   - **When it becomes the winner:** if the spike fails on size, latency or memory, or Chaquopy blocks the toolchain.
3. **Keep as plan C: YouTube.js 18.1.0 + googlevideo 4.1.1 + BgUtils 4.0.3 (MIT, option B).** It is the *only* permissive stack that can handle SABR-only streaming and BotGuard/PO tokens. Promote it only if YouTube removes all non-SABR, PO-token-free clients. yt-dlp has no SABR downloader (it only warns), and its PO-token plugin ecosystem is GPL (bgutil-ytdlp-pot-provider is GPL-3.0).
4. **GPL NewPipe Extractor in a separate companion APK (option F)** — only if the owner accepts publishing a GPL add-on. It keeps the main APK Unlicense, but the project still distributes GPL code and users install two apps.
5. **Reject:**
   - Public Invidious/Piped (option D): five listed instances, datacenter IPs blocked, privacy leak, volunteer bandwidth.
   - Everything else surveyed (option E): RustyPipe is GPL-3.0; java-youtube-downloader is dead; ytdl-core is archived; kkdai/youtube (Go) still defaults to the `android_vr` client that YouTube 403s; pytubefix offers nothing over yt-dlp; YoutubeExplode is .NET.

**Correction to the earlier claim** that "the only mature way to play YouTube audio is a GPL-3.0 library (NewPipe Extractor)": this is true only for *JVM* libraries. yt-dlp (Unlicense) is at least as mature and recovers faster. YouTube.js (MIT) is mature enough as a fallback.

**What would change the ranking**
- **Spike numbers miss (size, cold start, RSS) or Chaquopy cannot follow AGP:** option C wins. Chaquopy's last release is 17.0.0 from 2025-12-01.
- **YouTube makes every PO-token-free client SABR-only, or requires PO tokens for VISIONOS, tv and web_embedded:** option B is the only permissive path.
- **The owner rejects runtime code updates:** option A loses its biggest advantage over C. It still wins on upstream maintenance, but each fix then needs an APK release.
- **The owner must keep 32-bit ARM devices:** Chaquopy publishes no armeabi-v7a runtime for Python ≥ 3.12. Use option C on those devices, or pin Python 3.11 (EOL Oct 2027).

---

## Options considered (trade-off table)

Legend: ✅ good · ⚠️ caveat · ❌ blocker. "Recovery" = time from a YouTube break to a fix users can get.

| | A. yt-dlp in Chaquopy (+QuickJS) | B. YouTube.js + googlevideo + BgUtils in JS engine | C. Kotlin InnerTube client (port of yt-dlp logic) | D. Invidious / Piped | E. Other libs | F. NPE (GPL) in companion APK |
|---|---|---|---|---|---|---|
| **Licence of everything shipped** | ✅ Unlicense (yt-dlp, ejs core), MIT (Chaquopy, QuickJS, astring), ISC (meriyah), Apache-2.0 (quickjs-kt, OpenSSL 3.0.18), PSF-2.0 (CPython), MPL-2.0 (certifi CA data). **Must exclude** mutagen (GPL-2.0+) and never use youtubedl-android (GPL-3.0) | ✅ MIT (all three) + engine (QuickJS MIT / Android WebView / androidx.javascriptengine Apache-2.0) + MIT/ISC polyfills | ✅ Unlicense (own code; porting yt-dlp needs no clean room) + optional QuickJS/ejs | ✅ nothing shipped (servers are AGPL, irrelevant to the client) | ❌ RustyPipe GPL-3.0; ✅ others permissive but dead/weak | ⚠️ main app Unlicense; companion GPL-3.0-or-later |
| **Maturity / cadence 2025–26** | ✅ 26 stable releases in 2025, 10 in 2026 so far, 142 nightlies in 12 months, 500 commits in 12 months, 3 core maintainers | ⚠️ YouTube.js: 165 commits in 12 months, mostly one maintainer; release gaps 2025-10-16 → 2026-03-16; googlevideo gap 2025-09 → 2026-07; BgUtils gap 2025-03 → 2026-07 | ⚠️ as good as the owner's own follow-up of yt-dlp | ❌ 5 listed instances; "host at home" | ❌ (see option E notes) | ✅ NPE: 297 commits in 12 months; but release gap 2025-07-31 → 2026-01-11 |
| **Recovery from recent breaks** | ✅ n-challenge break 2026-03-03: mitigated same day, fixed 03-13. `android_vr` 403s from 2026-08-17: fixed in nightly 08-18, stable 08-19 | ⚠️ n-function break reported 2026-03-05 → fixed in 17.0.0 on 2026-03-16 (11 d). VISIONOS client added 2026-08-12 (yt-dlp: 2026-07-09) | ⚠️ owner-dependent; remote config fixes "client X dead" in hours, structural changes need an APK | ❌ months ("might take months"); instances often IP-blocked | ❌ kkdai/youtube still defaults to `android_vr` | ⚠️ like NPE (days to weeks); companion updates independently |
| **APK size (per ABI)** | ❌/⚠️ +≈13–20 MB: CPython runtime .so 10.6 MB uncompressed + lib-dynload 4.5 MB + stdlib .pyc 4.3 MB zipped + yt-dlp .pyc 1.4 MB (YouTube-only) to 6.0 MB (full) + QuickJS 0.9 MB | ✅ +≈1.5 MB JS bundle + 0.9 MB QuickJS (or 0 MB with WebView / JavaScriptSandbox) | ✅ ≈0.1 MB, +0.9 MB with QuickJS fallback | ✅ ~0 | — | ✅ main app −2–3 MB; companion ≈5 MB |
| **Latency per resolve** | ⚠️ cold: Python start + import (host 0.19 s warm; phone Unverified 1–3 s), once per process. Warm: 1 POST (VISIONOS, `player_skip`) + ~0.1 s Python. JS fallback: player JS 2.7 MB + solver (QuickJS host: 4.9 s first, 0.48 s cached) | ⚠️ engine start + 1–2 requests; decipher in QuickJS ≈ seconds on first player | ✅ 1 POST (~0.3–1 s on mobile); fallback as in A | ⚠️ +1 hop via third party | — | ⚠️ IPC + NPE's 4 InnerTube requests (plan: 0.5–2 s) |
| **Runtime update without app release** | ✅ pure Python/JS; download and verify, then put it on `sys.path` | ✅ JS bundle | ⚠️ config + solver JS only | ✅ (server-side, not ours) | — | ⚠️ companion APK update (still an APK) |
| **Integration effort (Kotlin/Media3)** | ⚠️ ~1 milestone: Chaquopy Gradle, Python glue, JSON mapping, JSC plugin, updater, IP-family handling | ❌ 2–3 milestones: fetch/stream/URL/TextEncoder shims or WebView bridge; SABR → Media3 `DataSource` bridge | ✅ VISIONOS path ~½ milestone; JS fallback +½; browse/search parsing +½–1 | ✅ simple REST | — | ⚠️ ~½–1 milestone: AIDL service, signature permission, `<queries>`, two release trains |
| **Feature coverage (stream, duration/flags, handle, back catalogue, channel search)** | ✅ all (`extract_info`, `youtube:tab`, search URL with `sp=EgIQAg==`) | ✅ all, plus SABR and PO tokens | ⚠️ all, but every endpoint parser is ours | ⚠️ most, instance-dependent | — | ✅ all (as today) |
| **ToS / legal exposure (base: all violate YouTube ToS on automated access and downloading)** | Same base. VISIONOS path solves no cipher; the JS fallback solves n/sig, which German courts treated as circumventing an effective technical measure (youtube-dl/Uberspace) | Highest: also emulates BotGuard attestation (BgUtils) if PO tokens are used | Same as A | Shifts extraction to third parties; leaks the user's listening to them; YouTube's legal team targeted Invidious in 2023 | — | Same as NPE today |
| **Privacy** | Direct to Google (as NPE) | Direct to Google | Direct to Google | Instance operator sees everything | — | Direct to Google |
| **Verdict** | **Winner (conditional on spike)** | **Plan C (SABR/PO-token future)** | **Runner-up** | Reject | Reject | Only if GPL add-on acceptable |

---

## Technical detail

### What "obtaining an audio URL" requires in October 2026

From yt-dlp source at commit `51bab8a` (2026-09-27), `yt_dlp/extractor/youtube/_base.py` and `_video.py`:

| Client (yt-dlp name) | JS player needed (n/sig challenges) | PO token for googlevideo HTTPS | Notes in source |
|---|---|---|---|
| `visionos` (`clientName` VISIONOS, `clientVersion` 1.02, client id 101) | **No** (`REQUIRE_JS_PLAYER: False`) | **No** (no policy) | "Made for kids" videos unavailable. Default JS-less client |
| `android_vr` | No | Required unless a player token is present | "Since 2026.08.17, ALL formats (including live HLS and itag 18) are 403'd with version 1.65.10"; removed from defaults |
| `tv`, `tv_downgraded` | Yes | No | Fallback when `visionos` cannot access a video |
| `web_embedded` | Yes | No | Fallback; age-restricted sometimes |
| `web`, `web_safari`, `mweb` | Yes | **Required** (`WEB_PO_TOKEN_POLICIES`) | — |
| `ios`, `android` | No | Required unless a player token is present | — |

Defaults: `_DEFAULT_CLIENTS = ('visionos', 'web')`; `_DEFAULT_JSLESS_CLIENTS = ('visionos',)`.

**Implication:** today every ecosystem hangs on the same VISIONOS client:
- yt-dlp without JS;
- NewPipe Extractor `dev`, which the plan says relies on VISIONOS alone;
- YouTube.js 18.x, which added VISIONOS on 2026-08-12.

When VISIONOS goes, the next tier needs a JS engine to solve n/sig challenges (tv, web_embedded). After that come PO tokens (BotGuard) and SABR, which today only YouTube.js/googlevideo/BgUtils address with permissive code. Any option must therefore ship, or be able to add, a JS engine.

### A. yt-dlp embedded with Chaquopy (winner)

**Packaging**
- Chaquopy 17.0.0 Gradle plugin. Python 3.13: 16 KB page-size devices "best supported with Python 3.13 or later" per the changelog. Python 3.13 has no armeabi-v7a runtime (Maven Central has no `target-3.13.9-0-armeabi-v7a.zip`); 3.11 still has one.
- `pip { install "yt-dlp==2026.8.19" (no extras); install "yt-dlp-ejs==0.8.0" }`. Vendor the wheel files in the repo (`wheels/…whl`) for reproducible builds. Chaquopy 17's pip uses `--only-binary` and accepts local wheels.
- **Do not** install yt-dlp's `default` extra. It pulls in mutagen (GPL-2.0+), which yt-dlp needs only for post-processing. For audio-URL extraction it needs none of brotli, requests, urllib3, websockets, pycryptodomex or curl_cffi: its stdlib urllib handler is enough.
- Trimming (optional, Unverified that it imports cleanly): drop non-YouTube extractors by patching `yt_dlp/extractor/_extractors.py`. In my measurement the full .pyc set zips to 6.0 MB; the YouTube, common and core subset to 1.4 MB.

**APK size estimate per ABI**, from Chaquopy 3.13.9-0 Maven artifacts:
- Native libraries:
  - `libpython3.13.so` 5.4 MB
  - `libcrypto_python.so` (OpenSSL 3.0.18) 3.7 MB
  - `libssl` 0.6 MB
  - `libsqlite3` 0.9 MB
  - That is 10.6 MB uncompressed, stored uncompressed by default (AGP `useLegacyPackaging=false`).
- `lib-dynload` modules 4.5 MB.
- stdlib .pyc zip 4.3 MB (download size).
- Total ≈ +13–20 MB per ABI, depending on packaging and trimming. Unverified until built.
- A universal APK with arm64-v8a and x86_64 doubles the native part. GitHub releases should ship per-ABI APKs (the youtubedl-android README recommends ABI splits for the same reason).

**Kotlin ↔ Python bridge**
- A tiny Python module `neutrodyne_yt.py` exposes `resolve(video_id, prefs_json) -> json`, `channel_tab(...)`, `search_channels(...)` and `resolve_handle(...)`.
- Each function calls `YoutubeDL(params).extract_info(url, download=False, process=False)` (or with processing for formats) and returns `YoutubeDL.sanitize_info(...)` as JSON. The yt-dlp README says `extract_info` is not guaranteed JSON-serialisable without `sanitize_info`.
- Params:
  - `extractor_args={'youtube': {'player_client': ['visionos'], 'player_skip': ['webpage', 'configs']}}`; on failure, retry with defaults (`visionos,web` plus yt-dlp's own `tv_downgraded`/`web_embedded` fallbacks).
  - `socket_timeout`.
  - `cachedir=<app cache>/yt-dlp`. This caches the preprocessed player JS; it cut solve time 10× in my benchmark.
  - `source_address` for IP family (below).
- `AudioStreamSelector` stays as designed. Map each yt-dlp format to `AudioCandidate`:
  - `format_id` gives the itag; `'251-drc'` marks DRC (yt-dlp sets `isDrc` → `-drc` and the "DRC" note).
  - `acodec`, `abr`, `filesize`.
  - `language` and `language_preference` (original track = highest).
  - `protocol == 'https'` for progressive.
- Use structured fields where possible instead of error strings:
  - `availability`: `public`, `unlisted`, `needs_auth`, `subscriber_only`, `premium_only`.
  - `live_status`: `is_upcoming`, `is_live`, `was_live`, `post_live`, `not_live`.
  - `age_limit`, `duration`.
- `ExtractorError` messages are the fallback classifier. They are string-based; keep a recorded-message test corpus.
- Threading: Chaquopy calls block. Run them on `Dispatchers.IO.limitedParallelism(2)` and keep the existing 20 s/25 s timeouts. A Python call cannot be cancelled mid-flight; bound it with yt-dlp's socket timeout and drop late results.

**JS challenges (for the fallback clients)**
- yt-dlp ships QuickJS support (`QuickJSJCP`), which runs the `qjs` executable through `subprocess`. On Android an executable must come from the APK's native library dir (Android 10 W^X: apps "cannot invoke `execve()` directly on files within the app's home directory"), packaged as `libqjs.so` with `useLegacyPackaging=true`.
- Better: register a **custom JSC provider plugin** (public API `yt_dlp.extractor.youtube.jsc.provider`) whose `_run_js_runtime`-equivalent passes the script to Kotlin (`from java import jclass`) and evaluates it in **quickjs-kt** 1.0.15. That library bundles QuickJS commit `04be246` of 2026-06-16, newer than the 2025-04-26 optimisation threshold yt-dlp warns about.
- Alternative engine: `androidx.javascriptengine` 1.1.1 (V8 in WebView's sandboxed process, API 26+ "if the WebView implementation supports it"; results as strings), with QuickJS as fallback.
- **My benchmark** (2026-10-05; Intel Xeon 2.1 GHz, single thread; player `631d3938`, `main` variant, 2.7 MB; EJS 0.8.0; n and sig in one call; output matched EJS's test vector `KBx1qz7jMhxELa8c → ttPvh7WIptsgSw`):

| Engine | First solve (parse + preprocess) | Cached (preprocessed player) |
|---|---|---|
| QuickJS 2026-06-04 (`qjs`) | 4.94 s | 0.48 s |
| Node 22 (V8 JIT) | 1.15 s | 0.23 s |
| Node 22 `--jitless` | 2.55 s | — |
| QuickJS, `tv` player variant (2.1 MB) | 3.59 s | — |

  Expect a mid-range phone to be about 1.5–3× slower (Unverified). The first solve happens once per player version, which YouTube rotates every few days to weeks; afterwards the cached run dominates. Not needed at all while VISIONOS works.

**Networking and IP binding**
- yt-dlp makes its own HTTP calls (urllib + Chaquopy's OpenSSL), not through OkHttp. googlevideo URLs carry `ip=` and are bound to the requesting IP.
- Mitigation 1: set `source_address='0.0.0.0'` or `'::'` consistently with 01's `FamilyHintDns` (yt-dlp's `--force-ipv4`/`--force-ipv6` map to `source_address`), or derive the hint from the URL's `ip=` as the design already does.
- Mitigation 2 (cleaner, Unverified effort): a yt-dlp `RequestHandler` plugin (`yt_dlp.networking.common.register_rh`) that forwards requests to the app's `@HttpClient(YOUTUBE)` OkHttp client via Chaquopy. Interceptors, DNS family hints, cancellation and proxy settings then match exactly.

**Runtime updates (key advantage)**
- Baseline: the yt-dlp and ejs versions bundled in the APK.
- `YtDlpUpdater` (WorkManager, daily and when the breaker opens):
  1. Fetch the project-signed manifest `{ytdlp: {version, url, sha256}, ejs: {...}}` from the Neutrodyne GitHub repo or release.
  2. Verify its signature with an ECDSA P-256 key pinned in the APK; `java.security` supports this on all API levels.
  3. Download the upstream wheel from PyPI or GitHub, check its SHA-256, and unpack it into `noBackupFilesDir/py/<version>/`.
  4. Prepend that directory to `sys.path` before the first `import yt_dlp`, then restart the Python worker.
- Upstream also signs releases: `SHA2-256SUMS.sig` and `SHA2-512SUMS.sig` exist for 2026.08.19, and the repo carries `public.key`. PyPI serves PEP 740 provenance for the wheel. But verifying OpenPGP in the app would pull in a large library, so the project's own manifest is the trust root. CI publishes it only after the canary passes. That also gives the owner a kill-switch: the manifest can pin a known-good version.
- Precedent: youtubedl-android exposes `updateYoutubeDL(context, UpdateChannel.STABLE|NIGHTLY)`. It is GPL, so precedent only.
- Version coupling: yt-dlp checks the EJS script hashes it was built with (`vendor/_info.py`, `VERSION = '0.8.0'`), so always update yt-dlp and ejs as a pair, exactly as pinned by that yt-dlp version.

**Alternative embedding to Chaquopy (if it stalls):** python.org now publishes official "Android embeddable package" builds (3.14.7 on 2026-08-05: aarch64 21.4 MB, x86_64 21.8 MB; no 32-bit, per PEP 738). The app would need its own JNI glue (no Java↔Python bridge). Estimated +½ milestone.

### B. YouTube.js + googlevideo + BgUtils (plan C, kept)

- Versions: youtubei.js 18.1.0 (2026-09-22), googlevideo 4.1.1 (2026-07-13), bgutils-js 4.0.3 (2026-08-04); all MIT. YouTube.js depends on fflate (MIT), meriyah (ISC) and @bufbuild/protobuf (Apache-2.0). Browser bundle: 1.56 MB unminified.
- Platform shim required by YouTube.js (`src/platform/README.md`):
  - `fetch`, `Headers`, `Request`, `Response`, `FormData`, `File`, `ReadableStream`;
  - `sha1hash`, `uuidv4`, a `Cache`;
  - an `eval` for the deciphering code. The default throws: "To decipher URLs, you must provide your own JavaScript evaluator". Since 17.0.0 `return new Function(data.output)();` is enough.
  - None of these exist in bare QuickJS, so polyfills plus a Kotlin `fetch` bridge are needed. In a WebView they exist, but CORS and the lack of POST bodies in `shouldInterceptRequest` still force a bridge.
- BgUtils' README: "it does not bypass BotGuard; you still need a runtime environment that meets its checks". In practice that means a real browser engine (WebView), which is heavy inside a `MediaSessionService`.
- SABR: googlevideo implements UMP/SABR. Playing SABR in Media3 means a custom `DataSource` fed from JS — the 2–3 milestones the plan estimates. This is the only reason to keep B: it is the permissive escape hatch if YouTube moves the remaining clients to SABR-only or PO-token-only.

### C. Kotlin InnerTube client (runner-up)

- **Legal basis:** port from yt-dlp (Unlicense: no attribution, no clean room needed) and/or YouTube.js (MIT: keep the notice). Keep the plan's rule: never read or copy NewPipe/LibreTube code into Unlicense modules.
- **Resolve request** (fields as in yt-dlp `_generate_player_context` and `INNERTUBE_CLIENTS['visionos']`):
  - `POST https://www.youtube.com/youtubei/v1/player?prettyPrint=false`
  - Body:
    - `context.client`: `clientName VISIONOS`, `clientVersion 1.02`, `deviceMake Apple`, `deviceModel RealityDevice17,1`, `osName visionOS`, `osVersion 26.5.23O471`, the Safari `userAgent`, `hl`/`gl`;
    - `videoId`;
    - `playbackContext.contentPlaybackContext.html5Preference = HTML5_PREF_WANTS`;
    - `contentCheckOk`, `racyCheckOk`.
  - Header: `X-YouTube-Client-Name: 101`.
  - Parse `playabilityStatus`, `videoDetails` and `streamingData.adaptiveFormats[]` (`itag`, `url`, `mimeType`, `averageBitrate`, `contentLength`, `audioTrack`, `isDrc`, `xtags`, `lastModified`) and `expiresInSeconds`.
- **Remote config** (signed, like A's manifest): ordered client list with full context and user agents, so a dead client is fixed in hours without an APK.
- **JS fallback:**
  1. Fetch the player URL and the player JS.
  2. Extract `signatureTimestamp`.
  3. Run the EJS solver (yt-dlp-ejs, Unlicense core plus meriyah ISC and astring MIT) in quickjs-kt with a preprocessed-player cache.
  4. Rewrite `n` and decode `signatureCipher`.
  The solver script itself can be updated at runtime (yt-dlp's own "remote components" do the same from GitHub or npm).
- **Other endpoints the app needs:**
  - `/navigation/resolve_url` (handle → `UC…`; stable);
  - `/browse` (Videos tab and continuations for back catalogue and enrichment; renderer churn: `lockupViewModel` etc.);
  - `/search` with `params=EgIQAg==` (channels).
  The browse and search parsers are the maintenance hot spot; yt-dlp's `_tab.py` and NPE's 2026 commits show frequent renderer changes.
- **Effort** (Unverified estimates): VISIONOS resolver with recorded-response tests ≈ ½ milestone; JS fallback ≈ ½; browse/search ≈ ½–1. Ongoing: weekly diff of yt-dlp `ie/youtube` commits.

### D. Invidious / Piped / self-hosted proxy

- The Invidious docs list five clearnet instances and say "The list of public instances is short due to the recent YouTube issues. If you can, please host Invidious at home". Public instances "MUST use a system that rotates the IP addresses used for communication with YouTube's servers".
- Media URLs fetched by an instance are bound to the instance's IP, so audio must be proxied through the instance. That means volunteer bandwidth and a full listening history visible to the operator.
- YouTube's legal team demanded Invidious shut down within 7 days (8 June 2023).
- A self-hosted proxy (yt-dlp on a server) fits neither a GitHub-only consumer app nor "no infrastructure".
- At most: an advanced "custom Invidious instance" setting for self-hosters. Not a primary path.

### E. Other libraries surveyed

| Library | Licence (verified) | State | Verdict |
|---|---|---|---|
| RustyPipe (Rust) | GPL-3.0 (codeberg LICENSE) | crate 0.11.4, 2025-04-23; repo updated 2026-08-03 | ❌ GPL |
| sealedtx/java-youtube-downloader (Java) | Public-domain text (Unlicense) | last commit 2025-05-27; open issues 2025: 403s, null responses, "login required" | ❌ broken/unmaintained |
| distube/ytdl-core (JS) | MIT | archived 2025-08-16; README points to youtubei.js | ❌ archived |
| HaarigerHarald/android-youtubeExtractor | BSD-style | last commit 2022-02-10 | ❌ dead |
| kkdai/youtube (Go, gomobile possible) | MIT | active (2026-08-29), but `DefaultClient = AndroidVRClient` — the client yt-dlp reports fully 403'd since 2026-08-17 | ❌ lags; Go runtime +MBs |
| pytubefix (Python) | MIT | active (11.2.0, 2026-09-30) | ❌ same embedding cost as yt-dlp, smaller team |
| rusty_ytdl (Rust) | MIT | last commit 2026-01-18 | ❌ low activity |
| YoutubeExplode (C#) | MIT | active (2026-10-02) | ❌ .NET on Android |
| youtubedl-android (Android wrapper of yt-dlp) | **GPL-3.0** | 0.18.1, 2025-11-16 | ❌ GPL; use Chaquopy directly |
| bgutil-ytdlp-pot-provider (yt-dlp PO-token plugin) | **GPL-3.0** | 2.0.1 | ❌ GPL (matters if PO tokens are ever needed in A) |
| NewPipe Extractor / NewPipe / LibreTube / RustyPipe / InnerTune family | GPL-3.0 | — | ❌ GPL |

### F. "NPE is GPL, so the APK must be GPL" — and the companion-app idea

- **The claim is correct** for an APK that links NewPipe Extractor in-process. Its LICENSE is GPLv3 and its README says "GNU General Public License … either version 3 … or (at your option) any later version". Classes compiled into the same `classes.dex` are one program. The FSF GPL FAQ: "If modules are designed to run linked together in a shared address space, that almost surely means combining them into one program". GPLv3 §5(c) then requires the whole work to be licensed under the GPL when conveyed.
- **What it means in practice for this owner:**
  - The Unlicense *source files* stay Unlicense in the repo; anyone can still take them under the Unlicense.
  - Only the combined APK is conveyed under GPL-3.0-or-later.
  - Obligations: licence notices plus Corresponding Source for the APK. For a public GitHub repo with tagged releases, that is a release asset or a link to the tag.
  - The cost is mostly labelling and principle, not work.
- **A separate *process* inside the same APK (`android:process`) changes nothing.** It is still the same package and dex.
- **A separate companion APK** (NPE + thin AIDL service; main app calls `resolveAudio(videoId) → url` over a bound service protected by a signature permission, with a `<queries>` entry):
  - FSF FAQ: "pipes, sockets and command-line arguments are communication mechanisms normally used between two separate programs". A narrow string-in, URL-out IPC is the textbook case of separate programs. The FAQ adds that very "intimate" exchanges of complex internal data could still count as one program, so keep the interface narrow.
  - Practical effect: the main APK stays purely Unlicense, but the project still **publishes GPL code** (the companion and its Corresponding Source). Users install, update and trust two apps.
  - Under Google developer verification each app is an unverified install, though the "advanced flow" is a one-time per-device setting.
  - The companion can be hot-fixed independently, which is a small plus.
  - It does **not** meet "avoid GPL" if the owner means "we never ship GPL". It only meets "the Neutrodyne app itself is not GPL".
  - Recommendation: only worth it if the owner relaxes the goal *and* options A/C fail.

### Legal / ToS (same base for all; differences only)

- All options access YouTube by automated means and separate audio, which YouTube's Terms and API policies forbid (see 04-youtube.md, Play guardrails). With GitHub-only distribution, Play policy no longer applies.
- **Differences:**
  1. The VISIONOS path (A and C) downloads plain URLs with no cipher to solve.
  2. JS n/sig solving (fallback in A and C; NPE and YouTube.js do it too) is what LG Hamburg (March 2023) and OLG Hamburg (November 2024; reported by heise 2024-11-27) treated, in the youtube-dl/Uberspace case, as circumventing YouTube's "rolling cipher" as a technical protection measure. That was a German case against a *host*; the owner's jurisdiction (Switzerland, given `ch.lkmc`) differs. Not legal advice.
  3. PO-token minting (BgUtils) emulates Google's anti-abuse attestation, so exposure is arguably highest.
  4. Invidious/Piped move the extraction to someone else but leak user data to them.
- **Runtime code download:** allowed outside Play. Its main risk is supply-chain compromise, mitigated by the project-signed manifest.

---

## Verified facts (with source URLs and dates)

All read on 2026-10-05 unless stated otherwise.

**yt-dlp**
1. Licence is the Unlicense ("This is free and unencumbered software released into the public domain"): https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE. PyPI `license_expression: Unlicense`, `requires_python >=3.10`: https://pypi.org/pypi/yt-dlp/json
2. Latest stable 2026.08.19 (uploaded 2026-08-19); latest nightly 2026.9.27.232945.dev0. 142 nightlies uploaded to PyPI in the last 12 months. Stable releases: 26 in 2025, 10 in 2026 so far (PyPI JSON, same URL). Wheel 3,185,533 bytes.
3. yt-dlp's `default` extra includes `mutagen`, `yt-dlp-ejs==0.8.0`, `certifi`, `brotli`, `requests`, `urllib3`, `websockets` and `pycryptodomex` (PyPI `requires_dist`, same URL).
4. Third-party licences of the *PyInstaller binaries*: "GNU Readline | GPL-3.0-or-later — Note: Only included in Linux builds"; "mutagen | GPL-2.0-or-later"; also LGPL libintl/libidn2/libunistring; Meriyah ISC; Astring MIT: https://github.com/yt-dlp/yt-dlp/blob/master/THIRD_PARTY_LICENSES.txt
5. Release 2026.08.19 assets include `yt-dlp_linux_aarch64`, `yt-dlp_musllinux_aarch64`, `yt-dlp` (zipimport), `SHA2-256SUMS(.sig)` and `SHA2-512SUMS(.sig)`. ELF headers (first 4 KiB, fetched 2026-10-05): interpreters `/lib/ld-linux-aarch64.so.1` and `/lib/ld-musl-aarch64.so.1` respectively. Source: https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19
6. Default clients `('visionos', 'web')`, JS-less `('visionos',)`; VISIONOS `REQUIRE_JS_PLAYER: False`, no PO-token policy; `android_vr` comment "Since 2026.08.17, ALL formats … are 403'd"; web clients require a GVS PO token. Sources, at commit 51bab8a (2026-09-27): https://github.com/yt-dlp/yt-dlp/blob/51bab8a0116f4d8004c315706d809782607d5847/yt_dlp/extractor/youtube/_base.py and https://github.com/yt-dlp/yt-dlp/blob/51bab8a0116f4d8004c315706d809782607d5847/yt_dlp/extractor/youtube/_video.py
7. Supported JS runtimes and minimums: Deno ≥ 2.3.0, Node ≥ 22, Bun ≥ 1.2.11 (deprecated), QuickJS ≥ 2023-12-09, QuickJS-ng any. The QuickJS provider runs `qjs --script <tempfile>` via subprocess. Sources: `yt_dlp/utils/_jsruntime.py` and `yt_dlp/extractor/youtube/jsc/_builtin/quickjs.py` at the same commit. Wiki: "QuickJS versions prior to 2025-4-26 are missing optimizations which can lead to execution times of several minutes" — https://github.com/yt-dlp/yt-dlp/wiki/EJS
8. JS Challenge Provider plugin API (public: `yt_dlp.extractor.youtube.jsc.provider`): https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/jsc/README.md
9. 2025.11.12 "An external JavaScript runtime is now required for full YouTube support"; 2025.10.22 stopgap; issue https://github.com/yt-dlp/yt-dlp/issues/15012 ("Support for YouTube without a JavaScript runtime is now considered 'deprecated'"). Changelog: https://github.com/yt-dlp/yt-dlp/blob/master/Changelog.md
10. `android_vr` break: PR #17461 opened and merged 2026-08-18, closing #17456, "Remove `android_vr` from default clients". Nightly 2026.8.18.122307.dev0 uploaded 2026-08-18 12:27 UTC; stable 2026.08.19. Sources: https://github.com/yt-dlp/yt-dlp/pull/17461 and PyPI.
11. n-challenge break: issue #16118 opened 2026-03-03 ("n challenge solving failed"); PR #16123 "Force player `9f4cc5e4`" merged 2026-03-03 (release 2026.03.03); proper fix 2026-03-13 (ejs 0.7.0, yt-dlp 2026.03.13). Sources: https://github.com/yt-dlp/yt-dlp/issues/16118 and https://github.com/yt-dlp/yt-dlp/pull/16123
12. VISIONOS client added to yt-dlp 2026-07-09 (commit 1328586, #17184); released in stable 2026.08.19 (git log, Changelog).
13. Activity in the 12 months to 2026-10-05: 500 commits on master (top: bashonly 191). Core maintainers listed: coletdjnz, bashonly, Grub4K (Maintainers.md). Source: git log of https://github.com/yt-dlp/yt-dlp
14. Channel search through the search URL with `sp=EgIQAg%253D%253D` returns channel entries (test case in `yt_dlp/extractor/youtube/_search.py`, same commit).
15. Embedding: "we do not guarantee the return value of `YoutubeDL.extract_info` to be json serializable … pass it through `YoutubeDL.sanitize_info`": https://github.com/yt-dlp/yt-dlp#embedding-yt-dlp
16. yt-dlp has no SABR downloader; it only warns "YouTube is forcing SABR streaming for this client" (`_video.py` around line 3544, same commit).

**yt-dlp-ejs**

17. LICENSE: Unlicense. README: "prebuilt wheels … contain both meriyah and astring, licensed under ISC and MIT". PyPI `license_expression: "Unlicense AND MIT AND ISC"`. Latest 0.8.0 (2026-03-17); 0.1.0 was 2025-10-21. Dependencies: astring 1.9.0, meriyah 6.1.4. Sources: https://github.com/yt-dlp/ejs and https://pypi.org/pypi/yt-dlp-ejs/json
18. Supported engines: deno ≥ 2.3, node ≥ 22, quickjs ≥ 2023-12-9, quickjs-ng any, bun (deprecated): https://github.com/yt-dlp/ejs/blob/main/README.md
19. **Own benchmark**, 2026-10-05: QuickJS 2026-06-04 built from bellard/quickjs at 04be246; EJS 0.8.0; player 631d3938 fetched from `https://www.youtube.com/s/player/631d3938/player_ias.vflset/en_US/base.js` (2,706,639 bytes).
    - QuickJS: first solve 4.94 s; preprocessed 0.48 s; `tv` variant (2,149,629 bytes) 3.59 s.
    - Node 22: 1.15 s / 0.23 s; Node `--jitless` 2.55 s.
    - n output matched the EJS test vector.
    - yt-dlp 2026.08.19 import plus `YoutubeDL` init: 0.187 s, max RSS 39 MB (bare Python 7.5 MB), CPython 3.11 on x86_64.
    - Live extraction from the sandbox failed: HTTP 429 and "Sign in to confirm you're not a bot".

**Python on Android**

20. Chaquopy licence MIT ("Copyright (c) 2017-2025 Chaquo Ltd and contributors / Permission is hereby granted…"): https://github.com/chaquo/chaquopy/blob/master/LICENSE.txt
21. Chaquopy 17.0.0 (2025-12-01): AGP 9.0–9.4 supported; Python 3.10–3.14; "Devices with 16 KB pages are now supported … use Python 3.13 or later"; pip uses `--only-binary`; latest tag on 2026-10-05 is 17.0.0. Source: https://github.com/chaquo/chaquopy/blob/master/product/runtime/docs/sphinx/changelog.rst
22. Chaquopy runtime artifacts on Maven Central (`com.chaquo.python:target`):
    - `3.13.9-0`: arm64-v8a zip 6,657,582 B, x86_64 6,866,312 B, stdlib-pyc zip 4,295,490 B, **no armeabi-v7a**.
    - `3.11.14-0` has armeabi-v7a (5,735,183 B).
    - arm64 contents: `libpython3.13.so` 5,392,048 B, `libcrypto_python.so` 3,721,048 B (OpenSSL 3.0.18), `libssl_python.so` 623,736 B, `libsqlite3_python.so` 886,520 B (SQLite 3.50.4); `lib-dynload` total 4,533,688 B; no readline/gdbm modules.
    - Source: https://repo.maven.apache.org/maven2/com/chaquo/python/target/
23. Official CPython Android builds: Python 3.14.7 (2026-08-05) offers "Android embeddable package" aarch64 (21.4 MB) and x86_64 (21.8 MB): https://www.python.org/downloads/release/python-3147/ ; PEP 738: https://peps.python.org/738
24. youtubedl-android LICENSE is GPL-3.0; README: "yt-dlp binary can be updated from within the library … `updateYoutubeDL`", "Use abi splits to reduce apk size"; latest tag 0.18.1 (2025-11-16). Source: https://github.com/yausername/youtubedl-android
25. Android 10: "Untrusted apps that target Android 10 cannot invoke `execve()` directly on files within the app's home directory": https://developer.android.com/about/versions/10/behavior-changes-10

**JS engines**

26. quickjs-kt: Apache-2.0 (LICENSE.txt); Maven `io.github.dokar3:quickjs-kt-android:1.0.15` (2026-09-03); AAR 1,828,904 B with `libquickjs.so` arm64 924,936 B, armeabi-v7a 656,988 B, x86_64 911,248 B; QuickJS submodule at bellard/quickjs 04be246 (2026-06-16). Source: https://github.com/dokar3/quickjs-kt
27. QuickJS LICENSE MIT (bellard; quickjs-ng also MIT): https://github.com/bellard/quickjs and https://github.com/quickjs-ng/quickjs
28. Zipline (Cash App, QuickJS-based) Apache-2.0, 1.28.0 (2026-09-29): https://github.com/cashapp/zipline
29. androidx.javascriptengine 1.0.0 (2025-07-02), 1.1.0 (2026-05-06, message ports), 1.1.1 (2026-09-23): https://developer.android.com/jetpack/androidx/releases/javascriptengine. Guide: supported on API 26+ "if the WebView implementation supports it", separate sandboxed process, results as strings: https://developer.android.com/develop/ui/views/layout/webapps/jsengine
30. Javet Apache-2.0 (6.0.2): https://github.com/caoccao/Javet

**YouTube.js family**

31. YouTube.js MIT (Copyright (c) 2021 LuanRT); npm `youtubei.js` 18.1.0 (2026-09-22); 17.0.1 (2026-03-16); 16.0.1 (2025-10-16), no release in between; dependencies fflate, meriyah, @bufbuild/protobuf; `bundle/browser.js` 1,557,725 B. Sources: https://github.com/LuanRT/YouTube.js and https://registry.npmjs.org/youtubei.js
32. YouTube.js 18.0.0 (2026-08-13) "Add the `VISIONOS` client (#1213)" (commit 2026-08-12); CHANGELOG: https://github.com/LuanRT/YouTube.js/blob/main/CHANGELOG.md
33. YouTube.js issue #1146 "exportedVars.nFunction is not a function" opened 2026-03-05; fixed by PR #1152 merged 2026-03-16 ("a simple `return new Function(data.output)();` is enough"). Sources: https://github.com/LuanRT/YouTube.js/issues/1146 and https://github.com/LuanRT/YouTube.js/pull/1152
34. Platform shim requirements and default eval error: https://github.com/LuanRT/YouTube.js/blob/main/src/platform/README.md and `src/platform/jsruntime/default.ts`
35. YouTube.js commits by month: Nov 2025 1, Dec 2025 1, Feb 2026 4. 165 commits in the 12 months to 2026-10-05 (48 by dependabot). Source: git log.
36. googlevideo MIT, 4.1.1 (2026-07-13), previous 4.0.4 (2025-09-15): https://github.com/LuanRT/googlevideo , https://registry.npmjs.org/googlevideo
37. BgUtils MIT, 4.0.3 (2026-08-04), previous 3.2.0 (2025-03-02); README: "it does not bypass BotGuard; you still need a runtime environment that meets its checks": https://github.com/LuanRT/BgUtils

**Others**

38. NewPipe Extractor GPLv3-or-later (LICENSE, README, file headers). Tags: v0.24.8 2025-07-31 → v0.25.0 2026-01-11 → v0.26.5 2026-08-15. Commit 9ed62db (2026-08-06) "Remove usage of ANDROID, IOS and WEB_EMBEDDED_PLAYER clients"; 297 commits in 12 months. Source: https://github.com/TeamNewPipe/NewPipeExtractor
39. RustyPipe GPL-3.0: https://codeberg.org/ThetaDev/rustypipe ; crates.io 0.11.4 (2025-04-23): https://crates.io/crates/rustypipe
40. java-youtube-downloader public-domain licence text; last commit 2025-05-27; open issues Jan–Mar 2025 report 403s and null responses: https://github.com/sealedtx/java-youtube-downloader
41. distube/ytdl-core archived 2025-08-16, "depends on youtubei.js from now on. This fork will be no longer maintained": https://github.com/distubejs/ytdl-core
42. kkdai/youtube MIT; `var DefaultClient = AndroidVRClient`; last commit 2026-08-29: https://github.com/kkdai/youtube
43. pytubefix MIT, 11.2.0 (2026-09-30): https://pypi.org/project/pytubefix/
44. bgutil-ytdlp-pot-provider GPL-3.0 (LICENSE), tag 2.0.1: https://github.com/Brainicism/bgutil-ytdlp-pot-provider
45. Invidious: "The list of public instances is short due to the recent YouTube issues. If you can, please host Invidious at home"; 5 clearnet instances; public instances "MUST use a system that rotates the IP addresses": https://docs.invidious.io/instances/
46. YouTube's legal team asked Invidious to shut down within 7 days (email of 2023-06-08): https://alternativeto.net/news/2023/6/youtube-legal-team-asked-invidious-developers-to-take-down-the-service-within-7-days and https://mjtsai.com/blog/2023/06/12/youtube-tries-to-shut-down-invidious
47. OLG Hamburg confirmed LG Hamburg (March 2023): Uberspace liable for hosting youtube-dl; the rolling cipher was treated as a technical protection measure. heise, 2024-11-27: https://heise.de/-10179284 ; netzpolitik: https://netzpolitik.org/2024/entscheidung-des-olg-hamburg-youtube-dl-org-bleibt-gesperrt/
48. GNU GPL FAQ: "If modules are designed to run linked together in a shared address space, that almost surely means combining them into one program … pipes, sockets and command-line arguments are communication mechanisms normally used between two separate programs": https://www.gnu.org/licenses/gpl-faq.html#MereAggregation (read via WebFetch 2026-10-05; the full passage on "intimate" semantics is from the same FAQ entry; direct re-fetch failed with HTTP 503/429)
49. Android "advanced flow" for unverified apps: one-time setup with a 24-hour wait; enforcement from 2026-09-30 in Brazil, Indonesia, Singapore and Thailand; global in 2027; ADB installs exempt. Sources (secondary): https://9to5google.com/2026/03/19/android-advanced-flow-sideloading/ and https://thehackernews.com/2026/03/google-adds-24-hour-wait-for-unverified.html

---

## Pitfalls & risks

1. **Single point of failure shared by everyone: VISIONOS.**
   - yt-dlp (JS-less), NPE `dev` and YouTube.js 18 all lean on it.
   - When YouTube kills or PO-gates it, as it did `android_vr` on 2026-08-17, A survives only if the JS engine is wired in (tv/web_embedded fallbacks). C survives only if its JS fallback exists and is current.
   - **Ship the JS path in v1, not later.**
2. **SABR-only / PO-token future.**
   - yt-dlp has no SABR downloader, and its PO-token providers are GPL plugins.
   - If every PO-token-free progressive client disappears, A and C stop delivering and B (googlevideo + BgUtils, WebView) is the only permissive route: 2–3 milestones.
   - Keep `YouTubeStreamResolver` engine-agnostic, as the plan already does.
3. **APK size versus N5 (< 25 MB).**
   - Option A cannot meet 25 MB as a universal APK. Per-ABI APKs plus a trimmed yt-dlp are needed, and probably a relaxed budget (open question 1).
4. **32-bit ARM.** Chaquopy has no armeabi-v7a runtime for Python ≥ 3.12 (official CPython dropped 32-bit too). Choose between:
   - dropping YouTube playback on armv7;
   - Python 3.11 (EOL Oct 2027; yt-dlp already recommends ≥ 3.11 and will drop 3.10 soon);
   - option C on armv7 only (two engines to maintain — not recommended).
5. **Chaquopy vendor and toolchain risk.**
   - One company, last release 2025-12-01, AGP support declared up to 9.4. The plan pins AGP 9.4.1 and Kotlin 2.4.20 with built-in Kotlin; untested combination (Unverified).
   - Fallback: official CPython Android packages plus own JNI (+½ milestone).
6. **Cold start, memory, battery.**
   - Python + yt-dlp adds ~40 MB RSS (host measurement) in whichever process hosts it.
   - Consider a dedicated `:extractor` process that is killed when idle, so the playback service is not pushed towards the low-memory killer. The cost is IPC.
   - Start Python lazily at first YouTube enqueue or pre-resolve, not at app start.
7. **IP binding.**
   - yt-dlp's own HTTP stack can choose a different IP family than OkHttp's media requests. The result is 403s on IPv6/IPv4-mixed networks.
   - Pin `source_address` or route yt-dlp through OkHttp via a RequestHandler plugin. Test on IPv6 Wi-Fi and on IPv4-only mobile networks, as the plan's M9 checklist already does.
8. **Error classification drift.**
   - yt-dlp errors are strings. Rely on `availability`/`live_status`/`age_limit` fields first, and keep a test corpus of messages per pinned version.
   - Runtime updates can change messages under a shipped app. The canary must run the classifier tests before the manifest is signed.
9. **Runtime-update supply chain.**
   - Downloaded Python runs with the app's permissions.
   - Trust only a manifest signed by the project key (published by CI after the canary), pin hashes of upstream assets, keep the bundled baseline as fallback, and expose "Use bundled extractor" in Settings › YouTube.
   - Never auto-follow yt-dlp nightly.
10. **Licence hygiene traps** (CI must enforce):
    - mutagen (GPL-2.0+) via yt-dlp's `default` extra;
    - youtubedl-android (GPL-3.0);
    - bgutil-ytdlp-pot-provider (GPL-3.0);
    - the official yt-dlp Linux binaries (Readline GPL-3.0+);
    - copying from NPE, LibreTube, Seal or Tubular.
    Required notices: CPython PSF-2.0, OpenSSL 3.0.18 Apache-2.0, SQLite (public domain), libffi/expat MIT, zlib, bzip2, xz 0BSD, mpdecimal BSD-2, certifi MPL-2.0, QuickJS MIT, quickjs-kt Apache-2.0, meriyah ISC, astring MIT. 01's Licensee/AboutLibraries do not see Python wheels or `.so` files inside Chaquopy assets, so this needs a hand-maintained `THIRD_PARTY_NOTICES` plus a CI check on the wheel list.
11. **Bot checks.** Datacenter IPs are challenged: my sandbox got HTTP 429 and "Sign in to confirm you're not a bot". GitHub-hosted canaries will flake, as the plan already notes. The canary needs a residential runner or a recorded-response replay.
12. **Option C maintenance trap.**
    - The VISIONOS resolver is easy. Back catalogue, search and enrichment parsing is where YouTube churns (NPE's May–July 2026 commits are mostly `lockupViewModel` fixes).
    - Choosing C means committing to weekly upstream-diff work.
13. **Legal.**
    - Avoiding GPL does not reduce ToS or anti-circumvention exposure.
    - The JS n/sig fallback is the part courts in Germany treated as circumvention.
    - The owner refusing Google verification removes one identity link, but GitHub releases and the `ch.lkmc` package prefix still identify the publisher.

---

## Open questions for the product owner

1. **APK budget:** accept roughly +15 MB per ABI for option A and ship per-ABI APKs on GitHub (arm64-v8a, x86_64; universal optional)? Or keep N5 (< 25 MB), which favours option C?
2. **32-bit ARM devices:**
   - drop in-app YouTube playback on armeabi-v7a;
   - pin Python 3.11 until 2027;
   - or accept option C everywhere?
3. **Runtime extractor updates:** may the app download and run newer yt-dlp/ejs versions approved by a project-signed manifest, without an APK release? Choose automatic or opt-in, and decide who holds the manifest signing key.
4. **JS-challenge fallback:** include the n/sig solver (more robust; legally the "circumvention" part), or ship VISIONOS-only and accept outages when it breaks?
5. **PO tokens / SABR:** if YouTube ever requires them, do we go to option B (BotGuard emulation in a WebView, highest exposure), or stop at "YouTube playback unavailable" and an emergency build?
6. **GPL companion fallback:** acceptable as a last resort (main app Unlicense, separate GPL add-on), or is "we never publish GPL" absolute?
7. **Flavors:** with GitHub-only distribution and no GPL, should the `foss`/`play` split collapse into one build?
   - The GPL boundary, the Corresponding-Source bundle, the Rhino/JitPack pins and the F-Droid anti-feature work all disappear from 04/09.
   - Unverified: IzzyOnDroid/F-Droid would likely reject runtime code downloads anyway (moot under GitHub-only).
8. **Spike gate:** approve a one-week M9 spike with pass criteria before committing to A:
   - foss arm64 APK size,
   - cold resolve ≤ 2 s and warm ≤ 1 s on the reference device,
   - +RSS ≤ 60 MB,
   - Chaquopy under AGP 9.4.1 with Kotlin 2.4.20,
   - IPv4/IPv6 403 test.

   If it fails, switch to C.
