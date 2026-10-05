# Neutrodyne

Neutrodyne is a server-less, open-source Android podcast player organised around groups. A group such as "tech", "news" or "fiction" is a user-defined set of podcasts and YouTube channels, and every group is its own newest-first episode feed. Everything runs on the device: the phone polls feeds itself, with no Neutrodyne backend, no account and no tracking.

**Status: planning — no code yet.** The repository holds the master plan and nine design documents. Implementation starts with milestone M0 ([roadmap](docs/PLAN.md#7-roadmap)).

## Headline features

| ID | Feature | Plan |
|---|---|---|
| R1 | Import and export of subscriptions: tolerant OPML import with preview and report, grouped OPML export, NewPipe/LibreTube/Takeout import, full backup and restore, automatic Android backup (Android 9+ with a screen lock and a system backup transport such as Google backup or Seedvault) | [R1](docs/PLAN.md#21-functional-requirements), [05](docs/design/05-groups-opml-backup.md) |
| R2 | Groups, each with its own episode feed: many-to-many groups shown as swipeable tabs, with filters, counts and per-group defaults | [R2](docs/PLAN.md#21-functional-requirements), [05](docs/design/05-groups-opml-backup.md#group-feeds) |
| R3 | YouTube channels as podcasts: subscribe by link or handle; audio playback and downloads in the `foss` build, "Watch on YouTube" in the `play` build | [R3](docs/PLAN.md#21-functional-requirements), [04](docs/design/04-youtube.md) |
| R4 | Streaming and downloading: background player with a database-owned queue, resumable downloads, auto-download and cleanup | [R4](docs/PLAN.md#21-functional-requirements), [06](docs/design/06-playback.md), [07](docs/design/07-downloads.md) |
| R5 | A cover-first UI: adaptive cover grid, group mosaics, artwork-derived colour, offline artwork for system surfaces | [R5](docs/PLAN.md#21-functional-requirements), [08](docs/design/08-ui-ux.md) |

## How the documents are organised

[docs/PLAN.md](docs/PLAN.md) is the single source of truth. It holds the requirement IDs (R1–R5, N1–N11), the decision register (D-ids), the open product-owner decisions with their defaults (PO-ids), the roadmap with acceptance criteria per milestone (M0–M11) and the risks. Where a design document and the plan disagree, the plan wins until it is amended.

The design documents elaborate the plan; each owns its area and links to the others instead of restating them:

| Document | Covers |
|---|---|
| [01-foundation.md](docs/design/01-foundation.md) | Toolchain and version catalog, modules and dependency rules, architecture patterns, DI, build flavors, networking baseline, platform compliance, licensing policy, M0 scaffold and spikes |
| [02-data-model.md](docs/design/02-data-model.md) | Room 3 schema, identity keys, indices, key queries, invalidation hygiene, retention, migrations |
| [03-feeds-and-discovery.md](docs/design/03-feeds-and-discovery.md) | Feed parser, fetch pipeline, ingestion, refresh scheduling, show notes, add-podcast flow, search, deep links, notifications |
| [04-youtube.md](docs/design/04-youtube.md) | YouTube flavor matrix, channel resolution, Atom ingestion, stream resolution, playback and download integration, licensing and hotfix process |
| [05-groups-opml-backup.md](docs/design/05-groups-opml-backup.md) | Groups, group feeds, effective settings, OPML import and export, backup and restore, Auto Backup |
| [06-playback.md](docs/design/06-playback.md) | Media3 service, URI resolution, streaming cache, queue and play context, positions, sleep timer, chapters, system surfaces |
| [07-downloads.md](docs/design/07-downloads.md) | Download engine, state machine, runners, storage layout, auto-download, cleanup, reconciliation |
| [08-ui-ux.md](docs/design/08-ui-ux.md) | Information architecture, navigation, screens, player sheet, theming, artwork pipeline, adaptive layouts, accessibility |
| [09-quality-and-release.md](docs/design/09-quality-and-release.md) | Testing, CI, static analysis, versioning and signing, distribution, reproducible builds, privacy, crash reporting, performance budgets |

## Licence

The source code in this repository is released into the public domain under the [Unlicense](LICENSE).

One module is the exception: `:youtube:streams` wraps NewPipe Extractor, which is GPL-3.0-or-later, so that module carries GPL-3.0-or-later SPDX headers and is linked only into the `foss` build. The `foss` APK (GitHub Releases, IzzyOnDroid, F-Droid) is therefore a combined work distributed under GPL-3.0-or-later, with the tagged source and a corresponding-source bundle attached to each release. The `play` build contains no GPL-3.0 code. This follows the plan's default for [D3](docs/PLAN.md#3-key-decisions) and [PO-1](docs/PLAN.md#po-1-licensing-of-shipped-binaries) (option A), which the product owner may still change before M9.
