// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.feeds

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInfoTest {
    @Test
    fun pathMatchesModule() {
        assertEquals(":feature:feeds", ModuleInfo.PATH)
    }
}
