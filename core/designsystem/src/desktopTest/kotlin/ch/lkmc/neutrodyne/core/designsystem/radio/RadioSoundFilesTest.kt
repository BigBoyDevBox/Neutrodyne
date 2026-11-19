// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import ch.lkmc.neutrodyne.core.designsystem.resources.Res
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 08 Control sounds: every [RadioSound] ships as the short, quiet-edged 48 kHz mono 16-bit PCM
 * WAV that both players load (Android `SoundPool`, the desktop's Java Sound clips), built by
 * tools/sounds/build_sounds.py from media-sources/sounds.
 */
class RadioSoundFilesTest {
    private class Pcm(
        val format: AudioFormat,
        val samples: ShortArray,
    )

    private fun read(sound: RadioSound): Pcm {
        val bytes = runBlocking { Res.readBytes(sound.file) }
        AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val data = stream.readAllBytes()
            val samples = ShortArray(data.size / 2)
            ByteBuffer
                .wrap(data)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(samples)
            return Pcm(stream.format, samples)
        }
    }

    private fun dbfs(sample: Int): Double = 20 * log10(maxOf(sample, 1) / 32768.0)

    @Test
    fun everySoundIsMono48kHz16BitPcm() {
        RadioSound.entries.forEach { sound ->
            val format = read(sound).format
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.encoding, "$sound encoding")
            assertEquals(48_000f, format.sampleRate, "$sound rate")
            assertEquals(16, format.sampleSizeInBits, "$sound bits")
            assertEquals(1, format.channels, "$sound channels")
            assertTrue(!format.isBigEndian, "$sound byte order")
        }
    }

    @Test
    fun everySoundIsShortAudibleAndUnclipped() {
        RadioSound.entries.forEach { sound ->
            val samples = read(sound).samples
            val seconds = samples.size / 48_000.0
            assertTrue(seconds in 0.02..MAX_SECONDS, "$sound lasts $seconds s")
            val peak = dbfs(samples.maxOf { abs(it.toInt()) })
            assertTrue(peak <= PEAK_CEILING_DBFS, "$sound peaks at $peak dBFS")
            assertTrue(peak >= QUIETEST_PEAK_DBFS, "$sound peaks at only $peak dBFS")
        }
    }

    @Test
    fun everySoundStartsAndEndsInSilence() {
        // A cut mid-waveform clicks on its own; the build fades both ends.
        RadioSound.entries.forEach { sound ->
            val samples = read(sound).samples
            val edge = 48 // 1 ms
            val start = samples.take(edge / 4).maxOf { abs(it.toInt()) }
            val end = samples.takeLast(edge).maxOf { abs(it.toInt()) }
            assertTrue(dbfs(start) < EDGE_DBFS, "$sound starts at ${dbfs(start)} dBFS")
            assertTrue(dbfs(end) < EDGE_DBFS, "$sound ends at ${dbfs(end)} dBFS")
        }
    }

    private companion object {
        const val MAX_SECONDS = 0.8
        const val PEAK_CEILING_DBFS = -2.9
        const val QUIETEST_PEAK_DBFS = -30.0
        const val EDGE_DBFS = -50.0
    }
}
