# 05 — Groups, OPML and backup

> Status: Draft v1, 2026-10-04 · Implements: R1.1–R1.9, R2.1–R2.9, R3.4, R4.4–R4.5 (policy resolution), R5.6 / N1, N3, N5, N9 · Milestones: M1, M2, M3, M4, M6, M8, M9, M11, M15 · Honours: D16, D20, D24, D29, D30, D31, D32, D33, D34, D35, D36, D44, D45, D66, D67; PO-11, PO-15 defaults · Owns: group semantics and lifecycle, group feeds and counts, effective-settings resolution, play-context membership, OPML export and import, the import pipeline for every file format, backup and restore, Auto Backup and file intents

Contents: [Scope](#scope) · [Group model and lifecycle](#group-model-and-lifecycle) · [Group feeds](#group-feeds) · [Effective settings resolution](#effective-settings-resolution) · [Playing a group](#playing-a-group) · [OPML export](#opml-export) · [OPML import](#opml-import) · [Other import formats](#other-import-formats) · [Full backup and restore](#full-backup-and-restore) · [Auto Backup](#auto-backup) · [Receiving files](#receiving-files) · [Settings](#settings) · [Testing](#testing) · [Error handling and failure modes](#error-handling-and-failure-modes) · [Delivery by milestone](#delivery-by-milestone) · [New names introduced here](#new-names-introduced-here) · [Open questions](#open-questions) · [Sources](#sources)

---

## Scope

Serves R1, R2, R3.4, N1, N9. Delivered in M1 (All and Podcast feeds), M2 (groups), M3 (files and backup), M4 (play contexts, playback settings), M6 (auto-download resolution), M8 (YouTube formats); see [Delivery by milestone](#delivery-by-milestone).

A group is a user-defined, many-to-many set of podcasts and YouTube channels with its own episode feed and defaults ([D29](../PLAN.md#3-key-decisions)); it is the product's differentiator. This document also owns every way subscriptions enter or leave the app as a file: OPML, the YouTube interchange formats (parsers specified by 04), the backup archive and the Auto Backup snapshot.

| Owned here | Not here (link instead) |
|---|---|
| Group semantics: name rules, palette, icon keys, order, membership edits, delete with undo, notification-channel lifecycle | Tables, columns, indices and every SQL statement — [02 podcast_group](02-data-model.md#podcast_group), [02 Key queries](02-data-model.md#key-queries) |
| `FeedSource`/`FeedFilters`/`FeedOrder` behaviour, the `FeedRepository` contract, counts window, "new since last visit", `includeInAll` | Tabs, pager and screen visuals — [08 Group feed pager](08-ui-ux.md#group-feed-pager), [08 Screens](08-ui-ux.md#screens); row overlay — [08 Live row state](08-ui-ux.md#live-row-state) |
| `EffectiveSettingsResolver` rules and attribution; `ScopeSettingsRepository` | Applying values to the player — [06 Per-scope playback settings](06-playback.md#per-scope-playback-settings); planner — [07 Auto-download policy](07-downloads.md#auto-download-policy); posting notifications — [03 New-episode notifications](03-feeds-and-discovery.md#new-episode-notifications) |
| Which episodes a play context contains and where "Play" starts (`PlayContextResolver`) | Projection, transitions, positions — [06 Queue and play context](06-playback.md#queue-and-play-context) |
| OPML writer and reader; the import pipeline (acquire, sniff, parse, classify, preview, commit, fetch, report) for every format | Refresh engine — [03 Refresh scheduling](03-feeds-and-discovery.md#refresh-scheduling); YouTube classification and NewPipe/LibreTube/Takeout/URL-list specs — [04 Import and export formats](04-youtube.md#import-and-export-formats) |
| Backup archive format, writer, validation, Merge/Replace restore | Identity-key computation — [03 Ingestion and diff](03-feeds-and-discovery.md#ingestion-and-diff); key storage and versions — [02 Identity keys](02-data-model.md#identity-keys) |
| Auto Backup rules XML, `AutoSnapshotWorker`, first-launch restore | Fresh-install detection — [02 Error handling and recovery](02-data-model.md#error-handling-and-recovery); start-up order — [01 Application start-up](01-foundation.md#application-start-up) |
| `ExternalImportActivity` filters, copy-on-receipt, `FileProvider` path `cache/export/` | `MainActivity` subscribe filters — [03 Deep links and share targets](03-feeds-and-discovery.md#deep-links-and-share-targets); merged manifest — [01 Manifest and permissions](01-foundation.md#manifest-and-permissions) |
| The portable-settings whitelist used by backups | `SettingKey` registry and DataStore files — [01 DataStore files and typed setting keys](01-foundation.md#datastore-files-and-typed-setting-keys) |

### Modules and public API

| Module | Contents from this document |
|---|---|
| `:core:model` | `Group`, `GroupDraft`, `GroupEdit`, `GroupNames`, `GroupPalette`, `GroupIcons`, `FeedTab`, `FeedCounts`, `VirtualCounts`, `FeedPrefs`, `DownloadAllEstimate`, `PlayContextSpec`, `SettingOverrides`, `SettingSource`, `Effective`, `EffectivePlayback`, `EffectiveAutoDownload`, `ImportOptions`, `ImportSessionView`, `ImportItemView`, `GroupProposal`, `BackupPreview`, `RestoreRequest`, `RestoreProgress`, `SnapshotStatus` |
| `:core:domain` | Interfaces `FeedRepository`, `GroupRepository`, `ScopeSettingsRepository`, `EffectiveSettingsResolver`, `PlayContextResolver`, `ImportRepository`, `BackupRepository`; errors `GroupError`, `ImportError`, `BackupError`, `ExportError` |
| `:feeds` (JVM) | `OpmlReader`, `OpmlWriter`, `ImportSourceSniffer`, `BackupCodec`, `ZipGuard` and their DTOs (packages `app.neutrodyne.feeds.opml`, `.backup`); 04's YouTube parsers sit beside them |
| `:core:data` | All implementations; `ImportClassifier`, `PayloadStore`, `OpmlExporter`, `BackupWriter`, `ImportFetchWorker`, `RestoreWorker`, `AutoSnapshotWorker`, `SnapshotScheduler`, `GroupNotificationChannels`, `FirstLaunchRestoreInitializer`, `ExportFilesCleaner` |
| `:feature:feeds`, `:feature:groups`, `:feature:importexport`, `:feature:podcast` | ViewModels and screens for `FeedsKey`, `GroupEditKey`, `GroupsManageKey`, `GroupSettingsKey`, `AddToGroupsKey`, `AllGroupsKey`, `ImportKey`, `BackupKey`, `ExportKey`, `PodcastSettingsKey` (visuals: 08) |
| `:app` | `ExternalImportActivity`, its manifest entries, `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`, `res/xml-v28/backup_rules.xml`, the `cache/export/` entry of `res/xml/file_paths.xml` |

```mermaid
flowchart LR
  subgraph FEAT["Feature modules"]
    FF[":feature:feeds"]
    FG[":feature:groups"]
    FIE[":feature:importexport"]
  end
  subgraph DOM[":core:domain interfaces"]
    FR["FeedRepository"]
    GR["GroupRepository"]
    SSR["ScopeSettingsRepository"]
    ESR["EffectiveSettingsResolver"]
    PCR["PlayContextResolver"]
    IR["ImportRepository"]
    BR["BackupRepository"]
  end
  subgraph DATA[":core:data"]
    IMPL["Impl classes, ImportClassifier, BackupWriter"]
    IFW["ImportFetchWorker"]
    RW["RestoreWorker"]
    ASW["AutoSnapshotWorker"]
  end
  subgraph FEEDS[":feeds (JVM)"]
    OPML["OpmlReader, OpmlWriter"]
    SN["ImportSourceSniffer, ZipGuard"]
    BC["BackupCodec"]
    YP["YouTube format parsers (04)"]
  end
  EIA["ExternalImportActivity (:app)"] --> IR
  FEAT --> DOM
  DOM -.->|"Hilt binds"| IMPL
  IMPL --> FEEDS
  IFW --> FRF["FeedRefresher (03)"]
  ESR --> CONS["06 player, 07 planner, 03 scheduler and notifier"]
  PCR --> P06["06 PlaybackController"]
```

### Threading and coroutines

All repository functions are main-safe (`suspend` or `Flow`), follow [01 Coroutines and threading](01-foundation.md#coroutines-and-threading) and never call `runCatching` in suspend code.

| Work | Context | Rule |
|---|---|---|
| DAO calls | Room query context (`@Dispatcher(IO)`) | Batch sizes and transaction rules of [02 Conventions](02-data-model.md#conventions): import commit 500 items, restore 1,000 lines per transaction |
| Copying payloads, OPML/JSON/CSV parsing, ZIP reading and writing | `@Dispatcher(IO)` (blocking streams) | Caps enforced while streaming; no `ContentResolver` call inside a transaction |
| Group proposals of a 10,000-item preview, OPML document building | `@Dispatcher(Default)` | Debounced 100 ms on item changes |
| Manual OPML and backup export | `@ApplicationScope` | Leaving the dialog does not cancel; result reported as a `UserMessage` |
| Delete-group undo window | `@ApplicationScope`, `delay(10_000)` | Process death within the window makes the delete final |
| `ImportFetchWorker`, `RestoreWorker`, `AutoSnapshotWorker` | `CoroutineWorker` (`@HiltWorker`) | Idempotent, resumable, 8-min soft deadline ([N2](../PLAN.md#22-non-functional-requirements)) |
| `ExternalImportActivity` copy | retained `ViewModel` scope | Survives rotation; must finish before the activity finishes (grant lifetime) |
| Backup and snapshot writing | one process-wide `Mutex` in `BackupWriter` | A manual backup waits for a running snapshot and vice versa |

### Platform constraints

| Constraint | Consequence here | Source |
|---|---|---|
| URI grants of VIEW/SEND intents "remain in effect while the stack of the receiving Activity is active" | Copy the payload before `finish()`; workers never read the original URI | [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider) |
| `ContentResolver.openOutputStream(uri)` uses mode `"w"`, which "may or may not truncate" | Every SAF write uses `"wt"` | [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver) |
| Neither Android MIME table maps `.opml`; file-system providers keep the requested display name only when the MIME type matches or has no mapping | `CreateDocument("text/x-opml")` (with `text/xml` the file becomes `x.opml.xml`) | [mime.types](https://android.googlesource.com/platform/external/mime-support/+/refs/heads/main/mime.types), [android.mime.types](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/mime/java-res/android.mime.types), [FileUtils](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/FileUtils.java) |
| `<data>` path attributes need scheme and host; `pathSuffix` exists from API 31; MIME and scheme matching is case-sensitive | `pathSuffix=".opml"` only on an alias enabled on API 31+ | [data element](https://developer.android.com/guide/topics/manifest/data-element) |
| Android 16 Safer Intents is opt-in and planned to become default | Our filters are fully specified; adoption is 01's decision (P27) | [Android 16 behaviour changes](https://developer.android.com/about/versions/16/behavior-changes-16) |
| Auto Backup: 25 MB per app, over quota "doesn't back up data to the cloud"; `<include>` disables the defaults; restore happens at install; a `BackupAgent` runs in restricted mode | Include-only rules for a small snapshot; no custom `BackupAgent` | [Auto Backup](https://developer.android.com/identity/data/autobackup) |
| `disableIfNoEncryptionCapabilities` (Android 12+ rules); `requireFlags="clientSideEncryption"` (Android 9+) "prevents backups from working" on 8.1 and lower; a missing section in `data-extraction-rules` means that mode is "fully enabled for all content" | PO-15 rules per API level ([Auto Backup](#auto-backup)) | [Auto Backup](https://developer.android.com/identity/data/autobackup) |
| The local backup transport must be marked encrypted (`backup_local_transport_parameters 'is_encrypted=true'`) | Test procedure | [Test backup and restore](https://developer.android.com/identity/data/testingbackup) |
| Expedited work before API 31 runs as a foreground service and needs `getForegroundInfo()`; Android 16 applies job quotas to work running beside an FGS | `import-{sessionId}` is expedited only on API 31+; every worker is resumable | [Define work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [Android 16 behaviour changes (all apps)](https://developer.android.com/about/versions/16/behavior-changes-all) |
| `dataSync` FGS covers "Import or export operations" but carries the Play declaration and the 6 h/24 h cap | Not used for imports or backups | [FGS service types](https://developer.android.com/develop/background-work/services/fgs/service-types) |
| Deleting a notification channel and recreating the same ID "un-deletes" it with its old settings; `createNotificationChannel` renames an existing channel | Channel IDs use the never-reused group `uuid` | [NotificationManager](https://developer.android.com/reference/android/app/NotificationManager) |

---

## Group model and lifecycle

Serves R2.1, R2.2, R5.6. Delivered in [M2](../PLAN.md#m2-groups-and-group-feeds). Honours [D29](../PLAN.md#3-key-decisions), [D36](../PLAN.md#3-key-decisions). Storage: [02 podcast_group](02-data-model.md#podcast_group), [02 podcast_group_member](02-data-model.md#podcast_group_member).

```kotlin
// :core:model — canonical type Group; shape owned here
data class Group(
    val id: Long, val uuid: String, val name: String, val sortOrder: Int,
    val colorArgb: Int?, val iconKey: String?,
    val feedOrder: FeedOrder, val playOrder: FeedOrder,
    val filterFlags: Int, val mediaFilter: MediaFilter, val hideOlderThanDays: Int?,
    val showAsTab: Boolean, val lastViewedAt: Long?, val createdAt: Long, val memberCount: Int,
)
data class GroupDraft(val name: String, val colorArgb: Int? = null, val iconKey: String? = null,
                      val playOrder: FeedOrder = FeedOrder.NEWEST_FIRST)  // colour null → next free palette colour
sealed interface GroupEdit {
    data class Rename(val name: String) : GroupEdit
    data class Look(val colorArgb: Int?, val iconKey: String?) : GroupEdit
    data class Orders(val feedOrder: FeedOrder, val playOrder: FeedOrder) : GroupEdit
    data class View(val filterFlags: Int, val mediaFilter: MediaFilter, val hideOlderThanDays: Int?) : GroupEdit
    data class ShowAsTab(val show: Boolean) : GroupEdit
}
```

`kind` is always `MANUAL` and `ruleJson` always `null` in v1 (smart groups reserved, [02 Reserved tables](02-data-model.md#reserved-tables)). `uuid` = `java.util.UUID.randomUUID().toString()` (lowercase), generated once and never reused ([02 Group uuid and nameKey](02-data-model.md#group-uuid-and-namekey)); notification channels and the mosaic artwork key `g-{groupUuid}` depend on it.

### Names

`GroupNames` (`:core:model`, pure) is the single implementation, used by the editor, import, restore and LibreTube group mapping.

1. Normalise to NFC.
2. Replace tabs, line and paragraph separators and every other `Cc` character with U+0020; delete the bidi controls U+202A–U+202E and U+2066–U+2069. Other format characters stay (ZWJ U+200D and variation selector U+FE0F build emoji).
3. Collapse runs of whitespace (`Char.isWhitespace` plus U+00A0, U+2007, U+202F) to one U+0020 and trim.
4. Length = `codePointCount`: 0 → `GroupError.NameEmpty`; > 40 → `GroupError.NameTooLong(40)` (code points, not graphemes, so JVM tests and every Android version agree).
5. `nameKey = NFC(normalized.lowercase(Locale.ROOT))`; a `nameKey` held by another group → `GroupError.NameTaken(groupId)`. The final NFC pass only matters where lowercasing decomposes (`İ` → `i̇`).

Examples (M2 acceptance 5): "Tech" is rejected when "tech" exists; a 41-code-point name is rejected; "🎧 Commute" is accepted; "Café" typed as `e + U+0301` collides with precomposed "Café". `GroupNames.suggestsOldestFirst(name)` is true when `nameKey` contains `fiction`, `audiobook`, `audio book`, `drama`, `serial`, `story`, `stories`, `novel`, `hörbuch` or `hörspiel`; the editor then offers "Play oldest first" ([PO-11](../PLAN.md#48-further-product-owner-decisions)), it never switches silently.

### Palette

`colorArgb` stores a seed colour; 08 maps it through HCT tones per theme, never rendering it raw ([08 Theming and colour](08-ui-ux.md#theming-and-colour)). `GroupPalette.COLORS` (order and values are stable forever):

| # | Key (content description) | ARGB | # | Key | ARGB |
|---|---|---|---|---|---|
| 0 | `red` | `0xFFF44336` | 6 | `blue` | `0xFF2196F3` |
| 1 | `deep_orange` | `0xFFFF5722` | 7 | `indigo` | `0xFF3F51B5` |
| 2 | `amber` | `0xFFFFC107` | 8 | `deep_purple` | `0xFF673AB7` |
| 3 | `green` | `0xFF4CAF50` | 9 | `pink` | `0xFFE91E63` |
| 4 | `teal` | `0xFF009688` | 10 | `brown` | `0xFF795548` |
| 5 | `cyan` | `0xFF00BCD4` | 11 | `blue_grey` | `0xFF607D8B` |

- A new group (editor, import, suggested groups) without a colour gets the first palette entry no existing group uses, in palette order; if all 12 are used, `COLORS[groupCount % 12]`.
- Colours from OPML (`nd:groupColor`) or backups that are not in the palette are kept (alpha forced to `0xFF`); the editor shows them as a "Custom" swatch. `null` (legacy) renders with the theme's primary colour.

### Icons

`iconKey` is a stable string (a Material Symbols name rendered by `:core:designsystem`), never a resource ID. Unknown keys from newer versions are stored and exported unchanged and render without an icon. Valid format `^[a-z0-9_]{1,40}$`; `null` = no icon. `GroupIcons.KEYS` (v1, 32 keys, new keys may be appended):

| Theme | Keys |
|---|---|
| News and society | `newspaper`, `public`, `account_balance`, `gavel` |
| Tech and science | `memory`, `computer`, `science`, `rocket_launch`, `psychology` |
| Stories and learning | `auto_stories`, `menu_book`, `history_edu`, `school`, `theater_comedy` |
| Culture and play | `music_note`, `movie`, `palette`, `sports_esports`, `sports_soccer` |
| Daily life | `fitness_center`, `restaurant`, `travel_explore`, `child_care`, `self_improvement`, `bedtime`, `directions_car`, `work` |
| General | `podcasts`, `smart_display`, `favorite`, `star` |

### Order

`sortOrder` is dense `0..n-1` and defines tab order, the Library groups view, OPML folder order and the "primary group" of hybrid OPML. Create appends `max + 1`; `reorder(ids)` rewrites every row in one write transaction (n ≤ a few dozen; drag reorder with `reorderable`, 08); delete compacts the remaining rows in the delete transaction. Tested to 50 groups ([N5](../PLAN.md#22-non-functional-requirements)); there is no cap.

### Membership

Many-to-many ([R2.2](../PLAN.md#21-functional-requirements)); rows are `source = MANUAL`, `addedAt = now`, member `sortOrder = 0` (manual order inside a group is reserved; group grids sort by title).

| Entry point | Navigation key | Call |
|---|---|---|
| Podcast screen group chips | `AddToGroupsKey(listOf(podcastId))` | `applyMembership(podcastIds, add, remove)` |
| Group editor cover-grid picker | `GroupEditKey(groupId)` (`null` = new) | `create(draft, memberIds)` / `setMembers(groupId, memberIds)` |
| Library multi-select "Add to group…" | `AddToGroupsKey(ids)` | `applyMembership` with tri-state chips: checked for all → `add`, cleared → `remove`, indeterminate untouched |
| Subscribe and import | — | 03's `SubscribeUseCase(groupIds)`; [Commit](#6-commit) |

Every membership write is one write transaction (`INSERT OR IGNORE` / `DELETE … WHERE groupId = ? AND podcastId IN (…)`), then: `RefreshController.reschedulePeriodic()` (the minimum refresh interval over groups may change, [03 Periodic tick](03-feeds-and-discovery.md#periodic-tick)) and `SnapshotScheduler.requestSoon()`. Auto-download and notification policies follow automatically through the resolver flows ([Effective settings resolution](#effective-settings-resolution)).

```kotlin
// :core:domain
interface GroupRepository {
    fun observeGroups(): Flow<List<Group>>                                   // ordered by sortOrder
    fun observeGroup(groupId: Long): Flow<Group?>
    fun observeGroupsOf(podcastId: Long): Flow<List<Group>>
    fun observeMemberIds(groupId: Long): Flow<Set<Long>>
    fun observeMosaics(): Flow<Map<Long, List<ArtworkRef>>>                   // 02 GroupDao.observeMosaics
    suspend fun checkName(name: String, excludingGroupId: Long? = null): Outcome<String, GroupError>
    suspend fun create(draft: GroupDraft, memberIds: Set<Long> = emptySet()): Outcome<Long, GroupError>
    suspend fun update(groupId: Long, edit: GroupEdit): Outcome<Unit, GroupError>
    suspend fun reorder(groupIdsInOrder: List<Long>): Outcome<Unit, GroupError>
    suspend fun setMembers(groupId: Long, podcastIds: Set<Long>): Outcome<Unit, GroupError>
    suspend fun applyMembership(podcastIds: Set<Long>, add: Set<Long>, remove: Set<Long>): Outcome<Unit, GroupError>
    suspend fun delete(groupId: Long): Outcome<DeletedGroupToken, GroupError>
    suspend fun undoDelete(token: DeletedGroupToken): Outcome<Long, GroupError>
}
@JvmInline value class DeletedGroupToken(val value: String)
sealed interface GroupError {
    data object NameEmpty : GroupError
    data class NameTooLong(val max: Int) : GroupError
    data class NameTaken(val groupId: Long) : GroupError
    data object NotFound : GroupError
    data object UndoExpired : GroupError
}
```

`create` and `Rename` validate with `GroupNames`; a `nameKey` unique violation inside the transaction (race) maps to `NameTaken` ([02 Error handling and recovery](02-data-model.md#error-handling-and-recovery)). `reorder` with a list that is not a permutation of the current IDs returns `NotFound` and writes nothing.

### Delete and undo

Deleting never deletes podcasts and can be undone for 10 s ([R2.1](../PLAN.md#21-functional-requirements)).

```mermaid
sequenceDiagram
  participant UI as Group menu (08)
  participant GR as GroupRepositoryImpl
  participant DB as Room
  participant CH as GroupNotificationChannels
  UI->>GR: delete(groupId)
  GR->>DB: read snapshot (group row, settings row, member ids, session context)
  GR->>DB: write transaction: delete group, compact sortOrder, clear GROUP context
  GR-->>UI: DeletedGroupToken, snackbar with Undo for 10 s
  alt Undo within 10 s
    UI->>GR: undoDelete(token)
    GR->>DB: reinsert same id and uuid, settings, members that still exist, shift sortOrder
    GR->>DB: restore session context if play_session.generation is unchanged
  else window ends or process dies
    GR->>CH: delete channel new_episodes_uuid (startup sweep covers process death)
  end
```

1. Snapshot in memory: the `podcast_group` row, its `podcast_group_settings` row, member IDs, and whether `play_session` has `contextType = GROUP AND contextId = groupId` (plus its `generation`).
2. One write transaction: `DELETE FROM podcast_group WHERE id = ?` (cascades members and settings), compact `sortOrder`, `UPDATE play_session SET contextType = NULL, contextId = NULL, contextAnchorEpisodeId = NULL, contextAnchorSortDate = NULL, generation = generation + 1` when it pointed at the group (06 keeps the current item and plays Up next, then stops).
3. Keep the snapshot under a random token for 10 s; a second delete creates a second token (independent snackbars, 08).
4. Undo: one write transaction re-inserting the row with its original `id` and `uuid` (`AUTOINCREMENT` never reused the ID), shifting `sortOrder ≥ old` by +1, re-inserting settings and memberships for podcasts that still exist, and restoring the session context only if `generation` still equals the post-delete value.
5. After the window: cancel the channel's active notification (`tag = channelId`, `id = 5000`) and delete the channel ([Notification channels](#notification-channels)); 08's `ArtworkStore` garbage collection drops `g-{uuid}` ([02 Artwork references](02-data-model.md#artwork-references)).

| Effect of deleting a group | Mechanism |
|---|---|
| Memberships, group settings | FK `ON DELETE CASCADE` |
| Podcasts, episodes, user state, downloads | untouched |
| Playing from the group | context cleared in the same transaction; current item continues |
| Feeds tab selected | 08 falls back to All with a snackbar (M2 acceptance 6) |
| Effective settings of former members | resolver flows re-emit; refresh tick rescheduled; 07's planner reacts |
| Notification channel `new_episodes_{uuid}` | deleted after the undo window |
| Mosaic artwork `g-{uuid}` | unreferenced → collected |
| Import history (`import_item.groupNamesJson`) | unaffected (names, not IDs) |

### Notification channels

Channel posting is 03's ([03 New-episode notifications](03-feeds-and-discovery.md#new-episode-notifications)); the lifecycle is here, in `GroupNotificationChannels` (`:core:data`).

| Event | Action |
|---|---|
| A group's `notifyNewEpisodes` becomes `true` | `createNotificationChannel(new_episodes_{uuid}, name = group name, importance DEFAULT, group grp_new_episodes)` |
| Group renamed | `createNotificationChannel` with the same ID and the new name (supported for user renames) |
| `notifyNewEpisodes` set to `false` or `null` | delete the channel; switching it back on "un-deletes" it with the user's sound and importance |
| Group deleted | delete after the undo window |
| Start-up (`AppInitializer` order 10, [01](01-foundation.md#application-start-up)) and `sync()` after settings writes | create missing channels for groups whose own setting is `true`; delete every `new_episodes_*` channel whose UUID is not such a group |

The channel group `grp_new_episodes` and the default channel `new_episodes` are created by 03's `ensureChannels()`. The AOSP per-app limit of 5,000 channels is irrelevant at our scale ([PreferencesHelper](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/notification/PreferencesHelper.java)).

---

## Group feeds

Serves R2.3, R2.4 (data side), R2.5, R2.8, R2.9, R3.4. Delivered in [M1](../PLAN.md#m1-subscribe-and-ingest-rss) (All and Podcast sources) and [M2](../PLAN.md#m2-groups-and-group-feeds) (everything else). Honours [D16](../PLAN.md#3-key-decisions), [D30](../PLAN.md#3-key-decisions). SQL, indices and EXPLAIN expectations: [02 Feed pages](02-data-model.md#feed-pages), [02 Feed counts](02-data-model.md#feed-counts).

```kotlin
// :core:domain — canonical members first
interface FeedRepository {
    fun pagedFeed(source: FeedSource, filters: FeedFilters, order: FeedOrder): Flow<PagingData<EpisodeRow>>
    fun observeGroupCounts(sinceMs: Long): Flow<Map<Long, FeedCounts>>        // groups without episodes → zeros
    fun observeVirtualCounts(sinceMs: Long): Flow<VirtualCounts>              // All and Ungrouped
    fun observeTabs(): Flow<List<FeedTab>>
    fun observePrefs(source: FeedSource): Flow<FeedPrefs>
    suspend fun setFilters(source: FeedSource, filters: FeedFilters)          // minSortDate ignored, see hide-older
    suspend fun setHideOlderThanDays(source: FeedSource, days: Int?)
    suspend fun setFeedOrder(source: FeedSource, order: FeedOrder)
    suspend fun markVisited(source: FeedSource, leftAt: Long)
    suspend fun countUnplayed(source: FeedSource, sortDateBefore: Long?): Int // "Mark all played" confirmation
    suspend fun downloadAllEstimate(source: FeedSource): DownloadAllEstimate  // "Download all unplayed"
}
// :core:model
data class FeedTab(val source: FeedSource, val title: String, val colorArgb: Int?, val iconKey: String?,
                   val groupUuid: String?)
data class FeedCounts(val unplayed: Int, val newSinceVisit: Int)
data class VirtualCounts(val all: FeedCounts, val ungrouped: FeedCounts)
data class FeedPrefs(val feedOrder: FeedOrder, val playOrder: FeedOrder, val filters: FeedFilters,
                     val hideOlderThanDays: Int?, val lastViewedAt: Long?)
data class DownloadAllEstimate(val episodeIds: List<Long>, val totalCandidates: Int, val knownBytes: Long,
                               val unknownSizeCount: Int, val capped: Boolean)
```

The implementation (`FeedRepositoryImpl`, `:core:data`) builds queries only through `FeedQueryBuilder` (or 02's generated fallback, selected behind this interface by spike S1) and maps `EpisodeRowProjection` to `EpisodeRow` with `PagingData.map`.

### Sources and tabs

| Source | Contains | Order | Persisted view prefs |
|---|---|---|---|
| `FeedSource.All` | every episode of podcasts with `includeInAll = 1` | `NEWEST_FIRST` (fixed in v1) | `groups.all_*` keys ([Settings](#settings)) |
| `FeedSource.Group(id)` | every episode of the group's members, membership evaluated live | `podcast_group.feedOrder` | columns of `podcast_group` |
| `FeedSource.Ungrouped` | episodes of podcasts in no group (ignores `includeInAll`) | `NEWEST_FIRST` (fixed) | `groups.ungrouped_*` keys |
| `FeedSource.Podcast(id)` | the podcast's episodes (podcast screen, Auto browse) | `podcast.episodeOrder ?: (SERIAL → OLDEST_FIRST else NEWEST_FIRST)` | order in `podcast.episodeOrder`; filters transient (ViewModel) |

Every source applies 02's `VISIBLE` fragment (Shorts unless opted in, upcoming, live, members-only, [04 Content flags and filtering](04-youtube.md#content-flags-and-filtering)). An episode of a podcast in "tech" and "news" appears in both group feeds and once in All; played state is per episode, so marking it in one feed marks it everywhere.

`observeTabs()` emits, in order: All (always); groups with `showAsTab = true` by `sortOrder`; Ungrouped when `groups.show_ungrouped_tab` is on **and** at least one podcast is in no group. The "All groups" sheet (`AllGroupsKey`) lists every group including hidden ones. Persisting the selected tab and the deleted-group fallback are 08's ([08 Group feed pager](08-ui-ux.md#group-feed-pager)).

### Filters, order and hide-older-than

| View setting | Stored as | Becomes |
|---|---|---|
| Unplayed only | bit `UNPLAYED = 1` of `filterFlags` | `FeedFilters.unplayedOnly` |
| Downloaded only | bit `DOWNLOADED = 2` | `downloadedOnly` |
| In progress only | bit `IN_PROGRESS = 4` | `inProgressOnly` (`startedAt IS NOT NULL AND playedAt IS NULL`) |
| Audio / video | `mediaFilter` | `media` |
| Hide older than N days | `hideOlderThanDays` ∈ {null, 1, 3, 7, 14, 30, 90, 365} | `minSortDate = floorToHour(now) − N·86 400 000` |
| Newest / oldest first | `feedOrder` | `order` |

`observePrefs` recomputes `minSortDate` on every emission and hourly while collected; rounding to the hour keeps the `RoomRawQuery` stable so `flatMapLatest` does not rebuild the pager on every tick. Filter chips write through `setFilters` immediately (one low-churn row update; `podcast_group` is not observed by the feed `PagingSource`, so the write does not invalidate the open list — the new `Pager` replaces it). `setFilters` and `setFeedOrder` on `FeedSource.Podcast` throw `IllegalArgumentException` for filters (transient) and write `podcast.episodeOrder` for order.

### Paging hand-off to 08

- One `Pager` per **visible** page: the Feeds ViewModel keeps at most 3 cached flows (current page ± 1) keyed by `(source, filters, order)` in an LRU and never builds pagers for every tab.
- `PagingConfig(pageSize = 40, prefetchDistance = 40, initialLoadSize = 80, enablePlaceholders = true, maxSize = 400)`; flows are built with `flatMapLatest` over `observePrefs(source)` and `.cachedIn(viewModelScope)` ([01 ViewModels and UI state](01-foundation.md#viewmodels-and-ui-state)).
- Lists use `itemKey { it.id }` and `itemContentType { if (it.sourceType == SourceType.YOUTUBE_CHANNEL) 1 else 0 }` (16:9 thumbnails vs square covers, 08).
- Positions, download progress and now-playing never come from the paged row ([D16](../PLAN.md#3-key-decisions)); rows overlay them from `EpisodeLiveStateSource` ([08 Live row state](08-ui-ux.md#live-row-state)).
- Pull-to-refresh calls `RefreshController.refreshFeed(source)` ([03 Refresh scheduling](03-feeds-and-discovery.md#refresh-scheduling)).

### Counts and new since last visit

[R2.8](../PLAN.md#21-functional-requirements). Counts are bounded by a window so an imported back catalogue cannot produce "12,000 unplayed".

- `sinceMs = floorToHour(now) − 30 d`; a group with `hideOlderThanDays < 30` uses its own bound (02's SQL takes the larger date). The window constant is `GroupCounts.WINDOW_DAYS = 30` (not a user setting). The repository re-evaluates `sinceMs` hourly while collected.
- **Unplayed** = visible, `availability = 'AVAILABLE'` (04's requested change to 02), `playedAt IS NULL`, inside the window. YouTube episodes count in both flavors (04 answer to 02).
- **New since last visit** = unplayed **and** `isNew = 1` **and** `firstSeenAt > COALESCE(lastViewedAt, createdAt)`. `isNew` is never set by initial fetches, imports or restores ([03 isNew and back-catalogue guard](03-feeds-and-discovery.md#isnew-and-back-catalogue-guard)), so a fresh import shows no "new" badges.
- All and Ungrouped are counted separately (an episode in two groups counts once in All); their `lastViewedAt` lives in `device_settings` (`groups.all_last_viewed_at`, `groups.ungrouped_last_viewed_at`) because "since last visit" is a per-device notion. When absent it is initialised to `now` on first read.

**Visit rule.** A visit starts when a feed page becomes the settled pager page while the Feeds destination is resumed, and ends when another page settles, the destination leaves composition or the app goes to the background. A visit of ≥ 1 s calls `markVisited(source, leftAt = now)`, which writes `podcast_group.lastViewedAt` (or the `device_settings` key). The ViewModel keeps the value read at visit start as the baseline for the "New" row highlight (`row.isNew && row.firstSeenAt > baseline`), so rows stay highlighted during the visit. `lastViewedAt` is device-local: it is not in backups and restored groups start with `lastViewedAt = now`.

### includeInAll and Ungrouped

`podcast.includeInAll` (default 1, toggled as "Show in All" in podcast settings through 03's `PodcastRepository.setIncludeInAll`) hides a high-volume show (an hourly bulletin) from the All feed and All counts only; its groups, its podcast screen, Ungrouped and play contexts other than All still include it ([PO-11](../PLAN.md#48-further-product-owner-decisions): per-podcast switch only, no per-group "hide from All").

### Group actions

[R2.6](../PLAN.md#21-functional-requirements). Menu items on a group (feed header overflow and Library group tile).

| Action | Behaviour | Calls | Milestone |
|---|---|---|---|
| Refresh this group | only member podcasts, forced | `RefreshController.refreshNow(RefreshScope.Group(id))` | M2 |
| Play | [Playing a group](#playing-a-group) | `PlaybackController.playFeed(Group(id), prefs.filters, prefs.playOrder, null)` | M4 |
| Mark all as played | options: all, older than 1 week, 1 month, 3 months; confirmation shows `countUnplayed(source, before)` ("Mark 214 episodes in 'tech' as played?"); no undo | `EpisodeRepository.markFeedPlayed(source, sortDateBefore)` (03; SQL [02 User-state writes](02-data-model.md#user-state-writes)) | M2 |
| Download all unplayed | confirmation "Download 37 episodes (about 1.9 GB, 3 sizes unknown)?"; metered prompt per 07 | `downloadAllEstimate(source)` → `DownloadController.request(ids, DownloadLane.MANUAL, allowMetered = null)` | M6 |
| Share as OPML | per-group export | `ExportKey(groupId)` ([OPML export](#opml-export)) | M3 |
| Import OPML into this group | picker; session created with this group as target | `ImportRepository.create(source, CreateImportOptions(targetGroupId = id))` | M3 |
| Edit, Group settings, Delete | editor, settings screen, [Delete and undo](#delete-and-undo) | `GroupEditKey(id)`, `GroupSettingsKey(id)` | M2 |

`downloadAllEstimate`: the group's context candidates ([Playing a group](#playing-a-group) membership rules, no anchor) that have no `download` row in `QUEUED`…`COMPLETED`, no tombstone (`downloadDismissedAt IS NULL`; the user deleted those deliberately), and are downloadable (`enclosureUrl IS NOT NULL`, or YouTube when `YouTubeCapabilities.downloads`), newest first, **capped at 200** (`capped = true` → "200 newest of 1,234"). Size per episode: `enclosureLength` if > 0, else `durationMs × 16 000 B/s` (128 kbit/s) if known, else counted in `unknownSizeCount`; no invented numbers.

### Performance

Budgets are [R2.9](../PLAN.md#21-functional-requirements): first page (COUNT + 80 rows) ≤ 60 ms for a group, ≤ 100 ms for All on the reference device with 300 podcasts, 50,000 episodes and 20 groups; counts for 20 groups ≤ 50 ms (recorded). They are measured by 02's `FeedQueryTimingTest`. If All misses its budget with LIMIT/OFFSET, only All switches to a keyset `PagingSource` keyed by `(sortDate, id)` behind `pagedFeed` (risk [T6](../PLAN.md#8-risks-and-mitigations)); callers do not change.

---

## Effective settings resolution

Serves R2.7, R4.4, R4.5. Delivered in M2 (refresh interval, notifications), M4 (speed, skip silence), M6 (auto-download, delete after played), M9 (YouTube auto-download globals). Honours [D20](../PLAN.md#3-key-decisions), [D45](../PLAN.md#3-key-decisions). Storage: `podcast_settings` and `podcast_group_settings` share 02's `ScopeOverrides` columns; `null` = inherit ([02 podcast_settings](02-data-model.md#podcast_settings)). There are no per-episode overrides in v1.

### Rules

First match wins. "Groups" means the podcast's member groups whose column is non-null.

| Setting | Column | Resolution | Global fallback (key owner) | From |
|---|---|---|---|---|
| Playback speed | `playbackSpeed` | podcast → **context group**: the group of `play_session` when `contextType = GROUP` and the episode's podcast is a member of it (also for Up next items) → global | `playback.*` speed key (06) | M4 |
| Skip silence | `skipSilence` | same as speed | `playback.*` (06) | M4 |
| Volume boost (reserved) | `boostDb` | same as speed | 06 (M12) | M12 |
| Intro / outro skip (reserved) | `introSkipMs`, `outroSkipMs` | podcast → 0; group columns ignored (intros are per show) | — | M12 |
| Auto-download | `autoDownload` | podcast → `true` if any group says `true`; `false` if groups set it and none says `true` → global | `downloads.*` (07); `YOUTUBE_CHANNEL`: `youtube.auto_download` (04) | M6, M9 |
| Keep latest N | `autoDownloadKeepLatest` | podcast → **max** over groups → global | `downloads.*` (07); YouTube: `youtube.auto_download_keep_latest` | M6 |
| Network | `autoDownloadNetwork` | podcast → **most restrictive** (`UNMETERED` beats `ANY`) → global | `downloads.*` (07) | M6 |
| Require charging | `autoDownloadRequireCharging` | podcast → `true` if any group says `true` → global | `downloads.*` (07) | M6 |
| Include video | `includeVideoInAutoDownload` | podcast → `false` if any group says `false` → global | `downloads.*` (07) | M6 |
| Delete after played | `deleteAfterPlayed` | podcast → **least aggressive** (`NEVER` > `AFTER_24H` > `IMMEDIATELY`) → global; applies to manual and auto downloads ([PO-12](../PLAN.md#48-further-product-owner-decisions)) | `downloads.*` (07) | M6 |
| New-episode notifications | `notifyNewEpisodes` | podcast → `true` if any group says `true`; `false` if groups set it and none says `true` → global | `feeds.notify_new_episodes` (03) | M2 |
| Refresh interval | `refreshIntervalMinutes` ∈ {60, 120, 240, 480, 720, 1440} | podcast → **min** over groups → global (0 = manual only = infinite) | `feeds.refresh_interval_minutes` (03) | M2 |

Additional rules:

1. **Dependent auto-download fields.** In a group, `autoDownloadKeepLatest`, `autoDownloadNetwork`, `autoDownloadRequireCharging` and `includeVideoInAutoDownload` are editable only while that group's `autoDownload = true`; setting `autoDownload` to `false` or `null` clears them in the same write. So the merges above only ever combine groups that enable auto-download. `deleteAfterPlayed` is independent. Podcast-level fields are independent (they also shape a group-enabled download).
2. **Capability.** For `YOUTUBE_CHANNEL` podcasts with `YouTubeCapabilities.downloads == false` (`play`, and `foss` before M9) auto-download resolves to `false` with source `NotSupported`; network, charging and delete-after use the `downloads.*` globals ([04 Auto-download for YouTube](04-youtube.md#auto-download-for-youtube)).
3. **Video.** YouTube episodes have `isVideo = false` (audio-only, D52), so "include video" never blocks them.
4. **Validation** on write: speed 0.5–3.0 in 0.05 steps; keep 1–10; refresh interval in the set above. Out-of-range values read from a backup are dropped (inherit).

### Attribution

Every settings screen shows the effective value and its source ([R2.7](../PLAN.md#21-functional-requirements)). Strings live in `:core:ui` (08 owns layout):

| `SettingSource` | Text |
|---|---|
| `Podcast` | "Set for this podcast" |
| `Group(groupId, name)` | "From group 'news'" (player: "1.5× (from group 'news')", M4 acceptance 7) |
| `Groups(groups)` (a merge decided by several groups) | "From groups 'tech' and 'news'"; three or more: "From 3 groups" (tap lists them). Only the groups that determined the value are listed (for `max`, the groups holding the maximum) |
| `AppDefault` | "App default" |
| `YouTubeDefault` | "YouTube default" |
| `NotSupported` | "Not available for YouTube channels" (row hidden by 08 where the flavor matrix says so) |

### API

```kotlin
// :core:model
sealed interface SettingSource {
    data object Podcast : SettingSource
    data class Group(val groupId: Long, val name: String) : SettingSource
    data class Groups(val groups: List<Pair<Long, String>>) : SettingSource
    data object AppDefault : SettingSource
    data object YouTubeDefault : SettingSource
    data object NotSupported : SettingSource
}
data class Effective<out T>(val value: T, val source: SettingSource)
data class EffectivePlayback(val speed: Effective<Float>, val skipSilence: Effective<Boolean>, val boostDb: Effective<Float>)
data class EffectiveAutoDownload(
    val podcastId: Long, val enabled: Effective<Boolean>, val keepLatest: Effective<Int>,
    val network: Effective<NetworkPolicy>, val requireCharging: Effective<Boolean>,
    val includeVideo: Effective<Boolean>, val deleteAfterPlayed: Effective<DeleteAfter>,
)
// :core:domain (canonical interface, owned here)
interface EffectiveSettingsResolver {
    suspend fun playback(podcastId: Long, contextGroupId: Long?): EffectivePlayback
    fun observePlayback(podcastId: Long, contextGroupId: Long?): Flow<EffectivePlayback>
    suspend fun autoDownload(podcastIds: Collection<Long>): Map<Long, EffectiveAutoDownload>
    fun observeAutoDownload(): Flow<Map<Long, EffectiveAutoDownload>>        // every podcast; distinctUntilChanged
    suspend fun notifications(podcastIds: Collection<Long>): Map<Long, Effective<Boolean>>
    suspend fun refreshIntervals(): Map<Long, Effective<Int?>>               // null = manual only
}
```

`contextGroupId` is passed by 06 as `play_session.contextId` when `contextType = GROUP`; the resolver itself checks membership and ignores the group otherwise. The implementation (`:core:data`) reads `ScopeSettingsDao` rows for the podcasts and their groups in one query, the globals from `SettingsRepository`, `podcast.sourceType`, and `YouTubeCapabilities`. Observing flows combine Room flows over `podcast_settings`, `podcast_group_settings`, `podcast_group_member`, `podcast` with the DataStore flows.

| Caller (owner) | When | Call |
|---|---|---|
| 06 `PlayerFactory`/`QueueProjector` | before the first `prepare()` and on every item transition | `playback(podcastId, contextGroupId)` |
| 06 `PlaybackStateSource.effectivePlayback` | while a session exists | `observePlayback(…)` |
| 07 `AutoDownloadPlanner` | after refresh events; on every emission of `observeAutoDownload()` (debounced 2 s, 07) | `autoDownload(ids)` / `observeAutoDownload()` |
| 07 cleanup | daily and after playback completes | `autoDownload(ids).deleteAfterPlayed` |
| 03 `RefreshScheduler` and `FeedRefresher` | tick computation; `nextRefreshAt` per feed | `refreshIntervals()` |
| 03 `NewEpisodeNotifier` | once per engine run | `notifications(ids)`; channel choice is 03's (first notifying group by `sortOrder`) |

### Writing overrides

```kotlin
// :core:model — mirror of 02's ScopeOverrides (all nullable)
data class SettingOverrides(
    val playbackSpeed: Float? = null, val skipSilence: Boolean? = null, val boostDb: Float? = null,
    val introSkipMs: Long? = null, val outroSkipMs: Long? = null,
    val autoDownload: Boolean? = null, val autoDownloadKeepLatest: Int? = null,
    val autoDownloadNetwork: NetworkPolicy? = null, val autoDownloadRequireCharging: Boolean? = null,
    val deleteAfterPlayed: DeleteAfter? = null, val includeVideoInAutoDownload: Boolean? = null,
    val notifyNewEpisodes: Boolean? = null, val refreshIntervalMinutes: Int? = null,
)
// :core:domain
interface ScopeSettingsRepository {
    fun observePodcast(podcastId: Long): Flow<ScopedSettingsView>        // override + effective + source per field
    fun observeGroup(groupId: Long): Flow<ScopedSettingsView>            // override + inherited global value
    suspend fun updatePodcast(podcastId: Long, change: (SettingOverrides) -> SettingOverrides): Outcome<Unit, SettingsError>
    suspend fun updateGroup(groupId: Long, change: (SettingOverrides) -> SettingOverrides): Outcome<Unit, SettingsError>
}
```

- Writes are a read-modify-write inside one write transaction (`@Upsert` is allowed on these single-writer tables, [02 DAO rules](02-data-model.md#dao-rules)); a row whose fields are all `null` is deleted. Rule 1 (dependent fields) and validation run before the write.
- After a write: refresh interval changed → `RefreshController.reschedulePeriodic()`; `notifyNewEpisodes` changed → `GroupNotificationChannels.sync()` (the screen requests `POST_NOTIFICATIONS` per 03's permission rule); always → `SnapshotScheduler.requestSoon()`. 07 and 06 observe the resolver flows; nothing else is pushed.
- 06's scoped commands (`PlaybackController.setSpeed(speed, scope)`, `nd.SPEED_SET_SCOPE`, `nd.SKIP_SILENCE`) write through this repository: `PODCAST` → `updatePodcast`, `GROUP` → `updateGroup(contextGroupId)` (rejected when the context is not a group), `GLOBAL` → `SettingsRepository`.
- On the podcast settings screen, playback rows show the podcast override or the global value, plus a hint listing member groups with their own value ("While playing from 'news': 1.5×").

---

## Playing a group

Serves R2.6, R3.7, R4.8. Delivered in [M4](../PLAN.md#m4-playback-core). Honours [D44](../PLAN.md#3-key-decisions) ([PO-11](../PLAN.md#48-further-product-owner-decisions) default: keep Up next first). This section defines **which** episodes a play context contains and **where** it starts; the query is 02's ([02 Play context](02-data-model.md#play-context)), projection and transitions are 06's ([06 Queue and play context](06-playback.md#queue-and-play-context)).

```kotlin
// :core:model
data class PlayContextSpec(
    val type: ContextType, val contextId: Long?, val order: FeedOrder,
    val filterFlags: Int, val mediaFilter: MediaFilter,
    val minSortDate: Long?,                 // fixed when the context starts (play_session.contextMinSortDate)
    val boundStartBySubscription: Boolean,  // OLDEST_FIRST groups only
)
// :core:domain
interface PlayContextResolver {
    suspend fun spec(source: FeedSource, filters: FeedFilters, order: FeedOrder): PlayContextSpec
    suspend fun downloadsSpec(): PlayContextSpec
    suspend fun startItem(spec: PlayContextSpec): Long?     // null = nothing to play
}
```

### Context per entry point

| Entry point | `ContextType` / `contextId` | Order | Filters carried into the context | `minSortDate` |
|---|---|---|---|---|
| Group feed: "Play" or a row's play button | `GROUP` / groupId | `podcast_group.playOrder` | the group's view filters: `DOWNLOADED`, `IN_PROGRESS`, `mediaFilter` (`UNPLAYED` is implied) | from `hideOlderThanDays` |
| All feed | `ALL` / null | `NEWEST_FIRST` | All view filters | from `groups.all_hide_older_than_days` |
| Ungrouped feed | `UNGROUPED` / null | `NEWEST_FIRST` | Ungrouped view filters | from `groups.ungrouped_hide_older_than_days` |
| Podcast screen | `PODCAST` / podcastId | effective `episodeOrder` | the screen's transient filters | null |
| Downloads screen "Play all" | `DOWNLOADS` / null | `NEWEST_FIRST` | completed downloads only (all podcasts, ignores `includeInAll`) | null |
| A single episode with no feed (episode detail from a notification, search, preview) | `EXTERNAL` | — | no tail | — |

### Membership of the context tail

An episode is in the tail iff **all** hold (evaluated live on every projector query):

1. Source predicate: group membership, `includeInAll = 1` (All only), no membership (Ungrouped), podcast, or `download.state = COMPLETED` (Downloads).
2. Not played (`playedAt IS NULL`), not in Up next, not the current item.
3. `VISIBLE` and `availability = 'AVAILABLE'` (YouTube premieres, live, members-only, unavailable reasons are skipped, [R3.8](../PLAN.md#21-functional-requirements)).
4. YouTube episodes only when `YouTubeCapabilities.inAppPlayback` (never in `play`, [R3.7](../PLAN.md#21-functional-requirements)).
5. The carried filters and `sortDate ≥ minSortDate`.
6. Strictly after the anchor `(contextAnchorSortDate, contextAnchorEpisodeId)` in the context order; **a null anchor means "from the beginning of the order"** (02's query without the row-value predicate).

### Start rules

1. **Row tapped (explicit start):** current = that episode, anchor = that episode; Up next is kept and plays next, then the tail continues after the anchor.
2. **"Play" without a start and Up next non-empty:** current = the head of Up next (06 moves it out of Up next per its rules), anchor = null, so after Up next the tail starts at the beginning of the order (M4 acceptance 6: two Up next items, then tech's unplayed episodes newest first).
3. **"Play" without a start and Up next empty:** current = `startItem(spec)`, anchor = that item.
4. **`startItem`:** the first tail item from the beginning of the order (02's start-item query). For `GROUP` with `OLDEST_FIRST` (`boundStartBySubscription = true`) the first search adds `e.sortDate >= p.subscribedAt`, so "Play fiction" does not start at a 2011 episode of a show subscribed this year; if that finds nothing, a second search runs without the bound (only the back catalogue is left unplayed). `PODCAST` with `OLDEST_FIRST` never uses the bound (serial shows start at episode 1).
5. `startItem == null` and Up next empty → `PlaybackController.playFeed` returns an outcome the UI shows as "Nothing unplayed in 'tech'"; in `play`, a group whose unplayed items are all YouTube shows "Episodes in 'tech' open in YouTube".

```mermaid
sequenceDiagram
  participant UI as Group feed (08)
  participant PC as PlaybackController (06)
  participant CR as PlayContextResolver (05)
  participant Q as QueueRepository (06)
  participant S as NeutrodynePlaybackService
  UI->>PC: playFeed(Group(id), prefs.filters, prefs.playOrder, startEpisodeId = null)
  PC->>CR: spec(source, filters, order)
  CR-->>PC: PlayContextSpec(GROUP, id, order, flags, minSortDate)
  alt Up next empty
    PC->>CR: startItem(spec)
    CR-->>PC: episodeId or null
  end
  PC->>Q: write play_session (current, context, anchor), generation + 1
  PC->>S: MediaController.play()
  S->>Q: observeVirtualQueue(K = 20) using the tail rules
```

Edge cases:

- New episodes arriving while playing: `NEWEST_FIRST` contexts keep moving towards older items, so newer arrivals are not inserted behind the anchor; the next "Play" starts with them. `OLDEST_FIRST` contexts pick them up at the end.
- A podcast removed from the group drops out of the tail at the next projector query; the current item keeps playing. Deleting the group clears the context ([Delete and undo](#delete-and-undo)).
- The context group's playback settings apply to every item whose podcast is a member, including Up next items ([Rules](#rules)).

---

## OPML export

Serves R1.4, R1.5, R1.6, R1.9. Delivered in [M3](../PLAN.md#m3-import-export-and-backup) (YouTube attributes and NewPipe JSON in [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds)). Honours [D31](../PLAN.md#3-key-decisions). Spec: [OPML 2.0](http://opml.org/spec2.opml).

### Model and writer

`OpmlExporter` (`:core:data`) reads podcasts, groups and memberships in one read transaction, builds an `ExportDocument` on `Default`, and calls the pure writer in `:feeds` (which knows no `:core:model` types).

```kotlin
// :feeds — app.neutrodyne.feeds.opml
data class ExportDocument(val title: String, val createdAt: Long, val groups: List<ExportGroup>,
                          val feeds: List<ExportFeed>)                 // groups in sortOrder
data class ExportGroup(val key: String, val name: String, val colorArgb: Int?, val iconKey: String?)
data class ExportFeed(
    val displayTitle: String,   // COALESCE(customTitle, title)
    val feedTitle: String, val xmlUrl: String, val htmlUrl: String?, val language: String?,
    val groupKeys: List<String>,        // member groups in sortOrder; first = primary group
    val youtube: Boolean, val ytVariants: Int?,
)
enum class OpmlLayout { GROUPED, FLAT }
object OpmlWriter { fun write(out: OutputStream, doc: ExportDocument, layout: OpmlLayout) }  // UTF-8, never throws on content
```

The writer is hand-written string output (no `XmlSerializer`: Android's throws `IllegalArgumentException` on characters outside XML 1.0, [KXmlSerializer](https://android.googlesource.com/platform/libcore/+/refs/heads/main/xml/src/main/java/com/android/org/kxml2/io/KXmlSerializer.java), and `android.util.Xml` does not exist in a JVM module). Exporting 1,000 feeds takes well under 100 ms; no progress UI.

### Structure

**Grouped (hybrid, default):**

1. `<?xml version="1.0" encoding="UTF-8"?>`, `<opml version="2.0" xmlns:nd="urn:neutrodyne:opml:1">`.
2. `<head>`: `<title>` ("Neutrodyne subscriptions", or "Neutrodyne: {group name}" for a group export), `<dateCreated>` in RFC 822 with a four-digit year and `GMT` (`EEE, dd MMM yyyy HH:mm:ss 'GMT'`, `Locale.ROOT`), `<docs>http://opml.org/spec2.opml</docs>`.
3. `<body>`: one folder outline per group in `sortOrder`, **including empty groups** (`<outline text="fiction" title="fiction"/>`), so names and order round-trip.
4. Each feed appears **exactly once**, inside the folder of its primary group (lowest `sortOrder`), with `category` listing **all** its groups in `sortOrder`.
5. Ungrouped feeds follow the folders at top level.
6. Inside a folder and among ungrouped feeds, feeds are sorted by `displayTitle` with `Collator.getInstance(Locale.ROOT)` at `SECONDARY` strength, ties by `xmlUrl` (deterministic golden files).

**Flat:** no folder outlines; every feed at top level in the same sort order, `category` kept. Flat output preserves groups and memberships for Neutrodyne and tag-aware readers, but **not** empty groups, group order, colours or icons (documented in the dialog: "Groups are kept as tags only").

### Attributes

| Outline | Attribute | Value |
|---|---|---|
| Folder | `text`, `title` | group name, raw (only XML-escaped) |
| Folder | `nd:groupColor` | `#RRGGBB` uppercase hex of `colorArgb` (omitted when null) |
| Folder | `nd:groupIcon` | `iconKey` (omitted when null) |
| Feed | `type` | always `rss` (also Atom and YouTube; the spec has no Atom value) |
| Feed | `text` | `displayTitle` |
| Feed | `title` | `feedTitle` |
| Feed | `xmlUrl` | `podcast.feedUrl` (no userinfo); with "Include passwords" `https://user:pass@host/…` (user and password percent-encoded per RFC 3986 userinfo rules) |
| Feed | `htmlUrl` | `podcast.link` if set; YouTube: `https://www.youtube.com/channel/{UC…}` |
| Feed | `language` | `podcast.language` if set |
| Feed | `category` | group names joined by `,`; inside each name `%` → `%25`, `,` → `%2C`, `/` → `%2F` (so a name never splits and never reads as a path); omitted for ungrouped feeds |
| Feed | `nd:source` | `rss` or `youtube` |
| Feed | `nd:ytVariants` | YouTube only: set bits in the fixed order `UULF,UUSH,UULV` ([04 OPML](04-youtube.md#opml)) |

Not written: `description` (bloat), `version`, `created`, Neutrodyne IDs or UUIDs. **Escaping** in every attribute and text node: `&` `&amp;`, `<` `&lt;`, `>` `&gt;`, `"` `&quot;`, newline `&#10;`, CR `&#13;`, tab `&#9;`. Before escaping, strip characters invalid in XML 1.0 (`U+0000–U+0008`, `U+000B`, `U+000C`, `U+000E–U+001F`, `U+FFFE`, `U+FFFF`) and unpaired surrogates, so one bad title can never abort an export.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0" xmlns:nd="urn:neutrodyne:opml:1">
  <head>
    <title>Neutrodyne subscriptions</title>
    <dateCreated>Sun, 04 Oct 2026 21:30:00 GMT</dateCreated>
    <docs>http://opml.org/spec2.opml</docs>
  </head>
  <body>
    <outline text="tech" title="tech" nd:groupColor="#2196F3" nd:groupIcon="memory">
      <outline type="rss" text="ATP" title="Accidental Tech Podcast" xmlUrl="https://atp.fm/rss"
               htmlUrl="https://atp.fm" category="tech,news" nd:source="rss"/>
      <outline type="rss" text="Marques Brownlee" title="Marques Brownlee"
               xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ"
               htmlUrl="https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ" category="tech"
               nd:source="youtube" nd:ytVariants="UULF"/>
    </outline>
    <outline text="news" title="news" nd:groupColor="#F44336">
      <outline type="rss" text="The Daily" title="The Daily" xmlUrl="https://feeds.example.com/daily"
               category="news" nd:source="rss"/>
    </outline>
    <outline text="fiction" title="fiction" nd:groupColor="#673AB7" nd:groupIcon="auto_stories"/>
    <outline type="rss" text="Some Show" title="Some Show" xmlUrl="https://example.com/rss" nd:source="rss"/>
  </body>
</opml>
```

Why hybrid: AntennaPod's reader visits every outline with `xmlUrl` at any depth, Pocket Casts scans `xmlUrl=` line by line and dedupes, gPodder and FreshRSS recreate folders (FreshRSS uses the innermost folder and would turn a flat `category="tech,news"` into one category "tech, news"), so nesting is safe and writing each feed once avoids duplicates everywhere ([AntennaPod OpmlReader](https://github.com/AntennaPod/AntennaPod), [Pocket Casts OpmlUrlReader](https://github.com/Automattic/pocket-casts-android), [gPodder opml.py](https://github.com/gpodder/gpodder), [FreshRSS ImportService](https://github.com/FreshRSS/FreshRSS/blob/edge/app/Services/ImportService.php)).

### Options and warnings

`ExportKey(groupId: Long?)` dialog (`:feature:importexport`; visuals 08):

| Option | Default | Remembered in |
|---|---|---|
| Format: Grouped / Flat list | Grouped | `backup.opml_layout` |
| Include YouTube channels ("Other podcast apps may not be able to play these") | on | `backup.opml_include_youtube` |
| Include passwords for private feeds | off, every time | never remembered |
| NewPipe JSON (YouTube channels only, M8; format: [04 NewPipe subscriptions JSON](04-youtube.md#newpipe-subscriptions-json-import-and-export)) | — | — |

- With YouTube excluded, a group whose members are all channels is written as an empty folder (grouped).
- **Private-URL warning** ([R1.9](../PLAN.md#21-functional-requirements)): before writing, count feeds where 03's `PrivateFeedUrls.looksPrivate(xmlUrl)` is true ([03 Private feed URLs](03-feeds-and-discovery.md#private-feed-urls)); if N > 0 show "This file contains private access links for N feeds. Anyone with the file can listen to them." (Continue / Cancel). With passwords included, a second line: "It also contains N passwords in plain text."
- Passwords come from `CredentialStore` at write time (03); podcasts whose credential cannot be decrypted are exported without userinfo.

### Destinations

| Destination | Mechanism |
|---|---|
| Save to file | `rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml"))` with name `neutrodyne-subscriptions-{yyyy-MM-dd}.opml` (group: `neutrodyne-{slug}-{yyyy-MM-dd}.opml`, slug = `nameKey` with non-`[a-z0-9]` runs → `-`, ≤ 40 chars, fallback `group`). Write the document to `cacheDir/export/` first, then copy to `openOutputStream(uri, "wt")` (if the provider rejects `"wt"` with `IllegalArgumentException` or `FileNotFoundException`, retry with `"w"` — Unverified which providers do). Snackbar "Saved" with "Share" |
| Share | Write `cacheDir/export/{file name}`, URI from `FileProvider` authority `${applicationId}.fileprovider`, `ACTION_SEND` with type `text/x-opml`, `EXTRA_STREAM`, `EXTRA_TITLE`, `ClipData.newRawUri(name, uri)`, `FLAG_GRANT_READ_URI_PERMISSION`, `Intent.createChooser` |

`res/xml/file_paths.xml` (`:app`) contains `<cache-path name="export" path="export/"/>` (07 adds the download roots to the same file). `ExportFilesCleaner` (an `AppInitializer`, order 300) deletes files in `cacheDir/export/` older than 24 h; each export also deletes older files with the same name.

**Single-group export** ([R1.5](../PLAN.md#21-functional-requirements)): `ExportKey(groupId)` writes one folder for that group with all its members (YouTube per the option) and `category` = that group only; Flat writes the members with `category` = that group. The OPML inside a full backup is the grouped export without passwords.

---

## OPML import

Serves R1.1, R1.2, R1.3, R1.6, N9. Delivered in [M3](../PLAN.md#m3-import-export-and-backup); YouTube items become importable in [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds). Honours [D24](../PLAN.md#3-key-decisions), [D32](../PLAN.md#3-key-decisions), [D66](../PLAN.md#3-key-decisions). Tables: [02 import_session](02-data-model.md#import_session), [02 import_item](02-data-model.md#import_item); commit SQL: [02 Import commit](02-data-model.md#import-commit). The same pipeline serves every format in [Other import formats](#other-import-formats).

```mermaid
sequenceDiagram
  participant U as User
  participant E as ExternalImportActivity or picker
  participant I as ImportRepository
  participant DB as Room
  participant W as ImportFetchWorker
  participant R as FeedRefresher (03)
  U->>E: open, share or pick a file
  E->>I: create(ImportSource)
  I->>I: copy to cacheDir/import, sniff, parse, classify
  I->>DB: import_session PREVIEW and import_item rows
  I-->>E: sessionId
  E->>U: ImportKey(sessionId) preview
  U->>I: confirm(sessionId)
  I->>DB: commit chunks of 500 (pending podcasts, groups, memberships, aliases)
  I->>W: enqueue import-sessionId
  W->>R: run(Podcasts(ids), origin IMPORT)
  R-->>W: FeedRunEvent per podcast
  W->>DB: item statuses, treat-as-played, session DONE
  W-->>U: report notification on import_backup
```

```kotlin
// :core:domain — canonical interface, owned here
interface ImportRepository {
    suspend fun create(source: ImportSource, options: CreateImportOptions = CreateImportOptions()): Outcome<Long, ImportError>
    fun observeSession(sessionId: Long): Flow<ImportSessionView?>
    fun pagedItems(sessionId: Long, filter: ImportItemFilter): Flow<PagingData<ImportItemView>>
    fun observeGroupProposals(sessionId: Long): Flow<List<GroupProposal>>
    suspend fun setSelected(sessionId: Long, ordinals: Set<Int>?, selected: Boolean)   // null = all selectable
    suspend fun updateOptions(sessionId: Long, change: (ImportOptions) -> ImportOptions)
    suspend fun confirm(sessionId: Long): Outcome<Unit, ImportError>
    suspend fun cancel(sessionId: Long)                       // PREVIEW: delete; FETCHING: stop reporting
    suspend fun retry(sessionId: Long, ordinals: Set<Int>)
    suspend fun editUrl(sessionId: Long, ordinal: Int, input: String): Outcome<Unit, AddPodcastError>
    suspend fun enterPassword(sessionId: Long, ordinal: Int, credentials: BasicCredentials): Outcome<Unit, AddPodcastError>
    suspend fun remove(sessionId: Long, ordinals: Set<Int>)  // unsubscribes the created podcasts
    fun observeOpenSessions(): Flow<List<ImportSessionView>>  // PREVIEW and FETCHING, for a resume banner (08)
}
data class ImportSource(val uri: String, val displayName: String?)          // canonical: content:// or https://
data class CreateImportOptions(val targetGroupId: Long? = null)              // "Import OPML into this group"
```

### Session and item states

```mermaid
stateDiagram-v2
  [*] --> PREVIEW: create
  PREVIEW --> [*]: cancel deletes session and payload
  PREVIEW --> COMMITTED: confirm (commit transactions done)
  COMMITTED --> FETCHING: ImportFetchWorker starts
  FETCHING --> DONE: no item QUEUED
  FETCHING --> CANCELLED: user stops checking
  DONE --> FETCHING: Retry or Edit URL or Enter password
  DONE --> [*]: db-maintenance after 7 days
  CANCELLED --> [*]: db-maintenance after 7 days
```

| Item status | Set when | Selectable in preview | Report section |
|---|---|---|---|
| `PREVIEW` | new valid entry | yes | — |
| `ALREADY_SUBSCRIBED` | `feedKey` or alias match | no (memberships still apply) | "Already subscribed (groups updated)" |
| `DUPLICATE_IN_FILE` | same identity as an earlier entry; its group names are merged into that entry | no | "Not imported" |
| `INVALID_URL` | no http(s) URL after normalisation, or an unsupported service (NewPipe `service_id ≠ 0`) | no | "Not imported" |
| `YOUTUBE_UNSUPPORTED_YET` | YouTube item before M8, or a `PL…` playlist (`errorDetail = "playlist"`) | no | "Not imported" |
| `QUEUED` | committed, not yet fetched (or retried) | — | progress |
| `SUBSCRIBED` | first ingest succeeded (also an empty but valid feed) | — | "Done" |
| `MERGED` | redirect or alias identity matched an existing podcast ([03 Podcast dedupe and merge](03-feeds-and-discovery.md#podcast-dedupe-and-merge)) | — | "Merged with existing" |
| `NOT_A_FEED` | `NOT_A_FEED`, `PARSE_ERROR`, `UNSUPPORTED_LIST_FEED` (after one autodiscovery attempt, [03 Refresh of pending podcasts](03-feeds-and-discovery.md#refresh-of-pending-podcasts)) | — | "Need attention" |
| `NO_MEDIA` | items but none playable (blog feed from a reader's OPML) | — | "No audio or video found" with "Remove all" |
| `AUTH_REQUIRED` | `HTTP_AUTH` | — | "Need attention" (Enter password) |
| `GONE` | `HTTP_GONE`, or `HTTP_NOT_FOUND` for RSS | — | "Need attention" |
| `FETCH_FAILED` | any other failure; `errorDetail` = `FeedErrorKind` name or YouTube resolution failure | — | "Need attention" |

Unselected `PREVIEW` items are never committed and stay `PREVIEW` (shown under "Not imported"). Failed podcasts stay subscribed with an error badge (AntennaPod 3.1 lesson, [forum](https://forum.antennapod.org/t/import-issues-with-opml/2676)).

### 1. Acquire

`PayloadStore` (`:core:data`) copies the source before anything else:

1. `content://`: query `OpenableColumns.DISPLAY_NAME` and `SIZE`; reject `SIZE > 50 MiB` (`ImportError.TooLarge`); copy through a counting stream that aborts at 50 MiB (sizes lie) to `cacheDir/import/incoming-{random}.tmp`.
2. `https://` ("Import from URL" box, or 03's `AddPodcastError.SubscriptionList(url)` "Import it" action): GET with 01's FEED client (03's timeouts, no credentials; `LocalNetworkGuardDns` applies), same 50 MiB cap.
3. After the session row exists, rename to `cacheDir/import/{sessionId}.bin` and store `payloadPath = "import/{sessionId}.bin"`.
4. A `SecurityException` or `FileNotFoundException` while reading → `ImportError.Unreadable` ("Couldn't open this file. Try choosing it from inside Neutrodyne.").

### 2. Sniff and parse

`ImportSourceSniffer` (`:feeds`) decides the format from bytes, never from MIME type or extension ([Sniffing](#sniffing)). For `OPML`, `OpmlReader` runs a cascade over the payload file (re-opened per pass):

```kotlin
// :feeds — app.neutrodyne.feeds.opml
data class OpmlLimits(val maxDepth: Int = 32, val maxFeeds: Int = 10_000, val maxOutlines: Int = 200_000,
                      val maxAttrChars: Int = 8_192)
enum class ParseMode { STRICT, RELAXED, SALVAGE }
data class FolderRef(val id: Int, val name: String, val parentId: Int?, val colorArgb: Int?, val iconKey: String?)
data class OpmlEntry(
    val ordinal: Int, val url: String, val text: String?, val title: String?, val htmlUrl: String?,
    val type: String?, val folderId: Int?,          // immediate parent folder
    val categories: List<String>,                   // decoded tokens, last path segment
    val preselect: Boolean,                         // false inside isComment, subscribed="0", type="link"
    val ndSource: String?, val ndYtVariants: String?,
)
data class OpmlDocument(val entries: List<OpmlEntry>, val folders: List<FolderRef>, val headTitle: String?,
                        val neutrodyne: Boolean, val mode: ParseMode, val ignoredOutlines: Int)
sealed interface OpmlFailure { data object EntityDeclared : OpmlFailure; data object TooDeep : OpmlFailure
    data object TooManyFeeds : OpmlFailure; data object TooManyOutlines : OpmlFailure; data object NoOutlines : OpmlFailure }
object OpmlReader { fun read(open: () -> InputStream, limits: OpmlLimits = OpmlLimits()): Result<OpmlDocument> }
```

Parser setup (03's `PullParserFactory` and `PrologGuard`, [03 Parser](03-feeds-and-discovery.md#parser)): namespace processing **off** (prefixed attributes keep raw names such as `nd:source`), `FEATURE_PROCESS_DOCDECL` off, `setInput(stream, null)` so KXml detects BOMs and the declared encoding (never a `Reader`, which would ignore `encoding="ISO-8859-1"`), leading ASCII whitespace skipped while preserving a BOM ([KXmlParser](https://android.googlesource.com/platform/libcore/+/refs/heads/main/xml/src/main/java/com/android/org/kxml2/io/KXmlParser.java)). `PrologGuard` rejects any `<!ENTITY` in the prolog (`EntityDeclared`): no XXE, no billion laughs.

1. **Strict** pass. On `XmlPullParserException` → 2.
2. **Relaxed** pass (`http://xmlpull.org/v1/doc/features.html#relaxed` = true; tolerates `&nbsp;`, some unquoted attributes). On failure → 3.
3. **Salvage**: a streaming scanner reads the decoded text in 64 KiB chunks, extracts each `<outline …>` tag (≤ 64 KiB per tag), and parses its attributes with `(\w[\w:.-]*)\s*=\s*("([^"]*)"|'([^']*)')`, unescaping the five predefined entities and numeric references. Folders are lost; `category` survives (it is an attribute), so groups of our own exports usually survive too. `mode = SALVAGE` → warning "This file is damaged; folders could not be read" ([R1.1](../PLAN.md#21-functional-requirements)).

Walk rules (strict and relaxed):

- Element names and attribute names are matched case-insensitively (a lowercase attribute map per outline: `xmlurl`, `url`, `text`, `title`, `htmlurl`, `type`, `category`, `iscomment`, `subscribed`, `nd:source`, `nd:ytvariants`, `nd:groupcolor`, `nd:groupicon`); `type` values are lowercased.
- An outline with `xmlUrl` — or with `url` when `type` is `rss` or `link` and the URL does not end in `.opml` (gPodder fallback) — is a **feed**, regardless of `type` (AntennaPod writes `type="atom"`). Its children are ignored (Overcast extended nests `podcast-episode` outlines in feeds).
- Outlines with `type` ∈ {`podcast-episode`, `podcast-playlist`, `include`} or `type="link"` with a `.opml` URL are **ignored** with their subtree (counted in `ignoredOutlines`, warning "12 entries were ignored: episode lists"). `include`/`link` lists are never fetched.
- Any other outline is a **folder**; its name is `text ?: title`, trimmed.
- `isComment="true"` marks the subtree `preselect = false` (not dropped); Overcast `subscribed="0"` and `type="link"` entries are `preselect = false`.
- Caps: depth > 32 → `TooDeep`; > 10,000 feeds → `TooManyFeeds`; > 200,000 outlines → `TooManyOutlines`; an attribute longer than 8,192 chars drops that outline with a warning. Each failure rejects the file with a clear message (M3 acceptance 3). `neutrodyne = true` when the root has `xmlns:nd="urn:neutrodyne:opml:1"`.
- `category` tokens: split on `,`; trim; for a token containing `/` keep the last non-empty segment (`/Harvard/Berkman` → `Berkman`, spec "slash-delimited category strings"); decode only `%25`, `%2C`, `%2F` (case-insensitive hex).

### 3. Classify

`ImportClassifier` (`:core:data`, because it needs `:youtube:api`) turns each entry into an `import_item`:

1. `YouTubeUrlClassifier.classify(url)`, then `classify(htmlUrl)` ([04 OPML](04-youtube.md#opml)): `Channel` → `kind = YOUTUBE`, `normalizedUrl` = canonical `https://www.youtube.com/feeds/videos.xml?channel_id={UC…}`, variants from `nd:ytVariants` > URL prefix hint > 1; `Handle`/`LegacyPath`/`Video` → `kind = YOUTUBE`, `normalizedUrl = null` (resolved by the worker, M8); `Playlist` → `YOUTUBE_UNSUPPORTED_YET`. Before M8 every YouTube item is `YOUTUBE_UNSUPPORTED_YET` ("YouTube channel — supported in a later build").
2. Otherwise 03's `AddInputNormalizer.normalize(url)` ([03 Input normalisation](03-feeds-and-discovery.md#input-normalisation)): `feed:`/`itpc:`/`pcast:`/`podcast:` schemes, scheme-less → `https://`, subscribe-page wrappers unwrapped; anything not http(s) → `INVALID_URL`. Userinfo stays in `originalUrl` until commit (the payload file holds it anyway); `normalizedUrl` never contains it.
3. Identity = `UrlNormalizer.forIdentity(normalizedUrl)` ([03 URL normalisation](03-feeds-and-discovery.md#url-normalisation)). Within the file, a repeated identity → `DUPLICATE_IN_FILE`, its group names unioned into the first entry (exports that repeat feeds per folder).
4. Already subscribed: identity against `podcast.feedKey` and `podcast_url_alias.url` → `ALREADY_SUBSCRIBED` with `podcastId`, unselected.
5. Title for display and for the pending row: Neutrodyne files: `title`, and `text` becomes `customTitle` when it differs; other files: `title ?: text ?: host` (gPodder writes the description into `text`).
6. Pre-selection: `PREVIEW` items are selected unless `preselect = false`. The preview never loads covers (10,000 rows would hit the network); rows show monograms.

### 4. Group mapping

[R1.2](../PLAN.md#21-functional-requirements). Each entry's group names = **`category` tokens ∪ {immediate parent folder}**, each normalised with `GroupNames` (empty dropped; > 40 code points truncated with a warning). Proposals are deduplicated by `nameKey`; the first-seen spelling wins unless an existing group has that key, whose name wins. Proposal order = folders in document order, then category-only names in order of first appearance; new groups are appended after existing groups in that order (our grouped export therefore reproduces group order).

Proposals excluded by default (the preview's group card shows each with a switch):

- **Wrapper folder.** Let T = top-level outlines that are feeds, or folders whose subtree contains a feed. The single folder in T is a wrapper when T has exactly one element, the file is not a Neutrodyne export, and either its `nameKey` ∈ {`feeds`, `subscriptions`, `podcasts`, `podcast feeds`} or it contains a sub-folder with feeds. Pocket Casts' `feeds` and Overcast's `feeds` (beside a feed-less `playlists`) are wrappers; a one-group share named "tech" is not. Shown as "'feeds' looks like a container, not a group".
- **gPodder default sections**: when `<head><title>` starts with "gPodder", folders named `audio`, `video` or `other` (gPodder derives them from content type, [opml.py](https://github.com/gpodder/gpodder)).

```kotlin
// :core:model — stored as import_session.optionsJson (02 JSON column, shape owned here)
@Serializable data class ImportOptions(
    val importGroups: Boolean = true,                           // master switch "Import folders as groups"
    val excludedGroupKeys: Set<String> = emptySet(),            // wrapper and gPodder sections pre-filled
    val renamedGroups: Map<String, String> = emptyMap(),        // nameKey → new name (validated)
    val targetGroupId: Long? = null, val targetGroupName: String? = null,  // "Put everything into group…"
    val treatExistingAsPlayed: Boolean = false,                 // D66 toggle
    val notifyNewEpisodes: Boolean = false,                     // writes podcast_settings.notifyNewEpisodes = true
    val restoreMode: RestoreMode? = null,                       // backup sessions only
    val restoreCategories: Set<RestoreCategory> = emptySet(),
    val committedAt: Long? = null, val attemptMarkerAt: Long? = null,   // worker bookkeeping
)
```

Neutrodyne exports: folder `nd:groupColor`/`nd:groupIcon` are applied to **newly created** groups only (existing groups keep their look). Formats without groups (NewPipe, Takeout, URL list) pre-fill the target group per 04's rule ([04 Pipeline rules for YouTube items](04-youtube.md#pipeline-rules-for-youtube-items-05-implements)). "Import OPML into this group" sets `targetGroupId` and `importGroups = false`.

### 5. Preview contract

`ImportKey(sessionId)` (`:feature:importexport`, visuals: [08 Screens](08-ui-ux.md#screens)) renders these models; everything survives process death because it is in `import_session`/`import_item`.

```kotlin
// :core:model
data class ImportSessionView(
    val id: Long, val sourceName: String?, val format: ImportFormat, val state: ImportState,
    val recoveredBySalvage: Boolean, val warnings: List<ImportWarning>, val options: ImportOptions,
    val counts: Map<ImportItemStatus, Int>, val selectedCount: Int, val youtubeCount: Int, val createdAt: Long,
)
data class ImportItemView(
    val ordinal: Int, val title: String, val host: String, val kind: ImportItemKind, val status: ImportItemStatus,
    val selected: Boolean, val selectable: Boolean, val groupNames: List<String>, val isPrivate: Boolean,
    val errorDetail: String?, val podcastId: Long?,
)
data class GroupProposal(val nameKey: String, val name: String, val memberCount: Int,
                         val existingGroupId: Long?, val included: Boolean, val excludedReason: String?)
@Serializable data class ImportWarning(val code: String, val count: Int = 0, val detail: String? = null)
enum class ImportItemFilter { ALL, NEW_ONLY, NOT_IMPORTED, NEEDS_ATTENTION }
```

Header: "142 podcasts and 3 YouTube channels in *antennapod-feeds-2026-10-01.opml*" plus warnings (`SALVAGED`, `IGNORED_OUTLINES`, `TRUNCATED_NAMES`, `LINKED_LISTS`). Rows: monogram, title, host only (never the full URL: tokens, N3), chips YouTube / Already subscribed / Duplicate / Invalid / Private feed (`PrivateFeedUrls.looksPrivate`). Controls: search, "Only new", select all/none, sticky "Subscribe to 139". Options: "Treat existing episodes as played (except the newest per podcast)" and "Notify me about new episodes for these podcasts", both off ([canonical defaults](#settings)). `pagedItems` needs `ImportDao.pagedItems(sessionId, filter)` returning a `PagingSource` (requested from 02). Proposals are computed in Kotlin from `groupNamesJson` (02 forbids SQL inside JSON).

### 6. Commit

`confirm(sessionId)` ([R1.3](../PLAN.md#21-functional-requirements): pending podcasts in the library within 2 s for 300 feeds):

1. Outside any transaction: for selected items whose `originalUrl` has userinfo, `CredentialStore.put(origin, user, password)` ([03 Basic auth and CredentialStore](03-feeds-and-discovery.md#basic-auth-and-credentialstore)).
2. Resolve the final group set: included proposals (renamed) when `importGroups`, plus the target group (created if `targetGroupName`).
3. Chunks of 500 items, each one write transaction (02's `ImportDao.commitChunk`): create missing groups (`nameKey` conflict = reuse; colour/icon per [Palette](#palette) or `nd:` attributes; `createdAt = now`, `lastViewedAt = now`); insert `PENDING_FIRST_FETCH` podcasts with `initialFetch = 1`, `nextRefreshAt = now`, `subscribedAt = now`, `title`, `customTitle`, `artworkKey = m-{sha1hex(feedKey)}`, `credentialId`, YouTube columns; aliases (reason `IMPORT`) for an original identity that differs from `feedKey`; memberships for new **and** already-subscribed items (M3 acceptance 7); `podcast_settings.notifyNewEpisodes = true` when the option is on; item → `QUEUED` with `podcastId`; `originalUrl` rewritten to `Redactor.url(originalUrl)` (01) so no password stays in the table.
4. `options.committedAt = attemptMarkerAt = now`; state `COMMITTED`; enqueue `import-{sessionId}`; `RefreshController.reschedulePeriodic()`; `GroupNotificationChannels.sync()`.
5. Items needing YouTube resolution (M8) stay `QUEUED` without `podcastId`; the worker inserts them ([04 Pipeline rules](04-youtube.md#pipeline-rules-for-youtube-items-05-implements)).

### 7. Fetch

`ImportFetchWorker` (`:core:data`, `@HiltWorker`): unique work `import-{sessionId}`, policy `KEEP`, tag `import`, constraint `NetworkType.CONNECTED`, `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)` **on API 31+ only** (below 31 expedited work needs a foreground notification; 03 made the same choice for `refresh-now`), backoff linear 30 s. It never runs as a foreground service.

```
doWork():
  session = load; if state ∉ {COMMITTED, FETCHING} → success
  state = FETCHING; deadline = elapsedRealtime + 8 min
  (M8) resolve QUEUED YouTube items without podcastId: YouTubeChannelResolver.resolve(ref, ID_ONLY),
       2 concurrent, 0.5–1.5 s jitter; insert each podcast in its own transaction; failure → FETCH_FAILED
  loop:
    derive(): for each QUEUED item whose podcast.lastAttemptAt ≥ options.attemptMarkerAt → final status
    pending = QUEUED items with podcastId and no attempt since the marker
    if pending empty → break
    if remaining < 30 s → return retry
    report = FeedRefresher.run(RefreshRequest(Podcasts(pending), force = true, pagesOnly = false,
                                              origin = IMPORT, deadlineElapsedMs = deadline))
    apply report.outcomes (and FeedRunEvents seen) → statuses
    OFFLINE or CANCELLED outcomes stay QUEUED → return retry (network constraint holds the work)
    if report.stoppedByDeadline → return retry
  after 20 attempts (runAttemptCount) remaining QUEUED → FETCH_FAILED("not_attempted")
  state = DONE, finishedAt = now; post report notification; SnapshotScheduler.requestSoon()
```

`force = true` because import fetches are user-initiated; a periodic run that already ingested an imported podcast is detected by `derive()` (the attempt marker), so it is not fetched twice. 03's engine applies 6 parallel / 2 per host (also `youtube.com`), `INITIAL` ingestion (no `isNew`, no notifications, no auto-download — M3 acceptance 4) and the pending-podcast rules ([03 Refresh of pending podcasts](03-feeds-and-discovery.md#refresh-of-pending-podcasts)).

Status derivation (from the `FeedOutcome`, or from the persisted podcast row when the outcome was not observed):

| Outcome / row | Status |
|---|---|
| `Ingested(firstIngest)`, or row `ACTIVE` with `lastErrorKind = null` | `SUBSCRIBED` |
| `Merged(into)`, or the item's identity is now an alias with reason `MERGE` | `MERGED` (`podcastId` = winner, updated by 02's merge) |
| `HTTP_AUTH` / `needsCredentials = 1` | `AUTH_REQUIRED` |
| `HTTP_GONE`, `HTTP_NOT_FOUND` (RSS) / `gone = 1` | `GONE` |
| `NO_MEDIA` | `NO_MEDIA` |
| `NOT_A_FEED`, `PARSE_ERROR`, `UNSUPPORTED_LIST_FEED` | `NOT_A_FEED` |
| `OFFLINE`, `CANCELLED` | stays `QUEUED` |
| anything else (incl. YouTube 404, which is transient) | `FETCH_FAILED` with `errorDetail = kind` |

**Treat existing as played** ([D66](../PLAN.md#3-key-decisions)): in the same transaction that sets `SUBSCRIBED`, when the option is on, run 02's "played except newest" pair for that podcast restricted to `e.isNew = 0` ([02 User-state writes](02-data-model.md#user-state-writes); the `isNew` restriction is requested from 02 so a genuinely new episode ingested meanwhile stays unplayed). The status transition happens once, so this runs at most once per podcast.

### 8. Report and fix-ups

The progress screen observes `ImportDao.observeProgress` ("Fetched 87 of 139"). At `DONE` the worker posts one notification on channel `import_backup` (ID 3001, tag `import-{sessionId}`): "139 added, 3 need attention", tap → `neutrodyne://open/import/{sessionId}` ([01 Intent routing](01-foundation.md#intent-routing)); skipped while that session's screen is resumed, and when `POST_NOTIFICATIONS` is not granted (never requested for imports).

| Action | Effect |
|---|---|
| Retry | `PodcastRepository.retry(podcastId)` semantics without its own refresh (clear `gone`/`needsCredentials`), items → `QUEUED`, `attemptMarkerAt = now`, state `FETCHING`, enqueue `import-{sessionId}` |
| Edit URL | `PodcastRepository.editFeedUrl(podcastId, input)` (03 applies a move and ingests); success → `SUBSCRIBED` (or `MERGED`), failure → error shown inline |
| Enter password | `PodcastRepository.setCredentials(podcastId, credentials)`; item → `QUEUED`, marker, enqueue worker (03's own refresh and ours dedupe through the marker) |
| Remove / Remove all | `UnsubscribeUseCase(ids)` (03), then delete the `import_item` rows |
| Re-download (backup sessions, M6) | see [After restore](#after-restore) |

### 9. Session lifetime

`PREVIEW` cancel deletes the session and the payload immediately. 02's `db-maintenance` step 3 deletes sessions in `DONE`, `CANCELLED` or abandoned `PREVIEW` with `COALESCE(finishedAt, createdAt)` older than 7 days and their payloads ([02 db-maintenance worker](02-data-model.md#db-maintenance-worker)). Until `db-maintenance` ships (M11), `AutoSnapshotWorker` runs the same `ImportDao` cleanup as its first step (M3). `ExternalImportActivity` sessions created after the user left get a "ready to review" notification (ID 3002) instead of being lost ([Receiving files](#receiving-files)).

---

## Other import formats

Serves R1.6, R1.7. Delivered in [M3](../PLAN.md#m3-import-export-and-backup) (sniffing, backups, YouTube reported as unsupported) and [M8](../PLAN.md#m8-youtube-subscriptions-in-all-builds) (YouTube formats). Format specifications and parsers: [04 Import and export formats](04-youtube.md#import-and-export-formats); this section is how they enter the pipeline.

### Sniffing

`ImportSourceSniffer.sniff(open: () -> InputStream): SniffResult` (`:feeds`) reads at most the first 64 KiB after a BOM, except for ZIPs (central directory, below):

| First bytes / content | Result |
|---|---|
| `PK\x03\x04` | ZIP: an entry named exactly `manifest.json` whose `format` is `neutrodyne-backup` → `NEUTRODYNE_BACKUP`; else any entry ending `.csv` (case-insensitive) → `TAKEOUT_CSV`; else `Unsupported("zip")` |
| `1F 8B` (gzip) | `Unsupported("tgz")` with 04's message ("choose the .zip file type") |
| `{` | JSON (≤ 10 MB): top-level `subscriptions` array whose objects have `service_id` → `NEWPIPE_JSON`; `localSubscriptions`, `groups`/`channelGroups` or `"format":"Piped"` → `LIBRETUBE_JSON`; else `Unsupported("json")` |
| `<` | XML: first element `opml` → `OPML`; `rss`, `feed`, `RDF` → `Unsupported("single_feed")` ("This is a podcast feed, not a list" with a "Subscribe" action handing the URL to `AddPodcastKey`); otherwise, when `<outline` occurs → `OPML` (salvage) |
| text, first line 3 comma-separated columns, second line starts `UC` | `TAKEOUT_CSV` |
| text, ≥ 80 % of non-empty non-`#` lines are URLs, `UC…` IDs or `@handles` | `URL_LIST` (M8; `ImportFormat.URL_LIST` requested by 04 and accepted here) |
| anything else | `Unsupported("unknown")` → "This doesn't look like a podcast list" |

### Adapters

Every parser yields `ImportEntry`s that go through [Classify](#3-classify), [Group mapping](#4-group-mapping), preview, commit and fetch unchanged.

```kotlin
// :feeds — app.neutrodyne.feeds.importing
data class ImportEntry(val ordinal: Int, val url: String, val title: String?, val customTitle: String?,
                       val htmlUrl: String?, val groupNames: List<String>, val preselect: Boolean,
                       val youtubeVariantsHint: Int?)
data class ImportDocument(val format: String, val entries: List<ImportEntry>, val groupLooks: Map<String, Pair<Int?, String?>>,
                          val groupOrder: List<String>, val salvaged: Boolean, val warnings: List<String>)
```

| `ImportFormat` | Parser (spec owner) | Groups | Notes |
|---|---|---|---|
| `OPML` | `OpmlReader` (here) | folders ∪ `category` | [OPML import](#opml-import) |
| `NEWPIPE_JSON` | `NewPipeSubscriptions.parse` (04) | none → target group "YouTube" pre-filled | other `service_id`s → `INVALID_URL` (`newpipe_service:{id}`) |
| `LIBRETUBE_JSON` | `LibreTubeBackupParser` (04) | LibreTube groups, order = `index`; channels only listed in a group are imported too | names through `GroupNames` |
| `TAKEOUT_CSV` | `TakeoutSubscriptionsParser` (04), CSV or ZIP | none → "YouTube" pre-filled | RFC 4180; localised header skipped |
| `URL_LIST` (M8) | `UrlListParser` (04) | none → "YouTube" pre-filled only if every item is YouTube | ≤ 1 MB, ≤ 5,000 lines |
| `NEUTRODYNE_BACKUP` | `BackupCodec` | from the archive | creates a session **without** `import_item` rows and routes to the restore preview ([Full backup and restore](#full-backup-and-restore)) |

### Archive guard

`ZipGuard` (`:feeds`) wraps `java.util.zip.ZipFile` over the local payload copy (random access; never extracts to disk, so entry names are never paths — no zip-slip):

| Cap | Backup | Takeout ZIP |
|---|---|---|
| Entries | ≤ 16, and only whitelisted names are read | ≤ 50 `.csv` entries scanned |
| Uncompressed per entry | ≤ 128 MiB | ≤ 5 MB |
| Uncompressed total | ≤ 256 MiB | ≤ 250 MB |
| Ratio | an entry whose inflated bytes exceed 100 × its compressed size and 16 MiB aborts | same |

Declared sizes in the central directory are not trusted: every entry is read through a counting stream that throws `ZipGuardException` at the cap (M3 acceptance 3: zip bomb and zip-slip names rejected without crash or OOM).

---

## Full backup and restore

Serves R1.7, R1.9, N1, N9. Delivered in [M3](../PLAN.md#m3-import-export-and-backup); re-download offer in [M6](../PLAN.md#m6-downloads). Honours [D33](../PLAN.md#3-key-decisions), [D66](../PLAN.md#3-key-decisions). Export and matching SQL: [02 Backup export](02-data-model.md#backup-export), [02 Restore matching](02-data-model.md#restore-matching).

### Archive

File `neutrodyne-backup-{yyyy-MM-dd-HHmm}.zip`, MIME `application/zip`, DEFLATE level 6. Everything is keyed by stable identities — `feedKey` (+ aliases, real `podcastGuid`), group `uuid`, episode `identityKey` with `kv` — never by row IDs ([02 Local row IDs](02-data-model.md#local-row-ids)).

| Entry (write order) | Content | Manual | Snapshot | Required on read |
|---|---|---|---|---|
| `library.json` | podcasts (identity, user fields, aliases, settings), groups (look, view prefs, settings, members), optional credentials | yes | yes | yes |
| `episodes.jsonl` | one line per episode with user state, queued or current: stub fields + state | yes | yes (size guard) | yes (may be empty) |
| `queue.json` | Up next refs and the play session | yes | yes | yes |
| `settings.json` | whitelisted portable settings with types | yes | yes | yes |
| `subscriptions.opml` | grouped OPML, no passwords — usable by other apps | yes | no | no |
| `manifest.json` | format, versions, counts, per-entry size + SHA-256 + records | yes (last) | yes (last) | yes |

The manifest is written last because it carries the hashes; readers open the local payload copy with `ZipFile` (random access), so position does not matter.

```kotlin
// :feeds — app.neutrodyne.feeds.backup. Json: write encodeDefaults = false, explicitNulls = false;
// read ignoreUnknownKeys = true; no polymorphic type chosen by the file.
@Serializable data class BackupManifest(
    val format: String = "neutrodyne-backup", val formatVersion: Int = 1, val minReaderVersion: Int = 1,
    val createdAt: String,                                   // ISO-8601 UTC (for humans)
    val kind: BackupKind, val app: AppInfoV1, val dbSchemaVersion: Int,
    val episodeKeysVersion: Int, val urlNormalizerVersion: Int, val installationId: String,
    val snapshotLevel: Int = 0, val includesCredentials: Boolean = false,
    val counts: CountsV1, val entries: List<EntryInfo>,
)
@Serializable enum class BackupKind { MANUAL, AUTO_SNAPSHOT, SCHEDULED }
@Serializable data class AppInfoV1(val versionName: String, val versionCode: Long, val flavor: String)
@Serializable data class CountsV1(val podcasts: Int, val groups: Int, val episodeLines: Int, val played: Int,
                                  val inProgress: Int, val upNext: Int, val settings: Int)
@Serializable data class EntryInfo(val name: String, val bytes: Long, val sha256: String, val records: Int)
@Serializable data class LibraryV1(val podcasts: List<PodcastV1>, val groups: List<GroupV1>,
                                   val credentials: List<CredentialV1> = emptyList())
@Serializable data class PodcastV1(
    val key: String, val feedUrl: String, val source: String = "RSS",         // feedKey; SourceType name
    val aliases: List<String> = emptyList(), val podcastGuid: String? = null,  // real GUIDs only
    val youtubeChannelId: String? = null, val youtubeVariants: Int? = null,
    val title: String, val customTitle: String? = null, val artworkUrl: String? = null, val link: String? = null,
    val subscribedAt: Long, val includeInAll: Boolean = true, val episodeOrder: String? = null,
    val settings: OverridesV1? = null, val credentialOrigin: String? = null,   // set when it had a credential
)
@Serializable data class GroupV1(
    val uuid: String, val name: String, val sortOrder: Int, val colorArgb: Int? = null, val iconKey: String? = null,
    val kind: String = "MANUAL", val rule: JsonElement? = null,
    val feedOrder: String = "NEWEST_FIRST", val playOrder: String = "NEWEST_FIRST", val filterFlags: Int = 0,
    val mediaFilter: String = "ALL", val hideOlderThanDays: Int? = null, val showAsTab: Boolean = true,
    val createdAt: Long? = null, val settings: OverridesV1? = null, val members: List<MemberV1> = emptyList(),
)
@Serializable data class MemberV1(val podcastKey: String, val addedAt: Long? = null)
@Serializable data class CredentialV1(val origin: String, val username: String, val password: String)
// OverridesV1: the 13 SettingOverrides fields, all nullable, enums by name
```

```kotlin
@Serializable data class EpisodeLineV1(                       // one line of episodes.jsonl
    val p: String, val k: String, val kv: Int = 1,            // podcast key, identityKey, EpisodeKeys version
    val g: String? = null, val t: String? = null, val d: Long? = null,      // guid, title, pubDate
    val u: String? = null, val ty: String? = null, val dur: Long? = null,   // enclosure URL, type, duration hint
    val yt: String? = null, val l: String? = null,                          // YouTube video ID, link
    val pl: Long? = null, val pc: Int = 0, val st: Long? = null,            // playedAt, playCount, startedAt
    val pos: Long? = null, val posAt: Long? = null, val posSrc: String? = null,
    val fav: Boolean = false, val dis: Long? = null,                        // favourite, downloadDismissedAt
    val dl: Boolean = false, val md: Long? = null,                          // was downloaded, measuredDurationMs
    val ts: Long,                                                           // last user-state change
)
@Serializable data class EpisodeRefV1(val p: String, val k: String, val kv: Int = 1)
@Serializable data class QueueV1(val upNext: List<EpisodeRefV1>, val session: SessionV1? = null)
@Serializable data class SessionV1(
    val current: EpisodeRefV1? = null, val contextType: String? = null,
    val contextGroupUuid: String? = null, val contextPodcastKey: String? = null,
    val order: String = "NEWEST_FIRST", val filterFlags: Int = 0, val mediaFilter: String = "ALL",
    val minSortDate: Long? = null, val anchor: EpisodeRefV1? = null, val anchorSortDate: Long? = null,
)
@Serializable data class SettingsV1(val values: List<SettingValueV1>)
@Serializable data class SettingValueV1(val key: String, val type: String,   // SettingKey subclass name
                                        val value: JsonPrimitive? = null, val values: List<String>? = null)
```

Not in any backup, by design: episodes without user state (re-fetched), show notes, chapters, artwork, download files and rows, refresh state and validators, `lastViewedAt`, import history, `device_settings`, and credentials unless the user opts in (manual backups only).

### Versioning

- Adding an optional field with a default never bumps `formatVersion`; readers ignore unknown keys.
- A change of meaning or a new required entry bumps `formatVersion`; `minReaderVersion` is the oldest reader that restores the file correctly (it stays 1 when old readers can safely ignore the change).
- A reader restores every `formatVersion` up to its own and refuses `minReaderVersion > supported` with "This backup was made by a newer version of Neutrodyne. Update the app to restore it."
- Episode-key changes are handled per line by `kv` ([02 Key versions](02-data-model.md#key-versions)); a line with `kv` newer than the app's `EpisodeKeys.VERSION` matches by enclosure URL or guid only, and its stub keeps the foreign key (prefixed by version, so it cannot collide). `dbSchemaVersion` is informational.

### Writing

`BackupWriter.write(kind, target: File, includeCredentials: Boolean)` (`:core:data`, holds the backup mutex):

1. Read the portable settings once: `SettingsRepository.observePortableSnapshot().first()` filtered by the [whitelist](#settings-whitelist).
2. Open one read transaction ([02 Backup export](02-data-model.md#backup-export)): read podcasts, aliases, settings rows, groups, members, queue and session in full; stream episode lines in keyset chunks of 1,000 into the `episodes.jsonl` entry of a `ZipOutputStream` on `target.tmp` (local file; no `ContentResolver` inside the transaction), each entry through a SHA-256 `DigestOutputStream` and a byte/record counter. The archive is a point-in-time snapshot (WAL reader isolation).
3. After the transaction: `library.json` (credentials decrypted through `CredentialStore` only when opted in; `credentialOrigin` is always written so a restore can flag `needsCredentials`), `queue.json`, `settings.json`, `subscriptions.opml` (manual only, from the in-memory library), then `manifest.json`.
4. Close, `fd.sync()`, rename `target.tmp` → `target`.
5. Manual backups: `CreateDocument("application/zip")` destination, copy `target` with `"wt"`, delete `target`, set `backup.last_manual_backup_at`. Snapshots: see [Auto Backup](#auto-backup).

The "Create backup" dialog (`BackupKey`) offers "Include passwords for private feeds" (off every time) with the warning "Anyone with this file can read these passwords" ([R1.9](../PLAN.md#21-functional-requirements)).

### Validation and preview

A backup arrives through the import pipeline ([Sniffing](#sniffing) → `NEUTRODYNE_BACKUP` session, no items). `BackupRepository.inspect(sessionId)` validates with `ZipGuard` caps, then:

1. Only whitelisted entry names are read; others are ignored (warning).
2. `manifest.json` (≤ 1 MiB): `format` must be `neutrodyne-backup` (`NotABackup`), `minReaderVersion` check (`NewerFormat`).
3. Every entry in `manifest.entries` must exist with the stated size and SHA-256 (`Corrupt(entry)`); required entries must be present.
4. `library.json` ≤ 32 MiB; `episodes.jsonl` is streamed, lines > 64 KiB or undecodable are skipped and counted (warning, not fatal).
5. Matching is dry-run to compute what Replace would remove.

```kotlin
// :core:domain — canonical interface, owned here
interface BackupRepository {
    suspend fun createBackup(destinationUri: String, includePasswords: Boolean): Outcome<BackupSummary, BackupError>
    suspend fun inspect(sessionId: Long): Outcome<BackupPreview, BackupError>
    suspend fun restore(sessionId: Long, request: RestoreRequest): Outcome<Unit, BackupError>   // enqueues backup-restore
    fun observeRestore(): Flow<RestoreProgress?>
    fun observeSnapshotStatus(): Flow<SnapshotStatus>
    suspend fun setSnapshotEnabled(enabled: Boolean)
}
// :core:model
enum class RestoreMode { MERGE, REPLACE }
enum class RestoreCategory { HISTORY, UP_NEXT, SETTINGS }        // subscriptions and groups are always restored
data class RestoreRequest(val mode: RestoreMode, val categories: Set<RestoreCategory>)
data class BackupPreview(
    val createdAt: Long, val appVersionName: String, val kind: BackupKind, val podcasts: Int, val groups: Int,
    val played: Int, val inProgress: Int, val upNext: Int, val settings: Int, val includesCredentials: Boolean,
    val localPodcastsNotInBackup: Int, val localGroupsNotInBackup: Int, val warnings: List<ImportWarning>,
)
data class BackupSummary(val bytes: Long, val podcasts: Int, val episodeLines: Int)
sealed interface RestoreProgress {
    data class Running(val phase: String, val done: Int, val total: Int) : RestoreProgress
    data class Finished(val sessionId: Long, val auto: Boolean) : RestoreProgress
    data class Failed(val error: BackupError) : RestoreProgress
}
data class SnapshotStatus(val enabled: Boolean, val lastWrittenAt: Long?, val bytes: Long?, val level: Int,
                          val lastError: String?)
```

Preview text: "Backup from 4 Oct 2026 (Neutrodyne 1.3): 142 podcasts, 6 groups, 3,214 played episodes, 12 in Up next, settings." Checkboxes: Listening history, Up next, Settings (Merge default: history and Up next on, settings off; Replace: all on). Mode: **Merge** (default, [canonical defaults](#settings)) or **Replace**; Replace requires a confirmation naming its effect ("Removes 9 podcasts and 2 groups that are not in the backup, and replaces your listening history"). Confirming Replace first calls `PlaybackController.pause()` from the feature layer.

### Restore algorithm

`RestoreWorker` (`:core:data`): unique work `backup-restore`, policy `KEEP` (a second request while one runs returns `BackupError.RestoreRunning`), no constraints (local work), tag `backup`, input `sessionId` and `auto`. Mode and categories are stored in the session's `ImportOptions`. Every step is idempotent, so WorkManager may re-run the worker after process death.

```mermaid
sequenceDiagram
  participant W as RestoreWorker
  participant C as BackupCodec
  participant DB as Room
  participant CS as CredentialStore (03)
  participant IW as ImportFetchWorker
  W->>C: validate payload again (caps, hashes)
  opt REPLACE
    W->>DB: unsubscribe local podcasts not in backup (UnsubscribeUseCase), delete local-only groups
  end
  opt backup includes credentials
    W->>CS: put(origin, user, password)
  end
  W->>DB: transaction A, library (groups, podcasts, aliases, settings, members)
  W->>DB: transactions B, 1,000 episode lines each (match or stub, merge state)
  W->>DB: transaction C, Up next and session
  W->>W: settings through SettingsRepository
  W->>DB: import_item per backup podcast, session COMMITTED
  W->>IW: enqueue import-sessionId (pending podcasts, initialFetch)
```

1. **Validate** again (the cache copy is ours, but cheap to re-check); failure → session `CANCELLED`, `RestoreProgress.Failed`, notification (ID 3003).
2. **Replace pre-step** (skipped on an empty database): local podcasts matched by no backup podcast → `UnsubscribeUseCase(ids)` (03: pauses playback of them, deletes download files, D24); local groups matched by no backup group → deleted without undo (channels deleted immediately); for every matched podcast, delete its `episode_state` and `episode_position` rows (only when HISTORY is selected; one transaction per podcast); clear `queue_entry` (UP_NEXT).
3. **Credentials** (opted-in manual backups only): `CredentialStore.put` outside transactions; remember `origin → credentialId`.
4. **Transaction A, library.** Groups in backup order: match `uuid`, then `nameKey`; apply the [Merge and Replace rules](#merge-and-replace-rules). Podcasts: 02's matching (key → aliases → real `podcastGuid`); unmatched → insert `PENDING_FIRST_FETCH`, `initialFetch = 1`, `nextRefreshAt = now`, `subscribedAt` from the backup, `artworkKey` = `u-{sha1hex(artworkUrl)}` or the monogram key, `needsCredentials = 1` when `credentialOrigin` is set and no credential was restored. Aliases `INSERT OR IGNORE` with reason `RESTORE` (never equal to a `feedKey`). A `GroupV1.kind` other than `MANUAL` is restored as `MANUAL` keeping `ruleJson` (warning "Smart group 'x' was imported as a regular group").
5. **Transactions B, episodes.** Read `queue.json` first and remember its refs. Stream lines, 1,000 per transaction: skip lines whose podcast is unknown; match by key (local keys computed for the line's `kv` when it differs), then normalised enclosure URL, then guid; unmatched lines with `u` or `yt` become stubs (02 SQL: `inFeed = 0`, `isNew = 0`, `contentHash = 0`); apply state when HISTORY is selected; record `(p, k) → episodeId` for referenced refs.
6. **Transaction C, Up next and session** (UP_NEXT): per the rules table; the session's group and podcast references are mapped through the uuid and key maps; `generation + 1`.
7. **Settings** (SETTINGS): every whitelisted value through `SettingsRepository.set`; unknown keys, `DEVICE` keys, type mismatches and invalid values are skipped and counted.
8. **Finish:** one `import_item` per backup podcast (inserted → `QUEUED`, matched → `ALREADY_SUBSCRIBED`, credentials missing → `AUTH_REQUIRED`); session `COMMITTED`, `attemptMarkerAt = now`; enqueue `import-{sessionId}` ([7. Fetch](#7-fetch)); `reschedulePeriodic()`; `GroupNotificationChannels.sync()`; request artwork sync for inserted podcasts (08, M4+); `SnapshotScheduler.requestSoon()`; `auto`: rename the snapshot ([Auto Backup](#auto-backup)); `RestoreProgress.Finished`.

### Merge and Replace rules

| Data | Merge (default) | Replace |
|---|---|---|
| Podcasts | union; matched keep their local row, episodes and downloads | exactly the backup's; local-only podcasts are unsubscribed (episodes, state, downloads deleted, D24) |
| `customTitle`, `includeInAll`, `episodeOrder`, `youtubeVariants` of matched podcasts | local wins (`customTitle`: local if non-null, else backup) | backup wins |
| Aliases | union | union |
| `podcast_settings`, `podcast_group_settings` | per column: local non-null wins, else backup | the backup row replaces the local row |
| Groups | match `uuid` → `nameKey`; matched keep local name, look, orders, view prefs; new groups appended after local groups in backup order | exactly the backup's: matched groups take backup values (a `nameKey` match adopts the backup `uuid`), local-only groups deleted, order = backup order |
| Memberships | union | exactly the backup's |
| Played | OR; `playedAt` = max; `playCount` = max | backup (absent = unplayed) |
| Position | the newer of `posAt` and the local `updatedAt` wins; when the merged result is played and `playedAt > posAt`, the position resets to 0 (mark-played semantics, 06) | backup (absent = none) |
| `startedAt` | earliest non-null, cleared when played | backup |
| Favourite, download tombstone | OR (tombstone time = max) | backup |
| `measuredDurationMs` | local if non-null, else backup | backup if non-null, else local |
| Up next | append backup entries not already queued, in backup order, after the local queue | the backup's list |
| Play session | restored only when the local `currentEpisodeId` is null | the backup's |
| Settings | only when SETTINGS is checked (default off) | when checked (default on) |
| Credentials (opted-in backups) | only for origins without a local credential | replace per origin |
| Downloads | never restored: "Re-download N episodes" offer | same |

### Stubs and matching

Stub episodes appear in feeds at once with the backup's title, date and enclosure, so played state and positions are visible before the first refresh. The next ingest of their podcast (INITIAL for pending podcasts) matches them by identity key, enclosure or guid and fills the feed columns, keeping user state ([02 Restore matching](02-data-model.md#restore-matching), [03 Ingestion and diff](03-feeds-and-discovery.md#ingestion-and-diff)). Stubs that never match (episodes that left the feed) are removed by retention after 90 days unless protected (favourite, in progress, queued); losing played state of an episode that no longer exists anywhere is accepted ([02 Retention and maintenance](02-data-model.md#retention-and-maintenance); answers 02's open question 6).

### Settings whitelist

The whitelist is derived, not maintained by hand: every key in 01's `AllSettingKeys.list` with `file == SettingsFile.PORTABLE`, written with its `SettingKey` subclass name as `type` ([01 DataStore files and typed setting keys](01-foundation.md#datastore-files-and-typed-setting-keys)). A key that must not travel (device paths, grants, prompt flags, selection state) must be declared `DEVICE`; the definition of done for every milestone already requires that classification ([PLAN 7.2](../PLAN.md#72-definition-of-done-every-milestone)). Credentials never pass through DataStore. The first-launch automatic restore skips SETTINGS because Auto Backup restores `settings.preferences_pb` itself, which is at least as new as the snapshot.

### After restore

- Restored podcasts fetch through `import-{sessionId}` with `initialFetch = 1`: no `isNew`, no notifications, no auto-download storm ([D66](../PLAN.md#3-key-decisions)); 07 sets `autoDownloadEligibleAfter` when policies resolve to enabled, so nothing is backfilled ([D67](../PLAN.md#3-key-decisions)).
- The report is the import report of the restore session; podcasts with `AUTH_REQUIRED` show "Enter password" (Keystore-encrypted credentials cannot move between devices, [03 Basic auth and CredentialStore](03-feeds-and-discovery.md#basic-auth-and-credentialstore)).
- **Re-download offer** (M6): lines with `dl = true` whose episode has no `COMPLETED` download → the report shows "Re-download 23 episodes (about 1.1 GB)" (size as in `downloadAllEstimate`); tapping calls `DownloadController.request(ids, DownloadLane.MANUAL, allowMetered = null)` from the visible screen (UIDT must be scheduled while visible, [07 Runners and scheduling](07-downloads.md#runners-and-scheduling)). YouTube lines are skipped when `YouTubeCapabilities.downloads` is false.

---

## Auto Backup

Serves R1.8, N1, N3. Delivered in [M3](../PLAN.md#m3-import-export-and-backup); M6 verifies that downloads stay out ([M6](../PLAN.md#m6-downloads) acceptance 7). Honours [D34](../PLAN.md#3-key-decisions), [D35](../PLAN.md#3-key-decisions), [PO-15](../PLAN.md#48-further-product-owner-decisions) default (on; no backup without encryption). Platform facts: [Auto Backup](https://developer.android.com/identity/data/autobackup).

| Path | Cloud backup | Device-to-device | Why |
|---|---|---|---|
| `files/backup/auto-snapshot.zip` | yes, encrypted transports only | yes | library, groups, history, queue (≤ 20 MB) |
| `files/datastore/settings.preferences_pb` | yes, encrypted transports only | yes | portable settings |
| Everything else: `databases/neutrodyne.db`, `device_settings`, `files/downloads/`, `Android/data/{applicationId}/files/Podcasts/`, `files/artwork/`, media and Coil caches, `credential` rows (inside the DB), import payloads, `restored-*.zip` | no | no | include-only rules; one downloaded episode would exceed the 25 MB quota and silently stop all backups |

### Rules XML

`:app/src/main/res/xml/data_extraction_rules.xml` (devices on Android 12+; we target 37):

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup disableIfNoEncryptionCapabilities="true">
        <include domain="file" path="backup/auto-snapshot.zip" />
        <include domain="file" path="datastore/settings.preferences_pb" />
    </cloud-backup>
    <device-transfer>
        <include domain="file" path="backup/auto-snapshot.zip" />
        <include domain="file" path="datastore/settings.preferences_pb" />
    </device-transfer>
    <!-- Android 16 QPR2+: a missing section would mean "fully enabled for all content". -->
    <cross-platform-transfer platform="ios">
        <include domain="file" path="backup/.none" />
    </cross-platform-transfer>
</data-extraction-rules>
```

`:app/src/main/res/xml-v28/backup_rules.xml` (Android 9–11; client-side encryption exists from Android 9):

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <include domain="file" path="backup/auto-snapshot.zip" requireFlags="clientSideEncryption" />
    <include domain="file" path="datastore/settings.preferences_pb" requireFlags="clientSideEncryption" />
    <include domain="file" path="backup/auto-snapshot.zip" requireFlags="deviceToDeviceTransfer" />
    <include domain="file" path="datastore/settings.preferences_pb" requireFlags="deviceToDeviceTransfer" />
</full-backup-content>
```

`:app/src/main/res/xml/backup_rules.xml` (Android 8.0–8.1, no client-side encryption, so PO-15 means no backup):

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <include domain="file" path="backup/.none" />
</full-backup-content>
```

- Manifest attributes (01 merges them): `android:allowBackup="true"`, `android:dataExtractionRules="@xml/data_extraction_rules"`, `android:fullBackupContent="@xml/backup_rules"`; no `backupAgent`, no `fullBackupOnly` (a custom agent would run in restricted mode without Hilt).
- **Unverified, checked in M3 with `bmgr` on API 26, 28, 31 and 36 images:** (a) two `<include>` elements for one path with different `requireFlags` are accepted (AOSP requires all listed flags when several are combined in one attribute, hence two elements); (b) an include of a path that never exists yields an empty backup; (c) platforms before Android 16 QPR2 ignore the `cross-platform-transfer` element. Fallbacks: drop the D2D includes on API 28–30; drop the cross-platform section if any older parser rejects the file.
- Consequence of PO-15: devices on Android 8.0–8.1, and devices without a screen lock, have no cloud backup of the library ([Open questions](#open-questions)).

### Snapshot production

`AutoSnapshotWorker` (`:core:data`, `@HiltWorker`, tag `backup`):

| Work | Request | Trigger |
|---|---|---|
| `backup-auto-snapshot` | periodic 24 h, `setRequiresDeviceIdle(true)`, `setRequiresCharging(true)`, policy `UPDATE` | start-up initializer, order 200 ([01 Application start-up](01-foundation.md#application-start-up)) |
| `backup-auto-snapshot-now` | one-time, initial delay 10 min, `setRequiresBatteryNotLow(true)`, policy `REPLACE` (debounce) | `SnapshotScheduler.requestSoon()` after subscribe, unsubscribe, group create/rename/delete/reorder, membership changes, scope-settings writes, import `DONE`, restore finished — not after played-state changes (the daily run covers them) |

Algorithm:

1. `backup.auto_snapshot_enabled` off → return success (turning it off deletes `auto-snapshot.zip` immediately).
2. Guard: `backup-restore` is enqueued, blocked or running → return success (the restore requests a snapshot when done).
3. Guard: an existing `auto-snapshot.zip` whose manifest `installationId` differs from `backup.installation_id` came from another device and has not been restored yet → never overwrite it; return success. (This is what protects a freshly restored phone from replacing the real snapshot with an empty one.)
4. M3–M10 only: run 02's import-session cleanup ([9. Session lifetime](#9-session-lifetime)).
5. `BackupWriter.write(AUTO_SNAPSHOT, files/backup/auto-snapshot.zip.tmp, includeCredentials = false)`, without `subscriptions.opml`.
6. Size guard: compressed size > 20 MB → rewrite at the next level; at level 3 still > 20 MB → keep the previous snapshot, store `backup.last_snapshot_error = "too_large"`, log WARN.
7. `fd.sync()`, atomic `renameTo` over `auto-snapshot.zip`, delete `restored-*.zip`, write `backup.last_snapshot_at`, `_bytes`, `_level`.

| Level | Episode lines kept | Typical use |
|---|---|---|
| 0 | all lines of [Backup export](02-data-model.md#backup-export) | normal (a few MB at the N5 scale) |
| 1 | played lines with `pl` older than 365 days and no position, favourite or queue reference slimmed to `p`, `k`, `kv`, `pl`, `ts` | very long histories |
| 2 | those lines dropped; tombstone-only lines older than 365 days dropped | extreme |
| 3 | only in-progress, favourite, queued and current lines | last resort |

### First-launch restore

`FirstLaunchRestoreInitializer` (`AppInitializer`, order 110, `:core:data`):

```mermaid
sequenceDiagram
  participant A as NeutrodyneApplication
  participant D as DatabaseOpener (02)
  participant F as FirstLaunchRestoreInitializer
  participant I as ImportRepository
  participant W as RestoreWorker
  A->>D: awaitOpen() (order 100)
  D-->>A: OpenResult(created, recovered)
  A->>F: run() (order 110)
  alt created and files/backup/auto-snapshot.zip exists
    F->>I: create session from the snapshot copy (NEUTRODYNE_BACKUP)
    F->>W: enqueue backup-restore (REPLACE, HISTORY and UP_NEXT, auto)
    W-->>F: Finished, snapshot renamed to restored-ts.zip
  else not created or no snapshot
    F-->>A: nothing to do
  end
```

- `OpenResult.created` (Room `onCreate` in this process) is the only fresh-install signal; a DataStore flag would itself have been restored ([02 Error handling and recovery](02-data-model.md#error-handling-and-recovery)). `recovered != null` (corrupt database quarantined) takes the same path with the local snapshot.
- The snapshot is copied to `cacheDir/import/{sessionId}.bin` so the restore path equals the manual one; the original is renamed to `files/backup/restored-{epochMs}.zip` only after success and deleted by the next successful snapshot.
- `NewerFormat` → keep the snapshot untouched, post "Your backup needs a newer version of Neutrodyne" (ID 3004); the installation-ID guard keeps it until an update restores it.
- Nothing waits for the restore at start-up ([01](01-foundation.md#application-start-up)); `BackupRepository.observeRestore()` drives 08's "Restoring your library…" banner in Feeds and Library. The library appears within seconds (transaction A), history fills in, then feeds refresh.

```mermaid
stateDiagram-v2
  [*] --> Absent
  Absent --> Writing: AutoSnapshotWorker
  Writing --> Current: fsync and atomic rename
  Writing --> Absent: failure, tmp deleted
  Current --> Writing: next run
  [*] --> Foreign: Auto Backup restore at install
  Foreign --> Restored: first-launch restore succeeds (renamed restored-ts.zip)
  Foreign --> Foreign: worker guard refuses to overwrite
  Restored --> Current: next successful snapshot deletes restored-ts.zip
```

### Testing with bmgr

M3 acceptance 6, run manually on GMD images and scripted in 09's instrumented job where `bmgr` is available ([Test backup and restore](https://developer.android.com/identity/data/testingbackup)):

```bash
pkg=app.neutrodyne.debug
adb shell bmgr enable true
adb shell bmgr transport com.android.localtransport/.LocalTransport
adb shell settings put secure backup_local_transport_parameters 'is_encrypted=true'   # else disableIfNoEncryptionCapabilities skips us
adb shell am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS -p "$pkg"    # optional: WorkManager state in logcat
adb shell bmgr backupnow "$pkg"            # expect "Package … with result: Success"
adb shell pm path "$pkg"                   # pull the APK(s), then:
adb shell pm uninstall --user 0 "$pkg" && adb install-multiple -t --user 0 base.apk
```

Before `backupnow`, the test build exposes a debug-only "Write snapshot now" action (Diagnostics, M3) so the snapshot exists. Pass: after reinstall the library, groups, played state, positions and Up next are back; the backup set (`adb shell bmgr list sets` / transport logs) contains only the two included files. Device-to-device: the D2D script of the same page (Android 12+). An automated `FirstLaunchRestoreTest` (GMD) covers the logic without `bmgr`: place a snapshot file, start with an empty database, assert the restored library.

---

## Receiving files

Serves R1.1. Delivered in [M3](../PLAN.md#m3-import-export-and-backup). The activity is `app.neutrodyne.ExternalImportActivity` in `:app` ([01 Manifest and permissions](01-foundation.md#manifest-and-permissions) lists it; filters are defined here).

```xml
<activity
    android:name=".ExternalImportActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:noHistory="true"
    android:theme="@style/Theme.Neutrodyne.Translucent"
    android:label="@string/import_into_neutrodyne">
    <!-- "Open with" and Share for typed OPML/XML and for backup or Takeout ZIPs; no scheme = content: and file: -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/x-opml" />
        <data android:mimeType="text/xml" />
        <data android:mimeType="application/xml" />
        <data android:mimeType="application/zip" />
    </intent-filter>
    <!-- Share of an untyped file (file managers report .opml as application/octet-stream) -->
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="application/octet-stream" />
    </intent-filter>
</activity>
<!-- API 31+: "Open with" for content://…/*.opml whatever MIME type the provider reports.
     pathSuffix does not exist below 31, where the filter would match every content URI. -->
<activity-alias
    android:name=".OpmlByExtensionAlias"
    android:targetActivity=".ExternalImportActivity"
    android:enabled="@bool/neutrodyne_api31_or_newer"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:scheme="content" android:host="*" android:mimeType="*/*" android:pathSuffix=".opml" />
    </intent-filter>
</activity-alias>
```

`res/values/bools.xml`: `neutrodyne_api31_or_newer = false`; `res/values-v31/bools.xml`: `true`.

**Why no broad filter.** A VIEW filter for `*/*` or `application/octet-stream` would offer Neutrodyne for every unknown file (Pocket Casts removed its octet-stream filter after it caught "install (1).apk"); MIME types of `.opml` are unreliable anyway (`application/octet-stream`, `text/xml`, `text/plain`, `text/x-opml`), and some providers expose neither a type nor a file name (Downloads `msf:` IDs, Gmail). Everything that the narrow filters miss goes through the in-app picker, and the content is sniffed regardless of how it arrived. JSON and CSV files (NewPipe, LibreTube, Takeout) are imported from the in-app picker only; Takeout ZIPs also arrive through the `application/zip` filter.

**Behaviour.**

1. Resolve the stream: VIEW → `intent.data`; SEND → `IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)`, else `clipData.getItemAt(0).uri`. No URI, or a scheme other than `content`/`file` → toast "Nothing to import", `finish()`.
2. A retained `ExternalImportViewModel` calls `ImportRepository.create(ImportSource(uri.toString(), displayName = null))`. The copy runs in the caller's coroutine (it must finish while the grant lives); after the copy, sniffing, parsing and persisting continue in `@ApplicationScope`. A progress overlay appears only if the call takes longer than 300 ms.
3. Success → `startActivity(Intent(ACTION_VIEW, "neutrodyne://open/import/{sessionId}").setClass(this, MainActivity::class.java).addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP))`, `finish()`. 01's `IntentRouter` turns it into `Navigate(LibraryKey, [ImportKey(sessionId)])`. `MainActivity` itself has no file filters.
4. Failure → a dialog with the [error text](#error-handling-and-failure-modes), then `finish()`.
5. If the user leaves after the copy but before the session is ready, the application-scope job still finishes and posts "Ready to review: 142 podcasts from antennapod-feeds.opml" (channel `import_backup`, ID 3002, tag = session ID) deep-linking to the session.

**In-app entry points** (all in `:feature:importexport`, launched from Discover, Library overflow, Settings › Backup and onboarding, 08): `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` with `arrayOf("*/*")` (MIME filtering would grey out `.opml` files on providers without a mapping) → the screen's ViewModel calls `create(ImportSource(uri, displayName))` and navigates to `ImportKey(sessionId)`; an "Import from URL" field creates the session from an `https://` source. A backup file picked anywhere routes to the restore preview because its session format is `NEUTRODYNE_BACKUP`.

---

## Settings

Keys follow 01's registry ([01 DataStore files and typed setting keys](01-foundation.md#datastore-files-and-typed-setting-keys)). Per-podcast and per-group overrides are columns, not DataStore keys ([Effective settings resolution](#effective-settings-resolution)); group view settings are columns of `podcast_group`. Canonical defaults implemented here: group names 1–40 chars, emoji allowed, unique by `nameKey`; `feedOrder`/`playOrder` `NEWEST_FIRST`; Ungrouped tab off; counts window 30 days or `hideOlderThanDays`; delete-group undo 10 s; OPML export grouped, YouTube on, passwords off; import "treat existing as played" off, "notifications for imported podcasts" off; restore mode Merge; auto snapshot every 24 h (idle + charging) plus 10 min after significant changes, size guard 20 MB.

| Key | Type | Default | File | UI location | Milestone |
|---|---|---|---|---|---|
| `groups.show_ungrouped_tab` | Bool | false | `settings` | Settings › Feeds › "Show Ungrouped tab" | M2 |
| `groups.all_filter_flags` | Int32 (bits of `FilterFlagBits`) | 0 | `settings` | Feeds › All › filter chips | M2 |
| `groups.all_media_filter` | Choice `MediaFilter` | `ALL` | `settings` | same | M2 |
| `groups.all_hide_older_than_days` | Int32 (0 = off; 1, 3, 7, 14, 30, 90, 365) | 0 | `settings` | Feeds › All › overflow | M2 |
| `groups.ungrouped_filter_flags`, `groups.ungrouped_media_filter`, `groups.ungrouped_hide_older_than_days` | as above | 0, `ALL`, 0 | `settings` | Feeds › Ungrouped | M2 |
| `groups.all_last_viewed_at`, `groups.ungrouped_last_viewed_at` | Int64 (0 = initialise to now) | 0 | `device_settings` | internal | M2 |
| `backup.opml_layout` | Choice `OpmlLayout` {`GROUPED`, `FLAT`} | `GROUPED` | `settings` | Export dialog | M3 |
| `backup.opml_include_youtube` | Bool | true | `settings` | Export dialog | M3 |
| `backup.auto_snapshot_enabled` | Bool | true | `settings` | Settings › Backup › "Include your library in Android backup" (with "Requires a screen lock") | M3 |
| `backup.installation_id` | Text (UUID generated on first read) | "" | `device_settings` | internal | M3 |
| `backup.last_snapshot_at`, `backup.last_snapshot_bytes`, `backup.last_snapshot_level`, `backup.last_snapshot_error` | Int64, Int64, Int32, Text | 0, 0, 0, "" | `device_settings` | Settings › Backup status line; diagnostics (09) | M3 |
| `backup.last_manual_backup_at` | Int64 | 0 | `device_settings` | Settings › Backup ("Last backup: 3 days ago") | M3 |
| `backup.scheduled_enabled` | Bool | false | `settings` | Settings › Backup | M15 |
| `backup.scheduled_interval_days` | Int32 {1, 3, 7, 14} | 7 | `settings` | same | M15 |
| `backup.scheduled_keep` | Int32 1–20 | 5 | `settings` | same | M15 |
| `backup.scheduled_tree_uri` | Text | "" | `device_settings` (SAF grants never transfer) | same | M15 |

**Scheduled backup (v1.x, M15) outline.** The user picks a folder with `ACTION_OPEN_DOCUMENT_TREE` (Android 11+ refuses the storage root and `Download/` itself; a subfolder works) and the app calls `takePersistableUriPermission`. `ScheduledBackupWorker` (`backup-scheduled`, periodic `backup.scheduled_interval_days`, charging + battery not low, `UPDATE`) writes a `MANUAL`-format archive with kind `SCHEDULED` (no passwords) through `documentfile` 1.1.0, names it `neutrodyne-backup-{yyyy-MM-dd-HHmm}.zip` (time included because providers rename duplicates to `name (1).zip`), and deletes the oldest files beyond `backup.scheduled_keep` matched by the lenient regex `^neutrodyne-backup-\d{4}-\d{2}-\d{2}-\d{4}( \(\d+\))?\.zip$`. A lost grant (`DocumentFile.canWrite()` false) posts a notification on `import_backup` (ID 3010) instead of failing silently (AntennaPod precedent: every 3 days, keep 5).

---
