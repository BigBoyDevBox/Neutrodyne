# Neutrodyne design backlog

This backlog records open design and engineering work for the radio home and
macOS experience. The review began on `f9bd91021` and used code inspection,
fresh desktop fixture renders, and desktop journeys for discovery, group
management, and the mini player. Playback latency and frame pacing require
measurements on the Mac. [Product plan](docs/PLAN.md)
[Radio design](docs/design/08-ui-ux.md#radio-home)

Keep the material and control vocabulary from PO-55. The walnut cabinet,
enamel signs, paper, and tactile controls should help users distinguish
navigation, programme, and transport. Native window and menu behavior matters
alongside the shared cabinet.

## Playback and programme correctness

### Keep playback and paging current during Mark played and Undo

`RadioViewModel.discard` captures the selected episode, position, and playing
state before awaiting the repository write. A listener can tune another slip,
pause, or receive an engine position update while the write is suspended. On
success, the projection can restore the captured selection, position, or
playback flag. During Undo, `onRows` defers a paging snapshot and the Undo path
drops that deferred snapshot. Write regression tests with a controllable
repository suspension. Change selection or pause before completing Mark
played, then deliver a newer page during Undo with both successful and failed
writes. Commit the row mutation against the current programme and reconcile
the latest same-playlist paging snapshot. Preserve the existing policy when
the actual selected item is removed. Give rapid repeated discards explicit
feedback while the first write is pending.

### Make buffering and errors intelligible on the radio

`RadioPlayer` distinguishes cleared, ready, playing, and paused. The transport
can also be waiting for a stream, retrying, or blocked on an error. Extend the
presentation using the existing playback snapshot and event bridge. Add text
next to the lamp and a route to the existing recovery action where applicable.
Keep Cancel available while buffering. Test a delayed stream, metered consent,
engine refusal, replay, and a switch from a failing item to a healthy one. Avoid
inventing a new source of playback truth in the UI.

### Keep all transport functions reachable at high text scales

`RadioPlayer` hides the skip keys below its combined width estimate. The
narrow layout relies on the needle, which requires a known duration and
cannot apply the listener's skip intervals. Provide a second layout for the
keys with 48 dp targets and the printed intervals. Use fewer scale labels
where they would collide. Show a quiet inactive dial when the duration is
unknown. Test both skip values, unknown durations, three-hour media,
and 2.0 text at 320 dp. Check German and keyboard access.

### Give long playlists a reliable count

`RadioViewModel.onRows` asks the repository for an unplayed count on every
incomplete paging snapshot. The result is stored as `RadioState.episodeCount`,
but the current dial only prints its position when the loaded episode list is
complete. The design calls for repository counts while paging. Show the total
as a localized unplayed count for partial pages, and show a position only when
the episode's ordinal is known. Check whether the count can come from the same
paging generation or can be shared across append events. A failed count should
leave the total unknown. The existing failure path reports a write error for
this read. Test successive append, invalidation, count failure, discard and
Undo, and a playing episode outside the selected group.

## Cabinet, layout, and accessibility

### Make the cabinet fit the useful viewport

`RadioScreen.RadioCabinet` sizes compact and short variants from window height
while sizing the deck from local constraints. In the narrow desktop fixture
the transport and deck leave roughly one full slip. The small layout permits
nested vertical scrolling once the cabinet exceeds its viewport. Keep the
valves and selection visible, but measure the usable space inside the insets
and define a minimum for the slip list. Reduce the speaker opening and panel
padding first, then use a more compact arrangement of the dial and transport.
Choose an intentional scroll mode if the total still cannot fit. Test 600 ×
480, 800 × 500, 1200 × 800, and the phone and tabletop presets at 1.0, 1.3, and
2.0 font scales. Include a two-line title, retry alongside Undo, and enough
episodes to require scrolling. Count whole usable rows and exercise focus at
the bottom of the cabinet.

### Give the playlist sign room and a legible fallback

`RadioProgramme` reserves an invisible key's worth of width to center the
sign opposite Menu. The 320 × 568 and 1.3× text desktop presets ellipsize even
“Morning rotation.” Keep the enamel treatment. Align the sign to the paper
tray's reading column or move Menu to a separate corner when the measured sign
cannot fit. Show the complete name in the chooser and expose it through desktop
hover or focus when clipped. Compare English and German, two long names with
the same prefix, and 320 dp plus 600 dp widths.

### Add a usable Mac programme scrollbar

The home list in `RadioProgramme` lacks the scrollbar drawn by `FeedsScreen`
and other scrolling destinations. In a short window, a full row can fill the
paper tray with little indication of more material below. The current shared
`NdScrollbar` overlay draws a thumb without drag support. Its lazy-list branch
also hides the thumb when all rows are even partly visible, and a viewport
shorter than its minimum thumb can yield a negative travel range. Fix those
cases before wiring the overlay into the radio, or provide a draggable
desktop scrollbar in the existing paper-tray gutter. Check a long list, one
clipped row, a tray shorter than the thumb minimum, and a fully fitting list.
Test wheel and trackpad movement, and keep the play target unobstructed.
The loaded window is the scrollbar's extent until pagination supplies more
rows.

### Establish a material and type hierarchy

The desktop radio renders draw several borders, screws, wood grain, glass
highlights, and small mono labels into the same few hundred pixels. Use a quiet
paper slip under readable ink, crisp sans-serif metadata, and retain mono for
the dial, counters, and short engraved terms. Keep a single direct light
direction across the faceplates, flatten seams where the two trays meet, and
check the reduced-contrast engraved labels on warm paper and charcoal. Start
with the radio home and one shared page. Compare normal and selected slips,
hover, keyboard focus, missing cover art, and Retina plus 1× rendering before
changing the shared components.

## Performance measurements

### Measure the work done by playback ticks

`RadioViewModel` republishes the whole `RadioState` on position ticks, and
`RadioRoute` forwards it through the cabinet. Separately,
`RadioPlayer` looks up selection and the visible count with list scans.
Profile a seeded 10,000-row station while playing, inertially scrolling,
and resizing on a Retina screen. Attribute work to composition, layout, asset
rasterization, and playback separately. If tick invalidation is material,
read positions in the dial and Nixie leaves, keep the list inputs stable,
and cache ID lookups by loaded snapshot. Compare p95 frame time, input latency,
composition counts, and idle CPU against the baseline. Measure with and without
artwork cached.

### Profile rendering and paging while scrolling

Compare individual and split `RadioCabinetPage` trays for duplicate lid
drawing, texture scaling, and repeated clips at Retina resolution. Trace
`RadioRoute.onRows` mapping the paging snapshot and the list's per-item
`radioPageIndex` lookup during rapid scrolling and station changes. Measure
frame time, GC, RSS, and appended pages with a cold and warm artwork cache.
Cache static drawing or index snapshots only where profiling shows work.
Keep episode keys and the identity guard between the rendered rows and the
paging snapshot. Verify output and pagination after rapid station switches.

## Listening workflows

### Put useful episode actions beside the programme

`RadioSlip` currently offers selection and Play. The menu keys go to app
destinations. Give a slip a compact context menu for episode notes, Play next,
download where supported, and Mark played at the end. Reuse the episode
navigation and action services behind `EpisodeRow`. Bind every action to the
slip's stable episode ID. Use the shared secondary-click and keyboard gesture
patterns. Verify primary click, Play, secondary click, Shift+F10, and a
touch interaction. Check that Open notes preserves the selected station,
scroll anchor, and active transport on return. Validate the new route and action
catalogue before exposing the menu.

### Preserve the station and scroll position when browsing

Treat switching among stations and visiting a detail page as different from
clearing the receiver. `RadioProgramme` resets scroll to the first item when
the playlist ID changes. Consider a bounded per-station anchor keyed by stable
episode ID and offset. Select the nearest surviving episode after refresh,
discard, or data removal. Keep the playing episode independent of the
browsed station. Test A → B → A, paging behind the anchor, removals, restart,
and Back. Defer a migration until a session-only prototype feels right.

### Make first tuning a short, recoverable journey

A new listener often needs to add a subscription and create their first
group before the radio has anything to tune. Keep group creation in Library.
Provide one route to the existing group workflow and a separate route to
Discover when there are no subscriptions. On macOS, exercise a clean launch,
adding a feed by paste, creating a playlist, and returning to play the first
episode. Repeat with an OPML import, a present but empty playlist, and a
feed whose refresh failed. Keep separate empty and error explanations so a
failed refresh never looks like an empty schedule.

### Make editing and finding work naturally with a Mac keyboard

`DesktopMenuBar` disables its Cut, Copy, Paste, Undo, and Select All items and
drops app menu accelerators while a Compose field has focus. The context menu
serves text editing. Build a focused-field action bridge only if the
composition can reliably supply enabled states and route to the actual field.
Start with Discover search and verify Cmd+F, Cmd+W, Cmd+, and ordinary text
shortcuts against focus in a field, in a modal, and in the programme. Preserve
the current key ownership for transport, and use the platform clipboard
rather than a new editing abstraction. Avoid dropping keyboard equivalents for
unrelated window commands once the focus bridge is proven.

## Prototypes

### Make the Mac useful without the main cabinet open

Prototype a small always-on-top receiver with current title, play, skip,
elapsed time, and an Open Radio action. Drive it from the existing desktop
playback source and the mini-player action semantics. It should never start a
second audio session. Design close and regular-window restoration against the
existing tray and quit behavior. Exercise VoiceOver, keyboard-only use,
full-screen Spaces, and unplugging a display before considering cross-platform
support.

### Offer practical station presets

A few optional enamel preset keys could bring favourite groups within one click
or keypress. Use actual groups and retain the existing chooser for the full
list. Show active state and unplayed count only when useful. Reordering should
be deliberate, with no accidental membership changes. Prototype three presets
on wide screens and evaluate whether they save time during a normal listening
session. Leave keybindings unset until they can coexist with desktop menu
accelerators.

### Use the valves for small, useful moments

The lamp or valve can acknowledge a station change or a finished episode with
a brief warm change. Use the same accessible status text and honor reduced
motion. Keep normal playback static and never introduce button sounds into the
audio. Prototype a single transition and compare idle CPU and distraction over
a listening session.

### Design an evening listening experience

Make an optional player display that dims secondary brass and highlights the
remaining sleep timer close to the Play switch. Use the existing PO-55 cabinet
and materials. Preserve contrast and focus indicators. Keep the timer
controls visible and check that the idle display causes no extra frame
activity. Review the product decision before
implementing a new user preference.

## Native verification on proposed fixes

- Scrub a streaming and a downloaded episode on macOS. Check pointer
  cancellation, playback resumption, and the number of engine seeks and
  writes. Include a duration correction while dragging. [Seek on release](https://github.com/L-K-M/Neutrodyne/pull/565)
- Invoke Mark played from VoiceOver and TalkBack, then check Undo and its
  return focus. Verify the desktop keyboard path with Delete, including on a
  compact Mac keyboard. [Programme slip action](https://github.com/L-K-M/Neutrodyne/pull/566)
