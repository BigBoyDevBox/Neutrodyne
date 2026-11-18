// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import ch.lkmc.neutrodyne.core.designsystem.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.MissingResourceException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.UnsupportedAudioFileException

@Composable
public actual fun rememberRadioSounds(enabled: Boolean): RadioSounds {
    if (!enabled) return RadioSounds.Silent
    val player = remember { ClipRadioSounds() }
    LaunchedEffect(player) { player.load() }
    DisposableEffect(player) { onDispose { player.close() } }
    return player
}

/**
 * [RadioSounds] on one Java Sound [Clip] per sound, opened on the default output device. Clips
 * are restarted on a single daemon thread, so a click never waits on the audio system. Without a
 * usable output device (none present, all busy, or a headless machine) [load] stops and the
 * controls stay silent; a sound whose file cannot be read is skipped alone.
 */
internal class ClipRadioSounds(
    private val openClip: () -> Clip = { AudioSystem.getClip() },
) : RadioSounds {
    private val clips = ConcurrentHashMap<RadioSound, Clip>()
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { task -> Thread(task, "radio-sounds").apply { isDaemon = true } }

    @Volatile
    private var closed = false

    suspend fun load() {
        withContext(Dispatchers.IO) {
            for (sound in RadioSound.entries) {
                val bytes =
                    try {
                        Res.readBytes(sound.file)
                    } catch (_: MissingResourceException) {
                        // RadioSoundFilesTest guards the files; a broken package only loses its sound.
                        continue
                    }
                val clip =
                    try {
                        openClip()
                    } catch (_: LineUnavailableException) {
                        return@withContext
                    } catch (_: IllegalArgumentException) {
                        // No mixer offers clips at all.
                        return@withContext
                    } catch (_: SecurityException) {
                        return@withContext
                    }
                try {
                    AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)).use(clip::open)
                } catch (_: LineUnavailableException) {
                    clip.close()
                    return@withContext
                } catch (_: IllegalArgumentException) {
                    // The device takes no clip in this format.
                    clip.close()
                    return@withContext
                } catch (_: UnsupportedAudioFileException) {
                    clip.close()
                    continue
                } catch (_: IOException) {
                    clip.close()
                    continue
                }
                synchronized(clips) {
                    if (closed) {
                        clip.close()
                        return@withContext
                    }
                    clips[sound] = clip
                }
            }
        }
    }

    override fun play(sound: RadioSound) {
        if (closed) return
        val clip = clips[sound] ?: return
        try {
            worker.execute {
                // Restarting the clip cuts a sound still ringing from the previous flick.
                clip.stop()
                clip.framePosition = 0
                clip.start()
            }
        } catch (_: RejectedExecutionException) {
            // Closed between the check and the hand-off.
        }
    }

    /**
     * Ignores later plays at once but closes the clips only after [RELEASE_DELAY_MS], so the
     * sound that switched control sounds off (or any other still ringing) plays out.
     */
    fun close() {
        synchronized(clips) { closed = true }
        try {
            worker.execute {
                Thread.sleep(RELEASE_DELAY_MS)
                clips.values.forEach(Clip::close)
                clips.clear()
            }
        } catch (_: RejectedExecutionException) {
            // Already closed.
        }
        worker.shutdown()
    }

    internal companion object {
        /** Longer than any sound; RadioSoundFilesTest caps them at 0.8 s. */
        const val RELEASE_DELAY_MS = 1_000L
    }
}
