// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Build
import android.os.Looper
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.testing.FakePlaySessionStore
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.core.PlayedRule
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import ch.lkmc.neutrodyne.playback.core.StartPositionRule
import ch.lkmc.neutrodyne.playback.core.StartRequest
import ch.lkmc.neutrodyne.playback.core.WindowDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A new start for another episode leaves the outgoing current in the near-end window after it has
 * played. The captured departure must therefore use NEW_START, not the PAUSE default, so the
 * session transaction can mark it played (06-playback's SEEK/new-start rule).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = PlaybackTestApplication::class, sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class NewStartDepartureTest {
    @Test fun anExplicitNewStartMarksTheNearEndCurrentPlayed() {
        LifecycleHarness(RuntimeEnvironment.getApplication()).use { h ->
            h.seedCurrent(A, positionMs = 10_000)
            h.lookup.items.value += B to media(id = B)
            h.publish(h.store.queue.copy(upNext = listOf(QueueItem(B, QueueOrigin.UP_NEXT))))
            val ports =
                PlaybackPorts(
                    starter = h.starter,
                    planner =
                        QueueWindowPlanner(
                            h.queues,
                            h.capabilities,
                            h.positions,
                            h.local,
                            h.clock,
                            WindowDiff(),
                            StartPositionRule(),
                            PlayedRule(),
                        ),
                    saver = h.saver,
                    effective = h.effective,
                    sessions = h.sessions,
                    queues = h.queues,
                    positions = h.positions,
                    lookup = h.lookup,
                    items = h.items,
                    chapters = h.chapters,
                )
            val fakeClock = FakeClock(false)
            val player =
                TestExoPlayerBuilder(h.app)
                    .setClock(fakeClock)
                    .setMediaSourceFactory(AdapterSources())
                    .build()
            val bridge =
                AndroidServiceBridge(
                    player,
                    ports,
                    h.access,
                    h.scope,
                    { true },
                    {},
                    player::pause,
                )
            bridge.attach()
            try {
                pumpUntil { player.mediaItemCount == 2 && player.episodeIdOrNull() == A }
                player.prepare()
                pumpUntil { player.duration > 0 }
                player.seekTo(NEAR_END_MS)
                pumpUntil { player.currentPosition == NEAR_END_MS }
                player.play()
                var attempts = 0
                while (!player.isPlaying && attempts++ < 50) {
                    fakeClock.advanceTime(100)
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                assertTrue("the current item never actually played", player.isPlaying)

                assertEquals(
                    PlayResult.Started,
                    h.run { bridge.start(StartRequest.Episode(B), StartOrigin.UI) },
                )
                pumpUntil { player.episodeIdOrNull() == B }

                val departure =
                    h.store.calls
                        .last { it.operation == FakePlaySessionStore.Operation.START }
                        .start
                        ?.departure
                assertNotNull("the new start captured no departure for the outgoing item", departure)
                assertEquals(A, departure!!.episodeId)
                assertTrue(
                    "the departure ${departure.positionMs} ms is outside the near-end window",
                    departure.positionMs >= NEAR_END_MS,
                )
                assertTrue(
                    "a near-end item that already played must be marked played on a new start",
                    departure.markPlayed,
                )
            } finally {
                bridge.detach()
                player.release()
            }
        }
    }

    private companion object {
        const val A = 1L
        const val B = 2L

        /** Inside [PlayedRule]'s near-end window. */
        const val NEAR_END_MS = AdapterFixture.DURATION_MS - 5_000
    }
}
