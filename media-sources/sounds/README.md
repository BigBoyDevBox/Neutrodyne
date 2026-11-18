<!-- SPDX-License-Identifier: Unlicense -->

# Control sound sources

The radio's control sounds ([08 Control sounds](../../docs/design/08-ui-ux.md#control-sounds),
PO-56) are built from the public-domain recordings in [`sources/`](sources/). Each is a
recording its author dedicated to the public domain under
[CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/) on
[Freesound](https://freesound.org/). The files are Freesound's high-quality Ogg Vorbis previews,
which download without an account, saved unchanged on 2026-11-18. The credits shipped with the
apps are in [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md#control-sounds-android-and-desktop).

| File in `sources/` | Recording | Author | Page | SHA-256 |
|---|---|---|---|---|
| `freesound-795663-toggle-switch-1.ogg` | "toggle switch 1": "The sound of an old toggle switch with a slight metallic noise." | TheTechTinkerer | [795663](https://freesound.org/people/TheTechTinkerer/sounds/795663/) | `c761ba19a8b7942288bd6758ca01368fe871ddfe1306cd1e8a3649a22e512a3c` |
| `freesound-403247-tape-deck-buttons-and-switches.ogg` | "Tape Deck Buttons and Switches": "Soft and hard button presses and switch flicks. Recorded from a Sony CFD-9 Radio/Cassette Player." | Nekkowe | [403247](https://freesound.org/people/Nekkowe/sounds/403247/) | `582ee74e3b12ae54e98dd425f16ce16cafc4e47b387d65ba39b17f1a917da8e4` |
| `freesound-877301-rotary-selector-ticks.ogg` | "Rotary Selector Ticks": "The clicking of a rotary selector switch recorded 3 inches away from an AT897, Denoised with iZotope RX 11." | loganzsound | [877301](https://freesound.org/people/loganzsound/sounds/877301/) | `a73c9c8ec3ed4b111a44c23aa6439bf2e07877cc66fa48d3844df2d462551070` |
| `freesound-730078-paper-slide.ogg` | "paper-slide": "sliding a piece of paper. a gentle brushing type sound." | brokenmachinery | [730078](https://freesound.org/people/brokenmachinery/sounds/730078/) | `64e2dbce83ff6173ef7d28a313df509841afc317a50b1f33434984163b7b412d` |

## Which part plays where

[`sounds.json`](sounds.json) names, for each app sound, its source file and the span cut from
it (seconds):

| App sound | Cut | What it is |
|---|---|---|
| `switch_on` | toggle switch 1, 0.025–0.265 s | the first flip |
| `switch_off` | toggle switch 1, 0.889–1.129 s | the second flip |
| `key_latch` | tape deck, 4.194–4.445 s | a key clunking down to latch, and the key it releases popping up |
| `key_press` | tape deck, 6.785–6.975 s | a key pressed and let go |
| `dial_tick` | rotary selector, 0.2585–0.3285 s | one detent |
| `slip_discard` | paper slide, 0–0.42 s | the whole slide |

The recordings do not say which flip of the toggle is on and which is off; the first is used for
on.

## Rebuilding

```sh
uv run --with numpy --with scipy --with soundfile python3 tools/sounds/build_sounds.py
```

[`tools/sounds/build_sounds.py`](../../tools/sounds/build_sounds.py) writes
`core/designsystem/src/commonMain/composeResources/files/sounds/<name>.wav` (48 kHz mono 16-bit
PCM). It takes the mix of the source's channels, removes rumble below 70 Hz and content above
18 kHz, cuts the span, resamples, fades both ends and levels the loudest 20 ms to -18 dBFS with
peaks at most -3 dBFS. It rounds instead of dithering, so a rebuild from the same sources and the
same library versions gives the same files. `RadioSoundFilesTest` checks the shipped files'
format, length, level and quiet edges.

To swap a sound, add the new recording to `sources/` (CC0 1.0 or public domain only, never CC BY
or more restrictive licences), point its entry in `sounds.json` at it, rebuild, and update the
credits: the table above, THIRD_PARTY_NOTICES.md, the `sound-freesound-<id>.json` entries in
`app/config/libraries`, `app/config/engine/libraries` and `desktopApp/config/libraries`, and the
claim in `app/policy/android-notices.toml`.

## Alternatives considered

Candidates whose pages showed CC0 when searched on 2026-11-18 and whose recordings looked clean
on analysis, should a sound need replacing (their licences were not re-verified as the chosen
four were): "Socket switch on off.wav" by greatsoundstube
([631540](https://freesound.org/people/greatsoundstube/sounds/631540/)) and "Toggle Switch" by
pillwave ([423957](https://freesound.org/people/pillwave/sounds/423957/)) for the switches;
"Tape machine button press metal" by hanneswannerberg
([275631](https://freesound.org/people/hanneswannerberg/sounds/275631/)) and "key-clack1" by
chris112233 ([738440](https://freesound.org/people/chris112233/sounds/738440/)) for the keys;
"Radial knob clicks turning dial" by el_boss
([643563](https://freesound.org/people/el_boss/sounds/643563/)) for the tick; "Playing Card Slide
Right" by el_boss ([571581](https://freesound.org/people/el_boss/sounds/571581/)) for the slip;
and Kenney's CC0 "UI Audio" pack ([kenney.nl](https://kenney.nl/assets/ui-audio)), whose
`switch*.ogg` are clean single clicks.
