// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.CredentialEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.GroupChannelSync
import ch.lkmc.neutrodyne.core.domain.NoPlaybackSync
import ch.lkmc.neutrodyne.core.domain.SyncIngestHook
import ch.lkmc.neutrodyne.core.testing.FakeCredentialCommitCoordinator
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeDto
import ch.lkmc.neutrodyne.sync.protocol.dto.ChangeResultDto
import ch.lkmc.neutrodyne.sync.protocol.dto.FieldValue
import ch.lkmc.neutrodyne.sync.protocol.dto.RecordDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 10 Passwords: the protected feed-auth boundary — wire `{u,p}` never reaches raw state, the
 * snapshot stages before Room writes, and the durable `pendingAuth` marker drives the
 * `SecretStore`/`credentialId` commit under the credential coordinator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class SyncProtectedAuthTestBase {
    private val now = 1_800_000_000_000L
    private val remote = NodeId("0000000000000002")
    private val localNode = NodeId("0000000000000001")
    private val account = "acct-1"
    private val deviceId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    private val rid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val feedUrl = "https://a.example/feed.xml"
    private val origin = "https://a.example"
    private val user = "sync-user"
    private val pass = "s3ntinel-pass-9f8e"

    @Test
    fun wireAuthNeverReachesRawState() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val clock = Hlc.of(now, 10, remote)

                assertEquals(1, h.applier.applyPage(listOf(authRecord()), roundId = 1).applied)

                val row = db.syncClockDao().get(SyncCollection.PODCAST.wire, rid)!!
                assertFalse(row.clocks.contains("s3ntinel"))
                assertFalse(row.clocks.contains(user))
                val raw = decodeRawClocks(row.clocks)!!
                assertNull(raw.state.fields["auth"])
                assertEquals(JsonPrimitive(origin), raw.state.fields["credentialOrigin"]?.v)

                val key = pendingAuthKey(account, rid, clock.toString())
                assertEquals(AuthReference(key, origin, clock.toString()), raw.authReference)
                assertEquals(PendingAuth(key, origin, clock.toString()), raw.pendingAuth)
                val staged = h.secrets.get(key)
                assertNotNull(staged)
                assertEquals(user, staged.username)
                assertEquals(pass, staged.secret.concatToString())
            } finally {
                db.close()
            }
        }

    @Test
    fun aStoreFailureBeforeStagingDropsNoRawState() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.secrets.failure = IllegalStateException("store locked")

                assertFailsWith<IllegalStateException> {
                    h.applier.applyPage(listOf(authRecord()), roundId = 1)
                }

                assertEquals(0, db.syncClockDao().count())
                assertNull(db.podcastDao().bySyncId(rid))
                assertTrue(h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX).isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun drainInstallsTheCredentialAndClearsTheMarker() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.applier.applyPage(listOf(authRecord()), roundId = 1)
                val staged = decodeRawClocks(clocksJson(db))!!.pendingAuth!!.reference
                seedCredential(db, h.secrets, origin)

                h.effects.drainPasswords()

                val podcast = db.podcastDao().bySyncId(rid)!!
                assertNotNull(podcast.credentialId)
                assertFalse(podcast.needsCredentials)
                val stored = h.secrets.get(origin)
                assertNotNull(stored)
                assertEquals(user, stored.username)
                assertEquals(pass, stored.secret.concatToString())

                val raw = decodeRawClocks(clocksJson(db))!!
                assertNull(raw.pendingAuth)
                val ref = raw.authReference!!
                // The accepted snapshot stays the reference — the exact bytes accepted at the
                // winning complete clock, immune to later edits of the live credential.
                assertEquals(staged, ref.storeKey)
                assertEquals(origin, ref.credentialOrigin)
                assertEquals(Hlc.of(now, 10, remote).toString(), ref.clock)
                val retained = h.secrets.get(staged)
                assertNotNull(retained)
                assertEquals(pass, retained.secret.concatToString())
                assertEquals(setOf(staged), h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX))
            } finally {
                db.close()
            }
        }

    @Test
    fun aCrashBetweenTheStoresRepeatsIdempotently() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.applier.applyPage(listOf(authRecord()), roundId = 1)
                // Crash after the `put` committed but before `credentialId`: the secret is at
                // its origin, unreferenced. The next drain binds the same row, not a duplicate.
                val firstId = seedCredential(db, h.secrets, origin)

                h.effects.drainPasswords()

                assertEquals(firstId, db.podcastDao().bySyncId(rid)!!.credentialId)
                assertNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)

                val commits = h.credentials.commits
                h.effects.drainPasswords()
                assertEquals(firstId, db.podcastDao().bySyncId(rid)!!.credentialId)
                // Nothing pending: only the orphan sweep still enters the coordinator.
                assertEquals(commits + 1, h.credentials.commits)
            } finally {
                db.close()
            }
        }

    @Test
    fun aLockedStoreKeepsTheMarkerPending() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.applier.applyPage(listOf(authRecord()), roundId = 1)
                seedCredential(db, h.secrets, origin)

                h.secrets.failure = IllegalStateException("store locked")
                assertFailsWith<IllegalStateException> { h.effects.drainPasswords() }
                assertNotNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
                assertNull(db.podcastDao().bySyncId(rid)!!.credentialId)

                h.secrets.failure = null
                h.effects.drainPasswords()
                assertNotNull(db.podcastDao().bySyncId(rid)!!.credentialId)
                assertNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
            } finally {
                db.close()
            }
        }

    @Test
    fun aMissingPodcastKeepsTheMarkerUntilItArrives() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val clock = Hlc.of(now, 10, remote).toString()
                val key = pendingAuthKey(account, rid, clock)
                h.secrets.put(key, user, pass.toCharArray())
                seedRaw(db, key, clock)
                seedCredential(db, h.secrets, origin)

                h.effects.drainPasswords()
                assertNotNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
                assertNotNull(h.secrets.get(key))

                db
                    .podcastDao()
                    .insertPodcast(
                        podcastEntity(syncId = rid) { copy(needsCredentials = true) },
                    )
                h.effects.drainPasswords()

                val podcast = db.podcastDao().bySyncId(rid)!!
                assertNotNull(podcast.credentialId)
                assertFalse(podcast.needsCredentials)
                assertNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
                // The snapshot is the reference now: it stays while raw state owns it.
                assertNotNull(h.secrets.get(key))
                assertEquals(key, decodeRawClocks(clocksJson(db))!!.authReference!!.storeKey)
            } finally {
                db.close()
            }
        }

    @Test
    fun aMissingSnapshotFlagsNeedsCredentialsAndKeepsTheMarker() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.applier.applyPage(listOf(authRecord()), roundId = 1)
                val key = decodeRawClocks(clocksJson(db))!!.pendingAuth!!.reference
                h.secrets.remove(key)

                h.effects.drainPasswords()

                assertTrue(db.podcastDao().bySyncId(rid)!!.needsCredentials)
                assertNotNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
            } finally {
                db.close()
            }
        }

    @Test
    fun equalClockRedeliveryKeepsOneSnapshotAndMarker() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val page = listOf(authRecord())
                h.applier.applyPage(page, roundId = 1)
                val second = h.applier.applyPage(page, roundId = 2)

                assertEquals(1, second.stale)
                assertEquals(1, h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX).size)
                assertNotNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)

                seedCredential(db, h.secrets, origin)
                h.effects.drainPasswords()
                assertNotNull(db.podcastDao().bySyncId(rid)!!.credentialId)
            } finally {
                db.close()
            }
        }

    @Test
    fun aLosingRemoteAuthDropsItsStagedSnapshot() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.applier.applyPage(listOf(authRecord(counter = 20)), roundId = 1)
                h.applier.applyPage(
                    listOf(authUpdate(counter = 10)),
                    roundId = 2,
                )

                val winner = pendingAuthKey(account, rid, Hlc.of(now, 20, remote).toString())
                assertEquals(setOf(winner), h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX))
                assertEquals(
                    winner,
                    decodeRawClocks(clocksJson(db))!!.pendingAuth!!.reference,
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerLocalAuthIsNotClobberedByRemote() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                db.podcastDao().insertPodcast(podcastEntity(syncId = rid))
                val h = harness(db)
                val local = Hlc.of(now + 60_000, 0, localNode)
                val localOrigin = "https://local.example"
                captureStored(db, rid, "auth", local, "{\"u\":\"x\",\"p\":\"y\"}")
                captureStored(db, rid, "credentialOrigin", local, "\"$localOrigin\"")

                h.applier.applyPage(listOf(authUpdate(counter = 10)), roundId = 1)

                val raw = decodeRawClocks(clocksJson(db))!!
                assertNull(raw.pendingAuth)
                // The local intent is only pending: the reference keeps the last accepted
                // state until the server's acknowledgement binds the exact sent snapshot.
                assertNull(raw.authReference)
                assertNotNull(
                    db.syncOutboxDao().get(SyncCollection.PODCAST.wire, rid, "auth"),
                )
                assertTrue(h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX).isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun aNewerRemoteAuthReplacesTheInstalledCredential() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val newOrigin = "https://b.example"
                seedCredential(db, h.secrets, origin)
                seedCredential(db, h.secrets, newOrigin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()
                val oldCredential = db.podcastDao().bySyncId(rid)!!.credentialId

                h.applier.applyPage(
                    listOf(authUpdate(origin = newOrigin, pass = "p-two", counter = 20)),
                    roundId = 2,
                )
                h.effects.drainPasswords()

                val podcast = db.podcastDao().bySyncId(rid)!!
                assertNotNull(podcast.credentialId)
                assertTrue(podcast.credentialId != oldCredential)
                assertEquals(
                    "p-two",
                    h.secrets
                        .get(newOrigin)
                        ?.secret
                        ?.concatToString(),
                )
                val raw = decodeRawClocks(clocksJson(db))!!
                assertNull(raw.pendingAuth)
                val winner = pendingAuthKey(account, rid, Hlc.of(now, 20, remote).toString())
                val ref = raw.authReference!!
                assertEquals(winner, ref.storeKey)
                assertEquals(newOrigin, ref.credentialOrigin)
                assertEquals(Hlc.of(now, 20, remote).toString(), ref.clock)
                assertEquals(setOf(winner), h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX))
            } finally {
                db.close()
            }
        }

    @Test
    fun anAcknowledgementBindsTheCompleteOriginalClock() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                seedCredential(db, h.secrets, origin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()

                val acked = Hlc.of(now + 5, 7, remote)
                h.effects.acknowledge(
                    listOf(
                        ChangeDto(
                            coll = SyncCollection.PODCAST.wire,
                            id = JsonPrimitive(rid),
                            fields =
                                mapOf(
                                    "auth" to
                                        FieldValue(authValue(user, pass), acked.toString()),
                                    "credentialOrigin" to
                                        FieldValue(JsonPrimitive(origin), acked.toString()),
                                ),
                        ),
                    ),
                    listOf(ChangeResultDto(0, "applied")),
                )
                var ref = decodeRawClocks(clocksJson(db))!!.authReference!!
                assertEquals(acked.toString(), ref.clock)
                // The bound key is the staged snapshot of the exact bytes that were sent.
                val boundKey = pendingAuthKey(account, rid, acked.toString())
                assertEquals(boundKey, ref.storeKey)
                assertNotNull(h.secrets.get(boundKey))

                val older = Hlc.of(now, 3, remote)
                h.effects.acknowledge(
                    listOf(
                        ChangeDto(
                            coll = SyncCollection.PODCAST.wire,
                            id = JsonPrimitive(rid),
                            fields =
                                mapOf(
                                    "auth" to
                                        FieldValue(authValue(user, pass), older.toString()),
                                ),
                        ),
                    ),
                    listOf(ChangeResultDto(0, "applied")),
                )
                ref = decodeRawClocks(clocksJson(db))!!.authReference!!
                assertEquals(acked.toString(), ref.clock)
                assertEquals(boundKey, ref.storeKey)
            } finally {
                db.close()
            }
        }

    /**
     * 10 Passwords / exact accepted-clock replay: after the remote credential installs, a
     * later local edit of the live credential must not change what a replay at the accepted
     * clock resends — the immutable snapshot bound at acceptance is the truth.
     */
    @Test
    fun anInstalledAuthReplaysItsAcceptedBytesNotTheLiveCredential() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                seedCredential(db, h.secrets, origin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()
                val accepted = Hlc.of(now, 10, remote)

                // The live credential changes after acceptance — a local password rotation.
                h.secrets.put(origin, user, "s3ntinel-rotated-4a1d".toCharArray())
                captureStored(db, rid, "auth", accepted, "\"replay\"")

                val pushed =
                    ChangeBuilder(db, h.secrets) { true }
                        .build(
                            db.syncOutboxDao().rows().filter {
                                it.coll == SyncCollection.PODCAST.wire && it.rid == rid
                            },
                        ).single { it.fields.containsKey("auth") }
                val value = pushed.fields.getValue("auth")
                assertEquals(accepted.toString(), value.c)
                // The replay reasserts the accepted snapshot, not the rotated credential.
                assertEquals(authValue(user, pass), value.v)
            } finally {
                db.close()
            }
        }

    /**
     * An acknowledgement binds the exact bytes that were sent — staged under the acknowledged
     * complete clock — even when a newer local edit raced the HTTP round. The newer outbox
     * intent survives the same atomic commit and the delayed ack cannot replace it.
     */
    @Test
    fun anAcknowledgedSendBindsTheSentBytesUnderANewerLocalEdit() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                seedCredential(db, h.secrets, origin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()
                val sent = Hlc.of(now + 30, 2, localNode)
                val newer = Hlc.of(now + 90, 0, localNode)
                db.withWriteTransaction {
                    db.syncOutboxDao().upsert(
                        ch.lkmc.neutrodyne.core.database.SyncOutboxEntity(
                            coll = SyncCollection.PODCAST.wire,
                            rid = rid,
                            field = "auth",
                            hlc = newer.packed,
                            nodeId = localNode.value,
                        ),
                    )
                }
                // The credential rotated while the push was in flight: what the server
                // accepted is the older pair, carried by the acknowledged change itself.
                h.secrets.put(origin, user, "s3ntinel-rotated-6b2e".toCharArray())

                h.effects.acknowledge(
                    listOf(
                        ChangeDto(
                            coll = SyncCollection.PODCAST.wire,
                            id = JsonPrimitive(rid),
                            fields =
                                mapOf(
                                    "auth" to FieldValue(authValue(user, pass), sent.toString()),
                                    "credentialOrigin" to
                                        FieldValue(JsonPrimitive(origin), sent.toString()),
                                ),
                        ),
                    ),
                    listOf(ChangeResultDto(0, "applied")),
                )

                val key = pendingAuthKey(account, rid, sent.toString())
                val raw = decodeRawClocks(clocksJson(db))!!
                assertEquals(AuthReference(key, origin, sent.toString()), raw.authReference)
                val staged = h.secrets.get(key)
                assertNotNull(staged)
                assertEquals(pass, staged.secret.concatToString())

                // The newer intent outlives the old ack, and a replay at the acked clock sends
                // the bytes that were accepted then — not the rotated live credential.
                assertNotNull(
                    db.syncOutboxDao().get(SyncCollection.PODCAST.wire, rid, "auth"),
                )
                db.withWriteTransaction {
                    db.syncOutboxDao().deleteField(SyncCollection.PODCAST.wire, rid, "auth")
                }
                captureStored(db, rid, "auth", sent, "\"replay\"")
                val rows =
                    db.syncOutboxDao().rows().filter {
                        it.coll == SyncCollection.PODCAST.wire && it.rid == rid
                    }
                val replayed =
                    ChangeBuilder(db, h.secrets) { true }.build(rows).single {
                        it.fields["auth"]?.c == sent.toString()
                    }
                assertEquals(authValue(user, pass), replayed.fields.getValue("auth").v)
            } finally {
                db.close()
            }
        }

    /**
     * A snapshot the ack path staged is owned by the still-pending outbox intent until the
     * acknowledgement's atomic commit binds it — the sweep derives that ownership from the
     * row's account, rid and complete clock.
     */
    @Test
    fun aPendingOutboxIntentKeepsItsStagedSnapshotFromTheSweep() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val clock = Hlc.of(now + 30, 2, localNode)
                val key = pendingAuthKey(account, rid, clock.toString())
                h.secrets.put(key, user, pass.toCharArray())
                db.withWriteTransaction {
                    db.syncOutboxDao().upsert(
                        ch.lkmc.neutrodyne.core.database.SyncOutboxEntity(
                            coll = SyncCollection.PODCAST.wire,
                            rid = rid,
                            field = "auth",
                            hlc = clock.packed,
                            nodeId = localNode.value,
                        ),
                    )
                }

                h.effects.drainPasswords()
                assertNotNull(h.secrets.get(key), "the pending intent owns its snapshot")

                db.withWriteTransaction {
                    db.syncOutboxDao().deleteAcked(
                        SyncCollection.PODCAST.wire,
                        rid,
                        "auth",
                        clock.packed,
                        localNode.value,
                    )
                }
                h.effects.drainPasswords()
                assertNull(h.secrets.get(key), "unowned after the intent is acknowledged")
            } finally {
                db.close()
            }
        }

    @Test
    fun theShareGateControlsTheHydratedPush() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                seedCredential(db, h.secrets, origin)
                h.applier.applyPage(listOf(authRecord(counter = 10)), roundId = 1)
                h.effects.drainPasswords()
                val clock = Hlc.of(now, 10, remote)
                captureStored(db, rid, "auth", clock, "\"replay\"")
                val rows =
                    db.syncOutboxDao().rows().filter {
                        it.coll == SyncCollection.PODCAST.wire && it.rid == rid
                    }

                val gated = ChangeBuilder(db, h.secrets) { false }.build(rows)
                assertTrue(gated.isEmpty())

                val pushed =
                    ChangeBuilder(db, h.secrets) { true }.build(rows).single {
                        it.fields.containsKey("auth")
                    }
                val value = pushed.fields.getValue("auth")
                assertEquals(clock.toString(), value.c)
                assertEquals(
                    JsonObject(
                        mapOf("u" to JsonPrimitive(user), "p" to JsonPrimitive(pass)),
                    ),
                    value.v,
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun aFeedWithOnlyAnOriginFlagsNeedsCredentials() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                val result = h.applier.applyPage(listOf(authRecord(includeAuth = false)), roundId = 1)

                assertEquals(1, result.applied)
                val podcast = db.podcastDao().bySyncId(rid)!!
                assertTrue(podcast.needsCredentials)
                assertEquals(listOf(podcast.id), result.feedsNeedingCredentials)
                assertNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
            } finally {
                db.close()
            }
        }

    @Test
    fun orphanSweepKeepsReferencedKeysAndLiveStagingFiles() =
        runTest {
            val db = newTestDb()
            val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "nd-auth-${rid.take(8)}"
            try {
                link(db)
                val h = harness(db, cacheDir = dir)
                // A pending marker whose podcast never arrived keeps its snapshot referenced.
                val clock = Hlc.of(now, 10, remote).toString()
                val referenced = pendingAuthKey(account, rid, clock)
                h.secrets.put(referenced, user, pass.toCharArray())
                seedRaw(db, referenced, clock)
                val orphan = pendingAuthKey(account, "orphan", clock)
                h.secrets.put(orphan, "u", "p".toCharArray())

                // A live first-link staging file is itself a consumer: the sweep is skipped.
                FileSystem.SYSTEM.createDirectories(dir)
                FileSystem.SYSTEM.write(dir / "sync-link-$deviceId.jsonl") { writeUtf8("{}\n") }
                h.effects.drainPasswords()
                assertEquals(
                    setOf(referenced, orphan),
                    h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX),
                )

                FileSystem.SYSTEM.delete(dir / "sync-link-$deviceId.jsonl")
                h.effects.drainPasswords()
                assertEquals(
                    setOf(referenced),
                    h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX),
                )
            } finally {
                FileSystem.SYSTEM.deleteRecursively(dir)
                db.close()
            }
        }

    @Test
    fun prefixEnumerationAndUnlinkSweepOnlyTouchStagingOrigins() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.secrets.put("https://feed.example", "u", "p".toCharArray())
                h.secrets.put("sync:example.test", "t", "s".toCharArray())
                val staged = pendingAuthKey(account, rid, "0-0000000000000000")
                h.secrets.put(staged, "u", "p".toCharArray())

                assertEquals(
                    setOf(staged),
                    h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX),
                )
                h.secrets.dropStagedAuthSnapshots()
                assertTrue(h.secrets.originsWithPrefix(PENDING_AUTH_PREFIX).isEmpty())
                assertNotNull(h.secrets.get("https://feed.example"))
                assertNotNull(h.secrets.get("sync:example.test"))
            } finally {
                db.close()
            }
        }

    @Test
    fun aDrainWaitsForTheCredentialCoordinator() =
        runTest {
            val db = newTestDb()
            try {
                link(db)
                val h = harness(db)
                h.applier.applyPage(listOf(authRecord()), roundId = 1)
                seedCredential(db, h.secrets, origin)

                val gate = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                val holder =
                    launch {
                        h.credentials.withCredentialCommit {
                            entered.complete(Unit)
                            gate.await()
                        }
                    }
                entered.await()
                val drain = launch { h.effects.drainPasswords() }
                runCurrent()

                assertTrue(drain.isActive)
                assertNotNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
                assertNull(db.podcastDao().bySyncId(rid)!!.credentialId)

                gate.complete(Unit)
                drain.join()
                holder.join()
                assertNotNull(db.podcastDao().bySyncId(rid)!!.credentialId)
                assertNull(decodeRawClocks(clocksJson(db))!!.pendingAuth)
            } finally {
                db.close()
            }
        }

    // --- fixture -----------------------------------------------------------------------------------

    protected abstract fun newTestDb(): NeutrodyneDatabase

    private suspend fun link(db: NeutrodyneDatabase) {
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(
                    SyncStateEntity(
                        enabled = true,
                        accountId = account,
                        deviceId = deviceId,
                        nodeId = localNode.value,
                        hlc = 1L shl 16,
                        linkedAt = now,
                    ),
                )
        }
    }

    private class Harness(
        val applier: SyncApplier,
        val effects: SyncApplyEffects,
        val secrets: FakeSecretStore,
        val credentials: FakeCredentialCommitCoordinator,
    )

    private fun harness(
        db: NeutrodyneDatabase,
        secrets: FakeSecretStore = FakeSecretStore(),
        credentials: FakeCredentialCommitCoordinator = FakeCredentialCommitCoordinator(),
        cacheDir: okio.Path = "__absent_cache__".toPath(),
    ): Harness =
        Harness(
            applier =
                SyncApplier(
                    db = db,
                    clock = TestClock(now),
                    secrets = secrets,
                    settings = FakeSettingsSyncPort(),
                    playback = NoPlaybackSync,
                    syncIngestHook = SyncIngestHook.None,
                    channelSync =
                        object : GroupChannelSync {
                            override suspend fun sync() = Unit

                            override suspend fun onUuidChanged(
                                oldUuid: String,
                                newUuid: String,
                            ) = Unit

                            override suspend fun onDeleted(uuid: String) = Unit
                        },
                    sharePasswords = { false },
                    coordinator = credentials,
                ),
            effects =
                SyncApplyEffects(
                    db,
                    FakeSettingsSyncPort(),
                    TestSettingsWriteCoordinator(),
                    { true },
                    secrets,
                    credentials,
                    cacheDir,
                ),
            secrets = secrets,
            credentials = credentials,
        )

    private suspend fun clocksJson(db: NeutrodyneDatabase): String? =
        db.syncClockDao().get(SyncCollection.PODCAST.wire, rid)?.clocks

    /**
     * A credential already at [origin]: the fake's `put` id and the `credential` row a real
     * `SecretStore.put` writes inside Room — `podcast.credentialId` is its FK child.
     */
    private suspend fun seedCredential(
        db: NeutrodyneDatabase,
        secrets: FakeSecretStore,
        origin: String,
        secret: String = pass,
    ): Long {
        val id = secrets.put(origin, user, secret.toCharArray())!!
        db
            .credentialDao()
            .insert(
                CredentialEntity(
                    id = id,
                    origin = origin,
                    username = user,
                    secretCipher = null,
                    iv = null,
                    createdAt = now,
                ),
            )
        return id
    }

    private suspend fun captureStored(
        db: NeutrodyneDatabase,
        rid: String,
        field: String,
        clock: Hlc,
        literal: String,
    ) {
        db.withWriteTransaction {
            db
                .syncOutboxDao()
                .captureStored(
                    SyncCollection.PODCAST.wire,
                    rid,
                    field,
                    clock.packed,
                    clock.nodeId.value,
                    literal,
                )
        }
    }

    /** A raw marker as a crashed or pre-arrival page left it: reference and marker, no podcast. */
    private suspend fun seedRaw(
        db: NeutrodyneDatabase,
        key: String,
        clock: String,
    ) {
        SyncClockStore(db)
            .put(
                SyncCollection.PODCAST.wire,
                rid,
                RawSyncRecord(
                    state =
                        RecordState(
                            SyncCollection.PODCAST,
                            fields =
                                mapOf(
                                    "credentialOrigin" to
                                        FieldState(JsonPrimitive(origin), Hlc.parse(clock)),
                                ),
                        ),
                    authReference = AuthReference(key, origin, clock),
                    pendingAuth = PendingAuth(key, origin, clock),
                ),
            )
    }

    private fun authRecord(
        counter: Long = 10,
        includeAuth: Boolean = true,
    ): RecordDto {
        val c = Hlc.of(now, counter, remote).toString()
        val fields =
            mutableMapOf(
                "subscribed" to FieldValue(JsonPrimitive(true), c),
                "feedUrl" to FieldValue(JsonPrimitive(feedUrl), c),
                "title" to FieldValue(JsonPrimitive("Feed $rid"), c),
                "subscribedAt" to FieldValue(JsonPrimitive(now), c),
                "credentialOrigin" to FieldValue(JsonPrimitive(origin), c),
            )
        if (includeAuth) {
            fields["auth"] = FieldValue(authValue(user, pass), c)
        }
        return RecordDto(
            coll = SyncCollection.PODCAST.wire,
            id = JsonPrimitive(rid),
            seq = counter,
            by = deviceId,
            fields = fields,
        )
    }

    /** An auth-only update on an existing record: a credential origin plus the `{u,p}` pair. */
    private fun authUpdate(
        origin: String = this.origin,
        pass: String = this.pass,
        counter: Long,
    ): RecordDto {
        val c = Hlc.of(now, counter, remote).toString()
        return RecordDto(
            coll = SyncCollection.PODCAST.wire,
            id = JsonPrimitive(rid),
            seq = counter,
            by = deviceId,
            fields =
                mapOf(
                    "credentialOrigin" to FieldValue(JsonPrimitive(origin), c),
                    "auth" to FieldValue(authValue(user, pass), c),
                ),
        )
    }

    private fun authValue(
        u: String,
        p: String,
    ): JsonObject = JsonObject(mapOf("u" to JsonPrimitive(u), "p" to JsonPrimitive(p)))
}
