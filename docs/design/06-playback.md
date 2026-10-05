# 06 — Playback

> Status: Draft v1, 2026-10-04 · Implements: R4.1, R4.3, R4.7, R4.8, R2.6 (play group), R2.7 (playback defaults), R3.5, R3.8, R5.2, R5.3 / N1, N2, N6, N7 · Milestones: M0, M4, M5, M6, M8, M9, M12–M14 · Honours: D20, D37, D38, D39, D40, D41, D42, D43, D44, D45, D64, D65; PO-16, PO-20 defaults · Owns: `:playback:api` and `:playback:impl` — the Media3 library service and session policy, ExoPlayer configuration, `MediaItem` shape and `EpisodeResolver`, the streaming cache, Up next and play-session semantics with `QueueProjector`, positions and played state, applying effective playback settings, sleep timer, chapters, notification and media buttons, system surfaces, video-as-audio, Android 15–17 playback restrictions and the UI ↔ service boundary

Contents: [Scope](#scope) · [Service architecture](#service-architecture) · [Player configuration](#player-configuration) · [Media items and URI resolution](#media-items-and-uri-resolution) · [Streaming cache](#streaming-cache) · [Queue and play context](#queue-and-play-context) · [Positions and played state](#positions-and-played-state) · [Per-scope playback settings](#per-scope-playback-settings) · [Sleep timer](#sleep-timer) · [Chapters](#chapters) · [Notification and media buttons](#notification-and-media-buttons) · [System surfaces](#system-surfaces) · [Video](#video) · [Background restrictions](#background-restrictions) · [UI boundary](#ui-boundary) · [Settings](#settings) · [Testing](#testing) · [Delivery by milestone](#delivery-by-milestone) · [Open questions](#open-questions) · [Sources](#sources)

---

## Scope

Serves R4.1, R4.3, R4.7, R4.8, N1, N2. Delivered in M4 (core), M5 (features and system surfaces), M6 (local files), M8 (`play` exclusions), M9 (YouTube branch); see [Delivery by milestone](#delivery-by-milestone).

One `MediaLibraryService` ([D37](../PLAN.md#3-key-decisions)) owns one `ExoPlayer`. The database owns *what* plays — Up next (`queue_entry`) and the play context (`play_session`) — and the player holds only a projection window of current + Up next + K = 20 context items ([D38](../PLAN.md#3-key-decisions)). Every playable episode is the URI `neutrodyne://episode/{episodeId}` ([D39](../PLAN.md#3-key-decisions)), resolved on each connection to a local file, a pinned remote enclosure or (foss) a fresh YouTube stream URL. Starting playback is only ever a user-visible or media-key action ([D43](../PLAN.md#3-key-decisions)).

### Responsibilities and boundaries

| This document owns | Owned elsewhere (link, do not restate) |
|---|---|
| `NeutrodynePlaybackService`, session and controller policy, `PlayerConnection` | Merged manifest and permissions — [01 Manifest and permissions](01-foundation.md#manifest-and-permissions) (06 lists its own entries) |
| ExoPlayer, audio chain, load control, extractor flags | Library versions — [01 Toolchain and versions](01-foundation.md#toolchain-and-versions) |
| `MediaItem` shape, `EpisodeResolver`, pins, error recovery | YouTube extraction, `ResolvedUrlCache`, breaker — [04 Stream resolution](04-youtube.md#stream-resolution), [04 Playback integration](04-youtube.md#playback-integration) |
| `SimpleCache` for streaming | Downloads, `LocalMediaIndex` implementation, file deletion — [07 Storage layout](07-downloads.md#storage-layout) |
| Up next and `play_session` semantics, `QueueProjector`, transitions | Which episodes a context contains and where "Play" starts — [05 Playing a group](05-groups-opml-backup.md#playing-a-group); context-tail SQL — [02 Play context](02-data-model.md#play-context) |
| Positions, played state, measured duration, start position | Table DDL and DAO SQL — [02 episode_position](02-data-model.md#episode_position), [02 User-state writes](02-data-model.md#user-state-writes) |
| Applying speed and skip silence; scope writes from the player | Resolution rules and attribution — [05 Effective settings resolution](05-groups-opml-backup.md#effective-settings-resolution) |
| Sleep timer; chapter sources, fetch, priority, current chapter | PSC chapter ingestion — [03 Ingestion and diff](03-feeds-and-discovery.md#ingestion-and-diff); timestamp grammar — [03 Show notes](03-feeds-and-discovery.md#show-notes) |
| Notification buttons, Auto browse tree, Assistant, resumption, Bluetooth | Artwork files, `ArtworkProvider`, monograms — [08 Artwork pipeline](08-ui-ux.md#artwork-pipeline) (06 consumes `ArtworkStore.contentUri`) |
| `PlaybackController`, `PlaybackStateSource`, threading | Player sheet layout, speed/sleep sheets, row overlay — [08 Player sheet](08-ui-ux.md#player-sheet), [08 Live row state](08-ui-ux.md#live-row-state) |

### Modules and public API

| Module | Contents from this document |
|---|---|
| `:playback:api` (JVM, `app.neutrodyne.playback.api`) | `PlaybackController`, `PlaybackStateSource`, `PlaybackMaintenance` and their data types (below) |
| `:core:domain` | `QueueRepository`, `ChapterRepository` (interfaces owned here) |
| `:core:model` | `UpNextItem`, `VirtualQueue`, `QueueItem`, `QueueOrigin`, `PlaySessionInfo`, `PlayContextInfo`, `AddResult`, `EpisodeChapter` |
| `:playback:impl` (Android, `app.neutrodyne.playback.impl`) | Everything else ([package layout](#package-layout)); `@UnstableApi` opt-in module-wide ([01 Convention plugins](01-foundation.md#convention-plugins)) |
| `:core:testing` | `FakePlaybackController`, `FakePlaybackStateSource`, `FakeQueueRepository`, `FakeChapterRepository` (M0) |

```kotlin
// :playback:api — commands. play* functions are UI-only (D43): they return ServiceUnavailable when the
// process is not STARTED. Transport functions are fire-and-forget on the main thread.
interface PlaybackController {
    suspend fun playEpisode(episodeId: Long): PlayResult                        // current = episode, context kept
    suspend fun playEpisodeAt(episodeId: Long, positionMs: Long): PlayResult    // timestamps, chapters of non-current items
    suspend fun playFeed(source: FeedSource, filters: FeedFilters, order: FeedOrder,
                         startEpisodeId: Long? = null): PlayResult
    suspend fun play(): PlayResult                                              // resume current, else start Up next
    fun pause()
    fun seekTo(positionMs: Long)                                                // current item
    fun skipBack()
    fun skipForward()
    fun skipToNext()                                                            // always next episode
    fun skipToPrevious()                                                        // > 3 s: restart, else previous episode
    suspend fun setSpeed(speed: Float, scope: SettingScope): ScopeWriteResult
    suspend fun setSkipSilence(enabled: Boolean, scope: SettingScope): ScopeWriteResult
    fun setSleepTimer(mode: SleepTimerMode)
    fun extendSleepTimer(minutes: Int)
    fun nextChapter()
    fun previousChapter()
    fun grantMeteredStreaming()                                                 // answer to NeedsMeteredConsent
    suspend fun dismiss()                                                       // stop, clear current, drop notification
}
sealed interface PlayResult {
    data object Started : PlayResult
    data object NothingToPlay : PlayResult
    data class NeedsMeteredConsent(val episodeId: Long) : PlayResult
    data object MeteredBlocked : PlayResult
    data object Offline : PlayResult
    data class NotPlayable(val episodeId: Long, val reason: UnplayableReason) : PlayResult
    data object ServiceUnavailable : PlayResult
}
enum class ScopeWriteResult { APPLIED, NO_CONTEXT_GROUP, NOTHING_PLAYING }
sealed interface SleepTimerMode {
    data object Off : SleepTimerMode
    data class Minutes(val minutes: Int) : SleepTimerMode                       // 1..240
    data object EndOfEpisode : SleepTimerMode                                   // EndOfChapter: v1.x (M12)
}
```

```kotlin
// :playback:api — state (implemented by PlaybackStateHub; all flows are main-safe)
interface PlaybackStateSource {
    val nowPlaying: StateFlow<NowPlaying?>
    val positionTicks: Flow<PositionSnapshot>       // 1 Hz while advancing, plus one emission per state change
    val sleepTimer: StateFlow<SleepTimerState>
    val currentChapter: StateFlow<CurrentChapter?>
    val effectivePlayback: StateFlow<EffectivePlayback?>   // 05's :core:model type: value + source per setting
    val events: Flow<PlaybackEvent>                 // one-shot, not replayed
}
data class NowPlaying(
    val episodeId: Long, val podcastId: Long, val title: String, val podcastTitle: String,
    val artwork: ArtworkRef, val sourceType: SourceType, val isVideo: Boolean,
    val phase: PlayerPhase, val isPlaying: Boolean, val playWhenReady: Boolean,
    val position: PositionSnapshot, val hasNext: Boolean, val stream: StreamKind?,  // null until first open
    val issue: PlaybackIssue?, val context: PlayContextInfo?,
)
data class PositionSnapshot(val episodeId: Long, val positionMs: Long, val bufferedMs: Long, val durationMs: Long?,
                            val speed: Float, val advancing: Boolean, val sampledAtElapsedMs: Long) {
    fun positionAt(elapsedMs: Long): Long = if (!advancing) positionMs else
        (positionMs + ((elapsedMs - sampledAtElapsedMs) * speed).toLong()).let { p -> durationMs?.let { minOf(p, it) } ?: p }
}
enum class PlayerPhase { NOT_LOADED, BUFFERING, READY, ENDED, ERROR }
enum class StreamKind { LOCAL, STREAM, YOUTUBE }
enum class PlaybackIssue { NETWORK_LOST, METERED_PAUSE, METERED_BLOCKED, LOCAL_FILE_MISSING,
                           YOUTUBE_RATE_LIMITED, YOUTUBE_BREAKER_OPEN, PLAYER_ERROR }
sealed interface UnplayableReason {
    data class Http(val status: Int) : UnplayableReason          // 404, 410
    data object AuthRequired : UnplayableReason                   // 401, 403 on an RSS enclosure
    data object UnsupportedFormat : UnplayableReason
    data object NoMedia : UnplayableReason                        // no enclosure and no video ID
    data class YouTube(val availability: Availability) : UnplayableReason
    data object YouTubeExtraction : UnplayableReason
    data object NotInThisBuild : UnplayableReason                 // play-flavor YouTube item
}
```

```kotlin
// :playback:api — continued
sealed interface SleepTimerState {
    data object Off : SleepTimerState
    data class Running(val remainingMs: Long, val totalMs: Long, val counting: Boolean, val fading: Boolean) : SleepTimerState
    data class EndOfEpisode(val episodeId: Long) : SleepTimerState
}
data class CurrentChapter(val episodeId: Long, val index: Int, val count: Int, val chapter: EpisodeChapter)
sealed interface PlaybackEvent {
    data class Skipped(val episodeId: Long, val title: String, val reason: UnplayableReason) : PlaybackEvent
    data class MarkedPlayed(val episodeId: Long) : PlaybackEvent
    data object SleepTimerFired : PlaybackEvent
}
interface PlaybackMaintenance {                     // Settings › Playback › Storage
    suspend fun streamingCacheBytes(): Long
    suspend fun clearStreamingCache()
}

// :core:domain
interface QueueRepository {
    fun observeUpNext(): Flow<List<UpNextItem>>
    fun observeSession(): Flow<PlaySessionInfo?>
    fun observeVirtualQueue(k: Int): Flow<VirtualQueue>
    suspend fun addNext(episodeIds: List<Long>): AddResult      // "Play next"
    suspend fun addLast(episodeIds: List<Long>): AddResult      // "Play last"
    suspend fun move(episodeId: Long, toIndex: Int)
    suspend fun remove(episodeIds: List<Long>)
    suspend fun clearUpNext()
    suspend fun clearContext()                                  // "Stop after Up next"
}
interface ChapterRepository {
    fun observe(episodeId: Long): Flow<List<EpisodeChapter>>    // winning source only, hidden chapters excluded
    suspend fun ensureLoaded(episodeId: Long, localFileUri: String? = null)   // 07 calls after COMPLETED
}
```

```kotlin
// :core:model
data class UpNextItem(val entryId: Long, val row: EpisodeRow)
enum class QueueOrigin { CURRENT, UP_NEXT, CONTEXT }
data class QueueItem(val episodeId: Long, val origin: QueueOrigin)
data class VirtualQueue(val generation: Long, val current: QueueItem?, val upNext: List<QueueItem>,
                        val contextTail: List<QueueItem>)
data class PlayContextInfo(val type: ContextType, val id: Long?, val title: String, val order: FeedOrder,
                           val filterFlags: Int, val mediaFilter: MediaFilter)
data class PlaySessionInfo(val currentEpisodeId: Long?, val context: PlayContextInfo?, val generation: Long)
sealed interface AddResult {
    data class Added(val count: Int) : AddResult                // includes moves of already-queued items
    data class Rejected(val reason: RejectReason) : AddResult
}
enum class RejectReason { ALREADY_PLAYING, YOUTUBE_EXTERNAL, UNAVAILABLE }
data class EpisodeChapter(val startMs: Long, val endMs: Long?, val title: String?, val imageUrl: String?,
                          val linkUrl: String?, val source: ChapterSource, val hidden: Boolean)
```

### Package layout

```
playback/impl/src/main/kotlin/app/neutrodyne/playback/impl/
  NeutrodynePlaybackService.kt  PlaybackModule.kt (Hilt bindings, SimpleCache, initializers)
  player/   PlayerFactory  SessionPlayer  NdMediaSourceFactory  NdLoadErrorHandlingPolicy
            BoostLimiterProcessor  EndOfItemPauseArbiter
  media/    MediaItemFactory  EpisodeResolver  EpisodeSourceIndex  GuardedHttpDataSource
            StreamingCache  AdjustableLruCacheEvictor  EnclosureFingerprint  ErrorRecovery
  queue/    QueueRepositoryImpl  SessionWriter  QueueProjector  WindowDiff  PlaybackHistory
  state/    PositionTracker  PlayedRule  StartPositionRule  PlaybackStateHub  ServiceBridge
            EffectivePlaybackApplier  MeteredStreamingGate
  features/ SleepTimer  Chapters  ChapterRepositoryImpl  Podcasting20ChaptersParser  PreResolver
  session/  SessionCallback  CustomCommands  MediaButtons  MediaLibraryTree  ResumptionProvider
            PlaybackChannels  TapToResumeNotifier
  ui/       PlayerConnection  PlaybackControllerImpl
```

Hilt (`PlaybackModule`, `SingletonComponent`): binds `PlaybackController` → `PlaybackControllerImpl`, `PlaybackStateSource` → `PlaybackStateHub`, `PlaybackMaintenance` → `StreamingCache`, `QueueRepository` → `QueueRepositoryImpl`, `ChapterRepository` → `ChapterRepositoryImpl`; provides `SimpleCache` (`@Singleton`); declares `@BindsOptionalOf LocalMediaIndex` and `@BindsOptionalOf DownloadController` (absent until M6: no local files, no missing-file reports); contributes `@IntoSet AppInitializer`s `PlaybackChannels` (order 10) and `PlayerConnection` registration (order 300). Service-side components (`QueueProjector`, `PositionTracker`, `SleepTimer`, `Chapters`, `EffectivePlaybackApplier`, `ErrorRecovery`, `PreResolver`, `MediaButtons`, `SessionCallback`) are unscoped and injected into the service, so each service instance gets fresh ones; `EpisodeResolver`, `EpisodeSourceIndex`, `StreamingCache`, `MeteredStreamingGate`, `PlaybackHistory`, `PlaybackStateHub` and `PlayerConnection` are `@Singleton`.

### New names introduced here

| Name | Kind / location | Purpose |
|---|---|---|
| `PlayResult`, `ScopeWriteResult`, `SleepTimerMode`, `SleepTimerState`, `NowPlaying`, `PositionSnapshot`, `PlayerPhase`, `StreamKind`, `PlaybackIssue`, `UnplayableReason`, `CurrentChapter`, `PlaybackEvent`, `PlaybackMaintenance` | `:playback:api` | Public playback contract |
| `PlaybackController.playEpisodeAt`, `play`, `grantMeteredStreaming`, `dismiss`, `extendSleepTimer`; `PlaybackStateSource.events` | members added to canonical interfaces | Timestamp seeks, resume, metered consent, stop |
| `QueueRepository` members above; `ChapterRepository` | `:core:domain` | Up next edits; chapter reads and download-time loading |
| `UpNextItem`, `QueueOrigin`, `QueueItem`, `VirtualQueue`, `PlayContextInfo`, `PlaySessionInfo`, `AddResult`, `RejectReason`, `EpisodeChapter` | `:core:model` | Queue and chapter models |
| `nd.PLAY_CONTEXT` args `mediaFilter`, `minSortDate`, `startPositionMs`; command `nd.DISMISS` | session commands | Full `FeedFilters`; timestamp start; stop |
| `episode:{id}@{parentId}` | browse-tree playable media ID | Carries the Auto context (`@group:7`, `@upnext`, `@downloads`, `@podcast:3`) |
| `nd.local` | `MediaItem.RequestMetadata.extras` key (Boolean) | Extractor flags for local files |
| `NOTIF_ID_PLAYBACK = 1001`, `NOTIF_ID_TAP_TO_RESUME = 4001` | notification IDs | Media notification; "Tap to resume" on `alerts` |
| Classes in [Package layout](#package-layout) | `:playback:impl` | — |
| `playback.*` keys | [Settings](#settings) | — |
| `FakePlaybackController`, `FakePlaybackStateSource`, `FakeQueueRepository`, `FakeChapterRepository` | `:core:testing` | Feature tests |

---

## Service architecture

Serves R4.7, N2. Delivered in M4 (service, session, notification), M5 (browse tree, resumption). Honours [D37](../PLAN.md#3-key-decisions), [D43](../PLAN.md#3-key-decisions), [PO-16](../PLAN.md#48-further-product-owner-decisions).

```mermaid
flowchart LR
  subgraph UI["UI side, same process"]
    VM["Feature ViewModels"] --> PC["PlaybackControllerImpl"]
    VM --> HUB["PlaybackStateHub"]
    VM --> QR["QueueRepositoryImpl"]
    PC --> CONN["PlayerConnection"]
  end
  subgraph SVC["NeutrodynePlaybackService (main looper)"]
    SESS["MediaLibrarySession + SessionCallback"] --> SP["SessionPlayer"]
    SP --> EXO["ExoPlayer"]
    PROJ["QueueProjector"] --> EXO
    POS["PositionTracker"] --> EXO
    APP["EffectivePlaybackApplier"] --> EXO
    SLP["SleepTimer"] --> EXO
    CH["Chapters"] --> EXO
    ERR["ErrorRecovery"] --> EXO
  end
  CONN -->|"MediaController commands"| SESS
  EXO -->|"loader thread"| RES["EpisodeResolver"]
  RES --> LMI["LocalMediaIndex (07)"]
  RES --> YT["YouTubeStreamResolver (04)"]
  PROJ --> DB[("Room")]
  POS --> DB
  QR --> DB
  SVC -->|"service state"| HUB
  SESS --> SYS["Notification, lock screen, Bluetooth, Auto"]
```

### Creation and destruction

`NeutrodynePlaybackService : MediaLibraryService` is `@AndroidEntryPoint`; Media3 services are `LifecycleService`s since 1.10, so `lifecycleScope` (main) is the service scope. `onCreate`, all on the main looper:

1. `super.onCreate()` (Hilt field injection).
2. `resolver.clearPins()`; `exo = playerFactory.create()` ([Player configuration](#player-configuration)).
3. `sessionPlayer = SessionPlayer(exo, …)` ([Notification and media buttons](#hardware-buttons-and-sessionplayer)).
4. `setMediaNotificationProvider(…)` and `setListener(tapToResumeListener)` ([Notification and media buttons](#notification-and-media-buttons)).
5. `session = MediaLibrarySession.Builder(this, sessionPlayer, sessionCallback).setId("neutrodyne").setSessionActivity(openPlayerPendingIntent).setMediaButtonPreferences(mediaButtons.current()).build()`. The session activity is an explicit `MainActivity` intent with data `neutrodyne://open/player`, `FLAG_IMMUTABLE` (route `ExpandPlayer`, [01 Intent routing](01-foundation.md#intent-routing)).
6. Attach listeners in this order (Media3 notifies in registration order, and the outgoing position must be saved first): `positionTracker`, `queueProjector` (starts observing the database; loads the window **without** `prepare()`), `applier`, `sleepTimer`, `chapters`, `meteredGate`, `errorRecovery`, `preResolver`, `mediaButtons`.
7. `stateHub.attach(ServiceBridge(exo, …))`.

`onGetSession(controllerInfo)` returns `session` for every controller. `onDestroy`: `positionTracker.flush()` (captures the snapshot on main, writes it on `@ApplicationScope` with `NonCancellable`), `stateHub.detach()`, `session.release()`, `exo.release()`, `resolver.clearPins()`, `super.onDestroy()`. `onTaskRemoved` keeps Media3's default (keep running while playing, otherwise `pauseAllPlayersAndStopSelf()`).

### Connection policy

`SessionCallback.onConnectAsync` (Media3 1.11 adds it; `onConnect` is a deprecation candidate):

| Controller | Detection | Player commands | Session commands |
|---|---|---|---|
| Media notification | `session.isMediaNotificationController(c)` | defaults minus `COMMAND_SEEK_TO_PREVIOUS`, `COMMAND_SEEK_TO_NEXT` (compact slots show seek back/forward) | all `nd.*` |
| Own app, System UI, Bluetooth, Wear (via notification listener) | `c.isTrusted` | defaults | all `nd.*` |
| Android Auto / AAOS | `session.isAutoCompanionController(c)` or `session.isAutomotiveController(c)` | defaults | all `nd.*` |
| Anything else | — | Media3 1.11 default for untrusted controllers: read-only ([PO-16](../PLAN.md#48-further-product-owner-decisions)) | none |

### Lifecycle and foreground state

Media3 runs the service in the foreground while `playWhenReady && (READY || BUFFERING)`, keeps it there for `DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS = 600_000` after a pause, stop, error or end, then demotes it and keeps the notification. We keep the default timeout.

```mermaid
stateDiagram-v2
  [*] --> Created: controller binds, media button or System UI
  Created --> Foreground: playWhenReady and READY or BUFFERING
  Foreground --> PausedInForeground: pause, error or end
  PausedInForeground --> Foreground: play within 10 min
  PausedInForeground --> Demoted: 10 min timeout
  Demoted --> Foreground: play from visible UI, notification or media key
  Demoted --> TapToResume: background start refused
  TapToResume --> Foreground: user taps notification or opens the player
  Demoted --> Stopped: notification dismissed or dismiss()
  Created --> Stopped: last controller unbinds with nothing loaded
  Stopped --> [*]
```

**Tap to resume.** `MediaSessionService.Listener.onForegroundServiceStartNotAllowedException()` (a background controller asked to play after demotion, e.g. focus regained after a 15-minute call) → `TapToResumeNotifier` posts `NOTIF_ID_TAP_TO_RESUME = 4001` on channel `alerts` (only if `POST_NOTIFICATIONS` is granted; otherwise only `NowPlaying.issue = PLAYER_ERROR` in-app): title "Playback paused", text "Android didn't let Neutrodyne resume in the background. Tap to continue." Content intent: explicit `MainActivity`, `neutrodyne://open/player` (routes only navigate, so the user presses Play in the visible player). One action "Resume": `PendingIntent.getForegroundService` to `NeutrodynePlaybackService` with `ACTION_MEDIA_BUTTON` + `KEYCODE_MEDIA_PLAY` (notification actions are FGS-start and while-in-use exempt). Unverified: that Media3 1.11 handles an `ACTION_MEDIA_BUTTON` start intent addressed to the service exactly like its own notification actions; if the M4 test fails, ship without the action. The notification auto-cancels and is cancelled when playback starts.

### Manifest entries (declared by `:playback:impl`)

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<application>
  <service android:name="app.neutrodyne.playback.impl.NeutrodynePlaybackService"
      android:exported="true" android:foregroundServiceType="mediaPlayback">          <!-- M4 -->
    <intent-filter>
      <action android:name="androidx.media3.session.MediaLibraryService" />
      <action android:name="android.media.browse.MediaBrowserService" />
      <action android:name="android.media.action.MEDIA_PLAY_FROM_SEARCH" />            <!-- M5 -->
    </intent-filter>
  </service>
  <receiver android:name="androidx.media3.session.MediaButtonReceiver" android:exported="true">   <!-- M5 -->
    <intent-filter><action android:name="android.intent.action.MEDIA_BUTTON" /></intent-filter>
  </receiver>
  <meta-data android:name="com.google.android.gms.car.application"
      android:resource="@xml/automotive_app_desc" />   <!-- M5; <automotiveApp><uses name="media"/></automotiveApp> -->
</application>
```

`POST_NOTIFICATIONS` is not needed for the media notification (media sessions are exempt). The session AAR merges `BluetoothValidationActivity` (`BLUETOOTH_PRIVILEGED`-protected AVRCP workaround on API 36/37); keep it.

---

## Player configuration

Serves R4.1, R4.7, R4.8. Delivered in M4. Values are the [canonical defaults](../PLAN.md#48-further-product-owner-decisions) (PO-20) unless noted.

```kotlin
internal class PlayerFactory @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val mediaSourceFactory: NdMediaSourceFactory,     // DataSource stack: Media items and URI resolution
    private val boost: BoostLimiterProcessor,                  // pass-through (inactive) until M12
    private val settings: SettingsStore,
) {
    fun create(): ExoPlayer {
        val s = runBlocking { settings.snapshot(PlaybackSettingKeys.ALL) }   // DataStore is in memory after first read
        val silence = SilenceSkippingAudioProcessor(250_000, 0.2f, 400_000, 10, 1024)  // Unverified tuning; verify by ear
        val renderers = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(c: Context, float: Boolean, params: Boolean): AudioSink =
                DefaultAudioSink.Builder(c).setEnableFloatOutput(float)
                    .setEnableAudioOutputPlaybackParameters(false)                  // Sonic speed, pitch preserved
                    .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(
                        arrayOf<AudioProcessor>(boost), silence, SonicAudioProcessor()))
                    .build()
        }
        return ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes(s.pauseForNavigation), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)                                     // switched per item
            .setSeekBackIncrementMs(s.skipBackMs).setSeekForwardIncrementMs(s.skipForwardMs)
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(60_000, 600_000, 1_500, 3_000)
                .setBackBuffer(60_000, /* retainBackBufferFromKeyframe = */ true).build())
            .setName("neutrodyne").build()
            .apply { trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)                   // v1.0: audio only (Video)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
    }
    fun audioAttributes(pauseForNav: Boolean) = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
        .setContentType(if (pauseForNav) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC).build()
}
```

| Aspect | Rule | Why |
|---|---|---|
| Audio focus | `handleAudioFocus = true`; `USAGE_MEDIA`; content type SPEECH when `playback.pause_for_navigation` (default on), else MUSIC | With SPEECH, ExoPlayer pauses instead of ducking for navigation prompts; transient loss (call) auto-resumes, permanent loss (other media app) does not |
| Becoming noisy | `setHandleAudioBecomingNoisy(true)` | Pause on headphone unplug (R4.7) |
| Wake mode | `WAKE_MODE_LOCAL` for `StreamKind.LOCAL`, `WAKE_MODE_NETWORK` otherwise, set in `onMediaItemTransition` and before the first `prepare()` | NETWORK adds a Wi-Fi lock for screen-off streaming |
| Load control | min 60 s, max 600 s, start 1.5 s, after rebuffer 3 s, back buffer 60 s | Instant "back 10 s"; the default audio byte cap (200 × 64 KiB ≈ 12.8 MB) still bounds memory (≈ 13 min at 128 kbps) — do **not** prioritise time over size (Android 17 memory limits) |
| Audio chain | `[BoostLimiterProcessor] → SilenceSkippingAudioProcessor → SonicAudioProcessor` | Custom processors run before silence skipping and speed; boost slot exists from day one ([D65](../PLAN.md#3-key-decisions)); `GainProcessor` only attenuates |
| Audio offload | Off in v1 | Processors are bypassed in offload mode |
| Seek increments | From `playback.skip_back_ms` / `playback.skip_forward_ms`; updated at runtime with `setSeekBackIncrementMs`/`setSeekForwardIncrementMs` (1.9+) **and** `session.setMediaButtonPreferences` | Notification icons follow the interval |
| Seek parameters | `SeekParameters.DEFAULT` (exact) | Chapters and resume positions land where expected |
| Repeat / shuffle | `REPEAT_MODE_OFF`, shuffle off; `COMMAND_SET_REPEAT_MODE` and `COMMAND_SET_SHUFFLE_MODE` removed in `SessionPlayer` | The database defines order |
| Video | Video and text track types disabled ([Video](#video)) | No decoding without a surface |

Extractors: `NdMediaSourceFactory` delegates to two `DefaultMediaSourceFactory` instances sharing the DataSource stack. Remote: `DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true).setDisableArtworkMetadata(true)` (seekable header-less CBR MP3; embedded APIC/`covr` art dropped — OOM risk; chapters kept). Local (`requestMetadata.extras["nd.local"] == true`): same plus `setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)` (accurate VBR seeks on files without a precise TOC; Media3 1.9+ still prefers Xing/VBRI when present). The hint is set by the projector from `LocalMediaIndex` at item build; a stale hint only affects seek accuracy.

`NdLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy`: for `InvalidResponseCodeException` 401/403/404/410 on a DataSpec whose key starts with `ep:`, retry once after 1 s (the retry falls back from a stale pinned final URL, [EpisodeResolver](#episoderesolver)), then `C.TIME_UNSET` (fatal). Everything else (including `yt:` 403/410, [04 Playback integration](04-youtube.md#playback-integration)) keeps the default backoff `min((n − 1) · 1 s, 5 s)`.

---

## Media items and URI resolution

Serves R4.1, R4.3, R3.5, R5.2. Delivered in M4 (remote), M6 (local), M9 (YouTube). Honours [D39](../PLAN.md#3-key-decisions), [D42](../PLAN.md#3-key-decisions).

### MediaItem shape

`MediaItemFactory.build(row: MediaLookupRow, live: ItemLive): MediaItem` from 02's `EpisodeDao.mediaInfo(ids)` ([02 Play context](02-data-model.md#play-context)).

| Field | Value |
|---|---|
| `mediaId` | `episode:{episodeId}` (queue diffing, Auto, resumption) |
| `uri` | `neutrodyne://episode/{episodeId}` |
| `mimeType` | never set — extractors sniff (wrong feed MIME types are common; `application/x-mpegURL` would select an absent HLS module) |
| `customCacheKey` | RSS: `ep:{episodeId}:{EnclosureFingerprint}`; YouTube: `yt:{videoId}` (the resolver sets `yt:{videoId}:{itag}` per DataSpec, [04](04-youtube.md#playback-integration)) |
| `requestMetadata` | `mediaUri` = the same URI (external controllers can re-request); `extras` = `nd.local` hint |
| `mediaMetadata.title` / `displayTitle` | episode title |
| `artist`, `albumTitle` | podcast title (`customTitle` first); `albumArtist` = podcast author. Many Bluetooth head units show only title and artist |
| `artworkUri` | [Artwork rule](#artwork-rule) |
| `durationMs` | `measuredDurationMs ?: feed durationMs` when > 0 (System UI shows no progress without it) |
| `releaseYear/Month/Day` | from `pubDate` (UTC) |
| `mediaType` | `MEDIA_TYPE_PODCAST_EPISODE` for every item in v1.0 (video plays as audio) |
| `isPlayable` / `isBrowsable` | true / false |
| `extras` | `EXTRAS_KEY_COMPLETION_STATUS` (not played / partially / fully), `EXTRAS_KEY_COMPLETION_PERCENTAGE`, `EXTRAS_KEY_DOWNLOAD_STATUS` (Unverified: exact `MediaConstants` names in 1.11.1; confirm at M5) |

**Immutable `LocalConfiguration`.** `uri`, `customCacheKey`, `mimeType` and the tag never change for a given item instance, so metadata refreshes use `replaceMediaItem` without re-preparing (`ProgressiveMediaSource.canUpdateMediaItem` compares URI, `customCacheKey` and image duration). If an RSS enclosure URL or length changes in the feed, the fingerprint changes: the projector replaces non-current items (re-prepare is harmless) and leaves the current item untouched until it is no longer current.

`EnclosureFingerprint.of(url, length) = sha1Hex("$url|${length ?: -1}").take(16)` over the raw stored `enclosureUrl`, so a new token or re-upload never shares cache bytes with the old file.

### Artwork rule

`artworkUri = ArtworkStore.contentUri(key, version)` — always a local `content://${applicationId}.artwork/…` URI, never `http(s)` (Auto requires `content://` or `android.resource://`; boot-time resumption has no network):

1. `YOUTUBE_CHANNEL`: `podcastArtworkKey` (square channel avatar, [04 Artwork and thumbnails](04-youtube.md#artwork-and-thumbnails)), never the 16:9 thumbnail.
2. RSS with its own art: the episode `artworkKey` only if `ArtworkStore.pinnedFile(key) != null` (episode art is pinned for downloads, [R5.3](../PLAN.md#21-functional-requirements)); otherwise the podcast key.
3. 08's `ArtworkProvider` serves the monogram when the file for a key is missing ([08 Artwork pipeline](08-ui-ux.md#artwork-pipeline)), so 06 never needs a fallback branch.

The session keeps Media3's default bitmap loader (`DataSourceBitmapLoader` behind `CacheBitmapLoader`), which reads `content://` URIs through `DefaultDataSource`; no Coil dependency in the service. `pinnedFile` touches the disk, so `MediaItemFactory` runs on `@Dispatcher(IO)`.

### DataSource stack

```mermaid
flowchart TB
  P["ProgressiveMediaSource, uri neutrodyne://episode/id"] --> R["ResolvingDataSource with EpisodeResolver"]
  R --> D["DefaultDataSource"]
  D -->|"file:// content://"| F["FileDataSource / ContentDataSource, no cache"]
  D -->|"http https"| C["CacheDataSource, SimpleCache, FLAG_IGNORE_CACHE_ON_ERROR"]
  C --> G["GuardedHttpDataSource"]
  G --> O["OkHttpDataSource on HttpClient MEDIA"]
```

`OkHttpDataSource` uses `@HttpClient(HttpClientKind.MEDIA)` ([01 One client family](01-foundation.md#one-client-family)): shared pool, User-Agent, `Accept-Encoding: identity` (byte-exact ranges) and the same-origin `AuthInterceptor`, so Basic-auth private feeds need no per-item headers. OkHttp follows http→https redirects (tracking prefixes such as Podtrac/OP3 do this; `DefaultHttpDataSource` would refuse cross-protocol redirects).

### EpisodeSourceIndex

`resolveDataSpec` runs on Media3's loader thread for every new connection and may block, but must not hit the database on the common path. `EpisodeSourceIndex` (`@Singleton`, `ConcurrentHashMap<Long, EpisodeSource>`) is filled by the projector for every window item **before** the item is handed to the player, and entries are removed when items leave the window.

```kotlin
internal data class EpisodeSource(
    val episodeId: Long, val podcastId: Long, val sourceType: SourceType,
    val enclosureUrl: String?, val enclosureLength: Long?, val videoId: String?,   // externalMediaId
    val isVideo: Boolean, val audioAlternateUrl: String?,                          // first audio/* alternate (M5)
    val availability: Availability,
)
```

Miss (an item inserted by Media3's resumption path before the projector ran): `runBlocking { withTimeout(5_000) { episodeDao.mediaInfo(listOf(id)) } }` — the only `runBlocking` DB call, allowed on the loader thread ([01 Coroutines and threading](01-foundation.md#coroutines-and-threading)).

### EpisodeResolver

`EpisodeResolver : ResolvingDataSource.Resolver` (`@Singleton`). A **pin** fixes, per episode and for the life of one playback of it, where the bytes come from:

```kotlin
internal sealed interface Pin {
    data class Local(val uri: String) : Pin
    data class Remote(val originalUrl: String, val cacheKey: String,
                      @Volatile var finalUrl: String? = null, @Volatile var totalLength: Long? = null) : Pin
    data class YouTube(val videoId: String, @Volatile var itag: Int? = null) : Pin
}
```

`resolveDataSpec(spec)`:

1. `spec.uri.scheme != "neutrodyne"` → return `spec` unchanged.
2. `id` from the path; `source = index[id] ?: dbFallback(id) ?: throw EpisodeGoneException` (→ skip, [Error recovery](#error-recovery)).
3. `pin = pins.getOrPut(id)`:
   1. `localMediaIndex.localUriOrNull(id)` non-null → `Local` (a completed download always wins, also for YouTube).
   2. `sourceType == YOUTUBE_CHANNEL && videoId != null` → if `!capabilities.inAppPlayback` throw `YouTubeResolveException(Unsupported)`; else `YouTube(videoId)`.
   3. Else `url = if (isVideo && audioAlternateUrl != null) audioAlternateUrl else enclosureUrl ?: throw NoMediaException`; `Remote(url, "ep:$id:${EnclosureFingerprint.of(url, length)}")`, and `streamingCache.resetResource(cacheKey)` (DAI rule below).
4. By pin:
   - `Local` → `spec.withUri(uri)` (DefaultDataSource routes it past the cache).
   - `Remote` → `spec.buildUpon().setUri(finalUrl ?: originalUrl).setKey(cacheKey).build()`.
   - `YouTube` → `runBlocking { withTimeout(25_000) { yt.resolveAudio(videoId, audioPref(pin.itag)) } }`; `Ok` with a different itag than `pin.itag` → throw `YouTubeFormatChangedException`; else `pin.itag = itag`, return `uri = audio.url`, `key = "yt:$videoId:$itag"`. Any other result → throw `YouTubeResolveException(result)` ([04 Playback integration](04-youtube.md#playback-integration), which also defines `AudioPref` from `youtube.audio_quality`, `youtube.volume_levelling` and the app language).
5. Thread interruption (Media3 cancelling a load) surfaces from `runBlocking` as `InterruptedException` → rethrown as `InterruptedIOException`.

**Pin lifetime.** Created at the first open of the item (often while pre-buffering it as the next item); released by `unpin(id)` when the item leaves the window, when it stops being current after having been current, on [error recovery](#error-recovery), and by `clearPins()` at service start and destroy.

**Dynamic ad insertion (risk [T7](../PLAN.md#8-risks-and-mitigations)).** DAI hosts (Megaphone, Acast, Art19, …) serve different bytes and lengths per request, so bytes from two responses must never be stitched:

- The source (local vs remote) never switches during a pin.
- `GuardedHttpDataSource` records the post-redirect URL of the first successful response (`OkHttpDataSource.getUri()`) into `pin.finalUrl`; later range requests of the same pin go straight to it, which keeps them on the same stitched rendition. It also records the total length (`Content-Range` total or `Content-Length` of a 200 at offset 0); a later response with a different total throws `ContentChangedException` (an `IOException` the policy treats as fatal) → [Error recovery](#error-recovery).
- A 401/403/404/410 on a request that used `finalUrl` clears `finalUrl` (signed CDN URLs expire) and rethrows; the single policy retry goes through the original URL. Final URLs live only in memory ([D50](../PLAN.md#3-key-decisions)).
- Each new `Remote` pin starts with an empty cache resource for its key (`resetResource`), so cached bytes are reused only within one pin. Positions are time-based; `episode_position.positionSource` records STREAM vs DOWNLOAD so 08 can show "position may differ" when a resumed download was listened to as a stream.

**Downloads during playback** (M6):

| Event | Behaviour |
|---|---|
| Download completes for the **current** item | Pin stays `Remote` until the item is no longer current (DAI) |
| Download completes for a **non-current** window item | Projector sees the download state change; if the item is pinned `Remote` it calls `resolver.unpin(id)` and removes and re-adds that item (drops its pre-buffer, so the next open resolves locally) |
| File of the current item deleted or missing | 07 defers user deletes of `play_session.currentEpisodeId` until the next transition; if the file still disappears, the next re-open fails with `ERROR_CODE_IO_FILE_NOT_FOUND` → [Error recovery](#error-recovery) re-pins to remote at the same position and calls `DownloadController.reportFileMissing(id)` |
| Removable volume unmounted mid-play | Same as missing file |

### YouTube branch

Owned by [04 Playback integration](04-youtube.md#playback-integration): branch condition, `DataSpec` key, format pinning, expiry through the cache TTL, 403/410 self-heal, pre-resolve and error mapping. 06 implements:

- `GuardedHttpDataSource` for keys starting `yt:`: on `InvalidResponseCodeException` 403 or 410, `yt.invalidate(videoId)` (videoId parsed from the key) at most twice per item per 60 s, then rethrow; Media3's retry re-enters `resolveDataSpec` at the same byte offset.
- `PreResolver`: on the position tick, when the current item has ≤ 60 s left and the next window item is YouTube without a local file, `appScope.launch { yt.resolveAudio(videoId, pref) }` once per item; the result is ignored (it warms `ResolvedUrlCache`).
- If a `YouTubeFormatChangedException` follows a `clen` change, `streamingCache.removeResource("yt:$videoId:$oldItag")` before re-preparing ([04 Error mapping](04-youtube.md#error-mapping)).
- In `play`, YouTube items are never projected (context tail `youtubePlayable = false`, `QueueRepository` rejects adds), so the branch is unreachable; `ExternalOnlyYouTubeStreamResolver` returns `Unsupported` defensively.

### Metered and offline gate

R4.1 setting `playback.stream_on_metered` = `ALLOW` (default) / `ASK` / `NEVER`. `MeteredStreamingGate` (`@Singleton`) holds an in-memory grant that is cleared when `NetworkMonitor.status.isMetered` becomes false or the process dies. An item "needs network" when it would not pin `Local`.

| Situation | Behaviour |
|---|---|
| UI starts an item (`play*`), network metered, `ASK`, no grant | No state change; `PlayResult.NeedsMeteredConsent(episodeId)`. UI dialog (08): "Stream on mobile data?" [Once] → `grantMeteredStreaming()` and retry · [Always] → set `ALLOW` and retry · [Cancel] |
| Same with `NEVER` | `PlayResult.MeteredBlocked` ("Streaming on mobile data is off" + Settings link) |
| No connected network and the item needs network | `PlayResult.Offline` ("You're offline — downloaded episodes still play") |
| Network becomes metered during an item | The current item continues |
| The next window item needs network, metered, no grant, policy ≠ `ALLOW` | `EndOfItemPauseArbiter` sets `pauseAtEndOfMediaItems`; at the pause `NowPlaying.issue = METERED_PAUSE` (UI banner "Continue on mobile data?"). A play from notification, media key or Auto counts as consent under `ASK` (grant) and is refused under `NEVER` (`issue = METERED_BLOCKED`) |

`EndOfItemPauseArbiter` owns the single `pauseAtEndOfMediaItems` flag: `flag = sleepTimer.endOfEpisodeArmed || meteredGate.blockBeforeNext`, recomputed whenever either input, the network or the window changes.

### Error recovery

`ErrorRecovery.onPlayerError(error)` on the main looper. Classification walks the cause chain.

| Condition | Action | `NowPlaying.issue` / event |
|---|---|---|
| `YouTubeResolveException`, `YouTubeFormatChangedException` | [04 Error mapping](04-youtube.md#error-mapping) (skip, pause, or one re-prepare) | per 04 |
| `ERROR_CODE_IO_FILE_NOT_FOUND` on a `Local` pin | `unpin`, `reportFileMissing(id)`, `prepare()` at the current position (re-pins `Remote`, or `Offline` → pause) | `LOCAL_FILE_MISSING` until playing |
| HTTP 404/410 on an RSS pin (after the stale-final-URL retry) | Skip to next | `Skipped(Http(status))` |
| HTTP 401/403 on an RSS pin | Skip to next | `Skipped(AuthRequired)` |
| HTTP 416, or `ContentChangedException` (DAI length change) | Once per item: `unpin`, `resetResource`, `prepare()` at the current position; second time: skip | — |
| `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`, `_TIMEOUT`, other network `IOException` | Keep the item; when `NetworkMonitor` reports a validated network **within the foreground window (≤ 10 min after the error)** and the user has not paused since, `prepare()` and resume; later, wait for the user | `NETWORK_LOST` |
| `ERROR_CODE_PARSING_*`, `ERROR_CODE_DECODING_*`, `ERROR_CODE_DECODER_INIT_FAILED`, `UnrecognizedInputFormatException` (also HLS enclosures) | Skip to next | `Skipped(UnsupportedFormat)` |
| `EpisodeGoneException`, `NoMediaException` | Skip to next | `Skipped(NoMedia)` |
| `ERROR_CODE_AUDIO_TRACK_*` | `prepare()` once, then pause | `PLAYER_ERROR` |
| Anything else | Pause, keep position | `PLAYER_ERROR` |

Limits: at most 3 automatic re-prepares per item per 2 min; at most 5 consecutive skips, then pause with `PLAYER_ERROR` (prevents spinning through a queue while offline). "Skip" = `exo.seekToNextMediaItem(); exo.prepare()` with `playWhenReady` unchanged; the resulting `SEEK` transition is handled like any other ([Transitions](#transitions)) and never marks the failed item played. With no next item: `exo.stop()`, phase `ERROR`. Every skip emits `PlaybackEvent.Skipped` (08 shows "Skipped “{title}”: {reason}").

---

## Streaming cache

Serves R4.1, N6. Delivered in M4. Honours [D40](../PLAN.md#3-key-decisions).

| Aspect | Rule |
|---|---|
| Instance | One process-wide `SimpleCache(File(filesDir, "media-cache"), AdjustableLruCacheEvictor(maxBytes), StandaloneDatabaseProvider(ctx))`, `@Singleton`, created lazily on first player creation (its index initialises on Media3's own thread). A second instance on the same folder throws |
| Location | `filesDir/media-cache/` (not `cacheDir`: the OS may purge files under the index). Never in Auto Backup (include-only rules, [D34](../PLAN.md#3-key-decisions)); Media3's `StandaloneDatabaseProvider` keeps its index in its own database file, also not backed up |
| Size | `playback.stream_cache_mb` (100, 250, **500**, 1000, 2000). `AdjustableLruCacheEvictor` is our `CacheEvictor` with a `@Volatile maxBytes` (LRU by last touch, like Media3's `LeastRecentlyUsedCacheEvictor`, which is final); a change applies immediately and evicts down to the new size on IO |
| Keys | RSS `ep:{episodeId}:{fingerprint}` — reset at each new pin (DAI); YouTube `yt:{videoId}:{itag}` — kept across sessions (one itag's bytes are identical across URLs) |
| Flags | `FLAG_IGNORE_CACHE_ON_ERROR`; default `CacheDataSink` fragment size |
| Separation | Downloads never read or write it; local files bypass it; downloads are never LRU-evicted ([D46](../PLAN.md#3-key-decisions)) |
| Clear | `PlaybackMaintenance.clearStreamingCache()`: `cache.keys` minus keys of current pins → `removeResource` on IO. Never `SimpleCache.delete()` or file deletion while the cache is open |
| Usage | `streamingCacheBytes()` = `cache.cacheSpace`; shown in Settings › Playback beside the clear action |
| Disk full | A failed cache write is ignored (`FLAG_IGNORE_CACHE_ON_ERROR`); playback continues from the network |

---

## Queue and play context

Serves R4.8, R2.6, R3.7. Delivered in M4. Honours [D38](../PLAN.md#3-key-decisions), [D44](../PLAN.md#3-key-decisions), [PO-11](../PLAN.md#48-further-product-owner-decisions). Tables: [02 queue_entry](02-data-model.md#queue_entry), [02 play_session](02-data-model.md#play_session).

### Definitions

- **Current** = `play_session.currentEpisodeId`. An item that becomes current is **removed from `queue_entry`** in the same transaction, so Up next never contains the playing item and played items leave Up next automatically.
- **Up next** = `queue_entry` ordered by `(ordinal, id)`; user-owned.
- **Play context** = `contextType`/`contextId` + order + filters + anchor; the **context tail** is the next items after the anchor that are unplayed, `AVAILABLE`, visible, playable in this build and not in Up next ([02 Play context](02-data-model.md#play-context)); which episodes a context contains and where "Play" starts are 05's rules ([05 Playing a group](05-groups-opml-backup.md#playing-a-group)).
- **Virtual queue** = `[current] ++ upNext ++ contextTail`. `QueueRepository.observeVirtualQueue(k)` emits current, at most `UP_NEXT_PROJECTION_CAP = 100` Up next items and `k` context items (queried as `k + 1`, the current item removed). Anchor `null` means "before the first item" (`(+∞, +∞)` for `NEWEST_FIRST`, `(−∞, −∞)` for `OLDEST_FIRST`).
- **Window** = the virtual queue minus items that cannot play: `availability != AVAILABLE`, YouTube when `!capabilities.inAppPlayback`. Up next keeps such items in the database (08 greys them); the window skips them.

### Starting playback

All UI starts go through the session command `nd.PLAY_CONTEXT` so the database write, the projection and `prepare()` happen in the service, atomically with respect to the projector; the client then calls `MediaController.play()` from the visible UI ([D43](../PLAN.md#3-key-decisions)).

```mermaid
sequenceDiagram
  participant UI as Visible UI
  participant PC as PlaybackControllerImpl
  participant MC as MediaController
  participant CB as CustomCommands (service)
  participant SW as SessionWriter
  participant PJ as QueueProjector
  participant X as ExoPlayer
  UI->>PC: playFeed(Group 7, filters, NEWEST_FIRST)
  PC->>MC: sendCustomCommand nd.PLAY_CONTEXT
  MC->>CB: onCustomCommand
  CB->>SW: plan, gates, then commitStart (one transaction, generation g)
  SW-->>CB: VirtualQueue g
  CB->>PJ: applyNow(VirtualQueue g)
  PJ->>X: setMediaItems(window, 0, startPositionMs) and prepare
  CB-->>MC: SessionResult OK
  PC->>MC: play()
  MC->>X: playWhenReady true, FGS starts while the app is visible
```

`nd.PLAY_CONTEXT` args: `contextType` (absent for `playEpisode`), `contextId`, `order`, `filterFlags`, `mediaFilter`, `minSortDate`, `startEpisodeId?`, `startPositionMs?`. Handler steps:

1. **Plan** (read-only): `startEpisodeId` given → current = it. Otherwise ("Play group" button): Up next non-empty → current = Up next head, context anchor `null` (the tail starts at the context's first item — D44, M4 acceptance 6); else current = 05's start item for the context, anchor = it; none → `RESULT_NOTHING_TO_PLAY`. `playEpisode` keeps the existing context and anchor.
2. **Gates** on the planned current: YouTube in `play` → `NotPlayable(NotInThisBuild)`; `availability != AVAILABLE` → `NotPlayable(YouTube(availability))`; [metered and offline gate](#metered-and-offline-gate).
3. **Commit** (`SessionWriter.commitStart`, one write transaction): `play_session` current, context columns, anchor (`contextAnchorEpisodeId`, `contextAnchorSortDate`), `contextMinSortDate`, `generation + 1`, `updatedAt`; delete the new current from `queue_entry`; `EpisodeStateDao.touchLastPlayed`.
4. **Project now**: read the virtual queue once (not waiting for the Flow), build items, `applier.applyFor(current)` (speed and skip silence before the first `prepare()`), `setMediaItems(window, 0, StartPositionRule(...))`, `prepare()`.
5. Result codes map 1:1 to `PlayResult`; the client calls `play()` only on success.

`play()` (resume): `awaitController()`; if `phase == IDLE` → `controller.prepare()`; `controller.play()`. `SessionPlayer` handles prepare/play on an empty playlist by loading the window from the database first (and, when `current == null` but Up next is non-empty, by committing Up next's head as current). The previously current item that a new start interrupts is not re-queued; it stays "in progress" in its feeds.

### Up next operations

| Operation | Rule |
|---|---|
| Play next / Play last | `addNext` / `addLast` (02 SQL: `ordinal = min − 1` / `max + 1`); an already-queued item moves; inserts chunked at 500 |
| Reject | the current item (`ALREADY_PLAYING`); YouTube when `!inAppPlayback` (`YOUTUBE_EXTERNAL`, [04 Capability consumers](04-youtube.md#capability-consumers)); `availability != AVAILABLE` (`UNAVAILABLE`) |
| Reorder | `move(episodeId, toIndex)`: one write; ordinal = midpoint of the new neighbours, renormalise when the gap < 1e-9 ([02 Up next ordering](02-data-model.md#up-next-ordering)) |
| Remove / Clear | `remove(ids)`, `clearUpNext()`; the current item is unaffected |
| Stop after Up next | `clearContext()`: `contextType = NULL`, `generation + 1` |
| Played anywhere | Every path that marks episodes played (06 transitions, `EpisodeRepository.setPlayed`/`markFeedPlayed` (03), import "treat as played" (05)) deletes those episodes from `queue_entry` in the same transaction |
| Context header | `observeSession()` → `PlayContextInfo` with a display title (group name, podcast title, "All", "Ungrouped", "Downloads") for "Then: tech, newest first" (08) |

### Transitions

`PositionTracker` hands every item change to `SessionWriter.onTransition(departure, arrival, reason)` (one write transaction, `generation + 1`):

| Player reason | Departing item | Arriving item |
|---|---|---|
| `MEDIA_ITEM_TRANSITION_REASON_AUTO` | Position saved; marked played ([played rule](#played-state)) | Becomes current; removed from `queue_entry`; if its origin is `CONTEXT`, anchor = it |
| `…_SEEK` (next/previous, Auto queue pick, skip after error) | Position saved; marked played only if within the threshold and it was playing in this pin | Same as AUTO |
| `…_PLAYLIST_CHANGED` | Ignored — our own `setMediaItems`; the database already describes it | — |
| `…_REPEAT` | Never (repeat off) | — |

The window keeps a side map `mediaId → QueueOrigin` so the writer knows whether the arriving item came from Up next or the context. When the last item ends (`STATE_ENDED`, nothing next): mark it played, `SessionWriter.endSession()` (current and context cleared), the projector clears the player and the service calls `pauseAllPlayersAndStopSelf()`; the notification disappears and a later Play starts Up next if it has items.

**Previous episode.** `PlaybackHistory` (`@Singleton`, in memory, last 20 episode IDs that were current in this process). `skipToPrevious()` and hardware/Bluetooth "previous" ([SessionPlayer](#hardware-buttons-and-sessionplayer)): position > 3 s → seek to 0 (an explicit reset, [positions](#positions)); else if history is non-empty → `SessionWriter.goBack(prevId)`: the current item is re-inserted at the front of Up next, `prevId` becomes current (its context origin restores the anchor to it); else seek to 0.

### QueueProjector

Runs on the main looper (`lifecycleScope`); collects `observeVirtualQueue(20)` (`distinctUntilChanged`, `conflate()`), then loads `MediaLookupRow`s for the window (one `EpisodeDao.observeMediaInfo(ids)` query, low-churn tables only) and builds items on IO.

```mermaid
sequenceDiagram
  participant Q as QueueRepository (UI edit)
  participant DB as Room
  participant PJ as QueueProjector (main)
  participant X as ExoPlayer
  participant PT as PositionTracker
  participant SW as SessionWriter
  Q->>DB: move Up next item (queue_entry ordinal)
  DB-->>PJ: VirtualQueue g
  PJ->>X: moveMediaItem(3, 1), index 0 untouched
  X-->>PT: onMediaItemTransition AUTO to item B
  PT->>PJ: beginOwnWrite()
  PT->>SW: onTransition(A departs, B arrives)
  DB-->>PJ: VirtualQueue g, read before the write
  PJ->>PJ: stash, own write in flight
  SW-->>PJ: endOwnWrite(g+1)
  DB-->>PJ: VirtualQueue g+1
  PJ->>X: diff empty or tail top-up only
```

**Generation guard.** `appliedGeneration` and `ownWritesInFlight` live on main. A snapshot is applied only if `ownWritesInFlight == 0` and `snapshot.generation ≥ appliedGeneration`; otherwise it is stashed (latest wins) and re-evaluated when the write completes. UI edits of `queue_entry` do not bump the generation, so they never look stale.

**Diff (`WindowDiff`, pure Kotlin).** `have` = player `mediaId`s, `want` = window `mediaId`s.

1. `want` empty → `exo.stop(); exo.clearMediaItems()` (if not already empty).
2. `have` empty or `want[0] != have[currentIndex]` → current changed through the database: `setMediaItems(window, 0, StartPositionRule(...))`; `prepare()` only if `playWhenReady` or a play is pending.
3. Otherwise: remove items before the current index (`removeMediaItems(0, cur)`; history lives in `PlaybackHistory`, not the player). Tail: compute the LCS of `haveTail` and `wantTail`; remove non-LCS items (descending index), then walk `wantTail` placing each item with `moveMediaItem` or `addMediaItem` — so reordering only the next item is one `moveMediaItem` and its pre-buffer survives. If the edit script exceeds 30 operations, `replaceMediaItems(1, size, wantTailItems)` instead. **Index 0 (current) is never touched** by steps 3–4.
4. Same IDs, changed metadata (title, artwork version, completion, download state) → `replaceMediaItem(i, item)` (no re-prepare; [immutable LocalConfiguration](#mediaitem-shape)).
5. Changed `LocalConfiguration` (fingerprint) on a non-current item, or a newly local item pinned `Remote` → `unpin` + remove + add at the same index.
6. Before handing items over: `EpisodeSourceIndex.putAll(window)`; after removals: `index.remove` and `resolver.unpin` for items that left.

**External controllers.** `onSetMediaItems` (Auto, Assistant, Bluetooth browse) and `onAddMediaItems` write the database **first**, then return items, so the next database emission never reverts the user's choice in the car ([System surfaces](#android-auto-and-assistant)). Media3 then calls `setMediaItems` on the player; the projector's next diff is a no-op.

**Edge cases.**

- Current episode deleted (unsubscribe cascade sets `currentEpisodeId` NULL): window becomes empty → player cleared; 03 pauses first ([03 Unsubscribe](03-feeds-and-discovery.md#unsubscribe-and-other-podcast-operations)).
- Context group deleted (05 clears the context, `generation + 1`): the current item continues, then Up next, then stop.
- Current item marked played elsewhere: if playing → `seekToNextMediaItem()` (a `SEEK` transition that does not mark again); if paused → the next item becomes current, paused.
- Up next with > 100 items: the window holds the first 100 and no tail; it tops up as items are consumed.
- A 2,000-episode group never materialises more than K = 20 context items in the player (timeline serialisation to System UI, Auto and Wear stays small).

---

## Positions and played state

Serves R4.1, R4.8, N1. Delivered in M4 (positions, played rule), M5 (measured duration, smart resume). Honours [D41](../PLAN.md#3-key-decisions). SQL: [02 User-state writes](02-data-model.md#user-state-writes).

### Positions

`PositionTracker : Player.Listener` (service, main looper). Each save is one write transaction through `PositionWriter`: `insertIfAbsent` + `updateGuarded` + (if position > 0) `ensure` + `markStarted` ([02](02-data-model.md#user-state-writes)), with `positionSource` = DOWNLOAD for `Local` pins, STREAM otherwise, and `durationMs` from the player.

| Trigger | Saved |
|---|---|
| Every 5 s while `isPlaying` (`delay(5_000)` loop in `lifecycleScope`) | current item, `exo.currentPosition` |
| `onIsPlayingChanged(false)` (pause, buffering stall, focus loss) | current item |
| `onPositionDiscontinuity(old, new, reason)` with a different `mediaId` | the **outgoing** item at `old.positionMs` — before anything else reacts (registered first) — via `SessionWriter.onTransition` |
| `onPositionDiscontinuity(…, DISCONTINUITY_REASON_SEEK)` same item | new position; a user seek to < 1 s is an explicit reset (`PositionDao.reset`) |
| `onDestroy` | snapshot captured on main, written on `@ApplicationScope` (`NonCancellable`) |

Guards (N1: at most 5 s lost on a process kill; never 0 over non-zero):

1. `updateGuarded` never replaces a non-zero position with 0; only `reset` (explicit reset, mark played) does.
2. The start position is applied before playback of an item proceeds (`setMediaItems(…, startPositionMs)` or `seekTo` inside `onMediaItemTransition`, both on main before the next tick), so a tick never records a few hundred milliseconds over a saved 45 minutes.
3. Played-after-start guard: inside the save transaction, if `episode_state.playedAt ≥ pinStartedAt` (the episode was marked played by another path after this playback began) the position is not written.
4. A failed write (`SQLITE_FULL`) is retried on the next tick.

### Start position

`StartPositionRule(saved, savedAt, durationMs, played, now, smartResume)`:

```kotlin
fun startPositionMs(saved: Long?, savedAt: Long?, durationMs: Long?, played: Boolean, now: Long, smartResume: Boolean): Long {
    if (played || saved == null || saved <= 5_000) return 0
    if (durationMs != null && saved >= durationMs - 30_000) return 0
    val rewind = if (smartResume && savedAt != null && now - savedAt > 5 * 60_000) 5_000 else 0
    return (saved - rewind).coerceAtLeast(0)
}
```

Applied to the first item of `setMediaItems` and in `onMediaItemTransition` (AUTO and SEEK) for the arriving item. **Smart resume** within a live session: `SessionPlayer` records the elapsed-realtime of the last user pause; a play after > 5 min rewinds 5 s (`playback.smart_resume`, default on). Playing an episode that is marked played starts at 0 and, at its first `isPlaying = true`, `markUnplayed` (it is being listened to again).

### Played state

`PlayedRule`: an item is finished when `durationMs − positionMs ≤ max(30_000, durationMs · 3 / 100)`; `durationMs` = player duration if known, else `measuredDurationMs`, else the feed hint; unknown → only end-of-media counts.

Marked played (one transaction: `EpisodeStateDao.markPlayed`, `PositionDao.reset`, delete from `queue_entry`; emits `PlaybackEvent.MarkedPlayed`) when:

- an AUTO transition leaves an item, or `STATE_ENDED`;
- `onPlayWhenReadyChanged(false, PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)` (sleep "end of episode" or the metered pause);
- a SEEK transition or a new start leaves an item within the threshold **and** the item was playing at least once in this pin (skipping a never-started item does not mark it).

Pausing near the end never marks played (pressing Play would otherwise restart from 0). `EpisodeRepository.setPlayed(ids, false)` (03) = `markUnplayed` + `PositionDao.reset` + `startedAt = NULL` (fully unplayed).

```mermaid
stateDiagram-v2
  [*] --> Unplayed
  Unplayed --> InProgress: first save with position above 0 sets startedAt
  InProgress --> Played: played rule or user marks played
  Unplayed --> Played: user or bulk marks played
  Played --> InProgress: listened to again, playedAt cleared at first isPlaying
  Played --> Unplayed: user marks unplayed
  InProgress --> Unplayed: user marks unplayed, position reset
```

### Measured duration

When the timeline reports a duration for the current item (`onPlaybackStateChanged(READY)`, not `C.TIME_UNSET`) and it differs from the stored `measuredDurationMs` by > 1 s (or none is stored): `EpisodeStateDao.ensure` + `setMeasuredDuration` once per pin (`itunes:duration` is often wrong; YouTube `play` has none). Lists read `COALESCE(s.measuredDurationMs, e.durationMs)` ([02 Feed pages](02-data-model.md#feed-pages)). Delivered in M5.

---

## Per-scope playback settings

Serves R2.7, R4.8. Delivered in M4 (speed, skip silence), M12 (boost, intro/outro). Honours [D20](../PLAN.md#3-key-decisions), [D45](../PLAN.md#3-key-decisions), [D65](../PLAN.md#3-key-decisions).

05's `EffectiveSettingsResolver` decides values and attribution ([05 Effective settings resolution](05-groups-opml-backup.md#effective-settings-resolution)); 06 supplies the inputs and applies the result.

- **Inputs.** `podcastId` of the item and `contextGroupId` = `play_session.contextId` when `contextType == GROUP` **and** the item's podcast is a member of that group (an Up next item from another podcast does not inherit the group's speed); otherwise null. Globals are `playback.speed` and `playback.skip_silence` ([Settings](#settings)).
- **When.** `EffectivePlaybackApplier` keeps the resolved values for every window item (re-resolved when the window, memberships or overrides change), so application is synchronous: before the first `prepare()` of a start, in `onMediaItemTransition`, and whenever the current item's effective value changes. It calls `exo.playbackParameters = PlaybackParameters(speed)` and `exo.skipSilenceEnabled = skipSilence`, and publishes the value with its source to `PlaybackStateSource.effectivePlayback` (08 renders "1.5× (from group 'news')", M4 acceptance 7). Audio already processed at the old speed (≈ 0.5 s) may play after a transition — accepted.
- **Writes from the player.** `setSpeed(speed, scope)` → `nd.SPEED_SET_SCOPE(speed, scope)`: clamp to 0.5–3.0, round to 0.05, apply to the player immediately, then write the scope through 05's `ScopeSettingsRepository` (`PODCAST` → the item's podcast; `GROUP` → `contextGroupId`, else `NO_CONTEXT_GROUP`; `GLOBAL` → `playback.speed`). Writing a scope also clears the same setting at the more specific scopes on the current chain (GLOBAL clears the podcast's and the context group's override; GROUP clears the podcast's), so the chosen value takes effect now; 08's sheet says so. `setSkipSilence` / `nd.SKIP_SILENCE` work the same way.
- **Speed cycle** (`nd.SPEED_CYCLE`, notification): next value of `playback.speed_presets` above the current speed (wrapping), written at the scope the current value comes from.
- **External `Player.setPlaybackSpeed`** from a trusted controller: applied for the current item only, not persisted; the next transition restores the effective value.
- **v1.x hooks (M12).** `BoostLimiterProcessor` reads a `@Volatile gainDb` (0 = inactive); `IntroOutroSkipper` will seek past `introSkipMs` at item start and schedule `exo.createMessage { … seekToNextMediaItem() }.setPosition(duration − outroSkipMs)`. Columns are reserved in [02 podcast_settings](02-data-model.md#podcast_settings); `nd.BOOST` is reserved.

---

## Sleep timer

Serves R4.8. Delivered in M5. Honours [PO-20](../PLAN.md#48-further-product-owner-decisions) (counts only while playing, 10 s fade).

`SleepTimer` (service, main looper) is driven by `nd.SLEEP_SET` (`mode` = `minutes` / `end_of_episode` / `off`, `minutes` 1–240) and `nd.SLEEP_EXTEND` (`minutes`), and publishes `SleepTimerState` to the hub. In-memory only: it dies with the service.

```mermaid
stateDiagram-v2
  [*] --> Off
  Off --> Counting: SLEEP_SET minutes while playing
  Off --> Frozen: SLEEP_SET minutes while paused
  Counting --> Frozen: playback stops advancing
  Frozen --> Counting: playback advances again
  Counting --> Fading: remaining 10 s or less
  Fading --> Frozen: user pauses, volume restored
  Fading --> Counting: SLEEP_EXTEND, volume restored
  Fading --> Off: remaining reaches 0, pause, volume restored
  Off --> EndOfEpisode: SLEEP_SET end_of_episode
  EndOfEpisode --> Off: END_OF_MEDIA_ITEM pause or SLEEP_SET off
  Counting --> Off: SLEEP_SET off
  Frozen --> Off: SLEEP_SET off
```

- **Minutes.** `remainingMs` decreases by elapsed-realtime deltas only while `exo.isPlaying` (a loop waking every `min(remaining − 10 s, 1 s)`). In the last 10 s, `exo.volume` ramps 1 → 0 in 20 steps of 500 ms, then `pause()`, then `volume = 1`, state `Off`, event `SleepTimerFired`. A pause during the fade restores the volume immediately. Extend adds minutes and restores the volume. `playback.sleep_last_minutes` remembers the last choice for 08's sheet.
- **End of episode.** Arms `EndOfItemPauseArbiter` (`pauseAtEndOfMediaItems = true`): the player pauses at the end of the current item without transitioning; `PositionTracker` marks it played (the `END_OF_MEDIA_ITEM` reason); the timer goes `Off`, which clears the flag (forgetting it would pause every later episode). Switching items while armed keeps it armed for the new current item.
- **After firing.** Media3 keeps the FGS for 10 min, then demotes; resuming needs a user action, which satisfies [D43](../PLAN.md#3-key-decisions).
- **v1.x (M12).** End of chapter: `exo.createMessage { _, _ -> exo.pause() }.setPosition(index, chapter.endMs).setDeleteAfterDelivery(true)`, re-armed on seeks and chapter changes. Shake to extend: accelerometer registered only in the last 60 s and for 5 min after firing; no permission needed.

---

## Chapters

Serves R4.8, R3.5. Delivered in M5 (P2.0 JSON, PSC, ID3, MP4), M6 (download-time extraction), M9 (YouTube descriptions). Table: [02 chapter](02-data-model.md#chapter).

### Sources and priority

The first source with at least one visible (non-hidden) chapter wins; sources are never merged (M5 acceptance 2).

| Priority | `ChapterSource` | Written by | When |
|---|---|---|---|
| 1 | `PODCASTING20_JSON` | 06 `Chapters` | When an item with `chaptersUrl` becomes current, and on `ChapterRepository.ensureLoaded` after a download completes |
| 2 | `PSC` | 03 ingestion | At ingest ([03 Ingestion and diff](03-feeds-and-discovery.md#ingestion-and-diff)) |
| 3 | `ID3` (MP3 `CHAP`) | 06 | `onTracksChanged` of the current item; `ensureLoaded` with a local file |
| 4 | `MP4` (Nero/QuickTime in M4A/M4B/MP4; Media3 1.11) | 06 | same |
| 5 | `YOUTUBE_DESC` | 06 via 04's `YouTubeChapters.parse(description, durationMs)` | When a YouTube item becomes current and no rows of priorities 1–4 exist ([04 Chapters from the description](04-youtube.md#chapters-from-the-description)) |

Writers replace all rows of one `(episodeId, source)` pair per transaction ([02 chapter](02-data-model.md#chapter)). Each source is written at most once per item per pin (ID3/MP4 rows only when the extracted list differs from the stored one).

### Podcasting 2.0 JSON

`Podcasting20ChaptersParser` (`:playback:impl`, `Json.parseToJsonElement`, no generated serializers): fetch `chaptersUrl` with `@HttpClient(HttpClientKind.API)` (8 s call timeout), body cap 1 MiB, accept `application/json+chapters` and `application/json` (and anything that parses). Rules: `version` must be a string and `chapters` an array, else reject; each chapter needs a numeric `startTime` ≥ 0 (seconds, float) else that chapter is dropped; `title`, `img`, `url` optional (`img`/`url` resolved against the chapters URL, only `http(s)` kept); `toc: false` → `hidden = true`; `endTime` → `endMs`; `location` ignored. Sort by start, drop duplicate starts (keep first), `endMs` defaults to the next start; cap 1,000 chapters. Failure → no rows; retried the next time the item becomes current, at most once per 24 h per episode (in-memory). When ingestion sees `chaptersUrl` change it deletes that episode's `PODCASTING20_JSON` rows (requested from 03).

### Embedded chapters

In `onTracksChanged(tracks)` for the current item: iterate `tracks.groups` → `getTrackFormat(i).metadata` entries that implement Media3's `androidx.media3.extractor.metadata.Chapter` (`ChapterFrame` for ID3 → `ID3`; MP4/Matroska entries → `MP4`); `startMs`, `endMs` (`C.TIME_UNSET` → null), `title`, `isHidden`. Times are relative to the period (offset 0 for progressive media). For downloads, `ChapterRepository.ensureLoaded(episodeId, localFileUri)` reads the same metadata with `androidx.media3.inspector.MetadataRetriever` (moved to `media3-inspector` in 1.11) so chapters exist offline before first play (Unverified: the 1.11.1 `MetadataRetriever` method names; M5 adapts).

### Current chapter and commands

`Chapters` observes `ChapterRepository.observe(currentId)`, finds the chapter containing the position by binary search, and schedules the next evaluation at `(nextStartMs − positionMs) / speed` while playing; it re-evaluates on discontinuities, speed changes and chapter-list changes, and publishes `CurrentChapter` (no per-second ticker). `nd.CHAPTER_NEXT` → seek to the next visible chapter start (none → no-op); `nd.CHAPTER_PREV` → position − current start > 3 s ? current start : previous visible start. Chapter list taps and show-notes timestamps ([03 Timestamp linkifier](03-feeds-and-discovery.md#timestamp-linkifier)) call `seekTo` when the episode is current, else `playEpisodeAt(episodeId, positionMs)` (M5 acceptance 5). Chapter titles in the notification and Auto (wrapping `MediaMetadata.subtitle`) are v1.x (M13).

---

## Notification and media buttons

Serves R4.7, R5.2. Delivered in M4 (notification), M5 (hardware button setting). Honours [PO-20](../PLAN.md#48-further-product-owner-decisions).

`DefaultMediaNotificationProvider.Builder(this).setChannelId("playback").setChannelName(R.string.channel_playback).setNotificationId(NOTIF_ID_PLAYBACK).build()` with `setSmallIcon(R.drawable.ic_stat_neutrodyne)` (monochrome vector in `:playback:impl`, replaced by the brand asset in M10). `PlaybackChannels` (initializer order 10) creates `playback` (IMPORTANCE_LOW) and `alerts` (IMPORTANCE_DEFAULT, "App alerts").

`MediaButtons.current()` (media button preferences; on API 33+ System UI builds controls from the session):

| Order | Slot | Command | Icon | Label |
|---|---|---|---|---|
| 1 | `SLOT_BACK` | `COMMAND_SEEK_BACK` | `ICON_SKIP_BACK_{5,10,15,30}` matching the interval, else `ICON_SKIP_BACK` + custom icon | "Back {n} seconds" |
| 2 | `SLOT_FORWARD` | `COMMAND_SEEK_FORWARD` | `ICON_SKIP_FORWARD_{5,10,15,30}`, else `ICON_SKIP_FORWARD` + custom icon | "Forward {n} seconds" |
| 3 | `SLOT_OVERFLOW` | session command `nd.SPEED_CYCLE` | `ICON_PLAYBACK_SPEED_{0_5,0_8,1_0,1_2,1_5,1_8,2_0}` matching the speed, else `ICON_PLAYBACK_SPEED` + custom icon | "Speed {x}×" |
| 4 | `SLOT_OVERFLOW` | `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` | `ICON_NEXT` | "Next episode" |

System UI (API 33+) shows play/pause, then `SLOT_BACK`, `SLOT_FORWARD`, then overflow buttons; the compact layout (lock screen, Wear, partly Auto) shows the first three — hence seek back/forward in slots 2–3. Changes of skip intervals or effective speed call `session.setMediaButtonPreferences(MediaButtons.current())` on main.

### Hardware buttons and SessionPlayer

`SessionPlayer : ForwardingSimpleBasePlayer(exo)` is the player given to the session (own UI, notification, Bluetooth, Auto all go through it); 06's components keep using `exo` directly. Unverified: `ForwardingSimpleBasePlayer` behaviour in 1.11.1 is checked on day one of M4; fallback is `ForwardingPlayer` with the same overrides.

| Override | Behaviour |
|---|---|
| `handleSeek(…, COMMAND_SEEK_TO_NEXT)` (headset/steering-wheel next, AVRCP skip) | `playback.hardware_buttons = EPISODE` (default): next episode; `SKIP`: `seekForward()` |
| `handleSeek(…, COMMAND_SEEK_TO_PREVIOUS)` | `EPISODE`: [previous-episode rule](#transitions); `SKIP`: `seekBack()` |
| `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` (our UI, notification "Next episode") | Always next episode |
| `getState()` | Advertises `COMMAND_SEEK_TO_NEXT`/`PREVIOUS` whenever an item is loaded (so hardware keys work with a one-item window); removes repeat and shuffle commands |
| `handleSetPlayWhenReady(true)`, `handlePrepare()` on an empty playlist | Returns a future that loads the window from the database (or starts Up next), then prepares and plays |
| `handleSetPlayWhenReady(true)` | Applies smart resume and the metered gate (consent under `ASK`, refusal under `NEVER`) |

Headset single/double/triple clicks are mapped by Media3 to play-pause/next/previous (Unverified for every head unit: some AVRCP passthroughs arrive as transport controls rather than key events — both paths end in `SessionPlayer`, so the setting applies either way).

---

## System surfaces

Serves R4.7, R5.2. Delivered in M4 (lock screen, System UI, Bluetooth metadata), M5 (resumption, Auto, Assistant). Honours [D42](../PLAN.md#3-key-decisions), [D64](../PLAN.md#3-key-decisions).

| Surface | Mechanism | Our work |
|---|---|---|
| Lock screen, quick-settings media carousel (API 33+ builds controls from the session) | Session metadata + media button preferences | `durationMs` and `content://` artwork in metadata; airplane-mode cover from the artwork store (M4 acceptance 5) |
| Bluetooth / AVRCP | Session (transport, metadata); AVRCP browsing needs `MediaLibraryService` (fixed for API 36/37 in 1.11.0) | Title + artist set; browse tree as below |
| Wear OS phone-media controls | Automatic from the session | none |
| Android Auto | Browse tree, search, completion/download extras | Below |
| AAOS | Same service would work, but distribution needs Play for cars | Not built in v1.0 ([D64](../PLAN.md#3-key-decisions)) |
| Resumption card after reboot | `MediaLibraryService` recent root → `onPlaybackResumption(isForPlayback = false)` | Below |
| Assistant "play X on Neutrodyne" | `MEDIA_PLAY_FROM_SEARCH`, `onSetMediaItems` with `requestMetadata.searchQuery` | Below |

### Android Auto and Assistant

`MediaLibraryTree` (`onGetLibraryRoot`, `onGetChildren`, `onGetItem`, `onSearch`, `onGetSearchResult`) reads the database through DAOs on IO and builds items with `MediaItemFactory`. Browse items carry `EXTRAS_KEY_CONTENT_STYLE_*` hints (lists for episodes, grid for podcasts).

| Media ID | Children (≤ 100 playable items per node, paged by `page`/`pageSize`) |
|---|---|
| `root` | `upnext`, `groups`, `downloads`, `podcasts` (if `EXTRAS_KEY_ROOT_CHILDREN_LIMIT` < 4, drop `podcasts`, then `downloads`) |
| `upnext` | current + Up next as `episode:{id}@upnext` |
| `groups` | browsable `group:{groupId}` in `sortOrder` (artwork: the group mosaic `g-{groupUuid}` from M10, before that the first member's cover) |
| `group:{groupId}` | the group's context-eligible episodes (05's Play-group set: unplayed, available, playable, group filters and `hideOlderThanDays`) in `playOrder`, as `episode:{id}@group:{groupId}` |
| `downloads` | completed downloads, newest first, `episode:{id}@downloads` (empty until M6) |
| `podcasts` | browsable `podcast:{podcastId}` by title |
| `podcast:{podcastId}` | unplayed and in-progress episodes in the podcast's `episodeOrder`, `episode:{id}@podcast:{podcastId}` |

`onSetMediaItems(items, startIndex, startPositionMs)`: parse `episode:{id}@{parent}`; parent `group:`/`podcast:`/`downloads` → start that context at `id` (`GROUP`/`PODCAST`/`DOWNLOADS`, order and filters per 05) — M5 acceptance 4; parent `upnext` or none → `playEpisode` semantics (context kept). Several items without a parent → first becomes current, the rest are added to the front of Up next, context `EXTERNAL` (no tail). `requestMetadata.searchQuery`: empty → resume; else exact case-insensitive group name → play group; else first podcast whose title contains the query → play podcast; else error result. `onSearch` returns matching groups and podcasts (≤ 20 each) as browsable items. Browse actions (download, mark played, add to Up next) are v1.x (M13). Sideloaded and F-Droid installs appear in Auto only with Auto's developer option "Unknown sources".

### Resumption

`ResumptionProvider.onPlaybackResumption(session, controller, isForPlayback)` (M5), from the database only — never the network:

- `isForPlayback = false` (System UI card at boot): one item for `currentEpisodeId` with metadata, `content://` artwork and completion extras, start position from `episode_position`. The call awaits the database open (`DatabaseOpener.awaitOpen()`, which runs migrations first) — never a raw query on an unmigrated file.
- `isForPlayback = true` (media key or Bluetooth play while the service is dead, or a tap on the card): the full window for the stored session, start index 0, `StartPositionRule` position; effective settings applied before Media3 prepares.
- No session → failed future (`UnsupportedOperationException`); Media3 ignores the request.

`MediaButtonReceiver` (declared in M5) starts the service for media keys; nothing is ever started from `BOOT_COMPLETED`.

---

## Video

Serves R4.7. Delivered in M5 (audio-only), M14 (video surface, PiP, YouTube video). Honours [D64](../PLAN.md#3-key-decisions), PO-9 (YouTube audio-only).

**v1.0: video plays as audio.** Video and text track types are disabled in `trackSelectionParameters` for the whole session, so no video decoder runs and the screen can be off (M5 acceptance 6). Muxed MP4 bytes are still downloaded; to save bandwidth the resolver prefers the first `episode_alt_enclosure` with an `audio/*` type and an `http(s)` source for `isVideo` episodes (Podcasting 2.0 `alternateEnclosure`; requested as part of the media lookup from 02). 08 shows a video badge from `episode.isVideo`. YouTube is always audio-only (itags per [D52](../PLAN.md#3-key-decisions)).

**v1.x plan (M14).** `:feature:player` adds `media3-ui-compose` `PlayerSurface` and receives the session `Player` under a qualifier declared in `:playback:api` ([01 Dependency injection](01-foundation.md#components-and-scopes) rule 4); the video track is enabled while a surface is attached or in PiP and disabled otherwise (short catch-up stall accepted); PiP via `setPictureInPictureParams(autoEnterEnabled = true, aspectRatio)` on API 31+ and `onUserLeaveHint` → `enterPictureInPictureMode()` on 26–30 (`core-pip` is alpha and not used); `MainActivity` `configChanges` revisited ([01 Attribute decisions](01-foundation.md#application-element-and-components)). YouTube video mode needs adaptive video + audio streams merged by a custom lazy `MediaSource` (`MergingMediaSource`); muxed progressive is often only 360p. Auto stays audio-only.

---

## Background restrictions

Serves N2, N7. Delivered in M4, verified in M5 and M11. Honours [D43](../PLAN.md#3-key-decisions); risk [T4](../PLAN.md#8-risks-and-mitigations). Compliance table rows P5, P6, P11–P13 of [01 Platform compliance](01-foundation.md#platform-compliance) point here.

| Rule (version, scope) | Our mechanism | Source |
|---|---|---|
| Android 12+: background FGS starts are restricted; notification, widget and media-button interactions are exempt | Playback starts only from visible UI, the notification, media keys; continuous playlist so auto-advance never restarts the FGS; `onForegroundServiceStartNotAllowedException` → Tap to resume | [Background FGS start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) |
| Android 14: FGS type `mediaPlayback` with `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Declared in the service manifest entry | [FGS service types](https://developer.android.com/develop/background-work/services/fgs/service-types) |
| Android 15 (target 35): `mediaPlayback` FGS cannot start from `BOOT_COMPLETED` | No boot receiver in playback; the resumption card is passive until tapped | [Android 15 behaviour changes](https://developer.android.com/about/versions/15/behavior-changes-15) |
| Android 15 (target 35): audio focus only for the top app or an app running an FGS | Focus is requested by ExoPlayer at `play()`, which happens in visible UI, in a running FGS (auto-advance) or from a media key | [Android 15 behaviour changes](https://developer.android.com/about/versions/15/behavior-changes-15) |
| Android 16: job quotas apply to jobs running beside an FGS | Not a playback concern; refresh and downloads are resumable (03, 07) | [Android 16 behaviour changes](https://developer.android.com/about/versions/16/behavior-changes-all) |
| Android 17 (all apps): playback, focus and volume need a visible activity or a non-`shortService` FGS; target 37: the FGS needs while-in-use capability; violations are **silently muted** | Start paths below; never start audio from workers, alarms, receivers (except media buttons) or "download finished"; no auto-play on Bluetooth connect | [Android 17 background audio](https://developer.android.com/about/versions/17/changes/bg-audio), [Android 17 changes (all apps)](https://developer.android.com/about/versions/17/behavior-changes-all) |
| Media-session notifications are exempt from `POST_NOTIFICATIONS` | No permission prompt for playback; "Tap to resume" needs it and degrades to in-app state | [Notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission) |

**Allowed start paths:** (1) `MediaController.play()` from visible UI (`PlaybackController.play*`, guarded by `PlayerConnection` refusing to connect unless the process is STARTED); (2) notification buttons and the Tap-to-resume action; (3) media keys, headset and Bluetooth/AVRCP (`MediaButtonReceiver` when the service is dead); (4) Android Auto and the System UI resumption card (platform media surfaces; Unverified: their while-in-use grant under Android 17 — covered by the M5 device checklist); (5) auto-advance and resume after transient focus loss or a network drop **while the FGS is still running** (≤ 10 min after a pause or error). Everything else waits for the user. Out of scope: alarm wake-up podcasts (needs `USAGE_ALARM` and exact alarms), auto-play on connect, play-when-downloaded.

**Demotion.** 10 min after a pause, error or end Media3 demotes the service (notification stays). A later background play request is refused by the platform and lands in Tap to resume.

**Testing hooks.** `adb shell cmd audio set-enable-hardening throw` on an API 37 image turns silent muting into exceptions (M4 acceptance 4); `adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS app.neutrodyne` with a reboot test for the resumption card (M5).

---

## UI boundary

Serves R4.7, R4.8, N4. Delivered in M4. Answers [01 Open questions](01-foundation.md#open-questions) item 4.

### PlayerConnection

```kotlin
@Singleton
class PlayerConnection @Inject constructor(@ApplicationContext private val ctx: Context) : DefaultLifecycleObserver {
    val controller: StateFlow<MediaController?>                     // main thread only
    @MainThread suspend fun awaitController(timeoutMs: Long = 5_000): MediaController?
    override fun onStart(owner: LifecycleOwner)                     // MediaController.Builder(ctx, token).buildAsync()
    override fun onStop(owner: LifecycleOwner)                      // MediaController.releaseFuture(f), value = null
}
```

Registered on `ProcessLifecycleOwner` by an `AppInitializer` (order 300, switches to main). `awaitController` returns null when the process is below STARTED (enforcing [D43](../PLAN.md#3-key-decisions)) or after 5 s. Releasing at process `onStop` matters: a leaked controller keeps the service bound, so it could never stop after the user pauses and leaves. Needs `lifecycle-process` in `:playback:impl` (requested from 01).

### PlaybackControllerImpl

| Call | Path |
|---|---|
| `playEpisode`, `playEpisodeAt`, `playFeed` | `sendCustomCommand(nd.PLAY_CONTEXT)` (10 s timeout), then `controller.play()` ([Starting playback](#starting-playback)) |
| `play()` | `prepare()` if idle, then `play()` |
| `pause`, `seekTo`, `skipBack`, `skipForward`, `skipToNext`, `skipToPrevious` | `MediaController` methods (`pause`, `seekTo`, `seekBack`, `seekForward`, `seekToNextMediaItem`, `seekToPrevious`); if no controller is connected (background caller such as 03's unsubscribe), `pause` goes to `ServiceBridge` (in-process handle; pausing never starts an FGS); other transport calls are dropped |
| `setSpeed`, `setSkipSilence`, `setSleepTimer`, `extendSleepTimer`, `nextChapter`, `previousChapter`, `dismiss` | `nd.SPEED_SET_SCOPE`, `nd.SKIP_SILENCE`, `nd.SLEEP_SET`, `nd.SLEEP_EXTEND`, `nd.CHAPTER_NEXT`/`PREV`, `nd.DISMISS` |
| `grantMeteredStreaming` | `MeteredStreamingGate.grant()` (same process) |

`nd.DISMISS`: save position, `SessionWriter.clearCurrent()` (current and context NULL), clear the player, `pauseAllPlayersAndStopSelf()`. Used by the mini player's swipe-to-dismiss (08).

### PlaybackStateHub

`PlaybackStateSource` is served in-process by `PlaybackStateHub`, written by the service's components (`ServiceBridge`) on main and read by any collector:

| Flow | Source | Rate |
|---|---|---|
| `nowPlaying` | Service running: rebuilt in `onEvents` for is-playing, playback-state, play-when-ready, discontinuity, parameters, item-transition, timeline and error events. Service not running: derived from `observeSession()` + media lookup + `PositionDao.observeFor(listOf(current))` with `phase = NOT_LOADED` (the mini player shows the last episode, ready to resume) | event-driven; `stateIn(@ApplicationScope, WhileSubscribed(5_000))` |
| `positionTicks` | `nowPlaying.position.positionAt(now)` — computed from the snapshot, no player access | 1 Hz while `advancing`, plus one emission per state change |
| `sleepTimer`, `currentChapter`, `effectivePlayback` | `SleepTimer`, `Chapters`, `EffectivePlaybackApplier` | event-driven |
| `events` | `ErrorRecovery`, `PositionTracker`, `SleepTimer` | `MutableSharedFlow(extraBufferCapacity = 8)` |

The full player's scrubber interpolates every frame with `PositionSnapshot.positionAt(frameTimeElapsedMs)` (no 60 Hz flow). 08's `EpisodeLiveStateSource` combines `nowPlaying` (is-now-playing, is-playing) and `positionTicks` for the playing row with 02's `IN (:ids)` position query for the others ([08 Live row state](08-ui-ux.md#live-row-state)).

### No Media3 in features (v1.0)

`:feature:player` and `:feature:queue` depend only on `:playback:api` and `:core:domain` ([D12](../PLAN.md#3-key-decisions)); they are tested with `:core:testing` fakes and need no Robolectric `Player`. `media3-ui-compose` state holders are replaced as follows; the library enters `:feature:player` only in M14 for `PlayerSurface`.

| `media3-ui-compose` holder | Neutrodyne equivalent |
|---|---|
| `rememberPlayPauseButtonState` | `NowPlaying.isPlaying` / `playWhenReady` / `phase` + `play()` / `pause()` |
| `rememberProgressStateWithTickInterval` | `NowPlaying.position.positionAt(…)` + `positionTicks` |
| `rememberPlaybackSpeedState` | `effectivePlayback` + `setSpeed` |
| `rememberNextButtonState`, previous | `NowPlaying.hasNext` + `skipToNext` / `skipToPrevious` |
| seek back / forward states | `skipBack` / `skipForward`; labels from `playback.skip_back_ms` / `skip_forward_ms` via `SettingsRepository` |

### Threading rules

| Code | Thread | Rule |
|---|---|---|
| Player, session, `SessionPlayer`, projector diffs, listeners, `SleepTimer`, `Chapters` | Application main looper (Media3 1.11 makes session getters throw elsewhere) | Never block; DB and network via `withContext(IO)` and back |
| `MediaController` calls | Main (`PlaybackControllerImpl` switches with `withContext(Dispatchers.Main.immediate)`) | — |
| DB reads and writes | Room's IO context | One write transaction per transition, start or save; no I/O inside |
| `MediaItemFactory`, `ArtworkStore.pinnedFile`, chapter fetch and parse | `@Dispatcher(IO)` | — |
| `EpisodeResolver.resolveDataSpec`, `GuardedHttpDataSource` | Media3 loader threads | Blocking allowed; in-memory index; `runBlocking` only for the DB fallback (5 s) and YouTube resolve (25 s) |
| `BoostLimiterProcessor`, `SilenceSkippingAudioProcessor` | Playback thread | Parameters are `@Volatile`, applied at buffer boundaries |
| Final position write in `onDestroy`, pre-resolve | `@ApplicationScope` | Survive service teardown |

---

## Settings

Serves R4.1, R4.8, R2.7. Keys follow [01 DataStore files and typed setting keys](01-foundation.md#datastore-files-and-typed-setting-keys) (`object PlaybackSettingKeys`). All are portable (`settings`, included in the backup whitelist); playback has no device-bound keys.

| Key | Type | Default | File | UI location | Milestone |
|---|---|---|---|---|---|
| `playback.speed` | Float32, 0.5–3.0 step 0.05 | 1.0 | `settings` | Settings › Playback › Default speed; speed sheet (GLOBAL scope) | M4 |
| `playback.skip_silence` | Bool | false | `settings` | Settings › Playback; speed sheet | M4 |
| `playback.speed_presets` | Text (comma-separated) | `0.8,1.0,1.2,1.5,1.8,2.0` | `settings` | Speed sheet › Edit presets | M4 |
| `playback.skip_back_ms` | Int64, one of 5/10/15/20/30/45/60 s | 10 000 | `settings` | Settings › Playback | M4 |
| `playback.skip_forward_ms` | Int64, one of 10/15/20/30/45/60/90 s | 30 000 | `settings` | Settings › Playback | M4 |
| `playback.pause_for_navigation` | Bool | true | `settings` | Settings › Playback ("Pause for navigation prompts") | M4 |
| `playback.stream_on_metered` | Choice `ALLOW`/`ASK`/`NEVER` | `ALLOW` | `settings` | Settings › Playback ("Streaming on mobile data") | M4 |
| `playback.stream_cache_mb` | Int32, one of 100/250/500/1000/2000 | 500 | `settings` | Settings › Playback › Storage (+ usage and "Clear streaming cache") | M4 |
| `playback.hardware_buttons` | Choice `EPISODE`/`SKIP` | `EPISODE` | `settings` | Settings › Playback ("Headset next/previous") | M5 |
| `playback.smart_resume` | Bool | true | `settings` | Settings › Playback ("Rewind 5 s after long pauses") | M5 |
| `playback.sleep_last_minutes` | Int32, 1–240 | 30 | `settings` | Sleep sheet (last choice) | M5 |

Per-podcast and per-group overrides (`podcast_settings.playbackSpeed`, `.skipSilence`; same in `podcast_group_settings`; `boostDb`, `introSkipMs`, `outroSkipMs` reserved for M12) are edited on 05's settings screens and from the player ([Per-scope playback settings](#per-scope-playback-settings)). YouTube audio preferences are 04's (`youtube.audio_quality`, `youtube.volume_levelling`).

---

## Testing

Serves N1, N2, N11; risk [M3r](../PLAN.md#8-risks-and-mitigations) (Media3 `@UnstableApi` churn). Runners and CI wiring: [09 Test strategy](09-quality-and-release.md#test-strategy). Robolectric tests use `media3-test-utils(-robolectric)` (`TestExoPlayerBuilder`, `FakeClock`, `TestPlayerRunHelper`, `FakeMediaSource`) and an in-memory `TestDb` with `AndroidSQLiteDriver`.

| Test class | Runner | Cases | Milestone |
|---|---|---|---|
| `WindowDiffTest` | JVM, TestParameterInjector | equal lists → no ops; current changed → one `setMediaItems`; next item reordered → exactly one `moveMediaItem`, index 0 untouched; insert/remove in the tail; > 30 ops → `replaceMediaItems(1, …)`; metadata-only → `replaceMediaItem`; fingerprint change on current deferred, on non-current re-added | M4 |
| `QueueProjectorTest` | Robolectric | M4 acceptance 1: DB → player → DB loop converges with one `setMediaItems` per start; stale-generation snapshot during an own write dropped; YouTube items filtered when `inAppPlayback = false`; Up next cap 100; unavailable Up next items skipped; `onSetMediaItems` writes the DB first | M4, M8 |
| `QueueRepositoryImplTest`, `SessionWriterTest` | Robolectric + `TestDb` | Play group with two Up next items → current = first, anchor null, then group tail newest first, played ones skipped (M4 acceptance 6); start with `startEpisodeId`; nothing to play; `addNext`/`addLast` move duplicates; reject current, YouTube in `play`, unavailable; `move` + renormalise; AUTO marks played and moves the anchor; SEEK below threshold does not mark; marking played removes from Up next; previous re-queues current; `generation` increments; `endSession` | M4 |
| `PositionTrackerTest` | Robolectric + `FakeClock` + `TestDb` | M4 acceptance 2: saves on 5 s tick, pause, seek, transition (outgoing value), destroy; non-zero never replaced by 0 except reset/played; start seek never leaves a small value; played-after-start guard; `positionSource`; `startedAt` once; seek to < 1 s resets | M4 |
| `PlayedRuleTest`, `StartPositionRuleTest` | JVM table | durations unknown / 20 s / 20 min / 3 h; threshold max(30 s, 3 %); never-started item; saved ≤ 5 s, ≥ duration − 30 s, played, smart-resume rewind | M4, M5 |
| `EpisodeResolverTest` | Robolectric, fakes for `LocalMediaIndex` and `YouTubeStreamResolver` | local wins; remote pin survives a download completing; new pin resets the cache resource; final URL reused and cleared on 404; YouTube key `yt:id:itag`; itag change → `YouTubeFormatChangedException`; `play` → `Unsupported`; index miss → DB fallback; interrupt → `InterruptedIOException` | M4, M6, M9 |
| `GuardedHttpDataSourceTest` | Robolectric + MockWebServer | `Accept-Encoding: identity` sent; total-length change → `ContentChangedException`; googlevideo 403 → `invalidate` + rethrow, ≤ 2 per 60 s; redirect chain → final URL recorded | M4, M9 |
| `ErrorRecoveryTest` | Robolectric, `FakeMediaSource` errors | every row of [Error recovery](#error-recovery); 3 re-prepares per 2 min; 5 consecutive skips → pause; `Skipped` events | M4, M6, M9 |
| `MeteredStreamingGateTest` | JVM | ASK → consent result; grant cleared on unmetered; NEVER blocks; local bypass; arbiter flag combines sleep EOE and metered | M4 |
| `EffectivePlaybackApplierTest` | Robolectric, fake resolver | applied before first `prepare`; on transition; group value only when the podcast is a member (M4 acceptance 7 attribution); scope write clears more specific overrides; external speed not persisted | M4 |
| `SessionCallbackConnectTest`, `SessionPlayerTest` | Robolectric (`MediaController` against the session) | notification controller lacks prev/next; untrusted controller read-only; hardware next/previous per setting; lazy window load on empty play; smart resume | M4, M5 |
| `SleepTimerTest` | Robolectric + `FakeClock` | M5 acceptance 1: counts only while playing; 20-step fade over 10 s; pause restores volume; extend; EOE pauses at the end, marks played, clears the flag | M5 |
| `Podcasting20ChaptersParserTest`, `ChaptersTest` | JVM; Robolectric with media fixtures | M5 acceptance 2: JSON with `toc: false`, relative `img`, unsorted, missing `version` rejected; PSC; ID3 MP3; M4B; first non-empty source wins; current-chapter boundaries; next/prev | M5 |
| `MediaLibraryTreeTest`, `ResumptionTest` | Robolectric (`MediaBrowser`) | root ≤ limit; children IDs and paging; `episode:{id}@group:7` sets the group context; search; resumption at boot returns one item with `content://` art and MockWebServer records zero requests | M5 |
| `PlaybackServiceTest` (instrumented, GMD API 26/34/36/37) | `MediaController` against the real service with a MockWebServer enclosure | play/pause/seek; headphone-unplug and focus-loss simulation (M4 acceptance 8); 30 min background auto-advance with screen off, no FGS exception (M4 acceptance 3, nightly); hardening `throw` on API 37 for UI, notification and media-key starts (M4 acceptance 4) | M4 |

Fixtures in `playback/impl/src/test/resources/media/`, generated by `scripts/fixtures/make-playback-fixtures.sh` with ffmpeg from synthetic tones (no third-party audio): `cbr_128k_60s.mp3` (no Xing header), `vbr_xing_60s.mp3`, `id3_chap_3.mp3`, `m4b_nero_3.m4b`, `video_10s.mp4` (H.264 + AAC), `silence_gaps_30s.mp3`; `chapters_pc20_full.json`, `chapters_pc20_invalid.json`. Unverified: ffmpeg's ID3 `CHAP` writing for MP3; fall back to a hand-built ID3 tag in the script.

**Manual device matrix** (M5, repeated before M11; recorded in the PR): Bluetooth headset (play, next, double-tap) with both `hardware_buttons` values; an AVRCP car head unit and the Android Auto Desktop Head Unit (sideload: "Unknown sources"); Wear watch; lock screen; phone call; navigation prompt (SPEECH pause vs MUSIC duck); wired-headphone unplug; airplane mode mid-stream; a 7-hour pause on a YouTube item (URL expiry, `foss`); deleting a download while it plays; reboot → resumption card with art and no network.

---

## Delivery by milestone

| Milestone | Delivered in this area |
|---|---|
| [M0](../PLAN.md#m0-scaffold-and-ci) | `:playback:api` interfaces and data types; `QueueRepository`/`ChapterRepository` interfaces and `:core:model` types; `:core:testing` fakes; `:playback:impl` compiling stub |
| [M4](../PLAN.md#m4-playback-core) | `NeutrodynePlaybackService` with `MediaLibrarySession` (root + `upnext` only), connection policy, `PlayerFactory`, `NdMediaSourceFactory`, `EpisodeResolver` (remote branch; local returns nothing until M6; YouTube throws `Unsupported` until M9), `GuardedHttpDataSource`, `SimpleCache` + `AdjustableLruCacheEvictor`, `QueueRepositoryImpl`, `SessionWriter`, `QueueProjector`, `PositionTracker`, played rule, `StartPositionRule`, `EffectivePlaybackApplier` (speed, skip silence), `MeteredStreamingGate`, `ErrorRecovery`, `MediaButtons`, `SessionPlayer` (lazy load, metered), `PlaybackChannels`, Tap to resume, `PlayerConnection`, `PlaybackControllerImpl`, `PlaybackStateHub`; settings `playback.speed` … `stream_cache_mb` |
| [M5](../PLAN.md#m5-playback-features-and-system-surfaces) | `SleepTimer`; `Chapters` (P2.0 JSON, PSC, ID3, MP4), chapter commands, timestamp seeks; `MediaButtonReceiver`, `ResumptionProvider`; hardware-button setting and history; smart resume; measured duration; full `MediaLibraryTree` with extras, `MEDIA_PLAY_FROM_SEARCH`; video-as-audio and audio alternates |
| [M6](../PLAN.md#m6-downloads) | Local branch through `LocalMediaIndex`, re-pin of newly local window items, missing-file recovery and `reportFileMissing`, `ChapterRepository.ensureLoaded` with `MetadataRetriever`, Auto download extras and `downloads` node content |
| [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds) | YouTube items excluded from projection, Up next and Auto in `play` (and temporarily in `foss`); avatar artwork for YouTube items |
| [M9](../PLAN.md#m9-youtube-playback-and-downloads-in-foss) | YouTube branch per 04, 403/410 self-heal, `PreResolver`, error mapping, description chapters |
| [M10](../PLAN.md#m10-covers-theming-adaptive-layouts-and-accessibility) | Group mosaics as Auto group artwork; brand small icon |
| [M11](../PLAN.md#m11-release-hardening-and-v10) | Device matrix re-run, hardening and boot tests on minified `fossRelease`, Play `mediaPlayback` FGS declaration text (with 09) |
| M12 / M13 / M14 | Boost, intro/outro skip, end-of-chapter timer, shake (M12); Auto polish, browse actions, chapter subtitle, widgets, `:playback:cast` in `play` (M13); video surface, PiP, YouTube video mode (M14) |

---

## Open questions

1. **Architect review:** D45 says playback settings come from "the group of the current play context". This document applies the group level only when the current item's podcast is a member of that group (an Up next item from another podcast does not inherit, e.g., the news group's 1.5×). 05 should state the same rule; otherwise PLAN D45 needs the wording.
2. **Architect review:** to honour DAI safety (risk T7), every new RSS pin starts with an empty `SimpleCache` resource for its key, so streamed bytes are reused only within one playback (rewind, skip back, retries, short gaps), not across sessions. D40's rationale still holds; cross-session reuse remains for YouTube keys only.
3. **Architect review:** "Play group" with a non-empty Up next makes the Up next head current (D44, M4 acceptance 6) — the item that was playing is interrupted. `playEpisode` keeps the existing context; `playFeed` with `startEpisodeId` plays that episode, then Up next, then the context. 08 must choose `playFeed(source, filters, group playOrder, startEpisodeId)` for a row's Play button in a feed.
4. **Architect review:** writing a speed or skip-silence scope from the player clears the same setting at more specific scopes on the current chain (so the chosen value takes effect). 05 owns attribution; confirm it is acceptable that "Apply to all podcasts" removes the current podcast's override.
5. **Owner 02:** add `EpisodeDao.observeMediaInfo(ids)` (Flow), and to `mediaInfo` the columns `enclosureLength`, `availability`, `playedAt`, `artworkVersion`, `podcastArtworkVersion`, download state, and the first audio alternate enclosure; add one-shot list queries for the Auto tree (`FeedDao.contextList(query, limit, offset)`, completed downloads page, podcasts by title, title/name search); `EpisodeStateDao.markUnplayed` must also clear `startedAt`; every mark-played statement also deletes from `queue_entry`.
6. **Owner 03:** `EpisodeRepository.setPlayed`/`markFeedPlayed` delete the episodes from `queue_entry` in the same transaction and reset positions (06 rules above); ingestion deletes `PODCASTING20_JSON` chapter rows when `chaptersUrl` changes.
7. **Owner 07:** `DownloadController.reportFileMissing(episodeId)`; defer file deletion of `play_session.currentEpisodeId` until it changes; call `ChapterRepository.ensureLoaded(id, finalUri)` after `COMPLETED` (best effort).
8. **Owner 01:** add `lifecycle-process` and `kotlinx-serialization-json` to `:playback:impl`; `:feature:player` gets no Media3 artifact in v1.0 (M14 adds `media3-ui-compose`); `media3-ui-compose` stays in the catalog unused until M14.
9. **Owner 04 / 01:** channel `alerts` is created by `:playback:impl`'s `PlaybackChannels`; 04's `YouTubeAlertNotifier` (in `:core:data`) must create it with the identical ID, importance and name before posting.
10. Unverified (resolved during M4/M5 by tests): `ForwardingSimpleBasePlayer` in 1.11.1; `MediaConstants` completion/download extra names; `MetadataRetriever` API in `media3-inspector`; Media3's handling of an `ACTION_MEDIA_BUTTON` start intent for the Tap-to-resume action; whether Media3 keeps the FGS during a transient focus loss; Android 17 while-in-use for Auto and the resumption card; the tuned `SilenceSkippingAudioProcessor` parameters; MP3 seek accuracy magnitude on VBR files with coarse Xing TOCs.
11. PO (PO-20 follow-ups): replaying a played episode marks it unplayed at the first second of playback; external play (notification, Bluetooth) under "Ask" counts as consent to stream on mobile data. Confirm both defaults.

---

## Sources

Checked 2026-10-04.

- Media3 releases and source: https://developer.android.com/jetpack/androidx/releases/media3 · https://github.com/androidx/media/blob/release/RELEASENOTES.md · https://github.com/androidx/media/tree/release (`MediaSessionService.java`, `MediaLibrarySessionImpl.java` recent-root resumption, `MediaNotificationManager.java` 10-min foreground timeout, `ProgressiveMediaPeriod.java`/`ProgressiveMediaSource.java` cache key and `canUpdateMediaItem`, `ResolvingDataSource.java` "allowed to block", `DefaultLoadErrorHandlingPolicy.java` backoff, `DefaultLoadControl.java` byte cap, `SilenceSkippingAudioProcessor.java`, `DefaultAudioSink.java` processor chain and offload bypass, `GainProcessor.java`, `AudioFocusManager.java` speech pauses, `SimpleCache.java` one instance per folder, `Mp3Extractor.java`, `CommandButton.java` icons and slots, `extractor/metadata/Chapter.java`, `id3/ChapterFrame.java`, `demos/session/src/main/AndroidManifest.xml`, `demos/session_service/DemoPlaybackService.kt`) · Google Maven metadata https://dl.google.com/android/maven2/androidx/media3/media3-exoplayer/maven-metadata.xml
- Session API: https://developer.android.com/reference/androidx/media3/session/MediaSession.Callback · background playback and resumption https://developer.android.com/media/media3/session/background-playback · System UI media controls https://developer.android.com/media/implement/surfaces/mobile
- Platform: https://developer.android.com/develop/background-work/services/fgs/service-types · https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start · https://developer.android.com/about/versions/15/behavior-changes-15 · https://developer.android.com/about/versions/16/behavior-changes-all · https://developer.android.com/about/versions/17/changes/bg-audio · https://developer.android.com/about/versions/17/behavior-changes-all · https://developer.android.com/about/versions/17/behavior-changes-17 · https://developer.android.com/develop/ui/views/notifications/notification-permission · https://developer.android.com/google/play/requirements/target-sdk
- Android Auto: artwork URIs https://developer.android.com/training/cars/media/create-media-browser/media-artwork · testing and "Unknown sources" https://developer.android.com/training/cars/testing
- Podcasting 2.0 chapters: https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/chapters.md · https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/examples/chapters/jsonChapters.md
- Prior art (behaviour only, no code copied): AntennaPod `ExoPlayerWrapper` (boost via `LoudnessEnhancer`, 100 MB `SimpleCache`, CBR seeking) https://github.com/AntennaPod/AntennaPod/blob/develop/playback/service/src/main/java/de/danoeh/antennapod/playback/service/internal/ExoPlayerWrapper.java · AntennaPod 3.12.0 playback rewrite https://github.com/AntennaPod/AntennaPod/releases/tag/3.12.0 and 3.12.2 position-reset fix https://github.com/AntennaPod/AntennaPod/releases/tag/3.12.2 · 3.12 beta regressions https://forum.antennapod.org/t/3-12-0-beta-bugs-in-playback-service-rewrite/8588 · Podcini FGS start failure on auto-next https://github.com/XilinJia/Podcini/issues/88 · NewPipe lazy YouTube sources https://github.com/TeamNewPipe/NewPipe/tree/dev/app/src/main/java/org/schabi/newpipe/player
- `core-pip` status: https://dl.google.com/android/maven2/androidx/core/core-pip/maven-metadata.xml
