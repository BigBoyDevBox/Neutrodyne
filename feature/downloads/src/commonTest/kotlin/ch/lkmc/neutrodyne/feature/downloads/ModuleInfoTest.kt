// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":feature:downloads", ModuleInfo.PATH)
    }
}
