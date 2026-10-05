# Neutrodyne research: playback and streaming (AndroidX Media3)

Research date: 2026-10-04. Media3 facts were checked against the `release` branch of
`androidx/media` (commit `8c6678b`, 2026-09-08, which is version 1.11.1), against Google Maven metadata, and
against developer.android.com. Every version number and non-obvious platform claim is listed with
its source in "Verified versions & facts". Anything I could not verify is marked **UNVERIFIED**.

---

## Recommendation

1. **Use Media3 1.11.1**, the current stable release (2026-09-10). There is no 1.12 alpha yet. Modules:
   `media3-exoplayer`, `media3-session`, `media3-datasource-okhttp`, `media3-ui-compose`,
   `media3-ui-compose-material3`, `media3-common-ktx`. Add `media3-exoplayer-hls` only if YouTube
   live or HLS feeds are in scope. Add `media3-cast` only in a "play" product flavor (see item 12).
   Media3 1.11.1 is built with `minSdk 23` and `compileSdk 36`.

2. **Use one `PlaybackService : MediaLibraryService`, not a plain `MediaSessionService`.** The extra
   cost is a small browse tree (`onGetLibraryRoot`/`onGetChildren`). In return we get:
   - Android Auto and AAOS.
   - Bluetooth AVRCP browsing. 1.11.0 fixed AVRCP recognition of `MediaLibraryService` on API 36/37.
   - Assistant "play X on Neutrodyne".
   - **The System UI playback-resumption card after reboot.** In 1.11.1 the "recent root" path that
     calls `onPlaybackResumption(isForPlayback = false)` only exists in `MediaLibrarySessionImpl`.

   The browse tree maps directly onto the product's *groups*: root → Up next / Groups → each group's
   feed / Downloads / Podcasts.

3. **Use one `ExoPlayer` instance owned by the service, for both audio and video.** Configure it with:
   - `AudioAttributes(USAGE_MEDIA, AUDIO_CONTENT_TYPE_SPEECH)` with `handleAudioFocus = true`.
     Speech content makes ExoPlayer *pause* rather than duck on navigation prompts. Offer this as a
     user setting.
   - `setHandleAudioBecomingNoisy(true)`.
   - Wake mode `C.WAKE_MODE_NETWORK` while the current item streams and `C.WAKE_MODE_LOCAL` for local
     files. LOCAL is the 1.9+ default.
   - `setSeekBackIncrementMs` / `setSeekForwardIncrementMs` from preferences. These can be changed at
     runtime since 1.9.
   - A custom `DefaultRenderersFactory.buildAudioSink` that installs
     `DefaultAudioProcessorChain([BoostLimiterProcessor], SilenceSkippingAudioProcessor(tuned), SonicAudioProcessor())`.
   - A custom `MediaSource.Factory` built on the DataSource stack in item 5.

4. **The database is the single source of truth for the queue. The ExoPlayer playlist is a
   projection that only the service maintains**: current item, then "Up next", then the first *K*
   items of the play context (for example a group feed). The UI never calls
   `addMediaItem`/`removeMediaItem` on the controller. It writes to `QueueRepository`, and the
   service's `QueueProjector` diffs the DB snapshot against the player playlist using
   `mediaId == episodeId`. Requests that external controllers make through `onSetMediaItems` and
   `onAddMediaItems` (Android Auto, Assistant) are written to the DB first.

5. **Give every episode `MediaItem` the stable URI `neutrodyne://episode/{id}`** with
   `customCacheKey = "ep:{id}:{enclosureFingerprint}"`. A `ResolvingDataSource` turns it into the real
   source each time a connection opens:
   - a local download (`file://` or `content://`, which bypasses the cache);
   - a pinned remote enclosure URL (through `CacheDataSource` and OkHttp);
   - a freshly resolved YouTube stream URL, re-resolved when it nears expiry or after an HTTP 403.

   This gives three things. Downloaded files play transparently. Expiring YouTube URLs heal
   themselves mid-episode. Metadata changes can use `replaceMediaItem` without interrupting playback,
   because `ProgressiveMediaSource.canUpdateMediaItem` only compares the URI, the
   `customCacheKey` and the image duration.

6. **Stream through `CacheDataSource` backed by a process-singleton `SimpleCache`** with
   `LeastRecentlyUsedCacheEvictor` (default 500 MB, user-configurable) in `filesDir/media-cache`.
   This is the streaming cache only. Downloads are separate files owned by the download subsystem,
   because an LRU cache would evict them.

7. **Notification: keep `DefaultMediaNotificationProvider` and drive it through media button
   preferences.**
   - `SLOT_BACK`: `COMMAND_SEEK_BACK`, icon `ICON_SKIP_BACK_{5|10|15|30}`.
   - `SLOT_FORWARD`: `COMMAND_SEEK_FORWARD`, icon `ICON_SKIP_FORWARD_*`.
   - `SLOT_OVERFLOW`: "Next episode" (`COMMAND_SEEK_TO_NEXT_MEDIA_ITEM`) and a "Speed" custom command
     (`ICON_PLAYBACK_SPEED_*`).
   - On API 33+ System UI builds the controls from the session, not from the notification, so a
     custom provider brings little.

8. **Playback resumption.**
   - Declare `androidx.media3.session.MediaButtonReceiver`.
   - Implement `onPlaybackResumption(session, controller, isForPlayback)` from the DB: the last
     episode, its position and a *local* artwork bitmap or `content://` URI.
   - Persist positions continuously (item 10) so resumption is correct even after a process kill.

9. **Per-episode settings live in the DB and the service applies them at each media-item
   transition.** The resolution order is episode override → podcast → group of the current play
   context → global. Covered settings: playback speed, skip silence, volume boost, intro/outro skip.

10. **Persist position** every 10 s while playing, on pause, on seek, on item transition (using
    `onPositionDiscontinuity`'s `oldPosition`) and in `onDestroy`. **Mark an episode played** on an
    automatic transition, on `STATE_ENDED`, on an end-of-item pause, or when the user skips with less
    than max(30 s, 3 %) left.

11. **Implement the sleep timer, chapters, video handling and the queue logic inside the service.**
    Expose them to the UI through custom `SessionCommand`s plus an in-process `StateFlow` singleton.
    - "End of episode" uses `ExoPlayer.setPauseAtEndOfMediaItems(true)`.
    - "End of chapter" uses `ExoPlayer.createMessage(...).setPosition(...)`.
    - Chapters merge Podcasting 2.0 JSON, Podlove PSC, ID3 `CHAP` / MP4 chapters (Media3's
      `Chapter` metadata entries, MP4 support new in 1.11) and YouTube description timestamps.

12. **Treat Chromecast as optional and confine it to a `play` flavor**, using
    `CastPlayer.Builder(ctx).setLocalPlayer(exoPlayer)` (1.9+) with the system Output Switcher.
    Cast needs Google Play services, which rules it out of F-Droid builds. It cannot play local
    downloads or (probably) YouTube streams.

13. **UI boundary.**
    - The UI talks to the service only through an app-process-scoped `MediaController`
      (`PlayerConnection`), plus repositories for data.
    - Release the controller in `ProcessLifecycleOwner.onStop`, otherwise the service can never stop.
    - Every "start playing" path goes through `controller.play()` while the UI is visible. This
      starts the foreground service with while-in-use capability, which **Android 17 (API 37)
      background-audio hardening** requires.

**Risks that could change the overall plan:**
- Android 17 background-audio hardening means any playback started without a visible activity, a
  notification tap, a media key or Media3's service lifecycle is **silently muted**. Background
  auto-play features must be designed for this; see Pitfalls.
- YouTube stream resolution (another research area) decides how robust item 5 is. If YouTube needs
  separate video and audio streams, video playback needs a custom lazy `MediaSource`, not just a
  `ResolvingDataSource`.
- Android Auto only shows apps that were not installed from Play if the user enables a developer
  option.

---

## Options considered

### A. Service type: `MediaSessionService` vs `MediaLibraryService`

| | MediaSessionService | MediaLibraryService (chosen) |
|---|---|---|
| Notification, lock screen, System UI controls (API 33+ derive controls from the session) | yes | yes |
| Bluetooth transport controls and metadata (AVRCP) | yes | yes |
| AVRCP *browsing* (car head units that list content) | no | yes (fixed for API 36/37 in 1.11.0) |
| Android Auto / AAOS | no (needs a browsable service) | yes |
| Wear OS phone-media controls | yes (automatic via the session) | yes |
| System UI resumption card after **reboot** (`isForPlayback = false`) | **no**: the recent-root handling lives only in `MediaLibrarySessionImpl` | yes |
| Assistant / `MEDIA_PLAY_FROM_SEARCH` | partial | yes (`onSearch`, `onAddMediaItems` with `requestMetadata.searchQuery`) |
| Extra code | none | `onGetLibraryRoot` and `onGetChildren` (~200 lines over existing repositories) |

A minimal tree costs little, and the reboot-resumption difference is decisive. Choose
`MediaLibraryService`.

### B. Queue model: what is the "playlist"?

1. **(Chosen) DB is the truth and the player holds a projection window.** The DB has an ordered
   `queue_entry` table plus a `play_context` (group, podcast, downloads…). The player holds
   `[current] + upNext + first K context items`, with K ≈ 20. The service tops the window up after
   each transition.
   - Pros: real pre-buffering and gapless transitions; Android Auto, Wear and the notification see
     a real "next"; the 10-min foreground timeout never matters between episodes; the UI shows the
     DB queue (with drag-reorder, swipe-remove) independently of the player; queues of any size stay
     cheap.
   - Cons: a diff/sync engine (~300 lines) and discipline about who mutates what.
2. **Mirror the full queue 1:1 into ExoPlayer.** Simple when the queue is small. It breaks down when
   "play group feed" means 2,000 episodes: every item gets a `MediaItem` and every timeline update
   is serialized to controllers and to the legacy session queue.
3. **One item at a time.** The player holds only the current item, and on `STATE_ENDED` the service
   loads the next one from the DB. This is AntennaPod's historical approach.
   - Simplest code.
   - No pre-buffering, so there is an audible gap.
   - No queue in Auto, and next/previous need a `ForwardingPlayer` to advertise commands.
   - Every transition passes through `STATE_ENDED`. Media3 keeps the foreground service for up to
     10 min afterwards, so this is not fatal, but it is fragile.
4. **`ForwardingSimpleBasePlayer` presenting a virtual full-queue timeline over a windowed
   ExoPlayer.** Most "correct" for external controllers, but the complexity is very high. Revisit
   only if Auto users need to scroll huge queues.

### C. When to turn an episode into a playable URL

1. **(Chosen for progressive audio and video) `ResolvingDataSource` with a custom scheme** resolves
   at every connection open, which covers the initial load, each seek-induced range request and each
   retry.
   - Handles expiring YouTube URLs mid-episode and downloads that finish while queued.
   - Lets metadata-only `replaceMediaItem` work.
   - Limits: it only works for *single-URL progressive* sources (MP3, M4A, MP4, WebM, Ogg).
     HLS/DASH manifests with relative URLs cannot sit behind a custom scheme.
   - Resolution runs on the loader thread and may block; the API allows this.
2. **Resolve when building the `MediaItem`.** Simple, but URLs go stale in a queue (YouTube's expire
   in hours) and downloads that finish later are ignored.
3. **A custom lazy `MediaSource`** (subclass `CompositeMediaSource<Void>` that resolves
   asynchronously in `prepareSourceInternal`, then prepares a child source). This is needed if a
   YouTube item must become a `MergingMediaSource` of separate video-only and audio-only streams.
   NewPipe does the equivalent outside the player: a sliding window of
   `PlaceholderMediaSource`/`LoadedMediaSource` with an expiry timestamp. Plan it as phase 2 for
   YouTube *video* only.
4. **Error-recovery `replaceMediaItem`.** On HTTP 403 or 410 from `onPlayerError`, re-resolve,
   replace the item, seek to the saved position and `prepare()`. Keep this as a safety net for
   every strategy.

### D. Streaming cache

1. **No cache (plain progressive).** Every seek backwards re-downloads, and resuming after a long
   pause re-fetches.
2. **(Chosen) `CacheDataSource` with an LRU `SimpleCache`.** Cheap, transparent, and makes
   rewind, skip-back and short offline gaps robust. One `SimpleCache` instance per folder per
   process; a second instance on the same folder throws.
3. **Media3 `DownloadManager`/`DownloadService` as the download store,** so downloads are just
   pinned cache content. Rejected for this area:
   - Downloads must not share an LRU-evicting cache.
   - Files are opaque spans, so they cannot be exported or kept in a user-chosen folder.
   - It runs its own foreground service.
   The download research area owns this decision. Playback only needs "is there a local URI for
   episode X?".

### E. Volume boost

1. **`android.media.audiofx.LoudnessEnhancer` on `player.audioSessionId`.** AntennaPod does this:
   gain in mB = 1000 × (v − 1), and it re-attaches on `onAudioSessionIdChanged`.
   - Zero DSP code.
   - Device-dependent: some OEMs lack the effect or misbehave, which is why AntennaPod has
     `isBoostSupported()`.
   - No soft limiter control.
2. **(Chosen) A custom `BaseAudioProcessor`** doing gain plus a soft limiter, placed first in
   `DefaultAudioProcessorChain`.
   - Deterministic on every device.
   - Works per item, since parameters can change at transitions.
   - About 150 lines plus unit tests.
   - Does not run when audio offload is active. Media3: "Audio processing is not supported in
     offload or passthrough mode."
   - Media3's own `GainProcessor` only attenuates (gain in [0, 1]), so it cannot boost.
3. **Mono downmix** as an accessibility extra for one-earbud listening, using Media3's
   `ChannelMixingAudioProcessor` with `ChannelMixingMatrix.createForConstantPower`.

### F. HTTP stack for media

`OkHttpDataSource` from `media3-datasource-okhttp`, sharing the app's single `OkHttpClient`, is the
recommended choice. It brings the same User-Agent, connection pool, DNS, proxy settings and
interceptors that feed refresh uses, and it follows http→https redirects by default.
`DefaultHttpDataSource` refuses cross-protocol redirects unless
`setAllowCrossProtocolRedirects(true)` is set, and podcast tracking prefixes (Podtrac, Chartable,
OP3 and others) frequently redirect http→https. A Ktor data source (`media3-datasource-ktor`) arrived
in 1.11.0 if the app standardises on Ktor. Cronet and `HttpEngineDataSource` are unnecessary here.

### G. Starting a later queue item at its saved position

1. **(Chosen) Seek on transition.** In `onMediaItemTransition` with reason `AUTO` or `SEEK`, look up
   the saved position and call `seekTo(savedMs)` if it is above 5 s and below duration − 30 s. This
   costs a short re-buffer, discards the pre-buffer, and is rare in practice.
2. **A wrapping `MediaSource` whose `Timeline.Window.defaultPositionUs` equals the saved position.**
   ExoPlayer starts auto-transitioned windows at the default position, so this is seamless. It needs
   a custom `ForwardingTimeline`, and the position is frozen at source creation. Phase 2 nicety.
3. **`ClippingConfiguration.startPositionMs`.** Rejected: it changes the timeline, so the user
   cannot rewind before the clip start.

### H. Chromecast

`CastPlayer.Builder(context).setLocalPlayer(exoPlayer)` (1.9+) wraps the local player and swaps to
`RemoteCastPlayer` when a session starts, with a `TransferCallback` to move state between them. The
session demo uses exactly this. Alternatives are no Cast at all, or manual `RemoteCastPlayer`
switching in the service. Recommendation: phase 2, `play` flavor only.

---

## Technical detail

### 1. Dependencies (version catalog)

```toml
[versions]
media3 = "1.11.1"          # stable 2026-09-10; check Google Maven before bumping
playServicesCast = "22.3.1" # what media3-cast 1.11.1 depends on (play flavor only)

[libraries]
media3-exoplayer          = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
media3-session            = { module = "androidx.media3:media3-session", version.ref = "media3" }
media3-datasource-okhttp  = { module = "androidx.media3:media3-datasource-okhttp", version.ref = "media3" }
media3-ui-compose         = { module = "androidx.media3:media3-ui-compose", version.ref = "media3" }
media3-ui-compose-m3      = { module = "androidx.media3:media3-ui-compose-material3", version.ref = "media3" }
media3-common-ktx         = { module = "androidx.media3:media3-common-ktx", version.ref = "media3" }
media3-exoplayer-hls      = { module = "androidx.media3:media3-exoplayer-hls", version.ref = "media3" } # optional
media3-cast               = { module = "androidx.media3:media3-cast", version.ref = "media3" }          # playImplementation only
# kotlinx-coroutines-guava is needed for ListenableFuture.await() / CoroutineScope.future {}
```

Notes:
- `media3-session` 1.11.1 pulls in `androidx.lifecycle:lifecycle-service`, because services are
  `LifecycleService`s since 1.10. This means `lifecycleScope` is available inside `PlaybackService`.
- Much of the API used below is `@UnstableApi`. Opt in module-wide with
  `-opt-in=androidx.media3.common.util.UnstableApi` in the `:playback` module. Expect minor API
  churn on each Media3 minor release.

### 2. Manifest

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<!-- WAKE_LOCK and ACCESS_NETWORK_STATE are merged from media3-exoplayer's manifest. -->
<!-- POST_NOTIFICATIONS is NOT needed for the media notification (media sessions are exempt). -->

<application ...>
  <!-- Android Auto -->
  <meta-data android:name="com.google.android.gms.car.application"
             android:resource="@xml/automotive_app_desc" />  <!-- <automotiveApp><uses name="media"/></automotiveApp> -->

  <service
      android:name=".playback.PlaybackService"
      android:foregroundServiceType="mediaPlayback"
      android:exported="true">
    <intent-filter>
      <action android:name="androidx.media3.session.MediaLibraryService" />
      <action android:name="android.media.browse.MediaBrowserService" />
      <action android:name="android.media.action.MEDIA_PLAY_FROM_SEARCH" />
    </intent-filter>
  </service>

  <!-- Playback resumption from headset/BT play button and System UI -->
  <receiver android:name="androidx.media3.session.MediaButtonReceiver" android:exported="true">
    <intent-filter><action android:name="android.intent.action.MEDIA_BUTTON" /></intent-filter>
  </receiver>

  <!-- Video Now Playing activity (or the single Compose activity) -->
  <activity android:name=".MainActivity"
      android:supportsPictureInPicture="true"
      android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation" ... />

  <!-- Artwork provider serving cached covers as content:// for System UI, Auto, AAOS -->
  <provider android:name=".artwork.ArtworkProvider" android:authorities="${applicationId}.artwork"
            android:exported="true" />  <!-- read-only; restrict paths -->
</application>
```

The session AAR also merges `androidx.media3.session.BluetoothValidationActivity`. It is protected
by `BLUETOOTH_PRIVILEGED` and works around AVRCP service discovery on API 36/37. Leave it in place.

### 3. Module and class layout (`:playback` module)

```
playback/
  PlaybackService.kt            MediaLibraryService; owns session, player, sub-controllers
  PlayerFactory.kt              builds ExoPlayer (+ optional CastPlayer wrapper)
  media/EpisodeMediaItems.kt    Episode -> MediaItem mapping (stable URI, metadata, extras)
  media/EpisodeResolver.kt      ResolvingDataSource.Resolver (local / remote / YouTube)
  media/MediaSourceFactory.kt   DataSource stack + per-item extractor flags
  queue/QueueProjector.kt       DB queue snapshot -> player playlist diff
  session/SessionCallback.kt    MediaLibrarySession.Callback (connect, add/set items, browse, resumption, custom cmds)
  session/Buttons.kt            media button preferences
  state/PositionTracker.kt      persist positions, mark played
  state/EffectiveSettings.kt    per podcast/group speed, skip-silence, boost, intro/outro
  features/SleepTimer.kt
  features/Chapters.kt          chapter merge + current chapter
  features/VideoPolicy.kt       disable video track when no surface; PiP hints
  audio/BoostLimiterProcessor.kt
  PlaybackServiceState.kt       @Singleton StateFlows for UI (timer, chapter, effective speed source)
ui/player/
  PlayerConnection.kt           app-scoped MediaController holder
```

### 4. Player construction

```kotlin
class PlayerFactory @Inject constructor(
  @ApplicationContext private val ctx: Context,
  private val okHttp: OkHttpClient,
  private val cache: SimpleCache,                  // @Singleton, one per process
  private val resolver: EpisodeResolver,
  private val boost: BoostLimiterProcessor,
  private val prefs: PlaybackPrefs,
) {
  val silenceSkipper = SilenceSkippingAudioProcessor(
    /* minimumSilenceDurationUs = */ 250_000,     // default 100_000; podcasts sound more natural ~200–300 ms
    /* silenceRetentionRatio = */ 0.2f,
    /* maxSilenceToKeepDurationUs = */ 400_000,
    /* minVolumeToKeepPercentageWhenMuting = */ 10,
    /* silenceThresholdLevel = */ 1024,
  )

  fun create(): ExoPlayer {
    val renderers = object : DefaultRenderersFactory(ctx) {
      override fun buildAudioSink(c: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean) =
        DefaultAudioSink.Builder(c)
          .setEnableFloatOutput(enableFloatOutput)
          // keep Sonic (software) speed control: consistent across devices, pitch-preserving
          .setEnableAudioOutputPlaybackParameters(false)
          .setAudioProcessorChain(
            DefaultAudioSink.DefaultAudioProcessorChain(arrayOf(boost), silenceSkipper, SonicAudioProcessor()))
          .build()
    }

    val http = OkHttpDataSource.Factory(okHttp)              // shares UA, pool, interceptors
    val cached = CacheDataSource.Factory()
      .setCache(cache)
      .setUpstreamDataSourceFactory(http)
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    // DefaultDataSource dispatches file:// and content:// to File/ContentDataSource; http(s) goes to `cached`.
    val scheme = DefaultDataSource.Factory(ctx, cached)
    val resolving = ResolvingDataSource.Factory(scheme, resolver)

    val extractors = DefaultExtractorsFactory()
      .setConstantBitrateSeekingEnabled(true)                // seekable MP3 streams lacking Xing/VBRI
      .setDisableArtworkMetadata(true)                       // 1.11+: drop embedded APIC/covr (OOM risk), keep CHAP etc.

    return ExoPlayer.Builder(ctx, renderers)
      .setMediaSourceFactory(DefaultMediaSourceFactory(resolving, extractors))
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(C.USAGE_MEDIA)
          .setContentType(if (prefs.pauseForNavPrompts) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
          .build(),
        /* handleAudioFocus = */ true)
      .setHandleAudioBecomingNoisy(true)
      .setWakeMode(C.WAKE_MODE_NETWORK)                      // switched per item, see below
      .setSeekBackIncrementMs(prefs.skipBackMs)
      .setSeekForwardIncrementMs(prefs.skipForwardMs)
      .setLoadControl(
        DefaultLoadControl.Builder()
          // audio-heavy app: buffer up to 10 min ahead (128 kbps ≈ 1 MB/min); video falls back to byte caps
          .setBufferDurationsMs(/* min */ 60_000, /* max */ 600_000, /* forPlayback */ 1_500, /* afterRebuffer */ 3_000)
          .setBackBuffer(/* ms */ 60_000, /* retainBackBufferFromKeyframe */ true)  // instant "back 10 s"
          .build())
      .setName("neutrodyne")
      .build()
  }
}
```

Notes:
- The DataSpec key set by `ProgressiveMediaSource` comes from `MediaItem.localConfiguration.customCacheKey`
  (verified in `ProgressiveMediaPeriod`). Because `CacheDataSource` keys on it, the cache entry stays
  stable while the resolved URL changes, as with YouTube.
- `DefaultLoadControl` caps the audio buffer at `DEFAULT_AUDIO_BUFFER_SIZE = 200 × 64 KiB ≈ 12.8 MB`
  unless `setTargetBufferBytes` or `setPrioritizeTimeOverSizeThresholds` is used. So a 10-min
  duration target is effectively byte-capped for high-bitrate audio, which is acceptable. Do not
  copy AntennaPod's 1 h / 3 h durations with time prioritised; 1.11.0 only *mitigated* the OOM risk.
- `SeekParameters.DEFAULT` is already `EXACT`.
- Wake mode: call `exo.setWakeMode(if (item.isLocal) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)` in
  `onMediaItemTransition`. NETWORK also holds a Wi-Fi lock, which only matters for screen-off
  streaming on Wi-Fi.
- Optional battery saver: `TrackSelectionParameters.AudioOffloadPreferences` with
  `AUDIO_OFFLOAD_MODE_ENABLED` only when speed = 1.0, skip silence is off and boost is 0 dB. In
  offload mode the processors are bypassed. Leave it off in v1.

### 5. `MediaItem` shape for an episode

```kotlin
fun Episode.toMediaItem(podcast: Podcast, artwork: ArtworkUris, state: EpisodeState): MediaItem =
  MediaItem.Builder()
    .setMediaId("episode:$id")                                   // stable; used by queue diff & Auto
    .setUri("neutrodyne://episode/$id")                         // resolved at open() time
    .setMimeType(enclosureMimeOrNull())                         // hint only; null -> sniffing
    .setCustomCacheKey("ep:$id:${enclosureFingerprint()}")      // url+length (+guid) hash
    .setRequestMetadata(MediaItem.RequestMetadata.Builder()
        .setMediaUri("neutrodyne://episode/$id".toUri()).build()) // lets external controllers re-request
    .setMediaMetadata(MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(podcast.title)                               // many BT head units only show title/artist
        .setAlbumTitle(podcast.title)
        .setDisplayTitle(title)
        .setDescription(summaryPlain)
        .setArtworkUri(artwork.contentUriFor(this))             // content://<app>.artwork/... (Auto/AAOS/System UI)
        .setDurationMs(durationMs.takeIf { it > 0 })
        .setMediaType(if (isVideo) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
        .setIsPlayable(true).setIsBrowsable(false)
        .setExtras(bundleOf(
          MediaConstants.EXTRAS_KEY_COMPLETION_STATUS to state.completionStatus(),
          MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE to state.fraction(),
          MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS to state.downloadStatus(),
        ))
        .build())
    .build()
```

Rules:
- **Never put mutable state in `LocalConfiguration`** (uri, mimeType, customCacheKey, tag).
  Otherwise a metadata refresh via `replaceMediaItem` re-prepares the item. Mutable state goes in
  `MediaMetadata`.
- `customCacheKey` must change if the enclosure URL or length changes, for example after a
  re-upload, so that the cache never mixes bytes from two different files.
- YouTube items use `neutrodyne://yt/{videoId}?mode=audio` and the same pattern.

### 6. `EpisodeResolver` (ResolvingDataSource.Resolver)

```kotlin
class EpisodeResolver @Inject constructor(
  private val downloads: DownloadIndex,          // episodeId -> local Uri? (sync, in-memory mirror of DB)
  private val episodes: EpisodeLookup,           // episodeId -> enclosure url, auth headers
  private val youtube: YouTubeStreamResolver,    // videoId -> (url, expiresAtMs), blocking OK
) : ResolvingDataSource.Resolver {

  // Pin remote/local choice per item for the life of its playback (see DAI pitfall).
  private val pinned = ConcurrentHashMap<String, Pinned>()

  override fun resolveDataSpec(spec: DataSpec): DataSpec {
    val uri = spec.uri
    if (uri.scheme != "neutrodyne") return spec
    return when (uri.host) {
      "episode" -> {
        val id = uri.lastPathSegment!!.toLong()
        val p = pinned.getOrPut("e$id") {
          downloads.localUriOrNull(id)?.let { Pinned.Local(it) }
            ?: Pinned.Remote(episodes.enclosureUrl(id).toUri(), episodes.authHeaders(id))
        }
        when (p) {
          is Pinned.Local -> spec.buildUpon().setUri(p.uri).build()
          is Pinned.Remote -> spec.buildUpon().setUri(p.uri).setHttpRequestHeaders(p.headers).build()
        }
      }
      "yt" -> {
        val vid = uri.lastPathSegment!!
        val r = youtube.cachedOrResolve(vid, minValidityMs = 5 * 60_000)  // re-resolve near expiry
        spec.buildUpon().setUri(r.url).build()
      }
      else -> throw IOException("Unknown neutrodyne URI $uri")
    }
  }

  fun unpin(mediaId: String) { /* called by service on item end / error recovery */ }
}
```

Supporting pieces:
- **YouTube 403 self-heal.** Wrap the HTTP factory in a tiny `DataSource` that catches
  `HttpDataSource.InvalidResponseCodeException` with code 403 or 410 for googlevideo hosts, calls
  `youtube.invalidate(videoId)`, then rethrows. `DefaultLoadErrorHandlingPolicy` retries
  `InvalidResponseCodeException` loads with a 0 / 1 / 2 s backoff (minimum 3 retries for
  progressive), and the retry re-enters `resolveDataSpec`, which now resolves a fresh URL. Because
  the bytes of a given itag are identical across URLs, continuing mid-file is safe for YouTube.
- **Do not re-resolve RSS enclosures mid-item** (pinning above). Dynamic-ad-insertion hosts can
  return different bytes per request.
- `resolveDataSpec` runs on ExoPlayer's loader thread, so blocking DB and network calls are allowed
  ("This method is allowed to block"). Keep `downloads.localUriOrNull` in memory, because it is
  called for every connection.
- Mark items as local or remote in the projection so the service can set the wake mode and choose
  extractor flags. A downloaded MP3 benefits from `Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING`. If
  needed, a tiny `MediaSource.Factory` can delegate to two `DefaultMediaSourceFactory` instances
  configured with different `ExtractorsFactory` flags based on a `RequestMetadata` extra.

### 7. Service skeleton

```kotlin
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {
  @Inject lateinit var playerFactory: PlayerFactory
  @Inject lateinit var callbackFactory: SessionCallback.Factory
  @Inject lateinit var projector: QueueProjector
  @Inject lateinit var positions: PositionTracker
  @Inject lateinit var settings: EffectiveSettingsApplier
  @Inject lateinit var sleepTimer: SleepTimer
  @Inject lateinit var buttons: Buttons
  private var session: MediaLibrarySession? = null

  override fun onCreate() {
    super.onCreate()
    val exo = playerFactory.create()
    val player: Player = exo            // play flavor: CastPlayer.Builder(this).setLocalPlayer(exo).build()
    session = MediaLibrarySession.Builder(this, player, callbackFactory.create(exo))
      .setId("neutrodyne")
      .setSessionActivity(nowPlayingPendingIntent())          // back-stacked via TaskStackBuilder
      .setBitmapLoader(CoilBitmapLoader(this))               // share UI image cache; see pitfalls
      .setMediaButtonPreferences(buttons.current())
      .setCommandButtonsForMediaItems(buttons.browseActions()) // Auto/AAOS: download, add to queue, mark played
      .build()
    setMediaNotificationProvider(
      DefaultMediaNotificationProvider.Builder(this)
        .setChannelId("playback").setChannelName(R.string.channel_playback).build()
        .also { it.setSmallIcon(R.drawable.ic_stat_neutrodyne) })

    // Everything below runs on the main looper: 1.11 enforces MediaSession threading strictly.
    lifecycleScope.launch { projector.run(exo) }             // DB -> playlist
    positions.attach(exo, lifecycleScope)                    // playlist -> DB
    settings.attach(exo, lifecycleScope)                     // speed/skip-silence/boost per item
    sleepTimer.attach(exo, lifecycleScope)
    lifecycleScope.launch { buttons.updates().collect { session?.setMediaButtonPreferences(it) } }
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

  // Default onTaskRemoved: keeps running if playing, otherwise pauseAllPlayersAndStopSelf().
  // That matches podcast-app expectations; no override needed.

  override fun onDestroy() {
    positions.flushBlocking()                                 // last position write
    session?.run { player.release(); release() }
    session = null
    super.onDestroy()
  }
}
```

Service and foreground-service facts that matter (verified from the 1.11.1 source):
- Media3 promotes the service to foreground while any session has `playWhenReady && (READY || BUFFERING)`.
- After a pause, stop, error or end, it *stays* in the foreground for
  `DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS = 600_000` (10 min, configurable lower via
  `setForegroundServiceTimeoutMs`), then demotes and keeps the notification.
- `isPlaybackOngoing()` reports this state. `pauseAllPlayersAndStopSelf()` disables the timeout and
  stops.
- If a controller asks to play while the app is in the background and the foreground-service start
  is refused (Android 12+), `MediaSessionService.Listener.onForegroundServiceStartNotAllowedException()`
  is called. Implement it as the session demo does: post a regular "Tap to resume" notification
  whose `PendingIntent` starts playback. Notification taps are an allowed foreground-service-start
  and while-in-use source.

### 8. Session callback

```kotlin
class SessionCallback(
  private val exo: ExoPlayer, private val repo: QueueRepository, private val lib: LibraryTree,
  private val items: EpisodeMediaItems, private val resumption: ResumptionStore, private val cmds: CustomCommands,
) : MediaLibraryService.MediaLibrarySession.Callback {

  override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo)
      : ListenableFuture<MediaSession.ConnectionResult> {
    val b = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller) // trusted: full; untrusted: read-only (1.11 default)
    return when {
      session.isMediaNotificationController(controller) -> immediateFuture(
        b.setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS)            // compact slots show back/forward seek instead
            .remove(Player.COMMAND_SEEK_TO_NEXT).build())
         .setAvailableSessionCommands(cmds.all())
         .setMediaButtonPreferences(buttons.current()).build())
      controller.isTrusted || session.isAutoCompanionController(controller) -> immediateFuture(
        b.setAvailableSessionCommands(cmds.all()).build())     // own UI, System UI, Auto, Wear companion
      else -> immediateFuture(b.build())                       // third-party apps: read-only
    }
  }

  // External controllers (Auto, Assistant, our own "play episode" if routed via controller) send items
  // without a usable URI: resolve by mediaId, and write the DB queue FIRST.
  override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
      mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long)
      : ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
    val ids = mediaItems.map { EpisodeId.parse(it.mediaId) ?: searchToEpisode(it.requestMetadata.searchQuery) }
    val snapshot = repo.playNow(ids.filterNotNull(), context = PlayContext.External(controller.packageName))
    items.windowFor(snapshot)  // MediaItemsWithStartPosition incl. saved position of first item
  }

  override fun onAddMediaItems(...) = /* repo.appendToUpNext(ids); return resolved items */

  override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo,
      isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
    val last = resumption.lastSession() ?: throw UnsupportedOperationException()
    if (!isForPlayback) {
      // boot time: ONE item, local artwork (no network at boot), completion extras
      MediaSession.MediaItemsWithStartPosition(listOf(items.forResumptionCard(last)), 0, last.positionMs)
    } else {
      items.windowFor(repo.snapshot()).also { applySpeedEtcFor(it) }   // speed etc. can be set here
    }
  }

  override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
      customCommand: SessionCommand, args: Bundle) = cmds.handle(customCommand, args)

  override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo,
      intent: Intent): Boolean {
    // Optional mapping: headset/steering-wheel NEXT/PREV -> seekForward/seekBack (user setting).
    val ev = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
    if (prefs.hardwareNextIsSkip && ev.action == KeyEvent.ACTION_DOWN) when (ev.keyCode) {
      KeyEvent.KEYCODE_MEDIA_NEXT -> { session.player.seekForward(); return true }
      KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { session.player.seekBack(); return true }
    }
    return false
  }

  // Library (Auto/AAOS/AVRCP browsing): root -> [Up next, Groups, Downloads, Podcasts] (<= 4 tabs;
  // honour MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT from LibraryParams.extras).
  override fun onGetLibraryRoot(...) = immediateFuture(LibraryResult.ofItem(lib.root(), params))
  override fun onGetChildren(...) = scope.future { LibraryResult.ofItemList(lib.children(parentId, page, pageSize), params) }
  override fun onSearch(...) / onGetSearchResult(...)       // Assistant + Auto search over podcasts/episodes
}
```

Custom commands, which work the same from the UI, the notification and Auto:

| action string | args | effect |
|---|---|---|
| `nd.SLEEP_SET` | `mode` (minutes / end_of_episode / end_of_chapter / off), `minutes` | start or cancel the sleep timer |
| `nd.SLEEP_EXTEND` | `minutes` | add time (also used by "shake to extend") |
| `nd.SPEED_CYCLE` | none | step through the user's speed presets (notification button) |
| `nd.SPEED_SET_SCOPE` | `speed`, `scope` (episode / podcast / group / global) | set speed and persist it at the given scope |
| `nd.SKIP_SILENCE` | `enabled`, `scope` | same pattern |
| `nd.BOOST` | `db`, `scope` | volume boost |
| `nd.CHAPTER_NEXT` / `nd.CHAPTER_PREV` | none | seek to chapter boundary |
| `nd.PLAY_CONTEXT` | `contextType`, `contextId`, `startEpisodeId?`, `order` | "Play group feed" and similar |

Speed itself goes through the standard `Player.setPlaybackSpeed` from the controller. Skip silence,
boost and the timer are not part of the `Player` interface, which is why they need custom commands.

### 9. Notification and media button preferences

```kotlin
class Buttons @Inject constructor(@ApplicationContext val c: Context, val prefs: PlaybackPrefs, val state: PlaybackServiceState) {
  fun current(): List<CommandButton> = listOf(
    CommandButton.Builder(backIcon(prefs.skipBackMs))            // ICON_SKIP_BACK_5/10/15/30 or ICON_SKIP_BACK
      .setDisplayName(c.getString(R.string.skip_back_n, prefs.skipBackMs / 1000))
      .setPlayerCommand(Player.COMMAND_SEEK_BACK)                // uses exo seekBackIncrement
      .setSlots(CommandButton.SLOT_BACK).build(),
    CommandButton.Builder(fwdIcon(prefs.skipForwardMs))
      .setDisplayName(c.getString(R.string.skip_fwd_n, prefs.skipForwardMs / 1000))
      .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
      .setSlots(CommandButton.SLOT_FORWARD).build(),
    CommandButton.Builder(speedIcon(state.speed.value))           // ICON_PLAYBACK_SPEED_1_0/1_2/1_5/1_8/2_0...
      .setDisplayName(c.getString(R.string.speed))
      .setSessionCommand(SessionCommand("nd.SPEED_CYCLE", Bundle.EMPTY))
      .setSlots(CommandButton.SLOT_OVERFLOW).build(),
    CommandButton.Builder(CommandButton.ICON_NEXT)
      .setDisplayName(c.getString(R.string.next_episode))
      .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
      .setSlots(CommandButton.SLOT_OVERFLOW).build(),
  )
}
```

How System UI lays these out on API 33+: slot 1 is play/pause/spinner, slot 2 is `SLOT_BACK`,
slot 3 is `SLOT_FORWARD`, and slots 4–5 hold overflow buttons in insertion order. The compact
layout, used on the lock screen, by Wear and partly by Auto, shows only the first three. This is
why seek back and forward must occupy slots 2 and 3.

Built-in icons cover skip intervals of 5, 10, 15 and 30 s, and speeds 0.5, 0.8, 1.0, 1.2, 1.5, 1.8
and 2.0. For other values use `ICON_SKIP_BACK` or `ICON_PLAYBACK_SPEED` with
`setCustomIconResId(R.drawable.…)` as the fallback icon.

Changing `prefs.skipBackMs` must call both `exo.setSeekBackIncrementMs(...)` (runtime setter since
1.9) and `session.setMediaButtonPreferences(...)`.

### 10. Queue projection (DB → player)

Data shapes (Room). The database research area owns the final schema; these are the fields
playback needs.

```kotlin
@Entity data class QueueEntry(          // explicit "Up next"
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val episodeId: Long, val ordinal: Double,           // fractional ordinal: cheap drag-reorder
  val addedAt: Long)

@Entity data class PlaySession(         // singleton row (id = 0)
  @PrimaryKey val id: Int = 0,
  val currentEpisodeId: Long?, val currentPositionMs: Long, val updatedAt: Long,
  val contextType: ContextType?,        // GROUP, PODCAST, DOWNLOADS, NEW_EPISODES, EXTERNAL, null
  val contextId: Long?, val contextOrder: Order, // OLDEST_FIRST / NEWEST_FIRST
  val contextAnchorEpisodeId: Long?)    // where in the context feed we are

@Entity data class EpisodePlayState(
  @PrimaryKey val episodeId: Long,
  val positionMs: Long, val durationMs: Long?,       // durationMs written back from player once known
  val playedAt: Long?, val playCount: Int, val lastPlayedAt: Long?)

@Entity data class PlaybackOverrides(   // one table, scope-keyed
  @PrimaryKey val scopeKey: String,     // "episode:12", "podcast:3", "group:7", "global"
  val speed: Float?, val skipSilence: Boolean?, val boostDb: Int?,
  val introSkipMs: Long?, val outroSkipMs: Long?)

@Entity data class ChapterRow(
  val episodeId: Long, val startMs: Long, val endMs: Long?, val title: String?,
  val imageUrl: String?, val linkUrl: String?, val hidden: Boolean,
  val source: ChapterSource)            // PODCASTING20_JSON, PSC, ID3, MP4, YOUTUBE_DESC
```

The virtual queue is `[current] ++ upNext(ordered) ++ contextTail(query, excluding played and
upNext)`. The projector materialises a window of `current + upNext + K` context items:

```kotlin
class QueueProjector @Inject constructor(private val repo: QueueRepository, private val items: EpisodeMediaItems) {
  suspend fun run(player: ExoPlayer) {
    repo.virtualQueue(windowContext = K)            // Flow<List<EpisodeWithPodcast>>, distinctUntilChanged
      .conflate()
      .collect { desired -> withContext(Dispatchers.Main.immediate) { applyDiff(player, desired) } }
  }

  private fun applyDiff(player: Player, desired: List<EpisodeWithPodcast>) {
    val want = desired.map { "episode:${it.id}" }
    val have = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
    if (want == have) return
    val cur = player.currentMediaItem?.mediaId
    if (cur != null && want.firstOrNull() == cur) {
      // never touch index 0 (playing). Edit only the tail.
      // Simple, correct strategy: replace everything after current with desired tail.
      // ExoPlayer keeps playback of the current item; next-item pre-buffer is discarded only if it changed.
      val curIdx = player.currentMediaItemIndex
      if (curIdx > 0) player.removeMediaItems(0, curIdx)        // history isn't kept in the player
      val tailHave = have.drop(curIdx + 1); val tailWant = want.drop(1)
      if (tailHave != tailWant)
        player.replaceMediaItems(1, Int.MAX_VALUE, desired.drop(1).map(items::toMediaItem))
    } else {
      // the "now playing" item changed via the DB (user tapped play on another episode)
      player.setMediaItems(desired.map(items::toMediaItem), 0, repo.savedPositionOf(desired.first()))
      player.prepare()
    }
  }
}
```

Refinements:
- Use LCS-based `moveMediaItem` so a reorder of the next item does not drop its pre-buffer.
- When only metadata differs (same IDs), call `replaceMediaItem` for changed metadata such as
  title, artwork or download badge. This is seamless because `LocalConfiguration` is unchanged.

Player → DB, in `PositionTracker` / `QueueAdvanceListener`:
- `onMediaItemTransition(item, reason)`:
  - Reason `AUTO`: mark the previous item played, remove it from `queue_entry`, set
    `PlaySession.currentEpisodeId` and advance the context anchor.
  - Reason `SEEK` (user pressed "next episode", or picked an item from the Auto queue): set
    current, and mark the old item played only if it was within the "almost finished" threshold.
  - After either, apply the saved position (Option G1) and the effective settings (§11).
- The resulting DB emission produces an equal list and therefore a no-op diff, so the loop is safe.
  Guard against races with a monotonically increasing `generation` in `PlaySession` and by doing all
  diffing on the main thread.

"Play group feed" (requirement 2) means `repo.playContext(GROUP, groupId, order, startEpisodeId)`:
1. Set `PlaySession.context`.
2. Clear or keep "Up next", depending on the open question below.
3. Set current = `startEpisodeId`, or the first unplayed episode in that order.
4. Send `controller.play()`.

### 11. Positions, played state and per-item settings

```kotlin
class PositionTracker @Inject constructor(private val dao: PlayStateDao) : Player.Listener {
  private lateinit var player: Player
  fun attach(p: Player, scope: CoroutineScope) {
    player = p; p.addListener(this)
    scope.launch { while (isActive) { delay(10_000); if (player.isPlaying) save(player.currentMediaItem, player.currentPosition) } }
  }
  override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) save(player.currentMediaItem, player.currentPosition) }
  override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
    // old.mediaItem + old.positionMs = the final position of the item we just left (correct even on AUTO)
    if (old.mediaItem?.mediaId != new.mediaItem?.mediaId) save(old.mediaItem, old.positionMs)
    else if (reason == Player.DISCONTINUITY_REASON_SEEK) save(new.mediaItem, new.positionMs)
  }
  override fun onPlayWhenReadyChanged(pwr: Boolean, reason: Int) {
    if (!pwr && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) markPlayed(player.currentMediaItem) // sleep "end of episode"
  }
  override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) markPlayed(player.currentMediaItem) }
  override fun onTimelineChanged(t: Timeline, reason: Int) { /* write back real durationMs when known */ }
  fun flushBlocking() = runBlocking { save(player.currentMediaItem, player.currentPosition) }
}
```

Effective settings are applied in `onMediaItemTransition`, and for the first item before `prepare()`:

```kotlin
val s = overrides.resolve(episodeId, podcastId, playSession.contextGroupIdOrNull)  // episode > podcast > group > global
exo.setPlaybackSpeed(s.speed)                 // Sonic, pitch preserved
exo.skipSilenceEnabled = s.skipSilence
boost.setGainDb(s.boostDb)                    // volatile field read on the playback thread
if (s.introSkipMs > 0 && exo.currentPosition < s.introSkipMs) exo.seekTo(s.introSkipMs)
if (s.outroSkipMs > 0) exo.createMessage { _, _ -> exo.seekToNextMediaItem() }
    .setPosition(exo.currentMediaItemIndex, durationMs - s.outroSkipMs).setDeleteAfterDelivery(true)
    .setLooper(Looper.getMainLooper()).send()
playbackState.effective.value = s             // for UI: "1.5× (from group 'news')"
```

Extras:
- **Smart resume.** If the item was paused for more than 5 min, `seekBack(3–10 s)` before resuming.
  Make this a setting.
- **Speed UI.** Allow 0.5–3.0× in 0.05 steps. Sonic supports far more, but intelligibility drops
  above 3×.

### 12. Sleep timer

The timer lives in the service as `SleepTimer`, exposing `StateFlow<SleepTimerState>` and
controlled by the `nd.SLEEP_*` commands.

| Mode | Implementation |
|---|---|
| N minutes | Deadline in `SystemClock.elapsedRealtime()`; coroutine on the main dispatcher. Count down only while `isPlaying` (pause freezes it); this is an open question for the PO. In the last 10 s, ramp `player.volume` from 1 to 0 in about 20 steps, then `pause()`, then restore volume to 1. |
| End of episode | `exo.pauseAtEndOfMediaItems = true` (runtime setter on `ExoPlayer`). Clear it in `onPlayWhenReadyChanged(false, END_OF_MEDIA_ITEM)`. The player pauses at the *end* of the item without transitioning, so §11 marks the item played explicitly. |
| End of chapter | `exo.createMessage { … pause() }.setPosition(index, chapter.endMs).setDeleteAfterDelivery(true).send()`. Re-arm the message on seeks and chapter changes. |
| Shake to extend (optional) | Register the accelerometer only during the last 60 s of the timer, and after it fires, for up to 5 min; on shake call `nd.SLEEP_EXTEND`. No permission is needed. |

After the timer pauses playback, Media3 keeps the foreground service for 10 min and then demotes
it. Resuming later needs a user action (notification, UI or media key), which complies with
Android 17's background-audio hardening.

### 13. Chapters

Chapter sources, in priority order. Pick the first non-empty source; do not merge sources.
1. **Podcasting 2.0 JSON** (`<podcast:chapters url type="application/json+chapters">`). Fetch when
   the episode starts or when it is downloaded, and cache the result in `ChapterRow`.
   - Required fields: `version` and `chapters[]` with `startTime` (float seconds).
   - Optional fields: `title`, `img`, `url`, `toc` (false means hidden), `endTime`, `location`.
   - Spec: Podcastindex `jsonChapters.md` v1.2.
2. **Podlove Simple Chapters** (`psc:chapters` inline in RSS), from the feed parser.
3. **Embedded chapters from Media3:**
   - ID3 `CHAP` in MP3 is exposed as `androidx.media3.extractor.metadata.id3.ChapterFrame`. In 1.11
     it implements `androidx.media3.extractor.metadata.Chapter` (`getStartTimeMs`, `getEndTimeMs`,
     `isHidden`, `getTitle`).
   - MP4/M4A/M4B Nero and QuickTime chapters, new in 1.11.0, and Matroska chapters arrive as
     `Chapter` entries in the track `Format.metadata`.
   - Read them in `onTracksChanged` by iterating `tracks.groups → getTrackFormat(i).metadata` and
     filtering `is Chapter`.
   - Times are relative to the period. For progressive single-period media the window offset is 0.
   - For downloads, parse at download time with `androidx.media3.inspector.MetadataRetriever`
     (moved from `exoplayer` in 1.11) so chapters are available offline before play.
4. **YouTube description timestamps** (`00:00 Intro` lines), parsed by the YouTube module, or
   NewPipeExtractor stream segments.

`Chapters.current: StateFlow<Chapter?>` is driven by a 1 s ticker while playing. It runs 4× per
second when the chapter UI is visible.

Optional:
- Show the chapter title in the notification and Auto by wrapping the player in
  `ForwardingSimpleBasePlayer` and overriding `getState()` to patch `MediaMetadata.subtitle`.
- `nd.CHAPTER_NEXT` and `nd.CHAPTER_PREV` commands.

### 14. Video podcasts and YouTube video

- Use the same player and the same session. The UI attaches a surface only on the Now Playing
  screen:
  - `media3-ui-compose`: `ContentFrame(player = controller, …)` or `PlayerSurface`.
  - `media3-ui-compose-material3` (1.10/1.11): the `Player` composable plus `ProgressSlider`,
    `PlaybackSpeedControl` and `MiniController`.
  - In-process `MediaController` passes the `Surface` to the session player.
- **Background means audio only.** When the surface is detached and the user is not in PiP, set
  `trackSelectionParameters = …buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()`.
  Re-enable when a surface attaches. The service can watch `onSurfaceSizeChanged(0, 0)`, or the UI
  can do it via the controller (`COMMAND_SET_TRACK_SELECTION_PARAMETERS`).
  - For muxed MP4 enclosures this saves decoding and battery but not bandwidth.
  - For separate YouTube streams (merged sources) it also saves bandwidth.
  - Re-enabling causes a short video catch-up.
- **YouTube defaults to audio-only.** Use an audio-only itag (AAC `m4a`, typically itag 140, or
  Opus `webm` 251) through the `neutrodyne://yt/...` resolver path. A "watch video" toggle switches
  the item to video mode:
  1. muxed progressive (often only 360p), which works with the same resolver path; or
  2. adaptive video-only plus audio-only, which needs Option C3: a custom lazy `MediaSource`
     producing `MergingMediaSource(Progressive(video), Progressive(audio))`.
  Switching mode re-prepares the item at the current position via `replaceMediaItem` with a
  different URI.
- **PiP.** Use `setPictureInPictureParams(Builder().setAutoEnterEnabled(true).setAspectRatio(videoAspect))`
  on API 31+, and `onUserLeaveHint()` → `enterPictureInPictureMode()` on API 26–30.
  `androidx.core:core-pip` exists but is `1.0.0-alpha04`; do not depend on it for v1.
- Media3's Android Auto support is audio-only. Video in cars is a separate parked-app topic; out of
  scope.

### 15. System surfaces checklist

- **Lock screen, quick settings media carousel, Wear OS, Bluetooth.** These come from the session
  automatically.
  - Set `durationMs` in metadata. Without it, the System UI seek bar shows no progress.
  - Artwork comes via the `BitmapLoader`.
- **Android Auto / AAOS.**
  - Browse tree (§8), artwork as `content://` URIs served by our `ArtworkProvider`. Auto requires a
    `content://` or `android.resource://` URI, not `http(s)`.
  - Completion and download extras on items.
  - `setCommandButtonsForMediaItems` for browse actions (download, add to queue, mark played).
    Media3 converts these to AAOS/Auto custom browse actions.
  - For sideloaded or F-Droid installs, the user must enable Auto developer settings → "Unknown
    sources".
  - Play listing for cars requires the car app quality guidelines.
- **Assistant.** Handle `MEDIA_PLAY_FROM_SEARCH` with `onSearch` and `onSetMediaItems` using
  `requestMetadata.searchQuery`.

### 16. Optional Chromecast (play flavor)

- Add `media3-cast` 1.11.1, which pulls `play-services-cast-framework` 22.3.1 and
  `mediarouter` 1.8.1.
- Initialise with `Cast.initialize(CastParams)` (1.11) and use `DefaultCastOptionsProvider` or our
  own `OptionsProvider` referenced from the manifest.
- Build the player as `CastPlayer.Builder(ctx).setLocalPlayer(exo).build()` and pass it to the
  session. The service code is otherwise unchanged, because sub-controllers must use the `Player`
  interface. Only ExoPlayer-specific features (skip silence, boost, `createMessage`) are disabled
  while casting.
- Supply a custom `MediaItemConverter` that maps `neutrodyne://episode/{id}` to the public enclosure
  URL. A local download cannot be cast; use the remote URL instead. Disable YouTube items.
  **UNVERIFIED** whether googlevideo URLs play on a receiver from another device; assume not.
- Use the **Output Switcher** (`CastParams.getShowSystemOutputSwitcherOnCastIconClick`, 1.11).
  Apps targeting API 37 must hold `ACCESS_LOCAL_NETWORK` unless they use a system picker, and the
  Output Switcher is that exemption.

### 17. UI ↔ service boundary

```kotlin
@Singleton
class PlayerConnection @Inject constructor(@ApplicationContext private val ctx: Context) : DefaultLifecycleObserver {
  private val _controller = MutableStateFlow<MediaController?>(null)
  val controller: StateFlow<MediaController?> = _controller
  private var future: ListenableFuture<MediaController>? = null

  init { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }

  override fun onStart(owner: LifecycleOwner) {
    val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
    future = MediaController.Builder(ctx, token)
      .setListener(object : MediaController.Listener { /* onExtrasChanged, onCustomCommand */ })
      .buildAsync().also { f -> f.addListener({ _controller.value = f.get() }, ContextCompat.getMainExecutor(ctx)) }
  }
  override fun onStop(owner: LifecycleOwner) {   // lets the service stop when paused & app backgrounded
    future?.let(MediaController::releaseFuture); future = null; _controller.value = null
  }
}
```

Rules for the boundary:
- **Transport** (play/pause/seek/speed/next): `controller` (`Player` API). Compose state holders
  come from `media3-ui-compose`, such as `rememberPlayPauseButtonState(controller)` and
  `rememberProgressStateWithTickInterval`. Kotlin flows can be built with
  `Player.listenTo(...)` from `media3-common-ktx`.
- **Compound playback actions** (play group, sleep timer, scoped settings):
  `controller.sendCustomCommand(SessionCommand(...), args)`.
- **Data edits** (queue reorder, add to Up next, per-podcast defaults): repositories write the DB,
  and the service observes. These work even when the service is not running.
- **Read-only service state the UI needs** (timer remaining, current chapter, effective setting
  source): an in-process `@Singleton PlaybackServiceState` with `StateFlow`s. Mirror the important
  parts into `MediaSession.setSessionExtras` only if external controllers need them.
- **Starting playback always means `controller.play()` from visible UI.** Never start playback by
  poking the service singleton directly. That guarantees the foreground-service start happens with
  while-in-use capability.

### 18. Threading

- Build the player and session on the main thread, so the application looper is main.
- Since 1.11, `MediaSession` getters throw if called off the application looper, and void methods
  are re-posted onto it.
- All service sub-controllers use `lifecycleScope` (main) and switch to `Dispatchers.IO` only for
  DB and network work, never for player calls.
- `AudioProcessor`s run on the playback thread. Make parameter fields `@Volatile` and apply changes
  at buffer boundaries.

### 19. Test plan specific to playback

- Robolectric and `TestExoPlayerBuilder` (`media3-test-utils`, `media3-test-utils-robolectric`) for
  the `QueueProjector` diff, `PositionTracker` and `SleepTimer` (with `FakeClock`).
- Instrumented tests:
  - Media3 session tests with `MediaController` on an emulator.
  - `adb shell cmd audio set-enable-hardening throw` on an Android 17 image, to catch background
    audio misuse.
  - `adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS <pkg>` together with a reboot test
    of the resumption card.
- Manual matrix:
  - Bluetooth headset (play, next, double-tap), a car head unit (AVRCP) and the Android Auto
    Desktop Head Unit.
  - Wear OS watch paired, lock screen, phone call interruption, navigation prompt (pause vs duck).
  - Unplugging wired headphones, a 7-hour pause on a YouTube item (URL expiry), airplane mode
    mid-stream, and deleting a download while it plays.

---

## Verified versions & facts

| Fact | Value | Source | Checked |
|---|---|---|---|
| Media3 latest stable | **1.11.1** (2026-09-10). Earlier: 1.11.0 (2026-08-05), 1.10.1 (2026-05-12), 1.10.0 (2026-03-25). No newer alpha/beta/RC | https://dl.google.com/android/maven2/androidx/media3/media3-exoplayer/maven-metadata.xml (lastUpdated 2026-09-11); https://github.com/androidx/media/blob/release/RELEASENOTES.md; https://developer.android.com/jetpack/androidx/releases/media3 | 2026-10-04 |
| All used modules exist at 1.11.1: exoplayer, session, ui, ui-compose, ui-compose-material3, common-ktx, datasource-okhttp, datasource-ktor, cast, exoplayer-hls/dash, inspector | 1.11.1 | Google Maven `maven-metadata.xml` per artifact under https://dl.google.com/android/maven2/androidx/media3/ | 2026-10-04 |
| Media3 1.11.1 minSdk 23, compileSdk 36 | | `gradle/libs.versions.toml` in https://github.com/androidx/media/tree/release ; minSdk 23 since 1.9.0 (RELEASENOTES) | 2026-10-04 |
| media3-cast 1.11.1 depends on play-services-cast-framework 22.3.1 and mediarouter 1.8.1; media3-session depends on lifecycle-service 2.8.0 | | https://dl.google.com/android/maven2/androidx/media3/media3-cast/1.11.1/media3-cast-1.11.1.pom ; …/media3-session-1.11.1.pom | 2026-10-04 |
| `MediaSessionService` and `MediaLibraryService` are `LifecycleService`s | since 1.10.0 | RELEASENOTES 1.10.0 "Session" | 2026-10-04 |
| Default `onConnect` gives **untrusted controllers read-only access**. Trusted = own app, system UID, MEDIA_CONTENT_CONTROL, STATUS_BAR_SERVICE, or an enabled notification listener | 1.11.0 | RELEASENOTES 1.11.0; `MediaSession.java` (`isTrusted` Javadoc, `AcceptedResultBuilder`) in androidx/media release branch; https://developer.android.com/reference/androidx/media3/session/MediaSession.Callback | 2026-10-04 |
| `onConnectAsync` added; `onConnect` "candidate to be deprecated" | 1.11.0 | RELEASENOTES 1.11.0 | 2026-10-04 |
| Session methods enforce application-looper threading (getters throw) | 1.11.0 | RELEASENOTES 1.11.0 | 2026-10-04 |
| AVRCP browsing didn't recognise `MediaLibraryService` on API 36/37; fixed | 1.11.0 | RELEASENOTES 1.11.0; session `AndroidManifest.xml` (BluetoothValidationActivity) | 2026-10-04 |
| `onPlaybackResumption(session, controller, isForPlayback)`. `false` at boot = metadata for the System UI card (one item, local artwork, completion extras) | since 1.8.0 | RELEASENOTES 1.8.0; https://developer.android.com/media/media3/session/background-playback ; MediaSession.Callback reference | 2026-10-04 |
| Boot-time "recent root" resumption implemented in `MediaLibrarySessionImpl` only | 1.11.1 source | `libraries/session/.../MediaLibrarySessionImpl.java` (`RECENT_LIBRARY_ROOT_MEDIA_ID`, `getRecentMediaItemAtDeviceBootTime`) | 2026-10-04 |
| Foreground kept for 10 min after pause/stop/end (`DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS = 600_000`, max), then demoted | 1.11.1 source | `MediaSessionService.java`, `MediaNotificationManager.java`; https://developer.android.com/media/media3/session/background-playback | 2026-10-04 |
| Default `onTaskRemoved`: keep running if playing, else `pauseAllPlayersAndStopSelf()` | 1.11.1 source | `MediaSessionService.java` Javadoc | 2026-10-04 |
| Media-session notifications are exempt from `POST_NOTIFICATIONS` | | https://developer.android.com/develop/ui/views/notifications/notification-permission (section "Media sessions") | 2026-10-04 |
| System UI (API 33+) slots: play, SLOT_BACK, SLOT_FORWARD, 2× overflow; compact = first 3 | | https://developer.android.com/media/implement/surfaces/mobile | 2026-10-04 |
| CommandButton icons include ICON_SKIP_BACK/FORWARD_{5,10,15,30}, ICON_PLAYBACK_SPEED_{0_5,0_8,1_0,1_2,1_5,1_8,2_0}; slots CENTRAL/BACK/FORWARD/BACK_SECONDARY/FORWARD_SECONDARY/OVERFLOW | 1.11.1 | `CommandButton.java` in release branch | 2026-10-04 |
| Player-command `CommandButton`s are correctly represented in platform sessions (System UI, Auto) | since 1.9.0 | RELEASENOTES 1.9.0 | 2026-10-04 |
| `ExoPlayer.setSeekBackIncrementMs` / `setSeekForwardIncrementMs` at runtime | since 1.9.0 | RELEASENOTES 1.9.0; `ExoPlayer.java` | 2026-10-04 |
| Wake lock handling on by default (`WAKE_MODE_LOCAL`); `WAKE_MODE_NETWORK` adds a Wi-Fi lock | since 1.9.0 | RELEASENOTES 1.9.0; `ExoPlayer.Builder` Javadoc | 2026-10-04 |
| `handleAudioBecomingNoisy` default false; `skipSilenceEnabled` default false; audio focus not handled by default | 1.11.1 | `ExoPlayer.java` Builder defaults Javadoc | 2026-10-04 |
| With `AUDIO_CONTENT_TYPE_SPEECH`, ExoPlayer's AudioFocusManager pauses instead of ducking | 1.11.1 | `libraries/common/.../audio/AudioFocusManager.java` (`willPauseWhenDucked`) | 2026-10-04 |
| `SilenceSkippingAudioProcessor` defaults: min silence 100 ms, retention 0.2, max kept 2 s, min volume 10 %, threshold 1024; buffer-size bug fixed in 1.11.1 | | `SilenceSkippingAudioProcessor.java`; RELEASENOTES 1.11.1 | 2026-10-04 |
| `DefaultAudioProcessorChain(audioProcessors[], silenceSkipper, sonic)`: custom processors run before silence skipping and speed | | `DefaultAudioSink.java` | 2026-10-04 |
| Audio processing is bypassed in offload/passthrough | | `DefaultAudioSink.java` comment "Audio processing is not supported in offload or passthrough mode" | 2026-10-04 |
| `GainProcessor` gain is in [0, 1] (attenuation only) | | `libraries/common/.../audio/GainProcessor.java` | 2026-10-04 |
| `ResolvingDataSource.Resolver.resolveDataSpec` may block; called for every new connection | | `ResolvingDataSource.java` | 2026-10-04 |
| `ProgressiveMediaPeriod` sets `DataSpec.key = customCacheKey`; `ProgressiveMediaSource.canUpdateMediaItem` compares uri, imageDurationMs and customCacheKey | | `ProgressiveMediaPeriod.java`, `ProgressiveMediaSource.java` | 2026-10-04 |
| `DefaultLoadErrorHandlingPolicy` retries HTTP errors with `min((n-1)*1000, 5000)` ms backoff | | `DefaultLoadErrorHandlingPolicy.java` | 2026-10-04 |
| `DefaultHttpDataSource` cross-protocol redirects default false | | `DefaultHttpDataSource.java` | 2026-10-04 |
| `SimpleCache`: only one instance per directory | | `SimpleCache.java` Javadoc | 2026-10-04 |
| `DefaultLoadControl` audio buffer cap 200 × 64 KiB; default min/max buffer 50 s | | `DefaultLoadControl.java` | 2026-10-04 |
| ID3 `ChapterFrame implements Chapter`; MP4 (Nero/QuickTime) and Matroska chapters exposed as `Chapter` entries | 1.11.0 | `extractor/metadata/Chapter.java`, `id3/ChapterFrame.java`; RELEASENOTES 1.11.0 | 2026-10-04 |
| `FLAG_DISABLE_ARTWORK_METADATA` / `DefaultExtractorsFactory.setDisableArtworkMetadata` | 1.11.0 | RELEASENOTES 1.11.0; `Mp3Extractor.java` | 2026-10-04 |
| MP3 index seeking now prefers Xing/VBRI and defaults to CBR assumption when no metadata | 1.9.0 | RELEASENOTES 1.9.0; `Mp3Extractor.java` flag docs | 2026-10-04 |
| `MetadataRetriever` moved to `androidx.media3.inspector` (old class removed) | 1.11.0 | RELEASENOTES 1.11.0 | 2026-10-04 |
| `CastPlayer.Builder().setLocalPlayer()`; `RemoteCastPlayer`; `Cast.initialize(CastParams)`; Output Switcher option | 1.9.0 / 1.11.0 | RELEASENOTES 1.9.0, 1.11.0; `libraries/cast` sources; `demos/session_service/DemoPlaybackService.kt` | 2026-10-04 |
| `media3-ui-compose-material3`: `Player`, `ProgressSlider`, `PlaybackSpeedToggleButton` (1.10); `MiniController`, `ErrorText` (1.11) | | RELEASENOTES 1.10.0 and 1.11.0 | 2026-10-04 |
| Media3 demo manifest: service actions `MediaLibraryService`, `MediaBrowserService`, `MEDIA_PLAY_FROM_SEARCH`; `MediaButtonReceiver`; `com.google.android.gms.car.application` meta-data | | `demos/session/src/main/AndroidManifest.xml` (release branch) | 2026-10-04 |
| FGS type `mediaPlayback` needs `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, no runtime prerequisites; apps targeting 35+ can't start it from `BOOT_COMPLETED` | Android 14/15 | https://developer.android.com/develop/background-work/services/fgs/service-types ; https://developer.android.com/about/versions/15/behavior-changes-15 | 2026-10-04 |
| Android 12+ background FGS start restriction and exemptions (notification/widget interaction etc.) | | https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start | 2026-10-04 |
| Android 15 (target 35): must be top app or run an FGS to request audio focus, else `AUDIOFOCUS_REQUEST_FAILED` | | https://developer.android.com/about/versions/15/behavior-changes-15 | 2026-10-04 |
| **Android 17 background audio hardening.** All apps: playback/focus/volume need a visible activity or a non-`shortService` FGS. Target 37: the FGS must have **while-in-use** capability. Playback is silently muted otherwise. Media key events, notification and widget clicks grant WIU. Test with `adb shell cmd audio set-enable-hardening …` | Android 17 | https://developer.android.com/about/versions/17/changes/bg-audio ; https://developer.android.com/about/versions/17/behavior-changes-all | 2026-10-04 |
| Android 17 (target 37) `ACCESS_LOCAL_NETWORK` runtime permission; Cast via Output Switcher exempt | | https://developer.android.com/about/versions/17/behavior-changes-17 ; https://developer.android.com/privacy-and-security/local-network-permission | 2026-10-04 |
| Play target API: from 2026-08-31 new apps/updates must target API 36 (Android 16) | | https://developer.android.com/google/play/requirements/target-sdk | 2026-10-04 |
| Android Auto artwork must be `content://` or `android.resource://` URIs | | https://developer.android.com/training/cars/media/create-media-browser/media-artwork | 2026-10-04 |
| Android Auto runs non-Play installs only with developer option "Unknown sources" (media apps) | | https://developer.android.com/training/cars/testing | 2026-10-04 |
| Podcasting 2.0 JSON chapters v1.2: `version`, `chapters[].startTime` required; `toc:false` = hidden | | https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/examples/chapters/jsonChapters.md ; https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/chapters.md | 2026-10-04 |
| Prior art: AntennaPod uses `LoudnessEnhancer` for boost, `SimpleCache` (100 MB LRU) for streaming, CBR seeking, `FLAG_DISABLE_ID3_METADATA` | | https://github.com/AntennaPod/AntennaPod/blob/develop/playback/service/src/main/java/de/danoeh/antennapod/playback/service/internal/ExoPlayerWrapper.java | 2026-10-04 |
| Prior art: NewPipe (dev branch 2026-09-26, still on ExoPlayer 2.19.1) resolves YouTube lazily with expiring `LoadedMediaSource` and a custom `YoutubeHttpDataSource` (`range`/`rn` params, Origin/Referer) | | https://github.com/TeamNewPipe/NewPipe/tree/dev/app/src/main/java/org/schabi/newpipe/player | 2026-10-04 |
| `androidx.core:core-pip` latest is 1.0.0-alpha04 | | https://dl.google.com/android/maven2/androidx/core/core-pip/maven-metadata.xml | 2026-10-04 |

---

## Pitfalls & edge cases

1. **Android 17 silent muting.**
   - Any path that starts audio without a visible activity or a while-in-use foreground service
     plays *silence* with no exception. Examples: a WorkManager job that "auto-plays when the
     download finishes", an alarm-style "wake up to podcast", or resuming after a network outage
     that lasted longer than the 10-min foreground window.
   - Inside the window Media3 keeps the foreground service, so automatic retry is fine. After
     demotion, post a "Tap to resume" notification instead.
   - A "wake-up alarm podcast" feature would need `USAGE_ALARM` plus the exact-alarm exemption.
     Treat it as out of scope.
2. **The 10-min foreground timeout.** Media3 demotes the service 10 min after a pause, end or error.
   Restarting from the background then raises `ForegroundServiceStartNotAllowedException`, which
   Media3 catches and reports through `Listener.onForegroundServiceStartNotAllowedException`.
   Implement that callback.
3. **Leaked controllers keep the service alive.** If the UI's `MediaController` is not released
   (ProcessLifecycle `onStop`), the service cannot be stopped after the user pauses and leaves.
   The notification then lingers after demotion.
4. **Untrusted controllers are read-only since 1.11.**
   - Third-party remotes, automation apps and widgets without notification-listener access can no
     longer control playback by default.
   - Android Auto (`isAutoCompanionController`), System UI and our own app are fine.
   - Decide explicitly whether to grant full commands to all controllers, as before 1.11 (open
     question).
5. **Feedback loops between DB and player.** Player transitions write the DB, and the DB re-emits
   to the projector. Diff on `mediaId` lists, run on the main thread, conflate the flow and never
   touch the currently playing index. Otherwise the result is stutter or an infinite
   re-`setMediaItems`.
6. **External `setMediaItems` (Auto, Assistant) bypassing the DB.** Always route through
   `onSetMediaItems`/`onAddMediaItems` to the repository. Otherwise the next DB emission "reverts"
   what the user picked in the car.
7. **Dynamic ad insertion (DAI) hosts** (Megaphone, Acast, Art19 and others) can serve different
   bytes, ads and lengths per request.
   - Never stitch byte ranges from two responses. Pin the resolved URL per item playback; consider
     resolving redirects once and keying the cache on the final URL plus `Content-Length`.
   - Never switch local↔remote mid-item.
   - A position saved while streaming may not match the downloaded file, which has different ads.
     Accept this, or store `positionSource` (stream/download) and show "position may differ".
8. **MP3 seeking accuracy.**
   - VBR files with a coarse Xing TOC (100 entries) seek approximately. On a 2 h episode the error
     can reach seconds, so chapter jumps and resume positions are "near". This is
     **UNVERIFIED magnitude**; test with real feeds.
   - Since 1.9 Media3 prefers Xing/VBRI over index seeking, even with `FLAG_ENABLE_INDEX_SEEKING`.
   - Use `setConstantBitrateSeekingEnabled(true)` so header-less CBR streams are seekable at all.
9. **Duration lies.** `itunes:duration` is often wrong or missing. Overwrite with player duration
   once the timeline knows it, and use that for "played" thresholds and progress bars.
10. **Speed change at item boundaries.** The new per-podcast speed is applied in
    `onMediaItemTransition`, but audio already pre-processed by Sonic and the audio track buffer
    (about 0.5 s with 1.11's fixed 500 ms PCM buffer) can play briefly at the old speed. Acceptable
    in practice; verify by ear.
11. **`pauseAtEndOfMediaItems` side effects.** The player pauses at the *end* of the item without
    transitioning, so "mark played" must also trigger on `END_OF_MEDIA_ITEM` pauses. Remember to
    reset the flag, or every later episode pauses too.
12. **Deleting a download that is playing.**
    - The open `FileDataSource` keeps reading, but the next seek re-opens the file and fails with
      `FileNotFoundException`.
    - Either block deletion of the current item, or catch the error, unpin, `replaceMediaItem` and
      seek to the saved position, which re-resolves to remote.
    - Also coordinate with auto-delete-after-played: delete only after the transition away.
13. **`SimpleCache` folder rules.**
    - Never create two instances; a second one on the same folder throws.
    - Delete with `SimpleCache.delete(dir, dbProvider)`, never by deleting files.
    - With `cacheDir` the OS may purge files under the index. `filesDir` plus an LRU cap and a
      "Clear streaming cache" setting avoids this.
14. **Artwork.**
    - Network artwork URIs do not work for the Auto browse tree, and System UI loads artwork via the
      session's `BitmapLoader`.
    - Serve covers as `content://` from our provider, backed by the same disk cache as Coil.
    - Large embedded APIC images in MP3s cause OOMs. Use `setDisableArtworkMetadata(true)` (1.11).
    - 1.11.0 fixed blurry double-downscaled notification artwork.
15. **Boot-time resumption has no network.** In `onPlaybackResumption(isForPlayback = false)`,
    return only local data: a cached artwork bitmap or `content://` URI. Also do not hit
    the DB through a not-yet-migrated path.
16. **Headset and car "next" buttons.** By default `KEYCODE_MEDIA_NEXT` jumps to the next *episode*.
    Many podcast listeners expect skip-forward. Make it a setting, implemented in
    `onMediaButtonEvent`. **UNVERIFIED** whether every AVRCP passthrough arrives as a key event
    rather than a transport control; test on real cars.
17. **Audio focus edge cases.**
    - Transient loss (a call) auto-resumes. Permanent loss (another media app) does not.
    - Speech content type means navigation prompts pause the podcast rather than duck it. Some users
      hate this, so make it a setting.
    - Android 15+ refuses focus from the background unless a foreground service is running.
18. **Video track disable/enable** re-selects tracks. For muxed progressive MP4 there may be a short
    stall when returning to the foreground. A muxed file still downloads video bytes even when the
    track is disabled.
19. **YouTube specifics** (coordinate with the YouTube research area).
    - URLs expire (`expire=` query parameter).
    - Googlevideo may throttle plain `Range` requests. NewPipe uses a custom data source adding
      `range`/`rn` query parameters and Origin/Referer headers. **UNVERIFIED** whether this is still
      required in Oct 2026.
    - Resolution can fail (PO-token or region issues). Surface a clear per-item error and let the
      queue continue to the next item.
20. **Cast loses features.** Skip silence, boost, `createMessage`-based intro/outro skip and
    end-of-chapter timers are ExoPlayer-only. While casting, hide them or emulate them with
    position polling. Local downloads cannot be cast.
21. **`@UnstableApi` churn.** Many APIs used here (`MediaButtonReceiver`, `onPlaybackResumption`,
    `CommandButton` slots, `CastPlayer`, `DefaultAudioSink.Builder`) are unstable. Pin the Media3
    version, read release notes on every bump and keep playback code in one module.
22. **Huge group feeds.** Never materialise thousands of `MediaItem`s. The window size K (about 20)
    bounds timeline serialization to System UI, Auto and Wear.
23. **Old installs and process death.** Positions are saved every 10 s plus on events. A kill can
    lose up to 10 s; that is acceptable.

---

## Open questions for the product owner

1. **Queue semantics.** When the user taps "Play" on a group feed, does it (a) replace "Up next",
   (b) play the group *after* the manual Up next items (the Spotify model, as recommended), or
   (c) ask? Are played episodes removed from Up next automatically?
2. **Group feed play order.** Oldest unplayed first (serial shows, fiction) or newest first (news)?
   Should it be a per-group setting? The recommendation is a per-group default with news = newest
   first.
3. **Settings precedence when a podcast is in several groups.** The proposal is: playing from a
   group's feed uses that group's defaults, otherwise the podcast's own setting, otherwise global.
   Does that match expectations?
4. **Sleep timer.** Should it count down only while playing, or by wall-clock time? Are
   "end of chapter" and "shake to extend" wanted in v1? Should there be a fade-out length setting?
5. **Hardware "next" button.** Should it skip forward N seconds or go to the next episode by
   default? What are the default skip intervals (proposal: back 10 s, forward 30 s)?
6. **Mark-as-played threshold** (proposal: within the last 30 s or 3 %). Delete played downloads
   automatically?
7. **YouTube.** Audio-only by default with an opt-in "watch video" mode, or video by default? Is
   video for YouTube in v1 at all? It is the hardest path (Option C3).
8. **Video podcasts.** Continue audio in the background (recommended) or pause when leaving the
   video screen? Is PiP required in v1?
9. **Chromecast in v1?** It needs Google Play services and excludes F-Droid builds. Is F-Droid
   distribution a goal? This also affects Android Auto, which only shows non-Play installs via a
   developer toggle.
10. **Android Auto / AAOS in v1, or later?** The `MediaLibraryService` architecture supports it
    either way, but the browse tree, search and car-quality review are real work.
11. **Third-party controllers.** Restore pre-1.11 behaviour (any app can control playback) or keep
    the 1.11 default (read-only for untrusted apps)?
12. **Volume boost range** (proposal: 0 / +3 / +6 / +10 dB with limiter). Is mono downmix wanted?
13. **Streaming cache size default** (proposal: 500 MB) and whether to pre-cache the start of the
    next queued episode on Wi-Fi.
