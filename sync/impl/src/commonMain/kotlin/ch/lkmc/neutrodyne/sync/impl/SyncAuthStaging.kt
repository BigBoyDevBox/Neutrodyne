// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.domain.SecretStore
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 10 Passwords / Raw state and Durable apply effects: the protected snapshot journal.
 *
 * A wire `auth` carries a plaintext `{u,p}` pair that must never reach `sync_clock`, the
 * outbox, the first-link staging file, an acknowledgement or a log. Before a page's Room
 * transaction — or a first-link staging line — is written, [stageAuthCandidates] copies each
 * podcast record's auth into the protected `SecretStore` under an immutable
 * `sync:pending:<account>:<rid>:<clock>` origin. The committed raw state then carries only a
 * reference (`AuthReference`) and the durable effect (`PendingAuth`); the winner's complete
 * HLC — packed counter and origin node — is part of the key, so a snapshot can never bind to
 * a different write.
 *
 * [decideAuth] compares the remote candidate against the stored reference clock and the
 * pending local intent: a strictly newer remote wins and journals `pendingAuth`; an
 * equal-clock redelivery keeps the existing marker; a losing candidate's staged snapshot is
 * returned in [AuthDecision.dropKeys] for post-commit cleanup. A pending local credential
 * keeps its own reference at its credential origin, so a later resync can hydrate the replay
 * from the real credential — never from raw metadata.
 */
internal const val PENDING_AUTH_PREFIX = "sync:pending:"

/** The staging origin of one immutable auth snapshot: account, record and complete clock. */
internal fun pendingAuthKey(
    account: String,
    rid: String,
    clock: String,
): String = "$PENDING_AUTH_PREFIX$account:$rid:$clock"

/** A validated, staged auth snapshot: its `SecretStore` key and the winning complete clock. */
internal class StagedAuth(
    val storeKey: String,
    val clock: String,
)

/** Decodes a `sync_clock.clocks` envelope; null on malformed JSON, as `SyncClockStore` reads it. */
internal fun decodeRawClocks(json: String?): RawSyncRecord? =
    json?.let { runCatching { SyncJson.decodeFromString<RawSyncRecord>(it) }.getOrNull() }

/** The `{u,p}` pair of a wire auth value, or null when the shape is not a credential. */
internal fun authPair(value: JsonElement?): Pair<String, CharArray>? {
    val obj = value as? JsonObject ?: return null
    val user = (obj["u"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
    val pass = (obj["p"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
    return user to pass.toCharArray()
}

/** Every `sync:pending:` key raw state still references — markers and references both count. */
internal suspend fun referencedAuthKeys(db: NeutrodyneDatabase): Set<String> =
    db
        .syncClockDao()
        .all()
        .asSequence()
        .mapNotNull { decodeRawClocks(it.clocks) }
        .flatMap { listOfNotNull(it.authReference?.storeKey, it.pendingAuth?.reference) }
        .filter { it.startsWith(PENDING_AUTH_PREFIX) }
        .toSet()

/**
 * Stages every podcast record's wire `auth` in the protected store before the raw records
 * commit. Runs inside the credential-commit coordinator: a `SecretStore.put` is a credential
 * write, and the orphan sweep's enumeration must not interleave it (03). A `SecretStore`
 * failure propagates — the page never commits, so neither the cursor nor any credential
 * state advances past a snapshot that never reached the store.
 *
 * A record whose `auth` is not a `{u,p}` pair with a parseable clock stages nothing; the
 * record fails normal validation in the merge. An unchanged redelivery of an already
 * referenced snapshot is not rewritten (10 idempotent redelivery).
 */
internal suspend fun stageAuthCandidates(
    db: NeutrodyneDatabase,
    secrets: SecretStore,
    coordinator: CredentialCommitCoordinator,
    records: List<RecordDto>,
): Map<String, StagedAuth> =
    coordinator.withCredentialCommit {
        val account = db.syncStateDao().get()?.accountId ?: "local"
        val staged = LinkedHashMap<String, StagedAuth>()
        for (dto in records) {
            if (dto.coll != SyncCollection.PODCAST.wire) continue
            val rid =
                runCatching { RecordId.fromWire(SyncCollection.PODCAST, dto.id) }
                    .getOrNull()
                    ?.rid ?: continue
            val field = dto.fields["auth"] ?: continue
            val (user, pass) = authPair(field.v) ?: continue
            val clock = field.c?.let { runCatching { Hlc.parse(it) }.getOrNull() } ?: continue
            val key = pendingAuthKey(account, rid, clock.toString())
            val referenced = decodeRawClocks(db.syncClockDao().get(SyncCollection.PODCAST.wire, rid)?.clocks)
            if (referenced?.authReference?.clock != clock.toString() || secrets.get(key) == null) {
                secrets.put(key, user, pass)
            }
            staged[rid] = StagedAuth(key, clock.toString())
        }
        staged
    }

/**
 * What one podcast record's `auth` resolves to against the stored raw state and the pending
 * outbox: the protected reference to persist, the durable effect to journal, the staged keys
 * that lost and whether the remote write won (its losing local intent leaves the outbox).
 */
internal class AuthDecision(
    val reference: AuthReference?,
    val pending: PendingAuth?,
    val dropKeys: List<String>,
    val remoteWon: Boolean,
)

internal fun decideAuth(
    stored: RawSyncRecord?,
    pending: RecordState?,
    merged: RecordState,
    authField: FieldState?,
    staged: StagedAuth?,
): AuthDecision {
    val storedRef = stored?.authReference
    val refClock = storedRef?.clock?.let { runCatching { Hlc.parse(it) }.getOrNull() }
    val localClock = pending?.fields?.get("auth")?.c
    val remoteClock = authField?.c
    val mergedOrigin = jsonString(merged.fields["credentialOrigin"]?.v)
    val pendingOrigin = jsonString(pending?.fields?.get("credentialOrigin")?.v)
    val drops = mutableListOf<String>()

    // A strictly newer remote write wins only when it bound to the merged record's winning
    // credential origin — a bare `auth` without a provable origin can never install.
    val remoteWon =
        remoteClock != null && staged != null && mergedOrigin != null &&
            (refClock == null || remoteClock > refClock) &&
            (localClock == null || remoteClock > localClock)
    if (remoteWon) {
        for (key in listOfNotNull(storedRef?.storeKey, stored?.pendingAuth?.reference)) {
            if (key.startsWith(PENDING_AUTH_PREFIX) && key != staged!!.storeKey) drops += key
        }
        return AuthDecision(
            reference = AuthReference(staged!!.storeKey, mergedOrigin!!, remoteClock.toString()),
            pending = PendingAuth(staged.storeKey, mergedOrigin, remoteClock.toString()),
            dropKeys = drops,
            remoteWon = true,
        )
    }
    // A strictly different, losing remote snapshot has no consumer; an equal-clock redelivery
    // of the referenced write is idempotent, not a loser.
    if (staged != null && remoteClock != null && remoteClock != refClock) drops += staged.storeKey

    // A pending local credential keeps the reference at its own credential origin — the live
    // credential the push hydrates — so a resync replay stays bound to this account's store.
    if (localClock != null && (refClock == null || localClock > refClock)) {
        val origin = pendingOrigin ?: mergedOrigin
        if (origin != null) {
            return AuthDecision(
                reference = AuthReference(origin, origin, localClock.toString()),
                pending = stored?.pendingAuth,
                dropKeys = drops,
                remoteWon = false,
            )
        }
    }
    return AuthDecision(storedRef, stored?.pendingAuth, drops, remoteWon = false)
}

/**
 * Removes every candidate key raw state no longer references — losing snapshots, failed-page
 * snapshots and superseded pending markers — under the coordinator, like every credential
 * delete. A key still referenced by a committed marker or reference is kept (10 cleanup).
 */
internal suspend fun dropUnreferencedStaged(
    db: NeutrodyneDatabase,
    secrets: SecretStore,
    coordinator: CredentialCommitCoordinator,
    candidates: Set<String>,
) {
    if (candidates.isEmpty()) return
    val referenced = referencedAuthKeys(db)
    coordinator.withCredentialCommit {
        for (key in candidates - referenced) {
            suspendRunCatching { secrets.remove(key) }
        }
    }
}

/** Drops every staged snapshot this slice owns — unlink, account switch, restore reset. */
internal suspend fun SecretStore.dropStagedAuthSnapshots() {
    for (origin in originsWithPrefix(PENDING_AUTH_PREFIX)) {
        suspendRunCatching { remove(origin) }
    }
}

private fun jsonString(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
