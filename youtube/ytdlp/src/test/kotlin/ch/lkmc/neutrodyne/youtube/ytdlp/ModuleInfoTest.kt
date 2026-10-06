// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":youtube:ytdlp", ModuleInfo.PATH)
    }
}
