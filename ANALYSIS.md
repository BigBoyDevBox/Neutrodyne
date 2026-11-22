# Neutrodyne design backlog

This is the open design and engineering backlog for the radio home, the shared
radio parts and the macOS experience. It is the document a future agent should
read before picking work. [Product plan](docs/PLAN.md) is the source of truth for
requirements and decisions; [radio design](docs/design/08-ui-ux.md#radio-home)
elaborates the cabinet. Keep the material and control vocabulary from PO-55: the
walnut cabinet, enamel signs, paper and tactile controls distinguish navigation,
programme and transport. Native window and menu behaviour matters alongside the
shared cabinet.

Review history: a first pass on `f9bd91021`, a polish pass on `a9b0e114f`, a
native-verification pass on 2026-11-18, a fourth source pass on `ad8dc5399`, and
an independent pass on `c542b073e` (2026-11-22) which is the current head. The
2026-11-22 pass re-read the radio, feeds adapter, desktop shell, sync server and
download data layer; its findings are folded in below and marked **new** where
earlier passes had not recorded them. A further independent pass on the same
head verified which earlier items were already fixed and added the feed-cache
batching, DNS-pinning, credential-clock and unaddressable-key items, also
marked **new** below. A second independent pass on the same head confirmed the
window-placement AWT values and the Compose desktop-density source, and opened
the five fixes listed under Done since the last consolidation; its review is
consolidated here without losing the earlier passes' detail.

## Done since the last consolidation

- **The home's compact and short layouts follow the usable height (PR #578).**
  `radioLayout(width, usableHeight, fontScale)` is pure and unit-tested.
- **The home Menu key is announced as a button (PR #579).**
- **Transport status while buffering, ended or failed (PR #575).** The radio
  prints BUFFERING, ENDED or PLAYBACK ERROR from the loaded player's phase, with
  the pilot lamp amber while buffering and red on an issue.
- **Arrow-key tuning (PR #576).** Up and Down move the selection, scroll it into
  view and carry focus.
- **Global shortcuts stood down in sync fields (PR #577).**
- **An empty radio offers its filling routes (PR #580), with Import and the
  NOT TUNED dial (PR #583).**
- **The third pass's fixes are merged.** PRs #581-#597 and #599 cover the
  deferred desktop refresh, programme-name search, empty-radio tuning, headings
  and roles, thin dial windows, haptics, the forbidden-enclosure password prompt,
  parked seeks, valve warm-up, sync and update copy, spoken elapsed time, new-start
  played marks, retained Discover results, the unknown-duration dial and desktop
  Previous. The earlier "awaiting merge" note is stale.
- **Three small fixes from the 2026-11-22 pass are in review:**
  [#609](https://github.com/L-K-M/Neutrodyne/pull/609) arrow-key tuning no longer
  jumps the list for a slip that is already visible and reveals a half-visible
  slip at the nearest edge;
  [#610](https://github.com/L-K-M/Neutrodyne/pull/610) the Menu key label is
  capped at one line (the key-press sound itself landed on main);
  [#612](https://github.com/L-K-M/Neutrodyne/pull/612) the chooser saver keeps
  the focus hand-off and reads old state defensively.
- **The empty-programme spinner gate from the same pass landed on main** (a
  partial page keeps the spinner, a failed page keeps its retry row); the
  follow-up branch [#611](https://github.com/L-K-M/Neutrodyne/pull/611) was
  closed as superseded.
- **Three more small fixes from the independent pass are in review:**
  [#619](https://github.com/L-K-M/Neutrodyne/pull/619) binds the macOS Window
  menu's Minimise and Zoom to Cmd+M and Ctrl+Cmd+F;
  [#622](https://github.com/L-K-M/Neutrodyne/pull/622) chunks
  `DownloadDao.downloadedEpisodeIds` at the 500-row download bind size;
  [#627](https://github.com/L-K-M/Neutrodyne/pull/627) prefers the programme
  row's metadata over a stale player snapshot for the selected episode.
- **The chooser marks the tuned playlist and focuses it** (`cec78f017`), and
  **Settings slip values yield to the title's longest word** (#562). The older
  "Mark the tuned playlist in the chooser" and mid-word title sections are
  done.
- **Three small fixes from a further pass are in review:**
  [#623](https://github.com/L-K-M/Neutrodyne/pull/623) a VERIFYING download's
  row key cancels like RESOLVING instead of being a dead tap;
  [#624](https://github.com/L-K-M/Neutrodyne/pull/624) the podcast
  filtered-empty action reads "Clear filters" unless the sole active filter is
  the unplayed one, matching Feeds;
  [#625](https://github.com/L-K-M/Neutrodyne/pull/625) link-code polling stops
  at the code lifetime instead of polling forever when the server keeps
  answering `authorization_pending`.
- **Five more small fixes from a second independent pass are in review:**
  [#615](https://github.com/L-K-M/Neutrodyne/pull/615) Escape goes back from a
  focused text field instead of being consumed with no action;
  [#618](https://github.com/L-K-M/Neutrodyne/pull/618) applies a paging
  snapshot that arrives during Undo;
  [#628](https://github.com/L-K-M/Neutrodyne/pull/628) keeps a swipe that
  starts on a slip's play key from discarding it;
  [#629](https://github.com/L-K-M/Neutrodyne/pull/629) gives a programme slip a
  button role;
  [#630](https://github.com/L-K-M/Neutrodyne/pull/630) letters a sign whose name
  ends in a space at its full size.

## Playback and programme correctness

### Reconcile paging during Undo and clarify repeated discards

The discard race has a tested fix in (#564, merged). During Undo, `onRows` still
defers a paging snapshot;
[#618](https://github.com/L-K-M/Neutrodyne/pull/618) applies it after a
successful un-play write and re-inserts the restored row when the newer page does
not carry it. Complete the failure path too: a failed un-play write must keep the
removal and must not restore the row. Give rapid repeated discards explicit
feedback while the first write is pending: `discard` returns early while
`writing`, so a second swipe is dropped with no acknowledgement.

### Add a recovery route and Cancel while buffering

The status and lamp read BUFFERING, ENDED and PLAYBACK ERROR from the loaded
player's phase and issue. What is still missing is the way out: a route to the
existing recovery action where applicable, and Cancel staying available while a
stream buffers. Test a delayed stream, metered consent, engine refusal, replay,
and a switch from a failing item to a healthy one. Avoid inventing a new source
of playback truth in the UI.

### Keep all transport functions reachable at high text scales

`RadioPlayer` hides the skip keys below its combined width estimate. The narrow
layout relies on the needle, which requires a known duration and cannot apply
the listener's skip intervals. Provide a second layout for the keys with 48 dp
targets and the printed intervals. Use fewer scale labels where they collide.
Test both skip values, unknown durations, three-hour media and 2.0 text at
320 dp, in German and with keyboard access.

Window width also matters. At 390 x 844 both keys flank the counter; at
700 x 900 the right pane narrows enough to hide them. Retain the 600 dp
two-column transition. Compare a second compact bank with reallocating pane width
at 599, 600 and 700 dp and 1x, 1.3x and 2x text. Keep the four Nixie digits
legible. At the 600 dp minimum width the home already takes the two-column
layout: the speaker grille takes half the width, both skip keys disappear and
slip titles cut to two short lines. Consider a 720 dp threshold for the split or
a speaker that yields width before the keys do.

### Give long playlists a reliable count

`RadioViewModel.onRows` asks the repository for an unplayed count on every
incomplete paging snapshot. The result is stored as `RadioState.episodeCount`,
but the dial only prints its position when the loaded episode list is complete.
Show the total as a localised unplayed count for partial pages, and show a
position only when the episode's ordinal is known. The loaded window index is
not the ordinal once `placeholdersBefore > 0`, so either carry the leading
placeholder count in `RadioState` or print the count without a position.
`RadioPlaylist` already carries `podcastCount` and `radio_meta`
("%1$d podcasts - %2$d episodes") is defined in EN and DE, but neither is drawn.
A failed count currently reports a write error for a read; a failed count should
leave the total unknown. Test successive append, invalidation, count failure,
discard and Undo, and a playing episode outside the selected group.

### Replace the last Material buttons on the radio home

`RadioProgramme` renders the paging Retry action and the discard Undo action with
Material `TextButton`, painted with `RadioText`. Their ripple, touch metrics and
font metrics are foreign to the paper tray, while every other action on the radio
is an ivory key or a bat switch. Restyle both as momentary piano keys without
changing the test tags (`radio_retry`, `radio_undo`), the focus hand-off after a
discard, or the single-line error/removal rows. Check the row height at compact
sizes and at 1.3x text.

### Let the radio play subscriptions without a group

PLAN R2.3 says two virtual feeds always exist, All and Ungrouped. The radio
offers only real groups, and `RadioViewModel.start()` returns early without one,
so a subscribed but ungrouped podcast cannot be played from the only full player.
All survives only behind `Route.SelectFeed(null)` from notifications, which opens
the legacy `FeedBrowserKey` screen. Owner decision: add an "All podcasts" station
first in the chooser, backed by `FeedSource.All` (it honours `includeInAll`), so
subscribing is enough to start listening while groups stay the curated inboxes.
Test a library with no groups, a podcast excluded from All, and a switch from All
to a group and back.

### Bring Android and desktop playback into parity

A review of `playback/*` found these differences. Two are already fixed: a
desktop enclosure answering 403 now asks for a password (#588), and Android marks
an outgoing episode in its near-end window played on a new start (#593). Verify
the rest against the code before fixing each with a regression test in
`playback/impl` (Robolectric harness in `LifecycleHarness.kt`) or
`playback/desktop` (`DesktopPlaybackTimingTest`).

- Android does not re-prepare an errored current item. After an untyped
  ExoPlayer error, an exhausted audio retry or an unplayable last item, Play
  reaches `StartOutcome.AlreadyCurrent` and calls `play()` in `STATE_IDLE`, so
  nothing resumes. The desktop re-prepares
  (`DesktopPlaybackController.startLocked`). See `AndroidServiceBridge.kt:296-309`
  and `ErrorRecovery.kt:306-330`.
- The desktop persists a user seek before the engine confirms it
  (`DesktopPlaybackController.kt:745`). A seek during a network drop stores a
  position that was never heard.
- The desktop never applies smart resume: `PlayStarter.start` gets the default
  `SmartResumePolicy.DISABLED` and no `StartPositionRule` call passes
  `smartResume`, although the synced setting defaults on.
- Per-podcast volume boost is a no-op: `BoostLimiterProcessor.onConfigure`
  returns `NOT_SET`, so Media3 skips it, and `boostDb` never reaches
  `EffectivePlayback` or the desktop `GainStage` (`PlayerFactory.kt:178`).
- Per-podcast intro and outro trim (`introSkipMs`, `outroSkipMs`) are stored,
  merged and backed up but never applied.
- Android's explicit start does not record the outgoing item in
  `PlaybackHistory`, so Previous after tapping another episode skips it
  (`QueueProjector.kt:629`; the desktop records it).
- A failed commit in the desktop's `advance` leaves `engineEndedHandled`
  latched, so playback stops and never advances
  (`DesktopPlaybackController.kt:846-906`).
- `EffectivePlaybackSettings.prepareWindow` issues one scope query and two
  global reads per podcast on every projection and clears its cache each time.
  Add a batch resolver like `notifications()`.
- `QueueProjector.onPositionDiscontinuity` mints a new `loadId` before its
  late-echo guard, so a "Marked played on ..." notice for the first occurrence
  can be dropped (`QueueProjector.kt:583-590`).

## Cabinet, layout, and accessibility

### Make the cabinet fit the useful viewport

The transport and the slips now follow the usable height after the system bars.
In the narrow desktop fixture the transport and deck leave roughly one full slip.
The small layout permits nested vertical scrolling once the cabinet exceeds its
viewport. Keep the valves and selection visible, but measure the usable space
inside the insets and define a minimum for the slip list. Reduce the speaker
opening and panel padding first, then use a more compact arrangement of the dial
and transport. Choose an intentional scroll mode if the total still cannot fit.
Test 600 x 480, 800 x 500, 1200 x 800 and the phone and tabletop presets at 1.0,
1.3 and 2.0 font scales, including a two-line title, Retry alongside Undo, and
enough episodes to require scrolling. The deck now shows whenever the window is
tall enough (owner decision 2026-11-17); the 560/440/500 dp minimums are still
unmeasured.

### Give the playlist sign room and a legible fallback

`RadioProgramme` reserves an invisible key's worth of width to centre the sign
opposite Menu. The 320 x 568 and 1.3x text desktop presets ellipsize even
"Morning rotation." Keep the enamel treatment. Align the sign to the paper
tray's reading column or move Menu to a separate corner when the measured sign
cannot fit. Show the complete name in the chooser and expose it through desktop
hover or focus when clipped. Compare English and German, two long names with the
same prefix, and 320 dp plus 600 dp widths.

### Add a usable Mac programme scrollbar

The home list in `RadioProgramme` lacks the scrollbar drawn by `FeedsScreen` and
other scrolling destinations. In a short window a full row can fill the paper
tray with little indication of more material below. The shared `NdScrollbar`
overlay draws a thumb without drag support and hides it when all rows are even
partly visible. PRs #569/#570 cover the draw exception and the minimum thumb
bounds. Address the partial-row case before wiring the overlay into the radio,
or provide a draggable desktop scrollbar in the paper-tray gutter. Compare the
treatments at 700 and 1200 dp, with a long list, one clipped row, a tray shorter
than the thumb minimum and a fully fitting list. Test wheel and trackpad
movement, and keep the play target unobstructed. The loaded window is the
scrollbar's extent until pagination supplies more rows.

### Establish a material and type hierarchy

The desktop radio draws several borders, screws, wood grain, glass highlights
and small mono labels into the same few hundred pixels. Use a quiet paper slip
under readable ink, crisp sans-serif metadata, and retain mono for the dial,
counters and short engraved terms. Keep a single direct light direction across
the faceplates, flatten seams where the two trays meet, and check the
reduced-contrast engraved labels on warm paper and charcoal. Start with the
radio home and one shared page. Compare normal and selected slips, hover,
keyboard focus, missing cover art, and Retina plus 1x rendering.

The home installs its own light scheme in `RadioScreen`. Many physical legends
draw fixed ink even when `NeutrodyneTheme` honors the system contrast request.
Sample the 11 sp metadata and 10 sp timeline against the lightest and darkest
grain under the letters. Measured contrast to start from: inactive Nixie digits
are 2.0:1 against the glass, the failed-downloads lamp 3.95:1, and the Library's
New group drawer label about 3.1:1. The selected slip is a `0x22E4BF71` tint
that is hard to tell from its neighbours; try a brass edge marker or a printed
pointer before a stronger tint.

### Replace the last Material surfaces on radio pages

These still use Material shapes on the radio's materials: the Library's "No
groups yet" icon and pill, Discover preview's Subscribe and + New group pills,
the podcast page's + Group chip and More link, the speed sheet's chips, slider
and segmented button, the Up next and Downloads text buttons, the Menu plate's
flat tan Close key, and the light drag-and-drop overlay on the charcoal stage.
The podcast page header has no enamel title, and Subscribed is a dot and text
where the mockup shows a toggle. In the group editor the member check badge
overlaps cover lettering. Replace each with the existing keys, switches and
signs, and compare goldens at 390 and 1200 dp.

### Close the remaining accessibility and localisation gaps

Radio slips expose no explicit role. `Modifier.selectable` does not default to
`Role.Tab` (its role parameter is null), so the earlier claim that the tree
reads a list of tabs is false;
[#629](https://github.com/L-K-M/Neutrodyne/pull/629) picks `Role.Button`
deliberately; verify with TalkBack. The chooser rows and the Sync link-method
selector are fixed. German lacks the crash-dialog strings
(`app/src/main/res/values-de`). `downloadSize` hard-codes the decimal point and
unit words, and the queue position "01 / 12" is assembled in code. On Android,
`RadioCabinetPage` already ends a page at the keyboard with
`aboveKeyboard(..., WindowInsets.ime)`, so the earlier "no `imePadding`
anywhere" claim is stale; verify the Sync, credentials and group-editor fields
with the keyboard up rather than assuming no IME handling. Test each with
TalkBack, VoiceOver and the German locale.

### Measure switch hit areas in dense panels

`RadioSwitch` requests 30 x 40 dp for the painted plate and passes the same
modifier to the interactive `BatToggle`. A probe on this Compose version found
that `Modifier.minimumInteractiveComponentSize()` does not widen the pointer
region here, so meeting the target needs a real layout change. Measure actual
hit regions between adjacent controls in Import, Groups and Playback speed
before sizing the surrounding panel. For standalone switches, reserve a distinct
48 x 48 dp area around the current plate (a centred plate in a 48 dp box, not a
larger plate). Verify corner taps, lever flicks, disabled controls and vertical
scrolling at 2x text. For rows that own their switch action, retain one
accessible target.

### Place the Mac window in the display's usable coordinates

A live AWT probe on this Mac reported primary bounds 1920 x 1080, an identity
default display transform, a normalizing transform of 0.9583, 69 DPI, and
top/bottom insets of 30/90. `WindowIcons.primaryScreen` passes the bounds and
physical DPI to `defaultWindowBounds`, which multiplies by 96/69. This computes
a first-window origin near (736, 351) for a 1200 x 800 cabinet, so the window
spans 736-1936 of a 1920 px screen and its right and bottom edges extend past the
display even before the Dock. The normalizing transform and the physical DPI are
two descriptions of the same scale; applying both is the suspected defect.

Verify the Compose-to-AWT position on a clean launch at 1x and Retina 2x, with
the Dock on two edges. Convert from the native usable coordinate space exactly
once. Verify the 600 x 480 minimum and a secondary display with a negative
origin. Check Windows fractional scaling and Linux scaling before sharing the
conversion across platforms. A second pass reproduced the same AWT values and
confirmed that Compose's desktop density is
`GraphicsConfiguration.getDefaultTransform().getScaleX()` and that Compose's own
`WindowLocationTracker` places windows directly in `getBounds()`'s space, so the
conversion should treat the bounds as user-space units. A branch
(`fix/mac-window-placement`) is exploring it.

### Make the Mac window feel native

Use `apple.awt.fullWindowContent` and `apple.awt.transparentTitleBar` on the
root pane, a `WindowDraggableArea` for the top band and about 28 dp for the
traffic lights, so the charcoal stage reaches the window top. With
`apple.awt.application.appearance=system`, a light-mode Mac now shows a light
title bar over the charcoal stage. `java.awt.Taskbar` offers a Dock menu (Play
or Pause, skip back and forward, the current title) and an opt-in icon badge
(unplayed in the tuned playlist); the app uses neither. Guard each with
`Taskbar.isSupported`: the Dock menu and text badge are macOS features, and
Windows offers neither. Window > Minimise lacked Cmd+M and Zoom lacked
Ctrl+Cmd+F; #619 binds them and stands them down with the other accelerators
while a text field is focused. The window title drops the episode as soon as
playback pauses (`NeutrodyneWindow.kt:304`); macOS convention keeps the current
title while the episode is loaded, so record the decision. Window bounds are
not persisted (MD4): `desktop.window_bounds` should be written one second after
the last move or resize and at quit, and restored with the 50% overlap rule.
Navigation state is lost on relaunch; restoring the last playlist already works
through settings. Check each in full-screen Spaces and with VoiceOver.

### Preserve veneer proportions during live resize

`RadioMaterials` stretches the cabinet wood image with FillBounds while the
plinth uses Crop. Capture one cabinet at portrait, ordinary Mac window and
ultrawide sizes at both display scales. Compare the existing veneer with an
aspect-preserving crop anchored at the same corner, keeping the bezel, lighting
and screws fixed. Select a treatment only if the grain holds its proportions
without an obvious seam or a distracting jump during live resize.

### Check the dial end labels and slip row geometry

A vision pass over the committed desktop goldens (which pass on HEAD) flagged
three things to confirm in a render: the rightmost waveform time label appears
clipped by the panel edge in the phone, small, landscape and tablet presets;
slip row heights vary when a title wraps to two lines while its neighbours stay
on one; and the group editor's dark cabinet reads inconsistently with the light
pages, although the code sets `dark = false` (confirm whether that is the radio
stage or a theme bug). Confirm before changing the dial's `SpaceBetween` labels,
the row minimum height or the editor theme.

## Confirmed small defects

- **A swipe that starts on a slip's play key can discard it.** The horizontal
  drag detector is attached to the whole `MaterialPlate`, including the 48 dp
  play key, the most tapped target on the slip. Restrict the gesture to the
  cover and text, or ignore drags that begin on the key.
  [#628](https://github.com/L-K-M/Neutrodyne/pull/628) makes the play key
  consume horizontal drags; the swipe tests start on the text and a regression
  test swipes the key.
- **`RadioInsert` restores no focus target after process death** when the
  chooser was closed but the hand-off had not completed; #612 persists the flag.
- **`RadioInsert` positioning is inset-sensitive.** It places the paper from the
  faceplate's `boundsInWindow` and a hand-computed dialog inset; re-check it on
  a wide pane row and with different dialog insets.
- **The Menu key label can wrap and un-centre the sign**; #610 caps it at one
  line.
- **The home's Menu key click sound landed on main**; its hand-drawn radial cap
  could become the shared `IvoryKeyCap`, but that is a visual change to the
  golden-covered radio home, so record the goldens first.
- **`ninePatch` could draw overlapping slices on a very narrow node**; the
  `ninePatchStops` clamp fixed it. `ValveDeck`'s tiling still has no unit test:
  extract the slice plan into a pure function and test the mirrored-tile, forced
  odd-count and narrow-node branches.
- **The deck constants are duplicated.** `DECK_MIN_HEIGHT`, `DECK_HEIGHT_SHARE`
  and `DECK_OVERHANG` exist in both `RadioScreen` and `RadioCabinetPage`; move
  them to `RadioMaterials` beside `VALVE_DECK_ASPECT`.
- **The player could show stale metadata for the selected episode.**
  `RadioState.selected` preferred `nowPlayingEpisode` over the row from the
  programme list, so a title or podcast changed by a refresh showed the old
  value on the player while the slip showed the new one. #627 prefers the row
  and keeps the player snapshot as the fallback; the measured duration still
  wins.
- **The printed needle scale always uses `MM:SS`; above 99 minutes the
  rightmost quarter label ("900:00") is wider than the others and nudges the
  row.** Either accept it deliberately or use a compact hours form there too.
- **`Nixie` draws a ghost `8` behind every digit;** check on Retina whether it
  greys the tubes enough to cost legibility.
- **`SkipKey`'s painted cap fills its whole 52 dp target while `SlipPlayKey`
  keeps a 36 dp cap inside 48 dp.** A little air between ring and target would
  match.
- **`MaterialPlate(METAL)` draws two screws at the top corners only;** the
  rendered faceplates carry four. The home's plinth feet are plain `Chassis`
  rectangles with no shadow or recess, so on the dark stage they read as
  floating bars.
- **The dial needles are drawn on top of the glass sheen:** `dialWindow` is a
  `drawBehind` on the parent while `TuningDial`, `NeedleWindow` and
  `StepCounter` draw their needles afterwards, so the needle never sits under
  the reflection.
- **`NdTopAppBar.FaceplateRow` falls back to the "between" position even when
  `betweenRoom < signWidth`**, so at very narrow widths the sign can run under a
  key.
- **The visible Nixie counter caps at `9H:59`** (`RadioState.kt:70-78`).
  `radioElapsed` coerces minutes to `9*60+59`, so a ten hour episode reads
  `9H:59` forever while the spoken description is unbounded. Accept the
  four-tube cap deliberately or print a compact two-digit-hours form.
- **`RadioText` sets a tight line height** (`RadioProgramme.kt:650`):
  `lineHeight = size * 1.2` sp can clip descenders at small sizes.
- **`SignLettering` mishandles a trailing space** (`RadioParts.kt:160-175`):
  it compares `getLineEnd(0, visibleEnd = true)`, which excludes trailing
  whitespace, with the full length, so a name ending in a space always letters
  at its minimum size. [#630](https://github.com/L-K-M/Neutrodyne/pull/630)
  compares against the trimmed name.
- **The programme's empty and error states are footnotes.** When a playlist is
  empty or fails to load, the tray shows only a small strip at its foot; give
  that state the paper's full height.

## Performance

### Measure the work done by playback ticks

`RadioViewModel` republishes the whole `RadioState` on position ticks, and
`RadioRoute` forwards it through the cabinet. `RadioPlayer` looks up selection
and the visible count with list scans. Profile a seeded 10,000-row station while
playing, inertially scrolling and resizing on a Retina screen. Attribute work
to composition, layout, asset rasterization and playback separately. If tick
invalidation is material, read positions in the dial and Nixie leaves, keep the
list inputs stable, and cache ID lookups by loaded snapshot. Compare p95 frame
time, input latency, composition counts and idle CPU against the baseline, with
and without artwork cached.

One cause is visible in code: `RadioRoute` rebuilds `RadioActions` on every tick
with a `loadEpisode` lambda that captures `state`, so the actions are never equal
and every visible `RadioSlip` recomposes each second while playing. A
composition-count test can show it before and after moving elapsed time into a
`State` read only by the needle and Nixie, remembering the actions with
`rememberUpdatedState` for captured values, and adding `contentType` to the slip
list. Desktop CPU while playing measured 2 to 7% on a Mac Studio, so the cost
matters most on low-end Android. The one-second desktop ticker also calls
`DesktopPlaybackController.publish()`, and `PlayerViewModel.position` switches
its tick subscription on each new `nowPlaying` value.

### Keep download progress and static drawing off the hot path

Download progress recomposes the whole Downloads screen; pass the live progress
`State` down and read it per row, as `PodcastScreen.kt:483` does. The Downloads
overview is recomputed by a 30 s polling pulse, and the engine's queue scan loads
every download row on each loop (`DownloadControllerImpl.kt:356`,
`DownloadEngine.kt:312`). The faceplate and paper card decode both the wide and
the tall artwork although only one is drawn (`RadioPanels.kt:44,61`). `recess`,
`radioGlass`, `cabinetEdges` and the needle allocate `Path` and `Brush` per
draw, and static tick scales redraw every frame (`TuningDial.kt:93`,
`RadioPlayer.kt:393`); cache them with `drawWithCache`. A control-sounds branch
rewrote every sound WAV to `cacheDir` on each cold Android start; if sounds
land, write them once per version.

### Profile rendering and paging while scrolling

Compare individual and split `RadioCabinetPage` trays for duplicate lid drawing,
texture scaling and repeated clips at Retina resolution. Trace
`RadioRoute.onRows` mapping the paging snapshot and the list's per-item
`radioPageIndex` lookup during rapid scrolling and station changes. The feed
pages by 40 with a maximum loaded size of 400; time `loadedIds.indexOf` against
that window before adding a lookup table. `RadioRoute` rebuilds the entire
`RadioEpisode` presentation list on every paging emission and copies it into
`RadioState`, so one append recomposes the whole cabinet even though
`LazyColumn` keys isolate unchanged rows. Map only the diff or keep the mapping
behind the snapshot identity. `Speaker` redraws its fixed grille pattern on
every invalidation; a cached draw is safe because only the size is an input.

Two more cheap wins to measure first:

- `feeds.countUnplayed` issues one count query per page while a long playlist
  streams in (see "Give long playlists a reliable count").
- Every nine-patch and three-patch slice draws with `FilterQuality.High`,
  including the small slices of every key on every slip. A fling composes
  hundreds of slips; try `FilterQuality.Low` for slices under a few pixels and
  measure the difference at Retina.

### Make desktop screenshot failures reproducible

On this Mac the focused Roborazzi verification of `RadioScreenshotTest.presets`
failed at `radio_phone.png` on clean main and on the accessibility change, with
identical SHA-256 digests for the two fresh renders. Compare the main render on
the reference CI OS against the committed PNG. Record its JDK, rasterizer,
density, fonts and renderer configuration, then decide whether the host should
use its own baseline or run visual acceptance on the reference OS. Verify all
six presets and prove a deliberate layout change still fails.

## Listening workflows

### Put useful episode actions beside the programme

`RadioSlip` currently offers selection and Play. Give a slip a compact context
menu for episode notes, Play next, download where supported, and Mark played at
the end. Reuse the episode navigation and action services behind `EpisodeRow`.
Bind every action to the slip's stable episode ID. Verify primary click, Play,
secondary click, Shift+F10 and a touch interaction. Check that Open notes
preserves the selected station, scroll anchor and active transport on return.
Chapter next and previous are unreachable from the radio, the only full player:
the handlers in `PlayerRoute.kt:134` are dead code. Offer them in the same menu
or on the keyboard when the episode has chapters.

### Preserve the station and scroll position when browsing

`RadioProgramme` resets scroll to the first item when the playlist ID changes.
Consider a bounded per-station anchor keyed by stable episode ID and offset.
Select the nearest surviving episode after refresh, discard or data removal.
Keep the playing episode independent of the browsed station. Test A -> B -> A,
paging behind the anchor, removals, restart and Back. Defer a migration until a
session-only prototype feels right.

### Make first tuning a short, recoverable journey

A new listener often needs to add a subscription and create their first group
before the radio has anything to tune. Keep group creation in Library. Provide
one route to the existing group workflow and a separate route to Discover when
there are no subscriptions. On macOS, exercise a clean launch, adding a feed by
paste, creating a playlist and returning to play the first episode. Repeat with
an OPML import, a present but empty playlist, and a feed whose refresh failed.
Keep separate empty and error explanations so a failed refresh never looks like
an empty schedule. Today the journey from the empty radio still takes seven or
more steps (Library, Groups, New group, name, members, save, back).

### Show how old an episode is and what is new

Slips print no date. For a news inbox, "today" against "three weeks ago"
decides what to keep: print the age like a rubber stamp beside the duration
("18 NOV"). The radio has no "new since last visit" marker; the legacy feed
browser had baselines (`FeedsViewModel.kt:451`, `markVisited`, `lastViewedAt`).
A red NEW stamp on episodes that arrived since the playlist was last tuned would
reuse them. Check both against German dates, 1.3x text and the compact row.

### Make editing and finding work naturally with a Mac keyboard

`DesktopMenuBar` disables Cut, Copy, Paste, Undo and Select All and drops app
menu accelerators while a Compose field has focus. Build a focused-field action
bridge only if the composition can reliably supply enabled states and route to
the actual field. Start with Discover search and verify Cmd+F, Cmd+W, Cmd+,
and ordinary text shortcuts against focus in a field, in a modal and in the
programme. Preserve the current key ownership for transport, and use the
platform clipboard rather than a new editing abstraction.

In the programme, each Up or Down press now changes the selection and reveals
the slip at the nearest edge (#609); verify a press inside the view, at its
bottom edge and at the end of the list. Escape was swallowed in text fields:
`isRootShortcut` marked it as a root shortcut and the text-field branch consumed
it with no action (`NeutrodyneRoot.kt:394,659-673`), so in Discover search
Escape neither cleared the field nor went back.
[#615](https://github.com/L-K-M/Neutrodyne/pull/615) removes it from
`isRootShortcut`, so the root's existing back path runs; verify it in Discover
search, Sync setup and a modal. The window's Cmd+Q fallback
routes through `onCloseRequest`, which hides to the tray, instead of quitting
(`NeutrodyneWindow.kt:555`). Cmd+, may be registered twice on macOS.

### Centralize text-field focus reporting

`.ndTextFieldFocus()` is hand-applied at every call site. Moving it inside
`PaperTextField` would stop the next dialog or search field from silently
reintroducing the shortcut conflict, but the wrapper lives in
`:core:designsystem`, which cannot see the tracker in `:core:ui` without a
circular dependency; raw `OutlinedTextField`s elsewhere would still need
explicit treatment. Do this as its own pass over every text field rather than
halfway, or move the tracker down to the design system first.

### Explain short searches and episode counts

For a one- or two-character search, show a localised hint that three characters
start an automatic search. Keep Enter available for an explicit short search.
The Discover list can show Apple's episode count while the feed preview shows a
different number. Compare both for the same podcast. Label the source of each
count, or use the feed count when it is available.

### Recover when a directory search cannot read the local library

Discover and Directory use `SearchRepositoryImpl.annotate` to mark incoming
hits as subscribed. A desktop probe closed the test database before a recorded
search. The flow emitted Pending, then threw `IllegalStateException` on the
subscription lookup before it could emit a final result. Exercise both screens
with a failing local lookup. Give the listener a way to retry when the library
is available, preserve the distinction between local failure and provider
failure, and verify that a subscribed podcast still opens its library page after
recovery.

### Diagnose the Linux YouTube preview handoff

In the Linux unit suite, `ExternalYouTubeModeTest.directoryChannelPreviewHandsOffToAdd`
timed out waiting for the channel title after opening a YouTube handle through
`PodcastPreviewKey`, on main and twice on the Discover proposal. At the timeout,
capture every Compose root, the active navigation entries and the Add sheet
state. Compare the preview route with the passing direct `AddPodcastKey`
journey in the same Linux suite. Determine whether the handoff lost the route
or the title assertion missed the active sheet. Verify that one Subscribe action
creates one channel in the selected group.

## Sync server and client

### Batch the feed-cache reads and eviction (new)

`FeedCacheService.batch` loops the (up to 1,000) requested URLs and calls
`feedCacheHead(url)` then `feedCache(listOf(url))` for each, inside one read
transaction: about 2,000 point queries per full batch. A single `IN` query for
the heads plus one for the selected bodies would be cheaper. `MAX_FEED_BATCH`
is 1,000, above the old SQLite default of 999 variables, so chunk at 500 like
the download planner's `BIND_CHUNK`. Separately, `JdbcWrite.putFeedCache`
calls `evictFeedCacheOverflow()`, which runs `SUM(body_bytes)` for the account
after every insert; maintain a running total or check the cap only when the
just-written entry crosses it.

### Pin the feed fetch to its checked DNS answers (new, security)

`HttpServerFeedFetcher` rejects literal local URLs (`allowed`) and local
resolutions (`FeedDns`), but `FeedDns.lookup` checks the addresses and returns
them, after which OkHttp connects; a publisher that answers the lookup with a
public address and the connection with a local one (DNS rebinding) is not
prevented. Pin the connection to the checked addresses, or document the
residual risk. This is hardening, not a demonstrated bypass.

### Reconsider the credential clock check in the server poll (new)

`FeedCacheService.basicAuthorization` sends the stored Basic header only when
`auth.c == credentialOrigin.c`. The client stamps the pair together, but the
server merges fields independently: a device with password sharing off can push
a newer `credentialOrigin` without `auth` (`SourceReader` writes the origin
whenever a credential exists, and `auth` only while sharing is on). After that
merge the stored auth is older than the origin, the check fails and the server
polls the private feed without its stored credential until something rewrites
`auth`. The final origin comparison already protects against sending the
credential to the wrong host; decide whether to relax the clock equality or
re-stamp the pair on the server when the origin changes.

### Fix the cause of unaddressable episode keys (new)

`SyncEngine.retireUnaddressable` deletes outbox rows whose `rid` cannot be
parsed by `RecordId.fromRid`. That happens when a feed GUID or enclosure URL
makes `EpisodeKeys` exceed `MAX_IDENTITY_KEY` (2,048 bytes) or contain a
control character. The episode keeps working locally, but its played state
and position silently stop syncing, and a log line is the only trace. A
content-hash fallback for over-long keys, or a bounded key at ingest, would fix
the cause rather than retiring the row.

### Wire up "Check again" (new, missing feature)

`EpisodeRow` offers `EpisodeAction.CheckAvailability` for greyed YouTube rows
when `RowCaps.recheck` is set, and `YouTubeChannelRepository.recheckAvailability`
is implemented (`YouTubeChannelRepositoryImpl.kt:92`). Every view model's
`onRowAction` ends in `else -> Unit` and none handles it
(`PodcastViewModel.kt`, `FeedsViewModel.kt`, `EpisodeViewModel.kt`), so the
action is a no-op. `PodcastViewModel` already injects
`YouTubeChannelRepository`; add it to the other two, call
`recheckAvailability`, and post a localized "Couldn't check — try again later"
message when it returns null (design 04). Add the string EN and DE, bind the
row's availability update through the existing episode flow, and test success,
a new reason and a transient failure.

## Library, downloads, and sync defects

### Make listening-page actions do what they show

- "Check again" is offered on unavailable YouTube rows, but every view model
  drops `EpisodeAction.CheckAvailability` (`PodcastViewModel.kt:320`,
  `FeedsViewModel.kt:564`, `EpisodeViewModel.kt:312`).
- A VERIFYING download key looked tappable but did nothing; #623 cancels it
  like RESOLVING.
- The podcast page's filtered-empty action said "Show played episodes" but
  cleared every filter; #624 labels it accurately. The page still has no
  episode search, and its filter bank scrolls away.
- Downloads rows document swipe to delete; there is none.
- The group editor's "Show as a tab in Feeds" switch reads `showAsTab`, which
  only the legacy feed browser uses now that the home has no tabs. Retire it or
  give it a meaning on the radio.

### Fix download storage limits and notices

`DownloadDao.downloadedEpisodeIds` was not chunked at SQLite's bind limit, so a
large library could throw (`DownloadDao.kt:29`); #622 chunks it at the existing
500-row download bind size. The storage cap applies only to automatic
downloads; `DownloadQuota`'s own contract says admission is AUTO-only, so the
manual case is an owner decision rather than a clear defect. Android's "SD card
is not available" notice has no action, so the setting that fixes it is
unreachable (`DownloadNotices.kt`). Test with 1,500 downloaded episodes, a cap
reached by a manual download, and an ejected card.

### Keep sync and settings drafts and copy intact

Sync setup, Settings > Sync, the "Link another device" sheet and Settings >
Discover keep drafts in `remember`, so rotation loses a typed invite code,
username, server address or country; use `rememberSaveable`. The first-link
choice cards print their counts without plurals ("1 groups"). Sync screens have
no Back key, unlike every other settings page.

### Fill the Android platform surfaces

Android has no app shortcuts and no Quick Settings tile;
`settings.gradle.kts:121` reserves `:feature:widgets` for a v1.x widget. The
media notification is the unbranded Media3 default, and new-episode
notifications carry no artwork. Start with a Play or Pause tile and a shortcut
to the last playlist.

## More ideas from the polish pass

### Offer an index for long programmes

The programme is a lazy pager with no way to jump. A radio-appropriate band of
A-Z or date keys along the tray edge, or a tuning-dial style jump strip that
scrolls the `LazyColumn` to the nearest matching sort date, would make
500-episode groups usable. Prototype first: it needs stable item keys and an
ordinal lookup that survives paging windows and station switches.

### Print the queue position as a counter window

`RadioPlayer` prints `NN / NN` as plain mono text beside the status. A small
brass-framed counter window like `StepCounter`'s glass, without the step keys,
would match the dial/Nixie assembly. Purely presentational; keep the complete
count semantics from "Give long playlists a reliable count".

### Show the running sleep timer on its Menu key

The Menu dialog's Sleep key never reflects state. When a timer runs, a small
counter on or beside the key (remaining minutes) would save opening the sheet.
Keep it quiet when off and honour the existing timer controls.

## Prototypes

### Make the Mac useful without the main cabinet open

Prototype a small always-on-top receiver with current title, play, skip, elapsed
time and an Open Radio action. Drive it from the existing desktop playback source
and the mini-player action semantics. It should never start a second audio
session. Design close and regular-window restoration against the existing tray
and quit behavior. Exercise VoiceOver, keyboard-only use, full-screen Spaces,
and unplugging a display before considering cross-platform support.

### Offer practical station presets

A few optional enamel preset keys could bring favourite groups within one click
or keypress. Use actual groups and retain the existing chooser for the full
list. Show active state and unplayed count only when useful. Reordering should
be deliberate, with no accidental membership changes. Prototype three presets
on wide screens and evaluate whether they save time. Leave keybindings unset
until they can coexist with desktop menu accelerators.

### Use the valves for small, useful moments

The lamp or valve can acknowledge a station change or a finished episode with a
brief warm change. Use the same accessible status text and honor reduced motion.
Keep normal playback static and never introduce button sounds into the audio.
The warm-up is merged (#592): the filaments brighten over 1.8 s when the radio is
switched on and cool over 0.9 s when it is switched off, static while playing
and instant with reduced motion. The dial paper's backlight (`dialPaper`
gradient) could warm with it for one gradient parameter.

### Give the signal meter a buffering job

The status lamp now says BUFFERING, but a needle is a better fit for an
uncertain stream: rest it while ready, let it hunt while buffering, and park it
on an issue. Reuse `NeedleGauge` beside the status lamp, drive it from the same
phase, and honor reduced motion. Measure idle CPU; if the needle animates, it
must stop the moment the stream settles. The alternative is a magic-eye tuning
indicator (a 6E5-style green eye, open while playing, wavering while buffering,
closed on error). Pick one of the two, not both.

### Recall the last tuned station

A small ivory key that returns to the previous playlist is the radio equivalent
of a station preset and costs nothing when unused. Keep it explicit (a long
press or a dedicated key) so it cannot fire by accident, and remember only
within the session until a persistence decision is made.

### Design an evening listening experience

Make an optional player display that dims secondary brass and highlights the
remaining sleep timer close to the Play switch. Use the existing PO-55 cabinet
and materials. Preserve contrast and focus indicators. Keep the timer controls
visible and check that the idle display causes no extra frame activity. Review
the product decision before implementing a new user preference. A mechanical
sleep timer, a wind-up knob on the faceplate that visibly unwinds as the timer
runs, could replace the sleep dialog there.

### Prototype chapter marks on the tuning scale

Try small index marks along the existing ruler for an episode with chapters.
Show the current chapter name on deliberate pointer or keyboard inspection, and
make it reachable by assistive technology. Use existing chapter times and seek
handling. Compare no chapters, dense short chapters, a very long title and
unknown duration. Keep the plain unchaptered dial, preserve arbitrary seeking
and avoid extra permanent controls. Decide from a full-size and a compact render
whether this earns space. Prototype slightly longer brass ticks, cluster marks
too close for a pointer to resolve, and test late-arriving chapter data with four
and eighty chapters.

### Fill the speaker grille on wide windows

At 1200 x 800 a grille the size of the slip tray holds nothing. Tuck a paper
"station log" card into its frame: the next three Up next slips, or the tuned
episode's show-notes summary. Compare against the plain grille at 700 and
1200 dp, and keep the card out of the layout when the grille is too small.

### Try small mechanical responses

Each must respect reduced motion and add no per-frame work while idle.

- The needle travels to a newly selected episode's position with a short
  spring instead of jumping.
- Haptics beyond the switches and the discard threshold (#586): a `SegmentTick`
  on the ivory keys, and light ticks when scrubbing across chapter marks or
  whole minutes.
- A short burst of tuning static while the station changes, once control sounds
  exist, with the needle sweeping the scale.
- A sign-off when the last slip of a playlist is discarded: the Nixie reads
  `--:--`, the valves cool, and the paper says the station has closed for the
  night, with keys to another station or Discover.
- A paper spike in the tray corner that stacks discarded slips; tapping it
  recalls the last one, as Undo does.

## Owner decisions: documentation the code contradicts

Each needs a decision: build it, or correct the document.

- `privacy.crash_reports` and its Privacy-page switch are documented (09 and 08)
  but not registered, read or rendered.
- The group media filter and "hide older than N days" are documented, but no UI
  sets them (`FeedRepository.setHideOlderThanDays` has no production caller).
- The Android in-app language picker exists only in tests
  (`BuildInfo.shippedLocales`); the desktop Language row and the Windows Access
  Bridge row are missing.
- Podcast and group settings document a Playback section (speed, skip silence)
  that is not rendered.
- Settings > Feeds documents "Show Ungrouped tab" and "Manage groups". The
  Library overflow lacks "Backup and restore". About and Privacy lack a
  "Privacy policy" row. 05's "Import from URL" is missing.
- `CHANGELOG.md` still reads `[Unreleased]` with only M0a.1 at version 1.0.0.
- Transcripts are parsed and stored but never shown; the documents defer them.
- Not documented anywhere, an idea: OPML auto-sync from a remote list.
- The radio's `fontScale` rule contradicts 08's platform table. The Android
  table says layouts switch on `fontScale >= 1.5` and never compute sizes from
  it; the radio home scales `minimumHeight`, key widths, cap sizes and the Nixie
  glass from `fontScale`. Record a dated amendment or align the table; the pure
  `radioLayout` now makes the absolute-threshold/scaled-minimum split explicit
  and tested.
- The desktop window title rule says "while playing" but macOS convention keeps
  the current title while an episode is loaded; decide before changing
  `NeutrodyneWindow.kt:304`.

## Checked and cleared

- **Elapsed time after a seek.** A suspected freeze does not exist: `seek()`
  populates `seeks` only in the not-loaded branch, so a loaded needle seek or
  skip keeps receiving ticks.
- **Update-dot announcement.** The radio Menu key's badge description merges
  with its label (`mergeDescendants=true`), so it does not double-announce.
- **Focused-field disposal.** `TextFieldFocusTest` proves a focused field
  leaving composition releases the shortcut stand-down.
- **Stock touch-target expansion.** `Modifier.minimumInteractiveComponentSize()`
  did not widen the pointer region in a desktop probe, so do not assume it
  satisfies the 48 dp rule here.
- **The storage cap and manual downloads.** `DownloadQuota` is AUTO-only by
  design; a manual download ignoring the cap is intended, not a defect.
- **The radio slip role.** `Modifier.selectable` does not default to
  `Role.Tab`; the earlier claim that the tree reads a list of tabs is false.
- **Android IME.** `RadioCabinetPage` applies `aboveKeyboard(...,
  WindowInsets.ime)` to every cabinet page, so the earlier "no `imePadding`
  anywhere" claim was stale.
- **`rememberClickSound` staleness.** The helper's `remember(sounds, sound,
  onClick)` keys include `onClick`, so a new callback is tracked; the reviewer's
  stale-lambda concern does not apply.
- **Claims refuted in earlier passes.** `onRows` re-emits identical state
  (StateFlow conflates it). The Nixie caption uppercases a Turkish i wrongly. A
  deep link is lost on process death (the stack restores it). The RTL swipe is
  broken (the offset is RTL-aware). Up next headings are spoken uppercase. A
  podcast description collapses on re-emission. The faceplate header ignores
  RTL (`placeRelative` mirrors it). Go menu items push top-level keys onto the
  current stack (`navigateFromPlayer` selects tabs for `TopLevelKey`). The macOS
  tray never appears. Completed downloads with a null size are marked missing. A
  zero-byte temp file skips adoption. The host-permit map grows without bound. A
  manual refresh that ignores the interval is a bug (the bypass is intended). An
  arrow-key focus request on a slip removed mid-scroll crashes (Compose UI
  1.12.1's `requestFocus()` on a detached requester prints a warning and returns
  false).

## Native verification on proposed fixes

Each fix below still needs the named device or VoiceOver/TalkBack check. Do not
claim it from a host test alone.

- With VoiceOver on macOS and TalkBack on Android, change a populated Discover
  search while a slow provider responds; check the label names the search that
  produced the visible rows and that rapid typing avoids repeated announcements.
  [Discover retained results](https://github.com/L-K-M/Neutrodyne/pull/594)
- Play a stream with no announced duration; check one duration legend, the
  advancing elapsed counter and a screen reader skipping the inactive dial.
  [Unknown-duration dial](https://github.com/L-K-M/Neutrodyne/pull/595)
- On macOS, leave a podcast with a saved position in the middle; press Previous
  within three seconds of the next episode starting and after three seconds, and
  check pause preservation. [Desktop Previous](https://github.com/L-K-M/Neutrodyne/pull/596)
- Scrub a streaming and a downloaded episode on macOS; check pointer
  cancellation, resumption and the number of engine seeks and writes.
  [Seek on release](https://github.com/L-K-M/Neutrodyne/pull/565),
  [receiver drag tests](https://github.com/L-K-M/Neutrodyne/pull/571)
- Invoke Mark played from VoiceOver and TalkBack, then check Undo and its
  return focus; verify the desktop Delete path.
  [Programme slip action](https://github.com/L-K-M/Neutrodyne/pull/566),
  [accessible discard](https://github.com/L-K-M/Neutrodyne/pull/563)
- Verify the printed quarter times against needle position and seeking on an
  RTL device. [RTL time scale](https://github.com/L-K-M/Neutrodyne/pull/567)
- Open the paper playlist insert with a non-first group active; check VoiceOver
  announces it as selected and keyboard focus starts on its checked row.
  [Tuned playlist indicator](https://github.com/L-K-M/Neutrodyne/pull/568)
- Launch the desktop app ten times from a fresh data directory on a slow disk
  and confirm it never exits before the window.
  [Deferred refresh controller](https://github.com/L-K-M/Neutrodyne/pull/581)
- In Discover, search "Star Wars: The Clone Wars", "AC/DC" and "Dr. Death", then
  paste an address with a no-break space or curly quotes and confirm it never
  reaches the directory. [Programme names search](https://github.com/L-K-M/Neutrodyne/pull/582)
- On a fresh install, follow the empty radio's Discover, Import and Library
  keys; check the sign gives way once a group exists and that all three keys
  show on a small phone. [Empty radio tuning](https://github.com/L-K-M/Neutrodyne/pull/583)
- With VoiceOver and TalkBack, move by headings through Library, Settings and
  the Menu plate, open a Diagnostics section and retune the Library dial with
  reduced motion on. [Headings and toggles](https://github.com/L-K-M/Neutrodyne/pull/584)
- Compare the mini radio's needle window and a downloading row at 1x and 2x.
  [Thin dial windows](https://github.com/L-K-M/Neutrodyne/pull/585)
- On a phone with touch feedback on and off, throw the radio switch and a
  settings switch and swipe a slip past the discard threshold; confirm nothing
  buzzes. [Haptics](https://github.com/L-K-M/Neutrodyne/pull/586)
- Fill a device's storage until the library cannot open and use Manage storage
  on a Pixel and an OEM build. [Manage storage guard](https://github.com/L-K-M/Neutrodyne/pull/587)
- Play a password-protected feed whose enclosure answers 403 on the desktop and
  confirm the Enter password action appears.
  [Forbidden enclosure](https://github.com/L-K-M/Neutrodyne/pull/588)
- Drag the needle on a slip that is not loaded, start that episode from Up next,
  and confirm the Nixie follows playback and the radio's ON resumes rather than
  restarts. [Parked seek](https://github.com/L-K-M/Neutrodyne/pull/589)
- Switch the radio on and off on a phone and a Retina Mac and watch the valves
  warm and cool; repeat with reduced motion and check idle CPU while playing.
  [Valves warm up](https://github.com/L-K-M/Neutrodyne/pull/592)
- Link a second device against a server and read the Merge card, the address
  field's label and an update card's size in English and German.
  [Sync and update copy](https://github.com/L-K-M/Neutrodyne/pull/590)
- With TalkBack and VoiceOver in English and German, focus the radio's counter
  at 1:01:01 and after 12 hours and listen for plural forms without zero parts.
  [Spoken elapsed time](https://github.com/L-K-M/Neutrodyne/pull/591)
- On a phone, play an episode to its last half minute, tap another episode, and
  confirm the first one shows as played on Android as on the desktop.
  [New start marks played](https://github.com/L-K-M/Neutrodyne/pull/593)
- Arrow-key tuning: press Down from a slip at the bottom edge of the viewport
  and confirm the list reveals it without snapping it to the top; repeat with a
  slip already fully visible. [PR #609](https://github.com/L-K-M/Neutrodyne/pull/609)
- With Control sounds on, press the radio Menu key and confirm it sounds like
  the other ivory keys; check the label at 200% text.
  [PR #610](https://github.com/L-K-M/Neutrodyne/pull/610)
- With a group whose first page is empty and later pages exist, confirm the
  programme keeps the spinner instead of flashing All clear; fail the load and
  confirm the retry row remains. [PR #611](https://github.com/L-K-M/Neutrodyne/pull/611)
- Tap a download in VERIFYING and confirm it cancels like RESOLVING; check the
  row at 1x and 2x. [PR #623](https://github.com/L-K-M/Neutrodyne/pull/623)
- With a downloaded or in-progress filter active and no episodes, confirm the
  podcast page offers "Clear filters" and clears every filter; with only the
  unplayed filter, confirm it still reads "Show played episodes".
  [PR #624](https://github.com/L-K-M/Neutrodyne/pull/624)
- With a server that keeps answering `authorization_pending` past the code
  lifetime, confirm the link flow fails with InvalidCode instead of polling
  forever. [PR #625](https://github.com/L-K-M/Neutrodyne/pull/625)
