// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import ch.lkmc.neutrodyne.core.designsystem.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.MissingResourceException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

@Composable
public actual fun rememberRadioSounds(enabled: Boolean): RadioSounds {
    if (!enabled) return RadioSounds.Silent
    val context = LocalContext.current.applicationContext
    val player = remember(context) { SoundPoolRadioSounds(context) }
    LaunchedEffect(player) { player.load() }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/**
 * [RadioSounds] on a [SoundPool] tagged as sonification, so the system volume, silent and vibrate
 * modes govern it and it never takes audio focus from a playing episode. `SoundPool` loads only
 * from files, descriptors or Android resources, so [load] copies each Compose resource into the
 * cache first (overwriting, so an app update's sounds replace the old ones). A sound still
 * decoding, or one whose copy failed, is dropped by `SoundPool.play`.
 */
internal class SoundPoolRadioSounds(
    private val context: Context,
) : RadioSounds {
    private val pool: SoundPool =
        SoundPool
            .Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            ).build()

    private val samples = ConcurrentHashMap<RadioSound, Int>()
    private val lock = Any()
    private var released = false

    /** Copies and queues every sound for decoding; returns early once [release] has run. */
    suspend fun load() {
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, CACHE_DIR)
            for (sound in RadioSound.entries) {
                val file =
                    try {
                        writeCachedSound(dir, sound, Res.readBytes(sound.file))
                    } catch (_: IOException) {
                        // A full or unwritable cache: this sound stays silent.
                        continue
                    } catch (_: SecurityException) {
                        // Access to this cache or resource was denied.
                        continue
                    } catch (_: MissingResourceException) {
                        // RadioSoundFilesTest guards the files; a broken package only loses its sound.
                        continue
                    }
                synchronized(lock) {
                    if (released) return@withContext
                    val sample = pool.load(file.path, 1)
                    if (sample != 0) samples[sound] = sample
                }
            }
        }
    }

    override fun play(sound: RadioSound) {
        val sample = samples[sound] ?: return
        pool.play(sample, VOLUME, VOLUME, PRIORITY, NO_LOOP, NORMAL_RATE)
    }

    /**
     * Ignores later plays and loads at once but releases the pool only after [RELEASE_DELAY_MS],
     * so the sound that switched control sounds off (or any other still ringing) plays out.
     */
    fun release() {
        synchronized(lock) {
            released = true
            samples.clear()
        }
        Handler(Looper.getMainLooper()).postDelayed(pool::release, RELEASE_DELAY_MS)
    }

    private companion object {
        const val CACHE_DIR = "radio-sounds"

        /** Longer than any sound; RadioSoundFilesTest caps them at 0.8 s. */
        const val RELEASE_DELAY_MS = 1_000L

        /** A fast double flick of a switch, or a key and a switch together, both sound. */
        const val MAX_STREAMS = 4
        const val VOLUME = 1f
        const val PRIORITY = 1
        const val NO_LOOP = 0
        const val NORMAL_RATE = 1f
    }
}

internal fun writeCachedSound(
    directory: File,
    sound: RadioSound,
    bytes: ByteArray,
): File {
    val dir = Files.createDirectories(directory.toPath())
    val destination = dir.resolve("${sound.name.lowercase()}.wav")
    // SoundPool may still be decoding the previous file after a quick off/on toggle.
    // Replace the directory entry so an open decoder keeps reading its original bytes.
    val staging = Files.createTempFile(dir, "radio-", ".wav")
    try {
        Files.write(staging, bytes)
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(staging)
    }
    return destination.toFile()
}
