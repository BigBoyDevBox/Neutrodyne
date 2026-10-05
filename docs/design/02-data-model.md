# 02 — Data model

> Status: Draft v1, 2026-10-04 · Implements: R1.3, R1.7, R1.8, R2.3, R2.5, R2.8, R2.9, R3.4, R4.2, R4.4, R4.5, R4.8 / N1, N5, N6, N9 · Milestones: M1, M2, M3, M4, M5, M6, M11 · Honours: D9, D15, D16, D17, D18, D19, D20, D21, D22, D23, D24, D29, D30, D33, D38, D41, D50 · Owns: the Room 3 schema (every entity, column, index and key SQL statement), identity-key storage, invalidation rules, retention, migrations and schema tests

Contents: [Scope](#scope) · [Conventions](#conventions) · [Entity relationship diagram](#entity-relationship-diagram) · [Tables](#tables) · [Identity keys](#identity-keys) · [Indices](#indices) · [Key queries](#key-queries) · [Invalidation hygiene](#invalidation-hygiene) · [Retention and maintenance](#retention-and-maintenance) · [Migrations and schema testing](#migrations-and-schema-testing) · [Error handling and recovery](#error-handling-and-recovery) · [Testing](#testing) · [Delivery by milestone](#delivery-by-milestone) · [Open questions](#open-questions) · [Sources](#sources)

---

## Scope

Serves N1, N5, R2.3, R2.8, R2.9. Delivered in [M1](../PLAN.md#m1-subscribe-and-ingest-rss) (complete schema v1) and extended by every later milestone ([Delivery by milestone](#delivery-by-milestone)).

Room is the single source of truth ([D14](../PLAN.md#3-key-decisions)). This document is the only place where entity definitions, column types, defaults, keys, indices and SQL are specified. Other documents show the subset of columns they read or write and link here.

| This document owns | It does not own (link instead) |
|---|---|
| Every `@Entity`, column, type, default, nullability, PK/FK/index | The ingestion diff algorithm and identity-key *computation* — [03 Ingestion and diff](03-feeds-and-discovery.md#ingestion-and-diff) |
| Type converters, JSON-column formats, bitmask values | Group behaviour, name validation, effective-settings rules — [05 Group model and lifecycle](05-groups-opml-backup.md#group-model-and-lifecycle), [05 Effective settings resolution](05-groups-opml-backup.md#effective-settings-resolution) |
| Identity-key *storage* format, versioning and uniqueness behaviour | Which episodes a play context contains (rules) — [05 Playing a group](05-groups-opml-backup.md#playing-a-group); queue projection — [06 Queue and play context](06-playback.md#queue-and-play-context) |
| All SQL of the key queries (feeds, counts, context tail, live state, refresh selection, download claim, auto-download, cleanup, retention, backup, restore) | Download state machine semantics and policies — [07 State machine](07-downloads.md#state-machine), [07 Auto-download policy](07-downloads.md#auto-download-policy) |
| Invalidation rules, `observedEntities`, churn classes | Artwork files, key derivation, pinning reasons — [08 Artwork pipeline](08-ui-ux.md#artwork-pipeline) |
| Retention (D23) and the `db-maintenance` worker | Backup archive format and merge rules — [05 Full backup and restore](05-groups-opml-backup.md#full-backup-and-restore) |
| Room 3 usage conventions, migration policy, schema tests | Hilt bindings and start-up order — [01 Dependency injection](01-foundation.md#dependency-injection); CI wiring — [09 CI pipelines](09-quality-and-release.md#ci-pipelines) |

### Module placement

| Module | Contents specified here |
|---|---|
| `:core:model` (JVM) | Enums of the canonical list plus [new enums](#new-names-introduced-here); `EpisodeRow`; bit constants `YouTubeVariantBits`, `FilterFlagBits` |
| `:core:database` (Android, `neutrodyne.room`) | `NeutrodyneDatabase`, all entities (`<Table>Entity`), DAOs, DAO projections, `FeedQueryBuilder`, `NeutrodyneConverters`, `EpisodeDescriptionCodec`, `DatabaseOpener`, migrations, `core/database/schemas/` |
| `:core:data` (Android) | `DbMaintenanceWorker`; entity ↔ `:core:model` mappers; JSON-column codecs (kotlinx.serialization) |

Only `:core:data`, `:core:artwork`, `:playback:impl` and `:download:impl` depend on `:core:database` ([PLAN 5.1](../PLAN.md#51-module-graph) rules 2 and 4). Features never see entities or DAOs; they receive `:core:model` types through `:core:domain` interfaces ([D12](../PLAN.md#3-key-decisions)).

### Table write ownership

"Churn" drives [Invalidation hygiene](#invalidation-hygiene). Every writer uses the column-scoped DAO methods named in [Key queries](#key-queries); no writer rewrites another owner's columns.

| Table | Churn | Writers (doc: class) | Main readers |
|---|---|---|---|
| `podcast` | low (fetch state batched) | 03: `FeedRefresher`, `SubscribeUseCase`; 04: YouTube columns via 03's engine, channel art and `channelMetadataAt` via `PodcastDao.applyYouTubeChannelMetadata`; 05: `ImportRepository`, `RestoreWorker` (insert), `GroupRepository`/podcast settings (`includeInAll`, `customTitle`, `episodeOrder`); 07: `AutoDownloadPlanner` (`autoDownloadEligibleAfter`) | everyone |
| `podcast_url_alias`, `credential` | low | 03 (subscribe, moves, merge, `CredentialStore`); 05 (import, restore aliases) | 03, 05, 06, 07 |
| `podcast_settings`, `podcast_group_settings` | low | 05 settings screens; 05 restore | `EffectiveSettingsResolver` (05) |
| `podcast_group`, `podcast_group_member` | low | 05 `GroupRepository`, import, restore | 05, 06, 08 |
| `episode` and children (`episode_description`, `episode_transcript`, `episode_alt_enclosure`, `person`, `funding`) | low | 03 ingestion; 04 enrichment (`IngestDao.applyYouTubeFacts`: `durationMs`, `availability`, `isShort`) as part of the refresh pipeline; 04 `YouTubeAvailabilityRecorder` (`EpisodeDao.setAvailability`, called by 06/07 at resolve time); 05 restore (stub rows); 02 retention (delete) | everyone |
| `chapter` | low | 03 (PSC rows); 06 (other sources) | 06, 08 |
| `episode_state` | low | 06 (started, played, measured duration); 03/08 via `EpisodeRepository` (favourite, bulk played); 07 (tombstone); 05 (import "treat as played", restore) | lists, 05, 06, 07 |
| `episode_position` | **high** (every 5 s while playing) | 06 `PositionTracker`; 05 restore | `EpisodeLiveStateSource` (08), 06 |
| `queue_entry`, `play_session` | medium (every transition) | 06; 05 restore | 06 |
| `download` | low (transitions only, [D17](../PLAN.md#3-key-decisions)) | 07 | lists, 06 `LocalMediaIndex` (via 07), 07 |
| `artwork` | low (batched) | 08 `ArtworkSyncWorker`, `ArtworkStore` | lists, 08 |
| `import_session`, `import_item` | medium during an import | 05 | 05 |

Exceptions to [D15](../PLAN.md#3-key-decisions) "episode is written only by ingestion" (recorded for a PLAN amendment): restore inserts stub rows that ingestion completes later; retention deletes rows; 04's `YouTubeAvailabilityRecorder` writes only `availability` when a stream resolve proves a video unavailable. None of them writes user state, and none rewrites other feed-derived columns of an existing row. 04's enrichment writes run inside the refresh pipeline and count as ingestion.

### New names introduced here

| Name | Kind / location | Purpose | Consumers |
|---|---|---|---|
| `ShowType { EPISODIC, SERIAL }`, `EpisodeType { FULL, TRAILER, BONUS }` | enums, `:core:model` | `podcast.showType`, `episode.episodeType` | 03, 08 |
| `FeedErrorKind` | enum, `:core:model`; values owned by 03, must include `UNKNOWN` | `podcast.lastErrorKind` | 03, 08 |
| `OwnerType { PODCAST, EPISODE }` | enum, `:core:model` | `person.ownerType`, `funding.ownerType` | 03 |
| `ImportItemKind { RSS, YOUTUBE }` | enum, `:core:model` | `import_item.kind` | 05 |
| `AliasReason { SUBSCRIBE_INPUT, REDIRECT, NEW_FEED_URL, IMPORT, RESTORE, MERGE, RENORMALISED }` | enum, `:core:model` | `podcast_url_alias.reason` | 03, 05 |
| `YouTubeVariantBits { LONG_FORM = 1, SHORTS = 2, LIVE = 4 }`, `FilterFlagBits { UNPLAYED = 1, DOWNLOADED = 2, IN_PROGRESS = 4 }` | constant objects, `:core:model` | Values of `podcast.youtubeVariants`, `podcast_group.filterFlags`, `play_session.contextFilterFlags` | 04, 05, 06 |
| `podcast.episodeOrder` | column `FeedOrder?` | Order of the podcast screen; null = `OLDEST_FIRST` when `showType = SERIAL`, else `NEWEST_FIRST` | 05, 08 |
| `podcast.autoDownloadEligibleAfter` | column `Long?` | D67 watermark: episodes with `firstSeenAt` ≤ it are never auto-download candidates | 07 |
| `podcast.pendingNewFeedUrl`, `podcast.lastFullFetchAt` | columns | Lazy `itunes:new-feed-url` adoption; time of the last 200 response with a parsed body (weekly unconditional fetch rule) | 03 |
| `podcast.channelMetadataAt` | column `Long?` (requested by 04) | Last YouTube channel-page or extractor metadata fetch (avatar, banner, description); null = never. Drives 04's 30-day avatar refresh and lazy banner | 04 |
| `ImportFormat.URL_LIST` | constant appended to the canonical `ImportFormat` (requested by 04) | Plain list of URLs, `UC…` IDs or handles; stored as `TEXT`, so no migration | 04, 05 |
| `podcast_url_alias.reason`, `podcast_url_alias.addedAt` | columns | Why and when an alias was recorded | 03, 05 |
| `episode_alt_enclosure.codecs`, `episode_alt_enclosure.isDefault` | columns | Podcasting 2.0 `alternateEnclosure@codecs`, `@default` | 03, 06 |
| `play_session.contextMediaFilter`, `contextMinSortDate`, `contextAnchorSortDate` | columns | Full context filter set; keyset anchor that survives deletion of the anchor row | 05, 06 |
| `download.requireCharging` | column `Boolean` | Per-row charging requirement (AUTO policy) for the claim query | 07 |
| `import_session.finishedAt` | column `Long?` | Start of the 7-day cleanup window | 05 |
| `EpisodeKeys.candidates(item)`, `EpisodeKeys.keyFor(episode, version)`, `EpisodeKeys.versionOf(key)` | required members of the canonical `EpisodeKeys` (`:feeds`, implemented by 03) | Version-tolerant matching ([Key versions](#key-versions)) | 03, 05 |
| `ScopeOverrides` | `@Embedded` class, `:core:database` | Guarantees identical columns in both settings tables | 05 |
| `NeutrodyneConverters`, `EpisodeDescriptionCodec`, `DatabaseOpener`, `OpenResult`, `RecoveryCause`, `DatabaseOpenException`, `TableRebuild`, `ForeignKeysDriver` (only if spike S3 needs it) | classes, `:core:database` | Converters, show-notes storage, open/recovery, migration helper | 01, 03, 05 |
| `FetchStateBatcher` | class, `:core:data` | Batches fetch-state-only `podcast` writes ([Refresh selection and fetch-state writes](#refresh-selection-and-fetch-state-writes)) | 03 |
| `EpisodeRowProjection`, `ContextItem`, `MediaLookupRow`, `ExistingEpisodeKey`, `EpisodeFeedUpdate`, `PodcastFeedMetadata`, `PodcastFetchState`, `DueFeed` (requested by 03), `YouTubeFeedMetadata`, `YouTubeFacts`, `ArtworkSyncResult`, `QueryPlanRow` | DAO projections, `:core:database` | Query results and partial-entity updates | 03, 04, 06, 07, 08 |
| `PodcastDao`, `EpisodeDao`, `IngestDao`, `FeedDao`, `GroupDao`, `ScopeSettingsDao`, `EpisodeStateDao`, `PositionDao`, `QueueDao`, `PlaySessionDao`, `DownloadDao`, `ArtworkDao`, `ChapterDao`, `CredentialDao`, `ImportDao`, `BackupDao`, `MaintenanceDao` | DAOs, `:core:database` | One DAO per area | impl modules |
| `diagnostics.db_quick_check_failed_at` | `device_settings` key, `Long` | Last failed `PRAGMA quick_check`, shown on the diagnostics screen | 09 |
| `MigrationInvariants`, `SeedDatabase`, `TestDb`, `SqlEnumLiterals` | test utilities, `:core:database` Android test fixtures (`core/database/src/testFixtures/`, [09 Shared helpers](09-quality-and-release.md#shared-helpers)) | Migration invariants, seeded scale DB, in-memory and temp-file DB factory, enum names used in SQL | 09 |

---

## Conventions

Serves N1, N9, N11. Delivered in M1.

### Naming and types

| Item | Rule |
|---|---|
| Tables | `snake_case`, exactly the canonical names |
| Columns | `camelCase` = Kotlin property name (Room default); never `@ColumnInfo(name = …)` renames |
| Entity classes | `<PascalCaseTable>Entity` (`PodcastGroupMemberEntity`) |
| Indices | Room default names `index_<table>_<col>[_<col>]` (EXPLAIN tests refer to them) |
| Primary keys | `Long` `@PrimaryKey(autoGenerate = true)`. Room 3's `algorithm` parameter defaults to `AUTOINCREMENT` (the alternative `ROWID` reuses IDs and is never used here), so deleted IDs are never reused, which keeps `episode:{id}` media IDs, notifications and `[e<id>]` file names unambiguous; `SchemaSmokeTest` asserts `AUTOINCREMENT` in `1.json`. Table rebuilds must preserve the `sqlite_sequence` high-water mark ([Writing migrations](#writing-migrations)). Natural keys where canonical (`artwork.key`, `podcast_url_alias.url`) |
| Timestamps | `Long` epoch milliseconds UTC from the injected `Clock` (never `System.currentTimeMillis()` in DAOs) |
| Booleans | Kotlin `Boolean` → `INTEGER` 0/1 |
| Enums | `TEXT` holding `Enum.name` via explicit converters ([Type converters](#type-converters)) |
| UUIDs | `TEXT`, lowercase canonical 8-4-4-4-12 ([D21](../PLAN.md#3-key-decisions)) |
| Colours | `Int` ARGB (`INTEGER`) |
| Binary | `ByteArray` → `BLOB` |
| Large columns | Declared **last** in the entity so SQLite reads hot columns without walking overflow pages (`descriptionHtml`, `categoriesJson`, `snippet`) |
| Defaults | Every non-null column that has a Kotlin default also has `@ColumnInfo(defaultValue = …)`, so raw SQL inserts (stubs, migrations) and future `ALTER TABLE ADD COLUMN` are well-defined |

### Type converters

One class, registered on the database: `@ColumnTypeConverters(NeutrodyneConverters::class)`. Each enum has an explicit pair (`fromX`/`toX`). Reading an unknown name returns the fallback below instead of throwing, so a value written by a newer build never crashes list rendering.

| Enum (`:core:model`) | Column(s) | Fallback on unknown name |
|---|---|---|
| `SourceType` | `podcast.sourceType` | `RSS` |
| `PodcastStatus` | `podcast.status` | `ACTIVE` |
| `FeedOrder` | `podcast_group.feedOrder`, `.playOrder`, `podcast.episodeOrder`, `play_session.contextOrder` | `NEWEST_FIRST` |
| `MediaFilter` | `podcast_group.mediaFilter`, `play_session.contextMediaFilter` | `ALL` |
| `GroupKind`, `MemberSource` | `podcast_group.kind`, `podcast_group_member.source` | `MANUAL` |
| `Availability` | `episode.availability` | `UNAVAILABLE` |
| `ChapterSource` | `chapter.source` | `PSC` |
| `PositionSource` | `episode_position.positionSource` | `STREAM` |
| `ContextType` | `play_session.contextType` | `null` (no context) |
| `NetworkPolicy`, `DeleteAfter` | settings tables | `UNMETERED`, `NEVER` (most conservative) |
| `DownloadState`, `DownloadLane`, `WaitReason`, `DownloadError`, `SourceKind` | `download` | `FAILED`, `MANUAL`, `NONE`, `UNKNOWN`, `RSS_ENCLOSURE` |
| `ImportFormat` (incl. `URL_LIST`), `ImportState`, `ImportItemStatus`, `ImportItemKind` | import tables | `OPML`, `DONE`, `FETCH_FAILED`, `RSS` |
| `ShowType`, `EpisodeType`, `FeedErrorKind`, `OwnerType`, `AliasReason` | new columns | `null`, `null`, `UNKNOWN`, `EPISODE`, `IMPORT` |

```kotlin
class NeutrodyneConverters {
    @ColumnTypeConverter fun fromDownloadState(v: DownloadState?): String? = v?.name
    @ColumnTypeConverter fun toDownloadState(v: String?): DownloadState? = v?.let { enumOr(it, DownloadState.FAILED) }
    // … one pair per enum in the table above
}
inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: fallback
```

The stored name is the contract: enum constants of persisted enums are only ever **appended**. Renaming or removing one requires a migration (`UPDATE <table> SET <col> = 'NEW' WHERE <col> = 'OLD'`) and a `ConverterTest` case; R8 does not affect `Enum.name` because `:app` keeps `-dontobfuscate` ([01](01-foundation.md#build-flavors)). Enum names used as SQL literals in this document (`'COMPLETED'`, `'YOUTUBE_CHANNEL'`, `'PENDING_FIRST_FETCH'`, …) are collected in the test-only list `SqlEnumLiterals`; `ConverterTest` asserts that each still exists in its enum.

### JSON columns and bitmasks

JSON columns are typed `String` in entities. Encoding and decoding happen in `:core:data` mappers with one shared `Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }`, so `:core:database` stays ignorant of DTOs owned by 03 and 05. No SQL ever reads inside JSON (no JSON1 functions; see [SQL dialect baseline](#sql-dialect-baseline)).

| Column | Shape | Owner of shape |
|---|---|---|
| `podcast.categoriesJson` | `List<List<String>>`: one path per `itunes:category`, outermost first (`[["Technology"],["Society & Culture","Documentary"]]`) | 02 (03 fills) |
| `episode_alt_enclosure.sourcesJson` | `List<{ "uri": String, "contentType": String? }>` | 03 |
| `import_item.groupNamesJson` | `List<String>` (trimmed, NFC) | 05 |
| `import_session.optionsJson`, `.warningsJson` | 05's `ImportOptions` / `List<ImportWarning>` DTOs | 05 |
| `podcast_group.ruleJson` | Reserved (smart groups), versioned `{ "v": 1, … }`; always `null` in v1 | 05 |

| Bitmask column | Bits | Notes |
|---|---|---|
| `podcast.youtubeVariants` | `LONG_FORM = 1`, `SHORTS = 2`, `LIVE = 4` | Default 1; meaningful only for `YOUTUBE_CHANNEL`; semantics in [04 Atom feed ingestion](04-youtube.md#atom-feed-ingestion) |
| `podcast_group.filterFlags`, `play_session.contextFilterFlags` | `UNPLAYED = 1`, `DOWNLOADED = 2`, `IN_PROGRESS = 4` | Media filter and minimum date are separate columns, never bits |

### Database builder and connections

`:core:database` exposes a factory; [01 Dependency injection](01-foundation.md#dependency-injection) binds the `SQLiteDriver` (`BundledSQLiteDriver` in production, `AndroidSQLiteDriver` in JVM/Robolectric tests, [D9](../PLAN.md#3-key-decisions)) and calls it through [`DatabaseOpener`](#error-handling-and-recovery).

```kotlin
@Database(entities = [/* the 22 entities of §Tables */], version = NeutrodyneDatabase.VERSION, exportSchema = true)
@ColumnTypeConverters(NeutrodyneConverters::class)
abstract class NeutrodyneDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun ingestDao(): IngestDao
    abstract fun feedDao(): FeedDao
    // … one accessor per DAO listed in "New names introduced here"
    companion object {
        const val VERSION = 1
        const val FILE_NAME = "neutrodyne.db"
        fun build(ctx: Context, driver: SQLiteDriver, io: CoroutineContext, cb: RoomDatabase.Callback) =
            Room.databaseBuilder<NeutrodyneDatabase>(ctx, ctx.getDatabasePath(FILE_NAME).absolutePath)
                .setDriver(driver)                                  // wrapped by ForeignKeysDriver only if spike S3 says so
                .setQueryCoroutineContext(io)                       // @Dispatcher(IO)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)    // explicit; WAL is mandatory
                .addMigrations(*ALL_MIGRATIONS)
                .addCallback(cb)                                    // suspend onCreate → OpenResult.created; suspend onOpen → PRAGMA optimize
                .build()                                            // never fallbackToDestructiveMigration*()
    }
}
```

- One database instance per process; only the main process opens it. The `:acra` process guard ([01](01-foundation.md#architecture-patterns)) must skip DB initialisation. Multi-instance invalidation stays off.
- WAL: with `WRITE_AHEAD_LOGGING` and no explicit pool setting, Room's pool has **one writer and four readers**; readers never block the writer and see a consistent snapshot. A caller waits for a pooled connection for at most 30 s and then gets an `SQLiteException`, so no transaction may run anywhere near that long ([Transactions and threading](#transactions-and-threading)). Room itself sets `busy_timeout` (≥ 3 s), `journal_mode` and `synchronous = NORMAL` on each connection. An in-memory database (tests) always uses a single connection, so tests of reader isolation use a temp-file database.
- **Opening.** Room opens the first connection, runs `BEGIN EXCLUSIVE TRANSACTION` → `onCreate` or the migrations → `END`, then calls the generated `onOpen` and our `Callback.onOpen`; callbacks must use only the `connection` they receive (touching the database instance or a DAO there fails with "Recursive database initialization detected"). Room retries a failing first open once after 500 ms. If the builder offers `allowDataLossOnRecovery()` it is never called: Room would then delete a corrupt file itself, while [`DatabaseOpener`](#error-handling-and-recovery) quarantines it instead.
- **Foreign keys.** `foreign_keys` must be `ON` on every connection after the database is open, and **`OFF` while migrations run** (see [Writing migrations](#writing-migrations): a table rebuild with foreign keys on cascades deletes into child tables). Room 3's generated `onOpen` executes `PRAGMA foreign_keys = ON` when any entity declares a foreign key, both for the first connection (after migrations) and for every later pooled connection; this matches what we need. Checked in the `androidx-main` sources of `room3-compiler`'s `OpenDelegateWriter` and `room3-runtime`'s `RoomConnectionManager` (2026-10-05); spike S3 ([01 Spikes](01-foundation.md#spikes)) confirms it for 3.0.3 with the bundled driver, including that the bundled SQLite is not compiled with foreign keys on by default (`PRAGMA foreign_keys` = 0 inside `Migration.migrate`). Fallback only if S3 fails: `ForeignKeysDriver(delegate, armed: () -> Boolean)`, a `SQLiteDriver` decorator whose `open()` runs `PRAGMA foreign_keys = ON` once `DatabaseOpener` has armed it after the first successful open. `SchemaSmokeTest` asserts the pragma on the writer and on a reader ([Testing](#testing)).
- `onOpen`: `PRAGMA optimize=0x10002` with the bundled driver, plain `PRAGMA optimize` with the framework driver; plain `PRAGMA optimize` daily in `db-maintenance` and after a migration that adds an index ([SQLite pragma optimize](https://www.sqlite.org/pragma.html#pragma_optimize): recommended usage since 3.46.0).

### Transactions and threading

| Rule | Detail |
|---|---|
| All DAO functions are `suspend`, return `Flow`, or return `PagingSource` | Room 3 requires coroutines; there are no blocking DAO calls and no main-thread queries. Synchronous lookups on the player loader thread use in-memory mirrors (`LocalMediaIndex`, 07; episode source index, 06) |
| Query context | `@Dispatcher(NeutrodyneDispatchers.IO)` via `setQueryCoroutineContext`; CPU-bound work (parsing, hashing, compression) happens **before** the transaction on `Default` |
| Multi-statement writes | `db.withWriteTransaction { }` (suspend extension on `RoomDatabase`) or `@Transaction` DAO functions. Write transactions run on the pool's single writer connection, so they serialise all writers and read-then-write logic inside them is race-free (Unverified detail: whether Room 3 opens them as `BEGIN IMMEDIATE`; the single writer makes the result the same) |
| Consistent multi-query reads | `db.withReadTransaction { }` (suspend extension, exists in Room 3) for backup export and the Auto Backup snapshot |
| No I/O inside transactions | No network, no file copies, no `ContentResolver` calls inside a transaction |
| Transaction length | Every transaction stays well under 1 s on the reference device (the longest is a large feed's ingest, ≤ 150 ms for 831 items); other callers wait for the single writer and time out after 30 s. `VACUUM` is the only multi-second write and runs under the guards of [db-maintenance worker](#db-maintenance-worker) |
| Batch sizes | Per-feed ingest: one transaction per fetched document (a feed page holds at most a few thousand items; RFC 5005 older pages are separate documents and separate transactions, 03); import commit: 500 items per transaction; restore: 1,000 episode lines per transaction; retention: 500 episodes per transaction; cleanup: one transaction per deleted file's row; `IN (:ids)` lists chunked at 500 |
| Cancellation | A cancelled coroutine rolls back its open transaction. Callers use `suspendRunCatching` (rethrows `CancellationException`) |
| Expected write latency | ≤ 150 ms for an 831-item feed diff; the 5-s position write may wait behind it, which is harmless |

### DAO rules

1. **Column-scoped writes for shared tables.** `podcast`, `episode_state`, `download` and `play_session` have several writers. They are written only with targeted `UPDATE … SET <owned columns>` statements or partial-entity `@Update(entity = …)` classes, never by upserting a whole entity that another module may have changed (lost updates).
2. **Row creation for lazily created rows** (`episode_state`, `episode_position`, settings): `INSERT OR IGNORE` with neutral values, then a targeted `UPDATE`. An ignored insert and an `UPDATE` that matches zero rows fire no Room trigger, so they cause no invalidation. An `UPDATE` that matches a row but writes identical values **does** fire the trigger, so every repeated write to a joined table carries a "value differs" predicate (`… WHERE episodeId = :id AND waitReason IS NOT :reason`).
3. **Never `OnConflictStrategy.REPLACE` / `INSERT OR REPLACE` on an FK parent table** (`podcast`, `episode`, `podcast_group`, `credential`, `import_session`). REPLACE deletes the existing row; with `ON DELETE CASCADE` children (episodes, user state) can be lost. (Unverified whether SQLite applies ON DELETE actions to REPLACE-deleted rows; forbidden regardless.) `@Upsert` (insert, then update on conflict) is allowed only for single-writer tables: `artwork`, `podcast_settings`, `podcast_group_settings`, `chapter`.
4. **Raw queries** (`@RawQuery`) are built only by `FeedQueryBuilder` from enumerated fragments; every value is bound, never concatenated. `observedEntities` must list every table the SQL references.
5. **Paged and observed list queries** follow [Invalidation hygiene](#invalidation-hygiene).
6. **Projections** are DAO-local data classes (`EpisodeRowProjection`); `:core:data` maps them to `:core:model` types (`PagingData.map`). Room never maps into `:core:model` classes directly.

### SQL dialect baseline

All SQL must run on **SQLite 3.18** (framework SQLite on API 26), even though production uses the bundled driver. Reason: production must be able to fall back to `AndroidSQLiteDriver` if spike S6 finds the APK-size or 16 KB-alignment cost of `sqlite-bundled` unacceptable ([01 Spikes](01-foundation.md#spikes)). JVM tests use `AndroidSQLiteDriver` on Robolectric's own native SQLite build, whose version is neither 3.18 nor the bundled one (recorded by spike S4), so JVM tests prove correctness but not the 3.18 baseline; the API 26 GMD run with `AndroidSQLiteDriver` does.

| Allowed | Forbidden |
|---|---|
| Row values `(a, b) < (?, ?)` (3.15) | Window functions (3.25) — use correlated `LIMIT 1` subqueries or Kotlin |
| `CROSS JOIN` to fix join order | SQL `UPSERT … ON CONFLICT DO UPDATE` (3.24) — Room `@Upsert` does not need it |
| `INSERT OR IGNORE`, `NOT EXISTS`, correlated subqueries with `LIMIT` | `NULLS FIRST/LAST` (3.30, Unverified version), `RETURNING` (3.35), JSON1 functions, generated columns |
| `PRAGMA optimize` (3.18) | `ALTER TABLE … RENAME COLUMN` / `DROP COLUMN` in hand-written migrations (3.25 / 3.35, Unverified versions) — use the table-rebuild procedure |

Framework SQLite versions by API (relevant only for the fallback driver; some manufacturers ship other versions): 26 → 3.18, 27 → 3.19, 28 → 3.22, 30 → 3.28, 31–33 → 3.32, 34 → 3.39/3.42, 35 → 3.44, 36.1/37 → 3.50 ([android.database.sqlite](https://developer.android.com/reference/android/database/sqlite/package-summary), re-checked 2026-10-05). SQLite before 3.32 allows only 999 bound variables per statement, hence the 500-ID chunking rule (Unverified: limit value not re-checked).

### Room 2 to Room 3 mapping

AI sessions tend to emit Room 2 code (risk [T1](../PLAN.md#8-risks-and-mitigations)). Use the right-hand column. Rows without a mark were checked against the Room 3 release notes and the `room3` sources on 2026-10-05; rows marked Unverified are confirmed by spike S2 ([01 Spikes](01-foundation.md#spikes)) and the first DAO/migration written in M1; correct this table if they differ.

| Room 2.x | Room 3 (`androidx.room3`, 3.0.3) |
|---|---|
| `androidx.room.*`, kapt or KSP | `androidx.room3.*`, **KSP only** (`room3-compiler`), Kotlin codegen only, Gradle plugin `androidx.room3` with `room3 { schemaDirectory("$projectDir/schemas") }` (extension name Unverified) |
| `@TypeConverter` / `@TypeConverters` | `@ColumnTypeConverter` / `@ColumnTypeConverters` |
| `runInTransaction {}`, `withTransaction {}` | `withWriteTransaction {}`; reads: `withReadTransaction {}` |
| `SupportSQLiteDatabase`, `Cursor`, `query(…)` | `SQLiteConnection` / `SQLiteStatement` via `useReaderConnection` / `useWriterConnection` + `usePrepared` (`room3-sqlite-wrapper` exists; never used here) |
| `Migration.migrate(SupportSQLiteDatabase)` | `suspend fun migrate(connection: SQLiteConnection)`; already called inside Room's migration transaction (possibly one transaction for all pending migrations) |
| `setQueryExecutor`, `allowMainThreadQueries()` | `setQueryCoroutineContext(…)`; no `Executor`, no main-thread mode |
| `Room.databaseBuilder(ctx, Db::class.java, "name")` | `Room.databaseBuilder<Db>(ctx, absolutePath)` + `setDriver(…)` (driver mandatory) |
| `setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)` | unchanged name (`JournalMode.TRUNCATE` / `WRITE_AHEAD_LOGGING`) |
| `SimpleSQLiteQuery` for `@RawQuery` | `RoomRawQuery(sql) { stmt -> stmt.bindLong(1, …) }` |
| `room-paging` `PagingSource` return type | `room3-paging` + `@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)` on the DAO |
| `InvalidationTracker.Observer`, `addObserver` | removed; `invalidationTracker.createFlow(vararg tables)` |
| `MigrationTestHelper(instrumentation, Db::class.java)` | `MigrationTestHelper(instrumentation, databaseClass = Db::class, driver = …, file = …)` (`room3-testing`; parameter names Unverified) |
| `@Entity` has no rowid option | `@Entity(withoutRowId = true)` |
| `@PrimaryKey(autoGenerate = true)` (always `AUTOINCREMENT`) | same, with `algorithm` defaulting to `AUTOINCREMENT` (`ROWID` would reuse IDs; never used) |
| `Callback.onCreate(db: SupportSQLiteDatabase)` | `override suspend fun onCreate(connection: SQLiteConnection)` (same for `onOpen`, `onDestructiveMigration`) |
| `clearAllTables()` | `suspend fun clearAllTables()` (used only by tests) |
| Built-in `UUID` converter | None in 3.0.x (`kotlin.uuid.Uuid` only from 3.1.0-alpha01): store `String` ([D21](../PLAN.md#3-key-decisions)) |
| `@AutoMigration` | Assumed unchanged (Unverified until the first auto-migration) |

### Platform constraints

| Constraint | Consequence | Source |
|---|---|---|
| `sqlite-bundled` ships native `.so` per ABI | 16 KB page alignment checked in CI (09) and by spike S6; APK size budget includes it | [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes), [SQLite drivers](https://developer.android.com/kotlin/multiplatform/sqlite) |
| The Android `sqlite-bundled` artifact ships `.so` files for Android ABIs only, so it is not expected to load under Robolectric on the host JVM (Unverified until spike S4) | JVM tests inject `AndroidSQLiteDriver`; the bundled driver is exercised by instrumented tests | [SQLite drivers](https://developer.android.com/kotlin/multiplatform/sqlite) |
| Auto Backup never includes `databases/` (include-only rules, [D34](../PLAN.md#3-key-decisions)) | A reinstall or new phone starts with an empty DB; `onCreate` triggers the snapshot restore of [05 Auto Backup](05-groups-opml-backup.md#auto-backup) | [Auto Backup](https://developer.android.com/identity/data/autobackup) |
| `hasFragileUserData = true` (07) | A "keep app data" uninstall leaves the DB; a later install may open **any** released schema version, so every released version must keep a migration path | [07 Lifecycle and reconciliation](07-downloads.md#lifecycle-and-reconciliation) |
| DB lives in credential-encrypted storage | No component touches it before first unlock; nothing is direct-boot aware | — |
| Android 16 job quotas apply to `db-maintenance` | The worker checkpoints in 500-row chunks and stops at an 8-min soft deadline ([N2](../PLAN.md#22-non-functional-requirements)) | [Android 16 behaviour changes](https://developer.android.com/about/versions/16/behavior-changes-all), [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) |

---

## Entity relationship diagram

Solid lines are foreign keys (cascade unless noted in [Tables](#tables)); dotted lines are logical references without FK.

```mermaid
erDiagram
  credential ||--o{ podcast : "auth (SET NULL)"
  podcast ||--o{ podcast_url_alias : "known as"
  podcast ||--o| podcast_settings : overrides
  podcast ||--o{ episode : has
  podcast ||--o{ podcast_group_member : "member of"
  podcast_group ||--o{ podcast_group_member : contains
  podcast_group ||--o| podcast_group_settings : overrides
  episode ||--o| episode_description : notes
  episode ||--o{ episode_transcript : transcripts
  episode ||--o{ episode_alt_enclosure : alternates
  episode ||--o{ chapter : chapters
  episode ||--o| episode_state : "user state"
  episode ||--o| episode_position : position
  episode ||--o| queue_entry : "up next"
  episode ||--o| download : download
  episode |o--o| play_session : "current (SET NULL)"
  import_session ||--o{ import_item : items
  podcast |o--o{ import_item : "result (SET NULL)"
  podcast ||..o{ person : "ownerType PODCAST"
  episode ||..o{ person : "ownerType EPISODE"
  podcast ||..o{ funding : "ownerType PODCAST"
  episode ||..o{ funding : "ownerType EPISODE"
  podcast }o..o| artwork : artworkKey
  episode }o..o| artwork : artworkKey
  podcast_group |o..o| artwork : "g-uuid mosaic"
```

---

## Tables

Serves N1, R2.3, R3.4, R4.2, R4.8. Delivered in M1: **every table below exists in schema version 1** ([D22](../PLAN.md#3-key-decisions)), even if its first writer arrives later. Each sketch is the complete column list; Room annotations are abbreviated (`CASCADE` = `ForeignKey(…, onDelete = ForeignKey.CASCADE)` on the named column).

### podcast

One row per subscription (RSS feed or YouTube channel). Previews are never persisted ([D24](../PLAN.md#3-key-decisions)): every row is subscribed. Low churn; the fetch-state columns are written in batches ([Refresh selection and fetch-state writes](#refresh-selection-and-fetch-state-writes)).

```kotlin
@Entity(tableName = "podcast",
    indices = [Index("feedKey", unique = true), Index("nextRefreshAt"), Index("podcastGuid"), Index("credentialId")],
    foreignKeys = [ForeignKey(CredentialEntity::class, ["id"], ["credentialId"], onDelete = ForeignKey.SET_NULL)])
data class PodcastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: SourceType, val feedUrl: String, val feedKey: String,          // identity (03; YouTube 04)
    val youtubeChannelId: String? = null, @ColumnInfo(defaultValue = "1") val youtubeVariants: Int = 1,
    val channelMetadataAt: Long? = null,                                         // YouTube art/description fetch (04)
    val podcastGuid: String? = null, @ColumnInfo(defaultValue = "0") val podcastGuidDerived: Boolean = false,
    val title: String, val author: String? = null, val link: String? = null,     // feed metadata (03)
    val language: String? = null, val explicit: Boolean? = null, val showType: ShowType? = null,
    val medium: String? = null, val locked: Boolean? = null, @ColumnInfo(defaultValue = "0") val complete: Boolean = false,
    val artworkUrl: String? = null, val artworkKey: String, val bannerUrl: String? = null,
    val customTitle: String? = null, @ColumnInfo(defaultValue = "1") val includeInAll: Boolean = true,   // user (05)
    val episodeOrder: FeedOrder? = null, val autoDownloadEligibleAfter: Long? = null,                     // 05, 07
    val status: PodcastStatus, val initialFetch: Boolean, val subscribedAt: Long, val latestEpisodeAt: Long? = null,
    val etag: String? = null, val lastModified: String? = null, val contentSha256: String? = null,       // validators (03)
    @ColumnInfo(defaultValue = "0") val parserVersion: Int = 0, @ColumnInfo(defaultValue = "0") val lastParseOk: Boolean = false,
    val lastAttemptAt: Long? = null, val lastSuccessAt: Long? = null, val lastFullFetchAt: Long? = null,  // scheduling (03)
    val nextRefreshAt: Long? = null, @ColumnInfo(defaultValue = "0") val failureCount: Int = 0,
    val lastErrorKind: FeedErrorKind? = null, val lastErrorDetail: String? = null,
    @ColumnInfo(defaultValue = "0") val gone: Boolean = false,
    @ColumnInfo(defaultValue = "0") val needsCredentials: Boolean = false,
    val ttlMinutes: Int? = null, val updateFrequencyRrule: String? = null,
    val pendingNewFeedUrl: String? = null, val pagingNextUrl: String? = null,                             // moves, paging (03)
    @ColumnInfo(defaultValue = "0") val pagingComplete: Boolean = false,
    val hubUrl: String? = null, @ColumnInfo(defaultValue = "0") val usesPodping: Boolean = false,
    val credentialId: Long? = null,
    val descriptionHtml: String? = null, val categoriesJson: String? = null,                             // large, last
)
```

| Column / rule | Detail |
|---|---|
| `feedUrl` | Current fetch URL without userinfo (credentials live in `credential`). For YouTube: `https://www.youtube.com/feeds/videos.xml?channel_id={UC…}` |
| `feedKey` | `UrlNormalizer.forIdentity(feedUrl)` ([Identity keys](#podcast-feedkey-and-aliases)); rewritten together with `feedUrl` |
| `title` | Never null. Before the first fetch: OPML/backup title, else the URL host. Display title = `COALESCE(customTitle, title)` |
| `artworkKey` | Never null: `u-{sha1hex(normalisedUrl)}` of `artworkUrl`, else monogram key `m-{sha1hex(feedKey)}`, computed with 08's `ArtworkKeys` ([08 Artwork pipeline](08-ui-ux.md#artwork-pipeline)). Rewritten in the same statement whenever `artworkUrl` changes |
| `contentSha256` | Lowercase hex of the last parsed body (64 chars) |
| `status`, `initialFetch` | See the state diagram below; transitions are 03's |
| `latestEpisodeAt` | Max `sortDate` of the podcast's episodes, maintained by ingestion |
| `gone`, `needsCredentials`, `failureCount`, `lastErrorKind` | Error badges; "possibly dead" is derived (`gone = 0 AND failureCount ≥ 10 AND COALESCE(lastSuccessAt, subscribedAt) < now − 7 d`, thresholds owned by [03 Per-feed states](03-feeds-and-discovery.md#per-feed-states)) — no column |
| `autoDownloadEligibleAfter` | Written only by 07's planner: the D67 watermark: the later of `subscribedAt` and the moment the effective auto-download policy became enabled, written by 07's watermark pass ([07 No-backfill watermark](07-downloads.md#no-backfill-watermark)), cleared when disabled ([Auto-download candidates](#auto-download-candidates)) |
| `channelMetadataAt`; for `YOUTUBE_CHANNEL` rows also `bannerUrl`, `artworkUrl`/`artworkKey`, `descriptionHtml` | After the subscribe or import insert, written only by `PodcastDao.applyYouTubeChannelMetadata` (04); for `YOUTUBE_CHANNEL` rows Atom ingestion's `applyFeedMetadata` never touches them, nor `link`, `youtubeChannelId`, `youtubeVariants` ([04 Atom feed ingestion](04-youtube.md#atom-feed-ingestion)) |

```mermaid
stateDiagram-v2
  [*] --> PENDING_FIRST_FETCH: import or restore commit, initialFetch = 1
  [*] --> ACTIVE: subscribe from in-memory preview, initialFetch = 0
  PENDING_FIRST_FETCH --> PENDING_FIRST_FETCH: fetch failed, failureCount + 1
  PENDING_FIRST_FETCH --> ACTIVE: first successful ingest, initialFetch = 0
  ACTIVE --> [*]: unsubscribe deletes the row
  PENDING_FIRST_FETCH --> [*]: Remove in the import report
```

### podcast_url_alias

Every identity-normalised URL the podcast was known by: subscribe input, OPML URL, redirect sources, `new-feed-url` sources, merged podcasts.

```kotlin
@Entity(tableName = "podcast_url_alias", indices = [Index("podcastId")], foreignKeys = [/* podcastId CASCADE */])
data class PodcastUrlAliasEntity(
    @PrimaryKey val url: String,     // UrlNormalizer.forIdentity form; never equal to any podcast.feedKey
    val podcastId: Long,
    val reason: AliasReason,
    val addedAt: Long,
)
```

### credential

Basic-auth credentials and a user's Podcast Index key/secret, encrypted with an Android Keystore AES-256-GCM key (key alias and crypto: 03). Never exported, never in backups; the DB itself is never backed up.

```kotlin
@Entity(tableName = "credential", indices = [Index("origin")])
data class CredentialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val origin: String,          // "https://host[:port]" lowercase, default port omitted; or "podcastindex"
    val username: String,
    val secretCipher: ByteArray, // ciphertext + GCM tag
    val iv: ByteArray,           // 12 bytes
    val createdAt: Long,
)
```

`CredentialDao.observeAll(): Flow<List<CredentialEntity>>` (`SELECT * FROM credential`) feeds 03's in-memory `CredentialStore` map, so a row deleted by a cascade also leaves the map. A feed credential is deleted in the transaction that removes its last referencing podcast ([Unsubscribe and merge](#unsubscribe-and-merge)); `db-maintenance` sweeps any survivor (`origin <> 'podcastindex' AND id NOT IN (SELECT credentialId FROM podcast WHERE credentialId IS NOT NULL)`), so no secret outlives its feed ([N3](../PLAN.md#22-non-functional-requirements)). `CredentialStore` (03) must drop its in-memory copy on the same events.

### podcast_settings

Per-podcast overrides; `null` = inherit ([D20](../PLAN.md#3-key-decisions)). Resolution rules: [05 Effective settings resolution](05-groups-opml-backup.md#effective-settings-resolution). The row is deleted when every override is `null`.

```kotlin
data class ScopeOverrides(                                   // @Embedded in both settings tables
    val playbackSpeed: Float? = null, val skipSilence: Boolean? = null,
    val boostDb: Float? = null, val introSkipMs: Long? = null, val outroSkipMs: Long? = null,   // reserved v1.x (D65)
    val autoDownload: Boolean? = null, val autoDownloadKeepLatest: Int? = null,
    val autoDownloadNetwork: NetworkPolicy? = null, val autoDownloadRequireCharging: Boolean? = null,
    val deleteAfterPlayed: DeleteAfter? = null, val includeVideoInAutoDownload: Boolean? = null,
    val notifyNewEpisodes: Boolean? = null, val refreshIntervalMinutes: Int? = null,
)
@Entity(tableName = "podcast_settings", foreignKeys = [/* podcastId CASCADE */])
data class PodcastSettingsEntity(@PrimaryKey val podcastId: Long, @Embedded val o: ScopeOverrides)
```

### podcast_group

User-defined group ([D29](../PLAN.md#3-key-decisions)). All and Ungrouped are virtual `FeedSource`s, never rows.

```kotlin
@Entity(tableName = "podcast_group",
    indices = [Index("uuid", unique = true), Index("nameKey", unique = true), Index("sortOrder")])
data class PodcastGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,                    // random UUID, lowercase; never reused
    val name: String,                    // GroupNames-normalised, 1–40 code points (05 validates)
    val nameKey: String,                 // GroupNames key (05): NFC(normalized.lowercase(Locale.ROOT))
    val sortOrder: Int,                  // dense 0..n-1
    val colorArgb: Int? = null, val iconKey: String? = null,
    @ColumnInfo(defaultValue = "'MANUAL'") val kind: GroupKind = GroupKind.MANUAL,
    @ColumnInfo(defaultValue = "'NEWEST_FIRST'") val feedOrder: FeedOrder = FeedOrder.NEWEST_FIRST,
    @ColumnInfo(defaultValue = "'NEWEST_FIRST'") val playOrder: FeedOrder = FeedOrder.NEWEST_FIRST,
    @ColumnInfo(defaultValue = "0") val filterFlags: Int = 0,
    @ColumnInfo(defaultValue = "'ALL'") val mediaFilter: MediaFilter = MediaFilter.ALL,
    val hideOlderThanDays: Int? = null,
    @ColumnInfo(defaultValue = "1") val showAsTab: Boolean = true,
    val lastViewedAt: Long? = null,
    val createdAt: Long, val updatedAt: Long,
    val ruleJson: String? = null,        // reserved (smart groups); null in v1
)
```

### podcast_group_member

```kotlin
@Entity(tableName = "podcast_group_member", primaryKeys = ["groupId", "podcastId"], withoutRowId = true,
    indices = [Index("podcastId", "groupId")], foreignKeys = [/* groupId CASCADE, podcastId CASCADE */])
data class PodcastGroupMemberEntity(
    val groupId: Long, val podcastId: Long,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,   // optional manual order inside the group grid
    val addedAt: Long,
    @ColumnInfo(defaultValue = "'MANUAL'") val source: MemberSource = MemberSource.MANUAL,  // RULE reserved
)
```

### podcast_group_settings

```kotlin
@Entity(tableName = "podcast_group_settings", foreignKeys = [/* groupId → podcast_group CASCADE */])
data class PodcastGroupSettingsEntity(@PrimaryKey val groupId: Long, @Embedded val o: ScopeOverrides)
```

### episode

Feed-derived data only ([D15](../PLAN.md#3-key-decisions)). No user state, no positions, no download progress.

```kotlin
@Entity(tableName = "episode",
    indices = [Index("podcastId", "identityKey", unique = true), Index("podcastId", "sortDate"),
               Index("sortDate"), Index("firstSeenAt")],
    foreignKeys = [/* podcastId CASCADE */])
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long, val identityKey: String, val guid: String? = null,
    val title: String, val pubDate: Long? = null, val rawPubDate: String? = null,
    val sortDate: Long, val feedOrder: Int, val firstSeenAt: Long, val lastSeenAt: Long,
    @ColumnInfo(defaultValue = "1") val inFeed: Boolean = true,
    @ColumnInfo(defaultValue = "0") val isNew: Boolean = false,
    val enclosureUrl: String? = null, val enclosureType: String? = null, val enclosureLength: Long? = null,
    val externalMediaId: String? = null, @ColumnInfo(defaultValue = "0") val isVideo: Boolean = false,
    val durationMs: Long? = null,                          // feed or enrichment hint; 06 measures the truth
    val season: Int? = null, val seasonName: String? = null,
    val episodeNumber: String? = null,                     // decimal as plain string ("12", "12.5")
    val episodeDisplay: String? = null, val episodeType: EpisodeType? = null, val explicit: Boolean? = null,
    val imageUrl: String? = null, val artworkKey: String? = null, val link: String? = null,
    val chaptersUrl: String? = null, val chaptersType: String? = null,
    val contentHash: Long,                                 // first 8 bytes of SHA-256 over 03's normalised fields
    @ColumnInfo(defaultValue = "'AVAILABLE'") val availability: Availability = Availability.AVAILABLE,
    @ColumnInfo(defaultValue = "0") val isShort: Boolean = false,
    val snippet: String? = null,                           // ≤ 200 chars plain text; last (large)
)
```

| Invariant | Enforced by |
|---|---|
| `enclosureUrl IS NOT NULL OR externalMediaId IS NOT NULL` (enclosure-less blog items are not stored) | 03 ingestion, 05 stub insertion (lines without either are skipped) |
| `imageUrl IS NULL` ⇔ `artworkKey IS NULL`; `imageUrl` is null when equal to the podcast artwork | 03 ingestion |
| `sortDate = min(pubDate ?: firstSeenAt, firstSeenAt + 24 h)` ([D19](../PLAN.md#3-key-decisions)) | 03 ingestion |
| List order is `(sortDate, id)`. Rows inserted in one parse are inserted in **descending `feedOrder`**, so that among equal `sortDate`s the item listed first in the document gets the highest `id` (feeds list newest first); `id` therefore encodes the D19 tiebreak | 03 ingestion |
| `isNew = 1` only for items inserted by a non-initial refresh and not part of a back-catalogue dump ([D66](../PLAN.md#3-key-decisions), [03 isNew and back-catalogue guard](03-feeds-and-discovery.md#isnew-and-back-catalogue-guard)); never cleared by user actions or by 04's enrichment (a premiere promoted from `UPCOMING` keeps the `isNew` it was inserted with) | 03 |
| `inFeed = 0` only after a successful parse with ≥ 1 item that lacked the row; a partial document (RFC 5005 page, YouTube's 15-entry window) flips only rows inside its own date window ([03 Ingestion and diff](03-feeds-and-discovery.md#ingestion-and-diff)); stubs from restore start with `inFeed = 0` | 03, 05 |
| `title` non-empty (03 supplies a fallback such as the date) | 03 |

```mermaid
stateDiagram-v2
  [*] --> InFeed: ingestion inserts
  [*] --> Stub: restore inserts a stub, inFeed = 0
  Stub --> InFeed: a refresh matches its identityKey
  InFeed --> Absent: successful parse without the item, inFeed = 0
  Absent --> InFeed: item reappears
  Absent --> [*]: retention after 90 days, unless protected
  Stub --> [*]: retention after 90 days, unless protected
  InFeed --> [*]: unsubscribe cascade
```

### episode_description

Show notes, kept out of the hot `episode` table. Read only by episode detail, chapter extraction from descriptions (04/06) and nothing that lists.

```kotlin
@Entity(tableName = "episode_description", foreignKeys = [/* episodeId CASCADE */])
data class EpisodeDescriptionEntity(@PrimaryKey val episodeId: Long, val html: ByteArray)

object EpisodeDescriptionCodec {             // the only way to read or write `html`
    fun encode(text: String): ByteArray      // UTF-8 ≥ 512 bytes → [0x01] + raw DEFLATE (nowrap, level 6); else [0x00] + UTF-8
    fun decode(bytes: ByteArray): String
}
```

The column holds raw HTML from the feed (or plain text for YouTube and Atom); sanitising happens at display time ([D27](../PLAN.md#3-key-decisions), [03 Show notes](03-feeds-and-discovery.md#show-notes)). Compression shrinks the largest table to roughly a third (see [Expected size](#expected-size)); encoding runs on `Default` before the ingest transaction.

### episode_transcript

```kotlin
@Entity(tableName = "episode_transcript", primaryKeys = ["episodeId", "url"], foreignKeys = [/* episodeId CASCADE */])
data class EpisodeTranscriptEntity(
    val episodeId: Long, val url: String, val type: String, val language: String? = null, val rel: String? = null,
)
```

### episode_alt_enclosure

```kotlin
@Entity(tableName = "episode_alt_enclosure", primaryKeys = ["episodeId", "ordinal"], foreignKeys = [/* CASCADE */])
data class EpisodeAltEnclosureEntity(
    val episodeId: Long, val ordinal: Int, val type: String, val length: Long? = null, val bitrate: Long? = null,
    val height: Int? = null, val lang: String? = null, val title: String? = null, val rel: String? = null,
    val codecs: String? = null, @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
    val integrityType: String? = null, val integrityValue: String? = null,
    val sourcesJson: String,
)
```

### person

Polymorphic owner, therefore no FK. Rows are deleted explicitly with their owner (ingestion replace, [Unsubscribe and merge](#unsubscribe-and-merge), retention) and swept for orphans by `db-maintenance`.

```kotlin
@Entity(tableName = "person", indices = [Index("ownerType", "ownerId")])
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerType: OwnerType, val ownerId: Long, val name: String,
    @ColumnInfo(defaultValue = "'host'") val role: String = "host",
    @ColumnInfo(defaultValue = "'cast'") val grp: String = "cast",
    val imageUrl: String? = null, val href: String? = null,
)
```

### funding

```kotlin
@Entity(tableName = "funding", indices = [Index("ownerType", "ownerId")])
data class FundingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerType: OwnerType, val ownerId: Long, val url: String, val label: String? = null,
)
```

### chapter

One table for every chapter source; the playback rule "first non-empty source wins" is 06's ([06 Chapters](06-playback.md#chapters)).

```kotlin
@Entity(tableName = "chapter", primaryKeys = ["episodeId", "source", "ordinal"], foreignKeys = [/* CASCADE */])
data class ChapterEntity(
    val episodeId: Long, val source: ChapterSource, val ordinal: Int,
    val startMs: Long, val endMs: Long? = null, val title: String? = null,
    val imageUrl: String? = null, val linkUrl: String? = null,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,      // P2.0 toc:false
)
```

Writers replace all rows of one `(episodeId, source)` pair in one transaction (`DELETE … WHERE episodeId = ? AND source = ?` then insert).

### episode_state

Low-churn user state ([D15](../PLAN.md#3-key-decisions)). Rows are created lazily on the first state change.

```kotlin
@Entity(tableName = "episode_state", indices = [Index("playedAt")], foreignKeys = [/* episodeId CASCADE */])
data class EpisodeStateEntity(
    @PrimaryKey val episodeId: Long,
    val startedAt: Long? = null,           // set once when the position first becomes > 0; cleared on reset
    val playedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    val downloadDismissedAt: Long? = null, // tombstone: user deleted the download (07)
    val measuredDurationMs: Long? = null,  // written back by 06
    val updatedAt: Long,                   // last user-state change (backup merge)
)
```

"In progress" everywhere means `startedAt IS NOT NULL AND playedAt IS NULL`; this is the low-churn proxy for `episode_position.positionMs > 0` and keeps lists free of `episode_position`. 06 maintains it inside the position-save transaction ([User-state writes](#user-state-writes)).

### episode_position

High churn ([D41](../PLAN.md#3-key-decisions)): written every 5 s while playing. **Never joined by paged or list queries**; read only through `IN (:ids)` queries and by 06.

```kotlin
@Entity(tableName = "episode_position", foreignKeys = [/* episodeId CASCADE */])
data class EpisodePositionEntity(
    @PrimaryKey val episodeId: Long,
    val positionMs: Long,
    val durationMs: Long? = null,
    val positionSource: PositionSource,    // STREAM or DOWNLOAD (DAI hosts serve different bytes, risk T7)
    val updatedAt: Long,
)
```

### queue_entry

Up next ([D38](../PLAN.md#3-key-decisions)). Ordering rules in [Up next ordering](#up-next-ordering).

```kotlin
@Entity(tableName = "queue_entry", indices = [Index("episodeId", unique = true), Index("ordinal")],
    foreignKeys = [/* episodeId CASCADE */])
data class QueueEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long, val ordinal: Double, val addedAt: Long,
)
```

### play_session

Singleton (`id = 0`). No position column ([D41](../PLAN.md#3-key-decisions)); semantics in [06 Queue and play context](06-playback.md#queue-and-play-context).

```kotlin
@Entity(tableName = "play_session", indices = [Index("currentEpisodeId")],
    foreignKeys = [ForeignKey(EpisodeEntity::class, ["id"], ["currentEpisodeId"], onDelete = ForeignKey.SET_NULL)])
data class PlaySessionEntity(
    @PrimaryKey val id: Int = 0,
    val currentEpisodeId: Long? = null,
    val contextType: ContextType? = null,            // null = no context
    val contextId: Long? = null,                     // groupId or podcastId; no FK (polymorphic)
    @ColumnInfo(defaultValue = "'NEWEST_FIRST'") val contextOrder: FeedOrder = FeedOrder.NEWEST_FIRST,
    @ColumnInfo(defaultValue = "0") val contextFilterFlags: Int = 0,
    @ColumnInfo(defaultValue = "'ALL'") val contextMediaFilter: MediaFilter = MediaFilter.ALL,
    val contextMinSortDate: Long? = null,            // fixed when the context starts
    val contextAnchorEpisodeId: Long? = null,        // no FK: anchor may be deleted
    val contextAnchorSortDate: Long? = null,         // keyset needs (sortDate, id) even after deletion
    @ColumnInfo(defaultValue = "0") val generation: Long = 0,
    val updatedAt: Long,
)
```

Deleting a group or unsubscribing a podcast clears a context that points at it (`contextType = NULL`) in the same transaction ([Unsubscribe and merge](#unsubscribe-and-merge); group delete: 05).

### download

One row per episode with a download in any state. `downloadedBytes` is persisted only on state transitions ([D17](../PLAN.md#3-key-decisions)); no stream or CDN URL columns ([D50](../PLAN.md#3-key-decisions)). State semantics: [07 State machine](07-downloads.md#state-machine).

```kotlin
@Entity(tableName = "download", indices = [Index("state", "lane", "priority", "requestedAt")],
    foreignKeys = [/* episodeId CASCADE */])
data class DownloadEntity(
    @PrimaryKey val episodeId: Long,
    val lane: DownloadLane, val state: DownloadState,
    @ColumnInfo(defaultValue = "'NONE'") val waitReason: WaitReason = WaitReason.NONE,
    val priority: Int,                         // MANUAL 100, AUTO 0, "download next" 200
    val requestedAt: Long,
    val sourceKind: SourceKind, val sourceRef: String,   // enclosure URL as in the feed, or YouTube video ID
    val formatPref: String? = null, val resolvedItag: Int? = null,
    val rootId: String,                        // "ext:primary" | "ext:{volumeUuid}" | "int" (| "saf:…" v1.x)
    val tempPath: String? = null, val relativePath: String? = null, val finalUri: String? = null,
    val totalBytes: Long? = null, @ColumnInfo(defaultValue = "0") val downloadedBytes: Long = 0,
    val estimatedBytes: Long? = null,
    val etag: String? = null, val lastModified: String? = null, val mimeType: String? = null,
    val allowMetered: Boolean, @ColumnInfo(defaultValue = "0") val requireCharging: Boolean = false,
    @ColumnInfo(defaultValue = "0") val attempt: Int = 0,
    @ColumnInfo(defaultValue = "0") val integrityFailures: Int = 0,
    val nextAttemptAt: Long? = null, val lastError: DownloadError? = null, val lastHttpStatus: Int? = null,
    val lastStopReason: Int? = null, val completedAt: Long? = null, val runnerToken: String? = null,
)
```

Deleting an episode cascades its `download` row, but **not the file**: anything that deletes episodes (unsubscribe, retention) first asks 07 to delete files; retention never deletes episodes that have a `download` row.

### artwork

Pinned artwork metadata ([D42](../PLAN.md#3-key-decisions), [D57](../PLAN.md#3-key-decisions)); keys are deterministic, so `podcast.artworkKey`/`episode.artworkKey` reference it without FK and a row may be missing (nothing pinned yet). A key needs (re-)syncing when its row is missing, `localPath` is null, or `url` differs from the current source descriptor (08's rule).

```kotlin
@Entity(tableName = "artwork")
data class ArtworkEntity(
    @PrimaryKey val key: String,               // u-…, m-…, g-… (08)
    val url: String? = null,                   // source descriptor of the stored bytes (08): image URL, or
                                               // nd:monogram:v1:{initials}:{hue} / nd:mosaic:v1:{hash}; null = never synced
    val localPath: String? = null,             // relative to filesDir/artwork; null = not pinned
    val width: Int? = null, val height: Int? = null,
    val seedArgb: Int? = null, val avgArgb: Int? = null,   // M10 fills them
    @ColumnInfo(defaultValue = "0") val version: Int = 0,  // bumps when bytes change (memory-key busting)
    val fetchedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val pinCount: Int = 0, // cache of the reference count, see Artwork references
    val lastError: String? = null,
)
```

### import_session

```kotlin
@Entity(tableName = "import_session")
data class ImportSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long, val finishedAt: Long? = null,
    val sourceName: String? = null,            // OpenableColumns.DISPLAY_NAME
    val sourceFormat: ImportFormat, val state: ImportState,
    @ColumnInfo(defaultValue = "0") val recoveredBySalvage: Boolean = false,
    val payloadPath: String? = null,           // relative to cacheDir: "import/{id}.bin"
    val optionsJson: String? = null, val warningsJson: String? = null,
)
```

### import_item

```kotlin
@Entity(tableName = "import_item", primaryKeys = ["sessionId", "ordinal"],
    indices = [Index("sessionId", "status"), Index("podcastId")],
    foreignKeys = [/* sessionId → import_session CASCADE */,
        ForeignKey(PodcastEntity::class, ["id"], ["podcastId"], onDelete = ForeignKey.SET_NULL)])
data class ImportItemEntity(
    val sessionId: Long, val ordinal: Int,
    val title: String? = null, val originalUrl: String, val normalizedUrl: String? = null,
    val kind: ImportItemKind, val groupNamesJson: String,
    val selected: Boolean, val status: ImportItemStatus,
    val podcastId: Long? = null, val errorDetail: String? = null,
)
```

### Reserved tables

Not created in v1 (each arrives with a migration): `podcast_group_exclusion` (smart groups), `episode_fts` (FTS4 search, M15; indexes `title` and `snippet` because `episode_description.html` is compressed — see [Open questions](#open-questions)), `sponsor_segment` (SponsorBlock, M14).

---

## Identity keys

Serves R1.7, R1.8, R3.4, N1 ([D18](../PLAN.md#3-key-decisions)). Delivered in M1; backup use in M3. 03 owns the computation (`EpisodeKeys`, `UrlNormalizer`, `PodcastGuid` in `:feeds`); this section owns stored formats, versioning and the database-side behaviour.

### Episode identityKey

Stored as `TEXT` in `episode.identityKey`, unique per podcast. Grammar: `key := [version] kind ":" payload`, where `version` is absent for version 1 and a decimal number (`2`, `3`, …) for later versions.

| Kind (v1) | Payload | Example |
|---|---|---|
| `g` | `guid.trim()`, verbatim, case-sensitive | `g:yt:video:3iRUwVzRDZQ`, `g:https://example.com/?p=123` |
| `u` | `UrlNormalizer.forIdentity(primaryEnclosureUrl)` | `u:` + normalised URL |
| `t` | lowercase hex SHA-1 of `title.trim().lowercase(Locale.ROOT)`, then `"\|"`, then `pubDate.truncatedTo(DAYS).toString()`, concatenated | `t:3f2a…` (40 hex) |
| `l` | lowercase hex SHA-1 of `link.trim()` | `l:9c1b…` |
| `h` | lowercase hex SHA-1 of `title.orEmpty() + description.orEmpty().take(500)` | `h:07de…` |

YouTube episodes always take the `g` branch (`guid = yt:video:{videoId}`). A GUID repeated inside one document falls back to `u` for the second occurrence (03).

### Key versions

- `EpisodeKeys.VERSION = 1`. Backups write `kv` per episode line ([D33](../PLAN.md#3-key-decisions)); in the DB the version is self-describing through the optional numeric prefix, so no column and no metadata table is needed.
- Mixed versions in one database are legal. The database is **never bulk re-keyed by a Room migration** (`:core:database` cannot call `:feeds`). Instead:
  1. 03's diff matches each parsed item against the stored keys using `EpisodeKeys.candidates(item)` — the current-version key first, then the keys of every older supported version — before falling back to enclosure and title+day matching. A match on an older-version key rewrites the row's key to the current version in place.
  2. Rows that never reappear in the feed keep their old key; that is harmless because restore matching (05) computes `EpisodeKeys.keyFor(localEpisode, kv)` for the backup line's `kv`.
  3. `EpisodeKeys` keeps the code of every released version forever; `versionOf(key)` parses the prefix.
- Changing `UrlNormalizer.forIdentity` output is a key-version change for `u` keys (and a `feedKey` change, below).

### Uniqueness and in-place re-keying

- `UNIQUE(podcastId, identityKey)`: inserting a second row with an existing key fails with `SQLITE_CONSTRAINT_UNIQUE` and aborts the feed's whole transaction (the feed is recorded as a parse failure and retried next refresh). 03 must therefore deduplicate keys within one parse before inserting.
- Fallback matches (GUID rewritten by a host, older key version) update `identityKey` and `guid` **in place** with `IngestDao.rekey(id, key, guid)`. The row ID and all user state (`episode_state`, `episode_position`, `download`, `queue_entry`, chapters) are preserved. The match order guarantees the target key is unused in that podcast; if it is not, the unique index aborts the transaction instead of silently merging.
- Ingestion never deletes an episode, so a refresh can never delete user state ([N1](../PLAN.md#22-non-functional-requirements)).

### Podcast feedKey and aliases

- `podcast.feedKey = UrlNormalizer.forIdentity(feedUrl)`, `UNIQUE`. It is the cross-device podcast key used by backups, OPML dedupe and "Already subscribed".
- `podcast_url_alias.url` holds identity-normalised URLs (same function), so `feedKey` and aliases compare directly. Lookup: [Restore matching](#restore-matching) (`feedKey IN … UNION alias`).
- When 03 accepts a move (301/308 chain, validated `new-feed-url`) it updates `feedUrl` and `feedKey` in one transaction and inserts the old `feedKey` as an alias (`REDIRECT` / `NEW_FEED_URL`). If the new `feedKey` exists as an alias of the same podcast, that alias row is deleted first (invariant: an alias never equals any `feedKey`). If it equals another podcast's `feedKey`, the two podcasts merge ([Unsubscribe and merge](#unsubscribe-and-merge)).
- 03 recomputes `feedKey` from `feedUrl` after every successful refresh. A difference (normaliser version change) is applied like a move with reason `RENORMALISED`. No Room migration ever recomputes keys.

### podcastGuid

`podcastGuid` holds the lowercase 8-4-4-4-12 form. `podcastGuidDerived = 1` marks a locally derived UUIDv5 ([podcast:guid spec](https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/guid.md)); derived values are never exported as real and never used for dedupe. The index is **not unique**: an ad-free premium feed may legitimately share the public feed's `podcast:guid`, so a match only prompts "Already subscribed?" (03).

### Group uuid and nameKey

`uuid` is a random (version 4) UUID in lowercase canonical form, generated by 05 (`UUID.randomUUID().toString()`), `TEXT UNIQUE`, never reused: notification channel IDs `new_episodes_{groupUuid}`, artwork keys `g-{groupUuid}` and backups depend on it. `nameKey` is computed only by 05's `GroupNames` ([05 Group model and lifecycle](05-groups-opml-backup.md#group-model-and-lifecycle)), `UNIQUE`; a collision inside a write transaction surfaces as `SQLITE_CONSTRAINT_UNIQUE`, which `GroupRepository` maps to `GroupError.NameTaken` (05). Raw SQL never computes `nameKey` (SQLite's `lower()` is ASCII-only).

### Local row IDs

Row IDs are device-local. They appear in media URIs (`neutrodyne://episode/{id}`), media IDs, content and deep-link URIs, notification extras and download file names (`[p<id>]`, `[e<id>]`), but **never** in backups, OPML or exports. After a restore on a new device every ID differs; restored download rows are not carried over (07 re-downloads on request). `AUTOINCREMENT` guarantees an ID is never reused after deletion (retention, unsubscribe).

---

## Indices

Serves R2.9, N5. Delivered in M1 (all indices exist in version 1); plans verified in M2.

| Table | Index (Room name) | Serves |
|---|---|---|
| `podcast` | PK `id`; `index_podcast_feedKey` (unique) | Subscribe/import/restore dedupe |
| | `index_podcast_nextRefreshAt` | Due selection |
| | `index_podcast_podcastGuid` | Dedupe and restore by real GUID |
| | `index_podcast_credentialId` | FK child index (credential delete) |
| `podcast_url_alias` | PK `url`; `index_podcast_url_alias_podcastId` | Alias lookup; FK |
| `credential` | `index_credential_origin` | Same-origin lookup |
| `podcast_group` | `uuid` (unique), `nameKey` (unique), `sortOrder` | Restore/OPML, name validation, ordered list |
| `podcast_group_member` | PK `(groupId, podcastId)` WITHOUT ROWID; `index_podcast_group_member_podcastId_groupId` | Group feed `IN (subquery)`; Ungrouped `NOT EXISTS`; "groups of podcast" |
| `episode` | `index_episode_podcastId_identityKey` (unique) | Ingest/restore matching; FK |
| | `index_episode_podcastId_sortDate` | Podcast feed, group feeds, counts, newest-per-podcast subqueries |
| | `index_episode_sortDate` | All feed ordered scan (the index ends with the rowid `id`, so `ORDER BY sortDate DESC, id DESC` needs no sort) |
| | `index_episode_firstSeenAt` | "New since" queries, notification digests |
| `episode_state` | PK; `index_episode_state_playedAt` | LEFT JOINs; history; retention |
| `queue_entry` | `episodeId` (unique), `ordinal` | Up next order |
| `play_session` | `currentEpisodeId` | FK child index |
| `download` | PK; `index_download_state_lane_priority_requestedAt` | Claim; quota; completed lists |
| `person`, `funding` | `(ownerType, ownerId)` | Owner lookup and deletes |
| `import_item` | PK; `(sessionId, status)`; `podcastId` | Progress counts; FK |
| other children | PKs starting with `episodeId` | FK cascades |

`EXPLAIN QUERY PLAN` expectations (asserted in [Testing](#testing); detail strings differ between SQLite versions, so assertions use tolerant regexes such as `SCAN (TABLE )?episode( AS e)?`):

| Query | Expected plan | Must not appear |
|---|---|---|
| Feed page, `All` | `SCAN e USING INDEX index_episode_sortDate` (outer), `SEARCH p USING INTEGER PRIMARY KEY` | `USE TEMP B-TREE FOR ORDER BY` |
| Feed page, `Group` | `SEARCH m USING PRIMARY KEY (groupId=?)` feeding `SEARCH e USING INDEX index_episode_podcastId_sortDate (podcastId=?)`; a temp B-tree sort of the group's rows is acceptable | `SCAN e` without an index |
| Feed page, `Podcast` | `SEARCH e USING INDEX index_episode_podcastId_sortDate (podcastId=?)` | Temp B-tree |
| Feed page, `Ungrouped` | Either per-podcast search or `SCAN e USING INDEX index_episode_sortDate` with a correlated `SEARCH m USING COVERING INDEX index_podcast_group_member_podcastId_groupId` | `SCAN e` without an index |
| Context tail | Same as the feed page, plus a range on the row value | `SCAN e` without an index |
| Group counts | `SEARCH e USING INDEX index_episode_podcastId_sortDate (podcastId=? AND sortDate>?)` per member | `SCAN e` |
| Download claim | `SEARCH download USING INDEX index_download_state_lane_priority_requestedAt (state=? AND lane=?)` | `SCAN download` |

`PRAGMA optimize` keeps statistics current so the planner can choose between "scan `sortDate`" and "IN-list + sort": `optimize=0x10002` on every open, plain `optimize` daily in `db-maintenance` and after any migration that adds an index ([SQLite](https://www.sqlite.org/pragma.html#pragma_optimize)).

---

## Key queries

Serves R2.3, R2.5, R2.6, R2.8, R2.9, R4.4, R4.5, R4.8, R1.3, R1.7. Each subsection names the DAO function, the milestone and the document that owns the semantics. `VISIBLE` below is this fragment (v1 implementation of PO-9 defaults; which flags hide an episode is owned by [04 Content flags and filtering](04-youtube.md#content-flags-and-filtering), and changing it is a code change, not a migration):

```sql
NOT (e.isShort = 1 AND (p.youtubeVariants & 2) = 0)
AND e.availability NOT IN ('UPCOMING', 'LIVE', 'MEMBERS_ONLY')
```

### Feed pages

`FeedDao.page(query: RoomRawQuery): PagingSource<Int, EpisodeRowProjection>` built by `FeedQueryBuilder.page(source, filters, order)` ([D30](../PLAN.md#3-key-decisions)). Contract and paging configuration: [05 Group feeds](05-groups-opml-backup.md#group-feeds). Delivered: All and Podcast in M1, everything in M2.

```kotlin
object FeedQueryBuilder {
    const val VISIBLE = "NOT (e.isShort = 1 AND (p.youtubeVariants & 2) = 0) " +
        "AND e.availability NOT IN ('UPCOMING', 'LIVE', 'MEMBERS_ONLY')"

    fun page(source: FeedSource, f: FeedFilters, order: FeedOrder): RoomRawQuery {
        val where = mutableListOf(VISIBLE); val args = mutableListOf<Long>()
        val from = if (source == FeedSource.All) {
            where += "p.id = e.podcastId"; where += "p.includeInAll = 1"
            "episode e CROSS JOIN podcast p"                 // forces the ordered scan of index_episode_sortDate
        } else "episode e JOIN podcast p ON p.id = e.podcastId"
        when (source) {
            FeedSource.All -> Unit
            FeedSource.Ungrouped -> where += "NOT EXISTS (SELECT 1 FROM podcast_group_member m WHERE m.podcastId = e.podcastId)"
            is FeedSource.Group -> { where += "e.podcastId IN (SELECT m.podcastId FROM podcast_group_member m WHERE m.groupId = ?)"; args += source.groupId }
            is FeedSource.Podcast -> { where += "e.podcastId = ?"; args += source.podcastId }
        }
        f.minSortDate?.let { where += "e.sortDate >= ?"; args += it }
        if (f.unplayedOnly) where += "s.playedAt IS NULL"
        if (f.inProgressOnly) where += "s.startedAt IS NOT NULL AND s.playedAt IS NULL"
        if (f.downloadedOnly) where += "d.state = 'COMPLETED'"
        when (f.media) { MediaFilter.AUDIO -> where += "e.isVideo = 0"; MediaFilter.VIDEO -> where += "e.isVideo = 1"; MediaFilter.ALL -> Unit }
        val dir = if (order == FeedOrder.NEWEST_FIRST) "DESC" else "ASC"
        val sql = "SELECT $ROW_COLUMNS FROM $from $ROW_JOINS WHERE ${where.joinToString(" AND ")} " +
            "ORDER BY e.sortDate $dir, e.id $dir"
        return RoomRawQuery(sql) { st -> args.forEachIndexed { i, v -> st.bindLong(i + 1, v) } }
    }
}
```

```sql
-- ROW_COLUMNS
e.id, e.podcastId, e.title, e.sortDate, e.pubDate,
COALESCE(s.measuredDurationMs, e.durationMs) AS durationMs,
e.isVideo, e.isShort, e.availability, e.episodeType, e.episodeDisplay, e.externalMediaId, e.isNew, e.firstSeenAt,
COALESCE(p.customTitle, p.title) AS podcastTitle, p.sourceType,
COALESCE(e.artworkKey, p.artworkKey) AS artworkKey, COALESCE(e.imageUrl, p.artworkUrl) AS artworkUrl,
COALESCE(a.version, 0) AS artworkVersion, a.avgArgb AS artworkAvgArgb,
s.playedAt, s.startedAt, COALESCE(s.isFavorite, 0) AS isFavorite, d.state AS downloadState
-- ROW_JOINS
LEFT JOIN episode_state s ON s.episodeId = e.id
LEFT JOIN download d ON d.episodeId = e.id
LEFT JOIN artwork a ON a.key = COALESCE(e.artworkKey, p.artworkKey)
```

- `includeInAll` applies to the All feed only; group, podcast and Ungrouped feeds ignore it.
- Room's `LimitOffsetPagingSource` runs `SELECT COUNT(*) FROM (<sql>)` and `SELECT * FROM (<sql>) LIMIT ? OFFSET ?`; EXPLAIN tests run on these wrapped forms ([room3-paging source](https://github.com/androidx/androidx/blob/androidx-main/room3/room3-paging/src/commonMain/kotlin/androidx/room3/paging/LimitOffsetPagingSource.kt)).
- The deterministic `(sortDate, id)` order makes pages stable across boundaries ([R2.3](../PLAN.md#21-functional-requirements)). Unverified in our setup: SQLite honours `CROSS JOIN` as a join-order hint (query-planner documentation, not re-checked); the EXPLAIN test is authoritative.
- `EpisodeRow` (`:core:model`, fields defined here, rendered by 08) is the mapped projection:

```kotlin
data class EpisodeRow(
    val id: Long, val podcastId: Long, val title: String, val podcastTitle: String,
    val sortDate: Long, val pubDate: Long?, val durationMs: Long?,
    val isVideo: Boolean, val isShort: Boolean, val availability: Availability,
    val episodeType: EpisodeType?, val sourceType: SourceType, val externalMediaId: String?,
    val isNew: Boolean, val firstSeenAt: Long,          // "new since last visit" = isNew && firstSeenAt > lastViewedAt
    val artwork: ArtworkRef, val artworkAvgArgb: Int?,  // ArtworkRef(key, url, version)
    val playedAt: Long?, val startedAt: Long?, val isFavorite: Boolean,
    val downloadState: DownloadState?,
)
```

### Fallback generated queries

Used only if spike S2 shows that Room 3 cannot return a `PagingSource` from `@RawQuery` ([D30](../PLAN.md#3-key-decisions)). Eight compile-time-checked functions, `{all, ungrouped, group, podcast} × {NewestFirst, OldestFirst}`, with filters as bound flags:

```kotlin
@Query("SELECT $ROW_COLUMNS FROM episode e JOIN podcast p ON p.id = e.podcastId $ROW_JOINS " +
    "WHERE e.podcastId IN (SELECT m.podcastId FROM podcast_group_member m WHERE m.groupId = :groupId) " +
    "AND ${FeedQueryBuilder.VISIBLE} AND e.sortDate >= :minSortDate " +
    "AND (:unplayedOnly = 0 OR s.playedAt IS NULL) AND (:downloadedOnly = 0 OR d.state = 'COMPLETED') " +
    "AND (:inProgressOnly = 0 OR (s.startedAt IS NOT NULL AND s.playedAt IS NULL)) " +
    "AND (:media = 'ALL' OR (:media = 'AUDIO' AND e.isVideo = 0) OR (:media = 'VIDEO' AND e.isVideo = 1)) " +
    "ORDER BY e.sortDate DESC, e.id DESC")
fun groupNewestFirst(groupId: Long, minSortDate: Long, unplayedOnly: Boolean, downloadedOnly: Boolean,
    inProgressOnly: Boolean, media: MediaFilter): PagingSource<Int, EpisodeRowProjection>
```

`minSortDate` is bound as `0` when absent. The `FeedRepository` implementation switches between builder and fallback behind one function, so callers do not change. If the All feed misses its R2.9 budget with LIMIT/OFFSET, only All switches to a keyset `PagingSource` keyed by `(sortDate, id)` (risk [T6](../PLAN.md#8-risks-and-mitigations)).

### Feed counts

`FeedDao.observeGroupCounts(sinceMs, nowMs)` backs `FeedRepository.observeGroupCounts(sinceMs)` ([R2.8](../PLAN.md#21-functional-requirements)); window and "new" semantics: [05 Group feeds](05-groups-opml-backup.md#group-feeds). Delivered in M2. Groups without counted episodes return no row (the repository fills zeros). Counted = `VISIBLE` **and** `availability = 'AVAILABLE'` (greyed, unplayable YouTube items never inflate badges) in both flavors; YouTube episodes count in `play` too (04's answer: opening one in YouTube marks it played).

```sql
SELECT g.id AS groupId,
       SUM(CASE WHEN s.playedAt IS NULL THEN 1 ELSE 0 END) AS unplayed,
       SUM(CASE WHEN s.playedAt IS NULL AND e.isNew = 1
                 AND e.firstSeenAt > COALESCE(g.lastViewedAt, g.createdAt) THEN 1 ELSE 0 END) AS newSinceVisit
FROM podcast_group g
JOIN podcast_group_member m ON m.groupId = g.id
JOIN podcast p ON p.id = m.podcastId
JOIN episode e ON e.podcastId = m.podcastId
LEFT JOIN episode_state s ON s.episodeId = e.id
WHERE e.sortDate >= MAX(:sinceMs, CASE WHEN g.hideOlderThanDays IS NULL THEN 0
                                       ELSE :nowMs - g.hideOlderThanDays * 86400000 END)
  AND <VISIBLE> AND e.availability = 'AVAILABLE'
GROUP BY g.id
```

All and Ungrouped are computed separately (an episode in two groups is counted once in All) and back `FeedRepository.observeVirtualCounts(sinceMs)`, with `:lastViewedAt` from `device_settings` (05); for these two, 05 passes as `:sinceMs` the larger of the window start and the feed's `hide older than` bound:

```sql
-- FeedDao.observeAllCounts(sinceMs, lastViewedAt);
-- observeUngroupedCounts: replace "p.includeInAll = 1" with the Ungrouped NOT EXISTS membership predicate
SELECT SUM(CASE WHEN s.playedAt IS NULL THEN 1 ELSE 0 END) AS unplayed,
       SUM(CASE WHEN s.playedAt IS NULL AND e.isNew = 1 AND e.firstSeenAt > :lastViewedAt THEN 1 ELSE 0 END) AS newSinceVisit
FROM episode e CROSS JOIN podcast p LEFT JOIN episode_state s ON s.episodeId = e.id
WHERE p.id = e.podcastId AND p.includeInAll = 1 AND e.sortDate >= :sinceMs
  AND <VISIBLE> AND e.availability = 'AVAILABLE'
```

`FeedDao.countUnplayed(query)` (M2) backs 05's `countUnplayed(source, sortDateBefore)` for the "Mark all as played" confirmation: `SELECT COUNT(*) FROM episode e JOIN podcast p … LEFT JOIN episode_state s … WHERE <source predicate> AND <VISIBLE> AND s.playedAt IS NULL [AND e.sortDate < :before]`, built by `FeedQueryBuilder.countUnplayed(source, before)` with the same source predicates as [Feed pages](#feed-pages) (one-shot, not observed). Its predicate set must equal the bulk mark-played statements in [User-state writes](#user-state-writes), so the confirmed number is the number marked.

Budget: all counts for 20 groups at the N5 scale in ≤ 50 ms (recorded, not gating). If missed, denormalise a per-podcast unplayed count maintained by the played-state and ingest transactions (a migration and a PLAN note).

### Library tiles and mosaics

`PodcastDao.observeLibraryTiles(sinceMs, groupId: Long?)` (owner 03 `PodcastRepository`, 08 renders; M1, group filter in M2). `sinceMs` is 05's 30-day counts window. `lastSuccessAt` and `failureCount` let the mapper derive 03's "possibly dead" badge. Sorting by title uses `java.text.Collator` in Kotlin (locale-aware), so SQL returns unsorted rows.

```sql
SELECT p.id, COALESCE(p.customTitle, p.title) AS title, p.sourceType, p.status,
       p.artworkKey, p.artworkUrl, COALESCE(a.version, 0) AS artworkVersion, a.avgArgb AS artworkAvgArgb,
       p.gone, p.needsCredentials, p.failureCount, p.lastErrorKind, p.lastSuccessAt, p.latestEpisodeAt, p.subscribedAt,
       (SELECT COUNT(*) FROM episode e LEFT JOIN episode_state s ON s.episodeId = e.id
         WHERE e.podcastId = p.id AND s.playedAt IS NULL AND e.sortDate >= :sinceMs
           AND <VISIBLE> AND e.availability = 'AVAILABLE') AS unplayedCount
FROM podcast p
LEFT JOIN artwork a ON a.key = p.artworkKey
WHERE :groupId IS NULL OR p.id IN (SELECT m.podcastId FROM podcast_group_member m WHERE m.groupId = :groupId)
```

Group mosaics (`GroupDao.observeMosaics()`, M2; four newest-active members per group, [R5.6](../PLAN.md#21-functional-requirements)):

```sql
SELECT m.groupId, p.id AS podcastId, p.artworkKey, p.artworkUrl, COALESCE(a.version, 0) AS artworkVersion,
       a.avgArgb AS artworkAvgArgb
FROM podcast_group_member m
JOIN podcast p ON p.id = m.podcastId
LEFT JOIN artwork a ON a.key = p.artworkKey
WHERE p.id IN (SELECT m2.podcastId FROM podcast_group_member m2 JOIN podcast p2 ON p2.id = m2.podcastId
               WHERE m2.groupId = m.groupId ORDER BY p2.latestEpisodeAt DESC, p2.id DESC LIMIT 4)
ORDER BY m.groupId, p.latestEpisodeAt DESC, p.id DESC
```

### Play context

Rules (which items, start item, exclusions): [05 Playing a group](05-groups-opml-backup.md#playing-a-group); consumption: [06 Queue and play context](06-playback.md#queue-and-play-context). Delivered in M4. `FeedQueryBuilder.contextTail(scope, filters, order, anchor, k, youtubePlayable)` reuses the feed-page source predicates; `ContextType` maps to `GROUP → Group`, `PODCAST → Podcast`, `ALL → All`, `UNGROUPED → Ungrouped`, `DOWNLOADS` → no source predicate plus `d.state = 'COMPLETED'` (all podcasts, ignoring `includeInAll`), `EXTERNAL` → no tail. The filters come from `contextFilterFlags`, `contextMediaFilter` and `contextMinSortDate`.

```sql
-- FeedDao.observeContext(q): Flow<List<ContextItem>>; NEWEST_FIRST shown, OLDEST_FIRST flips < and DESC
SELECT e.id, e.sortDate, e.podcastId
FROM episode e JOIN podcast p ON p.id = e.podcastId
LEFT JOIN episode_state s ON s.episodeId = e.id
LEFT JOIN download d ON d.episodeId = e.id
WHERE <source predicate> AND <filters> AND <VISIBLE>
  AND e.availability = 'AVAILABLE'
  AND s.playedAt IS NULL
  AND NOT EXISTS (SELECT 1 FROM queue_entry q WHERE q.episodeId = e.id)
  AND (:youtubePlayable = 1 OR p.sourceType != 'YOUTUBE_CHANNEL')
  AND e.sortDate >= :minSortDate
  AND (e.sortDate, e.id) < (:anchorSortDate, :anchorId)       -- omitted when the anchor is null
ORDER BY e.sortDate DESC, e.id DESC
LIMIT :k
```

- `:anchorSortDate`/`:anchorId` come from `play_session.contextAnchorSortDate`/`contextAnchorEpisodeId`, so the tail works even if the anchor row was deleted. The anchor is excluded by the strict comparison. **Null anchor** (05 start rule 2: Up next head is playing) = "from the beginning of the order": `FeedQueryBuilder` omits the row-value predicate. The current item is not excluded in SQL: 06 queries `k + 1` rows and drops the current one ([06 Queue and play context](06-playback.md#queue-and-play-context)).
- `:minSortDate` is bound as `0` when `contextMinSortDate` is null (all `sortDate`s are after 1990, 03).
- `youtubePlayable` = `YouTubeCapabilities.inAppPlayback` (false in `play`, [R3.7](../PLAN.md#21-functional-requirements)).
- **Start item** ("Play group" without a chosen episode): the null-anchor form with `LIMIT 1`. For `GROUP` with `OLDEST_FIRST` and `boundStartBySubscription`, the first attempt adds `AND e.sortDate >= p.subscribedAt` and a second attempt runs without it (05 start rule 4).
- **Auto browse lists** (06, M5): `FeedDao.contextList(query: RoomRawQuery)` is the same builder output with `LIMIT :limit OFFSET :offset` and the [Feed pages](#feed-pages) row columns, one-shot (not observed); completed downloads use the `DOWNLOADS` source; podcasts by title use `observeLibraryTiles`' columns as a one-shot `PodcastDao.listForBrowse()` sorted in Kotlin; Assistant search: `EpisodeDao.searchTitles(pattern, limit)` = `… WHERE (e.title LIKE :pattern ESCAPE '\' OR p.title LIKE :pattern ESCAPE '\' OR p.customTitle LIKE :pattern ESCAPE '\') AND <VISIBLE> ORDER BY e.sortDate DESC, e.id DESC LIMIT :limit` with `%`/`_`/`\` in the user text escaped (a full scan of `episode`, acceptable for an explicit voice search; FTS arrives in M15).

### Media lookup

`EpisodeDao.mediaInfo(ids): List<MediaLookupRow>` (one-shot; 06's loader-thread miss path) and `EpisodeDao.observeMediaInfo(ids): Flow<List<MediaLookupRow>>` (06's projector; observes `episode`, `podcast`, `episode_state`, `download`, `artwork`, `episode_alt_enclosure` — low churn only, never `episode_position`). Delivered in M4; the alternate-enclosure columns in M5. 07's `EpisodeDao.downloadSources(ids)` selects the same columns minus the artwork and state columns.

```sql
SELECT e.id, e.podcastId, e.title, e.enclosureUrl, e.enclosureType, e.enclosureLength, e.externalMediaId,
       e.isVideo, e.pubDate, e.availability, e.chaptersUrl, e.chaptersType,
       COALESCE(s.measuredDurationMs, e.durationMs) AS durationMs, s.playedAt, d.state AS downloadState,
       COALESCE(p.customTitle, p.title) AS podcastTitle, p.author, p.sourceType, p.credentialId,
       COALESCE(e.artworkKey, p.artworkKey) AS artworkKey, COALESCE(a.version, 0) AS artworkVersion,
       p.artworkKey AS podcastArtworkKey, COALESCE(pa.version, 0) AS podcastArtworkVersion,
       alt.type AS audioAlternateType, alt.length AS audioAlternateLength, alt.sourcesJson AS audioAlternateSourcesJson
FROM episode e JOIN podcast p ON p.id = e.podcastId
LEFT JOIN episode_state s ON s.episodeId = e.id
LEFT JOIN download d ON d.episodeId = e.id
LEFT JOIN artwork a ON a.key = COALESCE(e.artworkKey, p.artworkKey)
LEFT JOIN artwork pa ON pa.key = p.artworkKey
LEFT JOIN episode_alt_enclosure alt ON alt.episodeId = e.id AND alt.ordinal =
     (SELECT x.ordinal FROM episode_alt_enclosure x WHERE x.episodeId = e.id AND x.type LIKE 'audio/%'
      ORDER BY x.isDefault DESC, x.ordinal LIMIT 1)
WHERE e.id IN (:ids)
```

The mapper picks the first `http(s)` URI from `audioAlternateSourcesJson` (JSON is never read in SQL); no such URI → no alternate.

### Up next ordering

`QueueDao` (M4, semantics 06). `ordinal` is a `REAL`: reordering writes one row.

| Operation | SQL / rule |
|---|---|
| Observe | `SELECT q.id AS entryId, q.ordinal, q.episodeId, <row columns as in Feed pages> FROM queue_entry q JOIN episode e ON e.id = q.episodeId JOIN podcast p ON p.id = e.podcastId <ROW_JOINS> ORDER BY q.ordinal, q.id` |
| Add last | `INSERT OR IGNORE INTO queue_entry(episodeId, ordinal, addedAt) VALUES (:id, COALESCE((SELECT MAX(ordinal) FROM queue_entry), 0) + 1, :now)` |
| Add next (front) | Same with `COALESCE((SELECT MIN(ordinal) FROM queue_entry), 1) - 1` |
| Move between neighbours `a < b` | `ordinal = (a + b) / 2`; at the ends `first − 1` / `last + 1` |
| Renormalise | When `b − a < 1e-9`: in one write transaction read IDs ordered and set `ordinal = index + 1.0` |
| Remove | `DELETE FROM queue_entry WHERE episodeId IN (:ids)` (also part of every mark-played path, below) |
| Clear | `DELETE FROM queue_entry` |

Up next rows show positions through [Live row state](#live-row-state), never by joining `episode_position`.

### Live row state

`EpisodeLiveStateSource` (contract: [08 Live row state](08-ui-ux.md#live-row-state)) combines these observed `IN` queries for the visible IDs (≤ 200 per call; callers chunk) with in-memory sources from 06 and 07. Delivered: state in M2, positions in M4, downloads in M6.

```sql
-- PositionDao.observeFor(ids)
SELECT episodeId, positionMs, durationMs, positionSource, updatedAt FROM episode_position WHERE episodeId IN (:ids)
-- DownloadDao.observeLiveFor(ids)
SELECT episodeId, state, waitReason, downloadedBytes, totalBytes, estimatedBytes, nextAttemptAt, lastError
FROM download WHERE episodeId IN (:ids)
-- EpisodeStateDao.observeFor(ids)
SELECT episodeId, playedAt, startedAt, isFavorite, measuredDurationMs FROM episode_state WHERE episodeId IN (:ids)
```

### User-state writes

Column-scoped statements ([DAO rules](#dao-rules)); semantics owned by 06 (played, positions), 03/08 (favourite), 07 (tombstone), 05 (bulk import actions).

```kotlin
@Dao interface PositionDao {
    @Query("INSERT OR IGNORE INTO episode_position(episodeId, positionMs, durationMs, positionSource, updatedAt) " +
           "VALUES (:id, :pos, :dur, :src, :now)")
    suspend fun insertIfAbsent(id: Long, pos: Long, dur: Long?, src: PositionSource, now: Long)

    /** D41 guard: never replaces a non-zero position with 0; 06's played-after-start guard:
     *  no write if the episode was marked played at or after this playback began (pinStartedAt). */
    @Query("UPDATE episode_position SET positionMs = :pos, durationMs = COALESCE(:dur, durationMs), " +
           "positionSource = :src, updatedAt = :now WHERE episodeId = :id AND (:pos > 0 OR positionMs = 0) " +
           "AND NOT EXISTS (SELECT 1 FROM episode_state s WHERE s.episodeId = :id AND s.playedAt >= :pinStartedAt)")
    suspend fun updateGuarded(id: Long, pos: Long, dur: Long?, src: PositionSource, now: Long, pinStartedAt: Long): Int

    /** Only for explicit reset and mark-played (06). */
    @Query("UPDATE episode_position SET positionMs = 0, updatedAt = :now WHERE episodeId IN (:ids) AND positionMs <> 0")
    suspend fun reset(ids: List<Long>, now: Long): Int
}
// 06 PositionWriter, one write transaction per save event:
// db.withWriteTransaction { insertIfAbsent(…); updateGuarded(…); if (pos > 0) { state.ensure(id, now); state.markStarted(id, now) } }

@Dao interface EpisodeStateDao {
    @Query("INSERT OR IGNORE INTO episode_state(episodeId, playCount, isFavorite, updatedAt) VALUES (:id, 0, 0, :now)")
    suspend fun ensure(id: Long, now: Long)
    @Query("INSERT OR IGNORE INTO episode_state(episodeId, playCount, isFavorite, updatedAt) " +
           "SELECT id, 0, 0, :now FROM episode WHERE id IN (:ids)")
    suspend fun ensureAll(ids: List<Long>, now: Long)
    @Query("UPDATE episode_state SET startedAt = :now, updatedAt = :now " +
           "WHERE episodeId = :id AND startedAt IS NULL AND playedAt IS NULL")
    suspend fun markStarted(id: Long, now: Long): Int                 // matches 0 rows after the first time
    @Query("UPDATE episode_state SET playedAt = :now, playCount = playCount + 1, startedAt = NULL, updatedAt = :now " +
           "WHERE episodeId IN (:ids) AND playedAt IS NULL")
    suspend fun markPlayed(ids: List<Long>, now: Long): Int
    @Query("UPDATE episode_state SET playedAt = NULL, startedAt = NULL, updatedAt = :now " +
           "WHERE episodeId IN (:ids) AND (playedAt IS NOT NULL OR startedAt IS NOT NULL)")
    suspend fun markUnplayed(ids: List<Long>, now: Long): Int         // fully unplayed (06)
    // touchLastPlayed(id, now) once per play start; setFavorite(id, fav, now); setDismissed(id, at, now);
    // clearDismissed(id, now); setMeasuredDuration(id, ms) — each with a "value differs" predicate
}
```

**Mark played, every path** (06's player rule, 03's `EpisodeRepository.setPlayed`/`markFeedPlayed`, 05's import option): one `withWriteTransaction`, IDs chunked at 500, running `ensureAll(ids)`, `markPlayed(ids)`, `PositionDao.reset(ids)` and `QueueDao` "Remove" for the same IDs. **Mark unplayed** (user): `markUnplayed(ids)` + `PositionDao.reset(ids)`. Re-listening (06) calls `markUnplayed` for the one episode at its first `isPlaying`. `playCount` therefore counts transitions to played by any path.

**Bulk "Mark all as played"** ([R2.6](../PLAN.md#21-functional-requirements), M2; 05's `markFeedPlayed(source, sortDateBefore)`): inside one write transaction, first `FeedDao.unplayedIds(query)` = the `countUnplayed` query of [Feed counts](#feed-counts) selecting `e.id` instead of `COUNT(*)` (any `FeedSource`, `VISIBLE`, optional `e.sortDate < :before`), then the mark-played chain over those IDs. The confirmation count and the number of rows marked are therefore the same predicate.

**"Treat existing episodes as played except the newest per podcast"** ([D66](../PLAN.md#3-key-decisions); 05 runs it in the transaction that sets the import item `SUBSCRIBED`, M3): IDs = `SELECT e.id FROM episode e WHERE e.podcastId = :podcastId AND e.isNew = 0 AND e.id <> (SELECT id FROM episode WHERE podcastId = :podcastId ORDER BY sortDate DESC, id DESC LIMIT 1)`, then the mark-played chain. `isNew = 0` keeps an episode that a later, non-initial refresh found meanwhile unplayed.

### Refresh selection and fetch-state writes

`PodcastDao` (M1, semantics [03 Refresh scheduling](03-feeds-and-discovery.md#refresh-scheduling)). The podcast table has at most a few hundred rows; scans are acceptable. `RefreshScope` maps to `:scopeAll = 1` (All), a membership subquery variant (Group) or `id IN (:ids)` (Podcasts).

```sql
-- forceDue(scope): 03's "force" step, persisted so a continuation needs no IDs
UPDATE podcast SET nextRefreshAt = 0
WHERE gone = 0 AND needsCredentials = 0 AND (:scopeAll = 1 OR id IN (:ids)) AND nextRefreshAt IS NOT 0;

-- dueForRefresh(dueBefore = now + slack, scope): List<DueFeed>
SELECT id, feedUrl, sourceType, youtubeChannelId, youtubeVariants, channelMetadataAt, etag, lastModified,
       contentSha256, parserVersion, lastParseOk, credentialId, failureCount, initialFetch, status,
       lastSuccessAt, lastFullFetchAt, pendingNewFeedUrl, pagingNextUrl, pagingComplete, complete,
       ttlMinutes, latestEpisodeAt, subscribedAt, lastAttemptAt, lastErrorKind
FROM podcast
WHERE gone = 0 AND needsCredentials = 0
  AND (nextRefreshAt IS NULL OR nextRefreshAt <= :dueBefore)
  AND (:scopeAll = 1 OR id IN (:ids))
ORDER BY (status = 'PENDING_FIRST_FETCH') DESC, COALESCE(lastSuccessAt, 0) ASC, id ASC;

-- pagingPending(scope): 03's pagesOnly runs and background paging
SELECT <DueFeed columns> FROM podcast
WHERE gone = 0 AND needsCredentials = 0 AND pagingComplete = 0 AND pagingNextUrl IS NOT NULL
  AND (:scopeAll = 1 OR id IN (:ids))
ORDER BY id;
```

Outcomes that change no feed data (304, identical SHA-256, failures) only touch scheduling columns. They are written with `@Update(entity = PodcastEntity::class) suspend fun updateFetchStates(rows: List<PodcastFetchState>)` (partial entity: `id`, `lastAttemptAt`, `lastSuccessAt`, `nextRefreshAt`, `failureCount`, `lastErrorKind`, `lastErrorDetail`, `gone`, `needsCredentials`, and for `Unchanged` outcomes `etag`, `lastModified`, `lastFullFetchAt`, `lastParseOk` ([03 Validators](03-feeds-and-discovery.md#validators)); values computed by 03 from the `DueFeed` snapshot) in **batches of up to 20 outcomes or every 5 s**, so a 300-feed refresh invalidates open lists a few times instead of 300 times. The batcher (`FetchStateBatcher`, `:core:data`) flushes under `NonCancellable` when the run ends or hits its deadline; a process kill loses at most the unflushed outcomes, whose feeds are simply still due next tick (conditional GET, harmless). 03's user actions on a podcast (Retry, Edit URL, Enter password, unsubscribe) flush the batcher before writing, so a stale batched outcome never overwrites them. Outcomes with a changed body write validators and scheduling inside the feed's ingest transaction (validators are stored only after a successful commit, 03).

### Ingestion support

The diff algorithm is 03's ([03 Ingestion and diff](03-feeds-and-discovery.md#ingestion-and-diff)); `IngestDao` offers the primitives it needs inside one `withWriteTransaction` per feed (M1):

| Function | SQL / behaviour |
|---|---|
| `existing(podcastId): List<ExistingEpisodeKey>` | `SELECT id, identityKey, guid, enclosureUrl, title, pubDate, contentHash, inFeed FROM episode WHERE podcastId = ?` (03 builds the in-memory maps) |
| `PodcastDao.youtubeChannelIds()` (04's `YouTubeOutageMonitor`, M8) | `SELECT id FROM podcast WHERE sourceType = 'YOUTUBE_CHANNEL'` |
| `insertEpisodes(rows): List<Long>` | `@Insert` with ABORT; rows in descending `feedOrder` |
| `updateFeedFields(row: EpisodeFeedUpdate)` | One `@Query` `UPDATE episode SET … WHERE id = :id` per changed row (rows whose `contentHash` changed) writing every feed column except `id`, `podcastId`, `identityKey`, `firstSeenAt`, `isNew`. 03's null-preserving columns are written as `col = COALESCE(:col, col)`: `durationMs`, `imageUrl` and `artworkKey` (as a pair), `chaptersUrl` and `chaptersType`; `availability`, `isShort`, `isVideo` likewise take the adapter's `RowHint` when non-null ([03 Column rules on update](03-feeds-and-discovery.md#column-rules-on-update)). `sortDate` is recomputed by 03 from the stored `firstSeenAt` |
| `rekey(id, key, guid)` | `UPDATE episode SET identityKey = ?, guid = ? WHERE id = ?` |
| `setInFeed(ids, inFeed)` | Chunked `UPDATE episode SET inFeed = ? WHERE id IN (…)` for rows whose flag flips |
| `touchSeen(podcastId, now)` | `UPDATE episode SET lastSeenAt = :now WHERE podcastId = :pid AND inFeed = 1 AND lastSeenAt < :now - 86400000` (after the flips). Day granularity is enough for the 90-day retention clock and avoids rewriting every row of a large feed on each refresh |
| `replaceChildren(episodeId, description, transcripts, altEnclosures, persons, funding, pscChapters)` | Delete-and-insert per child table, only for changed episodes; persons/funding by `(ownerType, ownerId)` |
| `applyFeedMetadata(PodcastFeedMetadata)` | Partial update of 03-owned metadata, validators and scheduling columns of `podcast`; never `customTitle`, `includeInAll`, `episodeOrder`, `autoDownloadEligibleAfter`, `youtubeVariants`, `channelMetadataAt`; for `YOUTUBE_CHANNEL` rows a second partial class (`YouTubeFeedMetadata`) also omits `artworkUrl`, `artworkKey`, `bannerUrl`, `descriptionHtml`, `link`, `youtubeChannelId` (04) |
| `applyYouTubeFacts(rows: List<YouTubeFacts>)` (04, M9) | `@Update(entity = EpisodeEntity::class)` with partial class `YouTubeFacts(id, durationMs, availability, isShort)`; 04 passes only rows whose values changed; one transaction per channel, inside the refresh run |
| `EpisodeDao.youtubeEnrichmentCandidates(podcastId, now)` (04, M9) | `SELECT id, externalMediaId, durationMs, availability, isShort FROM episode WHERE podcastId = :pid AND externalMediaId IS NOT NULL AND ((durationMs IS NULL AND availability = 'AVAILABLE' AND firstSeenAt > :now - 604800000) OR (availability IN ('UPCOMING','LIVE') AND firstSeenAt > :now - 2592000000))` (index `podcastId` prefix) |
| `EpisodeDao.setAvailability(id, availability)` (04's `YouTubeAvailabilityRecorder`, M9) | `UPDATE episode SET availability = :a WHERE id = :id AND availability <> :a` — the only `episode` write outside the refresh pipeline besides restore stubs and retention |
| `PodcastDao.applyYouTubeChannelMetadata(id, artworkUrl, artworkKey, bannerUrl, descriptionHtml, channelMetadataAt)` (04, M8) | `UPDATE podcast SET artworkUrl = COALESCE(:artworkUrl, artworkUrl), artworkKey = CASE WHEN :artworkUrl IS NULL THEN artworkKey ELSE :artworkKey END, bannerUrl = COALESCE(:bannerUrl, bannerUrl), descriptionHtml = COALESCE(:descriptionHtml, descriptionHtml), channelMetadataAt = :channelMetadataAt WHERE id = :id`; 04 calls it only after a successful fetch, and a field the page did not provide (null) keeps its stored value |

### Downloads

`DownloadDao` (M6, semantics [07 State machine](07-downloads.md#state-machine)). Claiming is atomic: write transactions serialise ([D47](../PLAN.md#3-key-decisions) runners may race), and even without that the `claim` `UPDATE … WHERE state = 'QUEUED'` changes one row for exactly one caller; a caller that gets `0` returns `null` and 07's drain loop tries again. `DownloadDao` is an abstract class so `claimNext` can carry a body.

```kotlin
@Transaction suspend fun claimNext(lanes: List<DownloadLane>, now: Long, unmetered: Boolean, charging: Boolean,
                                   youtubeAllowed: Boolean, token: String, offset: Int = 0,
                                   hostHasSlot: (DownloadEntity) -> Boolean): DownloadEntity? {
    val pick = candidates(lanes, now, unmetered, charging, youtubeAllowed, offset).firstOrNull(hostHasSlot) ?: return null
    return if (claim(pick.episodeId, token) == 1) pick.copy(state = DownloadState.RESOLVING, runnerToken = token) else null
}
```

```sql
-- candidates(..., offset): pages of 20 rows; the engine picks the first whose host and YouTube slots are free and
-- pages on (offset 20, 40, …) when every row of a page waits for a busy host (07 Claiming and slots)
SELECT * FROM download
WHERE state = 'QUEUED' AND lane IN (:lanes)
  AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
  AND (allowMetered = 1 OR :unmetered = 1)
  AND (requireCharging = 0 OR :charging = 1)
  AND (:youtubeAllowed = 1 OR sourceKind != 'YOUTUBE')
ORDER BY priority DESC, requestedAt ASC, episodeId ASC
LIMIT 20 OFFSET :offset;
-- claim(id, token)
UPDATE download SET state = 'RESOLVING', waitReason = 'NONE', runnerToken = :token
WHERE episodeId = :id AND state = 'QUEUED';
```

| Purpose | SQL |
|---|---|
| Reconcile orphaned runners (07 `DownloadReconciler.resetOrphanedRunners`) | `UPDATE download SET state = :toState, waitReason = :reason, runnerToken = NULL, lastError = 'CANCELLED_BY_SYSTEM', lastStopReason = :stopReason WHERE state IN ('RESOLVING','DOWNLOADING','VERIFYING') AND lane IN (:lanes) AND (runnerToken IS NULL OR runnerToken NOT IN (:liveTokens))` — 07 calls it with `QUEUED`/`SYSTEM`/`STOP_PROCESS_DEATH`, or for `MANUAL` after a Task Manager stop with `PAUSED`/`NONE`/`STOP_USER_TASK_MANAGER` and the extra filter `AND runnerToken LIKE 'uidt:%'`, followed in the same transaction by `UPDATE download SET state = 'PAUSED', lastStopReason = :stopReason WHERE lane = 'MANUAL' AND state = 'QUEUED'` ([07 Pause, cancel and Task Manager stops](07-downloads.md#pause-cancel-and-task-manager-stops)) |
| Completed rows to verify on disk | `SELECT episodeId, rootId, relativePath, finalUri, totalBytes FROM download WHERE state = 'COMPLETED'` |
| `LocalMediaIndex` load (07, mirrored in memory) | `SELECT episodeId, finalUri FROM download WHERE state = 'COMPLETED' AND finalUri IS NOT NULL` |
| Storage used (quota) | `SELECT COALESCE(SUM(totalBytes), 0) FROM download WHERE state = 'COMPLETED'` |
| `observeEntries()` (Downloads screen, `Flow`) | `SELECT d.*, e.title, e.sortDate, e.podcastId, COALESCE(p.customTitle, p.title) AS podcastTitle, COALESCE(e.artworkKey, p.artworkKey) AS artworkKey, COALESCE(a.version, 0) AS artworkVersion, s.playedAt, COALESCE(s.isFavorite, 0) AS isFavorite FROM download d JOIN episode e ON e.id = d.episodeId JOIN podcast p ON p.id = e.podcastId LEFT JOIN episode_state s ON s.episodeId = d.episodeId LEFT JOIN artwork a ON a.key = COALESCE(e.artworkKey, p.artworkKey)` (sorted and grouped in Kotlin; ≈ 2,000 rows at most) |
| `observePlayedCompletedIds()` (`Flow`) | `SELECT d.episodeId FROM download d JOIN episode_state s ON s.episodeId = d.episodeId WHERE d.state = 'COMPLETED' AND s.playedAt IS NOT NULL` |
| `queuedNeeds(lane, now)` (runner scheduling, one row) | `SELECT COUNT(*) AS queued, SUM(CASE WHEN nextAttemptAt IS NULL OR nextAttemptAt <= :now THEN 1 ELSE 0 END) AS due, MAX(allowMetered) AS anyMeteredAllowed, MIN(CASE WHEN allowMetered = 0 THEN 1 ELSE 0 END) AS allNeedUnmetered, MIN(requireCharging) AS allNeedCharging, MIN(CASE WHEN nextAttemptAt > :now THEN nextAttemptAt END) AS earliestRetry, MIN(CASE WHEN nextAttemptAt > :now AND allowMetered = 1 THEN nextAttemptAt END) AS earliestRetryMetered, MIN(CASE WHEN nextAttemptAt > :now AND requireCharging = 0 THEN nextAttemptAt END) AS earliestRetryNoCharging, SUM(MAX(COALESCE(totalBytes, estimatedBytes, 157286400) - downloadedBytes, 0)) AS remainingBytes FROM download WHERE state = 'QUEUED' AND lane = :lane` (aggregates are NULL when no row matches) |
| `markWait(ids, reason, nextAttemptAt)` | `UPDATE download SET waitReason = :reason, nextAttemptAt = :next WHERE episodeId IN (:ids) AND state = 'QUEUED' AND (waitReason IS NOT :reason OR nextAttemptAt IS NOT :next)` (writes only changed rows) |
| `clearStorageWaits()` | `UPDATE download SET waitReason = 'NONE' WHERE state = 'QUEUED' AND waitReason = 'STORAGE'` |
| `requeueChangedEnclosures(now)` | One write transaction: `SELECT d.episodeId, d.rootId, d.tempPath FROM download d JOIN episode e ON e.id = d.episodeId WHERE d.state = 'FAILED' AND d.sourceKind = 'RSS_ENCLOSURE' AND d.lastError IN ('HTTP_NOT_FOUND','HTTP_GONE','HTTP_CLIENT','NOT_MEDIA') AND e.enclosureUrl IS NOT NULL AND d.sourceRef <> e.enclosureUrl`, then for those IDs `UPDATE download SET state = 'QUEUED', waitReason = 'NONE', attempt = 0, nextAttemptAt = NULL, lastError = NULL, lastHttpStatus = NULL, downloadedBytes = 0, etag = NULL, lastModified = NULL, tempPath = NULL, sourceRef = (SELECT e.enclosureUrl FROM episode e WHERE e.id = download.episodeId) WHERE episodeId IN (:ids)`; 07 deletes the returned `.part` files after commit |
| `rowsOnOtherRoots(target, limit)` (`download-move`) | `SELECT episodeId, rootId, relativePath, finalUri, totalBytes FROM download WHERE state = 'COMPLETED' AND rootId <> :target ORDER BY episodeId LIMIT :limit` |
| `pathsByRoot()` (orphan-file scan) | `SELECT rootId, relativePath, tempPath FROM download WHERE relativePath IS NOT NULL OR tempPath IS NOT NULL` |
| `PlaySessionDao.observeCurrentEpisodeId()` (07 deferral, read-only, `Flow<Long?>`) | `SELECT currentEpisodeId FROM play_session WHERE id = 0` (observes `play_session`, which changes per transition only) |

### Auto-download candidates

`DownloadDao.autoCandidates(...)` per podcast (M6). 07's `AutoDownloadPlanner` resolves the effective policy with `EffectiveSettingsResolver` (05) in Kotlin, then:

```sql
SELECT e.id, e.sortDate, e.enclosureLength, e.isVideo, e.externalMediaId, d.state AS downloadState, d.lane
FROM episode e
JOIN podcast p ON p.id = e.podcastId
LEFT JOIN episode_state s ON s.episodeId = e.id
LEFT JOIN download d ON d.episodeId = e.id
WHERE e.podcastId = :podcastId
  AND e.isNew = 1                                   -- never back catalogue (D66, D67)
  AND e.firstSeenAt > :eligibleAfter                -- podcast.autoDownloadEligibleAfter (D67)
  AND s.playedAt IS NULL AND s.downloadDismissedAt IS NULL
  AND <VISIBLE> AND e.availability = 'AVAILABLE'
  AND (:includeVideo = 1 OR e.isVideo = 0)
  AND (e.enclosureUrl IS NOT NULL OR (e.externalMediaId IS NOT NULL AND :youtubeDownloads = 1))
ORDER BY e.sortDate DESC, e.id DESC
LIMIT :keepLatest
```

Rows already downloaded or queued count towards `keepLatest`; the planner inserts `QUEUED(AUTO)` rows for returned episodes without a `download` row. Tombstoned episodes are skipped and do not occupy a slot (confirmed by [07 Auto-download policy](07-downloads.md#auto-download-policy)).

### Download all candidates

`FeedDao.downloadAllCandidates(query)` and `FeedDao.downloadAllCount(query)` (M6) back 05's `downloadAllEstimate(source)` ([05 Group feeds](05-groups-opml-backup.md#group-feeds)). `FeedQueryBuilder.downloadAll(spec, youtubeDownloads)` reuses the play-context source predicate and carried filters of [Play context](#play-context) without an anchor:

```sql
SELECT e.id, e.enclosureLength, COALESCE(s.measuredDurationMs, e.durationMs) AS durationMs
FROM episode e JOIN podcast p ON p.id = e.podcastId
LEFT JOIN episode_state s ON s.episodeId = e.id
LEFT JOIN download d ON d.episodeId = e.id
WHERE <source predicate> AND <filters> AND <VISIBLE> AND e.availability = 'AVAILABLE'
  AND e.sortDate >= :minSortDate
  AND s.playedAt IS NULL AND s.downloadDismissedAt IS NULL          -- tombstones are deliberate user deletions
  AND (d.episodeId IS NULL OR d.state IN ('FAILED', 'MISSING'))     -- no row in QUEUED … COMPLETED
  AND (e.enclosureUrl IS NOT NULL OR (e.externalMediaId IS NOT NULL AND :youtubeDownloads = 1))
ORDER BY e.sortDate DESC, e.id DESC
LIMIT 200
```

`downloadAllCount` is the same `WHERE` with `SELECT COUNT(*)` and no `LIMIT` (`capped = count > 200`). Unlike the context tail, Up next items and the current episode are not excluded: downloading them is wanted.

### Cleanup candidates

`DownloadDao.cleanupCandidates(upNextProtected)` (M6; eligibility order and grace periods are 07's, [07 Cleanup and quota](07-downloads.md#cleanup-and-quota)). The protected set of [R4.5](../PLAN.md#21-functional-requirements) is enforced in SQL; per-podcast `deleteAfterPlayed` is applied in Kotlin.

```sql
-- played downloads, AUTO lane first, oldest played first
SELECT d.episodeId, d.lane, d.totalBytes, d.rootId, d.relativePath, d.finalUri, s.playedAt, e.podcastId
FROM download d
JOIN episode e ON e.id = d.episodeId
JOIN episode_state s ON s.episodeId = d.episodeId
WHERE d.state = 'COMPLETED' AND s.playedAt IS NOT NULL AND s.isFavorite = 0
  AND d.episodeId NOT IN (SELECT currentEpisodeId FROM play_session WHERE currentEpisodeId IS NOT NULL)
  AND d.episodeId NOT IN (SELECT q.episodeId FROM queue_entry q ORDER BY q.ordinal LIMIT :upNextProtected)
ORDER BY (d.lane = 'AUTO') DESC, s.playedAt ASC
```

Unplayed `AUTO` downloads beyond `keepLatest` (07's rolling window; only podcasts whose effective auto-download is on, passed as `:podcastIds`). Protected rows are returned with a flag rather than filtered out, because they still occupy their place in the newest-N window:

```sql
SELECT d.episodeId, d.totalBytes, d.rootId, d.relativePath, d.finalUri, e.podcastId,
       (s.startedAt IS NOT NULL                                              -- in progress (07)
        OR COALESCE(s.isFavorite, 0) = 1
        OR d.episodeId IN (SELECT currentEpisodeId FROM play_session WHERE currentEpisodeId IS NOT NULL)
        OR d.episodeId IN (SELECT q.episodeId FROM queue_entry q ORDER BY q.ordinal LIMIT :upNextProtected)
       ) AS protected
FROM download d
JOIN episode e ON e.id = d.episodeId
LEFT JOIN episode_state s ON s.episodeId = d.episodeId
WHERE d.state = 'COMPLETED' AND d.lane = 'AUTO' AND e.podcastId IN (:podcastIds) AND s.playedAt IS NULL
ORDER BY e.podcastId, e.sortDate DESC, e.id DESC
```

Kotlin skips the first `keepLatest` rows per podcast and deletes the remaining rows with `protected = 0`. Unplayed `MANUAL` downloads are never returned by either query.

### Unsubscribe and merge

`PodcastDao.deleteCascade(podcastId)` ([D24](../PLAN.md#3-key-decisions), M1). The caller (03 `PodcastRepository.unsubscribe`) first asks 07 to delete the podcast's download files (`DownloadController.delete(ids, byUser = false)`, M6+) and flushes the fetch-state batcher.

```sql
-- one write transaction
DELETE FROM person  WHERE (ownerType = 'PODCAST' AND ownerId = :pid)
                       OR (ownerType = 'EPISODE' AND ownerId IN (SELECT id FROM episode WHERE podcastId = :pid));
DELETE FROM funding WHERE (ownerType = 'PODCAST' AND ownerId = :pid)
                       OR (ownerType = 'EPISODE' AND ownerId IN (SELECT id FROM episode WHERE podcastId = :pid));
UPDATE play_session SET contextType = NULL, contextId = NULL, contextAnchorEpisodeId = NULL, contextAnchorSortDate = NULL,
       generation = generation + 1, updatedAt = :now WHERE contextType = 'PODCAST' AND contextId = :pid;
DELETE FROM credential WHERE origin <> 'podcastindex'
   AND id = (SELECT credentialId FROM podcast WHERE id = :pid)
   AND NOT EXISTS (SELECT 1 FROM podcast o WHERE o.credentialId = credential.id AND o.id <> :pid);
DELETE FROM podcast WHERE id = :pid;
-- FK cascades: episode (+ description, transcript, alt_enclosure, chapter, episode_state, episode_position,
-- queue_entry, download), podcast_url_alias, podcast_settings, podcast_group_member;
-- SET NULL: play_session.currentEpisodeId, import_item.podcastId
```

`artwork` rows and files of the podcast are left to the reference-based garbage collection ([Artwork references](#artwork-references)).

**Merge** of podcast `loser` into `winner` (03 decides when, [03 Ingestion and diff](03-feeds-and-discovery.md#podcast-dedupe-and-merge); 05's import report shows `MERGED`). Before the transaction 03 deletes, through `DownloadController`, only the download files of **matched** loser episodes whose winner episode already has a `download` row (step 4 keeps the winner's row); unmatched loser episodes keep their downloads, because step 5 re-parents them. Matching runs in Kotlin on both podcasts' `IngestDao.existing()` lists: `identityKey`, then normalised enclosure URL. Then one write transaction:

1. `INSERT OR IGNORE INTO podcast_group_member(groupId, podcastId, sortOrder, addedAt, source) SELECT groupId, :winner, sortOrder, addedAt, source FROM podcast_group_member WHERE podcastId = :loser`.
2. `UPDATE OR IGNORE podcast_url_alias SET podcastId = :winner WHERE podcastId = :loser`; `INSERT OR IGNORE` the loser's `feedKey` as alias (`MERGE`); `DELETE FROM podcast_url_alias WHERE url = (SELECT feedKey FROM podcast WHERE id = :winner)` (an alias never equals a `feedKey`).
3. Settings: if the winner has no `podcast_settings` row, `INSERT INTO podcast_settings SELECT :winner, <override columns> FROM podcast_settings WHERE podcastId = :loser`; otherwise the winner's row stays. `includeInAll` = winner's; `customTitle` = winner's, else loser's.
4. Matched pairs `(l, w)`: user state merged with 05's Merge-restore rules (played = OR with the later `playedAt`, newer position wins, favourite and tombstone = OR, `INSERT OR IGNORE` + guarded `UPDATE`s of [User-state writes](#user-state-writes)); `UPDATE OR IGNORE queue_entry SET episodeId = :w WHERE episodeId = :l`; `UPDATE OR IGNORE download SET episodeId = :w WHERE episodeId = :l` (the winner keeps its own row if present; `relativePath` still finds the file); `UPDATE play_session SET currentEpisodeId = :w WHERE currentEpisodeId = :l`.
5. Unmatched loser episodes are re-parented, so their played state and positions survive ([N1](../PLAN.md#22-non-functional-requirements)): `UPDATE episode SET podcastId = :winner, inFeed = 0 WHERE id IN (:unmatched)` (cannot violate `UNIQUE(podcastId, identityKey)`, because none of their keys exists in the winner); retention ages them out later. Their `person`/`funding` rows keep `ownerId` (episode IDs do not change).
6. `UPDATE play_session SET contextId = :winner WHERE contextType = 'PODCAST' AND contextId = :loser`; `UPDATE import_item SET podcastId = :winner WHERE podcastId = :loser`.
7. `deleteCascade(loser)` (now only the matched loser rows and the loser's own metadata remain).

### Import commit

`ImportDao.commitChunk(...)` (M3, pipeline [05 OPML import](05-groups-opml-backup.md#opml-import)). Each chunk of ≤ 500 items is one write transaction:

1. Create missing groups: `INSERT OR IGNORE INTO podcast_group(uuid, name, nameKey, sortOrder, createdAt, updatedAt, …)` with `sortOrder = (SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM podcast_group)` and `uuid`/`nameKey` from 05; an ignored insert (Room returns `-1`) means a group with that `nameKey` exists: `SELECT id FROM podcast_group WHERE nameKey = :nameKey` and reuse it. A plain `INSERT` would abort the whole chunk on the unique index.
2. For new items: `INSERT OR IGNORE INTO podcast(sourceType, feedUrl, feedKey, title, customTitle, artworkKey, credentialId, status, initialFetch, subscribedAt, nextRefreshAt, includeInAll, youtubeChannelId, youtubeVariants) VALUES (…, 'PENDING_FIRST_FETCH', 1, :now, :now, 1, …)` (`customTitle` and `credentialId` as 05 supplies them, usually null), with `title` from the file (else the URL host) and the monogram `artworkKey` `m-{sha1hex(feedKey)}`. An ignored insert means the `feedKey` was subscribed meanwhile: `SELECT id FROM podcast WHERE feedKey = :feedKey` and continue as "already subscribed"; if that finds nothing the insert failed for another reason, which is a bug (throw, the chunk rolls back).
3. Aliases: `INSERT OR IGNORE INTO podcast_url_alias(url, podcastId, reason, addedAt) VALUES (:normalised, :pid, 'IMPORT', :now)` (skipped when equal to any `podcast.feedKey`).
4. Memberships, also for already-subscribed podcasts ([R1.2](../PLAN.md#21-functional-requirements)): `INSERT OR IGNORE INTO podcast_group_member(groupId, podcastId, sortOrder, addedAt, source) VALUES (…, 'MANUAL')`.
5. `UPDATE import_item SET status = 'QUEUED', podcastId = :pid WHERE sessionId = :sid AND ordinal = :ord`.

Progress: `ImportDao.observeProgress(sid)` = `SELECT status, COUNT(*) FROM import_item WHERE sessionId = :sid GROUP BY status` (observed). Preview and report lists: `ImportDao.pagedItems(sessionId, statuses: List<ImportItemStatus>): PagingSource<Int, ImportItemEntity>` = `SELECT * FROM import_item WHERE sessionId = :sid AND status IN (:statuses) ORDER BY ordinal` (index `(sessionId, status)`; observes `import_item`; 05 passes every status for "all"). Session cleanup: `ImportDao.expiredSessions(cutoff)` = `SELECT id, payloadPath FROM import_session WHERE state IN ('DONE','CANCELLED','PREVIEW') AND COALESCE(finishedAt, createdAt) < :cutoff` and `deleteSession(id)` (items cascade), called by `AutoSnapshotWorker` in M3–M10 and by `db-maintenance` from M11 (05's rule).

### Backup export

`BackupDao` (M3, archive format [05 Full backup and restore](05-groups-opml-backup.md#full-backup-and-restore)). `observeLibraryShape(): Flow<LibraryShape>` = `SELECT (SELECT COUNT(*) FROM podcast) AS podcasts, (SELECT COUNT(*) FROM podcast_group_member) AS memberships` drives 05's snapshot library watcher. The whole export runs inside **one read transaction**, so the archive is a point-in-time snapshot (WAL readers are isolated from concurrent writes). Library rows (podcasts with aliases and settings, groups with settings, memberships, queue, session) are small and read in full. Episodes are streamed in keyset chunks of 1,000 and written line by line to `episodes.jsonl`:

```sql
SELECT p.feedKey, e.id, e.identityKey, e.guid, e.title, e.pubDate, e.enclosureUrl, e.enclosureType, e.durationMs,
       e.externalMediaId, e.link,
       s.playedAt, s.playCount, s.startedAt, s.isFavorite, s.downloadDismissedAt, s.measuredDurationMs,
       s.updatedAt AS stateUpdatedAt, pos.positionMs, pos.positionSource, pos.updatedAt AS positionUpdatedAt,
       COALESCE(d.state = 'COMPLETED', 0) AS downloaded
FROM episode e
JOIN podcast p ON p.id = e.podcastId
LEFT JOIN episode_state s ON s.episodeId = e.id
LEFT JOIN episode_position pos ON pos.episodeId = e.id
LEFT JOIN download d ON d.episodeId = e.id
WHERE e.id > :afterId
  AND (s.episodeId IS NOT NULL OR pos.positionMs > 0 OR d.episodeId IS NOT NULL
       OR EXISTS (SELECT 1 FROM queue_entry q WHERE q.episodeId = e.id)
       OR e.id = (SELECT currentEpisodeId FROM play_session WHERE id = 0))
ORDER BY e.id
LIMIT 1000
```

The columns map 1:1 to 05's `EpisodeLineV1` fields; the `kv` of each line is `EpisodeKeys.versionOf(identityKey)`. The read transaction is `withReadTransaction`; only local files are written inside it (no `ContentResolver`). When the Auto Backup snapshot exceeds its size guard, 05 drops or slims lines in Kotlin before writing them; the query itself does not change.

### Restore matching

`BackupDao` (M3; merge rules owned by 05). Podcasts, in order of precedence:

```sql
-- 1. backup key or any backup alias against local feedKey or local aliases (keys = [key] + aliases)
SELECT id, 0 AS rank FROM podcast WHERE feedKey = :key
UNION ALL SELECT id, 1 FROM podcast WHERE feedKey IN (:keys)
UNION ALL SELECT podcastId, 2 FROM podcast_url_alias WHERE url IN (:keys)
ORDER BY rank, id LIMIT 1;
-- 2. real podcast:guid
SELECT id FROM podcast WHERE podcastGuid = :guid AND podcastGuidDerived = 0 ORDER BY id LIMIT 1;
-- 3. otherwise insert as in Import commit (status PENDING_FIRST_FETCH, initialFetch = 1) plus the backup's artworkUrl,
--    customTitle and credentialId; aliases with reason RESTORE
```

Groups: `SELECT id FROM podcast_group WHERE uuid = :uuid`, else `WHERE nameKey = :nameKey`, else insert.

Episodes, per podcast, in chunks of 1,000 lines: load `SELECT id, identityKey, guid, enclosureUrl, title, pubDate, link FROM episode WHERE podcastId = :pid` into maps (the extra columns feed `EpisodeKeys.keyFor`; an `h:` key additionally needs the description head, decoded from `episode_description` only on demand); for each line match by key (computing local keys with `EpisodeKeys.keyFor(local, kv)` when the line's `kv` differs from the stored version; a `kv` newer than the app's `EpisodeKeys.VERSION` skips key matching), then normalised enclosure URL, then `guid`. Unmatched lines with an enclosure URL or YouTube ID become **stubs** (`INSERT OR IGNORE`; an ignored stub is looked up by `(podcastId, identityKey)` and treated as matched):

```sql
INSERT OR IGNORE INTO episode(podcastId, identityKey, guid, title, pubDate, sortDate, feedOrder, firstSeenAt, lastSeenAt,
                    inFeed, isNew, enclosureUrl, enclosureType, durationMs, externalMediaId, link, contentHash, availability, isShort)
VALUES (:pid, :k, :guid, :title, :pubDate, MIN(COALESCE(:pubDate, :now), :now + 86400000), 0, :now, :now,
        0, 0, :u, :ty, :dur, :yt, :link, 0, 'AVAILABLE', 0)
```

`contentHash = 0` guarantees that the next refresh's diff updates the stub's feed columns when it matches it. State merge statements are the column-scoped writes of [User-state writes](#user-state-writes) with 05's rules (played = OR with `playedAt = max`; position with the newer `updatedAt`, via `INSERT OR IGNORE` then `UPDATE episode_position … WHERE episodeId = ? AND updatedAt < :posAt`; favourite and tombstone = OR).

### Artwork references

`ArtworkDao` (M4; store semantics, sync algorithm and descriptors: [08 Artwork pipeline](08-ui-ux.md#artwork-pipeline)). References are derived from data, so pins cannot leak:

```sql
-- referencedKeys(): podcast covers incl. monograms, artwork of completed downloads, group mosaics (never NULL)
SELECT artworkKey AS key FROM podcast
UNION SELECT e.artworkKey FROM download d JOIN episode e ON e.id = d.episodeId
      WHERE d.state = 'COMPLETED' AND e.artworkKey IS NOT NULL
UNION SELECT 'g-' || uuid FROM podcast_group;
-- garbage(): artwork rows (and files) no longer referenced; NOT IN is safe because the list has no NULL
SELECT key, localPath FROM artwork WHERE key NOT IN (<referencedKeys>);
-- recountPins(): <refCount> = the three counts below; rows whose count is unchanged are not written
UPDATE artwork SET pinCount = <refCount> WHERE pinCount <> <refCount>;
--   <refCount> = (SELECT COUNT(*) FROM podcast p WHERE p.artworkKey = artwork.key)
--              + (SELECT COUNT(*) FROM download d JOIN episode e ON e.id = d.episodeId
--                   WHERE d.state = 'COMPLETED' AND e.artworkKey = artwork.key)
--              + (CASE WHEN artwork.key IN (SELECT 'g-' || uuid FROM podcast_group) THEN 1 ELSE 0 END)
```

| Function (requested by 08) | SQL / behaviour |
|---|---|
| `syncCandidates()` | `SELECT p.artworkKey AS key, 'PODCAST' AS kind, p.artworkUrl AS sourceUrl, COALESCE(p.customTitle, p.title) AS title, p.feedKey, NULL AS groupId, a.url AS storedDescriptor, a.localPath, COALESCE(a.version, 0) AS version FROM podcast p LEFT JOIN artwork a ON a.key = p.artworkKey` `UNION ALL` the same shape for completed downloads' episode art (`e.artworkKey`, `e.imageUrl`, the podcast's title and `feedKey`, `WHERE d.state = 'COMPLETED' AND e.artworkKey IS NOT NULL`) `UNION ALL` groups (`'g-' \|\| g.uuid`, `'GROUP'`, `NULL`, `g.name`, `NULL`, `g.id`, …); mosaic members come from `GroupDao.observeMosaics()` (or its one-shot twin `mosaicMembers()`) |
| `applyBatch(rows: List<ArtworkSyncResult>)` | `@Upsert(entity = ArtworkEntity::class)` with partial class `ArtworkSyncResult(key, url, localPath, width, height, seedArgb, avgArgb, version, fetchedAt, lastError)`, one transaction per batch of 8; `pinCount` is never written here |
| `fallbackFor(key)` | `SELECT COALESCE(p.customTitle, p.title) AS title, p.feedKey FROM podcast p WHERE p.artworkKey = :key UNION ALL SELECT COALESCE(p.customTitle, p.title), p.feedKey FROM download d JOIN episode e ON e.id = d.episodeId JOIN podcast p ON p.id = e.podcastId WHERE e.artworkKey = :key LIMIT 1` (goes through `download`, so no index on `episode.artworkKey` is needed) |
| `observe(key)` | `SELECT * FROM artwork WHERE key = :key` (`Flow`) |
| `PodcastDao.observeArtworkKey(podcastId)` | `SELECT artworkKey FROM podcast WHERE id = :podcastId` (`Flow`; key for 08's `ArtworkRepository.observeColors(key, fallbackPodcastId)`) |
| `pinnedIndex()` | `SELECT key, localPath FROM artwork WHERE localPath IS NOT NULL` |

## Invalidation hygiene

Serves R2.9, N5 ([D16](../PLAN.md#3-key-decisions), [D17](../PLAN.md#3-key-decisions), risk [T5](../PLAN.md#8-risks-and-mitigations)). Delivered in M2 (tests), rules apply from M1.

### How Room invalidates

Room installs `AFTER INSERT/UPDATE/DELETE` row triggers per observed table and notifies observers per table after each committed transaction; observers re-run their query. `LimitOffsetPagingSource` invalidates on **any** change to any observed table and then re-runs `COUNT(*)` plus the page query ([room3-paging source](https://github.com/androidx/androidx/blob/androidx-main/room3/room3-paging/src/commonMain/kotlin/androidx/room3/paging/LimitOffsetPagingSource.kt)). Consequences:

- Invalidation is table-granular, not row- or column-granular. Joining a table written every 5 s makes every open feed re-query every 5 s.
- Statements that change zero rows (ignored inserts, guarded updates that do not match) fire no trigger and cause no invalidation.
- N writes in one transaction cause one notification; N transactions cause up to N.

```mermaid
sequenceDiagram
  participant PT as PositionTracker (06)
  participant PD as PositionDao
  participant DB as SQLite WAL
  participant IT as InvalidationTracker
  participant LS as EpisodeLiveStateSource (08)
  participant PS as Feed PagingSource
  PT->>PD: save(episodeId, positionMs) every 5 s
  PD->>DB: INSERT OR IGNORE + guarded UPDATE, one transaction
  DB-->>IT: episode_position modified
  IT-->>LS: re-run IN (visible ids) query, about 40 rows
  Note over PS: does not observe episode_position, so no COUNT and no page reload
```

### Observed tables per query

| Query | Kind | Observed tables | Notes |
|---|---|---|---|
| `FeedDao.page` | `PagingSource` (`@RawQuery observedEntities`) | `episode`, `podcast`, `episode_state`, `download`, `artwork`, `podcast_group_member` | Superset for every source; only low-churn tables |
| `FeedDao.observeContext` | `Flow` (`@RawQuery`) | `episode`, `podcast`, `episode_state`, `download`, `queue_entry`, `podcast_group_member` | `LIMIT 20` keyset query, cheap to re-run on transitions |
| `FeedDao.observeGroupCounts`, `observeAllCounts`, `observeUngroupedCounts` | `Flow` | `podcast_group`, `podcast_group_member`, `podcast`, `episode`, `episode_state` | |
| `PodcastDao.observeLibraryTiles`, `GroupDao.observeMosaics` | `Flow` | `podcast`, `artwork`, `episode`, `episode_state`, `podcast_group_member` (+ `podcast_group`) | |
| `QueueDao.observeUpNext` | `Flow` | `queue_entry`, `episode`, `podcast`, `episode_state`, `download`, `artwork` | Not paged |
| `EpisodeDao.observeMediaInfo` | `Flow`, `IN (:ids)` | `episode`, `podcast`, `episode_state`, `download`, `artwork`, `episode_alt_enclosure` | ≤ 120 IDs (06's window) |
| `DownloadDao.observeEntries`, `observePlayedCompletedIds` | `Flow` | `download`, `episode`, `podcast`, `episode_state` (+ `artwork`) | Downloads screen; `download` changes on transitions only |
| `PositionDao.observeFor`, `DownloadDao.observeLiveFor`, `EpisodeStateDao.observeFor` | `Flow`, `IN (:ids)` | one table each | High churn by design; ≤ 200 IDs |
| `PlaySessionDao.observeCurrentEpisodeId`, `ArtworkDao.observe` | `Flow` | one table each | |
| `ImportDao.observeProgress`, `ImportDao.pagedItems` | `Flow`, `PagingSource` | `import_item` | Medium churn only while an import runs |

### Write rules

1. `episode_position` is never referenced by a paged query, a count query, the library tiles or Up next.
2. Download byte progress is never persisted at progress cadence ([D17](../PLAN.md#3-key-decisions)); `download` changes only on transitions.
3. Fetch-state-only `podcast` writes are batched ([Refresh selection and fetch-state writes](#refresh-selection-and-fetch-state-writes)); ingestion of a changed feed is one transaction.
4. `ArtworkSyncWorker` writes artwork rows in one transaction per batch (8 images).
5. `episode_state.startedAt` is written once per episode (guarded update), not on every position tick.
6. Ingestion updates feed columns only for rows whose `contentHash` changed; a body with an unchanged SHA-256 writes nothing but fetch state.

### Rules for new tables

- Classify every new table: written more than once per 30 s in steady state = high churn; high-churn tables never appear in a paged query or in `observedEntities` of one.
- Adding a join to `FeedQueryBuilder` requires adding the table to `FeedDao.page`'s `observedEntities` (otherwise lists go stale) and to the hygiene test's churn matrix.
- A new high-churn column on a low-churn table is not allowed; put it in its own table keyed by the parent ID.

### Hygiene tests

`InvalidationHygieneTest` (Robolectric, M2; M2 acceptance criterion 3):

1. For each `FeedSource` (All, Ungrouped, Group, Podcast), create the `PagingSource`, load the first page and register `registerInvalidatedCallback` counting invalidations.
2. Perform 100 position saves with 06's sequence (`insertIfAbsent` + `updateGuarded` + `ensure` + `markStarted`, one transaction each, pos > 0, same and different episodes), 20 `PlaySessionDao` updates and 20 Up next reorders.
3. Assert zero invalidations after the first save of each episode (that save creates the `episode_state` row and sets `startedAt`, one invalidation by design). Positive control: one mark-played transaction causes exactly one invalidation.
4. `RefreshBatchingTest`: 300 simulated 304 outcomes through the batching writer cause ≤ 15 invalidations of an open All `PagingSource`.

---

## Retention and maintenance

Serves N1, N5 ([D23](../PLAN.md#3-key-decisions)). Delivered in M11 (`db-maintenance`); `PRAGMA optimize` on open from M1.

### Retention policy

An episode is deleted when it has been absent from its feed for 90 days (`inFeed = 0 AND lastSeenAt < now − 90 d`) and none of these protects it: a `download` row in any state; a `queue_entry`; being `play_session.currentEpisodeId`; favourite; in progress; played in the last 30 days; being the newest episode of its podcast (watermark, as NewPipe keeps one item per channel). Restore stubs (`inFeed = 0`, `lastSeenAt` = restore time) get the same 90 days to be matched by a refresh. YouTube videos that merely scroll out of the 15-entry Atom window keep `inFeed = 1` (03's partial-document rule) and are therefore never retention-deleted in v1; at ≈ 7,000 rows a year for a 20-uploads-a-day channel this is accepted and measured in M11 with `SeedDatabase(youtubeChannels = 30)` (04 open question 1).

```sql
-- MaintenanceDao.retentionBatch(absentBefore = now - 90 d, playedSince = now - 30 d); inside withWriteTransaction
SELECT e.id FROM episode e
WHERE e.inFeed = 0 AND e.lastSeenAt < :absentBefore
  AND NOT EXISTS (SELECT 1 FROM download d WHERE d.episodeId = e.id)
  AND NOT EXISTS (SELECT 1 FROM queue_entry q WHERE q.episodeId = e.id)
  AND NOT EXISTS (SELECT 1 FROM play_session ps WHERE ps.currentEpisodeId = e.id)
  AND NOT EXISTS (SELECT 1 FROM episode_state s WHERE s.episodeId = e.id
                  AND (s.isFavorite = 1 OR (s.startedAt IS NOT NULL AND s.playedAt IS NULL) OR s.playedAt >= :playedSince))
  AND NOT EXISTS (SELECT 1 FROM episode_position pos WHERE pos.episodeId = e.id AND pos.positionMs > 0
                  AND NOT EXISTS (SELECT 1 FROM episode_state s2 WHERE s2.episodeId = e.id AND s2.playedAt IS NOT NULL))
  AND e.id <> (SELECT e2.id FROM episode e2 WHERE e2.podcastId = e.podcastId ORDER BY e2.sortDate DESC, e2.id DESC LIMIT 1)
ORDER BY e.id
LIMIT 500;
-- same transaction, for the selected ids
DELETE FROM person  WHERE ownerType = 'EPISODE' AND ownerId IN (:ids);
DELETE FROM funding WHERE ownerType = 'EPISODE' AND ownerId IN (:ids);
DELETE FROM episode WHERE id IN (:ids);   -- cascades description, transcripts, alt enclosures, chapters, state, position
```

Selecting and deleting in the same write transaction closes the race with a user who queues or favourites an episode meanwhile. The candidate scan reads the whole `episode` table once per batch (≈ 50k rows, tens of ms); no partial index is used (Room cannot declare one and would flag a manually created index during schema validation).

### db-maintenance worker

`DbMaintenanceWorker` (`:core:data`, `@HiltWorker`), unique periodic work `db-maintenance`, 24 h, constraints device idle and battery not low, policy `UPDATE`, tag `maintenance`. It checks an 8-min soft deadline between steps and between batches and simply stops; the next run continues.

| Step | Action | Frequency |
|---|---|---|
| 1 | Retention batches until none left or deadline | daily |
| 2 | Orphan sweeps: `DELETE FROM person WHERE (ownerType = 'EPISODE' AND ownerId NOT IN (SELECT id FROM episode)) OR (ownerType = 'PODCAST' AND ownerId NOT IN (SELECT id FROM podcast))`; same for `funding`; `DELETE FROM credential WHERE origin <> 'podcastindex' AND id NOT IN (SELECT credentialId FROM podcast WHERE credentialId IS NOT NULL)` | daily |
| 3 | Import-session cleanup (rule owned by 05): `ImportDao.expiredSessions(now − 7 d)` ([Import commit](#import-commit)); delete `cacheDir/{payloadPath}`, then `deleteSession(id)`. Takes over from 05's `AutoSnapshotWorker`, which runs the same calls in M3–M10 | daily |
| 4 | Ask `ArtworkStore` (08) to collect garbage using [Artwork references](#artwork-references) (08's `ArtworkSyncWorker` also does this after each sync) | daily |
| 5 | `PRAGMA optimize` | daily |
| 6 | `PRAGMA quick_check` if at least 2 min remain before the deadline; on a result other than `ok`, write `diagnostics.db_quick_check_failed_at` and log (redacted) | daily |
| 7 | `VACUUM` when `freelist_count / page_count > 0.25` and freelist > 8 MB, free space > 2 × DB size + 100 MB, and no playback in the last 10 min (`play_session.updatedAt` and `MAX(episode_position.updatedAt)` older than 10 min). `VACUUM` cannot run inside a transaction: it runs on the writer connection via `useWriterConnection` outside any transaction and blocks other writers for its duration (a few seconds at the N5 scale; a writer waiting longer than Room's 30 s pool timeout fails and is retried by its owner), hence the playback guard and the idle constraint. After a vacuum the freelist is empty, so it does not repeat until the threshold is reached again | when thresholds are met |
| 8 | Record row counts, `page_count × page_size` and step durations for the diagnostics screen (09) | daily |

`VACUUM INTO '<cacheDir>/export/neutrodyne-diagnostics-<yyyy-MM-dd-HHmm>.db'` (SQLite ≥ 3.27: the bundled driver, or the framework driver on API 30+; `cache/export/` is the path the FileProvider shares) produces the diagnostics DB export of [D33](../PLAN.md#3-key-decisions); it is never importable ([SQLite VACUUM](https://www.sqlite.org/lang_vacuum.html)). Before it leaves the app the copy is scrubbed on a raw driver connection ([N3](../PLAN.md#22-non-functional-requirements)): `DELETE FROM credential`; every URL column that can carry a token (`podcast.feedUrl`, `feedKey`, `pagingNextUrl`, `pendingNewFeedUrl`, `artworkUrl`, `podcast_url_alias.url`, `episode.enclosureUrl`, `imageUrl`, `chaptersUrl`, `episode_alt_enclosure.sourcesJson`, `artwork.url`, `download.sourceRef`, `import_item.originalUrl`/`normalizedUrl`) replaced by `scheme://host/…#{rowid}`, and `episode.identityKey` and `episode.guid` (`u:` keys embed the enclosure URL; GUIDs can be URLs) replaced by their key prefix + `…#{rowid}` (the suffix keeps unique indices valid); then `VACUUM` so deleted bytes are gone. The flow and the user warning are 09's.

### Expected size

Planning estimate at the N5 scale (300 podcasts, 50,000 episodes, 20 groups). **Unverified:** the M2 seeded benchmark records the real numbers; budget ≤ 100 MB.

| Component | Rows | Bytes per row (incl. index entries) | Size |
|---|---|---|---|
| `episode` + 4 secondary indices | 50,000 | ≈ 800 (URLs, key, title, snippet) | ≈ 40 MB |
| `episode_description` (deflated) | 50,000 | ≈ 500 (from ≈ 1.5 KB raw) | ≈ 25 MB (≈ 75 MB uncompressed) |
| `episode_state` | ≈ 30,000 | ≈ 50 | ≈ 1.5 MB |
| chapters, persons, funding, transcripts, alternates | ≈ 60,000 | ≈ 60 | ≈ 3.5 MB |
| `podcast` (+ aliases, settings) | 300 | ≈ 3 KB (description) | ≈ 1 MB |
| everything else | — | — | < 1 MB |
| **Total** | | | **≈ 70 MB** |

Prior art: AntennaPod users report 52,000 items / 80 MB and 364 MB databases with battery symptoms, and AntennaPod never prunes removed episodes (issue #4426). Show notes are kept out of hot tables by three measures: the separate `episode_description` table (lists never load it), compression, and the 200-character `snippet` column for rows.

---

## Migrations and schema testing

Serves N1, N11 ([D22](../PLAN.md#3-key-decisions)). Harness delivered in M1; every later schema change follows it.

### Schema export and versioning

- `exportSchema = true`; the `neutrodyne.room` plugin sets `room3 { schemaDirectory("$projectDir/schemas") }`, producing `core/database/schemas/app.neutrodyne.core.database.NeutrodyneDatabase/<version>.json`. The files are committed and reviewed like code.
- Version 1 is the complete M1 schema. **A version is frozen once any tagged build (`vX.Y.Z-beta.N` or release) contains it**; afterwards its JSON never changes. Between tags, the next version number may be regenerated freely.
- Any change to an entity, index, `@Database` entity list or FTS/view definition bumps `VERSION` by one and adds a migration and its test in the same change.
- CI (09) runs KSP and fails on an uncommitted change under `schemas/` (drift check) and on any modification of a frozen version's JSON compared with the last tag.
- `fallbackToDestructiveMigration*` is never used, in any build type.

### Writing migrations

| Change | How |
|---|---|
| Add a table, an index, or a nullable/defaulted column | `@AutoMigration(from = N, to = N + 1)` allowed (still tested): these are plain `CREATE`/`ALTER TABLE … ADD COLUMN` statements |
| Anything that makes Room recreate a table (rename/drop a column, change a type, nullability, default, PK or FK) | **Manual** `Migration(N, N + 1)` with the table-rebuild procedure below, in `core/database/src/main/kotlin/app/neutrodyne/core/database/migration/MigrationNToM.kt`, listed in `ALL_MIGRATIONS`. Never an `@AutoMigration` with `@RenameColumn`/`@DeleteColumn` specs: Room's generated rebuild has the two hazards below and cannot be fixed from a spec |
| Rename a table; move data between tables | Manual migration |

Two hazards make table rebuilds dangerous here:

- **Foreign-key actions.** With `foreign_keys = ON`, `DROP TABLE x` first runs an implicit `DELETE` that fires `ON DELETE CASCADE`/`SET NULL` actions in child tables; `PRAGMA defer_foreign_keys` defers only constraint *checks*, not actions, and `PRAGMA foreign_keys` cannot be changed inside Room's migration transaction ([SQLite foreign keys](https://www.sqlite.org/foreignkeys.html) §2, §5). Rebuilding `podcast` or `episode` with foreign keys on would silently delete every episode, `episode_state`, position, Up next entry and download row. Migrations therefore rely on `foreign_keys = OFF` during migration ([Database builder and connections](#database-builder-and-connections), spike S3).
- **`AUTOINCREMENT` high-water mark.** Copying rows into `new_x` and renaming it leaves `sqlite_sequence` at the largest *surviving* ID, so IDs of deleted rows (retention, unsubscribe) would be reused, breaking the [Local row IDs](#local-row-ids) guarantee (media IDs, `[e<id>]` file names). The procedure restores the old sequence value (Unverified detail: exact `sqlite_sequence` behaviour on `DROP`/`RENAME`; the migration test asserts the outcome).

Manual migration rules:

1. Pure SQL on the passed `SQLiteConnection` (`override suspend fun migrate(connection: SQLiteConnection)`, already inside Room's transaction); no calls into `:feeds` or any other module, no network, no file I/O.
2. Table rebuild procedure for table `x`, implemented once as `TableRebuild.run(connection, table, newDdl, columnMap)` and used by every manual migration (SQLite's documented order, [ALTER TABLE](https://www.sqlite.org/lang_altertable.html) "Making Other Kinds Of Table Schema Changes"):
   1. `check(PRAGMA foreign_keys == 0)` — fail the migration (and therefore the test) otherwise;
   2. read `SELECT seq FROM sqlite_sequence WHERE name = 'x'` (AUTOINCREMENT tables);
   3. `CREATE TABLE new_x` with the exact DDL from the new schema JSON;
   4. `INSERT INTO new_x (<cols>) SELECT <cols or expressions> FROM x`;
   5. `DROP TABLE x`; `ALTER TABLE new_x RENAME TO x` (only the new table is ever renamed: renaming a parent table rewrites the child tables' FK clauses);
   6. recreate every index of `x` from the schema JSON;
   7. `UPDATE sqlite_sequence SET seq = MAX(seq, :oldSeq) WHERE name = 'x'`; if it changed no row, `INSERT INTO sqlite_sequence(name, seq) VALUES ('x', :oldSeq)` (`sqlite_sequence` has no unique constraint, so never `INSERT OR REPLACE`);
   8. `PRAGMA foreign_key_check` — fail the migration if it returns rows.
3. Never recompute identity keys or `feedKey` in a migration ([Key versions](#key-versions)); if a change requires re-ingestion, reset `podcast.parserVersion = 0` so 03 refetches without validators.
4. The migration must preserve every [invariant](#invariants); intended exceptions are declared in its test.
5. Enum renames are data migrations (`UPDATE … SET col = 'NEW' WHERE col = 'OLD'`, [Type converters](#type-converters)).

### Invariants

`MigrationInvariants` snapshots, before and after migrating, an ordered SHA-256 over these projections and compares them:

| Data | Projection |
|---|---|
| Subscriptions | `podcast(feedKey, feedUrl, customTitle, includeInAll, subscribedAt, credentialId)`, `podcast_url_alias(url, podcastId)` |
| Groups | `podcast_group(uuid, name, nameKey, sortOrder, colorArgb, iconKey, feedOrder, playOrder, filterFlags, mediaFilter, hideOlderThanDays, showAsTab)`, `podcast_group_member(groupId, podcastId)` |
| Settings | every column of `podcast_settings`, `podcast_group_settings` |
| Episode identity | `episode(id, podcastId, identityKey)` |
| User state | every column of `episode_state`; `episode_position(episodeId, positionMs)` |
| Queue | `queue_entry(episodeId)` in `ordinal` order; `play_session(currentEpisodeId, contextType, contextId)` |
| Downloads | `download(episodeId, rootId, relativePath, finalUri, totalBytes)` where `state = 'COMPLETED'` |
| Credentials | `credential(id, origin, username, secretCipher, iv)` |
| Row-count and ID guards | `COUNT(*)` of every table; `sqlite_sequence.seq` of every `AUTOINCREMENT` table is unchanged or larger |

### Tests

- `MigrationTestHelper(instrumentation, databaseClass = NeutrodyneDatabase::class, driver = …, file = …)`, with `core/database/schemas` added as assets of the `test` and `androidTest` source sets (09 configures).
- `MigrationNToMTest` per version pair: `createDatabase(N)` with fixture rows, `runMigrationsAndValidate(M, …)`, invariant comparison, plus assertions for the intended change.
- `MigrateAllTest`: create version 1 from `testFixtures/resources/db/v1-fixture.sql` (rows in **every** table, including edge values: null optionals, max lengths, emoji, a position of 1 ms), migrate to the current version, validate, compare invariants, then open with `NeutrodyneDatabase.build` and call one read function of every DAO.
- `RebuildProcedureTest` (M1, before any real rebuild exists): the shared `TableRebuild.run(connection, table, newDdl, columnMap)` helper that every manual migration uses is applied to `podcast` and `episode` of the v1 fixture inside a `BEGIN EXCLUSIVE` transaction on a raw driver connection; asserts every child table keeps its row count and `sqlite_sequence` is unchanged, and that the helper refuses to run when `foreign_keys = 1`. Whether Room's own migration transaction runs with foreign keys off is spike S3's assertion.
- Drivers: JVM/Robolectric with `AndroidSQLiteDriver`; instrumented (GMD API 26 and API 36) with both `BundledSQLiteDriver` and `AndroidSQLiteDriver`.
- M11 acceptance criterion 6: migrate the frozen schema of the first tester build to the 1.0 schema with `MigrateAllTest`, and upgrade a device from the last beta keeping all data.

---

## Error handling and recovery

Serves N1. Delivered in M1.

`DatabaseOpener` (in `:core:database`) owns opening and recovery. Initializer 100 of the start-up sequence calls `awaitOpen()` on IO, and 01's `StartupGate` renders instead of the app UI until it completes, so no ViewModel or repository exists before the database is open ([01 Application start-up](01-foundation.md#application-start-up)). The Hilt provider of `NeutrodyneDatabase` returns `requireDatabase()`, which blocks a background caller (a worker, a binder thread) until the open finishes and throws on the main thread if called before; framework components and initializers therefore hold database-backed dependencies lazily (01's rule).

```kotlin
class DatabaseOpener @Inject constructor(/* context, driver, @Dispatcher(IO) io, clock, files */) {
    suspend fun awaitOpen(): OpenResult            // idempotent; first call opens, later calls return the result;
                                                   // throws DatabaseOpenException if even a fresh database cannot be created
    fun requireDatabase(): NeutrodyneDatabase      // see above
}
data class OpenResult(val created: Boolean, val recovered: RecoveryCause?)
enum class RecoveryCause { CORRUPT, MIGRATION_FAILED, DOWNGRADE }
class DatabaseOpenException(val reason: Reason, cause: Throwable) : Exception(cause) { enum class Reason { DISK_FULL, IO, UNKNOWN } }
```

```mermaid
flowchart TD
  A["awaitOpen() on IO, main process only"] --> B{"quarantine marker present?"}
  B -->|yes| Q["move neutrodyne.db, -wal, -shm to databases/quarantine/ts/"]
  B -->|no| K{"neutrodyne.db exists?"}
  K -->|no| D
  K -->|yes| C["raw driver preflight: PRAGMA user_version"]
  C -->|"NOTADB or CORRUPT"| Q
  C -->|"version above VERSION"| Q
  C -->|ok| D["build Room, force open with a trivial read"]
  D -->|"migration or corruption error"| Q
  D -->|ok| E{"Callback.onCreate ran?"}
  Q --> F["build a fresh Room instance, onCreate runs"]
  F --> G["OpenResult created = true, recovered = cause"]
  E -->|yes| H["OpenResult created = true"]
  E -->|no| I["OpenResult created = false"]
  G --> J["05 restores files/backup/auto-snapshot.zip in Merge mode (D70)"]
  H --> J
```

- `created = true` is the only fresh-install signal (never a DataStore flag); 05's first-launch restore consumes it ([05 Auto Backup](05-groups-opml-backup.md#auto-backup)). With `recovered != null`, the UI (08) shows "Your library database was damaged and has been restored from the latest snapshot" and offers a crash report (09). Data loss is bounded by the snapshot age (≤ 24 h).
- Quarantine keeps only the newest quarantined copy, for 14 days, for the diagnostics export; it is never backed up.
- Mid-session corruption (`SQLITE_CORRUPT` from any statement): log redacted, write the quarantine marker only if `PRAGMA quick_check` also fails; the next start recovers.
- `DatabaseOpenException` (for example `SQLITE_FULL` while creating the fresh database after a quarantine) maps to 01's `StartupState.database = Failed`: 08 shows "Not enough storage to open your library" with "Manage storage" and "Retry"; nothing is deleted. Retrying calls `awaitOpen()` again (a failed result is not cached).
- A migration failure is quarantined only in release builds; debug and test builds rethrow, so a broken migration is never hidden during development.
- Unverified: how `androidx.sqlite.SQLiteException` exposes SQLite result codes with each driver (spike S2 records it; detection falls back to message parsing confirmed by `PRAGMA quick_check`).

| Failure | Detection | Behaviour |
|---|---|---|
| Unique violation on `feedKey`, `nameKey`, `uuid`, `(podcastId, identityKey)` | `SQLITE_CONSTRAINT_UNIQUE` | Repositories map `feedKey` → "already subscribed" (03), `nameKey` → `GroupError.NameTaken` (05); identity-key violations abort the feed transaction (03 records `IDENTITY_CONFLICT`). Others are bugs: rolled back, logged, `Outcome.Failure` |
| FK violation | `SQLITE_CONSTRAINT_FOREIGNKEY` | Bug; transaction rolled back; crash in debug builds |
| Disk full | `SQLITE_FULL` | Write fails; refresh run ends with a storage error; a position save is retried on the next tick (at most 5 s lost, N1); 07 pauses lanes |
| Long writer transaction | — | Avoided by the batch sizes in [Transactions and threading](#transactions-and-threading); no network or file I/O inside transactions |
| Cancellation (worker stopped, quota) | `CancellationException` | Room rolls back the open transaction; per-feed and per-chunk transactions bound the lost work |
| Bound-variable limit | `SQLITE_ERROR` "too many SQL variables" | Prevented by chunking `IN` lists at 500 |
| Device clock wrong | — | Timestamps come from `Clock`; a far-future clock could pin undated episodes (`sortDate = firstSeenAt`), so 03 takes `firstSeenAt` from the HTTP `Date` header when it differs from the device clock by more than 24 h ([03 Ingestion and diff](03-feeds-and-discovery.md#ingestion-and-diff)) |

---

## Testing

Serves N1, N5, N9. Test infrastructure, runners and CI wiring are owned by [09 Test strategy](09-quality-and-release.md#test-strategy); this section lists what this area must test. JVM tests run under Robolectric with `AndroidSQLiteDriver` and an in-memory database (`TestDb.inMemory()`), or a temp-file database (`TestDb.file()`) where a second connection is needed (WAL reader isolation, concurrent claimers); instrumented tests run on GMD with both drivers.

| Test | Env | Asserts | Milestone |
|---|---|---|---|
| `SchemaSmokeTest` | JVM + GMD | DB opens, every DAO read works on an empty DB; `PRAGMA foreign_keys` = 1 on the writer and on a reader connection (both drivers); `1.json` declares `AUTOINCREMENT` for every `autoGenerate` key | M1 |
| `ConverterTest` | JVM | Every enum round-trips; unknown names map to the documented fallback; every `SqlEnumLiterals` name exists; bit constants | M1 |
| `DescriptionCodecTest` | JVM | Round trip of ASCII, emoji, 1 MB HTML; < 512 bytes stored raw; corrupt header handled | M1 |
| `IdentityStorageTest` | JVM | Duplicate `(podcastId, identityKey)` aborts the whole transaction; `rekey` keeps `episode_state`, `episode_position`, `download`, `queue_entry` rows | M1 |
| `UnsubscribeCascadeTest` | JVM + GMD | `deleteCascade` removes every dependent row incl. person/funding and an unshared credential (a shared one survives); `play_session.currentEpisodeId` → NULL; `import_item.podcastId` → NULL | M1 |
| `PodcastMergeTest` | JVM | Memberships and aliases move without an alias equal to a `feedKey`; matched episodes merge user state and queue/download/current pointers; unmatched loser episodes are re-parented with `inFeed = 0` and keep their state; a `PODCAST` context follows the winner | M1 |
| `RebuildProcedureTest` | JVM + GMD | [Tests](#tests): `TableRebuild` keeps child rows and `sqlite_sequence`, refuses to run with foreign keys on | M1 |
| `IngestDaoTest` | JVM | `updateFeedFields` keeps stored `durationMs`/`imageUrl`+`artworkKey`/`chaptersUrl` when the parsed value is null; `touchSeen` writes only rows older than a day; `applyFeedMetadata` never touches user and YouTube-owned columns; `forceDue` + `dueForRefresh` order (pending first) | M1 |
| `FeedQueryBuilderTest` (TestParameterInjector over source × filters × order) | JVM | Exact ID lists on the hand-built fixture (5 podcasts, 3 groups with overlaps, 60 episodes incl. equal `sortDate`s, YouTube Shorts/UPCOMING rows, `includeInAll = 0`); concatenating pages of 7 equals the full ordered list | M2 |
| `QueryPlanTest` | JVM and GMD (bundled) on the seeded DB | [Indices](#indices) expectations on the Room-wrapped COUNT and page SQL; the bundled-driver result is authoritative if they differ | M2 |
| `InvalidationHygieneTest`, `RefreshBatchingTest` | JVM | [Hygiene tests](#hygiene-tests) | M2 |
| `CountsTest` | JVM | Window and `hideOlderThanDays`; `isNew` + `lastViewedAt`; a podcast in two groups counted in each group and once in All; Ungrouped; non-`AVAILABLE` and hidden Shorts not counted; `countUnplayed` equals the number of rows the bulk mark-played chain changes | M2 |
| `FeedQueryTimingTest` | GMD + reference device | Seeded DB: group first page (count + 80 rows) ≤ 60 ms, All ≤ 100 ms, page loads ≤ 20 ms (R2.9); median of 20 runs recorded by CI | M2 |
| `ImportCommitTest`, `BackupExportTest`, `RestoreMatchingTest` | JVM (`BackupExportTest` on `TestDb.file()`) | Chunking; an existing `nameKey`/`feedKey` is reused, not an aborted chunk; memberships for already-subscribed podcasts; export snapshot unaffected by a concurrent write on another connection; every `EpisodeLineV1` field filled; precedence key > alias > real GUID (derived GUID ignored); episode match by `k` (incl. older `kv`), enclosure, guid; stub fields and `contentHash = 0`; expired-session query | M3 |
| `PositionGuardTest` | JVM | A 0 save after 1234 keeps 1234; `reset` sets 0; `startedAt` written once; no write when `playedAt ≥ pinStartedAt`; mark-played chain resets the position and removes the Up next entry; `markUnplayed` clears `startedAt` | M4 |
| `ContextTailTest` | JVM | Keyset across equal `sortDate`s; excludes played, queued, current, unavailable, `play`-flavour YouTube; OLDEST_FIRST; anchor row deleted; DOWNLOADS scope | M4 |
| `UpNextOrdinalTest` | JVM | 60 inserts between the same neighbours trigger renormalisation and keep order | M4 |
| `DownloadClaimTest` | JVM + GMD | Two concurrent claimers: exactly one wins; priority/requestedAt order; metered, charging, YouTube, `nextAttemptAt` filters | M6 |
| `AutoDownloadCandidatesTest`, `CleanupCandidatesTest`, `DownloadAllCandidatesTest` | JVM | D67 watermark and `isNew`; tombstones; protected set (favourite, current, next 3 Up next, unplayed MANUAL, in-progress AUTO) and its place in the keep-N window; download-all excludes `QUEUED`…`COMPLETED` rows and tombstones, caps at 200 with a correct total | M6 |
| `DownloadDaoTest` | JVM | Reconcile with live tokens (`QUEUED`/`SYSTEM` and Task Manager `PAUSED`); `markWait` writes only changed rows (zero invalidations on repeat); `requeueChangedEnclosures`; `queuedNeeds` aggregates on an empty and a mixed lane | M6 |
| `ArtworkReferencesTest` | JVM | Referenced keys from podcasts, completed downloads and groups; garbage list; `recountPins` writes only changed rows | M4 |
| `RetentionTest` | JVM | Each protection rule individually; newest-per-podcast watermark; 500-row batches; person/funding/credential orphan sweeps | M11 |
| `DiagExportScrubTest` | JVM | The `VACUUM INTO` copy has no `credential` rows and no URL path, query or userinfo in the scrubbed columns | M11 |
| Migration tests | JVM + GMD | [Tests](#tests) | M1 onward |

Fixtures (`core/database/src/testFixtures/`, consumed with `testImplementation(testFixtures(project(":core:database")))`):

- `TestDb.inMemory(driver = AndroidSQLiteDriver())`, `TestDb.file(dir, driver)`; `TestClock` comes from the `:core:common` test fixtures (re-exported by `:core:testing`).
- `FeedFixture`: the 60-episode hand-built dataset above, expressed as Kotlin builders so expected orders are readable in tests.
- `SeedDatabase(seed = 42, podcasts = 300, episodes = 50_000, groups = 20, membershipsPerPodcast = 0..3, playedFraction = 0.6, favourites = 200, downloads = 300, inProgress = 50, youtubeChannels = 30)`: deterministic generator with realistic string lengths (titles 40–90 chars, enclosure URLs 90–180 chars, descriptions 0.2–6 KB) used by `QueryPlanTest`, `FeedQueryTimingTest` and the size measurement.
- `db/v1-fixture.sql` for `MigrateAllTest`.

---

## Delivery by milestone

| Milestone | Delivered in this area |
|---|---|
| [M0](../PLAN.md#m0-scaffold-and-ci) | `:core:database` compiling stub; spikes run and recorded in [01 Spikes](01-foundation.md#spikes): S2 Room 3 `@RawQuery` → `PagingSource` plus the Unverified rows of [Room 2 to Room 3 mapping](#room-2-to-room-3-mapping), S3 `foreign_keys` on every connection after open and off inside migrations, S4 Robolectric with `AndroidSQLiteDriver` (SQLite version recorded), S6 16 KB alignment and size of `sqlite-bundled` |
| [M1](../PLAN.md#m1-subscribe-and-ingest-rss) | Complete schema version 1 (all 22 tables incl. `podcast.channelMetadataAt`, indices, converters), `1.json` exported; `EpisodeDescriptionCodec`; `DatabaseOpener` with recovery; builder and `PRAGMA optimize` on open; `PodcastDao` (due selection, `forceDue`, fetch states, library tiles), `EpisodeDao`, `IngestDao`, `CredentialDao`, `ChapterDao` (PSC); `FeedDao.page` for All and Podcast; `FetchStateBatcher`; unsubscribe cascade and podcast merge; `TableRebuild`, migration harness, invariants, `RebuildProcedureTest` and `MigrateAllTest` |
| [M2](../PLAN.md#m2-groups-and-group-feeds) | `FeedQueryBuilder` for every source × filter × order (or the generated fallback); `GroupDao`, `ScopeSettingsDao`; group, All and Ungrouped counts, `countUnplayed`/`unplayedIds`, mosaics, library tiles with group filter; `EpisodeStateDao` and the mark-played/unplayed chains (bulk, favourite); state live query; `SeedDatabase`, `QueryPlanTest`, `InvalidationHygieneTest`, `FeedQueryTimingTest` (R2.9) |
| [M3](../PLAN.md#m3-import-export-and-backup) | `ImportDao` (commit chunks, progress, `pagedItems`, expired sessions for 05's `AutoSnapshotWorker`), `BackupDao` (export, restore matching, stubs, merge writes), "played except newest" query |
| [M4](../PLAN.md#m4-playback-core) | `QueueDao`, `PlaySessionDao`, `PositionDao` with both guards, context tail (anchored and null-anchor) and start item, `mediaInfo`/`observeMediaInfo`, position live query, `ArtworkDao` (sync candidates, batches, fallback, observe, pinned index, references, recount) |
| [M5](../PLAN.md#m5-playback-features-and-system-surfaces) | `ChapterDao` writes for P2.0 JSON, ID3, MP4 and YouTube-description sources; measured-duration write-back; audio-alternate columns of the media lookup; Auto browse lists and title search |
| [M6](../PLAN.md#m6-downloads) | `DownloadDao` (claim, transitions, reconcile, `LocalMediaIndex` load, quota, entries, `queuedNeeds`, `markWait`, storage waits, changed enclosures, roots, paths), `PlaySessionDao.observeCurrentEpisodeId`, `EpisodeDao.downloadSources`, auto-download, download-all and cleanup candidate queries, tombstones, `autoDownloadEligibleAfter`, download live query |
| [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds) | No schema change: YouTube columns exist since version 1; `PodcastDao.applyYouTubeChannelMetadata`, the YouTube variant of `applyFeedMetadata`; the `VISIBLE` fragment and the `youtubePlayable` parameter become meaningful |
| [M9](../PLAN.md#m9-youtube-playback-and-downloads-in-foss) | No schema change ([D50](../PLAN.md#3-key-decisions)): YouTube download rows use `sourceKind = 'YOUTUBE'`, `formatPref`, `resolvedItag`; `IngestDao.applyYouTubeFacts`, `EpisodeDao.youtubeEnrichmentCandidates`, `EpisodeDao.setAvailability` |
| [M10](../PLAN.md#m10-covers-theming-adaptive-layouts-and-accessibility) | No schema change: `artwork.seedArgb`/`avgArgb` are populated |
| [M11](../PLAN.md#m11-release-hardening-and-v10) | `DbMaintenanceWorker` (retention, orphan sweeps incl. credentials, import cleanup taken over from 05, optimize, quick_check, vacuum, stats), diagnostics export scrub, `RetentionTest`, size measurement against the budget, migration test from the first tester schema to 1.0 |
| M12–M15 | Migrations for `episode_fts` (M15), `sponsor_segment` (M14), boost/intro/outro columns already reserved (M12) |

---

## Open questions

1. Resolved by the [D16](../PLAN.md#3-key-decisions) amendment: batched fetch-state writes (≤ 15 invalidations per 300-feed refresh, `RefreshBatchingTest`, PLAN M2 acceptance 3); the 1:1 `podcast_fetch_state` table is the recorded fallback if that test or the R2.9 benchmark fails.
2. Resolved: [D9](../PLAN.md#3-key-decisions) now names the SQLite 3.18 baseline; window functions stay banned ([SQL dialect baseline](#sql-dialect-baseline)).
3. Resolved by [D71](../PLAN.md#3-key-decisions): descriptions stay a compressed `BLOB`; the M15 FTS index covers `title` and `snippet`.
4. Resolved: [D15](../PLAN.md#3-key-decisions) names the three exceptions (restore stubs, retention deletes, `YouTubeAvailabilityRecorder`).
5. Resolved: [D22](../PLAN.md#3-key-decisions) names the rebuild procedure, and 01's spike S3 now also asserts `PRAGMA foreign_keys` = 0 inside `Migration.migrate`, arms the `ForeignKeysDriver` fallback only after the first open, and names the pre-Room raw-connection step if foreign keys are on during migrations ([01 S3](01-foundation.md#s3-foreign_keys-with-the-bundled-driver)).
6. Resolved: 03 deletes only the files of matched loser downloads whose winner has a `download` row ([Unsubscribe and merge](#unsubscribe-and-merge)); the `CredentialStore` entry of a deleted credential leaves the map through `CredentialDao.observeAll()`.
7. Resolved by 05 ([05 Group actions](05-groups-opml-backup.md#group-actions)): "Download all" does not exclude Up next items or the current episode.
8. Resolved by 07 (07 open question 7): protected rows count in their place in the rolling window and are never deleted; video episodes with an audio alternate are not accepted (PO-12 follow-up (a)).
9. Spike S2 results may change the [Room 2 to Room 3 mapping](#room-2-to-room-3-mapping) rows marked Unverified (Gradle extension name, `MigrationTestHelper` parameter names, `@AutoMigration`, `BEGIN IMMEDIATE` for write transactions).
10. Resolved: PLAN M2 acceptance 1 now names `QueryPlanTest`, with the GMD run on the bundled driver authoritative and the JVM run asserting rows and "no full scan" only.

## Sources

Checked 2026-10-04 unless marked otherwise.

- Room 3 releases (3.0.3; `@ColumnTypeConverter`, `withoutRowId`, custom DAO return types, Flow `InvalidationTracker`, `Uuid` converter only in 3.1.0-alpha01): https://developer.android.com/jetpack/androidx/releases/room3 · https://dl.google.com/android/maven2/androidx/room3/room3-runtime/maven-metadata.xml
- Room 3 sources (`Entity`, `Index`, `ForeignKey`, `RawQuery` in `room3/room3-common/src/commonMain/kotlin/androidx/room3/`; `LimitOffsetPagingSource` COUNT and LIMIT/OFFSET behaviour): https://github.com/androidx/androidx/blob/androidx-main/room3/room3-paging/src/commonMain/kotlin/androidx/room3/paging/LimitOffsetPagingSource.kt
- Room 3 sources checked 2026-10-05: `PrimaryKey` (`algorithm` default `AUTOINCREMENT`, `ROWID` reuses IDs) https://github.com/androidx/androidx/blob/androidx-main/room3/room3-common/src/commonMain/kotlin/androidx/room3/PrimaryKey.kt · `RoomDatabase` (`setJournalMode`, `setMultipleConnectionPool`, suspend `Callback.onCreate/onOpen(connection)`, `withReadTransaction`, `withWriteTransaction`) https://github.com/androidx/androidx/blob/androidx-main/room3/room3-runtime/src/commonMain/kotlin/androidx/room3/RoomDatabase.kt · `Migration` (`suspend fun migrate(connection)`, called inside a transaction) https://github.com/androidx/androidx/blob/androidx-main/room3/room3-runtime/src/commonMain/kotlin/androidx/room3/migration/Migration.kt · `RoomConnectionManager` (busy timeout, WAL, `synchronous = NORMAL`, `BEGIN EXCLUSIVE` around create/migrate, then `onOpen`; later connections run `onOpen` too; one retry of the first open) https://github.com/androidx/androidx/blob/androidx-main/room3/room3-runtime/src/commonMain/kotlin/androidx/room3/RoomConnectionManager.kt · `OpenDelegateWriter` (generated `onOpen` executes `PRAGMA foreign_keys = ON` when entities declare foreign keys) https://github.com/androidx/androidx/blob/androidx-main/room3/room3-compiler/src/main/kotlin/androidx/room3/writer/OpenDelegateWriter.kt · builder docs in `RoomDatabase.kt` (WAL default pool: 4 readers + 1 writer; 30 s pool timeout; `allowDataLossOnRecovery` default `false`). These are `androidx-main` (3.1 development) sources; behaviour in 3.0.3 is confirmed by spikes S2/S3
- Room migrations and `MigrationTestHelper`: https://developer.android.com/training/data-storage/room/migrating-db-versions
- SQLite drivers (`BundledSQLiteDriver` recommended): https://developer.android.com/kotlin/multiplatform/sqlite · https://developer.android.com/jetpack/androidx/releases/sqlite · https://dl.google.com/android/maven2/androidx/sqlite/sqlite-bundled/maven-metadata.xml
- Framework SQLite versions per API level (re-checked 2026-10-05): https://developer.android.com/reference/android/database/sqlite/package-summary
- Paging 3.5.1: https://dl.google.com/android/maven2/androidx/paging/paging-runtime/maven-metadata.xml
- SQLite: `PRAGMA optimize` (recommended usage since 3.46.0, re-checked 2026-10-05) https://www.sqlite.org/pragma.html#pragma_optimize · row values (3.15) https://www.sqlite.org/rowvalue.html · `VACUUM INTO` https://www.sqlite.org/lang_vacuum.html · 3.27.0 release https://www.sqlite.org/releaselog/3_27_0.html · foreign keys (DROP TABLE runs FK actions; `foreign_keys` is a no-op inside a transaction; RENAME of a parent rewrites child FKs; re-checked 2026-10-05) https://www.sqlite.org/foreignkeys.html · REPLACE conflict resolution (delete triggers only with recursive triggers) https://www.sqlite.org/lang_conflict.html
- SQLite, not re-checked: table-rebuild procedure https://www.sqlite.org/lang_altertable.html · query planner and `CROSS JOIN` https://www.sqlite.org/optoverview.html · `AUTOINCREMENT` and `sqlite_sequence` https://www.sqlite.org/autoinc.html · limits https://www.sqlite.org/limits.html
- Auto Backup (25 MB cap, include-only rules): https://developer.android.com/identity/data/autobackup
- 16 KB page sizes: https://developer.android.com/guide/practices/page-sizes
- Android 16 job quotas: https://developer.android.com/about/versions/16/behavior-changes-all · long-running workers: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running · WorkManager 2.12.0: https://developer.android.com/jetpack/androidx/releases/work
- kotlinx.serialization stream APIs are experimental (`encodeToStream`, `decodeToSequence`): https://github.com/Kotlin/kotlinx.serialization/blob/master/formats/json/jvmMain/src/kotlinx/serialization/json/JvmStreams.kt
- `podcast:guid` (UUIDv5 derivation): https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/guid.md
- Apple: keep GUIDs stable when moving feeds: https://podcasters.apple.com/support/change-the-rss-feed-url
- Prior art on database growth and loss: AntennaPod 52,000 items / 80 MB https://forum.antennapod.org/t/cleanup-of-old-unlisted-episodes/5885 · 364 MB database https://forum.antennapod.org/t/massive-battery-drainage/2685 · no pruning of removed episodes https://github.com/AntennaPod/AntennaPod/issues/4426 · database error recovery https://antennapod.org/documentation/bugs-first-aid/database-error · position-reset fix in 3.12.2 https://github.com/AntennaPod/AntennaPod/releases/tag/3.12.2 · NewPipe keeps the newest item per channel (`database/feed/dao/FeedDAO.kt`) https://github.com/TeamNewPipe/NewPipe · Podcini's storage break https://github.com/XilinJia/Podcini/blob/main/migrationTo6.md
