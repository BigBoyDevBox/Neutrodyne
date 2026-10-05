# Research notes — Self-hosted sync server (`sync-server`)

Research area: `sync-server`. Date: 2026-10-05. Owner direction of 2026-10-05: *"If we're building a new podcast player from scratch, we should probably include a self-hosted server part for syncing subscriptions and listen states and such (but not audio files) and desktop OS as build targets."*

Scope: a self-hosted server that syncs one user's library state between Neutrodyne on Android and desktop. Covered: subscriptions, groups, membership and order, played state, positions, favourites, Up next, per-podcast and per-group settings, and selected global settings. Out of scope: audio files and feed contents (every client keeps polling feeds itself, PLAN D1). The plan this builds on: `/home/user/Neutrodyne/docs/PLAN.md` (D1, D3, D18, D20, D22–D24, D29, D33–D35, D38, D41, D44) and the design documents 02 (data model), 05 (groups, OPML and backup) and 06 (playback). It also builds on the sibling research note `kmp-architecture.md` (KMP structure, Ktor client 3.6.0 in common code, Metro, the BYO-JRE desktop default, its owner question Q1 on the Java runtime).

Method: I read primary sources: the specifications, the repositories and their LICENSE files, and Maven Central and Google Maven metadata. I also built two throw-away prototypes in this session (Ktor/JVM and Go, same endpoints, same SQLite schema) to measure footprint. Every version and non-obvious claim carries a URL; anything I could not check against a primary source is marked **Unverified**.

---

## Recommendation

1. **Build our own sync protocol, "Neutrodyne Sync v1".** Add gpodder API v2 compatibility endpoints later (v1.x) for third-party apps, and an Open Podcast API (OPA) adapter once OPA tags a stable release. Neither existing API can be our primary protocol:
   - **gpodder v2** has no groups, queue, favourites, settings or explicit unplayed state. Its subscriptions are scoped per device, it identifies podcasts by URL, and its clocks have one-second precision. AntennaPod and Kasts infer "played" from `position == total` and ignore the `new`/`download`/`delete` actions as unreliable (Kasts source, cited below).
   - **OPA** is a 0.1.0 draft: "All specifications are currently 'in progress'". Only the subscriptions endpoint is on `main`. The central sync endpoint and episodes live in unmerged branches, and the auth approach changed in June 2026.

   Our protocol borrows OPA's good ideas: a per-installation client ID, one batched `sync` endpoint, per-field timestamps, and podcast GUIDs as an extra identity hint. That keeps an adapter cheap.

2. **Data model: one record per entity, with last-writer-wins per field, ordered by a hybrid logical clock (HLC).** Records are stored as server-side documents and exchanged through a server-assigned per-account cursor (`seq`).
   - **Collections:** `podcast`, `group`, `member`, `episode`, `upnext`, `session` (now playing) and `setting`.
   - **Keys:** podcasts get a new stable `syncId` (UUIDv4, like D29's group `uuid`), because `feedKey` changes on feed moves. Episodes are keyed by `(podcast syncId, identityKey)` with the D18 version prefix, plus match hints (guid, enclosure, title, date) so that clients on another key version can still match. Groups use their D29 `uuid`.
   - **Ordered lists** (group order, member order, Up next) use per-item fractional-index keys (`orderKey`) with LWW, not a sequence CRDT.
   - **Deletions** are tombstone fields (`subscribed=false`, `deleted=true`, `in=false`), kept 365 days. Merges (two podcasts that turn out to be the same feed, two groups with the same `nameKey`) are tombstones with `mergedInto`.
   - **N1 guard:** a position of 0 is never accepted unless it carries `reset: true` (explicit reset or mark-played).
   - **Mass-change guard:** a pull that would unsubscribe many podcasts or delete groups asks before it applies.

3. **The server is Kotlin + Ktor 3.6.0 on the JVM, in the same Gradle build as the apps.** It shares two KMP common-only modules with the clients: `:sync:protocol` (DTOs, HLC, `OrderKey`, field-merge rules) and `:feeds` common (`UrlNormalizer`, `EpisodeKeys`, backup models). One merge implementation therefore runs on all three runtimes.
   - Shipped as a single platform-neutral fat JAR (`neutrodyne-sync-server-{v}.jar`, ≈ 30 MB) that runs on any **Java 17+** runtime the admin installs.
   - Measured in this session on x86-64: idle RSS 92 MB with small-footprint JVM flags, 105 MB after a 50,000-change push and pull. A Go build of the same prototype idled at 9 MB.
   - JVM footprint is acceptable on a Raspberry Pi 3/4/5. sqlite-jdbc ships armv6, armv7 and aarch64 natives, so 32-bit Raspberry Pi OS works too.
   - **Go is the alternative only if the owner wants a published, GPL/LGPL-free container image** (Q2).

4. **Storage, transport and deployment:**
   - **Storage:** SQLite (WAL) only in v1, through plain JDBC with SQL kept to the SQLite ≥ 3.35 / PostgreSQL ≥ 13 common subset (`INSERT … ON CONFLICT … DO UPDATE`, `RETURNING`). PostgreSQL stays possible behind a `SyncStore` interface but is not built until someone needs it.
   - **Transport:** HTTPS + JSON (gzip both ways), RFC 9457 problem details, and Server-Sent Events (`/api/v1/events`) as a "something changed" signal while an app is in the foreground or playing. Otherwise clients poll with WorkManager on Android and an in-process timer on desktop. No WebSocket, no FCM.
   - **Deployment:** the server never fetches feeds and makes no outbound request except an optional notify-only update check. It runs behind a reverse proxy for TLS, with Caddy as the documented default.
   - **Repository assets:** a systemd unit with sandboxing, a `Dockerfile` and `compose.yaml` that users build from the tagged source (Compose accepts a Git URL as build context), and Caddy/nginx examples.
   - **Container image:** none is *published* while D3 stands. Every mainstream JVM image contains OpenJDK (GPL-2.0 + Classpath Exception) and an OS layer with GPL/LGPL code (glibc is LGPL, BusyBox is GPL-2.0).

5. **Authentication: admin-created accounts with passwordless device linking.**
   - A new device starts an RFC 8628-shaped "device code" flow (`device_code`, `user_code`, polling with `authorization_pending`/`slow_down`). The user approves it on an already-linked Neutrodyne device or on the server's web page.
   - The admin CLI or web UI issues one-time invite codes for an account's first device.
   - Optional passwords (Argon2id via Bouncy Castle, MIT) exist for the web UI and the in-app login.
   - Devices hold opaque 256-bit tokens, stored as SHA-256 hashes and revocable per device. gpodder clients get scoped app passwords.
   - No OAuth server, no OIDC and no self-registration in v1.

6. **Clients: sync is optional, and local-only stays first-class.**
   - With no server configured, the sync module does nothing: no tables written, no network, no start-up cost.
   - Once linked, local changes to synced columns are captured in the same transaction as the write: SQLite triggers write a coalescing outbox (`sync_outbox`) with an HLC from `sync_state`, guarded by `WHEN enabled AND NOT applying`. DataStore settings are captured explicitly in `SettingsRepository`.
   - Pulled records are applied by a `SyncApplier` in one transaction per page.
   - **First link reuses 05's backup Merge rules.** Union for subscriptions, groups and memberships; LWW with the rows' own `updatedAt` as HLC time for state, so a stale device never stamps old state with "now" (AntennaPod's first sync does exactly that, cited below); Up next appended.
   - Episode state for episodes a device does not have yet is *parked* and applied when ingestion finds the episode. Only queued, in-progress and favourite episodes become stubs, so D23 retention keeps working.

7. **Not in v1:**
   - End-to-end encryption. The server is the user's own. "Sealed mode" is designed as a v2 option: encrypted payloads, HMAC'd record IDs, client-side merging with compare-and-swap. It disables gpodder compatibility and the web UI's library view.
   - PostgreSQL, OIDC, push through UnifiedPush, and sharing between accounts.

8. **Roadmap.** Android v1.0 is not blocked.
   - **Groundwork in M1–M4** (+3–5 days): `podcast.syncId`, `orderKey TEXT` columns instead of `REAL`/`Int` order columns, the inert sync tables and triggers in schema v1 (D22), `:sync:protocol` with HLC/OrderKey and tests.
   - **SY1** server core (L), **SY2** client sync on Android and desktop (L), **SY3** live updates and handoff (M): ship together with desktop 1.0 as **v1.1**.
   - **SY4** gpodder compatibility (M, v1.x). An OPA adapter when OPA is stable.
   - Total ≈ 9–12 engineer-weeks (Unverified planning estimate).

9. **Licensing.**
   - **Allowing LGPL would not materially simplify the sync server.** Every component it needs has a permissive licence: Ktor and kotlinx Apache-2.0, sqlite-jdbc Apache-2.0, Bouncy Castle MIT, slf4j MIT, pgjdbc BSD-2-Clause. The LGPL candidates have permissive substitutes:
     - logback (EPL-2.0/LGPL-2.1) → `slf4j-simple`;
     - argon2-jvm (LGPL-3.0) → Bouncy Castle's Argon2;
     - MariaDB Connector/J (LGPL-2.1) → not needed.
   - **The real friction is the runtime in shipped images:** OpenJDK is GPL-2.0 + Classpath Exception, and OS base layers contain GPL and LGPL code. Allowing LGPL alone does not fix that. Solving it needs either a scoped D3 carve-out for runtimes and base layers (the same question as the desktop JRE, KMP Q1) or a Go server.
   - We may interoperate with the AGPL/GPL servers (mygpo, Nextcloud gPodder Sync, oPodSync, gpodder2go, podsync) and the GPL clients (AntennaPod, Kasts) through their documented APIs, but never copy their code.

---

## Options considered (trade-off table)

### A. Protocol

| Option | Fit for Neutrodyne | Ecosystem | Maturity / status | Licence / legal | Verdict |
|---|---|---|---|---|---|
| **A1. Own protocol "Neutrodyne Sync v1"** (record/field LWW + HLC + server cursor) | Exact: groups, member order, Up next order, settings, now-playing, versioned episode keys, feed moves | Our clients only (plus compat layers) | We own it; conformance vectors keep client and server honest | Unlicense | **Recommended** |
| A2. gpodder API v2 as primary | Subscriptions per *device* (add/remove deltas); episode actions `download`/`play`/`delete`/`new` keyed by feed URL + media URL; no groups, queue, favourites or settings; played is implicit (`position == total`); timestamps in seconds | Large: AntennaPod, Kasts, gPodder, Cardo, GNOME Podcasts, many servers | Stable but frozen; gpodder.net itself "often overloaded" (AntennaPod docs) | Reference server mygpo is AGPL-3.0 (we would only implement the documented API) | **Compat layer in v1.x** (SY4) |
| A3. Open Podcast API as primary | Closest in spirit: per-field timestamps, LWW, client IDs, podcast GUID identity, a single `sync` endpoint. No groups or settings; queue "TBD"; wall-clock timestamps ("client timestamps … are considered authoritative"); 30 actions per request | AntennaPod, Kasts, Nextcloud gPodder, Funkwhale people; oPodSync lists it on its roadmap | OpenAPI `version: 0.1.0`; on `main` only subscriptions + conventions; sync endpoint in branch `subscriptions-redux` (2026-07-13), episodes in branch `episodes-endpoint`; auth switched to OAuth in the 2026-06-29 meeting | Spec text CC BY-SA 4.0 (implementing it is fine; do not paste its text into our Unlicense docs) | **Adapter later**, when OPA tags a stable version; track and give feedback now |
| A4. PortCast (IETF Internet-Draft `draft-trimplayer-portcast-00`) | Interchange format + optional sync API with `updatedAt` LWW and `If-Match` | None known | Individual submission, 2026-05-28, expires 2026-11-29, "no formal standing" | IETF draft terms | Watch only |
| A5. Generic CRDT document (Automerge/Yjs) | Converges by construction | Not podcast-aware | Cores in Rust/JS; Kotlin use means JNI or JS runtime (Unverified for current Kotlin bindings) | MIT (Automerge, Yjs) | Rejected: heavy, opaque to the server (no gpodder layer, no web UI), overkill for LWW-shaped data |
| A6. File-based sync (backup ZIP on WebDAV/Nextcloud/Syncthing) | No server code | Works with existing tools | Whole-file conflicts, no live handoff, no third-party clients | — | Rejected as *the* sync. A scheduled backup to a folder (M15) plus Syncthing remains a zero-server workaround to document |

### B. Server implementation stack

| Option | Code sharing with clients | Footprint (measured here, x86-64; see Verified facts) | Distribution under D3 as written | Maturity | Verdict |
|---|---|---|---|---|---|
| **B1. Kotlin/JVM + Ktor 3.6.0 (CIO engine) + JDBC/SQLite** | Full: `:sync:protocol`, `:feeds` common (UrlNormalizer, EpisodeKeys, backup models), the same tests | JARs 27.5 MB (sqlite-jdbc alone 12 MB); idle RSS 92 MB (`-Xmx64m`, SerialGC, C1 only) to 133 MB (default heap); 105–173 MB after 50k changes | Fat JAR with no JRE: admin installs Java 17+ (allowed). Published container image: **not allowed** (OpenJDK GPL+CE, OS layers) | Ktor stable; JVM everywhere incl. armv7 (Temurin ARM32, distro OpenJDK) | **Recommended** |
| B2. Kotlin/Native + Ktor (CIO) + androidx `sqlite-bundled` | Full | Small (Unverified; not measured) | Binary links the system glibc dynamically (fine for a plain binary). An image needs glibc (LGPL) or fails; Unverified whether K/N statically links GCC runtime parts (GPL-3.0 + GCC Runtime Library Exception) | `linuxX64`/`linuxArm64` are **Tier 2** targets; Ktor native: CIO only, "HTTPS without a reverse proxy is not supported"; no armv7 | Rejected for v1; possible later experiment |
| B3. Go single static binary (net/http + `modernc.org/sqlite` BSD-3, cgo-free) | None: protocol, merge rules and key normalisation duplicated in Go (mitigated by shared JSON conformance vectors) | 10.4 MB static binary; idle RSS 9 MB; 20 MB after 50k changes | **`FROM scratch` image with no GPL/LGPL** possible (Go runtime BSD-3) | Very mature for this job; goPodder (Apache-2.0) proves the shape, "~13 MB RAM at idle" | **Alternative** if the owner wants a published image without a D3 carve-out (Q2) |
| B4. Rust (axum, rusqlite/sqlx) | None | Similar to Go (Unverified) | musl static image without GPL/LGPL possible | Mature | Rejected: a third language with no advantage over Go here |
| B5. Fork goPodder (Apache-2.0, Go) | None | ~13 MB idle (its README) | As B3 | Production since mid-2025 | Rejected: gpodder-shaped data model; we would rewrite most of it anyway |
| B6. GraalVM Native Image of B1 | Full | Small, fast start (Unverified) | Output embeds SubstrateVM + JDK library (GPL-2.0 + CE) | Ktor supports it | Rejected (same licence problem as a JRE, more build complexity) |

### C. Conflict resolution

| Option | Behaviour | Problems | Verdict |
|---|---|---|---|
| Whole-record LWW | Last full record wins | Concurrent edits to different fields of one podcast or group lose data | Rejected |
| Per-field LWW by client wall clock (OPA's model) | Simple | Clock skew lets a device with a fast clock win forever; same-millisecond ties | Rejected as is |
| Server-arrival order | No client clocks needed | An offline edit made earlier overwrites a newer online one when it finally arrives | Rejected |
| **Per-field LWW by HLC with server admission bound (≤ 5 min ahead of server time) and node-ID tie-break** | Captures causality ("I changed X after seeing Y"), tolerates skew, deterministic everywhere | Needs a clock in every client and in the server's compat layer | **Recommended** |
| Union/OR (05's backup Merge) | Never loses data | Cannot express "unmark played", "unsubscribe" or "remove from group" | Used only for first-link merge of collections without timestamps |
| Op log replayed in order | Exact history | Unbounded growth, replay cost, hard compaction | Rejected (the server keeps current state plus tombstones) |

### D. Ordered lists (group order, member order, Up next)

| Option | Concurrency behaviour | Cost | Verdict |
|---|---|---|---|
| Whole-list LWW | One device's reorder wipes another's concurrent add | Trivial | Rejected |
| **Per-item fractional `orderKey` (string, base-62) + per-item LWW (membership and position)** | Concurrent adds and moves of different items merge; a concurrent move of the *same* item resolves by LWW; equal keys tie-break by record ID | One small string per item; port of `rocicorp/fractional-indexing` (CC0) | **Recommended** |
| Sequence CRDT (RGA/Logoot/Fugue) | Preserves both users' intent for interleaved inserts | Tombstone growth, complexity, no benefit for lists this short | Rejected |

### E. Authentication

| Option | UX | Security | Verdict |
|---|---|---|---|
| Username + password on every request (gpodder Basic) | Familiar | Password stored on every device; brute-force target | Only for gpodder compat, with **app passwords** |
| Full OAuth 2.0 / OIDC server (OPA's direction) | SSO possible | Large surface to build; needs a browser hop | Rejected for v1 (OIDC via a reverse proxy could come later) |
| JWT access + refresh tokens (OPA draft `/auth/login`) | Standard-ish | Revocation needs state anyway | Rejected (opaque tokens are simpler and revocable) |
| **Opaque per-device tokens + RFC 8628-style device linking + invites, optional password** | Type a short code or approve on the phone; no password on desktop | 256-bit random tokens, SHA-256 at rest, per-device revoke, rate-limited codes | **Recommended** |
| mTLS client certificates | Strong | Painful on Android and desktop | Rejected |

### F. Change notification

| Option | Fit | Verdict |
|---|---|---|
| Polling only | Works everywhere; handoff latency = poll interval | Fallback and background mode |
| **SSE `GET /api/v1/events` (notifications only; data via `/sync`)** | One-way is all we need; plain HTTP through proxies; Ktor server plugin `ktor-server-sse`; Ktor client SSE is in `ktor-client-core` with reconnection | **Recommended while foreground / playing** |
| WebSocket | Two-way not needed; more proxy configuration | Rejected |
| FCM | Needs Google Play services (N3 forbids) | Impossible |
| UnifiedPush (Android connector Apache-2.0) | Background wake-ups without Google | Optional v1.x |

### G. Storage

| Option | Verdict |
|---|---|
| **SQLite (WAL), one file, via sqlite-jdbc** | **Default and only v1 backend.** One writer is plenty for households; backups are a file copy or `VACUUM INTO` |
| PostgreSQL via pgjdbc (BSD-2) + HikariCP (Apache-2.0) | Later, behind `SyncStore`, only for real demand. SQL is written in the common subset from day one so it stays cheap |
| Room 3 on the server (`room3-runtime` publishes jvm and linux variants) | Rejected: Android-flavoured APIs, no PostgreSQL path, `sqlite-bundled` JVM natives lack armv7 |
| Exposed 1.5.0 (Apache-2.0) | Acceptable alternative to plain JDBC if PostgreSQL arrives; not needed for ~12 tables |

### H. Server distribution

| Option | GPL/LGPL in what we ship? | Admin effort | Verdict |
|---|---|---|---|
| **Fat JAR on GitHub Releases + admin-installed Java 17+** | No | `apt install openjdk-17-jre-headless` (or Temurin), then systemd | **Recommended** |
| **`Dockerfile` + `compose.yaml` in the repo, built by the admin** (base `eclipse-temurin:21-jre`, JAR fetched from the release with SHA-256 check) | No (the admin's Docker pulls the base image from its upstream) | `docker compose up -d --build` | **Recommended** |
| Published image on GHCR | **Yes** (OpenJDK GPL+CE, OS layers incl. glibc LGPL or BusyBox GPL) | `docker run` | Needs a D3 carve-out *and* a channel decision ("GitHub Releases only") — Q2, Q3 |
| jpackage/jlink bundle | Yes (OpenJDK) | Low | Same as above |
| Go static binary + `FROM scratch` image | No | Lowest | Only with B3 |

### I. End-to-end encryption

| Option | Gains | Losses | Verdict |
|---|---|---|---|
| **None in v1 (TLS only; server is self-hosted)** | Server-side merge, dedupe, gpodder layer, web UI library view, server-side backups in Neutrodyne backup format | The server operator (the user) can read listening history and private feed URLs | **Recommended for v1** |
| "Sealed mode" (v2): payloads encrypted (XChaCha20-Poly1305 or AES-GCM via Tink/BC), record IDs HMAC'd, record-level CAS, clients merge | Server learns only sizes, counts, timing, collection names | No gpodder/OPA layer, no web UI library view, no server-side dedupe beyond HMAC equality, key loss = server copy unreadable (clients can re-upload) | Design kept possible (record-level versions already exist) |
| Field-level E2EE with clear HLCs | Server could still order fields | Leaks change patterns; complex | Rejected |

---

## Technical detail

### 1. What syncs

| Data | Synced? | Notes |
|---|---|---|
| Subscriptions: `feedUrl`, `feedKey` + aliases (grow-only set), `sourceType`, `youtubeChannelId`, real `podcastGuid`, `subscribed` (tombstone), `subscribedAt` (min) | Yes | Private feed URLs with tokens included (disclosed; Q7). `title`, `artworkUrl` and `link` travel as display hints for stubs and the web UI, last writer wins; the refresh pipeline still owns the local copy (D15) |
| Podcast user fields: `customTitle`, `includeInAll`, `episodeOrder`, `youtubeVariants` | Yes | LWW per field |
| `credential` (Basic-auth passwords), Podcast Index key | **Never** | Only a `needsCredentials`/`credentialOrigin` hint, so another device prompts "Enter password" (05 After restore) |
| Groups: `name`, `colorArgb`, `iconKey`, `orderKey`, `feedOrder`, `playOrder`, `filterFlags`, `mediaFilter`, `hideOlderThanDays`, `showAsTab`, `kind`/`ruleJson` (reserved), `deleted` | Yes | `lastViewedAt` is device-local ("new since last visit" is per device) |
| Memberships: `in`, `orderKey`, `addedAt` | Yes | |
| `ScopeOverrides` (podcast and group): `playbackSpeed`, `skipSilence`, `boostDb`, `introSkipMs`, `outroSkipMs` | Yes | Listening preferences follow the user |
| `ScopeOverrides`: `autoDownload*`, `deleteAfterPlayed`, `includeVideoInAutoDownload`, `notifyNewEpisodes`, `refreshIntervalMinutes` | **Device-local by default** (Q6) | A phone and a desktop want different storage, notification and refresh policies; D45 resolution is unchanged per device |
| Episode state: played (`playedAt`), position (`positionMs`, `durationMs`, `positionSource`, `reset`), `isFavorite`, `playCount` (max), `lastPlayedAt` (max), `startedAt` (derived), `measuredDurationMs` (hint) | Yes | `downloadDismissedAt` stays device-local (downloads are device-local); `isNew` stays device-local |
| Up next: membership + `orderKey` | Yes | |
| Now playing (`play_session`: current episode + context) | Yes, as one LWW record `session/current` | Applied only on a device that is not playing ("adopt when idle", §3.6) |
| Global settings | Only keys explicitly flagged `synced` (subset of `PORTABLE`) | Default set: `playback.*` (speeds, skip intervals, smart resume, auto-mark-played threshold), feed sort/filter defaults; not `appearance.*`, `downloads.*`, `updates.*`, `youtube.engine_*`, `ui.*` (Q6) |
| Downloads, download files, artwork, feed contents, show notes, chapters, refresh validators, import history, the YouTube engine and its updates, crash data | **Never** | Every client polls feeds itself (D1 unchanged) |

### 2. Identity mapping

| Collection | Record ID | Why |
|---|---|---|
| `podcast` | `syncId`: UUIDv4, lowercase, **new column** `podcast.syncId TEXT UNIQUE NOT NULL`, generated at subscribe, import or restore (a restore adopts the backup's `syncId` when present and unused) | `feedKey` changes on moves and renormalisation (02 "Podcast feedKey and aliases"); a random ID survives them, exactly like D29's group `uuid` |
| `group` | `podcast_group.uuid` (D29) | Already stable, never reused |
| `member` | `groupUuid` + `podcastSyncId` (two fixed 36-char UUIDs, concatenated server-side) | |
| `episode` | `podcastSyncId` + `identityKey` (identityKey verbatim with its version prefix, D18) | identityKey is unique per podcast only. Because `syncId` is always 36 chars, `rid = syncId + identityKey` is unambiguous |
| `upnext` | Same as `episode` | |
| `session` | `current` (one per account) | |
| `setting` | The setting key name, e.g. `playback.skip_back_ms` | 01's key rules already forbid renames |

**Dedupe of podcasts.** The server keeps an index `podcast_key(account, feedKey) → syncId` over current keys and aliases of live (subscribed) podcasts. When a push creates podcast `B` whose `feedKey` or alias already belongs to live podcast `A`, the server:

1. keeps the older one (`subscribedAt`, then `syncId`) as survivor;
2. marks the other `mergedInto = survivor`;
3. moves its episode, Up next and member records to the survivor, merging fields by the normal rules;
4. returns `status: "merged", mergedInto` in the push result.

Clients apply a merge like 03's local merge ("Unsubscribe and merge" in 02): re-point the local row's `syncId` and merge state. Two devices that subscribe to the same feed offline therefore converge on one podcast.

**Feed moves.** A device that accepts a move (03: 301/308 chain or validated `new-feed-url`) pushes `feedUrl` (LWW) and adds the old key to `feedKeys` (union). Other devices apply it like a local move: update `feedUrl`/`feedKey`, insert the alias, and fetch the new URL on the next refresh. A collision with another podcast's key triggers the server-side merge above.

**Episode key versions (D18).**

- Every episode record carries match hints: `guid`, normalised enclosure URL, `title`, `date`, `link` and YouTube video ID. These are the same fields as 05's `EpisodeLineV1` stub fields.
- A client resolves an incoming record in this order:
  1. by `identityKey`, where it can compute the record's version;
  2. by enclosure, then by guid. This mirrors 05's "a line with `kv` newer than the app's matches by enclosure URL or guid only".
- When a client rewrites a key in place (an older-version key matched, or the host rewrote GUIDs), it pushes a **`rekey` operation** `{from, to}`. The server merges the old record into the new ID (field LWW) and tombstones the old one with `mergedInto`. Clients still holding the old key follow `mergedInto`.

**Groups with equal names.** Groups are unique by `nameKey` (NFC + lowercase, 05). Two devices that create "News" offline produce two UUIDs with one `nameKey`. The server merges live groups that share a `nameKey`, the same way a backup restore does (05 Restore step 4 "backup groups sharing a nameKey are merged into the first"):

- the older group (`createdAt`, then `uuid`) survives;
- memberships are united;
- the loser becomes `deleted` with `mergedInto`.

The server computes `nameKey` with the same `GroupNames` code, which moves to common code. NFC normalisation needs a small `expect`/`actual`, as the KMP research notes.

**Group order and membership order** use `orderKey` strings, not dense integers (§3.5).

### 3. Conflict resolution

#### 3.1 Hybrid logical clock

- **Clock (Kulkarni et al., 2014).** Each device keeps `(ms, counter)` plus a 64-bit `nodeId` (random, from the device ID). On a local event: `ms' = max(ms, wallNow + clockOffset)`, and the counter is incremented if `ms` did not change, else reset to 0. On receiving a remote HLC `r`: `ms = max(ms, r.ms, wallNow + offset)`, with the counter rules from the paper. The paper notes that an HLC "fits in 64 bits".
- **Wire form:** `HHHHHHHHHHHHCCCC-NNNNNNNNNNNNNNNN`, i.e. 12 hex digits of milliseconds, 4 hex digits of counter, a dash and a 16-hex node ID. Example: `01a10c942d800000-9f86d081884c7d65` for 2026-10-05T15:00:00Z. Byte-wise string comparison equals HLC order with node-ID tie-break, so the server's conditional upsert is a plain `WHERE excluded.hlc > current.hlc`.
- **Admission bound.** The server rejects a change whose `ms` is more than 5 min ahead of server time (`rejected: clock_skew`) or before 2020-01-01. Every response carries `serverTime`. A client whose offset exceeds 2 min sets `clockOffset = serverTime − wallNow` (persisted in `sync_state`). It also clamps a persisted local HLC that is more than 5 min ahead of the adjusted wall clock: such an HLC can never have been accepted. This bounds the damage a wrong device clock can do to 5 minutes.
- **No clocks before linking.** A device that never linked records no HLCs at all. On first link, local state is stamped from the existing row timestamps (`episode_state.updatedAt`, `episode_position.updatedAt`, `podcast.subscribedAt`, `podcast_group.updatedAt`, `podcast_group_member.addedAt`, `queue_entry.addedAt`), with counter 0 and the device's node ID.

#### 3.2 Field kinds (implemented once in `:sync:protocol`, used by client apply, server merge and the gpodder layer)

| Kind | Merge rule | Used for |
|---|---|---|
| `Lww<T>` | Keep the value with the greater HLC | Most fields |
| `LwwPosition` | `Lww`, except an incoming `{ms: 0}` without `reset: true` is ignored (N1); positions are never "max-wins" (seeking back and re-listening are legitimate) | `episode.pos` |
| `GrowSet<String>` | Union, no clock | `podcast.feedKeys` (aliases) |
| `Min<T>` / `Max<T>` | Minimum / maximum | `subscribedAt` (min); `playCount`, `lastPlayedAt` (max; `playCount` becomes approximate, accepted) |
| `Tombstone` | An `Lww<Boolean>` named `subscribed`, `deleted` or `in` | Deletions; edits to other fields never resurrect |
| `Redirect` | `mergedInto`, write-once | Merges and rekeys |

#### 3.3 Episode-state rules (derived after the field merge, on every client)

1. **Played vs position.** `played.v == true` with a played HLC newer than the position HLC → the effective position is 0 (06 mark-played semantics). A newer position after played means re-listening: 06's rule already marks the episode unplayed at the first `isPlaying`, which produces a newer `played = false`.
2. **Played vs Up next.** A played episode leaves Up next: the device that marked it played also pushes `upnext.in = false` with the same HLC. A receiver enforces 06's invariant locally even if the queue change arrives later.
3. **Played-after-start guard (06).** If a remote `played = true` arrives while this device plays that episode, the existing `pinStartedAt` guard stops further local position writes. The player is not stopped; the UI shows "Marked played on Pixel".
4. **A playing device is never seeked by sync.** Its own 5-s saves carry newer HLCs and win while it plays. When it stops, the last writer wins.
5. **Pull before play.** When the user starts an episode and the device is online, the client first runs a sync round trip with a 1.5 s budget, so it does not start from a stale local position and then overwrite the newer remote one.
6. **Position push cadence.** Positions are written locally every 5 s (D41, unchanged), but **pushed** on pause, stop, item transition and service destroy, and at most every 60 s while playing. The outbox coalesces, so no backlog builds up.

#### 3.4 Unsubscribe, delete and the mass-change guard

- **Unsubscribe** pushes `subscribed = false`. Other devices unsubscribe through `UnsubscribeUseCase`: playback pauses for it, downloads and episodes are deleted (D24). The server keeps the podcast's episode records for 180 days, so re-subscribing restores history.
- **Group delete** pushes `deleted = true`. Receivers delete the group and its notification channel. There is no cross-device undo; the local 10-s undo (R2.1) works because the push is debounced 10 s.
- **Mass-change guard.** A pulled batch that would unsubscribe more than 10 podcasts *or* more than 20 % of them, or delete more than 3 groups, is held in `sync_held`. The user sees "Pixel removed 37 podcasts and 2 groups. Apply here?" with **Apply**, **Keep mine (push them back)** and **Decide later**. "Keep mine" re-pushes the local values with fresh HLCs. Nothing is held for episode state.

#### 3.5 Ordered lists

- **`orderKey` strings.** Each list item stores an `orderKey`: a base-62 fractional-index string (digits and letters in ASCII order, so SQLite's `BINARY` collation sorts correctly). A move between neighbours `a < b` writes `between(a, b)` plus 2 random base-62 characters, so two devices inserting at the same spot do not produce equal keys. Equal keys tie-break by record ID.
- **Lists covered:** groups (one list per account), members (one list per group), Up next (one list per account).
- **Key length.** It grows slowly (about one character per repeated insert at the same spot, Unverified estimate). If any key exceeds 64 characters, the client rewrites the whole list with fresh keys: a burst of changes, but rare.
- **The algorithm** is ported from `rocicorp/fractional-indexing` (CC0-1.0, public domain), credited to David Greenspan's "Implementing Fractional Indexing" and to Figma's ordered-sequences article.
- **Local schema change (cheap now, before M1).** These columns become `orderKey TEXT`:
  - `queue_entry.ordinal REAL`; the midpoint and renormalisation rules of 02 "Up next ordering" are replaced by `OrderKey.between`;
  - `podcast_group.sortOrder Int` (dense);
  - `podcast_group_member.sortOrder Int`.

  Sorting is `ORDER BY orderKey, id`. Backups keep writing `sortOrder` as a rank, plus an optional `orderKey`.

#### 3.6 Now playing and handoff

- `session/current` holds the current episode ref, the play context and the writing device. In the play context, groups are referenced by `uuid` and podcasts by `syncId`; it covers type, order, filters, `minSortDate` and the anchor ref, as in 05's `SessionV1`.
- A device that is **not playing** adopts a newer remote session: it sets `play_session.currentEpisodeId` and the context, never starts playback (D43), and shows a "Continue on this device — *Episode* at 23:14 (from Pixel)" card. A device that is playing ignores remote sessions until it stops, then the newest one wins.
- **Why this matters for Up next.** The current item is removed from `queue_entry` (06 "Definitions"), so without a synced session the episode playing on the phone would disappear from the desktop's Up next. Syncing the session gives a continuous "phone → desktop" handoff.

### 4. Protocol sketch (Neutrodyne Sync v1)

Conventions:

- **Encoding:** JSON (UTF-8); `Content-Encoding: gzip` accepted on requests; responses gzip-compressed.
- **Errors:** `application/problem+json` per RFC 9457, with a `code`.
- **Headers:** `Authorization: Bearer <token>` and `Neutrodyne-Sync-Protocol: 1`.
- **Limits:** request ≤ 2 MiB compressed and ≤ 16 MiB decompressed (enforced by our own counter: Ktor's request decompression has no documented size limit); ≤ 1,000 changes per push; ≤ 1,000 records per page; string fields ≤ 4 KiB, URLs ≤ 4 KiB, titles ≤ 1 KiB.
- **Versioning:** protocol versions are integers. The server advertises `[min, max]`; a client below `min` gets `426` with `code: protocol_too_old`. Unknown JSON fields are ignored on both sides.

**Discovery**

```http
GET /.well-known/neutrodyne-sync
200 {"name":"Home","serverVersion":"1.1.0","protocol":{"min":1,"max":1},
     "features":["sse","link","password","gpodder"],"maxBatch":1000,"maxSkewMs":300000,
     "serverTime":"2026-10-05T15:00:00.123Z"}
```

**Authentication and devices**

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/auth/login` `{username, password, device:{id, name, platform, appVersion}}` → `{token, accountId, deviceId}` | Password login (if the account has a password) |
| `POST /api/v1/auth/invite/redeem` `{inviteCode, device:{…}}` → `{token, …}` | First device of an admin-created account (one-time code, 24 h) |
| `POST /api/v1/auth/link/start` `{device:{…}}` → `{deviceCode, userCode:"WDJB-MJHT", verificationUri:"https://sync.example.org/link", expiresIn:600, interval:5}` | RFC 8628-style start on the *new* device; `userCode` = 8 characters from RFC 8628's 20-consonant alphabet (≈ 34.6 bits) |
| `POST /api/v1/auth/link/approve` (authenticated) `{userCode}` → `{deviceName, platform}`; then `…/confirm` | Approval on a linked device or the web UI; shows what is being linked |
| `POST /api/v1/auth/link/token` `{deviceCode}` → `200 {token,…}` or `400 {"error":"authorization_pending" | "slow_down" | "expired_token" | "access_denied"}` | Polling, RFC 8628 error names |
| `POST /api/v1/auth/logout` | Revokes the calling token |
| `GET /api/v1/devices`, `PATCH /api/v1/devices/{id}` `{name}`, `DELETE /api/v1/devices/{id}` | Device list, rename, revoke |
| `POST /api/v1/app-passwords` `{label, scope:"gpodder"}` → `{username, password}` (shown once); `DELETE …/{id}` | For third-party gpodder clients (v1.x) |

**Sync (push + pull in one round trip)**

```http
POST /api/v1/sync
{
  "since": "c:4711",
  "changes": [
    {"coll":"podcast","id":"6f0c2a3e-1b6d-4c1e-9a55-0b9b8c2f1d10",
     "fields":{
       "feedUrl":   {"v":"https://feeds.example.com/show.xml","c":"01a10c942d800000-9f86d081884c7d65"},
       "feedKeys":  {"add":["https://example.com/show.xml"]},
       "subscribed":{"v":true,"c":"01a10c942d800000-9f86d081884c7d65"},
       "title":     {"v":"Example Show","c":"01a10c942d800000-9f86d081884c7d65"},
       "s.playbackSpeed":{"v":1.3,"c":"01a10c942d800001-9f86d081884c7d65"}}},
    {"coll":"episode","id":{"p":"6f0c2a3e-1b6d-4c1e-9a55-0b9b8c2f1d10","k":"g:tag:example.com,2026:ep42"},
     "match":{"guid":"tag:example.com,2026:ep42","enc":"https://cdn.example.com/ep42.mp3",
              "title":"Episode 42","date":"2026-10-01T05:00:00Z","kv":1},
     "fields":{
       "pos":   {"v":{"ms":1394000,"dur":3600000,"src":"STREAM"},"c":"01a10c944108000a-9f86d081884c7d65"},
       "played":{"v":false,"c":"01a105ed4c000000-9f86d081884c7d65"},
       "fav":   {"v":true,"c":"01a105ed4c000000-9f86d081884c7d65"}}},
    {"coll":"upnext","id":{"p":"6f0c…1d10","k":"g:tag:example.com,2026:ep43"},
     "fields":{"in":{"v":true,"c":"…"},"ok":{"v":"a0Vx3","c":"…"}}},
    {"coll":"member","id":{"g":"0d7c…","p":"6f0c…1d10"},
     "fields":{"in":{"v":true,"c":"…"},"ok":{"v":"a1","c":"…"}}},
    {"coll":"episode","op":"rekey","id":{"p":"6f0c…1d10","k":"u:https://old.example.com/ep7.mp3"},
     "to":{"p":"6f0c…1d10","k":"g:ep7-guid"}}
  ]
}
200
{
  "results":[{"i":0,"status":"applied"},{"i":1,"status":"applied"},{"i":2,"status":"stale"},
             {"i":3,"status":"applied"},{"i":4,"status":"merged","mergedInto":{"p":"6f0c…1d10","k":"g:ep7-guid"}}],
  "records":[
    {"coll":"group","id":"0d7c…","seq":4712,"by":"<deviceId>","fields":{"name":{"v":"Tech","c":"…"},"ok":{"v":"a0","c":"…"},"deleted":{"v":false,"c":"…"}}},
    {"coll":"session","id":"current","seq":4790,"by":"<deviceId>","fields":{"episode":{"v":{"p":"…","k":"…"},"c":"…"},"context":{"v":{"type":"GROUP","group":"0d7c…","order":"NEWEST_FIRST"},"c":"…"}}}
  ],
  "cursor":"c:4790","hasMore":false,"serverTime":"2026-10-05T15:00:05.412Z"
}
```

- **What a pull returns.** Pulls return the **full current record** (all fields with clocks) of each record changed since `since`, not deltas. That compacts the change log for free, and applying a record is idempotent. `by` lets a client skip its own echoes cheaply.
- **Statuses:** `applied`, `stale` (every field older than the stored one), `merged` (+ `mergedInto`), `rejected` (+ `code`: `clock_skew`, `invalid`, `quota`, `unknown_collection`). A rejection never fails the whole batch.
- **Retries:** a retried push is harmless, because LWW merge is idempotent and commutative. No batch ID is needed.
- **Pull only:** `GET /api/v1/changes?since=c:4711&limit=1000` returns the same `records`/`cursor`/`hasMore`.
- **Expired cursor.** `410 cursor_expired` comes back when `since` is older than the account's `minCursor` (tombstone purge horizon). The client then runs a full resync: pull from `c:0`, apply with LWW, then push only its outbox. It never re-pushes records the server no longer has unless they changed locally after the horizon.
- **Events:** `GET /api/v1/events` (SSE). Server events are `event: changed`, `data: {"cursor":"c:4791","by":"<deviceId>"}`. A heartbeat comment comes every 60 s (`?hb=` 30–120 s), and `retry: 10000`. Clients run `/sync` when `cursor` is ahead of theirs and `by` is not themselves.
- **Account maintenance:** `POST /api/v1/account/reset` (`{"confirm":"DELETE"}`) tombstones every record. Other devices see the deletions through the mass-change guard. `GET /api/v1/account/export` returns a Neutrodyne backup ZIP (05 format v1) of the account (v1.x).
- **Health:** `GET /healthz` (liveness), `GET /readyz` (database writable), and `GET /metrics` (Prometheus text, off by default, separate listen address).

### 5. Client side: capture, outbox, apply, scheduling

**New Room tables.** They are created in schema v1 (D22) and stay empty until linking.

```kotlin
@Entity(tableName = "sync_state")            // singleton id = 0, inserted in Callback.onCreate like play_session
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(defaultValue = "0") val enabled: Boolean = false,
    @ColumnInfo(defaultValue = "0") val applying: Boolean = false,  // set inside SyncApplier's transaction only
    val serverUrl: String? = null, val accountId: String? = null, val deviceId: String? = null,
    val credentialId: Long? = null,                                  // token in `credential` (Keystore-encrypted, origin "sync:<host>")
    val cursor: String? = null,
    @ColumnInfo(defaultValue = "0") val hlc: Long = 0,                // packed (ms << 16) | counter
    val nodeId: String? = null, @ColumnInfo(defaultValue = "0") val clockOffsetMs: Long = 0,
    val lastSyncAt: Long? = null, val lastError: String? = null, val protocol: Int? = null,
)
@Entity(tableName = "sync_outbox", primaryKeys = ["coll", "localKey", "field"])
data class SyncOutboxEntity(val coll: String, val localKey: String, val field: String, val hlc: Long)
// value is read from the source tables at push time, so repeated changes coalesce into one row
@Entity(tableName = "sync_clock", primaryKeys = ["coll", "rid"])
data class SyncClockEntity(val coll: String, val rid: String, val clocks: String /* {"pos":"…","played":"…"} */)
@Entity(tableName = "sync_parked", indices = [Index("podcastSyncId"), Index("guid"), Index("enclosureKey")])
data class SyncParkedEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val podcastSyncId: String,
    val identityKey: String, val guid: String?, val enclosureKey: String?, val record: String, val receivedAt: Long)
@Entity(tableName = "sync_held")             // mass-change guard
data class SyncHeldEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val batch: String, val summary: String, val heldAt: Long)
```

**Change capture with triggers.**

- **Mechanism.** One `AFTER INSERT/UPDATE/DELETE` trigger per synced table, scoped to the synced columns with `UPDATE OF`. Each trigger is guarded by `WHEN (SELECT enabled AND NOT applying FROM sync_state WHERE id = 0)`. It advances `sync_state.hlc` (`max(hlc + 1, (now_ms + clockOffsetMs) << 16)`, with `now_ms` from `julianday('now')`) and `INSERT OR REPLACE`s the outbox row.
- **Tables covered:** `podcast`, `podcast_settings`, `podcast_group`, `podcast_group_settings`, `podcast_group_member`, `episode_state`, `episode_position`, `queue_entry`, `play_session`.
- **SQL dialect.** D9 forbids SQL `UPSERT`, so the trigger SQL stays inside the SQLite 3.18 baseline (`INSERT OR REPLACE`).
- **Why triggers.** They cannot be forgotten by a new write path: bulk "Mark all played" over 5,000 IDs, the restore transactions, import commit and 06's position saves are all captured. Local-only users pay one indexed singleton read per write. Room does not model triggers, so they are created as raw SQL in `Callback.onCreate` and in migrations, which 02's rules already allow.
- **Settings and the fallback.** DataStore settings are captured explicitly in `SettingsRepository.set` for keys flagged `synced`. The fallback, if spike S14 rejects triggers, is an explicit `SyncRecorder.record(...)` inside the existing transaction helpers (`PositionWriter`, the mark-played chain, `GroupRepository`, `QueueDao` operations), plus an architecture test that every synced-column writer goes through them.

**Local deletions that must not propagate.**

- Retention deletes (D23) and the stub cleanup run with `applying = 1` set by their own transaction, a "do not capture" flag. They are storage housekeeping, not user intent.
- A local unsubscribe is captured as `subscribed = false` on the podcast record. Its cascade-deleted episode rows produce no episode deletes: the trigger ignores `DELETE` on `episode_state` and `episode_position`.

**Apply (`SyncApplier`, one write transaction per pulled page of ≤ 1,000 records).**

1. Set `applying = 1`.
2. For each record, read the clocks from `sync_clock`, merge the fields with `:sync:protocol`'s rules, and write only the fields whose clock advanced. Write through the same DAO statements 02 defines (column-scoped), with the guards (`updateGuarded`, the played chain).
3. Resolve episodes with the §2 ladder:
   - match found → apply;
   - no match, but the record is queued, in progress or favourite → insert a stub (05 "Stubs and matching" SQL: `inFeed = 0`, `isNew = 0`);
   - otherwise → park it in `sync_parked`.

   03's ingestion calls `SyncParkedStateApplier.apply(podcastId)` after inserting new episodes; it reuses 02's "Restore matching" ladder. Parked rows are deleted with their podcast.
4. New podcasts are inserted as `PENDING_FIRST_FETCH` with `initialFetch = 1` (no notifications, no auto-download storm, D66/D67), exactly like restore step 4. `import-sync` work then fetches them.
5. Set `applying = 0` and advance `cursor`, in the same transaction, so a crash re-applies the page idempotently.

**Push.** Read up to 1,000 outbox rows ordered by `hlc` and build changes from the current source values plus the clocks. On `applied`/`stale`/`merged`, delete the outbox rows whose `hlc` is ≤ the pushed one (a newer change made meanwhile stays). On `rejected: clock_skew`, re-stamp after the offset correction.

**Scheduling, Android (N2-compliant, no exact alarms, no FGS of its own).**

| Trigger | Mechanism |
|---|---|
| App comes to the foreground; Settings › Sync "Sync now" | `sync-now` one-time work (expedited on API 31+, as `refresh-now` in D25) |
| Outbox becomes non-empty | `sync-push` one-time work, `KEEP`, 10 s initial delay (debounce; also covers the 10-s group-delete undo), network constraint |
| Playback pause, stop, item transition | Push from the playback service's scope while it is still foreground; fall back to `sync-push` |
| Background | `sync-periodic` unique periodic work, 60 min default (WorkManager's minimum is 15 min), network constraint, 8-min soft deadline like other workers |
| Live updates | SSE connection only while the UI is visible or the playback FGS runs; heartbeat 90 s on mobile |

**Scheduling, desktop.** The KMP research's `DesktopJobRunner` gets a sync lane: a push 2 s after the last change, SSE while running, and a pull on SSE `changed` and every 15 min as a safety net.

**UI (Settings › Sync).**

- **Setup:** server address with discovery check; "Sign in with password" or "Link with a code"; "Link another device" (enter the code a new device shows).
- **Status:** connected as …, last sync, pending changes, and the device list with rename and revoke.
- **Settings:** what syncs (read-only list, plus "Sync playback settings" on/off); "Unlink this device"; "Delete my data on the server".
- **Disclosure:** "Your sync server stores your subscriptions (including private feed links) and listening history".

### 6. Linking and the first merge

| Situation | Behaviour |
|---|---|
| Account empty (first device) | Upload everything in pages of 1,000 changes, stamped from row timestamps; no prompt |
| Device library empty (new phone, new desktop) | Pull from `c:0`; podcasts appear at once as pending (R1.3-like), stubs and parked state as above |
| Both non-empty | Prompt: **Merge** (default), **Use the server's library on this device** (local Replace, 05 Replace rules), **Use this device's library everywhere** (account reset + upload; second confirmation naming the effect on other devices) |

**Merge** reuses 05's Merge table with one improvement: for state with timestamps, the row's own timestamp decides instead of OR. For example, a local `playedAt` from June loses to a server "unplayed" from October. In detail:

- **Unioned:** subscriptions, aliases, groups (matched `uuid` → `nameKey`), memberships.
- **LWW by row timestamps:** played, position (zero guard), favourite. `playedAt`, `playCount`, `startedAt` and `measuredDurationMs` follow 05's rules.
- **Up next:** local first, then server entries not yet queued.
- **Settings:** the server's values win when the account already has them; otherwise this device uploads its own.

The merge is implemented by mapping server records into 05's restore inputs (`PodcastV1`, `GroupV1`, `EpisodeLineV1`, `QueueV1`), so one merge routine (`RestoreMerger`) serves backups and sync. Its rules table in 05 remains the single source.

**Contrast with AntennaPod.** AntennaPod's first sync uploads every played episode as a PLAY action stamped `currentTimestamp()` (source below). On another device that marked the episode unplayed later, this flips it back to played. Using row timestamps avoids that.

### 7. Interaction with backup, Auto Backup, OPML, retention and YouTube

| Feature | Rule while sync is linked |
|---|---|
| Manual backup | Unchanged; adds optional `syncId` (podcasts) and `orderKey` (groups, members, Up next) fields (no `formatVersion` bump: optional fields with defaults, 05 Versioning) |
| Restore, **Merge** | Restored values are captured with the backup's timestamps (`ts`, `posAt`, `playedAt`) as HLC time, not "now", so they win only where actually newer |
| Restore, **Replace** | Asks: "Replace on all synced devices" (account reset + upload) or "Unlink and replace only this device" (default) |
| Auto Backup / first-launch restore (D34, D70) | The token (in `credential`) and `sync_state` are not backed up. `sync.server_url` and `sync.username` become `PORTABLE` settings, so a restored install shows "Reconnect to sync.example.org". A reinstall gets a new device ID; the old device entry is pruned after 180 days unseen or revoked by the user |
| OPML / NewPipe / LibreTube / Takeout import | Captured like any local change (podcasts, groups, memberships); one import of 300 feeds = one or two pushes |
| Episode retention (D23) and stub cleanup | Never propagate. Server-side GC (§9.4) keeps what matters |
| Unsubscribe (D24) | Propagates (with the mass-change guard); history kept on the server 180 days |
| Feed moves and merges (03) | §2; merges are server-arbitrated |
| YouTube channels | Same records (`sourceType = YOUTUBE_CHANNEL`, `youtubeChannelId`); YouTube Up next refs on an external-mode device are kept and greyed (06 YouTube branch) |
| Desktop (no Auto Backup) | Sync plus manual backup are the multi-device paths |

### 8. Authentication and authorisation (server)

- **Roles:**
  - `admin` manages accounts, invites, server settings and backups;
  - `user` owns exactly one library.
  - No cross-account sharing. The first admin is created by `neutrodyne-sync admin create` or a one-time setup token printed at first start (Unverified UX choice; goPodder uses a first-launch web form instead).
- **Tokens:**
  - 32 random bytes from `SecureRandom`, base64url, prefix `nds_`, so secret scanners can match it (GitHub-style).
  - Stored as SHA-256 (high entropy needs no slow hash) and compared in constant time.
  - Kinds: `device` (all sync scopes), `app_password` (gpodder scope only), `web_session` (cookie, 30-day sliding, `HttpOnly; Secure; SameSite=Strict`).
  - `lastUsedAt` is updated at most once per hour.
- **Passwords (optional):** Argon2id via Bouncy Castle `Argon2BytesGenerator`, OWASP minimum `m = 19 MiB, t = 2, p = 1`, raised to `m = 46 MiB` where RAM allows (Unverified tuning on a Pi). Rate limits use Ktor's RateLimit plugin per IP and per username (e.g. 10 attempts per 15 min), with exponential back-off.
- **Link codes:** valid 10 min; at most 5 approval attempts per code; the polling `interval` is enforced (`slow_down`). Approval requires an authenticated session and shows the device name and platform first, against "consent phishing" with a stolen code.
- **Invites:** a one-time code (or link) per account, valid 24 h.
- **Authorisation:** every query is scoped by `accountId` from the token. Record IDs are never global, so there are no cross-account IDs.

### 9. Server implementation

#### 9.1 Modules (same Gradle build)

| Module | Kind | Content |
|---|---|---|
| `:sync:protocol` | KMP common-only, depends on nothing project-internal | DTOs (kotlinx.serialization), `Hlc`, `OrderKey`, field kinds and merge rules, collection schemas, error codes, protocol version, conformance-vector runner |
| `:feeds` (common part, per the KMP research) | KMP common | `UrlNormalizer`, `EpisodeKeys`, backup models (`LibraryV1`, `EpisodeLineV1`, …); the server uses them for gpodder URL→`feedKey`, for match hints and for backup export |
| `:sync:api` | KMP common-only | `SyncController`, `SyncState`, `LinkFlow` interfaces for features (pattern of `:*:api`) |
| `:sync:impl` | KMP (common + `androidMain` + `desktopMain`) | Engine (outbox, push, pull, apply, first-link merge), Ktor client calls, SSE, scheduling glue (WorkManager workers in `androidMain`, `DesktopJobRunner` lane in `desktopMain`); uses `:core:database` DAOs and `:core:network` |
| `:sync:server` | Kotlin/JVM application | Ktor server (CIO engine), routes, auth, `SyncStore` (JDBC/SQLite), gpodder layer (v1.x), web UI (kotlinx.html), CLI, migrations, backup job |
| `:sync:server-test` (or test source set) | JVM | Black-box tests against a temporary server; multi-device simulation |

Module rules: features never depend on `:sync:impl`; `:sync:server` depends only on `:sync:protocol` and `:feeds` common, never on Android or app modules. A module-graph assertion enforces both. Licensee also runs on `:sync:server`'s runtime classpath (D60, N8).

#### 9.2 Dependencies and licences (server runtime classpath)

| Component | Version (2026-10-05) | Licence |
|---|---|---|
| Ktor server core, CIO, content-negotiation, SSE, auth, rate-limit, CSRF, compression, forwarded-header, call-id, html-builder | 3.6.0 (2026-09-16) | Apache-2.0 |
| kotlinx-serialization-json | 1.11.0 stable (1.12.0-RC exists) | Apache-2.0 |
| kotlinx-coroutines | 1.11.0 (pulled by Ktor) | Apache-2.0 |
| kotlinx-html | 0.12.0 | Apache-2.0 |
| sqlite-jdbc (xerial) | 3.53.4.0 (natives: Linux x86/x86_64/aarch64/armv6/armv7/riscv64/ppc64 + musl, FreeBSD, macOS, Windows) | Apache-2.0 (SQLite public domain) |
| Bouncy Castle `bcprov-jdk18on` (Argon2) | 1.86 | MIT |
| slf4j-api + slf4j-simple | 2.0.x (2.0.19 api via Ktor; 2.0.20 latest stable per Maven metadata) | MIT |
| Transitives seen in the prototype: Typesafe `config`, `kaml`, `snakeyaml-engine-kmp`, okio, kotlinx-io, kotlinx-datetime, `urlencoder-lib` | — | Unverified per artefact: Licensee decides |
| *Avoided:* logback (EPL-2.0 / LGPL-2.1), argon2-jvm 2.12 (LGPL-3.0), MariaDB Connector/J (LGPL-2.1-or-later), Flyway (not needed: a 40-line migrator over numbered SQL files and a `schema_version` table), H2 (MPL/EPL) | — | — |
| *Later, if PostgreSQL:* pgjdbc 42.7.13, HikariCP 7.1.0 | — | BSD-2-Clause, Apache-2.0 |

The Ktor project generator defaults to logback. The build must declare `slf4j-simple` (or an own SLF4J provider writing JSON lines) and ban `ch.qos.logback` in `verifyDependencyPolicy`.

#### 9.3 Storage schema (SQLite; SQL kept in the SQLite ≥ 3.35 / PostgreSQL ≥ 13 subset)

```sql
CREATE TABLE schema_version (version INTEGER NOT NULL);

CREATE TABLE account (
  id INTEGER PRIMARY KEY,                      -- BIGINT GENERATED … on PostgreSQL
  username TEXT NOT NULL UNIQUE,               -- NFC + lowercase key; display name separate
  display_name TEXT,
  role TEXT NOT NULL CHECK (role IN ('admin','user')),
  password_hash TEXT,                          -- Argon2id PHC string, NULL = passwordless
  seq INTEGER NOT NULL DEFAULT 0,              -- per-account change counter (cursor source)
  min_cursor INTEGER NOT NULL DEFAULT 0,       -- tombstone purge horizon
  created_at INTEGER NOT NULL, disabled_at INTEGER,
  quota_records INTEGER NOT NULL DEFAULT 500000
);

CREATE TABLE device (
  id TEXT PRIMARY KEY,                         -- client-generated UUIDv4 (OPA "Client-ID" analogue)
  account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  name TEXT NOT NULL, platform TEXT NOT NULL,  -- android | windows | macos | linux | gpodder
  app_version TEXT, node_id TEXT NOT NULL,     -- HLC node of this device
  created_at INTEGER NOT NULL, last_seen_at INTEGER, last_cursor INTEGER
);

CREATE TABLE token (
  hash BLOB PRIMARY KEY,                       -- SHA-256 of the secret
  account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  device_id TEXT REFERENCES device(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('device','app_password','web_session','invite')),
  label TEXT, created_at INTEGER NOT NULL, last_used_at INTEGER, expires_at INTEGER, revoked_at INTEGER
);

CREATE TABLE link_request (
  device_code_hash BLOB PRIMARY KEY, user_code TEXT NOT NULL UNIQUE,
  device_json TEXT NOT NULL, account_id INTEGER, approved_at INTEGER, denied_at INTEGER,
  created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, last_poll_at INTEGER, attempts INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE record (
  account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  coll TEXT NOT NULL,                          -- podcast | group | member | episode | upnext | session | setting
  rid TEXT NOT NULL,                           -- see §2
  seq INTEGER NOT NULL,                        -- account.seq at last change
  data TEXT NOT NULL,                          -- JSON {field: {v, c}} (+ "match" for episodes)
  max_hlc TEXT NOT NULL,
  dead INTEGER NOT NULL DEFAULT 0,             -- tombstoned (subscribed=false / deleted / in=false / mergedInto)
  merged_into TEXT,
  by_device TEXT, updated_at INTEGER NOT NULL,
  PRIMARY KEY (account_id, coll, rid)
);
CREATE INDEX record_seq ON record (account_id, seq);
CREATE INDEX record_dead ON record (account_id, dead, updated_at);

CREATE TABLE podcast_key (                     -- dedupe index over live podcasts' feedKey + aliases
  account_id INTEGER NOT NULL, feed_key TEXT NOT NULL, podcast_rid TEXT NOT NULL,
  PRIMARY KEY (account_id, feed_key)
);
CREATE TABLE group_name (                      -- live groups' nameKey for merge-on-collision
  account_id INTEGER NOT NULL, name_key TEXT NOT NULL, group_rid TEXT NOT NULL,
  PRIMARY KEY (account_id, name_key)
);
CREATE TABLE audit (                           -- security events only: logins, links, revocations, resets
  id INTEGER PRIMARY KEY, account_id INTEGER, at INTEGER NOT NULL, event TEXT NOT NULL, detail TEXT
);
```

**Write path.**

1. One transaction per push request.
2. `UPDATE account SET seq = seq + 1 … RETURNING seq` for each changed record. That is a single writer on SQLite, and a row lock on PostgreSQL. It removes the classic "sequence values commit out of order, a reader skips one" problem.
3. Then `SELECT … record` → `RecordMerger.merge` (`:sync:protocol`) → `INSERT … ON CONFLICT (account_id, coll, rid) DO UPDATE`.
4. Maintain `podcast_key`/`group_name` and run merges in the same transaction.
5. SQLite: `journal_mode = WAL`, `synchronous = NORMAL`, `busy_timeout = 5000`, one write connection behind a mutex, and a small pool of read connections.

#### 9.4 Retention and garbage collection (daily job)

| Records | Kept |
|---|---|
| Tombstoned `group`, `member`, `upnext`, `podcast` | 365 days after death, then purged; `min_cursor` advances to the lowest purged `seq` |
| `episode` records with no information (unplayed, position 0, not favourite) | Purged after 30 days |
| `episode` records of podcasts unsubscribed > 180 days | Purged |
| Played/favourite/in-progress `episode` records of live podcasts | Forever (≈ 200–300 B each; 50,000 ≈ 12–15 MB per heavy user, Unverified estimate) |
| Devices unseen 180 days | Flagged stale in the UI; tokens kept until revoked (owner may prefer auto-revoke) |
| `link_request` | Deleted 1 h after expiry |

#### 9.5 Performance (prototype)

The prototype was built in this session: a Ktor 3.6.0 CIO server with a single-field-register schema, `ON CONFLICT … DO UPDATE … WHERE excluded.hlc > reg.hlc`, and JDBC batches of 500. It pushed 50,000 changes in 1.7–2.0 s and pulled them in 0.5–0.7 s; the Go equivalent took 1.4 s and 0.35 s. The real design merges JSON documents in Kotlin, which is slower per change (Unverified), but a household server sees a few hundred changes per day plus one-off initial uploads of ~50k records. Throughput is not a concern. Memory and start-up on a Pi are measured in spike S16.

### 10. Deployment

**Artefacts on each GitHub release** (immutable, D79): `neutrodyne-sync-server-{v}.jar` (fat JAR, `Main-Class`), with its SHA-256 in `SHA256SUMS` and a provenance attestation; `neutrodyne-update.json` gains a `server` entry. Repository folder `server/deploy/` contains:

- `neutrodyne-sync.service` (systemd):

```ini
[Unit]
Description=Neutrodyne Sync server
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/java -Xmx96m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k \
  -jar /opt/neutrodyne-sync/neutrodyne-sync-server.jar serve
Environment=NEUTRODYNE_SYNC_DATA=/var/lib/neutrodyne-sync
Environment=NEUTRODYNE_SYNC_LISTEN=127.0.0.1:8787
Environment=NEUTRODYNE_SYNC_PUBLIC_URL=https://sync.example.org
DynamicUser=yes
StateDirectory=neutrodyne-sync
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes
ProtectKernelTunables=yes
ProtectControlGroups=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
MemoryMax=320M
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

- `Dockerfile` (built by the admin, nothing published):

```dockerfile
FROM eclipse-temurin:21-jre
ARG VERSION
ARG SHA256
ADD --checksum=sha256:${SHA256} \
    https://github.com/<owner>/Neutrodyne/releases/download/v${VERSION}/neutrodyne-sync-server-${VERSION}.jar /app/server.jar
USER 10001:10001
ENV NEUTRODYNE_SYNC_DATA=/data NEUTRODYNE_SYNC_LISTEN=0.0.0.0:8787
VOLUME /data
EXPOSE 8787
HEALTHCHECK CMD ["java","-cp","/app/server.jar","ch.lkmc.neutrodyne.sync.server.HealthCheck"]
ENTRYPOINT ["java","-Xmx96m","-XX:+UseSerialGC","-jar","/app/server.jar","serve"]
```

(`ADD --checksum` needs BuildKit, Unverified minimum version; fallback: `curl` + `sha256sum -c` in a build stage.)

- `compose.yaml`: `neutrodyne-sync` (build context `https://github.com/<owner>/Neutrodyne.git#v1.1.0:server/deploy`, which Compose supports: "A Git repository URL … the first part represents the reference … the second part represents a subdirectory"), plus an optional `caddy` service.
- `Caddyfile`: `sync.example.org { reverse_proxy neutrodyne-sync:8787 }`. Caddy (Apache-2.0) obtains certificates automatically. nginx snippet: `proxy_buffering off; proxy_read_timeout 1h;` on `/api/v1/events`, plus `X-Forwarded-*`.
- **Configuration:** environment variables `NEUTRODYNE_SYNC_*`, or `--config server.properties`. The server trusts `X-Forwarded-For`/`-Proto` only from `NEUTRODYNE_SYNC_TRUSTED_PROXIES` (Ktor `forwarded-header` plugin).
- **TLS:** none built in for v1. The server refuses to start on a non-loopback address without `NEUTRODYNE_SYNC_PUBLIC_URL=https://…` unless `--insecure-lan` is given, and the web UI shows a red "not HTTPS" banner.
- **Raspberry Pi:** Pi 3/4/5 and Zero 2 W. The admin installs Java 17+ (Debian 12 ships OpenJDK 17, newer distributions 21; Temurin has Linux ARM32 and aarch64 builds). The default launcher flags above target ≤ 160 MB RSS (Unverified on Pi; spike S16).
- **Backups:**
  - **Server-level:** a nightly `VACUUM INTO 'backups/neutrodyne-sync-YYYY-MM-DD.db'` (SQLite: "an alternative to the backup API for generating backup copies of a live database"), 7 kept, plus `neutrodyne-sync backup <file>` / `restore <file>` CLI.
  - **Per-account:** a nightly Neutrodyne backup ZIP (05 format) per user, 14 kept, downloadable in the web UI. A user can restore it with the app's normal restore even if the server dies.
- **Upgrades:** the JAR runs pending migrations at start in one transaction after taking an automatic pre-migration backup. A downgrade refuses to start on a newer `schema_version`.
- **Update notice:** the admin web UI shows "Version X available" from the same `neutrodyne-update.json` (daily, notify-only like D78; switch `NEUTRODYNE_SYNC_UPDATE_CHECK=false`). It is the only outbound request the server ever makes.

### 11. Web UI and CLI

- **Web UI:** server-rendered HTML (Ktor html-builder + kotlinx.html), no JavaScript build, a strict CSP (`default-src 'self'`) and CSRF tokens (Ktor CSRF plugin). English only in v1.
  - **Every user:** sign-in (password or link code from the app), "Approve a device" (enter code), Devices (rename, revoke, last seen), App passwords (v1.x), My library (read-only counts, subscriptions, groups, last synced), Download my data (backup ZIP), Delete my data.
  - **Admin:** Accounts (create, disable, invite link, reset link), Server (version, DB size, last backup, record counts, update notice), Settings (registration off, retention days, trusted proxies).
- **CLI** (`java -jar … <cmd>`): `serve`, `admin create`, `user add|invite|disable|list`, `backup`, `restore`, `migrate --check`, `doctor` (config, DB integrity, clock vs NTP hint).

### 12. Observability

- **Logs:** one line per request to stdout (JSON-lines option) with request ID (Ktor CallId), account ID, route, status, duration and change counts. Never logged: tokens, passwords, feed URLs or record payloads (feed URLs can contain secrets, like N3's redaction rule). Level via `NEUTRODYNE_SYNC_LOG_LEVEL`.
- **Health:** `/healthz` and `/readyz` for Docker/systemd.
- **Metrics:** an optional `/metrics` in Prometheus text format, a handful of hand-written counters and gauges (requests by route and status, push changes applied/stale/rejected, SSE connections, DB size, last backup age), so Micrometer is not needed.
- **Audit:** the `audit` table for security events, visible to admins.

### 13. Security hardening checklist

1. HTTPS via reverse proxy; HSTS from the proxy; `Secure` cookies; refusal to run plain HTTP publicly without an explicit flag.
2. Opaque tokens, hashed; per-device revoke; app passwords scoped to gpodder routes only.
3. Rate limits on login, link approval and polling; generic error messages; per-account and per-IP limits on `/sync` (e.g. 60/min) and SSE connections (≤ 10 per account).
4. Input caps (§4), JSON depth ≤ 16, strict schemas; `rid` and URL length caps; decompression bomb guard; quotas per account.
5. No outbound requests (no SSRF surface); no feed fetching; no template injection (kotlinx.html escapes); CSP, `X-Content-Type-Options`, `Referrer-Policy: no-referrer`, `frame-ancestors 'none'`.
6. Process isolation via systemd sandboxing or a non-root container user; data directory `0700`.
7. Clients treat server data as untrusted (N9): size caps per record and page, URL scheme allow-list (`http`/`https` feed URLs only, as 03), group names through `GroupNames`, settings through key validators, unknown collections ignored.
8. **Android:** the token is Keystore-encrypted in `credential` (03). The published APK is debuggable (risk P11), so a debugger could use the token; the device-revoke button is the remedy. **Android 17 LAN rule:** an app targeting API 37 cannot reach a local-network address without `ACCESS_LOCAL_NETWORK`, and the rule follows the destination IP, so a public hostname that resolves to a LAN IP inside the home is affected too. The plan's `LocalNetworkGuard` would fail such a server fast (01 P15). Recommendation: request `ACCESS_LOCAL_NETWORK` contextually in the sync setup only when the server resolves to a local address (Q10; amends D28/P15). Unverified whether Tailscale's 100.64.0.0/10 addresses count as "local network".
9. Dependency licences and CVEs: Licensee on the server classpath, Renovate, SBOM in releases (CycloneDX, Unverified tooling choice).

### 14. End-to-end encryption (design notes for a v2 "sealed mode")

- **Keys:** an account key (32 bytes) created by the first device and moved to new devices *inside* the link flow, encrypted to an ephemeral key exchanged through the code. A QR code is the other option. The key must never go through the server in the clear. Optional recovery passphrase via Argon2id.
- **Record format:** `rid' = HMAC-SHA256(kIds, coll || rid)`; payload = AEAD(kData, record JSON) with associated data `coll || rid'`. The server stores `(coll, rid', seq, version, dead, ciphertext)`.
- **Merging:** clients merge. A push carries `ifVersion`, and the server returns `409` with the current ciphertext on mismatch: Firefox Sync's `X-If-Unmodified-Since`/`412` pattern.
- **What is lost:**
  - the gpodder and OPA layers;
  - the web UI's library view;
  - server-side merges for podcasts and groups (clients handle them; HMAC equality on `feedKey` still lets the server flag duplicates);
  - the server-side backup ZIP.
- **Libraries:** Tink (Apache-2.0; already in the plan below API 33 for D76) on Android and JVM; Bouncy Castle (MIT) as an alternative. No libsodium binding is needed.

### 15. Compatibility layers

**gpodder API v2 subset (SY4, v1.x).** Route set: `/api/2/auth/{user}/login.json` and `logout.json`; `/api/2/devices/{user}.json` and `/api/2/devices/{user}/{device}.json`; `/api/2/subscriptions/{user}/{device}.json` (GET `since`, POST add/remove); `/subscriptions/{user}/{device}.{opml|txt|json}` (simple API); `/api/2/episodes/{user}.json` (GET `since`, `aggregated`; POST); `/api/2/sync-devices/{user}.json`. Optionally the Nextcloud gPodder Sync routes (`/index.php/apps/gpoddersync/subscriptions`, `…/subscription_change/create`, `…/episode_action`, `…/episode_action/create`).

| gpodder concept | Mapping |
|---|---|
| HTTP Basic + `sessionid` cookie | App password (scope `gpodder`); every request may carry Basic; a dummy session cookie is issued for clients that expect one |
| Device (`deviceid`) | A `device` row with `platform = gpodder`; HLC node = hash of `(account, deviceid)` |
| Device-scoped subscriptions | Ignored: all devices of an account share one subscription set; `sync-devices` reports every device as synchronised; POST is a no-op |
| `add` URL | `UrlNormalizer.forIdentity` → `feedKey` → existing podcast (resubscribe) or new `podcast` record (`title` = host, `RSS`); Neutrodyne clients fetch it |
| `remove` URL | `subscribed = false` on the matched podcast |
| `update_urls` | `[sent, current feedUrl]` when the URL was an alias or got sanitised |
| Episode action `play` (`started`, `position`, `total`, `timestamp`) | `pos = position·1000` (ignored if 0), HLC from `timestamp` (seconds; UTC assumed), node from device; `position ≥ total − max(30 s, 3 %)` with `total > 0` → also `played = true`. That is AntennaPod's convention (it uploads played as `position = total = duration`) and Kasts' (it infers played from position vs total) |
| `new` | `played = false` + position reset (Unverified that every client sends `new` for "mark unplayed"; Kasts says it is broken on gpodder.net) |
| `download`, `delete` | Ignored (as Nextcloud gPodder Sync ≥ 3.13.3 and Kasts do) |
| Episode identity (`podcast` URL, `episode` media URL, optional `guid`) | Podcast via `feedKey`/aliases; episode via `g:{guid}` when `guid` is present, else `u:{normalised media URL}` (the D18 v1 ladder's first two rungs); other key kinds are emitted with the record's enclosure hint |
| GET actions `since` | Unix seconds of the record changes, compared inclusively (duplicates are idempotent); the response `timestamp` is the max seconds returned. Unverified that every client treats it opaquely; seconds are the safe choice because Nextcloud's variant documents Unix time |
| Our records → actions | `play` with `position` = seconds, `total` = duration if known; played → `position = total`; played with unknown duration → skipped (AntennaPod requires `total > 0`, Unverified) |
| Not representable | Groups, Up next, favourites, settings, sessions |

Interop targets for tests: AntennaPod (gpodder.net option with a custom server, and the Nextcloud option, which needs Nextcloud Login Flow v2 — Unverified effort) and Kasts (providers `GPodderNet`, `GPodderNextcloud`).

**Open Podcast API adapter (later).** OPA's model maps closely:

| OPA | Ours |
|---|---|
| `Client-ID` | Device ID |
| `subscription.guid` | Real `podcastGuid`, or UUIDv5 derived from the feed URL per the podcast:guid spec |
| `sync_id` | Episode `rid` |
| Per-field `timestamp` | HLC milliseconds |
| `POST /api/v1/sync` with `action`/`type` | Our changes |
| `GET /api/v1/sync?since=` (RFC 3339) | A time-indexed view over `record.updated_at` |

Build the adapter when OPA publishes a tagged version with the sync and episodes endpoints merged. Until then, contribute our requirements to OPA's discussions: per-field clocks with skew handling, versioned episode keys, groups.

### 16. Testing

- **Conformance vectors** (`sync/protocol/vectors/*.json`): input records plus change sequences, and the expected merged state. They are run by `:sync:protocol` tests on the JVM and by the server, and would also be run by a Go server if Q2 picks B3.
- **Property-based convergence test:** N simulated devices with random offline edits, random delivery order, duplicates and clock skew within the admission bound. All replicas plus the server must reach identical state; the N1 invariants must hold (no 0-over-non-zero position, played ⇒ not in Up next).
- **Client DB tests:**
  - trigger capture on every synced column, with bulk paths;
  - no capture while `applying` or without `enabled`;
  - apply idempotence;
  - parked-state application on ingest;
  - first-link merge against 05's rules table;
  - migration tests for the new columns (D22 discipline).
- **Server black-box tests:** an embedded server on a temp DB via Ktor's test host; auth flows (link, invite, revoke); 410 resync; quotas; GC; merges; SSE events.
- **Interop (SY4):** recorded AntennaPod and Kasts request fixtures, plus a manual run against real apps.
- **Footprint test:** S16 on a Pi 4 (arm64) and a Pi 3/Zero 2 W (armv7), with idle and initial-upload RSS recorded in the release issue.

### 17. Licensing analysis

- **What we ship for the server:** our code (Unlicense) plus the permissive classpath of §9.2. That satisfies D3 and N8 as written.
- **What D3 blocks:** publishing a container image or a jpackage bundle. Both would ship OpenJDK (GPL-2.0 + Classpath Exception, per Adoptium) and, for images, OS layers with LGPL (glibc) or GPL (BusyBox) code.
- **What the KMP research found for desktop:** the same JRE issue (its Q1). One owner decision should cover both: either "no runtime/OS layers shipped" (BYO-JRE, user-built images) or a narrow carve-out such as "unmodified OpenJDK runtimes and unmodified distribution base layers, with source offers".
- **LGPL:** allowing it would let us use logback, argon2-jvm and MariaDB Connector/J. None is needed, so **there is no material simplification**. LGPL also would not legalise a JVM image, because OpenJDK is GPL+CE, not LGPL.
- **AGPL/GPL sync servers and clients:** mygpo, Nextcloud gPodder Sync, oPodSync, gpodder2go and PinePods are AGPL/GPL servers; podsync is GPL-3.0-or-later; AntennaPod is GPL-3.0; Kasts is GPL-2.0-only/GPL-3.0-only. We implement the documented gpodder HTTP API ourselves and copy no code, as goPodder (Apache-2.0) and Podhound (MIT) do. Unverified as legal advice: reimplementing a documented network API is generally not treated as creating a derivative work of an implementation; the owner may want a lawyer's view if it matters.
- **OPA text:** CC BY-SA 4.0. Linking and implementing is fine. Quoting large parts into Unlicense docs would bring share-alike obligations, so we don't.
- **Fractional indexing port:** CC0-1.0, compatible.

---

## Impact on the existing plan

### Vision, scope, non-goals

- §1 vision "server-less … no Neutrodyne backend, no account" → "No Neutrodyne-operated backend. An **optional, self-hosted** Neutrodyne Sync server syncs a user's library between their devices; feeds are still polled by every device."
- §1.2 non-goal "Neutrodyne server, accounts, cross-device sync (gpodder, Nextcloud) — sync is later" → replaced: "v1.0: no sync (groundwork only); v1.1: Neutrodyne Sync server + Android and desktop clients; v1.x: gpodder compatibility; no hosted service run by the project."
- Principle 3 ("talks only to hosts the user chose"): the sync server is one of them (PRIVACY.md, N3).

### Requirements

New **R7 — Sync (optional)**:

| ID | Statement (proposed) |
|---|---|
| R7.1 | With no sync server configured, the app sends no sync request, writes no sync data and every feature works. |
| R7.2 | A user can connect a device to a self-hosted Neutrodyne Sync server by address plus password, an invite code, or a link code approved on an already-linked device or the server's web page. |
| R7.3 | Subscriptions (including moves and unsubscribes), groups, memberships and their order, podcast user fields and playback overrides, played state, positions, favourites, Up next and its order, the now-playing episode and whitelisted global settings sync. Audio files, downloads, feed contents, credentials and device settings never sync. |
| R7.4 | Changes made offline are kept and sent when online. With both apps in the foreground, a change appears on the other device within 10 s; otherwise it appears at the next sync (app start, playback stop, or ≤ 60 min in the background). |
| R7.5 | Conflicts resolve per field by last writer (HLC). A position of 0 never replaces a non-zero position except by an explicit reset or mark-played; sync never seeks a playing player. |
| R7.6 | Linking a device with an existing library offers Merge (default), Use server library, Use this device's library. Merge never removes local subscriptions, groups or history. |
| R7.7 | A sync that would unsubscribe more than 10 podcasts (or 20 %) or delete more than 3 groups asks before applying. |
| R7.8 | The server runs on Linux x86-64, arm64 and armv7 with Java 17+ and SQLite, makes no outbound request except an optional update check, takes daily backups, and offers a web page for devices and accounts. |
| R7.9 (v1.x) | AntennaPod and Kasts can sync subscriptions and play positions with a Neutrodyne Sync server using an app password. |

Non-functional changes:

| ID | Change |
|---|---|
| N1 | Add "sync never deletes local history by retention propagation; the mass-change guard holds bulk removals" |
| N3 | Add the user's sync server to the host list; disclosure that it stores listening history and private feed links |
| N5 | Sync is lazily initialised; cold start unchanged; new server budgets (≤ 160 MB RSS idle on Pi, Unverified) |
| N8 | Add the server JAR to "every shipped artefact"; Licensee on `:sync:server`; no published container image unless the owner amends D3 (Q2) |
| N9 | Add server-side input caps and client-side caps on server data |
| New N13 Sync security | HTTPS except loopback or an explicitly accepted LAN; hashed, revocable tokens; rate limits; no secrets in logs |

### Decisions (D-ids)

| D-id | Change |
|---|---|
| D1 | Amend: feeds stay distributed; add "optional self-hosted sync server for user state only" (new D81 below) |
| D3 / N8 | Add the server-artefact clause (fat JAR only; BYO Java; user-built images) or the runtime/base-layer carve-out decided with the KMP research's Q1; ban logback, argon2-jvm and MariaDB Connector/J |
| D9 | Unchanged on clients; triggers stay in the SQLite 3.18 dialect (no SQL `UPSERT`) |
| D13 | Add `:sync:protocol`, `:sync:api`, `:sync:impl`, `:sync:server` (+ module-graph rules: features → `:sync:api` only; `:sync:server` → `:sync:protocol`, `:feeds` common only) |
| D18 | Add `podcast.syncId` (UUIDv4) as the cross-device podcast identity; `feedKey` stays the dedupe and backup-matching key; rekeys propagate as `rekey` operations |
| D20 | Classify `ScopeOverrides` fields synced (playback) vs device-local (auto-download, notifications, refresh); `SettingKey` gains `synced: Boolean` (subset of `PORTABLE`) |
| D22 | Schema v1 additionally contains `podcast.syncId`, `orderKey TEXT` in `podcast_group`, `podcast_group_member` and `queue_entry` (replacing `sortOrder`/`ordinal`), the tables `sync_state`, `sync_outbox`, `sync_clock`, `sync_parked`, `sync_held`, and the inert capture triggers |
| D23 | Retention and stub cleanup never propagate (`applying` flag); server-side GC rules (§9.4) |
| D24 | Unsubscribe propagates as a tombstone, guarded by the mass-change rule |
| D29 | Groups with equal `nameKey` merge on sync (older survives), as in restore |
| D33 | Backup gains optional `syncId` and `orderKey`; restore while linked uses backup timestamps as HLC time; Replace asks "everywhere vs this device only" |
| D34 / D35 | `sync.server_url`, `sync.username` are `PORTABLE`; token and `sync_state` never backed up |
| D38 | `queue_entry.ordinal REAL` → `orderKey TEXT` (fractional index); 02's midpoint and renormalise rules replaced |
| D41 | Unchanged locally; sync push cadence separate (pause, transition, ≤ 60 s while playing) |
| D44 / D43 | Synced `session/current` adopted only by idle devices; never starts playback |
| D28 / 01 P15 | Request `ACCESS_LOCAL_NETWORK` contextually for LAN sync servers (Q10) |
| D60 | CI: conformance vectors, convergence property test, server black-box tests, Licensee on the server, a `docker build` smoke test of `server/deploy/` (nothing pushed) |
| D63 / D79 | Releases add `neutrodyne-sync-server-{v}.jar` (same tag and version as the APKs, Q11) with checksums and attestation; `neutrodyne-update.json` gains a `server` entry |
| D78 | The server's admin page reuses the notify-only check |
| **New D81** | Neutrodyne Sync: own protocol (record/field LWW + HLC, server cursor, SSE notifications), Kotlin/Ktor JVM server sharing `:sync:protocol`, SQLite, passwordless device linking, no E2EE in v1, gpodder compat in v1.x, OPA adapter when stable |

### Design documents

- **02:**
  - new columns and tables;
  - `orderKey` replaces the REAL/Int order columns in "Up next ordering", the group order and the member order;
  - capture triggers and their invalidation note (only `sync_*` tables are written);
  - restore matching reused for parked state.
- **05:**
  - backup fields;
  - restore-while-linked rules;
  - `RestoreMerger` shared with first-link merge;
  - `GroupNames` in common code.
- **06:**
  - session adoption;
  - push cadence;
  - "pull before play";
  - the remote played-while-playing case.
- **03:**
  - `SyncParkedStateApplier` hook after ingest;
  - move/merge application from sync.
- **01:**
  - modules;
  - LAN permission;
  - DataStore `synced` flag;
  - the `credential` origin `sync:<host>`.
- **08:** Settings › Sync screens, link flow, "Continue on this device" card, mass-change dialog.
- **09:**
  - server release assets;
  - server CI;
  - S14–S18.
- **New design document `10-sync.md`:** owns everything in this note.

### Milestones (Unverified sizes; one engineer with AI sessions)

| ID | Content | Size | Depends on |
|---|---|---|---|
| SY0 (inside M1a/M2/M4, +3–5 days total) | `syncId`, `orderKey` columns, inert sync tables and triggers in schema v1; `:sync:protocol` with `Hlc`, `OrderKey`, field kinds, vectors | — | M0 |
| **SY1** Server core | `:sync:server`: storage, migrations, auth (invite, link, password, tokens), `/sync`, `/changes`, merges, GC, backups, minimal web UI, CLI, deploy assets, release asset | L (3–4 w) | M3 (backup models and merge rules settled) |
| **SY2** Client sync | `:sync:impl` + `:sync:api`: capture, outbox, apply, parked state and stubs, first-link merge, mass-change guard, Settings › Sync, Android scheduling, desktop lane, restore-while-linked, LAN permission | L (3–4 w) | SY1, M4 (positions, queue), desktop D2 for the desktop lane |
| **SY3** Live updates and handoff | SSE client and server, session record and adoption, pull-before-play, position push cadence, "Continue on this device" | M (1–2 w) | SY2; desktop D1 (playback) to be meaningful |
| **SY4** gpodder compatibility (v1.x) | App passwords, gpodder v2 subset (+ optional Nextcloud routes), interop tests with AntennaPod and Kasts | M (1.5–2 w) | SY2 |
| SY5 (when OPA is stable) | OPA adapter | M | SY2 |
| SY6 (v2, optional) | Sealed mode (E2EE) | L | SY3 |

Sequencing: SY0 lands with the schema baseline (cheap insurance against a table-rebuild migration later). SY1 can run beside M4–M6 with a second engineer. SY2 and SY3 ship with desktop 1.0 as **v1.1**. **Android v1.0 is not blocked.**

### Spikes (numbered after the KMP research's S8–S13; renumber on integration)

| Spike | Question | Fallback |
|---|---|---|
| S14 Change capture | Triggers on synced columns under Room 3 + `BundledSQLiteDriver` (and `AndroidSQLiteDriver` with SQLite 3.18 dialect); overhead on the 5-s position write and on bulk mark-played of 5,000 rows; Room invalidation unaffected for list queries | Explicit `SyncRecorder` in transaction helpers + architecture test |
| S15 Convergence harness | Property test with simulated devices and skewed clocks converges; N1 invariants hold | Simplify field kinds |
| S16 Server on a Pi | RSS idle and during a 50k-record initial upload, start-up time, on Pi 4 (arm64) and Pi 3/Zero 2 W (armv7, 32-bit OS) with Java 17 and 21 | Tighter JVM flags; recommend ≥ 1 GB devices; Go server (Q2) |
| S17 Network paths | Android 17 `ACCESS_LOCAL_NETWORK` flow for LAN servers; SSE through Caddy and nginx (buffering, idle timeouts); Tailscale addresses | Polling-only on problematic setups |
| S18 gpodder interop (with SY4) | AntennaPod and Kasts against our gpodder layer (login, `since` semantics, played mapping) | Document unsupported clients |

### Risks (new)

| ID | Risk | Mitigation |
|---|---|---|
| SR1 | A sync bug propagates data loss to every device | Mass-change guard; N1 position guard in shared code; server pre-migration backups; nightly per-account backup ZIPs; convergence property tests |
| SR2 | Device clock far ahead pollutes LWW | Server admission bound (5 min), client offset correction, HLC clamping |
| SR3 | Episode-key divergence between app versions (D18 versions, normaliser versions) | Match hints in every episode record, `rekey` operations, protocol version gate |
| SR4 | Android 17 LAN rule breaks home servers silently | Contextual `ACCESS_LOCAL_NETWORK`, fast-failing guard with a clear message, S17 |
| SR5 | OPA stabilises with a different model | Adapter layer, not core; participate in OPA |
| SR6 | Owner rejects user-built images; self-hosters expect `docker run` | Q2/Q3; Go fallback |
| SR7 | Privacy: the server holds listening history and private feed tokens | Self-hosted only; disclosure; tokens never logged; sealed mode v2 |
| SR8 | Battery drain from SSE on Android | SSE only while visible or playing; 90 s heartbeat; periodic work otherwise |
| SR9 | Scope creep delays v1.0 | Groundwork only in v1.0; SY1–SY3 in v1.1 |

---

## Verified facts (with URLs)

**Specifications and APIs**

1. **OPA:**
   - The site describes "a feature-complete synchronization API specification for podcast (web) apps and user-focused servers" ([openpodcastapi.org](https://openpodcastapi.org/)).
   - The spec repository's licence is **CC BY-SA 4.0** ([LICENSE.md](https://github.com/OpenPodcastAPI/api-specs/blob/main/LICENSE.md)), and its OpenAPI file says `version: 0.1.0` with only `/subscriptions` and `/deletions` paths on `main` ([schema.yml](https://github.com/OpenPodcastAPI/api-specs/blob/main/schema.yml)).
   - "All specifications are currently 'in progress'. Breaking changes can occur" ([specs index](https://github.com/OpenPodcastAPI/api-specs/blob/main/src/content/docs/specs/index.mdx)). The last commit on `main` checked was 2026-09-23, "Add Conventions section and Badges (#197)".
   - The conventions on `main` say: client timestamps "are considered authoritative", last-write-wins, "updates are limited to 30 actions per request", and a UUIDv4 `Client-ID` header ([synchronization model](https://github.com/OpenPodcastAPI/api-specs/blob/main/src/content/docs/specs/conventions/synchronization-model.md), [timestamps](https://github.com/OpenPodcastAPI/api-specs/blob/main/src/content/docs/specs/conventions/timestamps-and-modifications.md), [client IDs](https://github.com/OpenPodcastAPI/api-specs/blob/main/src/content/docs/specs/conventions/client-ids.md)).
   - The central `POST/GET /api/v1/sync` and the login/refresh-token auth exist only in branch `subscriptions-redux`, last commit 2026-07-13 ([sync.md](https://github.com/OpenPodcastAPI/api-specs/blob/subscriptions-redux/src/content/docs/specs/sync.md), [authentication.md](https://github.com/OpenPodcastAPI/api-specs/blob/subscriptions-redux/src/content/docs/specs/authentication.md)). Episodes, with per-field value + timestamp and the waterfall matching guid → enclosure → 2 of 3 fields, exist only in branch `episodes-endpoint` ([episodes get-all](https://github.com/OpenPodcastAPI/api-specs/blob/episodes-endpoint/src/content/docs/specs/episodes/get-all.mdx), [identification](https://github.com/OpenPodcastAPI/api-specs/blob/episodes-endpoint/src/content/docs/specs/episodes/identification-deduplication.mdx)).
   - The 2026-06-29 meeting decided "we go with OAuth" and "we prioritise the sync endpoint" ([meeting notes](https://github.com/OpenPodcastAPI/api-specs/blob/main/meeting-notes/2026-06-29.md)).
2. **PortCast** `draft-trimplayer-portcast-00`: individual Internet-Draft, published 2026-05-28, expires 2026-11-29, "no formal standing", LWW via `updatedAt` and `If-Match`/412 ([datatracker](https://datatracker.ietf.org/doc/html/draft-trimplayer-portcast-00)).
3. **gpodder API v2:**
   - Subscriptions are device-scoped: `POST /api/2/subscriptions/(username)/(deviceid).json` with `add`/`remove`, a response `timestamp` and `update_urls` ([subscriptions](https://gpoddernet.readthedocs.io/en/latest/api/reference/subscriptions.html)).
   - Episode actions are `download, play, delete, new`, with `started`/`position`/`total` in seconds and an ISO 8601 UTC `timestamp`; parameters `since` and `aggregated` ([events](https://gpoddernet.readthedocs.io/en/latest/api/reference/events.html)).
   - Auth is HTTP Basic plus a `sessionid` cookie ([auth](https://gpoddernet.readthedocs.io/en/latest/api/reference/auth.html)); device sync groups use `/api/2/sync-devices/(username).json` ([sync](https://gpoddernet.readthedocs.io/en/latest/api/reference/sync.html)).
4. **RFC 8628**, OAuth 2.0 Device Authorization Grant (Aug 2019): `device_code`, `user_code`, `verification_uri`, `interval`, `authorization_pending`, `slow_down` ([rfc-editor](https://www.rfc-editor.org/rfc/rfc8628)).
5. **RFC 9457** "Problem Details for HTTP APIs" (2023) obsoletes RFC 7807; media type `application/problem+json` ([rfc-editor](https://www.rfc-editor.org/rfc/rfc9457)).
   **Firefox SyncStorage 1.5:** the `newer` parameter, `X-Last-Modified`, and `X-If-Unmodified-Since` → `412` ([Mozilla docs](https://mozilla-services.readthedocs.io/en/latest/storage/apis-1.5.html)).
6. **HLC:** Kulkarni, Demirbas, Madeppa, Avva, Leone, "Logical Physical Clocks and Consistent Snapshots in Globally Distributed Databases" (2014): "HLC fits in 64 bits NTP timestamp format" ([PDF](https://cse.buffalo.edu/tech-reports/2014-04.pdf)).
7. **Fractional indexing:** `rocicorp/fractional-indexing` is CC0-1.0, based on David Greenspan's notebook and Figma's article ([repo](https://github.com/rocicorp/fractional-indexing), [Greenspan](https://observablehq.com/@dgreensp/implementing-fractional-indexing), [Figma](https://www.figma.com/blog/realtime-editing-of-ordered-sequences/)).

**Existing servers and clients**

8. **mygpo** (gpodder.net server) is AGPL-3.0 ([repo](https://github.com/gpodder/mygpo), `COPYING`). AntennaPod's docs say gpodder.net "is often overloaded, leading to errors in AntennaPod", and list Nextcloud gPodder Sync, oPodSync, goPodder, podsync and malipod as self-hosted options ([AntennaPod sync docs](https://antennapod.org/documentation/general/synchronization)).
9. **Nextcloud gPodder Sync:** AGPL-3.0, version 3.18.0 (2026-09-18); endpoints `/index.php/apps/gpoddersync/subscriptions`, `…/episode_action` with an optional `guid` and Unix-time `since`; 3.13.3 started to "ignore actions DELETE and DOWNLOAD" ([repo](https://github.com/thrillfall/nextcloud-gpodder), README, CHANGELOG).
10. **oPodSync:** AGPL-3.0, PHP + SQLite, gpodder and Nextcloud APIs, "Implement the Open Podcast API" on its roadmap ([repo](https://github.com/kd2org/opodsync)).
11. **goPodder:** Apache-2.0, Go, SQLite or PostgreSQL, "never fetches feed URLs", "~13 MB RAM at idle", Docker image on GHCR ([repo](https://github.com/cbrgm/gopodder)).
12. Other servers:
    - gpodder2go: AGPL-3.0 ([repo](https://github.com/oxtyped/gpodder2go));
    - podsync: `GPL-3.0-or-later` in `Cargo.toml` ([repo](https://github.com/bobrippling/podsync));
    - PodFetch: Apache-2.0 ([repo](https://github.com/SamTV12345/PodFetch));
    - PinePods and Audiobookshelf: GPL-3.0 ([PinePods](https://github.com/madeofpendletonwool/PinePods), [Audiobookshelf](https://github.com/advplyr/audiobookshelf));
    - Podhound: MIT, Bun + SQLite, gpodder API v2, announced 2026-08-30 ([forum post](https://forum.antennapod.org/t/podhound-lightweight-self-hosted-podcast-sync-server-gpodder-api-v2/9100); repo licence not opened, Unverified beyond the post).
13. **AntennaPod (GPL-3.0):**
    - Sync providers are gpodder.net and Nextcloud; actions `NEW, DOWNLOAD, PLAY, DELETE`.
    - On first sync it uploads every played episode as `PLAY` with `started = position = total = duration` and `.currentTimestamp()` ([SyncService.java L228–L248 at 9c7ffa1](https://github.com/AntennaPod/AntennaPod/blob/9c7ffa16736c020045571d274aef4cdcf0878100/net/sync/service/src/main/java/de/danoeh/antennapod/net/sync/service/SyncService.java#L228-L248), [EpisodeAction.java](https://github.com/AntennaPod/AntennaPod/blob/9c7ffa16736c020045571d274aef4cdcf0878100/net/sync/service-interface/src/main/java/de/danoeh/antennapod/net/sync/serviceinterface/EpisodeAction.java)).
14. **Kasts** (`GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL`):
    - Providers are `GPodderNet` and `GPodderNextcloud`.
    - Played is inferred from `position ≥ total − markAsPlayedBeforeEnd`.
    - `download`/`delete`/`new` are not applied because they are "broken in gpodder.net" ("the service only allows to upload only one download or delete action per episode") ([sync.cpp at d8c7bf5, ≈ L842–L915](https://invent.kde.org/multimedia/kasts/-/blob/d8c7bf599e620d145a05af348dfc05a2291eecff/src/sync/sync.cpp), [syncutils.h](https://invent.kde.org/multimedia/kasts/-/blob/d8c7bf599e620d145a05af348dfc05a2291eecff/src/sync/syncutils.h)).

**Server stack**

15. **Ktor 3.6.0**, published 2026-09-16 on Maven Central: server core, CIO, SSE, auth, rate-limit, CSRF, compression, forwarded-header, call-id and html-builder ([ktor-server-core 3.6.0](https://repo1.maven.org/maven2/io/ktor/ktor-server-core/3.6.0/), [maven-metadata](https://repo1.maven.org/maven2/io/ktor/ktor-server-sse/maven-metadata.xml)). Licence Apache-2.0 ([LICENSE](https://github.com/ktorio/ktor/blob/main/LICENSE)).
    - The `ktor-server-cio` 3.6.0 module metadata lists jvm, linuxX64, linuxArm64 and macosArm64 variants ([.module](https://repo1.maven.org/maven2/io/ktor/ktor-server-cio/3.6.0/ktor-server-cio-3.6.0.module)).
    - Native server: "only the CIO engine is supported", "HTTPS without a reverse proxy is not supported" ([Ktor native server](https://ktor.io/docs/server-native.html)).
    - SSE server plugin with heartbeat ([docs](https://ktor.io/docs/server-server-sent-events.html)); the client "SSE only requires the ktor-client-core artifact", with reconnection support ([docs](https://ktor.io/docs/client-server-sent-events.html)).
    - Request decompression via `CompressionConfig.Mode.DecompressRequest`, with no documented size limit ([docs](https://ktor.io/docs/server-compression.html)).
16. **Kotlin/Native tiers:** `linuxX64` and `linuxArm64` are Tier 2, `macosArm64` Tier 1, `mingwX64` Tier 3 ([target support](https://kotlinlang.org/docs/native-target-support.html)).
17. Google Maven module metadata: `room3-runtime` 3.0.3 publishes jvm, linuxX64, linuxArm64 and macosArm64 variants ([.module](https://dl.google.com/android/maven2/androidx/room3/room3-runtime/3.0.3/room3-runtime-3.0.3.module)), and so does `sqlite-bundled` 2.7.1 ([.module](https://dl.google.com/android/maven2/androidx/sqlite/sqlite-bundled/2.7.1/sqlite-bundled-2.7.1.module)).
18. **sqlite-jdbc** 3.53.4.0 (Maven `lastUpdated` 2026-08-26), Apache-2.0. Native libraries checked in the JAR: Linux x86, x86_64, aarch64, armv6, armv7, arm, riscv64, ppc64, musl x86/x86_64/aarch64, FreeBSD, macOS x86_64/aarch64, Windows x86/x86_64/aarch64/armv7. JAR size 12.0 MB ([artifact](https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/)).
19. SQLite UPSERT "follows the syntax established by PostgreSQL", added in 3.24.0 and generalised in 3.35.0 ([lang_upsert](https://www.sqlite.org/lang_upsert.html)). `VACUUM INTO` is "an alternative to the backup API for generating backup copies of a live database" ([lang_vacuum](https://www.sqlite.org/lang_vacuum.html)).
20. Other server-side libraries on Maven Central:
    - **Exposed:** 1.0.0 published 2026-01-22, latest 1.5.0 (2026-08-26), Apache-2.0 ([metadata](https://repo1.maven.org/maven2/org/jetbrains/exposed/exposed-core/maven-metadata.xml), [LICENSE](https://github.com/JetBrains/Exposed/blob/main/LICENSE.txt)).
    - **PostgreSQL JDBC:** 42.7.13, BSD-2-Clause ([license](https://jdbc.postgresql.org/license/)).
    - **HikariCP:** 7.1.0. **Flyway:** 13.9.0. **kotlinx-html:** 0.12.0. **Tink:** 1.23.0 ([Maven Central](https://repo1.maven.org/maven2/)).
21. Libraries we avoid:
    - **Bouncy Castle:** "Bouncy Castle APIs are released under the MIT license" ([licence](https://www.bouncycastle.org/about/license/)); `bcprov-jdk18on` 1.86 contains `org.bouncycastle.crypto.generators.Argon2BytesGenerator` (checked in the JAR).
    - **argon2-jvm** 2.12: LGPL-3.0 ([POM](https://repo1.maven.org/maven2/de/mkammerer/argon2-jvm/2.12/argon2-jvm-2.12.pom)).
    - **MariaDB Connector/J:** LGPL-2.1-or-later ([POM 3.5.6](https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.6/mariadb-java-client-3.5.6.pom)).
    - **logback:** "dual-licensed under the EPL v2.0 and the LGPL 2.1" ([licence](https://logback.qos.ch/license.html)).
22. **OWASP** Argon2id minimum: "m=19456 (19 MiB), t=2, p=1" ([cheat sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)).

**Runtimes, images and deployment**

23. **Debian 12 (bookworm)** ships `openjdk-17-jre-headless` 17.0.20.1 for armhf and arm64 ([packages.debian.org](https://packages.debian.org/bookworm/openjdk-17-jre-headless)), so a Java 17+ requirement needs no third-party JRE on Raspberry Pi OS (bookworm base).
    **Temurin** binaries are under "GNU General Public License, version 2 with the Classpath Exception" ([Adoptium FAQ](https://adoptium.net/docs/faq/)). Temurin 21 and 25 support Linux x64, aarch64, ARM 32-bit hard-float, ppc64le, s390x, riscv64 and Alpine x64/aarch64 ([supported platforms](https://adoptium.net/supported-platforms/)).
24. **Base-layer licences:** glibc is LGPL ("either version 2 of the License, or … any later version") ([GNU libc](https://www.gnu.org/software/libc/)); "BusyBox is licensed under the GNU General Public License, version 2" ([BusyBox](https://www.busybox.net/license.html)).
25. **Go alternative:** `modernc.org/sqlite` v1.60.1 (2026-09-29), BSD-3-Clause, "no cgo" ([pkg.go.dev](https://pkg.go.dev/modernc.org/sqlite)). The Go prototype linked as "statically linked" per `file(1)`.
26. **Compose** build context: "A Git repository URL. Git URLs accept context configuration in their fragment section … reference … subdirectory" ([Compose build spec](https://docs.docker.com/reference/compose-file/build/)).
27. **GitHub releases:** `make_latest` accepts `true`/`false`/`legacy` and "Defaults to true for newly published releases" ([REST docs](https://docs.github.com/en/rest/releases/releases#create-a-release)). This is needed only if server releases get their own tags (Q11).
28. **Caddy** is Apache-2.0 ([LICENSE](https://github.com/caddyserver/caddy/blob/master/LICENSE)).

**Android platform**

29. WorkManager: "The minimum repeat interval that can be defined is 15 minutes" ([define work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)).
30. Android 17: "local network protections are mandatory and enforced for apps targeting Android 17 or higher". The rule covers `ACCESS_LOCAL_NETWORK` (a runtime permission, `NEARBY_DEVICES` group), outgoing TCP to local addresses ("typically result in a timeout error") and `.local` mDNS; DNS to a local resolver on port 53 is exempt ([local network permission](https://developer.android.com/privacy-and-security/local-network-permission)).
31. UnifiedPush `android-connector` is Apache-2.0, last commit 2026-08-22 ([Codeberg](https://codeberg.org/UnifiedPush/android-connector)).

**Measurements in this session**

32. Measured on 2026-10-05, in a 4-vCPU x86-64 Linux container with 16 GB RAM, OpenJDK 21.0.11, Go 1.26.0. Both prototypes ran the same 2 endpoints and the same SQLite schema; load was 100 pushes of 500 field changes followed by paged pulls.

    | Prototype | Size | Idle RSS | RSS after load | Push 50k | Pull 50k |
    |---|---|---|---|---|---|
    | Ktor 3.6.0 CIO + sqlite-jdbc 3.53.4.0, `-XX:+UseSerialGC` (default heap) | 27.5 MB of JARs | 133 MB | 173 MB | 1.71 s | 0.54 s |
    | Same, `-Xmx64m -XX:+UseSerialGC -Xss512k -XX:ReservedCodeCacheSize=32m -XX:MaxMetaspaceSize=64m -XX:TieredStopAtLevel=1` | 27.5 MB of JARs | 92 MB | 105 MB | 1.97 s | 0.70 s |
    | Go + modernc.org/sqlite 1.60.1 (`CGO_ENABLED=0`, `-s -w`) | 10.4 MB static binary | 9 MB | 20 MB | 1.38 s | 0.35 s |

    The 50,000 single-field registers took 5.0 MB of database plus 6.1 MB of WAL. Not measured on a Pi.

---

## Pitfalls & risks

1. **"Played" is not a field in gpodder.** Clients infer it from `position ≥ total − threshold`; unplayed is `new`, which is unreliable. Any gpodder bridge must be lossy and must never be the source of truth for Neutrodyne-to-Neutrodyne sync.
2. **First-sync timestamp stamping.** Stamping existing state with "now" (as AntennaPod does on first sync) lets a stale device win every conflict once. Use row timestamps.
3. **Wall-clock LWW** (OPA's current model) lets one device with a wrong clock win for as long as the error lasts. HLC plus an admission bound fixes that. Server time must be sane too: `doctor` warns if NTP is off (Unverified detection method).
4. **`feedKey` as identity breaks on moves.** Use `syncId`, with feed keys as a dedupe index; handle the merge when a move collides.
5. **Retention vs sync.** Propagating local retention deletes would erase history on other devices. Re-creating stubs for every pulled played record would defeat D23. Hence the `applying` flag and parked state.
6. **The playing item is not in Up next (06).** Without a synced session, handoff loses it. Never seek or start a playing player from sync (D43).
7. **Position writes every 5 s.** Pushing at that rate wastes battery and server writes. The outbox coalesces; push only on pause, transition and every ≤ 60 s.
8. **Sequence gaps on PostgreSQL.** A global `BIGSERIAL` lets readers skip rows committed late. The per-account counter row taken under lock in the write transaction avoids it.
9. **Tombstone purge vs long-offline devices.** After the horizon, `410 cursor_expired` forces a resync. Records deleted elsewhere could otherwise be resurrected by the stale device; only outbox changes newer than the horizon are pushed.
10. **SSE behind proxies.** nginx buffers by default and idle timeouts cut connections. Document `proxy_buffering off` and a heartbeat shorter than proxy timeouts. Some corporate proxies break SSE; polling covers it.
11. **Android 17 local network.** A home server on `192.168.x.x`, or a public name that resolves to it inside the LAN, times out without `ACCESS_LOCAL_NETWORK`. The plan currently forbids that permission (01 P15).
12. **Debuggable published APKs** (P11). The sync token is reachable by anyone with ADB and debugging on. It is revocable, but say so in the help.
13. **Logging secrets.** Feed URLs can embed tokens (R1.9). Redact in server logs and never log request bodies.
14. **Ktor generator defaults** (logback, EPL/LGPL) would slip into the server JAR unless banned. Request decompression has no built-in bomb guard.
15. **Kotlin/Native temptation.** The native server needs a reverse proxy for TLS, is Tier 2 on Linux, has no armv7, and its static runtime licence content is Unverified.
16. **Docker image expectations.** Self-hosters expect `docker run ghcr.io/…`. A user-built image needs BuildKit and a network fetch of the JAR at build time. This is mitigated by `compose.yaml` with a Git build context, but still a step more than goPodder.
17. **Group-name merge surprises.** Two different groups that happen to share a name ("News" vs "news") merge. That is consistent with restore, but must be explained in the merge notice.
18. **Desktop single-instance and sync.** Two desktop instances with one DB would double-push. The KMP research's single-instance lock is a prerequisite.
19. **OPA moving target.** The spec's sync endpoint, episode identity and auth are still changing (OAuth decided 2026-06-29). Do not hard-wire OPA concepts into storage beyond what they cost nothing to keep (client IDs, podcast GUIDs, per-field timestamps).
20. **Release coupling.** If server releases get separate tags, a forgotten `make_latest=false` would point `releases/latest` at a server release and break the app's update check (D78), which reads `releases/latest/download/neutrodyne-update.json`. One tag for both avoids it.
21. **Legal nuance.** We implement the gpodder API from documentation only; reading AGPL server code while writing ours invites accusations of derivation. Keep a clean-room note in `10-sync.md`.

---

## Questions the owner must answer (with a recommended default each)

| # | Question | Recommended default |
|---|---|---|
| Q1 | **When does sync ship?** | **Groundwork (identity, `orderKey`, inert tables) in v1.0; server + Android + desktop sync in v1.1 together with desktop 1.0.** Android v1.0 is not blocked |
| Q2 | **Server runtime and image under D3.** A published container image or bundled runtime ships GPL/LGPL (OpenJDK GPL+CE, OS layers). Options: (a) Kotlin/JVM fat JAR + admin-installed Java, user-built image from our Dockerfile; (b) same plus a D3 carve-out to publish an image (decide together with the KMP research's Q1); (c) Go server with a GPL/LGPL-free `FROM scratch` image, losing shared Kotlin code | **(a)** now; revisit (b) when the desktop JRE question is decided |
| Q3 | **Channel.** "GitHub Releases only": may a container image live on GHCR (GitHub Packages)? | **No published image in v1** (follows Q2a); if Q2b, GHCR is the only registry |
| Q4 | **Protocol strategy.** Own protocol + gpodder compat + OPA adapter later, or wait for OPA? | **Own protocol now; gpodder v2 subset in v1.x (SY4); OPA adapter when OPA tags a stable release; the project gives feedback to OPA** |
| Q5 | **Accounts and sign-in.** | **Admin-created accounts, invite codes, passwordless device linking, optional password; no self-registration; no OIDC in v1** |
| Q6 | **What syncs beyond the obvious** (playback overrides yes; auto-download, notification and refresh overrides; appearance; download settings)? | **Sync listening state, library structure and playback preferences; keep auto-download, notifications, refresh intervals, appearance and all download settings per device** |
| Q7 | **Private feed URLs** (tokens) on the server? | **Yes, with disclosure** (otherwise private feeds cannot sync); Basic-auth passwords never |
| Q8 | **End-to-end encryption?** | **Not in v1**; keep sealed mode as a v2 option |
| Q9 | **PostgreSQL support?** | **SQLite only in v1**; PostgreSQL only on demand (SQL kept portable) |
| Q10 | **Android LAN servers.** Allow requesting `ACCESS_LOCAL_NETWORK` (amends D28 / 01 P15), or require a public HTTPS name? | **Request it contextually** in sync setup when the server resolves to a local address; explain it in the help |
| Q11 | **Server versioning.** Same tags and version as the app, or separate `server-v*` releases? | **Same tag and version** (one pipeline; `releases/latest` stays the app's); the protocol version decouples compatibility |
| Q12 | **Server update notice** (one daily request to GitHub from the server)? | **On by default in the admin page, disclosed, switchable off**, mirroring D78 |
| Q13 | **Server-side per-account backups** (Neutrodyne backup ZIPs, 14 days)? | **Yes** |
| Q14 | **Mass-change guard thresholds.** | **> 10 podcasts or > 20 % of podcasts, or > 3 groups, in one pull** |
| Q15 | **Stale devices.** Auto-revoke tokens of devices unseen for 180 days? | **Flag, don't revoke**; the user revokes in the device list |
