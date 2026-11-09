// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.FileSystem
import okio.Path

/**
 * 10 Durable apply effects: drains the recoverable part of a committed page (settings, protected
 * credentials) after the commit and at each start. A winning setting stores `pendingSetting`;
 * [drainSettings] applies it through [SettingsSyncPort] and clears the marker only after the
 * DataStore write succeeds, so a crash between the stores repeats the idempotent write.
 *
 * Every key drains under [SettingsWriteCoordinator], the same per-key mutex a local `set`/`reset`
 * and a linked restore take. Under that lock the marker and the newest local `v` outbox intent are
 * re-read: a strictly newer local intent wins and the stale remote value is skipped, while an equal
 * clock is an identical intent and still applies (a storage-failure retry). The marker is cleared
 * from a fresh row inside a Room write transaction, and only when it still matches the applied
 * value and clock, so a newer page's effect or other raw metadata is never rolled back. A skipped
 * marker is cleared by the same conditional ack: the upload ack deletes the outbox intent before
 * the server's echo page can replace the marker, so leaving it would let a later drain re-apply
 * the superseded remote value. No Room transaction spans the DataStore write.
 *
 * A drain runs only while the persisted "Sync playback settings" switch is on
 * ([settingsSyncEnabled], the live `SyncServerAddress.syncPlaybackSettings()` read — never
 * `SyncFlags`' late cache): while it is off the markers stay pending (10 Pull and apply step 3),
 * and turning it on drains them. A drain failure propagates; the markers it did not clear stay
 * for the next drain.
 *
 * 10 Passwords: [drainPasswords] installs each `pendingAuth` marker's protected snapshot as the
 * podcast's credential, all under [CredentialCommitCoordinator] — never inside a Room
 * transaction and never holding one across a `SecretStore` await or write. Under the lock the
 * marker, the winning reference and the newest local `auth` intent are re-read: a strictly
 * newer winner (local or remote) discards the stale marker and its snapshot; an equal or
 * unfinished marker installs idempotently. A missing podcast or a missing snapshot leaves the
 * marker pending (a missing snapshot also flags `needsCredentials`); the `credentialId` write
 * runs under capture suppression so a synced credential never echoes as a local intent. After
 * both stores commit, the marker clears and the reference re-binds to the installed origin —
 * the staging snapshot is then unreferenced and removed.
 *
 * [acknowledgeAuths] binds an acknowledged local `auth` push to its credential origin at the
 * acknowledged complete clock, so a later resync replays the protected value with its original
 * provenance instead of a bare projection.
 */
class SyncApplyEffects(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsSyncPort,
    private val writeLocks: SettingsWriteCoordinator,
    private val settingsSyncEnabled: suspend () -> Boolean,
    private val secrets: SecretStore,
    private val credentials: CredentialCommitCoordinator,
    /** The first-link staging file's parent — a live file retains every staged snapshot. */
    private val cacheDir: Path,
) {
    private val fileSystem = FileSystem.SYSTEM

    /** Applies every pending setting whose marker survived; leaves the rest for a later drain. */
    suspend fun drainSettings() {
        // The switch gate is the persisted value read live: markers stay pending while it is
        // off, so enabling it later applies them (the server's values win, as at the first link).
        if (!settingsSyncEnabled()) return
        for (row in db.syncClockDao().all()) {
            if (row.coll != SyncCollection.SETTING.wire) continue
            val key = AllSettingKeys.list.firstOrNull { it.name == row.rid } ?: continue
            drainSetting(key)
        }
    }

    /**
     * 10 Durable apply effects / Settings capture: the at-each-start drain, before any push or
     * hash reconciliation. Only while linked — an unlink cleared the sync tables, so a leftover
     * marker of the old account must never re-apply. Local only (Room and DataStore): no network
     * call, nothing that blocks start-up beyond the markers' own writes.
     */
    suspend fun drainAtStart() {
        val state = db.syncStateDao().get() ?: return
        if (!state.enabled || state.linkedAt == null) return
        drainSettings()
        drainPasswords()
    }

    // --- 10 Passwords: the protected credential drain ----------------------------------------------

    /**
     * Drains every podcast `pendingAuth` marker: orphaned staging keys leave first (a live
     * first-link staging file retains them all — it is itself a consumer), then each marker
     * installs under the credential-commit coordinator.
     */
    suspend fun drainPasswords() {
        secrets.awaitLoaded()
        sweepOrphanedAuthStaging()
        for (row in db.syncClockDao().all()) {
            if (row.coll != SyncCollection.PODCAST.wire) continue
            val raw = decode(row.clocks) ?: continue
            val pending = raw.pendingAuth ?: continue
            drainAuth(row.rid, pending)
        }
    }

    /**
     * `sync:pending:` entries nothing references are orphans: losing writes, failed pages and
     * forgotten accounts. Every committed `authReference`/`pendingAuth` key is kept.
     */
    private suspend fun sweepOrphanedAuthStaging() {
        // A live first-link staging file is itself a consumer of the pending snapshots.
        if (stagingFileAlive()) return
        val referenced = referencedAuthKeys(db)
        credentials.withCredentialCommit {
            for (origin in secrets.originsWithPrefix(PENDING_AUTH_PREFIX)) {
                if (origin !in referenced) suspendRunCatching { secrets.remove(origin) }
            }
        }
    }

    private suspend fun stagingFileAlive(): Boolean {
        val deviceId = db.syncStateDao().get()?.deviceId ?: return false
        return fileSystem.exists(cacheDir / "sync-link-$deviceId.jsonl")
    }

    private suspend fun drainAuth(
        rid: String,
        marker: PendingAuth,
    ) {
        credentials.withCredentialCommit {
            val row = db.syncClockDao().get(SyncCollection.PODCAST.wire, rid) ?: return@withCredentialCommit
            val raw = decode(row.clocks) ?: return@withCredentialCommit
            // A newer page moved the marker on; this pass owns only the marker it read.
            if (raw.pendingAuth != marker) return@withCredentialCommit
            val ref = raw.authReference
            val markerClock = runCatching { Hlc.parse(marker.clock) }.getOrNull()
            val newerLocal =
                db.syncOutboxDao().get(SyncCollection.PODCAST.wire, rid, "auth")?.let { intent ->
                    val local = outboxClock(intent)
                    markerClock != null && local != null && local > markerClock
                } == true
            if (ref == null || ref.clock != marker.clock || newerLocal) {
                // The winner moved past this marker (a newer local or remote write): discard it
                // and its snapshot — the newer effect owns the credential now.
                discardAuth(rid, marker)
                return@withCredentialCommit
            }
            // A missing podcast loses nothing: the marker outlives the row's arrival.
            val podcast = db.podcastDao().bySyncId(rid) ?: return@withCredentialCommit
            val snapshot = secrets.get(marker.reference)
            if (snapshot == null) {
                // The protected copy is gone (key loss, cleared store): the feed stays flagged
                // and the marker pending for the next drain, never silently dropped.
                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        if (db.podcastDao().byId(podcast.id) != null) {
                            db.podcastDao().setNeedsCredentials(podcast.id, true)
                        }
                    }
                }
                return@withCredentialCommit
            }
            val credentialId =
                secrets.put(marker.credentialOrigin, snapshot.username, snapshot.secret)
                    ?: return@withCredentialCommit
            val committed =
                try {
                    db.withWriteTransaction<Boolean> {
                        db.syncStateDao().withApplying<Boolean> {
                            if (db.podcastDao().byId(podcast.id) != null) {
                                db.podcastDao().setCredentialId(podcast.id, credentialId)
                                db.podcastDao().clearNeedsCredentials(podcast.id)
                                true
                            } else {
                                false
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    dropUnreferencedCredential(marker.credentialOrigin, credentialId)
                    throw failure
                }
            if (!committed) {
                // The podcast vanished between the checks and the commit — the idempotent put
                // is unreferenced again and the marker stays for a later arrival.
                dropUnreferencedCredential(marker.credentialOrigin, credentialId)
                return@withCredentialCommit
            }
            ackAuth(rid, marker)
            if (marker.reference.startsWith(PENDING_AUTH_PREFIX)) {
                suspendRunCatching { secrets.remove(marker.reference) }
            }
        }
    }

    /**
     * Both stores committed: the marker clears and the reference re-binds to the installed
     * credential's origin — what a resync hydrates and replays — keeping the winning clock.
     * Re-read and write share one Room transaction so a newer page's marker is never cleared.
     */
    private suspend fun ackAuth(
        rid: String,
        marker: PendingAuth,
    ) {
        db.withWriteTransaction {
            val fresh = db.syncClockDao().get(SyncCollection.PODCAST.wire, rid) ?: return@withWriteTransaction
            val raw = decode(fresh.clocks) ?: return@withWriteTransaction
            if (raw.pendingAuth != marker) return@withWriteTransaction
            db.syncClockDao().upsert(
                fresh.copy(
                    clocks =
                        encode(
                            raw.copy(
                                pendingAuth = null,
                                authReference =
                                    AuthReference(
                                        storeKey = marker.credentialOrigin,
                                        credentialOrigin = marker.credentialOrigin,
                                        clock = marker.clock,
                                    ),
                            ),
                        ),
                ),
            )
        }
    }

    /**
     * The marker's winner is gone: the marker clears and the staged snapshot leaves — a
     * strictly newer write owns the credential now.
     */
    private suspend fun discardAuth(
        rid: String,
        marker: PendingAuth,
    ) {
        db.withWriteTransaction {
            val fresh = db.syncClockDao().get(SyncCollection.PODCAST.wire, rid) ?: return@withWriteTransaction
            val raw = decode(fresh.clocks) ?: return@withWriteTransaction
            if (raw.pendingAuth != marker) return@withWriteTransaction
            db.syncClockDao().upsert(fresh.copy(clocks = encode(raw.copy(pendingAuth = null))))
        }
        if (marker.reference.startsWith(PENDING_AUTH_PREFIX)) {
            suspendRunCatching { secrets.remove(marker.reference) }
        }
    }

    /**
     * The `put` committed but the `credentialId` write did not: the unreferenced row is
     * reclaimed like `PodcastRepository.setCredentials` does — the marker stays pending and the
     * idempotent next drain re-puts it.
     */
    private suspend fun dropUnreferencedCredential(
        origin: String,
        credentialId: Long,
    ) {
        if (db.credentialDao().referenceCount(credentialId) == 0) {
            suspendRunCatching { secrets.remove(origin) }
        }
    }

    /**
     * 10 Passwords / Push ack: an acknowledged local `auth` keeps its protected reference — the
     * credential origin it was pushed from and the acknowledged complete clock — so a resync's
     * replay hydrates the stored snapshot rather than echoing a bare projection.
     */
    suspend fun acknowledgeAuths(changes: List<ChangeDto>) {
        val auths =
            changes.filter { change ->
                change.coll == SyncCollection.PODCAST.wire && change.fields.containsKey("auth")
            }
        if (auths.isEmpty()) return
        credentials.withCredentialCommit {
            db.withWriteTransaction {
                for (change in auths) {
                    val clock = change.fields.getValue("auth").c ?: continue
                    runCatching { Hlc.parse(clock) }.getOrNull() ?: continue
                    val rid =
                        runCatching {
                            RecordId.fromWire(SyncCollection.PODCAST, change.id)
                        }.getOrNull()
                            ?.rid ?: continue
                    val row = db.syncClockDao().get(SyncCollection.PODCAST.wire, rid) ?: continue
                    val raw = decode(row.clocks) ?: continue
                    val existing = raw.authReference
                    val existingClock =
                        existing?.clock?.let { runCatching { Hlc.parse(it) }.getOrNull() }
                    if (existingClock != null && existingClock >= Hlc.parse(clock)) continue
                    val origin =
                        jsonContent(change.fields["credentialOrigin"]?.v)
                            ?: jsonContent(raw.state.fields["credentialOrigin"]?.v)
                            ?: liveCredentialOrigin(rid)
                            ?: continue
                    db.syncClockDao().upsert(
                        row.copy(
                            clocks =
                                encode(
                                    raw.copy(authReference = AuthReference(origin, origin, clock)),
                                ),
                        ),
                    )
                }
            }
        }
    }

    /** The credential origin the podcast's live `credentialId` binds to. */
    private suspend fun liveCredentialOrigin(rid: String): String? {
        val podcast = db.podcastDao().bySyncId(rid) ?: return null
        val credentialId = podcast.credentialId ?: return null
        return db
            .credentialDao()
            .byIds(listOf(credentialId))
            .firstOrNull()
            ?.origin
    }

    private fun jsonContent(value: kotlinx.serialization.json.JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    // --- Settings drain (10 Durable apply effects) --------------------------------------------------

    private suspend fun drainSetting(key: SettingKey<*>) {
        writeLocks.withLock(key) {
            // A previous key or local writer may have held this drain while sync was disabled.
            // Admit each key under its write lock; later keys stay pending once the switch is off.
            if (!settingsSyncEnabled()) return@withLock
            val row = db.syncClockDao().get(SyncCollection.SETTING.wire, key.name) ?: return@withLock
            val raw = decode(row.clocks) ?: return@withLock
            val pending = raw.pendingSetting ?: return@withLock
            if (hasNewerLocalIntent(key, pending)) {
                ack(key, pending)
                return@withLock
            }
            if (!settings.applyRemote(key, pending.value)) return@withLock
            ack(key, pending)
        }
    }

    /**
     * True when the newest local `v` intent's complete clock is strictly newer than the pending
     * remote clock. A marker or intent without a parseable clock cannot prove a newer local write,
     * so it applies as before.
     */
    private suspend fun hasNewerLocalIntent(
        key: SettingKey<*>,
        pending: PendingSetting,
    ): Boolean {
        val pendingClock = runCatching { Hlc.parse(pending.clock) }.getOrNull() ?: return false
        val local = db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v") ?: return false
        val localClock = runCatching { Hlc(local.hlc, NodeId(local.nodeId)) }.getOrNull() ?: return false
        return localClock > pendingClock
    }

    /**
     * Clears the marker only when the fresh row still carries exactly the given value and clock.
     * The re-read and the write share one Room transaction, so a concurrent metadata update or a
     * newer page's marker is neither overwritten nor cleared. Used both after a successful apply
     * and when a strictly newer local intent supersedes the marker.
     */
    private suspend fun ack(
        key: SettingKey<*>,
        applied: PendingSetting,
    ) {
        db.withWriteTransaction {
            val fresh =
                db.syncClockDao().get(SyncCollection.SETTING.wire, key.name)
                    ?: return@withWriteTransaction
            val freshRaw = decode(fresh.clocks) ?: return@withWriteTransaction
            val marker = freshRaw.pendingSetting ?: return@withWriteTransaction
            if (marker.value != applied.value || marker.clock != applied.clock) {
                return@withWriteTransaction
            }
            db.syncClockDao().upsert(fresh.copy(clocks = encode(freshRaw.copy(pendingSetting = null))))
        }
    }

    private fun decode(json: String): RawSyncRecord? = decodeRawClocks(json)

    private fun encode(raw: RawSyncRecord): String = SyncJson.encodeToString(raw)
}
