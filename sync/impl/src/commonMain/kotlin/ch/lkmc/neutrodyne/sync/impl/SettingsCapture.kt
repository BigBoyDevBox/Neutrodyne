// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncCaptureKind
import ch.lkmc.neutrodyne.core.database.SyncClockEntity
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.domain.SettingsSyncPort
import ch.lkmc.neutrodyne.core.domain.SettingsWriteCoordinator
import ch.lkmc.neutrodyne.core.model.settings.AllSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 10 Settings capture: while linked and the persisted "Sync playback settings" switch is on,
 * installs the recorder that persists a local synced-key change as a literal `v` outbox intent
 * with a fresh clock **before** DataStore, and the materialized listener that advances the key's
 * [SettingBaseline] after every successful local DataStore write — captured or not. A failed
 * Room record leaves DataStore unchanged; a failed DataStore write keeps the committed intent
 * for recovery.
 *
 * [install] runs three steps, in order, each setting key under its [SettingsWriteCoordinator]
 * lock (the same mutex local `set`/`reset` takes, so no capture gap opens):
 *
 * 1. Both callbacks register first, so a local write during the rest of the install can neither
 *    escape capture nor lose its baseline write.
 * 2. [recoverCommittedIntents] replays every committed `v` outbox intent whose DataStore write
 *    never landed (a crash or a write failure after the Room commit). The intent keeps its
 *    original complete clock — replay never takes a fresh tick. The decision re-reads the
 *    intent, the pending remote marker, the baseline and the live stored value:
 *    - the stored value already equals the intent: nothing to write; the baseline just advances.
 *    - a pending remote marker at least as new as the intent wins ([SyncApplyEffects] drains it
 *      next, after recovery — that order keeps an older intent from masking the newer effect).
 *    - the stored value still equals the baseline's: the intent simply never materialized, so
 *      it is applied and the baseline advances with it.
 *    - the baseline is clockless or the stored value matches neither intent nor baseline: a
 *      newer real write exists that the account never saw (an edit made while the switch was
 *      off, or an external restore). The intent is superseded bookkeeping: its row is retired
 *      under its own clock and the baseline records the surviving value clocklessly — no wall
 *      clock is invented and the latest user intent is not rolled back.
 * 3. [captureAbsentKeys] captures the stored value of every synced key the account does not
 *    carry (`v` neither in raw state nor pending), so enabling the switch or completing a link
 *    publishes the local value as at the first link.
 *
 * Recovery runs only inside [install], never while the switch is off or the link is gone:
 * [reconcile] chooses between the two from the persisted gates read live. The callbacks also
 * re-check the gates at call time — a `set` racing an unlink or the switch's off edge captures
 * nothing and writes no baseline while unlinked.
 */
class SettingsCapture(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsSyncPort,
    private val writeLocks: SettingsWriteCoordinator,
    private val settingsSyncEnabled: suspend () -> Boolean,
) {
    /**
     * The lifecycle entry point (start-up, link, the switch's both edges, unlink, revocation):
     * installs while linked with the switch on, clears otherwise. The gates are the persisted
     * values read at this call — never a cache.
     */
    suspend fun reconcile() {
        if (!linked()) {
            clear()
        } else if (settingsSyncEnabled()) {
            install()
        } else {
            off()
        }
    }

    /**
     * The switch's off edge: the recorder goes inert but the materialized listener stays — a
     * local write while off is not captured, yet its clockless baseline is what later keeps a
     * committed-but-unmaterialized intent from rolling it back. [clear] removes both.
     */
    fun off() {
        settings.registerRecorder(null)
    }

    /** Starts recording and recovers committed intents; called while linked with the switch on. */
    suspend fun install() {
        settings.registerRecorder { key, json ->
            if (linked() && settingsSyncEnabled()) {
                db.withWriteTransaction {
                    db
                        .syncOutboxDao()
                        .captureIntent(SyncCollection.SETTING.wire, key.name, "v", json)
                }
            }
        }
        settings.registerMaterialized { key, json ->
            if (linked()) recordMaterialized(key, json)
        }
        recoverCommittedIntents()
        captureAbsentKeys()
    }

    /** Stops recording and baselining (unlinked, revoked, or the switch off). */
    fun clear() {
        settings.registerRecorder(null)
        settings.registerMaterialized(null)
    }

    /** A linked, unrevoked account — the only state in which capture bookkeeping may live. */
    private suspend fun linked(): Boolean {
        val state = db.syncStateDao().get() ?: return false
        return state.enabled && state.linkedAt != null && state.lastError != SYNC_ERROR_REVOKED
    }

    /**
     * The post-write half of the capture contract: the stored value [json] just landed in
     * DataStore, so the baseline tracks it at the committed intent's complete clock when the
     * row still carries that literal, or clocklessly when nothing was captured (the switch
     * off). The per-key lock the caller holds serializes this with every other writer.
     */
    private suspend fun recordMaterialized(
        key: SettingKey<*>,
        json: String,
    ) {
        db.withWriteTransaction {
            val intent = db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v")
            val clock =
                if (intent?.value == json) Hlc(intent.hlc, NodeId(intent.nodeId)).toString() else null
            val row = db.syncClockDao().get(SyncCollection.SETTING.wire, key.name)
            val raw = row?.clocks?.let(::decode)
            if (row != null && raw == null) return@withWriteTransaction
            val stored = raw ?: RawSyncRecord(RecordState(SyncCollection.SETTING))
            val next =
                if (clock == null) {
                    SettingBaseline(settingBaselineHash(json))
                } else {
                    stored.settingHash.advancedTo(json, clock)
                }
            db.syncClockDao().upsert(
                SyncClockEntity(
                    SyncCollection.SETTING.wire,
                    key.name,
                    encode(stored.copy(settingHash = next)),
                ),
            )
        }
    }

    /** Step 2 of [install]: replay or retire each committed `v` intent under its key's lock. */
    private suspend fun recoverCommittedIntents() {
        val rows =
            db.syncOutboxDao().rows().filter {
                it.coll == SyncCollection.SETTING.wire && it.field == "v"
            }
        for (row in rows) {
            val key = AllSettingKeys.list.firstOrNull { it.name == row.rid && it.synced } ?: continue
            writeLocks.withLock(key) { recoverIntent(key) }
        }
    }

    private suspend fun recoverIntent(key: SettingKey<*>) {
        // Re-read under the lock: a local write may have coalesced or acked the row meanwhile.
        val row = db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v") ?: return
        val intentClock = Hlc(row.hlc, NodeId(row.nodeId))
        val raw =
            db
                .syncClockDao()
                .get(SyncCollection.SETTING.wire, key.name)
                ?.clocks
                ?.let(::decode)
        val pendingClock = raw?.pendingSetting?.clock?.let(::parseHlc)
        // A pending remote effect at least as new as the intent wins; the drain applies it next.
        val deferred =
            row.captureKind != SyncCaptureKind.REPLAY &&
                pendingClock != null &&
                pendingClock >= intentClock
        if (deferred) return
        val current = settings.currentJson(key)
        if (current == row.value) {
            // Already materialized (the DataStore write landed before the crash): only the
            // baseline still has to advance to the intent's clock.
            row.value?.let { advanceBaseline(key, it, intentClock.toString()) }
            return
        }
        val baseline = raw?.settingHash
        when {
            // A replayed accepted value wins over anything stored locally.
            row.captureKind == SyncCaptureKind.REPLAY -> {
                materialize(key, row, intentClock)
            }

            // Nothing proves a newer write: the intent simply never landed — replay it.
            baseline == null -> {
                materialize(key, row, intentClock)
            }

            // A clockless baseline is a real write the account never saw (an edit while
            // the switch was off); so is a stored value that moved past its baseline
            // without a clock to prove it (an external restore, or a wiped value). The
            // intent is superseded bookkeeping — retire it, never roll the newer value back.
            baseline != null && baseline.newerThanStored(current, intentClock) -> {
                retire(key, row, current)
            }

            else -> {
                materialize(key, row, intentClock)
            }
        }
    }

    /**
     * The [SettingBaseline] proves a real write the intent predates: clockless (an edit made
     * while the switch was off), a stored value that moved past it, or a clock at least as new
     * as the intent's.
     */
    private fun SettingBaseline.newerThanStored(
        current: String?,
        intentClock: Hlc,
    ): Boolean {
        val baselineClock = clock?.let(::parseHlc)
        return baselineClock == null ||
            current == null ||
            settingBaselineHash(current) != hash ||
            baselineClock >= intentClock
    }

    /** The intent never landed in DataStore: apply it, then baseline it under its own clock. */
    private suspend fun materialize(
        key: SettingKey<*>,
        row: SyncOutboxEntity,
        intentClock: Hlc,
    ) {
        val literal = row.value ?: return
        if (!settings.applyRemote(key, literal)) return
        advanceBaseline(key, literal, intentClock.toString())
    }

    /** Baselines [json] at [clock] when that is newer — merged into the fresh row, one transaction. */
    private suspend fun advanceBaseline(
        key: SettingKey<*>,
        json: String,
        clock: String,
    ) {
        db.withWriteTransaction {
            val row = db.syncClockDao().get(SyncCollection.SETTING.wire, key.name)
            val raw = row?.clocks?.let(::decode)
            if (row != null && raw == null) return@withWriteTransaction
            val stored = raw ?: RawSyncRecord(RecordState(SyncCollection.SETTING))
            val next = stored.settingHash.advancedTo(json, clock)
            if (next === stored.settingHash) return@withWriteTransaction
            db.syncClockDao().upsert(
                SyncClockEntity(
                    SyncCollection.SETTING.wire,
                    key.name,
                    encode(stored.copy(settingHash = next)),
                ),
            )
        }
    }

    /**
     * Deletes the superseded intent under its own complete clock (so a newer coalesced intent
     * survives) and baselines the value that actually won locally — clocklessly when the winner
     * was never captured.
     */
    private suspend fun retire(
        key: SettingKey<*>,
        row: SyncOutboxEntity,
        current: String?,
    ) {
        db.withWriteTransaction {
            db.syncOutboxDao().deleteIfUnchanged(
                SyncCollection.SETTING.wire,
                key.name,
                "v",
                row.hlc,
                row.nodeId,
            )
            // Nothing stored survives the retirement itself; a wiped value baselines nothing.
            if (current == null) return@withWriteTransaction
            val clockRow = db.syncClockDao().get(SyncCollection.SETTING.wire, key.name)
            val raw = clockRow?.clocks?.let(::decode) ?: return@withWriteTransaction
            db.syncClockDao().upsert(
                clockRow.copy(
                    clocks = encode(raw.copy(settingHash = SettingBaseline(settingBaselineHash(current)))),
                ),
            )
        }
    }

    /**
     * Step 3 of [install]: every synced key the account does not carry — no `v` in raw state,
     * no pending marker and no committed intent — uploads the stored value, as at the first
     * link. A pending marker means the server decides the key, so it is left for the drain.
     */
    private suspend fun captureAbsentKeys() {
        for (key in AllSettingKeys.list) {
            if (!key.synced) continue
            writeLocks.withLock(key) {
                if (db.syncOutboxDao().get(SyncCollection.SETTING.wire, key.name, "v") != null) {
                    return@withLock
                }
                val raw =
                    db
                        .syncClockDao()
                        .get(SyncCollection.SETTING.wire, key.name)
                        ?.clocks
                        ?.let(::decode)
                if (raw?.state?.fields?.containsKey("v") == true || raw?.pendingSetting != null) {
                    return@withLock
                }
                val json = settings.currentJson(key) ?: return@withLock
                db.withWriteTransaction {
                    db
                        .syncOutboxDao()
                        .captureIntent(SyncCollection.SETTING.wire, key.name, "v", json)
                }
            }
        }
    }

    private fun parseHlc(clock: String): Hlc? = runCatching { Hlc.parse(clock) }.getOrNull()

    private fun decode(json: String): RawSyncRecord? =
        runCatching { SyncJson.decodeFromString<RawSyncRecord>(json) }.getOrNull()

    private fun encode(raw: RawSyncRecord): String = SyncJson.encodeToString(raw)
}
