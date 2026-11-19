// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The desktop player: restarts the right clip off the caller's thread, and never throws. */
class ClipRadioSoundsTest {
    /** A [Clip] that records its calls; `open` reads the stream's format to prove it was a WAV. */
    private class FakeClip(
        val calls: MutableList<String>,
        val started: CountDownLatch? = null,
    ) {
        var openedFrames = -1L
        val clip: Clip =
            Proxy.newProxyInstance(Clip::class.java.classLoader, arrayOf(Clip::class.java)) { _, method, args ->
                when (method.name) {
                    "open" -> {
                        if (args?.singleOrNull() is AudioInputStream) {
                            openedFrames = (args[0] as AudioInputStream).frameLength
                        }
                        calls += "open"
                        null
                    }

                    "start" -> {
                        calls += "start"
                        started?.countDown()
                        null
                    }

                    "setFramePosition" -> {
                        calls += "frame ${args[0]}"
                        null
                    }

                    "stop", "close" -> {
                        calls += method.name
                        null
                    }

                    "isOpen", "isRunning", "isActive" -> {
                        false
                    }

                    "hashCode" -> {
                        System.identityHashCode(this)
                    }

                    "equals" -> {
                        args?.get(0) === this
                    }

                    "toString" -> {
                        "FakeClip"
                    }

                    else -> {
                        null
                    }
                }
            } as Clip
    }

    @Test
    fun aSoundRestartsItsOwnClipFromTheTop() {
        val calls = CopyOnWriteArrayList<String>()
        val started = CountDownLatch(1)
        // The player opens one clip per sound, in RadioSound order.
        val clips =
            RadioSound.entries.map { sound ->
                if (sound == RadioSound.SWITCH_OFF) FakeClip(calls, started) else FakeClip(CopyOnWriteArrayList())
            }
        val queue = ArrayDeque(clips)
        val player = ClipRadioSounds { queue.removeFirst().clip }

        runBlocking { player.load() }
        player.play(RadioSound.SWITCH_OFF)

        assertTrue(started.await(5, TimeUnit.SECONDS), "the clip never started")
        assertEquals(listOf("open", "stop", "frame 0", "start"), calls.toList())
        assertTrue(clips.all { it.openedFrames > 0 }, "every sound opened with its frames")
        player.close()
    }

    /**
     * The real Java Sound path opens a clip for every file where the machine has an output
     * device, and stays silent without one (a headless CI runner). Nothing is played.
     */
    @Test
    fun theRealAudioSystemOpensEveryFileOrStaysSilent() {
        val opened = CopyOnWriteArrayList<Clip>()
        val player =
            ClipRadioSounds {
                AudioSystem.getClip().also(opened::add)
            }

        runBlocking { player.load() }

        assertTrue(opened.size <= RadioSound.entries.size)
        if (opened.size == RadioSound.entries.size) {
            assertTrue(opened.all { it.isOpen }, "a clip refused its WAV")
        }
        player.close()
    }

    @Test
    fun withoutAnOutputDeviceLoadingStopsAndPlayingDoesNothing() {
        var asked = 0
        val player =
            ClipRadioSounds {
                asked++
                throw LineUnavailableException("no device")
            }

        runBlocking { player.load() }
        RadioSound.entries.forEach(player::play)

        assertEquals(1, asked, "loading gives up on the first refusal")
        player.close()
    }

    @Test
    fun closingReleasesTheClipsAndLaterPlaysAreIgnored() {
        val calls = CopyOnWriteArrayList<String>()
        val player = ClipRadioSounds { FakeClip(calls).clip }
        runBlocking { player.load() }

        player.close()
        player.play(RadioSound.KEY_PRESS)
        player.close()

        // The worker drains the close before it stops.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (calls.count { it == "close" } < RadioSound.entries.size && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(RadioSound.entries.size, calls.count { it == "close" })
        assertTrue("start" !in calls, "a closed player played")
    }

    /** Switching control sounds off plays the lever's click, then closes the player: it rings out. */
    @Test
    fun closingLetsTheLastSoundRingOut() {
        val calls = CopyOnWriteArrayList<String>()
        val started = CountDownLatch(1)
        val player = ClipRadioSounds { FakeClip(calls, started).clip }
        runBlocking { player.load() }

        player.play(RadioSound.SWITCH_OFF)
        player.close()

        assertTrue(started.await(5, TimeUnit.SECONDS), "the click never started")
        Thread.sleep(ClipRadioSounds.RELEASE_DELAY_MS / 2)
        assertTrue("close" !in calls, "the click was cut off")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (calls.count { it == "close" } < RadioSound.entries.size && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(RadioSound.entries.size, calls.count { it == "close" })
    }
}
