# Research notes: UI / UX design with cover art

Scope: the visual language, information architecture, navigation, screen designs, colour, artwork pipeline (Coil), adaptive layouts, accessibility, onboarding and widgets for Neutrodyne. The product owner's requirement in focus is "a nice UI showing podcasts' graphical covers". Groups ("tech", "news", "fiction") each viewed as their own episode feed shape the navigation.

Checked on **2026-10-04**. Library versions were read from the Google Maven / Maven Central `maven-metadata.xml` files and the AndroidX release pages. API claims were checked against the **published sources jars** of the exact versions named (material3 1.4.0 and 1.5.0-alpha29, navigation-suite 1.4.0, foundation/ui/animation 1.12.1, navigation3-ui 1.2.0, activity-compose 1.13.0, glance 1.2.0) and the Coil and MaterialKolor repositories at HEAD. **[tested]** marks things I ran live from the research sandbox.

This file is consistent with the other research notes and depends on them:
- `stack.md`: BOM 2026.09.00, M3 stable, Navigation 3, Coil 3.6.3, minSdk 26 / targetSdk 37, one shared OkHttp client.
- `opml-groups.md`: many-to-many groups, the virtual "All" and "Ungrouped" feeds, group `colorArgb` / `iconKey` / 2×2 mosaic, keeping high-churn tables out of list queries.
- `playback.md`: an app-scoped `MediaController`, the `ArtworkProvider`, video on the Now Playing screen.
- `downloads.md`: the download state machine and `waitReason`.
- `feeds.md`: artwork candidate selection and the show-notes renderer.
- `youtube.md`: avatar, banner and thumbnail sources; the `play` flavour's "external episodes".

---

## Recommendation

### Decisions at a glance

| Concern | Choice for Neutrodyne |
|---|---|
| **Design system** | **Material 3 stable** (`material3` 1.4.0 via Compose BOM **2026.09.00**). The look is "cover-first": artwork carries the colour and the chrome stays quiet. **No Material 3 Expressive at launch.** It exists only in `material3:1.5.0-alpha29`, and that artifact also pulls **Compose foundation/ui/runtime 1.13.0-alpha01** into the app (verified in its POM). All visual decisions are wrapped in `:core:designsystem` so Expressive can be adopted in one place later. |
| **App chrome** | `NavigationSuiteScaffold` (`material3-adaptive-navigation-suite` 1.4.0) with its **default** `navigationSuiteType`: `ShortNavigationBarCompact` on compact width, `ShortNavigationBarMedium` on tabletop or short windows, and `WideNavigationRailCollapsed` otherwise (from the 1.4.0 source). Its `NavigationSuiteScaffoldState.hide()/show()` hides the bar while the full player is expanded. |
| **Top-level destinations (5)** | **Feeds · Library · Up next · Downloads · Discover.** Settings is a gear in each top-level top app bar, and the footer item of the wide rail. |
| **Switching between group feeds** | **`PrimaryScrollableTabRow` + `HorizontalPager`.** One page per feed: **All** first, then user groups in `sortOrder`, then **Ungrouped** (optional, off by default). You can swipe between groups. A trailing "All groups" button opens a sheet with a grid of group mosaics for quick jumps when there are many groups. **Filter chips** (Unplayed / Downloaded / In progress / Video + sort) filter *within* a feed; they are never used for choosing the group. |
| **Row swipe actions** | **Off by default in Feeds**, because they fight the pager's horizontal swipe. They are on in Up next, Downloads and Podcast detail. A setting can swap the roles of the two gestures. Every swipe action is also a long-press menu item and a TalkBack custom action. |
| **Library** | Adaptive cover grid: `LazyVerticalGrid(GridCells.Adaptive(100.dp))` gives 3 columns on 360–411 dp phones (16 dp gutters, 12 dp spacing), and about 4–5 on medium and 6–7 on expanded widths once the rail's width is subtracted. Users can choose a density of 72 / 100 / 152 dp minimum cell size. It has single-select **group FilterChips**, a `Podcasts | Groups` segmented button (Groups shows 2×2 mosaic tiles), and a long-press **selection mode** for bulk "Add to group…". |
| **Podcast detail** | Header tinted by the artwork (`SchemeContent` from the cover's seed). The 160 dp cover is a **shared element** from the grid tile or row thumbnail. Episode rows **omit** the thumbnail when the episode art equals the podcast cover. YouTube channels get the 6:1 channel banner behind the avatar. |
| **Player** | One root-level **`PlayerSheet`** (`AnchoredDraggableState<Collapsed\|Expanded>`). The 64 dp floating mini player morphs into the full player, driven by drag progress. The nav suite hides when expanded. `PredictiveBackHandler` collapses it with the gesture. Controls bind to the app-scoped `MediaController` through `media3-ui-compose` state holders. On ≥ 840 dp the full player is a **side panel** (supporting pane). In **tabletop** posture the art is above the hinge and the controls below it. |
| **Colour** | Three layers. (1) App scheme: wallpaper **dynamic colour** (API 31+, default on), otherwise a brand seed generated with MCU. (2) **Artwork-scoped schemes** (`SchemeContent`) for the full player, podcast header and the mini player tint. (3) Optional **pure-black** dark mode. Artwork seeds are **computed once off the UI thread and persisted**, never in composition. |
| **Colour library** | `com.materialkolor:material-color-utilities` **5.0.1**: a pure-Kotlin port of Google's Material Color Utilities (Apache-2.0) with no Compose dependency. We add a small mapper to `ColorScheme`. **Not** `androidx.palette` (it produces no M3 roles), and **not** the `material-kolor` Compose artifact (its 5.0.1 build is compiled against the M3 1.5 alpha). |
| **Image loading** | **Coil 3.6.3**, with a custom singleton `ImageLoader`: the app's OkHttp pool **without** OkHttp's own cache, a 20 % memory cache trimmed while backgrounded, and a 256 MB disk cache. It adds an `ArtworkRef` mapper (local file first), a YouTube-thumbnail interceptor, and **two cache tiers with explicit memory keys** (thumb, hero) so shared-element transitions never flash. |
| **Offline artwork** | **`ArtworkStore`** in `filesDir/artwork/`. Images are normalised to ≤ 1024 px and pinned for every subscription, for episode art of downloaded episodes, for group mosaics and for generated placeholders. Coil and playback's `ArtworkProvider` (lock screen, Auto, widgets) read the **same files**. |
| **Placeholder covers** | A generated **monogram cover**: up to two initials on a hue derived deterministically from the feed key, with tones chosen in HCT for ≥ 4.5:1 contrast. Compose draws it as a `Painter`, and it is rasterised once into `ArtworkStore` for system surfaces. |
| **Navigation** | **Navigation 3** 1.2.0 with one back stack per top-level tab. `adaptive-navigation3` 1.3.0 `ListDetailSceneStrategy` gives Library → Podcast → Episode and Feeds → Episode as two panes on ≥ 600 dp. |
| **Shared elements** | `SharedTransitionLayout { NavDisplay(sharedTransitionScope = this) }` for cover → header and row → episode transitions. **Not** for mini → full player, which is gesture-driven geometry inside one composable. |
| **Adaptive** | `currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)`. There is one layout table per width class (below). **Never lock orientation**: on API 36+ the lock is ignored on ≥ 600 dp, and the opt-out is gone for API 37 targets. |
| **Accessibility** | Each row is one merged node with a spoken summary and **custom actions** (Play, Download, Up next, Mark played). Touch targets are ≥ 48 dp. Text scales to 200 % with a stacked row layout at `fontScale ≥ 1.5`. Text is **never** drawn directly on artwork. `ui-test-junit4-accessibility` checks run in instrumented tests. |
| **Widgets** | Glance **1.2.0**. "Now playing" ships in v1; "Group feed" (latest unplayed in a chosen group) comes in v1.1. Artwork is passed as `ImageProvider(Icon.createWithContentUri(...))` from `ArtworkProvider`, so no bitmaps travel in the `RemoteViews` parcel (Android 17 enforces a bitmap memory cap). |
| **Icons** | Material **Symbols** vector drawables (Rounded, weight 400, fill toggled for the selected state), **not** `material-icons-extended`, which Google says is no longer maintained and slows builds. |

### Why, briefly

- **Covers make the product.** The requirement is about graphical covers, so every main surface shows art at a size where it reads:
  - Library: ~100–150 dp tiles.
  - Feeds rows: 56 dp.
  - Podcast detail: 160 dp.
  - Player: 280–480 dp.
  - Mini player: 48 dp.

  The chrome takes its colour from the art (artwork-scoped schemes) instead of competing with it. Expressive components (button groups, wavy progress, flexible app bars) would add polish, but they are not what makes a cover-forward app look good. Artwork size, artwork-derived colour, motion and typography are, and all of those are available on stable M3.
- **Expressive cost is higher than `stack.md` assumed.** Besides API churn (alpha29 changed `Slider`'s signature), the `material3-android:1.5.0-alpha29` POM depends on `foundation`, `ui`, `runtime` and `animation-core` **1.13.0-alpha01**. Adopting it moves the *whole* Compose stack to alpha, not just one component library. Stable 1.4.0 has none of `ButtonGroup`, `LoadingIndicator`, `LinearWavyProgressIndicator`, `HorizontalFloatingToolbar` or flexible top app bars, and `MaterialExpressiveTheme`, `MotionScheme` and `expressiveLightColorScheme` are `internal` there (checked in the 1.4.0 sources).
- **Tabs plus pager for groups.** The product owner wants "each group as a separate feed". Tabs say *place*; chips say *filter*. A pager gives the physical "swipe to the next feed" gesture that Feedly-, Google News- and Play-Store-style apps have taught users. Scrollable tabs handle arbitrary names and counts, and the trailing "All groups" sheet covers the long tail. A drawer would hide the groups, and it collides with `NavigationSuiteScaffold` on wide screens.
- **A root-level player sheet, not a nav destination.** Users drag the mini player up and down mid-gesture. An `AnchoredDraggableState` gives an interruptible, velocity-aware 0→1 progress, and that progress drives every morph (art size and position, corner radius, background alpha, nav bar hide). A navigation destination plus shared elements cannot follow a finger without a `SeekableTransitionState` setup, and it would put the player into each tab's back stack.
- **Precomputed seeds.** Quantising a bitmap costs tens of milliseconds and needs a software bitmap. Doing it once when the artwork is stored, and saving `seedArgb` plus `avgArgb` in the DB, means the player and detail headers have their colours on the first frame. It also gives a dominant-colour loading placeholder for every tile, which removes grey flashes in the grid.
- **A pinned artwork store.** Coil's disk cache is an LRU in `cacheDir`, which the OS may purge, so it cannot guarantee art for downloaded episodes offline or for the boot-time playback-resumption card (`playback.md` §15). A small pinned store fixes this, and it gives the lock screen, Android Auto and widgets the same pixels as the app.

### Risks that could change the overall plan

1. **Expressive at launch means a Compose alpha stack.** If the product owner insists on Expressive, the app depends on `material3:1.5.0-alphaNN` **and** Compose `1.13.0-alpha*` core. That affects every module's stability and the BOM strategy in `stack.md`.
2. **Gesture conflict: pager swipe vs row swipe actions.** Both are horizontal drags, and the innermost draggable (the row) wins (verify in the UI spike). We choose pager swipe by default. If the product owner wants AntennaPod / Pocket Casts-style row swipes in Feeds, the pager loses swipe and becomes tabs-only.
3. **[cross-area] The list UI needs a separate "live row state" channel.** `opml-groups.md` forbids joining position and download-progress tables in the paged group-feed query. Rows therefore get progress, played and download state from an `EpisodeLiveState` holder keyed by **visible** IDs (§9). This is UI-layer code that the data layer must support: an `IN (:ids)` query plus the in-memory progress buses.
4. **[cross-area] The artwork store versus `playback.md`'s "ArtworkProvider backed by the same disk cache as Coil".** I recommend that the provider serve `ArtworkStore` files instead. The Coil disk cache is purgeable and keyed by URL, not by podcast. Playback and downloads must call `ArtworkStore.pin()` when they need offline art.
5. **[cross-area] In the `play` flavour, YouTube items are "external episodes"** (`youtube.md`). Rows, the player and Up next need a distinct affordance ("Open in YouTube", no download, not queueable). The design below covers it, but QA must test both flavours.
6. **Widget-initiated playback under Android 17's background-audio hardening is UNVERIFIED.** A play tap on a widget may not give the service while-in-use capability. If it does not, the widget's play button must open the app (or use the media-button path that `playback.md` validates).
7. **Cleartext `http://` artwork.** Many old feeds use `http` image URLs, and Android blocks cleartext by default. The networking policy decides between upgrading to `https`, a domain allow-list, or a placeholder. The UI simply falls back to the monogram.

---

## Options considered

### A. Material baseline

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **M3 1.4.0 stable (BOM 2026.09.00)** | Stable; matches `stack.md`. Has `ShortNavigationBar`, `WideNavigationRail`, `PrimaryScrollableTabRow`, `PullToRefreshBox`, `SwipeToDismissBox`, `SegmentedButton`, carousel (experimental) and `SearchBar` (experimental) | No Expressive components or motion scheme. `TopAppBar`, `LargeTopAppBar`, `ModalBottomSheet` and `SearchBar` still need `@OptIn(ExperimentalMaterial3Api::class)` | **Chosen** |
| M3 1.5.0-alpha29 + Compose 1.13.0-alpha01 | Full Expressive: `MaterialExpressiveTheme`, `ButtonGroup`, `ToggleButton`, `FloatingToolbar`, wavy progress, flexible app bars, FAB menu, `HorizontalCenteredHeroCarousel` (most of these are already non-experimental *inside* the alpha). `LoadingIndicator` and `MaterialShapes` remain `@ExperimentalMaterial3ExpressiveApi` | The whole Compose core goes alpha. Source-breaking changes keep landing (alpha29 `Slider`, alpha26 `SearchBar`, alpha21 bottom-sheet state). No announced date for 1.5.0 beta | Upgrade path: adopt when 1.5.0 reaches RC, through `:core:designsystem` |
| Stable M3 plus hand-made "Expressive-lite" | `androidx.graphics:graphics-shapes` 1.1.0 (stable) gives `RoundedPolygon` / `Morph` for a shape-morphing play button. `Modifier.dropShadow` (ui 1.12) gives a tinted artwork glow | Custom code; must not imitate Expressive so closely that it clashes later | **Use sparingly**: the play/pause morph and the artwork glow only |

### B. App chrome and top-level destinations

| Option | Notes | Verdict |
|---|---|---|
| **Feeds · Library · Up next · Downloads · Discover** | Five equal-weight destinations, which is the M3 maximum for a bar. "Up next" is core podcast behaviour (it is the DB queue in `playback.md`), and Downloads matters for an offline-first player | **Chosen** |
| Feeds · Library · Downloads · Search · Settings | Settings is not a peer destination in M3. It wastes a slot that Up next needs | Rejected |
| Four destinations, with Up next inside the player only | Cleaner bar, but reordering the queue would need the player open. AntennaPod and Pocket Casts both expose the queue at top level | Fallback if the product owner wants four (open question) |
| Navigation drawer | Hides destinations; M3 now favours the `WideNavigationRail` expanded state over modal drawers | Rejected |

### C. Switching between group feeds (core decision)

| Option | Swipe between groups | Scales to many groups | Discoverability | Fits "separate feed" | Verdict |
|---|---|---|---|---|---|
| **Scrollable tabs + `HorizontalPager`** | Yes | ~10–12 comfortably; more through the "All groups" sheet | High (always visible) | Strong: each tab is a place with its own scroll position | **Chosen** |
| Single-select filter chips over one list | No | Similar | High | Weak: chips mean "filter this list" in M3; no per-group scroll memory | Used for *within-feed* filters only |
| Group drawer (Gmail-labels style) | No | Excellent | Low (hidden) | Medium | Rejected: hides the key feature; conflicts with the rail |
| Title dropdown ("Feeds: tech ▾") | No | Excellent | Low | Medium | Kept as the a11y/keyboard fallback: the tab row's trailing button opens the same list |
| Groups as nav-bar destinations | — | ≤ 5, and fixed | High | Strong | Rejected: groups are arbitrary and user-defined |
| Group overview grid → feed (two levels) | No | Excellent | Medium | Medium; an extra tap every time | Used in Library → Groups and in the "All groups" sheet |

### D. Expanding the player

| Option | Gesture-following | Complexity | Verdict |
|---|---|---|---|
| **Custom root `PlayerSheet` with `AnchoredDraggableState` and lerped layout** | Yes (offset is the progress) | Medium; one composable owns both layouts | **Chosen** |
| `BottomSheetScaffold` / `ModalBottomSheet` | Yes | Low. But the sheet is a *separate surface*: the mini-player morph is hard, `ModalBottomSheet` is experimental in 1.4.0 and its state API is being reworked in 1.5 alphas | Rejected |
| Player as a Nav3 destination with shared elements (mini art → full art) | Only through `SeekableTransitionState`; otherwise animates after release | Medium-high; pollutes every tab's back stack | Rejected |
| `media3-ui-compose-material3` `MiniController` / `Player` composables | n/a | Low, but Media3's visual design is fixed and not artwork-tinted | Use the **state holders** (`rememberPlayPauseButtonState`, `rememberProgressStateWithTickInterval`, `rememberPlaybackSpeedState`), not the stock composables; `ContentFrame` for video |

### E. Artwork colour extraction

| Option | Output | Threading / API | Verdict |
|---|---|---|---|
| **MCU via `com.materialkolor:material-color-utilities` 5.0.1** | `QuantizerCelebi` + `Score`, the same pipeline Android uses for wallpaper colour. Gives a seed, then a full M3 `DynamicScheme` (`SchemeContent`, `SchemeTonalSpot`, …) with built-in contrast curves | Pure Kotlin, any API level, runs in a worker | **Chosen** |
| `material-kolor` 5.0.1 Compose artifact | The same plus `rememberDynamicColorScheme` / `DynamicMaterialTheme` | Its POM depends on Compose Multiplatform `material3` 1.12.0-alpha03, which wraps androidx M3 **1.5.0-alpha22** (the androidx artifact is excluded transitively). Calling it on the 1.4.0 runtime is binary-compatible for `ColorScheme` (both have the same 48-role constructor, checked), but `DynamicMaterialExpressiveTheme` would crash. The README itself warns that image extraction "can be pretty slow" in UI | Not needed; avoid the coupling |
| `androidx.palette` 1.0.0 (1.1.0-alpha01 merges `-ktx`) | Swatches (vibrant, muted, …), no M3 roles, no contrast guarantees | Software bitmap; background thread | Rejected |
| MDC-Android `DynamicColorsOptions.setContentBasedSource(Bitmap)` | Views theme overlay | API 31+ only; Views, not Compose | Rejected |
| Compose M3 `dynamicLight/DarkColorScheme(context)` | Wallpaper only | `@RequiresApi(31)` | Used for layer 1 only |

### F. Offline artwork

| Option | Verdict |
|---|---|
| Coil disk cache only | Purgeable (it lives in `cacheDir`; the singleton default is `java.io.tmpdir/coil3_disk_cache`), LRU-evicted and keyed by URL. **Rejected** as the only store |
| **Pinned `ArtworkStore` (normalised files) + Coil disk cache for everything else** | **Chosen**. Subscriptions × ~120 KB ≈ 36 MB for 300 podcasts at 1024 px |
| Embed art in downloaded audio files (ID3 APIC) | Playback disables artwork metadata parsing to avoid OOM (`setDisableArtworkMetadata(true)`); not usable |

### G. Placeholder covers (feeds without art, broken URLs, offline first load)

| Option | Verdict |
|---|---|
| Generic microphone icon on grey | Every artless podcast looks identical; poor in a grid. Rejected |
| **Monogram: initials on a deterministic hue, HCT-toned** | **Chosen**. Distinct, stable across sessions, readable at 48 dp |
| Generative pattern (shape polygons from `graphics-shapes`) + title text | Pretty but busy; maybe later as a "style" option |
| First episode's art | Often absent too; inconsistent |

### H. Thumbnails in episode rows

| Policy | Where |
|---|---|
| **Podcast cover, or episode art when it differs** (feeds stores `imageUrl = null` when equal) | Feeds (mixed podcasts), Up next, Downloads |
| **No thumbnail**; date and duration take the slot | Podcast detail, where every row would show the same cover |
| YouTube: the 16:9 video thumbnail centre-cropped to square at 56 dp (from `mqdefault`, 320×180, never letterboxed) | Feeds rows for YouTube items. An option lets users show the channel avatar instead (open question) |

---

## Technical detail

### 1. Screen inventory

| # | Screen / surface | Nav3 key (`@Serializable … : NavKey`) | Pane role on ≥ 600 dp | Notes |
|---|---|---|---|---|
| 1 | **Feeds**: group pager | `FeedsRoute` (top-level) | list | Tabs: All, groups, [Ungrouped]. Filter chips, pull-to-refresh refreshes *this group's* podcasts, "Play group" header row |
| 2 | Episode detail | `EpisodeRoute(episodeId)` | detail | Art, actions, show notes (`feeds.md` jsoup → Compose renderer), chapters with chapter art, transcript link |
| 3 | **Library**: podcasts grid / groups | `LibraryRoute` (top-level) | list | Group chips, sort, grid/list toggle, selection mode |
| 4 | Podcast detail | `PodcastRoute(podcastId)` | detail (list for #2) | Tinted header, subscribe, groups chips, per-podcast settings, episode list |
| 5 | Group editor | `GroupEditRoute(groupId?)` | detail / extra | Name (≤ 40 chars), colour (12-colour palette), icon, member picker (cover grid with checkmarks), per-group settings (`opml-groups.md`) |
| 6 | Manage groups | `GroupsManageRoute` | list | Drag to reorder (defines tab order), rename, delete |
| 7 | **Up next** | `UpNextRoute` (top-level) | list | Drag to reorder, swipe to remove, "Clear", play context shown at top ("Then: tech, newest first") |
| 8 | **Downloads** | `DownloadsRoute` (top-level) | list | Storage bar, In progress (with `waitReason` text), Completed, Failed. Bulk delete |
| 9 | **Discover** | `DiscoverRoute` (top-level) | list | Search bar, paste-URL recognition, "Add YouTube channel", "Import OPML", charts by category |
| 10 | Search results / category | `DirectoryRoute(query/category)` | list | Cover list. Tap opens #4 in preview mode |
| 11 | Add by URL / YouTube sheet | sheet in #9 | — | One text field. Recognises RSS, Apple and YouTube (handle, `/channel/UC…`, `/@name`, video URL) and shows a preview card with art before subscribing |
| 12 | Import flow | `ImportRoute(sessionId)` | full | SAF picker → preview (group mapping, duplicates) → progress list where covers pop in as feeds resolve → report |
| 13 | Export | dialog in Settings or Library overflow | — | OPML hybrid / flat, backup ZIP |
| 14 | **Full player** | *not a route*: `PlayerSheet` expanded | side panel on ≥ 840 dp | Tabs inside: Up next · Chapters · Notes. Video surface for video items |
| 15 | Mini player | `PlayerSheet` collapsed | bottom of the content area | Hidden when nothing is loaded |
| 16 | Video full-screen / PiP | state of #14 | — | `ContentFrame`; PiP per `playback.md` §14 |
| 17 | Sleep timer, speed, add-to-groups, per-podcast settings | `ModalBottomSheet` | — | Sheets, not routes. Add-to-groups is a multi-select chip list plus "New group" |
| 18 | Settings + subpages | `SettingsRoute(page)` | list-detail | Appearance, Playback, Downloads, Feeds and refresh, YouTube (flavour-specific), Backup, Licences (GPL notice in `foss`) |
| 19 | Onboarding | empty states of #1/#3/#9, not a wizard | — | See §13 |
| 20 | Widgets | Glance | — | Now playing (v1), Group feed (v1.1), with a config activity to pick the group |

### 2. Navigation structure

```kotlin
// One back stack per top-level tab (Nav3 "multiple back stacks" recipe shape).
@Serializable sealed interface TopLevel : NavKey
@Serializable data object FeedsRoute : TopLevel
@Serializable data object LibraryRoute : TopLevel
@Serializable data object UpNextRoute : TopLevel
@Serializable data object DownloadsRoute : TopLevel
@Serializable data object DiscoverRoute : TopLevel
@Serializable data class PodcastRoute(val podcastId: Long) : NavKey
@Serializable data class EpisodeRoute(val episodeId: Long) : NavKey

@Composable
fun NeutrodyneRoot(app: AppState /* tab stacks, player state, prefs */) {
    val adaptive = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)
    val suiteState = rememberNavigationSuiteScaffoldState()
    val player = rememberPlayerSheetState()                    // §7
    LaunchedEffect(player.isExpanded) { if (player.isExpanded) suiteState.hide() else suiteState.show() }

    NavigationSuiteScaffold(
        state = suiteState,
        navigationItems = {
            TopLevelDestinations.forEach { d ->
                NavigationSuiteItem(
                    selected = app.currentTab == d.key,
                    onClick = { app.selectTab(d.key) },              // re-tap = pop to root + scroll to top
                    icon = { Icon(painterResource(if (app.currentTab == d.key) d.iconFilled else d.icon), null) },
                    label = { Text(stringResource(d.label)) },
                    badge = d.badge(app),                            // e.g. Downloads failed count
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            SharedTransitionLayout {
                NavDisplay(
                    backStack = app.currentBackStack,
                    onBack = { app.pop() },
                    sceneStrategies = listOf(rememberListDetailSceneStrategy()),
                    sharedTransitionScope = this,
                    entryProvider = appEntryProvider(app),
                    modifier = Modifier.padding(bottom = player.collapsedFootprint), // mini player space
                )
            }
            PlayerSheet(state = player, adaptive = adaptive)          // composed AFTER NavDisplay → its back handler wins
        }
    }
}
```

- Re-selecting the current tab pops that tab's stack to its root and scrolls to the top. On Feeds it also jumps to "All".
- `ListDetailSceneStrategy` metadata: `LibraryRoute` and `FeedsRoute` are `listPane(detailPlaceholder = { CoverMosaicPlaceholder() })`, `PodcastRoute` is `detailPane()`, and `EpisodeRoute` is `extraPane()` when opened from a podcast. On compact width these collapse to single-pane push navigation automatically.

### 3. Wireframes (ASCII)

Phone, compact width (360–411 dp). `[>]` is play, `[v]` is download state, `( )` is a 48 dp touch target.

**3.1 Feeds: group feed**

```
+--------------------------------------------------+
| Feeds                          (search) (gear)   |  TopAppBar, pinned
|  All   *tech   *news   *fiction   *science  (:::)|  PrimaryScrollableTabRow; * = group colour dot;
|  ====                                            |  (:::) = "All groups" sheet (outside the scroll)
| [x Unplayed] [Downloaded] [In progress] [Newest v]  FilterChips in a LazyRow
+--------------------------------------------------+
| (>) Play 23 unplayed      newest first v   (...) |  group header row (not a FAB: mini player lives below)
| TODAY                                            |  stickyHeader
| +------+ Episode title that can wrap onto    (v) |
| |cover | a second line, then ellipsis             |
| | 56dp | Podcast name - 45 min                (>)|
| +------+ ======-------------- 20 min left        |  progress (only when started)
| +------+ [YT] Video title from a channel     (v) |  YouTube item: 16:9 thumb centre-cropped
| |thumb | Channel name - video                 (>)|  duration unknown until played
| +------+                                         |
| YESTERDAY                                        |
| +------+ Another episode                     (v) |
| ...                                              |
|  .  .  o  .  .   <- swipe left/right = next group|
+--------------------------------------------------+
| +----+ Now playing episode title...  (>||) (+30) |  mini player 64dp, floating, 8dp side inset
| +----+ ===========------------------------------ |  2dp progress on the container's bottom edge
+--------------------------------------------------+
|  Feeds   Library   Up next   Downloads  Discover |  ShortNavigationBar
+--------------------------------------------------+
```

**3.2 Library: cover grid**

```
+--------------------------------------------------+
| Library                  (search) (sort) (:)     |
|        [ Podcasts | Groups ]                     |  SingleChoiceSegmentedButtonRow
| [All] [Ungrouped] [tech] [news] [fiction] [+]    |  single-select FilterChips; [+] = new group
+--------------------------------------------------+
| +----------+  +----------+  +----------+         |
| |        12|  |          |  |   o    3 |         |  12 = unplayed badge (top-end)
| |  COVER   |  |  COVER   |  | (avatar) |         |  YouTube: small "video" glyph bottom-start
| |          |  |       [v]|  |[>]       |         |  [v] = has downloads (optional)
| +----------+  +----------+  +----------+         |
| Show title    Another       Channel              |  titles optional (Settings > Library)
| two lines     show          name                 |
| +----------+  +----------+  +----------+         |
| |  AB      |  |          |  |          |         |  AB = generated monogram cover
| ...                                              |
+--------------------------------------------------+
  Long-press -> selection mode:
| (x) 3 selected      (add to group) (unsub) (:)   |  contextual top bar; tiles show check overlay
```

Groups segment:

```
| +-----+-----+  +-----+-----+  +-----------+       |
| | c1  | c2  |  | c1  | c2  |  |           |       |  2x2 mosaic of the 4 most recently
| +-----+-----+  +-----+-----+  |  + New    |       |  updated member covers; group colour
| | c3  | c4  |  | c3  |     |  |   group   |       |  as a 4dp top stripe + icon
| +-----+-----+  +-----+-----+  +-----------+       |
| tech  - 14     news - 6                           |
```

**3.3 Podcast detail**

```
+--------------------------------------------------+
| (<-)                               (gear) (:)    |  transparent bar; becomes surface + title on scroll
|   ...artwork-scheme primaryContainer gradient... |  SchemeContent(seed) -> surface at the bottom
|            +----------------------+              |
|            |                      |              |
|            |   COVER  160dp       |              |  shared element from grid tile / row thumb
|            |   (24dp corners)     |              |
|            +----------------------+              |
|  Podcast Title In headlineSmall, two lines max   |
|  Author / publisher                              |
|  [ Subscribed v ]   [tech x] [news x] [+ group]  |  InputChips = group membership, editable
|  Description first three lines of plain text ... |
|  more                                            |
|  214 episodes - weekly - updated 2 days ago      |
+--------------------------------------------------+
| [Unplayed] [Downloaded]               Newest v   |
| 12 OCT  Episode 214: Title of the episode    (v) |  no thumbnail (same cover); date block instead
|         52 min                               (>) |
| 05 OCT  Episode 213: ...                     (v) |
```

For YouTube channels, the 6:1 banner (`youtube.md` §3) fills the header behind the avatar with a bottom scrim. When no banner exists, the avatar's `SchemeContent` gradient is used.

**3.4 Full player (phone portrait)**

```
+--------------------------------------------------+
| (v)              Now playing               (:)   |  (v) collapse; status bar icons match scheme
|                                                  |
|      +------------------------------------+      |
|      |                                    |      |
|      |                                    |      |
|      |         ARTWORK (square)           |      |  min(width-48dp, 45% height, 480dp)
|      |         24dp corners, tinted       |      |  dropShadow in seed colour
|      |         glow                       |      |  swaps to chapter art when present
|      |                                    |      |
|      +------------------------------------+      |
|                                                  |
|  Episode title in titleLarge, two lines max      |
|  Podcast name  >                                 |  tap = open podcast detail (collapses sheet)
|  Ch. 3 - The interview                    (list) |
|  |------o-----|----|---------|----------------|  |  Slider with chapter tick marks
|  12:04                                  -33:10   |  remaining toggles to total on tap
|                                                  |
|   (-10)   (|<ch)   ((  >||  ))   (ch>|)   (+30)  |  play: 72dp, morphing shape
|                                                  |
|  (1.2x)    (sleep)    (cast)    (video)   (share)|  secondary row; cast only in `play` flavour
+--------------------------------------------------+
| ---- Up next (3) | Chapters | Notes ----         |  peek of inner tabs; drag up = full height
+--------------------------------------------------+
```

**3.5 Expanded width (≥ 840 dp): list-detail plus player panel**

```
+----+-------------------------+-------------------------+----------------+
|Rail| Library grid (list)     | Podcast detail (detail) | Now playing    |
| F  | +---+ +---+ +---+       | +-----+ Title           | +------------+ |
| L  | |   | |   | |   |       | |cover| Author          | |  ARTWORK   | |
| U  | +---+ +---+ +---+       | +-----+ [Subscribed]    | +------------+ |
| D  | +---+ +---+ +---+       | episodes...             | title / ctrls  |
| S  | |   | |   | |   |       |                         | Up next list   |
|----|                         |                         |                |
|gear|                         |                         |                |
+----+-------------------------+-------------------------+----------------+
  The player panel (360-412 dp) is shown when playing and dismissible. Below the
  Large class it becomes a bottom "player bar" plus a full-screen sheet as on phones.
```

**3.6 Tabletop posture (half-folded, hinge horizontal)**

```
+----------------------------------+
|       ARTWORK / VIDEO            |   upper half, above hinge
|==================================|   hinge (FoldingFeature bounds)
| title          12:04 / -33:10    |   lower half: controls on the "table"
| (-10)  ( >|| )  (+30)  (1.2x)    |
+----------------------------------+
```

### 4. Component specs

#### 4.1 Episode row (`EpisodeRow`)

Anatomy, left to right: thumbnail 56 dp (8 dp corners) → text column (title `titleSmall`, max 2 lines; meta `bodySmall`: "Podcast · 45 min" or "Podcast · 12 Oct") → trailing column: download-state button and play button (both 48 dp touch targets, 24 dp icons). There is no fixed height: minimum 72 dp, and it grows with font scale. Played rows dim the title to `onSurfaceVariant` and show a check glyph.

Playback-state visuals:

| State | Visual |
|---|---|
| Unplayed, never started | Small `primary` dot before the title ("new" since last visit) |
| In progress | 3 dp `LinearProgressIndicator` under the meta plus "20 min left". Play icon shows "resume" |
| Now playing | Play button becomes animated bars / pause; row container is `secondaryContainer` |
| Played | Title in `onSurfaceVariant`, check glyph, no progress bar |
| Duration unknown (YouTube Atom) | Meta omits duration. The progress bar appears only once the player reports a duration |

Download-state visuals (mapping `downloads.md`'s state machine):

| `DownloadState` (+ `waitReason`) | Trailing icon | Secondary text | Tap |
|---|---|---|---|
| none | `download` outline | — | Enqueue (asks first on metered network, per `downloads.md`) |
| `QUEUED` / `NONE`, `SLOT` | `schedule` with an indeterminate ring | "Queued" | Pause or cancel menu |
| `QUEUED` / `NETWORK`, `UNMETERED_NETWORK`, `CHARGING`, `STORAGE`, `BACKOFF`, `NEEDS_FOREGROUND` | `schedule` | "Waiting for Wi-Fi" / "Waiting for storage" / "Retrying in 4 min" / "Tap to resume" | Menu (Download now / Cancel) |
| `RESOLVING` | Indeterminate ring | — | Cancel |
| `DOWNLOADING` | Determinate `CircularProgressIndicator` around a stop icon | "34 %" (or MB on Downloads screen) | Pause |
| `PAUSED` | `pause_circle` | "Paused" | Resume |
| `VERIFYING` | Indeterminate ring | — | — |
| `COMPLETED` | `download_done` filled (`primary`) | — | Menu (Delete download) |
| `FAILED` | `error` (`error` colour) | Short reason | Retry |
| `MISSING` | `error` outline | "File missing" | Re-download |
| `play` flavour, YouTube | `open_in_new` replaces both buttons | "Opens in YouTube" | `ACTION_VIEW` |

Interaction:
- Tap the row → episode detail.
- Tap play → play now (if something else is playing, the default is "play now and push current to the top of Up next"; this is a setting).
- Long-press → multi-select mode.
- Swipe actions where enabled: start→end "Add to Up next", end→start "Mark played". Configurable.

Accessibility:

```kotlin
Row(
    Modifier
        .semantics(mergeDescendants = true) {
            contentDescription = summary   // "Episode 214, Title. Podcast. 12 October. 52 minutes, 20 minutes left. Downloaded."
            stateDescription = progressText                  // "40 percent played"
            customActions = listOf(
                CustomAccessibilityAction(playLabel) { onPlay(); true },
                CustomAccessibilityAction(downloadLabel) { onDownloadToggle(); true },
                CustomAccessibilityAction(upNextLabel) { onAddUpNext(); true },
                CustomAccessibilityAction(markPlayedLabel) { onTogglePlayed(); true },
            )
        }
        .combinedClickable(onClickLabel = openLabel, onClick = onOpen,
                           onLongClickLabel = selectLabel, onLongClick = onSelect)
) { /* thumbnail (contentDescription = null), texts, buttons (clearAndSetSemantics {} on the inner buttons
       so TalkBack does not stop on them separately; their actions are the custom actions above) */ }
```

At `LocalDensity.current.fontScale >= 1.5f` the row switches to a stacked layout: thumbnail and title on top, the action buttons in a row underneath. Android 14 documents `fontScale` as informational, so it is used only to choose a layout, never to compute sizes.

#### 4.2 Cover tile (`CoverTile`)

- Square, 12 dp corners (`MaterialTheme.shapes.medium`). The background while loading is `avgArgb` from the DB, so it never shows grey.
- Badges: unplayed count with `BadgedBox` / `Badge` (cap at "99+"), and a 20 dp "video" glyph for YouTube. Do not use the YouTube logo (trademark).
- Selection overlay: a scrim with a check and 0.92× scale (animated).
- Optional title below: `labelLarge`, 2 lines.
- Content description: when titles are hidden, "Title, 12 unplayed". When titles are shown, the cover is decorative (`null`) and the merged tile reads the text.

#### 4.3 Group tab and group mosaic

- Tab content: a 8 dp colour dot (`group.colorArgb` mapped to a theme tone, §5.4) plus the name (`maxLines = 1`, ellipsis, tab max width 200 dp) plus an optional unplayed count in `labelSmall`.
- An emoji or icon from `iconKey` is optional (open question).
- Mosaic: a 2×2 `Box` of four 50 % `AsyncImage`s with 2 dp gaps (`opml-groups.md` §K query). With fewer than four members the slots are filled with the group colour. A rasterised version is saved to `ArtworkStore` for Android Auto and widgets.

#### 4.4 Mini player

- Height 64 dp. Horizontal inset 8 dp. Container `surfaceContainerHigh`, blended 12 % toward the artwork scheme's `primaryContainer`. Corner 16 dp.
- Contents: 48 dp cover (10 dp corners), two text lines (episode `titleSmall`, podcast `bodySmall`), and play/pause (48 dp) plus skip-forward (48 dp; can be hidden in settings).
- Progress: a 2 dp line along the container's bottom edge.
- Gestures:
  - Tap or drag up → expand.
  - Drag down when paused → dismiss and stop.
  - **No** horizontal swipe-to-skip: it is too easy to trigger next to the pager.
- TalkBack: one merged node "Now playing: title, podcast. Paused." with actions Play/Pause, Skip forward and Expand player.

#### 4.5 Full player

- Artwork size: `min(width − 48 dp, height × 0.45, 480 dp)`, 24 dp corners, `Modifier.dropShadow(shape, Shadow(radius = 32.dp, color = seed.copy(alpha = .45f)))` (ui 1.12 API).
- Chapter art (Podcasting 2.0 or ID3 chapter images) crossfades over the cover.
- Background: a vertical gradient from the artwork scheme's `primaryContainer` (top 0–35 %) to `surface`.
  - Text sits in the lower region on `surface` and uses `onSurface` / `onSurfaceVariant`.
  - The play button is `primary` / `onPrimary`.
  - These pairs are generated with MCU contrast curves (≥ 4.5:1 at normal contrast for on-container text).
- Progress: M3 `Slider` with chapter tick marks drawn in `Modifier.drawBehind`. Elapsed time is on the left; remaining or total (toggle) on the right. Use `rememberProgressStateWithTickInterval(controller)`.
- Secondary row (48 dp icon buttons with tooltips): speed (opens a sheet with presets and a fine slider), sleep timer, Output Switcher / cast (`play` flavour), video toggle (video items only), share.
- Inner tabs (Up next · Chapters · Notes) live in a nested anchored sheet with a peek height of 56 dp.
- Video items: `ContentFrame(player = controller)` replaces the artwork area. A double-tap on the left or right half seeks −10 s / +30 s.

#### 4.6 Podcast detail header

- The header is the `LazyColumn`'s first item; it scrolls away.
- A pinned `TopAppBar` overlays it. Its container alpha and title visibility follow `firstVisibleItemScrollOffset`, which is simpler and more robust than nested-scroll `LargeTopAppBar` behaviour when the header is this tall.
- Status bar icon appearance is chosen from the artwork scheme's luminance (§11).

### 5. Theming and colour

#### 5.1 Layer 1: app scheme

```kotlin
@Composable
fun NeutrodyneTheme(prefs: AppearancePrefs, content: @Composable () -> Unit) {
    val dark = when (prefs.mode) { Mode.System -> isSystemInDarkTheme(); Mode.Light -> false; Mode.Dark -> true }
    val ctx = LocalContext.current
    val contrast = rememberSystemContrast()  // UiModeManager.getContrast() on API 34+ (range -1..1), else 0.0
    val base = if (prefs.wallpaperColors && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else remember(dark, contrast) {
        SchemeTonalSpot(Hct.fromInt(BRAND_SEED), dark, contrast).toComposeColorScheme(dark)
    }
    val scheme = if (dark && prefs.pureBlack) base.toPureBlack() else base
    MaterialTheme(colorScheme = scheme, typography = NeutrodyneType, shapes = NeutrodyneShapes, content = content)
}
```

#### 5.2 MCU → Compose mapping

This is kept small. It starts from the `lightColorScheme()` / `darkColorScheme()` defaults and then uses `ColorScheme.copy(...)` with named arguments, rather than the raw 48-argument constructor. That keeps it source-compatible when M3 adds roles: a new role keeps its default until we map it.

```kotlin
private val mdc = MaterialDynamicColors()
fun DynamicScheme.toComposeColorScheme(dark: Boolean): ColorScheme {
    fun DynamicColor.c() = Color(getArgb(this@toComposeColorScheme))
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = mdc.primary().c(), onPrimary = mdc.onPrimary().c(),
        primaryContainer = mdc.primaryContainer().c(), onPrimaryContainer = mdc.onPrimaryContainer().c(),
        // … every remaining role, including surfaceContainer*, surfaceDim/Bright and the *Fixed* roles
    )
}
fun ColorScheme.toPureBlack() = copy(
    background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0C), surfaceContainer = Color(0xFF111113),
    surfaceContainerHigh = Color(0xFF18181A), surfaceContainerHighest = Color(0xFF1F1F22),
    surfaceDim = Color.Black,
)
```

`ColorScheme.copy` exists in M3 1.4.0 (`ColorScheme.kt`); checked in the sources jar.

#### 5.3 Layer 2: artwork-scoped schemes

```kotlin
@Composable
fun ArtworkTheme(seedArgb: Int?, content: @Composable () -> Unit) {
    val app = MaterialTheme.colorScheme
    val dark = app.surface.luminance() < 0.5f
    val contrast = rememberSystemContrast()
    val target = remember(seedArgb, dark, contrast) {
        seedArgb?.let { SchemeContent(Hct.fromInt(it), dark, contrast).toComposeColorScheme(dark) } ?: app
    }
    // Animate only the roles the player and header use (~12), not all 48, on artwork change.
    val animated = target.animateSubset(spec = tween(400))
    MaterialTheme(colorScheme = animated, content = content)
}
```

- `SchemeContent` places the source colour in `primaryContainer`, so the art's hue stays recognisable. For near-monochrome art, `Score.score(..., fallbackColorArgb = null)` returns an **empty list**. In that case store `seedArgb = null` and the UI falls back to the app scheme, instead of MCU's default fallback "Google Blue", which looks random.
- The setting "Tint player with artwork colours" is on by default. When it is off, `ArtworkTheme` passes the app scheme through.
- Pure-black is applied after `SchemeContent` too, but only to surfaces; containers keep their tint.

#### 5.4 Group colours

`opml-groups.md` stores `colorArgb` from a curated 12-colour palette. **Do not render the raw ARGB.** Map it through HCT to theme-appropriate tones:
- dot or stripe: tone 40 light / 80 dark;
- container: tone 90 / 30;
- on-container: tone 10 / 90.

Then the same "tech blue" reads correctly in light, dark and pure-black. This is roughly ten lines with `TonalPalette.fromHct(Hct.fromInt(argb))`.

#### 5.5 Typography, shape, motion tokens (`:core:designsystem`)

- **Type:** M3 default scale with the platform font (Roboto / Google Sans on Pixels). Use `sp` everywhere and `lineHeight` in `sp`. A custom brand font is an open question. If one is used, prefer a variable downloadable font with `FontVariation.weight` so the player title can be heavier without extra files.
- **Shapes:**

  | Element | Corner |
  |---|---|
  | Cover thumbnails | 8 dp |
  | Tiles | 12 dp |
  | Mini player | 16 dp |
  | Detail and player art | 24 dp |
  | Sheets | 28 dp |

- **Motion:** our own `NeutrodyneMotion` object, a stand-in for Expressive's `MotionScheme`:
  - `spatial = spring(dampingRatio = 0.8f, stiffness = 380f)`
  - `effects = tween(200)`
  - When Expressive arrives this maps to `MotionScheme.expressive()`.
  - Wrap all transitions in it so "Remove animations" (animator duration scale 0) is honoured consistently. *UNVERIFIED:* that every Compose animation honours the system animator scale; check in the spike with the developer option.

### 6. Artwork pipeline

#### 6.1 Data shape (cross-area with the data model)

```kotlin
@Entity(tableName = "artwork")                    // low-churn; safe to join from list queries
data class ArtworkEntity(
    @PrimaryKey val key: String,                  // sha1(normalisedUrl) or "gen:<podcastKey>" for placeholders
    val url: String?,                             // null for generated
    val localPath: String?,                       // relative to filesDir/artwork, null = not pinned
    val width: Int?, val height: Int?,
    val seedArgb: Int?,                           // null = monochrome / unknown -> app scheme
    val avgArgb: Int?,                            // loading placeholder colour
    val version: Int,                             // bumps when bytes change -> busts memory keys
    val fetchedAt: Long, val pinCount: Int,       // pinned by: subscription, downloaded episode, group mosaic
)
// podcast.artworkKey, episode.artworkKey (nullable = use podcast's), group.mosaicArtworkKey
```

#### 6.2 `ArtworkSyncWorker`: fill the store, compute colours

- **Runs:** on subscribe, after a refresh that changed an artwork URL, on episode download (only if the episode has its own art), and after a group membership change (mosaic).
- **Constraints:** network, not low on storage. It batches 8 at a time.

```kotlin
suspend fun pin(ref: ArtworkRef) {
    val result = imageLoader.execute(
        ImageRequest.Builder(ctx).data(ref.url)
            .size(1024)                    // normalise; covers are 1400-3000 px square (Apple spec)
            .precision(Precision.INEXACT)
            .allowHardware(false)          // need pixels for quantisation + compress
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
    )
    val bmp = (result as? SuccessResult)?.image?.toBitmap() ?: return markFailed(ref)
    val file = store.fileFor(ref.key, hasAlpha = bmp.hasAlpha())
    file.outputStream().use {
        if (bmp.hasAlpha()) bmp.compress(PNG, 100, it)
        else bmp.compress(JPEG, 88, it)   // or WEBP_LOSSY on API 30+
    }
    val small = Bitmap.createScaledBitmap(bmp, 112, 112, true)
    val px = IntArray(112 * 112).also { small.getPixels(it, 0, 112, 0, 0, 112, 112) }
    val seed = Score.score(QuantizerCelebi.quantize(px, 128), desired = 1, fallbackColorArgb = null).firstOrNull()
    dao.upsert(ref.toEntity(file, bmp.width, bmp.height, seed, averageArgb(px), version = ref.version + 1))
}
```

Generated placeholders go through the same path: `MonogramRenderer.render(1024)` → file → seed = the generated hue.

#### 6.3 Coil `ImageLoader`

```kotlin
class NeutrodyneApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var okHttp: OkHttpClient
    @Inject lateinit var artworkStore: ArtworkStore

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Share connection pool + dispatcher, but no OkHttp disk cache: Coil caches images itself
        // (and by default ignores Cache-Control, always writing to its own disk cache).
        val imageClient = okHttp.newBuilder().cache(null).build()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { imageClient }))
                add(ArtworkRefMapper(artworkStore))       // ArtworkRef -> File (pinned) | url String
                add(YouTubeThumbnailInterceptor())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            // Audio app = long hours in background with an FGS: shrink to 25 % of max while backgrounded.
            .memoryCacheMaxSizePercentWhileInBackground(0.25)
            .diskCache {
                DiskCache.Builder().directory(context.cacheDir.resolve("coil"))
                    .maxSizeBytes(256L * 1024 * 1024).build()
            }
            .crossfade(150)
            .build()
    }
}

data class ArtworkRef(val key: String, val url: String?, val version: Int)

class ArtworkRefMapper(private val store: ArtworkStore) : Mapper<ArtworkRef, Any> {
    override fun map(data: ArtworkRef, options: Options): Any? =
        store.pinnedFile(data.key)?.takeIf(File::exists) ?: data.url   // null -> fallback painter
}
```

#### 6.4 Two cache tiers with explicit memory keys

In Coil 3 the computed memory key does **not** include the size unless transformations are set; a cached bitmap is reused when it is large enough. Give the two tiers explicit keys so both can live in memory, and so the hero request can show the thumb instantly:

```kotlin
object Covers {
    fun thumbPx(density: Density) = with(density) { 128.dp.roundToPx() }.coerceAtMost(384)  // grid + rows + mini
    fun thumb(ctx: Context, ref: ArtworkRef, px: Int) = ImageRequest.Builder(ctx)
        .data(ref).size(px)
        .memoryCacheKey("art:${ref.key}:v${ref.version}:t")
        .build()
    fun hero(ctx: Context, ref: ArtworkRef) = ImageRequest.Builder(ctx)
        .data(ref).size(1024)
        .memoryCacheKey("art:${ref.key}:v${ref.version}:h")
        .placeholderMemoryCacheKey("art:${ref.key}:v${ref.version}:t")   // seamless shared-element landing
        .build()
}
```

- Memory budget: a 384 px ARGB thumb is about 590 KB. ~20 visible tiles is about 12 MB. Heroes are 4 MB each, and only one or two are alive.
- The memory cache trims under pressure and in the background (above).

#### 6.5 YouTube thumbnails **[tested]**

- The feed's `media:thumbnail` is `hqdefault.jpg` at **480×360, 4:3 letterboxed**. `maxresdefault` and `hq720` are 1280×720. `mqdefault` is 320×180 and always exists.
- For a 2005 video, `maxresdefault`, `hq720` and `sddefault` return **404** with a 120×90 placeholder body.
- Coil throws on non-2xx (`HttpException`). Since 3.4.0 it also *caches* eligible 404s, so a missing `maxres` is not re-fetched on every bind.

```kotlin
class YouTubeThumbnailInterceptor : Interceptor {
    private val re = Regex("""^https?://i\d?\.ytimg\.com/vi/([\w-]{11})/\w*default\.jpg""")
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = (chain.request.data as? ArtworkRef)?.url ?: chain.request.data as? String
        val id = url?.let { re.find(it)?.groupValues?.get(1) } ?: return chain.proceed()
        val w = chain.size.width.pxOrElse { 1280 }
        val chainNames = if (w <= 320) listOf("mqdefault") else listOf("maxresdefault", "hq720", "mqdefault")
        for (name in chainNames) {
            val r = chain.withRequest(chain.request.newBuilder()
                .data("https://i.ytimg.com/vi/$id/$name.jpg").build()).proceed()
            if (r is SuccessResult) return r
        }
        return chain.proceed()   // last resort: 4:3 hqdefault; draw with LetterboxCrop (crop to 16:9 first)
    }
}
```

- Rows at 56 dp × 3 = 168 px fit inside `mqdefault`'s 180 px height, so the square centre crop stays sharp.
- Media session and notification art for YouTube items uses the **channel avatar** (square). Centre-cropped 16:9 frames look bad on Auto and the lock screen (`youtube.md` §3).
- `hqdefault` square-crop trap: a 1:1 crop of a 4:3 letterboxed frame keeps the black bars. Never square-crop `hqdefault` without first cropping the central 16:9 band (rows 45..315 of 360).

#### 6.6 Generated monogram placeholder

```kotlin
@Immutable data class MonogramSpec(val initials: String, val hue: Double)
fun monogramFor(title: String, feedKey: String) = MonogramSpec(
    initials = title.split(Regex("\\s+")).filter { it.firstOrNull()?.isLetterOrDigit() == true }
        .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "#" },
    hue = (feedKey.hashCode().toUInt().toLong() % 360).toDouble(),
)
class MonogramPainter(spec: MonogramSpec, dark: Boolean, private val textMeasurer: TextMeasurer) : Painter() {
    private val bg = Color(Hct.from(spec.hue, 36.0, if (dark) 30.0 else 85.0).toInt())
    private val fg = Color(Hct.from(spec.hue, 24.0, if (dark) 90.0 else 20.0).toInt())   // ~60 tone gap = >7:1
    override val intrinsicSize = Size.Unspecified
    override fun DrawScope.onDraw() { drawRect(bg); /* draw initials centred at 38 % of min(size) */ }
}
```

- Use it as `AsyncImage(fallback = …, error = …)`. Coil's `fallback` is used when the model resolves to `null`.
- Initial extraction must handle emoji and CJK: use `BreakIterator` grapheme clusters in the real code, not `first()`.

### 7. Player sheet implementation sketch

```kotlin
enum class SheetValue { Collapsed, Expanded }

@Composable
fun PlayerSheet(state: PlayerSheetState, adaptive: WindowAdaptiveInfo) {
    val controller by playerConnection.controller.collectAsStateWithLifecycle()
    val now by nowPlaying.collectAsStateWithLifecycle()         // episode, podcast, ArtworkRef, seedArgb
    if (now == null) return
    if (adaptive.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND)) {
        PlayerSidePanel(controller, now); return                  // §10
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val collapsedY = constraints.maxHeight - with(LocalDensity.current) { 72.dp.toPx() }
        val anchors = remember(collapsedY) {
            DraggableAnchors { SheetValue.Collapsed at collapsedY; SheetValue.Expanded at 0f }
        }
        SideEffect { state.drag.updateAnchors(anchors) }
        val p = state.drag.progress(SheetValue.Collapsed, SheetValue.Expanded)   // 0..1
        ArtworkTheme(now.seedArgb) {
            Box(
                Modifier
                    .offset { IntOffset(0, state.drag.requireOffset().roundToInt()) }
                    .anchoredDraggable(state.drag, orientation = Orientation.Vertical)
                    .graphicsLayer { shape = RoundedCornerShape(lerp(16.dp, 0.dp, p)); clip = true }
            ) {
                // Background: mini container colour -> full gradient, alpha by p
                // Artwork: lerp(miniArtRect, fullArtRect, p) positioned manually; size via Modifier.layout
                // Mini controls fade out over p 0..0.2; full controls fade in over p 0.6..1
            }
        }
        // Predictive back: collapse following the gesture, settle on commit/cancel.
        PredictiveBackHandler(enabled = state.drag.currentValue == SheetValue.Expanded) { events ->
            try {
                events.collect { e -> state.drag.anchoredDrag { dragTo(lerp(0f, collapsedY * 0.2f, e.progress)) } }
                state.drag.animateTo(SheetValue.Collapsed)
            } catch (c: CancellationException) {
                state.drag.animateTo(SheetValue.Expanded); throw c
            }
        }
    }
}
```

- `PredictiveBackHandler` (activity-compose 1.13.0) dispatches to the **last-composed enabled** handler. Composing the sheet after `NavDisplay` gives it priority while expanded.
- The newer `navigationevent-compose` 1.1.2 `NavigationEventHandler` (used by Nav3) is the alternative. Pick one consistently in the spike.
- Expanded state survives rotation and process death through `rememberSaveable`. If nothing is loaded, the sheet is absent and the content's bottom padding is 0.
- Starting playback from any UI goes through `controller.play()` while the activity is visible (`playback.md` §17, Android 17 background-audio hardening).

### 8. Group feed pager sketch

```kotlin
@Composable
fun FeedsScreen(vm: FeedsViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()   // [All, Group(1), Group(4), …, Ungrouped?]
    val pager = rememberPagerState(initialPage = vm.initialIndex()) { sources.size }
    val scope = rememberCoroutineScope()
    // Keep selection by id when groups are added, removed or reordered (never by index).
    LaunchedEffect(sources) { vm.indexOf(vm.selectedSourceId)?.let { pager.scrollToPage(it) } }
    LaunchedEffect(pager.settledPage) { vm.onSelected(sources[pager.settledPage].id) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 12.dp,
                                    modifier = Modifier.weight(1f)) {
                sources.forEachIndexed { i, s ->
                    Tab(selected = i == pager.currentPage,
                        onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        text = { GroupTabLabel(s) })
                }
            }
            IconButton(onClick = vm::openAllGroupsSheet) { Icon(painterResource(R.drawable.grid_view), stringResource(R.string.all_groups)) }
        }
        FeedFilterChips(vm)
        HorizontalPager(
            state = pager,
            key = { sources[it].id },                             // stable across reorder
            userScrollEnabled = !vm.rowSwipeActionsInFeeds,       // the gesture conflict switch
            beyondViewportPageCount = 1,
        ) { page ->
            val src = sources[page]
            val items = vm.pagingFor(src).collectAsLazyPagingItems()   // VM keeps an LRU of ~3 cached flows
            val listState = vm.listStateFor(src.id)                    // per-group scroll memory
            EpisodeFeedList(items, listState, vm.liveState)            // §9
        }
    }
}
```

- Dependencies: `HorizontalPager` (foundation 1.12.1, stable) and `PrimaryScrollableTabRow` (M3 1.4.0, stable).
- Pull-to-refresh (`PullToRefreshBox`, stable) wraps each page and refreshes **that page's podcasts only**: "All" refreshes everything.

### 9. Live per-row state (cross-area: invalidation hygiene)

The paged query returns low-churn columns only (title, dates, artwork key, duration, played flag if it is low-churn). Rows overlay the live state:

```kotlin
class EpisodeLiveState @Inject constructor(
    private val playStateDao: PlayStateDao,         // SELECT episodeId, positionMs, durationMs, playedAt … WHERE episodeId IN (:ids)
    private val downloadDao: DownloadDao,           // state, waitReason, bytes … WHERE episodeId IN (:ids)
    private val progressBus: DownloadProgressBus,   // in-memory, throttled (downloads.md)
    private val playback: PlaybackServiceState,     // current episode + 1 s position tick (playback.md §17)
) {
    fun observe(visibleIds: Flow<Set<Long>>): StateFlow<Map<Long, RowLive>> = visibleIds
        .debounce(120).distinctUntilChanged()
        .flatMapLatest { ids -> combine(playStateDao.observe(ids), downloadDao.observe(ids),
                                        progressBus.observe(ids), playback.nowPlaying) { a, b, c, d -> merge(a, b, c, d) } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}
// In the list: visible ids from listState.layoutInfo.visibleItemsInfo (+ 10 items margin), via snapshotFlow.
```

Each row reads `live[episode.id]`. Only the row of the now-playing episode changes every second. Room's table-granular invalidation then re-runs a ≤ 30-id `IN` query, not the `COUNT(*)` plus page query over the group.

### 10. Adaptive layouts

Breakpoints (dp): Compact < 600 ≤ Medium < 840 ≤ Expanded < 1200 ≤ Large < 1600 ≤ Extra-large.

| Width class | Chrome | Feeds | Library | Podcast / Episode | Player |
|---|---|---|---|---|---|
| Compact | `ShortNavigationBar` | Pager, single column | Grid, 3 columns (100 dp min) | Push navigation | Mini plus full-screen sheet |
| Medium (tablet portrait, unfolded inner display) | `WideNavigationRail` (collapsed) | Pager; rows max-width 720 dp, centred | 4–5 columns (content width minus rail) | List-detail: Library ↔ Podcast | Mini plus sheet; full player in two columns when landscape-ish (art left) |
| Expanded | Rail | List-detail: feed (list) ↔ episode detail | List-detail: grid ↔ podcast detail | 2 panes (+ extra pane for episode) | Bottom "player bar" (full width of content) plus sheet |
| Large / XL | Rail, expanded with labels and the "Settings" footer | 3 panes: feed / episode / player panel | Grid / detail / player panel | 3 panes | **Persistent side panel** 360–412 dp (art, controls, Up next) |

- Folds: when `currentWindowAdaptiveInfo().windowPosture.isTabletop` and something is playing, the expanded player uses the split layout (§3.6), placing the hinge from `windowPosture.hingeList`. A separating hinge in book posture becomes a natural list-detail split.
- `NavigationSuiteScaffold`'s default `navigationSuiteType` already returns `ShortNavigationBarMedium` for tabletop or compact-height windows.
- Never use `screenOrientation`, `resizableActivity="false"` or aspect-ratio limits. On API 36+ they are ignored on ≥ 600 dp, and for API 37 targets the `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` opt-out is gone. Test landscape on phones with a "Player in landscape" layout: art left, controls right.
- Keyboard and mouse (ChromeOS, desktop mode):
  - Space toggles play.
  - ←/→ seek.
  - Tabs reachable by Tab / arrow keys.
  - Hover states come free from M3.
  - Right-click maps to the long-press menu. On Android use a `pointerInput` block that checks `event.buttons.isSecondaryPressed`; the `Modifier.onClick(matcher = …)` helper is Compose-Desktop-only.

### 11. Edge-to-edge, system bars, predictive back

- Call `enableEdgeToEdge()` in `MainActivity.onCreate`. Edge-to-edge is mandatory: targetSdk 35 enforces it, and from targetSdk 36 `windowOptOutEdgeToEdgeEnforcement` is disabled.
- Every screen uses `Scaffold` insets or `WindowInsets.safeDrawing`. The mini player sits above the navigation bar's insets (the nav suite consumes them). When the suite is hidden (player expanded), the player itself pads `WindowInsets.navigationBars`.
- **Status bar icons over artwork headers:**
  - Podcast detail and the full player set `WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars` from the artwork scheme's top colour luminance while they are on screen, and restore it on dispose.
  - A 48 dp top scrim (`Brush.verticalGradient(scrim 40 % → transparent)`) guarantees legibility on busy art.
- **Predictive back:**
  - With targetSdk ≥ 36, system back-to-home and cross-activity animations are on by default, and `onBackPressed` is no longer called.
  - Use Nav3's built-in predictive pop (`predictivePopTransitionSpec`) for screens, and the player sheet's `PredictiveBackHandler` for collapse.
  - Never intercept back on a top-level root, so back-to-home animates.

### 12. Accessibility checklist

| Area | Rule | Implementation |
|---|---|---|
| Touch targets | ≥ 48 × 48 dp | M3 components pad automatically. Custom icon buttons use `Modifier.minimumInteractiveComponentSize()` |
| Rows and tiles | One focus stop each | `semantics(mergeDescendants = true)`, a composed `contentDescription`, `stateDescription` for progress, `CustomAccessibilityAction`s for swipe and long-press actions |
| Tabs and pager | Group switching without swipe | `Tab` has `Role.Tab` and selection semantics. TalkBack users switch via tabs, the "All groups" sheet, or the custom action "Next group" on the pager |
| Covers | No noise | Decorative (`contentDescription = null`) wherever a title is adjacent. The full-player art says "Cover art for <podcast>" only when a chapter image replaces it ("Chapter image: <chapter title>") |
| Text scaling | Readable at 200 % (Android 14+ non-linear scaling) | No fixed heights. `sp` with `sp` line heights. Rows stack at `fontScale ≥ 1.5`. Tabs ellipsize. The 5 nav labels may truncate, so verify with `ShortNavigationBar` at 200 % |
| Contrast over artwork | Text ≥ 4.5:1, icons ≥ 3:1 | No text on raw artwork. Text sits on scheme surfaces or a ≥ 60 % scrim. Artwork schemes come from MCU with system contrast (API 34+ `UiModeManager.getContrast()`) passed as `contrastLevel` |
| Motion | Respect "Remove animations" | All custom animations go through `NeutrodyneMotion`; no auto-marquee (use 2-line ellipsis instead of `basicMarquee`) |
| Live updates | No chatter | Do not announce the position tick (Media3 1.10 also hides the playback position from a11y in its views for this reason). Use `liveRegion = Polite` only for "Download failed" style state changes |
| Seek bar | Operable | `Slider` with `stateDescription = "12 minutes 4 seconds of 45 minutes"` and custom actions "Skip back 10 s" / "Skip forward 30 s" |
| Testing | Automated | `androidx.compose.ui:ui-test-junit4-accessibility` (1.8.0+): `enableAccessibilityChecks()` in instrumented UI tests checks contrast, touch-target size and traversal order. Plus a manual TalkBack pass on each release |

### 13. Onboarding and empty states

There is no multi-step wizard. The empty states are the onboarding.

| Where | Condition | Content and actions |
|---|---|---|
| Feeds | No subscriptions | Illustration plus "Your feeds live here". Primary: **Search podcasts** (→ Discover). Secondary: **Import OPML or backup**, **Add a YouTube channel**, **Add by URL** |
| Feeds | Subscriptions but no groups | The "All" tab works. A dismissible card at the top: "Group podcasts into feeds like Tech, News, Fiction". **Suggested groups** come from `itunes:category` of existing subscriptions ("Technology (5) · News (3) · Fiction (2)"), and one tap creates them with members (open question) |
| Feeds › group | Empty group | "No podcasts in *tech* yet" plus **Add podcasts**, which opens the cover-grid member picker |
| Feeds › group | All played (Unplayed filter on) | "You're all caught up" plus "Show played episodes" |
| Feeds | Offline | A top banner "Offline – showing downloaded episodes". Non-downloaded rows are dimmed and play is disabled with an explanation in a tooltip / a11y |
| Library | No subscriptions | Same actions as the Feeds empty state |
| Up next | Empty | "Nothing up next. Long-press any episode → Add to Up next", plus "Play a group" chips for the top 3 groups |
| Downloads | Empty | "Downloaded episodes play offline" plus a storage summary and a link to auto-download settings |
| Discover | First open | Search field focused only on explicit tap (no keyboard pop). Chips: Top charts · Categories · Add YouTube channel · Import |
| Import | After picking a file | Preview first (counts, groups, duplicates), then a progress list where each row's cover appears as its feed resolves. This "covers popping in" moment is the first-run delight |
| Notifications permission (API 33+) | — | Ask *contextually*: on first download completion or the first new-episode notification opt-in, never at launch |

### 14. Glance widgets

- **Dependency:** `androidx.glance:glance-appwidget` + `glance-material3` **1.2.0** (stable 2026-08-26, minSdk 23). Use `GlanceTheme` with the system dynamic colours. Artwork tint is not possible on all launchers.
- **Now playing**: sizes 2×1 / 4×1 / 4×2 via `SizeMode.Responsive`.
  - Content: cover; title and podcast (4×1+); play/pause, −10 and +30 buttons; a progress bar on 4×2, updated on state changes only, not every second.
  - Data comes from a small widget state written by the playback service, with throttled `updateAll`.
  - Taps: the body opens the app with the player expanded. Play/pause goes through the media-button path that `playback.md` validates.
  - *UNVERIFIED:* whether a widget tap gives the FGS while-in-use capability under Android 17 background-audio hardening. If it does not, play/pause opens the app.
- **Group feed** (v1.1): the latest 5–10 unplayed episodes of a chosen group, with cover thumbnails, in a `LazyColumn`. A config activity chooses the group. Tapping a row opens episode detail.
- **Artwork:**
  - Use `ImageProvider(Icon.createWithContentUri(artworkProvider.uriFor(key)))`, which the 1.2.0 sources show is available, so the launcher reads the file through our exported read-only provider.
  - **Android 17** enforces a cap of `1.5 × screen width × screen height × 4` bytes for bitmaps and icons inside a `RemoteViews` parcel, and throws a fatal `IllegalArgumentException` when it is exceeded. URI icons keep us far below it.
  - If a launcher fails to load URI icons (test Pixel Launcher plus one OEM launcher), fall back to 192 px `Bitmap`s, at most 10 per widget.
- **Previews:** `GlanceAppWidget.providePreview` + `GlanceAppWidgetManager.setWidgetPreviews` (APIs added in 1.2.0-alpha01) show a generated preview in the picker on Android 15+. `setWidgetPreview` is rate-limited (about 2 calls per hour), so set it once after the first subscription and on app update. Older platforms get a static `previewLayout`.

### 15. Performance and testing

- **Lists and grids:**
  - Stable `key = episode.id` / `podcast.id`.
  - `contentType` for headers and rows.
  - `Modifier.animateItem()` only in Up next (reorder).
  - Avoid `SubcomposeAsyncImage` in lazy lists (the Coil docs warn that subcomposition is slower); use `AsyncImage` with `placeholder = ColorPainter(avg)`.
- **Startup:** a Baseline Profile covering the Feeds and Library scroll paths and the player expand, generated with Macrobenchmark. A splash screen (`core-splashscreen` 1.2.0) holds until the first DB emission, at most 400 ms.
- **Jank budget:** grid fling at 120 Hz with 3 columns of 384 px thumbs. Coil decodes on `Dispatchers.IO`-limited threads, and hardware bitmaps are the default on API 26+. *Not required in Compose:* Coil's `allowHardware(false)` advice for shared-element transitions is specific to View transitions. Verify in the spike that Compose shared elements render hardware bitmaps without issue.
- **Screenshot tests:** Compose Preview Screenshot Testing for `EpisodeRow` (every download and playback state × light, dark and pure-black × font 1.0, 1.5, 2.0), `CoverTile`, `MonogramPainter`, and the mini and full player with three reference artworks (colourful, monochrome, very light). Use Coil's `LocalAsyncImagePreviewHandler` to inject images in previews.

---

## Verified versions & facts

| Item | Value / fact | Source | Checked |
|---|---|---|---|
| Compose BOM | **2026.09.00** (latest). Maps ui / foundation / animation **1.12.1**, `material3` **1.4.0**, `material3-adaptive*` **1.3.0** | https://developer.android.com/develop/ui/compose/bom/bom-mapping ; https://dl.google.com/android/maven2/androidx/compose/compose-bom/maven-metadata.xml | 2026-10-04 |
| `material3` | Stable **1.4.0**; alpha **1.5.0-alpha29** (2026-09-23) | https://developer.android.com/jetpack/androidx/releases/compose-material3 ; https://dl.google.com/android/maven2/androidx/compose/material3/group-index.xml | 2026-10-04 |
| M3 1.4.0 has no Expressive public API | `MaterialExpressiveTheme`, `MotionScheme`, `expressiveLightColorScheme`, flexible app bars are `internal`; `ButtonGroup`, `LoadingIndicator`, wavy progress, `FloatingToolbar` absent | https://dl.google.com/android/maven2/androidx/compose/material3/material3-android/1.4.0/material3-android-1.4.0-sources.jar | 2026-10-04 |
| M3 1.5.0-alpha29 Expressive status | `MaterialExpressiveTheme`, `ButtonGroup`, `ToggleButton`, `HorizontalFloatingToolbar`, `LinearWavyProgressIndicator`, flexible app bars, `HorizontalMultiBrowseCarousel` are public without experimental annotation; `LoadingIndicator`, `MaterialShapes` still `@ExperimentalMaterial3ExpressiveApi`; `ModalBottomSheet` still `@ExperimentalMaterial3Api` | https://dl.google.com/android/maven2/androidx/compose/material3/material3-android/1.5.0-alpha29/material3-android-1.5.0-alpha29-sources.jar | 2026-10-04 |
| M3 1.5.0-alpha29 drags Compose core to alpha | POM depends on `foundation`, `ui`, `runtime`, `animation-core` **1.13.0-alpha01** | https://dl.google.com/android/maven2/androidx/compose/material3/material3-android/1.5.0-alpha29/material3-android-1.5.0-alpha29.pom | 2026-10-04 |
| M3 1.4.0 component stability | Stable: `PrimaryScrollableTabRow`, `SwipeToDismissBox`, `PullToRefreshBox`, `ShortNavigationBar`, `WideNavigationRail`, `FilterChip`, `SingleChoiceSegmentedButtonRow`, `BadgedBox`, `Slider`. Experimental: `TopAppBar`, `LargeTopAppBar`, `ModalBottomSheet`, `SearchBar`, `HorizontalMultiBrowseCarousel`, `TooltipBox` | material3 1.4.0 sources jar (above) | 2026-10-04 |
| `material3-adaptive-navigation-suite` 1.4.0 | `NavigationSuiteScaffold(navigationItems, navigationSuiteType, state, primaryActionContent, …)`; default type: compact → `ShortNavigationBarCompact`, tabletop or compact height → `ShortNavigationBarMedium`, else `WideNavigationRailCollapsed`; `NavigationSuiteScaffoldState.hide()/show()/toggle()`; `NavigationSuiteType.None` | https://dl.google.com/android/maven2/androidx/compose/material3/material3-adaptive-navigation-suite-android/1.4.0/material3-adaptive-navigation-suite-android-1.4.0-sources.jar | 2026-10-04 |
| Material 3 Adaptive | Stable **1.3.0** (2026-08-12), alpha 1.4.0-alpha02 (2026-09-09); `adaptive-navigation3` with `ListDetailSceneStrategy` / `SupportingPaneSceneStrategy`; L / XL width classes | https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive | 2026-10-04 |
| Window size breakpoints | Width: compact < 600, medium < 840, expanded < 1200, large < 1600, XL ≥ 1600 dp; height: < 480, < 900, ≥ 900. `currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)` | https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes | 2026-10-04 |
| Navigation 3 | Stable **1.2.0** (2026-09-23); `NavDisplay(sharedTransitionScope = …)`, `LocalNavAnimatedContentScope`, `predictivePopTransitionSpec`, `sceneStrategies = listOf(...)` | https://developer.android.com/jetpack/androidx/releases/navigation3 ; https://developer.android.com/guide/navigation/navigation-3/animate-destinations ; https://developer.android.com/guide/navigation/navigation-3/custom-layouts ; navigation3-ui 1.2.0 sources jar | 2026-10-04 |
| Shared element APIs | Stable since Compose animation **1.10.0** (no `@ExperimentalSharedTransitionApi`); latest stable animation 1.12.1 | https://developer.android.com/jetpack/androidx/releases/compose-animation | 2026-10-04 |
| Foundation 1.12.1 | `AnchoredDraggableState(initialValue, anchors)`, `Modifier.anchoredDraggable`, `HorizontalPager`, `LazyVerticalGrid`, `basicMarquee` stable | https://dl.google.com/android/maven2/androidx/compose/foundation/foundation-android/1.12.1/foundation-android-1.12.1-sources.jar | 2026-10-04 |
| `Modifier.dropShadow(shape, Shadow)` | Present and non-experimental in ui 1.12.1 | https://dl.google.com/android/maven2/androidx/compose/ui/ui-android/1.12.1/ui-android-1.12.1-sources.jar | 2026-10-04 |
| `Modifier.blur` | Only effective on Android 12+, ignored below | https://composables.com/jetpack-compose/androidx.compose.ui/ui/modifiers/blur (mirror of the KDoc) | 2026-10-04 |
| `PredictiveBackHandler` | In activity-compose **1.13.0**, not deprecated; last-composed enabled handler wins; `navigationevent-compose` 1.1.2 stable as the alternative | activity-compose 1.13.0 sources jar; https://dl.google.com/android/maven2/androidx/navigationevent/navigationevent-compose/maven-metadata.xml | 2026-10-04 |
| Coil | **3.6.3** (2026-09-18); 3.5.0 raised Android minSdk to 23 and made `memoryCacheMaxSizePercentWhileInBackground` non-experimental; 3.4.0 caches eligible 404s | https://repo1.maven.org/maven2/io/coil-kt/coil3/coil-compose/maven-metadata.xml ; https://coil-kt.github.io/coil/changelog/ | 2026-10-04 |
| Coil defaults | Memory cache 20 % of available app memory (15 % on low-RAM devices); disk cache 2 % of free space clamped to 10–250 MB; singleton disk cache in `tmpdir/coil3_disk_cache` | https://github.com/coil-kt/coil/blob/main/coil-core/src/androidMain/kotlin/coil3/util/contexts.kt ; .../coil3/disk/DiskCache.kt ; .../nonJsCommonMain/kotlin/coil3/disk/utils.kt | 2026-10-04 |
| Coil caching semantics | Ignores `Cache-Control` by default (opt-in `coil-network-cache-control`); explicit `memoryCacheKey` short-circuits key computation; computed key omits size unless transformations are set; non-2xx throws `HttpException` | https://github.com/coil-kt/coil/blob/main/coil-network-core/README.md ; .../coil3/memory/MemoryCacheService.kt ; .../coil-network-core/.../NetworkFetcher.kt | 2026-10-04 |
| Coil Palette / hardware bitmaps | Palette needs `allowHardware(false)`; View shared-element transitions are incompatible with hardware bitmaps | https://coil-kt.github.io/coil/recipes/ | 2026-10-04 |
| Coil + subcomposition | `SubcomposeAsyncImage` "may not be suitable for … LazyList" | https://github.com/coil-kt/coil/blob/main/coil-compose/README.md | 2026-10-04 |
| MaterialKolor | `material-kolor` **5.0.1** (2026-08-27); `material-color-utilities` 5.0.1 (pure Kotlin + poko annotations), 6.0.0-beta01 on 2026-10-01; README warns image extraction "can be pretty slow" | https://repo1.maven.org/maven2/com/materialkolor/ ; https://github.com/jordond/MaterialKolor | 2026-10-04 |
| MaterialKolor 5.0.1 Compose coupling | `material-kolor-android` POM depends on `org.jetbrains.compose.material3:material3-android:1.12.0-alpha03` (excluding androidx M3), which itself wraps `androidx.compose.material3:1.5.0-alpha22` | https://repo1.maven.org/maven2/com/materialkolor/material-kolor-android/5.0.1/material-kolor-android-5.0.1.pom ; https://repo1.maven.org/maven2/org/jetbrains/compose/material3/material3-android/1.12.0-alpha03/material3-android-1.12.0-alpha03.pom | 2026-10-04 |
| MCU contrast | `onPrimaryContainer` uses `ContrastCurve(3.0, 4.5, 7.0, 11.0)`, i.e. 4.5:1 at normal contrast; `contrastLevel` −1..1; `Score.score(fallbackColorArgb: Int?)` can return empty with `null` fallback | https://github.com/jordond/MaterialKolor/tree/main/material-color-utilities/src/commonMain/kotlin/com/materialkolor (dynamiccolor/ColorSpec2021.kt, ContrastCurve.kt, score/Score.kt) | 2026-10-04 |
| `UiModeManager.getContrast()` | API 34; −1.0..1.0 | https://developer.android.com/reference/android/app/UiModeManager ; https://developer.android.com/reference/android/app/UiModeManager.ContrastChangeListener | 2026-10-04 |
| `androidx.palette` | 1.0.0 stable; 1.1.0-alpha01 (2026-07-01) merges `-ktx` into the main artifact | https://developer.android.com/jetpack/androidx/releases/palette ; Google Maven metadata | 2026-10-04 |
| MDC content-based dynamic colour | `DynamicColorsOptions.setContentBasedSource(Bitmap)`, "only available for S+" (Views) | https://github.com/material-components/material-components-android/blob/master/docs/theming/Color.md | 2026-10-04 |
| Compose dynamic colour | `dynamicLightColorScheme` / `dynamicDarkColorScheme` are `@RequiresApi(S)` | material3 1.4.0 sources (`DynamicTonalPalette.android.kt`) | 2026-10-04 |
| Media3 | **1.11.1** (2026-09-10). `media3-ui-compose` state holders (`rememberPlayPauseButtonState`, `rememberProgressStateWithTickInterval`, `rememberPlaybackSpeedState`, `rememberCurrentMediaItemState` (1.11)), `media3-ui-compose-material3` `Player`, `ProgressSlider`, `PlaybackSpeedToggleButton` (1.10), `MiniController`, `ErrorText` (1.11) | https://developer.android.com/jetpack/androidx/releases/media3 ; Google Maven metadata | 2026-10-04 |
| Glance | Stable **1.2.0** (2026-08-26), minSdk 23; alpha 1.3.0-alpha02; generated-preview APIs (`providePreview`, `setWidgetPreviews`) added in 1.2.0-alpha01; `ImageProvider(icon: Icon)` exists | https://developer.android.com/jetpack/androidx/releases/glance ; glance 1.2.0 sources jar | 2026-10-04 |
| Generated widget previews | Android 15+; `setWidgetPreview` rate-limited about 2 calls per hour | https://developer.android.com/develop/ui/compose/glance/generated-previews | 2026-10-04 |
| Android 17 widget bitmap cap | Apps targeting 37: bitmaps + icons in a `RemoteViews` parcel are limited to `1.5 × W × H × 4` bytes, or a fatal `IllegalArgumentException` | https://developer.android.com/about/versions/17/behavior-changes-17 | 2026-10-04 |
| Android 17 large screens | The resizability / orientation opt-out is unavailable for apps targeting 37 | https://developer.android.com/about/versions/17/behavior-changes-17 | 2026-10-04 |
| Android 16 behaviour | Target 36: edge-to-edge opt-out disabled; predictive-back system animations on and `onBackPressed` not called; orientation, resizability and aspect-ratio restrictions ignored on sw ≥ 600 dp; `elegantTextHeight` ignored | https://developer.android.com/about/versions/16/behavior-changes-16 | 2026-10-04 |
| Play target API | New apps and updates must target API 36 from 2026-08-31 (extension to 2026-11-01) | https://developer.android.com/google/play/requirements/target-sdk | 2026-10-04 |
| Font scaling | Android 14: non-linear scaling to 200 %; use `sp`, `sp` line heights; `fontScale` informational only | https://developer.android.com/about/versions/14/features#non-linear-font-scaling | 2026-10-04 |
| Touch targets | 48 dp minimum; Compose expands small clickables' touch area | https://developer.android.com/develop/ui/compose/accessibility/api-defaults | 2026-10-04 |
| Compose a11y test checks | `ui-test-junit4-accessibility` since 1.8.0; `enableAccessibilityChecks()` checks contrast, touch-target and traversal | https://developer.android.com/develop/ui/compose/accessibility/testing ; Google Maven `androidx/compose/ui/group-index.xml` | 2026-10-04 |
| Nav bar destination count | M3: navigation bar for 3–5 (guidance text: "two to five") destinations; use a rail or drawer beyond five. *m3.material.io did not render for the fetcher; read via a mirror of the M3 guidance* | https://m3.material.io/components/navigation-bar/guidelines ; https://www.sap.com/design-system/fiori-design-android/v25-8/components/m3-standard-components/navigation-bar/usage | 2026-10-04 |
| Material Icons library | "no longer maintained or recommended … can increase build time significantly"; use Material Symbols XML from Google Fonts | https://developer.android.com/develop/ui/compose/graphics/images/material | 2026-10-04 |
| Themed app icons | `<monochrome>` layer since Android 13; Android 16 QPR2 auto-themes icons without one | https://developer.android.com/develop/ui/views/launch/icon_design_adaptive | 2026-10-04 |
| Apple show cover spec | Square 1400–3000 px (3000 preferred), PNG or JPG, no transparency | https://podcasters.apple.com/support/5514-show-cover-template | 2026-10-04 |
| `podcast:image` | Multiple per channel / item; `aspect-ratio`, `width`, `purpose` tokens (artwork, social, canvas, banner, circular, …) | https://podcasting2.org/docs/podcast-namespace/tags/image | 2026-10-04 |
| YouTube thumbnails **[tested]** | Channel Atom feed `media:thumbnail` = `hqdefault.jpg` 480×360 (letterboxed); `mqdefault` 320×180; `maxresdefault` / `hq720` 1280×720; old video → `maxresdefault` / `hq720` / `sddefault` 404 with a 120×90 body | `curl https://www.youtube.com/feeds/videos.xml?channel_id=UC_x5XG1OV2P6uZZ5FSM9Ttw` ; `curl https://i.ytimg.com/vi/{jNQXAC9IVRw,8Xv5BLnXzgI}/<name>.jpg` ; letterbox facts also at https://www.binarymoon.co.uk/2014/03/using-youtube-thumbnails/ | 2026-10-04 |
| Other AndroidX | `graphics-shapes` 1.1.0, `window` 1.5.1 (1.6.0-alpha05), `core-splashscreen` 1.2.0, `activity-compose` 1.13.0, `lifecycle` 2.11.0 stable | Google Maven `maven-metadata.xml` per artifact | 2026-10-04 |

Not verified:
- Whether a Glance widget tap grants the playback FGS while-in-use capability under Android 17.
- Whether every Compose animation honours "Remove animations".
- That Compose shared elements need no `allowHardware(false)`.
- That launchers load `content://` `Icon`s in `RemoteViews` reliably.

Each is flagged in place.

---

## Pitfalls & edge cases

1. **Pager versus row swipe.** Horizontal drags inside pager pages are captured by `SwipeToDismissBox`, so the pager only swipes from gaps. Pick one per screen (the default is pager in Feeds). Never ship both "half-working".
2. **Group identity in the pager.** Groups get reordered, renamed, added and deleted, often from another screen. Key pages by group id, persist the *selected group id* (not the index), and handle "the selected group was deleted" by falling back to "All" with a snackbar.
3. **Arbitrary group names.** Names are up to 40 characters and may contain emoji, RTL scripts or near-identical text. Ellipsize at a 200 dp tab width, and test Arabic and Hebrew (the tab row mirrors). The name is the a11y label, so "tech" and "Tech" must not both exist (`nameKey` uniqueness in `opml-groups.md`).
4. **Many groups.** With more than ~12, the tab row becomes a scavenger hunt. The "All groups" sheet (mosaic grid, alphabetical with a recent section) is the escape hatch. Also consider letting users hide groups from Feeds while keeping them in Library.
5. **"All" can be huge.** 300 feeds give about 50k episodes. It must be paged (Room `PagingSource`), with a `LazyColumn` `stickyHeader` per day bucket computed in the pager's `insertSeparators`, not in SQL.
6. **High-churn joins re-query the feed every 10 s** during playback (`opml-groups.md`). Use the §9 live-state overlay. A tempting shortcut ("just join `play_state`") will show up as battery drain and scroll hitches only in long sessions.
7. **Seed extraction on monochrome art.** With MCU's default fallback, black-and-white covers get "Google Blue". Use `fallbackColorArgb = null` and treat empty as "no tint".
8. **Very light or very dark art.** `SchemeContent` keeps the art's tone in `primaryContainer`, so a nearly white cover gives a nearly white header in dark mode, which looks like a flash. Clamp the container tone to ≤ 40 in dark mode (`modifyColorScheme`-style post-process), or use `SchemeTonalSpot` when the seed's tone is > 90 or < 10.
9. **Animated scheme cost.** Animating all 48 roles allocates 48 animation states per artwork change. Animate the ~12 roles the player uses; snap the rest.
10. **Shared-element flash.** The detail request at a larger size misses the memory cache and shows the placeholder mid-transition. Fix it with the explicit two-tier keys plus `placeholderMemoryCacheKey` (§6.4). Shared keys must be unique per *source screen* (`"cover-lib-$id"` versus `"cover-feed-$id"`), because the same podcast can appear in two tab stacks.
11. **Artwork URL changes.** Bump `artwork.version` so memory keys change, re-pin the file and recompute the seed. Otherwise the old cover persists for the process lifetime.
12. **Huge or odd images.** Covers of 5000 px or 15 MB PNG decode fine (Coil downsamples), but the first load on mobile data is heavy. The store normalises to ≤ 1024 px after one fetch. Non-square covers (old RSS `image/url` logos, 144 px wide) are centre-cropped in tiles, and the monogram is used when the image is < 128 px. Transparent PNGs (against Apple's rules but seen in the wild) get a `surfaceContainerHighest` backdrop. SVG and other unsupported formats fail and fall back to the monogram (do not add `coil-svg` for this).
13. **Cleartext `http://` artwork.** It is blocked by default. Coordinate with the networking policy: try the `https://` upgrade first, otherwise fall back to the monogram. Never silently enable global cleartext.
14. **Letterboxed YouTube `hqdefault`.** A 1:1 crop keeps the black bars (§6.5). Shorts (if not filtered out per `youtube.md`) have vertical frames, so centre-crop is fine, but do not show them at 16:9.
15. **YouTube duration unknown** in Atom. Do not render "0 min"; omit it until known.
16. **`play` flavour external items.** They cannot be queued, downloaded or played in-app. Hide the download and play buttons, and exclude them from "Play group", Downloads and Up next drag targets. A group consisting only of YouTube channels in the `play` flavour still works as a browsing feed.
17. **Offline.** Coil's disk cache may be purged; pinned art covers subscriptions and downloads. Search results and previews of unsubscribed podcasts are best-effort. Never block a screen on artwork.
18. **Memory while backgrounded.** An audio app sits in the background with an FGS for hours. Trimming the image cache to 25 % in the background (Coil 3.5+) reduces LMK pressure on the playback process. Do not keep hero bitmaps in ViewModels.
19. **Status bar icon colour.** Artwork-tinted headers with light art under light icons are unreadable. Set icon appearance from the scheme and keep the 48 dp scrim. Restore it when leaving, including on predictive-back cancel.
20. **Predictive back ordering.** If the player sheet's `PredictiveBackHandler` is composed *before* `NavDisplay`, back pops the underlying screen while the player is expanded. Compose it after, and always call it unconditionally with `enabled = …` (activity-compose KDoc warns about conditional calls).
21. **Nav suite hide/show.** Animating the nav bar out while also animating the sheet can double-count insets for one frame. Drive both from the same progress, or hide only after `Expanded` settles.
22. **Orientation.** No locks anywhere. On ≥ 600 dp they are ignored on API 36+ and cannot be opted out of for API 37 targets. A video full-screen mode must not rely on `setRequestedOrientation`.
23. **Font scale 200 % plus five nav labels.** Labels may truncate in `ShortNavigationBar`. Verify, and accept icon-only for inactive items if needed (M3 allows it for 4–5 destinations).
24. **TalkBack and swipe-only features.** Every swipe action needs a custom action. The pager needs tabs. Dragging the player needs an Expand / Collapse action. Reordering Up next needs "Move up" / "Move down" actions.
25. **Widget limits.** Bitmaps in `RemoteViews` count toward the Android 17 cap; prefer URI icons. `setWidgetPreview` is rate-limited, so do not call it on every refresh.
26. **Expressive later.** When migrating, `MaterialExpressiveTheme` changes default shapes, motion and colour spec (SPEC_2025). Screenshot tests catch the visual diff. Keep component usage behind `:core:designsystem` wrappers (`NdButton`, `NdProgress`, `NdTopBar`, `NdLoading`) so the swap is local.
27. **Experimental opt-ins.** `TopAppBar`, `ModalBottomSheet` and `SearchBar` need `@OptIn(ExperimentalMaterial3Api::class)` in 1.4.0. Set the opt-in once in the design-system convention plugin, and keep usages inside `:core:designsystem` so API changes stay contained.

---

## Open questions for the product owner

1. **Material 3 Expressive at launch?** It is only available in alpha, and it pulls Compose core 1.13 alpha into the app. The recommendation is to launch on stable M3 and upgrade when 1.5.0 reaches RC. Is that acceptable?
2. **Top-level destinations.** Five (Feeds, Library, Up next, Downloads, Discover), or four with Up next inside the player only?
3. **Gesture in Feeds.** Swiping left or right switches groups (recommended), or performs row actions (mark played, add to Up next)? Users can change it either way. Which is the default?
4. **Which virtual feeds appear as tabs?** "All" first (recommended)? An "Ungrouped" tab (default off)? Should users be able to hide a group from Feeds but keep it in Library?
5. **Artwork-tinted player and podcast header by default?** Or wallpaper colours everywhere for a calmer look?
6. **Library default.** Grid with titles or covers only? Default density: 3 columns on phones?
7. **Group appearance.** Colour only, colour plus an icon from a curated set, or allow emoji? Is the 2×2 cover mosaic the right group tile, or should users be able to pick a "group cover"?
8. **Episode art versus podcast cover in rows.** When a show uses per-episode art, show it (recommended), or always show the show cover for recognisability?
9. **YouTube items in rows.** Show the video thumbnail (recommended) or the channel avatar? Should YouTube items carry a visible "video" badge?
10. **Large screens.** Is the persistent "Now playing" side panel on tablets and desktop wanted, or should the full-screen player be kept everywhere?
11. **Onboarding content.** Should the first run offer suggested groups from podcast categories, and/or curated starter packs ("Tech", "News", "Fiction")? Starter packs need editorial upkeep.
12. **Widgets in v1.** "Now playing" only, or also the "Group feed" widget?
13. **Brand.** A brand seed colour and app icon (with a monochrome layer)? A custom typeface, or the platform font?
14. **Accessibility commitment.** Should we state WCAG 2.2 AA as a release gate, which makes the automated checks blocking in CI?
