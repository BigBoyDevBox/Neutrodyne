// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals

class RadioSoundCacheTest {
    @Test
    fun replacingACachedSoundPreservesTheBytesAlreadyOpenForDecoding() {
        val directory = Files.createTempDirectory("radio-sounds-test").toFile()
        val original = byteArrayOf(1, 2, 3, 4)
        val replacement = byteArrayOf(5, 6)
        try {
            val file = writeCachedSound(directory, RadioSound.SWITCH_OFF, original)
            file.inputStream().use { decoding ->
                writeCachedSound(directory, RadioSound.SWITCH_OFF, replacement)
                assertContentEquals(original, decoding.readBytes())
                assertContentEquals(replacement, file.readBytes())
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
